package dev.tildejustin.mcsr_meta.json

import kotlinx.serialization.*

@Serializable
data class GitHubRelease(val assets: List<Asset>) {
    @Serializable
    data class Asset(@SerialName("browser_download_url") val url: String, val name: String)
}
