package com.codemobile.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codemobile.R
import com.codemobile.files.FileRepository
import com.codemobile.git.ArtifactDownloadService
import com.codemobile.git.GitCredentialsStore
import com.codemobile.git.GitHubActionsRepository
import com.codemobile.git.GitHubActionsRepository.ActionsResult
import com.codemobile.git.GitHubActionsTaskRunner
import com.codemobile.git.GitRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Panel de GitHub Actions (botón ▶, ver explorerHeaderRow en
 * activity_editor.xml): lista de runs del repo en currentDir (incluidos
 * los en curso), sus jobs, el log de un job, y los artifacts de un run con
 * descarga manual.
 *
 * Reusa infraestructura ya existente en vez de duplicarla: el mismo PAT
 * global de GitCredentialsStore (ver su doc — un repo, un token, igual que
 * los comandos git); GitHubActionsTaskRunner en vez de GitForegroundService
 * (ver su doc de clase: estas son llamadas de API rápidas, no operaciones
 * largas sobre el working tree, así que no comparten el bloqueo "un
 * comando git a la vez" ni necesitan notificación persistente);
 * [logViewer] (ActionsLogViewerController) para mostrar el log de un job
 * dividido por steps a pantalla completa, en vez de volcarlo como texto
 * plano en la consola git; y [startCustomTransfer] (ExplorerController)
 * para que el usuario elija la carpeta destino ANTES de descargar un
 * artifact — la descarga escribe directo ahí (ver downloadArtifact), sin
 * archivo temporal intermedio.
 *
 * Un solo overlay (dropdown de runs, de jobs o de artifacts) a la vez,
 * reemplazándose entre sí al navegar — mismo criterio que
 * GitActionsController.closeOverlay.
 *
 * Anclado siempre hacia ARRIBA del botón que lo abre, usando como límite
 * el borde superior de [explorerContainer] (ver openOverlayAbove). Antes
 * (Paso 13 y anteriores) anclaba hacia ABAJO, porque explorerHeaderRow
 * vivía arriba del todo del explorador y había espacio real debajo; al
 * reubicar esa fila al fondo del panel (pedido del usuario), el botón que
 * abre este panel quedó cerca del borde inferior de la pantalla, así que
 * el espacio real para crecer está arriba.
 *
 * Paso 13: [explorerContainer] acota el panel a la sección del explorador
 * (pedido explícito del usuario) — aunque [anchorContainer] sigue siendo
 * MATCH_PARENT (es solo el lienzo para el touch-fuera-cierra, ver
 * closeOverlay), el contenido real nunca debe ser más ancho que el panel
 * lateral ni extenderse sobre el área del editor (ver openOverlayAbove).
 */
class ActionsPanelController(
    private val layoutInflater: LayoutInflater,
    private val anchorContainer: FrameLayout,
    private val explorerContainer: View,
    private val logViewer: ActionsLogViewerController,
    private val currentDir: () -> File,
    private val startCustomTransfer: (toastMessage: String, onConfirm: (destDir: File) -> Unit) -> Unit,
    private val actionsButton: View,
    private val runOnUiThread: (() -> Unit) -> Unit
) {

    private var openOverlay: View? = null

    fun setup() {
        actionsButton.setOnClickListener { showRunsPanel() }
    }

    private fun closeOverlay() {
        openOverlay?.let { anchorContainer.removeView(it) }
        openOverlay = null
        anchorContainer.visibility = View.GONE
    }

    /** Ancla [content] siempre ARRIBA de [anchor], ocupando TODO el hueco
     *  vertical disponible entre el borde superior de [explorerContainer]
     *  (el árbol de archivos) y el borde superior de [anchor] — pedido
     *  explícito: el panel debe crecer de arriba hacia abajo llenando ese
     *  espacio, ignorando dónde esté el botón que lo activa, en vez de
     *  medir su propio contenido y quedar flotando chico con espacio
     *  libre arriba sin usar. runsListRecycler (ver view_actions_panel.xml)
     *  usa layout_weight para ocupar lo que sobre de esa altura fija y
     *  scrollea internamente si hay más runs/jobs/artifacts de los que
     *  entran.
     *
     *  Ancho y posición horizontal también acotados a [explorerContainer]
     *  (pedido explícito: el panel de Actions ocupa solo la sección del
     *  explorador, nunca se extiende sobre el editor) — antes se alineaba
     *  por la derecha del propio [anchor] con un ancho fijo del XML
     *  (280dp), lo que podía desbordar el panel lateral si este era más
     *  angosto, o verse desproporcionado si era más ancho (ver pedido de
     *  redimensionar manualmente el explorador). */
    private fun openOverlayAbove(anchor: View, content: View) {
        closeOverlay()
        anchorContainer.visibility = View.VISIBLE
        anchorContainer.addView(content)
        anchorContainer.setOnTouchListener { _, _ -> closeOverlay(); true }
        content.setOnClickListener { /* evita que un tap dentro del panel cierre por el listener de arriba */ }
        openOverlay = content

        content.post {
            val anchorLoc = IntArray(2)
            anchor.getLocationOnScreen(anchorLoc)
            val containerLoc = IntArray(2)
            anchorContainer.getLocationOnScreen(containerLoc)
            val explorerLoc = IntArray(2)
            explorerContainer.getLocationOnScreen(explorerLoc)
            val lp = content.layoutParams as FrameLayout.LayoutParams

            // Altura fija = todo el hueco entre el techo del explorador
            // y el botón, no el contenido medido — ver doc de función.
            val topOfExplorer = explorerLoc[1] - containerLoc[1]
            val topOfAnchor = anchorLoc[1] - containerLoc[1]
            val finalHeight = (topOfAnchor - topOfExplorer).coerceAtLeast(0)
            lp.height = finalHeight

            // Ancho y posición horizontal: siempre los del explorador
            // (ignora el ancho propio del XML inflado, ver doc de clase).
            lp.width = explorerContainer.width
            lp.leftMargin = explorerLoc[0] - containerLoc[0]
            lp.topMargin = topOfExplorer
            content.layoutParams = lp
        }
    }

    // ---- Resolución de owner/repo + token, compartida por las tres vistas ----

    /** Contexto resuelto una sola vez por apertura del panel: si no hay
     *  repo o no se pudo resolver el remoto, ninguna de las tres vistas
     *  (runs/jobs/artifacts) tiene sentido — se informa un solo mensaje
     *  claro en vez de que cada función repita la misma validación. */
    private fun resolveContextOrWarn(onFailure: () -> Unit): Triple<String, String, String?>? {
        val repoRoot = GitRepository.findRepoRoot(currentDir())
        if (repoRoot == null) {
            toast("No git repository in this folder or any parent folder.")
            onFailure()
            return null
        }
        val ownerRepo = GitHubActionsRepository.resolveOwnerRepo(repoRoot)
        if (ownerRepo == null) {
            toast("The \"origin\" remote isn't on GitHub (or isn't configured).")
            onFailure()
            return null
        }
        val token = GitCredentialsStore.getToken(anchorContainer.context)
        return Triple(ownerRepo.first, ownerRepo.second, token)
    }

    private fun toast(message: String) = Toast.makeText(anchorContainer.context, message, Toast.LENGTH_LONG).show()

    // ---- Vista 1: lista de runs ----

    /** Público (a diferencia del resto de las vistas del panel, todas
     *  privadas): RunController la invoca directamente cuando el botón Run
     *  se toca sobre un archivo Kotlin (ver su doc) — no hay runner de
     *  Kotlin en la app, así que ahí lo útil es abrir este mismo panel que
     *  ya abre actionsButton, en vez de duplicar su lógica de resolución de
     *  repo/owner/token. */
    fun showRunsPanel() {
        val view = layoutInflater.inflate(R.layout.view_actions_panel, anchorContainer, false)
        view.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)

        val statusText = view.findViewById<TextView>(R.id.actionsStatusText)
        val recycler = view.findViewById<RecyclerView>(R.id.runsListRecycler)
        recycler.layoutManager = LinearLayoutManager(recycler.context)
        view.findViewById<View>(R.id.actionsRefreshButton).setOnClickListener { loadRuns(statusText, recycler) }

        openOverlayAbove(actionsButton, view)
        loadRuns(statusText, recycler)
    }

    private fun loadRuns(statusText: TextView, recycler: RecyclerView) {
        val context = resolveContextOrWarn { closeOverlay() } ?: return
        val (owner, repo, token) = context
        statusText.visibility = View.VISIBLE
        statusText.text = "Loading runs..."
        recycler.adapter = null

        GitHubActionsTaskRunner.run(
            onMainThread = runOnUiThread,
            work = { GitHubActionsRepository.listRuns(owner, repo, token) },
            onResult = { result ->
                when (result) {
                    is ActionsResult.Success -> {
                        if (result.data.isEmpty()) {
                            statusText.visibility = View.VISIBLE
                            // Dos motivos posibles para "cero runs", y se
                            // informan distinto: si el repo ni siquiera
                            // tiene un workflow declarado (chequeo de
                            // filesystem, sin red — ver hasWorkflowFiles),
                            // lo accionable es sugerir crear uno; si SÍ hay
                            // workflow pero nunca corrió, es simplemente el
                            // estado inicial normal, nada que sugerir.
                            val repoRoot = GitRepository.findRepoRoot(currentDir())
                            statusText.text = if (repoRoot != null && !GitHubActionsRepository.hasWorkflowFiles(repoRoot)) {
                                "This repository has no workflow configured (.github/workflows). Add one to run CI here."
                            } else {
                                "No runs yet"
                            }
                        } else {
                            statusText.visibility = View.GONE
                            recycler.adapter = RunAdapter(result.data) { run ->
                                showJobsPanel(owner, repo, run, token)
                            }
                        }
                    }
                    is ActionsResult.Failure -> {
                        statusText.visibility = View.VISIBLE
                        statusText.text = result.message
                    }
                }
            }
        )
    }

    private class RunAdapter(
        private val runs: List<GitHubActionsRepository.WorkflowRun>,
        private val onRunTapped: (GitHubActionsRepository.WorkflowRun) -> Unit
    ) : RecyclerView.Adapter<RunAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: TextView = view.findViewById(R.id.runStatusIcon)
            val name: TextView = view.findViewById(R.id.runNameText)
            val meta: TextView = view.findViewById(R.id.runMetaText)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, position: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.view_actions_run_item, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount(): Int = runs.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val run = runs[position]
            holder.icon.text = statusIconFor(run.status, run.conclusion)
            holder.name.text = run.name
            holder.meta.text = "${run.branch} · ${statusLabelFor(run.status, run.conclusion)} · ${formatDate(run.createdAt)}"
            holder.itemView.setOnClickListener { onRunTapped(run) }
        }
    }

    // ---- Vista 2: jobs de un run ----

    private fun showJobsPanel(owner: String, repo: String, run: GitHubActionsRepository.WorkflowRun, token: String?) {
        val view = layoutInflater.inflate(R.layout.view_actions_panel, anchorContainer, false)
        view.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        view.findViewById<TextView>(R.id.actionsRefreshButton).text = "⬇"
        view.findViewById<TextView>(R.id.actionsRefreshButton).setOnClickListener { showArtifactsPanel(owner, repo, run, token) }

        val statusText = view.findViewById<TextView>(R.id.actionsStatusText)
        val recycler = view.findViewById<RecyclerView>(R.id.runsListRecycler)
        recycler.layoutManager = LinearLayoutManager(recycler.context)
        statusText.visibility = View.VISIBLE
        statusText.text = "Loading jobs..."

        openOverlayAbove(actionsButton, view)

        GitHubActionsTaskRunner.run(
            onMainThread = runOnUiThread,
            work = { GitHubActionsRepository.listJobs(owner, repo, run.id, token) },
            onResult = { result ->
                when (result) {
                    is ActionsResult.Success -> {
                        if (result.data.isEmpty()) {
                            statusText.visibility = View.VISIBLE
                            statusText.text = "No jobs yet"
                        } else {
                            statusText.visibility = View.GONE
                            recycler.adapter = JobAdapter(result.data) { job ->
                                closeOverlay()
                                logViewer.show(job.name, owner, repo, job.id, token, runOnUiThread)
                            }
                        }
                    }
                    is ActionsResult.Failure -> {
                        statusText.visibility = View.VISIBLE
                        statusText.text = result.message
                    }
                }
            }
        )
    }

    private class JobAdapter(
        private val jobs: List<GitHubActionsRepository.WorkflowJob>,
        private val onJobTapped: (GitHubActionsRepository.WorkflowJob) -> Unit
    ) : RecyclerView.Adapter<JobAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: TextView = view.findViewById(R.id.jobStatusIcon)
            val name: TextView = view.findViewById(R.id.jobNameText)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, position: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.view_actions_job_item, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount(): Int = jobs.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val job = jobs[position]
            holder.icon.text = statusIconFor(job.status, job.conclusion)
            holder.name.text = job.name
            holder.itemView.setOnClickListener { onJobTapped(job) }
        }
    }

    // ---- Vista 3: artifacts de un run ----

    private fun showArtifactsPanel(owner: String, repo: String, run: GitHubActionsRepository.WorkflowRun, token: String?) {
        val view = layoutInflater.inflate(R.layout.view_actions_panel, anchorContainer, false)
        view.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        view.findViewById<TextView>(R.id.actionsRefreshButton).visibility = View.GONE

        val statusText = view.findViewById<TextView>(R.id.actionsStatusText)
        val recycler = view.findViewById<RecyclerView>(R.id.runsListRecycler)
        recycler.layoutManager = LinearLayoutManager(recycler.context)
        statusText.visibility = View.VISIBLE
        statusText.text = "Loading artifacts..."

        openOverlayAbove(actionsButton, view)

        GitHubActionsTaskRunner.run(
            onMainThread = runOnUiThread,
            work = { GitHubActionsRepository.listArtifacts(owner, repo, run.id, token) },
            onResult = { result ->
                when (result) {
                    is ActionsResult.Success -> {
                        if (result.data.isEmpty()) {
                            statusText.visibility = View.VISIBLE
                            statusText.text = "No artifacts for this run"
                        } else {
                            statusText.visibility = View.GONE
                            recycler.adapter = ArtifactAdapter(result.data) { artifact ->
                                downloadArtifact(owner, repo, artifact)
                            }
                        }
                    }
                    is ActionsResult.Failure -> {
                        statusText.visibility = View.VISIBLE
                        statusText.text = result.message
                    }
                }
            }
        )
    }

    /** Elige la carpeta destino ANTES de descargar (ver
     *  ExplorerController.startCustomTransfer): nada se escribe a disco
     *  hasta que el usuario confirma con ✓ sobre la carpeta donde navegó.
     *  Un solo paso en vez de dos (descargar a un temporal y recién
     *  después moverlo) — más simple para el usuario y sin archivo
     *  intermedio que limpiar si cancela. Como la carpeta ya es la FINAL
     *  al confirmar, el conflicto de nombre se chequea ahí mismo: si
     *  "$safeName" ya existe, se le pregunta al usuario si sobrescribir o
     *  renombrar (sufijo automático vía FileRepository.uniqueFileIn) en
     *  vez de decidirlo por él en silencio. */
    private fun downloadArtifact(owner: String, repo: String, artifact: GitHubActionsRepository.Artifact) {
        closeOverlay()
        val safeName = if (artifact.name.ifBlank { "artifact" }.endsWith(".zip")) {
            artifact.name
        } else {
            "${artifact.name.ifBlank { "artifact" }}.zip"
        }
        startCustomTransfer(
            "Navigate to the destination folder and confirm (✓) to download \"${artifact.name}\"."
        ) { destDir ->
            val targetFile = File(destDir, safeName)
            if (targetFile.exists()) {
                AlertDialog.Builder(anchorContainer.context)
                    .setTitle("File already exists")
                    .setMessage("\"$safeName\" already exists in this folder.")
                    .setPositiveButton("Overwrite") { _, _ ->
                        beginArtifactDownload(owner, repo, artifact, targetFile)
                    }
                    .setNegativeButton("Rename") { _, _ ->
                        beginArtifactDownload(owner, repo, artifact, FileRepository.uniqueFileIn(destDir, safeName))
                    }
                    .show()
            } else {
                beginArtifactDownload(owner, repo, artifact, targetFile)
            }
        }
    }

    /** Dispara la descarga en sí sobre [destFile], ya resuelto (sobrescritura
     *  o renombre decidido en [downloadArtifact]). Corre en
     *  ArtifactDownloadService (no en GitHubActionsTaskRunner): a
     *  diferencia de listar runs/jobs, una descarga puede tardar y debe
     *  sobrevivir aunque el usuario mande la app a segundo plano — el
     *  Service arranca foreground con notificación propia y entrega el
     *  resultado directo a ArtifactDownloadNotifier, sin volver a este
     *  controller (que puede no seguir vivo para entonces). */
    private fun beginArtifactDownload(
        owner: String,
        repo: String,
        artifact: GitHubActionsRepository.Artifact,
        destFile: File
    ) {
        toast("Downloading \"${artifact.name}\"...")
        ArtifactDownloadService.start(
            anchorContainer.context,
            owner, repo, artifact.id, artifact.name,
            GitCredentialsStore.getToken(anchorContainer.context),
            destFile
        )
    }

    private class ArtifactAdapter(
        private val artifacts: List<GitHubActionsRepository.Artifact>,
        private val onDownloadTapped: (GitHubActionsRepository.Artifact) -> Unit
    ) : RecyclerView.Adapter<ArtifactAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.artifactNameText)
            val meta: TextView = view.findViewById(R.id.artifactMetaText)
            val downloadButton: View = view.findViewById(R.id.artifactDownloadButton)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, position: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.view_actions_artifact_item, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount(): Int = artifacts.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val artifact = artifacts[position]
            holder.name.text = artifact.name
            val sizeKb = artifact.sizeInBytes / 1024
            holder.meta.text = if (artifact.expired) "Expirado" else "$sizeKb KB"
            holder.downloadButton.isEnabled = !artifact.expired
            holder.downloadButton.alpha = if (artifact.expired) 0.4f else 1f
            holder.downloadButton.setOnClickListener {
                if (!artifact.expired) onDownloadTapped(artifact)
            }
        }
    }
}

/** [conclusion] manda sobre [status] una vez que el run/job terminó
 *  ("completed" no dice si salió bien o mal por sí solo). Mientras sigue
 *  en curso, conclusion es null y se usa [status] directamente. */
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
        else -> "●"
    }
}

private fun statusLabelFor(status: String, conclusion: String?): String {
    if (status != "completed") return status
    return conclusion ?: status
}

// DATE_FORMAT formatea en la zona local del dispositivo (Locale.getDefault,
// sin forzar TimeZone) — igual que GitRepository.log() ya hace con las
// fechas de commit. ISO_FORMAT en cambio DEBE parsear en UTC explícito: el
// sufijo "Z" de GitHub ("2026-11-21T20:15:03Z") es literal en el patrón,
// no una zona real, así que sin TimeZone.setTimeZone(UTC) acá SimpleDateFormat
// interpretaría esos campos numéricos como si ya fueran hora local, corriendo
// la hora mostrada por el offset del dispositivo.
private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
private val ISO_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

/** GitHub devuelve created_at en ISO-8601 UTC ("2026-11-21T20:15:03Z") —
 *  se reformatea al mismo estilo corto que ya usa GitRepository.log() para
 *  consistencia visual. Si el parseo falla (formato inesperado), se
 *  muestra el string crudo en vez de ocultar el dato. */
private fun formatDate(isoDate: String): String {
    return try {
        DATE_FORMAT.format(ISO_FORMAT.parse(isoDate)!!)
    } catch (e: Exception) {
        isoDate
    }
}
