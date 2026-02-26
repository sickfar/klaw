package io.github.klaw.engine.context

import io.github.klaw.engine.util.VT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

class FileCoreMemoryService(
    private val coreMemoryPath: Path,
) : CoreMemoryService {
    private val validSections = listOf("user", "agent")

    private fun readMemory(): JsonObject =
        if (Files.exists(coreMemoryPath)) {
            Json.parseToJsonElement(Files.readString(coreMemoryPath)).jsonObject
        } else {
            buildJsonObject {
                put("user", JsonObject(emptyMap()))
                put("agent", JsonObject(emptyMap()))
            }
        }

    private fun writeMemory(obj: JsonObject) {
        val parent = coreMemoryPath.parent
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent)
        }
        Files.writeString(coreMemoryPath, Json.encodeToString(JsonObject.serializer(), obj))
    }

    override suspend fun load(): String =
        withContext(Dispatchers.VT) {
            val mem = readMemory()
            val sb = StringBuilder()
            for (section in validSections) {
                val sectionObj = mem[section]?.jsonObject ?: continue
                if (sectionObj.isNotEmpty()) {
                    sb.appendLine("[$section]")
                    sectionObj.forEach { (k, v) ->
                        val text = runCatching { v.jsonPrimitive.content }.getOrDefault(v.toString())
                        sb.appendLine("$k: $text")
                    }
                }
            }
            sb.toString().trimEnd()
        }

    override suspend fun getJson(): String =
        withContext(Dispatchers.VT) {
            Json.encodeToString(JsonObject.serializer(), readMemory())
        }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun update(
        section: String,
        key: String,
        value: String,
    ): String =
        withContext(Dispatchers.VT) {
            if (section !in validSections) return@withContext "Error: section must be 'user' or 'agent'"
            try {
                val mem = readMemory()
                val sectionObj = (mem[section]?.jsonObject ?: JsonObject(emptyMap())).toMutableMap()
                sectionObj[key] = JsonPrimitive(value)
                val updated =
                    buildJsonObject {
                        mem.forEach { (k, v) ->
                            if (k == section) put(k, JsonObject(sectionObj)) else put(k, v)
                        }
                    }
                writeMemory(updated)
                "OK: $section.$key updated"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun delete(
        section: String,
        key: String,
    ): String =
        withContext(Dispatchers.VT) {
            if (section !in validSections) return@withContext "Error: section must be 'user' or 'agent'"
            try {
                val mem = readMemory()
                val sectionObj =
                    mem[section]?.jsonObject
                        ?: return@withContext "Error: key '$key' not found in '$section'"
                if (!sectionObj.containsKey(key)) {
                    return@withContext "Error: key '$key' not found in '$section'"
                }
                val updated = sectionObj.toMutableMap().apply { remove(key) }
                val newMem =
                    buildJsonObject {
                        mem.forEach { (k, v) ->
                            if (k == section) put(k, JsonObject(updated)) else put(k, v)
                        }
                    }
                writeMemory(newMem)
                "OK: $section.$key deleted"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }
}
