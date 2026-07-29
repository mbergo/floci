package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.services.eks.model.Cluster;

/**
 * Backing provider for real-mode EKS clusters. Floci keeps the AWS EKS wire protocol
 * regardless of the provider; only the infrastructure that backs a cluster changes.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link EksClusterManager} — local k3s Docker containers (default,
 *       {@code floci.services.eks.provider=k3s})</li>
 *   <li>{@link AksClusterProvider} — real Azure AKS clusters provisioned through the
 *       {@code az} CLI ({@code floci.services.eks.provider=aks})</li>
 * </ul>
 *
 * <p>Not used when {@code floci.services.eks.mock=true}.
 */
public interface EksClusterProvider {

    /**
     * Provisions the backing infrastructure for the given cluster. Must return quickly;
     * long-running provisioning happens asynchronously and is observed via {@link #isReady}.
     * The cluster status remains CREATING until {@link #isReady} returns true and
     * {@link #finalizeCluster} is called.
     */
    void startCluster(Cluster cluster);

    /** Whether the cluster's Kubernetes API server is provisioned and reachable. */
    boolean isReady(Cluster cluster);

    /**
     * Called once when {@link #isReady} first returns true. Populates endpoint and
     * certificate-authority data on the cluster before it transitions to ACTIVE.
     */
    void finalizeCluster(Cluster cluster);

    /** Tears down the backing infrastructure (honoring keep-running-on-shutdown). */
    void stopCluster(Cluster cluster);
}
