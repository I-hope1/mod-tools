package nipx;

import nipx.util.CRC64;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;

public final class ClassDiffUtil {
    private ClassDiffUtil() { }

    public static ClassDiff diff(byte[] oldBytes, byte[] newBytes) {
        ClassNode oldNode = parse(oldBytes);
        ClassNode newNode = parse(newBytes);
        return diff(oldNode, newNode);
    }

    private static ClassNode parse(byte[] bytes) {
        ClassNode cn = new ClassNode();
        // 关键：我们要深入代码，但跳过调试信息（行号、变量名等），确保逻辑对比的纯粹性
        new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return cn;
    }

    public static final class ClassDiff {
        final List<String> modifiedMethods = new ArrayList<>();
        final List<String> addedMethods = new ArrayList<>();
        final List<String> removedMethods = new ArrayList<>();
        // 字段变动通常会导致 Redefine 失败，记录它们以进行预警
        final List<String> changedFields = new ArrayList<>();

        boolean hasChange() {
            return !modifiedMethods.isEmpty() || !addedMethods.isEmpty() ||
                   !removedMethods.isEmpty() || !changedFields.isEmpty();
        }
    }

    private static ClassDiff diff(ClassNode oldC, ClassNode newC) {
        ClassDiff d = new ClassDiff();

        // 1. 字段对比（快速比对）
        Set<String> oldFields = new HashSet<>();
        for (FieldNode f : oldC.fields) oldFields.add(f.name + ":" + f.desc);
        for (FieldNode f : newC.fields) {
            if (!oldFields.contains(f.name + ":" + f.desc)) d.changedFields.add("+ " + f.name);
        }

        // 2. 方法对比
        Map<String, MethodNode> oldMethods = new HashMap<>();
        for (MethodNode m : oldC.methods) oldMethods.put(m.name + m.desc, m);

        for (MethodNode newM : newC.methods) {
            String key = newM.name + newM.desc;
            MethodNode oldM = oldMethods.remove(key);
            if (oldM == null) {
                d.addedMethods.add(key);
            } else if (isCodeChanged(oldM, newM)) {
                d.modifiedMethods.add(key);
            }
        }
        d.removedMethods.addAll(oldMethods.keySet());

        return d;
    }

    /**
     * 核心逻辑：基于指令流指纹的快速对比
     */
    private static boolean isCodeChanged(MethodNode m1, MethodNode m2) {
        // 首先进行低成本的快速判断
        if (m1.instructions.size() != m2.instructions.size()) return true;
        if (m1.access != m2.access) return true;

        // 计算逻辑指纹，不产生任何 String 垃圾
        return calculateMethodHash(m1) != calculateMethodHash(m2);
    }

    /**
     * 深度扫描指令流，计算逻辑指纹
     */
    public static long calculateMethodHash(MethodNode mn) {
        long h = 0;
        InsnList insns = mn.instructions;
        for (int i = 0; i < insns.size(); i++) {
            AbstractInsnNode node = insns.get(i);

            // 1. 混合 Opcode
            h = 31 * h + node.getOpcode();

            // 2. 根据指令类型混合关键特征
            // 这里的辩证法：忽略 Label 的对象标识符，因为它们在每次编译时都是新的，
            // 但我们要捕获指令引用的常量、字段名和方法名。
            if (node instanceof MethodInsnNode) {
                MethodInsnNode min = (MethodInsnNode) node;
                h = h * 31 + hashString(min.owner);
                h = h * 31 + hashString(min.name);
                h = h * 31 + hashString(min.desc);
            } else if (node instanceof FieldInsnNode) {
                FieldInsnNode fin = (FieldInsnNode) node;
                h = h * 31 + hashString(fin.owner);
                h = h * 31 + hashString(fin.name);
            } else if (node instanceof LdcInsnNode) {
                Object cst = ((LdcInsnNode) node).cst;
                h = h * 31 + (cst == null ? 0 : cst.hashCode());
            } else if (node instanceof VarInsnNode) {
                h = h * 31 + ((VarInsnNode) node).var;
            } else if (node instanceof TypeInsnNode) {
                h = h * 31 + hashString(((TypeInsnNode) node).desc);
            }
        }
        return h;
    }

    private static long hashString(String s) {
        return s == null ? 0 : CRC64.hashString(s);
    }

    static void logDiff(String className, ClassDiff diff) {
        if (!diff.hasChange()) return;
        StringBuilder sb = new StringBuilder("[DIFF] ").append(className).append(":").append('\n');
        if (!diff.modifiedMethods.isEmpty()) sb.append(" *Modified: ").append(diff.modifiedMethods).append('\n');
        if (!diff.addedMethods.isEmpty()) sb.append(" +Added: ").append(diff.addedMethods).append('\n');
        if (!diff.removedMethods.isEmpty()) sb.append(" -Removed: ").append(diff.removedMethods).append('\n');
        if (!diff.changedFields.isEmpty()) sb.append(" !Fields: ").append(diff.changedFields).append('\n');
        HotSwapAgent.info(sb.toString());
    }
}