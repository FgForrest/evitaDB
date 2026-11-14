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

package io.evitadb.index.hierarchy.predicate;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.identity.ConstructionMode;
import io.evitadb.test.identity.IdentityFixture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.FormulaIdentityFixtureSupport.*;

/**
 * Real construction inputs and operations for {@link FilteringFormulaHierarchyEntityPredicate}.
 *
 * Planning modes use primary-key filters. Their constants above {@link IdentityToken#CONTENT_LIMIT} are opaque,
 * but the superset intersection contains them as formula children: the predicate stays non-opaque and becomes
 * query-local. These are ordinary filter variants, with locality checked separately. Primary-key translation
 * cannot produce exact oversized token lists, so planning modes have no oversized variants. Any-child planning
 * always uses a custom computation and is opaque, so it has neither a value base nor exact oversized variants.
 * Only the precomputed constructor accepts arbitrary opaque and exact oversized formulas directly.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class FilteringFormulaHierarchyEntityPredicateIdentityFixture
	implements IdentityFixture<FilteringFormulaHierarchyEntityPredicate>, EvitaTestSupport {
	/** Covers each constructor with both self-hierarchy and referenced-hierarchy planning. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<FilteringFormulaHierarchyEntityPredicate>> modes() {
		final Formula opaque = opaqueFormula();
		final Formula oversized = oversizedFormula();
		final GlobalEntityIndex index = new GlobalEntityIndex(
			1, "category", new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE));
		index.insertPrimaryKeyIfMissing(1);
		index.insertPrimaryKeyIfMissing(2);
		return Map.of(
			"precomputed formula", new ConstructionMode<>(
				() -> new FilteringFormulaHierarchyEntityPredicate("category", Set.of(Scope.LIVE), leaf(1)),
				Map.of("formula", () -> new FilteringFormulaHierarchyEntityPredicate(
					"category", Set.of(Scope.LIVE), leaf(2))),
				Map.of("opaque formula", () -> new FilteringFormulaHierarchyEntityPredicate(
					"category", Set.of(Scope.LIVE), opaque)),
				Map.of("oversized formula", () -> new FilteringFormulaHierarchyEntityPredicate(
					"category", Set.of(Scope.LIVE), oversized)),
				Map.of(), Set.of(), Set.of(), Set.of()),
			"node filter", plannedMode(index, false, false),
			"hierarchy requirements", plannedMode(index, true, false),
			"referenced node filter", plannedMode(index, false, true),
			"referenced hierarchy requirements", plannedMode(index, true, true),
			"any child", anyChildMode(index, false, false),
			"node and any child", anyChildMode(index, true, false),
			"referenced any child", anyChildMode(index, false, true),
			"referenced node and any child", anyChildMode(index, true, true)
		);
	}

	/** Builds filter-only variants through the declared planning constructor. */
	@Nonnull
	private ConstructionMode<FilteringFormulaHierarchyEntityPredicate> plannedMode(
		@Nonnull GlobalEntityIndex index, boolean requirements, boolean referenced
	) {
		final int[] primaryKeys = new int[IdentityToken.CONTENT_LIMIT + 1];
		for (int i = 0; i < primaryKeys.length; i++) {
			primaryKeys[i] = i + 1;
		}
		final FilterBy largeFilter = filterBy(entityPrimaryKeyInSet(primaryKeys));
		return new ConstructionMode<>(
			() -> planned(index, requirements, referenced, filterBy(entityPrimaryKeyInSet(1)), null),
			Map.of(
				"filter", () -> planned(index, requirements, referenced, filterBy(entityPrimaryKeyInSet(2)), null),
				"large primary key filter", () -> planned(index, requirements, referenced, largeFilter, null)
			),
			Map.of(), Map.of(), Map.of(), Set.of(), Set.of(), Set.of()
		);
	}

	/** Builds fresh opaque holders through the any-child planning path. */
	@Nonnull
	private ConstructionMode<FilteringFormulaHierarchyEntityPredicate> anyChildMode(
		@Nonnull GlobalEntityIndex index, boolean nodeFilter, boolean referenced
	) {
		return new ConstructionMode<>(null, Map.of(),
			Map.of("child filter", () -> planned(index, false, referenced,
				nodeFilter ? filterBy(entityPrimaryKeyInSet(1)) : null, filterBy(entityPrimaryKeyInSet(2)))),
			Map.of(), Map.of(), Set.of(), Set.of(), Set.of());
	}

	/** Builds each planning-constructor sample while a real catalog and both collections are open. */
	@Nonnull
	private FilteringFormulaHierarchyEntityPredicate planned(
		@Nonnull GlobalEntityIndex index, boolean requirements, boolean referenced,
		@Nullable FilterBy filter, @Nullable FilterBy childFilter
	) {
		final TestPaths paths = createTestPaths("HierarchyPredicateIdentity");
		try (final Evita engine = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
			engine.defineCatalog("identity");
			engine.updateCatalog("identity", session -> {
				session.defineEntitySchema("category").withHierarchy().updateVia(session);
				session.defineEntitySchema("product")
					.withReferenceToEntity("categories", "category", Cardinality.ZERO_OR_MORE)
					.updateVia(session);
			});
			final Catalog catalog = (Catalog) engine.getCatalogInstanceOrThrowException("identity");
			catalog.getCollectionForEntityOrThrowException("category").addIndex(index);
			final String entityType = referenced ? "product" : "category";
			final QueryPlanningContext context = new QueryPlanningContext(
				catalog, catalog.getCollectionForEntityOrThrowException(entityType), null,
				new EvitaRequest(query(collection(entityType)), OffsetDateTime.parse("2026-01-01T00:00:00Z"),
					EntityReference.class, null),
				referenced ? Map.of() : Map.of(index.getIndexKey(), index),
				referenced ? Map.of() : Map.of(1, index), NoCacheSupervisor.INSTANCE);
			final ReferenceSchemaContract referenceSchema = referenced ?
				context.getSchema().getReferenceOrThrowException("categories") : null;
			return requirements ?
				new FilteringFormulaHierarchyEntityPredicate(context, index, Objects.requireNonNull(filter),
					context.getSchema("category"), referenceSchema) :
				new FilteringFormulaHierarchyEntityPredicate(null, context, Set.of(Scope.LIVE), filter,
					childFilter, referenceSchema);
		} finally {
			cleanupTestPaths(paths);
		}
	}
}
