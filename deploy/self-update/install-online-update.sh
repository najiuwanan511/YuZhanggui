#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(realpath "${1:-$SCRIPT_DIR/../..}")"
SKIP_APP_RECREATE="${2:-}"

if [[ -n "$SKIP_APP_RECREATE" && "$SKIP_APP_RECREATE" != "--skip-app-recreate" ]]; then
    echo "未知参数: $SKIP_APP_RECREATE" >&2
    exit 2
fi

if [[ $EUID -ne 0 ]]; then
    args=("$PROJECT_DIR")
    [[ -n "$SKIP_APP_RECREATE" ]] && args+=("$SKIP_APP_RECREATE")
    exec sudo -- "$0" "${args[@]}"
fi

for command in docker systemctl python3 realpath; do command -v "$command" >/dev/null; done
[[ -f "$PROJECT_DIR/compose.yaml" && -f "$PROJECT_DIR/.env" ]]
docker compose version >/dev/null

read_env() { sed -n "s/^${1}=//p" "$PROJECT_DIR/.env" | tail -n 1; }
ensure_env() {
    local key="$1" value="$2"
    if grep -q "^${key}=" "$PROJECT_DIR/.env"; then sed -i "s|^${key}=.*|${key}=${value}|" "$PROJECT_DIR/.env"
    else printf '\n%s=%s\n' "$key" "$value" >> "$PROJECT_DIR/.env"; fi
}

UPDATE_DIR="$(read_env UPDATE_HOST_DIR)"
if [[ -z "$UPDATE_DIR" ]]; then UPDATE_DIR="$PROJECT_DIR/runtime/update"
elif [[ "$UPDATE_DIR" != /* ]]; then UPDATE_DIR="$PROJECT_DIR/$UPDATE_DIR"; fi
UPDATE_DIR="$(realpath -m "$UPDATE_DIR")"
[[ "$PROJECT_DIR" != *' '* && "$UPDATE_DIR" != *' '* ]]

ensure_env UPDATE_HOST_DIR "$UPDATE_DIR"
ensure_env UPDATE_RELEASE_API "https://api.github.com/repos/najiuwanan511/YuZhanggui/releases/latest"
ensure_env UPDATE_GITHUB_REPOSITORY "najiuwanan511/YuZhanggui"
if grep -q '^UPDATE_DOWNLOAD_PROXY_PREFIXES=' "$PROJECT_DIR/.env"; then
    DOWNLOAD_PROXY_PREFIXES="$(read_env UPDATE_DOWNLOAD_PROXY_PREFIXES)"
else
    DOWNLOAD_PROXY_PREFIXES="https://gh-proxy.com/,https://ghfast.top/,https://ghproxy.net/"
    ensure_env UPDATE_DOWNLOAD_PROXY_PREFIXES "$DOWNLOAD_PROXY_PREFIXES"
fi

mkdir -p "$UPDATE_DIR" /etc/yuzhanggui /usr/local/lib/yuzhanggui
chmod 1777 "$UPDATE_DIR"
install -m 0755 "$SCRIPT_DIR/yuzhanggui-update-agent.sh" /usr/local/lib/yuzhanggui/yuzhanggui-update-agent.sh
cat > /etc/yuzhanggui/update-agent.env <<EOF
PROJECT_DIR=$PROJECT_DIR
UPDATE_DIR=$UPDATE_DIR
UPDATE_RELEASE_API=https://api.github.com/repos/najiuwanan511/YuZhanggui/releases/latest
UPDATE_DOWNLOAD_PROXY_PREFIXES=$DOWNLOAD_PROXY_PREFIXES
EOF
chmod 0600 /etc/yuzhanggui/update-agent.env

install -m 0644 "$SCRIPT_DIR/yuzhanggui-update.service" /etc/systemd/system/yuzhanggui-update.service
sed "s|@UPDATE_DIR@|$UPDATE_DIR|g" "$SCRIPT_DIR/yuzhanggui-update.path" > /etc/systemd/system/yuzhanggui-update.path
chmod 0644 /etc/systemd/system/yuzhanggui-update.path
touch "$UPDATE_DIR/agent.ready"
chmod 0644 "$UPDATE_DIR/agent.ready"
systemctl daemon-reload
systemctl enable --now yuzhanggui-update.path

if [[ "$SKIP_APP_RECREATE" != "--skip-app-recreate" ]]; then
    cd "$PROJECT_DIR"
    export APP_GIT_SHA="$(git rev-parse --verify HEAD 2>/dev/null || echo unknown)"
    docker compose up -d --build --no-deps --force-recreate app
fi

echo
echo "YuZhanggui 在线更新代理已安装。"
echo "Docker 容器数量不变；更新代理由飞牛OS宿主机 systemd 运行。"
echo "更新目录: $UPDATE_DIR"
