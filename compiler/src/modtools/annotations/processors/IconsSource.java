package modtools.annotations.processors;

import modtools.annotations.NameUtils;

import java.util.List;

/**
 * 拼出 {@code @IconAnn} 生成的图标类源码。
 * <p>
 * 只做字符串拼接：文件系统扫描和 {@code Filer} 落盘留在 {@link IconsProcessor}。
 * 这样这段可以当纯函数测——少个分号或 import 写错，症状是“处理器自己的输出编译不过”，
 * 现场很难看出是哪一行拼错的。
 */
final class IconsSource {
	private IconsSource() {}

	/**
	 * @param packageName 生成类的包名
	 * @param className   生成类的简单名（原类名去掉末尾的 {@code c}）
	 * @param sourceClass 原类的全限定名，生成的类会 import 它
	 * @param mainClass   主类全限定名，用于初始化 {@code modName}
	 * @param pngNames    图片文件名（不含扩展名），顺序决定字段顺序
	 */
	static String render(String packageName, String className, String sourceClass,
	                     String mainClass, List<String> pngNames) {
		StringBuilder out = new StringBuilder();

		out.append("package ").append(packageName).append(";\n\n");
		out.append("import ").append(sourceClass).append(";\n");
		out.append("import arc.scene.style.TextureRegionDrawable;\n");
		out.append("import arc.graphics.g2d.TextureRegion;\n");
		out.append("import arc.graphics.Texture;\n\n");

		out.append("public class ").append(className).append(" {\n");
		out.append("    public static String modName;\n\n");

		// 字段与 load() 里的赋值一一对应，先各自攒起来再一次性拼
		StringBuilder loadBody = new StringBuilder();
		loadBody.append("        if (modName == null) modName = mindustry.Vars.mods.getMod(")
		 .append(mainClass).append(".class).name;\n");

		for (String png : pngNames) {
			String fieldName = NameUtils.kebabToCamel(png);
			out.append("    public static TextureRegionDrawable ").append(fieldName).append(";\n");
			loadBody.append("        ").append(fieldName).append(" = n(\"").append(png).append("\");\n");
		}

		out.append("\n    private static TextureRegionDrawable n(String name) {\n");
		out.append("        return (TextureRegionDrawable) arc.Core.atlas.drawable(modName + \"-\" + name);\n");
		out.append("    }\n\n");

		out.append("    public static void load() {\n");
		out.append(loadBody);
		out.append("    }\n");
		out.append("}\n");
		return out.toString();
	}
}
