/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.remote;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ColumnInfo;
import androidx.room.PrimaryKey;

@Entity(tableName = "traditional_hosts")
public final class TraditionalHostEntity {
    @PrimaryKey @NonNull public final String id;
    @NonNull public final String name;
    public final String pendingCredential;
    @ColumnInfo(defaultValue = "0") public final boolean pendingDelete;

    public TraditionalHostEntity(@NonNull String id, @NonNull String name, String pendingCredential, boolean pendingDelete) {
        this.id = id;
        this.name = name;
        this.pendingCredential = pendingCredential;
        this.pendingDelete = pendingDelete;
    }
}
