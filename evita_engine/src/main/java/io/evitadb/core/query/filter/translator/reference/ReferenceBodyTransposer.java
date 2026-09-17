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

import io.evitadb.core.query.QueryPlanner.EnclosingContainerRelation;
import io.evitadb.core.query.QueryPlanner.FutureNotFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.reference.IndexTaggedFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Rebuilds the formula tree produced by a `referenceHaving` body so that the body is answered one reference row at
 * a time instead of once across the whole index family.
 *
 * The body is translated exactly once, by the ordinary visitor, which evaluates every leaf against every reduced
 * entity index and ORs the per-index results together. That shape answers "does this owner have a row matching
 * leaf A, and a row matching leaf B" - which is not the same question as "does this owner have a row matching
 * both", and the two differ for every owner holding more than one row of the reference. Transposing the tree
 * moves the union outwards, so each index contributes the conjunction of its own leaves.
 *
 * Leaves carry an {@link IndexTaggedFormula} naming the index that produced them; see that class for why position
 * cannot be used instead. The tags are consumed here and never appear in the returned tree.
 *
 * A negation is rebuilt the same way, and that is what makes it work at all. `not` inside the body means "this row
 * does not match", so the complement has to be taken against the owners of the row's **own** index - `Orᵢ(Aᵢ \ lᵢ)`.
 * Complementing against the enclosing super set instead answers "this owner has no matching row anywhere", which
 * additionally returns every owner holding no row of the reference at all. Every {@link FutureNotFormula}
 * placeholder is therefore resolved here, per index, and none reaches the enclosing level.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ReferenceBodyTransposer {
	/**
	 * Stands in for the super set on the negation-free path, where no projection can ever ask for one. It fails
	 * loudly rather than returning something plausible, because a silent super set there would mean a negation
	 * reached a rebuild that never gathered the indexes able to complement it.
	 */
	private static final Supplier<Formula> UNREACHABLE_SUPER_SET = () -> {
		throw new GenericEvitaInternalError(
			"A super set was requested while rebuilding a `referenceHaving` body that contains no negation!"
		);
	};

	private ReferenceBodyTransposer() {
		// utility class
	}

	/**
	 * Returns the formula with every {@link IndexTaggedFormula} replaced by the formula it wraps.
	 *
	 * The tag is an internal artifact of translating a reference body: it exists so the body can be rebuilt per
	 * index, and it must not travel any further. Removing it is a pure identity on the computed result, since the
	 * tag delegates computation to its own child.
	 *
	 * @param formula the formula to clean
	 * @return an equivalent formula carrying no tags
	 */
	@Nonnull
	public static Formula stripTags(@Nonnull Formula formula) {
		if (formula instanceof final IndexTaggedFormula tagged) {
			return stripTags(tagged.getDelegate());
		}
		final Formula[] children = formula.getInnerFormulas();
		if (children.length == 0) {
			return formula;
		}
		// rebuild only when something below actually changed, so an untagged subtree is returned untouched
		Formula[] rebuiltChildren = null;
		for (int i = 0; i < children.length; i++) {
			final Formula strippedChild = stripTags(children[i]);
			if (strippedChild != children[i]) {
				if (rebuiltChildren == null) {
					rebuiltChildren = children.clone();
				}
				rebuiltChildren[i] = strippedChild;
			}
		}
		return rebuiltChildren == null ? formula : formula.getCloneWithInnerFormulas(rebuiltChildren);
	}

	/**
	 * Rebuilds the body so each reference row is evaluated on its own, then unions the rows.
	 *
	 * Formally this moves the union outwards - `And(Or(l1ᵢ), Or(l2ᵢ))` becomes `Orᵢ(And(l1ᵢ, l2ᵢ))` - which is
	 * what turns "has a row matching l1 **and** has a row matching l2" into "has a row matching **both**". A
	 * negation moves with it: `FutureNot(Or(lᵢ))` becomes `Orᵢ(Aᵢ \ lᵢ)`, where `Aᵢ` is the owner set of index `i`.
	 *
	 * A body whose per-index contributions meet only under `or` is returned as it stands, because the rebuild
	 * would reproduce it exactly - see {@link #combinedOnlyByUnion(Formula, Map)} for why that matters far more
	 * than it sounds.
	 *
	 * Which indexes are rebuilt for depends on whether the body negates anything:
	 *
	 * - **without a negation**, only the indexes that actually tagged something. An index that contributed no leaf
	 *   projects to nothing under every operator, so iterating the whole family would add cost and no results;
	 * - **with a negation**, every index in scope. An index contributing nothing to a negated leaf contributes its
	 *   entire owner set, because the complement of nothing is everything - skipping it would lose owners.
	 *
	 * A subtree carrying no tags is index-independent - a constant, or a nested query planned once - and is kept
	 * as it stands for every index rather than being read as absent.
	 *
	 * @param body               the translated body of a `referenceHaving`
	 * @param scopeIndexSupplier supplies the indexes the body was evaluated against; consulted only when
	 *                           a negation is present, because that is the only case where an untagged index
	 *                           can still contribute
	 * @return the row-scoped equivalent, carrying neither tags nor unresolved negations
	 */
	@Nonnull
	public static Formula transpose(
		@Nonnull Formula body,
		@Nonnull Supplier<List<EntityIndex>> scopeIndexSupplier
	) {
		final Map<Formula, Boolean> projectableSubtrees = new IdentityHashMap<>();
		final boolean negating = containsFutureNot(body);
		if (!negating && combinedOnlyByUnion(body, projectableSubtrees)) {
			// the body already answers the row-scoped question, so the rebuild would reproduce it at a cost that
			// is quadratic in the size of the index family - see `combinedOnlyByUnion`. A body that was never
			// evaluated per index lands here too: it says the same thing about every row, and carries no tag for
			// `stripTags` to remove.
			return stripTags(body);
		}

		final List<Formula> perIndexFormulas;
		if (negating) {
			final List<EntityIndex> scopeIndexes = scopeIndexSupplier.get();
			perIndexFormulas = new ArrayList<>(scopeIndexes.size());
			for (final EntityIndex scopeIndex : scopeIndexes) {
				collectProjection(
					body, scopeIndex.getPrimaryKey(), scopeIndex::getAllPrimaryKeysFormula,
					projectableSubtrees, perIndexFormulas
				);
			}
		} else {
			final Set<Integer> taggedIndexes = CollectionUtils.createLinkedHashSet(16);
			collectTaggedIndexes(body, taggedIndexes);
			perIndexFormulas = new ArrayList<>(taggedIndexes.size());
			for (final Integer indexPrimaryKey : taggedIndexes) {
				collectProjection(
					body, indexPrimaryKey, UNREACHABLE_SUPER_SET, projectableSubtrees, perIndexFormulas
				);
			}
		}
		return switch (perIndexFormulas.size()) {
			case 0 -> EmptyFormula.INSTANCE;
			case 1 -> perIndexFormulas.get(0);
			default -> FormulaFactory.or(perIndexFormulas.toArray(Formula[]::new));
		};
	}

	/**
	 * Projects the body onto one index and appends the result, unless the index contributes nothing.
	 *
	 * @param body                the translated body
	 * @param indexPrimaryKey     the index whose row is being isolated
	 * @param superSet            owners of that index - the set a negation inside it is complemented against
	 * @param projectableSubtrees memo shared across indexes
	 * @param collected           accumulator of the per-index formulas
	 */
	private static void collectProjection(
		@Nonnull Formula body,
		int indexPrimaryKey,
		@Nonnull Supplier<Formula> superSet,
		@Nonnull Map<Formula, Boolean> projectableSubtrees,
		@Nonnull List<Formula> collected
	) {
		final Formula projection = resolveNegation(
			project(body, indexPrimaryKey, superSet, projectableSubtrees), superSet
		);
		if (!(projection instanceof EmptyFormula)) {
			collected.add(projection);
		}
	}

	/**
	 * Replaces a {@link FutureNotFormula} that reached the top of a projection with the real subtraction.
	 *
	 * A disjunctive container hands its negation upwards rather than resolving it - `P OR NOT N` is rewritten as
	 * `NOT(N \ P)` - so a placeholder can still be sitting at the root of a projected body. Here is where the
	 * index's own owner set finally applies.
	 *
	 * @param formula  the projected formula
	 * @param superSet owners of the index being projected
	 * @return the formula with no placeholder left
	 */
	@Nonnull
	private static Formula resolveNegation(@Nonnull Formula formula, @Nonnull Supplier<Formula> superSet) {
		return formula instanceof final FutureNotFormula futureNot ?
			new NotFormula(futureNot.getInnerFormula(), superSet.get()) :
			formula;
	}

	/**
	 * Returns the body as it applies to a single reference row - the one held by the index named by the tag.
	 *
	 * @param node                node being projected
	 * @param indexPrimaryKey     the index whose row is being isolated
	 * @param superSet            owners of that index, for the negations inside it
	 * @param projectableSubtrees memoized answer to "does this subtree have to be projected at all"
	 * @return the projection, or {@link EmptyFormula#INSTANCE} when this index contributes nothing
	 */
	@Nonnull
	private static Formula project(
		@Nonnull Formula node,
		int indexPrimaryKey,
		@Nonnull Supplier<Formula> superSet,
		@Nonnull Map<Formula, Boolean> projectableSubtrees
	) {
		if (node instanceof final IndexTaggedFormula tagged) {
			return tagged.getIndexPrimaryKey() == indexPrimaryKey ?
				stripTags(tagged.getDelegate()) : EmptyFormula.INSTANCE;
		}
		if (node instanceof final FutureNotFormula futureNot) {
			final Formula projectedInner = project(
				futureNot.getInnerFormula(), indexPrimaryKey, superSet, projectableSubtrees
			);
			// nothing in this index matches what is being negated, so every row of it satisfies the negation -
			// returning EMPTY here would delete the very owners the negation is supposed to select
			return projectedInner instanceof EmptyFormula ?
				superSet.get() : new FutureNotFormula(projectedInner);
		}
		if (!isProjectable(node, projectableSubtrees)) {
			// index-independent: it says the same thing about every row
			return node;
		}
		final Formula[] children = node.getInnerFormulas();
		final Formula[] projectedChildren = new Formula[children.length];
		for (int i = 0; i < children.length; i++) {
			projectedChildren[i] = project(children[i], indexPrimaryKey, superSet, projectableSubtrees);
		}
		if (node instanceof OrFormula) {
			final Formula[] survivors = withoutEmpty(projectedChildren);
			return survivors.length == 0 ?
				EmptyFormula.INSTANCE :
				FutureNotFormula.postProcess(survivors, EnclosingContainerRelation.DISJUNCTION, superSet);
		}
		if (node instanceof AndFormula) {
			// a leaf that contributed nothing for this row makes the whole conjunction empty for this row -
			// this is precisely the cross-row combination the transpose exists to prevent. A projected negation
			// is never EMPTY, so it can never be mistaken for an absent leaf.
			for (final Formula projectedChild : projectedChildren) {
				if (projectedChild instanceof EmptyFormula) {
					return EmptyFormula.INSTANCE;
				}
			}
			return FutureNotFormula.postProcess(
				projectedChildren, EnclosingContainerRelation.CONJUNCTION, superSet
			);
		}
		// any other container - an `AttributeFormula` wrapper, a scope container - keeps its identity because
		// downstream consumers match on it; it contributes nothing when everything under it went away. A
		// placeholder is resolved before the clone, because such a container is not a boolean junction and
		// carries no relation under which the negation could be composed.
		for (int i = 0; i < projectedChildren.length; i++) {
			projectedChildren[i] = resolveNegation(projectedChildren[i], superSet);
		}
		return withoutEmpty(projectedChildren).length == 0 ?
			EmptyFormula.INSTANCE : node.getCloneWithInnerFormulas(projectedChildren);
	}

	/**
	 * Answers whether every per-index contribution in the subtree meets the others only under a disjunction.
	 *
	 * Such a body needs no rebuild at all. Projecting it onto index `i` keeps `i`'s own contributions and discards
	 * the rest, and unioning those projections back together reproduces the disjunction the visitor already built -
	 * `∃` distributes over `∨`, so "has a row matching A **or** a row matching B" and "has a row matching A or B"
	 * are the same question. Any other operator above a contribution - a conjunction, or a container that keeps its
	 * identity - makes the rebuild change the answer, which is the whole point of the transpose.
	 *
	 * Skipping the rebuild here is not a micro-optimisation. The rebuild walks the whole body once per index, and
	 * the body holds one contribution per index, so its cost is quadratic in the size of the reference's index
	 * family: measured at 224 ms for 8,000 indexes and quadrupling with every doubling, against families that
	 * reach 169,102 indexes on a production catalog. A single leaf and a flat `or` of leaves - the overwhelmingly
	 * common reference bodies, and the only shapes {@link BidirectionalReferenceRewriter} accepts - are exactly
	 * the ones that land here.
	 *
	 * An index-independent subtree passes: it is kept whole for every index anyway, and a union of one thing with
	 * itself is that thing.
	 *
	 * @param node                subtree root
	 * @param projectableSubtrees memo shared with {@link #isProjectable(Formula, Map)}
	 * @return true when the subtree would survive the rebuild unchanged
	 */
	private static boolean combinedOnlyByUnion(
		@Nonnull Formula node,
		@Nonnull Map<Formula, Boolean> projectableSubtrees
	) {
		if (node instanceof IndexTaggedFormula || !isProjectable(node, projectableSubtrees)) {
			return true;
		}
		if (!(node instanceof OrFormula)) {
			return false;
		}
		for (final Formula child : node.getInnerFormulas()) {
			if (!combinedOnlyByUnion(child, projectableSubtrees)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Answers whether the subtree has to be rebuilt per index - it carries a tag, or a negation that has to be
	 * complemented against one index's owners. Memoized per node identity.
	 *
	 * @param node                subtree root
	 * @param projectableSubtrees memo
	 * @return true when the subtree cannot be reused unchanged for every index
	 */
	private static boolean isProjectable(
		@Nonnull Formula node,
		@Nonnull Map<Formula, Boolean> projectableSubtrees
	) {
		final Boolean memoized = projectableSubtrees.get(node);
		if (memoized != null) {
			return memoized;
		}
		boolean projectable = node instanceof IndexTaggedFormula || node instanceof FutureNotFormula;
		if (!projectable) {
			for (final Formula child : node.getInnerFormulas()) {
				if (isProjectable(child, projectableSubtrees)) {
					projectable = true;
					break;
				}
			}
		}
		projectableSubtrees.put(node, projectable);
		return projectable;
	}

	/**
	 * Collects the distinct indexes that tagged something, in first-seen order.
	 *
	 * The accumulator is a set rather than a list on purpose: a body carries one contribution per index, so
	 * de-duplicating by scanning a list would make the collection itself quadratic in the family size.
	 *
	 * @param node      subtree root
	 * @param collected accumulator, iterated in insertion order
	 */
	private static void collectTaggedIndexes(@Nonnull Formula node, @Nonnull Set<Integer> collected) {
		if (node instanceof final IndexTaggedFormula tagged) {
			collected.add(tagged.getIndexPrimaryKey());
			return;
		}
		for (final Formula child : node.getInnerFormulas()) {
			collectTaggedIndexes(child, collected);
		}
	}

	/**
	 * Answers whether an unresolved negation placeholder sits anywhere in the subtree.
	 *
	 * @param node subtree root
	 * @return true when a {@link FutureNotFormula} is present
	 */
	private static boolean containsFutureNot(@Nonnull Formula node) {
		if (node instanceof FutureNotFormula) {
			return true;
		}
		for (final Formula child : node.getInnerFormulas()) {
			if (containsFutureNot(child)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns the formulas that are not {@link EmptyFormula}.
	 *
	 * @param formulas formulas to filter
	 * @return the non-empty ones, possibly the same array
	 */
	@Nonnull
	private static Formula[] withoutEmpty(@Nonnull Formula[] formulas) {
		int surviving = 0;
		for (final Formula formula : formulas) {
			if (!(formula instanceof EmptyFormula)) {
				surviving++;
			}
		}
		if (surviving == formulas.length) {
			return formulas;
		}
		final Formula[] result = new Formula[surviving];
		int written = 0;
		for (final Formula formula : formulas) {
			if (!(formula instanceof EmptyFormula)) {
				result[written++] = formula;
			}
		}
		return result;
	}

}
