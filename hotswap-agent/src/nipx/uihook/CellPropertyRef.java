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
import nipx.jvmti.JVMTIEnv;
import org.objectweb.asm.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.tree.*;

import java.lang.foreign.MemorySegment;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

import static nipx.AnnotationTransformer.internalName;
import static nipx.HotSwapAgent.*;
import static org.objectweb.asm.Opcodes.*;

/** Cell 属性及子元素追踪器 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class CellPropertyRef {

	public static final String CL_TABLE = "arc/scene/ui/layout/Table";
	public static final String CL_CELL  = "arc/scene/ui/layout/Cell";

	//region 数据结构
	public record PropertyCall(String method, String desc, Object[] args, int line) { }

	public record CellIdentity(String hostClass, String hostMethod, String hostDesc, int sequence) { }
	//endregion

	//region 全局状态
	/** Cell → 唯一标识（使用 WeakHashMap，防止 Cell 内存泄漏） */
	private static final Map<Cell<?>, CellIdentity> cellToId = new WeakHashMap<>();

	/** 唯一标识 → Cell 弱引用 */
	private static final Map<CellIdentity, WeakReference<Cell<?>>> idToCell = new ConcurrentHashMap<>();

	/** 标识 → 当前方法调用链列表 (首个元素为 CreatorCall) */
	private static final Map<CellIdentity, List<PropertyCall>> records = new ConcurrentHashMap<>();

	/** 宿主类 → 该类的所有 Cell 标识 */
	private static final Map<String, List<CellIdentity>> classToCells = new ConcurrentHashMap<>();

	/** 每个 (宿主类, 方法, 描述符) 的序号计数器 */
	private static final Map<String, int[]> methodCounters = new ConcurrentHashMap<>();

	/** 是否启用 */
	private static volatile boolean enabled = false;
	//endregion

	//region 运行时记录与生命周期
	/** 运行时：在 Cell 实例构造结束时自动捕获注册 */
	public static void registerCellAtCreation(Cell<?> cell) {
		if (!enabled || cell == null) return;

		String[] callbackFrame = {null, null, null};
		int[]    x             = {-1};
		JVMTIEnv.getInstance().walkThreadFrames(MemorySegment.NULL, 12, 1, (className, methodName, methodDesc, thisAddr) -> {
			if (x[0] >= 0 && ++x[0] == 2) {
				callbackFrame[0] = className;
				callbackFrame[1] = methodName;
				callbackFrame[2] = methodDesc;
				return false;
			}
			if (CL_CELL.equals(className) && "<init>".equals(methodName)) {
				x[0] = 0;
			}
			return true;
		});

		String hostClass = callbackFrame[0];

		// 如果创建该单元格的直接源头是底层库，则属于隐式嵌套，不予追踪
		if (hostClass == null || hostClass.startsWith("arc/") || hostClass.startsWith("java/") || hostClass.startsWith("nipx/")) {
			return;
		}

		String hostMethod = callbackFrame[1];
		String hostDesc   = callbackFrame[2];

		String key = hostClass + "#" + hostMethod + ":" + hostDesc;

		int seq;
		synchronized (methodCounters) {
			int[] counter = methodCounters.computeIfAbsent(key, _ -> new int[]{0});
			seq = counter[0]++;
		}

		CellIdentity id = new CellIdentity(hostClass, hostMethod, hostDesc, seq);

		synchronized (cellToId) {
			cellToId.put(cell, id);
		}
		idToCell.put(id, new WeakReference<>(cell));
		classToCells.computeIfAbsent(hostClass, _ -> new CopyOnWriteArrayList<>()).add(id);

		if (DEBUG) {
			log("[CellProperty] Registered at creation: " + id.hostClass + "#"
			    + id.hostMethod + "[" + id.sequence + "]");
		}
	}

	public static void registerCell(Cell<?> cell, String hostClass, String hostMethod, String hostDesc) {
		if (!enabled || cell == null) return;

		// 避免因构造阶段已被成功捕捉后的重复注册
		synchronized (cellToId) {
			if (cellToId.containsKey(cell)) return;
		}

		String key = hostClass + "#" + hostMethod + ":" + hostDesc;
		int    seq;
		synchronized (methodCounters) {
			int[] counter = methodCounters.computeIfAbsent(key, _ -> new int[]{0});
			seq = counter[0]++;
		}

		CellIdentity id = new CellIdentity(hostClass, hostMethod, hostDesc, seq);

		synchronized (cellToId) {
			cellToId.put(cell, id);
		}
		idToCell.put(id, new WeakReference<>(cell));
		classToCells.computeIfAbsent(hostClass, _ -> new CopyOnWriteArrayList<>()).add(id);
	}

	public static void recordPropertyCall(Cell<?> cell, String method, String desc, Object[] args) {
		if (!enabled || cell == null) return;

		CellIdentity id;
		synchronized (cellToId) {
			id = cellToId.get(cell);
		}
		if (id == null) {
			id = inferCellIdentity(cell);
			if (id == null) return;

			synchronized (cellToId) {
				cellToId.put(cell, id);
			}
			idToCell.put(id, new WeakReference<>(cell));
			classToCells.computeIfAbsent(id.hostClass, _ -> new CopyOnWriteArrayList<>()).add(id);
		}

		if (!records.containsKey(id)) {
			try {
				byte[] currentBytecode = fetchCurrentBytecode(Class.forName(id.hostClass.replace('/', '.')));
				if (currentBytecode != null) {
					Map<String, List<List<PropertyCall>>> currentChains = extractCellChains(currentBytecode);
					String                                methodKey     = id.hostMethod + ":" + id.hostDesc;
					List<List<PropertyCall>>              chains        = currentChains.get(methodKey);
					if (chains == null) {
						for (Map.Entry<String, List<List<PropertyCall>>> entry : currentChains.entrySet()) {
							if (entry.getKey().startsWith(id.hostMethod + ":")) {
								chains = entry.getValue();
								break;
							}
						}
					}
					if (chains != null && id.sequence < chains.size()) {
						records.put(id, new CopyOnWriteArrayList<>(chains.get(id.sequence)));
					}
				}
			} catch (Throwable t) {
				// 忽略提取失败
			}
			records.putIfAbsent(id, new CopyOnWriteArrayList<>());
		}

		int line = -1;
		if (DEBUG) {
			line = inferLineNumber();
		}

		List<PropertyCall> chain = records.get(id);
		if (chain != null) {
			boolean exists = false;
			for (PropertyCall pc : chain) {
				if (pc.method.equals(method) && argsEqual(pc.args, args)) {
					exists = true;
					break;
				}
			}
			if (!exists) {
				chain.add(new PropertyCall(method, desc, args, line));
			}
		}

		if (DEBUG) {
			log("[CellProperty] " + id.hostClass + "#" + id.hostMethod + "[" + id.sequence
			    + "] → " + method + "(" + argsString(args) + ")");
		}
	}
	//endregion

	//region 热替换回调
	public static void onClassRedefined(String slashName, byte[] newBytecode) {
		if (!enabled) return;

		info("[CellProperty] Class redefined: " + slashName);

		List<CellIdentity> cellIds = classToCells.get(slashName);
		if (cellIds == null || cellIds.isEmpty()) {
			if (DEBUG) log("[CellProperty] ; No tracked Cells for " + slashName);
			return;
		}

		Map<String, List<List<PropertyCall>>> newChainsByMethod = extractCellChains(newBytecode);

		int totalUpdated = 0;
		int totalSkipped = 0;
		int totalRemoved = 0;
		int totalAdded   = 0;

		List<CellIdentity>         idsSnapshot       = new ArrayList<>(cellIds);
		Map<CellIdentity, Integer> newChainIndexes   = mapCellsToNewChains(idsSnapshot, newChainsByMethod);
		Map<String, List<Integer>> newChainAdditions = findNewChainAdditions(idsSnapshot, newChainsByMethod, newChainIndexes);

		for (CellIdentity id : idsSnapshot) {
			Cell<?> cell = findCellById(id);
			if (cell == null) {
				totalSkipped++;
				continue;
			}

			List<List<PropertyCall>> newChains = findChainsForId(newChainsByMethod, id);
			Integer                  newIndex  = newChainIndexes.get(id);

			// 【一、删除逻辑】：如果新的调用链不再包含该 Cell (注释/删除)
			if (newChains == null || newIndex == null || newIndex >= newChains.size()) {
				Table table = cell.getTable();
				if (table != null) {
					BindCell bind = BindCell.of(cell);
					bind.remove();
					Pools.free(bind);
					try {
						table.getCells().remove(cell, true);
					} catch (Throwable t) {
						// 忽略
					}
					Core.app.post(table::invalidateHierarchy);
				}
				removeCell(cell);
				totalRemoved++;
				continue;
			}

			List<PropertyCall> newCalls = newChains.get(newIndex);
			List<PropertyCall> oldCalls = records.get(id);

			if (oldCalls == null || oldCalls.isEmpty()) {
				if (!newCalls.isEmpty() && isTableCellCreator(CL_TABLE, newCalls.get(0).method)) {
					updateChildElement(cell, newCalls.get(0));
				}
				List<PropertyCall> properties = newCalls.subList(newCalls.isEmpty() ? 0 : 1, newCalls.size());
				applyAllCalls(cell, properties);
				totalUpdated++;
			} else {
				boolean applied = applyDiff(cell, oldCalls, newCalls);
				if (applied) totalUpdated++;
			}

			records.put(id, newCalls);
		}

		// 【二、新增逻辑】：按宿主方法分别追加无法匹配到旧 Cell 的新调用链。
		for (Map.Entry<String, List<Integer>> additionEntry : newChainAdditions.entrySet()) {
			String                   methodKey = additionEntry.getKey();
			List<List<PropertyCall>> newChains = newChainsByMethod.get(methodKey);
			if (newChains == null) continue;

			List<CellIdentity> methodIds = new ArrayList<>();
			for (CellIdentity id : idsSnapshot) {
				if (methodKeyMatches(id, methodKey)) {
					methodIds.add(id);
				}
			}
			if (!methodIds.isEmpty()) {
				Table table = null;
				for (CellIdentity id : methodIds) {
					Cell<?> existingCell = findCellById(id);
					if (existingCell != null && existingCell.getTable() != null) {
						table = existingCell.getTable();
						break;
					}
				}

				if (table != null) {
					for (int seq : additionEntry.getValue()) {
						List<PropertyCall> newCalls = newChains.get(seq);
						if (newCalls == null || newCalls.isEmpty()) continue;

						PropertyCall creator = newCalls.get(0);
						try {
							Method method = findTableMethod(creator.method, creator.desc);
							if (method != null) {
								Object[] converted = creator.args == null ? null : convertArgs(method.getParameterTypes(), creator.args);
								Cell<?>  newCell   = (Cell<?>) method.invoke(table, converted);
								if (newCell != null) {
									CellIdentity newId = new CellIdentity(slashName, methodNameFromKey(methodKey), methodDescFromKey(methodKey), seq);
									synchronized (cellToId) {
										cellToId.put(newCell, newId);
									}
									idToCell.put(newId, new WeakReference<>(newCell));
									classToCells.computeIfAbsent(slashName, _ -> new CopyOnWriteArrayList<>()).add(newId);

									List<PropertyCall> properties = newCalls.subList(1, newCalls.size());
									applyAllCalls(newCell, properties);

									records.put(newId, newCalls);
									totalAdded++;
								}
							}
						} catch (Exception e) {
							error("[CellProperty] Failed to dynamically append new cell for creator: " + creator.method, e);
						}
					}
					Core.app.post(table::invalidateHierarchy);
				}
			}
		}

		info("[CellProperty] Updated: " + totalUpdated + ", Removed: " + totalRemoved
		     + ", Added: " + totalAdded + ", Skipped: " + totalSkipped + " for " + slashName);
	}
	//endregion

	private static Map<CellIdentity, Integer> mapCellsToNewChains(List<CellIdentity> idsSnapshot,
	                                                              Map<String, List<List<PropertyCall>>> newChainsByMethod) {
		Map<CellIdentity, Integer>      result      = new HashMap<>();
		Map<String, List<CellIdentity>> idsByMethod = new LinkedHashMap<>();
		for (CellIdentity id : idsSnapshot) {
			String methodKey = findMethodKeyForId(newChainsByMethod, id);
			if (methodKey != null) {
				idsByMethod.computeIfAbsent(methodKey, _ -> new ArrayList<>()).add(id);
			}
		}

		for (Map.Entry<String, List<CellIdentity>> entry : idsByMethod.entrySet()) {
			List<List<PropertyCall>> newChains = newChainsByMethod.get(entry.getKey());
			if (newChains == null) continue;

			Set<Integer> usedNewIndexes = new HashSet<>();

			// 先用旧记录与新链内容做相似匹配，避免中间插入/删除时按序号整体错位。
			for (CellIdentity id : entry.getValue()) {
				List<PropertyCall> oldCalls = records.get(id);
				if (oldCalls == null || oldCalls.isEmpty()) continue;

				int bestIndex = findBestChainIndex(newChains, oldCalls, usedNewIndexes);
				if (bestIndex >= 0) {
					result.put(id, bestIndex);
					usedNewIndexes.add(bestIndex);
				}
			}

			// 旧运行时没有记录时，才退回到原 sequence，但避免覆盖已经精确匹配的链。
			for (CellIdentity id : entry.getValue()) {
				if (result.containsKey(id)) continue;
				if (id.sequence < newChains.size() && !usedNewIndexes.contains(id.sequence)) {
					result.put(id, id.sequence);
					usedNewIndexes.add(id.sequence);
				}
			}
		}

		return result;
	}

	private static Map<String, List<Integer>> findNewChainAdditions(List<CellIdentity> idsSnapshot,
	                                                                Map<String, List<List<PropertyCall>>> newChainsByMethod,
	                                                                Map<CellIdentity, Integer> mappedIndexes) {
		Map<String, List<Integer>> additions = new LinkedHashMap<>();
		for (String methodKey : newChainsByMethod.keySet()) {
			List<List<PropertyCall>> newChains = newChainsByMethod.get(methodKey);
			if (newChains == null || newChains.isEmpty()) continue;

			boolean      hasTrackedCells = false;
			Set<Integer> usedIndexes     = new HashSet<>();
			for (CellIdentity id : idsSnapshot) {
				if (!methodKeyMatches(id, methodKey)) continue;
				hasTrackedCells = true;
				Integer index = mappedIndexes.get(id);
				if (index != null) usedIndexes.add(index);
			}
			if (!hasTrackedCells) continue;

			for (int i = 0; i < newChains.size(); i++) {
				if (!usedIndexes.contains(i)) {
					additions.computeIfAbsent(methodKey, _ -> new ArrayList<>()).add(i);
				}
			}
		}
		return additions;
	}

	private static int findBestChainIndex(List<List<PropertyCall>> newChains, List<PropertyCall> oldCalls,
	                                      Set<Integer> usedNewIndexes) {
		int bestIndex = -1;
		int bestScore = 0;
		for (int i = 0; i < newChains.size(); i++) {
			if (usedNewIndexes.contains(i)) continue;

			int score = chainSimilarity(oldCalls, newChains.get(i));
			if (score > bestScore) {
				bestScore = score;
				bestIndex = i;
			}
		}
		return bestScore > 0 ? bestIndex : -1;
	}

	private static int chainSimilarity(List<PropertyCall> oldCalls, List<PropertyCall> newCalls) {
		if (callsEqual(oldCalls, newCalls)) return Integer.MAX_VALUE;
		if (oldCalls == null || newCalls == null || oldCalls.isEmpty() || newCalls.isEmpty()) return 0;

		int          score      = 0;
		PropertyCall oldCreator = oldCalls.get(0);
		PropertyCall newCreator = newCalls.get(0);
		if (oldCreator.method.equals(newCreator.method)) score += 2;
		if (oldCreator.desc.equals(newCreator.desc)) score += 2;
		if (argsEqual(oldCreator.args, newCreator.args)) score += 6;

		int max = Math.min(oldCalls.size(), newCalls.size());
		for (int i = 1; i < max; i++) {
			PropertyCall oldCall = oldCalls.get(i);
			PropertyCall newCall = newCalls.get(i);
			if (oldCall.method.equals(newCall.method)) score++;
			if (oldCall.desc.equals(newCall.desc)) score++;
			if (argsEqual(oldCall.args, newCall.args)) score += 2;
		}
		return score;
	}

	private static List<List<PropertyCall>> findChainsForId(Map<String, List<List<PropertyCall>>> chainsByMethod,
	                                                        CellIdentity id) {
		String methodKey = findMethodKeyForId(chainsByMethod, id);
		return methodKey == null ? null : chainsByMethod.get(methodKey);
	}

	private static String findMethodKeyForId(Map<String, List<List<PropertyCall>>> chainsByMethod, CellIdentity id) {
		String exactKey = id.hostMethod + ":" + id.hostDesc;
		if (chainsByMethod.containsKey(exactKey)) {
			return exactKey;
		}
		for (String key : chainsByMethod.keySet()) {
			if (methodKeyMatches(id, key)) {
				return key;
			}
		}
		return null;
	}

	private static boolean methodKeyMatches(CellIdentity id, String methodKey) {
		if (methodKey.equals(id.hostMethod + ":" + id.hostDesc)) return true;
		return methodKey.startsWith(id.hostMethod + ":");
	}

	private static String methodNameFromKey(String methodKey) {
		int colon = methodKey.indexOf(':');
		return colon < 0 ? methodKey : methodKey.substring(0, colon);
	}

	private static String methodDescFromKey(String methodKey) {
		int colon = methodKey.indexOf(':');
		return colon < 0 ? "()V" : methodKey.substring(colon + 1);
	}

	//region 属性与子元素更新
	private static void applyAllCalls(Cell<?> cell, List<PropertyCall> calls) {
		for (PropertyCall call : calls) {
			if (hasUsableArgs(call)) {
				invokeCellMethod(cell, call.method, call.args);
			}
		}
	}

	private static boolean applyDiff(Cell<?> cell, List<PropertyCall> oldCalls,
	                                 List<CellPropertyRef.PropertyCall> newCalls) {
		PropertyCall oldCreator = oldCalls.isEmpty() ? null : oldCalls.get(0);
		PropertyCall newCreator = newCalls.isEmpty() ? null : newCalls.get(0);

		boolean elementUpdated = false;
		if (newCreator != null && isTableCellCreator(CL_TABLE, newCreator.method)) {
			if (oldCreator == null || !oldCreator.method.equals(newCreator.method) || !argsEqual(oldCreator.args, newCreator.args)) {
				updateChildElement(cell, newCreator);
				elementUpdated = true;
			}
		}

		List<PropertyCall> oldProperties = oldCalls.subList(oldCalls.isEmpty() ? 0 : 1, oldCalls.size());
		List<PropertyCall> newProperties = newCalls.subList(newCalls.isEmpty() ? 0 : 1, newCalls.size());

		boolean propertiesChanged = !callsEqual(oldProperties, newProperties);

		if (elementUpdated || propertiesChanged) {
			if (propertiesChanged) {
				resetCell(cell);
				applyAllCalls(cell, newProperties);
			}
			if (DEBUG) {
				log("[CellProperty] Reapplied property chain due to changes (Element updated: " + elementUpdated + ").");
			}
			return true;
		}
		return false;
	}


	private static void resetCell(Cell<?> cell) {
		cell.set(Cell.defaults());
		resetCellEndRow(cell);
	}
	private static void resetCellEndRow(Cell<?> cell) {
		if (f_endRow == null) {
			error("[CellProperty] Failed to reset Cell end-row flag", new NullPointerException("F_cell_endRow is null"));
			return;
		}
		try {
			f_endRow.setBoolean(cell, false);
		} catch (Throwable e) {
			error("[CellProperty] Failed to reset Cell end-row flag", e);
		}
	}

	private static void updateChildElement(Cell<?> cell, CellPropertyRef.PropertyCall creator) {
		Element oldElement = cell.get();

		if (oldElement != null) {
			// 如果旧元素已经是 Table 且新创建的也是 table，直接跳过替换过程
			// 这样内部的 Lambda 渲染的子元素就不会被干掉
			if ((creator.method.equals("table") && oldElement instanceof Table) ||
			    (creator.method.equals("pane") && oldElement instanceof ScrollPane) ||
			    (creator.method.equals("stack") && oldElement instanceof Stack)) {
				if (DEBUG) log("[CellProperty] Skipping replacement for container: " + creator.method);
				return;
			}
		}

		// 针对文本类元素的优化更新（无需销毁重建）
		if (oldElement != null && creator.args != null && creator.args.length > 0 && creator.args[0] instanceof String newText) {
			if (oldElement instanceof Label label) {
				label.setText(newText);
				return;
			}
			if (oldElement instanceof TextButton button) {
				button.setText(newText);
				return;
			}
		}

		Table table = cell.getTable();
		if (table == null) return;

		try {
			Method method = findTableMethod(creator.method, creator.desc);
			if (method == null) return;

			Table    dummyTable = new Table();
			Object[] converted  = creator.args == null ? null : convertArgs(method.getParameterTypes(), creator.args);

			// 如果 creator 是 table(cons)，这里会调用我们提供的空 Cons
			Cell<?> dummyCell = (Cell<?>) method.invoke(dummyTable, converted);
			if (dummyCell == null || dummyCell.get() == null) return;

			Element newElement = dummyCell.get();

			// 优化：使用 BindCell 替换子元素并恢复约束
			BindCell bind = BindCell.of(cell);
			bind.replace(newElement, false);
			Pools.free(bind);
		} catch (Exception e) {
			error("[CellProperty] Failed to update child element for creator: " + creator.method, e);
		}
	}

	private static Method findTableMethod(String name, String desc) {
		for (Method m : Table.class.getMethods()) {
			if (m.getName().equals(name) && Type.getMethodDescriptor(m).equals(desc)) {
				return m;
			}
		}
		return null;
	}

	private static boolean callsEqual(List<PropertyCall> a, List<PropertyCall> b) {
		if (a == b) return true;
		if (a == null || b == null) return false;
		if (a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) {
			PropertyCall ca = a.get(i);
			PropertyCall cb = b.get(i);
			if (!ca.method.equals(cb.method)) return false;
			if (!ca.desc.equals(cb.desc)) return false;
			if (!argsEqual(ca.args, cb.args)) return false;
		}
		return true;
	}

	private static boolean argsEqual(Object[] a, Object[] b) {
		if (a == b) return true;
		if (a == null || b == null) return false;
		if (a.length != b.length) return false;
		for (int i = 0; i < a.length; i++) {
			if (!Objects.equals(a[i], b[i])) return false;
		}
		return true;
	}

	private static boolean hasUsableArgs(PropertyCall call) {
		if (call.args == null) return true;
		for (Object arg : call.args) {
			if (arg == null) return false;
		}
		return true;
	}

	private static void invokeCellMethod(Cell<?> cell, String methodName, Object[] args) {
		try {
			int len = args == null ? 0 : args.length;
			if ("row".equals(methodName) && len == 0 && f_endRow != null) {
				f_endRow.setBoolean(cell, true);
				return;
			}
			Method method = findMatchingMethod(methodName, len);
			if (method == null) {
				error("[CellProperty] No matching method: " + methodName
				      + "(" + len + " args)");
				return;
			}
			// if (DEBUG) log("[CellProperty] Invoking " + method + ": " + argsString(args));

			Object[] converted = convertArgs(method.getParameterTypes(), args);
			method.invoke(cell, converted);
			if ("colspan".equals(methodName)) {
				Table table = cell.getTable();
				if (table == null) return;
				recalculateColumns(table);
				if (cell.hasElement()) cell.get().invalidateHierarchy();
				table.invalidate();
				table.layout();
				if (DEBUG) log("[CellProperty] Recalculated columns for colspan changed.");
			}
		} catch (Exception e) {
			error("[CellProperty] Failed to invoke " + methodName, e);
		}
	}

	private static Method findMatchingMethod(String name, int paramCount) {
		for (Method m : Cell.class.getMethods()) {
			if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
				return m;
			}
		}
		for (Method m : Table.class.getMethods()) {
			if (m.getName().equals(name) && m.getParameterCount() == paramCount
			    && Cell.class.isAssignableFrom(m.getReturnType())) {
				return m;
			}
		}
		return null;
	}

	private static Object[] convertArgs(Class<?>[] paramTypes, Object[] args) {
		if (args == null) return null;
		Object[] result = new Object[args.length];
		for (int i = 0; i < args.length; i++) {
			Class<?> target = paramTypes[i];
			if (args[i] == null) {
				if (!target.isInterface()) {
					result[i] = null;
					continue;
				}
				result[i] = Proxy.newProxyInstance(target.getClassLoader(), new Class<?>[]{target}, (proxy, method, methodArgs) -> {
					if (method.getDeclaringClass() == Object.class) return method.invoke(proxy, methodArgs);
					return null;
				});
				continue;
			}
			if (target == float.class) {
				result[i] = ((Number) args[i]).floatValue();
			} else if (target == int.class) {
				result[i] = ((Number) args[i]).intValue();
			} else if (target == boolean.class || target == Boolean.class) {
				if (args[i] instanceof Number) {
					result[i] = ((Number) args[i]).intValue() != 0;
				} else {
					result[i] = args[i];
				}
			} else if (target == String.class) {
				result[i] = args[i].toString();
			} else if (CharSequence.class.isAssignableFrom(target)) {
				result[i] = args[i].toString();
			} else {
				result[i] = args[i];
			}
		}
		return result;
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
			cr.accept(cn, ClassReader.EXPAND_FRAMES);

			for (MethodNode mn : cn.methods) {
				List<List<PropertyCall>> chains = extractFromMethod(cn, mn);
				if (!chains.isEmpty()) {
					result.put(mn.name + ":" + mn.desc, chains);
				}
			}
		} catch (Exception e) {
			error("[CellProperty] Failed to extract chains from bytecode", e);
		}

		return result;
	}

	private static boolean isTableClass(ClassNode cn, String owner) {
		if (CL_TABLE.equals(owner)) return true;
		if (cn != null && owner.equals(cn.name)) {
			if (cn.superName != null && (cn.superName.equals(CL_TABLE) || cn.superName.contains("Table"))) {
				return true;
			}
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
		if (isCellClass(owner) && CELL_PROPERTY_METHODS.contains(name) && (desc.endsWith(")L" + CL_CELL + ";") || "()V".equals(desc) && "row".equals(name))) {
			return true;
		}
		if (isTableClass(cn, owner) && CELL_PROPERTY_METHODS.contains(name)) {
			return true;
		}
		return false;
	}

	private static List<List<PropertyCall>> extractFromMethod(ClassNode cn, MethodNode mn) {
		List<List<PropertyCall>> chains = new ArrayList<>();
		if (mn.instructions == null) return chains;

		List<PropertyCall> currentChain = null;
		boolean            inCell       = false;

		if (DEBUG) {
			log("[CellProperty] Scanning method: " + mn.name + mn.desc);
		}

		for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode mi) {
				if (DEBUG) {
					log("  [Insn] " + mi.owner + " # " + mi.name + " " + mi.desc + " (inCell: " + inCell + ")");
				}

				if (isTableCellCreator(cn, mi.owner, mi.name)) {
					if (currentChain != null && !currentChain.isEmpty()) {
						chains.add(currentChain);
					}
					currentChain = new ArrayList<>();
					Object[] args = extractArgs(cn, mn, mi);
					currentChain.add(new PropertyCall(mi.name, mi.desc, args, -1));
					inCell = true;
					if (DEBUG) {
						log("    -> Matched Creator: " + mi.name);
					}
				} else if (inCell && isCellProperty(cn, mi.owner, mi.name, mi.desc)) {
					Object[] args = extractArgs(cn, mn, mi);
					currentChain.add(new PropertyCall(mi.name, mi.desc, args, -1));
					if (DEBUG) {
						log("    -> Matched Property: " + mi.name);
					}
				} else {
					if (inCell && !currentChain.isEmpty()) {
						chains.add(currentChain);
					}
					currentChain = null;
					inCell = false;
					if (DEBUG) {
						log("    -> Chain broken / not matched");
					}
				}
			}
		}

		if (currentChain != null && !currentChain.isEmpty()) {
			chains.add(currentChain);
		}

		return chains;
	}

	private static int getPushes(AbstractInsnNode insn) {
		int opcode = insn.getOpcode();
		if (opcode == ACONST_NULL) return 1;
		if (opcode >= ICONST_M1 && opcode <= DCONST_1) return 1;
		if (opcode == BIPUSH || opcode == SIPUSH || opcode == LDC) return 1;
		if (opcode >= ILOAD && opcode <= ALOAD) return 1;
		if (opcode >= IADD && opcode <= DREM) return 1;
		if (opcode >= ISHL && opcode <= LXOR) return 1;
		if (opcode >= INEG && opcode <= DNEG) return 1;
		if (opcode >= I2L && opcode <= I2S) return 1;
		if (opcode >= LCMP && opcode <= DCMPG) return 1;
		if (opcode == GETSTATIC) return 1;
		if (opcode == GETFIELD) return 1;
		if (opcode == NEW) return 1;
		if (opcode == DUP) return 1;
		if (opcode == ARRAYLENGTH || opcode == INSTANCEOF) return 1;
		if (opcode >= IALOAD && opcode <= SALOAD) return 1;
		if (insn instanceof MethodInsnNode mi) {
			Type retType = Type.getReturnType(mi.desc);
			return retType.getSort() == Type.VOID ? 0 : 1;
		}
		if (insn instanceof InvokeDynamicInsnNode indy) {
			Type retType = Type.getReturnType(indy.desc);
			return retType.getSort() == Type.VOID ? 0 : 1;
		}
		return 0;
	}

	private static int getPops(AbstractInsnNode insn) {
		int opcode = insn.getOpcode();
		if (opcode >= ISTORE && opcode <= ASTORE) return 1;
		if (opcode >= IADD && opcode <= DREM) return 2;
		if (opcode >= ISHL && opcode <= LXOR) return 2;
		if (opcode >= INEG && opcode <= DNEG) return 1;
		if (opcode >= I2L && opcode <= I2S) return 1;
		if (opcode >= LCMP && opcode <= DCMPG) return 2;
		if (opcode == GETFIELD) return 1;
		if (opcode == PUTFIELD) return 2;
		if (opcode == PUTSTATIC) return 1;
		if (opcode == DUP) return 1;
		if (opcode == ARRAYLENGTH || opcode == INSTANCEOF || opcode == ATHROW || opcode == MONITORENTER || opcode == MONITOREXIT) {
			return 1;
		}
		if (opcode >= IFEQ && opcode <= IFLE) return 1;
		if (opcode >= IF_ICMPEQ && opcode <= IF_ACMPNE) return 2;
		if (opcode == IFNULL || opcode == IFNONNULL) return 1;
		if (opcode >= IALOAD && opcode <= SALOAD) return 2;
		if (opcode >= IASTORE && opcode <= SASTORE) return 3;
		if (insn instanceof MethodInsnNode mi) {
			int pops = Type.getArgumentTypes(mi.desc).length;
			if (opcode != INVOKESTATIC) {
				pops += 1;
			}
			return pops;
		}
		if (insn instanceof InvokeDynamicInsnNode indy) {
			return Type.getArgumentTypes(indy.desc).length;
		}
		return 0;
	}

	private static AbstractInsnNode skipExpression(AbstractInsnNode insn) {
		if (insn == null) return null;
		int              needed = 1;
		AbstractInsnNode curr   = insn;
		while (curr != null) {
			int pops   = getPops(curr);
			int pushes = getPushes(curr);
			needed -= pushes;
			needed += pops;
			if (needed <= 0) {
				return curr.getPrevious();
			}
			curr = curr.getPrevious();
		}
		return null;
	}

	private static AbstractInsnNode getPrevArgInsn(AbstractInsnNode start, Type[] argTypes, int targetIdx) {
		AbstractInsnNode prev = start;
		for (int i = argTypes.length - 1; i >= 0 && prev != null; i--) {
			if (i == targetIdx) {
				return prev;
			}
			prev = skipExpression(prev);
		}
		return null;
	}

	private static Object[] extractArgs(ClassNode cn, MethodNode mn, MethodInsnNode target) {
		Type[] argTypes = Type.getArgumentTypes(target.desc);
		if (argTypes.length == 0) return null;

		Object[]    args            = new Object[argTypes.length];
		Set<String> visitingMethods = new HashSet<>();
		for (int i = 0; i < argTypes.length; i++) {
			AbstractInsnNode argInsn = getPrevArgInsn(target.getPrevious(), argTypes, i);
			args[i] = resolveConstant(cn, mn, argInsn, visitingMethods);
		}

		return args;
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
		if (val == null) return null;
		if (!(val instanceof Number n)) return null;
		return switch (opcode) {
			case INEG -> -n.intValue();
			case LNEG -> -n.longValue();
			case FNEG -> -n.floatValue();
			case DNEG -> -n.doubleValue();
			default -> null;
		};
	}

	private static Object resolveConstant(ClassNode cn, MethodNode mn, AbstractInsnNode insn,
	                                      Set<String> visitingMethods) {
		if (insn == null) return null;

		if (insn instanceof FieldInsnNode fin && insn.getOpcode() == GETSTATIC) {
			try {
				String   className = fin.owner.replace('/', '.');
				Class<?> clazz     = Class.forName(className, true, Vars.mods.mainLoader());
				Field    field     = clazz.getField(fin.name);
				return field.get(null);
			} catch (Throwable t) {
				// 忽略
			}
		}

		if (insn instanceof LdcInsnNode) {
			return ((LdcInsnNode) insn).cst;
		} else if (insn instanceof InsnNode) {
			int opcode = insn.getOpcode();
			switch (opcode) {
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
				case I2S: {
					AbstractInsnNode prev = insn.getPrevious();
					if (prev == null) break;

					Object val = resolveConstant(cn, mn, prev, visitingMethods);
					if (val instanceof Number num) {
						switch (opcode) {
							case I2L:
							case F2L:
							case D2L:
								return num.longValue();
							case I2F:
							case L2F:
							case D2F:
								return num.floatValue();
							case I2D:
							case L2D:
							case F2D:
								return num.doubleValue();
							case L2I:
							case F2I:
							case D2I:
								return num.intValue();
							case I2B:
								return num.byteValue();
							case I2C:
								return (char) num.intValue();
							case I2S:
								return num.shortValue();
						}
					}
					break;
				}
				case IADD:
				case ISUB:
				case IMUL:
				case IDIV:
				case LADD:
				case LSUB:
				case LMUL:
				case LDIV:
				case FADD:
				case FSUB:
				case FMUL:
				case FDIV:
				case DADD:
				case DSUB:
				case DMUL:
				case DDIV: {
					AbstractInsnNode rightInsn = insn.getPrevious();
					if (rightInsn != null) {
						AbstractInsnNode leftInsn = skipExpression(rightInsn);
						Object           rightVal = resolveConstant(cn, mn, rightInsn, visitingMethods);
						Object           leftVal  = resolveConstant(cn, mn, leftInsn, visitingMethods);
						return evaluateBinaryOp(opcode, leftVal, rightVal);
					}
					break;
				}
				case INEG:
				case LNEG:
				case FNEG:
				case DNEG: {
					AbstractInsnNode prev = insn.getPrevious();
					if (prev != null) {
						Object val = resolveConstant(cn, mn, prev, visitingMethods);
						return evaluateUnaryOp(opcode, val);
					}
					break;
				}
			}
		}
		if (insn instanceof IntInsnNode iin && (insn.getOpcode() == BIPUSH || insn.getOpcode() == SIPUSH)) {
			return iin.operand;
		}
		if (insn instanceof VarInsnNode vin) {
			int opcode = vin.getOpcode();
			if (opcode == ILOAD || opcode == FLOAD || opcode == LLOAD || opcode == DLOAD || opcode == ALOAD) {
				return resolveVar(cn, mn, vin.var, visitingMethods);
			}
		}
		return null;
	}

	private static boolean isConstantType(Object val) {
		return val instanceof String || val instanceof Number || val instanceof Boolean || val instanceof Character;
	}

	private static Object resolveVar(ClassNode cn, MethodNode mn, int varIndex, Set<String> visitingMethods) {
		String visitKey = mn.name + ":" + mn.desc + "#" + varIndex;
		if (visitingMethods.contains(visitKey)) {
			return null;
		}
		visitingMethods.add(visitKey);
		tryLabel:
		try {
			int              writes      = 0;
			AbstractInsnNode singleStore = null;
			for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof VarInsnNode vin) {
					int opcode = vin.getOpcode();
					if (opcode == ISTORE || opcode == LSTORE || opcode == FSTORE || opcode == DSTORE || opcode == ASTORE) {
						if (vin.var == varIndex) {
							writes++;
							singleStore = insn;
						}
					}
				} else if (insn instanceof IincInsnNode iin) {
					if (iin.var == varIndex) {
						writes++;
					}
				}
			}

			if (writes == 1 && singleStore != null) {
				AbstractInsnNode prev = singleStore.getPrevious();
				if (prev != null) {
					Object val = resolveConstant(cn, mn, prev, visitingMethods);
					if (isConstantType(val)) {
						return val;
					}
				}
				break tryLabel;
			}
			if (writes != 0) break tryLabel;

			int    localIndex = ((mn.access & ACC_STATIC) != 0) ? 0 : 1;
			Type[] args       = Type.getArgumentTypes(mn.desc);
			int    argIdx     = -1;
			for (int i = 0; i < args.length; i++) {
				if (localIndex == varIndex) {
					argIdx = i;
					break;
				}
				localIndex += args[i].getSize();
			}

			if (argIdx < 0 || (!mn.name.startsWith("lambda$") && !mn.name.contains("$lambda"))) break tryLabel;

			for (MethodNode parentMn : cn.methods) {
				for (AbstractInsnNode insn = parentMn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (!(insn instanceof InvokeDynamicInsnNode indy) || indy.bsmArgs == null || indy.bsmArgs.length <= 1) {
						continue;
					}
					for (Object bsmArg : indy.bsmArgs) {
						if (!(bsmArg instanceof Handle h) || !h.getName().equals(mn.name) || !h.getOwner().equals(cn.name)) {
							continue;
						}
						Type[] indyArgs = Type.getArgumentTypes(indy.desc);
						if (argIdx >= indyArgs.length) continue;

						Object val = extractIndyArg(cn, parentMn, indy, argIdx, visitingMethods);
						if (isConstantType(val)) {
							return val;
						}
					}
				}
			}
		} finally {
			visitingMethods.remove(visitKey);
		}
		return null;
	}

	private static Object extractIndyArg(ClassNode cn, MethodNode mn, InvokeDynamicInsnNode indy, int argIdx,
	                                     Set<String> visitingMethods) {
		Type[]           argTypes = Type.getArgumentTypes(indy.desc);
		AbstractInsnNode argInsn  = getPrevArgInsn(indy.getPrevious(), argTypes, argIdx);
		return resolveConstant(cn, mn, argInsn, visitingMethods);
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

		bytes = CellPropertyRef.injectCell(bytes);
		if (bytes == null) return;

		Injector.redefineOneClass(cellClass, bytes);
		info("[CellProperty] Cell class redefined with property tracking");
	}

	public static byte[] injectCell(byte[] bytes) {
		ClassReader cr = new ClassReader(bytes);
		ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

		ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
			                                 String signature, String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

				// 核心突破：注入构造函数以确保任何隐式或无链式的 Cell 绝对捕获并生成对应的运行时 Sequence
				if (name.equals("<init>")) {
					return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
						@Override
						protected void onMethodExit(int opcode) {
							if (opcode == ATHROW) return;

							mv.visitVarInsn(ALOAD, 0);
							mv.visitMethodInsn(
							 INVOKESTATIC,
							 internalName(CellPropertyRef.class),
							 "registerCellAtCreation",
							 "(Larc/scene/ui/layout/Cell;)V",
							 false
							);
						}
					};
				}

				if (name.startsWith("<")) return mv;
				if (!CELL_PROPERTY_METHODS.contains(name)) return mv;
				if (!(descriptor.endsWith(")Larc/scene/ui/layout/Cell;") || ("row".equals(name) && "()V".equals(descriptor)))) {
					return mv;
				}

				return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
					@Override
					protected void onMethodExit(int opcode) {
						if (opcode == ATHROW) return;

						mv.visitVarInsn(ALOAD, 0);
						mv.visitLdcInsn(name);
						mv.visitLdcInsn(descriptor);

						Type[] argTypes = Type.getArgumentTypes(descriptor);
						pushArgsArray(mv, argTypes);

						mv.visitMethodInsn(
						 INVOKESTATIC,
						 internalName(CellPropertyRef.class),
						 "recordPropertyCall",
						 "(Larc/scene/ui/layout/Cell;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)V",
						 false
						);
					}
				};
			}
		};

		try {
			cr.accept(cv, ClassReader.EXPAND_FRAMES);
			return cw.toByteArray();
		} catch (Exception e) {
			error("[CellProperty] Failed to inject Cell class", e);
			return bytes;
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
			switch (t.getSort()) {
				case Type.INT:
					mv.visitVarInsn(ILOAD, localIdx);
					mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
					localIdx++;
					break;
				case Type.FLOAT:
					mv.visitVarInsn(FLOAD, localIdx);
					mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
					localIdx++;
					break;
				case Type.BOOLEAN:
					mv.visitVarInsn(ILOAD, localIdx);
					mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
					localIdx++;
					break;
				case Type.LONG:
					mv.visitVarInsn(LLOAD, localIdx);
					mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
					localIdx += 2;
					break;
				case Type.DOUBLE:
					mv.visitVarInsn(DLOAD, localIdx);
					mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
					localIdx += 2;
					break;
				default:
					mv.visitVarInsn(ALOAD, localIdx);
					localIdx++;
					break;
			}

			mv.visitInsn(AASTORE);
		}
	}

	private static void pushInt(MethodVisitor mv, int value) {
		if (value >= -1 && value <= 5) {
			mv.visitInsn(ICONST_0 + value);
		} else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
			mv.visitIntInsn(BIPUSH, value);
		} else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
			mv.visitIntInsn(SIPUSH, value);
		} else {
			mv.visitLdcInsn(value);
		}
	}
	//endregion

	//region 辅助方法
	private static Cell<?> findCellById(CellPropertyRef.CellIdentity target) {
		WeakReference<Cell<?>> ref = idToCell.get(target);
		return ref != null ? ref.get() : null;
	}

	private static CellIdentity inferCellIdentity(Cell<?> cell) {
		String[] callbackFrame = {null, null, null};
		int[]    x             = {-1};
		JVMTIEnv.getInstance().walkThreadFrames(MemorySegment.NULL, 12, 1, (className, methodName, methodDesc, thisAddr) -> {
			if (x[0] >= 0 && ++x[0] == 1) {
				callbackFrame[0] = className;
				callbackFrame[1] = methodName;
				callbackFrame[2] = methodDesc;
				return false;
			}
			if (CL_CELL.equals(className)) {
				x[0] = 0;
			}
			return true;
		});


		String callerClass = callbackFrame[0];

		// 如果直接调用者是库类，说明是内部嵌套调用，不作为外部宿主方法 Cell 追踪
		if (callerClass == null || callerClass.startsWith("arc/") || callerClass.startsWith("java/") || callerClass.startsWith("nipx/")) {
			return null;
		}

		String hostMethod = callbackFrame[1];
		String hostDesc   = callbackFrame[2];

		int seq = -1;
		try {
			Table table = cell.getTable();
			if (table != null) {
				var cells = table.getCells();
				if (cells != null) {
					seq = cells.indexOf(cell, true);
				}
			}
		} catch (Throwable t) {
			// 忽略
		}

		if (seq < 0) {
			String key = callerClass + "#" + hostMethod + ":" + hostDesc;
			synchronized (methodCounters) {
				int[] counter = methodCounters.computeIfAbsent(key, _ -> new int[]{0});
				seq = counter[0]++;
			}
		}

		return new CellIdentity(callerClass, hostMethod, hostDesc, seq);
	}

	private static int inferLineNumber() {
		StackTraceElement[] stack = Thread.currentThread().getStackTrace();
		for (StackTraceElement ste : stack) {
			String cn = ste.getClassName();
			if (!cn.startsWith("nipx.") && !cn.startsWith("arc.") && !cn.startsWith("java.")) {
				return ste.getLineNumber();
			}
		}
		return -1;
	}

	private static String argsString(Object[] args) {
		if (args == null) return "null";
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < args.length; i++) {
			if (i > 0) sb.append(", ");
			sb.append(args[i]);
		}
		return sb.append("]").toString();
	}
	//endregion

	//region 生命周期管理
	public static void enable() {
		enabled = true;
		info("[CellProperty] Enabled");
	}

	public static void disable() {
		enabled = false;
		info("[CellProperty] Disabled");
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static void clearAll() {
		synchronized (cellToId) {
			cellToId.clear();
		}
		idToCell.clear();
		records.clear();
		classToCells.clear();
		methodCounters.clear();
		info("[CellProperty] 🗑 Cleared all records");
	}

	public static void removeCell(Cell<?> cell) {
		CellIdentity id;
		synchronized (cellToId) {
			id = cellToId.remove(cell);
		}
		if (id != null) {
			idToCell.remove(id);
			records.remove(id);
			List<CellIdentity> list = classToCells.get(id.hostClass);
			if (list != null) list.remove(id);
		}
	}

	public static String getStats() {
		int cellToIdSize;
		synchronized (cellToId) {
			cellToIdSize = cellToId.size();
		}
		return "CellPropertyRef:\n"
		       + "  Enabled: " + enabled + "\n"
		       + "  Tracked Cells: " + cellToIdSize + "\n"
		       + "  Records: " + records.size() + "\n"
		       + "  Host Classes: " + classToCells.size();
	}
	//endregion

	//region Reflect
	private static final Field f_endRow  = nl(() -> Cell.class.getDeclaredField("endRow"));
	private static final Field f_colspan = nl(() -> Cell.class.getDeclaredField("colspan"));
	static class $table {
		static Field columnsField;

		static {
			try {
				columnsField = Table.class.getDeclaredField("columns");
				columnsField.setAccessible(true);
			} catch (NoSuchFieldException e) {
				columnsField = null;
			}
		}
	}
	@SuppressWarnings("rawtypes")
	static void recalculateColumns(Table table) throws IllegalAccessException {
		assert f_colspan != null;

		int       maxCols = 0;
		Seq<Cell> cells   = table.getCells();
		for (int i = 0; i < cells.size; ) {
			Cell c       = cells.get(i);
			int  rowCols = 0;
			do {
				rowCols += f_colspan.getInt(c);
				i++;
				if (i >= cells.size) break;
				c = cells.get(i);
			} while (!c.isEndRow());
			if (rowCols > maxCols) maxCols = rowCols;
		}

		// 使用反射设置 Table.columns
		try {
			$table.columnsField.setInt(table, maxCols);
		} catch (Exception e) {
			// fallback: 如果反射失败，至少 invalidate
			table.invalidate();
		}
	}
	private static <T> T nl(NLSupplier<T> supplier) {
		try {
			T t = supplier.get();
			if (t instanceof AccessibleObject ac) ac.setAccessible(true);
			return t;
		} catch (Throwable e) {
			error("[CellProperty] Failed to execute NLSupplier", e);
			return null;
		}
	}
	private interface NLSupplier<T> {
		T get() throws Throwable;
	}
	//endregion
}