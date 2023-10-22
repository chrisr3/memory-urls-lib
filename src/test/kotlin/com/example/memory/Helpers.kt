@file:JvmName("Helpers")
package com.example.memory

import java.io.InputStream
import java.nio.file.Path
import java.nio.file.Paths

fun Path.pathOf(vararg elements: String): Path = Paths.get(toAbsolutePath().toString(), *elements)

val String.toPathFormat: String get() = replace('.', '/')

private val String.resourceName: String get() = "$toPathFormat.class"
val Class<*>.resourceName get() = name.resourceName
val Class<*>.bytecode: ByteArray? get() = classLoader.getResourceAsStream(resourceName)?.use(InputStream::readBytes)
