// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.tailnet;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
@Entity(tableName = "tailnet_access")
public class TailnetAccessEntity {
    @PrimaryKey public int singleton;
    @NonNull public String freeProfileId;
    @NonNull public String legacyIds;
    public TailnetAccessEntity(int singleton, @NonNull String freeProfileId, @NonNull String legacyIds) {
        this.singleton = singleton; this.freeProfileId = freeProfileId; this.legacyIds = legacyIds;
    }
}
