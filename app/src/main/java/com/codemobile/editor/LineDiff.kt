package com.codemobile.editor

/** Estado de una línea del texto ACTUAL frente a savedContent (ver
 *  OpenFile.savedContent), usado por la franja de diff del margen derecho
 *  (ver EditorPaneView). No distingue líneas ELIMINADAS a propósito (alcance
 *  reducido explícito, pendiente a futuro): una línea eliminada no existe en
 *  el texto actual, así que marcarla requeriría una marca ENTRE dos líneas
 *  en vez de SOBRE una línea existente — haría falta otra forma de pintarla
 *  (triángulo/muesca en el borde) en vez de reusar la misma franja por
 *  línea que ADDED/MODIFIED. */
enum class LineStatus { UNCHANGED, ADDED, MODIFIED }

/** Diff por líneas entre [original] (savedContent) y [current] (texto en
 *  pantalla), basado en LCS (subsecuencia común más larga) de líneas
 *  completas — una comparación posición-a-posición (línea N actual vs línea
 *  N original) se desalinearía por completo ante cualquier línea insertada o
 *  borrada en el medio del archivo, marcando como "modificado" todo lo que
 *  sigue aunque no haya cambiado una letra. LCS es el mismo principio que
 *  usa git diff/VS Code para decidir qué líneas son realmente las "mismas"
 *  línea movida de lugar.
 *
 *  Devuelve un estado por cada línea de [current], en orden. Costo O(n*m) en
 *  cantidad de líneas (no de caracteres) — aceptable para el tamaño habitual
 *  de un archivo de código; no se corre en cada tecla, solo cuando el texto
 *  "se asienta" (ver EditorPaneView, mismo punto que refreshLineNumbers). */
fun computeLineStatuses(original: String, current: String): List<LineStatus> {
    val oldLines = original.split("\n")
    val newLines = current.split("\n")

    // dp[i][j] = longitud de la LCS entre oldLines[i..] y newLines[j..].
    // Tabla clásica de LCS, de atrás para adelante, para poder reconstruir
    // el camino con un solo recorrido hacia adelante después.
    val n = oldLines.size
    val m = newLines.size
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            dp[i][j] = if (oldLines[i] == newLines[j]) {
                dp[i + 1][j + 1] + 1
            } else {
                maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
    }

    // Recorrido hacia adelante: en cada paso, si la línea actual coincide
    // con la línea vieja en el tope de la LCS, es UNCHANGED (misma línea,
    // conservada) y avanzan ambos cursores. Si no coincide, newLines[j] es
    // una línea que no está en esa posición de la LCS: ADDED si no quedan
    // líneas viejas por consumir en este tramo (dp empuja a avanzar solo en
    // j), MODIFIED si en cambio hay una línea vieja "de sobra" en el mismo
    // tramo (se consume en paralelo, 1 a 1, como un reemplazo de línea en
    // vez de una inserción pura) — misma heurística simple que usan los
    // diffs de línea básicos para no mostrar TODA sustitución como
    // "borrar+agregar" por separado.
    val statuses = ArrayList<LineStatus>(m)
    var i = 0
    var j = 0
    while (j < m) {
        if (i < n && oldLines[i] == newLines[j]) {
            statuses.add(LineStatus.UNCHANGED)
            i++
            j++
        } else if (i < n && dp[i + 1][j] >= dp[i][j + 1]) {
            // Línea vieja "de sobra" descartada en este tramo: la línea
            // nueva que sigue ocupa su lugar -> reemplazo (MODIFIED), no
            // una inserción pura.
            statuses.add(LineStatus.MODIFIED)
            i++
            j++
        } else {
            statuses.add(LineStatus.ADDED)
            j++
        }
    }
    return statuses
}
