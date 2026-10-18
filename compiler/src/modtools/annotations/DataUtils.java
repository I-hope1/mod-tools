package modtools.annotations;

import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.Context;

import java.util.Arrays;

import static modtools.annotations.BaseProcessor.*;

public interface DataUtils extends NameString {
	default JCFieldAccess selfData(String typeName) {
		return mMaker.Select(mMaker.Ident(ns(typeName)), ns("data"));
	}
	/** 符号缓存必须跟着 {@link Context} 走，不能跨编译轮次复用 */
	final class Symbols {
		static Context           owner;
		static final ClassSymbol[] values = {
		 null/* MySettings */,
		 null/* SettingUI */,
		 null/* Data */
		};
	}

	static void resetSymbols() {
		Symbols.owner = null;
		Arrays.fill(Symbols.values, null);
	}
	default ClassSymbol C_MySettings() {
		return checkAndSet(0, "modtools.utils.MySettings");
	}
	default ClassSymbol SettingUI() {
		return checkAndSet(1, "modtools.content.SettingsUI");
	}
	default ClassSymbol C_Data() {
		return checkAndSet(2, "modtools.utils.MySettings$Data");
	}

	private ClassSymbol checkAndSet(int i, String name) {
		if (Symbols.owner != _context) {
			Symbols.owner = _context;
			Arrays.fill(Symbols.values, null);
		}
		if (Symbols.values[i] == null)
			Symbols.values[i] = mSymtab.getClass(mSymtab.unnamedModule, ns(name));
		return Symbols.values[i];
	}
	default JCFieldAccess internalData(String key) {
		return mMaker.Select(mMaker.Ident(C_MySettings()), ns(key));
	}
	default JCExpression getData(String data, String typeName) {
		return data.isEmpty() ? selfData(typeName) : internalData(data);
	}

}
