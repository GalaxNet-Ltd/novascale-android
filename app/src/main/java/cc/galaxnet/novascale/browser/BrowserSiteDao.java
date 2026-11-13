// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser;

import androidx.room.*;
import java.util.List;

@Dao
public interface BrowserSiteDao {
    @Query("SELECT * FROM browser_sites WHERE profile = :profile ORDER BY visited DESC, id") List<BrowserSiteEntity> all(String profile);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void save(BrowserSiteEntity site);
    @Query("DELETE FROM browser_sites WHERE profile = :profile AND id = :id") void delete(String profile, String id);
    @Query("DELETE FROM browser_sites WHERE profile = :profile") void deleteProfile(String profile);
    @Query("DELETE FROM browser_sites WHERE profile = :profile AND favorite = 0") void clearHistory(String profile);
    @Query("UPDATE browser_sites SET visited = 0 WHERE profile = :profile") void clearVisits(String profile);
    @Query("DELETE FROM browser_sites WHERE profile = :profile AND favorite = 0 AND id NOT IN (SELECT id FROM browser_sites WHERE profile = :profile AND visited > 0 ORDER BY visited DESC LIMIT 200)") void trim(String profile);
    @Query("UPDATE browser_sites SET visited = 0 WHERE profile = :profile AND favorite = 1 AND id NOT IN (SELECT id FROM browser_sites WHERE profile = :profile AND visited > 0 ORDER BY visited DESC LIMIT 200)") void trimFavoriteVisits(String profile);
}
