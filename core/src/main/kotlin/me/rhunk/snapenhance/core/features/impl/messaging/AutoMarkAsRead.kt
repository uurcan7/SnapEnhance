package me.rhunk.snapenhance.core.features.impl.messaging

import me.rhunk.snapenhance.core.event.events.impl.SendMessageWithContentEvent
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.features.FeatureLoadParams
import me.rhunk.snapenhance.core.features.impl.spying.StealthMode

class AutoMarkAsRead : Feature("Auto Mark As Read", loadParams = FeatureLoadParams.INIT_SYNC) {
    val isEnabled by lazy { context.config.messaging.autoMarkAsRead.get() }

    fun markConversationsAsRead(conversationIds: List<String>) {
        conversationIds.forEach { conversationId ->
            val lastClientMessageId = context.database.getMessagesFromConversationId(conversationId, 1)?.firstOrNull()?.clientMessageId?.toLong() ?: Long.MAX_VALUE
            context.feature(StealthMode::class).addDisplayedMessageException(lastClientMessageId)
            context.feature(Messaging::class).conversationManager?.displayedMessages(conversationId, lastClientMessageId) {
                if (it != null) {
                    context.log.warn("Failed to mark message $lastClientMessageId as read in conversation $conversationId")
                }
            }
        }
    }

    override fun init() {
        if (!isEnabled) return

        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            event.addCallbackResult("onSuccess") {
                markConversationsAsRead(event.destinations.conversations?.map { it.toString() } ?: return@addCallbackResult)
            }
        }
    }
}