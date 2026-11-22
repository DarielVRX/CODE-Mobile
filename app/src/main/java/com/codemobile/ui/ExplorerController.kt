package com.codemobile.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codemobile.R
import com.codemobile.files.FileRepository
import java.io.File

/**
 * Explorador de archivos: árbol con múltiples carpetas expandidas a la vez
 * (Paso 13 — antes navegación de un solo nivel, entrando/saliendo de
 * carpeta), buscador recursivo, selección múltiple, mover/copiar (modo
 * "elegir destino"), renombrar, info, eliminar, crear carpeta.
 *
 * Modelo del árbol (Paso 13): [expandedDirs] es el conjunto de carpetas
 * actualmente desplegadas (puede haber varias ramas abiertas a la vez,
 * sin relación de ancestro entre ellas). [currentDir] (se mantiene el
 * nombre por compatibilidad con los consumidores externos — ConsoleController/
 * ActionsPanelController/NewFileDialogController siguen leyéndolo igual)
 * pasa de ser "la carpeta en la que se está parado" a ser la carpeta
 * ACTIVA del árbol: la que usan crear archivo/comandos git/búsqueda
 * recursiva, independiente de cuáles carpetas están expandidas. Null =
 * ninguna seleccionada, y currentDir cae al fallback de rootDir() (ver su
 * getter). Gesto de un tap sobre una carpeta (ver onExplorerItemClick):
 * colapsada → se expande Y se selecciona; expandida pero no era la activa
 * → solo se selecciona (no se colapsa); expandida y YA era la activa →
 * se colapsa (la selección NO cambia — contraer no implica deseleccionar,
 * decisión explícita: "se queda en la que se acaba de contraer").
 *
 * El modo "elegir destino" (pickDestination) ya no navega: con el árbol
 * visible, el destino se elige con un tap directo sobre cualquier carpeta
 * ya desplegada (puede ser una rama distinta de donde están los archivos
 * de origen, visibles ambas a la vez) — ver onExplorerItemClick.
 *
 * Extraído tal cual de EditorActivity originalmente, sin cambiar ninguna
 * regla — incluyendo las dos correcciones que el código original ya
 * documenta (el bloqueo por findOpenTabInside antes de mover/eliminar, y
 * el bug resuelto de "Back durante pickDestination" navegando carpetas en
 * vez de cancelar de entrada).
 *
 * Depende de [tabs] por referencia directa (R, ver informe de diseño):
 * Explorador siempre necesita consultar/notificar a Pestañas (archivo ya
 * abierto, bloqueo de mover/eliminar, sincronizar tras renombrar), nunca
 * al revés, así que no hay ciclo. [newFileDialog] se reusa para el menú
 * "+": Nuevo archivo / Nueva carpeta ya vivía conceptualmente junto al
 * explorador aunque el diálogo en sí es casi sin estado. Se recibe como
 * lambda (no como instancia directa) porque NewFileDialogController a su
 * vez necesita leer currentDir de ESTE controller — igual que el ciclo
 * KeyboardRouter↔ControlsController, se rompe difiriendo la resolución
 * hasta el momento de uso, no el de construcción.
 */
class ExplorerController(
    private val context: Context,
    private val explorerContainer: View,
    // Paso 13: fila horizontal explorador+editor (ver activity_editor.xml/
    // mainRow) — su ancho total es la referencia para el tope máximo del
    // panel (ver setupExplorerResizeHandle/explorerMaxWidthPx), mismo rol
    // que contentColumn.height en ConsoleController.consoleMaxHeightPx.
    private val mainRow: View,
    // Divisor de arrastre (ver activity_editor.xml/explorerResizeHandle):
    // mismo patrón que consoleResizeHandle en ConsoleController, adaptado
    // de alto/rawY a ancho/rawX.
    private val explorerResizeHandle: View,
    private val explorerRecycler: RecyclerView,
    // Viewport real del árbol (ver activity_editor.xml) — su ancho medido
    // es la referencia correcta para "ancho visible del panel" (ver
    // FileExplorerAdapter.panelWidthPx/displayName), a diferencia de
    // explorerRecycler, que es wrap_content y crece libremente DENTRO de
    // este scroll cuando el contenido lo requiere.
    private val explorerTreeScroll: View,
    private val explorerGitBackground: View,
    private val explorerSearchBox: EditText,
    private val selectionBar: LinearLayout,
    private val selectionCountText: TextView,
    private val selectionRenameButton: TextView,
    private val selectionInfoButton: TextView,
    private val confirmTransferButton: TextView,
    private val cancelTransferButton: TextView,
    private val createFileButtonBig: TextView,
    private val collapseAllButton: TextView,
    private val dialogFormHost: DialogFormHost,
    private val tabs: TabsController,
    private val newFileDialog: () -> NewFileDialogController,
    private val routeKeyboardToExplorerSearch: () -> Unit,
    private val onBackStateChanged: () -> Unit,
    // Pendiente reportado: el panel debía cerrarse inmediatamente al abrir
    // un archivo (antes quedaba abierto, el usuario tenía que cerrarlo a
    // mano con folderButton/Back) — ver onExplorerItemClick, único call
    // site. No se llama en la rama de carpeta (toggleExplorerNode): solo
    // abrir un ARCHIVO cierra el panel.
    private val onFileOpened: () -> Unit
) {

    /** Qué pasa al confirmar (✓) la carpeta elegida en modo "elegir
     *  destino". [Files] es el camino original: mover/copiar un set de
     *  archivos YA EXISTENTES con FileRepository.move/copy (ver
     *  enterPickDestinationMode/startTransferOf). [CustomAction] es la
     *  generalización para un caso donde no hay ningún archivo todavía al
     *  momento de elegir destino — ej. ActionsPanelController necesita
     *  "elegí la carpeta, y YO (no FileRepository) decido qué hacer con
     *  ella" para arrancar una descarga directo ahí, en vez de descargar
     *  primero a un temporal y recién después mover. El mensaje de aviso
     *  para este caso lo define el caller (ver startCustomTransfer), ya
     *  que "mover"/"copiar" no describe lo que de verdad va a pasar. */
    private sealed class PendingTransfer {
        data class Files(val files: Set<File>, val isMove: Boolean) : PendingTransfer()
        data class CustomAction(val onConfirm: (destDir: File) -> Unit) : PendingTransfer()
    }

    /** Carpetas actualmente desplegadas en el árbol (ver doc de clase) —
     *  puede haber varias ramas abiertas a la vez, sin relación entre
     *  ellas. La raíz siempre está implícitamente "expandida" (sus hijos
     *  son el primer nivel listado) sin necesidad de estar en este set. */
    private val expandedDirs = mutableSetOf<File>()

    /** Carpeta activa del árbol (ver doc de clase) — null hasta el primer
     *  tap sobre una carpeta. Privado: fuera de esta clase se usa
     *  [currentDir], que resuelve el fallback a rootDir(). */
    private var selectedDir: File? = null

    /** API pública hacia ConsoleController/ActionsPanelController/
     *  NewFileDialogController (ver doc de clase): la carpeta activa, o
     *  rootDir() si ninguna fue seleccionada todavía. Nunca null, mismo
     *  contrato que antes de este cambio. */
    val currentDir: File
        get() = selectedDir ?: FileRepository.rootDir()

    private val selectedFiles = mutableSetOf<File>()
    private var pendingTransfer: PendingTransfer? = null

    private lateinit var explorerAdapter: FileExplorerAdapter

    // Debounce de la búsqueda recursiva (ver afterTextChanged más abajo):
    // searchRecursive es I/O de disco, sin filtrar carpetas pesadas (.git,
    // node_modules, build, etc.) — lanzarlo en CADA letra tecleada resultaba
    // perceptiblemente lento. searchRunnable se cancela y reemplaza en cada
    // pulsación, así que una letra nueva mata la búsqueda en cola de la
    // letra anterior en vez de encolarlas una tras otra; solo corre la de
    // la ÚLTIMA letra, tras SEARCH_DEBOUNCE_MS sin que se siga escribiendo.
    // La búsqueda que efectivamente dispara corre en un hilo de fondo (ver
    // refreshExplorer), así que ya no bloquea el hilo principal mientras
    // dura. No filtra carpetas pesadas — eso queda fuera de este fix.
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private val SEARCH_DEBOUNCE_MS = 200L

    fun setup() {
        explorerAdapter = FileExplorerAdapter(
            items = emptyList(),
            onItemClick = { file -> onExplorerItemClick(file) },
            onItemLongClick = { file -> onExplorerItemLongClick(file) },
            panelWidthPx = { explorerTreeScroll.width }
        )
        explorerRecycler.layoutManager = LinearLayoutManager(context)
        explorerRecycler.adapter = explorerAdapter
        setupExplorerResizeHandle()

        explorerSearchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                val runnable = Runnable { refreshExplorer() }
                searchRunnable = runnable
                searchHandler.postDelayed(runnable, SEARCH_DEBOUNCE_MS)
            }
        })
        explorerSearchBox.showSoftInputOnFocus = false
        explorerSearchBox.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) routeKeyboardToExplorerSearch()
        }
        explorerSearchBox.setOnClickListener { routeKeyboardToExplorerSearch() }

        findSelectionButton(R.id.selectionCancelButton).setOnClickListener { exitSelectionMode() }
        findSelectionButton(R.id.selectionDeleteButton).setOnClickListener { confirmAndDeleteSelection() }
        selectionRenameButton.setOnClickListener {
            selectedFiles.singleOrNull()?.let { showRenameDialog(it) }
        }
        selectionInfoButton.setOnClickListener {
            selectedFiles.singleOrNull()?.let { showFileInfoDialog(it) }
        }
        findSelectionButton(R.id.selectionCopyButton).setOnClickListener { enterPickDestinationMode(isMove = false) }
        findSelectionButton(R.id.selectionMoveButton).setOnClickListener { enterPickDestinationMode(isMove = true) }

        confirmTransferButton.setOnClickListener { confirmTransferToCurrentDir() }
        cancelTransferButton.setOnClickListener { cancelPickDestinationMode() }

        createFileButtonBig.setOnClickListener { showCreateMenu() }
        collapseAllButton.setOnClickListener { collapseAllAndDeselect() }
    }

    private fun findSelectionButton(id: Int): TextView = selectionBar.findViewById(id)

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    /** Tap simple sobre un ítem. Comportamiento según el modo activo:
     *  - Eligiendo destino: mismo gesto de árbol que el modo normal (ver
     *    rama de abajo) — el destino se elige dejando esa carpeta
     *    seleccionada y confirmando con ✓ (confirmTransferToCurrentDir
     *    lee currentDir en ese momento), nunca abre un archivo. A
     *    diferencia del modelo anterior (navegar adentro), el usuario
     *    puede seguir expandiendo libremente otras ramas para ubicar el
     *    destino sin perder de vista la rama de origen.
     *  - Selección múltiple activa: suma/quita de la selección.
     *  - Normal (carpeta): ver toggleExplorerNode — colapsada se expande
     *    y selecciona; expandida-no-activa solo selecciona; expandida-y-
     *    activa se colapsa (sin deseleccionar, ver doc de clase).
     *  - Normal (archivo): abre la pestaña. */
    private fun onExplorerItemClick(file: File) {
        if (pendingTransfer != null) {
            if (file.isDirectory) toggleExplorerNode(file)
            return
        }
        if (selectedFiles.isNotEmpty()) {
            toggleSelection(file)
            return
        }
        if (file.isDirectory) {
            toggleExplorerNode(file)
        } else {
            tabs.openOrFocus(file)
            onFileOpened()
        }
    }

    /** Gesto de tap sobre una carpeta en el árbol (ver doc de clase):
     *  colapsada → expande y selecciona; expandida pero no era la activa
     *  → solo selecciona (no colapsa — el usuario puede estar
     *  seleccionando una carpeta que ya estaba abierta por otra razón, o
     *  parte de otra rama); expandida y YA era la activa (tap repetido
     *  sobre la misma) → colapsa, sin tocar la selección. */
    private fun toggleExplorerNode(dir: File) {
        val wasExpanded = expandedDirs.contains(dir)
        val wasSelected = selectedDir == dir
        when {
            !wasExpanded -> {
                expandedDirs.add(dir)
                selectedDir = dir
            }
            wasExpanded && wasSelected -> {
                expandedDirs.remove(dir)
            }
            else -> {
                selectedDir = dir
            }
        }
        refreshExplorer()
        onBackStateChanged()
    }

    /** Long-press: activa selección múltiple, o suma/quita de la ya
     *  activa. Sin efecto mientras se elige destino de una transferencia. */
    private fun onExplorerItemLongClick(file: File) {
        if (pendingTransfer != null) return
        if (selectedFiles.isNotEmpty()) {
            toggleSelection(file)
        } else {
            enterSelectionMode(file)
        }
    }

    private fun enterSelectionMode(firstFile: File) {
        selectedFiles.add(firstFile)
        updateSelectionUi()
    }

    private fun toggleSelection(file: File) {
        if (!selectedFiles.remove(file)) {
            selectedFiles.add(file)
        }
        updateSelectionUi()
    }

    private fun exitSelectionMode() {
        selectedFiles.clear()
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        explorerAdapter.setSelection(selectedFiles.toSet())
        if (selectedFiles.isEmpty()) {
            selectionBar.visibility = View.GONE
        } else {
            selectionBar.visibility = View.VISIBLE
            selectionCountText.text = "${selectedFiles.size} selected"
            val singleSelected = if (selectedFiles.size == 1) View.VISIBLE else View.GONE
            selectionRenameButton.visibility = singleSelected
            selectionInfoButton.visibility = singleSelected
        }
        onBackStateChanged()
    }

    /** Entra en modo "elegir destino" para copiar/mover la selección
     *  actual, partiendo de currentDir sin moverse. */
    private fun enterPickDestinationMode(isMove: Boolean) {
        val targets = selectedFiles.toSet()
        if (targets.isEmpty()) return
        startTransferOf(targets, isMove)
    }

    /** Mismo modo "elegir destino" que [enterPickDestinationMode], pero
     *  para un set de archivos arbitrario en vez de selectedFiles —
     *  disponible para otros controllers que necesiten el mismo flujo de
     *  copy/move con un set propio (no necesariamente la selección activa
     *  del explorador). Limpia cualquier selección de archivos que
     *  hubiera activa, igual que el camino normal. */
    fun startTransferOf(targets: Set<File>, isMove: Boolean) {
        if (targets.isEmpty()) return
        val actionLabel = if (isMove) "move" else "copy"
        beginPendingTransfer(
            PendingTransfer.Files(targets, isMove),
            "Tap the destination folder and confirm (✓) to $actionLabel ${targets.size} item(s)."
        )
    }

    /** Generalización de [startTransferOf] para cuando no hay ningún
     *  archivo todavía al momento de elegir destino (ver doc de
     *  PendingTransfer.CustomAction) — usado por ActionsPanelController
     *  para arrancar la descarga de un artifact directo en la carpeta que
     *  el usuario elija, en vez de descargar primero a un temporal y
     *  recién después mover. [onConfirm] se llama UNA vez, con la carpeta
     *  elegida, cuando el usuario confirma con ✓ — quien llama decide qué
     *  hacer (y cómo reportar éxito/error) con esa carpeta; esta función
     *  no sabe nada de lo que [onConfirm] hace puertas adentro. */
    fun startCustomTransfer(toastMessage: String, onConfirm: (destDir: File) -> Unit) {
        beginPendingTransfer(PendingTransfer.CustomAction(onConfirm), toastMessage)
    }

    private fun beginPendingTransfer(transfer: PendingTransfer, toastMessage: String) {
        pendingTransfer = transfer
        selectedFiles.clear()
        explorerAdapter.setSelection(emptySet())
        selectionBar.visibility = View.GONE
        confirmTransferButton.visibility = View.VISIBLE
        cancelTransferButton.visibility = View.VISIBLE
        Toast.makeText(context, toastMessage, Toast.LENGTH_LONG).show()
        onBackStateChanged()
    }

    fun cancelPickDestinationMode() {
        pendingTransfer = null
        confirmTransferButton.visibility = View.GONE
        cancelTransferButton.visibility = View.GONE
        onBackStateChanged()
    }

    /** Despacha según el tipo de [pendingTransfer] (ver su doc de clase):
     *  [PendingTransfer.CustomAction] delega todo en su propia lambda sin
     *  tocar FileRepository; [PendingTransfer.Files] sigue el camino
     *  original de copia/movida en lote. */
    private fun confirmTransferToCurrentDir() {
        val transfer = pendingTransfer ?: return
        val destDir = currentDir

        when (transfer) {
            is PendingTransfer.CustomAction -> {
                cancelPickDestinationMode()
                transfer.onConfirm(destDir)
            }
            is PendingTransfer.Files -> confirmFilesTransfer(transfer, destDir)
        }
    }

    /** Ejecuta la copia/movida de todos los archivos pendientes hacia
     *  [destDir]. Por cada archivo, un conflicto se saltea (no es un
     *  fallo de IO) y se informa un resumen al final. */
    private fun confirmFilesTransfer(transfer: PendingTransfer.Files, destDir: File) {
        if (transfer.isMove) {
            val blockedBy = transfer.files.firstNotNullOfOrNull { tabs.findOpenTabInside(it) }
            if (blockedBy != null) {
                AlertDialog.Builder(context)
                    .setTitle("Can't move")
                    .setMessage("Close the \"${blockedBy.displayName}.${blockedBy.extension}\" tab first before moving the selection.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return
            }
        }

        var okCount = 0
        var conflictCount = 0
        var failureCount = 0
        for (target in transfer.files) {
            val outcome = if (transfer.isMove) {
                when (FileRepository.move(target, destDir)) {
                    is FileRepository.MoveResult.Success -> "ok"
                    FileRepository.MoveResult.AlreadyExists, FileRepository.MoveResult.DestinationInsideTarget -> "conflict"
                    FileRepository.MoveResult.IoFailure -> "failure"
                }
            } else {
                when (FileRepository.copy(target, destDir)) {
                    is FileRepository.CopyResult.Success -> "ok"
                    FileRepository.CopyResult.AlreadyExists, FileRepository.CopyResult.DestinationInsideTarget -> "conflict"
                    FileRepository.CopyResult.IoFailure -> "failure"
                }
            }
            when (outcome) {
                "ok" -> {
                    okCount++
                    // Paso 13: si lo que se movió es una carpeta que estaba
                    // expandida/activa en el árbol, esas referencias File
                    // ahora apuntan a una ruta que ya no existe (move()
                    // cambia la ruta real en disco) — a diferencia del
                    // modelo anterior (nunca se podía mover la carpeta en
                    // la que se estaba "parado"), con el árbol sí es
                    // posible seleccionar/expandir y después mover esa
                    // misma carpeta. Limpiar evita currentDir/expandedDirs
                    // quedando con Files obsoletos (ver rootForListing:
                    // listar desde la raíz real ya los ignoraría de todos
                    // modos, pero mejor no dejarlos colgados).
                    if (transfer.isMove) {
                        expandedDirs.remove(target)
                        if (selectedDir == target) selectedDir = null
                    }
                }
                "conflict" -> conflictCount++
                else -> failureCount++
            }
        }

        cancelPickDestinationMode()
        refreshExplorer()
        if (tabs.openFiles.isNotEmpty()) tabs.notifyTabsChanged()

        if (conflictCount > 0 || failureCount > 0) {
            val parts = mutableListOf("$okCount done")
            if (conflictCount > 0) parts.add("$conflictCount already existed at destination")
            if (failureCount > 0) parts.add("$failureCount failed")
            Toast.makeText(context, parts.joinToString(", "), Toast.LENGTH_LONG).show()
        }
    }

    /** Elimina todos los archivos/carpetas seleccionados de una sola
     *  confirmación. Bloquea con un aviso si alguno tiene una pestaña
     *  abierta dentro. */
    private fun confirmAndDeleteSelection() {
        val targets = selectedFiles.toList()
        if (targets.isEmpty()) return

        val blockedBy = targets.firstNotNullOfOrNull { tabs.findOpenTabInside(it) }
        if (blockedBy != null) {
            AlertDialog.Builder(context)
                .setTitle("Can't delete")
                .setMessage("Close the \"${blockedBy.displayName}.${blockedBy.extension}\" tab first before deleting the selection.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val totalCount = targets.sumOf { FileRepository.countFiles(it) }
        AlertDialog.Builder(context)
            .setTitle("Delete selection")
            .setMessage("Delete ${targets.size} selected item(s) and their $totalCount file(s) in total? This action can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                var anyFailure = false
                for (target in targets) {
                    val result = FileRepository.delete(target)
                    if (result is FileRepository.DeleteResult.IoFailure) {
                        anyFailure = true
                    } else {
                        // Paso 13: mismo motivo que en confirmFilesTransfer
                        // (move) — una carpeta eliminada no debe seguir
                        // "expandida" o ser la activa (currentDir) una vez
                        // que ya no existe en disco.
                        expandedDirs.remove(target)
                        if (selectedDir == target) selectedDir = null
                    }
                }
                exitSelectionMode()
                refreshExplorer()
                if (anyFailure) {
                    toast("Some items couldn't be deleted")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Lista de acciones del botón "+": un solo botón abre esta lista en
     *  vez de tener dos botones separados para archivo y carpeta. */
    private fun showCreateMenu() {
        val options = arrayOf("Nuevo archivo", "Nueva carpeta")
        AlertDialog.Builder(context)
            .setItems(options) { _, index ->
                when (index) {
                    0 -> newFileDialog().showNewFileDialog()
                    1 -> showNewFolderDialog()
                }
            }
            .show()
    }

    /** Diálogo de "Info" de un ítem: nombre, ruta, tipo, tamaño (recursivo
     *  si es carpeta) y fecha de modificación. */
    private fun showFileInfoDialog(file: File) {
        val sizeBytes = FileRepository.totalSizeBytes(file)
        val dateFormat = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault())
        val lines = mutableListOf(
            "Name: ${file.name}",
            "Path: ${file.absolutePath}",
            "Type: ${if (file.isDirectory) "Folder" else "File"}",
            "Size: ${FileRepository.formatFileSize(sizeBytes)}",
            "Modified: ${dateFormat.format(java.util.Date(file.lastModified()))}"
        )
        if (file.isDirectory) {
            lines.add("Contents: ${FileRepository.countFiles(file)} file(s)")
        }
        AlertDialog.Builder(context)
            .setTitle("Info")
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Diálogo de renombrar (archivo o carpeta): reusa el layout de "nueva
     *  carpeta" (solo un campo de nombre), precargado con el nombre actual. */
    private fun showRenameDialog(file: File) {
        dialogFormHost.show(
            title = "Renombrar",
            contentLayoutRes = R.layout.dialog_new_folder,
            bind = { dialogView ->
                val nameEdit = dialogView.findViewById<EditText>(R.id.editFolderName)
                nameEdit.setText(file.name)
                nameEdit.setSelection(0, nameEdit.text.length)
                listOf(nameEdit)
            },
            firstField = { it[0] },
            onConfirm = { fields ->
                val nameEdit = fields[0]
                when (val result = FileRepository.rename(file, nameEdit.text.toString())) {
                    is FileRepository.RenameResult.Success -> {
                        tabs.syncOpenTabsAfterRename(oldFile = file, renamedFile = result.renamed)
                        // Paso 13: si lo renombrado estaba expandido/era la
                        // carpeta activa, la referencia vieja (file) ya no
                        // existe en disco — se reemplaza por la nueva
                        // (result.renamed), no se pierde el estado: el
                        // usuario solo cambió el nombre, sigue trabajando
                        // en la misma carpeta.
                        if (expandedDirs.remove(file)) expandedDirs.add(result.renamed)
                        if (selectedDir == file) selectedDir = result.renamed
                        refreshExplorer()
                        if (tabs.openFiles.isNotEmpty()) tabs.notifyTabsChanged()
                    }
                    is FileRepository.RenameResult.InvalidName -> toast(result.reason)
                    FileRepository.RenameResult.AlreadyExists -> toast("Something with that name already exists")
                    FileRepository.RenameResult.IoFailure -> toast("Couldn't rename")
                }
                true
            }
        )
    }

    /** Diálogo mínimo de "nueva carpeta": solo nombre, sin selector de
     *  color (a diferencia de showNewFileDialog). */
    private fun showNewFolderDialog() {
        dialogFormHost.show(
            title = context.getString(R.string.action_new_folder),
            contentLayoutRes = R.layout.dialog_new_folder,
            bind = { dialogView -> listOf(dialogView.findViewById(R.id.editFolderName)) },
            firstField = { it[0] },
            onConfirm = { fields ->
                val nameEdit = fields[0]
                when (val result = FileRepository.createDirectory(currentDir, nameEdit.text.toString())) {
                    // La carpeta nueva queda seleccionada y expandida de
                    // una vez (acordado explícitamente): es lo que el
                    // usuario probablemente quiere seguir usando justo
                    // después de crearla (seguir creando adentro, moverle
                    // algo, etc.), igual que el gesto normal de tocar una
                    // carpeta colapsada (ver toggleExplorerNode).
                    is FileRepository.CreateDirectoryResult.Success -> {
                        expandedDirs.add(result.dir)
                        selectedDir = result.dir
                        refreshExplorer()
                    }
                    is FileRepository.CreateDirectoryResult.InvalidName -> toast(result.reason)
                    FileRepository.CreateDirectoryResult.AlreadyExists -> toast("Something with that name already exists")
                    FileRepository.CreateDirectoryResult.IoFailure -> toast("Couldn't create the folder")
                }
                true
            }
        )
    }

    // Token de secuencia para refreshExplorer(): se incrementa en cada
    // llamada y se captura en la lambda de fondo cuando hay búsqueda activa.
    // Si para cuando esa búsqueda termina ya se lanzó una más nueva (letra
    // siguiente, o se salió del cuadro de búsqueda), el token capturado ya
    // no coincide con el actual y el resultado se descarta — evita que una
    // búsqueda vieja, más lenta, sobrescriba a una más nueva que terminó
    // primero (orden de llegada en background no es orden de inicio).
    private var refreshSeq = 0

    /** Si hay texto en el buscador, muestra resultados recursivos en vez
     *  del listado normal de currentDir. El listado normal (list()) es
     *  rápido y síncrono, igual que antes. La búsqueda recursiva
     *  (searchRecursive) es I/O de disco que puede tardar, así que corre en
     *  un hilo de fondo para no bloquear el hilo principal — eso era lo que
     *  hacía sentir el teclado "trabado" y producía inserciones de texto
     *  fuera de orden al escribir rápido (el EditText seguía recibiendo
     *  teclas mientras el hilo principal estaba ocupado con la búsqueda
     *  anterior). El resultado se aplica de vuelta en el hilo principal. */
    fun refreshExplorer() {
        val query = explorerSearchBox.text?.toString() ?: ""
        val seq = ++refreshSeq
        if (query.isBlank()) {
            explorerAdapter.update(buildVisibleNodes())
            explorerAdapter.setSelectedDir(selectedDir)
            updateGitBackground()
            return
        }
        Thread {
            // Búsqueda recursiva parte de currentDir (carpeta activa), no
            // siempre de la raíz — mismo criterio que crear archivo/git:
            // todo lo que antes dependía de "dónde estoy parado" ahora
            // depende de la misma carpeta seleccionada.
            val items = FileRepository.searchRecursive(currentDir, query)
                .map { ExplorerNode(it, depth = 0) }
            searchHandler.post {
                if (seq != refreshSeq) return@post
                explorerAdapter.update(items)
                explorerAdapter.setSelectedDir(selectedDir)
                updateGitBackground()
            }
        }.start()
    }

    /** Aplana el árbol visible en una lista ordenada de [ExplorerNode], a
     *  partir del listado normal (no de búsqueda) de currentDir — ver doc
     *  de clase sobre expandedDirs. Cada carpeta en expandedDirs aporta
     *  sus hijos inmediatamente después de ella, con depth+1, de forma
     *  recursiva (una carpeta expandida dentro de otra expandida sigue
     *  bajando de nivel). list() ya ordena carpetas antes que archivos y
     *  alfabéticamente dentro de cada grupo (ver su doc) — ese orden se
     *  preserva en cada nivel. */
    private fun buildVisibleNodes(): List<ExplorerNode> {
        val result = mutableListOf<ExplorerNode>()
        fun walk(dir: File, depth: Int) {
            for (file in FileRepository.list(dir)) {
                result.add(ExplorerNode(file, depth))
                if (file.isDirectory && expandedDirs.contains(file)) {
                    walk(file, depth + 1)
                }
            }
        }
        walk(rootForListing(), 0)
        return result
    }

    /** La raíz real del árbol listado: siempre FileRepository.rootDir(),
     *  nunca currentDir — a diferencia del modelo anterior (navegación de
     *  un nivel), seleccionar una carpeta ya NO cambia qué se lista desde
     *  la raíz del árbol, solo decide cuál es la carpeta activa para
     *  crear/git/búsqueda (ver doc de clase). */
    private fun rootForListing(): File = FileRepository.rootDir()

    /** Marca grande de fondo (ver doc del layout, explorerGitBackground):
     *  visible mientras currentDir está dentro de un repo en cualquier
     *  profundidad, raíz incluida — no solo parado justo en la raíz. */
    private fun updateGitBackground() {
        explorerGitBackground.visibility =
            if (FileRepository.findRepoRoot(currentDir) != null) View.VISIBLE else View.GONE
    }

    fun hasSelection(): Boolean = selectedFiles.isNotEmpty()

    fun hasPendingTransfer(): Boolean = pendingTransfer != null

    /** Colapsa TODAS las carpetas expandidas de una vez (vuelve el árbol a
     *  su estado inicial). Usada solo por el botón dedicado collapseAllButton
     *  (ver collapseAllAndDeselect) — Back ya NO la llama (decisión
     *  explícita del usuario: minimizar/cerrar el panel no debe colapsar
     *  nada, ver doc de handleBackPress). No toca selectedDir por sí sola
     *  (ver doc de clase: contraer no deselecciona); collapseAllAndDeselect
     *  es quien decide deseleccionar además. */
    private fun collapseAll() {
        expandedDirs.clear()
        explorerSearchBox.setText("")
        refreshExplorer()
    }

    /** Botón dedicado "contraer todo": a diferencia de un tap normal sobre
     *  una carpeta (que nunca deselecciona al contraer, ver doc de clase),
     *  esta es una acción explícita de "reiniciar el árbol", así que
     *  además decae la selección a la raíz (selectedDir = null →
     *  currentDir cae a rootDir(), ver doc de esa property). */
    private fun collapseAllAndDeselect() {
        selectedDir = null
        collapseAll()
    }

    /** Maneja un Back mientras el panel del explorador está visible.
     *  Devuelve true si lo consumió (canceló algo), false si no había nada
     *  que hacer — ver EditorActivity.updateBackCallbackState, que decide
     *  si este callback llega a dispararse según haya algo para
     *  cancelar/deseleccionar.
     *
     *  Paso 13: pickDestination con Back ya CANCELA directo en vez de
     *  "subir un nivel" — el modo de elegir destino ya no navega (el
     *  destino se toca directo sobre el árbol ya desplegado, ver
     *  onExplorerItemClick), así que no hay a dónde "subir". Back ya NO
     *  colapsa el árbol (decisión explícita del usuario): minimizar o
     *  cerrar el panel debe dejar todo tal cual estaba al reabrirlo; para
     *  colapsar a propósito está el botón dedicado collapseAllButton (ver
     *  collapseAllAndDeselect). */
    fun handleBackPress(): Boolean {
        if (pendingTransfer != null) {
            cancelPickDestinationMode()
            return true
        }
        if (selectedFiles.isNotEmpty()) {
            exitSelectionMode()
            return true
        }
        return false
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun explorerMinWidthPx(): Int = dp(120)
    private fun explorerMaxWidthPx(): Int = (mainRow.width * 0.75f).toInt().coerceAtLeast(explorerMinWidthPx())

    /** Pendiente reportado: el panel arrancaba siempre al ancho fijo del
     *  XML (160dp, cercano al mínimo) en vez de al máximo ya definido para
     *  el resize manual (ver explorerMaxWidthPx) — nunca se tocaba
     *  layoutParams.width al abrir, solo al arrastrar explorerResizeHandle.
     *  Llamado desde EditorActivity.openExplorerPanel() dentro de un
     *  post{} (igual que DialogFormHost.shrinkToExcludeBottomControls):
     *  mainRow.width necesita que el layout pass ya haya corrido para dar
     *  un valor real, no el de un frame previo con el panel todavía GONE. */
    fun applyMaxWidth() {
        val lp = explorerContainer.layoutParams
        lp.width = explorerMaxWidthPx()
        explorerContainer.layoutParams = lp
    }

    /** Handle de arrastre horizontal del panel del explorador, para
     *  redimensionarlo — mismo patrón que setupConsoleResizeHandle en
     *  ConsoleController, adaptado de alto/rawY a ancho/rawX. */
    private fun setupExplorerResizeHandle() {
        var startX = 0f
        var startWidth = explorerContainer.layoutParams.width
        explorerResizeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startWidth = explorerContainer.layoutParams.width
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - startX
                    val newWidth = (startWidth + deltaX.toInt())
                        .coerceIn(explorerMinWidthPx(), explorerMaxWidthPx())
                    val lp = explorerContainer.layoutParams
                    if (lp.width != newWidth) {
                        lp.width = newWidth
                        explorerContainer.layoutParams = lp
                        // El ancho real de explorerTreeScroll (panelWidthPx,
                        // ver FileExplorerAdapter) no se actualiza hasta el
                        // próximo layout pass, posterior a este evento — se
                        // reaplica el viewport.post{} para recalcular
                        // displayName/minWidth de cada fila ya con el ancho
                        // nuevo, en vez de quedar con el truncado/minWidth
                        // del ancho anterior hasta el próximo refreshExplorer.
                        explorerTreeScroll.post { explorerAdapter.notifyDataSetChanged() }
                    }
                    true
                }
                else -> false
            }
        }
    }
}
