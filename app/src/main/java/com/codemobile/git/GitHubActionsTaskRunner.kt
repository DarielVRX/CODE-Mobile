package com.codemobile.git

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Ejecutor en segundo plano para las llamadas de GitHubActionsRepository
 * (listar runs/jobs, bajar un log, bajar un artifact) — deliberadamente
 * separado de GitForegroundService/GitTaskBus (ver esos archivos): esas
 * llamadas son pedidos HTTP de API, no operaciones git largas sobre el
 * working tree, así que no justifican un foreground Service con
 * notificación persistente ni comparten el bloqueo de "un comando git a la
 * vez" (un fetch de runs no debe esperar a que termine un push, ni
 * viceversa).
 *
 * Sin cola ni "una tarea a la vez" global: a diferencia de GitTaskBus, cada
 * llamada de ActionsPanelController corre independiente — el propio panel
 * decide si bloquea su UI mientras una carga está en curso (ver
 * isLoadingRuns/etc. ahí), no este runner.
 */
object GitHubActionsTaskRunner {

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    /** Corre [work] en Dispatchers.IO y entrega [onResult] en el hilo
     *  principal — mismo patrón que GitForegroundService.start, sin la
     *  parte de notificación/Service. [onMainThread] es la misma lambda
     *  runOnUiThread que ya usa ConsoleController (ver EditorActivity). */
    fun <T> run(
        onMainThread: (() -> Unit) -> Unit,
        work: suspend () -> GitHubActionsRepository.ActionsResult<T>,
        onResult: (GitHubActionsRepository.ActionsResult<T>) -> Unit
    ) {
        scope.launch {
            val result = try {
                work()
            } catch (e: Exception) {
                GitHubActionsRepository.ActionsResult.Failure(e.message ?: "Unexpected error")
            }
            onMainThread { onResult(result) }
        }
    }
}
