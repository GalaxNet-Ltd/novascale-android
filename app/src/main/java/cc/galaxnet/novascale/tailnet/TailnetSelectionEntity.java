// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.tailnet;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "tailnet_selection")
public class TailnetSelectionEntity {
    @PrimaryKey public int singleton;
    @NonNull public String profileId;

    public TailnetSelectionEntity(int singleton, @NonNull String profileId) {
        this.singleton = singleton; this.profileId = profileId;
    }
}
