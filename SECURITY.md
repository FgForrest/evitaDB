# Security Policy

## Supported Versions

evitaDB is currently in active development and fixes are back ported only to a few recent versions.

| Version     | Supported          |
|-------------|--------------------|
| 2025.7.x    | :white_check_mark: |
| 2025.6.x    | :white_check_mark: |
| 2025.5.x    | :white_check_mark: |
| <= 2025.4.x | :x:                |

## Reporting a Vulnerability

If you discover any vulnerability that could be exploited by malicious actors, please follow these steps:

1. **Contact us:** Send an email to [application_development@fg.cz](mailto:application_development@fg.cz)
2. **Do not disclose publicly:** Refrain from publishing the vulnerability details until we’ve confirmed a fix or provided guidance.
3. **Include details:** Provide clear information about the nature of the vulnerability, including steps to reproduce.

## Responsible Disclosure

We appreciate the efforts of the security community to responsibly disclose vulnerabilities. 
As a token of gratitude, we may publicly thank you once the vulnerability is confirmed and fixed (unless you prefer to remain anonymous).

## Encryption & Sensitive Data

To exchange sensitive information safely, we support encryption via PGP.

- **Public PGP Key:** [Download our public key](https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x9d1149b0c74e939dd766c7a93de3cdccf660797f)
- **Fingerprint:** `9D11 49B0 C74E 939D D766  C7A9 3DE3 CDCC F660 797F`

### Encrypting a Message with PGP

**Import the recipient’s public key:**

```bash
gpg --import recipient_public_key.asc
```

**Verify the key:**

```bash
gpg --fingerprint recipient@example.com
```

Ensure the fingerprint matches what’s listed above or on our official site.

**Encrypt your file or message:**

```bash
gpg --output message.enc --encrypt --recipient recipient@example.com message.txt
```

**Send the encrypted file:**

Attach or otherwise transmit message.enc.
