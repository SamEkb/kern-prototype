#!/usr/bin/env bash
# Диагностика раннера: память, ядра, лимиты контрольной группы задачи. Только чтение; не падает.

echo "--- ресурсы раннера ($(date +%T))"
free -m 2>/dev/null || grep -E "MemTotal|MemAvailable|SwapTotal" /proc/meminfo
echo "ядер: $(nproc 2>/dev/null)"
echo "пользователь: $(id 2>/dev/null)"
cg="$(sed -n 's/^0:://p' /proc/self/cgroup 2>/dev/null)"
if [ -n "$cg" ] && [ -d "/sys/fs/cgroup$cg" ]; then
    for f in memory.max memory.current memory.peak memory.swap.max pids.max cpu.max; do
        [ -r "/sys/fs/cgroup$cg/$f" ] && echo "cgroup $f: $(cat "/sys/fs/cgroup$cg/$f")"
    done
    [ -r "/sys/fs/cgroup$cg/memory.events" ] && echo "cgroup memory.events: $(tr '\n' ' ' <"/sys/fs/cgroup$cg/memory.events")"
else
    echo "cgroup: $(cat /proc/self/cgroup 2>/dev/null | tr '\n' ' ')"
fi
echo "ulimit -v: $(ulimit -v)"
exit 0
