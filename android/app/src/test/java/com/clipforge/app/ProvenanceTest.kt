package com.clipforge.app

import com.clipforge.app.core.Json
import com.clipforge.app.core.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProvenanceTest {

    private val signer = Provenance.Signer(Golden.TEST_SEED)

    @Test
    fun verifiesManifestSignedByDesktopEngine() {
        val doc = Json.parse(Golden.text("python_signed.json"))
        val r = Provenance.verifySignedManifest(doc)
        assertTrue(r.error ?: "", r.valid)
        val trusted = Provenance.verifySignedManifest(doc, trusted = signer.publicKey)
        assertTrue(trusted.valid)
        assertTrue(trusted.trustedKey)
    }

    @Test
    fun producesByteIdenticalSignatureToDesktopEngine() {
        // Ed25519 is deterministic: same key + same canonical bytes => same signature.
        val doc = Json.obj(Json.parse(Golden.text("python_signed.json")))!!
        val manifest = Json.obj(doc["manifest"])!!
        val ours = Provenance.signManifest(manifest, signer)
        assertEquals(Json.canonical(doc["signature"]), Json.canonical(ours["signature"]))
    }

    @Test
    fun detectsTampering() {
        val doc = Json.obj(Json.parse(Golden.text("python_signed.json")))!!
        val manifest = LinkedHashMap(Json.obj(doc["manifest"])!!)
        manifest["project_name"] = "changed"
        val tampered = linkedMapOf("manifest" to manifest, "signature" to doc["signature"])
        val r = Provenance.verifySignedManifest(tampered)
        assertFalse(r.valid)
        assertTrue(r.error!!.contains("does not match"))
    }

    @Test
    fun rejectsWrongTrustedKey() {
        val other = Provenance.Signer(ByteArray(32) { 7 })
        val doc = Json.parse(Golden.text("python_signed.json"))
        val r = Provenance.verifySignedManifest(doc, trusted = other.publicKey)
        assertFalse(r.valid)
        assertFalse(r.trustedKey)
        assertEquals("Manifest was signed by a different key than the trusted key", r.error)
    }

    @Test
    fun rejectsGarbageWithoutThrowing() {
        for (bad in listOf(null, "x", emptyMap<String, Any?>(), mapOf("manifest" to 1, "signature" to "no"),
            mapOf("manifest" to emptyMap<String, Any?>(), "signature" to mapOf("algorithm" to "RSA")))) {
            val r = Provenance.verifySignedManifest(bad)
            assertFalse(r.valid)
            assertTrue(r.error!!.isNotEmpty())
        }
    }

    @Test
    fun keyFormatsMatchDesktop() {
        val sig = Json.obj(Json.obj(Json.parse(Golden.text("python_signed.json")))!!["signature"])!!
        assertEquals(sig["public_key_pem"], Provenance.publicPem(signer.publicKey))
        assertEquals(sig["key_id"], Provenance.keyId(signer.publicKey))
        assertEquals(16, Provenance.keyId(signer.publicKey).length)
        assertEquals(39, Provenance.fingerprint(signer.publicKey).length)
        assertTrue(Provenance.parsePublicPem(Provenance.publicPem(signer.publicKey)).contentEquals(signer.publicKey))
    }

    @Test
    fun matchFilesAndReport() {
        val manifest = linkedMapOf<String, Any?>(
            "project_id" to "p",
            "source" to linkedMapOf("asset_id" to "s", "sha256" to "00".repeat(32)),
            "outputs" to listOf(linkedMapOf("asset_id" to "c1", "path" to "clips/clip_0001.mp4", "sha256" to "11".repeat(32))),
        )
        val files = listOf(
            "clips/clip_0001.mp4" to "11".repeat(32),
            "source.mp4" to "00".repeat(32),
            "x/clip_0001.mp4" to "22".repeat(32),
            "other.bin" to "33".repeat(32),
        )
        val res = Provenance.matchFiles(manifest, files)
        assertEquals(listOf("match", "match", "modified", "unknown"), res.map { it.status })
        assertEquals(listOf("output", "source", "output", null), res.map { it.role })
        val signed = Provenance.signManifest(manifest, signer)
        val report = Provenance.buildReport(signed, Provenance.verifySignedManifest(signed), res)
        val summary = Json.obj(report["summary"])!!
        assertEquals(false, summary["ok"])
        assertEquals(2, summary["files_matched"])
    }

    /**
     * Writes an Android-signed manifest for CI to verify with the desktop Python verifier
     * (android/tools/verify_cross_check.py).
     */
    @Test
    fun writeCrossCheckManifest() {
        val manifest = linkedMapOf<String, Any?>(
            "manifest_version" to "1.0",
            "generator" to linkedMapOf("name" to "CLIPFORGE", "version" to "1.0.0", "engine" to "unit test", "ai_used" to false),
            "project_name" to "Cross-check — Ünïcödé ✓ 🎬",
            "numbers" to listOf(0.1, 1e-7, 1e16, 29.97, 123456789.123, -0.0, 42, 1.0 / 3),
            "nested" to linkedMapOf("b" to listOf(true, false, null), "a" to "quote \" \\ \n \t \u0001"),
        )
        val signed = Provenance.signManifest(manifest, signer)
        val dir = File("build/cross-check").apply { mkdirs() }
        File(dir, "kotlin_signed.json").writeText(Json.pretty(signed), Charsets.UTF_8)
        assertTrue(Provenance.verifySignedManifest(Json.parse(Json.pretty(signed))).valid)
    }
}
