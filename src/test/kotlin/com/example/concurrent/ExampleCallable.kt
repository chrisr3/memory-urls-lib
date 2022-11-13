package com.example.concurrent

import org.slf4j.LoggerFactory
import java.util.concurrent.Callable

class ExampleCallable(private val input: Int) : Callable<String> {
    private val logger = LoggerFactory.getLogger(ExampleCallable::class.java)
    override fun call(): String {
        logger.info("Executing: {}", input)

        this::class.java.getAnnotation(Metadata::class.java)?.also { metadata ->
            logger.info("Kotlin data2={}", metadata.data2)
        }

        return if (input % 50 == 0) {
            "COLLECT-GARBAGE[$input]"
        } else if (input % 10 == 0) {
            "MULTIPLE-OF-10[$input]"
        } else {
            "OK[$input]"
        }
    }
}
