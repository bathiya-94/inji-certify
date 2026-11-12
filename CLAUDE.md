# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Inji Certify is a Spring Boot 3.2 / Java 21 verifiable-credential issuer implementing OpenID4VCI (draft 13) and W3C VC DM 1.1 / 2.0. It issues JSON-LD (`ldp_vc`), SD-JWT (`vc+sd-jwt`) and mock mDoc credentials. This checkout is a fork. On top of upstream it adds the ERL (licence) pre-authorized issuance stack: the `certify-dataprovider-router`, `dmt-data-access` and `erl-dataprovider-plugin` modules, and the pre-auth/QR services in `certify-service`.

## Build & test

Use the system `mvn`. The repo has `mvnw.cmd` only, and `.mvn/` is gitignored. Pass `-Dgpg.skip=true` on install/package builds.

```sh
mvn clean install -DskipTests -Dgpg.skip=true                 # full reactor build
mvn -pl certify-service -am test                               # one module + its deps
mvn -pl certify-service test -Dtest=CertifyIssuanceServiceImplTest            # single test class
mvn -pl certify-service test -Dtest=CertifyIssuanceServiceImplTest#methodName # single test method
mvn -pl certify-service,certify-dataprovider-router,erl-dataprovider-plugin -am verify -Dgpg.skip=true  # ERL stack
mvn -Pwith-plugins package -DskipTests -Dgpg.skip=true         # also builds certify-service-with-plugins (copies plugin JARs into its target/)
```

- Most `certify-service` tests run against in-memory H2 (`src/test/resources/application-test.properties`, `schema.sql`, `data.sql`). H2 has no status list, ledger or transaction tables. Anything that depends on PostgreSQL behaviour (row locks, `SKIP LOCKED`, advisory locks, JSONB, partial and expression indexes) is tested with Testcontainers PostgreSQL 16 instead: `StatusListAllocationIntegrationTest`, `StatusListPublicationIntegrationTest`, `LedgerSearchIntegrationTest`. They build their tables from the real `db_scripts/inji_certify/ddl/*.sql`.
- Testcontainers tests need Docker running and are **silently skipped** otherwise (`disabledWithoutDocker`). A skipped run proves nothing: check `Skipped: 0` in `target/surefire-reports`. With Docker Engine 29 the old Testcontainers client is rejected with HTTP 400 unless the API version is raised, which `src/test/resources/docker-java.properties` (`api.version=1.44`) does in `certify-service` and `erl-dataprovider-plugin`.
- `api-test/` is a separate TestNG API test rig. It is **not** in the root reactor and runs against a deployed instance. See `api-test/README.md`.
- No linter or formatter is configured. Lombok and MapStruct annotation processing is set up in `certify-service/pom.xml`.

## Running locally

The main class is `certify-service/.../CertifyServiceApplication.java`. `bootstrap.properties` sets `spring.profiles.active=local`, port `8090` and servlet path `/v1/certify`, so endpoints live at `http://localhost:8090/v1/certify/...`. The local config is `certify-service/src/main/resources/application-local.properties`, and it needs a Postgres DB. Initialise the DB with `db_scripts/inji_certify/deploy.sh deploy.properties`. The full guide is `docs/Local-Development.md`. For a Docker stack, see `docker-compose/docker-compose-injistack/`. Postman collections are in `docs/postman-collections/`.

## Module layout (dependency direction: top depends on bottom)

- `certify-service`: the Spring Boot app. Controllers, issuance services, JPA entities and repos, proof validation/generation, Velocity templating, status lists, ledger.
- `certify-core`: shared DTOs, constants, `CertifyException`/error codes, validation, and service SPIs (`core/spi`: `VCIssuanceService`, `CredentialConfigurationService`, `CredentialStatusService`, `IarService`, …) implemented in `certify-service`.
- `certify-integration-api`: the **plugin SPI** that external plugins compile against. It has `DataProviderPlugin`, `VCIssuancePlugin`, `AuditPlugin`, plus the fork's `LookupDataProviderPlugin`, `LookupValidator`, `CredentialTemplateCustomizer` and `DataProviderRegistration`. Changing it breaks plugin binary compatibility.
- `certify-dataprovider-router`, `dmt-data-access`, `erl-dataprovider-plugin`: ordinary library JARs, loaded at runtime from the service's `loader_path` (`additional_jars`). Do not repackage them as Boot executables. Each activates through a Spring Boot auto-configuration gated on a property.
- `certify-service-with-plugins`: Dockerfile-only packaging. It builds on `injistack/inji-certify:<version>` and copies plugin JARs into the loader path. Built only with `-Pwith-plugins`.

## Core architecture

**Two mutually exclusive issuance modes**, selected by `mosip.certify.plugin-mode`:
- `DataProvider` → `CertifyIssuanceServiceImpl`. Certify is the issuer. It calls `DataProviderPlugin.fetchData()` for raw claims, renders the VC with a Velocity template (`VelocityTemplatingEngineImpl` / `VCFormatter`), optionally adds a credential status entry and a ledger record, then signs through `CredentialFactory` → `Credential` (`W3CJsonLD`, `SDJWT`, `MDocCredential`) → `ProofGeneratorFactory`.
- `VCIssuance` → `VCIssuanceServiceImpl`. Certify proxies to a `VCIssuancePlugin` that returns an already-built VC.

The concrete plugin bean is picked by `mosip.certify.integration.data-provider-plugin` / `vc-issuance-plugin` (the class simple name). Plugins are usually external JARs from the `inji/digital-credential-plugins` repo.

**Credential configuration lives in the DB, not in properties.** The `credential_config` table is managed through the `/credential-configurations` CRUD API. It holds the base64 Velocity `vcTemplate`, `didUrl`, `signatureCryptoSuite`, `keyManagerAppId`/`keyManagerRefId`, format, and so on. Issuer metadata (`/.well-known/openid-credential-issuer`) and `did.json` are generated from it. `VCFormatter` looks up per-template signing settings by template name. Signing keys are managed by the embedded MOSIP `kernel-keymanager-service` (key_store / key_alias / ca_cert_store tables), addressed by AppId/RefId. See `docs/Credential-Issuer-Configuration.md` and `docs/Rendering-Template.md`.

**Wallet proof-of-possession**: `proof/` validators (`JwtProofValidator`, `DIDjwkProofManager`, `DIDkeysProofManager`) are selected by `ProofValidatorFactory`. Access tokens are checked by `filter/AccessTokenValidationFilter` against `mosip.certify.authn.*`, which points to an external AS such as eSignet or Keycloak. `LocalAccessTokenValidationFilter` covers tokens issued by Certify's own `/oauth/token` endpoint, used in the pre-auth and IAR flows.

**Other flows in `services/`**:
- Pre-authorized code / credential offers (`PreAuthorizedCodeService`, `/pre-authorized-data`, `/credential-offer-data/{id}`, `/oauth/token`). Transactions live in the Spring cache: `simple` locally, Redis 6.2+ in multi-instance deployments, where consumption relies on atomic GETDEL.
- Interactive Authorization Request / presentation during issuance (`Iar*Service`, `/oauth/iar`).
- Revocation via W3C Bitstring Status List. Reserving a position: `StatusListAllocationService` picks the active list for the issuer and purpose, `DatabaseStatusListIndexProvider` claims a random free position and counts it against the list's limit in one `REQUIRES_NEW` transaction (table `status_list_allocation`), and creates the next list under an advisory lock when one fills. The reservation commits before signing; after signing, `IssuanceRecordService` writes the ledger entry and confirms the position (`issued_at`) in one transaction, so a failed issuance never reuses a position. Revoking: `CredentialStatusServiceImpl` validates and queues a `credential_status_transaction` (idempotent for a repeated revocation); `StatusListUpdateBatchJob` (ShedLock lease shorter than the interval) calls `StatusListPublicationService`, which locks one list (`FOR UPDATE SKIP LOCKED`), re-signs it and marks exactly the included changes processed. The public `GET /credentials/status-list/{id}` returns `ETag` and cache headers, and 503 when a change has waited too long.
- Ledger search (`CredentialLedgerServiceImpl`): filters and paging (`limit`/`offset`, `X-Has-More`), searchable fields per credential type from `IndexedAttributePolicy`; `document_id` is the source document id and is served by `idx_ledger_document_page`.
- Management endpoints (revoke, ledger search) have their own security chain, `ManagementSecurityConfiguration` (order 2), owning `/credentials/**`, `/ledger-search/**` and `/v2/ledger-search/**`. Only `GET /credentials/status-list/*` is public; the four POST routes need a token with a scope and an allowed `client_id`; everything else is denied. `ManagementAuthStartupValidator` refuses to start with the ledger or status purposes in use and no management auth, unless `mosip.certify.management.auth.allow-unauthenticated=true` (set in `application-local.properties` only; the ERL overlay sets it false). Both are `@Profile("!test")`.
- Optional PixelPass QR responses (`CredentialQrService`; see `docs/Credential-QR-Response.md`).

## Fork-specific: ERL routed pre-auth issuance

Turned on with `mosip.certify.integration.data-provider-plugin=RouterDataProviderPlugin`, plus `dmt.datasource.enabled=true` and `dmt.erl.enabled=true`:
- `RouterDataProviderPlugin` maps credential configuration IDs to `DataProviderRegistration`s through `RoutingProperties.credentialMapping`. Every registration is validated at startup. Delegates receive **only validated lookup claims**, never wallet or access-token claims, and a delegate can never itself be a `LookupDataProviderPlugin`. To add a provider, contribute a new `DataProviderRegistration` bean (plugin + `LookupValidator` + optional `CredentialTemplateCustomizer`). The router needs no changes.
- `dmt-data-access` adds a secondary, read-only MySQL persistence unit (DMT `alfresco` DB). PostgreSQL remains the primary datasource (`CertifyPersistenceConfiguration`).
- Offer creation (`/pre-authorized-data`) is a private, service-to-service call authenticated by a service JWT (`PreAuthServiceSecurityConfiguration`, `PreAuthCallerAuthorizer`, `PreAuthServiceAuthProperties`). It is separate from wallet-facing auth.
- Example config, template, JSON-LD context and credential configuration are in `erl-dataprovider-plugin/examples/`. Deployment and security requirements are in `erl-dataprovider-plugin/README.md`.

## DMT mock API (sibling checkout)

`/Users/bathiya/GovTech/dmt-mock-api` is a separate Go repo (it may be missing on other machines) that stands in for the DMT licence backend. It serves the licence lookup that `erl-dataprovider-plugin` mirrors, ships the MySQL `licence` schema and synthetic fixtures (`db-init/`), and has `POST /api/v1/erl/credential-offers`, which validates a licence and calls this repo's `/pre-authorized-data` with a service JWT. Read its skill, `../../dmt-mock-api/.claude/skills/dmt-mock-api/SKILL.md`, before changing the ERL query, lookup validation, the `licence` schema or the offer call. `ErlSql.java` and the mock's `licence_query.go` must change together.

## End-to-end ERL lab (sibling checkout)

`../dmt-certify-test-kit` is a separate git repo, and it may be missing on other machines. Its operational runbook (start, verify, fresh database, iPhone setup, troubleshooting) is the skill at `../dmt-certify-test-kit/.claude/skills/erl-lab/SKILL.md`; read it before running lab commands. It runs this repo end to end with the ERL pre-auth stack: Certify built from this checkout, Postgres/MySQL 8.0.32/Redis via Docker Compose, the Go `dmt-mock-api`, an HTTPS gateway, a Python wallet, and an SVG VC viewer. Its current lab is `preauth/` (start with `preauth/README.md`; the iPhone flow is in `preauth/MOBILE-DEMO.md`). The older `scripts/` lab targets `dmt-dataprovider-plugin`, which is not on this branch. Runtime state lives in the kit's ignored `.local/erl-preauth/`, including `env.sh` and `config/`, and Certify's log goes there too. Internal Certify runs at `http://localhost:18090/v1/certify` and the public issuer at `https://localhost:19443/v1/certify`. Management routes (revoke, ledger search) are reachable only on the internal URL; the gateway blocks them. The lab's identity fixture is `preauth/mock_service_auth.py` (port 18088; pre-auth key at `/jwks.json`, management key at `/management/jwks.json`, `POST /oauth/token`). `preauth/exercise_management.py` runs the revoke-and-verify flow end to end. A second, separate lab: set `LAB_DIR`, `ERL_COMPOSE_PROJECT` (and optionally `ERL_POSTGRES_VOLUME`) before `preauth/prepare.py --localhost` and `preauth/start.sh`; never use `reset.sh` for it.

Changes here can break the lab:
- Its scripts copy JARs by hard-coded name (`<module>-0.14.0.jar` from each module's `target/`). A version bump or artifactId rename needs matching edits in `preauth/start.sh`, `preauth/reset.sh` and `scripts/manage.py`.
- `preauth/prepare.py` generates the lab config from `certify-service/src/main/resources/application-local.properties` overlaid with `erl-dataprovider-plugin/examples/application-erl.properties`, so property renames flow straight through. Rerun `prepare.py` after changing either file.
- `preauth/verify_licence_lookup.py` pulls the SQL from the first `"""` text block in `erl/repository/ErlSql.java` and asserts it matches the Go mock's `licence_query.go`. Changing the ERL query means changing the mock as well.
- `preauth/erl/` keeps its own copies of the ERL example template, context and credential configuration, and these can drift from `erl-dataprovider-plugin/examples/`.

## Database changes

Schema changes go into: `db_scripts/inji_certify/ddl/*.sql` (fresh install; also what the lab and the PostgreSQL tests load), `docker-compose/docker-compose-injistack/certify_init.sql` (a separate hand-kept copy for the Compose stack: keep it identical, it once drifted to a wrong column name) and `certify-service/src/test/resources/schema.sql` only for tables the H2 tests touch. Nothing is deployed yet, so no upgrade script is needed for these changes (the status-list allocation work has none). Once a first deployment exists, add a versioned `db_upgrade_script/mosip_certify/sql/<from>_to_<to>_upgrade.sql` plus a matching `_rollback.sql` for every later schema change (the latest existing pair is `0.13.1_to_0.14.0`).
