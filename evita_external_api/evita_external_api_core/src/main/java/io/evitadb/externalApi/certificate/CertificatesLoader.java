/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.CertificatePath;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CertificateUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.io.pem.PemReader;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * A class the loads and sets up certificates for the server.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
@Slf4j
public class CertificatesLoader {
	private final ApiOptions apiOptions;
	private final List<String> clientAllowedCertificates;
	private final CertificateFactory certificateFactory;
	private final CertificatePath certificatePath;

	static {
		java.security.Security.addProvider(
			new org.bouncycastle.jce.provider.BouncyCastleProvider()
		);
	}

	/**
	 * Loads a private key from the specified certificate path.
	 *
	 * @param certificatePath the path to the certificate files needed to load the private key, must not be null
	 * @return the loaded PrivateKey instance
	 * @throws IllegalArgumentException   if the specified private key file does not exist
	 * @throws EvitaInvalidUsageException if there is an error while loading the stored certificate
	 */
	@Nonnull
	private static PrivateKey loadPrivateKey(@Nonnull CertificatePath certificatePath) {
		final PrivateKey privateKey;
		final File certificatePrivateKey = Path.of(Objects.requireNonNull(certificatePath.privateKey())).toFile();
		Assert.isTrue(certificatePrivateKey.exists(), () -> "Certificate private key file `" + certificatePath.privateKey() + "` doesn't exists!");
		try (PemReader privateKeyReader = new PemReader(new FileReader(certificatePrivateKey))) {
			final PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKeyReader.readPemObject().getContent());
			final KeyFactory keyFactory = KeyFactory.getInstance("RSA");
			privateKey = keyFactory.generatePrivate(keySpec);
		} catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
			throw new EvitaInvalidUsageException(
				"Error while loading stored certificates. Check the server configuration file: " + e.getMessage(),
				"Error while loading stored certificates. Check the server configuration file.",
				e
			);
		}
		return privateKey;
	}

	/**
	 * Loads an X.509 certificate from the specified file path using the provided CertificateFactory.
	 *
	 * @param certificateFactory the CertificateFactory used to generate the X.509 certificate.
	 * @param certificatePath    the file path of the certificate to be loaded.
	 * @return the loaded X509Certificate.
	 * @throws IOException              if an I/O error occurs while reading the certificate from the specified path.
	 * @throws CertificateException     if there is a parsing error or another problem with the certificate data.
	 * @throws NoSuchAlgorithmException if no Provider supports a CertificateFactorySpi implementation for the specified type (X.509).
	 */
	@Nonnull
	private static X509Certificate[] loadCertificateChain(
		@Nonnull CertificateFactory certificateFactory,
		@Nonnull String certificatePath
	) throws IOException, CertificateException, NoSuchAlgorithmException {
		try (InputStream inputStream = new FileInputStream(certificatePath)) {
			 final Collection<? extends Certificate> certificateChain = certificateFactory.generateCertificates(inputStream);
			 return certificateChain.stream().map(X509Certificate.class::cast).toArray(X509Certificate[]::new);
		}
	}

	/**
	 * Reinitializes the loaded certificates by reloading them from the specified path and updating internal structures.
	 * This method checks for the presence of a certificate file and attempts to load both the private key and
	 * certificate from it. It also processes a list of client certificates to be added to the list of trusted certificates.
	 *
	 * The reinitialization involves:
	 * - Ensuring the certificate file path is set.
	 * - Loading the private key and certificate to create a TlsKeyPair.
	 * - Loading additional trusted client certificates and updating their fingerprints.
	 *
	 * If any errors occur during the loading of private keys or certificates, a GenericEvitaInternalError is thrown.
	 *
	 * @return LoadedCertificates containing the TLS key pair and list of trusted certificates used for secure communications
	 * @throws EvitaInvalidUsageException if the certificate file path is not set
	 * @throws GenericEvitaInternalError  if an IO, CertificateException, or NoSuchAlgorithmException occurs during loading
	 */
	@Nonnull
	public LoadedCertificates reinitialize() {
		final String certificateFile = this.certificatePath.certificate();
		if (certificateFile == null) {
			throw new EvitaInvalidUsageException("Certificate file path is not set.");
		}
		try {
			final TlsKeyPair tlsKeyPair = TlsKeyPair.of(
				loadPrivateKey(this.certificatePath),
				loadCertificateChain(this.certificateFactory, certificateFile)
			);

			final List<X509Certificate> trustedCertificates = new ArrayList<>(this.clientAllowedCertificates.size());
			final String certDirectoryPath = this.apiOptions.certificate().folderPath();
			for (String clientCert : this.clientAllowedCertificates) {
				final X509Certificate[] whitelistedClientCertificateChain = loadCertificateChain(this.certificateFactory, certDirectoryPath + clientCert);
				final X509Certificate whitelistedClientCertificate = whitelistedClientCertificateChain[0];
				log.info("Whitelisted client's certificate fingerprint: {}", CertificateUtils.getCertificateFingerprint(whitelistedClientCertificate));
				trustedCertificates.add(
					whitelistedClientCertificate
				);
			}
			return new LoadedCertificates(tlsKeyPair, trustedCertificates);
		} catch (IOException | CertificateException | NoSuchAlgorithmException e) {
			throw new GenericEvitaInternalError("An error occurred while loading configured certificates.", e);
		}
	}
}
