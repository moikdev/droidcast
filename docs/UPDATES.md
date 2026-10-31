# In-app nightly updates

Implementation in progress under [#55](https://github.com/moikdev/droidcast/issues/55). Existing r9 installations need one manual bootstrap APK to gain the updater. This is not a claim that the feature is released yet.

The Updates page lives in fullscreen Settings; Check for Updates opens the same content inside the floating palette. Network access is manual. No account, token, Accessibility service, overlay, automatic polling or notification permission is involved. Download progress, cancellation, retry, release notes, Android source approval and installer confirmation stay explicit. Android-owned approval/installer screens are the only external screens.

## Discovery and trust

The public GitHub releases listing includes prereleases, unlike the stable-only latest endpoint. Droidcast inspects non-draft nightly tags and the ten most recently published updater manifests among at most 100 releases. It chooses the highest versionCode in that bounded window. Older nightlies without metadata are excluded.

Each eligible release supplies `droidcast-update.json`, its named APK and `SHA256SUMS.txt`. Metadata identifies format 1, packageName, versionCode, versionName, source commit, APK filename, SHA-256 and byte length. Only fixed `moikdev/droidcast` release asset URLs are accepted. Downloads require HTTPS and an explicit GitHub/CDN host allowlist, limited redirects, response bounds and connect/read/deadline limits. The app sends no GitHub credential.

Before staging, the checksum file and manifest must agree. Downloaded bytes must match the exact size/hash, and Android archive metadata must match Droidcast's package, declared version, a strictly newer versionCode, compatible minimum SDK and exact current signing-certificate set. Legitimate certificate rotation is conservatively unsupported; it would require a separately reviewed migration. Android's installer performs its own package verification as well.

The process-wide coordinator serializes recovery, downloads, installation and result handling across activity/controller replacement. Partial files never become installable. Verified files are private and reverified after recovery and immediately before installation. Session writes are hashed again before commit. Uncommitted sessions are abandoned on failure/cancellation; saved sessions are reconciled after interruption. These contracts still require runtime validation and independent audits.

## Android installation

PackageInstaller stages private bytes without exporting APK URIs. A non-exported receiver handles an explicit mutable PendingIntent tied to a persisted session ID and random token. `STATUS_PENDING_USER_ACTION` is retained for a visible Continue with Android action; no background activity launch is required.

Android 8+ controls per-source install approval. Confirmation is enabled by default. Users can turn off Confirm each update; on API31+ that asks for USER_ACTION_NOT_REQUIRED for user-requested self-updates. The app declares UPDATE_PACKAGES_WITHOUT_USER_ACTION. Android documents self-update eligibility but retains platform/OEM confirmation fallback. This implementation promises neither silent installation nor physical Lenovo certification.

## Release versioning

The bootstrap uses versionCode 6, following legacy r1-r9 code 5. Every subsequent published updater build must strictly increase the code. Build with explicit `droidcastVersionCode`, `droidcastVersionName` and `droidcastBuildCommit` Gradle properties before freezing/test/audit. The commit is embedded in BuildConfig and manifest metadata.

`scripts/package-nightly-update.py` packages an already-tested APK; it does not rebuild/sign it. It checks the embedded package/version/source commit, the existing nightly certificate and public published version codes before writing the APK, update manifest and checksum file. Publish those three exact files with the release, then verify public download bytes. Development fixtures must not be published as eligible nightlies.

## Primary references

- [GitHub public releases listing](https://docs.github.com/en/rest/releases/releases#list-releases)
- [Android user-action and self-update conditions](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int))
- [PackageInstaller session commit](https://developer.android.com/reference/android/content/pm/PackageInstaller.Session#commit(android.content.IntentSender))
- [Per-source install approval](https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_UNKNOWN_APP_SOURCES)
