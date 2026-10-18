package com.functionaldude.aso

import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.ContainerLaunchException
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageSmokeTest {
    private val image = System.getProperty("aso.test.image")
    private val artifacts = Path.of(System.getProperty("aso.test.image-artifacts"))
    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val publicUrl = "https://aso-image.example.invalid"
    private val port = 18080

    @Test
    fun `container template covers every application environment variable`() {
        val configuration = Files.readString(Path.of(System.getProperty("aso.test.runtime-config")))
        val template = Files.readString(Path.of(System.getProperty("aso.test.container-env")))
        val configured = Regex("\\$\\{(ASO_[A-Z_]+)").findAll(configuration).map { it.groupValues[1] }.toSet()
        val documented = Regex("(?m)^#?\\s*(ASO_[A-Z_]+)=").findAll(template).map { it.groupValues[1] }.toSet()
        assertEquals(configured, documented, "Keep the container template aligned with the runtime configuration")
        assertFalse(template.contains("SPRING_PROFILES_ACTIVE=development"))
    }

    @Test
    fun `image accepts runtime configuration serves the packaged UI and fails closed without identity`() {
        Files.createDirectories(artifacts)
        val metadata =
            DockerClientFactory
                .instance()
                .client()
                .inspectImageCmd(image)
                .exec()
        val config = requireNotNull(metadata.config)
        assertTrue(config.user.orEmpty() !in setOf("", "0", "root"), "The application must run as a non-root user")
        assertEquals("https://github.com/functionaldude/aso", config.labels?.get("org.opencontainers.image.source"))

        Network.newNetwork().use { network ->
            PostgreSQLContainer(
                DockerImageName.parse(System.getProperty("aso.test.database-image")).asCompatibleSubstituteFor("postgres"),
            ).withNetwork(network).withNetworkAliases("image-database").withPassword("image-smoke-db-local-only").use { database ->
                database.start()
                keycloak(network).use { keycloak ->
                    keycloak.start()
                    val environment =
                        mapOf(
                            "ASO_DB_URL" to "jdbc:postgresql://image-database:5432/${database.databaseName}",
                            "ASO_DB_USER" to database.username,
                            "ASO_DB_PASSWORD" to database.password,
                            "ASO_PORT" to port.toString(),
                            "ASO_OIDC_ISSUER" to "http://127.0.0.1:8080/realms/aso-dev",
                            "ASO_OIDC_CLIENT_ID" to "aso",
                            "ASO_PUBLIC_URL" to publicUrl,
                            "ASO_MCP_AUDIENCE" to "$publicUrl/mcp",
                            "ASO_OIDC_GROUPS_CLAIM" to "groups",
                            "ASO_OIDC_MEMBER_GROUP" to "aso-members",
                            "ASO_OIDC_ADMIN_GROUP" to "aso-admins",
                            "ASO_CODEX_CALLBACK_URL" to "http://127.0.0.1:8766/callback",
                            "ASO_MCP_JAM_CALLBACK_URL" to "http://localhost:6274/oauth/callback",
                            "ASO_EMBEDDING_ENABLED" to "false",
                            "ASO_EMBEDDING_API_KEY" to "image-smoke-embedding-local-only",
                            "ASO_RATE_LIMIT_REQUESTS" to "20",
                            "ASO_RATE_LIMIT_WRITES" to "10",
                        )
                    // ASO_ADDRESS is deliberately absent: published-port access requires the image's default.
                    val base = "http://${keycloak.host}:${keycloak.getMappedPort(port)}"
                    val logs = StringBuffer()
                    application(keycloak, environment, logs).use { application ->
                        try {
                            application.start()
                            assertEquals("UP", json.readTree(get(base, "/actuator/health").body())["status"].asString())
                            val info = get(base, "/api/v1/info")
                            assertEquals(200, info.statusCode())
                            assertEquals("aso", json.readTree(info.body())["name"].asString())
                            listOf("/", "/about", "/agent-setup", "/questions", "/moderation").forEach { path ->
                                val response = get(base, path)
                                assertEquals(200, response.statusCode(), path)
                                assertTrue(response.body().contains("<app-root"), "Packaged Angular shell at $path")
                            }
                            assertEquals(401, get(base, "/api/v1/identity").statusCode())
                            assertEquals(401, get(base, "/api/v1/questions").statusCode())
                            val setup = get(base, "/api/v1/agent-setup")
                            assertEquals(200, setup.statusCode())
                            val configuration = json.readTree(setup.body())
                            assertEquals("$publicUrl/mcp", configuration["mcpUrl"].asString())
                            assertEquals(environment["ASO_OIDC_ISSUER"], configuration["issuer"].asString())
                            assertEquals("aso", configuration["clientId"].asString())
                            assertEquals(environment["ASO_CODEX_CALLBACK_URL"], configuration["codexCallbackUrl"].asString())
                            assertEquals(environment["ASO_MCP_JAM_CALLBACK_URL"], configuration["mcpJamCallbackUrl"].asString())
                            listOf("ASO_DB_PASSWORD", "ASO_EMBEDDING_API_KEY").forEach {
                                assertFalse(setup.body().contains(environment.getValue(it)), "$it must remain private")
                            }
                            val metadataResponse = get(base, "/.well-known/oauth-protected-resource/mcp")
                            assertEquals(200, metadataResponse.statusCode())
                            assertEquals("$publicUrl/mcp", json.readTree(metadataResponse.body())["resource"].asString())
                        } finally {
                            Files.writeString(artifacts.resolve("application.log"), logs.toString())
                        }
                    }
                    listOf(
                        "ASO_OIDC_ISSUER",
                        "ASO_OIDC_CLIENT_ID",
                        "ASO_PUBLIC_URL",
                        "ASO_MCP_AUDIENCE",
                        "ASO_OIDC_GROUPS_CLAIM",
                        "ASO_OIDC_MEMBER_GROUP",
                        "ASO_OIDC_ADMIN_GROUP",
                    ).forEach { missing ->
                        val rejectedLogs = StringBuffer()
                        application(keycloak, environment - missing, rejectedLogs).use { rejected ->
                            rejected.withStartupCheckStrategy(OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)))
                            try {
                                assertFailsWith<ContainerLaunchException>("Startup must fail without $missing") { rejected.start() }
                                assertTrue(
                                    rejectedLogs.contains("All ASO identity settings must be explicitly configured"),
                                    "Startup must reject missing $missing",
                                )
                            } finally {
                                Files.writeString(artifacts.resolve("missing-$missing.log"), rejectedLogs.toString())
                            }
                        }
                    }
                }
            }
        }
    }

    private fun keycloak(network: Network): SmokeContainer =
        SmokeContainer(DockerImageName.parse(System.getProperty("aso.test.keycloak-image")))
            .withNetwork(network)
            // The app shares this network namespace; publish its overridden port here.
            .withExposedPorts(8080, port)
            .withEnv("KC_DB", "dev-mem")
            .withEnv("ASO_WEB_CALLBACK", "$publicUrl/login/oauth2/code/oidc")
            .withEnv("ASO_WEB_LOGOUT", "$publicUrl/?signedOut")
            .withCopyFileToContainer(
                MountableFile.forHostPath(System.getProperty("aso.test.realm")),
                "/opt/keycloak/data/import/aso-dev-realm.json",
            ).withCommand("start-dev", "--import-realm", "--hostname=http://127.0.0.1:8080")
            .withCreateContainerCmdModifier { it.hostConfig!!.withMemory(1024L * 1024 * 1024) }
            .waitingFor(
                Wait.forHttp("/realms/aso-dev/.well-known/openid-configuration").forPort(8080).withStartupTimeout(Duration.ofMinutes(3)),
            )

    private fun application(
        keycloak: SmokeContainer,
        environment: Map<String, String>,
        logs: StringBuffer,
    ): SmokeContainer =
        SmokeContainer(DockerImageName.parse(image))
            .withImagePullPolicy { false }
            .withNetworkMode("container:${keycloak.containerId}")
            .withEnv(environment)
            .withLogConsumer { logs.append(it.utf8String) }
            .withCreateContainerCmdModifier { it.hostConfig!!.withMemory(1024L * 1024 * 1024) }
            .waitingFor(Wait.forLogMessage(".*Started AsoApplication.*", 1).withStartupTimeout(Duration.ofMinutes(2)))

    private fun get(
        base: String,
        path: String,
    ): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI("$base$path"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private class SmokeContainer(
        image: DockerImageName,
    ) : GenericContainer<SmokeContainer>(image)
}
