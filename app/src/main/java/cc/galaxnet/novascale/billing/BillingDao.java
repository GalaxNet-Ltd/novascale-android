// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.billing;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface BillingDao {
    @Query("SELECT * FROM entitlement WHERE id = 1") BillingEntitlementEntity entitlement();
    @Insert(onConflict = OnConflictStrategy.REPLACE) void save(BillingEntitlementEntity value);
}
