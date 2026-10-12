package nipx;

import nipx.annotation.CellHelper;
import nipx.uihook.CellPropertyRef;
import nipx.uihook.CellPropertyRef.CellIdentity;
import nipx.uihook.CellPropertyRef.PropertyCall;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CellHelperTest {

	@CellHelper
	static class WholeClassHelper {
		public static void m1() { }
		public static void m2() { }
	}

	static class MethodOnlyHelper {
		@CellHelper
		public static void helperMethod() { }

		public static void normalMethod() { }
	}

	@Test
	void testHelperAnnotationDetection() {
		String wholeClassSlash = WholeClassHelper.class.getName().replace('.', '/');
		assertTrue(CellPropertyRef.isHelper(wholeClassSlash, "m1", "()V"));
		assertTrue(CellPropertyRef.isHelper(wholeClassSlash, "m2", "()V"));

		String methodOnlySlash = MethodOnlyHelper.class.getName().replace('.', '/');
		assertTrue(CellPropertyRef.isHelper(methodOnlySlash, "helperMethod", "()V"));
		assertFalse(CellPropertyRef.isHelper(methodOnlySlash, "normalMethod", "()V"));
	}

	@Test
	void testProgrammaticRegisterHelper() {
		String customClass = "com/example/CustomHelper";
		assertFalse(CellPropertyRef.isHelper(customClass, "customMethod", "()V"));

		CellPropertyRef.registerHelper(customClass, "customMethod");
		assertTrue(CellPropertyRef.isHelper(customClass, "customMethod", "()V"));
		assertFalse(CellPropertyRef.isHelper(customClass, "otherMethod", "()V"));

		String wholeHelper = "com/example/WholeHelper";
		CellPropertyRef.registerHelper(wholeHelper);
		assertTrue(CellPropertyRef.isHelper(wholeHelper, "anyMethod", "()V"));
	}

	@Test
	void testCellOrderComparison() throws Exception {
		Method cmp = CellPropertyRef.class.getDeclaredMethod("compareCellOrder",
			int.class, int.class, int.class, int.class, int.class, int.class);
		cmp.setAccessible(true);

		// Primary key: caller line (40 vs 42)
		int r1 = (int) cmp.invoke(null, 40, -1, 0, 42, -1, 0);
		assertTrue(r1 < 0);

		// Secondary key: helper line when caller lines equal (line 42, helperLine 10 vs 15)
		int r2 = (int) cmp.invoke(null, 42, 10, 0, 42, 15, 0);
		assertTrue(r2 < 0);

		// Tertiary key: ordinal when lines and helperLines equal
		int r3 = (int) cmp.invoke(null, 42, 10, 0, 42, 10, 1);
		assertTrue(r3 < 0);

		// Equal
		int r4 = (int) cmp.invoke(null, 42, 10, 0, 42, 10, 0);
		assertEquals(0, r4);
	}

	@Test
	void testIdentityMatching() throws Exception {
		Method match = CellPropertyRef.class.getDeclaredMethod("matchesIdentity",
			CellIdentity.class, PropertyCall.class);
		match.setAccessible(true);

		// Normal cell matches normal creator
		CellIdentity directId = new CellIdentity("Host", "build", "()V", 40, "button");
		PropertyCall directCreator = new PropertyCall("arc/scene/ui/layout/Table", "button", "()Larc/scene/ui/layout/Cell;", new Object[0], 40);
		assertTrue((boolean) match.invoke(null, directId, directCreator));

		// Helper cell matches inlined helper creator
		CellIdentity helperId = new CellIdentity("Host", "build", "()V", 42, "image", "Underline", "of", 15);
		PropertyCall inlinedCreator = new PropertyCall("arc/scene/ui/layout/Table", "image", "()Larc/scene/ui/layout/Cell;", new Object[0], 42, "Underline", "of", 15);
		assertTrue((boolean) match.invoke(null, helperId, inlinedCreator));

		// Helper cell does not match non-helper creator
		assertFalse((boolean) match.invoke(null, helperId, directCreator));

		// Helper cell with different helperLine does not match
		CellIdentity diffLineId = new CellIdentity("Host", "build", "()V", 42, "image", "Underline", "of", 16);
		assertFalse((boolean) match.invoke(null, diffLineId, inlinedCreator));
	}
}
