#!/usr/bin/env bash

set -Eeuo pipefail

if [ "${1:-}" != "--yes" ]; then
    echo "此脚本只用于删除老账号仓库 najiuwanan/xianyu-Plus，并会永久删除其数据库和全部配置。" >&2
    echo "确认删除请重新执行并传入 --yes。" >&2
    exit 1
fi

if [ "$(id -u)" -ne 0 ]; then
    echo "请使用 root 权限执行。" >&2
    exit 1
fi

repository_origin() {
    local directory="$1"
    [ -d "$directory/.git" ] || return 0
    git -C "$directory" remote get-url origin 2>/dev/null || true
}

# 同时检查旧目录与当前项目目录：命中当前仓库即拒绝执行，避免误删正在用的安装。
declare -a install_dirs=(
    /root/yuzhanggui
    /root/YuZhanggui
    /root/xianyu-plus
    /root/xianyu-Plus
    /root/xianyusmart
    /root/XianYuSmart
)

declare -a legacy_dirs=()

for directory in "${install_dirs[@]}"; do
    remote="$(repository_origin "$directory")"
    if printf '%s\n' "$remote" | grep -Eqi 'github\.com[:/]najiuwanan511/YuZhanggui(\.git)?$'; then
        echo "检测到鱼掌柜（YuZhanggui）项目，已拒绝删除: $directory" >&2
        echo "此命令只能在尚未安装新版本的旧项目服务器上执行。" >&2
        exit 1
    fi
    if printf '%s\n' "$remote" | grep -Eqi 'github\.com[:/]najiuwanan/xianyu-Plus(\.git)?$'; then
        legacy_dirs+=("$directory")
    fi
done

if [ "${#legacy_dirs[@]}" -eq 0 ]; then
    echo "未检测到老账号仓库 najiuwanan/xianyu-Plus，已拒绝删除。" >&2
    exit 1
fi

for project in xianyu-plus xianyusmart; do
    docker ps -aq --filter "label=com.docker.compose.project=$project" | xargs -r docker rm -f
    docker volume ls -q --filter "label=com.docker.compose.project=$project" | xargs -r docker volume rm
done

docker volume rm \
    xianyu-plus-mysql-data xianyu-plus-app-data xianyu-plus-app-logs \
    xianyusmart_mysql-data xianyusmart_app-data xianyusmart_app-logs \
    2>/dev/null || true

docker network rm xianyu-plus xianyusmart_xianyusmart 2>/dev/null || true

systemctl disable --now xianyu-plus-update.path 2>/dev/null || true
rm -f /etc/systemd/system/xianyu-plus-update.service /etc/systemd/system/xianyu-plus-update.path
rm -rf /etc/xianyu-plus /usr/local/lib/xianyu-plus
rm -rf "${legacy_dirs[@]}"
systemctl daemon-reload

echo "老账号仓库 najiuwanan/xianyu-Plus 已彻底清理完成。"
