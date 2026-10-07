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
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
	 * The query routes whose virtual reading has landed - every route of the read-route inventory of #1615 since step
	 * 4h: steps 4b to 4g taught the reference bodies, the bidirectional rewrite, the hierarchy routes, the `pickFirst`
	 * orderings, the block-by-block orderings and the chains to read virtual partitions, and the facet, histogram and
	 * entity body lines read nothing a virtual partition answers differently, so they run with the switch on too.
	 */
	static final Set<ReadRoute> LANDED_ROUTES = Collections.unmodifiableSet(EnumSet.allOf(ReadRoute.class));
	/**
	 * The activation of an engine that maintains nothing, and so can never read virtually.
	 */
	static final ReadRouteActivation OFF = new ReadRouteActivation(null, Set.of(), Set.of(), null);

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
	 * Receives the virtual rows each named line read, summed per line name; `null` when nobody counts them.
	 */
	@Nullable private final Map<String, Long> virtualRowsPerLine;

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
		 * Route 12 - the gather of the partitions a `pickFirst` ordering walks and their target order, exercised by
		 * `VirtualPickFirstParityTest`.
		 */
		PICK_FIRST_GATHER,
		/**
		 * Route 13 - the claims of a `pickFirst` ordering and the merge of their sorted runs.
		 */
		PICK_FIRST_CLAIM,
		/**
		 * Route 14 - the sorted value of a partition in a `pickFirst` ordering by a reference attribute or compound.
		 */
		PICK_FIRST_VALUE,
		/**
		 * Route 15 - a `pickFirst` ordering by the referenced primary key.
		 */
		PICK_FIRST_REFERENCED_PRIMARY_KEY,
		/**
		 * Route 16 - the target rank the prefetch route of a `pickFirst` ordering picks the row by.
		 */
		PICK_FIRST_TARGET_RANK,
		/**
		 * Route 17 - the planning set of a block-by-block reference ordering: the partitions in target order, split
		 * into the blocks of one target each.
		 */
		TRAVERSE_PLANNING_SET,
		/**
		 * Route 18 - the sequential sorter walking the blocks, the owners of a virtual partition a singleton.
		 */
		SEQUENTIAL_BLOCKS,
		/**
		 * Route 19 - the attribute, compound and chain providers of a block.
		 */
		BLOCK_PROVIDERS,
		/**
		 * Route 20 - `entityPrimaryKeyNatural` in a block-by-block reference ordering.
		 */
		BLOCK_REFERENCED_PRIMARY_KEY,
		/**
		 * Route 21 - `attributeSetExact` / `attributeSetInFilter` in a reference block of one partition.
		 */
		BLOCK_EXACT_ATTRIBUTE_SET,
		/**
		 * Route 22 - the chains of `referenceContent(orderBy)`, original and reflected, resolved by key.
		 */
		REFERENCE_CONTENT_CHAIN,
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
	 * Creates the activation of an engine that counts no virtual rows per line.
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
		this(maintenance, entityTypes, landedRoutes, null);
	}

	/**
	 * Creates the activation of an engine.
	 *
	 * @param maintenance        the switch holder of the engine's catalog, `null` for an engine that maintains
	 *                           nothing
	 * @param entityTypes        the entity types the engine maintains
	 * @param landedRoutes       the routes that have landed
	 * @param virtualRowsPerLine receives the virtual rows each named line reads, summed per line name; `null` to count
	 *                           nothing
	 */
	ReadRouteActivation(
		@Nullable VirtualReferencePartitionMaintenance maintenance,
		@Nonnull Set<String> entityTypes,
		@Nonnull Set<ReadRoute> landedRoutes,
		@Nullable Map<String, Long> virtualRowsPerLine
	) {
		this.maintenance = maintenance;
		this.entityTypes = Set.copyOf(entityTypes);
		this.landedRoutes = Set.copyOf(landedRoutes);
		this.virtualRowsPerLine = virtualRowsPerLine;
	}

	/**
	 * Runs one unnamed query line reading every maintained collection - see
	 * {@link #run(String, Set, Set, Supplier)}.
	 *
	 * @param routes the routes the line exercises
	 * @param query  the query of the line
	 * @param <T>    the type of the query result
	 * @return the query result
	 */
	<T> T run(@Nonnull Set<ReadRoute> routes, @Nonnull Supplier<T> query) {
		return run(null, routes, this.entityTypes, query);
	}

	/**
	 * Runs one named query line reading every maintained collection - see {@link #run(String, Set, Set, Supplier)}.
	 *
	 * @param line   the name the virtual rows of the line are counted under
	 * @param routes the routes the line exercises
	 * @param query  the query of the line
	 * @param <T>    the type of the query result
	 * @return the query result
	 */
	<T> T run(@Nonnull String line, @Nonnull Set<ReadRoute> routes, @Nonnull Supplier<T> query) {
		return run(line, routes, this.entityTypes, query);
	}

	/**
	 * Runs one query line: with the read switch of the given maintained collections on when every route of the line
	 * has landed and the engine maintains its collections, with the switch off otherwise. The switch is off again when
	 * the line returns. A named line adds the virtual rows it read to the count of its name.
	 *
	 * @param line      the name the virtual rows of the line are counted under, `null` for an uncounted line
	 * @param routes    the routes the line exercises
	 * @param readTypes the entity types the line reads virtually - only those the engine maintains are switched
	 * @param query     the query of the line
	 * @param <T>       the type of the query result
	 * @return the query result
	 */
	<T> T run(
		@Nullable String line,
		@Nonnull Set<ReadRoute> routes,
		@Nonnull Set<String> readTypes,
		@Nonnull Supplier<T> query
	) {
		if (this.maintenance == null || !this.landedRoutes.containsAll(routes)) {
			return query.get();
		}
		final long rowsBefore = this.maintenance.getVirtualRowsRead();
		final Set<String> switched = new TreeSet<>(readTypes);
		switched.retainAll(this.entityTypes);
		for (final String entityType : switched) {
			this.maintenance.setReadVirtually(entityType, true);
		}
		try {
			return query.get();
		} finally {
			for (final String entityType : switched) {
				this.maintenance.setReadVirtually(entityType, false);
			}
			if (line != null && this.virtualRowsPerLine != null) {
				this.virtualRowsPerLine.merge(line, this.maintenance.getVirtualRowsRead() - rowsBefore, Long::sum);
			}
		}
	}

}
