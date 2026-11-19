#!/usr/bin/env bash
# 契约 A 的 0c-静态：对重写后的 bootstrap 做只读断言（不执行任何二进制）。
#
# 用法：
#   bash scripts/verify-bootstrap.sh            # 默认 aarch64
#   ARCH=arm bash scripts/verify-bootstrap.sh
set -uo pipefail

ARCH="${ARCH:-aarch64}"
NEW_PKG="${NEW_PKG:-com.zlllw.righthandman}"
OLD_PKG="${OLD_PKG:-com.termux}"
ZIP="${ZIP:-app/src/main/cpp/bootstrap-${ARCH}.zip}"
NEW_DATA="/data/data/${NEW_PKG}"
OLD_DATA="/data/data/${OLD_PKG}"
NEW_RPATH="${NEW_DATA}/files/usr/lib"

[ -f "$ZIP" ] || { echo "找不到 $ZIP" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
unzip -q "$ZIP" -d "$WORK" || { echo "解包失败" >&2; exit 1; }

fail=0
note() { printf '%-42s %s\n' "$1" "$2"; }
is_elf() { [ "$(head -c 4 "$1" | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]; }

# 1) 全树残留必须为 0——包含 ELF/.so 内部的字符串（等长替换后应当一并干净）
residue=$(python3 - "$WORK" "$OLD_DATA" <<'PY'
import os
import sys

root_dir, needle = sys.argv[1], sys.argv[2].encode("ascii")
hits = 0
for root, _dirs, files in os.walk(root_dir):
    for name in files:
        path = os.path.join(root, name)
        if os.path.islink(path):
            continue
        try:
            with open(path, "rb") as handle:
                if needle in handle.read():
                    hits += 1
        except OSError:
            pass
print(hits)
PY
)
residue=${residue:-0}
[ "$residue" -eq 0 ] && note "全树残留（含二进制，应为 0）" "0 ✓" || { note "全树残留（含二进制，应为 0）" "$residue ✗"; fail=1; }

# 2) SYMLINKS.txt 必须存在，且目标前缀已是新包名
if [ -s "$WORK/SYMLINKS.txt" ]; then
    link_old=$(grep -c "$OLD_DATA" "$WORK/SYMLINKS.txt" 2>/dev/null || true)
    link_old=${link_old:-0}
    [ "$link_old" -eq 0 ] && note "SYMLINKS 旧前缀（应为 0）" "0 ✓" || { note "SYMLINKS 旧前缀（应为 0）" "$link_old ✗"; fail=1; }
    note "SYMLINKS 首行目标" "$(head -1 "$WORK/SYMLINKS.txt" | cut -c1-70)…"
else
    note "SYMLINKS.txt" "缺失 ✗（安装器会直接抛错）"
    fail=1
fi

# 3) rpath 抽查
for probe in bin/bash bin/sh; do
    if [ -f "$WORK/$probe" ] && is_elf "$WORK/$probe"; then
        rpath=$(patchelf --print-rpath "$WORK/$probe" 2>/dev/null)
        if [ "$rpath" = "$NEW_RPATH" ]; then note "$probe 的 rpath" "$rpath ✓"
        else note "$probe 的 rpath" "$rpath ✗"; fail=1; fi
    fi
done

# 4) 关键可执行文件存在（不执行，只查文件）
[ -f "$WORK/bin/bash" ] && note "bin/bash" "存在 ✓" || { note "bin/bash" "缺失 ✗"; fail=1; }

# 5) ELF 内部残留：只统计、不判失败（patchelf 改不了编译进二进制的字符串）
elf_residue=0
while IFS= read -r f; do
    [ -z "$f" ] && continue
    if is_elf "$f"; then elf_residue=$((elf_residue + 1)); fi
done < <(grep -rl -a "$OLD_DATA" "$WORK" 2>/dev/null || true)
note "ELF 内部旧路径（仅统计）" "$elf_residue 个（需重建包才能消除）"

# 6) 概览
note "bin/ 文件数" "$(find "$WORK/bin" -type f 2>/dev/null | wc -l)"
note "是否有 python3" "$([ -f "$WORK/bin/python3" ] && echo 有 || echo 无（本轮未并入）)"

echo
if [ "$fail" -eq 0 ]; then
    echo "0c-静态：通过"
else
    echo "0c-静态：不通过" >&2
    exit 2
fi