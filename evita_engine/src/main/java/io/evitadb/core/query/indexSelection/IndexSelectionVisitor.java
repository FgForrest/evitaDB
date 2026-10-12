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

package io.evitadb.core.query.indexSelection;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.And;
import io.evitadb.api.query.filter.EntityHaving;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterInScope;
import io.evitadb.api.query.filter.GroupHaving;
import io.evitadb.api.query.filter.HierarchyFilterConstraint;
import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.filter.HierarchyWithinRoot;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ReducedIndexCandidates;
import io.evitadb.core.query.filter.SplitTargetPartitions;
import io.evitadb.core.query.filter.translator.reference.BidirectionalReferenceRewriter;
import io.evitadb.core.query.filter.translator.hierarchy.HierarchyWithinRootTranslator;
import io.evitadb.core.query.filter.translator.hierarchy.HierarchyWithinTranslator;
import io.evitadb.core.query.indexSelection.TargetIndexes.EligibilityObstacle;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.CatalogIndexKey;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.Index;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.bitmap.Bitmap;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This visitor examines {@link Query#getFilterBy()} query and tries to construct multiple {@link TargetIndexes}
 * alternative that can be used to fully interpret the filtering query. These alternative index sets will compete
 * one with another to produce filtering query with minimal execution costs.
 *
 * Currently, the logic is quite stupid - it searches the filter for all constraints within AND relation and when
 * relation or hierarchy query is encountered, it adds specific {@link EntityIndexType#REFERENCED_ENTITY} that contains
 * limited subset of the entities related to that placement/relation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class IndexSelectionVisitor implements ConstraintVisitor {
	private final QueryPlanningContext queryContext;
	@Getter private final List<TargetIndexes<? extends Index<?>>> targetIndexes = new LinkedList<>();
	private final int mainIndexCardinality;
	private FilterByVisitor filterByVisitor;

	public IndexSelectionVisitor(@Nonnull QueryPlanningContext queryContext) {
		this.queryContext = queryContext;
		final Set<Scope> allowedScopes = this.queryContext.getScopes();
		if (this.queryContext.hasEntityGlobalIndex()) {
			final List<GlobalEntityIndex> indexes = Arrays.stream(Scope.values())
				.filter(allowedScopes::contains)
				.map(this.queryContext::getGlobalEntityIndexIfExists)
				.filter(Optional::isPresent)
				.map(Optional::get)
				.toList();
			this.targetIndexes.add(
				new TargetIndexes<>(
					indexes.stream().map(it -> it.getIndexKey().toString()).collect(Collectors.joining(", ")),
					GlobalEntityIndex.class,
					indexes
				)
			);
			this.mainIndexCardinality = indexes
				.stream()
				.map(GlobalEntityIndex::getAllPrimaryKeys)
				.mapToInt(Bitmap::size)
				.sum();
		} else {
			final List<CatalogIndex> indexes = Arrays.stream(Scope.values())
				.filter(allowedScopes::contains)
				.map(it -> this.queryContext.getIndexIfExists(new CatalogIndexKey(it), CatalogIndex.class))
				.filter(Optional::isPresent)
				.map(Optional::get)
				.toList();
			if (!indexes.isEmpty()) {
				this.targetIndexes.add(
					new TargetIndexes<>(
						indexes.stream().map(it -> it.getIndexKey().toString()).collect(Collectors.joining(", ")),
						CatalogIndex.class,
						indexes
					)
				);
			}
			this.mainIndexCardinality = Integer.MIN_VALUE;
		}
	}

	@Override
	public void visit(@Nonnull Constraint<?> constraint) {
		final FilterConstraint filterConstraint = (FilterConstraint) constraint;

		if (filterConstraint instanceof And || filterConstraint instanceof FilterBy) {
			// if query is AND query we may traverse further
			@SuppressWarnings("unchecked") final FilterConstraint[] subConstraints = ((ConstraintContainer<FilterConstraint>) filterConstraint).getChildren();
			for (FilterConstraint subConstraint : subConstraints) {
				subConstraint.accept(this);
			}
		} else if (filterConstraint instanceof FilterInScope inScope) {
			getFilterByVisitor().getProcessingScope().doWithScope(
				EnumSet.of(inScope.getScope()),
				() -> {
					for (FilterConstraint subConstraint : inScope.getChildren()) {
						subConstraint.accept(this);
					}
					return null;
				}
			);
		} else if (filterConstraint instanceof HierarchyFilterConstraint hierarchyFilterConstraint) {
			// if query is hierarchy filtering query targeting different entity
			addHierarchyIndexOption(hierarchyFilterConstraint);
		} else if (filterConstraint instanceof final ReferenceHaving referenceHaving) {
			// if query is hierarchy filtering query targeting different entity
			addReferenceIndexOption(referenceHaving);
			for (FilterConstraint subConstraint : referenceHaving.getChildren()) {
				if (!(subConstraint instanceof EntityHaving) && !(subConstraint instanceof GroupHaving)) {
					subConstraint.accept(this);
				}
			}
		}
	}

	/**
	 * Registers {@link TargetIndexes} that represents hierarchy placement. It finds collection of
	 * {@link EntityIndexType#REFERENCED_ENTITY} indexes that contains all relevant data for entities that
	 * are part of the requested tree. This significantly limits the scope that needs to be examined.
	 *
	 * Inside an `inScope(...)` container of a query over more scopes the candidate is built for the container's
	 * scope only: it is registered with {@link EligibilityObstacle#PARTIAL_SCOPE_COVERAGE} when it holds any index,
	 * and not at all when it holds none.
	 *
	 * An option some of whose node partitions are registry rows is registered with
	 * {@link EligibilityObstacle#NOT_PARTITIONED_INDEX} even when the reference is indexed for partitioning - the rows
	 * were left by its former indexing for filtering only. It stays registered, because the translation of the
	 * constraint and the orderings narrowed by it read the rows, so the query falls back to the global index.
	 */
	private void addHierarchyIndexOption(@Nonnull HierarchyFilterConstraint constraint) {
		constraint.getReferenceName().ifPresent(
			referenceName -> {
				final EntitySchema entitySchema = this.queryContext.getSchema();
				final ReferenceSchemaContract referenceSchema = entitySchema.getReferenceOrThrowException(referenceName);
				if (referenceSchema.isReferencedEntityTypeManaged()) {
					final FilterByVisitor theFilterByVisitor = getFilterByVisitor();
					final Set<Scope> scopes = theFilterByVisitor.getProcessingScope().getScopes();
					for (Scope scope : scopes) {
						if (referenceSchema.getReferenceIndexType(scope) == ReferenceIndexType.NONE) {
							throw new ReferenceNotIndexedException(referenceSchema.getName(), entitySchema, scope);
						}
					}
					final boolean partialScopeCoverage = coversFewerScopesThanQuery(scopes, theFilterByVisitor);

					final Formula requestedHierarchyNodesFormula;
					if (constraint instanceof final HierarchyWithinRoot hierarchyWithinRoot) {
						requestedHierarchyNodesFormula = HierarchyWithinRootTranslator.createFormulaFromHierarchyIndex(
							hierarchyWithinRoot, theFilterByVisitor
						);
					} else if (constraint instanceof final HierarchyWithin hierarchyWithin) {
						requestedHierarchyNodesFormula = HierarchyWithinTranslator.createFormulaFromHierarchyIndex(
							hierarchyWithin, theFilterByVisitor
						);
					} else {
						//sanity check only
						throw new GenericEvitaInternalError("Should never happen");
					}
					if (requestedHierarchyNodesFormula instanceof EmptyFormula) {
						if (partialScopeCoverage) {
							// no node matches in the scopes of the enclosing `inScope` - that empties its branch only,
							// the other queried scopes may still match, so the whole query must not be short-circuited
							// (the translator computes the same empty branch without a registered candidate)
							return;
						}
						// if target entity has no global index present, it means that the query cannot be fulfilled
						// we may quickly return empty result
						this.targetIndexes.add(TargetIndexes.EMPTY);
					}
					// locate all hierarchy indexes - the partitions of the nodes split by representation, so the
					// registered single-owner ones are read out of the registry rather than resolved
					final Bitmap requestedHierarchyNodes = requestedHierarchyNodesFormula.compute();
					final SplitTargetPartitions partitions = this.queryContext.resolvePartitionsOfTargetsForRead(
						entitySchema, referenceSchema, scopes, requestedHierarchyNodes
					);
					final List<ReducedEntityIndex> materializedIndexes = partitions.materializedIndexes();
					if (partialScopeCoverage && materializedIndexes.isEmpty() && partitions.virtualCount() == 0) {
						// no owner in the scopes of the enclosing `inScope` references the nodes - the same reasoning
						// as for the empty node set above: an empty candidate would empty the whole query
						return;
					}
					final long cardinalityLimit = (long) this.mainIndexCardinality / 2;
					// a partition of the nodes the registry holds a row of was left there while the reference was
					// indexed for filtering only, and it holds none of the content an index object of the partitioning
					// keeps - a plan within the option would answer the other constraints from the index objects alone
					// and miss the row's owner, so the option is not partitioned while any of its partitions is a row
					final boolean partitioned = partitions.virtualCount() == 0 &&
						allIndexesArePartitioned(scopes, referenceSchema);
					final boolean withinCardinalityLimit = partitions.getOwnerRowCount() <= cardinalityLimit;
					// without a virtual partition the materialized ones are every partition of the nodes; otherwise a
					// registered partition has no object to resolve, so the supplier cannot answer every partition and
					// the consumers needing the objects read the split instead (see `resolveAllPartitionsOfNodes`)
					final Supplier<List<ReducedEntityIndex>> allIndexes = partitions.virtualCount() == 0 ?
						() -> materializedIndexes :
						() -> resolveAllPartitionsOfNodes(
							entitySchema, referenceSchema, scopes, requestedHierarchyNodes
						);
					// add indexes as potential target indexes
					this.targetIndexes.add(
						new TargetIndexes<>(
							EntityIndexType.REFERENCED_ENTITY.name() +
								" composed of " + requestedHierarchyNodes.size() + " indexes",
							constraint,
							scopes,
							ReducedEntityIndex.class,
							materializedIndexes.size() + partitions.virtualCount(),
							allIndexes,
							partitions,
							Stream.of(
									partitioned ? null : EligibilityObstacle.NOT_PARTITIONED_INDEX,
									withinCardinalityLimit ? null : EligibilityObstacle.HIGH_CARDINALITY,
									partialScopeCoverage ? EligibilityObstacle.PARTIAL_SCOPE_COVERAGE : null
								)
								.filter(Objects::nonNull)
								.toArray(EligibilityObstacle[]::new)
						)
					);
				}
			}
		);
	}

	/**
	 * Resolves the index object of every partition of the hierarchy nodes in every scope, node by node and scope by
	 * scope - the object-only list of a hierarchy option, which reads no partition virtually. It is the list of an
	 * option only when a partition of its nodes is a registry row, and such a partition has no index object: the lookup
	 * through the type index of a reference allowing duplicates fails on it, the lookup by key of one that does not
	 * yields nothing for it. So nothing reads the list: such an option carries
	 * {@link EligibilityObstacle#NOT_PARTITIONED_INDEX} and never wins a separate plan, the consumers of the objects
	 * of a set holding a row skip it (`FilterByVisitor#holdsIndexObjectsOnly`), and the translation of the hierarchy
	 * constraint and the block-by-block orderings narrowed by the option read the split.
	 *
	 * @param entitySchema    the queried entity schema
	 * @param referenceSchema the reference the hierarchy constraint names
	 * @param scopes          the scopes of the partitions
	 * @param nodes           the primary keys of the hierarchy nodes
	 * @return the index objects of the partitions of the nodes
	 */
	@Nonnull
	private List<ReducedEntityIndex> resolveAllPartitionsOfNodes(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Set<Scope> scopes,
		@Nonnull Bitmap nodes
	) {
		final List<ReducedEntityIndex> result = new ArrayList<>(nodes.size() * scopes.size());
		for (Integer hierarchyEntityId : nodes) {
			for (Scope scope : scopes) {
				final ReferencePartition[] partitions = this.queryContext.resolvePartitionsOfTarget(
					entitySchema, referenceSchema, scope, hierarchyEntityId
				);
				for (final ReferencePartition partition : partitions) {
					// the target indexes are evaluated through the query body channel
					result.add(ReferencePartitions.toReducedEntityIndex(partition));
				}
			}
		}
		return result;
	}

	/**
	 * Registers {@link TargetIndexes} that represents hierarchy placement. It finds collection of
	 * {@link EntityIndexType#REFERENCED_ENTITY} indexes that contains all relevant data for entities that
	 * are related to respective entity type and id. This may significantly limit the scope that needs to be examined.
	 *
	 * Inside an `inScope(...)` container of a query over more scopes the candidate is built for the container's
	 * scope only: it is registered with {@link EligibilityObstacle#PARTIAL_SCOPE_COVERAGE} when it holds any index,
	 * and not at all when it holds none.
	 */
	private void addReferenceIndexOption(@Nonnull ReferenceHaving constraint) {
		final EntitySchema entitySchema = this.queryContext.getSchema();
		final ReferenceSchemaContract referenceSchema = entitySchema.getReferenceOrThrowException(constraint.getReferenceName());
		final FilterByVisitor theFilterByVisitor = getFilterByVisitor();
		final Set<Scope> scopes = theFilterByVisitor.getProcessingScope().getScopes();

		// when the constraint will be answered from the counterpart end of a bidirectional reference, the
		// owner-side index set is never consulted - and merely discovering it (one reduced index per matching
		// referenced entity, plus a pass over all their bitmaps for the cardinality check below) is the dominant cost
		// this rewrite exists to avoid. The decision uses schemas and O(1) bitmap cardinalities only, so it can be
		// taken here, before anything is materialised.
		if (
			BidirectionalReferenceRewriter.isApplicable(
				this.queryContext, entitySchema, referenceSchema, constraint, scopes
			)
		) {
			return;
		}

		// discovery only - which partitions qualify, not the partition objects themselves. Resolving them is the
		// expensive half (one index object per partition the reference advertises, six figures on a production
		// catalog) and most candidates are rejected without ever needing them.
		final ReducedIndexCandidates candidates = theFilterByVisitor
			.getReferencedRecordEntityIndexCandidates(constraint, scopes);
		final int candidateCount = candidates.size();
		final boolean partialScopeCoverage = coversFewerScopesThanQuery(scopes, theFilterByVisitor);

		if (candidateCount == 0 && partialScopeCoverage) {
			// an empty candidate built for the scopes of the enclosing `inScope` only would empty the whole query
			// (`IndexSelectionResult#isEmpty`), although the other queried scopes may still match - skip it
		} else {
			final String indexDescription = EntityIndexType.REFERENCED_ENTITY.name() +
				" composed of " + candidateCount + " indexes";
			// `long`, because the sum below counts reference ROWS - an owner occurs in as many partitions as it
			// has rows - and that is not bounded by the owner collection's cardinality. An int sum can overflow
			// to a negative value, which would satisfy the limit and mark the alternative ELIGIBLE: exactly the
			// catastrophic plan this check exists to prevent.
			final long cardinalityLimit = (long) this.mainIndexCardinality / 2;
			final boolean partitioned = allIndexesArePartitioned(scopes, referenceSchema);
			// the candidate count is a sound lower bound on that sum in every state the mutator produces: it
			// un-advertises a partition in the same synchronous step in which its last owner leaves
			// (`ReferenceIndexMutator#referenceRemovalPerComponent`), so an advertised partition has an owner.
			// The load path is the only caller that could present one holding nobody, which
			// `ReducedIndexMembership#registerIndex` accounts for rather than refuses; were such a partition to
			// reach here the count would overstate the sum and raise HIGH_CARDINALITY where the exact sum would
			// not - dropping the alternative plan, which costs a slower query and never a different answer.
			// So a count already over the limit settles HIGH_CARDINALITY without resolving anything - which is
			// what keeps the obstacle reported for a reference that is rejected on its schema before the sum is
			// ever computed.
			final boolean countSettlesCardinality = candidateCount > cardinalityLimit;
			if (!partitioned || countSettlesCardinality || partialScopeCoverage) {
				// every obstacle is decided from the schema, the scopes and the candidate count alone, so
				// the partitions stay unresolved unless something downstream genuinely needs the objects - a candidate
				// covering fewer scopes than the query is ineligible whatever its exact cardinality, so the sum below
				// would buy nothing
				this.targetIndexes.add(
					new TargetIndexes<>(
						indexDescription,
						constraint,
						scopes,
						ReducedEntityIndex.class,
						candidateCount,
						candidates::resolve,
						candidates,
						Stream.of(
								partitioned ? null : EligibilityObstacle.NOT_PARTITIONED_INDEX,
								countSettlesCardinality ? EligibilityObstacle.HIGH_CARDINALITY : null,
								partialScopeCoverage ? EligibilityObstacle.PARTIAL_SCOPE_COVERAGE : null
							)
							.filter(Objects::nonNull)
							.toArray(EligibilityObstacle[]::new)
					)
				);
			} else {
				// the reference is partitioned and the count alone leaves the limit undecided, so the exact sum
				// has to be taken - and taking it needs the partitions anyway
				final List<ReducedEntityIndex> theTargetIndexes = candidates.resolve();
				final long ownerRows = theTargetIndexes.stream()
					.map(ReducedEntityIndex::getAllPrimaryKeys)
					.mapToLong(Bitmap::size)
					.sum();
				this.targetIndexes.add(
					new TargetIndexes<>(
						indexDescription,
						constraint,
						scopes,
						ReducedEntityIndex.class,
						theTargetIndexes,
						candidates,
						ownerRows <= cardinalityLimit
							? new EligibilityObstacle[0]
							: new EligibilityObstacle[]{EligibilityObstacle.HIGH_CARDINALITY}
					)
				);
			}
		}
	}

	/**
	 * Decides whether a candidate built for `candidateScopes` covers fewer scopes than the query requests - which is
	 * the case for every candidate built inside an `inScope(...)` container of a query over more scopes than the one
	 * the container names.
	 *
	 * Such a candidate is a correct answer for the container's branch, but not for the whole query: its partitions hold
	 * no entity of the other queried scopes, so a plan built on it would answer every constraint outside the container
	 * from the narrowed scope alone. See {@link EligibilityObstacle#PARTIAL_SCOPE_COVERAGE}.
	 *
	 * In a valid query the processing scopes are a subset of the query scopes, so inequality is the same as being
	 * a proper subset. An `inScope` naming a scope the query does not request is refused later by its translator;
	 * until then the inequality keeps such a candidate out of the plan as well.
	 *
	 * @param candidateScopes the processing scopes the candidate is built for
	 * @param filterByVisitor the visitor providing the scopes requested by the query
	 * @return `true` when the candidate does not cover every scope the query requests
	 */
	private static boolean coversFewerScopesThanQuery(
		@Nonnull Set<Scope> candidateScopes,
		@Nonnull FilterByVisitor filterByVisitor
	) {
		return !candidateScopes.equals(filterByVisitor.getScopes());
	}

	/**
	 * Checks if all indexes defined by the provided scopes are partitioned according to a specific reference index type
	 * in the given reference schema.
	 *
	 * @param scopes a set of {@link Scope} objects representing the indexes to be checked
	 * @param referenceSchema the {@link ReferenceSchemaContract} containing the reference index type information
	 * @return {@code true} if all indexes within the given scopes are partitioned for filtering and partitioning,
	 *         {@code false} otherwise
	 */
	private static boolean allIndexesArePartitioned(@Nonnull Set<Scope> scopes, @Nonnull ReferenceSchemaContract referenceSchema) {
		return scopes.stream()
			.allMatch(scope -> referenceSchema.getReferenceIndexType(scope) == ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING);
	}

	/**
	 * Retrieves an instance of the {@link FilterByVisitor}. If not already initialized, this method
	 * creates a new instance of {@link FilterByVisitor} using the current {@code queryContext},
	 * an empty collection of referenced attributes, and an empty {@link TargetIndexes}.
	 *
	 * @return the initialized {@link FilterByVisitor} instance
	 */
	@Nonnull
	private FilterByVisitor getFilterByVisitor() {
		if (this.filterByVisitor == null) {
			// create a lightweight visitor that can evaluate referenced attributes index location only
			this.filterByVisitor = new FilterByVisitor(
				this.queryContext,
				Collections.emptyList(),
				TargetIndexes.EMPTY
			);
		}
		return this.filterByVisitor;
	}

}
