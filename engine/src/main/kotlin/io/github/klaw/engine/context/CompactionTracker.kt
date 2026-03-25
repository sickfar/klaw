package io.github.klaw.engine.context

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Singleton
class CompactionTracker {
    enum class Status { IDLE, COMPACTING, QUEUED }

    private val states = ConcurrentHashMap<String, Status>()

    fun tryStart(chatId: String): Boolean {
        var transitioned = false
        states.compute(chatId) { _, current ->
            if (current == null || current == Status.IDLE) {
                transitioned = true
                Status.COMPACTING
            } else {
                current
            }
        }
        logger.trace { "Compaction tryStart: chatId=$chatId result=$transitioned" }
        return transitioned
    }

    fun queue(chatId: String) {
        states.compute(chatId) { _, current ->
            when (current) {
                Status.COMPACTING -> Status.QUEUED
                else -> current ?: Status.IDLE
            }
        }
    }

    fun complete(chatId: String): Boolean {
        var wasQueued = false
        states.compute(chatId) { _, current ->
            wasQueued = current == Status.QUEUED
            Status.IDLE
        }
        logger.trace { "Compaction complete: chatId=$chatId wasQueued=$wasQueued" }
        return wasQueued
    }

    fun isRunning(chatId: String): Boolean {
        val status = states[chatId] ?: return false
        return status == Status.COMPACTING || status == Status.QUEUED
    }

    fun status(chatId: String): Status = states[chatId] ?: Status.IDLE
}
