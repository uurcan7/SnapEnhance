package me.rhunk.snapenhance.core.features.impl.messaging

import me.rhunk.snapenhance.core.event.events.impl.SendMessageWithContentEvent
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.features.FeatureLoadParams
import me.rhunk.snapenhance.core.features.impl.spying.StealthMode

class AutoMarkAsRead : Feature("Auto Mark As Read", loadParams = FeatureLoadParams.INIT_SYNC) {
    override fun init() {
        if (!context.config.messaging.autoMarkAsRead.get()) return

        context.event.subscribe(SendMessageWithContentEvent::class) { event ->
            event.addCallbackResult("onSuccess") {
                event.destinations.conversations!!.map { it.toString() }.forEach { conversationId ->
                    val lastClientMessageId = context.database.getMessagesFromConversationId(conversationId, 1)?.firstOrNull()?.clientMessageId?.toLong() ?: Long.MAX_VALUE
                    context.feature(StealthMode::class).addDisplayedMessageException(lastClientMessageId)
                    context.feature(Messaging::class).conversationManager?.displayedMessages(conversationId, lastClientMessageId) {
                        if (it != null) {
                            context.log.warn("Failed to mark message $lastClientMessageId as read in conversation $conversationId")
                        }
                    }
                }
            }
        }
    }
}