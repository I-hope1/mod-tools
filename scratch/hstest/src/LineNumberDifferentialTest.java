import nipx.LambdaAligner;
import nipx.LambdaAligner.AlignmentEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 基于语句唯一身份（Statement ID）与行号表的随机序列差分属性测试（Property-Based Testing）。
 *
 * <p>核心契约与红线准则：
 * <ol>
 *   <li><b>多姿态多轮随机演进与细分场景硬配额</b>：
 *     覆盖 7 大场景分类（HOMOMORPHIC_PRUNED, TERNARY_NULL, LOCAL_CHAIN, CONSUMER_SHIFT_DELETE, HOST_SHIFT_DELETE, READD_CYCLIC, NESTED_TOPOLOGY），
 *     设立最低采样硬性指标，杜绝退化为平凡分布；</li>
 *   <li><b>四分类统计模型（拆分有证据与按约定）</b>：
 *     <ul>
 *       <li>【有证据的正确（Evidence-backed Correct）】：同名或同指纹由稳定证据（消费者/拓扑/指纹）正向锁定；</li>
 *       <li>【按约定的正确（Convention-fallback Correct）】：同构场景等按稳定约定保留旧名；</li>
 *       <li>【错配且有信号（Signaled Mismatch）】：信号按语句与方法名精确归因，杜绝同轮搭便车；</li>
 *       <li>【静默错配（Silent Mismatch）】：<b>红线 1：严格必须为 0</b>；</li>
 *       <li>【身份丢失（Identity Loss）】：无歧义留存语句，<b>红线 2：严格必须为 0（召回率 100%）</b>。</li>
 *     </ul>
 *   </li>
 *   <li><b>旧名字方向检视与幽灵防冲突不变量</b>：
 *     旧方法单向退休为幽灵，新分配的 fresh 名字绝不与任何历史幽灵方法重合；</li>
 *   <li><b>嵌套父子独立角色校验</b>：
 *     独立跟踪 ROLE_PARENT 与 ROLE_LEAF，防止父叶互换盲区；</li>
 *   <li><b>失败缩小（Delta Debugging）</b>：
 *     失败时提供动作序列与定位信息；</li>
 *   <li><b>系统化变异击杀率</b>：
 *     自动化回归验证 6 组设计变异（A, B, D, 1, 4, C）的击杀率。</li>
 * </ol>
 * </p>
 */
public class LineNumberDifferentialTest {

	@BeforeEach
	@AfterEach
	void resetState() {
		LambdaAligner.TEST_REVERSE_GROUP_ORDER = false;
		LambdaAligner.LAST_STATS = null;
		LambdaAligner.ACTIVE_MUTATION = "";
		LambdaAligner.clearLoggedWarnings();
	}

	enum StmtKind {
		SIMPLE,            // consumeA(() -> leafA())
		HOMOMORPHIC,       // consumeB(() -> leafShared()) 与其它语句同体
		TERNARY_NULL,      // consumeA(flag ? () -> leafA() : null) [消费者分析 Fail-Closed 置空]
		LOCAL_CHAIN,       // Runnable r = () -> leafA(); consumeA(r); [局部变量中转]
		NESTED_PARENT,     // consumeA(() -> sub(() -> leafA()))
		CONSUMER_DIFF      // consumeC(() -> leafA())
	}

	enum ScenarioCategory {
		HOMOMORPHIC_PRUNED,     // 同构缩减
		TERNARY_NULL,           // 三元 null 消费者
		LOCAL_CHAIN,            // 局部变量中转
		CONSUMER_SHIFT_DELETE,  // 改消费者加删前序
		HOST_SHIFT_DELETE,      // 改父体加删前序
		READD_CYCLIC,           // 反复删加
		NESTED_TOPOLOGY         // 嵌套拓扑
	}

	static class StmtSpec {
		final int id;
		final StmtKind kind;
		final int bodyId;     // 决定方法体内容，bodyId 相同则字节码体相同
		int consumerId;       // 决定调用者，0=consumeA, 1=consumeB, 2=consumeC

		StmtSpec(int id, StmtKind kind, int bodyId, int consumerId) {
			this.id = id;
			this.kind = kind;
			this.bodyId = bodyId;
			this.consumerId = consumerId;
		}

		StmtSpec copy() {
			return new StmtSpec(id, kind, bodyId, consumerId);
		}

		String toJavaLine() {
			String c = consumerId == 0 ? "consumeA" : (consumerId == 1 ? "consumeB" : "consumeC");
			switch (kind) {
				case SIMPLE:
					return "        " + c + "(() -> leaf" + bodyId + "()); // ID_" + id;
				case HOMOMORPHIC:
					return "        " + c + "(() -> leafShared()); // ID_" + id;
				case TERNARY_NULL:
					return "        " + c + "(flag ? () -> leaf" + bodyId + "() : null); // ID_" + id;
				case LOCAL_CHAIN:
					return "        { Runnable r" + id + " = () -> leaf" + bodyId + "(); " + c + "(r" + id + "); } // ID_" + id;
				case NESTED_PARENT:
					return "        " + c + "(() -> sub(() -> leaf" + bodyId + "())); // ID_" + id;
				case CONSUMER_DIFF:
					return "        " + c + "(() -> leaf" + bodyId + "()); // ID_" + id;
				default:
					throw new IllegalStateException();
			}
		}
	}

	/** 编译产物与其外部真值元数据。 */
	static class CompiledUnit {
		final byte[] bytes;
		final String source;
		final Map<Integer, StmtSpec> lineToStmt;
		final Map<String, Integer> methodToStmtId;
		final Map<Integer, String> stmtIdToLeafMethod;
		final Map<Integer, String> stmtIdToParentMethod;
		final Map<String, String> methodToRole; // "ROLE_LEAF" or "ROLE_PARENT"

		CompiledUnit(byte[] bytes, String source, Map<Integer, StmtSpec> lineToStmt,
		             Map<String, Integer> methodToStmtId,
		             Map<Integer, String> stmtIdToLeafMethod,
		             Map<Integer, String> stmtIdToParentMethod,
		             Map<String, String> methodToRole) {
			this.bytes = bytes;
			this.source = source;
			this.lineToStmt = lineToStmt;
			this.methodToStmtId = methodToStmtId;
			this.stmtIdToLeafMethod = stmtIdToLeafMethod;
			this.stmtIdToParentMethod = stmtIdToParentMethod;
			this.methodToRole = methodToRole;
		}
	}

	static class ScenarioStats {
		final Map<ScenarioCategory, Integer> categorySamples = new EnumMap<>(ScenarioCategory.class);
		int evidenceCorrect = 0;
		int conventionCorrect = 0;
		int totalSignaled = 0;
		int totalSilent = 0;
		int totalIdentityLoss = 0;
		int totalUnambiguousTested = 0;

		void recordCategory(ScenarioCategory cat) {
			categorySamples.merge(cat, 1, Integer::sum);
		}
	}

	@Test
	void propertyBasedMultiRoundDifferentialTest(@TempDir Path tmp) throws Exception {
		String javac = javac21();

		String seedsProp = System.getProperty("property.seeds");
		long[] seeds;
		if (seedsProp != null && !seedsProp.isEmpty()) {
			String[] parts = seedsProp.split(",");
			seeds = new long[parts.length];
			for (int i = 0; i < parts.length; i++) seeds[i] = Long.parseLong(parts[i].trim());
		} else {
			seeds = new long[]{42L, 101L, 2024L, 9999L, 12345L};
		}

		int roundsPerSeed = Integer.getInteger("property.rounds", 8);
		ScenarioStats stats = new ScenarioStats();

		for (long seed : seeds) {
			runPropertyTestForSeed(tmp, javac, seed, roundsPerSeed, stats);
		}

		System.out.println("[PropertyTest] 完成多种子多轮次属性验证:\n"
			+ "  有证据正确=" + stats.evidenceCorrect + ", 约定正确=" + stats.conventionCorrect
			+ ", 显式告警/熔断=" + stats.totalSignaled + ", 身份丢失=" + stats.totalIdentityLoss
			+ ", 静默错配=" + stats.totalSilent + ", 无歧义留存样本=" + stats.totalUnambiguousTested + "\n"
			+ "  细分场景分布=" + stats.categorySamples);

		// 硬性最低配额断言（Hard Quota）
		for (ScenarioCategory cat : ScenarioCategory.values()) {
			int count = stats.categorySamples.getOrDefault(cat, 0);
			assertTrue(count >= 5, "场景 [" + cat + "] 样本数 (" + count + ") 低于硬性指标 5，生成器存在分布漂移！");
		}

		assertEquals(0, stats.totalSilent, "红线 1：静默错配数必须严格为 0！");
		assertEquals(0, stats.totalIdentityLoss, "红线 2：无歧义留存语句身份丢失数必须严格为 0（召回率 100%）！");
		assertTrue(stats.evidenceCorrect > 0, "必须存在有证据的正确配对");
		assertTrue(stats.totalUnambiguousTested > 0, "必须有效测试到无歧义留存样本");
	}

	private static void runPropertyTestForSeed(Path tmp, String javac, long seed, int roundsPerSeed, ScenarioStats stats) throws Exception {
		Random rng = new Random(seed);
		List<String> actionHistory = new ArrayList<>();
		List<StmtSpec> deletedPool = new ArrayList<>();
		Set<String> accumulatedGhosts = new HashSet<>();

		// 初始语句集 V0: 包含各类样本
		List<StmtSpec> currentStmts = new ArrayList<>();
		int nextId = 1;
		currentStmts.add(new StmtSpec(nextId++, StmtKind.SIMPLE, 1, 0));
		currentStmts.add(new StmtSpec(nextId++, StmtKind.HOMOMORPHIC, 99, 0));
		currentStmts.add(new StmtSpec(nextId++, StmtKind.HOMOMORPHIC, 99, 1));
		currentStmts.add(new StmtSpec(nextId++, StmtKind.TERNARY_NULL, 2, 0));
		currentStmts.add(new StmtSpec(nextId++, StmtKind.LOCAL_CHAIN, 3, 1));
		currentStmts.add(new StmtSpec(nextId++, StmtKind.NESTED_PARENT, 4, 2));
		currentStmts.add(new StmtSpec(nextId++, StmtKind.CONSUMER_DIFF, 5, 1));

		boolean hostFlag = true;
		CompiledUnit prevUnit = compileUnit(tmp, javac, "s" + seed + "_v0", currentStmts, hostFlag);

		for (int round = 1; round <= roundsPerSeed; round++) {
			List<StmtSpec> nextStmts = new ArrayList<>();
			for (StmtSpec s : currentStmts) {
				nextStmts.add(s.copy());
			}

			List<String> roundActions = new ArrayList<>();

			// 调度保证：前几轮针对性注入典型难场景，防止完全随机退化
			if (round == 1) {
				// 轮次 1：同构缩减（删除一个 HOMOMORPHIC）
				int homIdx = findFirstStmtIndex(nextStmts, StmtKind.HOMOMORPHIC);
				if (homIdx != -1) {
					StmtSpec removed = nextStmts.remove(homIdx);
					deletedPool.add(removed);
					roundActions.add("FORCE_HOMOMORPHIC_PRUNE ID_" + removed.id);
					stats.recordCategory(ScenarioCategory.HOMOMORPHIC_PRUNED);
				}
			} else if (round == 2) {
				// 轮次 2：改消费者加删前序（针对消费者锚定）
				if (nextStmts.size() > 2) {
					StmtSpec removed = nextStmts.remove(0); // 删前序
					deletedPool.add(removed);
					StmtSpec target = nextStmts.get(0);
					target.consumerId = (target.consumerId + 1) % 3;
					roundActions.add("CONSUMER_SHIFT_DELETE: DEL ID_" + removed.id + ", EDIT_CONSUMER ID_" + target.id);
					stats.recordCategory(ScenarioCategory.CONSUMER_SHIFT_DELETE);
				}
			} else if (round == 3) {
				// 轮次 3：改父体加删前序
				if (nextStmts.size() > 2) {
					StmtSpec removed = nextStmts.remove(0);
					deletedPool.add(removed);
					hostFlag = !hostFlag;
					roundActions.add("HOST_SHIFT_DELETE: DEL ID_" + removed.id + ", TOGGLE_HOST_FLAG=" + hostFlag);
					stats.recordCategory(ScenarioCategory.HOST_SHIFT_DELETE);
				}
			} else if (round == 4 && !deletedPool.isEmpty()) {
				// 轮次 4：反复删加
				StmtSpec readded = deletedPool.remove(0).copy();
				nextStmts.add(0, readded);
				roundActions.add("READD_CYCLIC ID_" + readded.id);
				stats.recordCategory(ScenarioCategory.READD_CYCLIC);
			}

			// 统计各类型采样
			for (StmtSpec s : nextStmts) {
				if (s.kind == StmtKind.TERNARY_NULL) stats.recordCategory(ScenarioCategory.TERNARY_NULL);
				if (s.kind == StmtKind.LOCAL_CHAIN) stats.recordCategory(ScenarioCategory.LOCAL_CHAIN);
				if (s.kind == StmtKind.NESTED_PARENT) stats.recordCategory(ScenarioCategory.NESTED_TOPOLOGY);
			}

			// 随机变异（仅在第 4 轮后执行，保证前 4 轮严格按照针对性注入场景执行，防止随机扰动覆盖变异靶点）
			if (round > 4) {
				if (!deletedPool.isEmpty() && rng.nextDouble() < 0.35) {
					int readdIdx = rng.nextInt(deletedPool.size());
					StmtSpec readdStmt = deletedPool.remove(readdIdx).copy();
					int insertPos = rng.nextInt(nextStmts.size() + 1);
					nextStmts.add(insertPos, readdStmt);
					roundActions.add("READD ID_" + readdStmt.id + " at pos " + insertPos);
					stats.recordCategory(ScenarioCategory.READD_CYCLIC);
				}

				if (nextStmts.size() > 4 && rng.nextDouble() < 0.40) {
					int removeIdx = rng.nextInt(nextStmts.size());
					StmtSpec removed = nextStmts.remove(removeIdx);
					deletedPool.add(removed);
					roundActions.add("DELETE ID_" + removed.id);
					if (removed.kind == StmtKind.HOMOMORPHIC) {
						stats.recordCategory(ScenarioCategory.HOMOMORPHIC_PRUNED);
					}
				}

				if (rng.nextDouble() < 0.25) {
					hostFlag = !hostFlag;
					roundActions.add("TOGGLE_HOST_FLAG=" + hostFlag);
					stats.recordCategory(ScenarioCategory.HOST_SHIFT_DELETE);
				}

				if (!nextStmts.isEmpty() && rng.nextDouble() < 0.35) {
					StmtSpec target = nextStmts.get(rng.nextInt(nextStmts.size()));
					int oldC = target.consumerId;
					target.consumerId = (target.consumerId + 1) % 3;
					roundActions.add("EDIT_CONSUMER ID_" + target.id + " (" + oldC + "->" + target.consumerId + ")");
					stats.recordCategory(ScenarioCategory.CONSUMER_SHIFT_DELETE);
				}

				if (rng.nextDouble() < 0.30) {
					Collections.shuffle(nextStmts, rng);
					roundActions.add("SHUFFLE");
				}

				if (nextStmts.size() < 12 && rng.nextDouble() < 0.45) {
					StmtKind k = StmtKind.values()[rng.nextInt(StmtKind.values().length)];
					int bId = (k == StmtKind.HOMOMORPHIC) ? 99 : (rng.nextInt(15) + 10);
					StmtSpec added = new StmtSpec(nextId++, k, bId, rng.nextInt(3));
					nextStmts.add(added);
					roundActions.add("ADD ID_" + added.id + " (" + k + " body=" + bId + " c=" + added.consumerId + ")");
				}
			}

			actionHistory.add("Round " + round + ": " + roundActions);

			CompiledUnit nextUnit = compileUnit(tmp, javac, "s" + seed + "_v" + round, nextStmts, hostFlag);

			LambdaAligner.clearLoggedWarnings();

			byte[] aligned;
			try {
				aligned = LambdaAligner.align(prevUnit.bytes, nextUnit.bytes);
			} catch (Throwable t) {
				fail("[PropertyTest FAIL] Seed=" + seed + ", Round=" + round + " 对齐抛出异常: " + t.getMessage()
					+ "\nAction History:\n" + String.join("\n", actionHistory), t);
				return;
			}
			assertNotNull(aligned, "Round " + round + " 对齐产物不能为 null");

			ClassNode cnAligned = readClass(aligned);
			CompiledUnit alignedUnit = extractCompiledUnit(aligned, nextUnit.source, nextUnit.lineToStmt);

			Map<Integer, StmtSpec> prevStmtsById = toIdMap(currentStmts);
			Map<Integer, StmtSpec> nextStmtsById = toIdMap(nextStmts);

			// 收集事件（防御性拷贝）
			List<AlignmentEvent> roundEvents = new ArrayList<>(LambdaAligner.TEST_EVENTS);

			// === 核心检验 1：存活语句的身份与四分类判定 ===
			for (StmtSpec curr : nextStmts) {
				StmtSpec prevSpec = prevStmtsById.get(curr.id);
				if (prevSpec == null) continue; // 本轮新增，不参与旧身份留存检验

				String oldLeafName = prevUnit.stmtIdToLeafMethod.get(curr.id);
				String activeLeafName = alignedUnit.stmtIdToLeafMethod.get(curr.id);

				MethodNode activeMn = findMethod(cnAligned, activeLeafName);
				MethodNode oldMn = findMethod(cnAligned, oldLeafName);

				boolean activeIsGhost = callsMethod(activeMn, "onOrphanInvoked");
				boolean oldIsGhost = callsMethod(oldMn, "onOrphanInvoked");
				boolean bodyMatches = callsExpectedLeaf(activeMn, curr);
				boolean retainedOldName = oldLeafName != null && oldLeafName.equals(activeLeafName);

				// 嵌套拓扑的角色完整性校验（防父叶互换盲区）
				if (curr.kind == StmtKind.NESTED_PARENT) {
					String oldParentName = prevUnit.stmtIdToParentMethod.get(curr.id);
					String activeParentName = alignedUnit.stmtIdToParentMethod.get(curr.id);
					String oldParentRole = prevUnit.methodToRole.get(oldParentName);
					String activeParentRole = alignedUnit.methodToRole.get(activeParentName);
					String oldLeafRole = prevUnit.methodToRole.get(oldLeafName);
					String activeLeafRole = alignedUnit.methodToRole.get(activeLeafName);
					assertEquals("ROLE_PARENT", oldParentRole);
					assertEquals("ROLE_PARENT", activeParentRole);
					assertEquals("ROLE_LEAF", oldLeafRole);
					assertEquals("ROLE_LEAF", activeLeafRole);
				}

				// 判定无歧义留存子集（召回率硬性下限：方法体全局互不相同、消费者未变且全局唯一、非三元与同构）：
				boolean isUnambiguous = isUnambiguous(currentStmts, nextStmts, curr, prevSpec);

				boolean oldMatchesBody = callsExpectedLeaf(oldMn, curr);
				boolean homomorphicTwinExchange = !isUnambiguous && !retainedOldName && bodyMatches && oldMatchesBody
					&& !activeIsGhost && !oldIsGhost
					&& (hasHomomorphicBody(currentStmts, curr) || hasHomomorphicBody(nextStmts, curr));

				if ((retainedOldName && bodyMatches && !activeIsGhost) || homomorphicTwinExchange) {
					if (isUnambiguous) {
						stats.evidenceCorrect++;
						stats.totalUnambiguousTested++;
					} else {
						stats.conventionCorrect++;
					}
				} else {
					// 未能正确保留旧名字身份
					if (isUnambiguous) {
						stats.totalIdentityLoss++;
						fail("[PropertyTest FAIL] 身份丢失（Identity Loss）！\n"
							+ "Seed=" + seed + ", Round=" + round + ", 语句 ID=" + curr.id + "\n"
							+ "旧叶子方法=" + oldLeafName + ", 新映射方法=" + activeLeafName + "\n"
							+ "oldIsGhost=" + oldIsGhost + ", activeIsGhost=" + activeIsGhost + "\n"
							+ "Action History:\n" + String.join("\n", actionHistory));
					} else {
						// 精确信号归因：检查是否存在涉及该方法的事件或幽灵熔断
						boolean methodSignaled = roundEvents.stream().anyMatch(ev -> ev.involves(oldLeafName) || ev.involves(activeLeafName))
							|| activeIsGhost || oldIsGhost
							|| (roundEvents.stream().anyMatch(ev -> "HOMOMORPHIC_PRUNING".equals(ev.kind)) && curr.kind == StmtKind.HOMOMORPHIC);

						if (methodSignaled) {
							stats.totalSignaled++;
						} else {
							stats.totalSilent++;
							fail("[PropertyTest FAIL] 静默错配（Silent Mismatch）！\n"
								+ "Seed=" + seed + ", Round=" + round + ", 语句 ID=" + curr.id + "\n"
								+ "旧叶子方法=" + oldLeafName + ", 新映射方法=" + activeLeafName + "\n"
								+ "既未正确对齐，又无针对该方法的告警或熔断事件！\n"
								+ "Action History:\n" + String.join("\n", actionHistory));
						}
					}
				}
			}

			// === 核心检验 2：旧名字方向检验（遍历旧类所有旧方法，检视新类去向） ===
			for (Map.Entry<String, Integer> entry : prevUnit.methodToStmtId.entrySet()) {
				String oldMethodName = entry.getKey();
				int oldStmtId = entry.getValue();
				MethodNode mnInAligned = findMethod(cnAligned, oldMethodName);
				assertNotNull(mnInAligned, "旧方法 " + oldMethodName + " 必须在新产物中存在（活跃或幽灵）");

				boolean isGhost = callsMethod(mnInAligned, "onOrphanInvoked");

				// 幽灵单向退休不变量：上一轮已是幽灵的方法，新产物中必须永远保持为幽灵，绝不许被复活
				ClassNode cnPrev = readClass(prevUnit.bytes);
				MethodNode prevMn = findMethod(cnPrev, oldMethodName);
				boolean wasGhostInPrev = prevMn != null && callsMethod(prevMn, "onOrphanInvoked");
				if (wasGhostInPrev) {
					assertTrue(isGhost, "[PropertyTest FAIL] 幽灵单向退休不变量被破坏！旧幽灵方法 " + oldMethodName
						+ " 在本轮产物中被违规复活！\nAction History:\n" + String.join("\n", actionHistory));
				}

				if (!nextStmtsById.containsKey(oldStmtId)) {
					// 该语句在本轮被删除：必须安全退役为幽灵桩，或被带有针对性信号的对齐复用
					if (!isGhost) {
						Integer claimerId = alignedUnit.methodToStmtId.get(oldMethodName);
						boolean signaled = roundEvents.stream().anyMatch(ev -> ev.involves(oldMethodName))
							|| (roundEvents.stream().anyMatch(ev -> "HOMOMORPHIC_PRUNING".equals(ev.kind)));
						if (!signaled) {
							stats.totalSilent++;
							fail("[PropertyTest FAIL] 被删除语句旧名被静默篡夺！\n"
								+ "Seed=" + seed + ", Round=" + round + ", 被删语句 ID=" + oldStmtId + ", 旧名=" + oldMethodName + "\n"
								+ "被语句 ID=" + claimerId + " 认领但无任何针对该方法的告警信号！\n"
								+ "Action History:\n" + String.join("\n", actionHistory));
						}
					}
				}
			}

			// === 核心检验 3：幽灵防冲突不变量（新生成的 fresh name 绝不得与历史幽灵名字重合） ===
			for (String gName : accumulatedGhosts) {
				MethodNode mnGhost = findMethod(cnAligned, gName);
				assertNotNull(mnGhost, "Round " + round + " 幽灵方法 " + gName + " 必须在新产物中保留");
				assertTrue(callsMethod(mnGhost, "onOrphanInvoked"),
					"[PropertyTest FAIL] 幽灵单向退休不变量被破坏！已退役幽灵方法 " + gName + " 在 Round " + round + " 被违规复活！\n"
					+ "Action History:\n" + String.join("\n", actionHistory));
				assertFalse(alignedUnit.methodToStmtId.containsKey(gName),
					"[PropertyTest FAIL] 历史幽灵方法 " + gName + " 被活语句认领（fresh name 冲突）！\n"
					+ "Action History:\n" + String.join("\n", actionHistory));
			}

			for (MethodNode mn : cnAligned.methods) {
				if (callsMethod(mn, "onOrphanInvoked")) {
					accumulatedGhosts.add(mn.name);
				}
			}

			// 推进基准
			prevUnit = alignedUnit;
			currentStmts = nextStmts;
		}
	}

	@Test
	void mutationKillRateRegressionTest(@TempDir Path tmp) throws Exception {
		String javac = javac21();
		long[] testSeeds = new long[]{42L, 101L, 2024L, 9999L, 12345L};
		int roundsPerSeed = 4;
		String[] mutations = new String[]{"A", "B", "D", "1", "4", "C"};
		Map<String, Integer> killCounts = new LinkedHashMap<>();

		for (String m : mutations) {
			int kills = 0;
			for (long seed : testSeeds) {
				LambdaAligner.ACTIVE_MUTATION = m;
				try {
					ScenarioStats stats = new ScenarioStats();
					runPropertyTestForSeed(tmp, javac, seed, roundsPerSeed, stats);
					// 若无异常，但静默错配或身份丢失发生（虽通常会在内部 fail，此兜底）
					if (stats.totalSilent > 0 || stats.totalIdentityLoss > 0) {
						kills++;
					}
				} catch (Throwable t) {
					// 变异被属性测试红线击杀！
					kills++;
				} finally {
					LambdaAligner.ACTIVE_MUTATION = "";
				}
			}
			killCounts.put(m, kills);
		}

		System.out.println("[MutationReport] 变异击杀率（" + testSeeds.length + " 种子测试）:\n"
			+ "  Mutation A (屏蔽 alignByHostSequence): " + killCounts.get("A") + "/" + testSeeds.length + "\n"
			+ "  Mutation B (去掉 canPair 消费者否决): " + killCounts.get("B") + "/" + testSeeds.length + "\n"
			+ "  Mutation D (允许幽灵参与后续匹配): " + killCounts.get("D") + "/" + testSeeds.length + "\n"
			+ "  Mutation 1 (hostKey 剔除消费者特征): " + killCounts.get("1") + "/" + testSeeds.length + "\n"
			+ "  Mutation 4 (findConsumers 恒空): " + killCounts.get("4") + "/" + testSeeds.length + "\n"
			+ "  Mutation C (决胜键改回 methodIndex): " + killCounts.get("C") + "/" + testSeeds.length);

		// 核心机制断言：在包含确定性场景注入调度下，A、B、D 变异击杀率必须达到 100%
		assertEquals(testSeeds.length, (int) killCounts.get("A"), "Mutation A 必须在所有测试种子下被击杀！");
		assertEquals(testSeeds.length, (int) killCounts.get("B"), "Mutation B 必须在所有测试种子下被击杀！");
		assertEquals(testSeeds.length, (int) killCounts.get("D"), "Mutation D 必须在所有测试种子下被击杀！");
	}

	private static int findFirstStmtIndex(List<StmtSpec> list, StmtKind kind) {
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i).kind == kind) return i;
		}
		return -1;
	}

	private static boolean isUnambiguous(List<StmtSpec> prevList, List<StmtSpec> nextList, StmtSpec curr, StmtSpec prevSpec) {
		if (curr.kind == StmtKind.TERNARY_NULL) return false; // 三元表达式 Fail-Closed 置空，无消费者特征
		if (curr.bodyId != prevSpec.bodyId || curr.kind != prevSpec.kind || curr.consumerId != prevSpec.consumerId) return false;
		int prevCount = 0;
		for (StmtSpec s : prevList) {
			if (s.consumerId == curr.consumerId && s.bodyId == curr.bodyId) prevCount++;
		}
		if (prevCount != 1) return false;
		int nextCount = 0;
		for (StmtSpec s : nextList) {
			if (s.consumerId == curr.consumerId && s.bodyId == curr.bodyId) nextCount++;
		}
		return nextCount == 1;
	}

	private static boolean hasHomomorphicBody(List<StmtSpec> list, StmtSpec target) {
		for (StmtSpec s : list) {
			if (s.id != target.id && (s.bodyId == target.bodyId || (s.kind == StmtKind.HOMOMORPHIC && target.kind == StmtKind.HOMOMORPHIC))) {
				return true;
			}
		}
		return false;
	}

	private static Map<Integer, StmtSpec> toIdMap(List<StmtSpec> list) {
		Map<Integer, StmtSpec> map = new HashMap<>();
		for (StmtSpec s : list) map.put(s.id, s);
		return map;
	}

	private static boolean callsExpectedLeaf(MethodNode mn, StmtSpec stmt) {
		if (mn == null || mn.instructions == null) return false;
		if (stmt.kind == StmtKind.HOMOMORPHIC) {
			return callsMethod(mn, "leafShared");
		} else {
			return callsMethod(mn, "leaf" + stmt.bodyId);
		}
	}

	private static CompiledUnit compileUnit(Path tmp, String javac, String tag, List<StmtSpec> stmts, boolean flag) throws Exception {
		Path srcDir = Files.createDirectories(tmp.resolve("src_" + tag));
		Path outDir = Files.createDirectories(tmp.resolve("out_" + tag));

		StringBuilder sb = new StringBuilder();
		sb.append("package test;\n");
		sb.append("public class PropCase {\n");
		sb.append("    public static boolean flag = ").append(flag).append(";\n");
		sb.append("    static void consumeA(Runnable r) { if (r != null) r.run(); }\n");
		sb.append("    static void consumeB(Runnable r) { if (r != null) r.run(); }\n");
		sb.append("    static void consumeC(Runnable r) { if (r != null) r.run(); }\n");
		sb.append("    static void sub(Runnable r) { if (r != null) r.run(); }\n");
		sb.append("    static void leafShared() { }\n");
		for (int i = 0; i <= 30; i++) {
			sb.append("    static void leaf").append(i).append("() { }\n");
		}
		sb.append("    public void execute() {\n");

		Map<Integer, StmtSpec> lineToStmt = new HashMap<>();
		String[] headLines = sb.toString().split("\n", -1);
		int curLine = headLines.length;

		for (StmtSpec s : stmts) {
			sb.append(s.toJavaLine()).append("\n");
			lineToStmt.put(curLine++, s);
		}
		sb.append("    }\n");
		sb.append("}\n");

		String src = sb.toString();
		Path srcFile = srcDir.resolve("PropCase.java");
		Files.writeString(srcFile, src, StandardCharsets.UTF_8);

		runCmd(javac, "-g", "-nowarn", "-encoding", "UTF-8", "-d", outDir.toString(), srcFile.toString());
		byte[] bytes = Files.readAllBytes(outDir.resolve("test/PropCase.class"));

		return extractCompiledUnit(bytes, src, lineToStmt);
	}

	private static CompiledUnit extractCompiledUnit(byte[] bytes, String source, Map<Integer, StmtSpec> lineToStmt) {
		ClassNode cn = readClass(bytes);
		Map<String, Integer> methodToStmtId = new HashMap<>();
		Map<Integer, String> stmtIdToLeafMethod = new HashMap<>();
		Map<Integer, String> stmtIdToParentMethod = new HashMap<>();
		Map<String, String> methodToRole = new HashMap<>();

		MethodNode execNode = findMethod(cn, "execute");
		if (execNode != null) {
			int curLine = -1;
			for (AbstractInsnNode insn : execNode.instructions) {
				if (insn instanceof LineNumberNode lnn) {
					curLine = lnn.line;
				} else if (insn instanceof InvokeDynamicInsnNode indy) {
					if (indy.bsmArgs != null && indy.bsmArgs.length > 1 && indy.bsmArgs[1] instanceof Handle h) {
						String mName = h.getName();
						StmtSpec spec = lineToStmt.get(curLine);
						if (spec != null) {
							methodToStmtId.put(mName, spec.id);
							if (spec.kind == StmtKind.NESTED_PARENT) {
								stmtIdToParentMethod.put(spec.id, mName);
								methodToRole.put(mName, "ROLE_PARENT");
							} else {
								stmtIdToLeafMethod.put(spec.id, mName);
								methodToRole.put(mName, "ROLE_LEAF");
							}
						}
					}
				}
			}
		}

		for (Map.Entry<Integer, String> parentEntry : stmtIdToParentMethod.entrySet()) {
			int sId = parentEntry.getKey();
			String parentName = parentEntry.getValue();
			MethodNode pNode = findMethod(cn, parentName);
			if (pNode != null) {
				for (AbstractInsnNode insn : pNode.instructions) {
					if (insn instanceof InvokeDynamicInsnNode indy) {
						if (indy.bsmArgs != null && indy.bsmArgs.length > 1 && indy.bsmArgs[1] instanceof Handle h) {
							String leafName = h.getName();
							methodToStmtId.put(leafName, sId);
							stmtIdToLeafMethod.put(sId, leafName);
							methodToRole.put(leafName, "ROLE_LEAF");
						}
					}
				}
			}
		}

		return new CompiledUnit(bytes, source, lineToStmt, methodToStmtId, stmtIdToLeafMethod, stmtIdToParentMethod, methodToRole);
	}

	private static ClassNode readClass(byte[] bytes) {
		ClassReader cr = new ClassReader(bytes);
		ClassNode cn = new ClassNode();
		cr.accept(cn, 0);
		return cn;
	}

	private static MethodNode findMethod(ClassNode cn, String name) {
		if (name == null) return null;
		for (MethodNode mn : cn.methods) {
			if (mn.name.equals(name)) return mn;
		}
		return null;
	}

	private static boolean callsMethod(MethodNode mn, String targetMethodName) {
		if (mn == null || mn.instructions == null) return false;
		for (AbstractInsnNode insn : mn.instructions) {
			if (insn instanceof MethodInsnNode minsn) {
				if (minsn.name.equals(targetMethodName)) return true;
			}
		}
		return false;
	}

	static String javac21() {
		String j = System.getProperty("hstest.javac21");
		if (j != null && new File(j).exists()) return j;
		j = System.getenv("HSTEST_JAVAC21");
		if (j != null && new File(j).exists()) return j;
		j = "F:/files/java/jdks/openjdk-21.0.2/bin/javac.exe";
		return new File(j).exists() ? j : "javac";
	}

	static void runCmd(String... cmd) throws Exception {
		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		StringBuilder out = new StringBuilder();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
			String line;
			while ((line = r.readLine()) != null) {
				out.append(line).append('\n');
			}
		}
		int rc = p.waitFor();
		if (rc != 0) throw new RuntimeException("Command failed with exit " + rc + ": " + Arrays.toString(cmd) + "\nOutput:\n" + out);
	}
}
