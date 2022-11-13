package com.r3.sgx.memory

import org.assertj.core.api.Assertions.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry

/**
 * Creates a dummy jar containing the following:
 * - META-INF/MANIFEST.MF
 * - A compressed class file
 * - A directory entry
 *
 * The compression level is set to NO_COMPRESSION
 * in order to force the Gradle task to compress
 * the entries properly.
 */
class DummyJar(
    private val projectDir: Path,
    private val testClass: Class<*>,
    private val name: String
) {
    enum class Compression(val level: Int) {
        DEFAULT(Deflater.DEFAULT_COMPRESSION),
        NONE(Deflater.NO_COMPRESSION),
        BEST(Deflater.BEST_COMPRESSION)
    }

    private companion object {
        private fun uncompressed(name: String, data: ByteArray) = ZipEntry(name).apply {
            method = ZipEntry.STORED
            compressedSize = data.size.toLong()
            size = data.size.toLong()
            crc = CRC32().let { crc ->
                crc.update(data, 0, data.size)
                crc.value
            }
        }

        private fun compressed(name: String) = ZipEntry(name).apply { method = ZipEntry.DEFLATED }

        private fun directoryOf(type: Class<*>)
            = directory(type.`package`.name.toPathFormat + '/')

        private fun directory(name: String) = ZipEntry(name).apply {
            method = ZipEntry.STORED
            compressedSize = 0
            size = 0
            crc = 0
        }
    }

    private var _compression = Compression.NONE
    val compression: Compression get() = _compression

    fun setCompression(newValue: Compression): DummyJar {
        _compression = newValue
        return this
    }

    private lateinit var _path: Path
    val path: Path get() = _path

    fun build(): DummyJar {
        val manifest = Manifest().apply {
            mainAttributes.also { main ->
                main[Attributes.Name.MANIFEST_VERSION] = "1.0"
            }
        }
        _path = projectDir.pathOf("$name.jar")
        JarOutputStream(Files.newOutputStream(_path), manifest).use { jar ->
            jar.setComment(testClass.name)
            jar.setLevel(compression.level)

            // One directory entry (stored)
            jar.putNextEntry(directoryOf(testClass))

            // One compressed class file
            jar.putNextEntry(compressed(testClass.resourceName))
            testClass.bytecode?.apply(jar::write)
        }
        assertThat(_path).isRegularFile()
        return this
    }
}
