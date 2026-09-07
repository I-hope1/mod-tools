/*---
info: If Type(Primitive(x)) is String or Type(Primitive(y)) is String, then operator x + y returns ToString(x) followed by ToString(y)
es5id: 11.6.1_A3.1_T1
description: String concatenation
includes: [assert.js, sta.js]
---*/

assert.sameValue("1" + "1", "11", "'1' + '1' === '11'");
assert.sameValue("x" + 1, "x1", "'x' + 1 === 'x1'");
assert.sameValue(1 + "y", "1y", "1 + 'y' === '1y'");
