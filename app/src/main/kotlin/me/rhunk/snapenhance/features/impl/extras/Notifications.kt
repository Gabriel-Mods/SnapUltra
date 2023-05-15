package me.rhunk.snapenhance.features.impl.extras

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.UserHandle
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import me.rhunk.snapenhance.Constants
import me.rhunk.snapenhance.Logger
import me.rhunk.snapenhance.config.ConfigProperty
import me.rhunk.snapenhance.data.ContentType
import me.rhunk.snapenhance.data.MediaReferenceType
import me.rhunk.snapenhance.data.wrapper.impl.Message
import me.rhunk.snapenhance.data.wrapper.impl.SnapUUID
import me.rhunk.snapenhance.features.Feature
import me.rhunk.snapenhance.features.FeatureLoadParams
import me.rhunk.snapenhance.features.impl.Messaging
import me.rhunk.snapenhance.hook.HookStage
import me.rhunk.snapenhance.hook.Hooker
import me.rhunk.snapenhance.util.CallbackBuilder
import me.rhunk.snapenhance.util.EncryptionUtils
import me.rhunk.snapenhance.util.PreviewUtils
import me.rhunk.snapenhance.util.download.CdnDownloader
import me.rhunk.snapenhance.util.protobuf.ProtoReader

class Notifications : Feature("Notifications", loadParams = FeatureLoadParams.INIT_SYNC) {
    private val notificationDataQueue = mutableMapOf<Long, NotificationData>()
    private val cachedNotifications = mutableMapOf<String, MutableList<String>>()

    private val notifyAsUserMethod by lazy {
        XposedHelpers.findMethodExact(
            NotificationManager::class.java, "notifyAsUser",
            String::class.java,
            Int::class.javaPrimitiveType,
            Notification::class.java,
            UserHandle::class.java
        )
    }

    private val fetchConversationWithMessagesMethod by lazy {
        context.classCache.conversationManager.methods.first { it.name == "fetchConversationWithMessages"}
    }

    private val notificationManager by lazy {
        context.androidContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private fun setNotificationText(notification: NotificationData, text: String) {
        with(notification.notification.extras) {
            putString("android.text", text)
            putString("android.bigText", text)
        }
    }

    private fun computeNotificationText(conversationId: String): String {
        val messageBuilder = StringBuilder()
        cachedNotifications.computeIfAbsent(conversationId) { mutableListOf() }.forEach {
            if (messageBuilder.isNotEmpty()) messageBuilder.append("\n")
            messageBuilder.append(it)
        }
        return messageBuilder.toString()
    }

    private fun fetchMessagesResult(conversationId: String, messages: List<Message>) {
        val sendNotificationData = { it: NotificationData ->
            XposedBridge.invokeOriginalMethod(notifyAsUserMethod, notificationManager, arrayOf(
                it.tag, it.id, it.notification, it.userHandle
            ))
        }

        notificationDataQueue.entries.onEach { (messageId, notificationData) ->
            val snapMessage = messages.firstOrNull { message -> message.orderKey == messageId } ?: return
            val senderUsername = context.database.getFriendInfo(snapMessage.senderId.toString())?.displayName ?: throw Throwable("Cant find senderId of message $snapMessage")

            val contentType = snapMessage.messageContent.contentType
            val contentData = snapMessage.messageContent.content

            val formatUsername: (String) -> String = { "$senderUsername: $it" }
            val notificationCache = cachedNotifications.let { it.computeIfAbsent(conversationId) { mutableListOf() } }
            val appendNotifications: () -> Unit = { setNotificationText(notificationData, computeNotificationText(conversationId))}

            when (contentType) {
                ContentType.NOTE -> {
                    notificationCache.add(formatUsername("sent audio note"))
                    appendNotifications()
                }
                ContentType.CHAT -> {
                    ProtoReader(contentData).getString(2, 1)?.trim()?.let {
                        notificationCache.add(formatUsername(it))
                    }
                    appendNotifications()
                }
                ContentType.SNAP -> {
                    //serialize the message content into a json object
                    val serializedMessageContent = context.gson.toJsonTree(snapMessage.messageContent.instance()).asJsonObject
                    val mediaReferences = serializedMessageContent["mRemoteMediaReferences"]
                        .asJsonArray.map { it.asJsonObject["mMediaReferences"].asJsonArray }
                        .flatten()

                    mediaReferences.forEach { media ->
                        val mediaContent = media.asJsonObject["mContentObject"].asJsonArray.map { it.asByte }.toByteArray()
                        val mediaType = MediaReferenceType.valueOf(media.asJsonObject["mMediaType"].asString)
                        val urlKey = ProtoReader(mediaContent).getString(2, 2) ?: return@forEach
                        runCatching {
                            //download the media
                            var mediaInputStream = CdnDownloader.downloadWithDefaultEndpoints(urlKey)!!
                            val mediaInfo = ProtoReader(contentData).readPath(*Constants.MESSAGE_SNAP_ENCRYPTION_PROTO_PATH) ?: return@runCatching
                            //decrypt if necessary
                            if (mediaInfo.exists(Constants.ARROYO_ENCRYPTION_PROTO_INDEX)) {
                                mediaInputStream = EncryptionUtils.decryptInputStream(mediaInputStream, false, mediaInfo, Constants.ARROYO_ENCRYPTION_PROTO_INDEX)
                            }

                            val mediaByteArray = mediaInputStream.readBytes()
                            val bitmapPreview = PreviewUtils.createPreview(mediaByteArray, mediaType == MediaReferenceType.VIDEO)!!

                            val notificationBuilder = XposedHelpers.newInstance(
                                Notification.Builder::class.java,
                                context.androidContext,
                                notificationData.notification
                            ) as Notification.Builder
                            notificationBuilder.setLargeIcon(bitmapPreview)
                            notificationBuilder.style = Notification.BigPictureStyle().bigPicture(bitmapPreview).bigLargeIcon(null as Bitmap?)

                            sendNotificationData(notificationData.copy(id = System.nanoTime().toInt(), notification = notificationBuilder.build()))
                            return@onEach
                        }.onFailure {
                            Logger.xposedLog("Failed to send preview notification", it)
                        }
                    }
                }
                else -> {
                    notificationCache.add(formatUsername("sent $contentType"))
                }
            }

            sendNotificationData(notificationData)
        }.clear()
    }

    override fun init() {
        val fetchConversationWithMessagesCallback = context.mappings.getMappedClass("callbacks", "FetchConversationWithMessagesCallback")

        Hooker.hook(notifyAsUserMethod, HookStage.BEFORE, { context.config.bool(ConfigProperty.SHOW_MESSAGE_CONTENT) }) {
            val notificationData = NotificationData(it.argNullable(0), it.arg(1), it.arg(2), it.arg(3))

            if (!notificationData.notification.extras.containsKey("system_notification_extras")) {
                return@hook
            }
            val extras: Bundle = notificationData.notification.extras.getBundle("system_notification_extras")!!

            val messageId = extras.getString("message_id")!!
            val notificationType = extras.getString("notification_type")!!
            val conversationId = extras.getString("conversation_id")!!

            if (!notificationType.endsWith("CHAT") && !notificationType.endsWith("SNAP")) return@hook

            val conversationManager: Any = context.feature(Messaging::class).conversationManager
            notificationDataQueue[messageId.toLong()] =  notificationData

            val callback = CallbackBuilder(fetchConversationWithMessagesCallback)
                .override("onFetchConversationWithMessagesComplete") { param ->
                    val messageList = (param.arg(1) as List<Any>).map { msg -> Message(msg) }
                    fetchMessagesResult(conversationId, messageList)
                }
                .override("onError") { param ->
                    Logger.xposedLog("Failed to fetch message ${param.arg(0) as Any}")
                }.build()

            fetchConversationWithMessagesMethod.invoke(conversationManager, SnapUUID.fromString(conversationId).instance(), callback)
            it.setResult(null)
        }
    }

    data class NotificationData(
        val tag: String?,
        val id: Int,
        var notification: Notification,
        val userHandle: UserHandle
    )
}