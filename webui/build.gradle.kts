plugins {
    id("java")
}

val npmInstall by tasks.registering(Exec::class) {
    workingDir = projectDir
    commandLine("npm", "ci")
    inputs.file("package-lock.json")
    outputs.dir("node_modules")
}

val npmBuild by tasks.registering(Exec::class) {
    dependsOn(npmInstall)
    workingDir = projectDir
    commandLine("npx", "nuxt", "generate")
    inputs.dir("app")
    inputs.file("nuxt.config.ts")
    inputs.file("package.json")
    outputs.dir(".output/public")
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("webui-resources"))
}

val copyWebuiResources by tasks.registering(Copy::class) {
    dependsOn(npmBuild)
    from(".output/public")
    into(layout.buildDirectory.dir("webui-resources/webui"))
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(copyWebuiResources)
}
