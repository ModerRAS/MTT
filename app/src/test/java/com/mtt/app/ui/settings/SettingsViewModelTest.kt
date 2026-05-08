package com.mtt.app.ui.settings

import com.mtt.app.data.model.ChannelConfig
import com.mtt.app.data.model.ChannelType
import com.mtt.app.data.model.FetchedModel
import com.mtt.app.data.remote.ModelFetcher
import com.mtt.app.data.security.SecureStorage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var secureStorage: SecureStorage
    private lateinit var modelFetcher: ModelFetcher
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        secureStorage = mockk(relaxed = true)
        modelFetcher = mockk(relaxed = true)

        // Mock empty storage by default
        every { secureStorage.loadChannels() } returns emptyList()
        every { secureStorage.loadActiveChannelId() } returns null
        every { secureStorage.loadActiveModelId() } returns null
        every { secureStorage.getValue(any()) } returns null

        viewModel = SettingsViewModel(secureStorage, modelFetcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has empty channels`() {
        val state = viewModel.uiState.value

        assertTrue(state.channels.isEmpty())
        assertNull(state.activeChannelId)
        assertEquals("", state.activeModelId)
        assertFalse(state.isAddingChannel)
        assertNull(state.editingChannelId)
        assertEquals(50, state.batchSize)
        assertEquals(1, state.concurrency)
    }

    @Test
    fun `loadChannels loads saved channels from storage`() {
        val savedChannels = listOf(
            ChannelConfig(
                id = "channel-1",
                name = "OpenAI Channel",
                type = ChannelType.OPENAI,
                baseUrl = "https://api.openai.com/v1",
                apiKey = "sk-test-key",
                fetchedModels = listOf(
                    FetchedModel("gpt-4o", "GPT-4o", 128000)
                ),
                fetchedAt = 1234567890
            )
        )
        every { secureStorage.loadChannels() } returns savedChannels
        every { secureStorage.loadActiveChannelId() } returns "channel-1"
        every { secureStorage.loadActiveModelId() } returns "gpt-4o"

        viewModel.loadChannels()
        val state = viewModel.uiState.value

        assertEquals(1, state.channels.size)
        assertEquals("channel-1", state.channels[0].id)
        assertEquals("OpenAI Channel", state.channels[0].name)
        assertEquals("channel-1", state.activeChannelId)
        assertEquals("gpt-4o", state.activeModelId)
    }

    @Test
    fun `loadSettings loads batch size and concurrency`() {
        every { secureStorage.getValue(SecureStorage.KEY_BATCH_SIZE) } returns "100"
        every { secureStorage.getValue(SecureStorage.KEY_CONCURRENCY) } returns "5"

        viewModel.loadSettings()
        val state = viewModel.uiState.value

        assertEquals(100, state.batchSize)
        assertEquals(5, state.concurrency)
    }

    @Test
    fun `loadSettings uses defaults for invalid values`() {
        every { secureStorage.getValue(SecureStorage.KEY_BATCH_SIZE) } returns "invalid"
        every { secureStorage.getValue(SecureStorage.KEY_CONCURRENCY) } returns "also-invalid"

        viewModel.loadSettings()
        val state = viewModel.uiState.value

        assertEquals(50, state.batchSize)
        assertEquals(1, state.concurrency)
    }

    @Test
    fun `loadSettings clamps batch size to valid range`() {
        every { secureStorage.getValue(SecureStorage.KEY_BATCH_SIZE) } returns "500"
        every { secureStorage.getValue(SecureStorage.KEY_CONCURRENCY) } returns "0"

        viewModel.loadSettings()
        val state = viewModel.uiState.value

        assertEquals(200, state.batchSize) // clamped to max
        assertEquals(1, state.concurrency) // clamped to min
    }

    @Test
    fun `saveSettings persists batch size and concurrency`() {
        viewModel.onBatchSizeChange(100)
        viewModel.onConcurrencyChange(5)

        viewModel.saveSettings()

        io.mockk.verify {
            secureStorage.saveValue(SecureStorage.KEY_BATCH_SIZE, "100")
            secureStorage.saveValue(SecureStorage.KEY_CONCURRENCY, "5")
        }
    }

    @Test
    fun `toggleAddChannel shows and hides add dialog`() {
        assertFalse(viewModel.uiState.value.isAddingChannel)

        viewModel.toggleAddChannel()
        assertTrue(viewModel.uiState.value.isAddingChannel)
        assertEquals("", viewModel.uiState.value.newChannelForm.name)

        viewModel.toggleAddChannel()
        assertFalse(viewModel.uiState.value.isAddingChannel)
    }

    @Test
    fun `startEditChannel populates form with channel data`() {
        val channel = ChannelConfig(
            id = "channel-1",
            name = "Test Channel",
            type = ChannelType.ANTHROPIC,
            baseUrl = "https://api.anthropic.com",
            apiKey = "sk-test-key",
            fetchedModels = emptyList(),
            fetchedAt = null
        )
        every { secureStorage.loadChannels() } returns listOf(channel)
        viewModel.loadChannels()

        viewModel.startEditChannel("channel-1")
        val state = viewModel.uiState.value

        assertEquals("channel-1", state.editingChannelId)
        assertEquals("Test Channel", state.newChannelForm.name)
        assertEquals(ChannelType.ANTHROPIC, state.newChannelForm.type)
        assertEquals("https://api.anthropic.com", state.newChannelForm.baseUrl)
        assertEquals("sk-test-key", state.newChannelForm.apiKey)
        assertFalse(state.newChannelForm.apiKeyVisible)
    }

    @Test
    fun `cancelEditChannel clears editing state`() {
        val channel = ChannelConfig(
            id = "channel-1",
            name = "Test Channel",
            type = ChannelType.OPENAI,
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-test",
            fetchedModels = emptyList(),
            fetchedAt = null
        )
        every { secureStorage.loadChannels() } returns listOf(channel)
        viewModel.loadChannels()

        viewModel.startEditChannel("channel-1")
        viewModel.cancelEditChannel()
        val state = viewModel.uiState.value

        assertNull(state.editingChannelId)
        assertEquals("", state.newChannelForm.name)
    }

    @Test
    fun `updateFormName updates form name`() {
        viewModel.toggleAddChannel()

        viewModel.updateFormName("My Channel")
        assertEquals("My Channel", viewModel.uiState.value.newChannelForm.name)
    }

    @Test
    fun `updateFormType updates form type`() {
        viewModel.toggleAddChannel()

        viewModel.updateFormType(ChannelType.ANTHROPIC)
        assertEquals(ChannelType.ANTHROPIC, viewModel.uiState.value.newChannelForm.type)
    }

    @Test
    fun `updateFormBaseUrl updates form URL`() {
        viewModel.toggleAddChannel()

        viewModel.updateFormBaseUrl("https://custom.api.com/v1")
        assertEquals("https://custom.api.com/v1", viewModel.uiState.value.newChannelForm.baseUrl)
    }

    @Test
    fun `updateFormApiKey updates form key`() {
        viewModel.toggleAddChannel()

        viewModel.updateFormApiKey("sk-my-secret-key")
        assertEquals("sk-my-secret-key", viewModel.uiState.value.newChannelForm.apiKey)
    }

    @Test
    fun `toggleFormApiKeyVisibility toggles visibility`() {
        viewModel.toggleAddChannel()
        assertFalse(viewModel.uiState.value.newChannelForm.apiKeyVisible)

        viewModel.toggleFormApiKeyVisibility()
        assertTrue(viewModel.uiState.value.newChannelForm.apiKeyVisible)

        viewModel.toggleFormApiKeyVisibility()
        assertFalse(viewModel.uiState.value.newChannelForm.apiKeyVisible)
    }

    @Test
    fun `addChannel adds new channel when form is valid`() {
        viewModel.toggleAddChannel()
        viewModel.updateFormName("New Channel")
        viewModel.updateFormType(ChannelType.OPENAI)
        viewModel.updateFormBaseUrl("https://api.openai.com/v1")
        viewModel.updateFormApiKey("sk-new-key")

        viewModel.addChannel()

        val state = viewModel.uiState.value
        assertEquals(1, state.channels.size)
        assertEquals("New Channel", state.channels[0].name)
        assertEquals(ChannelType.OPENAI, state.channels[0].type)
        assertEquals("https://api.openai.com/v1", state.channels[0].baseUrl)
        assertEquals("sk-new-key", state.channels[0].apiKey)
        assertFalse(state.isAddingChannel)
        assertEquals("渠道 \"New Channel\" 已添加", state.globalMessage)
    }

    @Test
    fun `addChannel shows error when form is incomplete`() {
        viewModel.toggleAddChannel()
        viewModel.updateFormName("") // Missing required fields

        viewModel.addChannel()

        val state = viewModel.uiState.value
        assertTrue(state.channels.isEmpty())
        assertEquals("请填写所有必填项（名称、URL、API Key）", state.globalMessage)
    }

    @Test
    fun `addChannel trims base URL trailing slash`() {
        viewModel.toggleAddChannel()
        viewModel.updateFormName("Trim Test")
        viewModel.updateFormType(ChannelType.OPENAI)
        viewModel.updateFormBaseUrl("https://api.openai.com/v1/")
        viewModel.updateFormApiKey("sk-key")

        viewModel.addChannel()

        assertEquals("https://api.openai.com/v1", viewModel.uiState.value.channels[0].baseUrl)
    }

    @Test
    fun `updateChannel updates existing channel`() {
        val channel = ChannelConfig(
            id = "channel-1",
            name = "Original Name",
            type = ChannelType.OPENAI,
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-old-key",
            fetchedModels = emptyList(),
            fetchedAt = null
        )
        every { secureStorage.loadChannels() } returns listOf(channel)
        viewModel.loadChannels()

        viewModel.startEditChannel("channel-1")
        viewModel.updateFormName("Updated Name")
        viewModel.updateFormApiKey("sk-new-key")
        viewModel.updateChannel("channel-1")

        val state = viewModel.uiState.value
        assertEquals(1, state.channels.size)
        assertEquals("Updated Name", state.channels[0].name)
        assertEquals("sk-new-key", state.channels[0].apiKey)
        assertNull(state.editingChannelId)
    }

    @Test
    fun `deleteChannel removes channel from list`() {
        val channel = ChannelConfig(
            id = "channel-1",
            name = "To Delete",
            type = ChannelType.OPENAI,
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-key",
            fetchedModels = emptyList(),
            fetchedAt = null
        )
        every { secureStorage.loadChannels() } returns listOf(channel)
        viewModel.loadChannels()

        viewModel.deleteChannel("channel-1")

        val state = viewModel.uiState.value
        assertTrue(state.channels.isEmpty())
    }

    @Test
    fun `deleteChannel clears active channel if it was the deleted one`() {
        val channel = ChannelConfig(
            id = "channel-1",
            name = "Active Channel",
            type = ChannelType.OPENAI,
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-key",
            fetchedModels = emptyList(),
            fetchedAt = null
        )
        every { secureStorage.loadChannels() } returns listOf(channel)
        every { secureStorage.loadActiveChannelId() } returns "channel-1"
        viewModel.loadChannels()

        viewModel.deleteChannel("channel-1")

        val state = viewModel.uiState.value
        assertNull(state.activeChannelId)
    }

    @Test
    fun `deleteChannel preserves active channel if different from deleted`() {
        val channel1 = ChannelConfig(
            id = "channel-1",
            name = "Channel 1",
            type = ChannelType.OPENAI,
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-key1",
            fetchedModels = emptyList(),
            fetchedAt = null
        )
        val channel2 = ChannelConfig(
            id = "channel-2",
            name = "Channel 2",
            type = ChannelType.ANTHROPIC,
            baseUrl = "https://api.anthropic.com",
            apiKey = "sk-key2",
            fetchedModels = emptyList(),
            fetchedAt = null
        )
        every { secureStorage.loadChannels() } returns listOf(channel1, channel2)
        every { secureStorage.loadActiveChannelId() } returns "channel-2"
        viewModel.loadChannels()

        viewModel.deleteChannel("channel-1")

        val state = viewModel.uiState.value
        assertEquals("channel-2", state.activeChannelId)
        assertEquals(1, state.channels.size)
    }

    @Test
    fun `setActiveChannel updates and persists active channel ID`() {
        viewModel.setActiveChannel("new-active-id")

        io.mockk.verify { secureStorage.saveActiveChannelId("new-active-id") }
        assertEquals("new-active-id", viewModel.uiState.value.activeChannelId)
    }

    @Test
    fun `setActiveModel updates and persists active model ID`() {
        viewModel.setActiveModel("gpt-4o")

        io.mockk.verify { secureStorage.saveActiveModelId("gpt-4o") }
        assertEquals("gpt-4o", viewModel.uiState.value.activeModelId)
    }

    @Test
    fun `onBatchSizeChange updates batch size within valid range`() {
        viewModel.onBatchSizeChange(75)
        assertEquals(75, viewModel.uiState.value.batchSize)

        viewModel.onBatchSizeChange(0)
        assertEquals(1, viewModel.uiState.value.batchSize) // clamped to min

        viewModel.onBatchSizeChange(500)
        assertEquals(200, viewModel.uiState.value.batchSize) // clamped to max
    }

    @Test
    fun `onConcurrencyChange updates concurrency within valid range`() {
        viewModel.onConcurrencyChange(8)
        assertEquals(8, viewModel.uiState.value.concurrency)

        viewModel.onConcurrencyChange(0)
        assertEquals(1, viewModel.uiState.value.concurrency) // clamped to min

        viewModel.onConcurrencyChange(20)
        assertEquals(10, viewModel.uiState.value.concurrency) // clamped to max
    }

    @Test
    fun `clearMessage clears global message`() {
        // First set a message by triggering an error condition
        viewModel.toggleAddChannel()
        viewModel.addChannel() // This sets global message for incomplete form

        viewModel.clearMessage()
        assertNull(viewModel.uiState.value.globalMessage)
    }
}