package com.codemobile.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codemobile.R
import com.codemobile.git.GitHubActionsRepository
import com.codemobile.git.GitHubActionsRepository.ActionsResult
import com.codemobile.git.GitHubActionsTaskRunner

/**
 * Visor de log de un job de GitHub Actions a pantalla completa, dividido
 * en steps colapsables — reemplaza mostrar el log como texto plano en
 * ConsoleController (ver ActionsPanelController, que antes volcaba el log
 * completo ahí): un log de CI puede ser largo y sin estructura, difícil de
 * leer en una consola de celular. Mismo patrón de overlay full-screen que
 * HtmlPreviewController (FrameLayout hermano del root, back handling
 * propio registrado deshabilitado hasta que el visor está visible).
 *
 * Pide dos cosas en paralelo-secuencial (steps primero, log después,
 * ver showLog): GitHubActionsRepository.getJobSteps (estructura real,
 * JSON) y downloadJobLogs (texto plano) — luego
 * GitHubActionsRepository.groupLogLinesByStep correlaciona cada línea con
 * su step por timestamp (ver esa función: nunca por parseo frágil de
 * "##[group]"). El step con conclusion == "failure" arranca expandido y
 * resaltado (error_highlight) para que el usuario vea "dónde ocurrió el
 * error" sin tener que buscarlo entre los demás.
 */
class ActionsLogViewerController(
    private val container: FrameLayout,
    private val layoutInflater: LayoutInflater,
    private val onBackPressedDispatcher: OnBackPressedDispatcher,
    private val lifecycleOwner: LifecycleOwner
) {

    private var backCallback: OnBackPressedCallback? = null
    private var titleText: TextView? = null
    private var statusText: TextView? = null
    private var stepsRecycler: RecyclerView? = null

    fun setupBackHandling() {
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                close()
            }
        }
        onBackPressedDispatcher.addCallback(lifecycleOwner, backCallback!!)
    }

    private fun ensureInflated() {
        if (container.childCount > 0) return
        val view = layoutInflater.inflate(R.layout.view_actions_log_viewer, container, false)
        titleText = view.findViewById(R.id.logViewerTitle)
        statusText = view.findViewById(R.id.logViewerStatusText)
        stepsRecycler = view.findViewById<RecyclerView>(R.id.logViewerStepsRecycler).also {
            it.layoutManager = LinearLayoutManager(it.context)
        }
        view.findViewById<View>(R.id.logViewerClose).setOnClickListener { close() }
        container.addView(view)
    }

    fun close() {
        container.visibility = View.GONE
        backCallback?.isEnabled = false
    }

    /** Abre el visor para el job [jobId] del run [owner]/[repo] y arranca
     *  la carga: primero los steps (getJobSteps), y recién con esos en
     *  mano, el log de texto (downloadJobLogs) — en ese orden porque
     *  groupLogLinesByStep necesita los steps para correlacionar; pedirlos
     *  en paralelo no ahorraría una llamada real (son dos endpoints
     *  distintos de todos modos) y complicaría el manejo de errores sin
     *  necesidad. [runOnUiThread] y [onError]/éxito siguen el mismo patrón
     *  que el resto de ActionsPanelController. */
    fun show(
        jobName: String,
        owner: String,
        repo: String,
        jobId: Long,
        token: String?,
        runOnUiThread: (() -> Unit) -> Unit
    ) {
        ensureInflated()
        titleText?.text = jobName
        statusText?.visibility = View.VISIBLE
        statusText?.text = "Loading log..."
        stepsRecycler?.adapter = null
        container.visibility = View.VISIBLE
        backCallback?.isEnabled = true

        GitHubActionsTaskRunner.run(
            onMainThread = runOnUiThread,
            work = { GitHubActionsRepository.getJobSteps(owner, repo, jobId, token) },
            onResult = { stepsResult ->
                when (stepsResult) {
                    is ActionsResult.Failure -> {
                        statusText?.visibility = View.VISIBLE
                        statusText?.text = stepsResult.message
                    }
                    is ActionsResult.Success -> loadLogAndRender(owner, repo, jobId, token, stepsResult.data, runOnUiThread)
                }
            }
        )
    }

    private fun loadLogAndRender(
        owner: String,
        repo: String,
        jobId: Long,
        token: String?,
        steps: List<GitHubActionsRepository.WorkflowStep>,
        runOnUiThread: (() -> Unit) -> Unit
    ) {
        GitHubActionsTaskRunner.run(
            onMainThread = runOnUiThread,
            work = { GitHubActionsRepository.downloadJobLogs(owner, repo, jobId, token) },
            onResult = { logResult ->
                when (logResult) {
                    is ActionsResult.Failure -> {
                        statusText?.visibility = View.VISIBLE
                        statusText?.text = logResult.message
                    }
                    is ActionsResult.Success -> renderSteps(steps, logResult.data)
                }
            }
        )
    }

    /** Si [steps] viene vacío (job sin steps reportados todavía, ej. muy
     *  temprano en su ejecución) o ninguna línea pudo asociarse a un step
     *  real (groupLogLinesByStep devolvió todo con stepNumber == null —
     *  log vacío, o GitHub cambió el formato), se cae al fallback: un
     *  único "step" sintético con el log completo sin dividir. Nunca se
     *  oculta el log real por no poder estructurarlo. */
    private fun renderSteps(steps: List<GitHubActionsRepository.WorkflowStep>, rawLog: String) {
        statusText?.visibility = View.GONE
        val lines = GitHubActionsRepository.groupLogLinesByStep(rawLog, steps)
        val anyLineMatchedAStep = lines.any { it.stepNumber != null }

        val entries: List<StepEntry> = if (steps.isNotEmpty() && anyLineMatchedAStep) {
            steps.map { step ->
                val stepLines = lines.filter { it.stepNumber == step.number }.joinToString("\n") { it.text }
                StepEntry(step, stepLines)
            }
        } else {
            listOf(StepEntry(null, rawLog))
        }

        val failedStepNumber = steps.firstOrNull { it.conclusion == "failure" }?.number
        stepsRecycler?.adapter = StepAdapter(entries, failedStepNumber)
    }

    /** [step] null solo en el fallback (ver renderSteps) — un step
     *  sintético sin datos reales de GitHub, solo el log completo. */
    private data class StepEntry(val step: GitHubActionsRepository.WorkflowStep?, val logText: String)

    /** LIMITACIÓN CONOCIDA: el estado expandido/colapsado de un item vive
     *  solo en su View (logText.visibility), no en un modelo aparte — si
     *  RecyclerView recicla el ViewHolder de un step que el usuario
     *  expandió a mano (scroll largo en un workflow con muchos steps),
     *  ese holder vuelve a bindearse con el estado inicial (startExpanded)
     *  en vez de recordar que el usuario lo había tocado. Aceptable para
     *  esta primera versión (la cantidad típica de steps de un job cabe
     *  sin scroll relevante); si se vuelve un problema real, la solución
     *  es mover el estado a un Set<Int> de steps expandidos en el
     *  controller y leerlo en bindViewHolder en vez de recalcular
     *  startExpanded siempre igual. */
    private class StepAdapter(
        private val entries: List<StepEntry>,
        private val failedStepNumber: Int?
    ) : RecyclerView.Adapter<StepAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val headerRow: View = view.findViewById(R.id.stepHeaderRow)
            val statusIcon: TextView = view.findViewById(R.id.stepStatusIcon)
            val nameText: TextView = view.findViewById(R.id.stepNameText)
            val expandIcon: TextView = view.findViewById(R.id.stepExpandIcon)
            val logText: TextView = view.findViewById(R.id.stepLogText)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, position: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.view_actions_log_step_item, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = entries[position]
            val step = entry.step
            val isFailedStep = step != null && step.number == failedStepNumber

            holder.nameText.text = step?.name ?: "Full log"
            holder.statusIcon.text = if (step == null) "●" else statusIconFor(step.status, step.conclusion)
            val headerColorRes = if (isFailedStep) R.color.error_highlight else R.color.bg_elevated
            holder.headerRow.setBackgroundColor(ContextCompat.getColor(holder.headerRow.context, headerColorRes))
            holder.logText.text = entry.logText.ifBlank { "(no output)" }

            // El step fallido (o el único "step" del fallback) arranca
            // expandido — ver doc de clase: el usuario debe ver el error
            // sin tener que expandir uno por uno. Los demás arrancan
            // colapsados para no abrumar con el log completo de un
            // workflow con muchos steps exitosos.
            val startExpanded = isFailedStep || entries.size == 1
            holder.logText.visibility = if (startExpanded) View.VISIBLE else View.GONE
            holder.expandIcon.text = if (startExpanded) "⌃" else "⌄"

            holder.headerRow.setOnClickListener {
                val expanded = holder.logText.visibility == View.VISIBLE
                holder.logText.visibility = if (expanded) View.GONE else View.VISIBLE
                holder.expandIcon.text = if (expanded) "⌄" else "⌃"
            }
        }
    }
}

/** Mismo criterio que statusIconFor en ActionsPanelController (no se
 *  reusa esa función privada de otro archivo: son símbolos de texto
 *  triviales, duplicarlos acá es más simple que exportar una utilidad
 *  compartida para tres líneas de when). */
private fun statusIconFor(status: String, conclusion: String?): String {
    if (status != "completed") {
        return when (status) {
            "in_progress" -> "⏳"
            "queued" -> "●"
            else -> "●"
        }
    }
    return when (conclusion) {
        "success" -> "✓"
        "failure" -> "✗"
        "cancelled" -> "⊘"
        "skipped" -> "⊝"
        else -> "●"
    }
}
