package com.codemobile.exec

import com.codemobile.editor.JsImportSyntax
import java.io.File

/**
 * Preprocesador texto-a-texto que resuelve imports/exports de ES modules
 * (el estándar actual de JS, ver import/export vs CommonJS) ANTES de
 * pasarle el código a Rhino — Rhino no entiende esa sintaxis nativamente
 * (falla con un SyntaxError de parseo, no un error de "módulo no
 * encontrado"). Mismo enfoque pragmático por regex que ya usa GotoResolver
 * para Python/Kotlin/JS: sin parser real, cubre el caso estándar.
 *
 * Cada módulo importado se "hoistea" a una única constante de nivel
 * superior (__mod_N__), evaluada una sola vez y cacheada por ruta absoluta
 * — así dos imports del mismo archivo comparten el mismo estado de módulo
 * y sus efectos secundarios top-level no se repiten, igual que el caché de
 * módulos real de ES modules/Node. Un ciclo de imports no cuelga: el
 * módulo en medio de resolverse se trata como un objeto vacío.
 *
 * Soporta: "export function f(...)", "export const/let/var x = ...",
 * "export default function f(...)", "export default <expr>", e import
 * default/named/alias correspondientes. Fuera de alcance (igual que las
 * demás resoluciones por regex del proyecto): "export class", "export *
 * from", re-exports encadenados, imports dinámicos. Un import que no
 * resuelve a un archivo real se deja tal cual en el texto — Rhino lo
 * rechazará con su error normal, sin fallback silencioso.
 */
object JsModuleLoader {

    private val EXPORT_FUNCTION = Regex("""^(\s*)export\s+function\s+(\w+)""", RegexOption.MULTILINE)
    private val EXPORT_VAR = Regex("""^(\s*)export\s+(const|let|var)\s+(\w+)""", RegexOption.MULTILINE)
    private val EXPORT_DEFAULT_FUNCTION = Regex("""^\s*export\s+default\s+function\s+(\w+)""", RegexOption.MULTILINE)
    private val EXPORT_DEFAULT_EXPR = Regex("""^\s*export\s+default\s+(.+?);?\s*$""", RegexOption.MULTILINE)

    private data class ImportBindings(val defaultName: String?, val named: List<Pair<String, String>>)

    /** Devuelve el código final listo para `cx.evaluateString`: los
     *  módulos importados (recursivamente) van primero, cada uno en su
     *  propia constante __mod_N__, y luego el código del [entryFile] con
     *  sus líneas de import reemplazadas por lecturas de esas constantes. */
    fun flatten(entryFile: File, code: String): String {
        val moduleVars = LinkedHashMap<String, String>() // ruta absoluta -> nombre de variable
        val hoisted = mutableListOf<String>()
        val entryBody = rewriteImports(entryFile, code, moduleVars, hoisted, visiting = mutableSetOf())
        if (hoisted.isEmpty()) return code // sin imports resueltos: no tocar nada
        return hoisted.joinToString("\n") + "\n" + entryBody
    }

    /** Reemplaza cada línea de import en [code] (que pertenece a
     *  [file]) por una lectura de la variable de módulo correspondiente,
     *  resolviendo/aplanando recursivamente el módulo importado si aún no
     *  está en caché. No envuelve [code] en una IIFE: se usa tanto para el
     *  entry (que debe correr a nivel superior) como, desde
     *  [flattenModuleToVar], para el cuerpo interno de cada módulo. */
    private fun rewriteImports(
        file: File,
        code: String,
        moduleVars: MutableMap<String, String>,
        hoisted: MutableList<String>,
        visiting: MutableSet<String>
    ): String {
        val outLines = mutableListOf<String>()
        for (line in code.split('\n')) {
            val importMatch = JsImportSyntax.JS_IMPORT.find(line)
            if (importMatch == null) {
                outLines.add(line)
                continue
            }
            val importPath = importMatch.groupValues[1]
            val resolved = JsImportSyntax.resolveJsRelativePath(file, importPath)
            if (resolved == null) {
                // No resuelve: se deja la línea tal cual -> Rhino la
                // rechaza con su error normal de sintaxis, sin ocultarlo.
                outLines.add(line)
                continue
            }
            val varName = flattenModuleToVar(resolved, moduleVars, hoisted, visiting)
            val bindings = parseImportBindings(line)
            val replacement = StringBuilder()
            if (bindings.defaultName != null) {
                replacement.append("const ${bindings.defaultName} = $varName.__default__;\n")
            }
            if (bindings.named.isNotEmpty()) {
                val destructure = bindings.named.joinToString(", ") { (exported, local) ->
                    if (exported == local) exported else "$exported: $local"
                }
                replacement.append("const {$destructure} = $varName;\n")
            }
            outLines.add(replacement.toString().trimEnd('\n'))
        }
        return outLines.joinToString("\n")
    }

    /** Resuelve (si no está cacheado) el módulo en [file] a una constante
     *  __mod_N__ agregada a [hoisted], y devuelve su nombre de variable.
     *  Si [file] ya está en [visiting] (ciclo), devuelve una variable
     *  vacía sin recursar más, para no colgarse. */
    private fun flattenModuleToVar(
        file: File,
        moduleVars: MutableMap<String, String>,
        hoisted: MutableList<String>,
        visiting: MutableSet<String>
    ): String {
        val absPath = file.absolutePath
        moduleVars[absPath]?.let { return it }

        if (absPath in visiting) {
            val cycleVar = "__mod_cycle_${moduleVars.size}__"
            moduleVars[absPath] = cycleVar
            hoisted.add("const $cycleVar = {};")
            return cycleVar
        }
        visiting.add(absPath)

        val rawCode = runCatching { file.readText() }.getOrDefault("")
        val rewritten = rewriteImports(file, rawCode, moduleVars, hoisted, visiting)
        val (strippedCode, exportedNames, defaultExpr) = stripExportsAndCollect(rewritten)

        val varName = "__mod_${moduleVars.size}__"
        moduleVars[absPath] = varName // registrar antes de construir el string, por si hay auto-referencia indirecta

        val returnParts = exportedNames.toMutableList()
        if (defaultExpr != null) returnParts.add("__default__: $defaultExpr")
        hoisted.add(
            "const $varName = (function(){\n$strippedCode\nreturn {${returnParts.joinToString(", ")}};\n})();"
        )

        visiting.remove(absPath)
        return varName
    }

    /** Parsea qué nombres pide una línea de import: default, y cada
     *  nombre dentro de "{ a, b as c }" como (exportado, local). */
    private fun parseImportBindings(importLine: String): ImportBindings {
        val defaultName = JsImportSyntax.JS_IMPORT_DEFAULT_NAME.find(importLine)?.groupValues?.get(1)
        val namedBlock = JsImportSyntax.JS_IMPORT_NAMED_BLOCK.find(importLine)?.groupValues?.get(1)
        val named = namedBlock
            ?.split(',')
            ?.mapNotNull { part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val asMatch = Regex("""^(\w+)\s+as\s+(\w+)$""").find(trimmed)
                if (asMatch != null) asMatch.groupValues[1] to asMatch.groupValues[2]
                else trimmed to trimmed
            }
            ?: emptyList()
        return ImportBindings(defaultName, named)
    }

    /** Quita "export" de las declaraciones dejando la declaración
     *  subyacente intacta, y recolecta qué nombres quedaron exportados
     *  (para construir el "return {...}" de la IIFE del módulo). */
    private fun stripExportsAndCollect(moduleCode: String): Triple<String, List<String>, String?> {
        val exportedNames = mutableListOf<String>()

        var code = EXPORT_FUNCTION.replace(moduleCode) { m ->
            exportedNames.add(m.groupValues[2])
            "${m.groupValues[1]}function ${m.groupValues[2]}"
        }
        code = EXPORT_VAR.replace(code) { m ->
            exportedNames.add(m.groupValues[3])
            "${m.groupValues[1]}${m.groupValues[2]} ${m.groupValues[3]}"
        }

        var defaultExpr: String? = null
        val defaultFnMatch = EXPORT_DEFAULT_FUNCTION.find(code)
        if (defaultFnMatch != null) {
            defaultExpr = defaultFnMatch.groupValues[1]
            code = code.replaceFirst(Regex("""^\s*export\s+default\s+function\s+(\w+)""", RegexOption.MULTILINE), "function ${defaultFnMatch.groupValues[1]}")
        } else {
            val defaultExprMatch = EXPORT_DEFAULT_EXPR.find(code)
            if (defaultExprMatch != null) {
                defaultExpr = "__default__"
                code = code.replaceFirst(
                    Regex("""^\s*export\s+default\s+(.+?);?\s*$""", RegexOption.MULTILINE),
                    "const __default__ = ${defaultExprMatch.groupValues[1]};"
                )
            }
        }

        return Triple(code, exportedNames, defaultExpr)
    }
}
