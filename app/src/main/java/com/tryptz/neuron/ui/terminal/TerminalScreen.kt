package com.tryptz.neuron.ui.terminal

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.termux.terminal.KeyHandler
import com.termux.view.TerminalView
import com.tryptz.neuron.terminal.BootstrapProgress
import com.tryptz.neuron.terminal.InstallState
import com.tryptz.neuron.terminal.ModifierKeys
import com.tryptz.neuron.terminal.NeuronTerminalViewClient

private val TerminalBackground = Color(0xFF0D1117)

/** View tag remembering the applied font size, so recomposition doesn't re-layout the grid. */
private val FONT_TAG = com.tryptz.neuron.R.id.terminal_font_px

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    pendingInput: com.tryptz.neuron.terminal.TerminalInput?,
    onPendingInputConsumed: () -> Unit,
    onAskNeuron: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val fontSp by viewModel.fontSp.collectAsState()
    val keepAwake by viewModel.keepAwake.collectAsState()
    val vibrateOnBell by viewModel.vibrateOnBell.collectAsState()
    val modifierStates by viewModel.modifierStates.collectAsState()
    val message by viewModel.message.collectAsState()
    val manager = viewModel.manager

    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    // "Open in terminal" from chat: typed once the shell runs, never with Enter.
    LaunchedEffect(pendingInput) {
        val input = pendingInput ?: return@LaunchedEffect
        viewModel.awaitReady()
        manager.typeIntoCurrent(input)
        onPendingInputConsumed()
    }

    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants -> if (grants.values.all { it }) viewModel.setUpStorage() }

    Scaffold(
        containerColor = TerminalBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        state.sessions.forEachIndexed { i, s ->
                            FilterChip(
                                selected = i == state.current,
                                onClick = { viewModel.switchTo(i) },
                                label = { Text(s.title.take(18), fontFamily = FontFamily.Monospace) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close, "Close session",
                                        modifier = Modifier.size(16.dp).clickable { viewModel.close(i) }
                                    )
                                }
                            )
                        }
                        if (state.sessions.isEmpty()) Text("Terminal")
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { viewModel.newSession() }, enabled = state.install == InstallState.Ready) {
                        Icon(Icons.Default.Add, "New session")
                    }
                    IconButton(onClick = {
                        val cm = ContextCompat.getSystemService(context, ClipboardManager::class.java)
                        val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (!text.isNullOrEmpty()) manager.currentSession?.emulator?.paste(text)
                    }) { Icon(Icons.Default.ContentPaste, "Paste") }
                    IconButton(onClick = { onAskNeuron(manager.transcriptTail()) }) {
                        Icon(Icons.Default.AutoAwesome, "Ask Neuron about this output")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Copy screen") },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                                onClick = {
                                    menuOpen = false
                                    val text = manager.transcriptTail(maxLines = 2000, maxChars = 200_000)
                                    ContextCompat.getSystemService(context, ClipboardManager::class.java)
                                        ?.setPrimaryClip(ClipData.newPlainText("terminal", text))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Set up storage (~/storage)") },
                                leadingIcon = { Icon(Icons.Default.Folder, null) },
                                onClick = {
                                    menuOpen = false
                                    storagePermission.launch(
                                        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Keep awake (wake lock)") },
                                leadingIcon = { Icon(Icons.Default.Bolt, null) },
                                trailingIcon = { Checkbox(checked = keepAwake, onCheckedChange = null) },
                                onClick = { viewModel.setKeepAwake(!keepAwake) }
                            )
                            DropdownMenuItem(
                                text = { Text("Vibrate on bell") },
                                leadingIcon = { Icon(Icons.Default.Vibration, null) },
                                trailingIcon = { Checkbox(checked = vibrateOnBell, onCheckedChange = null) },
                                onClick = { viewModel.setVibrateOnBell(!vibrateOnBell) }
                            )
                            DropdownMenuItem(
                                text = { Text("Don't let Android kill sessions") },
                                leadingIcon = { Icon(Icons.Default.BatteryChargingFull, null) },
                                onClick = {
                                    menuOpen = false
                                    requestUnrestrictedBattery(context)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Close this session") },
                                leadingIcon = { Icon(Icons.Default.Close, null) },
                                enabled = state.sessions.isNotEmpty(),
                                onClick = { menuOpen = false; viewModel.close(state.current) }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF161B22))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(TerminalBackground)
                // The keyboard pushes the terminal up instead of covering the prompt.
                .imePadding()
        ) {
            state.userlandUnavailableReason?.let { reason ->
                Text(
                    reason,
                    color = Color(0xFFF0B72F),
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF2A2100)).padding(8.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            when (val install = state.install) {
                InstallState.Checking -> InstallPanel("Checking environment…", null, null)
                is InstallState.Installing -> InstallPanel(
                    title = "Setting up the Termux environment",
                    detail = when (val p = install.progress) {
                        null -> "Unpacking bootstrap…"
                        is BootstrapProgress.Extracting -> "${p.entries} files · ${p.currentPath.takeLast(48)}"
                        BootstrapProgress.Linking -> "Creating links…"
                        BootstrapProgress.Done -> "Finishing…"
                    },
                    onRetry = null
                )
                is InstallState.Failed -> InstallPanel(
                    title = "Setup failed",
                    detail = install.message,
                    onRetry = viewModel::retryInstall
                )
                InstallState.Ready -> {
                    val modifiers = viewModel.modifiers
                    AndroidView(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        factory = { ctx ->
                            TerminalView(ctx, null).apply {
                                isFocusable = true
                                isFocusableInTouchMode = true
                                setTypeface(Typeface.MONOSPACE)
                                setTerminalViewClient(
                                    NeuronTerminalViewClient(
                                        context = ctx,
                                        modifiers = modifiers,
                                        viewProvider = { terminalView },
                                        onFontScaled = { scale -> viewModel.stepFont(scale > 1f) }
                                    )
                                )
                                terminalView = this
                                manager.onScreenUpdate = { onScreenUpdated() }
                            }
                        },
                        update = { view ->
                            val px = spToPx(view, fontSp)
                            if (view.getTag(FONT_TAG) != px) {
                                view.setTextSize(px)
                                view.setTag(FONT_TAG, px)
                            }
                            val session = manager.currentSession
                            if (session != null && view.currentSession !== session) {
                                view.attachSession(session)
                                manager.deliverPendingInput()
                            }
                            view.onScreenUpdated()
                            // state.revision is read so session switches recompose this block.
                            state.revision
                            view.requestFocus()
                        }
                    )
                    ExtraKeysRow(
                        rows = DEFAULT_EXTRA_KEYS,
                        modifierStates = modifierStates,
                        onKey = { key -> terminalView?.let { sendExtraKey(it, key, modifiers) } }
                    )
                }
            }
        }

        message?.let { msg ->
            LaunchedEffect(msg) { kotlinx.coroutines.delay(2500); viewModel.clearMessage() }
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
                Snackbar { Text(msg) }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { manager.onScreenUpdate = null }
    }
}

@Composable
private fun InstallPanel(title: String, detail: String?, onRetry: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (onRetry == null) CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(title, color = Color(0xFFE6EDF3), style = MaterialTheme.typography.titleMedium)
        detail?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Color(0xFF8B949E), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        onRetry?.let {
            Spacer(Modifier.height(16.dp))
            Button(onClick = it) { Text("Retry") }
        }
    }
}

private fun spToPx(view: TerminalView, sp: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), view.resources.displayMetrics).toInt()

/** Extra keys go through the same paths as hardware keys, so modifiers combine correctly. */
private fun sendExtraKey(view: TerminalView, key: ExtraKey, modifiers: ModifierKeys) {
    when (key) {
        is ExtraKey.Modifier -> modifiers.tap(key.key)
        is ExtraKey.Special -> {
            var mod = 0
            if (modifiers.consume(ModifierKeys.Key.CTRL)) mod = mod or KeyHandler.KEYMOD_CTRL
            if (modifiers.consume(ModifierKeys.Key.ALT)) mod = mod or KeyHandler.KEYMOD_ALT
            if (modifiers.consume(ModifierKeys.Key.SHIFT)) mod = mod or KeyHandler.KEYMOD_SHIFT
            view.handleKeyCode(key.keyCode, mod)
        }
        is ExtraKey.Char -> {
            val ctrl = modifiers.consume(ModifierKeys.Key.CTRL)
            val alt = modifiers.consume(ModifierKeys.Key.ALT)
            key.text.codePoints().forEach { cp -> view.inputCodePoint(cp, ctrl, alt) }
        }
    }
}

@SuppressLint("BatteryLife") // The user asked for it from the menu.
private fun requestUnrestrictedBattery(context: android.content.Context) {
    val pm = ContextCompat.getSystemService(context, PowerManager::class.java)
    if (pm?.isIgnoringBatteryOptimizations(context.packageName) == true) return
    context.startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
