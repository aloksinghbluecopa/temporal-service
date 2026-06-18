package com.bluecopa.temporalservice.config

import com.bluecopa.temporalservice.activity.RoutingDynamicActivity
import com.bluecopa.temporalservice.workflow.DslWorkflowImpl
import com.bluecopa.temporalservice.workflow.TriggerCoordinatorWorkflowImpl
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.schedules.ScheduleClient
import io.temporal.client.schedules.ScheduleClientOptions
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.worker.WorkerFactory
import io.temporal.worker.WorkflowImplementationOptions
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "temporal")
data class TemporalProperties(
    val target: String,
    val namespace: String,
    val taskQueue: String,
    val triggerCoordinatorQueue: String = "trigger-coordinator-queue"
)

@Configuration
@EnableConfigurationProperties(
    TemporalProperties::class,
    DslEngineProperties::class,
    ArgoCompatProperties::class,
    RegistryProperties::class,
    DslCallbackProperties::class,
    KubernetesProperties::class,
    SecurityProperties::class,
    VisibilityProperties::class
)
class TemporalConfig {
    @Bean
    fun workflowServiceStubs(properties: TemporalProperties): WorkflowServiceStubs =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget(properties.target)
                .build()
        )

    @Bean
    fun dataConverter(): DefaultDataConverter {
        val objectMapper = JacksonJsonPayloadConverter.newDefaultObjectMapper()
        objectMapper.registerKotlinModule()
        return DefaultDataConverter(JacksonJsonPayloadConverter(objectMapper))
    }

    @Bean
    fun workflowClient(serviceStubs: WorkflowServiceStubs, properties: TemporalProperties, dataConverter: DefaultDataConverter): WorkflowClient =
        WorkflowClient.newInstance(
            serviceStubs,
            WorkflowClientOptions.newBuilder()
                .setNamespace(properties.namespace)
                .setDataConverter(dataConverter)
                .build()
        )

    @Bean
    fun scheduleClient(serviceStubs: WorkflowServiceStubs, properties: TemporalProperties, dataConverter: DefaultDataConverter): ScheduleClient =
        ScheduleClient.newInstance(
            serviceStubs,
            ScheduleClientOptions.newBuilder()
                .setNamespace(properties.namespace)
                .setDataConverter(dataConverter)
                .build()
        )

    @Bean
    fun workerFactory(client: WorkflowClient, properties: TemporalProperties, activity: RoutingDynamicActivity): WorkerFactory {
        val factory = WorkerFactory.newInstance(client)
        val worker = factory.newWorker(properties.taskQueue)
        // Fail the workflow (not just the workflow task) on any interpreter exception. DSL
        // interpretation is deterministic, so retrying the task can never succeed — failing fast
        // surfaces a clean WorkflowFailedException to the caller instead of wedging the task queue.
        val workflowOptions = WorkflowImplementationOptions.newBuilder()
            .setFailWorkflowExceptionTypes(Throwable::class.java)
            .build()
        worker.registerWorkflowImplementationTypes(workflowOptions, DslWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(activity)
        // Long-lived signal coordinator runs on its own queue with DEFAULT workflow options (NOT
        // failWorkflowExceptionTypes) so a transient trigger.launch failure retries instead of
        // killing the coordinator.
        val coordinatorWorker = factory.newWorker(properties.triggerCoordinatorQueue)
        coordinatorWorker.registerWorkflowImplementationTypes(TriggerCoordinatorWorkflowImpl::class.java)
        coordinatorWorker.registerActivitiesImplementations(activity)
        return factory
    }

    @Bean
    fun workerLifecycle(factory: WorkerFactory): SmartLifecycle = WorkerFactoryLifecycle(factory)
}

private class WorkerFactoryLifecycle(
    private val factory: WorkerFactory
) : SmartLifecycle {
    @Volatile
    private var running = false

    override fun start() {
        factory.start()
        running = true
    }

    override fun stop() {
        factory.shutdown()
        factory.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)
        running = false
    }

    override fun isRunning(): Boolean = running
}
