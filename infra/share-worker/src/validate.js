/** Spec §3 limits. Returns an error string, or null when the document is valid. */
export const MAX_BODY_BYTES = 1_000_000;
export const MAX_TRACKS = 2000;

const str = (v, min, max) => typeof v === "string" && v.trim().length >= min && v.length <= max;
const optStr = (v, max) => v === undefined || v === null || (typeof v === "string" && v.length <= max);

export function validateDoc(doc) {
    if (!doc || typeof doc !== "object") return "doc missing";
    if (doc.v !== 1) return "unsupported v";
    if (!str(doc.name, 1, 100)) return "bad name";
    if (!optStr(doc.sharedBy, 40)) return "bad sharedBy";
    if (doc.covers !== undefined) {
        if (!Array.isArray(doc.covers) || doc.covers.length > 4) return "bad covers";
        if (!doc.covers.every((c) => typeof c === "string" && c.startsWith("https://") && c.length <= 1000)) return "bad cover url";
    }
    if (!Array.isArray(doc.tracks) || doc.tracks.length < 1 || doc.tracks.length > MAX_TRACKS) return "bad tracks";
    for (const t of doc.tracks) {
        if (!t || !str(t.t, 1, 500) || !str(t.a, 1, 500)) return "bad track";
        if (!optStr(t.al, 500) || !optStr(t.isrc, 20) || !optStr(t.sp, 40) || !optStr(t.yt, 20)) return "bad track field";
        if (t.d !== undefined && t.d !== null && !(Number.isInteger(t.d) && t.d > 0)) return "bad duration";
    }
    return null;
}

/** base64url of 32 random bytes = 43 chars. */
export const validEditKey = (k) => typeof k === "string" && /^[A-Za-z0-9_-]{43}$/.test(k);
