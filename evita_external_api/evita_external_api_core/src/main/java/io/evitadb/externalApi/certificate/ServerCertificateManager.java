/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.certificate;

import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.configuration.AbstractApiConfiguration;
import io.evitadb.externalApi.configuration.CertificatePath;
import io.evitadb.externalApi.configuration.CertificateSettings;
import io.evitadb.utils.CertificateUtils;
import lombok.Getter;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.FileWriter;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static java.util.Optional.ofNullable;

/**
 * Main goal of this class is to generate self-signed certificates for testing purposes by usage of BouncyCastle library.
 */
public class ServerCertificateManager {
	/**
	 * Default path to the folder where the certificate related files will be stored.
	 */
	private static final String DEFAULT_SERVER_CERTIFICATE_FOLDER_PATH = System.getProperty("java.io.tmpdir") + File.separator + "evita-server-certificates" + File.separator;
	private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
	private static final String BC_PROVIDER = "BC";
	private static final String KEY_ALGORITHM = "RSA";
	private static final BouncyCastleProvider BOUNCY_CASTLE_PROVIDER = new BouncyCastleProvider();
	/**
	 * Variable that holds the path to the folder where the certificate related files will be stored.
	 */
	@Getter private final Path certificateFolderPath;

	/**
	 * Get path to the default folder where server certificates will be stored.
	 */
	@Nonnull
	public static String getDefaultServerCertificateFolderPath() {
		return DEFAULT_SERVER_CERTIFICATE_FOLDER_PATH;
	}

	/**
	 * Gets the path of the certificate and private key that should be used.
	 *
	 * @param certificateSettings part of {@link AbstractApiConfiguration} that contains information about certificates
	 *                            settings.
	 * @return {@link CertificatePath} object that contains paths to the certificate and private key.
	 */
	@Nonnull
	public static CertificatePath getCertificatePath(@Nonnull CertificateSettings certificateSettings) {
		final Path certPath;
		final Path certPrivateKeyPath;
		final String certPrivateKeyPassword;
		if (certificateSettings.generateAndUseSelfSigned()) {
			certPath = certificateSettings.getFolderPath().resolve(CertificateUtils.getGeneratedServerCertificateFileName());
			certPrivateKeyPath = certificateSettings.getFolderPath().resolve(CertificateUtils.getGeneratedServerCertificatePrivateKeyFileName());
			certPrivateKeyPassword = null;
		} else {
			final CertificatePath certificatePath = certificateSettings.custom();
			if (certificatePath == null || certificatePath.certificate() == null || certificatePath.privateKey() == null) {
				throw new EvitaInternalError("Certificate path is not properly set in the configuration file.");
			}
			certPath = ofNullable(certificatePath.certificate()).map(Path::of).orElse(null);
			certPrivateKeyPath = ofNullable(certificatePath.privateKey()).map(Path::of).orElse(null);
			certPrivateKeyPassword = certificatePath.privateKeyPassword();
		}
		return new CertificatePath(
			certPath.toAbsolutePath().toString(),
			certPrivateKeyPath.toAbsolutePath().toString(),
			certPrivateKeyPassword
		);
	}

	/**
	 * Creates a new instance of {@link ServerCertificateManager} with the default path to the folder where the certificate
	 * related files will be stored.
	 */
	public ServerCertificateManager(@Nullable CertificateSettings certificateSettings) {
		certificateFolderPath = certificateSettings.getFolderPath();
		Security.addProvider(BOUNCY_CASTLE_PROVIDER);
		final File file = this.certificateFolderPath.toFile();
		if (!file.exists()) {
			//noinspection ResultOfMethodCallIgnored
			file.mkdir();
		}
	}

	/**
	 * Get path to the server certificate with the given name and the default extension.
	 */
	@Nonnull
	public Path getCertificatePath(@Nonnull String certName) {
		return certificateFolderPath.resolve(certName + CertificateUtils.getCertificateExtension());
	}

	/**
	 * Get path to the server certificate private key with the given name and the default extension.
	 */
	@Nonnull
	public Path getCertificatePrivateKeyPath(@Nonnull String certName) {
		return certificateFolderPath.resolve(certName + CertificateUtils.getCertificateKeyExtension());
	}

	/**
	 * Get path to the server certificate private key with the default name and the default extension.
	 */
	@Nonnull
	public Path getCertificatePrivateKeyPath() {
		return certificateFolderPath.resolve(CertificateUtils.getServerCertName() + CertificateUtils.getCertificateKeyExtension());
	}

	/**
	 * Get path to the root CA certificate with the default name and the default extension.
	 */
	@Nonnull
	public Path getRootCaCertificatePath() {
		return certificateFolderPath.resolve(CertificateUtils.getGeneratedRootCaCertificateFileName());
	}

	/**
	 * Get path to the root CA certificate private key with the default name and the default extension.
	 */
	@Nonnull
	public Path getRootCaCertificateKeyPath() {
		return certificateFolderPath.resolve(CertificateUtils.getGeneratedRootCaCertificateKeyFileName());
	}

	/**
	 * Generates a self-signed certificate using the BouncyCastle library.
	 */
	public void generateSelfSignedCertificate() throws Exception {
		final KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM, BC_PROVIDER);
		keyPairGenerator.initialize(2048, new SecureRandom());
		final KeyPair keyPair = keyPairGenerator.generateKeyPair();

		final Instant now = Instant.now();
		final Date notBefore = Date.from(now);
		final Date notAfter = Date.from(now.plus(Duration.ofDays(365)));
		final ContentSigner contentSigner = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(keyPair.getPrivate());
		final X500Name x500Name = new X500Name("CN=" + CertificateUtils.getGeneratedRootCaCertificateName());
		final JcaX509ExtensionUtils rootCertExtUtils = new JcaX509ExtensionUtils();
		final X509v3CertificateBuilder certificateBuilder =
			new JcaX509v3CertificateBuilder(x500Name,
				BigInteger.valueOf(now.toEpochMilli()),
				notBefore,
				notAfter,
				x500Name,
				keyPair.getPublic())
				.addExtension(Extension.subjectKeyIdentifier, false, rootCertExtUtils.createSubjectKeyIdentifier(keyPair.getPublic()))
				.addExtension(Extension.authorityKeyIdentifier, false, rootCertExtUtils.createAuthorityKeyIdentifier(keyPair.getPublic()))
				.addExtension(Extension.basicConstraints, true, new BasicConstraints(true))
				.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyCertSign));
		final X509Certificate rootCert = new JcaX509CertificateConverter()
			.setProvider(BC_PROVIDER).getCertificate(certificateBuilder.build(contentSigner));
		rootCert.verify(keyPair.getPublic());
		rootCert.verify(rootCert.getPublicKey());

		try (final JcaPEMWriter pemWriter = new JcaPEMWriter(new FileWriter(getRootCaCertificatePath().toFile()))) {
			pemWriter.writeObject(rootCert);
		}

		try (final PemWriter privateKeyWriter = new PemWriter(new FileWriter(getRootCaCertificateKeyPath().toFile()))) {
			privateKeyWriter.writeObject(new PemObject("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
		}

		// Issue server and client certificates
		issueCertificate(CertificateUtils.getServerCertName(), keyPairGenerator, keyPair, x500Name, notBefore, notAfter, rootCert);
		issueCertificate(CertificateUtils.getClientCertName(), keyPairGenerator, keyPair, x500Name, notBefore, notAfter, rootCert);
	}

	/**
	 * Method that is used to issue a certificate by newly generated certificate authority.
	 *
	 * @param certificateName  name of the certificate to be issued
	 * @param keyPairGenerator key pair generator for getting public and private keys
	 * @param keyPair          key pair of the certificate authority
	 * @param x500Name         x500 name of the certificate authority
	 * @param notBefore        date from which the certificate is valid
	 * @param notAfter         date until which the certificate is valid
	 * @param rootCert         certificate authority certificate
	 */
	private void issueCertificate(
		@Nonnull String certificateName,
		@Nonnull KeyPairGenerator keyPairGenerator,
		@Nonnull KeyPair keyPair,
		@Nonnull X500Name x500Name,
		@Nonnull Date notBefore,
		@Nonnull Date notAfter,
		@Nonnull X509Certificate rootCert
	) throws Exception {
		final X500Name issuedCertSubject = new X500Name("CN=" + certificateName);
		final BigInteger issuedCertSerialNum = new BigInteger(Long.toString(new SecureRandom().nextLong()));
		final KeyPair issuedCertKeyPair = keyPairGenerator.generateKeyPair();

		final PKCS10CertificationRequestBuilder p10Builder = new JcaPKCS10CertificationRequestBuilder(issuedCertSubject, issuedCertKeyPair.getPublic());
		final JcaContentSignerBuilder csrBuilder = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).setProvider(BC_PROVIDER);

		// Sign the new KeyPair with the root cert Private Key
		final ContentSigner csrContentSigner = csrBuilder.build(keyPair.getPrivate());
		final PKCS10CertificationRequest csr = p10Builder.build(csrContentSigner);

		// Use the Signed KeyPair and CSR to generate an issued Certificate
		// Here serial number is randomly generated. In general, CAs use
		// a sequence to generate Serial number and avoid collisions
		final X509v3CertificateBuilder issuedCertBuilder = new X509v3CertificateBuilder(x500Name, issuedCertSerialNum, notBefore, notAfter, csr.getSubject(), csr.getSubjectPublicKeyInfo());

		final JcaX509ExtensionUtils issuedCertExtUtils = new JcaX509ExtensionUtils();

		// Add Extensions
		// Use BasicConstraints to say that this Cert is not a CA
		issuedCertBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));

		// Add Issuer cert identifier as Extension
		issuedCertBuilder.addExtension(Extension.authorityKeyIdentifier, false, issuedCertExtUtils.createAuthorityKeyIdentifier(rootCert));
		issuedCertBuilder.addExtension(Extension.subjectKeyIdentifier, false, issuedCertExtUtils.createSubjectKeyIdentifier(csr.getSubjectPublicKeyInfo()));

		// Add intended key usage extension if needed
		issuedCertBuilder.addExtension(Extension.keyUsage, false, new KeyUsage(KeyUsage.keyEncipherment | KeyUsage.digitalSignature));

		// Add DNS name to the cert to be used for SSL
		issuedCertBuilder.addExtension(Extension.subjectAlternativeName, false, new DERSequence(new ASN1Encodable[]{
			new GeneralName(GeneralName.dNSName, "localhost"),
			new GeneralName(GeneralName.iPAddress, "127.0.0.1")
		}));

		final X509CertificateHolder issuedCertHolder = issuedCertBuilder.build(csrContentSigner);
		final X509Certificate issuedCert = new JcaX509CertificateConverter().setProvider(BC_PROVIDER).getCertificate(issuedCertHolder);

		// Verify the issued cert signature against the root (issuer) cert
		issuedCert.verify(rootCert.getPublicKey(), BC_PROVIDER);

		try (final JcaPEMWriter pemWriterIssued = new JcaPEMWriter(new FileWriter(getCertificatePath(certificateName).toFile()))) {
			pemWriterIssued.writeObject(issuedCert);
		}

		try (final PemWriter privateKeyWriter = new PemWriter(new FileWriter(getCertificatePrivateKeyPath(certificateName).toFile()))) {
			privateKeyWriter.writeObject(new PemObject("PRIVATE KEY", issuedCertKeyPair.getPrivate().getEncoded()));
		}
	}
}