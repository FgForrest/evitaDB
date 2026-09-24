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

package io.evitadb.core.query.sort.reference.sorter;

import com.carrotsearch.hppc.IntIntHashMap;
import com.carrotsearch.hppc.IntObjectHashMap;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.order.PickFirstByEntityProperty;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.sort.NestedContextSorter;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.membership.ReducedIndexMembership;
import io.evitadb.roaringbitmap.PeekableIntIterator;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PrimitiveIterator.OfInt;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

/**
 * Resolves the reduced indexes a {@link PickFirstByEntityProperty} ordering of one reference walks, for one selection
 * of owner entities, and the order of the referenced entities (targets) those indexes belong to.
 *
 * # The semantics it serves
 *
 * An owner sorts on the value of its **first row, in target order, that carries the sorted value**. Every row of a
 * selected owner takes part, whatever the query's filter says about the same reference - so the index set depends on
 * the selection and nothing else. It is therefore resolved at execution time, from the computed selection, rather than
 * at planning time from the candidate indexes a `referenceHaving` on the same reference happened to produce.
 *
 * # How the index set is found
 *
 * Per scope, the reference's {@link ReducedIndexMembership} names the covered reduced indexes of each owner, and its
 * residual set names the rest. The union of the entries of the selected owners plus the residual set is a superset of
 * the indexes holding a row of the selection, and it is usually far smaller than the reference's whole family, which
 * is what the index route walked before. The membership is an **accelerator, never an authority**, so the resolution
 * follows the same rules as its other reader, `ReevaluateExpressionExecutor`:
 *
 * - a scope without a membership (none maintained yet, or discarded by a schema change) walks the whole family of the
 *   reference, exactly as before;
 * - an index that no longer resolves is skipped, and so is an index of the group family, which the membership covers
 *   too;
 * - the resolved indexes are a superset of the indexes holding a selected owner and are deliberately not probed
 *   against the selection here: {@link PickFirstReferenceSorter} intersects every index with the owners still
 *   unclaimed anyway, which rejects an index holding no selected owner at no extra cost, while a probe here would
 *   repeat that intersection for every index of a dense selection (measured at half the sort time for 80,187 owners
 *   over 4,022 partitions). Both routes derive the set from the same selection and membership, and a target order
 *   is a total order, so the targets of the extra indexes never change the relative order of the others.
 *
 * When the selection holds more covered owners than the reference has reduced indexes, the whole family is walked
 * instead: the gather would not visit fewer indexes and would cost more lookups.
 *
 * # Target order
 *
 * The targets of the kept indexes are ordered by the ordering specification of {@link PickFirstByEntityProperty}: by
 * their primary key when it is the plain `entityPrimaryKeyNatural`, by a {@link NestedContextSorter} over the
 * referenced collection otherwise. Indexes sharing one target (a reference allowing duplicates keeps one per
 * representative attribute values) follow in {@link RepresentativeReferenceKey#GENERIC_COMPARATOR} order. The same
 * target order is exposed as a rank function for the prefetch route, which must pick the very row the index route
 * picks.
 *
 * # Lifetime
 *
 * One instance is created per query plan and lives only as long as it. The last resolution is memoized by the
 * identity of the selection bitmap, which is safe because the indexes it reads are the snapshot of the query that
 * created the instance.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class PickFirstReducedIndexResolver {
	/**
	 * The context of the planned query, giving access to the indexes of the queried collection.
	 */
	@Nonnull private final QueryPlanningContext queryContext;
	/**
	 * The reference the ordering walks.
	 */
	@Nonnull private final ReferenceSchemaContract referenceSchema;
	/**
	 * The scopes the query processes, in the order of {@link Scope#values()}.
	 */
	@Nonnull private final Scope[] scopes;
	/**
	 * Sorter ordering the targets when the ordering specification is not the plain primary key order, `null` when it
	 * is.
	 */
	@Nullable private final NestedContextSorter targetSorter;
	/**
	 * Whether the plain primary key order of the targets is descending. Meaningless when {@link #targetSorter} is set.
	 */
	private final boolean targetsDescending;
	/**
	 * The selection {@link #memoizedResult} was resolved for, compared by identity.
	 */
	@Nullable private Bitmap memoizedSelection;
	/**
	 * The last resolution.
	 */
	@Nullable private ResolvedReducedIndexes memoizedResult;
	/**
	 * Supplier of the planning-time reduced indexes, see {@link #getPlanningIndexes()}.
	 */
	@Nonnull private final Supplier<ReducedEntityIndex[]> planningIndexes;
	/**
	 * Memoized result of {@link #planningIndexes}.
	 */
	@Nullable private ReducedEntityIndex[] memoizedPlanningIndexes;

	/**
	 * Creates a resolver.
	 *
	 * @param queryContext      the context of the planned query
	 * @param referenceSchema   the reference the ordering walks
	 * @param scopes            the scopes the query processes
	 * @param targetSorter      sorter ordering the targets, `null` for the plain primary key order
	 * @param targetsDescending whether the plain primary key order is descending
	 * @param planningIndexes   supplier of the planning-time reduced indexes, see {@link #getPlanningIndexes()}
	 */
	public PickFirstReducedIndexResolver(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope[] scopes,
		@Nullable NestedContextSorter targetSorter,
		boolean targetsDescending,
		@Nonnull Supplier<ReducedEntityIndex[]> planningIndexes
	) {
		this.queryContext = queryContext;
		this.referenceSchema = referenceSchema;
		this.scopes = scopes;
		this.targetSorter = targetSorter;
		this.targetsDescending = targetsDescending;
		this.planningIndexes = planningIndexes;
	}

	/**
	 * Returns the reference this resolver walks.
	 *
	 * @return the reference schema
	 */
	@Nonnull
	public ReferenceSchemaContract getReferenceSchema() {
		return this.referenceSchema;
	}

	/**
	 * Returns true when the ordering processes the given scope. Owners living in another scope are never claimed by
	 * the index route, so the prefetch route must leave them unsorted as well.
	 *
	 * @param scope the scope of an owner entity
	 * @return true if the scope is one of the scopes the query processes for this ordering
	 */
	public boolean isProcessedScope(@Nonnull Scope scope) {
		for (Scope processedScope : this.scopes) {
			if (processedScope == scope) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Resolves the reduced indexes that may hold a row of the selected owners - a superset of those that do - in
	 * target order.
	 *
	 * @param selection primary keys of the owners being sorted
	 * @return the indexes and the rank function of their targets
	 */
	@Nonnull
	public ResolvedReducedIndexes resolve(@Nonnull Bitmap selection) {
		if (this.memoizedResult != null && this.memoizedSelection == selection) {
			return this.memoizedResult;
		}
		final PersistentRoaringBitmap selected = RoaringBitmapBackedBitmap.getRoaringBitmap(selection);
		final List<ReducedEntityIndex> found = new ArrayList<>(64);
		if (!selected.isEmpty()) {
			for (Scope scope : this.scopes) {
				final ReferencedTypeEntityIndex typeIndex = getTypeIndex(scope);
				if (typeIndex != null) {
					final Bitmap candidates = selectCandidates(scope, typeIndex, selected);
					final OfInt it = candidates.iterator();
					while (it.hasNext()) {
						final ReducedEntityIndex index = resolveReducedIndex(it.nextInt(), scope);
						if (index != null) {
							found.add(index);
						}
					}
				}
			}
		}
		final ResolvedReducedIndexes result = order(found);
		this.memoizedSelection = selection;
		this.memoizedResult = result;
		return result;
	}

	/**
	 * Returns the rank of the targets referenced by the selected owners - the order the prefetch route considers the
	 * references of an owner in. The plain primary key order is answered without touching any index; any other order
	 * is computed over the targets of the resolved indexes, exactly as {@link #resolve(Bitmap)} computes it for the
	 * index route, so both routes rank the targets alike even for orders that depend on the ranked set as a whole.
	 *
	 * @param selection primary keys of the owners being sorted
	 * @return function returning a lower number for a target that comes first
	 */
	@Nonnull
	public IntUnaryOperator getTargetRank(@Nonnull Bitmap selection) {
		return this.targetSorter == null ? createTargetRank(ArrayUtils.EMPTY_INT_ARRAY) : resolve(selection).targetRank();
	}

	/**
	 * Returns the reduced indexes for the consumers that need an index array at planning time, before any selection
	 * exists - the block-by-block orderings of chain attributes and the single-index orderings - which therefore keep
	 * the planning-time index set of the block orderings: the candidates of a `referenceHaving` on the same reference
	 * when there are any, the whole family otherwise. Resolved once per instance, and only when asked for.
	 *
	 * @return the planning-time reduced indexes in target order
	 */
	@Nonnull
	public ReducedEntityIndex[] getPlanningIndexes() {
		if (this.memoizedPlanningIndexes == null) {
			this.memoizedPlanningIndexes = this.planningIndexes.get();
		}
		return this.memoizedPlanningIndexes;
	}

	/**
	 * Returns the type index listing the reduced indexes of the reference in the scope.
	 *
	 * @param scope the scope to look into
	 * @return the type index or `null` when the reference has no reduced index in the scope
	 */
	@Nullable
	private ReferencedTypeEntityIndex getTypeIndex(@Nonnull Scope scope) {
		return this.queryContext.getIndexIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, this.referenceSchema.getName()),
			ReferencedTypeEntityIndex.class
		).orElse(null);
	}

	/**
	 * Selects the primary keys of the reduced indexes worth probing for the selection in one scope: the membership
	 * entries of the selected owners plus the residual set, or the whole family when there is no membership or when
	 * gathering would not visit fewer indexes.
	 *
	 * @param scope     the scope
	 * @param typeIndex the type index listing the whole family in the scope
	 * @param selected  the selected owners
	 * @return primary keys of the candidate reduced indexes
	 */
	@Nonnull
	private Bitmap selectCandidates(
		@Nonnull Scope scope,
		@Nonnull ReferencedTypeEntityIndex typeIndex,
		@Nonnull PersistentRoaringBitmap selected
	) {
		final Bitmap family = typeIndex.getAllPrimaryKeys();
		final ReducedIndexMembership membership = this.queryContext.getGlobalEntityIndexIfExists(scope)
			.map(it -> it.getReducedIndexMembership(this.referenceSchema.getName()))
			.orElse(null);
		if (membership == null) {
			return family;
		}
		final PersistentRoaringBitmap coveredSelected = PersistentRoaringBitmap.and(
			RoaringBitmapBackedBitmap.getRoaringBitmap(membership.getCoveredOwners()), selected
		);
		if (coveredSelected.getCardinality() >= family.size()) {
			return family;
		}
		final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		final PeekableIntIterator ownerIt = coveredSelected.getIntIterator();
		while (ownerIt.hasNext()) {
			final OfInt indexIt = membership.getIndexPrimaryKeys(ownerIt.next()).iterator();
			while (indexIt.hasNext()) {
				writer.add(indexIt.nextInt());
			}
		}
		return new BaseBitmap(
			PersistentRoaringBitmap.or(
				writer.get(),
				RoaringBitmapBackedBitmap.getRoaringBitmap(membership.getResidualIndexPrimaryKeys())
			)
		);
	}

	/**
	 * Resolves a candidate primary key to a reduced index of this reference's entity family in the scope, tolerating
	 * keys that name nothing or an index of another family.
	 *
	 * @param indexPrimaryKey primary key of the candidate index
	 * @param scope           the scope the candidate was found in
	 * @return the reduced index or `null` when the key does not name one of this reference in the scope
	 */
	@Nullable
	private ReducedEntityIndex resolveReducedIndex(int indexPrimaryKey, @Nonnull Scope scope) {
		final EntityIndex index = this.queryContext.getEntityIndexByPrimaryKeyIfExists(indexPrimaryKey);
		if (
			index instanceof ReducedEntityIndex reducedIndex &&
				reducedIndex.getIndexKey().type() == EntityIndexType.REFERENCED_ENTITY &&
				reducedIndex.getIndexKey().scope() == scope &&
				reducedIndex.getIndexKey().discriminator() instanceof RepresentativeReferenceKey rrk &&
				this.referenceSchema.getName().equals(rrk.referenceName())
		) {
			return reducedIndex;
		}
		return null;
	}

	/**
	 * Orders the found indexes by the rank of their target, and indexes sharing one target by their representative
	 * attribute values.
	 *
	 * @param found the indexes in any order
	 * @return the ordered indexes along with the rank function of the targets
	 */
	@Nonnull
	private ResolvedReducedIndexes order(@Nonnull List<ReducedEntityIndex> found) {
		if (this.targetSorter == null) {
			// the plain primary key order needs no grouping: one sort by target, then by representative values
			final ReducedEntityIndex[] ordered = found.toArray(ReducedEntityIndex[]::new);
			final Comparator<ReducedEntityIndex> byTarget = Comparator.comparingInt(it -> it.getReferenceKey().primaryKey());
			Arrays.sort(
				ordered,
				(this.targetsDescending ? byTarget.reversed() : byTarget)
					.thenComparing(
						ReducedEntityIndex::getRepresentativeReferenceKey,
						RepresentativeReferenceKey.GENERIC_COMPARATOR
					)
			);
			return new ResolvedReducedIndexes(ordered, createTargetRank(ArrayUtils.EMPTY_INT_ARRAY));
		}
		final IntObjectHashMap<List<ReducedEntityIndex>> indexesByTarget = new IntObjectHashMap<>(found.size());
		final RoaringBitmapWriter<PersistentRoaringBitmap> targetWriter = RoaringBitmapBackedBitmap.buildWriter();
		for (ReducedEntityIndex index : found) {
			final int target = index.getReferenceKey().primaryKey();
			List<ReducedEntityIndex> sameTarget = indexesByTarget.get(target);
			if (sameTarget == null) {
				sameTarget = new ArrayList<>(1);
				indexesByTarget.put(target, sameTarget);
				targetWriter.add(target);
			}
			sameTarget.add(index);
		}
		final int[] orderedTargets = orderTargets(new BaseBitmap(targetWriter.get()));
		final ReducedEntityIndex[] ordered = new ReducedEntityIndex[found.size()];
		int peak = 0;
		for (int target : orderedTargets) {
			final List<ReducedEntityIndex> sameTarget = indexesByTarget.get(target);
			if (sameTarget.size() > 1) {
				sameTarget.sort(
					(a, b) -> RepresentativeReferenceKey.GENERIC_COMPARATOR.compare(
						a.getRepresentativeReferenceKey(), b.getRepresentativeReferenceKey()
					)
				);
			}
			for (ReducedEntityIndex index : sameTarget) {
				ordered[peak++] = index;
			}
		}
		return new ResolvedReducedIndexes(ordered, createTargetRank(orderedTargets));
	}

	/**
	 * Orders the targets by the ordering specification.
	 *
	 * @param targets primary keys of the targets
	 * @return the targets in target order
	 */
	@Nonnull
	private int[] orderTargets(@Nonnull Bitmap targets) {
		if (targets.isEmpty()) {
			return ArrayUtils.EMPTY_INT_ARRAY;
		} else if (this.targetSorter == null) {
			return this.targetsDescending ? ArrayUtils.reverse(targets.getArray()) : targets.getArray();
		} else {
			return this.targetSorter.sortAndSlice(targets);
		}
	}

	/**
	 * Creates the rank function of the ordered targets. The plain primary key order needs no lookup; any other order
	 * maps each target to its position, and a target outside the ordered set ranks after all of them rather than
	 * silently tying with the first one.
	 *
	 * @param orderedTargets the targets in target order
	 * @return function returning a lower number for a target that comes first
	 */
	@Nonnull
	private IntUnaryOperator createTargetRank(@Nonnull int[] orderedTargets) {
		if (this.targetSorter == null) {
			return this.targetsDescending ? pk -> -pk : pk -> pk;
		}
		final IntIntHashMap ranks = new IntIntHashMap(orderedTargets.length);
		for (int i = 0; i < orderedTargets.length; i++) {
			ranks.put(orderedTargets[i], i);
		}
		return pk -> ranks.getOrDefault(pk, Integer.MAX_VALUE);
	}

	/**
	 * The reduced indexes resolved for one selection.
	 *
	 * @param indexes    reduced indexes that may hold a row of the selection, in target order
	 * @param targetRank function returning a lower number for a target that comes first in target order
	 */
	public record ResolvedReducedIndexes(
		@Nonnull ReducedEntityIndex[] indexes,
		@Nonnull IntUnaryOperator targetRank
	) {
	}

}
