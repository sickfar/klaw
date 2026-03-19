package io.github.klaw.engine.tools

class FakeDockerClient : DockerClient {
    val runCalls = mutableListOf<DockerRunOptions>()
    val execCalls = mutableListOf<ExecCall>()
    val stopCalls = mutableListOf<String>()
    val rmCalls = mutableListOf<String>()
    val listContainersCalls = mutableListOf<String>()

    var nextRunResult: ExecutionResult = ExecutionResult(stdout = "fake-container-id", stderr = "", exitCode = 0)
    var nextRunException: Exception? = null
    var nextExecResult: ExecutionResult = ExecutionResult(stdout = "", stderr = "", exitCode = 0)
    var nextExecException: Exception? = null
    var listContainersResult: List<String> = emptyList()
    var listContainersException: Exception? = null

    val execResults: MutableList<ExecutionResult> = mutableListOf()

    data class ExecCall(
        val containerId: String,
        val cmd: List<String>,
        val timeout: Int,
    )

    override suspend fun run(options: DockerRunOptions): ExecutionResult {
        runCalls.add(options)
        nextRunException?.let { throw it }
        return nextRunResult
    }

    override suspend fun exec(
        containerId: String,
        cmd: List<String>,
        timeoutSeconds: Int,
    ): ExecutionResult {
        execCalls.add(ExecCall(containerId, cmd, timeoutSeconds))
        nextExecException?.let { throw it }
        return if (execResults.isNotEmpty()) execResults.removeFirst() else nextExecResult
    }

    override suspend fun stop(containerId: String) {
        stopCalls.add(containerId)
    }

    override suspend fun rm(containerId: String) {
        rmCalls.add(containerId)
    }

    override suspend fun listContainers(nameFilter: String): List<String> {
        listContainersCalls.add(nameFilter)
        listContainersException?.let { throw it }
        return listContainersResult
    }
}
