/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import io.evitadb.api.query.AttributeConstraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.api.query.filter.Or;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.query.order.ReferenceProperty;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.core.query.AttributeSchemaAccessor;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.core.query.algebra.deferred.FormulaWrapper;
import io.evitadb.core.query.algebra.reference.ReferencedOwnerExistenceFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.translator.reference.HavingTranslatorHelper.GlobalIndexAndFormula;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Optional.empty;
import static java.util.Optional.of;

/**
 * Re-expresses a `referenceHaving` against the **other end of a bidirectional (reflected) reference pair**.
 *
 * The owner-side evaluation materialises one {@link ReducedEntityIndex} per *referenced entity* that survives the
 * filter, so a constraint reaching a large collection fans out over that whole collection. The counterpart reference
 * partitions the very same rows the other way round - one reduced index per *owner* - so the identical question can be
 * answered by visiting one index per candidate owner instead. On the large e-commerce catalogue this replaces 44 390
 * index visits with 588.
 *
 * The rewrite is deliberately conservative: it recognises a narrow constraint shape and falls through to the standard
 * path (returning an empty {@link Optional}) for everything else. It is a planning-time decision only - the formula it
 * produces answers exactly the same question, and nothing downstream needs to know which end produced it.
 *
 * ## Equivalence for the supported shape
 *
 * For children consisting of at most one `entityHaving` plus pure reference-attribute constraints, the owner-side
 * result is `AND(branch1, branch2)` where `branch1 = { o : exists r in P, attrs(o, r) }` (the attribute constraints
 * OR-ed across the selected reduced indexes) and `branch2 = { o : exists r in P }` (the owner translation of the
 * nested query). `attrs(o, r)` implies `r` carries a matching row, so `branch1` is a subset of `branch2` and the
 * conjunction collapses to `branch1` - which is precisely what this rewrite computes from the other side.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class BidirectionalReferenceRewriter {
	/**
	 * How many times fewer indexes the counterpart end must visit before the rewrite is taken. The owner-side count is
	 * an upper bound (the nested query narrows it further), so a plain majority is not enough to be sure of a gain.
	 */
	private static final int MINIMAL_GAIN = 4;
	/**
	 * Absolute ceiling on the number of per-owner formulas built at planning time, regardless of the ratio.
	 */
	private static final int MAX_CANDIDATE_OWNERS = 10_000;

	private BidirectionalReferenceRewriter() {
		throw new UnsupportedOperationException("This class cannot be instantiated!");
	}

	/**
	 * Identifies the counterpart of the passed reference on the referenced entity's schema. Works in both directions of
	 * the pair: when the owner reference is the reflected one the counterpart is named outright, otherwise the
	 * referenced schema is scanned for a reflected reference pointing back at us.
	 *
	 * @param queryContext      context used to reach the referenced entity's schema
	 * @param ownerEntitySchema schema of the entity the query targets
	 * @param ownerReference    reference the `referenceHaving` filters on
	 * @return the counterpart reference schema, or empty when the reference is not bidirectional
	 */
	@Nonnull
	static Optional<ReferenceSchemaContract> findCounterpart(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull EntitySchemaContract ownerEntitySchema,
		@Nonnull ReferenceSchemaContract ownerReference
	) {
		if (!ownerReference.isReferencedEntityTypeManaged()) {
			return empty();
		}
		final EntitySchemaContract targetEntitySchema;
		try {
			targetEntitySchema = queryContext.getSchema(ownerReference.getReferencedEntityType());
		} catch (RuntimeException ex) {
			// the referenced collection may not exist at all - there is simply nothing to rewrite to
			return empty();
		}
		if (ownerReference instanceof ReflectedReferenceSchemaContract reflectedOwnerReference) {
			// the reflected end names its original outright
			return reflectedOwnerReference.isReflectedReferenceAvailable() ?
				targetEntitySchema.getReference(reflectedOwnerReference.getReflectedReferenceName()) : empty();
		}
		// the original end has to be found by scanning the other side for a reflection pointing back at us
		for (final ReferenceSchemaContract candidate : targetEntitySchema.getReferences().values()) {
			if (candidate instanceof ReflectedReferenceSchemaContract reflectedCandidate &&
				reflectedCandidate.isReflectedReferenceAvailable() &&
				reflectedCandidate.getReflectedReferenceName().equals(ownerReference.getName()) &&
				reflectedCandidate.getReferencedEntityType().equals(ownerEntitySchema.getName())
			) {
				return of(candidate);
			}
		}
		return empty();
	}

	/**
	 * Attempts to answer the passed `referenceHaving` from the counterpart end of the reference pair.
	 *
	 * @return formula producing owner entity primary keys, or empty when any precondition fails and the standard
	 *         owner-side evaluation must be used instead
	 */
	@Nonnull
	static Optional<Formula> tryRewrite(
		@Nonnull ReferenceHaving referenceHaving,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull EntitySchemaContract ownerEntitySchema,
		@Nonnull ReferenceSchemaContract ownerReference,
		@Nonnull ProcessingScope<?> processingScope
	) {
		final RewritePlan plan = preparePlan(
			filterByVisitor.getQueryContext(), ownerEntitySchema, ownerReference, referenceHaving,
			processingScope.getScopes()
		);
		if (plan == null) {
			return empty();
		}
		if (plan.candidateOwners().length == 0) {
			return of(EmptyFormula.INSTANCE);
		}

		final Set<Scope> scopes = processingScope.getScopes();
		final Formula referencedEntityFormula = createReferencedEntityFormula(
			filterByVisitor, filterByVisitor.getQueryContext(), plan.targetEntityType(),
			plan.split().entityHavingChild(), scopes, plan.counterpartScopes(), ownerReference
		);
		if (referencedEntityFormula == EmptyFormula.INSTANCE) {
			return of(EmptyFormula.INSTANCE);
		}
		final Formula[] perOwnerFormulas = createPerOwnerFormulas(
			filterByVisitor, filterByVisitor.getQueryContext(), plan.targetEntitySchema(), plan.counterpart(),
			processingScope, plan.split().attributeConstraints(), plan.candidateOwners(), plan.counterpartScopes()
		);
		return of(
			new ReferencedOwnerExistenceFormula(
				plan.candidateOwners(),
				perOwnerFormulas,
				referencedEntityFormula,
				counterpartTypeIndexId(
					filterByVisitor.getQueryContext(), plan.targetEntityType(), plan.counterpart(),
					plan.counterpartScopes()
				)
			)
		);
	}

	/**
	 * Decides - using nothing but schemas and O(1) bitmap cardinalities - whether the constraint will be answered from
	 * the counterpart end. Index selection asks this *before* it discovers the owner-side reduced index set, because
	 * merely discovering that set is the cost this rewrite exists to avoid.
	 *
	 * @return TRUE when {@link #tryRewrite} is going to take over this constraint
	 */
	public static boolean isApplicable(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull EntitySchemaContract ownerEntitySchema,
		@Nonnull ReferenceSchemaContract ownerReference,
		@Nonnull ReferenceHaving referenceHaving,
		@Nonnull Set<Scope> scopes
	) {
		return preparePlan(queryContext, ownerEntitySchema, ownerReference, referenceHaving, scopes) != null;
	}

	/**
	 * Resolves everything the rewrite needs that can be decided without building a single formula - the constraint
	 * split, the counterpart schema and the candidate owner keys - or NULL when any precondition fails.
	 *
	 * The plan is derived at most once per constraint and scope set. Both entry points need it - index selection
	 * asks {@link #isApplicable} so it can skip discovering the owner-side index set, and {@link #tryRewrite} then
	 * needs the plan itself - and they cannot disagree, because the derivation reads nothing but schemas and index
	 * cardinalities off a committed snapshot. Without the memoization the counterpart type index is fetched,
	 * unioned, intersected and materialised into an `int[]` of up to {@link #MAX_CANDIDATE_OWNERS} entries twice for
	 * every constraint the rewrite takes over.
	 */
	@Nullable
	private static RewritePlan preparePlan(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull EntitySchemaContract ownerEntitySchema,
		@Nonnull ReferenceSchemaContract ownerReference,
		@Nonnull ReferenceHaving referenceHaving,
		@Nonnull Set<Scope> scopes
	) {
		return queryContext.computeOncePerConstraint(
			referenceHaving, scopes,
			() -> preparePlanInternal(queryContext, ownerEntitySchema, ownerReference, referenceHaving, scopes)
		);
	}

	/**
	 * Derives the plan. Called at most once per constraint and scope set - see {@link #preparePlan}.
	 *
	 * The result is shared between the applicability check and the rewrite, so nothing downstream may mutate it;
	 * {@link RewritePlan#candidateOwners()} in particular is read-only for every consumer.
	 */
	@Nullable
	private static RewritePlan preparePlanInternal(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull EntitySchemaContract ownerEntitySchema,
		@Nonnull ReferenceSchemaContract ownerReference,
		@Nonnull ReferenceHaving referenceHaving,
		@Nonnull Set<Scope> scopes
	) {
		if (scopes.isEmpty()) {
			return null;
		}
		final SplitChildren split = splitChildren(referenceHaving);
		if (split == null) {
			return null;
		}
		// `orderBy(referenceProperty(R, ...))` sorts owners by the position of their reference row among the reduced
		// indexes index selection picked for R (`ReferencePropertyTranslator#selectReducedEntityIndexSet`). Taking the
		// rewrite removes that entry, and the sorter silently falls back to *every* reduced index of R - which orders
		// owners by their first reference row rather than their first *matching* one. Leave those queries alone.
		if (orderedByTheSameReference(queryContext, ownerReference.getName())) {
			return null;
		}

		final Optional<ReferenceSchemaContract> counterpartRef = findCounterpart(
			queryContext, ownerEntitySchema, ownerReference
		);
		if (counterpartRef.isEmpty()) {
			return null;
		}
		final ReferenceSchemaContract counterpart = counterpartRef.get();
		final String targetEntityType = ownerReference.getReferencedEntityType();
		final EntitySchemaContract targetEntitySchema = queryContext.getSchema(targetEntityType);
		// the owner side has to be usable too - partly for scope symmetry (a reflected row is dropped when either
		// side is not indexed in the target scope), partly so that a query asking for a scope the owner reference is
		// not indexed in still fails with the exception naming the *owner* reference
		// A relation may span two scopes - an archived owner referencing a live target, or the reverse - and the
		// counterpart records its half in the *target's* scope, not the owner's. Reading the counterpart only in the
		// owner's requested scopes therefore misses every cross-scope row. `ContainerizedLocalMutationExecutor`
		// maintains such a relation only when both reference schemas are indexed in every scope it spans, so the
		// scopes a counterpart row can possibly live in are exactly those where both ends are indexed.
		final Set<Scope> counterpartScopes = counterpartScopes(ownerReference, counterpart, scopes);
		if (!referenceUsableInScopes(ownerReference, scopes) || !referenceUsableInScopes(counterpart, scopes)) {
			return null;
		}
		if (!attributesMirrored(ownerReference, counterpart, split.attributeNames(), scopes)) {
			return null;
		}

		final CandidateOwners candidates = collectCandidateOwners(
			queryContext, ownerEntitySchema, targetEntityType, counterpart, counterpartScopes, scopes
		);
		if (candidates == null) {
			return null;
		}
		// Reference-attribute constraints are translated by the filter visitor, and the visitor narrows the indexes it
		// sees to `processingScope.getScopes()` (`FilterByVisitor#getEntityIndexStream`). A per-owner reduced index
		// living outside those scopes - which is exactly what `counterpartScopes` exists to reach - is therefore
		// filtered out before the attribute is evaluated, and the owner is dropped. The index-contents path has no such
		// filter, so a bare `referenceHaving` and a plain `entityHaving` are unaffected; only the attribute branch is.
		// Widening the visitor's scope set would change how every nested constraint resolves, so the rewrite instead
		// declines and lets the owner-side path answer it - but only when an out-of-scope index actually announced an
		// owner, because otherwise there is nothing for the filter to lose.
		if (!split.attributeConstraints().isEmpty() && candidates.crossScope()) {
			return null;
		}
		// the rewrite is a trade, not a free win. The candidate set is now known exactly, so the gate is applied to
		// the real union cardinality rather than to a per-scope sum - summing double-counts an owner announced in two
		// scopes and can decline a plan that is provably cheaper.
		if (!worthRewriting(
			queryContext, ownerEntitySchema, ownerReference,
			candidates.announced(), candidates.counterpartIndexes(), scopes
		)) {
			return null;
		}
		return new RewritePlan(
			counterpart, targetEntityType, targetEntitySchema, split, candidates.owners(), counterpartScopes
		);
	}

	/**
	 * Everything resolved before a single formula is built.
	 *
	 * @param counterpart        the reference on the other end of the pair
	 * @param targetEntityType   entity type the owner reference points at
	 * @param targetEntitySchema schema of that entity type
	 * @param split              the constraint children separated into nested query and reference attributes
	 * @param candidateOwners    ascending owner primary keys worth testing
	 * @param counterpartScopes  scopes a counterpart row can live in - a superset of the requested scopes whenever the
	 *                           pair is indexed in more than one, because a relation may span two of them
	 */
	private record RewritePlan(
		@Nonnull ReferenceSchemaContract counterpart,
		@Nonnull String targetEntityType,
		@Nonnull EntitySchemaContract targetEntitySchema,
		@Nonnull SplitChildren split,
		@Nonnull int[] candidateOwners,
		@Nonnull Set<Scope> counterpartScopes
	) {
	}

	/**
	 * Candidate owners, with the cardinality of the set the counterpart announced before it was narrowed to owners
	 * that actually exist in the requested scopes.
	 *
	 * @param announced         how many distinct owners the counterpart's type-level indexes named across all of its
	 *                          scopes
	 * @param counterpartIndexes how many reduced indexes those scopes hold in total - the number the rewrite actually
	 *                          visits. Without duplicates it equals {@link #announced}, because a pair maps to exactly
	 *                          one reduced index; with duplicates one owner maps to one index per representative-value
	 *                          partition, and the two diverge.
	 * @param owners            those of them that exist in the requested scopes, ascending
	 * @param crossScope        TRUE when an index outside the requested scopes announced at least one owner - i.e. when
	 *                          the relation really does span scopes and the extra scan was not merely defensive
	 */
	private record CandidateOwners(
		int announced,
		int counterpartIndexes,
		@Nonnull int[] owners,
		boolean crossScope
	) {
	}

	/**
	 * Resolves the scopes a counterpart row can live in: the requested scopes, plus every scope where **both** ends of
	 * the pair are indexed.
	 *
	 * The extra scopes are not an optimisation but a correctness requirement. The counterpart records its half of a
	 * relation in the scope of the entity that owns the row, so a cross-scope relation is announced by the counterpart
	 * in the *other* scope entirely. Restricting the scan to the owner's requested scopes silently drops every such
	 * owner - and `isRelationMaintained` keeps a cross-scope relation only when both ends are indexed in every scope it
	 * spans, which is precisely the set computed here.
	 *
	 * **The test is `isIndexedInScope` alone, deliberately**, and not the stricter
	 * {@link #referenceUsableInScopes} that the *requested* scopes go through - this set must mirror
	 * `isRelationMaintained`, which is the rule that decides whether the rows exist at all, and that rule looks at
	 * nothing else. Adding a {@link ReferenceIndexedComponents#REFERENCED_ENTITY} check here would be inert rather than
	 * safer: the scopes it would remove are exactly the ones {@link #collectCandidateOwners} already skips on a missing
	 * type index, so the candidate union, the cross-scope flag and the gate arithmetic all come out the same. The two
	 * other consumers agree for their own reasons - {@link #createPerOwnerFormulas} reaches those scopes through
	 * indexes gated by the very same component flag, and the bare-`entityHaving` branch of
	 * {@link #createReferencedEntityFormula} only ever *intersects* its wider union against per-owner results that can
	 * never originate in such a scope.
	 *
	 * The one caller that is **not** literally indifferent is {@link #counterpartTypeIndexId}: it folds a rolling hash
	 * over the scope set, so skipping a scope inside the loop still performs a round that removing it would not, and
	 * the two produce different numbers. That value feeds only the cache key, never the candidate or filter
	 * computation, so it can change a cache hit into a miss but never an answer. Pinned by
	 * `BidirectionalReferenceRewriterTest.WidenedScopeWithoutCounterpartIndex`.
	 */
	@Nonnull
	private static Set<Scope> counterpartScopes(
		@Nonnull ReferenceSchemaContract ownerReference,
		@Nonnull ReferenceSchemaContract counterpart,
		@Nonnull Set<Scope> requestedScopes
	) {
		final EnumSet<Scope> result = EnumSet.noneOf(Scope.class);
		result.addAll(requestedScopes);
		for (final Scope scope : Scope.values()) {
			if (ownerReference.isIndexedInScope(scope) && counterpart.isIndexedInScope(scope)) {
				result.add(scope);
			}
		}
		return result;
	}

	/**
	 * Splits the children of the `referenceHaving` into the nested-query part and the reference-attribute part,
	 * rejecting anything the rewrite cannot faithfully reproduce.
	 *
	 * @return the split, or NULL when the shape is not supported
	 */
	@Nullable
	private static SplitChildren splitChildren(@Nonnull ReferenceHaving referenceHaving) {
		// the parser hands the children over wrapped in a single `and` container - the split has to see through it,
		// and flattening a conjunction is semantics preserving at any depth
		final List<FilterConstraint> children = new ArrayList<>(referenceHaving.getChildren().length);
		flattenConjunction(referenceHaving.getChildren(), children);
		FilterConstraint entityHavingChild = null;
		final List<FilterConstraint> attributeConstraints = new ArrayList<>(children.size());
		final List<String> attributeNames = new ArrayList<>(children.size());
		for (final FilterConstraint child : children) {
			if (child instanceof EntityHaving entityHaving) {
				// more than one `entityHaving` would have to be conjuncted - not supported
				if (entityHavingChild != null) {
					return null;
				}
				final FilterConstraint[] entityHavingChildren = entityHaving.getChildren();
				if (entityHavingChildren.length == 0) {
					return null;
				}
				// the container's children are conjunctive - the nested query has to see them as one constraint
				entityHavingChild = entityHavingChildren.length == 1 ?
					entityHavingChildren[0] : new And(entityHavingChildren);
			} else if (collectAttributeNames(child, attributeNames)) {
				attributeConstraints.add(child);
			} else {
				// groupHaving, entityPrimaryKeyInSet, facet constraints, inScope containers, ... - fall through
				return null;
			}
		}
		// two attribute siblings are an implicit conjunction, and a conjunction is not reproducible - see above
		if (attributeConstraints.size() > 1) {
			return null;
		}
		return new SplitChildren(entityHavingChild, attributeConstraints, attributeNames);
	}

	/**
	 * Tells whether the query sorts by a property of the very reference the rewrite would take over.
	 *
	 * The sorter reads the reduced index set index selection registered for that reference name, so removing the entry
	 * changes the ordering rather than merely the plan - see {@code ReferencePropertyTranslator:104-122}.
	 */
	private static boolean orderedByTheSameReference(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull String referenceName
	) {
		final OrderConstraint orderBy = queryContext.getOrderBy();
		if (orderBy == null) {
			return false;
		}
		final List<ReferenceProperty> referenceProperties = QueryUtils.findConstraints(
			orderBy, ReferenceProperty.class
		);
		for (final ReferenceProperty referenceProperty : referenceProperties) {
			if (referenceName.equals(referenceProperty.getReferenceName())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Copies the passed constraints into the target list, replacing every `and` container with its own children. Only
	 * conjunctive containers are unwrapped, so the meaning of the constraint set is untouched.
	 */
	private static void flattenConjunction(
		@Nonnull FilterConstraint[] children,
		@Nonnull List<FilterConstraint> target
	) {
		for (final FilterConstraint child : children) {
			if (child instanceof And conjunction) {
				flattenConjunction(conjunction.getChildren(), target);
			} else {
				target.add(child);
			}
		}
	}

	/**
	 * Walks the constraint and collects the attribute names it touches, provided the whole subtree is a shape whose
	 * meaning is identical on both ends of the reference.
	 *
	 * Only a single attribute leaf or a pure disjunction of leaves qualifies, and deliberately so:
	 *
	 * Both exclusions are conservative rather than proven necessary. The reasons originally recorded here were
	 * **measured to be wrong** (2026-09-14) and are corrected below; whether either shape could now be reproduced
	 * faithfully is an open question deliberately left alone - see the ADR.
	 *
	 * - **`and` is excluded.** The original note claimed the owner-side path accepts an owner whose row to one
	 *   referenced entity satisfies `A` while a *different* row satisfies `B`. Measured: it does **not**. Two attribute
	 *   siblings must be satisfied by a *single* reference row - a query naming two values of the same reference
	 *   attribute returns the empty set, where the cross-row reading predicts a populated one
	 *   (`BidirectionalReferenceRewriteFunctionalTest#shouldMatchTwoAttributeSiblingsWithinOneRowRatherThanAcrossRows`).
	 * - **`not` is excluded.** The original note claimed `NotTranslator`'s `FutureNotFormula`, resolved against the
	 *   *owner* superset at the enclosing `filterBy`, reads as "no discovered row satisfies A". Measured: it does not
	 *   read as anything - **a nested `not` does not constrain the owner set at all.** Two queries settle it
	 *   (`BidirectionalReferenceRewriteFunctionalTest#shouldNotRewriteWhenNotIsNestedInsideReferenceHaving`): the
	 *   second negates a value every row of two owners carries, so under *either* the "no row" or the "some row"
	 *   reading those owners are excluded - and they come back present. An owner all of whose rows satisfy the negated
	 *   constraint cannot survive a filter that applied it.
	 *   Note this correction was itself corrected: a first measurement suggested the quantifier was merely inverted
	 *   ("some row does not satisfy A"), which the single available query could not distinguish from "not applied".
	 *   Treat the excluded-shape reasoning here as measured only where a named test is cited.
	 *
	 * @return TRUE when the subtree is a reference-attribute constraint the rewrite reproduces exactly
	 */
	private static boolean collectAttributeNames(
		@Nonnull FilterConstraint constraint,
		@Nonnull List<String> attributeNames
	) {
		if (constraint instanceof AttributeConstraint<?> attributeConstraint) {
			Collections.addAll(attributeNames, attributeConstraint.getAttributeNames());
			return true;
		}
		if (constraint instanceof Or or) {
			final FilterConstraint[] children = or.getChildren();
			if (children.length == 0) {
				return false;
			}
			for (final FilterConstraint child : children) {
				if (!collectAttributeNames(child, attributeNames)) {
					return false;
				}
			}
			return true;
		}
		return false;
	}

	/**
	 * Verifies the reference is indexed in every requested scope **and** maintains the per-referenced-entity index
	 * family this rewrite walks.
	 *
	 * The second condition is {@link ReferenceIndexedComponents#REFERENCED_ENTITY}, which is an axis orthogonal to
	 * {@code ReferenceIndexType}: a reference declared merely `indexedForFiltering` carries it just as one declared
	 * `indexedForFilteringAndPartitioning` does, and the reduced indexes exist at both levels. Do **not** tighten this
	 * to require partitioning - the production case this rewrite exists for runs on a `FOR_FILTERING`-only reference,
	 * and `BidirectionalReferenceRewriteFunctionalTest#shouldRewriteCorrectlyWhenBothEndsAreFilteringOnly` pins that.
	 */
	private static boolean referenceUsableInScopes(
		@Nonnull ReferenceSchemaContract reference,
		@Nonnull Set<Scope> scopes
	) {
		for (final Scope scope : scopes) {
			if (!reference.isIndexedInScope(scope)) {
				return false;
			}
			if (!reference.getIndexedComponents(scope).contains(ReferenceIndexedComponents.REFERENCED_ENTITY)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Verifies every attribute the constraints name carries the same values on both ends.
	 *
	 * Presence plus filterability on the counterpart is *not* enough: a reflected reference may inherit attributes
	 * from its original, but it may also declare its own, and a same-named independently declared attribute holds
	 * unrelated values. Only inheritance guarantees the two sides agree - and it is inheritance that also makes the
	 * decimal-places, localisation and uniqueness flags the translators read match.
	 */
	private static boolean attributesMirrored(
		@Nonnull ReferenceSchemaContract ownerReference,
		@Nonnull ReferenceSchemaContract counterpart,
		@Nonnull List<String> attributeNames,
		@Nonnull Set<Scope> scopes
	) {
		// exactly one end of a pair is the reflected one - that is the end whose inheritance decides
		final ReflectedReferenceSchemaContract reflected =
			ownerReference instanceof ReflectedReferenceSchemaContract reflectedOwner ?
				reflectedOwner :
				(counterpart instanceof ReflectedReferenceSchemaContract reflectedCounterpart ?
					reflectedCounterpart : null);
		if (reflected == null) {
			return false;
		}
		for (final String attributeName : attributeNames) {
			if (!isInherited(reflected, attributeName)) {
				return false;
			}
			final Optional<AttributeSchemaContract> attribute = counterpart.getAttribute(attributeName);
			if (attribute.isEmpty()) {
				return false;
			}
			final AttributeSchemaContract attributeSchema = attribute.get();
			for (final Scope scope : scopes) {
				if (!attributeSchema.isFilterableInScope(scope) && !attributeSchema.isUniqueInScope(scope)) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Tells whether the reflected end of the pair inherits the named attribute from its original.
	 */
	private static boolean isInherited(
		@Nonnull ReflectedReferenceSchemaContract reflected,
		@Nonnull String attributeName
	) {
		final String[] inheritanceFilter = reflected.getAttributeInheritanceFilter();
		boolean listed = false;
		for (final String inheritedAttributeName : inheritanceFilter) {
			if (inheritedAttributeName.equals(attributeName)) {
				listed = true;
				break;
			}
		}
		return switch (reflected.getAttributesInheritanceBehavior()) {
			case INHERIT_ALL_EXCEPT -> !listed;
			case INHERIT_ONLY_SPECIFIED -> listed;
		};
	}

	/**
	 * Collects the owner primary keys worth testing - everything the counterpart's type-level index knows about,
	 * narrowed to owners that actually exist in the requested scopes.
	 *
	 * The narrowing is not an optimisation but a correctness requirement: the counterpart's reduced indexes are keyed
	 * by owner primary key regardless of that owner's own scope, so without it the rewrite could emit an owner the
	 * owner-side evaluation would never produce.
	 *
	 * @return ascending owner primary keys, or NULL when a required index is missing and the rewrite must be abandoned
	 */
	@Nullable
	private static CandidateOwners collectCandidateOwners(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull EntitySchemaContract ownerEntitySchema,
		@Nonnull String targetEntityType,
		@Nonnull ReferenceSchemaContract counterpart,
		@Nonnull Set<Scope> counterpartScopes,
		@Nonnull Set<Scope> scopes
	) {
		PersistentRoaringBitmap candidates = null;
		// the reduced indexes the rewrite will actually visit - one per (owner, representative-value partition),
		// which is one per owner only when the counterpart forbids duplicates
		PersistentRoaringBitmap counterpartIndexes = null;
		boolean crossScope = false;
		for (final Scope scope : counterpartScopes) {
			final Optional<ReferencedTypeEntityIndex> typeIndex = queryContext.getEntityIndex(
				targetEntityType,
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, counterpart.getName()),
				ReferencedTypeEntityIndex.class
			);
			if (typeIndex.isEmpty()) {
				if (scopes.contains(scope)) {
					// the counterpart claims to be indexed in a scope the query actually asks for but has no index -
					// do not guess, fall through to the owner-side path
					return null;
				}
				// a scope the query did not ask for, scanned only because a relation *could* span into it. Skip it
				// rather than abandoning the rewrite: a reflected reference whose owner is archived gets no type index
				// at all, so insisting on one here declines every rewrite whose counterpart is the reflected end -
				// the case this rewrite exists for.
				//
				// Note what this does NOT assume. "No index, therefore no rows" is false, and knowingly so: #1583 is
				// exactly a schema declaring the reference indexed in a scope whose rows are present in the entity
				// body with no type index built for them, and a reference declaring only REFERENCED_GROUP_ENTITY in
				// a scope is a second route to it, since `isRelationMaintained` keeps the relation on
				// `isIndexedInScope` alone. Owners whose only qualifying row lives behind such a missing index are
				// under-reported here. That is the missing index, not this loop - narrowing `counterpartScopes` to
				// scopes carrying REFERENCED_ENTITY would remove exactly the scopes this branch already skips and
				// change no answer (it would shift the cache key, nothing more), which
				// `BidirectionalReferenceRewriterTest.WidenedScopeWithoutCounterpartIndex` pins.
				continue;
			}
			// getAllPrimaryKeys() on a type-level index yields the *reduced index* primary keys - the owner entity
			// keys this rewrite is keyed by are the referenced ones
			final PersistentRoaringBitmap scopeCandidates = RoaringBitmapBackedBitmap.getRoaringBitmap(
				typeIndex.get().getAllReferencedPrimaryKeys()
			);
			// `getAllPrimaryKeys()` yields reduced-index instance keys, which are unique per index, so OR-ing them
			// across scopes counts each index exactly once
			final PersistentRoaringBitmap scopeIndexes = RoaringBitmapBackedBitmap.getRoaringBitmap(
				typeIndex.get().getAllPrimaryKeys()
			);
			counterpartIndexes = counterpartIndexes == null ?
				scopeIndexes : PersistentRoaringBitmap.or(counterpartIndexes, scopeIndexes);
			if (!scopes.contains(scope) && !scopeCandidates.isEmpty()) {
				crossScope = true;
			}
			candidates = candidates == null ?
				scopeCandidates : PersistentRoaringBitmap.or(candidates, scopeCandidates);
		}
		if (candidates == null || candidates.isEmpty()) {
			// nothing is announced at all - there is no cheaper end to answer from
			return null;
		}
		final int announced = candidates.getCardinality();
		final int counterpartIndexCount = counterpartIndexes == null ? 0 : counterpartIndexes.getCardinality();

		PersistentRoaringBitmap ownersInScope = null;
		for (final Scope scope : scopes) {
			final Optional<GlobalEntityIndex> globalIndex = queryContext.getGlobalEntityIndexIfExists(
				ownerEntitySchema.getName(), scope
			);
			if (globalIndex.isEmpty()) {
				continue;
			}
			final PersistentRoaringBitmap scopeOwners = RoaringBitmapBackedBitmap.getRoaringBitmap(
				globalIndex.get().getAllPrimaryKeys()
			);
			ownersInScope = ownersInScope == null ?
				scopeOwners : PersistentRoaringBitmap.or(ownersInScope, scopeOwners);
		}
		if (ownersInScope == null) {
			return new CandidateOwners(announced, counterpartIndexCount, new int[0], crossScope);
		}
		return new CandidateOwners(
			announced, counterpartIndexCount,
			PersistentRoaringBitmap.and(candidates, ownersInScope).toArray(), crossScope
		);
	}

	/**
	 * Decides whether answering the constraint from the counterpart end is actually cheaper.
	 *
	 * Both sides of the trade are O(1) bitmap cardinalities available before anything is computed, and **both count
	 * reduced-index instances** - the unit the work is actually done in. The owner side would visit at most one index
	 * per *referenced entity* the owner reference knows about; the counterpart side visits every index its type-level
	 * indexes hold for the candidate owners. The owner-side number is an upper bound - the nested query narrows it
	 * further - which is why a plain "fewer is better" comparison is not enough and a margin is required.
	 *
	 * Counting owners on the counterpart side instead would be wrong as soon as duplicates are allowed: one owner then
	 * maps to one index per representative-value partition, so a gate demanding a {@link #MINIMAL_GAIN}x win could
	 * accept a plan doing many times *more* work. Without duplicates the two counts coincide, so this is a
	 * generalisation rather than a change of behaviour.
	 *
	 * @param candidateOwnerCount   how many owners the per-owner loop would run for - bounds the loop, not the cost
	 * @param counterpartIndexCount how many counterpart reduced indexes that loop would resolve in total
	 * @return TRUE when the rewrite should be taken
	 */
	private static boolean worthRewriting(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull EntitySchemaContract ownerEntitySchema,
		@Nonnull ReferenceSchemaContract ownerReference,
		int candidateOwnerCount,
		int counterpartIndexCount,
		@Nonnull Set<Scope> scopes
	) {
		if (candidateOwnerCount == 0 || candidateOwnerCount > MAX_CANDIDATE_OWNERS) {
			return false;
		}
		PersistentRoaringBitmap ownerSideBuckets = null;
		for (final Scope scope : scopes) {
			final Optional<ReferencedTypeEntityIndex> typeIndex = queryContext.getEntityIndex(
				ownerEntitySchema.getName(),
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, ownerReference.getName()),
				ReferencedTypeEntityIndex.class
			);
			if (typeIndex.isEmpty()) {
				continue;
			}
			// here getAllPrimaryKeys() is exactly right - these *are* the reduced indexes the owner side would visit
			final PersistentRoaringBitmap scopeBuckets = RoaringBitmapBackedBitmap.getRoaringBitmap(
				typeIndex.get().getAllPrimaryKeys()
			);
			ownerSideBuckets = ownerSideBuckets == null ?
				scopeBuckets : PersistentRoaringBitmap.or(ownerSideBuckets, scopeBuckets);
		}
		if (ownerSideBuckets == null) {
			return false;
		}
		return (long) counterpartIndexCount * MINIMAL_GAIN <= ownerSideBuckets.getCardinality();
	}

	/**
	 * Creates the shared formula narrowing the referenced entities - the nested query planned for `entityHaving`, or
	 * the full in-scope set of the referenced collection when there is none.
	 */
	@Nonnull
	private static Formula createReferencedEntityFormula(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull String targetEntityType,
		@Nullable FilterConstraint entityHavingChild,
		@Nonnull Set<Scope> scopes,
		@Nonnull Set<Scope> counterpartScopes,
		@Nonnull ReferenceSchemaContract ownerReference
	) {
		if (entityHavingChild == null) {
			// A bare `referenceHaving` asks only whether the owner holds any row at all, and the owner-side path
			// imposes no scope restriction on the *target* when answering that - a row pointing at a target in another
			// scope still counts. So the narrowing set here has to span every scope a counterpart row can live in,
			// not merely the requested ones, or an owner whose only targets sit in the other scope is dropped.
			// (The `entityHaving` branch below is different: the owner-side path plans its nested query against the
			// requested scopes, so the rewrite must too.)
			final List<Formula> allInScope = new ArrayList<>(counterpartScopes.size());
			for (final Scope scope : counterpartScopes) {
				queryContext.getGlobalEntityIndexIfExists(targetEntityType, scope)
					.ifPresent(it -> allInScope.add(it.getAllPrimaryKeysFormula()));
			}
			return allInScope.isEmpty() ?
				EmptyFormula.INSTANCE : FormulaFactory.or(allInScope.toArray(Formula[]::new));
		}
		final String nestedQueryDescription = "counterpart rewrite of reference `" + ownerReference.getName() + "`";
		final List<GlobalIndexAndFormula> nestedResults = HavingTranslatorHelper.planNestedQuery(
			targetEntityType,
			entityHavingChild,
			filterByVisitor,
			() -> nestedQueryDescription
		);
		final Formula[] nestedFormulas = new Formula[nestedResults.size()];
		for (int i = 0; i < nestedResults.size(); i++) {
			nestedFormulas[i] = nestedResults.get(i).filter();
		}
		final Formula nestedFormula = FormulaFactory.or(nestedFormulas);
		if (nestedFormula instanceof EmptyFormula) {
			// keep the identity the caller short-circuits on - wrapping an empty plan would hide the fact that this
			// constraint can never match, and the wrapper is only needed to hide a *populated* nested tree
			return EmptyFormula.INSTANCE;
		}
		// The nested plan belongs to the *target* collection's namespace, and more than thirty places in the engine
		// walk a planned filter tree matching nodes by reference name, facet id or price accessor without any way to
		// tell whose namespace a node came from. Exposing it as a visible child therefore leaks target-side facets,
		// hierarchy constraints and price accessors into owner-side extra results. The ordinary owner-side path never
		// exposes it either - `HavingTranslatorHelper` buries the identical plan behind a terminal `DeferredFormula`
		// whose child array is empty and whose `getCloneWithInnerFormulas` throws - so this is not a new device, it is
		// the same one, applied at the one place the rewrite had skipped it.
		return new DeferredFormula(
			new FormulaWrapper(
				nestedFormula,
				(executionContext, formula) -> {
					try {
						executionContext.pushStep(QueryPhase.EXECUTION_FILTER_NESTED_QUERY, nestedQueryDescription);
						return formula.compute();
					} finally {
						executionContext.popStep();
					}
				}
			)
		);
	}

	/**
	 * Builds one formula per candidate owner, producing the referenced entity primary keys that owner holds and that
	 * satisfy the reference-attribute constraints. When there are no attribute constraints the index contents are used
	 * verbatim and no constraint translation happens at all.
	 *
	 * @return formulas positionally paired with `candidateOwners`
	 */
	@Nonnull
	private static Formula[] createPerOwnerFormulas(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull EntitySchemaContract targetEntitySchema,
		@Nonnull ReferenceSchemaContract counterpart,
		@Nonnull ProcessingScope<?> processingScope,
		@Nonnull List<FilterConstraint> attributeConstraints,
		@Nonnull int[] candidateOwners,
		@Nonnull Set<Scope> counterpartScopes
	) {
		final String counterpartName = counterpart.getName();
		final AttributeSchemaAccessor attributeSchemaAccessor = new AttributeSchemaAccessor(
			queryContext.getCatalogSchema(), targetEntitySchema
		).withReferenceSchemaAccessor(counterpartName);
		// the split accepts at most one attribute child, so it is handed to the visitor exactly as it was written
		final FilterConstraint attributeConstraint = attributeConstraints.isEmpty() ?
			null : attributeConstraints.get(0);
		final Formula[] result = new Formula[candidateOwners.length];
		final List<ReducedEntityIndex> reusableIndexes = new ArrayList<>(counterpartScopes.size());
		for (int i = 0; i < candidateOwners.length; i++) {
			final int ownerPrimaryKey = candidateOwners[i];
			reusableIndexes.clear();
			// the counterpart's reduced index for an owner lives in the scope of the *target* rows it holds, which is
			// not necessarily one of the requested scopes - see `counterpartScopes`
			for (final Scope scope : counterpartScopes) {
				queryContext.getReducedEntityIndexes(
					scope, ownerPrimaryKey, targetEntitySchema, counterpart, (schema, indexKey) -> null
				).forEach(reusableIndexes::add);
			}
			if (reusableIndexes.isEmpty()) {
				// the owner was announced by the type-level index but carries no reduced index - treat as no match
				result[i] = EmptyFormula.INSTANCE;
				continue;
			}
			final List<ReducedEntityIndex> ownerIndexes = List.copyOf(reusableIndexes);
			if (attributeConstraint == null) {
				final Formula[] plainFormulas = new Formula[ownerIndexes.size()];
				for (int j = 0; j < ownerIndexes.size(); j++) {
					plainFormulas[j] = ownerIndexes.get(j).getAllPrimaryKeysFormula();
				}
				result[i] = FormulaFactory.or(plainFormulas);
			} else {
				result[i] = filterByVisitor.executeInContextAndIsolatedFormulaStack(
					ReducedEntityIndex.class,
					() -> ownerIndexes,
					ReferenceContent.ALL_REFERENCES,
					targetEntitySchema,
					counterpart,
					processingScope.getNestedQueryRestriction(),
					null,
					attributeSchemaAccessor,
					(entityContract, attributeName, locale) -> entityContract.getReferences(counterpartName)
						.stream()
						.map(it -> it.getAttributeValue(attributeName, locale)),
					() -> {
						attributeConstraint.accept(filterByVisitor);
						final Formula[] collectedFormulas = filterByVisitor.getCollectedFormulasOnCurrentLevel();
						return switch (collectedFormulas.length) {
							case 0 -> EmptyFormula.INSTANCE;
							case 1 -> collectedFormulas[0];
							default -> FormulaFactory.and(collectedFormulas);
						};
					},
					EntityPrimaryKeyInSet.class
				);
			}
		}
		return result;
	}

	/**
	 * Returns the transactional id of the counterpart type-level index the candidate owners were read from, so that
	 * the produced formula becomes obsolete as soon as anything about those references changes.
	 */
	private static long counterpartTypeIndexId(
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull String targetEntityType,
		@Nonnull ReferenceSchemaContract counterpart,
		@Nonnull Set<Scope> scopes
	) {
		long id = 0L;
		for (final Scope scope : scopes) {
			id = 31L * id + queryContext.getEntityIndex(
				targetEntityType,
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, counterpart.getName()),
				ReferencedTypeEntityIndex.class
			).map(ReferencedTypeEntityIndex::getId).orElse(0L);
		}
		return id;
	}

	/**
	 * Result of {@link #splitChildren(ReferenceHaving)} - the `entityHaving` payload separated from the pure
	 * reference-attribute constraints, along with every attribute name those constraints touch.
	 *
	 * @param entityHavingChild    the single child of the `entityHaving` container, or NULL when absent
	 * @param attributeConstraints the reference-attribute constraints, conjunctive between themselves
	 * @param attributeNames       every attribute name the constraints name, used for the precondition check
	 */
	private record SplitChildren(
		@Nullable FilterConstraint entityHavingChild,
		@Nonnull List<FilterConstraint> attributeConstraints,
		@Nonnull List<String> attributeNames
	) {
	}

}
