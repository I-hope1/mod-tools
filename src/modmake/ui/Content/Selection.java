package modmake.ui.Content;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Rect;
import arc.math.geom.Vec2;
import arc.scene.event.*;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.*;
import arc.scene.ui.ScrollPane.ScrollPaneStyle;
import arc.scene.ui.layout.*;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Time;
import mindustry.Vars;
import mindustry.graphics.Pal;
import mindustry.gen.*;
import mindustry.game.*;
import mindustry.ui.*;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.environment.OverlayFloor;
import modmake.IntVars;
import modmake.ui.Contents;
import modmake.ui.IntUI;

public class Selection extends Content {

	public Selection() {
		super("selection");
	}

	final ObjectMap<String, Boolean> select = ObjectMap.of(
			"tile", true, "building", false, "floor", false);

	public Dialog frag;
	public Table pane, functions;
	boolean show = false, move = false;
	float x1, y1, x2, y2;
	static final int buttonWidth = 200, buttonHeight = 45;

	Function<Tile> tiles;
	Function<Building> buildings;
	Function<Floor> floors;

	public void loadString() {
		Table table = new Table();
		table.add(localizedName()).color(Pal.accent).growX().left().row();
		table.table(t -> {
			t.left().defaults().left();
			tiles.setting(t);
			buildings.setting(t);
			floors.setting(t);
		}).growX().left().padLeft(16);
		Contents.settings.add(table);
	}

	public void load() {
		frag = new Dialog() {
			public void draw() {
				Lines.stroke(4f);

				Draw.color(Pal.accentBack);
				Rect r = new Rect(Math.min(x1, x2), Math.min(y1, y2) - 1, Math.abs(x1 - x2), Math.abs(y1 - y2));
				Lines.rect(r);
				r.y += 1f;
				Draw.color(Pal.accent);
				Lines.rect(r);
			}
		};
		frag.background(Tex.button);
		frag.touchable = Touchable.enabled;
		frag.setFillParent(true);

		int maxH = 400;
		InputListener listener = new InputListener() {
			public boolean keyDown(InputEvent event, KeyCode keycode) {
				if (keycode.value == "Escape") {
					hide();
				}
				return false;
			}

			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (button != KeyCode.mouseLeft) {
					hide();
					move = false;
					return false;
				}
				x1 = x2 = x;
				y1 = y2 = y;
				move = true;
				Time.run(2, () -> move = true);
				return show;
			}

			public void touchDragged(InputEvent event, float x, float y, int pointer) {
				x2 = x;
				y2 = y;
			}

			public void touchUp(InputEvent event, float _x, float _y, int pointer, KeyCode button) {
				if (!move)
					return;
				float mx = x2, my = y2, tmp;
				if (x1 > x2) {
					tmp = x2;
					x2 = x1;
					x1 = tmp;
				}
				if (y1 > y2) {
					tmp = y2;
					y2 = y1;
					y1 = tmp;
				}
				if (x2 - x1 < Vars.tilesize || y2 - y1 < Vars.tilesize) {
					hide();
					return;
				}

				tiles.clearSeq();
				buildings.clearSeq();

				Vec2 v1 = Core.camera.unproject(x1, y1).cpy();
				Vec2 v2 = Core.camera.unproject(x2, y2).cpy();
				for (float y = v1.y; y < v2.y; y += Vars.tilesize) {
					for (float x = v1.x; x < v2.x; x += Vars.tilesize) {
						Tile tile = Vars.world.tileWorld(x, y);
						if (tile != null) {
							if (select.get("tile") || select.get("floor"))
								tiles.seq.add(tile);
							if (select.get("building") && tile.build != null && !buildings.seq.contains(tile.build))
								buildings.seq.add(tile.build);
						}
					}
				}

				pane.touchable = Touchable.enabled;
				pane.visible = true;
				pane.setPosition(
						Mathf.clamp(mx, buttonWidth, Core.graphics.getWidth()),
						// 32是btn的高度
						Mathf.clamp(my, (maxH + 32) / 2f, Core.graphics.getHeight() - (maxH + 32) / 2f),
						Align.bottomRight);
				frag.hide();
				show = false;
			}
		};
		Core.scene.addListener(listener);

		int W = buttonWidth, H = buttonHeight;

		functions = new Table(Styles.black5);
		functions.defaults().width(W);

		pane = new Table(Styles.black5, t -> {
			t.table(right -> {
				right.right().defaults().right();
				right.button(Icon.cancel, Styles.clearTransi, this::hide).size(32);
			}).fillX().right().row();
			ScrollPaneStyle paneStyle = new ScrollPaneStyle();
			paneStyle.background = Styles.none;

			t.pane(paneStyle, functions).size(W, maxH).get().setSize(W, maxH);
		});
		pane.right().defaults().width(W).right();
		pane.visible = false;
		pane.update(() -> {
			if (Vars.state.isMenu())
				hide();
		});

		tiles = new Function<>("tile", new Table(t -> {
			TextButton btn1 = t.button("Set", () -> {
			}).height(H).growX().right().get();
			btn1.clicked(() -> {
				IntUI.showSelectImageTable(btn1, Vars.content.blocks(), () -> null, block -> {
					tiles.seq.each(tile -> {
						if (tile.block() != block)
							tile.setBlock(block, tile.team());
					});
				}, 40, 32, 6, true);
			});
			t.row();
			t.button("Clear", () -> {
				tiles.seq.each(Tile::setAir);
			}).height(H).growX().right().row();
		}));

		buildings = new Function<>("building", new Table(t -> {
			t.button("Infinite health", () -> {
				buildings.seq.each(b -> {
					b.health = Float.MAX_VALUE;
				});
			}).height(H).growX().right().row();
			TextButton btn1 = t.button("Team", () -> {
			}).height(H).growX().right().get();
			btn1.clicked(() -> {
				Team[] arr = Team.baseTeams;
				Seq<TextureRegionDrawable> icons = new Seq<>();
				TextureRegionDrawable whiteui = (TextureRegionDrawable) Tex.whiteui;

				for (Team team : arr) {
					icons.add((TextureRegionDrawable) whiteui.tint(team.color));
				}
				IntUI.showSelectImageTableWithIcons(btn1, new Seq<>(arr), icons, () -> null, team -> {
					buildings.seq.each(b -> {
						b.changeTeam(team);
					});
				}, 40, 32, 3, false);
			});
			t.row();
			t.button("Kill", () -> {
				buildings.seq.each(Building::kill);
			}).height(H).growX().right().row();
		}));

		floors = new Function<>("floor", new Table(t -> {
			TextButton btn1 = t.button("Set Floor Reset Overlay", () -> {
			}).height(H).growX().right().get();
			btn1.clicked(() -> {
				IntUI.showSelectImageTable(btn1, Vars.content.blocks().select(block -> block instanceof Floor),
						() -> null, floor -> {
							tiles.seq.each(tile -> {
								tile.setFloor((Floor) floor);
							});
						}, 40, 32, 6, true);
			});
			t.row();
			TextButton btn2 = t.button("Set Floor Preserving Overlay", () -> {
			}).height(H).growX().right().get();
			btn2.clicked(() -> {
				IntUI.showSelectImageTable(btn2, Vars.content.blocks()
						.select(block -> block instanceof Floor && !(block instanceof OverlayFloor)), () -> null,
						floor -> {
							tiles.seq.each(tile -> {
								tile.setFloorUnder((Floor) floor);
							});
						}, 40, 32, 6, true);
			});
			t.row();
			TextButton btn3 = t.button("Set Overlay", () -> {
			}).height(H).growX().right().get();
			btn3.clicked(() -> {
				IntUI.showSelectImageTable(btn3, Vars.content.blocks().select(block -> block instanceof OverlayFloor),
						() -> null, overlay -> {
							tiles.seq.each(tile -> {
								tile.setOverlay(overlay);
							});
						}, 40, 32, 6, true);
			});
			t.row();
		}));

		Core.scene.root.addChildAt(10, pane);

		btn.setDisabled(() -> Vars.state.isMenu());
		loadString();
	}

	public void hide() {
		frag.hide();
		show = false;
		pane.visible = false;
		pane.touchable = Touchable.disabled;
	}

	public void build() {
		show = true;
		frag.show();
	}

	class Function<T> {
		public final Table wrap;
		public final Table main;
		public final Table cont;
		public final Seq<T> seq = new Seq<>();
		public final String name;

		public Function(String n, Table cont) {
			name = n;
			wrap = new Table();
			main = new Table();
			this.cont = cont;
			functions.add(wrap).row();
			main.image().color(Color.gray).height(2).padTop(3f).padBottom(3f).fillX().row();
			main.add(name).growX().left().row();
			main.add(cont).width(buttonWidth);
			setup();
		}

		public void setting(Table t) {
			t.check(name, select.get(name), b -> {
				if (b)
					setup();
				else
					remove();
				Core.settings.put(IntVars.modName + "-select-" + name, b);
			});
		}

		public void remove() {
			wrap.clearChildren();
		}

		public void clearSeq() {
			seq.removeAll(item -> true);
		}

		public void setup() {
			wrap.add(main);
		}
	}

}
