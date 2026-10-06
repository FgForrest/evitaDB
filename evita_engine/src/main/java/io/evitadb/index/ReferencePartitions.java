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
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.OptionalLong;
import java.util.PrimitiveIterator.OfInt;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * The single implementation of {@link ReferencePartition} resolution. Every resolver surface -
 * `EntityCollection`, `QueryPlanningContext`, {@link io.evitadb.index.mutation.IndexMutationTarget}, the local
 * mutation executor - delegates here and only chooses where the indexes are read from: a collection's committed maps
 * under its dirty buffer, the index maps a query was created with, a `DataStoreReader` layering another collection's
 * in-flight state, or a write acquisition that enrols what it returns. The checks themselves - which objects are
 * partitions at all, which belong to a scope and a reference, and how the partitions of one target are found - exist
 * only here.
 *
 * ## Lenient and strict resolution
 *
 * - **Lenient** (`…IfExists`, {@link #asPartition}): `null` or no element when the primary key names nothing, and
 *   also when it names an index of the other family (a {@link ReducedGroupEntityIndex}); {@link #belongsTo} narrows a
 *   partition further to one scope and reference. This is for primary keys that come from an accelerator rather than
 *   from the index that owns them - `ReducedIndexMembership` entries may name an index that has since been dropped, or
 *   one of the group family.
 * - **Strict** (`require…`, `…OrThrow`): a primary key handed out by the index that owns the partition must
 *   resolve; anything else is a programming error reported as {@link GenericEvitaInternalError} naming the
 *   collection and the primary key.
 *
 * A by-key lookup and the primary keys a type index lists for one target are fixed to one family, scope and reference
 * by construction, so a mismatch there is always a programming error, in both modes.
 *
 * ## Object-only and read resolution
 *
 * Every resolver above reads the index objects and nothing else: it answers with a {@link ReducedEntityIndex} or with
 * nothing, so the write path - and every query route not yet able to read a virtual partition - can never meet a
 * {@link VirtualReferencePartition}. The **read resolvers** (`…ForRead…`, {@link #resolveVirtualIfExists},
 * {@link #split}, and {@link PartitionKeyMemo} by key) take the referenced-type entity index of the reference in the
 * scope when the query reads the collection virtually, `null` when it does not: given one, they answer a primary key
 * its registry holds a row for with a virtual handle read out of that row and never touch the index object, and fall
 * back to the object for every other key; given `null`, they answer exactly as the object-only resolvers do. A write
 * path must not call them - every place on it that holds a partition asserts it is not virtual (see
 * {@link #requireMaterialized}), and a guard test rejects a call of a read resolver from the write path's packages.
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
	 * Shared empty result of the resolutions of the partitions of one target.
	 */
	public static final ReferencePartition[] EMPTY_PARTITIONS = new ReferencePartition[0];
	/**
	 * Shared empty column of the rows of a split with no virtual candidate.
	 */
	private static final long[] EMPTY_LONG_ARRAY = new long[0];
	/**
	 * Shared empty column of the type indexes of a split with no virtual candidate.
	 */
	private static final ReferencedTypeEntityIndex[] EMPTY_TYPE_INDEXES = new ReferencedTypeEntityIndex[0];

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
			case VirtualReferencePartition virtual -> throw new GenericEvitaInternalError(
				"Virtual reference partition " + virtual.getPrimaryKey() + " (`" + virtual.getIndexKey() + "`) " +
					"reached the index-bound query body channel - the query route that resolved it must split its " +
					"virtual partitions off before this point!"
			);
		};
	}

	/**
	 * Hands a partition to a query route that reads it as an index object and does not read virtual partitions yet -
	 * a sort reading the partition's own sort or chain index through the generic {@link EntityIndex} API. Such a route
	 * resolves its partitions through the object-only resolvers, so a virtual partition cannot reach it until the
	 * route switches to a read resolver, which it does in the step named; until then the virtual arm throws.
	 *
	 * @param partition   the partition
	 * @param route       names the query route, for the error message
	 * @param landingStep the step of #1615 that teaches the route to read virtual partitions
	 * @return the index object the partition is
	 * @throws GenericEvitaInternalError when the partition is virtual
	 */
	@Nonnull
	public static EntityIndex toEntityIndex(
		@Nonnull ReferencePartition partition,
		@Nonnull String route,
		@Nonnull String landingStep
	) {
		return switch (partition) {
			case ReducedEntityIndex index -> index;
			case VirtualReferencePartition virtual ->
				throw virtualPartitionOnUnmigratedRoute(virtual, route, landingStep);
		};
	}

	/**
	 * Asserts that a partition a write path holds is not virtual. A write reaches a partition only through its
	 * reduced entity index, and the object-only resolvers the write path uses never answer with anything else - a
	 * virtual partition here means a read resolver leaked into the write path, which this rejects before anything is
	 * written.
	 *
	 * @param partition the partition, possibly `null`
	 * @param writeSite names the write path holding the partition, for the error message
	 * @return the partition
	 * @throws GenericEvitaInternalError when the partition is virtual
	 */
	@Nullable
	public static ReferencePartition requireMaterialized(
		@Nullable ReferencePartition partition,
		@Nonnull String writeSite
	) {
		if (partition instanceof VirtualReferencePartition virtual) {
			throw virtualPartitionOnWritePath(virtual, writeSite);
		}
		return partition;
	}

	/**
	 * Asserts that no partition of an array a write path holds is virtual - see
	 * {@link #requireMaterialized(ReferencePartition, String)}.
	 *
	 * @param partitions the partitions
	 * @param writeSite  names the write path holding the partitions, for the error message
	 * @return the partitions
	 * @throws GenericEvitaInternalError when a partition is virtual
	 */
	@Nonnull
	public static ReferencePartition[] requireMaterialized(
		@Nonnull ReferencePartition[] partitions,
		@Nonnull String writeSite
	) {
		for (final ReferencePartition partition : partitions) {
			requireMaterialized(partition, writeSite);
		}
		return partitions;
	}

	/**
	 * Creates the error the virtual arm of an exhaustive `switch` over a partition on a write path throws.
	 *
	 * @param partition the virtual partition the write path met
	 * @param writeSite names the write path, for the error message
	 * @return the error to throw
	 */
	@Nonnull
	public static GenericEvitaInternalError virtualPartitionOnWritePath(
		@Nonnull VirtualReferencePartition partition,
		@Nonnull String writeSite
	) {
		return new GenericEvitaInternalError(
			"The write path `" + writeSite + "` met virtual reference partition " + partition.getPrimaryKey() +
				" (`" + partition.getIndexKey() + "`) - a write reaches a partition only through its reduced entity " +
				"index, and a read resolver must not be used to find it!"
		);
	}

	/**
	 * Creates the error the virtual arm of an exhaustive `switch` over a partition on a query route throws while the
	 * route does not read virtual partitions yet. Such a route resolves partitions through the object-only resolvers,
	 * so the arm is unreachable until the route switches to a read resolver - which it does in the step named.
	 *
	 * @param partition    the virtual partition the route met
	 * @param route        names the query route, for the error message
	 * @param landingStep  the step of #1615 that teaches the route to read virtual partitions
	 * @return the error to throw
	 */
	@Nonnull
	public static GenericEvitaInternalError virtualPartitionOnUnmigratedRoute(
		@Nonnull VirtualReferencePartition partition,
		@Nonnull String route,
		@Nonnull String landingStep
	) {
		return new GenericEvitaInternalError(
			"The query route `" + route + "` met virtual reference partition " + partition.getPrimaryKey() +
				" (`" + partition.getIndexKey() + "`), but it reads virtual partitions only from step " + landingStep +
				" of #1615 on!"
		);
	}

	/**
	 * Resolves a partition the registry of single-owner partitions holds a row for into its virtual handle, reading
	 * the row and nothing else - two lookups in the registry, `O(log n)` each. The handle is built from the row of the
	 * registry as the type index holds it now.
	 *
	 * @param typeIndex           the referenced-type entity index of the reference in the partition's scope
	 * @param partitionPrimaryKey the primary key of the partition, positive
	 * @return the virtual partition, or `null` when the registry holds no row for the primary key
	 */
	@Nullable
	public static VirtualReferencePartition resolveVirtualIfExists(
		@Nonnull ReferencedTypeEntityIndex typeIndex,
		int partitionPrimaryKey
	) {
		final OptionalLong row = typeIndex.getVirtualReferencePartitionIndex().getPartitionOwner(partitionPrimaryKey);
		return row.isPresent() ? toVirtualPartition(typeIndex, partitionPrimaryKey, row.getAsLong()) : null;
	}

	/**
	 * Resolves a partition of a reference in a scope by its primary key, for a query - see the class documentation on
	 * read resolution. A primary key the registry of the given type index holds a row for answers its virtual handle;
	 * any other one answers its reduced entity index when that is a partition of the reference in the scope, `null`
	 * otherwise.
	 *
	 * @param partitionByPrimaryKey the lenient object-only resolution of the collection's partitions by primary key,
	 *                              answering `null` when the primary key names no partition
	 * @param virtualTypeIndex      the referenced-type entity index of the reference in the scope when the query
	 *                              reads the collection virtually, `null` when it does not
	 * @param scope                 the scope of the partition
	 * @param referenceName         the reference the partition belongs to
	 * @param partitionPrimaryKey   the primary key of the partition
	 * @return the partition, or `null` when the primary key names no partition of the reference in the scope
	 */
	@Nullable
	public static ReferencePartition resolveForReadIfExists(
		@Nonnull IntFunction<? extends ReferencePartition> partitionByPrimaryKey,
		@Nullable ReferencedTypeEntityIndex virtualTypeIndex,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		int partitionPrimaryKey
	) {
		if (virtualTypeIndex != null) {
			assertTypeIndexOf(virtualTypeIndex, scope, referenceName);
			final VirtualReferencePartition virtual = resolveVirtualIfExists(virtualTypeIndex, partitionPrimaryKey);
			if (virtual != null) {
				return virtual;
			}
		}
		final ReferencePartition partition = partitionByPrimaryKey.apply(partitionPrimaryKey);
		return partition != null && belongsTo(partition, scope, referenceName) ? partition : null;
	}

	/**
	 * Resolves a partition of a reference in a scope by a primary key that must name one, for a query - see
	 * {@link #resolveForReadIfExists(IntFunction, ReferencedTypeEntityIndex, Scope, String, int)}.
	 *
	 * @param partitionByPrimaryKey the lenient object-only resolution of the collection's partitions by primary key,
	 *                              answering `null` when the primary key names no partition
	 * @param virtualTypeIndex      the referenced-type entity index of the reference in the scope when the query
	 *                              reads the collection virtually, `null` when it does not
	 * @param scope                 the scope of the partition
	 * @param referenceName         the reference the partition belongs to
	 * @param partitionPrimaryKey   the primary key of the partition
	 * @param entityType            the collection the primary key was resolved in, for the error message
	 * @return the partition
	 * @throws GenericEvitaInternalError when the primary key names no partition of the reference in the scope
	 */
	@Nonnull
	public static ReferencePartition resolveForRead(
		@Nonnull IntFunction<? extends ReferencePartition> partitionByPrimaryKey,
		@Nullable ReferencedTypeEntityIndex virtualTypeIndex,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		int partitionPrimaryKey,
		@Nonnull String entityType
	) {
		final ReferencePartition partition = resolveForReadIfExists(
			partitionByPrimaryKey, virtualTypeIndex, scope, referenceName, partitionPrimaryKey
		);
		if (partition == null) {
			throw new GenericEvitaInternalError(
				"Expected a reference partition of `" + referenceName + "` in scope `" + scope + "` with primary key " +
					partitionPrimaryKey + " in collection `" + entityType + "`, but found none!"
			);
		}
		return partition;
	}

	/**
	 * Resolves every partition of one target entity of a reference in a scope, strictly, for a query - see the class
	 * documentation on read resolution. Without a type index to read virtually this is
	 * {@link #resolveOfTargetOrThrow}. With one, the partitions of the target are always enumerated through the type
	 * index, whatever the cardinality of the reference - a partition the registry holds a row for may have no index
	 * object to find by its key - and each primary key the registry holds a row for answers its virtual handle.
	 *
	 * @param indexByKey        lookup of the collection's indexes by key, answering `null` when absent
	 * @param indexByPrimaryKey lookup of the collection's indexes by primary key, answering `null` when absent
	 * @param virtualTypeIndex  the referenced-type entity index of the reference in the scope when the query reads the
	 *                          collection virtually, `null` when it does not
	 * @param scope             the scope of the partitions
	 * @param referenceName     the reference the partitions belong to
	 * @param allowsDuplicates  whether the reference allows duplicate references to one target
	 * @param targetPrimaryKey  the primary key of the target entity
	 * @param entityType        the collection the partitions belong to, for the error message
	 * @return the partitions of the target in the order the type index lists them, possibly empty
	 * @throws GenericEvitaInternalError when a listed primary key names no partition of the reference in the scope
	 */
	@Nonnull
	public static ReferencePartition[] resolveOfTargetForRead(
		@Nonnull Function<EntityIndexKey, ? extends Index<?>> indexByKey,
		@Nonnull IntFunction<? extends Index<?>> indexByPrimaryKey,
		@Nullable ReferencedTypeEntityIndex virtualTypeIndex,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		boolean allowsDuplicates,
		int targetPrimaryKey,
		@Nonnull String entityType
	) {
		if (virtualTypeIndex == null) {
			return resolveOfTargetOrThrow(
				indexByKey, indexByPrimaryKey, scope, referenceName, allowsDuplicates, targetPrimaryKey, entityType
			);
		}
		assertTypeIndexOf(virtualTypeIndex, scope, referenceName);
		final int[] partitionPrimaryKeys = virtualTypeIndex.getAllReferenceIndexes(targetPrimaryKey);
		if (partitionPrimaryKeys.length == 0) {
			return EMPTY_PARTITIONS;
		}
		final IntFunction<ReferencePartition> partitionByPrimaryKey =
			partitionPrimaryKey -> asPartition(indexByPrimaryKey.apply(partitionPrimaryKey));
		final ReferencePartition[] result = new ReferencePartition[partitionPrimaryKeys.length];
		for (int i = 0; i < partitionPrimaryKeys.length; i++) {
			result[i] = resolveForRead(
				partitionByPrimaryKey, virtualTypeIndex, scope, referenceName, partitionPrimaryKeys[i], entityType
			);
		}
		return result;
	}

	/**
	 * Splits every partition of several target entities of a reference in a scope by representation, for a query that
	 * reads the collection virtually - see the class documentation on read resolution. The partitions of each target
	 * are enumerated through the type index whatever the cardinality of the reference, as
	 * {@link #resolveOfTargetForRead} enumerates them, and the primary keys of all targets are split in one merge with
	 * the registry rows (see {@link #split(Bitmap, ReferencedTypeEntityIndex...)}) - no partition is resolved, neither
	 * by its primary key nor by its key.
	 *
	 * @param typeIndex         the referenced-type entity index of the reference in the scope, whose registry is read
	 * @param scope             the scope of the partitions
	 * @param referenceName     the reference the partitions belong to
	 * @param targetPrimaryKeys the primary keys of the target entities
	 * @return the split of the partitions of all the targets
	 */
	@Nonnull
	public static ReferencePartitionSplit splitOfTargetsForRead(
		@Nonnull ReferencedTypeEntityIndex typeIndex,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		@Nonnull Bitmap targetPrimaryKeys
	) {
		assertTypeIndexOf(typeIndex, scope, referenceName);
		final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		final OfInt it = targetPrimaryKeys.iterator();
		while (it.hasNext()) {
			// the partitions of distinct targets are distinct, so the writer only orders them
			for (final int partitionPrimaryKey : typeIndex.getAllReferenceIndexes(it.nextInt())) {
				writer.add(partitionPrimaryKey);
			}
		}
		final PersistentRoaringBitmap partitionPrimaryKeys = writer.get();
		return split(
			partitionPrimaryKeys.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(partitionPrimaryKeys), typeIndex
		);
	}

	/**
	 * Splits candidate partition primary keys of one reference by representation: those the registry of one of the
	 * type indexes holds a row for are read virtually, the rest through their reduced entity indexes. Each type index
	 * is merged with the candidates in one pass of a cursor over its rows (see
	 * {@link VirtualReferencePartitionIndex#seekPartitionOwner(int)}), which steps along near rows and descends the
	 * registry only for a far one - never one lookup per candidate. The split reads no index object at all.
	 *
	 * @param candidates  the candidate partition primary keys
	 * @param typeIndexes the referenced-type entity indexes of the reference whose registries are read - one per scope
	 *                    the candidates come from, none when the query does not read the collection virtually
	 * @return the split
	 * @throws GenericEvitaInternalError when two registries hold a row for one candidate
	 */
	@Nonnull
	public static ReferencePartitionSplit split(
		@Nonnull Bitmap candidates,
		@Nonnull ReferencedTypeEntityIndex... typeIndexes
	) {
		if (candidates.isEmpty() || typeIndexes.length == 0) {
			return new ReferencePartitionSplit(
				candidates, ArrayUtils.EMPTY_INT_ARRAY, EMPTY_LONG_ARRAY, EMPTY_TYPE_INDEXES
			);
		}
		final int capacity = candidates.size();
		int[] primaryKeys = ArrayUtils.EMPTY_INT_ARRAY;
		long[] ownersAndTargets = EMPTY_LONG_ARRAY;
		ReferencedTypeEntityIndex[] holders = EMPTY_TYPE_INDEXES;
		int count = 0;
		for (final ReferencedTypeEntityIndex typeIndex : typeIndexes) {
			final VirtualReferencePartitionIndex.PartitionOwnerCursor cursor =
				typeIndex.getVirtualReferencePartitionIndex().seekPartitionOwner(candidates.getFirst());
			final int countBefore = count;
			final OfInt it = candidates.iterator();
			while (it.hasNext()) {
				final int candidate = it.nextInt();
				if (!cursor.advanceTo(candidate)) {
					// the registry holds no row at or after this candidate - nor after any later one
					break;
				}
				if (cursor.partitionPrimaryKey() == candidate) {
					if (count == primaryKeys.length) {
						// sized lazily: most splits of a reference that is not read virtually find nothing
						final int grown = Math.min(capacity * typeIndexes.length, Math.max(16, count * 2));
						primaryKeys = Arrays.copyOf(primaryKeys, grown);
						ownersAndTargets = Arrays.copyOf(ownersAndTargets, grown);
						holders = Arrays.copyOf(holders, grown);
					}
					primaryKeys[count] = candidate;
					ownersAndTargets[count] = cursor.ownerAndTarget();
					holders[count] = typeIndex;
					count++;
				}
			}
			if (countBefore > 0 && count > countBefore) {
				// the rows of a second registry interleave with those of the first - restore the ascending order
				sortByPrimaryKey(primaryKeys, ownersAndTargets, holders, count);
			}
		}
		if (count == 0) {
			return new ReferencePartitionSplit(
				candidates, ArrayUtils.EMPTY_INT_ARRAY, EMPTY_LONG_ARRAY, EMPTY_TYPE_INDEXES
			);
		}
		final int[] virtualPrimaryKeys = count == primaryKeys.length ? primaryKeys : Arrays.copyOf(primaryKeys, count);
		final Bitmap materialized = count == candidates.size() ?
			EmptyBitmap.INSTANCE :
			new BaseBitmap(
				PersistentRoaringBitmap.andNot(
					RoaringBitmapBackedBitmap.getRoaringBitmap(candidates),
					PersistentRoaringBitmap.bitmapOf(virtualPrimaryKeys)
				)
			);
		return new ReferencePartitionSplit(
			materialized,
			virtualPrimaryKeys,
			count == ownersAndTargets.length ? ownersAndTargets : Arrays.copyOf(ownersAndTargets, count),
			count == holders.length ? holders : Arrays.copyOf(holders, count)
		);
	}

	/**
	 * Builds the virtual handle of a partition from its registry row - the representative attribute values are read
	 * from the registry's tuple dictionary, the empty tuple standing for a partition that has none recorded.
	 *
	 * @param typeIndex           the referenced-type entity index whose registry holds the row
	 * @param partitionPrimaryKey the primary key of the partition
	 * @param ownerAndTarget      the row, `pack(ownerPrimaryKey, targetPrimaryKey)`
	 * @return the virtual partition
	 */
	@Nonnull
	static VirtualReferencePartition toVirtualPartition(
		@Nonnull ReferencedTypeEntityIndex typeIndex,
		int partitionPrimaryKey,
		long ownerAndTarget
	) {
		final VirtualReferencePartitionIndex registry = typeIndex.getVirtualReferencePartitionIndex();
		final int tupleId = registry.getRepresentativeTupleId(partitionPrimaryKey);
		final Serializable[] tuple;
		if (tupleId == VirtualReferencePartitionIndex.NO_TUPLE_ID) {
			// a reference without representative attributes has the empty tuple, which the registry stands for by
			// absence
			tuple = ArrayUtils.EMPTY_SERIALIZABLE_ARRAY;
		} else {
			tuple = registry.getTuple(tupleId);
			if (tuple == null) {
				throw new GenericEvitaInternalError(
					"Partition " + partitionPrimaryKey + " refers to tuple id " + tupleId + ", which the registry " +
						"of `" + typeIndex.getIndexKey() + "` lacks!"
				);
			}
		}
		final RepresentativeReferenceKey representativeReferenceKey = new RepresentativeReferenceKey(
			new ReferenceKey(typeIndex.getReferenceName(), NumberUtils.unpackLow(ownerAndTarget)), tuple
		);
		return toVirtualPartition(
			typeIndex, partitionPrimaryKey, NumberUtils.unpackHigh(ownerAndTarget), representativeReferenceKey
		);
	}

	/**
	 * Builds the virtual handle of a partition whose representative reference key is already known.
	 *
	 * @param typeIndex                  the referenced-type entity index whose registry holds the partition's row
	 * @param partitionPrimaryKey        the primary key of the partition
	 * @param ownerPrimaryKey            the primary key of its only owner
	 * @param representativeReferenceKey the representative reference key of the partition
	 * @return the virtual partition
	 */
	@Nonnull
	static VirtualReferencePartition toVirtualPartition(
		@Nonnull ReferencedTypeEntityIndex typeIndex,
		int partitionPrimaryKey,
		int ownerPrimaryKey,
		@Nonnull RepresentativeReferenceKey representativeReferenceKey
	) {
		return new VirtualReferencePartition(
			partitionPrimaryKey,
			createKey(typeIndex.getIndexKey().scope(), representativeReferenceKey),
			representativeReferenceKey,
			ownerPrimaryKey,
			typeIndex
		);
	}

	/**
	 * Asserts that a type index handed to a read resolver is the one of the reference in the scope.
	 *
	 * @param typeIndex     the referenced-type entity index
	 * @param scope         the scope the resolver reads
	 * @param referenceName the reference the resolver reads
	 */
	static void assertTypeIndexOf(
		@Nonnull ReferencedTypeEntityIndex typeIndex,
		@Nonnull Scope scope,
		@Nonnull String referenceName
	) {
		final EntityIndexKey typeIndexKey = typeIndex.getIndexKey();
		// a plain branch rather than an assertion with a message, so the valid path builds no string
		if (
			typeIndexKey.type() != EntityIndexType.REFERENCED_ENTITY_TYPE || typeIndexKey.scope() != scope ||
				!referenceName.equals(typeIndexKey.discriminator())
		) {
			throw new GenericEvitaInternalError(
				"Type index `" + typeIndexKey + "` cannot resolve the partitions of `" + referenceName + "` in " +
					"scope `" + scope + "`!"
			);
		}
	}

	/**
	 * Sorts the first `count` entries of the aligned columns of a split by partition primary key. Only a split over
	 * several registries needs it, and the columns are short - an insertion sort keeps them aligned without boxing.
	 *
	 * @param primaryKeys      the partition primary keys
	 * @param ownersAndTargets the rows, aligned with `primaryKeys`
	 * @param holders          the type indexes holding the rows, aligned with `primaryKeys`
	 * @param count            the number of entries to sort
	 * @throws GenericEvitaInternalError when two registries hold a row for one partition
	 */
	private static void sortByPrimaryKey(
		@Nonnull int[] primaryKeys,
		@Nonnull long[] ownersAndTargets,
		@Nonnull ReferencedTypeEntityIndex[] holders,
		int count
	) {
		for (int i = 1; i < count; i++) {
			final int primaryKey = primaryKeys[i];
			final long ownerAndTarget = ownersAndTargets[i];
			final ReferencedTypeEntityIndex holder = holders[i];
			int j = i - 1;
			while (j >= 0 && primaryKeys[j] > primaryKey) {
				primaryKeys[j + 1] = primaryKeys[j];
				ownersAndTargets[j + 1] = ownersAndTargets[j];
				holders[j + 1] = holders[j];
				j--;
			}
			if (j >= 0 && primaryKeys[j] == primaryKey) {
				throw new GenericEvitaInternalError(
					"Partition " + primaryKey + " has a row in the registries of both `" + holders[j].getIndexKey() +
						"` and `" + holder.getIndexKey() + "`!"
				);
			}
			primaryKeys[j + 1] = primaryKey;
			ownersAndTargets[j + 1] = ownerAndTarget;
			holders[j + 1] = holder;
		}
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
	 * Resolves every partition of one target entity of a reference in a scope, strictly: a missing type index or
	 * partition yields no element, but a primary key the type index lists must resolve to a partition.
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
	 * Resolves every partition of one target entity through another collection's data store reader, leniently: a
	 * missing type index or partition yields no element, and so does a primary key the type index lists but the
	 * collection no longer holds. The lookup is the one of
	 * {@link #resolveOfTargetOrThrow(Function, IntFunction, Scope, String, boolean, int, String)}. The reader layers
	 * the in-flight indexes of that collection's current mutation over its committed maps.
	 *
	 * @param dataStoreReader  the reader of the collection owning the partitions
	 * @param scope            the scope of the partitions
	 * @param referenceName    the reference the partitions belong to
	 * @param allowsDuplicates whether the reference allows duplicate references to one target
	 * @param targetPrimaryKey the primary key of the target entity
	 * @return the partitions of the target, possibly empty
	 * @throws GenericEvitaInternalError when a key or a listed primary key names an index that is not a partition of
	 *                                   the reference in the scope
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
