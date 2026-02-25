package io.github.klaw.common.paths

internal expect fun platformEnv(name: String): String?

internal expect fun platformHome(): String

data class KlawPathsSnapshot(
    val config: String,
    val data: String,
    val state: String,
    val cache: String,
    val workspace: String,
    val engineSocket: String,
    val gatewayBuffer: String,
    val klawDb: String,
    val schedulerDb: String,
    val conversations: String,
    val summaries: String,
    val coreMemory: String,
    val skills: String,
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
        engineSocket = "$state/engine.sock",
        gatewayBuffer = "$state/gateway-buffer.jsonl",
        klawDb = "$data/klaw.db",
        schedulerDb = "$data/scheduler.db",
        conversations = "$data/conversations",
        summaries = "$data/summaries",
        coreMemory = "$data/memory/core_memory.json",
        skills = "$data/skills",
    )
}

object KlawPaths {
    private val snapshot: KlawPathsSnapshot by lazy { buildPaths() }
    val config: String get() = snapshot.config
    val data: String get() = snapshot.data
    val state: String get() = snapshot.state
    val cache: String get() = snapshot.cache
    val workspace: String get() = snapshot.workspace
    val engineSocket: String get() = snapshot.engineSocket
    val gatewayBuffer: String get() = snapshot.gatewayBuffer
    val klawDb: String get() = snapshot.klawDb
    val schedulerDb: String get() = snapshot.schedulerDb
    val conversations: String get() = snapshot.conversations
    val summaries: String get() = snapshot.summaries
    val coreMemory: String get() = snapshot.coreMemory
    val skills: String get() = snapshot.skills
}
