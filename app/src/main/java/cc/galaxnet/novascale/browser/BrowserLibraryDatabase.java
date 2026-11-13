// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(entities = {BrowserSiteEntity.class}, version = 1, exportSchema = true)
public abstract class BrowserLibraryDatabase extends RoomDatabase {
    public abstract BrowserSiteDao sites();
}
