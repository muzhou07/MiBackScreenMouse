package mz.mibackscreen.mouse

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mz.mibackscreen.mouse.core.AppPrefs
import mz.mibackscreen.mouse.core.BackScreenController
import mz.mibackscreen.mouse.core.BtMouseMode
import mz.mibackscreen.mouse.core.Logs
import mz.mibackscreen.mouse.core.RootShell
import mz.mibackscreen.mouse.core.WallpaperBackground

/** 卡片不透明度由「自定义外观」决定。 */

/** 蓝牙鼠标模式的运行时权限。 */
private val BT_PERMISSIONS = arrayOf(
    android.Manifest.permission.BLUETOOTH_CONNECT,
    android.Manifest.permission.BLUETOOTH_ADVERTISE,
)

private const val REQ_BT_PERMS = 1001

private fun hasBtPermissions(context: Context): Boolean = BT_PERMISSIONS.all {
    context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

/** 蓝牙鼠标模式的状态文字（供界面显示）。 */
private fun btStatusText(): String = when {
    !BtMouseMode.isOn -> "未开启"
    BtMouseMode.isConnected -> "已连接主机：${BtMouseMode.hostAddressMasked ?: "?"}"
    BtMouseMode.isAdvertising -> "已开启，等待主机连接"
    else -> "已注册，正在准备广播…"
}

/** 卡片与顶/底栏的外观：三个不透明度（0~1）。 */
private data class Appearance(
    val topAlpha: Float,
    val navAlpha: Float,
    val cardAlpha: Float,
) {
    companion object {
        val Default = Appearance(0.95f, 0.90f, 0.90f)

        fun from(prefs: AppPrefs) = Appearance(
            prefs.alphaTop / 100f,
            prefs.alphaNav / 100f,
            prefs.alphaCard / 100f,
        )
    }

    fun saveTo(prefs: AppPrefs) {
        prefs.alphaTop = (topAlpha * 100).toInt()
        prefs.alphaNav = (navAlpha * 100).toInt()
        prefs.alphaCard = (cardAlpha * 100).toInt()
    }
}

/**
 * 外观设置的进程内共享状态：设置页一改，顶栏/底栏/卡片立即生效。
 */
private object AppearanceState {
    var value by mutableStateOf(Appearance.Default)
        private set

    fun load(prefs: AppPrefs) {
        value = Appearance.from(prefs)
    }

    fun update(prefs: AppPrefs, next: Appearance) {
        value = next
        next.saveTo(prefs)
    }
}

private val LocalAppearance = compositionLocalOf { Appearance.Default }

class MainActivity : ComponentActivity() {

    /** 用户正在等蓝牙权限：授权通过后自动打开蓝牙鼠标模式。 */
    internal var pendingBtEnable = false

    /** 按「使用桌面背景」开关决定窗口底色：关 = 纯白，开 = 桌面壁纸（先白底兜底）。 */
    internal fun applyWindowBackground() {
        window.setBackgroundDrawable(ColorDrawable(Color.White.toArgb()))
        if (!AppPrefs(this).useWallpaper) return
        Thread {
            val bg = WallpaperBackground.of(this)
            runOnUiThread {
                if (!isFinishing && AppPrefs(this).useWallpaper) window.setBackgroundDrawable(bg)
            }
        }.start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BT_PERMS) return
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
        Logs.d("BtMouse", "蓝牙权限授权结果=$granted")
        if (granted && pendingBtEnable) {
            pendingBtEnable = false
            Thread { BtMouseMode.turnOn(applicationContext) }.start()
        } else if (!granted) {
            pendingBtEnable = false
            Logs.d("BtMouse", "未授予蓝牙权限，模式未打开")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 日志落盘：系统日志缓冲区刷掉后仍能事后排查
        Logs.attachFile(this)
        // 内容铺满全屏（含状态栏区域），顶部白色蒙版才能一直盖到屏幕最上沿
        enableEdgeToEdge()
        applyWindowBackground()
        AppearanceState.load(AppPrefs(this))
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAppearance provides AppearanceState.value) {
                    MainScreen()
                }
            }
        }
        // 无会话时清理上次崩溃残留的助手（它会一直占着触摸设备，且拖累背屏触摸）
        Thread {
            if (!BackScreenController.isTouchpadRunning()) BackScreenController.cleanupOrphanHelper(applicationContext)
        }.start()
    }
}

private data class Status(
    val root: Boolean = false,
    val touchpadRunning: Boolean = false,
    val touchpadDisplay: Int = -1,
    val launchReport: String = "-",
    val backDisplayPower: String = "-",
)

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPrefs(context) }

    var status by remember { mutableStateOf(Status()) }
    var onBackScreen by remember { mutableStateOf(prefs.showOnBackScreen) }

    fun refresh() {
        scope.launch {
            status = withContext(Dispatchers.IO) {
                val s = Status(
                    root = RootShell.isRootAvailable(),
                    touchpadRunning = BackScreenController.isTouchpadRunning(),
                    touchpadDisplay = BackScreenController.touchpadDisplayId,
                    launchReport = BackScreenController.lastLaunchReport?.text() ?: "-",
                    backDisplayPower = BackScreenController.backDisplayPowerState(),
                )
                Logs.d(
                    "App",
                    "刷新: root=${s.root} 触控板=${s.touchpadRunning} 屏幕=${s.touchpadDisplay} " +
                        "背屏=${s.backDisplayPower}",
                )
                s
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // 标题栏高度自己量，滚动内容据此下移（不写死，系统字体放大也不错位）
    var titleBarHeight by remember { mutableStateOf(0) }
    val titleBarHeightDp = with(LocalDensity.current) { titleBarHeight.toDp() }

    var tab by remember { mutableStateOf(Tab.HOME) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    Tab.HOME -> HomeTab(
                        status = status,
                        onBackScreen = onBackScreen,
                        onBackScreenChange = {
                            onBackScreen = it
                            prefs.showOnBackScreen = it
                        },
                        onRefresh = { refresh() },
                        topPad = titleBarHeightDp + 20.dp,
                    )

                    Tab.LOGS -> LogsTab(titleBarHeightDp + 20.dp)

                    Tab.SETTINGS -> SettingsTab(titleBarHeightDp + 20.dp)
                }
            }

            BottomNav(selected = tab, onSelect = { tab = it })
        }

        // 写在滚动 Column 之后 → 永远叠在内容之上，始终置顶
        TopTitleBar(
            title = if (tab == Tab.HOME) "背屏鼠标" else tab.label,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onGloballyPositioned { titleBarHeight = it.size.height },
        )
    }
}

/** 底部导航的三个页面。 */
private enum class Tab(val label: String) {
    HOME("主页"),
    LOGS("日志"),
    SETTINGS("设置"),
}

/** 顶部白色蒙版标题栏：始终置顶，内部用状态栏内边距给标题让位。 */
@Composable
private fun TopTitleBar(title: String, modifier: Modifier = Modifier) {
    val alpha = LocalAppearance.current.topAlpha
    // 顶栏越透明，越靠浅色文字 + 阴影保证可读
    val transparent = alpha < 0.5f
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = alpha)),
    ) {
        Text(
            text = title,
            style = if (transparent) {
                MaterialTheme.typography.headlineSmall.copy(
                    shadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 2f), 4f),
                )
            } else {
                MaterialTheme.typography.headlineSmall
            },
            color = if (transparent) Color.White else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp),
        )
    }
}

/** 首页：状态 + 触控板 + 蓝牙鼠标模式 + 手感。 */
@Composable
private fun HomeTab(
    status: Status,
    onBackScreen: Boolean,
    onBackScreenChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    topPad: Dp,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPrefs(context) }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = topPad, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(title = "状态") {
            StatusRow("Root", status.root)
            StatusRow("触控板会话", status.touchpadRunning)
            KeyValueRow(
                "触控板所在屏幕",
                when (status.touchpadDisplay) {
                    1 -> "背屏 (Display 1)"
                    0 -> "主屏"
                    else -> "-"
                },
            )
            KeyValueRow("背屏投放结果", status.launchReport)
            KeyValueRow("背屏 (Display 1) 状态", status.backDisplayPower)
        }

        SectionCard(title = "触控板") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("投放到背屏 (Display 1)", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = onBackScreen, onCheckedChange = onBackScreenChange)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            val ok = withContext(Dispatchers.IO) {
                                if (!RootShell.isRootAvailable()) {
                                    Logs.d("App", "未获得 root：仍启动触控板，但助手不可用（退回窗口触摸）")
                                }
                                BackScreenController.enableTouchpad(context)
                            }
                            Logs.d("App", if (ok) "启用成功" else "启用失败")
                            busy = false
                            onRefresh()
                        }
                    },
                ) { Text("启动触控板") }

                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            withContext(Dispatchers.IO) { BackScreenController.disableTouchpad(context) }
                            busy = false
                            onRefresh()
                        }
                    },
                ) { Text("停止触控板") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(enabled = !busy, onClick = onRefresh) { Text("刷新状态") }
            }
        }

        BtMouseModeCard(prefs)

        BtFeelCard(prefs)

        FeelSettingsCard(prefs)
    }
}

/** 日志页：诊断日志（可清空；文本可长按选中复制）。 */
@Composable
private fun LogsTab(topPad: Dp) {
    val logs = Logs.lines
    val context = LocalContext.current
    val prefs = remember { AppPrefs(context) }
    var diag by remember { mutableStateOf(prefs.diagMode) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 20.dp, end = 20.dp, top = topPad, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(title = "诊断日志", modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { Logs.clear() }) { Text("清空日志") }
                OutlinedButton(onClick = { exportLogs(context) }) { Text("导出日志") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("诊断模式（记录触摸坐标）", style = MaterialTheme.typography.bodySmall)
                Switch(
                    checked = diag,
                    onCheckedChange = {
                        diag = it
                        prefs.diagMode = it
                    },
                )
            }
            HorizontalDivider()
            Text(
                text = if (logs.isEmpty()) "（暂无日志）" else logs.joinToString("\n"),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

/** 设置页：自定义外观 + 关于。 */
@Composable
private fun SettingsTab(topPad: Dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPrefs(context) }
    val appearance = AppearanceState.value
    var useWallpaper by remember { mutableStateOf(prefs.useWallpaper) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = topPad, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(title = "自定义外观") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("使用桌面背景", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = useWallpaper,
                    onCheckedChange = {
                        useWallpaper = it
                        prefs.useWallpaper = it
                        (context as? MainActivity)?.applyWindowBackground()
                    },
                )
            }

            AlphaSlider("顶栏不透明度", appearance.topAlpha) {
                AppearanceState.update(prefs, appearance.copy(topAlpha = it))
            }
            AlphaSlider("底栏不透明度", appearance.navAlpha) {
                AppearanceState.update(prefs, appearance.copy(navAlpha = it))
            }
            AlphaSlider("卡片不透明度", appearance.cardAlpha) {
                AppearanceState.update(prefs, appearance.copy(cardAlpha = it))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { AppearanceState.update(prefs, Appearance.Default) }) {
                    Text("恢复默认")
                }
            }
        }

        SectionCard(title = "关于") {
            Text(text = "作者：muzhou07", style = MaterialTheme.typography.bodyMedium)
            Text(text = "版本号：$currentVersion", style = MaterialTheme.typography.bodyMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    enabled = !checkingUpdate,
                    onClick = {
                        checkingUpdate = true
                        scope.launch {
                            val info = withContext(Dispatchers.IO) { fetchLatestRelease() }
                            checkingUpdate = false
                            when {
                                info == null -> {
                                    Logs.d("Update", "检查更新失败（网络或接口异常）")
                                    Toast.makeText(
                                        context,
                                        "检查更新失败，请检查网络",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }

                                sameVersion(info.version, currentVersion) -> {
                                    Logs.d("Update", "已是最新版：$currentVersion")
                                    Toast.makeText(context, "已经是最新版", Toast.LENGTH_SHORT).show()
                                }

                                else -> {
                                    Logs.d("Update", "发现新版本：${info.version}（当前 $currentVersion）")
                                    updateInfo = info
                                }
                            }
                        }
                    },
                ) { Text(if (checkingUpdate) "检查中…" else "检查更新") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { openUrl(context, "https://github.com/muzhou07/MiBackScreenMouse") },
                ) { Text("Github主页") }

                OutlinedButton(
                    onClick = { openUrl(context, "https://www.coolapk.com/u/39919381") },
                ) { Text("作者酷安") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { openUrl(context, "https://afdian.com/a/muzhou07") },
                ) { Text("爱发电") }
            }
        }
    }

    // 有新版本时的详情窗口
    updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { updateInfo = null },
            title = { Text("发现新版本") },
            text = {
                Column {
                    Text(
                        text = "当前版本：$currentVersion",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "最新版本：${info.version}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("更新日志", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = info.notes,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isTrustedDownloadUrl(info.downloadUrl)) {
                            openUrl(context, info.downloadUrl)
                        } else {
                            Logs.d("Update", "下载地址不可信，已拦截: ${info.downloadUrl}")
                            Toast.makeText(context, "下载地址不可信，已拦截", Toast.LENGTH_LONG).show()
                        }
                        updateInfo = null
                    },
                ) { Text("下载更新") }
            },
            dismissButton = {
                TextButton(onClick = { updateInfo = null }) { Text("返回") }
            },
        )
    }
}

/** 底部导航栏：主页 / 日志 / 设置。 */
@Composable
private fun BottomNav(selected: Tab, onSelect: (Tab) -> Unit) {
    val alpha = LocalAppearance.current.navAlpha
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = alpha),
    ) {
        NavigationBarItem(
            selected = selected == Tab.HOME,
            onClick = { onSelect(Tab.HOME) },
            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
            label = { Text(Tab.HOME.label) },
        )
        NavigationBarItem(
            selected = selected == Tab.LOGS,
            onClick = { onSelect(Tab.LOGS) },
            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
            label = { Text(Tab.LOGS.label) },
        )
        NavigationBarItem(
            selected = selected == Tab.SETTINGS,
            onClick = { onSelect(Tab.SETTINGS) },
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text(Tab.SETTINGS.label) },
        )
    }
}

/** 卡片配色：Material3 默认底色 + 「自定义外观」里的卡片不透明度。 */
@Composable
private fun panelCardColors(): CardColors =
    CardDefaults.cardColors(
        containerColor = CardDefaults.cardColors().containerColor
            .copy(alpha = LocalAppearance.current.cardAlpha),
    )

/** GitHub 最新 release 的关键信息。 */
private data class UpdateInfo(
    val version: String,
    val notes: String,
    val downloadUrl: String,
)

/** 仓库的最新 release 接口。 */
private const val UPDATE_API =
    "https://api.github.com/repos/muzhou07/MiBackScreenMouse/releases/latest"

/** 当前版本号（来自 build.gradle.kts 的 versionName）。 */
private val currentVersion: String get() = BuildConfig.VERSION_NAME

/**
 * 拉取最新 release（阻塞，请在 IO 线程调用）；失败返回 null。
 * 需要带 User-Agent；下载地址优先取 assets 里的 .apk，没有则退回 release 页面。
 */
private fun fetchLatestRelease(): UpdateInfo? {
    return try {
        val conn = (URL(UPDATE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "MiBackScreenMouse/$currentVersion")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Logs.d("Update", "检查更新失败：HTTP $code")
                return null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val tag = json.optString("tag_name").ifBlank { json.optString("name") }

            var apkUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
            }

            UpdateInfo(
                version = tag,
                notes = json.optString("body").ifBlank { "（该版本没有填写更新日志）" },
                downloadUrl = apkUrl ?: json.optString("html_url"),
            )
        } finally {
            conn.disconnect()
        }
    } catch (t: Throwable) {
        Logs.d("Update", "检查更新异常：${t.javaClass.simpleName} ${t.message}")
        null
    }
}

/** 比较版本号：忽略大小写与开头的 v。 */
private fun sameVersion(a: String, b: String): Boolean =
    a.trim().removePrefix("v").removePrefix("V")
        .equals(b.trim().removePrefix("v").removePrefix("V"), ignoreCase = true)

/** 更新下载地址白名单：只放行 https + GitHub 官方域名，避免被诱导打开任意 scheme/站点。 */
private fun isTrustedDownloadUrl(url: String): Boolean {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    val host = uri.host?.lowercase() ?: return false
    return host == "github.com" ||
        host == "codeload.github.com" ||
        host.endsWith(".githubusercontent.com")
}

/** 用系统浏览器打开链接。 */
private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (t: Throwable) {
        Logs.d("App", "打开链接失败: ${t.javaClass.simpleName} ${t.message}")
    }
}

/**
 * 把当前日志缓冲导出成 `bsm-log-<时间戳>.txt` 到系统「下载」目录（MediaStore，免存储权限）；
 * 写入失败时退回应用外部私有目录。
 */
private fun exportLogs(context: Context) {
    val content = Logs.text()
    if (content.isBlank()) {
        Toast.makeText(context, "暂无日志可导出", Toast.LENGTH_SHORT).show()
        return
    }

    val name = "bsm-log-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"

    if (runCatching { saveToDownloads(context, name, content) }.getOrDefault(false)) {
        Logs.d("Logs", "日志已导出到 下载/$name")
        Toast.makeText(context, "已导出到「下载/$name」", Toast.LENGTH_LONG).show()
        return
    }

    // 兜底：写进应用外部私有目录（adb 也能直接取）
    runCatching {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, name)
        file.writeText(content)
        Logs.d("Logs", "日志已导出到 ${file.absolutePath}")
        Toast.makeText(context, "已导出到 ${file.absolutePath}", Toast.LENGTH_LONG).show()
    }.onFailure {
        Logs.d("Logs", "日志导出失败: ${it.javaClass.simpleName} ${it.message}")
        Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_LONG).show()
    }
}

/** 通过 MediaStore 写入系统下载目录；成功返回 true。 */
private fun saveToDownloads(context: Context, name: String, content: String): Boolean {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
    val wrote = resolver.openOutputStream(uri)?.use { out ->
        out.write(content.toByteArray())
        true
    } ?: false
    if (!wrote) {
        runCatching { resolver.delete(uri, null, null) }
        return false
    }
    values.clear()
    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    return true
}

@Composable
private fun BtFeelCard(prefs: AppPrefs) {
    val context = LocalContext.current
    var flushMs by remember { mutableStateOf(prefs.btFlushMs) }
    var smooth by remember { mutableStateOf(prefs.btSmooth) }

    SectionCard(title = "蓝牙鼠标手感") {
        Text(
            text = "报告节流窗口：${flushMs}ms（越小越跟手，越大越稳）",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = flushMs.toFloat(),
            valueRange = 4f..40f,
            steps = 8,
            onValueChange = {
                flushMs = it.toInt()
                prefs.btFlushMs = flushMs
                BtMouseMode.applyFeel(context)
            },
        )

        Text(
            text = "位移平滑：${smooth}%（越大越顺，延迟略增）",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = smooth.toFloat(),
            valueRange = 0f..90f,
            steps = 8,
            onValueChange = {
                smooth = it.toInt()
                prefs.btSmooth = smooth
                BtMouseMode.applyFeel(context)
            },
        )
    }
}

@Composable
private fun BtMouseModeCard(prefs: AppPrefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var btOn by remember { mutableStateOf(BtMouseMode.isOn) }
    var btMsg by remember { mutableStateOf(btStatusText()) }

    // 模式状态变化（注册/广播/主机连接）→ 刷新开关与状态文字
    DisposableEffect(Unit) {
        val listener: (String) -> Unit = {
            btOn = BtMouseMode.isOn
            btMsg = btStatusText()
        }
        BtMouseMode.addListener(listener)
        onDispose { BtMouseMode.removeListener(listener) }
    }

    SectionCard(title = "蓝牙设置") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("蓝牙鼠标模式", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = btOn,
                onCheckedChange = { want ->
                    if (want) {
                        if (hasBtPermissions(context)) {
                            scope.launch {
                                withContext(Dispatchers.IO) { BtMouseMode.turnOn(context) }
                                prefs.btMouseMode = true
                                btOn = BtMouseMode.isOn
                                btMsg = btStatusText()
                            }
                        } else {
                            // 先要权限，授权通过后由 MainActivity 自动打开
                            (context as? MainActivity)?.pendingBtEnable = true
                            (context as? android.app.Activity)
                                ?.requestPermissions(BT_PERMISSIONS, REQ_BT_PERMS)
                        }
                    } else {
                        scope.launch {
                            withContext(Dispatchers.IO) { BtMouseMode.turnOff() }
                            prefs.btMouseMode = false
                            btOn = false
                            btMsg = btStatusText()
                        }
                    }
                },
            )
        }

        KeyValueRow("当前状态", btMsg)

        Text(
            text = "配对后即可用背屏触控板控制对方（不支持苹果设备）",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                enabled = BtMouseMode.isConnected,
                onClick = { BtMouseMode.disconnectHost() },
            ) { Text("断开当前主机") }
        }
    }
}

@Composable
private fun FeelSettingsCard(prefs: AppPrefs) {
    var sensitivity by remember { mutableStateOf(prefs.sensitivity) }
    var longPress by remember { mutableStateOf(prefs.longPressMs) }
    var natural by remember { mutableStateOf(prefs.naturalScroll) }
    var accel by remember { mutableStateOf(prefs.accelEnabled) }
    var revX by remember { mutableStateOf(prefs.reverseX) }
    var revY by remember { mutableStateOf(prefs.reverseY) }

    SectionCard(title = "手感设置") {
        Text(
            text = "灵敏度 ${"%.1f".format(sensitivity)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = sensitivity,
            valueRange = 0.4f..3.0f,
            steps = 25,
            onValueChange = {
                sensitivity = it
                prefs.sensitivity = it
            },
        )

        Text(
            text = "长按判定 ${longPress}ms",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = longPress.toFloat(),
            valueRange = 300f..1200f,
            steps = 17,
            onValueChange = {
                longPress = it.toInt()
                prefs.longPressMs = it.toInt()
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "自然滚动", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = natural,
                onCheckedChange = {
                    natural = it
                    prefs.naturalScroll = it
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "指针速度加速", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = accel,
                onCheckedChange = {
                    accel = it
                    prefs.accelEnabled = it
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "左右反向", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = revX,
                onCheckedChange = {
                    revX = it
                    prefs.reverseX = it
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "上下反向", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = revY,
                onCheckedChange = {
                    revY = it
                    prefs.reverseY = it
                },
            )
        }
    }
}

/** 不透明度滑块（0~100%）。 */
@Composable
private fun AlphaSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Text(
        text = "$label：${(value * 100).toInt()}%",
        style = MaterialTheme.typography.bodyMedium,
    )
    Slider(
        value = value * 100f,
        valueRange = 0f..100f,
        steps = 19,
        onValueChange = { onChange(it / 100f) },
    )
}

@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        // 默认铺满宽度；传 modifier 可让卡片在页面里伸缩（例如日志页撑满剩余高度）
        modifier = modifier.fillMaxWidth(),
        // 半透明卡片：背后的壁纸会淡淡透出来
        colors = panelCardColors(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, value: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = if (value) "可用 / 运行中" else "不可用 / 未运行",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
