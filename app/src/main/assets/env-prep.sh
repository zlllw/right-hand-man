#!/usr/bin/env bash
# 手信「首次运行环境准备」脚本（任务书阶段 1）
#
# 为什么不能直接 apt install：
#   Termux 官方源的 deb 包内部路径固定为 /data/data/com.termux/...，
#   而本应用包名是等长的 com.rhmapp，dpkg 解包会写到不属于本应用的前缀并因权限被拒。
#   因此必须先把 deb 解包、改路径、做等长字节替换，再重新打包安装。
#   等长替换的前提是 10 字节的包名（com.termux / com.rhmapp），脚本会先校验。
#
# 输出：stdout 逐行人类可读进度；退出码见下方。
# 退出码：0 就绪 / 1 用法或环境前提不满足 / 2 bootstrap 缺失 /
#         3 apt update 失败 / 4 下载失败 / 5 重打包失败 / 6 安装失败 / 7 自检失败

set -u
set -o pipefail

PREFIX="${PREFIX:-/data/data/com.rhmapp/files/usr}"
PKG_DIR="${PREFIX%/files/usr}"
UPSTREAM_PREFIX=/data/data/com.termux
HOME="${HOME:-$PKG_DIR/files/home}"
PATH="$PREFIX/bin:/system/bin"
TMPDIR="${TMPDIR:-$PREFIX/tmp}"
export PREFIX HOME PATH TMPDIR
export DEBIAN_FRONTEND=noninteractive
export LANG="${LANG:-en_US.UTF-8}"
export LC_ALL="${LC_ALL:-en_US.UTF-8}"

APT_OPTS="-q -y -o Dpkg::Use-Pty=0 -o Acquire::Retries=2"
WORK="$TMPDIR/env-prep"
FIXED="$WORK/fixed"
CACHE="$PKG_DIR/cache/apt/archives"
PKGS="python nodejs libc++ npm"

step() { echo ""; echo "==> $*"; }
fail() { echo "FAIL $*"; exit "${2:-1}"; }

if [ "${#PKG_DIR}" -ne "${#UPSTREAM_PREFIX}" ]; then
  fail "包名长度与上游不一致（$PKG_DIR vs $UPSTREAM_PREFIX），无法做等长路径改写" 1
fi

for bin in apt-get dpkg dpkg-deb sed grep find; do
  command -v "$bin" >/dev/null 2>&1 || fail "缺少 $bin：bootstrap 未就绪" 2
done

# 已就绪就直出一条完成信息（node 链接失败也算未就绪，会顺手补 libc++）
if "$PREFIX/bin/python3" -c "import ssl,sqlite3" >/dev/null 2>&1 \
   && "$PREFIX/bin/node" --version >/dev/null 2>&1 \
   && "$PREFIX/bin/npm" --version >/dev/null 2>&1; then
  echo "python3 $("$PREFIX/bin/python3" --version 2>&1)"
  echo "node $("$PREFIX/bin/node" --version 2>&1)"
  echo "npm $("$PREFIX/bin/npm" --version 2>&1)"
  echo "READY 运行环境已就绪，无需安装"
  exit 0
fi

rm -rf "$FIXED" "$WORK/unpack"
mkdir -p "$FIXED" "$TMPDIR" || fail "无法创建临时目录 $TMPDIR" 2

step "更新包索引"
if ! apt-get $APT_OPTS update >"$WORK/apt-update.log" 2>&1; then
  tail -n 20 "$WORK/apt-update.log"
  fail "apt update 失败（检查网络或源可达性）" 3
fi
tail -n 2 "$WORK/apt-update.log"

# 包已登记但关键文件被删（例如手工动过 $PREFIX）时，--download-only 不会重新下载，
# dpkg -i 也就拿不到包，自检必然失败。这里显式 --reinstall 兜住这种降级场景。
REINSTALL=""
for pair in "python:$PREFIX/bin/python3" "nodejs:$PREFIX/bin/node" "npm:$PREFIX/bin/npm" "libc++:$PREFIX/lib/libc++_shared.so"; do
  pkg="${pair%%:*}"
  file="${pair#*:}"
  if dpkg -s "$pkg" >/dev/null 2>&1 && [ ! -e "$file" ]; then
    REINSTALL="$REINSTALL --reinstall $pkg"
  fi
done
if [ -n "$REINSTALL" ]; then
  echo "    检测到已登记但文件缺失的包，强制重装：$REINSTALL"
fi

step "下载 $PKGS 及其依赖"
if ! apt-get $APT_OPTS install --download-only --no-install-recommends $REINSTALL $PKGS >"$WORK/apt-dl.log" 2>&1; then
  tail -n 20 "$WORK/apt-dl.log"
  fail "下载失败（检查网络或磁盘空间）" 4
fi
grep -E "^(Fetched|Need to get|Download complete)" "$WORK/apt-dl.log" | tail -n 2

# 把 deb 内部前缀改成本应用前缀，再做等长字节替换
repack() {
  local in=$1 out=$2 root="$WORK/unpack" n=0 f s
  rm -rf "$root"
  mkdir -p "$root" || return 1
  dpkg-deb -R "$in" "$root" || return 1
  if [ -d "$root$UPSTREAM_PREFIX" ]; then
    mv "$root$UPSTREAM_PREFIX" "$root$PKG_DIR" || return 1
  fi
  while IFS= read -r -d '' f; do
    if grep -qa "$UPSTREAM_PREFIX" "$f" 2>/dev/null; then
      sed -i "s|$UPSTREAM_PREFIX|$PKG_DIR|g" "$f" || return 1
      n=$((n + 1))
    fi
  done < <(find "$root" -type f -print0)
  # sed -i 会重建文件并丢掉可执行位，maintainer script 必须复原，否则 dpkg-deb 拒绝打包
  for s in preinst postinst prerm postrm config triggers; do
    if [ -f "$root/DEBIAN/$s" ]; then chmod 0755 "$root/DEBIAN/$s"; fi
  done
  dpkg-deb --root-owner-group -Zzstd -z1 -b "$root" "$out" >/dev/null 2>&1 \
    || dpkg-deb --root-owner-group -b "$root" "$out" >/dev/null 2>&1 \
    || return 1
  echo "    改写 $n 个文件：$(basename "$out")"
  return 0
}

step "改写软件包内的路径前缀"
count=0
for deb in "$CACHE"/*.deb; do
  [ -e "$deb" ] || continue
  repack "$deb" "$FIXED/$(basename "$deb")" || fail "改写 $(basename "$deb") 失败" 5
  count=$((count + 1))
done
echo "    共 $count 个包"

if [ "$count" -gt 0 ]; then
  step "安装 $count 个包"
  if ! dpkg -i "$FIXED"/*.deb >"$WORK/dpkg.log" 2>&1; then
    tail -n 25 "$WORK/dpkg.log"
    fail "dpkg 安装失败" 6
  fi
  tail -n 3 "$WORK/dpkg.log"
else
  echo "    没有需要安装的新包"
fi

step "自检"
"$PREFIX/bin/python3" -c "import ssl,sqlite3,sys; print('    python prefix = ' + sys.prefix)" \
  || fail "python3 自检失败" 7
"$PREFIX/bin/node" --version || fail "node 自检失败" 7
"$PREFIX/bin/npm" --version || fail "npm 自检失败（opencode / Claude Code 靠它安装）" 7

step "清理"
rm -rf "$FIXED" "$WORK/unpack" "$WORK"/*.log
rm -f "$CACHE"/*.deb
echo "    $(du -sh "$PREFIX" 2>/dev/null | cut -f1) 已占用"
echo "READY 运行环境已就绪"
exit 0