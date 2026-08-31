package br.com.t4acontrol.backend;

import com.thingclips.smart.home.sdk.ThingHomeSdk;
import com.thingclips.smart.sdk.api.IResultCallback;
import com.thingclips.smart.sdk.api.IThingDevice;

/**
 * Tuya-only provisioning adapter kept independent from the runtime transport.
 *
 * <p>Account, home, discovery and pairing behavior continue to use the validated
 * {@link TuyaT4APlatform}. Device removal deliberately creates its own Tuya device handle instead
 * of relying on a transport-attached runtime handle. This keeps {@link T4AProvisioner} usable when
 * {@link T4ATransport} is later replaced by a native BLE implementation.
 */
public final class TuyaT4AProvisioner implements T4AProvisioner {
  private final TuyaT4APlatform platform = new TuyaT4APlatform();

  @Override
  public String currentAccount() {
    return platform.currentAccount();
  }

  @Override
  public void login(
      String countryCode,
      String email,
      String password,
      T4AContracts.Callback<String> callback) {
    platform.login(countryCode, email, password, callback);
  }

  @Override
  public void loadPrimaryHome(T4AContracts.Callback<T4AContracts.Home> callback) {
    platform.loadPrimaryHome(callback);
  }

  @Override
  public void startDiscovery(long timeoutMs, T4AContracts.DiscoveryListener listener) {
    platform.startDiscovery(timeoutMs, listener);
  }

  @Override
  public void stopDiscovery() {
    platform.stopDiscovery();
  }

  @Override
  public void pair(
      long homeId,
      T4AContracts.DiscoveredDevice device,
      T4AContracts.Callback<T4AContracts.Device> callback) {
    platform.pair(homeId, device, callback);
  }

  @Override
  public void remove(String deviceId, T4AContracts.ResultCallback callback) {
    IThingDevice removalDevice = ThingHomeSdk.newDeviceInstance(deviceId);
    if (removalDevice == null) {
      callback.onError("DEVICE_UNAVAILABLE", "Não foi possível abrir o dispositivo para remoção");
      return;
    }
    removalDevice.removeDevice(
        new IResultCallback() {
          @Override
          public void onSuccess() {
            removalDevice.onDestroy();
            callback.onSuccess();
          }

          @Override
          public void onError(String code, String error) {
            removalDevice.onDestroy();
            callback.onError(code, error);
          }
        });
  }

  @Override
  public void destroy() {
    platform.destroy();
  }
}
