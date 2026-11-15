package io.github.nicup14.debdroid.core

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

// Reads the debian image out of the offline installer
//
// For a device with no usable network, the image arrives in
// DebDroid-module-offline.zip, the release asset that bundles it. That zip is
// the only file accepted: it is what the project publishes for this case, and
// accepting loose .img or .img.gz files as well would invite whatever image
// happened to be lying around (the maintainer's call).
//
// The zip carries the image under img/, whole or as debian.img.part-* pieces,
// and is recognised as DebDroid's by its module.prop (id=debdroid). The form
// is told from the file's first bytes, never from its name, as a file picker
// often hands over a name that has lost its extension.
object ImageSource {

    // The file the user is told to choose
    const val OFFLINE_ZIP = "DebDroid-module-offline.zip"

    // A file that is not the offline installer at all
    class NotOfflineInstaller(message: String) : IOException(message)

    fun isZip(head: ByteArray) = head.size >= 4 && head[0] == 'P'.code.toByte() &&
        head[1] == 'K'.code.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte()

    // The image's bytes inside the offline installer
    fun open(input: InputStream): InputStream {
        val buffered = BufferedInputStream(input, 64 * 1024)
        buffered.mark(8)
        val head = ByteArray(4)
        var read = 0
        while (read < head.size) {
            val n = buffered.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
        buffered.reset()
        if (!isZip(head.copyOf(read))) {
            throw NotOfflineInstaller("This is not the offline installer. Choose $OFFLINE_ZIP from the releases page.")
        }
        return OfflineImage(ZipInputStream(buffered))
    }

    // What was copied: its size and sha256
    data class Copied(val size: Long, val sha256: String) {
        // Whether it is exactly the image this version ships
        fun matches(expectedSha: String, expectedSize: Long) = size == expectedSize && sha256 == expectedSha
    }

    // Copies the image to out, hashing as it goes
    // NOTE: No size limit and no refusal here. A mismatch is the caller's to
    //       warn about and the user's to decide on.
    fun copy(input: InputStream, out: OutputStream, cancelled: () -> Boolean = { false }, progress: (Long) -> Unit = {}): Copied {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        var total = 0L
        while (true) {
            if (cancelled()) throw IOException("Cancelled")
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            digest.update(buffer, 0, n)
            out.write(buffer, 0, n)
            progress(total)
        }
        return Copied(total, digest.digest().joinToString("") { "%02x".format(it) })
    }

    // The image inside the zip, whole or reassembled from its pieces
    // NOTE: The pieces are joined in the order the zip holds them, and that
    //       order is checked rather than trusted. module/build.sh sorts its
    //       entries, but a zip rebuilt by hand might not, and pieces joined out
    //       of order would only show up as a checksum mismatch, with a message
    //       that says nothing about why.
    // NOTE: module.prop sorts after img/, so it is looked for once the image
    //       has been read, and the stream only ends once the zip is known to
    //       be DebDroid's. A failure there throws before the caller moves the
    //       copied image into place.
    private class OfflineImage(private val zip: ZipInputStream) : InputStream() {
        private var current: InputStream? = null
        private var lastPart: String? = null
        private var whole = false
        private var finished = false
        private var foundAny = false
        private var moduleId: String? = null

        private fun readModuleProp() {
            val text = zip.readBytes().toString(Charsets.UTF_8)
            moduleId = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("id=") }?.removePrefix("id=")
        }

        // Moves to the next entry that is part of the image, noting module.prop on the way
        private fun advance(): Boolean {
            if (finished) return false
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.removePrefix("./")
                val base = name.substringAfterLast('/')
                when {
                    name == "module.prop" -> readModuleProp()
                    !foundAny && name.endsWith("img/debian.img") -> {
                        whole = true
                        foundAny = true
                        current = zip
                        return true
                    }
                    !whole && base.startsWith("debian.img.part-") -> {
                        val previous = lastPart
                        if (previous != null && base <= previous) {
                            throw IOException("The image pieces in this zip are out of order ($previous, then $base).")
                        }
                        lastPart = base
                        foundAny = true
                        current = zip
                        return true
                    }
                }
            }
            finish()
            return false
        }

        // Reads the rest of the zip and checks it is DebDroid's offline installer
        private fun finish() {
            if (finished) return
            finished = true
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name.removePrefix("./") == "module.prop") readModuleProp()
            }
            if (moduleId != "debdroid") {
                throw NotOfflineInstaller("This zip is not the DebDroid offline installer. Choose $OFFLINE_ZIP from the releases page.")
            }
            if (!foundAny) {
                throw NotOfflineInstaller("This is the module without the image. Choose $OFFLINE_ZIP, which carries it.")
            }
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (true) {
                val stream = current ?: if (advance()) current!! else return -1
                val n = stream.read(b, off, len)
                if (n >= 0) return n
                current = null
                // A whole image ends the search; pieces carry on to the next
                if (whole) {
                    finish()
                    return -1
                }
            }
        }

        override fun close() = zip.close()
    }
}
