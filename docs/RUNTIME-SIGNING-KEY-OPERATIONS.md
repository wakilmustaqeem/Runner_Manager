# Runtime signing key operations

## Key identity

- Algorithm: ECDSA P-256 (secp256r1)
- Signature format expected by the existing verifier: SHA256withECDSA over the exact manifest bytes.
- Public-key fingerprint (SHA-256 of DER-encoded SubjectPublicKeyInfo): `5a4e0b507415d51bf4d13b351c4c2d4e6a13fdb41cb0a681f9d77279eed5ccac`
- Public key resource: `app/src/main/res/raw/runtime_signing_public.pem`

## Private key rules

The private key is not part of this repository. Store the generated `runtime-signing-private.pem` offline in encrypted storage with a separate backup, or move it into an approved secret manager before using it to sign anything. Do not paste it into issues, chat, source code, APK assets, logs, or Actions output. If using GitHub Actions, add it through the repository's secret settings only after selecting and reviewing a signing workflow; no such workflow is configured by this change.

## Important trust boundary

The public key is only a candidate pin until the private key has been safely transferred to owner-controlled storage and the fingerprint above has been independently checked. The verifier must remain fail-closed when there is no valid signature. This commit does not wire the key into an installer, create or sign a runtime manifest, download an archive, or activate a runtime.

Before the key is used for a release:
1. Independently verify the public-key fingerprint.
2. Create a canonical manifest format and define exactly which bytes are signed.
3. Sign a test manifest outside the repository and verify it with the app verifier.
4. Test tampering, missing signatures, malformed signatures, unknown keys, and key rotation.
5. Keep private-key access separate from routine builds; never expose secrets to untrusted pull-request jobs.
6. Document recovery and rotation before the first public release.

## Key generation reference

For a new key on a trusted offline machine:

```sh
openssl ecparam -name prime256v1 -genkey -noout -out runtime-signing-private.pem
openssl pkey -in runtime-signing-private.pem -pubout -out runtime-signing-public.pem
openssl pkey -pubin -in runtime-signing-public.pem -outform DER | openssl dgst -sha256
```

The private key must stay outside version control. Replacing it later requires a deliberate public-key rotation and migration plan.
