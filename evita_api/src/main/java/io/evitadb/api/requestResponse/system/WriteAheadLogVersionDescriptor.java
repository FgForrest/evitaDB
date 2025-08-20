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

package io.evitadb.api.requestResponse.system;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * This record describes changes incorporated in a particular version of the catalog. It contains information about
 * the version number, when it was processed, and details about the transactions that were included in this version.
 *
 * The descriptor is used to track and report changes made to the catalog through the Write-Ahead Log (WAL) mechanism,
 * which ensures data durability and consistency.
 *
 * @param version            version number of the catalog
 * @param processedTimestamp timestamp when this particular version was created and processed
 * @param transactionChanges container with descriptions of all transactions incorporated in this catalog version
 */
public record WriteAheadLogVersionDescriptor(
	long version,
	@Nonnull OffsetDateTime processedTimestamp,
	@Nonnull TransactionChangesContainer<? extends TransactionChanges> transactionChanges
) implements Serializable {

	@Nonnull
	@Override
	public String toString() {
		return "Catalog version: " + this.version +
			", processed at " + DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(this.processedTimestamp) +
			" with " + this.transactionChanges.getTransactionChanges().length + " transactions " +
			this.transactionChanges;
	}

	/**
	 * This interface represents a container for transaction changes that are part of a catalog version.
	 * It provides methods to access the transaction changes and calculate aggregate statistics about them,
	 * such as the total size of mutations and the total count of mutations.
	 *
	 * @param <T> the type of transaction changes contained in this container, must implement TransactionChanges interface
	 */
	public interface TransactionChangesContainer<T extends TransactionChanges> {

		/**
		 * Retrieves an array of transaction changes that are part of this catalog version.
		 * Each element in the array represents changes made in a single transaction.
		 *
		 * @return an array of transaction changes contained in this version
		 */
		@Nonnull
		T[] getTransactionChanges();

		/**
		 * Calculates and returns the total size in bytes of all mutations incorporated in this catalog version.
		 * This is the sum of the sizes of all mutations across all transactions in this version.
		 *
		 * @return the total size in bytes of all mutations in this catalog version
		 */
		default long mutationSizeInBytes() {
			return Arrays.stream(getTransactionChanges())
			             .mapToLong(TransactionChanges::mutationSizeInBytes)
			             .sum();
		}

		/**
		 * Calculates and returns the total number of mutations incorporated in this catalog version.
		 * This is the sum of the mutation counts across all transactions in this version.
		 *
		 * @return the total number of mutations in this catalog version
		 */
		default int mutationCount() {
			return Arrays.stream(getTransactionChanges())
			             .mapToInt(TransactionChanges::mutationCount)
			             .sum();
		}

	}

	/**
	 * This interface describes changes made in a particular transaction within the Write-Ahead Log.
	 * It provides methods to access information about when the transaction was committed,
	 * how many mutations it contained, and the size of those mutations.
	 *
	 * Implementations of this interface should provide details about specific types of changes
	 * that can occur in a transaction, such as catalog schema changes or entity collection changes.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
	 */
	public interface TransactionChanges extends Serializable {

		/**
		 * Calculates and returns the processing lag between when the transaction was committed and when it was processed.
		 *
		 * This represents how long it took for the changes to become visible to all clients after the transaction
		 * was committed. A shorter lag indicates better system performance and responsiveness.
		 *
		 * @param processedTimestamp the timestamp when the transaction was processed
		 * @return the duration representing the processing lag between commit and processed timestamps
		 */
		@Nonnull
		default Duration processingLag(@Nonnull OffsetDateTime processedTimestamp) {
			return Duration.between(this.commitTimestamp(), processedTimestamp);
		}

		/**
		 * Returns a string representation of the transaction changes, optionally including processing lag information
		 * if a processed timestamp is provided.
		 *
		 * @param processedTimestamp the timestamp when the transaction was processed, can be null
		 * @return a string representation of the transaction changes
		 */
		@Nonnull
		String toString(@Nullable OffsetDateTime processedTimestamp);

		/**
		 * Returns the timestamp when this transaction was committed to the Write-Ahead Log.
		 *
		 * @return the commit timestamp of this transaction
		 */
		@Nonnull
		OffsetDateTime commitTimestamp();

		/**
		 * Returns the total number of mutations (changes) that were included in this transaction.
		 *
		 * @return the count of mutations in this transaction
		 */
		int mutationCount();

		/**
		 * Returns the total size in bytes of all mutations included in this transaction.
		 * This can be used to estimate the memory footprint or storage requirements.
		 *
		 * @return the size in bytes of all mutations in this transaction
		 */
		long mutationSizeInBytes();

	}

}
