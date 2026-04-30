/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import org.florisboard.lib.android.showShortToastSync
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

private enum class AiIntent(
    val icon: String,
    val label: String,
    val promptInstruction: String,
) {
    Accept(
        icon = "👍",
        label = "Согласиться",
        promptInstruction = "Agree with the message and continue the conversation constructively.",
    ),
    Decline(
        icon = "👎",
        label = "Отказать",
        promptInstruction = "Politely decline the request and keep the reply respectful.",
    ),
    Clarify(
        icon = "❓",
        label = "Уточнить",
        promptInstruction = "Ask a clear follow-up question to clarify the missing details.",
    ),
}

private enum class AiTone(
    val icon: String,
    val label: String,
    val promptInstruction: String,
) {
    Friendly(
        icon = "🙂",
        label = "Дружелюбно",
        promptInstruction = "Use a warm, friendly, natural tone.",
    ),
    Formal(
        icon = "💼",
        label = "Формально",
        promptInstruction = "Use a concise, professional, formal tone.",
    ),
}

@Composable
fun AiInputLayout(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboardManager by context.clipboardManager()
    val history by clipboardManager.historyFlow.collectAsState()
    val lastClipboardText = remember(history) {
        history.all.firstOrNull { item ->
            item.type == ItemType.TEXT && !item.isSensitive && !item.text.isNullOrBlank()
        }?.displayText(context)
    }
    var selectedIntent by remember { mutableStateOf(AiIntent.Accept) }
    var selectedTone by remember { mutableStateOf(AiTone.Friendly) }
    val aiPrompt = remember(lastClipboardText, selectedIntent, selectedTone) {
        buildAiSuggestionPrompt(
            sourceText = lastClipboardText,
            intent = selectedIntent,
            tone = selectedTone,
        )
    }

    SnyggColumn(
        elementName = FlorisImeUi.ClipboardContent.elementName,
        modifier = modifier
            .fillMaxWidth()
            .height(FlorisImeSizing.imeUiHeight())
            .padding(8.dp),
    ) {
        AiControlsRow(
            selectedIntent = selectedIntent,
            onSelectIntent = { selectedIntent = it },
            selectedTone = selectedTone,
            onSelectTone = { selectedTone = it },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            SelectionCard(
                intent = selectedIntent,
                tone = selectedTone,
                onCopyPrompt = {
                    clipboardManager.addNewPlaintext(aiPrompt)
                    context.showShortToastSync("Prompt скопирован")
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            PromptCard(
                prompt = aiPrompt,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LastClipboardCard(
                text = lastClipboardText ?: "Нет недавнего текста в буфере обмена",
            )
        }
    }
}

@Composable
private fun AiControlsRow(
    selectedIntent: AiIntent,
    onSelectIntent: (AiIntent) -> Unit,
    selectedTone: AiTone,
    onSelectTone: (AiTone) -> Unit,
) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val sizeModifier = Modifier
        .sizeIn(maxHeight = FlorisImeSizing.smartbarHeight)
        .aspectRatio(1f)

    SnyggRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(FlorisImeSizing.smartbarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SnyggIconButton(
            elementName = FlorisImeUi.ClipboardHeaderButton.elementName,
            onClick = { keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT },
            modifier = sizeModifier,
        ) {
            SnyggIcon(imageVector = Icons.AutoMirrored.Filled.ArrowBack)
        }
        Spacer(modifier = Modifier.width(4.dp))
        AiIntent.entries.forEach { intent ->
            AiToggleButton(
                text = intent.icon,
                selected = intent == selectedIntent,
                onClick = { onSelectIntent(intent) },
            )
        }
        Separator()
        AiTone.entries.forEach { tone ->
            AiToggleButton(
                text = tone.icon,
                selected = tone == selectedTone,
                onClick = { onSelectTone(tone) },
            )
        }
    }
}

@Composable
private fun SelectionCard(
    intent: AiIntent,
    tone: AiTone,
    onCopyPrompt: () -> Unit,
) {
    SnyggBox(
        elementName = FlorisImeUi.ClipboardItem.elementName,
        attributes = mapOf("type" to "text"),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 0.dp),
    ) {
        SnyggRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AiToggleButton(
                text = "🔄",
                selected = false,
                onClick = onCopyPrompt,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                modifier = Modifier.weight(1f),
                text = "AI Suggestions: ${intent.icon} ${intent.label} / ${tone.icon} ${tone.label}",
            )
        }
    }
}

@Composable
private fun PromptCard(
    prompt: String,
    modifier: Modifier = Modifier,
) {
    SnyggBox(
        elementName = FlorisImeUi.ClipboardItem.elementName,
        attributes = mapOf("type" to "text"),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            SnyggRow(verticalAlignment = Alignment.CenterVertically) {
                SnyggIcon(
                    modifier = Modifier.padding(end = 8.dp),
                    imageVector = Icons.Default.AutoAwesome,
                )
                SnyggText(text = "Prompt для ChatGPT")
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = prompt)
        }
    }
}

@Composable
private fun LastClipboardCard(
    text: String,
    modifier: Modifier = Modifier,
) {
    SnyggBox(
        elementName = FlorisImeUi.ClipboardItem.elementName,
        attributes = mapOf("type" to "text"),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            SnyggRow(verticalAlignment = Alignment.CenterVertically) {
                SnyggIcon(
                    modifier = Modifier.padding(end = 8.dp),
                    imageVector = Icons.Default.AutoAwesome,
                )
                SnyggText(text = "Последний буфер")
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = text)
        }
    }
}

@Composable
private fun Separator() {
    SnyggText(
        modifier = Modifier.padding(horizontal = 6.dp),
        text = "/",
    )
}

@Composable
private fun AiToggleButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SnyggBox(
        elementName = FlorisImeUi.ClipboardFilterChip.elementName,
        attributes = mapOf("state" to if (selected) "active" else "inactive"),
        modifier = Modifier
            .sizeIn(maxHeight = FlorisImeSizing.smartbarHeight)
            .aspectRatio(1f),
        clickAndSemanticsModifier = Modifier.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        SnyggText(
            text = text,
        )
    }
}

private fun buildAiSuggestionPrompt(
    sourceText: String?,
    intent: AiIntent,
    tone: AiTone,
): String {
    val normalizedSourceText = sourceText?.trim().orEmpty()
    return buildString {
        appendLine("You are an assistant that drafts AI Suggestions for a keyboard.")
        appendLine()
        appendLine("Generate the default reply only.")
        appendLine("Intent: ${intent.promptInstruction}")
        appendLine("Tone: ${tone.promptInstruction}")
        appendLine()
        appendLine("Rules:")
        appendLine("- Reply in the same language as the source text.")
        appendLine("- Output only the final message text, without explanations, quotes, markdown, or variants.")
        appendLine("- Keep the answer concise and ready to send.")
        appendLine("- Preserve the meaning of the source text and do not invent facts.")
        appendLine()
        appendLine("Source text:")
        appendLine("\"\"\"")
        appendLine(normalizedSourceText.ifBlank { "No source text is available." })
        appendLine("\"\"\"")
    }.trim()
}
