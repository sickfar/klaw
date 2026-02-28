# Test Guide

This is the test guide for klaw. It covers basic usage and configuration.

Klaw is an AI agent system that runs on Raspberry Pi 5. It supports multiple LLM providers and has a memory system for persistent context.

## Getting Started

To get started with klaw, you need to configure the engine and gateway services.

The engine handles LLM orchestration, memory, and tool execution. The gateway handles message transport from Telegram or Discord.

## Configuration

Configuration is done via YAML files in the config directory. The engine reads engine.json and the gateway reads gateway.json.

Each config file supports hot-reloading so changes take effect without restart.
