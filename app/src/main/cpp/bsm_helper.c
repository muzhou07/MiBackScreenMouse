/*
 * bsm_helper —— 背屏鼠标的 root 助手。
 * 独占背屏触摸设备、创建 uinput 虚拟鼠标，并通过本地 socket 与 App 交换触点帧 / 鼠标指令。
 * 协议为文本行，详见 README「通信协议」；控制口是 App 私有目录内的 unix socket（0600 且校验对端 uid），
 * 客户端连上后须先发 `A <token>` 鉴权（token 由 --token-file 传入、读完即删，不经过命令行）。
 */

#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <netinet/in.h>

#define ABS_SCALE 100                 /* 背屏触摸坐标是像素的 100 倍 */
#define MAX_SLOTS 16
#define SEND_BUF 4096
#define SOCK_PATH_MAX 200             /* 文件系统 socket 路径上限 */
#define PROTO_VER 4                   /* v4：控制口改为 App 私有目录内 socket，并强制校验对端 uid */
#define MAX_MOVE 10000                /* 单条指令允许的最大位移（即使已鉴权也不接受极端值） */
#define MAX_WHEEL 100                 /* 单条指令允许的最大滚轮量 */

/* 调试开关（--no-auth / --tcp / --token / --no-grab）默认不编译进正式包：
   构建脚本只在 debug 变体上传 -DBSM_DEBUG_TOOLS。 */
#ifdef BSM_DEBUG_TOOLS
#define DEBUG_TOOLS 1
#else
#define DEBUG_TOOLS 0
#endif

/* 控制口默认只开文件系统 socket（--sock 指定，位于 App 私有目录）；
 * TCP 口仅在显式传 --tcp PORT 时开启，供手动调试。 */

static const char *TAG = "bsm-helper";

static int g_touch_fd = -1;
static int g_uinput_fd = -1;
static bool g_grabbed = false;
static bool g_daemon = false;
static bool g_no_grab = false;
static bool g_debug = false;
static int g_write_log = 0;
static bool g_quit = false;   /* 收到 Q 指令：立刻退出 */
static int g_app_pid = -1;    /* App 主进程 pid（优先取 SO_PEERCRED；TCP 调试口才用 V 自报值） */
static int g_app_uid = -1;    /* App 主进程 uid（防 pid 复用误判） */
static int g_conn_pid = -1;   /* 当前连接对端 pid（SO_PEERCRED；拿不到时为 -1） */
static int g_conn_uid = -1;   /* 当前连接对端 uid */

/* 控制口：文件系统 socket 路径（--sock）与合法客户端 uid（--uid，-1=不限制，仅调试） */
static char g_sock_path[SOCK_PATH_MAX] = {0};
static int g_peer_uid = -1;
static bool g_no_auth = false;   /* --no-auth：显式关闭 token 校验（仅调试用） */

/* 客户端鉴权串（--token / --token-file 指定）：防止同机其它 App 抢占控制口 */
static char g_token[65] = {0};
static bool g_authed = false;
static time_t g_auth_deadline = 0;

/* 触摸设备像素范围（握手时告知 App，用于坐标映射；0=未知） */
static int g_src_w = 0;
static int g_src_h = 0;

static int g_unix_listen = -1;
static int g_tcp_listen = -1;
static int g_client = -1;

static char g_send_buf[SEND_BUF];
static size_t g_send_len = 0;

static void logf_(const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    fprintf(stderr, "%s: %s\n", TAG, buf);
}

static void send_str(const char *s) {
    if (g_client < 0 || !g_authed) return;
    size_t n = strlen(s);
    if (g_send_len + n + 1 >= sizeof g_send_buf) {
        (void)!write(g_client, g_send_buf, g_send_len);
        g_send_len = 0;
    }
    memcpy(g_send_buf + g_send_len, s, n);
    g_send_len += n;
    g_send_buf[g_send_len++] = '\n';
}

static void send_flush(void) {
    if (g_client < 0 || !g_authed || g_send_len == 0) return;
    ssize_t off = 0;
    while (off < (ssize_t)g_send_len) {
        ssize_t w = write(g_client, g_send_buf + off, g_send_len - off);
        if (w <= 0) {
            if (g_debug) logf_("发送失败: %s (已发 %zd/%zu)", strerror(errno), off, g_send_len);
            break;
        }
        off += w;
    }
    if (g_debug && g_write_log < 6) {
        g_write_log++;
        logf_("写入 %zd 字节 (fd=%d, 第%d次)", off, g_client, g_write_log);
    }
    g_send_len = 0;
}

static void sendf(const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    send_str(buf);
}

/* App 主进程是否还活着（pid<=0 表示未知，按活着处理） */
static bool app_alive(void) {
    if (g_app_pid <= 0) return true;
    char path[64];
    snprintf(path, sizeof path, "/proc/%d", g_app_pid);
    struct stat st;
    if (stat(path, &st) != 0) return false;
    if (g_app_uid >= 0 && (int)st.st_uid != g_app_uid) return false;  /* pid 被复用 */
    return true;
}

/* ------------------------------------------------------------------ 输入设备 */

static int abs_max_of(int fd, int code) {
    struct input_absinfo info;
    memset(&info, 0, sizeof info);
    if (ioctl(fd, EVIOCGABS(code), &info) < 0) return -1;
    return info.maximum;
}

static bool has_mt(int fd) {
    unsigned long bits[(ABS_CNT + 8 * sizeof(long) - 1) / (8 * sizeof(long))];
    memset(bits, 0, sizeof bits);
    if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof bits), bits) < 0) return false;
    return (bits[ABS_MT_POSITION_X / (8 * sizeof(long))] >> (ABS_MT_POSITION_X % (8 * sizeof(long)))) & 1UL;
}

static void dev_name_of(int fd, char *out, size_t cap) {
    out[0] = '\0';
    if (ioctl(fd, EVIOCGNAME(cap - 1), out) < 0) out[0] = '\0';
}

/* 判断是否为背屏触摸：ABS_MT 范围 ×100 后等于 904x572 */
static bool probe_backscreen(const char *path, char *name_out, size_t cap, int *xmax_out, int *ymax_out) {
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) return false;
    bool ok = false;
    if (has_mt(fd)) {
        int xmax = abs_max_of(fd, ABS_MT_POSITION_X);
        int ymax = abs_max_of(fd, ABS_MT_POSITION_Y);
        if (xmax_out) *xmax_out = xmax;
        if (ymax_out) *ymax_out = ymax;
        if (xmax > 0 && ymax > 0 && (xmax + 1) / ABS_SCALE == 904 && (ymax + 1) / ABS_SCALE == 572) {
            dev_name_of(fd, name_out, cap);
            ok = true;
        }
    }
    close(fd);
    return ok;
}

static void do_list(void) {
    DIR *d = opendir("/dev/input");
    if (!d) {
        printf("无法打开 /dev/input: %s\n", strerror(errno));
        return;
    }
    struct dirent *e;
    while ((e = readdir(d))) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        char path[128];
        snprintf(path, sizeof path, "/dev/input/%s", e->d_name);
        int fd = open(path, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
        if (fd < 0) continue;
        char name[128];
        dev_name_of(fd, name, sizeof name);
        if (has_mt(fd)) {
            int xmax = abs_max_of(fd, ABS_MT_POSITION_X);
            int ymax = abs_max_of(fd, ABS_MT_POSITION_Y);
            printf("%-20s name=\"%s\" MT x=%d y=%d -> %dx%d px%s\n", path, name, xmax, ymax,
                   (xmax + 1) / ABS_SCALE, (ymax + 1) / ABS_SCALE,
                   ((xmax + 1) / ABS_SCALE == 904 && (ymax + 1) / ABS_SCALE == 572) ? "  <== 背屏触摸" : "");
        } else {
            printf("%-20s name=\"%s\" (no MT)\n", path, name);
        }
        close(fd);
    }
    closedir(d);
}


/* ------------------------------------------------------------------ uinput 鼠标 */

static int create_mouse(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        logf_("打开 /dev/uinput 失败: %s", strerror(errno));
        return -1;
    }
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_KEYBIT, BTN_LEFT);
    ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT);
    ioctl(fd, UI_SET_KEYBIT, BTN_MIDDLE);
    ioctl(fd, UI_SET_EVBIT, EV_REL);
    ioctl(fd, UI_SET_RELBIT, REL_X);
    ioctl(fd, UI_SET_RELBIT, REL_Y);
    ioctl(fd, UI_SET_RELBIT, REL_WHEEL);
    ioctl(fd, UI_SET_RELBIT, REL_HWHEEL);
    /* 让系统把它识别成指针设备(鼠标) */
    ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_POINTER);

    struct uinput_setup setup;
    memset(&setup, 0, sizeof setup);
    snprintf(setup.name, UINPUT_MAX_NAME_SIZE, "BackScreen Mouse");
    setup.id.bustype = BUS_USB;
    setup.id.vendor = 0x4d5a;   /* "MZ" */
    setup.id.product = 0x0001;
    setup.id.version = 1;
    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) {
        logf_("创建 uinput 设备失败: %s", strerror(errno));
        close(fd);
        return -1;
    }
    /* 等内核把设备注册进 InputReader */
    struct timespec ts;
    ts.tv_sec = 0;
    ts.tv_nsec = 250L * 1000 * 1000;
    nanosleep(&ts, NULL);
    return fd;
}

static void emit_rel(int code, int value) {
    if (g_uinput_fd < 0) return;
    struct input_event ev;
    memset(&ev, 0, sizeof ev);
    ev.type = EV_REL;
    ev.code = code;
    ev.value = value;
    (void)!write(g_uinput_fd, &ev, sizeof ev);
}

static void emit_syn(void) {
    if (g_uinput_fd < 0) return;
    struct input_event ev;
    memset(&ev, 0, sizeof ev);
    ev.type = EV_SYN;
    ev.code = SYN_REPORT;
    (void)!write(g_uinput_fd, &ev, sizeof ev);
}

static void mouse_move(int dx, int dy) {
    while (dx != 0) {
        int step = dx > 200 ? 200 : (dx < -200 ? -200 : dx);
        emit_rel(REL_X, step);
        dx -= step;
    }
    while (dy != 0) {
        int step = dy > 200 ? 200 : (dy < -200 ? -200 : dy);
        emit_rel(REL_Y, step);
        dy -= step;
    }
    emit_syn();
}

static void mouse_button(int button, int down) {
    if (g_uinput_fd < 0) return;
    int code = button == 1 ? BTN_LEFT : (button == 2 ? BTN_RIGHT : BTN_MIDDLE);
    struct input_event ev;
    memset(&ev, 0, sizeof ev);
    ev.type = EV_KEY;
    ev.code = code;
    ev.value = down ? 1 : 0;
    (void)!write(g_uinput_fd, &ev, sizeof ev);
    emit_syn();
}

static void mouse_wheel(int code, int delta) {
    while (delta != 0) {
        int step = delta > 1 ? 1 : (delta < -1 ? -1 : delta);
        emit_rel(code, step);
        delta -= step;
    }
    emit_syn();
}

/* ------------------------------------------------------------------ 触摸解析 */

struct slot_state {
    int tracking_id;
    int x;
    int y;
};

static struct slot_state g_slots[MAX_SLOTS];
static int g_slot = 0;
static unsigned long g_frame_seq = 0;

static void reset_slots(void) {
    for (int i = 0; i < MAX_SLOTS; i++) {
        g_slots[i].tracking_id = -1;
        g_slots[i].x = 0;
        g_slots[i].y = 0;
    }
}

static void emit_frame(void) {
    int ids[MAX_SLOTS], xs[MAX_SLOTS], ys[MAX_SLOTS];
    int n = 0;
    for (int i = 0; i < MAX_SLOTS; i++) {
        if (g_slots[i].tracking_id >= 0) {
            ids[n] = g_slots[i].tracking_id;
            xs[n] = g_slots[i].x / ABS_SCALE;
            ys[n] = g_slots[i].y / ABS_SCALE;
            n++;
        }
    }
    char buf[1024];
    int off = snprintf(buf, sizeof buf, "T %lu %d", ++g_frame_seq, n);
    for (int i = 0; i < n && off < (int)sizeof buf - 24; i++) {
        off += snprintf(buf + off, sizeof buf - off, " %d %d %d", ids[i], xs[i], ys[i]);
    }
    if (g_debug && (g_frame_seq % 40 == 1)) logf_("发帧 #%lu 指针=%d client=%d", g_frame_seq, n, g_client);
    send_str(buf);
}

static void handle_touch_events(const struct input_event *evs, int count) {
    for (int i = 0; i < count; i++) {
        const struct input_event *e = &evs[i];
        if (e->type == EV_ABS) {
            switch (e->code) {
                case ABS_MT_SLOT:
                    if (e->value >= 0 && e->value < MAX_SLOTS) g_slot = e->value;
                    break;
                case ABS_MT_TRACKING_ID:
                    g_slots[g_slot].tracking_id = e->value;
                    break;
                case ABS_MT_POSITION_X:
                    g_slots[g_slot].x = e->value;
                    break;
                case ABS_MT_POSITION_Y:
                    g_slots[g_slot].y = e->value;
                    break;
                default:
                    break;
            }
        } else if (e->type == EV_SYN && e->code == SYN_REPORT) {
            emit_frame();
            send_flush();
        }
    }
}

/* ------------------------------------------------------------------ socket / 协议 */

/*
 * 在 App 私有目录里建文件系统 socket：
 *   - 路径由 --sock 指定（<filesDir>/bsm.sock，目录本身 0700 属主为 App）
 *   - socket 建好后 chown 给 App、chmod 0600 —— 别的 App 既连不上、也无法抢先 bind
 *   - 抽象命名空间已被弃用：它没有文件系统权限，任何 App 都能抢占并冒充助手骗走 token
 */
static int setup_unix_socket(const char *path) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    if (strlen(path) >= sizeof(addr.sun_path)) {
        logf_("socket 路径过长: %s", path);
        close(fd);
        return -1;
    }
    snprintf(addr.sun_path, sizeof addr.sun_path, "%s", path);
    unlink(path);   /* 清掉上次残留 */
    if (bind(fd, (struct sockaddr *)&addr, sizeof addr) < 0 || listen(fd, 1) < 0) {
        logf_("socket 监听失败(%s): %s", path, strerror(errno));
        close(fd);
        return -1;
    }
    if (g_peer_uid >= 0 && chown(path, (uid_t)g_peer_uid, (gid_t)g_peer_uid) < 0) {
        logf_("socket chown 失败: %s", strerror(errno));
    }
    if (chmod(path, 0600) < 0) logf_("socket chmod 失败: %s", strerror(errno));
    return fd;
}

static int setup_tcp(uint16_t port) {
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) return -1;
    int on = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &on, sizeof on);
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof addr);
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = htons(port);
    if (bind(fd, (struct sockaddr *)&addr, sizeof addr) < 0 || listen(fd, 1) < 0) {
        logf_("TCP 127.0.0.1:%u 监听失败: %s", port, strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

static void grab_touch(bool on) {
    if (g_no_grab || g_touch_fd < 0) return;
    if (!on && !g_grabbed) return; // 本来就没抓，不用再放（否则内核返回 EINVAL）
    if (ioctl(g_touch_fd, EVIOCGRAB, on ? 1 : 0) < 0) {
        logf_("EVIOCGRAB %d 失败: %s", on ? 1 : 0, strerror(errno));
        return;
    }
    g_grabbed = on;
    logf_("背屏触摸设备已%s独占", on ? "获取" : "释放");
}

static void client_disconnected(void) {
    if (g_client >= 0) close(g_client);
    g_client = -1;
    g_send_len = 0;
    g_authed = false;
    g_conn_pid = -1;
    g_conn_uid = -1;
    grab_touch(false);
    reset_slots();
    logf_("App 断开，已释放触摸设备，等待新连接");
}

/* 鉴权通过后的握手：独占触摸设备 + 上报就绪与几何信息 */
static void handshake(void) {
    reset_slots();
    grab_touch(true);
    send_str("K");
    /* H <proto> <srcW> <srcH>：协议版本 + 触摸设备像素范围 */
    sendf("H %d %d %d", PROTO_VER, g_src_w, g_src_h);
    send_str(g_grabbed ? "S 助手就绪（已 EVIOCGRAB 独占触摸）" : "S 助手就绪（未独占触摸：调试模式）");
    send_flush();
}

static void accept_client(int fd) {
    int c = accept(fd, NULL, NULL);
    if (c < 0) return;

    /* 先验身份：uid 不符的连接直接拒绝，且绝不顶掉合法连接 */
    struct ucred cred;
    socklen_t clen = sizeof cred;
    int peer_uid = -1;
    int peer_pid = -1;
    if (getsockopt(c, SOL_SOCKET, SO_PEERCRED, &cred, &clen) == 0) {
        peer_uid = (int)cred.uid;
        peer_pid = (int)cred.pid;
    }
    if (g_peer_uid >= 0 && peer_uid >= 0 && peer_uid != g_peer_uid) {
        logf_("拒绝连接：对端 uid=%d，期望 uid=%d", peer_uid, g_peer_uid);
        close(c);
        return;
    }
    /* 以 SO_PEERCRED 为准绑定生命周期：不采信客户端用 V 自报的 pid/uid */
    g_conn_pid = peer_pid;
    g_conn_uid = peer_uid;
    if (peer_pid > 0 && (g_peer_uid < 0 || peer_uid == g_peer_uid)) {
        g_app_pid = peer_pid;
        g_app_uid = peer_uid;
    }

    if (g_client >= 0) {
        /* 新连接顶替旧连接：App 重连时旧 fd 可能还没被内核回收 */
        logf_("新连接顶替旧连接 (旧 fd=%d)", g_client);
        close(g_client);
        g_client = -1;
        g_send_len = 0;
    }
    g_client = c;
    logf_("新连接(fd=%d pid=%d uid=%d)", c, peer_pid, peer_uid);
    g_authed = false;
    g_send_len = 0;
    /* 设了 token 就先等鉴权，通过后才独占触摸并握手 */
    if (g_token[0] != '\0') {
        g_auth_deadline = time(NULL) + 3;
        logf_("等待客户端鉴权…");
        return;
    }
    g_authed = true;
    handshake();
}

/* 幅值钳制：即使客户端已鉴权，也不让它用极端值驱动大循环 */
static int clamp_i(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

/* 恒时比较 token：长度不等也算失败，其余逐字节异或累加（避免 timing 侧信道） */
static bool token_eq(const char *a, const char *b) {
    size_t la = strlen(a), lb = strlen(b);
    size_t diff = la ^ lb;
    size_t n = la < lb ? la : lb;
    for (size_t i = 0; i < n; i++) {
        diff |= (size_t)(unsigned char)(a[i] ^ b[i]);
    }
    return diff == 0;
}

static void handle_client_line(char *line) {
    /* 未鉴权的连接只接受 A <token>，其它一律断开 */
    if (!g_authed) {
        char *p = line + 1;
        while (*p == ' ') p++;
        if (line[0] == 'A' && g_token[0] != '\0' && token_eq(p, g_token)) {
            g_authed = true;
            logf_("客户端鉴权通过");
            handshake();
        } else {
            logf_("鉴权失败，断开连接");
            client_disconnected();
        }
        return;
    }
    switch (line[0]) {
        case 'M': {
            int dx = 0, dy = 0;
            if (sscanf(line + 1, "%d %d", &dx, &dy) == 2) {
                mouse_move(clamp_i(dx, -MAX_MOVE, MAX_MOVE), clamp_i(dy, -MAX_MOVE, MAX_MOVE));
            }
            break;
        }
        case 'B': {
            int n = 0, d = 0;
            if (sscanf(line + 1, "%d %d", &n, &d) == 2) mouse_button(n, d ? 1 : 0);
            break;
        }
        case 'W': {
            int d = 0;
            if (sscanf(line + 1, "%d", &d) == 1) mouse_wheel(REL_WHEEL, clamp_i(d, -MAX_WHEEL, MAX_WHEEL));
            break;
        }
        case 'H': {
            int d = 0;
            if (sscanf(line + 1, "%d", &d) == 1) mouse_wheel(REL_HWHEEL, clamp_i(d, -MAX_WHEEL, MAX_WHEEL));
            break;
        }
        case 'P':
            /* 心跳：把负载原样回显成 R 行 */
            sendf("R%s", line + 1);
            send_flush();
            break;
        case 'V': {
            /* V <proto> <pid> <uid>：App 自报主进程；已从 SO_PEERCRED 拿到真实凭据时不采信自报值 */
            int ver = 0, pid = -1, uid = -1;
            int got = sscanf(line + 1, "%d %d %d", &ver, &pid, &uid);
            if (g_conn_pid > 0) {
                if (g_debug) {
                    logf_("App 握手: proto=%d（pid/uid 以 SO_PEERCRED 为准 %d/%d）",
                          ver, g_conn_pid, g_conn_uid);
                }
            } else {
                if (got >= 2) g_app_pid = pid;
                if (got >= 3) g_app_uid = uid;
                if (g_debug) logf_("App 握手: proto=%d pid=%d uid=%d（TCP 调试口，采用自报值）", ver, pid, uid);
            }
            break;
        }
        case 'Q':
            logf_("收到结束指令，释放设备并退出");
            client_disconnected();
            g_quit = true;
            break;
        default:
            break;
    }
}

static void cleanup(void) {
    if (g_grabbed) grab_touch(false);
    if (g_client >= 0) close(g_client);
    if (g_unix_listen >= 0) close(g_unix_listen);
    if (g_tcp_listen >= 0) close(g_tcp_listen);
    if (g_touch_fd >= 0) close(g_touch_fd);
    if (g_uinput_fd >= 0) {
        ioctl(g_uinput_fd, UI_DEV_DESTROY);
        close(g_uinput_fd);
    }
    if (g_sock_path[0] != '\0') unlink(g_sock_path);   /* 收工顺手删掉控制口文件 */
    g_client = g_unix_listen = g_tcp_listen = g_touch_fd = g_uinput_fd = -1;
}

static void on_signal(int sig) {
    (void)sig;
    cleanup();
    _exit(0);
}

/* ------------------------------------------------------------------ selftest / daemon */

static int selftest(void) {
    g_uinput_fd = create_mouse();
    if (g_uinput_fd < 0) return 1;
    logf_("uinput 鼠标已创建，自检：右移 300px、下移 200px、左键单击");
    for (int i = 0; i < 30; i++) {
        mouse_move(10, 0);
        usleep(16 * 1000);
    }
    for (int i = 0; i < 20; i++) {
        mouse_move(0, 10);
        usleep(16 * 1000);
    }
    mouse_button(1, 1);
    usleep(60 * 1000);
    mouse_button(1, 0);
    logf_("自检完成");
    ioctl(g_uinput_fd, UI_DEV_DESTROY);
    close(g_uinput_fd);
    g_uinput_fd = -1;
    return 0;
}

static int run_daemon(const char *device, uint16_t port, bool no_grab) {
    char path[128] = {0};
    char name[128] = {0};
    int xmax = -1, ymax = -1;

    if (device && *device) {
        strncpy(path, device, sizeof path - 1);
        /* 显式指定设备时也尽量探测名称/坐标范围，失败记为未知 */
        if (!probe_backscreen(path, name, sizeof name, &xmax, &ymax)) {
            xmax = -1;
            ymax = -1;
        }
    } else {
        DIR *d = opendir("/dev/input");
        if (!d) {
            logf_("无法打开 /dev/input");
            return 2;
        }
        struct dirent *e;
        while ((e = readdir(d))) {
            if (strncmp(e->d_name, "event", 5) != 0) continue;
            char candidate[128];
            snprintf(candidate, sizeof candidate, "/dev/input/%s", e->d_name);
            if (probe_backscreen(candidate, name, sizeof name, &xmax, &ymax)) {
                strncpy(path, candidate, sizeof path - 1);
                break;
            }
        }
        closedir(d);
        if (!path[0]) {
            logf_("没找到背屏触摸设备(ABS_MT 904x572)，用 --list 查看");
            return 3;
        }
    }

    g_src_w = xmax > 0 ? (xmax + 1) / ABS_SCALE : 0;
    g_src_h = ymax > 0 ? (ymax + 1) / ABS_SCALE : 0;

    g_touch_fd = open(path, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    if (g_touch_fd < 0) {
        logf_("打开触摸设备 %s 失败: %s", path, strerror(errno));
        return 4;
    }
    logf_("触摸设备: %s (\"%s\" MT=%dx%d px)", path, name, (xmax + 1) / ABS_SCALE, (ymax + 1) / ABS_SCALE);

    g_uinput_fd = create_mouse();
    if (g_uinput_fd < 0) return 5;

    if (g_sock_path[0] == '\0') {
        logf_("缺少 --sock（控制口路径），拒绝启动");
        cleanup();
        return 6;
    }
    g_unix_listen = setup_unix_socket(g_sock_path);
    g_tcp_listen = (port != 0) ? setup_tcp(port) : -1;   /* TCP 仅手动调试时开启 */
    if (g_unix_listen < 0) {
        logf_("控制口 socket 监听失败");
        cleanup();
        return 6;
    }
    logf_("就绪: sock=%s%s%s%s", g_sock_path,
          g_tcp_listen >= 0 ? " (TCP 调试口已开)" : "",
          no_grab ? " (不独占触摸)" : "",
          g_token[0] != '\0' ? " (需 token 鉴权)" : "");

    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGPIPE, SIG_IGN);

    struct input_event evs[64];
    struct pollfd pfd[4];
    time_t last_client = time(NULL);

    while (true) {
        int n = 0;
        if (g_touch_fd >= 0 && g_authed) {
            pfd[n].fd = g_touch_fd;
            pfd[n].events = POLLIN;
            pfd[n].revents = 0;
            n++;
        }
        if (g_unix_listen >= 0) {
            pfd[n].fd = g_unix_listen;
            pfd[n].events = POLLIN;
            pfd[n].revents = 0;
            n++;
        }
        if (g_tcp_listen >= 0) {
            pfd[n].fd = g_tcp_listen;
            pfd[n].events = POLLIN;
            pfd[n].revents = 0;
            n++;
        }
        if (g_client >= 0) {
            pfd[n].fd = g_client;
            pfd[n].events = POLLIN;
            pfd[n].revents = 0;
            n++;
        }

        int rc = poll(pfd, n, 500);
        if (rc < 0 && errno != EINTR) {
            logf_("poll 失败: %s", strerror(errno));
            break;
        }

        for (int i = 0; i < n; i++) {
            if (!(pfd[i].revents & (POLLIN | POLLHUP | POLLERR))) continue;
            if (pfd[i].fd == g_touch_fd) {
                ssize_t got = read(g_touch_fd, evs, sizeof evs);
                if (got > 0) {
                    handle_touch_events(evs, (int)(got / (ssize_t)sizeof(struct input_event)));
                } else if (got < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
                    logf_("触摸读取错误: %s", strerror(errno));
                }
            } else if (pfd[i].fd == g_unix_listen || pfd[i].fd == g_tcp_listen) {
                accept_client(pfd[i].fd);
                if (g_client >= 0) last_client = time(NULL);
            } else if (pfd[i].fd == g_client) {
                char buf[2048];
                ssize_t got = read(g_client, buf, sizeof buf - 1);
                if (got <= 0) {
                    client_disconnected();
                } else {
                    buf[got] = '\0';
                    char *p = buf;
                    while (p && *p) {
                        char *nl = strchr(p, '\n');
                        if (nl) *nl = '\0';
                        if (*p) handle_client_line(p);
                        p = nl ? nl + 1 : NULL;
                    }
                    last_client = time(NULL);
                }
            }
        }

        /* App 主动结束会话：立刻退出 */
        if (g_quit) {
            logf_("会话结束，退出");
            break;
        }

        /* 鉴权超时：连上了却迟迟不给 token，直接断开 */
        if (g_client >= 0 && !g_authed && time(NULL) > g_auth_deadline) {
            logf_("客户端鉴权超时，断开");
            client_disconnected();
        }

        /* App 主进程已结束：立刻退出，不留残留子进程 */
        if (!app_alive()) {
            logf_("App 主进程已结束，助手退出");
            break;
        }

        /* 安全兜底：App 异常消失且没人连回来就退出 */
        if (g_client < 0 && time(NULL) - last_client > 10) {
            logf_("10 秒无连接，自动退出");
            break;
        }
    }

    cleanup();
    return 0;
}

/* ------------------------------------------------------------------ main */

/* 从一次性文件读 token（App 写入、权限 0600，读完立即删除）：避免 token 出现在命令行里 */
static bool read_token_file(const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) {
        logf_("读取 token 文件失败(%s): %s", path, strerror(errno));
        return false;
    }
    char buf[128] = {0};
    size_t n = fread(buf, 1, sizeof buf - 1, f);
    fclose(f);
    unlink(path);
    if (n == 0) return false;
    char *s = buf;
    while (*s == ' ' || *s == '\t' || *s == '\r' || *s == '\n') s++;
    char *e = s + strlen(s);
    while (e > s && (e[-1] == ' ' || e[-1] == '\t' || e[-1] == '\r' || e[-1] == '\n')) *--e = '\0';
    if (*s == '\0') return false;
    snprintf(g_token, sizeof g_token, "%s", s);
    return true;
}

/* 正式版（未定义 BSM_DEBUG_TOOLS）里，这些调试开关一律拒绝 */
static int reject_debug_option(const char *opt) {
    fprintf(stderr, "该选项在当前构建中不可用: %s\n", opt);
    logf_("拒绝调试选项: %s", opt);
    return 1;
}

int main(int argc, char **argv) {
    const char *device = NULL;
    const char *token_file = NULL;
    uint16_t port = 0;              /* 0 = 不开 TCP：默认只开 App 私有目录内的文件系统 socket */
    bool no_grab = false;
    bool daemon = false;
    bool debug = false;
    bool no_auth = false;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--list") == 0) {
            do_list();
            return 0;
        } else if (strcmp(argv[i], "--selftest") == 0) {
            return selftest();
        } else if (strcmp(argv[i], "--daemon") == 0) {
            daemon = true;
        } else if (strcmp(argv[i], "--no-grab") == 0) {
            if (!DEBUG_TOOLS) return reject_debug_option(argv[i]);
            no_grab = true;
        } else if (strcmp(argv[i], "--debug") == 0) {
            debug = true;                   /* 只影响日志详细度，不算调试后门 */
        } else if (strcmp(argv[i], "--no-auth") == 0) {
            if (!DEBUG_TOOLS) return reject_debug_option(argv[i]);
            no_auth = true;                 /* 仅调试：显式关闭鉴权 */
        } else if (strcmp(argv[i], "--device") == 0 && i + 1 < argc) {
            device = argv[++i];
        } else if (strcmp(argv[i], "--sock") == 0 && i + 1 < argc) {
            snprintf(g_sock_path, sizeof g_sock_path, "%s", argv[++i]);
        } else if (strcmp(argv[i], "--uid") == 0 && i + 1 < argc) {
            g_peer_uid = atoi(argv[++i]);
        } else if (strcmp(argv[i], "--token-file") == 0 && i + 1 < argc) {
            token_file = argv[++i];
        } else if (strcmp(argv[i], "--tcp") == 0 && i + 1 < argc) {
            if (!DEBUG_TOOLS) return reject_debug_option(argv[i]);
            port = (uint16_t)atoi(argv[++i]);   /* 仅手动调试 */
        } else if (strcmp(argv[i], "--token") == 0 && i + 1 < argc) {
            if (!DEBUG_TOOLS) return reject_debug_option(argv[i]);
            snprintf(g_token, sizeof g_token, "%s", argv[++i]);   /* 仅手动调试 */
        } else {
            fprintf(stderr,
                    "用法: %s --daemon --sock PATH --uid N [--token-file PATH]\n"
                    "      %s [--list] [--selftest]\n"
                    "调试（仅 debug 构建可用）: [--device PATH] [--tcp PORT] [--token HEX] [--no-auth] [--no-grab]\n"
                    "其它: [--debug]\n",
                    argv[0], argv[0]);
            return 1;
        }
    }

    if (!daemon) {
        fprintf(stderr, "需要 --daemon / --list / --selftest 之一\n");
        return 1;
    }

    /* token 优先从一次性文件读（App 的正式路径）；命令行 --token 只在手动调试时用 */
    if (token_file != NULL && !read_token_file(token_file)) {
        fprintf(stderr, "读取 token 文件失败: %s\n", token_file);
        return 1;
    }
    if (g_token[0] == '\0' && !no_auth) {
        fprintf(stderr, "拒绝在无 token 的情况下运行（调试请显式加 --no-auth）\n");
        logf_("拒绝启动：无 token 且未加 --no-auth");
        return 1;
    }
    if (no_auth) logf_("警告：--no-auth 已关闭鉴权，仅用于调试");
    if (g_peer_uid < 0) {
        if (!DEBUG_TOOLS) {
            fprintf(stderr, "正式版必须指定 --uid（客户端 uid 白名单）\n");
            return 1;
        }
        logf_("警告：未指定 --uid，不限制客户端 uid（仅调试）");
    }

    g_daemon = true;
    g_no_grab = no_grab;
    g_debug = debug;
    g_no_auth = no_auth;
    return run_daemon(device, port, no_grab);
}
