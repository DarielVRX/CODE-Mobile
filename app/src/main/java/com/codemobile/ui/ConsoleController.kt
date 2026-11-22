package com.codemobile.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.codemobile.R
import com.codemobile.git.GitConsoleParser
import com.codemobile.git.GitCredentialsStore
import com.codemobile.git.GitForegroundService
import com.codemobile.git.GitIdentityStore
import com.codemobile.git.GitRepository
import com.codemobile.git.GitTaskBus
import com.codemobile.keyboard.PlainEditTextTarget
import java.io.File
import java.util.concurrent.SynchronousQueue

private const val CONSOLE_GIT_HINT_DEFAULT =
    "git clone / add / commit -m \"...\" / push / pull / status / branch / checkout / merge / " +
        "fetch / log / diff / reset / rm / rebase / stash"
private const val REQUEST_CODE_POST_NOTIFICATIONS = 2001

/**
 * Consola de ejecución (Run) + consola git (botón en el explorador),
 * ambas comparten el mismo panel y el mismo EditText de comandos.
 * Extraído tal cual de EditorActivity, sin cambiar ninguna regla.
 *
 * Depende de: [currentDir] (lectura hacia ExplorerController — L, ver
 * informe de diseño: Consola necesita saber sobre qué carpeta operan los
 * comandos git, pero no necesita referenciar el controller completo) y
 * [noActiveTab] (Paso 13: antes era isExplorerModeVisible — con explorador
 * y editor conviviendo siempre como panel lateral + columna, "modo
 * explorador" ya no existe; lo que de verdad decidía esas dos visibilidades
 * puntuales es si hay o no una pestaña activa, así que ahora lee eso
 * directo, sin pasar por el concepto de "modo pantalla"),
 * [restoreKeyboardTargetToEditor]
 * (hacia TabsController, misma razón), [runOnUiThread] y
 * [requestPostNotificationsPermission] (lambdas en vez de Activity
 * completa — ver informe §4.4/§4.5).
 *
 * La suscripción a GitTaskBus.onResult se hace en [attach], que
 * EditorActivity debe llamar siempre en onCreate (nunca condicionado a
 * abrir la consola) — si GitForegroundService seguía corriendo de una
 * Activity anterior, hay que re-suscribirse o onResult apuntaría a una
 * instancia ya destruida (ver riesgo #4 del informe de diseño).
 */
class ConsoleController(
    private val context: Context,
    private val consoleContainer: android.widget.FrameLayout,
    private val contentColumn: View,
    private val bottomControlsColumn: View,
    private val layoutInflater: LayoutInflater,
    private val dialogFormHost: DialogFormHost,
    private val keyboardRouter: KeyboardRouter,
    private val currentDir: () -> File,
    private val noActiveTab: () -> Boolean,
    private val restoreKeyboardTargetToEditor: () -> Unit,
    private val syncBottomControlsMargin: () -> Unit,
    private val runOnUiThread: (() -> Unit) -> Unit,
    private val requestPostNotificationsPermission: () -> Unit,
    /** Llamado la primera vez que se infla view_console.xml (ver
     *  showConsole), con las vistas de la fila gitActionsRow (incluida
     *  dentro de view_console.xml). GitActionsController no existe
     *  todavía cuando se construye ConsoleController (depende de estas
     *  vistas, que no existen hasta el primer showConsole), así que
     *  EditorActivity usa este callback para terminar de construirlo y
     *  conectarlo recién en ese momento, en vez de acoplar ambos
     *  controllers entre sí. branchButton/shortcutsButton son View (no
     *  TextView): branchButton ahora es un LinearLayout (ícono + label) y
     *  shortcutsButton un ImageView — ver view_git_actions.xml. */
    private val onGitActionsRowInflated: (branchButton: View, branchButtonText: TextView, shortcutsButton: View) -> Unit
) {

    private var consoleOutputView: TextView? = null
    private var consoleScrollView: android.widget.ScrollView? = null
    private var consoleGitInputView: EditText? = null
    private var gitActionsRowView: View? = null
    private var pendingStdinQueue: SynchronousQueue<String>? = null

    /** GitActionsController se suscribe acá (vía EditorActivity, en
     *  onGitActionsRowInflated) para refrescar el label de la rama activa
     *  después de cualquier resultado de comando git — push/pull/checkout/
     *  merge/rebase pueden mover HEAD, y es más simple refrescar siempre
     *  que decidir acá, en ConsoleController, cuáles comandos lo mueven y
     *  cuáles no.
     *
     *  [filesMayHaveChanged] SÍ es selectivo (a diferencia de la rama, que
     *  siempre se refresca): EditorActivity lo usa para refrescar el
     *  explorador de archivos solo cuando el comando que acaba de
     *  terminar pudo haber tocado el working tree — ver lastCommand
     *  AffectsWorkingTree, fijado en submitGitCommand() antes de
     *  runGitInBackground(). */
    var onGitResultApplied: ((filesMayHaveChanged: Boolean) -> Unit)? = null

    /** Fijado en submitGitCommand() justo antes de cada runGitInBackground(),
     *  según el ParsedCommand que se está por ejecutar — ver su doc. Leído
     *  una sola vez, acá en attach(), cuando el resultado de ESE comando
     *  llega. */
    private var lastCommandAffectsWorkingTree = false

    fun attach() {
        GitTaskBus.onResult = { result ->
            runOnUiThread {
                appendResult(result)
                updateGitInputEnabledState()
                onGitResultApplied?.invoke(lastCommandAffectsWorkingTree)
            }
        }
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    /** Puente bloqueante entre Python (input(), corriendo en el hilo de
     *  ExecutionDispatcher) y la consola. Llamado DESDE ese hilo de fondo,
     *  nunca el de UI. */
    fun onStdinRequest(pending: String): String {
        val queue = SynchronousQueue<String>()
        runOnUiThread {
            if (pending.isNotEmpty()) appendConsole(pending)
            pendingStdinQueue = queue
            consoleGitInputView?.visibility = View.VISIBLE
            consoleGitInputView?.isEnabled = true
            consoleGitInputView?.requestFocus()
            routeKeyboardToGitConsole()
        }
        val line = queue.take()
        return "$line\n"
    }

    /** Asegura que la consola se dibuje SIEMPRE por encima del teclado
     *  propio. Ver doc original: elevación puramente visual. */
    private fun setConsoleAboveKeyboard(above: Boolean) {
        contentColumn.elevation = if (above) bottomControlsColumn.elevation + 1f else 0f
    }

    fun showConsole() {
        setConsoleAboveKeyboard(true)
        // Sin esto, la PRIMERA apertura (bug preexistente, no introducido acá
        // — race condition de layout: bottomControlsColumn/controlBar aún no
        // completaron su primer layout pass real en este punto) dejaba
        // contentColumn con el margen inferior desactualizado, así que la
        // consola se dibujaba ocupando el espacio real de controlBar,
        // tapándola. Para la SEGUNDA apertura en adelante ya no hace falta
        // (ya hubo layout pass real de por medio), pero esto es idempotente:
        // si el margen ya está al día, es un no-op visual.
        syncBottomControlsMargin()
        if (consoleContainer.childCount == 0) {
            val view = layoutInflater.inflate(R.layout.view_console, consoleContainer, false)
            consoleOutputView = view.findViewById(R.id.consoleOutput)
            view.findViewById<TextView>(R.id.consoleClear).setOnClickListener {
                consoleOutputView?.text = ""
            }
            view.findViewById<TextView>(R.id.consoleClose).setOnClickListener {
                consoleContainer.visibility = View.GONE
                setConsoleAboveKeyboard(false)
                restoreKeyboardTargetToEditor()
                // Si no hay ninguna pestaña activa, customKeyboard estaba
                // GONE (ver EditorActivity.syncEditorVisibilityForTabs) y
                // routeKeyboardToGitConsole lo puso en VISIBLE a mano al
                // enfocar el campo de comandos — hay que revertirlo a GONE
                // al cerrar. Si hay una pestaña activa (con o sin Run de
                // por medio), no se toca nada: ya está VISIBLE y debe
                // seguir así.
                if (noActiveTab()) {
                    keyboardRouter.hideKeyboard()
                    syncBottomControlsMargin()
                }
            }
            consoleScrollView = view.findViewById(R.id.consoleScroll)
            setupConsoleResizeHandle(
                handle = view.findViewById(R.id.consoleResizeHandle),
                scroll = consoleScrollView!!
            )
            consoleGitInputView = view.findViewById(R.id.consoleGitInput)
            consoleGitInputView?.showSoftInputOnFocus = false
            consoleGitInputView?.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) routeKeyboardToGitConsole()
            }
            consoleGitInputView?.setOnClickListener { routeKeyboardToGitConsole() }
            consoleGitInputView?.setOnEditorActionListener { _, _, _ ->
                submitCurrentGitConsoleInput()
                true
            }
            gitActionsRowView = view.findViewById(R.id.gitActionsRow)
            val branchButton = view.findViewById<View>(R.id.branchButton)
            val branchButtonText = view.findViewById<TextView>(R.id.branchButtonText)
            val shortcutsButton = view.findViewById<View>(R.id.shortcutsButton)
            onGitActionsRowInflated(branchButton, branchButtonText, shortcutsButton)
            consoleContainer.addView(view)
        }
        consoleContainer.visibility = View.VISIBLE
        updateGitInputEnabledState()
    }

    /** Toma el texto actual de consoleGitInputView, lo envía a
     *  submitGitCommand y limpia el campo. */
    private fun submitCurrentGitConsoleInput() {
        val field = consoleGitInputView ?: return
        val text = field.text?.toString() ?: ""
        val stdinQueue = pendingStdinQueue
        if (stdinQueue != null) {
            pendingStdinQueue = null
            field.setText("")
            if (!noActiveTab()) {
                field.visibility = View.GONE
            }
            stdinQueue.put(text)
            updateGitInputEnabledState()
            return
        }
        submitGitCommand(text)
        field.setText("")
    }

    /** Dirige el teclado propio hacia el campo de comandos git. */
    private fun routeKeyboardToGitConsole() {
        val field = consoleGitInputView ?: return
        keyboardRouter.routeTo(
            PlainEditTextTarget(field, onEnterPressed = {
                submitCurrentGitConsoleInput()
                true
            })
        )
    }

    private fun updateGitInputEnabledState() {
        val running = GitTaskBus.isRunning
        consoleGitInputView?.isEnabled = !running
        consoleGitInputView?.hint = if (running) {
            "Waiting for the current git operation to finish…"
        } else {
            CONSOLE_GIT_HINT_DEFAULT
        }
    }

    /** Botón git (solo en el explorador): abre la consola si no estaba
     *  abierta, y muestra el campo de entrada de comandos git. */
    fun openGitConsole() {
        showConsole()
        consoleGitInputView?.visibility = View.VISIBLE
        gitActionsRowView?.visibility = View.VISIBLE
        onGitResultApplied?.invoke(false)
        appendConsole(
            "Git commands run against: ${currentDir().absolutePath}\n" +
                "For a different folder, close the console, navigate to it in the explorer, and open it again.\n"
        )
        appendConsole(GitConsoleParser.supportedCommandsHelp() + "\n")
        // Antes se forzaba requestFocus()+routeKeyboardToGitConsole() acá,
        // lo que abría el teclado propio automáticamente apenas se abría
        // la consola — con los botones de atajos nuevos, la mayoría de
        // las veces el usuario entra a tocar un botón, no a escribir, así
        // que el teclado ya no se abre solo: recién aparece si el usuario
        // toca consoleGitInput (ver su setOnFocusChangeListener/
        // setOnClickListener en showConsole) o si un diálogo que lo
        // necesita lo pide (showGitIdentityDialog/showGitCredentialsDialog,
        // vía DialogFormHost).
        if (!GitIdentityStore.hasIdentity(context)) {
            consoleContainer.post { showGitIdentityDialog() }
        }
    }

    /** Punto de entrada para GitActionsController (botones de atajos): la
     *  app muestra en consola la línea tal como un comando real de
     *  terminal, y reusa exactamente el mismo camino que si el usuario la
     *  hubiera escrito a mano en consoleGitInput — sin esto, los botones
     *  tendrían que duplicar el when de GitConsoleParser.ParsedCommand. */
    fun submitGitCommandFromButton(line: String) {
        showConsole()
        consoleGitInputView?.visibility = View.VISIBLE
        gitActionsRowView?.visibility = View.VISIBLE
        submitGitCommand(line)
    }

    /** Ramas del repo en currentDir(), o lista vacía si no hay repo —
     *  usado por GitActionsController para poblar el botón/dropdown de
     *  rama activa sin duplicar la búsqueda de repoRoot. */
    fun listBranchesForCurrentDir(): List<GitRepository.BranchInfo> {
        val repoRoot = GitRepository.findRepoRoot(currentDir()) ?: return emptyList()
        return GitRepository.listBranches(repoRoot)
    }

    /** Traduce el texto escrito en consoleGitInput a una llamada de
     *  GitRepository, usando GitConsoleParser. */
    private fun submitGitCommand(line: String) {
        if (line.isBlank()) return
        if (GitTaskBus.isRunning) {
            appendConsole("There's already a git operation in progress, wait for it to finish.\n")
            return
        }
        appendConsole("$ $line\n")

        val parsed = GitConsoleParser.parse(line)

        // Fijado ACÁ, antes del when de abajo, para cubrir los 7 comandos
        // que de verdad pueden cambiar archivos en el working tree —
        // leído después en attach() cuando el resultado de este comando
        // llega (ver doc de onGitResultApplied/lastCommandAffectsWorkingTree).
        // Deliberadamente NO incluye Add/Commit (tocan el índice/historial,
        // no el contenido de archivos ya presentes en disco) ni los
        // comandos de solo lectura (status/log/diff/fetch/branch sin
        // crear/push). Stash: CREATE (snapshot) no toca el working tree
        // tras terminar — lo deja limpio —, pero POP/DROP sí restauran o
        // descartan cambios reales.
        lastCommandAffectsWorkingTree = when (parsed) {
            is GitConsoleParser.ParsedCommand.Checkout,
            is GitConsoleParser.ParsedCommand.Merge,
            is GitConsoleParser.ParsedCommand.Pull,
            is GitConsoleParser.ParsedCommand.Reset,
            is GitConsoleParser.ParsedCommand.Rm,
            is GitConsoleParser.ParsedCommand.Rebase -> true
            is GitConsoleParser.ParsedCommand.Stash ->
                parsed.action == GitConsoleParser.StashAction.POP ||
                    parsed.action == GitConsoleParser.StashAction.DROP
            else -> false
        }

        when (parsed) {
            is GitConsoleParser.ParsedCommand.NotGitCommand -> {
                appendConsole("Not a git command (did you mean to run code? use the Run button)\n")
            }
            is GitConsoleParser.ParsedCommand.ParseError -> {
                appendConsole("${parsed.reason}\n")
            }
            is GitConsoleParser.ParsedCommand.Unsupported -> {
                appendConsole("\"git ${parsed.commandWord}\" isn't supported yet. ${GitConsoleParser.supportedCommandsHelp()}\n")
            }
            is GitConsoleParser.ParsedCommand.Clone -> {
                val destName = parsed.url.substringAfterLast('/').removeSuffix(".git")
                val destDir = File(currentDir(), destName)
                runGitInBackground(line) { GitRepository.clone(parsed.url, destDir, githubUsername(), githubToken()) }
            }
            is GitConsoleParser.ParsedCommand.Init -> {
                runGitInBackground(line) { GitRepository.init(currentDir()) }
            }
            is GitConsoleParser.ParsedCommand.Add -> withRepoRoot { repoRoot ->
                runGitInBackground(line) { GitRepository.add(repoRoot, parsed.pathspec) }
            }
            is GitConsoleParser.ParsedCommand.Commit -> withRepoRoot { repoRoot ->
                // Resumen corto antes de confirmar (no el diff completo:
                // volcado línea por línea era ilegible en una consola
                // chica de celular). Síncrono (no runGitInBackground):
                // es una lectura local rápida, igual criterio que
                // listBranches — no necesita notificación ni bloquear la
                // consola de por sí. Si el usuario quiere ver el contenido
                // real del cambio, está el botón/comando "diff" aparte.
                val stagedFiles = GitRepository.stagedFileNames(repoRoot)
                if (stagedFiles.isNotEmpty()) {
                    val count = stagedFiles.size
                    val noun = if (count == 1) "modified file" else "modified files"
                    appendConsole("$count $noun: ${stagedFiles.joinToString(", ")}\n")
                }
                requireGitIdentityThen {
                    runGitInBackground(line) {
                        GitRepository.commit(repoRoot, parsed.message, GitIdentityStore.getName(context), GitIdentityStore.getEmail(context))
                    }
                }
            }
            is GitConsoleParser.ParsedCommand.Push -> withRepoRoot { repoRoot ->
                requireGitCredentialsThen {
                    runGitInBackground(line) { GitRepository.push(repoRoot, githubUsername(), githubToken()) }
                }
            }
            is GitConsoleParser.ParsedCommand.Pull -> withRepoRoot { repoRoot ->
                requireGitCredentialsThen {
                    runGitInBackground(line) { GitRepository.pull(repoRoot, githubUsername(), githubToken()) }
                }
            }
            is GitConsoleParser.ParsedCommand.Status -> withRepoRoot { repoRoot ->
                runGitInBackground(line) { GitRepository.status(repoRoot) }
            }
            is GitConsoleParser.ParsedCommand.Branch -> withRepoRoot { repoRoot ->
                runGitInBackground(line) { GitRepository.branch(repoRoot, parsed.newBranchName) }
            }
            is GitConsoleParser.ParsedCommand.Checkout -> withRepoRoot { repoRoot ->
                runGitInBackground(line) { GitRepository.checkout(repoRoot, parsed.target) }
            }
            is GitConsoleParser.ParsedCommand.Merge -> withRepoRoot { repoRoot ->
                runGitInBackground(line) { GitRepository.merge(repoRoot, parsed.branchName) }
            }
            is GitConsoleParser.ParsedCommand.Fetch -> withRepoRoot { repoRoot ->
                requireGitCredentialsThen {
                    runGitInBackground(line) { GitRepository.fetch(repoRoot, githubUsername(), githubToken()) }
                }
            }
            is GitConsoleParser.ParsedCommand.Log -> withRepoRoot { repoRoot ->
                runGitInBackground(line) { GitRepository.log(repoRoot, parsed.count) }
            }
            is GitConsoleParser.ParsedCommand.Diff -> withRepoRoot { repoRoot ->
                runGitInBackground(line) { GitRepository.diff(repoRoot, parsed.staged) }
            }
            is GitConsoleParser.ParsedCommand.Reset -> withRepoRoot { repoRoot ->
                runGitInBackground(line) { GitRepository.reset(repoRoot, parsed.mode, parsed.target) }
            }
            is GitConsoleParser.ParsedCommand.Rm -> withRepoRoot { repoRoot ->
                runGitInBackground(line) { GitRepository.rm(repoRoot, parsed.pathspec) }
            }
            is GitConsoleParser.ParsedCommand.Rebase -> withRepoRoot { repoRoot ->
                runGitInBackground(line) { GitRepository.rebase(repoRoot, parsed.branchName) }
            }
            is GitConsoleParser.ParsedCommand.Stash -> withRepoRoot { repoRoot ->
                runGitInBackground(line) {
                    when (parsed.action) {
                        GitConsoleParser.StashAction.CREATE -> GitRepository.stashCreate(repoRoot)
                        GitConsoleParser.StashAction.POP -> GitRepository.stashPop(repoRoot)
                        GitConsoleParser.StashAction.LIST -> GitRepository.stashList(repoRoot)
                        GitConsoleParser.StashAction.DROP -> GitRepository.stashDrop(repoRoot)
                    }
                }
            }
        }
    }

    /** Despacha [work] a GitForegroundService. */
    private fun runGitInBackground(commandLine: String, work: suspend () -> GitRepository.GitResult) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestPostNotificationsPermission()
        }

        GitForegroundService.start(context, commandLine, work)
        updateGitInputEnabledState()
    }

    /** Busca el repo Git subiendo desde currentDir() y corre [block] si lo
     *  encuentra; si no, informa el error en la consola. */
    private fun withRepoRoot(block: (File) -> Unit) {
        val repoRoot = GitRepository.findRepoRoot(currentDir())
        if (repoRoot == null) {
            appendConsole("No git repository in this folder or any parent folder. Use \"git init\" or \"git clone\" first.\n")
            return
        }
        block(repoRoot)
    }

    private fun appendResult(result: GitRepository.GitResult) {
        when (result) {
            is GitRepository.GitResult.Success -> appendConsole("${result.message}\n")
            is GitRepository.GitResult.Failure -> appendConsole("✗ ${result.message}\n")
        }
    }

    private fun githubUsername(): String? = GitCredentialsStore.getUsername(context)
    private fun githubToken(): String? = GitCredentialsStore.getToken(context)

    /** push/pull necesitan credenciales; si no hay token guardado, se pide
     *  antes de intentar el comando. */
    private fun requireGitCredentialsThen(block: () -> Unit) {
        if (GitCredentialsStore.hasToken(context)) {
            block()
            return
        }
        showGitCredentialsDialog(afterSave = block)
    }

    private fun showGitCredentialsDialog(afterSave: (() -> Unit)? = null) {
        dialogFormHost.show(
            title = "Credenciales de GitHub",
            contentLayoutRes = R.layout.dialog_git_credentials,
            bind = { dialogView ->
                val usernameEdit = dialogView.findViewById<EditText>(R.id.editGitUsername)
                val tokenEdit = dialogView.findViewById<EditText>(R.id.editGitToken)
                githubUsername()?.let { usernameEdit.setText(it) }
                listOf(usernameEdit, tokenEdit)
            },
            firstField = { it[0] },
            onConfirm = { fields ->
                val (usernameEdit, tokenEdit) = fields
                val username = usernameEdit.text.toString().trim()
                val token = tokenEdit.text.toString().trim()
                if (username.isEmpty() || token.isEmpty()) {
                    Toast.makeText(context, "Username and token are required", Toast.LENGTH_SHORT).show()
                    false
                } else {
                    GitCredentialsStore.saveToken(context, username, token)
                    appendConsole("Credentials saved for \"$username\".\n")
                    afterSave?.invoke()
                    true
                }
            }
        )
    }

    /** commit necesita nombre/email de autoría; si no hay identidad
     *  guardada, se pide antes de intentar el comando. */
    private fun requireGitIdentityThen(block: () -> Unit) {
        if (GitIdentityStore.hasIdentity(context)) {
            block()
            return
        }
        showGitIdentityDialog(afterSave = block)
    }

    private fun showGitIdentityDialog(afterSave: (() -> Unit)? = null) {
        dialogFormHost.show(
            title = "Identity for commits",
            contentLayoutRes = R.layout.dialog_git_identity,
            bind = { dialogView ->
                val nameEdit = dialogView.findViewById<EditText>(R.id.editGitIdentityName)
                val emailEdit = dialogView.findViewById<EditText>(R.id.editGitIdentityEmail)
                GitIdentityStore.getName(context)?.let { nameEdit.setText(it) }
                GitIdentityStore.getEmail(context)?.let { emailEdit.setText(it) }
                listOf(nameEdit, emailEdit)
            },
            firstField = { it[0] },
            onConfirm = { fields ->
                val (nameEdit, emailEdit) = fields
                val name = nameEdit.text.toString().trim()
                val email = emailEdit.text.toString().trim()
                if (name.isEmpty() || email.isEmpty()) {
                    Toast.makeText(context, "Name and email are required", Toast.LENGTH_SHORT).show()
                    false
                } else {
                    GitIdentityStore.saveIdentity(context, name, email)
                    appendConsole("Identity saved: $name <$email>\n")
                    afterSave?.invoke()
                    true
                }
            }
        )
    }

    fun appendConsole(text: String) {
        consoleOutputView?.append(text)
        // Auto-scroll al final, como cualquier consola real: sin esto el
        // scroll se quedaba donde el usuario lo había dejado, obligando a
        // deslizar a mano cada vez que un comando agrega texto nuevo.
        // post{} porque el ScrollView todavía no re-midió su contenido en
        // este mismo frame (consoleOutputView.append ya disparó el cambio,
        // pero el layout pass que lo refleja en scrollView.height ocurre
        // después) — fullScroll contra la medida vieja no llegaría al final.
        consoleScrollView?.post {
            consoleScrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun consoleMinHeightPx(): Int = dp(60)
    private fun consoleMaxHeightPx(): Int = (contentColumn.height * 0.7f).toInt().coerceAtLeast(consoleMinHeightPx())

    /** Handle de arrastre vertical de la consola, para redimensionarla. */
    private fun setupConsoleResizeHandle(handle: View, scroll: View) {
        val params = scroll.layoutParams
        params.height = dp(120)
        scroll.layoutParams = params

        var startY = 0f
        var startHeight = params.height
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    startHeight = scroll.layoutParams.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.rawY - startY
                    val newHeight = (startHeight - deltaY.toInt())
                        .coerceIn(consoleMinHeightPx(), consoleMaxHeightPx())
                    val lp = scroll.layoutParams
                    if (lp.height != newHeight) {
                        lp.height = newHeight
                        scroll.layoutParams = lp
                    }
                    true
                }
                else -> false
            }
        }
    }
}
