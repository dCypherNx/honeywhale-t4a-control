package br.com.t4acontrol.backend;

/** Neutral task scheduler used by the backend instead of Android Handler APIs. */
public interface Scheduler {
  void post(Runnable task);
  void postDelayed(Runnable task, long delayMillis);
  void cancel(Runnable task);
  void cancelAll();
}
