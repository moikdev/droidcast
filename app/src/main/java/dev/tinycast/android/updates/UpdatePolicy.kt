package dev.tinycast.android.updates

import java.net.URI

internal data class UpdateAsset(val name: String, val url: String, val bytes: Long)
internal data class NightlyRelease(val tag: String, val title: String, val notes: String, val published: String, val assets: List<UpdateAsset>)
internal data class UpdateManifest(val format: Int, val packageName: String, val versionCode: Long, val versionName: String,
                                   val commit: String, val apk: String, val sha256: String, val bytes: Long)
internal data class UpdateCandidate(val release: NightlyRelease, val manifest: UpdateManifest, val apk: UpdateAsset, val checksums: UpdateAsset)
internal data class ArchiveIdentity(val packageName: String, val versionCode: Long, val versionName: String, val signers: Set<String>, val minSdk: Int)

internal object UpdatePolicy {
    const val PACKAGE = "dev.tinycast.android"
    const val MANIFEST = "droidcast-update.json"
    const val CHECKSUMS = "SHA256SUMS.txt"
    const val MAX_APK = 128L * 1024 * 1024
    const val MAX_METADATA = 32L * 1024
    const val FEED = "https://api.github.com/repos/moikdev/droidcast/releases?per_page=100"
    private val token = Regex("[A-Za-z0-9._-]{1,180}")
    private val hash = Regex("[a-fA-F0-9]{64}")

    fun assetUrl(tag: String, name: String): String {
        require(tag.startsWith("nightly-") && token.matches(tag) && tag != "." && tag != "..") { "Unsupported release tag" }
        require(token.matches(name) && name != "." && name != "..") { "Unsupported release asset" }
        return "https://github.com/moikdev/droidcast/releases/download/$tag/$name"
    }
    fun validateAsset(release: NightlyRelease, asset: UpdateAsset, max: Long) {
        require(asset.url == assetUrl(release.tag, asset.name) && asset.bytes in 1..max) { "Invalid release asset address or size" }
    }
    fun candidate(release: NightlyRelease, manifest: UpdateManifest): UpdateCandidate {
        require(manifest.format == 1 && manifest.packageName == PACKAGE) { "This update is not for Droidcast" }
        require(manifest.versionCode in 1..2_100_000_000 && manifest.versionName.isNotBlank() && manifest.versionName.length <= 80) { "Invalid update version" }
        require(Regex("[a-f0-9]{40}").matches(manifest.commit) && hash.matches(manifest.sha256)) { "Invalid update identity" }
        require(manifest.apk.endsWith(".apk", true) && manifest.bytes in 1..MAX_APK) { "Invalid APK metadata" }
        val apk = requireNotNull(release.assets.singleOrNull { it.name == manifest.apk }) { "Release APK is missing or ambiguous" }
        val sums = requireNotNull(release.assets.singleOrNull { it.name == CHECKSUMS }) { "Release checksum is missing or ambiguous" }
        validateAsset(release, apk, MAX_APK); validateAsset(release, sums, MAX_METADATA)
        require(apk.bytes == manifest.bytes) { "APK size does not match release metadata" }
        return UpdateCandidate(release, manifest, apk, sums)
    }
    fun checksum(text: String, filename: String): String {
        val found = text.lineSequence().mapNotNull { line ->
            Regex("^([a-fA-F0-9]{64}) [ *](.+)$").matchEntire(line.trimEnd())?.let {
                if (it.groupValues[2] == filename) it.groupValues[1].lowercase() else null
            }
        }.toList()
        require(found.size == 1) { "APK checksum is missing or ambiguous" }
        return found.single()
    }
    fun verifyArchive(candidate: UpdateManifest, archive: ArchiveIdentity, installed: ArchiveIdentity, sdk: Int) {
        require(archive.packageName == PACKAGE && archive.packageName == installed.packageName) { "APK package does not match Droidcast" }
        require(archive.versionCode == candidate.versionCode && archive.versionName == candidate.versionName) { "APK version does not match release metadata" }
        require(archive.versionCode > installed.versionCode) { "This APK is not newer than the installed build" }
        require(archive.minSdk <= sdk) { "This update requires a newer Android version" }
        require(archive.signers.isNotEmpty() && archive.signers == installed.signers) { "APK signing certificate does not match the installed app" }
    }
    fun transportUrl(value: String): URI {
        val uri = URI(value)
        require(uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && uri.fragment == null &&
            uri.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) {
            "Update download must use an approved HTTPS host"
        }
        return uri
    }
}
