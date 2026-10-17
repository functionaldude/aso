import com.github.gradle.node.npm.task.NpmTask

plugins {
    base
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.spring") version "2.3.21" apply false
    id("org.springframework.boot") version "4.1.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.diffplug.spotless") version "8.0.0"
    id("com.github.node-gradle.node") version "7.1.0"
}

allprojects {
    group = "com.functionaldude"
    version = "0.0.1-SNAPSHOT"
    repositories { mavenCentral() }
}

node {
    version = "24.22.0"
    download = true
    nodeProjectDir = layout.projectDirectory.dir("frontend")
    workDir = layout.projectDirectory.dir(".gradle/nodejs")
    npmInstallCommand = "ci"
}

spotless {
    kotlinGradle {
        target("*.gradle.kts", "backend/*.gradle.kts")
        ktlint("1.7.1")
    }
}

fun npmTask(
    name: String,
    script: String,
) = tasks.register<NpmTask>(name) {
    dependsOn(tasks.npmInstall)
    args = listOf("run", script)
    group = "frontend"
}

val frontendBuild = npmTask("frontendBuild", "build")
frontendBuild.configure {
    inputs.files(
        fileTree("frontend") {
            exclude("node_modules/**", "dist/**", ".angular/**", "coverage/**")
        },
    )
    outputs.dir("frontend/dist/aso/browser")
}
val frontendTest = npmTask("frontendTest", "test:ci")
val frontendLint = npmTask("frontendLint", "lint")
val frontendFormatCheck = npmTask("frontendFormatCheck", "format:check")
npmTask("frontendFormat", "format")
npmTask("frontendStart", "start")

tasks.check {
    dependsOn(":backend:check", frontendTest, frontendLint, frontendFormatCheck)
}
tasks.register("bootJar") {
    group = "build"
    dependsOn(":backend:bootJar")
}
tasks.register("bootRun") {
    group = "application"
    dependsOn(":backend:bootRun")
}
tasks.register("bootBuildImage") {
    group = "build"
    dependsOn(":backend:bootBuildImage")
}
tasks.register("integrationTest") {
    group = "verification"
    dependsOn(":backend:integrationTest")
}
tasks.register("oidcSmokeTest") {
    group = "verification"
    dependsOn(":backend:oidcSmokeTest")
}
tasks.register("e2eTest") {
    group = "verification"
    dependsOn(":backend:e2eTest")
}
tasks.register("playwrightInstall") {
    group = "verification"
    dependsOn(":backend:playwrightInstall")
}
tasks.clean {
    delete("frontend/dist", "frontend/coverage", "frontend/.angular")
    dependsOn(":backend:clean")
}

tasks.register("generateJooq") {
    group = "build"
    dependsOn(":backend:generateJooq")
}
listOf("searchRelevance", "searchLatency").forEach { taskName ->
    tasks.register(taskName) {
        group = "verification"
        dependsOn(":backend:$taskName")
    }
}
