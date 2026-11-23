package io.github.ghayaslakis.vidgrab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/**
 * One notification per download: progress, speed and ETA with Pause and Stop while it runs, then a result
 * ("Downloaded" - tap to open the file, "Paused" with Resume, "Failed" with Retry). The running ones are
 * grouped under the service's "N downloads running" notification.
 */
object Notifier {
    const val CH_PROGRESS = "dl"
    const val CH_RESULT = "results"
    const val GROUP = "vidgrab.downloads"
    const val ACT_PAUSE = "vidgrab.pause"
    const val ACT_STOP = "vidgrab.stop"
    const val ACT_RESUME = "vidgrab.resume"
    private const val ACCENT = "#3461FF"
    private val lastPost = ConcurrentHashMap<String, Long>()

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Downloads in progress", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_RESULT, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
    }

    // Two ids per download: the running one (in the group) and its result (outside it, so it stays
    // when the group's summary goes away with the service).
    private fun runId(a: MainActivity.Item) = 0x100000 + (a.url.hashCode() and 0x7FFFF) * 2
    private fun resultId(a: MainActivity.Item) = runId(a) + 1

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
        PendingIntent.FLAG_IMMUTABLE)

    private fun action(ctx: Context, a: MainActivity.Item, act: String, label: String, icon: Int): Notification.Action {
        val i = Intent(ctx, NotificationActions::class.java).setAction(act).putExtra("url", a.url)
        val pi = PendingIntent.getBroadcast(ctx, (act + a.url).hashCode(), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(Icon.createWithResource(ctx, icon), label, pi).build()
    }

    private fun base(ctx: Context, channel: String, title: String) = Notification.Builder(ctx, channel)
        .setContentTitle(title)
        .setColor(Color.parseColor(ACCENT))
        .setShowWhen(false)
        .setOnlyAlertOnce(true)
        .setContentIntent(openApp(ctx))

    private fun post(ctx: Context, id: Int, n: Notification) {
        try { ctx.getSystemService(NotificationManager::class.java).notify(id, n) } catch (_: Exception) {}
    }

    /** A running download: at most one update a second (Android drops faster ones anyway). */
    fun progress(ctx: Context, a: MainActivity.Item, text: String) {
        val now = System.currentTimeMillis()
        if (now - (lastPost[a.url] ?: 0L) < 1000) return
        lastPost[a.url] = now
        channels(ctx)
        val n = base(ctx, CH_PROGRESS, a.title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, a.pct.coerceAtLeast(0), a.pct < 0)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setGroup(GROUP)
            .addAction(action(ctx, a, ACT_PAUSE, "Pause", android.R.drawable.ic_media_pause))
            .addAction(action(ctx, a, ACT_STOP, "Stop", android.R.drawable.ic_menu_close_clear_cancel))
            .build()
        post(ctx, runId(a), n)
    }

    /** The download's work ended: replaces its progress with the result. */
    fun finished(ctx: Context, a: MainActivity.Item) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(runId(a))
        lastPost.remove(a.url)
        if (a.cancelled) { nm.cancel(resultId(a)); return }
        if (a.done && a.part.isNotEmpty()) return     // waits to be merged: the merged video gets the notification
        channels(ctx)
        val n = when {
            a.done -> {
                val b = base(ctx, CH_RESULT, "Downloaded")
                    .setContentText(a.title)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setAutoCancel(true)
                a.files.firstOrNull()?.let { f ->
                    val u = Uri.parse(f)
                    val mime = (try { ctx.contentResolver.getType(u) } catch (_: Exception) { null }) ?: "video/*"
                    val view = Intent(Intent.ACTION_VIEW).setDataAndType(u, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    b.setContentIntent(PendingIntent.getActivity(ctx, resultId(a), view,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
                b.build()
            }
            a.paused -> base(ctx, CH_PROGRESS, a.title)
                .setContentText(if (a.pct >= 0) "Paused at ${a.pct}%" else "Paused")
                .setSmallIcon(android.R.drawable.ic_media_pause)
                .addAction(action(ctx, a, ACT_RESUME, "Resume", android.R.drawable.ic_media_play))
                .addAction(action(ctx, a, ACT_STOP, "Stop", android.R.drawable.ic_menu_close_clear_cancel))
                .build()
            else -> base(ctx, CH_RESULT, "Download failed")
                .setContentText(a.title)
                .setStyle(Notification.BigTextStyle().bigText(a.title + "\n" + a.note.substringBefore(". Tap")))
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .addAction(action(ctx, a, ACT_RESUME, "Retry", android.R.drawable.ic_popup_sync))
                .build()
        }
        post(ctx, resultId(a), n)
    }

    /** Removes both of the download's notifications. */
    fun clear(ctx: Context, a: MainActivity.Item) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(runId(a))
        nm.cancel(resultId(a))
        lastPost.remove(a.url)
    }

    /** The service's own notification: the summary of the running downloads. */
    fun summary(ctx: Context, text: String): Notification {
        channels(ctx)
        return base(ctx, CH_PROGRESS, "VidGrab")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .build()
    }
}

/** The Pause / Stop / Resume buttons of the download notifications. */
class NotificationActions : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val act = i.action ?: return
        val url = i.getStringExtra("url") ?: return
        // false: VidGrab was restarted since, so this download is not known any more. Drop the stale
        // notifications; the list (with the partial download) is back the next time the app is opened.
        if (!MainActivity.fromNotification(act, url))
            ctx.getSystemService(NotificationManager::class.java).cancelAll()
    }
}
