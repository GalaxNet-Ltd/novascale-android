// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.billing;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "entitlement")
public class BillingEntitlementEntity {
    @PrimaryKey public int id;
    public boolean pro;
    public long verifiedAt;
    public BillingEntitlementEntity(int id, boolean pro, long verifiedAt) {
        this.id = id; this.pro = pro; this.verifiedAt = verifiedAt;
    }
}
