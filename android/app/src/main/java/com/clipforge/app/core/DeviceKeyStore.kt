package com.clipforge.app.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The device's Ed25519 signing identity.
 *
 * The 32-byte Ed25519 seed is stored in app-private storage, encrypted with an AES-256-GCM key
 * that lives in the Android Keystore (hardware-backed on most devices) and never leaves it.
 * The seed is decrypted only in memory while signing and is never exported or displayed.
 */
class DeviceKeyStore(private val dir: File) {

    data class Info(
        val keyId: String,
        val publicKeyPem: String,
        val fingerprint: String,
        val createdAt: String,
    )

    private val lock = Any()
    private var cachedPublic: ByteArray? = null

    private val sealedFile get() = File(dir, "ed25519_seed.sealed")
    private val publicFile get() = File(dir, "ed25519_public.pem")

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun seal(seed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(seed)
        return byteArrayOf(iv.size.toByte()) + iv + ct
    }

    private fun unseal(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt()
        require(ivLen in 12..16 && blob.size > 1 + ivLen) { "Corrupt sealed key file" }
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
    }

    /** Loads the seed, creating a new identity on first use. */
    private fun loadSeed(): ByteArray {
        synchronized(lock) {
            return loadSeedLocked()
        }
    }

    private fun loadSeedLocked(): ByteArray {
        dir.mkdirs()
        if (sealedFile.exists()) return unseal(sealedFile.readBytes())
        val seed = Provenance.newSeed()
        val tmp = File(dir, "ed25519_seed.sealed.tmp")
        tmp.writeBytes(seal(seed))
        if (!tmp.renameTo(sealedFile)) throw IllegalStateException("Could not store the signing key")
        val pub = Provenance.Signer(seed).publicKey
        publicFile.writeText(Provenance.publicPem(pub))
        return seed
    }

    fun <T> withSigner(block: (Provenance.Signer) -> T): T {
        val seed = loadSeed()
        try {
            return block(Provenance.Signer(seed))
        } finally {
            seed.fill(0)
        }
    }

    fun publicKey(): ByteArray {
        synchronized(lock) {
            cachedPublic?.let { return it }
        }
        // Creating the identity (if needed) takes the lock itself.
        val pub = if (publicFile.exists() && sealedFile.exists()) {
            Provenance.parsePublicPem(publicFile.readText())
        } else {
            withSigner { it.publicKey }
        }
        synchronized(lock) { cachedPublic = pub }
        return pub
    }

    fun info(): Info {
        val pub = publicKey()
        return Info(
            keyId = Provenance.keyId(pub),
            publicKeyPem = Provenance.publicPem(pub),
            fingerprint = Provenance.fingerprint(pub),
            createdAt = Provenance.iso(sealedFile.lastModified() / 1000.0),
        )
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "clipforge_seed_wrap_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
