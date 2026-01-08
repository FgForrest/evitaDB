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

package io.evitadb.api.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link ServerOptions} builder behaviour and default values.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("ServerOptions configuration tests")
class ServerOptionsTest {

	@Test
	@DisplayName("Builder should initialize defaults correctly")
	void shouldInitDefaults() {
		final ServerOptions options = ServerOptions.builder().build();
		assertTrue(options.requestThreadPool().minThreadCount() > 0);
		assertTrue(options.requestThreadPool().maxThreadCount() >= options.requestThreadPool().minThreadCount());
		assertEquals(8, options.requestThreadPool().threadPriority());
		assertEquals(100, options.requestThreadPool().queueSize());
		assertEquals(1200, options.closeSessionsAfterSecondsOfInactivity());
	}

	@Test
	@DisplayName("Static default constants should have correct values")
	void shouldHaveCorrectDefaultConstants() {
		assertEquals(5000L, ServerOptions.DEFAULT_QUERY_TIMEOUT_IN_MILLISECONDS);
		assertEquals(300_000L, ServerOptions.DEFAULT_TRANSACTION_TIMEOUT_IN_MILLISECONDS);
		assertEquals(1200, ServerOptions.DEFAULT_CLOSE_SESSIONS_AFTER_SECONDS_OF_INACTIVITY);
		assertFalse(ServerOptions.DEFAULT_READ_ONLY);
		assertFalse(ServerOptions.DEFAULT_QUIET);
	}

	@Test
	@DisplayName("No-arg constructor should initialize all defaults")
	void shouldInitAllDefaultsWithNoArgConstructor() {
		final ServerOptions options = new ServerOptions();

		// verify timeout defaults
		assertEquals(5000L, options.queryTimeoutInMilliseconds());
		assertEquals(300_000L, options.transactionTimeoutInMilliseconds());
		assertEquals(1200, options.closeSessionsAfterSecondsOfInactivity());

		// verify boolean defaults
		assertFalse(options.readOnly());
		assertFalse(options.quiet());

		// verify nested options are non-null
		assertNotNull(options.requestThreadPool());
		assertNotNull(options.transactionThreadPool());
		assertNotNull(options.serviceThreadPool());
		assertNotNull(options.changeDataCapture());
		assertNotNull(options.trafficRecording());
		assertNotNull(options.schedule());
	}

	@Test
	@DisplayName("Builder should allow setting all values")
	void shouldSetAllValuesViaBuilder() {
		final ThreadPoolOptions customRequestPool = ThreadPoolOptions.requestThreadPoolBuilder()
			.minThreadCount(5)
			.maxThreadCount(10)
			.build();
		final ThreadPoolOptions customTransactionPool = ThreadPoolOptions.transactionThreadPoolBuilder()
			.minThreadCount(2)
			.maxThreadCount(4)
			.build();
		final ThreadPoolOptions customServicePool = ThreadPoolOptions.serviceThreadPoolBuilder()
			.minThreadCount(1)
			.maxThreadCount(2)
			.build();
		final ChangeDataCaptureOptions customCdc = ChangeDataCaptureOptions.builder()
			.enabled(false)
			.build();
		final TrafficRecordingOptions customTraffic = TrafficRecordingOptions.builder()
			.enabled(true)
			.build();
		final ScheduleOptions customSchedule = ScheduleOptions.builder()
			.backup(List.of(
				BackupScheduleOptions.builder()
					.cron("0 0 2 * * *")
					.backupType(BackupType.FULL)
					.retention(7)
					.build()
			))
			.build();

		final ServerOptions options = ServerOptions.builder()
			.requestThreadPool(customRequestPool)
			.transactionThreadPool(customTransactionPool)
			.serviceThreadPool(customServicePool)
			.queryTimeoutInMilliseconds(10000L)
			.transactionTimeoutInMilliseconds(600000L)
			.closeSessionsAfterSecondsOfInactivity(3600)
			.changeDataCapture(customCdc)
			.trafficRecording(customTraffic)
			.schedule(customSchedule)
			.readOnly(true)
			.quiet(true)
			.build();

		assertEquals(customRequestPool, options.requestThreadPool());
		assertEquals(customTransactionPool, options.transactionThreadPool());
		assertEquals(customServicePool, options.serviceThreadPool());
		assertEquals(10000L, options.queryTimeoutInMilliseconds());
		assertEquals(600000L, options.transactionTimeoutInMilliseconds());
		assertEquals(3600, options.closeSessionsAfterSecondsOfInactivity());
		assertEquals(customCdc, options.changeDataCapture());
		assertEquals(customTraffic, options.trafficRecording());
		assertEquals(customSchedule, options.schedule());
		assertTrue(options.readOnly());
		assertTrue(options.quiet());
	}

	@Test
	@DisplayName("Builder from existing options should copy all values")
	void shouldCreateBuilderFromExistingOptions() {
		final ServerOptions original = ServerOptions.builder()
			.queryTimeoutInMilliseconds(15000L)
			.transactionTimeoutInMilliseconds(450000L)
			.closeSessionsAfterSecondsOfInactivity(2400)
			.readOnly(true)
			.quiet(true)
			.build();

		final ServerOptions copied = ServerOptions.builder(original).build();

		assertEquals(original.requestThreadPool(), copied.requestThreadPool());
		assertEquals(original.transactionThreadPool(), copied.transactionThreadPool());
		assertEquals(original.serviceThreadPool(), copied.serviceThreadPool());
		assertEquals(original.queryTimeoutInMilliseconds(), copied.queryTimeoutInMilliseconds());
		assertEquals(original.transactionTimeoutInMilliseconds(), copied.transactionTimeoutInMilliseconds());
		assertEquals(original.closeSessionsAfterSecondsOfInactivity(), copied.closeSessionsAfterSecondsOfInactivity());
		assertEquals(original.changeDataCapture(), copied.changeDataCapture());
		assertEquals(original.trafficRecording(), copied.trafficRecording());
		assertEquals(original.schedule(), copied.schedule());
		assertEquals(original.readOnly(), copied.readOnly());
		assertEquals(original.quiet(), copied.quiet());
	}

	@Test
	@DisplayName("Constructor should use defaults for null values")
	void shouldHandleNullValuesInConstructor() {
		final ServerOptions options = new ServerOptions(
			null,  // requestThreadPool
			null,  // transactionThreadPool
			null,  // serviceThreadPool
			5000L,
			300_000L,
			1200,
			null,  // changeDataCapture
			null,  // trafficRecording
			null,  // schedule
			false,
			false,
			false
		);

		// verify defaults are used for null values
		assertNotNull(options.requestThreadPool());
		assertNotNull(options.transactionThreadPool());
		assertNotNull(options.serviceThreadPool());
		assertNotNull(options.changeDataCapture());
		assertNotNull(options.trafficRecording());
		assertNotNull(options.schedule());
		assertTrue(options.schedule().backup().isEmpty());

		// verify thread pool defaults are applied
		assertEquals(8, options.requestThreadPool().threadPriority());
		assertEquals(5, options.transactionThreadPool().threadPriority());
		assertEquals(1, options.serviceThreadPool().threadPriority());
	}

	@Test
	@DisplayName("Thread pools should have correct default configurations")
	void shouldInitThreadPoolsWithCorrectDefaults() {
		final ServerOptions options = ServerOptions.builder().build();

		// request thread pool defaults
		final ThreadPoolOptions requestPool = options.requestThreadPool();
		assertTrue(requestPool.minThreadCount() > 0);
		assertTrue(requestPool.maxThreadCount() >= requestPool.minThreadCount());
		assertEquals(8, requestPool.threadPriority());
		assertEquals(100, requestPool.queueSize());

		// transaction thread pool defaults
		final ThreadPoolOptions transactionPool = options.transactionThreadPool();
		assertTrue(transactionPool.minThreadCount() > 0);
		assertTrue(transactionPool.maxThreadCount() >= transactionPool.minThreadCount());
		assertEquals(5, transactionPool.threadPriority());
		assertEquals(100, transactionPool.queueSize());

		// service thread pool defaults
		final ThreadPoolOptions servicePool = options.serviceThreadPool();
		assertTrue(servicePool.minThreadCount() >= 1);
		assertTrue(servicePool.maxThreadCount() >= servicePool.minThreadCount());
		assertEquals(1, servicePool.threadPriority());
		assertEquals(20, servicePool.queueSize());
	}

	@Test
	@DisplayName("No-arg constructor should initialize empty backup schedule")
	void shouldInitEmptyBackupSchedule() {
		final ServerOptions options = new ServerOptions();
		assertNotNull(options.schedule());
		assertNotNull(options.schedule().backup());
		assertTrue(options.schedule().backup().isEmpty());
	}

	@Test
	@DisplayName("Builder should allow setting schedule with backup list")
	void shouldSetScheduleViaBuilder() {
		final List<BackupScheduleOptions> backups = List.of(
			BackupScheduleOptions.builder()
				.cron("0 0 2 * * *")
				.backupType(BackupType.FULL)
				.retention(7)
				.build(),
			BackupScheduleOptions.builder()
				.cron("0 0 */4 * * *")
				.backupType(BackupType.SNAPSHOT)
				.retention(3)
				.build()
		);

		final ServerOptions options = ServerOptions.builder()
			.schedule(ScheduleOptions.builder().backup(backups).build())
			.build();

		assertEquals(2, options.schedule().backup().size());
		assertEquals(BackupType.FULL, options.schedule().backup().get(0).backupType());
		assertEquals(BackupType.SNAPSHOT, options.schedule().backup().get(1).backupType());
	}

	@Test
	@DisplayName("Backup schedule list should be immutable")
	void shouldReturnImmutableBackupScheduleList() {
		final ServerOptions options = ServerOptions.builder()
			.schedule(ScheduleOptions.builder()
				.backup(List.of(
					BackupScheduleOptions.builder()
						.cron("0 0 2 * * *")
						.build()
				))
				.build())
			.build();

		assertThrows(UnsupportedOperationException.class, () ->
			options.schedule().backup().add(
				BackupScheduleOptions.builder()
					.cron("0 0 3 * * *")
					.build()
			)
		);
	}

	@Test
	@DisplayName("Static schedule backup default constant should be empty list")
	void shouldHaveEmptyDefaultBackupSchedule() {
		assertNotNull(ScheduleOptions.DEFAULT_BACKUP_SCHEDULE);
		assertTrue(ScheduleOptions.DEFAULT_BACKUP_SCHEDULE.isEmpty());
	}

	@Test
	@DisplayName("Builder from existing options should copy schedule")
	void shouldCopyScheduleFromExistingOptions() {
		final List<BackupScheduleOptions> backups = List.of(
			BackupScheduleOptions.builder()
				.cron("0 0 2 * * *")
				.backupType(BackupType.FULL)
				.retention(5)
				.build()
		);

		final ServerOptions original = ServerOptions.builder()
			.schedule(ScheduleOptions.builder().backup(backups).build())
			.build();

		final ServerOptions copied = ServerOptions.builder(original).build();

		assertEquals(original.schedule().backup().size(), copied.schedule().backup().size());
		assertEquals(original.schedule().backup().get(0).cron(), copied.schedule().backup().get(0).cron());
		assertEquals(original.schedule().backup().get(0).backupType(), copied.schedule().backup().get(0).backupType());
		assertEquals(original.schedule().backup().get(0).retention(), copied.schedule().backup().get(0).retention());
	}

}
