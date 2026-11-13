// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.billing;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
@Entity(tableName = "usage_ledger")
public class HerdrUsageEntity {
    @PrimaryKey public int id;
    public long day;
    public int used;
    public long partialMs;
    @NonNull public String visits;
    public HerdrUsageEntity(int id, long day, int used, long partialMs, @NonNull String visits) {
        this.id = id; this.day = day; this.used = used; this.partialMs = partialMs; this.visits = visits;
    }
}
