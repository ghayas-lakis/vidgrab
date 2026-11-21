package com.example.vidgrab

import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.webkit.MimeTypeMap
import android.widget.*
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

// ---------- design system ----------
/**
 * Color tokens for one theme. `accent` is for text, icons and progress; `fill` is the background of
 * filled buttons (it keeps white text readable in both themes). ok / warn / danger are the status tones.
 */
private class Palette(
    val dark: Boolean,
    val bg: Int, val surface: Int, val surface2: Int, val outline: Int, val outlineStrong: Int,
    val text: Int, val muted: Int, val subtle: Int,
    val accent: Int, val fill: Int, val accentSoft: Int, val onAccentSoft: Int,
    val ok: Int, val warn: Int, val danger: Int, val dangerSoft: Int,
    val ripple: Int, val disabledBg: Int, val disabledFg: Int,
)

private fun hex(h: String) = Color.parseColor(h)

private val LIGHT = Palette(
    dark = false,
    bg = hex("#F4F6FA"), surface = hex("#FFFFFF"), surface2 = hex("#EEF1F7"), outline = hex("#E3E7EF"),
    outlineStrong = hex("#C3CAD7"),
    text = hex("#0E1726"), muted = hex("#5B6679"), subtle = hex("#8C96A8"),
    accent = hex("#3461FF"), fill = hex("#3461FF"), accentSoft = hex("#E8EEFF"), onAccentSoft = hex("#2347D1"),
    ok = hex("#0F7F3F"), warn = hex("#B45309"), danger = hex("#D92D20"), dangerSoft = hex("#FDECEA"),
    ripple = Color.argb(31, 14, 23, 38), disabledBg = hex("#E9ECF2"), disabledFg = hex("#A3ABB9"),
)

private val DARK = Palette(
    dark = true,
    bg = hex("#0B0E14"), surface = hex("#151A23"), surface2 = hex("#1D2330"), outline = hex("#252C3A"),
    outlineStrong = hex("#3A4354"),
    text = hex("#E8ECF3"), muted = hex("#9AA4B5"), subtle = hex("#6B7588"),
    accent = hex("#7B98FF"), fill = hex("#3D63F5"), accentSoft = hex("#1E2747"), onAccentSoft = hex("#B3C3FF"),
    ok = hex("#3DD68C"), warn = hex("#F5B544"), danger = hex("#FF6B6B"), dangerSoft = hex("#3A1D22"),
    ripple = Color.argb(41, 255, 255, 255), disabledBg = hex("#232A36"), disabledFg = hex("#5E687A"),
)

// spacing scale (dp)
private const val S1 = 4
private const val S2 = 8
private const val S3 = 12
private const val S4 = 16
private const val S5 = 20
// corner radii (dp)
private const val R_SM = 10
private const val R_MD = 14
private const val R_LG = 20
private const val R_PILL = 100
// type scale (sp)
private const val T_DISPLAY = 21f
private const val T_TITLE = 16f
private const val T_BODY = 14f
private const val T_LABEL = 13f
private const val T_CAPTION = 12f
private const val T_OVERLINE = 11f

private val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
private val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

// button kinds
private const val FILLED = 0
private const val TONAL = 1
private const val DANGER = 2
private const val GHOST = 3

// status tones (stored per item, so a theme change can re-color them)
private const val K_MUTED = 0
private const val K_ACTIVE = 1
private const val K_WARN = 2
private const val K_ERROR = 3
private const val K_OK = 4

// Marks our own progress lines (see --progress-template in buildRequest).
private const val PROG = "VGP|"
// Start of optimize()'s answer for audio and other non-video files (expected, not an error).
private const val NOT_VIDEO = "Not a video file"

// ---------- icons ----------
private const val G_PLAY = 1
private const val G_PAUSE = 2
private const val G_STOP = 3
private const val G_SHARE = 4
private const val G_OPEN = 5
private const val G_PASTE = 6
private const val G_CLOSE = 7
private const val G_FOLDER = 8
private const val G_WIFI = 9
private const val G_SPARK = 10
private const val G_DOWNLOAD = 11
private const val G_MORE = 12
private const val G_TRASH = 13
private const val G_CHECK = 14
private const val G_DONE_ALL = 15
private const val G_REFRESH = 16
private const val G_PHONE = 17
private const val G_ALERT = 18
private const val G_INFO = 19
private const val G_CHEVRON_DOWN = 20
private const val G_CHEVRON_RIGHT = 21
private const val G_CLEAR_ALL = 22
private const val G_VIDEO = 23

/**
 * Simple line icons drawn on a 24x24 grid, scaled to the bounds. They need no resources, stay sharp
 * at any size and follow tint lists, so buttons grey them out when disabled.
 */
private class Glyph(private val kind: Int, private val base: Int, private val sz: Int = -1,
                    private val weight: Float = 2f) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val r = RectF()
    private var tint: ColorStateList? = null
    private var alphaV = 255

    override fun getIntrinsicWidth() = sz
    override fun getIntrinsicHeight() = sz

    override fun draw(cv: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        if (s <= 0f) return
        val col = tint?.getColorForState(state, base) ?: base
        p.color = col
        p.alpha = Color.alpha(col) * alphaV / 255
        p.strokeWidth = weight
        p.style = Paint.Style.STROKE
        path.reset()
        cv.save()
        cv.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f)
        cv.scale(s, s)
        when (kind) {
            G_PLAY -> {
                p.style = Paint.Style.FILL_AND_STROKE
                path.moveTo(8f, 5.5f); path.lineTo(18.5f, 12f); path.lineTo(8f, 18.5f); path.close()
                cv.drawPath(path, p)
            }
            G_PAUSE -> {
                p.style = Paint.Style.FILL
                r.set(6.5f, 5f, 10.2f, 19f); cv.drawRoundRect(r, 1.4f, 1.4f, p)
                r.set(13.8f, 5f, 17.5f, 19f); cv.drawRoundRect(r, 1.4f, 1.4f, p)
            }
            G_STOP -> {
                p.style = Paint.Style.FILL
                r.set(6.5f, 6.5f, 17.5f, 17.5f); cv.drawRoundRect(r, 2.2f, 2.2f, p)
            }
            G_SHARE -> {
                cv.drawLine(8.6f, 10.8f, 15.4f, 6.7f, p)
                cv.drawLine(8.6f, 13.2f, 15.4f, 17.3f, p)
                p.style = Paint.Style.FILL
                cv.drawCircle(17.5f, 5.5f, 2.7f, p)
                cv.drawCircle(6.5f, 12f, 2.7f, p)
                cv.drawCircle(17.5f, 18.5f, 2.7f, p)
            }
            G_OPEN -> {
                path.moveTo(11f, 5f); path.lineTo(7f, 5f); path.quadTo(5f, 5f, 5f, 7f)
                path.lineTo(5f, 17f); path.quadTo(5f, 19f, 7f, 19f); path.lineTo(17f, 19f)
                path.quadTo(19f, 19f, 19f, 17f); path.lineTo(19f, 13f)
                path.moveTo(14f, 5f); path.lineTo(19f, 5f); path.lineTo(19f, 10f)
                path.moveTo(19f, 5f); path.lineTo(11.5f, 12.5f)
                cv.drawPath(path, p)
            }
            G_PASTE -> {
                r.set(5.5f, 5f, 18.5f, 21f); cv.drawRoundRect(r, 2.5f, 2.5f, p)
                r.set(9f, 3f, 15f, 7f); cv.drawRoundRect(r, 1.5f, 1.5f, p)
                cv.drawLine(9f, 12f, 15f, 12f, p)
                cv.drawLine(9f, 16f, 13f, 16f, p)
            }
            G_CLOSE -> {
                cv.drawLine(7f, 7f, 17f, 17f, p)
                cv.drawLine(17f, 7f, 7f, 17f, p)
            }
            G_FOLDER -> {
                path.moveTo(3.5f, 7f); path.lineTo(3.5f, 17.5f); path.quadTo(3.5f, 19f, 5f, 19f)
                path.lineTo(19f, 19f); path.quadTo(20.5f, 19f, 20.5f, 17.5f); path.lineTo(20.5f, 9.5f)
                path.quadTo(20.5f, 8f, 19f, 8f); path.lineTo(12f, 8f); path.lineTo(10f, 5.5f)
                path.lineTo(5f, 5.5f); path.quadTo(3.5f, 5.5f, 3.5f, 7f); path.close()
                cv.drawPath(path, p)
            }
            G_WIFI -> {
                r.set(12f - 13f, 19f - 13f, 12f + 13f, 19f + 13f); cv.drawArc(r, 225f, 90f, false, p)
                r.set(12f - 8.6f, 19f - 8.6f, 12f + 8.6f, 19f + 8.6f); cv.drawArc(r, 225f, 90f, false, p)
                r.set(12f - 4.3f, 19f - 4.3f, 12f + 4.3f, 19f + 4.3f); cv.drawArc(r, 225f, 90f, false, p)
                p.style = Paint.Style.FILL
                cv.drawCircle(12f, 19f, 1.5f, p)
            }
            G_SPARK -> {
                p.style = Paint.Style.FILL
                path.moveTo(12f, 2.5f); path.quadTo(12.9f, 11.1f, 21.5f, 12f)
                path.quadTo(12.9f, 12.9f, 12f, 21.5f); path.quadTo(11.1f, 12.9f, 2.5f, 12f)
                path.quadTo(11.1f, 11.1f, 12f, 2.5f); path.close()
                cv.drawPath(path, p)
            }
            G_DOWNLOAD -> {
                cv.drawLine(12f, 4f, 12f, 15f, p)
                path.moveTo(7f, 10.5f); path.lineTo(12f, 15.5f); path.lineTo(17f, 10.5f)
                cv.drawPath(path, p)
                cv.drawLine(5f, 19.5f, 19f, 19.5f, p)
            }
            G_MORE -> {
                p.style = Paint.Style.FILL
                cv.drawCircle(5.5f, 12f, 1.9f, p)
                cv.drawCircle(12f, 12f, 1.9f, p)
                cv.drawCircle(18.5f, 12f, 1.9f, p)
            }
            G_TRASH -> {
                cv.drawLine(4.5f, 6.5f, 19.5f, 6.5f, p)
                path.moveTo(9.5f, 6.5f); path.lineTo(9.5f, 4.5f); path.lineTo(14.5f, 4.5f); path.lineTo(14.5f, 6.5f)
                path.moveTo(6.5f, 6.5f); path.lineTo(7.5f, 19f); path.quadTo(7.6f, 20f, 8.6f, 20f)
                path.lineTo(15.4f, 20f); path.quadTo(16.4f, 20f, 16.5f, 19f); path.lineTo(17.5f, 6.5f)
                cv.drawPath(path, p)
                cv.drawLine(10.2f, 10.5f, 10.2f, 16f, p)
                cv.drawLine(13.8f, 10.5f, 13.8f, 16f, p)
            }
            G_CHECK -> {
                path.moveTo(5f, 12.5f); path.lineTo(10f, 17.5f); path.lineTo(19f, 7f)
                cv.drawPath(path, p)
            }
            G_DONE_ALL -> {
                path.moveTo(2.5f, 12.5f); path.lineTo(7f, 17f); path.lineTo(15f, 8f)
                path.moveTo(11.5f, 16f); path.lineTo(12.5f, 17f); path.lineTo(21.5f, 7f)
                cv.drawPath(path, p)
            }
            G_REFRESH -> {
                r.set(5f, 5f, 19f, 19f); cv.drawArc(r, 40f, 280f, false, p)
                path.moveTo(18.5f, 3.5f); path.lineTo(18.5f, 8f); path.lineTo(14f, 8f)
                cv.drawPath(path, p)
            }
            G_PHONE -> {
                r.set(7f, 2.5f, 17f, 21.5f); cv.drawRoundRect(r, 2.5f, 2.5f, p)
                cv.drawLine(10.5f, 18.5f, 13.5f, 18.5f, p)
            }
            G_ALERT -> {
                cv.drawCircle(12f, 12f, 9f, p)
                cv.drawLine(12f, 7.5f, 12f, 12.8f, p)
                p.style = Paint.Style.FILL
                cv.drawCircle(12f, 16.3f, 1.3f, p)
            }
            G_INFO -> {
                cv.drawCircle(12f, 12f, 9f, p)
                cv.drawLine(12f, 11f, 12f, 16.5f, p)
                p.style = Paint.Style.FILL
                cv.drawCircle(12f, 7.7f, 1.3f, p)
            }
            G_CHEVRON_DOWN -> {
                path.moveTo(7f, 10f); path.lineTo(12f, 15f); path.lineTo(17f, 10f)
                cv.drawPath(path, p)
            }
            G_CHEVRON_RIGHT -> {
                path.moveTo(10f, 7f); path.lineTo(15f, 12f); path.lineTo(10f, 17f)
                cv.drawPath(path, p)
            }
            G_CLEAR_ALL -> {
                cv.drawLine(4f, 7f, 20f, 7f, p)
                cv.drawLine(4f, 12f, 12f, 12f, p)
                cv.drawLine(4f, 17f, 9.5f, 17f, p)
                cv.drawLine(14.5f, 13.5f, 20f, 19f, p)
                cv.drawLine(20f, 13.5f, 14.5f, 19f, p)
            }
            G_VIDEO -> {
                r.set(3f, 5.5f, 21f, 18.5f); cv.drawRoundRect(r, 3f, 3f, p)
                p.style = Paint.Style.FILL_AND_STROKE
                p.strokeWidth = weight * 0.6f
                path.moveTo(10f, 9f); path.lineTo(15.2f, 12f); path.lineTo(10f, 15f); path.close()
                cv.drawPath(path, p)
            }
        }
        cv.restore()
    }

    override fun setAlpha(a: Int) { alphaV = a; invalidateSelf() }
    override fun setColorFilter(f: ColorFilter?) { p.colorFilter = f; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun setTintList(t: ColorStateList?) { tint = t; invalidateSelf() }
    override fun isStateful() = tint?.isStateful == true
    override fun onStateChange(s: IntArray): Boolean {
        val stateful = tint?.isStateful == true
        if (stateful) invalidateSelf()
        return stateful
    }
}

/**
 * A progress bar that glides to each new value instead of jumping. Going backwards (stop / restart)
 * is instant, and so is any change while the bar is not on screen.
 */
private class SmoothBar(ctx: Context) : ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal) {
    private var anim: ValueAnimator? = null

    private fun raw(v: Int) = super.setProgress(v)

    override fun setProgress(v: Int) {
        anim?.cancel()
        anim = null
        val from = progress
        if (v <= from || !isAttachedToWindow || !isShown) { raw(v); return }
        val va = ValueAnimator.ofInt(from, v)
        va.duration = 450
        va.interpolator = DecelerateInterpolator()
        va.addUpdateListener { raw(it.animatedValue as Int) }
        anim = va
        va.start()
    }
}

class MainActivity : Activity() {
    // choices: height in pixels, 0 = best available, -1 = audio only (MP3)
    // thumb: thumbnail URL; meta: "Site · 12:34 · Uploader"
    class Item(val url: String, val title: String, val labels: List<String>, val choices: List<Int>,
               val thumb: String = "", val meta: String = "") {
        lateinit var check: CheckBox
        lateinit var thumbView: ImageView
        lateinit var spinner: Spinner
        lateinit var bar: ProgressBar
        lateinit var state: TextView
        lateinit var box: LinearLayout
        lateinit var toggle: Button
        lateinit var stopBtn: ImageButton
        lateinit var doneBadge: View
        var tone = 0                      // status tone (K_*), so a theme change can re-color the line
        @Volatile var busy = false
        @Volatile var cancelled = false
        @Volatile var paused = false
        @Volatile var pid = ""
        @Volatile var proc = false        // a yt-dlp process is running for this item
        @Volatile var post = false        // merging / converting: needs no network, never interrupted
        @Volatile var netKill = false     // stopped on purpose because the network changed: always resumed
        @Volatile var afterNet = false    // resumed after an interruption: its speed is being watched
        @Volatile var runDir: File? = null
        @Volatile var pct = -1            // last known progress, kept across interruptions
        @Volatile var runOnWifi = false
        @Volatile var runStart = 0L
        @Volatile var runBytes0 = -1.0
        @Volatile var lastBytes = -1.0
        @Volatile var lastUi = 0L
        @Volatile var peak = 0.0          // best speed seen before an interruption (bytes/s)
        @Volatile var peakOnWifi = false
        @Volatile var slowRestarts = 0
        @Volatile var err = ""            // full text of the last failure, shown when the status is tapped
        var done = false
        val files = mutableListOf<String>()
    }

    private lateinit var input: EditText
    private lateinit var clearBtn: Button
    private lateinit var folderLabel: TextView
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var engineChip: TextView
    private lateinit var dlBtn: Button
    private lateinit var selectAll: CheckBox
    private lateinit var countBadge: TextView
    private lateinit var empty: View
    private lateinit var topBar: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var prefs: SharedPreferences
    @Volatile private var updating = false
    @Volatile private var engineFailed = false                    // the engine could not start
    @Volatile private var checkErr = ""                           // details of the last failed link check
    private var syncing = false                                   // updating "Select all" from code
    private val imgPool = Executors.newFixedThreadPool(2)         // thumbnails
    private val analyzer = Executors.newSingleThreadExecutor()
    private val downloader = Executors.newFixedThreadPool(2)
    private val items = CopyOnWriteArrayList<Item>()
    private var dp = 1f
    private var svcOn = false
    @Volatile private var ready = false
    private val handler = Handler(Looper.getMainLooper())
    private val checkRun = Runnable { autoCheck() }
    private val checking = ConcurrentHashMap.newKeySet<String>()   // links being checked right now
    private var prevLinks = setOf<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()     // links that cannot be downloaded (not retried)

    companion object {
        // Downloads running in this process; survives the activity being recreated.
        private val running = ConcurrentHashMap.newKeySet<String>()
    }

    @Volatile private var curNet: Network? = null

    // When the connection drops or changes, stop running downloads at once so they never keep using a
    // dead connection; the work loop resumes them from the partial file on a usable network.
    private val netCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val prev = curNet
            curNet = network
            if (prev != null && prev != network) restartRunning()   // e.g. mobile data -> Wi-Fi
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (wifiOnly() && !onWifi(getSystemService(ConnectivityManager::class.java), caps)) restartRunning()
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) runOnUiThread { autoCheck() }
        }

        override fun onLost(network: Network) {
            if (network == curNet) curNet = null
            restartRunning()
        }
    }

    private fun kill(a: Item) {
        try { YoutubeDL.getInstance().destroyProcessById(a.pid) } catch (_: Exception) {}
    }

    /** Stops every download that is transferring data; each one resumes by itself (from its partial file). */
    private fun restartRunning() {
        for (a in items) if (a.proc && !a.post) {
            a.netKill = true
            a.afterNet = true
            a.slowRestarts = 0
            kill(a)
        }
    }

    // ---------- design helpers ----------
    @Volatile private var pal = LIGHT
    // Status tones; the download code passes these to setState() / ui().
    private val C_MUTED: Int get() = pal.muted
    private val C_PRIMARY: Int get() = pal.accent
    private val C_OK: Int get() = pal.ok
    private val C_WARN: Int get() = pal.warn
    private val C_DANGER: Int get() = pal.danger
    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    private fun px(v: Int) = (v * dp).toInt()
    private fun pxf(v: Float) = v * dp

    private fun isNight(c: Configuration = resources.configuration) =
        (c.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun themeRes() = if (pal.dark) android.R.style.Theme_DeviceDefault_NoActionBar
        else android.R.style.Theme_DeviceDefault_Light_NoActionBar

    private fun dialogTheme() = if (pal.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    /** The color with its alpha multiplied by f. */
    private fun fade(c: Int, f: Float) =
        Color.argb((Color.alpha(c) * f).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    private fun rounded(color: Int, r: Int, stroke: Int = 0, strokeDp: Float = 1f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = px(r).toFloat()
        if (stroke != 0) setStroke(maxOf(1, pxf(strokeDp).toInt()), stroke)
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun dot(color: Int, size: Int): Drawable = oval(color).apply { setBounds(0, 0, px(size), px(size)) }

    /** Press feedback. Without a mask the ripple follows the content's (rounded) shape. */
    private fun ripple(content: Drawable?, mask: Drawable? = null, onFill: Boolean = false) =
        RippleDrawable(ColorStateList.valueOf(if (onFill) Color.argb(56, 255, 255, 255) else pal.ripple), content, mask)

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private fun add(parent: LinearLayout, v: View, top: Int = 0, bottom: Int = 0) = parent.addView(v,
        lp(MATCH, WRAP).apply { setMargins(0, px(top), 0, px(bottom)) })

    private fun hrow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun label(t: String, size: Float, color: Int, face: Typeface = Typeface.DEFAULT) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        typeface = face
    }

    /** Small uppercase heading above a block. */
    private fun overline(t: String) = label(t.uppercase(), T_OVERLINE, pal.subtle, MEDIUM).apply {
        letterSpacing = 0.12f
        setPadding(px(S1), px(S5 + S1), px(S1), px(S2))
    }

    /** A rounded square (or circle) holding an icon. */
    private fun tile(icon: Int, fg: Int, bgc: Int, size: Int = 36, r: Int = R_SM) = ImageView(this).apply {
        background = rounded(bgc, r)
        scaleType = ImageView.ScaleType.CENTER
        setImageDrawable(Glyph(icon, fg, px(size / 2 + 2)))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(pal.surface, R_LG, pal.outline)
        elevation = if (pal.dark) 0f else pxf(1.5f)
        setPadding(px(S4), px(S4), px(S4), px(S4))
    }

    private fun kindColors(kind: Int): Pair<Int, Int> = when (kind) {
        FILLED -> pal.fill to Color.WHITE
        DANGER -> pal.dangerSoft to pal.danger
        GHOST -> Color.TRANSPARENT to pal.muted
        else -> pal.accentSoft to pal.onAccentSoft
    }

    private fun fgList(fg: Int) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(pal.disabledFg, fg))

    /** Colors a pill button: FILLED (accent), TONAL (soft accent), DANGER (soft red) or GHOST (text only). */
    private fun style(b: TextView, kind: Int) {
        val (fill, fg) = kindColors(kind)
        val content = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled),
                rounded(if (kind == GHOST) Color.TRANSPARENT else pal.disabledBg, R_PILL))
            addState(intArrayOf(), rounded(fill, R_PILL))
        }
        b.background = if (kind == GHOST) ripple(content, rounded(Color.WHITE, R_PILL))
            else ripple(content, null, kind == FILLED)
        val l = fgList(fg)
        b.setTextColor(l)
        b.compoundDrawableTintList = l
    }

    /** A leading icon on a wrap-content button (0 = none). */
    private fun setIcon(b: TextView, icon: Int, size: Int = 18) {
        if (icon == 0) { b.setCompoundDrawablesRelative(null, null, null, null); return }
        val d = Glyph(icon, b.currentTextColor)
        d.setBounds(0, 0, px(size), px(size))
        b.setCompoundDrawablesRelative(d, null, null, null)
        b.compoundDrawablePadding = px(6)
    }

    /** Button text with an icon that stays next to the text, even in a wide button. */
    private fun iconText(icon: Int, text: String, color: Int): CharSequence {
        val d = Glyph(icon, color)
        d.setBounds(0, 0, px(20), px(20))
        val s = SpannableString("   $text")
        s.setSpan(ImageSpan(d, DynamicDrawableSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    private fun btn(text: String, kind: Int = TONAL, icon: Int = 0, h: Int = 44, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = T_BODY
        b.typeface = MEDIUM
        b.stateListAnimator = null
        b.minHeight = px(h)
        b.minimumHeight = px(h)
        b.minWidth = 0
        b.minimumWidth = 0
        b.gravity = Gravity.CENTER
        b.setPadding(px(if (icon != 0) 14 else 18), 0, px(18), 0)
        setIcon(b, icon)
        style(b, kind)
        b.setOnClickListener { onClick() }
        return b
    }

    /** A round icon-only button; long-press shows its name. */
    private fun styleRound(b: ImageButton, icon: Int, name: String, kind: Int) {
        val (fill, fg) = kindColors(kind)
        b.setImageDrawable(Glyph(icon, fg, px(18)))
        b.imageTintList = fgList(fg)
        b.background = ripple(StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), oval(pal.disabledBg))
            addState(intArrayOf(), oval(fill))
        }, null, kind == FILLED)
        b.contentDescription = name
        b.tooltipText = name
    }

    /** Round tick box. `overlay` is the variant that sits on a thumbnail (white ring, dark fill). */
    private fun tickBox(overlay: Boolean): Drawable {
        val size = px(22)
        val onBg = oval(pal.fill).apply {
            setSize(size, size)
            if (overlay) setStroke(px(2), Color.WHITE)
        }
        val on = LayerDrawable(arrayOf<Drawable>(onBg, Glyph(G_CHECK, Color.WHITE, px(14), 2.8f)))
        on.setLayerGravity(1, Gravity.CENTER)
        val off = oval(if (overlay) Color.argb(90, 0, 0, 0) else Color.TRANSPARENT).apply {
            setSize(size, size)
            setStroke(px(2), if (overlay) Color.WHITE else pal.outlineStrong)
        }
        return StateListDrawable().apply {
            setEnterFadeDuration(120)
            setExitFadeDuration(120)
            addState(intArrayOf(android.R.attr.state_checked), on)
            addState(intArrayOf(), off)
        }
    }

    private fun checkBox(overlay: Boolean = false) = CheckBox(this).apply {
        setButtonDrawable(tickBox(overlay))
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        if (overlay) {
            background = null
            setPadding(0, 0, 0, 0)
        }
    }

    private fun switchOf(on: Boolean, changed: (Boolean) -> Unit) = Switch(this).apply {
        thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.WHITE, if (pal.dark) hex("#9AA3B2") else Color.WHITE))
        trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(pal.fill, pal.outlineStrong))
        isChecked = on
        setOnCheckedChangeListener { _, c -> changed(c) }
    }

    /** A settings row: icon tile, title, muted explanation, and an optional control on the right. */
    private fun settingRow(icon: Int, title: String, sub: TextView, end: View?): LinearLayout {
        val r = hrow()
        r.setPadding(px(S3), px(S3), px(S3), px(S3))
        r.addView(tile(icon, pal.accent, pal.accentSoft), lp(px(36), px(36)))
        val t = LinearLayout(this)
        t.orientation = LinearLayout.VERTICAL
        t.setPadding(px(S3), 0, px(S2), 0)
        t.addView(label(title, T_BODY + 1, pal.text, MEDIUM))
        t.addView(sub)
        r.addView(t, lp(0, WRAP, 1f))
        if (end != null) r.addView(end, lp(WRAP, WRAP))
        r.background = ripple(null, rounded(Color.WHITE, R_MD))
        return r
    }

    private fun subText(t: String) = label(t, T_CAPTION, pal.muted).apply { setPadding(0, px(2), 0, 0) }

    private fun addDivider(parent: LinearLayout, inset: Int) = parent.addView(
        View(this).apply { setBackgroundColor(pal.outline) },
        lp(MATCH, 1).apply { marginStart = px(inset); marginEnd = px(S3) })

    /** A slim rounded progress bar that animates to each new value. */
    private fun barDrawable(): Drawable {
        val front = ClipDrawable(rounded(pal.accent, 3), Gravity.START, ClipDrawable.HORIZONTAL)
        return LayerDrawable(arrayOf<Drawable>(rounded(if (pal.dark) pal.outline else pal.surface2, 3), front)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    /** New cards fade and slide in; removed ones fade out. */
    private fun cardMotion() = LayoutTransition().apply {
        setAnimator(LayoutTransition.APPEARING, ObjectAnimator.ofPropertyValuesHolder(null as Any?,
            PropertyValuesHolder.ofFloat("alpha", 0f, 1f), PropertyValuesHolder.ofFloat("translationY", pxf(24f), 0f)))
        setInterpolator(LayoutTransition.APPEARING, DecelerateInterpolator(1.8f))
        setDuration(LayoutTransition.APPEARING, 340)
        setStartDelay(LayoutTransition.APPEARING, 60)
        setDuration(LayoutTransition.DISAPPEARING, 200)
    }

    private fun toneColor(k: Int) = when (k) {
        K_ACTIVE -> pal.accent
        K_WARN -> pal.warn
        K_ERROR -> pal.danger
        K_OK -> pal.ok
        else -> pal.muted
    }

    private fun toneKey(c: Int) = when (c) {
        C_PRIMARY -> K_ACTIVE
        C_WARN -> K_WARN
        C_DANGER -> K_ERROR
        C_OK -> K_OK
        else -> K_MUTED
    }

    @Suppress("DEPRECATION")
    private fun paintSystemBars() {
        window.statusBarColor = pal.bg
        window.navigationBarColor = pal.surface
        val d = window.decorView
        val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        d.systemUiVisibility = if (pal.dark) d.systemUiVisibility and light.inv() else d.systemUiVisibility or light
        d.setBackgroundColor(pal.bg)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val m = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (pal.dark) 0 else m, m)
        }
    }

    /** Tints the status bubble by what its message says: error, waiting, success or in progress. */
    private fun paintStatus() {
        val s = status.text.toString().lowercase()
        fun has(vararg k: String) = k.any { it in s }
        val tone = when {
            has("copied", "up to date") -> K_OK
            has("could not", "failed", "for details", "error") -> K_ERROR
            has("waiting", "already", "tick ", "no link", "first", "still starting", "no internet", "no app") -> K_WARN
            has("ready", "done", "updated", "optimized", "deleted", "removed", "cleared") -> K_OK
            else -> K_ACTIVE
        }
        val c = toneColor(tone)
        status.background = rounded(fade(c, if (pal.dark) 0.14f else 0.08f), R_MD, fade(c, 0.25f))
        val icon = Glyph(when (tone) { K_ERROR -> G_ALERT; K_OK -> G_CHECK; else -> G_INFO }, c, -1,
            if (tone == K_OK) 2.4f else 2f)
        icon.setBounds(0, 0, px(20), px(20))
        var end: Drawable? = null
        if (checkErr.isNotEmpty() && "details" in s) {
            end = Glyph(G_CHEVRON_RIGHT, c)
            end.setBounds(0, 0, px(18), px(18))
        }
        status.setCompoundDrawablesRelative(icon, null, end, null)
    }

    /** The small engine chip in the top bar: Starting, Updating, Ready or Engine error. */
    private fun refreshChip() {
        val (txt, tone) = when {
            engineFailed -> "Engine error" to K_ERROR
            updating -> "Updating" to K_ACTIVE
            ready -> "Ready" to K_OK
            else -> "Starting" to K_WARN
        }
        val c = toneColor(tone)
        engineChip.text = txt
        engineChip.setTextColor(c)
        engineChip.background = rounded(fade(c, if (pal.dark) 0.16f else 0.1f), R_PILL)
        engineChip.setCompoundDrawablesRelative(dot(c, 7), null, null, null)
    }

    /** Builds the whole screen in the current palette: at start, and again when the system theme changes. */
    private fun buildUi() {
        // top bar (fixed): logo, name, tagline and the engine chip
        topBar = hrow()
        topBar.setPadding(px(S5), px(S3), px(S4), px(S3))
        topBar.background = ColorDrawable(pal.bg)
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.app_logo)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(pal.surface, R_SM + 2)
            clipToOutline = true
        }
        topBar.addView(logo, lp(px(40), px(40)))
        val names = LinearLayout(this)
        names.orientation = LinearLayout.VERTICAL
        names.setPadding(px(S3), 0, px(S2), 0)
        names.addView(label("VidGrab", T_DISPLAY, pal.text, BOLD))
        names.addView(label("Download videos & music from the web", T_CAPTION, pal.muted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        topBar.addView(names, lp(0, WRAP, 1f))
        engineChip = label("", T_CAPTION, pal.muted, MEDIUM).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(10), px(5), px(12), px(5))
            compoundDrawablePadding = px(6)
            setOnClickListener { if (!ready && checkErr.isNotEmpty()) showDetails("Details", checkErr) }
        }
        topBar.addView(engineChip, lp(WRAP, WRAP))
        val topLine = View(this)
        topLine.setBackgroundColor(pal.outline)
        topLine.alpha = 0f

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(px(S4), px(S1), px(S4), px(S5 + S1))

        // status bubble (right under the top bar); tap it for error details
        status = TextView(this).apply {
            text = "Starting engine..."
            textSize = T_LABEL + 0.5f
            setTextColor(pal.text)
            setLineSpacing(px(2).toFloat(), 1f)
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = px(10)
            setPadding(px(14), px(12), px(14), px(12))
            setOnClickListener { if (checkErr.isNotEmpty()) showDetails("Details", checkErr) }
        }
        status.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                paintStatus()
                refreshChip()
                status.animate().cancel()
                status.alpha = 0.55f
                status.animate().alpha(1f).setDuration(200).start()
            }
        })
        add(root, status, S2, S1)

        // links card: heading with the Paste action, the box, and a Clear action
        val links = card()
        val linksHead = hrow()
        val lt = LinearLayout(this)
        lt.orientation = LinearLayout.VERTICAL
        lt.addView(label("Add links", T_TITLE, pal.text, MEDIUM))
        lt.addView(subText("Checked automatically as you paste"))
        linksHead.addView(lt, lp(0, WRAP, 1f))
        linksHead.addView(btn("Paste", FILLED, G_PASTE, 38) { pasteClipboard() }, lp(WRAP, px(38)))
        add(links, linksHead)
        input = EditText(this).apply {
            hint = "Paste links here, one per line"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            textSize = T_BODY
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), rounded(pal.surface2, R_MD, pal.accent, 1.5f))
                addState(intArrayOf(), rounded(pal.surface2, R_MD, pal.outline))
            }
            setPadding(px(14), px(12), px(14), px(12))
            setTextColor(pal.text)
            setHintTextColor(pal.subtle)
            highlightColor = fade(pal.accent, 0.3f)
        }
        input.setTextCursorDrawable(rounded(pal.accent, 1).apply { setSize(px(2), px(20)) })
        add(links, input, S3)
        val foot = hrow()
        foot.addView(label("Tip: share a link to VidGrab from any app", T_CAPTION, pal.subtle), lp(0, WRAP, 1f))
        clearBtn = btn("Clear", GHOST, G_CLOSE, 32) { input.setText("") }
        clearBtn.textSize = T_LABEL
        clearBtn.setPadding(px(10), 0, px(12), 0)
        clearBtn.visibility = View.INVISIBLE
        foot.addView(clearBtn, lp(WRAP, px(32)))
        add(links, foot, S2)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                clearBtn.visibility = if (e.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
                handler.removeCallbacks(checkRun)
                handler.postDelayed(checkRun, 600)
            }
        })
        add(root, links, S3)

        // settings card: tidy rows, each one tappable as a whole
        add(root, overline("Settings"))
        val settings = card()
        settings.setPadding(px(S1), px(S1), px(S1), px(S1))
        folderLabel = subText("").apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val change = label("Change", T_LABEL, pal.accent, MEDIUM)
        val chev = Glyph(G_CHEVRON_RIGHT, pal.accent)
        chev.setBounds(0, 0, px(16), px(16))
        change.setCompoundDrawablesRelative(null, null, chev, null)
        change.compoundDrawablePadding = px(2)
        val folderRow = settingRow(G_FOLDER, "Save to", folderLabel, change)
        folderRow.setOnClickListener { pickFolder() }
        add(settings, folderRow)
        addDivider(settings, 60)
        val wifi = switchOf(wifiOnly()) { c ->
            prefs.edit().putBoolean("wifiOnly", c).apply()
            // Turned on while on mobile data: pause running downloads until Wi-Fi is back.
            // Turned off: waiting downloads notice within a couple of seconds and continue.
            if (c && !canDownload()) restartRunning()
        }
        val wifiRow = settingRow(G_WIFI, "Wi-Fi only", subText("Turn off to also download on mobile data"), wifi)
        wifiRow.setOnClickListener { wifi.toggle() }
        add(settings, wifiRow)
        addDivider(settings, 60)
        val opt = switchOf(prefs.getBoolean("optimize", true)) { c -> prefs.edit().putBoolean("optimize", c).apply() }
        val optRow = settingRow(G_SPARK, "Optimize after download",
            subText("Smoother playback and seeking. Turn off to optimize later"), opt)
        optRow.setOnClickListener { opt.toggle() }
        add(settings, optRow)
        add(root, settings)

        // video list: heading with count + "Select all", empty state, then one card per video
        val listHead = hrow()
        listHead.setPadding(px(S1), px(S5 + S2), 0, px(S2))
        listHead.addView(label("Your videos", T_TITLE + 1, pal.text, BOLD))
        countBadge = label("", T_CAPTION, pal.onAccentSoft, MEDIUM).apply {
            background = rounded(pal.accentSoft, R_PILL)
            gravity = Gravity.CENTER
            minWidth = px(24)
            setPadding(px(8), px(2), px(8), px(2))
        }
        listHead.addView(countBadge, lp(WRAP, WRAP).apply { marginStart = px(S2) })
        listHead.addView(View(this), lp(0, 1, 1f))
        selectAll = checkBox().apply {
            text = "Select all"
            textSize = T_LABEL
            typeface = MEDIUM
            setTextColor(pal.muted)
            setPadding(px(S2), px(6), px(S1), px(6))
            setOnCheckedChangeListener { _, c -> if (!syncing) items.forEach { it.check.isChecked = c } }
        }
        listHead.addView(selectAll, lp(WRAP, WRAP))
        add(root, listHead)

        val emptyBox = LinearLayout(this)
        emptyBox.orientation = LinearLayout.VERTICAL
        emptyBox.gravity = Gravity.CENTER_HORIZONTAL
        emptyBox.background = GradientDrawable().apply {
            cornerRadius = pxf(R_LG.toFloat())
            setColor(fade(pal.surface, 0.55f))
            setStroke(maxOf(2, pxf(1.5f).toInt()), pal.outlineStrong, pxf(6f), pxf(5f))
        }
        emptyBox.setPadding(px(24), px(28), px(24), px(28))
        emptyBox.addView(ImageView(this).apply {
            background = oval(pal.accentSoft)
            scaleType = ImageView.ScaleType.CENTER
            setImageDrawable(Glyph(G_DOWNLOAD, pal.accent, px(26), 2.2f))
        }, lp(px(56), px(56)))
        emptyBox.addView(label("No videos yet", T_TITLE, pal.text, MEDIUM), lp(WRAP, WRAP).apply { topMargin = px(S3 + 2) })
        emptyBox.addView(label("Paste a link above, or share one to VidGrab from any app.", T_LABEL, pal.muted).apply {
            gravity = Gravity.CENTER
            setLineSpacing(px(2).toFloat(), 1f)
        }, lp(WRAP, WRAP).apply { topMargin = px(S1 + 2) })
        emptyBox.addView(label("Paste  →  Pick quality  →  Download", T_CAPTION, pal.subtle, MEDIUM).apply {
            background = rounded(pal.surface2, R_PILL)
            setPadding(px(12), px(5), px(12), px(5))
        }, lp(WRAP, WRAP).apply { topMargin = px(S4) })
        empty = emptyBox
        add(root, empty, S1)
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        add(root, list)

        // bottom bar: the main action is always in reach; everything else is under "More"
        dlBtn = btn("Download", FILLED, 0, 52) { downloadSelected() }
        dlBtn.textSize = T_TITLE - 0.5f
        val more = btn("More", TONAL, G_MORE, 52) { showMenu() }
        val bottom = hrow()
        bottom.setPadding(px(S4), px(S3), px(S4), px(S3))
        bottom.background = ColorDrawable(pal.surface)
        bottom.elevation = pxf(12f)
        bottom.addView(dlBtn, lp(0, px(52), 1f))
        bottom.addView(more, lp(WRAP, px(52)).apply { marginStart = px(S3) })

        scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(root)
        // A hairline (and a soft shadow in light mode) under the top bar once the page is scrolled.
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            topLine.alpha = if (y > 0) 1f else 0f
            topBar.elevation = if (y > 0 && !pal.dark) pxf(3f) else 0f
        }
        val bottomLine = View(this)
        bottomLine.setBackgroundColor(pal.outline)
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setBackgroundColor(pal.bg)
        page.addView(topBar, lp(MATCH, WRAP))
        page.addView(topLine, lp(MATCH, 1))
        page.addView(scroll, lp(MATCH, 0, 1f))
        page.addView(bottomLine, lp(MATCH, 1))
        page.addView(bottom, lp(MATCH, WRAP))
        setContentView(page)
        paintSystemBars()
        paintStatus()
        refreshChip()
        updateFolderLabel()
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        if (isNight(c) != pal.dark) applyTheme()
    }

    /**
     * The system switched between light and dark: rebuild every view in the new palette, keeping what is
     * on screen (typed links, status, ticks, chosen quality, status lines, progress). Items keep running;
     * their background work looks up a.state / a.bar when it updates them, so it finds the new views.
     */
    private fun applyTheme() {
        val typed = input.text.toString()
        val said = status.text.toString()
        val y = scroll.scrollY
        val ticks = items.map { it.check.isChecked }
        val sels = items.map { it.spinner.selectedItemPosition }
        val states = items.map { it.state.text.toString() }
        val bars = items.map { it.bar.progress }
        pal = if (isNight()) DARK else LIGHT
        setTheme(themeRes())
        buildUi()
        input.setText(typed)
        status.text = said
        for ((k, a) in items.withIndex()) {
            buildCard(a)
            a.check.isChecked = ticks[k]
            a.spinner.setSelection(sels[k])
            a.state.text = states[k]
            paintState(a)
            a.bar.progress = bars[k]
            list.addView(a.box, cardParams())
            refreshButtons(a)
        }
        list.layoutTransition = cardMotion()
        updateCounts()
        scroll.post { scroll.scrollTo(0, y) }
    }

    override fun onCreate(s: Bundle?) {
        pal = if (isNight()) DARK else LIGHT
        setTheme(themeRes())
        super.onCreate(s)
        dp = resources.displayMetrics.density
        prefs = getSharedPreferences("vidgrab", MODE_PRIVATE)
        buildUi()
        updateCounts()
        restore()
        list.layoutTransition = cardMotion()
        sweepPartials()
        handleIntent(intent)
        if (android.os.Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
        try { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCb) } catch (_: Exception) {}
        Thread {
            try {
                YoutubeDL.getInstance().init(application)
                FFmpeg.getInstance().init(application)
                autoUpdate()
                ready = true
                runOnUiThread {
                    status.text ="Ready (engine ${YoutubeDL.getInstance().versionName(application)}). Paste links above."
                    autoCheck()
                }
            } catch (e: Exception) {
                checkErr = rawError(e)
                engineFailed = true
                runOnUiThread { status.text = "Could not start the download engine. Tap here for details." }
            }
        }.start()
    }

    /**
     * Sites change often and an outdated engine stops working with them (the cause of "everything fails"
     * after a reinstall), so check for a new yt-dlp once a day at startup. It is a small download (~3 MB).
     */
    private fun autoUpdate() {
        val last = prefs.getLong("engineChecked", 0L)
        if (System.currentTimeMillis() - last < 24 * 60 * 60 * 1000L || !online()) return
        updating = true
        runOnUiThread { status.text = "Updating the download engine..." }
        try {
            YoutubeDL.getInstance().updateYoutubeDL(application, YoutubeDL.UpdateChannel.NIGHTLY)
            prefs.edit().putLong("engineChecked", System.currentTimeMillis()).apply()
        } catch (_: Exception) {
            // keep the current engine; it is tried again on the next start
        } finally {
            updating = false
        }
    }

    override fun onPause() { super.onPause(); persist() }

    // Back keeps the app (and its downloads) alive in the background instead of closing it.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (items.any { it.busy }) moveTaskToBack(true) else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCb) } catch (_: Exception) {}
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        handleIntent(i)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            input.append((Regex("https?://\\S+").find(t)?.value ?: t) + "\n")
        }
    }

    // ---------- connection + readable errors ----------
    private fun online(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val c = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun wifiOnly() = prefs.getBoolean("wifiOnly", true)

    /** True for Wi-Fi / Ethernet, including a VPN running on top of Wi-Fi. */
    private fun onWifi(cm: ConnectivityManager, c: NetworkCapabilities): Boolean {
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return true
        if (!c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        // A VPN hides the real connection: look at the networks under it.
        @Suppress("DEPRECATION") val nets = cm.allNetworks
        return nets.any { n ->
            val nc = cm.getNetworkCapabilities(n)
            nc != null && !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }

    private fun onWifiNow(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val c = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return onWifi(cm, c)
    }

    /** Online, and on Wi-Fi unless the user allowed mobile data. */
    private fun canDownload() = online() && (!wifiOnly() || onWifiNow())

    /** The full error text (yt-dlp's stderr and any causes), for the details dialog. */
    private fun rawError(e: Throwable): String {
        val sb = StringBuilder()
        var c: Throwable? = e
        while (c != null) { sb.append(c.message ?: c.javaClass.simpleName).append('\n'); c = c.cause }
        return sb.toString().trim()
    }

    /** Shows an error in full, with the engine version, and lets the user copy it. */
    private fun showDetails(title: String, text: String) {
        val engine = try { YoutubeDL.getInstance().versionName(application) } catch (_: Exception) { null }
        val full = ("Engine: " + (engine ?: "unknown") + "\n\n" + text).take(6000)
        val dlg = AlertDialog.Builder(this, dialogTheme()).setTitle(title).setMessage(full)
            .setPositiveButton("Copy") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("VidGrab error", full))
                status.text = "Error details copied."
            }
            .setNegativeButton("Close", null)
            .show()
        // error text reads best in a monospace font, and can be selected
        dlg.findViewById<TextView>(android.R.id.message)?.apply {
            typeface = Typeface.MONOSPACE
            textSize = T_CAPTION
            setTextIsSelectable(true)
        }
    }

    /** Returns a short readable cause, and whether it is a connection problem (worth retrying). */
    private fun why(e: Throwable): Pair<String, Boolean> {
        if (!online()) return "No internet connection" to true
        val raw = rawError(e)
        val t = raw.lowercase()
        fun has(vararg k: String) = k.any { it in t }
        return when {
            has("private video") -> "This video is private" to false
            has("sign in", "log in", "login", "not a bot") -> "This site asks for a login" to false
            has("not available in your country", "geo restrict", "geo-restrict") -> "Not available in your country" to false
            has("unsupported url") -> "This link is not supported" to false
            has("video unavailable", "has been removed", "does not exist", "http error 404") -> "Video not found or removed" to false
            has("http error 403") -> "The site refused access (403)" to false
            has("http error 429", "too many requests") -> "Too many requests - try again later" to false
            has("no space left") -> "Phone storage is full" to false
            has("securityexception", "permission denied", "cannot create file") -> "Cannot write to the chosen folder - pick it again" to false
            has("timed out", "timeout") -> "Connection timed out" to true
            has("name or service not known", "failed to resolve", "getaddrinfo", "name resolution", "nodename nor servname") ->
                "Cannot reach the server" to true
            has("connection reset", "connection refused", "connection aborted", "remote end closed", "incompleteread",
                "network is unreachable", "unable to download", "urlopen error", "ssl", "eof occurred", "incomplete download") ->
                "Connection interrupted" to true
            else -> {
                // yt-dlp's own "ERROR: ..." line, else the last meaningful line (e.g. a Python error)
                val d = Regex("ERROR:\\s*([^\\n]{1,120})").find(raw)?.groupValues?.get(1)
                    ?: raw.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("WARNING") }?.take(120)
                (if (!d.isNullOrEmpty()) "Download failed: $d" else "Download failed (unknown reason)") to false
            }
        }
    }

    // ---------- download folder ----------
    private fun updateFolderLabel() {
        val t = prefs.getString("tree", null)
        // A picked folder's id looks like "primary:Download/Videos"; show the part after the colon.
        val name = if (t == null) "Download/VidGrab" else try {
            DocumentsContract.getTreeDocumentId(Uri.parse(t)).substringAfter(':').ifEmpty { "Internal storage" }
        } catch (_: Exception) { "custom folder" }
        folderLabel.text = name
    }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, 1)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 3 && res == RESULT_OK && data != null) {
            val uris = mutableListOf<Uri>()
            data.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) }
            if (uris.isEmpty()) data.data?.let { uris.add(it) }
            if (uris.isNotEmpty()) optimizePicked(uris)
            return
        }
        if (req == 1 && res == RESULT_OK) {
            val u = data?.data ?: return
            contentResolver.takePersistableUriPermission(u,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs.edit().putString("tree", u.toString()).apply()
            updateFolderLabel()
        }
    }

    // ---------- list of videos ----------
    /** Checks every new link in the paste box automatically and lists its available resolutions. */
    private fun autoCheck() {
        if (!ready) return
        val inText = Regex("https?://\\S+").findAll(input.text).map { it.value }.distinct().toList()
        val newly = inText.filter { it !in prevLinks }
        prevLinks = inText.toSet()
        failed.removeAll(newly.toSet())          // a link you paste again is tried again
        failed.retainAll(inText.toSet())
        val fresh = inText.filter { u -> items.none { it.url == u } && u !in failed && u !in checking }
        if (fresh.isEmpty()) {
            if (newly.any { n -> items.any { it.url == n } }) status.text = "That link is already in your list below."
            return
        }
        status.text = "Checking ${fresh.size} link(s)..."
        checking.addAll(fresh)
        analyzer.execute {
            val problems = mutableListOf<String>()
            val details = StringBuilder()
            var added = 0
            for ((n, u) in fresh.withIndex()) {
                runOnUiThread { status.text = "Checking link ${n + 1} of ${fresh.size}..." }
                try {
                    val req = YoutubeDLRequest(u)
                    req.addOption("--dump-single-json")
                    req.addOption("--no-playlist")
                    req.addOption("--socket-timeout", "20")
                    val j = JSONObject(YoutubeDL.getInstance().execute(req).out)
                    val sizes = mutableMapOf<Int, Long>()
                    val fm = j.optJSONArray("formats")
                    for (k in 0 until (fm?.length() ?: 0)) {
                        val f = fm!!.getJSONObject(k)
                        val h = f.optInt("height", 0)
                        if (h <= 0 || f.optString("vcodec", "") == "none") continue
                        val sz = f.optLong("filesize", f.optLong("filesize_approx", 0))
                        if (sz >= (sizes[h] ?: -1L)) sizes[h] = sz
                    }
                    val labels = mutableListOf<String>()
                    val choices = mutableListOf<Int>()
                    for (h in sizes.keys.sortedDescending()) {
                        val sz = sizes[h] ?: 0L
                        labels.add("${h}p" + if (sz > 0) " (~${sz / 1_000_000} MB)" else "")
                        choices.add(h)
                    }
                    labels.add("Best available"); choices.add(0)
                    labels.add("Audio only (MP3)"); choices.add(-1)
                    val dur = j.optDouble("duration", 0.0).toLong()
                    val meta = listOf(j.optString("extractor_key", ""), if (dur > 0) fmt(dur) else "",
                        j.optString("uploader", ""))
                        .filter { it.isNotEmpty() && it != "null" }.joinToString(" · ")
                    val item = Item(u, j.optString("title", u), labels, choices, j.optString("thumbnail", ""), meta)
                    runOnUiThread {
                        if (items.none { it.url == u }) {
                            addRow(item)
                            item.check.isChecked = true
                            persist()
                        }
                        checking.remove(u)
                    }
                    added++
                } catch (e: Exception) {
                    val (msg, net) = why(e)
                    if (!net) failed.add(u)
                    checking.remove(u)
                    problems.add("Link ${n + 1}: $msg")
                    details.append(u).append('\n').append(rawError(e)).append("\n\n")
                }
            }
            checkErr = details.toString().trim()
            runOnUiThread {
                val head = if (added > 0) "Pick a resolution for each video, then tap Download." else ""
                val tail = if (problems.isNotEmpty()) "Tap here for details." else ""
                status.text = (listOf(head) + problems + tail).filter { it.isNotEmpty() }.joinToString("\n")
            }
        }
    }

    /** Removes a link from the paste box once its download has started. */
    private fun removeFromInput(u: String) {
        val t = input.text.toString()
        if (u in t) input.setText(t.lines().filter { !it.contains(u) }.joinToString("\n"))
    }

    private fun addRow(a: Item) {
        buildCard(a)
        if (a.done) {
            a.bar.progress = 100
            setState(a, "Done - saved", C_OK)
        }
        list.addView(a.box, cardParams())
        items.add(a)
        refreshButtons(a)
        updateCounts()
    }

    private fun cardParams() = lp(MATCH, WRAP).apply { setMargins(0, px(S1 + 2), 0, px(S1 + 2)) }

    /** The quality picker's texts, in the theme's colors. */
    private fun qualityAdapter(labels: List<String>) =
        object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(pal.text)
                v.textSize = T_LABEL
                v.typeface = MEDIUM
                v.setPadding(px(14), 0, px(34), 0)    // room on the right for the chevron
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setTextColor(pal.text)
                v.textSize = T_BODY
                v.setPadding(px(S4), px(S3), px(S4), px(S3))
                return v
            }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    /** The quality picker as a pill with a chevron. */
    private fun styleChip(sp: Spinner) {
        val pill = LayerDrawable(arrayOf<Drawable>(rounded(pal.surface2, R_PILL, pal.outline),
            Glyph(G_CHEVRON_DOWN, pal.muted, px(16))))
        pill.setLayerGravity(1, Gravity.CENTER_VERTICAL or Gravity.END)
        pill.setLayerInsetEnd(1, px(12))
        sp.background = ripple(pill)
        sp.setPadding(0, 0, 0, 0)
        sp.setPopupBackgroundDrawable(rounded(pal.surface, R_MD, pal.outline))
        sp.dropDownVerticalOffset = px(S1)
    }

    /**
     * Builds an item's card (also used to rebuild it after a theme change): thumbnail with a tick box,
     * duration and "done" badges; title and meta; quality pill and actions; progress; status line.
     */
    private fun buildCard(a: Item) {
        // thumbnail: a video icon on a soft tile until the picture has loaded
        val thumb = FrameLayout(this)
        thumb.background = rounded(pal.surface2, R_MD - 2)
        thumb.clipToOutline = true
        a.thumbView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageDrawable(Glyph(G_VIDEO, pal.subtle, px(28), 1.8f))
        }
        thumb.addView(a.thumbView, FrameLayout.LayoutParams(MATCH, MATCH))
        // the duration goes on the picture, the rest of the meta ("Site · Uploader") under the title
        val parts = a.meta.split(" · ").filter { it.isNotEmpty() }
        val dur = parts.firstOrNull { Regex("\\d+:\\d{2}(:\\d{2})?").matches(it) }
        if (dur != null) thumb.addView(TextView(this).apply {
            text = dur
            textSize = 10.5f
            typeface = MEDIUM
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(190, 0, 0, 0), 6)
            setPadding(px(5), px(1), px(5), px(1))
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, px(5), px(5)) })
        a.doneBadge = ImageView(this).apply {
            background = oval(pal.ok)
            scaleType = ImageView.ScaleType.CENTER
            setImageDrawable(Glyph(G_CHECK, if (pal.dark) pal.bg else Color.WHITE, px(14), 2.8f))
            visibility = View.GONE
        }
        thumb.addView(a.doneBadge, FrameLayout.LayoutParams(px(22), px(22), Gravity.TOP or Gravity.END)
            .apply { setMargins(0, px(6), px(6), 0) })
        a.check = checkBox(true).apply {
            setOnCheckedChangeListener { _, _ -> paintCard(a); updateCounts() }
        }
        thumb.addView(a.check, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START)
            .apply { setMargins(px(6), px(6), 0, 0) })

        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titles.setPadding(px(S3), 0, 0, 0)
        titles.addView(TextView(this).apply {
            text = a.title
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            textSize = T_BODY + 0.5f
            typeface = MEDIUM
            setTextColor(pal.text)
            setLineSpacing(px(1).toFloat(), 1f)
        })
        val metaText = parts.filter { it != dur }.joinToString(" · ")
        if (metaText.isNotEmpty()) titles.addView(TextView(this).apply {
            text = metaText
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            textSize = T_CAPTION
            setTextColor(pal.muted)
            setPadding(0, px(3), 0, 0)
        })
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.addView(thumb, lp(px(112), px(63)))
        top.addView(titles, lp(0, WRAP, 1f))

        // quality pill on the left, actions on the right
        a.spinner = Spinner(this, Spinner.MODE_DROPDOWN)
        a.spinner.adapter = qualityAdapter(a.labels)
        styleChip(a.spinner)
        val chipBox = FrameLayout(this)
        chipBox.addView(a.spinner, FrameLayout.LayoutParams(WRAP, px(36), Gravity.START or Gravity.CENTER_VERTICAL))
        // The two buttons change role: Start/Pause + Stop while unfinished, Open + Share when done.
        a.toggle = btn("Start", FILLED, G_PLAY, 38) {
            when {
                a.done -> openItem(a)
                a.busy && !a.paused && !a.cancelled -> pauseItem(a)
                else -> startItem(a)
            }
        }
        a.toggle.textSize = T_LABEL + 0.5f
        a.toggle.setPadding(px(12), 0, px(16), 0)
        a.stopBtn = ImageButton(this)
        a.stopBtn.scaleType = ImageView.ScaleType.CENTER
        a.stopBtn.setPadding(0, 0, 0, 0)
        a.stopBtn.setOnClickListener { if (a.done) shareItem(a) else stopItem(a) }
        val actions = hrow()
        actions.addView(chipBox, lp(0, WRAP, 1f))
        actions.addView(a.stopBtn, lp(px(38), px(38)).apply { marginStart = px(S2) })
        actions.addView(a.toggle, lp(WRAP, px(38)).apply { marginStart = px(S2) })

        a.bar = SmoothBar(this).apply {
            max = 100
            progressDrawable = barDrawable()
        }
        a.state = TextView(this).apply {
            text = "Ready to download"
            textSize = T_LABEL
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = px(S2)
            setLineSpacing(px(1).toFloat(), 1f)
            setOnClickListener { if (a.err.isNotEmpty()) showDetails(a.title, a.err) }
        }
        paintState(a)

        // Tapping the card ticks or unticks it.
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(px(S3), px(S3), px(S3), px(S3 + 2))
        box.elevation = if (pal.dark) 0f else pxf(1.5f)
        box.foreground = ripple(null, rounded(Color.WHITE, R_LG))
        box.setOnClickListener { a.check.toggle() }
        box.addView(top, lp(MATCH, WRAP))
        box.addView(actions, lp(MATCH, WRAP).apply { topMargin = px(S3) })
        box.addView(a.bar, lp(MATCH, px(5)).apply { topMargin = px(S3 + 2) })
        box.addView(a.state, lp(MATCH, WRAP).apply { topMargin = px(S2 + 2) })
        a.box = box
        paintCard(a)
        loadThumb(a)
    }

    /** Card background; the outline turns accent when the card is ticked. */
    private fun paintCard(a: Item) {
        val on = a.check.isChecked
        a.box.background = rounded(pal.surface, R_LG, if (on) fade(pal.accent, 0.75f) else pal.outline,
            if (on) 1.5f else 1f)
    }

    /** Video count in the list heading, "Select all" state, empty state, and the Download button label. */
    private fun updateCounts() {
        val n = items.size
        val sel = items.count { it.check.isChecked && !it.done }
        countBadge.text = n.toString()
        countBadge.visibility = if (n == 0) View.GONE else View.VISIBLE
        empty.visibility = if (n == 0) View.VISIBLE else View.GONE
        selectAll.visibility = if (n == 0) View.INVISIBLE else View.VISIBLE
        dlBtn.text = iconText(G_DOWNLOAD, if (sel > 0) "Download ($sel)" else "Download", Color.WHITE)
        syncing = true
        selectAll.isChecked = n > 0 && items.all { it.check.isChecked }
        syncing = false
    }

    /** Loads (and caches) the video's thumbnail into its card. */
    private fun loadThumb(a: Item) {
        if (!a.thumb.startsWith("http")) return
        val v = a.thumbView
        imgPool.execute {
            try {
                val f = File(cacheDir, "thumbs/" + Integer.toHexString(a.url.hashCode()))
                if (!f.exists()) {
                    f.parentFile?.mkdirs()
                    val c = java.net.URL(a.thumb).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 10_000
                    c.readTimeout = 10_000
                    try {
                        if (c.responseCode != 200) return@execute
                        val tmp = File(f.path + ".tmp")
                        c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                        tmp.renameTo(f)
                    } finally {
                        c.disconnect()
                    }
                }
                // decode at about the size it is shown, not the full resolution
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.path, o)
                var s = 1
                while (o.outWidth / (s * 2) >= px(112)) s *= 2
                val bmp = android.graphics.BitmapFactory.decodeFile(f.path,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = s }) ?: return@execute
                runOnUiThread {
                    v.imageTintList = null
                    v.scaleType = ImageView.ScaleType.CENTER_CROP
                    v.setImageBitmap(bmp)
                    v.alpha = 0f
                    v.animate().alpha(1f).setDuration(260).start()
                }
            } catch (_: Exception) {}
        }
    }

    private fun openItem(a: Item) {
        val u = Uri.parse(a.files.firstOrNull() ?: return)
        try {
            startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(u, contentResolver.getType(u) ?: "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            status.text = "No app found to open this file (or it was deleted)."
        }
    }

    private fun shareItem(a: Item) {
        if (a.files.isEmpty()) return
        val uris = ArrayList(a.files.map { Uri.parse(it) })
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        i.type = (try { contentResolver.getType(uris[0]) } catch (_: Exception) { null }) ?: "video/*"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(i, "Share")) } catch (_: Exception) {}
    }

    /** Everything that is not the main Download action, in a bottom sheet. */
    private fun showMenu() {
        val busy = items.any { it.busy }
        // Counts on the rows (and greyed-out rows) show at a glance what each action would touch.
        val ticked = items.filter { it.check.isChecked }
        val tickedUnfinished = ticked.count { !it.done }
        val tickedWithFiles = ticked.count { it.files.isNotEmpty() }
        val completed = items.count { it.done }
        val d = Dialog(this, if (pal.dark) android.R.style.Theme_DeviceDefault_Dialog_NoActionBar
            else android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar)
        val sheet = LinearLayout(this)
        sheet.orientation = LinearLayout.VERTICAL
        sheet.background = GradientDrawable().apply {
            setColor(pal.surface)
            val r = pxf(28f)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        sheet.setPadding(px(S2), px(S3), px(S2), px(S4))
        sheet.addView(View(this).apply { background = rounded(pal.outlineStrong, R_PILL) },
            lp(px(36), px(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = px(S3) })
        sheet.addView(label("More actions", T_TITLE, pal.text, MEDIUM).apply { setPadding(px(S4), 0, px(S4), px(S2)) })
        val rows = LinearLayout(this)
        rows.orientation = LinearLayout.VERTICAL
        val scrollRows = ScrollView(this)
        scrollRows.isVerticalScrollBarEnabled = false
        scrollRows.addView(rows)
        sheet.addView(scrollRows, lp(MATCH, WRAP))

        fun entry(icon: Int, title: String, sub: String, count: Int?, enabled: Boolean, danger: Boolean = false,
                  act: () -> Unit) {
            val r = hrow()
            r.setPadding(px(S3), px(10), px(S4), px(10))
            r.background = ripple(null, rounded(Color.WHITE, R_MD))
            val fg = if (danger) pal.danger else pal.accent
            r.addView(tile(icon, fg, if (danger) pal.dangerSoft else pal.accentSoft, 40, R_MD - 2), lp(px(40), px(40)))
            val t = LinearLayout(this)
            t.orientation = LinearLayout.VERTICAL
            t.setPadding(px(S3), 0, px(S2), 0)
            t.addView(label(title, T_BODY + 1, if (danger) pal.danger else pal.text, MEDIUM))
            t.addView(subText(sub))
            r.addView(t, lp(0, WRAP, 1f))
            if (count != null) r.addView(label(count.toString(), T_CAPTION, pal.muted, MEDIUM).apply {
                background = rounded(pal.surface2, R_PILL)
                gravity = Gravity.CENTER
                minWidth = px(26)
                setPadding(px(8), px(3), px(8), px(3))
            }, lp(WRAP, WRAP))
            r.isEnabled = enabled
            r.alpha = if (enabled) 1f else 0.4f
            if (enabled) r.setOnClickListener { d.dismiss(); act() }
            rows.addView(r, lp(MATCH, WRAP))
        }

        entry(G_DONE_ALL, "Remove completed", "Clears finished items. Files are kept.",
            completed, completed > 0) { removeCompleted() }
        entry(G_CLOSE, "Remove ticked unfinished", "Stops them and discards their partial files",
            tickedUnfinished, tickedUnfinished > 0) { removeUnfinished() }
        entry(G_TRASH, "Delete ticked videos", "Also deletes their saved files from your phone",
            ticked.size, ticked.isNotEmpty(), true) { deleteTicked() }
        entry(G_SPARK, "Optimize ticked files", "Fixes playback and seeking, without re-encoding",
            tickedWithFiles, tickedWithFiles > 0) { optimizeFiles() }
        entry(G_PHONE, "Optimize a video from my phone", "Saves an optimized copy; the original is untouched",
            null, ready) { pickVideos() }
        entry(G_REFRESH, when {
            updating -> "Updating engine..."
            busy -> "Update engine (after downloads finish)"
            else -> "Update engine"
        }, "Gets the newest site support (yt-dlp)", null, ready && !updating && !busy) { update() }
        entry(G_CLEAR_ALL, "Remove all", "Empties the list. Saved files are kept.",
            items.size, items.isNotEmpty()) { removeAll() }

        d.setContentView(sheet)
        d.setCanceledOnTouchOutside(true)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
            w.setWindowAnimations(android.R.style.Animation_InputMethod)
            w.setDimAmount(0.45f)
            w.decorView.setPadding(0, 0, 0, 0)
        }
        d.show()
    }

    private fun persist() {
        val arr = JSONArray()
        for (a in items) arr.put(JSONObject().apply {
            put("url", a.url); put("title", a.title); put("thumb", a.thumb); put("meta", a.meta)
            put("labels", JSONArray(a.labels)); put("choices", JSONArray(a.choices))
            put("sel", a.spinner.selectedItemPosition); put("done", a.done); put("pct", a.pct)
            put("files", JSONArray(a.files))
        })
        prefs.edit().putString("items", arr.toString()).apply()
    }

    private fun restore() {
        try {
            val arr = JSONArray(prefs.getString("items", null) ?: return)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val lj = o.getJSONArray("labels")
                val cj = o.getJSONArray("choices")
                val a = Item(o.getString("url"), o.getString("title"),
                    List(lj.length()) { lj.getString(it) }, List(cj.length()) { cj.getInt(it) },
                    o.optString("thumb", ""), o.optString("meta", ""))
                a.done = o.optBoolean("done")
                a.pct = o.optInt("pct", -1)
                o.optJSONArray("files")?.let { f -> for (k in 0 until f.length()) a.files.add(f.getString(k)) }
                addRow(a)
                a.spinner.setSelection(o.optInt("sel"))
                if (!a.done && a.pct >= 0) {
                    a.bar.progress = a.pct
                    setState(a, pausedText(a))
                }
            }
        } catch (_: Exception) {}
    }

    private fun confirm(msg: String, ok: () -> Unit) {
        if (autoConfirm) { ok(); return }
        AlertDialog.Builder(this, dialogTheme()).setMessage(msg)
            .setPositiveButton("Yes") { _, _ -> ok() }
            .setNegativeButton("No", null).show()
    }

    private fun choiceOf(a: Item) = a.choices[a.spinner.selectedItemPosition]

    // Same link + same resolution always maps to the same folder, so a retry continues the partial file.
    private fun dirFor(a: Item, choice: Int) = File(filesDir, "dl/" + Integer.toHexString((a.url + choice).hashCode()))

    private fun removeCompleted() {
        val gone = items.filter { it.done }
        if (gone.isEmpty()) { tell("No finished downloads to remove yet"); return }
        dropFromList(gone)
        tell("Removed ${gone.size} completed item(s). Files are kept.")
    }

    /** Deletes the partial-download folders of every resolution of this item, except one still in use. */
    private fun deletePartials(a: Item, keep: File? = null) {
        for (c in a.choices) { val d = dirFor(a, c); if (d != keep) d.deleteRecursively() }
    }

    /** Cancels an item (if running) and discards its partial files. A running worker deletes its own folder. */
    private fun discard(a: Item) {
        a.cancelled = true
        val busy = a.busy
        if (busy) kill(a)
        deletePartials(a, if (busy) a.runDir else null)
    }

    /** Removes partial-download folders that no item in the list uses any more. */
    private fun sweepPartials() {
        val keep = items.flatMap { a -> a.choices.map { dirFor(a, it).name } }.toSet()
        analyzer.execute {
            File(filesDir, "dl").listFiles()?.forEach {
                if (it.name !in keep && it.name !in running) it.deleteRecursively()
            }
        }
    }

    /**
     * Shows a result both in the status bubble and as a toast: the bubble sits at the top of the page,
     * out of sight when the list is scrolled, which made these actions look like they did nothing.
     */
    private fun tell(msg: String) {
        status.text = msg
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /** Takes items off the list (and off the screen). Call after discard() or when they are finished. */
    private fun dropFromList(sel: List<Item>) {
        for (a in sel) {
            list.removeView(a.box)
            items.remove(a)
        }
        persist()
        refreshService()
        updateCounts()
    }

    private fun removeUnfinished() {
        val ticked = items.filter { it.check.isChecked }
        val sel = ticked.filter { !it.done }
        if (sel.isEmpty()) {
            tell(if (ticked.isEmpty()) "Tick the unfinished downloads you want to remove first"
                else "The ticked videos are already finished - use \"Delete ticked videos\" or \"Remove completed\"")
            return
        }
        confirm("Remove ${sel.size} unfinished download(s) from the list? Their partial files are discarded.") {
            for (a in sel) discard(a)
            dropFromList(sel)
            tell("Removed ${sel.size} unfinished download(s).")
        }
    }

    private fun removeAll() {
        if (items.isEmpty()) { tell("The list is already empty"); return }
        confirm("Remove every item from the list, including unfinished ones? Partial downloads are discarded. Saved files are kept.") {
            val all = items.toList()
            for (a in all) discard(a)
            dropFromList(all)
            tell("List cleared.")
        }
    }

    /** Deletes one saved file. A file that is already gone counts as deleted. */
    private fun deleteSaved(f: String): Boolean = try {
        val u = Uri.parse(f)
        if (u.authority == MediaStore.AUTHORITY) contentResolver.delete(u, null, null)
        else DocumentsContract.deleteDocument(contentResolver, u)
        true
    } catch (_: java.io.FileNotFoundException) {
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Deletes the ticked videos, finished or not: their saved files are deleted from the phone,
     * unfinished downloads are stopped and their partial files discarded, and the cards are removed.
     */
    private fun deleteTicked() {
        val sel = items.filter { it.check.isChecked }
        if (sel.isEmpty()) { tell("Tick the videos you want to delete first"); return }
        val savedFiles = sel.fold(0) { n, a -> n + a.files.size }
        val msg = "Delete ${sel.size} ticked video(s)?\n\n" +
            (if (savedFiles > 0) "$savedFiles saved file(s) will be deleted from your phone.\n" else "") +
            "Unfinished downloads are stopped and discarded. The videos are removed from the list."
        confirm(msg) {
            var notDeleted = 0
            for (a in sel) {
                for (f in a.files) if (!deleteSaved(f)) notDeleted++
                a.files.clear()
                discard(a)
            }
            dropFromList(sel)
            tell(if (notDeleted == 0) "Deleted ${sel.size} video(s)."
                else "Deleted ${sel.size} video(s) from the list, but $notDeleted file(s) could not be deleted " +
                    "(VidGrab may no longer own them after a reinstall). Delete them in your Files app.")
        }
    }

    // ---------- optimize with the bundled ffmpeg ----------
    private fun ffmpegBin() = File(applicationInfo.nativeLibraryDir, "libffmpeg.so")

    /**
     * The bundled ffmpeg needs libraries from BOTH the library's python and ffmpeg folders (since
     * youtubedl-android 0.18 it links libc++_shared, libcrypto, libexpat, ... from the python one).
     * This is the same search path the library gives ffmpeg when yt-dlp merges a download.
     */
    private fun ffmpegEnv(pb: ProcessBuilder) {
        val pk = File(noBackupFilesDir, "youtubedl-android/packages")
        pb.environment()["LD_LIBRARY_PATH"] =
            listOf("python", "ffmpeg", "aria2c").joinToString(":") { File(pk, "$it/usr/lib").absolutePath }
        pb.environment()["TMPDIR"] = cacheDir.absolutePath
    }

    /**
     * Rebuilds the container (no re-encoding): fixes timestamps, drops broken packets, puts the index first.
     * Returns null when the file was optimized, otherwise the reason (with ffmpeg's error output).
     */
    private fun optimize(f: File): String? {
        val ext = f.extension.lowercase()
        if (ext !in listOf("mp4", "m4v", "mov", "mkv", "webm"))
            return "$NOT_VIDEO (.$ext) - only MP4, M4V, MOV, MKV and WEBM can be optimized"
        if (!ffmpegBin().exists()) return "The video tools (ffmpeg) are missing from this build"
        val dir = f.parentFile ?: return "Bad file location"
        if (dir.usableSpace < f.length() + 20_000_000L) return "Not enough free space to optimize"
        val out = File(dir, f.nameWithoutExtension + ".opt." + ext)
        try {
            val cmd = mutableListOf(ffmpegBin().absolutePath, "-nostdin", "-y", "-hide_banner", "-loglevel", "error",
                "-fflags", "+genpts+discardcorrupt", "-i", f.absolutePath,
                "-map", "0:v?", "-map", "0:a?", "-c", "copy", "-avoid_negative_ts", "make_zero")
            if (ext != "mkv" && ext != "webm") { cmd.add("-movflags"); cmd.add("+faststart") }
            cmd.add(out.absolutePath)
            val pb = ProcessBuilder(cmd)
            ffmpegEnv(pb)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val log = p.inputStream.bufferedReader().readText().trim()
            val code = p.waitFor()
            if (code == 0 && out.length() > f.length() / 2) {
                f.delete()
                return if (out.renameTo(f)) null else "Could not replace the file with the optimized one"
            }
            out.delete()
            return when {
                code == 0 -> "ffmpeg's result looked wrong, so the file was left unchanged"
                log.isEmpty() -> "ffmpeg failed (exit code $code)"
                else -> "ffmpeg failed (exit code $code):\n" + log.takeLast(1500)
            }
        } catch (e: Exception) {
            out.delete()
            return "Could not run ffmpeg: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun optimizeFiles() {
        val sel = items.filter { it.check.isChecked && it.files.isNotEmpty() }
        if (sel.isEmpty()) { tell("Tick finished videos first - only downloaded files can be optimized"); return }
        status.text = "Optimizing ${sel.size} item(s)..."
        analyzer.execute {
            var fixed = 0
            for (a in sel) {
                ui(a, "Optimizing...", C_PRIMARY)
                var ok = false
                val errs = mutableListOf<String>()
                for (s in a.files) {
                    val u = Uri.parse(s)
                    val ext = (try {
                        MimeTypeMap.getSingleton().getExtensionFromMimeType(contentResolver.getType(u))
                    } catch (_: Exception) { null }) ?: "mp4"
                    val tmp = File(cacheDir, "opt/${System.nanoTime()}.$ext").apply { parentFile?.mkdirs() }
                    try {
                        contentResolver.openInputStream(u)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                        val r = optimize(tmp)
                        if (r == null) {
                            contentResolver.openOutputStream(u, "wt")!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                            ok = true
                        } else errs.add(r)
                    } catch (e: Exception) {
                        errs.add(rawError(e))
                    }
                    tmp.delete()
                }
                if (ok) fixed++
                a.err = errs.joinToString("\n\n")
                ui(a, when {
                    ok && errs.isEmpty() -> "Optimized"
                    ok -> "Partly optimized - tap here for details"
                    errs.all { it.startsWith(NOT_VIDEO) } -> "Audio files don't need optimizing"
                    else -> "Could not optimize (file left unchanged) - tap here for details"
                }, if (ok) C_OK else C_DANGER)
            }
            runOnUiThread { tell("Optimized $fixed of ${sel.size} item(s).") }
        }
    }

    // ---------- clipboard + optimize a video from the phone ----------
    private fun pasteClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip
        val t = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else ""
        val urls = Regex("https?://\\S+").findAll(t).map { it.value }.toList()
        if (urls.isEmpty()) { status.text = "No link found in the clipboard"; return }
        input.append(urls.joinToString("\n") + "\n")
    }

    private fun pickVideos() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, 3)
    }

    private fun displayName(u: Uri): String? =
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** Optimizes videos chosen from the phone; each result is saved as a new "(optimized)" copy. */
    private fun optimizePicked(uris: List<Uri>) {
        status.text = "Optimizing ${uris.size} video(s)..."
        analyzer.execute {
            var ok = 0
            val errs = StringBuilder()
            for ((n, u) in uris.withIndex()) {
                runOnUiThread { status.text = "Optimizing video ${n + 1} of ${uris.size}..." }
                val name = (displayName(u) ?: "video.mp4").replace("/", "_")
                val ext = name.substringAfterLast('.', "mp4")
                val base = name.substringBeforeLast('.', name)
                val dir = File(cacheDir, "opt/${System.nanoTime()}").apply { mkdirs() }
                val tmp = File(dir, "in.$ext")
                try {
                    contentResolver.openInputStream(u)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                    val r = optimize(tmp)
                    if (r == null) {
                        val out = File(dir, "$base (optimized).$ext")
                        tmp.renameTo(out)
                        save(out)
                        ok++
                    } else errs.append(name).append(":\n").append(r).append("\n\n")
                } catch (e: Exception) {
                    errs.append(name).append(":\n").append(rawError(e)).append("\n\n")
                }
                dir.deleteRecursively()
            }
            if (errs.isNotEmpty()) checkErr = errs.toString().trim()
            runOnUiThread {
                val saved = "Saved as \"... (optimized)\" in your download folder; the originals are untouched."
                tell(when {
                    ok == uris.size -> "Optimized ${uris.size} video(s). $saved"
                    ok > 0 -> "Optimized $ok of ${uris.size} video(s). $saved Tap here for details on the others."
                    else -> "Could not optimize. Tap here for details."
                })
            }
        }
    }

    // ---------- downloading ----------
    private fun downloadSelected() {
        if (items.isEmpty()) { status.text = "Paste links above first"; return }
        if (!ready) { status.text = "The download engine is still starting - try again in a moment"; return }
        val sel = items.filter { it.check.isChecked }
        if (sel.isEmpty()) { tell("Tick the videos you want to download"); return }
        sel.forEach { startItem(it) }
        if (online() && !canDownload())
            status.text = "Waiting for Wi-Fi. Turn off \"Wi-Fi only\" in Settings to use mobile data."
    }

    private fun startItem(a: Item) {
        if (a.busy || a.done) return
        if (!ready) { setState(a, "The download engine is still starting - try again in a moment", C_WARN); return }
        a.err = ""
        val choice = choiceOf(a)
        val dir = dirFor(a, choice).apply { mkdirs() }
        if (!running.add(dir.name)) { setState(a, "Already running in the background", C_WARN); return }
        removeFromInput(a.url)
        a.runDir = dir
        a.busy = true
        a.cancelled = false
        a.paused = false
        setState(a, at(a) + "Queued...", C_PRIMARY)
        refreshButtons(a)
        refreshService()
        persist()
        downloader.execute { work(a, choice, dir) }
    }

    /** Pause keeps the partial file; Start continues from it. */
    private fun pauseItem(a: Item) {
        a.paused = true
        setState(a, "Pausing...")
        kill(a)
        refreshButtons(a)
    }

    /** Stop discards the partial files (of every resolution); Start then begins from zero. */
    private fun stopItem(a: Item) {
        if (a.done) return
        if (a.busy) {
            setState(a, "Stopping...")
            discard(a)
        } else {
            deletePartials(a)
            a.pct = -1
            a.bar.progress = 0
            setState(a, "Stopped")
        }
        refreshButtons(a)
    }

    private fun refreshButtons(a: Item) {
        if (a.done) {
            val has = a.files.isNotEmpty()
            a.toggle.text = "Open"; setIcon(a.toggle, G_OPEN, 16); style(a.toggle, FILLED); a.toggle.isEnabled = has
            styleRound(a.stopBtn, G_SHARE, "Share", TONAL); a.stopBtn.isEnabled = has
        } else {
            val stopping = a.busy && (a.paused || a.cancelled)
            a.toggle.text = when {
                stopping -> "Wait..."
                a.busy -> "Pause"
                else -> "Start"
            }
            setIcon(a.toggle, when {
                stopping -> 0
                a.busy -> G_PAUSE
                else -> G_PLAY
            }, 16)
            style(a.toggle, if (a.busy) TONAL else FILLED)
            a.toggle.isEnabled = !stopping
            styleRound(a.stopBtn, G_STOP, "Stop", DANGER)
            a.stopBtn.isEnabled = !(a.busy && a.cancelled)
        }
        a.spinner.isEnabled = !a.busy && !a.done
        a.spinner.alpha = if (a.spinner.isEnabled) 1f else 0.55f
        a.bar.progressTintList = ColorStateList.valueOf(if (a.done) C_OK else C_PRIMARY)
        a.doneBadge.visibility = if (a.done) View.VISIBLE else View.GONE
    }

    /** Keeps the background service (and its notification) running while any download is active. */
    private fun refreshService() {
        val n = items.count { it.busy }
        try {
            val i = Intent(this, DownloadService::class.java)
            if (n > 0) {
                if (!svcOn) { startForegroundService(i); svcOn = true }
                getSystemService(NotificationManager::class.java)
                    .notify(1, DownloadService.build(this, "$n download(s) running"))
            } else if (svcOn) {
                stopService(i)
                svcOn = false
            }
        } catch (_: Exception) {}
    }

    /** Sets an item's status line; tone colors it (muted, blue = active, amber = waiting, red = error, green = done). */
    private fun setState(a: Item, text: String, tone: Int = C_MUTED) {
        a.tone = toneKey(tone)
        a.state.text = text
        paintState(a)
    }

    /** Colors the status line and its leading mark: a dot, a check when done, an alert on errors. */
    private fun paintState(a: Item) {
        val c = toneColor(a.tone)
        a.state.setTextColor(c)
        val mark: Drawable = when (a.tone) {
            K_OK -> Glyph(G_CHECK, c, -1, 2.6f).apply { setBounds(0, 0, px(15), px(15)) }
            K_ERROR -> Glyph(G_ALERT, c).apply { setBounds(0, 0, px(15), px(15)) }
            else -> InsetDrawable(oval(c), px(4)).apply { setBounds(0, 0, px(15), px(15)) }
        }
        a.state.setCompoundDrawablesRelative(mark, null, null, null)
    }

    private fun ui(a: Item, text: String, tone: Int = C_MUTED) = runOnUiThread {
        if (a.pct >= 0) a.bar.progress = a.pct
        setState(a, text, tone)
    }

    private fun fmt(s: Long) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)

    private fun rateStr(b: Double) = when {
        b >= 1_000_000 -> "%.1f MB/s".format(b / 1_000_000)
        b >= 1_000 -> "%.0f KB/s".format(b / 1_000)
        else -> "%.0f B/s".format(b)
    }

    /** "45% - " when the progress is known, so it stays visible in every status message. */
    private fun at(a: Item) = if (a.pct >= 0) "${a.pct}% - " else ""

    private fun pausedText(a: Item) = at(a) + "Paused - tap Start to continue"

    private fun buildRequest(url: String, choice: Int, dir: File): YoutubeDLRequest {
        val r = YoutubeDLRequest(url)
        r.addOption("--no-playlist")
        r.addOption("--no-mtime")
        r.addOption("--continue")
        r.addOption("--retries", "3")
        r.addOption("--fragment-retries", "3")
        r.addOption("--retry-sleep", "2")
        r.addOption("--socket-timeout", "20")
        // Request big files in 10 MB pieces (servers throttle long single requests less) and fetch
        // 4 fragments at once for HLS/DASH streams.
        r.addOption("--http-chunk-size", "10M")
        r.addOption("--concurrent-fragments", "4")
        // Print progress in our own fixed format, one line each. The library's parser needs
        // "ETA mm:ss" and drops lines like "ETA Unknown" (normal right after a resume).
        r.addOption("--newline")
        r.addOption("--progress-template", "download:" + PROG +
            "%(progress.downloaded_bytes)s|%(progress.total_bytes)s|%(progress.total_bytes_estimate)s|" +
            "%(progress.speed)s|%(progress.eta)s|%(progress.fragment_index)s|%(progress.fragment_count)s")
        r.addOption("-o", dir.absolutePath + "/%(title).80s.%(ext)s")
        when (choice) {
            -1 -> { r.addOption("-x"); r.addOption("--audio-format", "mp3") }
            0 -> {
                r.addOption("-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b")
                r.addOption("--merge-output-format", "mp4")
            }
            else -> {
                r.addOption("-f", "bv*[height<=$choice][ext=mp4]+ba[ext=m4a]/bv*[height<=$choice]+ba/b[height<=$choice]")
                r.addOption("--merge-output-format", "mp4")
            }
        }
        return r
    }

    /** Handles one line of yt-dlp output; returns true when more bytes have arrived. */
    private fun onLine(a: Item, line: String): Boolean {
        if (a.paused || a.cancelled) { kill(a); return false }
        if (line.startsWith("[Merger]") || line.startsWith("[ExtractAudio]") ||
            line.startsWith("[VideoConvertor]") || line.startsWith("[Fixup")) {
            a.post = true
            ui(a, "Merging / converting...", C_PRIMARY)
            return false
        }
        val i = line.indexOf(PROG)
        if (i < 0) return false
        val p = line.substring(i + PROG.length).split('|')
        if (p.size < 7) return false
        fun num(k: Int) = p[k].trim().toDoubleOrNull()     // yt-dlp prints "NA" for unknown values
        val got = num(0)
        val total = num(1) ?: num(2)
        val speed = num(3)
        val eta = num(4)
        val fi = num(5)
        val fc = num(6)
        val pct = when {
            got != null && total != null && total > 0 -> got / total * 100
            fi != null && fc != null && fc > 0 -> fi / fc * 100
            else -> null
        }
        if (pct != null) a.pct = pct.toInt().coerceIn(0, 100)
        val now = System.currentTimeMillis()
        var moved = false
        if (got != null) {
            // A smaller count means the next stream (e.g. audio after video) started: measure it afresh.
            if (a.runBytes0 < 0 || got < a.lastBytes) { a.runBytes0 = got; a.runStart = now }
            moved = a.lastBytes >= 0 && got > a.lastBytes
            a.lastBytes = got
        }
        if (speed != null && speed > 0 && !a.afterNet && speed > a.peak) {
            a.peak = speed
            a.peakOnWifi = a.runOnWifi
        }
        checkSlow(a, now)
        if (now - a.lastUi >= 300 || (pct ?: 0.0) >= 100) {
            a.lastUi = now
            val parts = mutableListOf<String>()
            if (a.pct >= 0) parts.add("${a.pct}%")
            if (speed != null && speed > 0) parts.add(rateStr(speed)) else if (a.pct < 100) parts.add("connecting...")
            if (eta != null && eta >= 0) parts.add("ETA " + fmt(eta.toLong()))
            ui(a, parts.joinToString("  ·  "), C_PRIMARY)
        }
        return moved
    }

    /**
     * After an interruption a download sometimes comes back much slower than before (a stale connection
     * or a throttled server). If it stays under a third of its earlier speed for 20 s, start it again
     * from the partial file - at most twice per interruption.
     */
    private fun checkSlow(a: Item, now: Long) {
        if (!a.afterNet || a.post || a.runBytes0 < 0 || a.lastBytes < 0) return
        // Nothing to compare with, or a different kind of network (mobile data is often just slower).
        if (a.peak <= 0 || a.peakOnWifi != a.runOnWifi) { a.afterNet = false; return }
        val secs = (now - a.runStart) / 1000.0
        if (secs < 20) return
        val avg = (a.lastBytes - a.runBytes0) / secs
        if (avg >= a.peak / 3 || a.slowRestarts >= 2) { a.afterNet = false; return }
        a.slowRestarts++
        a.netKill = true
        kill(a)
    }

    private fun work(a: Item, choice: Int, dir: File) {
        var tries = 0
        try {
            while (true) {
                if (a.cancelled) return
                if (a.paused) { ui(a, pausedText(a)); return }
                if (!canDownload()) {
                    ui(a, at(a) + (if (online()) "Waiting for Wi-Fi - continues automatically"
                        else "Waiting for internet - continues automatically"), C_WARN)
                    while (!canDownload() && !a.cancelled && !a.paused) Thread.sleep(1500)
                    continue
                }
                try {
                    a.netKill = false
                    a.post = false
                    a.runOnWifi = onWifiNow()
                    a.runBytes0 = -1.0
                    a.lastBytes = -1.0
                    a.pid = dir.name + "-" + System.nanoTime()
                    ui(a, if (a.pct >= 0) "${a.pct}% - resuming..." else "Starting...", C_PRIMARY)
                    a.proc = true
                    try {
                        YoutubeDL.getInstance().execute(buildRequest(a.url, choice, dir), a.pid) { _, _, line ->
                            if (onLine(a, line)) tries = 0
                        }
                    } finally {
                        a.proc = false
                    }
                    // A killed process can still end "normally"; never treat its leftovers as a finished file.
                    if (a.netKill || a.paused || a.cancelled) throw Exception("interrupted")
                    val files = dir.listFiles().orEmpty()
                        .filter { !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.endsWith(".temp") }
                    if (files.isEmpty()) throw Exception("incomplete download")
                    // Optimizing is a bonus: if it fails the download is still saved, and the card says why.
                    var optErr = ""
                    if (prefs.getBoolean("optimize", true)) {
                        ui(a, "Optimizing video...", C_PRIMARY)
                        optErr = files.mapNotNull { optimize(it) }.filter { !it.startsWith(NOT_VIDEO) }
                            .joinToString("\n\n")
                    }
                    val saved = files.map { save(it) }
                    dir.deleteRecursively()
                    a.files.addAll(saved)
                    a.pct = 100
                    a.done = true
                    a.err = if (optErr.isEmpty()) "" else "Saved, but optimizing failed:\n\n$optErr"
                    runOnUiThread {
                        a.bar.progress = 100
                        if (optErr.isEmpty()) setState(a, "Done - saved", C_OK)
                        else setState(a, "Done - saved (not optimized - tap here for details)", C_WARN)
                        persist()
                        updateCounts()
                    }
                    return
                } catch (e: Exception) {
                    if (a.cancelled) return
                    if (a.paused) { ui(a, pausedText(a)); return }
                    if (a.netKill) continue     // stopped because the network changed: resume right away
                    val (msg, net) = why(e)
                    if (net && tries < 30) {
                        tries++
                        a.afterNet = true
                        a.slowRestarts = 0
                        ui(a, at(a) + "$msg - retrying...", C_WARN)
                        if (online()) Thread.sleep(3000)
                        continue
                    }
                    a.err = rawError(e)
                    ui(a, at(a) + "$msg. Tap Start to retry (progress is kept) or tap here for details.", C_DANGER)
                    return
                }
            }
        } finally {
            a.busy = false
            a.proc = false
            a.runDir = null
            running.remove(dir.name)
            if (a.cancelled) { dir.deleteRecursively(); a.pct = -1 }
            runOnUiThread {
                if (a.cancelled) { a.bar.progress = 0; setState(a, "Stopped") }
                refreshButtons(a)
                refreshService()
            }
        }
    }

    /** Copies a finished file to the chosen folder; returns its uri so it can be deleted later. */
    private fun save(f: File): String {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension) ?: "application/octet-stream"
        val tree = prefs.getString("tree", null)
        val uri: Uri = if (tree != null) {
            val t = Uri.parse(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
            DocumentsContract.createDocument(contentResolver, parent, mime, f.name)
                ?: throw SecurityException("cannot create file")
        } else {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/VidGrab")
            }
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw SecurityException("cannot create file")
        }
        contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
        return uri.toString()
    }

    // ---------- test hooks ----------
    // UI TEST CONTRACT: the end-to-end tests in app/src/androidTest drive the app ONLY through these
    // functions (plus the public fields of Item: url, title, labels, check, spinner, state, done, files, err).
    // A UI redesign may change any view, but must keep these names and what they do.
    // Must be called on the main thread (tests use ActivityScenario.onActivity).
    @Volatile internal var autoConfirm = false        // confirm() answers "Yes" without showing a dialog
    internal fun testReady() = ready
    internal fun testAddLinks(text: String) { input.append(text + "\n") }
    internal fun testItems(): List<Item> = items.toList()
    internal fun testStatus() = status.text.toString()
    internal fun testCheckErr() = checkErr
    internal fun testState(a: Item) = a.state.text.toString()
    internal fun testSelect(a: Item, label: String) { a.spinner.setSelection(a.labels.indexOf(label)) }
    internal fun testDownloadTicked() = downloadSelected()
    internal fun testOptimizeTicked() = optimizeFiles()
    internal fun testDeleteTicked() = deleteTicked()
    internal fun testRemoveUnfinished() = removeUnfinished()
    internal fun testRemoveAll() = removeAll()

    // ---------- engine update ----------
    private fun update() {
        if (!online()) { status.text = "No internet connection"; return }
        if (updating || items.any { it.busy }) return
        updating = true
        status.text = "Updating engine..."
        Thread {
            try {
                val r = YoutubeDL.getInstance().updateYoutubeDL(application, YoutubeDL.UpdateChannel.NIGHTLY)
                val v = YoutubeDL.getInstance().versionName(application)
                prefs.edit().putLong("engineChecked", System.currentTimeMillis()).apply()
                runOnUiThread {
                    status.text = if (r == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE)
                        "Already up to date ($v)" else "Updated to $v"
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Update failed: " + why(e).first }
            } finally {
                updating = false
                runOnUiThread { refreshChip() }
            }
        }.start()
    }
}
