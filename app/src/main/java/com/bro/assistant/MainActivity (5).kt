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
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class BroState(val label: String, val color: Color, val periodMs: Int) {
    IDLE("Navi", Color(0xFF3FA9F5), 2600),
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

const val GREETING =
    "Hi, I'm BRO. Tap Mic and talk, or type. Try: what time is it, or open YouTube. " +
        "For complex commands, tap AI and paste your key."

private val TWO_PI = (2.0 * Math.PI).toFloat()

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

private fun DrawScope.ringArcs(c: Offset, ringR: Float, color: Color, spin: Float) {
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

@Composable
fun BroOrb(state: BroState, level: Float, modifier: Modifier = Modifier) {
    val color by animateColorAsState(state.color, tween(500), label = "orbColor")
    val smoothLevel by animateFloatAsState(level, tween(90), label = "level")
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

    val spinMs = when (state) {
        BroState.THINKING -> 1500
        BroState.EXECUTING -> 1800
        else -> 5000
    }
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(spinMs, easing = LinearEasing)),
        label = "spin"
    )

    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = TWO_PI,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "wave"
    )

    val ripple by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "ripple"
    )

    Canvas(modifier = modifier) {
        val shake = if (state == BroState.ERROR) sin(wave * 8f) * 8f else 0f
        val c = Offset(size.width / 2f + shake, size.height / 2f)
        val base = size.minDimension / 2f
        val lvl = if (state == BroState.LISTENING) smoothLevel else 0f
        val coreR = base * 0.45f * pulse * (1f + 0.3f * lvl)
        val ringR = base * 0.72f

        // Glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.55f), Color.Transparent),
                center = c,
                radius = base
            ),
            radius = base,
            center = c
        )
        // Core
        drawCircle(color = color, radius = coreR, center = c)
        drawCircle(color = Color.White.copy(alpha = 0.25f), radius = coreR * 0.5f, center = c)

        // State-specific animation
        when (state) {
            BroState.IDLE -> {
                ringArcs(c, ringR, color, spin)
            }

            BroState.LISTENING -> {
                for (k in 0..1) {
                    val p = (ripple + k * 0.5f) % 1f
                    drawCircle(
                        color = color.copy(alpha = (1f - p) * 0.6f),
                        radius = coreR + (base - coreR) * p,
                        center = c,
                        style = Stroke(width = 3f)
                    )
                }
                ringArcs(c, ringR, color, spin)
            }

            BroState.THINKING -> {
                ringArcs(c, ringR, color, spin)
                for (i in 0..2) {
                    val a = Math.toRadians((spin * 2f + i * 120f).toDouble())
                    val pos = Offset(
                        c.x + (cos(a) * ringR).toFloat(),
                        c.y + (sin(a) * ringR).toFloat()
                    )
                    drawCircle(color = color, radius = 9f, center = pos)
                }
            }

            BroState.EXECUTING -> {
                val active = ((spin * 2f) / 30f).toInt() % 12
                for (i in 0 until 12) {
                    drawArc(
                        color = color.copy(alpha = if (i == active) 1f else 0.25f),
                        startAngle = i * 30f - 90f,
                        sweepAngle = 22f,
                        useCenter = false,
                        topLeft = Offset(c.x - ringR, c.y - ringR),
                        size = Size(ringR * 2f, ringR * 2f),
                        style = Stroke(width = 8f, cap = StrokeCap.Round)
                    )
                }
            }

            BroState.SPEAKING -> {
                val n = 24
                val r0 = coreR * 1.15f
                for (i in 0 until n) {
                    val ang = Math.toRadians(i * 360.0 / n)
                    val dx = cos(ang).toFloat()
                    val dy = sin(ang).toFloat()
                    val h = base * (0.06f + 0.22f * abs(sin(wave + i * 0.55f)))
                    drawLine(
                        color = color,
                        start = Offset(c.x + dx * r0, c.y + dy * r0),
                        end = Offset(c.x + dx * (r0 + h), c.y + dy * (r0 + h)),
                        strokeWidth = 6f,
                        cap = StrokeCap.Round
                    )
                }
            }

            BroState.SUCCESS -> {
                drawCircle(
                    color = color.copy(alpha = 0.6f),
                    radius = ringR,
                    center = c,
                    style = Stroke(width = 5f)
                )
                val a = Offset(c.x - coreR * 0.4f, c.y)
                val b = Offset(c.x - coreR * 0.1f, c.y + coreR * 0.3f)
                val d = Offset(c.x + coreR * 0.4f, c.y - coreR * 0.3f)
                drawLine(color = Color.White, start = a, end = b, strokeWidth = 10f, cap = StrokeCap.Round)
                drawLine(color = Color.White, start = b, end = d, strokeWidth = 10f, cap = StrokeCap.Round)
            }

            BroState.ERROR -> {
                drawCircle(
                    color = color.copy(alpha = 0.6f),
                    radius = ringR,
                    center = c,
                    style = Stroke(width = 5f)
                )
                val k = coreR * 0.3f
                drawLine(
                    color = Color.White,
                    start = Offset(c.x - k, c.y - k),
                    end = Offset(c.x + k, c.y + k),
                    strokeWidth = 10f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = Color.White,
                    start = Offset(c.x + k, c.y - k),
                    end = Offset(c.x - k, c.y + k),
                    strokeWidth = 10f,
                    cap = StrokeCap.Round
                )
            }
        }
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
    var level by remember { mutableStateOf(0f) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var ttsReady by remember { mutableStateOf(false) }
    var afterSpeech by remember { mutableStateOf(BroState.SUCCESS) }
    var showVoices by remember { mutableStateOf(false) }
    var showAiSettings by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf(ChatMessage(GREETING, false)) }
    val jobs = remember { JobHolder() }
    val keyStore = remember { ApiKeyStore(context) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // Shows SUCCESS, ERROR or IDLE for a moment, then returns to IDLE.
    fun showResultThenIdle() {
        scope.launch {
            val shown = afterSpeech
            state = shown
            delay(1200)
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

    // BRO says something: shows "text" in chat and speaks "spoken" when voice is ready.
    // "result" is the animation shown after speaking (SUCCESS, ERROR or IDLE).
    fun botSay(text: String, result: BroState = BroState.SUCCESS, spoken: String = text) {
        messages.add(ChatMessage(text, false))
        afterSpeech = result
        if (ttsReady && speaker.speak(spoken)) {
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

    // Recent conversation for the AI: list of (role, text), starts and ends with "user".
    fun buildHistory(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (m in messages.takeLast(12)) {
            if (m.text == GREETING) continue
            val role = if (m.fromUser) "user" else "assistant"
            if (out.isNotEmpty() && out.last().first == role) {
                val last = out.removeAt(out.size - 1)
                out.add(Pair(role, last.second + "\n" + m.text))
            } else {
                out.add(Pair(role, m.text))
            }
        }
        while (out.isNotEmpty() && out.first().first != "user") {
            out.removeAt(0)
        }
        return out
    }

    fun describeStep(index: Int, step: AiStep): String {
        val details = if (step.params.isEmpty()) {
            ""
        } else {
            " (" + step.params.entries.joinToString(", ") { it.key + ": " + it.value } + ")"
        }
        return "${index + 1}. ${step.action}$details"
    }

    // Stage 6 local commands first; Stage 7 AI only when local parsing cannot understand.
    fun handleUserText(text: String) {
        speaker.stop()
        jobs.job?.cancel()
        messages.add(ChatMessage(text, true))
        jobs.job = scope.launch {
            state = BroState.THINKING
            val local = LocalCommands.parse(text)
            if (local !is ParseResult.NeedsAi) {
                delay(400)
                val reply = LocalCommands.respond(text)
                state = BroState.EXECUTING
                delay(400)
                botSay(reply.text, reply.result)
                return@launch
            }

            val key = keyStore.get()
            if (key.isBlank()) {
                botSay(
                    "That needs my AI brain. Tap AI at the top and paste your API key first.",
                    BroState.IDLE
                )
                return@launch
            }

            when (val r = AiClient.ask(key, buildHistory())) {
                is AiResult.Failure -> botSay(r.message, BroState.ERROR)
                is AiResult.Plan -> {
                    if (r.kind == "plan" && r.steps.isNotEmpty()) {
                        val lines = r.steps.mapIndexed { i, s -> describeStep(i, s) }
                        val shown = (if (r.reply.isNotBlank()) r.reply + "\n" else "") +
                            "Plan:\n" + lines.joinToString("\n") +
                            "\nI have not run this yet. Running plans comes in the next stages."
                        botSay(
                            shown,
                            BroState.IDLE,
                            "I made a plan with ${r.steps.size} steps. Running plans comes in the next stage."
                        )
                    } else {
                        val answer = r.reply.ifBlank { "I'm not sure how to help with that." }
                        botSay(answer, if (r.kind == "clarify") BroState.IDLE else BroState.SUCCESS)
                    }
                }
            }
        }
    }

    val voice = remember {
        VoiceInput(
            context = context,
            onReady = { state = BroState.LISTENING },
            onPartial = { partial = it },
            onFinalText = { text ->
                partial = ""
                level = 0f
                handleUserText(text)
            },
            onFailed = { msg ->
                partial = ""
                level = 0f
                botSay(msg, BroState.ERROR)
            },
            onLevel = { level = it }
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
                BroState.ERROR
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
            level = 0f
        }
        handleUserText(text)
    }

    fun newChat() {
        jobs.job?.cancel()
        voice.cancel()
        speaker.stop()
        partial = ""
        level = 0f
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

    if (showAiSettings) {
        var keyInput by remember { mutableStateOf("") }
        var hasKey by remember { mutableStateOf(keyStore.has()) }
        AlertDialog(
            onDismissRequest = { showAiSettings = false },
            title = { Text("AI settings") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = if (hasKey) "An AI key is saved on this phone."
                        else "No AI key is saved yet.",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "Paste your free Gemini API key. It stays on this phone, and only " +
                            "complex commands are sent to the AI.",
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("AIza...") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val k = keyInput.trim()
                    if (k.isNotEmpty()) {
                        keyStore.save(k)
                        messages.add(ChatMessage("AI key saved on this phone.", false))
                    }
                    showAiSettings = false
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (hasKey) {
                        TextButton(onClick = {
                            keyStore.clear()
                            hasKey = false
                            messages.add(ChatMessage("AI key removed.", false))
                        }) { Text("Remove key") }
                    }
                    TextButton(onClick = { showAiSettings = false }) { Text("Cancel") }
                }
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
                        level = 0f
                        state = BroState.IDLE
                    }
                    showVoices = true
                }) { Text("Voice") }
                TextButton(onClick = {
                    if (state == BroState.LISTENING) {
                        voice.cancel()
                        partial = ""
                        level = 0f
                        state = BroState.IDLE
                    }
                    showAiSettings = true
                }) { Text("AI") }
                TextButton(onClick = { newChat() }) { Text("New chat") }
            }
        }

        if (partial.isNotEmpty()) {
            Text(text = partial, color = Color.Gray, fontSize = 14.sp)
        }

        BroOrb(
            state = state,
            level = level,
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
            val micLabel = when (state) {
                BroState.LISTENING -> "Stop"
                BroState.SPEAKING -> "Silence"
                else -> "Mic"
            }
            Button(onClick = { onMicClick() }) { Text(micLabel) }
            Button(onClick = { send() }) { Text("Send") }
        }
    }
}
