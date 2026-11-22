package com.codemobile.ui

import android.content.Context
import com.codemobile.exec.ExecutionDispatcher
import com.codemobile.files.FileRepository

/**
 * runCurrentFile() extraído de EditorActivity tal cual, sin cambiar
 * ninguna regla. Es un orquestador transversal genuino (toca Pestañas,
 * Consola y Preview HTML a la vez para una sola operación), así que recibe
 * referencias directas a los tres controllers en vez de lambdas sueltas —
 * ver informe de diseño §3.
 */
class RunController(
    private val context: Context,
    private val tabs: TabsController,
    private val console: ConsoleController,
    private val htmlPreview: HtmlPreviewController,
    private val openActionsPanel: () -> Unit
) {

    fun runCurrentFile() {
        tabs.persistCurrentFile()
        val of = tabs.openFiles.getOrNull(tabs.activeIndex) ?: return
        val lang = FileRepository.langFor(of.extension)
        val code = of.content.toString()

        // HTML no tiene stdout/stderr que mostrar en la consola de texto —
        // "ejecutarlo" es simplemente renderizarlo, en un panel separado.
        if (lang == FileRepository.Lang.HTML) {
            htmlPreview.showHtmlPreview(code, of.file)
            return
        }

        // Kotlin no tiene runner propio (ver ExecutionDispatcher): lo único
        // útil que Run puede hacer acá es abrir el panel de GitHub Actions
        // (si ya hay un workflow configurado en el repo, el usuario lo
        // dispara/sigue desde ahí; si no hay repo o no corrió nada, el
        // panel mismo lo informa — ver ActionsPanelController.
        // resolveContextOrWarn/loadRuns, sin duplicar esa lógica acá).
        if (lang == FileRepository.Lang.KOTLIN) {
            openActionsPanel()
            return
        }

        console.showConsole()
        console.appendConsole("▶ Ejecutando ${of.displayName}.${of.extension}…\n")

        ExecutionDispatcher.run(
            context,
            code,
            lang,
            sqlFile = of.file,
            onInputRequest = { pending -> console.onStdinRequest(pending) }
        ) { result ->
            if (result.output.isNotEmpty()) console.appendConsole(result.output)
            if (result.error != null) console.appendConsole("\n⚠ ${result.error}\n")
            console.appendConsole("— listo en ${result.durationMs}ms —\n")
        }
    }
}
