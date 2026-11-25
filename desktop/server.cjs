const loopback = new Set(["localhost", "127.0.0.1", "[::1]"]);

function serverOrigin(value = "http://localhost:3000") {
  const url = new URL(value);
  if (url.username || url.password || url.pathname !== "/" || url.search || url.hash
      || (url.protocol !== "https:" && !(url.protocol === "http:" && loopback.has(url.hostname)))) {
    throw new Error("Use an HTTPS Soleil server or a local HTTP address without a path.");
  }
  return url.origin;
}

function accountLink(value, origin) {
  const url = new URL(value.trim());
  const action = url.searchParams.get("action");
  const token = new URLSearchParams(url.hash.slice(1)).get("token");
  if (url.origin !== origin || url.username || url.password || url.pathname !== "/"
      || !["verify", "reset"].includes(action) || !/^[A-Za-z0-9_-]{24,160}$/.test(token ?? "")) {
    throw new Error("Copy the full verification or reset link from your Soleil email.");
  }
  return origin + "/?action=" + action + "#token=" + token;
}

module.exports = { serverOrigin, accountLink };
