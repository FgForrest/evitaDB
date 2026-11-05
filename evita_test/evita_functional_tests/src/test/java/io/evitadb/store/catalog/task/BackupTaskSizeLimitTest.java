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
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.task.ServerTask;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.export.file.ExportFileService;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.spi.export.model.ExportFileHandle;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.StringUtils;
import org.junit.jupiter.api.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real backup tasks must report an actionable refusal and leave only exports that survive retention.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(TASK)
@Tag(EXPORT)
@DisplayName("Backup export storage size limits")
class BackupTaskSizeLimitTest extends BackupTaskExportFixture {
	/** Observes the real handle's payload allowance during boundary calibration. */
	private ExportFileHandle lastHandle;
	/** Fixes descriptor filename bytes only while calibrating the finalization boundary. */
	private boolean stableFileName;

	@Nested
	@DisplayName("Point-in-time backup")
	class PointInTime {
		@Test
		@DisplayName("should restore the refusal replaced by Kryo during record copying")
		void shouldReportMidRecordCopyRefusal() throws IOException {
			configureLimit(66000);
			final ExportFileTooLargeException failure = assertRefused(false, 66000);
			assertTrue(Stream.of(failure.getSuppressed()).anyMatch(
				it -> it.getClass().getSimpleName().equals("KryoException")
			), "the cap must trip in record copying where Kryo replaces the refusal");
		}

		@Test
		@DisplayName("should refuse when the central directory crosses the allowance")
		void shouldRefuseDuringFinalization() throws IOException {
			assertFinalizationRefused(false);
		}

		@Test
		@DisplayName("should retain a backup that fits with its own sidecar after purging")
		void shouldKeepAnAdmittedBackup() throws IOException {
			assertAdmittedAndKept(false);
		}

		@Test
		@DisplayName("should retain an older export after refusing a backup and purging")
		void shouldKeepOlderExportAfterRefusal() throws IOException {
			assertOlderExportSurvives(false);
		}
	}

	@Nested
	@DisplayName("Full backup")
	class Full {
		@Test
		@DisplayName("should refuse while copying a data file")
		void shouldReportDataFileCopyRefusal() throws IOException {
			configureLimit(20000);
			final ExportFileTooLargeException failure = assertRefused(true, 20000);
			assertTrue(Stream.of(failure.getStackTrace()).anyMatch(
				it -> it.getClassName().equals(FullBackupTask.class.getName()) && it.getMethodName().contains("backup")
			), "the cap must trip inside data file copying");
		}

		@Test
		@DisplayName("should refuse when the central directory crosses the allowance")
		void shouldRefuseDuringFinalization() throws IOException {
			assertFinalizationRefused(true);
		}

		@Test
		@DisplayName("should retain a backup that fits with its own sidecar after purging")
		void shouldKeepAnAdmittedBackup() throws IOException {
			assertAdmittedAndKept(true);
		}

		@Test
		@DisplayName("should retain an older export after refusing a backup and purging")
		void shouldKeepOlderExportAfterRefusal() throws IOException {
			assertOlderExportSurvives(true);
		}
	}

	/** Reopens the real service on the same directory with a per-test configured limit. */
	private void configureLimit(long limit) {
		this.exports.close();
		this.exports = new ExportFileService(
			FileSystemExportOptions.builder().directory(this.paths.work().resolve("failed-exports"))
				.sizeLimitBytes(limit).build(), this.scheduler
		) {
			@Nonnull
			@Override
			public ExportFileHandleLocal storeFile(
				@Nonnull String name, @Nullable String description,
				@Nonnull String contentType, @Nullable String origin
			) {
				final ExportFileHandleLocal handle = super.storeFile(
					BackupTaskSizeLimitTest.this.stableFileName ? "boundary.zip" : name,
					description, contentType, origin
				);
				BackupTaskSizeLimitTest.this.lastHandle = handle;
				return handle;
			}
		};
	}

	/** Creates a fresh task because execution releases its catalog holds. */
	@Nonnull
	private ServerTask<?, FileForFetch> task(boolean full) throws IOException {
		return full ? new FullBackupTask(TEST_CATALOG, this.exports, this.persistence, null) :
			new BackupTask(TEST_CATALOG, null, null, false, bootstrap(), this.exports, this.persistence, null);
	}

	/** Checks the public task status as well as the completed future and physical export cleanup. */
	@Nonnull
	private ExportFileTooLargeException assertRefused(boolean full, long limit) throws IOException {
		final long onDisk = FileUtils.getDirectorySize(this.persistence.getCatalogStoragePath());
		final ServerTask<?, FileForFetch> task = task(full);
		task.transitionToIssued();
		assertThrows(RuntimeException.class, task::execute);
		return assertRefused(task, limit, onDisk);
	}

	/** Verifies an already-executed refusal, including its public status and physical cleanup. */
	@Nonnull
	private ExportFileTooLargeException assertRefused(
		@Nonnull ServerTask<?, FileForFetch> task, long limit, long onDisk
	) throws IOException {
		assertEquals(TaskSimplifiedState.FAILED, task.getStatus().simplifiedState());
		final String expected = expectedMessage(limit, onDisk);
		assertEquals(expected, task.getStatus().publicExceptionMessage());
		final CompletionException raised = assertThrows(
			CompletionException.class, () -> task.getFutureResult().join()
		);
		final ExportFileTooLargeException failure = assertInstanceOf(
			ExportFileTooLargeException.class, raised.getCause()
		);
		assertEquals(expected, failure.getPublicMessage());
		try (final Stream<Path> files = Files.list(this.paths.work().resolve("failed-exports"))) {
			assertEquals(0, files.filter(it -> it.toString().endsWith(".zip") ||
				it.toString().endsWith(FileForFetch.METADATA_EXTENSION)).count());
		}
		assertEquals(0, this.exports.listFilesToFetch(1, 10, Set.of()).getTotalRecordCount());
		return failure;
	}

	/** Uses independent literal wording so the operator-facing contract is checked byte for byte. */
	@Nonnull
	private static String expectedMessage(long limit, long onDisk) {
		return "The backup of catalog `" + TEST_CATALOG + "` was stopped because it exceeded the export storage " +
			"limit of " + StringUtils.formatByteSize(limit) + "; the server would delete a larger file. " +
			"No backup was created. " +
			"Increase `export.fileSystem.sizeLimitBytes` in the server configuration (or " +
			"`EVITADB_EXPORT_FILESYSTEM_SIZELIMITBYTES`) and restart the server. The catalog currently occupies " +
			StringUtils.formatByteSize(onDisk) + " on disk.";
	}

	/** Measures a complete archive with a generous real service limit. */
	@Nonnull
	private FileForFetch measure(boolean full) throws IOException {
		configureLimit(10000000);
		final ServerTask<?, FileForFetch> task = task(full);
		task.transitionToIssued();
		task.execute();
		assertEquals(TaskSimplifiedState.FINISHED, task.getStatus().simplifiedState());
		final FileForFetch file = task.getFutureResult().join();
		this.exports.deleteFile(file.fileId());
		return file;
	}

	/** Computes the maximum sidecar from the measured export's actual descriptor. */
	private static long metadataReserve(@Nonnull FileForFetch file) {
		final FileForFetch maximum = new FileForFetch(
			file.fileId(), file.name(), file.description(), file.contentType(), Long.MAX_VALUE,
			OffsetDateTime.of(999999999, 12, 31, 23, 59, 59, 999999999, ZoneOffset.ofHoursMinutesSeconds(17, 59, 59)),
			file.origin()
		);
		return maximum.toLines().stream().mapToLong(
			line -> (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8).length
		).sum();
	}

	/**
	 * Sets the allowance to N-1. Point-in-time bootstrap timestamps can reduce the next compressed archive by a
	 * byte or two; an admitted attempt becomes the new measurement. This calibrates a byte boundary, not a race.
	 */
	private void assertFinalizationRefused(boolean full) throws IOException {
		// Only the boundary test fixes the descriptor name; other tests retain the task's variable timestamp name.
		// The real service still constructs the handle, computes its allowance and writes all archive/sidecar bytes.
		this.stableFileName = true;
		FileForFetch measured = measure(full);
		for (int calibration = 0; calibration < 16; calibration++) {
			final long allowance = measured.totalSizeInBytes() - 1;
			final long limit = allowance + metadataReserve(measured);
			configureLimit(limit);
			final long onDisk = FileUtils.getDirectorySize(this.persistence.getCatalogStoragePath());
			final ServerTask<?, FileForFetch> task = task(full);
			task.transitionToIssued();
			RuntimeException raised = null;
			try {
				task.execute();
			} catch (RuntimeException exception) {
				raised = exception;
			}
			assertEquals(allowance, this.lastHandle.sizeLimit().maxFileSizeInBytes(),
				"the configured allowance must be exactly N-1 for this export's actual metadata");
			if (raised == null) {
				final FileForFetch smaller = task.getFutureResult().join();
				assertTrue(smaller.totalSizeInBytes() < measured.totalSizeInBytes(),
					"each admitted calibration must strictly reduce the measured archive size");
				this.exports.deleteFile(smaller.fileId());
				measured = smaller;
			} else {
				final ExportFileTooLargeException failure = assertRefused(task, limit, onDisk);
				assertTrue(Stream.of(failure.getStackTrace()).anyMatch(
					it -> it.getClassName().equals("java.util.zip.ZipOutputStream") &&
						it.getMethodName().equals("close")
				), "N-1 must trip during ZipOutputStream finalization");
				return;
			}
		}
		fail("could not calibrate the N-1 boundary within 16 strictly decreasing archive sizes");
	}

	/** A close fit must both succeed and remain fetchable after an explicit purge. */
	private void assertAdmittedAndKept(boolean full) throws IOException {
		final FileForFetch measured = measure(full);
		final long limit = measured.totalSizeInBytes() + metadataReserve(measured) + 16;
		configureLimit(limit);
		final ServerTask<?, FileForFetch> task = task(full);
		task.transitionToIssued();
		task.execute();
		assertEquals(TaskSimplifiedState.FINISHED, task.getStatus().simplifiedState());
		final FileForFetch file = task.getFutureResult().join();
		this.exports.purgeFiles();
		assertTrue(this.exports.getFile(file.fileId()).isPresent(), "an admitted backup must survive purge");
		try (final InputStream input = this.exports.fetchFile(file.fileId())) {
			assertEquals(file.totalSizeInBytes(), input.readAllBytes().length);
		}
		final long sidecarBytes = Files.size(this.paths.work().resolve("failed-exports")
			.resolve(file.fileId() + FileForFetch.METADATA_EXTENSION));
		assertTrue(limit - file.totalSizeInBytes() - sidecarBytes <= 64, "reserve must waste at most 64 bytes");
	}

	/** Tests retention in isolation; no concurrent unfinished export is present during purge. */
	private void assertOlderExportSurvives(boolean full) throws IOException {
		configureLimit(20000);
		final ExportFileHandle old = this.exports.storeFile("older.txt", null, "text/plain", null);
		old.outputStream().write(new byte[]{1, 2, 3});
		old.close();
		final ServerTask<?, FileForFetch> task = task(full);
		task.transitionToIssued();
		assertThrows(RuntimeException.class, task::execute);
		assertEquals(TaskSimplifiedState.FAILED, task.getStatus().simplifiedState());
		assertInstanceOf(ExportFileTooLargeException.class,
			assertThrows(CompletionException.class, () -> task.getFutureResult().join()).getCause());
		assertEquals(expectedMessage(20000, FileUtils.getDirectorySize(this.persistence.getCatalogStoragePath())),
			task.getStatus().publicExceptionMessage());
		this.exports.purgeFiles();
		assertEquals(1, this.exports.listFilesToFetch(1, 10, Set.of()).getTotalRecordCount());
		assertTrue(this.exports.getFile(old.fileId()).isPresent());
		try (final InputStream input = this.exports.fetchFile(old.fileId())) {
			assertArrayEquals(new byte[]{1, 2, 3}, input.readAllBytes());
		}
	}
}
