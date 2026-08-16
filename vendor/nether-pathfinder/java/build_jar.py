"""mockplayer P15 打包：把 natives 目录里的 native 库 + java 类打成 nether-pathfinder jar。

CI 走 build-natives.sh（zig 6 平台）+ 原版 gradle 流水线；本机 Windows 无法执行
.sh 编译器（cmake CreateProcess 不认 shebang），用本脚本生成单平台 jar 供本地
mocktest 与 vendor/baritone flatDir 依赖使用。

用法：python build_local_jar.py <natives目录> <输出jar路径>
（natives 目录里的 *.dll / *.so / *.dylib 全部进 natives.zip.xz，entry 名 = 文件名）
"""
import lzma
import os
import sys
import zipfile


def main():
    natives_dir, out_jar = sys.argv[1], sys.argv[2]
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # vendor/nether-pathfinder
    java_src = os.path.join(root, "java", "src", "main", "java")

    # 1) 编译 java 类（需要 xz jar 提供 XZInputStream，仅编译期）
    import subprocess
    classes_dir = os.path.join(root, ".tools", "classes")
    os.makedirs(classes_dir, exist_ok=True)
    xz = _find_xz_jar()
    subprocess.run(
        ["javac", "-encoding", "UTF-8", "-cp", xz, "-d", classes_dir] +
        [os.path.join(java_src, "dev", "babbaj", "pathfinder", f)
         for f in ("NetherPathfinder.java", "Octree.java", "PathSegment.java")],
        check=True,
    )

    # 2) natives.zip（NO_COMPRESSION，与原版 joinNatives 一致）→ natives.zip.xz
    # 注意：entry 名必须匹配 NetherPathfinder.getNativeLibName()（平台 + arch 后缀，
    # 如 nether_pathfinder-x86_64.dll / libnether_pathfinder-aarch64.so）
    tmp_zip = os.path.join(root, ".tools", "natives.zip")
    natives = [os.path.join(natives_dir, f) for f in os.listdir(natives_dir)
               if f.endswith((".dll", ".so", ".dylib"))]
    if not natives:
        raise SystemExit("no native libs found in " + natives_dir)
    with zipfile.ZipFile(tmp_zip, "w", compression=zipfile.ZIP_STORED) as z:
        for n in natives:
            z.write(n, os.path.basename(n))
    print("natives packed:", [os.path.basename(n) for n in natives])
    with open(tmp_zip, "rb") as f, open(os.path.join(root, ".tools", "natives.zip.xz"), "wb") as out:
        out.write(lzma.compress(f.read(), preset=9 | lzma.PRESET_EXTREME))

    # 3) jar：类 + natives.zip.xz + xz 解压库（原版用 shadowJar relocate + proguard，
    # 本机简化版直接以 org.tukaani.xz 包名并入——NetherPathfinder 静态块解压 natives
    # 需要它；正式发布 jar 由 CI 走原版 gradle 流程（relocate + 混淆））
    with zipfile.ZipFile(out_jar, "w", compression=zipfile.ZIP_DEFLATED) as z:
        for base, _, files in os.walk(classes_dir):
            for fn in files:
                p = os.path.join(base, fn)
                z.write(p, os.path.relpath(p, classes_dir))
        with zipfile.ZipFile(_find_xz_jar()) as xzjar:
            for entry in xzjar.namelist():
                if entry.startswith("org/tukaani/xz/"):
                    z.writestr(entry, xzjar.read(entry))
        z.write(os.path.join(root, ".tools", "natives.zip.xz"), "natives.zip.xz")
    print("jar written:", out_jar)


def _find_xz_jar():
    import glob
    home = os.path.expanduser("~")
    hits = glob.glob(os.path.join(home, ".gradle", "caches", "modules-2", "files-2.1",
                                  "org.tukaani", "xz", "*", "*", "xz-*.jar"))
    if not hits:
        raise SystemExit("xz jar not found in gradle cache")
    return hits[0]

if __name__ == "__main__":
    main()
