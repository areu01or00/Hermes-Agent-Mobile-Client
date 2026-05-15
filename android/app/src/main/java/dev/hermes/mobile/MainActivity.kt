package dev.hermes.mobile

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val LOG_TAG = "HermesWebView"
private const val PREFS_NAME = "hermes_mobile_client"
private const val PREF_LAST_DASHBOARD_BASE = "last_dashboard_base"
private const val STATE_WEBVIEW = "state_webview"

class MainActivity : ComponentActivity() {
    private lateinit var webView: HermesWebView
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startupExecutor = Executors.newSingleThreadExecutor()
    private val attemptedBases = CopyOnWriteArrayList<String>()
    private var showingConnectionHub = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(false)
        CookieManager.getInstance().setAcceptCookie(true)

        webView = HermesWebView(this).apply {
            mobileInputSink = object : HermesWebView.MobileInputSink {
                override fun sendText(text: String) {
                    sendHermesMobileText(text)
                }

                override fun sendKey(key: String) {
                    sendHermesMobileKey(key)
                }

                override fun scroll(deltaY: Float) {
                    sendHermesMobileScroll(deltaY)
                }
            }

            setBackgroundColor(Color.rgb(4, 28, 28))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.textZoom = 90
            settings.userAgentString = "${settings.userAgentString} HermesAgentMobile/0.1"

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    return handleInternalUrl(url)
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url ?: return false
                    return handleInternalUrl(uri.toString())
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    Log.d(LOG_TAG, "Loaded $url")
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        injectMobileChrome(view)
                        injectMobileInputBridge(view)
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    Log.e(LOG_TAG, "WebView error ${error.errorCode}: ${error.description} for ${request.url}")
                    if (request.isForMainFrame) {
                        renderStatusPage(
                            "Dashboard load failed: ${error.description} (${error.errorCode})",
                            listOf(request.url.toString()),
                        )
                    }
                    super.onReceivedError(view, request, error)
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    Log.e(LOG_TAG, "WebView HTTP ${errorResponse.statusCode}: ${errorResponse.reasonPhrase} for ${request.url}")
                    if (request.isForMainFrame) {
                        renderStatusPage(
                            "Dashboard returned HTTP ${errorResponse.statusCode}: ${errorResponse.reasonPhrase}",
                            listOf(request.url.toString()),
                        )
                    }
                    super.onReceivedHttpError(view, request, errorResponse)
                }
            }

        }

        setContentView(webView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        val restored = savedInstanceState?.getBundle(STATE_WEBVIEW)?.let { state ->
            webView.restoreState(state)
            true
        } ?: false
        if (restored) {
            Log.d(LOG_TAG, "Restored WebView state after configuration change")
            return
        }

        renderConnectionHome()
    }

    override fun onDestroy() {
        startupExecutor.shutdownNow()
        webView.destroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val webState = Bundle()
        webView.saveState(webState)
        outState.putBundle(STATE_WEBVIEW, webState)
    }

    private fun renderConnectionHome() {
        showingConnectionHub = true
        val html = """
            <html><body style="margin:0;background:#041c1c;color:#ffe6cb;font-family:monospace">
              <div style="padding:28px">
                <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
                  <div style="font-size:22px;letter-spacing:1px">HERMES CONNECTION HUB</div>
                  <a href="hermes://menu" style="text-decoration:none;color:#ffe6cb;background:#0d1d18;border:1px solid #21362d;padding:8px 12px">☰</a>
                </div>
                <div style="font-size:13px;color:#89917e;margin-bottom:18px">Choose how you want to connect</div>
                <a href="hermes://discover" style="display:block;text-decoration:none;background:#0d1d18;color:#ffe6cb;padding:16px;border:1px solid #21362d;margin-bottom:10px">Same Wi-Fi (Auto Discover)</a>
                <a href="hermes://manual" style="display:block;text-decoration:none;background:#0d1d18;color:#ffe6cb;padding:16px;border:1px solid #21362d;margin-bottom:10px">VPS / Cloud (Enter URL)</a>
                <a href="hermes://saved" style="display:block;text-decoration:none;background:#0d1d18;color:#ffe6cb;padding:16px;border:1px solid #21362d;margin-bottom:10px">Use Saved Endpoint</a>
                <a href="hermes://script" style="display:block;text-decoration:none;background:#0d1d18;color:#ffe6cb;padding:16px;border:1px solid #21362d;margin-bottom:10px">Show VPS Setup Script</a>
              </div>
            </body></html>
        """.trimIndent()
        mainHandler.post { webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) }
    }

    private fun connectSavedOrManual() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastBase = prefs.getString(PREF_LAST_DASHBOARD_BASE, null)?.trim()?.removeSuffix("/")
        if (!lastBase.isNullOrBlank()) {
            loadDashboardBase(lastBase, persist = false)
        } else {
            promptForManualEndpoint()
        }
    }

    private fun bootstrapDashboardConnection() {
        renderStatusPage("Scanning local network for Hermes dashboard...", attemptedBases.toList())
        startupExecutor.execute {
            Log.d(LOG_TAG, "bootstrap: start")
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastBase = prefs.getString(PREF_LAST_DASHBOARD_BASE, null)?.trim()?.removeSuffix("/")
            if (!lastBase.isNullOrBlank() && isHermesDashboardBase(lastBase)) {
                Log.d(LOG_TAG, "bootstrap: using last base $lastBase")
                loadDashboardBase(lastBase, persist = false)
                return@execute
            }

            val discovered = discoverHermesDashboardBases()
            val selected = discovered.firstOrNull()

            if (selected != null) {
                Log.d(LOG_TAG, "bootstrap: using discovered base $selected")
                loadDashboardBase(selected, persist = true)
                return@execute
            }

            Log.w(LOG_TAG, "bootstrap: discovery failed, showing status page")
            renderStatusPage("Could not find Hermes dashboard automatically.", attemptedBases.toList())
            renderConnectionHome()
        }
    }

    private fun loadDashboardBase(base: String, persist: Boolean) {
        if (persist) {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_LAST_DASHBOARD_BASE, base)
                .apply()
        }
        showingConnectionHub = false
        val chatUrl = "${base.removeSuffix("/")}/chat"
        renderStatusPage("Opening Hermes dashboard...", listOf(chatUrl))
        mainHandler.post { webView.loadUrl(chatUrl) }
    }

    private fun discoverHermesDashboardBases(): List<String> {
        val candidates = linkedSetOf<String>()

        discoverViaMdns().forEach { host ->
            candidates += "http://$host:9119"
        }
        discoverViaLanProbe().forEach { host ->
            candidates += "http://$host:9119"
        }

        val verified = mutableListOf<String>()
        for (base in candidates) {
            attemptedBases += base
            if (isHermesDashboardBase(base)) {
                verified += base
            }
        }
        Log.d(LOG_TAG, "Discovery verified ${verified.size} Hermes dashboard endpoints")
        return verified
    }

    private fun discoverViaMdns(): Set<String> {
        val nsd = getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return emptySet()
        val hosts = Collections.synchronizedSet(mutableSetOf<String>())
        val done = CountDownLatch(1)
        val resolvePending = Collections.synchronizedList(mutableListOf<CountDownLatch>())

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {
                done.countDown()
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                done.countDown()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                done.countDown()
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                val name = service.serviceName.lowercase()
                if (!name.contains("hermes")) return
                val wait = CountDownLatch(1)
                resolvePending += wait
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        wait.countDown()
                    }
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress
                        if (!host.isNullOrBlank()) hosts += host
                        wait.countDown()
                    }
                })
            }
        }

        return try {
            nsd.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            done.await(3500, TimeUnit.MILLISECONDS)
            runCatching { nsd.stopServiceDiscovery(listener) }
            resolvePending.forEach { it.await(1200, TimeUnit.MILLISECONDS) }
            hosts
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun discoverViaLanProbe(): Set<String> {
        val localIp = localIpv4Address() ?: return emptySet()
        val parts = localIp.split(".")
        if (parts.size != 4) return emptySet()
        val prefix = "${parts[0]}.${parts[1]}.${parts[2]}."
        val found = Collections.synchronizedSet(mutableSetOf<String>())
        val pool = Executors.newFixedThreadPool(32)
        val stop = AtomicBoolean(false)
        try {
            for (i in 1..254) {
                pool.execute {
                    if (stop.get()) return@execute
                    val host = "$prefix$i"
                    if (isHermesDashboardBase("http://$host:9119")) {
                        found += host
                        stop.set(true)
                    }
                }
            }
            pool.shutdown()
            val finished = pool.awaitTermination(8, TimeUnit.SECONDS)
            if (!finished) pool.shutdownNow()
        } catch (_: Exception) {
            pool.shutdownNow()
        }
        return found
    }

    private fun isHermesDashboardBase(baseUrl: String): Boolean {
        val clean = baseUrl.removeSuffix("/")
        val statusUrl = "$clean/api/status"
        return try {
            Log.d(LOG_TAG, "probe $statusUrl")
            val conn = (URL(statusUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1200
                readTimeout = 1200
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code != 200) return false
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            body.contains("\"version\"") && body.contains("\"gateway_running\"")
        } catch (_: Exception) {
            false
        }
    }

    private fun renderStatusPage(message: String, attempted: List<String>) {
        val attemptedHtml = if (attempted.isEmpty()) {
            "<li>No endpoints attempted yet.</li>"
        } else {
            attempted.joinToString("") { "<li>${it}/api/status</li>" }
        }
        val html = """
            <html><body style="background:#041c1c;color:#ffe6cb;font-family:monospace;padding:24px;line-height:1.45">
            <h3 style="margin:0 0 10px 0">Hermes Mobile Client</h3>
            <p style="margin:0 0 10px 0">$message</p>
            <p style="margin:0 0 6px 0">Attempted endpoints:</p>
            <ul style="margin:0 0 12px 0;padding-left:20px">$attemptedHtml</ul>
            <p style="margin:0">Ensure Hermes dashboard is reachable on port 9119 from this phone network, then relaunch app.</p>
            </body></html>
        """.trimIndent()
        mainHandler.post { webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) }
    }

    private fun promptForManualEndpoint() {
        mainHandler.post {
            val input = EditText(this).apply {
                hint = "http://<host>:9119"
                setText("http://")
                setTextColor(Color.parseColor("#ffe6cb"))
                setHintTextColor(Color.parseColor("#89917e"))
                setBackgroundColor(Color.parseColor("#0d1d18"))
                setPadding(28, 22, 28, 22)
            }
            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#041c1c"))
                setPadding(36, 28, 36, 20)
            }
            val title = TextView(this).apply {
                text = "VPS / CLOUD ENDPOINT"
                setTextColor(Color.parseColor("#ffe6cb"))
                textSize = 16f
                setPadding(0, 0, 0, 10)
            }
            val help = TextView(this).apply {
                text = "Enter dashboard base URL (example: http://203.0.113.10:9119)"
                setTextColor(Color.parseColor("#89917e"))
                textSize = 12f
                setPadding(0, 0, 0, 14)
            }
            wrapper.addView(title)
            wrapper.addView(help)
            wrapper.addView(input)

            AlertDialog.Builder(this)
                .setView(wrapper)
                .setCancelable(false)
                .setPositiveButton("Connect") { _, _ ->
                    hideKeyboard(input)
                    val raw = input.text?.toString()?.trim().orEmpty()
                    val base = normalizeDashboardBase(raw)
                    if (base.isNotBlank()) {
                        attemptedBases += base
                        loadDashboardBase(base, persist = true)
                    } else {
                        renderStatusPage("Manual endpoint is empty.", attemptedBases.toList())
                        renderConnectionHome()
                    }
                }
                .setNegativeButton("Back") { _, _ -> renderConnectionHome() }
                .show()
        }
    }

    private fun hideKeyboard(input: EditText) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    private fun normalizeDashboardBase(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
        return withScheme
            .removeSuffix("/")
            .removeSuffix("/chat")
            .removeSuffix("/")
    }

    private fun showVpsScriptDialog() {
        val script = """
            # Run on VPS after SSH
            cat > setup-vps-dashboard.sh <<'EOF'
            #!/usr/bin/env bash
            set -euo pipefail
            SERVICE_NAME="hermes-dashboard.service"
            SERVICE_PATH="/etc/systemd/system/${'$'}{SERVICE_NAME}"
            DASHBOARD_PORT=9119
            DASHBOARD_HOST=0.0.0.0
            RUN_USER="${'$'}USER"
            RUN_HOME="$(eval echo "~${'$'}{RUN_USER}")"
            WORKDIR="${'$'}{RUN_HOME}"
            if ! command -v hermes >/dev/null 2>&1; then echo "hermes not found"; exit 1; fi
            sudo tee "${'$'}{SERVICE_PATH}" >/dev/null <<EOT
            [Unit]
            Description=Hermes Dashboard
            After=network-online.target
            Wants=network-online.target
            [Service]
            Type=simple
            User=${'$'}{RUN_USER}
            WorkingDirectory=${'$'}{WORKDIR}
            Environment=HOME=${'$'}{RUN_HOME}
            Environment=HERMES_DASHBOARD_TUI=1
            ExecStart=$(command -v hermes) dashboard --host ${'$'}{DASHBOARD_HOST} --port ${'$'}{DASHBOARD_PORT} --no-open --insecure --tui
            Restart=on-failure
            RestartSec=3
            [Install]
            WantedBy=multi-user.target
            EOT
            sudo systemctl daemon-reload
            sudo systemctl enable "${'$'}{SERVICE_NAME}" >/dev/null
            sudo systemctl restart "${'$'}{SERVICE_NAME}"
            if command -v ufw >/dev/null 2>&1; then sudo ufw allow 9119/tcp >/dev/null 2>&1 || true; fi
            echo "Mobile URL: http://$(curl -fsS https://api.ipify.org):9119"
            EOF
            chmod +x setup-vps-dashboard.sh
            ./setup-vps-dashboard.sh
        """.trimIndent()

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#041c1c"))
            setPadding(28, 20, 28, 12)
        }
        val text = TextView(this).apply {
            setTextColor(Color.parseColor("#ffe6cb"))
            textSize = 11f
            this.text = script
        }
        scroll.addView(text)

        AlertDialog.Builder(this)
            .setTitle("VPS Setup Script")
            .setView(scroll)
            .setPositiveButton("Copy") { _, _ ->
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("vps_setup_script", script))
                renderStatusPage("Script copied to clipboard. Paste it in VPS SSH shell.", emptyList())
                renderConnectionHome()
            }
            .setNegativeButton("Back") { _, _ -> renderConnectionHome() }
            .show()
    }

    private fun handleInternalUrl(url: String): Boolean {
        if (!url.startsWith("hermes://")) return false
        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
        when (host) {
            "discover" -> bootstrapDashboardConnection()
            "manual" -> promptForManualEndpoint()
            "saved" -> connectSavedOrManual()
            "script" -> showVpsScriptDialog()
            "menu" -> showHamburgerMenu()
        }
        return true
    }

    private fun showHamburgerMenu() {
        AlertDialog.Builder(this)
            .setTitle("Menu")
            .setItems(arrayOf("Logout")) { _, which ->
                if (which == 0) logoutAndReset()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun logoutAndReset() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        webView.clearHistory()
        webView.clearCache(true)
        renderConnectionHome()
    }

    private fun injectMobileChrome(view: WebView) {
        if (showingConnectionHub) return
        view.evaluateJavascript(
            """
            (function(){
              if(document.getElementById('hermes-mobile-client-style')) return;
              var style=document.createElement('style');
              style.id='hermes-mobile-client-style';
              style.textContent=[
                'html,body,#root{touch-action:pan-x pan-y;-webkit-overflow-scrolling:touch;}',
                'body,*{overscroll-behavior:auto;}',
                'input,textarea,select{font-size:16px!important;}',
                '#hermes-mobile-power{width:34px;height:34px;border-radius:999px;border:1px solid rgba(255,230,203,.45);background:rgba(4,28,28,.25);color:#ffe6cb;display:inline-flex;align-items:center;justify-content:center;text-decoration:none;backdrop-filter:blur(6px);-webkit-backdrop-filter:blur(6px);font-size:17px;line-height:1;box-sizing:border-box;}',
                '#hermes-mobile-power:hover,#hermes-mobile-power:active{background:rgba(255,215,94,.12);border-color:rgba(255,215,94,.75);color:#ffd75e;}'
              ].join('\n');
              document.head.appendChild(style);

              if(document.getElementById('hermes-mobile-power')) return;
              var b=document.createElement('a');
              b.id='hermes-mobile-power';
              b.href='hermes://menu';
              b.setAttribute('aria-label','Power');
              b.setAttribute('title','Power');
              b.innerHTML='<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 2v10"/><path d="M18.4 6.6a9 9 0 1 1-12.8 0"/></svg>';

              var walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);
              var brandText=null;
              while(walker.nextNode()){
                var t=(walker.currentNode.nodeValue||'').replace(/\s+/g,' ').trim().toUpperCase();
                if(t.indexOf('HERMES')!==-1 && t.indexOf('AGENT')!==-1){
                  brandText=walker.currentNode.parentElement;
                  break;
                }
              }
              var host=brandText;
              while(host && host!==document.body){
                var r=host.getBoundingClientRect();
                if(r.width>60 && r.height>20) break;
                host=host.parentElement;
              }
              if(host && host!==document.body){
                host.style.display='flex';
                host.style.alignItems='center';
                host.style.justifyContent='space-between';
                host.style.gap='10px';
                host.appendChild(b);
              }else{
                b.style.position='fixed';
                b.style.top='12px';
                b.style.left='12px';
                b.style.zIndex='99999';
                document.body.appendChild(b);
              }
            })();
            """.trimIndent(),
            null,
        )
    }

    private fun sendHermesMobileText(text: String) {
        if (text.isEmpty()) return
        webView.post {
            val json = org.json.JSONObject.quote(text)
            webView.evaluateJavascript(
                "window.HermesMobileNativeInput&&window.HermesMobileNativeInput.text($json)",
                null,
            )
        }
    }

    private fun sendHermesMobileKey(key: String) {
        webView.post {
            val json = org.json.JSONObject.quote(key)
            webView.evaluateJavascript(
                "window.HermesMobileNativeInput&&window.HermesMobileNativeInput.key($json)",
                null,
            )
        }
    }

    private fun sendHermesMobileScroll(deltaY: Float) {
        webView.post {
            webView.evaluateJavascript(
                "window.HermesMobileNativeInput&&window.HermesMobileNativeInput.scroll(${deltaY.toInt()})",
                null,
            )
        }
    }

    private fun injectMobileInputBridge(view: WebView) {
        if (showingConnectionHub) return
        view.evaluateJavascript(
            """
            (function(){
              if(window.HermesMobileNativeInput) return;

              function terminalTarget(){
                return document.querySelector('.xterm-helper-textarea')
                  || document.querySelector('.xterm textarea')
                  || document.querySelector('.xterm');
              }

              function focusTerminal(){
                var target=terminalTarget();
                if(target && target.focus) target.focus();
                return target;
              }

              function fire(target,type,init){
                var ev;
                try {
                  ev = new InputEvent(type, Object.assign({bubbles:true,cancelable:true,composed:true}, init || {}));
                } catch (_) {
                  ev = document.createEvent('Event');
                  ev.initEvent(type,true,true);
                  Object.assign(ev, init || {});
                }
                target.dispatchEvent(ev);
              }

              function keyEvent(target,type,key,code,keyCode,extra){
                var ev;
                var opts = Object.assign({
                  key:key,
                  code:code,
                  bubbles:true,
                  cancelable:true,
                  composed:true,
                  keyCode:keyCode,
                  which:keyCode
                }, extra || {});
                try {
                  ev = new KeyboardEvent(type, opts);
                } catch (_) {
                  ev = document.createEvent('KeyboardEvent');
                  ev.initKeyboardEvent(type,true,true,window,key,0,false,false,false,false);
                }
                target.dispatchEvent(ev);
              }

              function sendText(text){
                var target=focusTerminal();
                if(!target) return false;
                if(target.tagName === 'TEXTAREA' || target.tagName === 'INPUT'){
                  target.value = text;
                  fire(target,'beforeinput',{inputType:'insertText',data:text});
                  fire(target,'input',{inputType:'insertText',data:text});
                  target.value = '';
                  return true;
                }
                keyEvent(target,'keydown',text,'',text.charCodeAt(0));
                keyEvent(target,'keypress',text,'',text.charCodeAt(0));
                keyEvent(target,'keyup',text,'',text.charCodeAt(0));
                return true;
              }

              function sendKey(key){
                var target=focusTerminal();
                if(!target) return false;
                var spec={
                  backspace:['Backspace','Backspace',8,'deleteContentBackward'],
                  delete:['Delete','Delete',46,'deleteContentForward'],
                  enter:['Enter','Enter',13,'insertLineBreak'],
                  up:['ArrowUp','ArrowUp',38,null],
                  down:['ArrowDown','ArrowDown',40,null],
                  left:['ArrowLeft','ArrowLeft',37,null],
                  right:['ArrowRight','ArrowRight',39,null]
                }[key];
                if(!spec) return false;
                keyEvent(target,'keydown',spec[0],spec[1],spec[2]);
                if(spec[3]) fire(target,'beforeinput',{inputType:spec[3],data:null});
                if(spec[3]) fire(target,'input',{inputType:spec[3],data:null});
                keyEvent(target,'keyup',spec[0],spec[1],spec[2]);
                if(target.value !== undefined) target.value = '';
                return true;
              }

              function dispatchWheel(target,deltaY){
                if(!target) return false;
                var ev;
                try {
                  ev = new WheelEvent('wheel',{
                    deltaY:deltaY,
                    wheelDelta:-deltaY,
                    bubbles:true,
                    cancelable:true,
                    composed:true
                  });
                } catch (_) {
                  ev = document.createEvent('WheelEvent');
                  ev.initEvent('wheel',true,true);
                  ev.deltaY = deltaY;
                  ev.wheelDelta = -deltaY;
                }
                target.dispatchEvent(ev);
                return true;
              }

              function dispatchShiftScroll(target,deltaY){
                if(!target) return false;
                var down = deltaY > 0;
                var key = down ? 'ArrowDown' : 'ArrowUp';
                var code = down ? 'ArrowDown' : 'ArrowUp';
                var keyCode = down ? 40 : 38;
                var steps = Math.max(1, Math.min(8, Math.round(Math.abs(deltaY) / 24)));
                for(var i=0;i<steps;i++){
                  keyEvent(target,'keydown',key,code,keyCode,{shiftKey:true});
                  keyEvent(target,'keyup',key,code,keyCode,{shiftKey:true});
                }
                return true;
              }

              function scrollTerminal(deltaY){
                var targets=[
                  document.querySelector('.xterm-helper-textarea'),
                  document.querySelector('.xterm-screen'),
                  document.querySelector('.xterm-viewport'),
                  document.querySelector('.xterm')
                ].filter(Boolean);
                if(targets.length === 0) return false;
                targets.forEach(function(target){ dispatchWheel(target, deltaY); });
                dispatchShiftScroll(targets[0], deltaY);
                return true;
              }

              window.HermesMobileNativeInput = {
                text: sendText,
                key: sendKey,
                scroll: scrollTerminal
              };
            })();
            """.trimIndent(),
            null,
        )
    }

    private fun localIpv4Address(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                if (!name.startsWith("wlan") && !name.startsWith("eth") && !name.startsWith("en")) continue
                val addresses = iface.inetAddresses
                for (addr in addresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}

class HermesWebView(context: Context) : WebView(context) {
    interface MobileInputSink {
        fun sendText(text: String)
        fun sendKey(key: String)
        fun scroll(deltaY: Float)
    }

    var mobileInputSink: MobileInputSink? = null
    private var lastTouchY: Float? = null
    private var scrollDebt = 0f

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN

        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val value = text?.toString().orEmpty()
                if (value.isNotEmpty()) mobileInputSink?.sendText(value)
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                val count = beforeLength.coerceAtLeast(1)
                repeat(count) { mobileInputSink?.sendKey("backspace") }
                return true
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                return deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action != KeyEvent.ACTION_DOWN) return true
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> mobileInputSink?.sendKey("backspace")
                    KeyEvent.KEYCODE_FORWARD_DEL -> mobileInputSink?.sendKey("delete")
                    KeyEvent.KEYCODE_ENTER -> mobileInputSink?.sendKey("enter")
                    KeyEvent.KEYCODE_DPAD_UP -> mobileInputSink?.sendKey("up")
                    KeyEvent.KEYCODE_DPAD_DOWN -> mobileInputSink?.sendKey("down")
                    KeyEvent.KEYCODE_DPAD_LEFT -> mobileInputSink?.sendKey("left")
                    KeyEvent.KEYCODE_DPAD_RIGHT -> mobileInputSink?.sendKey("right")
                    else -> event.unicodeChar.takeIf { it > 0 }?.let { mobileInputSink?.sendText(it.toChar().toString()) }
                }
                return true
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                mobileInputSink?.sendKey("enter")
                return true
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = event.y
                scrollDebt = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                val previous = lastTouchY
                if (previous != null) {
                    val delta = previous - event.y
                    lastTouchY = event.y
                    scrollDebt += delta
                    if (kotlin.math.abs(scrollDebt) >= 24f) {
                        mobileInputSink?.scroll(scrollDebt)
                        scrollDebt = 0f
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastTouchY = null
                scrollDebt = 0f
            }
        }
        return super.dispatchTouchEvent(event)
    }
}
