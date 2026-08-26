package br.com.t4acontrol.backend;

/** Account, inventory and pairing boundary. It is not used to exchange runtime DPS. */
public interface T4AProvisioner {
  /** Returns the restored account identifier, or {@code null} when no session exists. */
  String currentAccount();

  void login(
      String countryCode,
      String email,
      String password,
      T4AContracts.Callback<String> callback);

  /** Loads the primary home used by this single-device application. An id of zero means no home. */
  void loadPrimaryHome(T4AContracts.Callback<T4AContracts.Home> callback);

  void startDiscovery(long timeoutMs, T4AContracts.DiscoveryListener listener);

  void stopDiscovery();

  /** Performs all provider-specific token acquisition and device activation. */
  void pair(
      long homeId,
      T4AContracts.DiscoveredDevice device,
      T4AContracts.Callback<T4AContracts.Device> callback);

  void remove(String deviceId, T4AContracts.ResultCallback callback);

  void destroy();
}
