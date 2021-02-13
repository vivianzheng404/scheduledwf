package net.jas34.scheduledwf.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.PreDestroy;

import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.cronutils.utils.VisibleForTesting;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.core.utils.IDGenerator;
import com.netflix.conductor.dao.MetadataDAO;
import net.jas34.scheduledwf.dao.IndexScheduledWfDAO;
import net.jas34.scheduledwf.dao.ScheduledWfMetadataDAO;
import net.jas34.scheduledwf.dao.SchedulerManagerExecutionDAO;
import net.jas34.scheduledwf.metadata.ScheduleWfDef;
import net.jas34.scheduledwf.run.ManagerInfo;
import net.jas34.scheduledwf.run.ScheduledWorkFlow;
import net.jas34.scheduledwf.run.SchedulingResult;
import net.jas34.scheduledwf.run.ShutdownResult;
import net.jas34.scheduledwf.run.Status;

/**
 * @author Jasbir Singh
 */
@Singleton
public class DefaultSchedulerManager implements SchedulerManager {

    private final Logger logger = LoggerFactory.getLogger(DefaultSchedulerManager.class);

    private final ScheduledWfMetadataDAO scheduledWfMetadataDAO;
    private final SchedulerManagerExecutionDAO managerExecutionDAO;
    private final ScheduledProcessRegistry processRegistry;
    private final MetadataDAO metadataDAO;
    private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1);
    private final IndexScheduledWfDAO indexDAO;
    private final WorkflowSchedulingAssistant schedulingAssistant;
    private ManagerInfo managerInfo;
    private boolean isJunitRun;

    @Inject
    public DefaultSchedulerManager(ScheduledWfMetadataDAO scheduledWfMetadataDAO,
            SchedulerManagerExecutionDAO managerExecutionDAO, ScheduledProcessRegistry processRegistry,
            MetadataDAO metadataDAO, IndexScheduledWfDAO indexDAO,
            WorkflowSchedulingAssistant schedulingAssistant) {
        this(scheduledWfMetadataDAO, managerExecutionDAO, processRegistry, metadataDAO, indexDAO,
                schedulingAssistant, false);
    }

    public DefaultSchedulerManager(ScheduledWfMetadataDAO scheduledWfMetadataDAO,
            SchedulerManagerExecutionDAO managerExecutionDAO, ScheduledProcessRegistry processRegistry,
            MetadataDAO metadataDAO, IndexScheduledWfDAO indexDAO,
            WorkflowSchedulingAssistant schedulingAssistant, boolean isJunitRun) {
        this.scheduledWfMetadataDAO = scheduledWfMetadataDAO;
        this.managerExecutionDAO = managerExecutionDAO;
        this.processRegistry = processRegistry;
        this.metadataDAO = metadataDAO;
        this.indexDAO = indexDAO;
        this.schedulingAssistant = schedulingAssistant;
        this.isJunitRun = isJunitRun;
        registerManager();
        if (!isJunitRun) {
            manageProcesses();
        }
    }

    @VisibleForTesting
    void setManagerInfo(ManagerInfo managerInfo) {
        if (isJunitRun) {
            this.managerInfo = managerInfo;
        }
    }

    @Override
    public void registerManager() {
        long currentTimeStamp = System.currentTimeMillis();
        managerInfo = new ManagerInfo();
        managerInfo.setId(IDGenerator.generate());
        managerInfo.setName(DefaultSchedulerManager.class.getSimpleName());
        managerInfo.setNodeAddress(getNodeAddress());
        managerInfo.setCreateTime(currentTimeStamp);
        managerExecutionDAO.registerManager(managerInfo);
    }

    @Override
    public void manageProcesses() {
        scheduledExecutorService.scheduleAtFixedRate(() -> {
            scheduleApplicableWorkflows();
            manageShutDownProcesses();

        }, 1000, 1000, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    @Override
    public void shutdownProcesses() {
        List<ScheduledWorkFlow> runningProcesses =
                processRegistry.getAllRunningProcesses(managerInfo.getId());
        schedulingAssistant.shutdownAllSchedulersWithFailSafety(runningProcesses);
        processRegistry.shutDownRegistry(managerInfo.getId(), 5000);
        // TODO: to index shutdown processes using indexDAO.
        scheduledExecutorService.shutdown();
    }

    @VisibleForTesting
    List<SchedulingResult> scheduleApplicableWorkflows() {
        Optional<List<ScheduleWfDef>> scheduledWorkflowOptional =
                scheduledWfMetadataDAO.getAllScheduledWorkflowDefsByStatus(ScheduleWfDef.Status.RUN);

        if (!scheduledWorkflowOptional.isPresent()
                || CollectionUtils.isEmpty(scheduledWorkflowOptional.get())) {
            logger.debug("No scheduled workflow definitions available. No process to be scheduled");
            return null;
        }

        List<ScheduleWfDef> unScheduledWorkflows = scheduledWorkflowOptional
                .get().stream().filter(scheduleWfDef -> processRegistry
                        .isProcessTobeScheduled(scheduleWfDef.getWfName(), managerInfo.getId()))
                .collect(Collectors.toList());

        if (CollectionUtils.isEmpty(unScheduledWorkflows)) {
            logger.info("No workflow left to be scheduled.");
            return null;
        }

        List<SchedulingResult> results = new ArrayList<>();
        unScheduledWorkflows.forEach(unScheduledWorkflow -> {
            Optional<WorkflowDef> workflowDef = metadataDAO.getWorkflowDef(unScheduledWorkflow.getWfName(),
                    unScheduledWorkflow.getWfVersion());
            if (!workflowDef.isPresent()) {
                logger.info(
                        "No workflow definition present for workflow.name={}, workflow.version={}. Nothing will be scheduled for this workflow",
                        unScheduledWorkflow.getWfName(), unScheduledWorkflow.getWfVersion());
                return;
            }

            logger.info("Going to schedule workflow with name={} on node={}", unScheduledWorkflow.getWfName(),
                    managerInfo.getName());
            ScheduledWorkFlow scheduledWorkFlow = new ScheduledWorkFlow();
            scheduledWorkFlow.setId(IDGenerator.generate());
            scheduledWorkFlow.setName(unScheduledWorkflow.getWfName());
            scheduledWorkFlow.setNodeAddress(managerInfo.getNodeAddress());
            scheduledWorkFlow.setWfName(unScheduledWorkflow.getWfName());
            scheduledWorkFlow.setWfVersion(unScheduledWorkflow.getWfVersion());
            scheduledWorkFlow.setWfInput(unScheduledWorkflow.getWfInput());
            scheduledWorkFlow.setState(ScheduledWorkFlow.State.INITIALIZED);
            scheduledWorkFlow.setCronExpression(unScheduledWorkflow.getCronExpression());
            scheduledWorkFlow.setManagerRefId(managerInfo.getId());
            scheduledWorkFlow.setCreateTime(System.currentTimeMillis());
            scheduledWorkFlow.setCreatedBy(managerInfo.getNodeAddress() + ":" + managerInfo.getId());

            if (!processRegistry.addProcess(scheduledWorkFlow) && !isJunitRun) {
                return;
            }
            SchedulingResult result = schedulingAssistant.scheduleSchedulerWithFailSafety(scheduledWorkFlow);
            if (Status.FAILURE == result.getStatus()) {
                logger.error("Unable to  schedule workflow name={}, version={} with some error",
                        scheduledWorkFlow.getWfName(), scheduledWorkFlow.getWfVersion(),
                        result.getException());
                scheduledWorkFlow.setState(ScheduledWorkFlow.State.SCHEDULING_FAILED);
                scheduledWorkFlow.setSchedulingException(result.getException());
            } else {
                logger.info("Process submitted for scheduling with the id={}", result.getId());
                scheduledWorkFlow.setScheduledProcess(result.getProcessReference());
                scheduledWorkFlow.setState(ScheduledWorkFlow.State.RUNNING);
                scheduledWorkFlow.setSchedulingException(null);
            }
            processRegistry.updateProcessById(scheduledWorkFlow.getScheduledProcess(),
                    scheduledWorkFlow.getState(), scheduledWorkFlow.getId(), scheduledWorkFlow.getName());
            indexDAO.indexCreatedScheduledWorkFlow(scheduledWorkFlow);
            results.add(result);
        });
        return results;
    }

    @VisibleForTesting
    List<ShutdownResult> manageShutDownProcesses() {
        Optional<List<ScheduleWfDef>> tobeShutDownScheduleWfDefsOptional =
                scheduledWfMetadataDAO.getAllScheduledWorkflowDefsByStatus(ScheduleWfDef.Status.SHUTDOWN);
        if (!tobeShutDownScheduleWfDefsOptional.isPresent()
                || CollectionUtils.isEmpty(tobeShutDownScheduleWfDefsOptional.get())) {
            logger.debug("No ScheduleWfDef marked for shutdown");
            return null;
        }

        List<String> names = tobeShutDownScheduleWfDefsOptional.get().stream().map(ScheduleWfDef::getWfName)
                .collect(Collectors.toList());

        List<ScheduledWorkFlow> tobeShutDownProcesses =
                processRegistry.getTobeShutDownProcesses(managerInfo.getId(), names);
        if (CollectionUtils.isEmpty(tobeShutDownProcesses)) {
            logger.debug("No running process found for shutdown with managerRef={}, with names={}",
                    managerInfo.getId(), names);
            return null;
        }

        List<ShutdownResult> shutdownResults = new ArrayList<>();
        tobeShutDownProcesses.forEach(shutdownProcess -> {
            ShutdownResult result = schedulingAssistant.shutdownSchedulerWithFailSafety(shutdownProcess);

            if (Status.FAILURE == result.getStatus()) {
                logger.error("Unable to  shutdown workflow name={}, version={} with some error",
                        shutdownProcess.getWfName(), shutdownProcess.getWfVersion(), result.getException());
                shutdownProcess.setState(ScheduledWorkFlow.State.SHUTDOWN_FAILED);
                processRegistry.updateProcessById(shutdownProcess.getScheduledProcess(),
                        shutdownProcess.getState(), shutdownProcess.getId(), shutdownProcess.getName());
            } else {
                logger.info("Process submit signalled for shutdown with the id={}", result.getId());
                processRegistry.removeProcess(shutdownProcess);
            }
            indexDAO.indexShutdownScheduledWorkFlow(shutdownProcess);
            shutdownResults.add(result);
        });

        return shutdownResults;
    }
}
