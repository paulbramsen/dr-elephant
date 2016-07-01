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

package com.linkedin.drelephant;

import com.linkedin.drelephant.analysis.AnalyticJob;
import com.linkedin.drelephant.analysis.AnalyticJobGenerator;
import com.linkedin.drelephant.analysis.HDFSContext;
import com.linkedin.drelephant.analysis.AnalyticJobGeneratorHadoop2;

import com.linkedin.drelephant.analysis.HadoopSystemContext;
import com.linkedin.drelephant.math.Statistics;
import com.linkedin.drelephant.security.HadoopSecurity;
import java.io.IOException;
import java.security.PrivilegedAction;
import java.util.List;
import java.util.Set;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.linkedin.drelephant.util.Utils;
import models.AppResult;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.*;
import org.apache.log4j.Logger;


/**
 * The class that runs the Dr. Elephant daemon
 */
public class ElephantRunner implements Runnable {
  private static final Logger logger = Logger.getLogger(ElephantRunner.class);

  private static final String SPARK_APP_TYPE = "spark";
  private static final String RUNNING_JOB_POOL_SIZE_KEY = "drelephant.analysis.realtime.thread.count";
  private static final String COMPLETED_JOB_POOL_SIZE_KEY = "drelephant.analysis.completed.thread.count";
  private static final String FETCH_INTERVAL_KEY = "drelephant.analysis.fetch.interval";
  private static final String FETCH_LAG_KEY = "drelephant.analysis.fetch.lag";
  private static final String RUNNING_JOB_UPDATE_INTERVAL_KEY = "drelephant.analysis.realtime.update.interval";

  // Default interval between fetches
  private static final long DEFAULT_FETCH_INTERVAL = Statistics.MINUTE_IN_MS;
  // Default frequency with which running jobs should be analysed
  private static final long DEFAULT_RUNNING_JOB_UPDATE_INTERVAL = Statistics.MINUTE_IN_MS;
  // We provide one minute job fetch delay due to the job sending lag from AM/NM to JobHistoryServer HDFS
  private static final long DEFAULT_FETCH_LAG = Statistics.MINUTE_IN_MS;
  // The default number of executor threads to analyse completed jobs
  private static final long DEFAULT_COMPLETED_JOB_POOL_SIZE = 5;
  // The default number of executor threads to analyse running jobs
  private static final long DEFAULT_RUNNING_JOB_POOL_SIZE = 5; // TODO: Find optimal number of running threads

  private long _completedExecutorCount = DEFAULT_COMPLETED_JOB_POOL_SIZE;
  private long _runningExecutorCount = DEFAULT_RUNNING_JOB_POOL_SIZE;
  private long _fetchInterval = DEFAULT_FETCH_LAG;
  private long _runningJobUpdateInterval = DEFAULT_RUNNING_JOB_UPDATE_INTERVAL;
  private long _fetchLag = DEFAULT_FETCH_LAG;

  private final HadoopSecurity _hadoopSecurity;
  private final Configuration _configuration;
  private ExecutorService _completedJobPool;
  private ExecutorService _undefinedJobPool;

  // This queue will contain SUCCEEDED and FAILED jobs
  private final DelayQueue<AnalyticJob> _completedJobQueue = new DelayQueue<AnalyticJob>();
  // This queue will contain Jobs in Undefined state (RUNNING, PREP)
  private final DelayQueue<AnalyticJob> _undefinedJobQueue = new DelayQueue<AnalyticJob>();

  private Cluster _cluster;
  private AnalyticJobGenerator _analyticJobGenerator;
  private long _lastTime = 0;
  private long _currentTime = 0;
  private AtomicBoolean _running = new AtomicBoolean(true);

  public ElephantRunner() {
    _hadoopSecurity = new HadoopSecurity();
    _configuration = new Configuration();
    _analyticJobGenerator = new AnalyticJobGeneratorHadoop2();
  }

  @Override
  public void run() {
    try {
      logger.info("Dr.elephant has started");
      initialize();
      _cluster = new Cluster(_configuration);
      _hadoopSecurity.login();
      _hadoopSecurity.doAs(new PrivilegedAction<Void>() {
        @Override
        public Void run() {
          while (_running.get() && !Thread.currentThread().isInterrupted()) {
            _currentTime = System.currentTimeMillis();
            _analyticJobGenerator.updateResourceManagerAddresses();

            // Kerberos Authentation
            try {
              _hadoopSecurity.checkLogin();
            } catch (IOException e) {
              logger.info("Error with hadoop kerberos login", e);
              waitInterval(_fetchInterval);
              continue;
            }

            fetchApplications(_lastTime + 1, _currentTime);
            waitInterval(_fetchInterval);
          }
          logger.info("Main thread is terminated.");
          return null;
        }
      });
    } catch (Exception e) {
      logger.error(e.getMessage());
      logger.error(ExceptionUtils.getStackTrace(e));
      _completedJobPool.shutdown();
      _undefinedJobPool.shutdown();
    }
  }

  private void initialize() {
    if (!HadoopSystemContext.isHadoop2Env()) {
      throw new RuntimeException("Unsupported Hadoop major version detected. It is not 2.x.");
    }

    try {
      _analyticJobGenerator.configure(_configuration);
    } catch (Exception e) {
      logger.error("Error occurred when configuring the analysis provider.", e);
      throw new RuntimeException(e);
    }

    HDFSContext.load();
    ElephantContext.init();
    loadGeneralConfiguration();
    loadExecutorThreads();
  }

  /**
   * Load all the properties from GeneralConf.xml
   */
  private void loadGeneralConfiguration() {
    _configuration.addResource(this.getClass().getClassLoader().getResourceAsStream("GeneralConf.xml"));

    _completedExecutorCount = getLongFromConf(_configuration, COMPLETED_JOB_POOL_SIZE_KEY, DEFAULT_COMPLETED_JOB_POOL_SIZE);
    _runningExecutorCount = getLongFromConf(_configuration, RUNNING_JOB_POOL_SIZE_KEY, DEFAULT_RUNNING_JOB_POOL_SIZE);

    _fetchInterval = getLongFromConf(_configuration, FETCH_INTERVAL_KEY, DEFAULT_FETCH_INTERVAL);
    _fetchLag = getLongFromConf(_configuration, FETCH_LAG_KEY, DEFAULT_FETCH_LAG);

    _runningJobUpdateInterval = getLongFromConf(_configuration, RUNNING_JOB_UPDATE_INTERVAL_KEY,
        DEFAULT_RUNNING_JOB_UPDATE_INTERVAL);
  }

  /**
   * Extract a long value from the configuration
   *
   * @param conf The configuration object
   * @param key The key to be extracted
   * @param defaultValue The default value to use when key is not found
   * @return the extracted value
   */
  private long getLongFromConf(Configuration conf, String key, long defaultValue) {
    long result = defaultValue;
    try {
      result = conf.getLong(key, defaultValue);
    } catch (NumberFormatException e) {
      logger.error("Invalid configuration " + key + " in GeneralConf.xml. Value: " + conf.get(key)
          + ". Resetting it to default value: " + defaultValue);
    }
    return result;
  }

  /**
   * Create a thread pool and load all the executor threads for running and completed jobs
   */
  private void loadExecutorThreads() {
    logger.info("The number of threads analysing completed jobs is " + _completedExecutorCount);
    if (_completedExecutorCount > 0) {
      _completedJobPool = Executors.newFixedThreadPool((int)_completedExecutorCount);
      for (int i = 0; i < _completedExecutorCount; i++) {
        _completedJobPool.submit(new CompletedExecutorThread(i + 1, _completedJobQueue));
      }
    }

    logger.info("The number of threads analysing running jobs is " + _runningExecutorCount);
    if (_runningExecutorCount > 0) {
      _undefinedJobPool = Executors.newFixedThreadPool((int) _runningExecutorCount);
      for (int i = 0; i < _runningExecutorCount; i++) {
        _undefinedJobPool.submit(new UndefinedExecutorThread(i + 1, _undefinedJobQueue));
      }
    }
  }

  /**
   * Fetch all the completed jobs and jobs in <i>undefined</i> state from the resource manager
   * whose start time lie between <i>from</i> and <i>to</i>.
   *
   * Ideally we want to fetch only the completed and running jobs but since the resource manager
   * api doesn't allow filtering by running job, we will fetch all the jobs in <i>undefined</i>
   * state and then poll them.
   *
   * @param from Lower limit on the start time of the job in millisec
   * @param to Upper limit on the start time of the job ib millisec
   */
  private void fetchApplications(long from, long to) {
    logger.info("Fetching all the analytic apps which started between " + from + " and " + to);

    List<AnalyticJob> undefinedJobs;
    List<AnalyticJob> completedJobs;
    try {
      _analyticJobGenerator.updateAuthToken(to - _fetchLag);

      undefinedJobs = _analyticJobGenerator.fetchUndefinedAnalyticJobs(from, to);
      completedJobs = _analyticJobGenerator.fetchCompletedAnalyticJobs(from, to);

      Set<AnalyticJob> commonJobs = Utils.getIntersection(undefinedJobs, completedJobs);
      if (!commonJobs.isEmpty()) {
        // Make sure no job belongs to both queues. This may happen when a job completes(succeeded/failed)
        // after being added to the undefined queue and before being added to the completed queue.
        undefinedJobs.removeAll(commonJobs);
      }
    } catch (Exception e) {
      logger.error("Error fetching job list. Try again later...", e);
      return;
    }

    // There is a lag of job data from AM/NM to JobHistoryServer HDFS, we shouldn't use the current time, since
    // there might be new jobs arriving after we fetch jobs. We provide one minute delay to address this lag.
    for (int i = 0; i < completedJobs.size(); i++) {
      completedJobs.get(i).updateExpiryTime(_fetchLag);
    }

    logger.info("Completed Job queue size is " + (_completedJobQueue.size() + completedJobs.size()));
    logger.info("Undefined Job queue size is " + (_undefinedJobQueue.size() + undefinedJobs.size()));

    _completedJobQueue.addAll(completedJobs);
    _undefinedJobQueue.addAll(undefinedJobs);

    _lastTime = to;
  }

  private class CompletedExecutorThread implements Runnable {
    private int _threadId;
    private DelayQueue<AnalyticJob> _completedJobQueue;

    CompletedExecutorThread(int threadNum, DelayQueue<AnalyticJob> completedJobQueue) {
      this._threadId = threadNum;
      this._completedJobQueue = completedJobQueue;
    }

    @Override
    public void run() {
      while (_running.get() && !Thread.currentThread().isInterrupted()) {
        AnalyticJob analyticJob = null;
        try {
          analyticJob =_completedJobQueue.take();
          Job job = getJobFromCluster(analyticJob);
          analyticJob.setJobStatus(job.getJobState().name()).setSeverity(0);
          JobStatus.State jobState = job.getJobState();

          // Job State can be RUNNING, SUCCEEDED, FAILED, PREP or KILLED
          if (job.getJobState() == JobStatus.State.SUCCEEDED || job.getJobState() == JobStatus.State.FAILED) {
            logger.info("Executor thread " + _threadId + " for completed jobs is analyzing "
                + analyticJob.getAppType().getName() + " " + analyticJob.getAppId() + " :: " + jobState.name());
            AppResult result = analyticJob.getAnalysis();
            AppResult jobInDb = AppResult.find.byId(analyticJob.getAppId());
            if (jobInDb == null) {
              result.save();
            } else {
              // Delete and save to prevent Optimistic Locking
              jobInDb.delete();
              result.save();
            }
          } else {
            // Should not reach here. We consider only SUCCEEDED and FAILED JOBs in _completedJobQueue
            throw new RuntimeException(analyticJob.getAppId() + " is in " + jobState.name() + " state. This should not"
                + " happen. We consider only Succeeded and Failed jobs in Completed Jobs Queue");
          }
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        } catch (Exception e) {
          logger.error(e.getMessage());
          logger.error(ExceptionUtils.getStackTrace(e));
          retryOrDrop(analyticJob);
        }
      }
      logger.info("Executor Thread" + _threadId + " for completed jobs is terminated.");
    }
  }

  private class UndefinedExecutorThread implements Runnable {

    private int _threadId;
    private DelayQueue<AnalyticJob> _undefinedJobQueue;

    UndefinedExecutorThread(int threadNum, DelayQueue<AnalyticJob> undefinedJobQueue) {
      this._threadId = threadNum;
      this._undefinedJobQueue = undefinedJobQueue;
    }

    @Override
    public void run() {
      while (_running.get() && !Thread.currentThread().isInterrupted()) {
        AnalyticJob analyticJob = null;
        try {
          analyticJob =_undefinedJobQueue.take();
          Job job = getJobFromCluster(analyticJob);
          analyticJob.setJobStatus(job.getJobState().name()).setSeverity(0);
          JobStatus.State jobState = job.getJobState();

          // Job State can be RUNNING, SUCCEEDED, FAILED, PREP or KILLED
          if (jobState == JobStatus.State.SUCCEEDED || jobState == JobStatus.State.FAILED) {
            logger.info("App " + analyticJob.getAppId() + " has now completed. Adding it to the completed job Queue.");
            delayedEnqueue(analyticJob.setFinishTime(job.getFinishTime()), _completedJobQueue);
          } else if (jobState == JobStatus.State.RUNNING) {
            logger.info("Executor thread " + _threadId + " for undefined jobs is analyzing "
                + analyticJob.getAppType().getName() + " " + analyticJob.getAppId() + " :: " + jobState.name());
            if (analyticJob.getAppType().getName().equalsIgnoreCase(SPARK_APP_TYPE)) {
              // Ignore as we do not capture any metrics for Spark jobs now
              delayedEnqueue(analyticJob, _undefinedJobQueue);
            } else {
              AppResult result = analyticJob.getAnalysis();
              delayedEnqueue(analyticJob, _undefinedJobQueue);
              if (AppResult.find.byId(analyticJob.getAppId()) == null) {
                result.save();
              } else {
                result.update();
              }
            }
          } else {
            // Ignore Job State
            delayedEnqueue(analyticJob, _undefinedJobQueue);
          }
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        } catch (Exception e) {
          logger.error(e.getMessage());
          logger.error(ExceptionUtils.getStackTrace(e));
          retryOrDrop(analyticJob);
        }
      } logger.info("Running Executor Thread" + _threadId + " is terminated.");
    }
  }

  /**
   * Get the analytic job from the cluster
   *
   * @param analyticJob the job being analyzed
   * @return The Job from the cluster
   * @throws IOException
   * @throws InterruptedException
   */
  private Job getJobFromCluster(AnalyticJob analyticJob) throws IOException, InterruptedException {
    Job job = _cluster.getJob(JobID.forName(Utils.getJobIdFromApplicationId(analyticJob.getAppId())));
    if (job == null) {
      throw new RuntimeException("App " + analyticJob.getAppId() + " not found. This should not happen. Please"
          + " debug this issue.");
    }
    return job;
  }

  /**
   * Update the expiry time for the Delay Queue Item and enqueue.
   * This will make the item available in queue only after its time has expired.
   *
   * @param analyticJob The job to be enqueued
   * @param jobQueue The Delay Queue
   */
  private void delayedEnqueue(AnalyticJob analyticJob, DelayQueue<AnalyticJob> jobQueue) {
    analyticJob.updateExpiryTime(_runningJobUpdateInterval);
    jobQueue.add(analyticJob);
  }

  public DelayQueue<AnalyticJob> getCompletedJobQueue() {
    return _completedJobQueue;
  }

  public DelayQueue<AnalyticJob> getUndefinedJobQueue() {
    return _undefinedJobQueue;
  }

  /**
   * Add analytic job into the retry queue or drop it depending the number of retries.
   *
   * @param analyticJob the job being analyzed
   */
  private void retryOrDrop(AnalyticJob analyticJob) {
    if (analyticJob != null && analyticJob.retry()) {
      logger.error("Add analytic job id [" + analyticJob.getAppId() + "] into the retry list.");
      _analyticJobGenerator.addIntoRetries(analyticJob);
    } else {
      if (analyticJob != null) {
        logger.error("Drop the analytic job. Reason: reached the max retries for application id = [" + analyticJob
            .getAppId() + "].");
      }
    }
  }

  /**
   * Sleep for <i>interval</i> time
   *
   * @param interval the time to wait/sleep
   */
  private void waitInterval(long interval) {
    // Wait for long enough
    long nextRun = _lastTime + interval;
    long waitTime = nextRun - System.currentTimeMillis();

    if (waitTime <= 0) {
      return;
    }

    try {
      Thread.sleep(waitTime);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Kill the Dr. Elephant daemon
   */
  public void kill() {
    _running.set(false);
    if (_completedJobPool != null && !_completedJobPool.isShutdown()) {
      _completedJobPool.shutdownNow();
    }
    if (_undefinedJobPool != null && !_undefinedJobPool.isShutdown()) {
      _undefinedJobPool.shutdownNow();
    }
  }

  public boolean isKilled() {
    if (_completedJobPool != null && _undefinedJobPool != null) {
      return !_running.get() && _completedJobPool.isShutdown() && _undefinedJobPool.isShutdown();
    }
    return true;
  }
}