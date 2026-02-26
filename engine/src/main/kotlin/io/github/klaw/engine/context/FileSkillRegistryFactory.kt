package io.github.klaw.engine.context

import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.engine.context.stubs.StubSkillRegistry
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Replaces
import jakarta.inject.Singleton
import java.nio.file.Path

@Factory
class FileSkillRegistryFactory {
    @Singleton
    @Replaces(StubSkillRegistry::class)
    fun fileSkillRegistry(): FileSkillRegistry {
        val registry =
            FileSkillRegistry(
                dataSkillsDir = Path.of(KlawPaths.skills),
                workspaceSkillsDir = Path.of(KlawPaths.workspace, "skills"),
            )
        registry.discover()
        return registry
    }
}
