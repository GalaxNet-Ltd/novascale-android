// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser;

import android.app.Activity;
import android.content.Intent;

/** Runs under the test APK's own UID, without the target app's Kotlin runtime. */
public final class SessionLauncherActivity extends Activity {
    @Override public void onResume() {
        super.onResume();
        String targetPackage = getIntent().getStringExtra("targetPackage");
        if (targetPackage == null || !targetPackage.endsWith(".debug")) {
            throw new IllegalArgumentException("Use a disposable debug package");
        }
        startActivity(new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(targetPackage, "cc.galaxnet.novascale.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        finish();
    }
}
