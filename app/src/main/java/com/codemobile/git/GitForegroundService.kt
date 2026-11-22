package com.codemobile.git

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.codemobile.R
import com.codemobile.ui.EditorActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Corre UNA operación git larga (clone/push/pull/etc) en segundo plano con
 * una notificación fija (no descartable, ver NotificationCompat.Builder más
 * abajo) para que Android no mate el proceso mientras dura, y para que el
 * usuario no cierre la app sin darse cuenta de que hay algo en curso.
 *
 * Diseño deliberadamente simple: no recibe el comando por Intent (evitar
 * serializar ParsedCommand) — en vez de eso, EditorActivity deja el trabajo
 * a ejecutar en [GitTaskBus.pendingWork] antes de arrancar el Service, y
 * este lo consume una sola vez en onStartCommand. Un solo comando git a la
 * vez: EditorActivity ya bloquea la consola mientras GitTaskBus.isRunning
 * es true (ver EditorActivity.submitGitCommand), así que no hay necesidad
 * de encolar varios.
 */
class GitForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val work = GitTaskBus.pendingWork
        val label = GitTaskBus.pendingLabel ?: "git"
        if (work == null) {
            // No debería pasar (EditorActivity siempre deja el trabajo
            // antes de llamar a startForegroundService), pero si pasa no
            // hay nada que hacer: no dejar el Service ni la notificación
            // colgados sin motivo.
            stopSelf()
            return START_NOT_STICKY
        }
        GitTaskBus.pendingWork = null
        GitTaskBus.pendingLabel = null

        startForeground(NOTIFICATION_ID, buildNotification(label))

        serviceScope.launch {
            val result = try {
                work()
            } catch (e: Exception) {
                GitRepository.GitResult.Failure(e.message ?: "Unexpected error")
            }
            GitTaskBus.isRunning = false
            GitTaskBus.onResult?.invoke(result)
            stopSelf()
        }

        // START_NOT_STICKY: si el sistema mata el proceso de todos modos en
        // condiciones extremas de memoria, no tiene sentido reiniciar el
        // Service sin el comando original — mejor que el usuario reintente
        // a mano y vea el error en consola, que repetir un push a medias.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun buildNotification(label: String): Notification {
        ensureChannel()
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, EditorActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_git)
            .setContentTitle("CodeMobile — git operation in progress")
            .setContentText(label)
            .setContentIntent(openAppIntent)
            // ongoing + no swipe-to-dismiss: justo lo que se pidió, para que
            // el usuario no la cierre sin darse cuenta de que hay una
            // operación corriendo (push/clone grande puede tardar).
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Git operations",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "git_operations"
        private const val NOTIFICATION_ID = 1001

        /** Arranca el Service para correr [work] en segundo plano, mostrando
         *  [label] en la notificación (ej. "git push"). [work] corre en
         *  Dispatchers.IO; su resultado llega a [GitTaskBus.onResult] en el
         *  hilo del Service, así que quien escuche debe volver al hilo
         *  principal antes de tocar vistas.
         *
         *  isRunning se marca true ACÁ (en el hilo que llama a start, normalmente
         *  el principal) y no dentro de onStartCommand: startForegroundService
         *  entrega el Intent de forma asíncrona, así que si se marcara ahí
         *  habría una ventana breve donde isRunning sigue en false aunque el
         *  usuario ya disparó el comando — suficiente para que un segundo
         *  comando git se cuele antes de que el bloqueo de la consola surta
         *  efecto. */
        fun start(context: Context, label: String, work: suspend () -> GitRepository.GitResult) {
            GitTaskBus.pendingWork = work
            GitTaskBus.pendingLabel = label
            GitTaskBus.isRunning = true
            val intent = Intent(context, GitForegroundService::class.java)
            context.startForegroundService(intent)
        }
    }
}

/** Puente mínimo entre GitForegroundService y EditorActivity: no hace falta
 *  bind/Messenger para un solo valor de ida y uno de vuelta. isRunning es lo
 *  que EditorActivity usa para bloquear la consola (ver tarea "unificar
 *  consola" — mientras haya una tarea activa no se acepta otro comando). */
object GitTaskBus {
    @Volatile var isRunning: Boolean = false
        internal set

    internal var pendingWork: (suspend () -> GitRepository.GitResult)? = null
    internal var pendingLabel: String? = null

    /** Llamado desde el hilo del Service al terminar; EditorActivity debe
     *  hacer runOnUiThread/post antes de tocar vistas desde este callback. */
    var onResult: ((GitRepository.GitResult) -> Unit)? = null
}
