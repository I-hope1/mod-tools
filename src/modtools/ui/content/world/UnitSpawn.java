package modtools.ui.content.world;

import arc.*;
import arc.graphics.Gl;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.Version;
import mindustry.game.*;
import mindustry.game.EventType.Trigger;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.UnitType;
import mindustry.ui.Styles;
import modtools.events.ExecuteTree;
import modtools.events.ExecuteTree.*;
import modtools.ui.*;
import modtools.ui.HopeIcons;
import modtools.ui.IntUI.*;
import modtools.ui.components.*;
import modtools.ui.content.*;
import modtools.utils.*;
import modtools.utils.MySettings.Data;

import static mindustry.Vars.player;
import static modtools.ui.Contents.*;
import static rhino.ScriptRuntime.*;

public class UnitSpawn extends Content {

	public static final String noScorchMarksKey  = "@settings.noScorchMarks";
	public static final String killAllUnitsKey   = "@unitspawn.killAllUnits";
	public static final String removeAllUnitsKey = "@unitspawn.removeAllUnits";
	public final        String unitUnlimitedKey  = "@settings.unitUnlimited";
	public UnitSpawn() {
		super("unitSpawn");
	}

	{
		defLoadable = false;
	}

	Window   ui;
	UnitType selectUnit;
	int      amount = 1;
	Team     team;
	Table    unitCont;

	boolean unitUnlimited;
	float   x, y;
	TextField xField, yField, amountField, teamField;

	// 用于获取点击的坐标
	Element el = new Element();

	{
		el.fillParent = true;
	}

	InputListener listener = new InputListener() {
		public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
			Core.scene.removeListener(this);
			Vec2 vec2 = Core.camera.unproject(x, y);
			setX(vec2.x);
			setY(vec2.y);
			el.remove();
			return false;
		}
	};

	public void setup() {
		unitCont.clearChildren();
		MyItemSelection.buildTable(unitCont, Vars.content.units(), () -> selectUnit, u -> selectUnit = u,
		 10);
		unitCont.row();
		unitCont.table(right -> {
			Label name = new Label(""),
			 localizedName = new Label("");
			IntUI.longPressOrRclick(name, l -> {
				tester.put(l, selectUnit);
			});
			JSFunc.addDClickCopy(name);
			JSFunc.addDClickCopy(localizedName);
			right.update(() -> {
				name.setText(selectUnit != null ? selectUnit.name : "[red]ERROR");
				localizedName.setText(selectUnit != null ? selectUnit.localizedName : "[red]ERROR");
			});
			right.add(name).wrap().growX().row();
			right.add(localizedName).growX().wrap().row();
		}).growX();
	}
	public void setX(float x) {
		if (!validNumber(String.valueOf(x))) return;
		xField.setText(String.valueOf(x));
		this.x = x;
		//		swapnX = x;
	}
	public void setY(float y) {
		if (!validNumber(String.valueOf(y))) return;
		yField.setText(String.valueOf(y));
		this.y = y;
		//		swapnY = y;
	}

	public void _load() {
		root = ExecuteTree.nodeRoot(null, "unitSpawn", "<internal>", Icon.unitsSmall, () -> {});
		x = player.x;
		y = player.y;

		selectUnit = UnitTypes.dagger;
		team = Team.sharded;

		ui = new Window(localizedName(), 40 * 8, 400, true);
		ui.cont.table(table -> unitCont = table).grow().row();
		// Options1 (生成pos)
		ui.cont.table(Window.myPane, table -> {
			table.margin(-4f, 0f, -4f, 0f);
			table.table(x0 -> {
				x0.add("x:");
				xField = x0.field(String.valueOf(x), newX -> {
					x = Strings.parseFloat(newX);
				}).valid(this::validNumber).growX().get();
			}).growX();
			table.table(y0 -> {
				y0.add("y:");
				yField = y0.field(String.valueOf(y), newY -> {
					y = Strings.parseFloat(newY);
				}).valid(this::validNumber).growX().get();
			}).growX().row();
			table.button("@unitspawn.selectAposition", IntStyles.flatToggleMenut, () -> {
				if (el.parent == null) {
					Core.scene.addListener(listener);
					Core.scene.add(el);
				} else {
					el.remove();
				}
			}).growX().height(32).update(b -> {
				b.setChecked(el.parent != null);
			});
			table.button("@unitspawn.getfromplayer", IntStyles.cleart, () -> {
				setX(player.x);
				setY(player.y);
			}).growX().height(32);
		}).growX().row();
		// Options2
		ui.cont.table(Window.myPane, table -> {
			table.margin(-4f, 0f, -4f, 0f);
			table.table(t -> {
				t.add("@rules.title.teams");
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
				t.add("@filter.option.amount");
				amountField = t.field("" + amount, text -> {
					amount = (int) toInteger(text);
				}).valid(val -> validNumber(val) && Tools.validPosInt(val)).get();
			});
		}).growX().row();
		ui.cont.table(Window.myPane, table -> {
			table.margin(-4f, 0f, -4f, 0f);
			table.button("@ok", IntStyles.cleart, this::spawnIgnored)
			 .size(90, 50)
			 .disabled(b -> !isOk());
			table.button("post task", IntStyles.cleart, () ->
			 ExecuteTree.context(root, () ->
				ExecuteTree.node(selectUnit.localizedName,
				 "(" + x + "," + +y + ")\n{"
				 + team + "}[accent]×" + amount,
				 spawnRun()).resubmitted().apply()
			 )
			).size(90, 50);
			table.button(Icon.menuSmall, Styles.flati, 24, () -> {
				IntUI.showMenuListDispose(() -> Seq.with(
				 CheckboxList.withc(HopeIcons.loop, unitUnlimitedKey, unitUnlimited, () -> toggleUnitCap(!unitUnlimited)),
				 MenuList.with(Icon.eyeOffSmall, noScorchMarksKey, UnitSpawn::noScorchMarks),
				 MenuList.with(Icon.cancelSmall, killAllUnitsKey, UnitSpawn::killAllUnits),
				 MenuList.with(Icon.cancelSmall, removeAllUnitsKey, UnitSpawn::removeAllUnits)
				));
			}).size(32);
		}).growX();
		ui.getCell(ui.cont).minHeight(ui.cont.getPrefHeight());
		// ui.addCloseButton();

		Events.run(Trigger.draw, () -> {
			// if (!isOk()) return;

			Gl.flush();
			Draw.z(Layer.overlayUI);
			Draw.color(isOk() ? Pal.accent : Pal.remove, 0.5f);
			Lines.stroke(2);
			Lines.circle(x, y, 5);
			Draw.color();
		});
	}
	TaskNode root;
	public void load() {
		loadSettings();
		btn.setDisabled(() -> Vars.state.isMenu());
	}
	public boolean isOk() {
		return !Float.isNaN(x) && !Float.isNaN(y) && selectUnit != null && xField.isValid() && yField.isValid()
					 && amountField.isValid() && teamField.isValid();
	}

	public boolean validNumber(String str) {
		try {
			float d = Strings.parseFloat(str);
			return Math.abs(d) < 1E6 && !isNaN(d);
		} catch (Exception ignored) {}
		return false;
	}
	public void spawnIgnored() {
		try {
			spawn(selectUnit, amount, team, x, y);
		} catch (Throwable ignored) {}
	}
	public Runnable spawnRun() {
		UnitType selectUnit0 = selectUnit;
		int      amount0     = amount;
		Team     team0       = team;
		float    x0          = x, y0 = y;
		return () -> spawn(selectUnit0, amount0, team0, x0, y0);
	}

	public void spawn(UnitType selectUnit, int amount, Team team, float x, float y) {
		if (!isOk()) return;

		if (selectUnit.uiIcon == null || selectUnit.fullIcon == null) {
			RuntimeException exception = new RuntimeException("所选单位的图标为null，可能会崩溃", new NullPointerException("selectUnit icon is null"));
			IntUI.showException(exception);
			throw exception;
		}

		Unit unit = Version.number >= 136 ?
		 selectUnit.sample :
		 selectUnit.constructor.get();

		if (unit instanceof BlockUnitUnit) {
			RuntimeException exception = new RuntimeException("所选单位为blockUnit，可能会崩溃", new IllegalArgumentException("selectUnit is blockunit"));
			IntUI.showException(exception);
			throw exception;
		}
		for (int i = 0; i < amount; i++) {
			selectUnit.spawn(team, x, y);
		}
	}

	int defCap;
	public void loadSettings(Data SETTINGS) {
		Contents.settings_ui.add(localizedName(), new Table() {{
			left().defaults().left();
			Events.run(EventType.WorldLoadEvent.class, () -> {
				defCap = Vars.state.rules.unitCap;
				Vars.state.rules.unitCap = unitUnlimited ? 0xfff_fff : defCap;
			});
			check(unitUnlimitedKey, unitUnlimited, b -> toggleUnitCap(b)).padLeft(6).row();
			defaults().growX();
			button(noScorchMarksKey, UnitSpawn::noScorchMarks).row();
			button(killAllUnitsKey, UnitSpawn::killAllUnits).row();
			button(removeAllUnitsKey, UnitSpawn::removeAllUnits);
		}});
	}
	private void toggleUnitCap(boolean b) {
		unitUnlimited = b;
		Vars.state.rules.unitCap = b ? 0xffffff : defCap;
	}
	private static void removeAllUnits() {
		Groups.unit.each(Unit::remove);
		Groups.unit.clear();
		//			cont.check("服务器适配", b -> server = b);
	}
	private static void killAllUnits() {
		Groups.unit.each(Unit::kill);
	}
	private static void noScorchMarks() {
		Vars.content.units().each(u -> {
			u.deathExplosionEffect = Fx.none;
			u.createScorch = false;
			u.createWreck = false;
		});
	}
	public void build() {
		if (ui == null) _load();
		setup();
		ui.show();
	}
}
