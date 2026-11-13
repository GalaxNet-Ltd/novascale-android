// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.tailnet;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "tailnet_profiles")
public class TailnetProfileEntity {
    @PrimaryKey @NonNull public String id;
    @NonNull public String name;
    @NonNull public String controlUrl;
    public long createdAt;
    public boolean pendingDelete;

    public TailnetProfileEntity(@NonNull String id, @NonNull String name,
            @NonNull String controlUrl, long createdAt, boolean pendingDelete) {
        this.id = id; this.name = name; this.controlUrl = controlUrl;
        this.createdAt = createdAt; this.pendingDelete = pendingDelete;
    }
}
