package io.github.klaw.e2e.database

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.DbInspector
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.sql.DriverManager

/**
 * E2E test verifying the engine's SQLite database setup in Docker containers.
 *
 * Verifiable from host:
 * - WAL journal mode — persisted in DB file header, survives new connections
 * - Database schema created correctly — tables queryable via DbInspector
 * - Integrity check — DbInspector can read without errors
 *
 * Per-connection PRAGMAs (busy_timeout, synchronous, foreign_keys, temp_store)
 * are NOT verifiable from host — they reset on each new connection.
 * Those are covered by PersistentConnectionManagerTest (unit tests).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabasePragmaE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()

        val workspaceDir = WorkspaceGenerator.createWorkspace()
        val wiremockBaseUrl = "http://host.testcontainers.internal:${wireMock.port}"

        containers =
            KlawContainers(
                wireMockPort = wireMock.port,
                engineJson =
                    ConfigGenerator.engineJson(
                        wiremockBaseUrl = wiremockBaseUrl,
                        summarizationEnabled = false,
                        autoRagEnabled = false,
                    ),
                gatewayJson = ConfigGenerator.gatewayJson(),
                workspaceDir = workspaceDir,
            )
        containers.start()
    }

    @AfterAll
    fun stopInfrastructure() {
        containers.stop()
        wireMock.stop()
    }

    @Test
    fun `engine database uses WAL journal mode`() {
        val dbFile = File(containers.engineDataPath, "klaw.db")
        assertTrue(dbFile.exists(), "klaw.db should exist")

        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA journal_mode").use { rs ->
                    assertTrue(rs.next(), "PRAGMA journal_mode should return a result")
                    assertEquals("wal", rs.getString(1))
                }
            }
        }
    }

    @Test
    fun `engine database has correct schema tables`() {
        val dbFile = File(containers.engineDataPath, "klaw.db")
        assertTrue(dbFile.exists(), "klaw.db should exist")

        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                val tables = mutableListOf<String>()
                stmt
                    .executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name",
                    ).use { rs ->
                        while (rs.next()) {
                            tables.add(rs.getString(1))
                        }
                    }
                assertTrue(tables.contains("messages"), "messages table should exist")
                assertTrue(tables.contains("sessions"), "sessions table should exist")
                assertTrue(tables.contains("memory_chunks"), "memory_chunks table should exist")
                assertTrue(tables.contains("summaries"), "summaries table should exist")
            }
        }
    }

    @Test
    fun `engine database passes integrity check from host`() {
        val dbFile = File(containers.engineDataPath, "klaw.db")
        assertTrue(dbFile.exists(), "klaw.db should exist")

        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA integrity_check").use { rs ->
                    assertTrue(rs.next(), "integrity_check should return a result")
                    assertEquals("ok", rs.getString(1))
                }
            }
        }
    }

    @Test
    fun `engine database is queryable via DbInspector`() {
        val dbFile = File(containers.engineDataPath, "klaw.db")
        assertTrue(dbFile.exists(), "klaw.db should exist")

        DbInspector(dbFile).use { inspector ->
            val messages = inspector.getMessages("nonexistent")
            assertTrue(messages.isEmpty())
        }
    }
}
