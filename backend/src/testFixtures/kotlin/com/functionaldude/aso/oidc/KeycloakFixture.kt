package com.functionaldude.aso.oidc

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Path
import java.time.Duration

/** Shared image and realm are independent of Compose and persistent development state. */
class KeycloakFixture(
    callback: String = "http://127.0.0.1:8765/oidc/callback",
    mcpCallback: String = "http://127.0.0.1:8767/oidc/callback",
    resource: String = "http://127.0.0.1:8080/mcp",
    webCallback: String = "http://127.0.0.1:8080/login/oauth2/code/oidc",
    additionalMcpClient: Boolean = false,
) : GenericContainer<KeycloakFixture>(DockerImageName.parse(System.getProperty("aso.test.keycloak-image"))) {
    private var forwarder: LoopbackPortForwarder? = null
    val issuer: String get() = "${checkNotNull(forwarder) { "Keycloak fixture must be started" }.baseUrl}/realms/aso-dev"

    init {
        withExposedPorts(8080)
        val destination = "/opt/keycloak/data/import/aso-dev-realm.json"
        if (additionalMcpClient) {
            // Disposable fixture only: two public PKCE clients share the exact temporary callback/resource.
            // Keep the real Codex/MCP Jam registrations and persistent development seed unchanged.
            val json = JsonMapper.builder().build()
            val realm = json.readTree(Path.of(System.getProperty("aso.test.realm")).toFile())
            val clients = realm["clients"] as ArrayNode
            val first = clients.first { it["clientId"].asString() == "aso-mcp-test" }
            val second = json.readTree(json.writeValueAsString(first)) as ObjectNode
            second.put("clientId", "aso-mcp-other-test")
            second.put("name", "Second isolated MCP test client")
            clients.add(second)
            withCopyToContainer(Transferable.of(json.writeValueAsBytes(realm)), destination)
        } else {
            withCopyFileToContainer(MountableFile.forHostPath(System.getProperty("aso.test.realm")), destination)
        }
        withEnv("KC_DB", "dev-mem")
        withEnv("ASO_PROBE_CALLBACK", callback)
        withEnv("ASO_WEB_CALLBACK", webCallback)
        withEnv("ASO_WEB_LOGOUT", webCallback.substringBefore("/login/") + "/?signedOut")
        withEnv("ASO_MCP_CALLBACK", mcpCallback)
        withEnv("ASO_MCP_RESOURCE", resource)
        withCommand("start-dev", "--import-realm", "--hostname-strict=false")
        withCreateContainerCmdModifier { it.hostConfig!!.withMemory(1024L * 1024 * 1024) }
        waitingFor(Wait.forHttp("/realms/aso-dev/.well-known/openid-configuration").forPort(8080).withStartupTimeout(Duration.ofMinutes(3)))
    }

    override fun start() {
        if (isRunning) return
        super.start()
        try {
            forwarder = LoopbackPortForwarder(host, getMappedPort(8080))
        } catch (failure: Exception) {
            super.stop()
            throw failure
        }
    }

    override fun stop() {
        try {
            forwarder?.close()
            forwarder = null
        } finally {
            super.stop()
        }
    }
}
