/*
 * Copyright 2016 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.linkedin.drelephant.util;

import com.linkedin.drelephant.analysis.HadoopApplicationData;
import com.linkedin.drelephant.schedulers.AzkabanScheduler;
import com.linkedin.drelephant.schedulers.Scheduler;
import com.linkedin.drelephant.spark.data.SparkApplicationData;
import java.util.Map;
import java.util.Properties;

import java.util.Set;
import org.apache.log4j.Logger;

import models.AppResult;

import com.linkedin.drelephant.mapreduce.data.MapReduceApplicationData;


/**
 * InfoExtractor is responsible for retrieving information and context about a
 * job from the job's configuration
 */
public class InfoExtractor {
  private static final Logger logger = Logger.getLogger(InfoExtractor.class);
  private static final String SPARK_EXTRA_JAVA_OPTIONS = "spark.driver.extraJavaOptions";

  /**
   * All the supported Schedulers.
   * JobScheduler(x, y) where,
   * x, is the unique property in the job conf that identifies the scheduler. This property must exist
   *    in every job scheduled by the scheduler.
   * y, is an instance of the Scheduler.
   */
  static enum JobScheduler {
    AZKABAN(AzkabanScheduler.AZKABAN_JOB_URL);

    String identifier;

    JobScheduler(String identifier) {
      this.identifier = identifier;
    }

    String getIdentifier() {
      return this.identifier;
    }

    Scheduler getSchedulerInstance(String appId, Properties properties) {
      if (this.equals(JobScheduler.AZKABAN)) {
        return new AzkabanScheduler(appId, properties);
      } else {
        return null;
      }
    }
  }

  /**
   * Find the scheduler which scheduled the job.
   *
   * @param appId The application id
   * @param properties The application properties
   * @return the corresponding Scheduler which scheduled the job.
   */
  public static Scheduler getSchedulerInstance(String appId, Properties properties) {
    if (properties != null) {
      for (JobScheduler scheduler : JobScheduler.values()) {
        if (properties.containsKey(scheduler.getIdentifier())) {
          return scheduler.getSchedulerInstance(appId, properties);
        }
      }
    }
    return null;
  }

  /**
   * Loads result with the info depending on the application type
   *
   * @param result The jobResult to be loaded with.
   * @param data The Hadoop application data
   */
  public static void loadInfo(AppResult result, HadoopApplicationData data) {
    Properties properties = new Properties();
    if (data instanceof MapReduceApplicationData) {
      properties = retrieveMapreduceProperties((MapReduceApplicationData) data);
    } else if (data instanceof SparkApplicationData) {
      properties = retrieveSparkProperties((SparkApplicationData) data);
    }

    Scheduler scheduler = getSchedulerInstance(data.getAppId(), properties);

    if (scheduler != null) {
      //TODO-1 remove
      logger.info("Scheduler is not null");
      String appId = data.getAppId();

      // Load all the Ids
      result.jobDefId = Utils.truncateField(scheduler.getJobDefId(), AppResult.URL_LEN_LIMIT, appId);
      result.jobExecId = Utils.truncateField(scheduler.getJobExecId(), AppResult.URL_LEN_LIMIT, appId);
      result.flowDefId = Utils.truncateField(scheduler.getFlowDefId(), AppResult.URL_LEN_LIMIT, appId);
      result.flowExecId = Utils.truncateField(scheduler.getFlowExecId(), AppResult.FLOW_EXEC_ID_LIMIT, appId);

      // Dr. Elephant expects all the 4 ids(jobDefId, jobExecId, flowDefId, flowExecId) to be set.
      if (!Utils.isSet(result.jobDefId) || !Utils.isSet(result.jobExecId)
          || !Utils.isSet(result.flowDefId) || !Utils.isSet(result.flowExecId)) {
        logger.warn("This job doesn't have the correct " + scheduler.getSchedulerName() + " integration support. I"
            + " will treat this as an adhoc job");
        loadNoSchedulerInfo(result);
      } else {
        result.scheduler =  Utils.truncateField(scheduler.getSchedulerName(), AppResult.SCHEDULER_LIMIT, appId);
        result.workflowDepth = scheduler.getWorkflowDepth();
        result.jobName = scheduler.getJobName() != null ?
            Utils.truncateField(scheduler.getJobName(), AppResult.JOB_NAME_LIMIT, appId) : "";
        result.jobDefUrl = scheduler.getJobDefUrl() != null ?
            Utils.truncateField(scheduler.getJobDefUrl(), AppResult.URL_LEN_LIMIT, appId) : "";
        result.jobExecUrl = scheduler.getJobExecUrl() != null ?
            Utils.truncateField(scheduler.getJobExecUrl(), AppResult.URL_LEN_LIMIT, appId) : "";
        result.flowDefUrl = scheduler.getFlowDefUrl() != null ?
            Utils.truncateField(scheduler.getFlowDefUrl(), AppResult.URL_LEN_LIMIT, appId) : "";
        result.flowExecUrl = scheduler.getFlowExecUrl() != null ?
            Utils.truncateField(scheduler.getFlowExecUrl(), AppResult.URL_LEN_LIMIT, appId) : "";
      }
    } else {
      loadNoSchedulerInfo(result);
    }
  }

  /**
   * Retrieve the Mapreduce properties
   *
   * @param appData the Mapreduce Application Data
   * @return the retrieved mapreduce properties
   */
  public static Properties retrieveMapreduceProperties(MapReduceApplicationData appData) {
    return appData.getConf();
  }

  /**
   * Retrieve the spark properties from SPARK_EXTRA_JAVA_OPTIONS
   *
   * @param appData the Spark Application Data
   * @return The retrieved Spark properties
   */
  public static Properties retrieveSparkProperties(SparkApplicationData appData) {
    String prop = appData.getEnvironmentData().getSparkProperty(SPARK_EXTRA_JAVA_OPTIONS);
    Properties properties = new Properties();
    if (prop != null) {
      try {
        Map<String, String> javaOptions = Utils.parseJavaOptions(prop);
        for (String key : javaOptions.keySet()) {
          properties.setProperty(key, unescapeString(javaOptions.get(key)));
        }
        logger.info("Parsed options:" + properties.toString());
      } catch (IllegalArgumentException e) {
        logger.error("Encountered error while parsing java options into urls: " + e.getMessage());
      }
    } else {
      logger.error("Unable to retrieve the scheduler info for application [" +
          appData.getGeneralData().getApplicationId() + "]. It does not contain [" + SPARK_EXTRA_JAVA_OPTIONS
          + "] property in its spark properties.");
    }
    return properties;
  }

  /**
   * A temporary solution that SPARK 1.2 need to escape '&' with '\&' in its javaOptions.
   * This is the reverse process that recovers the escaped string.
   *
   * @param s The string to unescape
   * @return The original string
   */
  private static String unescapeString(String s) {
    if (s == null) {
      return null;
    }
    return s.replaceAll("\\\\\\&", "\\&");
  }

  /**
   * Update the application result with adhoc(not scheduled by a scheduler) information
   *
   * @param result The AppResult to be udpated
   */
  private static void loadNoSchedulerInfo(AppResult result) {
    result.scheduler = null;
    result.workflowDepth = 0;
    result.jobExecId = "";
    result.jobDefId = "";
    result.flowExecId = "";
    result.flowDefId = "";
    result.jobExecUrl = "";
    result.jobDefUrl = "";
    result.flowExecUrl = "";
    result.flowDefUrl = "";
    result.jobName = "";
  }
}
