#!/bin/sh
# 开机拉起 / 首次部署收尾（仪表 /data/p7assist/p7boot.sh）
# 自包含：FTP 上传的文件通常只有 644，这里自己转正 p7assist.new 并 chmod，
# 所以外部只需要一条 sh /data/p7assist/p7boot.sh，不必再拼又长又易粘错的命令。
D=/data/p7assist
mkdir -p $D/log
echo "$(date '+%F %T') boot uptime=$(cut -d' ' -f1 /proc/uptime)" >> $D/log/boot.log
if [ -s $D/p7assist.new ]; then
    mv -f $D/p7assist.new $D/p7assist
    echo "$(date '+%F %T') p7assist.new 已转正" >> $D/log/boot.log
fi
chmod 755 $D/p7assist $D/p7assistctl $D/p7boot.sh $D/p7watch.sh \
    $D/install-autostart.sh $D/uninstall-autostart.sh 2>/dev/null
# 等 ECluster / carservice 起来（车辆上电后通常在跑，最多等 6 秒）
i=0
while [ $i -lt 12 ]; do
    if pidof ECluster >/dev/null 2>&1; then
        break
    fi
    sleep 0.5
    i=$((i+1))
done
rm -f $D/stop
setsid $D/p7assist daemon >>$D/log/console.log 2>&1 </dev/null &
echo "$(date '+%F %T') started pid=$!" >> $D/log/boot.log
# 看门狗兜底：主进程异常退出（含 SIGKILL）后清屏关层，防止画面残留
[ -f $D/p7watch.sh ] && setsid sh $D/p7watch.sh >>$D/log/watch.log 2>&1 </dev/null &
# 开机自启（幂等）：首次执行时写入 rc.local，之后每次开机自动拉起
[ -f $D/install-autostart.sh ] && sh $D/install-autostart.sh
