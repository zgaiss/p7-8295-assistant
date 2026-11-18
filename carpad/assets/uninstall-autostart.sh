#!/bin/sh
# 移除开机自启（回滚用）
set -e
RC=/etc/rc.d/rc.local
mount -o remount,rw / 2>/dev/null || true
if [ -f "$RC" ]; then
    cp -f "$RC" "$RC.bak.$(date +%s)"
    grep -v 'p7assist/p7boot.sh' "$RC" > "$RC.tmp" && mv -f "$RC.tmp" "$RC"
    chmod 755 "$RC"
    echo "已移除自启行"
fi
sync
mount -o remount,ro / 2>/dev/null || true
