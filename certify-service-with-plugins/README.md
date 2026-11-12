# Certify Service with Plugins

## Overview
`certify-service-with-plugins` is a streamlined service that bundles runtime JARs within the Docker image, eliminating the need for an external artifact repository like Artifactory. This enhances deployment efficiency and reduces external dependencies.
This is built on top of base inji-certify docker image and includes the plugins required for the service to run.

## Features
- Bundles runtime JARs directly within the Docker image.
- Simplifies deployment process for demos and POCs.
- Removes dependency on Artifactory.
- Supports optional installation of `hsm_client` during deployment.

## Installation & Deployment
### Prerequisites
- Docker installed on the system.
- Required JARs included in the build process.

### Deploying the Service
To deploy the service, ensure the necessary configurations are set in your deployment pipeline. The JARs are already included in the image, so no additional artifact retrieval steps are needed.
New plugins if needed can be mounted loader_path using a volume mount either in [docker-compose](../docker-compose/docker-compose-injistack/README.md)  or [helm charts](../helm/inji-certify/)

#### Enabling HSM Client
To install the `hsm_client`, set the following environment variable in the deployment configuration:
```sh
install_hsm_client=true
```

Build the ERL router distribution from the parent checkout so the local plugin artifacts are available in the Maven reactor:

```sh
mvn -Pwith-plugins package -DskipTests -Dgpg.skip=true
```

The distribution copies `certify-dataprovider-router.jar`, `dmt-data-access.jar`, `erl-dataprovider-plugin.jar` and `mysql-connector-j.jar` alongside the existing plugin JARs. Standalone builds of this directory require the same-version parent and plugin modules to have been installed first. See `../erl-dataprovider-plugin/README.md` for configuration and internal offer routing.
