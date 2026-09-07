/*---
description: Test262 negative test expecting TypeError at runtime phase
negative:
  phase: runtime
  type: TypeError
---*/

var n = null;
n.foo();
