#!/bin/sh
# 一次性安装开机自启（在仪表上执行一次即可）
# 幂等：重复执行不会写重复行；写前备份 rc.local；结束后把根分区挂回只读
set -e
RC=/etc/rc.d/rc.local
LINE='[ -f /data/p7assist/p7boot.sh ] && sh /data/p7assist/p7boot.sh &'

mount -o remount,rw / 2>/dev/null || true
[ -f "$RC" ] || { echo "找不到 $RC，请手动加入：$LINE"; exit 1; }
cp -f "$RC" "$RC.bak.$(date +%s)"

if grep -q 'p7assist/p7boot.sh' "$RC"; then
    echo "自启已存在，跳过"
else
    printf '\n%s\n' "$LINE" >> "$RC"
    echo "已追加自启行"
fi

sync
mount -o remount,ro / 2>/dev/null || true
echo "完成。重启后 /data/p7assist/log/boot.log 会多一行。"
