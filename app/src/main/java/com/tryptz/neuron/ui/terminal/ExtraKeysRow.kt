package com.tryptz.neuron.ui.terminal

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tryptz.neuron.terminal.ModifierKeys
import com.tryptz.neuron.terminal.ModifierState

/** One key on the extra-keys row. */
sealed interface ExtraKey {
    val label: String
    data class Special(override val label: String, val keyCode: Int) : ExtraKey
    data class Char(override val label: String, val text: String) : ExtraKey
    data class Modifier(override val label: String, val key: ModifierKeys.Key) : ExtraKey
}

/**
 * Two rows like Termux's default, but every modifier is sticky and visibly
 * stateful (outlined = armed for one key, filled = locked), and the row
 * scrolls instead of shrinking keys to unreadable widths on narrow phones.
 */
val DEFAULT_EXTRA_KEYS: List<List<ExtraKey>> = listOf(
    listOf(
        ExtraKey.Special("ESC", KeyEvent.KEYCODE_ESCAPE),
        ExtraKey.Char("/", "/"),
        ExtraKey.Char("-", "-"),
        ExtraKey.Special("HOME", KeyEvent.KEYCODE_MOVE_HOME),
        ExtraKey.Special("↑", KeyEvent.KEYCODE_DPAD_UP),
        ExtraKey.Special("END", KeyEvent.KEYCODE_MOVE_END),
        ExtraKey.Special("PGUP", KeyEvent.KEYCODE_PAGE_UP),
        ExtraKey.Char("|", "|"),
        ExtraKey.Char("~", "~")
    ),
    listOf(
        ExtraKey.Special("TAB", KeyEvent.KEYCODE_TAB),
        ExtraKey.Modifier("CTRL", ModifierKeys.Key.CTRL),
        ExtraKey.Modifier("ALT", ModifierKeys.Key.ALT),
        ExtraKey.Special("←", KeyEvent.KEYCODE_DPAD_LEFT),
        ExtraKey.Special("↓", KeyEvent.KEYCODE_DPAD_DOWN),
        ExtraKey.Special("→", KeyEvent.KEYCODE_DPAD_RIGHT),
        ExtraKey.Special("PGDN", KeyEvent.KEYCODE_PAGE_DOWN),
        ExtraKey.Modifier("SHIFT", ModifierKeys.Key.SHIFT),
        ExtraKey.Modifier("FN", ModifierKeys.Key.FN)
    )
)

@Composable
fun ExtraKeysRow(
    rows: List<List<ExtraKey>>,
    modifierStates: Map<ModifierKeys.Key, ModifierState>,
    onKey: (ExtraKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF101418))
            .padding(vertical = 2.dp)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                row.forEach { key ->
                    val state = (key as? ExtraKey.Modifier)?.let { modifierStates[it.key] } ?: ModifierState.OFF
                    val (bg, fg) = when (state) {
                        ModifierState.OFF -> Color(0xFF1C2228) to Color(0xFFE6EDF3)
                        ModifierState.ONCE -> Color(0xFF1F3A5F) to Color(0xFF9CCBFF)
                        ModifierState.LOCKED -> Color(0xFF58A6FF) to Color(0xFF0D1117)
                    }
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onKey(key)
                        },
                        color = bg,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(38.dp).widthIn(min = 44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(
                                key.label,
                                color = fg,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (state == ModifierState.LOCKED) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        }
    }
}
