package br.com.t4acontrol.backend.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Fine-grained route-leg metrics independent from a particular routing UI. */
public final class RouteLegMetadata {
  public final double distanceMeters;
  public final double durationSeconds;
  public final double weight;
  public final String summary;
  public final List<Double> segmentDistancesMeters;
  public final List<Double> segmentDurationsSeconds;
  public final List<Double> segmentWeights;
  public final List<Double> segmentSpeedsMetersPerSecond;
  public final List<Integer> datasourceIndexes;
  public final List<String> datasourceNames;
  public final List<Long> nodeIds;

  public RouteLegMetadata(
      double distanceMeters,
      double durationSeconds,
      double weight,
      String summary,
      List<Double> segmentDistancesMeters,
      List<Double> segmentDurationsSeconds,
      List<Double> segmentWeights,
      List<Double> segmentSpeedsMetersPerSecond,
      List<Integer> datasourceIndexes,
      List<String> datasourceNames,
      List<Long> nodeIds) {
    this.distanceMeters = distanceMeters;
    this.durationSeconds = durationSeconds;
    this.weight = weight;
    this.summary = summary;
    this.segmentDistancesMeters = immutable(segmentDistancesMeters);
    this.segmentDurationsSeconds = immutable(segmentDurationsSeconds);
    this.segmentWeights = immutable(segmentWeights);
    this.segmentSpeedsMetersPerSecond = immutable(segmentSpeedsMetersPerSecond);
    this.datasourceIndexes = immutable(datasourceIndexes);
    this.datasourceNames = immutable(datasourceNames);
    this.nodeIds = immutable(nodeIds);
  }

  private static <T> List<T> immutable(List<T> source) {
    return Collections.unmodifiableList(new ArrayList<>(source));
  }
}
