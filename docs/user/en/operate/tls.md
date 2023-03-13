---
title: Setting up TLS
perex:
date: '1.3.2023'
author: 'Bc. Tomáš Pozler'
proofreading: 'needed'
published: false
---

<UsedTerms>
    <h4>Used terms</h4>
   <dl>
      <dt>certificate authority</dt>
      <dd>
         the certificate authority is an entity that stores, signs, and issues digital certificates. A digital certificate 
         certifies the ownership of a public key by the named subject of the certificate. This allows others (relying 
         parties) to rely upon signatures or on assertions made about the private key that corresponds to the certified 
         public key. A CA acts as a trusted third party—trusted both by the subject (owner) of the certificate and by 
         the party relying upon the certificate. [source](https://en.wikipedia.org/wiki/Certificate_authority)
      </dd>
      <dt>certificate</dt>
      <dd>
         the certificate is an electronic document used to prove the validity of a public key, and it contains a public 
         key along with a lot of additional information [source](https://en.wikipedia.org/wiki/Public_key_certificate)
      </dd>
      <dt>private key</dt>
      <dd>
         the private key is a classified part of the public/private key pair - it must never leave the secured perimeter.
         An entity that proves itself with a private key is considered authentic - a private key is similar to an ID card
         [see more](https://en.wikipedia.org/wiki/Public-key_cryptography)
      </dd>
      <dt>public key</dt>
      <dd>
         the public key is the second part public/private key pair - it may be freely distributed  and its ownership 
         does not entitle to anything, it only serves to prove the authenticity of the private key 
         [see more](https://en.wikipedia.org/wiki/Public-key_cryptography)
      </dd>
   </dl>
</UsedTerms>

All evitaDB APIs support the secure layer (HTTPS). The gRPC is completely based on the HTTP/2 protocol, which is binary
and requires encryption. Because of this fact, all evitaDB external APIs work only on secure protocol to keep the
security uniform.

<Note type="question">

<NoteTitle toggles="true">

##### How is it possible that I didn't have to configure a single certificate and everything "just" works out of the box?
</NoteTitle>

We don't want to make things complicated for developers and newcomers, but that doesn't mean that the default behavior
is secure, because it can't be. The evitaDB server automatically generates a self-signed <Term>certificate authority</Term> and
issues the server certificate required for TLS. This certificate will not be trusted by the clients unless you force
them to. Usually it's just a matter of toggling some switches and for development purposes it's good enough. For
production environments, we strongly recommend issuing your own <Term>certificate</Term> using the [Let's Encrypt](https://letsencrypt.org) 
authority, which can be automated and is part of all certificate trust chains these days.

</Note>

If you are familiar with certificate handling, you can skip the entire [create a certificate](#creating-a-certificate)
chapter and go to [configuration](configure.md#tls-configuration) or read about [mTLS](#mutual-tls-for-grpc) support for 
the gRPC protocol.

## Creating a certificate

You need a <Term>certificate</Term> to prove your identity, whether you are a server or a client.

It is possible to divide certificates into two groups according to the certificate signature:

- Certificates signed by a publicly trusted root certificate authority
- Certificates signed by a private certification authority

### Publicly trusted certificate authority

You can buy a <Term>certificate</Term> from commercial <Term name="certificate authority">certificate authorities</Term>,
or you can generate one for free using [Let's Encrypt](https://letsencrypt.org). You can find lots of information about
this process on the web, so we won't duplicate it here. To generate a free server certificate, follow the instructions 
on the [Certbot site](https://certbot.eff.org/).

### Self-signed certificate authority

In this guide, we will focus on the second group: self-signed certificates. When using [mTLS](#mutual-tls-for-grpc), it is 
necessary for the server to have access to a <Term>certificate authority</Term> that trusts it, and for clients that 
prove their identity with a <Term>certificate</Term> issued by that authority to allow communication.

To generate a certificate, we will use the [OpenSSL](https://www.openssl.org/) tool. It is pre-installed on many
Linux distributions, as well as on newer versions of the MacOS system. On Windows operating systems, you will need
to download and install the tool.

<LanguageSpecific to="javascript">

<Note type="info">
If you need to connect to a server that provides a self-signed certificate from the Node.js application, you need to set 
the variable `NODE_TLS_REJECT_UNAUTHORIZED` to the value `0`. However, this setting will cause Node.JS to log a warning 
message:

```
Setting the NODE_TLS_REJECT_UNAUTHORIZED environment variable to '0' makes TLS connections and HTTPS requests insecure 
by disabling certificate verification.
```

</Note>

</LanguageSpecific>

#### Creating certificate authority

To create <Term>certificate authority</Term> execute following command:

```bash
openssl req -x509 \
        -newkey rsa:2048 \ 
        -keyout rootCA.key \
        -out rootCA.crt \
        -days 365
```

It generates a <Term>certificate</Term> `rootCA.crt` and a <Term>private key</Term> `rootCA.key` for 
the <Term>certificate authority</Term> (after confirmation you have to enter a password).

<Note type="warning">
After running the commands in steps 1 and 2, you must enter a password - if you want an unencrypted certificate without 
a password, specify the `-nodes' parameter in the command.
</Note>

<Note type="question">

<NoteTitle toggles="true">

##### What do the arguments of the command mean?
</NoteTitle>

<Table>
    <Thead>
        <Tr>
            <Th>Argument</Th>
            <Th>Description</Th>
        </Tr>
    </Thead>
    <Tbody>
        <Tr>
            <Td>`-x509`</Td>
            <Td>produces a x509 certificate - according to a standard that defines the format of digital 
                certificates used in SSL/TLS connections</Td>
        </Tr>
        <Tr>
            <Td>`-newkey cipher:bits`</Td>
            <Td>
                determines the used [cipher](https://en.wikipedia.org/wiki/RSA_(cryptosystem)) and its complexity,
                you can list all supported ciphers by command `openssl ciphers`
            </Td>
        </Tr>
        <Tr>
            <Td>`-days`</Td>
            <Td>
                determines the number of days the certificate is valid, when this period expires the certificate
                is considered invalid and the server and client will refuse to work with it
            </Td>
        </Tr>
        <Tr>
            <Td>`-keyout`</Td>
            <Td>
                file name where <Term>private key</Term> is stored
            </Td>
        </Tr>
        <Tr>
            <Td>`-out`</Td>
            <Td>
                file name where <Term>certificate</Term> with public key is stored
            </Td>
        </Tr>
    </Tbody>
</Table>
</Note>

#### Certificate signing request

Now you need to create a text file called `domain.ext` with the following content. You need to replace `[domain]` in DNS 
section with the name of the domain (or [multiple domains](https://easyengine.io/wordpress-nginx/tutorials/ssl/multidomain-ssl-subject-alternative-names/)) 
where your going to run the evitaDB server:

```plain
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
subjectAltName = @alt_names
[alt_names]
DNS.1 = [domain]
```

Then you need to create the certificate signing request `domain.csr` using following command: 

```bash
openssl req -newkey rsa:2048 \
        -keyout domain.key \
        -out domain.csr
```

It generates a <Term>private key</Term> `domain.key` for the client and a request `domain.csr` to be signed by a
<Term>certificate authority</Term> (after confirmation you have to enter a password)

Now you are ready for the final step.

#### Issue signed certificate

Finally, you're ready to generate signed certificate which you can use for evitaDB server or any of the clients in case
the [mTLS](#mutual-tls-for-grpc) is enabled.

Generate new <Term>certificate</Term> signed by the [certificate authority](#creating-certificate-authority) 
represented by the `rootCA.crt` and its <Term>private key</Term> `rootCA.key` using the following command:

```bash
openssl x509 -req -CA rootCA.crt -CAkey rootCA.key \
        -in domain.csr -out domain.crt -days 365 \
        -extfile domain.ext
```

You can repeat this command multiple times to generate different certificates both for server side and for each 
of the clients.

<Note type="warning">
Do not use the same signed certificate for the server and the client! Do not use the same certificate for different 
clients! Each entity in the communication must be represented (identified) by a different certificate.

If you follow these recommendations, you'll be able to control the rejection of each client separately. If you issue a
certificate for a third party client and your contract with them ends, you can easily delete their certificate from the
list of allowed client certificates and effectively stop communicating with their client application.
</Note>

<Note type="info">
Both the server and the client can be provided with:

- certificate in the format `.crt`, `.cer` or `.pem`
- private key in the format `.key` or `.pem`
</Note>

<Note type="question">

<NoteTitle toggles="true">

##### What do the arguments of the command mean?
</NoteTitle>

<Table>
    <Thead>
        <Tr>
            <Th>Argument</Th>
            <Th>Description</Th>
        </Tr>
    </Thead>
    <Tbody>
        <Tr>
            <Td>`-CA`</Td>
            <Td>identifies the public part of the <Term>certificate authority</Term></Td>
        </Tr>
        <Tr>
            <Td>`-CAkey`</Td>
            <Td>identifies the <Term>private key</Term> of the <Term>certificate authority</Term></Td>
        </Tr>
        <Tr>
            <Td>`-in`</Td>
            <Td>identifies the [signing request](#preparing-certificate-signing-request) controlling the operation</Td>
        </Tr>
        <Tr>
            <Td>`-days`</Td>
            <Td>
                determines the number of days the certificate is valid, when this period expires the certificate
                is considered invalid and the server and client will refuse to work with it
            </Td>
        </Tr>
        <Tr>
            <Td>`-extfile`</Td>
            <Td>identifies the file with the domain description</Td>
        </Tr>
        <Tr>
            <Td>`-out`</Td>
            <Td>
                file name where the generated <Term>certificate</Term> with public key is stored
            </Td>
        </Tr>
    </Tbody>
</Table>
</Note>

## Mutual TLS for gRPC

The gRPC API, and thus <SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass>,
also offers the possibility of authentication via [mutual TLS](https://en.wikipedia.org/wiki/Mutual_authentication), in 
which client and server verify their identities with the help of a certificate exchange.

<Note type="question">

<NoteTitle toggles="true">

##### Why the mutual TLS is not supported in GraphQL or REST API?
</NoteTitle>

The gRPC API is used by evitaDB drivers and is expected to be a system API requiring a higher level of security. On the
other hand, GraphQL and REST APIs are usually used by end clients - maybe even directly from browsers or client
applications. From our point of view, these types of APIs are consumer level APIs with different authentication
requirements.

</Note>

The mTLS can be controlled in the configuration file <SourceClass>docker/evita-configuration.yaml</SourceClass> in the
section `api.endpoints.gRPC.mTLS`. At the same place it is possible to configure the list of client certificates that
are allowed to communicate with the gRPC server. The client that doesn't present itself with the accepted certificate
will be rejected by the server.

The client needs to configure path to its <Term>certificate</Term>, <Term>private key</Term> and optionally password to 
a private key in [configuration](../use/connectors/java.md).

We recommend the use of `mTLS` because it prevents a large number of attacks and thus emphasizes the security of
the communication.

Examples of attacks prevented:

- [on-path attack](https://www.wallarm.com/what/what-is-an-on-path-attacker)
- [man in the middle](https://en.wikipedia.org/wiki/Man-in-the-middle_attack)
- [spoofing](https://en.wikipedia.org/wiki/Spoofing_attack)
- [credential stuffing](https://en.wikipedia.org/wiki/Credential_stuffing)
- [brute force attack](https://en.wikipedia.org/wiki/Brute-force_attack)
- [phishing](https://www.cloudflare.com/learning/access-management/phishing-attack/)

### Default mTLS behaviour (not-secure)

The `mTLS` is enabled by default but in a way that is not secure and should be used only in development. When the evitaDB
starts and `generateAndUseSelfSigned` is set to `true` (default), it generates three public/private key pairs:

1. <Term>certificate authority</Term> in `evitaDB-CA-selfSigned.crt` and 
   its <Term>private key</Term> in `evitaDB-CA-selfSigned.key` files 
2. server <Term>certificate</Term> in `server.crt` and its <Term>private key</Term> in `server.key` files
3. client <Term>certificate</Term> in `client.crt` and its <Term>private key</Term> in `client.key` files

The `client.crt` is automatically added to the list of trusted client certificates. Both `client.crt` and `client.key`
are available for downloading using `system` endpoint. You'll see those when the evitaDB server starts:

```
API `system` listening on               http://your-domain:5557/system/
   - server certificate served at:      http://your-domain:5557/system/evitaDB-CA-selfSigned.crt
   - client certificate served at:      http://your-domain:5557/system/client.crt
   - client private key served at:      http://your-domain:5557/system/client.key
```

When the gRPC client starts and has the following settings (all are defaults):

- `useGeneratedCertificate`: `true`
- `mtlsEnabled`: `true`

It automatically downloads the default client certificate along with private key and use it for communication. We are
aware, that this is **not secure** and defies the logic of `mTLS`, but it allows us to test entire process and avoid
problems in test/production environments.

<Note type="warning">

<NoteTitle toggles="true">

##### How we can validate communication integrity?
</NoteTitle>

If you need to verify that the client communicates with the server directly without any 
[man-in-the-middle](https://en.wikipedia.org/wiki/Man-in-the-middle_attack), you may check the fingerprints of 
the used <Term>certificate authority</Term> both on the server side and the client.

**Server side fingerprint**

The fingerprint is written to the console output when the server starts - it looks like this:

```
Root CA Certificate fingerprint: 84:F0:29:87:D8:F5:F6:92:B4:7B:AA:AE:F3:5A:29:A1:C1:86:C4:B2:4D:44:63:6B:2D:F2:AD:75:B7:C6:F2:7E
```

**Client side fingerprint**

Client logs the fingerprint using [configured logging library](run.md#control-logging) on `INFO` level in this form:

```
16:11:18.712 INFO  i.e.d.c.ClientCertificateManager - Server's CA certificate fingerprint: 04:B0:9C:00:FB:32:D8:8A:7A:C9:34:19:5D:90:48:8A:BF:BF:E8:22:32:53:4C:4F:14:E1:EC:FA:C2:99:C3:DD
```
</Note>

### Recommended mTLS usage (secure)

For each of the gRPC client generate their own <Term>certificate</Term> using trusted <Term>certificate authority</Term>
(such as [Let's Encrypt](https://letsencrypt.org)), or your own [self-signed authority](#creating-certificate-authority).
Disable `generateAndUseSelfSigned` and configure server certificate and each of client certificates in 
[configuration](configure.md#tls-configuration).
