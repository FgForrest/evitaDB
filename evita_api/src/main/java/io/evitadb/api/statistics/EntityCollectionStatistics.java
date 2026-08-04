/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.api.statistics;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * A component-selected snapshot of **one** entity collection's statistics.
 *
 * **Why this is a separate call and not part of {@link CatalogStatistics}**
 *
 * Catalog-level statistics are the ones that get polled - a management screen refreshes them on a timer. Nesting a row
 * per collection inside every catalog-level component would make the size of that polled response grow with the number
 * of collections, and would force the expensive per-collection work
 * ({@link CatalogStatisticsComponent#INDEX_CARDINALITY}, {@link CatalogStatisticsComponent#MEMORY_FOOTPRINT}) into a
 * request that must stay cheap. So {@link CatalogStatistics} reports **aggregates only**, and anything about one
 * collection is fetched by naming that collection.
 *
 * **Consistency with {@link CatalogStatistics}**
 *
 * Each response is a snapshot taken when its own call was served, at its own catalog version. The catalog-level
 * aggregate therefore need **not** equal the sum of the collection-level values fetched separately - between the two
 * calls the catalog moves on. {@link #identity()} carries {@link CatalogIdentity#catalogVersion()} precisely so a
 * client that cares can tell whether two responses describe the same state.
 *
 * **Which components exist here**
 *
 * Only those for which {@link CatalogStatisticsComponent#isCollectionLevel()} holds. Requesting a catalog-only
 * component (sessions, the commit pipeline, WAL history, durability) is a programming error and is rejected, not
 * silently dropped.
 *
 * **An unknown entity type is an error, not a status**
 *
 * {@link ComponentAvailability} describes why a *component* of an existing collection could not be produced; it has no
 * value meaning "there is no such collection", and {@link #entityType()} could not be filled for one. A request naming
 * a collection the catalog does not hold therefore throws
 * {@link io.evitadb.api.exception.CollectionNotFoundException}, exactly as
 * `CatalogContract#getCollectionForEntityOrThrowException(String)` does - a client holding a stale inventory gets the
 * same answer here as everywhere else, rather than an empty response it might mistake for an empty collection.
 *
 * **Thread-safety**
 *
 * Immutable, and therefore thread-safe.
 *
 * @param identity           the catalog this collection belongs to and the version this snapshot was taken at; always
 *                           present
 * @param entityType         name of the entity collection this snapshot describes; always present
 * @param header             {@link CatalogStatisticsComponent#COLLECTIONS}, null unless requested and delivered
 * @param recordCounts       {@link CatalogStatisticsComponent#RECORD_COUNTS}, null unless requested and delivered
 * @param storageSize        {@link CatalogStatisticsComponent#STORAGE_SIZE}, null unless requested and delivered
 * @param storageComposition {@link CatalogStatisticsComponent#STORAGE_COMPOSITION}, null unless requested and
 *                           delivered
 * @param fragmentation      {@link CatalogStatisticsComponent#FRAGMENTATION}, null unless requested and delivered
 * @param indexSummary       {@link CatalogStatisticsComponent#INDEX_SUMMARY}, null unless requested and delivered
 * @param volatileState      {@link CatalogStatisticsComponent#VOLATILE_STATE}, null unless requested and delivered
 * @param componentStatus    outcome of every *requested* component; components that were not requested are absent from
 *                           the map entirely
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024-2026
 * @see CatalogStatistics
 */
public record EntityCollectionStatistics(
	@Nonnull CatalogIdentity identity,
	@Nonnull String entityType,
	@Nullable CollectionHeaderInfo header,
	@Nullable CollectionRecordCounts recordCounts,
	@Nullable CollectionStorageSize storageSize,
	@Nullable CollectionStorageComposition storageComposition,
	@Nullable CollectionFragmentation fragmentation,
	@Nullable CollectionIndexSummary indexSummary,
	@Nullable CollectionVolatileState volatileState,
	@Nonnull Map<CatalogStatisticsComponent, ComponentStatus> componentStatus
) {

	/**
	 * Starts building a snapshot for one collection of the given catalog.
	 *
	 * @param identity   the catalog the collection belongs to
	 * @param entityType name of the entity collection
	 * @return a builder seeded with `identity` and `entityType` and no components
	 */
	@Nonnull
	public static Builder builder(@Nonnull CatalogIdentity identity, @Nonnull String entityType) {
		return new Builder(identity, entityType);
	}

	/**
	 * Returns the collection header counters when they were requested and could be read.
	 *
	 * @return the {@link CatalogStatisticsComponent#COLLECTIONS} component, empty otherwise
	 */
	@Nonnull
	public Optional<CollectionHeaderInfo> headerIfPresent() {
		return Optional.ofNullable(this.header);
	}

	/**
	 * Returns the record counts when they were requested and could be computed.
	 *
	 * @return the {@link CatalogStatisticsComponent#RECORD_COUNTS} component, empty otherwise
	 */
	@Nonnull
	public Optional<CollectionRecordCounts> recordCountsIfPresent() {
		return Optional.ofNullable(this.recordCounts);
	}

	/**
	 * Returns the storage decomposition when it was requested and could be computed.
	 *
	 * @return the {@link CatalogStatisticsComponent#STORAGE_SIZE} component, empty otherwise
	 */
	@Nonnull
	public Optional<CollectionStorageSize> storageSizeIfPresent() {
		return Optional.ofNullable(this.storageSize);
	}

	/**
	 * Returns the storage-part composition when it was requested and could be computed.
	 *
	 * @return the {@link CatalogStatisticsComponent#STORAGE_COMPOSITION} component, empty otherwise
	 */
	@Nonnull
	public Optional<CollectionStorageComposition> storageCompositionIfPresent() {
		return Optional.ofNullable(this.storageComposition);
	}

	/**
	 * Returns the fragmentation statistics when they were requested and could be computed.
	 *
	 * @return the {@link CatalogStatisticsComponent#FRAGMENTATION} component, empty otherwise
	 */
	@Nonnull
	public Optional<CollectionFragmentation> fragmentationIfPresent() {
		return Optional.ofNullable(this.fragmentation);
	}

	/**
	 * Returns the index summary when it was requested and could be computed.
	 *
	 * @return the {@link CatalogStatisticsComponent#INDEX_SUMMARY} component, empty otherwise
	 */
	@Nonnull
	public Optional<CollectionIndexSummary> indexSummaryIfPresent() {
		return Optional.ofNullable(this.indexSummary);
	}

	/**
	 * Returns the volatile state statistics when they were requested and could be computed.
	 *
	 * @return the {@link CatalogStatisticsComponent#VOLATILE_STATE} component, empty otherwise
	 */
	@Nonnull
	public Optional<CollectionVolatileState> volatileStateIfPresent() {
		return Optional.ofNullable(this.volatileState);
	}

	/**
	 * Returns the outcome of one requested component.
	 *
	 * @param component the component to look up
	 * @return its status, empty when the component was never requested
	 */
	@Nonnull
	public Optional<ComponentStatus> statusOf(@Nonnull CatalogStatisticsComponent component) {
		return Optional.ofNullable(this.componentStatus.get(component));
	}

	/**
	 * Tells whether a component was requested *and* successfully computed.
	 *
	 * @param component the component to check
	 * @return true only when the component has a {@link ComponentAvailability#DELIVERED} status
	 */
	public boolean isDelivered(@Nonnull CatalogStatisticsComponent component) {
		final ComponentStatus status = this.componentStatus.get(component);
		return status != null && status.isDelivered();
	}

	/**
	 * Collects the components of an {@link EntityCollectionStatistics} snapshot as they are computed.
	 *
	 * Each `with...` method records both the value and a {@link ComponentAvailability#DELIVERED} status, so the two can
	 * never drift apart; {@link #withUnavailable} records the opposite case. The builder is not thread-safe - one
	 * request builds one snapshot.
	 */
	public static class Builder {
		/**
		 * The catalog the collection belongs to.
		 */
		private final CatalogIdentity identity;
		/**
		 * Name of the entity collection being described.
		 */
		private final String entityType;
		/**
		 * Status of every component requested so far, in enum order.
		 */
		private final Map<CatalogStatisticsComponent, ComponentStatus> componentStatus =
			new EnumMap<>(CatalogStatisticsComponent.class);
		private CollectionHeaderInfo header;
		private CollectionRecordCounts recordCounts;
		private CollectionStorageSize storageSize;
		private CollectionStorageComposition storageComposition;
		private CollectionFragmentation fragmentation;
		private CollectionIndexSummary indexSummary;
		private CollectionVolatileState volatileState;

		Builder(@Nonnull CatalogIdentity identity, @Nonnull String entityType) {
			this.identity = identity;
			this.entityType = entityType;
			// IDENTITY is delivered unconditionally - record it so a client never has to special-case its absence
			delivered(CatalogStatisticsComponent.IDENTITY);
		}

		/**
		 * Records the {@link CatalogStatisticsComponent#COLLECTIONS} component as delivered.
		 *
		 * @param value the collection header counters
		 * @return this builder
		 */
		@Nonnull
		public Builder withHeader(@Nonnull CollectionHeaderInfo value) {
			this.header = value;
			return delivered(CatalogStatisticsComponent.COLLECTIONS);
		}

		/**
		 * Records the {@link CatalogStatisticsComponent#RECORD_COUNTS} component as delivered.
		 *
		 * @param value the computed component
		 * @return this builder
		 */
		@Nonnull
		public Builder withRecordCounts(@Nonnull CollectionRecordCounts value) {
			this.recordCounts = value;
			return delivered(CatalogStatisticsComponent.RECORD_COUNTS);
		}

		/**
		 * Records the {@link CatalogStatisticsComponent#STORAGE_SIZE} component as delivered.
		 *
		 * @param value the computed component
		 * @return this builder
		 */
		@Nonnull
		public Builder withStorageSize(@Nonnull CollectionStorageSize value) {
			this.storageSize = value;
			return delivered(CatalogStatisticsComponent.STORAGE_SIZE);
		}

		/**
		 * Records the {@link CatalogStatisticsComponent#STORAGE_COMPOSITION} component as delivered.
		 *
		 * @param value the computed component
		 * @return this builder
		 */
		@Nonnull
		public Builder withStorageComposition(@Nonnull CollectionStorageComposition value) {
			this.storageComposition = value;
			return delivered(CatalogStatisticsComponent.STORAGE_COMPOSITION);
		}

		/**
		 * Records the {@link CatalogStatisticsComponent#FRAGMENTATION} component as delivered.
		 *
		 * @param value the computed component
		 * @return this builder
		 */
		@Nonnull
		public Builder withFragmentation(@Nonnull CollectionFragmentation value) {
			this.fragmentation = value;
			return delivered(CatalogStatisticsComponent.FRAGMENTATION);
		}

		/**
		 * Records the {@link CatalogStatisticsComponent#INDEX_SUMMARY} component as delivered.
		 *
		 * @param value the computed component
		 * @return this builder
		 */
		@Nonnull
		public Builder withIndexSummary(@Nonnull CollectionIndexSummary value) {
			this.indexSummary = value;
			return delivered(CatalogStatisticsComponent.INDEX_SUMMARY);
		}

		/**
		 * Records the {@link CatalogStatisticsComponent#VOLATILE_STATE} component as delivered.
		 *
		 * @param value the computed component
		 * @return this builder
		 */
		@Nonnull
		public Builder withVolatileState(@Nonnull CollectionVolatileState value) {
			this.volatileState = value;
			return delivered(CatalogStatisticsComponent.VOLATILE_STATE);
		}

		/**
		 * Records a component the caller requested but the engine could not compute.
		 *
		 * @param component    the component that could not be delivered
		 * @param availability the class of reason
		 * @param reason       human-readable explanation shown to the operator
		 * @return this builder
		 */
		@Nonnull
		public Builder withUnavailable(
			@Nonnull CatalogStatisticsComponent component,
			@Nonnull ComponentAvailability availability,
			@Nonnull String reason
		) {
			this.componentStatus.put(component, ComponentStatus.unavailable(component, availability, reason));
			return this;
		}

		/**
		 * Builds the immutable snapshot.
		 *
		 * @return the snapshot carrying every component recorded so far
		 */
		@Nonnull
		public EntityCollectionStatistics build() {
			return new EntityCollectionStatistics(
				this.identity,
				this.entityType,
				this.header,
				this.recordCounts,
				this.storageSize,
				this.storageComposition,
				this.fragmentation,
				this.indexSummary,
				this.volatileState,
				Collections.unmodifiableMap(new EnumMap<>(this.componentStatus))
			);
		}

		/**
		 * Marks a component as successfully computed.
		 *
		 * @param component the delivered component
		 * @return this builder
		 */
		@Nonnull
		private Builder delivered(@Nonnull CatalogStatisticsComponent component) {
			this.componentStatus.put(component, ComponentStatus.delivered(component));
			return this;
		}

	}

}
