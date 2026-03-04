package io.github.klaw.common.config

@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class ConfigDoc(
    val description: String,
    val possibleValues: Array<String> = [],
    val sensitive: Boolean = false,
)
