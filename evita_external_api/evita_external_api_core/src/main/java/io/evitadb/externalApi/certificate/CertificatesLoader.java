/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
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

	public LoadedCertificates reinitialize() {
		final String certificateFile = certificatePath.certificate();
		if (certificateFile == null) {
			throw new EvitaInvalidUsageException("Certificate file path is not set.");
		}
		log.info("Reinitializing certificates.");
		try {
			final TlsKeyPair tlsKeyPair = TlsKeyPair.of(
				loadPrivateKey(certificatePath),
				loadCertificate(certificateFactory, certificateFile)
			);

			final List<X509Certificate> trustedCertificates = new ArrayList<>(clientAllowedCertificates.size());
			final String certDirectoryPath = apiOptions.certificate().folderPath();
			for (String clientCert : clientAllowedCertificates) {
				final X509Certificate whitelistedClientCertificate = loadCertificate(certificateFactory, certDirectoryPath + clientCert);
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

	/**
	 * Reads certificate private key.
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

	@Nonnull
	private static X509Certificate loadCertificate(
		@Nonnull CertificateFactory certificateFactory,
		@Nonnull String certificatePath
	) throws IOException, CertificateException, NoSuchAlgorithmException {
		try (InputStream inputStream = new FileInputStream(certificatePath)) {
			return  (X509Certificate) certificateFactory.generateCertificate(inputStream);
		}
	}
}
