package modtools.world;

import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.graphics.Drawf;
import mindustry.type.Item;
import mindustry.world.Block;

import static mindustry.Vars.tilesize;
import static mindustry.Vars.world;

public class ItemSourceNode extends Block {
	public ItemSourceNode(String name) {
		super(name);

		update = true;
		configurable = true;

		buildType = ItemSourceNodeBuild::new;

		config(Item.class, (ItemSourceNodeBuild build, Item item) -> {
			if (build.outputItems.contains(item)) {
				build.outputItems.remove(item);
			} else {
				build.outputItems.add(item);
			}
		});
		config(Integer.class, (ItemSourceNodeBuild build, Integer pos) -> {
			Building other = world.build(pos);
			if (other != null) {
				if (build.links.contains(other)) {
					build.links.remove(other);
				} else {
					build.links.add(other);
				}
			}
		});
		config(ButtonType.class, (ItemSourceNodeBuild build, ButtonType type) -> {
			if (type == ButtonType.all) {
				build.outputItems.clear();
				build.outputItems.addAll(Vars.content.items());
			}
			if (type == ButtonType.none) {
				build.outputItems.clear();
			}
		});
		config(Boolean.class, (ItemSourceNodeBuild build, Boolean bool) -> {
			build.forceAdd = bool;
		});
	}

	public static class ItemSourceNodeBuild extends Building {
		Seq<Building> links = new Seq<>();
		Seq<Item> outputItems = new Seq<>();
		boolean forceAdd = false;

		@Override
		public boolean onConfigureBuildTapped(Building other) {
			if (other == null) return true;
			configure(other.pos());
			return false;
		}

		@Override
		public void buildConfiguration(Table table) {
			table.table(top -> {
				top.button("全选", () -> {
					configure(ButtonType.all);
				}).growX();
				top.button("全不选", () -> {
					configure(ButtonType.none);
				}).growX().row();
//				top.check("强制添加", forceAdd, this::configure).growX();
			}).growX().row();
			table.table(t -> ItemSelection.buildSelection(t, Vars.content.items(), this::configure, outputItems::contains));
		}

		@Override
		public void updateTile() {
			links.removeAll(b -> b.tile.build != b);

			links.each(b -> {
				if (b == null) return;

				outputItems.each(item -> {
					try {
						if (forceAdd) {
							b.items.set(item, Integer.MAX_VALUE);
						} else {
							if (b.acceptItem(this, item)) b.handleItem(this, item);
							b.handleStack(item, b.acceptStack(item, Integer.MAX_VALUE, this), this);
						}
					} catch (Throwable ignored) {}
				});
			});
		}

		public void write(Writes write) {
			super.write(write);
			write.i(links.size);

			links.each(link -> write.i(link.pos()));
		}

		public void read(Reads read, byte revision) {
			super.read(read, revision);
			int size = read.i();
			links.clear();
			for (int i = 0; i < size; i++) {
				links.add(world.build(read.i()));
			}
		}

		public void drawConfigure() {
			links.each(b -> {
				Drawf.square(b.x, b.y, b.block.size * tilesize);
			});
		}
	}

	enum ButtonType {
		all, none
	}
}
