package com.chirp.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.chirp.core.model.Role
import com.chirp.core.session.SessionPhase
import kotlinx.coroutines.delay
import kotlin.math.max

/** A chat bubble aligned left (assistant) or right (user). */
@Composable
fun MessageBubble(
    role: Role,
    text: String,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
    val isUser = role == Role.USER
    val container = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val onContainer = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            color = container.copy(alpha = if (dimmed) 0.6f else 1f),
            contentColor = onContainer,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp,
            ),
            tonalElevation = 1.dp,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * Large central status control. Pulses (scaled by mic level) while listening,
 * shows a spinner with elapsed seconds while thinking, an animated icon while
 * speaking, and a tappable mic when idle/paused.
 */
@Composable
fun MicStatusIndicator(
    phase: SessionPhase,
    rms: Float,
    thinkingStartedAtMillis: Long?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val infinite = rememberInfiniteTransition(label = "indicator")
    val pulse by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(animation = tween(750), repeatMode = RepeatMode.Reverse),
        label = "pulse",
    )

    // Map the recognizer RMS (roughly -2..10 dB) to a gentle extra scale.
    val rmsScale = 1f + (rms.coerceIn(0f, 10f) / 10f) * 0.35f
    val ringScale = when (phase) {
        SessionPhase.LISTENING -> max(pulse, rmsScale)
        SessionPhase.SPEAKING -> pulse
        else -> 1f
    }

    Box(modifier = modifier.size(180.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = ringColor(phase),
            modifier = Modifier
                .size(160.dp)
                .scale(ringScale)
                .alpha(0.22f),
        ) {}

        Surface(
            onClick = onTap,
            shape = CircleShape,
            color = coreColor(phase),
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(116.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                when (phase) {
                    SessionPhase.THINKING -> ThinkingContent(thinkingStartedAtMillis)
                    else -> Icon(
                        imageVector = iconFor(phase),
                        contentDescription = phase.name,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThinkingContent(thinkingStartedAtMillis: Long?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(thinkingStartedAtMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsed = thinkingStartedAtMillis?.let { ((now - it) / 1000).coerceAtLeast(0) } ?: 0L
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.onPrimary,
            strokeWidth = 3.dp,
            modifier = Modifier.size(34.dp),
        )
        Text(
            text = "${elapsed}s",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ringColor(phase: SessionPhase): Color = when (phase) {
    SessionPhase.ERROR -> MaterialTheme.colorScheme.error
    SessionPhase.PAUSED -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun coreColor(phase: SessionPhase): Color = when (phase) {
    SessionPhase.ERROR -> MaterialTheme.colorScheme.error
    SessionPhase.PAUSED -> MaterialTheme.colorScheme.secondary
    SessionPhase.SPEAKING -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

private fun iconFor(phase: SessionPhase): ImageVector = when (phase) {
    SessionPhase.LISTENING -> Icons.Filled.Mic
    SessionPhase.SPEAKING -> Icons.Filled.GraphicEq
    SessionPhase.PAUSED -> Icons.Filled.Mic
    SessionPhase.ERROR -> Icons.Filled.PriorityHigh
    else -> Icons.Filled.Mic
}
