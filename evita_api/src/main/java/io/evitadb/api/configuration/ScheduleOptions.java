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

import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/**
 * Container record for all scheduled task configurations. This record serves as a namespace
 * for different types of scheduled operations that can be configured in evitaDB.
 *
 * Currently supports:
 *
 * - **backup**: Automated backup schedules for creating periodic backups of catalogs
 *
 * Future extensions may include other scheduled tasks such as maintenance operations,
 * cleanup tasks, or administrative operations.
 *
 * @param backup List of backup schedule configurations. Each schedule defines when and how
 *               backups should be created. Schedules are applied server-wide to all catalogs.
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see BackupScheduleOptions
 */
public record ScheduleOptions(
	@Nonnull List<BackupScheduleOptions> backup
) {
	public static final List<BackupScheduleOptions> DEFAULT_BACKUP_SCHEDULE = Collections.emptyList();

	/**
	 * Builder for the schedule options. Recommended to use to avoid binary compatibility
	 * problems in the future.
	 */
	public static ScheduleOptions.Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for the schedule options. Recommended to use to avoid binary compatibility
	 * problems in the future.
	 */
	public static ScheduleOptions.Builder builder(@Nonnull ScheduleOptions options) {
		return new Builder(options);
	}

	/**
	 * Canonical constructor that ensures immutability and applies defaults for null values.
	 * This constructor is used for YAML deserialization.
	 */
	public ScheduleOptions(@Nullable List<BackupScheduleOptions> backup) {
		this.backup = backup == null ? DEFAULT_BACKUP_SCHEDULE : Collections.unmodifiableList(backup);
	}

	/**
	 * Standard builder pattern implementation.
	 */
	@ToString
	public static class Builder {
		private List<BackupScheduleOptions> backup = DEFAULT_BACKUP_SCHEDULE;

		Builder() {
		}

		Builder(@Nonnull ScheduleOptions options) {
			this.backup = options.backup();
		}

		@Nonnull
		public ScheduleOptions.Builder backup(@Nonnull List<BackupScheduleOptions> backup) {
			this.backup = backup;
			return this;
		}

		@Nonnull
		public ScheduleOptions build() {
			return new ScheduleOptions(
				this.backup
			);
		}
	}

}
