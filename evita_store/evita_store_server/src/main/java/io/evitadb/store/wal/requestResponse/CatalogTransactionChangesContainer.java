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

package io.evitadb.store.wal.requestResponse;


import io.evitadb.api.requestResponse.system.WriteAheadLogVersionDescriptor.TransactionChanges;
import io.evitadb.api.requestResponse.system.WriteAheadLogVersionDescriptor.TransactionChangesContainer;
import io.evitadb.store.wal.requestResponse.CatalogTransactionChangesContainer.CatalogTransactionChanges;
import io.evitadb.utils.StringUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Specialized implementation of {@link TransactionChangesContainer} that holds catalog transaction changes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class CatalogTransactionChangesContainer implements TransactionChangesContainer<CatalogTransactionChanges> {
	@Getter private final CatalogTransactionChanges[] transactionChanges;

	/**
	 * Calculates the total number of catalog schema changes by aggregating the catalogSchemaChanges value from all
	 * CatalogTransactionChanges objects in the transactionChanges array.
	 *
	 * @return the total number of catalog schema changes
	 */
	public int catalogSchemaChanges() {
		return Arrays.stream(this.transactionChanges)
		             .mapToInt(CatalogTransactionChanges::catalogSchemaChanges)
		             .sum();
	}

	/**
	 * Collect entity changes from all transactions and aggregate them so that they represent overall changes in
	 * the catalog of a particular version.
	 *
	 * @return aggregated entity changes
	 */
	@Nonnull
	public EntityCollectionChanges[] entityCollectionChanges() {
		return Arrays.stream(this.transactionChanges)
		             .flatMap(tc -> Arrays.stream(tc.entityCollectionChanges()))
		             .collect(Collectors.toMap(
			             EntityCollectionChanges::entityName,
			             ec -> ec,
			             EntityCollectionChanges::mergeWith
		             ))
		             .values()
		             .toArray(EntityCollectionChanges[]::new);
	}

	@Override
	public String toString() {
		return "(" + (catalogSchemaChanges() > 0 ? catalogSchemaChanges() + " catalog schema changes, " : "") +
			mutationCount() + " mutations, " + StringUtils.formatByteSize(mutationSizeInBytes()) + "):\n" +
			Arrays.stream(entityCollectionChanges())
			      .map(ec -> "\t - " + ec.toString())
			      .collect(Collectors.joining("\n"));
	}

	/**
	 * Describes changes in particular transaction.
	 *
	 * @param commitTimestamp         timestamp when particular version was committed
	 * @param catalogSchemaChanges    number of catalog schema altering mutations
	 * @param mutationCount           number of mutations incorporated in this version
	 * @param mutationSizeInBytes     size of the mutations in bytes
	 * @param entityCollectionChanges description of changes in particular entity collections
	 */
	public record CatalogTransactionChanges(
		long catalogVersion,
		@Nonnull OffsetDateTime commitTimestamp,
		int catalogSchemaChanges,
		int mutationCount,
		long mutationSizeInBytes,
		@Nonnull EntityCollectionChanges[] entityCollectionChanges
	) implements TransactionChanges {

		@Nonnull
		@Override
		public String toString() {
			return toString(null);
		}

		@Nonnull
		@Override
		public String toString(@Nullable OffsetDateTime processedTimestamp) {
			return "Transaction to version: " + this.catalogVersion +
				", committed at " + DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(this.commitTimestamp) +
				(processedTimestamp != null ?
					"(processing lag " + StringUtils.formatDuration(processingLag(processedTimestamp)) + ")" :
					"") +
				"(" + (catalogSchemaChanges() > 0 ? catalogSchemaChanges() + " catalog schema changes, " : "") +
				mutationCount() + " mutations, " + StringUtils.formatByteSize(mutationSizeInBytes()) + "):\n" +
				Arrays.stream(entityCollectionChanges())
				      .map(ec -> "\t - " + ec.toString())
				      .collect(Collectors.joining("\n"));
		}

	}

}
