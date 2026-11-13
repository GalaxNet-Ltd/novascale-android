// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.security.KeyStore
import java.util.UUID

/** Real Room and Android Keystore, isolated from the owner's saved sites. */
internal object BrowserLibraryChecks {
    fun run(test: Instrumentation) = runBlocking {
        val name = "browser-fixture-${UUID.randomUUID()}.db"
        val alias = "novascale.browser.test.${UUID.randomUUID()}"
        var db = Room.databaseBuilder(test.targetContext, BrowserLibraryDatabase::class.java, name).build()
        try {
            var library = BrowserLibrary(test.targetContext, db, alias)
            val url = "https://dashboard.example/a%2Fb?token=fixture-secret#page"
            check(library.load("work").isEmpty())
            library.visit("work", url, "Dashboard", 10)
            library.visit("work", url, "Dashboard updated", 11)
            check(library.load("work").single().visited == 11L)
            library.saveFavorite("work", url, "My dashboard")
            library.visit("personal", url, "Personal", 12)
            check(library.load("personal").single().title == "Personal")
            check(!library.load("personal").single().favorite)
            check(library.load("work").single().title == "My dashboard")
            val bytes = db.sites().all("work").single().payload.toString(Charsets.ISO_8859_1)
            check(!bytes.contains("fixture-secret") && !bytes.contains("My dashboard"))
            db.close()
            db = Room.databaseBuilder(test.targetContext, BrowserLibraryDatabase::class.java, name).build()
            library = BrowserLibrary(test.targetContext, db, alias)
            check(library.load("work").single().url == url)
            coroutineScope { repeat(12) { launch { library.visit("work", url, "Repeated", 20) } } }
            check(library.load("work").size == 1)
            repeat(205) { library.visit("work", "http://site$it/", "Site $it", 100L + it) }
            val sites = library.load("work")
            check(sites.count { it.visited > 0 } == 200)
            check(sites.single { it.favorite }.title == "My dashboard")
            check(library.clearHistory("work").single().visited == 0L)
            val favorite = library.load("work").single()
            val edited = library.saveFavorite("work", "https://new.example/", "Renamed", favorite.id).single()
            check(edited.id == favorite.id && edited.url == "https://new.example/")
            check(library.removeFavorite("work", edited.id).isEmpty())
            check(runCatching { library.saveFavorite("work", "https://user:secret@host/", "Unsafe") }.isFailure)
            library.removeProfile("personal")
            check(db.sites().all("personal").isEmpty())
            check(runCatching { library.visit("personal", url, "Late callback") }.isFailure)
            // A corrupt ciphertext must not silently become an empty, writable library.
            library.visit("work", "http://tamper.example/", "Test")
            val row = db.sites().all("work").single()
            row.payload[row.payload.lastIndex] = (row.payload.last().toInt() xor 1).toByte()
            db.sites().save(row)
            check(runCatching { library.load("work") }.isFailure)
        } finally {
            db.close(); test.targetContext.deleteDatabase(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
