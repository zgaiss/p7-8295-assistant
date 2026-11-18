#!/bin/sh
# 看门狗：常驻监督 p7assist 主进程，一旦退出（含被 SIGKILL / OOM）立即清屏关层，
# 保证任何异常都不会在仪表上留下残留画面（原厂仪表立即透出）。
# 由 p7boot.sh 开机拉起；二进制被删除（卸载）时自动退出。
D=/data/p7assist
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

while :; do
    if ! pidof p7assist >/dev/null 2>&1; then
        if [ -x "$BIN" ]; then
            echo "$(date '+%F %T') p7assist 退出，执行 cleanup 兜底" >> "$D/log/watch.log"
            "$BIN" cleanup >>"$D/log/watch.log" 2>&1
        else
            echo "$(date '+%F %T') 二进制不存在，看门狗退出" >> "$D/log/watch.log"
            break
        fi
    fi
    sleep 3
done
