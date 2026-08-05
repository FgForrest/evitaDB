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
 * A component-selected snapshot of one catalog's statistics.
 *
 * The caller names the {@link CatalogStatisticsComponent}s it wants; every component it did not ask for is null here,
 * and every component it *did* ask for has an entry in {@link #componentStatus()} saying whether it was delivered and,
 * if not, why. That distinction is the whole point of the shape: without it a client cannot tell an unrequested
 * component from one the engine could not compute, which is how a corrupted catalog ends up rendering as an empty
 * catalog on a management screen.
 *
 * **{@link #identity()} is always present**, requested or not - no other component can be interpreted without knowing
 * which catalog produced it and whether that catalog is usable.
 *
 * **Aggregates only**
 *
 * No component here carries a per-collection breakdown. This is the response that gets polled, so its size must not
 * grow with the number of collections in the catalog, and the expensive per-collection work must not be able to leak
 * into it. Every aggregate reported here is assembled from per-collection reads cheap enough to perform for every
 * collection on every request - an in-memory counter, a map size, one directory listing shared by all components.
 * Anything about a single collection is fetched by naming that collection: see {@link EntityCollectionStatistics}.
 *
 * The one exception is {@link CatalogStatisticsComponent#COLLECTIONS}, which lists *which* collections exist without
 * any statistics about them - a client needs that inventory before it can ask for any of them.
 *
 * **Consistency with {@link EntityCollectionStatistics}**
 *
 * Each response is a snapshot at its own catalog version, so an aggregate here need **not** equal the sum of the
 * collection-level values fetched separately - the catalog moves on between the two calls. Compare
 * {@link CatalogIdentity#catalogVersion()} of the two responses when that matters.
 *
 * **Obtaining an instance**
 *
 * - `CatalogContract#getStatistics(Set)` for a single catalog
 * - `EvitaManagementContract#getCatalogStatistics(String, Set)` through the management API, locally or over the driver
 *
 * **Thread-safety**
 *
 * Immutable, and therefore thread-safe. The values are a snapshot taken when the call was served and go stale as the
 * catalog changes.
 *
 * @param identity           who this catalog is and what mode it runs in; always present
 * @param recordCounts       {@link CatalogStatisticsComponent#RECORD_COUNTS}, null unless requested and delivered
 * @param collections        {@link CatalogStatisticsComponent#COLLECTIONS}, null unless requested and delivered
 * @param sessions           {@link CatalogStatisticsComponent#SESSIONS}, null unless requested and delivered
 * @param commitPipeline     {@link CatalogStatisticsComponent#COMMIT_PIPELINE}, null unless requested and delivered
 * @param activity           {@link CatalogStatisticsComponent#ACTIVITY}, null unless requested and delivered
 * @param durability         {@link CatalogStatisticsComponent#DURABILITY}, null unless requested and delivered
 * @param storageSize        {@link CatalogStatisticsComponent#STORAGE_SIZE}, null unless requested and delivered
 * @param storageComposition {@link CatalogStatisticsComponent#STORAGE_COMPOSITION}, null unless requested and
 *                           delivered
 * @param fragmentation      {@link CatalogStatisticsComponent#FRAGMENTATION}, null unless requested and delivered
 * @param history            {@link CatalogStatisticsComponent#HISTORY}, null unless requested and delivered
 * @param indexSummary       {@link CatalogStatisticsComponent#INDEX_SUMMARY}, null unless requested and delivered
 * @param volatileState      {@link CatalogStatisticsComponent#VOLATILE_STATE}, null unless requested and delivered
 * @param componentStatus    outcome of every *requested* component; components that were not requested are absent
 *                           from the map entirely
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024-2026
 */
public record CatalogStatistics(
	@Nonnull CatalogIdentity identity,
	@Nullable RecordCounts recordCounts,
	@Nullable CollectionsInfo collections,
	@Nullable SessionStatistics sessions,
	@Nullable CommitPipelineStatistics commitPipeline,
	@Nullable ActivityStatistics activity,
	@Nullable DurabilityStatistics durability,
	@Nullable StorageSizeStatistics storageSize,
	@Nullable StorageCompositionStatistics storageComposition,
	@Nullable FragmentationStatistics fragmentation,
	@Nullable HistoryStatistics history,
	@Nullable IndexSummaryStatistics indexSummary,
	@Nullable VolatileStateStatistics volatileState,
	@Nonnull Map<CatalogStatisticsComponent, ComponentStatus> componentStatus
) {

	/**
	 * Starts building a snapshot for the given catalog.
	 *
	 * @param identity the always-present identity component
	 * @return a builder seeded with `identity` and no components
	 */
	@Nonnull
	public static Builder builder(@Nonnull CatalogIdentity identity) {
		return new Builder(identity);
	}

	/**
	 * Returns the record counts when they were requested and could be computed.
	 *
	 * @return the {@link CatalogStatisticsComponent#RECORD_COUNTS} component, empty otherwise
	 */
	@Nonnull
	public Optional<RecordCounts> recordCountsIfPresent() {
		return Optional.ofNullable(this.recordCounts);
	}

	/**
	 * Returns the entity collection list when it was requested and could be computed.
	 *
	 * @return the {@link CatalogStatisticsComponent#COLLECTIONS} component, empty otherwise
	 */
	@Nonnull
	public Optional<CollectionsInfo> collectionsIfPresent() {
		return Optional.ofNullable(this.collections);
	}

	/**
	 * Returns the session statistics when they were requested and could be computed.
	 *
	 * @return the {@link CatalogStatisticsComponent#SESSIONS} component, empty otherwise
	 */
	@Nonnull
	public Optional<SessionStatistics> sessionsIfPresent() {
		return Optional.ofNullable(this.sessions);
	}

	/**
	 * Returns the commit pipeline watermarks when they were requested and could be computed.
	 *
	 * @return the {@link CatalogStatisticsComponent#COMMIT_PIPELINE} component, empty otherwise
	 */
	@Nonnull
	public Optional<CommitPipelineStatistics> commitPipelineIfPresent() {
		return Optional.ofNullable(this.commitPipeline);
	}

	/**
	 * Returns the write activity counters when they were requested and could be computed.
	 *
	 * @return the {@link CatalogStatisticsComponent#ACTIVITY} component, empty otherwise
	 */
	@Nonnull
	public Optional<ActivityStatistics> activityIfPresent() {
		return Optional.ofNullable(this.activity);
	}

	/**
	 * Returns the deferred-durability fence readings when they were requested and could be computed.
	 *
	 * @return the {@link CatalogStatisticsComponent#DURABILITY} component, or empty
	 */
	@Nonnull
	public Optional<DurabilityStatistics> durabilityIfPresent() {
		return Optional.ofNullable(this.durability);
	}

	/**
	 * Returns the storage decomposition when it was requested and could be computed.
	 *
	 * @return the {@link CatalogStatisticsComponent#STORAGE_SIZE} component, empty otherwise
	 */
	@Nonnull
	public Optional<StorageSizeStatistics> storageSizeIfPresent() {
		return Optional.ofNullable(this.storageSize);
	}

	/**
	 * Returns the storage-part composition when it was requested and could be computed.
	 *
	 * @return the {@link CatalogStatisticsComponent#STORAGE_COMPOSITION} component, empty otherwise
	 */
	@Nonnull
	public Optional<StorageCompositionStatistics> storageCompositionIfPresent() {
		return Optional.ofNullable(this.storageComposition);
	}

	/**
	 * Returns the fragmentation statistics when they were requested and could be computed.
	 *
	 * @return the {@link CatalogStatisticsComponent#FRAGMENTATION} component, empty otherwise
	 */
	@Nonnull
	public Optional<FragmentationStatistics> fragmentationIfPresent() {
		return Optional.ofNullable(this.fragmentation);
	}

	/**
	 * Returns the history statistics when they were requested and could be computed.
	 *
	 * @return the {@link CatalogStatisticsComponent#HISTORY} component, empty otherwise
	 */
	@Nonnull
	public Optional<HistoryStatistics> historyIfPresent() {
		return Optional.ofNullable(this.history);
	}

	/**
	 * Returns the index summary when it was requested and could be computed.
	 *
	 * @return the {@link CatalogStatisticsComponent#INDEX_SUMMARY} component, empty otherwise
	 */
	@Nonnull
	public Optional<IndexSummaryStatistics> indexSummaryIfPresent() {
		return Optional.ofNullable(this.indexSummary);
	}

	/**
	 * Returns the volatile state statistics when they were requested and could be computed.
	 *
	 * @return the {@link CatalogStatisticsComponent#VOLATILE_STATE} component, empty otherwise
	 */
	@Nonnull
	public Optional<VolatileStateStatistics> volatileStateIfPresent() {
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
	 * Collects the components of a {@link CatalogStatistics} snapshot as they are computed.
	 *
	 * Each `with...` method records both the value and a {@link ComponentAvailability#DELIVERED} status, so the two
	 * can never drift apart; {@link #withUnavailable} records the opposite case. The builder is not thread-safe - one
	 * request builds one snapshot.
	 */
	public static class Builder {
		/**
		 * The always-present identity component.
		 */
		private final CatalogIdentity identity;
		/**
		 * Status of every component requested so far, in enum order.
		 */
		private final Map<CatalogStatisticsComponent, ComponentStatus> componentStatus =
			new EnumMap<>(CatalogStatisticsComponent.class);
		private RecordCounts recordCounts;
		private CollectionsInfo collections;
		private SessionStatistics sessions;
		private CommitPipelineStatistics commitPipeline;
		private ActivityStatistics activity;
		private DurabilityStatistics durability;
		private StorageSizeStatistics storageSize;
		private StorageCompositionStatistics storageComposition;
		private FragmentationStatistics fragmentation;
		private HistoryStatistics history;
		private IndexSummaryStatistics indexSummary;
		private VolatileStateStatistics volatileState;

		Builder(@Nonnull CatalogIdentity identity) {
			this.identity = identity;
			// IDENTITY is delivered unconditionally - record it so a client never has to special-case its absence
			delivered(CatalogStatisticsComponent.IDENTITY);
		}

		/**
		 * Records the {@link CatalogStatisticsComponent#RECORD_COUNTS} component as delivered.
		 *
		 * @param value the computed component
		 * @return this builder
		 */
		@Nonnull
		public Builder withRecordCounts(@Nonnull RecordCounts value) {
			this.recordCounts = value;
			return delivered(CatalogStatisticsComponent.RECORD_COUNTS);
		}

		/**
		 * Records the {@link CatalogStatisticsComponent#COLLECTIONS} component as delivered.
		 *
		 * @param value the computed component
		 * @return this builder
		 */
		@Nonnull
		public Builder withCollections(@Nonnull CollectionsInfo value) {
			this.collections = value;
			return delivered(CatalogStatisticsComponent.COLLECTIONS);
		}

		/**
		 * Records the {@link CatalogStatisticsComponent#SESSIONS} component as delivered.
		 *
		 * @param value the computed component
		 * @return this builder
		 */
		@Nonnull
		public Builder withSessions(@Nonnull SessionStatistics value) {
			this.sessions = value;
			return delivered(CatalogStatisticsComponent.SESSIONS);
		}

		/**
		 * Records the {@link CatalogStatisticsComponent#COMMIT_PIPELINE} component as delivered.
		 *
		 * @param value the computed component
		 * @return this builder
		 */
		@Nonnull
		public Builder withCommitPipeline(@Nonnull CommitPipelineStatistics value) {
			this.commitPipeline = value;
			return delivered(CatalogStatisticsComponent.COMMIT_PIPELINE);
		}

		/**
		 * Records the {@link CatalogStatisticsComponent#ACTIVITY} component as delivered.
		 *
		 * @param value the computed component
		 * @return this builder
		 */
		@Nonnull
		public Builder withActivity(@Nonnull ActivityStatistics value) {
			this.activity = value;
			return delivered(CatalogStatisticsComponent.ACTIVITY);
		}

		/**
		 * Records the {@link CatalogStatisticsComponent#DURABILITY} component as delivered.
		 *
		 * @param value the computed component
		 * @return this builder
		 */
		@Nonnull
		public Builder withDurability(@Nonnull DurabilityStatistics value) {
			this.durability = value;
			return delivered(CatalogStatisticsComponent.DURABILITY);
		}

		/**
		 * Records the {@link CatalogStatisticsComponent#STORAGE_SIZE} component as delivered.
		 *
		 * @param value the computed component
		 * @return this builder
		 */
		@Nonnull
		public Builder withStorageSize(@Nonnull StorageSizeStatistics value) {
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
		public Builder withStorageComposition(@Nonnull StorageCompositionStatistics value) {
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
		public Builder withFragmentation(@Nonnull FragmentationStatistics value) {
			this.fragmentation = value;
			return delivered(CatalogStatisticsComponent.FRAGMENTATION);
		}

		/**
		 * Records the {@link CatalogStatisticsComponent#HISTORY} component as delivered.
		 *
		 * @param value the computed component
		 * @return this builder
		 */
		@Nonnull
		public Builder withHistory(@Nonnull HistoryStatistics value) {
			this.history = value;
			return delivered(CatalogStatisticsComponent.HISTORY);
		}

		/**
		 * Records the {@link CatalogStatisticsComponent#INDEX_SUMMARY} component as delivered.
		 *
		 * @param value the computed component
		 * @return this builder
		 */
		@Nonnull
		public Builder withIndexSummary(@Nonnull IndexSummaryStatistics value) {
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
		public Builder withVolatileState(@Nonnull VolatileStateStatistics value) {
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
		public CatalogStatistics build() {
			return new CatalogStatistics(
				this.identity,
				this.recordCounts,
				this.collections,
				this.sessions,
				this.commitPipeline,
				this.activity,
				this.durability,
				this.storageSize,
				this.storageComposition,
				this.fragmentation,
				this.history,
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
