package com.codemobile.keyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot

/**
 * Vista principal del teclado propio (reemplaza al IME de sistema).
 *
 * Soporta: QWERTY/shift + fila inferior con taps simples; pantalla de
 * símbolos y popup numérico ([screen] generaliza el estado de pantalla
 * entre QWERTY/símbolos/numpad); variantes por hold (acentos) sobre la
 * pantalla QWERTY; y los 3 anillos (brackets/operadores/nav), vía
 * [RingGestureView], colgados de las teclas "(" "=" "⇥" de la fila
 * inferior.
 *
 * [ringOverlay] y [accentPopup] no son hijos de esta vista: son vistas
 * hermanas en el FrameLayout raíz de activity_editor.xml (igual patrón que
 * suggestionsBar), inyectadas por EditorActivity vía estas propiedades
 * públicas (mismo patrón que [target]). Si vivieran adentro, activarlas
 * (visibility=VISIBLE) inflaría la medición de esta vista a MATCH_PARENT
 * completo o, con una altura fija, a un margen extra necesario para que el
 * popup de acentos tuviera espacio en la fila superior (qwertyuiop),
 * produciendo un salto visible del teclado cada vez que se abre un anillo
 * o un popup. Viviendo en el FrameLayout raíz (tamaño fijo de toda la
 * pantalla, sin relación con el tamaño de esta vista), activarlas no mueve
 * ni redimensiona el teclado en absoluto.
 */
class CustomKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** Dónde se enruta cada pulsación. CustomKeyboardView nunca es un IME:
     *  solo llama a los 4 métodos de KeyboardTarget (ver ese archivo).
     *  Puede ser un CodeEditText (editor de código, el caso normal) o un
     *  PlainEditTextTarget envolviendo el EditText de la consola git —
     *  ambos implementan la misma interfaz, así que este teclado no
     *  necesita saber cuál de los dos es. */
    var target: KeyboardTarget? = null

    /** Se invoca después de copiar, cortar o pegar (ver copySelection/
     *  cutSelection/pasteFromClipboard), y también desde
     *  cancelAnchorSelectionIfArmed() (Paso 7, tecla E — ver doc de
     *  anchorSelectionArmed): un hold simple sobre E ya armada cancela el
     *  modo y necesita el mismo efecto que copiar/pegar sobre
     *  selectModeActive real, así que reusa este mismo callback en vez de
     *  uno nuevo. target es un KeyboardTarget genérico (puede ser
     *  PlainEditTextTarget, sin ninguna noción de "modo selección"), así
     *  que este teclado no apaga selectModeActive directamente: solo avisa,
     *  y quien sepa qué hacer con eso (ControlsController, que conecta esto
     *  a controlBar.deactivateSelectMode()) decide. */
    var onCopyOrPaste: (() -> Unit)? = null

    /** Se invoca desde armAnchorSelection() (Paso 7, tecla E) justo cuando
     *  se arma el ancla: avisa a quien sepa reflejar "modo selección
     *  activo" en su propio estado (ControlsController, conectado a
     *  controlBar.activateSelectMode() — mismo patrón que onCopyOrPaste,
     *  pero para el otro sentido). Sin esto, ControlBarView.selectModeActive
     *  se quedaba en false aunque target.selectModeActive ya fuera true, lo
     *  que hacía que deactivateSelectMode() posterior (al cancelar el
     *  ancla) cortara en su guard sin apagar nada real — bug reportado y
     *  corregido. */
    var onActivateSelectMode: (() -> Unit)? = null

    /** Paso 7 (tecla E, "Ancla de selección"): true desde que un drag-abajo
     *  confirmado en E arma el ancla (target.selectionAnchor = cursor
     *  actual, target.selectModeActive = true, ver armAnchorSelection())
     *  hasta que se cancela o se "consume". Mientras es true:
     *  - gestureKey() de la letra E intercepta su PRÓXIMO hold (sin
     *    necesitar drag-abajo esta vez, un simple hold alcanza — ver el
     *    chequeo "ch == 'e' && anchorSelectionArmed" en su runnable de
     *    hold) para CANCELAR el modo en vez de reabrir el popup de acentos
     *    o decidir drag-acción.
     *  - Un drag-abajo NUEVO confirmado en E (no un hold simple) hace reset:
     *    applyDragAction()/armAnchorSelection() no distinguen "primera vez"
     *    de "ya armada", así que rearmar mientras ya está armada cancela
     *    la anterior y arma una nueva desde la posición ACTUAL del cursor
     *    en ese momento, sin código extra.
     *  - copySelection/cutSelection/pasteFromClipboard lo apagan (ver su
     *    doc): esas 3 acciones "consumen" la selección conceptualmente,
     *    aunque copySelection en particular no modifique nada, así que
     *    dejarlo armado después sería confuso.
     *  Un tap normal en CUALQUIER OTRA tecla (inserta un carácter,
     *  sobrescribiendo la selección activa por el mismo mecanismo de
     *  siempre) también apaga este flag — ver onKeyTap/onBackspace. Sin
     *  esto, el flag seguiría true aunque la selección real ya se hubiera
     *  perdido por la escritura, y el PRÓXIMO hold sobre E (que el usuario
     *  esperaría fuera un simple hold normal para el popup de acentos)
     *  entraría por error en "modo cancelar" en vez de comportarse como
     *  cualquier otra tecla sin ancla armada. */
    private var anchorSelectionArmed = false

    /** Overlays de gesto, ahora externos (ver doc de clase). Son
     *  `lateinit var` en vez de construirse en `init{}`: EditorActivity los
     *  asigna justo después de inflar el layout (bindViews()), antes de que
     *  el usuario pueda tocar ninguna tecla de "(" "=" "⇥" o una letra con
     *  variantes — si se tocan antes de la asignación, ringOriginKey/
     *  accentKey simplemente no abren nada (ver los null-checks en sus
     *  gestos), en vez de crashear. */
    lateinit var ringOverlay: RingGestureView
    lateinit var accentPopup: AccentPopupView

    /** Paso 7: barra de sugerencias, inyectada igual que ringOverlay/
     *  accentPopup (ver doc de clase) — el gesto de drag-abajo (ver
     *  actionDragKey()) la usa para mostrar el hint de la acción mientras
     *  se sostiene, sobre cualquier sugerencia real que hubiera en ese
     *  momento, restaurándola exacta al terminar el gesto. */
    lateinit var suggestionsBar: com.codemobile.editor.SuggestionsBarView

    /** Paso 7 (§5, SettingsActivity): overlay de edición, inyectado igual
     *  que ringOverlay/accentPopup/suggestionsBar (ver doc de clase) — solo
     *  se usa cuando [configMode] es true, nunca en el teclado normal de
     *  EditorActivity (que ni siquiera lo inyecta). */
    lateinit var configPanel: ConfigPanelView

    /** Paso 7 (§5): true solo en SettingsActivity. Cambia qué hace el tap
     *  en las teclas editables (letras reasignables, "(", "=", "↵"): en vez
     *  de su gesto normal, abren [configPanel] con su contenido actual.
     *  Las demás teclas (numpad, espacio, tab, enter, erase, shift, qwerty
     *  sin variantes/drag-action) no responden a nada en este modo — ver
     *  doc de letterKey()/bottomRow(). Debe asignarse ANTES de la primera
     *  llamada a render() (SettingsActivity lo hace antes de agregar la
     *  vista al layout), ya que decide en tiempo de construcción qué
     *  listener recibe cada tecla, no se relee en cada tap. */
    var configMode: Boolean = false

    /** Paso 7 (botón "Aplicar", 3 estados — ver KeyboardSettingsSource):
     *  de dónde leen/escriben las funciones effective*() de
     *  KeyboardLayouts y ConfigPanelView. Por defecto, el store REAL
     *  (SharedPreferences) — el teclado normal de EditorActivity nunca lo
     *  reasigna, así que sigue viendo/escribiendo overrides directo, sin
     *  ningún cambio de comportamiento. SettingsActivity SÍ lo reasigna a
     *  un PendingKeyboardSettings (buffer en memoria) antes de la primera
     *  llamada a render() — mismo momento y mismo motivo que configMode
     *  arriba: se lee en tiempo de construcción de cada tecla, así que
     *  reasignarlo después de la primera render() deja a medio camino
     *  teclas ya construidas contra la fuente vieja. */
    var settingsSource: KeyboardSettingsSource = RealKeyboardSettingsSource(context)

    /** Paso 7: acciones del drag-abajo que CustomKeyboardView no puede
     *  resolver por sí mismo (requieren lógica de más arriba: guardar el
     *  archivo, abrir el buscador, etc. — ver ControlsController, que es
     *  quien asigna esta lambda, mismo patrón que onRotateClick/
     *  onHideKeyboardClick en ControlBarView). Cortar/Copiar/Pegar e
     *  "Insertar /" NO pasan por acá: CustomKeyboardView ya tiene todo lo
     *  necesario para resolverlos solo (ver applyDragAction()). */
    var onDragAction: ((KeyboardLayouts.DragAction) -> Unit)? = null

    /** Pantallas del teclado. NUMPAD queda abierta hasta "ABC" explícito
     *  (no se cierra solo al soltar, a diferencia de los anillos). */
    private enum class Screen { QWERTY, SYMBOLS, NUMPAD }

    private var screen = Screen.QWERTY

    /** Estado de ⇧ (3 estados):
     *  - [OFF]: minúsculas, comportamiento normal.
     *  - [ONE_SHOT]: mayúscula para la SIGUIENTE letra únicamente; se apaga
     *    solo en cuanto esa letra se escribe (ver onLetterTap).
     *  - [CAPS_LOCK]: mayúsculas indefinidas; solo se apaga volviendo a
     *    tocar ⇧.
     *  Tap normal en ⇧: OFF→ONE_SHOT, ONE_SHOT→OFF, CAPS_LOCK→OFF (cualquier
     *  tap estando en lock lo apaga). Doble-tap rápido (dentro de
     *  SHIFT_DOUBLE_TAP_MS) en vez de eso activa CAPS_LOCK directamente. */
    private enum class ShiftState { OFF, ONE_SHOT, CAPS_LOCK }
    private var shiftState = ShiftState.OFF
    private var lastShiftTapAt = 0L
    private val shiftActive get() = shiftState != ShiftState.OFF

    private val keysColumn: LinearLayout

    // Referencia a la tecla "=" (RING_OPERATORS): se usa como origen más
    // restrictivo para calcular el radio máximo común de todos los anillos
    // en tiempo real (ver maxRingRadius()). Se reasigna cada vez que
    // bottomRow() se reconstruye (shift, cambio de pantalla, etc.) porque
    // la vista vieja deja de estar en el árbol.
    private var equalsKeyView: View? = null

    // Indicador de estado debajo de ⇧ (punto en ONE_SHOT, línea en
    // CAPS_LOCK): igual que equalsKeyView arriba, se reasigna cada
    // vez que renderQwertyContent() reconstruye la tecla, porque la vista
    // vieja deja de estar en el árbol.
    private var shiftIndicatorView: View? = null

    companion object {
        private const val KEY_HEIGHT_DP = 42
        private const val KEY_GAP_DP = 4
        private const val KEY_BG = 0xFF262A33.toInt()
        private const val KEY_BG_SPECIAL = 0xFF1C2027.toInt()
        private const val KEY_TEXT = 0xFFE8E9EC.toInt()
        private const val KEY_TEXT_DIM = 0xFF8A8F99.toInt()
        private const val KEY_RADIUS_DP = 8f
        // Tamaño de fuente único para TODAS las teclas (básicas, variantes,
        // specialKey/Enter/Tab/⇧/⌫, y símbolos dentro de los anillos):
        // antes había varios valores sueltos (19.5f, 22.5f, 27f, 30f...) y
        // un cambio de tamaño solo llegaba a las básicas por error — ahora
        // un solo punto de verdad.
        private const val KEY_TEXT_SIZE_SP = 22.5f
        private const val SHIFT_INDICATOR_WIDTH_DP = 5
        private const val SHIFT_INDICATOR_LOCK_WIDTH_DP = 14
        private const val SHIFT_INDICATOR_HEIGHT_DP = 5
        private const val SHIFT_INDICATOR_MARGIN_BOTTOM_DP = 4
        // Hint del carácter central del anillo: el botón
        // del centro del anillo expandido suele quedar tapado por el propio
        // dedo que sostiene el hold, así que no se nota qué carácter tiene
        // asociado hasta soltarlo por error. Este hint vive en la tecla de
        // ORIGEN (visible todo el tiempo, antes de iniciar el hold), chico,
        // en la esquina inferior derecha — no estorba al label principal.
        private const val RING_HINT_TEXT_SIZE_SP = 10f
        private const val RING_HINT_MARGIN_DP = 3
        private const val RING_HINT_TEXT_COLOR = 0xFF5B8CFF.toInt() // mismo acento que paintCenter en RingGestureView
        private const val LONG_PRESS_MS = 350L
        private const val DRAG_SLOP_DP = 12f
        // Paso 7 (repetición continua de UNDO/REDO, ver DragAction.
        // repeatsOnHold): mismo rol que CENTER_HOLD_THRESHOLD_MS/
        // CONTINUOUS_TICK_MS en RingGestureView.NavMode.DRAG, mismos
        // valores — un segundo sostenido sin soltar arranca la repetición,
        // luego un tick cada 120ms.
        private const val DRAG_ACTION_REPEAT_THRESHOLD_MS = 1000L
        private const val DRAG_ACTION_REPEAT_TICK_MS = 120L
        // Margen entre el radio máximo del anillo y el borde de pantalla
        // (ver ringOriginKey().maxRingRadius()), para que el trazo no quede
        // pegado exactamente al borde.
        private const val SAFETY_MARGIN_DP = 8f

        // ---- Borrado continuo al mantener ⌫ ----
        /** Cada cuánto se repite el borrado mientras se mantiene presionado,
         *  en ambos modos (carácter y palabra). */
        private const val ERASE_REPEAT_MS = 50L
        /** A los cuántos ms de hold CONTINUO (no desde el primer tick, sino
         *  desde que arrancó el hold) se pasa de borrar un carácter a borrar
         *  palabras completas: los primeros 3 segundos borra un carácter,
         *  después borra palabras completas. */
        private const val ERASE_WORD_MODE_AFTER_MS = 3000L

        // ---- Doble-tap de ⇧ para CAPS LOCK ----
        /** Ventana máxima entre dos taps de ⇧ para contar como doble-tap. */
        private const val SHIFT_DOUBLE_TAP_MS = 300L
    }

    init {
        setBackgroundColor(0xFF15171C.toInt())

        keysColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(6)
            setPadding(pad, pad, pad, pad)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        addView(keysColumn)

        render()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    /** Alto fijo de las 4 filas del teclado (3 filas QWERTY/shift + bottomRow,
     *  o su equivalente en SYMBOLS/NUMPAD), sin depender de measuredHeight.
     *  Usado por ControlsController para medir bottomControlsRow en
     *  landscape antes de que haya ocurrido un layout pass real. */
    fun expectedHeightPx(): Int {
        val rows = 4
        // Cada celda mide KEY_HEIGHT_DP + KEY_GAP_DP de alto (el gap vive
        // ahora como inset dentro de la celda, ver keyGapBackground()), sin
        // margin entre filas — se resta un KEY_GAP_DP para no sumar un gap
        // de más en los bordes externos (arriba de la primera fila, abajo
        // de la última) donde antes no existía ningún margin.
        return dp(6) * 2 + (dp(KEY_HEIGHT_DP) + dp(KEY_GAP_DP)) * rows - dp(KEY_GAP_DP)
    }

    /** Paso 7 (menú de configuración, §5): fuerza una reconstrucción
     *  completa del teclado para que los overrides recién guardados en
     *  KeyboardSettingsStore (desde SettingsActivity, otra Activity)
     *  queden reflejados aquí — letterKey/ringOriginKey/symbolKey solo
     *  leen esos overrides al construirse (ver effectiveAccentVariants y
     *  demás en KeyboardLayouts), no en cada tap, así que sin este
     *  refresco explícito un cambio hecho en Settings no se vería hasta
     *  el próximo cambio de pantalla/shift que ya disparara render() por
     *  otra razón. Pensado para EditorActivity.onResume(), que cubre el
     *  caso real (usuario abre Settings, cambia algo, presiona atrás). */
    fun refreshFromSettings() {
        render()
    }

    /** Paso 7 (botón "Aplicar", 3 estados): invocado junto con
     *  refreshFromSettings() cada vez que un panel de ConfigPanelView
     *  cierra habiendo tocado el buffer (ver los onChanged pasados a
     *  configPanel.showLetterPanel/showRingPanel/showSymbolEdit más abajo
     *  en esta clase) — SettingsActivity lo usa para refrescar la
     *  visibilidad del botón "Aplicar" sin que CustomKeyboardView necesite
     *  saber nada de ese botón. null en el teclado normal de
     *  EditorActivity (que ni siquiera asigna configPanel, así que esto
     *  nunca se invoca ahí). */
    var onSettingsChanged: (() -> Unit)? = null

    /** Encadena refreshFromSettings() (reconstruye las teclas) con el
     *  aviso externo de arriba — un solo punto para los 4 lugares de esta
     *  clase que cierran un panel de configuración, en vez de repetir
     *  "refreshFromSettings(); onSettingsChanged?.invoke()" cuatro veces. */
    private fun onConfigPanelChanged() {
        refreshFromSettings()
        onSettingsChanged?.invoke()
    }

    /** Despacha a la pantalla activa. Único punto que decide qué dibujar;
     *  cada pantalla delega el resto de la construcción en sus propios
     *  métodos (renderQwerty, renderSymbols, renderNumpad). */
    private fun render() {
        keysColumn.removeAllViews()
        when (screen) {
            Screen.QWERTY -> renderQwertyContent()
            Screen.SYMBOLS -> renderSymbolsContent()
            Screen.NUMPAD -> renderNumpadContent()
        }
    }

    /** Contenido de la pantalla QWERTY/shift. */
    private fun renderQwertyContent() {
        val rows = if (shiftActive) KeyboardLayouts.ROWS_UPPER else KeyboardLayouts.ROWS_LOWER

        rows.forEachIndexed { i, row ->
            val rowLayout = newRow()
            if (i == 2) rowLayout.addView(shiftKey(weight = 1f))
            row.forEach { ch -> rowLayout.addView(letterKey(ch)) }
            if (i == 2) rowLayout.addView(eraseKey(weight = 1f))
            keysColumn.addView(rowLayout)
        }

        keysColumn.addView(bottomRow(showSymbolsOrigin = true))
    }

    /** Contenido de la pantalla de símbolos (tap en "?123"): fila de dígitos
     *  para igualar el área con QWERTY, 3 principales + 12 secundarios, y la
     *  misma fila inferior pero con "ABC" en vez de "?123" para volver. */
    private fun renderSymbolsContent() {
        val digitsRow = newRow()
        KeyboardLayouts.SYMBOLS_ROW_DIGITS.forEach { d ->
            digitsRow.addView(symbolKey(d.toString(), main = false, weight = 1f))
        }
        keysColumn.addView(digitsRow)

        // Paso 7 (menú de configuración, §5): cada símbolo de SYMBOLS_SIDE
        // se lee vía effectiveSymbol(main=false, index) en vez de iterar la
        // lista directo, para poder pedirle a KeyboardSettingsStore el
        // override de ESE índice puntual (edición directa de un carácter,
        // sin variantes — ver su doc). sideIndex es un contador simple
        // porque el orden de consumo (2 antes de mainRow + 2 después + el
        // resto en sideRow) sigue siendo secuencial sobre la misma lista,
        // igual que antes con el ArrayDeque.
        var sideIndex = 0
        fun nextSideSymbol(): String {
            val s = KeyboardLayouts.effectiveSymbol(settingsSource, main = false, index = sideIndex).toString()
            sideIndex++
            return s
        }
        // Paso 7 (configMode): cada llamada a nextSideSymbol() ya avanzó
        // sideIndex, así que el índice real de ESE símbolo es sideIndex-1
        // justo después de llamarla — se usa para pasarle configIndex a
        // symbolKey sin duplicar el contador.

        // Las 3 filas deben sumar el mismo ancho total que digitsRow (10
        // columnas de weight=1f) para quedar alineadas entre sí — antes
        // mainRow sumaba 8.5 y sideRow 8, por eso los botones no coincidían
        // verticalmente con los de arriba/abajo.
        //
        // weight de las celdas principales calculado a partir de la
        // cantidad real de SYMBOLS_MAIN (hoy 6: \ # $ / . -), no un valor
        // fijo: 2 laterales + N principales + 2 laterales deben sumar 10
        // (igual que digitsRow), así que weight = 10 / (4 + N). Antes de
        // esta fórmula genérica, el valor estaba hardcodeado (1.667 para 3
        // celdas, luego 1.25 para 4) y había que recalcularlo a mano cada
        // vez que se agregaba o quitaba un símbolo principal — ahora no.
        val mainRowWeight = 10f / (2 + 2 + KeyboardLayouts.SYMBOLS_MAIN.size)
        val mainRow = newRow()
        mainRow.addView(symbolKey(nextSideSymbol(), main = false, weight = mainRowWeight, configIndex = sideIndex - 1))
        mainRow.addView(symbolKey(nextSideSymbol(), main = false, weight = mainRowWeight, configIndex = sideIndex - 1))
        KeyboardLayouts.SYMBOLS_MAIN.indices.forEach { i ->
            mainRow.addView(symbolKey(KeyboardLayouts.effectiveSymbol(settingsSource, main = true, index = i).toString(), main = true, weight = mainRowWeight, configIndex = i))
        }
        mainRow.addView(symbolKey(nextSideSymbol(), main = false, weight = mainRowWeight, configIndex = sideIndex - 1))
        mainRow.addView(symbolKey(nextSideSymbol(), main = false, weight = mainRowWeight, configIndex = sideIndex - 1))
        keysColumn.addView(mainRow)

        // 8 símbolos + "⌫" al final = 9 celdas; weight recalculado a 10/9 para
        // que la fila siga sumando 10 y quede alineada con digitsRow/mainRow.
        val sideRow = newRow()
        while (sideIndex < KeyboardLayouts.SYMBOLS_SIDE.size) {
            sideRow.addView(symbolKey(nextSideSymbol(), main = false, weight = 10f / 9f, configIndex = sideIndex - 1))
        }
        sideRow.addView(eraseKey(weight = 10f / 9f))
        keysColumn.addView(sideRow)

        keysColumn.addView(bottomRow(showSymbolsOrigin = false))
    }

    /** Contenido del popup numérico/calculadora (hold en "?123"): ocupa el
     *  MISMO área que QWERTY, no hay fila inferior duplicada — "ABC" dentro
     *  de la propia grilla cierra explícitamente la pantalla. */
    private fun renderNumpadContent() {
        val grid = LinearLayout(context)
        grid.orientation = LinearLayout.VERTICAL
        KeyboardLayouts.NUMPAD_GRID.forEach { gridRow ->
            val rowLayout = newRow()
            gridRow.forEach { key -> rowLayout.addView(numpadKey(key)) }
            grid.addView(rowLayout)
        }
        keysColumn.addView(grid)
    }

    private fun toggleShift() {
        val now = System.currentTimeMillis()
        val isDoubleTap = now - lastShiftTapAt <= SHIFT_DOUBLE_TAP_MS
        lastShiftTapAt = now
        shiftState = when {
            isDoubleTap -> ShiftState.CAPS_LOCK
            shiftState == ShiftState.OFF -> ShiftState.ONE_SHOT
            else -> ShiftState.OFF // ONE_SHOT→OFF o CAPS_LOCK→OFF
        }
        // Doble tap confirmado (entra a CAPS_LOCK): única rama de este
        // método que corresponde a un doble tap real, ver pedido de
        // feedback háptico en doble-tap.
        if (isDoubleTap) hapticTap(HapticKind.HOLD)
        render()
    }

    private fun goToScreen(newScreen: Screen) {
        screen = newScreen
        render()
    }

    private fun onBackspace() {
        cancelAnchorSelectionIfArmed()
        target?.backspaceAtCursor()
    }

    private fun onKeyTap(s: String) {
        cancelAnchorSelectionIfArmed()
        target?.insertAtCursor(s)
    }

    /** Inserción específica de una letra QWERTY (tap simple en letterKey, o
     *  elección en accentKey/su tap-base) — a diferencia de onKeyTap
     *  genérico (espacio, enter, símbolos, paste...), esta SÍ debe apagar
     *  el shift de "una sola mayúscula" después de escribir (pedido
     *  explícito: "una mayúscula que se auto-desactiva tras la letra").
     *  En CAPS_LOCK no se apaga —sigue en mayúsculas indefinidamente hasta
     *  que el usuario vuelva a tocar ⇧. */
    private fun onLetterTap(ch: Char) {
        onKeyTap(ch.toString())
        if (shiftState == ShiftState.ONE_SHOT) {
            shiftState = ShiftState.OFF
            render()
        }
    }

    // ---------- construcción de filas/teclas ----------

    private fun newRow(): LinearLayout {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        // Sin bottomMargin (ver keyGapBackground()): mismo fix que el hueco
        // de touch horizontal entre teclas, aplicado ahora también al eje
        // vertical. El margin entre filas no pertenecía al hitbox de
        // ninguna tecla de ninguna de las dos filas — un toque justo ahí no
        // activaba nada. El espacio vertical ahora vive como inset dentro
        // de cada celda (altura de celda = KEY_HEIGHT_DP + KEY_GAP_DP), así
        // las filas se tocan sin hueco muerto entre sus hitboxes.
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        return row
    }

    private fun letterKey(ch: Char): View {
        // ACCENT_MAP está indexado por la letra base en minúscula: con shift
        // activo la tecla muestra "N" pero el hold debe seguir ofreciendo
        // "ñ", no buscar una entrada "N" que no existe. Mismo criterio para
        // el mapa de acciones del drag-abajo (Paso 7, DEFAULT_DRAG_ACTIONS):
        // indexado también por minúscula.
        // Paso 7 (menú de configuración, §5): se lee siempre a través de
        // effectiveAccentVariants/effectiveDragAction (override guardado o
        // default de fábrica), nunca directo de ACCENT_MAP/
        // DEFAULT_DRAG_ACTIONS — ver doc de esas funciones en
        // KeyboardLayouts.
        val variants = KeyboardLayouts.effectiveAccentVariants(settingsSource, ch.lowercaseChar())
        val dragAction = KeyboardLayouts.effectiveDragAction(settingsSource, ch.lowercaseChar())
        // Paso 7 (§5, modo configuración): en SettingsActivity, un tap
        // simple en CUALQUIER letra abre su panel de edición — incluida
        // una letra sin nada asignado todavía (p.ej. "h"): el panel la
        // muestra con 0 variantes y sin atajo, con los botones [+] para
        // agregar el primero (ver ConfigPanelView.renderLetterList). Antes
        // esas letras quedaban fuera de alcance (lambda vacía) porque no
        // había nada que "editar" — pero sí hay algo que AGREGAR, que es
        // justo el caso que [+] existe para cubrir. Bug reportado y
        // corregido.
        if (configMode) {
            return baseKey(
                label = ch.toString(), bg = KEY_BG, textColor = KEY_TEXT, textSizeSp = KEY_TEXT_SIZE_SP, weight = 1f
            ) {
                configPanel.showLetterPanel(ch.lowercaseChar(), onDismiss = {}, onChanged = { onConfigPanelChanged() })
            }
        }
        if (variants.isEmpty() && dragAction == null) {
            return baseKey(
                label = ch.toString(), bg = KEY_BG, textColor = KEY_TEXT, textSizeSp = KEY_TEXT_SIZE_SP, weight = 1f
            ) { onLetterTap(ch) }
        }
        // Con variantes de acento y/o acción de drag-abajo: mantener
        // presionado NO inserta nada automáticamente. El hold abre un único
        // popup con AMBAS zonas a la vez (variantes arriba, drag-acción
        // abajo, ver gestureKey()/AccentPopupView) — no hay ambigüedad que
        // resolver después, el release decide solo. El tap normal (sin
        // pasar el umbral de hold) sigue insertando la letra base como
        // cualquier tecla.
        return gestureKey(ch, variants, dragAction)
    }

    /** Fusiona dos gestos de hold+release sobre la misma tecla (Paso 7): el
     *  popup lateral de acentos (ver doc histórica de accentKey, ahora
     *  parte de esta función) y el nuevo drag-abajo de acciones nombradas
     *  (DragAction). Varias letras (a, d, e, f, s) tienen ambos a la vez —
     *  Android solo permite un setOnTouchListener por vista, así que no se
     *  pueden mantener como dos funciones/listeners separados.
     *
     *  Paso 7 (§4, corrección del diseño original): variantes de acento y
     *  drag-acción de una misma letra NO compiten por el mismo gesto — ya
     *  conviven en el MISMO popup, en zonas opuestas (variantes arriba,
     *  drag-acción abajo, ver doc de clase de AccentPopupView). Un intento
     *  anterior (descartado) trataba de arbitrar con umbrales de distancia
     *  cuál de los dos "ganaba" el gesto post-hold; eso fallaba siempre a
     *  favor del popup porque su umbral lateral se cruzaba antes que el
     *  umbral vertical del drag-acción, sin importar la dirección real del
     *  dedo — el enfoque entero estaba mal planteado, no era un bug de
     *  calibrar mejor esos números. Ahora, al cumplirse el hold,
     *  accentPopup.start() se llama una sola vez con AMBAS zonas (vacías
     *  si la letra no las tiene) y todo el resto — incluida la repetición
     *  continua de UNDO/REDO — se resuelve adentro de AccentPopupView. */
    private fun gestureKey(ch: Char, variants: List<Char>, dragAction: KeyboardLayouts.DragAction?): View {
        val view = baseKey(ch.toString(), KEY_BG, KEY_TEXT, KEY_TEXT_SIZE_SP, 1f) {}
        val longPressHandler = Handler(Looper.getMainLooper())
        var longPressRunnable: Runnable? = null
        var popupActive = false
        var holdFired = false
        var downX = 0f
        var downY = 0f
        // Paso 7 (solo tecla E): true cuando el hold de ESTE gesto canceló
        // el modo de ancla ya armado (ver doc de anchorSelectionArmed) en
        // vez de proceder con la decisión normal acento/drag-acción — el
        // release correspondiente no debe hacer nada más (ni insertar "e"
        // ni abrir nada), a diferencia del resto de teclas donde un hold
        // sin mover el dedo cae de vuelta a onLetterTap en el ACTION_UP.
        var anchorCancelledThisGesture = false
        var savedSuggestions: List<String> = emptyList()
        val repeatHandler = Handler(Looper.getMainLooper())
        var repeatRunnable: Runnable? = null
        // Paso 7 (SAVE sostenido -> SAVE_AS, pedido explícito): true desde
        // que se cumple DRAG_ACTION_REPEAT_THRESHOLD_MS sosteniendo la
        // zona de acción de una DragAction con entrada en
        // KeyboardLayouts.HOLD_SWITCHES_TO, hasta que el gesto termina. El
        // release de ESTE gesto, si sigue dentro de la zona, dispara la
        // acción de REEMPLAZO en vez de la original — ver
        // onConfirmAction/scheduleHoldSwitchIfNeeded. No es el mismo timer
        // que scheduleRepeatIfNeeded (ese repite la MISMA acción en ticks;
        // este cambia una sola vez cuál acción dispara el próximo
        // release), pero usa el mismo Handler y el mismo umbral de 1s.
        var switchedByHold = false
        // Pendiente reportado: SAVE_AS (y cualquier futura entrada de
        // HOLD_SWITCHES_TO) debía disparar en el instante en que se cumple
        // el umbral de hold, no esperar al release — ver runnable de
        // scheduleHoldSwitchIfNeeded más abajo. holdSwitchFired distingue
        // ese disparo YA ocurrido: a diferencia de switchedByHold (que solo
        // registra "cuál acción aplicaría si soltara ahora" y se resetea al
        // salir de la zona en onActionZoneExited), esto no se resetea
        // dentro del mismo gesto — una vez disparado, el ACTION_UP de este
        // gesto no debe hacer nada más (ni reaplicar la acción, ni caer en
        // onLetterTap como tap simple).
        var holdSwitchFired = false

        fun cancelRepeat() {
            repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
            repeatRunnable = null
        }

        fun scheduleHoldSwitchIfNeeded(action: KeyboardLayouts.DragAction, isStillInActionZone: () -> Boolean) {
            val switchTo = KeyboardLayouts.HOLD_SWITCHES_TO[action] ?: return
            val runnable = Runnable {
                if (!isStillInActionZone()) return@Runnable
                switchedByHold = true
                holdSwitchFired = true
                // Cierra el popup ya (mismo cancel() que usa AccentPopupView
                // internamente antes de invocar onConfirmAction en el
                // camino normal de release, ver su onTouchEvent) para que
                // no quede el hint "Guardar como" flotando en pantalla
                // mientras se abre el diálogo encima, y para que el
                // ACTION_UP que llegue después (dedo recién soltado o
                // todavía sostenido) no intente reenviarle el evento a un
                // popup que ya no debe significar nada.
                accentPopup.cancel()
                popupActive = false
                applyDragAction(switchTo)
            }
            repeatRunnable = runnable
            repeatHandler.postDelayed(runnable, DRAG_ACTION_REPEAT_THRESHOLD_MS)
        }
        // Bandera que el propio Runnable revisa ANTES de abrir el popup:
        // handler.removeCallbacks() no tiene efecto si el Runnable ya fue
        // despachado por el Looper (está corriendo o a punto de correr) en
        // el instante en que llega ACTION_UP/CANCEL — ahí cancelPendingLongPress()
        // no evitaría nada, y accentPopup.start() podría ejecutarse DESPUÉS
        // de que ACTION_UP ya insertó la letra base, dejando el popup
        // abierto "fantasma" sin ningún gesto real sosteniéndolo. El próximo
        // toque en el teclado interactuaría entonces con ese estado
        // inconsistente, lo que podría provocar un
        // IndexOutOfBoundsException en AccentPopupView.onTouchEvent.
        // gestureEnded se marca true en cuanto termina el gesto, y el
        // Runnable la chequea antes de hacer nada.
        var gestureEnded = false

        fun cancelPendingLongPress() {
            gestureEnded = true
            longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
            longPressRunnable = null
        }

        // accentPopup es MATCH_PARENT sobre el FrameLayout raíz de
        // activity_editor.xml (toda la pantalla, ver doc de clase), así que
        // su sistema de coordenadas locales coincide con las coordenadas de
        // VENTANA (getLocationInWindow) — pero no necesariamente con las de
        // PANTALLA (rawX/rawY), que es lo que se usa abajo al reenviar el
        // gesto. Ambas solo coinciden cuando la ventana arranca en (0,0) de
        // la pantalla; en landscape, con una franja de sistema que desplaza
        // el origen de la ventana, quedaría un offset constante entre el
        // dedo real y dónde lo interpreta el popup, desplazando los gestos
        // de hold (el tap simple no tiene este problema porque no reenvía
        // nada, usa onLetterTap directo). rawToWindow() mide ese delta en
        // tiempo real desde la propia tecla y lo aplica antes de reenviar.
        fun originTopLeft(): Pair<Float, Float> {
            val loc = IntArray(2)
            view.getLocationInWindow(loc)
            return loc[0].toFloat() to loc[1].toFloat()
        }
        fun rawToWindow(rawX: Float, rawY: Float): Pair<Float, Float> {
            val screenLoc = IntArray(2)
            val windowLoc = IntArray(2)
            view.getLocationOnScreen(screenLoc)
            view.getLocationInWindow(windowLoc)
            val dx = (windowLoc[0] - screenLoc[0]).toFloat()
            val dy = (windowLoc[1] - screenLoc[1]).toFloat()
            return Pair(rawX + dx, rawY + dy)
        }

        // Paso 7 (repetición continua): arranca el timer de 1s que, de
        // seguir con el dedo en la zona de acción sin soltar, empieza a
        // repetir [action] cada DRAG_ACTION_REPEAT_TICK_MS hasta que salga
        // de la zona o suelte — mismo patrón que RingGestureView.
        // scheduleNavModeDecision/startContinuousInner. No-op si
        // !action.repeatsOnHold. El primer disparo de la acción (el del
        // release normal en modo "un solo step") NO lo cuenta este timer —
        // cuenta aparte, así que al activarse la repetición continua la
        // acción ya se ejecutó una vez por ese primer disparo y este tick
        // arranca sumando repeticiones ADICIONALES, igual que mantener una
        // tecla física lo haría. [isStillInActionZone] lo pasa gestureKey()
        // (no lee estado propio de AccentPopupView) para no crear una
        // dependencia circular entre ambas vistas.
        fun scheduleRepeatIfNeeded(action: KeyboardLayouts.DragAction, isStillInActionZone: () -> Boolean) {
            if (!action.repeatsOnHold) return
            val thresholdRunnable = Runnable {
                if (!isStillInActionZone()) return@Runnable
                val tick = object : Runnable {
                    override fun run() {
                        if (!isStillInActionZone()) return
                        applyDragAction(action)
                        repeatHandler.postDelayed(this, DRAG_ACTION_REPEAT_TICK_MS)
                    }
                }
                repeatRunnable = tick
                repeatHandler.postDelayed(tick, DRAG_ACTION_REPEAT_TICK_MS)
            }
            repeatRunnable = thresholdRunnable
            repeatHandler.postDelayed(thresholdRunnable, DRAG_ACTION_REPEAT_THRESHOLD_MS)
        }

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    popupActive = false
                    holdFired = false
                    gestureEnded = false
                    anchorCancelledThisGesture = false
                    switchedByHold = false
                    val runnable = Runnable {
                        if (gestureEnded) return@Runnable
                        holdFired = true
                        // Paso 7 (tecla E, caso especial): con el ancla ya
                        // armada, un hold simple cancela el modo en vez de
                        // abrir el popup — ya no hay ambigüedad que
                        // resolver porque el modo armado redefine lo que
                        // significa "hold en E".
                        if (ch == 'e' && anchorSelectionArmed) {
                            anchorCancelledThisGesture = true
                            cancelAnchorSelectionIfArmed()
                            hapticTap(HapticKind.HOLD)
                            return@Runnable
                        }
                        if (variants.isEmpty() && dragAction == null) return@Runnable
                        popupActive = true
                        hapticTap(HapticKind.HOLD)
                        if (::suggestionsBar.isInitialized && dragAction != null) {
                            savedSuggestions = suggestionsBar.currentSuggestionsSnapshot()
                            suggestionsBar.show(listOf(dragAction.hint))
                        }
                        val (ox, oy) = originTopLeft()
                        accentPopup.start(
                            originX = ox + view.width / 2f,
                            originY = oy,
                            originKeyWidth = view.width.toFloat(),
                            originKeyHeight = view.height.toFloat(),
                            options = variants,
                            actionHint = dragAction?.hint,
                            onConfirmVariant = { chosen -> onLetterTap(chosen) },
                            onConfirmAction = {
                                // holdSwitchFired ya disparó la acción de
                                // reemplazo en el propio timer (ver
                                // scheduleHoldSwitchIfNeeded) y canceló el
                                // popup — este callback no debería llegar a
                                // invocarse en ese caso (accentPopup.cancel()
                                // vacía options/actionHint, y onTouchEvent
                                // sale temprano con ambos vacíos), pero se
                                // guarda explícito para no depender de ese
                                // detalle interno y evitar aplicar la acción
                                // dos veces si el orden de eventos cambiara.
                                if (dragAction != null && !holdSwitchFired) {
                                    // Si el hold sostenido ya cambió de
                                    // acción (ver scheduleHoldSwitchIfNeeded),
                                    // el release dispara la de REEMPLAZO,
                                    // no la original.
                                    val toApply = if (switchedByHold) {
                                        KeyboardLayouts.HOLD_SWITCHES_TO[dragAction] ?: dragAction
                                    } else {
                                        dragAction
                                    }
                                    applyDragAction(toApply)
                                }
                            },
                            onActionZoneEntered = {
                                if (dragAction != null) {
                                    // Una acción no declara ambos a la vez
                                    // (ver doc de HOLD_SWITCHES_TO/
                                    // repeatsOnHold): solo uno de los dos
                                    // timers puede estar activo por gesto.
                                    if (KeyboardLayouts.HOLD_SWITCHES_TO.containsKey(dragAction)) {
                                        scheduleHoldSwitchIfNeeded(dragAction) { accentPopup.isInActionZone() }
                                    } else {
                                        scheduleRepeatIfNeeded(dragAction) { accentPopup.isInActionZone() }
                                    }
                                }
                            },
                            onActionZoneExited = {
                                cancelRepeat()
                                // Salir de la zona sin soltar reinicia el
                                // modo: si vuelve a entrar, debe volver a
                                // sostener desde cero para llegar a
                                // SAVE_AS, no quedar "pegado" al cambio ya
                                // hecho.
                                switchedByHold = false
                                if (::suggestionsBar.isInitialized && dragAction != null) {
                                    suggestionsBar.show(listOf(dragAction.hint))
                                }
                            }
                        )
                    }
                    longPressRunnable = runnable
                    longPressHandler.postDelayed(runnable, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (popupActive) {
                        val (wx, wy) = rawToWindow(event.rawX, event.rawY)
                        val forwarded = MotionEvent.obtain(event)
                        forwarded.setLocation(wx, wy)
                        accentPopup.dispatchTouchEvent(forwarded)
                        forwarded.recycle()
                    } else if (!holdFired) {
                        // Antes de cumplirse el hold: movimiento más allá
                        // del slop cancela el long-press pendiente (gesto
                        // normal de tap-y-arrastre, no debe abrir nada).
                        // Tras cumplirse el hold ya no hay nada más que
                        // decidir acá — ver doc de clase: el popup se abrió
                        // de una con ambas zonas, sin fase intermedia.
                        val moved = hypot((event.rawX - downX).toDouble(), (event.rawY - downY).toDouble()).toFloat()
                        if (moved > dpf(DRAG_SLOP_DP)) cancelPendingLongPress()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    gestureEnded = true
                    if (anchorCancelledThisGesture) {
                        // Ya se canceló el ancla en el hold (ver runnable):
                        // este release no inserta "e" ni hace nada más.
                    } else if (holdSwitchFired) {
                        // SAVE_AS (u otra entrada de HOLD_SWITCHES_TO) ya se
                        // disparó en el propio timer de hold (ver
                        // scheduleHoldSwitchIfNeeded) y canceló el popup —
                        // popupActive ya está en false, así que sin esta
                        // rama este release caería en el else de abajo y
                        // trataría el gesto como tap simple, insertando la
                        // letra base (ej. "s") de más.
                        cancelRepeat()
                    } else if (popupActive) {
                        cancelRepeat()
                        if (::suggestionsBar.isInitialized && dragAction != null) {
                            suggestionsBar.show(savedSuggestions)
                        }
                        val (wx, wy) = rawToWindow(event.rawX, event.rawY)
                        val forwarded = MotionEvent.obtain(event)
                        forwarded.setLocation(wx, wy)
                        accentPopup.dispatchTouchEvent(forwarded)
                        forwarded.recycle()
                        popupActive = false
                    } else {
                        cancelPendingLongPress()
                        // Bug reportado: gestureKey() instala su propio
                        // setOnTouchListener, que nunca dispara el
                        // setOnClickListener de baseKey (ver su doc) — así
                        // que el tap simple de una letra CON variantes/
                        // drag-action (A, D, E, F, S...) nunca llamaba a
                        // hapticTap(), a diferencia de cualquier otra tecla
                        // sin gesto propio.
                        hapticTap()
                        onLetterTap(ch)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelPendingLongPress()
                    if (popupActive) {
                        cancelRepeat()
                        if (::suggestionsBar.isInitialized && dragAction != null) {
                            suggestionsBar.show(savedSuggestions)
                        }
                        accentPopup.cancel()
                        popupActive = false
                    }
                    true
                }
                else -> false
            }
        }
        return view
    }

    /** Tecla de la fila inferior especial. En fase 1 solo implementa el tap
     *  de cada una; los "hold" (popup numérico, anillos) llegan en fases 2/5. */
    private fun specialKey(label: String, weight: Float, onTap: () -> Unit): View {
        return baseKey(
            label = label,
            bg = KEY_BG_SPECIAL,
            textColor = KEY_TEXT_DIM,
            textSizeSp = KEY_TEXT_SIZE_SP,
            weight = weight,
            onTap = onTap
        )
    }

    /** Tecla ⇧ con un indicador visual del estado: un
     *  punto debajo de la flecha en ONE_SHOT (próxima letra en mayúscula,
     *  se apaga sola después), una línea en CAPS_LOCK (mayúsculas
     *  indefinidas), y nada en OFF. Es una specialKey normal (mismo fondo/
     *  tamaño que baseKey) envuelta en un FrameLayout propio solo para
     *  poder anclar el indicador debajo del texto sin tocar baseKey —
     *  ninguna otra tecla lo necesita. */
    private fun shiftKey(weight: Float): View {
        val container = FrameLayout(context)
        // Sin margin lateral ni vertical (ver keyGapBackground()): mismo
        // fix que baseKey, para que no quede un hueco de touch entre ⇧ y
        // sus vecinas (erase a la derecha, la fila QWERTY a la izquierda,
        // la fila de arriba/abajo).
        container.layoutParams = LinearLayout.LayoutParams(0, dp(KEY_HEIGHT_DP) + dp(KEY_GAP_DP), weight)
        container.background = keyGapBackground(KEY_BG_SPECIAL)

        val label = TextView(context)
        label.text = "⇧"
        label.gravity = Gravity.CENTER
        label.setTextColor(KEY_TEXT_DIM)
        label.textSize = KEY_TEXT_SIZE_SP
        container.addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val indicator = View(context)
        val indicatorParams = FrameLayout.LayoutParams(dp(SHIFT_INDICATOR_WIDTH_DP), dp(SHIFT_INDICATOR_HEIGHT_DP))
        indicatorParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        indicatorParams.bottomMargin = dp(SHIFT_INDICATOR_MARGIN_BOTTOM_DP)
        indicator.layoutParams = indicatorParams
        val indicatorDrawable = GradientDrawable()
        indicatorDrawable.setColor(KEY_TEXT_DIM)
        indicator.background = indicatorDrawable
        container.addView(indicator)
        shiftIndicatorView = indicator
        updateShiftIndicator()

        container.setOnClickListener { toggleShift() }
        return container
    }

    /** Punto (ONE_SHOT) vs. línea (CAPS_LOCK) vs. oculto (OFF): mismo View
     *  reutilizado, solo cambia su tamaño/radio — más simple que dos Views
     *  separados con visibilidad alternada. Se llama desde toggleShift()
     *  (vía render→renderQwertyContent→shiftKey) cada vez que el estado
     *  cambia, y una vez al crear la tecla. */
    private fun updateShiftIndicator() {
        val indicator = shiftIndicatorView ?: return
        val params = indicator.layoutParams as? FrameLayout.LayoutParams ?: return
        val drawable = indicator.background as? GradientDrawable ?: return
        when (shiftState) {
            ShiftState.OFF -> {
                indicator.visibility = View.INVISIBLE
            }
            ShiftState.ONE_SHOT -> {
                params.width = dp(SHIFT_INDICATOR_WIDTH_DP)
                params.height = dp(SHIFT_INDICATOR_WIDTH_DP)
                drawable.cornerRadius = dpf(SHIFT_INDICATOR_WIDTH_DP / 2f)
                indicator.layoutParams = params
                indicator.visibility = View.VISIBLE
            }
            ShiftState.CAPS_LOCK -> {
                params.width = dp(SHIFT_INDICATOR_LOCK_WIDTH_DP)
                params.height = dp(SHIFT_INDICATOR_HEIGHT_DP)
                drawable.cornerRadius = dpf(SHIFT_INDICATOR_HEIGHT_DP / 2f)
                indicator.layoutParams = params
                indicator.visibility = View.VISIBLE
            }
        }
    }

    /** Tecla ⌫ con borrado continuo al mantener presionada: un tap simple
     *  sigue borrando 1 carácter (onBackspace);
     *  sosteniendo, tras LONG_PRESS_MS arranca un timer que repite cada
     *  ERASE_REPEAT_MS. Durante los primeros ERASE_WORD_MODE_AFTER_MS de
     *  hold CONTINUO borra un carácter por tick; pasado ese umbral cambia a
     *  borrar una palabra completa por tick (backspaceWordAtCursor) — el
     *  cambio de modo es por tiempo total sosteniendo, no se reinicia entre
     *  ticks. Visualmente es una specialKey normal (mismo estilo); solo el
     *  gesto cambia, vía setOnTouchListener en vez de setOnClickListener
     *  (igual patrón que accentKey/ringOriginKey: un listener reemplaza al
     *  otro, nunca convive con setOnClickListener en la misma vista). */
    private fun eraseKey(weight: Float): View {
        val view = baseKey(
            label = "⌫",
            bg = KEY_BG_SPECIAL,
            textColor = KEY_TEXT_DIM,
            textSizeSp = KEY_TEXT_SIZE_SP,
            weight = weight,
            onTap = {}
        )
        val repeatHandler = Handler(Looper.getMainLooper())
        var repeatRunnable: Runnable? = null
        var holdStartedAt = 0L

        fun cancelRepeat() {
            repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
            repeatRunnable = null
        }

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Primer borrado inmediato (comportamiento de tap normal
                    // si se suelta antes de LONG_PRESS_MS) + arranque del
                    // timer de hold, igual mecánica que accentKey/
                    // ringOriginKey.
                    onBackspace()
                    holdStartedAt = System.currentTimeMillis()
                    val runnable = object : Runnable {
                        override fun run() {
                            val wordMode = System.currentTimeMillis() - holdStartedAt >= ERASE_WORD_MODE_AFTER_MS
                            if (wordMode) target?.backspaceWordAtCursor() else onBackspace()
                            repeatHandler.postDelayed(this, ERASE_REPEAT_MS)
                        }
                    }
                    repeatRunnable = runnable
                    repeatHandler.postDelayed(runnable, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelRepeat()
                    true
                }
                else -> false
            }
        }
        return view
    }

    /** [showSymbolsOrigin] = true dibuja "\#$" (estamos en QWERTY/shift);
     *  false dibuja "ABC" para volver (estamos en la pantalla de símbolos).
     *  Constante en toda pantalla QWERTY/shift/símbolos, como en el mockup.
     *
     *  "(" abre su anillo (cuarto de círculo hacia la derecha) desde el
     *  segundo lugar de la fila, dejando más espacio libre a su izquierda.
     *  TAB tiene tap y hold distintos pero sin drag (ver holdableKey en
     *  bottomRow) — su anillo de navegación vive en "␣": hold+drag, con la
     *  variante de que un hold SIN moverse
     *  del centro ya arma el timer de step-down/continuo (ver
     *  RingGestureView.start()). "↵" es también un ring-origin-key: cuarto
     *  de círculo hacia la izquierda con los símbolos que viven del lado
     *  izquierdo, más "paste" en su centro. */
    private fun bottomRow(showSymbolsOrigin: Boolean): View {
        val row = newRow()
        // Tap inserta el carácter literal (vía CodeEditText.insertAtCursor +
        // su TextWatcher, sin autocierre); hold+drag+release abre el cuarto
        // de círculo hacia la derecha (RING_BRACKETS ya trae su propio
        // startAngle/endAngle, ver KeyboardLayouts).
        // Paso 7 (menú de configuración, §5): tap-base y RingSpec se leen
        // siempre vía effectiveRingOriginTap/effectiveRingSpec (override o
        // default), nunca directo de KeyboardLayouts.RING_BRACKETS — ver
        // doc de esas funciones.
        val bracketsTap = KeyboardLayouts.effectiveRingOriginTap(settingsSource, KeyboardSettingsStore.EditableRing.BRACKETS, "(")
        row.addView(
            // Paso 7 (§5, modo configuración): tap simple abre el panel del
            // anillo BRACKETS (tap-base + 5 sectores) en vez del gesto
            // normal de hold+drag — ver doc de configMode/ConfigPanelView.
            if (configMode) {
                baseKey(label = bracketsTap, bg = KEY_BG_SPECIAL, textColor = KEY_TEXT_DIM, textSizeSp = KEY_TEXT_SIZE_SP, weight = 1f) {
                    configPanel.showRingPanel(KeyboardSettingsStore.EditableRing.BRACKETS, hasTapBase = true, onDismiss = {}, onChanged = { onConfigPanelChanged() })
                }
            } else {
                ringOriginKey(
                    label = bracketsTap,
                    weight = 1f,
                    ringSpec = KeyboardLayouts.effectiveRingSpec(settingsSource, KeyboardSettingsStore.EditableRing.BRACKETS),
                    isNavRing = false,
                    onTap = { onKeyTap(bracketsTap) }
                )
            }
        )
        // Paso 7 (§5, modo configuración): numpad, espacio (RING_NAV) y tab
        // quedan fuera de alcance. "ABC"/símbolos es la EXCEPCIÓN: la
        // pantalla de símbolos sí debe poder alcanzarse en configMode (ahí
        // viven SYMBOLS_MAIN/SYMBOLS_SIDE, editables), así que su onTap de
        // navegación se deja igual que en modo normal; solo el onHold
        // (→ NUMPAD, fuera de alcance) se neutraliza.
        val originKey = if (configMode) {
            if (showSymbolsOrigin) {
                withCornerHint(
                    holdableKey(label = "\\#${'$'}", weight = 1.3f, onTap = { goToScreen(Screen.SYMBOLS) }, onHold = {}),
                    hintText = "123"
                )
            } else {
                specialKey("ABC", weight = 1.6f) { goToScreen(Screen.QWERTY) }
            }
        } else if (showSymbolsOrigin) {
            // Tap abre símbolos; hold abre el popup numérico de pantalla
            // completa. El label "\#$" son justo los 3 símbolos principales
            // de la pantalla que abre (ver SYMBOLS_MAIN).
            // Hint "123" (mismo patrón que el hint de ringOriginKey para el
            // carácter central del anillo, ver withCornerHint()): este
            // botón abre símbolos con tap pero el popup NUMÉRICO con hold,
            // y nada en su label de por sí sugiere esa segunda acción
            // (a diferencia de ringOriginKey, donde el centro del anillo SÍ
            // tiene un carácter concreto que mostrar). "123" en la esquina
            // adelanta qué hay detrás del hold sin tener que descubrirlo.
            withCornerHint(
                holdableKey(
                    label = "\\#${'$'}",
                    weight = 1.3f,
                    onTap = { goToScreen(Screen.SYMBOLS) },
                    onHold = { goToScreen(Screen.NUMPAD) }
                ),
                hintText = "123"
            )
        } else {
            specialKey("ABC", weight = 1.6f) { goToScreen(Screen.QWERTY) }
        }
        row.addView(originKey)
        val operatorsTap = KeyboardLayouts.effectiveRingOriginTap(settingsSource, KeyboardSettingsStore.EditableRing.OPERATORS, "=")
        val equalsKey = if (configMode) {
            baseKey(label = operatorsTap, bg = KEY_BG_SPECIAL, textColor = KEY_TEXT_DIM, textSizeSp = KEY_TEXT_SIZE_SP, weight = 1f) {
                configPanel.showRingPanel(KeyboardSettingsStore.EditableRing.OPERATORS, hasTapBase = true, onDismiss = {}, onChanged = { onConfigPanelChanged() })
            }
        } else {
            ringOriginKey(
                label = operatorsTap,
                weight = 1f,
                ringSpec = KeyboardLayouts.effectiveRingSpec(settingsSource, KeyboardSettingsStore.EditableRing.OPERATORS),
                isNavRing = false,
                onTap = { onKeyTap(operatorsTap) }
            )
        }
        equalsKeyView = equalsKey
        row.addView(equalsKey)
        row.addView(
            // ␣ (U+2423, "OPEN BOX"): glifo convencional de "espacio visible"
            // en editores de texto. Ahora también origen de RING_NAV (el
            // hold de navegación que antes vivía en "⇥"): tap normal sigue
            // insertando un espacio sin cambios. Espacio/RING_NAV NUNCA es
            // editable (ver doc de configMode) — sin bifurcación, siempre
            // ringOriginKey normal (inerte igual en configMode, ya que ahí
            // nadie llama a esta vista con un touch real de todos modos,
            // pero se mantiene el mismo código por simplicidad: no hay
            // nada que "apagar").
            ringOriginKey(label = "␣", weight = 3.7f, textSizeSp = 27f, ringSpec = KeyboardLayouts.RING_NAV, isNavRing = true, onTap = { onKeyTap(" ") })
        )
        row.addView(
            // TAB: tap indenta (con selección: el inicio de cada línea que
            // toca; sin selección: inserta "\t" literal en el cursor); hold
            // desindenta (con selección: cada línea que toca; sin
            // selección: la línea actual). Reemplaza al viejo gesto de
            // pinch sobre el gutter del editor (bug: no distinguía
            // selección activa) — ver KeyboardTarget.tabKey/
            // CodeEditText.tabKey. holdableKey ya cubre tap vs. long-press
            // sin necesitar drag. Tab NUNCA es editable (ver doc de
            // configMode): en ese modo las lambdas simplemente no hacen
            // nada útil (target?.tabKey no se nota sin texto que editar en
            // la pantalla de configuración), no se bifurca.
            holdableKey(
                label = "⇥",
                weight = 1f,
                onTap = { target?.tabKey(increase = true) },
                onHold = { target?.tabKey(increase = false) }
            )
        )
        row.addView(
            // "↵" también es ring-origin-key: cuarto de círculo hacia
            // la izquierda (RING_PASTE) con "paste" en su centro en vez de
            // un carácter — ver applyRingResult() para la acción real. El
            // tap normal sigue insertando "\n" sin cambios.
            // "↵" (el carácter de tap) NO es editable (ver doc del Paso 7,
            // §5: solo su anillo lo es), así que a diferencia de "(" / "="
            // no pasa por effectiveRingOriginTap — solo el RingSpec usa el
            // override. En configMode, tap simple abre el panel del anillo
            // PASTE SIN fila de tap-base (hasTapBase=false, ver
            // ConfigPanelView.renderRingList) — Enter mismo no es editable.
            if (configMode) {
                baseKey(label = "↵", bg = KEY_BG_SPECIAL, textColor = KEY_TEXT_DIM, textSizeSp = KEY_TEXT_SIZE_SP, weight = 1.6f) {
                    configPanel.showRingPanel(KeyboardSettingsStore.EditableRing.PASTE, hasTapBase = false, onDismiss = {}, onChanged = { onConfigPanelChanged() })
                }
            } else {
                ringOriginKey(
                    label = "↵",
                    weight = 1.6f,
                    ringSpec = KeyboardLayouts.effectiveRingSpec(settingsSource, KeyboardSettingsStore.EditableRing.PASTE),
                    isNavRing = false,
                    onTap = { onKeyTap("\n") }
                )
            }
        )
        return row
    }

    // Paso 7 (menú de configuración, §5): configIndex es el índice real
    // dentro de SYMBOLS_MAIN/SYMBOLS_SIDE (según [main]) para ESTE símbolo
    // puntual — se necesita solo en configMode, para poder abrir
    // configPanel.showSymbolEdit(main, index, ...) sobre el símbolo exacto
    // que se tocó, en vez de insertar su carácter como en modo normal.
    private fun symbolKey(label: String, main: Boolean, weight: Float, configIndex: Int = -1): View {
        return baseKey(
            label = label,
            bg = if (main) KEY_BG else KEY_BG_SPECIAL,
            textColor = if (main) KEY_TEXT else KEY_TEXT_DIM,
            // 30f para los símbolos "principales" es deliberado (se quedan
            // más grandes a propósito, para distinguirlos de los
            // secundarios en esta pantalla) — solo el secundario se unifica.
            textSizeSp = if (main) 30f else KEY_TEXT_SIZE_SP,
            weight = weight
        ) {
            if (configMode && configIndex >= 0) {
                configPanel.showSymbolEdit(
                    main = main,
                    index = configIndex,
                    onDismiss = {},
                    onChanged = { onConfigPanelChanged() }
                )
            } else {
                onKeyTap(label)
            }
        }
    }

    private fun numpadKey(key: KeyboardLayouts.NumpadKey): View {
        val bg = if (key.kind == KeyboardLayouts.NumpadKeyKind.ERASE) 0xFF4A2B2B.toInt() else KEY_BG
        val textColor = if (key.kind == KeyboardLayouts.NumpadKeyKind.ERASE) 0xFFEE8888.toInt() else KEY_TEXT
        val weight = 1f
        return baseKey(key.label, bg, textColor, KEY_TEXT_SIZE_SP, weight) {
            when (key.kind) {
                KeyboardLayouts.NumpadKeyKind.ABC -> goToScreen(Screen.QWERTY)
                KeyboardLayouts.NumpadKeyKind.ERASE -> onBackspace()
                KeyboardLayouts.NumpadKeyKind.SPACE -> onKeyTap(" ")
                KeyboardLayouts.NumpadKeyKind.ENTER -> onKeyTap("\n")
                else -> onKeyTap(key.label)
            }
        }
    }

    /** Tecla con tap y long-press distintos, sin drag (long-press simple).
     *  Es el mismo mecanismo base que usan las variantes por hold
     *  (acentos); los anillos agregan drag+sector sobre esta misma idea de
     *  "tap dispara algo, hold dispara otra cosa". */
    private fun holdableKey(label: String, weight: Float, onTap: () -> Unit, onHold: () -> Unit): View {
        val view = specialKey(label, weight, onTap)
        view.setOnLongClickListener { onHold(); true }
        return view
    }

    /** Envuelve [view] en un FrameLayout con [hintText] chico en la esquina
     *  inferior derecha — mismo patrón visual que el hint de
     *  carácter central en ringOriginKey() (RING_HINT_TEXT_SIZE_SP/
     *  RING_HINT_MARGIN_DP/RING_HINT_TEXT_COLOR), generalizado para
     *  cualquier tecla, no solo las de anillo: toma los LayoutParams que
     *  [view] ya traía (de baseKey/specialKey, vía la fila que la agrega)
     *  y se los pasa al wrapper, para que siga ocupando el mismo espacio en
     *  la fila; [view] pasa a MATCH_PARENT dentro del wrapper, así que su
     *  listener de touch (holdableKey ya le puso OnClickListener/
     *  OnLongClickListener encima de ella, no del wrapper) sigue recibiendo
     *  toda el área del botón sin cambios. */
    private fun withCornerHint(view: View, hintText: String): View {
        val container = FrameLayout(context)
        container.layoutParams = view.layoutParams
        view.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        container.addView(view)

        val hint = TextView(context)
        hint.text = hintText
        hint.setTextColor(RING_HINT_TEXT_COLOR)
        hint.textSize = RING_HINT_TEXT_SIZE_SP
        val hintParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        hintParams.gravity = Gravity.BOTTOM or Gravity.END
        hintParams.bottomMargin = dp(RING_HINT_MARGIN_DP)
        hintParams.marginEnd = dp(RING_HINT_MARGIN_DP)
        hint.layoutParams = hintParams
        // Igual que en ringOriginKey(): el hint no debe robarle touches al
        // botón real, que vive debajo en el mismo FrameLayout.
        hint.isClickable = false
        hint.isFocusable = false
        container.addView(hint)

        return container
    }

    /**
     * Tecla origen de un anillo (hold+drag+release): "(" "=" "⇥" en la fila
     * inferior. Un tap breve (sin pasar el umbral de hold, o sin moverse lo
     * suficiente) dispara [onTap] como cualquier tecla normal — el usuario
     * que solo quiere insertar "(" no tiene que aprender nada nuevo. Si el
     * dedo se sostiene [LONG_PRESS_MS] sin soltar, se activa [ringOverlay]
     * centrado arriba de esta tecla; de ahí en más todo el drag lo procesa
     * RingGestureView (coordenadas convertidas a las del overlay, que cubre
     * toda la vista), y esta tecla ya no vuelve a recibir touch events hasta
     * el próximo gesto (Android se los sigue mandando a esta vista porque
     * fue ella la que recibió el ACTION_DOWN, así que se los reenvía).
     */
    private fun ringOriginKey(
        label: String,
        weight: Float,
        ringSpec: KeyboardLayouts.RingSpec,
        isNavRing: Boolean,
        textSizeSp: Float = KEY_TEXT_SIZE_SP,
        onTap: () -> Unit
    ): View {
        // El tap se resuelve a mano en ACTION_UP (más abajo), no vía el
        // OnClickListener que instala baseKey: con setOnTouchListener
        // devolviendo true en todos los casos, Android nunca llega a
        // sintetizar el click, así que ese listener quedaría muerto —
        // se pasa un lambda vacío para que quede explícito.
        val view = baseKey(label, KEY_BG_SPECIAL, KEY_TEXT_DIM, textSizeSp, weight) {}
        val longPressHandler = Handler(Looper.getMainLooper())
        var longPressRunnable: Runnable? = null
        var ringActive = false
        var downX = 0f
        var downY = 0f
        // Ver el comentario detallado en gestureKey(): handler.removeCallbacks()
        // no cancela un Runnable que el Looper ya despachó (está corriendo o a
        // punto de correr) en el instante en que llega ACTION_UP/CANCEL. Sin
        // esta bandera, ringOverlay.start() podría ejecutarse recién DESPUÉS de
        // que ACTION_UP ya resolvió el gesto como tap normal, abriendo un
        // anillo "fantasma" sin gesto real sosteniéndolo — lo que explicaría
        // que el hold a veces no abra nada (resuelto como tap) y que, una vez
        // que el estado quedara corrompido, solo se recuperara al
        // reconstruirse la tecla entera (p.ej. con shift, que fuerza un
        // render() nuevo).
        var gestureEnded = false

        fun cancelPendingLongPress() {
            gestureEnded = true
            longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
            longPressRunnable = null
        }

        /** Centro del semicírculo = punto medio del BORDE INFERIOR de esta
         *  tecla (no el superior: el "centro" es un rectángulo con el
         *  contorno exacto del botón —ver RingGestureView.start()— que debe
         *  quedar dibujado sobre el propio botón, no flotando arriba de él),
         *  en coordenadas de ventana absolutas — ringOverlay es
         *  MATCH_PARENT sobre el FrameLayout raíz de activity_editor.xml
         *  (toda la pantalla, ver doc de clase), así que su sistema de
         *  coordenadas locales coincide exactamente con las de ventana: no
         *  hace falta restar ni sumar ningún offset. También devuelve el
         *  ancho real de la tecla, para que el rectángulo-centro tenga su
         *  mismo ancho exacto en vez de un valor hardcodeado.
         *
         * Esto no significa que el resto del gesto tampoco necesite
         * conversión: el MOVIMIENTO del dedo se reenvía más abajo con
         * event.rawX/rawY (coordenadas de PANTALLA), que solo coinciden
         * con las de ventana si la ventana arranca en (0,0) de la pantalla.
         * En landscape, con una franja de sistema que desplaza ese origen,
         * no coinciden, lo que desplazaría los gestos de hold hacia la
         * derecha del dedo real. Ver rawToWindow() más abajo, que sí hace
         * esa conversión para cada evento reenviado. */
        // Lado del botón del que nace el arco:
        // un cuarto de círculo que abre hacia la derecha (startAngle<=90,
        // caso "(") debe nacer del borde IZQUIERDO del botón, no de su
        // centro horizontal — igual, espejado, para uno que abre hacia la
        // izquierda (endAngle>=90, caso "↵"): nace del borde DERECHO. Con
        // originX en el centro del botón, la mitad del arco quedaba
        // "desperdiciada" superpuesta sobre el propio botón en vez de
        // extenderse hacia la pantalla libre, dando la sensación de anillo
        // despegado del borde real. El semicírculo completo (brackets ya no
        // es este caso, pero operators/nav sí) sigue centrado como antes.
        val sweepFull = ringSpec.startAngle - ringSpec.endAngle >= 179.0
        fun originInOverlay(): Triple<Float, Float, Float> {
            val loc = IntArray(2)
            view.getLocationInWindow(loc)
            val x = when {
                sweepFull -> loc[0] + view.width / 2f
                ringSpec.startAngle <= 90.0 -> loc[0].toFloat() // borde izquierdo
                else -> (loc[0] + view.width).toFloat() // borde derecho
            }
            val y = (loc[1] + view.height).toFloat()
            return Triple(x, y, view.width.toFloat())
        }

        /** Radio total disponible para inner+outer (sin contar el espacio que
         *  ya ocupa el centro), común a los 4 orígenes de anillo, para que no
         *  varíen de tamaño entre sí, pero tampoco fijo en dp (lo que se
         *  saldría o se quedaría corto según el ancho real de pantalla de
         *  cada dispositivo). Se mide en tiempo real desde el BORDE del
         *  rectángulo-centro de "=" (no desde su originX: medir desde el
         *  centro exacto sobre-restaría el radio disponible en teclas anchas
         *  como "␣", donde el centro ocupa más espacio y dejaría a
         *  inner+outer con menos grosor real que en teclas angostas) hasta
         *  el borde IZQUIERDO de pantalla: "=" es el origen más restrictivo
         *  (único sin un vecino de fila libre hacia ese lado), así que ese
         *  remanente sirve de tope para los otros 3 también. Un margen de
         *  seguridad (SAFETY_MARGIN_DP) evita que el trazo quede pegado al
         *  borde mismo. */
        fun maxRingRadius(): Float {
            val eqView = equalsKeyView
            val fallback = dpf(140f) // si "=" aún no se midió (width=0), piso razonable
            if (eqView == null || eqView.width == 0) return fallback
            val loc = IntArray(2)
            eqView.getLocationInWindow(loc)
            val eqCenterX = loc[0] + eqView.width / 2f
            // Mismo cálculo que RingGestureView.centerRect()/rCenter: la
            // diagonal del rectángulo-centro (mitad del ancho real de la
            // tecla, alto = KEY_HEIGHT_DP), duplicado a mano por la misma
            // razón ya documentada ahí.
            val eqRCenter = kotlin.math.hypot((eqView.width / 2f).toDouble(), dpf(KEY_HEIGHT_DP.toFloat()).toDouble()).toFloat()
            val available = eqCenterX - eqRCenter - dpf(SAFETY_MARGIN_DP)
            return available.coerceAtLeast(dpf(40f))
        }

        // En landscape, los gestos de hold se desplazarían hacia la derecha
        // del dedo real si no se corrige esto (el tap simple no tiene este
        // problema porque usa event.x/y normales, no rawX/rawY): al reenviar
        // el gesto a ringOverlay mientras está activo, se usa
        // event.rawX/rawY (coordenadas de PANTALLA) para
        // fijar la posición del MotionEvent reenviado, pero originInOverlay()
        // calcula el origen del anillo con getLocationInWindow (coordenadas
        // de VENTANA). Ambas coinciden solo cuando la ventana arranca en
        // (0,0) de la pantalla; en landscape, con una franja de sistema
        // (notch/cutout o barra de navegación) que desplaza el origen de la
        // ventana respecto al de la pantalla, quedaba un offset constante
        // entre dónde tocaba el dedo y dónde lo interpretaba el overlay.
        // rawToWindow() mide ese delta en tiempo real desde la propia tecla
        // (resta getLocationOnScreen - getLocationInWindow) y lo aplica a
        // cualquier coordenada de pantalla antes de reenviarla.
        fun rawToWindow(rawX: Float, rawY: Float): Pair<Float, Float> {
            val screenLoc = IntArray(2)
            val windowLoc = IntArray(2)
            view.getLocationOnScreen(screenLoc)
            view.getLocationInWindow(windowLoc)
            val dx = (windowLoc[0] - screenLoc[0]).toFloat()
            val dy = (windowLoc[1] - screenLoc[1]).toFloat()
            return Pair(rawX + dx, rawY + dy)
        }

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    ringActive = false
                    gestureEnded = false
                    val runnable = Runnable {
                        // Ver el comentario análogo en gestureKey(): guard
                        // defensivo contra UninitializedPropertyAccessException.
                        if (gestureEnded || !::ringOverlay.isInitialized) return@Runnable
                        ringActive = true
                        hapticTap(HapticKind.HOLD)
                        val (ox, oy, originW) = originInOverlay()
                        val maxRadius = maxRingRadius()
                        if (isNavRing) {
                            ringOverlay.start(
                                originX = ox, originY = oy, originWidth = originW, maxTotalRadius = maxRadius, spec = ringSpec, isNavRing = true,
                                onNavStep = { sym -> applyRingResult(sym) },
                                onNavContinuousTick = { sym -> applyRingResult(sym) }
                            )
                        } else {
                            ringOverlay.start(
                                originX = ox, originY = oy, originWidth = originW, maxTotalRadius = maxRadius, spec = ringSpec, isNavRing = false,
                                onRelease = { sym -> applyRingResult(sym) }
                            )
                        }
                    }
                    longPressRunnable = runnable
                    longPressHandler.postDelayed(runnable, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (ringActive) {
                        // El anillo ya está activo: todo el gesto pasa a
                        // RingGestureView, convertido a coordenadas de
                        // ventana (ver rawToWindow más arriba) para que
                        // coincida con el origen calculado por
                        // originInOverlay().
                        val (wx, wy) = rawToWindow(event.rawX, event.rawY)
                        val forwarded = MotionEvent.obtain(event)
                        forwarded.setLocation(wx, wy)
                        ringOverlay.dispatchTouchEvent(forwarded)
                        forwarded.recycle()
                    } else {
                        // Si se mueve más que el slop antes de que se arme el
                        // anillo, se cancela el long-press pendiente: no debe
                        // abrirse un anillo a mitad de un gesto de scroll/drag
                        // accidental que nunca llegó al umbral de hold.
                        val moved = hypot((event.rawX - downX).toDouble(), (event.rawY - downY).toDouble()).toFloat()
                        if (moved > dpf(DRAG_SLOP_DP)) cancelPendingLongPress()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    gestureEnded = true
                    if (ringActive) {
                        val (wx, wy) = rawToWindow(event.rawX, event.rawY)
                        val forwarded = MotionEvent.obtain(event)
                        forwarded.setLocation(wx, wy)
                        ringOverlay.dispatchTouchEvent(forwarded)
                        forwarded.recycle()
                        ringActive = false
                    } else {
                        cancelPendingLongPress()
                        onTap()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelPendingLongPress()
                    if (ringActive) {
                        ringOverlay.cancel()
                        ringActive = false
                    }
                    true
                }
                else -> false
            }
        }

        // Hint del carácter central: el botón
        // del centro del anillo expandido suele quedar tapado por el propio
        // dedo que sostiene el hold, así que no se ve qué carácter tiene
        // asociado hasta soltar (a veces por error). Se agrega un TextView
        // chico en la esquina inferior derecha de ESTA tecla (visible todo
        // el tiempo, antes de iniciar cualquier gesto), mismo patrón que
        // shiftKey() para su indicador: un FrameLayout propio que toma los
        // LinearLayout.LayoutParams que baseKey ya le dio a `view` (así
        // sigue ocupando el mismo espacio en la fila), con `view` dentro a
        // MATCH_PARENT (conserva su tamaño real: todo el cálculo de
        // geometría del anillo arriba sigue midiendo `view`, no el wrapper)
        // y el hint anclado encima con Gravity.
        val container = FrameLayout(context)
        container.layoutParams = view.layoutParams
        view.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        container.addView(view)

        val hintLabel = ringSpec.center
        val hint = TextView(context)
        hint.text = hintLabel
        hint.setTextColor(RING_HINT_TEXT_COLOR)
        hint.textSize = RING_HINT_TEXT_SIZE_SP
        val hintParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        hintParams.gravity = Gravity.BOTTOM or Gravity.END
        hintParams.bottomMargin = dp(RING_HINT_MARGIN_DP)
        hintParams.marginEnd = dp(RING_HINT_MARGIN_DP)
        hint.layoutParams = hintParams
        // El hint no debe interceptar el gesto de la tecla (tap/hold/drag ya
        // resueltos por el setOnTouchListener de `view`, más arriba): sin
        // esto, al estar encima en el FrameLayout, el hint se quedaría con
        // los touches que caigan en su pequeña esquina.
        hint.isClickable = false
        hint.isFocusable = false
        container.addView(hint)

        return container
    }

    private fun applyRingResult(symbol: String) {
        when (symbol) {
            "↓" -> target?.moveCursor(vertical = 1)
            "↑" -> target?.moveCursor(vertical = -1)
            "←" -> target?.moveCursor(horizontal = -1)
            "→" -> target?.moveCursor(horizontal = 1)
            "←←" -> target?.moveCursor(horizontal = -1)
            "↑↑" -> target?.moveCursor(vertical = -1)
            "→→" -> target?.moveCursor(horizontal = 1)
            else -> onKeyTap(symbol)
        }
    }

    /** Despacha el resultado confirmado de un gesto de drag-abajo (Paso 7,
     *  ver gestureKey()). CUT/COPY/PASTE/TOGGLE_COMMENT/ANCHOR_SELECTION se
     *  resuelven acá mismo porque CustomKeyboardView ya tiene todo lo
     *  necesario (igual que applyRingResult con sus propios símbolos); el
     *  resto requiere lógica de más arriba (guardar archivo, abrir
     *  buscador, etc.) y se despacha vía onDragAction — ver su doc,
     *  asignada por ControlsController. */
    private fun applyDragAction(action: KeyboardLayouts.DragAction) {
        when (action) {
            KeyboardLayouts.DragAction.CUT -> cutSelection()
            KeyboardLayouts.DragAction.COPY -> copySelection()
            KeyboardLayouts.DragAction.PASTE -> pasteFromClipboard()
            KeyboardLayouts.DragAction.TOGGLE_COMMENT -> target?.toggleLineComment()
            KeyboardLayouts.DragAction.ANCHOR_SELECTION -> armAnchorSelection()
            else -> onDragAction?.invoke(action)
        }
    }

    /** Paso 7 (tecla E, primer paso — ver doc de anchorSelectionArmed):
     *  arma el ancla en la posición ACTUAL del cursor de [target], no en
     *  ninguna posición del dedo (el cursor ya lo puso el usuario de
     *  antes, tocando el texto normalmente). selectModeActive/
     *  selectionAnchor son de CodeEditText, no de KeyboardTarget genérico
     *  (PlainEditTextTarget no tiene este concepto — un campo de un
     *  comando no necesita esta mecánica), así que se exige el cast acá en
     *  vez de ensanchar la interfaz; no-op silencioso si target no es un
     *  CodeEditText. */
    private fun armAnchorSelection() {
        val editor = target as? com.codemobile.editor.CodeEditText ?: return
        editor.selectModeActive = true
        editor.selectionAnchor = editor.selectionStart
        anchorSelectionArmed = true
        // Bug reportado y corregido: armar el ancla tocaba
        // editor.selectModeActive directo, sin avisarle a ControlBarView.
        // Su propio flag (selectModeActive, booleano SEPARADO del de
        // target, ver su doc de clase) se quedaba en false, así que
        // deactivateSelectMode() posterior (llamada desde
        // cancelAnchorSelectionIfArmed() vía onCopyOrPaste) cortaba en su
        // guard "if (!selectModeActive) return" ANTES de llegar a apagar
        // target?.selectModeActive — el modo ancla quedaba "cancelado" del
        // lado del teclado (anchorSelectionArmed=false) pero la selección
        // real en el editor nunca se apagaba. onActivateSelectMode avisa
        // ahora a quien conecte ese callback (ControlsController →
        // controlBar.activateSelectMode(), igual patrón que onCopyOrPaste).
        onActivateSelectMode?.invoke()
    }

    /** Paso 7 (tecla E): cancela el modo de ancla — apaga tanto la bandera
     *  propia de CustomKeyboardView como selectModeActive real en
     *  [target]/ControlBarView, reusando onCopyOrPaste (el mismo callback
     *  que ControlsController ya conecta a controlBar.deactivateSelectMode(),
     *  ver su doc de clase): el efecto que necesita esta cancelación es
     *  idéntico al que ya dispara copiar/pegar, así que no hace falta un
     *  callback nuevo. No-op si ya estaba apagada (evita apagar
     *  selectModeActive de nuevo si el usuario, por ejemplo, ya lo había
     *  apagado manualmente por otra vía). */
    private fun cancelAnchorSelectionIfArmed() {
        if (!anchorSelectionArmed) return
        anchorSelectionArmed = false
        onCopyOrPaste?.invoke()
    }

    /** Copiar (Paso 7: movido del anillo al drag-abajo de la tecla C). Solo
     *  actúa si hay una selección real (ver KeyboardTarget.
     *  selectedTextOrNull): sin selección, no hace nada — ya no cae a
     *  "copiar el archivo completo" como hacía la versión anterior basada
     *  en allText(), que sigue existiendo para Ctrl+A (seleccionar todo) y
     *  cualquier otro consumidor que sí quiera ese fallback. Apaga el modo
     *  de ancla de E si estaba armado (ver doc de anchorSelectionArmed):
     *  copiar no modifica el texto, pero "consume" la selección igual que
     *  cortar/pegar, conceptualmente. */
    private fun copySelection() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = target?.selectedTextOrNull()?.takeIf { it.isNotEmpty() } ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("", text))
        anchorSelectionArmed = false
        onCopyOrPaste?.invoke()
    }

    /** Cortar (Paso 7, nuevo — tecla X): copia la selección activa al
     *  portapapeles y la borra, igual criterio que copySelection() sobre la
     *  ausencia de selección (no-op, nunca corta "todo el archivo").
     *  insertAtCursor("") reemplaza/borra la selección activa sin tocar el
     *  resto del texto (mismo mecanismo ya usado por backspaceAtCursor).
     *  Apaga el modo de ancla de E si estaba armado (ver doc de
     *  copySelection). */
    private fun cutSelection() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = target?.selectedTextOrNull()?.takeIf { it.isNotEmpty() } ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("", text))
        target?.insertAtCursor("")
        anchorSelectionArmed = false
        onCopyOrPaste?.invoke()
    }

    /** Pegar (Paso 7: movido del anillo al drag-abajo de la tecla V): inserta
     *  el contenido actual del portapapeles del sistema en el cursor de
     *  [target].
     *
     *  BUG encontrado y eliminado (limpiaba el portapapeles al pegar): la
     *  versión anterior (pasteOrCopy, ya eliminada) vaciaba el portapapeles
     *  después de cada paste, para que el próximo toque "volviera a
     *  copiar" en vez de repetir el mismo paste — necesario en ese diseño
     *  porque un solo botón dual decidía qué hacer según si el portapapeles
     *  tenía contenido. Con copy/paste ya separados, esa limpieza ya no
     *  tiene ningún propósito y solo impedía pegar el mismo contenido una
     *  segunda vez — se quita sin reemplazo, el portapapeles del sistema
     *  queda intacto después de pegar, como es estándar en cualquier otra
     *  app. */
    private fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = clipboard.primaryClip
        val clipText = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotEmpty() }
        } else null
        clipText?.let { onKeyTap(it) }
        onCopyOrPaste?.invoke()
    }

    /** Paso 7 (§4, feedback háptico): punto único que consulta
     *  KeyboardSettingsStore.isHapticEnabled() antes de vibrar — ni
     *  baseKey() (tap normal) ni los 4 puntos de hold/drag/doble-tap que
     *  ya existían (gestureKey/onShiftTap) deben disparar una vibración
     *  directo, todos pasan por acá para respetar el toggle del menú de
     *  configuración desde un solo lugar.
     *
     *  Bug reportado: la vibración se sentía larga/incómoda al escribir
     *  rápido. Antes se usaba performHapticFeedback(HapticFeedbackConstants.
     *  VIRTUAL_KEY) — ya el feedback "más sutil" que ofrece Android, pero
     *  esa API deja la duración REAL a criterio del fabricante/versión del
     *  SO (varios dispositivos la alargan bastante más de lo que el nombre
     *  sugiere), sin forma de pedir una duración exacta. Vibrator.vibrate()
     *  con un VibrationEffect.createOneShot sí la fija en milisegundos
     *  concretos — [HapticKind] guarda duración+amplitud por tipo de gesto
     *  en vez de delegarlo a HapticFeedbackConstants. */
    private enum class HapticKind(val durationMs: Long, val amplitude: Int) {
        // Tap normal de tecla: el caso que se dispara en cada pulsación al
        // escribir, debe ser casi imperceptible en duración para no sumar
        // al escribir rápido.
        TAP(durationMs = 16L, amplitude = 110),
        // Gestos de hold/drag/doble-tap (accentos, anillos): más marcado a
        // propósito, ya lo era antes de esta configuración y no hay razón
        // para bajarle la intensidad ahora — pero sigue siendo corto.
        HOLD(durationMs = 15L, amplitude = 180),
    }

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun hapticTap(kind: HapticKind = HapticKind.TAP) {
        if (!KeyboardSettingsStore.isHapticEnabled(context)) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createOneShot(kind.durationMs, kind.amplitude))
    }

    private fun baseKey(
        label: String,
        bg: Int,
        textColor: Int,
        textSizeSp: Float,
        weight: Float,
        onTap: () -> Unit
    ): View {
        val tv = TextView(context)
        tv.text = label
        tv.gravity = Gravity.CENTER
        tv.setTextColor(textColor)
        tv.textSize = textSizeSp
        // Sin margin lateral ni vertical (ver keyGapBackground()): el
        // hitbox de esta vista ocupa el ancho completo que le da weight y
        // el alto KEY_HEIGHT_DP + KEY_GAP_DP, pegado al de las teclas
        // vecinas (misma fila y filas arriba/abajo), sin ningún hueco de
        // touch entre ellas.
        tv.layoutParams = LinearLayout.LayoutParams(0, dp(KEY_HEIGHT_DP) + dp(KEY_GAP_DP), weight)
        tv.background = keyGapBackground(bg)
        tv.setOnClickListener {
            // Paso 7 (§4): vibración en CUALQUIER tap de teclado, no solo
            // en los gestos de hold que ya la tenían — ver doc de
            // hapticTap(). baseKey es el punto común de todas las teclas
            // (letras, símbolos, numpad, especiales), así que un solo
            // cambio acá cubre todas.
            hapticTap()
            onTap()
        }
        return tv
    }

    /** Fondo de una tecla que deja el espaciado visual entre teclas (tanto
     *  horizontal como vertical) como PADDING interno (vía InsetDrawable)
     *  en vez de margin externo.
     *
     *  BUG encontrado y corregido (hueco de touch entre teclas), en dos
     *  partes:
     *  - Horizontal: con marginStart/marginEnd = dp(KEY_GAP_DP / 2) en cada
     *    tecla, el espacio visual entre dos teclas vecinas de la misma fila
     *    no pertenecía al hitbox de NINGUNA de las dos.
     *  - Vertical: el mismo problema, pero entre filas — newRow() aplicaba
     *    bottomMargin = dp(KEY_GAP_DP) al LinearLayout de la fila entera,
     *    espacio que tampoco pertenecía al hitbox de ninguna tecla de
     *    ninguna de las dos filas.
     *  En ambos casos, un toque justo en ese hueco (pocos px, perceptible
     *  para el usuario) no activaba nada. Con el espacio movido a padding
     *  de la propia vista en los dos ejes, la vista entera (hitbox
     *  incluido) ocupa el ancho completo asignado por weight y el alto
     *  completo KEY_HEIGHT_DP + KEY_GAP_DP, pegada a sus vecinas en los
     *  cuatro lados sin espacio muerto; el color de fondo se dibuja
     *  encogido hacia adentro (InsetDrawable) en los cuatro bordes, dejando
     *  ver el fondo del contenedor ahí — mismo resultado visual que antes,
     *  ahora sin deadzone de touch en ningún borde entre teclas. */
    private fun keyGapBackground(color: Int): android.graphics.drawable.Drawable {
        val shape = GradientDrawable()
        shape.cornerRadius = dpf(KEY_RADIUS_DP)
        shape.setColor(color)
        val halfGap = dp(KEY_GAP_DP / 2)
        return android.graphics.drawable.InsetDrawable(shape, halfGap, halfGap, halfGap, halfGap)
    }
}
