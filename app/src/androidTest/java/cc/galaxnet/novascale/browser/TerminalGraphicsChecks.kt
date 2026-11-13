// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Base64
import cc.galaxnet.novascale.core.GhosttySnapshotParser
import cc.galaxnet.novascale.core.TerminalRenderSnapshot
import cc.galaxnet.novascale.nativecore.NativeCore
import cc.galaxnet.novascale.terminal.NovaTerminalView
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Real packaged Ghostty/Wuffs -> packed snapshot -> Android Canvas, without SSH credentials. */
internal object TerminalGraphicsChecks {
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun compress(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        DeflaterOutputStream(out).use { it.write(bytes) }
    }.toByteArray()
    private fun command(control: String, bytes: ByteArray = byteArrayOf()) = "\u001b_G$control;${b64(bytes)}\u001b\\"
    private fun png(width: Int = 2, height: Int = 2, rgba: ByteArray = byteArrayOf(-1, 0, 0, -1, 0, -1, 0, -1, 0, 0, -1, -1, -1, -1, -1, 0)): ByteArray {
        fun chunk(kind: String, bytes: ByteArray): ByteArray {
            val body = kind.toByteArray() + bytes
            val crc = CRC32().apply { update(body) }.value.toInt()
            return ByteBuffer.allocate(12 + bytes.size).order(ByteOrder.BIG_ENDIAN)
                .putInt(bytes.size).put(body).putInt(crc).array()
        }
        val header = ByteBuffer.allocate(13).putInt(width).putInt(height).put(byteArrayOf(8, 6, 0, 0, 0)).array()
        val pixels = ByteArrayOutputStream().also { out ->
            repeat(height) { row -> out.write(0); out.write(rgba, row * width * 4, width * 4) }
        }.toByteArray()
        return byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", header) + chunk("IDAT", compress(pixels)) + chunk("IEND", byteArrayOf())
    }

    fun run(test: Instrumentation, activity: ProxyTestActivity) {
        fun snapshot(commands: String, scroll: Int = 0): TerminalRenderSnapshot {
            val bytes = NativeCore.nativeTerminalGraphicsProbe(b64(("\u001b[?25l" + commands).toByteArray()), scroll)
            check(bytes.isNotEmpty()) { "Native graphics probe failed" }
            return GhosttySnapshotParser.parse(bytes)
        }
        // Native input is deliberately split into 17-byte reads by the probe.
        // Verify the upgraded Unicode engine and our packed grapheme records
        // preserve combining marks, wide CJK and a joined emoji across reads.
        val unicode = "e\u0301界👩\u200d💻!"
        check(snapshot("12345678901234" + unicode).accessibleText().contains(unicode)) {
            "Native Unicode/grapheme snapshot changed during the VT upgrade"
        }
        val rgba = byteArrayOf(-1, 0, 0, -1, 0, -1, 0, -1, 0, 0, -1, -1, -1, -1, -1, 0)
        val raw = command("a=T,f=32,s=2,v=2,i=7,c=2,r=2,C=1", rgba)
        val frame = snapshot(raw)
        check(frame.images.size == 1 && frame.images.single().image.rgba.contentEquals(rgba)) { "Raw RGBA transfer failed" }
        val png = png()
        val pngCommand = command("a=T,f=100,i=7,c=2,r=2,C=1", png)
        check(snapshot(pngCommand).images.single().image.rgba.contentEquals(rgba)) { "PNG alpha decoding failed" }
        val rgb = byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, -1, -1, -1)
        check(snapshot(command("a=T,f=24,o=z,s=2,v=2,i=8,c=2,r=2,C=1", compress(rgb))).images.single().image.rgba.size == 16)
        val chunks = command("a=T,f=100,i=9,c=2,r=2,C=1,m=1", png.copyOfRange(0, 32)) + command("m=0", png.copyOfRange(32, png.size))
        check(snapshot(chunks).images.single().image.rgba.contentEquals(rgba)) { "Chunked PNG failed" }
        val largeRgba = ByteArray(512 * 512 * 4) { index ->
            when (index % 4) { 0 -> (index / 4 % 512).toByte(); 1 -> (index / (512 * 4)).toByte(); 2 -> 33; else -> -1 }
        }
        val largePng = png(512, 512, largeRgba)
        val largeChunks = buildString {
            for (offset in largePng.indices step 1024) {
                val end = minOf(offset + 1024, largePng.size)
                val control = if (offset == 0) "a=T,f=100,i=88,c=40,r=12,C=1," else ""
                append(command(control + "m=" + (if (end == largePng.size) 0 else 1), largePng.copyOfRange(offset, end)))
            }
        }
        check(snapshot(largeChunks).images.single().image.rgba.contentEquals(largeRgba)) { "One-MiB PNG/chunk export failed" }
        val virtual = command("a=T,U=1,f=32,s=2,v=2,i=7,p=1,c=2,r=2,z=-1", rgba) + "\u001b[38;2;0;0;7m" + String(Character.toChars(0x10EEEE)) + "\u0305\u0305"
        check(snapshot(virtual).images.isNotEmpty()) { "Unicode placeholder did not produce an image placement" }
        check(snapshot(raw + "\u001b[2J").images.isEmpty()) { "ED2 retained images" }
        check(snapshot(raw + "\u001b[?1049h").images.isEmpty()) { "Image leaked to alternate screen" }
        check(snapshot(raw + "\u001b[?1049h\u001b[?1049l").images.size == 1) { "Primary image not restored" }
        check(snapshot(raw + command("a=d,d=I,i=7")).images.isEmpty()) { "Explicit delete retained image" }
        check(snapshot(raw + "\u001bc").images.isEmpty()) { "Reset retained image" }
        val output = raw + "\r\n".repeat(40)
        check(snapshot(output).images.isEmpty()) { "Scrolled-off image remained visible" }
        check(snapshot(output, -40).images.isNotEmpty()) { "Scrollback lost image" }
        check(snapshot(command("a=T,f=32,s=999999,v=2,i=2", rgba)).images.isEmpty()) { "Oversized image accepted" }
        check(snapshot(command("a=T,f=100,i=2", byteArrayOf(1, 2, 3))).images.isEmpty()) { "Malformed PNG accepted" }
        check(snapshot(command("a=T,f=24,o=z,s=2,v=2,i=2", byteArrayOf(1, 2, 3))).images.isEmpty()) { "Malformed zlib accepted" }
        for (medium in listOf("f", "t", "s")) {
            check(snapshot(command("a=T,f=100,t=$medium,i=2", "/proc/self/maps".toByteArray())).images.isEmpty()) { "Local transfer enabled" }
        }
        val bomb = ByteArrayOutputStream().also { out ->
            DeflaterOutputStream(out).use { zip -> val zeros = ByteArray(8192); repeat(8193) { zip.write(zeros) } }
        }.toByteArray()
        check(snapshot(command("a=T,f=32,o=z,s=2,v=2,i=2", bomb) + raw).images.size == 1) { "Decompression limit did not recover" }
        val crop = snapshot(raw + command("a=p,i=7,p=2,x=1,y=0,w=1,h=1,c=3,r=2,z=-1,C=1"))
        check(crop.images.any { it.z == -1 && it.sourceX == 1 && it.sourceWidth == 1 && it.width == 3f }) { "Crop/scale/z placement failed" }
        // Follow the stable library's command semantics: oversized source crops
        // are clipped by Ghostty. The exported rectangle must remain in bounds.
        val clipped = snapshot(raw + command("a=p,i=7,p=2,w=4294967295,h=1,C=1"))
            .images.single { it.placementId == 2L }
        check(clipped.sourceX == 0 && clipped.sourceWidth == 2 && clipped.sourceHeight == 1) {
            "Ghostty did not clip the source crop to the image"
        }
        check(snapshot(raw + command("a=p,i=7,p=2,c=4294967295,r=4294967295,C=1") + "ALIVE")
            .accessibleText().contains("ALIVE")) { "Oversized placement prevented text rendering" }
        val parent = command("a=T,f=32,s=2,v=2,i=70,p=5,c=2,r=2,C=1", rgba)
        val child = command("a=T,f=32,s=2,v=2,i=71,p=6,P=70,Q=5,H=3,V=2,c=1,r=1,C=1", rgba)
        val relative = snapshot(parent + child).images.single { it.image.id == 71L }
        check(relative.left == 3f && relative.top == 2f && relative.width == 1f && relative.height == 1f) {
            "Ghostty 1.0 relative placement geometry was lost"
        }
        check(snapshot(parent + child + command("a=d,d=I,i=70")).images.none { it.image.id == 71L }) {
            "Deleting the parent retained its relative placement"
        }
        // Explicit frame selection uses upstream behavior. Automatic animation
        // playback is not scheduled by the Android renderer.
        val selectedFrame = ByteArray(16) { -1 }
        val animation = raw + command("a=f,f=32,s=2,v=2,i=7", selectedFrame) + command("a=a,i=7,c=2")
        check(snapshot(animation).images.single().image.rgba.contentEquals(selectedFrame)) {
            "Explicit Kitty frame selection did not update the image generation"
        }
        val manyImages = buildString {
            repeat(140) { append(command("a=T,f=32,s=1,v=1,i=${it + 1},c=1,r=1,C=1", byteArrayOf(-1, 0, 0, -1))) }
        }
        check(snapshot(manyImages).images.map { it.image.id }.distinct().size == 128) { "Export image budget changed" }
        val manyPlacements = raw + buildString {
            repeat(520) { append(command("a=p,i=7,p=${it + 1},c=1,r=1,C=1")) }
        }
        check(snapshot(manyPlacements).images.size == 512) { "Export placement budget changed" }
        val replacement = snapshot(raw + command("a=T,f=32,s=1,v=1,i=7,c=2,r=2,C=1", byteArrayOf(0, -1, 0, -1)))
        check(replacement.images.all { it.image.width == 1 && it.image.rgba.contentEquals(byteArrayOf(0, -1, 0, -1)) }) { "Image ID replacement retained pixels" }

        // Explicit pixel assertions detect stale transparent compositing and paint after clear.
        lateinit var view: NovaTerminalView
        var cw = 8; var ch = 16
        test.runOnMainSync {
            view = NovaTerminalView(activity)
            view.setTerminalCallbacks(null, null, onResizePixels = { _, _, w, h -> cw = w; ch = h })
            activity.setContentView(view)
            view.layout(0, 0, 640, 384)
            view.submitSnapshot(frame)
        }
        fun pixel(x: Int, y: Int): Int {
            var result = 0
            test.runOnMainSync {
                val bitmap = Bitmap.createBitmap(640, 384, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap)); result = bitmap.getPixel(x, y); bitmap.recycle()
            }
            return result
        }
        repeat(40) { if (pixel(2, 2) != 0xffff0000.toInt()) SystemClock.sleep(50) }
        check(pixel(2, 2) == 0xffff0000.toInt()) { "Canvas did not draw image" }
        check(pixel(cw * 2 - 2, 2) == 0xff00ff00.toInt()) { "Canvas crop/scale is wrong" }
        check(pixel(2, ch * 2 - 2) == 0xff0000ff.toInt()) { "Canvas image rows are wrong" }
        check(pixel(cw * 2 - 2, ch * 2 - 2) == frame.defaultBackgroundArgb) { "Transparent pixels did not preserve background" }
        test.runOnMainSync { view.submitSnapshot(replacement) }
        repeat(40) { if (pixel(2, 2) != 0xff00ff00.toInt()) SystemClock.sleep(50) }
        check(pixel(2, 2) == 0xff00ff00.toInt()) { "Canvas reused stale image ID" }
        test.runOnMainSync { view.submitSnapshot(frame.copy(images = emptyList())) }
        check(pixel(2, 2) == frame.defaultBackgroundArgb) { "Canvas retained cleared image" }

        // Read back the real hardware-accelerated window as well as software Canvas.
        test.runOnMainSync { view.submitSnapshot(frame) }
        var hardwareRed = false
        repeat(10) {
            if (!hardwareRed) {
                SystemClock.sleep(100)
                val copied = Bitmap.createBitmap(640, 384, Bitmap.Config.ARGB_8888)
                val latch = java.util.concurrent.CountDownLatch(1)
                val location = IntArray(2)
                test.runOnMainSync {
                    view.getLocationInWindow(location)
                    android.view.PixelCopy.request(activity.window,
                        android.graphics.Rect(location[0], location[1], location[0] + 640, location[1] + 384),
                        copied, { status ->
                            hardwareRed = status == android.view.PixelCopy.SUCCESS && copied.getPixel(2, 2) == 0xffff0000.toInt()
                            latch.countDown()
                        }, android.os.Handler(android.os.Looper.getMainLooper()))
                }
                check(latch.await(3, java.util.concurrent.TimeUnit.SECONDS)) { "PixelCopy timed out" }
                if (hardwareRed) {
                    java.io.File(activity.cacheDir, "terminal-kitty-check.png").outputStream().use {
                        copied.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                copied.recycle()
            }
        }
        check(hardwareRed) { "Hardware Canvas did not render the image" }
    }
}
