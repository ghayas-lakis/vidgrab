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
import java.net.URLEncoder
import java.util.concurrent.Executor

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
        Rule(rx("^https?://(?:www\\.|mobile\\.)?(?:twitter|x)\\.com/(\\w+)/status/(\\d+)")) {
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
    }

    private companion object {
        const val F_SEEN = 0            // a video page you watched (a site VidGrab knows)
        const val F_PAGE = 1            // a page of another site that shows a video
        const val F_STREAM = 2          // a direct video file or stream
        const val F_LINK = 3            // a link to a video on the current page
        const val MAX_STREAMS = 15      // per page
        const val HOME = "about:blank"
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
            if (json.length > 200_000) return
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
            found[pageVideo]?.let { f -> if (title.isNotEmpty() && f.title != title && f.kind == F_SEEN) { f.title = title; changed = true } }
        }
        // 2. the big video on screen has its own link (feeds whose address doesn't change)
        val near = o.optString("near").takeIf { it.isNotEmpty() }?.let { VideoLinks.canonical(it) }
        if (near != null && near != pageVideo) changed = addFound(near, "", F_SEEN, page, ad) or changed
        // 3. another site showing a video: the page (yt-dlp knows many sites) and its video sources
        if (!VideoLinks.knownSite(Uri.parse(page).host) && o.optBoolean("video")) {
            if (pageVideo == null) changed = addFound(page.substringBefore('#'), title, F_PAGE, page, false) or changed
            val media = o.optJSONArray("media")
            for (k in 0 until (media?.length() ?: 0)) changed = addStream(media!!.optString(k), page) or changed
        }
        // 4. links to videos on this page
        val ls = o.optJSONArray("links")
        val now = LinkedHashMap<String, Found>()
        for (k in 0 until (ls?.length() ?: 0)) {
            val e = ls!!.optJSONArray(k) ?: continue
            val c = VideoLinks.canonical(e.optString(0)) ?: continue
            if (c in found || c in now) continue
            now[c] = links[c] ?: Found(c, e.optString(1).trim(), VideoLinks.author(c), F_LINK, page)
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
        if (!url.startsWith("http") || url in found) return false
        if (found.values.count { it.kind == F_STREAM && it.page == page } >= MAX_STREAMS) return false
        val name = (Uri.parse(url).lastPathSegment ?: "video").take(48)
        found[url] = Found(url, "Video stream: $name", "", F_STREAM, page)
        return true
    }

    private fun clearFound() {
        found.clear()
        links.clear()
        lastCount = 0
        refreshFab()
        toast("The list of videos found is empty. New videos you watch are added again.")
    }

    /** The round button: how many videos were found. It pops when a new one is added. */
    private fun refreshFab() {
        sheetRefresh?.invoke()
        val n = found.size
        val l = links.size
        if (fullView != null || (n == 0 && l == 0)) { fab.visibility = View.GONE; lastCount = n; return }
        fab.text = if (n > 0) "$n video${if (n == 1) "" else "s"}" else "$l video link${if (l == 1) "" else "s"}"
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

        // rows: section titles (String) and videos (Found)
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
            override fun getViewTypeCount() = 2
            override fun getItemViewType(p: Int) = if (rows[p] is String) 0 else 1
            override fun isEnabled(p: Int) = rows[p] is Found && !(rows[p] as Found).sent

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
                val fallback = if (f.author.isNotEmpty()) "@${f.author} · video ${f.url.takeLast(6)}" else f.url
                title.text = number + f.title.ifBlank { fallback }
                val flags = mutableListOf(VideoLinks.site(f.url))
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

        fun rebuild() {
            main = mainAuthor()
            rows.clear()
            val seen = found.values.filter { it.kind == F_SEEN || it.kind == F_PAGE }
            val streams = found.values.filter { it.kind == F_STREAM }
            if (seen.isNotEmpty()) { rows.add("Watched (${seen.size})"); rows.addAll(seen) }
            if (streams.isNotEmpty()) { rows.add("Video streams (${streams.size})"); rows.addAll(streams) }
            if (links.isNotEmpty()) { rows.add("Links on this page (${links.size})"); rows.addAll(links.values) }
            adapter.notifyDataSetChanged()
            val n = rows.count { it is Found }
            head.text = if (n == 0) "No videos found yet" else "Videos found ($n)"
            val k = count()
            dl.text = if (k > 0) "Download ($k)" else "Download"
            dl.isEnabled = k > 0
            dl.alpha = if (k > 0) 1f else 0.5f
            val open = rows.filterIsInstance<Found>().filter { !it.sent }
            tickAll.text = if (open.isNotEmpty() && open.all { ticked(it, main) }) "Untick all" else "Tick all"
        }

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, p, _ ->
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
            val open = rows.filterIsInstance<Found>().filter { !it.sent }
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
            val sel = rows.filterIsInstance<Found>().filter { ticked(it, main) }
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
        erase()
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }
}
