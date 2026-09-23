/*
 * qsim-service — a JMT-backed queueing-network simulation service.
 * Copyright (C) 2026 qsim-service contributors.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */
package qsim.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URL;
import org.junit.jupiter.api.Test;
import qsim.model.MeasureResult;

class SolutionsParserTest {

  private File resource(String path) throws Exception {
    URL u = getClass().getResource(path);
    return new File(u.toURI());
  }

  @Test
  void parsesMeasuresAndMapsTypeAndStation() throws Exception {
    SolutionsParser.Parsed p = new SolutionsParser().parse(resource("/results/mm1.solutions.xml"));
    assertEquals(4, p.measures().size());

    MeasureResult u = p.measures().get(0);
    assertEquals("q", u.station());
    assertEquals("web", u.jobClass());
    assertEquals("utilization", u.type());        // reverse-mapped from "Utilization"
    assertEquals(0.5012, u.mean());
    assertEquals(0.4901, u.lower());
    assertEquals(true, u.success());
    assertEquals(45000, u.samplesAnalyzed());
    assertEquals(0.05, u.alpha());                // significance level, passed through verbatim

    MeasureResult rt = p.measures().get(1);
    assertEquals("fj", rt.station());             // fj__join -> fj (join suffix stripped)
    assertEquals("response-time", rt.type());

    MeasureResult branch = p.measures().get(2);
    assertEquals("fj", branch.station());         // fj__b0 -> fj (branch suffix stripped)
    assertEquals("queue-length", branch.type());

    MeasureResult noStrip = p.measures().get(3);
    assertEquals("queue__buffer", noStrip.station()); // queue__buffer NOT stripped (not a true fork-join suffix)
    assertEquals("utilization", noStrip.type());
  }

  /** The fork-join response time is what a caller asked for as "response-time" on that node. */
  @Test
  void forkJoinResponseTimeReversesToResponseTime() throws Exception {
    SolutionsParser.Parsed p = new SolutionsParser().parse(resource("/results/fork-join.solutions.xml"));
    assertEquals(1, p.measures().size());
    MeasureResult rt = p.measures().get(0);
    assertEquals("fj", rt.station());
    assertEquals("response-time", rt.type());
    assertEquals(0.2884507654809945, rt.mean());
  }

  @Test
  void completedFalseWhenAnyMeasureUnsuccessful() throws Exception {
    SolutionsParser.Parsed p = new SolutionsParser().parse(resource("/results/mm1.solutions.xml"));
    assertFalse(p.completed());                   // second measure (fj__join) has successful="false"
  }

  /**
   * Issue #12: limits are reported verbatim; the parser must NOT reorder them.
   *
   * <p>This fixture records the inverted output the service used to produce. Those limits were never
   * a JMT quirk (as the Task 11 investigation concluded) — {@code getLowerLimit()} is
   * {@code mean - confInt}, so they invert exactly when {@code confInt} is negative, which is what
   * writing {@code 1 - alpha} caused. The old silent swap turned that signal into a plausible-looking
   * interval and hid the defect for months. With alpha written correctly the limits order naturally,
   * so any future inversion is a real regression and must stay visible rather than be normalized away.
   */
  @Test
  void invertedLimitsArePassedThroughNotSilentlyReordered() throws Exception {
    SolutionsParser.Parsed p = new SolutionsParser().parse(resource("/results/inverted-bounds.solutions.xml"));
    assertEquals(1, p.measures().size());

    MeasureResult u = p.measures().get(0);
    assertEquals(0.5250552074712409, u.lower()); // raw lowerLimit attribute, not reordered
    assertEquals(0.4297696235625058, u.upper()); // raw upperLimit attribute, not reordered
  }

  /**
   * Issue #15. An "Arrival Rate" measure is an InverseMeasure: meanValue is 1/E[A] while the samples
   * underneath — and so the mean/variance/standardDeviation attributes — are the interarrival times
   * themselves. qsim reports the time, because that is the quantity the variance beside it describes;
   * a caller wanting the rate has throughput, or 1/mean.
   */
  @Test
  void anArrivalRateMeasureReportsTheInterarrivalTimeNotTheRate() throws Exception {
    SolutionsParser.Parsed p =
        new SolutionsParser().parse(resource("/results/interarrival.solutions.xml"));

    MeasureResult ia = p.measures().get(0);
    assertEquals("interarrival-time", ia.type());
    assertEquals("q1", ia.station());
    assertEquals("web", ia.jobClass());
    assertEquals(3.3440541634186807, ia.mean());          // the `mean` attribute, not meanValue
    assertEquals(10.74734194049205, ia.variance());
    assertEquals(3.2783138868162167, ia.stdDev());
    assertEquals(10240, ia.samplesAnalyzed());
    assertEquals(100, ia.samplesDiscarded());
    assertTrue(ia.success());

    // scv = variance / mean^2 is the caller's to compute, and now it is consistent: reporting
    // meanValue here would make this 118.7 rather than 0.961.
    assertEquals(0.961, ia.variance() / (ia.mean() * ia.mean()), 1e-3);
  }

  /**
   * JMT's confidence interval on an arrival measure is computed on the rate, so it does not bracket
   * the mean qsim reports. Issue #15 accepts a mean with no interval, so the limits are dropped
   * rather than transformed — an interval that does not contain its own point estimate is worse than
   * no interval. (The reciprocal transform is noted as future work in this plan.)
   */
  @Test
  void anArrivalRateMeasureReportsNoConfidenceInterval() throws Exception {
    SolutionsParser.Parsed p =
        new SolutionsParser().parse(resource("/results/interarrival.solutions.xml"));
    MeasureResult ia = p.measures().get(0);
    assertNull(ia.lower(), "the CI is on the rate; it would not bracket the interarrival mean");
    assertNull(ia.upper());
    assertEquals(0.05, ia.alpha(), "alpha and precision still describe the run");
    assertEquals(0.03, ia.precision());
  }

  /**
   * Review Focus 1. Throughput's mean is a rate (correctly — that is the figure callers want) but its
   * verbose statistics are over interdeparture times, so the two cannot sit side by side: a caller
   * computing variance/mean^2 would get 118.7 against a true 0.961. Dropping them is the honest
   * answer; reporting interdeparture moments means a separate domain type.
   */
  @Test
  void rateTypedMeasuresReportNoSecondMoments() throws Exception {
    SolutionsParser.Parsed p =
        new SolutionsParser().parse(resource("/results/interarrival.solutions.xml"));

    MeasureResult tp = p.measures().get(1);
    assertEquals("throughput", tp.type());
    assertEquals(0.30088256380549294, tp.mean(), "throughput stays a rate");
    assertNull(tp.variance(), "variance is over interdeparture times, not over the rate");
    assertNull(tp.stdDev());
    assertEquals(0.2935860105446729, tp.lower(), "its CI *is* on the rate, so it is kept");
    assertEquals(0.3085510461620826, tp.upper());
  }
}
