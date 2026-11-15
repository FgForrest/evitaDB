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

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.certificate.ServerCertificateManager;
import io.evitadb.externalApi.certificate.ServerCertificateManager.CertificateType;
import io.evitadb.externalApi.configuration.CertificateOptions;
import io.evitadb.externalApi.grpc.certificate.ClientCertificateManager;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.CertificateUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SERVER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises public certificate downloads and client bootstrapping through a real server in both certificate modes.
 * The System API uses HTTP while a separate REST endpoint requires TLS.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(SERVER)
@Tag(MANAGEMENT)
@DisplayName("Certificate downloads over HTTP")
class CustomCertificateDownloadTest implements EvitaTestSupport {
	private static final String TEST_DIRECTORY = "customCertificateDownloadTest";
	private static final String CUSTOM_FILE_NAME = "server-plain.pem";
	/** Live server closed after each test. */
	private EvitaServer server;
	/** Public certificate configured on the running server. */
	private Path certificate;
	/** Isolated directory containing server and client files. */
	private Path directory;
	/** Allocated System HTTP and REST HTTPS ports, in that order. */
	private int[] ports;

	/** Prepares an isolated directory for each server and client. */
	@BeforeEach
	void setUp() throws IOException {
		cleanTestSubDirectory(TEST_DIRECTORY);
		this.directory = getTestDirectory().resolve(TEST_DIRECTORY);
	}

	/** Closes the server and engine before removing their files. */
	@AfterEach
	void tearDown() throws Exception {
		if (this.server != null) {
			final Evita evita = this.server.getEvita();
			final CompletableFuture<Void> stopped = this.server.stop();
			getPortManager().releasePortsOnCompletion(TEST_DIRECTORY, stopped);
			stopped.get(30, TimeUnit.SECONDS);
			if (evita != null) {
				evita.close();
			}
		}
		cleanTestSubDirectory(TEST_DIRECTORY);
	}

	/** The advertised public certificate URL is independent of certificate mode and the configured filename. */
	@Test
	@DisplayName("Advertises the custom public certificate at the fixed URL")
	void shouldAdvertiseCustomCertificate() throws Exception {
		startServer(false, false);
		assertArrayEquals(
			certificateUrls("server.crt"),
			provider().getKeyEndPoints().get(SystemProvider.SERVER_CERTIFICATE_URL)
		);
	}

	/** Only the fixed public route serves the configured certificate, with its exact PEM bytes. */
	@Test
	@DisplayName("Downloads the custom certificate only at server.crt")
	void shouldDownloadCustomCertificate() throws Exception {
		startServer(false, false);
		try (final HttpClient client = httpClient()) {
			final HttpResponse<byte[]> response = download(client, baseUrl() + "server.crt");
			assertAll(
				() -> assertEquals(200, response.statusCode()),
				() -> assertArrayEquals(Files.readAllBytes(this.certificate), response.body()),
				() -> assertEquals("attachment; filename=\"server.crt\"",
					response.headers().firstValue("Content-Disposition").orElse("")),
				() -> assertEquals(404, download(client, baseUrl() + CUSTOM_FILE_NAME).statusCode()),
				() -> assertEquals(404, download(client, baseUrl() + "server.key").statusCode())
			);
			Files.write(this.certificate, new byte[]{'\n'}, StandardOpenOption.APPEND);
			assertArrayEquals(Files.readAllBytes(this.certificate),
				download(client, baseUrl() + "server.crt").body());
		}
	}

	/** Real neighboring client files must stay private even when the custom server enables mTLS. */
	@Test
	@DisplayName("Does not distribute neighboring client credentials in custom mode")
	void shouldNotDownloadCustomClientFiles() throws Exception {
		startServer(false, true);
		try (final HttpClient client = httpClient()) {
			assertAll(
				() -> assertEquals(404, download(client, baseUrl() + "client.crt").statusCode()),
				() -> assertEquals(404, download(client, baseUrl() + "client.key").statusCode()),
				() -> assertNull(provider().getKeyEndPoints().get(SystemProvider.CLIENT_CERTIFICATE_URL)),
				() -> assertNull(provider().getKeyEndPoints().get(SystemProvider.CLIENT_PRIVATE_KEY_URL))
			);
		}
	}

	/** Generated mode continues to serve all three downloads and advertise the mTLS credentials. */
	@Test
	@DisplayName("Serves and advertises generated certificates and client key")
	void shouldDownloadGeneratedCertificates() throws Exception {
		startServer(true, true);
		try (final HttpClient client = httpClient()) {
			final Map<String, String> downloads = Map.of(
				SystemProvider.SERVER_CERTIFICATE_URL, "server.crt",
				SystemProvider.CLIENT_CERTIFICATE_URL, "client.crt",
				SystemProvider.CLIENT_PRIVATE_KEY_URL, "client.key"
			);
			for (final Map.Entry<String, String> entry : downloads.entrySet()) {
				assertArrayEquals(certificateUrls(entry.getValue()),
					provider().getKeyEndPoints().get(entry.getKey()));
				final HttpResponse<byte[]> response = download(client, baseUrl() + entry.getValue());
				assertEquals(200, response.statusCode());
				assertArrayEquals(
					Files.readAllBytes(this.certificate.resolveSibling(entry.getValue())), response.body()
				);
			}
			assertEquals(404, download(client, baseUrl() + "server.key").statusCode());
		}
	}

	/** The normal auto-download client trusts the custom certificate and completes an actual TLS request. */
	@Test
	@DisplayName("Bootstraps a TLS client from the custom server certificate")
	void shouldDownloadCustomCertificateInClientAndConnect() throws Exception {
		startServer(false, false);
		final ClientCertificateManager manager = clientBuilder(false).build();
		assertArrayEquals(Files.readAllBytes(this.certificate),
			Files.readAllBytes(manager.getUsedRootCaCertificatePath()));
		try (final ClientFactory factory = manager.buildClientSslContext(null, ClientFactory.builder()).build()) {
			final WebClient client = WebClient.builder("https://localhost:" + this.ports[1])
				.factory(factory).responseTimeout(Duration.ofSeconds(10)).build();
			assertEquals(200, client.get("/rest/system").aggregate().get(15, TimeUnit.SECONDS).status().code());
		}
	}

	/** A client requesting generated mTLS credentials receives an actionable error from a custom-mode server. */
	@Test
	@DisplayName("Explains that a custom server does not distribute client credentials")
	void shouldRejectClientCertificateDownloadInCustomMode() throws Exception {
		startServer(false, true);
		final EvitaInvalidUsageException failure = assertThrows(
			EvitaInvalidUsageException.class, () -> clientBuilder(true).build()
		);
		assertAll(
			() -> assertEquals("The server does not distribute client certificates. " +
				"Configure a client certificate and private key and set useGeneratedCertificate to false.",
				failure.getMessage()),
			() -> assertInstanceOf(FileNotFoundException.class, failure.getCause()),
			() -> assertEquals(baseUrl() + "client.crt", failure.getCause().getMessage())
		);
	}

	/** Boots the real server with valid client credentials next to the custom public certificate. */
	private void startServer(boolean generated, boolean mtls) throws Exception {
		final Path certificates = Files.createDirectories(this.directory.resolve("certificates"));
		final String generatedFileName = CertificateUtils.getGeneratedServerCertificateFileName();
		this.certificate = certificates.resolve(generated ? generatedFileName : CUSTOM_FILE_NAME);
		if (!generated) {
			new ServerCertificateManager(new CertificateOptions(true, certificates.toString(), null))
				.generateSelfSignedCertificate(CertificateType.SERVER, CertificateType.CLIENT);
			Files.copy(certificates.resolve(generatedFileName), this.certificate);
			Files.delete(certificates.resolve(generatedFileName));
		}
		this.ports = getPortManager().allocatePorts(TEST_DIRECTORY, 2);
		final Map<String, String> arguments = new HashMap<>();
		arguments.put("storage.storageDirectory", this.directory.resolve("storage").toString());
		arguments.put("storage.workDirectory", this.directory.resolve("work").toString());
		arguments.put("cache.enabled", "false");
		arguments.put("api.requestTimeoutInMillis", "10K");
		arguments.put("api.certificate.folderPath", certificates + File.separator);
		arguments.put("api.certificate.generateAndUseSelfSigned", Boolean.toString(generated));
		arguments.put("api.certificate.custom.certificate", this.certificate.toString());
		arguments.put("api.certificate.custom.privateKey",
			certificates.resolve(CertificateUtils.getGeneratedServerCertificatePrivateKeyFileName()).toString());
		ExternalApiServer.gatherExternalApiProviders().forEach(registrar -> {
			final String code = registrar.getExternalApiCode();
			arguments.put("api.endpoints." + code + ".enabled", Boolean.toString(
				SystemProvider.CODE.equals(code) || "rest".equals(code)
			));
		});
		arguments.put("api.endpoints.system.host", "localhost:" + this.ports[0]);
		arguments.put("api.endpoints.system.exposeOn", "localhost:" + this.ports[0]);
		arguments.put("api.endpoints.system.tlsMode", "FORCE_NO_TLS");
		arguments.put("api.endpoints.rest.host", "localhost:" + this.ports[1]);
		arguments.put("api.endpoints.rest.exposeOn", "localhost:" + this.ports[1]);
		arguments.put("api.endpoints.rest.tlsMode", "FORCE_TLS");
		arguments.put("api.endpoints.rest.mTLS.enabled", Boolean.toString(mtls));
		arguments.put("api.endpoints.rest.mTLS.allowedClientCertificatesPaths",
			"[\"client.crt\"]");
		this.server = new EvitaServer(getPathInTargetDirectory(TEST_DIRECTORY), arguments);
		this.server.run();
		assertTrue(Files.exists(certificates.resolve("client.crt")));
		assertTrue(Files.exists(certificates.resolve("client.key")));
	}

	/** Configures the real certificate manager with an empty per-test client cache. */
	@Nonnull
	private ClientCertificateManager.Builder clientBuilder(boolean mtls) {
		return new ClientCertificateManager.Builder()
			.certificateClientFolderPath(this.directory.resolve("client"))
			.useGeneratedCertificate(true, "localhost", this.ports[0]).mtls(mtls);
	}

	/** Returns the live System API provider. */
	@Nonnull
	private SystemProvider provider() {
		return this.server.getExternalApiServer().getExternalApiProviderByCode(SystemProvider.CODE);
	}

	/** Returns a fixed certificate route on every advertised host address. */
	@Nonnull
	private String[] certificateUrls(@Nonnull String fileName) {
		return Arrays.stream(provider().getConfiguration().getBaseUrls())
			.map(base -> base + fileName).toArray(String[]::new);
	}

	/** Returns the advertised base URL of the real HTTP System API. */
	@Nonnull
	private String baseUrl() {
		return provider().getConfiguration().getBaseUrls()[0];
	}

	/** Builds a bounded HTTP client without redirects. */
	@Nonnull
	private static HttpClient httpClient() {
		return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(10)).build();
	}

	/** Sends a bounded request over the real HTTP socket. */
	@Nonnull
	private static HttpResponse<byte[]> download(@Nonnull HttpClient client, @Nonnull String url)
		throws IOException, InterruptedException {
		return client.send(
			HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
			HttpResponse.BodyHandlers.ofByteArray()
		);
	}
}
