package io.github.klaw.engine.workspace

class HeartbeatParser {
    data class HeartbeatTask(
        val name: String,
        val cron: String,
        val message: String,
        val model: String? = null,
        val injectInto: String? = null,
    )

    fun parse(content: String): List<HeartbeatTask> {
        val tasks = mutableListOf<HeartbeatTask>()
        var currentName: String? = null
        var currentCron: String? = null
        var currentMessage: String? = null
        var currentModel: String? = null
        var currentInjectInto: String? = null

        @Suppress("ReturnCount")
        fun flushTask() {
            val name = currentName ?: return
            val cron = currentCron ?: return
            val message = currentMessage ?: return
            tasks.add(HeartbeatTask(name, cron, message, currentModel, currentInjectInto))
            currentName = null
            currentCron = null
            currentMessage = null
            currentModel = null
            currentInjectInto = null
        }

        for (rawLine in content.lines()) {
            val line = rawLine.trim()
            when {
                line.startsWith("## ") -> {
                    flushTask()
                    currentName = line.removePrefix("## ").trim()
                }

                line.startsWith("- Cron:") -> {
                    currentCron = line.removePrefix("- Cron:").trim()
                }

                line.startsWith("- Message:") -> {
                    currentMessage = line.removePrefix("- Message:").trim()
                }

                line.startsWith("- Model:") -> {
                    currentModel = line.removePrefix("- Model:").trim()
                }

                line.startsWith("- InjectInto:") -> {
                    currentInjectInto = line.removePrefix("- InjectInto:").trim()
                }
            }
        }
        flushTask()
        return tasks
    }
}
