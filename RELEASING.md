# Releasing VibeNotes

Releases use exact `vMAJOR.MINOR.PATCH` tags and titles, for example `v0.1.18`. Patch versions contain compatible fixes; minor versions add functionality. Before 1.0, describe incompatible changes explicitly. After 1.0, incompatible changes require a new major version.

## Publish a version

1. Update the top section of [CHANGELOG.md](CHANGELOG.md), then merge the tested changes into `main`.
2. Tag that commit, for example `git tag v0.1.18`, then `git push origin v0.1.18`.
3. Android CI validates the tag, verifies the commit belongs to `main`, runs core tests and the Android regex check, and builds the APK. The tag supplies Android's `versionName`; the existing workflow run counter supplies its increasing `versionCode`.
4. The publishing job rejects versions that are not newer than the latest stable release. It publishes a separate release containing `VibeNotes.apk`, `version.json`, and `SHA256SUMS`. The APK, manifest, and notes describe the same build.
5. Check installation and updating on a phone. The current builds use the existing shared **debug** signing key and package identity to remain compatible with previous installations. Production signing requires a separate migration plan.

Pushes to branches and pull requests run CI and upload build artifacts; they do not publish releases. Re-running a published tag must not replace its files: use a new patch version for a corrected build.

## Update compatibility

New builds read `releases/latest/download/version.json`, which resolves to the latest stable version. The historical `latest-build` release remains a **prerelease compatibility channel** for older installations. After a versioned release is published, its APK is mirrored there and its manifest points to the versioned APK. The legacy tag is retained; it is no longer force-moved.

The compatibility release is not a numbered product release. Do not delete it while older installed versions rely on its URL. Do not enable immutable releases for this legacy mirror: it needs to replace its two compatibility assets.
