import org.apache.tools.ant.filters.ReplaceTokens
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.diffplug.spotless")
    `java-test-fixtures`
}

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
kotlin { compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property") } }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.commonmark:commonmark:0.30.0")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.security:spring-security-test")
    testFixturesImplementation("org.testcontainers:testcontainers")
    testFixturesImplementation("com.microsoft.playwright:playwright:1.58.0")
    testFixturesImplementation("tools.jackson.core:jackson-databind")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val integrationTest by sourceSets.creating
val e2eTest by sourceSets.creating
val oidcSmokeTest by sourceSets.creating
listOf(integrationTest, e2eTest, oidcSmokeTest).forEach {
    it.compileClasspath += sourceSets.testFixtures.get().output
    it.runtimeClasspath += sourceSets.testFixtures.get().output
    it.compileClasspath += sourceSets.main.get().output
    it.runtimeClasspath += sourceSets.main.get().output
    configurations[it.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
    configurations[it.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
}

dependencies {
    add(integrationTest.implementationConfigurationName, "org.springframework.boot:spring-boot-starter-test")
    add(integrationTest.implementationConfigurationName, "org.testcontainers:testcontainers-postgresql")
    add(integrationTest.implementationConfigurationName, "com.microsoft.playwright:playwright:1.58.0")
    add(integrationTest.implementationConfigurationName, "org.testcontainers:testcontainers")
    add(e2eTest.implementationConfigurationName, "org.testcontainers:testcontainers")
    add(e2eTest.implementationConfigurationName, "org.testcontainers:testcontainers-postgresql")
    add(e2eTest.implementationConfigurationName, "com.microsoft.playwright:playwright:1.58.0")
    add(oidcSmokeTest.implementationConfigurationName, "org.testcontainers:testcontainers")
    add(oidcSmokeTest.implementationConfigurationName, "com.microsoft.playwright:playwright:1.58.0")
}

spotless {
    kotlin {
        target("src/**/*.kt")
        targetExclude("src/generated/**")
        ktlint("1.7.1")
    }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

tasks.processResources {
    dependsOn(rootProject.tasks.named("frontendBuild"))
    from(rootProject.layout.projectDirectory.dir("frontend/dist/aso/browser")) { into("static") }
    filesMatching("application.yaml") {
        filter<ReplaceTokens>("tokens" to mapOf("projectVersion" to project.version.toString()))
    }
}

val databaseImage = rootProject.file("gradle/database-image.txt").readText().trim()

tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Verifies application startup and migrations against isolated PostgreSQL. Requires Docker."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    systemProperty("aso.test.database-image", databaseImage)
    shouldRunAfter(tasks.test)
}

val bootJar =
    tasks.named<BootJar>("bootJar") {
        archiveFileName = "aso.jar"
    }

val playwrightInstall =
    tasks.register<JavaExec>("playwrightInstall") {
        group = "verification"
        description = "Downloads the Chromium browser used by the packaged application smoke test."
        classpath = e2eTest.runtimeClasspath
        mainClass = "com.microsoft.playwright.CLI"
        environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
        args(if (project.hasProperty("playwrightWithDeps")) listOf("install", "--with-deps", "chromium") else listOf("install", "chromium"))
    }

tasks.register<Test>("e2eTest") {
    group = "verification"
    description = "Runs Chromium against the executable jar and an isolated PostgreSQL database. Requires Docker."
    dependsOn(bootJar, playwrightInstall)
    inputs.file(bootJar.flatMap { it.archiveFile })
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
    testClassesDirs = e2eTest.output.classesDirs
    classpath = e2eTest.runtimeClasspath
    systemProperty("aso.test.database-image", databaseImage)
    systemProperty(
        "aso.test.jar",
        bootJar
            .get()
            .archiveFile
            .get()
            .asFile.absolutePath,
    )
    systemProperty(
        "aso.test.artifacts",
        layout.buildDirectory
            .dir("e2e")
            .get()
            .asFile.absolutePath,
    )
    shouldRunAfter(tasks.test)
}

tasks.bootBuildImage {
    imageName = "aso:${project.version}"
    environment.put("BP_JVM_VERSION", "25")
}

val keycloakImage = rootProject.file("gradle/keycloak-image.txt").readText().trim()
tasks.register<Test>("oidcSmokeTest") {
    group = "verification"
    description = "Verifies the isolated Keycloak fixture via browser code/PKCE login. Requires Docker."
    // An explicit smoke run must exercise Docker and the real provider, even after a prior success.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    dependsOn(playwrightInstall)
    testClassesDirs = oidcSmokeTest.output.classesDirs
    classpath = oidcSmokeTest.runtimeClasspath
    inputs.file(rootProject.file("compose.yaml"))
    outputs.dir(layout.buildDirectory.dir("oidc"))
    inputs.file(rootProject.file("gradle/keycloak-image.txt"))
    inputs.file(rootProject.file("dev/keycloak/aso-dev-realm.json"))
    systemProperty("aso.test.keycloak-image", keycloakImage)
    systemProperty("aso.test.realm", rootProject.file("dev/keycloak/aso-dev-realm.json").absolutePath)
    systemProperty("aso.test.compose", rootProject.file("compose.yaml").absolutePath)
    systemProperty(
        "aso.test.oidc-artifacts",
        layout.buildDirectory
            .dir("oidc")
            .get()
            .asFile.absolutePath,
    )
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
}

// Parse the deliberately selected identity, Q&A and lifecycle DDL offline; never inspect a developer database.
val jooqCodegen by configurations.creating
configurations[jooqCodegen.name].extendsFrom(configurations.implementation.get())
dependencies {
    add(jooqCodegen.name, "org.jooq:jooq-codegen")
    add(jooqCodegen.name, "org.jooq:jooq-meta-extensions")
}
tasks.register<JavaExec>("generateJooq") {
    group = "build"
    description = "Regenerate checked-in jOOQ identity, Q&A and lifecycle sources from Flyway DDL without a database."
    classpath = jooqCodegen
    mainClass = "org.jooq.codegen.GenerationTool"
    workingDir = projectDir
    args("src/main/jooq/generator.xml")
}
listOf("integrationTest", "e2eTest").forEach { name ->
    tasks.named<Test>(name) {
        systemProperty("aso.test.keycloak-image", keycloakImage)
        systemProperty("aso.test.realm", rootProject.file("dev/keycloak/aso-dev-realm.json").absolutePath)
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
}

kotlin.sourceSets.named("main") { kotlin.srcDir("src/generated/kotlin") }

tasks.named<Test>("integrationTest") {
    dependsOn(playwrightInstall)
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
}
