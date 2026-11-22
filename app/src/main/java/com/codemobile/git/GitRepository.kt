package com.codemobile.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Wrapper delgado sobre JGit (librería Git pura JVM, sin binario externo ni
 * NDK) para los comandos que expone la consola Git del explorador (ver
 * com.codemobile.ui.GitConsoleParser y EditorActivity). Cada función de acá
 * traduce un comando a su llamada equivalente de la API de JGit y devuelve
 * un resultado explícito por tipo (éxito con qué pasó, o qué tipo de
 * error) — JGit resuelve toda la mecánica interna de Git (objetos, índice,
 * protocolo de red); estas funciones solo arman la llamada y envuelven el
 * resultado/excepción.
 *
 * Los catch son de Exception, no solo del esperado GitAPIException: JGit
 * puede lanzar otros tipos (confirmado al probar contra un repo real en el
 * entorno de desarrollo: antes de instalar GitSystemConfig, una config
 * externa con gpg.format=ssh hacía explotar CommitCommand con una
 * IllegalArgumentException no relacionada al commit en sí). GitSystemConfig
 * resuelve esa causa de raíz (JGit ya no lee config externa al sistema),
 * pero el catch amplio queda como defensa adicional ante cualquier otra
 * excepción inesperada de la librería.
 *
 * No hay credenciales por repo: un solo Personal Access Token global (ver
 * GitCredentialsStore), igual que una persona que usa la misma cuenta de
 * GitHub para todos sus repos en el celular.
 */
object GitRepository {

    init {
        GitSystemConfig.ensureInstalled()
    }

    sealed class GitResult {
        data class Success(val message: String) : GitResult()
        /** Comando ejecutado pero JGit devolvió un fallo esperable (sin
         *  cambios para commitear, credenciales inválidas, conflicto de
         *  merge, etc.) — no es un bug, es información para mostrar tal
         *  cual en la consola. */
        data class Failure(val message: String) : GitResult()
    }

    /** Rama local estructurada (a diferencia de [branch], que devuelve texto
     *  plano pensado para consola) — pensada para poblar el dropdown de
     *  ramas de GitActionsController. */
    data class BranchInfo(val name: String, val isCurrent: Boolean)

    /** Busca un repo Git subiendo desde [startDir] hacia la raíz (mismo
     *  comportamiento que el git real: "dir" puede ser cualquier subcarpeta
     *  DENTRO del repo, no necesariamente la raíz). Null si no hay ningún
     *  .git en ningún ancestro. */
    fun findRepoRoot(startDir: File): File? {
        var current: File? = startDir
        while (current != null) {
            if (File(current, ".git").exists()) return current
            current = current.parentFile
        }
        return null
    }

    private fun credentialsProvider(username: String?, token: String?): UsernamePasswordCredentialsProvider? {
        if (username.isNullOrBlank() || token.isNullOrBlank()) return null
        // GitHub acepta cualquier string no vacío como "usuario" cuando se
        // autentica por PAT; se usa el username guardado solo para
        // identificar al autor en los mensajes, JGit solo necesita el PAT.
        return UsernamePasswordCredentialsProvider(username, token)
    }

    fun init(dir: File): GitResult {
        return try {
            Git.init().setDirectory(dir).call().use { }
            GitResult.Success("Repository initialized at ${dir.absolutePath}")
        } catch (e: Exception) {
            GitResult.Failure(e.message ?: "Couldn't initialize the repository")
        }
    }

    fun clone(url: String, destDir: File, username: String?, token: String?): GitResult {
        return try {
            val cmd = Git.cloneRepository()
                .setURI(url)
                .setDirectory(destDir)
            credentialsProvider(username, token)?.let { cmd.setCredentialsProvider(it) }
            cmd.call().use { }
            GitResult.Success("Cloned to ${destDir.absolutePath}")
        } catch (e: Exception) {
            GitResult.Failure(e.message ?: "Couldn't clone the repository")
        }
    }

    /** [pathspec] tal como lo escribió el usuario después de "git add"
     *  (ej. "." para todo, o un nombre de archivo puntual). */
    fun add(repoRoot: File, pathspec: String): GitResult {
        return try {
            Git.open(repoRoot).use { git ->
                git.add().addFilepattern(pathspec).call()
            }
            GitResult.Success("Added: $pathspec")
        } catch (e: Exception) {
            GitResult.Failure(e.message ?: "Couldn't add $pathspec")
        }
    }

    /** [authorName]/[authorEmail]: identidad de autoría a grabar en el
     *  commit (ver GitIdentityStore) — pasados explícitos vía PersonIdent
     *  en vez de dejar que JGit resuelva algo por su cuenta.
     *
     *  BUG encontrado (reportado: "hice un commit y lo etiquetó como
     *  root"): sin setAuthor/setCommitter, JGit intenta resolver
     *  user.name/user.email leyendo ~/.gitconfig — pero GitSystemConfig
     *  bloquea a propósito esa lectura (ver su doc de clase), así que JGit
     *  caía a un fallback basado en el usuario del sistema operativo de
     *  Android, resultando en un autor genérico tipo "root". Ahora
     *  EditorActivity siempre pasa la identidad guardada en
     *  GitIdentityStore (pidiéndola primero si no existe, ver
     *  requireGitIdentityThen) — [authorName]/[authorEmail] nulos solo
     *  pasarían si se llamara a esta función sin pasar por ese flujo. */
    fun commit(repoRoot: File, message: String, authorName: String?, authorEmail: String?): GitResult {
        return try {
            Git.open(repoRoot).use { git ->
                // setAllowEmpty(false) de JGit solo bloquea un commit vacío
                // cuando ya hay un commit padre contra el cual comparar —
                // confirmado probando contra un repo real: el PRIMER commit
                // de un repo (sin padre) se permite igual aunque no haya
                // nada en el índice, a diferencia de git real en la
                // terminal, que rechaza también ese caso ("nothing to
                // commit"). Se compensa chequeando el índice a mano antes
                // de intentar el commit, sin importar si hay padre o no.
                val status = git.status().call()
                if (status.added.isEmpty() && status.changed.isEmpty() && status.removed.isEmpty()) {
                    return GitResult.Failure("Nothing to commit (use \"git add\" first)")
                }
                // Esta app no ofrece firmar commits (no hay flujo de GPG/SSH
                // signing key en la UI), así que se fuerza sin firmar sin
                // importar lo que diga una config externa — confirmado con
                // pruebas reales: una config con gpg.format=ssh hacía
                // explotar CommitCommand con una excepción no relacionada al
                // commit en sí, por una opción que la app nunca ofreció
                // configurar.
                val cmd = git.commit().setSign(false).setAllowEmpty(false).setMessage(message)
                if (!authorName.isNullOrBlank() && !authorEmail.isNullOrBlank()) {
                    val ident = PersonIdent(authorName, authorEmail)
                    cmd.setAuthor(ident).setCommitter(ident)
                }
                val result = cmd.call()
                GitResult.Success("Commit created: ${result.id.abbreviate(7).name()} — $message")
            }
        } catch (e: Exception) {
            // JGit lanza EmptyCommitException (subclase de GitAPIException)
            // cuando no hay nada en el índice para commitear — se muestra
            // igual que cualquier otro resultado esperable, no como bug.
            GitResult.Failure(e.message ?: "Couldn't create the commit")
        }
    }

    /** BUG encontrado (reportado por el usuario: "no hay confirmación o
     *  alerta de error... no se refleja en github ni veo alerta de nada"):
     *  PushCommand.call() de JGit NO lanza excepción cuando el remoto
     *  RECHAZA la actualización de una rama (rama protegida, non-fast-
     *  forward porque el remoto tiene commits que el local no tiene,
     *  credenciales sin permiso de escritura, etc.) — ese resultado viaja
     *  dentro de cada PushResult, en el status de cada RemoteRefUpdate
     *  (getRemoteUpdates()), no como excepción. Antes esta función
     *  ignoraba por completo esos status y solo miraba
     *  PushResult.messages (texto libre del protocolo, que en un rechazo
     *  silencioso suele venir vacío) — con ifBlank cayendo a "push ok", se
     *  reportaba éxito aunque GitHub nunca hubiera aplicado el cambio.
     *  Ahora se recorren los RemoteRefUpdate de cada resultado: solo
     *  OK/UP_TO_DATE cuentan como éxito real; cualquier otro status
     *  (NON_EXISTING/REJECTED_… /AWAITING_REPORT/NOT_ATTEMPTED) hace que la
     *  función devuelva Failure con el motivo, en vez de mentir. */
    fun push(repoRoot: File, username: String?, token: String?): GitResult {
        return try {
            Git.open(repoRoot).use { git ->
                val cmd = git.push()
                credentialsProvider(username, token)?.let { cmd.setCredentialsProvider(it) }
                val results = cmd.call()
                val updates = results.flatMap { it.remoteUpdates }
                val rejected = updates.filter {
                    it.status != RemoteRefUpdate.Status.OK && it.status != RemoteRefUpdate.Status.UP_TO_DATE
                }
                if (rejected.isNotEmpty()) {
                    val reasons = rejected.joinToString("\n") { update ->
                        val detail = update.message?.takeIf { it.isNotBlank() }
                        "${update.remoteName}: ${update.status}" + if (detail != null) " ($detail)" else ""
                    }
                    return GitResult.Failure("Push rejected by the remote:\n$reasons")
                }
                val summary = results.joinToString("\n") { it.messages.ifBlank { "" } }.trim()
                GitResult.Success(summary.ifBlank { "Push completed" })
            }
        } catch (e: Exception) {
            GitResult.Failure(e.message ?: "Couldn't push")
        }
    }

    fun pull(repoRoot: File, username: String?, token: String?): GitResult {
        return try {
            Git.open(repoRoot).use { git ->
                val cmd = git.pull()
                credentialsProvider(username, token)?.let { cmd.setCredentialsProvider(it) }
                val result = cmd.call()
                if (result.isSuccessful) {
                    GitResult.Success("Pull completed (${result.mergeResult?.mergeStatus ?: "ok"})")
                } else {
                    GitResult.Failure("Pull had conflicts or failed: ${result.mergeResult?.mergeStatus ?: "unknown"}")
                }
            }
        } catch (e: Exception) {
            GitResult.Failure(e.message ?: "Couldn't pull")
        }
    }

    fun status(repoRoot: File): GitResult {
        return try {
            Git.open(repoRoot).use { git ->
                val s = git.status().call()
                val lines = mutableListOf<String>()
                s.added.forEach { lines.add("added:       $it") }
                s.changed.forEach { lines.add("changed:     $it") }
                s.removed.forEach { lines.add("removed:     $it") }
                s.missing.forEach { lines.add("missing:     $it") }
                s.modified.forEach { lines.add("not staged:  $it") }
                s.untracked.forEach { lines.add("untracked:   $it") }
                if (lines.isEmpty()) {
                    GitResult.Success("Nothing to commit, working tree clean")
                } else {
                    GitResult.Success(lines.joinToString("\n"))
                }
            }
        } catch (e: Exception) {
            GitResult.Failure(e.message ?: "Couldn't get the status")
        }
    }

    /** Sin [newBranchName]: lista las ramas locales marcando la actual con
     *  "*". Con [newBranchName]: crea una rama nueva desde HEAD (no la
     *  cambia; para cambiar de rama usar checkout). */
    fun branch(repoRoot: File, newBranchName: String?): GitResult {
        return try {
            Git.open(repoRoot).use { git ->
                if (newBranchName.isNullOrBlank()) {
                    val current = git.repository.branch
                    val names = git.branchList().call().map { it.name.removePrefix("refs/heads/") }
                    val lines = names.map { if (it == current) "* $it" else "  $it" }
                    GitResult.Success(lines.joinToString("\n"))
                } else {
                    git.branchCreate().setName(newBranchName).call()
                    GitResult.Success("Branch created: $newBranchName")
                }
            }
        } catch (e: Exception) {
            GitResult.Failure(e.message ?: "Couldn't complete the branch operation")
        }
    }

    /** [target] es el nombre de rama (o commit) al que cambiar. */
    fun checkout(repoRoot: File, target: String): GitResult {
        return try {
            Git.open(repoRoot).use { git ->
                git.checkout().setName(target).call()
                GitResult.Success("Switched to $target")
            }
        } catch (e: Exception) {
            GitResult.Failure(e.message ?: "Couldn't switch to $target")
        }
    }

    /** [branchName] es la rama a fusionar DENTRO de la rama actual. */
    fun merge(repoRoot: File, branchName: String): GitResult {
        return try {
            Git.open(repoRoot).use { git ->
                val ref = git.repository.findRef(branchName)
                    ?: return GitResult.Failure("Branch \"$branchName\" not found")
                val result = git.merge().include(ref).call()
                if (result.mergeStatus.isSuccessful) {
                    GitResult.Success("Merge of \"$branchName\" completed (${result.mergeStatus})")
                } else {
                    GitResult.Failure("Merge had conflicts: ${result.mergeStatus}. Resolve the conflicts manually in the marked files.")
                }
            }
        } catch (e: Exception) {
            GitResult.Failure(e.message ?: "Couldn't complete the merge")
        }
    }

    /** Igual a [branch] sin [newBranchName], pero estructurado en vez de
     *  texto plano, para que GitActionsController pueda poblar un dropdown
     *  sin tener que parsear el formato pensado para consola. */
    fun listBranches(repoRoot: File): List<BranchInfo> {
        return try {
            Git.open(repoRoot).use { git ->
                val current = git.repository.branch
                git.branchList().call().map {
                    val name = it.name.removePrefix("refs/heads/")
                    BranchInfo(name, name == current)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun fetch(repoRoot: File, username: String?, token: String?): GitResult {
        return try {
            Git.open(repoRoot).use { git ->
                val cmd = git.fetch()
                credentialsProvider(username, token)?.let { cmd.setCredentialsProvider(it) }
                val result = cmd.call()
                val summary = result.trackingRefUpdates.joinToString("\n") {
                    "${it.remoteName} -> ${it.localName}: ${it.result}"
                }
                GitResult.Success(summary.ifBlank { "Already up to date, nothing new to fetch" })
            }
        } catch (e: Exception) {
            GitResult.Failure(e.message ?: "Couldn't fetch")
        }
    }

    /** [maxCount] nulo = 10 commits (mismo default razonable para no volcar
     *  todo el historial en una consola chica de celular). */
    fun log(repoRoot: File, maxCount: Int?): GitResult {
        return try {
            Git.open(repoRoot).use { git ->
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                val commits = git.log().setMaxCount(maxCount ?: 10).call()
                val lines = commits.map { c ->
                    val date = dateFormat.format(c.authorIdent.getWhen())
                    val shortMsg = c.shortMessage
                    "${c.id.abbreviate(7).name()}  $date  ${c.authorIdent.name} — $shortMsg"
                }.toList()
                if (lines.isEmpty()) {
                    GitResult.Success("No commits yet")
                } else {
                    GitResult.Success(lines.joinToString("\n"))
                }
            }
        } catch (e: Exception) {
            GitResult.Failure(e.message ?: "Couldn't get the log")
        }
    }

    /** [staged] true: diff del índice contra HEAD ("git diff --staged").
     *  [staged] false: diff del working tree contra el índice ("git
     *  diff"). Solo se invoca cuando el usuario lo pide explícito (botón
     *  "Diff" o "git diff" en consola) — antes de un commit se muestra en
     *  cambio un resumen corto (ver stagedFileNames), un diff completo
     *  volcado línea por línea resultaba ilegible en una consola chica de
     *  celular. */
    fun diff(repoRoot: File, staged: Boolean): GitResult {
        return try {
            Git.open(repoRoot).use { git ->
                val out = ByteArrayOutputStream()
                DiffFormatter(out).use { formatter ->
                    formatter.setRepository(git.repository)
                    val entries = git.diff().setCached(staged).call()
                    entries.forEach { formatter.format(it) }
                }
                val text = out.toString("UTF-8")
                GitResult.Success(text.ifBlank { "No changes to show" })
            }
        } catch (e: Exception) {
            GitResult.Failure(e.message ?: "Couldn't get the diff")
        }
    }

    /** Lista de nombres de archivo en stage (added/changed/removed),
     *  sin formato de texto — para armar un resumen corto ("3 archivos
     *  modificados") antes de confirmar un commit, en vez de volcar el
     *  diff completo línea por línea en una consola de celular. */
    fun stagedFileNames(repoRoot: File): List<String> {
        return try {
            Git.open(repoRoot).use { git ->
                val s = git.status().call()
                (s.added + s.changed + s.removed).distinct().sorted()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** [target] nulo = HEAD (mismo default que git real: "git reset" sin
     *  argumento resetea al commit actual, solo cambiando el modo). */
    fun reset(repoRoot: File, mode: GitConsoleParser.ResetMode, target: String?): GitResult {
        return try {
            Git.open(repoRoot).use { git ->
                val jgitMode = when (mode) {
                    GitConsoleParser.ResetMode.SOFT -> ResetCommand.ResetType.SOFT
                    GitConsoleParser.ResetMode.MIXED -> ResetCommand.ResetType.MIXED
                    GitConsoleParser.ResetMode.HARD -> ResetCommand.ResetType.HARD
                }
                val cmd = git.reset().setMode(jgitMode)
                if (!target.isNullOrBlank()) cmd.setRef(target)
                cmd.call()
                GitResult.Success("Reset (${mode.name.lowercase()}) completed" + if (target != null) " to $target" else "")
            }
        } catch (e: Exception) {
            GitResult.Failure(e.message ?: "Couldn't reset")
        }
    }

    /** [pathspec] tal como lo escribió el usuario después de "git rm"
     *  (quita del índice Y del working tree, igual que git real sin
     *  --cached — esta app no expone --cached todavía). */
    fun rm(repoRoot: File, pathspec: String): GitResult {
        return try {
            Git.open(repoRoot).use { git ->
                git.rm().addFilepattern(pathspec).call()
            }
            GitResult.Success("Removed: $pathspec")
        } catch (e: Exception) {
            GitResult.Failure(e.message ?: "Couldn't remove $pathspec")
        }
    }

    /** [branchName] es la rama sobre la cual rebasar la rama actual. JGit no
     *  soporta rebase interactivo (edit/squash/reorder) — solo el
     *  equivalente a "git rebase <rama>" simple. */
    fun rebase(repoRoot: File, branchName: String): GitResult {
        return try {
            Git.open(repoRoot).use { git ->
                if (git.repository.findRef(branchName) == null) {
                    return GitResult.Failure("Branch \"$branchName\" not found")
                }
                // setUpstream(String) en vez de pasar el Ref encontrado
                // arriba: RebaseCommand.setUpstream() solo sobrecarga
                // String/AnyObjectId/RevCommit, no Ref — error de
                // compilación real (JGit resuelve el nombre internamente
                // igual que si se le pasara el Ref ya buscado).
                val result = git.rebase().setUpstream(branchName).call()
                if (result.status.isSuccessful) {
                    GitResult.Success("Rebase onto \"$branchName\" completed (${result.status})")
                } else {
                    GitResult.Failure(
                        "Rebase had conflicts or failed: ${result.status}. Resolve the conflicts manually " +
                            "or abort with \"git rebase --abort\" (not yet supported by this console; " +
                            "use another git client if you need to abort)."
                    )
                }
            }
        } catch (e: Exception) {
            GitResult.Failure(e.message ?: "Couldn't complete the rebase")
        }
    }

    /** Crea un stash con los cambios actuales ("git stash" / "git stash
     *  push" sin argumentos). */
    fun stashCreate(repoRoot: File): GitResult {
        return try {
            Git.open(repoRoot).use { git ->
                val status = git.status().call()
                if (status.added.isEmpty() && status.changed.isEmpty() && status.removed.isEmpty() &&
                    status.modified.isEmpty() && status.missing.isEmpty()
                ) {
                    return GitResult.Failure("No changes to save in the stash")
                }
                val stashId = git.stashCreate().call()
                GitResult.Success("Stash created: ${stashId.abbreviate(7).name()}")
            }
        } catch (e: Exception) {
            GitResult.Failure(e.message ?: "Couldn't create the stash")
        }
    }

    /** Aplica y elimina el stash más reciente ("git stash pop"). */
    fun stashPop(repoRoot: File): GitResult {
        return try {
            Git.open(repoRoot).use { git ->
                git.stashApply().call()
                git.stashDrop().call()
                GitResult.Success("Stash applied and removed")
            }
        } catch (e: Exception) {
            GitResult.Failure(e.message ?: "Couldn't apply the stash (conflict? no stash?)")
        }
    }

    fun stashList(repoRoot: File): GitResult {
        return try {
            Git.open(repoRoot).use { git ->
                val stashes = git.stashList().call().toList()
                if (stashes.isEmpty()) {
                    GitResult.Success("No stashes saved")
                } else {
                    val lines = stashes.mapIndexed { index, c ->
                        "stash@{$index}: ${c.id.abbreviate(7).name()} — ${c.shortMessage}"
                    }
                    GitResult.Success(lines.joinToString("\n"))
                }
            }
        } catch (e: Exception) {
            GitResult.Failure(e.message ?: "Couldn't list the stashes")
        }
    }

    /** Elimina el stash más reciente sin aplicarlo ("git stash drop"). */
    fun stashDrop(repoRoot: File): GitResult {
        return try {
            Git.open(repoRoot).use { git ->
                git.stashDrop().call()
            }
            GitResult.Success("Most recent stash removed")
        } catch (e: Exception) {
            GitResult.Failure(e.message ?: "Couldn't remove the stash (is there one?)")
        }
    }
}
