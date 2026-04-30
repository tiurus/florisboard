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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.media.KeyboardLikeButton
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.jetpref.datastore.model.collectAsState
import org.florisboard.lib.android.AndroidKeyguardManager
import org.florisboard.lib.android.systemService
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

private const val MaxAiClipboardItems = 20

@Composable
fun AiInputLayout(
    modifier: Modifier = Modifier,
) {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val clipboardManager by context.clipboardManager()
    val keyboardManager by context.keyboardManager()
    val androidKeyguardManager = remember { context.systemService(AndroidKeyguardManager::class) }

    val historyEnabled by prefs.clipboard.historyEnabled.collectAsState()
    val history by clipboardManager.historyFlow.collectAsState()
    val deviceLocked = androidKeyguardManager.let { it.isDeviceLocked || it.isKeyguardLocked }
    val recentTextItems = remember(history) {
        history.all
            .filter { it.type == ItemType.TEXT && !it.isSensitive && !it.text.isNullOrBlank() }
            .take(MaxAiClipboardItems)
    }

    SnyggColumn(
        modifier = modifier
            .fillMaxWidth()
            .height(FlorisImeSizing.imeUiHeight()),
    ) {
        HeaderRow()
        SnyggBox(
            elementName = FlorisImeUi.ClipboardContent.elementName,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when {
                deviceLocked -> MessageView(
                    title = stringRes(R.string.clipboard__locked__title),
                    message = stringRes(R.string.clipboard__locked__message),
                )
                !historyEnabled -> MessageView(
                    title = stringRes(R.string.clipboard__disabled__title),
                    message = stringRes(R.string.clipboard__disabled__message),
                )
                recentTextItems.isEmpty() -> MessageView(
                    title = stringRes(R.string.ai__empty__title),
                    message = stringRes(R.string.ai__empty__message),
                )
                else -> AiClipboardList(
                    items = recentTextItems,
                    onClickItem = { clipboardManager.pasteItem(it) },
                )
            }
        }
    }
}

@Composable
private fun HeaderRow() {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val sizeModifier = Modifier
        .sizeIn(maxHeight = FlorisImeSizing.smartbarHeight)
        .aspectRatio(1f)

    SnyggRow(
        elementName = FlorisImeUi.ClipboardHeader.elementName,
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
        SnyggIcon(
            modifier = Modifier.padding(horizontal = 8.dp),
            imageVector = Icons.Default.AutoAwesome,
        )
        SnyggText(
            elementName = FlorisImeUi.ClipboardHeaderText.elementName,
            modifier = Modifier.weight(1f),
            text = stringRes(R.string.ai__header_title),
        )
        KeyboardLikeButton(
            modifier = sizeModifier,
            inputEventDispatcher = keyboardManager.inputEventDispatcher,
            keyData = TextKeyData.DELETE,
            elementName = FlorisImeUi.ClipboardHeaderButton.elementName,
        ) {
            SnyggIcon(imageVector = Icons.AutoMirrored.Outlined.Backspace)
        }
    }
}

@Composable
private fun AiClipboardList(
    items: List<ClipboardItem>,
    onClickItem: (ClipboardItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.id }) { item ->
            SnyggBox(
                elementName = FlorisImeUi.ClipboardItem.elementName,
                attributes = mapOf("type" to "text"),
                modifier = Modifier.fillMaxWidth(),
                clickAndSemanticsModifier = Modifier.clickable {
                    onClickItem(item)
                },
            ) {
                SnyggText(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    text = item.displayText().previewLines(),
                )
            }
        }
    }
}

private fun String.previewLines(): String {
    val preview = lineSequence().take(3).joinToString("\n")
    return if (preview.length > 240) {
        preview.take(240).trimEnd() + "..."
    } else {
        preview
    }
}

@Composable
private fun MessageView(
    title: String,
    message: String,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SnyggIcon(imageVector = Icons.Default.AutoAwesome)
        Spacer(modifier = Modifier.height(12.dp))
        SnyggText(text = title)
        Spacer(modifier = Modifier.height(8.dp))
        SnyggText(text = message)
    }
}
