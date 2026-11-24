package org.fossify.messages.rosenweg

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Eigener Speicher des Rosenweg Messengers (rosenweg.db). Bewusst NICHT in
 * MessagesDatabase: die nutzt fallbackToDestructiveMigration und wuerde bei
 * einer Fossify-Schemaaenderung den ganzen Verlauf verwerfen. Hier gibt es nur
 * echte Migrationen (MIGRATIONEN); die Fossify-Tabellen sind nur ein Spiegel.
 */

/** Gespraech (Gruppe, Ankuendigung, Direktnachricht). seq ergibt die Thread-ID. */
@Entity(tableName = "chat", indices = [Index(value = ["id"], unique = true)])
data class RwChat(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "seq") val seq: Long = 0,
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "title") val title: String?,
    @ColumnInfo(name = "topic_prefix") val topicPrefix: String,
    @ColumnInfo(name = "metadata") val metadata: String?,
    @ColumnInfo(name = "role") val role: String?,
    /** JSON-Liste der Mitglieder-IDs (aus /members bzw. metadata.participants) */
    @ColumnInfo(name = "members") val members: String?,
    /** hoechste bekannte Server-ID (fuer /api/history?since=) */
    @ColumnInfo(name = "last_server_id") val lastServerId: Long = 0,
    @ColumnInfo(name = "can_write") val canWrite: Boolean = true,
    @ColumnInfo(name = "thread_id") val threadId: Long = 0,
    /** eigenes Logo (metadata.avatar) als data:-URL, null = Standardlogo */
    @ColumnInfo(name = "avatar") val avatar: String? = null,
)

/** Eigener Zustand ausgehender Nachrichten */
object RwStatus {
    const val EMPFANGEN = 0
    const val AUSSTEHEND = 1
    const val GESENDET = 2
    const val FEHLER = 3
}

@Entity(
    tableName = "rwmessage",
    indices = [
        Index(value = ["chat_id", "client_msg_id"], unique = true),
        Index(value = ["chat_id", "ts"]),
        Index(value = ["server_id"]),
    ]
)
data class RwMessage(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "row_id") val rowId: Long = 0,
    @ColumnInfo(name = "server_id") val serverId: Long?,
    @ColumnInfo(name = "client_msg_id") val clientMsgId: String?,
    @ColumnInfo(name = "chat_id") val chatId: String,
    @ColumnInfo(name = "sender_id") val senderId: String,
    @ColumnInfo(name = "body") val body: String?,
    @ColumnInfo(name = "ciphertext") val ciphertext: String?,
    @ColumnInfo(name = "ts") val ts: Long,
    @ColumnInfo(name = "edited_at") val editedAt: Long?,
    @ColumnInfo(name = "deleted") val deleted: Boolean,
    @ColumnInfo(name = "reactions") val reactions: String?,
    @ColumnInfo(name = "reply_preview") val replyPreview: String?,
    @ColumnInfo(name = "media_name") val mediaName: String?,
    @ColumnInfo(name = "status") val status: Int,
    @ColumnInfo(name = "read") val read: Boolean,
    @ColumnInfo(name = "mirrored_message_id") val mirroredMessageId: Long = 0,
    // Quittungen: fuer fremde Nachrichten was wir senden sollen/gesendet haben (1 zugestellt, 2 gelesen)
    @ColumnInfo(name = "rcpt_soll", defaultValue = "0") val rcptSoll: Int = 0,
    @ColumnInfo(name = "rcpt_gesendet", defaultValue = "0") val rcptGesendet: Int = 0,
    // fuer eigene Nachrichten: wer zugestellt/gelesen hat (JSON-Listen) und Zahlen aus dem Verlauf
    @ColumnInfo(name = "rcpt_zugestellt") val rcptZugestellt: String? = null,
    @ColumnInfo(name = "rcpt_gelesen") val rcptGelesen: String? = null,
    @ColumnInfo(name = "rcpt_n_zugestellt", defaultValue = "0") val rcptNZugestellt: Int = 0,
    @ColumnInfo(name = "rcpt_n_gelesen", defaultValue = "0") val rcptNGelesen: Int = 0,
    @ColumnInfo(name = "rcpt_von") val rcptVon: Int? = null,
)

@Entity(tableName = "user")
data class RwUser(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    /** data:-URL oder URL aus /api/directory */
    @ColumnInfo(name = "avatar") val avatar: String? = null,
)

@Dao
interface RosenwegDao {
    @Query("SELECT * FROM chat")
    fun chats(): List<RwChat>

    @Query("SELECT * FROM chat WHERE id = :id")
    fun chat(id: String): RwChat?

    @Query("SELECT * FROM chat WHERE thread_id = :threadId")
    fun chatZuThread(threadId: Long): RwChat?

    @Query("SELECT * FROM chat WHERE topic_prefix = :prefix")
    fun chatZuPrefix(prefix: String): RwChat?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun neuerChat(chat: RwChat): Long

    @Update
    fun aendereChat(chat: RwChat)

    @Query("DELETE FROM chat WHERE id = :id")
    fun loescheChat(id: String)

    @Query("UPDATE chat SET last_server_id = :id WHERE id = :chatId AND last_server_id < :id")
    fun setzeLastServerId(chatId: String, id: Long)

    @Query("SELECT * FROM rwmessage WHERE chat_id = :chatId ORDER BY ts, row_id")
    fun nachrichten(chatId: String): List<RwMessage>

    @Query("SELECT * FROM rwmessage WHERE chat_id = :chatId AND client_msg_id = :clientMsgId")
    fun nachricht(chatId: String, clientMsgId: String): RwMessage?

    @Query("SELECT * FROM rwmessage WHERE chat_id = :chatId AND server_id = :serverId")
    fun nachrichtZuServerId(chatId: String, serverId: Long): RwMessage?

    @Query("SELECT * FROM rwmessage WHERE row_id = :rowId")
    fun nachrichtZuZeile(rowId: Long): RwMessage?

    @Query("SELECT * FROM rwmessage WHERE rcpt_soll > rcpt_gesendet AND client_msg_id IS NOT NULL ORDER BY ts LIMIT 500")
    fun offeneQuittungen(): List<RwMessage>

    @Query("UPDATE rwmessage SET rcpt_gesendet = :stufe WHERE row_id = :rowId AND rcpt_gesendet < :stufe")
    fun quittungGesendet(rowId: Long, stufe: Int)

    @Query("UPDATE rwmessage SET rcpt_soll = 2 WHERE chat_id = :chatId AND sender_id != :ich AND client_msg_id IS NOT NULL AND rcpt_soll < 2")
    fun quittungGelesenSoll(chatId: String, ich: String): Int

    @Query("SELECT * FROM rwmessage WHERE status = 1 ORDER BY ts")
    fun ausstehende(): List<RwMessage>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun neueNachricht(m: RwMessage): Long

    @Update
    fun aendereNachricht(m: RwMessage)

    @Query("DELETE FROM rwmessage WHERE row_id = :rowId")
    fun loescheNachricht(rowId: Long)

    @Query("DELETE FROM rwmessage WHERE chat_id = :chatId")
    fun loescheChatNachrichten(chatId: String)

    @Query("UPDATE rwmessage SET read = 1 WHERE chat_id = :chatId")
    fun markiereGelesen(chatId: String)

    @Query("SELECT * FROM user")
    fun benutzer(): List<RwUser>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun speichereBenutzer(users: List<RwUser>)
}

@Database(entities = [RwChat::class, RwMessage::class, RwUser::class], version = 2, exportSchema = true)
abstract class RosenwegDatenbank : RoomDatabase() {
    abstract fun dao(): RosenwegDao

    companion object {
        @Volatile
        private var instanz: RosenwegDatenbank? = null

        /** Kuenftige Schemaaenderungen hier als echte Migration ergaenzen (kein destruktiver Rueckfall). */
        private val MIGRATIONEN = arrayOf<Migration>(
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE chat ADD COLUMN avatar TEXT")
                    db.execSQL("ALTER TABLE user ADD COLUMN avatar TEXT")
                    db.execSQL("ALTER TABLE rwmessage ADD COLUMN rcpt_soll INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE rwmessage ADD COLUMN rcpt_gesendet INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE rwmessage ADD COLUMN rcpt_zugestellt TEXT")
                    db.execSQL("ALTER TABLE rwmessage ADD COLUMN rcpt_gelesen TEXT")
                    db.execSQL("ALTER TABLE rwmessage ADD COLUMN rcpt_n_zugestellt INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE rwmessage ADD COLUMN rcpt_n_gelesen INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE rwmessage ADD COLUMN rcpt_von INTEGER")
                    // Bestehender Verlauf: keine nachtraegliche Quittungs-Flut
                    db.execSQL("UPDATE rwmessage SET rcpt_soll = 2, rcpt_gesendet = 2")
                }
            },
        )

        fun get(context: Context): RosenwegDatenbank =
            instanz ?: synchronized(this) {
                instanz ?: Room.databaseBuilder(context.applicationContext, RosenwegDatenbank::class.java, "rosenweg.db")
                    .addMigrations(*MIGRATIONEN)
                    .build()
                    .also { instanz = it }
            }
    }
}
