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

package io.evitadb.exception;

import io.evitadb.cron.CronSchedule;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when there is a problem with calculating the next execution time for a cron schedule.
 * This typically occurs when the {@link CronSchedule} configuration is invalid or when the cron expression
 * cannot be evaluated to determine the next scheduled execution time.
 *
 * This exception extends {@link EvitaInvalidUsageException} as it represents an error in how the cron
 * schedule is defined or used by the client.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class CronScheduleException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 7248270560477635902L;

	public CronScheduleException(@Nonnull CronSchedule cronSchedule) {
		super(
			"Failed to calculate next execution time for the cron schedule: " + cronSchedule,
			"Failed to calculate next execution time for the cron schedule."
		);
	}

}
