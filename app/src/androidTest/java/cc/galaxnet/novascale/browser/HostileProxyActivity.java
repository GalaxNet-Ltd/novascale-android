// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;

/** Standalone Java so this companion app needs no target-app/Kotlin classes. */
public final class HostileProxyActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        final int port = getIntent().getIntExtra("port", 0);
        new Thread(() -> {
            boolean passed = false;
            try {
                for (String credential : new String[]{"", "Proxy-Authorization: Basic YmFkOmJhZA==\r\n"}) {
                    try (Socket socket = new Socket("127.0.0.1", port)) {
                        socket.setSoTimeout(5000);
                        socket.getOutputStream().write(("CONNECT example.invalid:443 HTTP/1.1\r\nHost: example.invalid:443\r\n" + credential + "\r\n").getBytes("UTF-8"));
                        String reply = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8")).readLine();
                        if (reply == null || !reply.contains(" 407 ")) throw new Exception("HTTP auth bypass");
                    }
                }
                try (Socket socket = new Socket("127.0.0.1", port)) {
                    socket.setSoTimeout(5000);
                    socket.getOutputStream().write(new byte[]{5, 1, 0});
                    if (socket.getInputStream().read() != 5 || socket.getInputStream().read() != 255) throw new Exception("SOCKS auth bypass");
                }
                passed = true;
            } catch (Exception ignored) { }
            final boolean result = passed;
            runOnUiThread(() -> {
                setResult(result ? RESULT_OK : RESULT_CANCELED, new Intent().putExtra("uid", Process.myUid()));
                finish();
            });
        }, "hostile-proxy-probe").start();
    }
}
