package nipx;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import java.util.*;

public class LambdaAligner {

    public static byte[] align(byte[] oldBytes, byte[] newBytes) {
        if (oldBytes == null) return newBytes;

        // 1. 收集信息
        Map<String, LinkedList<String>> oldPool = collect(oldBytes);
        List<SyntheticInfo> newMethods = collectList(newBytes);

        Map<String, String> renameMap = new HashMap<>();
        Set<String> assignedOldNames = new HashSet<>();

        // 2. 第一阶段：匹配失散的 Lambda
        for (SyntheticInfo n : newMethods) {
            String key = n.prefix + "|" + n.desc;
            LinkedList<String> pool = oldPool.get(key);
            if (pool != null && !pool.isEmpty()) {
                String oldName = pool.removeFirst();
                if (!n.name.equals(oldName)) {
                    renameMap.put(n.name, oldName);
                }
                assignedOldNames.add(oldName);
                n.isMapped = true;
            }
        }

        // 3. 第二阶段：冲突规避
        int safeId = 0;
        for (SyntheticInfo n : newMethods) {
            if (!n.isMapped) {
                if (isNameUsedInOld(n.name, oldBytes) || assignedOldNames.contains(n.name)) {
                    String safeName = "nipx$" + (safeId++) + "$" + n.name;
                    renameMap.put(n.name, safeName);
                }
            }
        }

        if (renameMap.isEmpty()) return newBytes;

        HotSwapAgent.info("[ALIGN] Map built: " + renameMap);

        // 4. 执行物理重映射（核心修改点）
        ClassReader cr = new ClassReader(newBytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

        // 使用自定义 Remapper，强制匹配简单名，并打印日志
        Remapper customRemapper = new Remapper() {
            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                String newName = renameMap.get(name);
                if (newName != null) {
                    // [调试探针] 只有这里打印了，才说明 ASM 真的在干活
                    // HotSwapAgent.info("  -> Remapping method: " + name + " to " + newName);
                    return newName;
                }
                return super.mapMethodName(owner, name, descriptor);
            }

            @Override
            public String mapInvokeDynamicMethodName(String name, String descriptor) {
                // 这对于 Lambda 的 Indy 调用至关重要
                String newName = renameMap.get(name);
                if (newName != null) {
                    return newName;
                }
                return super.mapInvokeDynamicMethodName(name, descriptor);
            }
        };

        try {
            cr.accept(new ClassRemapper(cw, customRemapper), 0);
            byte[] result = cw.toByteArray();

            // [双重验证] 检查结果字节码里是否还包含旧名字
            // 如果这里打印出来了，说明 ASM 写入失败
            // checkVerification(result, renameMap);

            return result;
        } catch (Exception e) {
            HotSwapAgent.error("[ALIGN] Critical error during remapping", e);
            return newBytes; // 失败退回
        }
    }

    // --- 辅助方法保持不变 ---

    private static String getPrefix(String name) {
        if (!name.startsWith("lambda$") && !name.startsWith("access$")) return null;
        int lastDollar = name.lastIndexOf('$');
        return (lastDollar == -1) ? name : name.substring(0, lastDollar + 1);
    }

    private static Map<String, LinkedList<String>> collect(byte[] bytes) {
        Map<String, LinkedList<String>> map = new HashMap<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                String prefix = getPrefix(name);
                if (prefix != null) map.computeIfAbsent(prefix + "|" + desc, k -> new LinkedList<>()).add(name);
                return null;
            }
        }, 0);
        return map;
    }

    private static List<SyntheticInfo> collectList(byte[] bytes) {
        List<SyntheticInfo> list = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                String prefix = getPrefix(name);
                if (prefix != null) list.add(new SyntheticInfo(name, prefix, desc));
                return null;
            }
        }, 0);
        return list;
    }

    private static boolean isNameUsedInOld(String name, byte[] oldBytes) {
        final boolean[] found = {false};
        new ClassReader(oldBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int acc, String n, String d, String s, String[] e) {
                if (n.equals(name)) found[0] = true;
                return null;
            }
        }, 0);
        return found[0];
    }

    static class SyntheticInfo {
        String name, prefix, desc;
        boolean isMapped = false;
        SyntheticInfo(String n, String p, String d) { name = n; prefix = p; desc = d; }
    }
}