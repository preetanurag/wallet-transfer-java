-- Existing transfer rows remain valid; forward transfers have no reversal_of.
ALTER TABLE transfers ADD COLUMN reversal_of UUID REFERENCES transfers(id);
ALTER TABLE transfers ADD CONSTRAINT reversal_not_self CHECK (reversal_of IS NULL OR reversal_of <> id);

-- Declined attempts do not consume the refund entitlement. Across all users and
-- keys, the database allows at most one successful reversal per original.
CREATE UNIQUE INDEX one_successful_reversal_per_transfer
    ON transfers(reversal_of) WHERE reversal_of IS NOT NULL AND status = 'succeeded';
