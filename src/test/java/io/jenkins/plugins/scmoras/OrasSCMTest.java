package io.jenkins.plugins.scmoras;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.scm.PollingResult;
import hudson.scm.SCM;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javaposse.jobdsl.plugin.ExecuteDslScripts;
import javaposse.jobdsl.plugin.LookupStrategy;
import javaposse.jobdsl.plugin.RemovedConfigFilesAction;
import javaposse.jobdsl.plugin.RemovedJobAction;
import javaposse.jobdsl.plugin.RemovedViewAction;
import land.oras.ArtifactType;
import land.oras.ContainerRef;
import land.oras.LocalPath;
import land.oras.Manifest;
import land.oras.Registry;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlRadioButtonInput;
import org.htmlunit.html.HtmlTextInput;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
    void shouldDetectChangesWhenArtifactIsUpdatedOnTheSameTag(JenkinsRule jenkinsRule, @TempDir Path tempDir)
            throws Exception {
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

    @Test
    void shouldPreserveScmSelectionThroughARealFormSubmissionRoundTrip(JenkinsRule jenkinsRule) throws Exception {
        String ref = pushRepo("form-round-trip");

        // Simulate a real user: start from a job with no SCM, select the "ORAS" radio button on the
        // configure page, fill in the container reference, and submit the form exactly like the browser would.
        FreeStyleProject p = jenkinsRule.createFreeStyleProject("form-round-trip");
        JenkinsRule.WebClient wc = jenkinsRule.createWebClient();
        HtmlPage page = wc.getPage(p, "configure");

        DomElement orasLabel = page.getFirstByXPath("//label[normalize-space(text())='ORAS']");
        assertNotNull(orasLabel, "The ORAS radio button should be offered in Source Code Management");
        HtmlRadioButtonInput orasRadio = (HtmlRadioButtonInput) page.getElementById(orasLabel.getAttribute("for"));
        orasRadio.click();
        ((HtmlTextInput) page.getElementsByName("_.containerRef").get(0)).setValueAttribute(ref);
        HtmlForm form = page.getFormByName("config");
        jenkinsRule.submit(form);

        FreeStyleProject saved = jenkinsRule.jenkins.getItemByFullName("form-round-trip", FreeStyleProject.class);
        assertInstanceOf(OrasSCM.class, saved.getScm());
        assertEquals(ref, ((OrasSCM) saved.getScm()).getContainerRef());

        // And crucially: re-opening the edit page must show ORAS as the selected SCM, with the
        // container reference pre-filled, not silently fall back to "None".
        HtmlPage editPage = wc.getPage(saved, "configure");
        List<DomElement> checkedOrasRadios = editPage.getByXPath(
                "//input[@name='scm' and @checked and following-sibling::label[normalize-space(text())='ORAS']]");
        assertFalse(checkedOrasRadios.isEmpty(), "ORAS should be pre-selected when editing the job");
        assertEquals(
                ref,
                ((HtmlTextInput) editPage.getElementsByName("_.containerRef").get(0)).getValueAttribute(),
                "The container reference should be pre-filled when editing the job");
    }

    @Test
    void shouldBeApplicableToPipelineJobsSoItAppearsInTheScmDropdownWhenEditing(JenkinsRule jenkinsRule)
            throws Exception {
        String ref = pushRepo("applicable-to-pipeline");

        OrasSCM scm = new OrasSCM(ref);
        scm.setInsecure(true);

        WorkflowJob p = jenkinsRule.createProject(WorkflowJob.class, "applicable-to-pipeline");
        p.setDefinition(new CpsScmFlowDefinition(scm, "Jenkinsfile"));
        p.save();

        // Regression test for: WorkflowJob is not a hudson.model.AbstractProject, and the default
        // SCMDescriptor#isApplicable(Job) only returns true for AbstractProject jobs. Without overriding it,
        // OrasSCM silently disappears from hudson.scm.SCM._for(job), which backs the "SCM" dropdown of
        // "Pipeline script from SCM" - so it can never be selected, or shown as selected, on a Pipeline job.
        assertTrue(
                SCM._for(p).stream().anyMatch(d -> d instanceof OrasSCM.DescriptorImpl),
                "OrasSCM must be applicable to Pipeline jobs");

        JenkinsRule.WebClient wc = jenkinsRule.createWebClient();
        HtmlPage page = wc.getPage(p, "configure");
        List<DomElement> containerRefInputs = page.getElementsByName("_.containerRef");
        assertFalse(containerRefInputs.isEmpty(), "The ORAS SCM fields should render on the Pipeline edit page");
        assertEquals(
                ref,
                ((HtmlTextInput) containerRefInputs.get(0)).getValueAttribute(),
                "The container reference should be pre-filled when editing the Pipeline job");
    }

    @Test
    void shouldConfigureOrasScmFromAJobDslSeedJob(JenkinsRule jenkinsRule) throws Exception {
        String ref = pushRepo("job-dsl");

        String dslScript = """
                job('dsl-generated') {
                    scm {
                        oras {
                            containerRef('%s')
                            insecure(true)
                        }
                    }
                }
                """.formatted(ref);

        ExecuteDslScripts dsl = new ExecuteDslScripts();
        dsl.setScriptText(dslScript);
        dsl.setUseScriptText(true);
        dsl.setSandbox(true);
        dsl.setFailOnMissingPlugin(true);
        dsl.setRemovedJobAction(RemovedJobAction.IGNORE);
        dsl.setRemovedViewAction(RemovedViewAction.IGNORE);
        dsl.setRemovedConfigFilesAction(RemovedConfigFilesAction.IGNORE);
        dsl.setLookupStrategy(LookupStrategy.JENKINS_ROOT);

        FreeStyleProject seed = jenkinsRule.createFreeStyleProject("seed");
        seed.getBuildersList().add(dsl);
        jenkinsRule.buildAndAssertSuccess(seed);

        FreeStyleProject generated = jenkinsRule.jenkins.getItemByFullName("dsl-generated", FreeStyleProject.class);
        assertNotNull(generated, "Job DSL should have generated the 'dsl-generated' job");
        assertInstanceOf(OrasSCM.class, generated.getScm());
        OrasSCM generatedScm = (OrasSCM) generated.getScm();
        assertEquals(ref, generatedScm.getContainerRef());
        assertTrue(generatedScm.isInsecure());
    }
}
