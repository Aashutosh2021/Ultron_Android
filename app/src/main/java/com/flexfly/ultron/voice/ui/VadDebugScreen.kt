package com.flexfly.ultron.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flexfly.ultron.ai.brain.BrainState
import com.flexfly.ultron.ai.conversation.ConversationState
import com.flexfly.ultron.ai.memory.MemoryState
import com.flexfly.ultron.ai.tool.ToolExecutionState
import com.flexfly.ultron.android.action.AndroidActionState
import com.flexfly.ultron.plugin.sdk.PluginState
import com.flexfly.ultron.voice.VadState
import com.flexfly.ultron.voice.session.VoiceSessionState
import com.flexfly.ultron.voice.speaker.VerificationResult
import com.flexfly.ultron.voice.wakeword.WakeWordState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug Dashboard for VAD, openWakeWord, ECAPA-TDNN Speaker Verification, Voice Session Orchestration, Conversation Engine, Brain Engine, Tool Framework, Memory Engine, Android Action Layer & Plugin SDK.
 */
@Composable
fun VadDebugScreen(
    viewModel: VadDebugViewModel = viewModel(),
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val debugInfo by viewModel.debugInfo.collectAsState()
    val wakeDebugInfo by viewModel.wakeDebugInfo.collectAsState()
    val speakerDebugInfo by viewModel.speakerDebugInfo.collectAsState()
    val sessionState by viewModel.sessionState.collectAsState()
    val sessionMetrics by viewModel.sessionMetrics.collectAsState()
    val convState by viewModel.conversationState.collectAsState()
    val convMetrics by viewModel.conversationMetrics.collectAsState()
    val brainState by viewModel.brainState.collectAsState()
    val brainMetrics by viewModel.brainMetrics.collectAsState()
    val toolState by viewModel.toolState.collectAsState()
    val toolMetrics by viewModel.toolMetrics.collectAsState()
    val memoryState by viewModel.memoryState.collectAsState()
    val memoryMetrics by viewModel.memoryMetrics.collectAsState()
    val actionState by viewModel.actionState.collectAsState()
    val actionMetrics by viewModel.actionMetrics.collectAsState()
    val pluginState by viewModel.pluginState.collectAsState()
    val pluginMetrics by viewModel.pluginMetrics.collectAsState()

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "ULTRON Full Architecture Dashboard",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Plugin Platform Card
            PluginStateBadgeCard(pluginState = pluginState)
            PluginMetricsCard(
                activeCount = pluginMetrics.activePluginsCount,
                exposedTools = pluginMetrics.totalExposedToolsCount,
                healthScore = pluginMetrics.averageHealthScore,
                loadMs = pluginMetrics.loadTimeMs,
            )

            // Android Action Layer Card
            AndroidActionStateBadgeCard(actionState = actionState)
            AndroidActionMetricsCard(
                lastAction = actionMetrics.lastActionName,
                executionMs = actionMetrics.executionTimeMs,
                successCount = actionMetrics.successCount,
                failureCount = actionMetrics.failureCount,
            )

            // Memory Engine Card
            MemoryStateBadgeCard(memoryState = memoryState)
            MemoryMetricsCard(
                totalCount = memoryMetrics.totalMemories,
                reads = memoryMetrics.readsCount,
                writes = memoryMetrics.writesCount,
                retrievalMs = memoryMetrics.retrievalTimeMs,
            )

            // Tool Execution Framework Card
            ToolStateBadgeCard(toolState = toolState)

            // Brain Engine Intelligence Card
            BrainStateBadgeCard(brainState = brainState)

            // AI Conversation Engine Card
            ConversationStateBadgeCard(convState = convState)

            // Voice Session State Badge
            SessionStateBadgeCard(sessionState = sessionState)

            // Low-Power Voice Stack Cards
            StateBadgeCard(state = debugInfo.state)
            WakeWordStateBadgeCard(state = wakeDebugInfo.state)

            Spacer(modifier = Modifier.weight(1f))

            // Action Control Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { viewModel.startService(context) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                ) {
                    Text("START SERVICE", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { viewModel.stopService(context) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC62828)),
                ) {
                    Text("STOP SERVICE", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PluginStateBadgeCard(pluginState: PluginState) {
    val (label, backgroundColor, textColor) = when (pluginState) {
        is PluginState.Idle -> Triple("PLUGIN PLATFORM IDLE 🔌", Color(0xFF424242), Color.White)
        is PluginState.Discovering -> Triple("DISCOVERING EXTENSION PLUGINS...", Color(0xFF0288D1), Color.White)
        is PluginState.Loading -> Triple("LOADING PLUGIN...", Color(0xFF1565C0), Color.White)
        is PluginState.Initializing -> Triple("INITIALIZING PLUGIN...", Color(0xFF6A1B9A), Color.White)
        is PluginState.Running -> Triple("PLUGINS ACTIVE & READY 🔌⚡", Color(0xFF2E7D32), Color.White)
        is PluginState.Paused -> Triple("PLUGIN PAUSED", Color(0xFFF57C00), Color.White)
        is PluginState.Stopped -> Triple("PLUGIN STOPPED", Color(0xFF757575), Color.White)
        is PluginState.Unloaded -> Triple("PLUGIN UNLOADED", Color(0xFF616161), Color.White)
        is PluginState.Failed -> Triple("PLUGIN FAILED", Color(0xFFC62828), Color.White)
        is PluginState.Error -> Triple("PLUGIN PLATFORM ERROR", Color(0xFFC62828), Color.White)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text("PLUGIN SDK & EXTENSION PLATFORM", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .background(backgroundColor, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                )
            }
        }
    }
}

@Composable
private fun PluginMetricsCard(
    activeCount: Int,
    exposedTools: Int,
    healthScore: Float,
    loadMs: Long,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            MetricRow("Active Extension Plugins", "$activeCount")
            MetricRow("Total Plugin Tools Exposed", "$exposedTools")
            MetricRow("Platform Health Score", String.format(Locale.US, "%.0f%%", healthScore * 100))
            MetricRow("Platform Discovery & Load Latency", "${loadMs}ms")
        }
    }
}

@Composable
private fun AndroidActionStateBadgeCard(actionState: AndroidActionState) {
    val (label, backgroundColor, textColor) = when (actionState) {
        is AndroidActionState.Idle -> Triple("ANDROID ACTION LAYER IDLE 📱", Color(0xFF424242), Color.White)
        is AndroidActionState.Queued -> Triple("QUEUED ACTION...", Color(0xFF0288D1), Color.White)
        is AndroidActionState.Validating -> Triple("VALIDATING OS ACTION...", Color(0xFF1565C0), Color.White)
        is AndroidActionState.WaitingPermission -> Triple("WAITING SYSTEM PERMISSION 🔑", Color(0xFFE65100), Color.White)
        is AndroidActionState.Executing -> Triple("EXECUTING OS FRAMEWORK CALL ⚡", Color(0xFFF57C00), Color.White)
        is AndroidActionState.WaitingResult -> Triple("WAITING OS RESULT...", Color(0xFF00838F), Color.White)
        is AndroidActionState.Completed -> Triple("OS ACTION COMPLETED ⚡", Color(0xFF2E7D32), Color.White)
        is AndroidActionState.Cancelled -> Triple("ACTION CANCELLED", Color(0xFF757575), Color.White)
        is AndroidActionState.Failed -> Triple("ACTION FAILED", Color(0xFFC62828), Color.White)
        is AndroidActionState.Timeout -> Triple("ACTION TIMEOUT", Color(0xFFB71C1C), Color.White)
        is AndroidActionState.Error -> Triple("ACTION ERROR", Color(0xFFC62828), Color.White)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text("ANDROID ACTION LAYER (OS FRAMEWORK INTERACTION)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .background(backgroundColor, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                )
            }
        }
    }
}

@Composable
private fun AndroidActionMetricsCard(
    lastAction: String,
    executionMs: Long,
    successCount: Int,
    failureCount: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            MetricRow("Last Executed OS Action", if (lastAction.isEmpty()) "None" else lastAction)
            MetricRow("Execution Latency", "${executionMs}ms")
            MetricRow("Success / Failures", "$successCount / $failureCount")
        }
    }
}

@Composable
private fun MemoryStateBadgeCard(memoryState: MemoryState) {
    val (label, backgroundColor, textColor) = when (memoryState) {
        is MemoryState.Idle -> Triple("MEMORY ENGINE IDLE 🧠💾", Color(0xFF424242), Color.White)
        is MemoryState.Reading -> Triple("READING MEMORY RECORD...", Color(0xFF0288D1), Color.White)
        is MemoryState.Writing -> Triple("WRITING NEW RECORD...", Color(0xFF1565C0), Color.White)
        is MemoryState.Updating -> Triple("UPDATING MEMORY...", Color(0xFF00838F), Color.White)
        is MemoryState.Deleting -> Triple("DELETING RECORD...", Color(0xFFC62828), Color.White)
        is MemoryState.Searching -> Triple("SEARCHING MEMORIES...", Color(0xFFF57C00), Color.White)
        is MemoryState.Ranking -> Triple("RANKING CANDIDATES...", Color(0xFF6A1B9A), Color.White)
        is MemoryState.Completed -> Triple("MEMORY OPERATION COMPLETE ⚡", Color(0xFF2E7D32), Color.White)
        is MemoryState.Error -> Triple("MEMORY ENGINE ERROR", Color(0xFFC62828), Color.White)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text("MEMORY ENGINE (SHORT & LONG TERM)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .background(backgroundColor, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                )
            }
        }
    }
}

@Composable
private fun MemoryMetricsCard(
    totalCount: Int,
    reads: Int,
    writes: Int,
    retrievalMs: Long,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            MetricRow("Total Stored Records", "$totalCount")
            MetricRow("Memory Reads / Writes", "$reads / $writes")
            MetricRow("Last Retrieval Duration", "${retrievalMs}ms")
        }
    }
}

@Composable
private fun ToolStateBadgeCard(toolState: ToolExecutionState) {
    val (label, backgroundColor, textColor) = when (toolState) {
        is ToolExecutionState.Idle -> Triple("TOOL FRAMEWORK IDLE ⚙️", Color(0xFF424242), Color.White)
        is ToolExecutionState.Queued -> Triple("QUEUED DISPATCH...", Color(0xFF0288D1), Color.White)
        is ToolExecutionState.Validating -> Triple("VALIDATING TOOL...", Color(0xFF1565C0), Color.White)
        is ToolExecutionState.WaitingPermission -> Triple("WAITING PERMISSIONS 🔑", Color(0xFFE65100), Color.White)
        is ToolExecutionState.Ready -> Triple("TOOL READY", Color(0xFF00838F), Color.White)
        is ToolExecutionState.Executing -> Triple("EXECUTING TOOL...", Color(0xFFF57C00), Color.White)
        is ToolExecutionState.Completed -> Triple("EXECUTION SUCCESSFUL ⚡", Color(0xFF2E7D32), Color.White)
        is ToolExecutionState.Cancelled -> Triple("CANCELLED", Color(0xFF757575), Color.White)
        is ToolExecutionState.Failed -> Triple("EXECUTION FAILED", Color(0xFFC62828), Color.White)
        is ToolExecutionState.Timeout -> Triple("EXECUTION TIMEOUT", Color(0xFFB71C1C), Color.White)
        is ToolExecutionState.Error -> Triple("FRAMEWORK ERROR", Color(0xFFC62828), Color.White)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text("TOOL EXECUTION FRAMEWORK", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .background(backgroundColor, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                )
            }
        }
    }
}

@Composable
private fun ToolMetricsCard(
    lastTool: String,
    executionMs: Long,
    successCount: Int,
    failureCount: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            MetricRow("Last Executed Tool", if (lastTool.isEmpty()) "None" else lastTool)
            MetricRow("Execution Duration", "${executionMs}ms")
            MetricRow("Success / Failures", "$successCount / $failureCount")
        }
    }
}

@Composable
private fun BrainStateBadgeCard(brainState: BrainState) {
    val (label, backgroundColor, textColor) = when (brainState) {
        is BrainState.Idle -> Triple("BRAIN IDLE 🧠", Color(0xFF424242), Color.White)
        is BrainState.ReceivingInput -> Triple("RECEIVING INPUT...", Color(0xFF0288D1), Color.White)
        is BrainState.Understanding -> Triple("UNDERSTANDING INTENT...", Color(0xFF1565C0), Color.White)
        is BrainState.Planning -> Triple("GENERATING EXECUTION PLAN 📝", Color(0xFFF57C00), Color.White)
        is BrainState.WaitingProvider -> Triple("WAITING PROVIDER STREAM 🌐", Color(0xFF6A1B9A), Color.White)
        is BrainState.WaitingTools -> Triple("WAITING TOOL DISPATCH ⚙️", Color(0xFFE65100), Color.White)
        is BrainState.GeneratingResponse -> Triple("SYNTHESIZING STRATEGY...", Color(0xFF00838F), Color.White)
        is BrainState.Completed -> Triple("THINKING COMPLETE ⚡", Color(0xFF2E7D32), Color.White)
        is BrainState.Error -> Triple("COGNITIVE ERROR", Color(0xFFC62828), Color.White)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text("BRAIN ENGINE (INTELLIGENCE & REASONING)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .background(backgroundColor, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                )
            }
        }
    }
}

@Composable
private fun BrainMetricsCard(
    intentCategory: String,
    confidence: Float,
    processingMs: Long,
    planningMs: Long,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            MetricRow("Classified Intent", intentCategory)
            MetricRow("Cognitive Thinking Time", "${processingMs}ms")
            MetricRow("Planner Duration", "${planningMs}ms")
        }
    }
}

@Composable
private fun ConversationStateBadgeCard(convState: ConversationState) {
    val (label, backgroundColor, textColor) = when (convState) {
        is ConversationState.Disconnected -> Triple("DISCONNECTED", Color(0xFF424242), Color.White)
        is ConversationState.Initializing -> Triple("INITIALIZING PROVIDER...", Color(0xFF0288D1), Color.White)
        is ConversationState.Connecting -> Triple("CONNECTING TO AI...", Color(0xFF1565C0), Color.White)
        is ConversationState.Connected -> Triple("AI CONNECTED 🟢", Color(0xFF2E7D32), Color.White)
        is ConversationState.StreamingInput -> Triple("STREAMING AUDIO IN 📤", Color(0xFF00838F), Color.White)
        is ConversationState.Generating -> Triple("GEMINI GENERATING... ⚡", Color(0xFF6A1B9A), Color.White)
        is ConversationState.StreamingOutput -> Triple("STREAMING RESPONSE 📥", Color(0xFFAB47BC), Color.White)
        is ConversationState.Cancelling -> Triple("CANCELLING GENERATION ⚡", Color(0xFFE65100), Color.White)
        is ConversationState.Reconnecting -> Triple("RECONNECTING...", Color(0xFFF57C00), Color.White)
        is ConversationState.Closed -> Triple("CLOSED", Color(0xFF424242), Color.White)
        is ConversationState.Error -> Triple("PROVIDER ERROR", Color(0xFFC62828), Color.White)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("AI CONVERSATION ENGINE", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .background(backgroundColor, RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                )
            }
        }
    }
}

@Composable
private fun ConversationMetricsCard(
    providerName: String,
    bytesSent: Long,
    bytesReceived: Long,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MetricRow("Active AI Provider", providerName)
            MetricRow("Bytes Streamed Out", "$bytesSent B")
            MetricRow("Bytes Received", "$bytesReceived B")
        }
    }
}

@Composable
private fun SessionStateBadgeCard(sessionState: VoiceSessionState) {
    val (label, backgroundColor, textColor) = when (sessionState) {
        is VoiceSessionState.Idle -> Triple("SESSION IDLE", Color(0xFF424242), Color.White)
        is VoiceSessionState.Starting -> Triple("STARTING SESSION...", Color(0xFF0288D1), Color.White)
        is VoiceSessionState.WaitingForUser -> Triple("WAITING FOR USER 🎙️", Color(0xFF1565C0), Color.White)
        is VoiceSessionState.Listening -> Triple("USER LISTENING...", Color(0xFF2E7D32), Color.White)
        is VoiceSessionState.Processing -> Triple("AI PROCESSING...", Color(0xFFF57C00), Color.White)
        is VoiceSessionState.Responding -> Triple("ASSISTANT RESPONDING 🔊", Color(0xFF7B1FA2), Color.White)
        is VoiceSessionState.Interrupted -> Triple("USER INTERRUPTED! ⚡", Color(0xFFE65100), Color.White)
        is VoiceSessionState.Ending -> Triple("ENDING SESSION...", Color(0xFF616161), Color.White)
        is VoiceSessionState.Ended -> Triple("SESSION ENDED", Color(0xFF424242), Color.White)
        is VoiceSessionState.Error -> Triple("SESSION ERROR", Color(0xFFC62828), Color.White)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("VOICE SESSION ORCHESTRATOR", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .background(backgroundColor, RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                )
            }
        }
    }
}

@Composable
private fun SessionMetricsCard(
    sessionId: String,
    durationMs: Long,
    interruptCount: Int,
    silenceTimeoutCount: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MetricRow("Active Session ID", if (sessionId.isNotEmpty()) sessionId else "None")
            MetricRow("Session Duration", "${durationMs / 1000}s")
            MetricRow("User Interruptions", interruptCount.toString())
            MetricRow("Silence Timeouts", silenceTimeoutCount.toString())
        }
    }
}

@Composable
private fun SpeakerStateBadgeCard(status: VerificationResult) {
    val (label, backgroundColor, textColor) = when (status) {
        is VerificationResult.NotEnrolled -> Triple("NOT ENROLLED ⚠️", Color(0xFF757575), Color.White)
        is VerificationResult.Ready -> Triple("READY (ENROLLED)", Color(0xFF0288D1), Color.White)
        is VerificationResult.Verifying -> Triple("VERIFYING EMBEDDING...", Color(0xFFF57C00), Color.White)
        is VerificationResult.Verified -> Triple("VERIFIED OWNER! ⚡", Color(0xFF2E7D32), Color.White)
        is VerificationResult.Rejected -> Triple("REJECTED SPEAKER 🛑", Color(0xFFC62828), Color.White)
        is VerificationResult.Error -> Triple("ERROR", Color(0xFFC62828), Color.White)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("SPEAKER VERIFICATION STATUS", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .background(backgroundColor, RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                )
            }
        }
    }
}

@Composable
private fun SpeakerMetricsCard(
    isEnrolled: Boolean,
    verificationCount: Int,
    inferenceTimeMs: Long,
    onClearProfile: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MetricRow("Enrollment Status", if (isEnrolled) "ENROLLED (Encrypted)" else "NOT ENROLLED")
            MetricRow("Verifications Run", verificationCount.toString())
            MetricRow("ECAPA-TDNN Inference", "${inferenceTimeMs}ms")
            if (isEnrolled) {
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onClearProfile,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC62828)),
                ) {
                    Text("Clear Enrolled Owner Profile", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun WakeWordStateBadgeCard(state: WakeWordState) {
    val (label, backgroundColor, textColor) = when (state) {
        is WakeWordState.Idle -> Triple("IDLE (PAUSED)", Color(0xFF424242), Color.White)
        is WakeWordState.Listening -> Triple("LISTENING (SCANNING)", Color(0xFF1565C0), Color.White)
        is WakeWordState.WakeDetected -> Triple("WAKE DETECTED! ⚡", Color(0xFF2E7D32), Color.White)
        is WakeWordState.Cooldown -> Triple("COOLDOWN", Color(0xFFE65100), Color.White)
        is WakeWordState.Error -> Triple("ERROR", Color(0xFFC62828), Color.White)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("WAKE WORD STATE", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .background(backgroundColor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                )
            }
        }
    }
}

@Composable
private fun WakeWordMetricsCard(
    modelName: String,
    detectionCount: Int,
    lastTriggerTimestamp: Long,
) {
    val timeStr = if (lastTriggerTimestamp > 0L) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastTriggerTimestamp))
    } else "None"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MetricRow("Loaded Model", modelName)
            MetricRow("Wake Triggers Count", detectionCount.toString())
            MetricRow("Last Trigger Time", timeStr)
        }
    }
}

@Composable
private fun StateBadgeCard(state: VadState) {
    val (label, backgroundColor, textColor) = when (state) {
        is VadState.Idle -> Triple("IDLE", Color(0xFF424242), Color.White)
        is VadState.SpeechStarted -> Triple("SPEECH STARTED", Color(0xFFF57C00), Color.White)
        is VadState.Speaking -> Triple("SPEAKING", Color(0xFF2E7D32), Color.White)
        is VadState.SpeechEnded -> Triple("SPEECH ENDED", Color(0xFF0288D1), Color.White)
        is VadState.Error -> Triple("ERROR", Color(0xFFC62828), Color.White)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("CURRENT STATE", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .background(backgroundColor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                )
            }
            if (state is VadState.Error) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = state.message,
                    fontSize = 12.sp,
                    color = Color(0xFFEF5350),
                )
            }
        }
    }
}

@Composable
private fun ConfidenceCard(
    label: String = "SPEECH CONFIDENCE",
    confidence: Float,
    extraInfo: String = "",
) {
    val percentage = (confidence * 100).toInt()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = if (extraInfo.isNotEmpty()) "$percentage% ($extraInfo)" else "$percentage%",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { confidence.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = if (confidence >= 0.5f) Color(0xFF4CAF50) else Color(0xFFFFB74D),
            )
        }
    }
}

@Composable
private fun MetricsCard(
    micStatus: String,
    sampleRate: Int,
    detectionCount: Int,
    speechFrames: Int,
    silenceFrames: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MetricRow("Microphone Status", micStatus)
            MetricRow("Sample Rate", "$sampleRate Hz")
            MetricRow("Total Frames Analyzed", detectionCount.toString())
            MetricRow("Speech Frames", speechFrames.toString())
            MetricRow("Silence Frames", silenceFrames.toString())
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
