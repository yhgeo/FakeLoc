#!/system/bin/sh
# ============================================================================
#  卸载清理：Magisk 卸载模块时自动执行
# ============================================================================
DATADIR=/data/adb/fakeloc

# 备份配置保留（用户可能还要用），只清日志
rm -f "$DATADIR/service.log" 2>/dev/null
rm -f "$DATADIR/service.log.tmp" 2>/dev/null

echo "FakeLoc: 已卸载。配置备份仍保留在 $DATADIR/config.json，可手动删除。"
