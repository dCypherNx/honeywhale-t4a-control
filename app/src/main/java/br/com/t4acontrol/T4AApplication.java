package br.com.t4acontrol;

import android.app.Application;
import br.com.t4acontrol.backend.T4ASdk;

public final class T4AApplication extends Application {
  @Override
  public void onCreate() {
    super.onCreate();
    T4ASdk.initialize(this, BuildConfig.DEBUG);
  }

  @Override
  public void onTerminate() {
    T4ASdk.destroy();
    super.onTerminate();
  }
}
