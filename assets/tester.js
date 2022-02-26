const IntFunc = (() => {
	function isOk(types, args){
		if (types.length != args.length) return false;
		for (let i = 0; i < args.length; i++) {
			if (types[i] != args)  return false;
		}
		return true;
	}
	let func = java.lang.Class.forName('modmake.ui.IntFunc', false, Vars.mods.mainLoader())
	let methods = func.getMethods();
	let obj = {}

	for (let i = 0; i < methods.length; i++) {
		let method = methods[i];

		let name = method.getName()
		let f = obj[name] = function (){
			let err = [], args = Array.from(arguments);
			/*for (let method of f.all) {
				if (isOk(method.getType(), args))*/
				return method.invoke.apply(method, [null].concat(args));
			/*}
			return "ArgumentsError: 无法找到相应的方法";*/
		}
		if (f.all == null) f.all = [];
		f.all.push(method);
		f.toString = function(){
			return f.all.join("\n");
		}
	}
	return obj;
})();

const forIn = function(obj) {
	let str = []
	for (let k in obj) {
		try {
			str.push(k + ": " + obj[k])
		} catch(e) {
			Log.err(e)
		}
	}
	return str.join("\n")
}