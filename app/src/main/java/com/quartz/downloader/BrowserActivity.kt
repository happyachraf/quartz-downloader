package com.quartz.downloader

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.view.View
import android.webkit.*
import android.widget.*
import org.json.*
import java.util.UUID

/** Website content and trusted Quartz controls use separate WebViews. */
class BrowserActivity : Activity() {
    private lateinit var web:WebView
    private lateinit var header:WebView
    private lateinit var footer:WebView
    private lateinit var root:LinearLayout
    private var provider="youtube"
    private var query=""
    private var address=""
    private var compact=false
    private var headerReady=false
    private var footerReady=false
    private var fullView:View?=null
    private var fullCallback:WebChromeClient.CustomViewCallback?=null
    private var chrome:WebChromeClient?=null
    private var progress=0
    private var failure=""
    private val sites=linkedMapOf("youtube" to "https://m.youtube.com", "tiktok" to "https://www.tiktok.com", "x" to "https://x.com", "facebook" to "https://m.facebook.com", "instagram" to "https://www.instagram.com", "web" to "https://www.google.com")
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(saved:Bundle?){
        super.onCreate(saved)
        window.statusBarColor=Color.rgb(16,14,23);window.navigationBarColor=Color.rgb(16,14,23)
        provider=intent.getStringExtra("provider")?.takeIf{it in sites} ?: "youtube"
        query=saved?.getString("query") ?: intent.getStringExtra("query").orEmpty()
        address=saved?.getString("address") ?: intent.getStringExtra("url").orEmpty()
        compact=intent.hasExtra("account") || address.isNotBlank()
        if(address.isNotBlank())provider=providerFor(address)
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(16,14,23))}
        header=trusted("header");footer=trusted("footer")
        web=WebView(this).apply {
            setBackgroundColor(Color.rgb(16,14,23));settings.javaScriptEnabled=true;settings.domStorageEnabled=true
            settings.allowFileAccess=false;settings.allowContentAccess=false;settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.mediaPlaybackRequiresUserGesture=true;settings.setSupportMultipleWindows(false);settings.useWideViewPort=true;settings.loadWithOverviewMode=true
            CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(this,true)
            webViewClient=object:WebViewClient(){
                override fun shouldOverrideUrlLoading(view:WebView,r:WebResourceRequest):Boolean{if(r.url.scheme!="https" && r.isForMainFrame)Toast.makeText(this@BrowserActivity,"Use the website version of this link",Toast.LENGTH_SHORT).show();return r.url.scheme!="https"}
                override fun onPageStarted(view:WebView,url:String,icon:android.graphics.Bitmap?){address=url;failure="";this@BrowserActivity.progress=0;provider=providerFor(url);if(isVideo(url))compact=true;render()}
                override fun onPageFinished(view:WebView,url:String){address=url;CookieManager.getInstance().flush();render()}
                override fun onReceivedError(view:WebView,r:WebResourceRequest,e:WebResourceError){if(r.isForMainFrame){failure="Could not load this page. Check your connection and tap Reload.";render()}}
                override fun onReceivedHttpError(view:WebView,r:WebResourceRequest,e:WebResourceResponse){if(r.isForMainFrame && e.statusCode>=500){failure="This website is unavailable. Tap Reload to try again.";render()}}
            }
            chrome=object:WebChromeClient(){
                override fun onProgressChanged(view:WebView,p:Int){this@BrowserActivity.progress=p;render()}
                override fun onShowCustomView(view:View,callback:CustomViewCallback){if(fullView!=null){callback.onCustomViewHidden();return};fullView=view;fullCallback=callback;root.visibility=View.GONE;addContentView(view,android.view.ViewGroup.LayoutParams(-1,-1))}
                override fun onHideCustomView(){fullView?.let{(it.parent as? android.view.ViewGroup)?.removeView(it)};fullView=null;root.visibility=View.VISIBLE;fullCallback?.onCustomViewHidden();fullCallback=null}
            };webChromeClient=chrome
        }
        root.addView(header,LinearLayout.LayoutParams(-1,dp(if(compact)112 else 245)))
        root.addView(web,LinearLayout.LayoutParams(-1,0,1f));root.addView(footer,LinearLayout.LayoutParams(-1,dp(if(compact)164 else 68)))
        setContentView(root);ScreenInsets.apply(this,root)
        web.setDownloadListener { url,ua,disposition,mime,_ ->
            try{
                val safe=UrlPolicy.normalize(url);val filename=URLUtil.guessFileName(safe,disposition,mime)
                val request=DownloadManager.Request(Uri.parse(safe)).setTitle(filename).setMimeType(mime).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS,"Quartz Downloader/$filename")
                CookieManager.getInstance().getCookie(safe)?.let{request.addRequestHeader("Cookie",it)};request.addRequestHeader("User-Agent",ua);web.url?.let{request.addRequestHeader("Referer",it)}
                val id=(getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                DownloadStore.add(this,JSONObject().put("id",UUID.randomUUID().toString()).put("type","direct").put("dmId",id).put("title",filename).put("filename",filename).put("url",safe).put("state","downloading").put("progress",0).put("message","Downloading in browser").put("createdAt",System.currentTimeMillis()))
                Toast.makeText(this,"Download started",Toast.LENGTH_SHORT).show()
            }catch(_:Exception){Toast.makeText(this,"Could not download this file",Toast.LENGTH_SHORT).show()}
        }
        if(saved==null || web.restoreState(saved)==null)navigate(address.ifBlank{searchUrl(provider,query)})
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun trusted(part:String):WebView=WebView(this).apply {
        setBackgroundColor(Color.rgb(16,14,23));settings.javaScriptEnabled=true;settings.allowFileAccess=false
        addJavascriptInterface(Controls(),"BrowserNative")
        webViewClient=object:WebViewClient(){
            override fun shouldOverrideUrlLoading(view:WebView,r:WebResourceRequest)=true
            override fun onPageFinished(view:WebView,url:String){if(part=="header")headerReady=true else footerReady=true;render()}
        };loadUrl("file:///android_asset/web/browser-$part.html")
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun render(){
        if(!::header.isInitialized || !::footer.isInitialized)return
        val landscape=resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE
        header.layoutParams?.height=dp(if(landscape){if(compact)72 else 134}else if(compact)112 else 245);header.requestLayout()
        val actions=compact && !landscape && !Regex("/(?:accounts/)?login|/i/flow/",RegexOption.IGNORE_CASE).containsMatchIn(address)
        footer.layoutParams?.height=dp(if(actions)164 else 68);footer.requestLayout()
        val value=JSONObject().put("provider",provider).put("query",query).put("url",address).put("compact",compact).put("actions",actions).put("landscape",landscape).put("progress",progress).put("failure",failure)
        if(headerReady)header.evaluateJavascript("window.BrowserUI.render($value)",null);if(footerReady)footer.evaluateJavascript("window.BrowserUI.render($value)",null)
    }
    private fun navigate(raw:String){try{val safe=UrlPolicy.normalize(raw);address=safe;web.loadUrl(safe)}catch(_:Exception){Toast.makeText(this,"Enter a secure website link",Toast.LENGTH_SHORT).show()}}
    private fun providerFor(url:String):String {
        val host=Uri.parse(url).host.orEmpty()
        return when{host.endsWith("youtube.com")||host=="youtu.be"->"youtube";host.endsWith("tiktok.com")->"tiktok";host.endsWith("x.com")||host.endsWith("twitter.com")->"x";host.endsWith("facebook.com")||host=="fb.watch"->"facebook";host.endsWith("instagram.com")->"instagram";else->"web"}
    }
    private fun searchUrl(site:String,text:String):String {
        if(text.isBlank())return sites[site] ?: sites.getValue("youtube")
        if(text.startsWith("https://"))return text
        val q=Uri.encode(text)
        return when(site){"youtube"->"https://m.youtube.com/results?search_query=$q";"tiktok"->"https://www.tiktok.com/search?q=$q";"x"->"https://x.com/search?q=$q&f=video";"facebook"->"https://www.facebook.com/search/videos/?q=$q";"instagram"->"https://www.instagram.com/explore/search/keyword/?q=$q";else->"https://www.google.com/search?tbm=vid&q=$q"}
    }
    private fun isVideo(url:String)=Regex("(?:[?&]v=|youtu\\.be/|/shorts/|/status/\\d+|/reels?/|/videos?/|fb\\.watch/|/p/[^/]+)",RegexOption.IGNORE_CASE).containsMatchIn(url)
    private fun download(url:String){try{val safe=UrlPolicy.normalize(url);CookieManager.getInstance().flush();startActivity(Intent(this,ShareActivity::class.java).putExtra("quartz_url",safe))}catch(_:Exception){Toast.makeText(this,"Open the video's page first",Toast.LENGTH_SHORT).show()}}
    private fun currentVideo(){
        val url=web.url ?: address;if(isVideo(url)){download(url);return}
        // Read nearby public permalinks. Website JavaScript never receives a native bridge.
        web.evaluateJavascript("""(() => {const videos=[...document.querySelectorAll('video')];const active=videos.find(v=>!v.paused)||videos.find(v=>{const b=v.getBoundingClientRect();return b.top<innerHeight&&b.bottom>0});const scope=active?.closest('article,[role=article]')||active?.parentElement?.parentElement||document;return [...new Set([...scope.querySelectorAll('a[href]')].map(a=>a.href).filter(u=>/^https:\/\//.test(u)&&/(\/status\/\d+|\/reels?\/|\/videos?\/|\/shorts\/|[?&]v=|\/p\/[^/]+)/.test(u)))].slice(0,12)})()""") { raw ->
            try{val links=JSONArray(raw);if(links.length()==1)download(links.getString(0))
                else if(links.length()>1)AlertDialog.Builder(this).setTitle("Choose the video link").setItems(Array(links.length()){links.getString(it)}){_,i->download(links.getString(i))}.setNegativeButton("Cancel",null).show()
                else Toast.makeText(this,"Open the video's post, then tap Download this video",Toast.LENGTH_LONG).show()
            }catch(_:Exception){Toast.makeText(this,"Open the video's post first",Toast.LENGTH_LONG).show()}
        }
    }
    inner class Controls {
        @JavascriptInterface fun search(site:String,text:String)=runOnUiThread{provider=site.takeIf{it in sites} ?: "youtube";query=text.take(2048);compact=false;navigate(searchUrl(provider,query));render()}
        @JavascriptInterface fun site(site:String)=runOnUiThread{provider=site.takeIf{it in sites} ?: "youtube";navigate(searchUrl(provider,query));render()}
        @JavascriptInterface fun action(action:String)=runOnUiThread{when(action){"back"->onBackPressed();"reload"->web.reload();"done"->{CookieManager.getInstance().flush();finish()};"download"->currentVideo();"link"->download(web.url ?: address)}}
        @JavascriptInterface fun tab(tab:String)=runOnUiThread{if(tab=="browse"){compact=false;render()}else if(tab in listOf("downloads","music","settings")){startActivity(Intent(this@BrowserActivity,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("quartz_tab",tab));finish()}}
    }
    override fun onBackPressed(){if(fullView!=null){chrome?.onHideCustomView();return};if(web.canGoBack())web.goBack() else finish()}
    override fun onSaveInstanceState(out:Bundle){out.putString("query",query);out.putString("address",address);web.saveState(out);super.onSaveInstanceState(out)}
    override fun onPause(){CookieManager.getInstance().flush();super.onPause()}
    override fun onConfigurationChanged(config:android.content.res.Configuration){super.onConfigurationChanged(config);render();root.requestApplyInsets()}
    override fun onDestroy(){chrome?.onHideCustomView();web.destroy();header.removeJavascriptInterface("BrowserNative");footer.removeJavascriptInterface("BrowserNative");header.destroy();footer.destroy();super.onDestroy()}
}
