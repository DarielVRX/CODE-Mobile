package com.codemobile.editor

import com.codemobile.files.FileRepository.Lang
import java.util.regex.Pattern

/**
 * Autocompletado NO predictivo (estilo Gboard): dado un prefijo ya escrito,
 * sugiere por coincidencia de prefijo contra dos fuentes —
 *
 * 1. Keywords del lenguaje, vía SyntaxHighlighter.keywordsFor(lang) (no se
 *    duplica esa lista aquí, tal como pide el plan). Lista fija y chica,
 *    sin costo relevante.
 * 2. Identificadores propios del usuario (variables/funciones) detectados en
 *    el propio texto del archivo, vía regex sobre patrones de DECLARACIÓN o
 *    ASIGNACIÓN real: `def nombre`, `class Nombre`, `for nombre in ...`,
 *    `nombre = ...`. Deliberadamente EXCLUYE contextos de comparación/lectura
 *    (`==`, `<`, `>`, condiciones de if/for/while que no son la propia
 *    declaración del for) para no sugerir como "identificador declarado"
 *    algo que solo se está leyendo o comparando.
 *
 * Bug de rendimiento corregido (archivos extensos, miles de líneas): antes,
 * (2) se recalculaba recorriendo el ARCHIVO ENTERO línea por línea en CADA
 * tecla (userIdentifiers(fullText) corriendo 3 Pattern/Matcher distintos por
 * línea), lo cual se medía en ~12ms/tecla con un archivo de ~4800 líneas —
 * perceptible al escribir rápido. Ahora [lineIdentifierIndex] mantiene un
 * registro línea→identificadores que esa línea declara, construido una sola
 * vez al abrir el archivo (ver buildFullIndex, llamado desde
 * CodeEditText.highlightAll) y actualizado SOLO para la línea que el cursor
 * abandona (ver updateLineOnExit) — nunca en cada tecla dentro de la misma
 * línea. Insertar/eliminar una línea completa (Enter, backspace que fusiona)
 * renumera las claves del índice (shiftLinesInsertedAt/shiftLinesRemovedAt)
 * en vez de recalcular todo. suggestionsFor() consulta la unión de ese
 * índice en vez de volver a escanear el archivo.
 */
class Autocomplete(private val lang: Lang) {

    private val keywords: List<String> = SyntaxHighlighter.keywordsFor(lang)

    /** línea (0-based) → identificadores que esa línea declara. Solo tiene
     *  entradas para líneas que declaran algo (una línea sin declaraciones
     *  no ocupa espacio acá). Ver doc de clase para el porqué de este
     *  diseño en vez de recorrer el archivo en cada tecla. */
    private val lineIdentifierIndex = HashMap<Int, Set<String>>()

    /** Construye [lineIdentifierIndex] desde cero recorriendo todo
     *  [fullText] — el mismo costo que antes tenía CADA tecla, pero ahora
     *  se paga una sola vez: al abrir el archivo (CodeEditText.highlightAll,
     *  llamado una vez por pestaña tras setText) o al cambiar de lenguaje
     *  (lang reconstruye Autocomplete, ver su setter en CodeEditText). */
    fun buildFullIndex(fullText: String) {
        lineIdentifierIndex.clear()
        fullText.lineSequence().forEachIndexed { index, line ->
            val ids = identifiersDeclaredIn(line)
            if (ids.isNotEmpty()) lineIdentifierIndex[index] = ids
        }
    }

    /** Recalcula SOLO la línea [lineIndex] con su contenido actual
     *  [lineContent] — se llama cuando el cursor abandona esa línea (ver
     *  CodeEditText), nunca por cada tecla mientras se permanece en ella.
     *  Si la línea ya no declara nada, se quita del índice en vez de dejar
     *  una entrada vacía. */
    fun updateLineOnExit(lineIndex: Int, lineContent: String) {
        val ids = identifiersDeclaredIn(lineContent)
        if (ids.isEmpty()) {
            lineIdentifierIndex.remove(lineIndex)
        } else {
            lineIdentifierIndex[lineIndex] = ids
        }
    }

    /** Una línea nueva se insertó en [atIndex] (Enter, o la primera línea de
     *  un pegado multilínea): todo lo que el índice tenía registrado en
     *  [atIndex] o más abajo corre +1, para seguir apuntando a la línea
     *  correcta tras el corrimiento — sin volver a escanear ningún
     *  contenido, es pura aritmética de claves. */
    fun shiftLinesInsertedAt(atIndex: Int, linesInserted: Int = 1) {
        if (lineIdentifierIndex.isEmpty() || linesInserted <= 0) return
        val shifted = HashMap<Int, Set<String>>(lineIdentifierIndex.size)
        for ((line, ids) in lineIdentifierIndex) {
            shifted[if (line >= atIndex) line + linesInserted else line] = ids
        }
        lineIdentifierIndex.clear()
        lineIdentifierIndex.putAll(shifted)
    }

    /** [linesRemoved] líneas desaparecieron a partir de [atIndex] (backspace
     *  que fusiona líneas, o borrar una selección que abarca saltos de
     *  línea): se descartan sus entradas del índice (ya no existen) y todo
     *  lo que estaba más abajo corre hacia arriba para seguir apuntando a
     *  la línea correcta. El caller debe llamar updateLineOnExit() por
     *  separado para la línea resultante de la fusión, si corresponde. */
    fun shiftLinesRemovedAt(atIndex: Int, linesRemoved: Int = 1) {
        if (lineIdentifierIndex.isEmpty() || linesRemoved <= 0) return
        val shifted = HashMap<Int, Set<String>>(lineIdentifierIndex.size)
        for ((line, ids) in lineIdentifierIndex) {
            when {
                line < atIndex -> shifted[line] = ids
                line >= atIndex + linesRemoved -> shifted[line - linesRemoved] = ids
                // line en [atIndex, atIndex+linesRemoved): esa línea
                // desapareció, se descarta su entrada.
            }
        }
        lineIdentifierIndex.clear()
        lineIdentifierIndex.putAll(shifted)
    }

    companion object {
        private val IDENTIFIER = "[a-zA-Z_][a-zA-Z0-9_]*"

        // `def nombre(` / `class Nombre` — declaración inequívoca, nunca lectura.
        private val DEF_OR_CLASS = Pattern.compile("\\b(?:def|class)\\s+($IDENTIFIER)")

        // `for nombre in ...` / `for a, b in ...` — toma solo el/los nombres
        // entre 'for' y 'in', que son la variable que se declara, no lo que
        // se recorre (lo de después de 'in' se ignora a propósito).
        private val FOR_IN = Pattern.compile("\\bfor\\s+([a-zA-Z_][a-zA-Z0-9_,\\s]*?)\\s+in\\b")

        // `nombre = valor` — asignación real. El lookahead/lookbehind niega
        // explícitamente '==', '!=', '<=', '>=' para no capturar comparaciones,
        // y el '=' no puede ir seguido de otro '=' (ya cubierto por el
        // lookahead), ni precedido de '<', '>', '!' (comparaciones compuestas).
        private val ASSIGNMENT = Pattern.compile(
            "\\b($IDENTIFIER)\\s*(?<![<>!=])=(?!=)"
        )

        // Líneas de condición (if/elif/while) cuyo contenido jamás debe
        // tratarse como declaración, aunque contengan un '=' de comparación
        // como '==' (ya excluido arriba) u otra asignación-con-efecto-lateral
        // dentro de la condición misma (p.ej. "if (x = leer()):" en algún
        // dialecto) — se descarta la línea entera para esas tres palabras
        // clave, que es el caso que el plan pide excluir explícitamente.
        private val CONDITION_LINE = Pattern.compile("^\\s*(if|elif|while)\\b")
    }

    /**
     * Sugerencias para [prefix] (ya escrito por el usuario, sin incluir el
     * cursor). Orden: keywords del lenguaje primero (coinciden más seguido y
     * son más cortas de confirmar), luego identificadores propios detectados
     * — ahora leídos de [lineIdentifierIndex] (ver doc de clase) en vez de
     * recorrer el archivo completo en cada llamada — ambos filtrados por
     * prefijo, sin duplicados, excluyendo una coincidencia exacta con
     * [prefix] (no tiene sentido "completar" una palabra ya completa).
     */
    fun suggestionsFor(prefix: String): List<String> {
        if (prefix.isEmpty()) return emptyList()

        val fromKeywords = keywords.filter { it.startsWith(prefix, ignoreCase = true) }
        val fromIdentifiers = allIndexedIdentifiers().filter { it.startsWith(prefix, ignoreCase = true) }

        return (fromKeywords + fromIdentifiers)
            .distinct()
            .filterNot { it.equals(prefix, ignoreCase = true) }
    }

    /** Unión de todos los identificadores actualmente registrados en
     *  [lineIdentifierIndex], sin importar en qué línea están — mismo
     *  resultado que antes devolvía userIdentifiers(fullText), calculado sin
     *  volver a tocar el texto del archivo. */
    private fun allIndexedIdentifiers(): Set<String> {
        if (lineIdentifierIndex.isEmpty()) return emptySet()
        val all = linkedSetOf<String>()
        for (ids in lineIdentifierIndex.values) all.addAll(ids)
        return all
    }

    /** Extrae los identificadores que UNA sola [line] declara (antes operaba
     *  sobre el archivo entero vía lineSequence — ver doc de clase: ahora
     *  cada línea se procesa una sola vez, al construir/actualizar el
     *  índice, no en cada tecla). Salta por completo las líneas de condición
     *  (if/elif/while) para evitar falsos positivos de "declaración" dentro
     *  de una comparación. */
    private fun identifiersDeclaredIn(line: String): Set<String> {
        if (CONDITION_LINE.matcher(line).find()) return emptySet()

        val found = linkedSetOf<String>()

        val defMatcher = DEF_OR_CLASS.matcher(line)
        while (defMatcher.find()) found.add(defMatcher.group(1))

        val forMatcher = FOR_IN.matcher(line)
        while (forMatcher.find()) {
            forMatcher.group(1).split(",").forEach { name ->
                val trimmed = name.trim()
                if (trimmed.isNotEmpty()) found.add(trimmed)
            }
        }

        val assignMatcher = ASSIGNMENT.matcher(line)
        while (assignMatcher.find()) found.add(assignMatcher.group(1))

        return found
    }
}
