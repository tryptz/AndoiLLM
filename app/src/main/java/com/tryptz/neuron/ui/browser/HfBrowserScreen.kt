package com.tryptz.neuron.ui.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.work.WorkInfo
import com.tryptz.neuron.download.HfFileRef
import com.tryptz.neuron.util.formatBytes

/** Trending GGUF models: the useful starting point instead of HF's homepage. */
const val HF_GGUF_HOME = "https://huggingface.co/models?library=gguf&sort=trending"

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled") // huggingface.co is a JS app; nothing is exposed to it.
@Composable
fun HfBrowserScreen(
    onClose: () -> Unit,
    onOpenModels: () -> Unit,
    startUrl: String = HF_GGUF_HOME,
    viewModel: HfBrowserViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val prompt by viewModel.prompt.collectAsState()
    val active by viewModel.active.collectAsState()
    val toast by viewModel.toast.collectAsState()

    var webView by remember { mutableStateOf<WebView?>(null) }
    var title by remember { mutableStateOf("Hugging Face") }
    var url by remember { mutableStateOf(startUrl) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    /** Set when the current page is a .gguf file page, to offer a one-tap download. */
    var pageFile by remember { mutableStateOf<HfFileRef?>(null) }

    BackHandler { if (webView?.canGoBack() == true) webView?.goBack() else onClose() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            Text(
                                Uri.parse(url).let { (it.host ?: "") + (it.path ?: "") },
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close browser") } },
                    actions = {
                        IconButton(onClick = { webView?.goBack() }, enabled = canGoBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                        IconButton(onClick = { webView?.goForward() }, enabled = canGoForward) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward")
                        }
                        IconButton(onClick = { webView?.loadUrl(HF_GGUF_HOME) }) { Icon(Icons.Default.Home, "GGUF models") }
                        IconButton(onClick = { webView?.reload() }) { Icon(Icons.Default.Refresh, "Reload") }
                    }
                )
                if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
        },
        floatingActionButton = {
            pageFile?.let { ref ->
                ExtendedFloatingActionButton(
                    onClick = { viewModel.request(ref) },
                    icon = { Icon(Icons.Default.Download, null) },
                    text = { Text("Download to Neuron") }
                )
            }
        },
        bottomBar = {
            if (active.isNotEmpty()) DownloadsStrip(active, onOpenModels)
        }
    ) { padding ->
        AndroidView(
            modifier = Modifier.padding(padding).fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setSupportMultipleWindows(false)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val target = request.url.toString()
                            val ref = HfFileRef.parse(target)
                            // A raw .gguf download: take it ourselves instead of streaming GBs into the WebView.
                            if (ref != null && "/resolve/" in target) { viewModel.request(ref); return true }
                            // Hugging Face stays in-app; anything else opens in the system browser.
                            if (!HfFileRef.isHuggingFaceHost(target)) {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                                return true
                            }
                            return false
                        }

                        override fun onPageStarted(view: WebView, pageUrl: String, favicon: Bitmap?) {
                            url = pageUrl
                            pageFile = HfFileRef.parse(pageUrl)
                        }

                        override fun doUpdateVisitedHistory(view: WebView, pageUrl: String, isReload: Boolean) {
                            // HF is a single-page app: in-page navigations arrive here, not in onPageStarted.
                            url = pageUrl
                            pageFile = HfFileRef.parse(pageUrl)
                            canGoBack = view.canGoBack()
                            canGoForward = view.canGoForward()
                        }

                        override fun onPageFinished(view: WebView, pageUrl: String) {
                            title = view.title?.removeSuffix(" · Hugging Face")?.ifBlank { null } ?: "Hugging Face"
                            canGoBack = view.canGoBack()
                            canGoForward = view.canGoForward()
                        }
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                        override fun onReceivedTitle(view: WebView, t: String?) {
                            title = t?.removeSuffix(" · Hugging Face")?.ifBlank { null } ?: title
                        }
                    }
                    // HF's download buttons can also arrive as WebView downloads.
                    setDownloadListener { dlUrl, _, _, _, _ ->
                        HfFileRef.parse(dlUrl)?.let { viewModel.request(it) } ?: viewModel.notGguf()
                    }
                    loadUrl(startUrl)
                    webView = this
                }
            },
            onRelease = { it.stopLoading(); it.destroy() }
        )

        prompt?.let { p ->
            DownloadSheet(prompt = p, onConfirm = viewModel::confirm, onDismiss = viewModel::dismiss, onFindMirror = { query ->
                viewModel.dismiss()
                webView?.loadUrl("https://huggingface.co/models?library=gguf&sort=downloads&search=${Uri.encode(query)}")
            })
        }

        toast?.let { msg ->
            LaunchedEffect(msg) { kotlinx.coroutines.delay(3000); viewModel.clearToast() }
            Box(Modifier.fillMaxSize().padding(padding).padding(16.dp), contentAlignment = Alignment.BottomCenter) {
                Snackbar(action = { TextButton(onClick = onOpenModels) { Text("Models") } }) { Text(msg) }
            }
        }
    }
}

@Composable
private fun DownloadSheet(
    prompt: DownloadPrompt,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onFindMirror: (String) -> Unit
) {
    val ref = when (prompt) {
        is DownloadPrompt.Checking -> prompt.ref
        is DownloadPrompt.Ready -> prompt.ref
        is DownloadPrompt.Gated -> prompt.ref
        is DownloadPrompt.Problem -> prompt.ref
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (prompt is DownloadPrompt.Gated) Icons.Default.Lock else Icons.Default.Download, null) },
        title = { Text(ref.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(ref.repo, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (prompt) {
                    is DownloadPrompt.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text("Checking size…")
                    }
                    is DownloadPrompt.Ready -> {
                        val r = prompt.result
                        val size = r.totalBytes?.let { (it).formatBytes() } ?: "unknown size"
                        if (r.parts.size > 1) Text("Split model: ${r.parts.size} parts, $size total. All parts are downloaded; it appears in your library when the last one finishes.")
                        else Text("Size: $size")
                        Text("Free space: ${(r.freeBytes).formatBytes()}")
                        if (r.totalBytes != null && r.totalBytes > r.freeBytes) {
                            Text("Not enough free space.", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    is DownloadPrompt.Gated -> Text(
                        "This repo is gated or private. Neuron downloads without a Hugging Face login, " +
                            "so it can't fetch it. GGUF mirrors (bartowski, unsloth, ggml-org) are usually ungated."
                    )
                    is DownloadPrompt.Problem -> Text(prompt.message, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when (prompt) {
                is DownloadPrompt.Ready -> {
                    val fits = prompt.result.totalBytes?.let { it <= prompt.result.freeBytes } ?: true
                    TextButton(onClick = onConfirm, enabled = fits) { Text("Download") }
                }
                is DownloadPrompt.Gated -> TextButton(onClick = {
                    onFindMirror(ref.repo.substringAfterLast('/').removeSuffix("-GGUF").removeSuffix("-gguf"))
                }) { Text("Find GGUF mirrors") }
                else -> {}
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun DownloadsStrip(active: List<com.tryptz.neuron.download.ActiveDownload>, onOpenModels: () -> Unit) {
    Surface(tonalElevation = 3.dp, onClick = onOpenModels) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            active.take(3).forEach { d ->
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        Text(
                            when (d.state) {
                                WorkInfo.State.FAILED -> "Failed"
                                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "Waiting"
                                else -> if (d.total > 0) "${(d.downloaded).formatBytes()} / ${(d.total).formatBytes()}" else (d.downloaded).formatBytes()
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (d.state == WorkInfo.State.RUNNING) {
                        if (d.total > 0) LinearProgressIndicator(progress = { d.downloaded.toFloat() / d.total }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (active.size > 3) Text("+${active.size - 3} more", style = MaterialTheme.typography.labelSmall)
        }
    }
}
