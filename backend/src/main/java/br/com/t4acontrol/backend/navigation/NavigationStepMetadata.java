package br.com.t4acontrol.backend.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Provider-neutral rich metadata for a single routing step. */
public final class NavigationStepMetadata {
  public enum TurnSeverity { STRAIGHT, SLIGHT, NORMAL, SHARP, U_TURN, UNKNOWN }
  public enum Context {
    TURN, NEW_NAME, DEPART, ARRIVE, MERGE, ON_RAMP, OFF_RAMP, FORK, END_OF_ROAD,
    CONTINUE, ROUNDABOUT, ROTARY, ROUNDABOUT_TURN, NOTIFICATION,
    EXIT_ROUNDABOUT, EXIT_ROTARY, UNKNOWN
  }

  public static final class Lane {
    public final List<String> indications;
    public final boolean valid;
    public final boolean active;

    public Lane(List<String> indications, boolean valid, boolean active) {
      this.indications = Collections.unmodifiableList(new ArrayList<>(indications));
      this.valid = valid;
      this.active = active;
    }
  }

  public static final class Intersection {
    public final double latitude;
    public final double longitude;
    public final List<Integer> bearings;
    public final List<Boolean> entry;
    public final int inIndex;
    public final int outIndex;
    public final List<String> classes;
    public final List<Lane> lanes;

    public Intersection(
        double latitude,
        double longitude,
        List<Integer> bearings,
        List<Boolean> entry,
        int inIndex,
        int outIndex,
        List<String> classes,
        List<Lane> lanes) {
      this.latitude = latitude;
      this.longitude = longitude;
      this.bearings = Collections.unmodifiableList(new ArrayList<>(bearings));
      this.entry = Collections.unmodifiableList(new ArrayList<>(entry));
      this.inIndex = inIndex;
      this.outIndex = outIndex;
      this.classes = Collections.unmodifiableList(new ArrayList<>(classes));
      this.lanes = Collections.unmodifiableList(new ArrayList<>(lanes));
    }
  }

  public final Context context;
  public final TurnSeverity severity;
  public final String rawType;
  public final String rawModifier;
  public final int bearingBefore;
  public final int bearingAfter;
  public final int roundaboutExit;
  public final String roadName;
  public final String roadRef;
  public final String pronunciation;
  public final String destinations;
  public final String exits;
  public final String mode;
  public final String rotaryName;
  public final String rotaryPronunciation;
  public final String drivingSide;
  public final double durationSeconds;
  public final double weight;
  public final List<GeoPoint> geometry;
  public final List<Intersection> intersections;

  public NavigationStepMetadata(
      Context context,
      TurnSeverity severity,
      String rawType,
      String rawModifier,
      int bearingBefore,
      int bearingAfter,
      int roundaboutExit,
      String roadName,
      String roadRef,
      String pronunciation,
      String destinations,
      String exits,
      String mode,
      String rotaryName,
      String rotaryPronunciation,
      String drivingSide,
      double durationSeconds,
      double weight,
      List<GeoPoint> geometry,
      List<Intersection> intersections) {
    this.context = context;
    this.severity = severity;
    this.rawType = rawType;
    this.rawModifier = rawModifier;
    this.bearingBefore = bearingBefore;
    this.bearingAfter = bearingAfter;
    this.roundaboutExit = roundaboutExit;
    this.roadName = roadName;
    this.roadRef = roadRef;
    this.pronunciation = pronunciation;
    this.destinations = destinations;
    this.exits = exits;
    this.mode = mode;
    this.rotaryName = rotaryName;
    this.rotaryPronunciation = rotaryPronunciation;
    this.drivingSide = drivingSide;
    this.durationSeconds = durationSeconds;
    this.weight = weight;
    this.geometry = Collections.unmodifiableList(new ArrayList<>(geometry));
    this.intersections = Collections.unmodifiableList(new ArrayList<>(intersections));
  }
}
