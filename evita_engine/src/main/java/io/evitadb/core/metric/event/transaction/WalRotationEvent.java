/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.core.metric.event.transaction;

import io.evitadb.api.observability.annotation.ExportDurationMetric;
import io.evitadb.api.observability.annotation.ExportInvocationMetric;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;

/**
 * Event that is fired when a shared WAL is rotated (and possibly pruned).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Name(AbstractTransactionEvent.PACKAGE_NAME + ".WalRotation")
@Description("Event that is fired when a shared WAL is rotated.")
@Label("WAL rotated")
@ExportDurationMetric(label = "WAL rotation duration in milliseconds")
@ExportInvocationMetric(label = "WAL rotations")
@Getter
public class WalRotationEvent extends AbstractWalStatisticsEvent {

	public WalRotationEvent() {
		this.begin();
	}

	public WalRotationEvent(@Nonnull String catalogName) {
		super(catalogName);
		this.begin();
	}

	/**
	 * Finalizes the event.
	 * @param oldestWalEntry the oldest WAL entry in the transaction
	 * @return the event
	 */
	@Nonnull
	public WalRotationEvent finish(@Nullable OffsetDateTime oldestWalEntry) {
		this.oldestWalEntryTimestampSeconds = oldestWalEntry == null ? 0 : oldestWalEntry.toEpochSecond();
		this.end();
		return this;
	}

}
