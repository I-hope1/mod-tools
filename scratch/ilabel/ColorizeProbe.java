import arc.graphics.Color;
import arc.graphics.g2d.Font.Glyph;
import arc.graphics.g2d.GlyphLayout.GlyphRun;
import arc.struct.IntMap;
import arc.struct.Seq;
import modtools.ui.comp.input.InlineLabel;

/**
 * 直接调用真实的 InlineLabel.splitAndColorize，用人工构造的 GlyphRun 验证染色结果。
 * 每个字形 advance 固定 10，xAdvances[0] = 0（libGDX 语义：首个元素是相对 run.x 的初始偏移）。
 */
public class ColorizeProbe {
	static final float ADV = 10f;

	/** 用若干行构造 runs：行内字符连续，y 逐行下移 */
	static Seq<GlyphRun> build(String... lines) {
		Seq<GlyphRun> runs = new Seq<>();
		float y = 0;
		for (String line : lines) {
			GlyphRun run = new GlyphRun();
			for (int i = 0; i < line.length(); i++) {
				Glyph g = new Glyph();
				g.id = line.charAt(i);
				g.xadvance = (int) ADV;
				run.glyphs.add(g);
			}
			run.xAdvances.add(0f);
			for (int i = 0; i < line.length(); i++) run.xAdvances.add(ADV);
			run.width = line.length() * ADV;
			run.x = 0;
			run.y = y;
			runs.add(run);
			y -= 20;
		}
		return runs;
	}

	static String dump(Seq<GlyphRun> runs) {
		StringBuilder sb = new StringBuilder();
		for (GlyphRun r : runs) {
			sb.append('[');
			for (Glyph g : r.glyphs) sb.append((char) g.id);
			sb.append("] color=").append(r.color).append(" x=").append(r.x)
			  .append(" y=").append(r.y).append(" w=").append(r.width).append('\n');
		}
		return sb.toString();
	}

	static void probe(String name, String text, String[] lines, Object... kv) {
		Seq<GlyphRun> runs = build(lines);
		IntMap<Color> map = new IntMap<>();
		for (int i = 0; i < kv.length; i += 2) map.put((Integer) kv[i], (Color) kv[i + 1]);
		InlineLabel.splitAndColorize(runs, map, new StringBuilder(text));
		System.out.println("=== " + name + " ===");
		System.out.println(dump(runs));
	}

	public static void main(String[] args) {
		Color white = Color.white, red = Color.red, blue = Color.blue;

		probe("A 单行 {0:white,3:red}", "abcde", new String[]{"abcde"}, 0, white, 3, red);
		probe("B 两行 {0:white,3:red} (无 len key)", "abcdefghij", new String[]{"abcde", "fghij"}, 0, white, 3, red);
		probe("C 两行 {0:white,3:red,10:white}", "abcdefghij", new String[]{"abcde", "fghij"}, 0, white, 3, red, 10, white);
		probe("D 两行 边界在第二行 {0:white,7:red}", "abcdefghij", new String[]{"abcde", "fghij"}, 0, white, 7, red);
		probe("E 三行 边界在第一行 {0:white,2:red}", "abcdefghijklmno", new String[]{"abcde", "fghij", "klmno"}, 0, white, 2, red);
		probe("F 两行 三个颜色 {0:white,2:red,7:blue}", "abcdefghij", new String[]{"abcde", "fghij"}, 0, white, 2, red, 7, blue);
		probe("G 两行 首色非白 {0:blue,3:red}", "abcdefghij", new String[]{"abcde", "fghij"}, 0, blue, 3, red);
		probe("H 两行 len key 是别的颜色 {0:white,3:red,10:blue}", "abcdefghij", new String[]{"abcde", "fghij"}, 0, white, 3, red, 10, blue);
		probe("I 单行 无 0 键 {2:red}", "abcde", new String[]{"abcde"}, 2, red);
		probe("J 空文本", "", new String[]{""}, 0, white);

		probe("K 边界正好在第二行起点 {0:white,5:red}", "abcdefghij", new String[]{"abcde", "fghij"}, 0, white, 5, red);
		probe("L 边界在最后一行中间 {0:white,4:red}", "abcdefghij", new String[]{"abcde", "fghij"}, 0, white, 4, red);
		probe("M 键超出文本长度 {0:white,8:red} len=5", "abcde", new String[]{"abcde"}, 0, white, 8, red);
		probe("N 三行 最后键在第二行 {0:white,3:red,7:blue}", "abcdefghijklmno",
			new String[]{"abcde", "fghij", "klmno"}, 0, white, 3, red, 7, blue);
	}
}
