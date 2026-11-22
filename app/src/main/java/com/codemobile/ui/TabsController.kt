package com.codemobile.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codemobile.R
import com.codemobile.editor.CodeEditText
import com.codemobile.editor.EditorPaneView
import com.codemobile.editor.SuggestionsBarView
import com.codemobile.files.BackupManager
import com.codemobile.files.FileRepository
import com.codemobile.files.OpenFile
import com.codemobile.files.colorIndexForExtension
import java.io.File
import java.util.concurrent.Executors

/**
 * Pestañas / ciclo de vida de archivo abierto: openFiles, activeIndex,
 * render del editor activo, backups automáticos (nunca el archivo real,
 * ver BackupManager) y el único camino que SÍ toca el archivo real
 * (persistCurrentFile, llamado solo desde el botón Save). Extraído tal
 * cual de EditorActivity, sin cambiar ninguna regla — incluyendo el bug ya
 * corregido documentado en closeTab (usar backupCurrentFile, no
 * persistCurrentFile, al cerrar una pestaña).
 *
 * El "foco de teclado" (antes controlBar.target/customKeyboard.target
 * tocados directamente en renderActiveEditor) ahora pasa por
 * [keyboardRouter], para no referenciar ControlsController directamente —
 * eso rompe el ciclo Pestañas↔Controles que señala el informe de diseño:
 * Pestañas nunca necesita conocer ControlsController, le basta el router
 * compartido. [onUndoRedoAvailabilityChanged], [onPendingCloserChanged],
 * [onHoldSelectStarted]/[onHoldSelectEnded], [selectModeActive] y
 * [suggestionsBar] son los puntos donde antes se tocaba controlBar/
 * suggestionsBar directo desde renderActiveEditor: siguen siendo
 * implementados por ControlsController, pero inyectados por constructor
 * (lambdas, o la vista misma para el caso de suggestionsBar, que no tiene
 * estado propio de Controles — ver nota en SuggestionsBarView).
 *
 * dialogFormContainer/layoutInflater se reciben directo (no vía
 * DialogFormHost) solo para showBackupStrategyDialog: ese panel no tiene
 * ningún EditText, así que no necesita enrutar el teclado propio ni
 * guardar/restaurar su estado — alcanza con dejarlo tal cual está (mismo
 * comportamiento "no cancelable" que el código original).
 */
class TabsController(
    private val context: Context,
    private val tabRecycler: RecyclerView,
    private val editorContainer: FrameLayout,
    // Placeholder de "sin pestañas abiertas" (ver closeTabNow/
    // renderActiveEditor): vista hermana de editorContainer, no hijo —
    // renderActiveEditor hace editorContainer.removeAllViews() en cada
    // cambio de pestaña, así que un TextView puesto ahí adentro se
    // borraría solo. Visibilidad siempre inversa a la de editorContainer.
    private val editorEmptyPlaceholder: TextView,
    private val pathBar: TextView,
    private val dialogFormContainer: FrameLayout,
    private val layoutInflater: LayoutInflater,
    private val keyboardRouter: KeyboardRouter,
    private val suggestionsBar: SuggestionsBarView,
    private val selectModeActive: () -> Boolean,
    private val onUndoRedoAvailabilityChanged: (canUndo: Boolean, canRedo: Boolean) -> Unit,
    private val onPendingCloserChanged: (closer: Char?, count: Int) -> Unit,
    private val onHoldSelectStarted: () -> Unit,
    private val onHoldSelectEnded: () -> Unit,
    private val ensureCursorVisibleAboveSuggestions: (CodeEditText, Int) -> Unit,
    private val onEditorTouched: () -> Unit,
    private val onSwitchingTab: () -> Unit,
    // Paso 13: ya no hay "modo pantalla" que cambiar al abrir/cerrar
    // pestañas (explorador y editor conviven siempre) — syncTabsVisibility
    // reemplaza a las antiguas showEditorMode/showExplorerMode, y solo
    // sincroniza pathBar/customKeyboard/controlBar según haya o no una
    // pestaña activa. openExplorerPanel (antes parte de showExplorerMode)
    // sigue siendo necesaria por separado: al vaciarse las pestañas
    // conviene abrir el panel automáticamente si estaba cerrado.
    private val syncTabsVisibility: () -> Unit,
    private val openExplorerPanel: () -> Unit,
    private val showNewFileDialog: () -> Unit,
    // Pendiente reportado (apertura lenta de archivos grandes): ver doc de
    // completeLoadingIfNeeded. Mismo patrón que runOnUiThread en
    // ConsoleController, no un parámetro nuevo inventado para esta clase.
    private val runOnUiThread: (() -> Unit) -> Unit
) {

    val openFiles = mutableListOf<OpenFile>()
    var activeIndex: Int = 0
        private set

    private lateinit var tabAdapter: TabBarAdapter

    // Un solo hilo de IO dedicado a completar la lectura de archivos
    // grandes en background (ver completeLoadingIfNeeded) — mismo patrón
    // que ExecutionDispatcher.executor, no Dispatchers.IO de coroutines
    // porque esta clase no usa coroutines en ningún otro lado.
    private val fileLoadExecutor = Executors.newSingleThreadExecutor()

    fun setup() {
        tabAdapter = TabBarAdapter(
            files = openFiles,
            onTabClick = { index -> switchToTab(index) },
            onAddClick = { showNewFileDialog() },
            onTabClose = { index -> closeTab(index) }
        )
        tabRecycler.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        tabRecycler.adapter = tabAdapter
    }

    fun notifyTabsChanged() {
        tabAdapter.notifyDataSetChanged()
    }

    fun activeCodeEditText(): CodeEditText? =
        (editorContainer.getChildAt(0) as? EditorPaneView)?.codeEditText

    /** Índice en openFiles de la pestaña cuyo archivo es exactamente
     *  [target] (igualdad exacta), o -1 si no hay ninguna. */
    fun indexOfOpenTab(target: File): Int {
        val targetPath = target.absolutePath
        return openFiles.indexOfFirst { it.file?.absolutePath == targetPath }
    }

    /** Busca si [target] (archivo o carpeta) tiene alguna pestaña abierta
     *  que apunte a él directamente, o a un archivo DENTRO de él si es
     *  carpeta. */
    fun findOpenTabInside(target: File): OpenFile? {
        val targetPath = target.absolutePath
        return openFiles.firstOrNull { of ->
            val currentPath = of.file?.absolutePath ?: return@firstOrNull false
            currentPath == targetPath || currentPath.startsWith(targetPath + File.separator)
        }
    }

    /** Abre [file] en una pestaña nueva, o activa la existente si ya está
     *  abierto. Usado por ExplorerController al tocar un archivo. */
    fun openOrFocus(file: File) {
        val existingIndex = indexOfOpenTab(file)
        if (existingIndex != -1) {
            activeIndex = existingIndex
            tabAdapter.setActiveIndex(activeIndex)
            tabAdapter.notifyDataSetChanged()
            renderActiveEditor()
            syncTabsVisibility()
            return
        }
        addNewTab(OpenFile.fromFile(file))
    }

    /** Actualiza la referencia de cualquier pestaña abierta cuyo File (o,
     *  para una CARPETA renombrada, cuyo File esté DENTRO de ella) apuntaba
     *  a la ruta vieja. */
    fun syncOpenTabsAfterRename(oldFile: File, renamedFile: File) {
        val oldPath = oldFile.absolutePath
        for (of in openFiles) {
            val currentFile = of.file ?: continue
            val currentPath = currentFile.absolutePath
            if (currentPath == oldPath) {
                of.file = renamedFile
                of.displayName = renamedFile.name.substringBeforeLast('.', renamedFile.name)
                of.extension = renamedFile.name.substringAfterLast('.', "")
            } else if (currentPath.startsWith(oldPath + File.separator)) {
                val relative = currentPath.removePrefix(oldPath)
                of.file = File(renamedFile.absolutePath + relative)
            }
        }
        if (openFiles.isNotEmpty()) {
            pathBar.text = openFiles[activeIndex].file?.absolutePath
                ?: "(sin guardar) ${openFiles[activeIndex].displayName}.${openFiles[activeIndex].extension}"
        }
    }

    /** Cierra la pestaña en [index] (botón "×", solo visible en la pestaña
     *  activa). Si no queda ninguna pestaña abierta, vuelve al explorador.
     *  BUG ya corregido (ver doc original): usa backupCurrentFile(), nunca
     *  persistCurrentFile() — cerrar sin guardar antes no debe tocar el
     *  archivo real.
     *
     *  Si el archivo en [index] tiene cambios sin guardar (isDirty), pide
     *  confirmación antes de cerrar (Guardar/Descartar/Cancelar) en vez de
     *  descartarlos en silencio como antes. Si no está sucio, cierra
     *  directo sin diálogo — mismo comportamiento de siempre. Se llama
     *  primero saveCurrentEditorStateIntoModel() (si [index] es la pestaña
     *  activa) para que isDirty refleje el estado REAL del editor en
     *  pantalla, no el que tenía openFiles[index] desde la última vez que
     *  se sincronizó.
     *
     *  A propósito, NO se llama backupCurrentFile() acá (a diferencia de
     *  antes de este cambio): "Descartar" no debe dejar ningún .bak
     *  escrito — ver showUnsavedChangesDialog, que borra el backup
     *  existente (si lo había, de una sesión anterior) en ambas ramas,
     *  Guardar y Descartar. */
    fun closeTab(index: Int) {
        if (index !in openFiles.indices) return
        if (index == activeIndex) saveCurrentEditorStateIntoModel()
        val openFile = openFiles[index]
        if (openFile.isDirty) {
            showUnsavedChangesDialog(openFile, index)
            return
        }
        closeTabNow(index)
    }

    /** Diálogo de confirmación con cambios sin guardar: Guardar/Descartar,
     *  más Cancelar para poder arrepentirse y no cerrar en absoluto.
     *  AlertDialog nativo, no vía DialogFormHost/dialogFormContainer: a
     *  diferencia de
     *  showDialogForm (que migró a vista propia porque tapaba el teclado
     *  propio detrás de una ventana separada), este diálogo no tiene ningún
     *  EditText ni necesita el teclado propio en absoluto — mismo criterio
     *  que ya usa checkRecoverableBackup() más abajo, con AlertDialog.Builder
     *  normal.
     *
     *  Guardar: FileRepository.save ya borra el .bak internamente (ver su
     *  propio código — "al guardar con Save, el archivo real ya queda al
     *  día y el backup deja de hacer falta"), así que no hace falta nada
     *  extra acá. Descartar: borra el .bak si existía (de esta sesión o de
     *  una anterior), en vez de dejarlo como antes de este cambio; sin
     *  esto, un "Descartar" podía dejar un backup desincronizado que luego
     *  checkRecoverableBackup() ofrecería "recuperar" como si fuera un
     *  cambio real sin guardar, pese a que ya se decidió descartarlo. */
    private fun showUnsavedChangesDialog(openFile: OpenFile, index: Int) {
        AlertDialog.Builder(context)
            .setTitle("Unsaved changes")
            .setMessage("\"${openFile.displayName}.${openFile.extension}\" has unsaved changes. What do you want to do?")
            .setPositiveButton("Save") { _, _ ->
                if (openFile.file != null) {
                    FileRepository.save(context, openFile)
                }
                closeTabNow(index)
            }
            .setNegativeButton("Discard") { _, _ ->
                val file = openFile.file
                if (file != null) {
                    val repoRoot = BackupManager.findRepoRoot(file)
                    val strategy = repoRoot?.let { BackupManager.savedStrategyFor(context, it) }
                    BackupManager.deleteBackup(context, file, repoRoot, strategy)
                }
                closeTabNow(index)
            }
            .setNeutralButton("Cancel", null)
            .setCancelable(true)
            .show()
    }

    /** Cierre real de la pestaña, sin ningún chequeo de isDirty (ya
     *  resuelto por closeTab/showUnsavedChangesDialog antes de llegar
     *  acá) — misma lógica de siempre, solo renombrada para poder
     *  invocarla después de confirmar Guardar/Descartar. */
    private fun closeTabNow(index: Int) {
        if (index !in openFiles.indices) return
        openFiles.removeAt(index)
        if (openFiles.isEmpty()) {
            activeIndex = 0
            tabAdapter.notifyDataSetChanged()
            editorContainer.removeAllViews()
            editorContainer.visibility = View.GONE
            editorEmptyPlaceholder.visibility = View.VISIBLE
            syncTabsVisibility()
            openExplorerPanel()
            return
        }
        activeIndex = when {
            index < activeIndex -> activeIndex - 1
            else -> activeIndex.coerceAtMost(openFiles.size - 1)
        }
        tabAdapter.setActiveIndex(activeIndex)
        tabAdapter.notifyDataSetChanged()
        renderActiveEditor()
    }

    fun addNewTab(openFile: OpenFile) {
        openFiles.add(openFile)
        activeIndex = openFiles.size - 1
        tabAdapter.setActiveIndex(activeIndex)
        tabAdapter.notifyDataSetChanged()
        renderActiveEditor()
        syncTabsVisibility()
        ensureBackupStrategyThenCheckRecovery(openFile)
        if (openFile.isLoadingFull) completeLoadingIfNeeded(openFile)
    }

    /** Pendiente reportado (apertura lenta de archivos grandes): openFile
     *  ya llegó con las primeras OpenFile.INITIAL_LOAD_LINES líneas (ver
     *  OpenFile.fromFile) y el resto todavía no se leyó. Completa esa
     *  lectura en fileLoadExecutor (fuera del hilo principal) y, al
     *  terminar, vuelve al hilo de UI para aplicar el contenido completo —
     *  SOLO si openFile.isLoadingFull sigue siendo true en ese momento: si
     *  en el medio el usuario recuperó un backup (ver checkRecoverableBackup,
     *  que pone isLoadingFull=false a mano) o por cualquier otra razón ya
     *  se completó, esta lectura tardía no debe pisar ese contenido más
     *  nuevo con el del archivo en disco.
     *
     *  No usa file.readText() para el resto: debe seguir la misma
     *  normalización de saltos de línea que readInitialChunk (LF, nunca
     *  CRLF/CR crudo) para que el documento final no quede con dos
     *  convenciones de salto de línea mezcladas en un mismo archivo —el
     *  editor entero asume \n en todos sus cálculos de línea, ningún otro
     *  punto del código maneja CRLF. */
    private fun completeLoadingIfNeeded(openFile: OpenFile) {
        val file = openFile.file ?: return
        fileLoadExecutor.execute {
            val full = runCatching { file.bufferedReader().useLines { it.joinToString("\n") } }.getOrNull()
            runOnUiThread {
                if (!openFile.isLoadingFull || full == null) return@runOnUiThread
                openFile.content = StringBuilder(full)
                openFile.savedContent = full
                openFile.isLoadingFull = false
                if (openFiles.getOrNull(activeIndex) === openFile) {
                    renderActiveEditor()
                }
            }
        }
    }

    private fun ensureBackupStrategyThenCheckRecovery(openFile: OpenFile) {
        val file = openFile.file ?: return
        val repoRoot = BackupManager.findRepoRoot(file)
        if (repoRoot == null) {
            checkRecoverableBackup(openFile, repoRoot = null)
            return
        }
        if (BackupManager.savedStrategyFor(context, repoRoot) != null) {
            checkRecoverableBackup(openFile, repoRoot)
            return
        }
        showBackupStrategyDialog(repoRoot) {
            checkRecoverableBackup(openFile, repoRoot)
        }
    }

    /** Diálogo de elección de estrategia de backup (una sola vez por
     *  repo): las 3 opciones disponibles. No cancelable: no hay botón
     *  Cancelar ni se instala un listener que cierre al tocar afuera — si
     *  no se elige nada, se vuelve a preguntar la próxima vez que haga
     *  falta. */
    private fun showBackupStrategyDialog(repoRoot: File, onChosen: () -> Unit) {
        dialogFormContainer.removeAllViews()
        val card = layoutInflater.inflate(R.layout.dialog_backup_strategy, dialogFormContainer, false)
        card.findViewById<TextView>(R.id.backupStrategyMessage).text = context.getString(
            R.string.backup_strategy_message, repoRoot.name
        )

        fun choose(strategy: BackupManager.Strategy) {
            BackupManager.saveStrategyFor(context, repoRoot, strategy)
            dialogFormContainer.visibility = View.GONE
            dialogFormContainer.removeAllViews()
            onChosen()
        }
        card.findViewById<TextView>(R.id.backupStrategyOptionInRepo).setOnClickListener {
            choose(BackupManager.Strategy.IN_REPO_GITIGNORE)
        }
        card.findViewById<TextView>(R.id.backupStrategyOptionAppPrivate).setOnClickListener {
            choose(BackupManager.Strategy.APP_PRIVATE)
        }
        card.findViewById<TextView>(R.id.backupStrategyOptionVisibleGlobal).setOnClickListener {
            choose(BackupManager.Strategy.VISIBLE_GLOBAL)
        }

        // A propósito, sin listener en dialogFormContainer que cierre al
        // tocar "afuera" — ver doc de la función.
        dialogFormContainer.setOnClickListener(null)
        card.isClickable = true
        dialogFormContainer.addView(card)
        dialogFormContainer.visibility = View.VISIBLE
    }

    /** Segundo paso del flujo de backups: si hay un .bak más nuevo que el
     *  archivo real, ofrece recuperarlo. */
    private fun checkRecoverableBackup(openFile: OpenFile, repoRoot: File?) {
        val file = openFile.file ?: return
        val recovered = BackupManager.findRecoverableBackup(context, file, repoRoot) ?: return
        AlertDialog.Builder(context)
            .setTitle("Unsaved changes found")
            .setMessage(
                "There's an automatic backup of \"${openFile.displayName}\" that's " +
                    "newer than the saved file (probably from an interrupted " +
                    "earlier session). Do you want to recover it?"
            )
            .setPositiveButton("Recover") { _, _ ->
                openFile.content = StringBuilder(recovered)
                openFile.cursorPosition = openFile.cursorPosition.coerceIn(0, recovered.length)
                openFile.isDirty = true
                // Si la lectura en background de un archivo grande (ver
                // completeLoadingIfNeeded) todavía estuviera en vuelo, debe
                // cancelarse acá: sin esto, al terminar pisaría este backup
                // recién recuperado con el contenido del archivo real en
                // disco, perdiendo la recuperación en silencio.
                openFile.isLoadingFull = false
                if (openFiles.getOrNull(activeIndex) === openFile) {
                    renderActiveEditor()
                }
            }
            .setNegativeButton("Discard", null)
            .setCancelable(false)
            .show()
    }

    /** Escribe el backup automático del archivo activo. Nunca toca el
     *  archivo real; ver BackupManager. */
    fun backupCurrentFile() {
        saveCurrentEditorStateIntoModel()
        val of = openFiles.getOrNull(activeIndex) ?: return
        val file = of.file ?: return
        // Pendiente reportado (apertura lenta de archivos grandes): con
        // isLoadingFull, of.content todavía es solo el primer chunk (ver
        // OpenFile.isLoadingFull) — escribir eso como backup dejaría un
        // .bak truncado que checkRecoverableBackup podría ofrecer
        // "recuperar" más adelante, perdiendo el resto del archivo real.
        // En la práctica of.isDirty ya seguiría siendo false mientras
        // carga (el teclado no se enruta al editor, ver renderActiveEditor:
        // sin eso no hay edición posible), pero esta guarda es explícita
        // para no depender de ese efecto indirecto.
        if (of.isLoadingFull) return
        if (!of.isDirty) return
        val repoRoot = BackupManager.findRepoRoot(file)
        val strategy = repoRoot?.let { BackupManager.savedStrategyFor(context, it) }
        if (repoRoot != null && strategy == null) return
        BackupManager.writeBackup(context, file, repoRoot, strategy, of.content.toString())
    }

    fun switchToTab(index: Int) {
        if (index !in openFiles.indices) return
        backupCurrentFile()
        onSwitchingTab()
        activeIndex = index
        tabAdapter.setActiveIndex(activeIndex)
        renderActiveEditor()
    }

    private fun saveCurrentEditorStateIntoModel() {
        val pane = editorContainer.getChildAt(0) as? EditorPaneView ?: return
        val of = openFiles.getOrNull(activeIndex) ?: return
        of.content = StringBuilder(pane.codeEditText.text?.toString() ?: "")
        of.cursorPosition = pane.codeEditText.selectionStart
    }

    /** Único camino que toca el archivo real: se llama solo desde el botón
     *  Save. Todo lo demás pasa por backupCurrentFile(), que nunca lo toca.
     *
     *  Pendiente reportado (apertura lenta de archivos grandes): con
     *  isLoadingFull, saveCurrentEditorStateIntoModel() leería el EditText
     *  real en pantalla, que todavía solo muestra el primer chunk (ver
     *  OpenFile.isLoadingFull) — sin este corte ANTES de llamarla, of.content
     *  (y de ahí el archivo real en disco) quedaría truncado al chunk
     *  parcial. En la práctica el botón Save no debería ni poder dispararse
     *  acá (el teclado no está enrutado al editor mientras carga, ver
     *  renderActiveEditor), pero esta guarda es explícita por si
     *  persistCurrentFile se llamara desde otro camino en el futuro. */
    fun persistCurrentFile() {
        val loadingFile = openFiles.getOrNull(activeIndex)
        if (loadingFile?.isLoadingFull == true) return
        saveCurrentEditorStateIntoModel()
        val of = openFiles.getOrNull(activeIndex) ?: return
        if (of.file != null) {
            FileRepository.save(context, of)
            // FileRepository.save actualiza of.savedContent, pero sin pasar
            // por ninguna edición de texto del EditText — nada dispara
            // onTextSettled acá, así que hay que refrescar a mano la franja
            // de diff del margen derecho (ver EditorPaneView.
            // refreshDiffMargin) para que deje de marcar como "sin guardar"
            // lo que se acaba de guardar.
            (editorContainer.getChildAt(0) as? EditorPaneView)?.refreshDiffMargin()
        }
    }

    /** Carpeta del archivo activo, para que "Guardar como" (ver
     *  SaveAsDialogController) proponga guardar junto al archivo actual,
     *  no en explorerController.currentDir (que puede ser una carpeta
     *  distinta a la que el usuario simplemente esté navegando en el
     *  explorador). Sin archivo activo o sin pestañas abiertas, cae al
     *  almacenamiento externo primario — mismo fallback razonable que usa
     *  el explorador al arrancar. */
    fun activeFileDir(): File =
        openFiles.getOrNull(activeIndex)?.file?.parentFile
            ?: android.os.Environment.getExternalStorageDirectory()

    /** Nombre propuesto para "Guardar como": el del archivo activo con su
     *  extensión, o "sin título.ext" si todavía no tiene archivo real. */
    fun activeFileName(): String {
        val of = openFiles.getOrNull(activeIndex) ?: return ""
        return if (of.extension.isNotEmpty()) "${of.displayName}.${of.extension}" else of.displayName
    }

    /** Tecla S del drag-abajo, sostenida más allá del umbral (Paso 7, ver
     *  KeyboardLayouts.HOLD_SWITCHES_TO/DragAction.SAVE_AS): guarda el
     *  archivo ACTIVO en una ruta nueva, vía FileRepository.saveAs. A
     *  diferencia de "Nuevo archivo" (NewFileDialogController, que crea un
     *  archivo vacío y lo abre en una pestaña NUEVA), esto reusa la MISMA
     *  pestaña y su contenido actual — openFile.file/displayName/extension
     *  quedan apuntando al archivo nuevo tras esto, el viejo archivo (si
     *  existía) queda intacto en disco, sin relación con esta pestaña de
     *  ahora en más.
     *
     *  Devuelve el File resultante en éxito, o null si no hay pestaña
     *  activa. [dir]/[fileName] ya vienen validados por el diálogo que
     *  llama a esto (ver SaveAsDialogController) — createFile reutiliza
     *  silenciosamente un archivo existente de ese nombre si ya está ahí
     *  (mismo criterio documentado en FileRepository.createFile: correcto
     *  para "guardar como", que sobreescribe a propósito). */
    fun saveActiveFileAs(dir: File, fileName: String): File? {
        saveCurrentEditorStateIntoModel()
        val of = openFiles.getOrNull(activeIndex) ?: return null
        val result = FileRepository.saveAs(context, of, dir, fileName)
        of.colorIndex = colorIndexForExtension(of.extension)
        (editorContainer.getChildAt(0) as? EditorPaneView)?.let { pane ->
            pane.codeEditText.lang = FileRepository.langFor(of.extension)
            pane.codeEditText.openFile = of
            pane.codeEditText.highlightAll()
            pane.refreshDiffMargin()
        }
        pathBar.text = of.file?.absolutePath
        notifyTabsChanged()
        return result
    }

    /** Fija/quita la zona muerta inferior de [editor] según orientación (ver
     *  doc de clase de SuggestionsBarView y de CodeEditText.clampTapY): en
     *  portrait, paddingBottom = suggestionsBar.expectedHeightPx() —
     *  permanente, exista o no una sugerencia visible en ese momento, para
     *  que aparecer/desaparecer la barra nunca mueva el scroll máximo ni el
     *  área táctil del editor. En landscape no hace falta (ver doc de
     *  clase de SuggestionsBarView: landscapeSuggestionsSlot ya reserva su
     *  propio ancho de columna fijo, ajeno al editor), así que se deja en 0.
     *  Público: también lo llama ControlsController.applyBottomControlsOrientation
     *  sobre el editor activo al rotar, porque el CodeEditText no se recrea
     *  al cambiar de orientación (solo se reorganiza el layout de
     *  controles), así que el padding fijado aquí al crear la pestaña
     *  quedaría desactualizado tras rotar si nadie lo reaplica. */
    fun applySuggestionsDeadzone(editor: CodeEditText) {
        val landscape = context.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val deadzonePx = if (landscape) 0 else suggestionsBar.expectedHeightPx()
        editor.setPadding(editor.paddingLeft, editor.paddingTop, editor.paddingRight, deadzonePx)
    }

    fun renderActiveEditor() {
        val of = openFiles.getOrNull(activeIndex) ?: return
        editorEmptyPlaceholder.visibility = View.GONE
        editorContainer.visibility = View.VISIBLE
        editorContainer.removeAllViews()

        val pane = EditorPaneView(context)
        applySuggestionsDeadzone(pane.codeEditText)
        pane.codeEditText.lang = FileRepository.langFor(of.extension)
        pane.codeEditText.openFile = of
        pane.codeEditText.setText(of.content.toString())
        pane.codeEditText.setSelection(of.cursorPosition.coerceIn(0, of.content.length))
        pane.codeEditText.highlightAll()
        pane.codeEditText.resetUndoHistory()
        pane.codeEditText.onUndoRedoAvailabilityChanged = { canUndo, canRedo ->
            onUndoRedoAvailabilityChanged(canUndo, canRedo)
        }
        pane.codeEditText.onPendingCloserChanged = { closer, count ->
            onPendingCloserChanged(closer, count)
        }
        pane.codeEditText.onHoldSelectStarted = { onHoldSelectStarted() }
        pane.codeEditText.onHoldSelectEnded = { onHoldSelectEnded() }
        pane.codeEditText.onEditorTouched = { onEditorTouched() }
        pane.codeEditText.onSuggestionsChanged = { suggestions ->
            suggestionsBar.show(suggestions)
            if (suggestions.isNotEmpty() &&
                context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            ) {
                ensureCursorVisibleAboveSuggestions(pane.codeEditText, suggestionsBar.expectedHeightPx())
            }
        }
        suggestionsBar.onSuggestionPicked = { word -> pane.codeEditText.replaceCurrentPrefix(word) }

        editorContainer.addView(
            pane,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        pane.codeEditText.selectModeActive = selectModeActive()
        // Pendiente reportado (apertura lenta de archivos grandes): con
        // isLoadingFull (ver su doc en OpenFile), solo las primeras líneas
        // están cargadas — el resto llega en background (ver
        // completeLoadingIfNeeded). No enrutar el teclado propio hacia este
        // editor evita que el usuario pueda escribir sobre un documento
        // todavía incompleto (sin tocar isEnabled/isFocusable del EditText,
        // que podría tener efectos secundarios no previstos sobre scroll o
        // selección) — en cuanto la carga completa termine,
        // completeLoadingIfNeeded vuelve a llamar renderActiveEditor, que
        // esta vez sí enruta el teclado con normalidad.
        if (!of.isLoadingFull) {
            keyboardRouter.setTargetOnly(pane.codeEditText)
        }
        pane.codeEditText.notifyPendingCloser()

        pathBar.text = if (of.isLoadingFull) {
            // isLoadingFull solo lo activa OpenFile.fromFile(file: File), así
            // que of.file nunca es null en esta rama.
            "Loading file… ${of.file!!.absolutePath}"
        } else {
            of.file?.absolutePath ?: "(sin guardar) ${of.displayName}.${of.extension}"
        }
    }

    /** Vuelve a apuntar el teclado propio al editor de código activo. Si no
     *  hay ninguna pestaña abierta todavía, target queda en null hasta que
     *  renderActiveEditor lo asigne al abrir o crear un archivo. */
    fun restoreKeyboardTargetToEditor() {
        val pane = editorContainer.getChildAt(0) as? EditorPaneView
        keyboardRouter.setTargetOnly(pane?.codeEditText)
    }
}
