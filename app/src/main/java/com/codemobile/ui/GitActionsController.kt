package com.codemobile.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codemobile.R
import com.codemobile.git.GitRepository
import com.codemobile.keyboard.PlainEditTextTarget

/**
 * Atajos de botón para los comandos git más comunes (ver view_git_actions,
 * view_git_shortcuts_panel, view_git_branch_dropdown). No reimplementa
 * ninguna ejecución de git: cada botón arma la misma línea de texto que el
 * usuario escribiría a mano en consoleGitInput y la pasa a
 * [ConsoleController.submitGitCommandFromButton] — un solo camino real de
 * ejecución, consistente con la consola.
 *
 * Para los atajos que necesitan un dato del usuario (commit -m, nueva
 * rama, rm <archivo>, reset <destino>) NO se ejecuta nada al tocar el
 * botón: se abre un input inline dentro del mismo panel/overlay y el
 * comando recién se arma y despacha completo cuando el usuario confirma
 * ese input (ver [showInlineInput]) — así nunca se dispara un comando a
 * medias.
 *
 * Ambos overlays (dropdown de ramas, panel de atajos) anclan SIEMPRE
 * hacia ARRIBA de su botón, sin cálculo dinámico de espacio: con el
 * teclado propio (CustomKeyboardView) ocupando buena parte de la pantalla
 * mientras la consola está abierta, casi nunca hay espacio real debajo
 * para un overlay que abra hacia abajo.
 *
 * Depende de [consoleController] para la ejecución real y para
 * [ConsoleController.listBranchesForCurrentDir] (consulta local, no pasa
 * por GitForegroundService). [anchorContainer] es el overlay hermano del
 * root donde se anclan branchDropdown/shortcutsPanel, igual patrón que
 * headerActionsPanel/dialogFormContainer en activity_editor.xml.
 *
 * [keyboardRouter] enruta el input inline de [showInlineInput] al teclado
 * propio (CustomKeyboardView) — el IME de sistema está vetado en toda la
 * app (todo EditText existente usa showSoftInputOnFocus=false +
 * KeyboardRouter, ver consoleGitInput/searchInputBox/explorerSearchBox),
 * así que este input no es una excepción a esa regla.
 *
 * [bottomControlsColumn] es necesario para el "tocar afuera para cerrar"
 * de closeOverlayOnOutsideTouch: gitActionsOverlay es MATCH_PARENT y vive
 * por encima de todo (elevation alta, igual que dialogFormContainer), así
 * que un listener de click que cubra toda su superficie intercepta
 * también los toques sobre CustomKeyboardView — que vive en
 * bottomControlsColumn, HERMANO de gitActionsOverlay, no hijo — antes de
 * que le lleguen al teclado. BUG encontrado: eso hacía que tocar una
 * tecla cerrara el input en vez de escribir en él. Se soluciona dejando
 * pasar el toque (no cerrando ni consumiéndolo) cuando cae dentro del
 * rectángulo real de bottomControlsColumn en pantalla.
 *
 * Ambos overlays anclan siempre hacia ARRIBA del botón que los abre; el
 * límite real de cuánto pueden crecer hacia arriba es simplemente el
 * borde superior de la pantalla — ver [maxOverlayHeight]. */
class GitActionsController(
    private val layoutInflater: LayoutInflater,
    private val anchorContainer: FrameLayout,
    private val consoleController: ConsoleController,
    private val keyboardRouter: KeyboardRouter,
    private val bottomControlsColumn: View,
    private val syncBottomControlsMargin: () -> Unit,
    private val branchButton: View,
    private val branchButtonText: TextView,
    private val shortcutsButton: View
) {

    /** Overlay actualmente abierto (dropdown de ramas, panel de atajos o
     *  input inline), null si no hay ninguno. Se cierra tocando fuera,
     *  igual que headerActionsPanel. */
    private var openOverlay: View? = null

    /** Repite, desde cero, el mismo cálculo de posición/alto usado al abrir
     *  el overlay actual — nunca parte de un lp.leftMargin/lp.topMargin ya
     *  mutado por una llamada anterior (eso fue justo el bug encontrado la
     *  primera vez: acumulaba desplazamiento en cada toggle de teclado),
     *  siempre mide desde cero con getLocationOnScreen()/content.height.
     *  Null si no hay overlay abierto. */
    private var openOverlayReposition: (() -> Unit)? = null

    /** Llamado por EditorActivity cuando el botón "^"/"⌄" de ControlBarView
     *  muestra/oculta el teclado propio (ver ControlsController.setup/
     *  onHideKeyboardClick). branchButton/shortcutsButton SÍ se mueven con
     *  ese cambio (la consola se reacomoda para dejarle espacio al
     *  teclado, ver contentColumn), así que repetir el cálculo original
     *  de posición basta para que el overlay los siga — no hace falta
     *  sumar a mano la altura del teclado.
     *
     *  anchorContainer.post{} y no una invocación síncrona: este método se
     *  llama desde dentro de onHideKeyboardClick, en el MISMO callback
     *  síncrono donde syncBottomControlsMargin() recién acaba de cambiar
     *  el bottomMargin de contentColumn — ese cambio todavía no tuvo su
     *  layout pass real (measure+layout) en este punto, así que
     *  branchButton/shortcutsButton seguían reportando su posición VIEJA
     *  en pantalla si se leía ahora mismo (BUG encontrado: el overlay se
     *  movía al revés de lo esperado, y además se corría de lado, porque
     *  tanto la posición de anchor como el ancho medido de content se
     *  leían a medio actualizar). post{} encola esto para después de que
     *  Android complete ese layout pass. No-op si no hay overlay abierto. */
    fun repositionOpenOverlay() {
        val reposition = openOverlayReposition ?: return
        anchorContainer.post { reposition() }
    }

    fun setup() {
        refreshBranchLabel()
        branchButton.setOnClickListener { showBranchDropdown() }
        shortcutsButton.setOnClickListener { showShortcutsPanel() }
    }

    /** Llamado después de cualquier comando que pueda mover HEAD (push,
     *  pull, checkout, merge, rebase) y al abrir la consola git — mismo
     *  criterio que GitRepository.branch() usa para marcar la rama
     *  actual, pero vía listBranchesForCurrentDir() en vez de reconstruir
     *  la búsqueda de repoRoot acá. */
    fun refreshBranchLabel() {
        val branches = consoleController.listBranchesForCurrentDir()
        val current = branches.firstOrNull { it.isCurrent }?.name
        branchButtonText.text = current ?: "—"
    }

    /** true mientras el overlay abierto es el input inline de
     *  showInlineInput (el único de los tres que enruta el teclado
     *  propio) — closeOverlay() lo usa para saber si hay que ocultar el
     *  teclado al cerrar; el dropdown de ramas y el panel de atajos no
     *  tocan el teclado en absoluto. */
    private var openOverlayOwnsKeyboard = false

    /** true si el punto de pantalla ([rawX], [rawY]) cae dentro del
     *  rectángulo que ocupa bottomControlsColumn ahora mismo (incluye
     *  CustomKeyboardView, ControlBarView y suggestionsBar — toda la
     *  columna fija al borde inferior, ver su comentario en
     *  activity_editor.xml). Se mide en cada toque, no una vez: el alto
     *  de esa columna cambia si el teclado se muestra/oculta. */
    private fun isTouchOverBottomControlsColumn(rawX: Float, rawY: Float): Boolean {
        val loc = IntArray(2)
        bottomControlsColumn.getLocationOnScreen(loc)
        val left = loc[0]
        val top = loc[1]
        val right = left + bottomControlsColumn.width
        val bottom = top + bottomControlsColumn.height
        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom
    }

    /** Alto máximo disponible para un overlay anclado arriba de [anchor]:
     *  desde el borde superior real de la pantalla (el propio
     *  anchorContainer, FrameLayout MATCH_PARENT hermano del root — ver
     *  doc de clase) hasta el borde superior de [anchor]. Se mide en cada
     *  apertura, no una vez — la posición de anchor en pantalla puede
     *  variar (p.ej. con el teclado visible o no).
     *
     *  Antes (Paso 13 y anteriores) el límite era explorerHeaderRow (fila
     *  fija del explorador) en vez del borde de pantalla: tenía sentido
     *  mientras esa fila vivía arriba del todo del explorador, cerca de
     *  donde podía solaparse visualmente con estos overlays. Al reubicar
     *  esa fila al fondo del explorador (pedido del usuario, ver historial),
     *  ya no hay ninguna relación de posición entre ambos — el límite
     *  correcto siempre fue, de forma más simple y general, no salirse de
     *  la pantalla. */
    private fun maxOverlayHeight(anchor: View): Int {
        val anchorLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)
        val containerLoc = IntArray(2)
        anchorContainer.getLocationOnScreen(containerLoc)
        return (anchorLoc[1] - containerLoc[1]).coerceAtLeast(0)
    }

    private fun closeOverlay() {
        openOverlay?.let { anchorContainer.removeView(it) }
        openOverlay = null
        openOverlayReposition = null
        anchorContainer.visibility = View.GONE
        if (openOverlayOwnsKeyboard) {
            // El EditText enrutado ya fue removido de la jerarquía (línea
            // de arriba) — sin esto, el teclado propio quedaba apuntando
            // a una vista muerta y ningún campo real recibía las teclas
            // hasta que el usuario tocara otra cosa a mano.
            keyboardRouter.hideKeyboard()
            openOverlayOwnsKeyboard = false
            // hideKeyboard() oculta CustomKeyboardView pero no recalcula
            // el alto de bottomControlsColumn — ese recálculo es
            // syncBottomControlsMargin() (mismo lambda que usa
            // ConsoleController al cerrar la consola). Sin esto, el
            // espacio reservado para el teclado quedaba en blanco hasta
            // que algún OTRO evento disparara ese recálculo por su
            // cuenta, dando la falsa sensación de que había que "cerrar
            // dos veces".
            syncBottomControlsMargin()
        }
    }

    /** Ancla [content] siempre ARRIBA de [anchor] (el fondo de content
     *  queda pegado al techo de anchor), alineado por la izquierda con
     *  anchor — usado por el dropdown de ramas, que vive del lado
     *  izquierdo de la fila y tiene espacio de sobra hacia la derecha.
     *  Sin cálculo de espacio disponible: fijo siempre hacia arriba (ver
     *  doc de clase). */
    private fun openOverlayAbove(anchor: View, content: View) {
        openOverlayAnchored(anchor, content, alignRight = false)
    }

    /** Igual que [openOverlayAbove], pero alineado por la DERECHA con
     *  [anchor] (el borde derecho de content coincide con el borde
     *  derecho de anchor) — usado por shortcutsButton, que vive en el
     *  extremo derecho de la fila: un overlay alineado a la izquierda se
     *  saldría de la pantalla por la derecha. */
    private fun openOverlayAboveLeft(anchor: View, content: View) {
        openOverlayAnchored(anchor, content, alignRight = true)
    }

    /** Ancla [content] siempre ARRIBA de [anchor], ocupando el ANCHO
     *  COMPLETO de anchorContainer (sin leftMargin ni ancho fijo) — usado
     *  solo por showInlineInput: a diferencia del dropdown de ramas o el
     *  panel de atajos (listas angostas que tiene sentido ver recortadas
     *  a un costado), un campo de texto para escribir se usa mejor con
     *  todo el ancho de la pantalla disponible. */
    private fun openOverlayAboveFullWidth(anchor: View, content: View) {
        closeOverlay()
        registerOpenOverlay(content)
        val reposition = {
            val anchorLoc = IntArray(2)
            anchor.getLocationOnScreen(anchorLoc)
            val containerLoc = IntArray(2)
            anchorContainer.getLocationOnScreen(containerLoc)
            val lp = content.layoutParams as FrameLayout.LayoutParams
            lp.topMargin = (anchorLoc[1] - containerLoc[1]) - content.height
            content.layoutParams = lp
        }
        openOverlayReposition = reposition
        content.post { reposition() }
    }

    /** Agrega [content] al overlay y deja montado el "tocar afuera para
     *  cerrar" compartido por los tres overlays (dropdown, panel de
     *  atajos, input inline) — ver doc de clase sobre
     *  bottomControlsColumn/isTouchOverBottomControlsColumn. Cada caller
     *  todavía debe posicionar [content] (leftMargin/topMargin) en su
     *  propio content.post {}. */
    private fun registerOpenOverlay(content: View) {
        anchorContainer.visibility = View.VISIBLE
        anchorContainer.addView(content)
        // OnTouchListener, no OnClickListener: gitActionsOverlay es
        // MATCH_PARENT y cubre toda la pantalla en Z-order (elevation
        // alta), incluida la franja donde CustomKeyboardView se ve
        // dibujado — aunque ese teclado viva en bottomControlsColumn, un
        // HERMANO de este overlay, no un hijo. Un OnClickListener ciego
        // trataba cualquier tap ahí como "tocar afuera para cerrar", lo
        // que impedía escribir con el teclado propio. Con OnTouchListener
        // se puede mirar las coordenadas del evento y, si caen dentro del
        // rectángulo real de bottomControlsColumn en pantalla, dejar
        // pasar el toque sin consumirlo (return false) para que llegue al
        // teclado — solo se cierra y se consume (return true) si cae
        // fuera de esa franja.
        anchorContainer.setOnTouchListener { _, event ->
            if (isTouchOverBottomControlsColumn(event.rawX, event.rawY)) {
                false
            } else {
                closeOverlay()
                true
            }
        }
        content.setOnClickListener { /* evita que un tap dentro del panel cierre por el listener de arriba */ }
        openOverlay = content
    }

    private fun openOverlayAnchored(anchor: View, content: View, alignRight: Boolean) {
        closeOverlay()
        registerOpenOverlay(content)
        // Ancho de content congelado la PRIMERA vez que se mide (> 0) y
        // reusado en cada reposition() posterior — nunca releído de
        // content.width en cada pasada. BUG encontrado: una vez que
        // lp.height queda recortado (ver más abajo), el ScrollView/
        // RecyclerView con scroll activo puede medir un ancho levemente
        // distinto al de antes del recorte (reserva de scrollbar, redondeo
        // de medición con un heightSpec EXACTLY que cambia en cada toggle
        // de teclado, según maxOverlayHeight) — leer ese ancho fluctuante
        // en la fórmula de alignRight hacía que leftMargin creciera o
        // encogiera un poco en cada toque, dando la apariencia de
        // "correrse a la izquierda" con cada toggle.
        var fixedContentWidth = -1
        // reposition() se guarda en openOverlayReposition (ver su doc) y
        // también posiciona content la primera vez, acá mismo — así
        // repositionOpenOverlay() puede repetir EXACTAMENTE el mismo
        // cálculo más adelante (p.ej. al tocar "^"), sin duplicar esta
        // lógica en otro lado. content.height SIEMPRE se vuelve a medir
        // (nunca se lee lp.height ya asignado por una llamada previa):
        // leer un layoutParams ya mutado fue el bug de acumulación
        // vertical encontrado antes (cada toggle de teclado desplazaba
        // más el overlay en vez de recalcular desde cero). content.width
        // es la única medida que SÍ se congela (ver fixedContentWidth).
        val reposition = {
            val anchorLoc = IntArray(2)
            anchor.getLocationOnScreen(anchorLoc)
            val containerLoc = IntArray(2)
            anchorContainer.getLocationOnScreen(containerLoc)
            val lp = content.layoutParams as FrameLayout.LayoutParams

            if (fixedContentWidth <= 0 && content.width > 0) {
                fixedContentWidth = content.width
            }
            val contentWidth = if (fixedContentWidth > 0) fixedContentWidth else content.width

            // Alto recortado contra topBoundaryView ANTES de calcular
            // topMargin: si content medía más de lo disponible, se le
            // fija ese máximo como alto real (en vez de WRAP_CONTENT),
            // lo que activa el scroll interno del RecyclerView (dropdown
            // de ramas) o del ScrollView (panel de atajos) — ambos ya
            // soportan scroll, solo les faltaba un alto acotado para que
            // se dispare en vez de seguir creciendo fuera de pantalla.
            val maxHeight = maxOverlayHeight(anchor)
            val contentHeight = content.height.coerceAtMost(maxHeight)
            if (content.height > maxHeight) {
                lp.height = maxHeight
            }

            val anchorLeftInContainer = anchorLoc[0] - containerLoc[0]
            lp.leftMargin = if (alignRight) {
                // Borde derecho de content == borde derecho de anchor:
                // ancho de anchor + su offset, menos el ancho FIJO de
                // content (congelado la primera vez, ver fixedContentWidth
                // arriba — nunca el ancho recién medido de esta pasada).
                (anchorLeftInContainer + anchor.width) - contentWidth
            } else {
                anchorLeftInContainer
            }
            // Siempre hacia ARRIBA: el margen superior es la posición Y
            // de anchor menos la altura (ya recortada arriba) de
            // content, nunca anchor.top + anchor.height (eso sería hacia
            // abajo).
            lp.topMargin = (anchorLoc[1] - containerLoc[1]) - contentHeight
            content.layoutParams = lp
        }
        openOverlayReposition = reposition
        content.post { reposition() }
    }

    // ---- Dropdown de ramas ----

    private fun showBranchDropdown() {
        val view = layoutInflater.inflate(R.layout.view_git_branch_dropdown, anchorContainer, false)
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        view.layoutParams = lp

        val branches = consoleController.listBranchesForCurrentDir()
        val recycler = view.findViewById<RecyclerView>(R.id.branchListRecycler)
        recycler.layoutManager = LinearLayoutManager(recycler.context)
        recycler.adapter = BranchAdapter(branches) { branchName ->
            closeOverlay()
            consoleController.submitGitCommandFromButton("git checkout $branchName")
            refreshBranchLabel()
        }

        view.findViewById<View>(R.id.createBranchRow).setOnClickListener {
            closeOverlay()
            showInlineInput(
                anchor = branchButton,
                hint = "New branch name",
                imeActionLabel = "Create",
            ) { branchName ->
                if (branchName.isNotBlank()) {
                    // branchCreate() de GitRepository.branch() no cambia a
                    // la rama nueva (ver su doc) — acá sí tiene sentido
                    // encadenar el checkout, porque "crear rama" desde este
                    // atajo rápido se espera que te deje parado en ella,
                    // a diferencia de "git branch <nombre>" escrito a mano
                    // en consola, que sigue el comportamiento real de git.
                    consoleController.submitGitCommandFromButton("git branch $branchName")
                    consoleController.submitGitCommandFromButton("git checkout $branchName")
                    refreshBranchLabel()
                }
            }
        }

        openOverlayAbove(branchButton, view)
    }

    private class BranchAdapter(
        private val branches: List<GitRepository.BranchInfo>,
        private val onBranchTapped: (String) -> Unit
    ) : RecyclerView.Adapter<BranchAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(R.id.branchItemText)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, position: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.view_git_branch_item, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount(): Int = branches.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val branch = branches[position]
            holder.text.text = if (branch.isCurrent) "* ${branch.name}" else "  ${branch.name}"
            holder.itemView.setOnClickListener { onBranchTapped(branch.name) }
        }
    }

    // ---- Panel de atajos ----

    private fun showShortcutsPanel() {
        val view = layoutInflater.inflate(R.layout.view_git_shortcuts_panel, anchorContainer, false)
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        view.layoutParams = lp

        // Atajos que ejecutan directo, sin pedir ningún dato. Los ids son
        // ahora LinearLayout (ícono + texto), no TextView — ver
        // view_git_shortcuts_panel.xml.
        view.findViewById<View>(R.id.shortcutFetch).setOnClickListener {
            closeOverlay(); consoleController.submitGitCommandFromButton("git fetch")
        }
        view.findViewById<View>(R.id.shortcutLog).setOnClickListener {
            closeOverlay(); consoleController.submitGitCommandFromButton("git log")
        }
        view.findViewById<View>(R.id.shortcutDiff).setOnClickListener {
            closeOverlay(); consoleController.submitGitCommandFromButton("git diff")
        }
        view.findViewById<View>(R.id.shortcutAdd).setOnClickListener {
            closeOverlay(); consoleController.submitGitCommandFromButton("git add .")
        }
        view.findViewById<View>(R.id.shortcutPush).setOnClickListener {
            closeOverlay(); consoleController.submitGitCommandFromButton("git push")
        }
        view.findViewById<View>(R.id.shortcutPull).setOnClickListener {
            closeOverlay()
            consoleController.submitGitCommandFromButton("git pull")
            refreshBranchLabel()
        }
        view.findViewById<View>(R.id.shortcutStash).setOnClickListener {
            closeOverlay(); consoleController.submitGitCommandFromButton("git stash")
        }

        // Atajos que necesitan un dato del usuario antes de poder armar el
        // comando completo: no se ejecuta nada hasta confirmar el input.
        view.findViewById<View>(R.id.shortcutCommit).setOnClickListener {
            closeOverlay()
            showInlineInput(
                anchor = shortcutsButton,
                hint = "Commit message",
                imeActionLabel = "Commit",
            ) { message ->
                if (message.isNotBlank()) {
                    consoleController.submitGitCommandFromButton("git commit -m \"$message\"")
                }
            }
        }
        view.findViewById<View>(R.id.shortcutRm).setOnClickListener {
            closeOverlay()
            showInlineInput(
                anchor = shortcutsButton,
                hint = "File to remove (git rm)",
                imeActionLabel = "Remove",
            ) { pathspec ->
                if (pathspec.isNotBlank()) {
                    consoleController.submitGitCommandFromButton("git rm $pathspec")
                }
            }
        }
        view.findViewById<View>(R.id.shortcutReset).setOnClickListener {
            closeOverlay()
            showInlineInput(
                anchor = shortcutsButton,
                hint = "Target commit/branch (empty = HEAD)",
                imeActionLabel = "Reset",
            ) { target ->
                // --mixed (default de git real) a propósito: es el modo
                // intermedio, nunca descarta cambios del working tree como
                // --hard sí haría — más seguro como atajo rápido de botón.
                val line = if (target.isBlank()) "git reset" else "git reset $target"
                consoleController.submitGitCommandFromButton(line)
            }
        }

        // shortcutsButton vive en el extremo derecho de gitActionsRow: el
        // panel debe abrir hacia la IZQUIERDA (alineado por el borde
        // derecho), o se saldría de la pantalla.
        openOverlayAboveLeft(shortcutsButton, view)
    }

    // ---- Input inline de un solo campo (reemplaza DialogFormHost para este
    // caso: no se ejecuta ningún comando hasta que el usuario confirma) ----

    /** Infla un pequeño overlay con un EditText + botón de confirmar,
     *  anclado arriba de [anchor], ocupando el ancho completo de pantalla
     *  (ver [openOverlayAboveFullWidth]). [onConfirm] recibe el texto tal
     *  cual (puede venir en blanco; cada caller decide si lo ignora) y
     *  solo se llama cuando el usuario toca confirmar o pulsa Enter/Done
     *  — nunca antes, y nunca automáticamente. */
    private fun showInlineInput(
        anchor: View,
        hint: String,
        imeActionLabel: String,
        onConfirm: (String) -> Unit
    ) {
        val row = LinearLayout(anchorContainer.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(resolveColor(R.color.bg_elevated))
            setPadding(dp(10), dp(6), dp(6), dp(6))
        }
        val input = EditText(anchorContainer.context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            this.hint = hint
            setSingleLine(true)
            // showSoftInputOnFocus=false: el IME de sistema está vetado en
            // toda la app, sin excepción — mismo criterio que
            // consoleGitInput/searchInputBox/explorerSearchBox, todos con
            // teclado propio (CustomKeyboardView) vía KeyboardRouter.
            showSoftInputOnFocus = false
        }
        val confirmButton = TextView(anchorContainer.context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            }
            text = imeActionLabel
            setTextColor(resolveColor(R.color.accent))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        row.addView(input)
        row.addView(confirmButton)

        // Ancho completo de pantalla (no dp(260) fijo): un campo de texto
        // se usa mejor con todo el ancho disponible, a diferencia del
        // dropdown de ramas o el panel de atajos.
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        row.layoutParams = lp

        val confirm = {
            val text = input.text?.toString()?.trim() ?: ""
            closeOverlay()
            onConfirm(text)
        }
        confirmButton.setOnClickListener { confirm() }

        openOverlayAboveFullWidth(anchor, row)
        openOverlayOwnsKeyboard = true
        input.requestFocus()
        // Enruta el teclado propio a este input — equivalente al
        // onEditorActionListener que tendría un EditText con IME real,
        // pero vía el teclado propio (ver doc de clase: keyboardRouter).
        keyboardRouter.routeTo(
            PlainEditTextTarget(input, onEnterPressed = {
                confirm()
                true
            })
        )
        // El usuario puede haber llegado a este atajo sin haber tocado
        // antes consoleGitInput (p.ej. tocando el atajo apenas se abre la
        // consola) — en ese caso el teclado propio todavía no está
        // visible, y solo enrutar el target no alcanza para mostrarlo
        // (mismo patrón que EditorActivity.onEditorTouched).
        if (!keyboardRouter.isKeyboardVisible()) {
            keyboardRouter.showKeyboard()
        }
    }

    private fun dp(v: Int): Int = (v * anchorContainer.resources.displayMetrics.density).toInt()
    private fun resolveColor(resId: Int): Int = anchorContainer.context.getColor(resId)
}
