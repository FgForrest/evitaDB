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

package io.evitadb.core.catalog;

import io.evitadb.api.configuration.BackupScheduleOptions;
import io.evitadb.api.configuration.BackupType;
import io.evitadb.api.configuration.ScheduleOptions;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.task.ServerTask;
import io.evitadb.core.executor.ScheduledTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.export.file.ExportFileService;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.spi.export.model.ExportFileHandle;
import io.evitadb.spi.store.catalog.header.model.CollectionReference;
import io.evitadb.spi.store.catalog.header.model.EntityCollectionHeader;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * This test verifies behaviour of {@link ScheduledBackupSupport}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ScheduledBackupSupport functionality tests")
class ScheduledBackupSupportTest implements EvitaTestSupport {

	private static final String SUBDIR_NAME = "scheduledBackupSupportTest_" + UUID.randomUUID();
	private static final String TEST_CATALOG = "testCatalog";
	private static final String FULL_BACKUP_ORIGIN = "BackupFull";
	private static final String SNAPSHOT_BACKUP_ORIGIN = "BackupSnapshot";

	private ExportFileService exportService;
	private SynchronousScheduler synchronousScheduler;
	private CatalogPersistenceService<LogRecordReference, CollectionReference, EntityCollectionHeader> mockPersistenceService;
	private Logger mockLogger;
	private TestScheduledBackupSupport testInstance;

	/**
	 * Test implementation of the ScheduledBackupSupport interface.
	 */
	private static class TestScheduledBackupSupport implements ScheduledBackupSupport {
	}

	/**
	 * A scheduler wrapper that captures scheduled tasks for synchronous execution.
	 * This avoids Thread.sleep() calls in tests by allowing controlled task
	 * execution.
	 */
	private static class SynchronousScheduler extends Scheduler {
		private final List<CapturedSchedule> capturedSchedules = new ArrayList<>(64);
		private final ScheduledThreadPoolExecutor executor;

		SynchronousScheduler() {
			super(createTestExecutor());
			this.executor = createTestExecutor();
		}

		private static ScheduledThreadPoolExecutor createTestExecutor() {
			return new ScheduledThreadPoolExecutor(2);
		}

		/**
		 * Record representing a captured scheduled task.
		 */
		record CapturedSchedule(Runnable runnable, long delay, TimeUnit unit) {
		}

		@Nonnull
		@Override
		public ScheduledFuture<?> schedule(@Nonnull Runnable runnable, long delay, @Nonnull TimeUnit delayUnits) {
			this.capturedSchedules.add(new CapturedSchedule(runnable, delay, delayUnits));
			ScheduledFuture<?> mockFuture = mock(ScheduledFuture.class);
			when(mockFuture.cancel(anyBoolean())).thenReturn(true);
			return mockFuture;
		}

		/**
		 * Executes the captured task at the specified index synchronously.
		 */
		public void executeCapturedTask(int index) {
			if (index < this.capturedSchedules.size()) {
				this.capturedSchedules.get(index).runnable().run();
			}
		}

		/**
		 * Overrides the submit method to execute the task synchronously and return a completed future.
		 * This allows mocked tasks to be executed directly, making the mocks work correctly.
		 */
		@Nonnull
		@Override
		public <T> CompletableFuture<T> submit(@Nonnull ServerTask<?, T> task) {
			// Execute the task synchronously (this will trigger the mock)
			final T result = task.execute();
			// Return a completed future with the result
			return CompletableFuture.completedFuture(result);
		}

		@Override
		public void shutdown() {
			this.executor.shutdown();
			super.shutdown();
		}

		@Nonnull
		@Override
		public List<Runnable> shutdownNow() {
			this.executor.shutdownNow();
			return super.shutdownNow();
		}
	}

	@BeforeEach
	void setUp() throws IOException {
		// Use random directory name for isolation
		final Path exportDirectory = getPathInTargetDirectory(SUBDIR_NAME);
		cleanTestSubDirectory(SUBDIR_NAME);

		final FileSystemExportOptions exportOptions = FileSystemExportOptions.builder()
			.sizeLimitBytes(100000)
			.historyExpirationSeconds(3600)
			.directory(exportDirectory)
			.build();
		this.synchronousScheduler = new SynchronousScheduler();
		this.exportService = new ExportFileService(exportOptions, Mockito.mock(Scheduler.class));
		this.mockPersistenceService = createMockPersistenceService();
		this.mockLogger = mock(Logger.class);
		this.testInstance = new TestScheduledBackupSupport();
	}

	@AfterEach
	void tearDown() {
		this.exportService.close();
		this.synchronousScheduler.shutdown();
		cleanTestSubDirectoryWithRethrow(SUBDIR_NAME);
	}

	@SuppressWarnings("unchecked")
	private static CatalogPersistenceService<LogRecordReference, CollectionReference, EntityCollectionHeader> createMockPersistenceService() {
		return mock(CatalogPersistenceService.class);
	}

	/**
	 * Creates a real backup file on disk using the ExportFileService.
	 *
	 * @param origin  the origin array for the file (e.g., ["Backup", "Full"])
	 * @param content the content to write to the file
	 * @return the created FileForFetch
	 */
	@Nonnull
	private FileForFetch createRealBackupFile(@Nonnull String origin, @Nonnull String content) throws IOException {
		final ExportFileHandle handle = this.exportService.storeExternallyManagedFile(
				"backup_" + UUID.randomUUID() + ".zip",
				"Test backup file",
				"application/zip",
				TEST_CATALOG,
				origin);
		try (OutputStream os = handle.outputStream()) {
			os.write(content.getBytes(StandardCharsets.UTF_8));
		}
		return handle.fileForFetchFuture().getNow(null);
	}

	/**
	 * Creates a mock ServerTask that returns a real file created on disk.
	 */
	@SuppressWarnings({"rawtypes"})
	@Nonnull
	private ServerTask createMockTaskWithRealFile(@Nonnull String origin, @Nonnull String content) {
		final ServerTask mockTask = mock(ServerTask.class);
		when(mockTask.execute()).thenAnswer(invocation -> createRealBackupFile(origin, content));
		return mockTask;
	}

	/**
	 * Creates a mock ServerTask that returns null (simulating failed backup).
	 */
	@SuppressWarnings({"rawtypes"})
	private static ServerTask createMockTaskReturningNull() {
		final ServerTask mockTask = mock(ServerTask.class);
		when(mockTask.execute()).thenReturn(null);
		return mockTask;
	}

	/**
	 * Creates a mock ServerTask that returns a real but empty file.
	 */
	@SuppressWarnings({"rawtypes"})
	private ServerTask createMockTaskWithEmptyFile(@Nonnull String origin) {
		final ServerTask mockTask = mock(ServerTask.class);
		// Empty content
		when(mockTask.execute()).thenAnswer(invocation -> createRealBackupFile(origin, ""));
		return mockTask;
	}

	// ===========================================
	// INITIALIZATION TESTS
	// ===========================================

	@Test
	@DisplayName("Should create no tasks when no backup schedules configured")
	void shouldCreateNoTasksWhenNoBackupSchedulesConfigured() {
		final ScheduleOptions scheduleOptions = new ScheduleOptions(Collections.emptyList());

		final List<ScheduledTask> tasks = this.testInstance.initializeBackupTasks(
			TEST_CATALOG, scheduleOptions, this.synchronousScheduler, this.exportService, this.mockPersistenceService,
			this.mockLogger
		);

		assertTrue(tasks.isEmpty(), "Should return empty list when no backup schedules configured");
	}

	@Test
	@DisplayName("Should create single full backup task")
	void shouldCreateSingleFullBackupTask() {
		final BackupScheduleOptions backupOptions = BackupScheduleOptions.builder()
				.cron("0 0 2 * * *") // Every day at 2 AM
				.backupType(BackupType.FULL)
				.retention(3)
				.build();
		final ScheduleOptions scheduleOptions = new ScheduleOptions(List.of(backupOptions));

		final List<ScheduledTask> tasks = this.testInstance.initializeBackupTasks(
			TEST_CATALOG, scheduleOptions, this.synchronousScheduler, this.exportService, this.mockPersistenceService,
			this.mockLogger
		);

		assertEquals(1, tasks.size(), "Should create exactly one task");
		assertEquals("AutomatedFullBackup", tasks.get(0).getTaskName());
	}

	@Test
	@DisplayName("Should create single snapshot backup task")
	void shouldCreateSingleSnapshotBackupTask() {
		final BackupScheduleOptions backupOptions = BackupScheduleOptions.builder()
				.cron("0 0 * * * *") // Every hour
				.backupType(BackupType.SNAPSHOT)
				.retention(5)
				.build();
		final ScheduleOptions scheduleOptions = new ScheduleOptions(List.of(backupOptions));

		final List<ScheduledTask> tasks = this.testInstance.initializeBackupTasks(
			TEST_CATALOG, scheduleOptions, this.synchronousScheduler, this.exportService, this.mockPersistenceService,
			this.mockLogger
		);

		assertEquals(1, tasks.size(), "Should create exactly one task");
		assertEquals("AutomatedSnapshotBackup", tasks.get(0).getTaskName());
	}

	@Test
	@DisplayName("Should create multiple backup tasks for multiple schedules")
	void shouldCreateMultipleBackupTasks() {
		final BackupScheduleOptions fullBackup = BackupScheduleOptions.builder()
				.cron("0 0 2 * * *")
				.backupType(BackupType.FULL)
				.retention(3)
				.build();
		final BackupScheduleOptions snapshotBackup = BackupScheduleOptions.builder()
				.cron("0 0 * * * *")
				.backupType(BackupType.SNAPSHOT)
				.retention(10)
				.build();
		final ScheduleOptions scheduleOptions = new ScheduleOptions(List.of(fullBackup, snapshotBackup));

		final List<ScheduledTask> tasks = this.testInstance.initializeBackupTasks(
			TEST_CATALOG, scheduleOptions, this.synchronousScheduler, this.exportService, this.mockPersistenceService,
			this.mockLogger
		);

		assertEquals(2, tasks.size(), "Should create two tasks");
		assertTrue(
				tasks.stream().anyMatch(t -> "AutomatedFullBackup".equals(t.getTaskName())),
				"Should have full backup task");
		assertTrue(
				tasks.stream().anyMatch(t -> "AutomatedSnapshotBackup".equals(t.getTaskName())),
				"Should have snapshot backup task");
	}

	@Test
	@DisplayName("Should return unmodifiable list")
	void shouldReturnUnmodifiableList() {
		final BackupScheduleOptions backupOptions = BackupScheduleOptions.builder()
				.cron("0 0 2 * * *")
				.backupType(BackupType.FULL)
				.retention(3)
				.build();
		final ScheduleOptions scheduleOptions = new ScheduleOptions(List.of(backupOptions));

		final List<ScheduledTask> tasks = this.testInstance.initializeBackupTasks(
			TEST_CATALOG, scheduleOptions, this.synchronousScheduler, this.exportService, this.mockPersistenceService,
			this.mockLogger
		);

		assertThrows(
			UnsupportedOperationException.class,
			() -> tasks.add(null),
			"Returned list should be unmodifiable"
		);
	}

	// ===========================================
	// FULL BACKUP EXECUTION TESTS
	// ===========================================

	@Test
	@DisplayName("Should execute full backup and log success with real file on disk")
	void shouldExecuteFullBackupSuccessfully() {
		//noinspection rawtypes
		final ServerTask mockTask = createMockTaskWithRealFile(FULL_BACKUP_ORIGIN, "full backup content data");
		//noinspection unchecked
		when(this.mockPersistenceService.createSystemFullBackupTask()).thenReturn(mockTask);

		final BackupScheduleOptions backupOptions = BackupScheduleOptions.builder()
				.cron("0 0 2 * * *")
				.backupType(BackupType.FULL)
				.retention(3)
				.build();
		final ScheduleOptions scheduleOptions = new ScheduleOptions(List.of(backupOptions));

		this.testInstance.initializeBackupTasks(
			TEST_CATALOG, scheduleOptions, this.synchronousScheduler, this.exportService, this.mockPersistenceService,
			this.mockLogger
		);

		// Execute the captured scheduled task
		this.synchronousScheduler.executeCapturedTask(0);

		// Verify backup was executed
		verify(this.mockPersistenceService).createSystemFullBackupTask();
		verify(mockTask).execute();

		// Verify success was logged
		verify(this.mockLogger).info(
				eq("Scheduled {} of catalog {} completed with size of {}."),
				eq("full backup"),
				eq(TEST_CATALOG),
				anyString());

		// Verify the file exists on disk
		final PaginatedList<FileForFetch> files = this.exportService.listFilesToFetch(
			1, 100, Set.of(TEST_CATALOG), Set.of()
		);
		assertEquals(1, files.getTotalRecordCount(), "Should have one backup file on disk");
	}

	// ===========================================
	// SNAPSHOT BACKUP EXECUTION TESTS
	// ===========================================

	@Test
	@DisplayName("Should execute snapshot backup and log success with real file on disk")
	void shouldExecuteSnapshotBackupSuccessfully() {
		//noinspection rawtypes
		final ServerTask mockTask = createMockTaskWithRealFile(SNAPSHOT_BACKUP_ORIGIN,
				"snapshot backup content data");
		//noinspection unchecked
		when(this.mockPersistenceService.createSystemBackupTask()).thenReturn(mockTask);

		final BackupScheduleOptions backupOptions = BackupScheduleOptions.builder()
				.cron("0 0 * * * *")
				.backupType(BackupType.SNAPSHOT)
				.retention(5)
				.build();
		final ScheduleOptions scheduleOptions = new ScheduleOptions(List.of(backupOptions));

		this.testInstance.initializeBackupTasks(
			TEST_CATALOG, scheduleOptions, this.synchronousScheduler, this.exportService, this.mockPersistenceService,
			this.mockLogger
		);

		// Execute the captured scheduled task
		this.synchronousScheduler.executeCapturedTask(0);

		// Verify backup was executed
		verify(this.mockPersistenceService).createSystemBackupTask();
		verify(mockTask).execute();

		// Verify success was logged
		verify(this.mockLogger).info(
				eq("Scheduled {} of catalog {} completed with size of {}."),
				eq("snapshot backup"),
				eq(TEST_CATALOG),
				anyString());

		// Verify the file exists on disk
		final PaginatedList<FileForFetch> files = this.exportService.listFilesToFetch(1, 100, Set.of(TEST_CATALOG),
		                                                                              Set.of());
		assertEquals(1, files.getTotalRecordCount(), "Should have one backup file on disk");
	}

	// ===========================================
	// ERROR HANDLING TESTS
	// ===========================================

	@Test
	@DisplayName("Should log error when backup produces null file")
	void shouldLogErrorWhenBackupProducesNullFile() {
		//noinspection rawtypes
		final ServerTask mockTask = createMockTaskReturningNull();
		//noinspection unchecked
		when(this.mockPersistenceService.createSystemFullBackupTask()).thenReturn(mockTask);

		final BackupScheduleOptions backupOptions = BackupScheduleOptions.builder()
				.cron("0 0 2 * * *")
				.backupType(BackupType.FULL)
				.retention(3)
				.build();
		final ScheduleOptions scheduleOptions = new ScheduleOptions(List.of(backupOptions));

		this.testInstance.initializeBackupTasks(
			TEST_CATALOG, scheduleOptions, this.synchronousScheduler, this.exportService, this.mockPersistenceService,
			this.mockLogger
		);

		// Execute the captured scheduled task
		this.synchronousScheduler.executeCapturedTask(0);

		// Verify error was logged
		verify(this.mockLogger).error(
				eq("Scheduled {} of catalog {} produced invalid file."),
				eq("full backup"),
				eq(TEST_CATALOG));
	}

	@Test
	@DisplayName("Should log error when backup produces zero-size file")
	void shouldLogErrorWhenBackupProducesZeroSizeFile() {
		//noinspection rawtypes
		final ServerTask mockTask = createMockTaskWithEmptyFile(FULL_BACKUP_ORIGIN);
		//noinspection unchecked
		when(this.mockPersistenceService.createSystemFullBackupTask()).thenReturn(mockTask);

		final BackupScheduleOptions backupOptions = BackupScheduleOptions.builder()
				.cron("0 0 2 * * *")
				.backupType(BackupType.FULL)
				.retention(3)
				.build();
		final ScheduleOptions scheduleOptions = new ScheduleOptions(List.of(backupOptions));

		this.testInstance.initializeBackupTasks(
			TEST_CATALOG, scheduleOptions, this.synchronousScheduler, this.exportService, this.mockPersistenceService,
			this.mockLogger
		);

		// Execute the captured scheduled task
		this.synchronousScheduler.executeCapturedTask(0);

		// Verify error was logged
		verify(this.mockLogger).error(
				eq("Scheduled {} of catalog {} produced invalid file."),
				eq("full backup"),
				eq(TEST_CATALOG));
	}

	// ===========================================
	// RETENTION POLICY TESTS
	// ===========================================

	@Test
	@DisplayName("Should apply retention policy - delete old backups exceeding retention limit")
	void shouldApplyRetentionPolicyAfterSuccessfulBackup() throws IOException {
		// Create existing backup files on disk that will exceed retention
		for (int i = 0; i < 5; i++) {
			createRealBackupFile(FULL_BACKUP_ORIGIN, "existing backup content " + i);
		}

		// Verify we have 5 files before backup
		assertEquals(5, this.exportService.listFilesToFetch(1, 100, Set.of(TEST_CATALOG), Set.of(FULL_BACKUP_ORIGIN))
				.getTotalRecordCount());

		// Create spy on export service to verify deleteFile calls
		final ExportFileService spyExportService = spy(this.exportService);

		// Setup mock task that creates a new backup file
		//noinspection rawtypes
		final ServerTask mockTask = createMockTaskWithRealFile(FULL_BACKUP_ORIGIN, "new backup content");
		//noinspection unchecked
		when(this.mockPersistenceService.createSystemFullBackupTask()).thenReturn(mockTask);

		final BackupScheduleOptions backupOptions = BackupScheduleOptions.builder()
				.cron("0 0 2 * * *")
				.backupType(BackupType.FULL)
				.retention(2) // Only keep 2 backups
				.build();
		final ScheduleOptions scheduleOptions = new ScheduleOptions(List.of(backupOptions));

		this.testInstance.initializeBackupTasks(
			TEST_CATALOG, scheduleOptions, this.synchronousScheduler, spyExportService, this.mockPersistenceService,
			this.mockLogger
		);

		// Execute the captured scheduled task
		this.synchronousScheduler.executeCapturedTask(0);

		// Verify that old backups were deleted (6 total - 2 retention = 4 deletions)
		verify(spyExportService, times(4)).deleteFile(any(UUID.class));

		// Verify deletion was logged
		verify(this.mockLogger, times(4)).info(
				eq("Deleted old scheduled {} of catalog {} with size of {}."),
				eq("full backup"),
				eq(TEST_CATALOG),
				anyString());
	}

	@Test
	@DisplayName("Should not delete files when within retention limit")
	void shouldNotDeleteFilesWhenWithinRetentionLimit() throws IOException {
		// Create 2 existing backup files (within retention limit of 5)
		for (int i = 0; i < 2; i++) {
			createRealBackupFile(FULL_BACKUP_ORIGIN, "existing backup content " + i);
		}

		// Create spy on export service
		final ExportFileService spyExportService = spy(this.exportService);

		// Setup mock task that creates a new backup file
		//noinspection rawtypes
		final ServerTask mockTask = createMockTaskWithRealFile(FULL_BACKUP_ORIGIN, "new backup content");
		//noinspection unchecked
		when(this.mockPersistenceService.createSystemFullBackupTask()).thenReturn(mockTask);

		final BackupScheduleOptions backupOptions = BackupScheduleOptions.builder()
				.cron("0 0 2 * * *")
				.backupType(BackupType.FULL)
				.retention(5) // Keep 5 backups, we only have 3 after new backup
				.build();
		final ScheduleOptions scheduleOptions = new ScheduleOptions(List.of(backupOptions));

		this.testInstance.initializeBackupTasks(
			TEST_CATALOG, scheduleOptions, this.synchronousScheduler, spyExportService, this.mockPersistenceService,
			this.mockLogger
		);

		// Execute the captured scheduled task
		this.synchronousScheduler.executeCapturedTask(0);

		// Verify no files were deleted
		verify(spyExportService, never()).deleteFile(any(UUID.class));

		// Verify we have 3 files after backup
		assertEquals(
			3,
			this.exportService.listFilesToFetch(1, 100, Set.of(TEST_CATALOG), Set.of(FULL_BACKUP_ORIGIN))
				.getTotalRecordCount()
		);
	}

}