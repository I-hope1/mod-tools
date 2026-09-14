package hope.magic.example;

import hope.magic.js.runtime.JSArray;
import hope.magic.js.runtime.JSContext;
import hope.magic.js.runtime.JSObject;
import hope.magic.js.runtime.JSShape;

import java.util.*;

public class ShapeMegamorphicAnalysis {

    public static void main(String[] args) throws Throwable {
        System.out.println("=== GradientShapeBenchmark 64 态 Shape 分布与 Cache 冲突实测分析 ===");

        JSContext ctx = new JSContext();
        String initCode = GradientShapeBenchmark.generateSetupCode(64);
        ctx.eval(initCode);

        JSArray pool = (JSArray) ctx.eval("pool_64;");
        System.out.println("pool_64 长度: " + pool.length());

        List<JSShape> shapes = new ArrayList<>();
        int[] shapeIds = new int[64];
        for (int i = 0; i < 64; i++) {
            JSObject obj = (JSObject) pool.getElement(i);
            shapes.add(obj.shape);
            shapeIds[i] = obj.shape.id;
        }

        System.out.println("64 个对象的 Shape ID 范围: min=" + Arrays.stream(shapeIds).min().orElse(0) 
                + ", max=" + Arrays.stream(shapeIds).max().orElse(0));
        System.out.println("Shape IDs: " + Arrays.toString(shapeIds));

        // 分析不同 Cache 大小下的槽位分布与命中率
        int[] testSizes = {64, 128, 256, 512, 1024};
        for (int cacheSize : testSizes) {
            analyzeCacheSize(shapes, cacheSize);
        }

        analyze2WayAssociative(shapes, 64);

        // 真实引擎执行 (编译并调用 2000 次属性访问函数)
        System.out.println("\n=== 真实引擎 (Invokedynamic + ChainedCallSite) 运行时实测 ===");
        String accessCode = GradientShapeBenchmark.generatePureAccessCode(64);
        hope.magic.js.runtime.JSFunction func = (hope.magic.js.runtime.JSFunction) ctx.eval("(function() {\n" + accessCode + "\n})");
        
        hope.magic.js.runtime.ChainedCallSite.STATS_HITS.reset();
        hope.magic.js.runtime.ChainedCallSite.STATS_MISSES.reset();

        double res = func.call0Double(ctx);
        long hits = hope.magic.js.runtime.ChainedCallSite.STATS_HITS.sum();
        long misses = hope.magic.js.runtime.ChainedCallSite.STATS_MISSES.sum();
        System.out.println("函数返回值: " + res + " (预期: 83616.0)");
        System.out.printf("引擎真实捕获计数 (当前 CACHE_SIZE=%d): Hits = %d, Misses = %d, 总 Megamorphic 访问 = %d\n",
                hope.magic.js.runtime.ChainedCallSite.CACHE_SIZE, hits, misses, (hits + misses));
        if (hits + misses > 0) {
            System.out.printf("实测命中率: %.2f%%, 实测未命中率: %.2f%%\n",
                    (double) hits / (hits + misses) * 100, (double) misses / (hits + misses) * 100);
        }

        System.out.println("\n=== GraalJS 多态退化分析 (Truffle Specialization Statistics) ===");
        try (org.graalvm.polyglot.Context gCtx = org.graalvm.polyglot.Context.newBuilder("js")
                .option("engine.TracePerformanceWarnings", "all")
                .option("engine.TraceCompilationPolymorphism", "true")
                .allowAllAccess(true)
                .build()) {
            System.out.println("--- 运行 Shape=4 预热 ---");
            gCtx.eval("js", GradientShapeBenchmark.generateSetupCode(4));
            org.graalvm.polyglot.Value f4 = gCtx.eval("js", "(function() {\n" + GradientShapeBenchmark.generatePureAccessCode(4) + "\n})");
            for (int i = 0; i < 2000; i++) f4.execute();

            System.out.println("--- 运行 Shape=8 预热 ---");
            gCtx.eval("js", GradientShapeBenchmark.generateSetupCode(8));
            org.graalvm.polyglot.Value f8 = gCtx.eval("js", "(function() {\n" + GradientShapeBenchmark.generatePureAccessCode(8) + "\n})");
            for (int i = 0; i < 2000; i++) f8.execute();
        } catch (Throwable t) {
            System.out.println("GraalJS SpecializationStatistics 异常: " + t.getMessage());
        }
    }

    private static void analyzeCacheSize(List<JSShape> shapes, int cacheSize) {
        int shift = 32 - Integer.numberOfTrailingZeros(cacheSize);
        int phi = 0x9E3779B9;

        // 槽位统计
        Map<Integer, List<Integer>> slotToShapes = new HashMap<>();
        for (int i = 0; i < shapes.size(); i++) {
            int shapeId = shapes.get(i).id;
            int slot = (shapeId * phi) >>> shift;
            slotToShapes.computeIfAbsent(slot, k -> new ArrayList<>()).add(i);
        }

        int occupiedSlots = slotToShapes.size();
        int singleOccupancy = 0;
        int multiOccupancy = 0;
        int maxCollisionsInSlot = 0;

        for (var entry : slotToShapes.entrySet()) {
            int count = entry.getValue().size();
            if (count == 1) {
                singleOccupancy++;
            } else {
                multiOccupancy++;
                if (cacheSize == 64) {
                    System.out.printf("  槽位 %2d 冲突 Shapes: %s (IDs: %s, val offsets: %s)\n",
                            entry.getKey(),
                            entry.getValue(),
                            entry.getValue().stream().map(idx -> shapes.get(idx).id).toList(),
                            entry.getValue().stream().map(idx -> shapes.get(idx).getOffset("val")).toList()
                    );
                }
            }
            maxCollisionsInSlot = Math.max(maxCollisionsInSlot, count);
        }

        // 模拟 2000 次按循环访问 (i % 64) 的真实命中/未命中
        long[] directCache = new long[cacheSize];
        int hits = 0;
        int misses = 0;
        int totalAccesses = 2000;

        for (int i = 0; i < totalAccesses; i++) {
            int shapeIdx = i % 64;
            JSShape s = shapes.get(shapeIdx);
            int slot = (s.id * phi) >>> shift;

            long entry = directCache[slot];
            if (entry != 0L && (int)(entry >>> 32) == s.id) {
                hits++;
            } else {
                misses++;
                // 写入缓存 (高32位 shape.id, 低32位 offset)
                directCache[slot] = ((long) s.id << 32) | (shapeIdx & 0xFFFFFFFFL);
            }
        }

        double hitRate = (double) hits / totalAccesses * 100.0;
        double missRate = (double) misses / totalAccesses * 100.0;

        System.out.printf("\n--- Cache 大小 = %d (shift = %d) ---\n", cacheSize, shift);
        System.out.printf("非空槽位: %d / %d (%.1f%%)\n", occupiedSlots, cacheSize, (double) occupiedSlots / cacheSize * 100);
        System.out.printf("独占槽位数(单Shape): %d\n", singleOccupancy);
        System.out.printf("冲突槽位数(>=2 Shape): %d, 最大槽位冲突数: %d\n", multiOccupancy, maxCollisionsInSlot);
        System.out.printf("2000 次访问实测: Hits = %d, Misses = %d, Hit Rate = %.2f%%, Miss Rate = %.2f%%\n",
                hits, misses, hitRate, missRate);
    }

    private static void analyze2WayAssociative(List<JSShape> shapes, int numSets) {
        int shift = 32 - Integer.numberOfTrailingZeros(numSets);
        int phi = 0x9E3779B9;

        long[] cache = new long[numSets * 2]; // 每个 Set 2 个槽
        int hits = 0;
        int misses = 0;
        int hitsWay0 = 0;
        int hitsWay1 = 0;
        int totalAccesses = 2000;

        for (int i = 0; i < totalAccesses; i++) {
            int shapeIdx = i % 64;
            JSShape s = shapes.get(shapeIdx);
            int set = (s.id * phi) >>> shift;
            int base = set << 1;

            long e0 = cache[base];
            if (e0 != 0L && (int)(e0 >>> 32) == s.id) {
                hits++;
                hitsWay0++;
                continue;
            }

            long e1 = cache[base + 1];
            if (e1 != 0L && (int)(e1 >>> 32) == s.id) {
                hits++;
                hitsWay1++;
                continue;
            }

            // Miss: 写入槽位
            misses++;
            long newEntry = ((long) s.id << 32) | (shapeIdx & 0xFFFFFFFFL);
            if (e0 == 0L) {
                cache[base] = newEntry;
            } else if (e1 == 0L) {
                cache[base + 1] = newEntry;
            } else {
                // 两个槽都有数据，踢掉 e1（或者简易 LRU）
                cache[base + 1] = newEntry;
            }
        }

        double hitRate = (double) hits / totalAccesses * 100.0;
        double missRate = (double) misses / totalAccesses * 100.0;

        System.out.printf("\n--- 2-Way 组相联 (Sets = %d, 总槽位 = %d) ---\n", numSets, numSets * 2);
        System.out.printf("2000 次访问实测: Hits = %d (Way0: %d, Way1: %d), Misses = %d, Hit Rate = %.2f%%, Miss Rate = %.2f%%\n",
                hits, hitsWay0, hitsWay1, misses, hitRate, missRate);
    }
}

