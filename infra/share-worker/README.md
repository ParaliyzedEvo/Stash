# stash-share

Stores shared Stash mixes and serves their links. See `docs/superpowers/specs/2026-09-23-shared-mixes-design.md`.

- KV `SHARE_KV`: `mix:<id>` → `{ doc, keyHash, deleted }`. Deleted mixes keep a `{deleted:true}` tombstone for 180 days.
- Rate limits: `CREATE_RL` (5/min per IP), `WRITE_RL` (30/min per IP).
- Routes: `POST /v1/mixes`, `PUT|DELETE|GET /v1/mixes/{id}`, `GET /v1/mixes/{id}/version`, `GET /m/{id}`, `GET /t`, `GET /.well-known/assetlinks.json`.

## Deploy

```bash
cd infra/share-worker
npx wrangler kv namespace create SHARE_KV   # paste the id into wrangler.toml
npm test
npx wrangler deploy
```

## Remove a mix by hand

Write a tombstone rather than deleting the key. Followers only stop following on a 410; a bare 404 is treated as a temporary miss.

```bash
npx wrangler kv key put --binding SHARE_KV --remote --ttl 15552000 "mix:<id>" '{"deleted":true}'
```

## Moving to a custom domain later

Add the domain as a Worker route or custom domain, add its host to `ShareConfig.HOSTS` in the app and to the manifest intent filter, and keep the old host working. `assetlinks.json` is served on every host automatically.
