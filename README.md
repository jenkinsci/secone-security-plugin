# SECONE Security

<a href="https://sec1.io">
  <img src="https://sec1.io/wp-content/uploads/2023/10/sec1-logo-svg-orange.svg" alt="SECONE" width="200" height="100">
</a>

## Introduction

SECONE Security plugin help developers/teams to scan their SCM for open source vulnerabilities against SECONE Security DB

## Usage
To use the plugin up you will need to take the following steps in order:

1. [Install the SECONE Security Plugin](#1-install-the-secone-security-plugin)
2. [Configure a SECONE API Token Credential](#2-configure-a-secone-api-token-credential)

## 1. Install the SECONE Security Plugin

- Go to "Manage Jenkins" > "Manage Plugins" > "Available".
- Search for "SECONE Security".
- Install the plugin.

## 2. Configure a SECONE API Token Credential

- Go to "Manage Jenkins" > "Manage Credentials"
- Choose a Store
- Choose a Domain
- Go to "Add Credentials"
- Select "Secret text"
- Add SECONE_API_KEY as ID and Configure the Credentials

## LICENSE

Licensed under MIT, see [LICENSE](LICENSE.md)

