/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.remote;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface TraditionalHostDao {
    @Query("SELECT * FROM traditional_hosts ORDER BY name COLLATE NOCASE, id")
    List<TraditionalHostEntity> all();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void save(TraditionalHostEntity host);

    @Query("UPDATE traditional_hosts SET pendingCredential = NULL WHERE id = :id")
    void clearPending(String id);

    @Query("UPDATE traditional_hosts SET pendingDelete = 1, pendingCredential = NULL WHERE id = :id")
    void markDeleted(String id);

    @Query("DELETE FROM traditional_hosts WHERE id = :id")
    void delete(String id);

    @Query("SELECT COUNT(*) FROM traditional_hosts WHERE id = :id AND pendingDelete = 0")
    int exists(String id);
}
