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
import io.evitadb.api.query.filter.AttributeLessThan;
import io.evitadb.api.query.filter.AttributeLessThanEquals;
import io.evitadb.api.query.filter.AttributeStartsWith;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.Or;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.ReferencePartitionSplit;
import io.evitadb.index.bitmap.BaseBitmap;
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
 * ## Why the candidate set is the answer
 *
 * The candidates of a reference filter are the partitions whose row-level data in the referenced-type entity index
 * satisfies the body (`FilterByVisitor#getReducedIndexPrimaryKeyFormula`), and every consumer re-evaluates the body
 * against each candidate's reduced entity index afterwards, because a partition with several owners holds the union
 * of their rows there. A single-owner partition holds exactly one row, so for the leaves whose type-level answer is the
 * per-partition answer - the **row-exact** leaves listed below - being a candidate already means the row satisfies the
 * body. The virtual part of the answer is then `E = C ∩ V`, translated to the owners of `E`, without re-evaluating
 * anything: the split carries the owner of every virtual candidate.
 *
 * Row-exact (step 4b of #1615): `and`, `or`, the attribute comparisons, `entityHaving` (the nested result holds only
 * existing targets, so the type-level existence intersection is a no-op) and a positive `entityPrimaryKeyInSet` (the
 * type-level existence intersection drops a missing target, and the reduced-index path only ever evaluates candidates,
 * so both readings drop it alike). Every other constraint - `not`, `attributeIs`, `groupHaving`, `entityLocaleEquals`,
 * `inScope` and whatever a later version admits in a reference body - differs between the two levels, and a body
 * containing one is **rejected** whenever a virtual candidate exists ({@link #assertRowExact}). It is never answered
 * from the candidate set, which would silently over-match, and never quietly handed back to the index objects, which
 * would hide that the route has not landed. Step 4c of #1615 teaches those leaves to read virtual rows exactly.
 *
 * The content of an `entityHaving` is a filter over the referenced collection, not over the reference row, so the
 * classification does not descend into it.
 *
 * ## Cost
 *
 * The virtual part costs one pass over the virtual candidates - an unpack and a bitmap append per candidate, `O(|E|)`
 * - on top of the split that produced them. Nothing is resolved, no index object is touched and no body is evaluated
 * per virtual partition.
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

	private VirtualPartitionBodyEvaluator() {
		// static helper, never instantiated
	}

	/**
	 * Asserts that a reference body can be answered for the virtual candidates of a split from the candidate set alone
	 * - see the class documentation. A split without virtual candidates passes whatever the body is, because the index
	 * objects answer every candidate exactly as before.
	 *
	 * @param body          the body evaluated against each reference row, `null` for an empty body
	 * @param split         the split of the candidates the body is answered for
	 * @param referenceName the reference, for the error message
	 * @throws GenericEvitaInternalError when the split holds a virtual candidate and the body holds a constraint that is
	 *                                   not row-exact
	 */
	public static void assertRowExact(
		@Nullable FilterConstraint body,
		@Nonnull ReferencePartitionSplit split,
		@Nonnull String referenceName
	) {
		if (body == null || split.getVirtualCount() == 0) {
			return;
		}
		final FilterConstraint unsupported = findNotRowExact(body);
		if (unsupported != null) {
			throw new GenericEvitaInternalError(
				"Virtual read of `" + unsupported.getClass().getSimpleName() + "` lands in 4c of #1615 - the body of " +
					"reference `" + referenceName + "` holds it and " + split.getVirtualCount() + " of its candidate " +
					"partitions are read virtually: " + body
			);
		}
	}

	/**
	 * Returns the owners of the virtual candidates of a split - the virtual part of the answer of a row-exact body
	 * (see {@link #assertRowExact}). The formula is a constant identified by its content, so it carries no dependency
	 * on the registry the owners were read from: a write that changes the answer changes the content and with it the
	 * hash of every cacheable formula above it.
	 *
	 * @param split the split of the candidates
	 * @return the constant over the owners, or the empty formula when the split holds no virtual candidate
	 */
	@Nonnull
	public static Formula getOwnersFormula(@Nonnull ReferencePartitionSplit split) {
		final int virtualCount = split.getVirtualCount();
		if (virtualCount == 0) {
			return EmptyFormula.INSTANCE;
		}
		// the candidates are in partition order, the owners are not - the writer accepts them in any order
		final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		for (int i = 0; i < virtualCount; i++) {
			writer.add(split.getVirtualOwnerPrimaryKey(i));
		}
		return new ConstantFormula(new BaseBitmap(writer.get()));
	}

	/**
	 * Finds the first constraint of a body that is not row-exact, not descending into an `entityHaving`.
	 *
	 * @param constraint the body or a part of it
	 * @return the first constraint that is not row-exact, `null` when there is none
	 */
	@Nullable
	private static FilterConstraint findNotRowExact(@Nonnull FilterConstraint constraint) {
		if (!ROW_EXACT_CONSTRAINTS.contains(constraint.getClass())) {
			return constraint;
		}
		if (constraint instanceof EntityHaving) {
			// its content filters the referenced collection, not the reference row
			return null;
		}
		final FilterConstraint[] children = switch (constraint) {
			case FilterBy filterBy -> filterBy.getChildren();
			case And and -> and.getChildren();
			case Or or -> or.getChildren();
			default -> FilterConstraint.EMPTY_ARRAY;
		};
		for (final FilterConstraint child : children) {
			final FilterConstraint unsupported = findNotRowExact(child);
			if (unsupported != null) {
				return unsupported;
			}
		}
		return null;
	}

}
