package com.flexfly.ultron.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.remember
import com.flexfly.ultron.model.Message
import com.flexfly.ultron.ui.theme.BgDark
import com.flexfly.ultron.ui.theme.CardBg
import com.flexfly.ultron.ui.theme.UltronCrimson
import com.flexfly.ultron.ui.theme.UltronRed
import com.flexfly.ultron.ui.theme.SubText
import com.flexfly.ultron.ui.theme.UserCyan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// RECOVERY NOTE: layout approximated from compiled Compose (Historyscreen.kt). The transcript
// data, roles, header/empty-state strings, and clear action are recovered; decorative
// sheen/pulse animations were dropped.

/**
 * Conversation history: a scrollable transcript of past [Message]s with a clear-all action.
 * Recovered signature: `HistoryScreen(messages, onClearHistory)`.
 */
@Composable
fun HistoryScreen(messages: List<Message>, onClearHistory: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(top = 24.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
    ) {
        val sessionsCount = remember(messages) {
            if (messages.isEmpty()) 0 else {
                var count = 1
                for (i in 1 until messages.size) {
                    if (messages[i].timestamp - messages[i - 1].timestamp > 30 * 60 * 1000) {
                        count++
                    }
                }
                count
            }
        }
        val messagesCount = messages.size
        val subtitle = "$sessionsCount session${if (sessionsCount == 1) "" else "s"} · $messagesCount message${if (messagesCount == 1) "" else "s"}"

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("History", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = SubText, fontSize = 12.sp)
            }
            if (messages.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .border(1.dp, UltronCrimson.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .background(UltronCrimson.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .clickable { onClearHistory() }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Clear", color = UltronCrimson, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.06f)))
        Spacer(modifier = Modifier.height(16.dp))

        if (messages.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                itemsIndexed(messages) { index, message ->
                    FadeSlideIn(delayMillis = index * 30) {
                        HistoryBubble(message)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryBubble(message: Message) {
    val isUser = message.role == "user"
    val formattedContent = formatMessageContent(message.content)

    if (isUser) {
        // User bubble: Aligned right, purple background, no avatar, sharp bottom-right
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .background(Color(0xFF5E173E))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = formattedContent,
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatTime(message.timestamp),
                color = SubText.copy(0.7f),
                fontSize = 10.sp,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
    } else {
        // Assistant bubble: Aligned left, avatar circle "A", dark background, sharp bottom-left
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF5E1035)),
                contentAlignment = Alignment.Center
            ) {
                Text("U", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.fillMaxWidth(0.85f)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
                        .background(Color(0xFF131722))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = formattedContent,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatTime(message.timestamp),
                    color = SubText.copy(0.7f),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun formatMessageContent(content: String): androidx.compose.ui.text.AnnotatedString {
    return remember(content) {
        buildAnnotatedString {
            var lastIdx = 0
            val regex = Regex("\\[TOOL:[a-zA-Z0-9_:]+\\]")
            regex.findAll(content).forEach { match ->
                val start = match.range.first
                val end = match.range.last + 1
                if (start > lastIdx) {
                    append(content.substring(lastIdx, start))
                }
                pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF22D3EE),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                append(content.substring(start, end))
                pop()
                lastIdx = end
            }
            if (lastIdx < content.length) {
                append(content.substring(lastIdx))
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No conversations yet", color = SubText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("Say \"Ultron\" to get started", color = SubText, fontSize = 13.sp)
        }
    }
}

@Composable
private fun FadeSlideIn(delayMillis: Int, content: @Composable () -> Unit) {
    // ponytail: delay param retained from the original API; a plain fade+slide covers it.
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 5 }),
    ) { content() }
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts))
