package com.clipdownloader

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.clipdownloader.download.DouyinWebCookieSeeder

/**
 * 抖音网页版登录对话框：内嵌 WebView 打开桌面站，用户扫码或用手机号登录。
 *
 * 登录态落在 WebView 的 cookie 存储里，与 [DouyinWebCookieSeeder] 共用并持久保存；
 * 「主页下载」的抖音通道带上这些 cookie 后，作品接口即可翻页获取全部作品
 * （游客只能拿到首页）。对话框关闭时销毁 WebView。
 */
object DouyinLoginDialog {

    @SuppressLint("SetJavaScriptEnabled")
    fun show(activity: Activity, onChanged: () -> Unit) {
        val context = activity
        val webView = WebView(context)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = DouyinWebCookieSeeder.DESKTOP_UA
            setSupportMultipleWindows(false)
            // 桌面版页面没有移动端 viewport：按内容宽度排版并整体缩放到屏宽，避免被裁切
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        var dialog: Dialog? = null
        webView.webViewClient = object : WebViewClient() {
            // 站点会用 snssdk1128:// 等尝试唤起 App，只放行 http(s)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme != "https" && request.url.scheme != "http"

            override fun onPageFinished(view: WebView, url: String?) {
                val cookie = DouyinWebCookieSeeder.currentCookie()
                if (DouyinWebCookieSeeder.isLoggedIn(cookie)) {
                    CookieManager.getInstance().flush()
                    Toast.makeText(context, "已登录抖音，重新解析即可获取全部作品", Toast.LENGTH_LONG).show()
                    onChanged()
                    dialog?.dismiss()
                }
            }
        }

        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        // 不透明背景：窗口与内容同色，避免下层 Activity 的标题栏透上来
        val typed = context.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
        val background = typed.getColor(0, 0xFF000000.toInt())
        typed.recycle()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(8), dp(8))
        }
        bar.addView(TextView(context).apply {
            text = "登录抖音网页版（扫码或手机号），成功后自动关闭"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(Button(context).apply {
            text = "完成"
            setOnClickListener { dialog?.dismiss() }
        })
        root.addView(bar)
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val d = Dialog(context, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar).apply {
            setContentView(root)
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window?.setBackgroundDrawable(ColorDrawable(background))
            setOnDismissListener {
                onChanged()
                webView.stopLoading()
                webView.destroy()
            }
        }
        dialog = d
        webView.loadUrl(DouyinWebCookieSeeder.DOMAIN)
        d.show()
    }
}
