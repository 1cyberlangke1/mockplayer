import dev.babbaj.pathfinder.NetherPathfinder;
import dev.babbaj.pathfinder.PathSegment;

import java.util.Arrays;

/**
 * mockplayer P15 native 冒烟：直接加载编译产物，验证：
 * 1. 加载 + newContext + insertChunkData + pathFind 全链路可用；
 * 2. src/dst 在固体方块内（原实现 findAir exit(1) 杀 JVM）→ 必须优雅返回 null 或路径，不崩。
 * 用法：java NativeSmoke（类路径含 nether-pathfinder jar / merged mod jar——
 * NetherPathfinder 静态块从 natives.zip.xz 解压并按平台 System.load）
 */
public class NativeSmoke {
    public static void main(String[] args) {
        long ctx = NetherPathfinder.newContext(42, null, NetherPathfinder.DIMENSION_OVERWORLD, 384, false);
        System.out.println("context=" + ctx);
        if (!NetherPathfinder.isThisSystemSupported()) {
            System.err.println("FAIL: native library did not load");
            System.exit(3);
        }

        // chunk (0,0) 全固体（最恶劣输入：src/dst 都在实体方块内）
        boolean[] solid = new boolean[16 * 16 * 384];
        Arrays.fill(solid, true);
        NetherPathfinder.insertChunkData(ctx, 0, 0, solid);
        PathSegment seg = NetherPathfinder.pathFind(ctx,
                8, 200, 8, 8, 200, 120, true, false, 10000, true, 8.0);
        System.out.println("solid->solid: " + (seg == null ? "null (graceful)" : "path len=" + seg.packed.length));
        if (seg != null) {
            // 固体区寻路不应给出非法路径，但至少不崩；记录长度即可
            System.out.println("  (unexpected path in solid-only world, len=" + seg.packed.length + ")");
        }

        // chunk (0,0) 全空气：正常路径
        boolean[] air = new boolean[16 * 16 * 384];
        NetherPathfinder.insertChunkData(ctx, 0, 0, air);
        seg = NetherPathfinder.pathFind(ctx,
                8, 200, 8, 8, 200, 120, true, false, 10000, true, 8.0);
        System.out.println("air->air: " + (seg == null ? "null" : "path len=" + seg.packed.length + " finished=" + seg.finished));
        if (seg == null || seg.packed.length == 0) {
            System.err.println("FAIL: air world should produce a path");
            System.exit(2);
        }

        NetherPathfinder.freeContext(ctx);
        System.out.println("SMOKE OK");
    }
}
