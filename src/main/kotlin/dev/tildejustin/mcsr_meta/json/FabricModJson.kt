package dev.tildejustin.mcsr_meta.json

import kotlinx.serialization.Serializable

@Serializable
class FabricModJson(val version: String, var name: String, var description: String = "")
