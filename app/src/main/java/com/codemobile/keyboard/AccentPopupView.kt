package com.codemobile.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Popup de variantes por hold (acentos/letras griegas) y, desde el Paso 7
 * (§4, corrección del diseño original: "las variantes y el drag action
 * viven en el mismo popup, en zonas opuestas"), también la zona del
 * drag-abajo de la misma tecla — ambas conviven en el MISMO popup desde
 * que se cumple el hold, nunca una tras otra ni en competencia por el
 * mismo gesto: la fila de variantes arriba de la tecla (estilo Gboard,
 * como siempre), y la celda del drag-acción ("zona opuesta") debajo de la
 * tecla. No es un anillo (no hay coronas concéntricas ni matiz temporal,
 * es más simple que RingGestureView).
 *
 * Mecánica: CustomKeyboardView llama [start] en cuanto se detecta el hold
 * (sin ninguna fase "indecisa" previa — a diferencia de un intento anterior
 * descartado, que intentaba arbitrar con umbrales de distancia cuál de los
 * dos gestos "ganaba": eso fallaba porque ambos viven en zonas separadas
 * del mismo popup, no son gestos en competencia), posicionando este popup
 * sobre la tecla con sus opciones y, si corresponde, la celda de acción.
 * Desde ahí todo el drag se resuelve en esta vista: mover el dedo sobre una
 * opción resalta esa variante (ver [activeIndex]); mover el dedo sobre la
 * celda de acción la resalta también (ver [actionActive]/[isInActionZone])
 * y notifica el cruce de zona vía [onActionZoneEntered]/
 * [onActionZoneExited] — CustomKeyboardView usa ese aviso para arrancar o
 * parar la repetición continua de UNDO/REDO (DragAction.repeatsOnHold),
 * que esta vista no decide por sí misma. Soltar sobre una opción confirma
 * esa variante ([onConfirmVariant]); soltar sobre la celda de acción
 * confirma el drag-acción ([onConfirmAction]); soltar en cualquier otro
 * lugar (incluido volver a la tecla origen, el "centro") CANCELA sin hacer
 * nada, igual que siempre: ni variante, ni acción, ni letra base.
 */
class AccentPopupView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var options: List<Char> = emptyList()
    private var actionHint: String? = null
    private var originX = 0f
    private var originY = 0f
    private var originKeyWidth = 0f
    private var originKeyHeight = 0f
    private var activeIndex = -1
    // Paso 7: true cuando el dedo está sobre la celda de acción (zona
    // "opuesta" a las variantes, ver doc de clase) — análogo a activeIndex
    // pero para esa celda, que no vive en la lista `options`.
    // internal set permitido (fun isInActionZone() de abajo), privado desde
    // afuera salvo por esa lectura — CustomKeyboardView necesita saber si
    // el dedo SIGUE en la zona de acción para la repetición continua de
    // UNDO/REDO (ver gestureKey().scheduleRepeatIfNeeded), sin reimplementar
    // su propio chequeo de posición duplicando isOverActionZone() acá y
    // allá.
    private var actionActive = false
    private var onConfirmVariant: ((Char) -> Unit)? = null
    private var onConfirmAction: (() -> Unit)? = null
    // Paso 7 (repetición continua, ver DragAction.repeatsOnHold): se
    // invoca una vez al entrar a la celda de acción (arma, mismo rol que
    // startDragAction en CustomKeyboardView) y se apaga al salir o soltar.
    // La propia repetición en sí (el timer de 1s + tick) sigue viviendo en
    // CustomKeyboardView vía estos dos callbacks — este popup solo avisa
    // CUÁNDO el dedo entra/sale de la celda, no decide si repite.
    private var onActionZoneEntered: (() -> Unit)? = null
    private var onActionZoneExited: (() -> Unit)? = null

    /** true si el dedo está AHORA MISMO sobre la celda de acción — lectura
     *  externa de [actionActive] para la repetición continua de UNDO/REDO
     *  (ver doc de ese campo). */
    fun isInActionZone(): Boolean = actionActive

    companion object {
        private const val CELL_WIDTH_DP = 44f
        private const val CELL_HEIGHT_DP = 48f
        private const val GAP_ABOVE_ORIGIN_DP = 8f
        // Paso 7: celda de acción, debajo de la tecla origen — mismo ancho
        // de celda que las variantes (CELL_WIDTH_DP) para que se vea como
        // parte del mismo sistema visual, pero más alta (necesita caber el
        // hint de texto completo, p.ej. "Seleccionar todo", no un solo
        // carácter).
        // 130dp: ancho suficiente para el hint más largo ("Ancla de
        // selección", "Seleccionar todo") a ACTION_TEXT_SIZE_SP sin
        // recortarse — medido a ojo, no hay autoescalado de fuente acá
        // (canvas.drawText no hace wrap ni shrink-to-fit).
        private const val ACTION_CELL_WIDTH_DP = 130f
        private const val ACTION_CELL_HEIGHT_DP = 48f
        private const val GAP_BELOW_ORIGIN_DP = 8f
        private const val ACTION_ARMED_TINT = 0xFF5B8CFF.toInt()
    }

    init {
        setWillNotDraw(false)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /** Igual que dp() pero para tamaño de texto: usa scaledDensity, no
     *  density, así que respeta el tamaño de fuente del sistema igual que
     *  cualquier TextView.textSize en sp (el teclado entero usa sp — ver
     *  CustomKeyboardView.KEY_TEXT_SIZE_SP). dp() normal ignoraría ese
     *  ajuste, dejando las variantes con un tamaño que no escala igual que
     *  el resto del teclado si el usuario cambia el tamaño de fuente del
     *  sistema. */
    private fun spText(v: Float): Float = v * resources.displayMetrics.scaledDensity

    /**
     * Arranca el popup: [options] (puede ser vacía si la tecla solo tiene
     * drag-acción) para la fila de variantes arriba, y [actionHint] (null
     * si la tecla no tiene drag-acción) para la celda de abajo — ver doc
     * de clase, ambas zonas conviven desde que se llama esto, no hay fase
     * previa de decidir cuál mostrar. Coordenadas ([originX], [originY],
     * [originKeyWidth], [originKeyHeight]) de la tecla origen en esta
     * vista: [originY] es su borde superior (variantes arriba) y
     * [originY]+[originKeyHeight] su borde inferior (celda de acción
     * abajo). [onConfirmVariant]/[onConfirmAction] se invocan una sola vez
     * si el release cae en su zona; [onActionZoneEntered]/
     * [onActionZoneExited] avisan cuándo el dedo entra/sale de la celda de
     * acción (para que CustomKeyboardView arranque/pare la repetición
     * continua de UNDO/REDO, ver DragAction.repeatsOnHold — este popup no
     * decide esa repetición, solo notifica el cruce de zona). */
    fun start(
        originX: Float,
        originY: Float,
        originKeyWidth: Float,
        originKeyHeight: Float,
        options: List<Char>,
        actionHint: String?,
        onConfirmVariant: (Char) -> Unit,
        onConfirmAction: () -> Unit,
        onActionZoneEntered: () -> Unit,
        onActionZoneExited: () -> Unit
    ) {
        this.originX = originX
        this.originY = originY
        this.originKeyWidth = originKeyWidth
        this.originKeyHeight = originKeyHeight
        this.options = options
        this.actionHint = actionHint
        this.onConfirmVariant = onConfirmVariant
        this.onConfirmAction = onConfirmAction
        this.onActionZoneEntered = onActionZoneEntered
        this.onActionZoneExited = onActionZoneExited
        activeIndex = if (options.size == 1) 0 else -1
        actionActive = false
        visibility = VISIBLE
        invalidate()
    }

    fun cancel() {
        visibility = GONE
        options = emptyList()
        actionHint = null
        activeIndex = -1
        actionActive = false
    }

    private fun variantsRect(): RectF {
        val w = dp(CELL_WIDTH_DP) * options.size
        val h = dp(CELL_HEIGHT_DP)
        // Centrado en originX por defecto, pero sin salirse de los bordes
        // laterales de pantalla: para teclas pegadas al borde, como "a" en
        // la fila izquierda, el popup debe desplazarse hacia el lado que
        // tiene espacio en vez de recortarse/quedar medio fuera de pantalla.
        //
        // El clamp usa el ancho de pantalla real (displayMetrics) en vez de
        // this.width (el ancho medido de la propia View), porque
        // accentPopup arranca GONE en activity_editor.xml y nunca fue
        // VISIBLE antes de la primera vez que start() la activa — una View
        // GONE no se mide ni se dispone, así que su width seguiría siendo 0
        // en ese primer uso (start() llama invalidate(), que pide un
        // redibujo pero no fuerza una medición síncrona del layout; recién
        // hay un width real uno o más frames después). displayMetrics está
        // disponible siempre, sin depender del ciclo de vida de la vista.
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val left = (originX - w / 2f).coerceIn(0f, (screenWidth - w).coerceAtLeast(0f))
        val bottom = originY - dp(GAP_ABOVE_ORIGIN_DP)
        val top = bottom - h
        return RectF(left, top, left + w, bottom)
    }

    /** Paso 7: celda de la zona de acción, "opuesta" a variantsRect() —
     *  debajo de la tecla origen en vez de arriba, mismo centrado
     *  horizontal con el mismo clamp de bordes de pantalla. Vacía
     *  (todos los lados en 0, nunca contiene ningún touch) si la tecla no
     *  tiene drag-acción (actionHint null). */
    private fun actionRect(): RectF {
        if (actionHint == null) return RectF(0f, 0f, 0f, 0f)
        val w = dp(ACTION_CELL_WIDTH_DP)
        val h = dp(ACTION_CELL_HEIGHT_DP)
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val left = (originX - w / 2f).coerceIn(0f, (screenWidth - w).coerceAtLeast(0f))
        val top = originY + originKeyHeight + dp(GAP_BELOW_ORIGIN_DP)
        return RectF(left, top, left + w, top + h)
    }

    private fun indexAt(x: Float, y: Float): Int {
        val rect = variantsRect()
        if (options.isEmpty() || y < rect.top || y > rect.bottom) return -1
        if (x < rect.left || x > rect.right) return -1
        val cellWidth = rect.width() / options.size
        val idx = ((x - rect.left) / cellWidth).toInt()
        return idx.coerceIn(0, options.size - 1)
    }

    private fun isOverActionZone(x: Float, y: Float): Boolean {
        if (actionHint == null) return false
        val rect = actionRect()
        return x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (options.isEmpty() && actionHint == null) return false
        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                val idx = indexAt(event.x, event.y)
                if (idx != activeIndex) {
                    activeIndex = idx
                    invalidate()
                }
                val overAction = isOverActionZone(event.x, event.y)
                if (overAction != actionActive) {
                    actionActive = overAction
                    invalidate()
                    if (overAction) onActionZoneEntered?.invoke() else onActionZoneExited?.invoke()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Se capturan `options` y el índice ANTES de cancel() (que
                // vacía la lista): sin esto, options[confirmed] indexaría
                // contra la lista ya vaciada por cancel() si ambas líneas
                // corrieran en el mismo frame, provocando un
                // IndexOutOfBoundsException.
                val currentOptions = options
                val confirmedVariant = if (event.action == MotionEvent.ACTION_UP) {
                    indexAt(event.x, event.y)
                } else {
                    -1
                }
                val confirmedAction = event.action == MotionEvent.ACTION_UP && isOverActionZone(event.x, event.y)
                val variantCallback = onConfirmVariant
                val actionCallback = onConfirmAction
                cancel()
                // Cancela (no inserta nada, ni variante ni acción ni letra
                // base) si el release no cayó en ninguna zona válida.
                if (confirmedVariant in currentOptions.indices) {
                    variantCallback?.invoke(currentOptions[confirmedVariant])
                } else if (confirmedAction) {
                    actionCallback?.invoke()
                }
            }
        }
        return true
    }

    // ---------- dibujo ----------

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF262A33.toInt() }
    private val activeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5B8CFF.toInt() }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF15171C.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8E9EC.toInt()
        textAlign = Paint.Align.CENTER
        // El constructor de View ya corrió antes de que se inicialicen las
        // propiedades de esta subclase, así que `resources` (y por lo tanto
        // spText()) ya está disponible acá sin necesidad de un valor hardcodeado.
        // 22.5f para que coincida con CustomKeyboardView.KEY_TEXT_SIZE_SP
        // (mismo tamaño aparente que cualquier otra tecla del teclado).
        textSize = spText(22.5f)
    }

    // Paso 7: texto del hint de la celda de acción, más pequeño que
    // textPaint — "Seleccionar todo" no cabe al tamaño de una variante de
    // un solo carácter, así que esta celda usa su propio tamaño de fuente.
    private val actionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8E9EC.toInt()
        textAlign = Paint.Align.CENTER
        textSize = spText(13f)
    }
    private val activeActionBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACTION_ARMED_TINT }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cornerRadius = dp(8f)

        if (options.isNotEmpty()) {
            val rect = variantsRect()
            val cellWidth = rect.width() / options.size
            options.forEachIndexed { i, ch ->
                val cellLeft = rect.left + i * cellWidth
                val cellRect = RectF(cellLeft, rect.top, cellLeft + cellWidth, rect.bottom)
                val paint = if (i == activeIndex) activeBgPaint else bgPaint
                canvas.drawRoundRect(cellRect, cornerRadius, cornerRadius, paint)
                canvas.drawRoundRect(cellRect, cornerRadius, cornerRadius, strokePaint)
                val fm = textPaint.fontMetrics
                canvas.drawText(
                    ch.toString(),
                    cellRect.centerX(),
                    cellRect.centerY() - (fm.ascent + fm.descent) / 2f,
                    textPaint
                )
            }
        }

        val hint = actionHint
        if (hint != null) {
            val cellRect = actionRect()
            val paint = if (actionActive) activeActionBgPaint else bgPaint
            canvas.drawRoundRect(cellRect, cornerRadius, cornerRadius, paint)
            canvas.drawRoundRect(cellRect, cornerRadius, cornerRadius, strokePaint)
            val fm = actionTextPaint.fontMetrics
            canvas.drawText(
                hint,
                cellRect.centerX(),
                cellRect.centerY() - (fm.ascent + fm.descent) / 2f,
                actionTextPaint
            )
        }
    }
}
