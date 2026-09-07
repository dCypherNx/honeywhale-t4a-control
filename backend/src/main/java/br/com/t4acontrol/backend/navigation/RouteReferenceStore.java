package br.com.t4acontrol.backend.navigation;

/** Persists the canonical external route reference, never provider-derived route geometry. */
public interface RouteReferenceStore {
  void save(String routeReference);
  String load();
  void clear();
}
