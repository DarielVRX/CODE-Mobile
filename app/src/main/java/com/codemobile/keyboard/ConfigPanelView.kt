package com.codemobile.keyboard

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.view.inputmethod.InputMethodManager
import android.widget.ScrollView
import android.widget.TextView

/**
 * Overlay del menú de configuración (Paso 7, §5): panel modal centrado en
 * pantalla, hermano de ringOverlay/accentPopup/suggestionsBar en el
 * FrameLayout raíz de activity_settings.xml (mismo patrón de inyección vía
 * propiedad pública que esos tres — ver doc de clase de CustomKeyboardView).
 * Solo existe mientras CustomKeyboardView.configMode es true; en el
 * teclado normal no se instancia ni se usa.
 *
 * A diferencia de AccentPopupView/RingGestureView (dibujo manual con
 * Canvas, pensados para un gesto rápido de hold+drag+release), este panel
 * usa vistas de Android normales (TextView/EditText/LinearLayout): el
 * contenido es variable en tamaño (0 a 5 variantes, listas de
 * DragAction...) y una de sus pantallas necesita foco real de EditText
 * para el IME del sistema — ninguna de las dos cosas es cómoda de dibujar
 * a mano con Canvas.
 *
 * Dos "pantallas" dentro del mismo panel (ver [PanelMode]):
 *  - LIST: lista de los valores actuales de la letra/anillo tocada
 *    ([listOf]: variantes y/o drag-action para una letra; tap-base +
 *    sectores para un anillo), más botones [+] donde corresponda (ver
 *    [LetterPanelSpec]/[RingPanelSpec]). Tocar un valor existente o un [+]
 *    pasa a EDIT para ese slot puntual.
 *  - EDIT: edición de UN slot. Un carácter (variante, sector de anillo,
 *    símbolo de pantalla secundaria) usa un EditText con foco real (IME
 *    del sistema — por eso SettingsActivity no tiene
 *    windowSoftInputMode=stateAlwaysHidden); un drag-action usa una lista
 *    de opciones de DragAction en vez de texto libre. Un botón "(x)"
 *    (reutilizando el hueco visual de la tecla espacio, ver pedido
 *    explícito del plan) aparece si el slot ya tenía un valor, para
 *    eliminarlo en vez de reemplazarlo.
 */
class ConfigPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** Paso 7 (botón "Aplicar", 3 estados — ver KeyboardSettingsSource):
     *  de dónde lee/escribe este panel. SettingsActivity lo reasigna al
     *  MISMO PendingKeyboardSettings que settingsKeyboard.settingsSource
     *  (buffer en memoria compartido, ver su doc) antes de la primera vez
     *  que se abre un panel — nunca al store real directo, a diferencia
     *  del teclado normal de EditorActivity (que ni siquiera instancia
     *  esta clase). Default (RealKeyboardSettingsSource) solo por
     *  seguridad ante un uso que olvide reasignarlo; en la práctica
     *  SettingsActivity siempre lo hace antes de que el usuario pueda
     *  tocar nada. */
    var settingsSource: KeyboardSettingsSource = RealKeyboardSettingsSource(context)

    /** Identifica qué letra/anillo abrió el panel y qué forma de contenido
     *  le corresponde — ver doc de clase. [ring] es null para una letra;
     *  [letter] es null para un anillo. Exactamente uno de los dos está
     *  presente. */
    private sealed class Owner {
        data class Letter(val ch: Char) : Owner()
        data class Ring(val ring: KeyboardSettingsStore.EditableRing, val hasTapBase: Boolean) : Owner()
        // Paso 7 (§5): pantalla de símbolos secundarios — edición directa
        // de un solo carácter, SIN pantalla LIST intermedia (ver doc de
        // showSymbolEdit). "Cancelar" en este caso no vuelve a ninguna
        // lista propia, cierra el panel entero (ver su uso en
        // showCharEditor).
        object Symbol : Owner()
    }

    /** Un slot editable dentro del panel abierto — variante en un índice,
     *  el drag-action de la letra, un sector de anillo, o el tap-base de
     *  un anillo. [kind] decide si EDIT muestra un EditText (carácter) o
     *  una lista de DragAction. */
    private sealed class Slot {
        data class AccentVariant(val index: Int) : Slot()
        object DragActionSlot : Slot()
        data class RingSector(val name: String) : Slot()
        object RingTapBase : Slot()
    }

    private var owner: Owner? = null
    private var onDismiss: (() -> Unit)? = null
    private var onChanged: (() -> Unit)? = null

    companion object {
        private const val PANEL_BG = 0xFF1C2027.toInt()
        private const val SCRIM_BG = 0x99000000.toInt()
        private const val ROW_BG = 0xFF262A33.toInt()
        private const val ROW_BG_PLUS = 0xFF20242C.toInt()
        private const val TEXT_COLOR = 0xFFE8E9EC.toInt()
        private const val TEXT_DIM = 0xFF8A8F99.toInt()
        private const val ACCENT = 0xFF5B8CFF.toInt()
        private const val DANGER = 0xFFEE8888.toInt()
        private const val PANEL_WIDTH_DP = 320
        private const val ROW_HEIGHT_DP = 48
        private const val ROW_GAP_DP = 8
        private const val PANEL_PADDING_DP = 16
        private const val MAX_LIST_HEIGHT_DP = 420
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private lateinit var scrim: View
    private lateinit var panel: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var bodyContainer: LinearLayout

    init {
        setClipChildren(false)
        buildChrome()
        visibility = GONE
    }

    /** Construye el fondo oscuro (scrim, cierra el panel al tocarlo) y la
     *  tarjeta del panel en sí (título + cuerpo dinámico) — llamado una
     *  sola vez en init; renderList()/renderEdit() solo repueblan
     *  [bodyContainer]. */
    private fun buildChrome() {
        scrim = View(context).apply {
            setBackgroundColor(SCRIM_BG)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setOnClickListener { dismiss() }
        }
        addView(scrim)

        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PANEL_BG)
            setPadding(dp(PANEL_PADDING_DP), dp(PANEL_PADDING_DP), dp(PANEL_PADDING_DP), dp(PANEL_PADDING_DP))
            layoutParams = LayoutParams(dp(PANEL_WIDTH_DP), LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            // El tap en el panel mismo no debe propagarse al scrim de atrás
            // (cerraría el panel al tocar su propio contenido) — un
            // listener vacío basta para consumir el evento sin que caiga
            // al padre.
            isClickable = true
        }
        addView(panel)

        titleText = TextView(context).apply {
            setTextColor(TEXT_COLOR)
            textSize = 16f
            setPadding(0, 0, 0, dp(PANEL_PADDING_DP))
        }
        panel.addView(titleText)

        bodyContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        // ScrollView: el panel de un anillo con tap-base tiene 6 filas más
        // el padding, puede exceder la altura disponible en landscape con
        // teclado del sistema abierto (para el EditText de edición) —
        // mejor que recortarse o desbordar la pantalla.
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(MAX_LIST_HEIGHT_DP))
            addView(bodyContainer)
        }
        panel.addView(scroll)
    }

    fun dismiss() {
        visibility = GONE
        owner = null
        bodyContainer.removeAllViews()
        onDismiss?.invoke()
    }

    // ---------- API pública: abrir el panel ----------

    /** Abre el panel LIST para la letra [ch] (minúscula) — variantes de
     *  acento + drag-action actuales, con sus [+] donde corresponda. */
    fun showLetterPanel(ch: Char, onDismiss: () -> Unit, onChanged: () -> Unit) {
        owner = Owner.Letter(ch)
        this.onDismiss = onDismiss
        this.onChanged = onChanged
        renderLetterList(ch)
        visibility = VISIBLE
    }

    /** Abre el panel LIST para el anillo [ring] — tap-base (solo si
     *  [hasTapBase]; PASTE/Enter no lo tiene, ver doc de
     *  KeyboardSettingsStore) + sus 5 sectores (center/inner0/inner1/
     *  outer0/outer1). */
    fun showRingPanel(ring: KeyboardSettingsStore.EditableRing, hasTapBase: Boolean, onDismiss: () -> Unit, onChanged: () -> Unit) {
        owner = Owner.Ring(ring, hasTapBase)
        this.onDismiss = onDismiss
        this.onChanged = onChanged
        renderRingList(ring, hasTapBase)
        visibility = VISIBLE
    }

    /** Abre directo la pantalla EDIT de un símbolo de SYMBOLS_MAIN
     *  ([main]=true) o SYMBOLS_SIDE ([main]=false) en el índice [index] —
     *  edición de un solo carácter, SIN variantes ni lista previa (ver
     *  doc de clase: "se edita directo, solo cabe un carácter sin
     *  variantes", pedido explícito). Sin botón "(x)": un símbolo de estas
     *  pantallas siempre tiene algún carácter (el de fábrica o un
     *  override), nunca queda "vacío". */
    fun showSymbolEdit(main: Boolean, index: Int, onDismiss: () -> Unit, onChanged: () -> Unit) {
        owner = Owner.Symbol
        this.onDismiss = onDismiss
        this.onChanged = onChanged
        val current = KeyboardLayouts.effectiveSymbol(settingsSource, main, index)
        showCharEditor(
            title = "Edit symbol",
            current = current,
            onSave = { newChar ->
                settingsSource.setSymbolOverride(main, index, newChar)
                onChanged()
                dismiss()
            },
            onDelete = null
        )
        visibility = VISIBLE
    }

    // ---------- LIST: letra ----------

    private fun renderLetterList(ch: Char) {
        titleText.text = "Key \"$ch\""
        bodyContainer.removeAllViews()

        val variants = KeyboardLayouts.effectiveAccentVariants(settingsSource, ch)
        val dragAction = KeyboardLayouts.effectiveDragAction(settingsSource, ch)

        bodyContainer.addView(sectionLabel("Variants"))
        variants.forEachIndexed { index, variant ->
            bodyContainer.addView(valueRow(variant.toString()) {
                openEdit(Slot.AccentVariant(index), currentChar = variant)
            })
        }
        if (variants.size < KeyboardSettingsStore.MAX_ACCENT_VARIANTS) {
            bodyContainer.addView(plusRow("Add variant") {
                openEdit(Slot.AccentVariant(variants.size), currentChar = null)
            })
        }

        bodyContainer.addView(sectionLabel("Shortcut (drag down)"))
        if (dragAction != null) {
            bodyContainer.addView(valueRow(dragAction.hint) {
                openDragActionEdit(ch, current = dragAction)
            })
        } else {
            bodyContainer.addView(plusRow("Add shortcut") {
                openDragActionEdit(ch, current = null)
            })
        }
    }

    // ---------- LIST: anillo ----------

    private fun ringDisplayName(ring: KeyboardSettingsStore.EditableRing): String = when (ring) {
        KeyboardSettingsStore.EditableRing.BRACKETS -> "Parentheses"
        KeyboardSettingsStore.EditableRing.OPERATORS -> "Operators"
        KeyboardSettingsStore.EditableRing.PASTE -> "Enter"
    }

    private fun renderRingList(ring: KeyboardSettingsStore.EditableRing, hasTapBase: Boolean) {
        titleText.text = "Ring: ${ringDisplayName(ring)}"
        bodyContainer.removeAllViews()

        val spec = KeyboardLayouts.effectiveRingSpec(settingsSource, ring)

        if (hasTapBase) {
            val tapDefault = when (ring) {
                KeyboardSettingsStore.EditableRing.BRACKETS -> "("
                KeyboardSettingsStore.EditableRing.OPERATORS -> "="
                KeyboardSettingsStore.EditableRing.PASTE -> "" // nunca llamado con hasTapBase=true
            }
            val tapValue = KeyboardLayouts.effectiveRingOriginTap(settingsSource, ring, tapDefault)
            bodyContainer.addView(sectionLabel("Key (normal tap)"))
            bodyContainer.addView(valueRow(tapValue) {
                openRingTapEdit(ring, current = tapValue)
            })
        }

        bodyContainer.addView(sectionLabel("Center"))
        bodyContainer.addView(valueRow(spec.center) {
            openRingSectorEdit(ring, "center", current = spec.center)
        })

        bodyContainer.addView(sectionLabel("Inner ring"))
        spec.inner.forEachIndexed { i, v ->
            bodyContainer.addView(valueRow(v) {
                openRingSectorEdit(ring, "inner$i", current = v)
            })
        }

        if (spec.outer.isNotEmpty()) {
            bodyContainer.addView(sectionLabel("Outer ring"))
            spec.outer.forEachIndexed { i, v ->
                bodyContainer.addView(valueRow(v) {
                    openRingSectorEdit(ring, "outer$i", current = v)
                })
            }
        }
    }

    // ---------- EDIT: carácter (variante o sector de anillo) ----------

    private fun openEdit(slot: Slot.AccentVariant, currentChar: Char?) {
        showCharEditor(
            title = if (currentChar != null) "Edit variant" else "New variant",
            current = currentChar,
            onSave = { newChar ->
                val letter = (owner as? Owner.Letter)?.ch ?: return@showCharEditor
                val variants = KeyboardLayouts.effectiveAccentVariants(settingsSource, letter).toMutableList()
                if (slot.index < variants.size) variants[slot.index] = newChar else variants.add(newChar)
                settingsSource.setAccentVariantsOverride(letter, variants)
                onChanged?.invoke()
                renderLetterList(letter)
            },
            onDelete = {
                val letter = (owner as? Owner.Letter)?.ch ?: return@showCharEditor
                val variants = KeyboardLayouts.effectiveAccentVariants(settingsSource, letter).toMutableList()
                if (slot.index in variants.indices) variants.removeAt(slot.index)
                settingsSource.setAccentVariantsOverride(letter, variants)
                onChanged?.invoke()
                renderLetterList(letter)
            }
        )
    }

    private fun openRingSectorEdit(ring: KeyboardSettingsStore.EditableRing, slotName: String, current: String) {
        showCharEditor(
            title = "Edit symbol",
            current = current.firstOrNull(),
            onSave = { newChar ->
                settingsSource.setRingSlotOverride(ring, slotName, newChar.toString())
                onChanged?.invoke()
                val hasTapBase = (owner as? Owner.Ring)?.hasTapBase ?: false
                renderRingList(ring, hasTapBase)
            },
            onDelete = null // un sector de anillo siempre tiene los 5 slots fijos, no se puede "vaciar" un hueco
        )
    }

    private fun openRingTapEdit(ring: KeyboardSettingsStore.EditableRing, current: String) {
        showCharEditor(
            title = "Edit key",
            current = current.firstOrNull(),
            onSave = { newChar ->
                settingsSource.setRingOriginTapOverride(ring, newChar.toString())
                onChanged?.invoke()
                renderRingList(ring, hasTapBase = true)
            },
            onDelete = null // el tap-base de "(" / "=" siempre tiene un valor, no un slot opcional
        )
    }

    /** Pantalla EDIT de un carácter: EditText con foco real (IME del
     *  sistema — ver doc de clase) prellenado con [current] si lo había.
     *  [onDelete] null oculta el botón "(x)" (slots que siempre tienen un
     *  valor fijo, nunca "vacío"); no null lo muestra, reusando el hueco
     *  visual de la tecla espacio (ver pedido explícito del plan — en este
     *  panel no hay teclado real debajo, así que "espacio" es solo la
     *  posición/forma del botón, no la tecla en sí). */
    private fun showCharEditor(title: String, current: Char?, onSave: (Char) -> Unit, onDelete: (() -> Unit)?) {
        titleText.text = title
        bodyContainer.removeAllViews()

        val input = EditText(context).apply {
            setText(current?.toString() ?: "")
            setTextColor(TEXT_COLOR)
            setHintTextColor(TEXT_DIM)
            hint = "A character"
            inputType = InputType.TYPE_CLASS_TEXT
            // Un solo carácter: cualquier tecla adicional del IME del
            // sistema simplemente sobreescribe lo ya escrito (ver
            // TextWatcher abajo) en vez de acumularse — más simple que
            // bloquear la entrada a nivel de filtro de longitud.
            setSingleLine(true)
        }
        bodyContainer.addView(input)
        input.requestFocus()
        input.selectAll()
        input.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }

        val buttonsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(PANEL_PADDING_DP)
            }
        }
        bodyContainer.addView(buttonsRow)

        if (onDelete != null) {
            buttonsRow.addView(actionButton("(x) Remove", DANGER) {
                onDelete()
            })
        }
        buttonsRow.addView(actionButton("Save", ACCENT) {
            val text = input.text?.toString().orEmpty()
            val newChar = text.lastOrNull()
            if (newChar != null) onSave(newChar)
        })
        buttonsRow.addView(actionButton("Cancel", TEXT_DIM) {
            // Vuelve a la lista sin aplicar nada — misma lista desde la
            // que se abrió este editor, releída fresca de owner. Symbol no
            // tiene lista propia (ver showSymbolEdit): cancelar ahí cierra
            // el panel entero.
            when (val o = owner) {
                is Owner.Letter -> renderLetterList(o.ch)
                is Owner.Ring -> renderRingList(o.ring, o.hasTapBase)
                is Owner.Symbol -> dismiss()
                null -> dismiss()
            }
        })
    }

    // ---------- EDIT: drag action (lista de opciones, no texto libre) ----------

    /** Pantalla EDIT del drag-action de una letra: lista de todas las
     *  KeyboardLayouts.DragAction disponibles (no texto libre, a
     *  diferencia de showCharEditor — ver doc de clase). Si [current] ya
     *  existía, el botón "(x)" permite quitarlo sin reemplazarlo por otro.
     *  Elegir una acción ya usada por OTRA letra pide confirmación
     *  explícita antes de aplicarla (duplicado permitido, pero avisado —
     *  pedido explícito del plan), sin bloquear el duplicado en sí. */
    private fun openDragActionEdit(letter: Char, current: KeyboardLayouts.DragAction?) {
        titleText.text = if (current != null) "Change shortcut" else "New shortcut"
        bodyContainer.removeAllViews()

        // SAVE_AS queda fuera de esta lista a propósito (ver doc de
        // KeyboardLayouts.DragAction.SAVE_AS/HOLD_SWITCHES_TO): solo se
        // alcanza sosteniendo el gesto de la letra que tenga SAVE, nunca
        // como asignación directa de una letra.
        KeyboardLayouts.DragAction.values().filter { it != KeyboardLayouts.DragAction.SAVE_AS }.forEach { action ->
            bodyContainer.addView(valueRow(action.hint) {
                confirmAndApplyDragAction(letter, action)
            })
        }

        if (current != null) {
            bodyContainer.addView(plusRow("(x) Remove shortcut", danger = true) {
                settingsSource.setDragActionOverride(letter, null)
                onChanged?.invoke()
                renderLetterList(letter)
            })
        }
    }

    /** Busca si [action] ya está asignada a otra letra (vía
     *  effectiveDragAction sobre las 26 letras — son pocas, barre todas
     *  sin indexar nada especial) y, de ser así, pide confirmación con un
     *  segundo panel efímero antes de aplicar — ver doc de
     *  openDragActionEdit. Sin conflicto, aplica directo. */
    private fun confirmAndApplyDragAction(letter: Char, action: KeyboardLayouts.DragAction) {
        val conflictLetter = ('a'..'z').firstOrNull { other ->
            other != letter && KeyboardLayouts.effectiveDragAction(settingsSource, other) == action
        }
        if (conflictLetter == null) {
            applyDragActionChoice(letter, action)
            return
        }
        titleText.text = "Confirm?"
        bodyContainer.removeAllViews()
        bodyContainer.addView(sectionLabel("\"${action.hint}\" is already on key \"$conflictLetter\". Assign it to \"$letter\" too?"))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(PANEL_PADDING_DP)
            }
        }
        bodyContainer.addView(row)
        row.addView(actionButton("Confirm", ACCENT) { applyDragActionChoice(letter, action) })
        row.addView(actionButton("Cancel", TEXT_DIM) { openDragActionEdit(letter, KeyboardLayouts.effectiveDragAction(settingsSource, letter)) })
    }

    private fun applyDragActionChoice(letter: Char, action: KeyboardLayouts.DragAction) {
        settingsSource.setDragActionOverride(letter, action)
        onChanged?.invoke()
        renderLetterList(letter)
    }

    // ---------- piezas de UI reusadas ----------

    private fun sectionLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(TEXT_DIM)
        textSize = 12f
        setPadding(0, dp(ROW_GAP_DP), 0, dp(4))
    }

    private fun valueRow(label: String, onTap: () -> Unit): View = TextView(context).apply {
        text = label
        setTextColor(TEXT_COLOR)
        textSize = 18f
        gravity = Gravity.CENTER
        setBackgroundColor(ROW_BG)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)).apply {
            topMargin = dp(4)
        }
        setOnClickListener { onTap() }
    }

    private fun plusRow(label: String, danger: Boolean = false, onTap: () -> Unit): View = TextView(context).apply {
        text = label
        setTextColor(if (danger) DANGER else ACCENT)
        textSize = 15f
        gravity = Gravity.CENTER
        setBackgroundColor(ROW_BG_PLUS)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)).apply {
            topMargin = dp(4)
        }
        setOnClickListener { onTap() }
    }

    private fun actionButton(label: String, color: Int, onTap: () -> Unit): View = TextView(context).apply {
        text = label
        setTextColor(color)
        textSize = 15f
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, dp(ROW_HEIGHT_DP), 1f).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        }
        setOnClickListener { onTap() }
    }
}
