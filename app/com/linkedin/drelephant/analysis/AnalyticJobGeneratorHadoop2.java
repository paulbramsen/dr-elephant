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

package com.linkedin.drelephant.analysis;

import com.linkedin.drelephant.ElephantContext;
import com.linkedin.drelephant.math.Statistics;
import controllers.MetricsController;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import models.AppResult;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.JobStatus;
import org.apache.hadoop.security.authentication.client.AuthenticatedURL;
import org.apache.hadoop.security.authentication.client.AuthenticationException;
import org.apache.log4j.Logger;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;


/**
 * This class provides a list of analysis promises to be generated under Hadoop YARN environment
 */
public class AnalyticJobGeneratorHadoop2 implements AnalyticJobGenerator {
  private static final Logger logger = Logger.getLogger(AnalyticJobGeneratorHadoop2.class);
  private static final String RESOURCE_MANAGER_ADDRESS = "yarn.resourcemanager.webapp.address";
  private static final String IS_RM_HA_ENABLED = "yarn.resourcemanager.ha.enabled";
  private static final String RESOURCE_MANAGER_IDS = "yarn.resourcemanager.ha.rm-ids";
  private static final String RM_NODE_STATE_URL = "http://%s/ws/v1/cluster/info";
  private static Configuration configuration;

  // Generate a token update interval with a random deviation so that it does not update the token exactly at the same
  // time with other token updaters (e.g. ElephantFetchers).
  private static final long TOKEN_UPDATE_INTERVAL =
      Statistics.MINUTE_IN_MS * 30 + new Random().nextLong() % (3 * Statistics.MINUTE_IN_MS);

  private String _resourceManagerAddress;
  private long _lastTime = 0;
  private long _tokenUpdatedTime = 0;
  private AuthenticatedURL.Token _token;
  private AuthenticatedURL _authenticatedURL;
  private final ObjectMapper _objectMapper = new ObjectMapper();

  private final Queue<AnalyticJob> _retryQueue = new ConcurrentLinkedQueue<AnalyticJob>();

  public void updateResourceManagerAddresses() {
    if (Boolean.valueOf(configuration.get(IS_RM_HA_ENABLED))) {
      String resourceManagers = configuration.get(RESOURCE_MANAGER_IDS);
      if (resourceManagers != null) {
        logger.info("The list of RM IDs are " + resourceManagers);
        List<String> ids = Arrays.asList(resourceManagers.split(","));
<<<<<<< HEAD
        updateAuthToken(System.currentTimeMillis());
        try {
          for (String id : ids) {
=======
        _currentTime = System.currentTimeMillis();
        updateAuthToken();
        for (String id : ids) {
          try {
>>>>>>> master
            String resourceManager = configuration.get(RESOURCE_MANAGER_ADDRESS + "." + id);
            String resourceManagerURL = String.format(RM_NODE_STATE_URL, resourceManager);
            logger.info("Checking RM URL: " + resourceManagerURL);
            JsonNode rootNode = readJsonNode(new URL(resourceManagerURL));
            String status = rootNode.path("clusterInfo").path("haState").getValueAsText();
            if (status.equals("ACTIVE")) {
              logger.info(resourceManager + " is ACTIVE");
              _resourceManagerAddress = resourceManager;
              break;
            } else {
              logger.info(resourceManager + " is STANDBY");
            }
          } catch (AuthenticationException e) {
            logger.info("Error fetching resource manager " + id + " state " + e.getMessage());
          } catch (IOException e) {
            logger.info("Error fetching Json for resource manager "+ id + " status " + e.getMessage());
          }
        }
      }
    } else {
      _resourceManagerAddress = configuration.get(RESOURCE_MANAGER_ADDRESS);
    }
    if (_resourceManagerAddress == null) {
      throw new RuntimeException("Cannot get YARN resource manager address from Hadoop Configuration property: ["
          + RESOURCE_MANAGER_ADDRESS + "].");
    }
  }

  @Override
  public void configure(Configuration configuration)
      throws IOException {
    this.configuration = configuration;
    updateResourceManagerAddresses();
  }

  @Override
  public Configuration getConfiguration() {
    return configuration;
  }

  /**
   *  Fetch all the succeeded and failed applications/analytic jobs from the resource manager.
   *
   * @return
   * @throws IOException
   * @throws AuthenticationException
   */
  @Override
  public List<AnalyticJob> fetchCompletedAnalyticJobs(long from, long to)
      throws IOException, AuthenticationException {
    List<AnalyticJob> appList = new ArrayList<AnalyticJob>();
    logger.info("Fetching all the completed applications");

    // Fetch all succeeded apps
    URL succeededAppsURL = new URL(new URL("http://" + _resourceManagerAddress), String.format(
            "/ws/v1/cluster/apps?finalStatus=SUCCEEDED&startedTimeBegin=%s&startedTimeEnd=%s",
            String.valueOf(from), String.valueOf(to)));
    logger.info("The succeeded apps URL is " + succeededAppsURL);
    JsonNode successList = readApps(succeededAppsURL);
    List<AnalyticJob> succeededApps = filterDuplicates(successList, from);
    appList.addAll(succeededApps);

    // Fetch all failed apps
    URL failedAppsURL = new URL(new URL("http://" + _resourceManagerAddress), String.format(
            "/ws/v1/cluster/apps?finalStatus=FAILED&startedTimeBegin=%s&startedTimeEnd=%s",
            String.valueOf(from), String.valueOf(to)));
    logger.info("The failed apps URL is " + failedAppsURL);
    JsonNode failList = readApps(failedAppsURL);
    List<AnalyticJob> failedApps = filterDuplicates(failList, from);
    appList.addAll(failedApps);

    logger.info("Fetched " + appList.size() + " completed applications.");
    return appList;
  }

  /**
   *  Fetch all the undefined applications/analytic jobs from the resource manager.
   *
   * @return
   * @throws IOException
   * @throws AuthenticationException
   */
  @Override
  public List<AnalyticJob> fetchUndefinedAnalyticJobs(long from, long to)
      throws IOException, AuthenticationException {
    List<AnalyticJob> appList = new ArrayList<AnalyticJob>();
    logger.info("Fetching all the applications in undefined state");

    // Fetch all apps in UNDEFINED state
    URL undefinedAppsURL = new URL(new URL("http://" + _resourceManagerAddress), String.format(
        "/ws/v1/cluster/apps?finalStatus=UNDEFINED&startedTimeBegin=%s&startedTimeEnd=%s",
        String.valueOf(from), String.valueOf(to)));
    logger.info("The undefined apps URL is " + undefinedAppsURL);
    JsonNode undefinedList = readApps(undefinedAppsURL);
    List<AnalyticJob> undefinedApps = filterDuplicates(undefinedList, from);
    appList.addAll(undefinedApps);

    // Append promises from the retry queue at the end of the list
    while (!_retryQueue.isEmpty()) {
      appList.add(_retryQueue.poll());
    }

    logger.info("Fetched " + appList.size() + " applications in undefined state.");
    return appList;
  }

  @Override
  public void addIntoRetries(AnalyticJob promise) {
    _retryQueue.add(promise);
    int retryQueueSize = _retryQueue.size();
    MetricsController.setRetryQueueSize(retryQueueSize);
    logger.info("Retry queue size is " + retryQueueSize);
  }

  /**
   * Authenticate and update the token
   */
  public void updateAuthToken(long currentTime) {
    if (currentTime - _tokenUpdatedTime > TOKEN_UPDATE_INTERVAL) {
      logger.info("AnalysisProvider updating its Authenticate Token...");
      _token = new AuthenticatedURL.Token();
      _authenticatedURL = new AuthenticatedURL();
      _tokenUpdatedTime = currentTime;
    }
  }

  /**
   * Connect to url using token and return the JsonNode
   *
   * @param url The url to connect to
   * @return
   * @throws IOException Unable to get the stream
   * @throws AuthenticationException Authencation problem
   */
  private JsonNode readJsonNode(URL url)
      throws IOException, AuthenticationException {
    HttpURLConnection conn = _authenticatedURL.openConnection(url, _token);
    return _objectMapper.readTree(conn.getInputStream());
  }

  /**
   * Parse the returned json from Resource manager
   *
   * @param url The REST call
   * @return
   * @throws IOException
   * @throws AuthenticationException Problem authenticating to resource manager
   */
  private JsonNode readApps(URL url) throws IOException, AuthenticationException {
    List<AnalyticJob> appList = new ArrayList<AnalyticJob>();
    JsonNode rootNode = readJsonNode(url);
    return rootNode.path("apps").path("app");
  }

  /**
   * When called first time after launch, hit the DB and avoid duplicated analytic jobs that have been analyzed
   * before.
   * @param apps The list of apps in JsonNode
   * @param lastTime The upper limit of the last fetch interval
   * @return The filtered list of analytic jobs
   */
  private List<AnalyticJob> filterDuplicates(JsonNode apps, long lastTime) {
    List<AnalyticJob> appList = new ArrayList<AnalyticJob>();
    for (JsonNode app : apps) {
      String appId = app.get("id").getValueAsText();
      if (!isFirstFetch() || !isAppAnalyzed(appId)) {
        String user = app.get("user").getValueAsText();
        String name = app.get("name").getValueAsText();
        String queueName = app.get("queue").getValueAsText();
        String finalStatus = app.get("finalStatus").getValueAsText();
        String trackingUrl = app.get("trackingUrl") != null? app.get("trackingUrl").getValueAsText() : null;
        long startTime = app.get("startedTime").getLongValue();
        long finishTime = app.get("finishedTime").getLongValue();

        ApplicationType type =
            ElephantContext.instance().getApplicationTypeForName(app.get("applicationType").getValueAsText());

        // If the application type is supported
        if (type != null) {
          AnalyticJob analyticJob = new AnalyticJob();
          analyticJob.setAppId(appId).setAppType(type).setUser(user).setName(name).setQueueName(queueName)
              .setTrackingUrl(trackingUrl).setStartTime(startTime).setFinishTime(finishTime).setFinalStatus(finalStatus);

          appList.add(analyticJob);
        }
      }
    }
    return appList;
  }

  /**
   * Check if this is the first time Dr. Elephant is fetching after a restart.
   * @return true, if first time after restart. false, otherwise
   */
  private boolean isFirstFetch() {
    return _lastTime == 0;
  }

  /**
   * Check if the application analysis has completed
   * @param appId
   * @return true, if database contains a record with status != SUCCEEDED and status != FAILED, false otherwise
   */
  private boolean isAppComplete(String appId) {
    return !AppResult.find.select("id").where().eq(AppResult.TABLE.ID, appId)
        .eq(AppResult.TABLE.STATUS, JobStatus.State.SUCCEEDED.name())
        .eq(AppResult.TABLE.STATUS, JobStatus.State.FAILED.name()).findList().isEmpty();
  }

  /**
   * An application is analyzed if it has an entry in the database and is in succeeded or failed state
   * @param appId
   * @return
   */
  private boolean isAppAnalyzed(String appId) {
    return AppResult.find.byId(appId) != null && isAppComplete(appId);
  }
}
