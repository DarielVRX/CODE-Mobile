package com.codemobile.git

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.codemobile.R
import com.codemobile.ui.EditorActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Descarga UN artifact de GitHub Actions en segundo plano con notificación
 * de progreso, para que sobreviva aunque el usuario mande la app a
 * background (a diferencia de GitHubActionsTaskRunner, que corre en una
 * CoroutineScope sin Service y por eso Android puede matarla junto con el
 * proceso si la Activity ya no está en foreground).
 *
 * Deliberadamente separado de GitForegroundService: ese está acoplado a
 * GitRepository.GitResult y a un lock de "un comando git a la vez"
 * (GitTaskBus) que no aplica acá — ya se decidió que las llamadas de
 * Actions no deben competir por ese lock (ver GitHubActionsTaskRunner). A
 * diferencia de ese runner, sí puede haber varias descargas de artifacts
 * corriendo al mismo tiempo (el usuario puede tocar "descargar" en más de
 * un artifact), así que este Service recibe los datos de cada descarga
 * por Intent (no por un "pendingWork" único como GitTaskBus) y cuenta
 * cuántas hay activas para saber cuándo recién puede detenerse.
 */
class ArtifactDownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val activeDownloads = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val owner = intent?.getStringExtra(EXTRA_OWNER)
        val repo = intent?.getStringExtra(EXTRA_REPO)
        val artifactId = intent?.getLongExtra(EXTRA_ARTIFACT_ID, -1L) ?: -1L
        val artifactName = intent?.getStringExtra(EXTRA_ARTIFACT_NAME)
        val token = intent?.getStringExtra(EXTRA_TOKEN)
        val destPath = intent?.getStringExtra(EXTRA_DEST_PATH)

        if (owner == null || repo == null || artifactId < 0 || artifactName == null || destPath == null) {
            // No debería pasar (ArtifactDownloadService.start siempre llena
            // todos los extras), pero si pasa no hay nada que descargar.
            if (activeDownloads.get() == 0) stopSelf()
            return START_NOT_STICKY
        }

        val destFile = File(destPath)
        activeDownloads.incrementAndGet()
        startForeground(progressNotificationIdFor(artifactName), buildProgressNotification(artifactName))

        serviceScope.launch {
            val result = try {
                GitHubActionsRepository.downloadArtifact(owner, repo, artifactId, token, destFile)
            } catch (e: Exception) {
                GitHubActionsRepository.ActionsResult.Failure(e.message ?: "Unexpected error")
            }
            when (result) {
                is GitHubActionsRepository.ActionsResult.Success -> {
                    ArtifactDownloadNotifier.notifySuccess(applicationContext, artifactName, destFile)
                }
                is GitHubActionsRepository.ActionsResult.Failure -> {
                    destFile.delete()
                    ArtifactDownloadNotifier.notifyFailure(applicationContext, artifactName, result.message)
                }
            }
            // stopSelf/stopForeground solo cuando ya no queda ninguna
            // descarga activa — con varias en simultáneo, la primera que
            // termina no debe tirar abajo el Service de las demás.
            if (activeDownloads.decrementAndGet() <= 0) {
                // STOP_FOREGROUND_DETACH (no _REMOVE): _REMOVE borra la
                // notificación asociada al foreground service al pararlo —
                // como esa notificación es la MISMA (mismo ID) que
                // notifySuccess/notifyFailure acaban de publicar arriba,
                // _REMOVE la hacía desaparecer de la bandeja casi al
                // instante (bug: con la app en foreground alcanzaba a
                // percibirse el heads-up antes de que se borrara; en
                // background, nunca llegaba a verse). DETACH en cambio
                // solo desvincula la notificación del Service para que siga
                // viva en la bandeja normal, descartable por el usuario.
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }

        // START_NOT_STICKY: igual que GitForegroundService — si el sistema
        // mata el proceso de todos modos, mejor que el usuario reintente la
        // descarga a mano que reiniciar el Service sin los datos del
        // artifact original.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun buildProgressNotification(artifactName: String): Notification {
        ArtifactDownloadNotifier.ensureChannel(this)
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, EditorActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, ArtifactDownloadNotifier.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle("Downloading \"$artifactName\"...")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    /** Mismo ID que ArtifactDownloadNotifier.notificationIdFor: así la
     *  notificación de progreso queda reemplazada por la de resultado
     *  final (éxito/fallo) en vez de acumularse como una segunda entrada
     *  en la barra. */
    private fun progressNotificationIdFor(artifactName: String): Int =
        ArtifactDownloadNotifier.notificationIdFor(artifactName)

    companion object {
        private const val EXTRA_OWNER = "owner"
        private const val EXTRA_REPO = "repo"
        private const val EXTRA_ARTIFACT_ID = "artifact_id"
        private const val EXTRA_ARTIFACT_NAME = "artifact_name"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_DEST_PATH = "dest_path"

        /** Arranca la descarga de [artifactId] en segundo plano. [token]
         *  puede ser null (repos públicos) — igual que
         *  GitHubActionsRepository.downloadArtifact ya acepta. [destFile]
         *  ya debe ser la ruta FINAL resuelta (sobrescritura o renombre
         *  decidido antes de llamar, ver ActionsPanelController). */
        fun start(
            context: Context,
            owner: String,
            repo: String,
            artifactId: Long,
            artifactName: String,
            token: String?,
            destFile: File
        ) {
            val intent = Intent(context, ArtifactDownloadService::class.java).apply {
                putExtra(EXTRA_OWNER, owner)
                putExtra(EXTRA_REPO, repo)
                putExtra(EXTRA_ARTIFACT_ID, artifactId)
                putExtra(EXTRA_ARTIFACT_NAME, artifactName)
                putExtra(EXTRA_TOKEN, token)
                putExtra(EXTRA_DEST_PATH, destFile.absolutePath)
            }
            context.startForegroundService(intent)
        }
    }
}
