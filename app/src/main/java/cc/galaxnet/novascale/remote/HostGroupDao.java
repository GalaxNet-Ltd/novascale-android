/* NovaScale for Android. Copyright (C) 2026 NovaScale contributors. SPDX-License-Identifier: Apache-2.0 */
package cc.galaxnet.novascale.remote;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface HostGroupDao {
    @Query("SELECT * FROM host_groups ORDER BY name COLLATE NOCASE, id")
    List<HostGroupEntity> all();

    @Query("SELECT * FROM host_group_memberships")
    List<HostGroupMembershipEntity> memberships();

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insert(HostGroupEntity group);

    @Query("UPDATE host_groups SET name = :name WHERE id = :id")
    int rename(String id, String name);

    @Query("DELETE FROM host_groups WHERE id = :id")
    int delete(String id);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertMembership(HostGroupMembershipEntity membership);

    @Query("DELETE FROM host_group_memberships WHERE kind = :kind AND scope = :scope AND hostId = :hostId")
    void removeHost(String kind, String scope, String hostId);

    @Query("DELETE FROM host_group_memberships WHERE groupId = :groupId AND kind = :kind AND scope = :scope AND hostId = :hostId")
    void removeMembership(String groupId, String kind, String scope, String hostId);
    @Query("DELETE FROM host_group_memberships WHERE kind = 'tailnet' AND scope = :profileId")
    void removeScope(String profileId);
}
