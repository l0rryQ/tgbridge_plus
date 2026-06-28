package dev.vanutp.tgbridge.common

import dev.vanutp.tgbridge.common.ConfigManager.config
import dev.vanutp.tgbridge.common.ConfigManager.lang
import dev.vanutp.tgbridge.common.models.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

object AuthService {
    private val yaml = com.charleskorn.kaml.Yaml(
        configuration = com.charleskorn.kaml.YamlConfiguration(
            encodeDefaults = false,
            strictMode = false
        )
    )
    private lateinit var filePath: Path
    private lateinit var logger: ILogger

    @Serializable
    data class AuthData(
        val nicknameToTgId: MutableMap<String, Long> = mutableMapOf(),
        val tgIdToIps: MutableMap<Long, MutableSet<String>> = mutableMapOf()
    )

    private var authData = AuthData()
    private val pendingCodes = ConcurrentHashMap<String, PendingCode>()
    private val pendingIpConfirmations = ConcurrentHashMap<String, PendingIpConfirmation>()

    data class PendingCode(
        val nickname: String,
        val code: String,
        val expiresAt: Instant
    )

    data class PendingIpConfirmation(
        val nickname: String,
        val ip: String,
        val tgId: Long,
        var approved: Boolean = false,
        val expiresAt: Instant
    )

    fun init(logger: ILogger, configPath: Path) {
        this.logger = logger
        this.filePath = configPath.resolve("tg_auth.yml")
        load()

        TelegramBridge.INSTANCE.coroutineScope.launch {
            while (true) {
                val now = Instant.now()
                pendingCodes.entries.removeIf { it.value.expiresAt.isBefore(now) }
                pendingIpConfirmations.entries.removeIf { it.value.expiresAt.isBefore(now) }
                delay(60000)
            }
        }

        TelegramBridge.INSTANCE.bot.registerMessageHandler { msg ->
            if (msg.chat.type == dev.vanutp.tgbridge.common.TgChatType.PRIVATE) {
                TelegramBridge.INSTANCE.coroutineScope.launch {
                    handlePrivateMessage(msg)
                }
            }
        }

        TelegramBridge.INSTANCE.bot.registerCallbackHandler { cb ->
            TelegramBridge.INSTANCE.coroutineScope.launch {
                handleCallbackQuery(cb)
            }
            true
        }
    }

    private fun load() {
        if (!Files.exists(filePath)) return
        try {
            val content = Files.newBufferedReader(filePath).use { it.readText() }
            authData = yaml.decodeFromString(content)
        } catch (e: Exception) {
            logger.error("Failed to load auth data: ${e.message}", e)
        }
    }

    private fun save() {
        try {
            Files.createDirectories(filePath.parent)
            Files.newBufferedWriter(filePath).use { it.write(yaml.encodeToString(authData)) }
        } catch (e: Exception) {
            logger.error("Failed to save auth data: ${e.message}", e)
        }
    }

    fun getOrGenerateCode(nickname: String): String {
        val existing = pendingCodes.values.find { it.nickname.equals(nickname, true) }
        if (existing != null && existing.expiresAt.isAfter(Instant.now())) {
            return existing.code
        }
        val code = (100000 + Random.nextInt(900000)).toString()
        pendingCodes[code] = PendingCode(
            nickname,
            code,
            Instant.now().plusSeconds(config.telegramAuth.codeExpirationMinutes * 60L)
        )
        return code
    }

    fun getTgId(nickname: String): Long? = authData.nicknameToTgId[nickname.lowercase()]

    fun isIpKnown(tgId: Long, ip: String): Boolean {
        if (!config.telegramAuth.ipConfirmation) return true
        return authData.tgIdToIps[tgId]?.contains(ip) ?: false
    }

    suspend fun requestIpConfirmation(nickname: String, tgId: Long, ip: String) {
        val key = "$tgId:$ip"
        if (pendingIpConfirmations.containsKey(key)) return

        pendingIpConfirmations[key] = PendingIpConfirmation(
            nickname,
            ip,
            tgId,
            expiresAt = Instant.now().plusSeconds(config.telegramAuth.codeExpirationMinutes * 60L)
        )

        TelegramBridge.INSTANCE.bot.sendMessage(
            tgId,
            lang.telegramAuth.botIpConfirm.formatLang(Placeholders(mapOf("ip" to ip))),
            parseMode = "HTML",
            replyMarkup = TgReplyMarkup(
                inlineKeyboard = listOf(
                    listOf(
                        TgInlineKeyboardButton(lang.telegramAuth.botIpApprove, "ip_approve:$key"),
                        TgInlineKeyboardButton(lang.telegramAuth.botIpDeny, "ip_deny:$key")
                    )
                )
            )
        )
    }

    fun isIpConfirmationPending(tgId: Long, ip: String): Boolean {
        return pendingIpConfirmations.containsKey("$tgId:$ip")
    }

    suspend fun isInGroup(tgId: Long): Boolean {
        return try {
            val member = TelegramBridge.INSTANCE.bot.getChatMember(config.telegramAuth.groupUsername, tgId)
            member.status in listOf(
                TgChatMemberStatus.MEMBER,
                TgChatMemberStatus.ADMINISTRATOR,
                TgChatMemberStatus.CREATOR,
                TgChatMemberStatus.RESTRICTED
            )
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun handleCallbackQuery(cb: TgCallbackQuery) {
        val data = cb.data ?: return
        val tgId = cb.from.id
        val callbackQueryId = cb.id

        if (data.startsWith("ip_approve:") || data.startsWith("ip_deny:")) {
            val key = data.substringAfter(":")
            val pending = pendingIpConfirmations[key]

            if (pending == null || pending.tgId != tgId) {
                TelegramBridge.INSTANCE.bot.answerCallbackQuery(callbackQueryId, lang.telegramAuth.botCodeInvalid)
                return
            }

            if (data.startsWith("ip_approve:")) {
                authData.tgIdToIps.getOrPut(tgId) { mutableSetOf() }.add(pending.ip)
                save()
                pendingIpConfirmations.remove(key)

                TelegramBridge.INSTANCE.bot.answerCallbackQuery(callbackQueryId, "Approved")
                cb.message?.let {
                    TelegramBridge.INSTANCE.bot.editMessageText(
                        tgId,
                        it.messageId,
                        lang.telegramAuth.botIpApproved.formatLang(Placeholders(mapOf("ip" to pending.ip))),
                        parseMode = "HTML"
                    )
                }
            } else {
                pendingIpConfirmations.remove(key)
                TelegramBridge.INSTANCE.bot.answerCallbackQuery(callbackQueryId, "Denied")
                cb.message?.let {
                    TelegramBridge.INSTANCE.bot.editMessageText(
                        tgId,
                        it.messageId,
                        lang.telegramAuth.botIpDenied.formatLang(Placeholders(mapOf("ip" to pending.ip))),
                        parseMode = "HTML"
                    )
                }
            }
        }
    }

    private suspend fun handlePrivateMessage(msg: TgMessage) {
        val text = msg.text?.trim() ?: return
        val tgId = msg.from?.id ?: return

        if (text == "/start") {
            TelegramBridge.INSTANCE.bot.sendMessage(
                tgId,
                lang.telegramAuth.botStart.formatLang(Placeholders(mapOf("group_username" to config.telegramAuth.groupUsername))),
                parseMode = "HTML"
            )
            return
        }

        val pending = pendingCodes[text]
        if (pending != null) {
            val nickname = pending.nickname
            authData.nicknameToTgId[nickname.lowercase()] = tgId
            save()
            pendingCodes.remove(text)

            TelegramBridge.INSTANCE.bot.sendMessage(
                tgId,
                lang.telegramAuth.botSuccess.formatLang(Placeholders(mapOf("username" to nickname))),
                parseMode = "HTML"
            )

            if (!isInGroup(tgId)) {
                TelegramBridge.INSTANCE.bot.sendMessage(
                    tgId,
                    lang.telegramAuth.botNotInGroup.formatLang(Placeholders(mapOf("group_username" to config.telegramAuth.groupUsername))),
                    parseMode = "HTML"
                )
            }
        } else {
            // Check if already linked
            val linkedNickname = authData.nicknameToTgId.entries.find { it.value == tgId }?.key
            if (linkedNickname != null) {
                TelegramBridge.INSTANCE.bot.sendMessage(
                    tgId,
                    lang.telegramAuth.botAlreadyLinked.formatLang(Placeholders(mapOf("username" to linkedNickname))),
                    parseMode = "HTML"
                )
            } else {
                TelegramBridge.INSTANCE.bot.sendMessage(tgId, lang.telegramAuth.botCodeInvalid)
            }
        }
    }

    suspend fun logModeration(type: String, player: String, admin: String, action: String, reason: String) {
        val chat = config.getChat(config.telegramAuth.moderationChat) ?: config.getDefaultChat()
        val text = lang.telegramAuth.moderationLog.formatLang(
            Placeholders(
                mapOf(
                    "type" to type,
                    "username" to player,
                    "admin" to admin,
                    "action" to action,
                    "reason" to reason
                )
            )
        )
        TelegramBridge.INSTANCE.chatManager.sendMessage(
            chat,
            dev.vanutp.tgbridge.common.models.MessageContentHTMLText(text)
        )
    }
}
