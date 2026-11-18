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

package io.evitadb.store.catalog.task;

import io.evitadb.api.exception.ExportFileTooLargeException;
import io.evitadb.export.file.ExportFileService;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.spi.export.model.ExportFileHandle;
import io.evitadb.spi.export.model.SizeCappedOutputStream;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.evitadb.test.TestTags.EXPORT;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises lazy catalog measurement through the shared factory and real local export handles.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(EXPORT)
@DisplayName("Backup size cap factory")
class BackupExportCapTest extends BackupTaskExportFixture {
	/** Gives the real handle a small payload allowance without changing its lifecycle. */
	@BeforeEach
	void configureAllowance() {
		this.exports.close();
		this.exports = new ExportFileService(
			FileSystemExportOptions.builder().directory(this.paths.work().resolve("failed-exports"))
				.sizeLimitBytes(1024).build(), this.scheduler
		);
	}

	/** Catalog size is diagnostic and must never prevent the export refusal itself. */
	@Nested
	@DisplayName("Catalog size measurement")
	class CatalogSizeMeasurement {
		@Test
		@DisplayName("should measure the catalog only when the allowance is crossed")
		void shouldMeasureCatalogWhenAllowanceIsCrossed() throws IOException {
			final Path directory = Files.createDirectory(paths.work().resolve("measured-catalog"));
			final Path payload = Files.write(directory.resolve("payload"), new byte[17]);
			final ExportFileHandle handle = exports.storeFile("lazy.zip", null, "application/zip", null);
			try (final SizeCappedOutputStream cap = BackupExportCap.forBackup(handle, TEST_CATALOG, directory)) {
				Files.write(payload, new byte[37]);

				final ExportFileTooLargeException failure = assertThrows(
					ExportFileTooLargeException.class,
					() -> cap.write(new byte[Math.toIntExact(handle.sizeLimit().maxFileSizeInBytes() + 1)])
				);
				assertEquals(new ExportFileTooLargeException(
					TEST_CATALOG, 1024, "export.fileSystem.sizeLimitBytes", 37L
				).getPublicMessage(), failure.getPublicMessage());
			} finally {
				handle.discard();
			}
		}

		@Test
		@DisplayName("should omit an unavailable measurement and remember the refusal")
		void shouldOmitUnavailableMeasurementAndRememberRefusal() throws IOException {
			final Path inaccessibleDirectory;
			try (final FileSystem fileSystem = FileSystems.newFileSystem(
				paths.work().resolve("closed-catalog.zip"), Map.of("create", "true")
			)) {
				inaccessibleDirectory = fileSystem.getPath("/");
			}
			final ExportFileHandle handle = exports.storeFile("unavailable.zip", null, "application/zip", null);
			try (final SizeCappedOutputStream cap = BackupExportCap.forBackup(
				handle, TEST_CATALOG, inaccessibleDirectory
			)) {
				final ExportFileTooLargeException failure = assertThrows(
					ExportFileTooLargeException.class,
					() -> cap.write(new byte[Math.toIntExact(handle.sizeLimit().maxFileSizeInBytes() + 1)])
				);
				assertEquals(new ExportFileTooLargeException(
					TEST_CATALOG, 1024, "export.fileSystem.sizeLimitBytes", null
				).getPublicMessage(), failure.getPublicMessage());
				assertSame(failure, assertThrows(ExportFileTooLargeException.class, cap::checkFailure));
			} finally {
				handle.discard();
			}
		}
	}
}
