package hope.magic.compiler;

import com.google.auto.service.AutoService;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Scope.WriteableScope;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.Completer;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.TypeSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.util.List;
import hope.magic.annotation.AccessMode;
import hope.magic.annotation.HField;
import hope.magic.annotation.HMarkMagic;
import hope.magic.annotation.HMethod;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static hope.magic.compiler.TypeUtils.*;

@AutoService(Processor.class)
@SupportedAnnotationTypes({
	"hope.magic.annotation.HField",
	"hope.magic.annotation.HMethod"
})
@SupportedOptions({
	"magic.optimize.p1",
	"targetVersion",
	"magic.target",
	"magic.mode"
})
public class AccessorProcessor extends BaseAccessorProc {
	private static final AtomicInteger ID_GEN = new AtomicInteger(0);

	private boolean isP1OptimizationEnabled() {
		if (env != null && env.getOptions() != null) {
			String opt = env.getOptions().get("magic.optimize.p1");
			if (opt != null) return Boolean.parseBoolean(opt);
		}
		String prop = System.getProperty("magic.optimize.p1");
		if (prop != null) return Boolean.parseBoolean(prop);
		String envVal = System.getenv("MAGIC_OPTIMIZE_P1");
		if (envVal != null) return Boolean.parseBoolean(envVal);
		return true;
	}

	private boolean isTargetAndroid() {
		if (env != null && env.getOptions() != null) {
			String targetVer = env.getOptions().get("targetVersion");
			if (targetVer != null && (targetVer.equals("8") || targetVer.equalsIgnoreCase("android"))) return true;
			String target = env.getOptions().get("magic.target");
			if (target != null && target.equalsIgnoreCase("android")) return true;
		}
		String targetProp = System.getProperty("magic.target");
		if (targetProp != null && targetProp.equalsIgnoreCase("android")) return true;
		return false;
	}

	// 编译期唯一的 Session Suffix，隔离多模块冲突
	private final String sessionSuffix = Long.toHexString(System.currentTimeMillis()) + "_" + Integer.toHexString(System.identityHashCode(this));
	private final String bridgeInternalName = "java/lang/invoke/MagicBridge_" + sessionSuffix;
	private final String bridgeClassName = "java.lang.invoke.MagicBridge_" + sessionSuffix;
	private final String bridgeDataClassName = "hope.magic.gen.MagicBridgeData_" + sessionSuffix;

	// 全模块现代模式唯一收敛类 MagicBridgeData ClassWriter 与原生 MagicBridge ClassWriter
	private ClassWriter bridgeDataWriter;
	private ClassWriter bridgeWriter;
	private final java.util.List<Consumer<MethodVisitor>> bridgeDataClinitInits = new ArrayList<>();

	// MagicBridge MemberName 记录，用于在 <clinit> 中批量极速解析并去重外部类加载
	public record BridgeMemberNameEntry(
		String fieldName,
		byte refKind,
		Type ownerType,
		String methodName,
		String methodDesc
	) {}
	private final java.util.List<BridgeMemberNameEntry> bridgeMemberNameEntries = new ArrayList<>();

	// 字段 / MemberName / MethodHandle 静态常量字段及生成方法去重映射表
	public record FieldOffsetRecord(String offsetFieldName, String baseFieldName) {}
	private final Map<String, FieldOffsetRecord> fieldOffsetMap = new LinkedHashMap<>();
	private final Map<String, String> fieldMethodMap = new LinkedHashMap<>();

	private final Map<String, String> memberNameMap = new LinkedHashMap<>();
	private final Map<String, String> linkToMethodMap = new LinkedHashMap<>();
	private final Map<String, String> bridgeClassFieldMap = new LinkedHashMap<>();

	private final Map<String, String> methodHandleMap = new LinkedHashMap<>();
	private final Map<String, String> methodHandleMethodMap = new LinkedHashMap<>();

	private final Map<String, String> indyMethodMap = new LinkedHashMap<>();

	private final Map<String, String> legacyFieldMethodMap = new LinkedHashMap<>();
	private final Map<String, String> legacyMethodMethodMap = new LinkedHashMap<>();

	private final Set<Symbol> installedClasses = new HashSet<>();
	private final Set<Symbol> legacyInstalledClasses = new HashSet<>();

	private int fieldId = 0;
	private int mnId = 0;
	private int mhId = 0;
	private int classFieldId = 0;
	private int methodId = 0;
	private boolean hasLinkToMethods = false;

	// 仅用于 MAGIC_ACCESSOR 经典模式（需独立延迟加载，避免干扰现代模式的零配置类校验）
	private final Map<Symbol, ClassWriter> legacyClassWriterMap = new LinkedHashMap<>();
	private final Map<ClassWriter, String> legacyClassNamesMap = new LinkedHashMap<>();
	private final Map<ClassWriter, java.util.List<Consumer<MethodVisitor>>> legacyClinitInits = new LinkedHashMap<>();

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		if (roundEnv.processingOver()) {
			return false;
		}

		for (TypeElement annotation : annotations) {
			for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
				if (!(element instanceof MethodSymbol methodSymbol)) {
					continue;
				}

				try {
					TreePath path = trees.getPath(element);
					if (path != null) {
						log.useSource(path.getCompilationUnit().getSourceFile());
						mMaker.toplevel = (JCCompilationUnit) path.getCompilationUnit();
					}

					dealElement(methodSymbol);
				} catch (Throwable e) {
					messager.printMessage(Diagnostic.Kind.ERROR, "处理失败: " + e.getMessage(), element);
				} finally {
					mMaker.toplevel = null;
					log.useSource(null);
				}
			}
		}

		// 写入 legacy 模式类（仅当存在 MAGIC_ACCESSOR 时）
		for (Map.Entry<ClassWriter, java.util.List<Consumer<MethodVisitor>>> entry : legacyClinitInits.entrySet()) {
			ClassWriter cw = entry.getKey();
			java.util.List<Consumer<MethodVisitor>> inits = entry.getValue();
			if (!inits.isEmpty()) {
				MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
				mv.visitCode();
				for (Consumer<MethodVisitor> init : inits) {
					init.accept(mv);
				}
				mv.visitInsn(Opcodes.RETURN);
				mv.visitMaxs(0, 0);
				mv.visitEnd();
			}
		}

		for (Map.Entry<Symbol, ClassWriter> entry : legacyClassWriterMap.entrySet()) {
			ClassWriter writer = entry.getValue();
			String className = legacyClassNamesMap.get(writer);
			try {
				writeClassBytes(mFiler.createClassFile(className, entry.getKey()), writer.toByteArray());
			} catch (Throwable e) {
				messager.printMessage(Diagnostic.Kind.ERROR, "无法写入生成的类文件 " + className + ": " + e.getMessage(), entry.getKey());
			}
		}
		legacyClassWriterMap.clear();
		legacyClassNamesMap.clear();
		legacyClinitInits.clear();

		// 写入现代收敛类 MagicBridgeData 和 MagicBridge
		if (bridgeDataWriter != null || hasLinkToMethods) {
			generateFinalBridgeData();
			bridgeDataWriter = null;
			bridgeWriter = null;
			bridgeDataClinitInits.clear();
			bridgeMemberNameEntries.clear();
			fieldOffsetMap.clear();
			fieldMethodMap.clear();
			memberNameMap.clear();
			linkToMethodMap.clear();
			methodHandleMap.clear();
			methodHandleMethodMap.clear();
			indyMethodMap.clear();
			legacyFieldMethodMap.clear();
			legacyMethodMethodMap.clear();
			installedClasses.clear();
			legacyInstalledClasses.clear();
			hasLinkToMethods = false;
		}

		return true;
	}

	private ClassWriter getOrCreateBridgeDataWriter() {
		if (bridgeDataWriter == null) {
			bridgeDataWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			bridgeDataWriter.visit(
				Opcodes.V1_8,
				Opcodes.ACC_PUBLIC,
				bridgeDataClassName.replace('.', '/'),
				null,
				"java/lang/Object",
				null
			);

			MethodVisitor mv = bridgeDataWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
			mv.visitCode();
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(1, 1);
			mv.visitEnd();
		}
		return bridgeDataWriter;
	}

	private ClassWriter getOrCreateBridgeWriter() {
		if (bridgeWriter == null) {
			bridgeWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			bridgeWriter.visit(
				Opcodes.V1_8,
				Opcodes.ACC_PUBLIC,
				bridgeInternalName,
				null,
				"java/lang/Object",
				null
			);

			MethodVisitor mv = bridgeWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
			mv.visitCode();
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(1, 1);
			mv.visitEnd();
		}
		return bridgeWriter;
	}

	private ClassWriter getOrCreateLegacyClassWriter(MethodSymbol element, String magicSuperClass) {
		if (legacyClassWriterMap.containsKey(element.owner)) {
			return legacyClassWriterMap.get(element.owner);
		}

		ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		String genClassName = "hope.magic.gen.MagicGenX" + ID_GEN.incrementAndGet();
		String superClassName = magicSuperClass != null ? magicSuperClass.replace('.', '/') : "hope/magic/runtime/MAGICIMPL";

		classWriter.visit(
			Opcodes.V1_8,
			Opcodes.ACC_PUBLIC,
			genClassName.replace('.', '/'),
			null,
			superClassName,
			null
		);

		MethodVisitor mv = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superClassName, "<init>", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();

		legacyClassWriterMap.put(element.owner, classWriter);
		legacyClassNamesMap.put(classWriter, genClassName);
		legacyClinitInits.put(classWriter, new ArrayList<>());
		return classWriter;
	}

	private void generateFinalBridgeData() {
		try {
			boolean p1Enabled = isP1OptimizationEnabled();
			if (bridgeWriter != null) {
				// 生成 MagicBridge 内部的原生 <clinit>
				if (!bridgeMemberNameEntries.isEmpty()) {
					MethodVisitor bridgeClinit = bridgeWriter.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
					bridgeClinit.visitCode();

					Map<String, Integer> externalClassSlotMap = null;
					if (p1Enabled) {
						// P1 优化路径: 槽位 0(ClassLoader), 槽位 1(Factory), 局部变量缓存外部类
						bridgeClinit.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/ClassLoader", "getSystemClassLoader", "()Ljava/lang/ClassLoader;", false);
						bridgeClinit.visitVarInsn(Opcodes.ASTORE, 0);

						bridgeClinit.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MemberName", "getFactory", "()Ljava/lang/invoke/MemberName$Factory;", false);
						bridgeClinit.visitVarInsn(Opcodes.ASTORE, 1);

						externalClassSlotMap = new LinkedHashMap<>();
						int nextSlot = 2;
						for (BridgeMemberNameEntry entry : bridgeMemberNameEntries) {
							if (!isBootstrapType(entry.ownerType)) {
								String flatName = entry.ownerType.tsym.flatName().toString();
								if (!externalClassSlotMap.containsKey(flatName)) {
									int slot = nextSlot++;
									externalClassSlotMap.put(flatName, slot);

									bridgeClinit.visitLdcInsn(flatName);
									bridgeClinit.visitInsn(Opcodes.ICONST_1);
									bridgeClinit.visitVarInsn(Opcodes.ALOAD, 0); // ClassLoader
									bridgeClinit.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
									bridgeClinit.visitVarInsn(Opcodes.ASTORE, slot);
								}
							}
						}

						for (BridgeMemberNameEntry entry : bridgeMemberNameEntries) {
							bridgeClinit.visitVarInsn(Opcodes.ALOAD, 1);
							bridgeClinit.visitIntInsn(Opcodes.BIPUSH, entry.refKind);
							bridgeClinit.visitTypeInsn(Opcodes.NEW, "java/lang/invoke/MemberName");
							bridgeClinit.visitInsn(Opcodes.DUP);

							if (isBootstrapType(entry.ownerType)) {
								bridgeClinit.visitLdcInsn(org.objectweb.asm.Type.getType(typeToDescriptor(entry.ownerType)));
							} else {
								String flatName = entry.ownerType.tsym.flatName().toString();
								int slot = externalClassSlotMap.get(flatName);
								bridgeClinit.visitVarInsn(Opcodes.ALOAD, slot);
							}

							bridgeClinit.visitLdcInsn(entry.methodName);
							bridgeClinit.visitLdcInsn(entry.methodDesc);
							bridgeClinit.visitVarInsn(Opcodes.ALOAD, 0); // ClassLoader
							bridgeClinit.visitMethodInsn(
								Opcodes.INVOKESTATIC,
								"java/lang/invoke/MethodType",
								"fromMethodDescriptorString",
								"(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;",
								false
							);

							bridgeClinit.visitIntInsn(Opcodes.BIPUSH, entry.refKind);
							bridgeClinit.visitMethodInsn(
								Opcodes.INVOKESPECIAL,
								"java/lang/invoke/MemberName",
								"<init>",
								"(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;B)V",
								false
							);

							bridgeClinit.visitInsn(Opcodes.ACONST_NULL);
							bridgeClinit.visitInsn(Opcodes.ICONST_M1);
							bridgeClinit.visitLdcInsn(org.objectweb.asm.Type.getType("Ljava/lang/NoSuchMethodException;"));
							bridgeClinit.visitMethodInsn(
								Opcodes.INVOKEVIRTUAL,
								"java/lang/invoke/MemberName$Factory",
								"resolveOrFail",
								"(BLjava/lang/invoke/MemberName;Ljava/lang/Class;ILjava/lang/Class;)Ljava/lang/invoke/MemberName;",
								false
							);
							bridgeClinit.visitFieldInsn(Opcodes.PUTSTATIC, bridgeInternalName, entry.fieldName, "Ljava/lang/invoke/MemberName;");
						}
					} else {
						// 未开启 P1: 每次重复 Class.forName 与多次 getSystemClassLoader()
						for (BridgeMemberNameEntry entry : bridgeMemberNameEntries) {
							bridgeClinit.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MemberName", "getFactory", "()Ljava/lang/invoke/MemberName$Factory;", false);
							bridgeClinit.visitIntInsn(Opcodes.BIPUSH, entry.refKind);
							bridgeClinit.visitTypeInsn(Opcodes.NEW, "java/lang/invoke/MemberName");
							bridgeClinit.visitInsn(Opcodes.DUP);

							pushClassForBootstrap(bridgeClinit, entry.ownerType);
							bridgeClinit.visitLdcInsn(entry.methodName);
							bridgeClinit.visitLdcInsn(entry.methodDesc);
							bridgeClinit.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/ClassLoader", "getSystemClassLoader", "()Ljava/lang/ClassLoader;", false);
							bridgeClinit.visitMethodInsn(
								Opcodes.INVOKESTATIC,
								"java/lang/invoke/MethodType",
								"fromMethodDescriptorString",
								"(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;",
								false
							);

							bridgeClinit.visitIntInsn(Opcodes.BIPUSH, entry.refKind);
							bridgeClinit.visitMethodInsn(
								Opcodes.INVOKESPECIAL,
								"java/lang/invoke/MemberName",
								"<init>",
								"(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;B)V",
								false
							);

							bridgeClinit.visitInsn(Opcodes.ACONST_NULL);
							bridgeClinit.visitInsn(Opcodes.ICONST_M1);
							bridgeClinit.visitLdcInsn(org.objectweb.asm.Type.getType("Ljava/lang/NoSuchMethodException;"));
							bridgeClinit.visitMethodInsn(
								Opcodes.INVOKEVIRTUAL,
								"java/lang/invoke/MemberName$Factory",
								"resolveOrFail",
								"(BLjava/lang/invoke/MemberName;Ljava/lang/Class;ILjava/lang/Class;)Ljava/lang/invoke/MemberName;",
								false
							);
							bridgeClinit.visitFieldInsn(Opcodes.PUTSTATIC, bridgeInternalName, entry.fieldName, "Ljava/lang/invoke/MemberName;");
						}
					}

					// 初始化构造器目标类的 static final Class 字段 (C2 JIT 常量折叠)
					for (Map.Entry<String, String> entry : bridgeClassFieldMap.entrySet()) {
						String flatName = entry.getKey();
						String clsField = entry.getValue();
						if (p1Enabled && externalClassSlotMap != null && externalClassSlotMap.containsKey(flatName)) {
							bridgeClinit.visitVarInsn(Opcodes.ALOAD, externalClassSlotMap.get(flatName));
						} else {
							bridgeClinit.visitLdcInsn(flatName);
							bridgeClinit.visitInsn(Opcodes.ICONST_1);
							bridgeClinit.visitVarInsn(Opcodes.ALOAD, 0); // ClassLoader
							bridgeClinit.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
						}
						bridgeClinit.visitFieldInsn(Opcodes.PUTSTATIC, bridgeInternalName, clsField, "Ljava/lang/Class;");
					}

					bridgeClinit.visitInsn(Opcodes.RETURN);
					bridgeClinit.visitMaxs(0, 0);
					bridgeClinit.visitEnd();
				}

				bridgeWriter.visitEnd();
				byte[] bridgeBytes = bridgeWriter.toByteArray();

				ClassWriter bdw = getOrCreateBridgeDataWriter();
				if (p1Enabled) {
					// P1 优化: ISO_8859_1 直转 byte[] 数组存储
					bdw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "BYTES", "[B", null, null).visitEnd();

					MethodVisitor installMv = bdw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "install", "()V", null, null);
					installMv.visitCode();
					installMv.visitLdcInsn(bridgeClassName);
					installMv.visitFieldInsn(Opcodes.GETSTATIC, bridgeDataClassName.replace('.', '/'), "BYTES", "[B");
					installMv.visitMethodInsn(Opcodes.INVOKESTATIC, "hope/magic/runtime/Magic", "installBridge", "(Ljava/lang/String;[B)V", false);
					installMv.visitInsn(Opcodes.RETURN);
					installMv.visitMaxs(2, 0);
					installMv.visitEnd();

					MethodVisitor clinitMv = bdw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
					clinitMv.visitCode();
					clinitMv.visitLdcInsn(new String(bridgeBytes, java.nio.charset.StandardCharsets.ISO_8859_1));
					clinitMv.visitFieldInsn(Opcodes.GETSTATIC, "java/nio/charset/StandardCharsets", "ISO_8859_1", "Ljava/nio/charset/Charset;");
					clinitMv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "getBytes", "(Ljava/nio/charset/Charset;)[B", false);
					clinitMv.visitFieldInsn(Opcodes.PUTSTATIC, bridgeDataClassName.replace('.', '/'), "BYTES", "[B");

					clinitMv.visitMethodInsn(Opcodes.INVOKESTATIC, bridgeDataClassName.replace('.', '/'), "install", "()V", false);
					for (Consumer<MethodVisitor> init : bridgeDataClinitInits) {
						init.accept(clinitMv);
					}
					clinitMv.visitInsn(Opcodes.RETURN);
					clinitMv.visitMaxs(2, 0);
					clinitMv.visitEnd();
				} else {
					// 未开启 P1: Base64 字符串存储
					String base64 = Base64.getEncoder().encodeToString(bridgeBytes);
					bdw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "BASE64", "Ljava/lang/String;", null, base64).visitEnd();

					MethodVisitor installMv = bdw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "install", "()V", null, null);
					installMv.visitCode();
					installMv.visitLdcInsn(bridgeClassName);
					installMv.visitFieldInsn(Opcodes.GETSTATIC, bridgeDataClassName.replace('.', '/'), "BASE64", "Ljava/lang/String;");
					installMv.visitMethodInsn(Opcodes.INVOKESTATIC, "hope/magic/runtime/Magic", "installBridge", "(Ljava/lang/String;Ljava/lang/String;)V", false);
					installMv.visitInsn(Opcodes.RETURN);
					installMv.visitMaxs(2, 0);
					installMv.visitEnd();

					MethodVisitor clinitMv = bdw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
					clinitMv.visitCode();
					clinitMv.visitMethodInsn(Opcodes.INVOKESTATIC, bridgeDataClassName.replace('.', '/'), "install", "()V", false);
					for (Consumer<MethodVisitor> init : bridgeDataClinitInits) {
						init.accept(clinitMv);
					}
					clinitMv.visitInsn(Opcodes.RETURN);
					clinitMv.visitMaxs(0, 0);
					clinitMv.visitEnd();
				}

				bdw.visitEnd();
				writeClassBytes(mFiler.createClassFile(bridgeDataClassName), bdw.toByteArray());
			} else if (bridgeDataWriter != null) {
				ClassWriter bdw = bridgeDataWriter;
				MethodVisitor clinitMv = bdw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
				clinitMv.visitCode();
				for (Consumer<MethodVisitor> init : bridgeDataClinitInits) {
					init.accept(clinitMv);
				}
				clinitMv.visitInsn(Opcodes.RETURN);
				clinitMv.visitMaxs(0, 0);
				clinitMv.visitEnd();

				bdw.visitEnd();
				writeClassBytes(mFiler.createClassFile(bridgeDataClassName), bdw.toByteArray());
			}
		} catch (Throwable e) {
			messager.printMessage(Diagnostic.Kind.ERROR, "生成收敛 MagicBridgeData 失败: " + e.getMessage());
		}
	}

	private void dealElement(MethodSymbol element) {
		HField hField = element.getAnnotation(HField.class);
		if (hField != null) {
			AccessMode mode = resolveMode(element, hField.mode());
			if (mode == AccessMode.MAGIC_ACCESSOR) {
				injectLegacyClinitInstall(element.owner);
				String magicSuperClass = getMagicSuperClassName(element.owner);
				ClassWriter cw = getOrCreateLegacyClassWriter(element, magicSuperClass);
				processFieldMagic(element, hField, cw, legacyClassNamesMap.get(cw));
			} else {
				ClassWriter cw = getOrCreateBridgeDataWriter();
				processFieldUnsafe(element, hField, cw, bridgeDataClassName, bridgeDataClinitInits);
			}
			return;
		}

		HMethod hMethod = element.getAnnotation(HMethod.class);
		if (hMethod != null) {
			AccessMode mode = resolveMode(element, hMethod.mode());
			if (mode == AccessMode.MAGIC_ACCESSOR) {
				injectLegacyClinitInstall(element.owner);
				String magicSuperClass = getMagicSuperClassName(element.owner);
				ClassWriter cw = getOrCreateLegacyClassWriter(element, magicSuperClass);
				processMethodMagic(element, hMethod, cw, legacyClassNamesMap.get(cw));
			} else if (mode == AccessMode.UNSAFE_AND_LINKTO) {
				ClassWriter bw = getOrCreateBridgeWriter();
				getOrCreateBridgeDataWriter(); // 保证生成 MagicBridgeData 基础设施
				injectClassClinitInstall(element.owner);
				processMethodLinkToBridge(element, hMethod, bw);
			} else {
				ClassWriter cw = getOrCreateBridgeDataWriter();
				if (mode == AccessMode.UNSAFE_AND_METHODHANDLE) {
					processMethodHandle(element, hMethod, cw, bridgeDataClassName, bridgeDataClinitInits);
				} else {
					// UNSAFE_AND_INDY
					processMethodIndy(element, hMethod, cw, bridgeDataClassName);
				}
			}
		}
	}

	private void injectLegacyClinitInstall(Symbol owner) {
		if (legacyInstalledClasses.add(owner)) {
			JCClassDecl classDecl = (JCClassDecl) trees.getTree(owner);
			if (classDecl != null) {
				JCExpression installCall = mMaker.Apply(
					List.nil(),
					mMaker.Select(mMaker.QualIdent(classSymbol("hope.magic.runtime.Magic")), names.fromString("install")),
					List.nil()
				);
				classDecl.defs = classDecl.defs.prepend(
					mMaker.Block(Flags.STATIC, List.of(mMaker.Exec(installCall)))
				);
			}
		}
	}

	private void injectClassClinitInstall(Symbol owner) {
		if (installedClasses.add(owner)) {
			JCClassDecl classDecl = (JCClassDecl) trees.getTree(owner);
			if (classDecl != null) {
				JCExpression installCall = mMaker.Apply(
					List.nil(),
					mMaker.Select(mMaker.QualIdent(classSymbol(bridgeDataClassName)), names.fromString("install")),
					List.nil()
				);
				classDecl.defs = classDecl.defs.prepend(
					mMaker.Block(Flags.STATIC, List.of(mMaker.Exec(installCall)))
				);
			}
		}
	}

	private boolean validateMethodSignature(MethodSymbol methodSymbol, MethodSymbol targetMethod) {
		boolean isConstructor = targetMethod.isConstructor() || targetMethod.name.toString().equals("<init>");
		if (isConstructor) {
			if (!types.isSameType(methodSymbol.getReturnType(), targetMethod.owner.type)) {
				messager.printMessage(
					Diagnostic.Kind.ERROR,
					"构造方法访问器返回类型须为目标类: 期望 " + targetMethod.owner.type + ", 实际 " + methodSymbol.getReturnType(),
					methodSymbol
				);
				return false;
			}
			List<VarSymbol> targetParams = targetMethod.getParameters();
			List<VarSymbol> accessorParams = methodSymbol.params;
			if (accessorParams.size() != targetParams.size()) {
				messager.printMessage(
					Diagnostic.Kind.ERROR,
					"构造方法访问器参数个数不匹配: 期望 " + targetParams.size() + " 个, 实际 " + accessorParams.size() + " 个",
					methodSymbol
				);
				return false;
			}
			for (int i = 0; i < targetParams.size(); i++) {
				if (!types.isSameType(accessorParams.get(i).type, targetParams.get(i).type)) {
					messager.printMessage(
						Diagnostic.Kind.ERROR,
						"参数 " + (i + 1) + " (" + accessorParams.get(i).name + ") 类型不匹配: 期望 " + targetParams.get(i).type + ", 实际 " + accessorParams.get(i).type,
						methodSymbol
					);
					return false;
				}
			}
			return true;
		}

		if (!types.isSameType(methodSymbol.getReturnType(), targetMethod.getReturnType())) {
			messager.printMessage(
				Diagnostic.Kind.ERROR,
				"方法返回类型与目标方法不匹配: 期望 " + targetMethod.getReturnType() + ", 实际 " + methodSymbol.getReturnType(),
				methodSymbol
			);
			return false;
		}

		boolean isStatic = targetMethod.isStatic();
		List<VarSymbol> targetParams = targetMethod.getParameters();
		List<VarSymbol> accessorParams = methodSymbol.params;

		if (isStatic) {
			if (accessorParams.size() != targetParams.size()) {
				messager.printMessage(
					Diagnostic.Kind.ERROR,
					"静态方法访问器参数个数不匹配: 期望 " + targetParams.size() + " 个, 实际 " + accessorParams.size() + " 个",
					methodSymbol
				);
				return false;
			}
			for (int i = 0; i < targetParams.size(); i++) {
				if (!types.isSameType(accessorParams.get(i).type, targetParams.get(i).type)) {
					messager.printMessage(
						Diagnostic.Kind.ERROR,
						"参数 " + (i + 1) + " (" + accessorParams.get(i).name + ") 类型不匹配: 期望 " + targetParams.get(i).type + ", 实际 " + accessorParams.get(i).type,
						methodSymbol
					);
					return false;
				}
			}
		} else {
			if (accessorParams.size() != targetParams.size() + 1) {
				messager.printMessage(
					Diagnostic.Kind.ERROR,
					"实例方法访问器参数个数不匹配 (首个参数须为目标对象): 期望 " + (targetParams.size() + 1) + " 个, 实际 " + accessorParams.size() + " 个",
					methodSymbol
				);
				return false;
			}
			if (!types.isSubtype(accessorParams.get(0).type, targetMethod.owner.type)) {
				messager.printMessage(
					Diagnostic.Kind.ERROR,
					"首个参数 (目标对象) 类型不匹配: 目标类型为 " + targetMethod.owner.type + ", 实际为 " + accessorParams.get(0).type,
					methodSymbol
				);
				return false;
			}
			for (int i = 0; i < targetParams.size(); i++) {
				if (!types.isSameType(accessorParams.get(i + 1).type, targetParams.get(i).type)) {
					messager.printMessage(
						Diagnostic.Kind.ERROR,
						"参数 " + (i + 2) + " (" + accessorParams.get(i + 1).name + ") 类型不匹配: 期望 " + targetParams.get(i).type + ", 实际 " + accessorParams.get(i + 1).type,
						methodSymbol
					);
					return false;
				}
			}
		}
		return true;
	}

	private AccessMode resolveMode(MethodSymbol element, AccessMode memberMode) {
		if (memberMode != null && memberMode != AccessMode.AUTO) {
			return memberMode;
		}
		HMarkMagic mark = element.owner.getAnnotation(HMarkMagic.class);
		if (mark != null && mark.mode() != AccessMode.AUTO) {
			return mark.mode();
		}
		if (isTargetAndroid()) {
			return AccessMode.UNSAFE_AND_METHODHANDLE;
		}
		return AccessMode.UNSAFE_AND_LINKTO;
	}

	//region 方案 1: MagicAccessorImpl (经典特权方案)

	private void processFieldMagic(
		MethodSymbol methodSymbol,
		HField hField,
		ClassWriter classWriter,
		String targetClassName
	) {
		DocReference reference = getSeeReference(HField.class, methodSymbol, ElementKind.FIELD);
		if (reference == null) return;

		VarSymbol target = (VarSymbol) reference.element();
		boolean isGetter = hField.isGetter();
		boolean isStatic = target.isStatic();
		JCMethodDecl methodDecl = trees.getTree(methodSymbol);

		if (!types.isSameType(methodSymbol.getReturnType(), (isGetter ? target.type : mSymtab.voidType))) {
			messager.printMessage(Diagnostic.Kind.ERROR, "字段类型与访问器返回类型不匹配: " + (isGetter ? target.type : "void") + " != " + methodSymbol.getReturnType(), methodSymbol);
			return;
		}

		// 方法去重
		String methodKey = targetClassName + "#" + target.owner.type + "#" + target.name + "#" + isGetter + "#" + isStatic;
		String genMethodName = legacyFieldMethodMap.get(methodKey);
		if (genMethodName != null) {
			rewriteMethodBody(methodDecl, targetClassName, genMethodName, isGetter);
			return;
		}

		genMethodName = "x" + (methodId++);
		legacyFieldMethodMap.put(methodKey, genMethodName);

		String methodDesc = '(' +
			(isStatic ? "" : typeToDescriptor(target.owner.type)) +
			(isGetter ? ")" + typeToDescriptor(target.type) : typeToDescriptor(target.type) + ")V");

		MethodVisitor mv = classWriter.visitMethod(
			Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
			genMethodName,
			methodDesc,
			null,
			null
		);

		String owner = dotToSlash(target.owner.type);
		String descriptor = typeToDescriptor(target.type);

		if (!isStatic) mv.visitVarInsn(Opcodes.ALOAD, 0);

		if (isGetter) {
			mv.visitFieldInsn(isStatic ? Opcodes.GETSTATIC : Opcodes.GETFIELD, owner, target.name.toString(), descriptor);
			mv.visitInsn(returnOpcode(target.type));
		} else {
			if (!isStatic) {
				mv.visitVarInsn(Opcodes.ALOAD, 0);
				mv.visitVarInsn(loadOpcode(target.type.tsym), 1);
			} else {
				mv.visitVarInsn(loadOpcode(target.type.tsym), 0);
			}
			mv.visitFieldInsn(isStatic ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD, owner, target.name.toString(), descriptor);
			mv.visitInsn(Opcodes.RETURN);
		}
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		rewriteMethodBody(methodDecl, targetClassName, genMethodName, isGetter);
	}

	private void processMethodMagic(
		MethodSymbol methodSymbol,
		HMethod hMethod,
		ClassWriter classWriter,
		String targetClassName
	) {
		DocReference reference = getSeeReference(HMethod.class, methodSymbol, ElementKind.METHOD, ElementKind.CONSTRUCTOR);
		if (reference == null) return;

		MethodSymbol targetMethod = (MethodSymbol) reference.element();
		if (!validateMethodSignature(methodSymbol, targetMethod)) return;

		JCMethodDecl methodDecl = trees.getTree(methodSymbol);
		boolean isConstructor = targetMethod.isConstructor() || targetMethod.name.toString().equals("<init>");

		if (targetMethod.isStatic() && !isConstructor) {
			if (hMethod.isSpecial()) {
				messager.printMessage(Diagnostic.Kind.ERROR, "静态方法不支持 isSpecial=true", methodSymbol);
				return;
			}
		}

		// 方法去重
		String methodKey = targetClassName + "#" + targetMethod.owner.type + "#" + targetMethod.name + "#" + targetMethod.type + "#" + (isConstructor || hMethod.isSpecial());
		String genMethodName = legacyMethodMethodMap.get(methodKey);
		if (genMethodName != null) {
			rewriteMethodBody(methodDecl, targetClassName, genMethodName, true);
			return;
		}

		genMethodName = "x" + (methodId++);
		legacyMethodMethodMap.put(methodKey, genMethodName);

		List<TypeSymbol> paramsL = methodSymbol.params.map(v -> v.type.tsym);
		String methodDesc = paramsL.stream().map(v -> typeToDescriptor(v.type)).collect(Collectors.joining("", "(", ")")) +
			typeToDescriptor(methodSymbol.getReturnType());

		MethodVisitor mv = classWriter.visitMethod(
			Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
			genMethodName,
			methodDesc,
			null,
			null
		);
		mv.visitCode();

		if (isConstructor) {
			String owner = dotToSlash(targetMethod.owner.type);
			mv.visitTypeInsn(Opcodes.NEW, owner);
			mv.visitInsn(Opcodes.DUP);
			int slot = 0;
			for (TypeSymbol typeSymbol : paramsL) {
				mv.visitVarInsn(loadOpcode(typeSymbol), slot);
				slot += typeSize(typeSymbol, mSymtab);
			}
			String ctorDesc = targetMethod.getParameters().stream().map(p -> typeToDescriptor(p.type)).collect(Collectors.joining("", "(", ")")) + "V";
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", ctorDesc, false);
			mv.visitInsn(Opcodes.ARETURN);
		} else {
			int slot = 0;
			for (TypeSymbol typeSymbol : paramsL) {
				mv.visitVarInsn(loadOpcode(typeSymbol), slot);
				slot += typeSize(typeSymbol, mSymtab);
			}

			int opcode;
			if (targetMethod.isStatic()) {
				opcode = Opcodes.INVOKESTATIC;
			} else if (hMethod.isSpecial() || targetMethod.isPrivate()) {
				opcode = Opcodes.INVOKESPECIAL;
			} else if (targetMethod.owner.isInterface()) {
				opcode = Opcodes.INVOKEINTERFACE;
			} else {
				opcode = Opcodes.INVOKEVIRTUAL;
			}

			String owner = dotToSlash(targetMethod.owner.type);
			String name = targetMethod.name.toString();
			String descriptor = targetMethod.getParameters().stream().map(p -> typeToDescriptor(p.type)).collect(Collectors.joining("", "(", ")")) +
				typeToDescriptor(targetMethod.getReturnType());

			mv.visitMethodInsn(opcode, owner, name, descriptor, opcode == Opcodes.INVOKEINTERFACE);
			mv.visitInsn(returnOpcode(targetMethod.getReturnType()));
		}
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		rewriteMethodBody(methodDecl, targetClassName, genMethodName, true);
	}
	//endregion

	//region 方案 2: Unsafe 字段访问 (收敛到 MagicBridgeData 并去重字段和方法)

	private void processFieldUnsafe(
		MethodSymbol methodSymbol,
		HField hField,
		ClassWriter classWriter,
		String targetClassName,
		java.util.List<Consumer<MethodVisitor>> clinitList
	) {
		DocReference reference = getSeeReference(HField.class, methodSymbol, ElementKind.FIELD);
		if (reference == null) return;

		VarSymbol target = (VarSymbol) reference.element();
		boolean isGetter = hField.isGetter();
		boolean isStatic = target.isStatic();
		JCMethodDecl methodDecl = trees.getTree(methodSymbol);

		if (!types.isSameType(methodSymbol.getReturnType(), (isGetter ? target.type : mSymtab.voidType))) {
			messager.printMessage(Diagnostic.Kind.ERROR, "字段类型与访问器返回类型不匹配: " + (isGetter ? target.type : "void") + " != " + methodSymbol.getReturnType(), methodSymbol);
			return;
		}

		// 1. 字段 Offset / Base 去重：同类、同名、同静态属性的字段共享同一个静态常量字段
		String fieldKey = target.owner.type.toString() + "#" + target.name.toString() + "#" + isStatic;
		FieldOffsetRecord record = fieldOffsetMap.get(fieldKey);
		if (record == null) {
			int id = fieldId++;
			String offsetFieldName = "OFF_" + id;
			String baseFieldName = isStatic ? "BASE_" + id : null;

			// 添加静态常量字段 (static final)
			classWriter.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, offsetFieldName, "J", null, null).visitEnd();
			if (isStatic) {
				classWriter.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, baseFieldName, "Ljava/lang/Object;", null, null).visitEnd();
			}

			// 注册 <clinit> 初始化
			clinitList.add(mv -> {
				pushClass(mv, target.owner.type);
				mv.visitLdcInsn(target.name.toString());
				if (isStatic) {
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, "hope/magic/runtime/LinkerHelper", "getStaticFieldOffset", "(Ljava/lang/Class;Ljava/lang/String;)J", false);
					mv.visitFieldInsn(Opcodes.PUTSTATIC, targetClassName.replace('.', '/'), offsetFieldName, "J");

					pushClass(mv, target.owner.type);
					mv.visitLdcInsn(target.name.toString());
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, "hope/magic/runtime/LinkerHelper", "getStaticFieldBase", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;", false);
					mv.visitFieldInsn(Opcodes.PUTSTATIC, targetClassName.replace('.', '/'), baseFieldName, "Ljava/lang/Object;");
				} else {
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, "hope/magic/runtime/LinkerHelper", "getFieldOffset", "(Ljava/lang/Class;Ljava/lang/String;)J", false);
					mv.visitFieldInsn(Opcodes.PUTSTATIC, targetClassName.replace('.', '/'), offsetFieldName, "J");
				}
			});

			record = new FieldOffsetRecord(offsetFieldName, baseFieldName);
			fieldOffsetMap.put(fieldKey, record);
		}

		// 2. 访问器方法去重：同目标字段、同Getter/Setter共享同一个生成方法
		String methodKey = target.owner.type.toString() + "#" + target.name.toString() + "#" + isGetter + "#" + isStatic;
		String genMethodName = fieldMethodMap.get(methodKey);
		if (genMethodName != null) {
			rewriteMethodBody(methodDecl, targetClassName, genMethodName, isGetter);
			return;
		}

		genMethodName = "x" + (methodId++);
		fieldMethodMap.put(methodKey, genMethodName);

		// 3. 生成访问器方法
		String methodDesc = '(' +
			(isStatic ? "" : typeToDescriptor(target.owner.type)) +
			(isGetter ? ")" + typeToDescriptor(target.type) : typeToDescriptor(target.type) + ")V");

		MethodVisitor mv = classWriter.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, genMethodName, methodDesc, null, null);
		mv.visitCode();

		mv.visitFieldInsn(Opcodes.GETSTATIC, "hope/magic/runtime/LinkerHelper", "UNSAFE", "Lsun/misc/Unsafe;");
		if (isStatic) {
			mv.visitFieldInsn(Opcodes.GETSTATIC, targetClassName.replace('.', '/'), record.baseFieldName(), "Ljava/lang/Object;");
		} else {
			mv.visitVarInsn(Opcodes.ALOAD, 0); // target obj
		}
		mv.visitFieldInsn(Opcodes.GETSTATIC, targetClassName.replace('.', '/'), record.offsetFieldName(), "J");

		if (isGetter) {
			String unsafeGetter = unsafeGetterMethodName(target.type);
			String unsafeDesc = unsafeGetterMethodDesc(target.type);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "sun/misc/Unsafe", unsafeGetter, unsafeDesc, false);
			if (unsafeGetter.equals("getObject")) {
				mv.visitTypeInsn(Opcodes.CHECKCAST, dotToSlash(target.type));
			}
			mv.visitInsn(returnOpcode(target.type));
		} else {
			int valSlot = isStatic ? 0 : 1;
			mv.visitVarInsn(loadOpcode(target.type.tsym), valSlot);
			String unsafeSetter = unsafeSetterMethodName(target.type);
			String unsafeDesc = unsafeSetterMethodDesc(target.type);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "sun/misc/Unsafe", unsafeSetter, unsafeDesc, false);
			mv.visitInsn(Opcodes.RETURN);
		}
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		rewriteMethodBody(methodDecl, targetClassName, genMethodName, isGetter);
	}
	//endregion

	//region 方案 2A: MagicBridge 内部原生直调 + static final MemberName + resolveOrFail

	private void processMethodLinkToBridge(
		MethodSymbol methodSymbol,
		HMethod hMethod,
		ClassWriter bridgeWriter
	) {
		DocReference reference = getSeeReference(HMethod.class, methodSymbol, ElementKind.METHOD, ElementKind.CONSTRUCTOR);
		if (reference == null) return;

		MethodSymbol targetMethod = (MethodSymbol) reference.element();
		if (!validateMethodSignature(methodSymbol, targetMethod)) return;

		JCMethodDecl methodDecl = trees.getTree(methodSymbol);
		hasLinkToMethods = true;

		boolean isConstructor = targetMethod.isConstructor() || targetMethod.name.toString().equals("<init>");
		boolean isStatic = targetMethod.isStatic() && !isConstructor;
		boolean isPrivate = targetMethod.isPrivate() || isConstructor;
		boolean isSpecial = hMethod.isSpecial() || isPrivate;
		boolean isInterface = targetMethod.owner.isInterface();

		byte refKind;
		String linkToType;
		if (isConstructor) {
			refKind = 7;
			linkToType = "linkToSpecial";
		} else if (isStatic) {
			refKind = 6;
			linkToType = "linkToStatic";
		} else if (isSpecial) {
			refKind = 7;
			linkToType = "linkToSpecial";
		} else if (isInterface) {
			refKind = 9;
			linkToType = "linkToInterface";
		} else {
			refKind = 5;
			linkToType = "linkToVirtual";
		}

		String targetMethodName = isConstructor ? "<init>" : targetMethod.name.toString();
		String targetMethodDesc = targetMethod.getParameters().stream().map(p -> typeToDescriptor(p.type)).collect(Collectors.joining("", "(", ")")) +
			(isConstructor ? "V" : typeToDescriptor(targetMethod.getReturnType()));

		String clsFieldName = null;
		if (isConstructor) {
			String clsKey = targetMethod.owner.type.tsym.flatName().toString();
			clsFieldName = bridgeClassFieldMap.get(clsKey);
			if (clsFieldName == null) {
				clsFieldName = "CLS_" + (classFieldId++);
				bridgeWriter.visitField(
					Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
					clsFieldName,
					"Ljava/lang/Class;",
					null,
					null
				).visitEnd();
				bridgeClassFieldMap.put(clsKey, clsFieldName);
			}
		}

		// 1. MemberName 字段去重定义在 MagicBridge 内部（强类型 static final MemberName）
		String mnKey = targetMethod.owner.type.toString() + "#" + targetMethodName + "#" + targetMethodDesc + "#" + refKind;
		String mnFieldName = memberNameMap.get(mnKey);
		if (mnFieldName == null) {
			int id = mnId++;
			mnFieldName = "MN_" + id;

			// 在 MagicBridge 中声明 public static final MemberName 字段
			bridgeWriter.visitField(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
				mnFieldName,
				"Ljava/lang/invoke/MemberName;",
				null,
				null
			).visitEnd();

			bridgeMemberNameEntries.add(new BridgeMemberNameEntry(
				mnFieldName,
				refKind,
				targetMethod.owner.type,
				targetMethodName,
				targetMethodDesc
			));

			memberNameMap.put(mnKey, mnFieldName);
		}

		// 2. MagicBridge 访问器方法去重：同目标方法、同调用模式复用同一个桥接方法
		String genMethodName = linkToMethodMap.get(mnKey);
		if (genMethodName != null) {
			rewriteMethodBody(methodDecl, bridgeClassName, genMethodName, true);
			return;
		}

		genMethodName = "x" + (methodId++);
		linkToMethodMap.put(mnKey, genMethodName);

		// 3. 在 MagicBridge 内部生成原生直调方法 (带 @ForceInline 和 @Hidden)
		List<TypeSymbol> paramsL = methodSymbol.params.map(v -> v.type.tsym);

		// 在 Bootstrap ClassLoader 中，对象参数统一擦除为 Object 以免 ClassNotFound
		StringBuilder bridgeMethodDesc = new StringBuilder("(");
		for (TypeSymbol p : paramsL) {
			if (isReferenceKind(p.type.getKind())) {
				bridgeMethodDesc.append("Ljava/lang/Object;");
			} else {
				bridgeMethodDesc.append(typeToDescriptor(p.type));
			}
		}
		bridgeMethodDesc.append(")");
		if (isConstructor) {
			bridgeMethodDesc.append("Ljava/lang/Object;");
		} else if (methodSymbol.getReturnType().getKind() != TypeKind.VOID && isReferenceKind(methodSymbol.getReturnType().getKind())) {
			bridgeMethodDesc.append("Ljava/lang/Object;");
		} else {
			bridgeMethodDesc.append(typeToDescriptor(methodSymbol.getReturnType()));
		}

		MethodVisitor bridgeMv = bridgeWriter.visitMethod(
			Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
			genMethodName,
			bridgeMethodDesc.toString(),
			null,
			null
		);
		bridgeMv.visitAnnotation("Ljdk/internal/vm/annotation/ForceInline;", true).visitEnd();
		bridgeMv.visitAnnotation("Ljava/lang/invoke/ForceInline;", true).visitEnd();
		bridgeMv.visitAnnotation("Ljdk/internal/vm/annotation/Hidden;", true).visitEnd();
		bridgeMv.visitAnnotation("Ljava/lang/invoke/LambdaForm$Hidden;", true).visitEnd();
		bridgeMv.visitCode();

		if (isConstructor) {
			// Constructor: 1. allocateInstance -> 2. DUP -> 3. load params -> 4. linkToSpecial(<init>) -> 5. return instance
			bridgeMv.visitMethodInsn(Opcodes.INVOKESTATIC, "jdk/internal/misc/Unsafe", "getUnsafe", "()Ljdk/internal/misc/Unsafe;", false);
			if (isBootstrapType(targetMethod.owner.type)) {
				bridgeMv.visitLdcInsn(org.objectweb.asm.Type.getType(typeToDescriptor(targetMethod.owner.type)));
			} else {
				bridgeMv.visitFieldInsn(Opcodes.GETSTATIC, bridgeInternalName, clsFieldName, "Ljava/lang/Class;");
			}
			bridgeMv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "jdk/internal/misc/Unsafe", "allocateInstance", "(Ljava/lang/Class;)Ljava/lang/Object;", false);
			bridgeMv.visitInsn(Opcodes.DUP);

			int slot = 0;
			for (TypeSymbol typeSymbol : paramsL) {
				bridgeMv.visitVarInsn(loadOpcode(typeSymbol), slot);
				slot += typeSize(typeSymbol, mSymtab);
			}

			bridgeMv.visitFieldInsn(Opcodes.GETSTATIC, bridgeInternalName, mnFieldName, "Ljava/lang/invoke/MemberName;");

			StringBuilder linkToDesc = new StringBuilder("(Ljava/lang/Object;");
			for (VarSymbol p : targetMethod.getParameters()) {
				if (isReferenceKind(p.type.getKind())) {
					linkToDesc.append("Ljava/lang/Object;");
				} else {
					linkToDesc.append(typeToDescriptor(p.type));
				}
			}
			linkToDesc.append("Ljava/lang/invoke/MemberName;)V");

			bridgeMv.visitMethodInsn(
				Opcodes.INVOKESTATIC,
				"java/lang/invoke/MethodHandle",
				"linkToSpecial",
				linkToDesc.toString(),
				false
			);

			bridgeMv.visitInsn(Opcodes.ARETURN);
		} else {
			int slot = 0;
			for (TypeSymbol typeSymbol : paramsL) {
				bridgeMv.visitVarInsn(loadOpcode(typeSymbol), slot);
				slot += typeSize(typeSymbol, mSymtab);
			}

			// 直接读取 MagicBridge 内部的 static final MemberName 字段（0 checkcast，C2 编译期常量折叠）
			bridgeMv.visitFieldInsn(Opcodes.GETSTATIC, bridgeInternalName, mnFieldName, "Ljava/lang/invoke/MemberName;");

			// 组装 linkToDescriptor
			StringBuilder linkToDesc = new StringBuilder("(");
			if (!isStatic) {
				linkToDesc.append("Ljava/lang/Object;");
			}
			for (VarSymbol p : targetMethod.getParameters()) {
				if (isReferenceKind(p.type.getKind())) {
					linkToDesc.append("Ljava/lang/Object;");
				} else {
					linkToDesc.append(typeToDescriptor(p.type));
				}
			}
			linkToDesc.append("Ljava/lang/invoke/MemberName;)");
			if (targetMethod.getReturnType().getKind() != TypeKind.VOID && isReferenceKind(targetMethod.getReturnType().getKind())) {
				linkToDesc.append("Ljava/lang/Object;");
			} else {
				linkToDesc.append(typeToDescriptor(targetMethod.getReturnType()));
			}

			bridgeMv.visitMethodInsn(
				Opcodes.INVOKESTATIC,
				"java/lang/invoke/MethodHandle",
				linkToType,
				linkToDesc.toString(),
				false
			);

			bridgeMv.visitInsn(returnOpcode(methodSymbol.getReturnType()));
		}
		bridgeMv.visitMaxs(0, 0);
		bridgeMv.visitEnd();

		// 4. 在 Javac 符号表中声明 MagicBridge 的对应方法（所有引用类型擦除为 Object，与 Bootstrap 字节码严格对齐）
		Symbol.ClassSymbol bridgeCs = classSymbol(bridgeClassName);
		if (bridgeCs.members_field == null) {
			bridgeCs.members_field = WriteableScope.create(bridgeCs);
			bridgeCs.flags_field = Flags.PUBLIC;
			bridgeCs.completer = Completer.NULL_COMPLETER;
		}
		List<Type> bridgeParamTypes = List.from(methodSymbol.params.map(v ->
			isReferenceKind(v.type.getKind()) ? mSymtab.objectType : v.type
		));
		Type bridgeReturnType = isConstructor ? mSymtab.objectType :
			((methodSymbol.getReturnType().getKind() != TypeKind.VOID && isReferenceKind(methodSymbol.getReturnType().getKind()))
			? mSymtab.objectType : methodSymbol.getReturnType());

		Type.MethodType mt = new Type.MethodType(
			bridgeParamTypes,
			bridgeReturnType,
			List.nil(),
			mSymtab.methodClass
		);
		MethodSymbol ms = new MethodSymbol(
			Flags.PUBLIC | Flags.STATIC,
			names.fromString(genMethodName),
			mt,
			bridgeCs
		);
		bridgeCs.members_field.enter(ms);

		// 5. 用户类直接调用 MagicBridge.<genMethodName>
		rewriteMethodBody(methodDecl, bridgeClassName, genMethodName, true);
	}
	//endregion

	//region 方案 2B: invokedynamic (indy) 动态调用点 (去重方法)

	private void processMethodIndy(
		MethodSymbol methodSymbol,
		HMethod hMethod,
		ClassWriter classWriter,
		String targetClassName
	) {
		DocReference reference = getSeeReference(HMethod.class, methodSymbol, ElementKind.METHOD, ElementKind.CONSTRUCTOR);
		if (reference == null) return;

		MethodSymbol targetMethod = (MethodSymbol) reference.element();
		if (!validateMethodSignature(methodSymbol, targetMethod)) return;

		JCMethodDecl methodDecl = trees.getTree(methodSymbol);

		boolean isConstructor = targetMethod.isConstructor() || targetMethod.name.toString().equals("<init>");
		boolean isStatic = targetMethod.isStatic() && !isConstructor;
		boolean isPrivate = targetMethod.isPrivate() || isConstructor;
		boolean isSpecial = hMethod.isSpecial() || isPrivate;
		boolean isInterface = targetMethod.owner.isInterface();

		int flags = (isStatic ? 1 : 0) | (isSpecial ? 2 : 0) | (isInterface ? 4 : 0) | (isConstructor ? 8 : 0);
		String targetMethodName = isConstructor ? "<init>" : targetMethod.name.toString();

		// 方法去重
		String methodKey = targetMethod.owner.type.toString() + "#" + targetMethodName + "#" + targetMethod.type.toString() + "#" + flags;
		String genMethodName = indyMethodMap.get(methodKey);
		if (genMethodName != null) {
			rewriteMethodBody(methodDecl, targetClassName, genMethodName, true);
			return;
		}

		genMethodName = "x" + (methodId++);
		indyMethodMap.put(methodKey, genMethodName);

		List<TypeSymbol> paramsL = methodSymbol.params.map(v -> v.type.tsym);
		String methodDesc = paramsL.stream().map(v -> typeToDescriptor(v.type)).collect(Collectors.joining("", "(", ")")) +
			typeToDescriptor(methodSymbol.getReturnType());

		MethodVisitor mv = classWriter.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, genMethodName, methodDesc, null, null);
		mv.visitCode();

		int slot = 0;
		for (TypeSymbol typeSymbol : paramsL) {
			mv.visitVarInsn(loadOpcode(typeSymbol), slot);
			slot += typeSize(typeSymbol, mSymtab);
		}

		Handle bsmHandle = new Handle(
			Opcodes.H_INVOKESTATIC,
			"hope/magic/runtime/LinkerHelper",
			"bootstrap",
			"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;Ljava/lang/String;I)Ljava/lang/invoke/CallSite;",
			false
		);

		String indyCallName = isConstructor ? "__init__" : targetMethod.name.toString();
		mv.visitInvokeDynamicInsn(
			indyCallName,
			methodDesc,
			bsmHandle,
			org.objectweb.asm.Type.getType(typeToDescriptor(targetMethod.owner.type)),
			indyCallName,
			flags
		);

		mv.visitInsn(returnOpcode(methodSymbol.getReturnType()));
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		rewriteMethodBody(methodDecl, targetClassName, genMethodName, true);
	}
	//endregion

	//region 方案 2C: MethodHandle.invokeExact (Android ART / 跨平台通用, 去重字段和方法)

	private void processMethodHandle(
		MethodSymbol methodSymbol,
		HMethod hMethod,
		ClassWriter classWriter,
		String targetClassName,
		java.util.List<Consumer<MethodVisitor>> clinitList
	) {
		DocReference reference = getSeeReference(HMethod.class, methodSymbol, ElementKind.METHOD, ElementKind.CONSTRUCTOR);
		if (reference == null) return;

		MethodSymbol targetMethod = (MethodSymbol) reference.element();
		if (!validateMethodSignature(methodSymbol, targetMethod)) return;

		JCMethodDecl methodDecl = trees.getTree(methodSymbol);

		boolean isConstructor = targetMethod.isConstructor() || targetMethod.name.toString().equals("<init>");
		boolean isStatic = targetMethod.isStatic() && !isConstructor;
		boolean isSpecial = hMethod.isSpecial() || isConstructor;
		String targetMethodName = isConstructor ? "<init>" : targetMethod.name.toString();

		// 1. MethodHandle 字段去重
		String mhKey = targetMethod.owner.type.toString() + "#" + targetMethodName + "#" + targetMethod.type.toString() + "#" + isStatic + "#" + isSpecial;
		String mhFieldName = methodHandleMap.get(mhKey);
		if (mhFieldName == null) {
			int id = mhId++;
			mhFieldName = "MH_" + id;

			// 添加静态 MethodHandle 字段
			classWriter.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, mhFieldName, "Ljava/lang/invoke/MethodHandle;", null, null).visitEnd();

			// 注册 <clinit> 初始化 MethodHandle
			String finalMhFieldName = mhFieldName;
			clinitList.add(mv -> {
				pushClass(mv, targetMethod.owner.type);
				mv.visitLdcInsn(targetMethodName);
				pushClass(mv, isConstructor ? targetMethod.owner.type : targetMethod.getReturnType());

				List<VarSymbol> params = targetMethod.getParameters();
				mv.visitIntInsn(Opcodes.BIPUSH, params.size());
				mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
				for (int i = 0; i < params.size(); i++) {
					mv.visitInsn(Opcodes.DUP);
					mv.visitIntInsn(Opcodes.BIPUSH, i);
					pushClass(mv, params.get(i).type);
					mv.visitInsn(Opcodes.AASTORE);
				}

				mv.visitInsn(isStatic ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
				mv.visitInsn(isSpecial ? Opcodes.ICONST_1 : Opcodes.ICONST_0);

				mv.visitMethodInsn(
					Opcodes.INVOKESTATIC,
					"hope/magic/runtime/LinkerHelper",
					"getMethodHandle",
					"(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;[Ljava/lang/Class;ZZ)Ljava/lang/invoke/MethodHandle;",
					false
				);
				mv.visitFieldInsn(Opcodes.PUTSTATIC, targetClassName.replace('.', '/'), finalMhFieldName, "Ljava/lang/invoke/MethodHandle;");
			});

			methodHandleMap.put(mhKey, mhFieldName);
		}

		// 2. 访问器方法去重
		String genMethodName = methodHandleMethodMap.get(mhKey);
		if (genMethodName != null) {
			rewriteMethodBody(methodDecl, targetClassName, genMethodName, true);
			return;
		}

		genMethodName = "x" + (methodId++);
		methodHandleMethodMap.put(mhKey, genMethodName);

		// 3. 生成基于 invokeExact 的方法调用
		List<TypeSymbol> paramsL = methodSymbol.params.map(v -> v.type.tsym);
		String methodDesc = paramsL.stream().map(v -> typeToDescriptor(v.type)).collect(Collectors.joining("", "(", ")")) +
			typeToDescriptor(methodSymbol.getReturnType());

		MethodVisitor mv = classWriter.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, genMethodName, methodDesc, null, null);
		mv.visitCode();

		mv.visitFieldInsn(Opcodes.GETSTATIC, targetClassName.replace('.', '/'), mhFieldName, "Ljava/lang/invoke/MethodHandle;");

		int slot = 0;
		for (TypeSymbol typeSymbol : paramsL) {
			mv.visitVarInsn(loadOpcode(typeSymbol), slot);
			slot += typeSize(typeSymbol, mSymtab);
		}

		String invokeExactDesc = paramsL.stream().map(v -> typeToDescriptor(v.type)).collect(Collectors.joining("", "(", ")"))
			+ typeToDescriptor(methodSymbol.getReturnType());

		mv.visitMethodInsn(
			Opcodes.INVOKEVIRTUAL,
			"java/lang/invoke/MethodHandle",
			"invokeExact",
			invokeExactDesc,
			false
		);

		mv.visitInsn(returnOpcode(methodSymbol.getReturnType()));
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		rewriteMethodBody(methodDecl, targetClassName, genMethodName, true);
	}

	private void rewriteMethodBody(JCMethodDecl methodDecl, String genClassName, String genMethodName, boolean hasReturn) {
		List<JCExpression> args = List.from(methodDecl.params.stream().map(v -> mMaker.Ident(v)).toList());
		mMaker.at(methodDecl.body);
		JCExpression apply = mMaker.Apply(
			List.nil(),
			mMaker.Select(mMaker.QualIdent(classSymbol(genClassName)), names.fromString(genMethodName)),
			args
		);

		if (hasReturn && methodDecl.restype != null && methodDecl.getReturnType().type.getKind() == TypeKind.DECLARED) {
			apply = mMaker.TypeCast(methodDecl.restype, apply);
		}

		methodDecl.body = mMaker.Block(0, List.of(
			hasReturn ? mMaker.Return(apply) : mMaker.Exec(apply)
		));
	}

	private String getMagicSuperClassName(Symbol owner) {
		for (AnnotationMirror am : owner.getAnnotationMirrors()) {
			if (am.getAnnotationType().asElement().getSimpleName().contentEquals("HMarkMagic")) {
				for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : am.getElementValues().entrySet()) {
					if (entry.getKey().getSimpleName().contentEquals("magicClass")) {
						Object val = entry.getValue().getValue();
						if (val instanceof TypeMirror tm) {
							return tm.toString();
						}
					}
				}
				return "hope.magic.runtime.MAGICIMPL";
			}
		}

		try {
			HMarkMagic mark = owner.getAnnotation(HMarkMagic.class);
			if (mark != null) {
				try {
					return mark.magicClass().getName();
				} catch (MirroredTypeException mte) {
					return mte.getTypeMirror().toString();
				}
			}
		} catch (Throwable ignored) {
		}

		return null;
	}

	private void pushClassForBootstrap(MethodVisitor mv, com.sun.tools.javac.code.Type type) {
		if (isBootstrapType(type)) {
			mv.visitLdcInsn(org.objectweb.asm.Type.getType(typeToDescriptor(type)));
		} else {
			mv.visitLdcInsn(type.tsym.flatName().toString());
			mv.visitInsn(Opcodes.ICONST_1);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/ClassLoader", "getSystemClassLoader", "()Ljava/lang/ClassLoader;", false);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
		}
	}

	private void pushClass(MethodVisitor mv, com.sun.tools.javac.code.Type type) {
		switch (type.getKind()) {
			case BOOLEAN -> mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", "TYPE", "Ljava/lang/Class;");
			case BYTE -> mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Byte", "TYPE", "Ljava/lang/Class;");
			case CHAR -> mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Character", "TYPE", "Ljava/lang/Class;");
			case SHORT -> mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Short", "TYPE", "Ljava/lang/Class;");
			case INT -> mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Integer", "TYPE", "Ljava/lang/Class;");
			case LONG -> mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Long", "TYPE", "Ljava/lang/Class;");
			case FLOAT -> mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Float", "TYPE", "Ljava/lang/Class;");
			case DOUBLE -> mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Double", "TYPE", "Ljava/lang/Class;");
			case VOID -> mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Void", "TYPE", "Ljava/lang/Class;");
			default -> mv.visitLdcInsn(org.objectweb.asm.Type.getType(typeToDescriptor(type)));
		}
	}

	private static boolean isBootstrapType(com.sun.tools.javac.code.Type type) {
		if (type == null || type.tsym == null) return false;
		String name = type.tsym.flatName().toString();
		return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("sun.") || name.startsWith("jdk.");
	}

	private static boolean isReferenceKind(TypeKind kind) {
		return kind != TypeKind.BOOLEAN && kind != TypeKind.BYTE && kind != TypeKind.CHAR
			&& kind != TypeKind.SHORT && kind != TypeKind.INT && kind != TypeKind.LONG
			&& kind != TypeKind.FLOAT && kind != TypeKind.DOUBLE && kind != TypeKind.VOID;
	}

	private static int loadOpcodeForKind(TypeKind kind) {
		return switch (kind) {
			case BOOLEAN, BYTE, CHAR, SHORT, INT -> Opcodes.ILOAD;
			case LONG -> Opcodes.LLOAD;
			case FLOAT -> Opcodes.FLOAD;
			case DOUBLE -> Opcodes.DLOAD;
			default -> Opcodes.ALOAD;
		};
	}

	private static int returnOpcodeForKind(TypeKind kind) {
		return switch (kind) {
			case BOOLEAN, BYTE, CHAR, SHORT, INT -> Opcodes.IRETURN;
			case LONG -> Opcodes.LRETURN;
			case FLOAT -> Opcodes.FRETURN;
			case DOUBLE -> Opcodes.DRETURN;
			case VOID -> Opcodes.RETURN;
			default -> Opcodes.ARETURN;
		};
	}

	private static char typeKindChar(TypeKind kind) {
		return switch (kind) {
			case BOOLEAN -> 'Z';
			case BYTE -> 'B';
			case CHAR -> 'C';
			case SHORT -> 'S';
			case INT -> 'I';
			case LONG -> 'J';
			case FLOAT -> 'F';
			case DOUBLE -> 'D';
			case VOID -> 'V';
			default -> 'L';
		};
	}

	private static String typeKindDescriptor(TypeKind kind) {
		return switch (kind) {
			case BOOLEAN -> "Z";
			case BYTE -> "B";
			case CHAR -> "C";
			case SHORT -> "S";
			case INT -> "I";
			case LONG -> "J";
			case FLOAT -> "F";
			case DOUBLE -> "D";
			case VOID -> "V";
			default -> "Ljava/lang/Object;";
		};
	}
	//endregion
}
