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

	@Test
	void testGeometry16Snapshot() {
		arc.Application oldApp = arc.Core.app;
		try {
			arc.Core.app = (arc.Application) java.lang.reflect.Proxy.newProxyInstance(
				arc.Application.class.getClassLoader(),
				new Class<?>[]{arc.Application.class},
				(proxy, method, methodArgs) -> {
					if ("isDesktop".equals(method.getName())) return true;
					return null;
				}
			);
			arc.scene.ui.layout.Cell<?> c1 = new arc.scene.ui.layout.Cell<>();
			c1.size(64f, 32f);
			c1.pad(8f);

			CellPropertyRef.Geometry16 snap = new CellPropertyRef.Geometry16();
			snap.set(c1);

			assertNull(snap.get());
			assertNull(snap.getTable());

			arc.scene.ui.layout.Cell<?> c2 = new arc.scene.ui.layout.Cell<>();
			c2.set(snap);

			assertEquals(64f, c2.minWidth());
			assertEquals(32f, c2.minHeight());
		} finally {
			arc.Core.app = oldApp;
		}
	}

	@Test
	void testIsCellDefaults() {
		arc.scene.ui.layout.Cell<?> def = arc.scene.ui.layout.Cell.defaults();
		assertTrue(CellPropertyRef.isCellDefaults(def));

		arc.scene.ui.layout.Cell<?> unattached = new arc.scene.ui.layout.Cell<>();
		assertTrue(CellPropertyRef.isCellDefaults(unattached));

		arc.scene.ui.layout.Table table = new arc.scene.ui.layout.Table();
		arc.scene.ui.layout.Cell<?> attached = table.add();
		assertFalse(CellPropertyRef.isCellDefaults(attached));
	}

	@Test
	void testCapabilityBitSeparation() {
		// Constant creator args -> usable
		PropertyCall constantCreator = new PropertyCall("arc/scene/ui/layout/Table", "button",
			"(Ljava/lang/CharSequence;)Larc/scene/ui/layout/Cell;", new Object[]{"Click"}, 10);
		assertTrue(CellPropertyRef.hasUsableCreatorArgs(constantCreator));

		// Lambda creator args -> not usable for recreating element
		PropertyCall lambdaCreator = new PropertyCall("arc/scene/ui/layout/Table", "button",
			"(Ljava/lang/CharSequence;Ljava/lang/Runnable;)Larc/scene/ui/layout/Cell;",
			new Object[]{"Click", new CellPropertyRef.LambdaInfo("Host", "lambda$0", "()V", new Object[0])}, 10);
		assertFalse(CellPropertyRef.hasUsableCreatorArgs(lambdaCreator));

		// Unresolved/null creator args -> not usable
		PropertyCall unresolvedCreator = new PropertyCall("arc/scene/ui/layout/Table", "button",
			"(Ljava/lang/CharSequence;)Larc/scene/ui/layout/Cell;", new Object[]{null}, 10);
		assertFalse(CellPropertyRef.hasUsableCreatorArgs(unresolvedCreator));

		// Constant property args -> usable
		PropertyCall constantProp = new PropertyCall("arc/scene/ui/layout/Cell", "size",
			"(F)Larc/scene/ui/layout/Cell;", new Object[]{32f}, 11);
		assertTrue(CellPropertyRef.hasUsablePropertyArgs(constantProp));

		// Unresolved property args -> not usable
		PropertyCall unresolvedProp = new PropertyCall("arc/scene/ui/layout/Cell", "pad",
			"(F)Larc/scene/ui/layout/Cell;", new Object[]{null}, 12);
		assertFalse(CellPropertyRef.hasUsablePropertyArgs(unresolvedProp));
	}

	@Test
	void testTemplateCreatorDetection() {
		assertTrue(CellPropertyRef.isTemplateCreator("defaults"));
		assertFalse(CellPropertyRef.isTemplateCreator("button"));
		assertFalse(CellPropertyRef.isTemplateCreator("image"));

		PropertyCall tplHead = new PropertyCall("arc/scene/ui/layout/Table", "defaults",
			"()Larc/scene/ui/layout/Cell;", new Object[0], 10);
		assertTrue(CellPropertyRef.isTemplateCreator(tplHead));

		PropertyCall btnHead = new PropertyCall("arc/scene/ui/layout/Table", "button",
			"(Ljava/lang/CharSequence;)Larc/scene/ui/layout/Cell;", new Object[]{"OK"}, 12);
		assertFalse(CellPropertyRef.isTemplateCreator(btnHead));

		List<PropertyCall> tplChain = Arrays.asList(tplHead,
			new PropertyCall("arc/scene/ui/layout/Cell", "size", "(F)Larc/scene/ui/layout/Cell;", new Object[]{32f}, 10));
		List<PropertyCall> btnChain = Arrays.asList(btnHead);

		List<List<PropertyCall>> found = CellPropertyRef.findTemplateChains(Arrays.asList(tplChain, btnChain));
		assertEquals(1, found.size());
		assertSame(tplChain, found.get(0));
	}

	@Test
	void testMiddleOfChainActiveTemplate() {
		PropertyCall tpl1Head = new PropertyCall("arc/scene/ui/layout/Table", "defaults",
			"()Larc/scene/ui/layout/Cell;", new Object[0], 10);
		List<PropertyCall> tpl1 = Arrays.asList(tpl1Head,
			new PropertyCall("arc/scene/ui/layout/Cell", "size", "(F)Larc/scene/ui/layout/Cell;", new Object[]{32f}, 10));

		PropertyCall tpl2Head = new PropertyCall("arc/scene/ui/layout/Table", "defaults",
			"()Larc/scene/ui/layout/Cell;", new Object[0], 20);
		List<PropertyCall> tpl2 = Arrays.asList(tpl2Head,
			new PropertyCall("arc/scene/ui/layout/Cell", "size", "(F)Larc/scene/ui/layout/Cell;", new Object[]{64f}, 20));

		List<List<PropertyCall>> templates = Arrays.asList(tpl1, tpl2);

		// Cell at line 12 should match tpl1 (10 <= 12 < 20)
		assertSame(tpl1, CellPropertyRef.findActiveTemplate(templates, 12));

		// Cell at line 25 should match tpl2 (20 <= 25)
		assertSame(tpl2, CellPropertyRef.findActiveTemplate(templates, 25));

		// Cell at line 5 (before any template) should not match when multiple templates exist
		assertNull(CellPropertyRef.findActiveTemplate(templates, 5));

		// Single template fallback
		assertSame(tpl1, CellPropertyRef.findActiveTemplate(Collections.singletonList(tpl1), 5));
	}

	@Test
	void testEvaluateTemplateGeometry() {
		arc.Application oldApp = arc.Core.app;
		try {
			arc.Core.app = (arc.Application) java.lang.reflect.Proxy.newProxyInstance(
				arc.Application.class.getClassLoader(),
				new Class<?>[]{arc.Application.class},
				(proxy, method, methodArgs) -> {
					if ("isDesktop".equals(method.getName())) return true;
					return null;
				}
			);

			PropertyCall tplHead = new PropertyCall("arc/scene/ui/layout/Table", "defaults",
				"()Larc/scene/ui/layout/Cell;", new Object[0], 10);
			PropertyCall sizeCall = new PropertyCall("arc/scene/ui/layout/Cell", "size",
				"(FF)Larc/scene/ui/layout/Cell;", new Object[]{48f, 24f}, 10);

			CellPropertyRef.Geometry16 geom = CellPropertyRef.evaluateTemplateGeometry(Arrays.asList(tplHead, sizeCall));
			assertNotNull(geom);
			assertNull(geom.get());
			assertNull(geom.getTable());
			assertEquals(48f, geom.minWidth());
			assertEquals(24f, geom.minHeight());
		} finally {
			arc.Core.app = oldApp;
		}
	}

	@Test
	void testAnchorBasedLoopInsertion() throws Exception {
		CellPropertyRef.clearAll();
		arc.scene.ui.layout.Table table = new arc.scene.ui.layout.Table();
		arc.scene.Element e1 = new arc.scene.Element();
		arc.scene.Element e2 = new arc.scene.Element();
		arc.scene.ui.layout.Cell<?> b1 = table.add(e1);
		arc.scene.ui.layout.Cell<?> b2 = table.add(e2);

		CellIdentity btnId = new CellIdentity("TestHost", "build", "()V", 10, "add");

		Method regMethod = CellPropertyRef.class.getDeclaredMethod("registerCellLocked",
			CellIdentity.class, arc.scene.ui.layout.Cell.class, int.class);
		regMethod.setAccessible(true);

		java.lang.reflect.Field cellStateField = CellPropertyRef.class.getDeclaredField("cellState");
		cellStateField.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<Object, Object> cellStateMap = (Map<Object, Object>) cellStateField.get(null);

		Class<?> cellStateClass = Class.forName("nipx.uihook.CellPropertyRef$CellState");
		java.lang.reflect.Constructor<?> csCtor = cellStateClass.getDeclaredConstructor(
			CellIdentity.class, int.class, List.class);
		csCtor.setAccessible(true);

		Object st1 = csCtor.newInstance(btnId, 0, new ArrayList<>());
		Object st2 = csCtor.newInstance(btnId, 0, new ArrayList<>());

		synchronized (cellStateMap) {
			cellStateMap.put(b1, st1);
			cellStateMap.put(b2, st2);
			regMethod.invoke(null, btnId, b1, 0);
			regMethod.invoke(null, btnId, b2, 0);
		}

		PropertyCall addCreator = new PropertyCall("arc/scene/ui/layout/Table", "add",
			"()Larc/scene/ui/layout/Cell;", new Object[0], 12);
		List<PropertyCall> newChain = Collections.singletonList(addCreator);

		Method appendMethod = CellPropertyRef.class.getDeclaredMethod("appendNewCell",
			String.class, String.class, List.class, List.class);
		appendMethod.setAccessible(true);

		List<CellIdentity> existingIds = new ArrayList<>(Collections.singletonList(btnId));
		boolean inserted = (boolean) appendMethod.invoke(null, "TestHost", "build:()V", newChain, existingIds);

		assertTrue(inserted);
		assertEquals(4, table.getCells().size);
		assertSame(b1, table.getCells().get(0));
		assertSame(b2, table.getCells().get(2));

		boolean reinserted = (boolean) appendMethod.invoke(null, "TestHost", "build:()V", newChain, existingIds);
		assertFalse(reinserted);
		assertEquals(4, table.getCells().size);
	}
}
