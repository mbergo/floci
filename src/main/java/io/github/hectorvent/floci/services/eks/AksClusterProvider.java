package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.azure.AzureCli;
import io.github.hectorvent.floci.services.eks.model.CertificateAuthority;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link EksClusterProvider} that backs EKS clusters with real Azure AKS clusters,
 * provisioned through the {@code az} CLI ({@code floci.services.eks.provider=aks}).
 *
 * <p>The AWS wire protocol is unchanged: {@code create-cluster} starts an async
 * {@code az aks create --no-wait}, the cluster stays CREATING while Floci polls
 * {@code provisioningState}, and once Succeeded {@code describe-cluster} returns the
 * real AKS endpoint and certificate-authority data extracted from
 * {@code az aks get-credentials}. The admin kubeconfig is also written to
 * {@code <data-path>/aks/<cluster>/kubeconfig} for direct {@code kubectl} use.
 *
 * <p>Compatibility note: AKS has no authentication-token-webhook hook point, so the
 * bearer tokens minted by {@code aws eks get-token} are <em>not</em> accepted by the
 * AKS API server — use the exported kubeconfig instead.
 */
@ApplicationScoped
public class AksClusterProvider implements EksClusterProvider {

    private static final Logger LOG = Logger.getLogger(AksClusterProvider.class);
    private static final int AKS_MAX_NAME_LENGTH = 63;
    private static final String NAME_PREFIX = "floci-";
    private static final Duration CREDENTIALS_TIMEOUT = Duration.ofMinutes(1);

    private final AzureCli az;
    private final EmulatorConfig config;
    private final AtomicBoolean resourceGroupEnsured = new AtomicBoolean(false);

    @Inject
    public AksClusterProvider(AzureCli az, EmulatorConfig config) {
        this.az = az;
        this.config = config;
    }

    @Override
    public void startCluster(Cluster cluster) {
        ensureResourceGroup();

        String aksName = aksName(cluster.getName());
        var aks = config.services().eks().aks();
        List<String> args = new ArrayList<>(List.of(
                "aks", "create",
                "--resource-group", config.azure().resourceGroup(),
                "--name", aksName,
                "--node-count", String.valueOf(aks.nodeCount()),
                "--node-vm-size", aks.nodeVmSize(),
                "--no-ssh-key",
                "--tags", "floci=true", "floci-eks-cluster=" + cluster.getName(),
                "--no-wait",
                "--only-show-errors"));
        aks.kubernetesVersion().ifPresent(v -> {
            args.add("--kubernetes-version");
            args.add(v);
        });

        LOG.infov("Provisioning AKS cluster {0} (EKS cluster {1}) in resource group {2}",
                aksName, cluster.getName(), config.azure().resourceGroup());
        AzureCli.Result result = az.run(args);
        if (!result.succeeded()) {
            throw new IllegalStateException("az aks create failed for cluster "
                    + cluster.getName() + ": " + result.stderr().trim());
        }
        // Endpoint and CA stay unset until finalizeCluster — matching AWS, where
        // describe-cluster carries no endpoint while the cluster is CREATING.
    }

    @Override
    public boolean isReady(Cluster cluster) {
        AzureCli.Result result = az.run(List.of(
                "aks", "show",
                "--resource-group", config.azure().resourceGroup(),
                "--name", aksName(cluster.getName()),
                "--query", "provisioningState",
                "--output", "tsv",
                "--only-show-errors"));
        if (!result.succeeded()) {
            return false;
        }
        String state = result.stdout().trim();
        if ("Failed".equalsIgnoreCase(state) || "Canceled".equalsIgnoreCase(state)) {
            LOG.warnv("AKS provisioning for cluster {0} ended in state {1}; the EKS cluster "
                    + "stays CREATING — inspect it with: az aks show -g {2} -n {3}",
                    cluster.getName(), state, config.azure().resourceGroup(),
                    aksName(cluster.getName()));
        }
        return "Succeeded".equalsIgnoreCase(state);
    }

    @Override
    public void finalizeCluster(Cluster cluster) {
        AzureCli.Result result = az.run(List.of(
                "aks", "get-credentials",
                "--resource-group", config.azure().resourceGroup(),
                "--name", aksName(cluster.getName()),
                "--admin",
                "--file", "-",
                "--only-show-errors"), CREDENTIALS_TIMEOUT);
        if (!result.succeeded()) {
            LOG.warnv("Could not fetch AKS credentials for cluster {0}: {1}",
                    cluster.getName(), result.stderr().trim());
            return;
        }

        String kubeconfig = result.stdout();
        String server = extractYamlField(kubeconfig, "server");
        String caData = extractYamlField(kubeconfig, "certificate-authority-data");
        if (server != null) {
            cluster.setEndpoint(server);
            cluster.setInternalEndpoint(server);
        }
        if (caData != null) {
            cluster.setCertificateAuthority(new CertificateAuthority(caData));
        }
        writeKubeconfig(cluster.getName(), kubeconfig);
        LOG.infov("Finalized EKS cluster {0} against AKS endpoint {1}", cluster.getName(), server);
    }

    @Override
    public void stopCluster(Cluster cluster) {
        if (config.services().eks().keepRunningOnShutdown()) {
            LOG.infov("Leaving AKS cluster for {0} running (keep-running-on-shutdown)", cluster.getName());
            return;
        }
        AzureCli.Result result = az.run(List.of(
                "aks", "delete",
                "--resource-group", config.azure().resourceGroup(),
                "--name", aksName(cluster.getName()),
                "--yes",
                "--no-wait",
                "--only-show-errors"));
        if (!result.succeeded()) {
            LOG.warnv("az aks delete failed for cluster {0}: {1}",
                    cluster.getName(), result.stderr().trim());
            return;
        }
        LOG.infov("Deleting AKS cluster for EKS cluster {0}", cluster.getName());
    }

    /**
     * Creates the shared resource group on first use when
     * {@code floci.azure.auto-create-resource-group} is enabled. {@code az group create}
     * is idempotent, so pre-existing groups are untouched.
     */
    private void ensureResourceGroup() {
        if (!config.azure().autoCreateResourceGroup() || !resourceGroupEnsured.compareAndSet(false, true)) {
            return;
        }
        AzureCli.Result result = az.run(List.of(
                "group", "create",
                "--name", config.azure().resourceGroup(),
                "--location", config.azure().location(),
                "--tags", "floci=true",
                "--only-show-errors"));
        if (!result.succeeded()) {
            resourceGroupEnsured.set(false);
            throw new IllegalStateException("az group create failed for resource group "
                    + config.azure().resourceGroup() + ": " + result.stderr().trim());
        }
    }

    /**
     * Maps an EKS cluster name (up to 100 chars) to a valid AKS managed-cluster name
     * (alphanumerics, hyphens and underscores, 63 chars max, must start and end with an
     * alphanumeric). Deterministic, so the name is recomputable after a restart without
     * storing provider state on the cluster. Names that need any mangling (invalid chars,
     * trailing separators, truncation) get an 8-char SHA-256 suffix of the original name,
     * keeping distinct EKS names collision-free.
     */
    static String aksName(String eksClusterName) {
        String cleaned = eksClusterName.replaceAll("[^a-zA-Z0-9_-]", "-");
        String candidate = NAME_PREFIX + cleaned;
        boolean mangled = !cleaned.equals(eksClusterName)
                || candidate.matches(".*[-_]$")
                || candidate.length() > AKS_MAX_NAME_LENGTH;
        if (!mangled) {
            return candidate;
        }
        String hash = sha256Hex(eksClusterName).substring(0, 8);
        int maxBase = AKS_MAX_NAME_LENGTH - hash.length() - 1;
        String base = candidate.length() > maxBase ? candidate.substring(0, maxBase) : candidate;
        return base.replaceAll("[-_]+$", "") + "-" + hash;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Best-effort local kubeconfig copy so users can kubectl into the AKS cluster directly. */
    private void writeKubeconfig(String clusterName, String kubeconfig) {
        Path localFile = Paths.get(config.services().eks().dataPath(), "aks", clusterName, "kubeconfig")
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(localFile.getParent());
            Files.writeString(localFile, kubeconfig);
            LOG.infov("Wrote AKS kubeconfig for cluster {0} to {1}", clusterName, localFile);
        } catch (IOException e) {
            LOG.warnv("Could not write AKS kubeconfig for cluster {0}: {1}", clusterName, e.getMessage());
        }
    }

    private static String extractYamlField(String yaml, String fieldName) {
        for (String line : yaml.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(fieldName + ":")) {
                return trimmed.substring(fieldName.length() + 1).trim();
            }
        }
        return null;
    }
}
