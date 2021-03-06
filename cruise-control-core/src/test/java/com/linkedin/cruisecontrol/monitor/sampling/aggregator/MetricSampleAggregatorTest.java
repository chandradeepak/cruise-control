/*
 * Copyright 2017 LinkedIn Corp. Licensed under the BSD 2-Clause License (the "License"). See License in the project root for license information.
 */

package com.linkedin.cruisecontrol.monitor.sampling.aggregator;

import com.linkedin.cruisecontrol.CruiseControlUnitTestUtils;
import com.linkedin.cruisecontrol.IntegerEntity;
import com.linkedin.cruisecontrol.exception.NotEnoughValidWindowsException;
import com.linkedin.cruisecontrol.metricdef.MetricDef;
import com.linkedin.cruisecontrol.metricdef.MetricInfo;
import com.linkedin.cruisecontrol.metricdef.AggregationFunction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;


/**
 * Unit test for {@link MetricSampleAggregator}
 */
public class MetricSampleAggregatorTest {
  private static final float EPSILON = 0.01f;
  private static final int NUM_WINDOWS = 20;
  private static final long WINDOW_MS = 1000L;
  private static final int MIN_SAMPLES_PER_WINDOW = 4;
  private static final IntegerEntity ENTITY1 = new IntegerEntity("g1", 1234);
  private static final IntegerEntity ENTITY2 = new IntegerEntity("g1", 5678);
  private static final IntegerEntity ENTITY3 = new IntegerEntity("g2", 1234);
  private final MetricDef _metricDef = CruiseControlUnitTestUtils.getMetricDef();

  @Test
  public void testAddSampleInDifferentWindows() throws NotEnoughValidWindowsException {
    MetricSampleAggregator<String, IntegerEntity> aggregator =
        new MetricSampleAggregator<>(NUM_WINDOWS, WINDOW_MS, MIN_SAMPLES_PER_WINDOW,
                                     0, 5, _metricDef);
    // The remaining windows should NUM_WINDOWS - 2 to 2 * NUM_WINDOWS - 3;
    populateSampleAggregator(2 * NUM_WINDOWS - 1, MIN_SAMPLES_PER_WINDOW, aggregator);

    AggregationOptions<String, IntegerEntity> options =
        new AggregationOptions<>(1, 1, NUM_WINDOWS, Collections.emptySet(),
                                 AggregationOptions.Granularity.ENTITY_GROUP, true);
    MetricSampleAggregationResult<String, IntegerEntity> aggResults =
        aggregator.aggregate(-1, Long.MAX_VALUE, options);
    assertNotNull(aggResults);

    assertEquals(1, aggResults.valuesAndExtrapolations().size());

    for (Map.Entry<IntegerEntity, ValuesAndExtrapolations> entry : aggResults.valuesAndExtrapolations().entrySet()) {
      ValuesAndExtrapolations valuesAndExtrapolations = entry.getValue();
      List<Long> windows = valuesAndExtrapolations.windows();
      assertEquals(NUM_WINDOWS, windows.size());
      for (int i = 0; i < NUM_WINDOWS; i++) {
        assertEquals((2 * NUM_WINDOWS - 2 - i) * WINDOW_MS, windows.get(i).longValue());
      }
      for (MetricInfo info : _metricDef.all()) {
        MetricValues valuesForMetric = valuesAndExtrapolations.metricValues().valuesFor(info.id());
        for (int i = 0; i < NUM_WINDOWS; i++) {
          double expectedValue;
          if (info.strategy() == AggregationFunction.LATEST || info.strategy() == AggregationFunction.MAX) {
            expectedValue = (2 * NUM_WINDOWS - 3 - i) * 10 + MIN_SAMPLES_PER_WINDOW - 1;
          } else {
            expectedValue = (2 * NUM_WINDOWS - 3 - i) * 10 + (MIN_SAMPLES_PER_WINDOW - 1) / 2.0;
          }
          assertEquals("The utilization for " + info.name() + " should be " + expectedValue,
                       expectedValue, valuesForMetric.get(i % NUM_WINDOWS), 0);
        }
      }
    }

    assertEquals(NUM_WINDOWS + 1, aggregator.allWindows().size());
    assertEquals(NUM_WINDOWS, aggregator.numAvailableWindows());
  }

  @Test
  public void testGeneration() {
    MetricSampleAggregator<String, IntegerEntity> aggregator =
        new MetricSampleAggregator<>(NUM_WINDOWS, WINDOW_MS, MIN_SAMPLES_PER_WINDOW,
                                     0, 5, _metricDef);

    CruiseControlUnitTestUtils.populateSampleAggregator(NUM_WINDOWS + 1, MIN_SAMPLES_PER_WINDOW,
                                                        aggregator, ENTITY1, 0, WINDOW_MS,
                                                        _metricDef);
    assertEquals(NUM_WINDOWS + 1, aggregator.generation().intValue());

    AggregationOptions<String, IntegerEntity> options =
        new AggregationOptions<>(1, 1, NUM_WINDOWS, Collections.emptySet(),
                                 AggregationOptions.Granularity.ENTITY_GROUP, true);
    MetricSampleAggregatorState<String, IntegerEntity> windowState = aggregator.aggregatorState();
    for (int i = 1; i < NUM_WINDOWS + 1; i++) {
      assertEquals(NUM_WINDOWS + 1, windowState.windowStates().get((long) i).generation().intValue());
    }

    CruiseControlUnitTestUtils.populateSampleAggregator(1, 1,
                                                        aggregator, ENTITY2, 1, WINDOW_MS, _metricDef);
    aggregator.completeness(-1, Long.MAX_VALUE, options);
    assertEquals(NUM_WINDOWS + 2, windowState.windowStates().get((long) 2).generation().intValue());
  }

  @Test
  public void testEarliestWindow() {
    MetricSampleAggregator<String, IntegerEntity> aggregator =
        new MetricSampleAggregator<>(NUM_WINDOWS, WINDOW_MS, MIN_SAMPLES_PER_WINDOW,
                                     0, 5, _metricDef);
    assertNull(aggregator.earliestWindow());
    CruiseControlUnitTestUtils.populateSampleAggregator(NUM_WINDOWS, MIN_SAMPLES_PER_WINDOW,
                                                        aggregator, ENTITY1, 0, WINDOW_MS,
                                                        _metricDef);
    assertEquals(WINDOW_MS, aggregator.earliestWindow().longValue());
    CruiseControlUnitTestUtils.populateSampleAggregator(2, MIN_SAMPLES_PER_WINDOW,
                                                        aggregator, ENTITY1, NUM_WINDOWS, WINDOW_MS,
                                                        _metricDef);
    assertEquals(2 * WINDOW_MS, aggregator.earliestWindow().longValue());
  }

  @Test
  public void testAllWindows() {
    MetricSampleAggregator<String, IntegerEntity> aggregator =
        new MetricSampleAggregator<>(NUM_WINDOWS, WINDOW_MS, MIN_SAMPLES_PER_WINDOW,
                                     0, 5, _metricDef);
    assertTrue(aggregator.allWindows().isEmpty());
    CruiseControlUnitTestUtils.populateSampleAggregator(NUM_WINDOWS + 1, MIN_SAMPLES_PER_WINDOW,
                                                        aggregator, ENTITY1, 0, WINDOW_MS,
                                                        _metricDef);
    List<Long> allStWindows = aggregator.allWindows();
    assertEquals(NUM_WINDOWS + 1, allStWindows.size());
    for (int i = 0; i < NUM_WINDOWS + 1; i++) {
      assertEquals((i + 1) * WINDOW_MS, allStWindows.get(i).longValue());
    }
  }

  @Test
  public void testAvailableWindows() {
    MetricSampleAggregator<String, IntegerEntity> aggregator =
        new MetricSampleAggregator<>(NUM_WINDOWS, WINDOW_MS, MIN_SAMPLES_PER_WINDOW,
                                     0, 5, _metricDef);
    assertTrue(aggregator.availableWindows().isEmpty());
    CruiseControlUnitTestUtils.populateSampleAggregator(1, MIN_SAMPLES_PER_WINDOW, aggregator,
                                                        ENTITY1, 0, WINDOW_MS, _metricDef);
    assertTrue(aggregator.availableWindows().isEmpty());
    CruiseControlUnitTestUtils.populateSampleAggregator(NUM_WINDOWS - 2, MIN_SAMPLES_PER_WINDOW, aggregator,
                                                        ENTITY1, 1, WINDOW_MS, _metricDef);
    List<Long> availableWindows = aggregator.availableWindows();
    assertEquals(NUM_WINDOWS - 2, availableWindows.size());
    for (int i = 0; i < NUM_WINDOWS - 2; i++) {
      assertEquals((i + 1) * WINDOW_MS, availableWindows.get(i).longValue());
    }
  }

  @Test
  public void testAggregationOption1() throws NotEnoughValidWindowsException {
    MetricSampleAggregator<String, IntegerEntity> aggregator = prepareCompletenessTestEnv();

    // Let the group coverage to be 1
    AggregationOptions<String, IntegerEntity> options =
        new AggregationOptions<>(0.5, 1, NUM_WINDOWS,
                                 new HashSet<>(Arrays.asList(ENTITY1, ENTITY2, ENTITY3)),
                                 AggregationOptions.Granularity.ENTITY, true);
    MetricSampleCompleteness<String, IntegerEntity> completeness =
        aggregator.completeness(-1, Long.MAX_VALUE, options);
    assertTrue(completeness.validWindowIndexes().isEmpty());
    assertTrue(completeness.validEntities().isEmpty());
    assertTrue(completeness.validEntityGroups().isEmpty());
    assertCompletenessByWindowIndex(completeness);
  }

  @Test
  public void testAggregationOption2() {
    MetricSampleAggregator<String, IntegerEntity> aggregator = prepareCompletenessTestEnv();
    // Change the group coverage requirement to 0, window 3, 4, 20 will be excluded because minValidEntityRatio is not met.
    AggregationOptions<String, IntegerEntity> options =
        new AggregationOptions<>(0.5, 0.0, NUM_WINDOWS,
                                 new HashSet<>(Arrays.asList(ENTITY1, ENTITY2, ENTITY3)),
                                 AggregationOptions.Granularity.ENTITY, true);
    MetricSampleCompleteness<String, IntegerEntity> completeness = aggregator.completeness(-1, Long.MAX_VALUE, options);
    assertEquals(17, completeness.validWindowIndexes().size());
    assertFalse(completeness.validWindowIndexes().contains(3L));
    assertFalse(completeness.validWindowIndexes().contains(4L));
    assertFalse(completeness.validWindowIndexes().contains(20L));
    assertEquals(2, completeness.validEntities().size());
    assertTrue(completeness.validEntities().contains(ENTITY1));
    assertTrue(completeness.validEntities().contains(ENTITY3));
    assertEquals(1, completeness.validEntityGroups().size());
    assertTrue(completeness.validEntityGroups().contains(ENTITY3.group()));
    assertCompletenessByWindowIndex(completeness);
  }

  @Test
  public void testAggregationOption3() {
    MetricSampleAggregator<String, IntegerEntity> aggregator = prepareCompletenessTestEnv();
    // Change the option to have 0.5 as minValidEntityGroupRatio. This will exclude window index 3, 4, 20.
    AggregationOptions<String, IntegerEntity> options =
        new AggregationOptions<>(0.0, 0.5, NUM_WINDOWS,
                                 new HashSet<>(Arrays.asList(ENTITY1, ENTITY2, ENTITY3)),
                                 AggregationOptions.Granularity.ENTITY, true);

    MetricSampleCompleteness<String, IntegerEntity> completeness = aggregator.completeness(-1, Long.MAX_VALUE, options);
    assertEquals(17, completeness.validWindowIndexes().size());
    assertFalse(completeness.validWindowIndexes().contains(3L));
    assertFalse(completeness.validWindowIndexes().contains(4L));
    assertFalse(completeness.validWindowIndexes().contains(20L));
    assertEquals(2, completeness.validEntities().size());
    assertTrue(completeness.validEntities().contains(ENTITY1));
    assertTrue(completeness.validEntities().contains(ENTITY3));
    assertEquals(1, completeness.validEntityGroups().size());
    assertTrue(completeness.validEntityGroups().contains(ENTITY3.group()));
    assertCompletenessByWindowIndex(completeness);
  }

  @Test
  public void testAggregationOption4() {
    MetricSampleAggregator<String, IntegerEntity> aggregator = prepareCompletenessTestEnv();
    // Change the option to have 0.5 as minValidEntityGroupRatio. This will exclude window index 3, 4, 20.
    AggregationOptions<String, IntegerEntity> options =
        new AggregationOptions<>(0.0, 0.0, NUM_WINDOWS,
                                 new HashSet<>(Arrays.asList(ENTITY1, ENTITY2, ENTITY3)),
                                 AggregationOptions.Granularity.ENTITY, true);

    MetricSampleCompleteness<String, IntegerEntity> completeness = aggregator.completeness(-1, Long.MAX_VALUE, options);
    assertEquals(20, completeness.validWindowIndexes().size());
    assertEquals(1, completeness.validEntities().size());
    assertTrue(completeness.validEntities().contains(ENTITY1));
    assertTrue(completeness.validEntityGroups().isEmpty());
    assertCompletenessByWindowIndex(completeness);
  }

  @Test
  public void testAggregationOption5() {
    MetricSampleAggregator<String, IntegerEntity> aggregator = prepareCompletenessTestEnv();
    // Change the option to use entity group granularity. In this case ENTITY1 will not be considered as valid entity
    // so there will be no valid windows.
    AggregationOptions<String, IntegerEntity> options =
        new AggregationOptions<>(0.5, 0.0, NUM_WINDOWS,
                                 new HashSet<>(Arrays.asList(ENTITY1, ENTITY2, ENTITY3)),
                                 AggregationOptions.Granularity.ENTITY_GROUP, true);
    MetricSampleCompleteness<String, IntegerEntity> completeness = aggregator.completeness(-1, Long.MAX_VALUE, options);
    assertTrue(completeness.validWindowIndexes().isEmpty());
    assertTrue(completeness.validEntities().isEmpty());
    assertTrue(completeness.validEntityGroups().isEmpty());
    assertCompletenessByWindowIndex(completeness);
  }

  @Test
  public void testAggregationOption6() {
    MetricSampleAggregator<String, IntegerEntity> aggregator = prepareCompletenessTestEnv();
    // Change the option to use entity group granularity and reduce the minValidEntityRatio to 0.3. This will
    // include ENTITY3 except in window 3, 4, 20.
    AggregationOptions<String, IntegerEntity> options =
        new AggregationOptions<>(0.3, 0.0, NUM_WINDOWS,
                                 new HashSet<>(Arrays.asList(ENTITY1, ENTITY2, ENTITY3)),
                                 AggregationOptions.Granularity.ENTITY_GROUP, true);
    MetricSampleCompleteness<String, IntegerEntity> completeness = aggregator.completeness(-1, Long.MAX_VALUE, options);
    assertEquals(17, completeness.validWindowIndexes().size());
    assertFalse(completeness.validWindowIndexes().contains(3L));
    assertFalse(completeness.validWindowIndexes().contains(4L));
    assertFalse(completeness.validWindowIndexes().contains(20L));
    assertEquals(1, completeness.validEntities().size());
    assertTrue(completeness.validEntities().contains(ENTITY3));
    assertEquals(1, completeness.validEntityGroups().size());
    assertTrue(completeness.validEntityGroups().contains(ENTITY3.group()));
    assertCompletenessByWindowIndex(completeness);
  }

  @Test
  public void testConcurrency() throws NotEnoughValidWindowsException {
    final int numThreads = 10;
    final int numEntities = 5;
    final int samplesPerWindow = 100;
    final int numRandomEntities = 10;

    // We set the minimum number of samples per window to be the total number of samples to insert.
    // So when there is a sample got lost we will fail to collect enough window.
    final MetricSampleAggregator<String, IntegerEntity> aggregator =
        new MetricSampleAggregator<>(NUM_WINDOWS, WINDOW_MS,
                                     samplesPerWindow * numThreads * (numRandomEntities / numEntities),
                                     0, 5, _metricDef);

    final Random random = new Random();
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < numThreads; i++) {
      Thread t = new Thread() {
        @Override
        public void run() {
          // Add samples for 10 random partitions.
          int startingEntity = random.nextInt(5) % numEntities;
          for (int i = 0; i < numRandomEntities; i++) {
            IntegerEntity entity = new IntegerEntity("group", (startingEntity + i) % numEntities);
            populateSampleAggregator(2 * NUM_WINDOWS + 1, samplesPerWindow, aggregator, entity);
          }
        }
      };
      threads.add(t);
    }
    threads.forEach(Thread::start);
    for (Thread t : threads) {
      try {
        t.join();
      } catch (InterruptedException e) {
        // let it go.
      }
    }
    assertEquals((NUM_WINDOWS + 1) * samplesPerWindow * numRandomEntities * numThreads, aggregator.numSamples());

    AggregationOptions<String, IntegerEntity> options =
        new AggregationOptions<>(1, 1, NUM_WINDOWS, Collections.emptySet(),
                                 AggregationOptions.Granularity.ENTITY_GROUP, true);
    MetricSampleAggregationResult<String, IntegerEntity> aggResult =
        aggregator.aggregate(-1, Long.MAX_VALUE, options);
    assertEquals(numEntities, aggResult.valuesAndExtrapolations().size());
    assertTrue(aggResult.invalidEntities().isEmpty());
    for (ValuesAndExtrapolations valuesAndExtrapolations : aggResult.valuesAndExtrapolations().values()) {
      assertEquals(NUM_WINDOWS, valuesAndExtrapolations.windows().size());
      assertTrue(valuesAndExtrapolations.extrapolations().isEmpty());
    }
  }

  private MetricSampleAggregator<String, IntegerEntity> prepareCompletenessTestEnv() {
    MetricSampleAggregator<String, IntegerEntity> aggregator =
        new MetricSampleAggregator<>(NUM_WINDOWS, WINDOW_MS, MIN_SAMPLES_PER_WINDOW,
                                     0, 5, _metricDef);
    populateSampleAggregator(NUM_WINDOWS + 1, MIN_SAMPLES_PER_WINDOW, aggregator, ENTITY1);
    populateSampleAggregator(2, MIN_SAMPLES_PER_WINDOW, aggregator, ENTITY3);
    CruiseControlUnitTestUtils.populateSampleAggregator(NUM_WINDOWS - 5, MIN_SAMPLES_PER_WINDOW, aggregator,
                                                        ENTITY3, 4, WINDOW_MS, _metricDef);
    return aggregator;
  }

  private void assertCompletenessByWindowIndex(MetricSampleCompleteness<String, IntegerEntity> completeness) {
    for (long wi = 1; wi <= NUM_WINDOWS; wi++) {
      if (wi == 3L || wi == 4L || wi == 20L) {
        assertEquals(1.0f / 3, completeness.validEntityRatioByWindowIndex().get(wi), EPSILON);
        assertEquals(0, completeness.validEntityRatioWithGroupGranularityByWindowIndex().get(wi), EPSILON);
        assertEquals(0.0, completeness.validEntityGroupRatioByWindowIndex().get(wi), EPSILON);
      } else {
        assertEquals(2.0f / 3, completeness.validEntityRatioByWindowIndex().get(wi), EPSILON);
        assertEquals(1.0f / 3, completeness.validEntityRatioWithGroupGranularityByWindowIndex().get(wi), EPSILON);
        assertEquals(0.5, completeness.validEntityGroupRatioByWindowIndex().get(wi), EPSILON);
      }
    }
  }

  private void populateSampleAggregator(int numWindows,
                                        int numSamplesPerWindow,
                                        MetricSampleAggregator<String, IntegerEntity> metricSampleAggregator) {
    populateSampleAggregator(numWindows, numSamplesPerWindow, metricSampleAggregator, ENTITY1);
  }

  private void populateSampleAggregator(int numWindows,
                                        int numSamplesPerWindow,
                                        MetricSampleAggregator<String, IntegerEntity> metricSampleAggregator,
                                        IntegerEntity entity) {
    CruiseControlUnitTestUtils.populateSampleAggregator(numWindows, numSamplesPerWindow, metricSampleAggregator,
                                                        entity, 0, WINDOW_MS, _metricDef);
  }
}
