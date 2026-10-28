# Return a PixelPass QR payload beside the signed ERL VC

Updated: 2026-10-27.

Status: implemented in the local checkout and running lab. Focused Java tests,
HTTP simulation, browser tests, real issuance and independent PNG decoding pass.
Inji Verify Collab upload and printed/physical-device scans remain pending; this
is not yet a completed external acceptance run.

## Implementation evidence — 2026-10-27

- Existing JVM PixelPass 0.8.0 is reused; no production Node helper or new port.
- All three issuance routes share the opt-in handling. False/absent skips encoding;
  QR encoding failure preserves the issued VC and does not repeat issuance.
- `/credential-qr` on port 18087 calls one platform `/api/credentials` route.
  Shared holder-proof logic and the new in-memory `scripts/preauth_client.py`
  support the flow; the original RSA CLI continues working.
- A real WP-CAB-5678 issuance produced a 1,283-character PixelPass payload and a
  version 24 QR at level M. Signature, validity, licence details and P-256 holder
  binding passed. Apple Vision decoded the PNG, and JavaScript PixelPass recovered
  the exact VC, including its proof.
- A live false-flag request returned a verified VC with neither QR field. Existing
  RSA wallet issuance, tamper rejection and consumed-code replay rejection passed.
- See `docs/Credential-QR-Response.md` for the implemented contract and the test
  kit's `vc-svg-viewer/docs/VALIDATION.md` for validation commands and limitations.
- Browser access was unavailable in the agent session, so Inji Verify Collab
  upload could not be performed. Print/phone acceptance also requires a device.

The design and acceptance checklist below remain the implementation reference.

## Goal and design

When the platform requests an ERL credential with `include_credential_qr: true`,
Inji Certify automatically encodes the complete signed VC using its existing JVM
PixelPass library and returns the string beside `credential`. The demo application
renders that string as a downloadable QR PNG for display or printing. The recipient
does not need a digital device.

This replaces the earlier Node subprocess design and separate platform issuance
and encoding endpoints. Encoding belongs in Certify; image rendering belongs in
the application. No additional runtime or HTTP port is needed.

```text
Operator enters vehicle number and issue timestamp
                    ↓
Platform creates and redeems a credential offer
                    ↓
Platform requests credential with include_credential_qr: true
                    ↓
Certify issues and signs the ERL VC, then encodes it with PixelPass
                    ↓
One response: credential + credential_qr
                    ↓
Platform verifies the VC and renders credential_qr.data as a PNG
                    ↓
Operator downloads or prints it; verifier uses Inji Verify
```

The target is [Inji Verify Collab](https://injiverify.collab.mosip.net/).
This is an issued-credential QR. Keep the existing credential-offer QR workflow
for downloading a credential into Inji Wallet. No wallet source change is planned;
importing this printed VC QR into a wallet is outside scope.

## Existing capabilities in this checkout

- `certify-service/pom.xml` declares `io.inji:pixelpass-jar:0.8.0`.
- `AppConfig.java` provides a `PixelPass` Spring bean.
- `CertifyIssuanceServiceImpl.java` uses PixelPass for configured Claim 169 QR
  entries. Those entries precede the final VC proof and represent a different
  payload from the complete signed VC requested here.
- `CredentialResponse.java` currently has no `credential_qr` field.
- The current, draft 12 and draft 11 controller routes all call
  `VCIssuanceService.getCredential(...)`.

Reuse the dependency and bean. Preserve existing Claim 169 behavior.

## API contract

Use the existing `POST /v1/certify/issuance/credential` endpoint. Apply the same
extension to `/v1/certify/issuance/vd12/credential` and
`/v1/certify/issuance/vd11/credential` through shared handling.

Add an optional Boolean request property:

```json
{
  "format": "ldp_vc",
  "credential_definition": {
    "type": ["VerifiableCredential", "<configured ERL credential type>"]
  },
  "proof": {
    "proof_type": "jwt",
    "jwt": "<fresh holder proof JWT>"
  },
  "include_credential_qr": true
}
```

This is illustrative; retain the configured ERL credential definition and normal
token/proof requirements. Put the flag in the JSON body, avoiding dependence on
query forwarding through the lab gateway.

Absent or false means all new response fields are omitted. Initially support a
true flag only for `ldp_vc`; reject unsupported formats before invoking issuance.
The demo uses it for the configured ERL VC.

Successful immediate issuance adds this response property:

```json
{
  "credential": {
    "@context": ["<existing contexts>"],
    "type": ["<existing types>"],
    "credentialSubject": {"<existing claims>": "<values>"},
    "proof": {"<existing proof fields>": "<values>"}
  },
  "credential_qr": {
    "encoding": "pixelpass",
    "data": "<complete signed VC encoded by PixelPass>"
  }
}
```

This is a structural example, not a usable VC. Preserve existing response fields,
including draft-specific `format` and nonce fields. `data` is the text placed in a
QR, not a PNG, URL or Base64 image. This is a custom response extension; document
it and test client compatibility.

Keep `credential_qr` outside the credential. Do not change its JSON-LD contexts,
claims, issuer, holder identifier or proof after signing. Embedding the encoded
complete VC inside itself would also create a recursive payload.

## Certify implementation and files

Paths are relative to `inji-certify/`.

| File | Planned change |
|---|---|
| `certify-core/src/main/java/io/mosip/certify/core/dto/CredentialRequest.java` | Add nullable `includeCredentialQr`, mapped to `include_credential_qr`. |
| `certify-core/src/main/java/io/mosip/certify/core/dto/CredentialResponse.java` | Add nullable `credentialQr` and `credentialQrError`, mapped to `credential_qr` and `credential_qr_error`; omit null values. |
| `certify-core/src/main/java/io/mosip/certify/core/dto/CredentialQr.java` (new) | Define `encoding` and `data`. |
| `certify-service/src/main/java/io/mosip/certify/services/CredentialQrService.java` (new) | Serialize and encode the final credential using the injected PixelPass bean and configured ObjectMapper. |
| `certify-service/src/main/java/io/mosip/certify/controller/VCIssuanceController.java` | Share opt-in validation and response enrichment across all three issuance routes. |
| `docs/inji-certify-openapi.yaml` | Document the flag, response properties, supported format and partial QR failure behavior. |
| `certify-service/src/test/java/io/mosip/certify/services/CredentialQrServiceTest.java` (new) | Test real encoding, round-trip preservation and encoding failure. |
| `certify-service/src/test/java/io/mosip/certify/controller/VCIssuanceControllerTest.java` | Test opt-in behavior, all routes, unsupported formats and issuance/QR failures. |

Validate the opt-in format in the shared controller path before invoking issuance,
covering either configured issuance implementation. Reuse existing invalid-request
conventions; add shared error constants/messages only where required by the
repository. Avoid duplicating encoding in the two issuance implementations.

After `getCredential(...)` succeeds and returns an actual signed credential:

1. Serialize only `response.getCredential()`, including the completed proof, to
   compact JSON using the same serialization configuration as the HTTP response.
2. Call `pixelPass.generateQRData(signedVcJson, "")` with an empty header.
3. Attach `credential_qr` without modifying the credential object.

Do not encode the response wrapper or a deferred response's acceptance token.
If issuance is deferred, omit QR fields until a signed credential exists; extending
any separate deferred retrieval endpoint is outside this initial scope.

### Failure behavior

Issuance errors retain existing behavior. If serialization or encoding fails after
successful issuance, preserve the successful credential response, omit
`credential_qr`, and add a sanitized string code such as
`"credential_qr_error": "encoding_failed"`. The success and error QR fields are
mutually exclusive. Do not log credentials, holder proofs or access tokens.

The application must distinguish “credential issued; QR unavailable” from issuance
failure. Do not automatically redeem another offer or reissue to recover from QR
failure. A separate encoding-retry API is outside scope; retain the VC for download.
PNG rendering can be retried from an existing payload without contacting Certify.

## Encoding, size and compatibility

The target representation is JSON → CBOR → zlib → Base45, with an empty header,
following the wallet's complete-credential QR flow. Prove that the pinned JVM
library's JSON path is compatible with the JavaScript decoder used by Inji Verify
before accepting implementation. Existing Claim 169 usage alone does not establish
this. Resolve any library/API mismatch before proceeding; do not silently introduce
a custom encoder.

- Encode only the complete signed credential. Exclude tokens, offer data and
  response metadata. Preserve meaningful Base45 whitespace.
- Use a fresh ES256/P-256 holder key for the new platform issuance flow when
  supported by issuer metadata; otherwise report unsupported configuration. This
  reduces the holder identifier before issuance. Preserve the CLI's RSA behavior
  and never rewrite an already-signed subject identifier.
- Base45 represents compressed bytes using QR-friendly characters; it does not
  itself compress them. Measure actual output rather than promising a final size.
- Render using QR alphanumeric mode, automatic version selection, error correction
  M and a four-module white border. These rendering settings are demo choices;
  the payload convention follows the wallet.
- Detect image capacity overflow and show a clear error. Preserve the VC and
  encoded data; do not remove signed fields or substitute an offer URL.

The inspected RSA sample was 1,742 bytes of compact JSON with a 536-character
holder identifier. A JavaScript PixelPass example produced 1,755 encoded characters.
These sample measurements do not establish JVM compatibility or the planned
P-256 credential's size.

Reference implementations:

- [Inji Wallet complete-credential QR flow](https://github.com/inji/inji-wallet/blob/2b37c058a9ae10216e099dce78fbda57069d41ef/components/QrCodeOverlay.tsx#L38-L44)
- [PixelPass source](https://github.com/inji/pixelpass)

## Demo application integration

After validating the Certify extension, update the application on port 18087 in
`dmt-certify-test-kit/`:

| File or area | Planned responsibility |
|---|---|
| `scripts/run_preauth_wallet.py` and a reusable issuance helper | Reuse discovery, token exchange and proof creation, preserving CLI behavior; support the platform's opt-in flow. |
| `vc-svg-viewer/viewer/server.py` | Add `/credential-qr` and one same-origin `POST /api/credentials` orchestration route. |
| `vc-svg-viewer/viewer/credentials.py` (new) | Request issuance with the flag, verify the VC and render the returned payload using the existing Python QR dependency. |
| `vc-svg-viewer/viewer/assets/credential-qr.html` and `credential-qr.js` (new) | Vehicle/date inputs, progress, credential summary, PNG preview/download and partial-failure handling. |
| Viewer tests and demo playbook/architecture documents | Cover the new flow and explain the encoding/rendering responsibilities. |

The platform endpoint returns the verified VC, Certify QR payload, verification
summary and, when rendering succeeds, a PNG data URL. Rendering failure may return
the VC and payload without a PNG. There is no new Certify endpoint and no separate
platform `/api/credential-qrs` endpoint in this plan.

Read addresses and credentials from the lab environment. Verify issuer, signature,
validity, requested licence details and holder binding before displaying success.
Discard the temporary private holder key afterward. Disable duplicate submissions,
invalidate stale results when inputs change, and display credential validity rather
than offer expiry. Retain results only for the active operation/page. No PDF layout,
archive or history is included.

## Verification documents

Reuse public documents managed by the test kit's `erl-demo-config/`:

| Resource | Address |
|---|---|
| Issuer DID | `did:web:bathiya-94.github.io:erl-demo-config:issuer` |
| DID document | `https://bathiya-94.github.io/erl-demo-config/issuer/did.json` |
| ERL JSON-LD context | `https://bathiya-94.github.io/erl-demo-config/contexts/erl.json` |

Before live acceptance, confirm reachability and that the active signing key is
authorized by the published DID. Repeat after signing-key resets and retain public
keys required for previously issued credentials. Verification may require internet
access to these documents.

## Delivery order and acceptance

1. Prove JVM PixelPass output decodes with the compatible JavaScript PixelPass
   implementation to the identical JSON credential, including proof.
2. Implement DTOs, shared controller handling, encoding service and OpenAPI contract.
   Test opt-out compatibility, all routes, supported formats, deferred responses
   and encoding failure without repeating issuance.
3. Issue a real ERL VC with the flag. Decode its payload, compare the complete VC
   and verify the original issuer signature. Cover Sinhala, decimals, nulls and
   tampering. Confirm the serialized credential is unchanged.
4. Integrate the UI. Test duplicate clicks, timeouts, nonce-refresh behavior,
   partial failures and rendering retry. Use existing Postman simulation for
   upstream failures; avoid automatic retries of consumed exchanges or issuance
   requests with uncertain outcomes.
5. Measure JSON size, payload length and QR version for representative records and
   the new P-256 flow. Exercise oversized payload handling. Decode the actual PNG
   independently, then unpack PixelPass and verify the recovered VC.
6. Upload fresh PNGs to Inji Verify Collab and confirm expected claims. Scan a
   printed sample. Confirm ordinary issuance and the existing Inji Wallet offer
   scan/download flow still work. Record actual device results separately from
   automated tests.

Completion requires actual Inji Verify acceptance, not merely a successful
encoding call or generated PNG.

## Demo assumptions

The platform acts on the recipient's behalf. A printed QR proves signed licence
data; it does not prove possession of the temporary holder key or identify the
person presenting the paper. Anyone scanning it can read its contents. This is a
local demo with session-only results; Certify retains its existing issuance ledger.
