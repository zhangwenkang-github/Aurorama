# 开发环境（本机实测）

## 已确认可用的工具链

| 组件 | 版本 / 路径 | 说明 |
| --- | --- | --- |
| JDK | `D:\Android\Android Studio\jbr`（OpenJDK 25） | **必须使用**，工程按 Java 21 编译，JDK 17 会报 “无效的源发行版：21” |
| 备选 JDK | `D:\Android\jdk\jdk-17.0.20.1+1` | 仅供其他 JDK 17 工程使用 |
| Android SDK | `D:\Android\AndroidSDK` | platforms 36/37，build-tools 36/37 |
| Gradle | 工程自带 wrapper（9.7.1），发行包缓存在 `~/.gradle/wrapper/dists` | 由 `gradlew.bat` 自动下载 |
| 编译产物 | `app/phone/build/outputs/apk/libre/debug/*.apk` | 按 ABI 拆分，单包 85~92 MB（debug） |

构建命令（PowerShell）：

```powershell
cd E:\codex_work\Android_Studio_Work_Space\Cinefin
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
.\gradlew.bat :app:phone:assembleDebug --console=plain
```

## 模拟器

| 项 | 值 |
| --- | --- |
| 系统镜像 | `system-images;android-36;google_apis;x86_64`（API 36 / Android 16） |
| 镜像下载包 | `D:\Android\SysImages\x86_64-36_r07.zip`（1.8 GB，已解包进 SDK） |
| AVD 目录 | `D:\Android\AVD`（环境变量 `ANDROID_AVD_HOME`） |
| AVD 名称 | `CinefinTablet`，2560×1600 / 320 dpi / 4 GB RAM / 4 核 |

**待办（需要管理员权限）**：当前 `emulator -accel-check` 报 “Android Emulator hypervisor driver is not installed”，
即没有硬件虚拟化加速，模拟器无法正常使用。安装方法（以管理员身份运行 PowerShell）：

```powershell
D:\Android\SysImages\aehd\silent_install.bat
```

安装后重启一次，`emulator -accel-check` 应显示加速可用。

## 网络与代理

* 直连可用：`dl.google.com`、`repo1.maven.org`、`services.gradle.org`（实测 200）。
* 直连不通、需要走代理：`github.com`（`api.github.com` 可用）。
* 系统里已有代理环境变量 `http_proxy` / `https_proxy`（形如 `https://用户名:密码@主机:端口`）。
  Java 工具不认识这种写法，需要时改用 Gradle 的 `systemProp.https.proxyHost/proxyPort/proxyUser/proxyPassword`。
* 本机 Android SDK 命令行工具（`sdkmanager` / `android sdk list`）**无法连接仓库**，
  但 curl 直连 Google 仓库正常，因此 SDK 组件采用“官方仓库 URL + curl 手动下载 + 本地解包”的方式安装。

## 测试服务器（只读使用）

* 地址：`https://jellyfins.zhangwenkang.com`（443）与 `http://jellyfins.zhangwenkang.com`（80）均可用
* 证书：受信任 CA 签发（`ssl_verify_result=0`），无需自签名例外配置
* 版本：Jellyfin Server 10.11.8，ServerId `0af95147e738465cb26c6c036f92ff22`
* 账号：`admin`（管理员）、`zhangwenkang`（普通用户）

> ⚠️ 该服务器为生产环境，**只允许只读接口调用**，禁止任何写入/删除操作。
> 凭据不写入仓库，联调时通过本地未纳入版本库的文件或环境变量注入。
