package io.github.klaw.common.config.schema

// AUTO-GENERATED — do not edit manually. Run generateSchemas task to regenerate.

object GeneratedSchemas {
    val ENGINE: String =
        """
        {
            "${'$'}schema": "http://json-schema.org/draft-07/schema#",
            "type": "object",
            "properties": {
                "workspace": {
                    "type": "string",
                    "description": "Workspace directory path (overrides KLAW_WORKSPACE env var)"
                },
                "providers": {
                    "type": "object",
                    "additionalProperties": {
                        "type": "object",
                        "properties": {
                            "type": {
                                "type": "string",
                                "description": "Provider API type (resolved from built-in registry if omitted)"
                            },
                            "endpoint": {
                                "type": "string",
                                "description": "API endpoint URL (resolved from built-in registry if omitted)"
                            },
                            "apiKey": {
                                "type": "string",
                                "description": "API key for authentication"
                            }
                        },
                        "additionalProperties": false
                    },
                    "description": "LLM provider definitions keyed by provider name"
                },
                "models": {
                    "type": "object",
                    "additionalProperties": {
                        "type": "object",
                        "properties": {
                            "temperature": {
                                "type": "number",
                                "description": "Sampling temperature for generation"
                            }
                        },
                        "additionalProperties": false
                    },
                    "description": "Model override settings keyed by provider/modelId"
                },
                "routing": {
                    "type": "object",
                    "properties": {
                        "default": {
                            "type": "string",
                            "description": "Default model reference as provider/modelId"
                        },
                        "fallback": {
                            "type": "array",
                            "items": {
                                "type": "string"
                            },
                            "description": "Fallback model references tried in order if default fails"
                        },
                        "tasks": {
                            "type": "object",
                            "properties": {
                                "summarization": {
                                    "type": "string",
                                    "description": "Model reference for summarization tasks"
                                },
                                "subagent": {
                                    "type": "string",
                                    "description": "Model reference for subagent tasks"
                                },
                                "consolidation": {
                                    "type": "string",
                                    "description": "Model reference for consolidation tasks"
                                }
                            },
                            "required": [
                                "summarization",
                                "subagent"
                            ],
                            "additionalProperties": false
                        }
                    },
                    "required": [
                        "default",
                        "tasks"
                    ],
                    "additionalProperties": false
                },
                "memory": {
                    "type": "object",
                    "properties": {
                        "embedding": {
                            "type": "object",
                            "properties": {
                                "type": {
                                    "type": "string",
                                    "description": "Embedding backend type"
                                },
                                "model": {
                                    "type": "string",
                                    "description": "Embedding model name (ONNX directory name or Ollama model identifier)"
                                },
                                "ollamaFallbackModel": {
                                    "type": "string",
                                    "description": "Ollama model name used when falling back from ONNX (default: all-minilm:l6-v2)"
                                }
                            },
                            "additionalProperties": false
                        },
                        "chunking": {
                            "type": "object",
                            "properties": {
                                "size": {
                                    "type": "integer",
                                    "exclusiveMinimum": 0,
                                    "description": "Maximum chunk size in approximate tokens"
                                },
                                "overlap": {
                                    "type": "integer",
                                    "minimum": 0,
                                    "description": "Overlap between consecutive chunks in approximate tokens"
                                }
                            },
                            "additionalProperties": false
                        },
                        "search": {
                            "type": "object",
                            "properties": {
                                "topK": {
                                    "type": "integer",
                                    "exclusiveMinimum": 0,
                                    "description": "Number of top results to return from hybrid search"
                                },
                                "mmr": {
                                    "type": "object",
                                    "properties": {
                                        "enabled": {
                                            "type": "boolean",
                                            "description": "Enable MMR diversity reranking for memory search results"
                                        },
                                        "lambda": {
                                            "type": "number",
                                            "description": "Relevance vs diversity tradeoff (0.0=max diversity, 1.0=pure relevance)"
                                        }
                                    },
                                    "additionalProperties": false
                                },
                                "temporalDecay": {
                                    "type": "object",
                                    "properties": {
                                        "enabled": {
                                            "type": "boolean",
                                            "description": "Enable temporal decay — recent memories score higher than old ones"
                                        },
                                        "halfLifeDays": {
                                            "type": "integer",
                                            "description": "Half-life in days — after this many days, score is halved"
                                        }
                                    },
                                    "additionalProperties": false
                                }
                            },
                            "required": [
                                "topK"
                            ],
                            "additionalProperties": false
                        },
                        "injectMemoryMap": {
                            "type": "boolean",
                            "description": "Inject a Memory Map of database categories into the system prompt"
                        },
                        "mapMaxCategories": {
                            "type": "integer",
                            "description": "Maximum number of categories displayed in the memory map"
                        },
                        "autoRag": {
                            "type": "object",
                            "properties": {
                                "enabled": {
                                    "type": "boolean",
                                    "description": "Enable automatic RAG retrieval for conversation context"
                                },
                                "topK": {
                                    "type": "integer",
                                    "exclusiveMinimum": 0,
                                    "description": "Number of top relevant messages to retrieve"
                                },
                                "maxTokens": {
                                    "type": "integer",
                                    "exclusiveMinimum": 0,
                                    "description": "Maximum tokens of auto-RAG context to inject"
                                },
                                "relevanceThreshold": {
                                    "type": "number",
                                    "exclusiveMinimum": 0.0,
                                    "description": "Minimum relevance score threshold for including results"
                                },
                                "minMessageTokens": {
                                    "type": "integer",
                                    "exclusiveMinimum": 0,
                                    "description": "Minimum token count in a message to trigger auto-RAG"
                                }
                            },
                            "additionalProperties": false
                        },
                        "compaction": {
                            "type": "object",
                            "properties": {
                                "enabled": {
                                    "type": "boolean",
                                    "description": "Enable background summarization of old messages"
                                },
                                "compactionThresholdFraction": {
                                    "type": "number",
                                    "description": "Fraction of context budget that defines the compaction zone (0.0 to 1.0, exclusive)"
                                },
                                "summaryBudgetFraction": {
                                    "type": "number",
                                    "description": "Fraction of context budget allocated to summaries (0.0 to 1.0, exclusive)"
                                }
                            },
                            "additionalProperties": false
                        },
                        "consolidation": {
                            "type": "object",
                            "properties": {
                                "enabled": {
                                    "type": "boolean",
                                    "description": "Enable daily memory consolidation"
                                },
                                "cron": {
                                    "type": "string",
                                    "description": "Cron expression for consolidation schedule"
                                },
                                "model": {
                                    "type": "string",
                                    "description": "Model reference for consolidation LLM call (empty = use summarization model)"
                                },
                                "excludeChannels": {
                                    "type": "array",
                                    "items": {
                                        "type": "string"
                                    },
                                    "description": "Channels to exclude from consolidation"
                                },
                                "category": {
                                    "type": "string",
                                    "description": "Memory category hint for consolidation summaries"
                                },
                                "minMessages": {
                                    "type": "integer",
                                    "description": "Minimum number of messages required to trigger consolidation"
                                }
                            },
                            "additionalProperties": false
                        }
                    },
                    "required": [
                        "search"
                    ],
                    "additionalProperties": false
                },
                "context": {
                    "type": "object",
                    "properties": {
                        "tokenBudget": {
                            "type": "integer",
                            "exclusiveMinimum": 0,
                            "description": "Token budget for context window. Priority: config > model registry > 100000."
                        },
                        "subagentHistory": {
                            "type": "integer",
                            "exclusiveMinimum": 0,
                            "description": "Maximum number of history runs to include for subagents"
                        }
                    },
                    "required": [
                        "subagentHistory"
                    ],
                    "additionalProperties": false
                },
                "processing": {
                    "type": "object",
                    "properties": {
                        "debounceMs": {
                            "type": "integer",
                            "minimum": 0,
                            "description": "Delay in milliseconds before processing buffered messages"
                        },
                        "maxConcurrentLlm": {
                            "type": "integer",
                            "exclusiveMinimum": 0,
                            "description": "Maximum number of concurrent LLM API requests"
                        },
                        "maxToolCallRounds": {
                            "type": "integer",
                            "exclusiveMinimum": 0,
                            "description": "Maximum number of tool-call rounds per conversation turn"
                        },
                        "maxToolOutputChars": {
                            "type": "integer",
                            "exclusiveMinimum": 0,
                            "description": "Maximum characters in tool output before truncation"
                        },
                        "maxDebounceEntries": {
                            "type": "integer",
                            "exclusiveMinimum": 0,
                            "description": "Maximum messages in the debounce buffer before force-flush"
                        },
                        "subagentTimeoutMs": {
                            "type": "integer",
                            "description": "Subagent execution timeout in milliseconds (default 5 minutes)"
                        },
                        "streaming": {
                            "type": "object",
                            "properties": {
                                "enabled": {
                                    "type": "boolean",
                                    "description": "Enable streaming for interactive responses"
                                },
                                "throttleMs": {
                                    "type": "integer",
                                    "description": "Minimum interval between stream deltas sent to gateway (ms)"
                                }
                            },
                            "additionalProperties": false
                        }
                    },
                    "required": [
                        "debounceMs",
                        "maxConcurrentLlm",
                        "maxToolCallRounds"
                    ],
                    "additionalProperties": false
                },
                "skills": {
                    "type": "object",
                    "properties": {
                        "maxInlineSkills": {
                            "type": "integer",
                            "exclusiveMinimum": 0,
                            "description": "Maximum number of skills included inline in the system prompt"
                        }
                    },
                    "additionalProperties": false
                },
                "commands": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "name": {
                                "type": "string",
                                "description": "Slash command name without the leading /"
                            },
                            "description": {
                                "type": "string",
                                "description": "Human-readable description shown in command help"
                            }
                        },
                        "required": [
                            "name",
                            "description"
                        ],
                        "additionalProperties": false
                    }
                },
                "heartbeat": {
                    "type": "object",
                    "properties": {
                        "interval": {
                            "type": "string",
                            "description": "Heartbeat interval as ISO-8601 duration (e.g. PT1H, PT30M) or 'off' to disable"
                        },
                        "model": {
                            "type": "string",
                            "description": "Model reference used for heartbeat generation"
                        },
                        "injectInto": {
                            "type": "string",
                            "description": "Session name to inject heartbeat output into"
                        },
                        "channel": {
                            "type": "string",
                            "description": "Channel to deliver heartbeat messages to"
                        }
                    },
                    "additionalProperties": false
                },
                "files": {
                    "type": "object",
                    "properties": {
                        "maxFileSizeBytes": {
                            "type": "integer",
                            "exclusiveMinimum": 0,
                            "description": "Maximum file size in bytes that file tools can read"
                        }
                    },
                    "additionalProperties": false
                },
                "codeExecution": {
                    "type": "object",
                    "properties": {
                        "dockerImage": {
                            "type": "string",
                            "description": "Docker image used for code execution sandbox"
                        },
                        "timeout": {
                            "type": "integer",
                            "description": "Maximum execution timeout in seconds"
                        },
                        "allowNetwork": {
                            "type": "boolean",
                            "description": "Allow network access inside the sandbox container"
                        },
                        "maxMemory": {
                            "type": "string",
                            "description": "Maximum memory limit for the sandbox container"
                        },
                        "maxCpus": {
                            "type": "string",
                            "description": "Maximum CPU cores for the sandbox container"
                        },
                        "readOnlyRootfs": {
                            "type": "boolean",
                            "description": "Mount the container root filesystem as read-only"
                        },
                        "keepAlive": {
                            "type": "boolean",
                            "description": "Keep sandbox container alive between executions (reuses container for faster execution and state persistence)"
                        },
                        "keepAliveIdleTimeoutMin": {
                            "type": "integer",
                            "description": "Idle timeout in minutes before stopping a kept-alive container"
                        },
                        "keepAliveMaxExecutions": {
                            "type": "integer",
                            "description": "Maximum executions before recycling a kept-alive container"
                        },
                        "volumeMounts": {
                            "type": "array",
                            "items": {
                                "type": "string"
                            },
                            "description": "Additional Docker volume mounts for the sandbox"
                        },
                        "runAsUser": {
                            "type": "string",
                            "description": "User:group ID for sandbox container process (default: 1000:1000)"
                        }
                    },
                    "additionalProperties": false
                },
                "hostExecution": {
                    "type": "object",
                    "properties": {
                        "enabled": {
                            "type": "boolean",
                            "description": "Enable host command execution outside Docker sandbox"
                        },
                        "allowList": {
                            "type": "array",
                            "items": {
                                "type": "string"
                            },
                            "description": "Commands allowed to run without user confirmation"
                        },
                        "notifyList": {
                            "type": "array",
                            "items": {
                                "type": "string"
                            },
                            "description": "Commands that trigger a notification to the user"
                        },
                        "preValidation": {
                            "type": "object",
                            "properties": {
                                "enabled": {
                                    "type": "boolean",
                                    "description": "Enable LLM-based pre-validation of host commands"
                                },
                                "model": {
                                    "type": "string",
                                    "description": "Model reference used for pre-validation checks"
                                },
                                "riskThreshold": {
                                    "type": "integer",
                                    "minimum": 0,
                                    "description": "Risk score threshold above which commands are blocked"
                                },
                                "timeoutMs": {
                                    "type": "integer",
                                    "exclusiveMinimum": 0,
                                    "description": "Timeout in milliseconds for the pre-validation LLM call"
                                }
                            },
                            "additionalProperties": false
                        },
                        "askTimeoutMin": {
                            "type": "integer",
                            "minimum": 0,
                            "description": "Timeout in minutes for user confirmation prompts (0 = infinite, no timeout)"
                        }
                    },
                    "additionalProperties": false
                },
                "web": {
                    "type": "object",
                    "properties": {
                        "fetch": {
                            "type": "object",
                            "properties": {
                                "enabled": {
                                    "type": "boolean",
                                    "description": "Enable the web_fetch tool for fetching web page content"
                                },
                                "requestTimeoutMs": {
                                    "type": "integer",
                                    "description": "HTTP request timeout in milliseconds"
                                },
                                "maxResponseSizeBytes": {
                                    "type": "integer",
                                    "description": "Maximum response body size in bytes (default 1MB)"
                                },
                                "userAgent": {
                                    "type": "string",
                                    "description": "User-Agent header sent with requests"
                                }
                            },
                            "additionalProperties": false
                        },
                        "search": {
                            "type": "object",
                            "properties": {
                                "enabled": {
                                    "type": "boolean",
                                    "description": "Enable the web_search tool for searching the internet"
                                },
                                "provider": {
                                    "type": "string",
                                    "description": "Search provider type"
                                },
                                "apiKey": {
                                    "type": "string",
                                    "description": "API key for the search provider"
                                },
                                "maxResults": {
                                    "type": "integer",
                                    "description": "Maximum number of search results to return"
                                },
                                "requestTimeoutMs": {
                                    "type": "integer",
                                    "description": "HTTP request timeout in milliseconds"
                                },
                                "braveEndpoint": {
                                    "type": "string",
                                    "description": "Brave Search API endpoint URL (override for testing)"
                                },
                                "tavilyEndpoint": {
                                    "type": "string",
                                    "description": "Tavily Search API endpoint URL (override for testing)"
                                }
                            },
                            "additionalProperties": false
                        }
                    },
                    "additionalProperties": false
                },
                "documents": {
                    "type": "object",
                    "properties": {
                        "maxPdfSizeBytes": {
                            "type": "integer",
                            "description": "Maximum PDF file size in bytes for pdf_read (default 50MB)"
                        },
                        "maxPages": {
                            "type": "integer",
                            "description": "Maximum number of pages to extract in pdf_read (0 = unlimited)"
                        },
                        "maxOutputChars": {
                            "type": "integer",
                            "description": "Maximum output text length in characters before truncation"
                        },
                        "pdfFontSize": {
                            "type": "number",
                            "description": "Default font size for md_to_pdf output"
                        }
                    },
                    "additionalProperties": false
                },
                "vision": {
                    "type": "object",
                    "properties": {
                        "enabled": {
                            "type": "boolean",
                            "description": "Enable vision/image analysis capabilities"
                        },
                        "model": {
                            "type": "string",
                            "description": "Model reference for vision analysis (e.g. 'glm/glm-4.6v')"
                        },
                        "maxTokens": {
                            "type": "integer",
                            "description": "Maximum output tokens for vision model responses (null = use model registry default)"
                        },
                        "maxImageSizeBytes": {
                            "type": "integer",
                            "description": "Maximum image file size in bytes (default 10MB)"
                        },
                        "maxImagesPerMessage": {
                            "type": "integer",
                            "description": "Maximum images per message for inline vision"
                        },
                        "supportedFormats": {
                            "type": "array",
                            "items": {
                                "type": "string"
                            },
                            "description": "Supported image MIME types"
                        },
                        "attachmentsDirectory": {
                            "type": "string",
                            "description": "Directory where gateway stores image attachments (must match gateway attachments.directory)"
                        }
                    },
                    "additionalProperties": false
                },
                "httpRetry": {
                    "type": "object",
                    "properties": {
                        "maxRetries": {
                            "type": "integer",
                            "minimum": 0,
                            "description": "Maximum number of retry attempts on transient API errors"
                        },
                        "requestTimeoutMs": {
                            "type": "integer",
                            "exclusiveMinimum": 0,
                            "description": "HTTP request timeout in milliseconds"
                        },
                        "initialBackoffMs": {
                            "type": "integer",
                            "exclusiveMinimum": 0,
                            "description": "Initial backoff delay in milliseconds before first retry"
                        },
                        "backoffMultiplier": {
                            "type": "number",
                            "minimum": 1.0,
                            "description": "Multiplier applied to backoff delay after each retry"
                        }
                    },
                    "additionalProperties": false
                },
                "database": {
                    "type": "object",
                    "properties": {
                        "busyTimeoutMs": {
                            "type": "integer",
                            "description": "SQLite busy timeout in milliseconds"
                        },
                        "integrityCheckOnStartup": {
                            "type": "boolean",
                            "description": "Run PRAGMA integrity_check on startup"
                        },
                        "backupEnabled": {
                            "type": "boolean",
                            "description": "Enable automatic database backups"
                        },
                        "backupInterval": {
                            "type": "string",
                            "description": "Backup interval as ISO-8601 duration"
                        },
                        "backupMaxCount": {
                            "type": "integer",
                            "description": "Maximum number of backup files to keep"
                        }
                    },
                    "additionalProperties": false
                },
                "logging": {
                    "type": "object",
                    "properties": {
                        "subagentConversations": {
                            "type": "boolean",
                            "description": "Log subagent conversation JSONL files for debugging"
                        }
                    },
                    "additionalProperties": false
                },
                "docs": {
                    "type": "object",
                    "properties": {
                        "enabled": {
                            "type": "boolean",
                            "description": "Enable the documentation tool for workspace docs"
                        }
                    },
                    "additionalProperties": false
                }
            },
            "required": [
                "providers",
                "models",
                "routing",
                "memory",
                "context",
                "processing"
            ],
            "additionalProperties": false
        }
        """.trimIndent()

    val GATEWAY: String =
        """
        {
            "${'$'}schema": "http://json-schema.org/draft-07/schema#",
            "type": "object",
            "properties": {
                "channels": {
                    "type": "object",
                    "properties": {
                        "telegram": {
                            "type": "object",
                            "properties": {
                                "token": {
                                    "type": "string",
                                    "description": "Telegram Bot API token"
                                },
                                "allowedChats": {
                                    "type": "array",
                                    "items": {
                                        "type": "object",
                                        "properties": {
                                            "chatId": {
                                                "type": "string",
                                                "description": "Platform-specific chat identifier"
                                            },
                                            "allowedUserIds": {
                                                "type": "array",
                                                "items": {
                                                    "type": "string"
                                                },
                                                "description": "List of user IDs allowed to interact in this chat"
                                            }
                                        },
                                        "required": [
                                            "chatId"
                                        ],
                                        "additionalProperties": false
                                    }
                                },
                                "apiBaseUrl": {
                                    "type": "string",
                                    "description": "Custom API base URL (testing only)"
                                }
                            },
                            "required": [
                                "token"
                            ],
                            "additionalProperties": false
                        },
                        "discord": {
                            "type": "object",
                            "properties": {
                                "enabled": {
                                    "type": "boolean",
                                    "description": "Enable the Discord bot channel"
                                },
                                "token": {
                                    "type": "string",
                                    "description": "Discord bot token"
                                },
                                "allowedGuilds": {
                                    "type": "array",
                                    "items": {
                                        "type": "object",
                                        "properties": {
                                            "guildId": {
                                                "type": "string",
                                                "description": "Discord guild (server) ID"
                                            },
                                            "allowedChannelIds": {
                                                "type": "array",
                                                "items": {
                                                    "type": "string"
                                                },
                                                "description": "Allowed channel IDs within guild (empty = all channels)"
                                            },
                                            "allowedUserIds": {
                                                "type": "array",
                                                "items": {
                                                    "type": "string"
                                                },
                                                "description": "Allowed user IDs (empty = deny all)"
                                            }
                                        },
                                        "required": [
                                            "guildId"
                                        ],
                                        "additionalProperties": false
                                    }
                                },
                                "apiBaseUrl": {
                                    "type": "string",
                                    "description": "Custom API base URL (testing only)"
                                }
                            },
                            "additionalProperties": false
                        },
                        "localWs": {
                            "type": "object",
                            "properties": {
                                "enabled": {
                                    "type": "boolean",
                                    "description": "Enable the local WebSocket channel"
                                },
                                "port": {
                                    "type": "integer",
                                    "description": "TCP port for the local WebSocket channel"
                                }
                            },
                            "additionalProperties": false
                        }
                    },
                    "additionalProperties": false
                },
                "delivery": {
                    "type": "object",
                    "properties": {
                        "maxReconnectAttempts": {
                            "type": "integer",
                            "description": "Max consecutive reconnect failures before giving up (0 = unlimited)"
                        },
                        "drainBudgetSeconds": {
                            "type": "integer",
                            "description": "Max seconds for draining inbound buffer on reconnect (0 = unlimited)"
                        },
                        "channelDrainBudgetSeconds": {
                            "type": "integer",
                            "description": "Max seconds for draining per-channel buffer (0 = unlimited)"
                        }
                    },
                    "additionalProperties": false
                },
                "attachments": {
                    "type": "object",
                    "properties": {
                        "directory": {
                            "type": "string",
                            "description": "Directory for storing received image attachments (empty = disabled)"
                        }
                    },
                    "additionalProperties": false
                },
                "webui": {
                    "type": "object",
                    "properties": {
                        "enabled": {
                            "type": "boolean",
                            "description": "Enable the Web UI (REST API + SPA)"
                        },
                        "apiToken": {
                            "type": "string",
                            "description": "Bearer token for API authentication (supports ${'$'}{ENV_VAR} substitution, empty = no auth)"
                        }
                    },
                    "additionalProperties": false
                }
            },
            "required": [
                "channels"
            ],
            "additionalProperties": false
        }
        """.trimIndent()

    val COMPOSE: String =
        """
        {
            "${'$'}schema": "http://json-schema.org/draft-07/schema#",
            "type": "object",
            "properties": {
                "services": {
                    "type": "object",
                    "additionalProperties": {
                        "type": "object",
                        "properties": {
                            "image": {
                                "type": "string"
                            },
                            "restart": {
                                "type": "string"
                            },
                            "env_file": {
                                "type": "string"
                            },
                            "environment": {
                                "type": "object",
                                "additionalProperties": {
                                    "type": "string"
                                }
                            },
                            "depends_on": {
                                "type": "array",
                                "items": {
                                    "type": "string"
                                }
                            },
                            "volumes": {
                                "type": "array",
                                "items": {
                                    "type": "string"
                                }
                            },
                            "ports": {
                                "type": "array",
                                "items": {
                                    "type": "string"
                                }
                            }
                        },
                        "required": [
                            "image"
                        ],
                        "additionalProperties": false
                    }
                },
                "volumes": {
                    "type": "object",
                    "additionalProperties": {
                        "type": "object",
                        "properties": {
                            "name": {
                                "type": "string"
                            }
                        },
                        "additionalProperties": false
                    }
                }
            },
            "required": [
                "services"
            ],
            "additionalProperties": false
        }
        """.trimIndent()
}
