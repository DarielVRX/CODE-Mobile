package com.codemobile.ui

import android.view.LayoutInflater
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.lifecycle.LifecycleOwner
import com.codemobile.R
import java.io.File

/**
 * Panel de preview HTML a pantalla completa (WebView). Extraído tal cual de
 * EditorActivity (showHtmlPreview/closeHtmlPreview/
 * setupHtmlPreviewBackHandling), sin cambiar ninguna regla: el callback de
 * Back se registra deshabilitado y solo se habilita mientras el preview
 * está visible, para que intercepte Back antes que cualquier otro callback
 * (navegación de explorador, guard de salida) — ver el comentario de orden
 * LIFO que EditorActivity sigue documentando en su propio onCreate: este
 * controller debe ser el ÚLTIMO en llamar a [setupBackHandling] dentro de
 * esa secuencia, para preservar ese orden.
 */
class HtmlPreviewController(
    private val htmlPreviewContainer: FrameLayout,
    private val layoutInflater: LayoutInflater,
    private val onBackPressedDispatcher: OnBackPressedDispatcher,
    private val lifecycleOwner: LifecycleOwner
) {

    private var htmlPreviewWebView: WebView? = null
    private var lastHtmlPreviewSource: String = ""
    private var lastHtmlPreviewBaseUrl: String? = null
    private var htmlPreviewBackCallback: OnBackPressedCallback? = null

    fun setupBackHandling() {
        htmlPreviewBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                closeHtmlPreview()
            }
        }
        onBackPressedDispatcher.addCallback(lifecycleOwner, htmlPreviewBackCallback!!)
    }

    fun showHtmlPreview(html: String, htmlFile: File? = null) {
        lastHtmlPreviewSource = html
        // baseUrl = carpeta del propio HTML, para que <link href="estilo.css">
        // y <script src="script.js"> (rutas relativas) resuelvan contra ella.
        // No se activa setAllowFileAccessFromFileURLs/UniversalAccessFromFileURLs:
        // no hace falta para este caso (solo resolución de rutas relativas, no
        // que el JS de la página acceda a otras rutas file:// fuera de su base).
        lastHtmlPreviewBaseUrl = htmlFile?.parentFile?.let { "file://${it.absolutePath}/" }
        if (htmlPreviewContainer.childCount == 0) {
            val view = layoutInflater.inflate(R.layout.view_html_preview, htmlPreviewContainer, false)
            val webView = view.findViewById<WebView>(R.id.htmlPreviewWebView)
            webView.settings.javaScriptEnabled = true
            htmlPreviewWebView = webView

            view.findViewById<TextView>(R.id.htmlPreviewReload).setOnClickListener {
                webView.loadDataWithBaseURL(lastHtmlPreviewBaseUrl, lastHtmlPreviewSource, "text/html", "utf-8", null)
            }
            view.findViewById<TextView>(R.id.htmlPreviewClose).setOnClickListener {
                closeHtmlPreview()
            }
            htmlPreviewContainer.addView(view)
        }
        htmlPreviewContainer.visibility = View.VISIBLE
        htmlPreviewBackCallback?.isEnabled = true
        htmlPreviewWebView?.loadDataWithBaseURL(lastHtmlPreviewBaseUrl, html, "text/html", "utf-8", null)
    }

    fun closeHtmlPreview() {
        htmlPreviewContainer.visibility = View.GONE
        htmlPreviewBackCallback?.isEnabled = false
    }
}
