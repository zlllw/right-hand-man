#!/usr/bin/env bash
# 手信内的一键 opencode 安装/修复脚本。
# 只用 app 自带的 Termux 环境：npm 取包、tarball 提供 musl 二进制，路径改写成 com.rhmapp。
# 退出码：0 就绪 / 2 架构不支持 / 3 缺少工具 / 4 npm 安装失败 /
#         5 路径补丁失败 / 6 下载或校验失败 / 7 解包失败 / 8 自检失败

set -u

PREFIX="${PREFIX:-/data/data/com.rhmapp/files/usr}"
HOME="${HOME:-/data/data/com.rhmapp/files/home}"
TMPDIR="${TMPDIR:-$PREFIX/tmp}"
PATH="$PREFIX/bin:/system/bin"
export PREFIX HOME TMPDIR PATH
export OPENCODE_TERMUX_AUTOUPDATE=0

VERSION="1.19.3"
PKG_DIR="$PREFIX/lib/node_modules/opencode-termux"
URL="https://github.com/C04-wq/opencode-termux/releases/download/v${VERSION}/opencode-termux-aarch64.tar.gz"
TARBALL="$TMPDIR/opencode-termux-aarch64.tar.gz"

step() { echo ""; echo "==> $*"; }
fail() { echo "FAIL $*"; exit "${2:-1}"; }

case "$(uname -m)" in
  aarch64|arm64) ;;
  *) fail "当前只支持 arm64 设备（检测到 $(uname -m)）" 2 ;;
esac

for tool in npm node curl tar sha256sum; do
  command -v "$tool" >/dev/null 2>&1 || fail "缺少 $tool，请先完成运行环境准备" 3
done

# 已装好且 wrapper 已是本应用路径时，直接返回，避免重复下载
if [ -x "$PREFIX/bin/opencode" ] && [ -f "$HOME/.opencode/.opencode-termux-version" ]; then
  current="$("$PREFIX/bin/opencode" --version 2>/dev/null | head -n 1)"
  case "$current" in
    *"$VERSION"*) echo "READY opencode $current 已安装"; exit 0 ;;
  esac
fi

step "安装 opencode-termux@$VERSION"
if ! npm install -g --ignore-scripts "opencode-termux@$VERSION"; then
  echo "    默认源失败，改用 npmmirror"
  npm install -g --ignore-scripts --registry=https://registry.npmmirror.com \
    "opencode-termux@$VERSION" || fail "npm 安装失败（检查网络/代理）" 4
fi
[ -f "$PKG_DIR/bin/opencode" ] || fail "npm 包缺少 bin/opencode" 4

step "适配 com.rhmapp 路径"
sed -i 's|/data/data/com.termux|/data/data/com.rhmapp|g' "$PKG_DIR/bin/opencode" "$PKG_DIR/install.js" \
  || fail "路径改写失败" 5
# 安装脚本会尝试 pkg install patchelf，在本应用前缀下必然失败；wrapper 已改用 musl loader，
# 不需要 patchelf，直接跳过依赖检查。
sed -i '/install\.js" --ensure-dependencies/d' "$PKG_DIR/bin/opencode" \
  || fail "依赖检查改写失败" 5
grep -q '/data/data/com.rhmapp/files/usr/bin/bash' "$PKG_DIR/bin/opencode" \
  || fail "wrapper shebang 未改写" 5

step "准备 opencode 二进制"
rm -f "$TARBALL"
if [ -r /data/local/tmp/oc.tar.gz ]; then
  echo "    使用 /data/local/tmp/oc.tar.gz 缓存"
  cp /data/local/tmp/oc.tar.gz "$TARBALL" || fail "复制本地缓存失败" 6
else
  echo "    下载约 62MB，请保持网络连接"
  curl -L --fail --retry 3 --retry-all-errors --connect-timeout 20 --max-time 900 \
    -o "$TARBALL" "$URL" || fail "下载失败" 6
fi

expected="$(node -e 'const r=require(process.argv[1]); console.log(r.archiveSha256||"")' \
  "$PKG_DIR/release-checksums.json" 2>/dev/null || true)"
actual="$(sha256sum "$TARBALL" | cut -d' ' -f1)"
if [ -n "$expected" ] && [ "$expected" != "$actual" ]; then
  rm -f "$TARBALL"
  fail "SHA256 校验失败" 6
fi

step "解包并写入 ~/.opencode"
rm -rf "$HOME/.opencode"
mkdir -p "$HOME/.opencode" || fail "无法创建 ~/.opencode" 7
tar xzf "$TARBALL" -C "$HOME/.opencode" || fail "解包失败" 7
chmod 755 "$HOME/.opencode/opencode" "$HOME/.opencode/ld-musl-aarch64.so.1" 2>/dev/null || true
printf "%s\n" "$VERSION" > "$HOME/.opencode/.opencode-termux-version"
rm -f "$TARBALL"

step "自检"
current="$("$PREFIX/bin/opencode" --version 2>&1 | head -n 1)"
echo "    $current"
case "$current" in
  *"$VERSION"*) ;;
  *) fail "opencode 自检失败：$current" 8 ;;
esac

echo "READY opencode $VERSION 安装完成"
exit 0