#!/usr/bin/env bash

set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

random_hex() {
    local bytes="$1"
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -hex "$bytes"
        return
    fi
    od -An -N "$bytes" -tx1 /dev/urandom | tr -d ' \n'
}

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "缺少命令: $1" >&2
        exit 1
    fi
}

require_command docker

if ! docker compose version >/dev/null 2>&1; then
    echo "需要 Docker Compose v2。" >&2
    exit 1
fi

if [ ! -f .env ]; then
    cp .env.example .env
    DB_PASSWORD="$(random_hex 24)"
    DB_ROOT_PASSWORD="$(random_hex 24)"
    JWT_SECRET="$(random_hex 48)"

    # 首次安装生成独立密钥，避免示例凭据进入运行环境。
    sed -i "s/change-me-database-password/$DB_PASSWORD/" .env
    sed -i "s/change-me-root-password/$DB_ROOT_PASSWORD/" .env
    sed -i "s/change-me-to-at-least-32-random-bytes/$JWT_SECRET/" .env
    chmod 600 .env
fi

UPDATE_HOST_DIR="$(sed -n 's/^UPDATE_HOST_DIR=//p' .env | tail -n 1)"
UPDATE_HOST_DIR="${UPDATE_HOST_DIR:-./runtime/update}"
if [[ "$UPDATE_HOST_DIR" != /* ]]; then UPDATE_HOST_DIR="$ROOT_DIR/$UPDATE_HOST_DIR"; fi
mkdir -p "$UPDATE_HOST_DIR"
chmod 1777 "$UPDATE_HOST_DIR"

export APP_GIT_SHA="$(git rev-parse --verify HEAD 2>/dev/null || echo unknown)"
docker compose up -d --build --remove-orphans

repair_failed_v21() {
    local failed
    failed="$(docker compose exec -T --interactive=false mysql sh -c 'mysql -N -s -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -e "SELECT COUNT(*) FROM flyway_schema_history WHERE version='"'"'21'"'"' AND success=0"' 2>/dev/null || true)"
    failed="${failed//$'\r'/}"
    if [ "$failed" != "1" ]; then
        return 1
    fi
    echo "检测到 V21 黑名单迁移失败，正在自动兼容修复..."
    docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' < deploy/sql/repair-v21-buyer-blacklist.sql
}

echo "正在等待鱼掌柜通过健康检查..."
APP_REPAIR_ATTEMPTED=0
for attempt in $(seq 1 90); do
    APP_CONTAINER_ID="$(docker compose ps -q app)"
    APP_HEALTH="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$APP_CONTAINER_ID" 2>/dev/null || true)"
    if [ "$APP_HEALTH" = "healthy" ]; then
        break
    fi
    if [ "$APP_REPAIR_ATTEMPTED" -eq 0 ] && repair_failed_v21; then
        APP_REPAIR_ATTEMPTED=1
        docker compose up -d --no-build --no-deps --force-recreate app
        continue
    fi
    if [ "$attempt" -eq 90 ]; then
        echo "鱼掌柜未能通过健康检查。" >&2
        docker compose logs --no-color --tail=120 app >&2
        exit 1
    fi
    sleep 2
done

install_online_update_agent() {
    if [ ! -d /run/systemd/system ]; then
        echo "当前宿主机未使用 systemd，已跳过网页在线更新代理。"
        return 0
    fi
    for command in systemctl python3 realpath; do
        if ! command -v "$command" >/dev/null 2>&1; then
            echo "缺少 $command，已跳过网页在线更新代理。" >&2
            return 0
        fi
    done
    if [ "$EUID" -ne 0 ]; then
        if ! command -v sudo >/dev/null 2>&1 || ! sudo -n true >/dev/null 2>&1; then
            echo "网页在线更新代理需要宿主机管理员权限，请安装后执行："
            echo "cd '$ROOT_DIR' && sudo ./deploy/self-update/install-online-update.sh"
            return 0
        fi
    fi

    echo "正在启用网页在线更新..."
    if ! ./deploy/self-update/install-online-update.sh "$ROOT_DIR" --skip-app-recreate; then
        echo "网页在线更新代理安装失败，应用仍可正常使用。请稍后手动执行：" >&2
        echo "cd '$ROOT_DIR' && sudo ./deploy/self-update/install-online-update.sh" >&2
    fi
}

install_online_update_agent
docker compose ps

echo
echo "鱼掌柜已启动: http://localhost:12400"
if [ -f "$UPDATE_HOST_DIR/agent.ready" ]; then
    echo "网页在线更新已启用，可在页面顶部的版本详情中直接更新。"
else
    echo "飞牛OS启用网页在线更新：sudo ./deploy/self-update/install-online-update.sh"
fi
echo "公网部署需先配置 deploy/nginx/certs、ALLOWED_ORIGINS 和 TRUST_PROXY，再执行:"
echo "docker compose --profile proxy up -d"
