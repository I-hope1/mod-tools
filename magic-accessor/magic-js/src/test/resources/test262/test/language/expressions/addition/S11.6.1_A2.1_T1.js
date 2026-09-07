/*---
info: Operator x + y returns ToNumber(x) + ToNumber(y)
es5id: 11.6.1_A2.1_T1
description: Checking Boolean, Number and Null
includes: [assert.js, sta.js]
---*/

assert.sameValue(1 + 1, 2, "1 + 1 === 2");
assert.sameValue(1 + -1, 0, "1 + -1 === 0");
assert.sameValue(-1 + -1, -2, "-1 + -1 === -2");
assert.sameValue(0 + 0, 0, "0 + 0 === 0");
