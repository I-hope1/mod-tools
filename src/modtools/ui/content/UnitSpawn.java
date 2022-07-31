package modtools.ui.content;

import arc.Core;
import arc.Events;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Label;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.Fx;
import mindustry.content.UnitTypes;
import mindustry.game.EventType;
import mindustry.game.EventType.Trigger;
import mindustry.game.Team;
import mindustry.gen.BlockUnitUnit;
import mindustry.gen.Groups;
import mindustry.gen.Icon;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.type.UnitType;
import mindustry.ui.Styles;
import modtools.ui.Contents;
import modtools.ui.IntUI;
import modtools.ui.components.MyItemSelection;
import modtools.ui.components.Window;
import modtools.utils.Tools;

import static mindustry.Vars.player;
import static modtools.utils.MySettings.settings;
import static rhino.ScriptRuntime.*;

public class UnitSpawn extends Content {

	public UnitSpawn() {
		super("unitSpawn");
	}

	public boolean loadable() {
		return settings.getBool("load-" + name, "false");
	}

	Window ui;
	UnitType selectUnit;
	int amount = 0;
	Team team;
	Table unitCont;
	boolean loop = false, unitUnlimited;
	TextField xField, yField, amountField, teamField;
	// 用于获取点击的坐标
	Element el = new Element();
	InputListener listener = new InputListener() {
		@Override
		public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
			Core.scene.removeListener(this);
			Vec2 vec2 = Core.camera.unproject(x, y);
			setX(vec2.x);
			setY(vec2.y);
			el.remove();
			return false;
		}
	};

	{
		el.fillParent = true;
	}


	public void setup() {
		unitCont.clearChildren();
		MyItemSelection.buildTable(unitCont, Vars.content.units(), () -> selectUnit, u -> selectUnit = u,
				10);
		unitCont.row();
		unitCont.table(right -> {
			Label name = new Label("");
			Label localizedName = new Label("");
			right.update(() -> {
				name.setText(selectUnit != null ? selectUnit.name : "[red]ERROR");
				localizedName.setText(selectUnit != null ? selectUnit.localizedName : "[red]ERROR");
			});
			right.add(name).wrap().growX().row();
			right.add(localizedName).growX().wrap().row();
		}).growX();
	}

	public void setX(float x) {
		xField.setText(String.valueOf(x));
//		swapnX = x;
	}

	public void setY(float y) {
		yField.setText(String.valueOf(y));
//		swapnY = y;
	}

	@Override
	public void load() {
		selectUnit = UnitTypes.alpha;
		team = Team.derelict;

		ui = new Window(localizedName(), 40 * 10, 400, true);
		ui.cont.table(table -> unitCont = table).grow().row();
		// Options1 (生成pos)
		ui.cont.table(Window.myPane, table -> {
			table.table(x -> {
				x.add("x:");
				xField = x.field("" + player.x, newX -> {
//					if (!isNaN(newX)) swapnX = Float.parseFloat(newX);
				}).valid(val -> validNumber(val)).get();
			}).growX();
			table.table(y -> {
				y.add("y:");
				yField = y.field("" + player.y, newY -> {
//					if (!isNaN(newY)) swapnY = Float.parseFloat(newY);
				}).valid(val -> validNumber(val)).get();
			}).growX().row();
			table.button("选取坐标", Styles.flatToggleMenut, () -> {
//				ui.hide();
				if (el.parent == null) {
					Core.scene.addListener(listener);
					Core.scene.add(el);
				} else {
					el.remove();
				}
			}).growX().height(32).update(b -> {
				b.setChecked(el.parent != null);
			});
			table.button("获取玩家坐标", Styles.cleart, () -> {
				setX(player.x);
				setY(player.y);
			}).growX().height(32);
		}).growX().row();
		// Options2
		ui.cont.table(Window.myPane, table -> {
			table.table(t -> {
				t.add("队伍");
				teamField = t.field("" + team.id, text -> {
					int id = (int) toInteger(text);
					team = Team.get(id);
				}).valid(val -> Tools.validPosInt(val) && toInteger(val) < Team.all.length).get();
				var btn = new ImageButton(Icon.edit, Styles.cleari);
				btn.clicked(() -> IntUI.showSelectImageTableWithFunc(btn, new Seq<>(Team.all),
						() -> team, newTeam -> {
							team = newTeam;
							teamField.setText("" + team.id);
						}, 48, 32, 6,
						team -> IntUI.whiteui.tint(team.color), true));
				t.add(btn);
			});
			table.table(t -> {
				t.add("数量");
				amountField = t.field("0", text -> {
					amount = (int) toInteger(text);
				}).valid(val -> validNumber(val) && Tools.validPosInt(val)).get();
			});
		}).growX().row();
		ui.cont.table(table -> {
			table.button("@ok", Styles.cleart, this::spawn).size(90, 50)
					.disabled(b -> !isOk());
			table.check("loop", b -> loop = b);
		}).growX();
		ui.getCell(ui.cont).minHeight(ui.cont.getPrefHeight());
//		ui.addCloseButton();

		btn.setDisabled(() -> Vars.state.isMenu());
		btn.update(() -> {
			if (loop) {
				spawn();
			}
		});

		Events.run(Trigger.draw, () -> {
			if (!isOk()) return;

			float x = (float) toNumber(xField.getText());
			float y = (float) toNumber(yField.getText());

			Draw.z(Layer.overlayUI);
			Draw.color(Pal.accent);
			Lines.stroke(2);
			Lines.circle(x, y, 5);
			Draw.color();
		});

		loadSettings();

	}

	public boolean isOk() {
		return selectUnit != null && xField.isValid() && yField.isValid() && amountField.isValid() && teamField.isValid();
	}

	public boolean validNumber(String str) {
		try {
			double d = toNumber(str);
			return Math.abs(d) < 1E6 && !isNaN(d);
		} catch (Exception ignored) {}
		return false;
	}

	public void spawn() {
		if (!isOk()) return;

		float x = (float) toNumber(xField.getText());
		float y = (float) toNumber(yField.getText());

		if (selectUnit.uiIcon == null || selectUnit.fullIcon == null) {
			IntUI.showException("所选单位的图标为null，可能会崩溃", new NullPointerException("selectUnit icon is null"));
			return;
		}
		try {
			Unit unit = selectUnit.constructor.get();

			if (unit instanceof BlockUnitUnit) {
				IntUI.showException("所选单位为blockUnit，可能会崩溃", new IllegalArgumentException("selectUnit is blockunit"));
				return;
			}
			for (int i = 0; i < amount; i++) {
				selectUnit.spawn(team, x, y);
			}
		} catch (Throwable e) {
			IntUI.showException("无法生成单位 " + selectUnit.localizedName, e);
		}
	}

	@Override
	public void loadSettings() {
		Table table = new Table();
		table.left().defaults().left();
		table.add(localizedName()).color(Pal.accent).row();
		table.table(cont -> {
			cont.left().defaults().left().width(200);
			int[] defCap = {0};
			Events.run(EventType.WorldLoadEvent.class, () -> {
				defCap[0] = Vars.state.rules.unitCap;
				Vars.state.rules.unitCap = unitUnlimited ? 0xffffff : defCap[0];
			});
			cont.check("单位无限制", unitUnlimited, b -> {
				unitUnlimited = b;
				Vars.state.rules.unitCap = b ? 0xffffff : defCap[0];
			}).fillX().row();
			cont.button("隐藏单位死亡痕迹", () -> {
				Vars.content.units().each(u -> {
					u.deathExplosionEffect = Fx.none;
					u.createScorch = false;
					u.createWreck = false;
				});
			}).row();
			cont.button("杀死所有单位", () -> {
				Groups.unit.each(Unit::kill);
			}).fillX().row();
			cont.button("清除所有单位", () -> {
				Groups.unit.each(Unit::remove);
				Groups.unit.clear();
			}).fillX().row();
//			cont.check("服务器适配", b -> server = b);
		}).padLeft(6);

		Contents.settings.add(table);
	}

	@Override
	public void build() {
		setup();
		ui.show();
	}
}
