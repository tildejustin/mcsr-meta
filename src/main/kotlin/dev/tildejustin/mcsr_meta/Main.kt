package dev.tildejustin.mcsr_meta

import dev.tildejustin.mcsr_meta.json.*
import io.github.z4kn4fein.semver.*
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.TextProgressMonitor
import java.net.URI
import java.nio.file.*
import java.security.MessageDigest
import java.util.*
import kotlin.io.path.*
import kotlin.time.*

// val legalModsPath: Path = Path.of("C:\\Users\\justi\\IdeaProjects\\legal-mods\\legal-mods")
val legalModsPath: Path = Path.of("legal-mods/legal-mods")
val aprilFoolsModsPath: Path = Path.of("mc_af-legal-mods")
val tempDir: Path = Path.of("temp")
lateinit var nameReplacements: Map<String, String>
lateinit var replacementDescriptions: HashMap<String, String>
lateinit var minecraftVersions: SortedSet<String>
lateinit var modIncompatibilities: List<List<String>>
lateinit var extraModIncompatibilities: List<List<String>>
lateinit var extraDependencies: Map<String, Map<String, List<String>>>
lateinit var unrecommendedMods: Map<String, List<String>>
lateinit var obsoleteMods: Map<String, List<String>>
lateinit var homepages: Map<String, String>
lateinit var v2Override: List<String>
lateinit var additionalIntermediary: Map<String, List<Intermediary>>
lateinit var githubReleases: Map<String, Map<String, List<String>>>
lateinit var modrinthReleases: List<String>
lateinit var extraEntries: List<Meta.Mod>


// modid -> list of conditions
lateinit var conditions: HashMap<String, MutableList<String>>

var secretPreReleases = setOf("1.2", "1.3", "1.4", "1.4.1", "1.4.3", "1.5", "1.6", "1.6.3", "1.7", "1.7.1", "1.7.3")
val legacyIntermediary = listOf(Intermediary.LEGACY_FABRIC, Intermediary.LEGACY_FABRIC_V2, Intermediary.ORNITHE, Intermediary.ORNITHE_GEN2)

// good for testing out quick changes
const val noReload = false
val comparer: (String, String) -> Int = { o1, o2 ->
    var one: Version? = null
    var two: Version? = null
    try {
        one = Version.parse(o1, false)
    } catch (_: VersionFormatException) {
    }
    try {
        two = Version.parse(o2, false)
    } catch (_: VersionFormatException) {
    }
    if (one != null && two != null) {
        two.compareTo(one)
    } else if (one != null) {
        -1
    } else if (two != null) {
        1
    } else {
        // april fools snapshots
        o2.compareTo(o1)
    }
}

val modVersionComparer: (Meta.ModVersion, Meta.ModVersion) -> Int = { s1, s2 ->
    if (s2.targetVersion.first().contains("+")) 1
    else if (s1.targetVersion.first().contains("+")) -1
    else comparer(s1.targetVersion.first().split("-")[0], s2.targetVersion.first().split("-")[0])
}

fun main() {
    val current = json.decodeFromString<Meta>(Path.of("mods.json").readText()).mods.flatMap { it.versions }
    var mark = TimeSource.Monotonic.markNow()
    // place to store downloaded mods
    if (!Files.exists(tempDir)) Files.createDirectory(tempDir)
    if (!noReload) {
        deleteAndRecloneLegalMods()
    }
    conditions = readConditions()
    readAdditionalData()
    val gitId = Git.open(legalModsPath.parent.toFile()).log().setMaxCount(1).call().first().name
    val aprilFoolsGitId = Git.open(aprilFoolsModsPath.toFile()).log().setMaxCount(1).call().first().name
    println("time taken: ${mark.elapsedNow().toString(DurationUnit.SECONDS, 1)}")
    mark = TimeSource.Monotonic.markNow()
    val mods = mutableListOf<Meta.Mod>()
    Files.list(legalModsPath).forEach { modid ->
        val modVersions = mutableListOf<Meta.ModVersion>()
        Files.list(modid).forEach {
            modVersions.add(generateModVersion(modid.name, Files.list(it).findFirst().get(), it.name, gitId, current))
        }
        mods.add(generateMod(modid, modVersions.stream().sorted(modVersionComparer).toList().toMutableList()))
    }
    Files.list(aprilFoolsModsPath).sorted().forEach { folder ->
        if (folder.isHidden() || folder.isRegularFile()) return@forEach
        Files.list(folder).forEach { modFile ->
            val fmj = readFabricModJson(modFile)
            mods.find { it.modid == fmj.id }?.versions?.add(generateModVersion(fmj.id, modFile, folder.name, aprilFoolsGitId, current, true)) ?: throw NoSuchFileException(fmj.id)
        }
    }
    handleOptiFine(mods)
    json.decodeFromString<HashMap<String, List<String>>>(aprilFoolsModsPath.resolve("external.json").readText()).forEach { (version, extras) ->
        val id = version.split("/", ";")[0]
        mods.find { it.modid == id }?.versions?.find { afVersionMatches(it, version) }?.targetVersion?.addAll(extras) ?: throw NoSuchElementException(version)
    }

    Path.of("mods.json").writeText(json.encodeToString(Meta(7, mods.sortedBy { it.modid })) + "\n")

    handleExtraMods()
    println("time taken: ${mark.elapsedNow().toString(DurationUnit.SECONDS, 1)}")
}

fun handleExtraMods() {
    val extraMods = arrayListOf<Meta.Mod>()
    // username/repo(/tag) -> (filename fragment -> [compatible versions])
    githubReleases.forEach { kv ->
        val parts = kv.key.split("/")
        // what's an error handling
        val rangeUrlPairs = json.decodeFromString<GitHubRelease>(
            URI.create("https://api.github.com/repos/${parts[0]}/${parts[1]}/releases/" + if (parts.size > 2) "tags/${parts[2]}" else "latest").toURL().readText()
        ).assets.mapNotNull { asset ->
            if ("-sources.jar" in asset.name) return@mapNotNull null
            val rangeKeys = kv.value.keys.filter { it in asset.name }
            if (rangeKeys.isEmpty()) return@mapNotNull null
            if (rangeKeys.size > 1) throw IllegalStateException("bad release filters")
            val range = kv.value[rangeKeys[0]]!!.flatMap { createSemverRangeFromFolderName(it) }.toSortedSet(comparer)
            Pair(asset.url, range)
        }
        // test for modid / desc
        val testUrl = rangeUrlPairs.first().first
        val dummy = handleAltExternalDownload("github_release_test", testUrl.substringAfterLast('/'), testUrl).path
        val templateFmj = readFabricModJson(dummy)
        val versionList = mutableListOf<Meta.ModVersion>()
        val mod = Meta.Mod(
            templateFmj.id,
            templateFmj.name,
            replacementDescriptions.getOrDefault(templateFmj.id, templateFmj.description),
            homepages.getOrElse(templateFmj.id) { "https://github.com/${parts[0]}/${parts[1]}" },
            versionList,
            incompatibilities = extraModIncompatibilities.filter { it.contains(templateFmj.id) }.flatten().filter { it != templateFmj.id }.sorted()
        )
        // TODO: overrides
        extraMods.add(mod)
        rangeUrlPairs.forEach {
            val path = handleAltExternalDownload(templateFmj.id, it.first.substringAfterLast('/'), it.first).path
            val (sha1, sha512, size) = hashPath(path)
            val newFmj = readFabricModJson(path)
            versionList.add(
                Meta.ModVersion(
                    it.second,
                    newFmj.version,
                    it.first,
                    sha1, sha512, size,
                    dependencies = addExtraDeps(newFmj, it.second),
                    intermediary = getIntermediary(newFmj.id, path, it.second).toList()
                )
            )
        }
    }

    fun versionOrNull(version: String): Version? {
        return try {
            val ver = Version.parse(version, false)
            if (ver.preRelease != null) null else ver
        } catch (_: VersionFormatException) {
            null
        }
    }

    modrinthReleases.forEach { id ->
        val bestPerVersion = HashMap<String, ModrinthVersion>()
        json.decodeFromString<List<ModrinthVersion>>(URI.create("https://api.modrinth.com/v2/project/${id}/version").toURL().readText())
            .filter { "forge" !in it.loaders && (it.files[0].filename != "LoTAS1.11.2-2.1.2.jar") }.forEach { version ->
                version.gameVersions.forEach { bestPerVersion.computeIfAbsent(it) { _ -> version } }
            }
        val versionBests = HashMap<ModrinthVersion, MutableSet<String>>()
        bestPerVersion.forEach { (k, v) -> versionBests.computeIfAbsent(v) { _ -> mutableSetOf() }.add(k) }
        val dummy = versionBests.keys
            .filter { mrVersion -> mrVersion.gameVersions.any { versionOrNull(it) != null } }
            .maxBy { it.gameVersions.mapNotNull(::versionOrNull).max() }
        val dummyMod = handleAltExternalDownload("github_release_test", dummy.files[0].filename, dummy.files[0].url).path
        val fmj = readFabricModJson(dummyMod)
        val versionList = mutableListOf<Meta.ModVersion>()
        val mod = Meta.Mod(
            fmj.id,
            fmj.name,
            replacementDescriptions.getOrDefault(fmj.id, fmj.description),
            "https://modrinth.com/mod/${id}",
            versionList,
            incompatibilities = extraModIncompatibilities.filter { it.contains(fmj.id) }.flatten().filter { it != fmj.id }.sorted()
        )
        // TODO: overrides
        extraMods.add(mod)
        versionBests.forEach { (k, v) ->
            val path = handleAltExternalDownload(fmj.id, k.files[0].filename, k.files[0].url).path
            val (sha1, sha512, size) = hashPath(path)
            versionList.add(
                Meta.ModVersion(
                    v.toSortedSet(comparer),
                    readFabricModJson(path).version,
                    k.files[0].url,
                    sha1, sha512, size,
                    dependencies = addExtraDeps(fmj, v),
                    intermediary = getIntermediary(fmj.id, path, v).toList()
                )
            )
        }
    }
    // TODO: overrides etc
    extraMods.addAll(extraEntries)
    extraMods.forEach { it.versions.sortWith(modVersionComparer) }
    Path.of("extra.json").writeText(json.encodeToString(Meta(7, extraMods.sortedBy { it.modid })) + "\n")
}

fun addExtraDeps(fmj: FabricModJson?, versions: Set<String>, modid: String? = null): Set<String> {
    val deps = sortedSetOf<String>()
    var id: String
    if (fmj != null) {
        id = fmj.id
        deps.addAll(fmj.depends.keys.filter { it !in listOf("fabricloader", "minecraft", "java", "mm", "fabric") && !it.startsWith("fabric-") })
    } else {
        id = modid!!
    }
    val unversionedDeps = extraDependencies[id]?.get("")
    if (unversionedDeps != null) deps.addAll(unversionedDeps)
    val versionedDeps = extraDependencies[id]?.filter { it.key in versions }?.flatMap { it.value }
    if (versionedDeps != null) deps.addAll(versionedDeps)
    return deps
}

fun handleOptiFine(mods: MutableList<Meta.Mod>) {
    val optifine = Meta.Mod(
        "optifine",
        "OptiFine",
        "OptiFine is a Minecraft optimization mod. It allows Minecraft to run faster and look better with full support for shaders, HD textures and many configuration options.",
        "https://optifine.net/home",
        mutableListOf(),
        incompatibilities = modIncompatibilities.filter { it.contains("optifine") }.flatten().filter { it != "optifine" }.sorted(),
    )
    val optifineLight = Meta.Mod(
        "optifine-light",
        "OptiFine Light",
        "A version of OptiFine that makes significantly less invasive changes to the game.",
        "https://optifine.net/home",
        mutableListOf(),
        incompatibilities = modIncompatibilities.filter { it.contains("optifine-light") }.flatten().filter { it != "optifine-light" }.sorted(),
    )
    mods.add(optifine)
    mods.add(optifineLight)

    class OptiFineEntry(val target: String, val edition: String, val patch: String, val filename: String)

    @Serializable
    class OptiFineData(
        val versions: List<String>,
        @SerialName("light_versions") val lightVersions: List<String>,
        @SerialName("additional_compatibility") val additionalCompatibility: Map<String, List<String>>
    )

    val normalList = mutableListOf<OptiFineEntry>()
    val lightList = mutableListOf<OptiFineEntry>()
    val optiFineData: OptiFineData = json.decodeFromString<OptiFineData>(Path.of("optifine.json").readText())
    (optiFineData.versions + optiFineData.lightVersions).forEach { filename ->
        val groups = "OptiFine_(.*?)_(L|HD|HD_U)_(.*?)\\.(?:jar|zip)".toRegex().matchEntire(filename)?.groupValues ?: return@forEach
        // get canonical version representation
        val versionParts = groups[1].split(".")
        val version = versionParts[0] + "." + versionParts[1] + if (versionParts.size > 2 && versionParts[2] != "0") "." + versionParts[2] else ""
        val edition = groups[2]
        val patch = groups[3]
        (if (filename in optiFineData.lightVersions) lightList else normalList).add(OptiFineEntry(version, edition, patch, filename))
    }
    (normalList + lightList).forEach { data ->
        val url = "https://optifine.net/download?f=${data.filename}"
        val targets = mutableSetOf(data.target, *optiFineData.additionalCompatibility.getOrDefault(data.filename, emptyList()).toTypedArray())
        val (sha1, sha512, size) = hashPath(handleAltExternalDownload("optifine", data.filename, url).path)
        (if (data.edition == "L") optifineLight else optifine).versions.add(
            Meta.ModVersion(
                targets,
                "${data.edition}_${data.patch}",
                url,
                sha1, sha512, size,
                unrecommendedMods["optifine"]?.none { it in targets } ?: true && (data.edition != "L" || optifine.versions.none { data.target in it.targetVersion }),
                false,
                dependencies = addExtraDeps(null, targets, if (data.edition == "L") "optifine-light" else "optifine"),
                legacyIntermediary
            )
        )
    }
    optifine.versions.sortWith(modVersionComparer)
    optifineLight.versions.sortWith(modVersionComparer)
}

fun afVersionMatches(modVersion: Meta.ModVersion, version: String): Boolean {
    // Formats:
    // Exact target: "fast_reset/1.19.4-1.21.5/fast-reset-1.4.3+1.19.4-1.20.6.jar"
    // Loose target: "fast_reset;1.19.4"
    if (version.contains(";")) return modVersion.targetVersion.contains(version.split(";")[1])
    return modVersion.url.endsWith(evaluateLinks(version))
}

fun evaluateLinks(partialPath: String): String {
    if (!partialPath.endsWith(".json")) return partialPath
    val parts = partialPath.split("/")
    return json.decodeFromString<ExternalModJson>(legalModsPath.resolve(parts[0]).resolve(parts[1]).resolve(parts[2]).readText()).link
}

@Serializable
data class AdditionalData(
    val names: HashMap<String, String>,
    val descriptions: HashMap<String, String>,
    val homepages: HashMap<String, String>,
    @SerialName("max-versions") val maxVersions: List<String>,
    @SerialName("not-recommended") val notRecommended: Map<String, List<String>>,
    val obsolete: HashMap<String, List<String>>,
    val incompatibilities: List<List<String>>,
    @SerialName("extras_incompatibilities") val extraIncompatibilities: List<List<String>>,
    @SerialName("extra_dependencies") val extraDependencies: Map<String, Map<String, List<String>>>,
    @SerialName("extra_traits") val extraTraits: Map<String, Set<String>>,
    @SerialName("v2-override") val v2Override: List<String>,
    @SerialName("additional_intermediary") val additionalIntermediary: Map<String, List<Intermediary>>,
    @SerialName("github_releases") val githubReleases: Map<String, Map<String, List<String>>>,
    @SerialName("modrinth_releases") val modrinthReleases: List<String>,
    @SerialName("extra_entries") val extraEntries: List<Meta.Mod>
)

fun readAdditionalData() {
    val additionalMetadata = json.decodeFromString<AdditionalData>(Path.of("data.jsonc").readText())
    val versions = additionalMetadata.maxVersions.map { maxVersion ->
        val version = Version.parse(maxVersion, false)
        // hardcoding 26.1+ versions until I finally someone else's meta for it b/c patches are unpredictable
        // legacy fabric only has 1.19.4, 1.10.2, 1.11.2, 1.12.2, and 1.13.2 for production intermediaries rn
        if (version.major > 1 || version.patch == 0 || version.minor in 9..13) return@map listOf(maxVersion)
        val noPatch = "1.${version.minor}"
        val intermediateVersions = (1..version.patch).map { "$noPatch.$it" } as ArrayList
        intermediateVersions.addFirst(noPatch)
        intermediateVersions.removeAll(secretPreReleases)
        return@map intermediateVersions
    }.flatten()
    nameReplacements = additionalMetadata.names
    replacementDescriptions = additionalMetadata.descriptions
    minecraftVersions = versions.toSortedSet(comparer)
    unrecommendedMods = additionalMetadata.notRecommended
    obsoleteMods = additionalMetadata.obsolete
    modIncompatibilities = additionalMetadata.incompatibilities
    extraModIncompatibilities = additionalMetadata.extraIncompatibilities
    extraDependencies = additionalMetadata.extraDependencies
    homepages = additionalMetadata.homepages
    v2Override = additionalMetadata.v2Override
    additionalIntermediary = additionalMetadata.additionalIntermediary
    githubReleases = additionalMetadata.githubReleases
    modrinthReleases = additionalMetadata.modrinthReleases
    extraEntries = additionalMetadata.extraEntries
    additionalMetadata.extraTraits.forEach { (k, v) -> conditions.getOrPut(k) { ArrayList() }.addAll(v) }
}

fun generateMod(modFolder: Path, versions: MutableList<Meta.ModVersion>): Meta.Mod {
    val chosenFolder = Files.list(modFolder).sorted { s1, s2 ->
        if (s2.name.contains("+")) return@sorted 1
        else if (s1.name.contains("+")) return@sorted -1
        return@sorted Version.parse(s2.name.split("-")[0], false).compareTo(Version.parse(s1.name.split("-")[0], false))
    }.findFirst().get()
    val newestModInfo = readFabricModJson(getExternalJarIfNecessary(chosenFolder))
    // override description if an override exists
    newestModInfo.description = replacementDescriptions.getOrDefault(modFolder.name, newestModInfo.description)
    newestModInfo.name = nameReplacements.getOrDefault(modFolder.name, newestModInfo.name)
    if (homepages[modFolder.name] == null) {
        throw RuntimeException("missing homepage for ${modFolder.name}")
    }
    return Meta.Mod(
        modFolder.name,
        newestModInfo.name,
        newestModInfo.description,
        homepages[modFolder.name]!!,
        versions,
        conditions.getOrDefault(modFolder.name, emptyList()),
        modIncompatibilities.filter { it.contains(modFolder.name) }.flatten().filter { it != modFolder.name }.sorted(),
        unrecommendedMods[modFolder.name]?.isNotEmpty() ?: true,
        obsoleteMods[modFolder.name]?.isEmpty() ?: false,
    )
}

fun generateModVersion(modid: String, modFile: Path, rangeName: String, gitId: String, current: List<Meta.ModVersion>, af: Boolean = false): Meta.ModVersion {
    @Suppress("NAME_SHADOWING") var modFile = modFile
    var modUrl: String
    if (modFile.extension == "json") {
        val (path, url) = handleExternalMod(modFile)
        modFile = path
        modUrl = url
    } else if (!af) {
        // remove first legal-mods git folder
        modUrl =
            "https://github.com/Minecraft-Java-Edition-Speedrunning/legal-mods/raw/${gitId}/${modFile.subpath(modFile.count() - 4, modFile.count()).toString().replace("\\", "/")}"
    } else {
        modUrl = "https://github.com/tildejustin/mc_af-legal-mods/raw/${gitId}/${modFile.subpath(modFile.count() - 2, modFile.count()).toString().replace("\\", "/")}"
    }
    val range = createSemverRangeFromFolderName(rangeName)
    if (v2Override.contains(modid)) {
        range.add("1.12")
    }
    val info = readFabricModJson(modFile)
    val unrecommendedIntersection = unrecommendedMods[modid]?.flatMap { createSemverRangeFromFolderName(it) }?.intersect(range)
    val obsoleteIntersection = obsoleteMods[modid]?.flatMap { createSemverRangeFromFolderName(it) }?.intersect(range)
    val (sha1, sha512, size) = hashPath(modFile)
    val oldVersion = current.find { it.sha512 == sha512 && it.url.substringAfterLast('/') == modUrl.substringAfterLast('/') }
    if (oldVersion != null) modUrl = oldVersion.url
    return Meta.ModVersion(
        range,
        info.version,
        modUrl,
        sha1, sha512, size,
        unrecommendedIntersection?.isEmpty() ?: true,
        obsoleteIntersection?.isNotEmpty() ?: false,
        addExtraDeps(info, range),
        getIntermediary(modid, modFile, range).sorted()
    )
}

fun getIntermediary(modid: String, modFile: Path, range: Set<String>): Set<Intermediary> {
    // for better detection I should take code from this https://github.com/thecatcore/WFVAIO
    val intermediaryTypes = mutableSetOf<Intermediary>()
    FileSystems.newFileSystem(modFile).use { jar ->
        jar.getPath("META-INF/MANIFEST.MF").readLines().forEach { line ->
            val parts = line.trim().split(":")
            when (parts[0]) {
                "Calamus-Generation" -> intermediaryTypes.add(
                    when (parts[1].trim().toInt()) {
                        1 -> Intermediary.ORNITHE
                        2 -> Intermediary.ORNITHE_GEN2
                        else -> throw RuntimeException()
                    }
                )

                "Legacy-Fabric-Intermediary-Version" -> intermediaryTypes.add(
                    when (parts[1].trim().toInt()) {
                        1 -> Intermediary.LEGACY_FABRIC
                        2 -> Intermediary.LEGACY_FABRIC_V2
                        else -> throw RuntimeException()
                    }
                )
            }
        }
    }
    if (modid in v2Override) {
        intermediaryTypes.add(Intermediary.LEGACY_FABRIC_V2)
    }
    if (modid in additionalIntermediary) {
        intermediaryTypes.addAll(additionalIntermediary[modid] as List<Intermediary>)
    }
    if (intermediaryTypes.isNotEmpty()) return intermediaryTypes
    // fallback for fabric / old legacy fabric
    val topVersion = range.last()
    try {
        val version = Version.parse(topVersion, false)
        intermediaryTypes.add(if (version.minor in 3..13) Intermediary.LEGACY_FABRIC else Intermediary.FABRIC)
    } catch (_: VersionFormatException) {
        intermediaryTypes.add(
            when (topVersion) {
                "1.RV-pre1" -> Intermediary.LEGACY_FABRIC_V2
                "15w14a" -> Intermediary.LEGACY_FABRIC
                else -> Intermediary.FABRIC
            }
        )
    }
    return intermediaryTypes
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

fun handleAltExternalDownload(modid: String, filename: String, url: String): RealizedExternalMod {
    val downloadedJar = tempDir.resolve(modid).resolve(filename)
    // TODO: remove once tested
    if (!downloadedJar.exists())
        downloadExternalMod(downloadedJar, url, null)
    return RealizedExternalMod(downloadedJar, url)
}

fun handleExternalMod(jsonPath: Path): RealizedExternalMod {
    val externalMod = json.decodeFromString<ExternalModJson>(jsonPath.readText())
    val downloadedJar = tempFileName(jsonPath.parent, jsonPath)
    if (!noReload) {
        downloadExternalMod(downloadedJar, externalMod.link, externalMod.hash)
    }
    return RealizedExternalMod(downloadedJar, externalMod.link)
}

fun downloadExternalMod(tempPath: Path, url: String, hash: String?) {
    Files.deleteIfExists(tempPath)
    Files.createDirectories(tempPath.parent)
    Files.createFile(tempPath)
    val jarBytes = URI.create(url).toURL().readBytes()
    // check the downloaded file
    if (hash != null) check(hashBytesSha512(jarBytes) == hash)
    tempPath.writeBytes(jarBytes)
}

@OptIn(ExperimentalSerializationApi::class)
private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; prettyPrintIndent = "  "; allowComments = true }

fun readFabricModJson(mod: Path): FabricModJson {
    FileSystems.newFileSystem(mod, null as ClassLoader?).use { fs ->
        val jsonFilePath = fs.getPath("fabric.mod.json")
        val jsonData = Files.readAllBytes(jsonFilePath)
        return json.decodeFromString<FabricModJson>(String(jsonData))
    }
}

fun readConditions(): HashMap<String, MutableList<String>> {
    val fileData = json.decodeFromString<HashMap<String, List<String>>>(legalModsPath.parent.resolve("conditional-mods.json").readText())
    val map = HashMap<String, MutableList<String>>()
    fileData.forEach { entry ->
        entry.value.forEach {
            map.getOrPut(it) { ArrayList() }.add(entry.key)
        }
    }
    return map
}

fun createSemverRangeFromFolderName(folder: String): MutableSet<String> {
    var parts = folder.split("-")
    if (folder == "1.RV-Pre1") parts = listOf(folder)
    assert(parts.count() in 1..2)
    if ("+" in folder) {
        val minVersion = Version.parse(parts[0].replace("+", ""), false)
        return minecraftVersions.filter {
            Version.parse(it, false) >= minVersion
        }.toSortedSet(comparer)
    }
    if (parts.count() == 1) {
        return sortedSetOf<String>(comparer, parts[0])
        // return listOf(parts[0]).toSortedSet(comparer) // above used to fail on type at runtime
    }
    val minVersion = Version.parse(parts[0], false)
    val maxVersion = Version.parse(parts[1], false)
    return minecraftVersions.filter {
        val currentVersion = Version.parse(it, false)
        return@filter currentVersion in minVersion..maxVersion
    }.toSortedSet(comparer)
}

// clear old repo and re-clone it
fun deleteAndRecloneLegalMods() {
    Path.of("legal-mods").toFile().deleteRecursively()
    Path.of("mc_af-legal-mods").toFile().deleteRecursively()
    Git.cloneRepository().setURI("https://github.com/Minecraft-Java-Edition-Speedrunning/legal-mods").setDepth(1).setProgressMonitor(TextProgressMonitor()).call()
    Git.cloneRepository().setURI("https://github.com/tildejustin/mc_af-legal-mods").setDepth(1).setProgressMonitor(TextProgressMonitor()).call()
}

fun ByteArray.toHex() = joinToString("") { byte -> "%02x".format(byte) }

val sha1Digest: MessageDigest = MessageDigest.getInstance("sha1")
val sha512Digest: MessageDigest = MessageDigest.getInstance("sha512")

fun hashPath(path: Path): Triple<String, String, Int> {
    val fileBytes = path.readBytes()
    return Triple(sha1Digest.digest(fileBytes).toHex(), sha512Digest.digest(fileBytes).toHex(), path.fileSize().toInt())
}

fun hashBytesSha512(bytes: ByteArray): String {
    return sha512Digest.digest(bytes).toHex()
}
