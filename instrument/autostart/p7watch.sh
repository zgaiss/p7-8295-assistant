#!/bin/sh
# 看门狗：监督 p7assist 主进程。主进程不在时先清屏关层（防画面残留），再尝试拉起。
#
# 存活判断不能只看一个名字：二进制被改名（例如应急时改成 p7assist.old）后
# `pidof p7assist` 会落空，看门狗于是每 3 秒清一次屏 —— 表现是「进程在画、
# fb1 里内容完整、但屏幕全空，reinit 后闪一下又消失」。这类误杀排查成本极高
# （2026-11-15 就踩了一次），所以这里按多个可能的名字判，且只在确认没有
# 主进程时才动手。
D=/data/p7assist
# Migration fence for upgraded legacy participants only, not an ownership lock.
# Any namespace shape (including dangling symlink) or unreadable directory denies.
legacy_guard() {
    if ! [ -d "$D" ] || ! ls -a "$D" >/dev/null 2>&1 ||
       [ -e "$D/update-v1" ] || [ -L "$D/update-v1" ]; then
        echo "update-v1 owns deployment or path uncertain; legacy disabled" >&2
        exit 73
    fi
}
mkdir -p "$D" || exit 73
legacy_guard
# END MIGRATION FENCE
BIN=$D/p7assist
PIDF=$D/watch.pid

mkdir -p $D/log

# 防重复：已有看门狗在跑就直接退出
if [ -f "$PIDF" ]; then
    OP=$(cat "$PIDF" 2>/dev/null)
    if [ -n "$OP" ] && kill -0 "$OP" 2>/dev/null; then
        exit 0
    fi
fi
echo $$ > "$PIDF"
trap 'rm -f "$PIDF"' EXIT

alive() {
    for n in p7assist p7assist.old p7assistctl; do
        pidof "$n" >/dev/null 2>&1 && return 0
    done
    return 1
}

tries=0
while :; do
    legacy_guard
    if ! alive; then
        if [ -x "$BIN" ]; then
            echo "$(date '+%F %T') 主进程不在，cleanup 兜底" >> "$D/log/watch.log"
            legacy_guard
            "$BIN" cleanup >>"$D/log/watch.log" 2>&1
            # cleanup 只清屏不启动，这里补上拉起；连续失败 5 次后停手，避免刷屏
            if [ "$tries" -lt 5 ]; then
                tries=$((tries+1))
                echo "$(date '+%F %T') 尝试拉起（第 $tries 次）" >> "$D/log/watch.log"
                legacy_guard
                setsid "$BIN" daemon >>"$D/log/console.log" 2>&1 </dev/null &
                sleep 3
            fi
        else
            echo "$(date '+%F %T') 二进制不存在，看门狗退出" >> "$D/log/watch.log"
            break
        fi
    else
        tries=0
    fi
    sleep 3
done
