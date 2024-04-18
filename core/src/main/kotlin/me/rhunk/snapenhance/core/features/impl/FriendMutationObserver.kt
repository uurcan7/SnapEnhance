package me.rhunk.snapenhance.core.features.impl

import com.google.gson.JsonObject
import me.rhunk.snapenhance.core.event.events.impl.NetworkApiRequestEvent
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.features.FeatureLoadParams
import me.rhunk.snapenhance.core.util.EvictingMap
import java.io.InputStreamReader

class FriendMutationObserver: Feature("FriendMutationObserver", loadParams = FeatureLoadParams.INIT_SYNC) {
    private val addSourceCache = EvictingMap<String, String>(500)

    fun getFriendAddSource(userId: String): String? {
        return addSourceCache[userId]
    }

    override fun init() {
        context.event.subscribe(NetworkApiRequestEvent::class) { event ->
            if (!event.url.contains("ami/friends")) return@subscribe
            event.onSuccess { buffer ->
                runCatching {
                    val jsonObject = context.gson.fromJson(InputStreamReader(buffer?.inputStream() ?: return@onSuccess, Charsets.UTF_8), JsonObject::class.java)

                    jsonObject.getAsJsonArray("added_friends").map { it.asJsonObject }.forEach { friend ->
                        val userId = friend.get("user_id").asString
                        (friend.get("add_source")?.asString?.takeIf {
                            it.isNotBlank()
                        } ?: friend.get("add_source_type")?.asString?.takeIf {
                            it.isNotBlank()
                        })?.let {
                            addSourceCache[userId] = it
                        }
                    }
                }.onFailure {
                    context.log.error("Failed to process friends", it)
                }
            }
        }
    }
}