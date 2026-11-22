package com.codemobile.git

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.codemobile.R
import com.codemobile.ui.EditorActivity

/**
 * Notificación de sistema al terminar de descargar un artifact de GitHub
 * Actions (ver ActionsPanelController) — deliberadamente separada del
 * canal de GitForegroundService (ver su CHANNEL_ID/IMPORTANCE_LOW): esa
 * notificación es un indicador persistente de "hay una operación git en
 * curso" mientras dura, pensada para no interrumpir (IMPORTANCE_LOW, sin
 * sonido). Esta es lo opuesto: un aviso puntual de que algo ya TERMINÓ,
 * que el usuario pidió ver en la barra de notificaciones del sistema como
 * prioritario (IMPORTANCE_HIGH) — no queda "ongoing" ni bloquea nada, el
 * usuario puede descartarla con un swipe normal.
 *
 * Sin Service propio: a diferencia de GitForegroundService (necesario ahí
 * porque Android puede matar un proceso en background sin una
 * notificación foreground activa durante una operación larga), acá la
 * descarga ya corrió en GitHubActionsTaskRunner — esta función solo
 * dispara el aviso final, una llamada puntual de NotificationManager, sin
 * nada que mantener vivo.
 */
object ArtifactDownloadNotifier {

    // internal (no private): ArtifactDownloadService arma su notificación
    // de progreso ongoing en el mismo canal y con el mismo ID que la de
    // resultado final, para que una reemplace a la otra en vez de
    // acumularse como dos entradas separadas en la barra.
    internal const val CHANNEL_ID = "artifact_downloads"
    private const val NOTIFICATION_ID_BASE = 2000

    /** [artifactName] se usa también para derivar un ID de notificación
     *  estable por nombre (hashCode) — así dos descargas de artifacts
     *  distintos no se pisan la notificación entre sí, pero reintentar la
     *  misma descarga actualiza la misma notificación en vez de acumular
     *  duplicados. */
    internal fun notificationIdFor(artifactName: String): Int =
        NOTIFICATION_ID_BASE + (artifactName.hashCode() and 0xFFFF)

    fun notifySuccess(context: Context, artifactName: String, savedFile: java.io.File) {
        ensureChannel(context)
        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, EditorActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle("Download complete")
            .setContentText("\"$artifactName\" guardado en ${savedFile.parentFile?.name ?: savedFile.parent}")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notify(context, artifactName, notification)
    }

    fun notifyFailure(context: Context, artifactName: String, message: String) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle("Couldn't download \"$artifactName\"")
            .setContentText(message)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notify(context, artifactName, notification)
    }

    private fun notify(context: Context, artifactName: String, notification: android.app.Notification) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationIdFor(artifactName), notification)
    }

    internal fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // IMPORTANCE_HIGH (a diferencia de IMPORTANCE_LOW en
        // GitForegroundService): el usuario pidió que esto aparezca como
        // notificación prioritaria, con sonido/heads-up según la
        // configuración del sistema — a propósito distinto del canal de
        // operaciones git en curso, que es deliberadamente silencioso.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Artifact downloads",
            NotificationManager.IMPORTANCE_HIGH
        )
        manager.createNotificationChannel(channel)
    }
}
