package io.jenkins.plugins.scmoras;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.scm.SCMRevisionState;

/**
 * Represents the state of a {@link OrasSCM} at a given point in time, identified by the manifest
 * digest of the repository artifact.
 */
public class OrasSCMRevisionState extends SCMRevisionState {

  private final String digest;

  public OrasSCMRevisionState(@CheckForNull String digest) {
    this.digest = digest;
  }

  @CheckForNull
  public String getDigest() {
    return digest;
  }

  @Override
  public String toString() {
    return "OrasSCMRevisionState{digest='" + digest + "'}";
  }
}
