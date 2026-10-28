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

package io.evitadb.index.mutation.local;

import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;

/**
 * What one root entity mutation knows about the partitions without an index object that its mutations write: the
 * primary keys of the partitions it has vacated, reserved for an owner entering the same partition again, and whether
 * the root mutation is finishing - applying the changes of its executors - so that no owner may enter a partition any
 * more.
 *
 * # Reservations
 *
 * A partition represented by an index object keeps the object after its last owner has left it until the root
 * mutation finishes and sweeps the empty indexes away, so an owner entering the partition again within the same root
 * mutation - the same entity re-inserting the reference, or another entity entering the partition the first one left -
 * finds the object and keeps its primary key. A partition represented only by its registry row loses the row when its
 * last owner leaves, so the primary key is reserved here instead, under the entity type, the scope and the
 * representative reference key of the partition: the next owner entering a partition of the key takes it, and the
 * partition keeps the very primary key the index object would have kept. A reservation nobody takes is dropped when the
 * root mutation finishes, exactly as the empty index object would have been swept.
 *
 * Every root mutation has its own instance, shared by the executors of its nested mutations, so a reservation never
 * outlives the root mutation and is never seen by another one. Like the executors it is not thread-safe.
 *
 * # Finishing
 *
 * The root mutation finishes by applying the changes of every executor it ran - the locale finalization, the removal
 * of an entity removed entirely, the demotion of partitions left with one owner. None of that brings an owner into a
 * partition, so an owner entering one while the root mutation finishes means the ordering the root mutation relies on
 * is broken; the acquisition of a partition for an entering owner asks {@link #isFinishing()} to refuse it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class PartitionReservations {
	/**
	 * Returned by {@link #take(String, Scope, RepresentativeReferenceKey)} when no primary key is reserved for the
	 * partition. Index primary keys are positive.
	 */
	public static final int NO_RESERVATION = 0;

	/**
	 * The reservations of an executor that must never vacate a partition or bring an owner into one - the executor
	 * writing as another owner of a partition (see
	 * {@link EntityIndexLocalMutationExecutor#createExecutorOfOtherOwner(int)}). Every operation fails.
	 */
	public static final PartitionReservations REFUSING = new PartitionReservations(true);

	/**
	 * TRUE when every operation fails - see {@link #REFUSING}.
	 */
	private final boolean refusing;
	/**
	 * The reserved primary keys by the partition they belong to; allocated by the first reservation, which most root
	 * mutations never make.
	 */
	@Nullable private Map<ReservationKey, Integer> reserved;
	/**
	 * TRUE while the root mutation applies the changes of its executors.
	 */
	private boolean finishing;

	/**
	 * Creates the reservations of one root mutation.
	 */
	public PartitionReservations() {
		this(false);
	}

	/**
	 * Creates the reservations, refusing every operation when asked to.
	 *
	 * @param refusing TRUE to fail every operation
	 */
	private PartitionReservations(boolean refusing) {
		this.refusing = refusing;
	}

	/**
	 * Reserves the primary key of a partition whose last owner has just left it, for the next owner entering a
	 * partition of the same key within the root mutation.
	 *
	 * @param entityType          the entity type owning the reference of the partition
	 * @param scope               the scope of the partition
	 * @param key                 the representative reference key of the partition
	 * @param partitionPrimaryKey the primary key of the partition
	 * @throws GenericEvitaInternalError when the reservations refuse every operation, or a primary key is reserved for
	 *                                   the key already - a partition of the key was vacated twice without an owner
	 *                                   entering it in between
	 */
	public void reserve(
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key,
		int partitionPrimaryKey
	) {
		if (this.refusing) {
			throw refused("reserve the primary key of partition " + partitionPrimaryKey + " `" + key + "`");
		}
		if (this.reserved == null) {
			this.reserved = CollectionUtils.createHashMap(4);
		}
		final Integer previous = this.reserved.putIfAbsent(
			new ReservationKey(entityType, scope, key), partitionPrimaryKey
		);
		if (previous != null) {
			throw new GenericEvitaInternalError(
				"Partition `" + key + "` in scope `" + scope + "` of entity type `" + entityType + "` was vacated as " +
					partitionPrimaryKey + " while primary key " + previous + " is still reserved for it - no owner " +
					"entered the partition in between!"
			);
		}
	}

	/**
	 * Takes the primary key reserved for a partition of the key, so that the owner entering it keeps the primary key
	 * the partition had. The reservation is consumed.
	 *
	 * @param entityType the entity type owning the reference of the partition
	 * @param scope      the scope of the partition
	 * @param key        the representative reference key of the partition
	 * @return the reserved primary key, or {@link #NO_RESERVATION} when none is reserved for the key
	 * @throws GenericEvitaInternalError when the reservations refuse every operation
	 */
	public int take(@Nonnull String entityType, @Nonnull Scope scope, @Nonnull RepresentativeReferenceKey key) {
		if (this.refusing) {
			throw refused("take the primary key reserved for partition `" + key + "`");
		}
		if (this.reserved == null) {
			return NO_RESERVATION;
		}
		final Integer primaryKey = this.reserved.remove(new ReservationKey(entityType, scope, key));
		return primaryKey == null ? NO_RESERVATION : primaryKey;
	}

	/**
	 * Returns the primary key reserved for a partition of the key without consuming it.
	 *
	 * @param entityType the entity type owning the reference of the partition
	 * @param scope      the scope of the partition
	 * @param key        the representative reference key of the partition
	 * @return the reserved primary key, or {@link #NO_RESERVATION} when none is reserved for the key
	 * @throws GenericEvitaInternalError when the reservations refuse every operation
	 */
	public int peek(@Nonnull String entityType, @Nonnull Scope scope, @Nonnull RepresentativeReferenceKey key) {
		if (this.refusing) {
			throw refused("look up the primary key reserved for partition `" + key + "`");
		}
		if (this.reserved == null) {
			return NO_RESERVATION;
		}
		final Integer primaryKey = this.reserved.get(new ReservationKey(entityType, scope, key));
		return primaryKey == null ? NO_RESERVATION : primaryKey;
	}

	/**
	 * Returns TRUE while the root mutation applies the changes of its executors - no owner may enter a partition then.
	 *
	 * @return TRUE while the root mutation is finishing
	 * @throws GenericEvitaInternalError when the reservations refuse every operation
	 */
	public boolean isFinishing() {
		if (this.refusing) {
			throw refused("ask whether the root mutation is finishing");
		}
		return this.finishing;
	}

	/**
	 * Marks the start and the end of the application of the changes of the root mutation's executors.
	 *
	 * @param finishing TRUE when the application starts, FALSE when it ends
	 * @throws GenericEvitaInternalError when the reservations refuse every operation
	 */
	public void setFinishing(boolean finishing) {
		if (this.refusing) {
			throw refused("mark the root mutation as finishing");
		}
		this.finishing = finishing;
	}

	/**
	 * Drops every reservation - the root mutation has finished, committed or rolled back, and the primary keys nobody
	 * took belong to partitions that no longer exist.
	 */
	public void clear() {
		this.reserved = null;
		this.finishing = false;
	}

	/**
	 * Creates the error of an operation the reservations refuse - see {@link #REFUSING}. Called only when the operation
	 * is refused, so the valid path builds no message.
	 *
	 * @param operation the operation refused, for the error message
	 * @return the error to throw
	 */
	@Nonnull
	private static GenericEvitaInternalError refused(@Nonnull String operation) {
		return new GenericEvitaInternalError(
			"An executor writing as another owner of a partition must not " + operation + "!"
		);
	}

	/**
	 * Identifies the partition a primary key is reserved for.
	 *
	 * @param entityType the entity type owning the reference of the partition
	 * @param scope      the scope of the partition
	 * @param key        the representative reference key of the partition
	 */
	private record ReservationKey(
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key
	) {
	}

}
