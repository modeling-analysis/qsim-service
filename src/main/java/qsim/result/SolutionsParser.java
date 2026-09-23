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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import qsim.model.MeasureResult;
import qsim.translate.MeasureMapper;

public class SolutionsParser {

  public record Parsed(List<MeasureResult> measures, boolean completed) {}

  // Inverse of MeasureMapper's registry (JMT measureType string -> domain type).
  private static final Map<String, String> REVERSE = Map.ofEntries(
      Map.entry("Response Time", "response-time"),
      Map.entry("Residence Time", "residence-time"),
      Map.entry("Queue Time", "queue-time"),
      Map.entry("Number of Customers", "queue-length"),
      Map.entry("Utilization", "utilization"),
      Map.entry("Throughput", "throughput"),
      Map.entry("Drop Rate", "drop-rate"),
      Map.entry("Arrival Rate", "interarrival-time"),
      Map.entry("System Response Time", "system-response-time"),
      // A fork-join node's "response-time" is requested as JMT's dedicated fork-region measure
      // (see MeasureMapper.FORK_JOIN_STATION / issue #6); it comes back under the domain node name
      // because that measure is anchored on the fork station, so no station remap is needed.
      Map.entry("Fork Join Response Time", "response-time"));

  public Parsed parse(File output) {
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      // XXE hardening: disable DOCTYPEs and external entities
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setFeature("http://xml.org/sax/features/external-general-entities", false);
      f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      f.setXIncludeAware(false);
      f.setExpandEntityReferences(false);
      Document doc = f.newDocumentBuilder().parse(output);
      NodeList measures = doc.getElementsByTagName("measure");
      List<MeasureResult> results = new ArrayList<>();
      boolean completed = true;
      for (int i = 0; i < measures.getLength(); i++) {
        Element m = (Element) measures.item(i);
        boolean success = Boolean.parseBoolean(m.getAttribute("successful"));
        completed &= success;
        // Reported verbatim, deliberately not reordered (issue #12). The Task 11 investigation read
        // inverted lowerLimit/upperLimit as a JMT output quirk and normalized it by swapping, but it
        // was never a quirk: Measure.getLowerLimit() is `mean - confInt`, so the endpoints invert
        // exactly when confInt is negative — which is what writing `1 - alpha` into <measure alpha>
        // caused. The swap turned that signal into a plausible-looking interval and hid the defect.
        // With alpha written correctly confInt is positive and the endpoints order naturally (the
        // degenerate paths, isZero and confInt == 0, are ordered too), so an inversion now means a
        // real regression and must stay visible.
        Double lower = parseD(m.getAttribute("lowerLimit"));
        Double upper = parseD(m.getAttribute("upperLimit"));
        String jmtType = m.getAttribute("measureType");

        // Which attribute holds the mean depends on the measure. For an InverseMeasure JMT reports
        // meanValue = 1/E[sample] while `mean`, `variance` and `standardDeviation` describe the raw
        // samples; for "Arrival Rate" the raw sample IS the quantity qsim reports, so the mean must
        // come from the sample-side attribute or it would not agree with the variance next to it
        // (issue #15). See MeasureMapper.RAW_SAMPLE_MEAN.
        boolean rawSampleMean = MeasureMapper.RAW_SAMPLE_MEAN.contains(jmtType);
        Double mean = parseD(m.getAttribute(rawSampleMean ? "mean" : "meanValue"));

        // JMT's confidence interval is computed on whatever meanValue reports, so for a raw-sample
        // measure it brackets the rate rather than the mean above. Issue #15 explicitly accepts a
        // mean with no interval; an interval that does not contain its point estimate would be worse
        // than none. A reciprocal transform is possible later (see the plan's deferred work).
        if (rawSampleMean) {
          lower = null;
          upper = null;
        }

        // The remaining rate-typed measures keep their rate as the mean — that is the figure callers
        // want — which leaves their second moments in the wrong units to sit beside it. Dropped
        // rather than converted; exposing interdeparture moments needs its own domain type.
        boolean rateTyped = MeasureMapper.RATE_TYPED.contains(jmtType);
        Double variance = rateTyped ? null : parseD(m.getAttribute("variance"));
        Double stdDev = rateTyped ? null : parseD(m.getAttribute("standardDeviation"));

        results.add(new MeasureResult(
            domainStation(m.getAttribute("station")),
            m.getAttribute("class"),
            REVERSE.getOrDefault(jmtType, jmtType),
            mean,
            lower,
            upper,
            parseD(m.getAttribute("alfa")),
            parseD(m.getAttribute("precision")),
            success,
            parseI(m.getAttribute("analyzedSamples")),
            parseI(m.getAttribute("discardedSamples")),
            variance,
            stdDev));
      }
      return new Parsed(results, completed);
    } catch (Exception e) {
      throw new IllegalStateException("cannot parse JMT solutions file " + output, e);
    }
  }

  /** Map an expanded fork-join station name back to its domain node name. */
  static String domainStation(String station) {
    if (station == null) return null;
    // Strip only TRUE suffixes (not substrings): __join at end, or __b followed by digits at end
    if (station.endsWith("__join")) {
      return station.substring(0, station.length() - "__join".length());
    }
    Matcher mb = BRANCH_SUFFIX_PATTERN.matcher(station);
    if (mb.find()) {
      return station.substring(0, mb.start());
    }
    return station;
  }

  private static final Pattern BRANCH_SUFFIX_PATTERN = Pattern.compile("__b\\d+$");

  private static Double parseD(String s) {
    if (s == null || s.isBlank()) return null;
    try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
  }

  private static Integer parseI(String s) {
    if (s == null || s.isBlank()) return null;
    try { return (int) Math.round(Double.parseDouble(s)); } catch (NumberFormatException e) { return null; }
  }
}
