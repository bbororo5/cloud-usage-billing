-- Local identities, no grants to raw data or the internal deduplication view.
create user if not exists billing_bff identified with sha256_password by 'local-bff-only';
create user if not exists billing_batch identified with sha256_password by 'local-batch-only';
-- Tenant read models and grants are intentionally deferred to the attribution stage.
