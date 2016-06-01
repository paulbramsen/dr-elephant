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
import com.linkedin.drelephant.analysis.ApplicationType;
import com.linkedin.drelephant.analysis.HDFSContext;
import com.linkedin.drelephant.analysis.HadoopSystemContext;
import com.linkedin.drelephant.analysis.AnalyticJobGeneratorHadoop2;

import com.linkedin.drelephant.math.Statistics;
import com.linkedin.drelephant.security.HadoopSecurity;
import java.io.IOException;
import java.net.URL;
import java.security.PrivilegedAction;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
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

  // Interval between fetches
  private static final long FETCH_INTERVAL = Statistics.MINUTE_IN_MS;
  // Time to wait if fetching job details fail
  private static final long WAIT_INTERVAL = Statistics.MINUTE_IN_MS;
  // Frequency with which running jobs should be analysed
  private static final long JOB_UPDATE_INTERVAL = Statistics.MINUTE_IN_MS;
  // We provide one minute job fetch delay due to the job sending lag from AM/NM to JobHistoryServer HDFS
  public static final long FETCH_DELAY = Statistics.MINUTE_IN_MS;
  private static final String SPARK_APP_TYPE = "spark";

  private AtomicBoolean _running = new AtomicBoolean(true);
  private long lastRun;

  private final HadoopSecurity _hadoopSecurity;
  private final ExecutorService _completedJobPool;
  private final ExecutorService _undefinedJobPool;
  private final DelayQueue<AnalyticJob> _completedJobQueue;  // SUCCEEDED and FAILED jobs
  private final DelayQueue<AnalyticJob> _undefinedJobQueue;  // Jobs in Undefined state (RUNNING, PREP)

  private AnalyticJobGenerator _analyticJobGenerator;
  private Cluster _cluster;
  private long _lastTime = 0;
  private long _currentTime = 0;

  public ElephantRunner(int completedJobPoolSize, int runningJobPoolSize) throws IOException {
    _hadoopSecurity = new HadoopSecurity();
    _cluster = new Cluster(new Configuration());

    _completedJobPool = Executors.newFixedThreadPool(completedJobPoolSize);
    _completedJobQueue = new DelayQueue<AnalyticJob>();
    for (int i = 0; i < completedJobPoolSize; i++) {
      _completedJobPool.submit(new CompletedExecutorThread(i + 1, _completedJobQueue));
    }

    _undefinedJobPool = Executors.newFixedThreadPool(runningJobPoolSize);
    // Delay Queue will ensure running jobs to be analysed after a fixed delay/interval
    _undefinedJobQueue = new DelayQueue<AnalyticJob>();
    for (int i = 0; i < runningJobPoolSize; i++) {
      _undefinedJobPool.submit(new UndefinedExecutorThread(i + 1, _undefinedJobQueue));
    }
  }

  private void loadAnalyticJobGenerator() {
    Configuration configuration = new Configuration();
    if (HadoopSystemContext.isHadoop2Env()) {
      _analyticJobGenerator = new AnalyticJobGeneratorHadoop2();
    } else {
      throw new RuntimeException("Unsupported Hadoop major version detected. It is not 2.x.");
    }

    try {
      _analyticJobGenerator.configure(configuration);
    } catch (Exception e) {
      logger.error("Error occurred when configuring the analysis provider.", e);
      throw new RuntimeException(e);
    }
  }

  @Override
  public void run() {
    try {
      logger.info("Dr.elephant has started");
      _hadoopSecurity.doAs(new PrivilegedAction<Void>() {
        @Override
        public Void run() {
          HDFSContext.load();
          loadAnalyticJobGenerator();
          ElephantContext.init();

          while (_running.get() && !Thread.currentThread().isInterrupted()) {
            _analyticJobGenerator.updateResourceManagerAddresses();
            lastRun = System.currentTimeMillis();
            logger.info("Fetching analytic job list...");

            try {
              _hadoopSecurity.checkLogin();
            } catch (IOException e) {
              logger.info("Error with hadoop kerberos login", e);
              //Wait for a while before retry
              waitInterval(WAIT_INTERVAL);
              continue;
            }

            List<AnalyticJob> undefinedJobs;
            List<AnalyticJob> completedJobs;
            try {
              _currentTime = System.currentTimeMillis();
              undefinedJobs = _analyticJobGenerator.fetchUndefinedAnalyticJobs(_lastTime + 1, _currentTime);
              completedJobs = _analyticJobGenerator.fetchCompletedAnalyticJobs(_lastTime + 1, _currentTime);
              _lastTime = _currentTime;
            } catch (Exception e) {
              logger.error("Error fetching job list. Try again later...", e);
              //Wait for a while before retry
              waitInterval(WAIT_INTERVAL);
              continue;
            }
            _undefinedJobQueue.addAll(undefinedJobs);

            // There is a lag of job data from AM/NM to JobHistoryServer HDFS, we shouldn't use the current time,
            // since there might be new jobs arriving after we fetch jobs. We provide one minute delay to address this
            // lag.
            for (int i = 0; i < completedJobs.size(); i++) {
              completedJobs.get(i).updateExpiryTime(FETCH_DELAY);
            }
            _completedJobQueue.addAll(completedJobs);
            logger.info("Completed Job queue size is " + _completedJobQueue.size());
            logger.info("Undefined Job queue size is " + _undefinedJobQueue.size());

            //Wait for a while before next fetch
            waitInterval(FETCH_INTERVAL);
          }
          logger.info("Main thread is terminated.");
          return null;
        }
      });
    } catch (IOException e) {
      logger.error(e.getMessage());
      logger.error(ExceptionUtils.getStackTrace(e));
      _completedJobPool.shutdown();
      _undefinedJobPool.shutdown();
    }
  }

  private class CompletedExecutorThread implements Runnable {

    private int _threadId;
    private BlockingQueue<AnalyticJob> _completedJobQueue;

    CompletedExecutorThread(int threadNum, BlockingQueue<AnalyticJob> completedJobQueue) {
      this._threadId = threadNum;
      this._completedJobQueue = completedJobQueue;
    }

    @Override
    public void run() {
      while (_running.get() && !Thread.currentThread().isInterrupted()) {
        AnalyticJob analyticJob = null;
        try {
          analyticJob = _completedJobQueue.take();
          Job job = _cluster.getJob(JobID.forName(Utils.getJobIdFromApplicationId(analyticJob.getAppId())));
          if (job == null) {
            throw new RuntimeException("App " + analyticJob.getAppId() + " not found. This should not happen. Please"
                + " debug this issue.");
          }
          JobStatus.State jobState = job.getJobState();
          analyticJob.setJobStatus(jobState.name()).setSeverity(-1);
          logger.info("Executor thread " + _threadId + " for completed jobs is analyzing "
              + analyticJob.getAppType().getName() + " " + analyticJob.getAppId() + " " + jobState.name());

          // Job State can be RUNNING, SUCCEEDED, FAILED, PREP or KILLED
          if (jobState == JobStatus.State.SUCCEEDED || jobState == JobStatus.State.FAILED) {
            AppResult result = analyticJob.getAnalysis();
            if (AppResult.find.byId(analyticJob.getAppId()) == null) {
              result.save();
            } else {
              result.update();
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
          retryAndDrop(analyticJob);
        }
      }
      logger.info("Executor Thread" + _threadId + " for completed jobs is terminated.");
    }
  }

  private class UndefinedExecutorThread implements Runnable {

    private int _threadId;
    private BlockingQueue<AnalyticJob> _undefinedJobQueue;

    UndefinedExecutorThread(int threadNum, BlockingQueue<AnalyticJob> undefinedJobQueue) {
      this._threadId = threadNum;
      this._undefinedJobQueue = undefinedJobQueue;
    }

    @Override
    public void run() {
      while (_running.get() && !Thread.currentThread().isInterrupted()) {
        AnalyticJob analyticJob = null;
        try {
          analyticJob = _undefinedJobQueue.take();
          Job job = _cluster.getJob(JobID.forName(Utils.getJobIdFromApplicationId(analyticJob.getAppId())));
          if (job == null) {
            throw new RuntimeException("App " + analyticJob.getAppId() + " not found. This should not happen. Please"
                + " debug this issue.");
          }
          JobStatus.State jobState = job.getJobState();
          analyticJob.setJobStatus(jobState.name()).setFinalStatus(jobState.name()).setSeverity(-1);;

          if (jobState == JobStatus.State.SUCCEEDED || jobState == JobStatus.State.FAILED) {
            analyticJob.setFinishTime(job.getFinishTime());
            logger.info("App " + analyticJob.getAppId() + " has now completed. Adding it to the completed job Queue.");
            _completedJobQueue.add(analyticJob);
          } else if (jobState == JobStatus.State.RUNNING) {
            logger.info("Executor thread " + _threadId + " for undefined jobs is analyzing "
                + analyticJob.getAppType().getName() + " " + analyticJob.getAppId() + " " + jobState.name());
            if (analyticJob.getAppType().getName().equalsIgnoreCase(SPARK_APP_TYPE)) {
              // Ignore as we do not capture any metrics for Spark jobs
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
            delayedEnqueue(analyticJob, _undefinedJobQueue);
          }
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        } catch (Exception e) {
          logger.error(e.getMessage());
          logger.error(ExceptionUtils.getStackTrace(e));
          retryAndDrop(analyticJob);
        }
      } logger.info("Running Executor Thread" + _threadId + " is terminated.");
    }
  }

  private void delayedEnqueue(AnalyticJob analyticJob, BlockingQueue<AnalyticJob> jobQueue) {
    analyticJob.updateExpiryTime(JOB_UPDATE_INTERVAL);
    jobQueue.add(analyticJob);
  }

  private void retryAndDrop(AnalyticJob analyticJob) {
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

  private void waitInterval(long interval) {
    // Wait for long enough
    long nextRun = lastRun + interval;
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

  public void kill() {
    _running.set(false);
    if (_completedJobPool != null && !_completedJobPool.isShutdown()) {
      _completedJobPool.shutdownNow();
    }
    if (_undefinedJobPool != null && !_undefinedJobPool.isShutdown()) {
      _undefinedJobPool.shutdownNow();
    }
  }

}