package com.example.diagnostics

import android.util.Log
import com.example.model.AiDiagnosticUiState
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout

/**
 * Gemini 4 Carbon high-thinking network diagnostics advisor powered by Firebase AI Logic.
 * Analyzes direct peer-to-peer socket routing, mDNS/NSD discovery, STUN NAT mapping, and VPN MTU/DNS routing.
 */
class AiDiagnosticsService {

    private val model by lazy {
        val config = generationConfig {
            thinkingConfig = thinkingConfig {
                thinkingLevel = ThinkingLevel.HIGH
                includeThoughts = true
            }
        }
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = "gemini-4-carbon",
            generationConfig = config,
            systemInstruction = content {
                text(
                    "You are a senior Android VPN, peer-to-peer socket, and networking diagnostics engineer for WiFiShare. " +
                        "WiFiShare operates completely serverless (direct peer-to-peer Host Gateway sockets, mDNS/NSD LAN discovery, " +
                        "ECDH + AES-256-GCM encryption, and Android VpnService with all state saved locally in Room). " +
                        "Provide precise, actionable root-cause analysis and concrete remediation steps for direct TCP/UDP connectivity, " +
                        "mDNS/NSD discovery, NAT/firewall traversal, DNS leaks, MTU fragmentation, and Android foreground/VPN services."
                )
            }
        )
    }

    private val _uiState = MutableStateFlow<AiDiagnosticUiState>(AiDiagnosticUiState.Idle)
    val uiState: StateFlow<AiDiagnosticUiState> = _uiState.asStateFlow()

    suspend fun analyzeDiagnostics(
        userQuery: String,
        telemetryContext: String
    ) {
        _uiState.value = AiDiagnosticUiState.Loading
        val thoughtsBuilder = StringBuilder()
        val answerBuilder = StringBuilder()
        val fullPrompt = buildString {
            appendLine("Device & Tunnel Telemetry:")
            appendLine(telemetryContext)
            appendLine()
            appendLine("Diagnostic Question:")
            appendLine(userQuery)
        }

        try {
            withTimeout(60_000L) {
                model.generateContentStream(fullPrompt).collect { chunk ->
                    val thought = chunk.thoughtSummary
                    if (!thought.isNullOrEmpty()) {
                        thoughtsBuilder.append(thought)
                    }
                    val text = chunk.text
                    if (!text.isNullOrEmpty()) {
                        answerBuilder.append(text)
                    }
                    _uiState.value = AiDiagnosticUiState.Streaming(
                        thoughts = thoughtsBuilder.toString(),
                        answer = answerBuilder.toString()
                    )
                }
            }
            if (answerBuilder.isNotBlank()) {
                _uiState.value = AiDiagnosticUiState.Success(
                    thoughts = thoughtsBuilder.toString(),
                    answer = answerBuilder.toString()
                )
            } else {
                _uiState.value = AiDiagnosticUiState.Error("Diagnostic model returned an empty response.")
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI diagnostic timed out", e)
            _uiState.value = AiDiagnosticUiState.Error("Diagnostics timed out after 60 seconds. Please try again.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI diagnostic failed", e)
            _uiState.value = AiDiagnosticUiState.Error(e.localizedMessage ?: "Diagnostic analysis failed.")
        }
    }

    fun reset() {
        _uiState.value = AiDiagnosticUiState.Idle
    }

    companion object {
        private const val TAG = "AiDiagnosticsService"
    }
}
