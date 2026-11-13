// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A bounded large sheet keeps the backdrop and its fixed actions visible. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovaModalSheet(onDismiss: () -> Unit, gesturesEnabled: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val sheetHeight = LocalConfiguration.current.screenHeightDp.dp * .9f
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetGesturesEnabled = gesturesEnabled,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = null) {
        Column(Modifier.height(sheetHeight)) {
            BottomSheetDefaults.DragHandle(modifier = Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally))
            content()
        }
    }
}
