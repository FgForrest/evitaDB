/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.certificate;

import com.linecorp.armeria.common.TlsKeyPair;
import io.evitadb.externalApi.configuration.AbstractApiOptions;
import io.evitadb.externalApi.configuration.CertificateOptions;
import io.evitadb.externalApi.configuration.CertificatePath;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CertificateUtils;
import lombok.Getter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Main goal of this class is to generate self-signed certificates for testing purposes by usage of BouncyCastle library.
 */
public class ServerCertificateManager {
	/**
	 * Default path to the folder where the certificate related files will be stored.
	 */
	private static final String DEFAULT_SERVER_CERTIFICATE_FOLDER_PATH = System.getProperty("java.io.tmpdir") + File.separator + "evita-server-certificates" + File.separator;
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
	 * @param certificateSettings part of {@link AbstractApiOptions} that contains information about certificates
	 *                            settings.
	 * @return {@link CertificatePath} object that contains paths to the certificate and private key.
	 */
	@Nonnull
	public static Optional<CertificatePath> getCertificatePath(@Nonnull CertificateOptions certificateSettings) {
		final Path certPath;
		final Path certPrivateKeyPath;
		final String certPrivateKeyPassword;
		final CertificatePath customPaths = certificateSettings.custom();
		if (certificateSettings.generateAndUseSelfSigned()) {
			certPath = certificateSettings.getFolderPath().resolve(CertificateUtils.getGeneratedServerCertificateFileName());
			certPrivateKeyPath = certificateSettings.getFolderPath().resolve(CertificateUtils.getGeneratedServerCertificatePrivateKeyFileName());
			certPrivateKeyPassword = null;
		} else if (customPaths != null && customPaths.certificate() != null && customPaths.privateKey() != null) {
			certPath = ofNullable(customPaths.certificate()).map(Path::of).orElse(null);
			certPrivateKeyPath = ofNullable(customPaths.privateKey()).map(Path::of).orElse(null);
			certPrivateKeyPassword = customPaths.privateKeyPassword();
		} else {
			return empty();
		}
		return of(
			new CertificatePath(
				ofNullable(certPath.toAbsolutePath())
					.map(it -> it.toAbsolutePath().toString())
					.orElse(null),
				ofNullable(certPrivateKeyPath)
					.map(it -> it.toAbsolutePath().toString())
					.orElse(null),
				certPrivateKeyPassword
			)
		);
	}

	/**
	 * Creates a new instance of {@link ServerCertificateManager} with the default path to the folder where the certificate
	 * related files will be stored.
	 */
	public ServerCertificateManager(@Nonnull CertificateOptions certificateSettings) {
		this.certificateFolderPath = certificateSettings.getFolderPath();
		final File file = this.certificateFolderPath.toFile();
		if (!file.exists()) {
			Assert.isTrue(file.mkdir(), "Failed to create directory: " + this.certificateFolderPath);
		}
	}

	/**
	 * Get path to the server certificate with the given name and the default extension.
	 */
	@Nonnull
	public Path getCertificatePath(@Nonnull String certName) {
		return this.certificateFolderPath.resolve(certName + CertificateUtils.getCertificateExtension());
	}

	/**
	 * Get path to the server certificate private key with the given name and the default extension.
	 */
	@Nonnull
	public Path getCertificatePrivateKeyPath(@Nonnull String certName) {
		return this.certificateFolderPath.resolve(certName + CertificateUtils.getCertificateKeyExtension());
	}

	/**
	 * Generates a self-signed certificate using the BouncyCastle library embedded inside Armeria library.
	 *
	 * @param type An array of {@link CertificateType} indicating the types of certificates to generate.
	 *             If the array contains {@link CertificateType#SERVER}, a server certificate will be generated.
	 *             If the array contains {@link CertificateType#CLIENT}, a client certificate will be generated.
	 * @throws Exception If there is an error during the generation of the self-signed certificates.
	 */
	public void generateSelfSignedCertificate(@Nonnull CertificateType... type) throws Exception {
		if (ArrayUtils.isEmpty(type)) {
			return;
		}

		// Issue server and client certificates
		if (Arrays.stream(type).anyMatch(it -> it == CertificateType.SERVER)) {
			final TlsKeyPair serverKeyPair = TlsKeyPair.ofSelfSigned();
			writeCertificateToFile(serverKeyPair, CertificateUtils.getServerCertName());
		}

		if (Arrays.stream(type).anyMatch(it -> it == CertificateType.CLIENT)) {
			final TlsKeyPair serverKeyPair = TlsKeyPair.ofSelfSigned();
			writeCertificateToFile(serverKeyPair, CertificateUtils.getClientCertName());
		}
	}

	/**
	 * Writes the certificate and private key of a given {@link TlsKeyPair} to separate files using the specified certificate name.
	 *
	 * @param tlsKeyPair The {@link TlsKeyPair} object containing the certificate chain and private key to be written to files.
	 * @param certificateName The name used to generate the file paths for the certificate and private key.
	 * @throws Exception If an error occurs while writing the certificate or private key to file.
	 */
	private void writeCertificateToFile(@Nonnull TlsKeyPair tlsKeyPair, @Nonnull String certificateName) throws Exception {
		try (final JcaPEMWriter pemWriterIssued = new JcaPEMWriter(new FileWriter(getCertificatePath(certificateName).toFile()))) {
			pemWriterIssued.writeObject(tlsKeyPair.certificateChain().get(0));
		}

		try (final PemWriter privateKeyWriter = new PemWriter(new FileWriter(getCertificatePrivateKeyPath(certificateName).toFile()))) {
			privateKeyWriter.writeObject(new PemObject("PRIVATE KEY", tlsKeyPair.privateKey().getEncoded()));
		}
	}

	/**
	 * The type of the certificate to generate.
	 */
	public enum CertificateType {
		/**
		 * Server certificate.
		 */
		SERVER,
		/**
		 * Client certificate.
		 */
		CLIENT
	}

}
