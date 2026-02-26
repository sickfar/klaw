package io.github.klaw.engine.scheduler

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.quartz.Job
import org.quartz.JobDataMap
import org.quartz.JobDetail
import org.quartz.JobKey
import org.quartz.impl.JobDetailImpl
import org.quartz.impl.jdbcjobstore.StdJDBCDelegate
import org.quartz.spi.ClassLoadHelper
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream
import java.sql.Connection
import java.sql.ResultSet

/**
 * Quartz JDBC delegate for SQLite that stores [JobDataMap] as JSON TEXT instead of
 * Java-serialized BLOB. Human-readable in SQLite, avoids class-version issues with
 * binary serialization.
 *
 * Adapted from the JsonbPostgresDelegate pattern (PostgreSQL JSONB variant).
 * Uses kotlinx.serialization.json instead of Jackson; ps.setString() instead of PGobject.
 *
 * Only job-detail data is overridden here; trigger-level JOB_DATA is always empty in Klaw
 * so the default BLOB path in [StdJDBCDelegate] handles it correctly.
 *
 * SQLite does not support FOR UPDATE row-level locking. The lock SQL is overridden via
 * org.quartz.jobStore.selectWithLockSQL in [QuartzKlawScheduler.buildProps].
 * With isClustered=false Quartz uses in-process SimpleSemaphore, so db locking is never used.
 */
class SQLiteDelegate : StdJDBCDelegate() {
    // ── Job-detail CRUD ──────────────────────────────────────────────────────────────────────

    @Suppress("MagicNumber")
    override fun insertJobDetail(
        conn: Connection,
        job: JobDetail,
    ): Int =
        conn.prepareStatement(rtp(INSERT_JOB_DETAIL)).use { ps ->
            ps.setString(1, job.key.name)
            ps.setString(2, job.key.group)
            ps.setString(3, job.description)
            ps.setString(4, job.jobClass.name)
            setBoolean(ps, 5, job.isDurable)
            setBoolean(ps, 6, job.isConcurrentExecutionDisallowed)
            setBoolean(ps, 7, job.isPersistJobDataAfterExecution)
            setBoolean(ps, 8, job.requestsRecovery())
            ps.setString(9, toJson(job.jobDataMap))
            ps.executeUpdate()
        }

    @Suppress("MagicNumber")
    override fun updateJobDetail(
        conn: Connection,
        job: JobDetail,
    ): Int =
        conn.prepareStatement(rtp(UPDATE_JOB_DETAIL)).use { ps ->
            ps.setString(1, job.description)
            ps.setString(2, job.jobClass.name)
            setBoolean(ps, 3, job.isDurable)
            setBoolean(ps, 4, job.isConcurrentExecutionDisallowed)
            setBoolean(ps, 5, job.isPersistJobDataAfterExecution)
            setBoolean(ps, 6, job.requestsRecovery())
            ps.setString(7, toJson(job.jobDataMap))
            ps.setString(8, job.key.name)
            ps.setString(9, job.key.group)
            ps.executeUpdate()
        }

    @Suppress("MagicNumber")
    override fun updateJobData(
        conn: Connection,
        job: JobDetail,
    ): Int =
        conn.prepareStatement(rtp(UPDATE_JOB_DATA)).use { ps ->
            ps.setString(1, toJson(job.jobDataMap))
            ps.setString(2, job.key.name)
            ps.setString(3, job.key.group)
            ps.executeUpdate()
        }

    @Suppress("NestedBlockDepth")
    override fun selectJobDetail(
        conn: Connection,
        jobKey: JobKey,
        loadHelper: ClassLoadHelper,
    ): JobDetail? =
        conn.prepareStatement(rtp(SELECT_JOB_DETAIL)).use { ps ->
            ps.setString(1, jobKey.name)
            ps.setString(2, jobKey.group)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                val json = rs.getString(COL_JOB_DATAMAP)
                JobDetailImpl().apply {
                    name = rs.getString(COL_JOB_NAME)
                    group = rs.getString(COL_JOB_GROUP)
                    description = rs.getString(COL_DESCRIPTION)
                    @Suppress("UNCHECKED_CAST")
                    jobClass =
                        loadHelper.loadClass(rs.getString(COL_JOB_CLASS), Job::class.java)
                            as Class<out Job>
                    setDurability(getBoolean(rs, COL_IS_DURABLE))
                    setRequestsRecovery(getBoolean(rs, COL_REQUESTS_RECOVERY))
                    // IS_NONCONCURRENT and IS_UPDATE_DATA are not restored from columns because
                    // JobDetailImpl derives isConcurrentExecutionDisallowed() and
                    // isPersistJobDataAfterExecution() dynamically from class annotations at runtime
                    // (no corresponding setters exist in Quartz 2.5.x JobDetailImpl).
                    // Setting jobClass above makes @DisallowConcurrentExecution effective immediately.
                    jobDataMap = if (json.isNullOrBlank()) JobDataMap() else fromJson(json)
                }
            }
        }

    // ── SQLite BLOB compatibility ────────────────────────────────────────────────────────────

    /**
     * SQLite JDBC does not implement [ResultSet.getBlob]. Override to use [ResultSet.getBytes]
     * which SQLite supports natively. Called by Quartz when reading trigger-level job data
     * (BLOB column in QRTZ_TRIGGERS). Trigger job data is always empty in Klaw, so this
     * typically returns null.
     */
    @Suppress("ReturnCount")
    override fun getObjectFromBlob(
        rs: ResultSet,
        colName: String,
    ): Any? {
        val bytes = rs.getBytes(colName) ?: return null
        if (bytes.isEmpty()) return null
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
    }

    // ── JSON helpers ─────────────────────────────────────────────────────────────────────────

    companion object {
        internal fun toJson(map: JobDataMap): String =
            buildJsonObject {
                map.forEach { (k, v) -> put(k.toString(), v?.toString()) }
            }.toString()

        internal fun fromJson(json: String): JobDataMap {
            val result = JobDataMap()
            Json.parseToJsonElement(json).jsonObject.forEach { (k, v) ->
                if (v !is JsonNull) result[k] = v.jsonPrimitive.content
            }
            return result
        }

    }
}
