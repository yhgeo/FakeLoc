#!/system/bin/sh
# ============================================================================
#  post-fs-data：最早阶段执行，只做目录创建（此时 /data 已挂载）
# ============================================================================
MODDIR=${0%/*}
DATADIR=/data/adb/fakeloc

mkdir -p "$DATADIR"
chmod 700 "$DATADIR"
chown root:root "$DATADIR"

# 记录一次启动时间，便于排查模块是否真的在跑
echo "$(date '+%Y-%m-%d %H:%M:%S') post-fs-data ok" >> "$DATADIR/service.log"
# 日志只留最近 200 行，避免无限增长
tail -n 200 "$DATADIR/service.log" > "$DATADIR/service.log.tmp" 2>/dev/null && \
    mv "$DATADIR/service.log.tmp" "$DATADIR/service.log"
