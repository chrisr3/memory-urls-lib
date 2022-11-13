package com.r3.sgx.memory

import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.TreeSet
import java.util.jar.JarFile
import java.util.stream.Collectors.toCollection

class FooTest {
    @Test
    fun appURLs() {
        FileSystems.getFileSystem(URI.create("jrt:/")).let { fs ->
            Files.walk(fs.getPath("modules")).filter { path ->
                path.nameCount > 1
            }.map { path ->
                path.getName(1).toString()
            }.collect(toCollection(::TreeSet))
        }.forEach { moduleName ->
            println("Module: $moduleName")
        }

        ModuleLayer.boot().modules().filter { module ->
            module.classLoader == ClassLoader.getPlatformClassLoader()
        }.onEach { module ->
            println("Platform: ${module.name}")
        }

        val systemModules = ModuleLayer.boot().modules().filter { module ->
            module.classLoader == ClassLoader.getSystemClassLoader()
        }.onEach { module ->
            println("App: ${module.name}")
        }

        systemModules.flatMap { module ->
            module.classLoader.getResources(JarFile.MANIFEST_NAME).asSequence()
        }.onEach { url ->
            println ("System URL(1): $url")
        }
        ClassLoader.getSystemClassLoader().getResources(JarFile.MANIFEST_NAME).asSequence().forEach { url ->
            println("System URL(2): $url")
        }

        println("SYSTEM: " + ClassLoader.getSystemClassLoader().getResource("com/sun/tools/jdeps/ModuleDotGraph.class"))
        println("PLATFORM: " + ClassLoader.getPlatformClassLoader().getResource("com/sun/tools/jdeps/ModuleDotGraph.class"))
    }
}
