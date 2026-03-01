package io.github.klaw.common.paths

internal expect fun platformEnv(name: String): String?

internal expect fun platformHome(): String

data class KlawPathsSnapshot(
    val config: String,
    val data: String,
    val state: String,
    val cache: String,
    val workspace: String,
    val enginePort: Int,
    val engineHost: String,
    val gatewayBuffer: String,
    val klawDb: String,
    val schedulerDb: String,
    val conversations: String,
    val summaries: String,
    val memory: String,
    val skills: String,
    val models: String,
    val logs: String,
    val deployConf: String,
    val hybridDockerCompose: String,
)

internal fun buildPaths(
    envProvider: (String) -> String? = ::platformEnv,
    homeProvider: () -> String = ::platformHome,
): KlawPathsSnapshot {
    val home = homeProvider()
    val configBase = envProvider("XDG_CONFIG_HOME") ?: "$home/.config"
    val dataBase = envProvider("XDG_DATA_HOME") ?: "$home/.local/share"
    val stateBase = envProvider("XDG_STATE_HOME") ?: "$home/.local/state"
    val cacheBase = envProvider("XDG_CACHE_HOME") ?: "$home/.cache"
    val workspace = envProvider("KLAW_WORKSPACE") ?: "$home/klaw-workspace"

    val config = "$configBase/klaw"
    val data = "$dataBase/klaw"
    val state = "$stateBase/klaw"
    val cache = "$cacheBase/klaw"

    return KlawPathsSnapshot(
        config = config,
        data = data,
        state = state,
        cache = cache,
        workspace = workspace,
        enginePort = envProvider("KLAW_ENGINE_PORT")?.toIntOrNull() ?: 7470,
        engineHost = envProvider("KLAW_ENGINE_HOST") ?: "127.0.0.1",
        gatewayBuffer = "$state/gateway-buffer.jsonl",
        klawDb = "$data/klaw.db",
        schedulerDb = "$data/scheduler.db",
        conversations = "$data/conversations",
        summaries = "$data/summaries",
        memory = "$data/memory",
        skills = "$data/skills",
        models = "$cache/models",
        logs = "$state/logs",
        deployConf = "$config/deploy.conf",
        hybridDockerCompose = "$config/docker-compose.json",
    )
}

object KlawPaths {
    private val snapshot: KlawPathsSnapshot by lazy { buildPaths() }
    val config: String get() = snapshot.config
    val data: String get() = snapshot.data
    val state: String get() = snapshot.state
    val cache: String get() = snapshot.cache
    val workspace: String get() = snapshot.workspace
    val enginePort: Int get() = snapshot.enginePort
    val engineHost: String get() = snapshot.engineHost
    val gatewayBuffer: String get() = snapshot.gatewayBuffer
    val klawDb: String get() = snapshot.klawDb
    val schedulerDb: String get() = snapshot.schedulerDb
    val conversations: String get() = snapshot.conversations
    val summaries: String get() = snapshot.summaries
    val memory: String get() = snapshot.memory
    val skills: String get() = snapshot.skills
    val models: String get() = snapshot.models
    val logs: String get() = snapshot.logs
    val deployConf: String get() = snapshot.deployConf
    val hybridDockerCompose: String get() = snapshot.hybridDockerCompose
}
