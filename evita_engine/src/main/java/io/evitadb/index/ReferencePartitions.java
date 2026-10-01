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

package io.evitadb.index;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.buffer.DataStoreReader;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.PrimitiveIterator.OfInt;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * The single implementation of {@link ReferencePartition} resolution. Every resolver surface -
 * `EntityCollection`, `QueryPlanningContext`, the local mutation executor - delegates here and only chooses where
 * the indexes are read from: a collection's committed maps under its dirty buffer, a query's snapshot, a
 * `DataStoreReader` layering another collection's in-flight state, or a write acquisition that enrols what it
 * returns. The checks themselves - which objects are partitions at all, which belong to a scope and a reference, and
 * how the partitions of one target are found - exist only here.
 *
 * ## Lenient and strict resolution
 *
 * - **Lenient** (`…IfExists`, {@link #asPartition}): `null` or no element when the primary key names nothing, and
 *   also when it names an index of the other family (a {@link ReducedGroupEntityIndex}), of another scope or of
 *   another reference. This is for primary keys that come from an accelerator rather than from the index that owns
 *   them - `ReducedIndexMembership` entries may name an index that has since been dropped, or one of the group
 *   family.
 * - **Strict** (`require…`, `…OrThrow`): a primary key handed out by the index that owns the partition must
 *   resolve; anything else is a programming error reported as {@link GenericEvitaInternalError} naming the
 *   collection and the primary key.
 *
 * A by-key lookup and the primary keys a type index lists for one target are fixed to one family, scope and reference
 * by construction, so a mismatch there is always a programming error, in both modes.
 *
 * ## Primary keys are per collection
 *
 * Partition primary keys come from a per-collection sequence, so the same number names a different index in every
 * collection. Every method here reads one collection's indexes through the functions it is given - the caller is
 * responsible for passing the functions of the collection that handed the primary keys out.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class ReferencePartitions {
	/**
	 * Shared empty result of the batch resolutions.
	 */
	public static final ReferencePartition[] EMPTY_PARTITIONS = new ReferencePartition[0];

	private ReferencePartitions() {
		// static helper, never instantiated
	}

	/**
	 * Hands a partition to the query body channel - `TargetIndexes`, `ProcessingScope`, the `executeInContext`
	 * family and the sort index arrays - which is bound to {@link Index} and so accepts only a partition that is an
	 * index object. This is the single entry into that channel: every partition the query side resolves through the
	 * handle and then evaluates a body or a sorter against passes here. A partition that is not an index object has
	 * to be split off before this point - its arm will be missing here and the compiler will say so.
	 *
	 * @param partition the partition
	 * @return the index object the partition is
	 */
	@Nonnull
	public static ReducedEntityIndex toReducedEntityIndex(@Nonnull ReferencePartition partition) {
		return switch (partition) {
			case ReducedEntityIndex index -> index;
		};
	}

	/**
	 * Creates the key a partition is registered under.
	 *
	 * @param scope                      the scope of the partition
	 * @param representativeReferenceKey the representative reference key of the partition
	 * @return the key of the partition
	 */
	@Nonnull
	public static EntityIndexKey createKey(
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey representativeReferenceKey
	) {
		return new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, scope, representativeReferenceKey);
	}

	/**
	 * Narrows an index to a partition, leniently.
	 *
	 * @param index the index, possibly `null`
	 * @return the partition or `null` when the index is absent or not a reference partition
	 */
	@Nullable
	public static ReferencePartition asPartition(@Nullable Index<?> index) {
		return index instanceof ReducedEntityIndex partition ? partition : null;
	}

	/**
	 * Narrows an index to a partition of the given reference in the given scope, leniently.
	 *
	 * @param index         the index, possibly `null`
	 * @param scope         the scope the partition must live in
	 * @param referenceName the reference the partition must belong to
	 * @return the partition or `null` when the index is absent, not a reference partition, or a partition of another
	 * scope or reference
	 */
	@Nullable
	public static ReferencePartition asPartition(
		@Nullable Index<?> index,
		@Nonnull Scope scope,
		@Nonnull String referenceName
	) {
		final ReferencePartition partition = asPartition(index);
		return partition != null && belongsTo(partition, scope, referenceName) ? partition : null;
	}

	/**
	 * Tests whether the partition lives in the given scope and belongs to the given reference.
	 *
	 * @param partition     the partition
	 * @param scope         the scope
	 * @param referenceName the name of the reference
	 * @return true when the partition belongs to the reference in the scope
	 */
	public static boolean belongsTo(
		@Nonnull ReferencePartition partition,
		@Nonnull Scope scope,
		@Nonnull String referenceName
	) {
		return partition.getIndexKey().scope() == scope &&
			referenceName.equals(partition.getRepresentativeReferenceKey().referenceName());
	}

	/**
	 * Narrows an index resolved by a primary key that must name a partition.
	 *
	 * @param index               the index resolved by `partitionPrimaryKey`, possibly `null`
	 * @param entityType          the collection the primary key was resolved in, for the error message
	 * @param partitionPrimaryKey the primary key the index was resolved by, for the error message
	 * @return the partition
	 * @throws GenericEvitaInternalError when the index is absent or not a reference partition
	 */
	@Nonnull
	public static ReferencePartition requirePartition(
		@Nullable Index<?> index,
		@Nonnull String entityType,
		int partitionPrimaryKey
	) {
		final ReferencePartition partition = asPartition(index);
		if (partition == null) {
			throw new GenericEvitaInternalError(
				"Expected a reference partition (" + ReducedEntityIndex.class.getSimpleName() + ") with primary key " +
					partitionPrimaryKey + " in collection `" + entityType + "` but got " +
					(index == null ? "NULL" : index.getClass()) + "!"
			);
		}
		return partition;
	}

	/**
	 * Resolves the partition of a representative reference key, leniently.
	 *
	 * @param indexByKey                 lookup of the collection's indexes by key, answering `null` when absent
	 * @param scope                      the scope of the partition
	 * @param representativeReferenceKey the representative reference key of the partition
	 * @return the partition or `null` when the collection holds none under the key
	 * @throws GenericEvitaInternalError when the key holds an index that is not a reference partition
	 */
	@Nullable
	public static ReferencePartition resolveIfExists(
		@Nonnull Function<EntityIndexKey, ? extends Index<?>> indexByKey,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey representativeReferenceKey
	) {
		final EntityIndexKey key = createKey(scope, representativeReferenceKey);
		return narrowByKey(indexByKey.apply(key), key);
	}

	/**
	 * Resolves the partition of a representative reference key through another collection's data store reader,
	 * leniently. The reader layers the in-flight indexes of that collection's current mutation over its committed
	 * maps, which a read of the collection's maps alone would miss.
	 *
	 * @param dataStoreReader            the reader of the collection owning the partition
	 * @param scope                      the scope of the partition
	 * @param representativeReferenceKey the representative reference key of the partition
	 * @return the partition or `null` when the collection holds none under the key
	 * @throws GenericEvitaInternalError when the key holds an index that is not a reference partition
	 */
	@Nullable
	public static ReferencePartition resolveIfExists(
		@Nonnull DataStoreReader dataStoreReader,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey representativeReferenceKey
	) {
		return resolveIfExists(indexByKey(dataStoreReader), scope, representativeReferenceKey);
	}

	/**
	 * Resolves the partitions of the given primary keys, strictly, in the ascending order of the primary keys.
	 *
	 * @param indexByPrimaryKey    lookup of the collection's indexes by primary key, answering `null` when absent
	 * @param partitionPrimaryKeys primary keys that must all name partitions of the collection
	 * @param entityType           the collection the primary keys belong to, for the error message
	 * @return the partitions, one per primary key
	 * @throws GenericEvitaInternalError when a primary key names no partition
	 */
	@Nonnull
	public static ReferencePartition[] resolveAll(
		@Nonnull IntFunction<? extends Index<?>> indexByPrimaryKey,
		@Nonnull Bitmap partitionPrimaryKeys,
		@Nonnull String entityType
	) {
		if (partitionPrimaryKeys.isEmpty()) {
			return EMPTY_PARTITIONS;
		}
		final ReferencePartition[] result = new ReferencePartition[partitionPrimaryKeys.size()];
		final OfInt it = partitionPrimaryKeys.iterator();
		int index = 0;
		while (it.hasNext()) {
			final int partitionPrimaryKey = it.nextInt();
			result[index++] = requirePartition(
				indexByPrimaryKey.apply(partitionPrimaryKey), entityType, partitionPrimaryKey
			);
		}
		return result;
	}

	/**
	 * Resolves every partition of one target entity of a reference in a scope, leniently: a missing type index or
	 * partition yields no element, and so does a primary key the type index lists but the collection no longer holds.
	 *
	 * The lookup depends on the reference cardinality. When duplicates are allowed, one target may be covered by
	 * several partitions (one per combination of representative attribute values), so they are enumerated through
	 * the `REFERENCED_ENTITY_TYPE` index of the reference; otherwise there is at most one and it is addressed directly
	 * by the key built from the reference key with no representative values.
	 *
	 * @param indexByKey        lookup of the collection's indexes by key, answering `null` when absent
	 * @param indexByPrimaryKey lookup of the collection's indexes by primary key, answering `null` when absent
	 * @param scope             the scope of the partitions
	 * @param referenceName     the reference the partitions belong to
	 * @param allowsDuplicates  whether the reference allows duplicate references to one target
	 * @param targetPrimaryKey  the primary key of the target entity
	 * @return the partitions of the target, possibly empty
	 * @throws GenericEvitaInternalError when a key or a listed primary key names an index that is not a partition of
	 *                                   the reference in the scope
	 */
	@Nonnull
	public static ReferencePartition[] resolveOfTarget(
		@Nonnull Function<EntityIndexKey, ? extends Index<?>> indexByKey,
		@Nonnull IntFunction<? extends Index<?>> indexByPrimaryKey,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		boolean allowsDuplicates,
		int targetPrimaryKey
	) {
		return resolveOfTarget(
			indexByKey, indexByPrimaryKey, scope, referenceName, allowsDuplicates, targetPrimaryKey, null
		);
	}

	/**
	 * Resolves every partition of one target entity of a reference in a scope, strictly: a missing type index or
	 * partition still yields no element, but a primary key the type index lists must resolve to a partition. See
	 * {@link #resolveOfTarget(Function, IntFunction, Scope, String, boolean, int)} for the lookup.
	 *
	 * @param indexByKey        lookup of the collection's indexes by key, answering `null` when absent
	 * @param indexByPrimaryKey lookup of the collection's indexes by primary key, answering `null` when absent
	 * @param scope             the scope of the partitions
	 * @param referenceName     the reference the partitions belong to
	 * @param allowsDuplicates  whether the reference allows duplicate references to one target
	 * @param targetPrimaryKey  the primary key of the target entity
	 * @param entityType        the collection the partitions belong to, for the error message
	 * @return the partitions of the target, possibly empty
	 * @throws GenericEvitaInternalError when a listed primary key names no partition, or a key or a listed primary key
	 *                                   names an index that is not a partition of the reference in the scope
	 */
	@Nonnull
	public static ReferencePartition[] resolveOfTargetOrThrow(
		@Nonnull Function<EntityIndexKey, ? extends Index<?>> indexByKey,
		@Nonnull IntFunction<? extends Index<?>> indexByPrimaryKey,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		boolean allowsDuplicates,
		int targetPrimaryKey,
		@Nonnull String entityType
	) {
		return resolveOfTarget(
			indexByKey, indexByPrimaryKey, scope, referenceName, allowsDuplicates, targetPrimaryKey, entityType
		);
	}

	/**
	 * Resolves every partition of one target entity through another collection's data store reader, leniently - see
	 * {@link #resolveOfTarget(Function, IntFunction, Scope, String, boolean, int)}. The reader layers the in-flight
	 * indexes of that collection's current mutation over its committed maps.
	 *
	 * @param dataStoreReader  the reader of the collection owning the partitions
	 * @param scope            the scope of the partitions
	 * @param referenceName    the reference the partitions belong to
	 * @param allowsDuplicates whether the reference allows duplicate references to one target
	 * @param targetPrimaryKey the primary key of the target entity
	 * @return the partitions of the target, possibly empty
	 */
	@Nonnull
	public static ReferencePartition[] resolveOfTarget(
		@Nonnull DataStoreReader dataStoreReader,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		boolean allowsDuplicates,
		int targetPrimaryKey
	) {
		return resolveOfTarget(
			indexByKey(dataStoreReader),
			partitionPrimaryKey -> dataStoreReader.getIndexIfExists(partitionPrimaryKey, __ -> null),
			scope, referenceName, allowsDuplicates, targetPrimaryKey, null
		);
	}

	/**
	 * Shared body of the lenient and strict resolution of the partitions of one target.
	 *
	 * @param indexByKey        lookup of the collection's indexes by key, answering `null` when absent
	 * @param indexByPrimaryKey lookup of the collection's indexes by primary key, answering `null` when absent
	 * @param scope             the scope of the partitions
	 * @param referenceName     the reference the partitions belong to
	 * @param allowsDuplicates  whether the reference allows duplicate references to one target
	 * @param targetPrimaryKey  the primary key of the target entity
	 * @param strictEntityType  the collection the partitions belong to when a listed primary key must resolve,
	 *                          `null` when a missing one is skipped
	 * @return the partitions of the target, possibly empty
	 */
	@Nonnull
	private static ReferencePartition[] resolveOfTarget(
		@Nonnull Function<EntityIndexKey, ? extends Index<?>> indexByKey,
		@Nonnull IntFunction<? extends Index<?>> indexByPrimaryKey,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		boolean allowsDuplicates,
		int targetPrimaryKey,
		@Nullable String strictEntityType
	) {
		if (allowsDuplicates) {
			// one target may be covered by several partitions - the type index of the reference lists all of them
			final EntityIndexKey typeIndexKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceName
			);
			final Index<?> typeIndex = indexByKey.apply(typeIndexKey);
			if (typeIndex == null) {
				return EMPTY_PARTITIONS;
			}
			Assert.isPremiseValid(
				typeIndex instanceof ReferencedTypeEntityIndex,
				() -> "Expected " + ReferencedTypeEntityIndex.class.getSimpleName() + " under `" + typeIndexKey +
					"` but got " + typeIndex.getClass() + "!"
			);
			final int[] partitionPrimaryKeys =
				((ReferencedTypeEntityIndex) typeIndex).getAllReferenceIndexes(targetPrimaryKey);
			if (partitionPrimaryKeys.length == 0) {
				return EMPTY_PARTITIONS;
			}
			final ReferencePartition[] result = new ReferencePartition[partitionPrimaryKeys.length];
			int count = 0;
			for (final int partitionPrimaryKey : partitionPrimaryKeys) {
				final Index<?> index = indexByPrimaryKey.apply(partitionPrimaryKey);
				if (index == null && strictEntityType == null) {
					// a primary key the collection no longer holds is tolerated by the lenient resolution
					continue;
				}
				final ReferencePartition partition = strictEntityType == null ?
					asPartition(index) : requirePartition(index, strictEntityType, partitionPrimaryKey);
				// the type index lists only partitions of its own scope and reference - anything else is corruption
				Assert.isPremiseValid(
					partition != null && belongsTo(partition, scope, referenceName),
					() -> "Type index `" + typeIndexKey + "` lists primary key " + partitionPrimaryKey +
						" for target " + targetPrimaryKey + ", which names " + index + " rather than a partition of " +
						"reference `" + referenceName + "` in scope `" + scope + "`!"
				);
				result[count++] = partition;
			}
			return count == result.length ? result : Arrays.copyOf(result, count);
		} else {
			// at most one partition per target, addressed by the reference key with no representative values
			final ReferencePartition partition = resolveIfExists(
				indexByKey, scope,
				new RepresentativeReferenceKey(
					new ReferenceKey(referenceName, targetPrimaryKey), ArrayUtils.EMPTY_SERIALIZABLE_ARRAY
				)
			);
			return partition == null ? EMPTY_PARTITIONS : new ReferencePartition[]{partition};
		}
	}

	/**
	 * Narrows the index a partition key resolved to.
	 *
	 * @param index the index registered under the key, possibly `null`
	 * @param key   the partition key
	 * @return the partition or `null` when nothing is registered under the key
	 */
	@Nullable
	private static ReferencePartition narrowByKey(@Nullable Index<?> index, @Nonnull EntityIndexKey key) {
		if (index == null) {
			return null;
		}
		final ReferencePartition partition = asPartition(index);
		Assert.isPremiseValid(
			partition != null,
			() -> "Expected a reference partition under `" + key + "` but got " + index.getClass() + "!"
		);
		return partition;
	}

	/**
	 * Adapts a data store reader to a by-key lookup answering `null` when the key holds nothing.
	 *
	 * @param dataStoreReader the reader
	 * @return the lookup
	 */
	@Nonnull
	private static Function<EntityIndexKey, Index<?>> indexByKey(@Nonnull DataStoreReader dataStoreReader) {
		return key -> dataStoreReader.getIndexIfExists(key, __ -> null);
	}

}
