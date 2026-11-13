// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.tailnet;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(entities = {TailnetProfileEntity.class, TailnetSelectionEntity.class, TailnetAccessEntity.class}, version = 2, exportSchema = true)
public abstract class TailnetProfileDatabase extends RoomDatabase {
    public abstract TailnetProfileDao profiles();
}
