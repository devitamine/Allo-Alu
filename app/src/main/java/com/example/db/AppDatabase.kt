package com.example.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val address: String, // Unique Algorand address
    val nickname: String,
    val note: String = "",
    val pinned: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "messages")
data class LocalMessage(
    @PrimaryKey val txId: String, // Algorand Transaction ID
    val sender: String,
    val receiver: String,
    val amount: Long = 0,
    val timestamp: Long, // Block timestamp in seconds
    val encryptedNote: String,
    val decryptedText: String,
    val status: String // "SENT", "PENDING", "FAILED"
)

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY pinned DESC, nickname ASC")
    fun getAllContacts(): Flow<List<Contact>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: Contact)

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun deleteContactById(id: Int)

    @Query("DELETE FROM contacts WHERE address = :address")
    suspend fun deleteContactByAddress(address: String)

    @Query("SELECT * FROM contacts WHERE address = :address LIMIT 1")
    suspend fun getContactByAddress(address: String): Contact?

    @Query("UPDATE contacts SET pinned = :pinned WHERE address = :address")
    suspend fun updateContactPinned(address: String, pinned: Boolean)

    @Query("UPDATE contacts SET note = :note WHERE address = :address")
    suspend fun updateContactNote(address: String, note: String)

    @Query("UPDATE contacts SET nickname = :nickname, note = :note WHERE address = :address")
    suspend fun updateContactDetails(address: String, nickname: String, note: String)
}

data class PeerWithTimestamp(
    val peer: String,
    val max_ts: Long
)

@Dao
interface MessageDao {
    @Query("""
        SELECT peer, MAX(max_ts) AS max_ts FROM (
            SELECT sender AS peer, MAX(timestamp) AS max_ts FROM messages WHERE sender != :myAddr AND sender != 'MY_ADDRESS_PLACEHOLDER' GROUP BY sender
            UNION ALL
            SELECT receiver AS peer, MAX(timestamp) AS max_ts FROM messages WHERE receiver != :myAddr AND receiver != 'MY_ADDRESS_PLACEHOLDER' GROUP BY receiver
        ) GROUP BY peer ORDER BY max_ts DESC
    """)
    fun getAllMessagePeersWithTimestamp(myAddr: String): Flow<List<PeerWithTimestamp>>

    @Query("""
        SELECT peer FROM (
            SELECT sender AS peer, MAX(timestamp) AS max_ts FROM messages WHERE sender != :myAddr AND sender != 'MY_ADDRESS_PLACEHOLDER' GROUP BY sender
            UNION ALL
            SELECT receiver AS peer, MAX(timestamp) AS max_ts FROM messages WHERE receiver != :myAddr AND receiver != 'MY_ADDRESS_PLACEHOLDER' GROUP BY receiver
        ) GROUP BY peer ORDER BY MAX(max_ts) DESC
    """)
    fun getAllMessagePeers(myAddr: String): Flow<List<String>>

    @Query("SELECT * FROM messages WHERE (sender = :myAddr AND receiver = :peerAddr) OR (sender = :peerAddr AND receiver = :myAddr) ORDER BY timestamp ASC")
    fun getMessagesForChat(myAddr: String, peerAddr: String): Flow<List<LocalMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: LocalMessage)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<LocalMessage>)

    @Query("SELECT MAX(timestamp) FROM messages WHERE sender = :peerAddr AND receiver = :myAddr")
    suspend fun getLatestReceivedMessageTimestamp(myAddr: String, peerAddr: String): Long?

    @Query("SELECT MAX(timestamp) FROM messages WHERE sender = :myAddr OR receiver = :myAddr")
    suspend fun getLatestMessageTimestamp(myAddr: String): Long?

    @Query("DELETE FROM messages WHERE txId = :txId")
    suspend fun deleteMessageByTxId(txId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE txId = :txId")
    suspend fun getMessageCountByTxId(txId: String): Int
}

@Database(entities = [Contact::class, LocalMessage::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "algopriv_chat_db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class ChatRepository(private val db: AppDatabase) {
    val contacts: Flow<List<Contact>> = db.contactDao().getAllContacts()

    fun getMessagesForChat(myAddr: String, peerAddr: String): Flow<List<LocalMessage>> {
        return db.messageDao().getMessagesForChat(myAddr, peerAddr)
    }

    suspend fun insertContact(contact: Contact) {
        db.contactDao().insertContact(contact)
    }

    suspend fun deleteContactById(id: Int) {
        db.contactDao().deleteContactById(id)
    }

    suspend fun deleteContactByAddress(address: String) {
        db.contactDao().deleteContactByAddress(address)
    }

    suspend fun getContactByAddress(address: String): Contact? {
        return db.contactDao().getContactByAddress(address)
    }

    suspend fun updateContactPinned(address: String, pinned: Boolean) {
        db.contactDao().updateContactPinned(address, pinned)
    }

    suspend fun updateContactNote(address: String, note: String) {
        db.contactDao().updateContactNote(address, note)
    }

    suspend fun updateContactDetails(address: String, nickname: String, note: String) {
        db.contactDao().updateContactDetails(address, nickname, note)
    }

    suspend fun insertMessage(message: LocalMessage) {
        db.messageDao().insertMessage(message)
    }

    suspend fun insertMessages(messages: List<LocalMessage>) {
        db.messageDao().insertMessages(messages)
    }

    suspend fun getLatestMessageTimestamp(myAddr: String): Long {
        return db.messageDao().getLatestMessageTimestamp(myAddr) ?: 0L
    }

    suspend fun deleteMessageByTxId(txId: String) {
        db.messageDao().deleteMessageByTxId(txId)
    }

    suspend fun getMessageCountByTxId(txId: String): Int {
        return db.messageDao().getMessageCountByTxId(txId)
    }
}
