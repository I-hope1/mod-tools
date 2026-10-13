package nipx;

import nipx.annotation.CellHelper;
import nipx.uihook.CellPropertyRef;
import nipx.uihook.CellPropertyRef.CellIdentity;
import nipx.uihook.CellPropertyRef.PropertyCall;
import nipx.uihook.CellPropertyRef.ThisPlaceholder;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
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

	static class SampleRebuildHost {
		boolean rebuilt = false;

		@nipx.annotation.HotRebuild
		public void rebuildUI() {
			this.rebuilt = true;
		}
	}

	static class SampleArgRebuildHost {
		String query = "init";
		int count = 42;
		String lastReceivedQuery = null;
		int lastReceivedCount = 0;

		@nipx.annotation.HotRebuild(argFields = {"query", "count"})
		public void rebuildWithArgs(String q, int c) {
			this.lastReceivedQuery = q;
			this.lastReceivedCount = c;
		}
	}

	static class SampleDirtyHost {
		boolean dirty = false;
		boolean rebuilt = false;

		public void forceChanged() {
			this.dirty = true;
		}

		@nipx.annotation.HotRebuild
		public void rebuild() {
			this.rebuilt = true;
		}
	}

	static class UnannotatedHost {
		boolean rebuilt = false;

		public void customRebuild() {
			this.rebuilt = true;
		}
	}

	@Test
	void testHotRebuildInvocationAndArgResolution() {
		SampleRebuildHost host1 = new SampleRebuildHost();
		CellPropertyRef.registerInstance(host1);
		assertTrue(CellPropertyRef.hasHotRebuild(SampleRebuildHost.class));
		assertFalse(host1.rebuilt);

		boolean ok = CellPropertyRef.triggerHotRebuild(SampleRebuildHost.class);
		assertTrue(ok);
		assertTrue(host1.rebuilt);
		CellPropertyRef.unregisterInstance(host1);

		SampleArgRebuildHost host2 = new SampleArgRebuildHost();
		host2.query = "customQuery";
		host2.count = 99;
		CellPropertyRef.registerInstance(host2);
		assertTrue(CellPropertyRef.hasHotRebuild(SampleArgRebuildHost.class));

		ok = CellPropertyRef.triggerHotRebuild(SampleArgRebuildHost.class);
		assertTrue(ok);
		assertEquals("customQuery", host2.lastReceivedQuery);
		assertEquals(99, host2.lastReceivedCount);
		CellPropertyRef.unregisterInstance(host2);

		SampleDirtyHost host3 = new SampleDirtyHost();
		CellPropertyRef.registerInstance(host3);
		assertFalse(host3.dirty);
		assertFalse(host3.rebuilt);

		ok = CellPropertyRef.triggerHotRebuild(SampleDirtyHost.class);
		assertTrue(ok);
		assertTrue(host3.dirty);
		assertTrue(host3.rebuilt);
		CellPropertyRef.unregisterInstance(host3);
	}

	@Test
	void testProgrammaticRegisterRebuildMethod() {
		UnannotatedHost host = new UnannotatedHost();
		CellPropertyRef.registerInstance(host);
		assertFalse(CellPropertyRef.hasHotRebuild(UnannotatedHost.class));

		CellPropertyRef.registerRebuildMethod(UnannotatedHost.class.getName(), "customRebuild");
		assertTrue(CellPropertyRef.hasHotRebuild(UnannotatedHost.class));

		boolean ok = CellPropertyRef.triggerHotRebuild(UnannotatedHost.class);
		assertTrue(ok);
		assertTrue(host.rebuilt);

		CellPropertyRef.unregisterRebuildMethod(UnannotatedHost.class.getName(), "customRebuild");
		CellPropertyRef.unregisterInstance(host);
	}

	@Test
	void testContainerConsRebuildAndTopLevelBlocking() {
		arc.scene.ui.layout.Table standalone = new arc.scene.ui.layout.Table();
		int[] standaloneBuilds = {0};
		arc.func.Cons<arc.scene.ui.layout.Table> standaloneCons = t -> standaloneBuilds[0]++;
		CellPropertyRef.setTableBuilder(standalone, standaloneCons);

		assertSame(standaloneCons, CellPropertyRef.getTableBuilder(standalone));
		assertEquals(0, standaloneBuilds[0]);

		// Direct container rebuild
		boolean ok = CellPropertyRef.triggerContainerRebuild(standalone);
		assertTrue(ok);
		assertEquals(1, standaloneBuilds[0]);
		CellPropertyRef.TRACKED_TABLES.remove(standalone);

		// Top-level blocking test: parent has child
		arc.scene.ui.layout.Table parent = new arc.scene.ui.layout.Table();
		arc.scene.ui.layout.Table child = new arc.scene.ui.layout.Table();
		parent.addChild(child);

		int[] parentBuilds = {0};
		int[] childBuilds = {0};

		arc.func.Cons<arc.scene.ui.layout.Table> parentCons = t -> {
			parentBuilds[0]++;
		};
		arc.func.Cons<arc.scene.ui.layout.Table> childCons = t -> {
			childBuilds[0]++;
		};

		CellPropertyRef.setTableBuilder(parent, parentCons);
		CellPropertyRef.setTableBuilder(child, childCons);

		String hostClass = Injector.getHostClassName(parentCons);
		if (hostClass != null) {
			String slash = hostClass.replace('.', '/');
			boolean rebuilt = CellPropertyRef.triggerContainerRebuild(slash);
			assertTrue(rebuilt);
			// Top-level blocking: parent was rebuilt, child was NOT redundantly invoked
			assertEquals(1, parentBuilds[0]);
			assertEquals(0, childBuilds[0]);
		}

		CellPropertyRef.TRACKED_TABLES.remove(parent);
		CellPropertyRef.TRACKED_TABLES.remove(child);
	}

	@Test
	void testContainerRebuildYieldsToHotRebuild() {
		arc.scene.ui.layout.Table table = new arc.scene.ui.layout.Table();
		int[] builds = {0};
		arc.func.Cons<arc.scene.ui.layout.Table> cons = t -> builds[0]++;
		CellPropertyRef.setTableBuilder(table, cons);

		// SampleRebuildHost has @HotRebuild, so triggerContainerRebuild must yield (return false)
		String slash = SampleRebuildHost.class.getName().replace('.', '/');
		boolean ok = CellPropertyRef.triggerContainerRebuild(slash);
		assertFalse(ok);
		assertEquals(0, builds[0]);

		CellPropertyRef.TRACKED_TABLES.remove(table);
	}

	@Test
	void testRegisteredCallbackRebuildAndFiltering() {
		arc.scene.Element elem = new arc.scene.Element();
		int[] resizedCount = {0};
		int[] updateCount = {0};

		Runnable resizedCb = () -> resizedCount[0]++;
		Runnable updateCb = () -> updateCount[0]++;

		CellPropertyRef.registerElementCallback(elem, resizedCb, "resized");
		CellPropertyRef.registerElementCallback(elem, updateCb, "update");

		// Direct rebuild on elem invokes both
		boolean ok = CellPropertyRef.triggerCallbackRebuild(elem);
		assertTrue(ok);
		assertEquals(1, resizedCount[0]);
		assertEquals(1, updateCount[0]);

		// slashName rebuild: "resized" is eligible and runs; "update" without Cell attribution is filtered out
		String host = Injector.getHostClassName(resizedCb);
		if (host != null) {
			String slash = host.replace('.', '/');
			resizedCount[0] = 0;
			updateCount[0] = 0;

			boolean rebuilt = CellPropertyRef.triggerCallbackRebuild(slash);
			assertTrue(rebuilt);
			assertEquals(1, resizedCount[0]);
			// Filtered: update had no Cell attribution
			assertEquals(0, updateCount[0]);
		}

		CellPropertyRef.unregisterElementCallback(elem, null);
	}

	@Test
	void testCallbackRebuildYieldsToHotRebuild() {
		arc.scene.Element elem = new arc.scene.Element();
		int[] count = {0};
		Runnable cb = () -> count[0]++;
		CellPropertyRef.registerElementCallback(elem, cb, "resized");

		// SampleRebuildHost has @HotRebuild, so callback rebuild must yield
		String slash = SampleRebuildHost.class.getName().replace('.', '/');
		boolean ok = CellPropertyRef.triggerCallbackRebuild(slash);
		assertFalse(ok);
		assertEquals(0, count[0]);

		CellPropertyRef.unregisterElementCallback(elem, null);
	}

	public static String sampleFiveArgStatic(String a, String b, String c, String d, String e) {
		return a + b + c + d + e;
	}

	public String sampleFiveArgInstance(String a, String b, String c, String d, String e) {
		return a + b + c + d + e;
	}

	@Test
	void testAsSpreaderArgumentArrayType() throws Throwable {
		Method invStatic = CellPropertyRef.class.getDeclaredMethod("invoke", MethodHandle.class, Object[].class);
		invStatic.setAccessible(true);
		MethodHandle mhStatic = MethodHandles.lookup().findStatic(CellHelperTest.class, "sampleFiveArgStatic",
			MethodType.methodType(String.class, String.class, String.class, String.class, String.class, String.class));
		String resStatic = (String) invStatic.invoke(null, mhStatic, new Object[]{"1", "2", "3", "4", "5"});
		assertEquals("12345", resStatic);

		Method invInst = CellPropertyRef.class.getDeclaredMethod("invoke", MethodHandle.class, Object.class, Object[].class);
		invInst.setAccessible(true);
		MethodHandle mhInst = MethodHandles.lookup().findVirtual(CellHelperTest.class, "sampleFiveArgInstance",
			MethodType.methodType(String.class, String.class, String.class, String.class, String.class, String.class));
		String resInst = (String) invInst.invoke(null, mhInst, this, new Object[]{"1", "2", "3", "4", "5"});
		assertEquals("12345", resInst);
	}

	@Test
	void testMakeDummyProxyPrimitiveDefaultReturns() throws Exception {
		Method m = CellPropertyRef.class.getDeclaredMethod("makeDummyProxy", Class.class);
		m.setAccessible(true);

		arc.func.Boolp boolProxy = (arc.func.Boolp) m.invoke(null, arc.func.Boolp.class);
		assertNotNull(boolProxy);
		assertFalse(boolProxy.get());

		arc.func.Floatp floatProxy = (arc.func.Floatp) m.invoke(null, arc.func.Floatp.class);
		assertNotNull(floatProxy);
		assertEquals(0.0f, floatProxy.get());

		arc.func.Intp intProxy = (arc.func.Intp) m.invoke(null, arc.func.Intp.class);
		assertNotNull(intProxy);
		assertEquals(0, intProxy.get());
	}

	@Test
	void testResolveThisPlaceholderPrecedenceAndSafety() throws Exception {
		Method m = CellPropertyRef.class.getDeclaredMethod("resolveThisPlaceholder",
			ThisPlaceholder.class, Class.class, arc.scene.ui.layout.Cell.class, arc.scene.ui.layout.Table.class);
		m.setAccessible(true);

		arc.scene.ui.layout.Table parent = new arc.scene.ui.layout.Table();
		arc.scene.ui.layout.Table child = new arc.scene.ui.layout.Table();
		parent.addChild(child);

		// Case 1: Matching hostClass in parent chain succeeds
		ThisPlaceholder tpParent = new ThisPlaceholder(parent.getClass().getName().replace('.', '/'));
		Object resolvedParent = m.invoke(null, tpParent, arc.scene.ui.layout.Table.class, null, child);
		assertNotNull(resolvedParent);

		// Case 2: Specified hostClass not in parent chain must return null (unsafe fallback prohibited)
		ThisPlaceholder tpUnknown = new ThisPlaceholder("non/existent/Host");
		Object resolvedUnknown = m.invoke(null, tpUnknown, arc.scene.ui.layout.Table.class, null, child);
		assertNull(resolvedUnknown);

		// Case 3: tp with null hostClass but matching targetType along parent chain
		ThisPlaceholder tpNullHost = new ThisPlaceholder(null);
		Object resolvedByTarget = m.invoke(null, tpNullHost, arc.scene.ui.layout.Table.class, null, child);
		assertNotNull(resolvedByTarget);
	}

	@Test
	void testDryRunPropertiesWithElementAwareProperties() throws Exception {
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

			Method m = CellPropertyRef.class.getDeclaredMethod("dryRunProperties", List.class);
			m.setAccessible(true);

			arc.func.Cons<arc.scene.Element> cons = elem -> {};
			List<PropertyCall> calls = List.of(
				new PropertyCall("arc/scene/ui/layout/Cell", "update", "(Larc/func/Cons;)Larc/scene/ui/layout/Cell;", new Object[]{cons}, 10)
			);
			boolean ok = (boolean) m.invoke(null, calls);
			assertTrue(ok);
		} finally {
			arc.Core.app = oldApp;
		}
	}

	@Test
	void testManualHelperPersistenceAcrossClear() {
		String manualHelper = "custom/manual/Helper";
		CellPropertyRef.registerHelper(manualHelper);
		assertTrue(CellPropertyRef.isHelper(manualHelper, "anyMethod", "()V"));

		// clearClassRecords should NOT remove manual helpers
		CellPropertyRef.clearClassRecords(manualHelper);
		assertTrue(CellPropertyRef.isHelper(manualHelper, "anyMethod", "()V"));
	}

	@Test
	void testSetupNewCellStateRetainsHelperMetadata() throws Exception {
		Method m = CellPropertyRef.class.getDeclaredMethod("setupNewCellState",
			String.class, String.class, List.class, int.class, arc.scene.ui.layout.Cell.class, PropertyCall.class, List.class);
		m.setAccessible(true);

		arc.scene.ui.layout.Table table = new arc.scene.ui.layout.Table();
		arc.scene.ui.layout.Cell<?> cell = table.add(new arc.scene.Element());

		PropertyCall creator = new PropertyCall("arc/scene/ui/layout/Table", "button", "()Larc/scene/ui/layout/Cell;",
			new Object[0], 42, "MyHelper", "help", 15, 2);
		List<PropertyCall> chain = List.of(creator);
		List<CellIdentity> ids = new ArrayList<>();

		m.invoke(null, "HostClass", "build:()V", chain, 0, cell, creator, ids);

		assertEquals(1, ids.size());
		CellIdentity createdId = ids.get(0);
		assertEquals("MyHelper", createdId.helperClass());
		assertEquals("help", createdId.helperMethod());
		assertEquals(15, createdId.helperLine());
		assertEquals(2, createdId.callOrdinal());

		CellPropertyRef.removeCell(cell);
	}

	@Test
	void testRemoveCellFromTableTransfersEndRow() throws Exception {
		arc.scene.ui.layout.Table table = new arc.scene.ui.layout.Table();
		arc.scene.ui.layout.Cell<?> c1 = table.add(new arc.scene.Element());
		arc.scene.ui.layout.Cell<?> c2 = table.add(new arc.scene.Element());
		nipx.uihook.ArcReflectionAdapter.setEndRow(c2, true);

		Method removeM = CellPropertyRef.class.getDeclaredMethod("removeCellFromTable", arc.scene.ui.layout.Cell.class);
		removeM.setAccessible(true);

		// Core.app is null here in headless test - must not NPE and must transfer endRow to c1
		removeM.invoke(null, c2);

		assertTrue(nipx.uihook.ArcReflectionAdapter.isEndRow(c1));
		assertFalse(table.getCells().contains(c2));

		CellPropertyRef.removeCell(c1);
	}

	@Test
	void testIsHelperLibraryShortCircuit() {
		assertFalse(CellPropertyRef.isHelper("java/lang/String", "valueOf", "()Ljava/lang/String;"));
		assertFalse(CellPropertyRef.isHelper("arc/scene/ui/layout/Table", "button", "()Larc/scene/ui/layout/Cell;"));
		assertFalse(CellPropertyRef.isHelper("mindustry/Vars", "load", "()V"));
	}

	@Test
	void testAlignerMappingScopedToClass() throws Exception {
		Method matchM = CellPropertyRef.class.getDeclaredMethod("matchMethods",
			String.class, Map.class, Map.class);
		matchM.setAccessible(true);

		Map<String, List<List<PropertyCall>>> oldM = Map.of(
			"lambda$build$0:()V", List.of()
		);
		Map<String, List<List<PropertyCall>>> newM = Map.of(
			"lambda$build$1:()V", List.of()
		);

		Map<String, String> prevMapping = LambdaAligner.LAST_METHOD_MAPPING;
		String prevClass = LambdaAligner.LAST_ALIGNED_CLASS;
		try {
			LambdaAligner.LAST_METHOD_MAPPING = Map.of("lambda$build$0:()V", "lambda$build$1:()V");
			LambdaAligner.LAST_ALIGNED_CLASS = "TargetHost";

			// Class matches TargetHost -> mapping is used
			Map<String, String> resMatch = (Map<String, String>) matchM.invoke(null, "TargetHost", oldM, newM);
			assertEquals("lambda$build$1:()V", resMatch.get("lambda$build$0:()V"));

			// Class does NOT match TargetHost -> mapping is NOT used (preventing cascade pollution)
			Map<String, String> resDiff = (Map<String, String>) matchM.invoke(null, "OtherHost", oldM, newM);
			assertNull(resDiff.get("lambda$build$0:()V"));
		} finally {
			LambdaAligner.LAST_METHOD_MAPPING = prevMapping;
			LambdaAligner.LAST_ALIGNED_CLASS = prevClass;
		}
	}

	@Test
	void testConvertArgsUnresolvedFallback() throws Exception {
		Method convertM = CellPropertyRef.class.getDeclaredMethod("convertArgs",
			arc.scene.ui.layout.Cell.class, arc.scene.ui.layout.Table.class,
			MethodType.class, Object[].class);
		convertM.setAccessible(true);

		CellPropertyRef.StaticRef unresolvedStatic = new CellPropertyRef.StaticRef("non/existent/Class", "field", "Ljava/lang/String;");
		CellPropertyRef.ThisPlaceholder unresolvedThis = new CellPropertyRef.ThisPlaceholder("non/existent/Host");

		MethodType mt = MethodType.methodType(void.class, String.class, int.class);
		Object[] result = (Object[]) convertM.invoke(null, null, null, mt, new Object[]{unresolvedStatic, unresolvedThis});

		assertNotNull(result);
		assertEquals(2, result.length);
		// String is non-interface: unresolved StaticRef must fall back to defaultValue (null), NOT the StaticRef object
		assertNull(result[0]);
		// int is primitive: unresolved ThisPlaceholder must fall back to defaultValue (0), NOT the ThisPlaceholder object
		assertEquals(0, result[1]);
	}

	@Test
	void testConstantFoldingExtendedOps() throws Exception {
		Method evalM = CellPropertyRef.class.getDeclaredMethod("evaluateBinaryOp", int.class, Object.class, Object.class);
		evalM.setAccessible(true);

		// IREM, ISHL, ISHR, IUSHR, IAND, IOR, IXOR
		assertEquals(1, evalM.invoke(null, org.objectweb.asm.Opcodes.IREM, 10, 3));
		assertEquals(16, evalM.invoke(null, org.objectweb.asm.Opcodes.ISHL, 4, 2));
		assertEquals(2, evalM.invoke(null, org.objectweb.asm.Opcodes.ISHR, 8, 2));
		assertEquals(2, evalM.invoke(null, org.objectweb.asm.Opcodes.IUSHR, 8, 2));
		assertEquals(4, evalM.invoke(null, org.objectweb.asm.Opcodes.IAND, 6, 4));
		assertEquals(7, evalM.invoke(null, org.objectweb.asm.Opcodes.IOR, 5, 2));
		assertEquals(6, evalM.invoke(null, org.objectweb.asm.Opcodes.IXOR, 5, 3));

		// LREM
		assertEquals(2L, evalM.invoke(null, org.objectweb.asm.Opcodes.LREM, 12L, 5L));

		// FREM, DREM
		assertEquals(1.5f, evalM.invoke(null, org.objectweb.asm.Opcodes.FREM, 5.5f, 2.0f));
		assertEquals(1.5d, evalM.invoke(null, org.objectweb.asm.Opcodes.DREM, 5.5d, 2.0d));
	}

	@Test
	void testApplyChainUpdatePreservesExistingElementOnEmptyOldChain() throws Exception {
		arc.scene.ui.layout.Table table = new arc.scene.ui.layout.Table();
		arc.scene.Element elem = new arc.scene.Element();
		arc.scene.ui.layout.Cell<?> cell = table.add(elem);

		Method applyM = CellPropertyRef.class.getDeclaredMethod("applyChainUpdate",
			arc.scene.ui.layout.Cell.class, List.class, List.class, boolean.class);
		applyM.setAccessible(true);

		PropertyCall newCreator = new PropertyCall("arc/scene/ui/layout/Table", "add",
			"(Larc/scene/Element;)Larc/scene/ui/layout/Cell;", new Object[]{new arc.scene.Element()}, 10);
		List<PropertyCall> newChain = List.of(newCreator);

		// oldChain is empty
		applyM.invoke(null, cell, List.of(), newChain, false);

		// The existing element should NOT be replaced by a newly instantiated Element
		assertSame(elem, cell.get(), "Existing element should be preserved on empty oldChain");
	}

	@Test
	void testDynamicArgMergeOnReload() throws Exception {
		Method mergeM = CellPropertyRef.class.getDeclaredMethod("mergeArgs", Object[].class, Object[].class);
		mergeM.setAccessible(true);

		// Case 1: Runtime hook fills concrete runtime value into template's DynamicArg
		CellPropertyRef.DynamicArg templateDyn = new CellPropertyRef.DynamicArg(0x1234);
		Object[] mergedFromHook = (Object[]) mergeM.invoke(null, new Object[]{templateDyn}, new Object[]{"[accent]Alice"});
		assertNotNull(mergedFromHook);
		assertTrue(mergedFromHook[0] instanceof CellPropertyRef.DynamicArg);
		CellPropertyRef.DynamicArg populatedDyn = (CellPropertyRef.DynamicArg) mergedFromHook[0];
		assertEquals(0x1234, populatedDyn.fingerprint());
		assertEquals("[accent]Alice", populatedDyn.runtimeValue());

		// Case 2: Redefine with same fingerprint -> preserves old runtime value
		CellPropertyRef.DynamicArg newSameDyn = new CellPropertyRef.DynamicArg(0x1234);
		Object[] preserved = (Object[]) mergeM.invoke(null, new Object[]{newSameDyn}, new Object[]{populatedDyn});
		assertNotNull(preserved);
		CellPropertyRef.DynamicArg resultPreserved = (CellPropertyRef.DynamicArg) preserved[0];
		assertEquals(0x1234, resultPreserved.fingerprint());
		assertEquals("[accent]Alice", resultPreserved.runtimeValue());

		// Case 3: Redefine with changed fingerprint -> discards old runtime value (null)
		CellPropertyRef.DynamicArg newDiffDyn = new CellPropertyRef.DynamicArg(0x5678);
		Object[] discarded = (Object[]) mergeM.invoke(null, new Object[]{newDiffDyn}, new Object[]{populatedDyn});
		assertNotNull(discarded);
		CellPropertyRef.DynamicArg resultDiscarded = (CellPropertyRef.DynamicArg) discarded[0];
		assertEquals(0x5678, resultDiscarded.fingerprint());
		assertNull(resultDiscarded.runtimeValue(), "Changed fingerprint must discard stale runtime value");
	}

	@Test
	void testDynamicArgUsability() {
		CellPropertyRef.DynamicArg emptyDyn = new CellPropertyRef.DynamicArg(0x1234);
		CellPropertyRef.DynamicArg populatedDyn = emptyDyn.withRuntimeValue("Hello");

		// Creator with DynamicArg is never constructible
		PropertyCall creatorWithDyn = new PropertyCall("arc/scene/ui/layout/Table", "button",
			"(Ljava/lang/String;)Larc/scene/ui/layout/Cell;", new Object[]{populatedDyn}, 10);
		assertFalse(CellPropertyRef.hasUsableCreatorArgs(creatorWithDyn),
			"Creators with DynamicArg must not be considered usable for creation");

		// Property with empty DynamicArg (null runtimeValue) is not usable
		PropertyCall propEmpty = new PropertyCall("arc/scene/ui/layout/Cell", "tooltip",
			"(Ljava/lang/String;)Larc/scene/ui/layout/Cell;", new Object[]{emptyDyn}, 12);
		assertFalse(CellPropertyRef.hasUsablePropertyArgs(propEmpty),
			"DynamicArg without runtimeValue must not be usable");

		// Property with populated DynamicArg IS usable
		PropertyCall propPopulated = new PropertyCall("arc/scene/ui/layout/Cell", "tooltip",
			"(Ljava/lang/String;)Larc/scene/ui/layout/Cell;", new Object[]{populatedDyn}, 12);
		assertTrue(CellPropertyRef.hasUsablePropertyArgs(propPopulated),
			"DynamicArg with non-null runtimeValue must be usable");
	}

	@Test
	void testDynamicArgConvertArgsUnwrap() throws Exception {
		Method convertM = CellPropertyRef.class.getDeclaredMethod("convertArgs",
			arc.scene.ui.layout.Cell.class, arc.scene.ui.layout.Table.class,
			MethodType.class, Object[].class);
		convertM.setAccessible(true);

		CellPropertyRef.DynamicArg populatedDyn = new CellPropertyRef.DynamicArg(0x1234, "unwrapped-value");
		CellPropertyRef.DynamicArg emptyDyn = new CellPropertyRef.DynamicArg(0x1234, null);

		MethodType mt = MethodType.methodType(void.class, String.class, int.class);
		Object[] converted = (Object[]) convertM.invoke(null, null, null, mt,
			new Object[]{populatedDyn, emptyDyn});

		assertNotNull(converted);
		assertEquals("unwrapped-value", converted[0]);
		// emptyDyn for primitive int falls back to defaultValue(int) = 0
		assertEquals(0, converted[1]);
	}

	@Test
	void testComputeSliceFingerprintDistinguishesExpressions() throws Exception {
		Method fpM = CellPropertyRef.class.getDeclaredMethod("computeInsnFingerprint",
			org.objectweb.asm.tree.ClassNode.class, org.objectweb.asm.tree.MethodNode.class,
			org.objectweb.asm.tree.analysis.Frame[].class, org.objectweb.asm.tree.AbstractInsnNode.class,
			Set.class, Map.class, int.class);
		fpM.setAccessible(true);

		// Two LDC instructions with different constants must yield different fingerprints
		org.objectweb.asm.tree.LdcInsnNode ldc1 = new org.objectweb.asm.tree.LdcInsnNode("[accent]");
		org.objectweb.asm.tree.LdcInsnNode ldc2 = new org.objectweb.asm.tree.LdcInsnNode("[blue]");
		int fp1 = (int) fpM.invoke(null, null, null, null, ldc1, new HashSet<>(), null, 0);
		int fp2 = (int) fpM.invoke(null, null, null, null, ldc2, new HashSet<>(), null, 0);
		assertNotEquals(fp1, fp2);

		// Two StringConcatFactory indy insns with different recipes must yield different fingerprints
		org.objectweb.asm.Handle bsm = new org.objectweb.asm.Handle(
			org.objectweb.asm.Opcodes.H_INVOKESTATIC,
			"java/lang/invoke/StringConcatFactory",
			"makeConcatWithConstants",
			"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
			false
		);
		org.objectweb.asm.tree.InvokeDynamicInsnNode indy1 = new org.objectweb.asm.tree.InvokeDynamicInsnNode(
			"makeConcatWithConstants", "(Ljava/lang/String;)Ljava/lang/String;", bsm, "\1[accent]\1"
		);
		org.objectweb.asm.tree.InvokeDynamicInsnNode indy2 = new org.objectweb.asm.tree.InvokeDynamicInsnNode(
			"makeConcatWithConstants", "(Ljava/lang/String;)Ljava/lang/String;", bsm, "\1[blue]\1"
		);
		int fpIndy1 = (int) fpM.invoke(null, null, null, null, indy1, new HashSet<>(), null, 0);
		int fpIndy2 = (int) fpM.invoke(null, null, null, null, indy2, new HashSet<>(), null, 0);
		assertNotEquals(fpIndy1, fpIndy2);
	}

	@Test
	void testRawOrdinalPreservationAcrossLoop() throws Exception {
		Method boundM = CellPropertyRef.class.getDeclaredMethod("onCellBoundImpl", arc.scene.ui.layout.Cell.class);
		boundM.setAccessible(true);

		Field csField = CellPropertyRef.class.getDeclaredField("cellState");
		csField.setAccessible(true);
		Map<arc.scene.ui.layout.Cell<?>, Object> cs = (Map<arc.scene.ui.layout.Cell<?>, Object>) csField.get(null);

		arc.scene.ui.layout.Table table = new arc.scene.ui.layout.Table();
		arc.scene.ui.layout.Cell<?> cell1 = table.add(new arc.scene.Element());
		arc.scene.ui.layout.Cell<?> cell2 = table.add(new arc.scene.Element());
		arc.scene.ui.layout.Cell<?> cell3 = table.add(new arc.scene.Element());

		try {
			boundM.invoke(null, cell1);
			boundM.invoke(null, cell2);
			boundM.invoke(null, cell3);

			Field idField = Class.forName("nipx.uihook.CellPropertyRef$CellState").getDeclaredField("id");
			idField.setAccessible(true);

			Object st1 = cs.get(cell1);
			assertNotNull(st1);
			CellIdentity id1 = (CellIdentity) idField.get(st1);
			assertEquals(0, id1.callOrdinal(), "First cell on site must have rawOrdinal 0");

			Object st2 = cs.get(cell2);
			assertNotNull(st2);
			CellIdentity id2 = (CellIdentity) idField.get(st2);
			assertEquals(1, id2.callOrdinal(), "Second cell on site must have rawOrdinal 1");

			Object st3 = cs.get(cell3);
			assertNotNull(st3);
			CellIdentity id3 = (CellIdentity) idField.get(st3);
			assertEquals(2, id3.callOrdinal(), "Third cell on site must have rawOrdinal 2");
		} finally {
			CellPropertyRef.removeCell(cell1);
			CellPropertyRef.removeCell(cell2);
			CellPropertyRef.removeCell(cell3);
		}
	}

	@Test
	void testTableSiteOrdinalsLifecycleReset() throws Exception {
		Method boundM = CellPropertyRef.class.getDeclaredMethod("onCellBoundImpl", arc.scene.ui.layout.Cell.class);
		boundM.setAccessible(true);

		Field csField = CellPropertyRef.class.getDeclaredField("cellState");
		csField.setAccessible(true);
		Map<arc.scene.ui.layout.Cell<?>, Object> cs = (Map<arc.scene.ui.layout.Cell<?>, Object>) csField.get(null);

		Field tsoField = CellPropertyRef.class.getDeclaredField("tableSiteOrdinals");
		tsoField.setAccessible(true);
		Map<arc.scene.ui.layout.Table, Map<String, Integer>> tso = (Map<arc.scene.ui.layout.Table, Map<String, Integer>>) tsoField.get(null);

		arc.scene.ui.layout.Table table = new arc.scene.ui.layout.Table();
		arc.scene.ui.layout.Cell<?> cell1 = table.add(new arc.scene.Element());
		arc.scene.ui.layout.Cell<?> cell2 = table.add(new arc.scene.Element());

		Field idField = Class.forName("nipx.uihook.CellPropertyRef$CellState").getDeclaredField("id");
		idField.setAccessible(true);

		try {
			boundM.invoke(null, cell1);
			boundM.invoke(null, cell2);

			assertTrue(tso.containsKey(table), "Table must be recorded in tableSiteOrdinals");

			// Clear children resets the table's site ordinal counters
			CellPropertyRef.onTableClearChildren(table);
			assertFalse(tso.containsKey(table), "onTableClearChildren must reset tableSiteOrdinals for table");

			// Next cell bound on cleared table must restart rawOrdinal from 0
			arc.scene.ui.layout.Cell<?> cellAfterClear = table.add(new arc.scene.Element());
			boundM.invoke(null, cellAfterClear);

			Object stAfter = cs.get(cellAfterClear);
			assertNotNull(stAfter);
			CellIdentity idAfter = (CellIdentity) idField.get(stAfter);
			assertEquals(0, idAfter.callOrdinal(), "After table clear, rawOrdinal must reset to 0");

			CellPropertyRef.removeCell(cellAfterClear);
		} finally {
			CellPropertyRef.removeCell(cell1);
			CellPropertyRef.removeCell(cell2);
		}
	}
}
