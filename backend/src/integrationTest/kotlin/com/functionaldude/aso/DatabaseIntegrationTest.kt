package com.functionaldude.aso

import com.functionaldude.aso.oidc.KeycloakFixture
import com.functionaldude.aso.oidc.OidcBrowserLogin
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DatabaseIntegrationTest {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `startup applies Flyway migrations and vector operations work`() {
        assertEquals(
            4,
            jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL", Int::class.java),
        )
        assertTrue(jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname = 'vector'", String::class.java)!!.isNotBlank())
        assertEquals(0.0, jdbc.queryForObject("SELECT '[1,2,3]'::vector <-> '[1,2,3]'::vector", Double::class.java))
    }

    @Test
    fun `V3 upgrades populated V2 identity data without rewriting it`() {
        fun migration() =
            Flyway
                .configure()
                .dataSource(
                    database.jdbcUrl,
                    database.username,
                    database.password,
                ).schemas("upgrade_check")
                .defaultSchema("upgrade_check")
        migration().target("2").load().migrate()
        val id = java.util.UUID.randomUUID()
        jdbc.update(
            "INSERT INTO upgrade_check.app_user (id, issuer, subject, display_name) VALUES (?, ?, ?, ?)",
            id,
            "https://fixture.invalid",
            "retained",
            "Retained identity",
        )
        migration().load().migrate()
        assertEquals(
            "Retained identity",
            jdbc.queryForObject("SELECT display_name FROM upgrade_check.app_user WHERE id = ?", String::class.java, id),
        )
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM upgrade_check.question", Int::class.java))
        assertEquals(
            4,
            jdbc.queryForObject(
                "SELECT count(*) FROM upgrade_check.flyway_schema_history WHERE success AND version IS NOT NULL",
                Int::class.java,
            ),
        )
    }

    @Test
    fun `V4 preserves populated V3 content and retry reservations`() {
        fun migration() =
            Flyway
                .configure()
                .dataSource(database.jdbcUrl, database.username, database.password)
                .schemas("lifecycle_upgrade")
                .defaultSchema("lifecycle_upgrade")
        migration().target("3").load().migrate()
        val actor = java.util.UUID.randomUUID()
        val question = java.util.UUID.randomUUID()
        val answer = java.util.UUID.randomUUID()
        val request = java.util.UUID.randomUUID()
        jdbc.update(
            "INSERT INTO lifecycle_upgrade.app_user(id,issuer,subject,display_name) VALUES (?, 'https://fixture.invalid', 'owner', 'Owner')",
            actor,
        )
        jdbc.update(
            "INSERT INTO lifecycle_upgrade.question(id,title,body,author_id,channel) VALUES (?, 'Retained', 'Problem', ?, 'mcp')",
            question,
            actor,
        )
        jdbc.update(
            "INSERT INTO lifecycle_upgrade.answer(id,question_id,body,author_id,channel) VALUES (?, ?, 'Fix', ?, 'web')",
            answer,
            question,
            actor,
        )
        jdbc.update(
            "INSERT INTO lifecycle_upgrade.content_request VALUES (?, 'create_question', ?, ?, ?)",
            actor,
            request,
            "a".repeat(64),
            question,
        )
        migration().load().migrate()
        assertEquals(
            "Retained",
            jdbc.queryForObject("SELECT title FROM lifecycle_upgrade.question WHERE id = ?", String::class.java, question),
        )
        assertEquals(1, jdbc.queryForObject("SELECT version FROM lifecycle_upgrade.question WHERE id = ?", Int::class.java, question))
        assertEquals(false, jdbc.queryForObject("SELECT hidden FROM lifecycle_upgrade.answer WHERE id = ?", Boolean::class.java, answer))
        assertEquals(
            question,
            jdbc.queryForObject(
                "SELECT result_id FROM lifecycle_upgrade.content_request WHERE request_id = ?",
                java.util.UUID::class.java,
                request,
            ),
        )
    }

    @Test
    fun `health reports availability without exposing internal details`() {
        val response =
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("\"UP\""))
        assertFalse(response.body().contains("components"))
    }

    companion object {
        private val login = OidcBrowserLogin()
        private val keycloak = KeycloakFixture(login.callback, login.callback).also { it.start() }
        private val database =
            PostgreSQLContainer(
                DockerImageName.parse(System.getProperty("aso.test.database-image")).asCompatibleSubstituteFor("postgres"),
            ).also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("aso.identity.issuer") { keycloak.issuer }
            registry.add("aso.identity.web-client-id") { "aso-web" }
            registry.add("aso.identity.web-client-secret") { "aso-web-secret-local-only" }
            registry.add("aso.identity.public-url") { "http://127.0.0.1:8080" }
            registry.add("aso.identity.mcp-audience") { "http://127.0.0.1:8080/mcp" }
            registry.add("aso.identity.groups-claim") { "groups" }
            registry.add("aso.identity.member-group") { "aso-members" }
            registry.add("aso.identity.admin-group") { "aso-admins" }
            registry.add("spring.datasource.url", database::getJdbcUrl)
            registry.add("spring.datasource.username", database::getUsername)
            registry.add("spring.datasource.password", database::getPassword)
        }

        @JvmStatic
        @AfterAll
        fun stopDatabase() {
            database.stop()
            keycloak.stop()
            login.close()
        }
    }
}
