package com.clipforge.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.test.platform.app.InstrumentationRegistry
import com.clipforge.app.core.DeviceKeyStore
import com.clipforge.app.core.ProcessingSettings
import com.clipforge.app.data.Database
import com.clipforge.app.data.JobRow
import com.clipforge.app.engine.JobEngine
import java.io.File

object TestSupport {
    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext: Context get() = InstrumentationRegistry.getInstrumentation().context

    /** Copies a bundled test video into a fresh scratch directory. */
    fun asset(name: String): File {
        val dir = File(context.cacheDir, "test-assets").apply { mkdirs() }
        val out = File(dir, name)
        testContext.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    /** An isolated engine (own data dir, in-memory DB, no foreground service). */
    fun engine(): JobEngine {
        val dir = File(context.cacheDir, "engine-" + System.nanoTime()).apply { mkdirs() }
        return JobEngine(context, Database(context, null), DeviceKeyStore(File(dir, "keys")), dir)
    }

    /** Small, fast settings for the 26 s scenes.mp4 asset (cuts at 8 s and 17 s). */
    val FAST = ProcessingSettings(
        targetDuration = 10.0, sceneWindow = 3.0, minDuration = 5.0, remainderMin = 3.0,
        resolution = "720p", aspectRatio = "9:16", quality = "compact",
    )

    fun waitForEnd(engine: JobEngine, id: String, timeoutMs: Long = 10 * 60_000L): JobRow {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val j = engine.db.getJob(id) ?: throw AssertionError("job vanished")
            if (!j.isActive) return j
            Thread.sleep(250)
        }
        throw AssertionError("job $id did not finish in time: ${engine.db.getJob(id)}")
    }

    fun waitFor(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(100)
        }
    }

    fun frameAt(file: File, sec: Double): Bitmap {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.absolutePath)
            return mmr.getFrameAtTime((sec * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw AssertionError("no frame in $file")
        } finally {
            mmr.release()
        }
    }

    fun isRed(c: Int) = (c shr 16 and 0xff) > 150 && (c shr 8 and 0xff) < 90 && (c and 0xff) < 90
    fun isBlue(c: Int) = (c and 0xff) > 150 && (c shr 16 and 0xff) < 90 && (c shr 8 and 0xff) < 90
    fun isBlack(c: Int) = (c shr 16 and 0xff) < 40 && (c shr 8 and 0xff) < 40 && (c and 0xff) < 40
    fun hex(c: Int) = String.format("#%06x", c and 0xffffff)
}
