package io.jenkins.plugins.scmoras;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Run;
import hudson.scm.SCM;
import hudson.scm.SCMDescriptor;
import java.io.IOException;
import java.util.Objects;
import jenkins.scm.api.SCMFileSystem;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceDescriptor;
import land.oras.ContainerRef;
import land.oras.Layer;
import land.oras.Manifest;
import land.oras.Registry;
import land.oras.exception.OrasException;
import land.oras.utils.Const;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides {@link OrasSCMFileSystem} instances, enabling Pipeline's "lightweight checkout" for
 * {@link OrasSCM}: a single file (typically the Jenkinsfile) can be read directly from the packaged
 * artifact without extracting the whole repository to disk.
 */
@Extension
public class OrasSCMFileSystemBuilder extends SCMFileSystem.Builder {

  private static final Logger LOG = LoggerFactory.getLogger(OrasSCMFileSystemBuilder.class);

  @Override
  public boolean supports(SCM scm) {
    return scm instanceof OrasSCM;
  }

  @Override
  public boolean supports(SCMSource source) {
    return false;
  }

  @Override
  protected boolean supportsDescriptor(SCMDescriptor descriptor) {
    return descriptor instanceof OrasSCM.DescriptorImpl;
  }

  @Override
  protected boolean supportsDescriptor(SCMSourceDescriptor descriptor) {
    return false;
  }

  @CheckForNull
  @Override
  public SCMFileSystem build(
      @NonNull Item owner,
      @NonNull SCM scm,
      @CheckForNull SCMRevision rev,
      @CheckForNull Run<?, ?> build)
      throws IOException {
    if (!(scm instanceof OrasSCM oras)) {
      return null;
    }
    StandardUsernamePasswordCredentials credentials =
        OrasSCM.getCredentials(owner, oras.getCredentialsId());
    if (credentials != null && build != null) {
      CredentialsProvider.track(build, credentials);
    }
    Registry registry = OrasSCM.buildRegistry(credentials, oras.isInsecure());
    ContainerRef ref = ContainerRef.parse(oras.getContainerRef());
    Manifest manifest;
    try {
      manifest = registry.getManifest(ref);
    } catch (OrasException e) {
      throw new IOException("Unable to read manifest for " + oras.getContainerRef(), e);
    }
    try {
      OrasSCM.ensureArtifactType(manifest);
    } catch (IllegalArgumentException e) {
      throw new IOException(e.getMessage(), e);
    }
    Layer layer = selectGzipLayer(manifest);
    if (layer == null) {
      LOG.debug(
          "No {} layer found for {}, lightweight checkout is not supported for this artifact",
          Const.DEFAULT_BLOB_DIR_MEDIA_TYPE,
          oras.getContainerRef());
      return null;
    }
    return new OrasSCMFileSystem(
        ref.withDigest(layer.getDigest()).toString(), oras.isInsecure(), credentials);
  }

  private static Layer selectGzipLayer(Manifest manifest) {
    return manifest.getLayers().stream()
        .filter(l -> Objects.equals(Const.DEFAULT_BLOB_DIR_MEDIA_TYPE, l.getMediaType()))
        .findFirst()
        .orElse(null);
  }
}
