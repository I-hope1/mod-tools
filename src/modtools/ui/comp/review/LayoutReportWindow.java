package modtools.ui.comp.review;

import arc.func.Cons;
import arc.graphics.Color;
import arc.scene.*;
import arc.struct.Seq;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import modtools.content.ui.ReviewElement;
import modtools.ui.comp.Window;
import modtools.ui.comp.Window.IDisposable;

/**
 * 布局体检报告：从某个元素往下扫一遍，把 {@link LayoutIssues} 认为有问题的元素列出来。
 * <p>只做一次扫描（打开时），不跟着 UI 变化刷新——排查完重新打开比一直重扫更省。
 */
public class LayoutReportWindow extends Window implements IDisposable {
	/** 扫描上限：目的是找问题，不是遍历整个 UI */
	private static final int MAX_DEPTH = 64, MAX_RESULT = 200;

	private final Seq<Element> found = new Seq<>();

	public LayoutReportWindow(Element root, Cons<Element> onSelect) {
		super("layoutCheck", 320, 160);
		title.setText("Layout Check");
		cont.left().defaults().left().growX();
		if (root == null) {
			cont.add("No element");
			return;
		}
		scan(root, 0);

		if (found.isEmpty()) {
			cont.add("No layout issue found").color(Pal.accent).pad(6f);
		} else {
			cont.add("Found " + found.size + (found.size >= MAX_RESULT ? "+" : "") + " element(s)")
			 .color(Color.orange).pad(4f).row();
			cont.pane(t -> {
				t.left().defaults().left().growX();
				for (Element element : found) {
					int mask = LayoutIssues.check(element);
					t.table(row -> {
						row.left().defaults().left();
						row.image(Icon.warningSmall).size(16f).color(color(mask)).padRight(4f);
						// 报告是一次性快照，名字直接算一次即可，不必挂逐帧的 Prov
						row.add(ReviewElement.getElementName(element)).fontScale(0.85f).growX();
						row.add(LayoutIssues.describe(mask)).fontScale(0.75f).color(color(mask)).padLeft(6f);
					}).growX().pad(2f)
					 .with(row -> row.clicked(() -> {
						 if (onSelect != null) onSelect.get(element);
					 }))
					 .row();
				}
			}).grow();
		}
	}

	private void scan(Element element, int depth) {
		if (element == null || depth > MAX_DEPTH || found.size >= MAX_RESULT) return;
		if (LayoutIssues.check(element) != 0) found.add(element);
		if (element instanceof Group group) {
			for (Element child : group.getChildren()) scan(child, depth + 1);
		}
	}

	private static Color color(int mask) {
		if (LayoutIssues.has(mask, LayoutIssues.INVALID_SIZE) || LayoutIssues.has(mask, LayoutIssues.OUT_OF_PARENT)) {
			return Pal.remove;
		}
		return Color.orange;
	}

	/** 便于从别处（如菜单）直接打开的调试入口 */
	public static void show(Element root, Cons<Element> onSelect) {
		new LayoutReportWindow(root, onSelect);
	}
}
