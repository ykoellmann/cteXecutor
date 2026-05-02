# How to release a new version

## Prerequisites (one-time setup)

Before the first automated release, the plugin must exist on JetBrains Marketplace.
Upload the plugin ZIP manually once via [plugins.jetbrains.com](https://plugins.jetbrains.com) to create the listing.
After that, all releases are automated.

The following secrets must be set in **GitHub → Settings → Secrets and variables → Actions**:

| Secret | Description |
|---|---|
| `PUBLISH_TOKEN` | From plugins.jetbrains.com → your profile → My Tokens |
| `CERTIFICATE_CHAIN` | Contents of `chain.crt` (generated with OpenSSL) |
| `PRIVATE_KEY` | Contents of `private_key_rsa.pem` — must start with `-----BEGIN RSA PRIVATE KEY-----` |
| `PRIVATE_KEY_PASSWORD` | Passphrase chosen during key generation |
| `GH_DEPLOY_TOKEN` | GitHub PAT with `repo` scope |

## Release steps

### 1. Update `CHANGELOG.md`

Move items from `[Unreleased]` into a new versioned section:

```markdown
## [2.1.0] - 2026-05-10
### feat
- Your new feature
### fix
- Your bug fix
```

### 2. Bump the version in `PluginInfo.kt`

```kotlin
const val VERSION = "2.1.0"
```

If users should see the update notification again, also bump `NOTIFICATION_VERSION` and update `NOTIFICATION_CONTENT`.

### 3. Commit and push

```bash
git add CHANGELOG.md src/main/kotlin/com/ykoellmann/ctexecutor/PluginInfo.kt
git commit -m "release 2.1.0"
git push
```

### 4. Push a version tag

```bash
git tag v2.1.0
git push origin v2.1.0
```

### 5. Done

GitHub Actions automatically:
- Patches `CHANGELOG.md` via `patchChangelog`
- Signs and publishes the plugin to JetBrains Marketplace
- Triggers a rebuild of the website changelog

## Changelog format reference

```markdown
## [Unreleased]
### feat
- New thing

## [2.1.0] - 2026-05-10
### feat
- Completed feature
### fix
- Bug fixed
### break
- Breaking change (shortcut, API, etc.)
```
