package com.bro.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
fun BroScreen() {
    var state by remember { mutableStateOf(BroState.IDLE) }
    var input by remember { mutableStateOf("") }
    val messages = remember {
        mutableStateListOf(ChatMessage("Hi, I'm BRO. Stage 1 interface is ready.", false))
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty()) return
        input = ""
        messages.add(ChatMessage(text, true))
        scope.launch {
            state = BroState.THINKING
            delay(800)
            state = BroState.EXECUTING
            delay(800)
            messages.add(ChatMessage("(Demo) I received: $text", false))
            state = BroState.SUCCESS
            delay(900)
            state = BroState.IDLE
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05070F))
            .statusBarsPadding()
            .imePadding()
            .padding(12.dp)
    ) {
        Text(
            text = "BRO",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Text(text = state.label, color = state.color, fontSize = 14.sp)

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
            Button(onClick = {
                state = if (state == BroState.LISTENING) BroState.IDLE else BroState.LISTENING
            }) { Text("Mic") }
            Button(onClick = { send() }) { Text("Send") }
        }
    }
}
