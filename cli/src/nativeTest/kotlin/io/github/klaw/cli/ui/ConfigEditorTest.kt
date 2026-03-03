package io.github.klaw.cli.ui

import io.github.klaw.common.config.ConfigPropertyDescriptor
import io.github.klaw.common.config.ConfigValueType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("LargeClass")
class ConfigEditorTest {
    // ── Helpers ──────────────────────────────────────────────────────────

    private fun descriptor(
        path: String,
        type: ConfigValueType = ConfigValueType.STRING,
        defaultValue: String? = null,
        possibleValues: List<String>? = null,
        sensitive: Boolean = false,
        required: Boolean = false,
    ) = ConfigPropertyDescriptor(
        path = path,
        type = type,
        description = "desc for $path",
        defaultValue = defaultValue,
        possibleValues = possibleValues,
        sensitive = sensitive,
        required = required,
    )

    private fun stateWith(
        descriptors: List<ConfigPropertyDescriptor>,
        config: JsonObject,
        viewportHeight: Int = 20,
    ): EditorState {
        val items = buildItems(descriptors, config)
        return EditorState(
            items = items,
            cursorIndex = 0,
            viewportTop = 0,
            viewportHeight = viewportHeight,
            editMode = EditMode.Navigation,
            modified = false,
            config = config,
        )
    }

    // ── buildItems tests ────────────────────────────────────────────────

    @Test
    fun `explicit properties come before defaults`() {
        val descriptors =
            listOf(
                descriptor("llm.model"),
                descriptor("llm.endpoint"),
                descriptor("llm.temperature"),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("gpt-4"))
                    },
                )
            }
        val items = buildItems(descriptors, config)

        // First property should be the explicit one
        val firstProp = items.filterIsInstance<EditorItem.Property>().first()
        assertEquals("llm.model", firstProp.descriptor.path)
        assertTrue(firstProp.isExplicit)

        // Default properties should come after divider
        val defaultProps = items.filterIsInstance<EditorItem.Property>().filter { !it.isExplicit }
        assertTrue(defaultProps.all { it.descriptor.path != "llm.model" })
    }

    @Test
    fun `section divider inserted between explicit and default sections`() {
        val descriptors =
            listOf(
                descriptor("llm.model"),
                descriptor("llm.endpoint"),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("gpt-4"))
                    },
                )
            }
        val items = buildItems(descriptors, config)

        val dividers = items.filterIsInstance<EditorItem.SectionDivider>()
        assertEquals(1, dividers.size)
        assertTrue(dividers[0].label.contains("Defaults"))
    }

    @Test
    fun `no divider when all properties are explicit`() {
        val descriptors =
            listOf(
                descriptor("llm.model"),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("gpt-4"))
                    },
                )
            }
        val items = buildItems(descriptors, config)

        val dividers = items.filterIsInstance<EditorItem.SectionDivider>()
        assertEquals(0, dividers.size)
    }

    @Test
    fun `no divider when all properties are defaults`() {
        val descriptors =
            listOf(
                descriptor("llm.model"),
                descriptor("llm.endpoint"),
            )
        val config = JsonObject(emptyMap())
        val items = buildItems(descriptors, config)

        val dividers = items.filterIsInstance<EditorItem.SectionDivider>()
        assertEquals(0, dividers.size)
    }

    @Test
    fun `MAP_SECTION and LIST_STRING types are filtered out`() {
        val descriptors =
            listOf(
                descriptor("llm.model", type = ConfigValueType.STRING),
                descriptor("llm.providers", type = ConfigValueType.MAP_SECTION),
                descriptor("llm.tags", type = ConfigValueType.LIST_STRING),
                descriptor("llm.temperature", type = ConfigValueType.DOUBLE),
            )
        val config = JsonObject(emptyMap())
        val items = buildItems(descriptors, config)

        val props = items.filterIsInstance<EditorItem.Property>()
        assertEquals(2, props.size)
        assertTrue(props.all { it.descriptor.type != ConfigValueType.MAP_SECTION })
        assertTrue(props.all { it.descriptor.type != ConfigValueType.LIST_STRING })
    }

    @Test
    fun `sensitive values are masked with asterisks`() {
        val descriptors =
            listOf(
                descriptor("llm.apiKey", sensitive = true),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("apiKey", JsonPrimitive("secret-key-123"))
                    },
                )
            }
        val items = buildItems(descriptors, config)

        val prop = items.filterIsInstance<EditorItem.Property>().first()
        assertEquals("***", prop.displayValue)
    }

    @Test
    fun `env var patterns shown as-is for sensitive fields`() {
        val descriptors =
            listOf(
                descriptor("llm.apiKey", sensitive = true),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("apiKey", JsonPrimitive("\${API_KEY}"))
                    },
                )
            }
        val items = buildItems(descriptors, config)

        val prop = items.filterIsInstance<EditorItem.Property>().first()
        assertEquals("\${API_KEY}", prop.displayValue)
    }

    @Test
    fun `default value shown for non-explicit properties`() {
        val descriptors =
            listOf(
                descriptor("llm.temperature", type = ConfigValueType.DOUBLE, defaultValue = "0.7"),
            )
        val config = JsonObject(emptyMap())
        val items = buildItems(descriptors, config)

        val prop = items.filterIsInstance<EditorItem.Property>().first()
        assertFalse(prop.isExplicit)
        assertEquals("0.7", prop.displayValue)
    }

    @Test
    fun `empty display value when no default and not explicit`() {
        val descriptors =
            listOf(
                descriptor("llm.model"),
            )
        val config = JsonObject(emptyMap())
        val items = buildItems(descriptors, config)

        val prop = items.filterIsInstance<EditorItem.Property>().first()
        assertEquals("", prop.displayValue)
    }

    // ── processEvent Navigation tests ───────────────────────────────────

    @Test
    fun `MoveDown moves cursor to next property`() {
        val descriptors =
            listOf(
                descriptor("llm.model"),
                descriptor("llm.endpoint"),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("gpt-4"))
                        put("endpoint", JsonPrimitive("http://localhost"))
                    },
                )
            }
        val state = stateWith(descriptors, config)
        val newState = processEvent(state, EditorEvent.MoveDown)

        assertEquals(1, newState.cursorIndex)
    }

    @Test
    fun `MoveUp moves cursor to previous property`() {
        val descriptors =
            listOf(
                descriptor("llm.model"),
                descriptor("llm.endpoint"),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("gpt-4"))
                        put("endpoint", JsonPrimitive("http://localhost"))
                    },
                )
            }
        val state = stateWith(descriptors, config).copy(cursorIndex = 1)
        val newState = processEvent(state, EditorEvent.MoveUp)

        assertEquals(0, newState.cursorIndex)
    }

    @Test
    fun `cursor does not go past top boundary`() {
        val descriptors =
            listOf(
                descriptor("llm.model"),
                descriptor("llm.endpoint"),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("gpt-4"))
                        put("endpoint", JsonPrimitive("http://localhost"))
                    },
                )
            }
        val state = stateWith(descriptors, config).copy(cursorIndex = 0)
        val newState = processEvent(state, EditorEvent.MoveUp)

        assertEquals(0, newState.cursorIndex)
    }

    @Test
    fun `cursor does not go past bottom boundary`() {
        val descriptors =
            listOf(
                descriptor("llm.model"),
                descriptor("llm.endpoint"),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("gpt-4"))
                        put("endpoint", JsonPrimitive("http://localhost"))
                    },
                )
            }
        val state = stateWith(descriptors, config)
        val lastIndex = state.items.lastIndex
        val stateAtEnd = state.copy(cursorIndex = lastIndex)
        val newState = processEvent(stateAtEnd, EditorEvent.MoveDown)

        assertEquals(lastIndex, newState.cursorIndex)
    }

    @Test
    fun `cursor skips SectionDivider on MoveDown`() {
        val descriptors =
            listOf(
                descriptor("llm.model"),
                descriptor("llm.endpoint"),
            )
        // model is explicit, endpoint is default -> divider in between
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("gpt-4"))
                    },
                )
            }
        val state = stateWith(descriptors, config)
        // Find the divider position
        val dividerIndex = state.items.indexOfFirst { it is EditorItem.SectionDivider }
        assertTrue(dividerIndex >= 0, "Should have a divider")

        // Position cursor just before divider
        val stateBeforeDivider = state.copy(cursorIndex = dividerIndex - 1)
        val newState = processEvent(stateBeforeDivider, EditorEvent.MoveDown)

        // Should skip past divider to next property
        assertEquals(dividerIndex + 1, newState.cursorIndex)
    }

    @Test
    fun `cursor skips SectionDivider on MoveUp`() {
        val descriptors =
            listOf(
                descriptor("llm.model"),
                descriptor("llm.endpoint"),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("gpt-4"))
                    },
                )
            }
        val state = stateWith(descriptors, config)
        val dividerIndex = state.items.indexOfFirst { it is EditorItem.SectionDivider }
        assertTrue(dividerIndex >= 0, "Should have a divider")

        // Position cursor just after divider
        val stateAfterDivider = state.copy(cursorIndex = dividerIndex + 1)
        val newState = processEvent(stateAfterDivider, EditorEvent.MoveUp)

        // Should skip past divider to previous property
        assertEquals(dividerIndex - 1, newState.cursorIndex)
    }

    @Test
    fun `MoveLeft on boolean toggles to false`() {
        val descriptors =
            listOf(
                descriptor("llm.verbose", type = ConfigValueType.BOOLEAN, defaultValue = "true"),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("verbose", JsonPrimitive(true))
                    },
                )
            }
        val state = stateWith(descriptors, config)
        val newState = processEvent(state, EditorEvent.MoveLeft)

        // Config should be updated
        assertTrue(newState.modified)
    }

    @Test
    fun `MoveRight on boolean toggles value`() {
        val descriptors =
            listOf(
                descriptor("llm.verbose", type = ConfigValueType.BOOLEAN, defaultValue = "false"),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("verbose", JsonPrimitive(false))
                    },
                )
            }
        val state = stateWith(descriptors, config)
        val newState = processEvent(state, EditorEvent.MoveRight)

        assertTrue(newState.modified)
    }

    @Test
    fun `MoveLeft on string with possibleValues cycles backward`() {
        val descriptors =
            listOf(
                descriptor(
                    "llm.model",
                    possibleValues = listOf("gpt-4", "gpt-3.5", "deepseek"),
                ),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("gpt-3.5"))
                    },
                )
            }
        val state = stateWith(descriptors, config)
        val newState = processEvent(state, EditorEvent.MoveLeft)

        // Should cycle from gpt-3.5 to gpt-4
        val prop = newState.items.filterIsInstance<EditorItem.Property>().first()
        assertEquals("gpt-4", prop.displayValue)
    }

    @Test
    fun `MoveRight on string with possibleValues cycles forward`() {
        val descriptors =
            listOf(
                descriptor(
                    "llm.model",
                    possibleValues = listOf("gpt-4", "gpt-3.5", "deepseek"),
                ),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("gpt-4"))
                    },
                )
            }
        val state = stateWith(descriptors, config)
        val newState = processEvent(state, EditorEvent.MoveRight)

        val prop = newState.items.filterIsInstance<EditorItem.Property>().first()
        assertEquals("gpt-3.5", prop.displayValue)
    }

    @Test
    fun `enum cycle wraps around forward`() {
        val descriptors =
            listOf(
                descriptor(
                    "llm.model",
                    possibleValues = listOf("gpt-4", "gpt-3.5", "deepseek"),
                ),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("deepseek"))
                    },
                )
            }
        val state = stateWith(descriptors, config)
        val newState = processEvent(state, EditorEvent.MoveRight)

        val prop = newState.items.filterIsInstance<EditorItem.Property>().first()
        assertEquals("gpt-4", prop.displayValue)
    }

    @Test
    fun `enum cycle wraps around backward`() {
        val descriptors =
            listOf(
                descriptor(
                    "llm.model",
                    possibleValues = listOf("gpt-4", "gpt-3.5", "deepseek"),
                ),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("gpt-4"))
                    },
                )
            }
        val state = stateWith(descriptors, config)
        val newState = processEvent(state, EditorEvent.MoveLeft)

        val prop = newState.items.filterIsInstance<EditorItem.Property>().first()
        assertEquals("deepseek", prop.displayValue)
    }

    @Test
    fun `Enter on text property enters InlineEdit mode`() {
        val descriptors =
            listOf(
                descriptor("llm.model"),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("gpt-4"))
                    },
                )
            }
        val state = stateWith(descriptors, config)
        val newState = processEvent(state, EditorEvent.Enter)

        assertTrue(newState.editMode is EditMode.InlineEdit)
        val editMode = newState.editMode
        assertEquals("gpt-4", editMode.buffer)
        assertEquals("gpt-4", editMode.originalValue)
    }

    @Test
    fun `Enter on SectionDivider is no-op`() {
        val descriptors =
            listOf(
                descriptor("llm.model"),
                descriptor("llm.endpoint"),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("gpt-4"))
                    },
                )
            }
        val state = stateWith(descriptors, config)
        val dividerIndex = state.items.indexOfFirst { it is EditorItem.SectionDivider }
        assertTrue(dividerIndex >= 0)

        val stateAtDivider = state.copy(cursorIndex = dividerIndex)
        val newState = processEvent(stateAtDivider, EditorEvent.Enter)

        assertEquals(EditMode.Navigation, newState.editMode)
    }

    @Test
    fun `Enter on number property enters InlineEdit mode`() {
        val descriptors =
            listOf(
                descriptor("llm.maxTokens", type = ConfigValueType.INT, defaultValue = "4096"),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("maxTokens", JsonPrimitive(4096))
                    },
                )
            }
        val state = stateWith(descriptors, config)
        val newState = processEvent(state, EditorEvent.Enter)

        assertTrue(newState.editMode is EditMode.InlineEdit)
        val editMode = newState.editMode
        assertEquals("4096", editMode.buffer)
    }

    // ── InlineEdit mode tests ───────────────────────────────────────────

    @Test
    fun `Char in InlineEdit appends to buffer`() {
        val descriptors = listOf(descriptor("llm.model"))
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("gpt"))
                    },
                )
            }
        val state =
            stateWith(descriptors, config).copy(
                editMode = EditMode.InlineEdit(buffer = "gpt", originalValue = "gpt-4"),
            )
        val newState = processEvent(state, EditorEvent.Char('-'))

        assertTrue(newState.editMode is EditMode.InlineEdit)
        assertEquals("gpt-", newState.editMode.buffer)
    }

    @Test
    fun `Backspace in InlineEdit removes last char`() {
        val descriptors = listOf(descriptor("llm.model"))
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("gpt-4"))
                    },
                )
            }
        val state =
            stateWith(descriptors, config).copy(
                editMode = EditMode.InlineEdit(buffer = "gpt-4", originalValue = "gpt-4"),
            )
        val newState = processEvent(state, EditorEvent.Backspace)

        assertTrue(newState.editMode is EditMode.InlineEdit)
        assertEquals("gpt-", newState.editMode.buffer)
    }

    @Test
    fun `Backspace on empty buffer is no-op`() {
        val descriptors = listOf(descriptor("llm.model"))
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("gpt-4"))
                    },
                )
            }
        val state =
            stateWith(descriptors, config).copy(
                editMode = EditMode.InlineEdit(buffer = "", originalValue = "gpt-4"),
            )
        val newState = processEvent(state, EditorEvent.Backspace)

        assertTrue(newState.editMode is EditMode.InlineEdit)
        assertEquals("", newState.editMode.buffer)
    }

    @Test
    fun `Enter in InlineEdit with valid string value applies to config`() {
        val descriptors = listOf(descriptor("llm.model"))
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("gpt-4"))
                    },
                )
            }
        val state =
            stateWith(descriptors, config).copy(
                editMode = EditMode.InlineEdit(buffer = "deepseek", originalValue = "gpt-4"),
            )
        val newState = processEvent(state, EditorEvent.Enter)

        assertEquals(EditMode.Navigation, newState.editMode)
        assertTrue(newState.modified)
        // Verify the config was updated
        val prop = newState.items.filterIsInstance<EditorItem.Property>().first()
        assertEquals("deepseek", prop.displayValue)
    }

    @Test
    fun `Enter in InlineEdit with invalid INT value stays in edit mode`() {
        val descriptors =
            listOf(
                descriptor("llm.maxTokens", type = ConfigValueType.INT),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("maxTokens", JsonPrimitive(4096))
                    },
                )
            }
        val state =
            stateWith(descriptors, config).copy(
                editMode = EditMode.InlineEdit(buffer = "not-a-number", originalValue = "4096"),
            )
        val newState = processEvent(state, EditorEvent.Enter)

        // Should stay in InlineEdit mode because validation failed
        assertTrue(newState.editMode is EditMode.InlineEdit)
    }

    @Test
    fun `Enter in InlineEdit with valid INT value applies to config`() {
        val descriptors =
            listOf(
                descriptor("llm.maxTokens", type = ConfigValueType.INT),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("maxTokens", JsonPrimitive(4096))
                    },
                )
            }
        val state =
            stateWith(descriptors, config).copy(
                editMode = EditMode.InlineEdit(buffer = "8192", originalValue = "4096"),
            )
        val newState = processEvent(state, EditorEvent.Enter)

        assertEquals(EditMode.Navigation, newState.editMode)
        assertTrue(newState.modified)
    }

    @Test
    fun `Enter in InlineEdit with invalid DOUBLE value stays in edit mode`() {
        val descriptors =
            listOf(
                descriptor("llm.temperature", type = ConfigValueType.DOUBLE),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("temperature", JsonPrimitive(0.7))
                    },
                )
            }
        val state =
            stateWith(descriptors, config).copy(
                editMode = EditMode.InlineEdit(buffer = "abc", originalValue = "0.7"),
            )
        val newState = processEvent(state, EditorEvent.Enter)

        assertTrue(newState.editMode is EditMode.InlineEdit)
    }

    @Test
    fun `Enter in InlineEdit with valid DOUBLE value applies to config`() {
        val descriptors =
            listOf(
                descriptor("llm.temperature", type = ConfigValueType.DOUBLE),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("temperature", JsonPrimitive(0.7))
                    },
                )
            }
        val state =
            stateWith(descriptors, config).copy(
                editMode = EditMode.InlineEdit(buffer = "0.9", originalValue = "0.7"),
            )
        val newState = processEvent(state, EditorEvent.Enter)

        assertEquals(EditMode.Navigation, newState.editMode)
        assertTrue(newState.modified)
    }

    @Test
    fun `Enter in InlineEdit with invalid LONG value stays in edit mode`() {
        val descriptors =
            listOf(
                descriptor("llm.timeout", type = ConfigValueType.LONG),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("timeout", JsonPrimitive(30000L))
                    },
                )
            }
        val state =
            stateWith(descriptors, config).copy(
                editMode = EditMode.InlineEdit(buffer = "nope", originalValue = "30000"),
            )
        val newState = processEvent(state, EditorEvent.Enter)

        assertTrue(newState.editMode is EditMode.InlineEdit)
    }

    @Test
    fun `Escape in InlineEdit cancels back to Navigation`() {
        val descriptors = listOf(descriptor("llm.model"))
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("gpt-4"))
                    },
                )
            }
        val state =
            stateWith(descriptors, config).copy(
                editMode = EditMode.InlineEdit(buffer = "deepseek", originalValue = "gpt-4"),
            )
        val newState = processEvent(state, EditorEvent.Escape)

        assertEquals(EditMode.Navigation, newState.editMode)
        // Config should NOT be modified
        assertFalse(newState.modified)
    }

    @Test
    fun `command keys in InlineEdit are treated as character input`() {
        // 's' key normally maps to Save, but in InlineEdit it should be a character
        val descriptors = listOf(descriptor("llm.model"))
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("test"))
                    },
                )
            }
        val state =
            stateWith(descriptors, config).copy(
                editMode = EditMode.InlineEdit(buffer = "te", originalValue = "test"),
            )

        // Save event in InlineEdit should append 's'
        val afterS = processEvent(state, EditorEvent.Save)
        assertTrue(afterS.editMode is EditMode.InlineEdit)
        assertEquals("tes", afterS.editMode.buffer)

        // Quit event should append 'q'
        val afterQ = processEvent(state, EditorEvent.Quit)
        assertTrue(afterQ.editMode is EditMode.InlineEdit)
        assertEquals("teq", afterQ.editMode.buffer)

        // Delete event should append 'd'
        val afterD = processEvent(state, EditorEvent.Delete)
        assertTrue(afterD.editMode is EditMode.InlineEdit)
        assertEquals("ted", afterD.editMode.buffer)

        // Add event should append 'a'
        val afterA = processEvent(state, EditorEvent.Add)
        assertTrue(afterA.editMode is EditMode.InlineEdit)
        assertEquals("tea", afterA.editMode.buffer)
    }

    // ── PageUp / PageDown tests ─────────────────────────────────────────

    @Test
    fun `PageDown moves cursor by viewport height`() {
        val descriptors = (1..30).map { descriptor("prop.$it") }
        val config =
            buildJsonObject {
                put(
                    "prop",
                    buildJsonObject {
                        for (i in 1..30) put("$i", JsonPrimitive("value$i"))
                    },
                )
            }
        val state = stateWith(descriptors, config, viewportHeight = 10)
        val newState = processEvent(state, EditorEvent.PageDown)

        assertEquals(10, newState.cursorIndex)
    }

    @Test
    fun `PageUp moves cursor by viewport height`() {
        val descriptors = (1..30).map { descriptor("prop.$it") }
        val config =
            buildJsonObject {
                put(
                    "prop",
                    buildJsonObject {
                        for (i in 1..30) put("$i", JsonPrimitive("value$i"))
                    },
                )
            }
        val state = stateWith(descriptors, config, viewportHeight = 10).copy(cursorIndex = 15)
        val newState = processEvent(state, EditorEvent.PageUp)

        assertEquals(5, newState.cursorIndex)
    }

    @Test
    fun `PageDown clamps to last item`() {
        val descriptors = (1..5).map { descriptor("prop.$it") }
        val config =
            buildJsonObject {
                put(
                    "prop",
                    buildJsonObject {
                        for (i in 1..5) put("$i", JsonPrimitive("value$i"))
                    },
                )
            }
        val state = stateWith(descriptors, config, viewportHeight = 10).copy(cursorIndex = 3)
        val newState = processEvent(state, EditorEvent.PageDown)

        assertEquals(state.items.lastIndex, newState.cursorIndex)
    }

    @Test
    fun `PageUp clamps to first item`() {
        val descriptors = (1..5).map { descriptor("prop.$it") }
        val config =
            buildJsonObject {
                put(
                    "prop",
                    buildJsonObject {
                        for (i in 1..5) put("$i", JsonPrimitive("value$i"))
                    },
                )
            }
        val state = stateWith(descriptors, config, viewportHeight = 10).copy(cursorIndex = 2)
        val newState = processEvent(state, EditorEvent.PageUp)

        assertEquals(0, newState.cursorIndex)
    }

    // ── Viewport scrolling tests ────────────────────────────────────────

    @Test
    fun `viewport scrolls down when cursor goes below visible area`() {
        val descriptors = (1..30).map { descriptor("prop.$it") }
        val config =
            buildJsonObject {
                put(
                    "prop",
                    buildJsonObject {
                        for (i in 1..30) put("$i", JsonPrimitive("value$i"))
                    },
                )
            }
        val state =
            stateWith(descriptors, config, viewportHeight = 5)
                .copy(cursorIndex = 4, viewportTop = 0)
        val newState = processEvent(state, EditorEvent.MoveDown)

        // cursor moves to 5, viewport must shift so cursor is visible
        assertTrue(newState.viewportTop > 0)
    }

    @Test
    fun `viewport scrolls up when cursor goes above visible area`() {
        val descriptors = (1..30).map { descriptor("prop.$it") }
        val config =
            buildJsonObject {
                put(
                    "prop",
                    buildJsonObject {
                        for (i in 1..30) put("$i", JsonPrimitive("value$i"))
                    },
                )
            }
        val state =
            stateWith(descriptors, config, viewportHeight = 5)
                .copy(cursorIndex = 5, viewportTop = 5)
        val newState = processEvent(state, EditorEvent.MoveUp)

        // cursor moves to 4, viewport must shift so cursor is visible
        assertTrue(newState.viewportTop <= 4)
    }

    // ── Boolean toggle updates config tests ─────────────────────────────

    @Test
    fun `boolean toggle from true to false updates config via setByPath`() {
        val descriptors =
            listOf(
                descriptor("llm.verbose", type = ConfigValueType.BOOLEAN),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("verbose", JsonPrimitive(true))
                    },
                )
            }
        val state = stateWith(descriptors, config)
        val newState = processEvent(state, EditorEvent.MoveRight)

        assertTrue(newState.modified)
        val prop = newState.items.filterIsInstance<EditorItem.Property>().first()
        assertEquals("false", prop.displayValue)
    }

    @Test
    fun `boolean toggle from false to true updates config via setByPath`() {
        val descriptors =
            listOf(
                descriptor("llm.verbose", type = ConfigValueType.BOOLEAN),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("verbose", JsonPrimitive(false))
                    },
                )
            }
        val state = stateWith(descriptors, config)
        val newState = processEvent(state, EditorEvent.MoveRight)

        assertTrue(newState.modified)
        val prop = newState.items.filterIsInstance<EditorItem.Property>().first()
        assertEquals("true", prop.displayValue)
    }

    @Test
    fun `boolean toggle on default value creates explicit entry`() {
        val descriptors =
            listOf(
                descriptor("llm.verbose", type = ConfigValueType.BOOLEAN, defaultValue = "true"),
            )
        val config = JsonObject(emptyMap())
        val state = stateWith(descriptors, config)
        val newState = processEvent(state, EditorEvent.MoveRight)

        assertTrue(newState.modified)
        // The property should now be explicit
        val prop = newState.items.filterIsInstance<EditorItem.Property>().first()
        assertEquals("false", prop.displayValue)
    }

    // ── Save/Quit marker tests ──────────────────────────────────────────

    @Test
    fun `Save event in Navigation mode sets save action`() {
        val descriptors = listOf(descriptor("llm.model"))
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("gpt-4"))
                    },
                )
            }
        val state = stateWith(descriptors, config)
        val newState = processEvent(state, EditorEvent.Save)

        assertEquals(EditorAction.SAVE, newState.pendingAction)
    }

    @Test
    fun `Quit event in Navigation mode sets quit action`() {
        val descriptors = listOf(descriptor("llm.model"))
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("gpt-4"))
                    },
                )
            }
        val state = stateWith(descriptors, config)
        val newState = processEvent(state, EditorEvent.Quit)

        assertEquals(EditorAction.QUIT, newState.pendingAction)
    }

    // ── Enter on BOOLEAN does not enter InlineEdit ──────────────────────

    @Test
    fun `Enter on BOOLEAN property toggles instead of entering edit mode`() {
        val descriptors =
            listOf(
                descriptor("llm.verbose", type = ConfigValueType.BOOLEAN),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("verbose", JsonPrimitive(true))
                    },
                )
            }
        val state = stateWith(descriptors, config)
        val newState = processEvent(state, EditorEvent.Enter)

        // Should toggle, not enter InlineEdit
        assertEquals(EditMode.Navigation, newState.editMode)
        val prop = newState.items.filterIsInstance<EditorItem.Property>().first()
        assertEquals("false", prop.displayValue)
    }

    @Test
    fun `Enter on enum property cycles forward instead of entering edit mode`() {
        val descriptors =
            listOf(
                descriptor(
                    "llm.model",
                    possibleValues = listOf("gpt-4", "gpt-3.5", "deepseek"),
                ),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("gpt-4"))
                    },
                )
            }
        val state = stateWith(descriptors, config)
        val newState = processEvent(state, EditorEvent.Enter)

        // Should cycle, not enter InlineEdit
        assertEquals(EditMode.Navigation, newState.editMode)
        val prop = newState.items.filterIsInstance<EditorItem.Property>().first()
        assertEquals("gpt-3.5", prop.displayValue)
    }

    // ── Enter on sensitive field enters edit with raw value ──────────────

    @Test
    fun `Enter on sensitive field opens InlineEdit with actual value`() {
        val descriptors =
            listOf(
                descriptor("llm.apiKey", sensitive = true),
            )
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("apiKey", JsonPrimitive("\${API_KEY}"))
                    },
                )
            }
        val state = stateWith(descriptors, config)
        val newState = processEvent(state, EditorEvent.Enter)

        assertTrue(newState.editMode is EditMode.InlineEdit)
        val editMode = newState.editMode
        // Should show the actual env var pattern, not masked
        assertEquals("\${API_KEY}", editMode.buffer)
    }

    // ── Unknown event is no-op ──────────────────────────────────────────

    @Test
    fun `Unknown event in Navigation mode is no-op`() {
        val descriptors = listOf(descriptor("llm.model"))
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("gpt-4"))
                    },
                )
            }
        val state = stateWith(descriptors, config)
        val newState = processEvent(state, EditorEvent.Unknown)

        assertEquals(state.cursorIndex, newState.cursorIndex)
        assertEquals(state.editMode, newState.editMode)
        assertEquals(state.modified, newState.modified)
    }

    @Test
    fun `Unknown event in InlineEdit mode is no-op`() {
        val descriptors = listOf(descriptor("llm.model"))
        val config =
            buildJsonObject {
                put(
                    "llm",
                    buildJsonObject {
                        put("model", JsonPrimitive("gpt-4"))
                    },
                )
            }
        val state =
            stateWith(descriptors, config).copy(
                editMode = EditMode.InlineEdit(buffer = "test", originalValue = "gpt-4"),
            )
        val newState = processEvent(state, EditorEvent.Unknown)

        assertTrue(newState.editMode is EditMode.InlineEdit)
        assertEquals("test", newState.editMode.buffer)
    }

    // ── Wildcard map expansion ────────────────────────────────────────────

    @Test
    fun `buildItems expands wildcard paths using actual map keys`() {
        val config =
            buildJsonObject {
                put(
                    "providers",
                    buildJsonObject {
                        put(
                            "zai",
                            buildJsonObject {
                                put("type", JsonPrimitive("openai-compatible"))
                                put("endpoint", JsonPrimitive("https://api.example.com"))
                            },
                        )
                    },
                )
            }
        val descriptors =
            listOf(
                descriptor("providers", type = ConfigValueType.MAP_SECTION),
                descriptor("providers.*.type"),
                descriptor("providers.*.endpoint"),
                descriptor("providers.*.apiKey", sensitive = true),
            )

        val items = buildItems(descriptors, config)
        val props = items.filterIsInstance<EditorItem.Property>()
        val explicit = props.filter { it.isExplicit }

        assertEquals(2, explicit.size)
        assertTrue(explicit.any { it.descriptor.path == "providers.zai.type" })
        assertTrue(explicit.any { it.descriptor.path == "providers.zai.endpoint" })
    }

    @Test
    fun `buildItems wildcard with multiple map keys shows all entries as explicit`() {
        val config =
            buildJsonObject {
                put(
                    "providers",
                    buildJsonObject {
                        put("zai", buildJsonObject { put("type", JsonPrimitive("openai-compatible")) })
                        put("deepseek", buildJsonObject { put("type", JsonPrimitive("openai-compatible")) })
                    },
                )
            }
        val descriptors =
            listOf(
                descriptor("providers.*.type"),
                descriptor("providers.*.endpoint"),
            )

        val items = buildItems(descriptors, config)
        val props = items.filterIsInstance<EditorItem.Property>()
        val explicit = props.filter { it.isExplicit }

        // Both providers have 'type' set
        assertEquals(2, explicit.size)
        assertTrue(explicit.any { it.descriptor.path == "providers.zai.type" })
        assertTrue(explicit.any { it.descriptor.path == "providers.deepseek.type" })
    }

    @Test
    fun `buildItems wildcard with empty map shows no items`() {
        val config = buildJsonObject { put("providers", buildJsonObject {}) }
        val descriptors = listOf(descriptor("providers.*.type"))

        val items = buildItems(descriptors, config)
        assertTrue(items.filterIsInstance<EditorItem.Property>().isEmpty())
    }

    @Test
    fun `buildItems wildcard when map absent shows no items`() {
        val config = JsonObject(emptyMap())
        val descriptors = listOf(descriptor("providers.*.type"))

        val items = buildItems(descriptors, config)
        assertTrue(items.filterIsInstance<EditorItem.Property>().isEmpty())
    }
}
