#!/usr/bin/env bash
# mockplayer P15：用 zig 0.11.0 交叉编译 nether-pathfinder 6 平台 native 库。
# 替代原版 multiplat_build.sh（其硬编码 cp libnether_pathfinder.so，Windows 产物会漏）。
# 用法：ZIG_DIR=<zig 可执行所在目录> ./build-natives.sh [输出目录]
# 输出目录默认 ./natives-out/，产物名与原版 nether-pathfinder jar 的 natives.zip.xz 一致。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
ZIG_DIR="${ZIG_DIR:-$ROOT/.tools/zig-windows-x86_64-0.11.0}"
OUT="${1:-$ROOT/natives-out}"
mkdir -p "$OUT"

# 局部 PATH（仅本脚本进程），不污染全局环境
export PATH="$ZIG_DIR:$PATH"
zig version >/dev/null

build_one() {
    local target="$1" outname="$2"
    local bd="$ROOT/.tools/build-$target"
    rm -rf "$bd"
    mkdir -p "$bd"
    (cd "$bd" && CXXFLAGS="-target $target" cmake -G Ninja "$ROOT" \
        -DPATHFINDER_TARGET="$target" \
        -DCMAKE_C_COMPILER="$ROOT/java/zigcc.sh" \
        -DCMAKE_CXX_COMPILER="$ROOT/java/zigcxx.sh" \
        -DCMAKE_AR="$ROOT/java/zigar.sh" \
        -DCMAKE_RANLIB="$ROOT/java/zigranlib.sh" \
        -DCMAKE_BUILD_TYPE=Release)
    ninja -C "$bd" -j"$(nproc)"
    # 产物名不跨平台统一（GNU 风格 libnether_pathfinder.dll / .so / .dylib），glob 收集
    local found=0
    while IFS= read -r -d '' f; do
        cp "$f" "$OUT/$outname"
        found=1
        echo "built: $outname ($target)"
    done < <(find "$bd" -maxdepth 1 \( -name "*.dll" -o -name "*.so" -o -name "*.dylib" \) -print0)
    [ "$found" = "1" ] || { echo "ERROR: no shared lib output for $target" >&2; exit 1; }
}

build_one x86_64-linux-gnu    libnether_pathfinder-x86_64.so
build_one aarch64-linux-gnu   libnether_pathfinder-aarch64.so
build_one x86_64-macos-none   libnether_pathfinder-x86_64.dylib
build_one aarch64-macos-none  libnether_pathfinder-aarch64.dylib
build_one x86_64-windows-gnu  nether_pathfinder-x86_64.dll
build_one aarch64-windows-gnu nether_pathfinder-aarch64.dll

echo "natives done -> $OUT"
