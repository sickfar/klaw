package io.github.klaw.cli.socket

class EngineNotRunningException :
    Exception(
        "Engine is not running. Start it with: systemctl --user start klaw-engine",
    )
