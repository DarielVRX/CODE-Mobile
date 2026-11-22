package com.codemobile.editor

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Barra de sugerencias de autocompletado. Fila dentro de bottomControlsColumn
 * en portrait (ver activity_editor.xml); columna fija dentro de
 * bottomControlsRow en landscape (ver ControlsController.
 * applyBottomControlsOrientation). En ambos casos puede colapsar a GONE de
 * verdad cuando no hay sugerencias (ver show()/hide()).
 *
 * BUG histórico (altura variable, causaba reflow en bucle; YA NO APLICA así
 * — ver mecanismo actual más abajo): en una versión anterior, sin
 * sugerencias la barra colapsaba a 0dp y con sugerencias pasaba a su alto
 * real (CHIP_HEIGHT_DP + padding). Ese cambio de alto movía el margen
 * inferior de contentColumn (ver EditorActivity.syncBottomControlsMargin),
 * así que mover un dedo sosteniéndolo sobre una letra con sugerencia
 * encogía el editor en el momento mismo en que aparecía la barra; eso corría
 * el punto bajo el dedo, la sugerencia para ese nuevo punto podía ser
 * distinta o no existir, la barra desaparecía, el editor volvía a crecer, el
 * punto bajo el dedo volvía a cambiar... un bucle que terminaba
 * desplazando la pantalla sin que el usuario lo pidiera. La corrección de
 * ENTONCES fue que esta vista se quedara siempre VISIBLE con su alto
 * reservado (vacía o no) — eso a su vez causaba una franja gris vacía
 * visible todo el tiempo, incluso sin ninguna pestaña abierta.
 *
 * MECANISMO ACTUAL (reemplaza al de arriba, solo aplica en portrait): la
 * reserva de espacio para evitar el reflow ya no la hace esta vista
 * quedándose VISIBLE vacía, sino CodeEditText.paddingBottom, fijo y
 * permanente (expectedHeightPx(), ver su doc), independiente de la
 * visibility real de esta barra. Así el editor siempre tiene esa franja
 * descontada de su scroll/área táctil (ver CodeEditText.maxScrollY/
 * onTouchEvent), exista o no la barra en ese momento, y esta vista queda
 * libre para reflejar su contenido real (GONE sin chips) sin reintroducir
 * el bug del reflow. En landscape no hace falta: landscapeSuggestionsSlot
 * (ver ControlsController.applyBottomControlsOrientation) ya reserva un
 * ancho fijo de columna independiente del contenido de esta vista, así que
 * ocultarla ahí tampoco mueve nada.
 *
 * BUG encontrado y corregido (chips invisibles en landscape): la versión
 * anterior extendía HorizontalScrollView directamente y lograba el modo
 * vertical rotando toda la vista 270° (rotation) con los specs de
 * onMeasure intercambiados a mano — mecanismo frágil (dependía de pivot
 * explícito, de que Android tradujera los touch events correctamente bajo
 * rotation, y de un segundo measure/layout forzado que no bastaba) que en
 * la práctica dejaba el contenedor visible pero sin ningún chip dibujado
 * dentro en landscape. Se reemplaza por el mismo patrón que ya funciona en
 * ControlBarView.setVertical(): dos contenedores reales (uno horizontal
 * para portrait, uno vertical para landscape), con measure/layout NATIVOS
 * de Android en ambos, sin rotation ni specs intercambiados — se alterna
 * cuál de los dos está agregado/visible, nunca ambos a la vez.
 *
 * No maneja gestos propios (a diferencia de RingGestureView/AccentPopupView):
 * cada sugerencia es un simple chip de texto con tap directo, porque no hace
 * falta la mecánica de hold-drag-release para esto.
 */
class SuggestionsBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    companion object {
        private const val BAR_BG = 0xFF1C2027.toInt()
        private const val CHIP_BG = 0xFF262A33.toInt()
        private const val CHIP_TEXT = 0xFFE8E9EC.toInt()
        private const val CHIP_HEIGHT_DP = 36
        private const val CHIP_PADDING_H_DP = 14
        private const val CHIP_GAP_DP = 6
        private const val BAR_PADDING_DP = 6
    }

    /** Fila horizontal (portrait): chips uno al lado del otro, scroll
     *  horizontal si exceden el ancho. */
    private val horizontalScroll: HorizontalScrollView
    private val rowHorizontal: LinearLayout

    /** Columna vertical (landscape): mismos chips apilados, scroll
     *  vertical si exceden el alto disponible de la columna angosta. */
    private val verticalScroll: ScrollView
    private val rowVertical: LinearLayout

    /** Último set de sugerencias mostrado, para poder reconstruir
     *  rowVertical/rowHorizontal al alternar setVertical() sin depender de
     *  que show() se vuelva a llamar desde afuera. */
    private var currentSuggestions: List<String> = emptyList()

    /** Invocado al tocar una sugerencia; quien escuche decide qué reemplazar. */
    var onSuggestionPicked: ((String) -> Unit)? = null

    /** Snapshot de lo que se está mostrando ahora mismo (Paso 7: el gesto de
     *  drag-abajo del qwerty lo lee ANTES de pisar la barra con el hint de
     *  la acción — ver CustomKeyboardView.actionDragKey() — para poder
     *  restaurarlo exacto con show() al terminar el gesto, sin que la
     *  barra necesite saber nada de ese mecanismo). */
    fun currentSuggestionsSnapshot(): List<String> = currentSuggestions

    /** true en landscape (columna angosta a la izquierda del teclado — ver
     *  EditorActivity.applyBottomControlsOrientation). Ver setVertical. */
    private var isVertical = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** "Espesor" fijo de la barra en su eje corto: alto en portrait, ancho
     *  en landscape (ver setVertical) — igual en ambos casos, porque todos
     *  los chips comparten CHIP_HEIGHT_DP sin importar cuántos haya.
     *  Expuesto para que quien decide si hay que despejarle espacio a la
     *  línea activa (ver EditorActivity/CodeEditText.ensureCursorVisibleAbove)
     *  o el ancho del slot angosto en landscape (ver
     *  ControlsController.applyBottomControlsOrientation) no dependa de
     *  height/getHeight(), que puede seguir en 0 si la vista nunca pasó por
     *  un layout pass estando VISIBLE en esta sesión de la Activity. */
    fun expectedHeightPx(): Int = dp(CHIP_HEIGHT_DP) + dp(BAR_PADDING_DP) * 2

    /** Alterna entre fila horizontal normal (portrait) y columna vertical
     *  angosta (landscape, a la izquierda del teclado): se intercambia cuál
     *  de los dos contenedores internos está agregado y VISIBLE, sin rotar
     *  nada — mismo patrón que ControlBarView.setVertical(). */
    fun setVertical(vertical: Boolean) {
        if (isVertical == vertical) return
        isVertical = vertical
        if (vertical) {
            horizontalScroll.visibility = View.GONE
            verticalScroll.visibility = View.VISIBLE
        } else {
            verticalScroll.visibility = View.GONE
            horizontalScroll.visibility = View.VISIBLE
        }
        rebuildChips()
    }

    init {
        setBackgroundColor(BAR_BG)
        // Alto/ancho fijo siempre, con o sin chips (ver doc de clase): sin
        // esto, sin sugerencias la fila mediría 0 (WRAP_CONTENT con 0
        // hijos) y la barra entera colapsaría igual que antes del fix.
        minimumHeight = expectedHeightPx()

        rowHorizontal = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        horizontalScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            val pad = dp(BAR_PADDING_DP)
            setPadding(pad, pad, pad, pad)
            addView(rowHorizontal, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        addView(horizontalScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        rowVertical = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        verticalScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            val pad = dp(BAR_PADDING_DP)
            setPadding(pad, pad, pad, pad)
            addView(rowVertical, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            visibility = View.GONE
        }
        addView(verticalScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Reemplaza las sugerencias mostradas. La reserva de espacio para
     *  evitar el reflow de aparecer/desaparecer (ver BUG histórico en doc
     *  de clase) ya NO la hace esta vista quedándose VISIBLE vacía — ahora
     *  la hace CodeEditText.paddingBottom, permanente e independiente de
     *  esta visibility (ver su doc). Esta vista simplemente refleja su
     *  contenido real: VISIBLE con chips, GONE sin ellos. */
    fun show(suggestions: List<String>) {
        currentSuggestions = suggestions
        visibility = if (suggestions.isEmpty()) GONE else VISIBLE
        rebuildChips()
    }

    /** Vacía los chips y colapsa la barra (GONE) — ya no hace falta
     *  quedarse VISIBLE vacía (ver doc de show()). */
    fun hide() {
        currentSuggestions = emptyList()
        visibility = GONE
        rebuildChips()
    }

    /** Reconstruye el contenido del contenedor ACTIVO (horizontal o
     *  vertical, según isVertical) a partir de currentSuggestions. El
     *  contenedor inactivo se deja vacío: nunca hace falta dibujar chips en
     *  los dos a la vez, y evita duplicar listeners de click sin uso. */
    private fun rebuildChips() {
        val activeRow = if (isVertical) rowVertical else rowHorizontal
        val inactiveRow = if (isVertical) rowHorizontal else rowVertical
        inactiveRow.removeAllViews()
        activeRow.removeAllViews()
        currentSuggestions.forEach { word ->
            activeRow.addView(chipFor(word))
        }
    }

    private fun chipFor(word: String): TextView = TextView(context).apply {
        text = word
        setTextColor(CHIP_TEXT)
        textSize = 14f
        gravity = Gravity.CENTER
        setBackgroundColor(CHIP_BG)
        if (isVertical) {
            // Se descartó rotar el texto (270°): el cálculo de stackedHeight
            // dejaba el rectángulo más chico de lo necesario y truncaba el
            // texto. En vez de seguir depurando esa medida, se optó por
            // ensanchar el slot angosto (ver expectedHeightPx() y su uso en
            // ControlsController.applyBottomControlsOrientation) para que el
            // texto sin rotar quepa cómodo, horizontal, apilado verticalmente
            // chip por chip.
            val padH = dp(CHIP_PADDING_H_DP)
            setPadding(padH, 0, padH, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(CHIP_HEIGHT_DP)
            ).apply { bottomMargin = dp(CHIP_GAP_DP) }
        } else {
            val padH = dp(CHIP_PADDING_H_DP)
            setPadding(padH, 0, padH, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(CHIP_HEIGHT_DP)
            ).apply { marginEnd = dp(CHIP_GAP_DP) }
        }
        setOnClickListener { onSuggestionPicked?.invoke(word) }
    }
}
