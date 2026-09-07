function Test262Error(message) {
  if (message) this.message = message;
}

Test262Error.prototype.name = "Test262Error";

Test262Error.prototype.toString = function () {
  return "Test262Error: " + (this.message || "");
};
