package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.azure.AzureCli;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AksClusterProviderTest {

    private static final String KUBECONFIG = """
            apiVersion: v1
            clusters:
            - cluster:
                certificate-authority-data: dGVzdC1jYS1kYXRh
                server: https://floci-demo-abc123.hcp.eastus.azmk8s.io:443
              name: floci-demo
            """;

    @TempDir
    Path dataPath;

    private RecordingAzureCli az;
    private boolean keepRunningOnShutdown;
    private boolean autoCreateResourceGroup;
    private Optional<String> kubernetesVersion;
    private AksClusterProvider provider;

    @BeforeEach
    void setUp() {
        az = new RecordingAzureCli(testConfig());
        keepRunningOnShutdown = false;
        autoCreateResourceGroup = true;
        kubernetesVersion = Optional.empty();
        provider = new AksClusterProvider(az, testConfig());
    }

    @Test
    void startClusterCreatesResourceGroupThenAksCluster() {
        az.script(ok(""), ok(""));

        provider.startCluster(cluster("demo"));

        assertEquals(2, az.invocations.size());
        List<String> groupCreate = az.invocations.get(0);
        assertEquals(List.of("group", "create", "--name", "floci-rg", "--location", "westus2",
                "--tags", "floci=true", "--only-show-errors"), groupCreate);

        List<String> aksCreate = az.invocations.get(1);
        assertEquals(List.of("aks", "create",
                "--resource-group", "floci-rg",
                "--name", "floci-demo",
                "--node-count", "2",
                "--node-vm-size", "Standard_B2s",
                "--no-ssh-key",
                "--tags", "floci=true", "floci-eks-cluster=demo",
                "--no-wait",
                "--only-show-errors"), aksCreate);
    }

    @Test
    void startClusterSkipsResourceGroupWhenAutoCreateDisabled() {
        autoCreateResourceGroup = false;
        az.script(ok(""));

        provider.startCluster(cluster("demo"));

        assertEquals(1, az.invocations.size());
        assertEquals("aks", az.invocations.get(0).get(0));
    }

    @Test
    void startClusterEnsuresResourceGroupOnlyOnce() {
        az.script(ok(""), ok(""), ok(""));

        provider.startCluster(cluster("one"));
        provider.startCluster(cluster("two"));

        long groupCreates = az.invocations.stream().filter(args -> args.get(0).equals("group")).count();
        assertEquals(1, groupCreates);
    }

    @Test
    void startClusterForwardsConfiguredKubernetesVersion() {
        kubernetesVersion = Optional.of("1.30");
        az.script(ok(""), ok(""));

        provider.startCluster(cluster("demo"));

        List<String> aksCreate = az.invocations.get(1);
        int idx = aksCreate.indexOf("--kubernetes-version");
        assertTrue(idx > 0);
        assertEquals("1.30", aksCreate.get(idx + 1));
    }

    @Test
    void startClusterThrowsWhenAksCreateFails() {
        az.script(ok(""), fail("quota exceeded"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> provider.startCluster(cluster("demo")));
        assertTrue(e.getMessage().contains("quota exceeded"));
    }

    @Test
    void isReadyOnlyWhenProvisioningSucceeded() {
        az.script(ok("Creating\n"), ok("Succeeded\n"), fail("not found"));

        Cluster cluster = cluster("demo");
        assertFalse(provider.isReady(cluster));
        assertTrue(provider.isReady(cluster));
        assertFalse(provider.isReady(cluster));

        List<String> show = az.invocations.get(0);
        assertEquals(List.of("aks", "show", "--resource-group", "floci-rg", "--name", "floci-demo",
                "--query", "provisioningState", "--output", "tsv", "--only-show-errors"), show);
    }

    @Test
    void finalizeClusterExtractsEndpointAndCaAndWritesKubeconfig() throws Exception {
        az.script(ok(KUBECONFIG));

        Cluster cluster = cluster("demo");
        provider.finalizeCluster(cluster);

        assertEquals("https://floci-demo-abc123.hcp.eastus.azmk8s.io:443", cluster.getEndpoint());
        assertEquals("https://floci-demo-abc123.hcp.eastus.azmk8s.io:443", cluster.getInternalEndpoint());
        assertEquals("dGVzdC1jYS1kYXRh", cluster.getCertificateAuthority().getData());

        Path kubeconfig = dataPath.resolve("aks").resolve("demo").resolve("kubeconfig");
        assertTrue(Files.exists(kubeconfig));
        assertEquals(KUBECONFIG, Files.readString(kubeconfig));

        List<String> getCredentials = az.invocations.get(0);
        assertEquals(List.of("aks", "get-credentials", "--resource-group", "floci-rg",
                "--name", "floci-demo", "--admin", "--file", "-", "--only-show-errors"), getCredentials);
    }

    @Test
    void finalizeClusterLeavesClusterUntouchedWhenCredentialsFail() {
        az.script(fail("forbidden"));

        Cluster cluster = cluster("demo");
        provider.finalizeCluster(cluster);

        assertNull(cluster.getEndpoint());
    }

    @Test
    void stopClusterDeletesAksCluster() {
        az.script(ok(""));

        provider.stopCluster(cluster("demo"));

        assertEquals(List.of("aks", "delete", "--resource-group", "floci-rg", "--name", "floci-demo",
                "--yes", "--no-wait", "--only-show-errors"), az.invocations.get(0));
    }

    @Test
    void stopClusterHonorsKeepRunningOnShutdown() {
        keepRunningOnShutdown = true;

        provider.stopCluster(cluster("demo"));

        assertTrue(az.invocations.isEmpty());
    }

    @Test
    void aksNamePassesCleanNamesThroughWithPrefix() {
        assertEquals("floci-my-cluster_1", AksClusterProvider.aksName("my-cluster_1"));
    }

    @Test
    void aksNameHashesNamesNeedingMangling() {
        String longName = "a".repeat(100);
        String name = AksClusterProvider.aksName(longName);
        assertTrue(name.length() <= 63);
        assertTrue(name.startsWith("floci-aaa"));
        assertTrue(name.matches(".*-[0-9a-f]{8}$"));
        assertEquals(name, AksClusterProvider.aksName(longName));

        String trailing = AksClusterProvider.aksName("demo-");
        assertNotEquals(AksClusterProvider.aksName("demo"), trailing);
        assertTrue(trailing.matches("floci-demo-[0-9a-f]{8}"));
        assertTrue(AksClusterProvider.aksName("has.dots").matches("floci-has-dots-[0-9a-f]{8}"));
    }

    private static Cluster cluster(String name) {
        Cluster cluster = new Cluster();
        cluster.setName(name);
        return cluster;
    }

    private static AzureCli.Result ok(String stdout) {
        return new AzureCli.Result(0, stdout, "");
    }

    private static AzureCli.Result fail(String stderr) {
        return new AzureCli.Result(1, "", stderr);
    }

    /** Records az invocations and replays scripted results, no subprocess involved. */
    private static final class RecordingAzureCli extends AzureCli {
        final List<List<String>> invocations = new ArrayList<>();
        private final Deque<Result> results = new ArrayDeque<>();

        RecordingAzureCli(EmulatorConfig config) {
            super(config);
        }

        void script(Result... scripted) {
            results.clear();
            results.addAll(List.of(scripted));
        }

        @Override
        public Result run(List<String> args, Duration timeout) {
            invocations.add(List.copyOf(args));
            if (results.isEmpty()) {
                throw new AssertionError("Unexpected az invocation: " + args);
            }
            return results.poll();
        }
    }

    private EmulatorConfig testConfig() {
        EmulatorConfig.AksConfig aksConfig = proxy(EmulatorConfig.AksConfig.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "nodeCount" -> 2;
                    case "nodeVmSize" -> "Standard_B2s";
                    case "kubernetesVersion" -> kubernetesVersion;
                    default -> defaultValue(method);
                });
        EmulatorConfig.EksServiceConfig eksConfig = proxy(EmulatorConfig.EksServiceConfig.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "aks" -> aksConfig;
                    case "dataPath" -> dataPath.toString();
                    case "keepRunningOnShutdown" -> keepRunningOnShutdown;
                    default -> defaultValue(method);
                });
        EmulatorConfig.ServicesConfig servicesConfig = proxy(EmulatorConfig.ServicesConfig.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "eks" -> eksConfig;
                    default -> defaultValue(method);
                });
        EmulatorConfig.AzureConfig azureConfig = proxy(EmulatorConfig.AzureConfig.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "cliPath" -> "az";
                    case "subscription" -> Optional.<String>empty();
                    case "resourceGroup" -> "floci-rg";
                    case "location" -> "westus2";
                    case "autoCreateResourceGroup" -> autoCreateResourceGroup;
                    default -> defaultValue(method);
                });
        return proxy(EmulatorConfig.class, (proxy, method, args) -> switch (method.getName()) {
            case "services" -> servicesConfig;
            case "azure" -> azureConfig;
            default -> defaultValue(method);
        });
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> type.getSimpleName() + "TestProxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> method.invoke(this, args);
                };
            }
            return handler.invoke(proxy, method, args);
        });
    }

    private Object defaultValue(Method method) {
        Class<?> returnType = method.getReturnType();
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == Optional.class) {
            return Optional.empty();
        }
        if (returnType == String.class) {
            return "";
        }
        return null;
    }
}
