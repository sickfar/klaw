package io.github.klaw.engine.tools

class FakeDockerClient : DockerClient {
    val runCalls = mutableListOf<DockerRunOptions>()
    val execCalls = mutableListOf<ExecCall>()
    val stopCalls = mutableListOf<String>()
    val rmCalls = mutableListOf<String>()

    var nextRunResult: String = "fake-container-id"
    var nextExecResult: ExecutionResult = ExecutionResult(stdout = "", stderr = "", exitCode = 0)

    data class ExecCall(
        val containerId: String,
        val cmd: List<String>,
        val timeout: Int,
    )

    override suspend fun run(options: DockerRunOptions): String {
        runCalls.add(options)
        return nextRunResult
    }

    override suspend fun exec(
        containerId: String,
        cmd: List<String>,
        timeoutSeconds: Int,
    ): ExecutionResult {
        execCalls.add(ExecCall(containerId, cmd, timeoutSeconds))
        return nextExecResult
    }

    override suspend fun stop(containerId: String) {
        stopCalls.add(containerId)
    }

    override suspend fun rm(containerId: String) {
        rmCalls.add(containerId)
    }
}
