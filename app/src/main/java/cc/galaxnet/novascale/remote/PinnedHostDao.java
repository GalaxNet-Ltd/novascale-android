/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.remote;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface PinnedHostDao {
    @Query("SELECT * FROM pinned_hosts ORDER BY kind, scope, hostId")
    List<PinnedHostEntity> all();

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insert(PinnedHostEntity pin);

    @Query("DELETE FROM pinned_hosts WHERE kind = :kind AND scope = :scope AND hostId = :hostId")
    void delete(String kind, String scope, String hostId);
    @Query("DELETE FROM pinned_hosts WHERE kind = 'tailnet' AND scope = :profileId")
    void removeScope(String profileId);
}
