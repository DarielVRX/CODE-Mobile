package com.codemobile.ui

import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.codemobile.R
import com.codemobile.editor.CodeEditText
import com.codemobile.editor.GotoResolver
import com.codemobile.editor.SuggestionsBarView
import com.codemobile.keyboard.CustomKeyboardView
import com.codemobile.keyboard.KeyboardLayouts
import com.codemobile.keyboard.PlainEditTextTarget

/**
 * Botón de header (run), el buscador del editor de código, y el layout
 * portrait/landscape de bottomControlsColumn/bottomControlsRow.
 *
 * Paso 7: save/undo/redo/selectAll/search ya no son botones — se movieron
 * al drag-abajo del teclado propio (ver KeyboardLayouts.DEFAULT_DRAG_ACTIONS
 * y onDragAction en setup()); este controller sigue resolviendo esas
 * acciones (misma lógica de siempre), solo que ahora también las dispara el
 * teclado, no un TextView con OnClickListener.
 *
 * Paso 13: el panel colapsable ⋮ (headerActionsButton/Panel/Row) se
 * elimina — Run vuelve a ser un botón fijo en tabBarRow (ver
 * activity_editor.xml), y folderButton (también fijo ahí, extremo
 * izquierdo) pasa a abrir/cerrar el panel lateral del explorador en vez de
 * cambiar de "modo pantalla" completo (ver EditorActivity).
 *
 * [tabs] se recibe por referencia directa para activeCodeEditText();
 * [runController] para el botón Run; [setRequestedOrientation] es la única
 * capacidad que exige Activity real, inyectada como lambda en vez de pasar
 * la Activity completa.
 */
class ControlsController(
    private val tabs: TabsController,
    private val runController: RunController,
    private val controlBar: ControlBarView,
    private val customKeyboard: CustomKeyboardView,
    private val suggestionsBar: SuggestionsBarView,
    private val keyboardRouter: KeyboardRouter,
    private val runButton: TextView,
    private val searchBarPanel: LinearLayout,
    private val searchInputBox: EditText,
    private val searchMatchCountText: TextView,
    private val searchPrevButton: TextView,
    private val searchNextButton: TextView,
    private val searchCloseButton: TextView,
    private val tabBarRow: View,
    private val pathBar: View,
    private val contentColumn: View,
    private val bottomControlsColumn: LinearLayout,
    private val bottomControlsRow: LinearLayout,
    private val landscapeSuggestionsSlot: FrameLayout,
    private val landscapeKeyboardSlot: FrameLayout,
    private val landscapeControlsSlot: FrameLayout,
    // Paso 13: antes isEditorModeVisible (editorContainer.visibility ==
    // VISIBLE) — con explorador y editor conviviendo siempre, lo que de
    // verdad decide si hay "header" (tabBarRow/pathBar/customKeyboard) que
    // mostrar es si hay una pestaña activa, no un modo de pantalla.
    private val hasOpenTab: () -> Boolean,
    private val setRequestedOrientation: (Int) -> Unit,
    private val getString: (Int) -> String,
    // Paso 7 (§5): abre SettingsActivity. Lambda inyectada en vez de pasar
    // la Activity completa — mismo patrón que setRequestedOrientation/
    // getString, que tampoco necesitan más que esa única capacidad.
    private val openSettings: () -> Unit,
    // Paso 7: "Guardar como" (SAVE sostenido, ver DragAction.SAVE_AS).
    // Lambda, no la referencia directa a SaveAsDialogController: ese
    // controller se construye DESPUÉS de ControlsController.setup() en
    // EditorActivity.onCreate (necesita dialogFormHost/tabsController, ya
    // construidos para ese punto, ver su comentario), así que una
    // referencia directa en el constructor fallaría por orden de
    // construcción. La lambda se resuelve recién cuando el usuario
    // realmente sostiene el gesto, momento en el que onCreate ya terminó
    // por completo.
    private val openSaveAsDialog: () -> Unit,
    // SystemInsetsController.bottomInsetPx(): el teclado (CustomKeyboardView)
    // tiene filas de alto fijo en dp, no proporcional al espacio disponible
    // — no se comprime aunque bottomControlsRow reciba menos alto vía
    // padding. Por eso el inset se SUMA acá al alto total en vez de restarse
    // del contenido (ver reapplyLandscapeRowLayoutIfVisible).
    private val bottomInsetPx: () -> Int
) {

    private var isLandscapeForced = false

    /** Llamado al final de onHideKeyboardClick (botón "^"/"⌄"), después de
     *  syncBottomControlsMargin() — EditorActivity lo usa para que
     *  GitActionsController.repositionOpenOverlay() reubique un dropdown/
     *  panel de atajos que haya quedado abierto: esos overlays fijan su
     *  posición una sola vez al abrirse, así que si el usuario los abre y
     *  recién ahí muestra/oculta el teclado con "^", quedaban mal
     *  ubicados. null por defecto (GitActionsController no existe hasta
     *  el primer showConsole, ver ConsoleController) — no-op mientras no
     *  se asigne. */
    var onKeyboardVisibilityChangedByButton: (() -> Unit)? = null

    fun activeCodeEditText(): CodeEditText? = tabs.activeCodeEditText()

    /** No-op desde el Paso 7 (ya no hay undoButton/redoButton visuales:
     *  Deshacer/Rehacer se movieron al drag-abajo de las teclas Z/Y — ver
     *  KeyboardLayouts.DEFAULT_DRAG_ACTIONS). Se mantiene como punto de
     *  entrada porque TabsController/CodeEditText siguen emitiendo la señal
     *  de disponibilidad (onUndoRedoAvailabilityChanged) por si algún
     *  consumidor futuro la necesita (p. ej. el menú de configuración). */
    fun updateUndoRedoButtons(canUndo: Boolean, canRedo: Boolean) {}

    private fun setupSearchBar() {
        searchInputBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                // requestSearchUpdate (debounce + cálculo en hilo de fondo,
                // ver su doc en CodeEditText) en vez de updateSearch directo
                // acá: mismo fix que ya tenía el buscador del explorador
                // para el mismo problema (recalcular todo en cada tecla,
                // síncrono, sentía "trabado" el teclado en archivos grandes).
                activeCodeEditText()?.requestSearchUpdate(s?.toString() ?: "") { count ->
                    updateSearchMatchCountLabel(count)
                } ?: updateSearchMatchCountLabel(0)
            }
        })
        searchInputBox.showSoftInputOnFocus = false
        searchInputBox.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) routeKeyboardToSearchBar()
        }
        searchInputBox.setOnClickListener { routeKeyboardToSearchBar() }
        searchNextButton.setOnClickListener {
            activeCodeEditText()?.let {
                it.jumpToNextSearchMatch()
                updateSearchMatchCountLabel(it.searchMatchCount())
            }
        }
        searchPrevButton.setOnClickListener {
            activeCodeEditText()?.let {
                it.jumpToPreviousSearchMatch()
                updateSearchMatchCountLabel(it.searchMatchCount())
            }
        }
        searchCloseButton.setOnClickListener { closeSearchBar() }
    }

    private fun updateSearchMatchCountLabel(count: Int) {
        val editor = activeCodeEditText()
        searchMatchCountText.text = when {
            searchInputBox.text.isNullOrEmpty() -> ""
            count == 0 -> getString(R.string.search_no_matches)
            else -> "${editor?.searchMatchDisplayIndex() ?: 0}/$count"
        }
    }

    /** Abre searchBarPanel sobre el editor activo y enruta el teclado
     *  propio al campo de búsqueda. */
    fun openSearchBar() {
        val params = searchBarPanel.layoutParams as FrameLayout.LayoutParams
        params.gravity = Gravity.TOP
        params.topMargin = if (tabBarRow.visibility == View.VISIBLE) {
            val loc = IntArray(2)
            tabBarRow.getLocationInWindow(loc)
            loc[1] + tabBarRow.height
        } else {
            ViewCompat.getRootWindowInsets(searchBarPanel)
                ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        }
        searchBarPanel.layoutParams = params
        searchBarPanel.visibility = View.VISIBLE
        searchInputBox.requestFocus()
        routeKeyboardToSearchBar()
    }

    /** Cierra searchBarPanel si estaba abierto; no-op si no. Llamado al
     *  cambiar de pestaña (el buscador apunta al CodeEditText de la
     *  pestaña saliente, no tendría sentido dejarlo abierto). */
    fun closeSearchBarIfOpen() {
        if (searchBarPanel.visibility == View.VISIBLE) closeSearchBar()
    }

    private fun closeSearchBar() {
        searchBarPanel.visibility = View.GONE
        searchInputBox.setText("")
        activeCodeEditText()?.clearSearch()
        tabs.restoreKeyboardTargetToEditor()
    }

    private fun routeKeyboardToSearchBar() {
        keyboardRouter.routeTo(
            PlainEditTextTarget(searchInputBox) {
                activeCodeEditText()?.let {
                    it.jumpToNextSearchMatch()
                    updateSearchMatchCountLabel(it.searchMatchCount())
                }
                true
            }
        )
    }

    fun setup() {
        runButton.setOnClickListener { runController.runCurrentFile() }
        setupSearchBar()
        controlBar.onRotateClick = { toggleLandscape() }
        controlBar.onSettingsClick = { openSettings() }
        customKeyboard.onCopyOrPaste = { controlBar.deactivateSelectMode() }
        // Bug reportado y corregido (ver doc de onActivateSelectMode en
        // CustomKeyboardView): armar el ancla de E ponía selectModeActive
        // real en el target directamente, sin que ControlBarView se
        // enterara — su propio flag quedaba en false, lo que después
        // rompía deactivateSelectMode() al cancelar el ancla.
        customKeyboard.onActivateSelectMode = { controlBar.activateSelectMode() }
        // Paso 7: acciones del drag-abajo del qwerty que CustomKeyboardView
        // no puede resolver por sí mismo (ver doc de onDragAction) —
        // reutilizan exactamente la misma lógica que sus botones
        // homólogos de arriba, ahora también disparados desde el teclado.
        customKeyboard.onDragAction = { action ->
            when (action) {
                KeyboardLayouts.DragAction.SAVE -> tabs.persistCurrentFile()
                KeyboardLayouts.DragAction.UNDO -> activeCodeEditText()?.undo()
                KeyboardLayouts.DragAction.REDO -> activeCodeEditText()?.redo()
                KeyboardLayouts.DragAction.SELECT_ALL -> selectAllInActiveEditor()
                KeyboardLayouts.DragAction.FIND -> openSearchBar()
                KeyboardLayouts.DragAction.DUPLICATE_LINE -> activeCodeEditText()?.duplicateCurrentLine()
                KeyboardLayouts.DragAction.DELETE_LINE -> activeCodeEditText()?.deleteCurrentLine()
                KeyboardLayouts.DragAction.SAVE_AS -> openSaveAsDialog()
                KeyboardLayouts.DragAction.GOTO -> goToDefinitionOrFile()
                // CUT/COPY/PASTE/TOGGLE_COMMENT/ANCHOR_SELECTION: resueltos
                // dentro de CustomKeyboardView, nunca llegan hasta acá (ver
                // applyDragAction()).
                else -> {}
            }
        }
        controlBar.onHideKeyboardClick = { hidden ->
            if (hidden) {
                keyboardRouter.hideKeyboard()
            } else {
                keyboardRouter.showKeyboard()
            }
            syncBottomControlsMargin()
            onKeyboardVisibilityChangedByButton?.invoke()
        }
        updateUndoRedoButtons(canUndo = false, canRedo = false)
    }

    /** Extraído de selectAllButton (Paso 7: también lo dispara el
     *  drag-abajo de la tecla A) — mismo criterio exacto: selectAll()
     *  nativo del EditText activo, y apaga el modo selección extendida de
     *  controlBar si estaba activo. */
    private fun selectAllInActiveEditor() {
        activeCodeEditText()?.let { editText -> editText.selectAll() }
        controlBar.deactivateSelectMode()
    }

    /** Tecla G del drag-abajo (Paso 7, extensión — ver GotoResolver.kt):
     *  sin cuadro de búsqueda ni lista, siempre sobre el identificador
     *  bajo el cursor o la selección activa del editor. Si GotoResolver no
     *  encuentra ningún destino (sin match, o sin match único), no hace
     *  nada — ni toast, ni ningún otro aviso, por decisión explícita. */
    private fun goToDefinitionOrFile() {
        val editor = activeCodeEditText() ?: return
        val sourceFile = editor.openFile?.file
        val wordRange = editor.wordOrSelectionAtCursor() ?: return
        val fullText = editor.text?.toString() ?: return

        val result = GotoResolver.resolve(
            lang = editor.lang,
            sourceFile = sourceFile,
            fullText = fullText,
            wordStart = wordRange.first,
            wordEnd = wordRange.last + 1
        )
        when (result) {
            is GotoResolver.GotoResult.None -> {}
            is GotoResolver.GotoResult.OpenFile -> {
                // Si el destino es el propio archivo activo, NO pasar por
                // openOrFocus(): reconstruye el editor leyendo
                // OpenFile.content (el modelo en memoria), que puede estar
                // desactualizado respecto al texto real en pantalla si no
                // hubo un guardado/sync reciente — pisaría ediciones sin
                // guardar. Mismo editor ya visible: solo mover el cursor.
                val isSameFile = sourceFile?.absolutePath == result.file.absolutePath
                if (!isSameFile) {
                    tabs.openOrFocus(result.file)
                }
                if (result.line != null) {
                    activeCodeEditText()?.let { target ->
                        val text = target.text?.toString() ?: return@let
                        val lines = text.split('\n')
                        if (result.line < lines.size) {
                            val offset = lines.take(result.line).sumOf { it.length + 1 }
                            target.setSelection(offset.coerceIn(0, text.length))
                        }
                    }
                }
            }
        }
    }

    private fun toggleLandscape() {
        isLandscapeForced = !isLandscapeForced
        setRequestedOrientation(
            if (isLandscapeForced) {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        )
    }

    /** Pública: EditorActivity.showExplorerMode() la llama directo para
     *  volver el margen a 0 al salir del editor (el explorador no reserva
     *  espacio para controlBar/customKeyboard). */
    fun setContentColumnBottomMargin(px: Int) {
        val params = contentColumn.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.bottomMargin == px) return
        params.bottomMargin = px
        contentColumn.layoutParams = params
    }

    private fun bottomControlsColumnHeightPx(): Int =
        if (bottomControlsRow.visibility == View.VISIBLE) {
            bottomControlsRow.measuredHeight
        } else {
            bottomControlsColumn.measuredHeight
        }

    /** Fuerza un measure+layout SÍNCRONO de [view] con su ancho real actual
     *  (tomado de su padre, measuredWidth ya correcto de un layout pass
     *  previo) y alto wrap_content (o el alto EXACTO dado en [exactHeightPx],
     *  cuando [view] ya tiene un layoutParams.height fijo que debe
     *  respetarse — ver su único uso en applyBottomControlsOrientation),
     *  para que measuredHeight ya refleje cualquier cambio de visibilidad
     *  recién asignado a sus hijos SIN esperar a que Android decida
     *  programar el próximo layout pass por su cuenta. Ver doc de
     *  syncBottomControlsMargin para el porqué. */
    private fun forceMeasureAndLayout(view: View, exactHeightPx: Int? = null) {
        val parent = view.parent as? View
        val widthPx = parent?.width?.takeIf { it > 0 } ?: view.width.takeIf { it > 0 }
        // Si ni el padre ni la vista misma tienen todavía un ancho real
        // medido, UNSPECIFIED deja que la vista mida su propio ancho natural
        // en vez de forzar un EXACTLY con 0px, que la dejaría sin pintar nada.
        val widthSpec = if (widthPx != null) {
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        } else {
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        }
        // UNSPECIFIED (comportamiento de siempre) deja que view mida su
        // propio alto natural — correcto para wrap_content real. Pero si
        // [view] ya tiene un layoutParams.height FIJO que queremos que se
        // respete (en vez de que un LinearLayout horizontal con hijos
        // MATCH_PARENT termine midiendo un alto ambiguo propio), EXACTLY
        // con ese valor fuerza justamente ese alto.
        val heightSpec = if (exactHeightPx != null) {
            View.MeasureSpec.makeMeasureSpec(exactHeightPx, View.MeasureSpec.EXACTLY)
        } else {
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        }
        view.measure(widthSpec, heightSpec)
        view.layout(view.left, view.top, view.left + view.measuredWidth, view.top + view.measuredHeight)
    }

    /** Recalcula el margen de contentColumn a partir de la altura real de
     *  bottomControlsColumn (o bottomControlsRow en landscape).
     *
     *  Depender solo de post{} asume que, para cuando esa lambda corra,
     *  Android ya habrá hecho un layout pass completo que deje
     *  measuredHeight al día con la visibilidad recién asignada — pero
     *  post{} solo encola un Runnable en el message queue, sin garantía de
     *  que un layout pass real haya ocurrido antes de que le toque el turno
     *  (varias vistas cambiando de visibilidad en el mismo frame pueden
     *  hacer que el layout pass real llegue después, dejando una ventana
     *  donde contentColumn tiene el margen viejo mientras sus hermanos ya
     *  cambiaron). Medir y layoutear bottomControlsColumn/bottomControlsRow
     *  a mano acá, de forma síncrona, antes de leer measuredHeight, elimina
     *  esa ventana. */
    fun syncBottomControlsMargin() {
        val measuredView = if (bottomControlsRow.visibility == View.VISIBLE) bottomControlsRow else bottomControlsColumn
        forceMeasureAndLayout(measuredView)
        setContentColumnBottomMargin(bottomControlsColumnHeightPx())
    }

    fun applyHeaderVisibility(keyboardOpen: Boolean) {
        if (!hasOpenTab()) return
        val isLandscape = runButton.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val hideHeader = isLandscape && keyboardOpen
        if (!isLandscape) {
            tabBarRow.visibility = View.VISIBLE
            pathBar.visibility = View.VISIBLE
            return
        }
        tabBarRow.visibility = if (hideHeader) View.GONE else View.VISIBLE
        pathBar.visibility = if (hideHeader) View.GONE else View.VISIBLE
    }

    /** Quita [view] de su padre actual solo si de verdad lo tiene todavía —
     *  una migración ya en curso (p. ej. disparada dos veces casi a la vez
     *  por rotación + cambio de insets casi simultáneos) no debe intentar
     *  un segundo removeView sobre una vista que ya quedó sin padre. */
    private fun detachIfAttached(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    fun applyBottomControlsOrientation(orientation: Int) {
        val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape == (bottomControlsRow.visibility == View.VISIBLE)) return

        // El CodeEditText activo no se recrea al rotar (solo se reorganiza
        // el layout de controles, ver resto de esta función), así que su
        // zona muerta inferior (ver TabsController.applySuggestionsDeadzone)
        // quedaría con el valor de la orientación anterior si nadie la
        // reaplica acá.
        activeCodeEditText()?.let { tabs.applySuggestionsDeadzone(it) }

        if (landscape) {
            detachIfAttached(suggestionsBar)
            detachIfAttached(customKeyboard)
            detachIfAttached(controlBar)
            // El slot mismo se fija al ancho exacto que la vista rotada
            // necesita (su propio expectedHeightPx()/
            // expectedWidthPxVertical() — el "espesor" real tras rotar),
            // en vez de un ancho fijo en XML (56dp) que podía desalinearse
            // si esas constantes cambiaban sin tocar también el XML. Con
            // el slot ya del tamaño exacto, la vista hija puede ser
            // MATCH_PARENT sin riesgo de que el rectángulo medido antes de
            // rotar quede más grande que el slot y se recorte/desaparezca
            // tras la rotación de 270° (un FrameLayout hace clip de sus
            // hijos por defecto).
            val suggestionsSlotParams = landscapeSuggestionsSlot.layoutParams
            // x2: la columna angosta (= expectedHeightPx() del chip)
            // resultaba muy estrecha para el texto horizontal sin rotar (ver
            // chipFor() en SuggestionsBarView, que abandonó la rotación
            // 270°) — se duplica solo el ancho de este slot, sin tocar
            // expectedHeightPx() en sí (lo sigue usando portrait y
            // ensureCursorVisibleAbove).
            suggestionsSlotParams.width = suggestionsBar.expectedHeightPx() * 2
            landscapeSuggestionsSlot.layoutParams = suggestionsSlotParams
            val controlsSlotParams = landscapeControlsSlot.layoutParams
            controlsSlotParams.width = controlBar.expectedWidthPxVertical()
            landscapeControlsSlot.layoutParams = controlsSlotParams

            landscapeSuggestionsSlot.addView(
                suggestionsBar,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
            landscapeKeyboardSlot.addView(
                customKeyboard,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            )
            landscapeControlsSlot.addView(
                controlBar,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
            suggestionsBar.setVertical(true)
            controlBar.setVertical(true)
            bottomControlsColumn.visibility = View.GONE
            bottomControlsRow.visibility = View.VISIBLE
            // Alto calculado de forma síncrona a partir del alto fijo
            // conocido del teclado (CustomKeyboardView.expectedHeightPx()),
            // no medido de forma asíncrona tras el hecho: eso dejaba una
            // ventana donde syncBottomControlsMargin corría con una altura
            // todavía vieja.
            val rowParams = bottomControlsRow.layoutParams
            // + bottomInsetPx(): el teclado no puede comprimirse para
            // cederle espacio al padding bottom que SystemInsetsController
            // le aplica (ver doc de ese parámetro) — el alto total tiene
            // que incluir ya ese espacio extra, o el padding simplemente
            // lo resta de un contenido que no cede, dejando un hueco vacío
            // en vez de empujar el teclado hacia arriba.
            rowParams.height = customKeyboard.expectedHeightPx() + bottomInsetPx()
            bottomControlsRow.layoutParams = rowParams
            // Measure+layout síncrono de TODA la fila landscape, en cascada
            // sobre sus 3 slots hijos (incluida suggestionsBar ya rotada
            // dentro de landscapeSuggestionsSlot): asignar un nuevo
            // layoutParams.width a un slot, o llamar addView/setVertical,
            // solo marca el árbol como "sucio" (requestLayout) — el
            // remeasure real de Android llega recién en el próximo
            // choreographer frame, después de que este método ya terminó.
            // syncBottomControlsMargin() de por sí solo mide
            // bottomControlsRow para el margen de contentColumn, sin
            // garantizar que ese remeasure en cascada ya haya ocurrido —
            // por eso suggestionsBar podía quedar con un tamaño/posición
            // viejo (o sin medir) en el primer frame visible tras rotar.
            forceMeasureAndLayout(bottomControlsRow, exactHeightPx = rowParams.height)
        } else {
            detachIfAttached(suggestionsBar)
            detachIfAttached(customKeyboard)
            detachIfAttached(controlBar)
            suggestionsBar.setVertical(false)
            controlBar.setVertical(false)
            bottomControlsColumn.addView(
                suggestionsBar,
                0,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
            bottomControlsColumn.addView(
                customKeyboard,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
            bottomControlsColumn.addView(
                controlBar,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
            val rowParams = bottomControlsRow.layoutParams
            rowParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
            bottomControlsRow.layoutParams = rowParams
            bottomControlsRow.visibility = View.GONE
            bottomControlsColumn.visibility = View.VISIBLE
        }
        syncBottomControlsMargin()
    }

    /** Fuerza measure+layout síncrono de controlBar antes de aplicarle
     *  margen: sin esto, el primer frame en el que showEditorMode() la pone
     *  VISIBLE puede dibujarla sin que sus TextView hijos ya tengan
     *  posición/tamaño reales (fila gris sin glifos por un frame), porque
     *  syncBottomControlsMargin solo mide el contenedor, no garantiza que
     *  ControlBarView en sí haya completado su propio layout pass. */
    fun forceControlBarLayout() {
        forceMeasureAndLayout(controlBar)
    }

    /** Re-fuerza el measure+layout síncrono de bottomControlsRow con su alto
     *  exacto ya fijado (ver applyBottomControlsOrientation), SIN recalcular
     *  ese alto. Llamado por SystemInsetsController después de aplicarle su
     *  padding bottom (inset de sistema): setPadding() por sí solo no
     *  dispara un remeasure síncrono, así que el padding quedaba asignado
     *  pero sin reflejarse — el contenido interno (suggestionsSlot/
     *  keyboardSlot/controlsSlot) seguía ocupando el mismo espacio de
     *  antes, tapado por la franja de gestos. No-op si no estamos en
     *  landscape (bottomControlsRow GONE): no hay nada que reacomodar. */
    fun reapplyLandscapeRowLayoutIfVisible() {
        if (bottomControlsRow.visibility != View.VISIBLE) return
        forceMeasureAndLayout(bottomControlsRow, exactHeightPx = bottomControlsRow.layoutParams.height)
    }
}
