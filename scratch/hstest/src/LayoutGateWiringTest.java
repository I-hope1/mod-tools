import nipx.HotSwapAgent;
import nipx.HotSwapAgent.LayoutAction;
import nipx.HotSwapAgent.LayoutDecision;
import nipx.LayoutGate.Verdict;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 布局门<b>接线</b>的 JUnit 断言（§7.2 风险 1 的重定义层）。
 *
 * <p>与 {@code LayoutGateAssert} 的分工：那边守"规则表 + changedFields 格式对接"；本类守
 * {@link HotSwapAgent#decideLayout} 这个纯函数 —— 它把接线里最易错的部分（取哪份字节、
 * 拒绝要带哪些类、off/skip 两个出口）抽了出来，可脱离 Instrumentation 单测。</p>
 *
 * <p>调用位置（必须在 {@code applyRedefinitions} 之前）不在本类覆盖范围内，属已知缺口。</p>
 */
class LayoutGateWiringTest {

	private static final String HOST = "lg.Host";

	/** 编译一个顶层类，返回其字节码。 */
	private static byte[] cls(String dotName, String source) {
		Map<String, byte[]> out = LayoutGateAssert.compile(Map.of(dotName, source));
		byte[] b = out.get(dotName);
		assertNotNull(b, "夹具编译失败：" + dotName);
		return b;
	}

	private static byte[] withField()   { return cls(HOST, "package lg;\npublic class Host { int a; }\n"); }
	private static byte[] withoutField(){ return cls(HOST, "package lg;\npublic class Host { }\n"); }
	private static byte[] addedField()  { return cls(HOST, "package lg;\npublic class Host { int a; int b; }\n"); }
	private static byte[] retypedField(){ return cls(HOST, "package lg;\npublic class Host { long a; }\n"); }

	/** 批次类名集：宿主 + 两个匿名类 + 一个具名内部类（后者不得被整组带走）+ 无关类。 */
	private static final Set<String> BATCH = new LinkedHashSet<>(List.of(
		HOST, "lg.Host$1", "lg.Host$2", "lg.Host$Builder", "lg.Other"));

	@Test
	void removedFieldDropsWholeGroup() {
		LayoutDecision d = HotSwapAgent.decideLayout(HOST, withField(), withoutField(),
			"reject", BATCH);
		assertEquals(LayoutAction.REJECT, d.action(), "删除字段必须拒绝");
		assertEquals(Verdict.REMOVED_FIELD, d.layout().verdict(), "档位应为 REMOVED_FIELD");
		assertEquals(HOST, d.hostName());
		assertEquals(Set.of(HOST, "lg.Host$1", "lg.Host$2"), d.dropped(),
			"整组 = 宿主 + 匿名类；具名内部类 Host$Builder 与无关类不得被带走");
	}

	@Test
	void typeChangeDropsWholeGroup() {
		LayoutDecision d = HotSwapAgent.decideLayout(HOST, withField(), retypedField(),
			"reject", BATCH);
		assertEquals(LayoutAction.REJECT, d.action());
		assertEquals(Verdict.CHANGED_FIELD_TYPE, d.layout().verdict());
		assertTrue(d.dropped().contains(HOST) && d.dropped().contains("lg.Host$1"));
	}

	@Test
	void pureAddPasses() {
		LayoutDecision d = HotSwapAgent.decideLayout(HOST, withField(), addedField(),
			"reject", BATCH);
		assertEquals(LayoutAction.PASS, d.action(), "纯新增字段放行，交给 InitFix");
		assertTrue(d.layout().compatible());
		assertTrue(d.dropped().isEmpty(), "放行时不得移出任何类");
	}

	@Test
	void warnModePassesButReports() {
		LayoutDecision d = HotSwapAgent.decideLayout(HOST, withField(), withoutField(),
			"warn", BATCH);
		assertEquals(LayoutAction.PASS, d.action(), "warn 模式必须放行");
		assertNotNull(d.layout());
		assertFalse(d.layout().compatible(), "但规则结果必须非兼容，调用方据此强告警");
		assertTrue(d.dropped().isEmpty(), "warn 模式不产生移出集合");
	}

	@Test
	void offModeSkipsEntirely() {
		LayoutDecision d = HotSwapAgent.decideLayout(HOST, withField(), withoutField(),
			"off", BATCH);
		assertEquals(LayoutAction.PASS, d.action());
		assertNull(d.layout(), "off 模式完全不解析字节");
		assertTrue(d.dropped().isEmpty());
	}

	@Test
	void missingBaselineIsNotSilent() {
		LayoutDecision d = HotSwapAgent.decideLayout(HOST, null, withoutField(), "reject", BATCH);
		assertEquals(LayoutAction.SKIP, d.action(), "取不到旧字节 → SKIP（放行但调用方必须告警）");
		assertTrue(d.dropped().isEmpty());
		LayoutDecision d2 = HotSwapAgent.decideLayout(HOST, withField(), null, "reject", BATCH);
		assertEquals(LayoutAction.SKIP, d2.action());
	}

	@Test
	void nestedAnonHostResolvesToOuter() {
		LayoutDecision d = HotSwapAgent.decideLayout("lg.Host$1$2", withField(), withoutField(),
			"reject", new LinkedHashSet<>(List.of("lg.Host", "lg.Host$1", "lg.Host$1$2")));
		assertEquals("lg.Host", d.hostName(), "Foo$1$2 的宿主应归约到 Foo");
		assertEquals(LayoutAction.REJECT, d.action());
		assertTrue(d.dropped().contains("lg.Host$1") && d.dropped().contains("lg.Host$1$2"),
			"嵌套匿名类也归约到最外层宿主整组");
	}
}
