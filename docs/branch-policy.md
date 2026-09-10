# Branch promotion policy

## Long-lived lines

The repository intentionally keeps three long-lived lines:

- `master`: stable production line.
- `feature/phase-6-navigation-domain`: active integration line while phase 6 is open.
- `feature/direct-ble-transport-fallback`: BLE investigation laboratory. It is historical/experimental and must never be merged wholesale into `master`.

## Approved work and phase 6

While phase 6 is open, any feature or fix that has been approved for the product must be present in `feature/phase-6-navigation-domain` before the next physical phase-6 build. Approved work may arrive through `master` first or be merged/cherry-picked directly into phase 6 when that is the intended integration path.

Completed transient feature/fix branches should be deleted after their approved commits are reachable from the appropriate integration line. Investigation branches are exempt when explicitly retained for future research.

## BLE/Tuya investigation quarantine

`feature/direct-ble-transport-fallback` and branches derived from it may contain experimental transports, probes, SDK introspection, provider-runtime fallbacks and other diagnostic code.

That experimental runtime must not return to `master` or to normal product branches.

A feature developed from the BLE laboratory may be promoted only after it is separated from the experimental lineage at the code level. The promoted change must:

1. preserve `NativeBleTransport` as the only production runtime transport;
2. keep Tuya/ThingClips confined to provisioning/account/credential acquisition and SDK lifecycle already present in the product;
3. contain no delayed Tuya runtime fallback, Tuya runtime transport composition, SDK introspection transport, replay/probe transport or ThingBle investigation probe in `app/src/main`;
4. pass `Verify Tuya boundary`, including `scripts/VerifyProductionBleRuntime.ps1`;
5. be reviewed as a normal feature/fix against the current product integration line, not merged wholesale from the investigation branch.

## Allowed Tuya footprint

The current approved product boundary is intentional:

- `TuyaT4AProvisioner` may use `TuyaT4APlatform` internally for account, discovery, pairing/removal and credential/provisioning responsibilities.
- `T4ASdk` may initialize/destroy the provider SDK because provisioning still depends on it.
- production session runtime is native BLE only.

This allowed provisioning footprint is not permission to restore Tuya as a runtime transport.

## Enforcement

`Verify Tuya boundary` is a required check on pull requests to `master`. It executes `scripts/VerifyProductionBleRuntime.ps1`, which verifies the production composition root and rejects known experimental/provider runtime references outside the approved provisioning adapters.

The rule is deliberately asymmetric: experimentation may branch out from the BLE laboratory, but experimental runtime code cannot flow back into the production line.
