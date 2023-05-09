package money.tegro.bot.receipts

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import money.tegro.bot.exceptions.InvalidRecipientException
import money.tegro.bot.exceptions.ReceiptIssuerActivationException
import money.tegro.bot.exceptions.ReceiptNotActiveException
import money.tegro.bot.exceptions.UnknownReceiptException
import money.tegro.bot.objects.PostgresUserPersistent
import money.tegro.bot.objects.User
import money.tegro.bot.receipts.PostgresReceiptPersistent.UsersReceipts.activations
import money.tegro.bot.receipts.PostgresReceiptPersistent.UsersReceipts.amount
import money.tegro.bot.receipts.PostgresReceiptPersistent.UsersReceipts.currency
import money.tegro.bot.receipts.PostgresReceiptPersistent.UsersReceipts.isActive
import money.tegro.bot.receipts.PostgresReceiptPersistent.UsersReceipts.issueTime
import money.tegro.bot.receipts.PostgresReceiptPersistent.UsersReceipts.issuerId
import money.tegro.bot.receipts.PostgresReceiptPersistent.UsersReceipts.recipientId
import money.tegro.bot.receipts.PostgresReceiptPersistent.UsersReceiptsChats.receiptId
import money.tegro.bot.utils.JSON
import money.tegro.bot.wallet.Coins
import money.tegro.bot.wallet.CryptoCurrency
import money.tegro.bot.walletPersistent
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.experimental.suspendedTransactionAsync
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.util.*

interface ReceiptPersistent {
    suspend fun createReceipt(
        issuer: User,
        coins: Coins,
        activations: Int,
        recipient: User? = null
    ): Receipt

    suspend fun activateReceipt(
        receipt: Receipt,
        recipient: User
    )

    suspend fun loadReceipts(user: User): ReceiptCollection

    suspend fun loadActivations(receipt: Receipt): List<UUID>

    suspend fun inactivateReceipt(receipt: Receipt)

    suspend fun addChatToReceipt(receipt: Receipt, chatId: Long)

    suspend fun deleteChatFromReceipt(receipt: Receipt, chatId: Long)

    suspend fun getChatsByReceipt(receipt: Receipt): List<Long>
}

object PostgresReceiptPersistent : ReceiptPersistent {

    object UsersReceipts : UUIDTable("users_receipts") {
        val issueTime = timestamp("issue_time")
        val issuerId = uuid("issuer_id").references(PostgresUserPersistent.Users.id)
        val currency = enumeration<CryptoCurrency>("currency")
        val amount = long("amount")
        val activations = integer("activations")
        val recipientId = uuid("recipient_id").references(PostgresUserPersistent.Users.id).nullable()
        val isActive = bool("is_active")

        init {
            transaction { SchemaUtils.create(this@UsersReceipts) }
        }
    }

    object UsersReceiptsChats : Table("users_receipts_chats") {
        val receiptId = uuid("receipt_id").references(UsersReceipts.id)
        val chatId = long("chat_id")

        init {
            transaction { SchemaUtils.create(this@UsersReceiptsChats) }
        }
    }

    object UsersReceiptsActivations : Table("users_receipts_activations") {
        val receiptId = uuid("receipt_id").references(UsersReceipts.id)
        val userId = uuid("user_id").references(PostgresUserPersistent.Users.id)

        init {
            transaction { SchemaUtils.create(this@UsersReceiptsActivations) }
        }
    }

    override suspend fun createReceipt(issuer: User, coins: Coins, activations: Int, recipient: User?): Receipt {
        val toFreeze = Coins(coins.currency, coins.amount * activations.toBigInteger())
        walletPersistent.freeze(issuer, toFreeze)
        try {
            val receipt = Receipt(
                id = UUID.randomUUID(),
                issueTime = Clock.System.now(),
                issuer = issuer,
                coins = coins,
                activations = activations,
                recipient = recipient,
            )
            saveReceipt(receipt)
            return receipt
        } catch (e: Throwable) {
            walletPersistent.unfreeze(issuer, coins)
            throw e
        }
    }

    fun saveReceipt(receipt: Receipt) {
        transaction {
            //addLogger(StdOutSqlLogger)

            exec(
                """
                    INSERT INTO users_receipts (id,issue_time,issuer_id, currency, amount, activations, recipient_id, is_active) 
                    values (?,?,?,?,?,?,?,?)
                    ON CONFLICT (id) DO UPDATE SET issue_time=?, issuer_id=?, currency=?, amount=?, activations=?,
                    recipient_id=?, is_active=?
                    """, args = listOf(
                    UsersReceipts.id.columnType to receipt.id,
                    issueTime.columnType to receipt.issueTime,
                    issuerId.columnType to receipt.issuer.id,
                    currency.columnType to receipt.coins.currency,
                    amount.columnType to receipt.coins.amount,
                    activations.columnType to receipt.activations,
                    recipientId.columnType to receipt.recipient?.id,
                    isActive.columnType to receipt.isActive,

                    issueTime.columnType to receipt.issueTime,
                    issuerId.columnType to receipt.issuer.id,
                    currency.columnType to receipt.coins.currency,
                    amount.columnType to receipt.coins.amount,
                    activations.columnType to receipt.activations,
                    recipientId.columnType to receipt.recipient?.id,
                    isActive.columnType to receipt.isActive,
                )
            )
        }
    }

    suspend fun deleteReceipt(receipt: Receipt) {
        inactivateReceipt(receipt)
        val toUnfreeze = Coins(receipt.coins.currency, receipt.coins.amount * receipt.activations.toBigInteger())
        walletPersistent.unfreeze(receipt.issuer, toUnfreeze)
    }

    override suspend fun inactivateReceipt(receipt: Receipt) {
        transaction {
            UsersReceipts.update({ UsersReceipts.id eq receipt.id }) {
                it[isActive] = false
            }
        }
    }

    override suspend fun addChatToReceipt(receipt: Receipt, chatId: Long) {
        transaction {
            UsersReceiptsChats.insert {
                it[receiptId] = receipt.id
                it[UsersReceiptsChats.chatId] = chatId
            }
        }
    }

    override suspend fun deleteChatFromReceipt(receipt: Receipt, chatId: Long) {
        transaction {
            UsersReceiptsChats.deleteWhere { receiptId.eq(receipt.id) and UsersReceiptsChats.chatId.eq(chatId) }
        }
    }

    override suspend fun getChatsByReceipt(receipt: Receipt): List<Long> {
        val chats = suspendedTransactionAsync {
            UsersReceiptsChats.select {
                receiptId.eq(receipt.id)
            }.mapNotNull {
                it[UsersReceiptsChats.chatId]
            }
        }
        return chats.await()
    }

    override suspend fun activateReceipt(receipt: Receipt, recipient: User) {
        val receipts = loadReceipts(receipt.issuer).toMutableList()
        val currentReceipt = receipts.find { it.id == receipt.id } ?: throw UnknownReceiptException(receipt)
        val activations = loadActivations(receipt)

        if (currentReceipt.recipient != null && currentReceipt.recipient != recipient) {
            throw InvalidRecipientException(receipt, recipient)
        }
        if (currentReceipt.issuer == recipient) {
            throw ReceiptIssuerActivationException(receipt)
        }
        if (activations.contains(recipient.id)) {
            throw ReceiptNotActiveException(receipt)
        }
        if (!currentReceipt.isActive || currentReceipt.activations < 1) {
            throw ReceiptNotActiveException(receipt)
        }
        receipt.issuer.transfer(recipient, receipt.coins)
        var currentActivations = receipt.activations
        currentActivations--
        if (currentActivations < 1) {
            inactivateReceipt(receipt)
        } else {
            transaction {
                UsersReceipts.update({ UsersReceipts.id eq receipt.id }) {
                    it[UsersReceipts.activations] = currentActivations
                }
                UsersReceiptsActivations.insert {
                    it[receiptId] = receipt.id
                    it[userId] = recipient.id
                }
            }
        }
    }

    suspend fun loadReceipt(receiptId: UUID): Receipt? {
        val receipt = suspendedTransactionAsync {
            val result = UsersReceipts.select {
                UsersReceipts.id.eq(receiptId)
            }.firstOrNull() ?: return@suspendedTransactionAsync null
            val issuer = PostgresUserPersistent.load(result[issuerId]) ?: return@suspendedTransactionAsync null
            val recipient = result[recipientId]?.let { uuid -> PostgresUserPersistent.load(uuid) }
            Receipt(
                id = result[UsersReceipts.id].value,
                issueTime = result[issueTime],
                issuer = issuer,
                coins = Coins(
                    currency = result[currency],
                    amount = result[amount].toBigInteger()
                ),
                activations = result[activations],
                recipient = recipient,
                isActive = result[isActive]
            )
        }
        return receipt.await()
    }

    override suspend fun loadReceipts(user: User): ReceiptCollection {
        val receipts = suspendedTransactionAsync {
            UsersReceipts.select {
                issuerId.eq(user.id)
            }.mapNotNull {
                val issuer = PostgresUserPersistent.load(it[issuerId]) ?: return@mapNotNull null
                val recipient = it[recipientId]?.let { uuid -> PostgresUserPersistent.load(uuid) }
                Receipt(
                    id = it[UsersReceipts.id].value,
                    issueTime = it[issueTime],
                    issuer = issuer,
                    coins = Coins(
                        currency = it[currency],
                        amount = it[amount].toBigInteger()
                    ),
                    activations = it[activations],
                    recipient = recipient,
                    isActive = it[isActive]
                )
            }
        }
        return ReceiptCollection(receipts = receipts.await())
    }

    override suspend fun loadActivations(receipt: Receipt): List<UUID> {
        val activations = suspendedTransactionAsync {
            UsersReceiptsActivations.select {
                UsersReceiptsActivations.receiptId.eq(receipt.id)
            }.map {
                it[UsersReceiptsActivations.userId]
            }
        }
        return activations.await()
    }
}

class JsonReceiptPersistent(
    val file: File
) : ReceiptPersistent {
    init {
        if (file.parentFile != null && !file.parentFile.exists()) file.parentFile.mkdirs()
        if (!file.exists()) {
            file.createNewFile()
            file.writeText("{}")
        }
    }

    private val fileLock = reentrantLock()

    override suspend fun createReceipt(issuer: User, coins: Coins, activations: Int, recipient: User?): Receipt {
        fileLock.withLock {
            walletPersistent.freeze(issuer, coins)
            try {
                val receipt = Receipt(
                    id = UUID.randomUUID(),
                    issueTime = Clock.System.now(),
                    issuer = issuer,
                    coins = coins,
                    activations = activations,
                    recipient = recipient
                )
                val receipts = loadReceiptsUnsafe(issuer).toMutableList()
                receipts.add(receipt)
                saveReceiptsUnsafe(issuer, ReceiptCollection(receipts))
                return receipt
            } catch (e: Throwable) {
                walletPersistent.unfreeze(issuer, coins)
                throw e
            }
        }
    }

    override suspend fun activateReceipt(receipt: Receipt, recipient: User) {
        fileLock.withLock {
            val receipts = loadReceiptsUnsafe(receipt.issuer).toMutableList()
            val currentReceipt = receipts.find { it.id == receipt.id } ?: throw UnknownReceiptException(receipt)

            if (currentReceipt.recipient != null && currentReceipt.recipient != recipient) {
                throw InvalidRecipientException(receipt, recipient)
            }
            if (currentReceipt.issuer == recipient) {
                throw ReceiptIssuerActivationException(receipt)
            }
            receipt.issuer.transfer(recipient, receipt.coins)
            receipts.remove(receipt)
            saveReceiptsUnsafe(receipt.issuer, ReceiptCollection(receipts))
        }
    }

    override suspend fun loadReceipts(user: User): ReceiptCollection {
        fileLock.withLock {
            return loadReceiptsUnsafe(user)
        }
    }

    override suspend fun loadActivations(receipt: Receipt): List<UUID> {
        TODO("Not yet implemented")
    }

    override suspend fun inactivateReceipt(receipt: Receipt) {
        val receipts = loadReceipts(receipt.issuer).toMutableList()
        receipts.remove(receipt)
        saveReceiptsUnsafe(receipt.issuer, ReceiptCollection(receipts))
    }

    override suspend fun addChatToReceipt(receipt: Receipt, chatId: Long) {
        TODO("Not yet implemented")
    }

    override suspend fun deleteChatFromReceipt(receipt: Receipt, chatId: Long) {
        TODO("Not yet implemented")
    }

    override suspend fun getChatsByReceipt(receipt: Receipt): List<Long> {
        TODO("Not yet implemented")
    }

    private fun loadReceiptsUnsafe(user: User): ReceiptCollection {
        val map = loadMap()
        return map[user.id] ?: ReceiptCollection(emptyList())
    }

    private fun saveReceiptsUnsafe(user: User, receipts: ReceiptCollection) {
        val map = loadMap().toMutableMap()
        map[user.id] = receipts
        saveMap(map)
    }

    private fun loadMap() =
        JSON.decodeFromString<Map<UUID, ReceiptCollection>>(file.readText())

    private fun saveMap(map: Map<UUID, ReceiptCollection>) = file.writeText(
        JSON.encodeToString(map)
    )
}