package com.codemobile.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.codemobile.R
import com.codemobile.editor.CodeEditText
import com.codemobile.editor.CodeEditText.DragMode

/**
 * Barra de controles fija encima del teclado propio (CustomKeyboardView),
 * con las acciones que no tienen una tecla dedicada en el teclado:
 *
 * - pendingCloserCell: celda que muestra el cierre que falta según
 *   BracketAccumulator sobre el texto real del archivo activo (sin ningún
 *   autocierre: ver doc de clase de OpenFile/CodeEditText.insertPendingCloser).
 *   Tocarla inserta ese cierre y revela el siguiente que quede pendiente. Si
 *   hay más de uno pendiente, muestra un contador pequeño superpuesto.
 * - rotateButton: gira la pantalla forzando landscape/portrait.
 * - hideKeyboardButton: oculta CustomKeyboardView. Tocar el área de texto
 *   con el teclado oculto lo reabre — esa parte vive en
 *   CodeEditText/EditorActivity, no acá.
 * - dragModeButton (Paso 8): alterna target?.dragMode entre CURSOR y
 *   CONTENT (ver su doc en CodeEditText) — "I" = mover cursor/selección
 *   (modo por defecto), "✥" (resaltado con accent) = mover el contenido
 *   con scroll. dragMode vive POR ARCHIVO en CodeEditText, no en este
 *   botón: el setter de [target] relee y refleja el modo real del nuevo
 *   archivo activo cada vez que se cambia de pestaña.
 * - settingsButton (Paso 7, §5): abre el menú de configuración del teclado
 *   (SettingsActivity) — a la izquierda de rotateButton, pedido explícito.
 *
 * El botón de seleccionar (antes "selectToggle", con toggle/doble-tap/
 * hold-drag de navegación) pasó primero a un botón de header (selectAllButton,
 * un solo tap = select all) y, desde el Paso 7, a una acción más del
 * drag-abajo del teclado propio (tecla A — ver KeyboardLayouts.
 * DEFAULT_DRAG_ACTIONS / ControlsController.onDragAction) — ya no vive en
 * esta barra, ni como botón propio en ningún lado. activateSelectMode()/
 * deactivateSelectMode() siguen acá porque el hold+drag directo sobre el
 * editor (CodeEditText.onHoldSelectStarted/Ended) todavía necesita
 * encender/apagar selectModeActive del target, pero ya no hay ningún
 * botón visual propio que sincronizar en esta vista.
 */
class ControlBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private lateinit var controlBarRow: LinearLayout
    private lateinit var hideKeyboardButton: TextView
    private lateinit var dragModeButton: TextView
    private lateinit var pendingCloserCell: TextView
    private lateinit var settingsButton: TextView
    private lateinit var rotateButton: TextView

    /** true mientras está en orientación vertical (landscape, columna
     *  angosta a la derecha del teclado — ver EditorActivity.
     *  applyBottomControlsOrientation). Para no reconfigurar los
     *  LayoutParams dos veces si se llama con el mismo valor otra vez. */
    private var isVertical = false

    /** Reasignado cada vez que se cambia de pestaña (ver KeyboardRouter):
     *  dragMode vive por archivo en CodeEditText, no en este botón, así que
     *  cada vez que target cambia hay que releer su dragMode actual y
     *  reflejarlo en dragModeButton — sin esto, el botón seguiría mostrando
     *  el modo de la pestaña ANTERIOR hasta el próximo tap, desincronizado
     *  del archivo realmente activo. */
    var target: CodeEditText? = null
        set(value) {
            field = value
            updateDragModeButton()
        }
    var selectModeActive: Boolean = false
        private set
    var onRotateClick: (() -> Unit)? = null
    var onSettingsClick: (() -> Unit)? = null
    /** Se invoca al tocar el botón; [hidden] indica el estado al que debe pasar el teclado. */
    var onHideKeyboardClick: ((hidden: Boolean) -> Unit)? = null
    var keyboardHidden: Boolean = false
        private set

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_control_bar, this, true)
        bindViews()
        setupPendingCloserCell()
        setupSettingsButton()
        setupRotateButton()
        setupHideKeyboardButton()
        setupDragModeButton()
    }

    private fun setupRotateButton() {
        rotateButton.setOnClickListener { onRotateClick?.invoke() }
    }

    private fun setupSettingsButton() {
        settingsButton.setOnClickListener { onSettingsClick?.invoke() }
    }

    private fun setupHideKeyboardButton() {
        hideKeyboardButton.setOnClickListener {
            setKeyboardHidden(!keyboardHidden)
            onHideKeyboardClick?.invoke(keyboardHidden)
        }
    }

    /**
     * Sincroniza el label del botón ("⌄" ocultar / "^" mostrar) con el estado
     * real del teclado. EditorActivity debe llamar esto también cuando el
     * teclado se reabre por otra vía (p. ej. tocar el área de texto), para que
     * el botón no quede desincronizado.
     */
    fun setKeyboardHidden(hidden: Boolean) {
        keyboardHidden = hidden
        hideKeyboardButton.text = if (hidden) "^" else "⌄"
    }

    /** "Espesor" angosto de esta vista cuando setVertical(true): el ancho
     *  fijo que debe reservarle landscapeControlsSlot (ver
     *  ControlsController.applyBottomControlsOrientation), igual al alto
     *  que la fila usa en portrait (48dp) — no cambia con la orientación. */
    fun expectedWidthPxVertical(): Int = (48 * resources.displayMetrics.density).toInt()

    private fun bindViews() {
        controlBarRow = findViewById(R.id.controlBarRow)
        hideKeyboardButton = findViewById(R.id.hideKeyboardButton)
        dragModeButton = findViewById(R.id.dragModeButton)
        pendingCloserCell = findViewById(R.id.pendingCloserCell)
        settingsButton = findViewById(R.id.settingsButton)
        rotateButton = findViewById(R.id.rotateButton)
    }

    /** Paso 8: alterna target?.dragMode entre CURSOR y CONTENT (ver su doc
     *  en CodeEditText) cada vez que se toca el botón, y refleja el nuevo
     *  estado de inmediato. Si no hay target todavía (pestaña recién
     *  creada antes de que KeyboardRouter lo asigne), no hace nada — no
     *  hay ningún CodeEditText al que aplicarle el modo. */
    private fun setupDragModeButton() {
        dragModeButton.setOnClickListener {
            val current = target?.dragMode ?: return@setOnClickListener
            target?.dragMode = if (current == DragMode.CURSOR) DragMode.CONTENT else DragMode.CURSOR
            updateDragModeButton()
        }
    }

    /** Sincroniza el label/color de dragModeButton con target?.dragMode —
     *  llamado al togglear (setupDragModeButton) y cada vez que target se
     *  reasigna (ver su setter), para que el botón nunca muestre el modo
     *  de la pestaña anterior. "I" (text_dim, modo por defecto) = CURSOR;
     *  "✥" (accent, mismo color que ya usa pendingCloserCell para resaltar
     *  un estado activo) = CONTENT. Sin target (pestaña recién creada, ver
     *  doc de setupDragModeButton), se deja en el estado CURSOR por
     *  defecto: no hay ningún dragMode real que reflejar todavía. */
    private fun updateDragModeButton() {
        val mode = target?.dragMode ?: DragMode.CURSOR
        if (mode == DragMode.CONTENT) {
            dragModeButton.text = "✥"
            dragModeButton.setTextColor(resources.getColor(R.color.accent, context.theme))
        } else {
            dragModeButton.text = "I"
            dragModeButton.setTextColor(resources.getColor(R.color.text_dim, context.theme))
        }
    }

    /** Alterna controlBarRow entre fila horizontal (portrait, cada botón se
     *  reparte el ANCHO con weight=1 y alto fijo 48dp) y columna vertical
     *  (landscape, columna angosta: cada botón se reparte el ALTO
     *  disponible con weight=1 y ancho match_parent). Mismos 4 TextView de
     *  siempre, solo se reconfiguran sus LayoutParams — nada se reinfla ni
     *  se duplica. No hace nada si [vertical] ya es el estado actual.
     *
     *  controlBarRow mismo también cambia de alto: el XML le fija 48dp
     *  (correcto en portrait, una fila), pero en vertical esta vista entera
     *  ocupa la columna angosta completa — sin este ajuste, el weight=1f de
     *  los botones se repartía sobre esos mismos 48dp fijos en vez de la
     *  altura real del slot, dejando los controles comprimidos arriba. */
    fun setVertical(vertical: Boolean) {
        if (isVertical == vertical) return
        isVertical = vertical
        controlBarRow.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        val rowParams = controlBarRow.layoutParams
        if (vertical) {
            rowParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            rowParams.height = LinearLayout.LayoutParams.MATCH_PARENT
        } else {
            rowParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            rowParams.height = (48 * resources.displayMetrics.density).toInt()
        }
        controlBarRow.layoutParams = rowParams
        val buttons = listOf(hideKeyboardButton, dragModeButton, pendingCloserCell, settingsButton, rotateButton)
        buttons.forEachIndexed { index, button ->
            val params = LinearLayout.LayoutParams(
                if (vertical) LinearLayout.LayoutParams.MATCH_PARENT else 0,
                if (vertical) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
            // El margen de separación entre celdas era marginEnd (horizontal);
            // en vertical tiene que ser marginBottom entre filas en vez de a
            // la derecha — y el último botón (rotateButton) no lleva margen
            // en ninguno de los dos casos, igual que en el XML original.
            val gapPx = (2 * resources.displayMetrics.density).toInt()
            val hasGap = index < buttons.size - 1
            if (vertical) {
                params.bottomMargin = if (hasGap) gapPx else 0
                params.marginEnd = 0
            } else {
                params.marginEnd = if (hasGap) gapPx else 0
                params.bottomMargin = 0
            }
            button.layoutParams = params
        }
    }

    private fun setupPendingCloserCell() {
        pendingCloserCell.setOnClickListener {
            target?.insertPendingCloser()
        }
    }

    /** Apaga el modo selección si estaba activo (estado real,
     *  target.selectModeActive). Se llama tras copiar o pegar (centro de
     *  RING_PASTE del teclado) o al terminar un hold+drag sobre el editor
     *  (CodeEditText.onHoldSelectEnded), para que la selección no quede
     *  "pegada" después de la acción que la usó. Ya no sincroniza ningún
     *  botón visual propio — ese botón se movió al header (selectAllButton
     *  en EditorActivity) y no refleja selectModeActive, solo dispara
     *  select-all de un tap. No hace nada si ya estaba apagado. */
    fun deactivateSelectMode() {
        if (!selectModeActive) return
        selectModeActive = false
        target?.selectModeActive = false
    }

    /** Prende el modo selección si estaba apagado (estado real) —
     *  contraparte de deactivateSelectMode(). La usa el hold+drag directo
     *  sobre el editor (CodeEditText.onHoldSelectStarted/EditorActivity)
     *  para mantener selectModeActive de este ControlBarView en sync con
     *  lo que ya pasó en el target, sin ningún botón visual propio que
     *  actualizar (ver nota de clase arriba). */
    fun activateSelectMode() {
        if (selectModeActive) return
        selectModeActive = true
        // BUG encontrado y evitado acá (ancla de selección destruida): si
        // target YA tiene selectModeActive=true (como pasa al venir desde
        // CodeEditText.onHoldSelectStarted, que lo activó él mismo ANTES
        // de notificar, con selectionAnchor ya fijo al inicio de la
        // palabra — ver startHoldSelect), volver a asignar
        // target.selectModeActive = true dispararía otra vez el setter de
        // CodeEditText (ver su doc: pisa selectionAnchor con
        // selectionStart), destruyendo el ancla fija que se acababa de
        // establecer. Solo se asigna si todavía no estaba activo en el
        // target.
        if (target?.selectModeActive != true) {
            target?.selectModeActive = true
        }
    }

    /** Llamado desde CodeEditText cada vez que cambia el tope o el tamaño de la pila. */
    fun updatePendingCloser(topCloser: Char?, count: Int) {
        if (topCloser == null) {
            pendingCloserCell.visibility = android.view.View.INVISIBLE
            pendingCloserCell.text = ""
        } else {
            pendingCloserCell.visibility = android.view.View.VISIBLE
            pendingCloserCell.text = if (count > 1) "$topCloser·$count" else topCloser.toString()
        }
    }
}
