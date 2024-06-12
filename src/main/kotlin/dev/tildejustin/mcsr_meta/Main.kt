package dev.tildejustin.mcsr_meta

import dev.tildejustin.mcsr_meta.json.*
import io.github.z4kn4fein.semver.Version
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.TextProgressMonitor
import java.net.URI
import java.nio.file.*
import java.security.MessageDigest
import kotlin.io.path.*
import kotlin.time.*

// val legalModsPath: Path = Path.of("/home/justin/PycharmProjects/legal-mods/legal-mods")
val legalModsPath: Path = Path.of("legal-mods/legal-mods")
val tempDir: Path = Path.of("temp")
lateinit var nameReplacements: HashMap<String, String>
lateinit var replacementDescriptions: HashMap<String, String>
lateinit var minecraftVersions: List<String>
lateinit var modIncompatibilities: List<List<String>>
lateinit var unrecommendedMods: HashMap<String, List<String>>
lateinit var obsoleteMods: HashMap<String, List<String>>
lateinit var codeSources: HashMap<String, String>
// modid -> list of conditions
lateinit var conditions: HashMap<String, MutableList<String>>

// good for testing out quick changes
const val noReload = false

fun main() {
    val mark = TimeSource.Monotonic.markNow()
    // place to store downloaded mods
    if (!Files.exists(tempDir)) Files.createDirectory(tempDir)
    // TODO: progress logging
    if (!noReload) {
        deleteAndRecloneLegalMods()
    }
    conditions = readConditions()
    readAdditionalData()
    val gitId = Git.open(legalModsPath.parent.toFile()).log().setMaxCount(1).call().first().name
    val mods = ArrayList<Meta.Mod>()
    Files.list(legalModsPath).forEach { modid ->
        val modVersions = ArrayList<Meta.ModVersion>()
        Files.list(modid).forEach {
            modVersions.add(generateModVersion(modid.name, it, gitId))
        }
        mods.add(generateMod(modid, modVersions.stream().sorted { s1, s2 ->
            if (s2.target_version.first().contains("+")) return@sorted 1
            else if (s1.target_version.first().contains("+")) return@sorted -1
            return@sorted Version.parse(s2.target_version.first().split("-")[0], false).compareTo(Version.parse(s1.target_version.first().split("-")[0], false))
        }.toList()))
    }
    Path.of("mods.json").writeText(json.encodeToString(Meta(6, mods.sortedBy { it.modid })) + "\n")
    println("time taken: ${mark.elapsedNow().toString(DurationUnit.SECONDS, 1)}")
}

@Serializable
data class AdditionalData(
    val names: HashMap<String, String>,
    val descriptions: HashMap<String, String>,
    val sources: HashMap<String, String>,
    @SerialName("max-versions") val maxVersions: List<String>,
    @SerialName("not-recommended") val notRecommended: HashMap<String, List<String>>,
    val obsolete: HashMap<String, List<String>>,
    val incompatibilities: List<List<String>>,
    @SerialName("extra-traits") val extraTraits: HashMap<String, Set<String>>
)

fun readAdditionalData() {
    val additionalMetadata = json.decodeFromString<AdditionalData>(Path.of("data.json").readText())
    val versions = additionalMetadata.maxVersions.map { maxVersion ->
        if (maxVersion.count { it == '.' } == 1) return@map listOf(maxVersion)
        val minor = maxVersion.substring(0, maxVersion.lastIndexOf("."))
        // legacy fabric only has 1.19.4, 1.10.2, 1.11.2, 1.12.2, and 1.13.2 for production intermediaries rn
        if (minor.split(".")[1].toInt() in 9..13) return@map listOf(maxVersion);
        val maxPatch = maxVersion.split(".").last().toInt()
        val intermediateVersions = (1..maxPatch).map { "$minor.$it" } as ArrayList
        intermediateVersions.addFirst(minor)
        return@map intermediateVersions
    }.flatten()
    nameReplacements = additionalMetadata.names
    replacementDescriptions = additionalMetadata.descriptions
    minecraftVersions = versions
    unrecommendedMods = additionalMetadata.notRecommended
    obsoleteMods = additionalMetadata.obsolete
    modIncompatibilities = additionalMetadata.incompatibilities
    codeSources = additionalMetadata.sources
    additionalMetadata.extraTraits.forEach { (k, v) -> conditions.getOrPut(k) { ArrayList() }.addAll(v) }
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
    if (codeSources[modFolder.name] == null) {
        throw RuntimeException("missing source repo for ${modFolder.name}")
    }
    return Meta.Mod(modFolder.name,
        newestModInfo.name,
        newestModInfo.description,
        codeSources[modFolder.name]!!,
        versions,
        conditions.getOrDefault(modFolder.name, emptyList()),
        modIncompatibilities.filter { it.contains(modFolder.name) }.flatten().filter { it != modFolder.name },
        unrecommendedMods[modFolder.name]?.isNotEmpty() ?: true,
        obsoleteMods[modFolder.name]?.isEmpty() ?: false,
    )
}

fun generateModVersion(modid: String, folder: Path, gitId: String): Meta.ModVersion {
    var modFile = Files.list(folder).findFirst().get()
    val modUrl: String
    if (modFile.extension == "json") {
        val (path, url) = handleExternalMod(modFile)
        modFile = path
        modUrl = url
    } else {
        // remove first legal-mods git folder
        modUrl = "https://github.com/Minecraft-Java-Edition-Speedrunning/legal-mods/raw/${gitId}/${modFile.subpath(modFile.count() - 4, modFile.count()).toString().replace("\\", "/")}"
    }
    val range = createSemverRangeFromFolderName(folder.name)
    val info = readFabricModJson(modFile)
    val unrecommendedIntersection = unrecommendedMods[modid]?.flatMap { createSemverRangeFromFolderName(it) }?.intersect(range)
    val obsoleteIntersection = obsoleteMods[modid]?.flatMap { createSemverRangeFromFolderName(it) }?.intersect(range)
    return Meta.ModVersion(range, info.version, modUrl, hashPath(modFile), unrecommendedIntersection?.isEmpty() ?: true, obsoleteIntersection?.isNotEmpty() ?: false)
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
    val fileData = Json.decodeFromString<HashMap<String, List<String>>>(legalModsPath.parent.resolve("conditional-mods.json").readText())
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
    Git.cloneRepository().setURI("https://github.com/Minecraft-Java-Edition-Speedrunning/legal-mods").setDepth(1).setProgressMonitor(TextProgressMonitor()).call()
}

fun ByteArray.toHex() = joinToString("") { byte -> "%02x".format(byte) }

val messageDigest: MessageDigest = MessageDigest.getInstance("sha512")

fun hashPath(path: Path): String {
    return messageDigest.digest(path.readBytes()).toHex()
}

fun hashBytes(bytes: ByteArray): String {
    return messageDigest.digest(bytes).toHex()
}
