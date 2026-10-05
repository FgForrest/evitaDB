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

package io.evitadb.index.mutation.storagePart;

import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Finds the storage-part accessor of an entity that is being mutated **within the same root entity mutation** as the
 * caller, so that a read of another entity's storage parts sees what that entity's mutation has already applied.
 *
 * A root entity mutation and every implicit or reflected mutation it spawns - in its own collection or in another one
 * - run one {@link ContainerizedLocalMutationExecutor} per entity, and none of them writes its storage parts into the
 * data store buffer until the root mutation commits, which happens only after every executor has finished. Until then
 * the applied state of an entity lives solely in its own executor's cache. A read that went to the data store reader
 * instead would see the entity as it was before the root mutation - or, when the part happens to be a trapped buffer
 * entry the executor mutates in place, the in-flight state by accident of aliasing. Asking this lookup first makes the
 * answer independent of the part's history.
 *
 * The lookup is scoped to one root mutation: it answers only while that mutation is in flight, and it never finds an
 * executor of another root mutation, committed or not. Like the executors it hands out it is not thread-safe and is
 * consulted only from the thread running the mutation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@FunctionalInterface
public interface InFlightEntityAccessorLookup {

	/**
	 * Returns the accessor of the executor mutating the given entity within the current root mutation. The accessor
	 * owns the entity, so asking it for a part of exactly this entity type and primary key answers from its own
	 * applied (cached) state and falls back to the data store reader only for parts it has not touched yet. It is
	 * returned as the executor's writable accessor because that is the only view knowing the entity's pending locale
	 * changes - see {@link WritableEntityStorageContainerAccessor#getAccessorOfOtherEntity(int)} - and a caller must
	 * not change the entity through it.
	 *
	 * @param entityType       the entity collection name
	 * @param entityPrimaryKey the primary key of the entity
	 * @return the accessor of the in-flight executor, or `null` when the current root mutation does not mutate the
	 *         entity
	 */
	@Nullable
	WritableEntityStorageContainerAccessor findInFlightAccessor(@Nonnull String entityType, int entityPrimaryKey);

}
