// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.billing;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(entities = {BillingEntitlementEntity.class}, version = 1, exportSchema = true)
public abstract class BillingDatabase extends RoomDatabase {
    public abstract BillingDao billing();
}
