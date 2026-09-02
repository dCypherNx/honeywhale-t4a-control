package br.com.t4acontrol.navigation;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import br.com.t4acontrol.BuildConfig;
import br.com.t4acontrol.backend.navigation.RoutePlanner;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Builds the Google Routes adapter with Android application identity headers. */
public final class GoogleRoutesPlannerFactory {
  private GoogleRoutesPlannerFactory() {}

  public static RoutePlanner create(Context context) throws IllegalStateException {
    Context appContext = context.getApplicationContext();
    String packageName = appContext.getPackageName();
    String certSha1 = signingCertificateSha1(appContext);
    return new GoogleRoutesRoutePlanner(
        BuildConfig.GOOGLE_ROUTES_API_KEY,
        packageName,
        certSha1);
  }

  private static String signingCertificateSha1(Context context) {
    try {
      PackageInfo packageInfo = context.getPackageManager().getPackageInfo(
          context.getPackageName(),
          PackageManager.GET_SIGNING_CERTIFICATES);
      if (packageInfo.signingInfo == null) {
        throw new IllegalStateException("Android signing information is unavailable");
      }

      Signature[] signatures = packageInfo.signingInfo.hasMultipleSigners()
          ? packageInfo.signingInfo.getApkContentsSigners()
          : packageInfo.signingInfo.getSigningCertificateHistory();
      if (signatures == null || signatures.length == 0) {
        throw new IllegalStateException("Android signing certificate is unavailable");
      }

      byte[] digest = MessageDigest.getInstance("SHA-1").digest(signatures[0].toByteArray());
      StringBuilder result = new StringBuilder();
      for (byte value : digest) {
        if (result.length() > 0) {
          result.append(':');
        }
        result.append(String.format("%02X", value & 0xff));
      }
      return result.toString();
    } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException ex) {
      throw new IllegalStateException("Unable to read Android signing certificate", ex);
    }
  }
}
