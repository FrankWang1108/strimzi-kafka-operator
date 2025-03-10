/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.model.KafkaConnector;
import io.strimzi.api.kafka.model.KafkaConnectorBuilder;
import io.strimzi.api.kafka.model.status.Condition;
import io.strimzi.api.kafka.model.status.KafkaConnectorStatus;
import io.strimzi.platform.KubernetesVersion;
import io.strimzi.operator.PlatformFeaturesAvailability;
import io.strimzi.operator.cluster.ClusterOperatorConfig;
import io.strimzi.operator.cluster.KafkaVersionTestUtils;
import io.strimzi.operator.cluster.operator.resource.DefaultZookeeperScalerProvider;
import io.strimzi.operator.cluster.operator.resource.ResourceOperatorSupplier;
import io.strimzi.operator.cluster.operator.resource.ZookeeperLeaderFinder;
import io.strimzi.operator.common.AbstractOperator;
import io.strimzi.operator.common.BackOff;
import io.strimzi.operator.common.DefaultAdminClientProvider;
import io.strimzi.operator.common.MetricsProvider;
import io.strimzi.operator.common.MicrometerMetricsProvider;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.test.container.StrimziKafkaCluster;
import io.strimzi.test.mockkube2.MockKube2;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.micrometer.VertxPrometheusOptions;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@EnableKubernetesMockClient(crud = true)
@ExtendWith(VertxExtension.class)
public class KafkaConnectorIT {
    private static StrimziKafkaCluster cluster;
    private static Vertx vertx;

    // Injected by Fabric8 Mock Kubernetes Server
    @SuppressWarnings("unused")
    private KubernetesClient client;
    private MockKube2 mockKube;
    private ConnectCluster connectCluster;

    @BeforeAll
    public static void before() throws IOException {
        final Map<String, String> kafkaClusterConfiguration = new HashMap<>();
        kafkaClusterConfiguration.put("zookeeper.connect", "zookeeper:2181");
        cluster = new StrimziKafkaCluster(3, 1, kafkaClusterConfiguration);
        cluster.start();
    }

    @AfterAll
    public static void after() {
        cluster.stop();
    }

    @BeforeEach
    public void beforeEach() throws InterruptedException {
        vertx = Vertx.vertx(new VertxOptions().setMetricsOptions(
                new MicrometerMetricsOptions()
                        .setPrometheusOptions(new VertxPrometheusOptions().setEnabled(true))
                        .setEnabled(true)
        ));
        // Configure the Kubernetes Mock
        mockKube = new MockKube2.MockKube2Builder(client)
                .withKafkaConnectorCrd()
                .build();
        mockKube.start();

        // Start a 3 node connect cluster
        connectCluster = new ConnectCluster()
                .usingBrokers(cluster.getBootstrapServers())
                .addConnectNodes(3);
        connectCluster.startup();
    }

    @AfterEach
    public void afterEach() {
        vertx.close();
        mockKube.stop();

        if (connectCluster != null) {
            connectCluster.shutdown();
        }
    }

    @Test
    public void testConnectorNotUpdatedWhenConfigUnchanged(VertxTestContext context) {
        KafkaConnectApiImpl connectClient = new KafkaConnectApiImpl(vertx);

        PlatformFeaturesAvailability pfa = new PlatformFeaturesAvailability(false, KubernetesVersion.V1_20);

        String namespace = "ns";
        String connectorName = "my-connector";

        LinkedHashMap<String, Object> config = new LinkedHashMap<>();
        config.put(TestingConnector.START_TIME_MS, 1_000);
        config.put(TestingConnector.STOP_TIME_MS, 0);
        config.put(TestingConnector.TASK_START_TIME_MS, 1_000);
        config.put(TestingConnector.TASK_STOP_TIME_MS, 0);
        config.put(TestingConnector.TASK_POLL_TIME_MS, 1_000);
        config.put(TestingConnector.TASK_POLL_RECORDS, 100);
        config.put(TestingConnector.NUM_PARTITIONS, 1);
        config.put(TestingConnector.TOPIC_NAME, "my-topic");

        KafkaConnector connector = createKafkaConnector(namespace, connectorName, config);
        Crds.kafkaConnectorOperation(client).inNamespace(namespace).resource(connector).create();

        MetricsProvider metrics = new MicrometerMetricsProvider();
        ResourceOperatorSupplier ros = new ResourceOperatorSupplier(vertx, client,
                new ZookeeperLeaderFinder(vertx,
                        // Retry up to 3 times (4 attempts), with overall max delay of 35000ms
                        () -> new BackOff(5_000, 2, 4)),
                new DefaultAdminClientProvider(),
                new DefaultZookeeperScalerProvider(),
                metrics,
                pfa, 10_000
        );

        KafkaConnectAssemblyOperator operator = new KafkaConnectAssemblyOperator(vertx, pfa, ros,
                ClusterOperatorConfig.fromMap(Collections.emptyMap(), KafkaVersionTestUtils.getKafkaVersionLookup()),
            connect -> new KafkaConnectApiImpl(vertx),
            connectCluster.getPort(2)
        ) { };

        Checkpoint async = context.checkpoint();
        operator.reconcileConnectorAndHandleResult(new Reconciliation("test", "KafkaConnect", namespace, "bogus"),
                "localhost", connectClient, true, connectorName,
                connector)
            .onComplete(context.succeeding(v -> assertConnectorIsRunning(context, client, namespace, connectorName)))
            .compose(v -> {
                config.remove(TestingConnector.START_TIME_MS, 1_000);
                config.put(TestingConnector.START_TIME_MS, 1_000);
                Crds.kafkaConnectorOperation(client)
                        .inNamespace(namespace)
                        .resource(createKafkaConnector(namespace, connectorName, config))
                        .patch();
                return operator.reconcileConnectorAndHandleResult(new Reconciliation("test", "KafkaConnect", namespace, "bogus"),
                        "localhost", connectClient, true, connectorName, connector);
            })
            .onComplete(context.succeeding(v -> context.verify(() -> {
                assertConnectorIsRunning(context, client, namespace, connectorName);
                // Assert metrics from Connector Operator
                MeterRegistry registry = metrics.meterRegistry();

                assertThat(registry.get(AbstractOperator.METRICS_PREFIX + "reconciliations").tag("kind", KafkaConnector.RESOURCE_KIND).counter().count(), CoreMatchers.is(2.0));
                assertThat(registry.get(AbstractOperator.METRICS_PREFIX + "reconciliations.successful").tag("kind", KafkaConnector.RESOURCE_KIND).counter().count(), CoreMatchers.is(2.0));

                assertThat(registry.get(AbstractOperator.METRICS_PREFIX + "reconciliations.duration").tag("kind", KafkaConnector.RESOURCE_KIND).timer().count(), CoreMatchers.is(2L));
                assertThat(registry.get(AbstractOperator.METRICS_PREFIX + "reconciliations.duration").tag("kind", KafkaConnector.RESOURCE_KIND).timer().totalTime(TimeUnit.MILLISECONDS), greaterThan(0.0));

                async.flag();
            })));
    }

    @Test
    public void testConnectorResourceNotReadyWhenConnectorFailed(VertxTestContext context) {
        KafkaConnectApiImpl connectClient = new KafkaConnectApiImpl(vertx);

        PlatformFeaturesAvailability pfa = new PlatformFeaturesAvailability(false, KubernetesVersion.V1_20);

        String namespace = "ns";
        String connectorName = "my-connector-2";

        LinkedHashMap<String, Object> config = new LinkedHashMap<>();
        config.put(TestingConnector.FAIL_ON_START, true);
        config.put(TestingConnector.START_TIME_MS, 0);
        config.put(TestingConnector.STOP_TIME_MS, 0);
        config.put(TestingConnector.TASK_START_TIME_MS, 0);
        config.put(TestingConnector.TASK_STOP_TIME_MS, 0);
        config.put(TestingConnector.TASK_POLL_TIME_MS, 0);
        config.put(TestingConnector.TASK_POLL_RECORDS, 100);
        config.put(TestingConnector.NUM_PARTITIONS, 1);
        config.put(TestingConnector.TOPIC_NAME, "my-topic");

        KafkaConnector connector = createKafkaConnector(namespace, connectorName, config);
        Crds.kafkaConnectorOperation(client).inNamespace(namespace).resource(connector).create();

        MetricsProvider metrics = new MicrometerMetricsProvider();
        ResourceOperatorSupplier ros = new ResourceOperatorSupplier(vertx, client,
                new ZookeeperLeaderFinder(vertx,
                        // Retry up to 3 times (4 attempts), with overall max delay of 35000ms
                        () -> new BackOff(5_000, 2, 4)),
                new DefaultAdminClientProvider(),
                new DefaultZookeeperScalerProvider(),
                metrics,
                pfa, 10_000
        );

        KafkaConnectAssemblyOperator operator = new KafkaConnectAssemblyOperator(vertx, pfa, ros,
                ClusterOperatorConfig.fromMap(Collections.emptyMap(), KafkaVersionTestUtils.getKafkaVersionLookup()),
                connect -> new KafkaConnectApiImpl(vertx),
                connectCluster.getPort(2)
        ) { };

        operator.reconcileConnectorAndHandleResult(new Reconciliation("test", "KafkaConnect", namespace, "bogus"),
                        "localhost", connectClient, true, connectorName,
                        connector)
                .onComplete(context.succeeding(v -> {
                    assertConnectorIsNotReady(context, client, namespace, connectorName);
                    context.completeNow();
                }));
    }

    @Test
    public void testConnectorResourceNotReadyWhenTaskFailed(VertxTestContext context) {
        KafkaConnectApiImpl connectClient = new KafkaConnectApiImpl(vertx);

        PlatformFeaturesAvailability pfa = new PlatformFeaturesAvailability(false, KubernetesVersion.V1_20);

        String namespace = "ns";
        String connectorName = "my-connector-3";

        LinkedHashMap<String, Object> config = new LinkedHashMap<>();
        config.put(TestingConnector.TASK_FAIL_ON_START, true);
        config.put(TestingConnector.START_TIME_MS, 0);
        config.put(TestingConnector.STOP_TIME_MS, 0);
        config.put(TestingConnector.TASK_START_TIME_MS, 0);
        config.put(TestingConnector.TASK_STOP_TIME_MS, 0);
        config.put(TestingConnector.TASK_POLL_TIME_MS, 0);
        config.put(TestingConnector.TASK_POLL_RECORDS, 100);
        config.put(TestingConnector.NUM_PARTITIONS, 1);
        config.put(TestingConnector.TOPIC_NAME, "my-topic");

        KafkaConnector connector = createKafkaConnector(namespace, connectorName, config);
        Crds.kafkaConnectorOperation(client).inNamespace(namespace).resource(connector).create();

        MetricsProvider metrics = new MicrometerMetricsProvider();
        ResourceOperatorSupplier ros = new ResourceOperatorSupplier(vertx, client,
                new ZookeeperLeaderFinder(vertx,
                        // Retry up to 3 times (4 attempts), with overall max delay of 35000ms
                        () -> new BackOff(5_000, 2, 4)),
                new DefaultAdminClientProvider(),
                new DefaultZookeeperScalerProvider(),
                metrics,
                pfa, 10_000
        );

        KafkaConnectAssemblyOperator operator = new KafkaConnectAssemblyOperator(vertx, pfa, ros,
                ClusterOperatorConfig.fromMap(Collections.emptyMap(), KafkaVersionTestUtils.getKafkaVersionLookup()),
                connect -> new KafkaConnectApiImpl(vertx),
                connectCluster.getPort(2)
        ) { };

        operator.reconcileConnectorAndHandleResult(new Reconciliation("test", "KafkaConnect", namespace, "bogus"),
                        "localhost", connectClient, true, connectorName,
                        connector)
                .compose(v -> {
                    // Sometimes task status doesn't appear on first reconcile if tasks haven't started yet
                    if (taskStatusIsPresent(client, namespace, connectorName)) {
                        return Future.succeededFuture();
                    } else {
                        Promise<Void> promise = Promise.promise();
                        vertx.setTimer(2000, id -> operator.reconcileConnectorAndHandleResult(new Reconciliation("test", "KafkaConnect", namespace, "bogus"),
                                        "localhost", connectClient, true, connectorName, connector)
                                .onComplete(result -> promise.complete(result.result())));
                        return promise.future();
                    }
                }).onComplete(context.succeeding(v -> {
                    assertConnectorTaskIsNotReady(context, client, namespace, connectorName);
                    context.completeNow();
                }));
    }

    private KafkaConnector createKafkaConnector(String namespace, String connectorName, LinkedHashMap<String, Object> config) {
        return new KafkaConnectorBuilder()
                    .withNewMetadata()
                        .withName(connectorName)
                        .withNamespace(namespace)
                    .endMetadata()
                    .withNewSpec()
                        .withClassName(TestingConnector.class.getName())
                        .withTasksMax(1)
                        .withConfig(config)
                    .endSpec()
                    .build();
    }

    @SuppressWarnings({ "rawtypes" })
    private void assertConnectorIsRunning(VertxTestContext context, KubernetesClient client, String namespace, String connectorName) {
        context.verify(() -> {
            KafkaConnector kafkaConnector = Crds.kafkaConnectorOperation(client).inNamespace(namespace).withName(connectorName).get();
            assertThat(kafkaConnector, notNullValue());
            assertThat(kafkaConnector.getStatus(), notNullValue());
            assertThat(kafkaConnector.getStatus().getTasksMax(), is(1));
            assertThat(kafkaConnector.getStatus().getConnectorStatus(), notNullValue());
            assertThat(kafkaConnector.getStatus().getConnectorStatus().get("connector"), instanceOf(Map.class));
            assertThat(((Map) kafkaConnector.getStatus().getConnectorStatus().get("connector")).get("state"), is("RUNNING"));
        });
    }

    private void assertConnectorIsNotReady(VertxTestContext context, KubernetesClient client, String namespace, String connectorName) {
        context.verify(() -> {
            KafkaConnector kafkaConnector = Crds.kafkaConnectorOperation(client).inNamespace(namespace).withName(connectorName).get();
            assertThat(kafkaConnector, notNullValue());
            assertThat(kafkaConnector.getStatus(), notNullValue());
            assertThat(kafkaConnector.getStatus().getConnectorStatus(), notNullValue());
            JsonObject connectorStatus = new JsonObject(kafkaConnector.getStatus().getConnectorStatus());
            assertThat(connectorStatus.getJsonObject("connector"), notNullValue());
            assertThat(connectorStatus.getJsonObject("connector").getString("state"), is("FAILED"));
            assertThat(kafkaConnector.getStatus().getConditions(), hasSize(1));
            Condition notReadyCondition = kafkaConnector.getStatus().getConditions().get(0);
            assertThat(notReadyCondition, hasProperty("type", equalTo("NotReady")));
            assertThat(notReadyCondition, hasProperty("status", equalTo("True")));
            assertThat(notReadyCondition, hasProperty("reason", equalTo("Throwable")));
            assertThat(notReadyCondition, hasProperty("message", equalTo("Connector has failed, see connectorStatus for more details.")));
        });
    }

    private boolean taskStatusIsPresent(KubernetesClient client, String namespace, String connectorName) {
        KafkaConnector kafkaConnector = Crds.kafkaConnectorOperation(client).inNamespace(namespace).withName(connectorName).get();
        Map<String, Object> connectorStatus = Optional.ofNullable(kafkaConnector)
                .map(KafkaConnector::getStatus)
                .map(KafkaConnectorStatus::getConnectorStatus)
                .orElseGet(HashMap::new);
        Object tasks = connectorStatus.get("tasks");
        return tasks instanceof ArrayList && ((ArrayList<?>) tasks).size() > 0;
    }

    private void assertConnectorTaskIsNotReady(VertxTestContext context, KubernetesClient client, String namespace, String connectorName) {
        context.verify(() -> {
            KafkaConnector kafkaConnector = Crds.kafkaConnectorOperation(client).inNamespace(namespace).withName(connectorName).get();
            assertThat(kafkaConnector, notNullValue());
            assertThat(kafkaConnector.getStatus(), notNullValue());
            assertThat(kafkaConnector.getStatus().getConnectorStatus(), notNullValue());
            JsonObject connectorStatus = new JsonObject(kafkaConnector.getStatus().getConnectorStatus());
            assertThat(connectorStatus.getJsonObject("connector"), notNullValue());
            assertThat(connectorStatus.getJsonObject("connector").getString("state"), is("RUNNING"));
            assertThat(connectorStatus.getJsonArray("tasks"), notNullValue());
            assertThat(connectorStatus.getJsonArray("tasks").size(), is(1));
            assertThat(connectorStatus.getJsonArray("tasks").getJsonObject(0).getString("state"), is("FAILED"));
            assertThat(kafkaConnector.getStatus().getConditions(), hasSize(1));
            Condition notReadyCondition = kafkaConnector.getStatus().getConditions().get(0);
            assertThat(notReadyCondition, hasProperty("type", equalTo("NotReady")));
            assertThat(notReadyCondition, hasProperty("status", equalTo("True")));
            assertThat(notReadyCondition, hasProperty("reason", equalTo("Throwable")));
            assertThat(notReadyCondition, hasProperty("message", equalTo("The following tasks have failed: 0, see connectorStatus for more details.")));
        });
    }
}
