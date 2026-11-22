package com.codemobile.editor

import java.io.File

/**
 * Regexes y helpers para parsear sintaxis de import/export de ES modules
 * en JS, usados tanto por GotoResolver (navegación con la tecla G) como
 * por JsModuleLoader (resolución real de imports al EJECUTAR con Rhino).
 * Extraído de GotoResolver sin cambiar comportamiento, para que ambos
 * lugares compartan exactamente la misma noción de qué es un import válido
 * y cómo se resuelve su ruta — evita que un fix futuro se aplique a un
 * lado y no al otro.
 */
object JsImportSyntax {

    val JS_IMPORT = Regex("""^\s*import\s+.*\sfrom\s+["']([^"']+)["']""")

    // Nombres reales importados en una línea de import JS: el default
    // ("import def from ..."), o cada nombre dentro de "{ a, b as c }" —
    // usado para resolver a qué import corresponde un alias usado como
    // "alias.word(" (ver GotoResolver.resolveJs), en vez de un substring
    // crudo sobre la línea completa.
    val JS_IMPORT_DEFAULT_NAME = Regex("""^\s*import\s+(\w+)\s*(?:,|\sfrom\b)""")
    val JS_IMPORT_NAMED_BLOCK = Regex("""\{([^}]*)\}""")

    /** true si la línea de import [importLine] trae, como nombre LOCAL
     *  (el que el resto del archivo usa para referirse a lo importado),
     *  exactamente [name] — ya sea el default ("import name from ...") o
     *  uno de los nombres dentro de "{ a, b as name }". Comparación
     *  exacta de identificador, no substring crudo sobre la línea. */
    fun importLineImportsName(importLine: String, name: String): Boolean {
        JS_IMPORT_DEFAULT_NAME.find(importLine)?.let { if (it.groupValues[1] == name) return true }
        val namedBlock = JS_IMPORT_NAMED_BLOCK.find(importLine)?.groupValues?.get(1) ?: return false
        return namedBlock.split(',').any { entry ->
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) return@any false
            val localName = trimmed.substringAfterLast(" as ").trim()
            localName == name
        }
    }

    /** "./utils" o "../lib/utils" resuelto desde la carpeta del archivo
     *  que importa, probando extensiones comunes de JS en orden; también
     *  prueba "<ruta>/index.<ext>" si la ruta resuelve a una carpeta. */
    fun resolveJsRelativePath(sourceFile: File, importPath: String): File? {
        val baseDir = sourceFile.parentFile ?: return null
        // java.io.File no tiene normalize() (eso es de java.nio.file.Path):
        // se resuelve ".."/"." a mano, segmento por segmento, sobre el path
        // ya absoluto de baseDir + importPath.
        val rawSegments = (baseDir.path.split(File.separatorChar) + importPath.split('/'))
            .filter { it.isNotEmpty() }
        val normalizedSegments = mutableListOf<String>()
        for (segment in rawSegments) {
            when (segment) {
                "." -> {}
                ".." -> if (normalizedSegments.isNotEmpty()) normalizedSegments.removeAt(normalizedSegments.size - 1)
                else -> normalizedSegments.add(segment)
            }
        }
        val target = File(File.separator + normalizedSegments.joinToString(File.separator))
        val extensions = listOf("js", "mjs", "cjs")
        if (target.isFile) return target
        val targetParent = target.parentFile ?: return null
        for (ext in extensions) {
            val withExt = File(targetParent, "${target.name}.$ext")
            if (withExt.isFile) return withExt
        }
        if (target.isDirectory) {
            for (ext in extensions) {
                val index = File(target, "index.$ext")
                if (index.isFile) return index
            }
        }
        return null
    }
}
