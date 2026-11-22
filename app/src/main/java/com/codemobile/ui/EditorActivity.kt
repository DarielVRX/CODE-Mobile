package com.codemobile.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.RecyclerView
import com.codemobile.R
import com.codemobile.editor.SuggestionsBarView
import com.codemobile.files.PermissionHelper
import com.codemobile.git.GitTaskBus
import com.codemobile.keyboard.AccentPopupView
import com.codemobile.keyboard.CustomKeyboardView
import com.codemobile.keyboard.PlainEditTextTarget
import com.codemobile.keyboard.RingGestureView
import com.codemobile.settings.SettingsActivity

/** Código de request para el permiso POST_NOTIFICATIONS (ver
 *  ConsoleController.runGitInBackground); no se usa onRequestPermissionsResult
 *  porque no hay nada que reintentar si lo niegan — la operación git corre
 *  igual, solo sin notificación visible. */
private const val REQUEST_CODE_POST_NOTIFICATIONS = 2001

/**
 * Activity única de la app (MVP): explorador (pantalla inicial) + tabbar +
 * editor + consola + barra de controles. El inset del teclado se maneja a
 * mano (ver SystemInsetsController), así que la barra de controles queda
 * empujada arriba de él de forma confiable en cualquier versión de Android.
 *
 * Tras el refactor a controllers por dominio, esta clase es un orquestador:
 * crea los 10 controllers (ver cada uno para su responsabilidad), resuelve
 * el ciclo Pestañas↔Controles vía KeyboardRouter (ver el comentario sobre
 * controlsController más abajo) y conserva solo lo que es genuinamente del
 * ciclo de vida de la Activity (permisos, back navigation de nivel
 * superior, insets de IME residual, rotación).
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var contentColumn: LinearLayout
    private lateinit var bottomControlsColumn: LinearLayout
    private lateinit var bottomControlsRow: LinearLayout
    private lateinit var landscapeSuggestionsSlot: FrameLayout
    private lateinit var landscapeKeyboardSlot: FrameLayout
    private lateinit var landscapeControlsSlot: FrameLayout
    private lateinit var tabRecycler: RecyclerView
    private lateinit var pathBar: TextView
    private lateinit var explorerContainer: LinearLayout
    private lateinit var mainRow: LinearLayout
    private lateinit var explorerResizeHandle: View
    private lateinit var explorerSearchBox: EditText
    private lateinit var explorerRecycler: RecyclerView
    private lateinit var explorerTreeScroll: View
    private lateinit var explorerGitBackground: View
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionCountText: TextView
    private lateinit var selectionRenameButton: TextView
    private lateinit var selectionInfoButton: TextView
    private lateinit var confirmTransferButton: TextView
    private lateinit var cancelTransferButton: TextView
    private lateinit var editorContainer: FrameLayout
    private lateinit var editorEmptyPlaceholder: TextView
    private lateinit var consoleContainer: FrameLayout
    private lateinit var htmlPreviewContainer: FrameLayout
    private lateinit var dialogFormContainer: FrameLayout
    private lateinit var gitActionsOverlay: FrameLayout
    private lateinit var actionsLogViewerContainer: FrameLayout
    private lateinit var controlBar: ControlBarView
    private lateinit var customKeyboard: CustomKeyboardView
    private lateinit var suggestionsBar: SuggestionsBarView
    private lateinit var tabBarRow: LinearLayout
    private lateinit var explorerHeaderRow: LinearLayout
    private lateinit var runButton: TextView
    private lateinit var folderButton: TextView
    private lateinit var searchBarPanel: LinearLayout
    private lateinit var searchInputBox: EditText
    private lateinit var searchMatchCountText: TextView
    private lateinit var searchPrevButton: TextView
    private lateinit var searchNextButton: TextView
    private lateinit var searchCloseButton: TextView
    private lateinit var createFileButtonBig: TextView
    private lateinit var gitButtonExplorer: TextView
    private lateinit var actionsButtonExplorer: TextView
    private lateinit var collapseAllButton: TextView

    private var backCallback: OnBackPressedCallback? = null

    // Controllers de dominio (ver cada clase). lateinit porque se
    // construyen en onCreate, después de bindViews() — el orden de
    // construcción importa (ver riesgos del informe de diseño): Insets →
    // KeyboardRouter → ControlsController → DialogFormHost → Tabs →
    // Explorer → Console → HtmlPreview → Run → NewFileDialog.
    private lateinit var systemInsets: SystemInsetsController
    private lateinit var keyboardRouter: KeyboardRouter
    private lateinit var controlsController: ControlsController
    private lateinit var dialogFormHost: DialogFormHost
    private lateinit var tabsController: TabsController
    private lateinit var explorerController: ExplorerController
    private lateinit var consoleController: ConsoleController
    // No lateinit: a diferencia del resto de los controllers, este no se
    // construye en buildControllers() sino recién en el primer
    // showConsole() (ver onGitActionsRowInflated más abajo), porque
    // depende de vistas que solo existen desde que se infla view_console.xml.
    private var gitActionsController: GitActionsController? = null
    private lateinit var htmlPreviewController: HtmlPreviewController
    private lateinit var runController: RunController
    private lateinit var newFileDialogController: NewFileDialogController
    private lateinit var saveAsDialogController: SaveAsDialogController
    private lateinit var actionsPanelController: ActionsPanelController
    private lateinit var actionsLogViewerController: ActionsLogViewerController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Se maneja el inset del teclado (IME) manualmente en vez de confiar en
        // android:windowSoftInputMode="adjustResize": en Android 11+ con un tema
        // moderno, adjustResize por sí solo puede no redimensionar el layout de
        // forma confiable. Con setDecorFitsSystemWindows(false) + un listener de
        // insets, empujamos nosotros mismos el contenido (y por lo tanto la barra
        // de controles) para que quede siempre arriba del teclado real.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_editor)

        bindViews()
        buildControllers()

        // Si la Activity arranca ya en landscape (el sistema la crea con esa
        // orientación desde el principio, no por una rotación posterior),
        // onConfigurationChanged nunca se dispara para avisarlo — hay que
        // aplicar el layout correcto desde el arranque, no solo reaccionar
        // a cambios posteriores.
        controlsController.applyBottomControlsOrientation(resources.configuration.orientation)
        tabsController.setup()
        explorerController.setup()
        controlsController.setup()
        systemInsets.setup()
        consoleController.attach()

        // Orden importa: OnBackPressedCallback se evalúa en orden LIFO (el
        // más reciente primero). setupExitGuard() se registra ANTES para que
        // quede más al fondo de la pila: mientras el callback de navegación
        // de setupBackNavigation() esté habilitado (dentro del explorador, o
        // en la raíz con pestañas abiertas) ese manda; el guard de salida
        // solo actúa cuando ese otro está deshabilitado (dentro del editor,
        // que es exactamente donde antes "Back" salía de la app sin avisar).
        setupExitGuard()
        setupBackNavigation()
        // Se registra AL FINAL (LIFO: el último agregado se evalúa primero)
        // para que Back cierre el preview HTML a pantalla completa antes
        // de llegar a cualquier otro callback (navegación de explorador o
        // guard de salida) — comportamiento esperado de un overlay a
        // pantalla completa, y un segundo camino de cierre además del
        // botón ✕ explícito.
        htmlPreviewController.setupBackHandling()
        // Mismo criterio y mismo motivo que htmlPreviewController arriba:
        // el visor de log de Actions es otro overlay a pantalla completa,
        // así que también se registra al final para que Back lo cierre
        // antes que cualquier otro callback.
        actionsLogViewerController.setupBackHandling()

        if (!PermissionHelper.hasStorageAccess()) {
            showPermissionDialog()
        }

        // Paso 13: explorador y editor ya conviven siempre (panel lateral +
        // columna derecha, ver activity_editor.xml/mainRow) en vez de ser
        // pantallas excluyentes — ya no hace falta elegir "modo" al
        // arrancar. tabBarRow queda visible desde el inicio (contiene
        // folderButton/Run, útiles incluso sin pestañas); el panel del
        // explorador arranca abierto (explorerContainer visible por XML);
        // pathBar/customKeyboard quedan ocultos hasta que haya una pestaña
        // activa (ver syncEditorVisibilityForTabs, ya refleja "sin
        // pestañas" porque openFiles está vacío en este punto).
        tabBarRow.visibility = View.VISIBLE
        syncEditorVisibilityForTabs()
        // activity_editor.xml fija controlBar en visibility="gone" (arranca
        // oculto a propósito, antes de saber si hay que mostrarlo) — sin
        // ponerlo VISIBLE acá, forceControlBarLayout() es un no-op real:
        // medir/layoutear una vista GONE no tiene ningún efecto visual.
        controlBar.visibility = View.VISIBLE
        // Tampoco puede forzarse el layout en el mismo instante: en este
        // punto de onCreate() todavía no ocurrió el primer layout pass real
        // de la Activity, así que ni controlBar ni su padre tienen un ancho
        // medido (ambos en 0) — forceMeasureAndLayout caería a su branch de
        // ancho UNSPECIFIED, midiendo controlBarRow (weight=1f horizontal)
        // con 0px de ancho total. controlBar.post{} encola esto para
        // después de ese primer layout pass, cuando el ancho real ya existe.
        controlBar.post { controlsController.forceControlBarLayout() }

        // No se abre ninguna pestaña "sin título" automáticamente.
        explorerController.refreshExplorer()
    }

    private fun bindViews() {
        contentColumn = findViewById(R.id.contentColumn)
        bottomControlsColumn = findViewById(R.id.bottomControlsColumn)
        bottomControlsRow = findViewById(R.id.bottomControlsRow)
        landscapeSuggestionsSlot = findViewById(R.id.landscapeSuggestionsSlot)
        landscapeKeyboardSlot = findViewById(R.id.landscapeKeyboardSlot)
        landscapeControlsSlot = findViewById(R.id.landscapeControlsSlot)
        tabRecycler = findViewById(R.id.tabRecycler)
        pathBar = findViewById(R.id.pathBar)
        mainRow = findViewById(R.id.mainRow)
        explorerContainer = findViewById(R.id.explorerContainer)
        explorerResizeHandle = findViewById(R.id.explorerResizeHandle)
        explorerSearchBox = findViewById(R.id.explorerSearchBox)
        explorerRecycler = findViewById(R.id.explorerRecycler)
        explorerTreeScroll = findViewById(R.id.explorerTreeScroll)
        explorerGitBackground = findViewById(R.id.explorerGitBackground)
        editorContainer = findViewById(R.id.editorContainer)
        editorEmptyPlaceholder = findViewById(R.id.editorEmptyPlaceholder)
        consoleContainer = findViewById(R.id.consoleContainer)
        htmlPreviewContainer = findViewById(R.id.htmlPreviewContainer)
        dialogFormContainer = findViewById(R.id.dialogFormContainer)
        gitActionsOverlay = findViewById(R.id.gitActionsOverlay)
        actionsLogViewerContainer = findViewById(R.id.actionsLogViewerContainer)
        controlBar = findViewById(R.id.controlBar)
        customKeyboard = findViewById(R.id.customKeyboard)
        suggestionsBar = findViewById(R.id.suggestionsBar)
        // ringOverlay/accentPopup ahora viven como hermanos de customKeyboard
        // en el FrameLayout raíz: se los inyecta acá, antes de que el
        // usuario pueda tocar nada, para que customKeyboard los controle
        // sin poseerlos.
        customKeyboard.ringOverlay = findViewById<RingGestureView>(R.id.ringOverlay)
        customKeyboard.accentPopup = findViewById<AccentPopupView>(R.id.accentPopup)
        // Paso 7: mismo patrón de inyección — el gesto de drag-abajo (ver
        // CustomKeyboardView.gestureKey()) necesita mostrar su hint en la
        // barra de sugerencias real, no una copia propia.
        customKeyboard.suggestionsBar = suggestionsBar
        tabBarRow = findViewById(R.id.tabBarRow)
        explorerHeaderRow = findViewById(R.id.explorerHeaderRow)
        runButton = findViewById(R.id.runButton)
        folderButton = findViewById(R.id.folderButton)
        searchBarPanel = findViewById(R.id.searchBarPanel)
        searchInputBox = findViewById(R.id.searchInputBox)
        searchMatchCountText = findViewById(R.id.searchMatchCount)
        searchPrevButton = findViewById(R.id.searchPrevButton)
        searchNextButton = findViewById(R.id.searchNextButton)
        searchCloseButton = findViewById(R.id.searchCloseButton)

        selectionBar = findViewById(R.id.selectionBar)
        selectionCountText = findViewById(R.id.selectionCountText)
        selectionRenameButton = findViewById(R.id.selectionRenameButton)
        selectionInfoButton = findViewById(R.id.selectionInfoButton)
        confirmTransferButton = findViewById(R.id.confirmTransferButton)
        cancelTransferButton = findViewById(R.id.cancelTransferButton)
        createFileButtonBig = findViewById(R.id.createFileButtonBig)
        collapseAllButton = findViewById(R.id.collapseAllButton)

        // Botón git (integración GitHub básica): solo desde el explorador.
        // Los comandos git operan sobre currentDir, así que solo tiene
        // sentido abrirlos parado en la carpeta correcta dentro del
        // explorador, nunca desde el editor con un archivo abierto.
        gitButtonExplorer = findViewById(R.id.gitButtonExplorer)
        // Botón de GitHub Actions (runs/jobs/logs/artifacts, ver
        // ActionsPanelController): mismo criterio que gitButtonExplorer,
        // solo desde el explorador, parado en la carpeta correcta.
        actionsButtonExplorer = findViewById(R.id.actionsButtonExplorer)
    }

    /** Construye los 10 controllers en el orden que exige su grafo de
     *  dependencias (ver riesgos de orden de inicialización del informe de
     *  diseño). El ciclo real Pestañas↔Controles se rompe porque
     *  KeyboardRouter solo necesita la LAMBDA syncBottomControlsMargin, no
     *  la instancia de ControlsController — Kotlin captura `this` por
     *  referencia diferida, así que la lambda `{ controlsController.
     *  syncBottomControlsMargin() }` es válida aunque controlsController
     *  todavía no exista en el momento en que se construye KeyboardRouter:
     *  solo debe existir cuando esa lambda se EJECUTE, no cuando se crea. */
    private fun buildControllers() {
        systemInsets = SystemInsetsController(
            rootView = findViewById(R.id.rootLayout),
            contentColumn = contentColumn,
            bottomControlsColumn = bottomControlsColumn,
            bottomControlsRow = bottomControlsRow,
            hasOpenTab = { tabsController.openFiles.isNotEmpty() },
            syncBottomControlsMargin = { controlsController.syncBottomControlsMargin() },
            reapplyLandscapeRowLayoutIfVisible = { controlsController.reapplyLandscapeRowLayoutIfVisible() }
        )

        keyboardRouter = KeyboardRouter(
            controlBar = controlBar,
            customKeyboard = customKeyboard,
            suggestionsBar = suggestionsBar,
            syncMargin = { controlsController.syncBottomControlsMargin() }
        )

        dialogFormHost = DialogFormHost(
            dialogFormContainer = dialogFormContainer,
            layoutInflater = layoutInflater,
            keyboardRouter = keyboardRouter,
            bottomControlsColumn = bottomControlsColumn
        )

        tabsController = TabsController(
            context = this,
            tabRecycler = tabRecycler,
            editorContainer = editorContainer,
            editorEmptyPlaceholder = editorEmptyPlaceholder,
            pathBar = pathBar,
            dialogFormContainer = dialogFormContainer,
            layoutInflater = layoutInflater,
            keyboardRouter = keyboardRouter,
            suggestionsBar = suggestionsBar,
            selectModeActive = { controlBar.selectModeActive },
            onUndoRedoAvailabilityChanged = { canUndo, canRedo -> controlsController.updateUndoRedoButtons(canUndo, canRedo) },
            onPendingCloserChanged = { closer, count -> controlBar.updatePendingCloser(closer, count) },
            onHoldSelectStarted = { controlBar.activateSelectMode() },
            onHoldSelectEnded = { controlBar.deactivateSelectMode() },
            ensureCursorVisibleAboveSuggestions = { editText, obscuredBottomPx -> editText.ensureCursorVisibleAbove(obscuredBottomPx) },
            onEditorTouched = {
                // Si la consola git estaba abierta, el teclado propio
                // seguía apuntando al campo de comandos — tocar el editor
                // es una señal igual de clara de que el usuario quiere
                // escribir ahí.
                tabsController.restoreKeyboardTargetToEditor()
                if (!keyboardRouter.isKeyboardVisible()) {
                    keyboardRouter.showKeyboard()
                    controlBar.setKeyboardHidden(false)
                    controlsController.syncBottomControlsMargin()
                }
            },
            onSwitchingTab = { controlsController.closeSearchBarIfOpen() },
            syncTabsVisibility = { syncEditorVisibilityForTabs() },
            openExplorerPanel = { openExplorerPanel() },
            showNewFileDialog = { newFileDialogController.showNewFileDialog() },
            runOnUiThread = { action -> runOnUiThread(action) }
        )

        explorerController = ExplorerController(
            context = this,
            explorerContainer = explorerContainer,
            mainRow = mainRow,
            explorerResizeHandle = explorerResizeHandle,
            explorerRecycler = explorerRecycler,
            explorerTreeScroll = explorerTreeScroll,
            explorerGitBackground = explorerGitBackground,
            explorerSearchBox = explorerSearchBox,
            selectionBar = selectionBar,
            selectionCountText = selectionCountText,
            selectionRenameButton = selectionRenameButton,
            selectionInfoButton = selectionInfoButton,
            confirmTransferButton = confirmTransferButton,
            cancelTransferButton = cancelTransferButton,
            createFileButtonBig = createFileButtonBig,
            collapseAllButton = collapseAllButton,
            dialogFormHost = dialogFormHost,
            tabs = tabsController,
            newFileDialog = { newFileDialogController },
            routeKeyboardToExplorerSearch = { keyboardRouter.routeTo(PlainEditTextTarget(explorerSearchBox)) },
            onBackStateChanged = { updateBackCallbackState() },
            onFileOpened = {
                explorerContainer.visibility = View.GONE
                updateBackCallbackState()
            }
        )

        consoleController = ConsoleController(
            context = this,
            consoleContainer = consoleContainer,
            contentColumn = contentColumn,
            bottomControlsColumn = bottomControlsColumn,
            layoutInflater = layoutInflater,
            dialogFormHost = dialogFormHost,
            keyboardRouter = keyboardRouter,
            currentDir = { explorerController.currentDir },
            noActiveTab = { tabsController.openFiles.isEmpty() },
            restoreKeyboardTargetToEditor = { tabsController.restoreKeyboardTargetToEditor() },
            syncBottomControlsMargin = { controlsController.syncBottomControlsMargin() },
            runOnUiThread = { action -> runOnUiThread(action) },
            requestPostNotificationsPermission = {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE_POST_NOTIFICATIONS
                )
            },
            onGitActionsRowInflated = { branchButton, branchButtonText, shortcutsButton ->
                // gitActionsRow (ver view_console.xml) solo existe desde el
                // primer showConsole(), así que GitActionsController recién
                // puede construirse acá, no en el resto de buildControllers().
                gitActionsController = GitActionsController(
                    layoutInflater = layoutInflater,
                    anchorContainer = gitActionsOverlay,
                    consoleController = consoleController,
                    keyboardRouter = keyboardRouter,
                    bottomControlsColumn = bottomControlsColumn,
                    syncBottomControlsMargin = { controlsController.syncBottomControlsMargin() },
                    branchButton = branchButton,
                    branchButtonText = branchButtonText,
                    shortcutsButton = shortcutsButton
                ).also { it.setup() }
                // push/pull/checkout/merge/rebase (background) y los
                // comandos lanzados desde botones pueden mover HEAD — se
                // refresca siempre que termina cualquier comando git, en
                // vez de decidir en ConsoleController cuáles lo mueven.
                consoleController.onGitResultApplied = { filesMayHaveChanged ->
                    gitActionsController?.refreshBranchLabel()
                    // Selectivo (a diferencia del refresh de rama de
                    // arriba, que siempre corre): solo checkout/merge/
                    // pull/reset/rm/rebase/stash pop/drop pueden cambiar
                    // archivos en el working tree — ver ConsoleController.
                    // lastCommandAffectsWorkingTree. El explorador siempre
                    // está mirando currentDir(), la misma carpeta sobre la
                    // que operan estos comandos, así que refrescarlo acá
                    // es seguro sin importar en qué pantalla esté el
                    // usuario ahora mismo (refreshExplorer() es un no-op
                    // visual si el explorador no está VISIBLE).
                    if (filesMayHaveChanged) {
                        explorerController.refreshExplorer()
                    }
                }
                // GitActionsController recién existe en este punto (ver
                // comentario arriba) — hasta ahora controlsController.setup()
                // ya corrió con este callback en null (no-op). De aquí en
                // más, cualquier toque al botón "^"/"⌄" reposiciona el
                // dropdown/panel de atajos que haya quedado abierto.
                controlsController.onKeyboardVisibilityChangedByButton = {
                    gitActionsController?.repositionOpenOverlay()
                }
            }
        )

        actionsLogViewerController = ActionsLogViewerController(
            container = actionsLogViewerContainer,
            layoutInflater = layoutInflater,
            onBackPressedDispatcher = onBackPressedDispatcher,
            lifecycleOwner = this
        )

        actionsPanelController = ActionsPanelController(
            layoutInflater = layoutInflater,
            anchorContainer = gitActionsOverlay,
            explorerContainer = explorerContainer,
            logViewer = actionsLogViewerController,
            currentDir = { explorerController.currentDir },
            startCustomTransfer = { toastMessage, onConfirm ->
                explorerController.startCustomTransfer(toastMessage, onConfirm)
            },
            actionsButton = actionsButtonExplorer,
            runOnUiThread = { action -> runOnUiThread(action) }
        ).also { it.setup() }

        htmlPreviewController = HtmlPreviewController(
            htmlPreviewContainer = htmlPreviewContainer,
            layoutInflater = layoutInflater,
            onBackPressedDispatcher = onBackPressedDispatcher,
            lifecycleOwner = this
        )

        runController = RunController(
            context = this,
            tabs = tabsController,
            console = consoleController,
            htmlPreview = htmlPreviewController,
            openActionsPanel = { actionsPanelController.showRunsPanel() }
        )

        controlsController = ControlsController(
            tabs = tabsController,
            runController = runController,
            controlBar = controlBar,
            customKeyboard = customKeyboard,
            suggestionsBar = suggestionsBar,
            keyboardRouter = keyboardRouter,
            runButton = runButton,
            searchBarPanel = searchBarPanel,
            searchInputBox = searchInputBox,
            searchMatchCountText = searchMatchCountText,
            searchPrevButton = searchPrevButton,
            searchNextButton = searchNextButton,
            searchCloseButton = searchCloseButton,
            tabBarRow = tabBarRow,
            pathBar = pathBar,
            contentColumn = contentColumn,
            bottomControlsColumn = bottomControlsColumn,
            bottomControlsRow = bottomControlsRow,
            landscapeSuggestionsSlot = landscapeSuggestionsSlot,
            landscapeKeyboardSlot = landscapeKeyboardSlot,
            landscapeControlsSlot = landscapeControlsSlot,
            hasOpenTab = { tabsController.openFiles.isNotEmpty() },
            setRequestedOrientation = { orientation -> requestedOrientation = orientation },
            getString = { resId -> getString(resId) },
            // Paso 7 (§5): startActivity simple, sin flags/extras — la
            // propia SettingsActivity es stateless entre aperturas (lee
            // KeyboardSettingsStore directo), así que no hay nada que
            // pasarle por Intent.
            openSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
            // Paso 7: "Guardar como" — ver doc de openSaveAsDialog en
            // ControlsController sobre por qué es una lambda indirecta
            // (saveAsDialogController todavía no existe en este punto de
            // onCreate).
            openSaveAsDialog = { saveAsDialogController.show() },
            bottomInsetPx = { systemInsets.bottomInsetPx() }
        )

        newFileDialogController = NewFileDialogController(
            context = this,
            dialogFormHost = dialogFormHost,
            currentDir = { explorerController.currentDir },
            onFileCreated = { _ -> explorerController.refreshExplorer() }
        )

        // Paso 7: "Guardar como" (tecla S del drag-abajo sostenida más
        // allá del umbral, ver KeyboardLayouts.DragAction.SAVE_AS) —
        // conectado acá en vez de en buildControllers() porque necesita
        // dialogFormHost/tabsController, ya construidos para este punto
        // (mismo criterio que newFileDialogController, justo arriba).
        saveAsDialogController = SaveAsDialogController(
            context = this,
            dialogFormHost = dialogFormHost,
            tabs = tabsController
        )

        // folderButton (📁, tabbar, extremo izquierdo): abre/cierra el
        // panel lateral del explorador (Paso 13) — ya no cambia de "modo
        // pantalla", el editor sigue visible a la derecha todo el tiempo.
        folderButton.setOnClickListener { toggleExplorerPanel() }
        gitButtonExplorer.setOnClickListener { consoleController.openGitConsole() }
    }

    /** Paso 13: explorador y editor conviven siempre (panel lateral +
     *  columna derecha), así que ya no hay "modo pantalla" que alternar —
     *  solo dos cosas independientes: (1) si el panel del explorador está
     *  abierto o cerrado (ver toggleExplorerPanel/openExplorerPanel), y (2)
     *  si hay una pestaña activa, que es lo único que de verdad decide la
     *  visibilidad de pathBar/customKeyboard (reemplaza lo que antes hacían
     *  showEditorMode/showExplorerMode combinadas). Llamarla después de
     *  cualquier cambio en openFiles (abrir/cerrar/crear pestaña). */
    private fun syncEditorVisibilityForTabs() {
        val hasOpenTab = tabsController.openFiles.isNotEmpty()
        pathBar.visibility = if (hasOpenTab) View.VISIBLE else View.GONE
        customKeyboard.visibility = if (hasOpenTab) View.VISIBLE else View.GONE
        controlBar.visibility = View.VISIBLE
        controlBar.setKeyboardHidden(!hasOpenTab)
        if (!hasOpenTab) suggestionsBar.hide()
        // Mide y layoutea controlBar a mano ANTES de aplicarle margen: evita
        // un primer frame con la fila visible pero sin sus botones ya
        // posicionados (ver ControlsController.forceControlBarLayout).
        controlsController.forceControlBarLayout()
        controlsController.syncBottomControlsMargin()
        // CustomKeyboardView no tiene estado "abierto/cerrado": a diferencia
        // del IME, es parte fija del layout y está visible todo el tiempo
        // que hay una pestaña activa (antes: "en modo editor").
        controlsController.applyHeaderVisibility(keyboardOpen = hasOpenTab)
        updateBackCallbackState()
    }

    /** Abre/cierra el panel lateral del explorador (folderButton). No toca
     *  pestañas ni editor — eso lo rige syncEditorVisibilityForTabs, por
     *  completo independiente de esto. Backup de seguridad (no guardado
     *  real) al ABRIR: el usuario puede estar a punto de crear/mover/
     *  eliminar archivos desde el explorador mientras el editor activo
     *  tiene cambios sin guardar — mismo backup que antes hacía
     *  showExplorerMode() al "salir" del editor, ahora relevante al abrir
     *  el panel, no al cerrarlo (el editor nunca deja de estar visible). */
    private fun toggleExplorerPanel() {
        if (explorerContainer.visibility == View.VISIBLE) {
            explorerContainer.visibility = View.GONE
        } else {
            tabsController.backupCurrentFile()
            openExplorerPanel()
        }
        updateBackCallbackState()
    }

    /** Fuerza el panel del explorador visible (arranque de la app, y al
     *  vaciarse las pestañas tras cerrar la última — ver
     *  TabsController.closeTabNow) — a diferencia de toggleExplorerPanel,
     *  no alterna: solo abre, nunca cierra. */
    private fun openExplorerPanel() {
        explorerContainer.visibility = View.VISIBLE
        explorerContainer.post { explorerController.applyMaxWidth() }
        explorerController.refreshExplorer()
        updateBackCallbackState()
    }

    /** El back del sistema navega el explorador: sube una carpeta si no
     *  estamos en la raíz. Solo al llegar a la raíz, y si hay pestañas
     *  abiertas, Back vuelve al editor. Si no hay pestañas abiertas, se
     *  deja pasar el back normal (sale de la app). Dentro del editor, Back
     *  siempre sale de la app (ver setupExitGuard). */
    /** Paso 13: ya no hay "modo pantalla" al que volver — el editor nunca
     *  deja de estar visible, así que Back ya no tiene un segundo
     *  significado ("volver al editor") cuando el explorador no consume
     *  nada. Solo intenta explorerController.handleBackPress() mientras el
     *  panel está visible (ver updateBackCallbackState); si no hay nada que
     *  consumir, el callback está deshabilitado y Back cae directo al
     *  guard de salida normal (setupExitGuard). */
    private fun setupBackNavigation() {
        val callback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                // Si el controller no tuvo nada que cancelar/deseleccionar,
                // Back simplemente oculta el panel (igual que el botón 📁,
                // ver toggleExplorerPanel) en vez de caer al guard de
                // salida — decisión explícita del usuario. Solo oculta
                // (GONE directo), nunca hace el trabajo de abrir (backup +
                // refreshExplorer de toggleExplorerPanel/openExplorerPanel)
                // porque Back jamás reabre el panel.
                if (!explorerController.handleBackPress()) {
                    explorerContainer.visibility = View.GONE
                }
                updateBackCallbackState()
            }
        }
        backCallback = callback
        onBackPressedDispatcher.addCallback(this, callback)
        updateBackCallbackState()
    }

    private fun updateBackCallbackState() {
        // Con panel visible, Back siempre tiene algo que hacer ahora:
        // cancelar/deseleccionar si hay algo pendiente, o si no, ocultar
        // el panel (ver handleOnBackPressed arriba) — así que ya no
        // depende de hasSelection()/hasPendingTransfer() para habilitarse.
        backCallback?.isEnabled = explorerContainer.visibility == View.VISIBLE
    }

    /** Timestamp del último Toast de aviso mostrado por el guard de salida,
     *  para la ventana de "press again". */
    private var exitWarningShownAt: Long = 0L

    /** Guard de "Back" dentro del editor (donde setupBackNavigation() deja
     *  su callback deshabilitado): siempre exige un segundo Back dentro de
     *  2s ("press again") para confirmar la salida. La única excepción
     *  real es una operación git en curso (GitTaskBus.isRunning). */
    private fun setupExitGuard() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = System.currentTimeMillis()
                if (now - exitWarningShownAt < 2000) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                } else {
                    exitWarningShownAt = now
                    val message = if (GitTaskBus.isRunning) {
                        "A git operation is in progress. Press again to exit."
                    } else {
                        "Presiona de nuevo para salir"
                    }
                    android.widget.Toast.makeText(this@EditorActivity, message, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_needed_title)
            .setMessage(R.string.permission_needed_body)
            .setPositiveButton(R.string.permission_continue) { _, _ ->
                PermissionHelper.requestStorageAccess(this)
            }
            .setCancelable(false)
            .show()
    }

    override fun onPause() {
        super.onPause()
        // Backup (no guardado real) si el usuario sale de la app, para no
        // perder cambios si Android mata el proceso en background. El
        // archivo real solo se toca con Save.
        if (editorContainer.visibility == View.VISIBLE) {
            tabsController.backupCurrentFile()
        }
    }

    /** Sin esto, el explorador quedaba con la lista cargada en onCreate()
     *  (antes de conceder el permiso de almacenamiento) hasta que el
     *  usuario reentraba a una carpeta manualmente. */
    override fun onResume() {
        super.onResume()
        if (explorerContainer.visibility == View.VISIBLE) {
            explorerController.refreshExplorer()
        }
        // Paso 7 (menú de configuración, §5): cubre el caso real de volver
        // de SettingsActivity (otra Activity, back normal dispara
        // onResume() acá) con overrides nuevos guardados — ver doc de
        // refreshFromSettings(). Barato llamarlo siempre aunque no se haya
        // tocado nada (reconstruye el teclado, no hace I/O nuevo más allá
        // de las lecturas ya baratas de SharedPreferences).
        customKeyboard.refreshFromSettings()
        hideResidualSystemIme()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideResidualSystemIme()
    }

    /** BUG encontrado (IME de sistema residual): todo campo de texto de la
     *  app tiene showSoftInputOnFocus = false, pero eso solo evita que un
     *  NUEVO foco abra el IME — no cubre el caso de volver del background
     *  con un EditText que YA tenía el foco desde antes.
     *
     *  Esta función es una mitigación REACTIVA (oculta el IME si ya
     *  alcanzó a mostrarse), no la causa raíz: desde Android 12 (API 31),
     *  el sistema restaura activamente la visibilidad del IME al recuperar
     *  el foco de una tarea si la última vez esa ventana lo tuvo visible —
     *  eso corre a nivel de WindowManagerService/InputMethodManagerService,
     *  y puede no quedar cubierto por un solo post{} en onResume()/
     *  onWindowFocusChanged(). La causa raíz se resuelve declarando
     *  android:windowSoftInputMode="adjustResize|stateAlwaysHidden" en el
     *  manifest (ver su comentario ahí): ese flag le dice al sistema que
     *  nunca restaure/muestre el IME real para esta ventana, el camino que
     *  AOSP documenta como el esperado para apps con teclado propio. Esta
     *  función se deja como red de seguridad adicional, no se elimina. */
    private fun hideResidualSystemIme() {
        window.decorView.post {
            val focused = currentFocus ?: return@post
            val textView = focused as? TextView
            if (textView != null && textView.showSoftInputOnFocus) return@post
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(focused.windowToken, 0)
        }
    }

    /** AndroidManifest declara configChanges="orientation|screenSize|..."
     *  para esta Activity, así que Android NO la recrea al rotar — hay que
     *  recalcular la visibilidad del header y el layout portrait/landscape
     *  explícitamente en vez de depender de un listener de insets. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (editorContainer.visibility == View.VISIBLE) {
            controlsController.applyHeaderVisibility(keyboardOpen = true)
        }
        controlsController.applyBottomControlsOrientation(newConfig.orientation)
    }
}
