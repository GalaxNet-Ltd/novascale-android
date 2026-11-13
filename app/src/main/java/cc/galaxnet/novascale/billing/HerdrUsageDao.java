// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.billing;
import androidx.room.*;
@Dao public interface HerdrUsageDao {
    @Query("SELECT * FROM usage_ledger WHERE id = 1") HerdrUsageEntity read();
    @Insert(onConflict = OnConflictStrategy.REPLACE) void save(HerdrUsageEntity value);
}
