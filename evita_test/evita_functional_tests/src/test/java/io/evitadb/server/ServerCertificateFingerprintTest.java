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
import io.evitadb.externalApi.certificate.ServerCertificateManager;
import io.evitadb.externalApi.certificate.ServerCertificateManager.CertificateType;
import io.evitadb.externalApi.configuration.CertificateOptions;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.CertificateUtils;
import io.evitadb.utils.ConsoleWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.evitadb.test.TestTags.SECURITY;
import static io.evitadb.test.TestTags.SERVER;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the server certificate fingerprint printed at start-up, which is what a client downloading the public
 * certificate over plain HTTP verifies it against.
 *
 * Isolation is required because the fingerprint is printed through {@link ConsoleWriter}, whose only sink is the
 * process-wide standard output that this test swaps for a capture.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Server certificate fingerprint at start-up")
@Tag(SERVER)
@Tag(SECURITY)
@Isolated
class ServerCertificateFingerprintTest implements EvitaTestSupport {
	private static final String TEST_DIRECTORY = "serverCertificateFingerprintTest";
	/** Deliberately not the generated certificate name, so only the configured custom path can be fingerprinted. */
	private static final String CUSTOM_FILE_NAME = "server-plain.pem";
	/** Live server closed after the test. */
	private EvitaServer server;
	/** Isolated directory containing the server files. */
	private Path directory;

	/** Prepares an isolated directory for the server. */
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

	/** A custom certificate is served for download too, so its fingerprint must be printed like a generated one. */
	@Test
	@DisplayName("Prints the fingerprint of a custom server certificate")
	void shouldPrintCustomCertificateFingerprintAtStartUp() throws Exception {
		final Path certificates = Files.createDirectories(this.directory.resolve("certificates"));
		new ServerCertificateManager(new CertificateOptions(true, certificates.toString(), null))
			.generateSelfSignedCertificate(CertificateType.SERVER);
		final Path certificate = certificates.resolve(CUSTOM_FILE_NAME);
		Files.move(certificates.resolve(CertificateUtils.getGeneratedServerCertificateFileName()), certificate);
		final String expectedFingerprint;
		try (final InputStream in = Files.newInputStream(certificate)) {
			expectedFingerprint = CertificateUtils.getCertificateFingerprint(
				CertificateFactory.getInstance("X.509").generateCertificate(in)
			);
		}

		final String console = captureConsole(() -> startCustomModeServer(certificates, certificate));

		assertTrue(console.contains("Server certificate fingerprint: "), "The fingerprint line must be printed");
		assertTrue(console.contains(expectedFingerprint), "The printed fingerprint must be the custom certificate's");
	}

	/** Boots a real server in custom certificate mode whose only endpoint requires TLS. */
	private void startCustomModeServer(@Nonnull Path certificates, @Nonnull Path certificate) {
		final int port = getPortManager().allocatePorts(TEST_DIRECTORY, 1)[0];
		final Map<String, String> arguments = createHashMap(16);
		arguments.put("storage.storageDirectory", this.directory.resolve("storage").toString());
		arguments.put("storage.workDirectory", this.directory.resolve("work").toString());
		arguments.put("cache.enabled", "false");
		arguments.put("api.requestTimeoutInMillis", "10K");
		arguments.put("api.certificate.folderPath", certificates + File.separator);
		arguments.put("api.certificate.generateAndUseSelfSigned", "false");
		arguments.put("api.certificate.custom.certificate", certificate.toString());
		arguments.put("api.certificate.custom.privateKey",
			certificates.resolve(CertificateUtils.getGeneratedServerCertificatePrivateKeyFileName()).toString());
		ExternalApiServer.gatherExternalApiProviders().forEach(registrar -> arguments.put(
			"api.endpoints." + registrar.getExternalApiCode() + ".enabled",
			Boolean.toString("rest".equals(registrar.getExternalApiCode()))
		));
		arguments.put("api.endpoints.rest.host", "localhost:" + port);
		arguments.put("api.endpoints.rest.exposeOn", "localhost:" + port);
		arguments.put("api.endpoints.rest.tlsMode", "FORCE_TLS");
		this.server = new EvitaServer(getPathInTargetDirectory(TEST_DIRECTORY), arguments);
		this.server.run();
	}

	/**
	 * Runs the action with the standard output redirected into a buffer and returns what it printed.
	 *
	 * @param action the action whose console output is captured
	 * @return the captured console output
	 */
	@Nonnull
	private static String captureConsole(@Nonnull Runnable action) {
		final PrintStream originalOut = System.out;
		final ByteArrayOutputStream capture = new ByteArrayOutputStream(16_384);
		System.setOut(new PrintStream(capture, true, StandardCharsets.UTF_8));
		// quiet mode is a JVM-wide flag another test may have left set, and it would suppress the output under test
		ConsoleWriter.setQuiet(false);
		try {
			action.run();
		} finally {
			System.setOut(originalOut);
		}
		return capture.toString(StandardCharsets.UTF_8);
	}
}
