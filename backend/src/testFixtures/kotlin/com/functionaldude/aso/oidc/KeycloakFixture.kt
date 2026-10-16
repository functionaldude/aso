package com.functionaldude.aso.oidc

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.time.Duration

/** Shared image and realm are independent of Compose and persistent development state. */
class KeycloakFixture(
    callback: String = "http://127.0.0.1:8765/oidc/callback",
    mcpCallback: String = "http://127.0.0.1:8767/oidc/callback",
    resource: String = "http://127.0.0.1:8080/mcp",
    webCallback: String = "http://127.0.0.1:8080/login/oauth2/code/oidc",
) : GenericContainer<KeycloakFixture>(DockerImageName.parse(System.getProperty("aso.test.keycloak-image"))) {
    init {
        withExposedPorts(8080)
        withCopyFileToContainer(
            MountableFile.forHostPath(System.getProperty("aso.test.realm")),
            "/opt/keycloak/data/import/aso-dev-realm.json",
        )
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
}
