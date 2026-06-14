package nipx.uihook;

import nipx.HotSwapAgent;
import org.objectweb.asm.*;

import java.util.*;

public class UIUpdateDispatcher {
    public static void diffAndUpdate(String className, byte[] oldBytecode, byte[] newBytecode) {
        Map<String, Map<String, Object>> oldConstants = extractConstants(oldBytecode);
        Map<String, Map<String, Object>> newConstants = extractConstants(newBytecode);

        for (Map.Entry<String, Map<String, Object>> entry : newConstants.entrySet()) {
            String methodName = entry.getKey();
            Map<String, Object> newLines = entry.getValue();
            Map<String, Object> oldLines = oldConstants.getOrDefault(methodName, Collections.emptyMap());

            for (Map.Entry<String, Object> lineEntry : newLines.entrySet()) {
                String lineKey = lineEntry.getKey(); // "行号:序号"
                Object newVal = lineEntry.getValue();
                Object oldVal = oldLines.get(lineKey);

                // 如果发现同一位置的 LDC 常量变了
                if (oldVal != null && !oldVal.equals(newVal)) {
                    if (newVal instanceof String newStr) {
                        String[] parts = lineKey.split(":");
                        int line = Integer.parseInt(parts[0]);
                        int index = Integer.parseInt(parts[1]);

                        HotSwapAgent.info("[UI-Auto-Update] 检测到文本常量改变在 " +
                                          className + "." + methodName + "() 第 " + line + " 行: " +
                                          oldVal + " -> " + newStr);

                        // 触发精准更新
                        UIHookRegistry.updateText(className, methodName, line, index, newStr);
                    }
                }
            }
        }
    }

    /**
     * 提取方法中所有 UI 关联的常量
     */
    private static Map<String, Map<String, Object>> extractConstants(byte[] bytecode) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        ClassReader cr = new ClassReader(bytecode);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                Map<String, Object> lineToConstant = new HashMap<>();
                result.put(name + descriptor, lineToConstant);

                return new MethodVisitor(Opcodes.ASM9) {
                    private int currentLine = -1;
                    private int callIndex = 0;
                    private Object lastLdc = null;

                    @Override
                    public void visitLineNumber(int line, Label start) {
                        currentLine = line;
                        callIndex = 0;
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        lastLdc = value;
                        super.visitLdcInsn(value);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        // 拦截所有返回 Cell 的 UI 创建调用
                        if (Type.getReturnType(descriptor).getDescriptor().equals("Larc/scene/ui/layout/Cell;")) {
                            if (currentLine != -1 && lastLdc != null) {
                                lineToConstant.put(currentLine + ":" + callIndex, lastLdc);
                                callIndex++;
                            }
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }
                };
            }
        }, 0);
        return result;
    }
}