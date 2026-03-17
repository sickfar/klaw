package io.github.klaw.common.config.schema

// AUTO-GENERATED — do not edit manually. Run generateSchemas task to regenerate.

object GeneratedSchemas {
    val ENGINE: String =
        """
        {
            "${'$'}schema": "http://json-schema.org/draft-07/schema#",
            "type": "object",
            "properties": {
                "providers": {
                    "type": "object",
                    "additionalProperties": {
                        "type": "object",
                        "properties": {
                            "type": {
                                "type": "string"
                            },
                            "endpoint": {
                                "type": "string"
                            },
                            "apiKey": {
                                "type": "string"
                            }
                        },
                        "required": [
                            "type",
                            "endpoint"
                        ],
                        "additionalProperties": false
                    }
                },
                "models": {
                    "type": "object",
                    "additionalProperties": {
                        "type": "object",
                        "properties": {
                            "maxTokens": {
                                "type": "integer"
                            },
                            "contextBudget": {
                                "type": "integer"
                            },
                            "temperature": {
                                "type": "number"
                            }
                        },
                        "additionalProperties": false
                    }
                },
                "routing": {
                    "type": "object",
                    "properties": {
                        "default": {
                            "type": "string"
                        },
                        "fallback": {
                            "type": "array",
                            "items": {
                                "type": "string"
                            }
                        },
                        "tasks": {
                            "type": "object",
                            "properties": {
                                "summarization": {
                                    "type": "string"
                                },
                                "subagent": {
                                    "type": "string"
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
                                    "type": "string"
                                },
                                "model": {
                                    "type": "string"
                                }
                            },
                            "required": [
                                "type",
                                "model"
                            ],
                            "additionalProperties": false
                        },
                        "chunking": {
                            "type": "object",
                            "properties": {
                                "size": {
                                    "type": "integer",
                                    "exclusiveMinimum": 0
                                },
                                "overlap": {
                                    "type": "integer",
                                    "minimum": 0
                                }
                            },
                            "required": [
                                "size",
                                "overlap"
                            ],
                            "additionalProperties": false
                        },
                        "search": {
                            "type": "object",
                            "properties": {
                                "topK": {
                                    "type": "integer",
                                    "exclusiveMinimum": 0
                                }
                            },
                            "required": [
                                "topK"
                            ],
                            "additionalProperties": false
                        }
                    },
                    "required": [
                        "embedding",
                        "chunking",
                        "search"
                    ],
                    "additionalProperties": false
                },
                "context": {
                    "type": "object",
                    "properties": {
                        "defaultBudgetTokens": {
                            "type": "integer",
                            "exclusiveMinimum": 0
                        },
                        "subagentHistory": {
                            "type": "integer",
                            "exclusiveMinimum": 0
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
                            "minimum": 0
                        },
                        "maxConcurrentLlm": {
                            "type": "integer",
                            "exclusiveMinimum": 0
                        },
                        "maxToolCallRounds": {
                            "type": "integer",
                            "exclusiveMinimum": 0
                        },
                        "maxToolOutputChars": {
                            "type": "integer",
                            "exclusiveMinimum": 0
                        },
                        "maxDebounceEntries": {
                            "type": "integer",
                            "exclusiveMinimum": 0
                        }
                    },
                    "required": [
                        "debounceMs",
                        "maxConcurrentLlm",
                        "maxToolCallRounds"
                    ],
                    "additionalProperties": false
                },
                "llm": {
                    "type": "object",
                    "properties": {
                        "maxRetries": {
                            "type": "integer",
                            "minimum": 0
                        },
                        "requestTimeoutMs": {
                            "type": "integer",
                            "exclusiveMinimum": 0
                        },
                        "initialBackoffMs": {
                            "type": "integer",
                            "exclusiveMinimum": 0
                        },
                        "backoffMultiplier": {
                            "type": "number",
                            "minimum": 1.0
                        }
                    },
                    "additionalProperties": false
                },
                "logging": {
                    "type": "object",
                    "properties": {
                        "subagentConversations": {
                            "type": "boolean"
                        }
                    },
                    "additionalProperties": false
                },
                "codeExecution": {
                    "type": "object",
                    "properties": {
                        "dockerImage": {
                            "type": "string"
                        },
                        "timeout": {
                            "type": "integer"
                        },
                        "allowNetwork": {
                            "type": "boolean"
                        },
                        "maxMemory": {
                            "type": "string"
                        },
                        "maxCpus": {
                            "type": "string"
                        },
                        "readOnlyRootfs": {
                            "type": "boolean"
                        },
                        "keepAlive": {
                            "type": "boolean"
                        },
                        "keepAliveIdleTimeoutMin": {
                            "type": "integer"
                        },
                        "keepAliveMaxExecutions": {
                            "type": "integer"
                        },
                        "volumeMounts": {
                            "type": "array",
                            "items": {
                                "type": "string"
                            }
                        }
                    },
                    "additionalProperties": false
                },
                "files": {
                    "type": "object",
                    "properties": {
                        "maxFileSizeBytes": {
                            "type": "integer",
                            "exclusiveMinimum": 0
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
                                "type": "string"
                            },
                            "description": {
                                "type": "string"
                            }
                        },
                        "required": [
                            "name",
                            "description"
                        ],
                        "additionalProperties": false
                    }
                },
                "compatibility": {
                    "type": "object",
                    "properties": {
                        "openclaw": {
                            "type": "object",
                            "properties": {
                                "enabled": {
                                    "type": "boolean"
                                },
                                "sync": {
                                    "type": "object",
                                    "properties": {
                                        "memoryMd": {
                                            "type": "boolean"
                                        },
                                        "dailyLogs": {
                                            "type": "boolean"
                                        },
                                        "userMd": {
                                            "type": "boolean"
                                        }
                                    },
                                    "additionalProperties": false
                                }
                            },
                            "additionalProperties": false
                        }
                    },
                    "additionalProperties": false
                },
                "autoRag": {
                    "type": "object",
                    "properties": {
                        "enabled": {
                            "type": "boolean"
                        },
                        "topK": {
                            "type": "integer",
                            "exclusiveMinimum": 0
                        },
                        "maxTokens": {
                            "type": "integer",
                            "exclusiveMinimum": 0
                        },
                        "relevanceThreshold": {
                            "type": "number",
                            "exclusiveMinimum": 0.0
                        },
                        "minMessageTokens": {
                            "type": "integer",
                            "exclusiveMinimum": 0
                        }
                    },
                    "additionalProperties": false
                },
                "docs": {
                    "type": "object",
                    "properties": {
                        "enabled": {
                            "type": "boolean"
                        }
                    },
                    "additionalProperties": false
                },
                "skills": {
                    "type": "object",
                    "properties": {
                        "maxInlineSkills": {
                            "type": "integer",
                            "exclusiveMinimum": 0
                        }
                    },
                    "additionalProperties": false
                },
                "hostExecution": {
                    "type": "object",
                    "properties": {
                        "enabled": {
                            "type": "boolean"
                        },
                        "allowList": {
                            "type": "array",
                            "items": {
                                "type": "string"
                            }
                        },
                        "notifyList": {
                            "type": "array",
                            "items": {
                                "type": "string"
                            }
                        },
                        "preValidation": {
                            "type": "object",
                            "properties": {
                                "enabled": {
                                    "type": "boolean"
                                },
                                "model": {
                                    "type": "string"
                                },
                                "riskThreshold": {
                                    "type": "integer",
                                    "minimum": 0
                                },
                                "timeoutMs": {
                                    "type": "integer",
                                    "exclusiveMinimum": 0
                                }
                            },
                            "additionalProperties": false
                        },
                        "askTimeoutMin": {
                            "type": "integer",
                            "exclusiveMinimum": 0
                        }
                    },
                    "additionalProperties": false
                },
                "heartbeat": {
                    "type": "object",
                    "properties": {
                        "interval": {
                            "type": "string"
                        },
                        "model": {
                            "type": "string"
                        },
                        "injectInto": {
                            "type": "string"
                        },
                        "channel": {
                            "type": "string"
                        }
                    },
                    "additionalProperties": false
                },
                "summarization": {
                    "type": "object",
                    "properties": {
                        "enabled": {
                            "type": "boolean"
                        },
                        "compactionThresholdFraction": {
                            "type": "number"
                        },
                        "summaryBudgetFraction": {
                            "type": "number"
                        }
                    },
                    "additionalProperties": false
                },
                "database": {
                    "type": "object",
                    "properties": {
                        "busyTimeoutMs": {
                            "type": "integer"
                        },
                        "integrityCheckOnStartup": {
                            "type": "boolean"
                        },
                        "backupEnabled": {
                            "type": "boolean"
                        },
                        "backupInterval": {
                            "type": "string"
                        },
                        "backupMaxCount": {
                            "type": "integer"
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
                                    "type": "string"
                                },
                                "allowedChats": {
                                    "type": "array",
                                    "items": {
                                        "type": "object",
                                        "properties": {
                                            "chatId": {
                                                "type": "string"
                                            },
                                            "allowedUserIds": {
                                                "type": "array",
                                                "items": {
                                                    "type": "string"
                                                }
                                            }
                                        },
                                        "required": [
                                            "chatId"
                                        ],
                                        "additionalProperties": false
                                    }
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
                                    "type": "boolean"
                                },
                                "token": {
                                    "type": "string"
                                }
                            },
                            "additionalProperties": false
                        },
                        "localWs": {
                            "type": "object",
                            "properties": {
                                "enabled": {
                                    "type": "boolean"
                                },
                                "port": {
                                    "type": "integer"
                                }
                            },
                            "additionalProperties": false
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
                                "type": "string"
                            },
                            "description": {
                                "type": "string"
                            }
                        },
                        "required": [
                            "name",
                            "description"
                        ],
                        "additionalProperties": false
                    }
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
