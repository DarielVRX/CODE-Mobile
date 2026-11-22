package com.codemobile.exec

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.codemobile.files.FileRepository.Lang
import java.io.File
import java.util.concurrent.Executors

/**
 * Corre el script en un hilo de fondo (nunca en el hilo de UI) y entrega el
 * resultado en el hilo principal vía callback, para que el panel de consola
 * se actualice de forma segura.
 */
object ExecutionDispatcher {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun run(
        context: Context,
        code: String,
        lang: Lang,
        sqlFile: File?,
        onInputRequest: (pendingOutput: String) -> String,
        onResult: (ExecutionResult) -> Unit
    ) {
        executor.execute {
            // onInputRequest se invoca y BLOQUEA este hilo de fondo (nunca el de
            // UI) mientras espera que el usuario escriba algo en la consola; ver
            // PythonRunner.run para el puente real hacia sys.stdin. Recibe el
            // texto de stdout/stderr acumulado desde el último aviso, para que
            // se pueda volcar a consola (junto con el prompt) antes de bloquear.
            val result = when (lang) {
                // sqlFile es, en realidad, siempre el archivo activo (ver
                // RunController, único caller) — no algo exclusivo de SQL;
                // se reutiliza tal cual para PythonRunner (Paso 7,
                // extensión: agrega la raíz del proyecto a sys.path para
                // resolver imports reales a otros archivos del usuario,
                // ver doc de PythonRunner.run), en vez de agregar un
                // segundo parámetro redundante con el mismo valor.
                Lang.PYTHON -> PythonRunner.run(context, code, sqlFile, onInputRequest)
                // Mismo motivo que PYTHON: sqlFile es el archivo activo, se
                // reutiliza para que JsRunner pueda resolver imports/exports
                // de ES modules reales a otros archivos (ver JsModuleLoader).
                Lang.JAVASCRIPT -> JsRunner.run(code, sqlFile)
                // SqlRunner deriva de sqlFile la ruta del .db persistente
                // (puede ser null si la pestaña nunca se guardó), ver su
                // doc. HTML nunca llega hasta acá — se intercepta antes, en
                // EditorActivity.runCurrentFile, porque no tiene nada que
                // correr en este dispatcher de fondo.
                Lang.SQL -> SqlRunner.run(sqlFile, code)
                Lang.HTML -> ExecutionResult(
                    output = "",
                    error = "HTML se muestra en el panel de preview, no en la consola.",
                    durationMs = 0
                )
                // Kotlin no tiene runner propio (no hay JVM/kotlinc embebido en
                // el dispositivo): mismo mensaje que PLAIN, no "todavía" sino
                // "no soportado", para no insinuar que es una omisión temporal.
                Lang.KOTLIN -> ExecutionResult(
                    output = "",
                    error = "Can't run Kotlin files from the app.",
                    durationMs = 0
                )
                Lang.PLAIN -> ExecutionResult(
                    output = "",
                    error = "Can't run this file type yet.",
                    durationMs = 0
                )
            }
            mainHandler.post { onResult(result) }
        }
    }
}
