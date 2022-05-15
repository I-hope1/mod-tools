package modmake.ui.until;

import arc.func.Func;
import arc.func.Func2;
import arc.func.Prov;
import arc.graphics.Color;
import arc.scene.ui.*;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.JsonValue;
import mindustry.Vars;
import mindustry.content.Fx;
import mindustry.entities.Effect;
import mindustry.entities.bullet.BulletType;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.type.*;
import mindustry.ui.Styles;
import modmake.ui.components.Fields;
import modmake.ui.IntUI;

import java.lang.reflect.Field;

public class buildContent {

	ObjectMap<Class<?>, String> defaultClass = ObjectMap.of(
			Effect.class, "none",
			UnitType.class, "mono",
			Item.class, "copper",
			Liquid.class, "water",
			ItemStack.class, "copper/0",
			LiquidStack.class, "water/0"
	);
	Seq<String> _Fx = new Seq<>();

	{
		Field[] fs = Fx.class.getFields();
		for (Field f : fs) {
			_Fx.add(f.getName());
		}
	}

	ObjectMap<Class<?>, Func2<Table, JsonValue, Prov<String>>> filterClass = ObjectMap.of(
			Color.class, (Func2<Table, JsonValue, Prov<String>>) (table, value) -> {
				Color color;
				color = Color.valueOf(value.asString());

				Button button = new Button();

				Cell<Image> image = button.image().size(30).color(color);
				Label label = button.add("" + color).get();
				/* 使用原本自带的采色器 */
				button.clicked(() -> Vars.ui.picker.show(color, c -> {
					image.color(c);
					label.setText(c + "");
				}));

				table.add(button);

				return () -> "\"" + label.getText() + "\"";
			},
			BulletType.class, (Func2<Table, JsonValue, Prov<String>>) (table, value) -> {
				table = table.table().get();
				value = value.isObject() ? value.get() : new UncObject();
				let type = value.remove("type");
				let typeName = type == null ? type.asString() : "BulletType";
				let selection = new typeSelection(Classes.get(typeName), typeName, {bullet:types.bullet });
				table.add(selection.table).padBottom(4).row();
				let cont = table.table().name("cont").get();
				let map = fObject(cont, () -> selection.type, value, Seq ([BulletType]))
				return map
			},
			StatusEffect.class, (table, value) -> {
				table = table.table().get();
				value = value || new IntObject();
				Table cont = table.table().name("cont").get();
				Prov<String> map = fObject(cont, () -> StatusEffect.class, value, Seq.of(StatusEffect.class));
				return map;
			},
	/* AmmoType, (table, value) -> {},
	DrawBlock, (table, value) -> {},
	Ability, (table, value) -> {}, */
			Weapon.class, (table, value) -> {
				table = table.table().get();
				value = value || new IntObject();
				Table cont = table.table().name("cont").get();
				Prov<String> map = fObject(cont, () -> Weapon.class, value, Seq.of(Weapon.class));
				return map;
			},
			ItemStack.class, (table, value) ->{
				String item;
				int amount;
				if (typeof value =="string") {
					String[] arr = value.asString().split("/");
					item = arr[0];
					amount = arr[1];
				} :
					value instanceof IntObject ? [value.get("item"), value.get("amount")] : [Items.copper, 0]

				let items = Vars.content.items();

				// if (!items.contains(item)) throw "Unable to convert " + item + " to Item."
				if (Float.isNaN(amount)) throw UnknownError("\"" + amount + "\" isn\"t a number');
				return buildOneStack(table, "item", items, item, amount);
			},
			// like ItemStack
			LiquidStack.class, (table, value) -> {
				let[item, amount] =typeof value =="string" ?
					value.split("/") : [value.get("liquid"), value.get("amount")]

				let items = Vars.content.liquids()

				// if (!items.contains(item)) throw "Unable to convert " + item + " to Liquid."
				if (isNaN(amount)) amount = 0// throw TypeError("\"" + amount + "\" isn\"t a number')
				return buildOneStack(table, "liquid", items, item, amount);
			},
			Effect.class, (table, value) -> {
				let val = value.isString() ? value.asString() : defaultClass.get(Effect.class);
				TextButton btn = table.button(val, Styles.cleart, () -> {
						IntUI.showSelectListTable(btn, effects, val, 130, 50, () -> btn.setText(val = fx), true);
				}).size(130, 45).get();
				return () -> val;
			},
			UnitType.class, (table, value) -> {
				value = "" + value || defaultClass.get(UnitType.class);
				let prov = IntFunc.selectionWithField(table, Vars.content.units(), value, 42, 32, 6, true)

				return prov;
			},
			Item.class, (table, value) -> {
				value = "" + value || defaultClass.get(Item.class);
				let prov = IntFunc.selectionWithField(table, Vars.content.items(), value, 42, 32, 6, true)

				return prov
			},
			Liquid.class, (table, value) -> {
				value = "" + value || defaultClass.get(Liquid.class);
				let prov = IntFunc.selectionWithField(table, Vars.content.liquids(), value, 42, 32, 6, true)

				return prov
			},
			ObjectMap.class, (table, value, classes) -> {
				let map = new IntObject();
				let cont = new Table(Tex.button);
				let children = new Table();
				cont.add(children).fillX().row();
				table.add(cont).fillX()
				let i = 0
				function add (k, v){
				children.add(Fields.colorfulTable(i++, cons(t = > {
								map.put(
										exports.filterClass.get(classes[0])(t, k),
						exports.filterClass.get(BulletType) (t, v)
				)
				t.table(cons(right = > {
						right.button("", Icon.trash, Styles.cleart, () = > {
								map.remove(k)
				if (t != null) t.remove()
					});
				})).right().growX().right();
			}))).row()
			}
				value = value || new IntObject()
				value.each(add)

				cont.button("$add", Icon.add, () = > add(exports.defaultClass.get(classes[0]), new IntObject())).fillX()

				return prov(() = > map)
			}
	);
				category =new

	Seq(),unitType =Seq.withArrays("none","flying","mech","legs","naval","payload");
	ObjectMap<String, Func2<Table, JsonValue, Prov<String>>>filterKey =ObjectMap.of(
			"category",(table,value)-> {
		if (!category.contains("" + value)) return null;
		let val = value || "distribution";
		let btn = table.button(val, Styles.cleart, () = > {
				IntFunc.showSelectListTable(btn, category, val, 130, 50, cons(cat = > btn.setText(val = cat)), false);
		}).size(130, 45).get();
		return prov(() = > val)
	},
			"type",(table,value,type)->

	{
		let val;
		if (type == UnitType) {
			val = value || "none";
			let btn = table.button(val, Styles.cleart, () -> {
					IntFunc.showSelectListTable(btn, unitType, val, 130, 50, cons(type -> btn.setText(val = type)), false)
			;
			}).size(130, 45).get();
		}

		return prov(() -> val)
	},
			"consumes",(table,value)->

	{
		value = value || new IntObject()
		let cont = table.table(Tex.button).get()
		let content = {
				power:(t, v) -> {
		let field = new TextField(v instanceof IntObject ? 0 : "" + v)
		t.add(field);
		return prov(() -> field.getText())
	}, item:
		(t, obj) -> {
			obj.put("items", fArray(t, Classes.get("ItemStack"), obj.getDefault("items",[])))
			t.row()
			t.table(cons(t -> {
					t.check(Core.bundle.get("ModMake.consumes-optional", "optional"), obj.getDefault("optional", false), boolc(b -> obj.put("optional", b)))
			t.check(Core.bundle.get("ModMake.consumes-booster", "booster"), obj.getDefault("booster", false), boolc(b -> obj.put("booster", b)))
				})).row()
			return obj
		}, liquid:
		(t, obj) -> {
			let p = exports.filterClass.get(LiquidStack) (t, obj)
			let v = p.get()
			t.row()
			t.table(cons(t -> {
					t.check(Core.bundle.get("ModMake.consumes-optional", "optional"), obj.getDefault("optional", false), boolc(b -> v.put("optional", b)))
			t.check(Core.bundle.get("ModMake.consumes-booster", "booster"), obj.getDefault("booster", false), boolc(b -> v.put("booster", b)))
				})).row()
			return v
		}
		}
		function consumer (name, displayName, key, obj){
		this.enable = obj != null
		obj = obj || new IntObject()
		this.name = name
		let table = new Table()
		let t = this.table = new Table()

		cont.check(displayName, this.enable, boolc(b -> this.setup(b))).row()
		cont.add(table).row()
		value.put(key, content[name] (t, obj));
		cont.row()
		this.setup = function(b) {
			if (this.enable = b) {
				table.add(this.table)
			} else this.table.remove()
		}
		this.setup(this.enable)
	}
		let power = new consumer("power", "power", "power", value.get("power"));
		let item = new consumer("item", "items", "items", value.get("items"));
		let liquid = new consumer("liquid", "liquids", "liquid", value.get("liquid"));

		return prov(() -> {
		if (!power.enable) value.remove("power")
		if (!item.enable) value.remove("items")
		if (!liquid.enable) value.remove("liquid")
		return value + ""
		})
	}
)

	exports.load =

	function() {
		for (let cat of Category.all)category.add("" + cat)
	}

	exports.make =

	function(type) {
		try {
			let cons = Seq([type]).get(0).getDeclaredConstructor();
			cons.setAccessible(true);
			return cons.newInstance();
		} catch (e) {
			Vars.ui.showErrorMessage(e);
		}
	}

	function fObject(t, type, value, typeBlackList) {
		let table = new Table(Tex.button), children = new Table,
				fields = new Fields.constructor(value, type, children);
		value = fields.map
		children.center().defaults().center().minWidth(100)
		table.add(children).row()
		t.add(table)
		value.each((k, v) -> {
			if (!(value[k] instanceof Function))
		/* try {
			if (add.filter(type.getField(k)))  */ fields.add(null, k)/* ;
		} catch(e) { continue } */
		})
		table.add(add.constructor(value, fields, type)).fillX().growX()
		return prov(() -> {
		if (!typeBlackList.contains(type.get())) value.put("type", type.get().getSimpleName())
		return value
	})
	}

	function addItem(type, fields, i, value) {
		let t = new Table;
		exports.build(type, fields, t, i, value, true)
		fields.add(t, i)
	}

	function fArray(t, vType, v) {
		let table = new Table, children = new Table,
		fields = new Fields.constructor(v || new IntArray(), prov(() -> vType), children)
		children.center().defaults().center().minWidth(100)
		table.add(children).name("cont").row()
		t.add(table)
		v = fields.map
		let len = v.length
		for (var j = 0; j < len; j++) {
			addItem(vType, fields, j, v[j])
		}
		table.button("$add", () -> {
				addItem(vType, fields, v.length, exports.defaultClass.get(vType) || new IntObject())
		}).fillX();
		return prov(() -> v);
	}

	function buildOneStack(t, type, stack, content, amount) {
		let output = new IntObject();

		t.add("$" + type);

		content = content || stack.get(0)
		output.put(type, IntFunc.selectionWithField(t, stack, content instanceof cont ? content.name : "" + content, 42, 32, 6, true))

		t.add("$amount");
		let atf = t.field("" + (amount | 0), cons(t -> {})).get();
		output.put("amount", prov(() -> atf.getText() | 0));

		return prov(() -> output);
	}

	public Class<?>[] getGenericType (Field field) {
		return ("class " + field.getGenericType())
				.replace("" + field.getType(), "").replace( /\<(. + ?)\>/,"$1")
		.split( /\,\s */).map(str -> Class.forName(str, false, Seq.class.getClassLoader()));
	}

	/* 构建table */
	public void build(type, fields, t, k, v, isArray) {
		void fail() {
			let field = new TextField("" + v);
			t.add(field);
			return () -> field.getText().replace( /\s */,"") !="" ? field.getText() : "\"\"";
		}
		let map = fields.map;

		if (!isArray) t.add(Core.bundle.get("content." + k, k) + ":").fillX().left().padLeft(2).padRight(6);

		let output = (() -> {

		if (type == null) return

		try {
			let field = isArray ? null : type.getField(k)
			let vType = isArray ? type : field.type
			if (vType == null || vType == lstr) {
				return
			}
			if (vType.isPrimitive()) {
				if (vType + "" == "boolean") {
					let btn = t.button("" + v, Styles.cleart, () -> btn.setText("" + (v = !v))).size(130, 45).get()
					return prov(() -> v)
				}
				return;
			}

			if ((vType.isArray() || vType == Seq) && v instanceof IntArray) {
				return fArray(t, vType == Seq ? this.getGenericType(field)[0] : vType.getComponentType(), v)
			}
			if (IntFunc.toClass(ObjectMap).isAssignableFrom(vType)) {
				let classes = this.getGenericType(field)
				return this.filterClass.get(vType)(t, v, classes)
			}
			if (this.filterClass.containsKey(vType)) {
				return this.filterClass.get(vType)(t, v)
			}
		} catch (e) {
			Log.info(type);
			Log.err(e);
		}
		finally {

			if (this.filterKey.containsKey(k)) {
				return this.filterKey.get(k)(t, v)
			}
		}
		return;

		/* else if (k == "ammoTypes") {
			v = v.toArray()
	
			let contents = Vars.content[type == "LiquidTurret" ? "liquids" : "items"]().toArray();
			let btn = t.button("$add", () -> IntFunc.showSelectImageTable(
				btn, contents, null, 40, 32, cons(item -> {
				v[item.name] = {}
			}), 6, true)).get();
	
			map.put(k, {
				toString() {
					t.clear();
					t.remove();
					return JSON.stringify(v)
				}
			})
		} */

	})();
		if (output == null) output = fail()
		map.put(k, output)


		t.table(cons(right -> {
				right.right().defaults().right()
		if (!isArray && Core.bundle.has(type.getSimpleName() + "_" + k + ".help")) {
			let btn = right.button("?", () -> IntFunc.showSelectTable(btn, (p, hide) -> {
				p.pane(p -> p.add(Core.bundle.get(type.getSimpleName() + "_" + k + ".help"))).width(400)
				p.button("ok", hide).fillX()
			}, false)).padLeft(2);
		}
		right.button("", Icon.trash, Styles.cleart, () -> fields.remove(t, k));
	})).right().growX().right();
	}

}
