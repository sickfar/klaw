package io.github.klaw.cli.socket

class EngineNotRunningException : Exception(
    "Engine не запущен. Запустите: systemctl start klaw-engine",
)
