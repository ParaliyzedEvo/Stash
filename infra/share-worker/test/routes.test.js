import { test } from "node:test";
import assert from "node:assert/strict";
import { handle } from "../src/index.js";
import { env } from "./fake-kv.js";

const BASE = "https://share.test";

test("unknown path is 404, wrong method is 405", async () => {
    assert.equal((await handle(new Request(`${BASE}/nope`), env())).status, 404);
    assert.equal((await handle(new Request(`${BASE}/v1/mixes/abcdefgh`, { method: "PATCH" }), env())).status, 405);
});
