package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.azure.AzureCli;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * {@link Ec2InstanceProvider} that backs EC2 instances with real Azure virtual machines,
 * provisioned through the {@code az} CLI ({@code floci.services.ec2.provider=azure-vm}).
 *
 * <p>The AWS wire protocol is unchanged: {@code run-instances} provisions an Azure VM
 * asynchronously (the instance stays {@code pending} until the VM is up), and
 * {@code describe-instances} then returns the VM's real public/private addresses.
 * Instance lifecycle maps to EC2 semantics: stop = {@code az vm deallocate} (no compute
 * billing while stopped), start = {@code az vm start} (public IP may change, as on EC2),
 * terminate = {@code az vm delete} plus cleanup of the VM's public IP and NSG.
 *
 * <p>UserData is passed to the VM as cloud-init {@code --custom-data}, matching how EC2
 * hands user data to Linux AMIs. Security-group ingress ports are opened on the VM's
 * network security group. When a key pair is attached, its public key is injected for the
 * configured admin user, so {@code ssh <admin-user>@<public-ip>} works directly on port 22.
 *
 * <p>Compatibility notes: AMI ids are not translated — every instance boots the configured
 * {@code floci.services.ec2.azure-vm.image}; and the VM runs Azure's IMDS, not the local
 * AWS IMDS, so instance metadata lookups inside the guest are not AWS-shaped.
 */
@ApplicationScoped
public class AzureVmProvider implements Ec2InstanceProvider {

    private static final Logger LOG = Logger.getLogger(AzureVmProvider.class);
    private static final String NAME_PREFIX = "floci-";
    private static final String APP_PORTS_RULE = "floci-app-ports";
    private static final Duration CREATE_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration LIFECYCLE_TIMEOUT = Duration.ofMinutes(10);

    /**
     * Built-in mapping of common EC2 instance types to comparable Azure VM sizes, used
     * when {@code floci.services.ec2.azure-vm.size} is not set. Unmapped types fall back
     * to {@code Standard_B2s}.
     */
    static final Map<String, String> INSTANCE_TYPE_SIZES = Map.ofEntries(
            Map.entry("t2.nano", "Standard_B1ls"),
            Map.entry("t3.nano", "Standard_B1ls"),
            Map.entry("t2.micro", "Standard_B1s"),
            Map.entry("t3.micro", "Standard_B1s"),
            Map.entry("t2.small", "Standard_B1ms"),
            Map.entry("t3.small", "Standard_B1ms"),
            Map.entry("t2.medium", "Standard_B2s"),
            Map.entry("t3.medium", "Standard_B2s"),
            Map.entry("t2.large", "Standard_B2ms"),
            Map.entry("t3.large", "Standard_B2ms"),
            Map.entry("m5.large", "Standard_D2s_v5"),
            Map.entry("m6i.large", "Standard_D2s_v5"),
            Map.entry("m5.xlarge", "Standard_D4s_v5"),
            Map.entry("m6i.xlarge", "Standard_D4s_v5"),
            Map.entry("c5.large", "Standard_F2s_v2"),
            Map.entry("c6i.large", "Standard_F2s_v2"),
            Map.entry("c5.xlarge", "Standard_F4s_v2"),
            Map.entry("r5.large", "Standard_E2s_v5"),
            Map.entry("r5.xlarge", "Standard_E4s_v5"));

    private static final String DEFAULT_SIZE = "Standard_B2s";

    private final AzureCli az;
    private final EmulatorConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "azure-vm-provider");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public AzureVmProvider(AzureCli az, EmulatorConfig config) {
        this.az = az;
        this.config = config;
    }

    @Override
    public void launch(Instance instance, ResolvedAmiImage image, String publicKey, String region, Set<Integer> appPorts) {
        instance.setState(InstanceState.pending());

        executor.submit(() -> {
            try {
                az.ensureResourceGroup();

                String instanceId = instance.getInstanceId();
                String vmName = vmName(instanceId);
                var vm = config.services().ec2().azureVm();

                List<String> args = new ArrayList<>(List.of(
                        "vm", "create",
                        "--resource-group", config.azure().resourceGroup(),
                        "--name", vmName,
                        "--image", vm.image(),
                        "--size", sizeFor(instance.getInstanceType(), vm.size().orElse(null)),
                        "--admin-username", vm.adminUsername(),
                        "--public-ip-address-dns-name", vmName,
                        "--os-disk-delete-option", "Delete",
                        "--nic-delete-option", "Delete",
                        "--tags", "floci=true", "floci-ec2-instance=" + instanceId));
                if (publicKey != null && !publicKey.isBlank()) {
                    args.add("--ssh-key-values");
                    args.add(publicKey.trim());
                }
                else {
                    args.add("--generate-ssh-keys");
                }
                String userData = instance.getUserData();
                if (userData != null && !userData.isBlank()) {
                    args.add("--custom-data");
                    args.add(userData);
                }
                args.add("--only-show-errors");

                LOG.infov("Provisioning Azure VM {0} for EC2 instance {1} in resource group {2}",
                        vmName, instanceId, config.azure().resourceGroup());
                AzureCli.Result result = az.run(args, CREATE_TIMEOUT);
                if (!result.succeeded()) {
                    LOG.warnv("az vm create failed for EC2 instance {0}: {1}",
                            instanceId, result.stderr().trim());
                    instance.setState(InstanceState.terminated());
                    return;
                }

                // Marks the instance as having backing compute; shutdown/restore paths key on it.
                instance.setDockerContainerId(vmName);
                applyAddresses(instance,
                        objectMapper.readTree(result.stdout()), "publicIpAddress", "privateIpAddress");

                if (appPorts != null && !appPorts.isEmpty()) {
                    reconcileNsgRule(vmName, appPorts);
                }

                instance.setState(InstanceState.running());
                LOG.infov("EC2 instance {0} running as Azure VM {1} (public IP {2})",
                        instanceId, vmName, String.valueOf(instance.getPublicIpAddress()));
            } catch (Exception e) {
                LOG.warnv("Failed to launch EC2 instance {0} as Azure VM: {1}",
                        instance.getInstanceId(), e.getMessage());
                instance.setState(InstanceState.terminated());
            }
        });
    }

    /**
     * Deallocates the VM without blocking emulator shutdown ({@code --no-wait}); the caller
     * flips the instance to {@code stopped} so StartInstances can revive it after a restart.
     */
    @Override
    public void stopForShutdown(Instance instance) {
        if (instance.getDockerContainerId() == null) {
            return;
        }
        AzureCli.Result result = az.run(vmCommand("deallocate", instance, "--no-wait"));
        if (!result.succeeded()) {
            LOG.warnv("Error deallocating Azure VM for EC2 instance {0} on shutdown: {1}",
                    instance.getInstanceId(), result.stderr().trim());
        }
    }

    @Override
    public void stop(Instance instance) {
        if (instance.getDockerContainerId() == null) {
            instance.setState(InstanceState.stopped());
            return;
        }
        instance.setState(InstanceState.stopping());
        executor.submit(() -> {
            AzureCli.Result result = az.run(vmCommand("deallocate", instance), LIFECYCLE_TIMEOUT);
            if (!result.succeeded()) {
                LOG.warnv("Error deallocating Azure VM for EC2 instance {0}: {1}",
                        instance.getInstanceId(), result.stderr().trim());
            }
            instance.setState(InstanceState.stopped());
        });
    }

    @Override
    public void start(Instance instance) {
        if (instance.getDockerContainerId() == null) {
            instance.setState(InstanceState.running());
            return;
        }
        instance.setState(InstanceState.pending());
        executor.submit(() -> {
            AzureCli.Result result = az.run(vmCommand("start", instance), LIFECYCLE_TIMEOUT);
            if (!result.succeeded()) {
                LOG.warnv("Error starting Azure VM for EC2 instance {0}: {1}",
                        instance.getInstanceId(), result.stderr().trim());
            }
            refreshAddresses(instance);
            instance.setState(InstanceState.running());
        });
    }

    @Override
    public void terminate(Instance instance) {
        String vmName = instance.getDockerContainerId();
        instance.setState(InstanceState.shuttingDown());
        executor.submit(() -> {
            if (vmName != null) {
                // Synchronous delete: the NIC (delete-option Delete) must release the public IP
                // and NSG before they can be removed, so ordering matters.
                AzureCli.Result result = az.run(vmCommand("delete", instance, "--yes"), LIFECYCLE_TIMEOUT);
                if (!result.succeeded()) {
                    LOG.warnv("Error deleting Azure VM for EC2 instance {0}: {1}",
                            instance.getInstanceId(), result.stderr().trim());
                }
                // az vm create leaves the NSG and public IP as separate resources; best-effort cleanup.
                az.run(List.of("network", "nsg", "delete",
                        "--resource-group", config.azure().resourceGroup(),
                        "--name", vmName + "NSG", "--only-show-errors"));
                az.run(List.of("network", "public-ip", "delete",
                        "--resource-group", config.azure().resourceGroup(),
                        "--name", vmName + "PublicIP", "--only-show-errors"));
            }
            instance.setState(InstanceState.terminated());
            instance.setTerminatedAt(System.currentTimeMillis());
        });
    }

    @Override
    public void reboot(Instance instance) {
        if (instance.getDockerContainerId() == null) {
            return;
        }
        executor.submit(() -> {
            AzureCli.Result result = az.run(vmCommand("restart", instance, "--no-wait"));
            if (result.succeeded()) {
                LOG.infov("Rebooted Azure VM {0}", instance.getDockerContainerId());
            }
            else {
                LOG.warnv("Error rebooting Azure VM for EC2 instance {0}: {1}",
                        instance.getInstanceId(), result.stderr().trim());
            }
        });
    }

    @Override
    public boolean isContainerRunning(Instance instance) {
        if (instance.getDockerContainerId() == null) {
            return false;
        }
        AzureCli.Result result = az.run(vmCommand("show", instance,
                "--show-details", "--query", "powerState", "--output", "tsv"));
        return result.succeeded() && "VM running".equalsIgnoreCase(result.stdout().trim());
    }

    /**
     * Azure VMs live outside the emulator host, so there is no local IMDS registration or
     * host-port reservation to restore after a restart — the persisted addresses stay valid.
     */
    @Override
    public boolean restoreMetadataRegistration(Instance instance) {
        return false;
    }

    @Override
    public void reconcilePublishedPorts(Instance instance, Set<Integer> desiredPorts) {
        String vmName = instance.getDockerContainerId();
        if (vmName == null) {
            return;
        }
        executor.submit(() -> reconcileNsgRule(vmName, desiredPorts));
    }

    /**
     * Maintains a single idempotent NSG rule ({@value APP_PORTS_RULE}) on the VM's network
     * security group carrying all security-group app ports; an empty set removes the rule.
     */
    private void reconcileNsgRule(String vmName, Set<Integer> ports) {
        String nsgName = vmName + "NSG";
        if (ports == null || ports.isEmpty()) {
            az.run(List.of("network", "nsg", "rule", "delete",
                    "--resource-group", config.azure().resourceGroup(),
                    "--nsg-name", nsgName,
                    "--name", APP_PORTS_RULE, "--only-show-errors"));
            return;
        }
        List<String> args = new ArrayList<>(List.of(
                "network", "nsg", "rule", "create",
                "--resource-group", config.azure().resourceGroup(),
                "--nsg-name", nsgName,
                "--name", APP_PORTS_RULE,
                "--priority", "1010",
                "--access", "Allow",
                "--protocol", "Tcp",
                "--direction", "Inbound",
                "--destination-port-ranges"));
        args.addAll(new TreeSet<>(ports).stream().map(String::valueOf).collect(Collectors.toList()));
        args.add("--only-show-errors");
        AzureCli.Result result = az.run(args);
        if (!result.succeeded()) {
            LOG.warnv("Error opening security-group ports {0} on Azure NSG {1}: {2}",
                    ports, nsgName, result.stderr().trim());
        }
    }

    /** Re-reads the VM's addresses after a start; Azure may hand out a new public IP, as EC2 does. */
    private void refreshAddresses(Instance instance) {
        AzureCli.Result result = az.run(vmCommand("show", instance,
                "--show-details", "--query", "{pub:publicIps, priv:privateIps, fqdn:fqdns}",
                "--output", "json"));
        if (!result.succeeded()) {
            LOG.warnv("Could not refresh addresses for EC2 instance {0}: {1}",
                    instance.getInstanceId(), result.stderr().trim());
            return;
        }
        try {
            applyAddresses(instance, objectMapper.readTree(result.stdout()), "pub", "priv");
        } catch (Exception e) {
            LOG.warnv("Could not parse Azure VM details for EC2 instance {0}: {1}",
                    instance.getInstanceId(), e.getMessage());
        }
    }

    /**
     * Applies public/private addresses from an az JSON payload onto the instance model. The
     * public DNS name prefers the resolvable Azure FQDN ({@code <vm>.<location>.cloudapp.azure.com})
     * created via {@code --public-ip-address-dns-name}.
     */
    private void applyAddresses(Instance instance, JsonNode node, String publicField, String privateField) {
        String publicIp = firstValue(node.path(publicField).asText(null));
        String privateIp = firstValue(node.path(privateField).asText(null));
        String fqdn = firstValue(node.path("fqdns").asText(node.path("fqdn").asText(null)));

        if (publicIp != null) {
            instance.setPublicIpAddress(publicIp);
            instance.setPublicDnsName(fqdn != null ? fqdn : publicIp);
        }
        if (privateIp != null) {
            Ec2ContainerManager.exposeReachablePrivateAddress(instance, privateIp);
        }
    }

    /** az -d fields like publicIps/fqdns are comma-joined lists; the first entry is the primary. */
    private static String firstValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.split(",")[0].trim();
    }

    private List<String> vmCommand(String action, Instance instance, String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "vm", action,
                "--resource-group", config.azure().resourceGroup(),
                "--name", instance.getDockerContainerId()));
        args.addAll(List.of(extra));
        args.add("--only-show-errors");
        return args;
    }

    /**
     * Deterministic Azure VM name (and DNS label) for an instance id. Instance ids are
     * emulator-generated ({@code i-} plus hex), so the mapping is a plain prefix and stays
     * recomputable after a restart without extra state.
     */
    static String vmName(String instanceId) {
        return NAME_PREFIX + instanceId.toLowerCase();
    }

    /**
     * Resolves the Azure VM size: an explicit {@code floci.services.ec2.azure-vm.size}
     * always wins; otherwise common EC2 instance types map to comparable sizes, with
     * {@value DEFAULT_SIZE} as the fallback.
     */
    static String sizeFor(String instanceType, String configuredSize) {
        if (configuredSize != null && !configuredSize.isBlank()) {
            return configuredSize;
        }
        if (instanceType != null) {
            String mapped = INSTANCE_TYPE_SIZES.get(instanceType.toLowerCase());
            if (mapped != null) {
                return mapped;
            }
        }
        return DEFAULT_SIZE;
    }
}
