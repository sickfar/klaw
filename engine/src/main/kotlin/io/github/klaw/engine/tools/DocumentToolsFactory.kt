package io.github.klaw.engine.tools

import io.github.klaw.common.config.EngineConfig
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

@Factory
class DocumentToolsFactory {
    @Singleton
    fun pdfReadTool(
        config: EngineConfig,
        fileTools: FileTools,
    ): PdfReadTool = PdfReadTool(fileTools, config.documents)

    @Singleton
    fun mdToPdfTool(
        config: EngineConfig,
        fileTools: FileTools,
    ): MdToPdfTool = MdToPdfTool(fileTools, config.documents)
}
