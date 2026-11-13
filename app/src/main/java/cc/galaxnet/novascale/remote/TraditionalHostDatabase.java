/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.remote;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(entities = {TraditionalHostEntity.class, HostGroupEntity.class, HostGroupMembershipEntity.class, PinnedHostEntity.class}, version = 4, exportSchema = true)
public abstract class TraditionalHostDatabase extends RoomDatabase {
    public abstract TraditionalHostDao hosts();
    public abstract HostGroupDao groups();
    public abstract PinnedHostDao pins();
}
