#!/bin/sh

set -eu

log() {
    printf '%s\n' "[yuzhanggui] $*"
}

start_remote_browser_display() {
    display="${DISPLAY:-:99}"
    remote_port="${CAPTCHA_BROWSER_REMOTE_PORT:-7900}"
    vnc_password="${CAPTCHA_BROWSER_VNC_PASSWORD:-change-me}"
    password_file=/tmp/x11vnc.pass
    xvfb_log=/tmp/xvfb.log
    x11vnc_log=/tmp/x11vnc.log
    novnc_log=/tmp/novnc.log

    case "$display" in
        :[0-9]*) ;;
        *)
            log "DISPLAY 必须是类似 :99 的 X11 显示编号，当前值为 $display"
            exit 1
            ;;
    esac

    if [ "$vnc_password" = "change-me" ]; then
        log "警告：仍在使用默认 VNC 密码，请设置 CAPTCHA_BROWSER_VNC_PASSWORD"
    fi

    if ! command -v Xvfb >/dev/null 2>&1 || ! command -v x11vnc >/dev/null 2>&1; then
        log "远程验证需要 Xvfb 和 x11vnc，但运行镜像中未找到它们"
        exit 1
    fi

    novnc_proxy=""
    for candidate in /usr/share/novnc/utils/novnc_proxy /usr/share/novnc/utils/novnc_proxy.py; do
        if [ -x "$candidate" ] || [ -f "$candidate" ]; then
            novnc_proxy="$candidate"
            break
        fi
    done
    if [ -z "$novnc_proxy" ]; then
        log "远程验证需要 noVNC，但未找到 novnc_proxy"
        exit 1
    fi

    x11vnc -storepasswd "$vnc_password" "$password_file" >/dev/null 2>&1
    Xvfb "$display" -screen 0 1440x900x24 -ac +extension RANDR >"$xvfb_log" 2>&1 &
    xvfb_pid=$!
    sleep 1
    if ! kill -0 "$xvfb_pid" 2>/dev/null; then
        log "Xvfb 启动失败，详情见 $xvfb_log"
        exit 1
    fi

    x11vnc -display "$display" -rfbport 5900 -rfbauth "$password_file" \
        -forever -shared -noxdamage -localhost >"$x11vnc_log" 2>&1 &
    x11vnc_pid=$!
    sleep 1
    if ! kill -0 "$x11vnc_pid" 2>/dev/null; then
        log "x11vnc 启动失败，详情见 $x11vnc_log"
        exit 1
    fi

    "$novnc_proxy" --vnc localhost:5900 --listen "$remote_port" >"$novnc_log" 2>&1 &
    novnc_pid=$!
    sleep 1
    if ! kill -0 "$novnc_pid" 2>/dev/null; then
        log "noVNC 启动失败，详情见 $novnc_log"
        exit 1
    fi

    log "飞牛远程验证已启动：监听端口 $remote_port，访问 /vnc.html?autoconnect=true&resize=scale"
    trap 'kill "$novnc_pid" "$x11vnc_pid" "$xvfb_pid" 2>/dev/null || true' INT TERM EXIT
}

if [ "${CAPTCHA_BROWSER_REMOTE_ENABLED:-false}" = "true" ]; then
    start_remote_browser_display
fi

runtime_jar="${UPDATE_JAR_PATH:-/app/update/app.jar}"
if [ ! -r "$runtime_jar" ]; then
    runtime_jar=/app/app.jar
fi

exec java ${JAVA_OPTS:-} -Dserver.port="${SERVER_PORT:-12400}" -jar "$runtime_jar"
