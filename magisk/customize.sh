#!/system/bin/sh
# ============================================================================
#  Magisk 安装脚本（由 Magisk 在刷入时执行）
# ============================================================================
SKIPUNZIP=0

ui_print "- FakeLoc Root Helper"

DATADIR=/data/adb/fakeloc

ui_print "- 创建配置目录 $DATADIR"
mkdir -p "$DATADIR"
chmod 700 "$DATADIR"
chown root:root "$DATADIR"

ui_print "- 提示：定位 hook 由 LSPosed 模块负责"
ui_print "  1) 在 LSPosed 里启用 FakeLoc 模块"
ui_print "  2) 作用域至少勾选 系统框架(android) 与 目标应用"
ui_print "  3) 重启目标应用使其重新挂载 hook"

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
