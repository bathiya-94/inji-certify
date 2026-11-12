# ERL pre-authorized issuance

Build from the parent repository with Java 21:

```sh
mvn -pl certify-service,certify-dataprovider-router,erl-dataprovider-plugin -am verify -Dgpg.skip=true
```

Copy the router, DMT persistence and ERL JARs and the Boot-managed MySQL Connector/J JAR into the service's `additional_jars` loader directory. The service-with-plugins packaging also copies these artifacts. These are ordinary library JARs; do not repackage them as executable Spring Boot archives. New providers implement `DataProviderPlugin` and register a `DataProviderRegistration` with their validator and optional template customizer. The registry discovers registrations without adding router branches.

Overlay `examples/application-erl.properties` on the existing Certify configuration. Set the MySQL URL to the default `alfresco` database, with a read-only account granted access to `licence.*` and permission to select the default database (the lab uses `SHOW VIEW` on `alfresco.*`); use driver connection/socket timeouts. PostgreSQL remains the primary persistence unit. Configure separate service-JWT issuer/JWKS/audience and the permitted ERL client identity. Licence-fee, arrears and fines charge codes are mandatory deployment inputs.

The ERL examples use W3C Verifiable Credentials Data Model 2.0 (`https://www.w3.org/ns/credentials/v2`) with `validFrom` and `validUntil`.

Publish `examples/erl-context.json` at an issuer-controlled HTTPS URL. Its `example.org` term namespace is an example vocabulary: select a stable deployment vocabulary before production. Set the same context URL in the template and credential configuration. Base64-encode the final template into `vcTemplate`, set the deployed issuer DID/key configuration, and register the configuration through the existing credential-configuration API. The example configuration is a starting artifact, not an automatic database migration.

The ERL template helper serializes the subject with a JSON serializer, preserving decimals, nulls and escaping. It sets expiration after generic lifetime defaults; missing or expired `valid_to` fails before signing. Fines are the latest non-deleted configured fines charge amount, not a payment or sum calculation.

Use Redis 6.2 or newer for shared pre-auth transactions. All instances need the same cache prefix and serialization; environments need distinct prefixes. Configure code/offer TTLs at least as long as the permitted offer lifetime and transaction TTL at least as long as wallet access-token lifetime. Atomic `GETDEL` replaces JVM-local consumption. Validation failures do not consume codes; a failure after consumption requires a fresh offer.

Offer creation uses the private ClusterIP URL and a service JWT. Generated offers, discovery and wallet operations use the public issuer URL. With Istio enabled, Helm denies public offer creation before catch-all routing and applies a workload authorization policy. Set `preAuth.allowedPrincipals` to approved ERL workload identities and ensure sidecars and mesh mTLS identify callers. An empty allowlist denies all offer creation. If Istio is disabled, supply equivalent path and workload/network restrictions in the deployment gateway; JWT validation alone does not establish private exposure.

Run MySQL and Redis integration tests with Docker available. MySQL tests use 8.0.32 and synthetic fixtures. The test kit contains the isolated full-schema setup and wallet runner. No shared DMT schema changes are required. Before introducing the new cache contract into an existing environment, drain legacy outstanding offers that contain complete claims; later compatible provider mapping changes use the current route and require no provider snapshot.

## Revocation, status lists and ledger search

Every ERL licence is issued with its own id (`mosip.certify.data-provider-plugin.id-field-prefix-uri=urn:uuid:`) and a `BitstringStatusListEntry` for the `revocation` purpose. The credential configuration must enable `credentialStatusPurposes=["revocation"]`, and the template must render `id` and `credentialStatus` (the example does); the template helper refuses to issue a licence without them. The overlay sets 64 KiB lists that stop accepting new licences at 50% and roll over to a new list.

The ledger records each licence after it has been signed. For ERL it stores `document_id` (the printed licence number, `license_no`, always a string so leading zeros survive), `vehicle_no` and `issued_date`; only `document_id` and `vehicle_no` can be searched. The internal DMT row id is not indexed. Attributes are configured per credential type, keyed by the types sorted and joined with a comma, and fall back to `mosip.certify.indexed-mappings` for types without their own entry:

```properties
mosip.certify.indexed-types[ERLCredential,VerifiableCredential].mappings[document_id]=$.license_no
mosip.certify.indexed-types[ERLCredential,VerifiableCredential].searchable-fields=document_id,vehicle_no
```

Another document type needs only its own entry (for example `mappings[document_id]=$.drivers_license_id`); the search API has no per-type code. Searches take `limit` (1 to 1000) and `offset` (0 to 10000); see `docs/inji-certify-openapi.yaml`. An index on `(issuer_id, credential_type, indexed_attributes->>'document_id', id)` serves the common lookup; add a field-specific index only after measuring a real workload.

**Management access.** Revocation (`POST /credentials/status`, `/credentials/v2/status`) and ledger search (`POST /ledger-search`, `/v2/ledger-search`) require a bearer token from an identity provider you configure with `mosip.certify.management.auth.*`: issuer, JWKS URI, audience, the allowed client ids and the claim that carries the client id. The token needs scope `certify:credentials:revoke` or `certify:ledger:search`. This trust is separate from the wallet and pre-authorization settings. The routes under `/credentials/**`, `/ledger-search/**` and `/v2/ledger-search/**` are closed unless this is configured, and Certify refuses to start with the ledger or status lists in use and no management authentication; `mosip.certify.management.auth.allow-unauthenticated=true` overrides that for local development only and must not be set in an ERL or production overlay. The public `GET /credentials/status-list/{id}` stays unauthenticated.

**Publication.** A 200 from the revocation endpoints means the change is queued. Certify publishes every 30 seconds; verifiers may cache a list for 20 seconds (`Cache-Control`, with an `ETag` for revalidation). A list with a change unpublished for longer than 90 seconds returns 503 rather than being served as current.

**Database.** The new tables and indexes are in `db_scripts/inji_certify`; a new install creates them from those scripts. Nothing is deployed yet, so there is no upgrade script.

