package hope.magic.example;

import hope.magic.js.runtime.IntObjectMap;
import hope.magic.js.runtime.JSShape;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.info.GraphLayout;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.StampedLock;

/**
 * 深入对比 Shape 分支迁移在不同并发与不可变数据结构下的读写吞吐与内存足迹：
 * 1. COW_IntObjectMap: 原始类型无装箱 + 单分支快路径 + COW 拷贝更新 (当前方案)
 * 2. ConcurrentHashMap: JDK 标准并发无锁哈希表 (包含 Integer 装箱与 Node 开销)
 * 3. SegmentedStampedLock: 基于 StampedLock 乐观读/分段锁保护的单实例 IntObjectMap
 * 4. ImmutableCompactArray: 持久化紧凑平行数组 (无哈希、无装箱、线性扫描)
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class MultiTransitionStrategyBenchmark {

    // --- 1. COW IntObjectMap 策略 ---
    static final class CowStrategy {
        volatile int singleKey = -1;
        volatile JSShape singleTransition;
        volatile IntObjectMap<JSShape> multiTransitions;

        public JSShape get(int key) {
            JSShape trans;
            if (singleKey == key && (trans = singleTransition) != null) {
                return trans;
            }
            IntObjectMap<JSShape> multi = multiTransitions;
            if (multi != null) {
                return multi.get(key);
            }
            return null;
        }

        public synchronized void put(int key, JSShape next) {
            if (singleTransition == null && multiTransitions == null) {
                singleTransition = next;
                singleKey = key;
                return;
            }
            IntObjectMap<JSShape> map;
            if (multiTransitions == null) {
                map = new IntObjectMap<>();
                map.put(singleKey, singleTransition);
            } else {
                map = multiTransitions.copy();
            }
            map.put(key, next);
            multiTransitions = map;
        }
    }

    // --- 2. ConcurrentHashMap 策略 ---
    static final class ChmStrategy {
        volatile int singleKey = -1;
        volatile JSShape singleTransition;
        final ConcurrentHashMap<Integer, JSShape> multiTransitions = new ConcurrentHashMap<>(8);

        public JSShape get(int key) {
            JSShape trans;
            if (singleKey == key && (trans = singleTransition) != null) {
                return trans;
            }
            return multiTransitions.get(key); // Integer autoboxing
        }

        public void put(int key, JSShape next) {
            if (singleTransition == null && multiTransitions.isEmpty()) {
                singleTransition = next;
                singleKey = key;
                return;
            }
            if (multiTransitions.isEmpty() && singleTransition != null) {
                multiTransitions.put(singleKey, singleTransition);
            }
            multiTransitions.put(key, next);
        }
    }

    // --- 3. StampedLock 乐观读策略 (分段/乐观锁) ---
    static final class StampedLockStrategy {
        volatile int singleKey = -1;
        volatile JSShape singleTransition;
        final IntObjectMap<JSShape> multiTransitions = new IntObjectMap<>();
        final StampedLock lock = new StampedLock();

        public JSShape get(int key) {
            JSShape trans;
            if (singleKey == key && (trans = singleTransition) != null) {
                return trans;
            }
            long stamp = lock.tryOptimisticRead();
            JSShape next = multiTransitions.get(key);
            if (!lock.validate(stamp)) {
                stamp = lock.readLock();
                try {
                    next = multiTransitions.get(key);
                } finally {
                    lock.unlockRead(stamp);
                }
            }
            return next;
        }

        public void put(int key, JSShape next) {
            long stamp = lock.writeLock();
            try {
                if (singleTransition == null && multiTransitions.isEmpty()) {
                    singleTransition = next;
                    singleKey = key;
                    return;
                }
                if (multiTransitions.isEmpty() && singleTransition != null) {
                    multiTransitions.put(singleKey, singleTransition);
                }
                multiTransitions.put(key, next);
            } finally {
                lock.unlockWrite(stamp);
            }
        }
    }

    // --- 4. ImmutableCompactArray (持久化紧凑数组策略) ---
    static final class CompactArrayStrategy {
        volatile int singleKey = -1;
        volatile JSShape singleTransition;
        volatile int[] keys;
        volatile JSShape[] values;

        public JSShape get(int key) {
            JSShape trans;
            if (singleKey == key && (trans = singleTransition) != null) {
                return trans;
            }
            int[] k = keys;
            if (k != null) {
                for (int i = 0; i < k.length; i++) {
                    if (k[i] == key) return values[i];
                }
            }
            return null;
        }

        public synchronized void put(int key, JSShape next) {
            if (singleTransition == null && keys == null) {
                singleTransition = next;
                singleKey = key;
                return;
            }
            int[] oldK = keys;
            JSShape[] oldV = values;
            if (oldK == null) {
                keys = new int[]{singleKey, key};
                values = new JSShape[]{singleTransition, next};
            } else {
                int len = oldK.length;
                int[] newK = Arrays.copyOf(oldK, len + 1);
                JSShape[] newV = Arrays.copyOf(oldV, len + 1);
                newK[len] = key;
                newV[len] = next;
                keys = newK;
                values = newV;
            }
        }
    }

    private CowStrategy cow;
    private ChmStrategy chm;
    private StampedLockStrategy stamped;
    private CompactArrayStrategy compact;

    private static final int BRANCH_COUNT = 8;
    private static final int[] KEYS = new int[BRANCH_COUNT];
    private static final JSShape[] SHAPES = new JSShape[BRANCH_COUNT];

    static {
        for (int i = 0; i < BRANCH_COUNT; i++) {
            KEYS[i] = 1000 + i * 31;
            SHAPES[i] = JSShape.ROOT.addProperty("prop_" + i, JSShape.TYPE_DOUBLE);
        }
    }

    @Setup(Level.Trial)
    public void setup() {
        cow = new CowStrategy();
        chm = new ChmStrategy();
        stamped = new StampedLockStrategy();
        compact = new CompactArrayStrategy();

        for (int i = 0; i < BRANCH_COUNT; i++) {
            cow.put(KEYS[i], SHAPES[i]);
            chm.put(KEYS[i], SHAPES[i]);
            stamped.put(KEYS[i], SHAPES[i]);
            compact.put(KEYS[i], SHAPES[i]);
        }
    }

    // --- Benchmark 1: 单分支快路径命中 (singleKey) ---
    @Benchmark
    public JSShape test_01_single_hit_cow() {
        return cow.get(KEYS[0]);
    }

    @Benchmark
    public JSShape test_01_single_hit_chm() {
        return chm.get(KEYS[0]);
    }

    @Benchmark
    public JSShape test_01_single_hit_stamped() {
        return stamped.get(KEYS[0]);
    }

    @Benchmark
    public JSShape test_01_single_hit_compact() {
        return compact.get(KEYS[0]);
    }

    // --- Benchmark 2: 多分支命中 (第 2 条分支，命中 multi) ---
    @Benchmark
    public JSShape test_02_multi_branch_cow() {
        return cow.get(KEYS[2]);
    }

    @Benchmark
    public JSShape test_02_multi_branch_chm() {
        return chm.get(KEYS[2]);
    }

    @Benchmark
    public JSShape test_02_multi_branch_stamped() {
        return stamped.get(KEYS[2]);
    }

    @Benchmark
    public JSShape test_02_multi_branch_compact() {
        return compact.get(KEYS[2]);
    }

    // --- Benchmark 3: 循环扫描 8 个不同分支 (模拟异构对象创建) ---
    @Benchmark
    public void test_03_all_branches_cow(Blackhole bh) {
        for (int k : KEYS) bh.consume(cow.get(k));
    }

    @Benchmark
    public void test_03_all_branches_chm(Blackhole bh) {
        for (int k : KEYS) bh.consume(chm.get(k));
    }

    @Benchmark
    public void test_03_all_branches_stamped(Blackhole bh) {
        for (int k : KEYS) bh.consume(stamped.get(k));
    }

    @Benchmark
    public void test_03_all_branches_compact(Blackhole bh) {
        for (int k : KEYS) bh.consume(compact.get(k));
    }

    // --- JOL 内存足迹与独立主函数 ---
    public static void main(String[] args) {
        MultiTransitionStrategyBenchmark bench = new MultiTransitionStrategyBenchmark();
        bench.setup();

        System.out.println("================ 8 分支各策略物理内存占用对比 (OpenJDK JOL) ================");
        System.out.printf("1. COW IntObjectMap     总浅堆+深堆大小: %d 字节\n", GraphLayout.parseInstance(bench.cow).totalSize());
        System.out.printf("2. ConcurrentHashMap   总浅堆+深堆大小: %d 字节\n", GraphLayout.parseInstance(bench.chm).totalSize());
        System.out.printf("3. StampedLock (分段锁) 总浅堆+深堆大小: %d 字节\n", GraphLayout.parseInstance(bench.stamped).totalSize());
        System.out.printf("4. CompactArray (紧凑数组)总浅堆+深堆大小: %d 字节\n", GraphLayout.parseInstance(bench.compact).totalSize());
        System.out.println("============================================================================");
    }
}
