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

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import com.linkedin.drelephant.analysis.AnalyticJob;
import com.linkedin.drelephant.analysis.AnalyticJobGenerator;
import com.linkedin.drelephant.analysis.HDFSContext;
import com.linkedin.drelephant.analysis.HadoopSystemContext;
import com.linkedin.drelephant.analysis.AnalyticJobGeneratorHadoop2;

import com.linkedin.drelephant.security.HadoopSecurity;
import java.io.IOException;
import java.security.PrivilegedAction;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import com.linkedin.drelephant.util.Utils;
import models.AppResult;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.log4j.Logger;


/**
 * The class that runs the Dr. Elephant daemon
 */
public class ElephantRunner implements Runnable {
  private static final Logger logger = Logger.getLogger(ElephantRunner.class);

  private static final long FETCH_INTERVAL = 60 * 1000;     // Interval between fetches
  private static final long RETRY_INTERVAL = 60 * 1000;     // Interval between retries
  private static final int EXECUTOR_NUM = 5;                // The number of executor threads to analyse the jobs

  private static final String FETCH_INTERVAL_KEY = "drelephant.analysis.fetch.interval";
  private static final String RETRY_INTERVAL_KEY = "drelephant.analysis.retry.interval";
  private static final String EXECUTOR_NUM_KEY = "drelephant.analysis.thread.count";

  private AtomicBoolean _running = new AtomicBoolean(true);
  private long lastRun;
  private long _fetchInterval;
  private long _retryInterval;
  private int _executorNum;
  private HadoopSecurity _hadoopSecurity;
  private ExecutorService _service;
  private BlockingQueue<AnalyticJob> _jobQueue;
  private AnalyticJobGenerator _analyticJobGenerator;

  private void loadGeneralConfiguration() {
    Configuration configuration = ElephantContext.instance().getGeneralConf();

    _executorNum = Utils.getNonNegativeInt(configuration, EXECUTOR_NUM_KEY, EXECUTOR_NUM);
    _fetchInterval = Utils.getNonNegativeLong(configuration, FETCH_INTERVAL_KEY, FETCH_INTERVAL);
    _retryInterval = Utils.getNonNegativeLong(configuration, RETRY_INTERVAL_KEY, RETRY_INTERVAL);
  }

  private void loadAnalyticJobGenerator() {
    if (HadoopSystemContext.isHadoop2Env()) {
      _analyticJobGenerator = new AnalyticJobGeneratorHadoop2();
    } else {
      throw new RuntimeException("Unsupported Hadoop major version detected. It is not 2.x.");
    }

    try {
      _analyticJobGenerator.configure(ElephantContext.instance().getGeneralConf());
    } catch (Exception e) {
      logger.error("Error occurred when configuring the analysis provider.", e);
      throw new RuntimeException(e);
    }
  }

  @Override
  public void run() {
    logger.info("Dr.elephant has started");
    try {
      _hadoopSecurity = new HadoopSecurity();
      _hadoopSecurity.doAs(new PrivilegedAction<Void>() {
        @Override
        public Void run() {
          HDFSContext.load();
          loadGeneralConfiguration();
          loadAnalyticJobGenerator();
          ElephantContext.init();

          _jobQueue = new LinkedBlockingQueue<AnalyticJob>();
          logger.info("executor num is " + _executorNum);
          if (_executorNum > 0) {
            _service = Executors.newFixedThreadPool(_executorNum,
                    new ThreadFactoryBuilder().setNameFormat("dr-el-executor-thread-%d").build());
            for (int i = 0; i < _executorNum; i++) {
              _service.submit(new ExecutorThread(_jobQueue));
            }
          }

          while (_running.get() && !Thread.currentThread().isInterrupted()) {
            _analyticJobGenerator.updateResourceManagerAddresses();
            lastRun = System.currentTimeMillis();

            logger.info("Fetching analytic job list...");

            try {
              _hadoopSecurity.checkLogin();
            } catch (IOException e) {
              logger.info("Error with hadoop kerberos login", e);
              //Wait for a while before retry
              waitInterval(_retryInterval);
              continue;
            }

            List<AnalyticJob> todos;
            try {
              todos = _analyticJobGenerator.fetchAnalyticJobs();
            } catch (Exception e) {
              logger.error("Error fetching job list. Try again later...", e);
              //Wait for a while before retry
              waitInterval(_retryInterval);
              continue;
            }

            _jobQueue.addAll(todos);
            logger.info("Job queue size is " + _jobQueue.size());

            //Wait for a while before next fetch
            waitInterval(_fetchInterval);
          }
          logger.info("Main thread is terminated.");
          return null;
        }
      });
    } catch (Exception e) {
      logger.error(e.getMessage());
      logger.error(ExceptionUtils.getStackTrace(e));
    }
  }

  private class ExecutorThread implements Runnable {

    private BlockingQueue<AnalyticJob> _jobQueue;

    ExecutorThread(BlockingQueue<AnalyticJob> jobQueue) {
      this._jobQueue = jobQueue;
    }

    @Override
    public void run() {
      while (_running.get() && !Thread.currentThread().isInterrupted()) {
        AnalyticJob analyticJob = null;
        try {
          analyticJob = _jobQueue.take();
          String analysisName = String.format("%s %s", analyticJob.getAppType().getName(), analyticJob.getAppId());
          long analysisStartTimeMillis = System.currentTimeMillis();
          logger.info(String.format("Analyzing %s", analysisName));
          AppResult result = analyticJob.getAnalysis();
          result.save();
          logger.info(String.format("Analysis of %s took %sms", analysisName, System.currentTimeMillis() - analysisStartTimeMillis));

        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        } catch (Exception e) {
          logger.error(e.getMessage());
          logger.error(ExceptionUtils.getStackTrace(e));

          if (analyticJob != null && analyticJob.retry()) {
            logger.error("Add analytic job id [" + analyticJob.getAppId() + "] into the retry list.");
            _analyticJobGenerator.addIntoRetries(analyticJob);
          } else {
            if (analyticJob != null) {
              logger.error("Drop the analytic job. Reason: reached the max retries for application id = ["
                      + analyticJob.getAppId() + "].");
            }
          }
        }
      }
      logger.info("Executor thread terminated.");
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
    if (_service != null) {
      _service.shutdownNow();
    }
  }
}
