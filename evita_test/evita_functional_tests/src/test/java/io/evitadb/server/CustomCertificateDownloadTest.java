/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.server;

import io.evitadb.core.Evita;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.certificate.ServerCertificateManager;
import io.evitadb.externalApi.certificate.ServerCertificateManager.CertificateType;
import io.evitadb.externalApi.configuration.CertificateOptions;
import io.evitadb.externalApi.configuration.CertificatePath;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.CertificateUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SERVER;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises certificate downloads over a real HTTP socket with custom and generated server certificates.
 * The System API uses HTTP while a separate TLS endpoint ensures certificate routes are registered.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(SERVER)
@Tag(MANAGEMENT)
@org.junit.jupiter.api.DisplayName("Certificate downloads over HTTP")
class CustomCertificateDownloadTest implements EvitaTestSupport {
	private static final String TEST_DIRECTORY = "customCertificateDownloadTest";
	private static final String CUSTOM_FILE_NAME = "server-plain.pem";

	@BeforeEach
	/** Prepares isolated certificate files. */
	void setUp() throws IOException {
		cleanTestSubDirectory(TEST_DIRECTORY);
	}

	@AfterEach
	/** Removes the certificate fixture. */
	void tearDown() throws IOException {
		cleanTestSubDirectory(TEST_DIRECTORY);
	}

	@org.junit.jupiter.api.DisplayName("Downloads the custom certificate")
	@Test
	/** Downloads the configured certificate by its bare filename. */
	void shouldDownloadCustomCertificate() throws Exception {
		assertCertificateDownload(false, CUSTOM_FILE_NAME, false);
	}

	@org.junit.jupiter.api.DisplayName("Downloads the generated certificate")
	@Test
	/** Checks the generated certificate as a positive control. */
	void shouldDownloadGeneratedCertificate() throws Exception {
		assertCertificateDownload(true, CertificateUtils.getGeneratedServerCertificateFileName(), false);
	}

	/** On Unix a backslash is an ordinary filename character, so it stays part of the download route and name. */
	@org.junit.jupiter.api.DisplayName("Downloads a custom certificate whose filename contains a backslash")
	@Test
	void shouldKeepBackslashInCertificateFileNameOnUnix() throws Exception {
		Assumptions.assumeTrue(File.separatorChar == '/', "A backslash separates path segments on this platform");
		assertCertificateDownload(false, "back\\slash.pem", false);
	}

	/** A valid certificate must fail startup rather than silently losing to a fixed system endpoint. */
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = {"status", "server-name", "liveness", "readiness"})
	@org.junit.jupiter.api.DisplayName("Rejects certificate filenames colliding with system routes at startup")
	void shouldRejectCertificateRouteCollision(@Nonnull String fileName) throws Exception {
		assertCertificateDownload(false, fileName, true);
	}

	/** Generated download names are harmless aliases when both registrations serve exactly the same file. */
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.MethodSource("sharedDownloadNames")
	@org.junit.jupiter.api.DisplayName("Allows generated download names serving the same file")
	void shouldShareIdenticalCertificateDownload(@Nonnull String fileName) throws Exception {
		assertCertificateDownload(false, fileName, false);
	}

	/** Supplies the generated names rather than pinning the platform's certificate suffixes in test data. */
	@Nonnull
	private static java.util.stream.Stream<String> sharedDownloadNames() {
		return java.util.stream.Stream.of(
			CertificateUtils.getGeneratedServerCertificateFileName(),
			CertificateUtils.getGeneratedClientCertificateFileName(),
			CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName()
		);
	}

	/**
	 * Boots the real server using the same directory, port allocation and shutdown helpers as EvitaServerTest.
	 * Only the user-facing URL is asserted; the double-slash request is logged as diagnostic evidence.
	 */
	private void assertCertificateDownload(boolean generated, @Nonnull String fileName, boolean conflict)
		throws Exception {
		final Path directory = getTestDirectory().resolve(TEST_DIRECTORY);
		final Path certificates = Files.createDirectories(directory.resolve("certificates"));
		final String generatedFileName = CertificateUtils.getGeneratedServerCertificateFileName();
		final Path certificate = certificates.resolve(fileName);
		if (!generated) {
			final ServerCertificateManager certificateManager = new ServerCertificateManager(
				new CertificateOptions(true, certificates.toString(), new CertificatePath(null, null, null))
			);
			certificateManager.generateSelfSignedCertificate(CertificateType.SERVER);
			if (!certificate.equals(certificates.resolve(generatedFileName))) {
				Files.copy(certificates.resolve(generatedFileName), certificate);
			}
		}

		final int[] ports = getPortManager().allocatePorts(TEST_DIRECTORY, 2);
		final Map<String, String> arguments = new HashMap<>();
		arguments.put("storage.storageDirectory", directory.resolve("storage").toString());
		arguments.put("storage.workDirectory", directory.resolve("work").toString());
		arguments.put("cache.enabled", "false");
		arguments.put("api.requestTimeoutInMillis", "10K");
		arguments.put("api.certificate.folderPath", certificates.toString());
		arguments.put("api.certificate.generateAndUseSelfSigned", Boolean.toString(generated));
		arguments.put("api.certificate.custom.certificate", certificate.toString());
		arguments.put(
			"api.certificate.custom.privateKey",
			certificates.resolve(CertificateUtils.getGeneratedServerCertificatePrivateKeyFileName()).toString()
		);
		ExternalApiServer.gatherExternalApiProviders().forEach(registrar -> {
			final String code = registrar.getExternalApiCode();
			arguments.put("api.endpoints." + code + ".enabled", Boolean.toString(
				SystemProvider.CODE.equals(code) || "rest".equals(code)
			));
		});
		arguments.put("api.endpoints.system.host", "localhost:" + ports[0]);
		arguments.put("api.endpoints.system.exposeOn", "localhost:" + ports[0]);
		arguments.put("api.endpoints.system.tlsMode", "FORCE_NO_TLS");
		arguments.put("api.endpoints.rest.host", "localhost:" + ports[1]);
		arguments.put("api.endpoints.rest.exposeOn", "localhost:" + ports[1]);
		arguments.put("api.endpoints.rest.tlsMode", "FORCE_TLS");

		final EvitaServer server = new EvitaServer(getPathInTargetDirectory(TEST_DIRECTORY), arguments);
		try {
			if (conflict) {
				final GenericEvitaInternalError failure = assertThrows(GenericEvitaInternalError.class, server::run);
				assertEquals(
					"Certificate file `" + certificate + "` conflicts with fixed system route `/" + fileName + "`.",
					failure.getMessage()
				);
				return;
			}
			server.run();
			final SystemProvider provider = server.getExternalApiServer()
				.getExternalApiProviderByCode(SystemProvider.CODE);
			assertNotNull(provider);
			final String baseUrl = provider.getConfiguration().getBaseUrls()[0];
			if (generated) {
				assertEquals(baseUrl + fileName, provider.getKeyEndPoints()
					.get(SystemProvider.SERVER_CERTIFICATE_URL)[0]);
			} else {
				assertNull(provider.getKeyEndPoints().get(SystemProvider.SERVER_CERTIFICATE_URL));
			}
			try (final HttpClient client = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(Duration.ofSeconds(10))
				.build()) {
				// the request target escapes characters a URI cannot carry literally, such as a backslash
				final String requestName = URLEncoder.encode(fileName, StandardCharsets.UTF_8);
				final HttpResponse<byte[]> response = download(client, baseUrl + requestName);
				final HttpResponse<byte[]> doubleSlashResponse = download(client, baseUrl + "/" + requestName);
				System.out.printf(
					"Certificate download (%s): %s -> %d; %s -> %d; Content-Disposition: %s%n",
					generated ? "generated" : "custom",
					response.uri(), response.statusCode(),
					doubleSlashResponse.uri(), doubleSlashResponse.statusCode(),
					response.headers().firstValue("Content-Disposition").orElse("<absent>")
				);
				assertAll(
					() -> assertEquals(200, response.statusCode(), "Certificate download must return HTTP 200"),
					() -> assertArrayEquals(Files.readAllBytes(certificate), response.body()),
					() -> assertEquals(
						"attachment; filename=\"" + fileName + "\"",
						response.headers().firstValue("Content-Disposition").orElse("")
					)
				);
			}
		} finally {
			final Evita evita = server.getEvita();
			final java.util.concurrent.CompletableFuture<Void> stopped = server.stop();
			getPortManager().releasePortsOnCompletion(TEST_DIRECTORY, stopped);
			stopped.join();
			if (evita != null) {
				evita.close();
			}
		}
	}

	/**
	 * Sends a bounded HTTP/1.1 request over the socket without following redirects.
	 */
	@Nonnull
	private static HttpResponse<byte[]> download(@Nonnull HttpClient client, @Nonnull String url)
		throws IOException, InterruptedException {
		return client.send(
			HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
			HttpResponse.BodyHandlers.ofByteArray()
		);
	}
}
