# Production signing and nightly continuity

Issue [#21](https://github.com/moikdev/droidcast/issues/21) remains open. This is a release contract to implement and verify, not a claim that production signing is configured.

## Current identities

The published nightly channel uses package `dev.tinycast.android` and certificate SHA-256:

```text
e7bee2db540fcbb0633d82870b2c4e3d14ecc0c18474b1a7c73d47eb0bf92c08
```

The installed r10 versionCode is 6. Current updater validation requires exact package, newer version, manifest/checksum identity and the same active signing certificate. The release packager independently pins the same nightly certificate. Neither component currently accepts certificate rotation. Random CI debug certificates and the disposable benchmark package are excluded from that channel.

## Owner-controlled production key

The maintainer must select an existing controlled keystore or a dedicated new key. Keep the encrypted keystore and its recovery copy outside the repository; publish only certificate fingerprints, signed APKs and integrity metadata. Passwords must enter signing tools through a private file/secret mechanism, never a command-line literal, repository file, PR body or build log. Key ownership and recoverability must be established before publishing a production channel.

A production artifact must be nondebuggable, tied to an immutable implementation commit, built with a monotonic versionCode, verified with apksigner and tested as those exact bytes. The packager must not rebuild or re-sign an APK after its validation.

## A different certificate requires an upgrade plan

A new certificate cannot simply replace the current nightly signer. The current in-app verifier would reject it, even if Android could validate a supported signing lineage. Do not weaken that verifier to accept arbitrary signers from a release manifest.

A migration needs all of the following:

1. A reviewed bridge update signed by the existing nightly key, carrying a narrowly defined production trust transition.
2. Cryptographically verified signing lineage or an explicit, maintainer-reviewed clean-install/data-migration path. A changed package would also require a separate updater/channel and data migration.
3. Release-feed sequencing that still lets old r10 clients obtain the bridge. Publishing an incompatible certificate as their newest nightly first would strand their current updater.
4. API-specific evidence. Android's rotated-key behavior differs by platform version; retain the minimum-API contract rather than assuming an API36 success proves API26 continuity.
5. Actual emulator installation tests covering old→bridge→production, rejection of unrelated/forged certificates, cancellation/retry, retained collections/grants/extension data and recovery after interruption. Preserve original failed attempts.

Android's apksigner supports a signed certificate lineage and an explicit rotation minimum SDK. Its default rotated-key behavior targets API33+, with an explicit option for API28+. Earlier platforms use the unrotated signer. These capabilities do not establish that Droidcast's current verifier or release feed supports the transition.

## Required release evidence

Record the exact source and artifact hashes, application ID, versionCode/name, debuggable state, active certificate and lineage policy, build/tool versions, updater metadata, test identities, restoration results and two independent exact-head audits. Keep production and nightly trust policies explicit. A functioning debug-signed nightly is not production-signing certification.

Primary references: [Android app signing](https://developer.android.com/studio/publish/app-signing) and [apksigner rotation/options](https://developer.android.com/tools/apksigner).
