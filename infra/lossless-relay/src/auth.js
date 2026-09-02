import { createHmac, timingSafeEqual } from "node:crypto";

/** Clock skew tolerated on X-Stash-Ts, in seconds (spec §5.4: ±5 min). */
export const MAX_SKEW_S = 300;

/**
 * Verifies the signature the client attaches when the signed config carries a
 * `relay_key` (spec §5.4):
 *   X-Stash-Auth = hex( HMAC-SHA256( key, "<install>:<track_id>:<format_id>:<ts>" ) )
 * `keys` holds the current key and, during a rotation, the previous one; blanks are skipped.
 * Pure: header values as strings, server clock in unix seconds. Never throws.
 */
export function verifyMint({ install, ts, auth } = {}, trackId, formatId, nowSec, keys) {
    if (!/^[A-Za-z0-9-]{8,64}$/.test(install || "")) return false;
    if (!/^\d{1,12}$/.test(ts || "")) return false;
    if (Math.abs(nowSec - Number(ts)) > MAX_SKEW_S) return false;
    if (!/^[0-9a-f]{64}$/.test(auth || "")) return false;
    const got = Buffer.from(auth, "hex");
    const msg = `${install}:${trackId}:${formatId}:${ts}`;
    return keys.filter(Boolean).some((k) => timingSafeEqual(createHmac("sha256", k).update(msg).digest(), got));
}
