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

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.filter.EntityScope;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.GroupHaving;
import io.evitadb.api.query.filter.SeparateEntityScopeContainer;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.exception.ReferenceComponentNotIndexedException;
import io.evitadb.core.query.QueryPlanner;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.core.query.algebra.deferred.FormulaWrapper;
import io.evitadb.core.query.algebra.reference.IndexTaggedFormula;
import io.evitadb.core.query.algebra.reference.ReferenceOwnerTranslatingFormula;
import io.evitadb.core.query.algebra.reference.ReferencedEntityIndexPrimaryKeyTranslatingFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.core.query.filter.NegationResolution;
import io.evitadb.core.query.filter.NestedQueryRestriction;
import io.evitadb.core.query.sort.entity.comparator.EntityNestedQueryComparator;
import io.evitadb.core.query.sort.entity.comparator.EntityNestedQueryComparator.EntityPropertyWithScopes;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.AbstractReducedEntityIndex;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NumberUtils;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PrimitiveIterator.OfInt;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * Shared utility methods for {@link EntityHavingTranslator} and {@link GroupHavingTranslator}.
 * Both translators share identical nested query planning logic and a very similar formula
 * construction flow, differing only in:
 *
 * - the target entity type (referenced entity vs. referenced group entity),
 * - the managed-type check,
 * - the index lookup method for translating matched PKs back to owner entity PKs.
 *
 * This helper extracts the shared logic to avoid duplication.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class HavingTranslatorHelper {

	/**
	 * A functional interface for looking up reduced entity indexes given the entity schema,
	 * the reference schema, and a primary key of the target entity (either referenced entity
	 * or group entity).
	 */
	@FunctionalInterface
	interface ReducedIndexLookup {

		/**
		 * Returns a stream of reduced entity indexes for the given target entity primary key.
		 *
		 * @param entitySchema the schema of the queried entity
		 * @param referenceSchema the schema of the reference
		 * @param targetEntityPk the primary key of the target entity (referenced or group)
		 * @return stream of reduced entity indexes
		 */
		@Nonnull
		Stream<? extends EntityIndex> lookup(
			@Nonnull EntitySchemaContract entitySchema,
			@Nonnull ReferenceSchemaContract referenceSchema,
			int targetEntityPk
		);
	}

	/**
	 * Represents a combination of a global entity index and a corresponding filter formula.
	 * This record is used within the context of query planning and execution to encapsulate
	 * the necessary components for filtering entities based on specific criteria.
	 *
	 * @param globalIndex The global entity index that contains a complete set of indexed data,
	 *                    including their bodies, for all entities in the collection.
	 * @param filter      The formula that represents the filter constraints applied to the entities
	 *                    in the global index.
	 */
	public record GlobalIndexAndFormula(
		@Nullable GlobalEntityIndex globalIndex,
		@Nonnull Formula filter
	) {
	}

	/**
	 * Plans and constructs a nested query for the provided target entity type and filter constraint.
	 * The formula is cached and computed only once to avoid redundant computation. When the target
	 * entity doesn't have a global index, an empty formula is returned (since no entities are present
	 * there). The filter is still checked against the target entity schema in every scope without a global index,
	 * so that a filter that cannot be evaluated fails the query whether the entities exist or not.
	 *
	 * @param targetEntityType         the type of the target entity for which the nested query is being planned
	 * @param filter                   the filter constraint that applies the necessary filtering logic
	 * @param filterByVisitor          the visitor object used for traversing and processing filter constraints
	 * @param taskDescriptionSupplier  a supplier that provides a task description used in exception messages
	 *                                 and logging
	 * @return list of {@link GlobalIndexAndFormula} objects containing global entity indexes and
	 *         filter formulas resulting from planning the nested query
	 */
	@Nonnull
	public static List<GlobalIndexAndFormula> planNestedQuery(
		@Nonnull String targetEntityType,
		@Nonnull FilterConstraint filter,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull Supplier<String> taskDescriptionSupplier
	) {
		final ProcessingScope<?> processingScope = filterByVisitor.getProcessingScope();
		final EntityCollection targetEntityCollection = filterByVisitor.getEntityCollectionOrThrowException(
			targetEntityType, taskDescriptionSupplier
		);
		final List<GlobalEntityIndex> globalIndexes = processingScope.getScopes()
			.stream()
			.map(
				scope -> targetEntityCollection.getIndexByKeyIfExists(
					new EntityIndexKey(EntityIndexType.GLOBAL, scope)
				)
			)
			.filter(Objects::nonNull)
			.map(GlobalEntityIndex.class::cast)
			.toList();

		// a scope the target entity type holds no entity of has no index and the nested query is not planned there,
		// but the filter is still checked against the entity schema in that scope, so that the query does not fail or
		// pass depending on the data - nothing can match there, so the formula of the check is not used
		if (globalIndexes.size() < processingScope.getScopes().size()) {
			final Set<Scope> scopesWithoutIndex = EnumSet.copyOf(processingScope.getScopes());
			globalIndexes.forEach(it -> scopesWithoutIndex.remove(it.getIndexKey().scope()));
			FilterByVisitor.createFormulaForTheFilter(
				filterByVisitor.getQueryContext(),
				scopesWithoutIndex,
				filter instanceof FilterBy filterBy ? filterBy : new FilterBy(filter),
				null,
				targetEntityType,
				taskDescriptionSupplier
			);
		}

		if (globalIndexes.isEmpty()) {
			return List.of(new GlobalIndexAndFormula(null, EmptyFormula.INSTANCE));
		} else {
			// the restriction is a set of primary keys, and primary keys only mean anything inside one collection -
			// so it narrows the nested query it was built for and passes every other one through untouched. The
			// `groupHaving` route arrives here with the reference's *group* type, whose keys live in an unrelated
			// universe, and would otherwise be intersected with the referenced entities' keys and come out empty
			final NestedQueryRestriction restriction = processingScope.getNestedQueryRestriction();
			final FilterConstraint enrichedConstraint = restriction == null ?
				filter :
				restriction.applyTo(
					processingScope.getReferenceSchema() == null ?
						null : processingScope.getReferenceSchema().getName(),
					targetEntityType,
					filter
				);
			final FilterBy combinedFilterBy = enrichedConstraint instanceof FilterBy fb ?
				fb : new FilterBy(enrichedConstraint);
			final Optional<EntityNestedQueryComparator> entityNestedQueryComparator =
				ofNullable(processingScope.getEntityNestedQueryComparator());

			return globalIndexes
				.stream()
				.map(globalIndex -> new GlobalIndexAndFormula(
						globalIndex,
						filterByVisitor.computeOnlyOnce(
							Collections.singletonList(globalIndex),
							combinedFilterBy,
							() -> {
								// ordered - a unique lookup in the nested query prefers the scope its `scope(...)` lists first
								final Set<Scope> targetedScopes = CollectionUtils.createLinkedHashSet(4);
								Collections.addAll(
									targetedScopes,
									ofNullable(
										QueryUtils.findConstraint(
											combinedFilterBy, EntityScope.class,
											SeparateEntityScopeContainer.class
										)
									).map(EntityScope::getScopesInRequestedOrder)
										.orElseGet(() -> new Scope[]{
											globalIndex.getIndexKey().scope()
										})
								);
								final QueryPlanningContext nestedQueryContext = entityNestedQueryComparator
									.map(it -> {
										final Optional<EntityPropertyWithScopes> orderBy =
											ofNullable(it.getOrderBy());
										orderBy.ifPresent(
											ob -> targetedScopes.addAll(ob.scopes())
										);
										return targetEntityCollection.createQueryContext(
											filterByVisitor.getQueryContext(),
											filterByVisitor.getEvitaRequest().deriveCopyWith(
												targetEntityType,
												combinedFilterBy,
												orderBy
													.map(EntityPropertyWithScopes::createStandaloneOrderBy)
													.orElse(null),
												it.getLocale(),
												targetedScopes
											),
											filterByVisitor.getEvitaSession()
										);
									})
									.orElseGet(
										() -> targetEntityCollection.createQueryContext(
											filterByVisitor.getQueryContext(),
											filterByVisitor.getEvitaRequest().deriveCopyWith(
												targetEntityType,
												combinedFilterBy,
												null,
												null,
												targetedScopes
											),
											filterByVisitor.getEvitaSession()
										)
									);

								return QueryPlanner.planNestedQuery(
									nestedQueryContext, taskDescriptionSupplier
								).getFilter();
							}
						)
					)
				).toList();
		}
	}

	/**
	 * Verifies that the reference maintains its {@link ReferenceIndexedComponents#REFERENCED_ENTITY} index family in
	 * the passed scope, and refuses the lookup that is about to read it when it does not.
	 *
	 * Every query path over a reference - `referenceHaving` (including under `not`), `hierarchyWithin`,
	 * `hierarchyOfReference`, `referenceContent` with a filter or with an ordering by a predecessor attribute,
	 * `referenceProperty` ordering, the histograms of `referenceSummaryOfReference` and `histogramHaving`, which is
	 * rewritten into a `referenceHaving` - reads the reduced entity indexes, and only the entity component builds them.
	 * A scope indexed for {@link ReferenceIndexedComponents#REFERENCED_GROUP_ENTITY} alone, or with no component at
	 * all, reports itself indexed while holding none of them, so every such path answers it with an empty result - and
	 * a `not` around it with the whole collection. Neither answer is distinguishable from a genuine one. The schema
	 * rule in `ReferenceSchema#validate` refuses the shape, but it runs only when a schema changes, never on load, so a
	 * catalog stored before the rule existed still carries it and reaches this guard.
	 *
	 * The guard is called where the entity index family is looked up - `FilterByVisitor#getReducedIndexPrimaryKeyFormula`,
	 * `QueryPlanningContext#getReducedEntityIndexes`, the chain-index lookup of `ReferenceOrderByVisitor` and the
	 * type-index lookup of `ReferenceHistogramAccumulator` - rather than once per constraint, so that no path reading
	 * the family can bypass it; `ReferencePropertyTranslator` checks every ordered scope up front, because it may be
	 * handed a reduced index set that index selection narrowed with `inScope(...)` and never look the family up at
	 * all. It judges **the schema only**: an indexed scope that holds no rows legitimately has no index and must keep
	 * answering with an empty result, so the absence of an index is never taken as evidence of the misconfiguration.
	 *
	 * A scope in which the reference is not indexed at all is not this guard's concern - the lookups handle it on
	 * their own, and the ones that refuse it do so with {@link io.evitadb.core.exception.ReferenceNotIndexedException},
	 * whose message names the real problem.
	 *
	 * @param entitySchema    schema of the entity owning the reference
	 * @param referenceSchema schema of the reference whose entity index family is about to be read
	 * @param scope           the scope the lookup reads
	 * @param queriedScopes   every scope the query asks for; the message offers narrowing the query as a workaround
	 *                        only when there is another scope to narrow it to
	 * @throws ReferenceComponentNotIndexedException when the reference is indexed in the scope without the entity
	 *                                               component
	 */
	public static void assertEntityComponentIndexed(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Scope scope,
		@Nonnull Set<Scope> queriedScopes
	) {
		if (
			referenceSchema.isIndexedInScope(scope) &&
				!referenceSchema.getIndexedComponents(scope).contains(ReferenceIndexedComponents.REFERENCED_ENTITY)
		) {
			throw new ReferenceComponentNotIndexedException(
				"Reference `" + referenceSchema.getName() + "` of entity `" + entitySchema.getName() +
					"` is indexed in scope `" + scope.name() + "` without the `REFERENCED_ENTITY` indexed component, " +
					"so it holds none of the reduced entity indexes that filtering, hierarchy, ordering and histogram " +
					"queries over it read, and it cannot answer them in that scope (facet summaries are not affected). " +
					entityComponentSchemaFix(referenceSchema, scope.name()) + storedDataNotReindexed() +
					narrowingRemedy(scope, queriedScopes)
			);
		}
	}

	/**
	 * Verifies that the reference maintains its {@link ReferenceIndexedComponents#REFERENCED_GROUP_ENTITY} index in
	 * every queried scope in which it is indexed, and rejects a `groupHaving` that reads it when it does not.
	 *
	 * Declaring a referenced group type does not by itself make the engine maintain group indexes -
	 * {@link ReferenceSchemaContract#getIndexedComponents(Scope)} decides that, per scope, and it defaults to
	 * {@link ReferenceIndexedComponents#REFERENCED_ENTITY} alone. Without the group component the reduced group
	 * indexes {@link GroupRowLookup#createIndexLocalGroupFormula(ReducedEntityIndex)} reads were never built, so
	 * every index contributes
	 * {@link EmptyBitmap#INSTANCE} and the constraint silently matches nothing - while a `not` around it matches
	 * *everything*, the complement of the empty set. Neither answer is distinguishable from a genuine result, which
	 * is what makes the silence dangerous: it is how a fixture in `ReferenceHavingRowSemanticsFunctionalTest` passed
	 * while proving nothing, and how a real defect came to be recorded as refuted.
	 *
	 * **One such scope among those queried is enough to refuse.** A union over several scopes that silently drops
	 * the rows of one of them is a partial answer that looks exactly like a complete one - an owner carrying the
	 * requested group in the scope without the component would simply be missing. The message therefore names the
	 * offending scopes and the ways out: indexing the component there, or - when the query spans other scopes that
	 * carry it - narrowing the query to them with `scope(...)`, or confining the constraint to them with `inScope(...)`.
	 *
	 * It stays silent when the reference is indexed in none of the queried scopes, leaving that case to the
	 * {@link io.evitadb.core.exception.ReferenceNotIndexedException} the throwing stub built by
	 * {@link ReferencedTypeEntityIndex#createThrowingStub} already raises. That message names the real problem -
	 * the reference is not indexed at all - and is strictly better than the one below. A queried scope that does not
	 * index the reference is skipped for the same reason.
	 *
	 * The entity-component counterpart is {@link #assertEntityComponentIndexed}, which guards the lookups of the
	 * reduced entity indexes rather than one constraint, because every query path over a reference reads them.
	 *
	 * @param groupHaving     the constraint being translated, quoted back in the error message
	 * @param entitySchema    schema of the entity being queried
	 * @param referenceSchema schema of the reference the constraint is nested in
	 * @param scopes          the scopes the query asked for
	 * @throws ReferenceComponentNotIndexedException when a queried scope indexes the reference without the group
	 *                                               component
	 */
	static void assertGroupComponentIndexed(
		@Nonnull GroupHaving groupHaving,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Set<Scope> scopes
	) {
		final EnumSet<Scope> missingScopes = EnumSet.noneOf(Scope.class);
		for (final Scope scope : Scope.values()) {
			if (
				scopes.contains(scope) &&
					referenceSchema.isIndexedInScope(scope) &&
					!referenceSchema.getIndexedComponents(scope).contains(
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
			) {
				missingScopes.add(scope);
			}
		}
		if (missingScopes.isEmpty()) {
			return;
		}
		final String missingScopeNames = scopeNames(missingScopes);
		final String fix = referenceSchema instanceof ReflectedReferenceSchemaContract reflected &&
			reflected.isIndexedComponentsInherited() ?
			inheritedComponentsFix(reflected, "REFERENCED_GROUP_ENTITY", missingScopeNames) :
			"Add `REFERENCED_GROUP_ENTITY` to `indexedComponentsInScopes` of reference `" + referenceSchema.getName() +
				"` in scope(s) `" + missingScopeNames + "`.";
		throw new ReferenceComponentNotIndexedException(
			"Filtering constraint `" + groupHaving + "` targets reference `" + referenceSchema.getName() +
				"` of entity `" + entitySchema.getName() + "`, but that reference does not index its referenced " +
				"group entity in the queried scope(s) `" + missingScopeNames + "` it is indexed in. Declaring a group " +
				"type alone builds no group index, so the constraint could never match anything there. " + fix +
				storedDataNotReindexed() + narrowingRemedy(missingScopes, scopes)
		);
	}

	/**
	 * Describes the schema change that gives a reference the {@link ReferenceIndexedComponents#REFERENCED_ENTITY}
	 * component in a scope. A reflected reference inheriting its components takes them from the reference it
	 * reflects, so the component cannot be added to it directly - the message points at the inheritance instead.
	 *
	 * @param referenceSchema the reference lacking the component
	 * @param scopeNames      the scope or comma-separated scopes lacking it
	 * @return the sentence telling how to fix the schema
	 */
	@Nonnull
	private static String entityComponentSchemaFix(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String scopeNames
	) {
		if (referenceSchema instanceof ReflectedReferenceSchemaContract reflected && reflected.isIndexedComponentsInherited()) {
			return inheritedComponentsFix(reflected, "REFERENCED_ENTITY", scopeNames);
		} else {
			return "Fix the schema: add `REFERENCED_ENTITY` to `indexedComponentsInScopes` of reference `" +
				referenceSchema.getName() + "` in scope `" + scopeNames + "`.";
		}
	}

	/**
	 * Describes the schema change for a reflected reference that inherits its indexed components: it has none of its
	 * own to add the missing one to, so it has to declare them explicitly - or the reference it reflects has to carry
	 * the component in the scope.
	 *
	 * @param reflected  the reflected reference lacking the component
	 * @param component  name of the missing component
	 * @param scopeNames the scope or comma-separated scopes lacking it
	 * @return the sentence telling how to fix the schema
	 */
	@Nonnull
	private static String inheritedComponentsFix(
		@Nonnull ReflectedReferenceSchemaContract reflected,
		@Nonnull String component,
		@Nonnull String scopeNames
	) {
		return "Reference `" + reflected.getName() + "` inherits its indexed components from reference `" +
			reflected.getReflectedReferenceName() + "` of entity `" + reflected.getReferencedEntityType() +
			"`, which carries no `" + component + "` in scope `" + scopeNames + "`. Fix the schema: declare the " +
			"indexed components of reference `" + reflected.getName() + "` explicitly, with `" + component +
			"` in scope `" + scopeNames + "`, or index reference `" + reflected.getReflectedReferenceName() +
			"` in that scope with it.";
	}

	/**
	 * Completes an error message of the indexed-component guards with the workaround that avoids the scopes a
	 * reference cannot answer in: narrowing the whole query to the other queried scopes with `scope(...)`, or
	 * confining the constraint to them with `inScope(...)`. When the query asks for no other scope there is nothing to
	 * narrow it to, and only the schema fix is left.
	 *
	 * @param unanswerableScopes the scopes the query must avoid
	 * @param queriedScopes      every scope the query asks for
	 * @return the closing sentence of the message, or an empty string when no workaround exists
	 */
	@Nonnull
	private static String narrowingRemedy(@Nonnull Set<Scope> unanswerableScopes, @Nonnull Set<Scope> queriedScopes) {
		final EnumSet<Scope> answerableScopes = EnumSet.noneOf(Scope.class);
		for (final Scope scope : queriedScopes) {
			if (!unanswerableScopes.contains(scope)) {
				answerableScopes.add(scope);
			}
		}
		if (answerableScopes.isEmpty()) {
			return "";
		}
		return " As a workaround, query only the scope(s) that can answer: narrow the query with `scope(" +
			scopeNames(answerableScopes) + ")`, or wrap the constraint in `inScope(...)` naming only them.";
	}

	/**
	 * Single-scope form of {@link #narrowingRemedy(Set, Set)}.
	 *
	 * @param unanswerableScope the scope the query must avoid
	 * @param queriedScopes     every scope the query asks for
	 * @return the closing sentence of the message, or an empty string when no workaround exists
	 */
	@Nonnull
	private static String narrowingRemedy(@Nonnull Scope unanswerableScope, @Nonnull Set<Scope> queriedScopes) {
		return narrowingRemedy(EnumSet.of(unanswerableScope), queriedScopes);
	}

	/**
	 * Lists the scope names in {@link Scope} declaration order, separated by commas.
	 *
	 * @param scopes the scopes to list
	 * @return the comma-separated names
	 */
	@Nonnull
	private static String scopeNames(@Nonnull Set<Scope> scopes) {
		final StringBuilder names = new StringBuilder(32);
		for (final Scope scope : Scope.values()) {
			if (scopes.contains(scope)) {
				if (!names.isEmpty()) {
					names.append(", ");
				}
				names.append(scope.name());
			}
		}
		return names.toString();
	}

	/**
	 * Warns that adding a component to a collection which already holds data does not index what is already stored.
	 * The indexes a component builds are filled only as entities are written, so the schema fix alone leaves every
	 * entity stored before it invisible to the queries it enables - a partial answer that looks complete.
	 *
	 * @return the sentence, to be placed right after the schema fix it qualifies
	 */
	@Nonnull
	private static String storedDataNotReindexed() {
		return " On a collection that already holds data the schema change does not index the entities already " +
			"stored - they are indexed only as they are written - so data written before the change stays invisible " +
			"to these queries until it is written again.";
	}

	/**
	 * Translates a having constraint (either {@link io.evitadb.api.query.filter.EntityHaving} or
	 * {@link GroupHaving}) into a formula that computes owner entity
	 * primary keys matching the constraint.
	 *
	 * @param filterConstraint          the child filter constraint from the having container
	 * @param filterByVisitor           the visitor for traversing filter constraints
	 * @param entitySchema              the schema of the queried entity
	 * @param referenceSchema           the reference schema
	 * @param targetEntityType          the type of the target entity (referenced or group)
	 * @param isTargetManaged           whether the target entity type is managed by evitaDB
	 * @param typeLevelIndexType        identifies which type-level index this constraint is filtering
	 *                                  against: {@link EntityIndexType#REFERENCED_ENTITY_TYPE} when
	 *                                  invoked from {@code EntityHavingTranslator} (target PKs are
	 *                                  referenced entity PKs), or
	 *                                  {@link EntityIndexType#REFERENCED_GROUP_ENTITY_TYPE} when
	 *                                  invoked from {@code GroupHavingTranslator} (target PKs are
	 *                                  group entity PKs). The two index spaces are keyed by different
	 *                                  PK universes, so the discovery-phase branch below must skip
	 *                                  the referenced-entity PK translation when the target is the
	 *                                  group type.
	 * @param reducedIndexLookup        the function to look up reduced entity indexes for a target PK
	 * @param nestedQueryDescription    a supplier for the description of this nested query
	 * @return a formula computing matching owner entity primary keys
	 */
	@Nonnull
	static Formula translateHavingConstraint(
		@Nonnull FilterConstraint filterConstraint,
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String targetEntityType,
		boolean isTargetManaged,
		@Nonnull EntityIndexType typeLevelIndexType,
		@Nonnull ReducedIndexLookup reducedIndexLookup,
		@Nonnull Supplier<String> nestedQueryDescription
	) {
		@SuppressWarnings("unchecked")
		final ProcessingScope<EntityIndex> processingScope =
			(ProcessingScope<EntityIndex>) filterByVisitor.getProcessingScope();

		return FormulaFactory.or(
			planNestedQuery(
				targetEntityType,
				filterConstraint,
				filterByVisitor,
				nestedQueryDescription
			)
				.stream()
				.map(nestedResult -> {
					if (ReferencedTypeEntityIndex.class.isAssignableFrom(processingScope.getIndexType())) {
						return switch (typeLevelIndexType) {
							// In the index-discovery phase (scope holds a ReferencedTypeEntityIndex) the type-level
							// index is keyed by referenced entity PKs, not group PKs, so it cannot be asked about
							// groups. When the caller re-evaluates every candidate row afterwards (candidate mode),
							// the reduced indexes of the targets the matching groups hold are a safe superset -
							// and a far smaller one than the whole family, which every later per-index step
							// multiplies. When the returned set IS the answer (strict mode), the unrestricted set
							// is kept, exactly as before.
							case REFERENCED_GROUP_ENTITY_TYPE -> FormulaFactory.or(
								processingScope
									.getIndexStream()
									.filter(it -> processingScope.getScopes().contains(it.getIndexKey().scope()))
									.map(ReferencedTypeEntityIndex.class::cast)
									.map(
										it -> processingScope.getNegationResolution() == NegationResolution.PER_ROW ?
											groupNarrowedReducedIndexPksFormula(
												it,
												getGroupRowLookup(
													filterByVisitor, entitySchema, referenceSchema, nestedResult
												)
											) :
											allReducedIndexPksFormula(it)
									)
									.toArray(Formula[]::new)
							);
							case REFERENCED_ENTITY_TYPE -> FormulaFactory.or(
								processingScope
									.getIndexStream()
									.filter(it -> processingScope.getScopes().contains(it.getIndexKey().scope()))
									.map(ReferencedTypeEntityIndex.class::cast)
									.map(
										it -> new ReferencedEntityIndexPrimaryKeyTranslatingFormula(
											referenceSchema,
											targetEntityType,
											isTargetManaged,
											filterByVisitor::getGlobalEntityIndexIfExists,
											it,
											nestedResult.filter(),
											processingScope.getReferencedEntityExpansionFunction()
										)
									)
									.toArray(Formula[]::new)
							);
							default -> throw new GenericEvitaInternalError(
								"Unsupported type-level index type for having constraint: " + typeLevelIndexType
							);
						};
					} else if (typeLevelIndexType == EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE) {
						// BRANCH B below resolves reduced indexes from the *collection*, so inside a reduced index
						// it answers "does this owner have any row whose group matches" instead of "does the row
						// held by THIS index have a matching group". The two differ for every owner holding several
						// rows of the same reference, which makes the group filter cross-row and lets it combine
						// wrongly with sibling constraints and with `not`. Evaluate per index instead.
						if (nestedResult.globalIndex() == null) {
							return EmptyFormula.INSTANCE;
						}
						final GroupRowLookup groupRowLookup = getGroupRowLookup(
							filterByVisitor, entitySchema, referenceSchema, nestedResult
						);
						// only a reduced ENTITY index holds rows keyed by a referenced entity; a group index in the
						// scope would have its group PK misread as a target, so it is filtered out explicitly
						return FormulaFactory.or(
							processingScope
								.getIndexStream()
								.filter(it -> processingScope.getScopes().contains(it.getIndexKey().scope()))
								.filter(ReducedEntityIndex.class::isInstance)
								.map(ReducedEntityIndex.class::cast)
								.map(groupRowLookup::createIndexLocalGroupFormula)
								.filter(it -> it != EmptyFormula.INSTANCE)
								.toArray(Formula[]::new)
						);
					} else if (processingScope.getReferenceSchema() != null &&
						AbstractReducedEntityIndex.class.isAssignableFrom(processingScope.getIndexType())) {
						// BRANCH B below resolves the reduced indexes from the *collection*, so inside a reduced
						// index it answers "does this owner have any row whose target matches" instead of "does the
						// row held by THIS index have a matching target" - the same cross-row reading the group
						// branch above exists to avoid. A `not` around it then complements an owner-level set and
						// answers "this owner references nothing matching", dropping every owner holding a matching
						// row AND a non-matching one.
						// the guard names the ABSTRACT class deliberately, and narrowing it to `ReducedEntityIndex`
						// silently reopens the defect on the fetch path: `ReferenceHavingTranslator` declares its
						// scope as `ReducedEntityIndex`, but `ReferencedEntityFetcher#computeResultWithPassedIndex`
						// declares the superclass - it may legitimately be handed a group index - even though the
						// index it passes is a reduced entity one. Which rows survive a filtered `referenceContent`
						// is decided here too, so both scopes have to reach this branch. Dispatching on the actual
						// indexes rather than on the declared class is what the filter below does.
						if (nestedResult.globalIndex() == null) {
							return EmptyFormula.INSTANCE;
						}
						// A reduced entity index is keyed by the one referenced entity it holds rows for, so the
						// nested query's verdict is the same for every row in it: all of its owners, or none. The
						// verdict is therefore settled here, once per index, against the nested result computed a
						// single time - rather than by a per-index formula that scans the whole nested result at
						// execution to find its one target. That per-index formula also embedded the nested filter,
						// so every one of them carried a copy of its transactional ids, which every enclosing `or`
						// concatenated again: quadratic in time and in memory in the size of the index family.
						// The membership decision lives in the SHAPE of the tree (which indexes contribute), so a
						// changed nested result yields a different formula and can never be served a stale cache
						// hit; each contribution is a constant over the index's own transactional bitmap.
						// The nested filter was initialised by `computeOnlyOnce` in `planNestedQuery` and memoizes
						// its result, so every plan alternative and every per-index call shares one computation.
						final Bitmap matchingTargets = nestedResult.filter().compute();
						if (matchingTargets.isEmpty()) {
							return EmptyFormula.INSTANCE;
						}
						// an index whose target does not match contributes nothing and is not tagged at all: the
						// transposer reads an absent tag exactly as an empty one - dropped under `or`, emptying the
						// row under `and`, and the row's whole owner set under a negation
						return FormulaFactory.or(
							processingScope
								.getIndexStream()
								.filter(it -> processingScope.getScopes().contains(it.getIndexKey().scope()))
								.filter(ReducedEntityIndex.class::isInstance)
								.map(ReducedEntityIndex.class::cast)
								.filter(it -> matchingTargets.contains(it.getReferenceKey().primaryKey()))
								.map(HavingTranslatorHelper::createIndexLocalTargetFormula)
								.filter(it -> it != EmptyFormula.INSTANCE)
								.toArray(Formula[]::new)
						);
					} else {
						if (nestedResult.globalIndex() == null) {
							return EmptyFormula.INSTANCE;
						}
						return filterByVisitor.computeOnlyOnce(
							List.of(nestedResult.globalIndex()),
							filterConstraint,
							() -> {
								final ReferenceOwnerTranslatingFormula outputFormula =
									new ReferenceOwnerTranslatingFormula(
										nestedResult.globalIndex(),
										nestedResult.filter(),
										it -> {
											final PersistentRoaringBitmap combinedResult = PersistentRoaringBitmap.or(
												reducedIndexLookup.lookup(entitySchema, referenceSchema, it)
													.map(EntityIndex::getAllPrimaryKeys)
													.map(RoaringBitmapBackedBitmap::getRoaringBitmap)
													.toArray(PersistentRoaringBitmap[]::new)
											);
											return combinedResult.isEmpty() ?
												EmptyBitmap.INSTANCE : new BaseBitmap(combinedResult);
										}
									);
								return new DeferredFormula(
									new FormulaWrapper(
										outputFormula,
										(executionContext, formula) -> {
											try {
												executionContext.pushStep(
													QueryPhase.EXECUTION_FILTER_NESTED_QUERY,
													nestedQueryDescription
												);
												return formula.compute();
											} finally {
												executionContext.popStep();
											}
										}
									)
								);
							},
							2L,
							// we need to add exact pointers to the entity schema and reference schema,
							// which play role in the lambda evaluation
							NumberUtils.pack(
								System.identityHashCode(entitySchema),
								System.identityHashCode(referenceSchema)
							),
							nestedResult.globalIndex().getPrimaryKey()
						);
					}
				})
				.toArray(Formula[]::new)
		);
	}

	/**
	 * Builds the row-exact owner formula contributed by one reduced entity index whose target matched the nested
	 * query of an `entityHaving` body.
	 *
	 * The verdict of the nested query is constant across the whole index - it is keyed by the one referenced entity
	 * it holds rows for - so the contribution of a matching index is simply all of its owners. The result carries an
	 * {@link IndexTaggedFormula}, because an untagged leaf is kept whole for every index by
	 * {@link ReferenceBodyTransposer} - which is precisely the owner-level reading the per-index evaluation exists
	 * to avoid.
	 *
	 * @param targetIndex the reduced entity index whose target matched
	 * @return the tagged owner formula, or {@link EmptyFormula#INSTANCE} when the index holds no owner
	 */
	@Nonnull
	private static Formula createIndexLocalTargetFormula(@Nonnull ReducedEntityIndex targetIndex) {
		final Formula owners = targetIndex.getAllPrimaryKeysFormula();
		return owners instanceof EmptyFormula ?
			EmptyFormula.INSTANCE : new IndexTaggedFormula(targetIndex.getPrimaryKey(), owners);
	}

	/**
	 * Returns the {@link GroupRowLookup} for the matching groups of one nested `groupHaving` query, building it on
	 * the first request and memoizing it in the planning context for the rest of the plan.
	 *
	 * The memo matters more than it looks: `ReferencedEntityFetcher` evaluates a filtered `referenceContent` once per
	 * reduced index, each time with a single-index scope, and the index-discovery pass and the per-index pass of
	 * one `referenceHaving` both need the same lookup. Rebuilding it on every call would cost the matching groups'
	 * index resolution once per index - worse than the per-group scan it replaces.
	 *
	 * Neither caller can hand over the same constraint instance twice - the fetch path re-translates its filter per
	 * index and `histogramHaving` rewrites itself anew on every translation - so the memo is keyed by value, see
	 * {@link GroupRowLookupKey}.
	 *
	 * @param filterByVisitor visitor providing the planning context and the processing scope
	 * @param entitySchema    schema of the entity owning the reference
	 * @param referenceSchema schema of the reference carrying the group
	 * @param nestedResult    the planned nested query over one group global index
	 * @return the lookup, empty when the group collection has no global index in the scope
	 */
	@Nonnull
	static GroupRowLookup getGroupRowLookup(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull GlobalIndexAndFormula nestedResult
	) {
		if (nestedResult.globalIndex() == null) {
			return GroupRowLookup.EMPTY;
		}
		return filterByVisitor.getQueryContext().computeOncePerKey(
			new GroupRowLookupKey(
				entitySchema.getName(),
				referenceSchema.getName(),
				Set.copyOf(filterByVisitor.getProcessingScope().getScopes()),
				nestedResult.filter()
			),
			() -> GroupRowLookup.build(
				filterByVisitor, entitySchema, referenceSchema, nestedResult.filter().compute()
			)
		);
	}

	/**
	 * Returns a formula carrying the reduced-index primary keys that can possibly hold a row of one of the groups the
	 * `groupHaving` matched - the candidate set used in the index-discovery phase when every candidate row is
	 * re-evaluated afterwards.
	 *
	 * The type-level index in scope is keyed by referenced entity PKs, so it cannot be asked about groups directly.
	 * The reduced group indexes of the matching groups can: each lists the referenced entities it holds rows for, and
	 * the reduced entity indexes of those targets - every representative variant of them - are a superset of the rows
	 * the group condition can accept. The superset is all this phase promises; the rows are re-examined per index.
	 * Narrowing here is what keeps every later per-index step proportional to the rows of the selected group rather
	 * than to the whole reference family, which a value condition shared by many groups (one attribute holding
	 * widths, heights and weights alike) would otherwise select almost entirely.
	 *
	 * Three rules keep the result identical to the unrestricted one wherever it has to be:
	 *
	 * - the type index is read **first and unconditionally**: a reference not indexed in the scope is represented by
	 *   a throwing stub, and that read is what raises its `ReferenceNotIndexedException`;
	 * - an empty set is {@link EmptyFormula#INSTANCE}, never a {@link ConstantFormula} over an empty bitmap, which that
	 *   class refuses.
	 *
	 * A broad group filter does not need a fallback to the unrestricted set: the union of its targets is built once
	 * per plan and scope ({@link GroupRowLookup#getTargetsInScope(Scope)}), so it costs one pass over the targets the
	 * matching groups hold, and translating it costs what translating the whole family would.
	 *
	 * @param scopeIndex     the {@link ReferencedTypeEntityIndex} currently in the processing scope
	 * @param groupRowLookup the matching groups' reduced group indexes
	 * @return formula yielding the candidate reduced-index PKs
	 */
	@Nonnull
	private static Formula groupNarrowedReducedIndexPksFormula(
		@Nonnull ReferencedTypeEntityIndex scopeIndex,
		@Nonnull GroupRowLookup groupRowLookup
	) {
		if (scopeIndex.getAllReferencedPrimaryKeys().isEmpty()) {
			return EmptyFormula.INSTANCE;
		}
		final PersistentRoaringBitmap targets = groupRowLookup.getTargetsInScope(scopeIndex.getIndexKey().scope());
		return targets.isEmpty() ? EmptyFormula.INSTANCE : reducedIndexPksFormula(scopeIndex, targets);
	}

	/**
	 * Returns a constant formula carrying *all* reduced-index primary keys tracked by the supplied
	 * REFERENCED_ENTITY_TYPE index. Used by {@link GroupHaving} during the reference index-discovery
	 * phase when the returned set is the answer itself (nothing re-examines the rows afterwards): the
	 * type-level index in scope is keyed by referenced entity PKs, so it cannot narrow reduced-index PKs
	 * by group entity PKs. By returning the full set we keep AND-composition with sibling constraints
	 * (EntityHaving, attribute filters) intact.
	 *
	 * @param scopeIndex the {@link ReferencedTypeEntityIndex} currently in the processing scope
	 * @return formula yielding the full bitmap of reduced-index PKs from the index
	 */
	@Nonnull
	private static Formula allReducedIndexPksFormula(@Nonnull ReferencedTypeEntityIndex scopeIndex) {
		final Bitmap allReferenced = scopeIndex.getAllReferencedPrimaryKeys();
		if (allReferenced.isEmpty()) {
			return EmptyFormula.INSTANCE;
		}
		return reducedIndexPksFormula(scopeIndex, RoaringBitmapBackedBitmap.getRoaringBitmap(allReferenced));
	}

	/**
	 * Translates referenced entity PKs into the primary keys of the reduced entity indexes holding their rows.
	 *
	 * @param scopeIndex the {@link ReferencedTypeEntityIndex} currently in the processing scope
	 * @param targets    referenced entity primary keys to translate
	 * @return constant formula over the reduced-index PKs, or {@link EmptyFormula#INSTANCE} when there are none
	 */
	@Nonnull
	private static Formula reducedIndexPksFormula(
		@Nonnull ReferencedTypeEntityIndex scopeIndex,
		@Nonnull PersistentRoaringBitmap targets
	) {
		final Bitmap indexPks = scopeIndex.getIndexPrimaryKeys(targets);
		return indexPks.isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(indexPks);
	}

	/**
	 * Key of one {@link GroupRowLookup} in the planning context's value-keyed memo. It identifies everything the
	 * lookup is derived from, so a key rebuilt by a later call hits:
	 *
	 * - the entity type and the reference whose reduced group indexes the lookup holds,
	 * - the processing scopes the group indexes are resolved in,
	 * - the matching groups, by the instance of the nested query's formula - compared by identity, because
	 *   {@link FilterByVisitor#computeOnlyOnce} hands out one instance per group global index and value-equal nested
	 *   filter. The same instance therefore means the same groups, while a nested filter changed by a
	 *   {@link NestedQueryRestriction} is another instance and another lookup.
	 *
	 * @param entityType     type of the entity owning the reference - the group indexes belong to it
	 * @param referenceName  name of the reference carrying the group
	 * @param scopes         processing scopes the group indexes are resolved in
	 * @param matchingGroups the nested group query planned over one group global index, compared by identity
	 */
	record GroupRowLookupKey(
		@Nonnull String entityType,
		@Nonnull String referenceName,
		@Nonnull Set<Scope> scopes,
		@Nonnull Formula matchingGroups
	) {

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof GroupRowLookupKey that)) return false;
			return this.matchingGroups == that.matchingGroups &&
				this.entityType.equals(that.entityType) &&
				this.referenceName.equals(that.referenceName) &&
				this.scopes.equals(that.scopes);
		}

		@Override
		public int hashCode() {
			return 31 * Objects.hash(this.entityType, this.referenceName, this.scopes) +
				System.identityHashCode(this.matchingGroups);
		}
	}

	/**
	 * Key of one bucket of reduced group indexes: the scope of the owners and the representative attribute values of
	 * the rows. A reduced entity index with the same scope and representative values holds the very rows the group
	 * indexes of the bucket describe. The values are held as a list so that the key compares them element-wise.
	 *
	 * @param scope                scope of the reduced group indexes in the bucket
	 * @param representativeValues representative attribute values of the rows, compared element-wise
	 */
	private record GroupBucketKey(
		@Nonnull Scope scope,
		@Nonnull List<Serializable> representativeValues
	) {

		/**
		 * Creates the key of the bucket describing the rows held by the passed reduced index.
		 *
		 * @param index a reduced entity or group index
		 * @return key of the bucket its rows belong to
		 */
		@Nonnull
		static GroupBucketKey of(@Nonnull AbstractReducedEntityIndex index) {
			return new GroupBucketKey(
				index.getIndexKey().scope(),
				Arrays.asList(index.getRepresentativeReferenceKey().representativeAttributeValues())
			);
		}
	}

	/**
	 * The reduced group indexes of the groups a `groupHaving` matched, bucketed so that one reduced entity index can
	 * find the group indexes describing its own rows with a single hash lookup.
	 *
	 * A reference row is the tuple `(owner, target, representativeValues, group)`, and a reduced entity index is keyed
	 * by `(referenceName, target, representativeValues)` - so one index holds at most one row per owner. The reduced
	 * **group** index keyed by the same representative values is therefore the one holding that very row, and asking
	 * it which owners reference this index's target answers "whose row *here* carries one of the matching groups"
	 * rather than "who references a matching group at all". That distinction is the whole point: the second question
	 * is cross-row and combines wrongly with sibling constraints and with `not`.
	 *
	 * Built once per plan, it replaces a per-index formula that re-resolved every matching group's indexes and
	 * scanned them at execution time: the cost per reduced entity index is now one lookup per group index in its
	 * bucket - one for the single group a `histogramHaving` selects.
	 */
	static final class GroupRowLookup {
		/**
		 * Lookup of a `groupHaving` whose group collection has no global index in the scope - it matches no group.
		 */
		static final GroupRowLookup EMPTY = new GroupRowLookup(Map.of(), Map.of());
		/**
		 * Reduced group indexes of the matching groups, keyed by the rows they describe.
		 */
		private final Map<GroupBucketKey, List<ReducedGroupEntityIndex>> buckets;
		/**
		 * The same reduced group indexes, keyed by their scope only.
		 */
		private final Map<Scope, List<ReducedGroupEntityIndex>> byScope;
		/**
		 * Union of the referenced entities the group indexes of one scope hold rows for, built on the first request.
		 */
		private final Map<Scope, PersistentRoaringBitmap> targetsByScope = new EnumMap<>(Scope.class);

		/**
		 * Resolves the reduced group indexes of every matching group in the processing scopes and buckets them.
		 *
		 * @param filterByVisitor visitor used to resolve the group indexes
		 * @param entitySchema    schema of the entity owning the reference
		 * @param referenceSchema schema of the reference carrying the group
		 * @param matchingGroups  primary keys of the groups the nested query matched
		 * @return the lookup
		 */
		@Nonnull
		static GroupRowLookup build(
			@Nonnull FilterByVisitor filterByVisitor,
			@Nonnull EntitySchemaContract entitySchema,
			@Nonnull ReferenceSchemaContract referenceSchema,
			@Nonnull Bitmap matchingGroups
		) {
			if (matchingGroups.isEmpty()) {
				return EMPTY;
			}
			final Map<GroupBucketKey, List<ReducedGroupEntityIndex>> buckets = new HashMap<>(16);
			final Map<Scope, List<ReducedGroupEntityIndex>> byScope = new EnumMap<>(Scope.class);
			final OfInt it = matchingGroups.iterator();
			while (it.hasNext()) {
				filterByVisitor
					.getReferencedGroupEntityIndexes(entitySchema, referenceSchema, it.nextInt())
					.filter(ReducedGroupEntityIndex.class::isInstance)
					.map(ReducedGroupEntityIndex.class::cast)
					.forEach(groupIndex -> {
						buckets.computeIfAbsent(GroupBucketKey.of(groupIndex), k -> new ArrayList<>(2))
							.add(groupIndex);
						byScope.computeIfAbsent(groupIndex.getIndexKey().scope(), k -> new ArrayList<>(16))
							.add(groupIndex);
					});
			}
			return new GroupRowLookup(buckets, byScope);
		}

		/**
		 * Creates the lookup over already bucketed group indexes; use {@link #build} or {@link #EMPTY}.
		 *
		 * @param buckets group indexes keyed by the rows they describe
		 * @param byScope the same group indexes keyed by the scope of their owners
		 */
		private GroupRowLookup(
			@Nonnull Map<GroupBucketKey, List<ReducedGroupEntityIndex>> buckets,
			@Nonnull Map<Scope, List<ReducedGroupEntityIndex>> byScope
		) {
			this.buckets = buckets;
			this.byScope = byScope;
		}

		/**
		 * Returns the referenced entities the matching groups hold rows for, among owners living in the passed scope.
		 * The union is built on the first request and kept for the rest of the plan; a scope without group indexes
		 * gets a fresh empty bitmap and nothing is stored, so {@link #EMPTY} is never modified.
		 *
		 * @param scope the scope of the owners
		 * @return referenced entity primary keys, possibly empty
		 */
		@Nonnull
		PersistentRoaringBitmap getTargetsInScope(@Nonnull Scope scope) {
			final List<ReducedGroupEntityIndex> groupIndexes = this.byScope.get(scope);
			if (groupIndexes == null) {
				return RoaringBitmapBackedBitmap.buildWriter().get();
			}
			return this.targetsByScope.computeIfAbsent(
				scope,
				s -> {
					final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
					for (final ReducedGroupEntityIndex groupIndex : groupIndexes) {
						for (final Integer target : groupIndex.getReferencedEntityPrimaryKeys()) {
							writer.add(target);
						}
					}
					return writer.get();
				}
			);
		}

		/**
		 * Builds the row-exact owner formula contributed by one reduced entity index for a `groupHaving` body: the
		 * owners whose row held by this index belongs to one of the matching groups.
		 *
		 * @param targetIndex the reduced entity index whose rows are being evaluated
		 * @return the tagged owner formula, or {@link EmptyFormula#INSTANCE} when no row of the index is in a matching
		 * group
		 */
		@Nonnull
		Formula createIndexLocalGroupFormula(@Nonnull ReducedEntityIndex targetIndex) {
			final List<ReducedGroupEntityIndex> bucket = this.buckets.get(GroupBucketKey.of(targetIndex));
			if (bucket == null) {
				return EmptyFormula.INSTANCE;
			}
			final int targetPrimaryKey = targetIndex.getReferenceKey().primaryKey();
			final List<Formula> owners = new ArrayList<>(bucket.size());
			for (final ReducedGroupEntityIndex groupIndex : bucket) {
				final Bitmap groupOwners = groupIndex.getOwnerPKsForReferencedEntity(targetPrimaryKey);
				if (groupOwners != null && !groupOwners.isEmpty()) {
					owners.add(new ConstantFormula(groupOwners));
				}
			}
			return switch (owners.size()) {
				case 0 -> EmptyFormula.INSTANCE;
				case 1 -> new IndexTaggedFormula(targetIndex.getPrimaryKey(), owners.get(0));
				default -> new IndexTaggedFormula(
					targetIndex.getPrimaryKey(), FormulaFactory.or(owners.toArray(Formula[]::new))
				);
			};
		}
	}

	private HavingTranslatorHelper() {
		// utility class
	}

}
