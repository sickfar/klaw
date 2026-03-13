package io.github.klaw.engine.context

import jakarta.inject.Singleton
import java.util.concurrent.ConcurrentHashMap

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
        return wasQueued
    }

    fun isRunning(chatId: String): Boolean {
        val status = states[chatId] ?: return false
        return status == Status.COMPACTING || status == Status.QUEUED
    }

    fun status(chatId: String): Status = states[chatId] ?: Status.IDLE
}
