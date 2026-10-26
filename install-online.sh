#!/usr/bin/env bash

set -Eeuo pipefail

REPOSITORY="https://github.com/najiuwanan511/YuZhanggui.git"
TARGET_DIR="${YUZHANGGUI_INSTALL_DIR:-$HOME/yuzhanggui}"
INSTALL_URL="https://raw.githubusercontent.com/najiuwanan511/YuZhanggui/main/install-online.sh"
MODE=""

for arg in "$@"; do
    case "$arg" in
        --fresh)
            MODE="fresh"
            ;;
        -h | --help)
            cat <<'EOF'
用法: curl -fsSL https://raw.githubusercontent.com/najiuwanan511/YuZhanggui/main/install-online.sh | bash -s -- [选项]

  --fresh     安装目录已存在时，清空数据库数据卷与目录后全新安装（会清空数据）
  --help      显示本帮助

也可用 YUZHANGGUI_INSTALL_DIR=/某个路径 指定安装目录。
EOF
            exit 0
            ;;
        *)
            echo "未知参数: $arg（使用 --help 查看用法）" >&2
            exit 1
            ;;
    esac
done

command -v git >/dev/null 2>&1 || {
    echo "缺少 git，无法下载项目。" >&2
    exit 1
}

if [ -e "$TARGET_DIR" ]; then
    if [ "$MODE" = "fresh" ]; then
        echo "安装目录已存在: $TARGET_DIR"
        echo "按 --fresh 处理：清空数据卷并删除目录后全新安装。"
        if [ -f "$TARGET_DIR/compose.yaml" ]; then
            (cd "$TARGET_DIR" && docker compose down -v --remove-orphans) || true
        fi
        rm -rf "$TARGET_DIR"
    else
        cat <<EOF
安装目录已存在: $TARGET_DIR

为避免覆盖现有文件，安装已取消。请按需要选择一种方式：

1) 已装过、只是想更新或修复（保留数据，推荐）
     cd $TARGET_DIR && ./update.sh

2) 数据库连不上（日志出现 Access denied），想清空数据全新安装
     cd $TARGET_DIR && git pull --ff-only origin main && ./install.sh --fresh

   或一步重装（会删除 $TARGET_DIR 与数据库数据卷）：
     curl -fsSL $INSTALL_URL | bash -s -- --fresh

3) 装到另一个目录，旧的先不动
     YUZHANGGUI_INSTALL_DIR=$HOME/yuzhanggui2 curl -fsSL $INSTALL_URL | bash
EOF
        exit 1
    fi
fi

mkdir -p "$(dirname "$TARGET_DIR")"
git clone --depth 1 "$REPOSITORY" "$TARGET_DIR"
cd "$TARGET_DIR"

if [ "$MODE" = "fresh" ]; then
    ./install.sh --fresh
else
    ./install.sh
fi
