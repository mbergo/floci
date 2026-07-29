package io.github.hectorvent.floci.core.common.azure;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thin subprocess runner for the Azure CLI ({@code az}). Used by Azure-backed service
 * providers (e.g. {@code floci.services.eks.provider=aks}) to provision real Azure
 * resources while Floci keeps serving the AWS wire protocol.
 *
 * <p>Deliberately a subprocess wrapper rather than the Azure SDK: no new dependencies,
 * GraalVM native-image safe, and it reuses the developer's existing {@code az login}
 * session and default subscription. The binary path is configurable via
 * {@code floci.azure.cli-path}; {@code floci.azure.subscription} (when set) is passed
 * as {@code --subscription} on every invocation.
 */
@ApplicationScoped
public class AzureCli {

    private static final Logger LOG = Logger.getLogger(AzureCli.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    private final EmulatorConfig config;
    private final AtomicBoolean resourceGroupEnsured = new AtomicBoolean(false);

    @Inject
    public AzureCli(EmulatorConfig config) {
        this.config = config;
    }

    /** Result of one {@code az} invocation. */
    public record Result(int exitCode, String stdout, String stderr) {
        public boolean succeeded() {
            return exitCode == 0;
        }
    }

    /**
     * Runs {@code az <args>} with the default timeout, appending
     * {@code --subscription} when configured. Never throws on a non-zero exit code —
     * callers inspect {@link Result#succeeded()}.
     */
    public Result run(List<String> args) {
        return run(args, DEFAULT_TIMEOUT);
    }

    /** Runs {@code az <args>} with an explicit timeout. */
    public Result run(List<String> args, Duration timeout) {
        List<String> command = new ArrayList<>();
        command.add(config.azure().cliPath());
        command.addAll(args);
        config.azure().subscription().ifPresent(sub -> {
            command.add("--subscription");
            command.add(sub);
        });

        LOG.debugv("Running: {0}", String.join(" ", command));
        try {
            Process process = new ProcessBuilder(command).start();
            // Drain stderr concurrently so a chatty CLI cannot fill the pipe and deadlock.
            StringBuilder stderr = new StringBuilder();
            Thread stderrDrain = Thread.ofVirtual().start(() -> drain(process.getErrorStream(), stderr));
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Result(-1, stdout, "az command timed out after " + timeout.toSeconds() + "s");
            }
            stderrDrain.join(TimeUnit.SECONDS.toMillis(5));
            return new Result(process.exitValue(), stdout, stderr.toString());
        } catch (IOException e) {
            return new Result(-1, "", "could not start '" + config.azure().cliPath() + "': " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "", "interrupted while waiting for az: " + e.getMessage());
        }
    }

    private static void drain(InputStream stream, StringBuilder sink) {
        try {
            sink.append(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Process died or stream closed; whatever was read is enough for diagnostics.
        }
    }

    /**
     * Creates the shared {@code floci.azure.resource-group} on first use when
     * {@code floci.azure.auto-create-resource-group} is enabled. {@code az group create}
     * is idempotent, so pre-existing groups are untouched. Shared by all Azure-backed
     * providers; the group is only ensured once per emulator run (retried on failure).
     */
    public void ensureResourceGroup() {
        if (!config.azure().autoCreateResourceGroup() || !resourceGroupEnsured.compareAndSet(false, true)) {
            return;
        }
        Result result = run(List.of(
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
}
