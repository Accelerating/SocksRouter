# 自动发布 APK

提交 `.github/workflows/release.yml` 后，每次向 GitHub 推送新 tag，Actions 都会构建已签名的 Release APK，并将 APK 和 SHA-256 校验文件上传到对应的 GitHub Release。普通分支提交不会触发发布。

当前 APK 支持 Android 12（API 31）及以上的 arm64-v8a 设备，与项目现有构建配置一致。

## 首次配置

在仓库 **Settings → Secrets and variables → Actions → New repository secret** 中添加两个 Repository secrets：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 项目现有 `socksrouter-release.keystore` 的 Base64 内容 |
| `ANDROID_SIGNING_PROPERTIES_BASE64` | 项目现有 `release-signing.properties` 的 Base64 内容 |

在 macOS 本机分别执行以下命令，将内容复制到剪贴板后粘贴到对应 Secret：

```bash
base64 < socksrouter-release.keystore | tr -d '\n' | pbcopy
base64 < release-signing.properties | tr -d '\n' | pbcopy
```

每执行一条命令后先保存对应 Secret，再执行下一条。

`release-signing.properties` 应采用以下格式。`storeFile` 必须使用下面的相对路径，不要使用本机绝对路径；密码和别名使用现有签名文件的实际值，并遵循 Java Properties 的转义规则：

```properties
storeFile=socksrouter-release.keystore
storePassword=你的密钥库密码
keyAlias=socksrouter
keyPassword=你的密钥密码
```

继续使用现有签名文件，才能覆盖安装此前使用同一签名的版本。签名文件和密码配置已被 `.gitignore` 排除，不要提交到仓库。工作流使用 GitHub 自动提供的 `GITHUB_TOKEN` 创建 Release，无需额外配置个人访问令牌。

## 发布版本

先提交并推送代码（包括工作流），再为要发布的提交创建并推送 tag：

```bash
git tag v1.0.1
git push origin v1.0.1
```

在仓库 **Actions → Release APK** 查看构建进度。成功后，在 **Releases → 对应 tag → Assets** 下载 `SocksRouter-arm64-v8a.apk`；`SHA256SUMS.txt` 可用于校验下载文件。

所有 tag 都会触发发布，推荐使用 `v1.0.1` 这样的版本名。APK 的 `versionName` 使用 tag 名并去掉开头的 `v`，`versionCode` 使用该工作流的 `GITHUB_RUN_NUMBER + 1`（第一次为 2）。后续发布应保持此工作流的运行编号递增；同一次运行的重试保持版本号不变。

构建或签名验证失败时不会创建新 Release。配置好 Secrets 后，可在 Actions 中重试失败的运行；重试已有 Release 的运行会替换同名附件。当前所有 tag 都创建正式 Release，包括名称含有 `beta` 或 `rc` 的 tag。

本地构建默认版本仍为 `1.0` / `1`，也可手动指定：

```bash
./gradlew :app:assembleRelease -PreleaseVersionName=1.0.1 -PreleaseVersionCode=2
```

参考：[GitHub CLI 创建 Release](https://cli.github.com/manual/gh_release_create)、[AGP 9.1 构建依赖版本](https://developer.android.com/build/releases/agp-9-1-0-release-notes)。
