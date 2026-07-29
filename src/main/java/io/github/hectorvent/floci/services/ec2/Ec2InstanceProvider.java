package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.Instance;

import java.util.Set;

/**
 * Backing-compute provider for real-mode EC2 instances ({@code floci.services.ec2.mock=false}).
 *
 * <p>The default implementation is {@link Ec2ContainerManager}, which runs each instance as a
 * local Docker container. Alternative providers (selected via
 * {@code floci.services.ec2.provider}) can back instances with other infrastructure — e.g.
 * {@link AzureVmProvider} provisions real Azure virtual machines through the {@code az} CLI —
 * while {@link Ec2Service} keeps serving the unchanged AWS wire protocol.
 *
 * <p>Contract notes:
 * <ul>
 *   <li>All lifecycle methods mutate the passed {@link Instance} in place (state transitions,
 *       addresses, backing-compute id) and return quickly; slow provisioning work runs
 *       asynchronously on a provider-owned executor.</li>
 *   <li>{@code launch} must move the instance through {@code pending} and eventually to
 *       {@code running}, or to {@code terminated} on failure.</li>
 *   <li>{@code stopForShutdown} is the synchronous emulator-shutdown path: it must not
 *       schedule work on an async executor and must not change instance state — the caller
 *       handles the state flip so it is captured by the final persistence flush.</li>
 * </ul>
 */
public interface Ec2InstanceProvider {

    /**
     * Provisions backing compute for a freshly created instance.
     *
     * @param instance  the EC2 instance model (mutated in-place as state transitions occur)
     * @param image     resolved AMI image (Docker image URI for container providers;
     *                  cloud providers map AMIs through their own configuration)
     * @param publicKey SSH public key content to inject (may be null)
     * @param region    AWS region of the instance
     * @param appPorts  TCP ports opened by the instance's security groups to expose
     *                  (empty for none)
     */
    void launch(Instance instance, ResolvedAmiImage image, String publicKey, String region, Set<Integer> appPorts);

    /** Synchronous stop for emulator shutdown; leaves instance state to the caller. */
    void stopForShutdown(Instance instance);

    /** Stops the backing compute, transitioning the instance through stopping → stopped. */
    void stop(Instance instance);

    /** Starts previously stopped backing compute, transitioning through pending → running. */
    void start(Instance instance);

    /**
     * Destroys the backing compute, transitioning through shutting-down → terminated and
     * setting {@code terminatedAt} for TTL pruning.
     */
    void terminate(Instance instance);

    /** Reboots the backing compute without an API-visible state transition. */
    void reboot(Instance instance);

    /** Whether the instance's backing compute is currently running. */
    boolean isContainerRunning(Instance instance);

    /**
     * Re-attaches emulator-side wiring (IMDS registration, host port reservations) to backing
     * compute that survived an emulator restart. Returns true when the instance's compute was
     * found running and wiring was restored.
     */
    boolean restoreMetadataRegistration(Instance instance);

    /**
     * Reconciles the exposed security-group ports of a running instance after
     * authorize/revoke-security-group-ingress. An empty set removes all exposed app ports.
     */
    void reconcilePublishedPorts(Instance instance, Set<Integer> desiredPorts);
}
