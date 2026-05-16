package com.zyyme.workdayalarmclock

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class WebActivity : AppCompatActivity() {
    companion object {
        private const val CONTROL_URL = "http://127.0.0.1:8080"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var titleView: TextView
    private lateinit var addressEdit: EditText
    private var currentUrl = CONTROL_URL
    private var currentTitle: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web)

        webView = findViewById(R.id.web_view)
        progressBar = findViewById(R.id.web_progress)
        titleView = findViewById(R.id.tv_web_title)
        addressEdit = findViewById(R.id.et_web_address)
        webView.requestFocus()

        findViewById<ImageButton>(R.id.btn_web_back).setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                finish()
            }
        }
        findViewById<ImageButton>(R.id.btn_web_refresh).setOnClickListener {
            webView.reload()
        }
        titleView.setOnClickListener {
            showAddressEditor()
        }
        addressEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && addressEdit.visibility == View.VISIBLE) {
                hideAddressEditor()
            }
        }
        addressEdit.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_NULL ||
                (event?.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                loadAddressFromInput()
                true
            } else {
                false
            }
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
                currentUrl = url ?: currentUrl
                currentTitle = null
                showDisplayTitle()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                currentUrl = url ?: currentUrl
                currentTitle = view?.title?.takeIf { it.isNotBlank() }
                showDisplayTitle()
            }

            @Suppress("OverridingDeprecatedMember")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                Toast.makeText(this@WebActivity, "Web控制台打开失败", Toast.LENGTH_SHORT).show()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }

        if (savedInstanceState == null) {
            webView.loadUrl(CONTROL_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    private fun showDisplayTitle() {
        if (addressEdit.visibility == View.VISIBLE) {
            return
        }
        titleView.text = currentTitle ?: currentUrl
    }

    private fun showAddressEditor() {
        addressEdit.setText(currentUrl)
        titleView.visibility = View.GONE
        addressEdit.visibility = View.VISIBLE
        addressEdit.requestFocus()
        addressEdit.selectAll()
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(addressEdit, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideAddressEditor() {
        addressEdit.visibility = View.GONE
        titleView.visibility = View.VISIBLE
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(addressEdit.windowToken, 0)
        showDisplayTitle()
    }

    private fun loadAddressFromInput() {
        val url = normalizeUrl(addressEdit.text.toString())
        if (url.isEmpty()) {
            return
        }
        currentUrl = url
        currentTitle = null
        addressEdit.setText(url)
        hideAddressEditor()
        webView.loadUrl(url)
    }

    private fun normalizeUrl(input: String): String {
        val url = input.trim()
        if (url.isEmpty()) {
            return ""
        }
        return if (url.contains("://")) url else "http://$url"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
