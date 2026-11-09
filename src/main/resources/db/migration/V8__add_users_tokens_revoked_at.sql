-- v2.1.1 (ADR 0024, security review finding SEC-5): disabling a user revokes their tokens for good.
-- New migration only - V1..V7 are never edited.

-- Tokens issued before this moment are rejected. Set when an enabled account is disabled, so re-enabling it
-- does not bring back tokens issued before the disable. NULL = never disabled.
ALTER TABLE users ADD COLUMN tokens_revoked_at TIMESTAMPTZ NULL;
