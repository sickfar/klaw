package io.github.klaw.engine.tools

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.llm.ToolCall
import io.github.klaw.common.llm.ToolDef
import io.github.klaw.common.llm.ToolResult
import io.github.klaw.engine.context.ToolRegistry
import io.github.klaw.engine.context.stubs.StubToolRegistry
import io.micronaut.context.annotation.Replaces
import jakarta.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Singleton
@Replaces(StubToolRegistry::class)
@Suppress("TooManyFunctions", "LongParameterList")
class ToolRegistryImpl(
    private val fileTools: FileTools,
    private val skillTools: SkillTools,
    private val memoryTools: MemoryTools,
    private val docsTools: DocsTools,
    private val scheduleTools: ScheduleTools,
    private val subagentTools: SubagentTools,
    private val utilityTools: UtilityTools,
    private val config: EngineConfig,
) : ToolRegistry {
    override suspend fun listTools(): List<ToolDef> =
        if (config.docs.enabled) {
            toolDefs
        } else {
            toolDefs.filter { it.name !in DOCS_TOOL_NAMES }
        }

    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(call: ToolCall): ToolResult {
        val result =
            try {
                val args =
                    if (call.arguments.isBlank()) {
                        JsonObject(emptyMap())
                    } else {
                        Json.parseToJsonElement(call.arguments).jsonObject
                    }
                dispatch(call.name, args)
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        return ToolResult(callId = call.id, content = result)
    }

    @Suppress("CyclomaticComplexity", "LongMethod", "CyclomaticComplexMethod")
    private suspend fun dispatch(
        name: String,
        args: JsonObject,
    ): String =
        when (name) {
            "file_read" -> {
                fileTools.read(
                    args.str("path"),
                    args.intOrNull("startLine"),
                    args.intOrNull("maxLines"),
                )
            }

            "file_write" -> {
                fileTools.write(args.str("path"), args.str("content"), args.str("mode"))
            }

            "file_list" -> {
                fileTools.list(args.str("path"), args.boolOrNull("recursive") ?: false)
            }

            "memory_search" -> {
                memoryTools.search(args.str("query"), args.intOrNull("topK") ?: DEFAULT_MEMORY_TOP_K)
            }

            "memory_save" -> {
                memoryTools.save(args.str("content"), args.strOrNull("source") ?: "manual")
            }

            "memory_core_get" -> {
                memoryTools.coreGet()
            }

            "memory_core_update" -> {
                memoryTools.coreUpdate(
                    args.str("section"),
                    args.str("key"),
                    args.str("value"),
                )
            }

            "memory_core_delete" -> {
                memoryTools.coreDelete(args.str("section"), args.str("key"))
            }

            "docs_search" -> {
                docsTools.search(args.str("query"), args.intOrNull("topK") ?: DEFAULT_DOCS_TOP_K)
            }

            "docs_read" -> {
                docsTools.read(args.str("path"))
            }

            "docs_list" -> {
                docsTools.list()
            }

            "skill_list" -> {
                skillTools.list()
            }

            "skill_load" -> {
                skillTools.load(args.str("name"))
            }

            "schedule_list" -> {
                scheduleTools.list()
            }

            "schedule_add" -> {
                scheduleTools.add(
                    args.str("name"),
                    args.str("cron"),
                    args.str("message"),
                    args.strOrNull("model"),
                    args.strOrNull("injectInto"),
                )
            }

            "schedule_remove" -> {
                scheduleTools.remove(args.str("name"))
            }

            "subagent_spawn" -> {
                subagentTools.spawn(
                    args.str("name"),
                    args.str("message"),
                    args.strOrNull("model"),
                    args.strOrNull("injectInto"),
                )
            }

            "current_time" -> {
                utilityTools.currentTime()
            }

            "send_message" -> {
                utilityTools.sendMessage(
                    args.str("channel"),
                    args.str("chatId"),
                    args.str("text"),
                )
            }

            else -> {
                "Error: unknown tool '$name'"
            }
        }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing required parameter: $key")

    private fun JsonObject.strOrNull(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun JsonObject.intOrNull(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.boolOrNull(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

    companion object {
        private const val DEFAULT_MEMORY_TOP_K = 10
        private const val DEFAULT_DOCS_TOP_K = 5
        private val DOCS_TOOL_NAMES = setOf("docs_search", "docs_read", "docs_list")

        private val toolDefs =
            listOf(
                ToolDef(
                    "file_read",
                    "Прочитать файл из рабочей директории",
                    toolParams(
                        listOf("path"),
                        mapOf(
                            "path" to stringProp("Путь к файлу относительно workspace"),
                            "startLine" to intProp("Начальная строка (1-based)"),
                            "maxLines" to intProp("Максимальное количество строк"),
                        ),
                    ),
                ),
                ToolDef(
                    "file_write",
                    "Записать содержимое в файл",
                    toolParams(
                        listOf("path", "content", "mode"),
                        mapOf(
                            "path" to stringProp("Путь к файлу относительно workspace"),
                            "content" to stringProp("Содержимое для записи"),
                            "mode" to stringProp("Режим записи: overwrite или append"),
                        ),
                    ),
                ),
                ToolDef(
                    "file_list",
                    "Показать содержимое директории",
                    toolParams(
                        listOf("path"),
                        mapOf(
                            "path" to stringProp("Путь к директории относительно workspace"),
                            "recursive" to boolProp("Рекурсивный обход поддиректорий"),
                        ),
                    ),
                ),
                ToolDef(
                    "memory_search",
                    "Поиск по долговременной памяти",
                    toolParams(
                        listOf("query"),
                        mapOf(
                            "query" to stringProp("Поисковый запрос"),
                            "topK" to intProp("Количество результатов (по умолчанию 10)"),
                        ),
                    ),
                ),
                ToolDef(
                    "memory_save",
                    "Сохранить информацию в долговременную память",
                    toolParams(
                        listOf("content"),
                        mapOf(
                            "content" to stringProp("Текст для сохранения"),
                            "source" to stringProp("Источник информации (по умолчанию manual)"),
                        ),
                    ),
                ),
                ToolDef(
                    "memory_core_get",
                    "Получить текущее содержимое основной памяти (user/agent)",
                    toolParams(emptyList(), emptyMap()),
                ),
                ToolDef(
                    "memory_core_update",
                    "Обновить ключ в основной памяти (user/agent секция)",
                    toolParams(
                        listOf("section", "key", "value"),
                        mapOf(
                            "section" to stringProp("Секция: user или agent"),
                            "key" to stringProp("Ключ"),
                            "value" to stringProp("Значение"),
                        ),
                    ),
                ),
                ToolDef(
                    "memory_core_delete",
                    "Удалить ключ из основной памяти",
                    toolParams(
                        listOf("section", "key"),
                        mapOf(
                            "section" to stringProp("Секция: user или agent"),
                            "key" to stringProp("Ключ для удаления"),
                        ),
                    ),
                ),
                ToolDef(
                    "docs_search",
                    "Поиск по документации проекта",
                    toolParams(
                        listOf("query"),
                        mapOf(
                            "query" to stringProp("Поисковый запрос"),
                            "topK" to intProp("Количество результатов (по умолчанию 5)"),
                        ),
                    ),
                ),
                ToolDef(
                    "docs_read",
                    "Прочитать документ по пути",
                    toolParams(
                        listOf("path"),
                        mapOf("path" to stringProp("Путь к документу")),
                    ),
                ),
                ToolDef(
                    "docs_list",
                    "Показать список доступных документов",
                    toolParams(emptyList(), emptyMap()),
                ),
                ToolDef(
                    "skill_list",
                    "Показать список доступных навыков",
                    toolParams(emptyList(), emptyMap()),
                ),
                ToolDef(
                    "skill_load",
                    "Загрузить полное содержимое навыка",
                    toolParams(
                        listOf("name"),
                        mapOf("name" to stringProp("Имя навыка")),
                    ),
                ),
                ToolDef(
                    "schedule_list",
                    "Показать список запланированных задач",
                    toolParams(emptyList(), emptyMap()),
                ),
                ToolDef(
                    "schedule_add",
                    "Добавить запланированную задачу",
                    toolParams(
                        listOf("name", "cron", "message"),
                        mapOf(
                            "name" to stringProp("Уникальное имя задачи"),
                            "cron" to stringProp("Cron-выражение расписания"),
                            "message" to stringProp("Сообщение для выполнения"),
                            "model" to stringProp("Модель LLM (необязательно)"),
                            "injectInto" to stringProp("chatId для отправки результата (необязательно)"),
                        ),
                    ),
                ),
                ToolDef(
                    "schedule_remove",
                    "Удалить запланированную задачу",
                    toolParams(
                        listOf("name"),
                        mapOf("name" to stringProp("Имя задачи для удаления")),
                    ),
                ),
                ToolDef(
                    "subagent_spawn",
                    "Запустить субагента для выполнения задачи",
                    toolParams(
                        listOf("name", "message"),
                        mapOf(
                            "name" to stringProp("Имя субагента"),
                            "message" to stringProp("Задание для субагента"),
                            "model" to stringProp("Модель LLM (необязательно)"),
                            "injectInto" to stringProp("chatId для отправки результата (необязательно)"),
                        ),
                    ),
                ),
                ToolDef(
                    "current_time",
                    "Получить текущую дату и время",
                    toolParams(emptyList(), emptyMap()),
                ),
                ToolDef(
                    "send_message",
                    "Отправить сообщение в указанный канал и чат",
                    toolParams(
                        listOf("channel", "chatId", "text"),
                        mapOf(
                            "channel" to stringProp("Канал (telegram, discord и т.д.)"),
                            "chatId" to stringProp("Идентификатор чата"),
                            "text" to stringProp("Текст сообщения"),
                        ),
                    ),
                ),
            )
    }
}
