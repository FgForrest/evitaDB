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

package io.evitadb.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test verifies contract of {@link CertificateUtils} class.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("CertificateUtils contract tests")
class CertificateUtilsTest {

	@Nested
	@DisplayName("File name generation tests")
	class FileNameGenerationTests {

		@Test
		@DisplayName("Should generate server certificate file name")
		void shouldGenerateServerCertificateFileName() {
			final String fileName = CertificateUtils.getGeneratedServerCertificateFileName();
			assertEquals("server.crt", fileName);
		}

		@Test
		@DisplayName("Should generate client certificate file name")
		void shouldGenerateClientCertificateFileName() {
			final String fileName = CertificateUtils.getGeneratedClientCertificateFileName();
			assertEquals("client.crt", fileName);
		}

		@Test
		@DisplayName("Should generate server private key file name")
		void shouldGenerateServerPrivateKeyFileName() {
			final String fileName = CertificateUtils.getGeneratedServerCertificatePrivateKeyFileName();
			assertEquals("server.key", fileName);
		}

		@Test
		@DisplayName("Should generate client private key file name")
		void shouldGenerateClientPrivateKeyFileName() {
			final String fileName = CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName();
			assertEquals("client.key", fileName);
		}

		@Test
		@DisplayName("Should return server cert name")
		void shouldReturnServerCertName() {
			final String certName = CertificateUtils.getServerCertName();
			assertEquals("server", certName);
		}

		@Test
		@DisplayName("Should return client cert name")
		void shouldReturnClientCertName() {
			final String certName = CertificateUtils.getClientCertName();
			assertEquals("client", certName);
		}

		@Test
		@DisplayName("Should return certificate extension")
		void shouldReturnCertificateExtension() {
			final String extension = CertificateUtils.getCertificateExtension();
			assertEquals(".crt", extension);
		}

		@Test
		@DisplayName("Should return certificate key extension")
		void shouldReturnCertificateKeyExtension() {
			final String extension = CertificateUtils.getCertificateKeyExtension();
			assertEquals(".key", extension);
		}

		@Test
		@DisplayName("Should have consistent naming pattern")
		void shouldHaveConsistentNamingPattern() {
			final String serverCert = CertificateUtils.getGeneratedServerCertificateFileName();
			final String serverKey = CertificateUtils.getGeneratedServerCertificatePrivateKeyFileName();
			final String clientCert = CertificateUtils.getGeneratedClientCertificateFileName();
			final String clientKey = CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName();

			// All should end with proper extensions
			assertTrue(serverCert.endsWith(CertificateUtils.getCertificateExtension()));
			assertTrue(serverKey.endsWith(CertificateUtils.getCertificateKeyExtension()));
			assertTrue(clientCert.endsWith(CertificateUtils.getCertificateExtension()));
			assertTrue(clientKey.endsWith(CertificateUtils.getCertificateKeyExtension()));

			// All should start with proper names
			assertTrue(serverCert.startsWith(CertificateUtils.getServerCertName()));
			assertTrue(serverKey.startsWith(CertificateUtils.getServerCertName()));
			assertTrue(clientCert.startsWith(CertificateUtils.getClientCertName()));
			assertTrue(clientKey.startsWith(CertificateUtils.getClientCertName()));
		}
	}
}
