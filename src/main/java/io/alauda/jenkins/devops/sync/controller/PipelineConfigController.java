package io.alauda.jenkins.devops.sync.controller;

import hudson.Extension;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1Condition;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfigList;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.ConnectionAliveDetectTask;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.client.JenkinsClient;
import io.alauda.jenkins.devops.sync.client.PipelineConfigClient;
import io.alauda.jenkins.devops.sync.constants.Constants;
import io.alauda.jenkins.devops.sync.exception.PipelineConfigConvertException;
import io.alauda.jenkins.devops.sync.monitor.Metrics;
import io.alauda.jenkins.devops.sync.util.ConditionUtils;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import io.alauda.jenkins.devops.sync.util.PipelineConfigUtils;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.extended.controller.Controller;
import io.kubernetes.client.extended.controller.builder.ControllerBuilder;
import io.kubernetes.client.extended.controller.builder.ControllerManagerBuilder;
import io.kubernetes.client.extended.controller.reconciler.Reconciler;
import io.kubernetes.client.extended.controller.reconciler.Request;
import io.kubernetes.client.extended.controller.reconciler.Result;
import io.kubernetes.client.extended.workqueue.DefaultRateLimitingQueue;
import io.kubernetes.client.extended.workqueue.RateLimitingQueue;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.informer.cache.Lister;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Extension
public class PipelineConfigController
    implements ResourceController, ConnectionAliveDetectTask.HeartbeatResourceDetector {

  private static final Logger logger = LoggerFactory.getLogger(PipelineConfigController.class);
  private static final String CONTROLLER_NAME = "PipelineConfigController";

  private LocalDateTime lastEventComingTime;
  private RateLimitingQueue<Request> queue;

  @Override
  public void add(ControllerManagerBuilder managerBuilder, SharedInformerFactory factory) {
    DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();

    SharedIndexInformer<V1alpha1PipelineConfig> informer =
        factory.getExistingSharedIndexInformer(V1alpha1PipelineConfig.class);
    if (informer == null) {
      informer =
          factory.sharedIndexInformerFor(
              callGeneratorParams ->
                  api.listPipelineConfigForAllNamespacesCall(
                      null,
                      null,
                      null,
                      "jenkins=" + AlaudaSyncGlobalConfiguration.get().getJenkinsService(),
                      null,
                      null,
                      callGeneratorParams.resourceVersion,
                      callGeneratorParams.timeoutSeconds,
                      callGeneratorParams.watch,
                      null,
                      null),
              V1alpha1PipelineConfig.class,
              V1alpha1PipelineConfigList.class,
              TimeUnit.MINUTES.toMillis(AlaudaSyncGlobalConfiguration.get().getResyncPeriod()));
    }

    PipelineConfigClient client = new PipelineConfigClient(informer);
    Clients.register(V1alpha1PipelineConfig.class, client);

    queue = new DefaultRateLimitingQueue<>(Executors.newSingleThreadExecutor());

    Controller controller =
        ControllerBuilder.defaultBuilder(factory)
            .withWorkQueue(queue)
            .watch(
                (workQueue) ->
                    ControllerBuilder.controllerWatchBuilder(
                            V1alpha1PipelineConfig.class, workQueue)
                        .withWorkQueueKeyFunc(
                            pipelineConfig ->
                                new Request(
                                    pipelineConfig.getMetadata().getNamespace(),
                                    pipelineConfig.getMetadata().getName()))
                        .withOnAddFilter(
                            pipelineConfig -> {
                              Metrics.incomingRequestCounter.labels("pipeline_config", "add").inc();

                              logger.debug(
                                  "[{}] receives event: Add; PipelineConfig '{}/{}'",
                                  CONTROLLER_NAME,
                                  pipelineConfig.getMetadata().getNamespace(),
                                  pipelineConfig.getMetadata().getName());
                              return true;
                            })
                        .withOnUpdateFilter(
                            (oldPipelineConfig, newPipelineConfig) -> {
                              Metrics.incomingRequestCounter
                                  .labels("pipeline_config", "update")
                                  .inc();

                              String namespace = oldPipelineConfig.getMetadata().getNamespace();
                              String name = oldPipelineConfig.getMetadata().getName();

                              logger.debug(
                                  "[{}] receives event: Update; PipelineConfig '{}/{}'",
                                  CONTROLLER_NAME,
                                  namespace,
                                  name);

                              return true;
                            })
                        .withOnDeleteFilter(
                            (pipelineConfig, aBoolean) -> {
                              Metrics.incomingRequestCounter
                                  .labels("pipeline_config", "delete")
                                  .inc();
                              logger.debug(
                                  "[{}] receives event: Delete; PipelineConfig '{}/{}'",
                                  CONTROLLER_NAME,
                                  pipelineConfig.getMetadata().getNamespace(),
                                  pipelineConfig.getMetadata().getName());
                              return true;
                            })
                        .build())
            .withReconciler(new PipelineConfigReconciler(new Lister<>(informer.getIndexer())))
            .withName(CONTROLLER_NAME)
            .withWorkerCount(4)
            .build();

    managerBuilder.addController(controller);
  }

  @Override
  public LocalDateTime lastEventComingTime() {
    return lastEventComingTime;
  }

  @Override
  public String resourceName() {
    return "PipelineConfig";
  }

  @Override
  public boolean hasResourceExists() throws ApiException {
    DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
    V1alpha1PipelineConfigList pipelineConfigList =
        api.listPipelineConfigForAllNamespaces(null, null, null, null, 1, null, "0", null, null);

    return pipelineConfigList != null
        && pipelineConfigList.getItems() != null
        && pipelineConfigList.getItems().size() != 0;
  }

  class PipelineConfigReconciler implements Reconciler {

    private Lister<V1alpha1PipelineConfig> lister;
    private JenkinsClient jenkinsClient;

    PipelineConfigReconciler(Lister<V1alpha1PipelineConfig> lister) {
      this.lister = lister;
      this.jenkinsClient = JenkinsClient.getInstance();
    }

    @Override
    public Result reconcile(Request request) {
      lastEventComingTime = LocalDateTime.now();

      Metrics.completedRequestCounter.labels("pipeline_config").inc();
      Metrics.remainedRequestsGauge.labels("pipeline_config").set(queue.length());

      String namespace = request.getNamespace();
      String name = request.getName();

      V1alpha1PipelineConfig pc = lister.namespace(namespace).get(name);
      if (pc == null) {
        logger.debug(
            "[{}] Cannot found PipelineConfig '{}/{}' in local lister, will try to remove it's correspondent Jenkins job",
            getControllerName(),
            namespace,
            name);
        boolean deleteSucceed;
        try {
          deleteSucceed = jenkinsClient.deleteJob(new NamespaceName(namespace, name));
          if (!deleteSucceed) {
            logger.warn(
                "[{}] Failed to delete job for PipelineConfig '{}/{}'",
                getControllerName(),
                namespace,
                name);
          }
        } catch (IOException | InterruptedException e) {
          logger.warn(
              "[{}] Failed to delete job for PipelineConfig '{}/{}', reason {}",
              getControllerName(),
              namespace,
              name,
              e.getMessage());
          Thread.currentThread().interrupt();
        }
        return new Result(false);
      }

      V1alpha1Condition initializedCondition =
          ConditionUtils.getCondition(
              pc.getStatus().getConditions(), Constants.PIPELINE_CONFIG_CONDITION_TYPE_INITIALIZED);
      if (initializedCondition == null
          || !initializedCondition.getStatus().equals(Constants.CONDITION_STATUS_TRUE)) {
        logger.debug(
            "[{}] PipelineConfig '{}/{}' not initialized, skip this reconcile",
            getControllerName(),
            namespace,
            name);
        return new Result(false);
      }

      // clone PipelineConfig so that we won't modify it in two places
      pc = DeepCopyUtils.deepCopy(pc);
      V1alpha1PipelineConfig pipelineConfigCopy = DeepCopyUtils.deepCopy(pc);

      V1alpha1Condition syncedCondition =
          ConditionUtils.getCondition(
              pipelineConfigCopy.getStatus().getConditions(),
              Constants.PIPELINE_CONFIG_CONDITION_TYPE_SYNCED);
      if (syncedCondition == null) {
        logger.debug(
            "[{}] PipelineConfig '{}/{}' doesn't have Synced condition, skip this reconcile",
            getControllerName(),
            namespace,
            name);
        return new Result(false);
      }
      if (syncedCondition.getStatus().equals(Constants.CONDITION_STATUS_TRUE)) {
        logger.debug(
            "[{}] PipelineConfig '{}/{}' already synced, skip this reconcile",
            getControllerName(),
            namespace,
            name);
        return new Result(false);
      }

      logger.debug(
          "[{}] Start to create or update Jenkins job for PipelineConfig '{}/{}'",
          getControllerName(),
          namespace,
          name);

      syncedCondition.status(Constants.CONDITION_STATUS_TRUE).lastAttempt(DateTime.now());

      PipelineConfigUtils.dependencyCheck(
          pipelineConfigCopy, pipelineConfigCopy.getStatus().getConditions());
      try {
        if (!jenkinsClient.hasSyncedJenkinsJob(pipelineConfigCopy)) {
          boolean succeedUpdated = jenkinsClient.upsertJob(pipelineConfigCopy);
          if (!succeedUpdated) {
            return new Result(false);
          }
        }
      } catch (PipelineConfigConvertException | IOException e) {
        logger.warn(
            "[{}] Failed to convert PipelineConfig '{}/{}' to Jenkins Job, reason {}",
            getControllerName(),
            namespace,
            name,
            e.getMessage());
        syncedCondition
            .status(Constants.CONDITION_STATUS_FALSE)
            .reason(Constants.PIPELINE_CONFIG_CONDITION_REASON_CREATE_JENKINS_JOB_FAILED)
            .message(e.getMessage());
      }

      logger.debug("[{}] Will update PipelineConfig '{}/{}'", getControllerName(), namespace, name);
      PipelineConfigClient pipelineConfigClient =
          (PipelineConfigClient) Clients.get(V1alpha1PipelineConfig.class);
      boolean succeed = pipelineConfigClient.update(pc, pipelineConfigCopy);
      return new Result(!succeed);
    }

    private String getControllerName() {
      return CONTROLLER_NAME;
    }
  }
}
