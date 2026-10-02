package nipx.uihook;

import arc.Core;
import arc.scene.Element;
import arc.scene.ui.*;
import arc.scene.ui.Label;
import arc.scene.ui.layout.*;
import arc.scene.ui.layout.Stack;
import arc.struct.Seq;
import arc.util.pooling.Pools;
import mindustry.Vars;
import nipx.Injector;
import org.objectweb.asm.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.lang.invoke.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;

import static nipx.AnnotationTransformer.internalName;
import static nipx.HotSwapAgent.*;
import static nipx.uihook.ArcReflectionAdapter.*;
import static org.objectweb.asm.Opcodes.*;

/** Cell 属性及子元素追踪器 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class CellPropertyRef {

    public static final String CL_TABLE = "arc/scene/ui/layout/Table";
    public static final String CL_CELL  = "arc/scene/ui/layout/Cell";

    //region 数据结构
    public record PropertyCall(String method, String desc, Object[] args, int line) { }

    public record CellIdentity(String hostClass, String hostMethod, String hostDesc,
                               int line, String creatorName) {
        @Override
        public String toString() {
            return hostClass + "#" + hostMethod + "@" + line + "(" + creatorName + ")";
        }
    }

    public record LambdaInfo(String ownerClass, String methodName, String methodDesc, Object[] captures) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LambdaInfo that)) return false;
            return ownerClass.equals(that.ownerClass)
                && methodName.equals(that.methodName)
                && methodDesc.equals(that.methodDesc)
                && Arrays.equals(captures, that.captures);
        }
        @Override
        public int hashCode() {
            int result = Objects.hash(ownerClass, methodName, methodDesc);
            result = 31 * result + Arrays.hashCode(captures);
            return result;
        }
        @Override
        public String toString() { return "Lambda[" + methodName + "]"; }
    }

    private record HostFrame(String hostClass, String hostMethod, String hostDesc,
                             int line, String creatorName) { }

    private record CellEntry(Cell<?> cell, CellIdentity id, int chainIdx, List<PropertyCall> chain) { }

    private record MethodExtraction(boolean analyzed, List<List<PropertyCall>> chains) { }

    private record ChainMatch(int index, List<PropertyCall> chain) { }

    private static final ChainMatch NO_CHAIN = new ChainMatch(-1, null);

    private static final class CellState {
        CellIdentity id;
        int chainIdx;
        List<PropertyCall> chain;
        final Map<String, Integer> slotCursor = new HashMap<>();
        CellState(CellIdentity id, int chainIdx, List<PropertyCall> chain) {
            this.id = id;
            this.chainIdx = chainIdx;
            this.chain = chain;
        }
    }

    private static final CellState IGNORED_STATE = new CellState(
        new CellIdentity("", "", "", -1, null), -1, new ArrayList<>());
    //endregion

    //region 全局状态
    private static final ThreadLocal<boolean[]> BUSY = ThreadLocal.withInitial(() -> new boolean[1]);
    private static final AtomicInteger FAILURES = new AtomicInteger();
    private static final int MAX_FAILURES = 20;
    private static volatile Thread uiThread;

    private static final Map<Cell<?>, CellState> cellState = new WeakHashMap<>();
    private static final Map<CellIdentity, Set<Cell<?>>> idToCells = new HashMap<>();
    private static final Map<String, LinkedHashSet<CellIdentity>> classToCells = new HashMap<>();
    private static final Map<String, Map<String, List<List<PropertyCall>>>> chainCache = new HashMap<>();
    private static final Map<CellIdentity, ChainMatch> templateCache = new HashMap<>();

    private static volatile boolean enabled = false;
    private static volatile byte[] originalCellBytes;

    private static final StackWalker WALKER = StackWalker.getInstance();

    private static <T> Set<T> newWeakSet() {
        return Collections.newSetFromMap(new WeakHashMap<>());
    }

    private static boolean offUiThread() {
        return uiThread != null && Thread.currentThread() != uiThread;
    }
    //endregion

    //region 钩子入口

    public static void onCellBound(Cell<?> cell) {
        if (!enabled || cell == null) return;
        if (Thread.currentThread() != uiThread) return;

        boolean[] busy = BUSY.get();
        if (busy[0]) return;
        busy[0] = true;
        try {
            onCellBoundImpl(cell);
        } catch (Throwable t) {
            if (FAILURES.incrementAndGet() > MAX_FAILURES) {
                error("[CellProperty] Circuit breaker tripped in onCellBound", t);
                disable();
            } else if (DEBUG) {
                error("[CellProperty] onCellBound failed", t);
            }
        } finally {
            busy[0] = false;
        }
    }

    private static void onCellBoundImpl(Cell<?> cell) {
        removeCell(cell);

        HostFrame hf = findHostContext();
        if (hf == null) {
            synchronized (cellState) {
                cellState.put(cell, IGNORED_STATE);
            }
            return;
        }

        CellIdentity id = new CellIdentity(hf.hostClass, hf.hostMethod, hf.hostDesc,
                                           hf.line, hf.creatorName);
        ChainMatch match = findChainMatch(id);
        int chainIdx = match == null ? -1 : match.index();
        List<PropertyCall> chain = (match == null || match.chain() == null)
            ? new ArrayList<>() : new ArrayList<>(match.chain());

        synchronized (cellState) {
            cellState.put(cell, new CellState(id, chainIdx, chain));
            registerCellLocked(id, cell);
        }

        if (DEBUG) log("[CellProperty] Bound: " + id + " idx=" + chainIdx);
    }

    private static void registerCellLocked(CellIdentity id, Cell<?> cell) {
        if (id == IGNORED_STATE.id) return;
        idToCells.computeIfAbsent(id, k -> newWeakSet()).add(cell);
        classToCells.computeIfAbsent(id.hostClass, k -> new LinkedHashSet<>()).add(id);
    }

    private static void unregisterCellLocked(CellIdentity id, Cell<?> cell) {
        if (id == IGNORED_STATE.id) return;
        Set<Cell<?>> s = idToCells.get(id);
        if (s != null) {
            s.remove(cell);
            if (s.isEmpty()) {
                idToCells.remove(id);
                LinkedHashSet<CellIdentity> set = classToCells.get(id.hostClass);
                if (set != null) set.remove(id);
            }
        }
    }

    public static void onCellFreed(Cell<?> cell) {
        if (!enabled || cell == null) return;
        if (Thread.currentThread() != uiThread) return;

        boolean[] busy = BUSY.get();
        if (busy[0]) return;
        busy[0] = true;
        try {
            removeCell(cell);
        } catch (Throwable t) {
            if (DEBUG) log("[CellProperty] onCellFreed failed: " + t.getMessage());
        } finally {
            busy[0] = false;
        }
    }

    public static void recordPropertyCall(Cell<?> cell, String method, String desc, Object[] args) {
        if (!enabled || cell == null) return;
        if (Thread.currentThread() != uiThread) return;

        boolean[] busy = BUSY.get();
        if (busy[0]) return;
        busy[0] = true;
        try {
            recordPropertyCallImpl(cell, method, desc, args);
        } catch (Throwable t) {
            if (FAILURES.incrementAndGet() > MAX_FAILURES) {
                error("[CellProperty] Circuit breaker tripped, disabling", t);
                disable();
            } else if (DEBUG) {
                error("[CellProperty] recordPropertyCall failed (" + method + ")", t);
            }
        } finally {
            busy[0] = false;
        }
    }

    /** 同名同 desc 出现多次时按调用顺序填槽，不依赖"参数是否已填"。 */
    private static void recordPropertyCallImpl(Cell<?> cell, String method, String desc, Object[] args) {
        CellState state;
        synchronized (cellState) { state = cellState.get(cell); }
        if (state == null || state == IGNORED_STATE) return;

        List<PropertyCall> chain = state.chain;
        if (chain.isEmpty()) return;

        Object[] sanitized = sanitizeArgs(args);
        String key = method + desc;
        int cursor = state.slotCursor.getOrDefault(key, 0);
        int slot = findNthSlotByMethodDesc(chain, method, desc, cursor);
        if (slot < 0) {
            if (DEBUG) log("[CellProperty] Slot #" + cursor + " not found: " + method + desc);
            return;
        }
        state.slotCursor.put(key, cursor + 1);

        PropertyCall existing = chain.get(slot);
        Object[] merged = mergeArgs(existing.args, sanitized);
        if (!argsEqual(existing.args, merged)) {
            chain.set(slot, new PropertyCall(method, desc, merged, existing.line));
        }
    }
    //endregion

    //region 身份与链

    private static boolean isLibraryClass(String slashName) {
        return slashName.startsWith("arc/")
            || slashName.startsWith("java/")
            || slashName.startsWith("javax/")
            || slashName.startsWith("jdk/")
            || slashName.startsWith("sun/")
            || slashName.startsWith("kotlin/")
            || slashName.startsWith("mindustry/")
            || slashName.startsWith("rhino/")
            || slashName.startsWith("org/mozilla/")
            || slashName.startsWith("org/objectweb/");
    }

    private static HostFrame findHostContext() {
        return WALKER.walk(s -> {
            String creatorName = null;
            for (StackWalker.StackFrame f : (Iterable<StackWalker.StackFrame>) s::iterator) {
                String cls  = f.getClassName().replace('.', '/');
                String name = f.getMethodName();

                if (cls.equals(CL_CELL)
                    || cls.startsWith("arc/util/pooling/")
                    || cls.startsWith("nipx/")) continue;

                if (cls.equals(CL_TABLE)) {
                    if (TABLE_CELL_CREATORS.contains(name)) creatorName = name;
                    continue;
                }

                if (isLibraryClass(cls)) return null;

                return new HostFrame(cls, name, f.getDescriptor(), f.getLineNumber(), creatorName);
            }
            return null;
        });
    }

    /**
     * 找 id 对应的静态链。候选不唯一（同一行同一 creator、行号取不到时命中多条）
     * 视为"不确定"，返回 NO_CHAIN。负缓存。
     */
    private static ChainMatch findChainMatch(CellIdentity id) {
        ChainMatch cached = templateCache.get(id);
        if (cached != null) return cached == NO_CHAIN ? null : cached;

        Map<String, List<List<PropertyCall>>> chains =
            chainCache.computeIfAbsent(id.hostClass, CellPropertyRef::buildChainModel);

        List<List<PropertyCall>> methodChains = chains.get(methodKeyOf(id));
        if (methodChains == null) {
            templateCache.put(id, NO_CHAIN);
            return null;
        }

        ChainMatch found = null;
        int hits = 0;
        for (int i = 0; i < methodChains.size(); i++) {
            List<PropertyCall> chain = methodChains.get(i);
            if (chain.isEmpty()) continue;
            PropertyCall creator = chain.get(0);
            if (id.creatorName != null && !id.creatorName.equals(creator.method)) continue;
            if (id.line >= 0 && creator.line >= 0 && id.line != creator.line) continue;
            if (found == null) found = new ChainMatch(i, chain);
            hits++;
        }
        ChainMatch r = (hits == 1) ? found : NO_CHAIN;
        templateCache.put(id, r);
        return r == NO_CHAIN ? null : r;
    }

    private static Map<String, List<List<PropertyCall>>> buildChainModel(String slashName) {
        try {
            Class<?> hostClass = Class.forName(slashName.replace('/', '.'), false, Vars.mods.mainLoader());
            byte[] bytes = fetchCurrentBytecode(hostClass);
            return bytes == null ? Map.of() : extractCellChains(bytes);
        } catch (Throwable t) {
            error("[CellProperty] chain model build failed for " + slashName, t);
            return Map.of();
        }
    }

    private static String methodKeyOf(CellIdentity id) { return id.hostMethod + ":" + id.hostDesc; }

    private static String methodNameFromKey(String methodKey) {
        int colon = methodKey.indexOf(':');
        return colon < 0 ? methodKey : methodKey.substring(0, colon);
    }

    private static String methodDescFromKey(String methodKey) {
        int colon = methodKey.indexOf(':');
        return colon < 0 ? "()V" : methodKey.substring(colon + 1);
    }

    private static boolean isLambdaKey(String k) {
        return methodNameFromKey(k).startsWith("lambda$");
    }

    /** lambda$build$3 → "build"。 */
    private static String lambdaOwner(String k) {
        String n = methodNameFromKey(k);
        int a = n.indexOf('$');
        int b = n.lastIndexOf('$');
        return a >= 0 && a < b ? n.substring(a + 1, b) : "";
    }

    private static int lambdaOrdinal(String k) {
        String n = methodNameFromKey(k);
        try { return Integer.parseInt(n.substring(n.lastIndexOf('$') + 1)); }
        catch (Exception e) { return 0; }
    }

    private static int findNthSlotByMethodDesc(List<PropertyCall> chain, String method, String desc, int nth) {
        int count = 0;
        for (int i = 0; i < chain.size(); i++) {
            PropertyCall pc = chain.get(i);
            if (pc.method.equals(method) && pc.desc.equals(desc)) {
                if (count == nth) return i;
                count++;
            }
        }
        return -1;
    }

    private static Object[] mergeArgs(Object[] template, Object[] runtimeKnown) {
        int lt = template == null ? 0 : template.length;
        int lr = runtimeKnown == null ? 0 : runtimeKnown.length;
        if (lt == 0) return runtimeKnown;
        if (lr == 0) return template;
        if (lt != lr) return runtimeKnown;
        Object[] out = template.clone();
        for (int j = 0; j < out.length; j++) {
            if (out[j] == null && runtimeKnown[j] != null) out[j] = runtimeKnown[j];
        }
        return out;
    }

    private static List<PropertyCall> mergeTemplate(List<PropertyCall> template,
                                                    List<PropertyCall> runtimeKnown) {
        if (runtimeKnown == null || runtimeKnown.isEmpty()) return new ArrayList<>(template);

        Map<String, List<PropertyCall>> runtimeByKey = new HashMap<>();
        for (PropertyCall r : runtimeKnown) {
            runtimeByKey.computeIfAbsent(r.method + r.desc, k -> new ArrayList<>()).add(r);
        }

        Map<String, Integer> seen = new HashMap<>();
        List<PropertyCall> out = new ArrayList<>(template.size());
        for (PropertyCall t : template) {
            String key = t.method + t.desc;
            int nth = seen.merge(key, 1, Integer::sum) - 1;
            List<PropertyCall> list = runtimeByKey.get(key);
            PropertyCall r = (list != null && nth < list.size()) ? list.get(nth) : null;
            Object[] merged = r == null ? t.args : mergeArgs(t.args, r.args);
            out.add(new PropertyCall(t.method, t.desc, merged, t.line));
        }
        return out;
    }
    //endregion

    //region 热替换回调

    public static void afterRedefine(String slashName, byte[] newBytecode) {
        if (!enabled) return;
        Runnable work = () -> {
            boolean[] busy = BUSY.get();
            if (busy[0]) return;
            busy[0] = true;
            try {
                afterRedefinedImpl(slashName, newBytecode);
            } catch (Throwable t) {
                error("[CellProperty] afterRedefined failed for " + slashName, t);
            } finally {
                busy[0] = false;
            }
        };
        if (Thread.currentThread() == uiThread) work.run();
        else if (Core.app != null) Core.app.post(work);
        else work.run();
    }

    private static void afterRedefinedImpl(String slashName, byte[] newBytecode) {
        info("[CellProperty] Class redefined: " + slashName);

        Map<String, List<List<PropertyCall>>> oldModel = chainCache.get(slashName);
        if (oldModel == null) {
            if (DEBUG) log("[CellProperty] No old model for " + slashName);
            return;
        }

        Map<String, List<List<PropertyCall>>> newModel = extractCellChains(newBytecode);

        Map<String, String> methodPairs = matchMethods(oldModel, newModel);

        Map<String, Map<Integer, Integer>> chainAligns = new HashMap<>();
        Map<String, String> oldMkToNewMk = new HashMap<>();
        Set<String> matchedNew = new HashSet<>();

        for (Entry<String, List<List<PropertyCall>>> oe : oldModel.entrySet()) {
            String oldMk = oe.getKey();
            String newMk = methodPairs.get(oldMk);
            if (newMk == null) continue;
            List<List<PropertyCall>> newChains = newModel.get(newMk);
            if (newChains == null) continue;
            Map<Integer, Integer> align = alignChains(oe.getValue(), newChains);
            chainAligns.put(oldMk, align);
            oldMkToNewMk.put(oldMk, newMk);
            for (Entry<Integer, Integer> me : align.entrySet()) {
                matchedNew.add(newMk + "#" + me.getValue());
            }
        }

        List<CellEntry> entries = new ArrayList<>();
        synchronized (cellState) {
            for (Entry<Cell<?>, CellState> e : cellState.entrySet()) {
                Cell<?> c = e.getKey();
                if (c == null) continue;
                CellState st = e.getValue();
                if (st == IGNORED_STATE) continue;
                if (!st.id.hostClass.equals(slashName)) continue;
                entries.add(new CellEntry(c, st.id, st.chainIdx, st.chain));
            }
        }

        int updated = 0, removed = 0, added = 0, skipped = 0;
        Map<Cell<?>, CellIdentity> newIdOf = new IdentityHashMap<>();
        Map<Cell<?>, Integer> newIdxOf = new IdentityHashMap<>();
        Map<Cell<?>, List<PropertyCall>> newChainOf = new IdentityHashMap<>();

        for (CellEntry ce : entries) {
            Cell<?> cell = ce.cell;
            if (cell.getTable() == null) continue;

            String oldMk = methodKeyOf(ce.id);
            String newMk = oldMkToNewMk.get(oldMk);

            if (newMk == null) {
                // 方法未配对（lambda 名字漂移且相似度不足，或方法被删）→ 保持不动，坐标作废
                skipped++;
                continue;
            }

            List<List<PropertyCall>> newChains = newModel.get(newMk);
            if (newChains == null || newChains.isEmpty()) {
                // 方法存在但站点被删光 → 移除
                removeCellFromTable(cell);
                removed++;
                continue;
            }

            if (ce.chainIdx < 0) {
                // 绑定时就没链（不确定的站点），保持不动，坐标作废
                skipped++;
                continue;
            }

            Map<Integer, Integer> align = chainAligns.get(oldMk);
            Integer newIdx = align == null ? null : align.get(ce.chainIdx);

            if (newIdx == null) {
                // 站点在新模型里没有对应 → 移除
                removeCellFromTable(cell);
                removed++;
                continue;
            }

            List<PropertyCall> newTemplate = newChains.get(newIdx);
            List<PropertyCall> mergedNew = mergeTemplate(newTemplate, ce.chain);

            PropertyCall newCreator = mergedNew.get(0);
            CellIdentity newId = new CellIdentity(
                slashName,
                methodNameFromKey(newMk),
                methodDescFromKey(newMk),
                newCreator.line,
                newCreator.method);

            try {
                List<PropertyCall> effective = applyChainUpdate(cell, ce.chain, mergedNew);
                newIdOf.put(cell, newId);
                newIdxOf.put(cell, newIdx);
                newChainOf.put(cell, effective);
                if (effective == mergedNew) {
                    if (!callsEqual(ce.chain, mergedNew)) updated++;
                } else {
                    skipped++;
                }
            } catch (Throwable t) {
                error("[CellProperty] applyChainUpdate failed for " + ce.id, t);
                newIdOf.put(cell, newId);
                newIdxOf.put(cell, newIdx);
                newChainOf.put(cell, ce.chain);
                skipped++;
            }
        }

        chainCache.put(slashName, newModel);
        templateCache.keySet().removeIf(id -> id.hostClass.equals(slashName));

        synchronized (cellState) {
            idToCells.keySet().removeIf(id -> id.hostClass.equals(slashName));
            classToCells.remove(slashName);

            for (Entry<Cell<?>, CellState> e : cellState.entrySet()) {
                Cell<?> c = e.getKey();
                if (c == null) continue;
                CellState st = e.getValue();
                if (st == IGNORED_STATE) continue;
                if (!st.id.hostClass.equals(slashName)) continue;

                CellIdentity nid = newIdOf.get(c);
                if (nid != null) {
                    st.id = nid;
                    Integer ni = newIdxOf.get(c);
                    st.chainIdx = ni != null ? ni : -1;
                    List<PropertyCall> nc = newChainOf.get(c);
                    if (nc != null) st.chain = new ArrayList<>(nc);
                } else {
                    // 没有跟上新模型：坐标作废，只登记
                    st.chainIdx = -1;
                }
                registerCellLocked(st.id, c);
            }
        }

        List<CellIdentity> idsAfter = new ArrayList<>();
        synchronized (cellState) {
            LinkedHashSet<CellIdentity> set = classToCells.get(slashName);
            if (set != null) idsAfter.addAll(set);
        }

        for (Entry<String, List<List<PropertyCall>>> e : newModel.entrySet()) {
            String mk = e.getKey();
            List<List<PropertyCall>> chains = e.getValue();
            for (int j = 0; j < chains.size(); j++) {
                if (matchedNew.contains(mk + "#" + j)) continue;
                List<PropertyCall> chain = chains.get(j);
                if (chain.isEmpty()) continue;
                PropertyCall creator = chain.get(0);
                if (!isTableCellCreator(CL_TABLE, creator.method)) continue;
                try {
                    if (appendNewCell(slashName, mk, chain, idsAfter)) added++;
                } catch (Throwable t) {
                    error("[CellProperty] appendNewCell failed for " + mk, t);
                }
            }
        }

        info("[CellProperty] Updated: " + updated + ", Removed: " + removed
             + ", Added: " + added + ", Skipped: " + skipped + " for " + slashName);
    }

    /**
     * 方法级配对。LambdaAligner 已经跑过、lambda 名大概率已对齐；
     * 这里作为兜底：非 lambda 同 key 直接配；lambda 按相似度配对，空方法不参与。
     */
    private static Map<String, String> matchMethods(Map<String, List<List<PropertyCall>>> oldM,
                                                    Map<String, List<List<PropertyCall>>> newM) {
        record Pair(String o, String n, double s, int dist) { }

        Map<String, String> out = new HashMap<>();

        for (String k : oldM.keySet()) {
            if (!isLambdaKey(k) && newM.containsKey(k)) out.put(k, k);
        }

        List<Pair> pairs = new ArrayList<>();
        for (Entry<String, List<List<PropertyCall>>> oe : oldM.entrySet()) {
            String ok = oe.getKey();
            if (!isLambdaKey(ok) || oe.getValue().isEmpty()) continue;
            for (Entry<String, List<List<PropertyCall>>> ne : newM.entrySet()) {
                String nk = ne.getKey();
                if (!isLambdaKey(nk) || ne.getValue().isEmpty()) continue;
                if (!lambdaOwner(ok).equals(lambdaOwner(nk))) continue;
                if (!methodDescFromKey(ok).equals(methodDescFromKey(nk))) continue;
                double s = methodScore(oe.getValue(), ne.getValue());
                if (s >= 0.6) {
                    pairs.add(new Pair(ok, nk, s, Math.abs(lambdaOrdinal(ok) - lambdaOrdinal(nk))));
                }
            }
        }

        pairs.sort(Comparator
            .<Pair>comparingDouble(p -> -p.s())
            .thenComparingInt(Pair::dist)
            .thenComparing(Pair::o)
            .thenComparing(Pair::n));

        Set<String> used = new HashSet<>(out.values());
        for (Pair p : pairs) {
            if (!out.containsKey(p.o()) && used.add(p.n())) out.put(p.o(), p.n());
        }
        return out;
    }

    /** 链级相似度总和 / 两侧自身总和的最大值，范围 [0, 1]。 */
    private static double methodScore(List<List<PropertyCall>> olds, List<List<PropertyCall>> news) {
        double got = 0;
        for (Entry<Integer, Integer> e : alignChains(olds, news).entrySet()) {
            got += chainSimilarityStatic(olds.get(e.getKey()), news.get(e.getValue()));
        }
        return got / Math.max(selfScore(olds), selfScore(news));
    }

    private static int selfScore(List<List<PropertyCall>> cs) {
        int s = 0;
        for (List<PropertyCall> c : cs) s += chainSimilarityStatic(c, c);
        return Math.max(s, 1);
    }

    private static Map<Integer, Integer> alignChains(List<List<PropertyCall>> olds,
                                                     List<List<PropertyCall>> news) {
        int n = olds.size(), m = news.size();
        Map<Integer, Integer> out = new HashMap<>();
        if (n == 0 || m == 0) return out;

        int[][] score = new int[n][m];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                score[i][j] = chainSimilarityStatic(olds.get(i), news.get(j));
            }
        }

        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                int best = Math.max(dp[i + 1][j], dp[i][j + 1]);
                if (score[i][j] > 0) best = Math.max(best, dp[i + 1][j + 1] + score[i][j]);
                dp[i][j] = best;
            }
        }
        for (int i = 0, j = 0; i < n && j < m; ) {
            if (score[i][j] > 0 && dp[i][j] == dp[i + 1][j + 1] + score[i][j]) {
                out.put(i, j); i++; j++;
            } else if (dp[i][j] == dp[i + 1][j]) {
                i++;
            } else {
                j++;
            }
        }
        return out;
    }

    private static int chainSimilarityStatic(List<PropertyCall> oldChain, List<PropertyCall> newChain) {
        if (oldChain == null || oldChain.isEmpty() || newChain == null || newChain.isEmpty()) return 0;
        PropertyCall oc = oldChain.get(0);
        PropertyCall nc = newChain.get(0);
        if (!oc.method.equals(nc.method)) return 0;

        int s = 1;
        if (argsEqual(oc.args, nc.args)) s += 4;
        int min = Math.min(oldChain.size(), newChain.size());
        for (int k = 1; k < min; k++) {
            if (oldChain.get(k).method.equals(newChain.get(k).method)) s += 1;
        }
        return s;
    }

    private static boolean appendNewCell(String slashName, String methodKey,
                                         List<PropertyCall> newChain, List<CellIdentity> existingIds) throws Throwable {
        PropertyCall creator = newChain.get(0);

        if (!hasUsableArgs(creator)) {
            if (DEBUG) log("[CellProperty] Skipping append with unknown creator args: " + creator.method);
            return false;
        }

        CellIdentity beforeId = null, afterId = null;
        int beforeLine = Integer.MIN_VALUE, afterLine = Integer.MAX_VALUE;
        for (CellIdentity id : existingIds) {
            if (!methodKeyOf(id).equals(methodKey)) continue;
            if (id.line < 0) continue;
            if (id.line < creator.line && id.line > beforeLine) { beforeId = id; beforeLine = id.line; }
            if (id.line > creator.line && id.line < afterLine)  { afterId = id;  afterLine = id.line; }
        }

        Table targetTable = null;
        int insertCellIndex = -1;

        Cell<?> anchor = beforeId != null ? firstAliveCell(beforeId) : null;
        if (anchor != null && anchor.getTable() != null) {
            targetTable = anchor.getTable();
            insertCellIndex = targetTable.getCells().indexOf(anchor, true) + 1;
        } else {
            anchor = afterId != null ? firstAliveCell(afterId) : null;
            if (anchor != null && anchor.getTable() != null) {
                targetTable = anchor.getTable();
                insertCellIndex = targetTable.getCells().indexOf(anchor, true);
            }
        }

        if (targetTable == null) {
            for (CellIdentity id : existingIds) {
                if (!methodKeyOf(id).equals(methodKey)) continue;
                Cell<?> c = firstAliveCell(id);
                if (c != null && c.getTable() != null) {
                    targetTable = c.getTable();
                    insertCellIndex = targetTable.getCells().size;
                    break;
                }
            }
        }
        if (targetTable == null) return false;

        MethodType type = MethodType.fromMethodDescriptorString(creator.desc, Vars.mods.mainLoader());
        MethodHandle mh = findTableMethod(creator.method, type);
        if (mh == null) return false;

        Object[] converted = creator.args == null ? null : convertArgs(type, creator.args);
        ArcReflectionAdapter.clearImplicitEndRow(targetTable);

        Cell<?> newCell = invoke(mh, targetTable, converted);
        if (newCell == null) return false;

        CellIdentity newId = new CellIdentity(slashName,
            methodNameFromKey(methodKey), methodDescFromKey(methodKey),
            creator.line, creator.method);

        int newIdx = -1;
        Map<String, List<List<PropertyCall>>> chains = chainCache.get(slashName);
        if (chains != null) {
            List<List<PropertyCall>> methodChains = chains.get(methodKey);
            if (methodChains != null) {
                for (int i = 0; i < methodChains.size(); i++) {
                    if (methodChains.get(i) == newChain || callsEqual(methodChains.get(i), newChain)) {
                        newIdx = i; break;
                    }
                }
            }
        }

        synchronized (cellState) {
            cellState.put(newCell, new CellState(newId, newIdx, new ArrayList<>(newChain)));
            registerCellLocked(newId, newCell);
        }
        existingIds.add(newId);

        List<PropertyCall> props = new ArrayList<>(newChain.subList(1, newChain.size()));
        applyAllCalls(newCell, props);

        ArcReflectionAdapter.ensureTableRows(targetTable,
            ArcReflectionAdapter.getCellRow(newCell) + 1);
        targetTable.invalidate();

        if (insertCellIndex >= 0) {
            Seq<Cell> cells = targetTable.getCells();
            int currentIdx = cells.indexOf(newCell, true);
            if (currentIdx != -1 && insertCellIndex < cells.size - 1) {
                cells.remove(newCell, true);
                cells.insert(insertCellIndex, newCell);

                Element element = newCell.get();
                if (element != null) {
                    Seq<Element> children = targetTable.getChildren();
                    children.remove(element, true);
                    int elementInsertIndex = children.size;
                    for (int i = insertCellIndex + 1; i < cells.size; i++) {
                        Cell<?> nextCell = cells.get(i);
                        if (!nextCell.hasElement()) continue;
                        int idx = children.indexOf(nextCell.get(), true);
                        if (idx != -1) { elementInsertIndex = idx; break; }
                    }
                    children.insert(elementInsertIndex, element);
                }
            }
        }

        repairTableGrid(targetTable);
        Core.app.post(targetTable::invalidateHierarchy);
        return true;
    }

    private static Cell<?> firstAliveCell(CellIdentity id) {
        if (id == IGNORED_STATE.id) return null;
        synchronized (cellState) {
            Set<Cell<?>> s = idToCells.get(id);
            if (s == null) return null;
            for (Cell<?> c : s) {
                if (c != null) return c;
            }
        }
        return null;
    }
    //endregion

    //region 属性与子元素更新

    /**
     * 应用新链。
     * @return 该 Cell 现在实际对应的链（作为下一次热重载的旧基线）。
     *         - 全部应用成功：返回 newChain。
     *         - 部分应用（有未知参数）或 dryRun 失败：返回 baseline（creator 新 + 属性旧），
     *           下一次热重载会重新尝试。
     */
    private static List<PropertyCall> applyChainUpdate(Cell<?> cell, List<PropertyCall> oldChain,
                                                       List<PropertyCall> newChain) {
        if (oldChain == null || oldChain.isEmpty()) {
            if (!newChain.isEmpty() && isTableCellCreator(CL_TABLE, newChain.get(0).method)) {
                updateChildElement(cell, newChain.get(0));
            }
            List<PropertyCall> props = newChain.subList(newChain.isEmpty() ? 0 : 1, newChain.size());
            applyAllCalls(cell, props);
            return newChain;
        }

        PropertyCall oldCreator = oldChain.get(0);
        PropertyCall newCreator = newChain.get(0);

        boolean elementUpdated = false;
        if (isTableCellCreator(CL_TABLE, newCreator.method)) {
            if (!oldCreator.method.equals(newCreator.method)
                || !argsEqual(oldCreator.args, newCreator.args)) {
                updateChildElement(cell, newCreator);
                elementUpdated = true;
            }
        }

        List<PropertyCall> oldProps = new ArrayList<>(oldChain.subList(1, oldChain.size()));
        List<PropertyCall> newProps = new ArrayList<>(newChain.subList(1, newChain.size()));
        boolean propsChanged = !callsEqual(oldProps, newProps);

        if (!elementUpdated && !propsChanged) return newChain;

        List<PropertyCall> baseline = new ArrayList<>();
        baseline.add(newCreator);
        baseline.addAll(oldProps);

        boolean allUsable = true;
        for (PropertyCall p : newProps) {
            if (!hasUsableArgs(p)) { allUsable = false; break; }
        }

        if (!allUsable) {
            // 部分应用：只补"新增或改变且参数已知"的调用，不动旧属性
            for (PropertyCall p : newProps) {
                if (hasUsableArgs(p) && !containsCall(oldProps, p)) {
                    invokeCellMethod(cell, p.method, p.desc, p.args);
                }
            }
            return baseline;
        }

        if (!dryRunProperties(newProps)) return baseline;

        boolean touchesRow = chainTouchesRow(oldProps) || chainTouchesRow(newProps);
        boolean savedEndRow = !touchesRow && ArcReflectionAdapter.isEndRow(cell);

        resetCell(cell);
        applyAllCalls(cell, newProps);

        if (!touchesRow) ArcReflectionAdapter.setEndRow(cell, savedEndRow);
        return newChain;
    }

    private static boolean containsCall(List<PropertyCall> l, PropertyCall p) {
        for (PropertyCall c : l) {
            if (c.method.equals(p.method) && c.desc.equals(p.desc) && argsEqual(c.args, p.args)) return true;
        }
        return false;
    }

    private static boolean chainTouchesRow(List<PropertyCall> calls) {
        for (PropertyCall c : calls) {
            if ("row".equals(c.method)) return true;
        }
        return false;
    }

    private static void applyAllCalls(Cell<?> cell, List<PropertyCall> calls) {
        for (PropertyCall call : calls) {
            if (hasUsableArgs(call)) invokeCellMethod(cell, call.method, call.desc, call.args);
        }
    }

    private static boolean dryRunProperties(List<PropertyCall> calls) {
        Table dummyTable = new Table();
        Cell<?> dummyCell = dummyTable.add();
        try {
            for (PropertyCall call : calls) {
                if (!hasUsableArgs(call)) continue;
                invokeCellMethodOrThrow(dummyCell, call.method, call.desc, call.args);
            }
            return true;
        } catch (Throwable t) {
            if (DEBUG) log("[CellProperty] dry-run exception: " + t.getMessage());
            return false;
        }
    }

    private static void resetCell(Cell<?> cell) {
        cell.set(Cell.defaults());
        ArcReflectionAdapter.setEndRow(cell, false);
    }

    private static void updateChildElement(Cell<?> cell, PropertyCall creator) {
        Element oldElement = cell.get();

        if (oldElement != null) {
            if (("table".equals(creator.method) && oldElement instanceof Table)
                || ("pane".equals(creator.method) && oldElement instanceof ScrollPane)
                || ("stack".equals(creator.method) && oldElement instanceof Stack)) {
                if (DEBUG) log("[CellProperty] Skipping container replacement: " + creator.method);
                return;
            }
        }

        if (oldElement != null && creator.args != null && creator.args.length > 0
            && creator.args[0] instanceof String newText) {
            if (("label".equals(creator.method) || "add".equals(creator.method))
                && oldElement instanceof Label label) {
                label.setText(newText);
                return;
            }
            if (("button".equals(creator.method) || "textButton".equals(creator.method))
                && oldElement instanceof TextButton button) {
                button.setText(newText);
                return;
            }
        }

        if (!hasUsableArgs(creator)) {
            if (DEBUG) log("[CellProperty] Skipping rebuild with unknown args: " + creator.method);
            return;
        }

        Table table = cell.getTable();
        if (table == null) return;

        try {
            MethodType type = MethodType.fromMethodDescriptorString(creator.desc, Vars.mods.mainLoader());
            MethodHandle mh = findTableMethod(creator.method, type);
            if (mh == null) return;

            Table    dummyTable = new Table();
            Object[] converted  = creator.args == null ? null : convertArgs(type, creator.args);
            Cell<?> dummyCell = invoke(mh, dummyTable, converted);
            if (dummyCell == null || dummyCell.get() == null) return;

            Element newElement = dummyCell.get();
            BindCell bind = BindCell.of(cell);
            bind.replace(newElement, false);
            Pools.free(bind);
        } catch (Throwable e) {
            error("[CellProperty] Failed to update child element: " + creator.method, e);
        }
    }

    private static MethodHandle findTableMethod(String name, MethodType methodType) {
        try { return lookup().findVirtual(Table.class, name, methodType); }
        catch (Throwable ignored) { return null; }
    }

    private static boolean callsEqual(List<PropertyCall> a, List<PropertyCall> b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            PropertyCall ca = a.get(i), cb = b.get(i);
            if (!ca.method.equals(cb.method)) return false;
            if (!ca.desc.equals(cb.desc)) return false;
            if (!argsEqual(ca.args, cb.args)) return false;
        }
        return true;
    }

    private static boolean argsEqual(Object[] a, Object[] b) {
        int la = a == null ? 0 : a.length;
        int lb = b == null ? 0 : b.length;
        if (la != lb) return false;
        for (int i = 0; i < la; i++) {
            if (!Objects.equals(a[i], b[i])) return false;
        }
        return true;
    }

    private static boolean hasUsableArgs(PropertyCall call) {
        return hasUsableValue(call.args);
    }

    private static boolean hasUsableValue(Object[] arr) {
        if (arr == null) return true;
        for (Object a : arr) {
            if (a == null) return false;
            if (a instanceof LambdaInfo li && !hasUsableValue(li.captures())) return false;
        }
        return true;
    }

    private static void invokeCellMethod(Cell<?> cell, String methodName, String methodDesc, Object[] args) {
        try { invokeCellMethodOrThrow(cell, methodName, methodDesc, args); }
        catch (Throwable e) { error("[CellProperty] Failed to invoke " + methodName, e); }
    }

    private static void invokeCellMethodOrThrow(Cell<?> cell, String methodName,
                                                String methodDesc, Object[] args) throws Throwable {
        int len = args == null ? 0 : args.length;
        if ("row".equals(methodName) && len == 0) {
            ArcReflectionAdapter.setEndRow(cell, true);
            return;
        }
        MethodType type = MethodType.fromMethodDescriptorString(methodDesc, Vars.mods.mainLoader());
        MethodHandle mh = findMatchingMethod(methodName, type);
        if (mh == null) throw new NoSuchMethodException(methodName + "(" + len + " args)");

        Object[] converted = convertArgs(type, args);
        Class<?> receiverType = mh.type().parameterType(0);
        if (receiverType.isAssignableFrom(Cell.class)) {
            invoke(mh, cell, converted);
        } else if (receiverType.isAssignableFrom(Table.class)) {
            invoke(mh, cell.getTable(), converted);
        } else {
            throw new IllegalStateException("Unexpected receiver type: " + receiverType);
        }

        if ("colspan".equals(methodName)) {
            Table table = cell.getTable();
            if (table == null) return;
            ArcReflectionAdapter.recalculateColumns(table);
            if (cell.hasElement()) cell.get().invalidateHierarchy();
            table.invalidate();
            table.layout();
        }
    }

    private static MethodHandle findMatchingMethod(String name, MethodType methodType) {
        try { return lookup().findVirtual(Cell.class, name, methodType); }
        catch (Throwable ignored) { }
        try { return lookup().findVirtual(Table.class, name, methodType); }
        catch (Throwable ignored) { }
        return null;
    }

    private static Object[] convertArgs(MethodType methodType, Object[] args) {
        if (args == null) return null;
        Object[] result = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (i >= methodType.parameterCount()) { result[i] = arg; continue; }
            Class<?> target = methodType.parameterType(i);
            if (arg == null) {
                result[i] = target.isInterface() ? makeDummyProxy(target) : null;
                continue;
            }
            if (arg instanceof LambdaInfo li) {
                result[i] = makeLambda(li, target);
                continue;
            }
            result[i] = switch (target.getTypeName()) {
                case "void" -> arg;
                case "boolean" -> arg instanceof Number n ? n.intValue() != 0 : arg;
                case "byte" -> ((Number) arg).byteValue();
                case "char" -> arg instanceof Number n ? (char) n.intValue() : (char) arg;
                case "short" -> ((Number) arg).shortValue();
                case "int" -> ((Number) arg).intValue();
                case "long" -> ((Number) arg).longValue();
                case "float" -> ((Number) arg).floatValue();
                case "double" -> ((Number) arg).doubleValue();
                default -> CharSequence.class.isAssignableFrom(target) ? String.valueOf(arg) : arg;
            };
        }
        return result;
    }

    private static Object makeDummyProxy(Class<?> target) {
        return Proxy.newProxyInstance(target.getClassLoader(), new Class<?>[]{target},
            (proxy, method, methodArgs) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "toString" -> "DummyProxy[" + target.getSimpleName() + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == methodArgs[0];
                        default -> null;
                    };
                }
                return null;
            });
    }

    private static Object makeLambda(LambdaInfo li, Class<?> target) {
        if (!hasUsableValue(li.captures())) return makeDummyProxy(target);

        return Proxy.newProxyInstance(target.getClassLoader(), new Class<?>[]{target},
            (proxy, method, methodArgs) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "toString" -> "LambdaProxy[" + li.methodName() + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == methodArgs[0];
                        default -> null;
                    };
                }
                try {
                    Class<?> owner = loadClass(li.ownerClass().replace('/', '.'));
                    MethodType type = MethodType.fromMethodDescriptorString(
                        li.methodDesc(), owner.getClassLoader());
                    boolean isStatic;
                    MethodHandle handle;
                    try {
                        handle = lookup().findStatic(owner, li.methodName(), type);
                        isStatic = true;
                    } catch (Throwable e) {
                        handle = lookup().findSpecial(owner, li.methodName(), type, owner);
                        isStatic = false;
                    }
                    if (handle == null) return null;

                    Object[] allArgs = new Object[li.captures().length
                        + (methodArgs == null ? 0 : methodArgs.length)];
                    System.arraycopy(li.captures(), 0, allArgs, 0, li.captures().length);
                    if (methodArgs != null)
                        System.arraycopy(methodArgs, 0, allArgs, li.captures().length, methodArgs.length);

                    if (isStatic) return invoke(handle, allArgs);

                    Object instance = allArgs.length > 0 ? allArgs[0] : null;
                    if (instance == null) return null;
                    Object[] actual = new Object[Math.max(0, allArgs.length - 1)];
                    if (actual.length > 0)
                        System.arraycopy(allArgs, 1, actual, 0, actual.length);
                    return invoke(handle, instance, actual);
                } catch (Throwable t) {
                    error("[CellProperty] Lambda invocation failed", t);
                    return null;
                }
            });
    }
    //endregion

    //region 链移除

    private static void removeCellFromTable(Cell<?> cell) {
        Table table = cell.getTable();
        if (table != null) {
            try {
                BindCell bind = BindCell.of(cell);
                bind.remove();
                Pools.free(bind);
                table.getCells().remove(cell, true);
                Core.app.post(table::invalidateHierarchy);
            } catch (Throwable t) {
                error("[CellProperty] Failed to remove cell " + cell, t);
            }
        }
        removeCell(cell);
    }

    public static void removeCell(Cell<?> cell) {
        synchronized (cellState) {
            CellState st = cellState.remove(cell);
            if (st == null || st == IGNORED_STATE) return;
            unregisterCellLocked(st.id, cell);
        }
    }
    //endregion

    //region ASM 字节码分析

    private static final Set<String> CELL_PROPERTY_METHODS = new HashSet<>(Arrays.asList(
        "size", "width", "height",
        "minSize", "minWidth", "minHeight",
        "maxSize", "maxWidth", "maxHeight",
        "pad", "padTop", "padLeft", "padBottom", "padRight",
        "fill", "fillX", "fillY",
        "align", "center", "top", "left", "bottom", "right",
        "grow", "growX", "growY",
        "row",
        "expand", "expandX", "expandY",
        "colspan",
        "uniform", "uniformX", "uniformY",
        "color",
        "margin", "marginTop", "marginLeft", "marginBottom", "marginRight",
        "name", "disabled", "touchable", "visible", "scaling",
        "wrap", "ellipsis", "labelAlign", "fontScale",
        "scrollX", "scrollY", "maxTextLength", "valid",
        "tooltip", "style", "checked"
    ));

    private static final Set<String> TABLE_CELL_CREATORS = new HashSet<>(Arrays.asList(
        "add", "button", "image", "label", "textButton",
        "imageButton", "area", "table", "pane", "stack",
        "toggleButton", "imageTextButton", "checkBox", "slider",
        "textField", "selectBox", "list", "tree"
    ));

    public static Map<String, List<List<PropertyCall>>> extractCellChains(byte[] bytecode) {
        Map<String, List<List<PropertyCall>>> result = new HashMap<>();
        if (bytecode == null) return result;
        try {
            ClassReader cr = new ClassReader(bytecode);
            ClassNode   cn = new ClassNode();
            cr.accept(cn, ClassReader.SKIP_FRAMES);
            for (MethodNode mn : cn.methods) {
                MethodExtraction ex = extractFromMethod(cn, mn);
                if (ex.analyzed()) {
                    result.put(mn.name + ":" + mn.desc, ex.chains());
                }
            }
        } catch (Exception e) {
            error("[CellProperty] Failed to extract chains", e);
        }
        return result;
    }

    private static boolean isTableClass(ClassNode cn, String owner) {
        if (CL_TABLE.equals(owner)) return true;
        if (cn != null && owner.equals(cn.name)) {
            if (cn.superName != null && (cn.superName.equals(CL_TABLE) || cn.superName.contains("Table"))) return true;
        }
        return owner.endsWith("Table") || owner.contains("/Table");
    }

    private static boolean isCellClass(String owner) {
        if (CL_CELL.equals(owner)) return true;
        return owner.endsWith("Cell") || owner.contains("/Cell");
    }

    private static boolean isTableCellCreator(ClassNode cn, String owner, String name) {
        return isTableClass(cn, owner) && TABLE_CELL_CREATORS.contains(name);
    }

    private static boolean isTableCellCreator(String owner, String name) {
        return isTableCellCreator(null, owner, name);
    }

    private static boolean isCellProperty(ClassNode cn, String owner, String name, String desc) {
        if (isCellClass(owner) && CELL_PROPERTY_METHODS.contains(name)
            && (desc.endsWith(")L" + CL_CELL + ";") || "()V".equals(desc) && "row".equals(name))) {
            return true;
        }
        return isTableClass(cn, owner) && CELL_PROPERTY_METHODS.contains(name);
    }

    /** 先扫站点，扫不到就不 analyze（大多数方法没有 Cell 调用，这一步省掉 Analyzer 开销）。 */
    private static MethodExtraction extractFromMethod(ClassNode cn, MethodNode mn) {
        List<List<PropertyCall>> chains = new ArrayList<>();
        if (mn.instructions == null || mn.instructions.size() == 0)
            return new MethodExtraction(true, chains);

        List<MethodInsnNode> cellCallSites = new ArrayList<>();
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode mi)) continue;
            if (!returnsCell(mi)) continue;
            if (!isTableCellCreator(cn, mi.owner, mi.name)
                && !isCellProperty(cn, mi.owner, mi.name, mi.desc)) continue;
            cellCallSites.add(mi);
        }
        if (cellCallSites.isEmpty()) return new MethodExtraction(true, chains);

        Frame<SourceValue>[] frames;
        try {
            Analyzer<SourceValue> analyzer = new Analyzer<>(new SourceInterpreter());
            frames = analyzer.analyze(cn.name, mn);
        } catch (Throwable t) {
            if (DEBUG) log("[CellProperty] Analyzer failed for " + cn.name + "#" + mn.name + mn.desc);
            return new MethodExtraction(false, chains);
        }

        Map<MethodInsnNode, MethodInsnNode> creatorOf = new HashMap<>();
        for (MethodInsnNode site : cellCallSites) creatorOf.put(site, findChainHead(cn, mn, frames, site));

        Map<MethodInsnNode, List<MethodInsnNode>> byCreator = new LinkedHashMap<>();
        for (MethodInsnNode site : cellCallSites) {
            MethodInsnNode head = creatorOf.get(site);
            if (head == null) continue;
            if (!isTableCellCreator(cn, head.owner, head.name)) continue;
            byCreator.computeIfAbsent(head, k -> new ArrayList<>()).add(site);
        }

        for (Entry<MethodInsnNode, List<MethodInsnNode>> e : byCreator.entrySet()) {
            List<PropertyCall> chain = new ArrayList<>();
            for (MethodInsnNode site : e.getValue()) {
                Object[] args = extractArgsDataflow(cn, mn, frames, site);
                chain.add(new PropertyCall(site.name, site.desc, args, lineOf(mn, site)));
            }
            if (!chain.isEmpty()) chains.add(chain);
        }
        return new MethodExtraction(true, chains);
    }

    private static boolean returnsCell(MethodInsnNode mi) {
        if ("row".equals(mi.name) && "()V".equals(mi.desc)) return true;
        return mi.desc.endsWith(")L" + CL_CELL + ";");
    }

    private static MethodInsnNode findChainHead(ClassNode cn, MethodNode mn,
                                                Frame<SourceValue>[] frames, MethodInsnNode site) {
        Set<AbstractInsnNode> visited = new HashSet<>();
        MethodInsnNode curr = site;
        while (true) {
            if (!visited.add(curr)) return curr;
            SourceValue recv = receiverSource(frames, mn, curr);
            if (recv == null || recv.insns.size() != 1) return curr;
            AbstractInsnNode prev = recv.insns.iterator().next();
            if (!(prev instanceof MethodInsnNode prevCall)) return curr;
            if (!returnsCell(prevCall)) return curr;
            if (!isTableCellCreator(cn, prevCall.owner, prevCall.name)
                && !isCellProperty(cn, prevCall.owner, prevCall.name, prevCall.desc)) return curr;
            curr = prevCall;
        }
    }

    private static SourceValue receiverSource(Frame<SourceValue>[] frames, MethodNode mn,
                                              MethodInsnNode site) {
        int idx = mn.instructions.indexOf(site);
        if (idx < 0) return null;
        Frame<SourceValue> f = frames[idx];
        if (f == null) return null;
        int op = site.getOpcode();
        if (op == INVOKESTATIC || op == INVOKEDYNAMIC) return null;
        int argCount = Type.getArgumentTypes(site.desc).length;
        int base = f.getStackSize() - argCount - 1;
        if (base < 0) return null;
        return f.getStack(base);
    }

    private static Object[] extractArgsDataflow(ClassNode cn, MethodNode mn,
                                                 Frame<SourceValue>[] frames, MethodInsnNode site) {
        Type[] argTypes = Type.getArgumentTypes(site.desc);
        if (argTypes.length == 0) return null;

        int idx = mn.instructions.indexOf(site);
        if (idx < 0) return null;
        Frame<SourceValue> f = frames[idx];
        if (f == null) return null;

        int base = f.getStackSize() - argTypes.length;
        if (base < 0) return null;

        Object[] args = new Object[argTypes.length];
        Set<String> visiting = new HashSet<>();
        for (int i = 0; i < argTypes.length; i++) {
            Object v = resolveSourceValue(cn, mn, frames, f.getStack(base + i), visiting);
            args[i] = coerce(argTypes[i], sanitizeValue(v));
        }
        return args;
    }

    private static Object coerce(Type t, Object v) {
        if (!(v instanceof Number n)) return v;
        return switch (t.getSort()) {
            case Type.BOOLEAN -> n.intValue() != 0;
            case Type.CHAR    -> (char) n.intValue();
            case Type.BYTE    -> n.byteValue();
            case Type.SHORT   -> n.shortValue();
            case Type.INT     -> n.intValue();
            case Type.LONG    -> n.longValue();
            case Type.FLOAT   -> n.floatValue();
            case Type.DOUBLE  -> n.doubleValue();
            default -> v;
        };
    }

    private static Object resolveSourceValue(ClassNode cn, MethodNode mn,
                                              Frame<SourceValue>[] frames, SourceValue sv,
                                              Set<String> visiting) {
        if (sv == null || sv.insns.size() != 1) return null;
        return resolveInsnConstant(cn, mn, frames, sv.insns.iterator().next(), visiting);
    }

    private static Object operand(ClassNode cn, MethodNode mn,
                                  Frame<SourceValue>[] frames, AbstractInsnNode insn,
                                  int fromTop, Set<String> visiting) {
        int idx = mn.instructions.indexOf(insn);
        if (idx < 0) return null;
        Frame<SourceValue> f = frames[idx];
        if (f == null) return null;
        int pos = f.getStackSize() - 1 - fromTop;
        if (pos < 0) return null;
        return resolveSourceValue(cn, mn, frames, f.getStack(pos), visiting);
    }

    private static Object resolveInsnConstant(ClassNode cn, MethodNode mn,
                                              Frame<SourceValue>[] frames, AbstractInsnNode insn,
                                              Set<String> visiting) {
        if (insn == null) return null;

        if (insn instanceof FieldInsnNode fin && insn.getOpcode() == GETSTATIC) {
            try {
                if (!fin.owner.equals(cn.name)) return null;
                Class<?> clazz = Class.forName(fin.owner.replace('/', '.'), false, Vars.mods.mainLoader());
                Field field = clazz.getDeclaredField(fin.name);
                int m = field.getModifiers();
                if (!Modifier.isStatic(m) || !Modifier.isFinal(m)) return null;
                field.setAccessible(true);
                Object v = field.get(null);
                return isConstantType(v) ? v : null;
            } catch (Throwable ignored) { }
            return null;
        }

        if (insn instanceof LdcInsnNode ldc) return ldc.cst;

        if (insn instanceof InsnNode in) {
            int op = in.getOpcode();
            switch (op) {
                case ICONST_M1: return -1;
                case ICONST_0:  return 0;
                case ICONST_1:  return 1;
                case ICONST_2:  return 2;
                case ICONST_3:  return 3;
                case ICONST_4:  return 4;
                case ICONST_5:  return 5;
                case FCONST_0:  return 0.0f;
                case FCONST_1:  return 1.0f;
                case FCONST_2:  return 2.0f;
                case DCONST_0:  return 0.0d;
                case DCONST_1:  return 1.0d;
                case LCONST_0:  return 0L;
                case LCONST_1:  return 1L;
                case I2L: case I2F: case I2D:
                case L2I: case L2F: case L2D:
                case F2I: case F2L: case F2D:
                case D2I: case D2L: case D2F:
                case I2B: case I2C: case I2S:
                    return convertNumeric(op, operand(cn, mn, frames, insn, 0, visiting));
                case IADD: case ISUB: case IMUL: case IDIV:
                case LADD: case LSUB: case LMUL: case LDIV:
                case FADD: case FSUB: case FMUL: case FDIV:
                case DADD: case DSUB: case DMUL: case DDIV: {
                    Object right = operand(cn, mn, frames, insn, 0, visiting);
                    Object left  = operand(cn, mn, frames, insn, 1, visiting);
                    return evaluateBinaryOp(op, left, right);
                }
                case INEG: case LNEG: case FNEG: case DNEG:
                    return evaluateUnaryOp(op, operand(cn, mn, frames, insn, 0, visiting));
            }
        }

        if (insn instanceof IntInsnNode iin
            && (iin.getOpcode() == BIPUSH || iin.getOpcode() == SIPUSH)) return iin.operand;

        if (insn instanceof VarInsnNode vin) {
            int op = vin.getOpcode();
            if (op == ILOAD || op == FLOAD || op == LLOAD || op == DLOAD || op == ALOAD) {
                return resolveVar(cn, mn, frames, vin.var, visiting);
            }
        }

        if (insn instanceof InvokeDynamicInsnNode indy
            && indy.bsm != null
            && "java/lang/invoke/LambdaMetafactory".equals(indy.bsm.getOwner())
            && indy.bsmArgs != null && indy.bsmArgs.length >= 2
            && indy.bsmArgs[1] instanceof Handle handle) {

            Type[] captureTypes = Type.getArgumentTypes(indy.desc);
            Object[] captures = new Object[captureTypes.length];
            int idx = mn.instructions.indexOf(indy);
            Frame<SourceValue> f = idx >= 0 ? frames[idx] : null;
            if (f != null) {
                int base = f.getStackSize() - captureTypes.length;
                if (base >= 0) {
                    for (int i = 0; i < captureTypes.length; i++) {
                        Object v = resolveSourceValue(cn, mn, frames, f.getStack(base + i), visiting);
                        captures[i] = sanitizeValue(v);
                    }
                }
            }
            return new LambdaInfo(handle.getOwner(), handle.getName(), handle.getDesc(), captures);
        }

        return null;
    }

    private static Object convertNumeric(int op, Object v) {
        if (!(v instanceof Number n)) return null;
        return switch (op) {
            case I2L, F2L, D2L -> n.longValue();
            case I2F, L2F, D2F -> n.floatValue();
            case I2D, L2D, F2D -> n.doubleValue();
            case L2I, F2I, D2I -> n.intValue();
            case I2B -> n.byteValue();
            case I2C -> (char) n.intValue();
            case I2S -> n.shortValue();
            default -> null;
        };
    }

    private static Object resolveVar(ClassNode cn, MethodNode mn,
                                     Frame<SourceValue>[] frames, int varIndex,
                                     Set<String> visiting) {
        String visitKey = mn.name + ":" + mn.desc + "#" + varIndex;
        if (!visiting.add(visitKey)) return null;
        try {
            int writes = 0;
            AbstractInsnNode singleStore = null;
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof VarInsnNode vin) {
                    int op = vin.getOpcode();
                    if ((op == ISTORE || op == LSTORE || op == FSTORE || op == DSTORE || op == ASTORE)
                        && vin.var == varIndex) { writes++; singleStore = insn; }
                } else if (insn instanceof IincInsnNode iin && iin.var == varIndex) writes++;
            }
            if (writes == 1 && singleStore != null) {
                int idx = mn.instructions.indexOf(singleStore);
                if (idx >= 0) {
                    Frame<SourceValue> f = frames[idx];
                    if (f != null && f.getStackSize() > 0) {
                        Object v = resolveSourceValue(cn, mn, frames,
                            f.getStack(f.getStackSize() - 1), visiting);
                        if (isConstantType(v)) return v;
                    }
                }
            }
            return null;
        } finally {
            visiting.remove(visitKey);
        }
    }

    private static Object evaluateBinaryOp(int opcode, Object left, Object right) {
        if (left == null || right == null) return null;
        if (!(left instanceof Number l) || !(right instanceof Number r)) return null;
        return switch (opcode) {
            case IADD -> l.intValue() + r.intValue();
            case ISUB -> l.intValue() - r.intValue();
            case IMUL -> l.intValue() * r.intValue();
            case IDIV -> r.intValue() == 0 ? null : l.intValue() / r.intValue();
            case LADD -> l.longValue() + r.longValue();
            case LSUB -> l.longValue() - r.longValue();
            case LMUL -> l.longValue() * r.longValue();
            case LDIV -> r.longValue() == 0L ? null : l.longValue() / r.longValue();
            case FADD -> l.floatValue() + r.floatValue();
            case FSUB -> l.floatValue() - r.floatValue();
            case FMUL -> l.floatValue() * r.floatValue();
            case FDIV -> r.floatValue() == 0.0f ? null : l.floatValue() / r.floatValue();
            case DADD -> l.doubleValue() + r.doubleValue();
            case DSUB -> l.doubleValue() - r.doubleValue();
            case DMUL -> l.doubleValue() * r.doubleValue();
            case DDIV -> r.doubleValue() == 0.0d ? null : l.doubleValue() / r.doubleValue();
            default -> null;
        };
    }

    private static Object evaluateUnaryOp(int opcode, Object val) {
        if (!(val instanceof Number n)) return null;
        return switch (opcode) {
            case INEG -> -n.intValue();
            case LNEG -> -n.longValue();
            case FNEG -> -n.floatValue();
            case DNEG -> -n.doubleValue();
            default -> null;
        };
    }

    private static boolean isConstantType(Object val) {
        return val instanceof String || val instanceof Number
            || val instanceof Boolean || val instanceof Character;
    }

    private static Object sanitizeValue(Object v) {
        if (v == null) return null;
        if (isConstantType(v)) return v;
        if (v instanceof LambdaInfo li) {
            Object[] caps = li.captures();
            Object[] newCaps = caps == null ? null : sanitizeArgs(caps);
            return new LambdaInfo(li.ownerClass(), li.methodName(), li.methodDesc(), newCaps);
        }
        return null;
    }

    private static Object[] sanitizeArgs(Object[] args) {
        if (args == null) return null;
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) out[i] = sanitizeValue(args[i]);
        return out;
    }

    private static int lineOf(MethodNode mn, AbstractInsnNode insn) {
        for (AbstractInsnNode n = insn; n != null; n = n.getPrevious()) {
            if (n instanceof LineNumberNode lnn) return lnn.line;
        }
        return -1;
    }
    //endregion

    //region ASM 注入

    public static void redefineCellProperties() {
        Class<?> cellClass = Cell.class;
        byte[] bytes = fetchCurrentBytecode(cellClass);
        if (bytes == null) { error("[CellProperty] Cannot fetch Cell bytecode"); return; }

        if (originalCellBytes == null) {
            if (containsHook(bytes)) {
                error("[CellProperty] Cell already injected but no original bytes; aborting");
                return;
            }
            originalCellBytes = bytes;
        }

        byte[] injected = injectCell(originalCellBytes);
        if (injected == null) return;
        if (!verifyBytecode(injected)) {
            error("[CellProperty] Injected bytecode failed verification, aborting redefine");
            return;
        }

        Injector.redefineOneClass(cellClass, injected);
        info("[CellProperty] Cell class redefined with property tracking");
    }

    private static boolean containsHook(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1).contains("onCellBound");
    }

    private static boolean verifyBytecode(byte[] bytes) {
        try {
            ClassLoader parent = Cell.class.getClassLoader();
            ClassLoader verifier = new ClassLoader(parent) {
                Class<?> define(byte[] b) { return defineClass(null, b, 0, b.length); }
            };
            Method m = verifier.getClass().getDeclaredMethod("define", byte[].class);
            m.setAccessible(true);
            Class<?> c = (Class<?>) m.invoke(verifier, (Object) bytes);
            c.getDeclaredMethods();
            return true;
        } catch (Throwable t) {
            error("[CellProperty] Bytecode verify failed", t);
            return false;
        }
    }

    public static byte[] injectCell(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

        ClassVisitor cv = new ClassVisitor(ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

                if (name.equals("setLayout")) {
                    return new AdviceAdapter(ASM9, mv, access, name, descriptor) {
                        @Override protected void onMethodExit(int opcode) {
                            if (opcode == ATHROW) return;
                            mv.visitVarInsn(ALOAD, 0);
                            mv.visitMethodInsn(INVOKESTATIC,
                                internalName(CellPropertyRef.class),
                                "onCellBound", "(Larc/scene/ui/layout/Cell;)V", false);
                        }
                    };
                }

                if (name.equals("reset") && "()V".equals(descriptor)) {
                    return new AdviceAdapter(ASM9, mv, access, name, descriptor) {
                        @Override protected void onMethodExit(int opcode) {
                            if (opcode == ATHROW) return;
                            mv.visitVarInsn(ALOAD, 0);
                            mv.visitMethodInsn(INVOKESTATIC,
                                internalName(CellPropertyRef.class),
                                "onCellFreed", "(Larc/scene/ui/layout/Cell;)V", false);
                        }
                    };
                }

                if (name.startsWith("<")) return mv;
                if (!CELL_PROPERTY_METHODS.contains(name)) return mv;
                if (!(descriptor.endsWith(")Larc/scene/ui/layout/Cell;")
                    || ("row".equals(name) && "()V".equals(descriptor)))) return mv;

                return new AdviceAdapter(ASM9, mv, access, name, descriptor) {
                    @Override protected void onMethodExit(int opcode) {
                        if (opcode == ATHROW) return;
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitLdcInsn(name);
                        mv.visitLdcInsn(descriptor);
                        pushArgsArray(mv, Type.getArgumentTypes(descriptor));
                        mv.visitMethodInsn(INVOKESTATIC,
                            internalName(CellPropertyRef.class),
                            "recordPropertyCall",
                            "(Larc/scene/ui/layout/Cell;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)V",
                            false);
                    }
                };
            }
        };

        try {
            cr.accept(cv, ClassReader.EXPAND_FRAMES);
            return cw.toByteArray();
        } catch (Exception e) {
            error("[CellProperty] Failed to inject Cell class", e);
            return null;
        }
    }

    private static void pushArgsArray(MethodVisitor mv, Type[] argTypes) {
        pushInt(mv, argTypes.length);
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        int localIdx = 1;
        for (int i = 0; i < argTypes.length; i++) {
            mv.visitInsn(DUP);
            pushInt(mv, i);
            Type t = argTypes[i];
            mv.visitVarInsn(t.getOpcode(ILOAD), localIdx);
            box(mv, t);
            localIdx += t.getSize();
            mv.visitInsn(AASTORE);
        }
    }

    private static void box(MethodVisitor mv, Type t) {
        switch (t.getSort()) {
            case Type.BOOLEAN -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean",
                "valueOf", "(Z)Ljava/lang/Boolean;", false);
            case Type.CHAR    -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character",
                "valueOf", "(C)Ljava/lang/Character;", false);
            case Type.BYTE    -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Byte",
                "valueOf", "(B)Ljava/lang/Byte;", false);
            case Type.SHORT   -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Short",
                "valueOf", "(S)Ljava/lang/Short;", false);
            case Type.INT     -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer",
                "valueOf", "(I)Ljava/lang/Integer;", false);
            case Type.FLOAT   -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float",
                "valueOf", "(F)Ljava/lang/Float;", false);
            case Type.LONG    -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long",
                "valueOf", "(J)Ljava/lang/Long;", false);
            case Type.DOUBLE  -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double",
                "valueOf", "(D)Ljava/lang/Double;", false);
            default -> { }
        }
    }

    private static void pushInt(MethodVisitor mv, int value) {
        if (value >= -1 && value <= 5) mv.visitInsn(ICONST_0 + value);
        else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) mv.visitIntInsn(BIPUSH, value);
        else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) mv.visitIntInsn(SIPUSH, value);
        else mv.visitLdcInsn(value);
    }
    //endregion

    //region 辅助方法

    private static Class<?> loadClass(String className) throws ClassNotFoundException {
        return Class.forName(className, true, Vars.mods.mainLoader());
    }
    //endregion

    //region 生命周期管理

    public static void enable() {
        Runnable init = () -> {
            uiThread = Thread.currentThread();
            FAILURES.set(0);
            enabled = true;
            info("[CellProperty] Enabled, UI thread = " + uiThread.getName());
        };
        if (Core.app != null) Core.app.post(init);
        else init.run();
    }

    public static void disable() {
        if (!enabled) return;
        enabled = false;
        if (Core.app != null) Core.app.post(CellPropertyRef::performRollback);
        else clearAll();
        info("[CellProperty] Disabled (rollback scheduled)");
    }

    private static void performRollback() {
        if (enabled) return;
        byte[] original = originalCellBytes;
        originalCellBytes = null;
        if (original != null) {
            try {
                Injector.redefineOneClass(Cell.class, original);
                info("[CellProperty] Cell class rolled back to original bytecode");
            } catch (Throwable t) {
                error("[CellProperty] Rollback failed", t);
            }
        }
        clearAll();
    }

    public static boolean isEnabled() { return enabled; }

    public static void clearClassRecords(String hostSlashName) {
        if (hostSlashName == null) return;
        if (offUiThread() && Core.app != null) {
            Core.app.post(() -> clearClassRecords(hostSlashName));
            return;
        }

        List<Cell<?>> targets = new ArrayList<>();
        synchronized (cellState) {
            for (Entry<Cell<?>, CellState> e : cellState.entrySet()) {
                Cell<?> c = e.getKey();
                if (c == null) continue;
                CellState st = e.getValue();
                if (st == IGNORED_STATE) continue;
                if (st.id.hostClass.equals(hostSlashName)) targets.add(c);
            }
        }
        for (Cell<?> c : targets) removeCellFromTable(c);

        chainCache.remove(hostSlashName);
        templateCache.keySet().removeIf(id -> id.hostClass.equals(hostSlashName));
    }

    public static void clearAll() {
        if (offUiThread() && Core.app != null) {
            Core.app.post(CellPropertyRef::clearAll);
            return;
        }
        synchronized (cellState) {
            cellState.clear();
            idToCells.clear();
            classToCells.clear();
            chainCache.clear();
            templateCache.clear();
        }
        info("[CellProperty] 🗑 Cleared all records");
    }

    public static String getStats() {
        synchronized (cellState) {
            int ignored = 0;
            for (CellState st : cellState.values()) {
                if (st == IGNORED_STATE) ignored++;
            }
            return "CellPropertyRef:\n"
                 + "  Enabled: " + enabled + "\n"
                 + "  Failures: " + FAILURES.get() + "\n"
                 + "  Tracked Cells: " + cellState.size() + " (ignored: " + ignored + ")\n"
                 + "  Identities: " + idToCells.size() + "\n"
                 + "  Host Classes: " + classToCells.size() + "\n"
                 + "  Chain Cache: " + chainCache.size() + "\n"
                 + "  Template Cache: " + templateCache.size();
        }
    }
    //endregion

    //region Reflect

    private static <T> T invoke(MethodHandle handle, Object instance, Object[] args) throws Throwable {
        return (T) switch (args == null ? 0 : args.length) {
            case 0 -> handle.invoke(instance);
            case 1 -> handle.invoke(instance, args[0]);
            case 2 -> handle.invoke(instance, args[0], args[1]);
            case 3 -> handle.invoke(instance, args[0], args[1], args[2]);
            case 4 -> handle.invoke(instance, args[0], args[1], args[2], args[3]);
            default -> handle.bindTo(instance).asSpreader(Object.class, args.length).invoke(args);
        };
    }

    private static <T> T invoke(MethodHandle handle, Object[] args) throws Throwable {
        return (T) switch (args == null ? 0 : args.length) {
            case 0 -> handle.invoke();
            case 1 -> handle.invoke(args[0]);
            case 2 -> handle.invoke(args[0], args[1]);
            case 3 -> handle.invoke(args[0], args[1], args[2]);
            case 4 -> handle.invoke(args[0], args[1], args[2], args[3]);
            default -> handle.asSpreader(Object.class, args.length).invoke(args);
        };
    }
    //endregion
}