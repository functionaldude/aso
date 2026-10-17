package com.functionaldude.aso.search

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EmbeddingClientTest {
    @Test
    fun `slow and oversized local responses fail within configured bounds`() {
        EmbeddingFixture().use { fixture ->
            val settings =
                EmbeddingSettings(
                    enabled = true,
                    baseUrl = fixture.baseUrl,
                    dimensions = 3,
                    requestTimeout = java.time.Duration.ofMillis(100),
                    maxResponseBytes = 1024,
                )
            fixture.delayMillis = 500
            val start = System.nanoTime()
            assertFailsWith<EmbeddingUnavailable> { EmbeddingClient(settings).query("water") }
            assertTrue(
                System.nanoTime() - start <
                    java.time.Duration
                        .ofSeconds(1)
                        .toNanos(),
            )
            fixture.delayMillis = 0
            fixture.dimensions = 2000
            assertFailsWith<EmbeddingUnavailable> { EmbeddingClient(settings).query("water") }
        }
    }

    @Test
    fun `local adapter prepares queries separately and validates incompatible responses`() {
        EmbeddingFixture().use { fixture ->
            val settings =
                EmbeddingSettings(
                    enabled = true,
                    baseUrl = fixture.baseUrl,
                    dimensions = 3,
                    queryPrefix = "query: ",
                    documentPrefix = "doc: ",
                )
            val client = EmbeddingClient(settings)
            assertEquals(listOf(1.0, 0.0, 0.0), client.query("water"))
            assertEquals(2, client.documents(listOf("water", "build")).size)
            assertEquals(listOf("query: water", "doc: water", "doc: build"), fixture.inputs.toList())
            fixture.dimensions = 4
            assertFailsWith<EmbeddingUnavailable> { client.query("water") }
            fixture.dimensions = 3
            fixture.malformed = true
            assertFailsWith<EmbeddingUnavailable> { client.documents(listOf("water", "build")) }
            fixture.failure = true
            assertFailsWith<EmbeddingUnavailable> { client.query("water") }
        }
    }

    @Test
    fun `public providers and credential URLs are refused and preparation changes generations`() {
        assertFailsWith<IllegalArgumentException> { EmbeddingSettings(baseUrl = "https://api.openai.com/v1") }
        assertFailsWith<IllegalArgumentException> { EmbeddingSettings(baseUrl = "http://user:secret@localhost/v1") }
        assertFailsWith<IllegalArgumentException> {
            EmbeddingSettings(baseUrl = "https://192.0.2.1/v1", allowedHosts = listOf("192.0.2.1")).endpoint()
        }
        assertNotEquals(EmbeddingSettings().generation, EmbeddingSettings(documentPrefix = "passage: ").generation)
        assertNotEquals(EmbeddingSettings().generation, EmbeddingSettings(dimensions = 512).generation)
        assertEquals(EmbeddingSettings().generation, EmbeddingSettings(apiKey = "private").generation)
    }

    @Test
    fun `technical tokens preserve punctuation and complete versions`() {
        val tokens = SearchIdentifiers.tokens("C++ C# org.example:lib:1.2.0 java.lang.NoClassDefFoundError SQLSTATE 40P01")
        assertEquals(6, tokens.size)
        assertEquals(listOf("C++", "org.example:lib:1.2.0"), SearchIdentifiers.matched("C++ and org.example:lib:1.2.0", tokens))
        assertTrue(SearchIdentifiers.matched("C# org.example:lib:1.2.01", listOf("C++", "org.example:lib:1.2.0")).isEmpty())
        assertEquals(listOf("java.lang.NoClassDefFoundError"), SearchIdentifiers.matched("java.lang.NoClassDefFoundError: parser", tokens))
    }
}
