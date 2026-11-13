// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Activity
import android.content.Intent
import java.util.concurrent.CountDownLatch

/** Non-exported test host, absent from release APKs. */
class ProxyTestActivity : androidx.activity.ComponentActivity() {
    val hostileResult = CountDownLatch(1)
    var hostilePassed = false
    var hostileUid = -1
    @Deprecated("Instrumentation-only legacy result bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42) {
            hostilePassed = resultCode == RESULT_OK
            hostileUid = data?.getIntExtra("uid", -1) ?: -1
            hostileResult.countDown()
        }
    }
}
