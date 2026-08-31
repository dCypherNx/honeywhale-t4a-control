package br.com.t4acontrol.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RssiCalibrationTest {
  @Test public void derivesWeightedBandsFromMinusFifteenAndMinusNinety() {
    RssiCalibration calibration = new RssiCalibration(-15, -90);
    RssiCalibration.Snapshot snapshot = calibration.snapshot();

    assertTrue(snapshot.ready);
    assertEquals(Integer.valueOf(-82), snapshot.stableWorst);
    assertEquals(Integer.valueOf(-48), snapshot.shortMin);
    assertEquals(Integer.valueOf(-69), snapshot.mediumMin);
    assertEquals(Integer.valueOf(-82), snapshot.longMin);

    int shortWidth = snapshot.best - snapshot.shortMin;
    int mediumWidth = snapshot.shortMin - snapshot.mediumMin;
    int longWidth = snapshot.mediumMin - snapshot.longMin;
    assertTrue(shortWidth > mediumWidth);
    assertTrue(mediumWidth > longWidth);
  }

  @Test public void singleOutlierCannotRedefineExtreme() {
    RssiCalibration calibration = new RssiCalibration(-20, -60);

    calibration.observe(-50);
    calibration.observe(-50);
    calibration.observe(-100);

    RssiCalibration.Snapshot snapshot = calibration.snapshot();
    assertEquals(Integer.valueOf(-20), snapshot.best);
    assertEquals(Integer.valueOf(-60), snapshot.worst);
  }

  @Test public void medianOfThreeConsecutiveSamplesCanExpandObservedRange() {
    RssiCalibration calibration = new RssiCalibration(-20, -60);

    calibration.observe(-85);
    calibration.observe(-86);
    assertTrue(calibration.observe(-84));
    RssiCalibration.Snapshot snapshot = calibration.snapshot();
    assertEquals(Integer.valueOf(-85), snapshot.worst);
  }
}
