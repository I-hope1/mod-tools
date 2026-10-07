import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code SemAssert} 8 个场景的 JUnit 化（试点第二批）。
 *
 * <p>{@code SemAssert} 的 {@code check}/{@code main} 只累计计数、不直接 exit，所以无需重写：
 * 场景已抽成静态方法，这里逐场景调用并守各自的断言条数（sum = 40，与 suite.sh 输出一致）。</p>
 *
 * <p>刻意随机顺序：尽早暴露对 {@code HierarchyTree.register} 这类全局状态的隐式顺序依赖。
 * 6 个夹具组的 Case 类名互不相同，同组内每次 {@code force} 前都重新注册，预期无污染。</p>
 */
@TestMethodOrder(MethodOrderer.Random.class)
class SemAssertTest {

	static Fx fx;

	@BeforeAll
	static void init() {
		fx = new DirFx(21);
	}

	@BeforeEach
	void clean() {
		SemAssert.reset();
	}

	private void expect(int totalChecks) {
		assertEquals(List.of(), SemAssert.failures, "失败的断言");
		assertEquals(totalChecks, SemAssert.passed, "断言条数（防止断言被悄悄跳过）");
	}

	@Test
	void s1_swap2_delete() throws Exception {
		SemAssert.scenario1(fx);
		expect(5);
	}

	@Test
	void s2_leaf_insert() throws Exception {
		SemAssert.scenario2(fx);
		expect(3);
	}

	@Test
	void s3_leaf_both_edit() throws Exception {
		SemAssert.scenario3(fx);
		expect(3);
	}

	@Test
	void s4_deep_delete() throws Exception {
		SemAssert.scenario4(fx);
		expect(4);
	}

	@Test
	void s5_two_step2() throws Exception {
		SemAssert.scenario5(fx);
		expect(3);
	}

	@Test
	void s6_deep2_leaf_edit() throws Exception {
		SemAssert.scenario6(fx);
		expect(7);
	}

	/** 已知限制：当前用普通 check 钉住实际行为，迁移不改语义，仅打 tag。 */
	@Test
	@Tag("known-limitation")
	void s7_deep2_delete_and_edit() throws Exception {
		SemAssert.scenario7(fx);
		expect(5);
	}

	@Test
	void s8_save3() throws Exception {
		SemAssert.scenario8(fx);
		expect(10);
	}
}
