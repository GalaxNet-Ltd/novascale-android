// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.tailnet;

import androidx.room.*;
import java.util.List;

@Dao
public interface TailnetProfileDao {
    @Query("SELECT * FROM tailnet_access WHERE singleton = 1") TailnetAccessEntity access();
    @Insert(onConflict = OnConflictStrategy.REPLACE) void saveAccess(TailnetAccessEntity access);
    @Query("UPDATE tailnet_profiles SET pendingDelete = 0 WHERE id = :id") void commitStaged(String id);
    @Query("SELECT * FROM tailnet_profiles ORDER BY createdAt, id")
    List<TailnetProfileEntity> all();
    @Query("SELECT profileId FROM tailnet_selection WHERE singleton = 1")
    String activeId();
    @Insert void insert(TailnetProfileEntity profile);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void select(TailnetSelectionEntity selection);
    @Query("UPDATE tailnet_profiles SET name = :name WHERE id = :id AND pendingDelete = 0")
    int rename(String id, String name);
    @Query("UPDATE tailnet_profiles SET pendingDelete = 1 WHERE id = :id")
    void markDeleted(String id);
    @Query("DELETE FROM tailnet_profiles WHERE id = :id AND pendingDelete = 1")
    void finishDelete(String id);
}
