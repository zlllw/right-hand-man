#!/usr/bin/env bash
# 契约 A 的 0b：把上游 bootstrap 的前缀重写为本项目包名。
#
# 三层重写（缺一不可，见 docs/契约A-阶段0-改包名与bootstrap.md 的 A4）：
#   1) 文本文件 + SYMLINKS.txt：前缀字符串替换；
#   2) ELF：只改 DT_RUNPATH（**不能 sed**，换长度会破坏 ELF 结构）；
#   3) SYMLINKS.txt 里符号链接的「目标」也是绝对路径，随第 1 层一起被替换。
#
# 用法（在 Linux / WSL 里跑，需要 patchelf + zip + unzip）：
#   bash scripts/rewrite-bootstrap.sh
#   NEW_PKG=com.example.app ARCH=aarch64 bash scripts/rewrite-bootstrap.sh
set -euo pipefail

NEW_PKG="${NEW_PKG:-com.zlllw.righthandman}"
OLD_PKG="${OLD_PKG:-com.termux}"
ARCH="${ARCH:-aarch64}"
SRC_ZIP="${SRC_ZIP:-app/src/main/cpp/bootstrap-${ARCH}.zip}"
OUT_ZIP="${OUT_ZIP:-$SRC_ZIP}"
WORK="${WORK:-${TMPDIR:-/tmp}/rhb-bootstrap-${ARCH}}"

# 注意：要替换的是**整个 app data 前缀**，不能只替换 /files/usr——
# bootstrap 里还有 files/home、files/usr-staging 等路径（契约 A 的 A4 第 1 层）。
OLD_DATA="/data/data/${OLD_PKG}"
NEW_DATA="/data/data/${NEW_PKG}"
NEW_RPATH="${NEW_DATA}/files/usr/lib"

for tool in patchelf zip unzip od sed; do
    command -v "$tool" >/dev/null 2>&1 || { echo "缺少依赖：$tool" >&2; exit 1; }
done
[ -f "$SRC_ZIP" ] || { echo "找不到 $SRC_ZIP" >&2; exit 1; }

# 首次运行时留一份上游原件；之后一律从原件解包，避免重复运行把已改内容当输入。
if [ ! -f "${SRC_ZIP}.upstream" ]; then
    cp "$SRC_ZIP" "${SRC_ZIP}.upstream"
    echo "已保存上游原件：${SRC_ZIP}.upstream"
fi
SOURCE_ZIP="${SRC_ZIP}.upstream"

rm -rf "$WORK"
mkdir -p "$WORK/tree"
echo "解包 $SOURCE_ZIP"
unzip -q "$SOURCE_ZIP" -d "$WORK/tree"
cd "$WORK/tree"

is_elf() {
    [ "$(head -c 4 "$1" | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]
}

elf_count=0
text_count=0
elf_failed=0

while IFS= read -r -d '' file; do
    if is_elf "$file"; then
        # ELF：只重写 rpath，绝不改字节长度
        if patchelf --set-rpath "$NEW_RPATH" "$file" 2>/dev/null; then
            elf_count=$((elf_count + 1))
        else
            elf_failed=$((elf_failed + 1))
            echo "WARN patchelf 失败：$file"
        fi
    else
        # 文本文件（含 SYMLINKS.txt）：前缀替换；二进制但非 ELF 的跳过，避免损坏
        if grep -Iq . "$file" 2>/dev/null; then
            if grep -q "$OLD_DATA" "$file" 2>/dev/null; then
                sed -i "s|$OLD_DATA|$NEW_DATA|g" "$file"
                text_count=$((text_count + 1))
            fi
        fi
    fi
done < <(find . -type f -print0)

echo "ELF 改写：$elf_count 个（失败 $elf_failed）"
echo "文本改写：$text_count 个"

# ---- 自检：残留必须为 0 ----
# 注意：grep 无匹配时返回 1，在 set -e + pipefail 下会被当成失败，所以统一加 || true
residue_text=$(grep -rl -I "$OLD_DATA" . 2>/dev/null | wc -l || true)
residue_symlink=$(grep -c "$OLD_DATA" SYMLINKS.txt 2>/dev/null || true)
residue_text=${residue_text:-0}
residue_symlink=${residue_symlink:-0}
echo "文本残留：$residue_text；SYMLINKS 残留：${residue_symlink:-0}"
if [ "$residue_text" -ne 0 ] || [ "${residue_symlink:-0}" -ne 0 ]; then
    echo "自检不通过：仍有 $OLD_DATA 残留" >&2
    exit 2
fi
[ -s SYMLINKS.txt ] || { echo "SYMLINKS.txt 缺失或为空，安装器会直接报错" >&2; exit 3; }
[ "$elf_failed" -eq 0 ] || { echo "有 ELF 改写失败，产物不可用" >&2; exit 4; }

# ---- 重新打包（覆盖原 zip） ----
rm -f "$WORK/out.zip"
zip -q -r -X -y "$WORK/out.zip" .
cd - >/dev/null
cp "$WORK/out.zip" "$OUT_ZIP"

echo "已覆盖：$OUT_ZIP"
echo "新 sha256：$(sha256sum "$OUT_ZIP" | cut -d' ' -f1)"
echo "大小：$(du -h "$OUT_ZIP" | cut -f1)"