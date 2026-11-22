package com.codemobile.git

/**
 * Parsea una línea de texto escrita en el campo de comandos git de la
 * consola (ver view_console.xml / EditorActivity.onGitCommandSubmit) y
 * decide qué llamada de GitRepository corresponde. Separado de
 * EditorActivity para que el parseo de texto (tokenizar, reconocer
 * flags) no se mezcle con el wiring de la UI.
 *
 * No interpreta nada de shell real (sin pipes, sin variables, sin
 * comillas anidadas) — cubre clone, init, add, commit, push, pull,
 * status, branch, checkout, merge, fetch, log, diff, reset, rm, rebase y
 * stash (create/pop/list/drop).
 */
object GitConsoleParser {

    /** Modo de "git reset" — mismos tres niveles que git real. */
    enum class ResetMode { SOFT, MIXED, HARD }

    /** Subcomando de "git stash" — Create es el "git stash" sin argumentos
     *  (equivalente a "git stash push" en git real). */
    enum class StashAction { CREATE, POP, LIST, DROP }

    sealed class ParsedCommand {
        data class Clone(val url: String) : ParsedCommand()
        object Init : ParsedCommand()
        data class Add(val pathspec: String) : ParsedCommand()
        data class Commit(val message: String) : ParsedCommand()
        object Push : ParsedCommand()
        object Pull : ParsedCommand()
        object Status : ParsedCommand()
        data class Branch(val newBranchName: String?) : ParsedCommand()
        data class Checkout(val target: String) : ParsedCommand()
        data class Merge(val branchName: String) : ParsedCommand()
        object Fetch : ParsedCommand()
        /** [count] nulo = cantidad default (ver GitRepository.log). */
        data class Log(val count: Int?) : ParsedCommand()
        /** [staged] true = "git diff --staged" (contra el índice); false =
         *  "git diff" (working tree contra el índice). */
        data class Diff(val staged: Boolean) : ParsedCommand()
        data class Reset(val mode: ResetMode, val target: String?) : ParsedCommand()
        data class Rm(val pathspec: String) : ParsedCommand()
        data class Rebase(val branchName: String) : ParsedCommand()
        data class Stash(val action: StashAction) : ParsedCommand()
        data class Unsupported(val commandWord: String) : ParsedCommand()
        /** La línea no empieza con "git " (no debería llegar nunca si el
         *  caller ya filtró por el prefijo, pero la función es segura de
         *  llamar con cualquier texto). */
        object NotGitCommand : ParsedCommand()
        data class ParseError(val reason: String) : ParsedCommand()
    }

    private val SUPPORTED_COMMANDS = listOf(
        "clone", "init", "add", "commit", "push", "pull", "status", "branch", "checkout", "merge",
        "fetch", "log", "diff", "reset", "rm", "rebase", "stash"
    )

    fun supportedCommandsHelp(): String =
        "Available commands: ${SUPPORTED_COMMANDS.joinToString(", ") { "git $it" }}"

    fun parse(line: String): ParsedCommand {
        val tokens = tokenize(line.trim())
        if (tokens.isEmpty() || tokens[0] != "git") return ParsedCommand.NotGitCommand
        if (tokens.size < 2) return ParsedCommand.ParseError("Missing command after \"git\"")

        val commandWord = tokens[1]
        val args = tokens.drop(2)

        return when (commandWord) {
            "clone" -> {
                val url = args.firstOrNull { !it.startsWith("-") }
                    ?: return ParsedCommand.ParseError("Usage: git clone <url>")
                ParsedCommand.Clone(url)
            }
            "init" -> ParsedCommand.Init
            "add" -> {
                val pathspec = args.firstOrNull()
                    ?: return ParsedCommand.ParseError("Usage: git add <file|.>")
                ParsedCommand.Add(pathspec)
            }
            "commit" -> {
                val message = extractFlagValue(args, "-m")
                    ?: return ParsedCommand.ParseError("Usage: git commit -m \"message\"")
                ParsedCommand.Commit(message)
            }
            "push" -> ParsedCommand.Push
            "pull" -> ParsedCommand.Pull
            "status" -> ParsedCommand.Status
            "branch" -> ParsedCommand.Branch(args.firstOrNull { !it.startsWith("-") })
            "checkout" -> {
                val target = args.firstOrNull()
                    ?: return ParsedCommand.ParseError("Usage: git checkout <branch>")
                ParsedCommand.Checkout(target)
            }
            "merge" -> {
                val branchName = args.firstOrNull()
                    ?: return ParsedCommand.ParseError("Usage: git merge <branch>")
                ParsedCommand.Merge(branchName)
            }
            "fetch" -> ParsedCommand.Fetch
            "log" -> {
                // Acepta tanto "git log -n 5" como "git log 5" (JGit no tiene
                // un flag -n real, pero se soporta la sintaxis más conocida
                // de la terminal además del número suelto).
                val count = (extractFlagValue(args, "-n") ?: args.firstOrNull { !it.startsWith("-") })
                    ?.toIntOrNull()
                if (count != null && count <= 0) {
                    return ParsedCommand.ParseError("Commit count must be greater than 0")
                }
                ParsedCommand.Log(count)
            }
            "diff" -> {
                val staged = args.any { it == "--staged" || it == "--cached" }
                ParsedCommand.Diff(staged)
            }
            "reset" -> {
                val mode = when {
                    args.any { it == "--soft" } -> ResetMode.SOFT
                    args.any { it == "--hard" } -> ResetMode.HARD
                    else -> ResetMode.MIXED
                }
                val target = args.firstOrNull { !it.startsWith("-") }
                ParsedCommand.Reset(mode, target)
            }
            "rm" -> {
                val pathspec = args.firstOrNull { !it.startsWith("-") }
                    ?: return ParsedCommand.ParseError("Usage: git rm <file>")
                ParsedCommand.Rm(pathspec)
            }
            "rebase" -> {
                val branchName = args.firstOrNull()
                    ?: return ParsedCommand.ParseError("Usage: git rebase <branch>")
                ParsedCommand.Rebase(branchName)
            }
            "stash" -> {
                val sub = args.firstOrNull()
                val action = when (sub) {
                    null, "push", "save" -> StashAction.CREATE
                    "pop" -> StashAction.POP
                    "list" -> StashAction.LIST
                    "drop" -> StashAction.DROP
                    else -> return ParsedCommand.ParseError(
                        "Usage: git stash [push|pop|list|drop]"
                    )
                }
                ParsedCommand.Stash(action)
            }
            else -> ParsedCommand.Unsupported(commandWord)
        }
    }

    /** Busca [flag] en [args] y devuelve el token siguiente como valor,
     *  soportando que ese valor esté entre comillas dobles con espacios
     *  adentro (ej. -m "primer commit"), ya separado en un solo token por
     *  tokenize(). */
    private fun extractFlagValue(args: List<String>, flag: String): String? {
        val index = args.indexOf(flag)
        if (index == -1 || index + 1 >= args.size) return null
        return args[index + 1]
    }

    /** Tokeniza respetando comillas dobles como un solo token (para que
     *  -m "primer commit" sea un token, no dos). No soporta comillas
     *  simples ni escapes dentro de las comillas — alcanza para el uso
     *  esperado (mensajes de commit entre comillas dobles). */
    fun tokenize(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ' ' && !inQuotes -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(c)
            }
            i++
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }
}
