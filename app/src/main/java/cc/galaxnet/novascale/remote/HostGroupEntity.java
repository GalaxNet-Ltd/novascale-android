/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.remote;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "host_groups", indices = {@Index(value = {"name"}, unique = true)})
public final class HostGroupEntity {
    @PrimaryKey @NonNull public final String id;
    @NonNull @ColumnInfo(collate = ColumnInfo.NOCASE) public final String name;

    public HostGroupEntity(@NonNull String id, @NonNull String name) {
        this.id = id;
        this.name = name;
    }
}
