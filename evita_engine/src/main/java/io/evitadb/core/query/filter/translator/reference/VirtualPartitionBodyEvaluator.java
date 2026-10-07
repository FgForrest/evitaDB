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

package io.evitadb.core.query.filter.translator.reference;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.AttributeBetween;
import io.evitadb.api.query.filter.AttributeContains;
import io.evitadb.api.query.filter.AttributeEndsWith;
import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.filter.AttributeGreaterThan;
import io.evitadb.api.query.filter.AttributeGreaterThanEquals;
import io.evitadb.api.query.filter.AttributeInRange;
import io.evitadb.api.query.filter.AttributeInSet;
import io.evitadb.api.query.filter.AttributeIs;
import io.evitadb.api.query.filter.AttributeLessThan;
import io.evitadb.api.query.filter.AttributeLessThanEquals;
import io.evitadb.api.query.filter.AttributeStartsWith;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterInScope;
import io.evitadb.api.query.filter.GroupHaving;
import io.evitadb.api.query.filter.Not;
import io.evitadb.api.query.filter.Or;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.NestedQueryRestriction;
import io.evitadb.core.query.filter.VirtualPartitionRows;
import io.evitadb.core.query.filter.VirtualPartitionRows.InScopeReading;
import io.evitadb.core.query.sort.entity.comparator.EntityNestedQueryComparator;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.ReferencePartitionSplit;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;

/**
 * Answers the body of a reference filter for the candidate partitions read virtually - the registered single-owner
 * partitions a {@link ReferencePartitionSplit} split off the candidates.
 *
 * ## Two readings
 *
 * The candidates of a reference filter are the partitions whose row-level data in the referenced-type entity index
 * satisfies the body (`FilterByVisitor#getReducedIndexPrimaryKeyFormula`), and every consumer re-evaluates the body
 * against each candidate's reduced entity index afterwards, because a partition with several owners holds the union
 * of their rows there. A single-owner partition holds exactly one row, so for the leaves whose type-level answer is the
 * per-partition answer - the **row-exact** leaves - being a candidate already means the row satisfies the body. For a
 * body made of them alone the virtual part of the answer is `E = C ∩ V`, translated to the owners of `E`, without
 * re-evaluating anything: the split carries the owner of every virtual candidate.
 *
 * Row-exact: `and`, `or`, the attribute comparisons, `entityHaving` (the nested result holds only existing targets, so
 * the type-level existence intersection is a no-op) and a positive `entityPrimaryKeyInSet` (the type-level existence
 * intersection drops a missing target, and the reduced-index path only ever evaluates candidates, so both readings
 * drop it alike).
 *
 * The other leaves admitted in a reference body - `not`, `attributeIs`, `groupHaving`, `entityLocaleEquals` and
 * `inScope` - answer differently at the two levels, so a body holding one is **answered exactly** instead:
 * the body is evaluated once more against the referenced-type entity index of every scope holding virtual
 * candidates, with every negation settled in place and the candidates of that scope - the universe `U` - installed as
 * {@link VirtualPartitionRows}, which every leaf differing between the levels reads to answer the rows exactly (see
 * {@link FilterByVisitor#getMatchingVirtualPartitions}). The result intersected with `U` is the set of virtual
 * candidates whose single row satisfies the body; universe and evaluation are per scope, because `inScope` compares
 * the scope it names with the scope of the row.
 *
 * A constraint outside both lists is **refused** whenever a virtual candidate exists - never answered from the
 * candidate set, which could silently over-match, and never quietly handed back to the index objects, which would hide
 * that its virtual reading does not exist. Every constraint a reference body admits today is on one of the lists.
 *
 * The content of an `entityHaving` or a `groupHaving` is a filter over the referenced or the group collection, not
 * over the reference row, so the classification does not descend into it.
 *
 * ## Cost
 *
 * The row-exact part costs one pass over the virtual candidates - an unpack and a bitmap append per candidate,
 * `O(|E|)` - on top of the split that produced them. The exact part adds one evaluation of the body against the
 * referenced-type entity index per scope holding virtual candidates - the evaluation the candidate discovery already
 * performs, settled in place - and one pass over the candidates per leaf override; nothing is resolved, no index object
 * is touched and no body is evaluated per virtual partition.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class VirtualPartitionBodyEvaluator {
	/**
	 * The constraints whose type-level answer is the per-partition answer for a single-owner partition - see the class
	 * documentation.
	 */
	private static final Set<Class<? extends FilterConstraint>> ROW_EXACT_CONSTRAINTS = Set.of(
		FilterBy.class,
		And.class,
		Or.class,
		AttributeEquals.class,
		AttributeInSet.class,
		AttributeBetween.class,
		AttributeLessThan.class,
		AttributeLessThanEquals.class,
		AttributeGreaterThan.class,
		AttributeGreaterThanEquals.class,
		AttributeStartsWith.class,
		AttributeEndsWith.class,
		AttributeContains.class,
		AttributeInRange.class,
		EntityHaving.class,
		EntityPrimaryKeyInSet.class
	);
	/**
	 * The constraints whose type-level answer differs from the per-partition one, and which the exact evaluation over
	 * {@link VirtualPartitionRows} answers - see the class documentation.
	 */
	private static final Set<Class<? extends FilterConstraint>> EXACTLY_ANSWERED_CONSTRAINTS = Set.of(
		Not.class,
		AttributeIs.class,
		GroupHaving.class,
		EntityLocaleEquals.class,
		FilterInScope.class
	);

	private VirtualPartitionBodyEvaluator() {
		// static helper, never instantiated
	}

	/**
	 * Tells whether a reference body can be answered for virtual candidates from the candidate set alone - see the
	 * class documentation. A body holding a constraint that is not row-exact must be answered exactly
	 * ({@link #getMatchingPartitions}).
	 *
	 * @param body          the body evaluated against each reference row, `null` for an empty body
	 * @param referenceName the reference, for the error message
	 * @return TRUE when every constraint of the body is row-exact
	 * @throws GenericEvitaInternalError when the body holds a constraint with no virtual reading at all
	 */
	public static boolean isRowExact(@Nullable FilterConstraint body, @Nonnull String referenceName) {
		if (body == null) {
			return true;
		}
		final FilterConstraint unsupported = findUnsupported(body);
		if (unsupported != null) {
			throw new GenericEvitaInternalError(
				"Virtual read of `" + unsupported.getClass().getSimpleName() + "` is not defined - the body of " +
					"reference `" + referenceName + "` holds it and some of its candidate partitions are read " +
					"virtually: " + body
			);
		}
		return isRowExactInternal(body);
	}

	/**
	 * Tells whether a constraint class has a virtual reading - whether a body holding it is answered rather than
	 * refused when a candidate is read virtually.
	 *
	 * @param constraintClass the class of the constraint
	 * @return TRUE when the class is row-exact or answered exactly
	 */
	public static boolean isReadVirtually(@Nonnull Class<? extends FilterConstraint> constraintClass) {
		return ROW_EXACT_CONSTRAINTS.contains(constraintClass) ||
			EXACTLY_ANSWERED_CONSTRAINTS.contains(constraintClass);
	}

	/**
	 * Answers a body exactly for the virtual candidates of a split and returns the primary keys of those whose single
	 * row satisfies it. The body is evaluated against the referenced-type entity index of every scope holding virtual
	 * candidates, with those candidates installed as the universe of the evaluation (see
	 * {@link FilterByVisitor#getMatchingVirtualPartitions}).
	 *
	 * @param filterByVisitor             the visitor translating the reference filter
	 * @param entitySchema                the schema of the entity owning the reference
	 * @param referenceSchema             the reference
	 * @param body                        the body
	 * @param split                       the split of the candidates
	 * @param inScopeReading              how the route being answered reads `inScope` - see {@link InScopeReading}
	 * @param nestedQueryRestriction      the narrowing of a nested `entityHaving` query, as the candidate discovery
	 *                                    got it
	 * @param entityNestedQueryComparator the comparator the route evaluates the body of a materialized partition
	 *                                    with, NULL when it uses none
	 * @return the primary keys of the virtual candidates satisfying the body, possibly empty
	 */
	@Nonnull
	public static Bitmap getMatchingPartitions(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull FilterBy body,
		@Nonnull ReferencePartitionSplit split,
		@Nonnull InScopeReading inScopeReading,
		@Nullable NestedQueryRestriction nestedQueryRestriction,
		@Nullable EntityNestedQueryComparator entityNestedQueryComparator
	) {
		final VirtualPartitionRows[] rowsPerScope = VirtualPartitionRows.of(split, inScopeReading);
		final AttributeSchemaAccessor attributeSchemaAccessor =
			filterByVisitor.getProcessingScope().withReferenceSchemaAccessor(referenceSchema.getName());
		PersistentRoaringBitmap result = null;
		for (final VirtualPartitionRows rows : rowsPerScope) {
			final Bitmap matching = filterByVisitor.getMatchingVirtualPartitions(
				entitySchema, referenceSchema, body, rows, attributeSchemaAccessor, nestedQueryRestriction,
				entityNestedQueryComparator
			);
			if (!matching.isEmpty()) {
				final PersistentRoaringBitmap matchingBitmap = RoaringBitmapBackedBitmap.getRoaringBitmap(matching);
				result = result == null ? matchingBitmap : PersistentRoaringBitmap.or(result, matchingBitmap);
			}
		}
		return result == null ? EmptyBitmap.INSTANCE : new BaseBitmap(result);
	}

	/**
	 * Answers a body exactly for the virtual candidates of a split as `referenceContent(filterBy)` reads it, and
	 * returns the primary keys of those whose single row satisfies it. That route evaluates the body against each
	 * partition with the post-processor of `inScope` running, which ORs one copy of the body per requested scope (see
	 * {@link InScopeReading}): the copy of the partition's own scope ({@link InScopeReading#OTHER_SCOPES_MET}) and,
	 * when the query requests another scope too, the copy in which every `inScope` is unmet
	 * ({@link InScopeReading#ALL_SCOPES_UNMET}). A body without `inScope` has no copies, and is evaluated once.
	 *
	 * @param filterByVisitor             the visitor translating the reference filter
	 * @param entitySchema                the schema of the entity owning the reference
	 * @param referenceSchema             the reference
	 * @param body                        the body
	 * @param split                       the split of the candidates
	 * @param nestedQueryRestriction      the narrowing of a nested `entityHaving` query, as the candidate discovery
	 *                                    got it
	 * @param entityNestedQueryComparator the comparator of the ordering of the fetched references, which the route
	 *                                    evaluates the body of a materialized partition with - the body's
	 *                                    `entityLocaleEquals` hands it its locale; NULL when no ordering is requested
	 * @return the primary keys of the virtual candidates satisfying the body, possibly empty
	 */
	@Nonnull
	public static Bitmap getMatchingPartitionsPerRow(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull FilterBy body,
		@Nonnull ReferencePartitionSplit split,
		@Nullable NestedQueryRestriction nestedQueryRestriction,
		@Nullable EntityNestedQueryComparator entityNestedQueryComparator
	) {
		final Bitmap ownScopeCopy = getMatchingPartitions(
			filterByVisitor, entitySchema, referenceSchema, body, split, InScopeReading.OTHER_SCOPES_MET,
			nestedQueryRestriction, entityNestedQueryComparator
		);
		final Set<Scope> requestedScopes = filterByVisitor.getScopes();
		if (requestedScopes.size() < 2 || !containsInScope(body)) {
			return ownScopeCopy;
		}
		// every partition lives in one of the requested scopes, so with two of them each has the copy of the other
		final Bitmap otherScopeCopy = getMatchingPartitions(
			filterByVisitor, entitySchema, referenceSchema, body, split, InScopeReading.ALL_SCOPES_UNMET,
			nestedQueryRestriction, entityNestedQueryComparator
		);
		if (otherScopeCopy.isEmpty()) {
			return ownScopeCopy;
		} else if (ownScopeCopy.isEmpty()) {
			return otherScopeCopy;
		}
		return new BaseBitmap(
			PersistentRoaringBitmap.or(
				RoaringBitmapBackedBitmap.getRoaringBitmap(ownScopeCopy),
				RoaringBitmapBackedBitmap.getRoaringBitmap(otherScopeCopy)
			)
		);
	}

	/**
	 * Returns the owners of the virtual candidates of a split - the virtual part of the answer of a row-exact body
	 * (see {@link #isRowExact}). The formula is a constant identified by its content, so it carries no dependency on
	 * the registry the owners were read from: a write that changes the answer changes the content and with it the hash
	 * of every cacheable formula above it.
	 *
	 * @param split the split of the candidates
	 * @return the constant over the owners, or the empty formula when the split holds no virtual candidate
	 */
	@Nonnull
	public static Formula getOwnersFormula(@Nonnull ReferencePartitionSplit split) {
		return getOwnersFormula(split, null);
	}

	/**
	 * Returns the owners of the virtual candidates of a split that satisfy a body answered exactly (see
	 * {@link #getMatchingPartitions}) - a constant identified by its content, like {@link #getOwnersFormula(
	 * ReferencePartitionSplit)}.
	 *
	 * @param split    the split of the candidates
	 * @param matching the primary keys of the virtual candidates satisfying the body, `null` when every one does
	 * @return the constant over the owners, or the empty formula when no virtual candidate satisfies the body
	 */
	@Nonnull
	public static Formula getOwnersFormula(@Nonnull ReferencePartitionSplit split, @Nullable Bitmap matching) {
		final int virtualCount = split.getVirtualCount();
		if (virtualCount == 0 || (matching != null && matching.isEmpty())) {
			return EmptyFormula.INSTANCE;
		}
		// the candidates are in partition order, the owners are not - the writer accepts them in any order
		final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		for (int i = 0; i < virtualCount; i++) {
			if (matching == null || matching.contains(split.getVirtualPrimaryKey(i))) {
				writer.add(split.getVirtualOwnerPrimaryKey(i));
			}
		}
		final PersistentRoaringBitmap owners = writer.get();
		return owners.isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(new BaseBitmap(owners));
	}

	/**
	 * Finds the first constraint of a body that has no virtual reading, not descending into an `entityHaving` or a
	 * `groupHaving`.
	 *
	 * @param constraint the body or a part of it
	 * @return the first constraint with no virtual reading, `null` when there is none
	 */
	@Nullable
	private static FilterConstraint findUnsupported(@Nonnull FilterConstraint constraint) {
		if (!isReadVirtually(constraint.getClass())) {
			return constraint;
		}
		for (final FilterConstraint child : rowChildrenOf(constraint)) {
			final FilterConstraint unsupported = findUnsupported(child);
			if (unsupported != null) {
				return unsupported;
			}
		}
		return null;
	}

	/**
	 * Tells whether every constraint of a body with a virtual reading is row-exact, not descending into an
	 * `entityHaving` or a `groupHaving`.
	 *
	 * @param constraint the body or a part of it
	 * @return TRUE when no constraint of it has to be answered exactly
	 */
	private static boolean isRowExactInternal(@Nonnull FilterConstraint constraint) {
		if (!ROW_EXACT_CONSTRAINTS.contains(constraint.getClass())) {
			return false;
		}
		for (final FilterConstraint child : rowChildrenOf(constraint)) {
			if (!isRowExactInternal(child)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Tells whether a body holds `inScope` among the constraints on the reference row.
	 *
	 * @param constraint the body or a part of it
	 * @return TRUE when an `inScope` constrains the row
	 */
	private static boolean containsInScope(@Nonnull FilterConstraint constraint) {
		if (constraint instanceof FilterInScope) {
			return true;
		}
		for (final FilterConstraint child : rowChildrenOf(constraint)) {
			if (containsInScope(child)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns the children of a constraint that speak about the reference row - none for `entityHaving` and
	 * `groupHaving`, whose content filters another collection, and none for a leaf.
	 *
	 * @param constraint the constraint
	 * @return its children constraining the row
	 */
	@Nonnull
	private static FilterConstraint[] rowChildrenOf(@Nonnull FilterConstraint constraint) {
		return switch (constraint) {
			case FilterBy filterBy -> filterBy.getChildren();
			case And and -> and.getChildren();
			case Or or -> or.getChildren();
			case Not not -> not.getChildren();
			case FilterInScope inScope -> inScope.getChildren();
			default -> FilterConstraint.EMPTY_ARRAY;
		};
	}

}
