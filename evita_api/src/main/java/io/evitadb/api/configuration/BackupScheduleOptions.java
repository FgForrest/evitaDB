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

import io.evitadb.cron.CronSchedule;
import io.evitadb.utils.Assert;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Backup scheduling options define how automated backups should be scheduled and managed.
 * Each instance represents a single backup schedule that will run according to the specified
 * cron expression, creating backups of the specified type and maintaining the configured
 * number of backup copies.
 *
 * ## Cron Expression Format
 *
 * Uses the 6-field cron format: second minute hour day-of-month month day-of-week
 *
 * Examples:
 *
 * - 0 0 2 &#42; &#42; &#42; - Every day at 2:00 AM
 * - 0 0 &#42;/4 &#42; &#42; &#42; - Every 4 hours
 * - 0 0 0 &#42; &#42; SUN - Every Sunday at midnight
 *
 * @param cron       The cron expression defining when backups should be executed.
 *                   Uses 6-field format: second minute hour day-of-month month day-of-week.
 *                   See {@link CronSchedule} for supported syntax.
 * @param backupType The type of backup to perform - either FULL or SNAPSHOT.
 * @param retention  The number of backup copies to retain. Older backups exceeding this
 *                   count will be automatically removed. Must be a positive integer.
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record BackupScheduleOptions(
	@Nonnull String cron,
	@Nonnull BackupType backupType,
	int retention
) {
	public static final int DEFAULT_RETENTION = 3;
	public static final BackupType DEFAULT_BACKUP_TYPE = BackupType.SNAPSHOT;

	/**
	 * Builder for the backup schedule options. Recommended to use to avoid binary compatibility
	 * problems in the future.
	 */
	public static BackupScheduleOptions.Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for the backup schedule options. Recommended to use to avoid binary compatibility
	 * problems in the future.
	 */
	public static BackupScheduleOptions.Builder builder(@Nonnull BackupScheduleOptions options) {
		return new Builder(options);
	}

	/**
	 * Constructor with nullable parameters for YAML deserialization.
	 * Applies defaults for null values.
	 */
	public BackupScheduleOptions(
		@Nonnull String cron,
		@Nullable BackupType backupType,
		@Nullable Integer retention
	) {
		this(
			cron,
			backupType == null ? DEFAULT_BACKUP_TYPE : backupType,
			retention == null ? DEFAULT_RETENTION : retention
		);
	}

	/**
	 * Canonical constructor with validation.
	 */
	public BackupScheduleOptions {
		Assert.notNull(cron, "Cron expression must not be null");
		Assert.isTrue(
			CronSchedule.isValid(cron),
			"Invalid cron expression: " + cron
		);
		Assert.isTrue(
			retention > 0,
			"Retention must be a positive integer, got: " + retention
		);
	}

	/**
	 * Standard builder pattern implementation.
	 */
	@ToString
	public static class Builder {
		private String cron;
		private BackupType backupType = DEFAULT_BACKUP_TYPE;
		private int retention = DEFAULT_RETENTION;

		Builder() {
		}

		Builder(@Nonnull BackupScheduleOptions options) {
			this.cron = options.cron();
			this.backupType = options.backupType();
			this.retention = options.retention();
		}

		@Nonnull
		public BackupScheduleOptions.Builder cron(@Nonnull String cron) {
			this.cron = cron;
			return this;
		}

		@Nonnull
		public BackupScheduleOptions.Builder backupType(@Nonnull BackupType backupType) {
			this.backupType = backupType;
			return this;
		}

		@Nonnull
		public BackupScheduleOptions.Builder retention(int retention) {
			this.retention = retention;
			return this;
		}

		@Nonnull
		public BackupScheduleOptions build() {
			Assert.notNull(this.cron, "Cron expression must be specified");
			return new BackupScheduleOptions(
				this.cron,
				this.backupType,
				this.retention
			);
		}
	}

}
