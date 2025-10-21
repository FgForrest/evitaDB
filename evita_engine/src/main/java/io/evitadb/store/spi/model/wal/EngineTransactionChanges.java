/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.store.spi.model.wal;


import io.evitadb.api.requestResponse.system.WriteAheadLogVersionDescriptor.TransactionChanges;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Describes changes in particular transaction.
 *
 * @param commitTimestamp     timestamp when particular version was committed
 * @param mutationCount       number of mutations incorporated in this version
 * @param walSizeInBytes      size of the mutations in bytes
 * @param changes             description of changes in the transaction
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record EngineTransactionChanges(
	long version,
	@Nonnull OffsetDateTime commitTimestamp,
	int mutationCount,
	long walSizeInBytes,
	@Nonnull String[] changes
) implements TransactionChanges {

	@Nonnull
	@Override
	public String toString() {
		return toString(null);
	}

	@Nonnull
	@Override
	public String toString(@Nullable OffsetDateTime processedTimestamp) {
		return "Transaction to version: " + this.version +
			", committed at " + DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(this.commitTimestamp) +
			(processedTimestamp != null ?
				"(processing lag " + StringUtils.formatDuration(processingLag(processedTimestamp)) + ")" : "") +
			mutationCount() + " mutations, " + StringUtils.formatByteSize(walSizeInBytes()) + "):\n" +
			String.join("\n", this.changes);
	}

}
