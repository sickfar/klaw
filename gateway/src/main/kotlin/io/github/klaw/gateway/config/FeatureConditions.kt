package io.github.klaw.gateway.config

import io.github.klaw.common.config.GatewayConfig
import io.micronaut.context.condition.Condition
import io.micronaut.context.condition.ConditionContext

class WsEnabledCondition : Condition {
    override fun matches(context: ConditionContext<*>): Boolean {
        val config = context.beanContext.findBean(GatewayConfig::class.java).orElse(null)
        return config?.channels?.localWs?.enabled == true
    }
}

class WebuiEnabledCondition : Condition {
    override fun matches(context: ConditionContext<*>): Boolean {
        val config = context.beanContext.findBean(GatewayConfig::class.java).orElse(null)
        return config?.webui?.enabled == true
    }
}
