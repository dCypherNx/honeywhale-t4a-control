package br.com.t4acontrol;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.WindowManager;
import br.com.t4acontrol.backend.T4ABackend;
import br.com.t4acontrol.backend.T4ASdk;
import br.com.t4acontrol.backend.TuyaT4APlatform;

public final class T4AApplication extends Application {
  private static final String PREFS = "t4a_settings";
  private static final String PREF_KEEP_SCREEN_ON = "keep_screen_on";

  /** Application composition root: the domain receives replaceable provisioning and transport. */
  public T4ABackend createBackend(T4ABackend.Listener listener) {
    TuyaT4APlatform platform = new TuyaT4APlatform();
    return new T4ABackend(this, platform, platform, listener);
  }

  @Override
  public void onCreate() {
    super.onCreate();
    T4ASdk.initialize(this, BuildConfig.DEBUG);
    registerActivityLifecycleCallbacks(
        new ActivityLifecycleCallbacks() {
          @Override
          public void onActivityResumed(Activity activity) {
            if (!(activity instanceof MainActivity)) return;
            boolean keepOn =
                getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_KEEP_SCREEN_ON, true);
            if (keepOn)
              activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
          }

          @Override
          public void onActivityPaused(Activity activity) {
            if (activity instanceof MainActivity)
              activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
          }

          @Override
          public void onActivityCreated(Activity activity, Bundle state) {}

          @Override
          public void onActivityStarted(Activity activity) {}

          @Override
          public void onActivityStopped(Activity activity) {}

          @Override
          public void onActivitySaveInstanceState(Activity activity, Bundle state) {}

          @Override
          public void onActivityDestroyed(Activity activity) {}
        });
  }

  @Override
  public void onTerminate() {
    T4ASdk.destroy();
    super.onTerminate();
  }
}
