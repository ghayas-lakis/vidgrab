package io.github.ghayaslakis.vidgrab

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import com.yausername.youtubedl_android.YoutubeDL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Recognizes links to single videos on the sites VidGrab knows, and writes them in one form, so the same
 * video found twice (another address form, tracking parameters) is listed once.
 */
internal object VideoLinks {
    private class Rule(val re: Regex, val make: (MatchResult) -> String)

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val rules = listOf(
        Rule(rx("^https?://(?:www\\.|m\\.)?tiktok\\.com/@([\\w.-]+)/video/(\\d+)")) {
            "https://www.tiktok.com/@${it.groupValues[1]}/video/${it.groupValues[2]}" },
        Rule(rx("^https?://(?:www\\.|touch\\.|m\\.)?dailymotion\\.com/(?:embed/)?video/([a-z0-9]+)")) {
            "https://www.dailymotion.com/video/${it.groupValues[1]}" },
        Rule(rx("^https?://dai\\.ly/([a-z0-9]+)")) { "https://www.dailymotion.com/video/${it.groupValues[1]}" },
        Rule(rx("^https?://(?:www\\.|m\\.)?youtube\\.com/watch\\?(?:[^#]*&)?v=([\\w-]{11})")) {
            "https://www.youtube.com/watch?v=${it.groupValues[1]}" },
        Rule(rx("^https?://(?:www\\.|m\\.)?youtube\\.com/shorts/([\\w-]{11})")) {
            "https://www.youtube.com/shorts/${it.groupValues[1]}" },
        Rule(rx("^https?://youtu\\.be/([\\w-]{11})")) { "https://www.youtube.com/watch?v=${it.groupValues[1]}" },
        Rule(rx("^https?://(?:www\\.)?instagram\\.com/(?:[\\w.]+/)?(reels?|tv)/([\\w-]+)")) {
            "https://www.instagram.com/reel/${it.groupValues[2]}/" },
        Rule(rx("^https?://(?:www\\.|m\\.|web\\.)?facebook\\.com/(?:watch/?\\?(?:[^#]*&)?v=|reel/|[\\w.]+/videos/(?:[\\w.-]+/)?)(\\d+)")) {
            "https://www.facebook.com/watch/?v=${it.groupValues[1]}" },
        Rule(rx("^https?://(?:www\\.|mobile\\.|m\\.)?(?:twitter|x)\\.com/(\\w+)/status/(\\d+)")) {
            "https://x.com/${it.groupValues[1]}/status/${it.groupValues[2]}" },
        Rule(rx("^https?://(?:www\\.|player\\.)?vimeo\\.com/(?:video/)?(\\d+)")) { "https://vimeo.com/${it.groupValues[1]}" },
        Rule(rx("^https?://(?:www\\.|m\\.)?twitch\\.tv/videos/(\\d+)")) { "https://www.twitch.tv/videos/${it.groupValues[1]}" },
        Rule(rx("^https?://(?:www\\.|m\\.)?bilibili\\.com/video/(\\w+)")) { "https://www.bilibili.com/video/${it.groupValues[1]}" },
    )

    /** The video link in its one form, or null when it isn't a link to a single video on a known site. */
    fun canonical(url: String): String? {
        for (r in rules) { val m = r.re.find(url) ?: continue; return r.make(m) }
        return null
    }

    /** The account that posted it, where the link says (TikTok, X). */
    fun author(url: String): String =
        Regex("tiktok\\.com/@([\\w.-]+)/").find(url)?.groupValues?.get(1)
            ?: Regex("x\\.com/(\\w+)/status/").find(url)?.groupValues?.get(1) ?: ""

    private val KNOWN = listOf("tiktok.com", "youtube.com", "youtu.be", "dailymotion.com", "dai.ly", "instagram.com",
        "facebook.com", "fb.watch", "x.com", "twitter.com", "vimeo.com", "twitch.tv", "bilibili.com")

    /** Sites whose videos are found by their links (their raw streams would just be noise, ads included). */
    fun knownSite(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return KNOWN.any { h == it || h.endsWith(".$it") }
    }

    fun site(url: String): String = try {
        (Uri.parse(url).host ?: "").lowercase().removePrefix("www.").removePrefix("m.")
    } catch (_: Exception) { "" }
}

/**
 * A small private browser. Every video you watch (and every video stream or video link on the page) is
 * collected; the round button opens the list, where you tick what to download, and whether to merge.
 *
 * Nothing is kept: no history, cookies, cache, site storage or form data survive closing it (they are also
 * wiped when it opens, in case VidGrab was killed while it was open). The keyboard is asked not to learn
 * what is typed in the address bar.
 */
class BrowserActivity : Activity() {
    private class Found(val url: String, var title: String, val author: String, val kind: Int, val page: String) {
        var ad = false
        var tick: Boolean? = null       // set by the user; null = the default (see ticked())
        var sent = false                // already sent to the download list
        val isShort = "youtube.com/shorts/" in url      // at most 3 minutes long
    }

    /** What is known about a video's length and size (0 = not known). sure = stated by the site. */
    private class Info { var sec = 0; var sure = false; var bytes = 0L }

    /** A row of the list that does something when tapped. */
    private class RowAction(val text: String, val run: () -> Unit)

    private companion object {
        const val F_SEEN = 0            // a video page you watched (a site VidGrab knows)
        const val F_PAGE = 1            // a page of another site that shows a video
        const val F_STREAM = 2          // a direct video file or stream
        const val F_LINK = 3            // a link to a video on the current page
        const val MAX_STREAMS = 15      // per page
        const val HOME = "about:blank"
        const val MAX_CHECK = 60        // lengths asked from yt-dlp per tap
        const val MAX_PAGES = 60        // YouTube pages read for their video's length, per page visited (0.13 MB each)
        val LENGTHS = listOf(0 to 0, 0 to 60, 60 to 0, 180 to 0, 300 to 0, 600 to 0, 1200 to 0, 2400 to 0, 3600 to 0)
        val SIZES = listOf(0, 5, 10, 25, 50, 100, 500)
    }

    private lateinit var pal: Palette
    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var backBtn: ImageButton
    private lateinit var fwdBtn: ImageButton
    private lateinit var reloadBtn: ImageButton
    private lateinit var loading: ProgressBar
    private lateinit var fab: TextView
    private lateinit var fullBox: FrameLayout
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    private var dp = 1f
    private var script = ""
    private var mobileUa = ""
    private var desktop = false
    private var isLoading = false
    @Volatile private var curPage = ""

    // Watched pages, page videos and streams: kept (in order) until "Clear". Links: the current page's only.
    private val found = LinkedHashMap<String, Found>()
    private val links = LinkedHashMap<String, Found>()
    private var lastCount = 0
    private var sheetRefresh: (() -> Unit)? = null

    // Lengths and sizes, and the filter on them (kept between launches; 0 = no limit)
    private val infos = HashMap<String, Info>()
    private val streamParts = HashSet<String>()     // the qualities of a stream that is listed itself
    private val looked = HashSet<String>()          // asked the site
    private val checked = HashSet<String>()         // asked yt-dlp
    private val unanswered = HashSet<String>()      // ... and waiting for its answer
    private val looker = Executors.newFixedThreadPool(3)
    private val checker = Executors.newFixedThreadPool(2)
    private val checkIds = ConcurrentHashMap.newKeySet<String>()    // yt-dlp processes running
    @Volatile private var checkRun = 0
    private val checking get() = unanswered.size
    private var pagesRead = 0
    private var minSec = 0
    private var maxSec = 0
    private var minMb = 0
    private val prefs by lazy { getSharedPreferences("vidgrab", MODE_PRIVATE) }

    private fun px(v: Int) = (v * dp).toInt()
    private fun pxf(v: Float) = v * dp
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun rounded(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = pxf(r.toFloat())
        if (stroke != 0) setStroke(maxOf(1, px(1)), stroke)
    }

    private fun ripple(content: Drawable?, mask: Drawable?) = RippleDrawable(ColorStateList.valueOf(pal.ripple), content, mask)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun iconBtn(icon: Int, name: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageDrawable(Glyph(icon, pal.text, px(20)))
        imageTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(pal.disabledFg, pal.text))
        scaleType = ImageView.ScaleType.CENTER
        background = ripple(null, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
        contentDescription = name
        tooltipText = name
        setOnClickListener { onClick() }
    }

    override fun onCreate(s: Bundle?) {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        pal = if (night) DARK else LIGHT
        setTheme(if (pal.dark) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        super.onCreate(s)
        dp = resources.displayMetrics.density
        minSec = prefs.getInt("browserMinSec", 0)
        maxSec = prefs.getInt("browserMaxSec", 0)
        minMb = prefs.getInt("browserMinMb", 0)
        script = try { assets.open("finder.js").bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
        buildUi()
        setupWeb()
        erase()                          // anything left from a session that ended without closing
        applyProxy {
            val start = intent.getStringExtra("url")
            if (start != null && start.startsWith("http")) web.loadUrl(start) else showHome()
        }
    }

    // ---------- screen ----------
    @Suppress("DEPRECATION")
    private fun buildUi() {
        window.statusBarColor = pal.surface
        window.navigationBarColor = pal.surface
        val d = window.decorView
        val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        d.systemUiVisibility = if (pal.dark) d.systemUiVisibility and light.inv() else d.systemUiVisibility or light

        backBtn = iconBtn(G_BACK, "Back") { if (web.canGoBack()) web.goBack() }
        fwdBtn = iconBtn(G_FORWARD, "Forward") { if (web.canGoForward()) web.goForward() }
        reloadBtn = iconBtn(G_REFRESH, "Reload") { if (isLoading) web.stopLoading() else web.reload() }
        val menuBtn = iconBtn(G_MORE, "Menu") {}
        menuBtn.setOnClickListener { showMenu(menuBtn) }
        address = EditText(this).apply {
            hint = "Search or type a web address"
            setSingleLine()
            textSize = T_BODY
            setTextColor(pal.text)
            setHintTextColor(pal.subtle)
            highlightColor = Color.argb(77, Color.red(pal.accent), Color.green(pal.accent), Color.blue(pal.accent))
            // private: no suggestions, and the keyboard is asked not to learn what is typed here
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            background = rounded(pal.surface2, R_PILL)
            setPadding(px(14), 0, px(14), 0)
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { go(text.toString()); true } else false
            }
            setOnFocusChangeListener { _, has -> if (!has) showAddress(web.url) }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(pal.surface)
            setPadding(px(S1), px(6), px(S1), px(6))
        }
        bar.addView(backBtn, LinearLayout.LayoutParams(px(40), px(40)))
        bar.addView(fwdBtn, LinearLayout.LayoutParams(px(40), px(40)))
        bar.addView(address, LinearLayout.LayoutParams(0, px(40), 1f).apply { marginStart = px(S1); marginEnd = px(S1) })
        bar.addView(reloadBtn, LinearLayout.LayoutParams(px(40), px(40)))
        bar.addView(menuBtn, LinearLayout.LayoutParams(px(40), px(40)))

        loading = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(pal.accent)
            progressBackgroundTintList = ColorStateList.valueOf(pal.surface)
            visibility = View.INVISIBLE
        }

        web = WebView(this)
        fab = TextView(this).apply {
            textSize = T_BODY
            typeface = MEDIUM
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val ic = Glyph(G_DOWNLOAD, Color.WHITE)
            ic.setBounds(0, 0, px(20), px(20))
            setCompoundDrawablesRelative(ic, null, null, null)
            compoundDrawablePadding = px(8)
            setPadding(px(16), 0, px(20), 0)
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(56, 255, 255, 255)), rounded(pal.fill, R_PILL), null)
            elevation = pxf(8f)
            visibility = View.GONE
            setOnClickListener { showSheet() }
        }
        fullBox = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        column.addView(View(this).apply { setBackgroundColor(pal.outline) }, LinearLayout.LayoutParams(MATCH, 1))
        column.addView(loading, LinearLayout.LayoutParams(MATCH, px(3)))
        column.addView(web, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val root = FrameLayout(this)
        root.setBackgroundColor(pal.bg)
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(fab, FrameLayout.LayoutParams(WRAP, px(50), Gravity.BOTTOM or Gravity.END)
            .apply { setMargins(0, 0, px(S4), px(S5 + S1)) })
        root.addView(fullBox, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        refreshNav()
    }

    private fun showAddress(url: String?) {
        if (address.hasFocus()) return
        address.setText(if (url == null || url == HOME || url.startsWith("data:")) "" else url)
    }

    private fun refreshNav() {
        backBtn.isEnabled = web.canGoBack()
        fwdBtn.isEnabled = web.canGoForward()
        reloadBtn.setImageDrawable(Glyph(if (isLoading) G_CLOSE else G_REFRESH, pal.text, px(20)))
        reloadBtn.contentDescription = if (isLoading) "Stop" else "Reload"
    }

    /** An address, or else a search (DuckDuckGo, which keeps no search history). */
    private fun go(t: String) {
        val q = t.trim()
        if (q.isEmpty()) return
        val url = when {
            Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(q) -> q
            !q.contains(' ') && Regex("^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/.*)?$").matches(q) -> "https://$q"
            else -> "https://duckduckgo.com/?q=" + URLEncoder.encode(q, "UTF-8")
        }
        address.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(address.windowToken, 0)
        web.loadUrl(url)
    }

    /** The start page: a few sites, in VidGrab's colors. */
    private fun showHome() {
        fun c(x: Int) = String.format("#%06X", 0xFFFFFF and x)
        val sites = listOf("TikTok" to "https://www.tiktok.com/", "Dailymotion" to "https://www.dailymotion.com/",
            "YouTube" to "https://m.youtube.com/", "Instagram" to "https://www.instagram.com/",
            "Facebook" to "https://m.facebook.com/watch/", "X" to "https://x.com/", "Vimeo" to "https://vimeo.com/",
            "Twitch" to "https://m.twitch.tv/")
        val tiles = sites.joinToString("") { (n, u) -> "<a href=\"$u\"><b>${n.take(1)}</b><span>$n</span></a>" }
        val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body{margin:0;padding:28px 18px;background:${c(pal.bg)};color:${c(pal.text)};font-family:sans-serif}
h1{font-size:22px;margin:8px 4px 6px}p{color:${c(pal.muted)};font-size:14px;line-height:1.45;margin:0 4px 22px}
.g{display:grid;grid-template-columns:repeat(4,1fr);gap:12px}
a{display:flex;flex-direction:column;align-items:center;gap:7px;text-decoration:none;color:${c(pal.text)};font-size:12.5px}
b{display:flex;align-items:center;justify-content:center;width:54px;height:54px;border-radius:16px;
background:${c(pal.accentSoft)};color:${c(pal.onAccentSoft)};font-size:21px}
.n{margin:26px 4px 0;padding:14px;border-radius:14px;background:${c(pal.surface)};border:1px solid ${c(pal.outline)};
color:${c(pal.muted)};font-size:13px;line-height:1.5}
</style></head><body><h1>Private browsing</h1>
<p>Open a video and the download button appears. Swipe through a series and every video you watch is collected, in order.</p>
<div class="g">$tiles</div>
<div class="n">Nothing is kept: history, searches, logins and site data are erased when you close the browser.</div>
</body></html>"""
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Downloads (VidGrab's list)")
        m.menu.add(0, 2, 1, "Desktop site").apply { isCheckable = true; isChecked = desktop }
        m.menu.add(0, 3, 2, "Clear videos found").isEnabled = found.isNotEmpty() || links.isNotEmpty()
        m.menu.add(0, 4, 3, "Start page")
        m.menu.add(0, 5, 4, "Close and erase browsing")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                2 -> setDesktop(!desktop)
                3 -> clearFound()
                4 -> showHome()
                5 -> finish()
            }
            true
        }
        m.show()
    }

    private fun setDesktop(on: Boolean) {
        desktop = on
        val ver = Regex("Chrome/([\\d.]+)").find(mobileUa)?.groupValues?.get(1) ?: "120.0.0.0"
        web.settings.userAgentString = if (on)
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$ver Safari/537.36"
            else mobileUa
        web.reload()
    }

    // ---------- the web view ----------
    @Suppress("SetJavaScriptEnabled")
    private fun setupWeb() {
        val s = web.settings
        s.javaScriptEnabled = true                  // video sites need it
        s.domStorageEnabled = true                  // erased when the browser closes
        s.setSupportMultipleWindows(false)          // "open in a new tab" opens here
        s.javaScriptCanOpenWindowsAutomatically = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setGeolocationEnabled(false)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        // Look like the phone's own browser: some sites refuse "embedded" browsers.
        mobileUa = s.userAgentString.replace("; wv", "").replace(Regex("Version/[\\d.]+ "), "")
        s.userAgentString = mobileUa
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)   // fewer trackers
        web.isSaveEnabled = false
        web.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        web.addJavascriptInterface(Bridge(), "VidGrabFinder")
        web.setBackgroundColor(pal.bg)
        // A link to a file: a video is added to the list (and the list opens); anything else is ignored.
        web.setDownloadListener { url, _, _, mime, _ ->
            if ((mime ?: "").startsWith("video/") || Regex("\\.(mp4|webm|mkv|mov|m4v|m3u8)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
                if (addStream(url, web.url ?: url)) refreshFab()
                showSheet()
            } else toast("VidGrab's browser only downloads videos.")
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                return when (u.scheme?.lowercase()) {
                    "http", "https" -> false
                    "intent" -> {
                        // "open in the app" links: follow the web address they carry, never open an app
                        val fb = try {
                            Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
                        } catch (_: Exception) { null }
                        if (fb != null && fb.startsWith("http")) view.loadUrl(fb)
                        true
                    }
                    else -> true                    // app links (snssdk..., market:, ...) are ignored
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                curPage = url
                pagesRead = 0
                isLoading = true
                loading.visibility = View.VISIBLE
                showAddress(url)
                refreshNav()
                // the previous page's links no longer apply
                if (links.isNotEmpty()) { links.clear(); refreshFab() }
            }

            override fun onPageFinished(view: WebView, url: String) {
                isLoading = false
                loading.visibility = View.INVISIBLE
                inject()
                refreshNav()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                // also called when a site changes its address without loading a page (TikTok's swiping)
                curPage = url
                showAddress(url)
                refreshNav()
                // a fast swipe is over before the finder looks: the address is enough to list the video
                VideoLinks.canonical(url)?.let { if (addFound(it, "", F_SEEN, url, false)) refreshFab() }
            }

            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                // Video files and streams that the page loads (only on sites VidGrab doesn't know)
                if (req.isForMainFrame) return null
                val u = req.url
                val page = curPage
                if (VideoLinks.knownSite(Uri.parse(page).host)) return null
                val path = (u.path ?: "").lowercase()
                if (Regex("\\.(mp4|webm|m3u8|mpd|mov|m4v)$").containsMatchIn(path)) {
                    val link = u.toString()
                    runOnUiThread { if (addStream(link, page)) refreshFab() }
                }
                return null
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Without this, a crashed page would close all of VidGrab (downloads included).
                toast("The page stopped working, so the browser was closed.")
                finish()
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                loading.progress = p
                if (p >= 20) inject()
            }

            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                // full-screen video
                if (fullView != null) { callback.onCustomViewHidden(); return }
                fullView = view
                fullCallback = callback
                fullBox.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
                fullBox.visibility = View.VISIBLE
                fab.visibility = View.GONE
                if (android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.hide(WindowInsets.Type.systemBars())
            }

            override fun onHideCustomView() {
                fullView?.let { fullBox.removeView(it) }
                fullView = null
                fullCallback = null
                fullBox.visibility = View.GONE
                if (android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.show(WindowInsets.Type.systemBars())
                refreshFab()
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
            }
        }
    }

    /** Runs the video finder in the page (it starts only once per page). */
    private fun inject() {
        if (script.isNotEmpty()) try { web.evaluateJavascript(script, null) } catch (_: Exception) {}
    }

    /** What the finder reports (it calls in on a background thread). */
    inner class Bridge {
        @JavascriptInterface
        fun report(json: String) {
            if (json.length > 400_000) return
            runOnUiThread { onReport(json) }
        }
    }

    private fun cleanTitle(t: String) = t.replace(Regex("\\s*[|\\-–]\\s*(TikTok|YouTube|Dailymotion|Vidéo Dailymotion|" +
        "Video Dailymotion|Instagram|Facebook|X|Twitter|Vimeo|Twitch)\\s*$", RegexOption.IGNORE_CASE), "").trim()
        .let { if (it.equals("TikTok", true) || it.startsWith("TikTok - ", true) || it == "YouTube") "" else it }

    private fun onReport(json: String) {
        if (isFinishing) return
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        val page = o.optString("page")
        val cur = web.url ?: return
        // only the page on display can report (not a frame of another site inside it)
        if (Uri.parse(page).host == null || Uri.parse(page).host != Uri.parse(cur).host) return
        val title = cleanTitle(o.optString("title"))
        val ad = o.optBoolean("ad")
        var changed = false
        // 1. the page itself is a video: you are watching it
        val pageVideo = VideoLinks.canonical(page)
        if (pageVideo != null) {
            changed = addFound(pageVideo, title, F_SEEN, page, ad) or changed
            found[pageVideo]?.let { f ->
                if (title.isNotEmpty() && f.title != title && f.kind == F_SEEN) { f.title = title; changed = true }
                if (o.optBoolean("settled") && f.ad != ad) { f.ad = ad; changed = true }
            }
            changed = learn(pageVideo, o.optInt("pageDur"), o.optLong("pageSize"), true) or changed
            changed = learn(pageVideo, o.optInt("dur"), 0L, false) or changed
        }
        // 2. the big video on screen has its own link (feeds whose address doesn't change)
        val near = o.optString("near").takeIf { it.isNotEmpty() }?.let { VideoLinks.canonical(it) }
        if (near != null) {
            if (near != pageVideo) changed = addFound(near, "", F_SEEN, page, ad) or changed
            changed = learn(near, o.optInt("nearDur"), o.optLong("nearSize"), true) or changed
            if (pageVideo == null) changed = learn(near, o.optInt("dur"), 0L, false) or changed
        }
        // 3. another site showing a video: the page (yt-dlp knows many sites) and its video sources
        if (!VideoLinks.knownSite(Uri.parse(page).host) && o.optBoolean("video")) {
            if (pageVideo == null) {
                val u = page.substringBefore('#')
                changed = addFound(u, title, F_PAGE, page, false) or changed
                changed = learn(u, o.optInt("metaDur"), 0L, true) or changed
                // an ad before the video can play in the same place: the longest one is the video
                if (o.optInt("dur") > (infos[u]?.sec ?: 0)) changed = learn(u, o.optInt("dur"), 0L, false) or changed
            }
            val media = o.optJSONArray("media")
            for (k in 0 until (media?.length() ?: 0)) changed = addStream(media!!.optString(k), page) or changed
        }
        // 4. links to videos on this page
        val ls = o.optJSONArray("links")
        val now = LinkedHashMap<String, Found>()
        for (k in 0 until (ls?.length() ?: 0)) {
            val e = ls!!.optJSONArray(k) ?: continue
            val c = VideoLinks.canonical(e.optString(0)) ?: continue
            changed = learn(c, e.optInt(2), e.optLong(3), true) or changed
            val t = e.optString(1).trim()
            val f = found[c] ?: now[c] ?: links[c] ?: Found(c, t, VideoLinks.author(c), F_LINK, page)
            if (t.isNotEmpty() && f.title.isBlank()) { f.title = t; changed = true }
            if (c !in found) now[c] = f
        }
        if (now.keys != links.keys) {
            links.clear()
            links.putAll(now)
            changed = true
        }
        if (changed) refreshFab()
    }

    private fun addFound(url: String, title: String, kind: Int, page: String, ad: Boolean): Boolean {
        if (url in found || found.size >= 500) return false
        val f = Found(url, title, VideoLinks.author(url), kind, page)
        f.ad = ad
        links.remove(url)
        found[url] = f
        return true
    }

    private fun addStream(url: String, page: String): Boolean {
        if (!url.startsWith("http") || url in found || url in streamParts) return false
        if (found.values.count { it.kind == F_STREAM && it.page == page } >= MAX_STREAMS) return false
        val name = (Uri.parse(url).lastPathSegment ?: "video").take(48)
        found[url] = Found(url, "Video stream: $name", "", F_STREAM, page)
        return true
    }

    private fun clearFound() {
        found.clear()
        links.clear()
        infos.clear()
        looked.clear()
        checked.clear()
        stopChecking()
        lastCount = 0
        refreshFab()
        toast("The list of videos found is empty. New videos you watch are added again.")
    }

    /** The round button: how many videos were found. It pops when a new one is added. */
    private fun refreshFab() {
        lookUp()
        sheetRefresh?.invoke()
        val n = found.values.count { it.url !in streamParts && fits(it) == true }
        val l = links.values.count { fits(it) == true }
        if (fullView != null || (found.isEmpty() && links.isEmpty())) { fab.visibility = View.GONE; lastCount = n; return }
        val unknown = if (n + l > 0) 0 else (found.values + links.values).count { it.url !in streamParts && fits(it) == null }
        fab.text = when {
            n > 0 -> "$n video${if (n == 1) "" else "s"}"
            l > 0 -> "$l ${if (filterOn()) "" else "video "}link${if (l == 1) "" else "s"}"
            unknown > 0 -> "$unknown to check"
            else -> "No videos"
        } + if (filterOn()) "  ·  " + filterText() else ""
        if (fab.visibility != View.VISIBLE) {
            fab.visibility = View.VISIBLE
            fab.alpha = 0f
            fab.translationY = pxf(24f)
            fab.animate().alpha(1f).translationY(0f).setDuration(220).start()
        } else if (n > lastCount) {
            fab.animate().scaleX(1.12f).scaleY(1.12f).setDuration(110).withEndAction {
                fab.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }.start()
        }
        lastCount = n
    }

    // ---------- length and size ----------
    private fun filterOn() = minSec > 0 || maxSec > 0 || minMb > 0

    private fun mins(s: Int) = if (s % 60 == 0) "${s / 60} min" else Lengths.clock(s)

    private fun lengthText() = when {
        minSec > 0 -> mins(minSec) + " +"
        maxSec > 0 -> "Shorts"
        else -> "Any length"
    }

    private fun sizeText() = if (minMb > 0) "$minMb MB +" else "Any size"

    private fun filterText() = listOf(if (minSec > 0 || maxSec > 0) lengthText() else "", if (minMb > 0) sizeText() else "")
        .filter { it.isNotEmpty() }.joinToString(", ")

    /** Does the video pass the filter? null = its length (or size) is not known. */
    private fun fits(f: Found): Boolean? {
        if (!filterOn() || f.sent) return true
        val i = infos[f.url]
        var known = false
        if (minSec > 0 || maxSec > 0) {
            val s = i?.sec ?: 0
            if (s > 0) {
                if (s < minSec || (maxSec in 1 until s && !f.isShort)) return false
                known = true
            } else if (f.isShort) {
                if (minSec >= 180) return false
                if (maxSec > 0) known = true        // asked for Shorts
            }
        }
        if (minMb > 0 && i != null && i.bytes > 0) {
            if (i.bytes < minMb * 1_000_000L) return false
            known = true
        }
        return if (known) true else null
    }

    /** Remembers a video's length and size. Returns whether that is news. */
    private fun learn(url: String, sec: Int, bytes: Long, sure: Boolean): Boolean {
        if (sec <= 0 && bytes <= 0) return false
        val i = infos.getOrPut(url) { Info() }
        var changed = false
        if (sec > 0 && (sure || !i.sure)) {
            if (i.sec != sec) { i.sec = sec; changed = true }
            if (sure) i.sure = true
        }
        if (bytes > 0 && bytes != i.bytes) { i.bytes = bytes; changed = true }
        return changed
    }

    private fun lengths() = Lengths(web.settings.userAgentString ?: mobileUa, prefs.getString("proxy", "") ?: "")

    /**
     * Asks the sites for lengths the page didn't show, where that is quick: streams, Dailymotion, Vimeo and
     * YouTube. Only when needed: a filter is set, or the list is open.
     */
    private fun lookUp() {
        if (isFinishing || (!filterOn() && sheetRefresh == null)) return
        val todo = (found.values + links.values).filter {
            !it.sent && it.url !in looked && (infos[it.url]?.sec ?: 0) == 0 && it.url !in streamParts
        }
        if (todo.isEmpty()) return
        val lens = lengths()
        fun later(work: () -> (() -> Boolean)) = try {
            looker.execute {
                val apply = work()
                runOnUiThread { if (!isFinishing && !isDestroyed && apply()) refreshFab() }
            }
        } catch (_: Exception) {}
        val dm = todo.filter { "dailymotion.com/video/" in it.url }.map { it.url }
        for (group in dm.chunked(50)) {
            looked.addAll(group)
            later {
                val r = lens.dailymotion(group.map { it.substringAfterLast('/') });
                { group.map { learn(it, r[it.substringAfterLast('/')] ?: 0, 0L, true) }.any { it } }
            }
        }
        for (f in todo) {
            val u = f.url
            when {
                f.kind == F_STREAM -> {
                    looked.add(u)
                    val cookie = try { CookieManager.getInstance().getCookie(u) } catch (_: Exception) { null }
                    later {
                        val r = lens.stream(u, f.page, cookie);
                        {
                            // the stream's separate qualities are the same video: listed once
                            val parts = (r?.parts ?: emptyList()).filter { it != u }
                            val more = streamParts.addAll(parts)
                            learn(u, r?.sec ?: 0, r?.bytes ?: 0L, true) or more
                        }
                    }
                }
                u.startsWith("https://vimeo.com/") -> {
                    looked.add(u)
                    later { val s = lens.vimeo(u); { learn(u, s, 0L, true) } }
                }
                // a whole page per video, so only when the filter needs it
                u.startsWith("https://www.youtube.com/") && (minSec > 0 || maxSec > 0) && fits(f) == null &&
                    pagesRead < MAX_PAGES -> {
                    pagesRead++
                    looked.add(u)
                    later { val s = lens.youtube(u); { learn(u, s, 0L, true) } }
                }
            }
        }
    }

    /** Asks yt-dlp for the lengths that couldn't be found any other way (slow, so only when asked to). */
    private fun check(list: List<Found>) {
        if (!MainActivity.engineReady()) { toast("The download engine is still starting. Try again in a moment."); return }
        val lens = lengths()
        val run = checkRun
        for (f in list.take(MAX_CHECK)) {
            if (!checked.add(f.url)) continue
            unanswered.add(f.url)
            checker.execute {
                var r: VideoInfo? = null
                if (run == checkRun) {
                    val jar = Cookies.forRun(this)
                    val id = "len" + System.nanoTime()
                    checkIds.add(id)
                    r = lens.engine(f.url, jar, id)
                    checkIds.remove(id)
                    jar?.delete()
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed || run != checkRun) return@runOnUiThread
                    unanswered.remove(f.url)
                    learn(f.url, r?.sec ?: 0, r?.bytes ?: 0L, true)
                    refreshFab()
                }
            }
        }
        sheetRefresh?.invoke()
    }

    private fun stopChecking() {
        checkRun++
        for (id in checkIds.toList()) try { YoutubeDL.getInstance().destroyProcessById(id) } catch (_: Exception) {}
        checked.removeAll(unanswered)               // these can be asked again
        unanswered.clear()
    }

    private fun setFilter(min: Int = minSec, max: Int = maxSec, mb: Int = minMb) {
        minSec = min; maxSec = max; minMb = mb
        stopChecking()
        prefs.edit().putInt("browserMinSec", min).putInt("browserMaxSec", max).putInt("browserMinMb", mb).apply()
        refreshFab()
    }

    private fun dialogTheme() = if (pal.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    private fun askNumber(title: String, done: (Int) -> Unit) {
        val e = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(); hint = "0" }
        val box = FrameLayout(this).apply { setPadding(px(S5 + S1), px(S2), px(S5 + S1), 0); addView(e) }
        AlertDialog.Builder(this, dialogTheme()).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ -> e.text.toString().toIntOrNull()?.let { done(minOf(it, 100_000)) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseLength() {
        val names = LENGTHS.map { (lo, hi) ->
            when { lo == 3600 -> "1 hour and longer"; lo > 0 -> "${lo / 60} min and longer"
                hi > 0 -> "Shorts only (or up to ${hi / 60} min)"; else -> "Any length" }
        } + "Other..."
        AlertDialog.Builder(this, dialogTheme()).setTitle("Show videos of this length")
            .setSingleChoiceItems(names.toTypedArray(), LENGTHS.indexOf(minSec to maxSec).let { if (it < 0) LENGTHS.size else it }) { d, i ->
                d.dismiss()
                if (i < LENGTHS.size) setFilter(min = LENGTHS[i].first, max = LENGTHS[i].second)
                else askNumber("At least how many minutes?") { setFilter(min = it * 60, max = 0) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseSize() {
        val names = SIZES.map { if (it == 0) "Any size" else "$it MB and bigger" } + "Other..."
        AlertDialog.Builder(this, dialogTheme()).setTitle("Show videos of this size")
            .setSingleChoiceItems(names.toTypedArray(), SIZES.indexOf(minMb).let { if (it < 0) SIZES.size else it }) { d, i ->
                d.dismiss()
                if (i < SIZES.size) setFilter(mb = SIZES[i])
                else askNumber("At least how many MB?") { setFilter(mb = it) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- the list of videos found ----------
    /** The account most watched videos are from, when there clearly is one (a series): others may be ads. */
    private fun mainAuthor(): String? {
        val counts = found.values.filter { it.kind == F_SEEN && it.author.isNotEmpty() && !it.ad }
            .groupingBy { it.author }.eachCount()
        val total = counts.values.sum()
        val top = counts.maxByOrNull { it.value } ?: return null
        return if (total >= 3 && top.value * 10 >= total * 6) top.key else null
    }

    private fun otherAccount(f: Found, main: String?) =
        main != null && f.kind == F_SEEN && f.author.isNotEmpty() && f.author != main

    /** Ticked by default: watched videos except ads, page videos, streams of pages without one. Not links. */
    private fun ticked(f: Found, main: String?): Boolean {
        if (f.sent) return false
        if (f.tick == null && fits(f) != true) return false
        return f.tick ?: when (f.kind) {
            F_SEEN -> !f.ad && !otherAccount(f, main)
            F_PAGE -> true
            F_STREAM -> found.values.none { it.kind == F_PAGE && it.page == f.page }
            else -> false
        }
    }

    private fun showSheet() {
        val d = Dialog(this, if (pal.dark) android.R.style.Theme_DeviceDefault_Dialog_NoActionBar
            else android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(pal.surface)
                val r = pxf(28f)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(px(S2), px(S3), px(S2), px(S3))
        }
        sheet.addView(View(this).apply { background = rounded(pal.outlineStrong, R_PILL) },
            LinearLayout.LayoutParams(px(36), px(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = px(S3) })
        val head = TextView(this).apply {
            textSize = T_TITLE
            typeface = MEDIUM
            setTextColor(pal.text)
            setPadding(px(S4), 0, px(S4), px(2))
        }
        sheet.addView(head)
        sheet.addView(TextView(this).apply {
            text = "Every video you watch is added here, in order: swipe through a series to collect all its episodes. " +
                "Ads and videos from other accounts are left unticked."
            textSize = T_CAPTION
            setTextColor(pal.muted)
            setLineSpacing(pxf(2f), 1f)
            setPadding(px(S4), 0, px(S4), px(S2))
        })

        // the filter: how long, how big
        fun pill(onClick: () -> Unit) = Button(this).apply {
            isAllCaps = false
            textSize = T_LABEL
            typeface = MEDIUM
            stateListAnimator = null
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(px(14), 0, px(14), 0)
            setOnClickListener { onClick() }
        }
        fun paintPill(b: Button, text: String, on: Boolean) {
            b.text = "$text  \u25BE"
            b.setTextColor(if (on) pal.onAccentSoft else pal.muted)
            b.background = RippleDrawable(ColorStateList.valueOf(pal.ripple),
                if (on) rounded(pal.accentSoft, R_PILL) else rounded(pal.surface, R_PILL, pal.outlineStrong), null)
        }
        val lenPill = pill { chooseLength() }
        val sizePill = pill { chooseSize() }
        sheet.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(S4), px(S1), px(S4), px(S2))
            addView(lenPill, LinearLayout.LayoutParams(WRAP, px(38)))
            addView(sizePill, LinearLayout.LayoutParams(WRAP, px(38)).apply { marginStart = px(S2) })
        })

        // rows: section titles (String), videos (Found) and actions (RowAction)
        val rows = mutableListOf<Any>()
        var main: String? = null
        val listView = ListView(this).apply {
            divider = null
            selector = ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = true
        }
        val dl = Button(this)
        val tickAll = Button(this)

        fun count() = rows.count { it is Found && ticked(it, main) }

        val adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(p: Int) = rows[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getViewTypeCount() = 3
            override fun getItemViewType(p: Int) = when (rows[p]) { is String -> 0; is Found -> 1; else -> 2 }
            override fun isEnabled(p: Int) = rows[p].let { it is RowAction || (it is Found && !it.sent) }

            override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                val r = rows[p]
                if (r is String) {
                    val t = (convert as? TextView) ?: TextView(this@BrowserActivity).apply {
                        textSize = T_OVERLINE
                        typeface = MEDIUM
                        letterSpacing = 0.1f
                        setTextColor(pal.subtle)
                        setPadding(px(S4), px(S3), px(S4), px(S1))
                    }
                    t.text = r.uppercase()
                    return t
                }
                if (r is RowAction) {
                    val t = (convert as? TextView) ?: TextView(this@BrowserActivity).apply {
                        textSize = T_LABEL
                        typeface = MEDIUM
                        setTextColor(pal.accent)
                        setPadding(px(S4), px(S2), px(S4), px(S2))
                    }
                    t.text = r.text
                    return t
                }
                val f = r as Found
                val row = (convert as? LinearLayout) ?: LinearLayout(this@BrowserActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(px(S2), px(S2), px(S4), px(S2))
                    background = ripple(null, rounded(Color.WHITE, R_MD))
                    addView(CheckBox(this@BrowserActivity).apply {
                        buttonTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                            intArrayOf(pal.fill, pal.outlineStrong))
                        isClickable = false
                        isFocusable = false
                    }, LinearLayout.LayoutParams(WRAP, WRAP))
                    val texts = LinearLayout(this@BrowserActivity).apply { orientation = LinearLayout.VERTICAL }
                    texts.addView(TextView(this@BrowserActivity).apply {
                        textSize = T_BODY
                        setTextColor(pal.text)
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    texts.addView(TextView(this@BrowserActivity).apply {
                        textSize = T_CAPTION
                        setTextColor(pal.muted)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, px(2), 0, 0)
                    })
                    addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = px(S1) })
                }
                val box = row.getChildAt(0) as CheckBox
                val texts = row.getChildAt(1) as LinearLayout
                val title = texts.getChildAt(0) as TextView
                val sub = texts.getChildAt(1) as TextView
                box.isChecked = ticked(f, main)
                box.isEnabled = !f.sent
                val number = if (f.kind == F_SEEN) {
                    (found.values.filter { it.kind == F_SEEN }.indexOf(f) + 1).toString() + ".  "
                } else ""
                val fallback = if (f.author.isNotEmpty()) "@${f.author} · video ${f.url.takeLast(6)}"
                    else if (f.isShort) "YouTube Short ${f.url.takeLast(11)}" else f.url
                title.text = number + f.title.ifBlank { fallback }
                val inf = infos[f.url]
                val flags = mutableListOf(
                    if (inf != null && inf.sec > 0) Lengths.clock(inf.sec) else if (f.isShort) "Short" else "",
                    if (inf != null && inf.bytes > 0) "%.1f MB".format(inf.bytes / 1e6) else "",
                    VideoLinks.site(f.url))
                if (f.author.isNotEmpty() && f.title.isNotBlank()) flags.add("@" + f.author)
                when {
                    f.sent -> flags.add("added to downloads")
                    f.ad -> flags.add("looks like an ad")
                    otherAccount(f, main) -> flags.add("other account, maybe an ad")
                    f.kind == F_PAGE -> flags.add("video on this page")
                }
                sub.text = flags.filter { it.isNotEmpty() }.joinToString(" · ")
                sub.setTextColor(if (!f.sent && (f.ad || otherAccount(f, main))) pal.warn else pal.muted)
                row.alpha = if (f.sent) 0.5f else 1f
                return row
            }
        }

        // every video, in the order of the list without a filter (the order videos are merged in)
        fun inOrder(): List<Found> {
            val all = found.values.filter { it.url !in streamParts }
            return all.filter { it.kind != F_STREAM } + all.filter { it.kind == F_STREAM } + links.values
        }

        // what "Tick all" is for: the videos that pass the filter
        fun tickable(): List<Found> {
            val open = rows.filterIsInstance<Found>().filter { !it.sent }
            return open.filter { fits(it) == true }.ifEmpty { open }
        }

        fun rebuild() {
            main = mainAuthor()
            rows.clear()
            paintPill(lenPill, lengthText(), minSec > 0 || maxSec > 0)
            paintPill(sizePill, sizeText(), minMb > 0)
            val all = inOrder()
            val (pass, rest) = all.partition { fits(it) == true }
            val seen = pass.filter { it.kind == F_SEEN || it.kind == F_PAGE }
            val streams = pass.filter { it.kind == F_STREAM }
            val ls = pass.filter { it.kind == F_LINK }
            val unknown = rest.filter { fits(it) == null }
            if (seen.isNotEmpty()) { rows.add("Watched (${seen.size})"); rows.addAll(seen) }
            if (streams.isNotEmpty()) { rows.add("Video streams (${streams.size})"); rows.addAll(streams) }
            if (ls.isNotEmpty()) { rows.add("Links on this page (${ls.size})"); rows.addAll(ls) }
            if (unknown.isNotEmpty() || checking > 0) {
                rows.add((if (minSec > 0 || maxSec > 0) "Length" else "Size") + " not known (${unknown.size})")
                val todo = unknown.filter { it.url !in checked && it.kind != F_STREAM }
                if (checking > 0) rows.add(RowAction("Checking, $checking left. Tap to stop") { stopChecking(); sheetRefresh?.invoke() })
                else if (todo.isNotEmpty()) rows.add(RowAction("Check " + (if (todo.size > MAX_CHECK) "the first $MAX_CHECK" else
                    "${todo.size}") + " now (a few seconds each)") { check(todo) })
                rows.addAll(unknown)
            }
            val hidden = rest.size - unknown.size
            if (hidden > 0) rows.add("$hidden hidden by the filter")
            adapter.notifyDataSetChanged()
            head.text = when {
                all.isEmpty() -> "No videos found yet"
                filterOn() -> "Videos found (${pass.size} of ${all.size})"
                else -> "Videos found (${all.size})"
            }
            val k = count()
            dl.text = if (k > 0) "Download ($k)" else "Download"
            dl.isEnabled = k > 0
            dl.alpha = if (k > 0) 1f else 0.5f
            val open = tickable()
            tickAll.text = if (open.isNotEmpty() && open.all { ticked(it, main) }) "Untick all" else "Tick all"
        }

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, p, _ ->
            (rows[p] as? RowAction)?.let { it.run(); return@setOnItemClickListener }
            val f = rows[p] as? Found ?: return@setOnItemClickListener
            if (f.sent) return@setOnItemClickListener
            f.tick = !ticked(f, main)
            rebuild()
        }
        // tall enough for the list, but never more than about 60% of the screen
        val maxH = (resources.displayMetrics.heightPixels * 0.6).toInt()
        val n0 = found.size + links.size
        val h = minOf(maxH, px(66) * n0 + px(36) * 3).coerceAtLeast(px(120))
        sheet.addView(listView, LinearLayout.LayoutParams(MATCH, h))

        fun styleBtn(b: Button, filled: Boolean) {
            b.isAllCaps = false
            b.textSize = T_BODY
            b.typeface = MEDIUM
            b.stateListAnimator = null
            b.minHeight = px(46)
            b.minimumHeight = px(46)
            b.setPadding(px(16), 0, px(16), 0)
            b.setTextColor(if (filled) Color.WHITE else pal.onAccentSoft)
            b.background = RippleDrawable(ColorStateList.valueOf(if (filled) Color.argb(56, 255, 255, 255) else pal.ripple),
                rounded(if (filled) pal.fill else pal.accentSoft, R_PILL), null)
        }
        val clear = Button(this)
        styleBtn(tickAll, false)
        styleBtn(clear, false)
        styleBtn(dl, true)
        clear.text = "Clear"
        tickAll.setOnClickListener {
            val open = tickable()
            val on = !(open.isNotEmpty() && open.all { ticked(it, main) })
            for (f in open) f.tick = on
            rebuild()
        }
        clear.setOnClickListener {
            AlertDialog.Builder(this, if (pal.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert
                else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setMessage("Empty the list of videos found? Videos you watch from now on are added again.")
                .setPositiveButton("Empty it") { _, _ -> clearFound() }
                .setNegativeButton("Cancel", null)
                .show()
        }
        dl.setOnClickListener {
            val listed = rows.filterIsInstance<Found>().toSet()
            val sel = inOrder().filter { it in listed && ticked(it, main) }
            if (sel.isEmpty()) return@setOnClickListener
            askMerge(sel) { d.dismiss() }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(S2), px(S3), px(S2), 0)
        }
        actions.addView(tickAll, LinearLayout.LayoutParams(WRAP, px(46)))
        actions.addView(clear, LinearLayout.LayoutParams(WRAP, px(46)).apply { marginStart = px(S2) })
        actions.addView(dl, LinearLayout.LayoutParams(0, px(46), 1f).apply { marginStart = px(S2) })
        sheet.addView(actions)

        rebuild()
        sheetRefresh = { rebuild() }
        lookUp()
        d.setOnDismissListener { sheetRefresh = null }
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

    /** Two or more videos: ask whether to merge them into one. Then hand them to VidGrab's download list. */
    private fun askMerge(sel: List<Found>, done: () -> Unit) {
        if (sel.size < 2) { send(sel, false); done(); return }
        AlertDialog.Builder(this, if (pal.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert
            else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("Merge into one video?")
            .setMessage("Merge: the ${sel.size} videos are downloaded, then joined into one video in the order of the " +
                "list. The separate videos are deleted after merging.\n\nKeep separate: ${sel.size} videos are saved.")
            .setPositiveButton("Merge") { _, _ -> send(sel, true); done() }
            .setNegativeButton("Keep separate") { _, _ -> send(sel, false); done() }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun send(sel: List<Found>, merge: Boolean) {
        MainActivity.browserUa = web.settings.userAgentString
        for (f in sel) if (f.kind == F_STREAM) MainActivity.referers[f.url] = f.page
        if (!MainActivity.fromBrowser(sel.map { it.url }, merge)) {
            toast("Open VidGrab first, then the browser from it.")
            return
        }
        for (f in sel) { f.sent = true; f.tick = null }
        refreshFab()
        toast("Downloading ${sel.size} video(s)" + (if (merge) ", then merging them" else "") +
            ". Speed and time left are in VidGrab's list (menu > Downloads).")
    }

    // ---------- privacy ----------
    /** Uses the proxy from Settings for every page (when this phone's WebView supports it), then runs then. */
    private fun applyProxy(then: () -> Unit) {
        val p = getSharedPreferences("vidgrab", MODE_PRIVATE).getString("proxy", "") ?: ""
        val now = Executor { it.run() }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            if (p.isNotEmpty()) toast("This phone's web view can't use a proxy, so pages load without it. A VPN app works instead.")
            then()
            return
        }
        try {
            if (p.isEmpty()) ProxyController.getInstance().clearProxyOverride(now) { runOnUiThread { then() } }
            else ProxyController.getInstance().setProxyOverride(ProxyConfig.Builder().addProxyRule(p).build(), now) {
                runOnUiThread { then() }
            }
        } catch (e: Exception) {
            toast("The proxy could not be used (${e.message}). Pages load without it.")
            then()
        }
    }

    /** Erases everything browsing leaves behind: cookies and logins, site storage, cache, history, form data. */
    private fun erase() {
        try { web.clearCache(true); web.clearHistory(); web.clearFormData() } catch (_: Exception) {}
        try { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush() } catch (_: Exception) {}
        try { WebStorage.getInstance().deleteAllData() } catch (_: Exception) {}
        try { GeolocationPermissions.getInstance().clearAll() } catch (_: Exception) {}
        @Suppress("DEPRECATION")
        try { WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword() } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            fullView != null -> {
                fullCallback?.onCustomViewHidden()
                if (fullView != null) web.webChromeClient?.onHideCustomView()
            }
            web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        sheetRefresh = null
        looker.shutdownNow()
        checker.shutdownNow()
        stopChecking()
        erase()
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }
}
