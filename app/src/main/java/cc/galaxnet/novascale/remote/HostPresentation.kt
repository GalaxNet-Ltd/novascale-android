/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.remote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.ui.NovaActionRow
import cc.galaxnet.novascale.ui.NovaGroupedCard
import cc.galaxnet.novascale.ui.NovaIcons
import cc.galaxnet.novascale.ui.NovaPlatformTile
import cc.galaxnet.novascale.ui.NovaSectionLabel
import cc.galaxnet.novascale.ui.NovaStatusDot
import cc.galaxnet.novascale.ui.NovaTheme

internal fun hostMatchesSearch(query: String, name: String, vararg addresses: String): Boolean {
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    return terms.all { term -> (listOf(name) + addresses).any { it.contains(term, ignoreCase = true) } }
}

@Composable
internal fun HostIdentityRow(
    name: String,
    address: String,
    os: String? = null,
    online: Boolean? = null,
    route: String? = null,
    pinned: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)
        .then(if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick))
        .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        NovaPlatformTile(os)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                online?.let { NovaStatusDot(it) }
                Text(address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = NovaTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            route?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = NovaTheme.colors.textSecondary) }
        }
        if (pinned) Icon(Icons.Outlined.PushPin, stringResource(R.string.host_pinned_indicator),
            Modifier.size(18.dp), tint = NovaTheme.colors.primary)
        if (onClick != null) Icon(NovaIcons.ChevronRight, null, tint = NovaTheme.colors.textSecondary, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun HostOrganizationCard(
    groupNames: List<String>, onManageGroups: (() -> Unit)?,
    pinned: Boolean, pinEnabled: Boolean, onTogglePin: (() -> Unit)?,
) {
    if (onManageGroups == null && onTogglePin == null) return
    NovaSectionLabel(stringResource(R.string.hosts_organize))
    NovaGroupedCard(Modifier.fillMaxWidth()) {
        Column {
            onManageGroups?.let { action ->
                NovaActionRow(stringResource(R.string.host_groups), Icons.Outlined.FolderOpen,
                    NovaTheme.colors.menuFile, Modifier.clickable(role = Role.Button, onClick = action),
                    description = groupNames.joinToString(", ").ifEmpty { stringResource(R.string.hosts_groups_none) })
            }
            if (onManageGroups != null && onTogglePin != null) HorizontalDivider(color = NovaTheme.colors.borderSubtle)
            onTogglePin?.let { action ->
                NovaActionRow(stringResource(if (pinned) R.string.host_unpin else R.string.host_pin), Icons.Outlined.PushPin,
                    NovaTheme.colors.primary, Modifier.toggleable(pinned, enabled = pinEnabled, role = Role.Switch, onValueChange = { action() }),
                    description = stringResource(R.string.hosts_pin_help),
                    trailing = { Switch(checked = pinned, onCheckedChange = null, enabled = pinEnabled) })
            }
        }
    }
}
