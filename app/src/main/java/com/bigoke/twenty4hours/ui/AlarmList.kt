package com.bigoke.twenty4hours.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bigoke.twenty4hours.data.Alarm

@Composable
fun AlarmRow(
    alarm: Alarm,
    isNext: Boolean,
    countdown: String?,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val on = alarm.enabled
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // dot
        Box(
            Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(
                    when {
                        !on -> Brush.linearGradient(listOf(T24.Faint, T24.Faint))
                        isNext -> Brush.verticalGradient(listOf(T24.AmberLight, T24.Amber))
                        else -> Brush.verticalGradient(listOf(T24.CreamLight, T24.CreamDark))
                    }
                )
        )
        Spacer(Modifier.width(18.dp))
        Text(
            alarm.timeText,
            color = if (on) T24.Text else T24.Faint,
            fontSize = 24.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.widthIn(min = 72.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                alarm.displayLabel,
                color = if (on) T24.Text else T24.Faint,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(alarm.repeatText(), color = T24.Muted, fontSize = 13.sp, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
            Text(
                if (on && countdown != null) countdown else "Off",
                color = if (on) T24.Muted else T24.Faint,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(2.dp))
            Switch(
                checked = on,
                onCheckedChange = onToggle,
                modifier = Modifier.scale(0.8f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = T24.CreamLight,
                    checkedTrackColor = T24.Amber,
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = T24.Muted,
                    uncheckedTrackColor = T24.SurfaceHigh,
                    uncheckedBorderColor = T24.Line,
                )
            )
        }
    }
}
