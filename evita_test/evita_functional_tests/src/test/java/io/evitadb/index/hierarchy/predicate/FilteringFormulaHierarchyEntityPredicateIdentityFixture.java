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
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.*;
import io.evitadb.core.query.algebra.identity.*;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.test.identity.*;

import java.time.OffsetDateTime;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;

import javax.annotation.Nonnull;

import java.util.Map;
import java.util.Set;

import static io.evitadb.core.query.algebra.deferred.DeferredFormulaIdentityFixture.*;
import static io.evitadb.test.identity.CloneOutcome.*;

/**
 * Real construction inputs and operations for {@link FilteringFormulaHierarchyEntityPredicate}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class FilteringFormulaHierarchyEntityPredicateIdentityFixture
	implements IdentityFixture<FilteringFormulaHierarchyEntityPredicate>, io.evitadb.test.EvitaTestSupport {
	/** Covers the value, opaque and oversized forms admitted by construction. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<FilteringFormulaHierarchyEntityPredicate>> modes() {
		final Formula opaque = opaqueFormula();
		final Formula oversized = oversizedFormula();
		final GlobalEntityIndex index = new GlobalEntityIndex(
			1, "category", new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE));
		index.insertPrimaryKeyIfMissing(1);
		index.insertPrimaryKeyIfMissing(2);
		return Map.of("precomputed formula", new ConstructionMode<>(
			() -> new FilteringFormulaHierarchyEntityPredicate("category", Set.of(Scope.LIVE), leaf(1)),
			Map.of("formula", () -> new FilteringFormulaHierarchyEntityPredicate("category", Set.of(Scope.LIVE),
				leaf(2))),
			Map.of("opaque formula", () -> new FilteringFormulaHierarchyEntityPredicate("category",
				Set.of(Scope.LIVE), opaque)),
			Map.of("oversized formula", () -> new FilteringFormulaHierarchyEntityPredicate("category",
				Set.of(Scope.LIVE), oversized)),
			Map.of(), Set.of(), Set.of(), Set.of()),
			"node filter", new ConstructionMode<>(
				() -> this.planned(index, false, filterBy(entityPrimaryKeyInSet(1)), null),
				Map.of("filter", () -> this.planned(index, false, filterBy(entityPrimaryKeyInSet(2)), null)),
				Map.of("opaque formula", () -> new FilteringFormulaHierarchyEntityPredicate("category",
					Set.of(Scope.LIVE), opaque)),
				Map.of("oversized formula", () -> new FilteringFormulaHierarchyEntityPredicate("category",
					Set.of(Scope.LIVE), oversized)),
				Map.of(), Set.of(), Set.of(), Set.of()),
			"hierarchy requirements", new ConstructionMode<>(
				() -> this.planned(index, true, filterBy(entityPrimaryKeyInSet(1)), null),
				Map.of("filter", () -> this.planned(index, true, filterBy(entityPrimaryKeyInSet(2)), null)),
				Map.of("opaque formula", () -> new FilteringFormulaHierarchyEntityPredicate("category",
					Set.of(Scope.LIVE), opaque)),
				Map.of("oversized formula", () -> new FilteringFormulaHierarchyEntityPredicate("category",
					Set.of(Scope.LIVE), oversized)),
				Map.of(), Set.of(), Set.of(), Set.of()),
			"any child", new ConstructionMode<>(null, Map.of(),
				Map.of("child filter", () -> this.planned(index, false, null, filterBy(entityPrimaryKeyInSet(1)))),
				Map.of(), Map.of(), Set.of(), Set.of(), Set.of()),
			"node and any child", new ConstructionMode<>(null, Map.of(),
				Map.of("filters", () -> this.planned(index, false, filterBy(entityPrimaryKeyInSet(1)),
					filterBy(entityPrimaryKeyInSet(2)))),
				Map.of(), Map.of(), Set.of(), Set.of(), Set.of()));
	}

	/** Builds each planning-constructor sample while a real catalog and collection are open. */
	@Nonnull
	private FilteringFormulaHierarchyEntityPredicate planned(
		@Nonnull GlobalEntityIndex index, boolean requirements,
		@javax.annotation.Nullable io.evitadb.api.query.filter.FilterBy filter,
		@javax.annotation.Nullable io.evitadb.api.query.filter.FilterBy childFilter
	) {
		final TestPaths paths = createTestPaths("HierarchyPredicateIdentity");
		try (final Evita engine = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
			engine.defineCatalog("identity");
			engine.updateCatalog("identity", session -> {
				session.defineEntitySchema("category").withHierarchy().updateVia(session);
			});
			final Catalog catalog = (Catalog) engine.getCatalogInstanceOrThrowException("identity");
			final QueryPlanningContext context = new QueryPlanningContext(
				catalog, catalog.getCollectionForEntityOrThrowException("category"), null,
				new EvitaRequest(query(collection("category")), OffsetDateTime.parse("2026-01-01T00:00:00Z"),
					EntityReference.class, null),
				Map.of(index.getIndexKey(), index), Map.of(1, index), NoCacheSupervisor.INSTANCE);
			return requirements ?
				new FilteringFormulaHierarchyEntityPredicate(context, index, java.util.Objects.requireNonNull(filter),
					context.getSchema(), null) :
				new FilteringFormulaHierarchyEntityPredicate(null, context, Set.of(Scope.LIVE), filter,
					childFilter, null);
		} finally {
			cleanupTestPaths(paths);
		}
	}
}
