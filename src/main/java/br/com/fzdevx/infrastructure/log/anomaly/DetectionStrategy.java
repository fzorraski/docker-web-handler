package br.com.fzdevx.infrastructure.log.anomaly;

import br.com.fzdevx.domain.model.anomaly.AnomalyResult;
import br.com.fzdevx.domain.model.anomaly.BucketStats;

import java.util.List;

/**
 * Strategy for detecting anomalies in time-bucketed signal data.
 */
public interface DetectionStrategy {

    record DetectionResult(List<BucketStats> buckets, List<AnomalyResult> anomalies) {}

    DetectionResult detect(List<BucketStats> buckets, double threshold, int baselineWindow,
                           String signalType, String metric);
}
