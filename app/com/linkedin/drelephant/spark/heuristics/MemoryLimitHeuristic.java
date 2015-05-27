package com.linkedin.drelephant.spark.heuristics;

import com.linkedin.drelephant.analysis.Heuristic;
import com.linkedin.drelephant.analysis.HeuristicResult;
import com.linkedin.drelephant.analysis.Severity;
import com.linkedin.drelephant.spark.SparkApplicationData;
import com.linkedin.drelephant.spark.SparkEnvironmentData;
import com.linkedin.drelephant.spark.SparkExecutorData;
import com.linkedin.drelephant.util.MemoryFormatUtils;
import org.apache.log4j.Logger;
import org.apache.spark.SparkConf;

import static com.linkedin.drelephant.spark.SparkExecutorData.EXECUTOR_DRIVER_NAME;


/**
 * This heuristic checks for memory consumption.
 *
 * @author yizhou
 */
public class MemoryLimitHeuristic implements Heuristic<SparkApplicationData> {
  public static final String HEURISTIC_NAME = "Spark Memory Limit";
  private static final Logger logger = Logger.getLogger(MemoryLimitHeuristic.class);

  public static final String SPARK_EXECUTOR_MEMORY = "spark.executor.memory";
  public static final String SPARK_DRIVER_MEMORY = "spark.driver.memory";
  public static final String SPARK_EXECUTOR_INSTANCES = "spark.executor.instances";

  public static final String SPARK_STORAGE_MEMORY_FRACTION = "spark.storage.memoryFraction";
  public static final double DEFAULT_SPARK_STORAGE_MEMORY_FRACTION = 0.6d;

  @Override
  public HeuristicResult apply(SparkApplicationData data) {

    long totalExecutorMem = getTotalExecutorMem(data);
    long totalStorageMem = getTotalStorageMem(data);
    long totalDriverMem = getTotalDriverMem(data);
    long peakMem = getStoragePeakMemory(data);

    Severity totalMemorySeverity = getTotalMemorySeverity(totalExecutorMem);
    Severity memoryUtilizationServerity = getMemoryUtilizationSeverity(peakMem, totalStorageMem);

    HeuristicResult result =
        new HeuristicResult(getHeuristicName(), Severity.max(totalMemorySeverity, memoryUtilizationServerity));

    result.addDetail("Total executor memory allocated", MemoryFormatUtils.bytesToString(totalExecutorMem));
    result.addDetail("Total driver memory allocated", MemoryFormatUtils.bytesToString(totalDriverMem));
    result.addDetail("Total memory allocated for storage", MemoryFormatUtils.bytesToString(totalStorageMem));
    result.addDetail("Total memory used at peak", MemoryFormatUtils.bytesToString(peakMem));
    result.addDetail("Memory utilization rate", String.format("%1.3f", peakMem * 1.0 / totalStorageMem));
    return result;
  }

  /**
   * Get the total executor memory
   *
   * @param data The spark application data that contains the information
   * @return The memory in bytes
   */
  private static long getTotalExecutorMem(SparkApplicationData data) {
    int executorNum = Integer.parseInt(data.getEnvironmentData().getSparkProperty(SPARK_EXECUTOR_INSTANCES, "0"));
    long perExecutorMem =
        MemoryFormatUtils.stringToBytes(data.getEnvironmentData().getSparkProperty(SPARK_EXECUTOR_MEMORY, "0"));
    return executorNum * perExecutorMem;
  }

  /**
   * Get the total driver memory
   *
   * @param data The spark application data that contains the information
   * @return The memory in bytes
   */
  private static long getTotalDriverMem(SparkApplicationData data) {
    long bytes = MemoryFormatUtils.stringToBytes(data.getEnvironmentData().getSparkProperty(SPARK_DRIVER_MEMORY));
    // spark.driver.memory might not be present, in which case we would infer it from the executor data
    if (bytes == 0L) {
      SparkExecutorData.ExecutorInfo info = data.getExecutorData().getExecutorInfo(EXECUTOR_DRIVER_NAME);
      if (info == null) {
        logger.error("Application id [" + data.getGeneralData().getApplicationId()
            + "] does not contain driver memory configuration info and also does not contain executor driver info."
            + " Unable to detect is driver memory usage.");
        return 0L;
      }
      // This maxmium memory only counts in memory for storage
      bytes = (long) (info.maxMem / getStorageMemoryFraction(data.getEnvironmentData()));
    }

    return bytes;
  }

  /**
   * Get the storage memory fraction ratio used for storage
   *
   * @param data The spark environment data
   * @return the memory fraction
   */
  private static double getStorageMemoryFraction(SparkEnvironmentData data) {
    String ratio = data.getSparkProperty(SPARK_STORAGE_MEMORY_FRACTION);
    if (ratio == null) {
      ratio = new SparkConf().get(SPARK_STORAGE_MEMORY_FRACTION, String.valueOf(DEFAULT_SPARK_STORAGE_MEMORY_FRACTION));
    }
    return Double.parseDouble(ratio);
  }

  /**
   * Get the peak storage memory used during all running time of the spark application
   *
   * @param data The spark application data that contains the information
   * @return The memory in bytes
   */
  private static long getStoragePeakMemory(SparkApplicationData data) {
    SparkExecutorData executorData = data.getExecutorData();
    long mem = 0L;
    for (String id : executorData.getExecutors()) {
      mem += executorData.getExecutorInfo(id).memUsed;
    }
    return mem;
  }

  /**
   * Get the total memory allocated for storage
   *
   * @param data The spark application data that contains the information
   * @return The memory in bytes
   */
  private static long getTotalStorageMem(SparkApplicationData data) {
    SparkExecutorData executorData = data.getExecutorData();
    long totalStorageMem = 0L;
    for (String id : executorData.getExecutors()) {
      totalStorageMem += executorData.getExecutorInfo(id).maxMem;
    }
    return totalStorageMem;
  }

  @Override
  public String getHeuristicName() {
    return HEURISTIC_NAME;
  }

  public static Severity getTotalMemorySeverity(long memory) {
    return Severity
        .getSeverityAscending(memory, MemoryFormatUtils.stringToBytes("0.5T"), MemoryFormatUtils.stringToBytes("1T"),
            MemoryFormatUtils.stringToBytes("1.5T"), MemoryFormatUtils.stringToBytes("2T"));
  }

  private static Severity getMemoryUtilizationSeverity(long peakMemory, long totalStorageMemory) {
    double fraction = peakMemory * 1.0 / totalStorageMemory;
    if (totalStorageMemory < MemoryFormatUtils.stringToBytes("10 GB")) {
      return Severity.NONE;
    } else {
      return Severity.getSeverityDescending(fraction, 0.8d, 0.6d, 0.4d, 0.2d);
    }
  }
}