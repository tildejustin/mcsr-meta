package dev.tildejustin.mcsr_meta.json

import kotlinx.serialization.Serializable

@Serializable
class FabricModJson(val version: String, val name: String, var description: String = "")
