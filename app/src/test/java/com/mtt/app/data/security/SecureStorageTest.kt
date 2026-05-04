package com.mtt.app.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [SecureStorage].
 *
 * Tests encrypted API key save/read/delete/check operations using
 * Robolectric with real EncryptedSharedPreferences.
 */
@RunWith(RobolectricTestRunner::class)
class SecureStorageTest {

    private lateinit var secureStorage: SecureStorage
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        secureStorage = SecureStorage(context)
        // Clear any existing data to ensure test isolation
        clearAllProviders()
    }

    @After
    fun tearDown() {
        clearAllProviders()
    }

    private fun clearAllProviders() {
        secureStorage.clearApiKey(SecureStorage.PROVIDER_OPENAI)
        secureStorage.clearApiKey(SecureStorage.PROVIDER_ANTHROPIC)
    }

    // ═══════════════════════════════════════════════
    //  Save and Get API Key Tests
    // ═══════════════════════════════════════════════

    @Test
    fun `saveApiKey and getApiKey return consistent value for OpenAI`() {
        val apiKey = "sk-openai-test-key-12345"

        secureStorage.saveApiKey(SecureStorage.PROVIDER_OPENAI, apiKey)
        val retrieved = secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI)

        assertEquals(apiKey, retrieved)
    }

    @Test
    fun `saveApiKey and getApiKey return consistent value for Anthropic`() {
        val apiKey = "sk-ant-test-key-67890"

        secureStorage.saveApiKey(SecureStorage.PROVIDER_ANTHROPIC, apiKey)
        val retrieved = secureStorage.getApiKey(SecureStorage.PROVIDER_ANTHROPIC)

        assertEquals(apiKey, retrieved)
    }

    @Test
    fun `getApiKey returns null for non-existent key`() {
        val result = secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI)

        assertNull(result)
    }

    @Test
    fun `getApiKey returns null for never-saved custom provider`() {
        val result = secureStorage.getApiKey("custom_provider")

        assertNull(result)
    }

    @Test
    fun `saveApiKey and getApiKey work with custom provider`() {
        val provider = "custom_provider"
        val apiKey = "sk-custom-key-abc123"

        secureStorage.saveApiKey(provider, apiKey)
        val retrieved = secureStorage.getApiKey(provider)

        assertEquals(apiKey, retrieved)
    }

    // ═══════════════════════════════════════════════
    //  Clear API Key Tests
    // ═══════════════════════════════════════════════

    @Test
    fun `clearApiKey removes saved OpenAI key`() {
        secureStorage.saveApiKey(SecureStorage.PROVIDER_OPENAI, "test-key")
        assertNotNull(secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI))

        secureStorage.clearApiKey(SecureStorage.PROVIDER_OPENAI)

        assertNull(secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI))
    }

    @Test
    fun `clearApiKey removes saved Anthropic key`() {
        secureStorage.saveApiKey(SecureStorage.PROVIDER_ANTHROPIC, "test-key")
        assertNotNull(secureStorage.getApiKey(SecureStorage.PROVIDER_ANTHROPIC))

        secureStorage.clearApiKey(SecureStorage.PROVIDER_ANTHROPIC)

        assertNull(secureStorage.getApiKey(SecureStorage.PROVIDER_ANTHROPIC))
    }

    @Test
    fun `clearApiKey does not affect other providers`() {
        val openaiKey = "openai-key"
        val anthropicKey = "anthropic-key"

        secureStorage.saveApiKey(SecureStorage.PROVIDER_OPENAI, openaiKey)
        secureStorage.saveApiKey(SecureStorage.PROVIDER_ANTHROPIC, anthropicKey)

        secureStorage.clearApiKey(SecureStorage.PROVIDER_OPENAI)

        assertNull(secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI))
        assertEquals(anthropicKey, secureStorage.getApiKey(SecureStorage.PROVIDER_ANTHROPIC))
    }

    // ═══════════════════════════════════════════════
    //  Has API Key Tests
    // ═══════════════════════════════════════════════

    @Test
    fun `hasApiKey returns true after saving OpenAI key`() {
        secureStorage.saveApiKey(SecureStorage.PROVIDER_OPENAI, "test-key")

        assertTrue(secureStorage.hasApiKey(SecureStorage.PROVIDER_OPENAI))
    }

    @Test
    fun `hasApiKey returns true after saving Anthropic key`() {
        secureStorage.saveApiKey(SecureStorage.PROVIDER_ANTHROPIC, "test-key")

        assertTrue(secureStorage.hasApiKey(SecureStorage.PROVIDER_ANTHROPIC))
    }

    @Test
    fun `hasApiKey returns false after clearing key`() {
        secureStorage.saveApiKey(SecureStorage.PROVIDER_OPENAI, "test-key")
        assertTrue(secureStorage.hasApiKey(SecureStorage.PROVIDER_OPENAI))

        secureStorage.clearApiKey(SecureStorage.PROVIDER_OPENAI)

        assertFalse(secureStorage.hasApiKey(SecureStorage.PROVIDER_OPENAI))
    }

    @Test
    fun `hasApiKey returns false for never-saved key`() {
        assertFalse(secureStorage.hasApiKey(SecureStorage.PROVIDER_OPENAI))
    }

    @Test
    fun `hasApiKey returns false for non-existent custom provider`() {
        assertFalse(secureStorage.hasApiKey("nonexistent_provider"))
    }

    // ═══════════════════════════════════════════════
    //  Overwrite Key Tests
    // ═══════════════════════════════════════════════

    @Test
    fun `saveApiKey overwrites existing key`() {
        val originalKey = "original-key"
        val newKey = "new-key"

        secureStorage.saveApiKey(SecureStorage.PROVIDER_OPENAI, originalKey)
        assertEquals(originalKey, secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI))

        secureStorage.saveApiKey(SecureStorage.PROVIDER_OPENAI, newKey)

        assertEquals(newKey, secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI))
        assertTrue(secureStorage.hasApiKey(SecureStorage.PROVIDER_OPENAI))
    }

    @Test
    fun `saveApiKey overwrites different provider key independently`() {
        val openaiKey1 = "openai-v1"
        val openaiKey2 = "openai-v2"
        val anthropicKey = "anthropic-key"

        secureStorage.saveApiKey(SecureStorage.PROVIDER_OPENAI, openaiKey1)
        secureStorage.saveApiKey(SecureStorage.PROVIDER_ANTHROPIC, anthropicKey)

        secureStorage.saveApiKey(SecureStorage.PROVIDER_OPENAI, openaiKey2)

        assertEquals(openaiKey2, secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI))
        assertEquals(anthropicKey, secureStorage.getApiKey(SecureStorage.PROVIDER_ANTHROPIC))
    }

    // ═══════════════════════════════════════════════
    //  Custom Provider Tests
    // ═══════════════════════════════════════════════

    @Test
    fun `custom provider key is saved and retrieved correctly`() {
        val provider = "my_custom_provider"
        val apiKey = "sk-custom-api-key-xyz"

        secureStorage.saveApiKey(provider, apiKey)
        val retrieved = secureStorage.getApiKey(provider)

        assertEquals(apiKey, retrieved)
    }

    @Test
    fun `custom provider hasApiKey works correctly`() {
        val provider = "test_custom"

        assertFalse(secureStorage.hasApiKey(provider))

        secureStorage.saveApiKey(provider, "key")

        assertTrue(secureStorage.hasApiKey(provider))
    }

    @Test
    fun `custom provider clearApiKey works correctly`() {
        val provider = "another_custom"

        secureStorage.saveApiKey(provider, "key")
        assertTrue(secureStorage.hasApiKey(provider))

        secureStorage.clearApiKey(provider)

        assertFalse(secureStorage.hasApiKey(provider))
        assertNull(secureStorage.getApiKey(provider))
    }

    // ═══════════════════════════════════════════════
    //  Edge Cases
    // ═══════════════════════════════════════════════

    @Test
    fun `empty string key can be saved and retrieved`() {
        val emptyKey = ""

        secureStorage.saveApiKey(SecureStorage.PROVIDER_OPENAI, emptyKey)
        val retrieved = secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI)

        // Empty string is a valid value
        assertEquals(emptyKey, retrieved)
        assertTrue(secureStorage.hasApiKey(SecureStorage.PROVIDER_OPENAI))
    }

    @Test
    fun `special characters in key are preserved`() {
        val specialKey = "sk-key-with-special-chars-!@#$%^&*()"

        secureStorage.saveApiKey(SecureStorage.PROVIDER_OPENAI, specialKey)
        val retrieved = secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI)

        assertEquals(specialKey, retrieved)
    }

    @Test
    fun `unicode characters in key are preserved`() {
        val unicodeKey = "密钥-秘密鍵-마스터키"

        secureStorage.saveApiKey(SecureStorage.PROVIDER_OPENAI, unicodeKey)
        val retrieved = secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI)

        assertEquals(unicodeKey, retrieved)
    }

    @Test
    fun `long key can be saved and retrieved`() {
        val longKey = "sk-" + "a".repeat(1000)

        secureStorage.saveApiKey(SecureStorage.PROVIDER_OPENAI, longKey)
        val retrieved = secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI)

        assertEquals(longKey, retrieved)
    }

    @Test
    fun `multiple save-clear cycles work correctly`() {
        val key = "test-key-cycle"

        repeat(3) {
            secureStorage.saveApiKey(SecureStorage.PROVIDER_OPENAI, key)
            assertEquals(key, secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI))

            secureStorage.clearApiKey(SecureStorage.PROVIDER_OPENAI)
            assertNull(secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI))
        }
    }
}
