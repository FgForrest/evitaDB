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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ScheduleOptions} configuration.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ScheduleOptions configuration tests")
class ScheduleOptionsTest {

	@Test
	@DisplayName("Builder should initialize with empty backup schedule by default")
	void shouldInitializeWithEmptyBackupSchedule() {
		final ScheduleOptions options = ScheduleOptions.builder().build();

		assertNotNull(options.backup());
		assertTrue(options.backup().isEmpty());
	}

	@Test
	@DisplayName("Builder should allow setting backup schedule list")
	void shouldSetBackupScheduleViaBuilder() {
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

		final ScheduleOptions options = ScheduleOptions.builder()
			.backup(backups)
			.build();

		assertEquals(2, options.backup().size());
		assertEquals("0 0 2 * * *", options.backup().get(0).cron());
		assertEquals(BackupType.FULL, options.backup().get(0).backupType());
		assertEquals(7, options.backup().get(0).retention());
		assertEquals("0 0 */4 * * *", options.backup().get(1).cron());
		assertEquals(BackupType.SNAPSHOT, options.backup().get(1).backupType());
		assertEquals(3, options.backup().get(1).retention());
	}

	@Test
	@DisplayName("Copy constructor should copy all values")
	void shouldCopyAllValuesFromExistingOptions() {
		final List<BackupScheduleOptions> backups = List.of(
			BackupScheduleOptions.builder()
				.cron("0 0 3 * * *")
				.backupType(BackupType.FULL)
				.retention(5)
				.build()
		);

		final ScheduleOptions original = ScheduleOptions.builder()
			.backup(backups)
			.build();

		final ScheduleOptions copied = ScheduleOptions.builder(original).build();

		assertEquals(original.backup().size(), copied.backup().size());
		assertEquals(original.backup().get(0).cron(), copied.backup().get(0).cron());
		assertEquals(original.backup().get(0).backupType(), copied.backup().get(0).backupType());
		assertEquals(original.backup().get(0).retention(), copied.backup().get(0).retention());
	}

	@Test
	@DisplayName("Static constants should have correct default values")
	void shouldHaveCorrectDefaultConstants() {
		assertNotNull(ScheduleOptions.DEFAULT_BACKUP_SCHEDULE);
		assertTrue(ScheduleOptions.DEFAULT_BACKUP_SCHEDULE.isEmpty());
	}

	@Test
	@DisplayName("Nullable constructor should apply defaults for null values")
	void shouldApplyDefaultsForNullValues() {
		final ScheduleOptions options = new ScheduleOptions(null);

		assertNotNull(options.backup());
		assertTrue(options.backup().isEmpty());
	}

	@Test
	@DisplayName("Constructor with null should initialize empty schedules")
	void shouldInitializeEmptySchedulesWithNullParameter() {
		final ScheduleOptions options = new ScheduleOptions(null);

		assertNotNull(options.backup());
		assertTrue(options.backup().isEmpty());
	}

	@Test
	@DisplayName("Backup schedule list should be immutable")
	void shouldReturnImmutableBackupScheduleList() {
		final ScheduleOptions options = ScheduleOptions.builder()
			.backup(List.of(
				BackupScheduleOptions.builder()
					.cron("0 0 2 * * *")
					.build()
			))
			.build();

		assertThrows(UnsupportedOperationException.class, () ->
			options.backup().add(
				BackupScheduleOptions.builder()
					.cron("0 0 3 * * *")
					.build()
			)
		);
	}

}
