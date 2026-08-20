import arc.func.Cons;
import arc.scene.ui.layout.Table;
import nipx.uihook.CellPropertyRef;
import org.junit.Test;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class CellPropertyRefTest {

    // Dummy classes for testing bytecode analysis
    static class DummyClass1 {
        void testDirectConstant(Table table) {
            table.add().width(100f);
        }

        void testLocalVarNoWrites(Table table) {
            float w = 200f;
            table.add().width(w);
        }

        void testLocalVarWithWrites(Table table) {
            float w = 200f;
            w = 300f;
            table.add().width(w);
        }

        void testLambdaCapture(Table table) {
            float w = 400f;
            table.table((Cons<Table>) t -> {
                t.add().width(w);
            });
        }

        void testNestedLambdaCapture(Table table) {
            float w = 500f;
            table.table((Cons<Table>) t -> {
                t.table((Cons<Table>) t2 -> {
                    t2.add().width(w);
                });
            });
        }

        void testArithmeticExpression(Table table) {
            float size = 100f * 3f;
            table.add().size(size, size * 0.7f);
        }

        void testRowProperty(Table table) {
            table.add().growX().row();
            table.add().width(42f);
        }
    }

    private byte[] getClassBytecode(Class<?> clazz) throws Exception {
        String resourceName = clazz.getName().replace('.', '/') + ".class";
        try (InputStream is = clazz.getClassLoader().getResourceAsStream(resourceName)) {
            if (is == null) {
                throw new IllegalArgumentException("Class resource not found: " + resourceName);
            }
            return is.readAllBytes();
        }
    }

    @Test
    public void testExtractCellChains() throws Exception {
        byte[] bytecode = getClassBytecode(DummyClass1.class);
        Map<String, List<List<CellPropertyRef.PropertyCall>>> chains = CellPropertyRef.extractCellChains(bytecode);

        // Verify Direct Constant
        List<List<CellPropertyRef.PropertyCall>> directChains = findChains(chains, "testDirectConstant");
        assertNotNull(directChains);
        assertEquals(1, directChains.size());
        assertEquals(100f, directChains.get(0).get(1).args()[0]);

        // Verify Local Variable without writes
        List<List<CellPropertyRef.PropertyCall>> localVarNoWritesChains = findChains(chains, "testLocalVarNoWrites");
        assertNotNull(localVarNoWritesChains);
        assertEquals(1, localVarNoWritesChains.size());
        assertEquals(200f, localVarNoWritesChains.get(0).get(1).args()[0]);

        // Verify Local Variable with writes (should not resolve/return null)
        List<List<CellPropertyRef.PropertyCall>> localVarWithWritesChains = findChains(chains, "testLocalVarWithWrites");
        assertNotNull(localVarWithWritesChains);
        assertEquals(1, localVarWithWritesChains.size());
        assertNull(localVarWithWritesChains.get(0).get(1).args()[0]);

        // Verify Lambda Capture (the lambda method is also in the same bytecode of DummyClass1)
        List<List<CellPropertyRef.PropertyCall>> lambdaChains = findChains(chains, "lambda$testLambdaCapture$0");
        assertNotNull(lambdaChains);
        assertEquals(1, lambdaChains.size());
        assertEquals(400f, lambdaChains.get(0).get(1).args()[0]);

        // Verify Nested Lambda Capture
        List<List<CellPropertyRef.PropertyCall>> nestedLambdaChains = findChains(chains, "lambda$testNestedLambdaCapture$1");
        assertNotNull(nestedLambdaChains);
        assertEquals(1, nestedLambdaChains.size());
        assertEquals(500f, nestedLambdaChains.get(0).get(1).args()[0]);

        // Verify Arithmetic Expression (size, size * 0.7f) -> resolves to (300.0f, 210.0f)
        List<List<CellPropertyRef.PropertyCall>> arithmeticChains = findChains(chains, "testArithmeticExpression");
        assertNotNull(arithmeticChains);
        assertEquals(1, arithmeticChains.size());
        assertEquals(300.0f, arithmeticChains.get(0).get(1).args()[0]);
        assertEquals(210.0f, arithmeticChains.get(0).get(1).args()[1]);

        List<List<CellPropertyRef.PropertyCall>> rowChains = findChains(chains, "testRowProperty");
        assertNotNull(rowChains);
        assertEquals(2, rowChains.size());
        assertEquals("row", rowChains.get(0).get(2).method());
    }

    @Test
    public void testCellPoolingAndHostContext() {
        arc.scene.ui.layout.Cell<?> cell = new arc.scene.ui.layout.Cell<>();
        CellPropertyRef.enable();

        CellPropertyRef.currentHostContext.set(new String[]{"com/example/MyUI", "buildUI", "()V"});
        CellPropertyRef.registerCellAtCreation(cell);

        assertTrue(CellPropertyRef.getStats().contains("Tracked Cells: 1"));

        // Simulate returning cell to Pools
        CellPropertyRef.onCellFreed(cell);
        assertTrue(CellPropertyRef.getStats().contains("Tracked Cells: 0"));

        CellPropertyRef.currentHostContext.remove();
        CellPropertyRef.disable();
    }

    @Test
    public void testCellPoolReuseViaSetLayout() {
        arc.scene.ui.layout.Cell<?> cell = new arc.scene.ui.layout.Cell<>();
        CellPropertyRef.enable();

        // 1. 首次注册 (等价于 Table A 中的 obtainCell)
        CellPropertyRef.currentHostContext.set(new String[]{"com/example/UIA", "methodA", "()V"});
        CellPropertyRef.registerCellAtCreation(cell);
        assertTrue(CellPropertyRef.getStats().contains("Tracked Cells: 1"));

        // 2. 模拟 Cell 被 Table.obtainCell() 重新取出：
        //    cellPool.obtain() 之后会立刻调用 cell.setLayout(newTable)
        //    injectCell 在 setLayout 入口注入了 onCellFreed，所以直接调用 onCellFreed 等价
        CellPropertyRef.currentHostContext.remove();
        CellPropertyRef.onCellFreed(cell); // 等价于 setLayout 注入触发的效果

        // 旧身份必须被清除
        assertTrue(CellPropertyRef.getStats().contains("Tracked Cells: 0"));

        // 3. 重新注册到 Method B（等价于下次 recordPropertyCall 时 JVMTI 推断）
        CellPropertyRef.currentHostContext.set(new String[]{"com/example/UIB", "methodB", "()V"});
        CellPropertyRef.registerCellAtCreation(cell);
        assertTrue(CellPropertyRef.getStats().contains("Tracked Cells: 1"));

        CellPropertyRef.currentHostContext.remove();
        CellPropertyRef.disable();
        CellPropertyRef.clearAll();
    }

    private List<List<CellPropertyRef.PropertyCall>> findChains(Map<String, List<List<CellPropertyRef.PropertyCall>>> chains, String methodName) {
        for (Map.Entry<String, List<List<CellPropertyRef.PropertyCall>>> entry : chains.entrySet()) {
            if (entry.getKey().startsWith(methodName + ":")) {
                return entry.getValue();
            }
        }
        return null;
    }
}
