package com.codemobile.files

import java.io.File
import java.util.ArrayDeque

/** Tipo de archivo reconocido por el diálogo de creación (ver
 *  EditorActivity.showNewFileDialog) y por el color fijo de pestaña (ver
 *  colorIndexForExtension más abajo). OTHER ("+ Otro") no fuerza ninguna
 *  extensión: el usuario la escribe libre, y cae en el color gris neutro
 *  de colorIndexForExtension (cualquier extensión no reconocida, incluida
 *  la vacía). */
enum class FileType(val label: String, val fixedExtension: String?) {
    PYTHON("Python", "py"),
    JAVASCRIPT("JavaScript", "js"),
    HTML("HTML", "html"),
    CSS("CSS", "css"),
    SQL("SQL", "sql"),
    KOTLIN("Kotlin", "kt"),
    OTHER("+ Otro", null)
}

/** Cada EXTENSIÓN reconocida tiene un color fijo, reutilizando los mismos
 *  16 colores de colors.xml tab_01..tab_16 (ningún color ni recurso
 *  nuevo). Cualquier extensión no reconocida (incluyendo vacía) cae en el
 *  índice 13 (tab_14), el gris más neutro de la paleta. Se llama tanto al
 *  crear un archivo nuevo como al reabrir uno existente (ver
 *  OpenFile.fromFile), así que un archivo .py creado antes de que existiera
 *  esta función también termina pintado con su color, sin importar qué
 *  color tenía guardado. */
fun colorIndexForExtension(extension: String): Int = when (extension.lowercase()) {
    "py" -> 4   // tab_05, verde
    "js", "mjs", "cjs" -> 2   // tab_03, amarillo
    "html", "htm" -> 0   // tab_01, rojo/naranja
    "css" -> 7   // tab_08, azul
    "sql" -> 9   // tab_10, púrpura
    "kt", "kts" -> 8   // tab_09, violeta oscuro
    "txt", "" -> 12   // tab_13, gris claro (texto plano)
    else -> 13   // tab_14, gris neutro ("+ Otro" / no reconocido)
}

/**
 * Representa un archivo abierto en una pestaña.
 *
 * El color de la pestaña depende de su EXTENSIÓN (ver colorIndexForExtension
 * arriba), no se elige a mano: fromFile/untitled lo calculan solas.
 *
 * Ya NO autocompleta pares de brackets/comillas (decisión explícita: se
 * eliminó el autocierre por completo porque la maquinaria para mantenerlo
 * sincronizado con el cursor era una fuente recurrente de bugs — ver
 * BracketAccumulator. El usuario tipea todo a mano; el único apoyo es la
 * celda de "pendientes", que ahora lee directo de
 * BracketAccumulator.visiblePending sobre el texto real y la posición del
 * cursor, sin ningún estado propio por archivo que mantener). Esta clase ya
 * no tiene ninguna pila de cierres pendientes.
 */
data class OpenFile(
    var file: File?,
    var displayName: String,
    var extension: String,
    var colorIndex: Int,
    var content: StringBuilder = StringBuilder(),
    var cursorPosition: Int = 0,
    var isDirty: Boolean = false,
    /** Espejo del contenido tal como está (o quedará) en disco: lo que
     *  escribió fromFile() al cargar, o lo último que FileRepository.save/
     *  saveAs escribieron. isDirty se calcula comparando el texto actual
     *  del editor contra esto (ver CodeEditText), no por "hubo algún
     *  evento de edición" — así un undo/redo que vuelve exactamente al
     *  contenido guardado no queda marcado sucio. */
    var savedContent: String = "",
    /** Pendiente reportado: abrir un archivo grande demoraba porque
     *  fromFile() leía el archivo entero de forma síncrona antes de poder
     *  mostrar la pestaña. true mientras [content] solo tiene las primeras
     *  INITIAL_LOAD_LINES líneas (ver fromFile) y el resto todavía se está
     *  leyendo en background (ver TabsController.addNewTab/openOrFocus) —
     *  el editor queda sin edición habilitada y persistCurrentFile se
     *  niega a guardar mientras esto sea true, para no guardar un archivo
     *  truncado ni perder ediciones que de todos modos no pueden existir
     *  aún. checkRecoverableBackup (TabsController) lo pone en false a
     *  mano si el usuario recupera un backup ANTES de que la carga en
     *  background termine, para que esa carga no pise el backup recuperado
     *  al llegar después. */
    var isLoadingFull: Boolean = false
) {
    companion object {
        /** Ver doc de isLoadingFull: tamaño del primer chunk mostrado de
         *  inmediato en un archivo grande, antes de completar el resto en
         *  background. */
        const val INITIAL_LOAD_LINES = 200

        fun untitled(extension: String = "txt"): OpenFile {
            return OpenFile(
                file = null,
                displayName = "untitled",
                extension = extension,
                colorIndex = colorIndexForExtension(extension)
            )
        }

        /** colorIndex ya no se recibe como parámetro: se calcula siempre
         *  desde la extensión real del archivo (ver colorIndexForExtension),
         *  así que un archivo .py reabierto después de este cambio queda
         *  verde aunque antes tuviera guardado un color manual distinto.
         *
         *  Pendiente reportado (apertura lenta de archivos grandes): ya no
         *  lee el archivo entero acá — solo hasta INITIAL_LOAD_LINES líneas,
         *  lectura acotada y rápida incluso en archivos enormes. Si el
         *  archivo tiene más contenido que eso, isLoadingFull queda true y
         *  savedContent se deja VACÍO a propósito (no el chunk parcial):
         *  savedContent debe reflejar el archivo completo real en disco,
         *  nunca un recorte, así que se completa recién cuando la carga en
         *  background (ver TabsController) termine — mientras tanto el
         *  editor queda sin edición habilitada (ver renderActiveEditor), así
         *  que no hay ninguna comparación de isDirty que pueda salir mal
         *  por tener savedContent desactualizado en el medio. */
        fun fromFile(file: File): OpenFile {
            val name = file.name
            val ext = name.substringAfterLast('.', "")
            val base = name.substringBeforeLast('.', name)
            val of = OpenFile(
                file = file,
                displayName = base,
                extension = ext,
                colorIndex = colorIndexForExtension(ext)
            )
            if (file.exists()) {
                val (chunk, hasMore) = readInitialChunk(file, INITIAL_LOAD_LINES)
                of.content = StringBuilder(chunk)
                if (hasMore) {
                    of.isLoadingFull = true
                } else {
                    of.savedContent = chunk
                }
            } else {
                of.savedContent = ""
            }
            return of
        }

        /** Lee como máximo [maxLines] líneas de [file], uniéndolas con '\n'
         *  (normaliza CRLF/CR a LF igual que el resto del editor — ver doc
         *  de completeLoadingIfNeeded en TabsController: ningún otro punto
         *  del código maneja CRLF, así que esto no es una normalización
         *  nueva, es consistente con cómo ya se trata el texto en todos
         *  lados), deteniéndose apenas se cumple el límite — nunca carga el
         *  archivo completo en memoria solo para descartar el resto.
         *  Devuelve el chunk y si quedó contenido sin leer después de él
         *  (archivo con más de [maxLines] líneas). useLines() cierra el
         *  BufferedReader automáticamente al salir (o al hacer break), sin
         *  necesidad de un try/finally a mano. */
        private fun readInitialChunk(file: File, maxLines: Int): Pair<String, Boolean> {
            val sb = StringBuilder()
            var lineCount = 0
            var hasMore = false
            file.bufferedReader().useLines { lines ->
                val iterator = lines.iterator()
                while (iterator.hasNext()) {
                    val line = iterator.next()
                    if (lineCount >= maxLines) {
                        hasMore = true
                        break
                    }
                    if (lineCount > 0) sb.append('\n')
                    sb.append(line)
                    lineCount++
                }
            }
            return sb.toString() to hasMore
        }
    }
}
