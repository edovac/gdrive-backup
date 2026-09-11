# Google Credentials Setup

This guide configures the service account used by the application to preview and back up Google Workspace Drive data.

The application uses two separate authentication paths:

- OAuth login: unlocks the admin UI with `drive.readonly` access.
- Service account with domain-wide delegation: accesses Drive data while impersonating Workspace users.

The service-account path requires a Google Workspace organization. It cannot impersonate personal Gmail accounts such as `user@gmail.com`.

## 1. Create or select a Google Cloud project

1. Open the [Google Cloud Console](https://console.cloud.google.com/).
2. Create a project or select the project used by this application.
3. Make sure billing and organization policies permit the required APIs.

## 2. Enable APIs

In **APIs & Services > Library**, enable:

- **Google Drive API**
- **Admin SDK API**

The Drive API is used for Drive listing and backup operations. The Admin SDK API is used later for Workspace user enumeration.

## 3. Configure OAuth login

OAuth login unlocks the application UI for the administrator. It uses only the
read-only Drive scope and does not provide domain-wide impersonation.

### Configure the OAuth consent screen

1. In Google Cloud Console, open **APIs & Services > OAuth consent screen**.
2. Choose **Internal** if the Cloud project belongs to a Google Workspace organization.
	Choose **External** for testing with a personal Google account.
3. Enter the application name and support information.
4. Add this scope:

```text
https://www.googleapis.com/auth/drive.readonly
```

5. If the application is configured as **External**, add the account used for
	testing under **Test users**.
6. Save the consent-screen configuration.

### Create the OAuth client

1. Open **APIs & Services > Credentials**.
2. Click **Create credentials > OAuth client ID**.
3. Select **Desktop app** as the application type.
4. Give the client a descriptive name, for example `gdrive-backup-desktop`.
5. Create the client and click **Download JSON**.

Store the downloaded file outside the repository, for example:

```text
/home/edoardo/client_secret.json
```

The application uses an installed-app loopback redirect. Do not create a web
application client for this flow, and do not commit the client-secrets JSON.

## 4. Create a service account

1. Open **IAM & Admin > Service Accounts**.
2. Click **Create service account**.
3. Give it a descriptive name, for example `gdrive-backup`.
4. Create the service account.
5. Open the created service account and copy its **OAuth 2 Client ID** if domain-wide delegation is shown. This is a numeric client ID and is different from the service-account email address.

Do not grant broad project roles unless they are required. Drive access is granted through domain-wide delegation and OAuth scopes.

## 5. Create a service-account key

1. Open the service account.
2. Go to **Keys**.
3. Click **Add key > Create new key**.
4. Select **JSON**.
5. Download the key and store it outside the repository.

Example Linux/WSL location:

```text
/home/edoardo/gdrive-service-account.json
```

Protect the file:

```bash
chmod 600 /home/edoardo/gdrive-service-account.json
```

Never commit this file, copy it into `src/`, or share it publicly. If the key is exposed, revoke it immediately in Google Cloud Console and create a replacement.

## 6. Enable domain-wide delegation

Domain-wide delegation must be enabled for the service account. Depending on the Cloud Console layout, this is available from the service account details under **Domain-wide delegation** or from the service account's advanced settings.

Copy the service account's **OAuth 2 Client ID**. You will enter this value in the Google Workspace Admin Console.

## 7. Authorize the service account in Admin Console

A Google Workspace super administrator must perform this step.

1. Open the [Google Admin Console](https://admin.google.com/).
2. Go to **Security > Access and data control > API controls**.
3. Open **Manage Domain Wide Delegation**.
4. Click **Add new**.
5. Enter the service account's **OAuth 2 Client ID**.
6. Add these OAuth scopes, one per line or separated according to the Admin Console field instructions:

```text
https://www.googleapis.com/auth/drive.readonly
https://www.googleapis.com/auth/admin.directory.user.readonly
```

7. Save the authorization.

The scopes must exactly match the scopes requested by the application. The first scope allows read-only Drive access for the impersonated user. The second allows read-only Workspace user enumeration.

## 8. Choose an impersonated Workspace user

The service account can impersonate only an account in the Workspace domain that authorized the delegation.

Use a real Workspace user, for example:

```text
admin@your-workspace-domain.com
```

Do not use a personal Gmail account such as:

```text
user@gmail.com
```

The impersonated user must have access to the Drive data that the application is expected to preview. For organization-wide backup, the service account uses domain-wide delegation to impersonate each Workspace user as required by the backup workflow.

## 9. Configure the application

Set the following environment variables before starting the application:

```bash
export GOOGLE_SERVICE_ACCOUNT_KEY=/home/edoardo/gdrive-service-account.json
export GOOGLE_IMPERSONATED_USER=admin@your-workspace-domain.com
```

The OAuth UI login also requires a desktop OAuth client-secrets file:

```bash
export GOOGLE_OAUTH_CLIENT_SECRETS=/home/edoardo/client_secret.json
```

Start the application:

```bash
./mvnw spring-boot:run
```

Or provide the values for one command:

```bash
GOOGLE_OAUTH_CLIENT_SECRETS=/home/edoardo/client_secret.json \
GOOGLE_SERVICE_ACCOUNT_KEY=/home/edoardo/gdrive-service-account.json \
GOOGLE_IMPERSONATED_USER=admin@your-workspace-domain.com \
./mvnw spring-boot:run
```

## 10. Verify the setup

After signing in through the UI:

- The UI should show `Google connected` after OAuth login.
- The Drive preview should show `Loading available drives...`.
- `My Drive` and accessible Shared Drives should appear when delegation is configured correctly.

If the UI shows `Google connected, Drive preview unavailable`, check:

1. `GOOGLE_IMPERSONATED_USER` is a Workspace account, not a personal Gmail account.
2. The service-account key belongs to the Cloud project where delegation was configured.
3. The Admin Console authorization uses the service account's OAuth 2 Client ID.
4. Both scopes are authorized exactly as shown above.
5. The Drive API and Admin SDK API are enabled.
6. The Workspace user exists and has access to the expected Drive data.
7. Changes to domain-wide delegation have had a few minutes to propagate.

## Security checklist

- Keep service-account JSON keys outside the repository.
- Do not commit OAuth client-secrets JSON, access tokens, refresh tokens, or service-account keys.
- Use the minimum required scopes.
- Restrict local key-file permissions.
- Rotate and revoke keys if they are exposed.
- Use a dedicated service account for this application.
