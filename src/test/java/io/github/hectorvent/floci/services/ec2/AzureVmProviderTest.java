package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.azure.AzureCli;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

class AzureVmProviderTest {

    private static final String VM_CREATE_JSON = """
            {
              "fqdns": "floci-i-0abc.eastus.cloudapp.azure.com",
              "publicIpAddress": "20.1.2.3",
              "privateIpAddress": "10.0.0.4",
              "powerState": "VM running"
            }
            """;

    private RecordingAzureCli az;
    private Optional<String> configuredSize;
    private AzureVmProvider provider;

    @BeforeEach
    void setUp() {
        configuredSize = Optional.empty();
        az = new RecordingAzureCli(testConfig());
        provider = new AzureVmProvider(az, testConfig());
    }

    @Test
    void launchCreatesResourceGroupThenVmAndAppliesAddresses() {
        az.script(ok(""), ok(VM_CREATE_JSON));

        Instance inst = instance("i-0abc");
        inst.setInstanceType("t3.micro");
        provider.launch(inst, ResolvedAmiImage.minimal("ignored"), "ssh-rsa AAAA key", "us-east-1", Set.of());

        assertEquals("pending", inst.getState().getName());
        awaitState(inst, "running");

        assertEquals(2, az.invocations.size());
        assertEquals("group", az.invocations.get(0).get(0));
        assertEquals(List.of("vm", "create",
                "--resource-group", "floci-rg",
                "--name", "floci-i-0abc",
                "--image", "Ubuntu2204",
                "--size", "Standard_B1s",
                "--admin-username", "ec2-user",
                "--public-ip-address-dns-name", "floci-i-0abc",
                "--os-disk-delete-option", "Delete",
                "--nic-delete-option", "Delete",
                "--tags", "floci=true", "floci-ec2-instance=i-0abc",
                "--ssh-key-values", "ssh-rsa AAAA key",
                "--only-show-errors"), az.invocations.get(1));

        assertEquals("floci-i-0abc", inst.getDockerContainerId());
        assertEquals("20.1.2.3", inst.getPublicIpAddress());
        assertEquals("floci-i-0abc.eastus.cloudapp.azure.com", inst.getPublicDnsName());
        assertEquals("10.0.0.4", inst.getPrivateIpAddress());
        assertEquals("ip-10-0-0-4.ec2.internal", inst.getPrivateDnsName());
    }

    @Test
    void launchGeneratesSshKeysAndPassesUserDataWhenNoKeyPair() {
        az.script(ok(""), ok(VM_CREATE_JSON));

        Instance inst = instance("i-0abc");
        inst.setUserData("#!/bin/sh\necho hi\n");
        provider.launch(inst, ResolvedAmiImage.minimal("ignored"), null, "us-east-1", Set.of());
        awaitState(inst, "running");

        List<String> create = az.invocations.get(1);
        assertTrue(create.contains("--generate-ssh-keys"));
        int idx = create.indexOf("--custom-data");
        assertEquals("#!/bin/sh\necho hi\n", create.get(idx + 1));
    }

    @Test
    void launchOpensSecurityGroupPortsInSingleNsgRule() {
        az.script(ok(""), ok(VM_CREATE_JSON), ok(""));

        Instance inst = instance("i-0abc");
        provider.launch(inst, ResolvedAmiImage.minimal("ignored"), null, "us-east-1", Set.of(443, 80));
        awaitState(inst, "running");

        List<String> rule = az.invocations.get(2);
        assertEquals(List.of("network", "nsg", "rule", "create",
                "--resource-group", "floci-rg",
                "--nsg-name", "floci-i-0abcNSG",
                "--name", "floci-app-ports",
                "--priority", "1010",
                "--access", "Allow",
                "--protocol", "Tcp",
                "--direction", "Inbound",
                "--destination-port-ranges", "80", "443",
                "--only-show-errors"), rule);
    }

    @Test
    void launchTerminatesInstanceWhenVmCreateFails() {
        az.script(ok(""), fail("quota exceeded"));

        Instance inst = instance("i-0abc");
        provider.launch(inst, ResolvedAmiImage.minimal("ignored"), null, "us-east-1", Set.of());
        awaitState(inst, "terminated");

        assertNull(inst.getDockerContainerId());
    }

    @Test
    void stopDeallocatesVm() {
        az.script(ok(""));

        Instance inst = launched("i-0abc");
        provider.stop(inst);

        assertEquals("stopping", inst.getState().getName());
        awaitState(inst, "stopped");
        assertEquals(List.of("vm", "deallocate", "--resource-group", "floci-rg",
                "--name", "floci-i-0abc", "--only-show-errors"), az.invocations.get(0));
    }

    @Test
    void startStartsVmAndRefreshesAddresses() {
        az.script(ok(""), ok("""
                {"pub": "20.9.9.9", "priv": "10.0.0.7", "fqdn": "floci-i-0abc.eastus.cloudapp.azure.com"}
                """));

        Instance inst = launched("i-0abc");
        provider.start(inst);

        awaitState(inst, "running");
        assertEquals(List.of("vm", "start", "--resource-group", "floci-rg",
                "--name", "floci-i-0abc", "--only-show-errors"), az.invocations.get(0));
        assertEquals("20.9.9.9", inst.getPublicIpAddress());
        assertEquals("10.0.0.7", inst.getPrivateIpAddress());
    }

    @Test
    void terminateDeletesVmAndCleansUpNetworkResources() {
        az.script(ok(""), ok(""), ok(""));

        Instance inst = launched("i-0abc");
        provider.terminate(inst);

        assertEquals("shutting-down", inst.getState().getName());
        awaitState(inst, "terminated");
        assertTrue(inst.getTerminatedAt() > 0);

        assertEquals(List.of("vm", "delete", "--resource-group", "floci-rg",
                "--name", "floci-i-0abc", "--yes", "--only-show-errors"), az.invocations.get(0));
        assertEquals(List.of("network", "nsg", "delete", "--resource-group", "floci-rg",
                "--name", "floci-i-0abcNSG", "--only-show-errors"), az.invocations.get(1));
        assertEquals(List.of("network", "public-ip", "delete", "--resource-group", "floci-rg",
                "--name", "floci-i-0abcPublicIP", "--only-show-errors"), az.invocations.get(2));
    }

    @Test
    void stopForShutdownDeallocatesWithoutWaitingAndLeavesState() {
        az.script(ok(""));

        Instance inst = launched("i-0abc");
        provider.stopForShutdown(inst);

        assertEquals(List.of("vm", "deallocate", "--resource-group", "floci-rg",
                "--name", "floci-i-0abc", "--no-wait", "--only-show-errors"), az.invocations.get(0));
        assertEquals("running", inst.getState().getName());
    }

    @Test
    void isContainerRunningChecksPowerState() {
        az.script(ok("VM running\n"), ok("VM deallocated\n"));

        Instance inst = launched("i-0abc");
        assertTrue(provider.isContainerRunning(inst));
        assertFalse(provider.isContainerRunning(inst));
        assertFalse(provider.isContainerRunning(instance("i-0new")));
    }

    @Test
    void reconcilePublishedPortsWithEmptySetDeletesRule() {
        az.script(ok(""));

        Instance inst = launched("i-0abc");
        provider.reconcilePublishedPorts(inst, Set.of());

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> !az.invocations.isEmpty());
        assertEquals(List.of("network", "nsg", "rule", "delete", "--resource-group", "floci-rg",
                "--nsg-name", "floci-i-0abcNSG", "--name", "floci-app-ports", "--only-show-errors"),
                az.invocations.get(0));
    }

    @Test
    void restoreMetadataRegistrationIsNoOpForRemoteVms() {
        assertFalse(provider.restoreMetadataRegistration(launched("i-0abc")));
        assertTrue(az.invocations.isEmpty());
    }

    @Test
    void sizeForPrefersConfiguredSizeThenMappingThenDefault() {
        assertEquals("Standard_D8s_v5", AzureVmProvider.sizeFor("t3.micro", "Standard_D8s_v5"));
        assertEquals("Standard_B1s", AzureVmProvider.sizeFor("t3.micro", null));
        assertEquals("Standard_B2ms", AzureVmProvider.sizeFor("T2.LARGE", null));
        assertEquals("Standard_B2s", AzureVmProvider.sizeFor("u-6tb1.metal", null));
        assertEquals("Standard_B2s", AzureVmProvider.sizeFor(null, null));
    }

    @Test
    void vmNameIsDeterministicPrefixedLowercase() {
        assertEquals("floci-i-0abcdef", AzureVmProvider.vmName("i-0ABCdef"));
    }

    private static Instance instance(String id) {
        Instance inst = new Instance();
        inst.setInstanceId(id);
        return inst;
    }

    private static Instance launched(String id) {
        Instance inst = instance(id);
        inst.setDockerContainerId(AzureVmProvider.vmName(id));
        inst.setState(InstanceState.running());
        return inst;
    }

    private static void awaitState(Instance inst, String state) {
        Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> inst.getState() != null && state.equals(inst.getState().getName()));
    }

    private static AzureCli.Result ok(String stdout) {
        return new AzureCli.Result(0, stdout, "");
    }

    private static AzureCli.Result fail(String stderr) {
        return new AzureCli.Result(1, "", stderr);
    }

    /** Records az invocations and replays scripted results, no subprocess involved. */
    private static final class RecordingAzureCli extends AzureCli {
        final List<List<String>> invocations = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();

        RecordingAzureCli(EmulatorConfig config) {
            super(config);
        }

        void script(Result... scripted) {
            results.clear();
            results.addAll(List.of(scripted));
        }

        @Override
        public synchronized Result run(List<String> args, Duration timeout) {
            invocations.add(List.copyOf(args));
            Result next = results.poll();
            if (next == null) {
                throw new AssertionError("Unexpected az invocation: " + args);
            }
            return next;
        }
    }

    private EmulatorConfig testConfig() {
        EmulatorConfig.AzureVmConfig vmConfig = proxy(EmulatorConfig.AzureVmConfig.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "image" -> "Ubuntu2204";
                    case "size" -> configuredSize;
                    case "adminUsername" -> "ec2-user";
                    default -> defaultValue(method);
                });
        EmulatorConfig.Ec2ServiceConfig ec2Config = proxy(EmulatorConfig.Ec2ServiceConfig.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "azureVm" -> vmConfig;
                    default -> defaultValue(method);
                });
        EmulatorConfig.ServicesConfig servicesConfig = proxy(EmulatorConfig.ServicesConfig.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "ec2" -> ec2Config;
                    default -> defaultValue(method);
                });
        EmulatorConfig.AzureConfig azureConfig = proxy(EmulatorConfig.AzureConfig.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "cliPath" -> "az";
                    case "subscription" -> Optional.<String>empty();
                    case "resourceGroup" -> "floci-rg";
                    case "location" -> "westus2";
                    case "autoCreateResourceGroup" -> true;
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
