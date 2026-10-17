package com.functionaldude.aso

import com.functionaldude.aso.oidc.KeycloakFixture
import com.functionaldude.aso.oidc.OidcBrowserLogin
import com.functionaldude.aso.search.EmbeddingFixture
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Opt-in visual fixtures never read .env or connect to persistent development services. */
class PackagedUiFixture(
    artifacts: Path,
) : AutoCloseable {
    private val database =
        PostgreSQLContainer(DockerImageName.parse(System.getProperty("aso.test.database-image")).asCompatibleSubstituteFor("postgres"))
    private val login = OidcBrowserLogin()
    private val embeddings = EmbeddingFixture()
    private val port = ServerSocket(0).use { it.localPort }
    val baseUrl = "http://127.0.0.1:$port"
    private val keycloak = KeycloakFixture(login.callback, login.callback, "$baseUrl/mcp", "$baseUrl/login/oauth2/code/oidc")
    private var application: Process? = null

    init {
        try {
            Files.createDirectories(artifacts)
            database.start()
            keycloak.start()
            val builder =
                ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-jar",
                    System.getProperty("aso.test.jar"),
                    "--server.port=$port",
                    "--server.address=127.0.0.1",
                ).redirectErrorStream(true).redirectOutput(artifacts.resolve("application.log").toFile())
            builder.environment().keys.removeIf { it.startsWith("ASO_") || it.startsWith("SPRING_") }
            builder.environment().putAll(
                mapOf(
                    "ASO_OIDC_ISSUER" to keycloak.issuer,
                    "ASO_OIDC_WEB_CLIENT_ID" to "aso-web",
                    "ASO_OIDC_WEB_CLIENT_SECRET" to "aso-web-secret-local-only",
                    "ASO_PUBLIC_URL" to baseUrl,
                    "ASO_MCP_AUDIENCE" to "$baseUrl/mcp",
                    "ASO_CODEX_CLIENT_ID" to "aso-codex",
                    "ASO_CODEX_CALLBACK_URL" to login.callback,
                    "ASO_MCP_JAM_CLIENT_ID" to "aso-mcpjam",
                    "ASO_MCP_JAM_CALLBACK_URL" to login.callback,
                    "ASO_OIDC_GROUPS_CLAIM" to "groups",
                    "ASO_OIDC_MEMBER_GROUP" to "aso-members",
                    "ASO_OIDC_ADMIN_GROUP" to "aso-admins",
                    "ASO_DB_URL" to database.jdbcUrl,
                    "ASO_DB_USER" to database.username,
                    "ASO_DB_PASSWORD" to database.password,
                    "ASO_EMBEDDING_ENABLED" to "true",
                    "ASO_EMBEDDING_BASE_URL" to embeddings.baseUrl,
                    "ASO_EMBEDDING_DIMENSIONS" to "3",
                    "ASO_RATE_LIMIT_REQUESTS" to "100000",
                    "ASO_RATE_LIMIT_WRITES" to "100000",
                ),
            )
            application = builder.start()
            val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
            val deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos()
            var healthy = false
            while (System.nanoTime() < deadline && application!!.isAlive) {
                val response =
                    runCatching {
                        http.send(
                            HttpRequest.newBuilder(URI("$baseUrl/actuator/health")).timeout(Duration.ofSeconds(2)).build(),
                            HttpResponse.BodyHandlers.discarding(),
                        )
                    }.getOrNull()
                if (response?.statusCode() == 200) {
                    healthy = true
                    break
                }
                Thread.sleep(250)
            }
            check(healthy) { "Packaged preview did not become healthy; inspect application.log" }
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    @Synchronized
    override fun close() {
        application?.let {
            it.destroy()
            if (!it.waitFor(10, TimeUnit.SECONDS)) it.destroyForcibly().waitFor()
        }
        application = null
        embeddings.close()
        keycloak.close()
        login.close()
        database.close()
    }
}
