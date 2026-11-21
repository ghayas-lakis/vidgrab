package com.example.vidgrab

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Logins for sites that need one (private videos, "Sign in to confirm you're not a bot", ...).
 * The user signs in inside SignInActivity; its cookies are saved as a Netscape cookies.txt file, which
 * yt-dlp reads with --cookies. Each yt-dlp run gets its own copy, because yt-dlp writes the file back.
 */
object Cookies {
    private const val HEADER = "# Netscape HTTP Cookie File"

    private fun file(ctx: Context) = File(ctx.filesDir, "cookies.txt")

    /** A private copy of the saved logins for one yt-dlp run (delete it afterwards), or null when signed out. */
    fun forRun(ctx: Context): File? {
        val f = file(ctx)
        if (!f.exists() || f.length() == 0L) return null
        return try {
            val dir = File(ctx.cacheDir, "cookies").apply { mkdirs() }
            File(dir, "${System.nanoTime()}.txt").also { f.copyTo(it, overwrite = true) }
        } catch (_: Exception) { null }
    }

    /** The sites with a saved login, e.g. ["youtube.com", "google.com"]. */
    fun sites(ctx: Context): List<String> = try {
        file(ctx).readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { it.split('\t').firstOrNull()?.trimStart('.') }.distinct().sorted()
    } catch (_: Exception) { emptyList() }

    fun clear(ctx: Context) {
        file(ctx).delete()
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (_: Exception) {}
    }

    /** "accounts.google.com" -> "google.com", "www.bbc.co.uk" -> "bbc.co.uk". */
    fun siteOf(host: String): String {
        val p = host.lowercase().trimEnd('.').split('.')
        if (p.size <= 2) return p.joinToString(".")
        val secondLevel = p[p.size - 2] in setOf("co", "com", "net", "org", "gov", "ac", "edu", "ne", "or")
        return p.takeLast(if (secondLevel && p.last().length == 2) 3 else 2).joinToString(".")
    }

    /**
     * Saves the WebView's cookies for the hosts the sign-in visited, replacing any older login for those
     * sites. Returns the sites saved.
     */
    fun saveFrom(ctx: Context, hosts: Collection<String>): List<String> {
        val cm = CookieManager.getInstance()
        cm.flush()
        val expiry = System.currentTimeMillis() / 1000 + 365L * 24 * 60 * 60
        val fresh = LinkedHashMap<String, String>()          // "domain\tname" -> cookies.txt line
        // Visit shorter hosts first, so a site-wide cookie wins over one seen only on a sub-domain.
        for (host in hosts.distinct().sortedBy { it.length }) {
            val raw = cm.getCookie("https://$host") ?: continue
            val domain = "." + siteOf(host)
            for (pair in raw.split(";")) {
                val kv = pair.trim()
                val eq = kv.indexOf('=')
                if (eq <= 0) continue
                val name = kv.substring(0, eq).trim()
                val value = kv.substring(eq + 1).trim()
                if (name.isEmpty() || '\t' in value || '\n' in value) continue
                fresh.putIfAbsent("$domain\t$name", listOf(domain, "TRUE", "/", "TRUE", expiry.toString(), name, value).joinToString("\t"))
            }
        }
        if (fresh.isEmpty()) return emptyList()
        val newDomains = fresh.keys.map { it.substringBefore('\t') }.toSet()
        val keep = try {
            file(ctx).readLines().filter { l ->
                l.isNotBlank() && !l.startsWith("#") && l.substringBefore('\t') !in newDomains
            }
        } catch (_: Exception) { emptyList() }
        file(ctx).writeText((listOf(HEADER, "") + keep + fresh.values).joinToString("\n") + "\n")
        return newDomains.map { it.trimStart('.') }.sorted()
    }
}

/**
 * A plain browser page for signing in to a site. "Done" saves the login (Cookies.saveFrom) for VidGrab's
 * downloads. Result extra "sites": the sites saved, comma separated.
 */
class SignInActivity : Activity() {
    private lateinit var web: WebView
    private val hosts = ConcurrentHashMap.newKeySet<String>()

    override fun onCreate(s: Bundle?) {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        setTheme(if (dark) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        super.onCreate(s)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val bg = if (dark) Color.parseColor("#0B0E14") else Color.parseColor("#F4F6FA")
        val fg = if (dark) Color.parseColor("#E8ECF3") else Color.parseColor("#0E1726")
        val muted = if (dark) Color.parseColor("#9AA4B5") else Color.parseColor("#5B6679")
        window.statusBarColor = bg

        val start = intent.getStringExtra("url") ?: "https://www.youtube.com/"
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bg)
            setPadding(px(16), px(8), px(8), px(8))
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = "Sign in"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(fg)
        })
        val where = TextView(this).apply {
            text = "Sign in below, then tap Done. VidGrab keeps only this login, on this phone."
            textSize = 12f
            setTextColor(muted)
        }
        titles.addView(where)
        bar.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            setOnClickListener { setResult(RESULT_CANCELED); finish() }
        })
        bar.addView(Button(this).apply {
            text = "Done"
            isAllCaps = false
            setOnClickListener { done() }
        })
        val loading = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }

        web = WebView(this)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true       // sign-in pages need it
        web.settings.domStorageEnabled = true
        // Look like the phone's normal browser: some sign-in pages refuse "embedded" browsers.
        web.settings.userAgentString = web.settings.userAgentString
            .replace("; wv", "").replace(Regex("Version/[\\d.]+ "), "")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                req.url.host?.let { hosts.add(it) }            // where the login may have set cookies
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                loading.visibility = View.VISIBLE
                android.net.Uri.parse(url).host?.let { hosts.add(it); where.text = it }
            }

            override fun onPageFinished(view: WebView, url: String) {
                loading.visibility = View.GONE
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(loading, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(3)))
            addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        if (s != null) web.restoreState(s) else web.loadUrl(start)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        web.saveState(out)
    }

    private fun done() {
        val sites = Cookies.saveFrom(this, hosts)
        setResult(RESULT_OK, Intent().putExtra("sites", sites.joinToString(", ")))
        finish()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
