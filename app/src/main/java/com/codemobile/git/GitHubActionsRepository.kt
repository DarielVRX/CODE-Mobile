package com.codemobile.git

import org.eclipse.jgit.api.Git
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Wrapper delgado sobre la API REST de GitHub Actions (runs, jobs, logs,
 * artifacts) — mismo criterio que GitRepository con JGit: sin librería
 * HTTP/JSON nueva (el proyecto no trae OkHttp/Retrofit, ver app/build.gradle),
 * solo HttpURLConnection del JDK y org.json (incluido en el SDK de Android).
 *
 * Reusa el mismo Personal Access Token global de GitCredentialsStore: un PAT
 * classic con scope "repo" o uno fine-grained con el permiso de repositorio
 * "Actions" (read) alcanza para todas las funciones de este archivo (todas
 * de solo lectura). Sin token, solo funcionan repos públicos.
 */
object GitHubActionsRepository {

    sealed class ActionsResult<out T> {
        data class Success<out T>(val data: T) : ActionsResult<T>()
        data class Failure(val message: String) : ActionsResult<Nothing>()
    }

    data class WorkflowRun(
        val id: Long,
        val name: String,
        val branch: String,
        /** "queued" | "in_progress" | "completed". */
        val status: String,
        /** Solo tiene sentido cuando status == "completed": "success",
         *  "failure", "cancelled", etc. Null mientras no terminó. */
        val conclusion: String?,
        val event: String,
        val createdAt: String,
        val htmlUrl: String
    )

    data class WorkflowJob(
        val id: Long,
        val name: String,
        val status: String,
        val conclusion: String?
    )

    /** Un paso individual dentro de un job (ver getJobSteps) — a
     *  diferencia de WorkflowJob (la lista resumida de listJobs),
     *  [startedAt]/[completedAt] son justo lo que hace falta para
     *  correlacionar líneas del log de texto plano (ver
     *  ActionsLogViewerController) con el step al que pertenecen: cada
     *  línea del log trae su propio timestamp ISO, y cae dentro del
     *  step cuyo rango [startedAt, completedAt] la contiene. */
    data class WorkflowStep(
        val number: Int,
        val name: String,
        val status: String,
        val conclusion: String?,
        val startedAt: String?,
        val completedAt: String?
    )

    data class Artifact(
        val id: Long,
        val name: String,
        val sizeInBytes: Long,
        val expired: Boolean
    )

    private const val API_BASE = "https://api.github.com"

    /** Busca el remoto "origin" del repo en [repoRoot] y lo descompone en
     *  owner/repo. Soporta tanto HTTPS ("https://github.com/owner/repo.git")
     *  como SSH ("git@github.com:owner/repo.git") — aunque esta app solo usa
     *  HTTPS para clone/push/pull (ver GitRepository), el remoto pudo haber
     *  sido configurado por fuera (otro cliente git, repo importado). Null
     *  si no hay repo, no hay "origin", o la URL no es de github.com. */
    fun resolveOwnerRepo(repoRoot: File): Pair<String, String>? {
        return try {
            Git.open(repoRoot).use { git ->
                val url = git.repository.config.getString("remote", "origin", "url") ?: return null
                parseOwnerRepo(url)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** true si [repoRoot] tiene al menos un archivo de workflow declarado
     *  (.github/workflows, extensión yml o yaml) — chequeo puro de filesystem, sin
     *  red, a diferencia de listRuns (que SÍ pega contra la API y requiere
     *  owner/repo/token resueltos). Se usa para distinguir, cuando listRuns
     *  devuelve una lista vacía, "este repo nunca configuró CI" (sugerir
     *  crear un workflow) de "tiene CI configurado pero nunca corrió nada
     *  todavía" (sin sugerencia, es simplemente el estado inicial normal) —
     *  ver ActionsPanelController.loadRuns. */
    fun hasWorkflowFiles(repoRoot: File): Boolean {
        val dir = File(repoRoot, ".github/workflows")
        if (!dir.isDirectory) return false
        return dir.listFiles { f -> f.isFile && (f.extension == "yml" || f.extension == "yaml") }
            ?.isNotEmpty() ?: false
    }

    private fun parseOwnerRepo(url: String): Pair<String, String>? {
        val cleaned = url.trim().removeSuffix(".git")
        val sshMatch = Regex("""git@github\.com:(.+)/(.+)""").find(cleaned)
        if (sshMatch != null) {
            val (owner, repo) = sshMatch.destructured
            return owner to repo
        }
        val httpsMatch = Regex("""https?://(?:[^@/]+@)?github\.com/(.+)/(.+)""").find(cleaned)
        if (httpsMatch != null) {
            val (owner, repo) = httpsMatch.destructured
            return owner to repo
        }
        return null
    }

    /** [maxCount] nulo = 20 runs (misma idea que GitRepository.log: no
     *  volcar de entrada un historial enorme en un panel de celular). Los
     *  runs en curso (queued/in_progress) vienen incluidos junto a los ya
     *  terminados, en el mismo orden que devuelve GitHub (más reciente
     *  primero) — GitHub no requiere pedirlos aparte. */
    fun listRuns(owner: String, repo: String, token: String?, maxCount: Int? = null): ActionsResult<List<WorkflowRun>> {
        val perPage = (maxCount ?: 20).coerceIn(1, 100)
        return get("$API_BASE/repos/$owner/$repo/actions/runs?per_page=$perPage", token) { body ->
            val runsJson = JSONObject(body).getJSONArray("workflow_runs")
            val runs = mutableListOf<WorkflowRun>()
            for (i in 0 until runsJson.length()) {
                val r = runsJson.getJSONObject(i)
                runs.add(
                    WorkflowRun(
                        id = r.getLong("id"),
                        name = r.optString("name", "(unnamed)"),
                        branch = r.optString("head_branch", "?"),
                        status = r.optString("status", "?"),
                        conclusion = r.optStringOrNull("conclusion"),
                        event = r.optString("event", "?"),
                        createdAt = r.optString("created_at", ""),
                        htmlUrl = r.optString("html_url", "")
                    )
                )
            }
            runs
        }
    }

    fun listJobs(owner: String, repo: String, runId: Long, token: String?): ActionsResult<List<WorkflowJob>> {
        return get("$API_BASE/repos/$owner/$repo/actions/runs/$runId/jobs", token) { body ->
            val jobsJson = JSONObject(body).getJSONArray("jobs")
            val jobs = mutableListOf<WorkflowJob>()
            for (i in 0 until jobsJson.length()) {
                val j = jobsJson.getJSONObject(i)
                jobs.add(
                    WorkflowJob(
                        id = j.getLong("id"),
                        name = j.optString("name", "(unnamed)"),
                        status = j.optString("status", "?"),
                        conclusion = j.optStringOrNull("conclusion")
                    )
                )
            }
            jobs
        }
    }

    /** Job individual con su array de steps (distinto de listJobs, que da
     *  la lista resumida sin steps) — GET .../actions/jobs/{job_id}. Usado
     *  junto con downloadJobLogs para dividir el log de texto plano por
     *  step (ver WorkflowStep y ActionsLogViewerController). */
    fun getJobSteps(owner: String, repo: String, jobId: Long, token: String?): ActionsResult<List<WorkflowStep>> {
        return get("$API_BASE/repos/$owner/$repo/actions/jobs/$jobId", token) { body ->
            val stepsJson = JSONObject(body).optJSONArray("steps") ?: JSONArray()
            val steps = mutableListOf<WorkflowStep>()
            for (i in 0 until stepsJson.length()) {
                val s = stepsJson.getJSONObject(i)
                steps.add(
                    WorkflowStep(
                        number = s.optInt("number", i + 1),
                        name = s.optString("name", "(unnamed step)"),
                        status = s.optString("status", "?"),
                        conclusion = s.optStringOrNull("conclusion"),
                        startedAt = s.optStringOrNull("started_at"),
                        completedAt = s.optStringOrNull("completed_at")
                    )
                )
            }
            steps
        }
    }

    /** El endpoint real de GitHub ("/actions/jobs/{job_id}/logs") responde
     *  con un 302 a una URL firmada de Azure Blob que entrega el log en
     *  texto plano — HttpURLConnection sigue redirects automáticamente para
     *  GET, así que no hace falta resolver el Location a mano. */
    fun downloadJobLogs(owner: String, repo: String, jobId: Long, token: String?): ActionsResult<String> {
        return get("$API_BASE/repos/$owner/$repo/actions/jobs/$jobId/logs", token) { body -> body }
    }

    /** Una línea del log de un job, ya asociada al step al que pertenece
     *  (ver groupLogLinesByStep). [stepNumber] null = no se pudo asociar
     *  a ningún step (línea sin timestamp reconocible, o cae antes del
     *  primer step/después del último — pasa con líneas de setup/teardown
     *  del runner que GitHub agrega fuera de los steps del usuario). */
    data class LogLine(val stepNumber: Int?, val text: String)

    /** Cada línea de log de GitHub Actions arranca con un timestamp ISO de
     *  precisión variable ("2026-11-21T20:15:03.1234567Z algo pasó") — este
     *  regex solo recorta ese prefijo para comparar la MARCA DE TIEMPO
     *  contra el rango [startedAt, completedAt] de cada step; no valida el
     *  formato exacto de fracción de segundo (length variable según el
     *  runner), por eso corta en la primera 'Z' seguida de espacio en vez
     *  de un patrón de dígitos fijo. */
    private val LOG_LINE_TIMESTAMP = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+Z) (.*)$""")

    /** Divide [rawLog] en líneas y asigna cada una al step de [steps] cuyo
     *  rango [startedAt, completedAt] contiene su timestamp — ver LogLine.
     *  Steps sin startedAt/completedAt (no llegaron a correr, ej.
     *  "skipped") quedan sin ninguna línea asociada, lo cual es correcto:
     *  no hay nada que mostrar para un step que no corrió. Si NINGUNA
     *  línea matchea el formato esperado (log vacío, o GitHub cambió el
     *  formato), todas las líneas devuelven stepNumber = null — el caller
     *  decide el fallback (ver ActionsLogViewerController: mostrar el log
     *  completo sin dividir en ese caso, nunca ocultarlo). */
    fun groupLogLinesByStep(rawLog: String, steps: List<WorkflowStep>): List<LogLine> {
        val ranges = steps.mapNotNull { step ->
            val start = step.startedAt?.let { parseIsoInstant(it) } ?: return@mapNotNull null
            val end = step.completedAt?.let { parseIsoInstant(it) } ?: Long.MAX_VALUE
            Triple(step.number, start, end)
        }
        return rawLog.lineSequence().map { line ->
            val match = LOG_LINE_TIMESTAMP.find(line)
            if (match == null) {
                LogLine(null, line)
            } else {
                val (timestampStr, rest) = match.destructured
                val timestamp = parseIsoInstant(timestampStr)
                val stepNumber = if (timestamp == null) {
                    null
                } else {
                    ranges.firstOrNull { (_, start, end) -> timestamp >= start && timestamp <= end }?.first
                }
                LogLine(stepNumber, rest)
            }
        }.toList()
    }

    /** Epoch millis de un timestamp ISO-8601 de GitHub ("...Z" o con
     *  offset explícito, ver la nota de la documentación oficial sobre
     *  started_at/completed_at de steps viniendo con offset en vez de
     *  "Z") — java.time (disponible desde API 26, el minSdk de esta app,
     *  ver app/build.gradle) parsea ambas variantes con Instant.parse/
     *  OffsetDateTime sin necesitar dos SimpleDateFormat distintos. Null
     *  si el string no es parseable, en vez de lanzar. */
    private fun parseIsoInstant(iso: String): Long? {
        return try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (e: Exception) {
            try {
                java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
            } catch (e2: Exception) {
                null
            }
        }
    }

    fun listArtifacts(owner: String, repo: String, runId: Long, token: String?): ActionsResult<List<Artifact>> {
        return get("$API_BASE/repos/$owner/$repo/actions/runs/$runId/artifacts", token) { body ->
            val artifactsJson = JSONObject(body).getJSONArray("artifacts")
            val artifacts = mutableListOf<Artifact>()
            for (i in 0 until artifactsJson.length()) {
                val a = artifactsJson.getJSONObject(i)
                artifacts.add(
                    Artifact(
                        id = a.getLong("id"),
                        name = a.optString("name", "artifact"),
                        sizeInBytes = a.optLong("size_in_bytes", 0L),
                        expired = a.optBoolean("expired", false)
                    )
                )
            }
            artifacts
        }
    }

    /** Descarga el zip de un artifact (endpoint de GitHub con el mismo
     *  patrón de redirect que downloadJobLogs) directo a [destFile] — sin
     *  pasar el contenido binario por String, a diferencia de los otros
     *  endpoints de este archivo, que son JSON/texto. [destFile] ya es la
     *  ruta FINAL elegida por el usuario (ver ActionsPanelController.
     *  downloadArtifact: el picker de carpeta se resuelve antes de llamar
     *  a esta función, no después) — nada de archivo temporal intermedio. */
    fun downloadArtifact(owner: String, repo: String, artifactId: Long, token: String?, destFile: File): ActionsResult<Unit> {
        val urlStr = "$API_BASE/repos/$owner/$repo/actions/artifacts/$artifactId/zip"
        return try {
            val connection = openConnection(urlStr, token)
            val code = connection.responseCode
            if (code !in 200..299) {
                return ActionsResult.Failure(httpErrorMessage(code, connection))
            }
            connection.inputStream.use { input: InputStream ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            ActionsResult.Success(Unit)
        } catch (e: Exception) {
            ActionsResult.Failure(e.message ?: "Couldn't download the artifact")
        }
    }

    /** Núcleo compartido por todos los endpoints de solo lectura que
     *  devuelven JSON/texto (todos salvo downloadArtifact, que es binario y
     *  escribe directo a disco). [parse] recibe el body ya leído como
     *  String y construye el resultado tipado. */
    private fun <T> get(urlStr: String, token: String?, parse: (String) -> T): ActionsResult<T> {
        return try {
            val connection = openConnection(urlStr, token)
            val code = connection.responseCode
            if (code !in 200..299) {
                return ActionsResult.Failure(httpErrorMessage(code, connection))
            }
            val body = connection.inputStream.use { readFully(it) }
            ActionsResult.Success(parse(body))
        } catch (e: Exception) {
            ActionsResult.Failure(e.message ?: "Couldn't complete the request to GitHub")
        }
    }

    private fun openConnection(urlStr: String, token: String?): HttpURLConnection {
        val connection = URL(urlStr).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (!token.isNullOrBlank()) {
            connection.setRequestProperty("Authorization", "Bearer $token")
        }
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        return connection
    }

    /** Traduce los códigos de error más comunes de la API de GitHub a un
     *  mensaje que tiene sentido mostrar tal cual en el panel, sin volcar
     *  el JSON de error crudo (mismo criterio que GitRepository: errores
     *  esperables se informan, no se tratan como bug). */
    private fun httpErrorMessage(code: Int, connection: HttpURLConnection): String {
        val detail = try {
            connection.errorStream?.use { readFully(it) }?.let { body ->
                val json = JSONObject(body)
                if (json.isNull("message")) null else json.optString("message")
            }
        } catch (e: Exception) {
            null
        }
        val base = when (code) {
            401 -> "Invalid or expired GitHub credentials"
            403 -> "No permission (token missing the \"Actions\" permission, or private repository without credentials?)"
            404 -> "Not found (repository with no workflows, or no permission to view it?)"
            else -> "GitHub responded with error $code"
        }
        return if (!detail.isNullOrBlank()) "$base: $detail" else base
    }

    private fun readFully(input: InputStream): String {
        return BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
    }

    /** JSONObject.optString devuelve el string literal "null" (no Kotlin
     *  null) para una clave con valor null explícito en el JSON — pasa
     *  justo con "conclusion" mientras el run sigue en curso. isNull()
     *  filtra ese caso ANTES de llamar a optString(key) (la variante de
     *  un solo argumento, sin fallback null: la sobrecarga de dos
     *  argumentos espera un String de reemplazo no-nulo del lado Java, no
     *  un marcador de "sin valor"). */
    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        return optString(key)
    }
}
