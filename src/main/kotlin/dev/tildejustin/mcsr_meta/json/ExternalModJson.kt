package dev.tildejustin.mcsr_meta.json

import kotlinx.serialization.Serializable

@Serializable
data class ExternalModJson(val link: String, val hash: String)
