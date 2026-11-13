// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "browser_sites", indices = {@Index("profile")})
public class BrowserSiteEntity {
    @PrimaryKey @NonNull public final String id;
    @NonNull public final String profile;
    @NonNull public final byte[] payload;
    public final long visited;
    public final boolean favorite;
    public BrowserSiteEntity(@NonNull String id, @NonNull String profile, @NonNull byte[] payload, long visited, boolean favorite) {
        this.id = id; this.profile = profile; this.payload = payload; this.visited = visited; this.favorite = favorite;
    }
}
