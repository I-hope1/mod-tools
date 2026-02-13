package nipx;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

import java.util.*;

public class LambdaAligner {
    public static byte[] align(byte[] oldClassBytes, byte[] newClassBytes) {
        if (oldClassBytes == null) return newClassBytes;

        // 1. 按签名分组记录旧的 Lambda 名称
        // Map<Descriptor, Queue<OldName>>
        Map<String, Queue<String>> oldPool = collectLambdasToPool(oldClassBytes);
        // 2. 提取新 Lambda 列表
        List<LambdaInfo> newLambdas = collectLambdaList(newClassBytes);

        Map<String, String> renameMap = new HashMap<>();
        List<String> usedNewNames = new ArrayList<>();

        // 3. 尝试按签名“认亲”
        for (LambdaInfo n : newLambdas) {
            Queue<String> pool = oldPool.get(n.desc);
            if (pool != null && !pool.isEmpty()) {
                String oldName = pool.poll(); // 取出同签名最早出现的那个名字
                if (!oldName.equals(n.name)) {
                    renameMap.put(n.name, oldName);
                }
            }
        }

        if (renameMap.isEmpty()) return newClassBytes;

        // 4. 执行重映射
        ClassReader cr = new ClassReader(newClassBytes);
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new ClassRemapper(cw, new SimpleRemapper(renameMap)), 0);
        return cw.toByteArray();
    }

    private static Map<String, Queue<String>> collectLambdasToPool(byte[] bytes) {
        Map<String, Queue<String>> map = new HashMap<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                if (name.startsWith("lambda$")) {
                    map.computeIfAbsent(desc, k -> new LinkedList<>()).add(name);
                }
                return null;
            }
        }, 0);
        return map;
    }

    private static List<LambdaInfo> collectLambdaList(byte[] bytes) {
        List<LambdaInfo> list = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                if (name.startsWith("lambda$")) {
                    list.add(new LambdaInfo(name, desc));
                }
                return null;
            }
        }, 0);
        return list;
    }

    static class LambdaInfo {
        String name, desc;
        LambdaInfo(String n, String d) { name = n; desc = d; }
    }
}