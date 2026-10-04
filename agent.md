# 开发手册（agent.md）

> 给接手维护这个项目的人：**约定、必须遵守的不变量、构建与验证流程、踩过的坑**。
> 写法上尽量写成可以直接照做的形式（命令、检查项、禁止事项），clone 下来也可以直接喂给
> AI 编程助手当上下文，让它照着这份约定改。
> 用户向文档见 [README.md](README.md)；更细的设计依据与历史结论放在作者设备 `docs/` 笔记里，此部分内容未入库。
> 仓库内容边界（什么入库、什么被忽略）见下面 §1 —— 动手加文件之前先看它。

---

*此文档由大模型完成编辑

请先阅读README.md并了解内容后再阅读本文！！！
请先阅读README.md并了解内容后再阅读本文！！！
请先阅读README.md并了解内容后再阅读本文！！！

## 0. 一分钟速览

| 项 | 内容 |
|---|---|
| 项目 | 小米 17 Pro / ProMax「背屏鼠标」：背屏当触控板，驱动主屏指针 |
| 包名 | `mz.mibackscreen.mouse` |
| 版本 | `1.0.3`（versionCode 6），见 `app/build.gradle.kts` |
| 技术栈 | Kotlin + Jetpack Compose(Material3)；助手是 C（NDK 交叉编译） |
| 前提 | **必须 root**（KernelSU / Magisk），App 通过 `su -c` 起 root 助手 |
| 许可证 | GPL-3.0（见 `LICENSE`） |
| 仓库规模 | 40 个文件、合计不到 300 KB（只有源码 + 文档 + Gradle wrapper） |
| 远端 | `origin` = GitHub，分支 `main` |

**代码分工（改东西先找对文件）**

| 位置 | 负责 | 说明 |
|---|---|---|
| `MainActivity.kt` | 主屏三页界面 | 全部 Compose UI 都在这一个文件里（底栏 主页/日志/设置 + 各卡片） |
| `core/` | 会话与提权 | `BackScreenController`(启停/投放/状态)、`HelperClient`(socket+鉴权)、`RootHelper`(助手进程)、`RootShell`(su)、`AppPrefs`、`Logs`、`WallpaperBackground`、`MouseSink`(输出抽象)、`BtHidMouse`(BLE HID 鼠标)、`BtMouseMode`(蓝牙模式唯一持有者) |
| `touchpad/` | 背屏上的那块面 | `TouchpadActivity`(纯黑 + 触点同步)、`TouchpadGestureEngine`(手势→鼠标指令)、`TouchIndicatorView` |
| `app/src/main/cpp/bsm_helper.c` | root 助手 | EVIOCGRAB 独占触摸 + uinput 虚拟鼠标 + socket 协议（单文件） |

---

## 1. 仓库结构与忽略规则

### 1.1 入库内容（40 个文件）

| 分组 | 内容 |
|---|---|
| 源码 | `app/src/main/`：`AndroidManifest.xml`、`cpp/bsm_helper.c`、`java/…/MainActivity.kt` + `core/` 7 个 + `touchpad/` 3 个、`res/` 5 个 xml |
| 构建配置 | `settings.gradle.kts`、`build.gradle.kts`、`app/build.gradle.kts`、`gradle.properties`、`gradle/libs.versions.toml` |
| Gradle wrapper | `gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.{jar,properties}` |
| 文档 | `README.md`、`agent.md`、`HANDOFF.txt`、`LICENSE` |
| 工具脚本 | `_tools/`（`wsl_build.sh`、`build.sh`、`build_helper.sh`、`probe*.sh`、`verify_apk.sh`、`rename_pkg.ps1`） |
| 忽略规则本身 | `.gitignore`、`.gitattributes` |

### 1.2 不入库内容（由 `.gitignore` 的 8 个分区处理）

| 路径 / 模式 | 体积 | 为什么不入库 | 对应分区 |
|---|---|---|---|
| `_decomp/`、`BackScreen.apk` | 36 + 18 MB | 反编译的小米官方资源与官方包（版权属小米） | 4) |
| `_refs/` | 0.6 MB | 第三方参考实现（版权属各自作者） | 4) |
| `adb/` | 79 MB | 本机 adb 工具 + 抓取的系统日志 | 4) |
| `artifacts/` | 102 MB | 设备截图、测试脚本、构建出的 APK | 4) |
| `docs/` | 36 KB | 本地开发笔记（设计依据、注释存档），自己看就行 | 4) |
| `release.jks`、`keystore.properties`（含 `release - 副本.jks` 之类副本） | 4 KB | **正式签名密钥与密码** | 3) |
| `local.properties` | — | 各机器不同的 SDK 路径 | 2) |
| `app/src/main/jniLibs/` | — | 助手是 `buildRootHelper` 的构建产物 | 1) |
| `.gradle/`、`build/`、`captures/`、`.cxx/`、`.kotlin/`、`*.apk`、`*.aab`、`*.dex` | — | 构建缓存与产物 | 1) |
| `.idea/`、`*.iml`、`.vscode/`、`.navigation/` 等 | — | IDE 个人配置 | 5) |
| `*.log`、`*.tmp`、`*.bak`、`*.orig`、`*.hprof`、`nohup.out` | — | 临时文件与日志 | 6) |
| `.DS_Store`、`Thumbs.db`、`desktop.ini`、`$RECYCLE.BIN/` | — | 系统垃圾文件 | 7) |

`.gitignore` **刻意没有** `*.jar` / `*.so` / `*.txt` / `*.json` 这类宽通配：会误伤 wrapper 的 jar、
证书文本等。文件末尾有一行 `!gradle/wrapper/gradle-wrapper.jar` 作防御性例外（若将来要加 `*.jar`，
必须让它排在例外之前）。

### 1.3 新增文件时该放哪

| 你要加的东西 | 放这里 | 会不会入库 |
|---|---|---|
| 代码 / 资源 | `app/src/main/…` | 会 |
| 设计说明、协议、FAQ 的长文本 | `docs/`（或 README/agent.md） | 会 |
| 构建、验证脚本 | `_tools/` | 会 |
| 临时产物、截图、抓的日志、构建出的 APK | `artifacts/` | 不会 |
| 第三方代码、反编译资料 | `_refs/`、`_decomp/` | 不会 |
| 密钥、密码、本机配置 | 仓库根目录 | 不会（已在忽略列表） |

原则：**只有「别人 clone 后需要用到、且有权分发」的文件才入库**；其余一律丢进上面那几类忽略目录。

### 1.4 换行符与二进制（`.gitattributes`）

`* text=auto` 由 Git 判断文本；`*.sh`、`gradlew` 固定 LF；`*.bat`、`*.cmd` 固定 CRLF；
`*.png`、`*.jpg`、`*.jar`、`*.jks`、`*.keystore` 标记为 binary。工作区里 Windows 侧文件是 CRLF
没关系，提交时会被规范化。

### 1.5 上传前 / 提交前自检

```bash
git ls-files | wc -l      # 应为 40（新增文件后相应增加）
git status --ignored      # 确认本机专属文件都处于 Ignored 状态
git add -A --dry-run      # 最后一道闸：不应出现密钥、大文件、构建产物
```

---

## 2. 本机环境事实（照抄即可）

| 项 | 值 |
|---|---|
| 设备序列号 | 用 `adb devices` 查；接多台设备时加 `-s <serial>` |
| adb | 必须用项目内的 `.\adb\adb.exe`（系统 adb 在本机不可用） |
| 构建 | 只能走 WSL：`wsl bash /mnt/d/Code/VSCode/Android/MiBackScreenMouse/_tools/wsl_build.sh assembleDebug` |
| 主屏 | Display 0，1220×2656，density 520，uniqueId `...331` |
| 背屏 | Display 1，904×572，density 450，uniqueId `...332` |
| 背屏触摸节点 | `/dev/input/event7`（`Xiaomi_Touch_Input_1`，`ABS_MT` 904×572）—— 设备号可能变，用 `--list` 复核 |
| 裁剪挖孔 | 主屏顶部 x=573~647, y=0~150（像素分析要避开） |
| 设备 su | `.\adb\adb.exe shell "su -c id"` → `uid=0`（可用） |
| 助手产物 | 当前编译出 `libbsm_helper.so` ≈ 21760 字节（改代码后会变，日志里会打印字节数） |

**几个必须知道的坑**

1. **别靠看截图判断 UI**：主界面截图很难肉眼比对（背后是壁纸、卡片又是半透明的）。用 `uiautomator dump` 拿控件树
   （Compose 会暴露 text + bounds），或用 `artifacts/*.ps1` 里的像素脚本（`gridmap.ps1` 能画
   ASCII 色块图，最好用）。
2. **主屏会自己休眠锁屏**（MainActivity 刻意没加 `FLAG_KEEP_SCREEN_ON`）。**一锁屏会话就结束**
   （见 §6 第 4 条），所以长时间调试前先：
   `adb shell "svc power stayon true; input keyevent 224; wm dismiss-keyguard"`，
3. **背屏上下文会影响下一次启动**：App 声明了 `miui.rear.policy`，如果在背屏有焦点时启动
   （例如刚 force-stop 掉一个在背屏上的实例），MainActivity 可能被系统建到背屏（Display 1）。
   要它回到主屏用 `am start --display 0 -n mz.mibackscreen.mouse/.MainActivity`。
4. **主屏焦点可能是 null**：触控板在背屏激活期间，主屏 `mCurrentFocus` 可能是 `null`，
   这时 `input tap` 打不到主屏上的按钮（`input -d 1 keyevent` 也不一定能结束触控板）。
   要结束会话请在主屏上点按钮，或直接 `am force-stop`（助手检测到 App 死亡会自动退出）。

---

## 3. 构建与安装

```bash
# 构建（WSL；脚本先同步工程到 ~/bsm、删掉源码侧已不存在的文件与旧 jniLibs，再构建并把 APK 拉回 artifacts/）
wsl bash /mnt/d/Code/VSCode/Android/MiBackScreenMouse/_tools/wsl_build.sh assembleDebug
wsl bash /mnt/d/Code/VSCode/Android/MiBackScreenMouse/_tools/wsl_build.sh assembleRelease

# 安装：装哪个包要和设备上已装的签名一致，否则会 INSTALL_FAILED_UPDATE_INCOMPATIBLE
.\adb\adb.exe install -r artifacts\app-release.apk    # 正式签名（本机这台设备装的就是它）
.\adb\adb.exe install -r artifacts\app-debug.apk      # 调试签名，得先 adb uninstall 才装得上
```

- 仓库里**有** Gradle wrapper（`gradlew` / `gradlew.bat` / `gradle/wrapper/gradle-wrapper.jar`），
  在能联网的机器上直接 `./gradlew assembleDebug` 也可以；本机因为 WSL 里已装好 Gradle 9.3.1
  且网络受限，所以用上面的脚本。
- 助手 `bsm_helper` 由 Gradle 任务 `buildRootHelper` 在 `preBuild` 前自动交叉编译到
  `app/src/main/jniLibs/arm64-v8a/libbsm_helper.so`。**该文件是构建产物、已被 .gitignore 忽略**，
  不要手工放旧文件进去：`wsl_build.sh` 每次构建前会 `rm -rf` 它强制重新编译。
- 只想快速校验助手能否编译：`wsl bash …/wsl_build.sh buildRootHelper`。
- **签名别混**：本机这台设备通常装的是 **release 签名**的包，`install -r` 一个 debug 包会直接失败
  （`INSTALL_FAILED_UPDATE_INCOMPATIBLE`；这时 App 不产生任何日志，很容易误判成「点了没反应」）。
  要么装 release 包，要么先 `adb uninstall mz.mibackscreen.mouse` 再装 debug（会清掉 App 设置）。

---

## 4. 改完必做的验证清单

1. `wsl …/wsl_build.sh assembleDebug` → 必须 `BUILD SUCCESSFUL`（日志里会打印 `bsm_helper -> … (字节数)`）；
2. 安装（签名要和设备上已装的一致，见 §3「签名别混」）：`adb install -r artifacts\app-release.apk`；
3. `adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml`，核对控件 text/bounds；
4. 残留检查：`adb shell "su -c pidof bsm_helper"` —— **正常应该是空的**（没会话就不该有助手）；
5. 端到端：点「启动触控板」，看日志应依次出现
   `助手已启动(root)` → 助手侧 `就绪 … (需 token 鉴权)` → `投放成功` → `已连接助手` →
   `等待客户端鉴权…` → `客户端鉴权通过` → `背屏触摸设备已获取独占` → `助手协议 v3`；
   点「停止触控板」应看到 `助手已完全退出（无残留进程）`，且 `pidof` 变空；
6. 锁屏即关：会话中点 `adb shell input keyevent 26`（电源键）→ 触控板应立即结束，
   日志出现「触控板不可见，结束会话」和「触控板会话已停止」，`pidof` 变空；
7. 运行细节看 App 内「诊断日志」卡片，或
   `adb logcat -s BackScreenMouse/Client BackScreenMouse/Session BackScreenMouse/Gesture`。

**动了协议/鉴权时，额外做一次「旁路验证」**（不用 UI，直接测助手）：

```bash
# 1) 从 APK 取出助手并推到设备（PowerShell 里做），然后手动起一个带 token 的实例
adb shell "su -c 'chmod 755 /data/local/tmp/bsm_helper; nohup /data/local/tmp/bsm_helper --daemon --debug --token test123 --tcp 38499 >/data/local/tmp/helper.log 2>&1 &'"
adb forward tcp:38499 tcp:38499
# 2) 用任意 TCP 客户端连 127.0.0.1:38499：
#    发 "A wrongtoken" → 应被立刻断开（read=0），助手日志「鉴权失败，断开连接」
#    发 "A test123"    → 应收 K / H 3 904 572 / S 助手就绪，日志「客户端鉴权通过」
#    再发 "Q"          → 助手应退出，pidof 为空
# 3) 收尾
adb forward --remove tcp:38499
adb shell "su -c 'rm -f /data/local/tmp/bsm_helper /data/local/tmp/helper.log'"
```

注意：助手有「10 秒无连接自动退出」的兜底，所以第 2 步必须在起进程后 10 秒内做完，
否则你会连到一个已经退出的助手（表现为连接立刻 EOF，容易误判成鉴权失败）。

---

## 5. 代码约定

- **注释风格（重要）**：中文、单行、只写「为什么」。**不要**写 Markdown 式 `##` 标题
  不要「注意：」「关键点：」这类说教句式，也不要复述代码本身在做什么。
  长篇的设计依据别塞在代码里：写进本机 `docs/` 笔记，代码里只留一句结论。
- UI 改动只在 `MainActivity.kt`；背屏触控板只在 `touchpad/`；提权相关只在 `core/RootShell.kt` /
  `core/RootHelper.kt`。
- 不新增依赖：现有依赖只有 androidx / compose / coroutines（见 `gradle/libs.versions.toml`），
  不用的条目要删掉。
- 复杂度能不加就不加：这是个小工具，单文件 C + 一个 Compose 文件是刻意的选择。
- 新增文件前先看 §1.3，别把临时产物/密钥/第三方资料留在会被提交的位置。

---

## 6. 不变量（改代码前必须知道，破坏会崩或留下垃圾）

1. **不做强制置顶投放**：不用 root 把 Activity 强行置顶到背屏，也不用 `setShowWhenLocked` /
   `setTurnScreenOn` 覆盖锁屏 —— 会拖崩 HyperOS 的 SystemUI 等系统相关组件，这是踩过的坑。
2. 不 `force-stop com.xiaomi.subscreencenter`、不写 `subscreen_display_time`、不覆盖锁屏；
   退出时也不需要「还原」，因为背屏桌面从未被接管。
3. `BackScreenController.sessionActive` **只存内存**：进程被杀后自动失效，系统恢复出来的
   TouchpadActivity 实例会据此自我结束（防幽灵触控板）。不要把它落盘。
4. 触控板一旦不可见（锁屏 / 被官方背屏顶掉 / 被切走）就立即复用 `disableTouchpad()` 收尾；
   **锁屏即会话结束**，不要引入任何「锁屏后再唤醒 / 搬移重建」的补救逻辑。
5. 助手退出有三重兜底，改动时别破坏：收到 `Q`、`app_alive()`（pid + **uid** 校验，防 pid 复用）、
   10 秒无连接自动退出；`client_disconnected()` 必须释放 `EVIOCGRAB`。
6. **协议 v4：控制口只开 App 私有目录内的文件系统 socket**（`<filesDir>/bsm.sock`，0600、属主为本 App），
   助手侧用 `SO_PEERCRED` **强制校验对端 uid**（`--uid`），不符直接拒绝且**不顶掉合法连接**；
   抽象命名空间与 TCP 口都不再默认监听（`--tcp` 仅手动调试）。
7. **token 只走一次性文件**：App 写 `<filesDir>/.bsm-token`（0600）→ 助手 `--token-file` 读取后立刻
   `unlink`；**token 绝不能出现在命令行**（`su -c … --token` 会让同机能读 ps 的角色拿到它）。
   没 token 时助手**拒绝以守护进程启动**（调试必须显式 `--no-auth`）。
8. 触点帧只有在助手独占设备、且连接**已鉴权**时才读（`g_touch_fd >= 0 && g_authed`），
   避免未鉴权的连接偷看触点数据。
8. 指针位移的余数必须在**输出域**扣减（`TouchpadGestureEngine.emitMove`），
   否则残差指数放大 → 指针数值爆炸、疯狂抖动。
9. 长按判定用 Handler 定时器，不依赖帧到达（手指按住不动时设备几乎不报帧）。
10. 助手识别背屏触摸设备的方式是「`ABS_MT` 范围 ×100 == 904×572」，换机型要改这里
    和 `TouchpadGestureEngine` 的目标尺寸默认值。
11. **蓝牙：先注册再广播、先停广播再注销**（`BtMouseMode.turnOn`、`BtHidMouse.close`），
    顺序反了会出现"主机搜不到"或"广告残留"。
12. **蓝牙权限必须先拿到**（`BLUETOOTH_CONNECT` + `BLUETOOTH_ADVERTISE`），
    否则 `registerApp` 直接抛 `SecurityException` 把 App 崩掉。
13. 蓝牙报告必须走节流合并（`BtHidMouse.queue/flushPending`）：触控板每帧都产生位移，
    逐帧写 BLE 会一顿一顿；平滑只改"这一步发多少"，**没发的余量必须留到下一轮，不能丢位移**。
14. 只有 `BtMouseMode` 能注册 HID；触控板会话只通过 `sink()` 取输出目标，
    **禁止在会话里再注册一次**（会互相顶掉）。
15. 检查更新用 `tag_name` 与 `BuildConfig.VERSION_NAME` 比较（忽略大小写与开头 `v`）；
    发新版必须把 release 标为 **Latest**，否则"检查更新"会指向旧版本。
16. 助手的调试开关（`--no-auth` / `--tcp` / `--token` / `--no-grab`）由 `BSM_DEBUG_TOOLS` 宏控制，
    **只有 debug 变体**才会带上（见 `buildRootHelper` 的 `wantDebugTools`）；正式包里这些开关
    一律被拒绝，且**必须**拿到 `--uid`。
17. 协议输入一律钳制（`MAX_MOVE` / `MAX_WHEEL`）；`V` 指令自报的 pid/uid 在能取到
    `SO_PEERCRED` 时**被忽略**（存活检查以真实凭据为准）。
18. 释放助手按**内容 SHA-256** 判定是否重写（`RootHelper.ensureInstalled`）：
    只比文件大小会漏掉"同大小不同内容"的情况，安全修复可能到不了本地助手。
19. token 比较用 `token_eq()`（恒时比较），不要改回 `strcmp`。

---

## 7. 常见故障速查

| 现象 | 原因 / 处理 |
|---|---|
| 点启动没反应、指针不动 | 先看 Root 是否可用（KernelSU 是否授权）；再看诊断日志有没有「已连接助手」「触点帧 N 帧/秒」 |
| 状态一直显示「运行中」 | 触控板一不可见就会自动收尾（锁屏、被顶掉、切走），刷新看看 |
| 背屏出现纯黑、点了没反应的触控板 | 进程被杀后系统恢复的旧实例；重新打开 App 点「停止触控板」 |
| 背屏触摸失灵 / 耗电 | 残留助手占着触摸设备：`adb shell "su -c pidof bsm_helper"`，重开 App 会自动清理，或 `su -c killall -9 bsm_helper` |
| 助手连不上 / 一直被断开 | 旧版 App 连新版助手（协议 v3 需要 `A <token>`）或 token 不一致；助手日志会打「等待客户端鉴权…」「鉴权失败，断开连接」 |
| 手动测助手时连接立刻 EOF | 大概率是助手已经「10 秒无连接」自动退出了（见 §4 的旁路验证注意事项） |
| 锁屏时启动失败 | 设计如此：系统锁屏时拒绝 `am start --display 1`，会话不启动（锁屏 = 没有触控板）。解锁后再点启动 |

---

## 8. 发布流程（GitHub）

1. 改版本号：`app/build.gradle.kts` 的 `versionCode` / `versionName`；
2. 构建正式包：`wsl …/wsl_build.sh assembleRelease` → `artifacts/app-release.apk`。
   正式签名读仓库根目录的 `keystore.properties` + `release.jks`（都已 gitignore，**不入库**）；
   **缺这两个文件时 `assembleRelease` 会直接失败**（`assembleDebug` 不受影响）——
   这是为了防止误把 debug 签名的包当正式包发出去。
   校验：`apksigner verify --verbose --print-certs artifacts/app-release.apk` →
   应输出 `Verifies`，指纹 `85:02:6F:…:8E:3F`，DN `CN=muzhou07, OU=muzhou07, O=muzhou07, C=CN`，RSA 4096，v2 方案；
3. 调试包用 `assembleDebug`（debug 签名）。注意：正式包与调试包签名不同，
   **同一台设备上不能互相覆盖安装**（要先卸载，或分开设备测）；
4. 提交前三连（见 §1.5）：`git ls-files | wc -l`、`git status --ignored`、`git add -A --dry-run`；
   确认没有 `artifacts/`、`adb/`、`_decomp/`、`_refs/`、`BackScreen.apk`、`local.properties`、
   `app/src/main/jniLibs/`、`release.jks`、`keystore.properties`；
5. 提交并推送；APK 用 **Releases 附件**发布，不要塞进仓库根目录；
6. 离线备份 `release.jks` + 密码（**别放在仓库目录里**）：丢了就再也无法用同一应用身份发更新；
7. 文档同步：用户可见的变化写进 README「更新记录」；影响开发方式的约定写进本文件；
8. 发完把该 release 标为 **Latest**（「检查更新」读的就是 `releases/latest`），APK 作为附件上传。

---

## 9. 命令速查

`<filesDir>` = `/data/data/mz.mibackscreen.mouse/files`。

```bash
# 设备状态
.\adb\adb.exe devices -l
.\adb\adb.exe shell "pidof bsm_helper; cmd display get-displays | grep -E 'Display id|state'"
.\adb\adb.exe shell "dumpsys activity activities | grep -E '^Display #|topResumedActivity'"

# 唤醒 + 解锁（截图 / UI 验证前）
.\adb\adb.exe shell "svc power stayon true; input keyevent 224; wm dismiss-keyguard"

# 取控件树（代替看截图）
.\adb\adb.exe shell uiautomator dump /sdcard/ui.xml; .\adb\adb.exe pull /sdcard/ui.xml

# 助手自检（会移动指针并单击）
.\adb\adb.exe shell "su -c '<filesDir>/bsm_helper --selftest'"
# 列出输入设备（确认背屏触摸节点）
.\adb\adb.exe shell "su -c '<filesDir>/bsm_helper --list'"
# 手动起一个需鉴权的助手（协议调试用，详见 §4）
.\adb\adb.exe shell "su -c 'nohup /data/local/tmp/bsm_helper --daemon --debug --token test123 --tcp 38499 >/data/local/tmp/helper.log 2>&1 &'"

# git 自检
git ls-files | wc -l ; git status --ignored ; git add -A --dry-run
```

蓝牙鼠标模式相关的调试（权限可以先给，避免每次都弹框）：

```bash
.\adb\adb.exe shell pm grant mz.mibackscreen.mouse android.permission.BLUETOOTH_CONNECT
.\adb\adb.exe shell pm grant mz.mibackscreen.mouse android.permission.BLUETOOTH_ADVERTISE
# 已配对设备 / HID 服务状态
.\adb\adb.exe shell "dumpsys bluetooth_manager | grep -E 'Bonded devices|HidDeviceService'"
```

控制口安全自检（会话运行中做）：

```bash
# 1) 助手命令行里不应出现 token（只该有 --sock/--uid/--token-file）
.\adb\adb.exe shell "ps -A -o USER,ARGS | grep bsm_helper"
# 2) 控制口 socket 应为 0600 且属主是本 App
.\adb\adb.exe shell "su -c 'ls -l /data/data/mz.mibackscreen.mouse/files/bsm.sock'"
# 3) 不该再监听 127.0.0.1:38472
.\adb\adb.exe shell "cat /proc/net/tcp | grep -i 9658"
# 4) 负向测试：换一个 uid 去连（这里 adb shell 常是 root=0），应被拒绝且不影响现有会话
.\adb\adb.exe shell "echo '' | toybox nc -U /data/data/mz.mibackscreen.mouse/files/bsm.sock"
```

---

## 10. 文档索引

| 文件 | 用途 |
|---|---|
| `README.md` | 面向用户：功能、安装、使用、手势、协议、构建、签名、FAQ |
| `agent.md` | 本文件：约定、不变量、验证清单、忽略规则、发布流程 |
| `docs/`（本机，不入库） | 开发笔记：设计依据、历史结论、踩坑记录（`BT_MOUSE_NOTES.md` 是蓝牙鼠标的验证结论与踩坑） |
| `HANDOFF.txt` | 第一轮开发交接记录（历史；最新状态以本文件为准） |
| `.gitignore` / `.gitattributes` | 入库边界与换行符/二进制规则 |
| `LICENSE` | GPL-3.0 全文 |
| `_tools` | 一些方便Agent设计与调试的脚本工具 |