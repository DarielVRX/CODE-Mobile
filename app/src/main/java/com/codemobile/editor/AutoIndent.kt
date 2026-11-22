package com.codemobile.editor

import com.codemobile.files.FileRepository.Lang

/**
 * Autoindentación básica por heurística de línea anterior (no por parseo real):
 *
 * - Si la línea anterior termina en un carácter "abridor de bloque" ({, (, [, o ':'
 *   en Python), la nueva línea hereda la indentación anterior + 1 nivel.
 * - En cualquier otro caso, la nueva línea hereda exactamente la misma indentación
 *   ("mantener sangría"), que es el comportamiento mínimo esperable.
 *
 * BUG/ajuste encontrado (reportado por el usuario): usar el carácter tab real
 * (\t) como unidad de indentación mezclaba mal con la convención real de cada
 * lenguaje (PEP 8 para Python, la guía de Kotlin, etc., que indentan con
 * espacios, no con \t) — un archivo escrito acá y abierto después en un editor
 * de escritorio con su propio ancho de tab configurado se veía con una
 * indentación distinta a la que tenía en el celular. Ahora cada lenguaje
 * indenta con la cantidad de espacios de SU convención más común (ver
 * spacesPerLevel), no con \t — ancho fijo y consistente entre editores.
 * leadingWhitespace (más abajo) sigue aceptando \t al HEREDAR indentación ya
 * existente (archivos viejos escritos con tab real, de este editor antes de
 * este cambio o de cualquier otro): solo el nivel NUEVO que esta función
 * agrega al abrir un bloque pasa a ser espacios, nunca \t. La tecla ⇥ de
 * CodeEditText (ver su tabKey) también pasó a usar indentUnitFor acá en vez
 * de \t literal, por consistencia — un tap de ⇥ y un Enter que abre un
 * bloque deben verse igual. La única excepción que sigue insertando \t
 * literal a propósito es PlainEditTextTarget.tabKey: el campo de comandos
 * git de la consola, que no tiene ningún Lang asociado (no es código de un
 * archivo, ver su doc de clase), así que esta convención por lenguaje no le
 * aplica.
 *
 * No hace dedent automático al escribir '}' o 'return' — eso queda fuera del MVP.
 */
object AutoIndent {

    /** Espacios por nivel de indentación, según la convención más común de
     *  cada lenguaje: PEP 8 (Python) y la guía oficial de Kotlin usan 4;
     *  JavaScript (Airbnb/Prettier/Google) y HTML suelen usar 2. SQL no
     *  tiene un estándar fuerte: se deja en 2, mismo criterio simple que
     *  JS/HTML. PLAIN (archivo sin lenguaje reconocido, "+Otro") no tiene
     *  convención propia: 4 por defecto, el más universal entre los
     *  lenguajes de arriba. */
    private fun spacesPerLevel(lang: Lang): Int = when (lang) {
        Lang.PYTHON, Lang.KOTLIN, Lang.PLAIN -> 4
        Lang.JAVASCRIPT, Lang.HTML, Lang.SQL -> 2
    }

    fun indentUnitFor(lang: Lang): String = " ".repeat(spacesPerLevel(lang))

    /** Prefijo de comentario de línea del lenguaje (tecla Q, drag-abajo
     *  "Comentar/descomentar línea" — Paso 7, reemplaza al viejo
     *  INSERT_SLASH, que solo insertaba "/" literal sin ningún criterio de
     *  lenguaje). null = el lenguaje no tiene comentario de LÍNEA real
     *  (HTML solo tiene comentario de BLOQUE, <!-- -->, que requeriría
     *  envolver la línea completa en vez de prefijarla — pedido
     *  explícitamente fuera de alcance: Q simplemente no hace nada en
     *  HTML, ver CodeEditText.toggleLineComment). */
    fun lineCommentPrefixFor(lang: Lang): String? = when (lang) {
        Lang.PYTHON -> "#"
        Lang.SQL -> "--"
        Lang.JAVASCRIPT, Lang.KOTLIN -> "//"
        Lang.PLAIN -> "//"
        Lang.HTML -> null
    }

    /**
     * Calcula la indentación a insertar después de un salto de línea, dada la
     * línea anterior completa (sin el \n) y el lenguaje activo.
     */
    fun indentForNewLine(previousLine: String, lang: Lang): String {
        val currentIndent = leadingWhitespace(previousLine)
        val trimmed = previousLine.trimEnd()
        val opensBlock = when (lang) {
            Lang.PYTHON -> trimmed.endsWith(":")
            Lang.JAVASCRIPT, Lang.HTML, Lang.SQL, Lang.KOTLIN, Lang.PLAIN ->
                trimmed.endsWith("{") || trimmed.endsWith("(") || trimmed.endsWith("[")
        }
        return if (opensBlock) currentIndent + indentUnitFor(lang) else currentIndent
    }

    private fun leadingWhitespace(line: String): String {
        val sb = StringBuilder()
        for (c in line) {
            if (c == ' ' || c == '\t') sb.append(c) else break
        }
        return sb.toString()
    }
}
