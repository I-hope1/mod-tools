package modtools.annotations.unsafe;

import com.google.auto.service.AutoService;
import com.sun.source.tree.MemberReferenceTree.ReferenceMode;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.List;
import jdk.internal.org.objectweb.asm.*;
import modtools.annotations.BaseProcessor;
import modtools.annotations.asm.GenPool;
import modtools.annotations.asm.Sample.AConstants;
import modtools.annotations.processors.asm.BaseASMProc;

import javax.annotation.processing.Processor;
import javax.tools.JavaFileObject;
import java.io.*;
import java.util.*;
import java.util.Map.Entry;


@AutoService(Processor.class)
public class PoolProc extends BaseProcessor<ClassSymbol> {
	public static final String  ENTITY_MAPPING = "mindustry/gen/EntityMapping";

	ArrayList<String>    list    = new ArrayList<>();
	Map<Integer, String> idMap   = new HashMap<>();
	Map<String, String>  nameMap = new HashMap<>();

	String      genClassName;
	ClassSymbol genClassSymbol;

	public void init() throws IOException {
		ClassSymbol element = mSymtab.enterClass(mSymtab.unnamedModule, names.fromString("mindustry.gen.EntityMapping"));
		// MethodSymbol clinit  = (MethodSymbol) elements.getAllMembers(element).stream().filter(s -> s.name.equals(names.clinit)).findFirst().get();
		// println(clinit);
		// println(clinit.code);
		new ClassReader(element.classfile.openInputStream().readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
			                                 String[] exceptions) {
				if ("<clinit>".equals(name)) {
					return new MethodVisitor(Opcodes.ASM9) {
						boolean inIdMap, inNameMap;
						String  name;
						Integer id;
						public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
							if ((opcode == Opcodes.GETSTATIC) && owner.equals(ENTITY_MAPPING) && name.equals("idMap") && descriptor.equals("[Larc/func/Prov;")) {
								// println(descriptor);
								inIdMap = true;
							}
							if ((opcode == Opcodes.GETSTATIC) && owner.equals(ENTITY_MAPPING) && name.equals("nameMap") && descriptor.equals("Larc/struct/ObjectMap;")) {
								// println(descriptor);
								inNameMap = true;
							}
						}
						public void visitIntInsn(int opcode, int operand) {
							if (inIdMap && opcode == Opcodes.BIPUSH) {
								id = operand;
								inIdMap = false;
							}
							super.visitIntInsn(opcode, operand);
						}
						public void visitLdcInsn(Object value) {
							if (inNameMap && value instanceof String s) {
								name = s;
								inNameMap = false;
							}
							super.visitLdcInsn(value);
						}
						public void visitInvokeDynamicInsn(String name1, String descriptor, Handle bootstrapMethodHandle,
						                                   Object... bootstrapMethodArguments) {
							inIdMap = false;
							inNameMap = false;
							for (Object argument : bootstrapMethodArguments) {
								if (argument instanceof Handle h) {
									String owner = h.getOwner();
									if (id != null) {
										idMap.put(id, owner.replace("/", "."));
										id = null;
									}
									if (name != null) {
										nameMap.put(name, owner.replace("/", "."));
										name = null;
									}

									if (!list.contains(owner)) {
										list.add(owner);
									}
								}
							}
							super.visitInvokeDynamicInsn(name1, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
						}
					};
				}
				return super.visitMethod(access, name, descriptor, signature, exceptions);
			}
		}, ClassReader.EXPAND_FRAMES);
		// println(idMap);
		// println(nameMap);

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		genClassName = AConstants.nextGenClassName() + "EntityInit";
		cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, genClassName.replace('.', '/'), null,
		 "apzmagic/MAGICIMPL", null);
		for (String s : list) {
			// MethodSymbol ms = new MethodSymbol(Flags.STATIC | Flags.PUBLIC, ns(s), mSymtab.voidType, genClassSymbol);
			// genClassSymbol.members().enter(ms);
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "init", "(L" + s + ";)V", null, null);
			mv.visitIntInsn(Opcodes.ALOAD, 0);
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL, s, "<init>", "()V", false);
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(1, 1);
			mv.visitEnd();
		}
		cw.visitEnd();
		list.replaceAll(s -> s.replace("/", "."));

		JavaFileObject file = mFiler.createClassFile(genClassName);
		genClassSymbol = mSymtab.enterClass(mSymtab.unnamedModule, ns(genClassName));
		byte[] byteArray = cw.toByteArray();
		try (OutputStream outputStream = file.openOutputStream()) {
			outputStream.write(byteArray);
		}
		BaseASMProc.logClassFile(byteArray, genClassName);
	}
	public void dealElement(ClassSymbol element) throws Throwable {
		ClassSymbol pools         = findClassSymbol("arc.util.pooling.Pools");
		ClassSymbol entityMapping = findClassSymbol("mindustry.gen.EntityMapping");
		ClassSymbol poolable      = findClassSymbol("arc.util.pooling.Pool$Poolable");

		list.removeIf(s -> findClassSymbol(s).isSubClass(poolable, types));
		for (Entry<String, String> entry : nameMap.entrySet()) {
			if (!list.contains(entry.getValue())) {
				idMap.remove(entry.getKey());
			}
		}
		for (Entry<Integer, String> entry : idMap.entrySet()) {
			String s = entry.getValue();
			if (!list.contains(s)) {
				nameMap.remove(s);
			}
		}

		addImport(element, pools);
		addImport(element, entityMapping);

		// ---install---
		JCClassDecl  classDecl  = trees.getTree(element);
		JCMethodDecl methodDecl = findChild(classDecl, Tag.METHODDEF, m -> m.name.contentEquals("install"));
		if (methodDecl == null) return;
		for (String s : list) {
			ClassSymbol clazz = findClassSymbol(s);

			// Pools.set(%s%.class, () -> changeClass(s::create));
			JCLambda lambda = mMaker.Lambda(List.nil(),
			 mMaker.Apply(List.nil(), mMaker.Ident(ns("changeClass")),
				List.of(mMaker.Reference(ReferenceMode.INVOKE, ns("create"), mMaker.QualIdent(clazz), null)))
			);
			methodDecl.body.stats = methodDecl.body.stats.append(
			 mMaker.Exec(mMaker.Apply(List.nil(), mMaker.Select(mMaker.Ident(pools), ns("get")),
				List.of(mMaker.ClassLiteral(findClassSymbol(s)),
				 lambda)))
			);

			// map.put(s, () -> Pools.obtain(%s%.class, null));
			JCExpression expr = mMaker.Apply(List.nil(),
			 mMaker.Select(mMaker.Ident(ns("map")),
				ns("put")),
			 List.of(mMaker.Literal(s), mMaker.Lambda(List.nil(),
				mMaker.Apply(List.nil(), mMaker.Select(mMaker.QualIdent(pools), ns("obtain")),
				 List.of(mMaker.ClassLiteral(clazz), mMaker.Literal(TypeTag.BOT, null)))
			 ))
			);
			methodDecl.body.stats = methodDecl.body.stats.append(mMaker.Exec(expr));


		}
		for (Entry<Integer, String> entry : idMap.entrySet()) {
			Integer id = entry.getKey();
			String  s  = entry.getValue();
			// EntityMapping.idMap[%id%] = map.get(s);
			ClassSymbol clazz = findClassSymbol(s);

			methodDecl.body.stats = methodDecl.body.stats.append(
			 mMaker.Exec(mMaker.Assign(mMaker.Indexed(mMaker.Select(mMaker.Ident(entityMapping), ns("idMap")), mMaker.Literal(id)),
				mMaker.Apply(List.nil(), mMaker.Select(mMaker.Ident(ns("map")), ns("get")),
				 List.of(mMaker.Literal(s))))
			 ));
		}
		for (Entry<String, String> entry : nameMap.entrySet()) {
			String name = entry.getKey();
			String s    = entry.getValue();
			// EntityMapping.nameMap.put(name, map.get(s));
			ClassSymbol clazz = findClassSymbol(s);

			methodDecl.body.stats = methodDecl.body.stats.append(
			 mMaker.Exec(mMaker.Apply(List.nil(), mMaker.Select(mMaker.Select(mMaker.Ident(entityMapping), ns("nameMap")), ns("put")),
				List.of(mMaker.Literal(name), mMaker.Apply(List.nil(), mMaker.Select(mMaker.Ident(ns("map")), ns("get")),
				 List.of(mMaker.Literal(s))))))
			);
		}

		// println(methodDecl);

		// ---reset---

		methodDecl = findChild(classDecl, Tag.METHODDEF, m -> m.name.contentEquals("reset"));
		if (methodDecl == null) return;
		mMaker.at(methodDecl);
		JCVariableDecl var1 = methodDecl.params.get(0);
		for (String s : list) {
			// 生成:  if (var1 instanceof %s%) { %genClassName%.init((%s%)var1); Pools.get(%s%.class, null).free(var1); }
			ClassSymbol sym  = findClassSymbol(s);
			JCTypeCast  cast = mMaker.TypeCast(sym.type, mMaker.Ident(var1.sym));
			methodDecl.body.stats = methodDecl.body.stats.append(
			 mMaker.If(mMaker.TypeTest(mMaker.Ident(var1), mMaker.QualIdent(sym)),
				mMaker.Block(0, List.of(mMaker.Exec(mMaker.Apply(List.nil(), mMaker.Select(mMaker.QualIdent(genClassSymbol), ns("init")),
				 List.of(cast))), mMaker.Exec(mMaker.Apply(List.nil(),
				 mMaker.Select(mMaker.Apply(List.nil(), mMaker.Select(mMaker.Ident(pools), ns("get")), List.of(mMaker.ClassLiteral(sym), mMaker.Literal(TypeTag.BOT, null))),
					ns("free")), List.of(cast))))),
				null)
			);
		}
		// println(methodDecl);
	}
	public Set<Class<?>> getSupportedAnnotationTypes0() {
		return Set.of(GenPool.class);
	}
}
