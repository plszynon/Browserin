package com.example.browser

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private data class Tab(val webView: WebView, var title: String = "Nowa karta")

    private lateinit var webViewContainer: FrameLayout
    private lateinit var tabBarContainer: LinearLayout
    private lateinit var editUrl: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var incognitoBadge: TextView
    private lateinit var toolbar: LinearLayout

    private val tabs = mutableListOf<Tab>()
    private var currentTabIndex = -1

    private var isIncognito = false
    private var adblockEnabled = true
    private val blockedHosts = mutableSetOf<String>()
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult

        val data = result.data
        val uris: Array<Uri>? = if (result.resultCode == RESULT_OK && data != null) {
            val clipData = data.clipData
            if (clipData != null) {
                Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
            } else {
                data.data?.let { arrayOf(it) }
            }
        } else null

        callback.onReceiveValue(uris)
    }
    private val prefs by lazy { getSharedPreferences("browser_data", Context.MODE_PRIVATE) }

    // Skrypt wstrzykiwany na każdą stronę - przechwytuje kliknięcia w linki typu blob:
    // (używane np. przez przyciski "Eksportuj / Pobierz" generujące plik w JS)
    private val blobCaptureScript = """
        (function() {
            document.addEventListener('click', function(e) {
                var el = e.target;
                for (var i = 0; i < 5 && el; i++) {
                    if (el.tagName === 'A' && el.href && el.href.startsWith('blob:')) {
                        e.preventDefault();
                        var filename = el.getAttribute('download') || 'pobrany_plik';
                        fetch(el.href).then(function(r){ return r.blob(); }).then(function(blob){
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                AndroidBlobBridge.saveBlob(reader.result, filename);
                            };
                            reader.readAsDataURL(blob);
                        });
                        return;
                    }
                    el = el.parentElement;
                }
            }, true);
        })();
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loadBlockList()
        adblockEnabled = prefs.getBoolean("adblock_enabled", true)

        webViewContainer = findViewById(R.id.webViewContainer)
        tabBarContainer = findViewById(R.id.tabBarContainer)
        editUrl = findViewById(R.id.editUrl)
        progressBar = findViewById(R.id.progressBar)
        incognitoBadge = findViewById(R.id.incognitoBadge)
        toolbar = findViewById(R.id.toolbar)

        applySavedThemeColor()

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            currentWebView()?.let { if (it.canGoBack()) it.goBack() }
        }
        findViewById<ImageButton>(R.id.btnForward).setOnClickListener {
            currentWebView()?.let { if (it.canGoForward()) it.goForward() }
        }
        findViewById<ImageButton>(R.id.btnReload).setOnClickListener {
            currentWebView()?.reload()
        }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener {
            showMenu()
        }

        editUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                loadFromInput(editUrl.text.toString())
                true
            } else {
                false
            }
        }

        val startUrl = intent.getStringExtra("OPEN_URL") ?: "file:///android_asset/newtab.html"
        createNewTab(startUrl)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = intent.getStringExtra("OPEN_URL")
        if (url != null) createNewTab(url)
    }

    // ---------- OBSŁUGA KART ----------

    private fun currentWebView(): WebView? =
        tabs.getOrNull(currentTabIndex)?.webView

    private fun createNewTab(url: String) {
        val webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setupWebView(webView)
        webViewContainer.addView(webView)

        val tab = Tab(webView)
        tabs.add(tab)
        webView.loadUrl(url)
        switchToTab(tabs.size - 1)
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices) return
        currentTabIndex = index
        for (i in tabs.indices) {
            tabs[i].webView.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        editUrl.setText(tabs[index].webView.url ?: "")
        rebuildTabBar()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs[index]
        webViewContainer.removeView(tab.webView)
        tab.webView.destroy()
        tabs.removeAt(index)

        if (tabs.isEmpty()) {
            createNewTab("file:///android_asset/newtab.html")
            return
        }
        val newIndex = (index - 1).coerceAtLeast(0)
        switchToTab(newIndex)
    }

    private fun rebuildTabBar() {
        tabBarContainer.removeAllViews()
        for ((i, tab) in tabs.withIndex()) {
            val pill = LinearLayout(this)
            pill.orientation = LinearLayout.HORIZONTAL
            pill.gravity = Gravity.CENTER_VERTICAL
            pill.setPadding(20, 12, 12, 12)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(6, 6, 6, 6)
            pill.layoutParams = params
            pill.setBackgroundColor(if (i == currentTabIndex) Color.parseColor("#DDDDDD") else Color.parseColor("#EEEEEE"))

            val label = TextView(this)
            val title = tab.title.take(14)
            label.text = title
            label.setPadding(0, 0, 12, 0)
            pill.addView(label)

            val closeBtn = TextView(this)
            closeBtn.text = "✕"
            closeBtn.setPadding(8, 0, 8, 0)
            closeBtn.setOnClickListener { closeTab(i) }
            pill.addView(closeBtn)

            pill.setOnClickListener { switchToTab(i) }
            tabBarContainer.addView(pill)
        }

        val addBtn = TextView(this)
        addBtn.text = "  +  "
        addBtn.textSize = 18f
        val addParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        addParams.setMargins(6, 6, 6, 6)
        addBtn.layoutParams = addParams
        addBtn.setOnClickListener { createNewTab("file:///android_asset/newtab.html") }
        tabBarContainer.addView(addBtn)
    }

    // ---------- KONFIGURACJA WEBVIEW ----------

    private fun setupWebView(webView: WebView) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.setSupportZoom(true)
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // Zapobiega białemu "mignięciu" przed załadowaniem strony
        webView.setBackgroundColor(Color.TRANSPARENT)

        // Jeśli system jest w trybie ciemnym, przyciemniaj też strony,
        // które same nie obsługują dark mode
        val nightMode = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        if (nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
            }
        }

        webView.addJavascriptInterface(BlobDownloadBridge(), "AndroidBlobBridge")
        webView.addJavascriptInterface(NewTabBridge(), "AndroidNewTab")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (view == currentWebView()) {
                    progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                    progressBar.progress = newProgress
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                val tab = tabs.find { it.webView == view }
                if (tab != null && !title.isNullOrEmpty()) {
                    tab.title = title
                    if (view == currentWebView()) rebuildTabBar()
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }

                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    Toast.makeText(this@MainActivity, "Nie można otworzyć wyboru plików", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (!adblockEnabled) return null
                val host = request.url.host ?: return null
                val isBlocked = blockedHosts.any { host == it || host.endsWith(".$it") }
                return if (isBlocked) {
                    WebResourceResponse("text/plain", "utf-8", null)
                } else {
                    null
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (view == currentWebView()) {
                    editUrl.setText(if (url.startsWith("file:///android_asset/")) "" else url)
                }
                view.evaluateJavascript(blobCaptureScript, null)
                if (!isIncognito && !url.startsWith("file:///android_asset/")) {
                    saveHistoryEntry(url, view.title ?: url)
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Problem z certyfikatem SSL")
                    .setMessage("Ta strona ma niezaufany certyfikat. Kontynuować mimo to?")
                    .setPositiveButton("Kontynuuj") { _, _ -> handler.proceed() }
                    .setNegativeButton("Anuluj") { _, _ -> handler.cancel() }
                    .show()
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:")) {
                // Pobieranie blobów obsługuje wstrzyknięty skrypt JS (patrz blobCaptureScript)
                // Ten listener łapie tylko przypadki, gdy nie zadziałało kliknięcie w <a>
                Toast.makeText(
                    this,
                    "Kliknij bezpośrednio w przycisk pobierania na stronie",
                    Toast.LENGTH_LONG
                ).show()
                return@setDownloadListener
            }
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimeType)
                request.addRequestHeader("User-Agent", userAgent)
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.allowScanningByMediaScanner()

                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "Pobieranie: $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Nie udało się pobrać pliku: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Most JS <-> Android dostarczający listę zakładek do strony nowej karty
    private inner class NewTabBridge {
        @JavascriptInterface
        fun getBookmarksJson(): String {
            return prefs.getString("bookmarks", "[]") ?: "[]"
        }
    }

    // Most JS <-> Android do zapisywania plików pochodzących z blob: (np. eksport zapisu gry)
    private inner class BlobDownloadBridge {
        @JavascriptInterface
        fun saveBlob(base64DataUrl: String, filename: String) {
            try {
                val commaIndex = base64DataUrl.indexOf(',')
                val meta = base64DataUrl.substring(0, commaIndex)
                val data = base64DataUrl.substring(commaIndex + 1)
                val bytes = Base64.decode(data, Base64.DEFAULT)
                val mime = Regex("data:(.*?);base64").find(meta)?.groupValues?.get(1) ?: "application/octet-stream"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, filename)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { it.write(bytes) }
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    }
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val file = java.io.File(dir, filename)
                    FileOutputStream(file).use { it.write(bytes) }
                }

                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Zapisano: $filename", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Błąd zapisu pliku: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---------- POZOSTAŁE FUNKCJE ----------

    private fun loadFromInput(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

        val looksLikeUrl = trimmed.contains(".") && !trimmed.contains(" ")
        val finalUrl = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            looksLikeUrl -> "https://$trimmed"
            else -> "https://www.google.com/search?q=" + Uri.encode(trimmed)
        }
        currentWebView()?.loadUrl(finalUrl)
    }

    private fun loadBlockList() {
        try {
            val reader = BufferedReader(InputStreamReader(assets.open("adblock_hosts.txt")))
            reader.forEachLine { line ->
                val h = line.trim()
                if (h.isNotEmpty() && !h.startsWith("#")) blockedHosts.add(h)
            }
            reader.close()
        } catch (e: Exception) {
            // brak listy nie powinien wywalać aplikacji
        }
    }

    private fun showMenu() {
        val options = arrayOf(
            if (isIncognito) "Wyłącz tryb incognito" else "Włącz tryb incognito",
            if (adblockEnabled) "Wyłącz adblock" else "Włącz adblock",
            "Dodaj do zakładek",
            "Zakładki",
            "Historia",
            "Zmień kolor motywu",
            "Nowa karta"
        )
        AlertDialog.Builder(this)
            .setTitle("Menu")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleIncognito()
                    1 -> toggleAdblock()
                    2 -> addBookmark()
                    3 -> startActivity(Intent(this, BookmarksActivity::class.java))
                    4 -> startActivity(Intent(this, HistoryActivity::class.java))
                    5 -> showColorPicker()
                    6 -> createNewTab("file:///android_asset/newtab.html")
                }
            }
            .show()
    }

    private fun toggleIncognito() {
        isIncognito = !isIncognito
        incognitoBadge.visibility = if (isIncognito) View.VISIBLE else View.GONE
        Toast.makeText(
            this,
            if (isIncognito) "Tryb incognito włączony" else "Tryb incognito wyłączony",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleAdblock() {
        adblockEnabled = !adblockEnabled
        prefs.edit().putBoolean("adblock_enabled", adblockEnabled).apply()
        Toast.makeText(
            this,
            if (adblockEnabled) "Adblock włączony" else "Adblock wyłączony - odświeżam stronę",
            Toast.LENGTH_SHORT
        ).show()
        currentWebView()?.reload()
    }

    private fun addBookmark() {
        val webView = currentWebView() ?: return
        val url = webView.url ?: return
        val title = webView.title ?: url
        val arr = JSONArray(prefs.getString("bookmarks", "[]"))
        val obj = JSONObject()
        obj.put("title", title)
        obj.put("url", url)
        arr.put(obj)
        prefs.edit().putString("bookmarks", arr.toString()).apply()
        Toast.makeText(this, "Dodano do zakładek", Toast.LENGTH_SHORT).show()
    }

    private fun saveHistoryEntry(url: String, title: String) {
        val arr = JSONArray(prefs.getString("history", "[]"))
        val obj = JSONObject()
        obj.put("title", title)
        obj.put("url", url)
        obj.put("time", System.currentTimeMillis())
        arr.put(obj)
        val trimmed = if (arr.length() > 500) {
            val newArr = JSONArray()
            for (i in (arr.length() - 500) until arr.length()) newArr.put(arr.get(i))
            newArr
        } else arr
        prefs.edit().putString("history", trimmed.toString()).apply()
    }

    private fun applySavedThemeColor() {
        val savedColor = prefs.getInt("theme_color", Color.parseColor("#2196F3"))
        toolbar.setBackgroundColor(savedColor)
        window.statusBarColor = savedColor
    }

    private fun showColorPicker() {
        val colors = listOf(
            "#2196F3", "#F44336", "#4CAF50", "#FF9800", "#9C27B0",
            "#009688", "#E91E63", "#607D8B", "#000000", "#795548"
        )

        val grid = GridLayout(this)
        grid.columnCount = 5
        grid.setPadding(24, 24, 24, 24)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Wybierz kolor motywu")
            .setView(grid)
            .setNegativeButton("Anuluj", null)
            .create()

        for (hex in colors) {
            val swatch = View(this)
            val size = 120
            val params = GridLayout.LayoutParams()
            params.width = size
            params.height = size
            params.setMargins(12, 12, 12, 12)
            swatch.layoutParams = params
            swatch.setBackgroundColor(Color.parseColor(hex))
            swatch.setOnClickListener {
                val color = Color.parseColor(hex)
                toolbar.setBackgroundColor(color)
                window.statusBarColor = color
                prefs.edit().putInt("theme_color", color).apply()
                dialog.dismiss()
            }
            grid.addView(swatch)
        }

        dialog.show()
    }

    override fun onBackPressed() {
        val wv = currentWebView()
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else if (tabs.size > 1) {
            closeTab(currentTabIndex)
        } else {
            super.onBackPressed()
        }
    }
}
