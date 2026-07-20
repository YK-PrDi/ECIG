#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/www/wwwroot/ai-studio}"
SERVICE_NAME="${SERVICE_NAME:-ai-studio}"

if [ "$(id -u)" -ne 0 ]; then
  echo "请用 root 执行：sudo bash $0" >&2
  exit 1
fi

if [ ! -d "$APP_DIR" ]; then
  echo "目录不存在：$APP_DIR" >&2
  exit 1
fi

detect_target_user() {
  if [ -n "${TARGET_USER:-}" ]; then
    echo "$TARGET_USER"
    return
  fi
  if [ -n "${SUDO_USER:-}" ] && [ "${SUDO_USER:-}" != "root" ]; then
    echo "$SUDO_USER"
    return
  fi
  if id www >/dev/null 2>&1; then
    echo "www"
    return
  fi
  if id www-data >/dev/null 2>&1; then
    echo "www-data"
    return
  fi
  useradd -r -s /usr/sbin/nologin aistudio 2>/dev/null || useradd -r -s /sbin/nologin aistudio
  echo "aistudio"
}

TARGET_USER="$(detect_target_user)"
TARGET_GROUP="$(id -gn "$TARGET_USER")"
APP_JAR="$APP_DIR/app.jar"

echo "==> 目标目录：$APP_DIR"
echo "==> 降权用户：$TARGET_USER:$TARGET_GROUP"

if systemctl list-unit-files "${SERVICE_NAME}.service" >/dev/null 2>&1; then
  echo "==> 停止服务：$SERVICE_NAME"
  systemctl stop "$SERVICE_NAME" 2>/dev/null || true
fi

echo "==> 解除不可变属性和 ACL"
if command -v chattr >/dev/null 2>&1; then
  chattr -R -i "$APP_DIR" 2>/dev/null || true
fi
if command -v setfacl >/dev/null 2>&1; then
  setfacl -Rb "$APP_DIR" 2>/dev/null || true
fi

echo "==> 把目录从 root 降到 $TARGET_USER:$TARGET_GROUP"
chown -R "$TARGET_USER:$TARGET_GROUP" "$APP_DIR"

echo "==> 设置最小可用权限"
find "$APP_DIR" -type d -exec chmod 755 {} \;
find "$APP_DIR" -type f -exec chmod 644 {} \;
if [ -f "$APP_DIR/.env" ]; then
  chmod 600 "$APP_DIR/.env"
fi

echo "==> 用低权限用户验证目录可写可删"
runuser -u "$TARGET_USER" -- sh -c '
  set -e
  test_file="$1/.permission-delete-test.$$"
  printf test > "$test_file"
  rm -f "$test_file"
' sh "$APP_DIR"

if [ -f "$APP_JAR" ]; then
  echo "==> 用低权限用户验证 app.jar 可移动/替换"
  runuser -u "$TARGET_USER" -- sh -c '
    set -e
    jar="$1"
    mv "$jar" "$jar.permission-test"
    mv "$jar.permission-test" "$jar"
  ' sh "$APP_JAR"
fi

if systemctl list-unit-files "${SERVICE_NAME}.service" >/dev/null 2>&1; then
  echo "==> 用 systemd override 把服务运行用户从 root 降到 $TARGET_USER"
  mkdir -p "/etc/systemd/system/${SERVICE_NAME}.service.d"
  cat > "/etc/systemd/system/${SERVICE_NAME}.service.d/override.conf" <<EOF
[Service]
User=$TARGET_USER
Group=$TARGET_GROUP
EOF
  systemctl daemon-reload
  systemctl start "$SERVICE_NAME" 2>/dev/null || {
    echo "服务启动失败，请查看：journalctl -u $SERVICE_NAME -n 80 --no-pager" >&2
    exit 1
  }
fi

echo "==> 当前权限"
stat -c '%U:%G %a %n' "$APP_DIR" "$APP_JAR" 2>/dev/null || true
if command -v lsattr >/dev/null 2>&1; then
  lsattr -d "$APP_DIR" "$APP_JAR" 2>/dev/null || true
fi

echo "完成：$APP_DIR 已降权到 $TARGET_USER:$TARGET_GROUP，低权限用户已验证可删除/移动目录内文件。"
