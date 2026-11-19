package com.clipforge.app.core

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import java.io.File
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

/**
 * Ownership & provenance, compatible with the desktop engine (backend/clipforge/provenance.py):
 * the same manifest layout, the same canonical JSON and the same Ed25519 signature block,
 * so either side verifies manifests signed by the other.
 *
 * What this proves: a manifest was signed by the holder of a specific private key, and files
 * match the hashes recorded in it. What it does NOT do: prevent copying, or establish
 * copyright ownership.
 */
object Provenance {
    const val APP_NAME = "CLIPFORGE"
    const val APP_VERSION = "1.0.0"
    const val MANIFEST_VERSION = "1.0"
    const val CANONICALIZATION = "json:sort_keys,separators=(',',':'),utf-8"
    const val SIGNATURE_ALGORITHM = "Ed25519"

    const val DISCLAIMER = "This report verifies the integrity of files against a signed CLIPFORGE " +
        "manifest and identifies which key signed it. It does not prevent copying " +
        "and does not by itself establish copyright ownership."

    /** DER prefix of an Ed25519 SubjectPublicKeyInfo (RFC 8410), followed by the 32 raw bytes. */
    private val SPKI_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )

    private val ISO: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun now(): Double = System.currentTimeMillis() / 1000.0

    fun iso(ts: Double): String = ISO.format(Instant.ofEpochSecond(Math.floor(ts).toLong()))

    fun newId(): String = UUID.randomUUID().toString()

    // ------------------------------------------------------------------ hashing
    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256File(file: File): String = file.inputStream().use { sha256Stream(it).first }

    /** Returns (hex digest, byte count). */
    fun sha256Stream(input: InputStream, onBytes: ((Long) -> Unit)? = null): Pair<String, Long> {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            total += n
            onBytes?.invoke(total)
        }
        return hex(md.digest()) to total
    }

    // --------------------------------------------------------------------- keys
    fun publicPem(raw: ByteArray): String {
        require(raw.size == 32) { "Ed25519 public keys are 32 bytes" }
        val der = SPKI_PREFIX + raw
        val b64 = Base64.getEncoder().encodeToString(der)
        val body = b64.chunked(64).joinToString("\n")
        return "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----\n"
    }

    /** Parses an Ed25519 "BEGIN PUBLIC KEY" PEM; returns the 32 raw key bytes. */
    fun parsePublicPem(pem: String): ByteArray {
        val begin = "-----BEGIN PUBLIC KEY-----"
        val end = "-----END PUBLIC KEY-----"
        val i = pem.indexOf(begin)
        val j = pem.indexOf(end)
        if (i < 0 || j < 0 || j < i) throw IllegalArgumentException("Not a PEM public key")
        val body = pem.substring(i + begin.length, j).filter { !it.isWhitespace() }
        val der = try {
            Base64.getDecoder().decode(body)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Public key PEM is not valid base64")
        }
        if (der.size != 44 || !der.copyOfRange(0, 12).contentEquals(SPKI_PREFIX)) {
            throw IllegalArgumentException("Public key is not Ed25519")
        }
        return der.copyOfRange(12, 44)
    }

    fun keyId(raw: ByteArray): String = sha256(raw).substring(0, 16)

    fun fingerprint(raw: ByteArray): String = sha256(raw).substring(0, 32).chunked(4).joinToString(":")

    /** An Ed25519 key pair held in memory only while signing. */
    class Signer(seed: ByteArray) {
        val publicKey: ByteArray
        private val signer: Ed25519Sign

        init {
            require(seed.size == 32) { "Ed25519 seeds are 32 bytes" }
            val pair = Ed25519Sign.KeyPair.newKeyPairFromSeed(seed)
            publicKey = pair.publicKey
            signer = Ed25519Sign(pair.privateKey)
        }

        fun sign(data: ByteArray): ByteArray = signer.sign(data)
    }

    fun newSeed(): ByteArray = Ed25519Sign.KeyPair.newKeyPair().privateKey

    // ---------------------------------------------------------------- manifests
    fun signManifest(manifest: Map<String, Any?>, signer: Signer): Map<String, Any?> {
        val sig = signer.sign(Json.canonicalBytes(manifest))
        return linkedMapOf(
            "manifest" to manifest,
            "signature" to linkedMapOf(
                "algorithm" to SIGNATURE_ALGORITHM,
                "canonicalization" to CANONICALIZATION,
                "key_id" to keyId(signer.publicKey),
                "public_key_pem" to publicPem(signer.publicKey),
                "value" to Base64.getEncoder().encodeToString(sig),
            ),
        )
    }

    data class SignatureResult(
        val valid: Boolean,
        val algorithm: String?,
        val keyId: String?,
        val trustedKey: Boolean,
        val error: String?,
    ) {
        fun toJson(): Map<String, Any?> = linkedMapOf(
            "valid" to valid, "algorithm" to algorithm, "key_id" to keyId,
            "trusted_key" to trustedKey, "error" to error,
        )
    }

    /** Checks a signed manifest document. Never throws for bad input. */
    fun verifySignedManifest(doc: Any?, trusted: ByteArray? = null): SignatureResult {
        var algorithm: String? = null
        var kid: String? = null
        var trustedOk = false
        var error: String? = null
        try {
            val d = Json.obj(doc)
            if (d == null || !d.containsKey("manifest") || !d.containsKey("signature")) {
                throw IllegalArgumentException("Not a signed CLIPFORGE manifest (missing 'manifest' or 'signature')")
            }
            val sig = Json.obj(d["signature"]) ?: throw IllegalArgumentException("Malformed signature block")
            algorithm = sig["algorithm"] as? String
            if (algorithm != SIGNATURE_ALGORITHM) {
                throw IllegalArgumentException("Unsupported signature algorithm: ${sig["algorithm"]}")
            }
            val embedded = parsePublicPem(sig["public_key_pem"] as? String ?: "")
            kid = keyId(embedded)
            val claimed = sig["key_id"] as? String
            if (!claimed.isNullOrEmpty() && claimed != kid) {
                throw IllegalArgumentException("key_id does not match the embedded public key")
            }
            var verifierKey = embedded
            if (trusted != null) {
                verifierKey = trusted
                trustedOk = trusted.contentEquals(embedded)
                if (!trustedOk) error = "Manifest was signed by a different key than the trusted key"
            }
            val value = sig["value"] as? String ?: ""
            val signature = try {
                Base64.getDecoder().decode(value)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Signature value is not valid base64")
            }
            try {
                Ed25519Verify(verifierKey).verify(signature, Json.canonicalBytes(d["manifest"]))
            } catch (e: GeneralSecurityException) {
                return SignatureResult(false, algorithm, kid, trustedOk, error
                    ?: "Signature does not match manifest contents (manifest modified or wrong key)")
            }
            return SignatureResult(true, algorithm, kid, trustedOk, error)
        } catch (e: Exception) {
            return SignatureResult(false, algorithm, kid, trustedOk, error ?: (e.message ?: e.toString()))
        }
    }

    data class FileResult(
        val name: String,
        val sha256: String,
        val status: String, // match | modified | unknown
        val role: String?,  // output | source | null
        val assetId: String?,
        val expectedSha256: String?,
    ) {
        fun toJson(): Map<String, Any?> = linkedMapOf(
            "name" to name, "sha256" to sha256, "status" to status, "role" to role,
            "asset_id" to assetId, "expected_sha256" to expectedSha256,
        )
    }

    /** files: (name, sha256). Matches each to a manifest output or the source. */
    fun matchFiles(manifest: Map<String, Any?>, files: List<Pair<String, String>>): List<FileResult> {
        val byHash = HashMap<String?, Map<String, Any?>>()
        val byPath = HashMap<String, Map<String, Any?>>()
        for (o in Json.arr(manifest["outputs"]) ?: emptyList()) {
            val out = Json.obj(o) ?: continue
            byHash[out["sha256"] as? String] = out
            byPath[((out["path"] as? String) ?: "").substringAfterLast('/')] = out
        }
        val src = Json.obj(manifest["source"]) ?: emptyMap()
        return files.map { (name, digest) ->
            val base = name.replace('\\', '/').substringAfterLast('/')
            val hit = byHash[digest]
            when {
                hit != null -> FileResult(name, digest, "match", "output", hit["asset_id"] as? String, hit["sha256"] as? String)
                src["sha256"] == digest -> FileResult(name, digest, "match", "source", src["asset_id"] as? String, digest)
                byPath.containsKey(base) -> {
                    val out = byPath.getValue(base)
                    FileResult(name, digest, "modified", "output", out["asset_id"] as? String, out["sha256"] as? String)
                }
                else -> FileResult(name, digest, "unknown", null, null, null)
            }
        }
    }

    fun buildReport(
        signedDoc: Any?,
        signature: SignatureResult,
        files: List<FileResult>,
        missing: List<Map<String, Any?>> = emptyList(),
    ): Map<String, Any?> {
        val manifest = Json.obj(Json.obj(signedDoc)?.get("manifest"))
        val ok = signature.valid && files.all { it.status == "match" } && missing.isEmpty()
        return linkedMapOf(
            "report_version" to "1.0",
            "generator" to linkedMapOf("name" to APP_NAME, "version" to APP_VERSION),
            "verified_at" to iso(now()),
            "project_id" to manifest?.get("project_id"),
            "signature" to signature.toJson(),
            "files" to files.map { it.toJson() },
            "missing" to missing,
            "summary" to linkedMapOf(
                "ok" to ok,
                "signature_valid" to signature.valid,
                "trusted_key" to signature.trustedKey,
                "files_checked" to files.size,
                "files_matched" to files.count { it.status == "match" },
                "files_modified" to files.count { it.status == "modified" },
                "files_unknown" to files.count { it.status == "unknown" },
                "files_missing" to missing.size,
            ),
            "disclaimer" to DISCLAIMER,
        )
    }
}
