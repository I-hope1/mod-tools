package modtools.ui;

import arc.Core;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.NinePatch;
import arc.graphics.g2d.TextureAtlas.AtlasRegion;
import arc.graphics.gl.FileTextureData;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import arc.util.Timer.Task;
import mindustry.Vars;
import mindustry.gen.*;
import mindustry.graphics.MultiPacker.PageType;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.misc.PairProv.SizeProv;
import modtools.ui.IntUI.*;
import modtools.ui.comp.Hitter;
import modtools.utils.TaskManager;
import modtools.utils.ui.*;

import static modtools.ui.IntUI.topGroup;
import static modtools.ui.comp.utils.ValueLabel.DEBUG;

/** 用于添加preview侦听器  */
public class PreviewUtils {
	public static final float PREVIEW_SHOW_DELAY_SECONDS = 0.11f;
	public static Cell<?> buildImagePreviewButton(
	 Element element, Table table,
	 Prov<Drawable> prov, Cons<Drawable> consumer) {
		return addPreviewButton(table, p -> {
			p.top();
			try {
				Drawable drawable = prov.get();
				drawable.getClass(); // null check
				int   size = 100;
				float mul;
				if (element != null) {
					float w = Math.max(2, element.getWidth());
					mul = element.getHeight() / w;
				} else {
					mul = 1;
				}
				Image alphaBg = new Image(Tex.alphaBg);
				alphaBg.color.a = 0.7f;
				p.stack(alphaBg, new Image(drawable))
				 .update(t -> t.setColor(element != null ? element.color : Color.white))
				 .size(size, size * mul).row();

				p.add(ReflectTools.getName(drawable == TmpVars.trd ?
					TmpVars.trd.getRegion().getClass() : drawable.getClass()))
				 .color(KeyValue.stressColor).pad(4)
				 .left().row();
				KeyValue keyValue = KeyValue.THE_ONE;
				p.defaults().growX();
				if (drawable instanceof TextureRegionDrawable trd && trd.getRegion() instanceof AtlasRegion atg) {
					keyValue.label(p, "Name", () -> atg.name);
					if (atg.texture.getTextureData() instanceof FileTextureData) {
						String   str  = String.valueOf(atg.texture);
						char     c    = str.charAt(str.length() - 1);
						PageType type = PageType.all[Character.isDigit(c) ? c - '0' : 0];
						keyValue.label(p, "Page", type::name, Pal.accent);
					}
					keyValue.valueLabel(p, "Texture", () -> atg.texture, Texture.class);
				} else if (drawable instanceof ScaledNinePatchDrawable snpd) {
					NinePatch patch = snpd.getPatch();

					keyValue.valueLabel(p, "Texture", patch::getTexture, Texture.class);
				}
				keyValue.label(p, "Original Size", new SizeProv(() ->
				 Tmp.v1.set(prov.get().getMinWidth(), prov.get().getMinHeight())
				));
				p.row();
				if (consumer != null) {
					p.button(Icon.pickSmall, Styles.clearNonei, () -> {
						IntUI.drawablePicker().show(prov.get(), consumer);
					}).size(42);
				}
			} catch (Throwable e) {
				if (DEBUG) Log.err(e);
				p.add("ERROR").labelAlign(Align.left).row();
				p.image(Core.atlas.drawable("error"));
			}
		});
	}
	public static Cell<ImageButton> addPreviewButton(Table table, Cons<Table> cons) {
		return table.button(Icon.imageSmall, Styles.clearNonei, IntVars.EMPTY_RUN)
		 .size(36)
		 .with(button -> addPreviewListener(button, cons));
	}
	public static void addPreviewListener(Element element, Cons<Table> cons) {
		element.addListener(new HoverAndExitListener() {
			Hitter hitter = null;
			SelectTable table;
			final Task showTask = TaskManager.newTask(this::show);
			final Task hideTask = TaskManager.newTask(() -> {
				if (hitter != null && hitter.hide()) hitter = null;
			});
			void hide() {
				showTask.cancel();
				if (hitter != null && hitter.canHide()) {
					TaskManager.trySchedule(0.1f, hideTask);
				}
			}
			public void enter0(InputEvent event, float x, float y, int pointer, Element fromActor) {
				if (hideTask.isScheduled()) {
					hideTask.cancel();
					return;
				}
				TaskManager.scheduleOrReset(PREVIEW_SHOW_DELAY_SECONDS, showTask);
			}
			void show(){
				if (hideTask.isScheduled()) {
					hideTask.cancel();
					return;
				}
				if (hitter != null) hitter.hide(); // 移除上一次的
				if (Hitter.peek() != hitter) Hitter.peek().hide();

				table = IntUI.showSelectTable(element, (p, _, _) -> cons.get(p), false, Align.bottom);
				hitter = Hitter.peek();
				if (Vars.mobile) {
					hitter.touchable = Touchable.disabled;
					hitter.autoClose = true;
					table.table.update(() -> {
						if (hitter != null && hitter.init) {
							topGroup.addChild(hitter);
							hitter.touchable = Touchable.enabled;
							hitter.autoClose = true;
							table.table.update(null);
						}
					});
				}
				table.clearChildren();
				table.add(table.table);
				table.touchable = Touchable.enabled;
				table.addListener(new HoverAndExitListener() {
					public void enter0(InputEvent event, float x, float y, int pointer, Element fromActor) {
						hideTask.cancel();
					}
					public void exit0(InputEvent event, float x, float y, int pointer, Element toActor) {
						hide();
					}
				});
			}
			public void exit0(InputEvent event, float x, float y, int pointer, Element toActor) {
				hide();
			}
		});
	}
}
