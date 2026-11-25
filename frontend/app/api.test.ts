import { afterEach, describe, expect, it, vi } from "vitest";
import { api, query, refresh } from "./api";

afterEach(() => vi.unstubAllGlobals());

describe("Campus HTTP client", () => {
  it("encodes searches and preserves false and zero", () => {
    const params = new URLSearchParams(query({ q: "book & coffee", page: 0, popular: false, skip: null }));
    expect(params.get("q")).toBe("book & coffee");
    expect(params.get("page")).toBe("0");
    expect(params.get("popular")).toBe("false");
    expect(params.has("skip")).toBe(false);
  });

  it("returns field validation errors", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ fields: ["body: must not be blank"] }, { status: 400 })));
    await expect(api("/posts", "POST", { body: "" }, "token")).rejects.toMatchObject({ status: 400, message: "body: must not be blank" });
  });

  it("shares concurrent refresh requests", async () => {
    const fetch = vi.fn().mockResolvedValue(Response.json({ username: "alex", accessToken: "token" }));
    vi.stubGlobal("fetch", fetch);
    await Promise.all([refresh(), refresh()]);
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});
