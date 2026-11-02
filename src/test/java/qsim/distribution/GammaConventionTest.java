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
package qsim.distribution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import random.GammaDistr;
import random.GammaDistrPar;
import random.engine.MersenneTwister;
import org.junit.jupiter.api.Test;
import qsim.model.Distribution;

/**
 * Pins the Gamma parametrization convention on <em>JMT's</em> side of the boundary.
 *
 * <p>{@code GammaDistrPar}'s second constructor argument is named {@code lambda}, which reads like
 * a rate, but {@code GammaDistr.nextRand} and {@code GammaDistr.pdf} both use it as a <b>scale</b>
 * (E[X] = alpha*beta, Var[X] = alpha*beta^2). JMT is internally inconsistent about this:
 * {@code theorMean()} and {@code theorVariance()} return alpha/lambda and alpha/lambda^2, i.e. the
 * rate convention, and are simply wrong for the values the sampler produces. Nothing in
 * {@code jmt.engine} or in qsim calls those two methods, so they are inert — but the discrepancy
 * makes the sampler's true convention easy to "correct" in the wrong direction.
 *
 * <p>{@link DistributionResolver} therefore emits beta = mean*scv (the scale). If a future JMT
 * upgrade ever made {@code lambda} genuinely mean a rate, {@code DistributionResolverTest} would
 * still pass — it only checks our own output — and the damage would surface as quietly wrong
 * service times. These tests fail loudly instead.
 */
class GammaConventionTest {

  private static final int SAMPLES = 400_000;
  private static final long SEED = 20260728L;

  /** Sample mean and SCV of {@code SAMPLES} draws from GammaDistr(alpha, beta). */
  private static double[] sampleMoments(double alpha, double beta) throws Exception {
    GammaDistr g = new GammaDistr();
    g.setRandomEngine(new MersenneTwister((int) SEED));
    GammaDistrPar p = new GammaDistrPar(alpha, beta);
    double sum = 0, sumSq = 0;
    for (int i = 0; i < SAMPLES; i++) {
      double x = g.nextRand(p);
      sum += x;
      sumSq += x * x;
    }
    double mean = sum / SAMPLES;
    double variance = sumSq / SAMPLES - mean * mean;
    return new double[] {mean, variance / (mean * mean)};
  }

  @Test
  void jmtGammaSamplerUsesScaleNotRate() throws Exception {
    // beta != 1/beta for every case below, so scale and rate predictions are distinguishable.
    double[][] cases = {{2.0, 3.0}, {0.5, 4.0}, {4.0, 0.25}};
    for (double[] c : cases) {
      double alpha = c[0], beta = c[1];
      double observed = sampleMoments(alpha, beta)[0];
      double scalePrediction = alpha * beta;   // what nextRand actually does
      double ratePrediction = alpha / beta;    // what theorMean() claims

      assertTrue(Math.abs(observed - scalePrediction) / scalePrediction < 0.02,
          "GammaDistr(alpha=" + alpha + ", beta=" + beta + ") sample mean " + observed
              + " must match the SCALE prediction " + scalePrediction);
      assertTrue(Math.abs(observed - ratePrediction) / ratePrediction > 0.10,
          "sample mean " + observed + " must NOT match the RATE prediction " + ratePrediction
              + " — if this fails, JMT changed convention and DistributionResolver must follow");
    }
  }

  @Test
  void theorMeanIsKnownWrongAndMustStayUnused() throws Exception {
    // Characterization, not endorsement: documents the trap so nobody trusts these accessors.
    GammaDistr g = new GammaDistr();
    GammaDistrPar p = new GammaDistrPar(2.0, 3.0);
    assertEquals(2.0 / 3.0, g.theorMean(p), 1e-12);      // rate convention
    assertEquals(6.0, sampleMoments(2.0, 3.0)[0], 0.2);  // sampler: scale convention
  }

  /**
   * The guard that actually matters: a (mean, scv) request must come back out of JMT's sampler
   * with those same moments, through the exact alpha/beta DistributionResolver emits.
   */
  @Test
  void resolverMomentsSurviveRoundTripThroughJmtSampler() throws Exception {
    DistributionResolver resolver = new DistributionResolver();
    double[][] cases = {{0.5, 4.0}, {0.5, 2.0}, {2.0, 0.5}, {1.0, 0.25}};
    for (double[] c : cases) {
      double mean = c[0], scv = c[1];
      CanonicalDistribution cd = resolver.resolve(new Distribution(null, null, null, mean, scv));
      assertEquals("jmt.engine.random.GammaDistr", cd.distributionClass());

      double alpha = Double.parseDouble(cd.params().get(0).value());
      double beta = Double.parseDouble(cd.params().get(1).value());
      double[] moments = sampleMoments(alpha, beta);

      assertTrue(Math.abs(moments[0] - mean) / mean < 0.02,
          "requested mean " + mean + " (scv " + scv + ") but JMT sampled mean " + moments[0]);
      assertTrue(Math.abs(moments[1] - scv) / scv < 0.05,
          "requested scv " + scv + " (mean " + mean + ") but JMT sampled scv " + moments[1]);
    }
  }
}
