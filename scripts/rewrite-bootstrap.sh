#!/usr/bin/env bash
# 契约 A 的 0b：把上游 bootstrap 里的包名做**等长原位替换**。
#
# 关键前提：新旧包名必须**字节数相同**（com.termux 与 com.rhmapp 都是 10 字节）。
# 只有等长，才能对 ELF / .so 内部编译进去的路径字符串做原位替换而不破坏其结构。
# 这也是为什么 apt/dpkg 的旧路径残留能被真正修掉——它们不是 RUNPATH，patchelf 管不到。
#
# 用法（在 Linux / WSL 里跑，需要 zip / unzip / python3）：
#   bash scripts/rewrite-bootstrap.sh
#   NEW_PKG=com.rhmapp ARCH=arm bash scripts/rewrite-bootstrap.sh
set -euo pipefail

NEW_PKG="${NEW_PKG:-com.rhmapp}"
OLD_PKG="${OLD_PKG:-com.termux}"
ARCH="${ARCH:-aarch64}"
SRC_ZIP="${SRC_ZIP:-app/src/main/cpp/bootstrap-${ARCH}.zip}"
OUT_ZIP="${OUT_ZIP:-$SRC_ZIP}"
WORK="${WORK:-${TMPDIR:-/tmp}/rhb-bootstrap-${ARCH}}"

for tool in zip unzip python3; do
    command -v "$tool" >/dev/null 2>&1 || { echo "缺少依赖：$tool" >&2; exit 1; }
done
[ -f "$SRC_ZIP" ] || { echo "找不到 $SRC_ZIP" >&2; exit 1; }

# 等长约束：不满足就直接拒绝，避免把 ELF 改坏
OLD_LEN=${#OLD_PKG}
NEW_LEN=${#NEW_PKG}
if [ "$OLD_LEN" -ne "$NEW_LEN" ]; then
    echo "包名长度不等（$OLD_PKG=$OLD_LEN 字节, $NEW_PKG=$NEW_LEN 字节）——" >&2
    echo "等长原位替换不成立；要么换等长包名，要么改用重建包的方案。" >&2
    exit 1
fi
echo "等长校验通过：${OLD_PKG} / ${NEW_PKG} 均为 ${OLD_LEN} 字节"

# 从上游原件解包，避免重复运行把已改内容当输入
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

# 全树等长替换：文本、ELF、.so 一视同仁，因为长度不变所以结构安全
python3 - "$OLD_PKG" "$NEW_PKG" <<'PY'
import os
import sys

old = sys.argv[1].encode("ascii")
new = sys.argv[2].encode("ascii")
if len(old) != len(new):
    sys.exit("包名长度不等，拒绝执行")

total = 0
touched = 0
for root, _dirs, files in os.walk("."):
    for name in files:
        path = os.path.join(root, name)
        if os.path.islink(path):
            continue
        try:
            with open(path, "rb") as handle:
                data = handle.read()
        except OSError as error:
            print("跳过（读失败）：%s (%s)" % (path, error), file=sys.stderr)
            continue
        if old not in data:
            continue
        count = data.count(old)
        with open(path, "wb") as handle:
            handle.write(data.replace(old, new))
        total += count
        touched += 1

print("替换完成：%d 处，涉及 %d 个文件" % (total, touched))
PY

# 自检 1：全树（含二进制）不得再有旧包名
residue=$(python3 - "$OLD_PKG" <<'PY'
import os
import sys

needle = sys.argv[1].encode("ascii")
hits = []
for root, _dirs, files in os.walk("."):
    for name in files:
        path = os.path.join(root, name)
        if os.path.islink(path):
            continue
        try:
            with open(path, "rb") as handle:
                if needle in handle.read():
                    hits.append(path)
        except OSError:
            pass
print(len(hits))
PY
)
echo "全树残留（含二进制）：$residue"
if [ "$residue" -ne 0 ]; then
    echo "自检不通过：仍有 $OLD_PKG 残留" >&2
    exit 2
fi

# 自检 2：SYMLINKS.txt 必须存在（安装器缺它直接抛错）
[ -s SYMLINKS.txt ] || { echo "SYMLINKS.txt 缺失或为空" >&2; exit 3; }

# 自检 3：抽查一个 ELF 的 RUNPATH 与 NEEDED 是否已指向新包名
if command -v readelf >/dev/null 2>&1; then
    probe="bin/bash"
    if [ -f "$probe" ]; then
        runpath=$(readelf -d "$probe" 2>/dev/null | grep -E "RUNPATH|RPATH" || true)
        echo "bash 的 rpath：${runpath:-（无）}"
        case "$runpath" in
            *"$NEW_PKG"*) : ;;
            *) echo "自检不通过：bash 的 RUNPATH 未指向新包名" >&2; exit 4 ;;
        esac
    fi
fi

# 重新打包（覆盖原 zip）
rm -f "$WORK/out.zip"
zip -q -r -X -y "$WORK/out.zip" .
cd - >/dev/null
cp "$WORK/out.zip" "$OUT_ZIP"

echo "已覆盖：$OUT_ZIP"
echo "新 sha256：$(sha256sum "$OUT_ZIP" | cut -d' ' -f1)"
echo "大小：$(du -h "$OUT_ZIP" | cut -f1)"