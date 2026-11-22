package com.codemobile.editor

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.widget.Magnifier
import android.widget.OverScroller
import androidx.appcompat.widget.AppCompatEditText
import com.codemobile.files.FileRepository.Lang
import com.codemobile.files.OpenFile
import com.codemobile.keyboard.KeyboardTarget

/**
 * Editor de código: AppCompatEditText con autoindentación básica y resaltado
 * de sintaxis por regex. Ya NO autocompleta pares de brackets/comillas
 * (decisión explícita, ver doc de clase de OpenFile): el único apoyo para
 * brackets es BracketAccumulator, que solo lee el texto real y alimenta la
 * celda de "pendientes" (ver notifyPendingCloser/insertPendingCloser).
 *
 * Los gestos (pinch para indentar) se manejan en el contenedor EditorPaneView,
 * que envuelve a esta vista y tiene acceso al layout completo (gutter + margen)
 * — aquí solo vive la lógica de texto en sí.
 *
 * Implementa KeyboardTarget (ver ese archivo) para que CustomKeyboardView
 * pueda escribir acá sin saber que es específicamente un editor de código;
 * los 4 métodos de la interfaz ya existían con esta firma exacta, así que
 * implementarla no cambia nada de su comportamiento.
 */
class CodeEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatEditText(context, attrs), KeyboardTarget {

    // Debe ser el primer campo: super()'s constructor (EditText.<init>) llama
    // setText() y dispara onSelectionChanged ANTES de que el resto de los
    // inicializadores de esta subclase corran. Cualquier acceso a otra
    // propiedad desde un callback de super() en ese punto crashea con NPE, así
    // que los callbacks chequean este flag primero (true recién al final de init{}).
    private var constructed = false

    var lang: Lang = Lang.PLAIN
        set(value) {
            field = value
            highlighter = SyntaxHighlighter(value)
            autocomplete = Autocomplete(value)
        }

    var openFile: OpenFile? = null
    var onPendingCloserChanged: ((Char?, Int) -> Unit)? = null

    /** Qué hace el drag de 1 dedo sobre el editor (Paso 8, botón dedicado
     *  en ControlBarView.dragModeButton): CURSOR (por defecto) mueve el
     *  cursor/selección, igual que siempre; CONTENT hace scroll del
     *  contenido, lo que antes hacía el gesto de 2 dedos (ya eliminado, ver
     *  onTouchEvent — se decidió no mantener ambos gestos a la vez, el
     *  toggle es la única forma de pasar a modo scroll con 1 dedo). El tap
     *  simple (sin drag) sigue ubicando el cursor sin importar este modo:
     *  solo cambia qué hace MOVER el dedo tras bajarlo. El hold+drag de
     *  selección (startHoldSelect) y la lupa de precisión (showMagnifierAt)
     *  solo tienen sentido en modo CURSOR — ver su armado condicionado en
     *  ACTION_DOWN. */
    enum class DragMode { CURSOR, CONTENT }
    var dragMode: DragMode = DragMode.CURSOR

    /** Invocado al final de CADA edición real de usuario (afterTextChanged
     *  del watcher interno, después de que cualquier paso programático
     *  anidado ya terminó — autoindentación, undo/redo, aceptar una
     *  sugerencia de autocompletado, etc., todos marcan selfEditing=true
     *  mientras hacen su propio insert/delete sobre el Editable). Quien
     *  necesite reaccionar a "el texto cambió" debe escuchar ESTO, nunca un
     *  TextWatcher propio sobre este EditText: un TextWatcher externo ve
     *  TODAS las notificaciones crudas del Editable, incluyendo las de esos
     *  pasos internos — ej. un Enter con autoindentación disparaba DOS
     *  notificaciones (una por el '\n' del usuario, otra por el insert de
     *  indent que handleNewLine hace en medio del procesamiento de la
     *  primera), cada una con el documento en un estado distinto. Fue
     *  justo el bug real que motivó este callback: EditorPaneView contaba
     *  líneas con un TextWatcher crudo y el recuento quedaba mal tras
     *  Enter, pegar texto multilínea, o borrar un salto de línea. */
    var onTextSettled: (() -> Unit)? = null

    /** Autocompletado no predictivo (Autocomplete.kt), reconstruido cada vez
     *  que cambia [lang] para usar las keywords del lenguaje correcto. */
    private var autocomplete = Autocomplete(Lang.PLAIN)

    /** Invocado tras cada cambio de texto relevante con la lista de sugerencias
     *  para el prefijo actual (vacía si no corresponde mostrar nada). Quien
     *  escuche (EditorActivity) decide mostrar/ocultar SuggestionsBarView. */
    var onSuggestionsChanged: ((List<String>) -> Unit)? = null

    /** Invocado en cada toque sobre el editor. No indica por sí solo si el
     *  teclado propio está oculto — eso lo decide quien escuche este
     *  callback (EditorActivity, que sabe el estado de CustomKeyboardView) —
     *  pero sirve como el "tocar el área de texto reabre el teclado" que
     *  antes resolvía gratis el foco + showSoftInputOnFocus del IME de
     *  sistema. Con showSoftInputOnFocus=false ya no hay ninguna señal
     *  equivalente, así que hace falta exponer esta. */
    var onEditorTouched: (() -> Unit)? = null

    /** Cuando está activo (toggle "Select" de la barra de controles), un tap
     *  sobre el texto extiende la selección hasta el punto tocado en vez de
     *  simplemente mover el cursor ahí. El punto de inicio queda fijo en
     *  [selectionAnchor] desde que se activa (o desde el primer toque/flecha),
     *  y cada toque o flecha posterior solo mueve el otro extremo: así la
     *  selección no "salta" usando selectionEnd como ancla cambiante. */
    var selectModeActive: Boolean = false
        set(value) {
            field = value
            selectionAnchor = if (value) selectionStart else -1
        }

    /** Extremo fijo de la selección mientras selectModeActive está activo, o -1
     *  si todavía no se fijó (se fija con el primer toque/flecha tras activar). */
    var selectionAnchor: Int = -1

    private var highlighter = SyntaxHighlighter(Lang.PLAIN)
    private var selfEditing = false

    /** Historial de undo/redo, por instancia de CodeEditText
     *  (= por pestaña, ya que cada OpenFile renderiza la suya propia en
     *  renderActiveEditor — ver EditorActivity). Vive en memoria nada más:
     *  no se serializa a OpenFile ni a disco, se pierde si la Activity muere.
     *  Snapshots de texto completo (no diffs): simple y correcto, el tamaño
     *  típico de archivo de este editor hace que el costo sea despreciable.
     *
     *  Agrupación de pasos: NO es por tecla individual (undo por cada letra
     *  se siente tedioso en código) ni por diff real (mucho más complejo de
     *  lo que amerita este MVP) — en vez de eso, cada entrada de afterTextChanged
     *  decide si el cambio se "fusiona" con el último snapshot apilado (tecleo
     *  contiguo de inserción pura, sin pausa larga) o si abre un paso nuevo
     *  (borrado, pegado, indentado, reemplazo de sugerencia, o más de
     *  MERGE_WINDOW_MS de inactividad). */
    private val undoHistory = UndoHistory()

    /** Snapshot de un paso de undo/redo (ver UndoHistory más abajo). Vive a
     *  nivel de CodeEditText, no dentro de UndoHistory: Kotlin no permite
     *  declarar una data class ni un companion object directamente dentro
     *  de una inner class (cuenta como clase local, que no lo admite).
     *  Misma razón por la que MERGE_WINDOW_MS vive en el companion object
     *  de CodeEditText en vez de en UndoHistory. */
    private data class UndoSnapshot(val text: String, val cursor: Int)

    private inner class UndoHistory {
        private val undoStack = ArrayDeque<UndoSnapshot>()
        private val redoStack = ArrayDeque<UndoSnapshot>()
        private var lastPushAt = 0L
        private var lastEditWasPlainInsertAt = -1 // posición donde terminaría una inserción contigua, o -1

        /** Primer snapshot, al cargar un archivo en el editor (ver setText en
         *  EditorActivity.renderActiveEditor) — sin esto, el primer undo no
         *  tendría a qué volver. Limpia redoStack: cargar contenido nuevo
         *  invalida cualquier redo pendiente de la pestaña anterior. */
        fun reset(text: String, cursor: Int) {
            undoStack.clear()
            redoStack.clear()
            undoStack.addLast(UndoSnapshot(text, cursor))
            lastEditWasPlainInsertAt = -1
        }

        /** Registra el estado posterior a una edición. [isPlainContiguousInsert]
         *  indica si esta edición fue una inserción pura de caracteres
         *  consecutivos respecto a la anterior (se intenta fusionar con el
         *  tope en vez de apilar un paso nuevo); cualquier otro tipo de
         *  cambio (borrado, pegado, indentado, sugerencia, Enter) siempre
         *  abre un paso nuevo. */
        fun push(text: String, cursor: Int, isPlainContiguousInsert: Boolean) {
            val now = System.currentTimeMillis()
            redoStack.clear()
            val withinMergeWindow = now - lastPushAt < MERGE_WINDOW_MS
            val canMerge = isPlainContiguousInsert && withinMergeWindow &&
                lastEditWasPlainInsertAt == cursor - 1 && undoStack.isNotEmpty()
            if (canMerge) {
                undoStack.removeLast()
            }
            undoStack.addLast(UndoSnapshot(text, cursor))
            lastPushAt = now
            lastEditWasPlainInsertAt = if (isPlainContiguousInsert) cursor else -1
        }

        fun canUndo(): Boolean = undoStack.size > 1
        fun canRedo(): Boolean = redoStack.isNotEmpty()

        /** Devuelve el snapshot al que hay que volver, o null si no hay nada
         *  que deshacer (siempre queda al menos el snapshot inicial en el
         *  fondo de undoStack, que nunca se saca). */
        fun undo(): UndoSnapshot? {
            if (!canUndo()) return null
            val current = undoStack.removeLast()
            redoStack.addLast(current)
            lastEditWasPlainInsertAt = -1
            return undoStack.last()
        }

        fun redo(): UndoSnapshot? {
            if (!canRedo()) return null
            val next = redoStack.removeLast()
            undoStack.addLast(next)
            lastEditWasPlainInsertAt = -1
            return next
        }
    }

    /** Notifica a quien escuche (EditorActivity, para alpha de los botones
     *  ↶/↷) que la disponibilidad de undo/redo pudo haber cambiado. */
    var onUndoRedoAvailabilityChanged: ((canUndo: Boolean, canRedo: Boolean) -> Unit)? = null

    private fun notifyUndoRedoAvailability() {
        onUndoRedoAvailabilityChanged?.invoke(undoHistory.canUndo(), undoHistory.canRedo())
    }

    /** Debe llamarse después de setText() al cargar/cambiar de archivo (ver
     *  EditorActivity.renderActiveEditor): arranca el historial desde el
     *  contenido recién cargado, para que el primer undo no borre todo el
     *  archivo por error. */
    fun resetUndoHistory() {
        val editable = text ?: return
        undoHistory.reset(editable.toString(), selectionStart.coerceIn(0, editable.length))
        notifyUndoRedoAvailability()
    }

    fun undo() {
        val snapshot = undoHistory.undo() ?: return
        applyHistorySnapshot(snapshot.text, snapshot.cursor)
        notifyUndoRedoAvailability()
    }

    fun redo() {
        val snapshot = undoHistory.redo() ?: return
        applyHistorySnapshot(snapshot.text, snapshot.cursor)
        notifyUndoRedoAvailability()
    }

    /** Paso 7 (tecla D del drag-abajo): duplica la línea donde está el
     *  cursor (incluido su salto de línea final, si lo tiene — la última
     *  línea del archivo no tiene uno, y la copia tampoco lo agrega),
     *  insertando la copia justo debajo. El cursor queda en la copia, en la
     *  misma columna relativa que tenía. Ignora cualquier selección activa
     *  a propósito (opera siempre sobre la línea del cursor, no sobre el
     *  rango seleccionado) — distinto de insertAtCursor, que si reemplazara
     *  una selección perdería la noción de "línea actual" que este comando
     *  necesita. */
    fun duplicateCurrentLine() {
        val layout = layout ?: return
        val editable = text ?: return
        val cursor = selectionStart.coerceIn(0, editable.length)
        val line = layout.getLineForOffset(cursor)
        val lineStart = layout.getLineStart(line)
        val lineEnd = layout.getLineEnd(line)
        val col = cursor - lineStart
        val lineText = editable.substring(lineStart, lineEnd)
        val hasTrailingNewline = lineText.endsWith("\n")
        val toInsert = if (hasTrailingNewline) lineText else "\n$lineText"
        editable.insert(lineEnd, toInsert)
        val newCursor = if (hasTrailingNewline) lineEnd + lineText.length + col else lineEnd + 1 + col
        setSelection(newCursor)
    }

    /** Paso 7 (tecla K del drag-abajo, Ctrl+K): borra por completo la línea
     *  donde está el cursor, incluido su salto de línea (el de la línea
     *  misma si lo tiene; si es la última línea del archivo y no tiene uno,
     *  se borra en su lugar el salto de línea ANTERIOR, para no dejar dos
     *  líneas vacías pegadas donde antes había una sola línea vacía al
     *  final). El cursor queda al inicio de lo que antes era la línea
     *  siguiente (o al final del archivo si no quedó ninguna). Mismo
     *  criterio que duplicateCurrentLine(): ignora cualquier selección
     *  activa, siempre opera sobre la línea del cursor. */
    fun deleteCurrentLine() {
        val layout = layout ?: return
        val editable = text ?: return
        val cursor = selectionStart.coerceIn(0, editable.length)
        val line = layout.getLineForOffset(cursor)
        val lineStart = layout.getLineStart(line)
        val lineEnd = layout.getLineEnd(line)
        val isLastLineWithoutNewline = lineEnd == editable.length && (lineEnd == lineStart || editable[lineEnd - 1] != '\n')
        if (isLastLineWithoutNewline && lineStart > 0) {
            // Última línea del archivo, sin salto final propio: se borra
            // desde el '\n' anterior (el que separaba esta línea de la
            // previa) en vez de desde lineStart, así la línea previa pasa a
            // ser la última sin dejar un '\n' colgante de más.
            editable.delete(lineStart - 1, lineEnd)
            setSelection(lineStart - 1)
        } else {
            editable.delete(lineStart, lineEnd)
            setSelection(lineStart.coerceAtMost(editable.length))
        }
    }

    /** Restaura texto+cursor sin pasar por el TextWatcher normal (selfEditing
     *  = true, igual que cualquier otra edición programática de esta clase).
     *
     *  BUG encontrado (acumulador "pendiente para siempre" sin autocierre):
     *  como selfEditing=true salta afterTextChanged por completo (ver
     *  TextWatcher más abajo), y este método no llamaba notifyPendingCloser()
     *  tras reemplazar el texto, undo/redo podía dejar la celda mostrando el
     *  pending de ANTES de deshacer/rehacer — desincronizado del texto real
     *  resultante — hasta la próxima edición manual que sí pase por el
     *  watcher. Esto no depende para nada del viejo mecanismo de autocierre
     *  (ya eliminado): es simplemente que este camino de edición programática
     *  nunca refrescaba la celda. */
    private fun applyHistorySnapshot(newText: String, cursor: Int) {
        val editable = text ?: return
        selfEditing = true
        editable.replace(0, editable.length, newText)
        setSelection(cursor.coerceIn(0, newText.length))
        selfEditing = false
        highlightAll()
        notifyPendingCloser()
        // Mismo fast-path de longitud que afterTextChanged (ver su doc) —
        // newText ya viene como String sin costo extra acá, pero la
        // comparación != sigue siendo cara en archivos grandes sin este
        // atajo.
        openFile?.let { of -> of.isDirty = newText.length != of.savedContent.length || newText != of.savedContent }
        // Mismo motivo que notifyPendingCloser arriba: selfEditing=true
        // salta afterTextChanged por completo, así que onTextSettled (ver
        // su doc) tampoco se dispararía solo. Undo/redo cambia el texto
        // tanto como cualquier otra edición — quien escuche onTextSettled
        // (ej. EditorPaneView, para los números de línea) necesita
        // enterarse también acá.
        onTextSettled?.invoke()
    }

    /** Recalcula el indicador de "cierre pendiente" (celda dedicada de la
     *  barra de controles) a partir de BracketAccumulator.visiblePending
     *  sobre el texto ACTUAL completo y la posición actual del cursor. Sin
     *  autocierre (ver doc de clase de OpenFile/BracketAccumulator), esto
     *  es "qué falta cerrar en el texto real tal cual está, descartando lo
     *  que ya quedó resuelto fuera de orden antes del cursor" — ver doc de
     *  clase de BracketAccumulator (separación global/roto). Por depender
     *  del cursor, hay que volver a llamarla también cuando el cursor se
     *  mueve, no solo cuando el texto cambia (ver onSelectionChanged).
     *  Pública para que EditorActivity la invoque al renderizar una
     *  pestaña recién cargada (ver renderActiveEditor).
     *
     *  El conteo mostrado usa BracketAccumulator.pendingCount, NO
     *  visible.size: visiblePending colapsa por TIPO de cierre (.distinct(),
     *  correcto para decidir qué sugerir), así que "(((" daba size=1 en vez
     *  de 3 — pendingCount cuenta los niveles reales, sin tocar esa
     *  lógica. */
    fun notifyPendingCloser() {
        val editable = text ?: return
        val visible = BracketAccumulator.visiblePending(editable, selectionStart, bracketRestartIndex, lastKnownCursorLine)
        val topCloser = visible.firstOrNull()
        val count = if (topCloser != null) {
            BracketAccumulator.pendingCount(editable, selectionStart, topCloser, bracketRestartIndex, lastKnownCursorLine)
        } else {
            0
        }
        onPendingCloserChanged?.invoke(topCloser, count)
    }

    /** Índice de "puntos de reinicio" de BracketAccumulator (ver su doc de
     *  clase sobre el bug de rendimiento corregido): se puebla una sola vez
     *  al abrir el archivo (highlightAll) y se invalida desde la línea
     *  tocada en cada edición (ver invalidateBracketRestartIndexFrom) —
     *  mismo patrón que el índice de Autocomplete, aplicado acá para
     *  acelerar el lado IZQUIERDO (desde el inicio del archivo hasta el
     *  cursor) del cálculo de brackets/comillas pendientes. El lado DERECHO
     *  (desde el cursor hasta el final) sigue recorriendo el resto del
     *  archivo tal cual — no hay forma de cachear eso sin saber de
     *  antemano dónde va a estar el cursor. */
    private val bracketRestartIndex = BracketRestartIndex()

    // ---- Mantenimiento del índice incremental de Autocomplete.kt (ver su
    // doc de clase sobre el bug de rendimiento corregido) ----

    /** Línea (0-based) donde estaba el cursor la última vez que se confirmó
     *  su posición — se compara contra la línea actual en cada punto donde
     *  el cursor puede haberse movido (edición o selección), para saber si
     *  "abandonó" una línea y hay que refrescar el índice de esa línea
     *  nada más (ver updateAutocompleteIndexIfLineChanged). -1 = sin
     *  inicializar todavía (antes del primer cálculo real). */
    private var lastKnownCursorLine: Int = -1

    /** Contenido de [lastKnownCursorLine] la última vez que se la registró
     *  en el índice — para poder llamar updateLineOnExit con el texto
     *  correcto de la línea que se deja atrás, sin tener que re-derivar sus
     *  límites después de que el cursor ya se movió a otra línea. */
    private var lastKnownCursorLineContent: String = ""

    /** Número de línea 0-based de la posición [offset] dentro de [full]:
     *  cuántos '\n' hay antes de esa posición. Mismo criterio que
     *  rehighlightCurrentLine para encontrar límites de línea, pero
     *  devolviendo el índice en vez del rango de offsets. */
    private fun lineIndexOf(full: String, offset: Int): Int {
        val safe = offset.coerceIn(0, full.length)
        var count = 0
        var i = 0
        while (i < safe) {
            if (full[i] == '\n') count++
            i++
        }
        return count
    }

    /** [lineStart, lineEnd) de la línea [lineIndex] (0-based) dentro de
     *  [full], buscados por conteo de '\n' en vez de desde la posición del
     *  cursor (a diferencia de rehighlightCurrentLine): hace falta poder
     *  pedir los límites de CUALQUIER línea, no solo la del cursor actual,
     *  porque este método se usa para releer la línea que el cursor recién
     *  abandonó. */
    private fun lineBoundsOf(full: String, lineIndex: Int): IntRange {
        var start = 0
        var currentLine = 0
        while (currentLine < lineIndex) {
            val nextBreak = full.indexOf('\n', start)
            if (nextBreak == -1) return full.length until full.length
            start = nextBreak + 1
            currentLine++
        }
        val end = full.indexOf('\n', start).let { if (it == -1) full.length else it }
        return start until end
    }

    /** Sincroniza lastKnownCursorLine/[autocomplete]'s index con la posición
     *  ACTUAL del cursor: si sigue en la misma línea que la última vez, no
     *  hace nada (caso común: tipeo normal dentro de una línea, costo cero);
     *  si cambió de línea, refresca en el índice el contenido de la línea
     *  que se deja atrás (updateLineOnExit) y actualiza el registro de
     *  "línea conocida" a la nueva posición. Debe llamarse después de que
     *  tanto el texto como selectionStart ya reflejan el estado posterior a
     *  cualquier edición — ver call sites en el watcher y onSelectionChanged. */
    private fun updateAutocompleteIndexIfLineChanged() {
        val full = text?.toString() ?: return
        val newLine = lineIndexOf(full, selectionStart)
        if (newLine == lastKnownCursorLine) return
        if (lastKnownCursorLine != -1) {
            autocomplete.updateLineOnExit(lastKnownCursorLine, lastKnownCursorLineContent)
        }
        lastKnownCursorLine = newLine
        val bounds = lineBoundsOf(full, newLine)
        lastKnownCursorLineContent = full.substring(bounds.first, bounds.last + 1)
    }

    /** Si la edición que acaba de terminar insertó o eliminó líneas
     *  completas (Enter, backspace que fusiona dos líneas, pegado/borrado
     *  multilínea), renumera el índice de Autocomplete para que sus
     *  entradas sigan apuntando a la línea correcta — pura aritmética de
     *  claves, sin volver a escanear contenido (ver
     *  Autocomplete.shiftLinesInsertedAt/shiftLinesRemovedAt). Si el conteo
     *  neto de líneas no cambió (el caso común: tipeo normal sin Enter), no
     *  hace nada.
     *
     *  [lastKnownCursorLine] también se corrige acá: si apuntaba a una línea
     *  por debajo del punto de la edición, su número ya no es válido tras el
     *  shift, y actualizarlo a mano evita que la próxima llamada a
     *  updateAutocompleteIndexIfLineChanged compare contra un índice de
     *  línea obsoleto y dispare un updateLineOnExit con la línea equivocada. */
    private fun shiftAutocompleteIndexForLineCountChange(editStartLine: Int, newlinesRemoved: Int, newlinesAdded: Int) {
        val delta = newlinesAdded - newlinesRemoved
        if (delta == 0) return
        if (delta > 0) {
            autocomplete.shiftLinesInsertedAt(editStartLine + 1, delta)
        } else {
            autocomplete.shiftLinesRemovedAt(editStartLine + 1, -delta)
        }
        if (lastKnownCursorLine > editStartLine) {
            lastKnownCursorLine += delta
        }
    }

    /** Invalida en [bracketRestartIndex] todo punto de reinicio en o después
     *  de [editStartLine] — a diferencia de shiftAutocompleteIndexForLineCountChange,
     *  esto se llama SIEMPRE que el texto cambia, no solo cuando cambia el
     *  conteo de líneas: una edición dentro de una sola línea (sin Enter de
     *  por medio) puede igual agregar o quitar un bracket/comilla, lo cual
     *  invalida cualquier punto de reinicio que dependiera de ese tramo. Ver
     *  BracketRestartIndex.invalidateFrom: barato (aritmética sobre las
     *  claves ya cacheadas, no vuelve a tocar el texto), y los puntos
     *  descartados se recalculan solos, perezosamente, la próxima vez que
     *  leftStack los necesite. */
    private fun invalidateBracketRestartIndexFrom(editStartLine: Int) {
        bracketRestartIndex.invalidateFrom(editStartLine)
    }

    private val watcher = object : TextWatcher {
        // Rango [start, start+count) de la inserción actual, SOLO cuando abarca
        // más de una línea (pegado multilínea, o cualquier inserción de más de
        // un carácter que incluya '\n'). rehighlightCurrentLine (usado en el
        // resto de afterTextChanged) solo repinta la línea del cursor, así que
        // un pegado de varias líneas quedaba sin resaltar salvo la última —
        // las demás no recibían resaltado hasta tocarlas (cerrar/reabrir el
        // archivo, por ejemplo). Con este rango, afterTextChanged resalta cada
        // línea tocada por la inserción en vez de solo la del cursor.
        private var pastedMultilineRange: IntRange? = null
        // Para el historial de undo/redo: true si este ciclo de watcher es una
        // inserción pura de UN carácter (sin antes borrar nada). Se usa en
        // afterTextChanged para decidir si el snapshot se fusiona con el
        // anterior (tecleo contiguo) o abre un paso nuevo (ver UndoHistory.push).
        private var isPlainSingleCharInsert = false

        // Línea (0-based) donde empieza el tramo reemplazado, y cuántos '\n'
        // tenía ESE tramo antes de la edición — capturados en
        // beforeTextChanged porque es el único momento en que el texto viejo
        // todavía existe. [editNewlinesAdded] se completa después, en
        // onTextChanged, con los '\n' del tramo nuevo. Juntos permiten saber,
        // en afterTextChanged, si esta edición insertó o eliminó líneas
        // completas y renumerar el índice de Autocomplete en vez de
        // recalcularlo entero (ver shiftAutocompleteIndexForLineCountChange).
        private var editStartLine = 0
        private var editNewlinesRemoved = 0
        private var editNewlinesAdded = 0

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            if (selfEditing || s == null) return
            textChangeInFlight = true
            editStartLine = lineIndexOf(s.toString(), start)
            editNewlinesRemoved = s.subSequence(start, start + count).count { it == '\n' }
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            if (selfEditing || s == null) return
            isPlainSingleCharInsert = count == 1 && before == 0
            editNewlinesAdded = s.subSequence(start, start + count).count { it == '\n' }
            // Pegado (u otra inserción) de más de una línea de una sola vez:
            // se guarda el rango para que afterTextChanged resalte cada línea
            // tocada, no solo la del cursor (ver pastedMultilineRange arriba).
            pastedMultilineRange = if (count > 1 && s.subSequence(start, start + count).contains('\n')) {
                start until (start + count)
            } else {
                null
            }
            // Detecta Enter para aplicar autoindentación. Ya no hay autocierre
            // del que ocuparse acá (ver doc de clase de OpenFile): no hace
            // falta ningún shift de tracking, solo disparar handleNewLine.
            if (count == 1 && before == 0 && s[start] == '\n') {
                handleNewLine(start)
            }
        }

        override fun afterTextChanged(editable: Editable?) {
            if (selfEditing || editable == null) return

            val multilineRange = pastedMultilineRange
            if (multilineRange != null) {
                pastedMultilineRange = null
                // El rango capturado en onTextChanged es sobre el texto YA
                // insertado (afterTextChanged corre después), así que sus
                // índices siguen siendo válidos directamente contra `editable`.
                rehighlightRange(editable, multilineRange.first, multilineRange.last + 1)
            } else {
                rehighlightCurrentLine(editable)
            }
            // Invalida el índice de puntos de reinicio de BracketAccumulator:
            // cualquier punto cacheado en o después de editStartLine pudo
            // quedar desactualizado por esta edición (agregó/quitó un
            // bracket o comilla) — ver doc de invalidateBracketRestartIndexFrom.
            // Se llama siempre, sin condicionarlo a si cambió el conteo de
            // líneas (a diferencia de shiftAutocompleteIndexForLineCountChange,
            // justo abajo).
            invalidateBracketRestartIndexFrom(editStartLine)
            // Cualquier edición puede cambiar cuál es la línea más larga del
            // documento (agregar/quitar texto en cualquier línea) — ver doc
            // de cachedMaxScrollX. Se invalida siempre, sin intentar
            // detectar si la línea tocada era o no la más larga: esa
            // comprobación sería tan cara como el propio recorrido que el
            // caché evita.
            invalidateMaxScrollXCache()
            // Si esta edición insertó o eliminó líneas completas, renumera
            // el índice de Autocomplete, y sincroniza lastKnownCursorLine a
            // la posición real post-edición del cursor — AMBAS cosas antes
            // de notifyPendingCloser, que depende de lastKnownCursorLine
            // para encontrar el punto de reinicio correcto (ver doc de
            // BracketAccumulator.leftStack: pasarle una línea vieja no daría
            // un resultado incorrecto, pero sí uno menos óptimo).
            shiftAutocompleteIndexForLineCountChange(editStartLine, editNewlinesRemoved, editNewlinesAdded)
            updateAutocompleteIndexIfLineChanged()
            // notifyPendingCloser recorre el texto (desde el punto de
            // reinicio más cercano hasta el cursor, ver doc de clase de
            // BracketAccumulator), sin ningún estado propio que mantener
            // sincronizado más allá de lo ya sincronizado arriba — se llama
            // siempre, sin condicionarlo a qué tipo de edición fue.
            notifyPendingCloser()
            // Pendiente reportado: escribir/borrar en archivos grandes era
            // muy lento. Dos causas en esta función, ambas por tecla (no
            // solo al abrir):
            // 1) Esta línea y undoHistory.push llamaban editable.toString()
            //    POR SEPARADO, cada una reconstruyendo el documento entero
            //    como String nuevo — se unifica en currentText, una sola
            //    reconstrucción reusada por ambos usos.
            // 2) isDirty comparaba ese String completo contra savedContent
            //    con != (recorre carácter por carácter en el peor caso,
            //    ver doc de OpenFile.isDirty: la comparación de CONTENIDO,
            //    no de "hubo un evento", es intencional para que undo/redo
            //    hasta el contenido exacto guardado no quede marcado sucio
            //    — no se puede simplificar a una bandera booleana sin
            //    perder eso). Fast-path barato que cubre la enorme mayoría
            //    de ediciones reales (insertar o borrar algo cambia el
            //    largo): si currentText.length ya difiere de
            //    savedContent.length, es sucio sin necesidad de comparar
            //    ningún contenido — solo cuando los largos COINCIDEN (caso
            //    raro: reemplazar un carácter por otro, o deshacer/rehacer
            //    hasta calzar exacto) se paga la comparación completa.
            val currentText = editable.toString()
            openFile?.let { of ->
                of.isDirty = currentText.length != of.savedContent.length || currentText != of.savedContent
            }
            textChangeInFlight = false
            updateSuggestions()
            undoHistory.push(currentText, selectionEnd, isPlainContiguousInsert = isPlainSingleCharInsert)
            notifyUndoRedoAvailability()
            onTextSettled?.invoke()
        }
    }

    /** Palabra (identificador) inmediatamente antes del cursor, sin incluirlo,
     *  o null si el cursor no está pegado al final de un identificador
     *  (p.ej. justo después de un espacio, paréntesis, o al medio de una
     *  selección) — o si está en medio de uno (ver chequeo de la derecha
     *  más abajo). Es la "prefix" que espera Autocomplete.suggestionsFor.
     *
     *  Sin el chequeo a la derecha, un cursor en medio de una palabra YA
     *  completa (p.ej. "cl|ass", cursor entre 'l' y 'a') devolvía "cl" como
     *  si fuera un prefijo real a completar, mostrando sugerencias (como
     *  "class", que coincide por prefijo) para algo que el usuario no está
     *  escribiendo — ya está completo, el cursor solo pasó por ahí al
     *  editar o navegar. Si el carácter inmediatamente a la derecha del
     *  cursor es también de identificador, no hay prefijo que completar. */
    private fun currentPrefixRange(): IntRange? {
        val editable = text ?: return null
        val cursor = selectionStart
        if (cursor != selectionEnd || cursor <= 0) return null
        if (cursor < editable.length && isIdentifierChar(editable[cursor])) return null
        var start = cursor
        while (start > 0 && isIdentifierChar(editable[start - 1])) start--
        if (start == cursor) return null
        return start until cursor
    }

    private fun isIdentifierChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /** true si el cursor está justo después de un espacio/tab/salto de línea,
     *  o al inicio del archivo — el único momento en que tiene sentido el
     *  fallback de mostFrequentIdentifiers (ver updateSuggestions): recién
     *  arrancando una palabra nueva, sin nada todavía escrito que filtrar.
     *  Deliberadamente NO cubre otros "sin prefijo" como cursor tras '(' o
     *  ',' — ahí no se está por escribir una palabra suelta. */
    private fun isAfterWhitespaceOrStart(): Boolean {
        val editable = text ?: return false
        val cursor = selectionStart
        if (cursor != selectionEnd) return false
        if (cursor == 0) return true
        return editable[cursor - 1].let { it == ' ' || it == '\t' || it == '\n' }
    }

    /** Recalcula sugerencias para la palabra actual y notifica [onSuggestionsChanged].
     *  No hace nada si no hay un prefijo válido (cursor no pegado a un identificador),
     *  salvo el fallback de palabras frecuentes justo tras espacio/tab/salto de línea. */
    private fun updateSuggestions() {
        val callback = onSuggestionsChanged ?: return
        val editable = text ?: return
        val range = currentPrefixRange()
        if (range == null) {
            // Arrancando una palabra nueva (sin nada aún que filtrar): cae a
            // los identificadores más usados en el archivo (ver
            // Autocomplete.mostFrequentIdentifiers), en vez de dejar la
            // barra en blanco. Cualquier otro "sin prefijo" (cursor tras
            // '(', ',', etc.) sigue sin mostrar nada, como antes.
            callback(if (isAfterWhitespaceOrStart()) autocomplete.mostFrequentIdentifiers(editable.toString()) else emptyList())
            return
        }
        val prefix = editable.substring(range.first, range.last + 1)
        callback(autocomplete.suggestionsFor(prefix, editable.toString()))
    }

    /** Reemplaza la palabra actual (el mismo prefijo usado para calcular las
     *  sugerencias) por [replacement], dejando el cursor justo después. Usado
     *  al tocar un chip de SuggestionsBarView. No hace nada si el prefijo ya
     *  no es válido (p.ej. el cursor se movió entretanto). */
    fun replaceCurrentPrefix(replacement: String) {
        val editable = text ?: return
        val range = currentPrefixRange() ?: return
        selfEditing = true
        editable.replace(range.first, range.last + 1, replacement)
        selfEditing = false
        setSelection(range.first + replacement.length)
        rehighlightCurrentLine(editable)
        // Mismo motivo: selfEditing=true también saltó el watcher que
        // normalmente mantiene al día tanto el índice de Autocomplete como
        // el de puntos de reinicio de BracketAccumulator. El reemplazo
        // (aceptar una sugerencia) nunca cruza un salto de línea
        // (currentPrefixRange ya garantiza que el prefijo está dentro de UNA
        // línea), así que no hace falta ningún shift de índices de línea —
        // alcanza con refrescar el contenido cacheado de la línea actual a
        // mano. updateAutocompleteIndexIfLineChanged primero por si
        // lastKnownCursorLine todavía fuera -1 (p.ej. esta es la primera
        // interacción tras abrir el archivo): sin esto, lineBoundsOf(-1) y
        // updateLineOnExit(-1, ...) corromperían el índice con una entrada
        // de línea inválida.
        updateAutocompleteIndexIfLineChanged()
        lastKnownCursorLineContent = editable.toString().let { full ->
            lineBoundsOf(full, lastKnownCursorLine).let { bounds -> full.substring(bounds.first, bounds.last + 1) }
        }
        autocomplete.updateLineOnExit(lastKnownCursorLine, lastKnownCursorLineContent)
        // La sugerencia aceptada puede incluir brackets/comillas: invalida
        // el punto de reinicio de esta línea (y los posteriores, aunque acá
        // nunca hay posteriores afectados porque no cruza salto de línea)
        // ANTES de notifyPendingCloser, que depende de lastKnownCursorLine
        // (ya sincronizado arriba) para encontrar el punto de reinicio
        // correcto — mismo orden que en afterTextChanged del watcher.
        invalidateBracketRestartIndexFrom(lastKnownCursorLine)
        notifyPendingCloser()
        openFile?.let { it.isDirty = editable.toString() != it.savedContent }
        updateSuggestions()
        // selfEditing evita que esto pase por el TextWatcher normal, así que
        // el push al historial no ocurre solo — se hace a mano. Paso atómico
        // propio (nunca se fusiona con tecleo anterior o posterior).
        undoHistory.push(editable.toString(), selectionEnd, isPlainContiguousInsert = false)
        notifyUndoRedoAvailability()
        onTextSettled?.invoke()
    }

    init {
        addTextChangedListener(watcher)
        // El teclado es ahora CustomKeyboardView (vista propia dentro de la app),
        // no el IME del sistema: nunca debe solicitarse el teclado de sistema al
        // enfocar este EditText, tanto con tap como con foco programático.
        showSoftInputOnFocus = false
        constructed = true
    }

    /** Punto único de inserción de texto en el cursor (o reemplazo de la
     *  selección activa, si hay una). Usado por CustomKeyboardView y por
     *  cualquier otro control que escriba en el editor (antes duplicado en
     *  ControlBarView.insertAtCursor); todo pasa por el TextWatcher existente,
     *  así que autoindent sigue funcionando igual sin importar el origen de
     *  la pulsación. */
    override fun insertAtCursor(s: String) {
        val editable = text ?: return
        val start = selectionStart.coerceIn(0, editable.length)
        val end = selectionEnd.coerceIn(0, editable.length)
        if (start != end) {
            editable.replace(minOf(start, end), maxOf(start, end), s)
            setSelection(minOf(start, end) + s.length)
        } else {
            // editable.insert() dispara el TextWatcher de forma sincrónica
            // antes de retornar. Si lo insertado es un '\n', el watcher llama
            // a handleNewLine(), que puede insertar la indentación automática
            // y ya mueve el cursor a donde corresponde (después del indent).
            // Por eso se registra la posición ANTES de insertar y, si el
            // watcher ya movió el cursor a otro lado durante el insert (lo
            // cual indica que ya decidió la posición final él mismo), se
            // respeta esa posición en vez de sobreescribirla con
            // start + s.length sin condición.
            val cursorBefore = selectionStart
            editable.insert(start, s)
            val expectedIfUntouched = start + s.length
            val watcherAlreadyMovedCursor = selectionStart != cursorBefore && selectionStart != expectedIfUntouched
            if (!watcherAlreadyMovedCursor) {
                setSelection(expectedIfUntouched)
            }
            // Si watcherAlreadyMovedCursor es true, el watcher ya dejó el
            // cursor en una posición propia (p.ej. después del indent de
            // autoindent, vía handleNewLine): no se toca.
        }
    }

    /** Borra un carácter a la izquierda del cursor (o la selección activa),
     *  equivalente a Backspace. */
    override fun backspaceAtCursor() {
        val editable = text ?: return
        val start = selectionStart.coerceIn(0, editable.length)
        val end = selectionEnd.coerceIn(0, editable.length)
        if (start != end) {
            editable.delete(minOf(start, end), maxOf(start, end))
            setSelection(minOf(start, end))
        } else if (start > 0) {
            editable.delete(start - 1, start)
            setSelection(start - 1)
        }
    }

    /** Borra una "palabra" completa a la izquierda del cursor (modo
     *  acelerado de borrado continuo, tras 3s de hold sobre ⌫). Si hay
     *  selección, se comporta igual que [backspaceAtCursor] (la borra
     *  entera). Sin selección: primero consume hacia la izquierda toda
     *  racha contigua de separadores (cualquier carácter NO alfanumérico:
     *  espacio, tab, salto de línea, puntuación, símbolos), y luego, si
     *  queda algo detrás, consume la racha contigua de caracteres
     *  alfanuméricos (la "palabra" en sí). Deja de
     *  borrar separadores en cuanto encuentra el primer alfanumérico, y deja
     *  de borrar alfanuméricos en cuanto encuentra el primer separador o el
     *  inicio del texto — nunca cruza de una racha a otra del mismo tipo. */
    override fun backspaceWordAtCursor() {
        val editable = text ?: return
        val start = selectionStart.coerceIn(0, editable.length)
        val end = selectionEnd.coerceIn(0, editable.length)
        if (start != end) {
            editable.delete(minOf(start, end), maxOf(start, end))
            setSelection(minOf(start, end))
            return
        }
        if (start <= 0) return
        var i = start
        while (i > 0 && !editable[i - 1].isLetterOrDigit()) i--
        if (i == start) {
            // No había separadores pegados al cursor: consumir directamente
            // la racha alfanumérica.
            while (i > 0 && editable[i - 1].isLetterOrDigit()) i--
        }
        editable.delete(i, start)
        setSelection(i)
    }

    /** Punto único de movimiento del cursor (antes duplicado en
     *  ControlBarView.moveCursor). [vertical] mueve por línea visual
     *  preservando columna; [horizontal] mueve por carácter. Si
     *  [selectModeActive] está activo, extiende la selección desde
     *  [selectionAnchor] en vez de mover el cursor "suelto". */
    /** Asegura que la línea del cursor quede visible por encima de una franja
     *  tapada en la parte inferior del área visible del editor (en landscape,
     *  esa franja es la que ocupa SuggestionsBarView, flotante sobre el
     *  FrameLayout raíz y ajena al auto-scroll normal de EditText: Android
     *  hace scroll para mantener el cursor dentro del alto de esta vista,
     *  pero no sabe que algo externo tapa parte de ese alto). Si la línea del
     *  cursor ya está por encima de esa franja, no hace nada. [obscuredBottomPx]
     *  es el alto, en píxeles, de la franja tapada medida desde el borde
     *  inferior visible del editor. */
    fun ensureCursorVisibleAbove(obscuredBottomPx: Int) {
        val layout = layout ?: return
        val editable = text ?: return
        if (obscuredBottomPx <= 0) return
        val cursor = selectionStart.coerceIn(0, editable.length)
        val line = layout.getLineForOffset(cursor)
        val cursorBottomInLayout = layout.getLineBottom(line)
        // Borde inferior visible real del editor, en coordenadas del propio
        // layout de texto (ya corrido por el scroll actual).
        val visibleBottomInLayout = scrollY + height - paddingBottom
        val obscuredTopInLayout = visibleBottomInLayout - obscuredBottomPx
        if (cursorBottomInLayout > obscuredTopInLayout) {
            val delta = cursorBottomInLayout - obscuredTopInLayout
            scrollTo(scrollX, scrollY + delta)
        }
    }

    /** Reemplaza la implementación nativa de TextView.bringPointIntoView
     *  (ver doc de clase sobre onTouchEvent: todo el gesto táctil se maneja
     *  a mano para que el Editor nativo de Android no compita con esta
     *  lógica — bringPointIntoView es la única vía por la que ese
     *  comportamiento nativo seguía entrando, porque Android lo dispara
     *  internamente desde dentro de setSelection/onSelectionChanged, no
     *  desde eventos táctiles interceptados).
     *
     *  BUG DE RENDIMIENTO encontrado (reportado: "arrastrar en modo CURSOR
     *  cerca del borde de la pantalla es mucho más lento que el scrollbar"):
     *  cuando el drag en modo CURSOR mueve el cursor cerca de un borde
     *  visible, cada ACTION_MOVE llama moveCursorOrSelectionTo ->
     *  setSelection(offset), y la implementación NATIVA de bringPointIntoView
     *  hace su propio trabajo de medición de layout en cada una de esas
     *  llamadas — docenas de veces por segundo mientras el dedo se mantiene
     *  en esa zona. Esta implementación propia usa el mismo patrón ya
     *  usado en ensureCursorVisibleAbove (arriba): ubicar el punto con
     *  getLineForOffset/getLineTop/getLineBottom/getPrimaryHorizontal, que
     *  son todas lecturas O(1) sobre el Layout ya calculado, sin recorrer
     *  ninguna colección de líneas — y mover el scroll lo mínimo necesario
     *  con el mismo scrollTo barato que ya usa el resto de la clase.
     *
     *  No cambia el comportamiento visible: el cursor sigue "empujando" la
     *  vista cuando queda fuera de lo visible durante el drag, igual que
     *  antes — el fix es de costo, no de conducta. */
    override fun bringPointIntoView(offset: Int): Boolean {
        val l = layout ?: return false
        val editable = text ?: return false
        val safeOffset = offset.coerceIn(0, editable.length)
        val line = l.getLineForOffset(safeOffset)

        val visibleTop = scrollY
        val visibleBottom = scrollY + height - paddingTop - paddingBottom
        val lineTop = l.getLineTop(line)
        val lineBottom = l.getLineBottom(line)

        var newScrollY = scrollY
        if (lineBottom > visibleBottom) {
            newScrollY += lineBottom - visibleBottom
        } else if (lineTop < visibleTop) {
            newScrollY -= visibleTop - lineTop
        }
        newScrollY = newScrollY.coerceIn(0, maxScrollY())

        val visibleLeft = scrollX
        val visibleRight = scrollX + width - paddingLeft - paddingRight
        val x = l.getPrimaryHorizontal(safeOffset).toInt()

        var newScrollX = scrollX
        if (x > visibleRight) {
            newScrollX += x - visibleRight
        } else if (x < visibleLeft) {
            newScrollX -= visibleLeft - x
        }
        newScrollX = newScrollX.coerceIn(0, maxScrollX())

        val changed = newScrollX != scrollX || newScrollY != scrollY
        if (changed) scrollTo(newScrollX, newScrollY)
        return changed
    }

    override fun moveCursor(horizontal: Int, vertical: Int) {
        val layout = layout ?: return
        val editable = text ?: return
        val cursor = selectionStart.coerceIn(0, editable.length)

        val newPos: Int = if (vertical != 0) {
            val line = layout.getLineForOffset(cursor)
            val targetLine = (line + vertical).coerceIn(0, layout.lineCount - 1)
            val col = cursor - layout.getLineStart(line)
            (layout.getLineStart(targetLine) + col).coerceAtMost(layout.getLineEnd(targetLine))
        } else {
            (cursor + horizontal).coerceIn(0, editable.length)
        }

        if (selectModeActive) {
            if (selectionAnchor < 0) selectionAnchor = cursor
            val anchor = selectionAnchor
            setSelection(minOf(anchor, newPos), maxOf(anchor, newPos))
        } else {
            setSelection(newPos)
        }
    }

    /** Texto a copiar con el botón de copiar del anillo (ver
     *  CustomKeyboardView.pasteOrCopy). Respeta cualquier selección activa,
     *  sea hecha con selectModeActive o con los controles nativos de
     *  Android (long-press + manijas, que mueven selectionStart/selectionEnd
     *  de este EditText igual que la selección propia). Si hay una
     *  selección real (selectionEnd > selectionStart), se copia solo esa
     *  porción; si no hay selección (cursor "suelto", largo 0), se copia el
     *  archivo completo, para poder copiarlo sin tener que seleccionar nada
     *  a mano primero. */
    override fun allText(): String {
        val full = text?.toString() ?: return ""
        val start = selectionStart.coerceIn(0, full.length)
        val end = selectionEnd.coerceIn(0, full.length)
        return if (end > start) {
            full.substring(start, end)
        } else {
            full
        }
    }

    /** Ver doc de KeyboardTarget.selectedTextOrNull(): mismo cálculo de
     *  start/end que allText(), pero sin su fallback a "todo el texto" — sin
     *  selección real, devuelve null (Paso 7: usado por Copiar/Cortar). */
    override fun selectedTextOrNull(): String? {
        val full = text?.toString() ?: return null
        val start = selectionStart.coerceIn(0, full.length)
        val end = selectionEnd.coerceIn(0, full.length)
        return if (end > start) full.substring(start, end) else null
    }

    /** Rango del identificador bajo el cursor, o de la selección activa si
     *  hay una (tecla G / DragAction.GOTO — ver GotoResolver). Con
     *  selección real, se usa tal cual viene, sin "redondear" a palabra
     *  completa (si el usuario seleccionó justo lo que quería resolver,
     *  esa es su intención). Sin selección, expande hacia ambos lados del
     *  cursor mientras isIdentifierChar() siga dando true — a diferencia
     *  de currentPrefixRange() (solo mira hacia atrás, para sugerencias
     *  mientras se escribe), este mira en ambas direcciones
     *  porque el cursor puede estar en cualquier punto DENTRO de la
     *  palabra, no solo al final. Null si no hay selección y el cursor no
     *  está tocando ningún identificador (ej. sobre un espacio, un
     *  símbolo, o al final del archivo). */
    fun wordOrSelectionAtCursor(): IntRange? {
        val full = text?.toString() ?: return null
        val selStart = selectionStart.coerceIn(0, full.length)
        val selEnd = selectionEnd.coerceIn(0, full.length)
        if (selEnd > selStart) return selStart until selEnd

        val cursor = selStart
        val touchesBefore = cursor > 0 && isIdentifierChar(full[cursor - 1])
        val touchesAfter = cursor < full.length && isIdentifierChar(full[cursor])
        if (!touchesBefore && !touchesAfter) return null

        var start = cursor
        while (start > 0 && isIdentifierChar(full[start - 1])) start--
        var end = cursor
        while (end < full.length && isIdentifierChar(full[end])) end++
        return start until end
    }

    /** true mientras hay una edición de texto en curso (entre before/onTextChanged
     *  y afterTextChanged), para que onSelectionChanged ignore los cambios de
     *  selección que son efecto colateral de esa edición, no un movimiento de
     *  cursor real del usuario. */
    private var textChangeInFlight = false

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        // super()'s own EditText.<init> calls setText() before this subclass's
        // property initializers (selfEditing, textChangeInFlight) have run,
        // which otherwise NPEs here.
        if (!constructed) return
        if (selfEditing || textChangeInFlight) return
        // Movimiento de cursor puro (sin texto de por medio, por eso no pasa
        // por el watcher): si cruzó a otra línea, refresca en el índice de
        // Autocomplete el contenido de la línea que se deja atrás — ver doc
        // de updateAutocompleteIndexIfLineChanged.
        updateAutocompleteIndexIfLineChanged()
        updateSuggestions()
        // visiblePending depende del cursor (capa "roto" relativa a él, ver
        // doc de clase de BracketAccumulator): un movimiento de cursor
        // puro (sin cambiar texto, por eso no pasa por el TextWatcher)
        // puede revelar o esconder un cierre pendiente — ej. en "([)", "]"
        // vuelve a mostrarse si el cursor se mueve de "([)|" a "([|)".
        notifyPendingCloser()
    }

    /** Posición y momento del ACTION_DOWN más reciente, para distinguir un
     *  tap real de un scroll/drag en onTouchEvent (ver comentario ahí). A
     *  diferencia de lastMoveX/Y (ver abajo), NUNCA se reasignan en
     *  ACTION_MOVE: deben seguir siendo el punto real de bajada del dedo
     *  hasta el ACTION_UP, para poder comparar la distancia TOTAL del gesto
     *  contra TAP_MAX_SLOP_PX sin importar cuánto duró. */
    private var touchDownAt: Long = 0L
    private var touchDownX: Float = 0f
    private var touchDownY: Float = 0f

    /** Último punto conocido del dedo durante un drag en modo CONTENT (ver
     *  dragMode): a diferencia de touchDownX/Y, SÍ se actualiza en cada
     *  ACTION_MOVE, porque el scroll necesita el delta desde el movimiento
     *  INMEDIATAMENTE anterior (cuánto se movió el dedo desde el último
     *  frame), no desde el punto de bajada original (eso daría un scroll
     *  que salta de golpe al valor acumulado total en cada evento, en vez
     *  de seguir al dedo en vivo). */
    private var lastMoveX: Float = 0f
    private var lastMoveY: Float = 0f

    /** Inercia del scroll en modo CONTENT (pedido explícito, "agregar
     *  inercia al modo contenido"): al soltar el dedo tras un drag rápido,
     *  el scroll sigue desacelerando solo en vez de detenerse en seco —
     *  mismo mecanismo que usa cualquier ScrollView nativo. velocityTracker
     *  se alimenta con cada MotionEvent del gesto en curso (ver
     *  ACTION_DOWN/MOVE/UP en onTouchEvent) para medir qué tan rápido se
     *  movía el dedo al soltar; scroller recibe esa velocidad en
     *  ACTION_UP (fling) y computeScroll() (override de View, llamado por
     *  Android en cada frame mientras el scroller sigue animando) aplica
     *  los valores intermedios con scrollTo. Un nuevo ACTION_DOWN cancela
     *  cualquier fling en curso (forceFinished): tocar para frenar/retomar
     *  el control es el comportamiento esperado, no que el fling previo
     *  siga corriendo por debajo del gesto nuevo. */
    private val velocityTracker: VelocityTracker by lazy { VelocityTracker.obtain() }
    private val scroller: OverScroller by lazy { OverScroller(context) }

    /** Momento y posición del último tap confirmado (ACTION_UP válido como
     *  tap), para reconocer un doble tap = seleccionar palabra completa.
     *  Mismo umbral de tiempo que usan ControlBarView.selectToggle y
     *  CustomKeyboardView.toggleShift (DOUBLE_TAP_MS); acá además se exige
     *  que el segundo tap caiga cerca del primero en X/Y, porque un doble
     *  tap en dos palabras distintas de la pantalla no debería dar la
     *  selección de una palabra "entre medio" que no se tocó. */
    private var lastTapAt: Long = 0L
    private var lastTapX: Float = 0f
    private var lastTapY: Float = 0f

    /** true si el gesto de 1 dedo actual ya superó el slop de tap y pasó a
     *  mover el cursor en vivo — una vez que cruza ese umbral, el ACTION_UP
     *  final ya no debe tratarlo como tap (ya se movió el cursor en cada
     *  ACTION_MOVE, no hace falta nada más al soltar). */
    private var oneFingerDragMoved = false

    /** Modo de selección por hold+drag DIRECTO sobre el editor (pedido
     *  explícito: sin pasar por el botón selectToggle). Al mantener
     *  presionado HOLD_SELECT_MS sin moverse más que el slop de tap sobre
     *  una palabra, se selecciona esa palabra completa y el origen de la
     *  selección queda FIJO al inicio de esa palabra (simplificación
     *  pedida explícitamente, para no tener que resolver selección hacia
     *  atrás): todo el drag que sigue solo puede EXTENDER el final hacia
     *  donde se mueva el dedo, nunca mover el inicio. Reutiliza
     *  selectModeActive/selectionAnchor (el mismo mecanismo que ya usa
     *  selectToggle y moveCursorOrSelectionTo) en vez de un modelo de
     *  selección paralelo — por eso ACTION_MOVE no necesita código nuevo
     *  para expandir: moveCursorOrSelectionTo ya extiende desde
     *  selectionAnchor hacia offset sin importar quién activó
     *  selectModeActive.
     *
     *  Distinto del modo "persistente" del botón: holdSelectActive=true
     *  solo mientras el dedo sigue abajo. Al soltar, selectModeActive se
     *  apaga de nuevo — es un modo efímero de este gesto, no un toggle
     *  que quede prendido (ver pedido: "mientras no se suelte el dedo"). */
    private var holdSelectArmed = false
    private var holdSelectActive = false
    private val holdSelectHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var holdSelectRunnable: Runnable? = null

    /** Notificado cuando el hold+drag de este editor activa/desactiva el
     *  modo selección, para que EditorActivity sincronice ControlBarView
     *  (botón visual + estado, ver ControlBarView.activateSelectMode/
     *  deactivateSelectMode) — selectModeActive en sí ya quedó aplicado
     *  acá antes de notificar, esto es solo para que el botón no quede
     *  desincronizado de lo que pasó en el editor. */
    var onHoldSelectStarted: (() -> Unit)? = null
    var onHoldSelectEnded: (() -> Unit)? = null

    private fun cancelHoldSelectTimer() {
        holdSelectRunnable?.let { holdSelectHandler.removeCallbacks(it) }
        holdSelectRunnable = null
        holdSelectArmed = false
    }

    /** Termina el modo hold+drag si estaba activo (soltar dedo, cancelar
     *  gesto, o un 2do dedo aparece) — apaga selectModeActive y notifica,
     *  simétrico a como se activó. No toca nada si no estaba activo (un
     *  tap/drag normal que nunca llegó a activar el hold no debe apagar
     *  un selectModeActive que el USUARIO prendió a mano con el botón). */
    private fun endHoldSelectIfActive() {
        if (!holdSelectActive) return
        holdSelectActive = false
        selectModeActive = false
        onHoldSelectEnded?.invoke()
    }

    /** Reposiciona el cursor (o extiende la selección, con selectModeActive)
     *  a la posición X/Y dada — misma lógica que antes vivía solo en
     *  ACTION_UP, ahora reusada en cada ACTION_MOVE de 1 dedo para que el
     *  cursor siga al dedo en vivo en vez de saltar una sola vez al soltar. */
    private fun moveCursorOrSelectionTo(x: Float, y: Float) {
        val offset = getOffsetForPosition(x, clampTapY(y))
        if (selectModeActive) {
            if (selectionAnchor < 0) selectionAnchor = offset
            setSelection(minOf(selectionAnchor, offset), maxOf(selectionAnchor, offset))
        } else {
            setSelection(offset)
        }
    }

    /** Recorta [y] para que un tap/drag nunca caiga dentro de la franja
     *  inferior reservada por paddingBottom (ver doc de clase: en portrait
     *  es la zona muerta de SuggestionsBarView, permanente sin importar si
     *  la barra está visible en ese momento). Sin esto, un tap ahí
     *  posicionaría el cursor en la línea "tapada" por esa franja en vez de
     *  la línea utilizable más cercana justo arriba — este clamp hace que
     *  ese tap aterrice ahí en su lugar. paddingBottom es 0 en landscape
     *  (ver doc de clase), así que ahí este clamp es un no-op. */
    private fun clampTapY(y: Float): Float {
        val maxY = height - paddingBottom - 1
        return if (maxY < 0) y else y.coerceAtMost(maxY.toFloat())
    }

    /** Lupa de precisión durante el drag de 1 dedo (modo libre y modo
     *  selección): al desactivar el Editor nativo de Android (ver nota en
     *  onTouchEvent) también se perdió su lupa nativa, que es justamente lo
     *  que ayuda a ver con precisión dónde cae el dedo sobre el texto, algo
     *  que el dedo mismo tapa. android.widget.Magnifier (API 28+) hace lo
     *  mismo que esa lupa nativa pero apuntado a mano a este EditText — no
     *  hace falta redibujar el texto, el sistema captura lo que ya está en
     *  pantalla. Por debajo de API 28 simplemente no hay lupa (el resto del
     *  gesto funciona igual, solo sin esta ayuda visual). */
    private val magnifier: Magnifier? by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@lazy null
        // BUG encontrado (reportado: "el conjunto está muy cercano a la
        // posición del dedo"): el contenido ya queda bien centrado (ver
        // nota de showMagnifierAt más abajo — eso es el SOURCE point, qué
        // se magnifica), pero la VENTANA flotante en sí se dibuja casi
        // pegada al dedo por default, tapándose con él. setDefaultSourceToMagnifierOffset
        // (API 29+) desplaza solo la ventana respecto al punto fuente, sin
        // tocar qué contenido magnifica ni su centrado: offsetY negativo =
        // ventana más arriba del dedo. Por debajo de API 29 (pero >= 28) no
        // existe este builder, así que queda sin el ajuste — mismo
        // posicionamiento default que ya había, sin romper nada.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val offsetYPx = -(72 * resources.displayMetrics.density).toInt()
            Magnifier.Builder(this)
                .setDefaultSourceToMagnifierOffset(0, offsetYPx)
                .build()
        } else {
            Magnifier(this)
        }
    }

    /** BUG encontrado (cursor pegado al borde inferior de la lupa): el
     *  offset negativo se aplicaba al Y que se le pasa a show(), pero ese
     *  Y es el SOURCE center — el punto del texto real que la lupa va a
     *  magnificar y centrar en su contenido, no la posición en pantalla
     *  donde se dibuja la ventana flotante de la lupa (eso Android ya lo
     *  decide solo, posicionándola arriba del dedo). Con el offset ahí, le
     *  pedíamos a la lupa que centrara el contenido de una línea más
     *  arriba del dedo, así que el punto real bajo el dedo (y, sin offset)
     *  quedaba desplazado hacia el borde inferior de la lupa en vez de en
     *  su centro. Se pasa el punto real sin offset: Magnifier ya se
     *  encarga de separar su ventana flotante del dedo por su cuenta. */
    private fun showMagnifierAt(x: Float, y: Float) {
        val m = magnifier ?: return
        m.show(x, y)
    }

    private fun dismissMagnifier() {
        magnifier?.dismiss()
    }

    /** Doble tap sobre el texto = seleccionar la palabra completa bajo el
     *  dedo (identificador, usando el mismo criterio que isIdentifierChar:
     *  letras, dígitos y '_'). Si el offset no cae sobre un carácter de
     *  palabra (espacio, símbolo, línea vacía), no hace nada — un doble tap
     *  en un espacio no debería seleccionar la palabra más cercana, sería
     *  sorpresivo. */
    private fun selectWordAt(x: Float, y: Float) {
        val editable = text ?: return
        val offset = getOffsetForPosition(x, clampTapY(y))
        if (offset < 0 || offset >= editable.length || !isIdentifierChar(editable[offset])) return
        var start = offset
        var end = offset
        while (start > 0 && isIdentifierChar(editable[start - 1])) start--
        while (end < editable.length && isIdentifierChar(editable[end])) end++
        setSelection(start, end)
    }

    /** Dispara tras HOLD_SELECT_MS sosteniendo sin moverse (ver
     *  holdSelectArmed/el Runnable armado en ACTION_DOWN): activa el modo
     *  hold+drag directo sobre el editor — al dejar presionado se
     *  selecciona la palabra completa bajo el dedo y se permite seguir
     *  expandiendo la selección mientras no se suelte.
     *
     *  Si [x],[y] cae sobre un carácter de palabra (mismo criterio que
     *  selectWordAt), selecciona la palabra completa. Si no (espacio,
     *  símbolo, fin de línea/texto), selecciona igual desde el punto
     *  tocado: el carácter individual bajo el dedo, o el cursor colapsado
     *  ahí si no hay carácter (offset al final del texto) — así el
     *  hold+drag siempre arranca algo en vez de no hacer nada fuera de una
     *  palabra. El origen de la selección queda FIJO al inicio detectado
     *  (selectionAnchor = start, nunca el punto exacto del dedo): una
     *  simplificación deliberada para no tener que resolver selección hacia
     *  atrás — el resto del drag (ver ACTION_MOVE más arriba,
     *  moveCursorOrSelectionTo) solo puede mover el OTRO extremo, nunca
     *  este. */
    private fun startHoldSelect(x: Float, y: Float) {
        val editable = text ?: return
        val offset = getOffsetForPosition(x, clampTapY(y))
        if (offset < 0 || offset > editable.length) return
        var start = offset
        var end = offset
        if (offset < editable.length && isIdentifierChar(editable[offset])) {
            while (start > 0 && isIdentifierChar(editable[start - 1])) start--
            while (end < editable.length && isIdentifierChar(editable[end])) end++
        } else if (offset < editable.length) {
            // Sin palabra bajo el dedo: tomar el carácter individual tocado
            // (espacio, símbolo, etc.) como selección de partida.
            end = offset + 1
        }
        // offset == editable.length (fin del texto): start == end, cursor
        // colapsado ahí — no hay carácter que seleccionar.

        requestFocus()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        holdSelectActive = true
        selectModeActive = true
        selectionAnchor = start
        setSelection(start, end)
        showMagnifierAt(x, y)
        onHoldSelectStarted?.invoke()
    }

    /** Caché de maxScrollX (ver esa función): recorrer getLineWidth de TODAS
     *  las líneas del layout es, en un archivo de miles de líneas, un costo
     *  real por sí solo — y antes se repetía en CADA ACTION_MOVE del drag en
     *  modo CONTENT (decenas de veces por segundo mientras el dedo se
     *  mueve), a diferencia del scrollbar (DiffMarginView), que usa un
     *  snapshot ya cacheado y nunca llama esto. -1 significa "inválido,
     *  recalcular en la próxima lectura" — invalidado por cualquier cambio
     *  que pueda alterar el ancho de la línea más larga: texto (afterTextChanged,
     *  ver su uso de invalidateMaxScrollXCache) o tamaño de la vista
     *  (onSizeChanged). Es un límite de ancho, no una posición de scroll:
     *  no cambia con el scroll en sí, solo con el documento o el layout. */
    private var cachedMaxScrollX = -1

    private fun invalidateMaxScrollXCache() {
        cachedMaxScrollX = -1
    }

    /** Límite real de scroll horizontal: el ancho total del texto más largo
     *  menos el ancho visible, nunca negativo (layout más angosto que la
     *  vista, nada que scrollear). Mismo criterio que Android usa
     *  internamente para TextView con setHorizontallyScrolling(true), pero
     *  acá hace falta a mano porque el scroll en modo CONTENT (ver dragMode/
     *  onTouchEvent) ya no pasa por el Editor nativo.
     *
     *  Recalcula solo cuando cachedMaxScrollX quedó invalidado (ver su doc):
     *  el recorrido de TODAS las líneas del layout (getLineWidth) pasa a
     *  ocurrir una vez por edición/resize en vez de una vez por evento de
     *  touch durante el drag. */
    private fun maxScrollX(): Int {
        val l = layout ?: return 0
        if (l.lineCount == 0) return 0
        if (cachedMaxScrollX == -1) {
            val widestLine = (0 until l.lineCount).maxOf { l.getLineWidth(it) }
            cachedMaxScrollX = (widestLine - width + paddingLeft + paddingRight).toInt().coerceAtLeast(0)
        }
        return cachedMaxScrollX
    }

    /** Público (a diferencia de maxScrollX): lo usa también DiffMarginView
     *  (ver EditorPaneView.updateThumbMetrics) para calcular la proporción
     *  del thumb de su scrollbar funcional frente al alto total real del
     *  documento. */
    fun maxScrollY(): Int {
        val l = layout ?: return 0
        return (l.height - height + paddingTop + paddingBottom).coerceAtLeast(0)
    }

    /** Público: lo usa DiffMarginView para frenar en seco cualquier fling en
     *  curso en cuanto el usuario toca su franja (ver doc de scroller) — sin
     *  esto, el fling seguiría animando scrollY en paralelo al jump-to-click
     *  del scrollbar, compitiendo por el control del scroll. */
    fun cancelFling() {
        scroller.forceFinished(true)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Un solo dedo maneja todo el gesto, según dragMode (ver su doc):
        // CURSOR mueve el cursor/selección (con hold+drag y lupa, como
        // siempre), CONTENT hace scroll. Ya no existe un gesto separado de 2
        // dedos (eliminado a propósito, Paso 8: competía con el toggle de
        // dragMode en vez de complementarlo — ahora dragMode es la ÚNICA
        // forma de pasar a modo scroll). Esta función nunca delega a
        // super.onTouchEvent(): todo el gesto se maneja acá a mano, para que
        // el Editor nativo de Android (scroll de TextView, selección
        // táctil, lupa de precisión) no compita de forma asíncrona con esta
        // lógica.
        // Alimenta el VelocityTracker con TODOS los eventos del gesto
        // (no solo en modo CONTENT): es más simple mantenerlo siempre al
        // día que prenderlo/apagarlo según dragMode, y no tiene costo
        // real — solo se LEE (getYVelocity) en ACTION_UP, y solo importa
        // si en ese momento el modo sigue siendo CONTENT. clear() en
        // ACTION_DOWN va ANTES de addMovement de ESTE evento (si fuera al
        // revés, borraría el propio ACTION_DOWN recién agregado) — por eso
        // el clear vive dentro del when, no antes junto al addMovement
        // general de los demás actions.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) velocityTracker.clear()
        velocityTracker.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownAt = System.currentTimeMillis()
                touchDownX = event.x
                touchDownY = event.y
                lastMoveX = event.x
                lastMoveY = event.y
                oneFingerDragMoved = false
                // Tocar de nuevo cancela cualquier fling en curso (ver doc
                // de scroller): el usuario está retomando el control a
                // mano, no debe seguir corriendo un scroll del gesto
                // anterior por debajo de este nuevo touch.
                scroller.forceFinished(true)
                // El hold+drag de selección solo tiene sentido en modo
                // CURSOR (ver doc de dragMode): en modo CONTENT, mantener
                // presionado sin moverse no debe seleccionar nada, así que
                // ni siquiera se arma el timer.
                cancelHoldSelectTimer()
                if (dragMode == DragMode.CURSOR) {
                    val downX = event.x
                    val downY = event.y
                    holdSelectArmed = true
                    val runnable = Runnable {
                        holdSelectArmed = false
                        startHoldSelect(downX, downY)
                    }
                    holdSelectRunnable = runnable
                    holdSelectHandler.postDelayed(runnable, HOLD_SELECT_MS)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == DragMode.CONTENT) {
                    // Scroll con el delta desde el ÚLTIMO movimiento
                    // (lastMoveX/Y, no touchDownX/Y — ver su doc): cada
                    // ACTION_MOVE trae el desplazamiento desde el frame
                    // anterior, mismo principio que antes usaba el focal de
                    // 2 dedos. touchDownX/Y se dejan intactos a propósito:
                    // ACTION_UP los necesita para medir la distancia TOTAL
                    // del gesto completo y decidir si fue un tap, sin
                    // importar cuánto scrolleó en el medio.
                    val dx = event.x - lastMoveX
                    val dy = event.y - lastMoveY
                    val newScrollX = (scrollX - dx).toInt().coerceIn(0, maxScrollX())
                    val newScrollY = (scrollY - dy).toInt().coerceIn(0, maxScrollY())
                    scrollTo(newScrollX, newScrollY)
                    lastMoveX = event.x
                    lastMoveY = event.y
                    val totalDx = event.x - touchDownX
                    val totalDy = event.y - touchDownY
                    if (totalDx * totalDx + totalDy * totalDy > TAP_MAX_SLOP_PX * TAP_MAX_SLOP_PX) {
                        oneFingerDragMoved = true
                    }
                } else {
                    val dx = event.x - touchDownX
                    val dy = event.y - touchDownY
                    val overSlop = dx * dx + dy * dy > TAP_MAX_SLOP_PX * TAP_MAX_SLOP_PX
                    // Si el hold todavía no se activó (sigue armado,
                    // esperando HOLD_SELECT_MS) y el dedo ya se movió más
                    // que el slop, esto es un drag NORMAL, no un hold: se
                    // cancela el timer para que no dispare de más tarde
                    // sobre una posición vieja. Si holdSelectActive ya es
                    // true, en cambio, este mismo código de abajo
                    // (moveCursorOrSelectionTo) es exactamente lo que hace
                    // falta para EXTENDER la selección — ya usa
                    // selectModeActive/selectionAnchor sin necesitar nada
                    // especial acá (ver doc de holdSelectArmed más arriba).
                    if (holdSelectArmed && overSlop) cancelHoldSelectTimer()
                    if (oneFingerDragMoved || overSlop) {
                        if (!oneFingerDragMoved) requestFocus()
                        oneFingerDragMoved = true
                        moveCursorOrSelectionTo(event.x, event.y)
                        showMagnifierAt(event.x, event.y)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val duration = System.currentTimeMillis() - touchDownAt
                val dx = event.x - touchDownX
                val dy = event.y - touchDownY
                val distanceSq = dx * dx + dy * dy
                // Mismo umbral que antes distinguía tap de scroll: ahora
                // distingue tap de drag (cursor o contenido, ver dragMode).
                // oneFingerDragMoved se pone en true tanto si ya se movió
                // el cursor en vivo (modo CURSOR) como si ya hizo scroll
                // más allá del slop (modo CONTENT, ver ACTION_MOVE) — en
                // cualquiera de los dos casos no hace falta (ni corresponde)
                // tratar este UP como un tap. Un tap REAL (el dedo nunca
                // superó el slop en ninguno de los dos modos) sigue
                // ubicando el cursor sin importar dragMode, como pide el
                // alcance del Paso 8.
                if (!oneFingerDragMoved && duration < TAP_MAX_DURATION_MS &&
                    distanceSq < TAP_MAX_SLOP_PX * TAP_MAX_SLOP_PX
                ) {
                    requestFocus()
                    onEditorTouched?.invoke()
                    // Doble tap = seleccionar palabra completa, en vez de
                    // simplemente reposicionar el cursor. Se compara
                    // contra el tap anterior (no contra touchDownX/Y de
                    // este mismo gesto, que es el mismo punto por
                    // definición de tap) usando el mismo umbral de
                    // tiempo que ControlBarView/CustomKeyboardView.
                    val sinceLastTap = System.currentTimeMillis() - lastTapAt
                    val tdx = event.x - lastTapX
                    val tdy = event.y - lastTapY
                    if (sinceLastTap <= DOUBLE_TAP_MS &&
                        tdx * tdx + tdy * tdy < TAP_MAX_SLOP_PX * TAP_MAX_SLOP_PX
                    ) {
                        selectWordAt(event.x, event.y)
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        lastTapAt = 0L
                    } else {
                        moveCursorOrSelectionTo(event.x, event.y)
                        lastTapAt = System.currentTimeMillis()
                        lastTapX = event.x
                        lastTapY = event.y
                    }
                }
                // Inercia (ver doc de scroller/velocityTracker arriba):
                // solo en modo CONTENT, y solo si este gesto fue un drag
                // real (oneFingerDragMoved) — un tap no tiene velocidad
                // que lanzar. computeCurrentVelocity(1000) da la
                // velocidad en píxeles por 1000ms = píxeles por segundo,
                // que es justo la unidad que espera fling() de
                // OverScroller.
                if (dragMode == DragMode.CONTENT && oneFingerDragMoved) {
                    velocityTracker.computeCurrentVelocity(1000)
                    val velocityY = velocityTracker.yVelocity
                    val velocityX = velocityTracker.xVelocity
                    if (velocityY * velocityY + velocityX * velocityX > MIN_FLING_VELOCITY_SQ) {
                        // fling() espera la velocidad en la dirección en
                        // que se moverá el SCROLL, que es la opuesta a la
                        // del dedo (mismo signo invertido que ya usa
                        // ACTION_MOVE: scrollX/Y -= dx/dy del dedo) — por
                        // eso se niega acá.
                        scroller.fling(
                            scrollX, scrollY,
                            -velocityX.toInt(), -velocityY.toInt(),
                            0, maxScrollX(),
                            0, maxScrollY()
                        )
                        invalidate()
                    }
                }
                oneFingerDragMoved = false
                dismissMagnifier()
                // Soltar el dedo termina el hold+drag si estaba activo
                // (ver doc de holdSelectActive: es efímero, solo dura
                // mientras el dedo sigue abajo) y cancela el timer si
                // seguía armado sin haber llegado a activarse (tap
                // simple o drag normal que terminó antes de los
                // HOLD_SELECT_MS).
                cancelHoldSelectTimer()
                endHoldSelectIfActive()
            }
            MotionEvent.ACTION_CANCEL -> {
                oneFingerDragMoved = false
                dismissMagnifier()
                cancelHoldSelectTimer()
                endHoldSelectIfActive()
            }
        }
        return true
    }

    /** Aplica el fling de inercia (ver scroller/ACTION_UP en onTouchEvent)
     *  frame a frame. Android llama este override de View automáticamente
     *  mientras haya una animación pendiente, siempre que se lo pida con
     *  invalidate() (ya hecho en el fling() de ACTION_UP, y de nuevo acá
     *  mismo en cada frame mientras scroller.computeScrollOffset() siga
     *  devolviendo true) — no hace falta ningún Handler/Runnable propio
     *  para el loop de animación, esto reemplaza esa necesidad. */
    override fun computeScroll() {
        super.computeScroll()
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, scroller.currY)
            invalidate()
        }
    }

    /** El ancho visible real (width) entra directo en la fórmula de
     *  maxScrollX (ver su doc y la de cachedMaxScrollX) — un cambio de
     *  tamaño (rotar pantalla, mostrar/ocultar teclado, mostrar/ocultar
     *  SuggestionsBarView empujando el layout) deja ese caché
     *  desactualizado aunque el documento en sí no cambió. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateMaxScrollXCache()
    }

    /** Libera el VelocityTracker (ver su doc): cada pestaña crea su propio
     *  CodeEditText y lo descarta al cambiar de archivo o cerrar la
     *  pestaña (ver TabsController.renderActiveEditor/closeTabNow,
     *  editorContainer.removeAllViews()) — recycle() evita acumular
     *  instancias de VelocityTracker sin liberar mientras la app sigue
     *  viva. */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        velocityTracker.recycle()
    }

    private fun handleNewLine(newlineIndex: Int) {
        val text = text ?: return
        val full = text.toString()
        val lineStart = full.lastIndexOf('\n', newlineIndex - 1).let { if (it == -1) 0 else it + 1 }
        val previousLine = full.substring(lineStart, newlineIndex)
        val indent = AutoIndent.indentForNewLine(previousLine, lang)
        if (indent.isNotEmpty()) {
            selfEditing = true
            val insertPos = newlineIndex + 1
            text.insert(insertPos, indent)
            setSelection(insertPos + indent.length)
            selfEditing = false
        }
    }

    /** Resalta el texto completo (sintaxis + marca de tab), línea por línea.
     *  Hace falta llamarlo explícitamente tras setText(): rehighlightCurrentLine
     *  solo repinta la línea del cursor en cada edición, así que un archivo
     *  recién cargado (al abrirlo o volver a su pestaña) no pasaba por el
     *  highlighter y se veía sin colores ni marca de tab hasta que el usuario
     *  tocaba cada línea. Se itera línea por línea (no todo el texto de una
     *  sola vez) porque las reglas del highlighter asumen límites de línea real
     *  (p.ej. el comentario "#.*$" captura hasta el próximo \n, no hasta el
     *  final de todo el documento).
     *
     *  Mismo punto único usado para construir desde cero el índice
     *  incremental de Autocomplete y el de puntos de reinicio de
     *  BracketAccumulator (ver sus docs de clase): se llama una sola vez
     *  por apertura de pestaña/archivo, igual que el resaltado, así que es
     *  el lugar correcto para pagar ese costo una sola vez en vez de en
     *  cada tecla. lastKnownCursorLine se resetea a -1 para forzar que la
     *  próxima sincronización de línea (onSelectionChanged/watcher) parta
     *  de cero contra el contenido recién cargado. */
    fun highlightAll() {
        val editable = text ?: return
        val full = editable.toString()
        autocomplete.buildFullIndex(full)
        BracketAccumulator.populateRestartIndex(full, bracketRestartIndex)
        // Cubre el camino de applyHistorySnapshot (undo/redo) y el de abrir
        // un archivo nuevo, ninguno de los cuales pasa por afterTextChanged
        // (selfEditing=true salta el watcher) — ver doc de
        // cachedMaxScrollX/invalidateMaxScrollXCache. Llamarlo también desde
        // afterTextChanged, como ya se hace, es redundante pero inofensivo
        // (invalidar dos veces no cuesta nada).
        invalidateMaxScrollXCache()
        lastKnownCursorLine = -1
        var lineStart = 0
        // Arranca siempre en null: lineStart=0 es el principio del
        // documento, no puede haber ningún delimitador de triple comilla
        // sin cerrar todavía. Cada vuelta recalcula el estado para la
        // PRÓXIMA línea mirando cómo quedó el documento hasta el final de
        // la línea que se acaba de pintar (ver openTripleQuoteDelimiterAt)
        // — se propaga incrementalmente en vez de recalcular desde el
        // principio cada vez, aunque el resultado sería el mismo.
        var openDelimiter: String? = null
        while (lineStart <= full.length) {
            var lineEnd = full.indexOf('\n', lineStart)
            if (lineEnd == -1) lineEnd = full.length
            highlighter.highlightLine(editable, lineStart, lineEnd, openDelimiter)
            openDelimiter = highlighter.openTripleQuoteDelimiterAt(full, lineEnd)
            if (lineEnd >= full.length) break
            lineStart = lineEnd + 1
        }
    }

    private fun rehighlightCurrentLine(editable: Editable) {
        val full = editable.toString()
        val cursor = selectionStart.coerceIn(0, full.length)
        val lineStart = full.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let { if (it == -1) 0 else it + 1 }
        var lineEnd = full.indexOf('\n', cursor)
        if (lineEnd == -1) lineEnd = full.length
        // A diferencia de highlightAll (que arrastra el estado de la línea
        // anterior sin volver a escanear desde cero), esta función solo
        // repinta UNA línea sin haber recorrido las anteriores en esta
        // pasada, así que el estado "¿qué delimitador vengo arrastrando
        // abierto, si alguno?" se calcula de una sola vez escaneando el
        // documento completo hasta lineStart (ver openTripleQuoteDelimiterAt)
        // — más caro por llamada, pero esta función ya corre por cada
        // tecla, no por cada línea del documento, así que el costo es el
        // mismo orden.
        val openDelimiter = highlighter.openTripleQuoteDelimiterAt(full, lineStart)
        highlighter.highlightLine(editable, lineStart, lineEnd, openDelimiter)
    }

    /** Resalta todas las líneas tocadas por el rango [rangeStart, rangeEnd)
     *  (usado tras un pegado multilínea, ver pastedMultilineRange). Extiende
     *  el rango a límites de línea completa en ambos extremos y repite el
     *  mismo recorrido línea por línea de highlightAll, pero acotado, por el
     *  mismo motivo: las reglas del highlighter asumen límites de línea real. */
    private fun rehighlightRange(editable: Editable, rangeStart: Int, rangeEnd: Int) {
        val full = editable.toString()
        val from = rangeStart.coerceIn(0, full.length)
        val to = rangeEnd.coerceIn(0, full.length)
        var lineStart = full.lastIndexOf('\n', (from - 1).coerceAtLeast(0)).let { if (it == -1) 0 else it + 1 }
        // Mismo motivo que en rehighlightCurrentLine: el rango puede
        // empezar en medio del documento, así que el estado inicial sí
        // necesita el escaneo completo hasta lineStart. De ahí en
        // adelante, dentro del propio bucle, se propaga igual que en
        // highlightAll.
        var openDelimiter = highlighter.openTripleQuoteDelimiterAt(full, lineStart)
        while (lineStart <= to) {
            var lineEnd = full.indexOf('\n', lineStart)
            if (lineEnd == -1) lineEnd = full.length
            highlighter.highlightLine(editable, lineStart, lineEnd, openDelimiter)
            openDelimiter = highlighter.openTripleQuoteDelimiterAt(full, lineEnd)
            if (lineEnd >= full.length) break
            lineStart = lineEnd + 1
        }
    }

    /** Inserta en el cursor el cierre que BracketAccumulator marca como
     *  pendiente Y VISIBLE en la posición actual del cursor (tope de la
     *  celda dedicada de la barra de controles — ver visiblePending: un
     *  tipo roto, o uno que ya se resuelve más adelante en el documento,
     *  no debe poder insertarse aquí), como cualquier otro carácter
     *  tipeado a mano — sin autocierre, no hay ningún cierre "ya puesto"
     *  sobre el que saltar: siempre se inserta literal. */
    fun insertPendingCloser() {
        val text = text ?: return
        val pos = selectionStart.coerceIn(0, text.length)
        val closer = BracketAccumulator.visiblePending(text, pos, bracketRestartIndex, lastKnownCursorLine).firstOrNull() ?: return
        selfEditing = true
        text.insert(pos, closer.toString())
        setSelection(pos + 1)
        selfEditing = false
        notifyPendingCloser()
        onTextSettled?.invoke()
    }

    /** Tecla "⇥" del teclado (ver doc de KeyboardTarget.tabKey): reemplaza
     *  al viejo gesto de pinch sobre el gutter, que ignoraba por completo si
     *  había una selección activa — pinch siempre indentaba/desindentaba
     *  solo la línea de selectionStart, aunque el usuario tuviera 2+ líneas
     *  seleccionadas (bug reportado). Esta versión sí distingue los casos:
     *
     *  - Sin selección (selectionStart == selectionEnd): [increase]=true
     *    inserta la unidad de indentación DEL LENGUAJE actual en el cursor
     *    (tap normal, igual que cualquier otra tecla) — NO es "indentar la
     *    línea", es insertar donde está escribiendo. Ya NO es \t literal
     *    (ver AutoIndent.indentUnitFor y su doc de clase: mismo ajuste que
     *    la indentación automática tras Enter, por consistencia — un tap de
     *    ⇥ y un Enter que abre un bloque deben verse igual). [increase]=false
     *    (hold) desindenta la línea actual, igual que el viejo pinch-in.
     *  - Con selección, sea completa o parcial, de una o varias líneas:
     *    [increase] indenta/desindenta el INICIO de cada línea que el rango
     *    toca (aunque la selección empiece o termine a mitad de línea), y la
     *    selección se reexpande al final para seguir cubriendo esas mismas
     *    líneas completas — así un segundo tap/hold inmediato sigue
     *    operando sobre el mismo bloque sin tener que reseleccionar. */
    override fun tabKey(increase: Boolean) {
        val text = text ?: return
        val full = text.toString()
        val selStart = selectionStart.coerceIn(0, full.length)
        val selEnd = selectionEnd.coerceIn(0, full.length)

        if (selStart == selEnd) {
            if (increase) {
                insertAtCursor(AutoIndent.indentUnitFor(lang))
            } else {
                indentLinesInRange(text, full, selStart, selStart, increase = false)
            }
            return
        }

        val from = minOf(selStart, selEnd)
        val to = maxOf(selStart, selEnd)
        indentLinesInRange(text, full, from, to, increase)
    }

    /** Núcleo compartido de [tabKey] para el caso con selección (o hold sin
     *  selección, que es el caso degenerado de una sola línea): indenta o
     *  desindenta el inicio de cada línea tocada por [from, to], de atrás
     *  hacia adelante (para que los índices de las líneas ya procesadas no
     *  se corran por los inserts/deletes de las líneas siguientes), y deja
     *  la selección cubriendo el mismo bloque de líneas completas al final
     *  — o el cursor simple en el caso de una sola línea sin selección
     *  previa (from == to al entrar).
     *
     *  Al desindentar, [unit] completo (no una cantidad parcial variable)
     *  se descuenta de CADA línea, o ninguna se toca: si alguna línea del
     *  bloque no tiene al menos unit.length espacios/tabs al inicio, toda
     *  la operación se aborta sin modificar nada (bug histórico: antes se
     *  quitaba una cantidad "removed" variable por línea —a veces menos de
     *  unit.length—, lo que hacía imposible calcular la nueva selección de
     *  forma aritmética simple y exacta; un desajuste ahí producía
     *  setSelection con índices fuera de rango y crasheaba). Al garantizar
     *  un delta fijo por línea en ambas direcciones, la nueva selección se
     *  calcula sumando/restando unit.length * cantidad de líneas sobre las
     *  posiciones originales, sin volver a escanear el texto ya
     *  modificado. */
    private fun indentLinesInRange(text: Editable, full: String, from: Int, to: Int, increase: Boolean) {
        val unit = AutoIndent.indentUnitFor(lang)
        val hadSelection = from != to

        // Índice de inicio de cada línea tocada por [from, to], en orden
        // (lineStarts.first() = primera línea del bloque, lineStarts.last()
        // = última).
        val lineStarts = mutableListOf<Int>()
        var pos = from
        while (true) {
            val lineStart = full.lastIndexOf('\n', (pos - 1).coerceAtLeast(0)).let { if (it == -1) 0 else it + 1 }
            lineStarts.add(lineStart)
            val nextNewline = full.indexOf('\n', pos)
            if (nextNewline == -1 || nextNewline >= to) break
            pos = nextNewline + 1
        }

        if (!increase) {
            // Dry-run: todas las líneas deben tener unit.length espacios/
            // tabs al inicio, o se aborta sin tocar el texto.
            val allHaveEnough = lineStarts.all { lineStart ->
                (0 until unit.length).all { offset ->
                    val index = lineStart + offset
                    index < full.length && (full[index] == ' ' || full[index] == '\t')
                }
            }
            if (!allHaveEnough) return
        }

        selfEditing = true
        // Procesar de atrás hacia adelante (última línea primero) para que
        // los índices de las líneas ya procesadas no se corran por los
        // inserts/deletes de las líneas siguientes.
        for (lineStart in lineStarts.asReversed()) {
            if (increase) {
                text.insert(lineStart, unit)
            } else {
                text.delete(lineStart, lineStart + unit.length)
            }
        }
        selfEditing = false

        // Delta fijo y exacto por línea, ya garantizado por el dry-run
        // (desindentar) o por ser siempre unit.length completo (indentar).
        val deltaPerLine = if (increase) unit.length else -unit.length
        val totalDelta = deltaPerLine * lineStarts.size
        val newFull = text.toString()
        val blockStart = lineStarts.first()
        val blockEnd = (to + totalDelta).coerceIn(blockStart, newFull.length)

        rehighlightRange(text, blockStart, blockEnd)
        if (hadSelection) {
            setSelection(blockStart, blockEnd)
        } else {
            // Sin selección previa (caso "hold sin selección" de tabKey):
            // mismo comportamiento que el viejo pinch-in, cursor simple
            // ajustado por lo que se quitó/agregó al inicio de SU línea.
            val lineStart = lineStarts.first()
            setSelection((from + deltaPerLine).coerceIn(lineStart, newFull.length))
        }

        // selfEditing=true saltó el watcher que normalmente mantiene al día
        // el índice de Autocomplete. Indentar/desindentar solo toca
        // whitespace al inicio de línea, nunca agrega/quita saltos de línea
        // (no hace falta shift de claves), pero el contenido cacheado de
        // cada línea tocada sí cambió — es una acción explícita del usuario
        // (tecla ⇥), no el hot path de cada tecla, así que un rebuild
        // completo acá es barato y evita tener que razonar línea por línea.
        //
        // bracketRestartIndex NO necesita tocarse acá: indentar/desindentar
        // nunca agrega ni quita un bracket/comilla (solo espacios/tabs al
        // inicio de línea), así que ningún punto de reinicio cacheado queda
        // invalidado por esta operación.
        autocomplete.buildFullIndex(newFull)
        lastKnownCursorLine = -1
        undoHistory.push(newFull, selectionEnd, isPlainContiguousInsert = false)
        notifyUndoRedoAvailability()
        onTextSettled?.invoke()
    }

    /** Tecla Q del drag-abajo (Paso 7, reemplaza a INSERT_SLASH — ver doc
     *  de KeyboardTarget.toggleLineComment): mismo cálculo de rango de
     *  líneas que indentLinesInRange (sin selección: solo la línea del
     *  cursor; con selección: cada línea que toca, aunque sea parcial), de
     *  atrás hacia adelante para no corromper índices ya procesados.
     *
     *  "Toggle" se decide por el bloque completo, no línea por línea:
     *  descomenta solo si TODAS las líneas no-vacías del bloque ya
     *  empiezan (ignorando espacios/tabs iniciales) con el prefijo; si
     *  alguna no lo tiene, el bloque entero se comenta (agregando el
     *  prefijo a cada línea, incluidas las que ya lo tenían, para no dejar
     *  un resultado mixto confuso de un solo tap). Las líneas vacías (solo
     *  o nada de espacio) no cuentan para decidir el modo y no se tocan al
     *  comentar ni al descomentar — comentar una línea en blanco no aporta
     *  nada y descomentarla no tiene qué quitar. */
    override fun toggleLineComment() {
        val prefix = AutoIndent.lineCommentPrefixFor(lang) ?: return
        val text = text ?: return
        val full = text.toString()
        val selStart = selectionStart.coerceIn(0, full.length)
        val selEnd = selectionEnd.coerceIn(0, full.length)
        val hadSelection = selStart != selEnd
        val from = minOf(selStart, selEnd)
        val to = maxOf(selStart, selEnd)

        val lineStarts = mutableListOf<Int>()
        var pos = from
        while (true) {
            val lineStart = full.lastIndexOf('\n', (pos - 1).coerceAtLeast(0)).let { if (it == -1) 0 else it + 1 }
            lineStarts.add(lineStart)
            val nextNewline = full.indexOf('\n', pos)
            if (nextNewline == -1 || nextNewline >= to) break
            pos = nextNewline + 1
        }

        fun lineEndOf(lineStart: Int): Int {
            val nl = full.indexOf('\n', lineStart)
            return if (nl == -1) full.length else nl
        }

        // Líneas no vacías del bloque (ver doc arriba): deciden el modo y
        // son las únicas tocadas.
        val nonBlankLineStarts = lineStarts.filter { ls -> full.substring(ls, lineEndOf(ls)).isNotBlank() }
        if (nonBlankLineStarts.isEmpty()) return

        fun firstNonSpaceIndex(lineStart: Int): Int {
            var i = lineStart
            val end = lineEndOf(lineStart)
            while (i < end && (full[i] == ' ' || full[i] == '\t')) i++
            return i
        }

        val allCommented = nonBlankLineStarts.all { ls ->
            full.startsWith(prefix, firstNonSpaceIndex(ls))
        }
        val decomment = allCommented

        selfEditing = true
        var totalDelta = 0
        for (lineStart in nonBlankLineStarts.asReversed()) {
            val contentStart = firstNonSpaceIndex(lineStart)
            if (decomment) {
                text.delete(contentStart, contentStart + prefix.length)
                totalDelta -= prefix.length
            } else {
                text.insert(contentStart, prefix)
                totalDelta += prefix.length
            }
        }
        selfEditing = false

        val newFull = text.toString()
        val blockStart = lineStarts.first()
        val blockEnd = (to + totalDelta).coerceIn(blockStart, newFull.length)

        rehighlightRange(text, blockStart, blockEnd)
        if (hadSelection) {
            setSelection(blockStart, blockEnd)
        } else {
            // Sin selección (una sola línea, el único lineStart tocado es
            // nonBlankLineStarts[0] si la línea no está en blanco): el
            // delta solo corre el cursor si estaba EN o DESPUÉS del punto
            // donde se insertó/borró el prefijo — si estaba antes (p.ej.
            // al principio de una línea indentada, antes del primer
            // carácter no-espacio), insertar/borrar ahí no desplaza nada
            // antes de esa posición, así que el cursor debe quedarse
            // igual, no sumarle el delta a ciegas.
            val contentStart = firstNonSpaceIndex(nonBlankLineStarts.first())
            val newPos = if (from >= contentStart) {
                from + if (decomment) -prefix.length else prefix.length
            } else {
                from
            }
            setSelection(newPos.coerceIn(lineStarts.first(), newFull.length))
        }

        // selfEditing=true saltó el watcher que normalmente mantiene al día
        // el índice de Autocomplete. Comentar/descomentar puede cambiar si
        // una línea matchea CONDITION_LINE (ver doc de clase de
        // Autocomplete: "# if x:" ya no ancla como condición), así que un
        // rebuild completo es más seguro que intentar un shift fino —
        // acción explícita del usuario (botón/gesto), no el hot path de
        // cada tecla, el costo es aceptable acá.
        //
        // bracketRestartIndex NO necesita tocarse acá: BracketAccumulator no
        // distingue comentarios de código real (ningún highlightLine de por
        // medio, ver su doc de clase), así que insertar/quitar el prefijo de
        // comentario (#, //) nunca agrega ni quita un bracket/comilla real
        // — ningún punto de reinicio cacheado queda invalidado.
        autocomplete.buildFullIndex(newFull)
        lastKnownCursorLine = -1
        undoHistory.push(newFull, selectionEnd, isPlainContiguousInsert = false)
        notifyUndoRedoAvailability()
        onTextSettled?.invoke()
    }

    // ---------- búsqueda en el archivo ----------

    /** Posiciones (inicio, fin) de cada coincidencia de la búsqueda activa,
     *  sobre el texto COMPLETO (no solo lo visible) — null = buscador
     *  cerrado/sin búsqueda activa. [searchMatchIndex] es cuál de ellas es
     *  la "actual" (la que queda resaltada distinta y a la que
     *  jumpToSearchMatch hace scroll). Ambos viven acá (no en
     *  EditorActivity) porque dependen directo del texto real del
     *  Editable, igual criterio que BracketAccumulator/undoHistory. */
    private var searchMatches: List<IntRange> = emptyList()
    private var searchMatchIndex: Int = -1

    private val searchMatchBg = Color.parseColor("#4D5B86D9") // acento con alpha, coincidencias normales
    private val searchCurrentBg = Color.parseColor("#B35B86D9") // misma base, más opaco: la coincidencia activa

    // Mismo problema (y mismo fix) que ya tenía el buscador del explorador
    // (ver ExplorerController.refreshExplorer): recalcular TODAS las
    // coincidencias en CADA tecla, síncrono en el hilo principal, es lo que
    // hacía sentir "trabado" el teclado al escribir rápido en un archivo
    // grande — el recorrido de indexOf() en loop sobre el texto completo es
    // trabajo de CPU real, no instantáneo. requestSearchUpdate() aplica el
    // mismo patrón: debounce (no busca en cada tecla, solo tras
    // SEARCH_DEBOUNCE_MS sin que se siga escribiendo), cálculo en hilo de
    // fondo (updateSearch() en sí es puro trabajo sobre un String ya en
    // memoria, sin I/O, pero sigue siendo costo de CPU que no debe bloquear
    // la UI en un archivo de miles de líneas), y un token de secuencia para
    // descartar un resultado viejo que llegue después de uno más nuevo.
    // Aplicar los spans al Editable (applySearchHighlights) sí debe volver
    // al hilo principal — Spannable no es seguro de tocar fuera de él.
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private var searchSeq = 0
    private val SEARCH_DEBOUNCE_MS = 200L

    /** Parte pura (sin tocar la vista ni el Editable) del cálculo de
     *  búsqueda: dado el texto completo y la query, devuelve cada rango
     *  coincidente, sin distinguir mayúsculas/minúsculas — búsqueda básica,
     *  no hace falta más para el caso de uso pedido. Callable desde
     *  cualquier hilo (ver requestSearchUpdate), porque no lee ni escribe
     *  ningún estado de instancia. */
    private fun computeSearchMatches(full: String, query: String): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        val matches = ArrayList<IntRange>()
        var from = 0
        while (from <= full.length - query.length) {
            val idx = full.indexOf(query, from, ignoreCase = true)
            if (idx == -1) break
            matches.add(idx until (idx + query.length))
            from = idx + query.length
        }
        return matches
    }

    /** Recalcula las coincidencias de [query] de forma síncrona, en el
     *  hilo que la llame, y las resalta con un fondo propio
     *  (BackgroundColorSpan: no choca con el ForegroundColorSpan del
     *  resaltado de sintaxis, ver SyntaxHighlighter.highlightLine, que
     *  nunca toca spans de fondo). [query] vacío limpia todo sin dejar
     *  ninguna coincidencia. Devuelve el conteo total.
     *
     *  Debe llamarse SIEMPRE desde el hilo principal (toca el Editable vía
     *  applySearchHighlights y lee selectionStart) — para no bloquear la UI
     *  en un archivo grande, el camino normal desde el buscador es
     *  [requestSearchUpdate], no esta función directo. Se mantiene pública
     *  y síncrona por si algún caller futuro necesita el resultado
     *  inmediato sin debounce (ninguno lo hace hoy). */
    fun updateSearch(query: String): Int {
        clearSearchHighlights()
        if (query.isEmpty()) {
            searchMatches = emptyList()
            searchMatchIndex = -1
            return 0
        }
        val matches = computeSearchMatches(text?.toString() ?: "", query)
        searchMatches = matches
        // Si ya había una coincidencia "actual" antes de este recálculo
        // (p.ej. el usuario sigue escribiendo la misma query y la lista de
        // matches cambió), se intenta mantener la más cercana a la
        // posición del cursor en vez de siempre saltar a la primera —
        // mejor UX, y evita peleas de scroll contra ensureCursorVisibleAbove.
        searchMatchIndex = if (matches.isEmpty()) {
            -1
        } else {
            val cursor = selectionStart
            matches.indexOfFirst { it.first >= cursor }.let { if (it == -1) 0 else it }
        }
        applySearchHighlights()
        // A diferencia de jumpToNext/PreviousSearchMatch (que SÍ mueven el
        // índice antes de llamar esto), acá el índice ya quedó elegido
        // arriba — autoscrollear a esa coincidencia inicial en cada tecleo
        // es justo lo que evita que el usuario tenga que tocar "siguiente"
        // después de escribir la búsqueda.
        if (searchMatchIndex != -1) scrollToSearchMatch()
        return matches.size
    }

    /** Punto de entrada real del buscador (ver ControlsController.
     *  setupSearchBar): debounce + cálculo en hilo de fondo + token de
     *  secuencia, mismo patrón que ExplorerController.refreshExplorer para
     *  el mismo problema (recalcular todo en cada tecla, síncrono,
     *  bloqueaba la UI en archivos grandes). [query] vacío es un caso
     *  especial: limpiar la búsqueda debe sentirse instantáneo (no tiene
     *  sentido debouncear un cuadro que se está vaciando), así que no pasa
     *  por el hilo de fondo. [onResult] recibe el conteo final, para que
     *  ControlsController actualice el indicador "N/M" — puede no llegar a
     *  llamarse nunca si una query más nueva cancela esta antes de
     *  terminar. */
    fun requestSearchUpdate(query: String, onResult: (Int) -> Unit) {
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
        val seq = ++searchSeq
        if (query.isEmpty()) {
            onResult(updateSearch(query))
            return
        }
        val runnable = Runnable {
            val full = text?.toString() ?: ""
            Thread {
                val matches = computeSearchMatches(full, query)
                searchHandler.post {
                    if (seq != searchSeq) return@post // una query más nueva ya se disparó
                    applyComputedSearchMatches(matches)
                    onResult(matches.size)
                }
            }.start()
        }
        searchRunnable = runnable
        searchHandler.postDelayed(runnable, SEARCH_DEBOUNCE_MS)
    }

    /** Aplica al Editable (hilo principal) un resultado ya calculado en
     *  background — misma lógica de searchMatchIndex/resaltado/scroll que
     *  updateSearch, extraída para no duplicarla entre el camino síncrono y
     *  el de background. */
    private fun applyComputedSearchMatches(matches: List<IntRange>) {
        searchMatches = matches
        searchMatchIndex = if (matches.isEmpty()) {
            -1
        } else {
            val cursor = selectionStart
            matches.indexOfFirst { it.first >= cursor }.let { if (it == -1) 0 else it }
        }
        applySearchHighlights()
        if (searchMatchIndex != -1) scrollToSearchMatch()
    }

    /** Cantidad total de coincidencias de la búsqueda activa (0 si no hay ninguna o el buscador está cerrado). */
    fun searchMatchCount(): Int = searchMatches.size

    /** Índice (1-based, para mostrar "2/5") de la coincidencia actual, o 0 si no hay ninguna. */
    fun searchMatchDisplayIndex(): Int = if (searchMatchIndex == -1) 0 else searchMatchIndex + 1

    /** Avanza a la siguiente coincidencia (circular: de la última vuelve a
     *  la primera) y hace scroll para que quede visible. No hace nada si
     *  no hay ninguna coincidencia. */
    fun jumpToNextSearchMatch() {
        if (searchMatches.isEmpty()) return
        searchMatchIndex = (searchMatchIndex + 1) % searchMatches.size
        applySearchHighlights()
        scrollToSearchMatch()
    }

    /** Igual que [jumpToNextSearchMatch] pero hacia atrás (circular). */
    fun jumpToPreviousSearchMatch() {
        if (searchMatches.isEmpty()) return
        searchMatchIndex = (searchMatchIndex - 1 + searchMatches.size) % searchMatches.size
        applySearchHighlights()
        scrollToSearchMatch()
    }

    /** Cierra el buscador: limpia resaltados y el estado de coincidencias.
     *  No toca el texto ni el cursor — EditorActivity decide si además
     *  devuelve el foco/teclado al editor. También cancela un
     *  requestSearchUpdate en debounce/background pendiente — importante al
     *  cambiar de pestaña con el buscador abierto (ver EditorActivity.
     *  onSwitchingTab -> closeSearchBarIfOpen): sin esto, un resultado que
     *  terminara de calcularse después del cambio de pestaña resaltaría un
     *  editor que el usuario ya no está mirando. */
    fun clearSearch() {
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
        searchRunnable = null
        searchSeq++ // invalida cualquier cálculo en background ya en vuelo
        clearSearchHighlights()
        searchMatches = emptyList()
        searchMatchIndex = -1
    }

    private fun clearSearchHighlights() {
        val editable = text ?: return
        val spans = editable.getSpans(0, editable.length, BackgroundColorSpan::class.java)
        for (s in spans) editable.removeSpan(s)
    }

    private fun applySearchHighlights() {
        val editable = text ?: return
        clearSearchHighlights()
        searchMatches.forEachIndexed { index, range ->
            val bg = if (index == searchMatchIndex) searchCurrentBg else searchMatchBg
            editable.setSpan(
                BackgroundColorSpan(bg),
                range.first, range.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun scrollToSearchMatch() {
        val range = searchMatches.getOrNull(searchMatchIndex) ?: return
        setSelection(range.first, range.last + 1)
        // bringPointIntoView ya existe como parte de TextView/EditText:
        // hace scroll del propio Editor para que la posición del cursor
        // (que acabamos de mover al inicio de la coincidencia) quede
        // visible, sin necesitar cálculo manual de línea/layout.
        bringPointIntoView(range.first)
    }

    companion object {
        /** Ventana de fusión de pasos de undo (ver UndoHistory.push): dos
         *  inserciones contiguas separadas por menos de esto se tratan como
         *  un solo paso de undo. */
        private const val MERGE_WINDOW_MS = 800L

        /** Umbral de duración para que un toque cuente como "tap" (y abra el
         *  teclado) en vez de scroll/drag — ver onTouchEvent. */
        private const val TAP_MAX_DURATION_MS = 200L

        /** Umbral de desplazamiento (px) para lo mismo: un dedo que se mueve
         *  más que esto entre ACTION_DOWN y ACTION_UP se trata como drag,
         *  sin importar cuánto haya durado. */
        private const val TAP_MAX_SLOP_PX = 20f

        /** Ventana entre dos taps para contarlos como doble tap (selección
         *  de palabra) — mismo valor que ControlBarView.selectToggle y
         *  CustomKeyboardView.toggleShift usan para su propio doble tap. */
        private const val DOUBLE_TAP_MS = 300L

        /** Umbral de hold para activar el modo selección directo sobre el
         *  editor (ver startHoldSelect) — mismo valor que
         *  CustomKeyboardView.LONG_PRESS_MS usa para sus propios gestos de
         *  hold (accentos, anillos): lo bastante corto para sentirse
         *  responsivo, lo bastante largo para no competir con un tap o un
         *  drag normal que arranca rápido. */
        private const val HOLD_SELECT_MS = 350L

        /** Umbral de velocidad (px/segundo, al cuadrado para comparar sin
         *  sqrt) para que un drag soltado en modo CONTENT dispare fling
         *  (ver ACTION_UP/scroller): un drag que termina casi quieto (el
         *  usuario frenó el dedo antes de soltar) no debe seguir
         *  deslizando solo, sería sorpresivo. 100 px/s de margen antes de
         *  considerar "hubo velocidad real que lanzar". */
        private const val MIN_FLING_VELOCITY_SQ = 100f * 100f
    }
}
