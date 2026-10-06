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

// TODO JNO - remove at the end of #1615 (the whole file)
package io.evitadb.index.virtual;

import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Decides with which read switch a query line of {@link DifferentialEngines} runs. Every query line carries the query
 * routes it exercises; a line runs with the read switch of every collection of the engine on only when every one of its
 * routes has **landed** - when the step of #1615 that teaches the route to read virtual partitions is in - and with the
 * switch off otherwise, on the same engine and the same snapshot. Adding a route to {@link #LANDED_ROUTES} is how a
 * step makes the harness compare the virtual reading of its routes with the reference engine's object reading.
 *
 * The switch is turned on just for the query and off again after it, so the writes of the harness never run with it -
 * write isolation is proven separately, by {@link VirtualPartitionWriteIsolationTest}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class ReadRouteActivation {
	/**
	 * The query routes whose virtual reading has landed - those of steps 4b, 4c and 4d of #1615: every body of
	 * `referenceHaving` and of `referenceContent(filterBy)` - the row-exact ones answered from the candidate set, the
	 * negations, `attributeIs`, `groupHaving`, `entityLocaleEquals` and `inScope` answered exactly - its target index
	 * list, `entityHaving` / `entityPrimaryKeyInSet` in a body, the bidirectional rewrite, the translation of a
	 * partition to its target, and the hierarchy routes - `hierarchyWithin` / `hierarchyWithinRoot` of a reference and
	 * the statistics of `hierarchyOfReference`.
	 */
	static final Set<ReadRoute> LANDED_ROUTES = EnumSet.of(
		ReadRoute.REFERENCE_HAVING_BODY,
		ReadRoute.REFERENCE_HAVING_TARGET_INDEXES,
		ReadRoute.NEGATION_IN_BODY,
		ReadRoute.GROUP_IN_BODY,
		ReadRoute.LOCALE_IN_BODY,
		ReadRoute.REFERENCED_ENTITY_IN_BODY,
		ReadRoute.BIDIRECTIONAL_REWRITE,
		ReadRoute.PARTITION_TO_TARGET,
		ReadRoute.REFERENCE_CONTENT_FILTER,
		ReadRoute.HIERARCHY_WITHIN,
		ReadRoute.HIERARCHY_STATISTICS
	);
	/**
	 * The activation of an engine that maintains nothing, and so can never read virtually.
	 */
	static final ReadRouteActivation OFF = new ReadRouteActivation(null, Set.of(), Set.of());

	/**
	 * The switch holder of the engine's catalog, `null` for an engine that maintains nothing.
	 */
	@Nullable private final VirtualReferencePartitionMaintenance maintenance;
	/**
	 * The entity types the engine maintains - and reads virtually on a landed route.
	 */
	@Nonnull private final Set<String> entityTypes;
	/**
	 * The routes that have landed.
	 */
	@Nonnull private final Set<ReadRoute> landedRoutes;

	/**
	 * The query routes of the virtual reading, numbered as the read-route inventory of the phase-4 plan of #1615
	 * numbers them. Only the routes a query line of the harness or a read parity test exercises are listed.
	 */
	enum ReadRoute {
		/**
		 * Route 1 - the body of `referenceHaving` evaluated over the candidate partitions.
		 */
		REFERENCE_HAVING_BODY,
		/**
		 * Route 2 - the target index list a `referenceHaving` plans over.
		 */
		REFERENCE_HAVING_TARGET_INDEXES,
		/**
		 * Route 3 - a negation or `attributeIs` inside a reference body.
		 */
		NEGATION_IN_BODY,
		/**
		 * Route 4 - `groupHaving` inside a reference body.
		 */
		GROUP_IN_BODY,
		/**
		 * Route 5 - `entityLocaleEquals` inside a reference body.
		 */
		LOCALE_IN_BODY,
		/**
		 * Route 6 - `entityHaving` / `entityPrimaryKeyInSet` inside a `referenceHaving` body.
		 */
		REFERENCED_ENTITY_IN_BODY,
		/**
		 * Route 7 - a `referenceHaving` answered from the counterpart of a bidirectional reference pair.
		 */
		BIDIRECTIONAL_REWRITE,
		/**
		 * Route 8 - the translation of a partition primary key to the primary key of the entity it references.
		 */
		PARTITION_TO_TARGET,
		/**
		 * Route 9 - the rows `referenceContent(filterBy)` keeps, aggregated per representative reference key across
		 * both representations and every examined scope.
		 */
		REFERENCE_CONTENT_FILTER,
		/**
		 * Route 10 - `hierarchyWithin` / `hierarchyWithinRoot` of a reference: the owners of the partitions of the
		 * hierarchy nodes, exercised by `VirtualHierarchyParityTest`.
		 */
		HIERARCHY_WITHIN,
		/**
		 * Route 11 - the statistics of `hierarchyOfReference`: the owners of the partitions of each hierarchy node,
		 * intersected with the statistics base, exercised by `VirtualHierarchyParityTest`.
		 */
		HIERARCHY_STATISTICS,
		/**
		 * Route 24 - facet summaries and histograms, which read the target index or the type index.
		 */
		FACETS_AND_HISTOGRAMS,
		/**
		 * Route 25 - entity bodies and plain `referenceContent`, read from storage parts.
		 */
		ENTITY_BODY
	}

	/**
	 * Creates the activation of an engine.
	 *
	 * @param maintenance  the switch holder of the engine's catalog, `null` for an engine that maintains nothing
	 * @param entityTypes  the entity types the engine maintains
	 * @param landedRoutes the routes that have landed
	 */
	ReadRouteActivation(
		@Nullable VirtualReferencePartitionMaintenance maintenance,
		@Nonnull Set<String> entityTypes,
		@Nonnull Set<ReadRoute> landedRoutes
	) {
		this.maintenance = maintenance;
		this.entityTypes = Set.copyOf(entityTypes);
		this.landedRoutes = Set.copyOf(landedRoutes);
	}

	/**
	 * Runs one query line: with the read switch of every maintained collection on when every route of the line has
	 * landed and the engine maintains its collections, with the switch off otherwise. The switch is off again when the
	 * line returns.
	 *
	 * @param routes the routes the line exercises
	 * @param query  the query of the line
	 * @param <T>    the type of the query result
	 * @return the query result
	 */
	<T> T run(@Nonnull Set<ReadRoute> routes, @Nonnull Supplier<T> query) {
		if (this.maintenance == null || !this.landedRoutes.containsAll(routes)) {
			return query.get();
		}
		for (final String entityType : this.entityTypes) {
			this.maintenance.setReadVirtually(entityType, true);
		}
		try {
			return query.get();
		} finally {
			for (final String entityType : this.entityTypes) {
				this.maintenance.setReadVirtually(entityType, false);
			}
		}
	}

}
