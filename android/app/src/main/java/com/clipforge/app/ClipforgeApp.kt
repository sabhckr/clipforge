package com.clipforge.app

import android.app.Application
import com.clipforge.app.core.DeviceKeyStore
import com.clipforge.app.data.Database
import com.clipforge.app.engine.JobEngine
import com.clipforge.app.engine.ProcessingService
import com.clipforge.app.export.Exporter
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ClipforgeApp : Application() {
    lateinit var engine: JobEngine
        private set
    lateinit var exporter: Exporter
        private set

    private val recovered = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        val dataDir = File(filesDir, "clipforge").apply { mkdirs() }
        engine = JobEngine(this, Database(this), DeviceKeyStore(File(dataDir, "keys")), dataDir)
        engine.serviceController = ProcessingService.Controller(this)
        exporter = Exporter(this, engine)
        ProcessingService.ensureChannel(this)
    }

    /** Requeues work interrupted by a crash or force-stop. Called once from the visible activity. */
    fun recoverOnce() {
        if (recovered.compareAndSet(false, true)) {
            Thread({ engine.recover() }, "clipforge-recover").start()
        }
    }
}
