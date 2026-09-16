package io.jenkins.plugins.scmoras;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import jenkins.scm.api.SCMFile;
import jenkins.scm.api.SCMFileSystem;
import land.oras.ContainerRef;
import land.oras.Registry;
import land.oras.exception.OrasException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/**
 * Lightweight, read-only view of a single repository artifact layer, used to serve one or a few
 * files (typically just the Jenkinsfile) without extracting the whole artifact to disk. Streams the
 * packaged tar.gz directly from the registry and reads only the bytes of the entry actually
 * requested.
 */
final class OrasSCMFileSystem extends SCMFileSystem {

  /** Reference to the resolved layer blob, already pinned to its digest. */
  private final String layerRef;

  private final boolean insecure;
  private final StandardUsernamePasswordCredentials credentials;

  OrasSCMFileSystem(
      String layerRef, boolean insecure, StandardUsernamePasswordCredentials credentials) {
    super(null);
    this.layerRef = layerRef;
    this.insecure = insecure;
    this.credentials = credentials;
  }

  @Override
  public long lastModified() {
    return 0L;
  }

  @Override
  public SCMFile getRoot() {
    return new OrasSCMFile(this);
  }

  /**
   * Streams through every entry of the packaged tar.gz, invoking the visitor for each one. The
   * visitor receives the tar stream positioned at the start of the entry's content; it may read
   * from it (only for the entry it cares about) or ignore it entirely - unread bytes are skipped
   * automatically when the next entry is requested. Returning {@code false} from the visitor stops
   * the scan early.
   */
  void scan(EntryVisitor visitor) throws IOException {
    Registry registry = OrasSCM.buildRegistry(credentials, insecure);
    try (InputStream blob = registry.fetchBlob(ContainerRef.parse(layerRef));
        BufferedInputStream buffered = new BufferedInputStream(blob);
        GzipCompressorInputStream gzip = new GzipCompressorInputStream(buffered);
        TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        if (!visitor.visit(entry, tar)) {
          return;
        }
      }
    } catch (OrasException e) {
      throw new IOException("Failed to read repository artifact " + layerRef, e);
    }
  }

  @FunctionalInterface
  interface EntryVisitor {
    boolean visit(TarArchiveEntry entry, InputStream content) throws IOException;
  }
}
