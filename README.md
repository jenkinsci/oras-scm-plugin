# ORAS SCM Plugin

[![Build Status](https://ci.jenkins.io/buildStatus/icon?job=Plugins/oras-scm-plugin/main)](https://ci.jenkins.io/job/plugins/job/oras-scm-plugin/)
[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/oras-scm.svg)](https://plugins.jenkins.io/oras-scm/)
[![GitHub release](https://img.shields.io/github/release/jenkinsci/oras-scm-plugin.svg?label=changelog)](https://github.com/jenkinsci/oras-scm-plugin/releases/latest)
[![Contributors](https://img.shields.io/github/contributors/jenkinsci/oras-scm-plugin.svg)](https://github.com/jenkinsci/oras-scm-plugin/graphs/contributors)

## Introduction

This plugin provides an SCM (`hudson.scm.SCM`) implementation that checks out a repository packaged as an
OCI artifact and pulled with [ORAS](https://oras.land/).

It is the counterpart to [pipeline-cps-oras](https://plugins.jenkins.io/pipeline-cps-oras/): that plugin lets a
pipeline job fetch its Jenkinsfile from an OCI registry, but jobs configured that way have no SCM attached, so a
`checkout scm` step (implicit at the start of every declarative `agent` block) has nothing to check out. This
plugin fills that gap by making the repository artifact itself a real SCM that Jenkins can check out, poll, and
record against a build, exactly like Git or Subversion.

> [!WARNING]
> The ORAS Java SDK is currently in **beta** state and might impact the stability of this plugin.
>
> Its configuration and APIs might change in future releases.

<p align="left">
<a href="https://oras.land/"><img src="https://oras.land/img/oras.svg" alt="banner" width="200px"></a>
</p>

## Getting started

The plugin registers a new SCM named **ORAS**, selectable anywhere Jenkins lets you pick an SCM: a Freestyle
project's "Source Code Management" section, a Multibranch pipeline, or the SCM dropdown of a "Pipeline script from
SCM" job.

Credentials are optional if using an unsecured registry, otherwise provide a username/password credential.

In order to be checked out, the artifact must have the following media type:
`application/vnd.jenkins.repo.manifest.v1+json`

You can push such an artifact using the [ORAS CLI](https://oras.land/docs/commands/oras_push) from the root of the
repository you want to check out:

```bash
oras push localhost:5000/hello:latest --artifact-type application/vnd.jenkins.repo.manifest.v1+json .
```

The whole content of the artifact is extracted into the build workspace on every checkout, and the manifest
digest is used to detect changes when polling.

### Implicit checkout in a declarative pipeline

In the job configuration, under **Pipeline**, choose the **Pipeline script from SCM** definition, then pick
**ORAS** in the SCM dropdown and fill in the container reference, credentials, and script path (e.g.
`Jenkinsfile`). Because the job now has a real SCM attached, the implicit `checkout scm` that declarative
pipelines perform before the first stage works as expected, populating the workspace with the rest of the
repository too — no explicit `checkout` step needed in the Jenkinsfile itself.

### Explicit checkout step

If your pipeline is not itself SCM-backed (for example when using `pipeline-cps-oras` to fetch the script), you
can still check out a repository artifact explicitly with the generic `checkout` step, either using the
`@Symbol`-based shorthand:

```groovy
pipeline {
    agent any
    stages {
        stage('Checkout') {
            steps {
                checkout oras(containerRef: 'localhost:5000/hello:latest', insecure: true)
            }
        }
        stage('Build') {
            steps {
                sh './build.sh'
            }
        }
    }
}
```

or the classic `$class` map syntax:

```groovy
checkout([$class: 'OrasSCM', containerRef: 'localhost:5000/hello:latest', insecure: true])
```

### Freestyle projects

Select **ORAS** in the "Source Code Management" section of a Freestyle project and fill in the container reference,
credentials, and insecure flag as needed.

## Issues

Report issues and enhancements in the [Jenkins issue tracker](https://issues.jenkins.io/).

## Contributing

Refer to our [contribution guidelines](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md)

## LICENSE

Licensed under MIT, see [LICENSE](LICENSE.md)
