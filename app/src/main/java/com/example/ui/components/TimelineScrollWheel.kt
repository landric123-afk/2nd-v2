package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AcidGreen
import com.example.ui.theme.BlackCarbon
import com.example.ui.theme.DarkGreyCarbon
import com.example.ui.theme.GuerrillaPink
import com.example.ui.theme.LaserCyan
import com.example.ui.theme.MutedText
import com.example.ui.theme.WhiteText
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

data class TimelineStop(
    val hours: Int,
    val shortLabel: String,
    val fullLabel: String
)

data class TimelineUploadMarker(
    val timestamp: Long,
    val isGraffiti: Boolean
)

private val TIMELINE_STOPS = listOf(
    TimelineStop(1, "1H", "1H"),
    TimelineStop(2, "2H", "2H"),
    TimelineStop(6, "6H", "6H"),
    TimelineStop(12, "12H", "12H"),
    TimelineStop(24, "24H", "24H"),
    TimelineStop(48, "48H", "48H"),
    TimelineStop(0, "ALL", "ALL")
)

fun formatUploadTimestamp(timestamp: Long): String {
    val formatter = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

/**
 * Ultra-minimalistic hardware-inspired cylindrical jog wheel for filtering
 * Sound-Drops and Virtual Graffiti by upload date/time.
 */
@Composable
fun MinimalTimelineScrollWheel(
    selectedHours: Int,
    soundCount: Int,
    graffitiCount: Int,
    latestUploadTimestamp: Long?,
    uploadMarkers: List<TimelineUploadMarker>,
    onHoursSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentOnHoursSelected by rememberUpdatedState(onHoursSelected)
    val currentSelectedHours by rememberUpdatedState(selectedHours)

    val selectedIndex = remember(selectedHours) {
        val idx = TIMELINE_STOPS.indexOfFirst { it.hours == selectedHours }
        if (idx >= 0) idx else TIMELINE_STOPS.lastIndex
    }

    val wheelPosition = remember { Animatable(selectedIndex.toFloat()) }
    var dragAccumulatedIndex by remember { mutableFloatStateOf(selectedIndex.toFloat()) }

    LaunchedEffect(selectedIndex) {
        if (abs(wheelPosition.value - selectedIndex.toFloat()) > 0.01f) {
            wheelPosition.animateTo(
                targetValue = selectedIndex.toFloat(),
                animationSpec = tween(durationMillis = 160)
            )
        }
        dragAccumulatedIndex = selectedIndex.toFloat()
    }

    val activeStop = TIMELINE_STOPS[selectedIndex]
    val rangeReadout = remember(selectedHours) {
        if (selectedHours == 0) {
            "ALL"
        } else {
            val cutoff = System.currentTimeMillis() - (selectedHours * 60 * 60 * 1000L)
            "≥ ${formatUploadTimestamp(cutoff).uppercase(Locale.getDefault())}"
        }
    }
    val latestReadout = remember(latestUploadTimestamp, soundCount, graffitiCount) {
        val ts = latestUploadTimestamp?.let { formatUploadTimestamp(it).uppercase(Locale.getDefault()) } ?: "—"
        "$ts · ${soundCount}S ${graffitiCount}A"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkGreyCarbon)
            .testTag("timeline_scroll_wheel")
    ) {
        // Single hairline telemetry strip: active window + upload timestamp
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = activeStop.fullLabel,
                    color = GuerrillaPink,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = rangeReadout,
                    color = WhiteText.copy(alpha = 0.7f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = latestReadout,
                color = MutedText,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }

        // Sleek recessed cylindrical knurled thumbwheel with minimal top/bottom ruler ticks
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .padding(bottom = 6.dp)
                .height(38.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragAccumulatedIndex = wheelPosition.value
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val maxIdx = (TIMELINE_STOPS.size - 1).toFloat()
                            val stepPixels = (size.width / 4.2f).coerceAtLeast(52f)
                            val deltaIdx = -dragAmount / stepPixels
                            dragAccumulatedIndex = (dragAccumulatedIndex + deltaIdx).coerceIn(0f, maxIdx)

                            scope.launch {
                                wheelPosition.snapTo(dragAccumulatedIndex)
                            }

                            val snapped = dragAccumulatedIndex.roundToInt()
                            val targetHours = TIMELINE_STOPS[snapped].hours
                            if (targetHours != currentSelectedHours) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentOnHoursSelected(targetHours)
                            }
                        },
                        onDragEnd = {
                            val snapped = dragAccumulatedIndex.roundToInt()
                            scope.launch {
                                wheelPosition.animateTo(
                                    targetValue = snapped.toFloat(),
                                    animationSpec = tween(durationMillis = 120)
                                )
                            }
                            currentOnHoursSelected(TIMELINE_STOPS[snapped].hours)
                        },
                        onDragCancel = {
                            val snapped = dragAccumulatedIndex.roundToInt()
                            scope.launch {
                                wheelPosition.animateTo(
                                    targetValue = snapped.toFloat(),
                                    animationSpec = tween(durationMillis = 120)
                                )
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val tappedIdx = (fraction * (TIMELINE_STOPS.size - 1)).roundToInt()
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        currentOnHoursSelected(TIMELINE_STOPS[tappedIdx].hours)
                    }
                }
                .testTag("timeline_knurled_wheel_canvas")
        ) {
            val currentPos = wheelPosition.value
            val nowMs = remember(uploadMarkers) { System.currentTimeMillis() }

            Canvas(modifier = Modifier.matchParentSize()) {
                val width = size.width
                val height = size.height
                val rulerHeight = 7.dp.toPx()
                val slotTop = rulerHeight + 1.dp.toPx()
                val slotBottom = height - rulerHeight - 1.dp.toPx()
                val slotHeight = (slotBottom - slotTop).coerceAtLeast(10.dp.toPx())
                val centerX = width / 2f

                // Top & bottom ruler ticks
                val tickSpacing = 8.dp.toPx()
                val scrollShiftPx = currentPos * (tickSpacing * 4f)
                val totalTicks = (width / tickSpacing).toInt() + 8
                val startOffset = -(scrollShiftPx % (tickSpacing * 5f))

                for (i in -4..totalTicks) {
                    val x = startOffset + i * tickSpacing
                    if (x < 0f || x > width) continue
                    val isMajor = ((i % 5) + 5) % 5 == 0
                    val tickLen = if (isMajor) 5.5.dp.toPx() else 3.dp.toPx()
                    val tickColor = if (isMajor) MutedText.copy(alpha = 0.7f) else MutedText.copy(alpha = 0.3f)

                    drawLine(
                        color = tickColor,
                        start = Offset(x, rulerHeight - tickLen),
                        end = Offset(x, rulerHeight),
                        strokeWidth = if (isMajor) 1.2.dp.toPx() else 0.8.dp.toPx()
                    )
                    drawLine(
                        color = tickColor,
                        start = Offset(x, slotBottom + 1.dp.toPx()),
                        end = Offset(x, slotBottom + 1.dp.toPx() + tickLen),
                        strokeWidth = if (isMajor) 1.2.dp.toPx() else 0.8.dp.toPx()
                    )
                }

                // Minimalist upload timestamp pips along upper track
                val maxHorizonMs = 48 * 60 * 60 * 1000L
                uploadMarkers.forEach { marker ->
                    val ageMs = (nowMs - marker.timestamp).coerceIn(0L, maxHorizonMs)
                    val norm = 1f - (ageMs.toFloat() / maxHorizonMs.toFloat())
                    val dotX = 6.dp.toPx() + norm * (width - 12.dp.toPx())
                    drawCircle(
                        color = if (marker.isGraffiti) LaserCyan else AcidGreen,
                        radius = 1.6.dp.toPx(),
                        center = Offset(dotX, 2.dp.toPx())
                    )
                }

                // Recessed bezel slot
                drawRoundRect(
                    color = Color(0xFF070709),
                    topLeft = Offset(0f, slotTop),
                    size = Size(width, slotHeight),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                )

                // Cylindrical metallic drum
                val inset = 1.5.dp.toPx()
                val cylTop = slotTop + inset
                val cylHeight = (slotHeight - inset * 2).coerceAtLeast(6.dp.toPx())
                val cylWidth = (width - inset * 2).coerceAtLeast(10.dp.toPx())

                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        0.0f to Color(0xFF0A0A0D),
                        0.16f to Color(0xFF282832),
                        0.38f to Color(0xFF5A5B6B),
                        0.50f to Color(0xFF8C8E9E),
                        0.62f to Color(0xFF5A5B6B),
                        0.84f to Color(0xFF282832),
                        1.0f to Color(0xFF0A0A0D)
                    ),
                    topLeft = Offset(inset, cylTop),
                    size = Size(cylWidth, cylHeight),
                    cornerRadius = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx())
                )

                // Knurled grooves rotating across cylindrical perspective
                val notchCount = 34
                val phaseRad = currentPos * 0.52f
                val grooveTop = cylTop + 2.5.dp.toPx()
                val grooveHeight = (cylHeight - 5.dp.toPx()).coerceAtLeast(3.dp.toPx())

                for (i in 0 until notchCount) {
                    val baseAngle = ((i.toFloat() / notchCount.toFloat()) * PI.toFloat()) - (PI.toFloat() / 2f)
                    val wrapped = (((baseAngle + phaseRad) % PI.toFloat()) + PI.toFloat()) % PI.toFloat() - (PI.toFloat() / 2f)
                    val projX = sin(wrapped)
                    val depthCos = cos(wrapped).coerceAtLeast(0f)

                    if (depthCos > 0.14f) {
                        val x = centerX + projX * (cylWidth * 0.48f)
                        val notchW = (3.dp.toPx() * depthCos).coerceAtLeast(0.9.dp.toPx())
                        val notchAlpha = (0.85f * depthCos).coerceIn(0.1f, 0.9f)

                        drawRoundRect(
                            color = Color(0xFF0A0A0E).copy(alpha = notchAlpha),
                            topLeft = Offset(x - notchW / 2f, grooveTop),
                            size = Size(notchW, grooveHeight),
                            cornerRadius = CornerRadius(notchW / 2f, notchW / 2f)
                        )
                    }
                }

                // Center needle
                drawLine(
                    color = GuerrillaPink,
                    start = Offset(centerX, 0f),
                    end = Offset(centerX, slotTop + 2.dp.toPx()),
                    strokeWidth = 1.8.dp.toPx()
                )
                drawLine(
                    color = GuerrillaPink,
                    start = Offset(centerX, slotBottom - 2.dp.toPx()),
                    end = Offset(centerX, height),
                    strokeWidth = 1.8.dp.toPx()
                )
            }
        }

        HorizontalDivider(color = BlackCarbon, thickness = 1.dp)
    }
}
