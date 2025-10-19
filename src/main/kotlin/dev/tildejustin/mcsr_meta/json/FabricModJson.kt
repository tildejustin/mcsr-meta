package dev.tildejustin.mcsr_meta.json

import kotlinx.serialization.*
import kotlinx.serialization.builtins.*
import kotlinx.serialization.json.*

@Serializable
@OptIn(ExperimentalSerializationApi::class)
class FabricModJson(
    val version: String,
    val id: String,
    var name: String,
    var description: String = "",
    val depends: Map<String, @Serializable(with = ListSerializer::class) List<String>> = emptyMap()
)

object ListSerializer : JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
    // If response is not an array, then it is a single object that should be wrapped into the array
    override fun transformDeserialize(element: JsonElement): JsonElement =
        element as? JsonArray ?: JsonArray(listOf(element))
}
