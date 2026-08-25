package com.chirp.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationDaoTest {

    private lateinit var db: ChirpDatabase
    private lateinit var conversationDao: ConversationDao
    private lateinit var messageDao: MessageDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ChirpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        conversationDao = db.conversationDao()
        messageDao = db.messageDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndReadConversation() = runBlocking {
        val id = conversationDao.insert(
            ConversationEntity(title = "New conversation", model = "llama3", systemPrompt = null, createdAt = 1, updatedAt = 1),
        )
        val loaded = conversationDao.getById(id)
        assertNotNull(loaded)
        assertEquals("llama3", loaded!!.model)

        val all = conversationDao.observeAll().first()
        assertEquals(1, all.size)
    }

    @Test
    fun observeAllOrdersByUpdatedAtDescending() = runBlocking {
        val older = conversationDao.insert(
            ConversationEntity(title = "older", model = "m", systemPrompt = null, createdAt = 1, updatedAt = 1),
        )
        val newer = conversationDao.insert(
            ConversationEntity(title = "newer", model = "m", systemPrompt = null, createdAt = 2, updatedAt = 2),
        )
        val all = conversationDao.observeAll().first()
        assertEquals(newer, all[0].id)
        assertEquals(older, all[1].id)
    }

    @Test
    fun messagesAreReturnedInOrderAndUserCountIsCorrect() = runBlocking {
        val id = conversationDao.insert(
            ConversationEntity(title = "t", model = "m", systemPrompt = null, createdAt = 1, updatedAt = 1),
        )
        messageDao.insert(MessageEntity(conversationId = id, role = "user", text = "hi", timestamp = 1))
        messageDao.insert(MessageEntity(conversationId = id, role = "assistant", text = "hello", timestamp = 2))
        messageDao.insert(MessageEntity(conversationId = id, role = "user", text = "again", timestamp = 3))

        val messages = messageDao.getByConversation(id)
        assertEquals(listOf("hi", "hello", "again"), messages.map { it.text })
        assertEquals(2, messageDao.userMessageCount(id))
    }

    @Test
    fun deletingConversationCascadesToMessages() = runBlocking {
        val id = conversationDao.insert(
            ConversationEntity(title = "t", model = "m", systemPrompt = null, createdAt = 1, updatedAt = 1),
        )
        messageDao.insert(MessageEntity(conversationId = id, role = "user", text = "hi", timestamp = 1))
        messageDao.insert(MessageEntity(conversationId = id, role = "assistant", text = "hello", timestamp = 2))
        assertEquals(2, messageDao.getByConversation(id).size)

        conversationDao.deleteById(id)

        // Foreign key ON DELETE CASCADE (Room enables FK enforcement by default).
        assertEquals(0, messageDao.getByConversation(id).size)
    }
}
