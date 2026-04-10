/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.driver.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClientTlsOptions} record and its builder.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("ClientTlsOptions")
class ClientTlsOptionsTest {

	@Test
	@DisplayName("should initialize all defaults via builder")
	void shouldInitDefaults() {
		final ClientTlsOptions options = ClientTlsOptions.builder().build();

		assertTrue(options.tlsEnabled());
		assertFalse(options.mtlsEnabled());
		assertTrue(options.useGeneratedCertificate());
		assertFalse(options.trustCertificate());
		assertNull(options.serverCertificatePath());
		assertNull(options.certificateFileName());
		assertNull(options.certificateKeyFileName());
		assertNull(options.certificateKeyPassword());
		assertNotNull(options.certificateFolderPath());
		assertEquals(ClientTlsOptions.DEFAULT_TRUST_STORE_PASSWORD, options.trustStorePassword());
	}

	@Test
	@DisplayName("should initialize all defaults via no-arg constructor")
	void shouldInitDefaultsViaNoArgConstructor() {
		final ClientTlsOptions options = new ClientTlsOptions();

		assertTrue(options.tlsEnabled());
		assertFalse(options.mtlsEnabled());
		assertTrue(options.useGeneratedCertificate());
		assertFalse(options.trustCertificate());
		assertNull(options.serverCertificatePath());
		assertNull(options.certificateFileName());
		assertNull(options.certificateKeyFileName());
		assertNull(options.certificateKeyPassword());
		assertNotNull(options.certificateFolderPath());
		assertEquals(ClientTlsOptions.DEFAULT_TRUST_STORE_PASSWORD, options.trustStorePassword());
	}

	@Nested
	@DisplayName("Boolean defaults")
	class BooleanDefaultsTest {

		@Test
		@DisplayName("should enable TLS by default")
		void shouldEnableTlsByDefault() {
			final ClientTlsOptions options = ClientTlsOptions.builder().build();
			assertTrue(options.tlsEnabled());
		}

		@Test
		@DisplayName("should disable mTLS by default")
		void shouldDisableMtlsByDefault() {
			final ClientTlsOptions options = ClientTlsOptions.builder().build();
			assertFalse(options.mtlsEnabled());
		}

		@Test
		@DisplayName("should enable generated certificate by default")
		void shouldEnableGeneratedCertificateByDefault() {
			final ClientTlsOptions options = ClientTlsOptions.builder().build();
			assertTrue(options.useGeneratedCertificate());
		}

		@Test
		@DisplayName("should disable trust certificate by default")
		void shouldDisableTrustCertificateByDefault() {
			final ClientTlsOptions options = ClientTlsOptions.builder().build();
			assertFalse(options.trustCertificate());
		}
	}

	@Nested
	@DisplayName("Nullable path handling")
	class NullablePathHandlingTest {

		@Test
		@DisplayName("should have null server certificate path by default")
		void shouldHaveNullServerCertificatePathByDefault() {
			final ClientTlsOptions options = ClientTlsOptions.builder().build();
			assertNull(options.serverCertificatePath());
		}

		@Test
		@DisplayName("should have null certificate file name by default")
		void shouldHaveNullCertificateFileNameByDefault() {
			final ClientTlsOptions options = ClientTlsOptions.builder().build();
			assertNull(options.certificateFileName());
		}

		@Test
		@DisplayName("should have non-null certificate folder path by default")
		void shouldHaveNonNullCertificateFolderPathByDefault() {
			final ClientTlsOptions options = ClientTlsOptions.builder().build();
			assertNotNull(options.certificateFolderPath());
		}
	}

	@Nested
	@DisplayName("Builder setters")
	class BuilderSettersTest {

		@Test
		@DisplayName("should disable TLS via builder")
		void shouldDisableTls() {
			final ClientTlsOptions options = ClientTlsOptions.builder().tlsEnabled(false).build();
			assertFalse(options.tlsEnabled());
		}

		@Test
		@DisplayName("should enable mTLS via builder")
		void shouldEnableMtls() {
			final ClientTlsOptions options = ClientTlsOptions.builder().mtlsEnabled(true).build();
			assertTrue(options.mtlsEnabled());
		}

		@Test
		@DisplayName("should set server certificate path via builder")
		void shouldSetServerCertificatePath() {
			final ClientTlsOptions options =
				ClientTlsOptions.builder()
					.serverCertificatePath(Path.of("/certs/server.crt"))
					.build();

			assertEquals(Path.of("/certs/server.crt"), options.serverCertificatePath());
		}

		@Test
		@DisplayName("should set certificate file names via builder")
		void shouldSetCertificateFileNames() {
			final ClientTlsOptions options =
				ClientTlsOptions.builder()
					.certificateFileName(Path.of("client.crt"))
					.certificateKeyFileName(Path.of("client.key"))
					.certificateKeyPassword("secret")
					.build();

			assertEquals(Path.of("client.crt"), options.certificateFileName());
			assertEquals(Path.of("client.key"), options.certificateKeyFileName());
			assertEquals("secret", options.certificateKeyPassword());
		}

		@Test
		@DisplayName("should set trust store password via builder")
		void shouldSetTrustStorePassword() {
			final ClientTlsOptions options =
				ClientTlsOptions.builder()
					.trustStorePassword("myPassword")
					.build();

			assertEquals("myPassword", options.trustStorePassword());
		}

		@Test
		@DisplayName("should disable generated certificate via builder")
		void shouldSetUseGeneratedCertificateViaBuilder() {
			final ClientTlsOptions options =
				ClientTlsOptions.builder()
					.useGeneratedCertificate(false)
					.build();

			assertFalse(options.useGeneratedCertificate());
		}

		@Test
		@DisplayName("should enable trust certificate via builder")
		void shouldSetTrustCertificateViaBuilder() {
			final ClientTlsOptions options = ClientTlsOptions.builder().trustCertificate(true).build();
			assertTrue(options.trustCertificate());
		}

		@Test
		@DisplayName("should set certificate folder path via builder")
		void shouldSetCertificateFolderPathViaBuilder() {
			final ClientTlsOptions options =
				ClientTlsOptions.builder()
					.certificateFolderPath(Path.of("/custom"))
					.build();

			assertEquals(Path.of("/custom"), options.certificateFolderPath());
		}
	}

	@Nested
	@DisplayName("Builder copy constructor")
	class BuilderCopyTest {

		@Test
		@DisplayName("should copy all fields from source")
		void shouldCopyAllFieldsFromSource() {
			final ClientTlsOptions source =
				ClientTlsOptions.builder()
					.tlsEnabled(false)
					.mtlsEnabled(true)
					.useGeneratedCertificate(false)
					.trustCertificate(true)
					.serverCertificatePath(Path.of("/certs/server.crt"))
					.certificateFileName(Path.of("client.crt"))
					.certificateKeyFileName(Path.of("client.key"))
					.certificateKeyPassword("pass")
					.certificateFolderPath(Path.of("/my/certs"))
					.trustStorePassword("myTrustPass")
					.build();

			final ClientTlsOptions copy = ClientTlsOptions.builder(source).build();

			assertFalse(copy.tlsEnabled());
			assertTrue(copy.mtlsEnabled());
			assertFalse(copy.useGeneratedCertificate());
			assertTrue(copy.trustCertificate());
			assertEquals(Path.of("/certs/server.crt"), copy.serverCertificatePath());
			assertEquals(Path.of("client.crt"), copy.certificateFileName());
			assertEquals(Path.of("client.key"), copy.certificateKeyFileName());
			assertEquals("pass", copy.certificateKeyPassword());
			assertEquals(Path.of("/my/certs"), copy.certificateFolderPath());
			assertEquals("myTrustPass", copy.trustStorePassword());
		}

		@Test
		@DisplayName("should allow overriding single field in copy")
		void shouldAllowOverridingSingleFieldInCopy() {
			final ClientTlsOptions source =
				ClientTlsOptions.builder()
					.tlsEnabled(false)
					.mtlsEnabled(true)
					.useGeneratedCertificate(false)
					.trustCertificate(true)
					.serverCertificatePath(Path.of("/certs/server.crt"))
					.trustStorePassword("originalPass")
					.build();

			final ClientTlsOptions copy =
				ClientTlsOptions.builder(source)
					.trustStorePassword("overridden")
					.build();

			// overridden field
			assertEquals("overridden", copy.trustStorePassword());
			// all other fields preserved
			assertFalse(copy.tlsEnabled());
			assertTrue(copy.mtlsEnabled());
			assertFalse(copy.useGeneratedCertificate());
			assertTrue(copy.trustCertificate());
			assertEquals(Path.of("/certs/server.crt"), copy.serverCertificatePath());
		}
	}

	@Nested
	@DisplayName("Deprecated methods")
	class DeprecatedMethodsTest {

		@SuppressWarnings("deprecation")
		@Test
		@DisplayName("should delegate rootCaCertificatePath to serverCertificatePath")
		void shouldDelegateRootCaCertificatePath() {
			final ClientTlsOptions options =
				ClientTlsOptions.builder()
					.serverCertificatePath(Path.of("/certs/ca.crt"))
					.build();

			assertEquals(options.serverCertificatePath(), options.rootCaCertificatePath());
		}

		@SuppressWarnings("deprecation")
		@Test
		@DisplayName("should set server certificate path via deprecated builder method")
		void shouldSetServerCertificatePathViaDeprecatedBuilderMethod() {
			final Path certPath = Path.of("/certs/root-ca.crt");

			final ClientTlsOptions options =
				ClientTlsOptions.builder()
					.rootCaCertificatePath(certPath)
					.build();

			assertEquals(certPath, options.serverCertificatePath());
		}
	}
}
