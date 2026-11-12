# ERL status lists, revocation, and service authentication

## Implementation status (2026-11-09)

Built and verified on the `status_list` branch: sections 2 to 6 except the scale tooling in 6.8. Verified by 667 reactor tests (PostgreSQL 16 Testcontainers for allocation, publication and search, not skipped) and by `preauth/exercise_management.py` against a real, freshly provisioned lab: 66 checks, including revocation visible in 29 s (by credential id) and 18 s (by list position) against the 90 s target, a stale list returning 503, and a real rollover (last allowed licence closes the list, the replacement is created with all 524,288 positions, the next licence lands on it).

Not built: `kit/preauth/performance/seed_ledger.py` and `management_load.py`, and therefore none of the section 5 capacity benchmarks (16M and 160M row datasets, 5 issuances per second, 20 searches per second, query plans at both sizes). The one query plan checked is on a 60,000-row table (document-id search uses `idx_ledger_document_page`, no sort). Treat the capacity gate as still open.

Where the build differs from the text below:

- **H2 `schema.sql`** needs no change: it has no status list, ledger or transaction tables, and nothing touches them under H2. PostgreSQL behaviour is tested on PostgreSQL.
- **FULL flag.** `status_list_credential.credential_status` is set to FULL when the replacement list is created (in its creation transaction), not at the last allocation. The claim transaction must not take that row's lock, which the publisher holds while signing. Allocation state is authoritative in `status_list_allocation`.
- **Position lookup index.** The existing unique constraint on `(status_list_credential_id, list_index)` already serves the v2 issued-position lookup, so no extra index was added.
- **No upgrade script.** Nothing is deployed yet, so the schema changes live only in the fresh DDL (`db_scripts/inji_certify/ddl/`). Add a versioned upgrade and rollback pair once a first deployment exists.
- **ShedLock defaults** changed from `lock-at-most-for` 50 minutes to 55 s (lease below the default one-minute interval); the ERL overlay uses 25 s and 28 s for the 30 s schedule.
- **Metrics** (pending count, oldest pending age, publication duration and failures) are registered; the lab does not expose them. Allocation contention is not measured (dropped with the 1,000/s target).
- **Compose and Docker.** `docker-compose/.../certify_init.sql` is synchronised with `db_scripts` and its `capacity` column corrected to `capacity_in_kb`. Testcontainers needs `docker-java.properties` (`api.version=1.44`) with Docker Engine 29.
- **Breaking for existing deployments.** The service refuses to start with the ledger or status purposes in use and no management authentication. `application-local.properties` and the Compose configuration set `allow-unauthenticated=true` so local and demo setups keep working; Helm or other deployments must either configure `mosip.certify.management.auth.*` or set the override knowingly.
- **Status list proof context (existing behaviour, not changed).** Certify's signer includes the Ed25519Signature2020 suite context in the proof options although a status list's own `@context` lists only the VC v2 context, so a verifier that reads the document's context alone rejects a correctly signed list. `exercise_management.py` verifies it with the suite context added and reports that. Adding the suite context to new lists would make strict verifiers work; that changes published documents and was left as a decision.

## 1. Targets and status-list sizing

Implement for the confirmed requirements:

- **16 million newly issued VCs per year.**
- Peak **5 issuances per second**, **2 revocations per second** and about **20 searches per second** (revised on 2026-11-09 from 1,000, 50 and 100).
- Allocate **one position per issuance**, without batch reservations.
- Stop allocating to a list at **50% capacity** and create its replacement.
- Make accepted revocations visible to online verifiers within **90 seconds** (revised from 60), under healthy operating conditions.

**Recommend 64 KiB per status list**, measured before compression:

| List size | Total positions | Allocations at 50% | Lists for 16 million VCs |
|---|---:|---:|---:|
| 16 KiB | 131,072 | 65,536 | 245 |
| **64 KiB** | **524,288** | **262,144** | **62** |
| 128 KiB | 1,048,576 | 524,288 | 31 |

64 KiB balances download size and list count. This exceeds the W3C minimum of 16 KiB. The published representation is compressed, so these figures are not HTTP download sizes. [W3C specification](https://www.w3.org/TR/vc-bitstring-status-list/)

At 5 issuances per second, a 64 KiB list (262,144 usable positions) lasts about 14.6 hours at peak. At the 16 million per year average (about 0.5 per second), it lasts about six days.

Use a **ten-year capacity forecast**: 160 million issuances require approximately **611 lists**, without annual resets. Failed issuance allocations can increase this slightly. Keep older lists accessible and updatable after rollover; do not recycle their indexes.

## 2. Activate status lists and make allocation scalable

**Configuration and credential contents**

Keep reusable Certify settings in its ERL profile; keep local addresses, secrets, fixtures, and the Python IDP in the DMT test kit.

Set:

```properties
mosip.certify.issuer.ledger-enabled=true
mosip.certify.statuslist.size-in-kb=64
mosip.certify.statuslist.usable-capacity-percentage=50
mosip.certify.data-provider-plugin.credential-status.allowed-status-purposes={'revocation'}
```

- Retain the ERL VCDM 2.0 configuration. Enable `credentialStatusPurposes=["revocation"]`, which is not in the current ERL credential configuration; section 6.3 has the matching change.
- Update the ERL template to include the generated **VC `id` and `credentialStatus`**. They are currently missing from the template. Regenerate its encoded configuration consistently.
- Configure status-list signing with the ERL signing key and publicly resolvable issuer verification method.
- Configure indexed-attribute extraction per credential type, retaining the existing global mappings as the fallback for types without a scoped configuration. For ERL, map the printed `license_no` into a common `document_id` and retain `vehicle_no`, and `issued_date`. Configure searchable fields separately from stored metadata: ERL permits `document_id` and `vehicle_no`; `issued_date` remains metadata. Other document types define their own source-ID mapping and additional searchable fields without changes to the search controller.
- Save successful issuance metadata after signing and before returning the VC, so signing failures do not leave apparently issued ledger entries.

**Allocation**

Retain the existing prepopulated `status_list_available_indices` table and its random available-row selection using `ORDER BY RANDOM()` with `FOR UPDATE SKIP LOCKED`. Add **one new `status_list_allocation` table**, with one row per list, for the persisted count, allocation limit, issuer/purpose, and active status.

Decision: keep the existing random-selection logic for this iteration. The discussed shuffle-once/allocation-order optimization and random-candidate retry approach are deferred; neither is part of the implementation scope.

Concurrency scope: the existing position-row locks already protect individual position claims. The additional fixes below address the separate capacity-check and list-creation races; they do not replace that existing protection. See the concurrency note and acceptance scenarios in section 6.4.

- Replace the per-issuance count query with a conditional counter increment that cannot exceed the stored limit.
- Select and mark an available position first, then increment the counter in the same transaction. Acquire the shared counter lock only for the final update/commit, rather than holding it during the expensive random selection.
- If the counter increment fails because the list has closed, roll back the selected position and use the current active list. Database errors and temporarily locked/unavailable positions must not be treated as proof that the list is full.
- Serialize initial creation and rollover, and enforce at most one active allocation row per issuer/purpose. Allocation **262,144** closes the list; allocation **262,145** uses its replacement.
- Keep credential signing outside the allocation transaction. A committed allocation is never reused after issuance failure.

At 5 issuances per second the counter row is updated five times a second, so the single-row contention concern does not apply and the counter design is kept. This retains approximately **32 million position rows per year** at 50% utilization, plus partial-list rounding, and retains the cost of `ORDER BY RANDOM()`. A random-start range scan may replace `ORDER BY RANDOM()` only if one benchmarked claim is measurably slow; it is not part of this scope. The new coordination table adds only about one row per status list. Removing repeated counts and fixing concurrent rollover is sufficient for the 5-issuances-per-second target; benchmark one claim, list initialization, and database/index storage before production. Background pre-creation of lists and unused-index removal are not required at this rate; list creation may take a few seconds once every several hours.

For this nonproduction rollout, provision a fresh, disposable Certify PostgreSQL database/schema from the updated DDL, register the required issuer/key/credential configuration, and issue new synthetic credentials. Start new allocations on 64 KiB lists. Do not copy old ledger, status-list, transaction, or allocation rows into this clean test database. The DMT source database and its licence fixtures do not need to be reset. Legacy-data migration is outside this rollout's scope.

## 3. Python mock IDP and separate management authentication

Create **`preauth/mock_service_auth.py` inside the DMT test kit** and update its launcher, which currently references a missing file.

The mock will provide:

| Endpoint | Purpose |
|---|---|
| `POST /oauth/token` | Issue a service token using `client_credentials` and HTTP Basic client authentication |
| `GET /management/jwks.json` | Publish the management signing public key |
| `GET /.well-known/oauth-authorization-server` | Advertise token and key endpoints |
| Existing `GET /jwks.json` | Preserve the current pre-authentication fixture’s public key |

Use loopback port **18088**, persistent private signing keys and client credentials, RS256 signatures, and **600-second tokens**. Tokens contain issuer, audience, expiry, client identity, and granted scopes. Do not log secrets or tokens.

Implement a separate Certify management security chain with these properties:

```text
mosip.certify.management.auth.enabled
mosip.certify.management.auth.issuer
mosip.certify.management.auth.jwk-set-uri
mosip.certify.management.auth.audience
mosip.certify.management.auth.allowed-clients
mosip.certify.management.auth.client-id-claim
mosip.certify.management.auth.allow-unauthenticated   # default false; local profile only
```

For the lab, use audience `certify-management`, client `erl-admin-backend`, and identity claim `client_id`.

| Protected POST endpoint, relative to `/v1/certify` | Required scope |
|---|---|
| `/credentials/status`, `/credentials/v2/status` | `certify:credentials:revoke` |
| `/ledger-search`, `/v2/ledger-search` | `certify:ledger:search` |

Validate signature, issuer, audience, expiry, configured client identity, and scope. Return **401** for missing/invalid authentication and **403** for insufficient permission. Enabled but incomplete configuration must fail startup.

**Fail closed.** The existing `mosip.certify.security.ignore-auth-urls` currently lists `**/credentials/**` and `**/ledger-search/**` as unauthenticated, so a deployment that omits or mistypes `management.auth.enabled` would silently leave revocation and ledger search open.

- Remove `**/credentials/**` and `**/ledger-search/**` from both `ignore-auth-urls` and the CSRF-ignore list; the management chain now owns these routes. Do not re-add the status-list path to `ignore-auth-urls`: that property is a plain path list that cannot restrict by method, and the default chain no longer sees these requests. The public status-list GET is permitted inside the management chain instead (section 6.1).
- The management chain covers `/credentials/**`, `/ledger-search/**` and `/v2/ledger-search/**`. Within them, only `GET /credentials/status-list/*` is public, the four POST routes require authentication and their scope, and everything else is `denyAll()`. Endpoints added later under these paths are therefore protected by default.
- Startup validation: if `management.auth.enabled=false` while the ledger or any credential-status purpose is configured, refuse to start unless `mosip.certify.management.auth.allow-unauthenticated=true`. Only the local/test profile sets that override; ERL and production overlays must not.

Keep wallet authentication and its `mosip.certify.authn.jwk-set-uri` unchanged. Public status-list GET remains unauthenticated.

This implements the service-account token pattern supported by Keycloak. A later Keycloak deployment supplies its issuer/JWKS, audience and scopes, with `client-id-claim=azp` if that is its configured client identity claim. [Keycloak service accounts](https://www.keycloak.org/docs/26.7.0/server_admin/index.html#_service_accounts)

## 4. Request flow, search, and publication

Provide a runnable Bruno collection with this sequence:

1. **Get management token:** call `http://localhost:18088/oauth/token` using the service client ID and secret.
2. **Search if needed:** call the generic ledger endpoint with `Authorization: Bearer {{management_access_token}}`, issuer, credential type, and an exact `document_id` or other field permitted for that type. ERL also supports `vehicle_no`. In Bruno, start with `limit=100` and `offset=0`. Skip search when the credential ID is already known.
3. **Revoke:** call `/credentials/status` with that credential ID and `status=true`.
4. **Verify publication:** fetch the public status-list URL from the VC, verify its signature, decompress it, and confirm the referenced bit is set.

Use `http://localhost:18090/v1/certify` for local management calls. The existing public gateway blocks management routes; retain that boundary. Production management calls use private HTTPS.

**Endpoint correctness**

- Select the stored status entry matching the requested purpose.
- Validate v2 list IDs, index bounds, and linkage to an issued credential.
- Reject purpose mismatches and attempts to clear revocation. Repeated revocation is idempotent.
- Keep the existing response contract, documenting that success acknowledges a queued change; publication happens asynchronously.

**Bounded ledger search**

Keep search **generic across document types**, with bounded offset pagination for filtered admin searches. The controller and request shape must not hardcode ERL field names. A common `document_id` is the source document's business identifier; it is distinct from the VC's `credential_id` and the internal ledger row `id`. Scope it by issuer and credential type, and do not make it unique: repeated issuance can generate multiple VCs for the same source document. Mandatory exact-match filters should keep result sets small, but their selectivity must be tested for every enabled type. PostgreSQL still computes skipped rows, so cap the offset rather than permitting unrestricted deep paging. [PostgreSQL LIMIT/OFFSET](https://www.postgresql.org/docs/current/queries-limit.html)

- Apply the same pagination contract to `/ledger-search` and `/v2/ledger-search`. Retain required `issuerId` and `credentialType`. Require a nonblank `credentialId` and/or at least one exact-match entry in `indexedAttributesEquals` permitted for that credential type. Support credential-ID-only lookup using the existing credential-ID index; the current validator must be changed because it always requires an attribute filter. Combine all supplied filters with AND. Reject unsupported or blank attributes; do not silently discard them. No prefix, substring, or wildcard searches.
- Extend the current global attribute-mapping configuration with per-credential-type mappings and searchable-field allowlists, keyed by the normalized credential-type string already stored in the ledger. Scoped configuration takes precedence; types without it retain their global mappings as their permitted fields. Extract only from trusted provider data. Normalize source IDs to strings, preserving leading zeros. For ERL, `document_id` maps from `$.license_no`; another type can map the same key from `$.drivers_license_id` or `$.revenue_license_id`. Keep native attribute names available when configured. UI labels may differ by document type while the generic ID filter remains `document_id`.
- Add optional integer body fields `limit` and `offset`. Supplying either selects paginated mode; omitted values then default to `limit=100` and `offset=0`. Allow `1 <= limit <= 1000` and `0 <= offset <= 10000`. Return HTTP 400 for invalid values; use `search_window_exceeded` above the offset cap and prompt the administrator to narrow the search, for example by adding `document_id`. Treat 10,000 as the initial configurable maximum, subject to capacity validation.
- Query filtered ledger rows using `ORDER BY id ASC LIMIT (limit + 1) OFFSET offset`. Return responses for at most `limit` ledger rows and use the extra row to set `X-Has-More: true|false`. The ID is the internal ledger row ID, not the VC UUID. Retain response arrays and the current HTTP 200/nonempty and 204/empty behaviour; paginated empty responses include `X-Has-More: false`. No cursor or `X-Next-Cursor` is introduced.
- If both pagination fields are absent, preserve the planned compatibility behaviour: return all matches up to 1,000; detect a larger result with a 1,001-row lookup and return HTTP 400 `pagination_required`, without a partial array. Do not run an automatic total-count query in either mode.
- Update Bruno to request 100 records at offsets 0, 100, 200, and so on. The admin UI uses Previous/Next and can jump to a page by calculating `(page - 1) * limit`, within the offset cap. Reset the offset when a filter or page size changes. Results are live, not a cross-request database snapshot; the UI must allow refresh rather than promise an immutable result set or an exact page total.

Example first-page request (issuer and credential type retain their deployed values):

```json
{
  "issuerId": "<configured ERL issuer DID>",
  "credentialType": "ERLCredential,VerifiableCredential",
  "indexedAttributesEquals": { "document_id": "RL-12345" },
  "limit": 100,
  "offset": 0
}
```

**Search indexes**

Use one common B-tree expression index for the source document ID across all document types:

```sql
CREATE INDEX idx_ledger_document_page
    ON ledger (issuer_id, credential_type, (indexed_attributes->>'document_id'), id);
```

Filter `document_id` using the matching, parameterized `indexed_attributes->>'document_id' = :documentId` expression. Retain parameterized JSONB `@>` containment and the existing GIN index for other configured attributes, including ERL vehicle number and native licence ID. The GIN index supports different attribute names without a new index or controller branch for each document type, but it does not provide the ID ordering; measure any required sorting. Add a dedicated field expression index only when a measured workload justifies it, with a matching query expression. The base design no longer requires ERL-specific vehicle/licence indexes. [PostgreSQL JSONB indexing](https://www.postgresql.org/docs/current/datatype-json.html#JSON-INDEXING), [expression indexes](https://www.postgresql.org/docs/current/indexes-expressional.html)

No `document_id` backfill or legacy coverage migration is required for the fresh development database. Populate `document_id` through the configured extraction mappings during new issuance and generate fresh synthetic ledger records for tests. A restored copy containing older ledger rows would still require a separate migration decision; it is not the clean-start approach selected here.

Retain query and index validation independently of migration: verify source-ID, other-attribute, credential-ID-only, and combined searches with `EXPLAIN (ANALYZE, BUFFERS)` on representative seeded datasets, rather than an empty database. Measure index storage and issuance write cost. [PostgreSQL indexes and ordering](https://www.postgresql.org/docs/current/indexes-ordering.html)

**Publication within 90 seconds**

- Run the publication job once every **30 seconds**, processing batches of **1,000** and draining further batches while work remains. Healthy worst case for a revocation made just after a run: about 30s wait + 2s publish + 20s cache = about **52 seconds**. If a node crashes and one run is missed: about 30s + 30s + 2s + 20s = **82 seconds**, still inside the 90-second target. At 2 revocations per second a run handles about 60 changes.
- Keep the existing global ShedLock lease so only one node runs the job per interval. Set `lock-at-least-for=25s` and `lock-at-most-for=28s`, both below the 30s interval. The code's current default `lock-at-most-for` is 50 minutes, so a node that crashes mid-run would block all publication for up to 50 minutes; with 28s, the next scheduled run takes over, so a crash costs at most one run. The per-list database lock below remains as the safeguard if a run ever outlasts its lease. Normal runs take seconds.
- Process affected lists one at a time on the scheduler thread. At 2 revocations per second, a 30-second run handles about 60 changes, so parallel workers are not needed; revisit only if measured publication delay requires it.
- Put each list update in a separate transactional service: lock the list, load pending changes, update/sign, and commit the document and processed markers together.
- Set signed `credentialSubject.ttl=20000` and matching **20-second HTTP/CDN caching**, with revalidation and no stale-serving overrides. W3C recommends aligning these caching signals. [Status-list TTL](https://www.w3.org/TR/vc-bitstring-status-list/#bitstringstatuslistcredential)
- Monitor oldest pending age. If an affected list has unpublished changes older than 90 seconds (three 30-second intervals, so one missed run after a node crash does not trigger it; a lower value would trigger the error during crash recovery), return an explicit unavailable response rather than repeatedly serving outdated status as fresh.

## 5. Validation, rollout, and assumptions

**Functional acceptance**

- Issue a synthetic ERL VC containing its ID and status reference.
- Obtain a service token directly, search the ledger, revoke the VC, and verify the published bit within 90 seconds through the gateway/cache path.
- Verify both endpoint versions, authentication failures, scope isolation, and unchanged wallet issuance.
- Verify fail-closed behaviour: startup is refused when management auth is disabled without the explicit override, and unauthenticated requests to revoke or ledger search return 401 on every route under the covered paths.
- Test concurrent rollover, duplicate prevention, issuance failure, publisher crashes (kill the publishing node mid-run; the next scheduled run on another node must publish the pending changes, with no manual unlock), signer failure, and retained access to old lists.
- For both search versions, test vehicle-only, licence-only, and combined AND filters; no matches; repeated VCs for one source licence; rejection of blank/unsupported filters; default pagination values; first, middle, last, and empty pages; and `X-Has-More`. Cover limits 1 and 1,000, offsets 0 and 10,000, invalid/noninteger inputs, and rejection above the cap. Verify legacy requests return all results up to 1,000 and `pagination_required` for 1,001. With a fixed dataset, consecutive pages must not omit or duplicate credentials.
- Register a second synthetic document type with a different native source-ID field. Test generic `document_id` mapping on newly issued records, native-field searches, scoped mapping precedence, global fallback, and per-type field validation. Identical document IDs in different issuers/types must remain isolated, while repeated VCs for the same document remain searchable. Test credential-ID-only lookup, preservation of leading zeros, and missing source IDs. No document-specific controller or mandatory per-type SQL index may be needed to enable the second type.

**Capacity acceptance**

- Benchmark against PostgreSQL with representative **16-million and 160-million-row datasets**.
- Exercise **5 issuances/second and 2 revocations/second**, including revocations distributed across historical lists. Treat this as a normal load test, not a stress test.
- Use **20 searches/second** as the planning assumption.
- Benchmark generic `document_id`, credential-ID-only, and each type's other permitted filters at offsets 0 and 10,000, including unusually frequent document IDs, vehicle numbers, and combined filters. Inspect query plans and filtered cardinalities at both dataset sizes, comparing the common document-ID index with generic GIN-backed searches. Offset pagination is accepted for these filtered admin searches only; treat failure of the search load test as a release blocker, and require a separately reviewed design for unfiltered browsing or bulk export.
- Measure one status-position claim, list creation time, search latency, publication delay (revocation request to published bit), and database/index size.
- Treat meeting these targets as a production release gate.

Roll out first in the DMT lab, then a production-like load environment, then production after the capacity and failure tests pass.

The deployment target remains one ERL issuer with instance-wide management authority; the reusable search API must support other credential types through configuration. The second type is a synthetic validation fixture, not an additional production provider. This development rollout starts with a fresh Certify database and newly issued test VCs; preserving or backfilling old test records is not required. Preserve the new credentials' status-list URLs and indexes across subsequent rollovers. The ten-year forecast is not a deletion policy. The Python IDP remains a development fixture.

## 6. Planned code changes

This section maps the agreed behaviour to implementation work. **New** means a proposed file to create; **Modify** means an existing file to update. The VCDM 2.0 context/date-field correction in the Certify ERL examples is already in the working tree; the implementation below is still planned.

Path shorthand used below:

- `service/` = `certify-service/src/main/java/io/mosip/certify/`.
- `core/` = `certify-core/src/main/java/io/mosip/certify/core/`.
- `db/` = `db_scripts/inji_certify/`.
- `kit/` = the sibling `dmt-certify-test-kit` repository.

### 6.1 Service-token authentication

| Action | File | Planned change |
|---|---|---|
| New | `service/config/ManagementAuthProperties.java` | Bind and validate `mosip.certify.management.auth.*`. Default the feature to disabled and `client-id-claim` to `client_id`; require issuer, JWKS URL, audience, and a nonempty client allowlist when enabled. |
| New | `service/config/ManagementSecurityConfiguration.java` | Add an order-2 stateless security chain after the existing order-1 pre-auth chain, always registered (not only when `enabled=true`). Match `/credentials/**`, `/ledger-search/**` and `/v2/ledger-search/**` using the configured servlet prefix. Apply rules in this order: (1) `GET /credentials/status-list/*` is `permitAll`; (2) the four POST routes require authentication and their scope; (3) `anyRequest().denyAll()`. When `enabled=false`, rule 2 denies unless `allow-unauthenticated=true`. Enable `.cors(...)` on this chain using the existing `CorsConfigurationSource` bean, so the public status-list GET gets whatever CORS mapping `cors-enabled-get-method-urls` defines (the default chain applied it before; the management chain must too). Use a dedicated JWT decoder and explicit RS256 trust for the initial mock. Disable CSRF only in this bearer-token chain. |
| New | `service/services/ManagementCallerAuthorizer.java` | After JWT validation, require the configured client-identity claim to be a nonblank string in the allowlist. Combine that check with the endpoint's scope check. Do not fall back among `client_id`, `azp`, and `sub`. |
| Modify | `certify-service/src/main/resources/application-local.properties` | Remove `**/credentials/**` and `**/ledger-search/**` from `mosip.certify.security.ignore-auth-urls` and `ignore-csrf-urls`. Do not add a status-list entry to either list. Today `cors-enabled-get-method-urls` lists only `/rendering-template/**`, so cross-origin browser reads of the status list are already unsupported. Add `/credentials/status-list/**` to that property only if browser-based verifiers must read it; either way the management chain must enable CORS so the setting takes effect. |
| New | `service/config/ManagementAuthStartupValidator.java` | Fail startup when auth is disabled, the ledger or a status purpose is configured, and `allow-unauthenticated` is not true. Log a warning when the override is on. |
| Modify | `erl-dataprovider-plugin/examples/application-erl.properties` | Enable management auth for ERL and reference the environment variables generated by the test kit. Keep wallet and pre-auth token trust configured independently. |

The management decoder must require `exp` and validate signature, issuer, audience, expiry, and any `nbf`. Missing or invalid bearer tokens return 401 before controller invocation; valid tokens with a disallowed client or missing scope return 403. Key-fetch/decoding failures must fail closed. The existing `SecurityConfig.java` and `PreAuthServiceSecurityConfiguration.java` continue handling their current routes; tests must prove that the status-list GET is reachable without a token (and returns CORS headers for a cross-origin request if the path is added to `cors-enabled-get-method-urls`), that wallet issuance and pre-auth routes are not matched by the management chain, and that every other path under the covered prefixes is denied.

### 6.2 Generic attribute mapping and paginated search

| Action | File | Planned change |
|---|---|---|
| Modify | `service/config/IndexedAttributesConfig.java` | Retain `indexedMappings` and add `Map<String, CredentialTypeIndexConfig> indexedTypes`. The nested configuration contains `mappings` and `searchableFields`. Validate normalized type keys, nonblank JSONPaths, and that each permitted search field has a mapping. |
| New | `service/services/IndexedAttributePolicy.java` | Resolve scoped mappings and permitted fields by the normalized credential-type string. Use the existing global mappings when the type has no scoped configuration. Centralize this logic so extraction and search validation cannot disagree. |
| Modify | `service/utils/LedgerUtils.java` | Make attribute extraction accept the resolved credential type, use the policy, and produce `document_id` as a scalar string. Preserve leading zeros in string IDs; reject object/array IDs. Share the existing sorted credential-type representation with configuration and request validation. |
| New | `service/config/LedgerSearchProperties.java` | Bind `mosip.certify.ledger-search.default-limit=100`, `max-limit=1000`, `max-offset=10000`, and `legacy-limit=1000`, validating their ranges and relationships. |
| Modify | `core/dto/CredentialLedgerSearchRequest.java` | Add nullable integer `limit` and `offset` fields. Preserve whether pagination was requested before applying defaults. Reject fractional/noninteger JSON values rather than allowing integer coercion. Retain `issuerId`, `credentialType`, `credentialId`, and `indexedAttributesEquals`. |
| New | `core/validation/StrictIntegerDeserializer.java` | Apply field-level Jackson deserialization to `limit` and `offset`, accepting only integer JSON numbers within the Java integer range. Leave coercion settings for unrelated endpoints alone. |
| New | `core/dto/LedgerSearchResult.java` | Add an internal result object carrying records, `hasMore`, and whether the request used pagination. This supplies controller header metadata without changing the public response array. |
| Modify | `core/spi/CredentialLedgerService.java` | Return `LedgerSearchResult<CredentialStatusResponse>` from both search methods; update all callers and tests. |
| Modify | `service/repository/LedgerIssuanceTableCustomRepository.java` and `LedgerIssuanceTableCustomRepositoryImpl.java` | Return a bounded result, add deterministic `ORDER BY id ASC`, bind limit/offset, and fetch one extra row. Use the fixed document-ID extraction expression for `document_id` and bound JSONB containment values for other configured fields. Keep issuer/type filtering and optional credential-ID filtering. |
| Modify | `service/services/CredentialLedgerServiceImpl.java` | Validate filters through the policy, accept credential-ID-only lookup, apply pagination defaults/caps, and map the bounded records to the current v1/v2 response fields. Enforce the legacy 1,000-record ceiling without a full count query. |
| Modify | `service/controller/CredentialLedgerController.java` | Set `X-Has-More` for paginated responses, serialize only the record array, and retain 200/nonempty and 204/empty behaviour. |

The proposed internal Java result is:

```java
public record LedgerSearchResult<T>(
        List<T> records,
        boolean hasMore,
        boolean paginated) {}
```

Pagination operates on ledger rows before response mapping. ERL has one revocation entry per row. If another credential type has multiple status entries, keep all responses belonging to one ledger row on the same page and document that the limit counts credentials.

The ERL profile will provide configuration equivalent to:

```properties
mosip.certify.indexed-types[ERLCredential,VerifiableCredential].mappings[document_id]=$.license_no
mosip.certify.indexed-types[ERLCredential,VerifiableCredential].mappings[vehicle_no]=$.vehicle_no
mosip.certify.indexed-types[ERLCredential,VerifiableCredential].mappings[issued_date]=$.issued_date
mosip.certify.indexed-types[ERLCredential,VerifiableCredential].searchable-fields=document_id,vehicle_no
```

Add a configuration-binding test for the comma-separated type key and underscore-containing field names. A synthetic driving-licence type will bind `document_id` to its own source path through the same mechanism. A declared `document_id` mapping must resolve to a nonblank scalar on new issuance; report invalid provider data if it does not. Additional stored fields are searchable only when allowed by that type's policy.

### 6.3 Issuance, template, and ledger persistence

| Action | File | Planned change |
|---|---|---|
| Modify | `service/services/CertifyIssuanceServiceImpl.java` | Resolve indexed attributes with the credential type, allocate a status position, render/sign the VC, then record the successful issuance before returning it. Move the existing ledger write after successful `addProof(...)`. Pass the actual rendered validity dates rather than generic defaults that a provider may have overridden. |
| New | `service/services/IssuanceRecordService.java` | Own one transaction for the ledger insert, when enabled, and confirmation that the allocated position belongs to an issued credential. If persistence fails, do not return the signed VC. With ledger disabled, still confirm the status allocation so v2 validation does not depend on ledger availability. |
| Modify | `core/spi/CredentialLedgerService.java` and `service/services/CredentialLedgerServiceImpl.java` | Extend the ledger-write arguments to include expiration date and persist it in the existing `expiration_date` column. Participate in the issuance-record transaction. |
| Modify | `erl-dataprovider-plugin/src/main/java/io/mosip/certify/erl/ErlTemplateHelper.java` | Serialize the supplied status object into an `erlCredentialStatus` JSON template value, using a JSON serializer just as the helper already does for the subject. |
| Modify | `erl-dataprovider-plugin/examples/erl-template.vm` and `kit/preauth/erl/erl-template.vm` | Render the generated VC `id` and serialized `credentialStatus` alongside the existing V2 context and validity fields. |
| Modify | Both repositories' corresponding `erl-credential-configuration.json` files | Enable `credentialStatusPurposes=["revocation"]` and regenerate `vcTemplate` from the matching template. Validate that decoding the payload reproduces that template exactly. |

A failed issuance can consume a committed allocation but must never cause that position to be assigned to another credential. The ledger continues to describe issuance and status references; this change does not add a separately maintained revoked flag to it.

### 6.4 Allocation schema and Java allocator

Keep the existing allocation method and add **one coordination table**, rather than the earlier three-table sparse-claim redesign. Retain both `status_list_credential` and `status_list_available_indices`.

**Concurrency review note — 2026-11-08**

Static review of `DatabaseStatusListIndexProvider.acquireIndex()` and `StatusListCredentialService.findOrCreateStatusList()` identifies the following distinct concerns. These findings still need the PostgreSQL reproduction/regression tests below; they are not claims of completed runtime verification.

| Concern | Current code | Planned handling |
|---|---|---|
| Duplicate assignment of one position | Already protected by the position-row `FOR UPDATE SKIP LOCKED` claim/update and existing uniqueness constraint. | Retain the current query and constraint; add a regression test. |
| Strict 50% allocation boundary | The assigned-row count and position claim are separate operations. Concurrent requests can both pass the count check and allocate different positions beyond the threshold. | Replace the count check with an atomic, conditional counter update in the same transaction as the position assignment. Roll back any position assignment that cannot obtain capacity. |
| Initial creation and replacement creation | The current find-then-create path can be entered by multiple requests; their different list IDs do not conflict. | Serialize creation per issuer/purpose, recheck active state inside the creation lock, and enforce at most one active allocation row with a partial unique index. |
| Allocation error mistaken for fullness | Exceptions and missing position results can currently become an empty result, leading the caller to create a new list. | Report full only from authoritative closed/exhausted allocation state. Propagate database errors or temporary position unavailability separately. |

Being inside `@Transactional` does not itself serialize the current count-check-claim or find-create sequence across requests. The new counter table is the chosen implementation for capacity tracking and active-list lookup; it is not required to solve duplicate position claims, which are already handled.

Create `db/ddl/certify-status_list_allocation.sql` with one row per list:

| Column | Meaning |
|---|---|
| `status_list_credential_id` | Primary key and foreign key to the existing status-list record. |
| `issuer_id`, `status_purpose` | Identify the allocation group. Different document types from the same issuer can share the same purpose/list. |
| `physical_capacity` | Number of positions in the list; 524,288 for 64 KiB. |
| `allocation_limit` | Maximum committed allocations; 262,144 at 50%. |
| `allocated_count` | Persisted count, initially zero, incremented once per committed allocation. |
| `is_active` | Whether the list accepts new allocations. Historical rows remain for closed lists. |
| `cr_dtimes`, `upd_dtimes` | Creation and update timestamps. |

Use `BIGINT` for counts/indexes and a check constraint enforcing `0 <= allocated_count <= allocation_limit <= physical_capacity`. Add a partial unique index to enforce one active list per issuer/purpose:

```sql
CREATE UNIQUE INDEX uq_status_list_allocation_active
    ON status_list_allocation (issuer_id, status_purpose)
    WHERE is_active;
```

The unique index prevents duplicate active lists, but does not by itself coordinate initial creation. Use a transaction-scoped PostgreSQL advisory lock derived consistently from issuer/purpose for initial creation and rollover; recheck active state while holding it. Acquire this creation lock only after any previous position/counter transaction has ended. [PostgreSQL partial unique indexes](https://www.postgresql.org/docs/current/indexes-partial.html)

| Action | File | Planned change |
|---|---|---|
| Modify | `service/services/StatusListIndexProvider.java` | Return a claim result containing the assigned index and whether it reached the allocation limit. Reserve an empty result for an actually full list; errors remain exceptions. |
| New | `service/entity/StatusListAllocation.java` and `service/repository/StatusListAllocationRepository.java` | Model the single new table; provide active-list lookup, a conditional bounded counter increment, and active-list creation/closure operations. |
| Modify | `service/services/DatabaseStatusListIndexProvider.java` | Keep random selection from available rows and `SKIP LOCKED`. In one `REQUIRES_NEW` transaction, claim a position, then conditionally increment its list's count only where `is_active` and count is below limit. Roll back the position change if the increment fails. Remove the count query and swallowed-error/full conflation. |
| New | `service/services/StatusListAllocationService.java` | Resolve the active allocation row and coordinate initial creation/rollover with explicit transaction boundaries and the issuer/purpose advisory lock. Return the selected list ID and index to issuance. |
| Modify | `service/services/StatusListCredentialService.java` | Delegate assignment and remove the broad transaction around `addCredentialStatus()`. Retain `initializeAvailableIndices()` for new lists. During creation, save the signed list, initialize all available position rows, and create the active allocation row in one creation transaction. Remove the current `-1`/exception-as-full rollover path. |
| Modify | `service/entity/StatusListAvailableIndices.java`, `service/repository/StatusListAvailableIndicesRepository.java`, and `db/ddl/certify-status_list_available_indices.sql` | Retain the existing assignment flag and uniqueness rule. Add nullable `issued_at` and `credential_id` fields to confirm successful issuance separately from position allocation. Support issued-position lookup for v2 revocation. |
| Modify | `db/ddl.sql` | Retain the existing position-table DDL and include the new allocation-table DDL after the status-list table. The fresh database requires no legacy-data conversion. |
| Modify | `db/ddl/certify-ledger.sql` | Add the shared `idx_ledger_document_page` expression index from section 4; retain existing generic search indexes. |

Allocation flow:

1. Read the active allocation row for the issuer/purpose; if absent, create a fully initialized list under the creation lock and commit it before assignment.
2. In the position-allocation transaction, select an unassigned position using the existing random query and `FOR UPDATE SKIP LOCKED`, then set `is_assigned=true`.
3. Conditionally increment `allocated_count` while the same list is active and below its stored limit. This update acquires the counter-row lock after random selection, keeping expensive selection outside that shared critical section. Never batch-reserve positions.
4. If another request already closed the list, roll back the position assignment before asking the coordinator for the current list. If no position is returned while capacity remains, report a retryable allocation failure rather than assuming fullness. Database errors also propagate explicitly.
5. On the increment to 262,144, set `is_active=false` and mark the status-list allocation flag FULL with a targeted update that does not overwrite `vc_document`. Commit the position assignment and counter change together.
6. After the final allocation commits, create the replacement under the issuer/purpose creation lock. Recheck active state so competing requests reuse a replacement already created. A crash or creation failure is recovered on the next request; the old list cannot exceed its limit.
7. Render/sign the credential outside the allocation transaction. After success, the issuance-record transaction fills in the existing position row's `issued_at` and credential ID.

The capacity reservation uses a single conditional update, conceptually:

```sql
UPDATE status_list_allocation
SET allocated_count = allocated_count + 1,
    is_active = (allocated_count + 1 < allocation_limit),
    upd_dtimes = CURRENT_TIMESTAMP
WHERE status_list_credential_id = :listId
  AND is_active
  AND allocated_count < allocation_limit
RETURNING allocated_count, allocation_limit, is_active;
```

Run position claim plus counter update in one `REQUIRES_NEW`, `READ_COMMITTED` transaction. Do not commit the position claim before reserving capacity. A zero-row counter update requires the transaction to roll back the selected position; only a confirmed closed/exhausted row is reported as full. A missing allocation row or other inconsistent state is an error, not an instruction to create a replacement. End that transaction before entering the creation-lock transaction.

Creation also uses an explicit transaction: acquire the issuer/purpose advisory lock, re-read active state, reuse an existing active list if present, otherwise create/sign the list, initialize its positions, insert its active allocation row, and commit. Publish no new list reference to an issuing request before that commit. A creation failure rolls back the new database records, and a later request can retry. The partial unique index is an additional database safeguard, not a substitute for the lock and recheck.

**Concurrency acceptance scenarios**

- **Same list, simultaneous claims:** use separate PostgreSQL connections and overlapping transactions; no committed allocations may share a `(list ID, position)` pair.
- **One allocation remains:** synchronize multiple requests so they all start against the old list. Exactly one may take its last permitted allocation. Others must undo their temporary old-list claims and use the replacement; the old counter and assigned-position count must equal its limit.
- **No active list:** start several creators concurrently. Exactly one fully initialized active list must be committed for the issuer/purpose, with no extra lists created by requests that waited for the lock.
- **Concurrent rollover:** cross the threshold under load and verify one replacement is reused, its position rows exist before any allocation, and the closed list accepts no further committed allocations.
- **Failure and recovery:** fail after a position update, after a counter update but before commit, and during replacement initialization. Verify rollback/count consistency, no partially active list, and successful retry. A database error or temporarily unavailable position must not create a replacement merely because the original method returned no index.

Validate size/percentage settings at startup and guard capacity multiplication against overflow. Preserve each existing list's stored capacity/limit if configuration changes. Counter updates remain serialized briefly; at 5 issuances per second this is not a bottleneck, and bulk position initialization runs about once every several hours, so validate it once under load rather than optimising it. This design has no random-candidate collision loop or 32-candidate retry rule. Signed status-list publication remains separate from normal position allocation.

### 6.5 Revocation validation and asynchronous publication

| Action | File | Planned change |
|---|---|---|
| Modify | `core/dto/UpdateCredentialStatusRequest.java` and `UpdateCredentialStatusRequestV2.java` | Validate nested input, nonblank v2 list IDs, and nonnegative indexes. Keep existing request field names and the v2 contract that identifies the stored list ID. |
| Modify | `service/services/CredentialStatusServiceImpl.java` | For v1, resolve the ledger status entry matching the requested purpose; if omitted, require a single unambiguous entry. For v2, validate list purpose, physical bounds, and an assigned position whose `issued_at` confirms issuance. Enforce configured purposes and reject `status=false` for revocation. |
| Modify | `service/repository/CredentialStatusTransactionRepository.java` and `db/ddl/certify-credential_status_transaction.sql` | Add concurrency-safe insertion/readback for repeated revocations, ordered pending work per list, and oldest-pending-time queries. Add a unique partial index on `(status_list_credential_id, status_list_index, status_purpose)` for true revocations, plus a pending-work index by list/time/transaction ID. |
| New | `service/services/StatusListPublicationService.java` | Own the public, proxied transactional method that claims a list row, reads its pending updates, applies/re-signs its bitstring, and saves the new document and processed markers together. |
| Modify | `service/services/StatusListUpdateBatchJob.java` | Run every 30 seconds on the scheduler thread, drain batches of 1,000 one list at a time, and call the publication service. Keep the global ShedLock lease with `lock-at-least-for=25s` and `lock-at-most-for=28s`, which prevents overlapping runs, and add per-list database locking inside the publication service. No separate executor is needed at 2 revocations per second. |
| Modify | `service/repository/StatusListCredentialRepository.java` | Add list-row claims using `FOR UPDATE SKIP LOCKED`, so concurrent nodes cannot publish the same list simultaneously. Read pending transactions after acquiring that list's lock. |
| Modify | `service/utils/BitStringStatusListUtils.java` | Throw on invalid indexes rather than logging and silently skipping them. Preserve the established bit ordering. |
| Modify | `service/services/StatusListCredentialService.java` and `service/controller/CredentialStatusController.java` | Add signed `credentialSubject.ttl=20000` on creation/publication. Return a `ResponseEntity<String>` for public GET with `Cache-Control: public, max-age=20, s-maxage=20, must-revalidate` and a strong `ETag` derived from a hash of the stored `vcDocument` bytes. Honour `If-None-Match` and return **304 Not Modified** (no body) when it matches. Keep the 503 decision: return 503 with `Cache-Control: no-store` and no ETag when unpublished work for that list exceeds 90 seconds, so a stale list is never served as current. The ETag is computed after the signed document is stored, so it changes only when the published list changes. |
| New | `service/config/StatusListPublicationConfiguration.java` | Provide an injectable UTC clock and settings for cache TTL and maximum pending age. Use the existing Actuator/Micrometer support for pending count, oldest pending age, and publication duration/failure. |

Only mark the transaction IDs actually included in a successful publication as processed. Signing/database failure rolls back the publication and leaves them pending for the next dispatch; exclude a failed list from the remainder of that dispatch so other lists can progress without a tight retry loop. Normalize queue/publication timestamps to UTC. The pending-age guard must execute before returning any cacheable response. Repeated true revocation returns the existing transaction's acknowledgement; other future purposes, such as suspension, are not subjected to the irreversible-revocation uniqueness rule.

Add the following effective settings to the ERL overlay:

```properties
mosip.certify.batch.status-list-update.enabled=true
mosip.certify.batch.status-list-update.cron-expression=*/30 * * * * *
mosip.certify.batch.status-list-update.lock-at-least-for=25s
mosip.certify.batch.status-list-update.lock-at-most-for=28s
mosip.certify.batch.status-list-update.batch-size=1000
mosip.certify.status-list.ttl-ms=20000
mosip.certify.status-list.max-unpublished-age-seconds=90
```

### 6.6 HTTP errors and contract documentation

The current `service/advice/ExceptionHandlerAdvice.java` converts several internal validation exceptions into HTTP 200. Add `service/exception/ManagementApiException.java` carrying an error code and HTTP status, and update the advice to return the specified 400/404/503 responses on management/status-list routes. Handle malformed pagination JSON and DTO validation on those routes as HTTP 400. Security-chain failures continue to return 401/403 before controller execution.

Use a consistent management error body with `error` and `error_description`, including the planned `pagination_required` and `search_window_exceeded` codes. Document this together with unchanged successful response arrays, `X-Has-More`, the source-ID convention, and the asynchronous revocation acknowledgement in `docs/inji-certify-openapi.yaml`. Update `erl-dataprovider-plugin/README.md` with per-type mappings and management-token configuration. Preserve the v1/v2 date-field differences in existing successful search responses.

### 6.7 DMT test-kit Python code and lab wiring

| Action | File | Planned change |
|---|---|---|
| New | `kit/preauth/mock_service_auth.py` | Implement the loopback OAuth mock using `ThreadingHTTPServer`, PyJWT, and the lab's existing cryptography dependency. Expose the four endpoints in section 3; validate Basic client credentials, the grant, and requested scopes; sign 600-second management JWTs with a dedicated persistent RSA key. |
| Modify | `kit/preauth/prepare.py` | Generate the management signing key once with restrictive permissions, reuse the existing private client configuration, export `/management/jwks.json` as the management JWKS URI, and populate the ERL security/search/status-list settings through the Certify-owned overlay. |
| Modify | `kit/scripts/manage.py` | Launch the new Python server from the test-kit path instead of the missing Certify example path. Retain managed start/stop/restart behaviour and the existing `jwks` process identity. |
| Modify | `kit/preauth/compose.yaml`, `prepare.py`, and `start.sh` | Add an explicit named-volume override for Certify PostgreSQL so this rollout can select a new empty volume. Initialize it from the updated DDL and use the existing key/configuration registration flow. Keep the MySQL source volume. Do not use the existing full `reset.sh` for this operation because it removes both database volumes. |
| Modify | `kit/preauth/bruno/Get Management Token.bru` and environment templates | Parameterize the token endpoint, client ID, and secret; keep the resulting access token in a runtime variable. Never commit resolved secrets. |
| New | Requests in `kit/preauth/bruno/` | Add v1/v2 ledger search, v1/v2 revocation, and public status-list retrieval requests. Use the generic attribute map and `limit`/`offset`; preserve the selected synthetic credential ID and status reference for the flow. |
| New | `kit/preauth/exercise_management.py` | Reuse the existing synthetic issuance flow, obtain a service token directly from the mock, search, revoke, poll the public list, verify its signature, and inspect the target bit. Assert that adjacent bits are unaffected. |
| Modify | `kit/preauth/erl/README.md` | Document the direct service-token flow, fresh Certify database setup, generic search configuration, pagination, and the distinction between acknowledgement and publication. Correct stale mock-server references. |

The mock continues serving the original pre-auth public key at `/jwks.json`; its management key is published at `/management/jwks.json`. It must reject unsupported grants, invalid credentials, and unregistered scopes; return OAuth errors with appropriate statuses and token responses with `Cache-Control: no-store`; and avoid logging bearer tokens or secrets. Client secrets belong to the admin backend or local test tools, not browser code.

### 6.8 Tests and implementation order

Add the following meaningful checks alongside the existing suites:

| Test area | Planned test files/work |
|---|---|
| Security | New `certify-service/src/test/java/io/mosip/certify/config/ManagementSecurityTest.java`: real signed JWTs and a local HTTP JWKS fixture; issuer/audience/expiry/client/scope failures, all four protected routes, and existing wallet/pre-auth/public-route behaviour. Add fail-closed cases: with the flag off, every protected route (including an unlisted path under `/credentials/`) is rejected and startup fails without the override; the public status-list GET stays reachable without a token and, when configured in `cors-enabled-get-method-urls`, returns CORS headers for a cross-origin request. `SecurityConfig` is `@Profile("!test")`, so also run a non-`test` profile slice to prove wallet issuance is unchanged. |
| Configuration/search | New `IndexedAttributesConfigTest`, `LedgerUtilsTest`, and `LedgerSearchIntegrationTest`; extend existing `CredentialLedgerServiceImplTest`. Cover property binding, two document types, ID-only searches, source-ID preservation, PostgreSQL JSONB queries, page boundaries, headers, and error statuses. |
| Allocation | New `DatabaseStatusListIndexProviderIntegrationTest`; adapt `StatusListCredentialServiceTest`. Implement the separate-connection concurrency scenarios in section 6.4, including the already-protected position-uniqueness case and the two remaining capacity/creation races. Cover prepopulated initialization, locked-position skipping, rollback/count consistency, and failure after a committed allocation. Distinguish temporary unavailability and database errors from fullness. |
| Issuance | New `IssuanceRecordServiceIntegrationTest` and `ErlCredentialProfileTest`. Check signed VC ID/status/validity fields against the ledger and claim, template/base64 agreement, and atomic persistence when a database write fails after signing. |
| Revocation/publication | Extend `CredentialStatusServiceImplTest` and `BitStringStatusListUtilsTest`; add `StatusListPublicationIntegrationTest` and controller checks. Cover idempotent concurrent revocation, invalid/unissued indexes, purpose mismatch, nonreversible revocation, competing nodes, publication rollback, and stale-list 503/cache headers, plus ETag stability (same list gives same tag, a republished list gives a new one) and 304 responses for matching `If-None-Match`. |
| Python/end to end | New `kit/preauth/tests/test_mock_service_auth.py`, plus `exercise_management.py`. Verify client errors, claims/signatures, separate JWKS, restart persistence, and the 90-second flow through the public gateway. |
| Scale | Add `kit/preauth/performance/seed_ledger.py` and `management_load.py` for streaming synthetic dataset generation and the agreed mixed workload. Capture query plans, latency percentiles, queue age, database/index sizes, and write overhead. Keep large dataset/load runs separate from ordinary unit tests. |

Use PostgreSQL 16 Testcontainers for tests that exercise JSONB, partial indexes, row locking, or transaction rollback; add the test-scoped PostgreSQL Testcontainers module to `certify-service/pom.xml`. A skipped Docker test is not evidence that the database behaviour passed. Inject clocks for age/expiry tests instead of relying on long sleeps. Reuse the project's existing Spring/JUnit conventions and update affected mocks for the new internal result types.

Implement in this order:

1. Schema files (section 6.9), configuration models, and internal DTO/SPI contracts.
2. Allocator, issuance-record transaction, V2 ID/status templates, and their PostgreSQL tests.
3. Management security chain, Python IDP, and lab configuration wiring.
4. Generic paginated search, revocation validation, transactional publication, and HTTP error/header handling.
5. Bruno/OpenAPI documentation and synthetic end-to-end validation on the fresh Certify database.
6. Representative dataset/load validation before claiming the production capacity targets.

### 6.9 Schema files and sync

The same schema is defined in several places. Each one must change together with the code. The table lists every file, what changes in it, and what it is used for.

| File | Used by | Change |
|---|---|---|
| `db/ddl/certify-status_list_allocation.sql` (new) | Fresh installs of the lab and Compose stacks | Create `status_list_allocation` with the check constraint and the partial unique index from section 6.4. |
| `db/ddl/certify-status_list_available_indices.sql` | Fresh installs | Add nullable `issued_at` and `credential_id`; add the issued-position lookup index. |
| `db/ddl/certify-credential_status_transaction.sql` | Fresh installs | Add the unique partial index for true revocations and the pending-work index (section 6.5). |
| `db/ddl/certify-ledger.sql` | Fresh installs | Add `idx_ledger_document_page` (section 4). |
| `db/ddl.sql` | Full fresh install | Include the new allocation-table DDL after the status-list table. |
| `docker-compose/docker-compose-injistack/certify_init.sql` | Docker Compose stack | Make it identical to the `db_scripts` files above, with the `certify.` schema prefix. Fix the existing mismatch: this file creates `status_list_credential.capacity`, but the entity maps `capacity_in_kb`. Also align `idx_ledger_issuance_date` with `idx_ledger_issue_date`. |
| `certify-service/src/test/resources/schema.sql` | H2 tests | No change: it has no status list, ledger or transaction tables. PostgreSQL-only behaviour (partial and expression indexes, `SKIP LOCKED`, advisory locks) is tested with Testcontainers PostgreSQL. |
| `db_upgrade_script/mosip_certify/sql/<from>_to_<to>_upgrade.sql` and `_rollback.sql` (new pair) | Existing databases | Target version to be confirmed; the current release is 0.14.0 and the last script is `0.13.1_to_0.14.0`. The upgrade must: (a) remove duplicate true revocations before creating the unique partial index; (b) create one `status_list_allocation` row per existing list, with `allocated_count` set from its current assigned positions and `is_active` set only for the list still accepting allocations; (c) add the new columns and create the ledger index with `CREATE INDEX CONCURRENTLY`, outside a transaction, so writes are not blocked. |

**Sync rules**

- The Compose file must match `db_scripts` apart from the schema prefix. A test (or a CI check) compares the two files, and fails on any difference in table, column or index names.
- A test compares every column name in the new and changed tables with the JPA `@Column` names in the entities. This catches mismatches such as `capacity` versus `capacity_in_kb`.
- Each new or changed table must appear in `schema.sql` (H2) and in the Testcontainers PostgreSQL setup.
- The upgrade script and its rollback are tested on a copy of a database populated with the 0.14.0 schema before any production use.

