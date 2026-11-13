// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.billing

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only non-sensitive entitlement metadata. Tokens/credentials are never persisted here. */
internal class RoomEntitlementCache(context: Context, databaseName: String = "billing.db") : EntitlementCache, AutoCloseable {
    private val database = Room.databaseBuilder(context.applicationContext, BillingDatabase::class.java, databaseName).build()
    override suspend fun read() = withContext(Dispatchers.IO) {
        database.billing().entitlement()?.let { CachedEntitlement(it.pro, it.verifiedAt) }
    }
    override suspend fun write(value: CachedEntitlement) = withContext(Dispatchers.IO) {
        database.billing().save(BillingEntitlementEntity(1, value.pro, value.verifiedAt))
    }
    override fun close() = database.close()
}
