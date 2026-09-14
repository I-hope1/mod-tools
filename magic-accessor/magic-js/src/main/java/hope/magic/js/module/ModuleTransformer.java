package hope.magic.js.module;

import hope.magic.js.ast.Node;
import hope.magic.js.ast.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * ES6+ 模块 AST 转换器 (Module Transformer)。
 * 负责将 ES6+ import / export 规范语法无缝降解（Lowering）为 CommonJS 运行时兼容的 AST 结构，
 * 保证与既有 bytecode compiler、JIT 优化、PIC 内联缓存及 CJS 模块无缝互操作。
 */
public class ModuleTransformer {

	public static boolean hasModuleSyntax(Node.Program program) {
		if (program == null || program.body == null) return false;
		for (Node stmt : program.body) {
			if (stmt instanceof Node.ImportDecl || stmt instanceof Node.ExportDecl) {
				return true;
			}
		}
		return false;
	}

	public static Node.Program transform(Node.Program program) {
		if (!hasModuleSyntax(program)) {
			return program;
		}

		List<Node> newBody = new ArrayList<>();
		int line = program.line;
		int col = program.column;

		// 1. 标记 ESM 规范属性: exports.__esModule = true;
		newBody.add(assignExports("__esModule", new Node.LiteralExpr(Boolean.TRUE, line, col), line, col));

		// 2. 提升处理所有顶层函数声明并先行绑定导出 (Function Hoisting & Early Export Binding)
		// 在 ESM 规范中，函数声明与导出先于模块正文求值，这是打破循环依赖（Circular Dependency）的核心机制。
		java.util.Set<Node> hoistedStmts = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for (Node stmt : program.body) {
			if (stmt instanceof Node.FunctionDecl fn) {
				if (fn.name != null) {
					newBody.add(fn);
					hoistedStmts.add(fn);
				}
			} else if (stmt instanceof Node.ExportDecl exp && exp.declaration instanceof Node.FunctionDecl fn) {
				hoistedStmts.add(exp);
				int eLine = exp.line;
				int eCol = exp.column;
				if (fn.name != null) {
					newBody.add(fn);
					if (exp.isDefault) {
						newBody.add(assignExports("default", new Node.IdentifierExpr(fn.name, eLine, eCol), eLine, eCol));
					}
					newBody.add(assignExports(fn.name, new Node.IdentifierExpr(fn.name, eLine, eCol), eLine, eCol));
				} else {
					newBody.add(assignExports("default", new Node.FunctionExpr(null, fn.params, fn.body, fn.isAsync, eLine, eCol), eLine, eCol));
				}
			}
		}

		int modCounter = 0;

		// 3. 提升处理所有 import 声明 (Module Hoisting)
		for (Node stmt : program.body) {
			if (stmt instanceof Node.ImportDecl importDecl) {
				String modVar = "$esm_import_" + (++modCounter);
				int iLine = importDecl.line;
				int iCol = importDecl.column;

				// var $esm_import_N = require("specifier");
				newBody.add(new Node.VarDecl(
						modVar,
						new Node.CallExpr(
								new Node.IdentifierExpr("require", iLine, iCol),
								List.of(new Node.LiteralExpr(importDecl.moduleSpecifier, iLine, iCol)),
								iLine,
								iCol
						),
						iLine,
						iCol
				));

				Node modVarIdent = new Node.IdentifierExpr(modVar, iLine, iCol);

				// import defaultBinding from "specifier";
				if (importDecl.defaultBinding != null) {
					// var def = ($esm_import_N && $esm_import_N.__esModule) ? $esm_import_N.default : $esm_import_N;
					Node cond = new Node.BinaryExpr(
							modVarIdent,
							TokenType.AND,
							new Node.MemberAccessExpr(modVarIdent, "__esModule", iLine, iCol),
							iLine,
							iCol
					);
					Node thenExpr = new Node.MemberAccessExpr(modVarIdent, "default", iLine, iCol);
					Node elseExpr = modVarIdent;
					newBody.add(new Node.VarDecl(
							importDecl.defaultBinding,
							new Node.TernaryExpr(cond, thenExpr, elseExpr, iLine, iCol),
							iLine,
							iCol
					));
				}

				// import * as ns from "specifier";
				if (importDecl.namespaceBinding != null) {
					newBody.add(new Node.VarDecl(importDecl.namespaceBinding, modVarIdent, iLine, iCol));
				}

				// import { a, b as c } from "specifier";
				for (Node.ImportSpecifier spec : importDecl.namedSpecifiers) {
					newBody.add(new Node.VarDecl(
							spec.localName,
							new Node.MemberAccessExpr(modVarIdent, spec.importedName, iLine, iCol),
							iLine,
							iCol
					));
				}
			}
		}

		// 4. 处理主体语句与 export 声明
		for (Node stmt : program.body) {
			if (stmt instanceof Node.ImportDecl || hoistedStmts.contains(stmt)) {
				// 已经提前 hoist 到顶部，跳过
				continue;
			}

			if (stmt instanceof Node.ExportDecl exportDecl) {
				int eLine = exportDecl.line;
				int eCol = exportDecl.column;

				// Case A: export default ...
				if (exportDecl.isDefault) {
					if (exportDecl.declaration instanceof Node.FunctionDecl fn) {
						if (fn.name != null) {
							newBody.add(fn);
							newBody.add(assignExports("default", new Node.IdentifierExpr(fn.name, eLine, eCol), eLine, eCol));
						} else {
							newBody.add(assignExports(
									"default",
									new Node.FunctionExpr(null, fn.params, fn.body, fn.isAsync, eLine, eCol),
									eLine,
									eCol
							));
						}
					} else if (exportDecl.declaration instanceof Node.ClassDecl cls) {
						if (cls.name != null) {
							newBody.add(cls);
							newBody.add(assignExports("default", new Node.IdentifierExpr(cls.name, eLine, eCol), eLine, eCol));
						} else {
							newBody.add(assignExports("default", cls, eLine, eCol));
						}
					} else {
						newBody.add(assignExports("default", exportDecl.declaration, eLine, eCol));
					}
					continue;
				}

				// Case B: export * from "spec"; or export * as ns from "spec";
				if (exportDecl.isExportAll) {
					String reModVar = "$esm_reexport_" + (++modCounter);
					newBody.add(new Node.VarDecl(
							reModVar,
							new Node.CallExpr(
									new Node.IdentifierExpr("require", eLine, eCol),
									List.of(new Node.LiteralExpr(exportDecl.fromModuleSpecifier, eLine, eCol)),
									eLine,
									eCol
							),
							eLine,
							eCol
					));
					Node reModIdent = new Node.IdentifierExpr(reModVar, eLine, eCol);

					if (exportDecl.exportAllAs != null) {
						newBody.add(assignExports(exportDecl.exportAllAs, reModIdent, eLine, eCol));
					} else {
						// for (var $k in reModIdent) { if ($k !== 'default' && $k !== '__esModule') exports[$k] = reModIdent[$k]; }
						Node condition = new Node.BinaryExpr(
								new Node.BinaryExpr(
										new Node.IdentifierExpr("$k", eLine, eCol),
										TokenType.NOT_EQ_EQ,
										new Node.LiteralExpr("default", eLine, eCol),
										eLine,
										eCol
								),
								TokenType.AND,
								new Node.BinaryExpr(
										new Node.IdentifierExpr("$k", eLine, eCol),
										TokenType.NOT_EQ_EQ,
										new Node.LiteralExpr("__esModule", eLine, eCol),
										eLine,
										eCol
								),
								eLine,
								eCol
						);
						Node ifBody = new Node.ExprStmt(
								new Node.AssignExpr(
										new Node.IndexAccessExpr(
												new Node.IdentifierExpr("exports", eLine, eCol),
												new Node.IdentifierExpr("$k", eLine, eCol),
												eLine,
												eCol
										),
										TokenType.ASSIGN,
										new Node.IndexAccessExpr(
												reModIdent,
												new Node.IdentifierExpr("$k", eLine, eCol),
												eLine,
												eCol
										),
										eLine,
										eCol
								),
								eLine,
								eCol
						);
						Node loopBody = new Node.IfStmt(condition, ifBody, null, eLine, eCol);
						newBody.add(new Node.ForInStmt("$k", true, reModIdent, loopBody, eLine, eCol));
					}
					continue;
				}

				// Case C: export { a, b as c } [from "spec"];
				if (exportDecl.namedSpecifiers != null && !exportDecl.namedSpecifiers.isEmpty()) {
					if (exportDecl.fromModuleSpecifier != null) {
						String reModVar = "$esm_reexport_" + (++modCounter);
						newBody.add(new Node.VarDecl(
								reModVar,
								new Node.CallExpr(
										new Node.IdentifierExpr("require", eLine, eCol),
										List.of(new Node.LiteralExpr(exportDecl.fromModuleSpecifier, eLine, eCol)),
										eLine,
										eCol
								),
								eLine,
								eCol
						));
						Node reModIdent = new Node.IdentifierExpr(reModVar, eLine, eCol);
						for (Node.ExportSpecifier spec : exportDecl.namedSpecifiers) {
							newBody.add(assignExports(
									spec.exportedName,
									new Node.MemberAccessExpr(reModIdent, spec.localName, eLine, eCol),
									eLine,
									eCol
							));
						}
					} else {
						for (Node.ExportSpecifier spec : exportDecl.namedSpecifiers) {
							newBody.add(assignExports(
									spec.exportedName,
									new Node.IdentifierExpr(spec.localName, eLine, eCol),
									eLine,
									eCol
							));
						}
					}
					continue;
				}

				// Case D: export var/let/const/function/class
				if (exportDecl.declaration != null) {
					Node decl = exportDecl.declaration;
					if (decl instanceof Node.VarDecl vd) {
						newBody.add(vd);
						newBody.add(assignExports(vd.name, new Node.IdentifierExpr(vd.name, vd.line, vd.column), vd.line, vd.column));
					} else if (decl instanceof Node.BlockStmt bs) {
						for (Node s : bs.statements) {
							newBody.add(s);
							if (s instanceof Node.VarDecl vd) {
								newBody.add(assignExports(vd.name, new Node.IdentifierExpr(vd.name, vd.line, vd.column), vd.line, vd.column));
							}
						}
					} else if (decl instanceof Node.FunctionDecl fn) {
						newBody.add(fn);
						newBody.add(assignExports(fn.name, new Node.IdentifierExpr(fn.name, fn.line, fn.column), fn.line, fn.column));
					} else if (decl instanceof Node.ClassDecl cls) {
						newBody.add(cls);
						newBody.add(assignExports(cls.name, new Node.IdentifierExpr(cls.name, cls.line, cls.column), cls.line, cls.column));
					} else {
						newBody.add(decl);
					}
					continue;
				}
			}

			// 普通语句直接添加
			newBody.add(stmt);
		}

		return new Node.Program(newBody, program.line, program.column);
	}

	private static Node assignExports(String key, Node valueExpr, int line, int column) {
		Node.MemberAccessExpr target = new Node.MemberAccessExpr(
				new Node.IdentifierExpr("exports", line, column),
				key,
				line,
				column
		);
		return new Node.ExprStmt(
				new Node.AssignExpr(target, TokenType.ASSIGN, valueExpr, line, column),
				line,
				column
		);
	}
}
