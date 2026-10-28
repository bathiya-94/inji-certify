# Complete-credential QR response extension

The credential issuance routes support an optional custom extension for `ldp_vc`:
add `"include_credential_qr": true` to the normal JSON request body. Authorization,
credential definition and holder proof requirements are unchanged. The extension
is available on `/issuance/credential`, `/issuance/vd11/credential` and
`/issuance/vd12/credential` under the configured Certify context path.

After successful immediate issuance, Certify encodes only the completed signed VC
with the existing JVM PixelPass 0.8.0 bean and an empty header. It returns:

```json
{
  "credential": {"...": "complete original signed credential"},
  "credential_qr": {
    "encoding": "pixelpass",
    "data": "<Base45 text to render as a QR>"
  }
}
```

This is a structural example. The encoded payload is JSON → CBOR → zlib → Base45;
it is not an image. Render the exact `data` string, including spaces, with a QR
library. Check its capacity at the selected error correction level. An application
may use alphanumeric mode, level M and a four-module white border, as in the demo.
Certify does not guarantee every credential fits in one QR.

`credential_qr` is outside the signed credential. No claims, contexts, holder IDs
or signatures are rewritten. The opt-in does not enable, disable or alter existing
configured Claim 169 QR entries.

If absent or false, the flag does not invoke encoding and both QR response fields
are omitted. A true flag with another credential format is rejected before
issuance with `unsupported_credential_format`. A deferred response with no
credential omits QR fields; no deferred retrieval extension is implemented.

If encoding fails after issuance, Certify still returns the issued credential and
`"credential_qr_error": "encoding_failed"`, omitting `credential_qr`. Do not repeat
issuance to recover from this error. Download or retain the issued credential.
Existing issuance errors retain their normal behavior. No new endpoint is added.

These fields are a custom API extension, not standard OpenID4VCI QR parameters.
The OpenAPI contract describes them. Compatibility with each consuming wallet or
verifier must be checked for the deployed versions.

Run the focused tests with Java 21:

```sh
mvn -B -ntp -pl certify-service -am test \
  -Dtest=CredentialQrServiceTest,CredentialQrControllerTest,VCIssuanceControllerTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dgpg.skip=true
```

The companion test kit provides `/credential-qr` on port 18087, a real HTTP
Postman simulation, browser tests and `preauth/verify_credential_qr.mjs` for
JVM-to-JavaScript decoding checks. It acts on the recipient's behalf using an
operation-local P-256 holder key, verifies the VC and renders the returned text.
A printed QR verifies signed claims; it does not demonstrate possession of that
holder key or identify the person presenting the printout.
