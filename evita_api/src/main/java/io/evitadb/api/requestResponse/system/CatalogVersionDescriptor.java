/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.api.requestResponse.system;

import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Objects describes changes incorporated in particular version of the catalog.
 *
 * @param version                 version of the catalog
 * @param processedTimestamp      timestamp when particular version was created
 * @param transactionChanges      description of changes in transactions incorporated in this catalog version
 */
public record CatalogVersionDescriptor(
	long version,
	@Nonnull OffsetDateTime processedTimestamp,
	@Nonnull TransactionChanges[] transactionChanges

) implements Serializable {

	/**
	 * Returns overall size of the mutations incorporated in this version.
	 * @return overall size of the mutations incorporated in this version
	 */
	public long mutationSizeInBytes() {
		return Arrays.stream(transactionChanges)
			.mapToLong(TransactionChanges::mutationSizeInBytes)
			.sum();
	}

	/**
	 * Returns overall number of mutations incorporated in this version.
	 * @return overall number of mutations incorporated in this version
	 */
	public int mutationCount() {
		return Arrays.stream(transactionChanges)
			.mapToInt(TransactionChanges::mutationCount)
			.sum();
	}

	/**
	 * Calculates the total number of catalog schema changes by aggregating the catalogSchemaChanges value from all
	 * TransactionChanges objects in the transactionChanges array.
	 *
	 * @return the total number of catalog schema changes
	 */
	public int catalogSchemaChanges() {
		return Arrays.stream(transactionChanges)
			.mapToInt(TransactionChanges::catalogSchemaChanges)
			.sum();
	}

	/**
	 * Collect entity changes from all transactions and aggregate them so that they represent overall changes in
	 * the catalog of particular version.
	 *
	 * @return aggregated entity changes
	 */
	@Nonnull
	public EntityCollectionChanges[] entityCollectionChanges() {
		return Arrays.stream(transactionChanges)
			.flatMap(tc -> Arrays.stream(tc.entityCollectionChanges))
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
		return "Catalog version: " + version +
			", processed at " + DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(processedTimestamp) +
			" with " + transactionChanges.length + " transactions " +
			"(" + (catalogSchemaChanges() > 0 ? catalogSchemaChanges() + " catalog schema changes, " : "") +
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
	public record TransactionChanges(
		long catalogVersion,
		@Nonnull OffsetDateTime commitTimestamp,
		int catalogSchemaChanges,
		int mutationCount,
		long mutationSizeInBytes,
		@Nonnull EntityCollectionChanges[] entityCollectionChanges
	) implements Serializable {

		/**
		 * Returns processing lag between commit and processed introducedAt. I.e. how long it took to incorporate changes to be
		 * visible by all clients since the transaction has been committed.
		 *
		 * @return processing lag between commit and processed introducedAt
		 */
		@Nonnull
		public Duration processingLag(@Nonnull OffsetDateTime processedTimestamp) {
			return Duration.between(commitTimestamp, processedTimestamp);
		}

		@Override
		public String toString() {
			return toString(null);
		}

		public String toString(@Nullable OffsetDateTime processedTimestamp) {
			return "Transaction to version: " + catalogVersion +
				", committed at " + DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(commitTimestamp) +
				(processedTimestamp != null ? "(processing lag " + StringUtils.formatDuration(processingLag(processedTimestamp)) + ")" : "") +
				"(" + (catalogSchemaChanges() > 0 ? catalogSchemaChanges() + " catalog schema changes, " : "") +
				mutationCount() + " mutations, " + StringUtils.formatByteSize(mutationSizeInBytes()) + "):\n" +
				Arrays.stream(entityCollectionChanges())
					.map(ec -> "\t - " + ec.toString())
					.collect(Collectors.joining("\n"));
		}

	}

	/**
	 * Describes changes in particular entity collection in a very generic way.
	 *
	 * @param entityName    name of the entity ({@link EntitySchema#getName()})
	 * @param schemaChanges number of schema altering mutations
	 * @param upserted      number of upserted entities
	 * @param removed       number of removed entities
	 */
	public record EntityCollectionChanges(
		@Nonnull String entityName,
		int schemaChanges,
		int upserted,
		int removed
	) implements Serializable {

		/**
		 * Merges the given {@link EntityCollectionChanges} with this instance.
		 *
		 * @param otherChanges the {@link EntityCollectionChanges} to merge
		 * @return a new {@link EntityCollectionChanges} instance representing the merged changes
		 * @throws IllegalArgumentException if the entity names of the two collections are not the same
		 */
		@Nonnull
		public EntityCollectionChanges mergeWith(@Nonnull EntityCollectionChanges otherChanges) {
			Assert.isPremiseValid(
				entityName.equals(otherChanges.entityName),
				"Entity name must be the same for merging changes"
			);
			return new EntityCollectionChanges(
				entityName,
				schemaChanges + otherChanges.schemaChanges,
				upserted + otherChanges.upserted,
				removed + otherChanges.removed
			);
		}

		@Override
		public String toString() {
			return "changes in `" + entityName + "`: " +
				(schemaChanges > 0 ? schemaChanges + " schema changes" : "") +
				(upserted > 0 ? (schemaChanges > 0 ? ", " : "") + upserted + " upserted entities" : "") +
				(removed > 0 ? (schemaChanges > 0 || upserted > 0 ? ", " : "") + removed + " removed entities" : "");
		}
	}

}
