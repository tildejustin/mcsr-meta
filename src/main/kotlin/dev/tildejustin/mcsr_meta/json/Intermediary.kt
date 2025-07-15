package dev.tildejustin.mcsr_meta.json

import kotlinx.serialization.*

@Serializable
enum class Intermediary {
    @SerialName("net.fabricmc:intermediary")
    FABRIC,

    @SerialName("net.legacyfabric:intermediary")
    LEGACY_FABRIC,

    @SerialName("net.legacyfabric.v2:intermediary")
    LEGACY_FABRIC_V2,

    @SerialName("net.ornithemc:calamus-intermediary")
    ORNITHE,

    @SerialName("net.ornithemc:calamus-intermediary-gen2")
    ORNITHE_GEN2
}
