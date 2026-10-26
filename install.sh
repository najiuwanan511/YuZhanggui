#!/usr/bin/env bash

set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

FORCE_MODE=""

for arg in "$@"; do
    case "$arg" in
        --fresh)
            FORCE_MODE="fresh"
            ;;
        --keep-data)
            FORCE_MODE="keep"
            ;;
        -h | --help)
            cat <<'EOF'
用法: ./install.sh [选项]

  --fresh       清空数据库数据卷全新安装（忽略现有 .env，会清空数据）
  --keep-data   保留已有数据卷，按提示输入原数据库密码
  -h, --help    显示本帮助
EOF
            exit 0
            ;;
        *)
            echo "未知参数: $arg（使用 --help 查看用法）" >&2
            exit 1
            ;;
    esac
done

MYSQL_DATA_VOLUME="${MYSQL_DATA_VOLUME:-yuzhanggui-mysql-data}"

is_interactive() {
    [ -t 0 ]
}

ask_choice() {
    local prompt="$1" default="$2" reply=""
    if is_interactive; then
        read -r -p "$prompt" reply || true
    fi
    printf '%s' "${reply:-$default}"
}

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

read_env_value() {
    local file="$1" key="$2"
    [ -f "$file" ] || return 0
    sed -n "s/^${key}=//p" "$file" | tail -n 1 | tr -d '\r'
}

set_env_value() {
    local key="$1" value="$2" escaped
    escaped="$(printf '%s' "$value" | sed -e 's/[\\&|]/\\&/g')"
    if grep -q "^${key}=" .env; then
        sed -i "s|^${key}=.*|${key}=${escaped}|" .env
    else
        printf '%s=%s\n' "$key" "$value" >> .env
    fi
}

volume_exists() {
    docker volume inspect "$1" >/dev/null 2>&1
}

require_command docker

if ! docker compose version >/dev/null 2>&1; then
    echo "需要 Docker Compose v2。" >&2
    exit 1
fi

generate_env() {
    cp .env.example .env
    # 首次安装生成独立密钥，避免示例凭据进入运行环境。
    set_env_value DB_PASSWORD "$(random_hex 24)"
    set_env_value DB_ROOT_PASSWORD "$(random_hex 24)"
    set_env_value JWT_SECRET "$(random_hex 48)"
    chmod 600 .env
}

remove_mysql_volume() {
    echo "正在删除数据库数据卷 $MYSQL_DATA_VOLUME ..."
    if ! docker volume rm -f "$MYSQL_DATA_VOLUME" >/dev/null 2>&1; then
        echo "无法删除数据卷 $MYSQL_DATA_VOLUME，可能仍有容器在使用它。" >&2
        echo "请先到旧的安装目录执行 docker compose down -v，再重新运行本脚本。" >&2
        exit 1
    fi
}

restore_env_from_backup() {
    local candidate
    for candidate in .env.bak .env.backup .env.old .env.save; do
        [ -f "$candidate" ] || continue
        [ -n "$(read_env_value "$candidate" DB_PASSWORD)" ] || continue
        echo "发现配置备份 $candidate，其中保存了原来的数据库密码。"
        case "$(ask_choice "是否用它恢复数据库密码？[Y/n] " "Y")" in
            [Yy]*)
                cp "$candidate" .env
                chmod 600 .env
                echo "已用 $candidate 恢复 .env。"
                return 0
                ;;
        esac
    done
    return 1
}

prompt_keep_data_password() {
    local db_password db_root_password
    if ! is_interactive; then
        echo "非交互执行且缺少 .env，无法询问原数据库密码。" >&2
        echo "请把原来的 .env 复制到 $ROOT_DIR 后重试，或使用 ./install.sh --fresh 全新安装。" >&2
        exit 1
    fi
    echo
    echo "以下两个密码即原安装目录 .env 中的 DB_PASSWORD 与 DB_ROOT_PASSWORD。"
    read -r -p "请输入原数据库用户密码 DB_PASSWORD: " db_password || true
    read -r -p "请输入原数据库 root 密码 DB_ROOT_PASSWORD: " db_root_password || true
    db_password="${db_password//[[:space:]]/}"
    db_root_password="${db_root_password//[[:space:]]/}"
    if [ -z "$db_password" ] || [ -z "$db_root_password" ]; then
        echo "密码为空，安装已中止。确认不需要旧数据可执行 ./install.sh --fresh。" >&2
        exit 1
    fi
    cp .env.example .env
    set_env_value DB_PASSWORD "$db_password"
    set_env_value DB_ROOT_PASSWORD "$db_root_password"
    set_env_value JWT_SECRET "$(random_hex 48)"
    chmod 600 .env
}

fresh_install() {
    echo "本次为全新安装：将清空数据库数据卷 $MYSQL_DATA_VOLUME 并重新生成 .env。"
    docker compose down --remove-orphans >/dev/null 2>&1 || true
    if volume_exists "$MYSQL_DATA_VOLUME"; then
        remove_mysql_volume
    fi
    rm -f .env
    generate_env
}

resolve_existing_volume() {
    echo
    echo "检测到已存在的数据库数据卷: $MYSQL_DATA_VOLUME"
    echo "当前目录没有 .env，无法得知这个数据卷里保存的数据库密码。"
    echo "MySQL 只在数据卷为空时初始化账号，直接生成新密码会导致应用启动报 Access denied 并反复重启。"
    echo

    if restore_env_from_backup; then
        echo "继续使用现有数据卷。"
        return 0
    fi

    if [ "$FORCE_MODE" = "keep" ]; then
        prompt_keep_data_password
        return 0
    fi

    if ! is_interactive; then
        echo "非交互执行，为避免误删数据，安装已中止。请任选一种方式后重试：" >&2
        echo "  保留数据：把原来的 .env 复制到 $ROOT_DIR" >&2
        echo "  全新安装：./install.sh --fresh（会清空数据库）" >&2
        exit 1
    fi

    echo "请选择处理方式："
    echo "  1) 保留数据卷，手动输入原来的数据库密码（数据不丢）"
    echo "  2) 删除数据卷，全新安装（会清空账号、商品、订单等全部数据）"
    echo "  如果只是从旧目录重装或升级、旧数据不要了，选 2 即可。"
    case "$(ask_choice "请输入 1 或 2 [默认 1]: " "1")" in
        2)
            case "$(ask_choice "确认删除 $MYSQL_DATA_VOLUME 及其中全部数据？请输入 delete 确认: " "")" in
                delete)
                    remove_mysql_volume
                    generate_env
                    ;;
                *)
                    echo "未确认，安装已中止。" >&2
                    exit 1
                    ;;
            esac
            ;;
        *)
            prompt_keep_data_password
            ;;
    esac
}

if [ "$FORCE_MODE" = "fresh" ]; then
    fresh_install
elif [ ! -f .env ]; then
    if volume_exists "$MYSQL_DATA_VOLUME"; then
        resolve_existing_volume
    else
        generate_env
    fi
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

print_db_access_denied_hint() {
    docker compose logs --no-color app 2>/dev/null | grep -q "Access denied for user" || return 0
    cat >&2 <<EOF

------------------------------------------------------------------------
数据库账号密码校验失败（MySQL 1045 Access denied）。

多数情况是重新执行安装时生成了新的随机数据库密码，却保留了旧的数据卷：
MySQL 只在数据卷为空时初始化账号，数据卷里保存的仍是旧密码。

  不需要旧数据（重装 / 升级场景）：
      docker compose down -v && docker compose up -d --build

  需要保留旧数据：
      把原来的 .env 复制回本目录，或执行 ./install.sh --keep-data 后按提示输入原密码
------------------------------------------------------------------------
EOF
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
        print_db_access_denied_hint
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
echo "请备份 $ROOT_DIR/.env：数据库密码在里面，丢失后无法连接已有数据卷。"
if [ -f "$UPDATE_HOST_DIR/agent.ready" ]; then
    echo "网页在线更新已启用，可在页面顶部的版本详情中直接更新。"
else
    echo "飞牛OS启用网页在线更新：sudo ./deploy/self-update/install-online-update.sh"
fi
echo "公网部署需先配置 deploy/nginx/certs、ALLOWED_ORIGINS 和 TRUST_PROXY，再执行:"
echo "docker compose --profile proxy up -d"
