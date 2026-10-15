package nipx.uihook;

import arc.Core;
import arc.func.Cons;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.event.EventListener;
import arc.scene.ui.*;
import arc.scene.ui.Label;
import arc.scene.ui.layout.*;
import arc.scene.ui.layout.Stack;
import arc.struct.Seq;
import arc.util.pooling.Pools;
import mindustry.Vars;
import nipx.AnnotationTransformer;
import nipx.HotSwapAgent;
import nipx.Injector;
import nipx.InstanceTracker;
import nipx.LambdaAligner;
import nipx.HotswapBatchListener;
import nipx.annotation.HotRebuild;
import nipx.ref.ClassHierarchyOracle;
import nipx.util.LibTool;
import java.lang.instrument.ClassDefinition;
import org.objectweb.asm.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.lang.invoke.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
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
	public record PropertyCall(
		String owner, String method, String desc, Object[] args, int line,
		String helperClass, String helperMethod, int helperLine, int callOrdinal
	) {
		public PropertyCall(String owner, String method, String desc, Object[] args, int line) {
			this(owner, method, desc, args, line, null, null, -1, 0);
		}

		public PropertyCall(String owner, String method, String desc, Object[] args, int line,
		                    String helperClass, String helperMethod, int helperLine) {
			this(owner, method, desc, args, line, helperClass, helperMethod, helperLine, 0);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof PropertyCall that)) return false;
			return line == that.line
			       && helperLine == that.helperLine
			       && callOrdinal == that.callOrdinal
			       && Objects.equals(owner, that.owner)
			       && Objects.equals(method, that.method)
			       && Objects.equals(desc, that.desc)
			       && Objects.equals(helperClass, that.helperClass)
			       && Objects.equals(helperMethod, that.helperMethod)
			       && Arrays.equals(args, that.args);
		}

		@Override
		public int hashCode() {
			int result = Objects.hash(owner, method, desc, line, helperClass, helperMethod, helperLine, callOrdinal);
			result = 31 * result + Arrays.hashCode(args);
			return result;
		}
	}

	public record CellIdentity(
		String hostClass, String hostMethod, String hostDesc,
		int line, String creatorName,
		String helperClass, String helperMethod, int helperLine,
		int callOrdinal
	) {
		public CellIdentity(String hostClass, String hostMethod, String hostDesc,
		                    int line, String creatorName) {
			this(hostClass, hostMethod, hostDesc, line, creatorName, null, null, -1, 0);
		}

		public CellIdentity(String hostClass, String hostMethod, String hostDesc,
		                    int line, String creatorName,
		                    String helperClass, String helperMethod, int helperLine) {
			this(hostClass, hostMethod, hostDesc, line, creatorName, helperClass, helperMethod, helperLine, 0);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof CellIdentity that)) return false;
			return line == that.line
			       && helperLine == that.helperLine
			       && callOrdinal == that.callOrdinal
			       && Objects.equals(hostClass, that.hostClass)
			       && Objects.equals(hostMethod, that.hostMethod)
			       && Objects.equals(hostDesc, that.hostDesc)
			       && Objects.equals(creatorName, that.creatorName)
			       && Objects.equals(helperClass, that.helperClass)
			       && Objects.equals(helperMethod, that.helperMethod);
		}

		@Override
		public int hashCode() {
			return Objects.hash(hostClass, hostMethod, hostDesc, line, creatorName,
				helperClass, helperMethod, helperLine, callOrdinal);
		}

		@Override
		public String toString() {
			if (helperClass != null) {
				return hostClass + "#" + hostMethod + "@" + line + "[" + callOrdinal + "]"
				       + "->[" + helperClass + "#" + helperMethod + "@" + helperLine + "](" + creatorName + ")";
			}
			return hostClass + "#" + hostMethod + "@" + line + "[" + callOrdinal + "](" + creatorName + ")";
		}
	}

	public record LambdaInfo(String ownerClass, String methodName, String methodDesc, Object[] captures, int tag) {
		public LambdaInfo(String ownerClass, String methodName, String methodDesc, Object[] captures) {
			this(ownerClass, methodName, methodDesc, captures, H_INVOKESTATIC);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof LambdaInfo that)) return false;
			return tag == that.tag
			       && ownerClass.equals(that.ownerClass)
			       && methodName.equals(that.methodName)
			       && methodDesc.equals(that.methodDesc)
			       && Arrays.equals(captures, that.captures);
		}

		@Override
		public int hashCode() {
			int result = Objects.hash(ownerClass, methodName, methodDesc, tag);
			result = 31 * result + Arrays.hashCode(captures);
			return result;
		}

		@Override
		public String toString() { return "Lambda[" + methodName + "]"; }
	}

	public record StaticRef(String owner, String name, String desc) {
		@Override
		public String toString() { return owner + "." + name; }
	}

	public record ThisPlaceholder(String hostClass) {
		@Override
		public String toString() { return "this[" + hostClass + "]"; }
	}

	public record DynamicArg(int fingerprint, Object runtimeValue) {
		public DynamicArg(int fingerprint) {
			this(fingerprint, null);
		}

		public DynamicArg withRuntimeValue(Object val) {
			return new DynamicArg(fingerprint, val);
		}

		@Override
		public String toString() {
			return runtimeValue != null
				? "DynamicArg#" + Integer.toHexString(fingerprint) + "(" + runtimeValue + ")"
				: "DynamicArg#" + Integer.toHexString(fingerprint);
		}
	}

	private record HostFrame(
		String hostClass, String hostMethod, String hostDesc,
		int line, String creatorName,
		String helperClass, String helperMethod, int helperLine
	) {
		HostFrame(String hostClass, String hostMethod, String hostDesc, int line, String creatorName) {
			this(hostClass, hostMethod, hostDesc, line, creatorName, null, null, -1);
		}
	}

	private record CellEntry(Cell<?> cell, CellIdentity id, int chainIdx, List<PropertyCall> chain) { }

	private record MethodExtraction(boolean analyzed, List<List<PropertyCall>> chains) { }

	private record ChainMatch(int index, List<PropertyCall> chain) { }

	private static final ChainMatch NO_CHAIN = new ChainMatch(-1, null);

	public static class Geometry16 extends Cell<Element> {
		public Geometry16() {
			super();
		}
	}

	private static final class CellState {
		CellIdentity       id;
		int                chainIdx;
		List<PropertyCall> chain;
		final Map<String, Integer> slotCursor = new HashMap<>();
		Cell baseline;
		Geometry16 snapshot;
		final Map<Integer, WeakReference<EventListener>> slotListeners = new HashMap<>();
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
	private static final    ThreadLocal<boolean[]> BUSY         = ThreadLocal.withInitial(() -> new boolean[1]);
	private static final    AtomicInteger          FAILURES     = new AtomicInteger();
	private static final    int                    MAX_FAILURES = 20;
	private static volatile Thread                 uiThread;

	private static final Map<Cell<?>, CellState>                            cellState      = new WeakHashMap<>();
	private static final Map<CellIdentity, Set<Cell<?>>>                    idToCells      = new HashMap<>();
	private static final Map<String, LinkedHashSet<CellIdentity>>           classToCells   = new HashMap<>();
	private static final Map<String, Map<String, List<List<PropertyCall>>>> chainCache     = new HashMap<>();
	private static final Map<CellIdentity, ChainMatch>                      templateCache  = new HashMap<>();
	private static final Map<Cell<?>, CellIdentity>                         unpatchedCells = new WeakHashMap<>();
	private static final Map<Table, Map<String, Integer>>                   tableSiteOrdinals = new WeakHashMap<>();

	private static volatile boolean enabled = false;
	private static volatile byte[]  originalCellBytes;

	static {
		HotSwapAgent.addTransparentFramePredicate(className ->
			CellPropertyRef.class.getName().equals(className)
		);
		HotSwapAgent.addBatchListener(new HotswapBatchListener() {
			@Override
			public void afterInternalBatch(List<ClassDefinition> redefinedClasses) {
				if (!isEnabled()) return;
				afterRedefineBatch(redefinedClasses);
			}
		});
	}

	private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

	private static <T> Set<T> newWeakSet() {
		return Collections.newSetFromMap(new WeakHashMap<>());
	}

	private static boolean isUiThread() {
		if (uiThread != null) return Thread.currentThread() == uiThread;
		if (Core.app == null) return true;
		Core.app.post(() -> {
			if (uiThread == null) {
				uiThread = Thread.currentThread();
				info("[CellProperty] UI thread resolved: " + uiThread.getName());
			}
		});
		return false;
	}

	private static boolean offUiThread() {
		return !isUiThread();
	}
	//endregion

	//region 钩子入口

	public static void onCellBound(Cell<?> cell) {
		if (!enabled || cell == null) return;
		if (!isUiThread()) return;

		boolean[] busy = BUSY.get();
		if (busy[0]) return;
		busy[0] = true;
		try {
			onCellBoundImpl(cell);
			FAILURES.set(0);
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

		Table table = cell.getTable();
		String siteKey = hf.hostClass + "#" + hf.hostMethod + "@" + hf.line + "#" + (hf.creatorName == null ? "" : hf.creatorName)
		                 + "#" + (hf.helperClass == null ? "" : hf.helperClass) + "@" + hf.helperLine;
		int rawOrdinal = 0;
		if (table != null) {
			synchronized (cellState) {
				Map<String, Integer> siteCounts = tableSiteOrdinals.computeIfAbsent(table, k -> new HashMap<>());
				rawOrdinal = siteCounts.getOrDefault(siteKey, 0);
				siteCounts.put(siteKey, rawOrdinal + 1);
			}
		}

		CellIdentity id = new CellIdentity(hf.hostClass, hf.hostMethod, hf.hostDesc,
		 hf.line, hf.creatorName, hf.helperClass, hf.helperMethod, hf.helperLine, rawOrdinal);
		ChainMatch match    = findChainMatch(id);
		int        chainIdx = match == null ? -1 : match.index();
		List<PropertyCall> chain = (match == null || match.chain() == null)
		 ? new ArrayList<>() : new ArrayList<>(match.chain());

		Geometry16 snap = null;
		try {
			snap = new Geometry16();
			snap.set(cell);
		} catch (Throwable ignored) { }

		CellState st = new CellState(id, chainIdx, chain);
		st.baseline = snap;
		st.snapshot = snap;

		synchronized (cellState) {
			cellState.put(cell, st);
			registerCellLocked(id, cell, chainIdx);
		}

		if (DEBUG) log("[CellProperty] Bound: " + id + " idx=" + chainIdx);
	}

	private static final Set<Cell<?>> KNOWN_DEFAULTS = Collections.newSetFromMap(new WeakHashMap<>());

	public static boolean isCellDefaults(Cell<?> c) {
		if (c == null) return false;
		Cell<?> def = null;
		try {
			def = Cell.defaults();
		} catch (Throwable ignored) { }
		if (def != null) {
			KNOWN_DEFAULTS.add(def);
			if (c == def) return true;
		}
		if (KNOWN_DEFAULTS.contains(c)) return true;
		return c.getTable() == null && !c.hasElement();
	}

	public static void onCellSet(Cell<?> cell, Cell<?> arg) {
		if (!enabled || cell == null || arg == null) return;
		if (arg instanceof Geometry16) return;
		if (!isUiThread()) return;

		boolean[] busy = BUSY.get();
		if (busy[0]) return;
		busy[0] = true;
		try {
			if (isCellDefaults(arg)) {
				removeCell(cell);
			} else {
				onCellApplyTemplate(cell, arg);
			}
		} catch (Throwable t) {
			if (DEBUG) log("[CellProperty] onCellSet failed: " + t.getMessage());
		} finally {
			busy[0] = false;
		}
	}

	private static void onCellApplyTemplate(Cell<?> cell, Cell<?> arg) {
		synchronized (cellState) {
			CellState st = cellState.get(cell);
			if (st != null && st != IGNORED_STATE) {
				Geometry16 snap = new Geometry16();
				snap.set(arg);
				st.snapshot = snap;
				st.baseline = snap;
			}
		}
	}

	private static void registerCellLocked(CellIdentity id, Cell<?> cell, int chainIdx) {
		if (id == IGNORED_STATE.id) return;
		if (chainIdx < 0) {
			unpatchedCells.put(cell, id);
		} else {
			unpatchedCells.remove(cell);
		}
		idToCells.computeIfAbsent(id, k -> newWeakSet()).add(cell);
		classToCells.computeIfAbsent(id.hostClass, k -> new LinkedHashSet<>()).add(id);
	}

	private static void unregisterCellLocked(CellIdentity id, Cell<?> cell) {
		if (id == IGNORED_STATE.id) return;
		unpatchedCells.remove(cell);
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
		if (!isUiThread()) return;

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
		if (!isUiThread()) return;

		boolean[] busy = BUSY.get();
		if (busy[0]) return;
		busy[0] = true;
		try {
			recordPropertyCallImpl(cell, method, desc, args);
			FAILURES.set(0);
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

		String   key       = method + desc;
		int      cursor    = state.slotCursor.getOrDefault(key, 0);
		int      slot      = findNthSlotByMethodDesc(chain, method, desc, cursor);
		if (slot < 0) {
			if (DEBUG) log("[CellProperty] Slot #" + cursor + " not found: " + method + desc);
			return;
		}
		state.slotCursor.put(key, cursor + 1);

		PropertyCall existing = chain.get(slot);
		Object[]     merged   = mergeArgs(existing.args, args);
		if (!argsEqual(existing.args, merged)) {
			chain.set(slot, new PropertyCall(existing.owner, method, desc, merged, existing.line,
			                                 existing.helperClass, existing.helperMethod, existing.helperLine, existing.callOrdinal));
		}
		if ("tooltip".equals(method) && cell.get() != null && cell.get().getListeners().size > 0) {
			state.slotListeners.put(slot, new WeakReference<>(cell.get().getListeners().peek()));
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
			List<StackWalker.StackFrame> userFrames = new ArrayList<>();

			for (StackWalker.StackFrame f : (Iterable<StackWalker.StackFrame>) s::iterator) {
				String cls  = f.getClassName().replace('.', '/');
				String name = f.getMethodName();

				if (cls.equals(CL_CELL)
				    || cls.startsWith("arc/util/pooling/")
				    || cls.startsWith("nipx/")) {
					continue;
				}

				if (cls.equals(CL_TABLE) || isTableCellCreator(cls, name)) {
					if (userFrames.isEmpty() && (TABLE_CELL_CREATORS.contains(name) || isTableCellCreator(cls, name))) {
						creatorName = name;
					}
					continue;
				}

				if (isLibraryClass(cls)) {
					break;
				}

				userFrames.add(f);
			}

			if (userFrames.isEmpty()) return null;

			StackWalker.StackFrame first = userFrames.get(0);
			String firstCls  = first.getClassName().replace('.', '/');
			String firstName = first.getMethodName();
			String firstDesc = first.getDescriptor();

			if (!isHelper(firstCls, firstName, firstDesc)) {
				return new HostFrame(firstCls, firstName, firstDesc, first.getLineNumber(), creatorName);
			}

			// 最内层 helper 帧（方法名 + 行号）
			String helperCls    = firstCls;
			String helperMethod = firstName;
			int    helperLine   = first.getLineNumber();

			// 从内向外剥 helper 帧，嵌套取最外层作为调用点
			for (int i = 1; i < userFrames.size(); i++) {
				StackWalker.StackFrame f = userFrames.get(i);
				String cls  = f.getClassName().replace('.', '/');
				String name = f.getMethodName();
				String desc = f.getDescriptor();

				if (!isHelper(cls, name, desc)) {
					return new HostFrame(cls, name, desc, f.getLineNumber(), creatorName,
					                     helperCls, helperMethod, helperLine);
				}
			}

			return null;
		});
	}

	private static boolean matchesSite(CellIdentity id, PropertyCall creator) {
		if (id.creatorName != null && !id.creatorName.equals(creator.method)) return false;
		if (id.line >= 0 && creator.line >= 0 && id.line != creator.line) return false;
		if (id.helperClass != null) {
			if (!id.helperClass.equals(creator.helperClass)) return false;
			if (id.helperMethod != null && creator.helperMethod != null && !id.helperMethod.equals(creator.helperMethod)) return false;
			if (id.helperLine >= 0 && creator.helperLine >= 0 && id.helperLine != creator.helperLine) return false;
		} else {
			if (creator.helperClass != null) return false;
		}
		return true;
	}

	private static boolean matchesIdentity(CellIdentity id, PropertyCall creator) {
		if (!matchesSite(id, creator)) return false;
		return id.callOrdinal == creator.callOrdinal;
	}

	/**
	 * 找 id 对应的静态链。若该站点只有唯一样本，直接命中（支持循环与多实例）；
	 * 同一行同 creator 多个站点时，按 callOrdinal 取模候选数路由。
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

		List<Integer> candidateIndices = new ArrayList<>();
		for (int i = 0; i < methodChains.size(); i++) {
			List<PropertyCall> chain = methodChains.get(i);
			if (chain.isEmpty()) continue;
			PropertyCall creator = chain.get(0);
			if (matchesSite(id, creator)) {
				candidateIndices.add(i);
			}
		}

		ChainMatch r;
		if (candidateIndices.isEmpty()) {
			r = NO_CHAIN;
		} else if (candidateIndices.size() == 1) {
			int idx = candidateIndices.get(0);
			r = new ChainMatch(idx, methodChains.get(idx));
		} else {
			int targetIdx = candidateIndices.get(Math.floorMod(id.callOrdinal, candidateIndices.size()));
			r = new ChainMatch(targetIdx, methodChains.get(targetIdx));
		}
		templateCache.put(id, r);
		return r == NO_CHAIN ? null : r;
	}

	private static ChainMatch findChainMatchInModel(Map<String, List<List<PropertyCall>>> model,
	                                                String methodKey, CellIdentity id,
	                                                Set<String> matchedNew) {
		List<List<PropertyCall>> methodChains = model.get(methodKey);
		if (methodChains == null) return null;

		List<Integer> candidateIndices = new ArrayList<>();
		for (int i = 0; i < methodChains.size(); i++) {
			if (matchedNew.contains(methodKey + "#" + i)) continue;
			List<PropertyCall> chain = methodChains.get(i);
			if (chain.isEmpty()) continue;
			PropertyCall creator = chain.get(0);
			if (matchesSite(id, creator)) {
				candidateIndices.add(i);
			}
		}

		if (candidateIndices.isEmpty()) return null;
		if (candidateIndices.size() == 1) {
			int idx = candidateIndices.get(0);
			return new ChainMatch(idx, methodChains.get(idx));
		}
		int targetIdx = candidateIndices.get(Math.floorMod(id.callOrdinal, candidateIndices.size()));
		return new ChainMatch(targetIdx, methodChains.get(targetIdx));
	}

	private static Map<String, List<List<PropertyCall>>> buildChainModel(String slashName) {
		try {
			Class<?> hostClass = Class.forName(slashName.replace('/', '.'), false, getTargetLoader());
			byte[]   bytes     = fetchCurrentBytecode(hostClass);
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
		String n = methodNameFromKey(k);
		return n.startsWith("lambda$") || n.contains("$lambda$") || n.contains("$lambda-");
	}

	/**
	 * Java:   lambda$build$3       → "build"
	 * Kotlin: metricTable$lambda$1 → "metricTable"
	 */
	private static String lambdaOwner(String k) {
		String n = methodNameFromKey(k);
		if (n.startsWith("lambda$")) {
			int a = 6;
			int b = n.lastIndexOf('$');
			return b > a ? n.substring(a + 1, b) : "";
		}
		int kIdx = n.indexOf("$lambda$");
		if (kIdx >= 0) return n.substring(0, kIdx);
		kIdx = n.indexOf("$lambda-");
		if (kIdx >= 0) return n.substring(0, kIdx);
		return "";
	}

	private static int lambdaOrdinal(String k) {
		String n = methodNameFromKey(k);
		int sep = Math.max(n.lastIndexOf('$'), n.lastIndexOf('-'));
		if (sep >= 0 && sep < n.length() - 1) {
			try { return Integer.parseInt(n.substring(sep + 1)); } catch (Exception ignored) { }
		}
		return 0;
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
			Object tArg = out[j];
			Object rArg = runtimeKnown[j];
			if (tArg instanceof DynamicArg newDyn) {
				if (rArg instanceof DynamicArg oldDyn) {
					// 热重载时模板合并：比较表达式切片指纹
					if (newDyn.fingerprint() == oldDyn.fingerprint()) {
						// 指纹未变：安全继承已有运行时值
						out[j] = newDyn.withRuntimeValue(oldDyn.runtimeValue());
					} else {
						// 指纹改变：旧值已作废，不能再填旧值，保持 null 标脏
						out[j] = newDyn;
						if (DEBUG) log("[CellProperty] Dynamic argument fingerprint changed, marking dirty for rebuild: "
						               + oldDyn.fingerprint() + " -> " + newDyn.fingerprint());
					}
				} else if (rArg != null) {
					// 运行时钩子填充 (recordPropertyCallImpl)：rArg 为具体运行时实参
					out[j] = newDyn.withRuntimeValue(rArg);
				}
			} else if (tArg == null && rArg != null) {
				if (rArg instanceof DynamicArg) {
					out[j] = null;
				} else {
					out[j] = rArg;
				}
			}
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
		List<PropertyCall>   out  = new ArrayList<>(template.size());
		for (PropertyCall t : template) {
			String             key    = t.method + t.desc;
			int                nth    = seen.merge(key, 1, Integer::sum) - 1;
			List<PropertyCall> list   = runtimeByKey.get(key);
			PropertyCall       r      = (list != null && nth < list.size()) ? list.get(nth) : null;
			Object[]           merged = r == null ? t.args : mergeArgs(t.args, r.args);
			out.add(new PropertyCall(t.owner, t.method, t.desc, merged, t.line,
			                         t.helperClass, t.helperMethod, t.helperLine, t.callOrdinal));
		}
		return out;
	}
	//endregion

	//region 热替换回调

	/**
	 * 批次重定义回调：每批次在 UI 线程统一调度执行一次，避免单类多次调度引发卡顿与闪烁。
	 */
	public static void afterRedefineBatch(List<ClassDefinition> definitions) {
		if (!isEnabled() || definitions == null || definitions.isEmpty()) return;
		List<Map.Entry<String, byte[]>> batchItems = new ArrayList<>(definitions.size());
		Map<String, Map<String, List<List<PropertyCall>>>> capturedOldModels = new HashMap<>();
		for (ClassDefinition def : definitions) {
			String slashName = internalName(def.getDefinitionClass());
			byte[] bytes = def.getDefinitionClassFile();
			batchItems.add(new AbstractMap.SimpleImmutableEntry<>(slashName, bytes));
			Map<String, List<List<PropertyCall>>> oldModel = chainCache.get(slashName);
			if (oldModel != null) {
				capturedOldModels.put(slashName, oldModel);
			}
		}

		Runnable work = () -> {
			boolean[] busy = BUSY.get();
			if (busy[0]) return;
			busy[0] = true;
			try {
				for (Map.Entry<String, byte[]> item : batchItems) {
					String slashName = item.getKey();
					byte[] newBytecode = item.getValue();
					try {
						afterRedefinedImpl(slashName, newBytecode, capturedOldModels.get(slashName));
					} catch (Throwable t) {
						error("[CellProperty] afterRedefined failed for " + slashName, t);
					}
				}
			} finally {
				busy[0] = false;
			}
		};
		if (isUiThread() || Core.app == null) { work.run(); } else { Core.app.post(work); }
	}

	public static void afterRedefine(String slashName, byte[] newBytecode) {
		if (!enabled) return;
		Map<String, List<List<PropertyCall>>> capturedOldModel = chainCache.get(slashName);
		Runnable work = () -> {
			boolean[] busy = BUSY.get();
			if (busy[0]) return;
			busy[0] = true;
			try {
				afterRedefinedImpl(slashName, newBytecode, capturedOldModel);
			} catch (Throwable t) {
				error("[CellProperty] afterRedefined failed for " + slashName, t);
			} finally {
				busy[0] = false;
			}
		};
		if (isUiThread() || Core.app == null) { work.run(); } else { Core.app.post(work); }
	}

	private static final ThreadLocal<Set<String>> REENTRANT_REDEFINE = ThreadLocal.withInitial(HashSet::new);

	private static void afterRedefinedImpl(String slashName, byte[] newBytecode,
	                                       Map<String, List<List<PropertyCall>>> capturedOldModel) {
		Set<String> reentrant = REENTRANT_REDEFINE.get();
		if (!reentrant.add(slashName)) return;
		try {
			info("[CellProperty] Class redefined: " + slashName);

			WHOLE_CLASS_HELPERS.remove(slashName);
			HELPER_METHODS_BY_CLASS.remove(slashName);
			SCANNED_HELPER_CLASSES.remove(slashName);

			// 收集依赖于此 helper 的宿主类
			Set<String> dependentHosts = new HashSet<>();
			synchronized (cellState) {
				for (CellState st : cellState.values()) {
					if (st == IGNORED_STATE) continue;
					if (slashName.equals(st.id.helperClass)) {
						dependentHosts.add(st.id.hostClass);
					}
				}
			}
			for (Entry<String, Map<String, List<List<PropertyCall>>>> e : chainCache.entrySet()) {
				for (List<List<PropertyCall>> chains : e.getValue().values()) {
					for (List<PropertyCall> chain : chains) {
						for (PropertyCall pc : chain) {
							if (slashName.equals(pc.helperClass)) {
								dependentHosts.add(e.getKey());
								break;
							}
						}
					}
				}
			}

			Map<String, List<List<PropertyCall>>> oldModel = capturedOldModel != null ? capturedOldModel : chainCache.get(slashName);
			if (oldModel != null) {
				applyHostRedefine(slashName, newBytecode, oldModel);
			} else if (DEBUG) {
				log("[CellProperty] No old model for " + slashName);
			}

			// 触发方法级 @HotRebuild 兜底重建 (Task 31)
			try {
				triggerHotRebuild(slashName);
			} catch (Throwable t) {
				if (DEBUG) log("[CellProperty] triggerHotRebuild failed for " + slashName + ": " + t.getMessage());
			}

			// 触发容器级 Cons 重建兜底 (Task 32)
			try {
				triggerContainerRebuild(slashName);
			} catch (Throwable t) {
				if (DEBUG) log("[CellProperty] triggerContainerRebuild failed for " + slashName + ": " + t.getMessage());
			}

			// 触发注册型回调重建兜底 (Task 33)
			try {
				triggerCallbackRebuild(slashName);
			} catch (Throwable t) {
				if (DEBUG) log("[CellProperty] triggerCallbackRebuild failed for " + slashName + ": " + t.getMessage());
			}

			// 级联刷新依赖该 helper 的宿主类
			for (String dep : dependentHosts) {
				if (!dep.equals(slashName)) {
					try {
						byte[] depBytes = fetchBytecode(dep);
						if (depBytes != null) {
							if (DEBUG) log("[CellProperty] Cascading helper update to host " + dep);
							afterRedefinedImpl(dep, depBytes, chainCache.get(dep));
						}
					} catch (Throwable t) {
						if (DEBUG) log("[CellProperty] Failed cascade to " + dep + ": " + t.getMessage());
					}
				}
			}
		} finally {
			reentrant.remove(slashName);
		}
	}

	private static void applyHostRedefine(String slashName, byte[] newBytecode,
	                                      Map<String, List<List<PropertyCall>>> oldModel) {

		Map<String, List<List<PropertyCall>>> newModel = extractCellChains(newBytecode);

		Map<String, String> methodPairs = matchMethods(slashName, oldModel, newModel);

		Map<String, Map<Integer, Integer>> chainAligns  = new HashMap<>();
		Map<String, String>                oldMkToNewMk = new HashMap<>();
		Set<String>                        matchedNew   = new HashSet<>();

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

		Map<String, List<List<PropertyCall>>> oldTemplatesByMethod = new HashMap<>();
		for (Entry<String, List<List<PropertyCall>>> oe : oldModel.entrySet()) {
			oldTemplatesByMethod.put(oe.getKey(), findTemplateChains(oe.getValue()));
		}
		Map<String, List<List<PropertyCall>>> newTemplatesByMethod = new HashMap<>();
		for (Entry<String, List<List<PropertyCall>>> ne : newModel.entrySet()) {
			newTemplatesByMethod.put(ne.getKey(), findTemplateChains(ne.getValue()));
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

		int                              updated    = 0, removed = 0, added = 0, skipped = 0;
		Map<Cell<?>, CellIdentity>       newIdOf    = new IdentityHashMap<>();
		Map<Cell<?>, Integer>            newIdxOf   = new IdentityHashMap<>();
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
				ChainMatch retryMatch = findChainMatchInModel(newModel, newMk, ce.id, matchedNew);
				if (retryMatch != null) {
					int newIdx = retryMatch.index();
					matchedNew.add(newMk + "#" + newIdx);
					List<PropertyCall> newTemplate = retryMatch.chain();
					List<PropertyCall> mergedNew   = mergeTemplate(newTemplate, ce.chain);
					PropertyCall       newCreator  = mergedNew.get(0);
					CellIdentity newId = new CellIdentity(
					 slashName,
					 methodNameFromKey(newMk),
					 methodDescFromKey(newMk),
					 newCreator.line,
					 newCreator.method,
					 newCreator.helperClass,
					 newCreator.helperMethod,
					 newCreator.helperLine,
					 ce.id.callOrdinal);
					boolean templateChanged = updateCellTemplateIfChanged(cell, ce.id, newCreator, oldMk, newMk,
						oldTemplatesByMethod, newTemplatesByMethod);
					try {
						List<PropertyCall> effective = applyChainUpdate(cell, ce.chain, mergedNew, templateChanged);
						newIdOf.put(cell, newId);
						newIdxOf.put(cell, newIdx);
						newChainOf.put(cell, effective);
						if (effective == mergedNew) {
							if (!callsEqual(ce.chain, mergedNew) || templateChanged) updated++;
						} else {
							skipped++;
						}
					} catch (Throwable t) {
						error("[CellProperty] retry applyChainUpdate failed for " + ce.id, t);
						newIdOf.put(cell, newId);
						newIdxOf.put(cell, newIdx);
						newChainOf.put(cell, ce.chain);
						skipped++;
					}
					continue;
				}
				// 绑定时就没链且重试仍未匹配：保持不动，坐标作废，保留在 unpatchedCells
				skipped++;
				continue;
			}

			Map<Integer, Integer> align  = chainAligns.get(oldMk);
			Integer               newIdx = align == null ? null : align.get(ce.chainIdx);

			if (newIdx == null) {
				// 站点在新模型里没有对应 → 移除
				removeCellFromTable(cell);
				removed++;
				continue;
			}

			List<PropertyCall> newTemplate = newChains.get(newIdx);
			List<PropertyCall> mergedNew   = mergeTemplate(newTemplate, ce.chain);

			PropertyCall newCreator = mergedNew.get(0);
			CellIdentity newId = new CellIdentity(
			 slashName,
			 methodNameFromKey(newMk),
			 methodDescFromKey(newMk),
			 newCreator.line,
			 newCreator.method,
			 newCreator.helperClass,
			 newCreator.helperMethod,
			 newCreator.helperLine,
			 ce.id.callOrdinal);

			boolean templateChanged = updateCellTemplateIfChanged(cell, ce.id, newCreator, oldMk, newMk,
				oldTemplatesByMethod, newTemplatesByMethod);
			try {
				List<PropertyCall> effective = applyChainUpdate(cell, ce.chain, mergedNew, templateChanged);
				newIdOf.put(cell, newId);
				newIdxOf.put(cell, newIdx);
				newChainOf.put(cell, effective);
				if (effective == mergedNew) {
					if (!callsEqual(ce.chain, mergedNew) || templateChanged) updated++;
				} else {
					skipped++;
					synchronized (cellState) {
						unpatchedCells.put(cell, ce.id);
					}
					warn("[CellProperty] Cell " + ce.id + " has unpatched dynamic properties (needs rebuild)");
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
				registerCellLocked(st.id, c, st.chainIdx);
			}
		}

		List<CellIdentity> idsAfter = new ArrayList<>();
		synchronized (cellState) {
			LinkedHashSet<CellIdentity> set = classToCells.get(slashName);
			if (set != null) idsAfter.addAll(set);
		}

		for (Entry<String, List<List<PropertyCall>>> e : newModel.entrySet()) {
			String                   mk     = e.getKey();
			List<List<PropertyCall>> chains = e.getValue();
			for (int j = 0; j < chains.size(); j++) {
				if (matchedNew.contains(mk + "#" + j)) continue;
				List<PropertyCall> chain = chains.get(j);
				if (chain.isEmpty()) continue;
				PropertyCall creator = chain.get(0);
				if (!isCreatorCall(creator) || isTemplateCreator(creator)) continue;
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
	 * 方法级配对。以 LambdaAligner 的 old->new 映射为主，
	 * 相似度配对（阈值 0.6）作为兜底；非 lambda 同 key 直接配。
	 */
	private static Map<String, String> matchMethods(String slashName,
	                                                Map<String, List<List<PropertyCall>>> oldM,
	                                                Map<String, List<List<PropertyCall>>> newM) {
		record Pair(String o, String n, double s, int dist) { }

		Map<String, String> out = new HashMap<>();

		for (String k : oldM.keySet()) {
			if (!isLambdaKey(k) && newM.containsKey(k)) out.put(k, k);
		}

		// 1. 优先采用 LambdaAligner 产出的权威映射（oldKey -> newKey），且仅作用于当前对齐的类
		Map<String, String> alignerMapping = LambdaAligner.LAST_METHOD_MAPPING;
		if (alignerMapping != null && !alignerMapping.isEmpty()
		    && Objects.equals(slashName, LambdaAligner.LAST_ALIGNED_CLASS)) {
			for (Entry<String, String> e : alignerMapping.entrySet()) {
				String ok = e.getKey();
				String nk = e.getValue();
				if (oldM.containsKey(ok) && newM.containsKey(nk) && !out.containsKey(ok)) {
					out.put(ok, nk);
				}
			}
		}

		// 2. 相似度配对作为兜底（阈值 0.6），只处理尚未配对的 lambda 方法
		List<Pair> pairs = new ArrayList<>();
		for (Entry<String, List<List<PropertyCall>>> oe : oldM.entrySet()) {
			String ok = oe.getKey();
			if (out.containsKey(ok) || !isLambdaKey(ok) || oe.getValue().isEmpty()) continue;
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
		int                   n   = olds.size(), m = news.size();
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
				out.put(i, j);
				i++;
				j++;
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

	private static int compareCellOrder(int line1, int helperLine1, int ordinal1,
	                                    int line2, int helperLine2, int ordinal2) {
		if (line1 != line2) return Integer.compare(line1, line2);
		if (helperLine1 != helperLine2) return Integer.compare(helperLine1, helperLine2);
		return Integer.compare(ordinal1, ordinal2);
	}

	private static boolean appendNewCell(String slashName, String methodKey,
	                                     List<PropertyCall> newChain, List<CellIdentity> existingIds) throws Throwable {
		PropertyCall creator = newChain.get(0);
		if (!isCreatorCall(creator) || isTemplateCreator(creator)) return false;

		if (!hasUsableCreatorArgs(creator)) {
			if (DEBUG) log("[CellProperty] Skipping append with unconstructible creator args: " + creator.method);
			return false;
		}

		CellIdentity beforeId = null, afterId = null;
		for (CellIdentity id : existingIds) {
			if (!methodKeyOf(id).equals(methodKey)) continue;
			if (id.line < 0) continue;
			int cmp = compareCellOrder(id.line, id.helperLine, id.callOrdinal,
			                           creator.line, creator.helperLine, creator.callOrdinal);
			if (cmp < 0) {
				if (beforeId == null || compareCellOrder(id.line, id.helperLine, id.callOrdinal,
				                                         beforeId.line, beforeId.helperLine, beforeId.callOrdinal) > 0) {
					beforeId = id;
				}
			} else if (cmp > 0) {
				if (afterId == null || compareCellOrder(id.line, id.helperLine, id.callOrdinal,
				                                        afterId.line, afterId.helperLine, afterId.callOrdinal) < 0) {
					afterId = id;
				}
			}
		}

		List<Cell<?>> anchors = new ArrayList<>();
		boolean insertAfter = true;
		if (beforeId != null) {
			anchors = allAliveCells(beforeId);
			insertAfter = true;
		}
		if (anchors.isEmpty() && afterId != null) {
			anchors = allAliveCells(afterId);
			insertAfter = false;
		}

		Map<Table, List<Cell<?>>> anchorsByTable = new LinkedHashMap<>();
		if (!anchors.isEmpty()) {
			for (Cell<?> c : anchors) {
				if (c != null && c.getTable() != null) {
					anchorsByTable.computeIfAbsent(c.getTable(), k -> new ArrayList<>()).add(c);
				}
			}
		} else {
			for (CellIdentity id : existingIds) {
				if (!methodKeyOf(id).equals(methodKey)) continue;
				Cell<?> c = firstAliveCell(id);
				if (c != null && c.getTable() != null) {
					anchorsByTable.computeIfAbsent(c.getTable(), k -> new ArrayList<>());
					break;
				}
			}
		}
		if (anchorsByTable.isEmpty()) return false;

		MethodType   type = MethodType.fromMethodDescriptorString(creator.desc, getTargetLoader());
		MethodHandle mh   = findTableMethod(creator.owner, creator.method, type);
		if (mh == null) return false;

		Table anyTable = anchorsByTable.keySet().iterator().next();
		Object[] converted = creator.args == null ? null : convertArgs(null, anyTable, type, creator.args);

		int newIdx = -1;
		Map<String, List<List<PropertyCall>>> chains = chainCache.get(slashName);
		if (chains != null) {
			List<List<PropertyCall>> methodChains = chains.get(methodKey);
			if (methodChains != null) {
				for (int i = 0; i < methodChains.size(); i++) {
					if (methodChains.get(i) == newChain || callsEqual(methodChains.get(i), newChain)) {
						newIdx = i;
						break;
					}
				}
			}
		}

		boolean anyInserted = false;
		for (Entry<Table, List<Cell<?>>> entry : anchorsByTable.entrySet()) {
			Table targetTable = entry.getKey();
			List<Cell<?>> tableAnchors = entry.getValue();

			Seq<Cell> initialCells = new Seq<>(targetTable.getCells());
			List<Cell<?>> orderedAnchors = new ArrayList<>();
			if (!tableAnchors.isEmpty()) {
				for (Cell<?> c : initialCells) {
					if (tableAnchors.contains(c)) orderedAnchors.add(c);
				}
			}

			boolean tableInserted = false;
			if (orderedAnchors.isEmpty()) {
				ArcReflectionAdapter.clearImplicitEndRow(targetTable);
				Cell<?> newCell = invoke(mh, targetTable, converted);
				if (newCell != null) {
					setupNewCellState(slashName, methodKey, newChain, newIdx, newCell, creator, existingIds);
					tableInserted = true;
				}
			} else {
				for (Cell<?> anchor : orderedAnchors) {
					Seq<Cell> currentCells = targetTable.getCells();
					int anchorIdx = currentCells.indexOf(anchor, true);
					if (anchorIdx < 0) continue;

					if (insertAfter) {
						if (anchorIdx + 1 < currentCells.size) {
							Cell<?> next = currentCells.get(anchorIdx + 1);
							if (isCellMatchingStation(next, slashName, methodKey, creator)) {
								continue;
							}
						}
						ArcReflectionAdapter.clearImplicitEndRow(targetTable);
						Cell<?> newCell = invoke(mh, targetTable, converted);
						if (newCell == null) continue;
						setupNewCellState(slashName, methodKey, newChain, newIdx, newCell, creator, existingIds);

						int curIdx = currentCells.indexOf(newCell, true);
						if (curIdx != -1 && curIdx != anchorIdx + 1) {
							currentCells.remove(newCell, true);
							currentCells.insert(anchorIdx + 1, newCell);
							moveChildElement(targetTable, newCell, anchorIdx + 1);
						}
						tableInserted = true;
					} else {
						if (anchorIdx > 0) {
							Cell<?> prev = currentCells.get(anchorIdx - 1);
							if (isCellMatchingStation(prev, slashName, methodKey, creator)) {
								continue;
							}
						}
						ArcReflectionAdapter.clearImplicitEndRow(targetTable);
						Cell<?> newCell = invoke(mh, targetTable, converted);
						if (newCell == null) continue;
						setupNewCellState(slashName, methodKey, newChain, newIdx, newCell, creator, existingIds);

						int curIdx = currentCells.indexOf(newCell, true);
						if (curIdx != -1 && curIdx != anchorIdx) {
							currentCells.remove(newCell, true);
							currentCells.insert(anchorIdx, newCell);
							moveChildElement(targetTable, newCell, anchorIdx);
						}
						tableInserted = true;
					}
				}
			}

			if (tableInserted) {
				repairTableGrid(targetTable);
				targetTable.invalidate();
				if (Core.app != null) Core.app.post(targetTable::invalidateHierarchy);
				anyInserted = true;
			}
		}

		return anyInserted;
	}

	private static void setupNewCellState(String slashName, String methodKey,
	                                      List<PropertyCall> newChain, int newIdx,
	                                      Cell<?> newCell, PropertyCall creator,
	                                      List<CellIdentity> existingIds) {
		CellIdentity newId = new CellIdentity(slashName,
			methodNameFromKey(methodKey), methodDescFromKey(methodKey),
			creator.line, creator.method,
			creator.helperClass, creator.helperMethod, creator.helperLine,
			creator.callOrdinal);

		Geometry16 snap = null;
		try {
			snap = new Geometry16();
			snap.set(newCell);
		} catch (Throwable ignored) { }

		CellState st = new CellState(newId, newIdx, new ArrayList<>(newChain));
		st.baseline = snap;
		st.snapshot = snap;

		synchronized (cellState) {
			cellState.put(newCell, st);
			registerCellLocked(newId, newCell, newIdx);
		}
		existingIds.add(newId);

		List<PropertyCall> props = new ArrayList<>(newChain.subList(1, newChain.size()));
		applyAllCalls(newCell, st, props);

		Table targetTable = newCell.getTable();
		if (targetTable != null) {
			ArcReflectionAdapter.ensureTableRows(targetTable,
				ArcReflectionAdapter.getCellRow(newCell) + 1);
		}
	}

	private static void moveChildElement(Table targetTable, Cell<?> newCell, int cellInsertIndex) {
		Element element = newCell.get();
		if (element == null) return;
		Seq<Cell> cells = targetTable.getCells();
		Seq<Element> children = targetTable.getChildren();
		children.remove(element, true);
		int elementInsertIndex = children.size;
		for (int i = cellInsertIndex + 1; i < cells.size; i++) {
			Cell<?> nextCell = cells.get(i);
			if (!nextCell.hasElement()) continue;
			int idx = children.indexOf(nextCell.get(), true);
			if (idx != -1) {
				elementInsertIndex = idx;
				break;
			}
		}
		children.insert(elementInsertIndex, element);
	}

	private static boolean isCellMatchingStation(Cell<?> cell, String slashName, String methodKey, PropertyCall creator) {
		if (cell == null) return false;
		synchronized (cellState) {
			CellState st = cellState.get(cell);
			if (st == null || st == IGNORED_STATE) return false;
			if (!st.id.hostClass.equals(slashName) || !methodKeyOf(st.id).equals(methodKey)) return false;
			if (!matchesSite(st.id, creator)) return false;
			if (st.chain != null && !st.chain.isEmpty()) {
				return st.chain.get(0).callOrdinal == creator.callOrdinal;
			}
			return matchesIdentity(st.id, creator);
		}
	}

	private static Cell<?> firstAliveCell(CellIdentity id) {
		if (id == IGNORED_STATE.id) return null;
		synchronized (cellState) {
			Set<Cell<?>> s = idToCells.get(id);
			if (s == null) return null;
			for (Cell<?> c : s) {
				if (c != null && c.getTable() != null && c.get() != null) return c;
			}
		}
		return null;
	}

	public static List<Cell<?>> allAliveCells(CellIdentity id) {
		List<Cell<?>> res = new ArrayList<>();
		if (id == null || id == IGNORED_STATE.id) return res;
		synchronized (cellState) {
			Set<Cell<?>> s = idToCells.get(id);
			if (s == null) return res;
			for (Cell<?> c : s) {
				if (c != null && c.getTable() != null && c.get() != null) res.add(c);
			}
		}
		return res;
	}
	//endregion

	//region 属性与子元素更新

	/**
	 * 应用新链。
	 * @return 该 Cell 现在实际对应的链（作为下一次热重载的旧基线）。
	 * - 全部应用成功：返回 newChain。
	 * - 部分应用（有未知参数）或 dryRun 失败：返回 baseline（creator 新 + 属性旧），
	 * 下一次热重载会重新尝试。
	 */
	private static List<PropertyCall> applyChainUpdate(Cell<?> cell, List<PropertyCall> oldChain,
	                                                   List<PropertyCall> newChain,
	                                                   boolean templateUpdated) {
		CellState state;
		synchronized (cellState) {
			state = cellState.get(cell);
		}

		if (oldChain == null || oldChain.isEmpty()) {
			if (!newChain.isEmpty() && isCreatorCall(newChain.get(0)) && !isTemplateCreator(newChain.get(0))) {
				if (cell.get() == null) {
					updateChildElement(cell, newChain.get(0));
				} else if (newChain.get(0).args != null && newChain.get(0).args.length > 0
				           && newChain.get(0).args[0] instanceof String newText) {
					if (cell.get() instanceof Label label) {
						label.setText(newText);
					} else if (cell.get() instanceof TextButton button) {
						button.setText(newText);
					}
				}
			}
			List<PropertyCall> props = newChain.subList(newChain.isEmpty() ? 0 : 1, newChain.size());
			applyAllCalls(cell, state, props);
			return newChain;
		}

		PropertyCall oldCreator = oldChain.get(0);
		PropertyCall newCreator = newChain.get(0);

		boolean elementUpdated = false;
		if (isCreatorCall(newCreator) && !isTemplateCreator(newCreator)) {
			if (!Objects.equals(oldCreator.owner, newCreator.owner)
			    || !oldCreator.method.equals(newCreator.method)
			    || !argsEqual(oldCreator.args, newCreator.args)) {
				if (hasUsableCreatorArgs(newCreator)) {
					updateChildElement(cell, newCreator);
					elementUpdated = true;
					if (state != null) {
						state.slotListeners.clear();
					}
				} else if (DEBUG) {
					log("[CellProperty] Creator changed but creator args dynamic; retaining element and updating properties");
				}
			}
		}

		List<PropertyCall> oldProps     = new ArrayList<>(oldChain.subList(1, oldChain.size()));
		List<PropertyCall> newProps     = new ArrayList<>(newChain.subList(1, newChain.size()));
		boolean            propsChanged = !callsEqual(oldProps, newProps);

		if (!elementUpdated && !propsChanged && !templateUpdated) return newChain;

		List<PropertyCall> baseline = new ArrayList<>();
		baseline.add(newCreator);
		baseline.addAll(oldProps);

		boolean allUsable = true;
		for (PropertyCall p : newProps) {
			if (!hasUsablePropertyArgs(p)) {
				allUsable = false;
				break;
			}
		}

		if (!allUsable) {
			// 部分应用：默认只补"新增或改变且参数已知"的调用，不动旧属性。
			//
			// 但 elementUpdated 时旧子元素已被 BindCell.replace 换掉，挂在旧元素上的回调
			// （update/disabled/tooltip/checked）与文本随替换一起丢失；此时只补"新变化"不足，
			// 未变化的回调不会被重新挂到新元素上。故 elementUpdated 时把参数已知的新属性整体重放
			// （几何在 Cell 上幂等，回调在新元素上是首次挂载），未知参数的属性仍只能放弃。
			for (int i = 0; i < newProps.size(); i++) {
				PropertyCall p = newProps.get(i);
				if (!hasUsablePropertyArgs(p)) continue;
				if (elementUpdated || templateUpdated || !containsCall(oldProps, p)) {
					applyPropertyCallWithTracking(cell, state, i + 1, p);
				}
			}
			return baseline;
		}

		if (!dryRunProperties(newProps)) return baseline;

		boolean touchesRow  = chainTouchesRow(oldProps) || chainTouchesRow(newProps);
		boolean savedEndRow = !touchesRow && ArcReflectionAdapter.isEndRow(cell);

		cleanupAdditiveListeners(cell, state);
		resetCell(cell, state);
		applyAllCalls(cell, state, newProps);

		if (!touchesRow) ArcReflectionAdapter.setEndRow(cell, savedEndRow);
		if (templateUpdated && cell.getTable() != null) {
			try {
				cell.getTable().invalidate();
			} catch (Throwable ignored) { }
		}
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

	private static void cleanupAdditiveListeners(Cell<?> cell, CellState state) {
		if (state == null || cell == null) return;
		Element elem = cell.get();
		if (elem != null && !state.slotListeners.isEmpty()) {
			for (WeakReference<EventListener> ref : state.slotListeners.values()) {
				EventListener l = ref != null ? ref.get() : null;
				if (l != null) {
					try {
						elem.removeListener(l);
					} catch (Throwable ignored) { }
				}
			}
		}
		state.slotListeners.clear();
	}

	private static void applyPropertyCallWithTracking(Cell<?> cell, CellState state, int slot, PropertyCall call) {
		if (state != null) {
			WeakReference<EventListener> oldRef = state.slotListeners.remove(slot);
			if (oldRef != null) {
				EventListener oldL = oldRef.get();
				if (oldL != null && cell.get() != null) {
					try {
						cell.get().removeListener(oldL);
					} catch (Throwable ignored) { }
				}
			}
		}
		Element elem = cell.get();
		int countBefore = (elem != null && elem.getListeners() != null) ? elem.getListeners().size : -1;
		invokeCellMethod(cell, call.method, call.desc, call.args);
		if ("tooltip".equals(call.method) && state != null && elem != null && elem.getListeners() != null) {
			if (elem.getListeners().size > countBefore && elem.getListeners().size > 0) {
				state.slotListeners.put(slot, new WeakReference<>(elem.getListeners().peek()));
			}
		}
	}

	private static void applyAllCalls(Cell<?> cell, CellState state, List<PropertyCall> calls) {
		for (int i = 0; i < calls.size(); i++) {
			PropertyCall call = calls.get(i);
			if (hasUsableArgs(call)) applyPropertyCallWithTracking(cell, state, i + 1, call);
		}
	}

	private static boolean dryRunProperties(List<PropertyCall> calls) {
		Table   dummyTable = new Table();
		Cell<?> dummyCell  = dummyTable.add(new Element());
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

	private static void resetCell(Cell<?> cell, CellState state) {
		if (state != null && state.snapshot != null) {
			cell.set(state.snapshot);
		} else if (state != null && state.baseline != null) {
			cell.set(state.baseline);
		} else {
			cell.set(Cell.defaults());
		}
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

		if (!hasUsableCreatorArgs(creator)) {
			if (DEBUG) log("[CellProperty] Skipping rebuild with unconstructible args: " + creator.method);
			return;
		}

		Table table = cell.getTable();
		if (table == null) return;

		try {
			MethodType   type = MethodType.fromMethodDescriptorString(creator.desc, getTargetLoader());
			MethodHandle mh   = findTableMethod(creator.owner, creator.method, type);
			if (mh == null) return;

			Table    dummyTable = new Table();
			Object[] converted  = creator.args == null ? null : convertArgs(cell, table, type, creator.args);
			Cell<?>  dummyCell  = invoke(mh, dummyTable, converted);
			if (dummyCell == null || dummyCell.get() == null) return;

			Element  newElement = dummyCell.get();
			BindCell bind       = BindCell.of(cell);
			bind.replace(newElement, false);
			Pools.free(bind);
		} catch (Throwable e) {
			error("[CellProperty] Failed to update child element: " + creator.method, e);
		}
	}

	private static MethodHandle findTableMethod(String owner, String name, MethodType methodType) {
		try {
			Class<?> ownerClass = Table.class;
			if (owner != null && !CL_TABLE.equals(owner)) {
				try {
					ownerClass = Class.forName(owner.replace('/', '.'), false, getTargetLoader());
				} catch (Throwable ignored) {
					ownerClass = Table.class;
				}
			}
			return lookup().findVirtual(ownerClass, name, methodType);
		} catch (Throwable ignored) {
			try {
				return lookup().findVirtual(Table.class, name, methodType);
			} catch (Throwable ignored2) {
				return null;
			}
		}
	}

	private static boolean callsEqual(List<PropertyCall> a, List<PropertyCall> b) {
		if (a == b) return true;
		if (a == null || b == null) return false;
		if (a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) {
			PropertyCall ca = a.get(i), cb = b.get(i);
			if (!Objects.equals(ca.owner, cb.owner)) return false;
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

	public static boolean hasUsableCreatorArgs(PropertyCall creator) {
		if (creator == null) return false;
		if (creator.args == null) return true;
		for (Object a : creator.args) {
			if (a == null) return false;
			if (a instanceof LambdaInfo) return false;
			if (a instanceof DynamicArg) return false;
		}
		return true;
	}

	public static boolean hasUsablePropertyArgs(PropertyCall call) {
		return hasUsableArgs(call);
	}

	private static boolean hasUsableArgs(PropertyCall call) {
		return hasUsableValue(call.args);
	}

	private static boolean hasUsableValue(Object[] arr) {
		if (arr == null) return true;
		for (Object a : arr) {
			if (a == null) return false;
			if (a instanceof DynamicArg da && da.runtimeValue() == null) return false;
			if (a instanceof LambdaInfo li && !hasUsableValue(li.captures())) return false;
		}
		return true;
	}

	private static void invokeCellMethod(Cell<?> cell, String methodName, String methodDesc, Object[] args) {
		try { invokeCellMethodOrThrow(cell, methodName, methodDesc, args); } catch (Throwable e) {
			error("[CellProperty] Failed to invoke " + methodName, e);
		}
	}

	private static void invokeCellMethodOrThrow(Cell<?> cell, String methodName,
	                                            String methodDesc, Object[] args) throws Throwable {
		int len = args == null ? 0 : args.length;
		if ("row".equals(methodName) && len == 0) {
			ArcReflectionAdapter.setEndRow(cell, true);
			return;
		}
		MethodType   type = MethodType.fromMethodDescriptorString(methodDesc, getTargetLoader());
		MethodHandle mh   = findMatchingMethod(methodName, type);
		if (mh == null) throw new NoSuchMethodException(methodName + "(" + len + " args)");

		Object[] converted    = convertArgs(cell, cell != null ? cell.getTable() : null, type, args);
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
		try { return lookup().findVirtual(Cell.class, name, methodType); } catch (Throwable ignored) { }
		try { return lookup().findVirtual(Table.class, name, methodType); } catch (Throwable ignored) { }
		return null;
	}

	private static Object resolveStaticRef(StaticRef sr) {
		if (sr == null) return null;
		try {
			ClassLoader cl = getTargetLoader();
			Class<?> clazz = Class.forName(sr.owner().replace('/', '.'), false, cl);
			Field f = clazz.getDeclaredField(sr.name());
			f.setAccessible(true);
			return f.get(null);
		} catch (Throwable t) {
			if (DEBUG) log("[CellProperty] Failed to resolve StaticRef " + sr + ": " + t.getMessage());
			return null;
		}
	}

	private static Object resolveThisPlaceholder(ThisPlaceholder tp, Class<?> targetType, Cell<?> cell, Table table) {
		if (tp == null) return null;
		Table rootTable = table != null ? table : (cell != null ? cell.getTable() : null);
		if (rootTable == null) return null;

		// 1. 优先按 hostClass 沿 parent 链向上查找，支持子类 (isAssignableFrom)
		if (tp.hostClass() != null) {
			String hostClassDots = tp.hostClass().replace('/', '.');
			Class<?> hostClazz = null;
			try {
				hostClazz = loadClass(hostClassDots);
			} catch (Throwable ignored) {}

			for (Element curr = rootTable; curr != null; curr = curr.parent) {
				boolean matches = false;
				if (hostClazz != null) {
					matches = hostClazz.isInstance(curr);
				} else {
					for (Class<?> c = curr.getClass(); c != null; c = c.getSuperclass()) {
						if (c.getName().equals(hostClassDots)) {
							matches = true;
							break;
						}
					}
				}
				if (matches && (targetType == null || targetType == Object.class || targetType.isInstance(curr))) {
					return curr;
				}
			}
			return null;
		}

		// 2. 未指定 hostClass 时，按 targetType 沿 parent 链寻找
		if (targetType != null && targetType != Object.class) {
			for (Element curr = rootTable; curr != null; curr = curr.parent) {
				if (targetType.isInstance(curr)) return curr;
			}
		}

		return null;
	}

	private static Object[] convertArgs(Cell<?> contextCell, Table contextTable, MethodType methodType, Object[] args) {
		if (args == null) return null;
		Object[] result = new Object[args.length];
		for (int i = 0; i < args.length; i++) {
			Object arg = args[i];
			if (arg instanceof DynamicArg da) {
				arg = da.runtimeValue();
			}
			if (i >= methodType.parameterCount()) {
				result[i] = arg;
				continue;
			}
			Class<?> target = methodType.parameterType(i);
			if (arg instanceof StaticRef sr) {
				Object resolved = resolveStaticRef(sr);
				if (resolved != null) {
					arg = resolved;
				} else if (target.isInterface()) {
					result[i] = makeDummyProxy(target);
					continue;
				} else {
					result[i] = defaultValue(target);
					continue;
				}
			}
			if (arg instanceof ThisPlaceholder tp) {
				Object resolved = resolveThisPlaceholder(tp, target, contextCell, contextTable);
				if (resolved != null) {
					arg = resolved;
				} else if (target.isInterface()) {
					result[i] = makeDummyProxy(target);
					continue;
				} else {
					result[i] = defaultValue(target);
					continue;
				}
			}
			if (arg == null) {
				result[i] = target.isInterface() ? makeDummyProxy(target) : defaultValue(target);
				continue;
			}
			if (arg instanceof LambdaInfo li) {
				result[i] = makeLambda(contextCell, contextTable, li, target);
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

	private static Object defaultValue(Class<?> t) {
		if (t == null || !t.isPrimitive() || t == void.class) return null;
		if (t == boolean.class) return false;
		if (t == byte.class) return (byte) 0;
		if (t == short.class) return (short) 0;
		if (t == char.class) return '\0';
		if (t == int.class) return 0;
		if (t == long.class) return 0L;
		if (t == float.class) return 0.0f;
		if (t == double.class) return 0.0d;
		return Array.get(Array.newInstance(t, 1), 0);
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
			 return defaultValue(method.getReturnType());
		 });
	}

	private static Object makeLambda(Cell<?> cell, Table table, LambdaInfo li, Class<?> target) {
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
				 boolean      isStatic = false;
				 MethodHandle handle   = null;
				 switch (li.tag()) {
					 case H_INVOKESTATIC -> {
						 try { handle = lookup().findStatic(owner, li.methodName(), type); isStatic = true; } catch (Throwable ignored) {}
					 }
					 case H_NEWINVOKESPECIAL -> {
						 try { handle = lookup().findConstructor(owner, type); isStatic = true; } catch (Throwable ignored) {}
					 }
					 case H_INVOKEVIRTUAL, H_INVOKEINTERFACE -> {
						 try { handle = lookup().findVirtual(owner, li.methodName(), type); isStatic = false; } catch (Throwable ignored) {}
					 }
					 case H_INVOKESPECIAL -> {
						 try { handle = lookup().findSpecial(owner, li.methodName(), type, owner); isStatic = false; }
						 catch (Throwable ignored) {
							 try { handle = lookup().findVirtual(owner, li.methodName(), type); isStatic = false; } catch (Throwable ignored2) {}
						 }
					 }
					 default -> {}
				 }
				 if (handle == null) {
					 try { handle = lookup().findStatic(owner, li.methodName(), type); isStatic = true; }
					 catch (Throwable ignored) {
						 try { handle = lookup().findVirtual(owner, li.methodName(), type); isStatic = false; }
						 catch (Throwable ignored2) {
							 try { handle = lookup().findSpecial(owner, li.methodName(), type, owner); isStatic = false; }
							 catch (Throwable ignored3) {
								 try { handle = lookup().findConstructor(owner, type); isStatic = true; }
								 catch (Throwable ignored4) {}
							 }
						 }
					 }
				 }
				 if (handle == null) return defaultValue(method.getReturnType());

				 Object[] caps = li.captures();
				 Object[] resolvedCaps = new Object[caps.length];
				 for (int c = 0; c < caps.length; c++) {
					 if (caps[c] instanceof StaticRef sr) {
						 resolvedCaps[c] = resolveStaticRef(sr);
					 } else if (caps[c] instanceof ThisPlaceholder tp) {
						 resolvedCaps[c] = resolveThisPlaceholder(tp, Object.class, cell, table);
					 } else {
						 resolvedCaps[c] = caps[c];
					 }
				 }
				 Object[] allArgs = new Object[resolvedCaps.length
				                               + (methodArgs == null ? 0 : methodArgs.length)];
				 System.arraycopy(resolvedCaps, 0, allArgs, 0, resolvedCaps.length);
				 if (methodArgs != null) {
					 System.arraycopy(methodArgs, 0, allArgs, resolvedCaps.length, methodArgs.length);
				 }

				 if (isStatic) return invoke(handle, allArgs);

				 Object instance = allArgs.length > 0 ? allArgs[0] : null;
				 if (instance == null) return defaultValue(method.getReturnType());
				 Object[] actual = new Object[Math.max(0, allArgs.length - 1)];
				 if (actual.length > 0) { System.arraycopy(allArgs, 1, actual, 0, actual.length); }
				 return invoke(handle, instance, actual);
			 } catch (LinkageError le) {
				 // 透传给外层 UpdateRef.Wrapped*，触发精准局部熔断；若在此吞掉，
				 // 被删除/改签名的旧方法会在 60FPS 热路径上每帧刷屏且熔断永不触发。
				 throw le;
			 } catch (Throwable t) {
				 error("[CellProperty] Lambda invocation failed", t);
				 return defaultValue(method.getReturnType());
			 }
		 });
	}
	//endregion

	//region 链移除

	private static void removeCellFromTable(Cell<?> cell) {
		Table table = cell.getTable();
		if (table != null) {
			try {
				boolean endRow = ArcReflectionAdapter.isEndRow(cell);
				int idx = table.getCells().indexOf(cell, true);
				if (endRow && idx > 0) {
					Cell<?> prev = table.getCells().get(idx - 1);
					ArcReflectionAdapter.setEndRow(prev, true);
				}

				BindCell bind = BindCell.of(cell);
				bind.remove();
				Pools.free(bind);
				table.getCells().remove(cell, true);

				ArcReflectionAdapter.repairTableGrid(table);
				table.invalidate();
				if (Core.app != null) {
					Core.app.post(table::invalidateHierarchy);
				}
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
			Table table = cell.getTable();
			if (table != null && (table.getCells() == null || table.getCells().isEmpty())) {
				tableSiteOrdinals.remove(table);
			}
		}
	}
	//endregion

	//region ASM 字节码分析

	private static final Set<String> CELL_PROPERTY_METHODS = ConcurrentHashMap.newKeySet();
	private static final Set<String> TABLE_CELL_CREATORS    = ConcurrentHashMap.newKeySet();
	private static final Set<String> PURE_FUNCTIONS         = ConcurrentHashMap.newKeySet();

	public static final String ANNOTATION_CELL_HELPER = "Lnipx/annotation/CellHelper;";
	private static final Set<String> MANUAL_WHOLE_CLASS_HELPERS = ConcurrentHashMap.newKeySet();
	private static final Map<String, Set<String>> MANUAL_HELPER_METHODS_BY_CLASS = new ConcurrentHashMap<>();
	private static final Set<String> WHOLE_CLASS_HELPERS    = ConcurrentHashMap.newKeySet();
	private static final Map<String, Set<String>> HELPER_METHODS_BY_CLASS = new ConcurrentHashMap<>();
	private static final Set<String> SCANNED_HELPER_CLASSES = ConcurrentHashMap.newKeySet();

	static {
		CELL_PROPERTY_METHODS.addAll(Arrays.asList(
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
		 "wrap", "wrapLabel", "ellipsis", "labelAlign", "fontScale",
		 "scrollX", "scrollY", "maxTextLength", "valid",
		 "tooltip", "style", "checked", "update"
		));

		TABLE_CELL_CREATORS.addAll(Arrays.asList(
		 "add", "button", "image", "label", "textButton",
		 "imageButton", "area", "table", "pane", "stack",
		 "toggleButton", "imageTextButton", "checkBox", "slider",
		 "textField", "selectBox", "list", "tree",
		 "field", "check", "buttonRow", "buttonCenter", "collapser",
		 "wrapTable", "labelWrap", "spacer", "spacerX", "spacerY",
		 "imageDraw", "rect"
		));

		initTableCellCreators();
		initCellPropertyMethods();
		initPureFunctions();
	}

	private static void initTableCellCreators() {
		try {
			for (Method m : Table.class.getMethods()) {
				if (Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers())) {
					if (Cell.class.isAssignableFrom(m.getReturnType())) {
						String name = m.getName();
						if (!"defaults".equals(name) && !"getCell".equals(name)
						    && !"obtainCell".equals(name) && !"clearChildren".equals(name)
						    && !"removeChild".equals(name)) {
							TABLE_CELL_CREATORS.add(name);
						}
					}
				}
			}
		} catch (Throwable ignored) { }
	}

	private static void initCellPropertyMethods() {
		try {
			for (Method m : Cell.class.getMethods()) {
				if (Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers())) {
					String name = m.getName();
					if ("defaults".equals(name) || "reset".equals(name) || "set".equals(name)
					    || "clearElement".equals(name) || "setElement".equals(name)
					    || "setLayout".equals(name) || "setBounds".equals(name)
					    || "self".equals(name) || "group".equals(name) || "with".equals(name)
					    || "get".equals(name) || "getElement".equals(name) || "getTable".equals(name)) {
						continue;
					}
					if (Cell.class.isAssignableFrom(m.getReturnType()) || "row".equals(name)) {
						CELL_PROPERTY_METHODS.add(name);
					}
				}
			}
		} catch (Throwable ignored) { }
	}

	public static void registerCreator(String name) {
		if (name != null && !name.isEmpty()) TABLE_CELL_CREATORS.add(name);
	}

	public static void registerProperty(String name) {
		if (name != null && !name.isEmpty()) CELL_PROPERTY_METHODS.add(name);
	}

	private static void initPureFunctions() {
		registerPureFunction("java/lang/Math", "max");
		registerPureFunction("java/lang/Math", "min");
		registerPureFunction("java/lang/Math", "abs");
		registerPureFunction("java/lang/Math", "round");
		registerPureFunction("java/lang/Math", "floor");
		registerPureFunction("java/lang/Math", "ceil");
		registerPureFunction("java/lang/Math", "sqrt");
		registerPureFunction("java/lang/Math", "pow");
		registerPureFunction("java/lang/Math", "sin");
		registerPureFunction("java/lang/Math", "cos");
		registerPureFunction("java/lang/Math", "tan");
		registerPureFunction("java/lang/Math", "atan2");
		registerPureFunction("java/lang/Math", "log");
		registerPureFunction("java/lang/Math", "log10");
		registerPureFunction("java/lang/Math", "exp");
		registerPureFunction("java/lang/Math", "signum");
	}

	public static void registerPureFunction(String owner, String name) {
		if (owner != null && name != null) {
			PURE_FUNCTIONS.add(owner.replace('.', '/') + "#" + name);
		}
	}

	public static void registerPureFunction(String owner, String name, String desc) {
		if (owner != null && name != null) {
			String key = owner.replace('.', '/') + "#" + name;
			if (desc != null && !desc.isEmpty()) key += ":" + desc;
			PURE_FUNCTIONS.add(key);
		}
	}

	private static boolean isPureFunction(String owner, String name, String desc) {
		if (owner == null || name == null) return false;
		String base = owner + "#" + name;
		if (PURE_FUNCTIONS.contains(base)) return true;
		return desc != null && PURE_FUNCTIONS.contains(base + ":" + desc);
	}

	private static Object evaluatePureFunction(String owner, String name, String desc, Object[] args) {
		try {
			Class<?> clazz = "java/lang/Math".equals(owner)
			 ? Math.class
			 : Class.forName(owner.replace('/', '.'), false, getTargetLoader());
			MethodType mt = MethodType.fromMethodDescriptorString(desc, getTargetLoader());
			MethodHandle mh = lookup().findStatic(clazz, name, mt);
			Object res = mh.invokeWithArguments(args);
			return isConstantType(res) ? res : null;
		} catch (Throwable t) {
			if (DEBUG) log("[CellProperty] Failed to evaluate pure function " + owner + "." + name + ": " + t.getMessage());
			return null;
		}
	}

	private static byte[] fetchBytecode(String slashName) {
		try {
			Class<?> clazz = Class.forName(slashName.replace('/', '.'), false, getTargetLoader());
			return fetchCurrentBytecode(clazz);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static void scanHelpers(String slashName) {
		if (slashName == null || !SCANNED_HELPER_CLASSES.add(slashName)) return;
		try {
			byte[] bytes = fetchBytecode(slashName);
			if (bytes == null) return;
			ClassReader cr = new ClassReader(bytes);
			ClassVisitor cv = new ClassVisitor(ASM9) {
				private boolean classIsHelper = false;

				@Override
				public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
					if (ANNOTATION_CELL_HELPER.equals(descriptor)) {
						classIsHelper = true;
						WHOLE_CLASS_HELPERS.add(slashName);
					}
					return super.visitAnnotation(descriptor, visible);
				}

				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor,
				                                 String signature, String[] exceptions) {
					if (classIsHelper) {
						Set<String> set = HELPER_METHODS_BY_CLASS.computeIfAbsent(slashName, k -> ConcurrentHashMap.newKeySet());
						set.add(name);
						set.add(name + descriptor);
						return null;
					}
					return new MethodVisitor(ASM9) {
						@Override
						public AnnotationVisitor visitAnnotation(String annDesc, boolean visible) {
							if (ANNOTATION_CELL_HELPER.equals(annDesc)) {
								Set<String> set = HELPER_METHODS_BY_CLASS.computeIfAbsent(slashName, k -> ConcurrentHashMap.newKeySet());
								set.add(name);
								set.add(name + descriptor);
							}
							return super.visitAnnotation(annDesc, visible);
						}
					};
				}
			};
			cr.accept(cv, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		} catch (Throwable t) {
			if (DEBUG) log("[CellProperty] Failed to scan helpers for " + slashName + ": " + t.getMessage());
		}
	}

	public static boolean isHelper(String owner, String name, String desc) {
		if (owner == null) return false;
		String slashOwner = owner.replace('.', '/');
		if (isLibraryClass(slashOwner)) return false;
		if (MANUAL_WHOLE_CLASS_HELPERS.contains(slashOwner) || WHOLE_CLASS_HELPERS.contains(slashOwner)) return true;
		int dollar = slashOwner.indexOf('$');
		if (dollar > 0 && (MANUAL_WHOLE_CLASS_HELPERS.contains(slashOwner.substring(0, dollar))
		                   || WHOLE_CLASS_HELPERS.contains(slashOwner.substring(0, dollar)))) return true;

		Set<String> manualMethods = MANUAL_HELPER_METHODS_BY_CLASS.get(slashOwner);
		if (manualMethods != null && (manualMethods.contains(name) || (desc != null && manualMethods.contains(name + desc)))) {
			return true;
		}
		Set<String> methods = HELPER_METHODS_BY_CLASS.get(slashOwner);
		if (methods != null && (methods.contains(name) || (desc != null && methods.contains(name + desc)))) {
			return true;
		}
		if (!SCANNED_HELPER_CLASSES.contains(slashOwner)) {
			scanHelpers(slashOwner);
			if (dollar > 0 && !SCANNED_HELPER_CLASSES.contains(slashOwner.substring(0, dollar))) {
				scanHelpers(slashOwner.substring(0, dollar));
			}
			if (MANUAL_WHOLE_CLASS_HELPERS.contains(slashOwner) || WHOLE_CLASS_HELPERS.contains(slashOwner)) return true;
			if (dollar > 0 && (MANUAL_WHOLE_CLASS_HELPERS.contains(slashOwner.substring(0, dollar))
			                   || WHOLE_CLASS_HELPERS.contains(slashOwner.substring(0, dollar)))) return true;
			manualMethods = MANUAL_HELPER_METHODS_BY_CLASS.get(slashOwner);
			if (manualMethods != null && (manualMethods.contains(name) || (desc != null && manualMethods.contains(name + desc)))) {
				return true;
			}
			methods = HELPER_METHODS_BY_CLASS.get(slashOwner);
			return methods != null && (methods.contains(name) || (desc != null && methods.contains(name + desc)));
		}
		return false;
	}

	public static void registerHelper(String owner) {
		if (owner != null && !owner.isEmpty()) {
			MANUAL_WHOLE_CLASS_HELPERS.add(owner.replace('.', '/'));
		}
	}

	public static void registerHelper(String owner, String name) {
		registerHelper(owner, name, null);
	}

	public static void registerHelper(String owner, String name, String desc) {
		if (owner != null && name != null) {
			String key = (desc != null && !desc.isEmpty()) ? name + desc : name;
			MANUAL_HELPER_METHODS_BY_CLASS.computeIfAbsent(owner.replace('.', '/'), k -> ConcurrentHashMap.newKeySet()).add(key);
		}
	}

	public static Map<String, List<List<PropertyCall>>> extractCellChains(byte[] bytecode) {
		Map<String, List<List<PropertyCall>>> result = new HashMap<>();
		if (bytecode == null) return result;
		try {
			ClassReader cr = new ClassReader(bytecode);
			ClassNode   cn = new ClassNode();
			cr.accept(cn, ClassReader.SKIP_FRAMES);
			AnnotationTransformer.HierarchyTree.register(cn);
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

	private static ClassLoader getTargetLoader() {
		try {
			if (Vars.mods != null && Vars.mods.mainLoader() != null) {
				return Vars.mods.mainLoader();
			}
		} catch (Throwable ignored) { }
		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		return cl != null ? cl : HotSwapAgent.class.getClassLoader();
	}

	private static ClassHierarchyOracle.HierarchyCheck checkTableClass(ClassNode cn, String owner) {
		if (CL_TABLE.equals(owner)) return ClassHierarchyOracle.HierarchyCheck.YES;
		if (owner == null) return ClassHierarchyOracle.HierarchyCheck.NO;
		if (cn != null && cn.name.equals(owner)) {
			AnnotationTransformer.HierarchyTree.register(cn);
		}
		return AnnotationTransformer.HierarchyTree.checkAssignableFrom(CL_TABLE, owner, getTargetLoader());
	}

	private static boolean isTableClass(ClassNode cn, String owner) {
		ClassHierarchyOracle.HierarchyCheck check = checkTableClass(cn, owner);
		if (check == ClassHierarchyOracle.HierarchyCheck.YES) return true;
		if (check == ClassHierarchyOracle.HierarchyCheck.NO) return false;
		// UNKNOWN: 退回精确匹配
		return CL_TABLE.equals(owner);
	}

	private static ClassHierarchyOracle.HierarchyCheck checkCellClass(ClassNode cn, String owner) {
		if (CL_CELL.equals(owner)) return ClassHierarchyOracle.HierarchyCheck.YES;
		if (owner == null) return ClassHierarchyOracle.HierarchyCheck.NO;
		if (cn != null && cn.name.equals(owner)) {
			AnnotationTransformer.HierarchyTree.register(cn);
		}
		return AnnotationTransformer.HierarchyTree.checkAssignableFrom(CL_CELL, owner, getTargetLoader());
	}

	private static boolean isCellClass(ClassNode cn, String owner) {
		ClassHierarchyOracle.HierarchyCheck check = checkCellClass(cn, owner);
		if (check == ClassHierarchyOracle.HierarchyCheck.YES) return true;
		if (check == ClassHierarchyOracle.HierarchyCheck.NO) return false;
		// UNKNOWN: 退回精确匹配
		return CL_CELL.equals(owner);
	}


	public static boolean isTemplateCreator(String methodName) {
		return "defaults".equals(methodName);
	}

	public static boolean isTemplateCreator(PropertyCall call) {
		return call != null && isTemplateCreator(call.method);
	}

	private static boolean isTableCellCreator(ClassNode cn, String owner, String name) {
		return isTableClass(cn, owner) && (TABLE_CELL_CREATORS.contains(name) || isTemplateCreator(name));
	}

	private static boolean isTableCellCreator(String owner, String name) {
		return isTableCellCreator(null, owner, name);
	}

	private static boolean isCreatorCall(PropertyCall pc) {
		if (pc == null) return false;
		return isTableCellCreator(pc.owner, pc.method);
	}

	public static List<List<PropertyCall>> findTemplateChains(List<List<PropertyCall>> chains) {
		List<List<PropertyCall>> tpls = new ArrayList<>();
		if (chains != null) {
			for (List<PropertyCall> c : chains) {
				if (!c.isEmpty() && isTemplateCreator(c.get(0))) {
					tpls.add(c);
				}
			}
		}
		return tpls;
	}

	public static List<PropertyCall> findActiveTemplate(List<List<PropertyCall>> templateChains, int cellLine) {
		if (templateChains == null || templateChains.isEmpty()) return null;
		List<PropertyCall> active = null;
		for (List<PropertyCall> tpl : templateChains) {
			if (tpl == null || tpl.isEmpty()) continue;
			PropertyCall head = tpl.get(0);
			if (cellLine < 0 || head.line <= cellLine) {
				active = tpl;
			} else {
				break;
			}
		}
		if (active == null && templateChains.size() == 1) {
			return templateChains.get(0);
		}
		return active;
	}

	public static Geometry16 evaluateTemplateGeometry(List<PropertyCall> templateChain) {
		Geometry16 result = new Geometry16();
		if (templateChain == null || templateChain.size() <= 1) {
			result.set(Cell.defaults());
			return result;
		}
		try {
			Table   dummyTable = new Table();
			Cell<?> dummyCell  = dummyTable.defaults();
			for (int i = 1; i < templateChain.size(); i++) {
				PropertyCall call = templateChain.get(i);
				if (!hasUsablePropertyArgs(call)) continue;
				invokeCellMethod(dummyCell, call.method, call.desc, call.args);
			}
			result.set(dummyCell);
		} catch (Throwable t) {
			if (DEBUG) log("[CellProperty] Failed to evaluate template geometry: " + t.getMessage());
			result.set(Cell.defaults());
		}
		return result;
	}

	private static boolean updateCellTemplateIfChanged(Cell<?> cell, CellIdentity id,
	                                                   PropertyCall newCreator,
	                                                   String oldMk, String newMk,
	                                                   Map<String, List<List<PropertyCall>>> oldTemplatesByMethod,
	                                                   Map<String, List<List<PropertyCall>>> newTemplatesByMethod) {
		List<PropertyCall> oldTpl = findActiveTemplate(oldTemplatesByMethod.get(oldMk), id.line);
		List<PropertyCall> newTpl = findActiveTemplate(newTemplatesByMethod.get(newMk), newCreator != null ? newCreator.line : id.line);
		boolean templateChanged = false;
		if (oldTpl == null && newTpl != null) {
			templateChanged = true;
		} else if (oldTpl != null && newTpl == null) {
			templateChanged = true;
		} else if (oldTpl != null && newTpl != null) {
			templateChanged = !callsEqual(oldTpl, newTpl);
		}

		if (templateChanged) {
			Geometry16 newSnap = evaluateTemplateGeometry(newTpl);
			synchronized (cellState) {
				CellState st = cellState.get(cell);
				if (st != null) {
					st.snapshot = newSnap;
				}
			}
			Table tbl = cell.getTable();
			if (tbl != null) {
				try {
					Cell<?> def = tbl.defaults();
					if (def != null && newSnap != null) {
						def.set(newSnap);
					}
				} catch (Throwable ignored) { }
			}
		}
		return templateChanged;
	}

	private static boolean isCellProperty(ClassNode cn, String owner, String name, String desc) {
		if (isCellClass(cn, owner) && CELL_PROPERTY_METHODS.contains(name)
		    && (desc.endsWith(")L" + CL_CELL + ";") || "()V".equals(desc) && "row".equals(name))) {
			return true;
		}
		return isTableClass(cn, owner) && CELL_PROPERTY_METHODS.contains(name);
	}

	/** 先扫站点，扫不到就不 analyze（大多数方法没有 Cell 调用，这一步省掉 Analyzer 开销）。 */
	private static MethodExtraction extractFromMethod(ClassNode cn, MethodNode mn) {
		return extractFromMethod(cn, mn, null, null, null, -1, new HashSet<>());
	}

	private static MethodExtraction extractFromMethod(ClassNode cn, MethodNode mn,
	                                                  Map<Integer, Object> paramBindings,
	                                                  String helperOwner, String helperName, int callerLine,
	                                                  Set<String> expandingHelpers) {
		List<List<PropertyCall>> chains = new ArrayList<>();
		if (mn.instructions == null || mn.instructions.size() == 0) { return new MethodExtraction(true, chains); }

		List<MethodInsnNode> cellCallSites   = new ArrayList<>();
		List<MethodInsnNode> helperCallSites = new ArrayList<>();
		for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof MethodInsnNode mi)) continue;
			if (isHelper(mi.owner, mi.name, mi.desc)) {
				helperCallSites.add(mi);
				continue;
			}
			if (!returnsCell(mi)) continue;
			if (!isTableCellCreator(cn, mi.owner, mi.name)
			    && !isCellProperty(cn, mi.owner, mi.name, mi.desc)) { continue; }
			cellCallSites.add(mi);
		}
		if (cellCallSites.isEmpty() && helperCallSites.isEmpty()) return new MethodExtraction(true, chains);

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
				Object[] args = extractArgsDataflow(cn, mn, frames, site, paramBindings);
				int l = lineOf(mn, site);
				if (helperOwner != null) {
					chain.add(new PropertyCall(site.owner, site.name, site.desc, args, callerLine,
					                           helperOwner, helperName, l, 0));
				} else {
					chain.add(new PropertyCall(site.owner, site.name, site.desc, args, l));
				}
			}
			if (!chain.isEmpty()) chains.add(chain);
		}

		for (MethodInsnNode helperSite : helperCallSites) {
			int hLine = lineOf(mn, helperSite);
			Object[] callerArgs = extractArgsDataflow(cn, mn, frames, helperSite, paramBindings);

			List<List<PropertyCall>> inlinedChains = expandHelper(
				cn, mn, helperSite.owner, helperSite.name, helperSite.desc,
				callerArgs, hLine, expandingHelpers
			);
			if (inlinedChains == null || inlinedChains.isEmpty()) {
				// Unexpandable helper: skip, will be recorded in ledger (unpatchedCells) at runtime
				continue;
			}

			// Chained calls on the Cell returned by this helper: e.g. Underline.of(t, 3).pad(5)
			List<MethodInsnNode> chainedOnHelper = new ArrayList<>();
			for (MethodInsnNode site : cellCallSites) {
				if (creatorOf.get(site) == helperSite) {
					chainedOnHelper.add(site);
				}
			}
			if (!chainedOnHelper.isEmpty()) {
				List<PropertyCall> lastInlined = inlinedChains.get(inlinedChains.size() - 1);
				for (MethodInsnNode outerCall : chainedOnHelper) {
					Object[] args = extractArgsDataflow(cn, mn, frames, outerCall, paramBindings);
					lastInlined.add(new PropertyCall(outerCall.owner, outerCall.name, outerCall.desc, args,
					                                 lineOf(mn, outerCall)));
				}
			}

			if (helperOwner != null) {
				for (List<PropertyCall> inlined : inlinedChains) {
					for (int i = 0; i < inlined.size(); i++) {
						PropertyCall pc = inlined.get(i);
						inlined.set(i, new PropertyCall(
							pc.owner, pc.method, pc.desc, pc.args, callerLine,
							pc.helperClass, pc.helperMethod, pc.helperLine, pc.callOrdinal
						));
					}
				}
			}

			chains.addAll(inlinedChains);
		}

		// 任务 18：按调用点行号排序，同行按 helperLine / 出现顺序分配 callOrdinal
		chains.sort((c1, c2) -> {
			PropertyCall h1 = c1.get(0);
			PropertyCall h2 = c2.get(0);
			return compareCellOrder(h1.line, h1.helperLine, 0,
			                        h2.line, h2.helperLine, 0);
		});

		Map<Integer, Integer> lineOrdinals = new HashMap<>();
		for (List<PropertyCall> chain : chains) {
			PropertyCall head = chain.get(0);
			int line = head.line;
			int ordinal = lineOrdinals.getOrDefault(line, 0);
			lineOrdinals.put(line, ordinal + 1);

			if (ordinal > 0) {
				for (int i = 0; i < chain.size(); i++) {
					PropertyCall pc = chain.get(i);
					chain.set(i, new PropertyCall(
						pc.owner, pc.method, pc.desc, pc.args, pc.line,
						pc.helperClass, pc.helperMethod, pc.helperLine, ordinal
					));
				}
			}
		}

		return new MethodExtraction(true, chains);
	}

	private static Map<Integer, Object> computeParamBindings(int access, String desc, Object[] callerArgs) {
		Map<Integer, Object> bindings = new HashMap<>();
		if (callerArgs == null) return bindings;
		Type[] argTypes = Type.getArgumentTypes(desc);
		int slot = (access & ACC_STATIC) != 0 ? 0 : 1;
		for (int i = 0; i < argTypes.length && i < callerArgs.length; i++) {
			bindings.put(slot, callerArgs[i]);
			slot += argTypes[i].getSize();
		}
		return bindings;
	}

	private static List<List<PropertyCall>> expandHelper(
		ClassNode callerCn, MethodNode callerMn,
		String helperOwner, String helperName, String helperDesc,
		Object[] callerArgs, int callerLine,
		Set<String> expandingHelpers
	) {
		String key = helperOwner + "#" + helperName + ":" + (helperDesc != null ? helperDesc : "");
		if (!expandingHelpers.add(key)) {
			// Task 17 condition 4: Recursion cycle detected -> unexpandable
			if (DEBUG) log("[CellProperty] Recursive helper cycle detected: " + key);
			return null;
		}
		try {
			byte[] bytes = fetchBytecode(helperOwner);
			if (bytes == null) return null;

			ClassReader cr = new ClassReader(bytes);
			ClassNode helperCn = new ClassNode();
			cr.accept(helperCn, ClassReader.SKIP_FRAMES);
			AnnotationTransformer.HierarchyTree.register(helperCn);

			MethodNode helperMn = null;
			for (MethodNode mn : helperCn.methods) {
				if (mn.name.equals(helperName) && (helperDesc == null || mn.desc.equals(helperDesc))) {
					helperMn = mn;
					break;
				}
			}
			if (helperMn == null || helperMn.instructions == null || helperMn.instructions.size() == 0) {
				return null;
			}

			// Task 17 condition 2: Check for branches (IF*, SWITCH, GOTO)
			for (AbstractInsnNode insn = helperMn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				int op = insn.getOpcode();
				if ((op >= IFEQ && op <= LOOKUPSWITCH)
				    || op == IFNULL
				    || op == IFNONNULL) {
					if (DEBUG) log("[CellProperty] Helper contains branch, rejecting: " + key);
					return null;
				}
			}

			// Task 17 condition 3: Check for arbitrary external method calls
			for (AbstractInsnNode insn = helperMn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode min) {
					if ("<init>".equals(min.name) && ("java/lang/Object".equals(min.owner) || CL_CELL.equals(min.owner))) {
						continue;
					}
					if (isTableCellCreator(helperCn, min.owner, min.name)) continue;
					if (isCellProperty(helperCn, min.owner, min.name, min.desc)) continue;
					if (isPureFunction(min.owner, min.name, min.desc)) continue;
					if (isHelper(min.owner, min.name, min.desc)) continue;
					if (DEBUG) log("[CellProperty] Helper calls non-helper external method, rejecting: " + min.owner + "#" + min.name);
					return null;
				}
			}

			Map<Integer, Object> bindings = computeParamBindings(helperMn.access, helperMn.desc, callerArgs);

			MethodExtraction ex = extractFromMethod(
				helperCn, helperMn, bindings, helperOwner, helperName, callerLine, expandingHelpers
			);
			if (!ex.analyzed() || ex.chains().isEmpty()) return null;

			// Task 17 condition 1: Verify all required parameters were inferred
			for (List<PropertyCall> chain : ex.chains()) {
				for (PropertyCall pc : chain) {
					if (pc.args != null) {
						for (Object arg : pc.args) {
							if (arg == null) {
								if (DEBUG) log("[CellProperty] Helper property arg unresolvable, rejecting: " + pc.method);
								return null;
							}
						}
					}
				}
			}

			return ex.chains();
		} catch (Throwable t) {
			if (DEBUG) log("[CellProperty] Failed to expand helper " + key + ": " + t.getMessage());
			return null;
		} finally {
			expandingHelpers.remove(key);
		}
	}

	private static boolean returnsCell(MethodInsnNode mi) {
		if ("row".equals(mi.name) && "()V".equals(mi.desc)) return true;
		return mi.desc.endsWith(")L" + CL_CELL + ";");
	}

	private static MethodInsnNode findChainHead(ClassNode cn, MethodNode mn,
	                                            Frame<SourceValue>[] frames, MethodInsnNode site) {
		Set<AbstractInsnNode> visited = new HashSet<>();
		MethodInsnNode        curr    = site;
		while (true) {
			if (!visited.add(curr)) return curr;
			SourceValue recv = receiverSource(frames, mn, curr);
			if (recv == null || recv.insns.size() != 1) return curr;
			AbstractInsnNode prev = recv.insns.iterator().next();
			if (!(prev instanceof MethodInsnNode prevCall)) return curr;
			if (isHelper(prevCall.owner, prevCall.name, prevCall.desc)) return prevCall;
			if (isTransparentCellCall(prevCall)) {
				curr = prevCall;
				continue;
			}
			if (!returnsCell(prevCall)) return curr;
			if (!isTableCellCreator(cn, prevCall.owner, prevCall.name)
			    && !isCellProperty(cn, prevCall.owner, prevCall.name, prevCall.desc)) { return curr; }
			curr = prevCall;
		}
	}

	private static boolean isTransparentCellCall(MethodInsnNode mi) {
		if (isCellClass(null, mi.owner)) {
			return "self".equals(mi.name) || "with".equals(mi.name);
		}
		return false;
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
		int base     = f.getStackSize() - argCount - 1;
		if (base < 0) return null;
		return f.getStack(base);
	}

	private static Object[] extractArgsDataflow(ClassNode cn, MethodNode mn,
	                                            Frame<SourceValue>[] frames, MethodInsnNode site,
	                                            Map<Integer, Object> paramBindings) {
		Type[] argTypes = Type.getArgumentTypes(site.desc);
		if (argTypes.length == 0) return null;

		int idx = mn.instructions.indexOf(site);
		if (idx < 0) return null;
		Frame<SourceValue> f = frames[idx];
		if (f == null) return null;

		int base = f.getStackSize() - argTypes.length;
		if (base < 0) return null;

		Object[]    args     = new Object[argTypes.length];
		Set<String> visiting = new HashSet<>();
		for (int i = 0; i < argTypes.length; i++) {
			SourceValue sv = f.getStack(base + i);
			Object v = resolveSourceValue(cn, mn, frames, sv, visiting, paramBindings);
			Object sanitized = sanitizeValue(v);
			if (sanitized != null) {
				args[i] = coerce(argTypes[i], sanitized);
			} else {
				int fp = computeSliceFingerprint(cn, mn, frames, sv, new HashSet<>(), paramBindings, 0);
				if (fp != 0) {
					args[i] = new DynamicArg(fp, null);
				} else {
					args[i] = null;
				}
			}
		}
		return args;
	}

	private static Object coerce(Type t, Object v) {
		if (!(v instanceof Number n)) return v;
		return switch (t.getSort()) {
			case Type.BOOLEAN -> n.intValue() != 0;
			case Type.CHAR -> (char) n.intValue();
			case Type.BYTE -> n.byteValue();
			case Type.SHORT -> n.shortValue();
			case Type.INT -> n.intValue();
			case Type.LONG -> n.longValue();
			case Type.FLOAT -> n.floatValue();
			case Type.DOUBLE -> n.doubleValue();
			default -> v;
		};
	}

	private static Object resolveSourceValue(ClassNode cn, MethodNode mn,
	                                         Frame<SourceValue>[] frames, SourceValue sv,
	                                         Set<String> visiting,
	                                         Map<Integer, Object> paramBindings) {
		if (sv == null || sv.insns.size() != 1) return null;
		return resolveInsnConstant(cn, mn, frames, sv.insns.iterator().next(), visiting, paramBindings);
	}

	private static Object operand(ClassNode cn, MethodNode mn,
	                              Frame<SourceValue>[] frames, AbstractInsnNode insn,
	                              int fromTop, Set<String> visiting,
	                              Map<Integer, Object> paramBindings) {
		int idx = mn.instructions.indexOf(insn);
		if (idx < 0) return null;
		Frame<SourceValue> f = frames[idx];
		if (f == null) return null;
		int pos = f.getStackSize() - 1 - fromTop;
		if (pos < 0) return null;
		return resolveSourceValue(cn, mn, frames, f.getStack(pos), visiting, paramBindings);
	}

	private static Object resolveInsnConstant(ClassNode cn, MethodNode mn,
	                                          Frame<SourceValue>[] frames, AbstractInsnNode insn,
	                                          Set<String> visiting,
	                                          Map<Integer, Object> paramBindings) {
		if (insn == null) return null;

		if (insn instanceof FieldInsnNode fin && insn.getOpcode() == GETSTATIC) {
			if (fin.owner.equals(cn.name)) {
				try {
					Class<?> clazz = Class.forName(fin.owner.replace('/', '.'), false, getTargetLoader());
					Field    field = clazz.getDeclaredField(fin.name);
					int      m     = field.getModifiers();
					if (Modifier.isStatic(m) && Modifier.isFinal(m)) {
						field.setAccessible(true);
						Object v = field.get(null);
						if (isConstantType(v)) return v;
					}
				} catch (Throwable ignored) { }
			}
			return new StaticRef(fin.owner, fin.name, fin.desc);
		}

		if (insn instanceof LdcInsnNode ldc) return ldc.cst;

		if (insn instanceof InsnNode in) {
			int op = in.getOpcode();
			switch (op) {
				case ICONST_M1:
					return -1;
				case ICONST_0:
					return 0;
				case ICONST_1:
					return 1;
				case ICONST_2:
					return 2;
				case ICONST_3:
					return 3;
				case ICONST_4:
					return 4;
				case ICONST_5:
					return 5;
				case FCONST_0:
					return 0.0f;
				case FCONST_1:
					return 1.0f;
				case FCONST_2:
					return 2.0f;
				case DCONST_0:
					return 0.0d;
				case DCONST_1:
					return 1.0d;
				case LCONST_0:
					return 0L;
				case LCONST_1:
					return 1L;
				case I2L:
				case I2F:
				case I2D:
				case L2I:
				case L2F:
				case L2D:
				case F2I:
				case F2L:
				case F2D:
				case D2I:
				case D2L:
				case D2F:
				case I2B:
				case I2C:
				case I2S:
					return convertNumeric(op, operand(cn, mn, frames, insn, 0, visiting, paramBindings));
				case IADD:
				case ISUB:
				case IMUL:
				case IDIV:
				case IREM:
				case ISHL:
				case ISHR:
				case IUSHR:
				case IAND:
				case IOR:
				case IXOR:
				case LADD:
				case LSUB:
				case LMUL:
				case LDIV:
				case LREM:
				case LSHL:
				case LSHR:
				case LUSHR:
				case LAND:
				case LOR:
				case LXOR:
				case FADD:
				case FSUB:
				case FMUL:
				case FDIV:
				case FREM:
				case DADD:
				case DSUB:
				case DMUL:
				case DDIV:
				case DREM: {
					Object right = operand(cn, mn, frames, insn, 0, visiting, paramBindings);
					Object left  = operand(cn, mn, frames, insn, 1, visiting, paramBindings);
					return evaluateBinaryOp(op, left, right);
				}
				case INEG:
				case LNEG:
				case FNEG:
				case DNEG:
					return evaluateUnaryOp(op, operand(cn, mn, frames, insn, 0, visiting, paramBindings));
			}
		}

		if (insn instanceof IntInsnNode iin
		    && (iin.getOpcode() == BIPUSH || iin.getOpcode() == SIPUSH)) { return iin.operand; }

		if (insn instanceof VarInsnNode vin) {
			int op = vin.getOpcode();
			if (op == ILOAD || op == FLOAD || op == LLOAD || op == DLOAD || op == ALOAD) {
				return resolveVar(cn, mn, frames, vin.var, visiting, paramBindings);
			}
		}

		if (insn instanceof MethodInsnNode min && min.getOpcode() == INVOKESTATIC) {
			int idx = mn.instructions.indexOf(min);
			if (idx >= 0 && frames != null) {
				Frame<SourceValue> f = frames[idx];
				if (f != null && isPureFunction(min.owner, min.name, min.desc)) {
					Type[] argTypes = Type.getArgumentTypes(min.desc);
					int base = f.getStackSize() - argTypes.length;
					if (base >= 0) {
						Object[] args = new Object[argTypes.length];
						boolean allConst = true;
						for (int i = 0; i < argTypes.length; i++) {
							Object v = resolveSourceValue(cn, mn, frames, f.getStack(base + i), visiting, paramBindings);
							if (v == null || !isConstantType(v)) {
								allConst = false;
								break;
							}
							args[i] = coerce(argTypes[i], v);
						}
						if (allConst) {
							Object evaluated = evaluatePureFunction(min.owner, min.name, min.desc, args);
							if (evaluated != null) return evaluated;
						}
					}
				}
			}
		}

		if (insn instanceof InvokeDynamicInsnNode indy
		    && indy.bsm != null
		    && "java/lang/invoke/LambdaMetafactory".equals(indy.bsm.getOwner())
		    && indy.bsmArgs != null && indy.bsmArgs.length >= 2
		    && indy.bsmArgs[1] instanceof Handle handle) {

			Type[]             captureTypes = Type.getArgumentTypes(indy.desc);
			Object[]           captures     = new Object[captureTypes.length];
			int                idx          = mn.instructions.indexOf(indy);
			Frame<SourceValue> f            = idx >= 0 ? frames[idx] : null;
			if (f != null) {
				int base = f.getStackSize() - captureTypes.length;
				if (base >= 0) {
					for (int i = 0; i < captureTypes.length; i++) {
						Object v = resolveSourceValue(cn, mn, frames, f.getStack(base + i), visiting, paramBindings);
						captures[i] = sanitizeValue(v);
					}
				}
			}
			return new LambdaInfo(handle.getOwner(), handle.getName(), handle.getDesc(), captures, handle.getTag());
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
	                                 Set<String> visiting,
	                                 Map<Integer, Object> paramBindings) {
		String visitKey = mn.name + ":" + mn.desc + "#" + varIndex;
		if (!visiting.add(visitKey)) return null;
		try {
			int              writes      = 0;
			AbstractInsnNode singleStore = null;
			for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof VarInsnNode vin) {
					int op = vin.getOpcode();
					if ((op == ISTORE || op == LSTORE || op == FSTORE || op == DSTORE || op == ASTORE)
					    && vin.var == varIndex) {
						writes++;
						singleStore = insn;
					}
				} else if (insn instanceof IincInsnNode iin && iin.var == varIndex) writes++;
			}
			if (writes == 0 && paramBindings != null && paramBindings.containsKey(varIndex)) {
				return paramBindings.get(varIndex);
			}
			if (writes == 0 && varIndex == 0 && (mn.access & ACC_STATIC) == 0) {
				return new ThisPlaceholder(cn.name);
			}
			if (writes == 1 && singleStore != null) {
				int idx = mn.instructions.indexOf(singleStore);
				if (idx >= 0) {
					Frame<SourceValue> f = frames[idx];
					if (f != null && f.getStackSize() > 0) {
						Object v = resolveSourceValue(cn, mn, frames,
						 f.getStack(f.getStackSize() - 1), visiting, paramBindings);
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
			case IREM -> r.intValue() == 0 ? null : l.intValue() % r.intValue();
			case ISHL -> l.intValue() << r.intValue();
			case ISHR -> l.intValue() >> r.intValue();
			case IUSHR -> l.intValue() >>> r.intValue();
			case IAND -> l.intValue() & r.intValue();
			case IOR -> l.intValue() | r.intValue();
			case IXOR -> l.intValue() ^ r.intValue();

			case LADD -> l.longValue() + r.longValue();
			case LSUB -> l.longValue() - r.longValue();
			case LMUL -> l.longValue() * r.longValue();
			case LDIV -> r.longValue() == 0L ? null : l.longValue() / r.longValue();
			case LREM -> r.longValue() == 0L ? null : l.longValue() % r.longValue();
			case LSHL -> l.longValue() << r.intValue();
			case LSHR -> l.longValue() >> r.intValue();
			case LUSHR -> l.longValue() >>> r.intValue();
			case LAND -> l.longValue() & r.longValue();
			case LOR -> l.longValue() | r.longValue();
			case LXOR -> l.longValue() ^ r.longValue();

			case FADD -> l.floatValue() + r.floatValue();
			case FSUB -> l.floatValue() - r.floatValue();
			case FMUL -> l.floatValue() * r.floatValue();
			case FDIV -> r.floatValue() == 0.0f ? null : l.floatValue() / r.floatValue();
			case FREM -> r.floatValue() == 0.0f ? null : l.floatValue() % r.floatValue();

			case DADD -> l.doubleValue() + r.doubleValue();
			case DSUB -> l.doubleValue() - r.doubleValue();
			case DMUL -> l.doubleValue() * r.doubleValue();
			case DDIV -> r.doubleValue() == 0.0d ? null : l.doubleValue() / r.doubleValue();
			case DREM -> r.doubleValue() == 0.0d ? null : l.doubleValue() % r.doubleValue();
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
		if (v instanceof StaticRef) return v;
		if (v instanceof ThisPlaceholder) return v;
		if (v instanceof DynamicArg) return v;
		if (v instanceof LambdaInfo li) {
			Object[] caps    = li.captures();
			Object[] newCaps = caps == null ? null : sanitizeArgs(caps);
			return new LambdaInfo(li.ownerClass(), li.methodName(), li.methodDesc(), newCaps, li.tag());
		}
		return null;
	}

	private static Object[] sanitizeArgs(Object[] args) {
		if (args == null) return null;
		Object[] out = new Object[args.length];
		for (int i = 0; i < args.length; i++) out[i] = sanitizeValue(args[i]);
		return out;
	}

	private static SourceValue stackOperand(MethodNode mn, Frame<SourceValue>[] frames,
	                                        AbstractInsnNode insn, int fromTop) {
		if (mn == null || mn.instructions == null || frames == null || insn == null) return null;
		int idx = mn.instructions.indexOf(insn);
		if (idx < 0) return null;
		Frame<SourceValue> f = frames[idx];
		if (f == null) return null;
		int pos = f.getStackSize() - 1 - fromTop;
		if (pos < 0) return null;
		return f.getStack(pos);
	}

	private static int computeSliceFingerprint(ClassNode cn, MethodNode mn,
	                                          Frame<SourceValue>[] frames, SourceValue sv,
	                                          Set<String> visiting,
	                                          Map<Integer, Object> paramBindings, int depth) {
		if (sv == null || sv.insns == null || sv.insns.isEmpty() || depth > 8) return 0;
		int h = 1;
		for (AbstractInsnNode insn : sv.insns) {
			h = 31 * h + computeInsnFingerprint(cn, mn, frames, insn, visiting, paramBindings, depth);
		}
		return h == 0 ? 1 : h;
	}

	private static int computeInsnFingerprint(ClassNode cn, MethodNode mn,
	                                         Frame<SourceValue>[] frames, AbstractInsnNode insn,
	                                         Set<String> visiting,
	                                         Map<Integer, Object> paramBindings, int depth) {
		if (insn == null || depth > 8) return 0;
		int op = insn.getOpcode();

		if (insn instanceof LdcInsnNode ldc) {
			return 31 * op + Objects.hashCode(ldc.cst);
		}

		if (insn instanceof IntInsnNode iin) {
			return 31 * op + iin.operand;
		}

		if (insn instanceof InsnNode) {
			switch (op) {
				case ACONST_NULL:
				case ICONST_M1:
				case ICONST_0:
				case ICONST_1:
				case ICONST_2:
				case ICONST_3:
				case ICONST_4:
				case ICONST_5:
				case LCONST_0:
				case LCONST_1:
				case FCONST_0:
				case FCONST_1:
				case FCONST_2:
				case DCONST_0:
				case DCONST_1:
					return op;
				case I2L:
				case I2F:
				case I2D:
				case L2I:
				case L2F:
				case L2D:
				case F2I:
				case F2L:
				case F2D:
				case D2I:
				case D2L:
				case D2F:
				case I2B:
				case I2C:
				case I2S:
				case INEG:
				case LNEG:
				case FNEG:
				case DNEG:
				case ARRAYLENGTH: {
					SourceValue op0 = stackOperand(mn, frames, insn, 0);
					return 31 * op + computeSliceFingerprint(cn, mn, frames, op0, visiting, paramBindings, depth + 1);
				}
				case IADD:
				case ISUB:
				case IMUL:
				case IDIV:
				case IREM:
				case ISHL:
				case ISHR:
				case IUSHR:
				case IAND:
				case IOR:
				case IXOR:
				case LADD:
				case LSUB:
				case LMUL:
				case LDIV:
				case LREM:
				case LSHL:
				case LSHR:
				case LUSHR:
				case LAND:
				case LOR:
				case LXOR:
				case FADD:
				case FSUB:
				case FMUL:
				case FDIV:
				case FREM:
				case DADD:
				case DSUB:
				case DMUL:
				case DDIV:
				case DREM: {
					SourceValue right = stackOperand(mn, frames, insn, 0);
					SourceValue left  = stackOperand(mn, frames, insn, 1);
					int h = 31 * op + computeSliceFingerprint(cn, mn, frames, left, visiting, paramBindings, depth + 1);
					return 31 * h + computeSliceFingerprint(cn, mn, frames, right, visiting, paramBindings, depth + 1);
				}
				default:
					return op;
			}
		}

		if (insn instanceof FieldInsnNode fin) {
			int h = Objects.hash(op, fin.owner, fin.name, fin.desc);
			if (op == GETFIELD) {
				SourceValue recv = stackOperand(mn, frames, insn, 0);
				h = 31 * h + computeSliceFingerprint(cn, mn, frames, recv, visiting, paramBindings, depth + 1);
			}
			return h;
		}

		if (insn instanceof MethodInsnNode min) {
			int h = Objects.hash(op, min.owner, min.name, min.desc);
			Type[] argTypes = Type.getArgumentTypes(min.desc);
			int argCount = argTypes.length;
			for (int i = 0; i < argCount; i++) {
				int fromTop = argCount - 1 - i;
				SourceValue argSv = stackOperand(mn, frames, insn, fromTop);
				h = 31 * h + computeSliceFingerprint(cn, mn, frames, argSv, visiting, paramBindings, depth + 1);
			}
			if (op != INVOKESTATIC) {
				SourceValue recv = stackOperand(mn, frames, insn, argCount);
				h = 31 * h + computeSliceFingerprint(cn, mn, frames, recv, visiting, paramBindings, depth + 1);
			}
			return h;
		}

		if (insn instanceof VarInsnNode vin) {
			if (op == ILOAD || op == LLOAD || op == FLOAD || op == DLOAD || op == ALOAD) {
				if (vin.var == 0 && mn != null && (mn.access & ACC_STATIC) == 0) {
					return Objects.hash(op, "THIS", cn != null ? cn.name : "");
				}
				if (paramBindings != null && paramBindings.containsKey(vin.var)) {
					return Objects.hash(op, "PARAM", paramBindings.get(vin.var));
				}
				// 检查是否为单次写入局部变量
				if (mn != null && mn.instructions != null) {
					int writes = 0;
					AbstractInsnNode singleStore = null;
					for (AbstractInsnNode cur = mn.instructions.getFirst(); cur != null; cur = cur.getNext()) {
						if (cur instanceof VarInsnNode svin && svin.var == vin.var) {
							int sop = svin.getOpcode();
							if (sop == ISTORE || sop == LSTORE || sop == FSTORE || sop == DSTORE || sop == ASTORE) {
								writes++;
								singleStore = svin;
							}
						} else if (cur instanceof IincInsnNode iin && iin.var == vin.var) {
							writes++;
						}
					}
					if (writes == 1 && singleStore != null && depth < 8) {
						String visitKey = mn.name + ":" + mn.desc + "#" + vin.var;
						if (visiting.add(visitKey)) {
							try {
								SourceValue storeVal = stackOperand(mn, frames, singleStore, 0);
								if (storeVal != null) {
									return 31 * op + computeSliceFingerprint(cn, mn, frames, storeVal, visiting, paramBindings, depth + 1);
								}
							} finally {
								visiting.remove(visitKey);
							}
						}
					}
				}
				return Objects.hash(op, "VAR", vin.var);
			}
			return Objects.hash(op, vin.var);
		}

		if (insn instanceof InvokeDynamicInsnNode indy) {
			int h = Objects.hash(op, indy.name, indy.desc);
			if (indy.bsm != null) {
				h = 31 * h + Objects.hash(indy.bsm.getOwner(), indy.bsm.getName(), indy.bsm.getDesc());
			}
			if (indy.bsm != null && "java/lang/invoke/StringConcatFactory".equals(indy.bsm.getOwner())) {
				if (indy.bsmArgs != null) {
					for (Object bsmArg : indy.bsmArgs) {
						h = 31 * h + Objects.hashCode(bsmArg);
					}
				}
			} else if (indy.bsmArgs != null) {
				for (Object bsmArg : indy.bsmArgs) {
					if (bsmArg instanceof Handle handle) {
						h = 31 * h + Objects.hash(handle.getOwner(), handle.getName(), handle.getDesc(), handle.getTag());
					} else {
						h = 31 * h + Objects.hashCode(bsmArg);
					}
				}
			}
			Type[] indyArgTypes = Type.getArgumentTypes(indy.desc);
			for (int i = 0; i < indyArgTypes.length; i++) {
				int fromTop = indyArgTypes.length - 1 - i;
				SourceValue argSv = stackOperand(mn, frames, indy, fromTop);
				h = 31 * h + computeSliceFingerprint(cn, mn, frames, argSv, visiting, paramBindings, depth + 1);
			}
			return h;
		}

		if (insn instanceof TypeInsnNode tin) {
			int h = Objects.hash(op, tin.desc);
			if (op == CHECKCAST || op == INSTANCEOF) {
				SourceValue op0 = stackOperand(mn, frames, insn, 0);
				h = 31 * h + computeSliceFingerprint(cn, mn, frames, op0, visiting, paramBindings, depth + 1);
			}
			return h;
		}

		return op;
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
		byte[]   bytes     = fetchCurrentBytecode(cellClass);
		if (bytes == null) {
			error("[CellProperty] Cannot fetch Cell bytecode");
			return;
		}

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
						@Override
						protected void onMethodExit(int opcode) {
							if (opcode == ATHROW) return;
							mv.visitVarInsn(ALOAD, 0);
							mv.visitMethodInsn(INVOKESTATIC,
							 internalName(CellPropertyRef.class),
							 "onCellBound", "(Larc/scene/ui/layout/Cell;)V", false);
						}
					};
				}

				if (name.equals("set") && "(Larc/scene/ui/layout/Cell;)Larc/scene/ui/layout/Cell;".equals(descriptor)) {
					return new AdviceAdapter(ASM9, mv, access, name, descriptor) {
						@Override
						protected void onMethodEnter() {
							mv.visitVarInsn(ALOAD, 0);
							mv.visitVarInsn(ALOAD, 1);
							mv.visitMethodInsn(INVOKESTATIC,
							 internalName(CellPropertyRef.class),
							 "onCellSet", "(Larc/scene/ui/layout/Cell;Larc/scene/ui/layout/Cell;)V", false);
						}
					};
				}

				if (name.equals("reset") && "()V".equals(descriptor)) {
					return new AdviceAdapter(ASM9, mv, access, name, descriptor) {
						@Override
						protected void onMethodExit(int opcode) {
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
				      || ("row".equals(name) && "()V".equals(descriptor)))) { return mv; }

				return new AdviceAdapter(ASM9, mv, access, name, descriptor) {
					@Override
					protected void onMethodExit(int opcode) {
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
			case Type.CHAR -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character",
			 "valueOf", "(C)Ljava/lang/Character;", false);
			case Type.BYTE -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Byte",
			 "valueOf", "(B)Ljava/lang/Byte;", false);
			case Type.SHORT -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Short",
			 "valueOf", "(S)Ljava/lang/Short;", false);
			case Type.INT -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer",
			 "valueOf", "(I)Ljava/lang/Integer;", false);
			case Type.FLOAT -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float",
			 "valueOf", "(F)Ljava/lang/Float;", false);
			case Type.LONG -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long",
			 "valueOf", "(J)Ljava/lang/Long;", false);
			case Type.DOUBLE -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double",
			 "valueOf", "(D)Ljava/lang/Double;", false);
			default -> { }
		}
	}

	private static void pushInt(MethodVisitor mv, int value) {
		if (value >= -1 && value <= 5) {
			mv.visitInsn(ICONST_0 + value);
		} else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
			mv.visitIntInsn(BIPUSH, value);
		} else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) { mv.visitIntInsn(SIPUSH, value); } else {
			mv.visitLdcInsn(value);
		}
	}
	//endregion

	//region 辅助方法

	private static Class<?> loadClass(String className) throws ClassNotFoundException {
		return Class.forName(className, true, getTargetLoader());
	}
	//endregion

	//region 生命周期管理

	public static void enable() {
		FAILURES.set(0);
		enabled = true;
		if (Core.app != null) {
			Core.app.post(() -> {
				uiThread = Thread.currentThread();
				info("[CellProperty] Enabled, UI thread = " + uiThread.getName());
			});
		} else {
			uiThread = null;
			info("[CellProperty] Enabled (Core.app not ready yet, uiThread deferred)");
		}
	}

	public static void disable() {
		if (!enabled) return;
		enabled = false;
		if (Core.app != null) { Core.app.post(CellPropertyRef::performRollback); } else clearAll();
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
		byte[] origTable = originalTableBytes;
		originalTableBytes = null;
		if (origTable != null) {
			try {
				Injector.redefineOneClass(Table.class, origTable);
				info("[CellProperty] Table class rolled back to original bytecode");
			} catch (Throwable t) {
				error("[CellProperty] Table rollback failed", t);
			}
		}
		clearAll();
	}

	private static volatile Boolean enabledOverride = null;

	public static void setEnabled(boolean b) {
		enabledOverride = b;
		enabled = b;
	}
	public static boolean isEnabled() {
		if (enabledOverride != null) return enabledOverride;
		return enabled || Boolean.parseBoolean(System.getProperty("nipx.agent.cell_property_ref", "false"));
	}

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
		synchronized (cellState) {
			for (Map<String, Integer> siteCounts : tableSiteOrdinals.values()) {
				siteCounts.keySet().removeIf(k -> k.startsWith(hostSlashName + "#"));
			}
		}
		WHOLE_CLASS_HELPERS.remove(hostSlashName);
		HELPER_METHODS_BY_CLASS.remove(hostSlashName);
		SCANNED_HELPER_CLASSES.remove(hostSlashName);
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
			unpatchedCells.clear();
			tableSiteOrdinals.clear();
			WHOLE_CLASS_HELPERS.clear();
			HELPER_METHODS_BY_CLASS.clear();
			SCANNED_HELPER_CLASSES.clear();
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
			       + "  Unpatched Cells: " + unpatchedCells.size() + "\n"
			       + "  Identities: " + idToCells.size() + "\n"
			       + "  Host Classes: " + classToCells.size() + "\n"
			       + "  Chain Cache: " + chainCache.size() + "\n"
			       + "  Template Cache: " + templateCache.size();
		}
	}
	//endregion

	//region HotRebuild 重建兜底 (Task 31)

	private static final Map<String, Set<String>> REGISTERED_REBUILD_METHODS = new ConcurrentHashMap<>();
	public static final Set<Object>               REGISTERED_INSTANCES       = Collections.synchronizedSet(newWeakSet());

	public static void registerRebuildMethod(String className, String methodName) {
		if (className == null || methodName == null) return;
		String norm = className.replace('/', '.');
		REGISTERED_REBUILD_METHODS.computeIfAbsent(norm, k -> ConcurrentHashMap.newKeySet()).add(methodName);
		String slash = className.replace('.', '/');
		REGISTERED_REBUILD_METHODS.computeIfAbsent(slash, k -> ConcurrentHashMap.newKeySet()).add(methodName);
	}

	public static void unregisterRebuildMethod(String className, String methodName) {
		if (className == null || methodName == null) return;
		String norm = className.replace('/', '.');
		Set<String> s1 = REGISTERED_REBUILD_METHODS.get(norm);
		if (s1 != null) s1.remove(methodName);
		String slash = className.replace('.', '/');
		Set<String> s2 = REGISTERED_REBUILD_METHODS.get(slash);
		if (s2 != null) s2.remove(methodName);
	}

	public static void registerInstance(Object instance) {
		if (instance != null) {
			REGISTERED_INSTANCES.add(instance);
		}
	}

	public static void unregisterInstance(Object instance) {
		if (instance != null) {
			REGISTERED_INSTANCES.remove(instance);
		}
	}

	public static boolean hasHotRebuild(Class<?> clazz) {
		if (clazz == null) return false;
		String name = clazz.getName();
		String slash = name.replace('.', '/');
		if (REGISTERED_REBUILD_METHODS.containsKey(name) || REGISTERED_REBUILD_METHODS.containsKey(slash)) {
			return true;
		}
		Class<?> curr = clazz;
		while (curr != null && curr != Object.class) {
			for (Method m : curr.getDeclaredMethods()) {
				if (m.isAnnotationPresent(HotRebuild.class)) {
					return true;
				}
			}
			for (Class<?> iface : curr.getInterfaces()) {
				for (Method m : iface.getDeclaredMethods()) {
					if (m.isAnnotationPresent(HotRebuild.class)) {
						return true;
					}
				}
			}
			curr = curr.getSuperclass();
		}
		return false;
	}

	public static boolean triggerHotRebuild(String slashName) {
		if (slashName == null) return false;
		try {
			Class<?> clazz = Class.forName(slashName.replace('/', '.'), false, getTargetLoader());
			return triggerHotRebuild(clazz);
		} catch (Throwable t) {
			if (DEBUG) log("[HotRebuild] Failed to load class for rebuild: " + slashName + ": " + t.getMessage());
			return false;
		}
	}

	public static boolean triggerHotRebuild(Class<?> clazz) {
		if (clazz == null || !hasHotRebuild(clazz)) return false;

		if (isUiThread() || Core.app == null) {
			return doTriggerHotRebuild(clazz);
		} else {
			Core.app.post(() -> doTriggerHotRebuild(clazz));
			return true;
		}
	}

	private static boolean doTriggerHotRebuild(Class<?> clazz) {
		Map<String, Method> rebuildMethods = findRebuildMethods(clazz);
		if (rebuildMethods.isEmpty()) return false;

		Set<Object> instances = findInstancesOfClass(clazz);
		boolean anyInvoked = false;

		for (Method m : rebuildMethods.values()) {
			String[] argFields = null;
			HotRebuild hr = m.getAnnotation(HotRebuild.class);
			if (hr != null) {
				argFields = hr.argFields();
			}
			if (Modifier.isStatic(m.getModifiers())) {
				try {
					invokeRebuildMethod(null, m, argFields);
					anyInvoked = true;
				} catch (Throwable t) {
					warn("[HotRebuild] Failed to invoke static rebuild method " + m.getName() + " on " + clazz.getName() + ": " + t.getMessage());
				}
			} else {
				for (Object inst : instances) {
					try {
						invokeRebuildMethod(inst, m, argFields);
						anyInvoked = true;
					} catch (Throwable t) {
						warn("[HotRebuild] Failed to invoke rebuild method " + m.getName() + " on instance " + inst + ": " + t.getMessage());
					}
				}
			}
		}
		return anyInvoked;
	}

	private static Map<String, Method> findRebuildMethods(Class<?> clazz) {
		Map<String, Method> map = new LinkedHashMap<>();
		Class<?> curr = clazz;
		while (curr != null && curr != Object.class) {
			String name = curr.getName();
			String slash = name.replace('.', '/');
			Set<String> registered = new HashSet<>();
			Set<String> r1 = REGISTERED_REBUILD_METHODS.get(name);
			if (r1 != null) registered.addAll(r1);
			Set<String> r2 = REGISTERED_REBUILD_METHODS.get(slash);
			if (r2 != null) registered.addAll(r2);

			for (Method m : curr.getDeclaredMethods()) {
				boolean annotated = m.isAnnotationPresent(HotRebuild.class);
				boolean reg = registered.contains(m.getName());
				if (annotated || reg) {
					String sig = m.getName() + Type.getMethodDescriptor(m);
					if (!map.containsKey(sig)) {
						map.put(sig, m);
					}
				}
			}
			for (Class<?> iface : curr.getInterfaces()) {
				String iname = iface.getName();
				String islash = iname.replace('.', '/');
				Set<String> ireg = new HashSet<>();
				Set<String> ir1 = REGISTERED_REBUILD_METHODS.get(iname);
				if (ir1 != null) ireg.addAll(ir1);
				Set<String> ir2 = REGISTERED_REBUILD_METHODS.get(islash);
				if (ir2 != null) ireg.addAll(ir2);

				for (Method m : iface.getDeclaredMethods()) {
					if (m.isAnnotationPresent(HotRebuild.class) || ireg.contains(m.getName())) {
						String sig = m.getName() + Type.getMethodDescriptor(m);
						if (!map.containsKey(sig)) {
							map.put(sig, m);
						}
					}
				}
			}
			curr = curr.getSuperclass();
		}
		return map;
	}

	public static Set<Object> findInstancesOfClass(Class<?> clazz) {
		Set<Object> instances = Collections.newSetFromMap(new IdentityHashMap<>());
		if (clazz == null) return instances;

		// 1. 手动注册实例 (REGISTERED_INSTANCES)
		synchronized (REGISTERED_INSTANCES) {
			for (Object obj : REGISTERED_INSTANCES) {
				if (obj != null && clazz.isInstance(obj)) {
					instances.add(obj);
				}
			}
		}

		// 2. Kotlin object / Java 单例 INSTANCE
		try {
			Field instanceField = clazz.getDeclaredField("INSTANCE");
			if (Modifier.isStatic(instanceField.getModifiers())) {
				instanceField.setAccessible(true);
				Object singleton = instanceField.get(null);
				if (singleton != null && clazz.isInstance(singleton)) {
					instances.add(singleton);
				}
			}
		} catch (Throwable ignored) { }

		// 3. LibTool JVMTI 堆遍历
		try {
			if (LibTool.initialized()) {
				Object[] heapObjs = LibTool.getInstances(clazz);
				if (heapObjs != null) {
					for (Object o : heapObjs) {
						if (o != null && clazz.isInstance(o)) {
							instances.add(o);
						}
					}
				}
			}
		} catch (Throwable ignored) { }

		// 4. InstanceTracker 跟踪表
		try {
			List<?> tracked = InstanceTracker.getInstances(clazz);
			if (tracked != null) {
				for (Object o : tracked) {
					if (o != null && clazz.isInstance(o)) {
						instances.add(o);
					}
				}
			}
		} catch (Throwable ignored) { }

		// 5. 存活 Cell 与父级 Element 树遍历
		synchronized (cellState) {
			for (Entry<Cell<?>, CellState> entry : cellState.entrySet()) {
				Cell<?> cell = entry.getKey();
				if (cell == null) continue;

				Table table = cell.getTable();
				if (table != null) {
					if (clazz.isInstance(table)) {
						instances.add(table);
					}
					Element el = table.parent;
					while (el != null) {
						if (clazz.isInstance(el)) {
							instances.add(el);
						}
						el = el.parent;
					}
				}

				Element elem = cell.get();
				if (elem != null) {
					if (clazz.isInstance(elem)) {
						instances.add(elem);
					}
					Element el = elem.parent;
					while (el != null) {
						if (clazz.isInstance(el)) {
							instances.add(el);
						}
						el = el.parent;
					}
				}
			}
		}

		// 6. 若内部类实例存在，检查 enclosing this$0
		for (Object obj : new ArrayList<>(instances)) {
			try {
				Field this0 = obj.getClass().getDeclaredField("this$0");
				this0.setAccessible(true);
				Object outer = this0.get(obj);
				if (outer != null && clazz.isInstance(outer)) {
					instances.add(outer);
				}
			} catch (Throwable ignored) { }
		}

		return instances;
	}

	private static void invokeRebuildMethod(Object inst, Method m, String[] argFields) throws Exception {
		Method targetMethod = m;
		if (inst != null && Modifier.isAbstract(m.getModifiers())) {
			try {
				targetMethod = inst.getClass().getMethod(m.getName(), m.getParameterTypes());
			} catch (Throwable ignored) { }
		}
		if (Modifier.isAbstract(targetMethod.getModifiers())) {
			throw new IllegalStateException("Cannot invoke abstract rebuild method: " + targetMethod);
		}

		Object[] args = resolveRebuildArgs(inst, targetMethod, argFields);
		tryResetDirtyFlags(inst);

		boolean[] busyArr = BUSY.get();
		boolean wasBusy = busyArr[0];
		busyArr[0] = false;
		try {
			targetMethod.setAccessible(true);
			targetMethod.invoke(inst, args);
			if (DEBUG) log("[HotRebuild] Successfully invoked " + targetMethod.getName() + " on " + (inst != null ? inst : "static"));
		} finally {
			busyArr[0] = wasBusy;
		}
	}

	private static Object[] resolveRebuildArgs(Object inst, Method m, String[] argFields) throws Exception {
		int paramCount = m.getParameterCount();
		if (paramCount == 0) {
			return new Object[0];
		}
		if (argFields == null || argFields.length != paramCount) {
			throw new IllegalArgumentException(
				"Method " + m.getName() + " in " + m.getDeclaringClass().getName() +
				" expects " + paramCount + " arguments, but @HotRebuild provided " +
				(argFields == null ? 0 : argFields.length) + " argFields"
			);
		}
		Object[] args = new Object[paramCount];
		Class<?> searchClass = inst != null ? inst.getClass() : m.getDeclaringClass();
		for (int i = 0; i < paramCount; i++) {
			String fieldName = argFields[i];
			Field f = findFieldRecursive(searchClass, fieldName);
			if (f == null) {
				throw new NoSuchFieldException("Field '" + fieldName + "' not found on class " + searchClass.getName() + " for @HotRebuild");
			}
			f.setAccessible(true);
			args[i] = f.get(inst);
		}
		return args;
	}

	private static Field findFieldRecursive(Class<?> clazz, String fieldName) {
		Class<?> curr = clazz;
		while (curr != null && curr != Object.class) {
			try {
				return curr.getDeclaredField(fieldName);
			} catch (NoSuchFieldException ignored) { }
			curr = curr.getSuperclass();
		}
		return null;
	}

	private static Method findMethodRecursive(Class<?> clazz, String name, Class<?>... paramTypes) {
		Class<?> curr = clazz;
		while (curr != null && curr != Object.class) {
			try {
				Method m = curr.getDeclaredMethod(name, paramTypes);
				m.setAccessible(true);
				return m;
			} catch (NoSuchMethodException ignored) { }
			curr = curr.getSuperclass();
		}
		return null;
	}

	private static void tryResetDirtyFlags(Object inst) {
		if (inst == null) return;
		try {
			Method m1 = findMethodRecursive(inst.getClass(), "forceChanged");
			if (m1 != null && m1.getParameterCount() == 0) {
				m1.invoke(inst);
			}

			Method m2 = findMethodRecursive(inst.getClass(), "markDirty");
			if (m2 != null && m2.getParameterCount() == 0) {
				m2.invoke(inst);
			}

			Field dataField = findFieldRecursive(inst.getClass(), "data");
			if (dataField != null) {
				dataField.setAccessible(true);
				Object dataVal = dataField.get(inst);
				if (dataVal != null) {
					Method dm = findMethodRecursive(dataVal.getClass(), "forceChanged");
					if (dm != null && dm.getParameterCount() == 0) {
						dm.invoke(dataVal);
					} else {
						Method sm = findMethodRecursive(dataVal.getClass(), "setChanged", boolean.class);
						if (sm != null) {
							sm.invoke(dataVal, true);
						}
					}
				}
			}
		} catch (Throwable ignored) { }
	}
	//endregion

	//region Container Cons 重建 (Task 32)

	public static final Set<Table>          TRACKED_TABLES    = Collections.synchronizedSet(newWeakSet());
	private static final Map<Table, Object> FALLBACK_BUILDERS = Collections.synchronizedMap(new WeakHashMap<>());
	private static volatile Field           tableBuilderField;
	private static volatile byte[]          originalTableBytes;

	public static void setTableBuilder(Table table, Cons cons) {
		if (table == null) return;
		TRACKED_TABLES.add(table);
		if (cons == null) return;

		try {
			if (tableBuilderField == null) {
				try {
					tableBuilderField = Table.class.getField("$nipx$builder");
				} catch (NoSuchFieldException e) {
					try {
						tableBuilderField = Table.class.getDeclaredField("$nipx$builder");
					} catch (NoSuchFieldException ignored) { }
				}
				if (tableBuilderField != null) {
					tableBuilderField.setAccessible(true);
				}
			}
			if (tableBuilderField != null) {
				tableBuilderField.set(table, cons);
				return;
			}
		} catch (Throwable ignored) { }

		FALLBACK_BUILDERS.put(table, cons);
	}

	public static Cons getTableBuilder(Table table) {
		if (table == null) return null;
		try {
			if (tableBuilderField == null) {
				try {
					tableBuilderField = Table.class.getField("$nipx$builder");
				} catch (NoSuchFieldException e) {
					try {
						tableBuilderField = Table.class.getDeclaredField("$nipx$builder");
					} catch (NoSuchFieldException ignored) { }
				}
				if (tableBuilderField != null) {
					tableBuilderField.setAccessible(true);
				}
			}
			if (tableBuilderField != null) {
				return (Cons) tableBuilderField.get(table);
			}
		} catch (Throwable ignored) { }

		return (Cons) FALLBACK_BUILDERS.get(table);
	}

	public static void onTableConsConstructed(Table table, Cons cons) {
		if (table == null || cons == null) return;
		setTableBuilder(table, cons);
	}

	public static void onTableMethodWithCons(Cell<?> cell, Cons cons) {
		if (cell == null || cons == null) return;
		Element elem = cell.get();
		if (elem instanceof Table) {
			setTableBuilder((Table) elem, cons);
		}
	}

	public static void onTableClearChildren(Table table) {
		if (table == null) return;
		synchronized (cellState) {
			tableSiteOrdinals.remove(table);
		}
	}

	public static boolean triggerContainerRebuild(String slashName) {
		if (slashName == null) return false;
		try {
			Class<?> clazz = Class.forName(slashName.replace('/', '.'), false, getTargetLoader());
			if (hasHotRebuild(clazz)) {
				// 任务 32 规定：只在没有 @HotRebuild 方法时才用
				return false;
			}
		} catch (Throwable ignored) { }

		if (isUiThread() || Core.app == null) {
			return doTriggerContainerRebuild(slashName);
		} else {
			Core.app.post(() -> doTriggerContainerRebuild(slashName));
			return true;
		}
	}

	public static boolean triggerContainerRebuild(Table table) {
		if (table == null) return false;
		Cons cons = getTableBuilder(table);
		if (cons == null) return false;
		rebuildTable(table, cons);
		return true;
	}

	private static boolean doTriggerContainerRebuild(String slashName) {
		Set<Table> affectedTables = new HashSet<>();

		// 1. 检查已记录的 Table
		synchronized (TRACKED_TABLES) {
			for (Table t : TRACKED_TABLES) {
				if (t == null) continue;
				Cons cons = getTableBuilder(t);
				if (cons != null) {
					String hostClass = Injector.getHostClassName(cons);
					if (hostClass != null && hostClass.replace('.', '/').equals(slashName)) {
						affectedTables.add(t);
					}
				}
			}
		}

		// 2. 检查 cellState 中所有的 Table
		synchronized (cellState) {
			for (Entry<Cell<?>, CellState> e : cellState.entrySet()) {
				Cell<?> cell = e.getKey();
				if (cell == null) continue;
				Table t = cell.getTable();
				if (t != null && !affectedTables.contains(t)) {
					Cons cons = getTableBuilder(t);
					if (cons != null) {
						String hostClass = Injector.getHostClassName(cons);
						if (hostClass != null && hostClass.replace('.', '/').equals(slashName)) {
							affectedTables.add(t);
						}
					}
				}
			}
		}

		// 3. 检查场景树根节点（若存在）
		if (Core.scene != null && Core.scene.root != null) {
			collectAffectedTablesFromScene(Core.scene.root, affectedTables, slashName);
		}

		if (affectedTables.isEmpty()) return false;

		// 4. 顶层阻断优化：只重建最高层级的受影响 Table
		List<Table> topLevelTargets = new ArrayList<>();
		for (Table t : affectedTables) {
			boolean hasAncestorInAffected = false;
			Element curr = t.parent;
			while (curr != null) {
				if (curr instanceof Table && affectedTables.contains((Table) curr)) {
					hasAncestorInAffected = true;
					break;
				}
				curr = curr.parent;
			}
			if (!hasAncestorInAffected) {
				topLevelTargets.add(t);
			}
		}

		// 5. 执行重建
		for (Table t : topLevelTargets) {
			Cons cons = getTableBuilder(t);
			if (cons != null) {
				rebuildTable(t, cons);
			}
		}

		return !topLevelTargets.isEmpty();
	}

	private static void collectAffectedTablesFromScene(Element element, Set<Table> out, String slashName) {
		if (element == null) return;
		if (element instanceof Table) {
			Table t = (Table) element;
			Cons cons = getTableBuilder(t);
			if (cons != null) {
				String host = Injector.getHostClassName(cons);
				if (host != null && host.replace('.', '/').equals(slashName)) {
					out.add(t);
					// 顶层阻断：如果父 Table 已被收集，不再深入遍历其子元素
					return;
				}
			}
		}
		if (element instanceof Group) {
			Group g = (Group) element;
			for (Element child : g.getChildren()) {
				collectAffectedTablesFromScene(child, out, slashName);
			}
		}
	}

	private static void rebuildTable(Table t, Cons cons) {
		if (t == null || cons == null) return;
		boolean[] busyArr = BUSY.get();
		boolean wasBusy = busyArr[0];
		busyArr[0] = false;
		try {
			t.clearChildren();
			cons.get(t);
			t.pack();
			if (DEBUG) log("[ContainerRebuild] Rebuilt table container " + t);
		} catch (Throwable ex) {
			warn("[ContainerRebuild] Failed to rebuild table " + t + ": " + ex.getMessage());
		} finally {
			busyArr[0] = wasBusy;
		}
	}

	public static void redefineTable() {
		Class<Table> tableClass = Table.class;
		byte[] bytes = fetchCurrentBytecode(tableClass);
		if (bytes == null) {
			error("[CellProperty] Cannot fetch Table bytecode");
			return;
		}

		if (originalTableBytes == null) {
			if (containsTableHook(bytes)) {
				error("[CellProperty] Table already injected but no original bytes; aborting");
				return;
			}
			originalTableBytes = bytes;
		}

		byte[] injected = injectTable(originalTableBytes);
		if (injected == null) return;
		if (!verifyBytecode(injected)) {
			error("[CellProperty] Injected Table bytecode failed verification, aborting redefine");
			return;
		}

		Injector.redefineOneClass(tableClass, injected);
		info("[CellProperty] Table class redefined with $nipx$builder support");
	}

	private static boolean containsTableHook(byte[] bytes) {
		String s = new String(bytes, StandardCharsets.ISO_8859_1);
		return s.contains("onTableConsConstructed") || s.contains("onTableClearChildren");
	}

	public static byte[] injectTable(byte[] bytes) {
		ClassReader cr = new ClassReader(bytes);
		ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

		ClassVisitor cv = new ClassVisitor(ASM9, cw) {
			boolean isTable = false;

			@Override
			public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
				super.visit(version, access, name, signature, superName, interfaces);
				if (CL_TABLE.equals(name)) {
					isTable = true;
					super.visitField(ACC_PUBLIC, "$nipx$builder", "Larc/func/Cons;", null, null).visitEnd();
				}
			}

			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
				if (!isTable) return mv;

				if ("<init>".equals(name) && "(Larc/func/Cons;)V".equals(descriptor)) {
					return new AdviceAdapter(ASM9, mv, access, name, descriptor) {
						@Override
						protected void onMethodExit(int opcode) {
							if (opcode == ATHROW) return;
							mv.visitVarInsn(ALOAD, 0);
							mv.visitVarInsn(ALOAD, 1);
							mv.visitMethodInsn(INVOKESTATIC,
								internalName(CellPropertyRef.class),
								"onTableConsConstructed",
								"(Larc/scene/ui/layout/Table;Larc/func/Cons;)V", false);
						}
					};
				}

				if ("table".equals(name) && "(Larc/func/Cons;)Larc/scene/ui/layout/Cell;".equals(descriptor)) {
					return new AdviceAdapter(ASM9, mv, access, name, descriptor) {
						@Override
						protected void onMethodExit(int opcode) {
							if (opcode == ATHROW) return;
							mv.visitInsn(DUP);
							mv.visitVarInsn(ALOAD, 1);
							mv.visitMethodInsn(INVOKESTATIC,
								internalName(CellPropertyRef.class),
								"onTableMethodWithCons",
								"(Larc/scene/ui/layout/Cell;Larc/func/Cons;)V", false);
						}
					};
				}

				if ("clearChildren".equals(name) && "()V".equals(descriptor)) {
					return new AdviceAdapter(ASM9, mv, access, name, descriptor) {
						@Override
						protected void onMethodEnter() {
							mv.visitVarInsn(ALOAD, 0);
							mv.visitMethodInsn(INVOKESTATIC,
								internalName(CellPropertyRef.class),
								"onTableClearChildren",
								"(Larc/scene/ui/layout/Table;)V", false);
						}
					};
				}

				return mv;
			}
		};

		try {
			cr.accept(cv, ClassReader.EXPAND_FRAMES);
			return cw.toByteArray();
		} catch (Exception e) {
			error("[CellProperty] Failed to inject Table class", e);
			return null;
		}
	}
	//endregion

	//region Registered Callback 重建 (Task 33)

	public static class ElementCallback {
		public final WeakReference<Element> elementRef;
		public final Object                 callback;
		public final String                 registerMethod;
		public final String                 hostClass;

		public ElementCallback(WeakReference<Element> elementRef, Object callback, String registerMethod, String hostClass) {
			this.elementRef = elementRef;
			this.callback = callback;
			this.registerMethod = registerMethod;
			this.hostClass = hostClass;
		}
	}

	public static final Set<ElementCallback> TRACKED_CALLBACKS = Collections.synchronizedSet(new LinkedHashSet<>());

	public static void registerElementCallback(Element element, Object callback, String registerMethod) {
		if (element == null || callback == null) return;
		String host = Injector.getHostClassName(callback);
		String slashHost = host != null ? host.replace('.', '/') : null;
		TRACKED_CALLBACKS.add(new ElementCallback(new WeakReference<>(element), callback, registerMethod, slashHost));
	}

	public static void unregisterElementCallback(Element element, Object callback) {
		if (element == null && callback == null) return;
		synchronized (TRACKED_CALLBACKS) {
			TRACKED_CALLBACKS.removeIf(cb -> {
				Element el = cb.elementRef.get();
				if (el == null) return true;
				if (element != null && el != element) return false;
				return callback == null || cb.callback == callback;
			});
		}
	}

	public static boolean triggerCallbackRebuild(String slashName) {
		if (slashName == null) return false;
		try {
			Class<?> clazz = Class.forName(slashName.replace('/', '.'), false, getTargetLoader());
			if (hasHotRebuild(clazz)) {
				// 任务 33 规定：依赖任务 31 护栏，有 @HotRebuild 时让位
				return false;
			}
		} catch (Throwable ignored) { }

		if (isUiThread() || Core.app == null) {
			return doTriggerCallbackRebuild(slashName);
		} else {
			Core.app.post(() -> doTriggerCallbackRebuild(slashName));
			return true;
		}
	}

	public static boolean triggerCallbackRebuild(Element element) {
		if (element == null) return false;
		boolean anyInvoked = false;
		List<ElementCallback> targets = new ArrayList<>();
		synchronized (TRACKED_CALLBACKS) {
			for (ElementCallback cb : TRACKED_CALLBACKS) {
				if (cb.elementRef.get() == element) {
					targets.add(cb);
				}
			}
		}
		for (ElementCallback cb : targets) {
			if (invokeCallback(cb.elementRef.get(), cb.callback)) {
				anyInvoked = true;
			}
		}
		return anyInvoked;
	}

	private static boolean doTriggerCallbackRebuild(String slashName) {
		List<ElementCallback> eligible = new ArrayList<>();
		synchronized (TRACKED_CALLBACKS) {
			Iterator<ElementCallback> it = TRACKED_CALLBACKS.iterator();
			while (it.hasNext()) {
				ElementCallback cb = it.next();
				Element elem = cb.elementRef.get();
				if (elem == null) {
					it.remove();
					continue;
				}
				if (cb.hostClass != null && cb.hostClass.equals(slashName)) {
					if (isEligibleCallback(elem, cb)) {
						eligible.add(cb);
					}
				}
			}
		}

		if (eligible.isEmpty()) return false;

		boolean anyInvoked = false;
		for (ElementCallback cb : eligible) {
			Element el = cb.elementRef.get();
			if (el != null && invokeCallback(el, cb.callback)) {
				anyInvoked = true;
			}
		}
		return anyInvoked;
	}

	private static boolean isEligibleCallback(Element elem, ElementCallback cb) {
		String m = cb.registerMethod != null ? cb.registerMethod : "";
		// 1. 构建型入口天然放行 (resized, onResize, field, button 等)
		if (m.equals("resized") || m.equals("onResize") || m.equals("field") || m.equals("button") || m.equals("shown")) {
			return true;
		}

		// 2. 对于通用 update，必须具备 Cell 归属才重跑（过滤掉纯库循环与自终止的事务型 update）
		if (m.equals("update")) {
			return hasCellAttribution(elem);
		}

		return hasCellAttribution(elem);
	}

	private static boolean hasCellAttribution(Element elem) {
		if (elem == null) return false;
		synchronized (cellState) {
			for (Entry<Cell<?>, CellState> e : cellState.entrySet()) {
				Cell<?> cell = e.getKey();
				if (cell == null) continue;
				if (cell.get() == elem || cell.getTable() == elem) return true;
				Table t = cell.getTable();
				if (t != null) {
					Element p = elem;
					while (p != null) {
						if (p == t) return true;
						p = p.parent;
					}
				}
			}
		}
		return false;
	}

	private static boolean invokeCallback(Element element, Object callback) {
		if (callback == null) return false;
		boolean[] busyArr = BUSY.get();
		boolean wasBusy = busyArr[0];
		busyArr[0] = false;
		try {
			if (callback instanceof Runnable) {
				((Runnable) callback).run();
				return true;
			}
			if (callback instanceof Cons) {
				if (element instanceof TextField) {
					((Cons) callback).get(((TextField) element).getText());
				} else {
					((Cons) callback).get(element);
				}
				return true;
			}
		} catch (Throwable t) {
			warn("[CallbackRebuild] Failed to invoke callback on " + element + ": " + t.getMessage());
		} finally {
			busyArr[0] = wasBusy;
		}
		return false;
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
			default -> handle.bindTo(instance).asSpreader(Object[].class, args.length).invoke(args);
		};
	}

	private static <T> T invoke(MethodHandle handle, Object[] args) throws Throwable {
		return (T) switch (args == null ? 0 : args.length) {
			case 0 -> handle.invoke();
			case 1 -> handle.invoke(args[0]);
			case 2 -> handle.invoke(args[0], args[1]);
			case 3 -> handle.invoke(args[0], args[1], args[2]);
			case 4 -> handle.invoke(args[0], args[1], args[2], args[3]);
			default -> handle.asSpreader(Object[].class, args.length).invoke(args);
		};
	}
	//endregion
}
