import { test } from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";

test("worker module exports a fetch handler", () => {
    assert.equal(typeof worker.fetch, "function");
});
