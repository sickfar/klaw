package io.github.klaw.engine.tools

import io.github.klaw.engine.context.SkillMeta
import io.github.klaw.engine.context.SkillRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SkillToolsTest {
    private val registry = mockk<SkillRegistry>()
    private val skillTools = SkillTools(registry)

    @Test
    fun `list returns formatted skill list`() =
        runTest {
            coEvery { registry.listAll() } returns
                listOf(
                    SkillMeta("skill-a", "Does A"),
                    SkillMeta("skill-b", "Does B"),
                )

            val result = skillTools.list()
            assertTrue(result.contains("- skill-a: Does A"))
            assertTrue(result.contains("- skill-b: Does B"))
        }

    @Test
    fun `list returns message when no skills`() =
        runTest {
            coEvery { registry.listAll() } returns emptyList()

            val result = skillTools.list()
            assertEquals("No skills available", result)
        }

    @Test
    fun `load returns full content`() =
        runTest {
            coEvery { registry.getFullContent("my-skill") } returns "# My Skill\nContent here."

            val result = skillTools.load("my-skill")
            assertEquals("# My Skill\nContent here.", result)
        }

    @Test
    fun `load returns error for unknown skill`() =
        runTest {
            coEvery { registry.getFullContent("unknown") } returns null

            val result = skillTools.load("unknown")
            assertTrue(result.contains("not found"), "Expected not found but got: $result")
        }
}
