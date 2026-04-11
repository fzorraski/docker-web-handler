package br.com.fzdevx.application.port;

import br.com.fzdevx.domain.model.anomaly.AnomalyResult;
import br.com.fzdevx.domain.model.anomaly.BucketStats;

import java.util.List;
import java.util.Set;

public interface AnomalyDetectionPort {

    Set<String> validMethods();

    DetectionResult detect(List<BucketStats> buckets, double threshold,
                           int baselineWindow, String signalType,
                           String metric, String method);

    record DetectionResult(List<BucketStats> buckets, List<AnomalyResult> anomalies) {}
}
