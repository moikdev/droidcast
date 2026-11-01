package dev.tinycast.android

import dev.tinycast.android.updates.*
import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val tag = "nightly-2026-11-01-r10"
    private val name = "Droidcast-nightly-r10.apk"
    private val hash = "a".repeat(64)
    private val manifest = UpdateManifest(1, UpdatePolicy.PACKAGE, 7, "0.5.1-nightly", "a".repeat(40), name, hash, 1234)
    private fun asset(name: String, bytes: Long) = UpdateAsset(name, UpdatePolicy.assetUrl(tag, name), bytes)
    private val release get() = NightlyRelease(tag, "Nightly r10", "Public release notes", "2026-11-01T00:00:00Z",
        listOf(asset(name, 1234), asset(UpdatePolicy.CHECKSUMS, 120)))
    private val installed = ArchiveIdentity(UpdatePolicy.PACKAGE, 6, "0.5.0-nightly", setOf("trusted"), 26)
    private val archive = installed.copy(versionCode = 7, versionName = manifest.versionName)
    @Test fun candidateRequiresOneBoundedMatchingAsset() {
        assertEquals(name, UpdatePolicy.candidate(release, manifest).apk.name)
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.candidate(release.copy(assets = release.assets + release.assets.first()), manifest) }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.candidate(release, manifest.copy(bytes = 1235)) }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.candidate(release, manifest.copy(bytes = UpdatePolicy.MAX_APK + 1)) }
    }
    @Test fun releaseCannotRedirectDiscoveryToAnotherRepoOrPrivateHost() {
        for (url in listOf("http://github.com/moikdev/droidcast/release.apk", "https://github.com/evil/repo/releases/download/$tag/$name",
            "https://127.0.0.1/file.apk", "https://github.com:443/moikdev/droidcast/releases/download/$tag/$name",
            "https://user@github.com/moikdev/droidcast/releases/download/$tag/$name")) {
            val bad = release.copy(assets = listOf(release.assets.first().copy(url = url), release.assets.last()))
            assertThrows(url, IllegalArgumentException::class.java) { UpdatePolicy.candidate(bad, manifest) }
        }
    }
    @Test fun onlyExplicitApprovedHttpsRedirectHostsAreAccepted() {
        UpdatePolicy.transportUrl("https://release-assets.githubusercontent.com/some/file?temporary=token")
        for (url in listOf("http://github.com/file", "https://github.com.evil.example/file", "https://localhost/file",
            "https://user@github.com/file", "https://github.com:8443/file", "https://objects.githubusercontent.com/file#fragment"))
            assertThrows(url, IllegalArgumentException::class.java) { UpdatePolicy.transportUrl(url) }
    }
    @Test fun manifestRejectsWrongPackageSchemaOrInvalidIdentity() {
        for (bad in listOf(manifest.copy(format = 2), manifest.copy(packageName = "dev.evil"), manifest.copy(versionCode = 0),
            manifest.copy(commit = "../bad"), manifest.copy(apk = "../file.apk"), manifest.copy(sha256 = "bad"))) {
            assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.candidate(release, bad) }
        }
    }
    @Test fun checksumMustMatchExactlyOneFilename() {
        assertEquals(hash, UpdatePolicy.checksum("$hash  $name\n", name))
        assertEquals(hash, UpdatePolicy.checksum("${hash.uppercase()} *$name\n", name))
        for (text in listOf("$hash  other.apk", "$hash  $name\n$hash  $name\n", "bad  $name", "$hash  path/$name"))
            assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.checksum(text, name) }
    }
    @Test fun exactSignerAndStrictlyNewerArchiveAreRequired() {
        UpdatePolicy.verifyArchive(manifest, archive, installed, 36)
        for (bad in listOf(archive.copy(signers = emptySet()), archive.copy(signers = setOf("attacker")), archive.copy(signers = setOf("trusted", "other")),
            archive.copy(packageName = "dev.other"), archive.copy(versionName = "different"), archive.copy(minSdk = 37))) {
            assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.verifyArchive(manifest, bad, installed, 36) }
        }
        for (code in listOf(5L, 6L)) assertThrows(IllegalArgumentException::class.java) {
            UpdatePolicy.verifyArchive(manifest.copy(versionCode = code), archive.copy(versionCode = code), installed, 36)
        }
    }
}
