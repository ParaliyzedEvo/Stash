# Stash Lossless Relay (Cloudflare Worker)

Mints short-lived Qobuz FLAC URLs for the Stash app from a small pool of
operator-owned Qobuz accounts. The device streams from Qobuz's CDN directly;
**no audio ever transits this Worker.** Contract and design:
[`../../docs/superpowers/specs/2026-08-31-lossless-relay-plan-b-contract.md`](../../docs/superpowers/specs/2026-08-31-lossless-relay-plan-b-contract.md).

Sized as a bridge, not the main path: the durable lossless path is the user's
own Qobuz account (free to us, no ceiling). Four accounts serve roughly 65 daily
actives thanks to the shared mint cache; see spec §6.

## Endpoints

