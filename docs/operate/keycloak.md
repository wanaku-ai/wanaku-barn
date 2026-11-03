---
title: Set up Keycloak for Wanaku
description: Deploy Keycloak with Podman or on Kubernetes, import the Wanaku realm, configure MCP clients, and manage users and service clients.
---

# Set up Keycloak for Wanaku

Wanaku uses Keycloak for authentication when oauth2-proxy protects it. This page shows how to deploy Keycloak, import the Wanaku realm, and manage users and clients.

> [!NOTE]
> The Barn backend does not enforce authentication. Wanaku and its oauth2-proxy control access to it. See
> [Run the backend without authentication](install.md#run-the-backend-without-authentication).

Choose the setup that matches your environment.

- **Local Development:** Use Podman for a quick, local instance.
- **OpenShift Deployment:** Follow these steps for a cluster environment.

## Option 1: Local Setup with Podman

This method is ideal for development and testing on your local machine.

### Starting the Keycloak Container

First, run the following command in your terminal to start a Keycloak container.
This command also sets the initial admin credentials and maps a local volume for data persistence.

```shell
podman run -d \
  -p 127.0.0.1:8543:8080 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin \
  -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  -v keycloak-dev:/opt/keycloak/data \
  quay.io/keycloak/keycloak:26.7 start-dev
```

- `-p 127.0.0.1:8543:8080`: Maps port `8543` on your local machine to the container's port `8080`. By default, Wanaku expects Keycloak on port `8543`.
- `-e ...`: Sets the default **admin** username and password. Change the password for any non-trivial use case.
- `-v keycloak-dev...`: Creates a persistent volume named `keycloak-dev` to store Keycloak data.

## Option 2: Deploying to OpenShift or Kubernetes

If you are deploying Wanaku in OpenShift or Kubernetes, you can follow these steps to get an entirely new Keycloak setup up and
running.
If you already have a Keycloak instance, you may skip the deployment section and jump to importing the realm.

### Deploying Keycloak

Apply the pre-defined Kubernetes configurations located in the wanaku-barn [`deploy/auth`](https://github.com/wanaku-ai/wanaku-barn/tree/main/deploy/auth) directory.
This will create all the necessary resources for Keycloak to run.

> [!IMPORTANT]
> Before applying, review the files and be sure to change the default admin password for security.

- Openshift

The keycloak.yaml contains a ingress resource, in Openshift it automatically creates an Openshift route with TLS.

```shell
NS=$(kubectl config view --minify -o jsonpath='{..namespace}')
DOMAIN=$(oc get ingresses.config.openshift.io cluster -o jsonpath='{.spec.domain}')
KC_HOST="keycloak-$NS.$DOMAIN"
cat deploy/auth/keycloak.yaml | sed -z "s/- hosts:\n *- KEYCLOAK_HOST/- {}/;s/KEYCLOAK_HOST/$KC_HOST/g"| kubectl create -f -
```

- Minikube

```shell
cat deploy/auth/keycloak.yaml | sed "s/KEYCLOAK_HOST/keycloak.$(minikube ip).nip.io/"| kubectl create -f -
```

## Importing the Wanaku Realm Configuration (via Wanaku Keycloak Admin CLI)

The simplest way to import the realm configuration is using the `wanaku-keycloak-admin` CLI. You can set admin credentials once via environment variables:

```shell
export WANAKU_ADMIN_USERNAME=admin
export WANAKU_ADMIN_PASSWORD=admin
wanaku-keycloak-admin realm create
```

This imports the default realm configuration from `deploy/auth/wanaku-config.json`. You can specify a custom configuration file with `--config /path/to/realm.json` and a custom Keycloak URL with `--keycloak-url`.
Also, in case the keycloak server is in https with a self-signed certificate, then you have to import the realm config file with the Keycloak UI or execute the `deploy/auth/configure-auth.sh` script.

To get the keycloak server:

- Minikube : `echo "https://"$(kubectl get ingress keycloak -o jsonpath='{.spec.rules[0].host}')`
- OpenShift: `echo "https://"$(kubectl get route keycloak -o jsonpath='{.spec.host}')`

## Importing the Wanaku Realm Configuration (via Shell Script)

Alternatively, Wanaku comes with a [script that simplifies importing](https://github.com/wanaku-ai/wanaku-barn/blob/main/deploy/auth/configure-auth.sh)
the realm configuration into keycloak.

The script takes care to discover the Keycloak server address.
You can optionally customize the keycloak URL, user and password with the following environment variables:

- set `WANAKU_KEYCLOAK_PASS` to the admin password of your Keycloak instance
- set `WANAKU_KEYCLOAK_HOST` to the address of your Keycloak instance (i.e.; `localhost` if using Podman). If in OpenShift or minikube, it already takes care to discover it.

## Importing the Wanaku Realm Configuration (via Keycloak UI)

Alternatively, you may also import the configuration using Keycloak's UI, and then proceed to regenerate the service client secret.

### Regenerating the Service Client Secret

Finally, for security, you must regenerate the client secret for the `wanaku-service` client.

1. Navigate to the Keycloak Admin Console at `http://localhost:8543`.
2. Log in with your admin credentials (**admin**/**admin**).
3. Select the **wanaku** realm from the dropdown in the top-left corner.
4. Go to **Clients** in the side menu and click on **wanaku-service**.
5. Go to the **Credentials** tab.
6. Click the **Regenerate secret** button and confirm. Copy the new secret to use in your application's configuration.

![Screenshot of Keycloak admin console showing the wanaku-service client credentials tab with the Regenerate secret button](../imgs/keycloak-service.png)

## Configure MCP clients

When connecting an MCP client application to Wanaku, you need to configure it with the correct OIDC client ID and scopes.
By default, the Wanaku Keycloak realm provides the following settings for MCP clients:

- **Client ID**: `mcp-client`
- **Scopes**: `openid`, `wanaku-mcp-client`

When an application uses these settings to connect to a Wanaku MCP endpoint, the user will be redirected to the
Keycloak login page. After entering their username and password, Keycloak will redirect them back to the application
with a valid authentication token.

> [!NOTE]
> If using automatic client registration, the registered client will use these same defaults. Applications only need
> to request a new client ID and grant when changing the namespace in use.

## Admin commands

The standalone `wanaku-keycloak-admin` binary provides Keycloak administration operations for managing realms, users, and service client credentials. These commands authenticate directly against Keycloak using admin credentials rather than the user's stored token.

### Common Options

All admin commands share the following options:

| Option | Description | Default |
|---|---|---|
| `--admin-username` | Admin username for Keycloak (required unless `WANAKU_ADMIN_USERNAME` is set) | |
| `--admin-password` | Admin password for Keycloak (required unless `WANAKU_ADMIN_PASSWORD` is set, interactive) | |
| `--keycloak-url` | Keycloak server URL | `http://localhost:8543` |
| `--realm` | Keycloak realm to manage | `wanaku` |

If you prefer not to pass admin credentials on every command, set them once in your environment:

```shell
export WANAKU_ADMIN_USERNAME=admin
export WANAKU_ADMIN_PASSWORD=admin
wanaku-keycloak-admin users list
```

### User Management

```shell
# List all users in the realm
wanaku-keycloak-admin users list --admin-username admin --admin-password admin

# Create a new user (email, first-name, last-name are optional and default to username-based values)
# By default, the email is marked as verified (equivalent to passing --verified).
# Use --no-verified to leave it unverified, but note that emailVerified must be true
# for OIDC login to succeed; otherwise Keycloak returns "account_not_fully_set_up"
# and the CLI throws ServiceAuthException: Account is not fully set up.
wanaku-keycloak-admin users add --admin-username admin --admin-password admin \
  --username alice --password secretpass \
  --email alice@example.com --first-name Alice --last-name Smith

# Create a user with an unverified email
wanaku-keycloak-admin users add --admin-username admin --admin-password admin \
  --username bob --password secretpass --no-verified

# Remove a user
wanaku-keycloak-admin users remove --admin-username admin --admin-password admin \
  --username alice

# Set a user's password
wanaku-keycloak-admin users set-password --admin-username admin --admin-password admin \
  --username alice --password newpass
```

### Service Client Credential Management

```shell
# List service clients (filters out internal Keycloak clients)
wanaku-keycloak-admin credentials list --admin-username admin --admin-password admin

# Create a new service client
wanaku-keycloak-admin credentials add --admin-username admin --admin-password admin \
  --client-id my-service --description "My service client"

# Create a service client and display its secret
wanaku-keycloak-admin credentials add --admin-username admin --admin-password admin \
  --client-id my-service --show-secret

# Show an existing client's secret
wanaku-keycloak-admin credentials show --admin-username admin --admin-password admin \
  --client-id my-service --show-secret

# Regenerate a client's secret
wanaku-keycloak-admin credentials regenerate --admin-username admin --admin-password admin \
  --client-id my-service --show-secret

# Remove a service client
wanaku-keycloak-admin credentials remove --admin-username admin --admin-password admin \
  --client-id my-service
```

> [!NOTE]
> The `--show-secret` flag is required to display client secrets. Without it, `credentials show` will print a warning instead. Use with caution as secrets may leak into logs or shell history.

To capture only the secret in a script, add `--plain` with `--show-secret`. This writes the secret alone to standard output. Errors use standard error, and the command returns a non-zero status if it cannot return a secret:

```shell
CLIENT_SECRET=$(wanaku-keycloak-admin credentials show --admin-username admin --admin-password admin \
  --client-id my-service --show-secret --plain)
```

### Realm Management

```shell
# Import the default realm configuration (deploy/auth/wanaku-config.json)
wanaku-keycloak-admin realm create --admin-username admin --admin-password admin

# Import a custom realm configuration file
wanaku-keycloak-admin realm create --admin-username admin --admin-password admin \
  --config /path/to/realm.json

# Import with a custom Keycloak URL
wanaku-keycloak-admin realm create --keycloak-url http://keycloak:8080 \
  --admin-username admin --admin-password admin
```

## Next steps

- [Authenticate the Wanaku CLI](../cli/authenticate.md): log in with the realm that you created.
- [Secure a deployment](secure-a-deployment.md): check the rest of the deployment before you go live.
