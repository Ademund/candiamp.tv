package tv.candiamp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

fun JsonObject.text(key: String): String? {
    val e = this[key] ?: return null
    if (e is JsonNull) return null
    return (e as? JsonPrimitive)?.contentOrNull
}

fun JsonObject.int(key: String): Int? = text(key)?.toIntOrNull()
fun JsonObject.long(key: String): Long? = text(key)?.toLongOrNull()
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/**
 * Subsonic serialisers disagree on whether a list of one item is an array or a bare object,
 * so every list is read through here.
 */
fun JsonObject.objects(key: String): List<JsonObject> = when (val e = this[key]) {
    null, is JsonNull -> emptyList()
    is JsonArray -> e.mapNotNull { it as? JsonObject }
    is JsonObject -> listOf(e)
    else -> emptyList()
}

fun JsonElement?.plain(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> contentOrNull
    else -> null
}
