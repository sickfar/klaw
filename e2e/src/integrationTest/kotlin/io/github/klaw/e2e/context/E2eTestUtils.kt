package io.github.klaw.e2e.context

import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.awaitility.kotlin.withPollInterval
import java.time.Duration

fun awaitCondition(
    description: String,
    timeout: Duration,
    pollInterval: Duration = Duration.ofMillis(E2eConstants.POLL_INTERVAL_MS),
    condition: () -> Boolean,
) {
    await
        .withAlias(description)
        .atMost(timeout)
        .withPollInterval(pollInterval)
        .until { condition() }
}

object E2eConstants {
    const val POLL_INTERVAL_MS = 500L

    // ~1674 chars = 210 tokens via JTokkit BPE (cl100k_base)
    const val USER_MSG_PADDING =
        "alpha beta gamma delta epsilon zeta eta theta iota kappa " +
            "lambda mu nu xi omicron pi rho sigma tau upsilon phi chi psi omega " +
            "architecture design patterns software engineering distributed systems " +
            "microservices containers orchestration deployment monitoring observability " +
            "reliability scalability performance optimization caching strategies " +
            "database indexing query optimization schema design normalization " +
            "event sourcing command query responsibility segregation domain driven " +
            "design bounded contexts aggregates entities value objects repositories " +
            "services factories specifications anti corruption layers shared kernels " +
            "published languages open host services conformist patterns customer " +
            "supplier teams partnership big ball of mud legacy system integration " +
            "strangler fig pattern branch by abstraction feature toggles canary " +
            "releases blue green deployments rolling updates immutable infrastructure " +
            "infrastructure as code configuration management continuous integration " +
            "continuous delivery continuous deployment pipeline automation testing " +
            "strategies unit testing integration testing end to end testing contract " +
            "testing property based testing mutation testing fuzzing chaos engineering " +
            "resilience testing load testing stress testing performance testing " +
            "security testing penetration testing vulnerability assessment threat " +
            "modeling risk assessment compliance auditing governance frameworks " +
            "regulatory requirements data protection privacy policies access control " +
            "authentication authorization encryption key management certificate " +
            "management secret rotation audit logging monitoring alerting incident " +
            "response disaster recovery business continuity planning capacity planning"

    // ~771 chars = 107 tokens via JTokkit BPE (stored as STUB_COMPLETION_TOKENS)
    const val ASST_MSG_PADDING =
        "Here is a detailed analysis covering the key aspects " +
            "of the topic you raised. The architecture follows established patterns " +
            "with careful consideration of scalability and maintainability concerns. " +
            "Performance metrics indicate favorable outcomes across all measured " +
            "dimensions including throughput latency and resource utilization. " +
            "The implementation leverages modern frameworks and libraries to ensure " +
            "robust error handling graceful degradation and comprehensive monitoring. " +
            "Testing coverage exceeds baseline requirements with particular attention " +
            "to edge cases boundary conditions and failure scenarios. Documentation " +
            "has been updated to reflect the latest changes and deployment procedures " +
            "have been verified in staging environments before production rollout."
}
