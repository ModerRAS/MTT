package com.mtt.app.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SecureStorage.
 * 
 * Tests the encryption/decryption round-trip, clearing, and provider separation.
 */
class SecureStorageTest {
    
    private lateinit var secureStorage: SecureStorage
    
    @Before
    fun setup() {
        // Use application context for testing
        val context = ApplicationProvider.getApplicationContext<Context>()
        secureStorage = SecureStorage(context)
    }
    
    @After
    fun cleanup() {
        // Clear all keys after each test
        secureStorage.clearApiKey(SecureStorage.PROVIDER_OPENAI)
        secureStorage.clearApiKey(SecureStorage.PROVIDER_ANTHROPIC)
    }
    
    @Test
    fun `save and get round-trip returns original value`() {
        // Given: an API key
        val testKey = "sk-test123"
        
        // When: saving and retrieving
        secureStorage.saveApiKey(SecureStorage.PROVIDER_OPENAI, testKey)
        val retrievedKey = secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI)
        
        // Then: retrieved key matches original
        assertEquals(testKey, retrievedKey)
    }
    
    @Test
    fun `clear removes the key`() {
        // Given: a saved API key
        val testKey = "sk-clear-test"
        secureStorage.saveApiKey(SecureStorage.PROVIDER_OPENAI, testKey)
        
        // When: clearing the key
        secureStorage.clearApiKey(SecureStorage.PROVIDER_OPENAI)
        
        // Then: key is null
        assertNull(secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI))
    }
    
    @Test
    fun `hasApiKey returns true when key is saved`() {
        // Given: no key
        assertFalse(secureStorage.hasApiKey(SecureStorage.PROVIDER_OPENAI))
        
        // When: saving a key
        secureStorage.saveApiKey(SecureStorage.PROVIDER_OPENAI, "sk-has-key")
        
        // Then: hasApiKey returns true
        assertTrue(secureStorage.hasApiKey(SecureStorage.PROVIDER_OPENAI))
    }
    
    @Test
    fun `hasApiKey returns false after clear`() {
        // Given: a saved key
        secureStorage.saveApiKey(SecureStorage.PROVIDER_OPENAI, "sk-test")
        assertTrue(secureStorage.hasApiKey(SecureStorage.PROVIDER_OPENAI))
        
        // When: clearing the key
        secureStorage.clearApiKey(SecureStorage.PROVIDER_OPENAI)
        
        // Then: hasApiKey returns false
        assertFalse(secureStorage.hasApiKey(SecureStorage.PROVIDER_OPENAI))
    }
    
    @Test
    fun `different providers have separate keys`() {
        // Given: different API keys for different providers
        val openaiKey = "sk-openai-12345"
        val anthropicKey = "sk-ant-67890"
        
        // When: saving to different providers
        secureStorage.saveApiKey(SecureStorage.PROVIDER_OPENAI, openaiKey)
        secureStorage.saveApiKey(SecureStorage.PROVIDER_ANTHROPIC, anthropicKey)
        
        // Then: each provider returns its own key
        assertEquals(openaiKey, secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI))
        assertEquals(anthropicKey, secureStorage.getApiKey(SecureStorage.PROVIDER_ANTHROPIC))
        assertNotEquals(openaiKey, secureStorage.getApiKey(SecureStorage.PROVIDER_ANTHROPIC))
    }
    
    @Test
    fun `getApiKey returns null for unset provider`() {
        // When: getting key for never-set provider
        val retrieved = secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI)
        
        // Then: returns null
        assertNull(retrieved)
    }
    
    @Test
    fun `clear one provider does not affect other`() {
        // Given: keys for both providers
        secureStorage.saveApiKey(SecureStorage.PROVIDER_OPENAI, "sk-openai")
        secureStorage.saveApiKey(SecureStorage.PROVIDER_ANTHROPIC, "sk-anthropic")
        
        // When: clearing only OpenAI key
        secureStorage.clearApiKey(SecureStorage.PROVIDER_OPENAI)
        
        // Then: OpenAI key is gone, Anthropic key remains
        assertNull(secureStorage.getApiKey(SecureStorage.PROVIDER_OPENAI))
        assertEquals("sk-anthropic", secureStorage.getApiKey(SecureStorage.PROVIDER_ANTHROPIC))
    }
}