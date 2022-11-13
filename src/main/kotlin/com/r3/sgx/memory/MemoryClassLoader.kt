package com.r3.sgx.memory

import java.net.URL
import java.nio.ByteBuffer
import java.security.CodeSigner
import java.security.CodeSource
import java.security.SecureClassLoader
import java.util.Collections
import java.util.Enumeration
import java.util.zip.ZipEntry
import java.util.zip.ZipEntry.STORED
import java.util.zip.ZipInputStream

open class MemoryClassLoader(urls: List<MemoryURL>, parent: ClassLoader?) : SecureClassLoader(parent) {
    @Suppress("unused")
    constructor(urls: List<MemoryURL>) : this(urls, ClassLoader.getSystemClassLoader())

    private val tablesOfContents = urls.associateWith(::getTableOfContents)
    private val urls = ArrayList(urls)

    private fun getTableOfContents(url: MemoryURL): Set<String> {
        return ZipInputStream(url.value.openStream()).use { zip ->
            val contents = linkedSetOf<String>()
            while (true) {
                val entry = zip.nextEntry ?: break
                contents += entry.name
            }
            contents
        }
    }

    fun getURLs(): Array<URL> {
        return urls.map(MemoryURL::value).toTypedArray()
    }

    private fun findHost(resourceName: String?): MemoryURL? {
        tablesOfContents.forEach { (host, tableOfContents) ->
            if (resourceName in tableOfContents) {
                return host
            }
        }
        return null
    }

    @Throws(ClassNotFoundException::class)
    final override fun findClass(name: String): Class<*>? {
        val resourceName = name.replace('.', '/') + ".class"
        findHost(resourceName)?.also { host ->
            val connection = host.value.openConnection()
            ZipInputStream(connection.getInputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (resourceName == entry.name) {
                        val byteCode = if (entry.isUncompressed && connection.content is ByteBuffer) {
                            (connection.content as ByteBuffer).slice().apply {
                                // Zero-copy optimisation for uncompressed data.
                                limit(entry.size.toInt())
                            }
                        } else {
                            ByteBuffer.wrap(zip.readBytes())
                        }
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
        return findHost(name)?.let { host ->
            createResource(host.value, name)
        }
    }

    final override fun findResources(name: String?): Enumeration<URL>? {
        return tablesOfContents.mapNotNull { (host, tableOfContents) ->
            host.takeIf { name in tableOfContents }?.let { createResource(it.value, name) }
        }.let(Collections::enumeration)
    }

    private val ZipEntry.isUncompressed: Boolean get() = method == STORED && size > 0
}
