package com.codemobile.editor

import android.text.Editable
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import com.codemobile.files.FileRepository.Lang
import java.util.regex.Pattern

/**
 * Resaltado de sintaxis por reglas regex simples, aplicado línea por línea.
 * No es un parser real: no entiende contexto profundo (ej. strings multilínea),
 * pero cubre el caso cotidiano de palabras clave, strings, comentarios y números
 * con un costo de implementación y de CPU muy bajo, razonable para un MVP.
 */
class SyntaxHighlighter(private val lang: Lang) {

    private data class Rule(val pattern: Pattern, val colorAttr: Int)

    private val rules: List<Rule> = buildRules()

    // Strings de una sola línea ("..." / '...') ya cubren todos los
    // lenguajes por igual (regla compartida en buildRules). Lo que Kotlin
    // Y Python agregan, cada uno por su lado, es la triple comilla para
    // strings multilínea/raw (""" en Kotlin; """ o ''' en Python, ambas
    // formas válidas e intercambiables ahí) — que SÍ puede abarcar varias
    // líneas reales, a diferencia del resto del highlighter (reglas regex
    // por línea, sin memoria de lo que vino antes). Esto exige saber, para
    // la línea que se está pintando, si ya venía "dentro" de un bloque de
    // alguno de estos delimitadores abierto en una línea anterior.
    // tripleQuoteDelimiters() devuelve la lista vacía para cualquier otro
    // lenguaje, así que ahí esto nunca encuentra nada y no hace falta un
    // chequeo de lang aparte en cada sitio que lo usa.
    private val tripleQuoteDelimiters: List<String> = tripleQuoteDelimitersFor(lang)

    private fun tripleQuoteDelimitersFor(lang: Lang): List<String> = when (lang) {
        Lang.KOTLIN -> listOf("\"\"\"")
        Lang.PYTHON -> listOf("\"\"\"", "'''")
        else -> emptyList()
    }

    /** Para [position] del documento completo [fullText]: el delimitador
     *  (de entre tripleQuoteDelimiters) dentro de cuyo bloque cae esa
     *  posición, o null si no cae dentro de ninguno. "Dentro" se calcula
     *  por paridad: el número de apariciones de ESE delimitador antes de
     *  [position] es impar — cada delimitador se cuenta por separado (en
     *  Python, una cantidad par de """ no se ve afectada por cuántas '''
     *  hay, y viceversa). Sin escape especial y sin distinguir, p.ej, """
     *  de """" (cuatro comillas): un escaneo de paridad simple, consistente
     *  con el resto del highlighter (reglas por patrón, no un parser real).
     *  Si ambos delimitadores resultaran "abiertos" a la vez en la misma
     *  posición (código inválido/ambiguo, p.ej. mezclar """ y ''' sin
     *  cerrar ninguno) se devuelve el primero de la lista: caso límite sin
     *  una respuesta correcta única, no vale la pena resolverlo mejor. */
    fun openTripleQuoteDelimiterAt(fullText: CharSequence, position: Int): String? {
        if (tripleQuoteDelimiters.isEmpty()) return null
        val limit = position.coerceIn(0, fullText.length)
        val text = fullText.subSequence(0, limit)
        for (delimiter in tripleQuoteDelimiters) {
            val matcher = Pattern.compile(Pattern.quote(delimiter)).matcher(text)
            var count = 0
            while (matcher.find()) count++
            if (count % 2 != 0) return delimiter
        }
        return null
    }

    private fun buildRules(): List<Rule> {
        val keywords = keywordsFor(lang)

        val list = mutableListOf<Rule>()
        if (keywords.isNotEmpty()) {
            // SQL no distingue mayúsculas/minúsculas en sus palabras clave
            // (SELECT, select y Select son equivalentes) — el resto de los
            // lenguajes sí, así que el flag solo aplica acá.
            val flags = if (lang == Lang.SQL) Pattern.CASE_INSENSITIVE else 0
            val kwPattern = Pattern.compile("\\b(${keywords.joinToString("|")})\\b", flags)
            list.add(Rule(kwPattern, R_KEYWORD))
        }
        // Números
        list.add(Rule(Pattern.compile("\\b\\d+(\\.\\d+)?\\b"), R_NUMBER))
        // Strings simples (sin soporte multilínea)
        list.add(Rule(Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), R_STRING))
        list.add(Rule(Pattern.compile("'([^'\\\\]|\\\\.)*'"), R_STRING))
        // Nombre de función tras 'def'/'function' o antes de '('
        list.add(Rule(Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?=\\()"), R_FUNCTION))
        // Comentarios: deben ir al final para pisar coincidencias previas en esa porción de línea.
        // Cada lenguaje tiene su propia sintaxis de comentario de línea;
        // HTML usa <!-- --> (no es un comentario de una sola línea, pero se
        // resalta igual como aproximación razonable para el MVP).
        val commentPattern = when (lang) {
            Lang.PYTHON -> Pattern.compile("#.*$")
            Lang.SQL -> Pattern.compile("--.*$")
            Lang.HTML -> Pattern.compile("<!--.*-->")
            Lang.JAVASCRIPT, Lang.KOTLIN, Lang.PLAIN -> Pattern.compile("//.*$")
        }
        list.add(Rule(commentPattern, R_COMMENT))

        return list
    }

    /** Aplica los spans de color sobre [editable] para el rango [lineStart, lineEnd).
     *
     *  [openTripleQuoteDelimiter]: el delimitador de triple comilla (""" o
     *  ''' según el lenguaje) que ya viene abierto sin cerrar al llegar a
     *  [lineStart], o null si no hay ninguno abierto (ver
     *  openTripleQuoteDelimiterAt — quien llama lo calcula sobre el
     *  documento completo antes de invocar esto, porque esta función solo
     *  ve la línea, no lo que vino antes). Siempre null para un lenguaje
     *  sin triple comilla (tripleQuoteDelimiters vacío). */
    fun highlightLine(editable: Editable, lineStart: Int, lineEnd: Int, openTripleQuoteDelimiter: String? = null) {
        if (lineStart >= lineEnd) return
        // Limpia spans previos de resaltado en el rango
        val spans = editable.getSpans(lineStart, lineEnd, ForegroundColorSpan::class.java)
        for (s in spans) {
            if (editable.getSpanStart(s) >= lineStart && editable.getSpanEnd(s) <= lineEnd) {
                editable.removeSpan(s)
            }
        }
        val tabSpans = editable.getSpans(lineStart, lineEnd, TabMarkerSpan::class.java)
        for (s in tabSpans) {
            if (editable.getSpanStart(s) >= lineStart && editable.getSpanEnd(s) <= lineEnd) {
                editable.removeSpan(s)
            }
        }
        val lineText = editable.subSequence(lineStart, lineEnd).toString()

        // Tramo [tripleStart, tripleEnd) de lineText que es el string de
        // triple comilla (si lo hay en esta línea); -1/-1 si no hay
        // ninguno. Las reglas normales corren sobre TODO lo que quede
        // afuera de ese tramo — antes Y después (p.ej. 'x = """raw""" + y'
        // tiene código real tanto antes del delimitador que abre como
        // después del que cierra).
        var tripleStart = -1
        var tripleEnd = -1
        if (openTripleQuoteDelimiter != null) {
            // Ya venía abierto desde antes de esta línea: busca el cierre
            // de ESE MISMO delimitador (no cualquiera de la lista — en
            // Python, un '''...''' abierto no lo cierra una """ suelta).
            val closeIdx = lineText.indexOf(openTripleQuoteDelimiter)
            tripleStart = 0
            tripleEnd = if (closeIdx == -1) lineText.length else closeIdx + openTripleQuoteDelimiter.length
        } else if (tripleQuoteDelimiters.isNotEmpty()) {
            // No venía abierto: busca cuál de los delimitadores de este
            // lenguaje abre primero en la línea (el que aparece en la
            // posición más temprana), para elegir correctamente entre """
            // y ''' cuando ambos son válidos (Python).
            var bestDelimiter: String? = null
            var bestIdx = -1
            for (delimiter in tripleQuoteDelimiters) {
                val idx = lineText.indexOf(delimiter)
                if (idx != -1 && (bestIdx == -1 || idx < bestIdx)) {
                    bestIdx = idx
                    bestDelimiter = delimiter
                }
            }
            if (bestDelimiter != null) {
                val closeIdx = lineText.indexOf(bestDelimiter, bestIdx + bestDelimiter.length)
                tripleStart = bestIdx
                tripleEnd = if (closeIdx == -1) lineText.length else closeIdx + bestDelimiter.length
            }
        }
        if (tripleStart != -1 && tripleEnd > tripleStart) {
            editable.setSpan(
                ForegroundColorSpan(colorFor(R_STRING)),
                lineStart + tripleStart, lineStart + tripleEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        fun applyRulesOn(rangeStart: Int, rangeEnd: Int) {
            if (rangeStart >= rangeEnd) return
            val rangeText = lineText.substring(rangeStart, rangeEnd)
            for (rule in rules) {
                val matcher = rule.pattern.matcher(rangeText)
                while (matcher.find()) {
                    val start = lineStart + rangeStart + matcher.start()
                    val end = lineStart + rangeStart + matcher.end()
                    if (start == end) continue
                    editable.setSpan(
                        ForegroundColorSpan(colorFor(rule.colorAttr)),
                        start, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }

        if (tripleStart == -1) {
            applyRulesOn(0, lineText.length)
        } else {
            applyRulesOn(0, tripleStart)
            applyRulesOn(tripleEnd, lineText.length)
        }
        // Marca visual del tab real (\t), para distinguirlo de espacios cuando
        // el mismo archivo se edita también en un editor de escritorio.
        for (i in lineText.indices) {
            if (lineText[i] == '\t') {
                val pos = lineStart + i
                editable.setSpan(
                    TabMarkerSpan(colorFor(R_COMMENT)),
                    pos, pos + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun colorFor(tag: Int): Int = when (tag) {
        R_KEYWORD -> 0xFFC586C0.toInt()
        R_STRING -> 0xFFCE9178.toInt()
        R_FUNCTION -> 0xFFDCDCAA.toInt()
        R_COMMENT -> 0xFF6A9955.toInt()
        R_NUMBER -> 0xFFB5CEA8.toInt()
        else -> 0xFFE4E4EC.toInt()
    }

    companion object {
        private const val R_KEYWORD = 1
        private const val R_STRING = 2
        private const val R_FUNCTION = 3
        private const val R_COMMENT = 4
        private const val R_NUMBER = 5

        /** Lista de keywords por lenguaje, extraída de buildRules() para que
         *  Autocomplete.kt (fase 6 del plan de CustomKeyboardView) la reutilice
         *  tal cual, en vez de duplicarla — el plan pide explícitamente no
         *  mantener dos listas de keywords por separado. */
        fun keywordsFor(lang: Lang): List<String> = when (lang) {
            Lang.PYTHON -> listOf(
                "def", "class", "return", "if", "elif", "else", "for", "while", "in", "not",
                "and", "or", "import", "from", "as", "try", "except", "finally", "with",
                "lambda", "pass", "break", "continue", "yield", "None", "True", "False", "self"
            )
            Lang.JAVASCRIPT -> listOf(
                "function", "return", "if", "else", "for", "while", "in", "of", "const", "let",
                "var", "class", "extends", "new", "try", "catch", "finally", "throw", "typeof",
                "null", "undefined", "true", "false", "this", "import", "export", "default", "async", "await"
            )
            Lang.HTML -> listOf(
                "html", "head", "body", "div", "span", "a", "p", "ul", "li", "ol", "table",
                "tr", "td", "th", "form", "input", "button", "script", "style", "link", "meta",
                "title", "h1", "h2", "h3", "img", "br", "label", "select", "option", "textarea"
            )
            Lang.SQL -> listOf(
                "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
                "CREATE", "TABLE", "DROP", "ALTER", "JOIN", "INNER", "LEFT", "RIGHT", "ON",
                "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "AND", "OR", "NOT", "NULL", "PRIMARY",
                "KEY", "FOREIGN", "REFERENCES", "DEFAULT", "UNIQUE", "DISTINCT", "AS"
            )
            Lang.KOTLIN -> listOf(
                "fun", "val", "var", "class", "object", "interface", "return", "if", "else",
                "when", "for", "while", "do", "in", "is", "as", "null", "true", "false", "this",
                "super", "try", "catch", "finally", "throw", "import", "package", "companion",
                "data", "sealed", "enum", "override", "private", "public", "protected", "internal",
                "open", "abstract", "lateinit", "const", "suspend", "inline", "init", "constructor",
                "typealias", "vararg", "out", "by", "get", "set"
            )
            Lang.PLAIN -> emptyList()
        }
    }
}
