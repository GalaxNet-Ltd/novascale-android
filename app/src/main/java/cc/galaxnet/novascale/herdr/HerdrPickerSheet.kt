/* Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.herdr

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.galaxnet.novascale.R
import kotlin.math.roundToInt

/** Native medium/expanded detents, with content and actions sized to the visible part of the sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HerdrPickerSheet(title: String, onDismiss: () -> Unit,
    dismissLabel: Int = R.string.action_done, content: @Composable ColumnScope.() -> Unit) {
    val expandOnOpen = LocalConfiguration.current.screenHeightDp < 600 * LocalDensity.current.fontScale
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = expandOnOpen)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        // Keep the full-height anchor available for dragging up. A full-height
        // child alone would put its footer below the screen at the medium detent.
        Box(Modifier.fillMaxWidth().fillMaxHeight()) {
            Column(Modifier.fillMaxWidth().layout { measurable, constraints ->
                // Read the offset during measurement so IME/anchor changes and
                // the visible footer height are resolved in the same layout pass.
                val offset = runCatching { sheet.requireOffset() }.getOrDefault(0f).roundToInt().coerceAtLeast(0)
                val height = (constraints.maxHeight - offset).coerceAtLeast(0)
                val child = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                layout(child.width, child.height) { child.placeRelative(0, 0) }
            }) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(dismissLabel)) }
                }
                content()
            }
        }
    }
}
