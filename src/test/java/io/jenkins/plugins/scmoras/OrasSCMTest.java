package io.jenkins.plugins.scmoras;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.scm.PollingResult;
import java.nio.file.Files;
import java.nio.file.Path;
import land.oras.ArtifactType;
import land.oras.ContainerRef;
import land.oras.LocalPath;
import land.oras.Manifest;
import land.oras.Registry;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@WithJenkins
@Testcontainers(disabledWithoutDocker = true)
class OrasSCMTest {

    @Container
    private final ZotContainer container = new ZotContainer().withStartupAttempts(3);

    private static final Path REPO_DIR = Path.of("src/test/resources/repo").toAbsolutePath();

    private Registry registry;

    @BeforeEach
    void before() {
        registry = Registry.builder().insecure(container.getRegistry()).build();
    }

    private String pushRepo(String tag) {
        ContainerRef ref = ContainerRef.parse("%s/repo:%s".formatted(container.getRegistry(), tag));
        registry.pushArtifact(ref, OrasSCM.ARTIFACT_TYPE_REPO, LocalPath.of(REPO_DIR));
        return ref.toString();
    }

    @Test
    void shouldCheckoutRepositoryWithExplicitCheckoutStepInScriptedPipeline(JenkinsRule jenkinsRule) throws Exception {
        String ref = pushRepo("explicit");

        WorkflowJob p = jenkinsRule.createProject(WorkflowJob.class, "explicit-checkout");
        p.setDefinition(new CpsFlowDefinition("""
                node {
                    checkout([$class: 'OrasSCM', containerRef: '%s', insecure: true])
                    echo "Building..."
                    def content = readFile('repo/data/hello.txt').trim()
                    echo "content=${content}"
                }
                """.formatted(ref), true));

        WorkflowRun b = jenkinsRule.buildAndAssertSuccess(p);
        jenkinsRule.assertLogContains("Checking out", b);
        jenkinsRule.assertLogContains("content=hello-from-oras", b);
    }

    @Test
    void shouldSupportImplicitCheckoutInDeclarativePipelineViaScmBoundFlowDefinition(JenkinsRule jenkinsRule)
            throws Exception {
        String ref = pushRepo("implicit");

        OrasSCM scm = new OrasSCM(ref);
        scm.setInsecure(true);

        WorkflowJob p = jenkinsRule.createProject(WorkflowJob.class, "implicit-checkout");
        // No explicit "checkout" step anywhere: the declarative Jenkinsfile fetched from the repo
        // artifact itself relies on the implicit "checkout scm" performed before the first stage,
        // exactly like a job configured with "Pipeline script from SCM".
        p.setDefinition(new CpsScmFlowDefinition(scm, "repo/Jenkinsfile"));

        WorkflowRun b = jenkinsRule.buildAndAssertSuccess(p);
        jenkinsRule.assertLogContains("content=hello-from-oras", b);
    }

    @Test
    void shouldFailBuildWhenArtifactTypeIsNotRepoManifest(JenkinsRule jenkinsRule) throws Exception {
        ContainerRef ref = ContainerRef.parse("%s/repo:wrong-type".formatted(container.getRegistry()));
        registry.pushArtifact(
                ref, ArtifactType.from("application/vnd.jenkins.pipeline.manifest.v1+json"), LocalPath.of(REPO_DIR));

        WorkflowJob p = jenkinsRule.createProject(WorkflowJob.class, "wrong-artifact-type");
        p.setDefinition(new CpsFlowDefinition("""
                node {
                    checkout([$class: 'OrasSCM', containerRef: '%s', insecure: true])
                }
                """.formatted(ref), true));

        WorkflowRun b = jenkinsRule.buildAndAssertStatus(Result.FAILURE, p);
        jenkinsRule.assertLogContains("does not point to a valid repository manifest", b);
    }

    @Test
    void shouldDetectNoChangesWhenArtifactDigestIsUnchanged(JenkinsRule jenkinsRule) throws Exception {
        String ref = pushRepo("poll-nochange");

        OrasSCM scm = new OrasSCM(ref);
        scm.setInsecure(true);

        FreeStyleProject p = jenkinsRule.createFreeStyleProject("poll-nochange");
        p.setScm(scm);
        jenkinsRule.buildAndAssertSuccess(p);

        PollingResult result = p.poll(TaskListener.NULL);
        assertFalse(result.hasChanges(), "No change was pushed to the registry, polling should report no changes");
    }

    @Test
    void shouldDetectChangesWhenArtifactIsUpdatedOnTheSameTag(
            JenkinsRule jenkinsRule, @org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        String tag = "poll-change";
        String ref = pushRepo(tag);

        OrasSCM scm = new OrasSCM(ref);
        scm.setInsecure(true);

        FreeStyleProject p = jenkinsRule.createFreeStyleProject("poll-change");
        p.setScm(scm);
        jenkinsRule.buildAndAssertSuccess(p);

        // Push new content on the same mutable tag
        Path updatedRepo = tempDir.resolve("repo");
        Files.createDirectories(updatedRepo.resolve("data"));
        Files.writeString(updatedRepo.resolve("data/hello.txt"), "hello-from-oras-v2\n");
        ContainerRef containerRef = ContainerRef.parse("%s/repo:%s".formatted(container.getRegistry(), tag));
        Manifest updated = registry.pushArtifact(containerRef, OrasSCM.ARTIFACT_TYPE_REPO, LocalPath.of(updatedRepo));

        PollingResult result = p.poll(TaskListener.NULL);
        assertTrue(result.hasChanges(), "Digest changed to " + updated.getDigest() + ", polling should report changes");
        assertEquals(PollingResult.Change.SIGNIFICANT, result.change);
    }

    @Test
    void configRoundTripShouldPreserveDefinition(JenkinsRule jenkinsRule) throws Exception {
        String ref = "localhost:5000/repo:latest";

        FreeStyleProject p = jenkinsRule.createFreeStyleProject("config-round-trip");
        OrasSCM scm = new OrasSCM(ref);
        scm.setInsecure(true);
        p.setScm(scm);

        jenkinsRule.configRoundtrip(p);

        assertEquals(OrasSCM.class, p.getScm().getClass());
        OrasSCM reloaded = (OrasSCM) p.getScm();
        assertEquals(ref, reloaded.getContainerRef());
        assertTrue(reloaded.isInsecure());
        assertEquals("", reloaded.getCredentialsId(), "Credentials ID should be empty by default");
    }
}
