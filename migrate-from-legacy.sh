#!/usr/bin/env bash

set -Eeuo pipefail

NEW_REPOSITORY="https://github.com/najiuwanan511/YuZhanggui.git"
NEW_UPDATE_REPOSITORY="najiuwanan511/YuZhanggui"
LEGACY_DIR="${YUZHANGGUI_LEGACY_DIR:-$(pwd)}"
TARGET_DIR="${YUZHANGGUI_TARGET_DIR:-$HOME/yuzhanggui}"

fail() {
    echo "迁移失败: $*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "缺少命令: $1"
}

set_env() {
    local key="$1"
    local value="$2"
    if grep -q "^${key}=" "$TARGET_DIR/.env"; then
        sed -i "s|^${key}=.*|${key}=${value}|" "$TARGET_DIR/.env"
    else
        printf '\n%s=%s\n' "$key" "$value" >> "$TARGET_DIR/.env"
    fi
}

find_legacy_volume() {
    local preferred_name="$1"
    local fallback_name="$2"
    if docker volume inspect "$preferred_name" >/dev/null 2>&1; then
        printf '%s\n' "$preferred_name"
    elif docker volume inspect "$fallback_name" >/dev/null 2>&1; then
        printf '%s\n' "$fallback_name"
    fi
}

require_command docker
require_command git
require_command gzip
docker compose version >/dev/null 2>&1 || fail "需要 Docker Compose v2"

LEGACY_DIR="$(cd "$LEGACY_DIR" && pwd)"
[ -f "$LEGACY_DIR/.env" ] || fail "未找到 $LEGACY_DIR/.env。请先进入旧项目目录后再执行。"
[ -f "$LEGACY_DIR/compose.yaml" ] || fail "未找到 $LEGACY_DIR/compose.yaml。请确认这是旧项目目录。"
[ ! -e "$TARGET_DIR" ] || fail "目标目录已存在: $TARGET_DIR。请设置一个空的 YUZHANGGUI_TARGET_DIR 后重试。"

mkdir -p "$(dirname "$TARGET_DIR")"
git clone --depth 1 "$NEW_REPOSITORY" "$TARGET_DIR"
cp "$LEGACY_DIR/.env" "$TARGET_DIR/.env"
chmod 600 "$TARGET_DIR/.env"

# 旧版本（XianYuPlus / XianYuSmart）没有把卷名写进 .env，这里沿用旧卷名，
# 避免在用户数据旁边新建一套空卷。
MYSQL_VOLUME="$(find_legacy_volume xianyu-plus-mysql-data xianyusmart_mysql-data || true)"
APP_DATA_VOLUME="$(find_legacy_volume xianyu-plus-app-data xianyusmart_app-data || true)"
APP_LOGS_VOLUME="$(find_legacy_volume xianyu-plus-app-logs xianyusmart_app-logs || true)"
[ -n "$MYSQL_VOLUME" ] && set_env MYSQL_DATA_VOLUME "$MYSQL_VOLUME"
[ -n "$APP_DATA_VOLUME" ] && set_env APP_DATA_VOLUME "$APP_DATA_VOLUME"
[ -n "$APP_LOGS_VOLUME" ] && set_env APP_LOGS_VOLUME "$APP_LOGS_VOLUME"

# 旧库名可能仍是 xianyusmart，迁移时跟随旧配置，不强制改名，避免丢数据。
set_env UPDATE_GITHUB_REPOSITORY "$NEW_UPDATE_REPOSITORY"
set_env UPDATE_RELEASE_API "https://api.github.com/repos/${NEW_UPDATE_REPOSITORY}/releases/latest"

BACKUP_FILE="$LEGACY_DIR/.env.before-yuzhanggui-migration.$(date +%Y%m%d-%H%M%S)"
DATABASE_BACKUP_FILE="$LEGACY_DIR/yuzhanggui-migration-backup.$(date +%Y%m%d-%H%M%S).sql.gz"
cp "$LEGACY_DIR/.env" "$BACKUP_FILE"

echo "正在备份旧数据库..."
docker compose --project-directory "$LEGACY_DIR" --env-file "$LEGACY_DIR/.env" exec -T --interactive=false mysql \
    sh -c 'mysqldump --single-transaction -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' \
    | gzip > "$DATABASE_BACKUP_FILE"
[ -s "$DATABASE_BACKUP_FILE" ] || fail "数据库备份文件为空，已取消迁移"

echo "正在停止旧服务（不会删除数据卷）..."
docker compose --project-directory "$LEGACY_DIR" --env-file "$LEGACY_DIR/.env" down --remove-orphans

echo "正在启动新版本并执行数据兼容检查..."
cd "$TARGET_DIR"
./update.sh

# 旧版本装的在线更新代理仍指向 old 路径，需要重新安装到新目录。
if [ -f /etc/systemd/system/xianyu-plus-update.path ] || [ -f /etc/xianyu-plus/update-agent.env ]; then
    echo "检测到旧版在线更新代理，正在切换到新项目目录..."
    if ! ./deploy/self-update/install-online-update.sh "$TARGET_DIR"; then
        echo "警告: 新服务已启动，但在线更新代理尚未切换。" >&2
        echo "请在新项目目录执行: sudo ./deploy/self-update/install-online-update.sh" >&2
    fi
fi

echo
echo "迁移完成。数据卷和原项目目录均已保留。"
echo "新项目目录: $TARGET_DIR"
echo "旧配置备份: $BACKUP_FILE"
echo "数据库备份: $DATABASE_BACKUP_FILE"
echo "后续更新请在新项目目录执行: ./update.sh"
