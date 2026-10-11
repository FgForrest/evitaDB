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
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.VirtualRowConsumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Decides with which read switch a query line of the differential twin harness runs. Every query line carries the query
 * routes it exercises; a line runs with the read switch of every collection of the engine on only when every one of its
 * routes has **landed** - when the route reads virtual partitions - and with the switch off otherwise, on the same
 * engine and the same snapshot. Adding a route to {@link #LANDED_ROUTES} is how a
 * step makes the harness compare the virtual reading of its routes with the reference engine's object reading.
 *
 * The switch is turned on just for the query and off again after it, so the writes of the harness never run with it -
 * write isolation is proven separately, by the write isolation test of the virtual partitions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class ReadRouteActivation {
	/**
	 * The query routes whose virtual reading has landed - every route of the read-route inventory below: the reference
	 * bodies, the bidirectional rewrite, the hierarchy routes, the `pickFirst` orderings, the block-by-block orderings
	 * and the chains all read virtual partitions, and the facet, histogram and entity body lines read nothing a
	 * virtual partition answers differently, so they run with the switch on too.
	 */
	static final Set<ReadRoute> LANDED_ROUTES = Collections.unmodifiableSet(EnumSet.allOf(ReadRoute.class));
	/**
	 * The activation of an engine that maintains nothing, and so can never read virtually.
	 */
	static final ReadRouteActivation OFF = new ReadRouteActivation(null, Set.of(), Set.of(), null, null);

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
	 * Receives what the consuming stages of each named line answered virtually and the routes the line declared,
	 * per line name; `null` when nobody counts them.
	 */
	@Nullable private final Map<String, LineConsumption> consumptionPerLine;

	/**
	 * The query routes of the virtual reading, numbered as the read-route inventory below numbers them. Only the
	 * routes a query line of the harness or a read parity test exercises are listed.
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
		PICK_FIRST_CLAIM(VirtualRowConsumer.PICK_FIRST_CLAIM_WALK),
		/**
		 * Route 14 - the sorted value of a partition in a `pickFirst` ordering by a reference attribute or compound.
		 */
		PICK_FIRST_VALUE(VirtualRowConsumer.PICK_FIRST_VIRTUAL_RUN),
		/**
		 * Route 15 - a `pickFirst` ordering by the referenced primary key, which claims and orders the virtual
		 * partitions by the referenced primary key read out of their rows.
		 */
		PICK_FIRST_REFERENCED_PRIMARY_KEY(
			VirtualRowConsumer.PICK_FIRST_CLAIM_WALK, VirtualRowConsumer.PICK_FIRST_VIRTUAL_RUN
		),
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
		 * Route 17 narrowed by a hierarchy constraint - the planning set of a block-by-block reference ordering taken
		 * from the hierarchy option of index selection: the partitions of the nodes, the virtual ones as handles.
		 */
		HIERARCHY_TRAVERSE_PLANNING_SET(VirtualRowConsumer.HIERARCHY_OPTION_PARTITIONS),
		/**
		 * Route 18 - the sequential sorter walking the blocks, the owners of a virtual partition a singleton.
		 */
		SEQUENTIAL_BLOCKS,
		/**
		 * Route 19 - the attribute, compound and chain providers of a block.
		 */
		BLOCK_PROVIDERS(VirtualRowConsumer.BLOCK_ATTRIBUTE_PROVIDER),
		/**
		 * Route 20 - `entityPrimaryKeyNatural` in a block-by-block reference ordering.
		 */
		BLOCK_REFERENCED_PRIMARY_KEY(VirtualRowConsumer.BLOCK_REFERENCED_KEY_PROVIDER),
		/**
		 * Route 21 - `attributeSetExact` / `attributeSetInFilter` in a reference block of one partition.
		 */
		BLOCK_EXACT_ATTRIBUTE_SET(VirtualRowConsumer.BLOCK_EXACT_SET_LOOKUP),
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
		ENTITY_BODY;

		/**
		 * The consuming stages that count the virtual rows the route answers on their own - a line declaring the
		 * route must see each of them answer some (see {@link LineConsumption#getIdleConsumers()}). Empty for a
		 * route whose virtual reading only the aggregate count of virtual rows witnesses.
		 */
		@Nonnull private final Set<VirtualRowConsumer> consumers;

		/**
		 * Creates a route counted by the given consuming stages.
		 *
		 * @param consumers the consuming stages of the route, none for a route only the aggregate count witnesses
		 */
		ReadRoute(@Nonnull VirtualRowConsumer... consumers) {
			final EnumSet<VirtualRowConsumer> theConsumers = EnumSet.noneOf(VirtualRowConsumer.class);
			Collections.addAll(theConsumers, consumers);
			this.consumers = Collections.unmodifiableSet(theConsumers);
		}

		/**
		 * Returns the consuming stages that count the virtual rows the route answers on their own.
		 *
		 * @return the consuming stages, possibly empty
		 */
		@Nonnull
		Set<VirtualRowConsumer> getConsumers() {
			return this.consumers;
		}
	}

	/**
	 * What the consuming stages of one named line answered virtually, summed over every run of the line, and the
	 * routes the line declared over those runs. A line declaring a route counted by a consuming stage must see that
	 * stage answer virtual rows - the aggregate count of the line alone cannot tell a route whose gather reads
	 * partitions virtually but whose consumer resolves them to their objects again from one that reads them virtually
	 * throughout.
	 */
	static final class LineConsumption {
		/**
		 * The routes the line declared, over every run.
		 */
		@Nonnull private final EnumSet<ReadRoute> routes = EnumSet.noneOf(ReadRoute.class);
		/**
		 * The virtual rows each consuming stage answered, indexed by {@link VirtualRowConsumer#ordinal()}.
		 */
		@Nonnull private final long[] rowsConsumed = new long[VirtualRowConsumer.values().length];

		/**
		 * Returns the routes the line declared.
		 *
		 * @return the routes
		 */
		@Nonnull
		Set<ReadRoute> getRoutes() {
			return Collections.unmodifiableSet(this.routes);
		}

		/**
		 * Returns how many virtual rows the consuming stage answered in the line.
		 *
		 * @param consumer the consuming stage
		 * @return the virtual rows it answered
		 */
		long getRowsConsumed(@Nonnull VirtualRowConsumer consumer) {
			return this.rowsConsumed[consumer.ordinal()];
		}

		/**
		 * Returns the consuming stages the declared routes of the line are counted by.
		 *
		 * @return the consuming stages, possibly empty
		 */
		@Nonnull
		Set<VirtualRowConsumer> getRequiredConsumers() {
			final EnumSet<VirtualRowConsumer> result = EnumSet.noneOf(VirtualRowConsumer.class);
			for (final ReadRoute route : this.routes) {
				result.addAll(route.getConsumers());
			}
			return result;
		}

		/**
		 * Returns the consuming stages a declared route of the line is counted by that answered no virtual row.
		 *
		 * @return the idle consuming stages, empty when every one answered some
		 */
		@Nonnull
		List<VirtualRowConsumer> getIdleConsumers() {
			final List<VirtualRowConsumer> result = new ArrayList<>(VirtualRowConsumer.values().length);
			for (final VirtualRowConsumer consumer : getRequiredConsumers()) {
				if (this.rowsConsumed[consumer.ordinal()] <= 0L) {
					result.add(consumer);
				}
			}
			return result;
		}

		@Override
		public String toString() {
			final StringBuilder result = new StringBuilder(64 * VirtualRowConsumer.values().length);
			result.append(this.routes).append(" consumed {");
			for (final VirtualRowConsumer consumer : VirtualRowConsumer.values()) {
				if (this.rowsConsumed[consumer.ordinal()] > 0L) {
					result.append(consumer).append('=').append(this.rowsConsumed[consumer.ordinal()]).append(' ');
				}
			}
			return result.append('}').toString();
		}
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
		this(maintenance, entityTypes, landedRoutes, null, null);
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
	 * @param consumptionPerLine receives what the consuming stages of each named line answer virtually and the routes
	 *                           the line declares, per line name; `null` to count nothing
	 */
	ReadRouteActivation(
		@Nullable VirtualReferencePartitionMaintenance maintenance,
		@Nonnull Set<String> entityTypes,
		@Nonnull Set<ReadRoute> landedRoutes,
		@Nullable Map<String, Long> virtualRowsPerLine,
		@Nullable Map<String, LineConsumption> consumptionPerLine
	) {
		this.maintenance = maintenance;
		this.entityTypes = Set.copyOf(entityTypes);
		this.landedRoutes = Set.copyOf(landedRoutes);
		this.virtualRowsPerLine = virtualRowsPerLine;
		this.consumptionPerLine = consumptionPerLine;
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
	 * the line returns. A named line adds the virtual rows it read to the count of its name, and the virtual rows each
	 * consuming stage answered and the routes it declares to the consumption of its name.
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
		final long[] consumedBefore = consumedRows(this.maintenance);
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
			if (line != null && this.consumptionPerLine != null) {
				final LineConsumption consumption =
					this.consumptionPerLine.computeIfAbsent(line, __ -> new LineConsumption());
				consumption.routes.addAll(routes);
				final long[] consumedAfter = consumedRows(this.maintenance);
				for (int i = 0; i < consumedAfter.length; i++) {
					consumption.rowsConsumed[i] += consumedAfter[i] - consumedBefore[i];
				}
			}
		}
	}

	/**
	 * Returns how many virtual rows each consuming stage has answered in the holder so far.
	 *
	 * @param maintenance the switch holder
	 * @return the counts, indexed by {@link VirtualRowConsumer#ordinal()}
	 */
	@Nonnull
	private static long[] consumedRows(@Nonnull VirtualReferencePartitionMaintenance maintenance) {
		final VirtualRowConsumer[] consumers = VirtualRowConsumer.values();
		final long[] result = new long[consumers.length];
		for (final VirtualRowConsumer consumer : consumers) {
			result[consumer.ordinal()] = maintenance.getVirtualRowsConsumed(consumer);
		}
		return result;
	}

}
