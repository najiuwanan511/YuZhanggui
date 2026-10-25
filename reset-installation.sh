#!/usr/bin/env bash

set -Eeuo pipefail

if [ "${1:-}" != "--yes" ]; then
    echo "此脚本会永久删除鱼掌柜（YuZhanggui）的容器、数据卷、配置和项目目录。" >&2
    echo "确认删除请重新执行并传入 --yes。" >&2
    exit 1
fi

if [ "$(id -u)" -ne 0 ]; then
    echo "请使用 root 权限执行。" >&2
    exit 1
fi

for project in yuzhanggui; do
    docker ps -aq --filter "label=com.docker.compose.project=$project" | xargs -r docker rm -f
    docker volume ls -q --filter "label=com.docker.compose.project=$project" | xargs -r docker volume rm
done

docker volume rm \
    yuzhanggui-mysql-data yuzhanggui-app-data yuzhanggui-app-logs \
    2>/dev/null || true

systemctl disable --now yuzhanggui-update.path 2>/dev/null || true
rm -f /etc/systemd/system/yuzhanggui-update.service /etc/systemd/system/yuzhanggui-update.path
rm -rf /etc/yuzhanggui /usr/local/lib/yuzhanggui /root/yuzhanggui /root/YuZhanggui
systemctl daemon-reload

echo "鱼掌柜已彻底清理完成。"
