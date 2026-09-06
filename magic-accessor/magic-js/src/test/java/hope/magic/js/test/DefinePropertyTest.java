package hope.magic.js.test;

import hope.magic.js.runtime.*;
import org.junit.jupiter.api.*;

import java.util.List;

public class DefinePropertyTest {

	@Test
	public void testBasicGetterAndSetter() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
		 """
			let obj = { _val: 10 };
			Object.defineProperty(obj, 'val', {
			    get: function() { return this._val * 2; },
			    set: function(v) { this._val = v; },
			    enumerable: true,
			    configurable: true
			});
			let before = obj.val;
			obj.val = 25;
			let after = obj.val;
			let raw = obj._val;
			[before, after, raw];
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals(20.0, arr.getElement(0));
		Assertions.assertEquals(50.0, arr.getElement(1));
		Assertions.assertEquals(25.0, arr.getElement(2));
	}

	@Test
	public void testGetterOnlyAndSetterOnly() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
		 """
			let obj = { _x: 100 };
			Object.defineProperty(obj, 'readonly', {
			    get: function() { return this._x; }
			});
			let v1 = obj.readonly;
			obj.readonly = 999; // 无 setter，非严格模式下静默忽略
			let v2 = obj.readonly;
			[v1, v2];
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals(100.0, arr.getElement(0));
		Assertions.assertEquals(100.0, arr.getElement(1));
	}

	@Test
	public void testPrototypeChainAccessorWithReceiver() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
		 """
			let proto = { _multiplier: 3 };
			Object.defineProperty(proto, 'computed', {
			    get: function() { return this.base * this._multiplier; },
			    set: function(v) { this.base = v + 1; },
			    enumerable: true,
			    configurable: true
			});

			let item = Object.create(proto);
			item.base = 5;
			item._multiplier = 10;

			let v1 = item.computed; // 5 * 10 = 50 (this 绑定到 item)
			item.computed = 20;     // item.base = 21
			let v2 = item.computed; // 21 * 10 = 210
			let ownProps = Object.getOwnPropertyNames(item);
			let hasComputedOwn = item.hasOwnProperty('computed');
			[v1, v2, item.base, hasComputedOwn];
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals(50.0, arr.getElement(0));
		Assertions.assertEquals(210.0, arr.getElement(1));
		Assertions.assertEquals(21.0, arr.getElement(2));
		Assertions.assertEquals(Boolean.FALSE, arr.getElement(3));
	}

	@Test
	public void testDataPropertyAttributes() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
		 """
			let obj = {};
			Object.defineProperty(obj, 'ro', {
			    value: 42,
			    writable: false,
			    enumerable: true,
			    configurable: true
			});
			let before = obj.ro;
			obj.ro = 999; // writable: false，写入静默忽略
			let after = obj.ro;
			[before, after];
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals(42.0, arr.getElement(0));
		Assertions.assertEquals(42.0, arr.getElement(1));
	}

	@Test
	public void testEnumerableAttribute() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
		 """
			let obj = { a: 1, b: 2 };
			Object.defineProperty(obj, 'hidden', {
			    value: 3,
			    enumerable: false,
			    configurable: true
			});
			let keys = Object.keys(obj);
			let allNames = Object.getOwnPropertyNames(obj);
			let hiddenVal = obj.hidden;
			[keys.length, allNames.length, hiddenVal];
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals(2.0, arr.getElement(0)); // 只有 a, b
		Assertions.assertEquals(3.0, arr.getElement(1)); // a, b, hidden
		Assertions.assertEquals(3.0, arr.getElement(2)); // hidden 正常可读
	}

	@Test
	public void testConfigurableAttributeAndDeletion() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
		 """
			let obj = {};
			Object.defineProperty(obj, 'locked', {
			    value: 123,
			    configurable: false
			});
			delete obj.locked; // configurable: false，禁止删除
			let val = obj.locked;
			let exists = obj.hasOwnProperty('locked');
			[val, exists];
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals(123.0, arr.getElement(0));
		Assertions.assertEquals(Boolean.TRUE, arr.getElement(1));
	}

	@Test
	public void testCannotRedefineNonConfigurable() {
		JSContext cx = new JSContext();
		Assertions.assertThrows(RuntimeException.class, () -> {
			cx.eval(
			 """
				let obj = {};
				Object.defineProperty(obj, 'fixed', {
				    value: 1,
				    configurable: false
				});
				// 尝试把 configurable 改为 true 或变更值
				Object.defineProperty(obj, 'fixed', {
				    configurable: true
				});
				"""
			);
		});
	}

	@Test
	public void testInvalidDescriptorValidation() {
		JSContext cx = new JSContext();
		// 同时包含 value 和 get
		Assertions.assertThrows(RuntimeException.class, () -> {
			cx.eval("Object.defineProperty({}, 'bad', { value: 1, get: function() {} });");
		});

		// getter 不是函数
		Assertions.assertThrows(RuntimeException.class, () -> {
			cx.eval("Object.defineProperty({}, 'bad', { get: 123 });");
		});

		// 目标不是对象
		Assertions.assertThrows(RuntimeException.class, () -> {
			cx.eval("Object.defineProperty(123, 'bad', {});");
		});
	}

	@Test
	public void testGetOwnPropertyDescriptor() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
		 """
			let obj = {};
			Object.defineProperty(obj, 'x', {
			    value: 'hello',
			    writable: false,
			    enumerable: false,
			    configurable: true
			});
			let desc = Object.getOwnPropertyDescriptor(obj, 'x');
			[desc.value, desc.writable, desc.enumerable, desc.configurable];
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals("hello", arr.getElement(0));
		Assertions.assertEquals(Boolean.FALSE, arr.getElement(1));
		Assertions.assertEquals(Boolean.FALSE, arr.getElement(2));
		Assertions.assertEquals(Boolean.TRUE, arr.getElement(3));
	}

	@Test
	public void testGetOwnPropertyDescriptorForAccessor() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
		 """
			let fn = function() { return 99; };
			let obj = {};
			Object.defineProperty(obj, 'acc', {
			    get: fn,
			    enumerable: true,
			    configurable: false
			});
			let desc = Object.getOwnPropertyDescriptor(obj, 'acc');
			[desc.get === fn, desc.set, desc.enumerable, desc.configurable];
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals(Boolean.TRUE, arr.getElement(0));
		Assertions.assertEquals(JSUndefined.INSTANCE, arr.getElement(1));
		Assertions.assertEquals(Boolean.TRUE, arr.getElement(2));
		Assertions.assertEquals(Boolean.FALSE, arr.getElement(3));
	}

	@Test
	public void testDefinePropertiesAndGetOwnPropertyDescriptors() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
		 """
			let obj = {};
			Object.defineProperties(obj, {
			    foo: { value: 10, writable: true, enumerable: true },
			    bar: { get: function() { return 20; }, enumerable: false }
			});
			let descriptors = Object.getOwnPropertyDescriptors(obj);
			[obj.foo, obj.bar, descriptors.foo.writable, descriptors.bar.enumerable];
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals(10.0, arr.getElement(0));
		Assertions.assertEquals(20.0, arr.getElement(1));
		Assertions.assertEquals(Boolean.TRUE, arr.getElement(2));
		Assertions.assertEquals(Boolean.FALSE, arr.getElement(3));
	}

	@Test
	public void testVueStyleReactivitySimulation() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
		 """
			function defineReactive(obj, key, val) {
			    obj._count = val;
			    Object.defineProperty(obj, key, {
			        enumerable: true,
			        configurable: true,
			        get: function() {
			            return this._count;
			        },
			        set: function(newVal) {
			            if (newVal !== this._count) {
				            this._count = newVal * 10;
			            }
			        }
			    });
			}

			let state = {};
			defineReactive(state, 'count', 1);
			let v1 = state.count;
			state.count = 5;
			let v2 = state.count;
			[v1, v2];
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals(1.0, arr.getElement(0));
		Assertions.assertEquals(50.0, arr.getElement(1));
	}

	@Test
	public void testNormalPropertyICRemainsFastAndZeroCost() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
		 """
			let sum = 0;
			for (let i = 0; i < 10000; i++) {
			    let pt = { x: i, y: i * 2 };
			    sum += pt.x + pt.y;
			}
			sum;
			"""
		);
		Assertions.assertEquals(149985000.0, res);
	}
}
