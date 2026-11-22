package com.codemobile.exec

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Ejecuta un script .sql contra una base SQLite persistente por archivo:
 * CREATE TABLE corre una vez y los runs futuros solo hacen SELECT/INSERT
 * sobre los datos ya existentes. El .db vive junto al .sql, mismo nombre
 * base, misma carpeta — así "db.sql" persiste en "db.sql.db", visible y
 * copiable junto al script como cualquier archivo real del proyecto (no
 * escondido en almacenamiento interno de la app, ya que el usuario puede
 * querer versionarlo o inspeccionarlo aparte).
 *
 * android.database.sqlite ya viene con el SDK de Android: no hace falta
 * ninguna dependencia nueva (a diferencia de Chaquopy/Rhino para Python/JS).
 */
object SqlRunner {

    /** Sufijo fijo del archivo de base de datos derivado del .sql. Un
     *  simple "${file.name}.db" en vez de reemplazar la extensión evita
     *  cualquier ambigüedad con nombres de archivo que ya contengan puntos
     *  (ej. "v1.2.sql" -> "v1.2.sql.db", nunca "v1.db" por error). */
    private const val DB_SUFFIX = ".db"

    fun run(sqlFile: File?, code: String): ExecutionResult {
        val startTime = System.currentTimeMillis()
        val output = StringBuilder()
        var errorMessage: String? = null

        // Sin archivo real respaldando la pestaña (sin título, nunca
        // guardado) no hay dónde persistir un .db junto a él — se cae a
        // una base en memoria, que al menos permite probar el script en
        // esa sesión de Run aunque no sobreviva a cerrar la pestaña. Esto
        // es un fallback razonable, no el camino principal: persistencia
        // real requiere Guardar el .sql al menos una vez.
        val db = if (sqlFile != null) {
            val dbFile = File(sqlFile.parentFile, sqlFile.name + DB_SUFFIX)
            SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        } else {
            SQLiteDatabase.create(null)
        }

        try {
            // Split simple por ';' — no maneja ';' dentro de strings o
            // comentarios, suficiente para el alcance de principiantes de
            // este MVP (scripts de CREATE TABLE/INSERT/SELECT directos,
            // sin triggers ni procedimientos con cuerpos que contengan ';').
            val statements = code.split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            for (stmt in statements) {
                val upper = stmt.trimStart().uppercase()
                if (upper.startsWith("SELECT")) {
                    db.rawQuery(stmt, null).use { cursor ->
                        output.append(formatResultSet(cursor))
                    }
                } else {
                    db.execSQL(stmt)
                    output.append("OK (${stmt.take(40)}${if (stmt.length > 40) "…" else ""})\n")
                }
            }
        } catch (e: Exception) {
            errorMessage = e.message ?: e.toString()
        } finally {
            db.close()
        }

        val duration = System.currentTimeMillis() - startTime
        return ExecutionResult(output.toString(), errorMessage, duration)
    }

    /** Formatea un Cursor de SELECT como tabla de texto monoespaciado,
     *  columnas separadas por " | ", ancho de cada columna ajustado al
     *  valor más largo que contiene (incluyendo su propio header) — nada
     *  sofisticado, solo legible en la consola de texto existente sin
     *  necesitar una vista de tabla dedicada. */
    private fun formatResultSet(cursor: Cursor): String {
        val columns = cursor.columnNames
        val rows = mutableListOf<List<String>>()
        while (cursor.moveToNext()) {
            rows.add((0 until cursor.columnCount).map { idx ->
                cursor.getString(idx) ?: "NULL"
            })
        }
        if (rows.isEmpty()) return "(sin resultados)\n"

        val widths = columns.indices.map { idx ->
            maxOf(columns[idx].length, rows.maxOf { it[idx].length })
        }
        val sb = StringBuilder()
        sb.append(columns.indices.joinToString(" | ") { columns[it].padEnd(widths[it]) }).append("\n")
        sb.append(widths.joinToString("-+-") { "-".repeat(it) }).append("\n")
        for (row in rows) {
            sb.append(row.indices.joinToString(" | ") { row[it].padEnd(widths[it]) }).append("\n")
        }
        return sb.toString()
    }
}
