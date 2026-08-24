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

package io.evitadb.externalApi.grpc;

import io.evitadb.api.file.FileForFetch;
import io.evitadb.core.Evita;
import io.evitadb.driver.EvitaClient;
import io.evitadb.driver.config.ClientTimeoutOptions;
import io.evitadb.driver.config.ClientTlsOptions;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.HostDefinition;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.server.EvitaServer;
import io.evitadb.spi.export.model.ExportFileHandle;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.CertificateUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import static io.evitadb.test.TestTags.EXPORT;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SLOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the Java driver can download a file bigger than Armeria's default response-length cap.
 *
 * Armeria caps a client response at 10 MiB by default, and the cap counts the **whole** HTTP body -
 * which for a server-streaming call is every message added together, not the largest one. `EvitaClient`
 * never overrode it, so `management().fetchFile(...)` could not fetch a backup larger than that: it
 * failed part-way through with a bare `RESOURCE_EXHAUSTED`, indistinguishable from the unbounded-
 * buffering failure this whole line of work started from. The cap is now lifted on the driver's
 * streaming and CDC channels and kept on the unary one, where a 10 MiB reply genuinely is anomalous.
 *
 * Two existing tests look like they would have caught this and do not, which is why this one exists:
 *
 * - `EvitaClientReadWriteTest#shouldBackupAndRestoreCatalogViaDownloadingAndUploadingFileContents`
 *   downloads through the driver, but backs up a ten-product catalog - about 1 MB, an order of
 *   magnitude under the cap;
 * - `LongRunningGrpcFetchFileBackpressureTest` moves 512 MiB, but through its own stub built with
 *   `maxResponseLength(0)`, so it never exercises the driver's default at all.
 *
 * **Calibration.** {@link #FILE_SIZE} is asserted against {@link #ARMERIA_DEFAULT_MAX_RESPONSE_LENGTH}
 * rather than assumed to exceed it: shrink the file below the cap and this test passes whether the fix
 * is present or not.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Java driver must download files larger than Armeria's default response-length cap")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(MANAGEMENT)
@Tag(EXPORT)
@Tag(SLOW)
public class LongRunningEvitaClientLargeFileDownloadTest {
	private static final String DATA_SET = "GrpcLargeFileDownload";
	/**
	 * Armeria's `ClientOptions.MAX_RESPONSE_LENGTH` default - the ceiling this test exists to cross.
	 */
	private static final long ARMERIA_DEFAULT_MAX_RESPONSE_LENGTH = 10L * 1024L * 1024L;
	/**
	 * Size of the file to download. Comfortably over the cap, small enough to build and move quickly.
	 */
	private static final long FILE_SIZE = 24L * 1024L * 1024L;
	/**
	 * Size of the buffer the file is written and read with.
	 */
	private static final int BUFFER_SIZE = 65_536;

	/**
	 * Creates a file of the requested size and publishes it for fetching.
	 *
	 * @param evita       engine whose export service the file is stored in
	 * @param contentsCrc accumulator the written contents are checksummed into
	 * @return descriptor of the published file
	 */
	@Nonnull
	private static FileForFetch createExportedFile(@Nonnull Evita evita, @Nonnull CRC32 contentsCrc) throws Exception {
		final byte[] pattern = new byte[BUFFER_SIZE];
		for (int i = 0; i < pattern.length; i++) {
			pattern[i] = (byte) (i * 31 + 7);
		}
		final ExportFileHandle handle = evita.management().exportService().storeFile(
			"grpc-large-file-download.bin",
			"Large file used by the driver download test",
			"application/octet-stream",
			"test"
		);
		try {
			final OutputStream outputStream = handle.outputStream();
			long written = 0L;
			while (written < FILE_SIZE) {
				final int toWrite = (int) Math.min(pattern.length, FILE_SIZE - written);
				outputStream.write(pattern, 0, toWrite);
				contentsCrc.update(pattern, 0, toWrite);
				written += toWrite;
			}
		} finally {
			handle.close();
		}
		final FileForFetch fileForFetch = handle.fileForFetchFuture().get(60, TimeUnit.SECONDS);
		assertEquals(FILE_SIZE, fileForFetch.totalSizeInBytes(), "Exported file has an unexpected size.");
		return fileForFetch;
	}

	@DataSet(
		value = DATA_SET, openWebApi = {GrpcProvider.CODE, SystemProvider.CODE},
		readOnly = false, destroyAfterClass = true,
		// the download is gated on transport readiness, which only exists when the request executor is a
		// real pool - see LongRunningGrpcFetchFileBackpressureTest for the full explanation
		useRealThreadPools = true
	)
	static EvitaClient setUp(EvitaServer evitaServer) {
		final ApiOptions apiOptions = evitaServer.getExternalApiServer().getApiOptions();
		final HostDefinition grpcHost = apiOptions.getEndpointConfiguration(GrpcProvider.CODE).getHost()[0];
		final HostDefinition systemHost = apiOptions.getEndpointConfiguration(SystemProvider.CODE).getHost()[0];

		final String serverCertificates = apiOptions.certificate().getFolderPath().toString();
		final int lastDash = serverCertificates.lastIndexOf('-');
		assertTrue(lastDash > 0, "Dash not found! Look at the evita-configuration.yml in test resources!");
		final Path clientCertificates = Path.of(serverCertificates.substring(0, lastDash) + "-client");

		return new EvitaClient(
			EvitaClientConfiguration.builder()
				.host(grpcHost.hostAddress())
				.port(grpcHost.port())
				.systemApiPort(systemHost.port())
				.tls(
					ClientTlsOptions.builder()
						.mtlsEnabled(false)
						.certificateFolderPath(clientCertificates)
						.certificateFileName(Path.of(CertificateUtils.getGeneratedClientCertificateFileName()))
						.certificateKeyFileName(
							Path.of(CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName())
						)
						.build()
				)
				.timeouts(
					ClientTimeoutOptions.builder()
						.timeout(10, TimeUnit.MINUTES)
						.build()
				)
				.build()
		);
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should download a 24 MB file through EvitaClient with its default channel configuration")
	void shouldDownloadFileLargerThanDefaultResponseLengthCap(Evita evita, EvitaClient evitaClient) throws Exception {
		assertTrue(
			FILE_SIZE > ARMERIA_DEFAULT_MAX_RESPONSE_LENGTH,
			"File is not larger than Armeria's default response-length cap, so this test would pass " +
				"with or without the fix it exists to guard."
		);

		final CRC32 expectedCrc = new CRC32();
		final FileForFetch bigFile = createExportedFile(evita, expectedCrc);
		try {
			final CRC32 receivedCrc = new CRC32();
			long receivedBytes = 0L;
			// the driver stages the download into a temp file and hands back a stream over it, so this
			// reads from disk rather than holding 24 MB in memory
			try (final InputStream inputStream = evitaClient.management().fetchFile(bigFile.fileId())) {
				final byte[] buffer = new byte[BUFFER_SIZE];
				int bytesRead;
				while ((bytesRead = inputStream.read(buffer)) != -1) {
					receivedCrc.update(buffer, 0, bytesRead);
					receivedBytes += bytesRead;
				}
			}

			log.info("Downloaded {} B through the driver's default channel configuration.", receivedBytes);
			assertEquals(FILE_SIZE, receivedBytes, "Client did not receive the whole file.");
			assertEquals(
				expectedCrc.getValue(), receivedCrc.getValue(),
				"Downloaded contents differ from the stored file."
			);
		} finally {
			evita.management().deleteFile(bigFile.fileId());
		}
	}

}
