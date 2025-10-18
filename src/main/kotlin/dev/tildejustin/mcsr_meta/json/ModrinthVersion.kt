package dev.tildejustin.mcsr_meta.json

import kotlinx.serialization.*

@Serializable
data class ModrinthVersion(@SerialName("game_versions") val gameVersions: List<String>, val loaders: List<String>, val files: List<File>) {
    @Serializable
    data class File(val hashes: Hashes, val url: String, val filename: String) {
        @Serializable
        data class Hashes(val sha512: String)
    }
}
