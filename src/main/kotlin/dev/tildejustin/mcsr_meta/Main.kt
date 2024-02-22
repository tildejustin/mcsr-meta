package dev.tildejustin.mcsr_meta

import dev.tildejustin.mcsr_meta.json.*
import io.github.z4kn4fein.semver.Version
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import org.eclipse.jgit.api.Git
import java.net.URI
import java.nio.file.*
import java.security.MessageDigest
import kotlin.io.path.*

// val legalModsPath: Path = Path.of("/home/justin/PycharmProjects/legal-mods/legal-mods")
val legalModsPath: Path = Path.of("legal-mods/legal-mods")
val tempDir: Path = Path.of("temp")
lateinit var nameReplacements: HashMap<String, String>
lateinit var replacementDescriptions: HashMap<String, String>
lateinit var minecraftVersions: List<String>
lateinit var modIncompatibilities: List<List<String>>
lateinit var unrecommendedMods: List<String>
// lateinit var recommendationOverrides: Map<String, List<String>>
val gitId: String = Git.open(legalModsPath.parent.toFile()).log().setMaxCount(1).call().first().name

// modid -> list of conditions
val conditions = readConditions()

// good for testing out quick changes
const val noReload = false

fun main() {
    readAdditionalData()
    // place to store downloaded mods
    if (!Files.exists(tempDir)) Files.createDirectory(tempDir)
    // TODO: progress logging
    if (!noReload) {
        deleteAndRecloneLegalMods()
    }
    val mods = ArrayList<Meta.Mod>()
    Files.list(legalModsPath).forEach { modid ->
        val modVersions = ArrayList<Meta.ModVersion>()
        Files.list(modid).forEach {
            modVersions.add(generateModVersion(it))
        }
        mods.add(generateMod(modid, modVersions.stream().sorted { s1, s2 ->
            if (s2.target_version.last().contains("+")) return@sorted 1
            else if (s1.target_version.last().contains("+")) return@sorted -1
            return@sorted Version.parse(s2.target_version.last().split("-")[0], false).compareTo(Version.parse(s1.target_version.last().split("-")[0], false))
        }.toList()))
    }
    Path.of("mods.json").writeText(json.encodeToString(Meta(5, mods.sortedBy { it.modid })))
}

@Serializable
data class AdditionalData(
    val names: HashMap<String, String>,
    val descriptions: HashMap<String, String>,
    val max_versions: List<String>,
    val not_recommended: List<String>,
    val incompatibilities: List<List<String>>,
    val recommendation_overrides: HashMap<String, List<String>>,
    val extra_traits: HashMap<String, Set<String>>
)

fun readAdditionalData() {
    val additionalMetadata = json.decodeFromString<AdditionalData>(Path.of("data.json").readText())
    val versions = additionalMetadata.max_versions.map { maxVersion ->
        val minor = maxVersion.substring(0, maxVersion.lastIndexOf("."))
        val maxPatch = maxVersion.split(".").last().toInt()
        val intermediateVersions = (1..maxPatch).map { "$minor.$it" }
        intermediateVersions.addFirst(minor)
        return@map intermediateVersions
    }.flatten()
    nameReplacements = additionalMetadata.names
    replacementDescriptions = additionalMetadata.descriptions
    minecraftVersions = versions
    unrecommendedMods = additionalMetadata.not_recommended
    // for (entry in additionalMetadata.recommendation_overrides.entries) {
    //     // remap ranges in array to single versions
    //     additionalMetadata.recommendation_overrides[entry.key] = entry.value.map { createSemverRangeFromFolderName(it) }.flatten()
    // }
    modIncompatibilities = additionalMetadata.incompatibilities
    // recommendationOverrides = additionalMetadata.recommendation_overrides
    additionalMetadata.extra_traits.forEach { (k, v) -> conditions.getOrPut(k) { ArrayList() }.addAll(v) }
}

fun generateMod(modFolder: Path, versions: List<Meta.ModVersion>): Meta.Mod {
    val chosenFolder = Files.list(modFolder).sorted { s1, s2 ->
        if (s2.name.contains("+")) return@sorted 1
        else if (s1.name.contains("+")) return@sorted -1
        return@sorted Version.parse(s2.name.split("-")[0], false).compareTo(Version.parse(s1.name.split("-")[0], false))
    }.findFirst().get()
    val newestModInfo = readFabricModJson(getExternalJarIfNecessary(chosenFolder))
    // override description if an override exists
    newestModInfo.description = replacementDescriptions.getOrDefault(modFolder.name, newestModInfo.description)
    newestModInfo.name = nameReplacements.getOrDefault(modFolder.name, newestModInfo.name)
    return Meta.Mod(modFolder.name,
        newestModInfo.name,
        newestModInfo.description,
        versions,
        modFolder.name !in unrecommendedMods,
        conditions.getOrDefault(modFolder.name, emptyList()),
        modIncompatibilities.filter { it.contains(modFolder.name) }.flatten().filter { it != modFolder.name })
}

fun generateModVersion(folder: Path): Meta.ModVersion {
    var modFile = Files.list(folder).findFirst().get()
    val modUrl: String
    if (modFile.extension == "json") {
        val (path, url) = handleExternalMod(modFile)
        modFile = path
        modUrl = url
    } else {
        // remove first legal-mods git folder
        modUrl = "https://github.com/Minecraft-Java-Edition-Speedrunning/legal-mods/raw/${gitId}/${modFile.subpath(modFile.count() - 4, modFile.count())}"
    }
    val range = createSemverRangeFromFolderName(folder.name)
    val info = readFabricModJson(modFile)
    // TODO: figure out how to do recommendation overrides (needs to be either true or false, only when contradicting the default recommendation, and has to be qualified to a version range)
    // for example, dynamic-fps 1.3-1.12.2 is not recommended, but since there's no sleepbackground for pre 1.7 it needs to be something like
    // recommended: { "value": true, "versions": ["1.3.1",..."1.6.4"] }, but this feels to be very difficult to implement concisely in modcheck
    return Meta.ModVersion(range, info.version, modUrl, hashPath(modFile))
}

fun getExternalJarIfNecessary(folder: Path): Path {
    val modFile = Files.list(folder).findFirst().get()
    if (modFile.extension == "json") {
        return tempFileName(folder, modFile)
    }
    return modFile
}

private fun tempFileName(folder: Path, modFile: Path): Path =
    tempDir.resolve(legalModsPath.relativize(folder)).resolve(modFile.nameWithoutExtension + ".jar")

data class RealizedExternalMod(val path: Path, val url: String)

fun handleExternalMod(jsonPath: Path): RealizedExternalMod {
    val externalMod = json.decodeFromString<ExternalModJson>(jsonPath.readText())
    val downloadedJar = tempFileName(jsonPath.parent, jsonPath)
    if (!noReload) {
        Files.deleteIfExists(downloadedJar)
        Files.createDirectories(downloadedJar.parent)
        Files.createFile(downloadedJar)
        val jarBytes = URI.create(externalMod.link).toURL().readBytes()
        // check the downloaded file
        check(hashBytes(jarBytes) == externalMod.hash)
        downloadedJar.writeBytes(jarBytes)
    }
    return RealizedExternalMod(downloadedJar, externalMod.link)
}

@OptIn(ExperimentalSerializationApi::class)
private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; prettyPrintIndent = "  " }

fun readFabricModJson(mod: Path): FabricModJson {
    FileSystems.newFileSystem(mod, null as ClassLoader?).use { fs ->
        val jsonFilePath = fs.getPath("fabric.mod.json")
        val jsonData = Files.readAllBytes(jsonFilePath)
        return json.decodeFromString<FabricModJson>(String(jsonData))
    }
}

fun readConditions(): HashMap<String, MutableList<String>> {
    val fileData = Json.decodeFromString<HashMap<String, List<String>>>(Path.of("legal-mods/conditional-mods.json").readText())
    val map = HashMap<String, MutableList<String>>()
    fileData.forEach { entry ->
        entry.value.forEach {
            map.getOrPut(it) { ArrayList() }.add(entry.key)
        }
    }
    return map
}

fun createSemverRangeFromFolderName(folder: String): List<String> {
    val parts = folder.split("-")
    assert(parts.count() in 1..2)
    if ("+" in folder) {
        val minVersion = Version.parse(parts[0].replace("+", ""), false)
        return minecraftVersions.filter {
            Version.parse(it, false) >= minVersion
        }
    }
    if (parts.count() == 1) {
        return listOf(parts[0])
    }
    val minVersion = Version.parse(parts[0], false)
    val maxVersion = Version.parse(parts[1], false)
    return minecraftVersions.filter {
        val currentVersion = Version.parse(it, false)
        return@filter currentVersion in minVersion..maxVersion
    }
}

// clear old repo and re-clone it
fun deleteAndRecloneLegalMods() {
    Path.of("legal-mods").toFile().deleteRecursively()
    Git.cloneRepository().setURI("https://github.com/Minecraft-Java-Edition-Speedrunning/legal-mods").setDepth(1).call()
}

fun ByteArray.toHex() = joinToString("") { byte -> "%02x".format(byte) }

val messageDigest: MessageDigest = MessageDigest.getInstance("sha512")

fun hashPath(path: Path): String {
    return messageDigest.digest(path.readBytes()).toHex()
}

fun hashBytes(bytes: ByteArray): String {
    return messageDigest.digest(bytes).toHex()
}
