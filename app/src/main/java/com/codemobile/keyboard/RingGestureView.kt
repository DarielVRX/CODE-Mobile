package com.codemobile.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Anillo real: dos coronas concéntricas de sectores contiguos (sin huecos),
 * más un centro, dibujadas con Canvas sobre el arco que nace arriba de la
 * tecla que originó el hold. El arco puede ser el semicírculo completo
 * (RING_OPERATORS, RING_NAV: 180°→0°) o un cuarto de círculo (RING_BRACKETS:
 * 90°→0° hacia la derecha; RING_PASTE: 180°→90° hacia la izquierda) — el
 * rango real lo define KeyboardLayouts.RingSpec.startAngle/endAngle, leído en
 * cada gesto. Comparte la misma mecánica base (hold+drag+release) entre los 4
 * anillos; solo el anillo de nav agrega el matiz temporal en su centro (ver
 * [isNavRing]).
 *
 * Geometría análoga a los <path> SVG del mockup original (halfRing() en
 * CodeMobile___Mockup_teclado_propio.html): mismos 3 radios (centro/interno/
 * externo), sectores iguales y contiguos dentro del arco que corresponda.
 *
 * Esta vista se agrega como overlay flotante (no ocupa espacio en el layout
 * normal del teclado) justo encima de la tecla origen cuando esta recibe un
 * ACTION_DOWN sostenido; CustomKeyboardView decide cuándo mostrarla/ocultarla
 * y le pasa las coordenadas de origen, el radio máximo real del dispositivo y
 * el spec del anillo a dibujar.
 */
class RingGestureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Sector resuelto del gesto en curso, o null si el dedo está sobre el
     *  centro o fuera de cualquier corona. */
    private sealed class Sector {
        object Center : Sector()
        data class Inner(val index: Int) : Sector()
        data class Outer(val index: Int) : Sector()
    }

    private var spec: KeyboardLayouts.RingSpec? = null
    private var isNavRing = false
    private var originX = 0f
    private var originY = 0f // borde INFERIOR de la tecla origen (ver CustomKeyboardView.originInOverlay)
    private var originWidth = 0f
    // Espacio disponible para inner+outer (SIN contar lo que ya ocupa el
    // centro) para el gesto en curso, calculado por CustomKeyboardView según
    // el dispositivo real (ver start()). rInner()/rOuter() lo suman sobre
    // rCenter() — ver comentario ahí para el porqué de este cambio.
    private var maxTotalRadius = 0f

    private var activeSector: Sector? = null
    private var onRelease: ((String) -> Unit)? = null

    /** Modo del gesto de navegación (solo [isNavRing]; sin corona externa):
     *  - [PENDING]: recién abierto por el hold de 350ms, decidiendo si pasa
     *    el segundo de umbral o se suelta antes.
     *  - [DRAG]: pasó 1s sin soltar — los 3 sectores de dirección se
     *    comportan como el viejo anillo externo (paso continuo mientras el
     *    dedo se mantenga en uno, labels con flecha doble). Se cierra al
     *    soltar, como cualquier otro anillo.
     *  - [FIXED]: se soltó ANTES del segundo de umbral — el anillo queda
     *    dibujado en pantalla (visibility sigue VISIBLE) y pasa a capturar
     *    touches NUEVOS e independientes (dedo ya levantado del gesto
     *    original): cada tap en el centro o en un sector hace un solo paso
     *    (labels normales) y el anillo permanece; un hold sobre cualquiera
     *    de los 4 en este modo no activa nada especial (mismo resultado que
     *    un tap); tocar fuera del área cierra el anillo sin acción. */
    private enum class NavMode { PENDING, DRAG, FIXED }
    private var navMode = NavMode.PENDING

    // ---- Matiz temporal del centro/sectores de navegación (solo isNavRing) ----
    private val holdHandler = Handler(Looper.getMainLooper())
    private var continuousRunnable: Runnable? = null
    private var centerHoldFired = false
    private var centerHoldContinuous = false
    private var onNavStep: ((String) -> Unit)? = null
    private var onNavContinuousTick: ((String) -> Unit)? = null
    /** Solo relevante en [NavMode.FIXED]: posición del último ACTION_DOWN de
     *  un tap nuevo, para poder distinguirlo de un hold sostenido sobre el
     *  mismo sector (que en este modo no debe activar nada especial: si se
     *  deja presionado, no pasa nada, se hace un solo step igual). Se
     *  resuelve siempre en el ACTION_UP de cada tap, nunca durante el hold
     *  en sí. */
    private var fixedModeDownSector: Sector? = null

    companion object {
        // El "centro" no es un círculo de radio fijo (ver CENTER_HEIGHT_DP
        // y resolveSector/drawCenterRect) sino un rectángulo con el
        // contorno exacto del botón origen, así que los radios del anillo
        // se miden desde ese mismo punto medio del borde inferior del
        // botón (originX, originY).
        //
        // El radio total no es un dp fijo: se saldría o se quedaría corto
        // según el ancho real de pantalla de cada dispositivo (p. ej. con
        // el botón TAB en el extremo derecho de la fila, un radio fijo
        // generoso se saldría del borde derecho en varios dispositivos).
        // CustomKeyboardView.maxRingRadius() lo calcula en tiempo real a
        // partir de la distancia del BORDE del centro de "=" (no de su
        // originX, ver ese método) al borde izquierdo de pantalla (el
        // origen más restrictivo) y lo pasa a start() como [maxTotalRadius]:
        // el espacio disponible para inner+outer, igual para los 4 orígenes
        // de anillo. Esta constante fija la PROPORCIÓN inner/outer dentro de
        // ESE espacio disponible (65%/35%: más ancho el interno, que es el
        // más usado, sin que el externo quede demasiado delgado) — ver
        // rInner()/rOuter(), que lo suman sobre rCenter().
        //
        // maxTotalRadius se mide desde originX, no desde el borde del
        // centro: medirlo desde el borde del centro haría que botones
        // anchos (originWidth grande → rCenter grande, como "␣")
        // terminaran con MENOS espacio real para las coronas que botones
        // angostos, aunque maxTotalRadius llegara con el mismo valor para
        // los 4 — las coronas de "␣" se verían más delgadas que las de "("
        // pese a la misma proporción nominal.
        private const val INNER_FRACTION = 0.65f
        /** Alto del rectángulo-centro (hit-test, sin cambios): igual al alto
         *  real de una tecla (CustomKeyboardView.KEY_HEIGHT_DP). Duplicado a
         *  mano porque esa constante es privada de CustomKeyboardView; si
         *  cambia ahí, hay que actualizar también acá (mismo trade-off ya
         *  documentado para CONTENT_MARGIN_EDITOR_MODE_DP en
         *  activity_editor.xml). */
        private const val CENTER_HEIGHT_DP = 42f
        private const val CENTER_HOLD_THRESHOLD_MS = 1000L
        private const val CONTINUOUS_TICK_MS = 120L
    }

    init {
        // La vista vive en un overlay que cubre toda la pantalla disponible
        // por encima del teclado; ella misma no pinta fondo, solo el anillo.
        setWillNotDraw(false)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /** Igual que dp() pero para tamaño de texto (scaledDensity, no density)
     *  — ver AccentPopupView.spText() para la misma corrección y su
     *  razón: sin esto, los símbolos dentro del anillo no escalan con el
     *  tamaño de fuente del sistema como el resto del teclado, y además el
     *  32f anterior era un valor crudo en px sin multiplicar por ninguna
     *  densidad, diminuto en pantallas de alta densidad. */
    private fun spText(v: Float): Float = v * resources.displayMetrics.scaledDensity

    /** Radio de arranque de las coronas: el borde del rectángulo-centro (la
     *  mitad de su diagonal), no 0 — así el primer anillo no se superpone
     *  con las esquinas del contorno del botón origen ni deja un hueco
     *  visible. maxTotalRadius ya viene medido desde este mismo borde (ver
     *  CustomKeyboardView.maxRingRadius()), así que inner/outer se suman
     *  sobre él en vez de repartirse sobre el radio total desde originX —
     *  medir desde originX sin descontar esto haría que botones con tecla
     *  ancha (como "␣", originWidth grande → rCenter grande) se quedaran
     *  con menos espacio real para las coronas que botones angostos,
     *  aunque maxTotalRadius fuera el mismo para los 4. */
    private fun rCenter(): Float = hypot((originWidth / 2f).toDouble(), dp(CENTER_HEIGHT_DP).toDouble()).toFloat()

    private fun rInner(): Float = rCenter() + maxTotalRadius * INNER_FRACTION
    private fun rOuter(): Float = rCenter() + maxTotalRadius

    /**
     * Arranca un gesto de anillo centrado en ([originX], [originY]) —
     * coordenadas de esta vista, no de la tecla origen (CustomKeyboardView
     * ya hace esa conversión al posicionar el overlay). [spec] define qué
     * va en cada sector. [isNavRing] habilita el comportamiento temporal del
     * centro; en ese caso se usan [onNavStep] (TAB único / un paso) y
     * [onNavContinuousTick] (se llama repetidamente mientras el dedo se
     * mantiene, hasta soltar) en vez de [onRelease].
     */
    fun start(
        originX: Float,
        originY: Float,
        originWidth: Float,
        maxTotalRadius: Float,
        spec: KeyboardLayouts.RingSpec,
        isNavRing: Boolean = false,
        onRelease: (String) -> Unit = {},
        onNavStep: (String) -> Unit = {},
        onNavContinuousTick: (String) -> Unit = {}
    ) {
        this.originX = originX
        this.originY = originY
        this.originWidth = originWidth
        this.maxTotalRadius = maxTotalRadius
        this.spec = spec
        this.isNavRing = isNavRing
        this.onRelease = onRelease
        this.onNavStep = onNavStep
        this.onNavContinuousTick = onNavContinuousTick
        activeSector = Sector.Center
        centerHoldFired = false
        centerHoldContinuous = false
        navMode = NavMode.PENDING
        fixedModeDownSector = null
        visibility = VISIBLE
        invalidate()
        // El gesto llega aquí ya habiendo pasado los 350ms de LONG_PRESS_MS
        // (eso lo decide CustomKeyboardView antes de llamar start()). A
        // partir de aquí se arma el segundo timer, el de 1s, que decide el
        // NavMode: si se sigue sosteniendo sin soltar, pasa PENDING→DRAG
        // (ver scheduleNavModeDecision); si se suelta antes, handleRelease
        // resuelve PENDING→FIXED.
        if (isNavRing) scheduleNavModeDecision()
    }

    /** Oculta el overlay y cancela cualquier temporizador de nav pendiente,
     *  sin disparar ninguna acción (usado cuando CustomKeyboardView necesita
     *  abortar el gesto, p.ej. al cambiar de pantalla) — incluye cerrar un
     *  anillo que estuviera en NavMode.FIXED, ya que ese modo deja de
     *  depender de que haya algún dedo sosteniendo el gesto. */
    fun cancel() {
        cancelContinuous()
        visibility = GONE
        activeSector = null
        spec = null
        navMode = NavMode.PENDING
        fixedModeDownSector = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val currentSpec = spec ?: return false

        // NavMode.FIXED es un modo aparte: el gesto
        // original YA terminó (el dedo que abrió el anillo se levantó antes
        // de 1s); de aquí en más esta vista recibe touches NUEVOS e
        // independientes directamente (ya es VISIBLE y MATCH_PARENT sobre
        // toda la pantalla), uno por cada tap — nunca reenviados desde
        // CustomKeyboardView, que ya no tiene ningún dedo que reenviar.
        if (navMode == NavMode.FIXED) {
            handleFixedModeTouch(event, currentSpec)
            return true
        }

        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                val sector = resolveSector(event.x, event.y, currentSpec)
                if (sector != activeSector) {
                    activeSector = sector
                    // En NavMode.PENDING, moverse de sector NO arranca nada
                    // (el criterio DRAG-vs-FIXED es puramente temporal):
                    // solo se actualiza activeSector para
                    // que scheduleNavModeDecision() sepa, cuando se cumpla el
                    // segundo, dónde está el dedo en ese instante. En
                    // NavMode.DRAG (ya pasó el segundo), cambiar de sector sí
                    // debe mover el modo continuo al nuevo sector.
                    if (isNavRing && navMode == NavMode.DRAG) handleDragSectorChanged(sector, currentSpec)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handleRelease(currentSpec)
            }
        }
        return true
    }

    /** Maneja cada tap independiente mientras el anillo de nav está en
     *  NavMode.FIXED. Un ACTION_DOWN fuera del área (resolveSector devuelve
     *  null) cierra el anillo sin acción. Un ACTION_DOWN dentro del área
     *  arma el mismo hold de 350ms que abre los otros anillos — pero en
     *  este modo sostenerlo no activa nada especial: el resultado se
     *  resuelve siempre en el ACTION_UP, sea cual sea el tiempo sostenido,
     *  usando los labels normales (modo fijo = un solo paso). */
    private fun handleFixedModeTouch(event: MotionEvent, spec: KeyboardLayouts.RingSpec) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val sector = resolveSector(event.x, event.y, spec)
                if (sector == null) {
                    // Tocar fuera: cierra el anillo fijo sin ninguna acción.
                    closeFixedMode()
                    return
                }
                fixedModeDownSector = sector
                activeSector = sector
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                // Un drag dentro del modo fijo simplemente resuelve el
                // sector bajo el dedo para la pintura activa, sin disparar
                // nada — la acción real solo ocurre en ACTION_UP.
                val sector = resolveSector(event.x, event.y, spec)
                if (sector != activeSector) {
                    activeSector = sector
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val downSector = fixedModeDownSector
                fixedModeDownSector = null
                val upSector = resolveSector(event.x, event.y, spec)
                activeSector = null
                invalidate()
                // Solo cuenta si el dedo se levanta sobre el MISMO sector
                // donde bajó (comportamiento de tap normal); si se arrastró
                // a otro sector o fuera, no pasa nada — ni siquiera cierra
                // el anillo, para no sorprender con un cierre accidental en
                // medio de un intento fallido de tap.
                if (downSector != null && downSector == upSector) {
                    labelFor(downSector, spec)?.let { onNavStep?.invoke(it) }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                fixedModeDownSector = null
                activeSector = null
                invalidate()
            }
        }
    }

    /** Cierra el anillo fijo sin disparar ninguna acción (tocar fuera). */
    private fun closeFixedMode() {
        navMode = NavMode.PENDING
        visibility = GONE
        activeSector = null
        fixedModeDownSector = null
        spec = null
    }

    /** Rectángulo del "centro": el contorno exacto del botón origen (mismo
     *  ancho que la tecla real, alto = CENTER_HEIGHT_DP), con su borde
     *  inferior pegado a originY y centrado horizontalmente en originX. */
    private fun centerRect(): RectF {
        val halfW = originWidth / 2f
        val h = dp(CENTER_HEIGHT_DP)
        return RectF(originX - halfW, originY - h, originX + halfW, originY)
    }

    private fun resolveSector(x: Float, y: Float, spec: KeyboardLayouts.RingSpec): Sector? {
        if (centerRect().contains(x, y)) return Sector.Center

        val dx = x - originX
        val dy = originY - y // invertido: y de pantalla crece hacia abajo
        val radius = hypot(dx.toDouble(), dy.toDouble()).toFloat()

        // Ángulo en convención matemática (0°=derecha, 90°=arriba), igual que
        // ringSectorPath() en el mockup, para que los índices de sector
        // coincidan exactamente con el orden de KeyboardLayouts.RingSpec.
        val angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).let {
            if (it < 0) it + 360 else it
        }

        // Fuera del arco propio de este spec (puede ser un cuarto de círculo,
        // no siempre el semicírculo completo) cuenta como centro: soltar ahí
        // no debe interpretarse como un sector válido de ninguna corona.
        val hi = maxOf(spec.startAngle, spec.endAngle)
        val lo = minOf(spec.startAngle, spec.endAngle)
        if (angleDeg < lo || angleDeg > hi) return Sector.Center

        val rInner = rInner()

        // Arco propio repartido en N sectores iguales y contiguos.
        val sweep = hi - lo
        fun sectorIndex(count: Int): Int {
            val step = sweep / count
            val fromStart = hi - angleDeg
            val idx = (fromStart / step).toInt().coerceIn(0, count - 1)
            return idx
        }

        // Sin corona externa (hoy solo RING_NAV, ver effectiveMaxRadius):
        // pasar rInner ya no selecciona nada — es sencillamente "fuera del
        // anillo" (null), que en NavMode.FIXED cierra el anillo al soltar
        // ahí, y en cualquier otro contexto no hace nada.
        if (spec.outer.isEmpty()) {
            return if (radius <= rInner) Sector.Inner(sectorIndex(spec.inner.size)) else null
        }

        // radius > rOuter no cae a "centro": si lo hiciera, arrastrar más
        // allá del radio visual del anillo externo dejaría el gesto
        // "pegado" al último sector resuelto antes de salir (resolveSector
        // devolvería Sector.Center, y un ACTION_MOVE posterior con el dedo
        // aún más afuera volvería a dar Center, así que activeSector nunca
        // volvería a cambiar). El radio real del dedo deja de importar una
        // vez pasado rInner: dentro del arco angular válido, cualquier
        // distancia por encima de rInner selecciona el sector exterior
        // correspondiente a ese ÁNGULO, como si el anillo fuera un círculo
        // más grande que la pantalla aunque visualmente solo se dibuje
        // hasta rOuter.
        return if (radius <= rInner) {
            Sector.Inner(sectorIndex(spec.inner.size))
        } else {
            Sector.Outer(sectorIndex(spec.outer.size))
        }
    }

    private fun labelFor(sector: Sector, spec: KeyboardLayouts.RingSpec): String? = when (sector) {
        Sector.Center -> spec.center
        is Sector.Inner -> spec.inner.getOrNull(sector.index)
        is Sector.Outer -> spec.outer.getOrNull(sector.index)
    }

    /** Variante de labelFor() para NavMode.DRAG: un Sector.Inner usa su
     *  label CONTINUO (innerContinuousLabels, p.ej. "↑↑" en vez de "↑") — el
     *  rol que cumplía el viejo anillo externo. Fuera de RING_NAV (donde
     *  innerContinuousLabels es null) esto nunca se invoca. */
    private fun continuousLabelFor(sector: Sector.Inner, spec: KeyboardLayouts.RingSpec): String? =
        spec.innerContinuousLabels?.getOrNull(sector.index)

    // ---------- release (anillos de brackets/operadores, y centro/anillo-
    // interno del anillo de nav) ----------

    private fun handleRelease(spec: KeyboardLayouts.RingSpec) {
        if (isNavRing) {
            handleNavRelease(spec)
            return
        }

        val sector = activeSector

        cancelContinuous()
        visibility = GONE
        activeSector = null
        this.spec = null

        val label = sector?.let { labelFor(it, spec) } ?: return
        onRelease?.invoke(label)
    }

    /** Release específico de RING_NAV: el NavMode decide qué pasa.
     *  - DRAG: el modo continuo ya se viene disparando desde ACTION_MOVE/el
     *    propio timer; soltar solo lo detiene, no dispara nada adicional
     *    (igual que el viejo anillo externo al soltar).
     *  - PENDING (nunca llegó a pasar el segundo): pasa a
     *    NavMode.FIXED en vez de cerrarse. El sector donde se soltó hace un
     *    solo paso (label normal) y el anillo queda dibujado, escuchando
     *    touches nuevos (ver onTouchEvent/handleFixedModeTouch). */
    private fun handleNavRelease(spec: KeyboardLayouts.RingSpec) {
        val sector = activeSector
        val wasDrag = navMode == NavMode.DRAG
        cancelContinuous()

        if (wasDrag) {
            visibility = GONE
            activeSector = null
            this.spec = null
            navMode = NavMode.PENDING
            return
        }

        // PENDING→FIXED: el anillo permanece dibujado (visibility sigue
        // VISIBLE, spec sigue vivo) en vez de cerrarse. El sector donde cayó
        // ESTE release (el del hold original) también cuenta como un tap
        // normal, igual que cualquiera de los taps nuevos que vengan después.
        navMode = NavMode.FIXED
        activeSector = null
        invalidate()
        if (sector != null) {
            labelFor(sector, spec)?.let { onNavStep?.invoke(it) }
        }
    }

    // ---------- matiz temporal exclusivo del anillo de navegación ----------

    /** Arranca el timer de 1s que decide PENDING→DRAG. Es único y global al
     *  abrir el gesto: si se cumple sin que se haya soltado antes, mira
     *  dónde está el dedo EN ESE INSTANTE (puede haberse movido del centro
     *  a un sector antes del segundo, el criterio es puramente temporal)
     *  para decidir qué continuo arrancar. */
    private fun scheduleNavModeDecision() {
        val runnable = Runnable {
            val currentSpec = spec ?: return@Runnable
            navMode = NavMode.DRAG
            when (val sector = activeSector) {
                Sector.Center -> startContinuousCenter()
                is Sector.Inner -> startContinuousInner(sector, currentSpec)
                else -> { /* fuera del área al cumplirse el segundo: no arranca nada */ }
            }
        }
        continuousRunnable = runnable
        holdHandler.postDelayed(runnable, CENTER_HOLD_THRESHOLD_MS)
    }

    /** En NavMode.DRAG, el dedo cambió de sector: cancela el tick continuo
     *  anterior y arranca el que corresponda al nuevo sector (centro =
     *  step-down continuo; un sector direccional = continuo en esa
     *  dirección; fuera del área = ninguno, hasta que vuelva a entrar). */
    private fun handleDragSectorChanged(sector: Sector?, currentSpec: KeyboardLayouts.RingSpec) {
        cancelContinuous()
        when (sector) {
            Sector.Center -> startContinuousCenter()
            is Sector.Inner -> startContinuousInner(sector, currentSpec)
            else -> { /* fuera del área: sin continuo hasta volver a entrar */ }
        }
    }

    private fun startContinuousCenter() {
        onNavContinuousTick?.invoke("↓")
        val tick = object : Runnable {
            override fun run() {
                if (navMode != NavMode.DRAG || activeSector != Sector.Center) return
                onNavContinuousTick?.invoke("↓")
                holdHandler.postDelayed(this, CONTINUOUS_TICK_MS)
            }
        }
        continuousRunnable = tick
        holdHandler.postDelayed(tick, CONTINUOUS_TICK_MS)
    }

    /** Modo DRAG sobre uno de los 3 sectores direccionales: cumple el rol
     *  que antes tenía el anillo externo (paso continuo mientras el dedo se
     *  mantenga en ese MISMO sector). Si el dedo se mueve a otro sector,
     *  handleDragSectorChanged() cancela este tick y arranca el que
     *  corresponda al nuevo sector. Usa el label CONTINUO
     *  (innerContinuousLabels), no el normal. */
    private fun startContinuousInner(sector: Sector.Inner, currentSpec: KeyboardLayouts.RingSpec) {
        val label = continuousLabelFor(sector, currentSpec) ?: return
        val tick = object : Runnable {
            override fun run() {
                if (navMode != NavMode.DRAG || activeSector != sector) return
                onNavContinuousTick?.invoke(label)
                holdHandler.postDelayed(this, CONTINUOUS_TICK_MS)
            }
        }
        continuousRunnable = tick
        // Primer tick inmediato: "mientras el dedo se mantenga ahí" empieza
        // a moverse desde que llega, no después de esperar un ciclo.
        holdHandler.post(tick)
    }

    private fun cancelContinuous() {
        continuousRunnable?.let { holdHandler.removeCallbacks(it) }
        continuousRunnable = null
    }

    // ---------- dibujo ----------

    private val paintOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF343944.toInt() }
    private val paintInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2F3542.toInt() }
    // BUG encontrado (visual, reportado por el usuario): el centro se
    // dibujaba SIEMPRE en azul (ex paintCenter) y el resalte de selección
    // (ex paintActive) era un gris casi idéntico al color base de los
    // sectores — exactamente al revés de lo esperado: el azul debería ser
    // la señal de "esto está seleccionado", no un color permanente del
    // centro. El trazo propio (paintCenterStroke) sigue existiendo para
    // que el centro se note como el "origen" del anillo incluso sin
    // selección, pero ya no en azul: ahora usa el mismo paintInner que el
    // resto cuando no está activo (ver drawRing más abajo), y paintActive
    // (ahora el azul) es el único resalte de selección, igual para centro
    // y sectores.
    private val paintCenterStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8AB4FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val paintActive = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5B8CFF.toInt() }
    private val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF15171C.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        // 22.5f para igualar CustomKeyboardView.KEY_TEXT_SIZE_SP (mismo
        // tamaño aparente que cualquier tecla normal del teclado).
        textSize = spText(22.5f)
    }

    /** Radio del semicírculo/cuarto-de-círculo-centro cuando se dibuja
     *  relleno (dedo quieto en el centro, hold activo): el mayor de los dos
     *  lados del rectángulo-centro (ancho real del botón vs.
     *  CENTER_HEIGHT_DP). El HIT-TEST del centro sigue siendo centerRect()
     *  sin cambios: esto es solo dibujo. */
    private fun centerHoldRadius(): Float = maxOf(originWidth / 2f, dp(CENTER_HEIGHT_DP))

    /** Recorte circular del centro en hold: debe respetar el MISMO arco que
     *  el resto del anillo (180° para operators/nav, 90° para brackets/
     *  paste) — un círculo de 360° completo se saldría visualmente del área
     *  del propio anillo. Mismo truco de arcTo que path(), pero des-centro
     *  hasta radio 0 en vez de entre dos radios. */
    private fun centerHoldPath(hi: Double, lo: Double): Path {
        val r = centerHoldRadius()
        val rect = RectF(originX - r, originY - r, originX + r, originY + r)
        val sweep = hi - lo
        return Path().apply {
            moveTo(originX, originY)
            arcTo(rect, (-hi).toFloat(), sweep.toFloat())
            close()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val spec = spec ?: return
        val rCenter = rCenter()
        val rInner = rInner()
        val rOuter = rOuter()
        val hi = maxOf(spec.startAngle, spec.endAngle)
        val lo = minOf(spec.startAngle, spec.endAngle)

        drawRing(canvas, spec.outer.size, rInner, rOuter, paintOuter, isOuter = true, hi, lo)
        drawRing(canvas, spec.inner.size, rCenter, rInner, paintInner, isOuter = false, hi, lo)

        // Centro: siempre el semicírculo/cuarto-de-círculo mientras el
        // anillo esté abierto, sin importar qué sector esté activo — el
        // rectángulo no se dibuja durante el gesto. Resaltado (paintActive,
        // azul) solo cuando el dedo está efectivamente sobre el centro;
        // color base (paintInner, igual que el resto de los sectores) en
        // cualquier otro caso.
        val holdPath = centerHoldPath(hi, lo)
        val centerPaint = if (activeSector == Sector.Center) paintActive else paintInner
        canvas.drawPath(holdPath, centerPaint)
        canvas.drawPath(holdPath, paintCenterStroke)

        // Posición del glifo del centro: el centro es un sector circular
        // relleno (centerHoldPath(), radio centerHoldRadius()), así que rx=0
        // (prácticamente sobre el origen mismo) dejaría el glifo pegado a
        // una esquina del área real en vez de en medio de ella. 2/3 del
        // radio es una aproximación simple y suficiente al centroide
        // visual de un sector circular (el centroide real de un sector de
        // ángulo θ cae a (4r·sin(θ/2))/(3θ) del origen; para 90°/180° eso
        // ronda 0.6–0.42r, pero 2/3r se ve mejor centrado dentro del área
        // rellena a simple vista, sin el sesgo hacia el borde curvo que da
        // la fórmula exacta).
        val centerGlyphRadius = centerHoldRadius() * (2f / 3f)
        drawLabel(canvas, spec.center, centerGlyphRadius, centerGlyphRadius, hi, lo)
        // Labels de los sectores direccionales: en NavMode.DRAG muestran el
        // label CONTINUO (↑↑, no ↑) — el sector se está comportando como el
        // viejo anillo externo; en cualquier otro momento (PENDING o FIXED,
        // o cualquier otro ring que no sea nav) muestran el label normal de
        // un solo paso.
        spec.inner.forEachIndexed { i, label ->
            val shown = if (isNavRing && navMode == NavMode.DRAG) {
                spec.innerContinuousLabels?.getOrNull(i) ?: label
            } else label
            drawSectorLabel(i, spec.inner.size, (rCenter + rInner) / 2f, shown, canvas, hi, lo)
        }
        spec.outer.forEachIndexed { i, label -> drawSectorLabel(i, spec.outer.size, (rInner + rOuter) / 2f, label, canvas, hi, lo) }
    }

    private fun drawRing(
        canvas: Canvas,
        count: Int,
        rFrom: Float,
        rTo: Float,
        basePaint: Paint,
        isOuter: Boolean,
        hi: Double,
        lo: Double
    ) {
        if (count <= 0) return
        val sweep = hi - lo
        val step = sweep / count
        for (i in 0 until count) {
            val start = hi - i * step
            val end = hi - (i + 1) * step
            val sector: Sector = if (isOuter) Sector.Outer(i) else Sector.Inner(i)
            val paint = if (activeSector == sector) paintActive else basePaint
            drawSector(canvas, path(rFrom, rTo, start, end), paint)
        }
    }

    private fun drawSector(canvas: Canvas, path: Path, paint: Paint) {
        canvas.drawPath(path, paint)
        canvas.drawPath(path, paintStroke)
    }

    /** Sector real de corona circular (arco exterior + radial + arco
     *  interior + radial), con arcTo verdadero en vez de una aproximación
     *  por cuerda recta: a 45°/60° por sector (3 u 4 sectores por medio
     *  círculo) la cuerda se notaría como un borde recto en vez de curvo,
     *  dando un aspecto "no circular". Convención de
     *  ángulos: 0°=derecha, 90°=arriba (matemática), pero Canvas.arcTo usa
     *  0°=derecha, ángulos CRECIENDO EN SENTIDO HORARIO con Y hacia abajo —
     *  como originY-r*sin(deg) ya invierte el eje Y al convertir a
     *  coordenadas de pantalla, el ángulo de pantalla equivalente es
     *  simplemente -deg (barrido horario = -barrido matemático). */
    private fun path(rFrom: Float, rTo: Float, startDeg: Double, endDeg: Double): Path {
        val outerRect = RectF(originX - rTo, originY - rTo, originX + rTo, originY + rTo)
        val innerRect = RectF(originX - rFrom, originY - rFrom, originX + rFrom, originY + rFrom)
        // startDeg > endDeg siempre en este archivo (el semicírculo se
        // recorre 180°→0°, pasando por 90°=arriba). En ángulos de pantalla
        // (negando, ver comentario de la función) eso es de -startDeg a
        // -endDeg, con -startDeg < -endDeg: un sweep positivo (horario) de
        // (startDeg - endDeg) grados recorre ese tramo pasando por arriba,
        // que es el arco exterior deseado. El arco interior se recorre en
        // sentido contrario (-sweep) partiendo de -endDeg, para volver sobre
        // la misma franja angular y cerrar el sector con las dos radiales.
        val sweep = (startDeg - endDeg)
        return Path().apply {
            arcTo(outerRect, (-startDeg).toFloat(), sweep.toFloat(), true)
            arcTo(innerRect, (-endDeg).toFloat(), (-sweep).toFloat())
            close()
        }
    }

    private fun drawSectorLabel(index: Int, count: Int, radius: Float, label: String, canvas: Canvas, hi: Double, lo: Double) {
        val step = (hi - lo) / count
        val mid = hi - (index + 0.5) * step
        drawLabel(canvas, label, radius, radius, mid, mid)
    }

    private fun drawLabel(canvas: Canvas, label: String, rx: Float, ry: Float, startDeg: Double, endDeg: Double) {
        val mid = (startDeg + endDeg) / 2.0
        val rad = Math.toRadians(mid)
        val x = originX + rx * Math.cos(rad).toFloat()
        val y = originY - ry * Math.sin(rad).toFloat()
        val fm = textPaint.fontMetrics
        canvas.drawText(label, x, y - (fm.ascent + fm.descent) / 2f, textPaint)
    }
}
