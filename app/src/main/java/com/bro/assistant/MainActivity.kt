package com.bro.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class BroState(val label: String, val color: Color, val periodMs: Int) {
    IDLE("Idle", Color(0xFF3FA9F5), 2600),
    LISTENING("Listening...", Color(0xFF00E5A8), 900),
    THINKING("Thinking...", Color(0xFFB388FF), 700),
    EXECUTING("Executing...", Color(0xFFFFB300), 500),
    SPEAKING("Speaking...", Color(0xFF40C4FF), 450),
    SUCCESS("Done", Color(0xFF66BB6A), 1200),
    ERROR("Error", Color(0xFFEF5350), 300)
}

data class ChatMessage(val text: String, val fromUser: Boolean)

class JobHolder {
    var job: Job? = null
}

const val GREETING = "Hi, I'm BRO. Tap Mic and talk, or type a message. I'll answer out loud."

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                BroScreen()
            }
        }
    }
}

@Composable
fun BroOrb(state: BroState, modifier: Modifier = Modifier) {
    val color by animateColorAsState(state.color, tween(500), label = "orbColor")
    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            tween(state.periodMs, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(5000, easing = LinearEasing)),
        label = "spin"
    )

    Canvas(modifier = modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val base = size.minDimension / 2f
        val coreR = base * 0.45f * pulse

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.55f), Color.Transparent),
                center = c,
                radius = base
            ),
            radius = base,
            center = c
        )
        drawCircle(color = color, radius = coreR, center = c)
        drawCircle(color = Color.White.copy(alpha = 0.25f), radius = coreR * 0.5f, center = c)

        val ringR = base * 0.72f
        drawArc(
            color = color,
            startAngle = spin,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = Offset(c.x - ringR, c.y - ringR),
            size = Size(ringR * 2f, ringR * 2f),
            style = Stroke(width = 6f, cap = StrokeCap.Round)
        )
        drawArc(
            color = color.copy(alpha = 0.5f),
            startAngle = -spin + 180f,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = Offset(c.x - ringR, c.y - ringR),
            size = Size(ringR * 2f, ringR * 2f),
            style = Stroke(width = 4f, cap = StrokeCap.Round)
        )
    }
}

@Composable
fun VoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = (if (selected) "● " else "○ ") + label,
        fontSize = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp)
    )
}

@Composable
fun BroScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var state by remember { mutableStateOf(BroState.IDLE) }
    var input by remember { mutableStateOf("") }
    var partial by remember { mutableStateOf("") }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var ttsReady by remember { mutableStateOf(false) }
    var afterSpeech by remember { mutableStateOf(BroState.SUCCESS) }
    var showVoices by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf(ChatMessage(GREETING, false)) }
    val jobs = remember { JobHolder() }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // Shows SUCCESS or ERROR for a moment, then returns to IDLE.
    fun showResultThenIdle() {
        scope.launch {
            val shown = afterSpeech
            state = shown
            delay(900)
            if (state == shown) state = BroState.IDLE
        }
    }

    val speaker = remember {
        Speaker(
            context = context,
            onReadyChanged = { ok ->
                ttsReady = ok
                if (!ok) {
                    messages.add(
                        ChatMessage(
                            "Voice output is unavailable. In phone Settings, search for " +
                                "\"Text-to-speech\", choose Speech Services by Google and install " +
                                "English voice data. I will keep replying in text.",
                            false
                        )
                    )
                }
            },
            onSpeakingStarted = { state = BroState.SPEAKING },
            onSpeakingFinished = { showResultThenIdle() }
        )
    }

    // BRO says something: shows it in chat and speaks it when voice is ready.
    fun botSay(text: String, error: Boolean = false) {
        messages.add(ChatMessage(text, false))
        afterSpeech = if (error) BroState.ERROR else BroState.SUCCESS
        if (ttsReady && speaker.speak(text)) {
            state = BroState.SPEAKING
        } else {
            showResultThenIdle()
        }
    }

    fun previewVoice() {
        afterSpeech = BroState.SUCCESS
        if (ttsReady && speaker.speak("Hi, I'm BRO. This is how I sound.")) {
            state = BroState.SPEAKING
        }
    }

    // Demo pipeline (real understanding and actions come in later stages)
    fun handleUserText(text: String) {
        speaker.stop()
        jobs.job?.cancel()
        messages.add(ChatMessage(text, true))
        jobs.job = scope.launch {
            state = BroState.THINKING
            delay(800)
            state = BroState.EXECUTING
            delay(800)
            botSay("(Demo) I received: $text")
        }
    }

    val voice = remember {
        VoiceInput(
            context = context,
            onReady = { state = BroState.LISTENING },
            onPartial = { partial = it },
            onFinalText = { text ->
                partial = ""
                handleUserText(text)
            },
            onFailed = { msg ->
                partial = ""
                botSay(msg, error = true)
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            voice.destroy()
            speaker.shutdown()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permanentlyDenied = false
            voice.start()
        } else {
            val act = context as? Activity
            permanentlyDenied = act != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(
                    act, Manifest.permission.RECORD_AUDIO
                )
            botSay(
                if (permanentlyDenied)
                    "Microphone permission is blocked. Tap Mic again to open Settings and allow it."
                else
                    "I need microphone permission to hear you. Tap Mic to try again.",
                error = true
            )
        }
    }

    fun onMicClick() {
        if (state == BroState.LISTENING) {
            voice.stop()
            return
        }
        if (state == BroState.SPEAKING) {
            speaker.stop()
            state = BroState.IDLE
            return
        }
        if (state == BroState.THINKING || state == BroState.EXECUTING) return

        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        when {
            granted -> voice.start()
            permanentlyDenied -> context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                )
            )
            else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty()) return
        input = ""
        if (voice.isListening) {
            voice.cancel()
            partial = ""
        }
        handleUserText(text)
    }

    fun newChat() {
        jobs.job?.cancel()
        voice.cancel()
        speaker.stop()
        partial = ""
        messages.clear()
        messages.add(ChatMessage(GREETING, false))
        state = BroState.IDLE
    }

    if (showVoices) {
        val options = remember { speaker.voiceOptions() }
        var selected by remember { mutableStateOf<String?>(speaker.savedVoiceName()) }
        AlertDialog(
            onDismissRequest = { showVoices = false },
            title = { Text("Choose BRO's voice") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = "Tap a voice to hear it. Android does not label voices as male " +
                            "or female, so keep the deepest one you like.",
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    VoiceRow(
                        label = "Auto (best guess, deeper pitch)",
                        selected = selected == null
                    ) {
                        selected = null
                        speaker.selectVoice(null)
                        previewVoice()
                    }
                    options.forEach { option ->
                        VoiceRow(
                            label = option.label,
                            selected = selected == option.name
                        ) {
                            selected = option.name
                            speaker.selectVoice(option.name)
                            previewVoice()
                        }
                    }
                    if (options.isEmpty()) {
                        Text(
                            text = "No other English voices were found. BRO will use the " +
                                "default voice with a deeper pitch.",
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showVoices = false }) { Text("Done") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05070F))
            .statusBarsPadding()
            .imePadding()
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "BRO",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(text = state.label, color = state.color, fontSize = 14.sp)
            }
            Row {
                TextButton(onClick = {
                    if (state == BroState.LISTENING) {
                        voice.cancel()
                        partial = ""
                        state = BroState.IDLE
                    }
                    showVoices = true
                }) { Text("Voice") }
                TextButton(onClick = { newChat() }) { Text("New chat") }
            }
        }

        if (partial.isNotEmpty()) {
            Text(text = partial, color = Color.Gray, fontSize = 14.sp)
        }

        BroOrb(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { m ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    Text(
                        text = m.text,
                        color = Color.White,
                        modifier = Modifier
                            .background(
                                if (m.fromUser) Color(0xFF1E3A5F) else Color(0xFF1B1F2E),
                                RoundedCornerShape(14.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type to BRO...") },
                singleLine = true
            )
            Button(onClick = { onMicClick() }) {
                Text(
                    when (state) {
                        BroState.LISTENING -> "Stop"
                        BroState.SPEAKING -> "Silence"
                        else -> "Mic"
                    }
                )
            }
            Button(onClick = { send() }) { Text("Send") }
        }
    }
}
