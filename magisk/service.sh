#!/system/bin/sh
# ============================================================================
#  service：开机后期执行（late_start service）
#
#  做两件事：
#   1. 修正 FakeLoc 应用 prefs 的可读权限 —— 这是配置通道的**兜底**路径。
#      主通道是 ContentProvider（走 binder，不受 SELinux 跨应用读限制影响）；
#      XSharedPreferences 通道需要 prefs 文件可读，在部分 ROM / SELinux 策略下
#      即使改了权限也会被拒绝，所以这里只是 best-effort。
#   2. 把配置备份做一次校验，确保目录结构完好。
# ============================================================================
MODDIR=${0%/*}
DATADIR=/data/adb/fakeloc
PKG=com.mo.fakeloc

LOG="$DATADIR/service.log"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $1" >> "$LOG"; }

# 等系统起来
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 3
done
# 再等一会儿，确保应用数据目录已就绪
sleep 20

mkdir -p "$DATADIR"
chmod 700 "$DATADIR"

# ---- 兜底：让 prefs 对其它 uid 可读（SELinux 可能仍然拒绝，属正常） ----
if [ -d "/data/data/$PKG" ]; then
    chmod 711 "/data/data/$PKG" 2>/dev/null
    chmod 755 "/data/data/$PKG/shared_prefs" 2>/dev/null
    chmod 644 "/data/data/$PKG/shared_prefs/fakeloc.xml" 2>/dev/null
    log "prefs perms patched"
else
    log "app data dir not found (应用可能尚未安装/启动过)"
fi

# ---- 备份文件权限自愈 ----
if [ -f "$DATADIR/config.json" ]; then
    chmod 600 "$DATADIR/config.json"
    chown root:root "$DATADIR/config.json"
    log "config backup present"
fi

# 日志轮转
tail -n 200 "$LOG" > "$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"

log "service.sh done"
