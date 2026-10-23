# ERL pre-authorized issuance

Build from the parent repository with Java 21:

```sh
mvn -pl certify-service,certify-dataprovider-router,erl-dataprovider-plugin -am verify -Dgpg.skip=true
```

Copy the router, DMT persistence and ERL JARs and the Boot-managed MySQL Connector/J JAR into the service's `additional_jars` loader directory. The service-with-plugins packaging also copies these artifacts. These are ordinary library JARs; do not repackage them as executable Spring Boot archives. New providers implement `DataProviderPlugin` and register a `DataProviderRegistration` with their validator and optional template customizer. The registry discovers registrations without adding router branches.

Overlay `examples/application-erl.properties` on the existing Certify configuration. Set the MySQL URL to the default `alfresco` database, with a read-only account granted access to `licence.*` and permission to select the default database (the lab uses `SHOW VIEW` on `alfresco.*`); use driver connection/socket timeouts. PostgreSQL remains the primary persistence unit. Configure separate service-JWT issuer/JWKS/audience and the permitted ERL client identity. Licence-fee, arrears and fines charge codes are mandatory deployment inputs.

Publish `examples/erl-context.json` at an issuer-controlled HTTPS URL. Its `example.org` term namespace is an example vocabulary: select a stable deployment vocabulary before production. Set the same context URL in the template and credential configuration. Base64-encode the final template into `vcTemplate`, set the deployed issuer DID/key configuration, and register the configuration through the existing credential-configuration API. The example configuration is a starting artifact, not an automatic database migration.

The ERL template helper serializes the subject with a JSON serializer, preserving decimals, nulls and escaping. It sets expiration after generic lifetime defaults; missing or expired `valid_to` fails before signing. Fines are the latest non-deleted configured fines charge amount, not a payment or sum calculation.

Use Redis 6.2 or newer for shared pre-auth transactions. All instances need the same cache prefix and serialization; environments need distinct prefixes. Configure code/offer TTLs at least as long as the permitted offer lifetime and transaction TTL at least as long as wallet access-token lifetime. Atomic `GETDEL` replaces JVM-local consumption. Validation failures do not consume codes; a failure after consumption requires a fresh offer.

Offer creation uses the private ClusterIP URL and a service JWT. Generated offers, discovery and wallet operations use the public issuer URL. With Istio enabled, Helm denies public offer creation before catch-all routing and applies a workload authorization policy. Set `preAuth.allowedPrincipals` to approved ERL workload identities and ensure sidecars and mesh mTLS identify callers. An empty allowlist denies all offer creation. If Istio is disabled, supply equivalent path and workload/network restrictions in the deployment gateway; JWT validation alone does not establish private exposure.

Run MySQL and Redis integration tests with Docker available. MySQL tests use 8.0.32 and synthetic fixtures. The test kit contains the isolated full-schema setup and wallet runner. No shared DMT schema changes are required. Before introducing the new cache contract into an existing environment, drain legacy outstanding offers that contain complete claims; later compatible provider mapping changes use the current route and require no provider snapshot.
