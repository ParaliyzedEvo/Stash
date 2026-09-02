import { test } from "node:test";
import assert from "node:assert/strict";
import { verifyMint } from "../src/auth.js";

const H = {
    install: "0f7c3a2e-install",
    ts: "1700000000",
    auth: "fc6ad1b240aa21efc87e1088841bd3b2eac9bb5139d855de9dde6e0729bb0532",
};

test("accepts the client's HMAC vector", () => {
    assert.equal(verifyMint(H, 42, 27, 1700000000, ["k-secret"]), true);
});

test("accepts the previous key during a rotation; blanks are skipped", () => {
    assert.equal(verifyMint(H, 42, 27, 1700000000, ["new-key", "k-secret"]), true);
    assert.equal(verifyMint(H, 42, 27, 1700000000, ["new-key", undefined]), false);
});

test("rejects a wrong key, a tampered track id, and a tampered install id", () => {
    assert.equal(verifyMint(H, 42, 27, 1700000000, ["other"]), false);
    assert.equal(verifyMint(H, 43, 27, 1700000000, ["k-secret"]), false);
    assert.equal(verifyMint({ ...H, install: "someone-else-1" }, 42, 27, 1700000000, ["k-secret"]), false);
});

test("rejects a timestamp outside ±300 s in either direction", () => {
    assert.equal(verifyMint(H, 42, 27, 1700000000 + 300, ["k-secret"]), true);
    assert.equal(verifyMint(H, 42, 27, 1700000000 + 301, ["k-secret"]), false);
    assert.equal(verifyMint(H, 42, 27, 1700000000 - 301, ["k-secret"]), false);
});

test("rejects malformed or missing headers without throwing", () => {
    assert.equal(verifyMint({ install: "", ts: "x", auth: "zz" }, 42, 27, 1700000000, ["k-secret"]), false);
    assert.equal(verifyMint({}, 42, 27, 1700000000, ["k-secret"]), false);
    assert.equal(verifyMint({ ...H, auth: H.auth.slice(0, 63) }, 42, 27, 1700000000, ["k-secret"]), false);
});
