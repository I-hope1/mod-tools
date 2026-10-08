package nipx;

import nipx.LayoutGate.FieldInfo;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 后置校验 {@code validateRenameMap} 的"改名后字段布局"守卫。
 *
 * <p>布局门为容纳 {@code this$N} 位移比较的是**屏蔽后**描述符（宽松近似）。若屏蔽后判等、但
 * 新描述符经最终 {@code renameMap} 改写后并不等于旧描述符（即新字段其实指向被映射到别处的匿名类），
 * 那是真实的类型变更，必须在后置校验整组拒绝，不能静默放行。</p>
 */
class FieldLayoutValidationTest {

	private static AnonClassAligner.AnonInfo info(String fieldName, String desc) {
		List<FieldInfo> fs = List.of(new FieldInfo(fieldName, desc, false, true));
		return new AnonClassAligner.AnonInfo(
			"X$1", new byte[]{}, 1L, "java/lang/Object", List.of(),
			"run", "()V", List.of(), List.of(), 1, null, fs, fs);
	}

	private static Map<AnonClassAligner.AnonInfo, AnonClassAligner.AnonInfo> pair(String newDesc, String oldDesc) {
		Map<AnonClassAligner.AnonInfo, AnonClassAligner.AnonInfo> m = new LinkedHashMap<>();
		m.put(info("val$x", newDesc), info("val$x", oldDesc));
		return m;
	}

	@Test
	void postRenameMismatchRejected() {
		// 新 val$x:LFoo$2; 屏蔽后与旧 val$x:LFoo$1; 判等（都可配对），但 Foo$2 最终映射到 Foo$3，
		// 改名后得到 LFoo$3; != LFoo$1; —— 真实类型变更，必须整组拒绝。
		Map<String, String> rename = new LinkedHashMap<>();
		rename.put("Foo$2", "Foo$3");
		var ex = assertThrows(AnonClassAligner.AlignmentRejectedException.class,
			() -> AnonClassAligner.validateRenameMap(rename, "X", pair("LFoo$2;", "LFoo$1;")));
		assertTrue(ex.reason.contains("post-rename field layout mismatch"), ex.reason);
	}

	@Test
	void postRenameMatchAccepted() {
		// 正确映射：Foo$2 -> Foo$1，改名后 LFoo$2; 变成 LFoo$1; == 旧值，放行。
		Map<String, String> rename = new LinkedHashMap<>();
		rename.put("Foo$2", "Foo$1");
		assertDoesNotThrow(() -> AnonClassAligner.validateRenameMap(rename, "X", pair("LFoo$2;", "LFoo$1;")));
	}
}
