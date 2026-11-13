/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.remote;

import androidx.annotation.NonNull;
import androidx.room.Entity;

@Entity(tableName = "pinned_hosts", primaryKeys = {"kind", "scope", "hostId"})
public final class PinnedHostEntity {
    @NonNull public final String kind;
    @NonNull public final String scope;
    @NonNull public final String hostId;
    public PinnedHostEntity(@NonNull String kind, @NonNull String scope, @NonNull String hostId) {
        this.kind = kind;
        this.scope = scope;
        this.hostId = hostId;
    }
}
