package io.github.nicup14.debdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Reading the image out of the offline installer, and refusing anything else
class ImageSourceTest {

    // A stand-in image: big enough to cross buffer boundaries, cheap to build
    private val image = ByteArray(700_000) { (it * 31 + it / 977).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) }
    private val moduleProp = "module.prop" to "id=debdroid\nname=DebDroid\nversion=v1.2.6\n".toByteArray()

    private fun read(file: ByteArray): ImageSource.Copied {
        val out = ByteArrayOutputStream()
        val copied = ImageSource.copy(ImageSource.open(ByteArrayInputStream(file)), out)
        if (copied.matches(sha, image.size.toLong())) assertTrue(out.toByteArray().contentEquals(image))
        return copied
    }

    private fun refused(file: ByteArray, expect: String) {
        try {
            read(file)
            fail("accepted a file that should be refused")
        } catch (e: IOException) {
            assertTrue("message was: ${e.message}", e.message!!.contains(expect))
        }
    }

    private fun gzip(bytes: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(bytes) } }.toByteArray()

    private fun zip(vararg entries: Pair<String, ByteArray>) = ByteArrayOutputStream().also { o ->
        ZipOutputStream(o).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
    }.toByteArray()

    // Splits the image the way the repository's img/ parts are split
    private fun parts(count: Int): List<Pair<String, ByteArray>> {
        val size = (image.size + count - 1) / count
        return (0 until count).map { i ->
            "img/debian.img.part-a${'a' + i}" to image.copyOfRange(i * size, minOf(image.size, (i + 1) * size))
        }
    }

    // The offline module as module/build.sh lays it out: sorted, so img/ comes
    // before module.prop
    private fun offlineModule(img: List<Pair<String, ByteArray>>, prop: Pair<String, ByteArray>? = moduleProp) =
        zip(*(listOf("LICENSE" to "MIT".toByteArray(), "debdroid/debdroid.sh" to "#!/system/bin/sh".toByteArray()) +
            img + listOfNotNull(prop) + listOf("rootfs/manifest" to "# manifest".toByteArray())).toTypedArray())

    @Test fun theOfflineModuleWithPiecesIsReassembled() =
        assertTrue(read(offlineModule(parts(5))).matches(sha, image.size.toLong()))

    @Test fun theOfflineModuleWithAWholeImageIsRead() =
        assertTrue(read(offlineModule(listOf("img/debian.img" to image))).matches(sha, image.size.toLong()))

    @Test fun aRawImageIsRefused() = refused(image, "not the offline installer")

    @Test fun aCompressedImageIsRefused() = refused(gzip(image), "not the offline installer")

    @Test fun aZipThatIsNotDebDroidsIsRefused() =
        refused(offlineModule(parts(3), "module.prop" to "id=something-else\n".toByteArray()), "not the DebDroid offline installer")

    @Test fun aZipWithNoModulePropIsRefused() =
        refused(offlineModule(parts(3), null), "not the DebDroid offline installer")

    @Test fun theSlimModuleSaysItHasNoImage() =
        refused(offlineModule(emptyList()), "without the image")

    @Test fun piecesOutOfOrderAreRefusedWithTheReason() =
        refused(offlineModule(parts(3).let { listOf(it[1], it[0], it[2]) }), "out of order")

    // NOTE: A mismatch is reported, never refused: the screen warns, shows both
    //       checksums and lets the user decide (the maintainer's call).
    @Test fun aDifferentImageIsCopiedAndReportedAsDifferent() {
        val other = image.copyOf(image.size + 4096)
        val copied = read(offlineModule(listOf("img/debian.img" to other)))
        assertEquals(other.size.toLong(), copied.size)
        assertFalse(copied.matches(sha, image.size.toLong()))
    }

    @Test fun cancellingStops() {
        try {
            ImageSource.copy(ByteArrayInputStream(image), ByteArrayOutputStream(), cancelled = { true })
            fail("cancelling did not stop the copy")
        } catch (e: IOException) {
            assertEquals("Cancelled", e.message)
        }
    }
}
