/*
 * Copyright 2017 LinkedIn Corp. Licensed under the BSD 2-Clause License (the "License").  See License in the project root for license information.
 */

package com.linkedin.kafka.cruisecontrol.monitor.sampling.aggregator;

import com.linkedin.kafka.cruisecontrol.config.KafkaCruiseControlConfig;
import com.linkedin.kafka.cruisecontrol.monitor.MonitorUtils;
import com.linkedin.kafka.cruisecontrol.common.Resource;
import com.linkedin.kafka.cruisecontrol.exception.NotEnoughSnapshotsException;
import com.linkedin.kafka.cruisecontrol.monitor.sampling.BrokerMetricSample;
import com.linkedin.kafka.cruisecontrol.monitor.sampling.PartitionMetricSample;
import com.linkedin.kafka.cruisecontrol.monitor.sampling.Snapshot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

import static com.linkedin.kafka.cruisecontrol.monitor.sampling.aggregator.MetricSampleAggregationResult.Imputation.FORCED_INSUFFICIENT;
import static com.linkedin.kafka.cruisecontrol.monitor.sampling.aggregator.MetricSampleAggregationResult.Imputation.FORCED_UNKNOWN;
import static com.linkedin.kafka.cruisecontrol.monitor.sampling.aggregator.MetricSampleAggregationResult.Imputation.NO_VALID_IMPUTATION;


/**
 * This class aggregates the metric samples generated by the MetricFetcher.
 * <p>
 * The metric sample aggregator performs the sanity check on the samples and aggregate the samples into the
 * corresponding snapshot window. Because the Kafka broker does not report the bytes in rate for the followers,
 * we assume the sample we get are only from the leaders, and we are going to derive the follower metrics based on
 * the leader metrics.
 * </p>
 */
public class MetricSampleAggregator {
  private static final Logger LOG = LoggerFactory.getLogger(MetricSampleAggregator.class);
  // TODO: Currently we consider all the imputations as valid, will limit the max allowed imputations later.
  private static final float MAX_SNAPSHOT_PERCENT_WITH_FLAWS = 1.0f;
  private final int _numSnapshots;
  private final int _numSnapshotsToKeep;
  private final long _snapshotWindowMs;
  private final ConcurrentNavigableMap<Long, Map<TopicPartition, AggregatedMetrics>> _windowedAggregatedPartitionMetrics;
  private final Metadata _metadata;
  private final int _minSamplesPerSnapshot;
  // A flag indicating whether we are collecting the metrics. If the flag is on, no snapshot window will be evicted from
  // the maintained windowed aggregated metrics.
  private final AtomicInteger _snapshotCollectionInProgress;
  // A generation for the aggregate result at a particular time. Whenever the aggregation result changes, the generation
  // id is bumped. This is to identify whether a cached aggregation result is still valid or not.
  private final AtomicLong _aggregationResultGeneration;
  private final Map<Integer, BrokerMetricSample> _latestBrokerMetrics;
  private final MetricCompletenessChecker _metricCompletenessChecker;
  private volatile long _activeSnapshotWindow;
  // The following two variables is for cashing purpose. If nothing has been back-inserted into any stable snapshot
  // window, we do not need to recompute the aggregation result. If lastResult is null, a new aggregation will be
  // performed.
  private volatile MetricSampleAggregationResult _cachedAggregationResult;
  // The active snapshot window when the _cachedAggregationResult was taken;
  private volatile long _cachedAggregationResultWindow;

  /**
   * Construct the metric sample aggregator.
   *
   * @param config   The load monitor configurations.
   * @param metadata The metadata of the cluster.
   */
  public MetricSampleAggregator(KafkaCruiseControlConfig config,
                                Metadata metadata,
                                MetricCompletenessChecker metricCompletenessChecker) {
    _windowedAggregatedPartitionMetrics = new ConcurrentSkipListMap<>();
    // We keep twice as many the snapshot windows.
    _numSnapshots = config.getInt(KafkaCruiseControlConfig.NUM_LOAD_SNAPSHOTS_CONFIG);
    _numSnapshotsToKeep = _numSnapshots * 2;
    _snapshotWindowMs = config.getLong(KafkaCruiseControlConfig.LOAD_SNAPSHOT_WINDOW_MS_CONFIG);
    _minSamplesPerSnapshot = config.getInt(KafkaCruiseControlConfig.MIN_SAMPLES_PER_LOAD_SNAPSHOT_CONFIG);
    _activeSnapshotWindow = -1L;
    _snapshotCollectionInProgress = new AtomicInteger(0);
    _metadata = metadata;
    _metricCompletenessChecker = metricCompletenessChecker;
    _cachedAggregationResult = null;
    _cachedAggregationResultWindow = -1L;
    _aggregationResultGeneration = new AtomicLong(0);
    _latestBrokerMetrics = new ConcurrentHashMap<>();
  }

  public void updateBrokerMetricSample(BrokerMetricSample brokerMetricSample) {
    _latestBrokerMetrics.put(brokerMetricSample.brokerId(), brokerMetricSample);
  }

  public Double latestBrokerUtil(int broker, Resource resource) {
    BrokerMetricSample latestSample = _latestBrokerMetrics.get(broker);
    return latestSample == null ? null : latestSample.metricFor(resource);
  }

  /**
   * Add a sample to the metric aggregator. This method is thread safe.
   *
   * @param sample The metric sample to add.
   */
  public boolean addSample(PartitionMetricSample sample) {
    return addSample(sample, true, true);
  }

  /**
   * Add a sample to the metric aggregator. This method is thread safe.
   *
   * @param sample The metric sample to add.
   * @param leaderValidation whether perform the leader validation or not.
   *
   * @return true if the sample is accepted, false if the sample is ignored.
   */
  public boolean addSample(PartitionMetricSample sample, boolean leaderValidation, boolean updateCompletenessCache) {
    // Sanity check the sample
    if (!isValidSample(sample, leaderValidation)) {
      return false;
    }
    // Find the snapshot window
    long snapshotWindow = MonitorUtils.toSnapshotWindow(sample.sampleTime(), _snapshotWindowMs);
    Map<TopicPartition, AggregatedMetrics> snapshotsByPartition = _windowedAggregatedPartitionMetrics.get(snapshotWindow);
    if (snapshotsByPartition == null) {
      // The synchronization is needed so we don't remove a snapshot that is being collected.
      synchronized (this) {
        // This is the first sample of the snapshot window, so we should create one.
        snapshotsByPartition = new ConcurrentHashMap<>();
        Map<TopicPartition, AggregatedMetrics> oldValue =
            _windowedAggregatedPartitionMetrics.putIfAbsent(snapshotWindow, snapshotsByPartition);
        // Ok... someone has created one for us, use it.
        if (oldValue != null) {
          snapshotsByPartition = oldValue;
        }

        if (_activeSnapshotWindow < snapshotWindow && oldValue == null) {
          LOG.debug("Rolled out new snapshot window {}, number of snapshots = {}", snapshotWindow,
                    _windowedAggregatedPartitionMetrics.size());
          _activeSnapshotWindow = snapshotWindow;
          _aggregationResultGeneration.incrementAndGet();
          // We only keep N ready snapshots and one active snapshot, evict the old ones if needed. But do not do this
          // when snapshot collection is in progress. We can do it later.
          while (_snapshotCollectionInProgress.get() == 0 && _windowedAggregatedPartitionMetrics.size() > _numSnapshotsToKeep + 1) {
            Long oldestSnapshotWindow = _windowedAggregatedPartitionMetrics.firstKey();
            _windowedAggregatedPartitionMetrics.remove(oldestSnapshotWindow);
            _metricCompletenessChecker.removeWindow(oldestSnapshotWindow);
            LOG.debug("Removed snapshot window {}, number of snapshots = {}", oldestSnapshotWindow,
                      _windowedAggregatedPartitionMetrics.size());
          }
        }
      }
    }
    AggregatedMetrics aggMetrics =
        snapshotsByPartition.computeIfAbsent(sample.topicPartition(), topicPartition -> new AggregatedMetrics());
    aggMetrics.addSample(sample);
    if (updateCompletenessCache) {
      _metricCompletenessChecker.updatePartitionCompleteness(this, snapshotWindow, sample.topicPartition());
    }
    // If we are inserting metric samples into some past windows, invalidate the aggregation result cache and
    // bump up aggregation result generation.
    if (snapshotWindow != _activeSnapshotWindow) {
      synchronized (this) {
        _cachedAggregationResult = null;
        _cachedAggregationResultWindow = -1L;
        _aggregationResultGeneration.incrementAndGet();
      }
    }

    LOG.trace("Added sample {} to aggregated metrics window {}.", sample, snapshotWindow);
    return true;
  }

  /**
   * Refresh the completeness of all the partitions and windows. This is an expensive operation and is only used
   * by the SampleStore when it just finished loading. At that point the load monitor should still in Loading state.
   */
  public void refreshCompletenessCache() {
    Set<TopicPartition> allPartitions = new HashSet<>();
    for (Map<TopicPartition, AggregatedMetrics> windowData : _windowedAggregatedPartitionMetrics.values()) {
      allPartitions.addAll(windowData.keySet());
    }
    _metricCompletenessChecker.refreshAllPartitionCompleteness(this,
                                                               _windowedAggregatedPartitionMetrics.keySet(),
                                                               allPartitions);
  }

  /**
   * Collect the snapshots for all the topic partitions.
   * <p>
   * If a topic has at least one snapshot that does not have enough samples, that topic will be excluded from the
   * returned snapshot. This is because:
   * <ol>
   * <li>We assume that only new topics would have insufficient data. So we only balance the existing topics and
   * allow more time to collect enough utilization data for the new topics.</li>
   * <li>If we don't have enough data to make a replica movement decision, it is better not to take any action.</li>
   * </ol>
   *
   * @param cluster The current cluster information.
   * @param now the current time.
   * @return A mapping between the partition info and the snapshots.
   */
  public MetricSampleAggregationResult recentSnapshots(Cluster cluster, long now) throws NotEnoughSnapshotsException {
    return snapshots(cluster, -1L, now, _numSnapshots, false);
  }

  /**
   * Collect the snapshots for all the topic partitions for a time window.
   * <p>
   * If a topic has at least one snapshot that does not have enough samples, that topic will be excluded from the
   * returned snapshot. This is because:
   * <ol>
   * <li>We assume that only new topics would have insufficient data. So we only balance the existing topics and
   * allow more time to collect enough utilization data for the new topics.</li>
   * <li>If we don't have enough data to make a replica movement decision, it is better not to take any action.</li>
   * </ol>
   *
   * @param cluster The current cluster information.
   * @param from the start of the time window
   * @param to the end of the time window
   * @param requiredNumSnapshots the required exact number of snapshot windows to get. The value must be positive.
   * @param includeAllTopics include all the topics regardless of the number of samples we have. An empty snapshot will
   *                         be used if there is no sample for a partition.
   * @return A mapping between the partition info and the snapshots.
   */
  public MetricSampleAggregationResult snapshots(Cluster cluster,
                                                 long from,
                                                 long to,
                                                 int requiredNumSnapshots,
                                                 boolean includeAllTopics) throws NotEnoughSnapshotsException {
    // Disable the snapshot window eviction to avoid inconsistency of data.
    try {
      if (requiredNumSnapshots <= 0) {
        throw new IllegalArgumentException("The required number of snapshots can not be " + requiredNumSnapshots
                                               + ". It must be positive.");
      }
      // Synchronize with addSamples() here.
      synchronized (this) {
        _snapshotCollectionInProgress.incrementAndGet();
        // If we have a cache, use it.
        if (from <= 0 && Math.min(MonitorUtils.toSnapshotWindow(to, _snapshotWindowMs), _activeSnapshotWindow) == _cachedAggregationResultWindow
            && _cachedAggregationResult != null
            && includeAllTopics == _cachedAggregationResult.includeAllTopics()
            && requiredNumSnapshots == _numSnapshots) {
          LOG.debug("Returning metric sample aggregation result from cache.");
          return _cachedAggregationResult;
        }
      }
      LOG.debug("Getting metric sample aggregation result (from={}, to={}, requiredNumSnapshots={}, includeAllTopics={})",
                from, to, requiredNumSnapshots, includeAllTopics);
      // Have a local variable in case the active snapshot window changes.
      long activeSnapshotWindow = _activeSnapshotWindow;
      // Get the ready snapshots.
      Map<Long, Map<TopicPartition, AggregatedMetrics>> candidateWindowedAggMetrics =
          _windowedAggregatedPartitionMetrics.subMap(from, true, to, true);
      if (candidateWindowedAggMetrics.isEmpty()) {
        throw new NotEnoughSnapshotsException("No snapshot is available in [" + from + "," + to + "].");
      }
      Map<Long, Map<TopicPartition, AggregatedMetrics>> readyWindowedAggMetrics;
      // Add the metrics to the ready aggregated metrics.
      readyWindowedAggMetrics = ensureNumSnapshots(candidateWindowedAggMetrics,
                                                   activeSnapshotWindow,
                                                   requiredNumSnapshots);
      // Ensure we only read from the aggregated metrics.
      readyWindowedAggMetrics = Collections.unmodifiableMap(readyWindowedAggMetrics);
      LOG.debug("Collected {} snapshot windows to aggregate", readyWindowedAggMetrics.size());

      MetricSampleAggregationResult aggregationResult =
          new MetricSampleAggregationResult(currentGeneration(), includeAllTopics);
      // Add the snapshots for each topic.
      for (String topic : cluster.topics()) {
        MetricSampleAggregationResult topicResult =
            recentSnapshotsForTopic(topic, cluster, readyWindowedAggMetrics, includeAllTopics);
        if (topicResult.snapshots().isEmpty()) {
          LOG.debug("Topic {} will not be added to the cluster load snapshot due to insufficient metric samples.", topic);
        } else {
          LOG.trace("Added topic {} to the cluster load snapshot.", topic);
        }
        aggregationResult.merge(topicResult);
      }
      if (from <= 0 && MonitorUtils.toSnapshotWindow(to, _snapshotWindowMs) >= activeSnapshotWindow
          && requiredNumSnapshots == _numSnapshots) {
        synchronized (this) {
          _cachedAggregationResult = aggregationResult;
          _cachedAggregationResultWindow = activeSnapshotWindow;
        }
      }
      return aggregationResult;
    } finally {
      _snapshotCollectionInProgress.decrementAndGet();
    }
  }

  private Map<Long, Map<TopicPartition, AggregatedMetrics>> ensureNumSnapshots(
      Map<Long, Map<TopicPartition, AggregatedMetrics>> candidateWindowedAggMetrics,
      long activeSnapshotWindow,
      int requiredNumSnapshots) throws NotEnoughSnapshotsException {
    Map<Long, Map<TopicPartition, AggregatedMetrics>> readyWindowedAggMetrics = new LinkedHashMap<>();
    int activeWindowIncluded = candidateWindowedAggMetrics.containsKey(activeSnapshotWindow) ? 1 : 0;
    if (candidateWindowedAggMetrics.size() - activeWindowIncluded < requiredNumSnapshots) {
      throw new NotEnoughSnapshotsException("There are only " + (candidateWindowedAggMetrics.size() - activeWindowIncluded) +
                                                " snapshots available, which is less than the required " + requiredNumSnapshots
                                                + " snapshots.");
    }
    Iterator<Map.Entry<Long, Map<TopicPartition, AggregatedMetrics>>> iter =
        candidateWindowedAggMetrics.entrySet().iterator();
    int numExtraSnapshots = candidateWindowedAggMetrics.size() - activeWindowIncluded - requiredNumSnapshots;
    for (int i = 0; i < numExtraSnapshots; i++) {
      iter.next();
    }
    while (readyWindowedAggMetrics.size() < requiredNumSnapshots) {
      Map.Entry<Long, Map<TopicPartition, AggregatedMetrics>> entry = iter.next();
      readyWindowedAggMetrics.put(entry.getKey(), entry.getValue());
    }
    return readyWindowedAggMetrics;
  }

  public Long earliestSnapshotWindow() {
    return _windowedAggregatedPartitionMetrics.isEmpty() ? null : _windowedAggregatedPartitionMetrics.firstKey();
  }

  /**
   * @return the current active snapshot window.
   */
  public List<Long> snapshotWindows() {
    List<Long> windows = new ArrayList<>(_windowedAggregatedPartitionMetrics.size());
    windows.addAll(_windowedAggregatedPartitionMetrics.keySet());
    return windows;
  }

  /**
   * @return Whether there are enough snapshots in the metric sample aggregator.
   */
  public int numSnapshotWindows() {
    return _windowedAggregatedPartitionMetrics.size();
  }

  /**
   * Clear the entire metric sample aggregator.
   */
  public void clear() {
    // Synchronize with addSample() and recentSnapshots()
    synchronized (this) {
      // Wait for the snapshot collection to finish if needed.
      while (_snapshotCollectionInProgress.get() > 0) {
        try {
          Thread.sleep(10);
        } catch (InterruptedException e) {
          //
        }
      }
      _windowedAggregatedPartitionMetrics.clear();
      _activeSnapshotWindow = -1L;
    }
  }

  /**
   * Get the current aggregation result generation. An aggregation result is still valid if its generation equals
   * to the current aggregation result generation.
   */
  public long currentGeneration() {
    return _aggregationResultGeneration.get();
  }

  MetricSampleAggregationResult cachedAggregationResult() {
    return _cachedAggregationResult;
  }

  long cachedAggregationResultWindow() {
    return _cachedAggregationResultWindow;
  }

  long activeSnapshotWindow() {
    return _activeSnapshotWindow;
  }

  public int totalNumSamples() {
    int numSamples = 0;
    for (Map.Entry<Long, Map<TopicPartition, AggregatedMetrics>> entry : _windowedAggregatedPartitionMetrics.entrySet()) {
      for (Map.Entry<TopicPartition, AggregatedMetrics> e : entry.getValue().entrySet()) {
        numSamples += e.getValue().numSamples();
      }
    }
    return numSamples;
  }

  /**
   * Get all the snapshots for a topic before the given time, excluding the snapshot window that the given time falls in.
   */
  private MetricSampleAggregationResult recentSnapshotsForTopic(String topic,
                                                                Cluster cluster,
                                                                Map<Long, Map<TopicPartition, AggregatedMetrics>> readyWindowedAggMetrics,
                                                                boolean includeAllTopics)
      throws NotEnoughSnapshotsException {
    List<PartitionInfo> partitions = cluster.partitionsForTopic(topic);
    MetricSampleAggregationResult result = new MetricSampleAggregationResult(currentGeneration(), includeAllTopics);

    for (PartitionInfo partition : partitions) {
      TopicPartition tp = new TopicPartition(topic, partition.partition());
      Snapshot[] snapshots = new Snapshot[readyWindowedAggMetrics.size()];
      int index = 0;
      for (Map.Entry<Long, Map<TopicPartition, AggregatedMetrics>> entry : readyWindowedAggMetrics.entrySet()) {
        long snapshotWindow = entry.getKey();
        SnapshotAndImputation partitionSnapShot =
                partitionSnapshotForWindow(tp, cluster, snapshotWindow, snapshotWindow, includeAllTopics, true);

        // There is an invalid partition for the topic, we just exclude the topic by returning the result immediately.
        // At this point the result does not contain any snapshot, but only partition flaw.
        if (partitionSnapShot.snapshot() == null) {
          result.discardAllSnapshots();
          result.recordPartitionWithSampleFlaw(tp, snapshotWindow, partitionSnapShot.imputation());
          return result;
        }

        // Record the imputation and return the result without snapshot if there has been too many imputations.
        if (partitionSnapShot.imputation() != null) {
          result.recordPartitionWithSampleFlaw(tp, snapshotWindow, partitionSnapShot.imputation());
          if (result.sampleFlaw(tp).size() > _numSnapshots * MAX_SNAPSHOT_PERCENT_WITH_FLAWS) {
            LOG.debug("{} already has {} snapshot with flaws, excluding the partition from the model.",
                      tp, result.sampleFlaw(tp).size());
            result.discardAllSnapshots();
            return result;
          }
        }

        snapshots[index++] = partitionSnapShot.snapshot();
      }
      result.addPartitionSnapshots(new TopicPartition(partition.topic(), partition.partition()), snapshots);
    }
    return result;
  }

  /**
   * Check if the partition is a valid partition or not. A valid partition has enough metrics to be give a
   * reliable aggregation. We accept imputation here.
   */
  boolean isValidPartition(long window, TopicPartition tp) {
    return partitionSnapshotForWindow(tp, _metadata.fetch(), window, window,
                                      false, true).imputation() != NO_VALID_IMPUTATION;
  }

  /**
   * This function helps find the snapshot of a partition for a given snapshotWindow. If the partition's data is not
   * sufficient in this snapshotWindow, there are a few imputations that could be taken. The actions are defined
   * in {@link MetricSampleAggregationResult.Imputation}.
   *
   * @param tp the partition to get the snapshot
   * @param cluster the cluster information
   * @param snapshotWindow the snapshot window to get the snapshot for.
   * @param resultSnapshotWindow Sometimes when imputation is used, the looked up snapshot window is not the actual
   *                             snapshot shot window that should be returned. In this case, the result snapshot
   *                             window will be different from the snapshot window used for the lookup.
   * @param includeAllTopics whether all topics should be included
   * @param allowImputation whether allow to use imputation or not.
   * @return the snapshot of the partition for the snapshot window and the imputation made if any. If no imputation
   * was made, the imputation would be null.
   */
  private SnapshotAndImputation partitionSnapshotForWindow(TopicPartition tp,
                                                           Cluster cluster,
                                                           long snapshotWindow,
                                                           long resultSnapshotWindow,
                                                           boolean includeAllTopics,
                                                           boolean allowImputation) {
    Map<TopicPartition, AggregatedMetrics> aggMetricsForWindow = _windowedAggregatedPartitionMetrics.get(snapshotWindow);
    if (aggMetricsForWindow == null) {
      return new SnapshotAndImputation(null, null);
    }
    AggregatedMetrics aggMetricsForPartition = aggMetricsForWindow.get(tp);
    Snapshot snapshot = null;
    MetricSampleAggregationResult.Imputation imputation = null;
    if (aggMetricsForPartition != null && aggMetricsForPartition.enoughSamples(_minSamplesPerSnapshot)) {
      // We have sufficient samples
      snapshot = aggMetricsForPartition.toSnapshot(resultSnapshotWindow);
    } else if (allowImputation) {
      // Allow imputation.
      // Check if we have some samples.
      if (aggMetricsForPartition != null && aggMetricsForPartition.numSamples() >= _minSamplesPerSnapshot / 2) {
        // We have more than half of the required samples but not sufficient
        snapshot = aggMetricsForPartition.toSnapshot(snapshotWindow);
        imputation = MetricSampleAggregationResult.Imputation.AVG_AVAILABLE;
        LOG.debug("Not enough metric samples for {} in snapshot window {}. Required {}, got {} samples. Falling back to use {}",
                  tp, snapshotWindow, _minSamplesPerSnapshot, aggMetricsForPartition.numSamples(), MetricSampleAggregationResult.Imputation.AVG_AVAILABLE);
      }

      // We do not have enough samples, fall back to use adjacent windows.
      if (snapshot == null) {
        snapshot = avgAdjacentSnapshots(tp, cluster, snapshotWindow);
        if (snapshot != null) {
          imputation = MetricSampleAggregationResult.Imputation.AVG_ADJACENT;
          LOG.debug("No metric samples for {} in snapshot window {}. Required {}, falling back to use {}", tp,
                    snapshotWindow, _minSamplesPerSnapshot, MetricSampleAggregationResult.Imputation.AVG_ADJACENT);
        }
      }

      // Adjacent windows does not work, fall back to previous period
      if (snapshot == null) {
        snapshot = partitionSnapshotForWindow(tp, cluster, snapshotWindow - _snapshotWindowMs * _numSnapshots,
                                              resultSnapshotWindow, false, false).snapshot();
        if (snapshot != null) {
          imputation = MetricSampleAggregationResult.Imputation.PREV_PERIOD;
          LOG.debug("No metric samples for {} in snapshot window {}. Required {}, falling back to use {}", tp,
                    snapshotWindow, _minSamplesPerSnapshot, MetricSampleAggregationResult.Imputation.PREV_PERIOD);
        }
      }
    }

    // Lastly if user just wants the snapshot and we do not have any data, return a zero snapshot.
    if (snapshot == null && includeAllTopics) {
      if (aggMetricsForPartition != null) {
        imputation = FORCED_INSUFFICIENT;
        snapshot = aggMetricsForPartition.toSnapshot(snapshotWindow);
      } else {
        imputation = FORCED_UNKNOWN;
        snapshot = new Snapshot(snapshotWindow, 0.0, 0.0, 0.0, 0.0);
      }
    }

    if (snapshot == null) {
      imputation = NO_VALID_IMPUTATION;
      LOG.debug("No metric samples for {} in snapshot window {}. Required {}. No imputation strategy works.", tp,
                snapshotWindow, _minSamplesPerSnapshot);
    }

    return new SnapshotAndImputation(snapshot, imputation);
  }

  private Snapshot avgAdjacentSnapshots(TopicPartition tp,
                                        Cluster cluster,
                                        long snapshotWindow) {
    // The aggregated metrics for the current window should never be null. We have checked that earlier.
    AggregatedMetrics aggMetricsForPartition = _windowedAggregatedPartitionMetrics.get(snapshotWindow).get(tp);
    Snapshot currSnapshot =
        aggMetricsForPartition == null ? null : aggMetricsForPartition.toSnapshot(snapshotWindow);
    Snapshot prevSnapshot = partitionSnapshotForWindow(tp, cluster, snapshotWindow - _snapshotWindowMs,
                                                       snapshotWindow - _snapshotWindowMs, false, false).snapshot();
    Snapshot nextSnapshot = partitionSnapshotForWindow(tp, cluster, snapshotWindow + _snapshotWindowMs,
                                                       snapshotWindow + _snapshotWindowMs, false, false).snapshot();
    if (prevSnapshot != null && nextSnapshot != null) {
      return new Snapshot(snapshotWindow,
                          averageNullableUtilization(Resource.CPU, prevSnapshot, currSnapshot, nextSnapshot),
                          averageNullableUtilization(Resource.NW_IN, prevSnapshot, currSnapshot, nextSnapshot),
                          averageNullableUtilization(Resource.NW_OUT, prevSnapshot, currSnapshot, nextSnapshot),
                          averageNullableUtilization(Resource.DISK, prevSnapshot, currSnapshot, nextSnapshot));
    } else {
      return null;
    }
  }

  private double averageNullableUtilization(Resource resource, Snapshot... snapshots) {
    if (snapshots.length == 0) {
      throw new IllegalArgumentException("The snapshot list cannot be empty");
    }
    double total = 0.0;
    int numNull = 0;
    for (Snapshot snapshot : snapshots) {
      if (snapshot != null) {
        total += snapshot.utilizationFor(resource);
      } else {
        numNull++;
      }
    }
    // In our case it is impossible that all the snapshots are null, so no need to worry about divided by 0.
    return total / (snapshots.length - numNull);
  }

  /**
   * The current state.  This is not synchronized and so this may show an inconsistent state.
   * @return a non-null map sorted by snapshot window.  Mutating this map will not mutate the internals of this class.
   */
  public SortedMap<Long, Map<TopicPartition, Snapshot>> currentSnapshots() {
    SortedMap<Long, Map<TopicPartition, Snapshot>> currentSnapshots = new TreeMap<>();
    for (Map.Entry<Long, Map<TopicPartition, AggregatedMetrics>> snapshotWindowAndData : _windowedAggregatedPartitionMetrics
        .entrySet()) {
      Long snapshotWindow = snapshotWindowAndData.getKey();
      Map<TopicPartition, AggregatedMetrics> aggregatedMetricsMap = snapshotWindowAndData.getValue();
      Map<TopicPartition, Snapshot> snapshotMap = new HashMap<>(aggregatedMetricsMap.size());
      currentSnapshots.put(snapshotWindow, snapshotMap);
      for (Map.Entry<TopicPartition, AggregatedMetrics> metricsForPartition : aggregatedMetricsMap.entrySet()) {
        TopicPartition tp = metricsForPartition.getKey();
        Snapshot snapshot = metricsForPartition.getValue().toSnapshot(snapshotWindow);
        snapshotMap.put(tp, snapshot);
      }
    }

    return currentSnapshots;
  }

  /**
   * This is a simple sanity check on the sample data. We only verify that
   * <p>
   * 1. the broker of the sampled data is from the broker who holds the leader replica. If it is not, we simply
   * discard the data because leader migration may have occurred so the metrics on the old data might not be
   * accurate anymore.
   * <p>
   * 2. The sample contains metric for all the resources.
   *
   * @param sample the sample to do the sanity check.
   * @param leaderValidation whether skip the leader validation or not.
   * @return <tt>true</tt> if the sample is valid.
   */
  private boolean isValidSample(PartitionMetricSample sample, boolean leaderValidation) {
    boolean validLeader = true;
    if (leaderValidation) {
      Node leader = _metadata.fetch().leaderFor(sample.topicPartition());
      validLeader = (leader != null) && (sample.brokerId() == leader.id());
      if (!validLeader) {
        LOG.warn("The metric sample is discarded due to invalid leader. Current leader {}, Sample: {}", leader, sample);
      }
    }
    boolean completeMetrics = sample.numMetrics() == Resource.values().length;
    if (!completeMetrics) {
      LOG.warn("The metric sample is discarded due to missing metrics. Sample: {}", sample);
    }
    return validLeader && completeMetrics;
  }

  static class SnapshotAndImputation {
    private final Snapshot _snapshot;
    private final MetricSampleAggregationResult.Imputation _imputation;

    SnapshotAndImputation(Snapshot snapshot, MetricSampleAggregationResult.Imputation imputation) {
      _snapshot = snapshot;
      _imputation = imputation;
    }

    public Snapshot snapshot() {
      return _snapshot;
    }

    public MetricSampleAggregationResult.Imputation imputation() {
      return _imputation;
    }
  }
}
