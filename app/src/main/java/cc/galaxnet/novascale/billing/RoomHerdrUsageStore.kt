// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.billing
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class RoomHerdrUsageStore(context: Context, databaseName: String = "herdr-usage.db") : HerdrUsageStore, AutoCloseable {
    private val database = Room.databaseBuilder(context.applicationContext, HerdrUsageDatabase::class.java, databaseName).build()
    override suspend fun read(): HerdrUsageRecord? = withContext(Dispatchers.IO) {
        database.usage().read()?.let { row ->
            val visits = JSONArray(row.visits)
            HerdrUsageRecord(row.day, row.used, row.partialMs, buildMap {
                for (i in 0 until visits.length()) {
                    val visit = visits.getJSONObject(i)
                    val id = visit.getString("id")
                    put(id, HerdrVisit(id, visit.getString("identity"), visit.getBoolean("charged")))
                }
            })
        }
    }
    override suspend fun write(value: HerdrUsageRecord) = withContext(Dispatchers.IO) {
        val visits = JSONArray()
        value.visits.values.forEach { visits.put(JSONObject().put("id", it.id).put("identity", it.identity).put("charged", it.charged)) }
        database.usage().save(HerdrUsageEntity(1, value.day, value.used, value.partialMs, visits.toString()))
    }
    override fun close() = database.close()
}
