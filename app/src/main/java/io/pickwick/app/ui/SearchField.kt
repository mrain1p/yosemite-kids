package io.pickwick.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The kid-facing search field. Whitelist-scoped — it only ever searches the
 * family's indexed channels, so there is no "whole of YouTube" to leak into.
 *
 * The IME's search action submits (also what the Google TV keyboard offers
 * on its enter key). On phones a microphone button hands the query to the
 * system speech recognizer: a six-year-old can say "volcanoes" long before
 * they can spell it. The recognizer is a system intent, not a permission —
 * the device that hosts it (Google's app) does the listening.
 */
@Composable
internal fun SearchField(
    onSearch: (String) -> Unit,
    /** Pre-filled query (the results page keeps the field with its query). */
    initial: String = "",
    /** Voice input is phone/tablet only; the TV keyboard has its own mic. */
    voice: Boolean = false,
    onVoiceUnavailable: () -> Unit = {}
) {
    var query by remember(initial) { mutableStateOf(initial) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val speech = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val heard = result.data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()?.trim().orEmpty()
        if (heard.isNotEmpty()) {
            query = heard
            onSearch(heard)
        }
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        placeholder = { Text("Search your channels") },
        singleLine = true,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        leadingIcon = {
            Icon(androidx.compose.material.icons.Icons.Filled.Search, contentDescription = null)
        },
        trailingIcon = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(androidx.compose.material.icons.Icons.Filled.Clear, contentDescription = "Clear")
                    }
                }
                if (voice) {
                    IconButton(
                        onClick = {
                            val intent = android.content.Intent(
                                android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                            ).apply {
                                putExtra(
                                    android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                )
                                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "What do you want to watch?")
                            }
                            runCatching { speech.launch(intent) }
                                .onFailure { onVoiceUnavailable() }
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        // Drawn mic: the icon pack's Mic lives in the extended
                        // set, which is megabytes of dex for one glyph.
                        MicGlyph()
                    }
                }
            }
        },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = androidx.compose.ui.text.input.ImeAction.Search
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onSearch = { if (query.isNotBlank()) onSearch(query.trim()) }
        )
    )
}

@Composable
private fun MicGlyph() {
    val ink = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(Modifier.size(26.dp)) {
        val w = size.width
        val h = size.height
        // Capsule head
        drawRoundRect(
            ink,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.34f, h * 0.06f),
            size = androidx.compose.ui.geometry.Size(w * 0.32f, h * 0.52f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.16f)
        )
        // Cradle arc
        drawArc(
            ink, startAngle = 20f, sweepAngle = 140f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.18f),
            size = androidx.compose.ui.geometry.Size(w * 0.64f, h * 0.60f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.09f)
        )
        // Stem + base
        drawRect(ink, androidx.compose.ui.geometry.Offset(w * 0.46f, h * 0.76f), androidx.compose.ui.geometry.Size(w * 0.08f, h * 0.14f))
        drawRect(ink, androidx.compose.ui.geometry.Offset(w * 0.30f, h * 0.88f), androidx.compose.ui.geometry.Size(w * 0.40f, h * 0.07f))
    }
}
