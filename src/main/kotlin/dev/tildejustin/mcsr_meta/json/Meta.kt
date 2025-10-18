package dev.tildejustin.mcsr_meta.json

import kotlinx.serialization.*

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class Meta(val schemaVersion: Int, val mods: List<Mod>) {
    @Serializable
    data class Mod(
        val modid: String,
        val name: String,
        val description: String = "",
        val homepage: String,
        val versions: MutableList<ModVersion>,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val traits: List<String> = listOf(),
        @EncodeDefault(EncodeDefault.Mode.NEVER) val incompatibilities: List<String> = listOf(),
        @EncodeDefault(EncodeDefault.Mode.NEVER) val recommended: Boolean = true,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val obsolete: Boolean = false
    )

    @Serializable
    data class ModVersion(
        @SerialName("target_version") val targetVersion: MutableSet<String>,
        val version: String,
        val url: String,
        val hash: String,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val recommended: Boolean = true,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val obsolete: Boolean = false,
        val intermediary: List<Intermediary>
    )
}
