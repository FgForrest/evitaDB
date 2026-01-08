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

import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link BackupScheduleOptions} configuration.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("BackupScheduleOptions configuration tests")
class BackupScheduleOptionsTest {

	@Test
	@DisplayName("Builder should use defaults for backup type and retention")
	void shouldUseDefaultsForBackupTypeAndRetention() {
		final BackupScheduleOptions options = BackupScheduleOptions.builder()
			.cron("0 0 2 * * *")
			.build();

		assertEquals("0 0 2 * * *", options.cron());
		assertEquals(BackupType.SNAPSHOT, options.backupType());
		assertEquals(3, options.retention());
	}

	@Test
	@DisplayName("Builder should allow setting all values")
	void shouldSetAllValuesViaBuilder() {
		final BackupScheduleOptions options = BackupScheduleOptions.builder()
			.cron("0 0 0 * * SUN")
			.backupType(BackupType.FULL)
			.retention(7)
			.build();

		assertEquals("0 0 0 * * SUN", options.cron());
		assertEquals(BackupType.FULL, options.backupType());
		assertEquals(7, options.retention());
	}

	@Test
	@DisplayName("Should reject invalid cron expression")
	void shouldRejectInvalidCronExpression() {
		assertThrows(EvitaInvalidUsageException.class, () ->
			BackupScheduleOptions.builder()
				.cron("invalid-cron")
				.build()
		);
	}

	@Test
	@DisplayName("Should reject non-positive retention")
	void shouldRejectNonPositiveRetention() {
		assertThrows(IllegalArgumentException.class, () ->
			BackupScheduleOptions.builder()
				.cron("0 0 2 * * *")
				.retention(0)
				.build()
		);

		assertThrows(IllegalArgumentException.class, () ->
			BackupScheduleOptions.builder()
				.cron("0 0 2 * * *")
				.retention(-1)
				.build()
		);
	}

	@Test
	@DisplayName("Should require cron expression")
	void shouldRequireCronExpression() {
		assertThrows(IllegalArgumentException.class, () ->
			BackupScheduleOptions.builder()
				.backupType(BackupType.FULL)
				.build()
		);
	}

	@Test
	@DisplayName("Copy constructor should copy all values")
	void shouldCopyAllValuesFromExistingOptions() {
		final BackupScheduleOptions original = BackupScheduleOptions.builder()
			.cron("0 0 3 * * *")
			.backupType(BackupType.FULL)
			.retention(5)
			.build();

		final BackupScheduleOptions copied = BackupScheduleOptions.builder(original).build();

		assertEquals(original.cron(), copied.cron());
		assertEquals(original.backupType(), copied.backupType());
		assertEquals(original.retention(), copied.retention());
	}

	@Test
	@DisplayName("Static constants should have correct default values")
	void shouldHaveCorrectDefaultConstants() {
		assertEquals(3, BackupScheduleOptions.DEFAULT_RETENTION);
		assertEquals(BackupType.SNAPSHOT, BackupScheduleOptions.DEFAULT_BACKUP_TYPE);
	}

	@Test
	@DisplayName("Nullable constructor should apply defaults for null values")
	void shouldApplyDefaultsForNullValues() {
		final BackupScheduleOptions options = new BackupScheduleOptions(
			"0 0 2 * * *",
			null,
			null
		);

		assertEquals("0 0 2 * * *", options.cron());
		assertEquals(BackupType.SNAPSHOT, options.backupType());
		assertEquals(3, options.retention());
	}

	@Test
	@DisplayName("Should accept valid cron expressions with various patterns")
	void shouldAcceptValidCronExpressions() {
		// Every hour at minute 0
		assertDoesNotThrow(() -> BackupScheduleOptions.builder().cron("0 0 * * * *").build());
		// Every 10 minutes
		assertDoesNotThrow(() -> BackupScheduleOptions.builder().cron("0 */10 * * * *").build());
		// 6 AM and 7 PM every day
		assertDoesNotThrow(() -> BackupScheduleOptions.builder().cron("0 0 6,19 * * *").build());
		// Every hour 9-17 on weekdays
		assertDoesNotThrow(() -> BackupScheduleOptions.builder().cron("0 0 9-17 * * MON-FRI").build());
		// Christmas Day at midnight
		assertDoesNotThrow(() -> BackupScheduleOptions.builder().cron("0 0 0 25 12 *").build());
	}

}
