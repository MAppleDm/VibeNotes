package io.github.mappledm.vibenotes.core.update

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class UpdateClientTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.shutdown()

    private val apk = ByteArray(200_000) { (it % 251).toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }

    private fun client() = UpdateClient(server.url("/version.json").toString())

    private fun release(sha: String = apkSha) =
        ReleaseInfo(versionCode = 12, versionName = "0.1.12", apkUrl = server.url("/VibeNotes.apk").toString(), sha256 = sha, size = apk.size.toLong())

    @Test
    fun readsTheManifestAndIgnoresUnknownFields() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"versionCode":12,"versionName":"0.1.12","apkUrl":"https://x/VibeNotes.apk","sha256":"ab","size":5,"notes":"- Обновления","extra":1}""",
            ),
        )
        val info = client().latest()
        assertEquals(12L, info.versionCode)
        assertEquals("- Обновления", info.notes)
        assertEquals("no-cache", server.takeRequest().getHeader("Cache-Control"))
    }

    @Test
    fun missingReleaseIsExplained() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            client().latest()
            fail()
        } catch (e: UpdateException) {
            assertEquals("На GitHub пока нет опубликованной сборки", e.message)
        }
    }

    @Test
    fun downloadIsVerifiedAndReportsProgress() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val target = tmp.root.resolve("update.apk")
        val progress = mutableListOf<Float?>()
        client().download(release(), target) { progress += it }
        assertTrue(target.readBytes().contentEquals(apk))
        assertEquals(1f, progress.last())
        assertFalse(tmp.root.resolve("update.apk.part").exists())
    }

    @Test
    fun mismatchedFileIsDeletedAndNeverInstalled() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val target = tmp.root.resolve("update.apk")
        try {
            client().download(release(sha = "0".repeat(64)), target)
            fail()
        } catch (e: UpdateException) {
            assertTrue(e.message!!.startsWith("Файл не совпал"))
        }
        assertFalse(target.exists())
        assertFalse(tmp.root.resolve("update.apk.part").exists())
    }
}
