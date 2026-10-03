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
adb install -r MiBackScreenMouse-0.1.1-release.apk
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

## 原理

背面在系统里是 HyperOS 的副屏桌面（Display 1）。投放就一步：`am start --display 1` 把触控板直接建到副屏
（Activity 要带 `miui.rear.policy` 属性才放行）。系统在锁屏时会拒绝这类启动，所以**锁屏就等于没有触控板**：
不解锁就投不上去，我也没做补救。整个过程不动副屏桌面、不杀别的进程、也不覆盖锁屏——窗口只是盖在上面，
退出的时候不用做什么「还原」。

几道保险都是踩过坑才补上的：

- 进程被杀之后系统会把旧的触控板实例恢复出来，那个是点不动的。所以没有会话的实例会在 `onCreate` 里自己结束
- App 崩溃或被系统杀掉可能留下 root 助手，它一直占着触摸设备，既耗电又让背面触摸失灵。所以打开 App 时发现没在投放就清理一遍
- 触控板一旦不可见（锁屏、被官方副屏顶掉、被切走）就直接按停止处理：释放触摸独占、退出助手
- 助手那边还有三重退出兜底：收到 `Q`、App 主进程没了（pid 和 uid 一起校验）、10 秒没人连

## 通信协议

App 和助手之间就是逐行文本，一行一条，当前版本 `PROTO_VER = 3`。默认连 `127.0.0.1:38472`，连不上就退到抽象 unix socket `@bsm-helper`，断了自动重连。

socket 是本机回环、谁都能连，所以加了道握手：启动助手时 App 会给一串本次会话随机生成的 token，客户端连上后第一条必须发 `A <token>`，助手验过才去独占触摸设备并开始回数据；3 秒不给或者给错，直接断开。

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

助手还有些命令行开关：`--list` 列输入设备（用来确认哪一个是背屏触摸）、`--device PATH` 手动指定设备、`--selftest` 移一下指针点一下左键自检、`--no-grab` 不独占触摸（纯调试用）、`--token` 要求鉴权、`--tcp` 换端口、`--debug` 多打日志。

## 自己编译

JDK 17、Android SDK（compileSdk 37）、NDK 29.0.14206865、Gradle 9.3.1（AGP 9.1.0 + Kotlin 2.4.0）。仓库里带了 wrapper，不用先装 Gradle：

```bash
./gradlew assembleDebug        # Windows 用 gradlew.bat assembleDebug
```

助手不用手动编，Gradle 任务 `buildRootHelper` 会在打包前把 `app/src/main/cpp/bsm_helper.c` 交叉编译成 `lib/arm64-v8a/libbsm_helper.so` 塞进 APK。

我这边是在 Windows 上写、在 WSL 里编（Windows 侧原生 Gradle 跑这套 AGP + NDK 一直有毛病），所以留了个 `_tools/wsl_build.sh`：把工程同步到 WSL 的 `~/bsm`、编完再把 APK 拉回 `artifacts/`。脚本里的路径是按我自己的机器写的，你用要改一下。

## 签名

你自己 clone 下来本项目由于签名文件缺失会导致最终编译使用的是 debug 签名，使用 debug 签名的应用与 release 版本无法共存，请自行卸载

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
- App 和助手版本对不上时（协议不一致）助手会直接断开，日志里能看到

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
App 里点开「诊断日志」卡片就行。想在电脑上看：
```bash
adb logcat -s BackScreenMouse/Client BackScreenMouse/Session BackScreenMouse/Gesture BackScreenMouse/Touchpad
```
遇到说不清的问题，把状态卡和这段日志一起贴出来，基本就能定位。

## 免责

自己写着玩的，要 root，涉及独占输入设备和虚拟鼠标注入，有风险。
用之前请确认符合你当地的法律法规和保修条款，出现未知问题导致的错误请自行承担。

## 致谢

背屏投放和背屏壁纸的思路参考了 MiRearScreenSwitcher 和 back-screen-wallpaper 这两个项目

## 版本

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
