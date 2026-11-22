package com.codemobile.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.codemobile.R

/**
 * Contenedor del editor: gutter izquierdo (números de línea) + CodeEditText
 * + margen derecho (franja de diff "sin guardar", ver DiffMarginView).
 *
 * Gestos:
 * - Sobre el cuerpo del editor (CodeEditText, ver su onTouchEvent y
 *   dragMode, Paso 8): el drag de 1 dedo mueve el cursor (en vivo mientras
 *   se arrastra, o extiende selección si selectModeActive está activo) o
 *   hace scroll del contenido, horizontal y vertical, en vez del scroll
 *   nativo de Android, según el modo elegido con el botón dedicado de
 *   ControlBarView (dragModeButton) — ver el comentario en onTouchEvent
 *   sobre por qué nunca delega a super.
 *
 * El margen derecho YA NO tiene el gesto de long-press+drag para
 * seleccionar línea(s) (eliminado a propósito: esa franja pasó a usarse
 * para la franja de diff "sin guardar" y ambos usos competían por el mismo
 * espacio táctil/visual — ver DiffMarginView). Quien necesite seleccionar
 * línea(s) completas usa los métodos de selección existentes por fuera de
 * este gesto.
 *
 * El gutter YA NO tiene gesto de pinch para indentar/desindentar (bug
 * reportado: no distinguía selección activa, siempre indentaba solo la
 * línea de selectionStart aunque hubiera 2+ líneas seleccionadas). Esa
 * función se reemplazó por la tecla "⇥" del teclado propio (ver
 * KeyboardTarget.tabKey/CodeEditText.tabKey): tap indenta/inserta tab, hold
 * desindenta, y ambos sí operan sobre toda la selección cuando hay una.
 */
class EditorPaneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    val codeEditText = CodeEditText(context)
    private val gutter = FrameLayout(context)
    private val lineNumbers = TextView(context)
    private val rightMargin = DiffMarginView(context)

    init {
        orientation = HORIZONTAL

        // Ancho inicial placeholder: se recalcula más abajo, una vez que
        // lineNumbers ya tiene su textSize real configurado (gutterWidthForDigits
        // mide con lineNumbers.paint, que hasta entonces todavía tendría el
        // tamaño de fuente por defecto del TextView, no los 13sp reales).
        gutter.layoutParams = LayoutParams(dpToPx(34), LayoutParams.MATCH_PARENT)
        gutter.setBackgroundColor(ContextCompat.getColor(context, R.color.bg_gutter))

        codeEditText.layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        codeEditText.setBackgroundColor(Color.TRANSPARENT)
        codeEditText.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        codeEditText.textSize = 13f
        codeEditText.gravity = Gravity.TOP
        codeEditText.setPadding(dpToPx(4), dpToPx(8), dpToPx(4), dpToPx(8))
        codeEditText.setHorizontallyScrolling(true)

        // Números de línea: el único contenido del gutter (ya no tiene
        // gesto de pinch, ver doc de clase). No es un EditText aparte con su
        // propio scroll: comparte exactamente el mismo tamaño de fuente,
        // padding vertical y lineSpacing que codeEditText para que cada
        // número caiga a la altura visual correcta, y se desplaza a mano
        // siguiendo el scrollY real del editor (ver setupLineNumbers()) en
        // vez de depender de un scroll propio que podría desincronizarse.
        // setHorizontallyScrolling(true) en codeEditText (más abajo) implica
        // que nunca hace wrap: cada línea lógica ("\n") es siempre exactamente
        // una línea visual, así que contar '\n' basta para numerar.
        lineNumbers.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        lineNumbers.setTextColor(ContextCompat.getColor(context, R.color.text_dim))
        lineNumbers.textSize = codeEditText.textSize / resources.displayMetrics.scaledDensity
        lineNumbers.gravity = Gravity.END
        lineNumbers.setPadding(0, dpToPx(8), dpToPx(6), dpToPx(8))
        lineNumbers.setLineSpacing(codeEditText.lineSpacingExtra, codeEditText.lineSpacingMultiplier)
        lineNumbers.text = "1"
        gutter.addView(lineNumbers)
        // Recién acá lineNumbers.paint ya refleja su textSize real: ahora sí
        // se puede medir el ancho correcto para MIN_GUTTER_DIGITS (reemplaza
        // el placeholder de arriba).
        (gutter.layoutParams as LayoutParams).width = gutterWidthForDigits(MIN_GUTTER_DIGITS)

        rightMargin.layoutParams = LayoutParams(dpToPx(26), LayoutParams.MATCH_PARENT)
        // Fondo lo pinta DiffMarginView.onDraw (bg_gutter, igual que el
        // gutter izquierdo) — no setBackgroundColor acá, porque la vista
        // necesita distinguir ese fondo de las franjas de color por línea
        // dibujadas encima.

        addView(gutter)
        addView(codeEditText)
        addView(rightMargin)

        setupLineNumbers()
        // El tamaño de codeEditText (alto visible real) solo se conoce
        // tras el primer layout — recién ahí tiene sentido la primera
        // medición real del thumb (ver updateThumbMetrics). También
        // recalcula en cada layout posterior (rotar pantalla, mostrar/
        // ocultar teclado cambiando el alto disponible), no solo al
        // scrollear o editar.
        codeEditText.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            rightMargin.updateThumbMetrics(codeEditText)
        }
    }

    /** Recalcula el texto del gutter ("1\n2\n...\nN") cada vez que el texto
     *  queda asentado, y lo desplaza para que siga el scroll vertical del
     *  editor. Usa el callback onTextSettled de CodeEditText (ver su doc) en
     *  vez de un TextWatcher propio: un TextWatcher crudo sobre el mismo
     *  EditText recibe TAMBIÉN las notificaciones intermedias de ediciones
     *  programáticas internas (autoindentación tras Enter, undo/redo,
     *  autocompletado, autocierre de brackets, pinch-indent), cada una con
     *  el documento en un estado distinto al final — eso producía conteos
     *  de línea erróneos justo tras Enter, al pegar texto con saltos de
     *  línea, o al borrar uno. onTextSettled se dispara una sola vez por
     *  edición real ya completa, evitando ese problema.
     *  setOnScrollChangeListener (API 23+, minSdk de este proyecto es 26)
     *  corre en cada scroll, incluyendo el que dispara el propio usuario al
     *  arrastrar o el autoscroll al escribir al final de un archivo largo. */
    private fun setupLineNumbers() {
        codeEditText.onTextSettled = {
            refreshLineNumbers()
            refreshDiffMargin()
        }
        codeEditText.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            lineNumbers.scrollTo(0, scrollY)
            rightMargin.updateThumbMetrics(codeEditText)
        }
        rightMargin.onJumpToOffset = { scrollY ->
            codeEditText.scrollTo(codeEditText.scrollX, scrollY)
        }
        // Tocar el scrollbar mientras hay inercia en curso debe frenarla de
        // inmediato: si no, el fling sigue moviendo scrollY en paralelo al
        // jump-to-click, compitiendo por el control (pedido explícito del
        // usuario).
        rightMargin.onTouchDown = {
            codeEditText.cancelFling()
        }
    }

    /** Recalcula las franjas de "sin guardar" del margen derecho (ver
     *  DiffMarginView/LineDiff) cada vez que el texto queda asentado, mismo
     *  criterio y mismo punto de enganche que refreshLineNumbers (ver su
     *  doc) — onTextSettled, no un TextWatcher propio, para no recalcular en
     *  medio de ediciones programáticas internas todavía incompletas.
     *  openFile puede ser null momentáneamente (pestaña recién creada antes
     *  de asignarlo); sin savedContent que comparar, no hay nada que
     *  marcar.
     *
     *  Público (no solo interno a onTextSettled) porque guardar (botón
     *  Save, ver TabsController.persistCurrentFile) actualiza
     *  openFile.savedContent SIN pasar por ninguna edición de texto en el
     *  EditText — nada dispara onTextSettled en ese momento, así que sin
     *  este llamado explícito las franjas de "sin guardar" seguirían
     *  mostrando el estado de ANTES de guardar hasta la próxima tecla. */
    fun refreshDiffMargin() {
        val of = codeEditText.openFile ?: return
        val current = codeEditText.text?.toString() ?: ""
        rightMargin.statuses = computeLineStatuses(of.savedContent, current)
        // Mismo motivo que el post{} de refreshLineNumbers: recién en el
        // próximo frame el scrollY del editor refleja el valor real tras
        // esta edición (p.ej. Enter empujando la vista hacia abajo). El
        // alto total del documento también puede haber cambiado (líneas
        // agregadas/quitadas), así que el thumb de scroll (ver
        // DiffMarginView.updateThumbMetrics) se recalcula en el mismo
        // punto, no solo al scrollear.
        codeEditText.post {
            rightMargin.updateThumbMetrics(codeEditText)
        }
    }

    private fun refreshLineNumbers() {
        // layout puede ser null momentáneamente durante medidas iniciales;
        // contar líneas por saltos de '\n' en el texto (no por layout.lineCount)
        // evita depender de que el layout ya esté calculado en este punto.
        val lineCount = (codeEditText.text?.count { it == '\n' } ?: 0) + 1
        if (lineCount == currentLineCount) return
        currentLineCount = lineCount
        lineNumbers.text = (1..lineCount).joinToString("\n")
        updateGutterWidth(lineCount)
        // BUG encontrado (numeración "se reinicia" tras Enter, reportado
        // por el usuario con capturas: el gutter vuelve a mostrar "1, 2,
        // 3..." aunque el editor sigue mostrando, p.ej., la línea 533).
        // Asignar lineNumbers.text reinicia el scroll INTERNO de ese
        // TextView a (0,0) — Android hace esto siempre que cambia el
        // contenido de un TextView con scroll propio — así que hace falta
        // reaplicar el scroll real del editor después. El bug es que
        // onTextSettled (y por lo tanto esta función) corre SINCRÓNICAMENTE
        // dentro del propio TextWatcher de CodeEditText, ANTES de que
        // Android termine de recalcular el nuevo scrollY del EditText tras
        // el cambio de texto (p.ej. cuando Enter empuja el cursor/vista
        // hacia abajo) — leer codeEditText.scrollY en este mismo instante
        // da el valor VIEJO, de antes de la edición, no el que va a quedar
        // una vez que el layout se actualice. post{} difiere la lectura (y
        // el scrollTo) al próximo frame, cuando ese recálculo ya terminó.
        codeEditText.post {
            lineNumbers.scrollTo(0, codeEditText.scrollY)
        }
    }

    private var currentLineCount = 1
    private var currentGutterDigits = MIN_GUTTER_DIGITS

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    /** Cuántos dígitos tiene [lineCount] (ej. 999 -> 3, 1000 -> 4). */
    private fun digitsOf(lineCount: Int): Int = lineCount.toString().length

    /** Ancho del gutter para mostrar números de hasta [digits] dígitos sin
     *  recortarlos: mide el ancho real de esa cantidad de "0" (el dígito
     *  más ancho de cualquier fuente monoespaciada/tabular habitual) con el
     *  mismo tamaño de fuente que usa lineNumbers, y le suma el padding
     *  horizontal que ya tiene (6dp a la derecha, ver init) más un margen
     *  simétrico a la izquierda para que no quede pegado al borde. */
    private fun gutterWidthForDigits(digits: Int): Int {
        val textWidth = lineNumbers.paint.measureText("0".repeat(digits))
        return textWidth.toInt() + dpToPx(6) + dpToPx(6)
    }

    /** Recalcula el ancho del gutter según la cantidad de dígitos que exige
     *  [lineCount] (ver BUG: con ancho fijo en dp, un archivo de 1000+
     *  líneas no tenía espacio para el 4to dígito y los números quedaban
     *  cortados/envueltos — reportado por el usuario con capturas). Solo
     *  toca layoutParams cuando la cantidad de DÍGITOS cambia (no en cada
     *  línea agregada/quitada): pasar de 99 a 100 líneas no necesita más
     *  ancho, así que evita relayouts de más mientras el archivo crece
     *  dentro del mismo rango de dígitos. */
    private fun updateGutterWidth(lineCount: Int) {
        val digits = digitsOf(lineCount).coerceAtLeast(MIN_GUTTER_DIGITS)
        if (digits == currentGutterDigits) return
        currentGutterDigits = digits
        val lp = gutter.layoutParams as LayoutParams
        lp.width = gutterWidthForDigits(digits)
        gutter.layoutParams = lp
    }

    companion object {
        /** Ancho mínimo del gutter en dígitos, aunque el archivo tenga
         *  menos de 100 líneas (1 o 2 dígitos) — mismo ancho mínimo
         *  razonable que ya tenía el valor fijo anterior (34dp ~ 3
         *  dígitos), para no angostar el gutter de archivos chicos. */
        private const val MIN_GUTTER_DIGITS = 3
    }
}

/** Franja de "cambios sin guardar" del margen derecho (ver doc de clase de
 *  EditorPaneView): una línea de color fina por cada línea del archivo con
 *  texto AÑADIDO o MODIFICADO frente a savedContent (ver LineDiff), al
 *  estilo del gutter de diff de VS Code — pero sin git de por medio, porque
 *  compara contra el contenido tal como estaba la última vez que se abrió o
 *  guardó el archivo, no contra el último commit (ver computeLineStatuses).
 *  Líneas ELIMINADAS no se marcan (alcance reducido a propósito, ver
 *  LineStatus).
 *
 *  Además es una scrollbar FUNCIONAL del editor (pedido explícito: "no
 *  aparece la barra de desplazamiento" tras agregar las franjas de diff —
 *  antes esta franja era solo informativa). Dibuja un thumb que representa
 *  la porción visible del documento, y tocar en cualquier punto de la
 *  franja (no solo sobre el thumb) salta el scroll ahí mismo — "jump to
 *  click", pedido explícito en vez del estilo tradicional de arrastrar
 *  solo desde el thumb: en una franja de 26dp de ancho acertarle al thumb
 *  exacto sería poco práctico.
 *
 *  Reemplaza al FrameLayout vacío que antes solo servía de área táctil para
 *  el gesto de long-press+drag (ya eliminado, ver EditorPaneView). Se pinta
 *  a mano en onDraw en vez de usar vistas hijas por línea: con archivos de
 *  varios miles de líneas, una vista por línea sería mucho más cara que
 *  dibujar rectángulos directo sobre un Canvas.
 *
 *  Las franjas se dibujan en estilo "minimap" (pedido explícito): el
 *  estado de TODAS las líneas del documento se comprime proporcionalmente
 *  en la altura fija de esta vista (línea i -> i/totalLíneas * height), sin
 *  relación con el scroll actual del editor — así se ve de un vistazo
 *  dónde hay cambios sin guardar en el archivo completo, incluso en partes
 *  scrolleadas fuera de vista. Por eso esta vista ya no usa su propio
 *  scrollTo (ni franjas ni thumb lo necesitan: el thumb siempre se dibujó
 *  en coordenadas de viewport, ver updateThumbMetrics/onDraw). */
class DiffMarginView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var statuses: List<LineStatus> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** Invocado con el nuevo scrollY (coordenadas del documento, mismo
     *  sistema que CodeEditText.scrollY) cuando el usuario toca o arrastra
     *  sobre esta franja — EditorPaneView lo conecta a
     *  codeEditText.scrollTo. */
    var onJumpToOffset: ((Int) -> Unit)? = null

    /** Invocado en ACTION_DOWN, antes de jumpToY — EditorPaneView lo conecta
     *  a codeEditText.cancelFling() para que tocar el scrollbar frene de
     *  inmediato cualquier inercia en curso (ver doc de clase de
     *  CodeEditText.scroller). */
    var onTouchDown: (() -> Unit)? = null

    private val bgPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.bg_gutter)
    }
    private val addedPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.run_green)
    }
    private val modifiedPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.accent)
    }

    /** Semitransparente (alpha, no un color nuevo): el thumb se dibuja
     *  ENCIMA de las franjas de diff y no debe tapar del todo su color —
     *  ver doc de clase. */
    private val thumbPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.text_dim)
        alpha = 160
    }

    private val stripeWidthPx = (3 * resources.displayMetrics.density)

    /** Alto mínimo del thumb (ver su uso en updateThumbMetrics), para que
     *  siga siendo tocable/visible en documentos muy largos donde la
     *  proporción real daría un thumb de apenas unos píxeles — 24dp,
     *  escalado a la densidad real del dispositivo, no un valor crudo en px. */
    private val minThumbHeightPx = (24 * resources.displayMetrics.density)

    /** Alto y posición del thumb en coordenadas de VIEWPORT (0 = arriba de
     *  lo visible), recalculados por updateThumbMetrics. thumbHeightPx
     *  queda en 0 mientras no haya nada que scrollear (documento más corto
     *  que el viewport) — onDraw no dibuja ningún thumb en ese caso, ni
     *  onTouchEvent permite arrastrar/saltar (no tendría a dónde). */
    private var thumbTopPx = 0f
    private var thumbHeightPx = 0f

    /** editorMaxScrollY/editorViewportHeightPx: snapshot de
     *  codeEditText.maxScrollY()/height tomado en el último
     *  updateThumbMetrics, para no necesitar una referencia directa al
     *  editor dentro de onTouchEvent (jumpToY los usa para convertir la Y
     *  tocada en esta franja a un scrollY del documento). */
    private var editorMaxScrollY = 0
    private var editorViewportHeightPx = 0

    /** Recalcula thumbTopPx/thumbHeightPx a partir del estado real del
     *  editor — llamado desde EditorPaneView en cada scroll, tras cada
     *  edición (el alto total del documento cambia con el texto) y en cada
     *  cambio de layout (rotar pantalla, mostrar/ocultar teclado). Mismas
     *  proporciones que cualquier scrollbar: thumbHeight es la fracción
     *  del documento que entra en el viewport, thumbTop es esa misma
     *  fracción aplicada al recorrido que le queda al thumb (viewport menos
     *  su propio alto) en el punto de scroll actual. */
    fun updateThumbMetrics(editor: CodeEditText) {
        val viewportHeight = editor.height
        val maxScroll = editor.maxScrollY()
        editorMaxScrollY = maxScroll
        editorViewportHeightPx = viewportHeight
        if (viewportHeight <= 0 || maxScroll <= 0) {
            // Nada que scrollear (documento más corto que el viewport, o
            // medidas todavía no listas): sin thumb, y jumpToY no tiene
            // ningún destino válido al que saltar (ver su guard).
            thumbHeightPx = 0f
            thumbTopPx = 0f
            invalidate()
            return
        }
        val documentHeight = maxScroll + viewportHeight
        thumbHeightPx = (viewportHeight.toFloat() * viewportHeight / documentHeight)
            .coerceAtLeast(minThumbHeightPx)
        val scrollFraction = editor.scrollY.toFloat() / maxScroll
        thumbTopPx = scrollFraction * (viewportHeight - thumbHeightPx)
        invalidate()
    }

    /** Convierte una Y tocada (coordenadas de ESTA vista, ya en viewport —
     *  ver onTouchEvent) al scrollY de documento correspondiente, centrando
     *  el thumb en el punto tocado: mismo criterio "jump to click" de
     *  cualquier scrollbar que salta al tocar fuera del thumb, en vez de
     *  page-up/page-down. No hace nada si no hay thumb (nada que
     *  scrollear, ver updateThumbMetrics). */
    private fun jumpToY(touchY: Float) {
        if (thumbHeightPx <= 0f || editorMaxScrollY <= 0) return
        val usableTrack = (editorViewportHeightPx - thumbHeightPx).coerceAtLeast(1f)
        val desiredThumbTop = (touchY - thumbHeightPx / 2f).coerceIn(0f, usableTrack)
        val scrollFraction = desiredThumbTop / usableTrack
        val newScrollY = (scrollFraction * editorMaxScrollY).toInt().coerceIn(0, editorMaxScrollY)
        onJumpToOffset?.invoke(newScrollY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onTouchDown?.invoke()
                jumpToY(event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                // event.y ya llega en coordenadas de ESTA vista (viewport),
                // no de documento — a diferencia de onDraw, acá no hace
                // falta sumar scrollY: Android entrega las coordenadas de
                // touch sin trasladar por el scroll propio de la vista.
                jumpToY(event.y)
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        // Minimap: cada línea i se ubica en i/totalLíneas * height, sin
        // relación con lineHeightPx/scroll real — comprime el documento
        // entero en la altura fija de la franja (ver doc de clase).
        val totalLines = statuses.size
        if (totalLines > 0 && height > 0) {
            val pxPerLine = height.toFloat() / totalLines
            for ((index, status) in statuses.withIndex()) {
                if (status == LineStatus.UNCHANGED) continue
                val paint = if (status == LineStatus.ADDED) addedPaint else modifiedPaint
                val top = index * pxPerLine
                // Alto mínimo de 1px por franja: con documentos largos,
                // pxPerLine puede ser menor a 1px y la franja no pintaría
                // nada.
                val bottom = (top + pxPerLine).coerceAtLeast(top + 1f)
                canvas.drawRect(0f, top, stripeWidthPx, bottom, paint)
            }
        }
        if (thumbHeightPx > 0f) {
            canvas.drawRect(0f, thumbTopPx, width.toFloat(), thumbTopPx + thumbHeightPx, thumbPaint)
        }
    }
}
