-- Visibility (2026-09-22): a salted, truncated hash of the caller's IP on each
-- install's daily quota row, so the IP report can see many installs behind one IP.
-- Written by the same upsert that already bumps the row: no extra row writes.
ALTER TABLE quota ADD COLUMN ip TEXT;
