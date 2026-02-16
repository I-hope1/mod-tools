package nipx;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

import java.util.*;
import java.security.MessageDigest;

import static nipx.HotSwapAgent.*;

public class LambdaAligner {

	public static byte[] align(byte[] oldBytes, byte[] newBytes) {
		if (oldBytes == null) return newBytes;
		if (oldBytes.length == 0) return newBytes;

		// 1. 提取旧类的合成方法信息 (以 Hash+Desc 为索引)
		Map<String, SyntheticInfo> oldMethods = collectSyntheticMethods(oldBytes);
		// 2. 提取新类的合成方法信息
		List<SyntheticInfo> newMethods = new ArrayList<>(collectSyntheticMethods(newBytes).values());

		// 核心映射表：NewName -> OldName
		Map<String, String> renameMap = new HashMap<>();

		// 已使用的旧方法名，防止重复分配
		Set<String> usedOldNames = new HashSet<>();

		// --- 第一步：精确匹配（逻辑未变的方法优先对齐） ---
		// 依据：Hash + Desc + Prefix + ParentMethod 相同，认为绝对是同一个 Lambda
		for (SyntheticInfo ni : newMethods) {
			for (SyntheticInfo oi : oldMethods.values()) {
				if (!usedOldNames.contains(oi.name) &&
				    oi.hash == ni.hash &&
				    oi.desc.equals(ni.desc) &&
				    oi.prefix.equals(ni.prefix) &&
				    oi.parentMethod.equals(ni.parentMethod)) { // ← 添加父方法检查

					if (!ni.name.equals(oi.name)) {
						renameMap.put(ni.name, oi.name);
					}
					ni.matched = true;
					usedOldNames.add(oi.name);
					break;
				}
			}
		}

		// --- 第二步：模糊匹配（代码逻辑改了，但结构还在） ---
		// 依据：按出现顺序对齐相同 Prefix 和 Desc 的方法
		for (SyntheticInfo ni : newMethods) {
			if (ni.matched) continue;
			for (SyntheticInfo oi : oldMethods.values()) {
				if (!usedOldNames.contains(oi.name) &&
				    oi.desc.equals(ni.desc) &&
				    oi.prefix.equals(ni.prefix) &&
				    oi.parentMethod.equals(ni.parentMethod)) { // ← 关键：必须同一个父方法

					renameMap.put(ni.name, oi.name);
					ni.matched = true;
					usedOldNames.add(oi.name);
					break;
				}
			}
		}

		if (renameMap.isEmpty()) return newBytes;

		if (DEBUG) log("[LambdaAligner] Renamed " + renameMap.size() + " methods:" + renameMap);

		// --- 第三步：物理重映射 ---
		ClassReader cr = new ClassReader(newBytes);
		ClassWriter cw = new ClassWriter(0);

		Remapper remapper = new Remapper(Opcodes.ASM9) {
			@Override
			public String mapMethodName(String owner, String name, String descriptor) {
				return renameMap.getOrDefault(name, name);
			}

			@Override
			public String mapInvokeDynamicMethodName(String name, String descriptor, Handle bsm, Object... bsmArgs) {
				// Lambda 在 invokedynamic 中作为方法名存在
				return renameMap.getOrDefault(name, name);
			}
		};

		cr.accept(new ClassRemapper(cw, remapper), 0);
		return cw.toByteArray();
	}

	private static Map<String, SyntheticInfo> collectSyntheticMethods(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		Map<String, SyntheticInfo> map = new LinkedHashMap<>();

		for (MethodNode mn : cn.methods) {
			// 忽略非合成方法
			if ((mn.access & Opcodes.ACC_SYNTHETIC) == 0) continue;
			String prefix = getPrefix(mn.name);
			if (prefix != null) {
				String parentMethod = extractParentMethod(mn.name); // ← 提取父方法名
				map.put(mn.name, new SyntheticInfo(mn.name, prefix, mn.desc, getFastMethodHash(mn), parentMethod));
			}
		}
		return map;
	}

	/**
	 * 提取 Lambda 的逻辑身份（去除序号）
	 * lambda$hide$4          → lambda$hide
	 * lambda$new$3           → lambda$new
	 * lambda$bar$0$lambda$1  → lambda$bar$0$lambda
	 * access$000             → access (没有父方法信息)
	 */
	private static String extractParentMethod(String lambdaName) {
		if (lambdaName.startsWith("lambda$")) {
			int lastDollar = lambdaName.lastIndexOf('$');
			if (lastDollar > 7) { // "lambda$".length() == 7
				return lambdaName.substring(0, lastDollar);
			}
			return "lambda"; // 边界情况: lambda$0
		}

		if (lambdaName.startsWith("access$")) {
			// access$000 → access (仅保留前缀，没有父方法信息)
			return "access";
		}

		return "";
	}
	private static String getPrefix(String name) {
		if (name.startsWith("lambda$")) return "lambda$";
		if (name.startsWith("access$")) return "access$";
		return null;
	}

	/**
	 * 使用 SHA-256 提取稳定的方法指纹
	 */
	public static String getMethodHashSHA256(MethodNode mn) {
		try {
			ClassWriter cw = new ClassWriter(0);
			mn.accept(cw);
			byte[]        methodBytes = cw.toByteArray();
			MessageDigest md          = MessageDigest.getInstance("SHA-256");
			return Base64.getEncoder().encodeToString(md.digest(methodBytes));
		} catch (Exception e) {
			return String.valueOf(mn.instructions.size());
		}
	}

	public static long getFastMethodHash(MethodNode mn) {
		MethodFingerprinter hasher = new MethodFingerprinter();
		mn.accept(hasher);
		return hasher.getHash();
	}

	static class SyntheticInfo {
		String name, prefix, desc, parentMethod;
		long    hash;
		boolean matched = false;
		SyntheticInfo(String n, String p, String d, long h, String pm) {
			this.name = n;
			this.prefix = p;
			this.desc = d;
			this.hash = h;
			this.parentMethod = pm;
		}
	}
}