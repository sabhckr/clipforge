package com.clipforge.app

object Golden {
    fun text(name: String): String {
        val stream = Golden::class.java.getResourceAsStream("/golden/$name")
            ?: throw IllegalStateException("missing golden resource $name")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun bytes(name: String): ByteArray {
        val stream = Golden::class.java.getResourceAsStream("/golden/$name")
            ?: throw IllegalStateException("missing golden resource $name")
        return stream.use { it.readBytes() }
    }

    /** Fixed TEST-ONLY seed shared with android/tools/make_golden.py. Protects nothing. */
    val TEST_SEED = ByteArray(32) { it.toByte() }
}
