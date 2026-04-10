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

package io.evitadb.spi.store.catalog.persistence;


import io.evitadb.core.buffer.TrappedChanges;

import javax.annotation.Nonnull;
import java.util.function.IntConsumer;

/**
 * Intermediate sealed interface in the persistence service hierarchy that adds bulk-flush capability on top of the
 * basic lifecycle contract from {@link PersistenceService}. Implementations manage complex in-memory data structures
 * such as entity indexes and storage parts that may accumulate changes across many short-lived transactions before
 * being persisted in a single consolidated write.
 *
 * The two permitted sub-interfaces cover the two kinds of rich storage in evitaDB:
 * - {@link CatalogPersistenceService} — catalog-level data (catalog schema, catalog index, WAL, collection registry)
 * - {@link EntityCollectionPersistenceService} — entity-level data (entity bodies, attribute/price/reference indexes)
 *
 * The {@link #flushTrappedUpdates} method distinguishes this interface from a plain `StoragePartPersistenceService`:
 * it is specifically designed for the *bulk-write* code path where a large number of in-memory changes have already
 * been logically committed and need to be persisted as efficiently as possible.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public sealed interface RichPersistenceService extends PersistenceService
	permits CatalogPersistenceService, EntityCollectionPersistenceService {

	/**
	 * Writes all {@link TrappedChanges} collected during a bulk-write phase (e.g. during initial catalog population
	 * or WAL replay) directly to the underlying persistent storage file. Unlike the transactional write path this
	 * method bypasses the transactional memory layer: it is intended for situations where changes have already been
	 * logically committed and only the physical persistence step is pending.
	 *
	 * The progress of the flush is reported through `trappedUpdatedProgress`, which receives the count of storage
	 * parts written so far. This allows callers to update a progress indicator for long-running operations.
	 *
	 * @param catalogVersion        the catalog version these trapped changes belong to; used to label the flushed
	 *                              records so that concurrent readers see a consistent snapshot
	 * @param trappedChanges        the pending
	 *                              {@link io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart} changes
	 *                              accumulated since the last flush
	 * @param trappedUpdatedProgress callback that receives the running count of storage parts flushed; invoked at
	 *                              least once per batch to enable progress reporting
	 */
	void flushTrappedUpdates(
		long catalogVersion,
		@Nonnull TrappedChanges trappedChanges,
		@Nonnull IntConsumer trappedUpdatedProgress
	);

}
