package com.codemobile.editor

import com.codemobile.files.FileRepository
import java.io.File

/**
 * Resuelve la tecla G (drag-abajo, DragAction.GOTO, ver KeyboardLayouts):
 * dado el identificador bajo el cursor (o seleccionado) en el archivo
 * activo, decide a qué archivo saltar y, si corresponde, a qué línea
 * dentro de él — sin ningún parser real de cada lenguaje (no hay uno en
 * el proyecto), solo heurísticas por regex sobre el texto crudo. Objeto
 * puro, sin Android ni vistas: ControlsController es quien llama a esto y
 * después usa el resultado contra TabsController/CodeEditText.
 *
 * Diseño acordado explícitamente con el usuario (Paso 7, extensión):
 * - Nunca hay un cuadro de búsqueda ni una lista para elegir: si no hay
 *   match exacto y sin ambigüedad en cada paso, GotoResolver no hace nada
 *   (GotoResult.None) — ni aviso, ni mejor intento.
 * - La "raíz de proyecto" para Python/Kotlin es FileRepository.
 *   projectRootFor(archivo activo) — misma función que usa PythonRunner.run
 *   para resolver imports reales al EJECUTAR (ver su doc): la raíz del
 *   repo git que contiene al archivo, o rootDir() entero si no hay repo.
 * - JS es el único caso sin ninguna raíz: su ruta de import ya es
 *   relativa al propio archivo que importa (igual que Node/ES modules),
 *   así que se resuelve sin tocar rootDir()/findRepoRoot en absoluto.
 */
object GotoResolver {

    sealed class GotoResult {
        object None : GotoResult()
        data class OpenFile(val file: File, val line: Int? = null) : GotoResult()
    }

    /** [sourceFile] es el archivo activo (null si es un archivo "sin
     *  guardar" — sin ruta real no hay forma de resolver imports
     *  relativos ni raíz de repo, así que siempre cae a None). [fullText]
     *  es el contenido completo del editor activo (no necesariamente
     *  savedContent: el usuario puede estar editando sin guardar).
     *  [wordStart]/[wordEnd] son los índices del identificador bajo el
     *  cursor o de la selección activa dentro de [fullText] (ver
     *  CodeEditText.wordOrSelectionAtCursor). */
    fun resolve(
        lang: FileRepository.Lang,
        sourceFile: File?,
        fullText: String,
        wordStart: Int,
        wordEnd: Int
    ): GotoResult {
        if (sourceFile == null) return GotoResult.None
        if (wordStart < 0 || wordEnd <= wordStart || wordEnd > fullText.length) return GotoResult.None
        val word = fullText.substring(wordStart, wordEnd)
        if (word.isBlank()) return GotoResult.None

        val lineStart = fullText.lastIndexOf('\n', wordStart - 1).let { if (it == -1) 0 else it + 1 }
        val lineEndIdx = fullText.indexOf('\n', wordEnd).let { if (it == -1) fullText.length else it }
        val line = fullText.substring(lineStart, lineEndIdx)

        return when (lang) {
            FileRepository.Lang.PYTHON -> resolvePython(sourceFile, fullText, word, line)
            FileRepository.Lang.KOTLIN -> resolveKotlin(sourceFile, fullText, word, line)
            FileRepository.Lang.JAVASCRIPT -> resolveJs(sourceFile, fullText, word, line)
            else -> GotoResult.None
        }
    }

    // ---------- Python ----------

    private val PY_IMPORT = Regex("""^\s*import\s+([\w.]+)""")
    private val PY_FROM_IMPORT = Regex("""^\s*from\s+([\w.]+)\s+import\s+(.+)""")
    private val PY_DEF = Regex("""^\s*def\s+%s\s*\(""")

    private fun resolvePython(sourceFile: File, fullText: String, word: String, line: String): GotoResult {
        val projectRoot = FileRepository.projectRootFor(sourceFile)

        // Caso 1: el cursor está sobre el propio módulo del import ("import
        // paquete.sub" o "from paquete.sub import X" con el cursor en
        // "paquete"/"sub") — el import entero siempre contiene a `word`
        // como uno de sus segmentos si es ese el caso.
        val plainImportMatch = PY_IMPORT.find(line)
        val fromImportMatch = PY_FROM_IMPORT.find(line)
        val importMatch = plainImportMatch ?: fromImportMatch
        if (importMatch != null) {
            val modulePath = importMatch.groupValues[1]
            val segments = modulePath.split('.')
            if (segments.contains(word)) {
                val file = resolvePythonModule(projectRoot, modulePath)
                if (file != null) return GotoResult.OpenFile(file)
            }
            // Cursor en uno de los nombres importados ("from paquete.sub
            // import X, Y" con el cursor en Y): buscar "def Y(" dentro del
            // módulo ya resuelto, no en todo el árbol. Limitación conocida,
            // fuera del alcance acordado: "Y as Z" con cursor en el alias
            // Z no matchea acá (el nombre real a buscar en el módulo es
            // Y, no Z) — caso no cubierto, cae a None como cualquier otro
            // caso sin match.
            if (fromImportMatch != null) {
                val importedNames = fromImportMatch.groupValues[2].split(',').map { it.trim().substringBefore(" as ") }
                if (importedNames.any { it == word }) {
                    val file = resolvePythonModule(projectRoot, modulePath) ?: return GotoResult.None
                    val targetLine = findDefLine(file, word, PY_DEF) ?: return GotoResult.None
                    return GotoResult.OpenFile(file, targetLine)
                }
            }
        }

        // Caso 2: llamada simple "word(" — archivo actual primero, luego
        // los módulos ya importados en este archivo.
        if (Regex("""\b${Regex.escape(word)}\s*\(""").containsMatchIn(line)) {
            findDefLineInText(fullText, word, PY_DEF)?.let { return GotoResult.OpenFile(sourceFile, it) }
            for (m in PY_IMPORT.findAll(fullText) + PY_FROM_IMPORT.findAll(fullText)) {
                val modulePath = m.groupValues[1]
                val file = resolvePythonModule(projectRoot, modulePath) ?: continue
                val targetLine = findDefLine(file, word, PY_DEF) ?: continue
                return GotoResult.OpenFile(file, targetLine)
            }
            return GotoResult.None
        }

        // Caso 3: "lib.word(" — lib resuelto por import, word buscado
        // adentro de ese módulo.
        val dotCallMatch = Regex("""(\w+)\.${Regex.escape(word)}\s*\(""").find(line)
        if (dotCallMatch != null) {
            val libAlias = dotCallMatch.groupValues[1]
            // "import modulo as alias" con ese alias exacto, o "import
            // modulo" cuyo último segmento ya ES el alias usado (sin
            // "as") — nunca "cualquier import del archivo".
            val aliasedImport = Regex("""^\s*import\s+([\w.]+)\s+as\s+${Regex.escape(libAlias)}\b""")
                .find(fullText)
            val modulePath = if (aliasedImport != null) {
                aliasedImport.groupValues[1]
            } else {
                val plainMatch = PY_IMPORT.findAll(fullText)
                    .firstOrNull { it.groupValues[1].substringAfterLast('.') == libAlias }
                    ?: return GotoResult.None
                plainMatch.groupValues[1]
            }
            val file = resolvePythonModule(projectRoot, modulePath) ?: return GotoResult.None
            val targetLine = findDefLine(file, word, PY_DEF) ?: return GotoResult.None
            return GotoResult.OpenFile(file, targetLine)
        }

        return GotoResult.None
    }

    /** "paquete.sub" -> <root>/paquete/sub.py, o <root>/paquete/sub/__init__.py
     *  si el primero no existe. Sin búsqueda por nombre en ningún otro
     *  lado: si ninguna de las dos rutas existe, no hay resultado. */
    private fun resolvePythonModule(root: File, modulePath: String): File? {
        val relative = modulePath.replace('.', File.separatorChar)
        val asFile = File(root, "$relative.py")
        if (asFile.isFile) return asFile
        val asPackage = File(root, "$relative${File.separator}__init__.py")
        if (asPackage.isFile) return asPackage
        return null
    }

    // ---------- Kotlin ----------

    private val KT_IMPORT = Regex("""^\s*import\s+([\w.]+)""")

    // Prefijos de source root estándar de Gradle/Android, probados en orden
    // (ver findKotlinFileByFqName): un proyecto Kotlin real que compila en
    // cualquier toolchain estándar EXIGE que la carpeta del archivo refleje
    // su paquete declarado (com.foo.bar -> com/foo/bar/Clase.kt) — no es una
    // convención opcional, es una regla del compilador. Por eso no hace
    // falta ningún recorrido recursivo ni desambiguación por lectura de
    // "package": la ruta exacta ya es la prueba de que es el archivo
    // correcto. Un archivo cuya carpeta no coincida con su "package"
    // declarado no compilaría en ningún entorno Kotlin real, así que no es
    // un caso que valga la pena cubrir aquí.
    private val KOTLIN_SOURCE_PREFIXES = listOf(
        "app/src/main/java/",
        "app/src/main/kotlin/",
        "src/main/java/",
        "src/main/kotlin/",
        "" // sin prefijo: el paquete cuelga directo de la raíz del proyecto
    )

    private fun resolveKotlin(sourceFile: File, fullText: String, word: String, line: String): GotoResult {
        val projectRoot = FileRepository.projectRootFor(sourceFile)

        val importMatch = KT_IMPORT.find(line)
        if (importMatch != null) {
            val fqName = importMatch.groupValues[1]
            if (fqName.split('.').contains(word)) {
                // Sin importar si el cursor está en el último segmento (la
                // clase importada) o en uno intermedio (un segmento del
                // paquete): el FQN completo es el mismo, y es lo único que
                // hace falta para encontrar la ruta real del archivo.
                val file = findKotlinFileByFqName(projectRoot, fqName)
                if (file != null) return GotoResult.OpenFile(file)
            }
        }

        // Llamada simple "word(" — archivo actual primero, luego los
        // archivos declarados en los imports de este archivo (mismo
        // criterio que Python: no se escanea todo el árbol por nombre de
        // función, solo los módulos ya referenciados).
        if (Regex("""\b${Regex.escape(word)}\s*\(""").containsMatchIn(line)) {
            findFunLineInText(fullText, word)?.let { return GotoResult.OpenFile(sourceFile, it) }
            for (m in KT_IMPORT.findAll(fullText)) {
                val fqName = m.groupValues[1]
                val file = findKotlinFileByFqName(projectRoot, fqName) ?: continue
                val targetLine = findDefLine(file, word, Regex("""^\s*fun\s+%s\s*\(""")) ?: continue
                return GotoResult.OpenFile(file, targetLine)
            }
            return GotoResult.None
        }

        // "Obj.word(" — Obj resuelto igual que arriba por import, word
        // buscado dentro de ese archivo.
        val dotCallMatch = Regex("""(\w+)\.${Regex.escape(word)}\s*\(""").find(line)
        if (dotCallMatch != null) {
            val objName = dotCallMatch.groupValues[1]
            val importMatchForObj = KT_IMPORT.findAll(fullText).firstOrNull { it.groupValues[1].substringAfterLast('.') == objName }
                ?: return GotoResult.None
            val fqName = importMatchForObj.groupValues[1]
            val file = findKotlinFileByFqName(projectRoot, fqName) ?: return GotoResult.None
            val targetLine = findDefLine(file, word, Regex("""^\s*fun\s+%s\s*\(""")) ?: return GotoResult.None
            return GotoResult.OpenFile(file, targetLine)
        }

        return GotoResult.None
    }

    /** Resuelve un FQN de import ("com.foo.bar.Clase") a su archivo real,
     *  probando los prefijos de source root estándar (ver
     *  KOTLIN_SOURCE_PREFIXES) en orden: cada intento es solo construir una
     *  ruta y comprobar su existencia (una sola llamada .isFile, ninguna
     *  lectura de directorio ni de contenido) — sin recorrido recursivo del
     *  árbol ni desambiguación por lectura de "package", porque la ruta
     *  exacta de carpetas ya es, por sí sola, la prueba de que es el
     *  archivo correcto (ver comentario de KOTLIN_SOURCE_PREFIXES). Se
     *  detiene en el primer prefijo cuyo archivo exista. */
    private fun findKotlinFileByFqName(root: File, fqName: String): File? {
        val relativePath = fqName.replace('.', File.separatorChar) + ".kt"
        for (prefix in KOTLIN_SOURCE_PREFIXES) {
            val candidate = File(root, prefix + relativePath)
            if (candidate.isFile) return candidate
        }
        return null
    }

    // ---------- JS ----------
    // Regexes y resolución de rutas de import compartidas con JsModuleLoader
    // (ejecución real) en JsImportSyntax — ver ese archivo.

    private fun resolveJs(sourceFile: File, fullText: String, word: String, line: String): GotoResult {
        val importMatch = JsImportSyntax.JS_IMPORT.find(line)
        if (importMatch != null) {
            val importPath = importMatch.groupValues[1]
            // Ruta literal relativa al propio archivo (igual que Node/ES
            // modules): solo se resuelven imports relativos ("./", "../"),
            // nunca paquetes de node_modules (no hay instalador de
            // paquetes en este proyecto).
            if (importPath.startsWith("./") || importPath.startsWith("../")) {
                val resolved = JsImportSyntax.resolveJsRelativePath(sourceFile, importPath) ?: return GotoResult.None
                return GotoResult.OpenFile(resolved)
            }
            return GotoResult.None
        }

        if (Regex("""\b${Regex.escape(word)}\s*\(""").containsMatchIn(line)) {
            findJsFunctionLineInText(fullText, word)?.let { return GotoResult.OpenFile(sourceFile, it) }
            for (m in JsImportSyntax.JS_IMPORT.findAll(fullText)) {
                val importPath = m.groupValues[1]
                if (!importPath.startsWith("./") && !importPath.startsWith("../")) continue
                val resolved = JsImportSyntax.resolveJsRelativePath(sourceFile, importPath) ?: continue
                val targetLine = findJsFunctionLine(resolved, word) ?: continue
                return GotoResult.OpenFile(resolved, targetLine)
            }
            return GotoResult.None
        }

        val dotCallMatch = Regex("""(\w+)\.${Regex.escape(word)}\s*\(""").find(line)
        if (dotCallMatch != null) {
            val alias = dotCallMatch.groupValues[1]
            val importForAlias = JsImportSyntax.JS_IMPORT.findAll(fullText)
                .firstOrNull { JsImportSyntax.importLineImportsName(it.value, alias) }
                ?: return GotoResult.None
            val importPath = importForAlias.groupValues[1]
            if (!importPath.startsWith("./") && !importPath.startsWith("../")) return GotoResult.None
            val resolved = JsImportSyntax.resolveJsRelativePath(sourceFile, importPath) ?: return GotoResult.None
            val targetLine = findJsFunctionLine(resolved, word) ?: return GotoResult.None
            return GotoResult.OpenFile(resolved, targetLine)
        }

        return GotoResult.None
    }

    // ---------- Helpers comunes ----------

    private fun findDefLine(file: File, name: String, pattern: Regex): Int? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return findDefLineInText(text, name, pattern)
    }

    private fun findDefLineInText(text: String, name: String, pattern: Regex): Int? {
        val specific = Regex(pattern.pattern.replace("%s", Regex.escape(name)))
        val lines = text.split('\n')
        for ((index, l) in lines.withIndex()) {
            if (specific.containsMatchIn(l)) return index
        }
        return null
    }

    private fun findFunLineInText(text: String, name: String): Int? =
        findDefLineInText(text, name, Regex("""^\s*fun\s+%s\s*\("""))

    private fun findJsFunctionLine(file: File, name: String): Int? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return findJsFunctionLineInText(text, name)
    }

    private fun findJsFunctionLineInText(text: String, name: String): Int? {
        val pattern = Regex(
            """^\s*(?:export\s+)?(?:function\s+%s\s*\(|const\s+%s\s*=)"""
                .replace("%s", Regex.escape(name))
        )
        val lines = text.split('\n')
        for ((index, l) in lines.withIndex()) {
            if (pattern.containsMatchIn(l)) return index
        }
        return null
    }
}
