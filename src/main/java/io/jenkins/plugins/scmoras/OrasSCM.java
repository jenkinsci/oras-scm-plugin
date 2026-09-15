package io.jenkins.plugins.scmoras;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.scm.ChangeLogParser;
import hudson.scm.NullChangeLogParser;
import hudson.scm.PollingResult;
import hudson.scm.SCM;
import hudson.scm.SCMDescriptor;
import hudson.scm.SCMRevisionState;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Objects;
import jenkins.MasterToSlaveFileCallable;
import jenkins.model.Jenkins;
import land.oras.ArtifactType;
import land.oras.ContainerRef;
import land.oras.Manifest;
import land.oras.OCI;
import land.oras.Registry;
import land.oras.exception.OrasException;
import land.oras.policy.ContainersPolicy;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SCM implementation that checks out the content of a repository packaged as an OCI artifact of type
 * {@code application/vnd.jenkins.repo.manifest.v1+json} and pulled with ORAS.
 */
public class OrasSCM extends SCM {

    private static final Logger LOG = LoggerFactory.getLogger(OrasSCM.class);

    public static final ArtifactType ARTIFACT_TYPE_REPO =
            ArtifactType.from("application/vnd.jenkins.repo.manifest.v1+json");

    /**
     * Reference to the container holding the repository content, such as my-registry/my-repo:latest
     */
    private final String containerRef;

    /**
     * Credentials ID to authenticate against the registry
     */
    private String credentialsId;

    /**
     * Insecure flag to allow pulling from insecure registries (without TLS).
     */
    private boolean insecure;

    @DataBoundConstructor
    public OrasSCM(String containerRef) {
        this.containerRef = containerRef;
    }

    public String getContainerRef() {
        return containerRef;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    @SuppressWarnings("unused") // Used by Stapler
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    public boolean isInsecure() {
        return insecure;
    }

    @DataBoundSetter
    @SuppressWarnings("unused") // Used by Stapler
    public void setInsecure(boolean insecure) {
        this.insecure = insecure;
    }

    @Override
    public void checkout(
            @NonNull Run<?, ?> build,
            @NonNull Launcher launcher,
            @NonNull FilePath workspace,
            @NonNull TaskListener listener,
            @CheckForNull File changelogFile,
            @CheckForNull SCMRevisionState baseline)
            throws IOException, InterruptedException {
        Item item = build.getParent();
        StandardUsernamePasswordCredentials credentials = getCredentials(item, credentialsId);
        if (credentials != null) {
            CredentialsProvider.track(build, credentials);
        }
        ContainerRef ref = ContainerRef.parse(containerRef);
        Registry registry = buildRegistry(credentials, insecure);
        Manifest manifest = registry.getManifest(ref);
        ensureArtifactType(manifest);
        String digest = manifest.getDigest();

        listener.getLogger().printf("Checking out %s with digest %s into %s%n", containerRef, digest, workspace);

        String username = credentials != null ? credentials.getUsername() : null;
        String password = credentials != null ? credentials.getPassword().getPlainText() : null;
        workspace.act(new PullTask(ref.withDigest(digest).toString(), insecure, username, password));

        if (changelogFile != null) {
            createEmptyChangeLog(changelogFile, listener, "log");
        }
        build.addAction(new OrasSCMRevisionState(digest));
    }

    @Override
    public SCMRevisionState calcRevisionsFromBuild(
            @NonNull Run<?, ?> build,
            @Nullable FilePath workspace,
            @Nullable Launcher launcher,
            @NonNull TaskListener listener) {
        return build.getAction(OrasSCMRevisionState.class);
    }

    @Override
    public PollingResult compareRemoteRevisionWith(
            @NonNull Job<?, ?> project,
            @Nullable Launcher launcher,
            @Nullable FilePath workspace,
            @NonNull TaskListener listener,
            @NonNull SCMRevisionState baseline)
            throws IOException, InterruptedException {
        StandardUsernamePasswordCredentials credentials = getCredentials(project, credentialsId);
        Registry registry = buildRegistry(credentials, insecure);
        ContainerRef ref = ContainerRef.parse(containerRef);
        Manifest manifest = registry.getManifest(ref);
        ensureArtifactType(manifest);
        String remoteDigest = manifest.getDigest();
        String baselineDigest = baseline instanceof OrasSCMRevisionState state ? state.getDigest() : null;
        OrasSCMRevisionState remote = new OrasSCMRevisionState(remoteDigest);
        if (Objects.equals(remoteDigest, baselineDigest)) {
            listener.getLogger().printf("No change found for %s, digest is still %s%n", containerRef, remoteDigest);
            return new PollingResult(baseline, remote, PollingResult.Change.NONE);
        }
        listener.getLogger()
                .printf(
                        "Change found for %s, digest moved from %s to %s%n",
                        containerRef, baselineDigest, remoteDigest);
        return new PollingResult(baseline, remote, PollingResult.Change.SIGNIFICANT);
    }

    @Override
    public boolean requiresWorkspaceForPolling() {
        return false;
    }

    @Override
    public ChangeLogParser createChangeLogParser() {
        return new NullChangeLogParser();
    }

    @Override
    public String getKey() {
        return "oras-" + containerRef;
    }

    private static void ensureArtifactType(Manifest manifest) {
        if (!Objects.equals(
                ARTIFACT_TYPE_REPO.getMediaType(), manifest.getArtifactType().getMediaType())) {
            throw new IllegalArgumentException(
                    "The container reference does not point to a valid repository manifest. Make sure to set %s artifact type when pushing the artifact. Found artifact type %s instead"
                            .formatted(ARTIFACT_TYPE_REPO, manifest.getArtifactType()));
        }
    }

    private static Registry buildRegistry(StandardUsernamePasswordCredentials credentials, boolean insecure) {
        Registry.Builder builder = Registry.builder().withPolicy(ContainersPolicy.newPolicy());
        if (insecure) {
            builder = builder.insecure();
        }
        if (credentials == null) {
            LOG.debug("No credentials found for the container reference, will use default authentication");
            return builder.defaults().build();
        }
        LOG.debug("Credentials found: {}, creating registry for username {}", credentials, credentials.getUsername());
        return builder.defaults(
                        credentials.getUsername(), credentials.getPassword().getPlainText())
                .build();
    }

    public static @Nullable StandardUsernamePasswordCredentials getCredentials(Item item, String credentialsId) {
        if (credentialsId == null || credentialsId.isEmpty()) {
            return null;
        }
        return CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItem(
                        StandardUsernamePasswordCredentials.class, item, ACL.SYSTEM2, Collections.emptyList()),
                CredentialsMatchers.allOf(
                        CredentialsMatchers.withId(credentialsId),
                        CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class)));
    }

    /**
     * Pulls and extracts the repository artifact into the workspace. Runs on the node holding the workspace
     * (which may be a remote agent), so it must only rely on plain, serializable data.
     */
    private static final class PullTask extends MasterToSlaveFileCallable<Void> {

        private static final long serialVersionUID = 1L;

        private final String containerRef;
        private final boolean insecure;
        private final String username;
        private final String password;

        PullTask(String containerRef, boolean insecure, String username, String password) {
            this.containerRef = containerRef;
            this.insecure = insecure;
            this.username = username;
            this.password = password;
        }

        @Override
        public Void invoke(File workspace, VirtualChannel channel) throws IOException {
            Registry.Builder builder = Registry.builder().withPolicy(ContainersPolicy.newPolicy());
            if (insecure) {
                builder = builder.insecure();
            }
            Registry registry = (username == null || username.isEmpty())
                    ? builder.defaults().build()
                    : builder.defaults(username, password).build();
            if (!workspace.exists() && !workspace.mkdirs()) {
                throw new IOException("Unable to create workspace directory: " + workspace);
            }
            registry.pullArtifact(ContainerRef.parse(containerRef), workspace.toPath(), OCI.PullOptions.overwrite());
            return null;
        }
    }

    @Extension
    @Symbol("oras")
    @SuppressWarnings("unused")
    public static class DescriptorImpl extends SCMDescriptor<OrasSCM> {

        public DescriptorImpl() {
            super(null);
        }

        @Override
        public boolean isApplicable(Job project) {
            // The default in SCMDescriptor only returns true for AbstractProject (Freestyle-like) jobs.
            // Without this override, WorkflowJob (Pipeline) never sees OrasSCM as an option: it is silently
            // missing from the "Pipeline script from SCM" SCM dropdown and cannot be selected or edited there.
            return true;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "ORAS";
        }

        @SuppressWarnings("unused")
        @POST
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item item, @QueryParameter String credentialsId) {
            final StandardListBoxModel result = new StandardListBoxModel();
            if (item == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return result.includeCurrentValue(credentialsId);
                }
            } else {
                if (!item.hasPermission(Item.EXTENDED_READ) && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                    return result.includeCurrentValue(credentialsId);
                }
            }
            return result.includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            item,
                            StandardUsernameCredentials.class,
                            Collections.emptyList(),
                            CredentialsMatchers.instanceOf(StandardUsernameCredentials.class))
                    .includeCurrentValue(credentialsId);
        }

        @SuppressWarnings("unused")
        public FormValidation doCheckContainerRef(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.error("Reference is required");
            }
            return FormValidation.ok();
        }

        /**
         * Test connection to the registry
         */
        @POST
        public FormValidation doTestConnection(
                @AncestorInPath Item item,
                @QueryParameter String containerRef,
                @QueryParameter boolean insecure,
                @QueryParameter String credentialsId) {
            if (item != null) {
                item.checkPermission(Item.CONFIGURE);
            } else {
                Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            }
            if (containerRef == null || containerRef.trim().isEmpty()) {
                return FormValidation.error("Reference is required");
            }
            try {
                StandardUsernamePasswordCredentials credentials = getCredentials(item, credentialsId);
                Registry registry = buildRegistry(credentials, insecure);
                ContainerRef ref = ContainerRef.parse(containerRef);
                Manifest manifest = registry.getManifest(ref);
                ensureArtifactType(manifest);
                return FormValidation.ok("Success! Found Artifact " + manifest.getArtifactType() + " with digest "
                        + manifest.getDigest());
            } catch (IllegalArgumentException e) {
                return FormValidation.error("Invalid artifact: " + e.getMessage());
            } catch (OrasException e) {
                return FormValidation.error("Connection failed: " + e.getMessage());
            }
        }
    }
}
