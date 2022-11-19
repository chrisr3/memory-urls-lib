package com.r3.sgx.memory

import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.charset.StandardCharsets.UTF_8
import java.security.CodeSigner
import java.security.CodeSource
import java.security.SecureClassLoader
import java.util.Collections
import java.util.Enumeration
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

@Suppress("UsePropertyAccessSyntax")
open class MemoryClassLoader @Throws(IOException::class) constructor(
    urls: List<MemoryURL>, parent: ClassLoader?
) : SecureClassLoader(parent) {
    private companion object {
        private const val LOCAL_BLOCK_SIGNATURE = 0x04034b50
        private const val DATA_DESCRIPTOR_SIGNATURE = 0x08074b50
        private const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50
        private const val END_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50
        private const val END_ZIP64_CENTRAL_DIRECTORY_SIGNATURE = 0x06064b50
        private const val FLAGS_OFFSET = 6
        private const val COMPRESSED_SIZE_OFFSET = 18
        private const val BASE_DATA_DESCRIPTOR_SIZE = 12
        private const val DATA_DESCRIPTOR_FLAG = 0x08
        private const val EXTRA_ZIP64_TAG = 1
        private const val EOF = -1

        private val logger = LoggerFactory.getLogger(MemoryClassLoader::class.java)

        private fun ByteBuffer.skip(skipLength: Int): ByteBuffer {
            return position(position() + skipLength)
        }

        private inline fun zipRequires(predicate: Boolean, lazyMessage: () -> String) {
            if (!predicate) {
                throw ZipException(lazyMessage())
            }
        }

        init {
            registerAsParallelCapable()
        }
    }

    @Suppress("unused")
    @Throws(IOException::class)
    constructor(urls: List<MemoryURL>) : this(urls, ClassLoader.getSystemClassLoader())

    private val tablesOfContents = urls.associateWith(::getTableOfContents)
    private val urls = ArrayList(urls)

    @Throws(IOException::class)
    private fun getTableOfContents(url: MemoryURL): Map<String, Int> {
        val connection = url.value.openConnection()
        val cursor = (connection.content as ByteBuffer).duplicate().order(LITTLE_ENDIAN)

        val entries = mutableMapOf<Int, String>()

        ZipInputStream(connection.getInputStream()).use { zip ->
            // Iterate through the ZIP's data contents.
            // Not all of these entries may be "live".
            while (true) {
                val entry = zip.nextEntry ?: break
                zip.closeEntry()

                // Compute the index of the next file entry inside the ByteBuffer.
                val position = getPositionAndNext(entry, cursor)
                zipRequires(position >= 0) {
                    "Incorrect computed position for ${url.value.path}!/${entry.name}."
                }

                entries[position] = entry.name
            }
        }

        val contents = linkedMapOf<String, Int>()
        var actualSize = 0

        // We should now have reached the Central Directory records,
        // which provide the indices of the ZIP's "live" entries.
        while (cursor.hasRemaining()) {
            val signature = cursor.getInt()
            if (signature != CENTRAL_DIRECTORY_SIGNATURE) {
                actualSize += contents.size

                // This should be the "end of central directory" record.
                if (signature == END_CENTRAL_DIRECTORY_SIGNATURE) {
                    validateCentralDirectory(cursor, actualSize)
                    break
                } else if (signature == END_ZIP64_CENTRAL_DIRECTORY_SIGNATURE) {
                    validateZip64CentralDirectory(cursor, actualSize)
                    break
                }

                throw ZipException("End of Central Directory record is missing.")
            }

            // This is a JAR rather than a plain ZIP, and so
            // we will always use UTF-8 encoding regardless
            // of the Language Encoding Flag setting (bit 11).
            cursor.skip(4 * (UShort.SIZE_BYTES + UInt.SIZE_BYTES))

            val nameLength = cursor.getShort().toUShort().toInt()
            val extraLength = cursor.getShort().toUShort().toInt()
            val commentLength = cursor.getShort().toUShort().toInt()

            cursor.skip(ULong.SIZE_BYTES)

            var localOffset = cursor.getInt()

            val nameBytes = ByteArray(nameLength)
            cursor.get(nameBytes)
            val name = String(nameBytes, UTF_8)

            if (localOffset == -1) {
                val extra = cursor.slice().limit(extraLength)
                while (extra.hasRemaining()) {
                    val blockTag = extra.getShort().toUShort().toInt()
                    val blockLength = extra.getShort().toUShort().toInt()
                    if (blockTag == EXTRA_ZIP64_TAG) {
                        val offset = extra.getLong(extra.position() + 16).toULong()
                        zipRequires(offset < cursor.capacity().toUInt()) {
                            "Zip entry $name has invalid offset $offset."
                        }
                        localOffset = offset.toInt()
                        break
                    }
                    extra.skip(blockLength)
                }
            } else if (localOffset.toUInt().toLong() >= cursor.capacity()) {
                throw ZipException("Zip entry $name has invalid offset ${localOffset.toUInt()}.")
            }

            val entryName = entries[localOffset]
                ?: throw ZipException("Directory entry $name has no file data.")

            if (name != entryName) {
                throw ZipException("Directory entry $name, but file data is for $entryName.")
            } else if (contents.putIfAbsent(entryName, localOffset) != null) {
                logger.warn("Duplicate entry {} in {}.", entryName, url.value)
                ++actualSize
            }

            // Skip the remainder of this directory entry.
            cursor.skip(extraLength + commentLength)
        }

        return contents
    }

    private fun getPositionAndNext(entry: ZipEntry, cursor: ByteBuffer): Int {
        val position = cursor.position()
        if (cursor.getInt(position) != LOCAL_BLOCK_SIGNATURE) {
            return EOF
        }

        val entryDataSize = entry.compressedSize
        zipRequires(entryDataSize < cursor.capacity()) {
            "Zip entry ${entry.name} has invalid size $entryDataSize."
        }

        val flagsField = cursor.getShort(position + FLAGS_OFFSET).toUShort().toInt()
        cursor.position(position + COMPRESSED_SIZE_OFFSET)
        val compressedField = cursor.getInt()
        val uncompressedField = cursor.getInt()
        val nameLength = cursor.getShort().toUShort().toInt()
        val extraLength = cursor.getShort().toUShort().toInt()

        // Check that we're reading the correct entry.
        val nameBytes = ByteArray(nameLength)
        cursor.get(nameBytes)
        val name = String(nameBytes, UTF_8)
        zipRequires(name == entry.name) {
            "Expected ${entry.name} but found $name."
        }

        // Advance past all extra records and the data itself.
        cursor.skip(extraLength + entryDataSize.toInt())

        // Check for a data descriptor trailing the data.
        if (flagsField and DATA_DESCRIPTOR_FLAG != 0) {
            var dataDescriptorSize = BASE_DATA_DESCRIPTOR_SIZE
            val current = cursor.position()
            val signature = cursor.getInt(current)
            if (signature == DATA_DESCRIPTOR_SIGNATURE) {
                // This signature is optional.
                dataDescriptorSize += UInt.SIZE_BYTES
            }
            if (compressedField == -1 || uncompressedField == -1) {
                // This is a Zip64 file entry.
                dataDescriptorSize += (ULong.SIZE_BYTES - UInt.SIZE_BITS) * 2
            }
            cursor.position(current + dataDescriptorSize)
        }

        return position
    }

    private fun validateCentralDirectory(cursor: ByteBuffer, actualSize: Int) {
        val thisDiskNumber = cursor.getShort().toUShort().toInt()
        val startDiskNumber = cursor.getShort().toUShort().toInt()
        zipRequires(thisDiskNumber == 0 && startDiskNumber == 0) {
            "Multi-part archives are not supported."
        }

        val thisDiskTotalEntries = cursor.getShort().toUShort().toInt()
        zipRequires(thisDiskTotalEntries == actualSize) {
            "Found $actualSize ZIP entries, but expected $thisDiskTotalEntries."
        }

        val totalEntries = cursor.getShort().toUShort().toInt()
        zipRequires(totalEntries == thisDiskTotalEntries) {
            "ZIP contains $thisDiskTotalEntries entries, but expects $totalEntries entries overall."
        }
    }

    private fun validateZip64CentralDirectory(cursor: ByteBuffer, actualSize: Int) {
        cursor.skip(ULong.SIZE_BYTES + UShort.SIZE_BYTES + UShort.SIZE_BYTES)
        val thisDiskNumber = cursor.getInt().toUInt()
        val startDiskNumber = cursor.getInt().toUInt()
        zipRequires(thisDiskNumber == 0u && startDiskNumber == 0u) {
            "Multi-part archives are not supported."
        }

        val thisDiskTotalEntries = cursor.getLong().toULong()
        zipRequires(thisDiskTotalEntries == actualSize.toULong()) {
            "Found $actualSize ZIP entries, but expected $thisDiskTotalEntries."
        }

        val totalEntries = cursor.getLong().toULong()
        zipRequires(totalEntries == thisDiskTotalEntries) {
            "ZIP contains $thisDiskTotalEntries entries, but expects $totalEntries entries overall."
        }
    }

    fun getURLs(): Array<URL> {
        return urls.map(MemoryURL::value).toTypedArray()
    }

    private fun findResourceLocation(resourceName: String?): Pair<MemoryURL, Int>? {
        tablesOfContents.forEach { (host, tableOfContents) ->
            tableOfContents[resourceName]?.also { position ->
                return host to position
            }
        }
        return null
    }

    @Throws(ClassNotFoundException::class)
    final override fun findClass(name: String): Class<*>? {
        val resourceName = name.replace('.', '/') + ".class"
        findResourceLocation(resourceName)?.also { (host, position) ->
            val connection = host.value.openConnection()
            ZipInputStream(connection.getInputStream()).use { zip ->
                (connection.content as ByteBuffer).position(position)
                zip.nextEntry?.also { entry ->
                    if (resourceName == entry.name) {
                        val byteCode = ByteBuffer.wrap(zip.readBytes())
                        return defineClass(name, byteCode, host)
                    }
                }
            }
        }
        throw ClassNotFoundException(name)
    }

    private fun defineClass(name: String, byteCode: ByteBuffer, url: MemoryURL): Class<*>? {
        val idx = name.lastIndexOf('.')
        if (idx > 0) {
            val packageName = name.substring(0, idx)
            @Suppress("deprecation")
            if (getPackage(packageName) == null) {
                definePackage(packageName, null, null, null, null, null, null, null)
            }
        }
        return defineClass(name, byteCode, CodeSource(url.value, arrayOf<CodeSigner>()))
    }

    private fun createResource(hostURL: URL, resourceName: String?): URL {
        return URL(hostURL.protocol, null, -1, hostURL.path + "!/" + resourceName)
    }

    final override fun findResource(name: String?): URL? {
        return findResourceLocation(name)?.let { (host, _) ->
            createResource(host.value, name)
        }
    }

    final override fun findResources(name: String?): Enumeration<URL>? {
        return tablesOfContents.mapNotNull { (host, tableOfContents) ->
            host.takeIf { name in tableOfContents }?.let { createResource(it.value, name) }
        }.let(Collections::enumeration)
    }
}
