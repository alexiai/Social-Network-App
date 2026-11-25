const { test } = require("node:test");
const assert = require("node:assert/strict");
const { serverOrigin, accountLink } = require("./server.cjs");

test("permits local HTTP and remote HTTPS origins", () => {
  assert.equal(serverOrigin(), "http://localhost:3000");
  assert.equal(serverOrigin("http://127.0.0.1:3001"), "http://127.0.0.1:3001");
  assert.equal(serverOrigin("https://soleil.example/"), "https://soleil.example");
  for (const url of ["http://soleil.example", "https://soleil.example/chats", "https://user@soleil.example", "file:///tmp/soleil"]) {
    assert.throws(() => serverOrigin(url));
  }
});

test("only opens valid Soleil verification and reset links on the configured origin", () => {
  const origin = "https://soleil.example";
  const token = "abcdef0123456789ABCDEF0123456789";
  for (const action of ["verify", "reset"]) {
    const link = origin + "/?action=" + action + "#token=" + token;
    assert.equal(accountLink("  " + link, origin), link);
    assert.throws(() => accountLink(link.replace("soleil.example", "other.example"), origin));
  }
  assert.throws(() => accountLink(origin + "/?action=verify#token=x", origin));
  assert.throws(() => accountLink(origin + "/?action=other#token=" + token, origin));
});
