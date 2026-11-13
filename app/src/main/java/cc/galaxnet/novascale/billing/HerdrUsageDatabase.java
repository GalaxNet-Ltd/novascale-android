// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.billing;
import androidx.room.*;
@Database(entities = {HerdrUsageEntity.class}, version = 1, exportSchema = true)
public abstract class HerdrUsageDatabase extends RoomDatabase {
    public abstract HerdrUsageDao usage();
}
