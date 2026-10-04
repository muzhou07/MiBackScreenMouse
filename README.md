# 背屏鼠标（MiBackScreenMouse）

此项目为将小米17 Pro的背屏改造为触控板使用

此项目目前仅适配了小米17 Pro单机型，17 Pro Max与18 Pro系列机型请自行修改代码或者等我更新

此项目的功能与实现均已经与小米 17 Pro / Android 17 / Hyper OS 4 Beta上测试可用

*此项目大量代码使用AI完成，作者仅编写部分代码，属于Vibe Coding作品且此文章使用AI编写

需要 root 才能跑。背面副屏的触摸事件得用内核接口抢过来自己读。

```
手指（背面 904×572）
        │
        ▼  /dev/input/eventN ── EVIOCGRAB 独占
┌─────────── bsm_helper（root 守护进程，C 写的） ───────────┐
│  · 抓住背面的触摸设备，系统 InputReader 收不到事件         │
│  · 建一个 /dev/uinput 虚拟鼠标                             │
│  · 用本地 socket 把触点帧发给 App，并从 App 收回鼠标指令    │
└──────────────────────────┬─────────────────────────────────┘
   触点帧 T <seq> <n> <id x y>…  │  M dx dy / B n 0|1 / W ±n / H ±n
┌──────────────────────────┴─────────────────────────────────┐
│  App（Kotlin + Compose）                                    │
│  · TouchpadActivity —— 背面的纯黑触控板 + 触点指示          │
│  · TouchpadGestureEngine —— 触点帧 → 手势 → 鼠标指令        │
│  · MainActivity —— 主屏上的设置页（启停、手感、日志）       │
└─────────────────────────────────────────────────────────────┘
                 → uinput 虚拟鼠标 → 主屏指针
```

（下面提到的「助手」都指 `bsm_helper` 这个小程序。）

## 能做什么

- 背面一块纯黑触控板，手指落下有圆点和拖尾（照着系统录屏里「显示点按操作反馈」做的）
- 单指滑动/轻点/长按，双指滚动和右键，三指中键
- 灵敏度、长按判定、滚动方向、左右上下反向都能在主界面调，改完重启一次触控板生效
- **蓝牙鼠标模式**：手机本身变成一只 BLE 鼠标，电脑/安卓设备配对后，用背屏触控板直接控制对方（本机指针不动）
- 界面分三页：**主页**（状态/触控板/蓝牙模式/手感）、**日志**（可清空、可导出）、**设置**（外观/关于/检查更新）
- 「自定义外观」可调顶栏、底栏、卡片的不透明度，背景可切纯白或桌面壁纸
- 「关于」里有版本号与**检查更新**（读 GitHub 最新 release，有新版可直达下载）

## 手势

| 操作 | 效果 |
|---|---|
| 单指滑动 | 移动指针 |
| 单指轻点 | 左键单击 |
| 单指长按（默认 500ms） | 按住右键，接着拖动就是右键拖拽 |
| 双击后按住拖动 | 按住左键拖拽 |
| 双指滑动 | 滚轮（横竖都支持） |
| 双指轻点 | 右键单击 |
| 三指轻点 | 中键单击 |

长按是用定时器判定的，不靠触点帧——手指按住不动的时候设备本身几乎不上报数据。

## 蓝牙鼠标模式

让这台手机本身当鼠标（走标准 BLE HID，不需要额外安装任何东西）：

1. 主页打开「**蓝牙鼠标模式**」开关（第一次会申请两个蓝牙权限）
2. 在电脑 / 安卓平板 / 电视盒子的蓝牙里搜索本机名字，配对并连接
3. 状态变成「已连接主机：XX:XX:…」后点「启动触控板」，背屏手势就发给对方了（**本机指针不动**）
4. 关掉开关即恢复普通蓝牙状态

限制与说明：

- 支持 **Windows** 与 **安卓**（Windows 11、安卓手机/平板实测可用）
- **不支持苹果设备**（iPadOS / macOS）：对方只会走经典蓝牙通道，连上即断，指针出不来，做不到
- 一次只能连一台主机；换主机前先在 App 里点「断开当前主机」
- Android 的 HID 设备角色**不会自己广播**，所以 App 会自己发一条带 HID 服务 UUID 的 BLE 广告——主机的蓝牙列表能搜到这台手机，靠的就是它
- 蓝牙手感与「本机模式」分开调：`报告节流窗口`（4–40ms）与`位移平滑`（0–90%），在主页「蓝牙鼠标手感」卡片里

## 需要什么

- 小米 17 Pro （背面 904×572，主屏 1220×2656）
- HyperOS / Android 15 及以上
- KernelSU 或 Magisk，并给这个 App 授权

别的机型没适配过。助手是按「触摸设备的 ABS_MT 范围 ×100 正好等于 904×572」来认背屏的，认不出来就不会启动会话。

## 装和用

1. 装 APK（Releases 里有，或者照下面自己编译），包名 `mz.mibackscreen.mouse`
2. 打开 App 会申请 root 权限，同意
3. 主界面「触控板」卡片里保持「投放到背屏 (Display 1)」开着，点「启动触控板」
4. 背面亮起黑屏触控板，手指在上面划就能控制主屏指针。点「停止触控板」结束，也可以在触控板上按返回键 / 上滑

```bash
adb install -r MiBackScreenMouse-1.0.0-release.apk
```

debug 包和 release 包签名不一样，同一台手机上没法互相覆盖安装，换包要先卸载。

## 设置项

| 设置 | 默认 | 说明 |
|---|---|---|
| 投放到背屏 (Display 1) | 开 | 关掉就只在 App 当前那块屏显示 |
| 灵敏度 | 1.3 | 0.4–3.0，越大指针跑得越快 |
| 长按判定 | 500ms | 300–1200ms |
| 自然滚动 | 开 | 双指下滑 = 页面向下 |
| 指针速度加速 | 开 | 划得快时位移放大，最多 3 倍 |
| 左右反向 | 开 | 手指右滑指针左移（背屏拿在手里这样顺手） |
| 上下反向 | 关 | 上下方向反过来 |
| 蓝牙鼠标模式 | 关 | 打开后手机变成 BLE 鼠标，可被电脑/安卓设备连接 |
| 报告节流窗口 | 8ms | 4–40ms，仅蓝牙模式：越小越跟手，越大越稳 |
| 位移平滑 | 0% | 0–90%，仅蓝牙模式：越大越顺、延迟略增 |
| 使用桌面背景 | 关 | 关=纯白背景，开=用桌面壁纸 |
| 顶栏/底栏/卡片不透明度 | 95/90/90% | 「自定义外观」里调，0–100% |
| 诊断模式（记录触摸坐标） | 关 | 「日志」页里开；开了日志才会记录触摸坐标 |

## 原理

背面在系统里是 HyperOS 的副屏桌面（Display 1）。投放就一步：`am start --display 1` 把触控板直接建到副屏
（Activity 要带 `miui.rear.policy` 属性才放行）。系统在锁屏时会拒绝这类启动，所以**锁屏就等于没有触控板**：
不解锁就投不上去，目前也没做补救。整个过程不动副屏桌面、不杀别的进程、也不覆盖锁屏——窗口只是盖在上面，
退出的时候不用做什么「还原」。

几道保险都是踩过坑才补上的：

- 进程被杀之后系统会把旧的触控板实例恢复出来，那个是点不动的。所以没有会话的实例会在 `onCreate` 里自己结束
- App 崩溃或被系统杀掉可能留下 root 助手，它一直占着触摸设备，既耗电又让背面触摸失灵。所以打开 App 时发现没在投放就清理一遍
- 触控板一旦不可见（锁屏、被官方副屏顶掉、被切走）就直接按停止处理：释放触摸独占、退出助手
- 助手那边还有三重退出兜底：收到 `Q`、App 主进程没了（pid 和 uid 一起校验）、10 秒没人连

### 蓝牙鼠标是怎么做的

- 用 `BluetoothHidDevice`（HID device 角色）注册一只标准 HOGP 鼠标：5 键 + X/Y + 滚轮 + AC Pan，报告 5 字节
- 注册完**还必须自己发一条可连接的 BLE 广告**（HID 服务 UUID `0x1812` + 设备名）——系统不会替 HID device 角色广播，不广播主机根本搜不到
- 手势的输出目标用 `MouseSink` 抽象：本机模式=发指令给助手（uinput），蓝牙模式=转成 BLE 报告，手势识别只认这一个接口
- 报告做了**节流 + 合并 + 平滑**：触控板每帧都产生位移（100Hz 上下），而 BLE 每秒能发的报告有限，逐帧写就会一顿一顿
- 权限：`BLUETOOTH_CONNECT`（注册）+ `BLUETOOTH_ADVERTISE`（广播），运行时授权

## 安全设计

这个 App 要 root，所以本地攻击面按「同机其它 App 都可能恶意」来设计：

- **控制口不放行任何人**：助手只在 App 私有目录（`filesDir`，目录 0700）里建 socket，权限 0600、
  属主是 App，并用 `SO_PEERCRED` 校验对端 uid；别的 App 既连不上、也无法抢先占位冒充
- **鉴权串不进命令行**：每会话随机的 token 走一次性文件（0600，助手读完即删）。
  `su -c … --token <hex>` 那种写法会让能读 `ps` 的角色直接拿到 token，所以不用
- **没有 token 就不启动**：助手拒绝在无 token 的情况下做守护进程（手动调试必须显式 `--no-auth`）
- **不监听 TCP**：`127.0.0.1` 端口任何本机 App 都能连，默认不开（`--tcp` 只用于调试）
- **触控板 Activity 不导出**（`exported=false`）：只有本应用和 root 的 `am start` 能投放它
- **日志脱敏**：蓝牙主机地址只留首尾两段；触摸坐标默认不记录（「日志」页里可开「诊断模式」）
- **检查更新只走 GitHub 官方域名**（https 白名单），不会打开任意链接
- 正式包必须带正式密钥（缺 `keystore.properties` / `release.jks` 时构建直接失败），避免发出 debug 签名的包

## 通信协议

App 和助手之间就是逐行文本，一行一条，当前版本 `PROTO_VER = 4`。

控制口是 **App 私有目录内的 unix socket**：`<filesDir>/bsm.sock`（权限 0600，属主为本 App）。
助手起来后会用 `SO_PEERCRED` 校验对端 uid，**不是本 App 的连接直接拒绝**（也不会顶掉正在用的连接）；
抽象命名空间和 `127.0.0.1` TCP 口都不再默认监听（`--tcp PORT` 只在手动调试时开）。

鉴权串（token）每会话随机生成（16 字节），**不走命令行**：App 写进 `<filesDir>/.bsm-token`（0600），
助手用 `--token-file` 读取后立刻删除；客户端连上后第一条必须发 `A <token>`，
验过才去独占触摸设备并开始回数据；3 秒不给或给错，直接断开。没有 token 时助手拒绝启动守护进程。

助手 → App：

| 消息 | 说明 |
|---|---|
| `K` | 就绪（已独占触摸设备、已建好虚拟鼠标） |
| `H <proto> <srcW> <srcH>` | 协议版本 + 触摸设备像素范围 |
| `T <seq> <n> [<id> <x> <y>]...` | 触点帧，坐标是背面像素（0..903 / 0..571） |
| `S` / `E` | 状态 / 错误（都会打到日志里） |
| `R <ping>` | 心跳回显 |

App → 助手：

| 消息 | 说明 |
|---|---|
| `A <token>` | 鉴权，连上后第一条 |
| `M <dx> <dy>` | 相对移动（助手再拆成 ±200 的小步） |
| `B <1\|2\|3> <1\|0>` | 左/右/中键按下、抬起 |
| `W <±n>` / `H <±n>` | 垂直 / 水平滚轮 |
| `P <t>` | 心跳，3 秒一次，10 秒没响应就当断了 |
| `V <proto> <pid> <uid>` | 告诉助手 App 的 pid，App 没了助手跟着退 |
| `Q` | 收工：放掉独占、销毁虚拟鼠标、退出 |

助手还有些命令行开关：`--sock PATH` 控制口 socket 路径、`--uid N` 允许的客户端 uid、
`--token-file PATH` 一次性 token 文件（正式路径都走这三个）；`--list` 列输入设备（用来确认哪一个是背屏触摸）、
`--device PATH` 手动指定设备、`--selftest` 移一下指针点一下左键自检、`--no-grab` 不独占触摸、
`--no-auth` 关掉鉴权、`--tcp PORT` 开调试用 TCP 口、`--token HEX` 调试用明文 token、`--debug` 多打日志。
（`--no-auth` / `--tcp` / `--token` / `--no-grab` 都只用于手动调试。）

## 自己编译

JDK 17、Android SDK（compileSdk 37）、NDK 29.0.14206865、Gradle 9.3.1（AGP 9.1.0 + Kotlin 2.4.0）。仓库里带了 wrapper，不用先装 Gradle：

```bash
./gradlew assembleDebug        # Windows 用 gradlew.bat assembleDebug
```

助手不用手动编，Gradle 任务 `buildRootHelper` 会在打包前把 `app/src/main/cpp/bsm_helper.c` 交叉编译成 `lib/arm64-v8a/libbsm_helper.so` 塞进 APK。

我这边是在 Windows 上写、在 WSL 里编（Windows 侧原生 Gradle 跑这套 AGP + NDK 一直有毛病），所以留了个 `_tools/wsl_build.sh`：把工程同步到 WSL 的 `~/bsm`、编完再把 APK 拉回 `artifacts/`。脚本里的路径是按我自己的机器写的，你用要改一下。

## 签名

你自己 clone 下来本项目由于签名文件缺失会导致最终编译使用的是 debug 签名，使用 debug 签名的应用与 release 版本无法共存，请自行卸载

> 打正式包（`assembleRelease`）时如果缺 `keystore.properties` / `release.jks`，构建会**直接失败**，
> 避免不小心把 debug 签名的包发出去；`assembleDebug` 不受影响。

```bash
./gradlew assembleRelease
apksigner verify --verbose --print-certs app-release.apk
```

密钥是 JKS + RSA 4096，别名 `mibackscreen`，证书指纹 SHA-256
`85:02:6F:C7:58:36:E8:84:50:A8:AF:AD:D3:A9:65:32:C5:A6:01:CB:48:53:78:DE:F4:15:8B:97:35:85:8E:3F`
——从外面下载到 APK 的话，可以用 `apksigner verify --print-certs` 对一下这串。

## 已知问题 / 还没做的

- 只在小米 17 Pro 上验证过，别的机型没条件试
- 锁屏就会关掉触控板（锁屏状态下系统不允许把窗口建到副屏），解锁后得重新点一次启动
- 加速只有开/关，没有曲线；也没有双指缩放、三指滑动切应用这类手势
- App 和助手协议版本不一致时会直接断开并提示重装（协议 v4 起控制口规则也变了，旧助手不能混用）
- 蓝牙模式的指针手感还偏「跳」（BLE 报告率限制），已给出手感旋钮但还没调到满意
- 蓝牙鼠标模式注册在 App 进程里：进程被系统回收后主机就断了（常驻前台服务还没做）
- 苹果设备（iPadOS / macOS）不支持蓝牙鼠标模式

## 常见问题

**截图一片黑，或者看着像锁屏？**
主界面我没加屏幕常亮（不想让 App 一直亮着）。调试前先：
`adb shell "svc power stayon true; input keyevent 224; wm dismiss-keyguard"`
顺便提一句：主屏一锁屏，触控板就会关掉（见下面「锁屏之后触控板会怎样」），所以调试时最好先打开 stayon。

**背面出现一块点了没反应的黑屏？**
那是进程被杀之后被系统恢复出来的旧实例。重开 App 点一下「停止触控板」就没了。

**锁屏之后触控板会怎样？**
直接关掉。锁屏即视为关闭触控板：会话结束、触摸独占释放、助手退出；解锁后要重新点一次启动。
（锁屏状态下系统本来也不允许把窗口建到副屏。）

**背面触摸失灵、还很耗电？**
多半是残留的助手还占着触摸设备：
```bash
adb shell "su -c pidof bsm_helper"      # 有输出就是残留
adb shell "su -c killall -9 bsm_helper"
```
重开 App 也会自动清理。

**点了启动，指针却不动？**
先看状态卡里 Root 是不是「可用」（KernelSU 授权了没），再看日志里有没有「已连接助手」「触点帧 N 帧/秒」。没有帧说明触摸设备没认出来，可以 `su -c '/data/data/mz.mibackscreen.mouse/files/bsm_helper --list'` 看看有没有 904×572 的那个节点。

**想看运行日志？**
App 里切到底栏「日志」页就行，还能点「导出日志」存成 txt 到系统下载目录。想在电脑上看：
```bash
adb logcat -s BackScreenMouse/Client BackScreenMouse/Session BackScreenMouse/Gesture BackScreenMouse/Touchpad BackScreenMouse/BtMouse
```
遇到说不清的问题，把状态卡和这段日志一起贴出来，基本就能定位。

**蓝牙鼠标模式打开后，电脑/平板搜不到？**
先确认开关是打开的、状态显示「已开启，等待主机连接」（主机列表里显示的就是本机蓝牙名）。搜不到多半是主机缓存了旧配对，先在主机蓝牙里「忽略/删除」这台设备，再重新搜索。

**蓝牙鼠标连上了，但指针一跳一跳？**
BLE 每秒能发的报告有限。把主页「蓝牙鼠标手感」里的`位移平滑`调到 40–70%，或把`报告节流窗口`从 8ms 调到 12–16ms，会明显更均匀（代价是略微延迟）。

**iPad / Mac 能用吗？**
不能。苹果设备会走经典蓝牙通道，配对后连上即断、没有指针，已确认不做适配。

## 免责

自己写着玩的，要 root，涉及独占输入设备和虚拟鼠标注入，有风险。
用之前请确认符合你当地的法律法规和保修条款，出现未知问题导致的错误请自行承担。

## 致谢

背屏投放和背屏壁纸的思路参考了 MiRearScreenSwitcher 和 back-screen-wallpaper 这两个项目

## 版本

**1.0.1** — 安全加固：控制口从「抽象 unix socket + 127.0.0.1 TCP」改为 **App 私有目录内的文件系统 socket（0600）并强制校验对端 uid**，杜绝同机 App 抢占/冒充；会话 token 改走**一次性文件**（不再出现在 `su -c` 命令行），没 token 时助手拒绝启动；触控板 Activity 改为 `exported=false`；日志脱敏（蓝牙地址打码、触摸坐标默认不记录，新增「诊断模式」开关）；检查更新只允许 GitHub 官方域名；正式包缺密钥时构建直接失败；并修掉了「幽灵触控板实例」在结束时会崩溃的问题。

**1.0.0** — 新增「蓝牙鼠标模式」：手机本身当 BLE 鼠标（Windows/安卓可用，苹果不支持），带节流窗口与位移平滑两个手感旋钮；界面改为「主页 / 日志 / 设置」三页底栏；新增「自定义外观」（顶栏/底栏/卡片不透明度、纯白或桌面壁纸背景）；日志可导出到系统下载目录；「关于」卡片加入版本号与检查更新（读 GitHub 最新 release）。

**0.1.1** — 给助手的控制口加了握手鉴权（之前本机任何 App 都能连上去注入鼠标）；发送队列满时优先保住按键和停止指令；停止会话不再卡在锁里等；清理残留助手时顺手把陈旧的会话标记复位；锁屏即视为关闭触控板；并且清理掉了部分AI写的冗余源码。

**0.1.0** — 第一个能用的版本：纯黑触控板 + 触点指示、完整手势、背屏投放、状态卡和诊断日志（此版本未发布）。

## 许可

GPL-3.0，全文见 [LICENSE](LICENSE)。

```
Copyright (C) 2026 muzhou07

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```
