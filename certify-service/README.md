# Certify Service with Plugins

## Overview
`certify-service` is a streamlined service that does not bundle plugins directly within the Docker image. Instead, users are required to mount the necessary plugins during deployment. This provides flexibility in terms of which plugins are used and allows for easy updates without rebuilding the Docker image.

## Features
- Core service runs within the Docker image.
- Supports mounting plugins dynamically during deployment via volume mounts (e.g., Docker Compose or Helm charts).
- No bundled plugins; users can mount specific plugins required for their use case.
- Simplifies deployment by separating service core from plugin dependencies.

## Installation & Deployment
### Local logging
With the `local` Spring profile active, logs are written to both the console and
`logs/inji-certify.log`, relative to the application's working directory.
Restart the service after changing logging properties.

Follow the log from that working directory:
```sh
tail -f logs/inji-certify.log
```

Log files rotate daily or at 10 MB and are compressed. Archives are retained for
up to 7 days, subject to a 100 MB archive size cap. The `logs/` directory is
excluded from Git.

Set `LOGGING_FILE_NAME` to override the file path. For additional Certify logging,
set `LOGGING_LEVEL_IO_MOSIP_CERTIFY=DEBUG` before starting the service, or change
`logging.level.io.mosip.certify` in `src/main/resources/application-local.properties`.

### Prerequisites
- Docker installed on the system.
- Docker image for certify-service built or pulled from the registry.
- Required plugins available to mount (as external volumes).

### Deploying the Service
To deploy the service, ensure the necessary configurations are set in your deployment pipeline. Since the Docker image does not include plugins, they must be mounted at runtime.
New plugins  can be mounted loader_path using a volume mount either in [docker-compose](../docker-compose/docker-compose-injistack/README.md)  or [helm charts](../helm/inji-certify/)

#### Enabling HSM Client
To install the `hsm_client`, set the following environment variable in the deployment configuration:
```sh
install_hsm_client=true
```
