-- This Source Code Form is subject to the terms of the Mozilla Public
-- License, v. 2.0. If a copy of the MPL was not distributed with this
-- file, You can obtain one at https://mozilla.org/MPL/2.0/.
-- -------------------------------------------------------------------------------------------------
-- Database Name: inji_certify
-- Table Name : status_list_allocation
-- Purpose    : One row per status list. Tracks how many positions have been allocated and whether the
--              list still accepts new allocations. Replaces the per-issuance count query.
-- -------------------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS status_list_allocation (
    status_list_credential_id VARCHAR(255) PRIMARY KEY,   -- References status_list_credential.id
    issuer_id VARCHAR(255) NOT NULL,                      -- Issuer (DID) that signs the list
    status_purpose VARCHAR(100) NOT NULL,                 -- e.g. 'revocation'
    physical_capacity BIGINT NOT NULL,                    -- Positions in the list (524288 for 64 KiB)
    allocation_limit BIGINT NOT NULL,                     -- Maximum committed allocations (262144 at 50%)
    allocated_count BIGINT NOT NULL DEFAULT 0,            -- Incremented once per committed allocation
    is_active BOOLEAN NOT NULL DEFAULT TRUE,              -- Whether the list accepts new allocations
    cr_dtimes TIMESTAMP NOT NULL DEFAULT NOW(),
    upd_dtimes TIMESTAMP,
    CONSTRAINT fk_sla_alloc_status_list
        FOREIGN KEY (status_list_credential_id)
        REFERENCES status_list_credential(id)
        ON DELETE CASCADE,
    CONSTRAINT ck_sla_alloc_counts
        CHECK (allocated_count >= 0
               AND allocated_count <= allocation_limit
               AND allocation_limit <= physical_capacity)
);

-- At most one active list per issuer and purpose.
CREATE UNIQUE INDEX IF NOT EXISTS uq_status_list_allocation_active
    ON status_list_allocation (issuer_id, status_purpose)
    WHERE is_active;
