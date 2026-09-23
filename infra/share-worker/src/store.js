const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
const TOMBSTONE_TTL_S = 180 * 24 * 3600;
const kvKey = (id) => `mix:${id}`;

export function newId() {
    const bytes = crypto.getRandomValues(new Uint8Array(8));
    return Array.from(bytes, (b) => ALPHABET[b % 62]).join("");
}

export async function sha256Hex(s) {
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
    return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

/** Constant-time comparison of two equal-length hex strings. */
export function sameHex(a, b) {
    if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
    let diff = 0;
    for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
    return diff === 0;
}

export const readMix = (kv, id) => kv.get(kvKey(id), "json");
export const writeMix = (kv, id, record) => kv.put(kvKey(id), JSON.stringify(record));
export const writeTombstone = (kv, id) =>
    kv.put(kvKey(id), JSON.stringify({ deleted: true }), { expirationTtl: TOMBSTONE_TTL_S });

/** A fresh id that isn't taken (collisions are ~impossible, but retry anyway). */
export async function freeId(kv) {
    for (let i = 0; i < 5; i++) {
        const id = newId();
        if ((await readMix(kv, id)) === null) return id;
    }
    throw new Error("could not allocate an id");
}
