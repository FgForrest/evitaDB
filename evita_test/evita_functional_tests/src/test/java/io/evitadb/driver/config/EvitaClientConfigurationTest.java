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

import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.dataType.data.ReflectionCachingBehaviour;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EvitaClientConfiguration} record and its builder.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("EvitaClientConfiguration")
class EvitaClientConfigurationTest {

	@Test
	@DisplayName("should initialize all defaults via builder")
	void shouldInitDefaults() {
		final EvitaClientConfiguration config = EvitaClientConfiguration.builder().build();

		assertNotNull(config.connection());
		assertNotNull(config.tls());
		assertNotNull(config.timeouts());
		assertNotNull(config.threadPool());
		assertEquals(ReflectionCachingBehaviour.CACHE, config.reflectionLookupBehaviour());
		assertNull(config.openTelemetryInstance());
		assertFalse(config.retry());
		assertEquals(100, config.trackedTaskLimit());
		assertTrue(config.changeCaptureQueueSize() > 0);
	}

	@Nested
	@DisplayName("New grouped builder style")
	class GroupedBuilderStyleTest {

		@Test
		@DisplayName("should build with connection options group")
		void shouldBuildWithConnectionGroup() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.connection(
						ClientConnectionOptions.builder()
							.host("myhost")
							.port(9999)
							.build()
					)
					.build();

			assertEquals("myhost", config.connection().host());
			assertEquals(9999, config.connection().port());
		}

		@Test
		@DisplayName("should build with TLS options group")
		void shouldBuildWithTlsGroup() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.tls(ClientTlsOptions.builder().tlsEnabled(false).build())
					.build();

			assertFalse(config.tls().tlsEnabled());
		}

		@Test
		@DisplayName("should build with timeout options group")
		void shouldBuildWithTimeoutGroup() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.timeouts(ClientTimeoutOptions.builder().timeout(30, TimeUnit.SECONDS).build())
					.build();

			assertEquals(30, config.timeouts().timeout());
			assertEquals(TimeUnit.SECONDS, config.timeouts().timeoutUnit());
		}

		@Test
		@DisplayName("should build with all groups configured")
		void shouldBuildWithAllGroupsConfigured() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.connection(
						ClientConnectionOptions.builder()
							.host("server.example.com")
							.port(1234)
							.systemApiPort(5678)
							.clientId("test-client")
							.build()
					)
					.tls(
						ClientTlsOptions.builder()
							.tlsEnabled(true)
							.mtlsEnabled(true)
							.useGeneratedCertificate(false)
							.certificateFileName(Path.of("client.crt"))
							.certificateKeyFileName(Path.of("client.key"))
							.build()
					)
					.timeouts(
						ClientTimeoutOptions.builder()
							.timeout(10, TimeUnit.SECONDS)
							.streamingTimeout(5, TimeUnit.MINUTES)
							.build()
					)
					.retry(true)
					.trackedTaskLimit(50)
					.build();

			assertEquals("server.example.com", config.connection().host());
			assertEquals(1234, config.connection().port());
			assertEquals(5678, config.connection().systemApiPort());
			assertEquals("test-client", config.connection().clientId());
			assertTrue(config.tls().tlsEnabled());
			assertTrue(config.tls().mtlsEnabled());
			assertFalse(config.tls().useGeneratedCertificate());
			assertEquals(10, config.timeouts().timeout());
			assertEquals(5, config.timeouts().streamingTimeout());
			assertEquals(TimeUnit.MINUTES, config.timeouts().streamingTimeoutUnit());
			assertTrue(config.retry());
			assertEquals(50, config.trackedTaskLimit());
		}
	}

	@Nested
	@DisplayName("OpenTelemetry configuration")
	class OpenTelemetryConfigurationTest {

		@Test
		@DisplayName("should return null OpenTelemetry by default")
		void shouldReturnNullOpenTelemetryByDefault() {
			final EvitaClientConfiguration config = EvitaClientConfiguration.builder().build();
			assertNull(config.openTelemetryInstance());
		}

		@Test
		@DisplayName("should set OpenTelemetry instance via builder")
		void shouldSetOpenTelemetryInstanceViaBuilder() {
			final Object otelInstance = new Object();
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.openTelemetryInstance(otelInstance)
					.build();

			assertNotNull(config.openTelemetryInstance());
			assertSame(otelInstance, config.openTelemetryInstance());
		}

		@Test
		@DisplayName("should preserve OpenTelemetry in copy constructor")
		void shouldPreserveOpenTelemetryInCopyConstructor() {
			final Object otelInstance = new Object();
			final EvitaClientConfiguration source =
				EvitaClientConfiguration.builder()
					.openTelemetryInstance(otelInstance)
					.build();

			final EvitaClientConfiguration copy = EvitaClientConfiguration.builder(source).build();

			assertSame(otelInstance, copy.openTelemetryInstance());
		}
	}

	@Nested
	@DisplayName("Top-level builder setters")
	class TopLevelBuilderSettersTest {

		@Test
		@DisplayName("should set change capture queue size via builder")
		void shouldSetChangeCaptureQueueSizeViaBuilder() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.changeCaptureQueueSize(500)
					.build();

			assertEquals(500, config.changeCaptureQueueSize());
		}

		@Test
		@DisplayName("should set tracked task limit via builder")
		void shouldSetTrackedTaskLimitViaBuilder() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.trackedTaskLimit(200)
					.build();

			assertEquals(200, config.trackedTaskLimit());
		}

		@Test
		@DisplayName("should set thread pool options via builder")
		void shouldSetThreadPoolViaBuilder() {
			final ThreadPoolOptions customPool =
				ThreadPoolOptions.clientThreadPoolBuilder()
					.minThreadCount(4)
					.build();

			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.threadPool(customPool)
					.build();

			assertEquals(4, config.threadPool().minThreadCount());
		}

		@Test
		@DisplayName("should set retry flag via builder")
		void shouldSetRetryViaBuilder() {
			final EvitaClientConfiguration config = EvitaClientConfiguration.builder().retry(true).build();
			assertTrue(config.retry());
		}

		@Test
		@DisplayName("should set reflection lookup behaviour via builder")
		void shouldSetReflectionCachingBehaviourViaBuilder() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.reflectionCachingBehaviour(ReflectionCachingBehaviour.NO_CACHE)
					.build();

			assertEquals(ReflectionCachingBehaviour.NO_CACHE, config.reflectionLookupBehaviour());
		}
	}

	@Nested
	@DisplayName("Connection builder setters")
	class ConnectionBuilderSettersTest {

		@Test
		@DisplayName("should build with flat connection style for convenience")
		void shouldBuildWithFlatConnectionStyle() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.host("localhost")
					.port(5555)
					.systemApiPort(5555)
					.build();

			assertEquals("localhost", config.connection().host());
			assertEquals(5555, config.connection().port());
			assertEquals(5555, config.connection().systemApiPort());
		}

		@Test
		@DisplayName("should set client ID via builder")
		void shouldSetClientIdViaBuilder() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.clientId("custom-client")
					.build();

			assertEquals("custom-client", config.connection().clientId());
		}
	}

	@Nested
	@DisplayName("Deprecated flat builder style")
	class DeprecatedFlatBuilderStyleTest {

		@SuppressWarnings("deprecation")
		@Test
		@DisplayName("should build with old flat style for backward compatibility")
		void shouldBuildWithOldFlatStyle() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.host("localhost")
					.port(5555)
					.systemApiPort(5555)
					.useGeneratedCertificate(false)
					.mtlsEnabled(false)
					.timeout(10, TimeUnit.SECONDS)
					.build();

			assertEquals("localhost", config.connection().host());
			assertEquals(5555, config.connection().port());
			assertEquals(5555, config.connection().systemApiPort());
			assertFalse(config.tls().useGeneratedCertificate());
			assertFalse(config.tls().mtlsEnabled());
			assertEquals(10, config.timeouts().timeout());
			assertEquals(TimeUnit.SECONDS, config.timeouts().timeoutUnit());
		}

		@SuppressWarnings("deprecation")
		@Test
		@DisplayName("should set TLS options via deprecated builder")
		void shouldSetTlsOptionsViaDeprecatedBuilder() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.tlsEnabled(false)
					.mtlsEnabled(true)
					.serverCertificatePath(Path.of("/certs/server.crt"))
					.certificateFileName(Path.of("client.crt"))
					.certificateKeyFileName(Path.of("client.key"))
					.certificateKeyPassword("secret")
					.certificateFolderPath(Path.of("/my/certs"))
					.trustStorePassword("myTrustPass")
					.rootCaCertificatePath(Path.of("/certs/root-ca.crt"))
					.useGeneratedCertificate(false)
					.trustCertificate(true)
					.build();

			assertFalse(config.tls().tlsEnabled());
			assertTrue(config.tls().mtlsEnabled());
			// rootCaCertificatePath overwrites serverCertificatePath
			assertEquals(Path.of("/certs/root-ca.crt"), config.tls().serverCertificatePath());
			assertEquals(Path.of("client.crt"), config.tls().certificateFileName());
			assertEquals(Path.of("client.key"), config.tls().certificateKeyFileName());
			assertEquals("secret", config.tls().certificateKeyPassword());
			assertEquals(Path.of("/my/certs"), config.tls().certificateFolderPath());
			assertEquals("myTrustPass", config.tls().trustStorePassword());
			assertFalse(config.tls().useGeneratedCertificate());
			assertTrue(config.tls().trustCertificate());
		}

		@SuppressWarnings("deprecation")
		@Test
		@DisplayName("should set streaming timeout via deprecated builder")
		void shouldSetStreamingTimeoutViaDeprecatedBuilder() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.streamingTimeout(30, TimeUnit.MINUTES)
					.build();

			assertEquals(30, config.timeouts().streamingTimeout());
			assertEquals(TimeUnit.MINUTES, config.timeouts().streamingTimeoutUnit());
		}
	}

	@Nested
	@DisplayName("Connection accessor delegates")
	class ConnectionAccessorDelegatesTest {

		@Test
		@DisplayName("should delegate host() to connection().host()")
		void shouldDelegateHostToConnectionHost() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.connection(ClientConnectionOptions.builder().host("myhost").build())
					.build();

			assertEquals(config.connection().host(), config.host());
		}

		@Test
		@DisplayName("should delegate port() to connection().port()")
		void shouldDelegatePortToConnectionPort() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.connection(ClientConnectionOptions.builder().port(1234).build())
					.build();

			assertEquals(config.connection().port(), config.port());
		}

		@Test
		@DisplayName("should delegate clientId() to connection().clientId()")
		void shouldDelegateClientIdToConnectionClientId() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.connection(ClientConnectionOptions.builder().clientId("myClient").build())
					.build();

			assertEquals(config.connection().clientId(), config.clientId());
		}

		@Test
		@DisplayName("should delegate systemApiPort() to connection().systemApiPort()")
		void shouldDelegateSystemApiPortToConnectionSystemApiPort() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.connection(ClientConnectionOptions.builder().systemApiPort(7777).build())
					.build();

			assertEquals(config.connection().systemApiPort(), config.systemApiPort());
		}
	}

	@Nested
	@DisplayName("Deprecated accessor delegates")
	class DeprecatedAccessorDelegatesTest {

		@SuppressWarnings("deprecation")
		@Test
		@DisplayName("should delegate deprecated tlsEnabled() to tls().tlsEnabled()")
		void shouldDelegateTlsEnabledAccessor() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.tls(ClientTlsOptions.builder().tlsEnabled(false).build())
					.build();

			assertEquals(config.tls().tlsEnabled(), config.tlsEnabled());
		}

		@SuppressWarnings("deprecation")
		@Test
		@DisplayName("should delegate deprecated timeout() to timeouts().timeout()")
		void shouldDelegateTimeoutAccessor() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.timeouts(ClientTimeoutOptions.builder().timeout(42, TimeUnit.MINUTES).build())
					.build();

			assertEquals(config.timeouts().timeout(), config.timeout());
			assertEquals(config.timeouts().timeoutUnit(), config.timeoutUnit());
		}

		@SuppressWarnings("deprecation")
		@Test
		@DisplayName("should delegate deprecated streamingTimeout() to timeouts().streamingTimeout()")
		void shouldDelegateStreamingTimeoutAccessor() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.timeouts(
						ClientTimeoutOptions.builder()
							.streamingTimeout(120, TimeUnit.SECONDS)
							.build()
					)
					.build();

			assertEquals(config.timeouts().streamingTimeout(), config.streamingTimeout());
			assertEquals(config.timeouts().streamingTimeoutUnit(), config.streamingTimeoutUnit());
		}

		@SuppressWarnings("deprecation")
		@Test
		@DisplayName("should delegate deprecated mtlsEnabled() to tls().mtlsEnabled()")
		void shouldDelegateMtlsEnabledAccessor() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.tls(ClientTlsOptions.builder().mtlsEnabled(true).build())
					.build();

			assertEquals(config.tls().mtlsEnabled(), config.mtlsEnabled());
		}

		@SuppressWarnings("deprecation")
		@Test
		@DisplayName("should delegate deprecated useGeneratedCertificate() to tls().useGeneratedCertificate()")
		void shouldDelegateUseGeneratedCertificateAccessor() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.tls(ClientTlsOptions.builder().useGeneratedCertificate(false).build())
					.build();

			assertEquals(config.tls().useGeneratedCertificate(), config.useGeneratedCertificate());
		}

		@SuppressWarnings("deprecation")
		@Test
		@DisplayName("should delegate deprecated trustCertificate() to tls().trustCertificate()")
		void shouldDelegateTrustCertificateAccessor() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.tls(ClientTlsOptions.builder().trustCertificate(true).build())
					.build();

			assertEquals(config.tls().trustCertificate(), config.trustCertificate());
		}

		@SuppressWarnings("deprecation")
		@Test
		@DisplayName("should delegate deprecated serverCertificatePath() to tls().serverCertificatePath()")
		void shouldDelegateServerCertificatePathAccessor() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.tls(ClientTlsOptions.builder().serverCertificatePath(Path.of("/certs/s.crt")).build())
					.build();

			assertEquals(config.tls().serverCertificatePath(), config.serverCertificatePath());
		}

		@SuppressWarnings("deprecation")
		@Test
		@DisplayName("should delegate deprecated rootCaCertificatePath() to tls().serverCertificatePath()")
		void shouldDelegateRootCaCertificatePathAccessor() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.tls(ClientTlsOptions.builder().serverCertificatePath(Path.of("/certs/ca.crt")).build())
					.build();

			assertEquals(config.tls().serverCertificatePath(), config.rootCaCertificatePath());
		}

		@SuppressWarnings("deprecation")
		@Test
		@DisplayName("should delegate deprecated certificateFileName() to tls().certificateFileName()")
		void shouldDelegateCertificateFileNameAccessor() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.tls(ClientTlsOptions.builder().certificateFileName(Path.of("client.crt")).build())
					.build();

			assertEquals(config.tls().certificateFileName(), config.certificateFileName());
		}

		@SuppressWarnings("deprecation")
		@Test
		@DisplayName("should delegate deprecated certificateKeyFileName() to tls().certificateKeyFileName()")
		void shouldDelegateCertificateKeyFileNameAccessor() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.tls(ClientTlsOptions.builder().certificateKeyFileName(Path.of("client.key")).build())
					.build();

			assertEquals(config.tls().certificateKeyFileName(), config.certificateKeyFileName());
		}

		@SuppressWarnings("deprecation")
		@Test
		@DisplayName("should delegate deprecated certificateKeyPassword() to tls().certificateKeyPassword()")
		void shouldDelegateCertificateKeyPasswordAccessor() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.tls(ClientTlsOptions.builder().certificateKeyPassword("secret").build())
					.build();

			assertEquals(config.tls().certificateKeyPassword(), config.certificateKeyPassword());
		}

		@SuppressWarnings("deprecation")
		@Test
		@DisplayName("should delegate deprecated certificateFolderPath() to tls().certificateFolderPath()")
		void shouldDelegateCertificateFolderPathAccessor() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.tls(ClientTlsOptions.builder().certificateFolderPath(Path.of("/my/certs")).build())
					.build();

			assertEquals(config.tls().certificateFolderPath(), config.certificateFolderPath());
		}

		@SuppressWarnings("deprecation")
		@Test
		@DisplayName("should delegate deprecated trustStorePassword() to tls().trustStorePassword()")
		void shouldDelegateTrustStorePasswordAccessor() {
			final EvitaClientConfiguration config =
				EvitaClientConfiguration.builder()
					.tls(ClientTlsOptions.builder().trustStorePassword("myPass").build())
					.build();

			assertEquals(config.tls().trustStorePassword(), config.trustStorePassword());
		}
	}

	@Nested
	@DisplayName("Builder copy constructor")
	class BuilderCopyTest {

		@Test
		@DisplayName("should copy all fields from source")
		void shouldCopyAllFieldsFromSource() {
			final EvitaClientConfiguration source =
				EvitaClientConfiguration.builder()
					.connection(
						ClientConnectionOptions.builder()
							.host("original-host")
							.port(1111)
							.build()
					)
					.tls(ClientTlsOptions.builder().tlsEnabled(false).build())
					.timeouts(ClientTimeoutOptions.builder().timeout(30, TimeUnit.SECONDS).build())
					.retry(true)
					.trackedTaskLimit(50)
					.threadPool(
						ThreadPoolOptions.clientThreadPoolBuilder()
							.minThreadCount(2)
							.build()
					)
					.build();

			final EvitaClientConfiguration copy = EvitaClientConfiguration.builder(source).build();

			assertEquals("original-host", copy.connection().host());
			assertEquals(1111, copy.connection().port());
			assertFalse(copy.tls().tlsEnabled());
			assertEquals(30, copy.timeouts().timeout());
			assertEquals(TimeUnit.SECONDS, copy.timeouts().timeoutUnit());
			assertTrue(copy.retry());
			assertEquals(50, copy.trackedTaskLimit());
			assertEquals(2, copy.threadPool().minThreadCount());
		}

		@Test
		@DisplayName("should allow overriding single group in copy")
		void shouldAllowOverridingSingleGroupInCopy() {
			final EvitaClientConfiguration source =
				EvitaClientConfiguration.builder()
					.connection(
						ClientConnectionOptions.builder()
							.host("original")
							.port(1234)
							.build()
					)
					.retry(true)
					.build();

			final EvitaClientConfiguration modified =
				EvitaClientConfiguration.builder(source)
					.connection(
						ClientConnectionOptions.builder()
							.host("modified")
							.port(5678)
							.build()
					)
					.build();

			assertEquals("modified", modified.connection().host());
			assertEquals(5678, modified.connection().port());
			// retry should remain unchanged
			assertTrue(modified.retry());
		}

		@Test
		@DisplayName("should preserve all top-level fields in copy")
		void shouldPreserveAllTopLevelFieldsInCopy() {
			final Object otelInstance = new Object();
			final EvitaClientConfiguration source =
				EvitaClientConfiguration.builder()
					.openTelemetryInstance(otelInstance)
					.changeCaptureQueueSize(999)
					.reflectionCachingBehaviour(ReflectionCachingBehaviour.NO_CACHE)
					.trackedTaskLimit(77)
					.retry(true)
					.build();

			final EvitaClientConfiguration copy = EvitaClientConfiguration.builder(source).build();

			assertSame(otelInstance, copy.openTelemetryInstance());
			assertEquals(999, copy.changeCaptureQueueSize());
			assertEquals(ReflectionCachingBehaviour.NO_CACHE, copy.reflectionLookupBehaviour());
			assertEquals(77, copy.trackedTaskLimit());
			assertTrue(copy.retry());
		}
	}
}
