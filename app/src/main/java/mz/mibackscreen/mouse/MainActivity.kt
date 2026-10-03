package mz.mibackscreen.mouse

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mz.mibackscreen.mouse.core.AppPrefs
import mz.mibackscreen.mouse.core.BackScreenController
import mz.mibackscreen.mouse.core.Logs
import mz.mibackscreen.mouse.core.RootShell
import mz.mibackscreen.mouse.core.WallpaperBackground

/** 卡片不透明度。 */
private const val PANEL_ALPHA = 0.9f

/** 顶部标题栏蒙版不透明度。 */
private const val TITLE_BAR_ALPHA = 0.95f

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 内容铺满全屏（含状态栏区域），顶部白色蒙版才能一直盖到屏幕最上沿
        enableEdgeToEdge()
        // 窗口背景先黑兜底，随后在后台换成桌面壁纸
        window.setBackgroundDrawable(ColorDrawable(Color.Black.toArgb()))
        Thread {
            val bg = WallpaperBackground.of(this)
            runOnUiThread { if (!isFinishing) window.setBackgroundDrawable(bg) }
        }.start()
        setContent {
            MaterialTheme {
                SettingsScreen()
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
private fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPrefs(context) }

    var status by remember { mutableStateOf(Status()) }
    var busy by remember { mutableStateOf(false) }
    var onBackScreen by remember { mutableStateOf(prefs.showOnBackScreen) }
    val logs = Logs.lines

    fun refresh() {
        scope.launch {
            busy = true
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
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // 标题栏高度自己量，滚动内容据此下移（不写死，系统字体放大也不错位）
    var titleBarHeight by remember { mutableStateOf(0) }
    val titleBarHeightDp = with(LocalDensity.current) { titleBarHeight.toDp() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = titleBarHeightDp + 20.dp, bottom = 20.dp),
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
                    Switch(
                        checked = onBackScreen,
                        onCheckedChange = {
                            onBackScreen = it
                            prefs.showOnBackScreen = it
                        },
                    )
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
                                refresh()
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
                                refresh()
                            }
                        },
                    ) { Text("停止触控板") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(enabled = !busy, onClick = { refresh() }) { Text("刷新状态") }
                }
            }

            FeelSettingsCard(prefs)

            SectionCard(title = "诊断日志") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { Logs.clear() }) { Text("清空日志") }
                }
                HorizontalDivider()
                Text(
                    text = if (logs.isEmpty()) "（暂无日志）" else logs.joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }

            SectionCard(title = "关于") {
                Text(text = "作者：muzhou07", style = MaterialTheme.typography.bodyMedium)

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

        // 写在滚动 Column 之后 → 永远叠在内容之上，始终置顶
        TopTitleBar(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onGloballyPositioned { titleBarHeight = it.size.height },
        )
    }
}

/** 顶部白色蒙版标题栏：始终置顶，内部用状态栏内边距给标题让位。 */
@Composable
private fun TopTitleBar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = TITLE_BAR_ALPHA)),
    ) {
        Text(
            text = "背屏鼠标",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp),
        )
    }
}

/** 卡片配色：Material3 默认底色 + [PANEL_ALPHA]。 */
@Composable
private fun cardColors90(): CardColors =
    CardDefaults.cardColors(
        containerColor = CardDefaults.cardColors().containerColor.copy(alpha = PANEL_ALPHA),
    )

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

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        // 半透明卡片：背后的壁纸会淡淡透出来
        colors = cardColors90(),
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
