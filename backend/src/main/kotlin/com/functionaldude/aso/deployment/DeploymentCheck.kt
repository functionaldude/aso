package com.functionaldude.aso.deployment

import com.functionaldude.aso.search.EmbeddingClient
import com.functionaldude.aso.search.EmbeddingSettings
import org.flywaydb.core.Flyway
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import java.sql.DriverManager

/** Explicit deployment command, never run by ordinary application startup. */
object DeploymentCheck {
    @JvmStatic
    fun main(args: Array<String>) {
        val environment = StandardEnvironment()
        YamlPropertySourceLoader().load("application", ClassPathResource("application.yaml")).forEach {
            environment.propertySources.addLast(it)
        }
        ConfigurationPropertySources.attach(environment)
        val binder = Binder.get(environment)
        val settings = binder.bind("aso.embedding", Bindable.of(EmbeddingSettings::class.java)).get()
        require(settings.enabled && settings.provider == "vertex") { "Deployment check requires explicit Vertex configuration" }
        val url = environment.getRequiredProperty("spring.datasource.url")
        val user = environment.getRequiredProperty("spring.datasource.username")
        val password = environment.getRequiredProperty("spring.datasource.password")
        Flyway
            .configure()
            .dataSource(url, user, password)
            .load()
            .migrate()
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select extversion from pg_extension where extname = 'vector'").use {
                    check(it.next()) { "pgvector extension missing" }
                    println("Cloud SQL connected; Flyway migrations applied; pgvector ${it.getString(1)}")
                }
            }
        }
        val client = EmbeddingClient(settings)
        check(client.query("How do I troubleshoot a PostgreSQL connection timeout?").size == settings.dimensions)
        check(
            client.documents(listOf("Check the connection pool size and database connection limits.")).single().size == settings.dimensions,
        )
        println("Vertex query and document embeddings verified: ${settings.model}, ${settings.dimensions} dimensions")
    }
}
