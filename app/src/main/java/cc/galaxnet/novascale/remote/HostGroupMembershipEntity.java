/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.remote;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

@Entity(tableName = "host_group_memberships",
        primaryKeys = {"groupId", "kind", "scope", "hostId"},
        foreignKeys = @ForeignKey(entity = HostGroupEntity.class, parentColumns = "id",
                childColumns = "groupId", onDelete = ForeignKey.CASCADE),
        indices = {@Index(value = {"kind", "scope", "hostId"})})
public final class HostGroupMembershipEntity {
    @NonNull public final String groupId;
    @NonNull public final String kind;
    @NonNull public final String scope;
    @NonNull public final String hostId;

    public HostGroupMembershipEntity(@NonNull String groupId, @NonNull String kind,
                                     @NonNull String scope, @NonNull String hostId) {
        this.groupId = groupId;
        this.kind = kind;
        this.scope = scope;
        this.hostId = hostId;
    }
}
