$ErrorActionPreference = 'Stop'

# Production-line invariant:
# - Tuya/ThingClips is allowed only for provisioning/account/credential acquisition.
# - Runtime transport must remain native BLE only.
# - Experimental transports/probes from the BLE investigation lineage must never leak into master.

$applicationPath = 'app/src/main/java/br/com/t4acontrol/T4AApplication.java'
if (-not (Test-Path -LiteralPath $applicationPath -PathType Leaf)) {
  throw "Missing production composition root: $applicationPath"
}

$application = Get-Content -LiteralPath $applicationPath -Raw

$requiredComposition = @(
  'new TuyaT4AProvisioner()',
  'new UnavailableRuntimeTransport(listener::onRawLog)',
  'new NativeBleTransport(this, unavailableRuntime, listener::onRawLog, false)'
)
foreach ($required in $requiredComposition) {
  if (-not $application.Contains($required)) {
    throw "Production composition invariant missing: $required"
  }
}

$forbiddenCompositionPatterns = @(
  'new\s+TuyaT4APlatform\s*\(',
  'DelayedFallbackTransport',
  'DirectBleFallbackTransport',
  'SdkIntrospectionTransport',
  'DeviceInfoReplayLoggingTransport',
  'BleProtocolMetadataProbeTransport',
  'ThingBle[A-Za-z0-9_]*Probe',
  'ThingBleProtocolIntrospector',
  'ThingBleWireSampler'
)
foreach ($pattern in $forbiddenCompositionPatterns) {
  if ($application -match $pattern) {
    throw "Experimental/provider runtime leaked into production composition: $pattern"
  }
}

# Tuya runtime adapter is allowed to exist because TuyaT4AProvisioner uses it internally.
# It must not be referenced from the Android production runtime graph.
$appSources = Get-ChildItem 'app/src/main/java' -Recurse -File |
  Where-Object { $_.Extension -in '.java', '.kt' }
$runtimeTuyaLeaks = $appSources | Select-String -Pattern 'TuyaT4APlatform|DelayedFallbackTransport|DirectBleFallbackTransport|SdkIntrospectionTransport|DeviceInfoReplayLoggingTransport|BleProtocolMetadataProbeTransport|ThingBle[A-Za-z0-9_]*(Probe|Introspector|Sampler)'
if ($runtimeTuyaLeaks) {
  $runtimeTuyaLeaks | ForEach-Object { Write-Error $_.ToString() }
  throw 'Experimental Tuya/BLE runtime references are forbidden in app/src/main.'
}

# Provider SDK imports remain confined to the explicitly approved backend adapters.
$allowedProviderFiles = @(
  (Resolve-Path 'backend/src/main/java/br/com/t4acontrol/backend/TuyaT4APlatform.java').Path,
  (Resolve-Path 'backend/src/main/java/br/com/t4acontrol/backend/TuyaT4AProvisioner.java').Path,
  (Resolve-Path 'backend/src/main/java/br/com/t4acontrol/backend/T4ASdk.java').Path
)
$providerLeaks = Get-ChildItem 'backend/src/main/java' -Recurse -Filter '*.java' |
  Where-Object { $_.FullName -notin $allowedProviderFiles } |
  Select-String -Pattern 'com\.thingclips\.|ThingHomeSdk|TuyaT4APlatform'
if ($providerLeaks) {
  $providerLeaks | ForEach-Object { Write-Error $_.ToString() }
  throw 'Tuya/ThingClips provider runtime escaped the approved provisioning adapters.'
}

Write-Output 'Production runtime boundary OK: native BLE runtime; Tuya confined to provisioning/SDK lifecycle.'
