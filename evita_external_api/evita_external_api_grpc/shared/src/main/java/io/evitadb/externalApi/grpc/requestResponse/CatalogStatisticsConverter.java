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

package io.evitadb.externalApi.grpc.requestResponse;

import com.google.protobuf.StringValue;
import io.evitadb.api.CatalogState;
import io.evitadb.api.statistics.DataStoreFragmentation;
import io.evitadb.api.statistics.CatalogIdentity;
import io.evitadb.api.statistics.CatalogStatistics;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.CollectionHeaderInfo;
import io.evitadb.api.statistics.CollectionIndexSummary;
import io.evitadb.api.statistics.CollectionIndexSummary.IndexKindCount;
import io.evitadb.api.statistics.CollectionRecordCounts;
import io.evitadb.api.statistics.CollectionStorageComposition;
import io.evitadb.api.statistics.CollectionStorageSize;
import io.evitadb.api.statistics.DataStoreVolatileState;
import io.evitadb.api.statistics.CollectionsInfo;
import io.evitadb.api.statistics.CollectionsInfo.CollectionInfo;
import io.evitadb.api.statistics.CommitPipelineStatistics;
import io.evitadb.api.statistics.ComponentStatus;
import io.evitadb.api.statistics.EntityCollectionStatistics;
import io.evitadb.api.statistics.FragmentationStatistics;
import io.evitadb.api.statistics.HistoryStatistics;
import io.evitadb.api.statistics.IndexSummaryStatistics;
import io.evitadb.api.statistics.RecordCounts;
import io.evitadb.api.statistics.SessionStatistics;
import io.evitadb.api.statistics.StorageCompositionStatistics;
import io.evitadb.api.statistics.StoragePartUsage;
import io.evitadb.api.statistics.StorageSizeStatistics;
import io.evitadb.api.statistics.VolatileStateStatistics;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toCatalogStatisticsComponent;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toComponentAvailability;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toEntityIndexKind;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcCatalogStatisticsComponent;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcComponentAvailability;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcEntityIndexKind;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcScope;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toScope;

/**
 * Translates the component-selected statistics model between its Java and its gRPC form, in both directions - the
 * server projects a computed snapshot onto the wire, the driver reads one back.
 *
 * **What this converter has to preserve is a tri-state, not a value**
 *
 * Every component of a snapshot is in exactly one of three situations, and they must stay distinguishable end to end:
 *
 * 1. **not requested** - the sub-message is absent *and* the component has no entry in the status list
 * 2. **delivered** - the sub-message is present and the status says `DELIVERED`, and this holds even when every field
 *    of that sub-message is `0`; a collection with no entities really does report zeroes
 * 3. **requested but unavailable** - the sub-message is absent and the status says why
 *
 * Cases 1 and 3 are both "absent" on the wire and are told apart *only* by the status list, which is precisely the
 * distinction that stops a corrupted catalog from rendering as an empty one. Presence is therefore driven by Java
 * null-ness alone: a `map(...).orElse(<default>)` anywhere in here would turn case 3 into a fabricated case 2.
 *
 * The reverse direction reconstructs the records through their canonical constructors rather than through their
 * builders, because a builder records a `DELIVERED` status alongside every value it accepts - correct when computing a
 * snapshot, wrong when decoding one, where the status the server sent is the answer and must survive verbatim.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see CatalogStatistics
 * @see EntityCollectionStatistics
 */
public class CatalogStatisticsConverter {

	private CatalogStatisticsConverter() {
	}

	/**
	 * Converts the components named in a request into the set the engine takes.
	 *
	 * An empty list is rejected rather than silently answered with an identity-only snapshot: the component list is
	 * what the caller is asking about, so an empty one is a malformed request, not a request for nothing. The engine
	 * refuses it too - this is the message-level gate, so a malformed request never reaches a catalog at all, and the
	 * two agree on the wording deliberately.
	 *
	 * @param grpcComponents components named in the request
	 * @return the requested components
	 * @throws EvitaInvalidUsageException when the list is empty or names an unspecified / unknown component
	 */
	@Nonnull
	public static Set<CatalogStatisticsComponent> toComponents(
		@Nonnull List<GrpcCatalogStatisticsComponent> grpcComponents
	) {
		if (grpcComponents.isEmpty()) {
			throw new EvitaInvalidUsageException(
				"No statistics component was requested - name at least one component to compute."
			);
		}
		final Set<CatalogStatisticsComponent> components = EnumSet.noneOf(CatalogStatisticsComponent.class);
		for (final GrpcCatalogStatisticsComponent grpcComponent : grpcComponents) {
			components.add(toCatalogStatisticsComponent(grpcComponent));
		}
		return components;
	}

	/**
	 * Converts a set of components into the list a request carries.
	 *
	 * @param components components to request
	 * @return the components in their gRPC form, in enum order
	 */
	@Nonnull
	public static List<GrpcCatalogStatisticsComponent> toGrpcComponents(
		@Nonnull Set<CatalogStatisticsComponent> components
	) {
		final GrpcCatalogStatisticsComponent[] grpcComponents = new GrpcCatalogStatisticsComponent[components.size()];
		int index = 0;
		for (final CatalogStatisticsComponent component : components) {
			grpcComponents[index++] = toGrpcCatalogStatisticsComponent(component);
		}
		return List.of(grpcComponents);
	}

	/**
	 * Projects a computed catalog statistics snapshot onto the wire.
	 *
	 * @param statistics the snapshot to project
	 * @return its gRPC form, carrying only the components that were delivered plus the status of every requested one
	 */
	@Nonnull
	public static GrpcCatalogStatisticsSnapshot toGrpcCatalogStatisticsSnapshot(
		@Nonnull CatalogStatistics statistics
	) {
		final GrpcCatalogStatisticsSnapshot.Builder builder = GrpcCatalogStatisticsSnapshot.newBuilder()
			.setIdentity(toGrpcCatalogIdentity(statistics.identity()));
		// every setter below is guarded on null-ness alone - an absent component must stay absent, so that the status
		// list remains the only thing saying whether it was never asked for or could not be computed
		if (statistics.recordCounts() != null) {
			builder.setRecordCounts(toGrpcRecordCounts(statistics.recordCounts()));
		}
		if (statistics.collections() != null) {
			builder.setCollections(toGrpcCollectionsInfo(statistics.collections()));
		}
		if (statistics.sessions() != null) {
			builder.setSessions(toGrpcSessionStatistics(statistics.sessions()));
		}
		if (statistics.commitPipeline() != null) {
			builder.setCommitPipeline(toGrpcCommitPipelineStatistics(statistics.commitPipeline()));
		}
		if (statistics.storageSize() != null) {
			builder.setStorageSize(toGrpcStorageSizeStatistics(statistics.storageSize()));
		}
		if (statistics.storageComposition() != null) {
			builder.setStorageComposition(toGrpcStorageCompositionStatistics(statistics.storageComposition()));
		}
		if (statistics.fragmentation() != null) {
			builder.setFragmentation(toGrpcFragmentationStatistics(statistics.fragmentation()));
		}
		if (statistics.history() != null) {
			builder.setHistory(toGrpcHistoryStatistics(statistics.history()));
		}
		if (statistics.indexSummary() != null) {
			builder.setIndexSummary(toGrpcIndexSummaryStatistics(statistics.indexSummary()));
		}
		if (statistics.volatileState() != null) {
			builder.setVolatileState(toGrpcVolatileStateStatistics(statistics.volatileState()));
		}
		for (final ComponentStatus status : statistics.componentStatus().values()) {
			builder.addComponentStatus(toGrpcComponentStatus(status));
		}
		return builder.build();
	}

	/**
	 * Reads a catalog statistics snapshot back from the wire.
	 *
	 * @param snapshot the received message
	 * @return its Java form, with every absent sub-message left null and the received statuses preserved as sent
	 */
	@Nonnull
	public static CatalogStatistics toCatalogStatistics(@Nonnull GrpcCatalogStatisticsSnapshot snapshot) {
		return new CatalogStatistics(
			toCatalogIdentity(snapshot.getIdentity()),
			snapshot.hasRecordCounts() ? toRecordCounts(snapshot.getRecordCounts()) : null,
			snapshot.hasCollections() ? toCollectionsInfo(snapshot.getCollections()) : null,
			snapshot.hasSessions() ? toSessionStatistics(snapshot.getSessions()) : null,
			snapshot.hasCommitPipeline() ? toCommitPipelineStatistics(snapshot.getCommitPipeline()) : null,
			snapshot.hasStorageSize() ? toStorageSizeStatistics(snapshot.getStorageSize()) : null,
			snapshot.hasStorageComposition() ?
				toStorageCompositionStatistics(snapshot.getStorageComposition()) : null,
			snapshot.hasFragmentation() ? toFragmentationStatistics(snapshot.getFragmentation()) : null,
			snapshot.hasHistory() ? toHistoryStatistics(snapshot.getHistory()) : null,
			snapshot.hasIndexSummary() ? toIndexSummaryStatistics(snapshot.getIndexSummary()) : null,
			snapshot.hasVolatileState() ? toVolatileStateStatistics(snapshot.getVolatileState()) : null,
			toComponentStatuses(snapshot.getComponentStatusList())
		);
	}

	/**
	 * Projects a computed entity collection statistics snapshot onto the wire.
	 *
	 * @param statistics the snapshot to project
	 * @return its gRPC form, carrying only the components that were delivered plus the status of every requested one
	 */
	@Nonnull
	public static GrpcEntityCollectionStatisticsSnapshot toGrpcEntityCollectionStatisticsSnapshot(
		@Nonnull EntityCollectionStatistics statistics
	) {
		final GrpcEntityCollectionStatisticsSnapshot.Builder builder =
			GrpcEntityCollectionStatisticsSnapshot.newBuilder()
				.setIdentity(toGrpcCatalogIdentity(statistics.identity()))
				.setEntityType(statistics.entityType());
		if (statistics.header() != null) {
			builder.setHeader(toGrpcCollectionHeaderInfo(statistics.header()));
		}
		if (statistics.recordCounts() != null) {
			builder.setRecordCounts(toGrpcCollectionRecordCounts(statistics.recordCounts()));
		}
		if (statistics.storageSize() != null) {
			builder.setStorageSize(toGrpcCollectionStorageSize(statistics.storageSize()));
		}
		if (statistics.storageComposition() != null) {
			builder.setStorageComposition(toGrpcCollectionStorageComposition(statistics.storageComposition()));
		}
		if (statistics.fragmentation() != null) {
			builder.setFragmentation(toGrpcDataStoreFragmentation(statistics.fragmentation()));
		}
		if (statistics.indexSummary() != null) {
			builder.setIndexSummary(toGrpcCollectionIndexSummary(statistics.indexSummary()));
		}
		if (statistics.volatileState() != null) {
			builder.setVolatileState(toGrpcDataStoreVolatileState(statistics.volatileState()));
		}
		for (final ComponentStatus status : statistics.componentStatus().values()) {
			builder.addComponentStatus(toGrpcComponentStatus(status));
		}
		return builder.build();
	}

	/**
	 * Reads an entity collection statistics snapshot back from the wire.
	 *
	 * @param snapshot the received message
	 * @return its Java form, with every absent sub-message left null and the received statuses preserved as sent
	 */
	@Nonnull
	public static EntityCollectionStatistics toEntityCollectionStatistics(
		@Nonnull GrpcEntityCollectionStatisticsSnapshot snapshot
	) {
		return new EntityCollectionStatistics(
			toCatalogIdentity(snapshot.getIdentity()),
			snapshot.getEntityType(),
			snapshot.hasHeader() ? toCollectionHeaderInfo(snapshot.getHeader()) : null,
			snapshot.hasRecordCounts() ? toCollectionRecordCounts(snapshot.getRecordCounts()) : null,
			snapshot.hasStorageSize() ? toCollectionStorageSize(snapshot.getStorageSize()) : null,
			snapshot.hasStorageComposition() ?
				toCollectionStorageComposition(snapshot.getStorageComposition()) : null,
			snapshot.hasFragmentation() ? toDataStoreFragmentation(snapshot.getFragmentation()) : null,
			snapshot.hasIndexSummary() ? toCollectionIndexSummary(snapshot.getIndexSummary()) : null,
			snapshot.hasVolatileState() ? toDataStoreVolatileState(snapshot.getVolatileState()) : null,
			toComponentStatuses(snapshot.getComponentStatusList())
		);
	}

	/*
		SHARED PARTS
	 */

	/**
	 * Converts the always-present identity component to its gRPC form.
	 *
	 * @param identity the identity component
	 * @return its gRPC form, with `catalogId` left unset and `catalogState` reported as `UNKNOWN_CATALOG_STATE` when
	 * the corresponding value could not be determined
	 */
	@Nonnull
	private static GrpcCatalogIdentity toGrpcCatalogIdentity(@Nonnull CatalogIdentity identity) {
		final GrpcCatalogIdentity.Builder builder = GrpcCatalogIdentity.newBuilder()
			.setCatalogName(identity.catalogName())
			.setCatalogState(EvitaEnumConverter.toGrpcCatalogState(identity.catalogState()))
			.setCatalogVersion(identity.catalogVersion())
			.setReadOnly(identity.readOnly())
			.setUnusable(identity.unusable())
			.setTransactional(identity.transactional())
			.setGoingLive(identity.goingLive())
			.setEntityCollectionCount(identity.entityCollectionCount());
		if (identity.catalogId() != null) {
			builder.setCatalogId(EvitaDataTypesConverter.toGrpcUuid(identity.catalogId()));
		}
		return builder.build();
	}

	/**
	 * Reads the identity component back from the wire.
	 *
	 * @param grpcIdentity the received identity
	 * @return its Java form
	 */
	@Nonnull
	private static CatalogIdentity toCatalogIdentity(@Nonnull GrpcCatalogIdentity grpcIdentity) {
		return new CatalogIdentity(
			grpcIdentity.hasCatalogId() ? EvitaDataTypesConverter.toUuid(grpcIdentity.getCatalogId()) : null,
			grpcIdentity.getCatalogName(),
			toNullableCatalogState(grpcIdentity.getCatalogState()),
			grpcIdentity.getCatalogVersion(),
			grpcIdentity.getReadOnly(),
			grpcIdentity.getUnusable(),
			grpcIdentity.getTransactional(),
			grpcIdentity.getGoingLive(),
			grpcIdentity.getEntityCollectionCount()
		);
	}

	/**
	 * Converts a catalog state that may legitimately be unknown.
	 *
	 * `EvitaEnumConverter#toCatalogState` throws on `UNKNOWN_CATALOG_STATE`, which is right everywhere the state is
	 * mandatory - but the identity component of a corrupted catalog is exactly the case where the server could not
	 * determine it, and failing there would kill the statistics call for the one catalog worth inspecting.
	 *
	 * @param grpcCatalogState the received state
	 * @return the state, or null when the server could not determine it
	 */
	@Nullable
	private static CatalogState toNullableCatalogState(@Nonnull GrpcCatalogState grpcCatalogState) {
		return grpcCatalogState == GrpcCatalogState.UNKNOWN_CATALOG_STATE ?
			null : EvitaEnumConverter.toCatalogState(grpcCatalogState);
	}

	/**
	 * Converts the status of one requested component to its gRPC form.
	 *
	 * @param status the status to convert
	 * @return its gRPC form, with `reason` left unset when the component was delivered
	 */
	@Nonnull
	private static GrpcComponentStatus toGrpcComponentStatus(@Nonnull ComponentStatus status) {
		final GrpcComponentStatus.Builder builder = GrpcComponentStatus.newBuilder()
			.setComponent(toGrpcCatalogStatisticsComponent(status.component()))
			.setAvailability(toGrpcComponentAvailability(status.availability()));
		if (status.reason() != null) {
			builder.setReason(StringValue.of(status.reason()));
		}
		return builder.build();
	}

	/**
	 * Reads the statuses of the requested components back from the wire.
	 *
	 * The statuses are reconstructed through the canonical constructor rather than the `delivered` / `unavailable`
	 * factories: those enforce the invariant that binds a reason to a non-delivered outcome, which is a rule for the
	 * side *producing* a status. A decoder's job is to report what the server actually said.
	 *
	 * @param grpcStatuses the received statuses
	 * @return an immutable map keyed by component, in enum order; empty when nothing was requested
	 */
	@Nonnull
	private static Map<CatalogStatisticsComponent, ComponentStatus> toComponentStatuses(
		@Nonnull List<GrpcComponentStatus> grpcStatuses
	) {
		final Map<CatalogStatisticsComponent, ComponentStatus> statuses =
			new EnumMap<>(CatalogStatisticsComponent.class);
		for (final GrpcComponentStatus grpcStatus : grpcStatuses) {
			final CatalogStatisticsComponent component = toCatalogStatisticsComponent(grpcStatus.getComponent());
			statuses.put(
				component,
				new ComponentStatus(
					component,
					toComponentAvailability(grpcStatus.getAvailability()),
					grpcStatus.hasReason() ? grpcStatus.getReason().getValue() : null
				)
			);
		}
		return Collections.unmodifiableMap(statuses);
	}

	/**
	 * Converts one storage-part histogram entry to its gRPC form.
	 *
	 * @param usage the entry to convert
	 * @return its gRPC form
	 */
	@Nonnull
	private static GrpcStoragePartUsage toGrpcStoragePartUsage(@Nonnull StoragePartUsage usage) {
		return GrpcStoragePartUsage.newBuilder()
			.setStoragePartType(usage.storagePartType())
			.setCount(usage.count())
			.setTotalBytes(usage.totalBytes())
			.build();
	}

	/**
	 * Reads a storage-part histogram back from the wire.
	 *
	 * @param grpcUsages the received entries
	 * @return their Java form
	 */
	@Nonnull
	private static StoragePartUsage[] toStoragePartUsages(@Nonnull List<GrpcStoragePartUsage> grpcUsages) {
		final StoragePartUsage[] usages = new StoragePartUsage[grpcUsages.size()];
		for (int i = 0; i < usages.length; i++) {
			final GrpcStoragePartUsage grpcUsage = grpcUsages.get(i);
			usages[i] = new StoragePartUsage(
				grpcUsage.getStoragePartType(), grpcUsage.getCount(), grpcUsage.getTotalBytes()
			);
		}
		return usages;
	}

	/*
		CATALOG LEVEL COMPONENTS
	 */

	/**
	 * Converts the catalog-level record counts to their gRPC form.
	 *
	 * @param recordCounts the component to convert
	 * @return its gRPC form
	 */
	@Nonnull
	private static GrpcRecordCounts toGrpcRecordCounts(@Nonnull RecordCounts recordCounts) {
		return GrpcRecordCounts.newBuilder()
			.setTotalRecords(recordCounts.totalRecords())
			.setLiveRecords(recordCounts.liveRecords())
			.setArchivedRecords(recordCounts.archivedRecords())
			.build();
	}

	/**
	 * Reads the catalog-level record counts back from the wire.
	 *
	 * @param grpcRecordCounts the received component
	 * @return its Java form
	 */
	@Nonnull
	private static RecordCounts toRecordCounts(@Nonnull GrpcRecordCounts grpcRecordCounts) {
		return new RecordCounts(
			grpcRecordCounts.getTotalRecords(),
			grpcRecordCounts.getLiveRecords(),
			grpcRecordCounts.getArchivedRecords()
		);
	}

	/**
	 * Converts the entity collection inventory to its gRPC form.
	 *
	 * @param collections the component to convert
	 * @return its gRPC form
	 */
	@Nonnull
	private static GrpcCollectionsInfo toGrpcCollectionsInfo(@Nonnull CollectionsInfo collections) {
		final GrpcCollectionsInfo.Builder builder = GrpcCollectionsInfo.newBuilder();
		for (final CollectionInfo collection : collections.collections()) {
			builder.addCollections(
				GrpcCollectionInfo.newBuilder()
					.setEntityType(collection.entityType())
					.setEntityTypePrimaryKey(collection.entityTypePrimaryKey())
					.build()
			);
		}
		return builder.build();
	}

	/**
	 * Reads the entity collection inventory back from the wire.
	 *
	 * @param grpcCollections the received component
	 * @return its Java form
	 */
	@Nonnull
	private static CollectionsInfo toCollectionsInfo(@Nonnull GrpcCollectionsInfo grpcCollections) {
		final List<GrpcCollectionInfo> grpcCollectionList = grpcCollections.getCollectionsList();
		final CollectionInfo[] collections = new CollectionInfo[grpcCollectionList.size()];
		for (int i = 0; i < collections.length; i++) {
			final GrpcCollectionInfo grpcCollection = grpcCollectionList.get(i);
			collections[i] = new CollectionInfo(
				grpcCollection.getEntityType(), grpcCollection.getEntityTypePrimaryKey()
			);
		}
		return new CollectionsInfo(collections);
	}

	/**
	 * Converts the session statistics to their gRPC form.
	 *
	 * @param sessions the component to convert
	 * @return its gRPC form
	 */
	@Nonnull
	private static GrpcSessionStatistics toGrpcSessionStatistics(@Nonnull SessionStatistics sessions) {
		return GrpcSessionStatistics.newBuilder()
			.setActiveSessions(sessions.activeSessions())
			.setActiveReadOnlySessions(sessions.activeReadOnlySessions())
			.setActiveReadWriteSessions(sessions.activeReadWriteSessions())
			.build();
	}

	/**
	 * Reads the session statistics back from the wire.
	 *
	 * @param grpcSessions the received component
	 * @return its Java form
	 */
	@Nonnull
	private static SessionStatistics toSessionStatistics(@Nonnull GrpcSessionStatistics grpcSessions) {
		return new SessionStatistics(
			grpcSessions.getActiveSessions(),
			grpcSessions.getActiveReadOnlySessions(),
			grpcSessions.getActiveReadWriteSessions()
		);
	}

	/**
	 * Converts the commit pipeline watermarks to their gRPC form.
	 *
	 * @param commitPipeline the component to convert
	 * @return its gRPC form
	 */
	@Nonnull
	private static GrpcCommitPipelineStatistics toGrpcCommitPipelineStatistics(
		@Nonnull CommitPipelineStatistics commitPipeline
	) {
		return GrpcCommitPipelineStatistics.newBuilder()
			.setLastAssignedCatalogVersion(commitPipeline.lastAssignedCatalogVersion())
			.setLastWrittenCatalogVersion(commitPipeline.lastWrittenCatalogVersion())
			.setLastDurableCatalogVersion(commitPipeline.lastDurableCatalogVersion())
			.setLastFinalizedCatalogVersion(commitPipeline.lastFinalizedCatalogVersion())
			.build();
	}

	/**
	 * Reads the commit pipeline watermarks back from the wire.
	 *
	 * @param grpcCommitPipeline the received component
	 * @return its Java form
	 */
	@Nonnull
	private static CommitPipelineStatistics toCommitPipelineStatistics(
		@Nonnull GrpcCommitPipelineStatistics grpcCommitPipeline
	) {
		return new CommitPipelineStatistics(
			grpcCommitPipeline.getLastAssignedCatalogVersion(),
			grpcCommitPipeline.getLastWrittenCatalogVersion(),
			grpcCommitPipeline.getLastDurableCatalogVersion(),
			grpcCommitPipeline.getLastFinalizedCatalogVersion()
		);
	}

	/**
	 * Converts the catalog-level storage decomposition to its gRPC form.
	 *
	 * @param storageSize the component to convert
	 * @return its gRPC form
	 */
	@Nonnull
	private static GrpcStorageSizeStatistics toGrpcStorageSizeStatistics(@Nonnull StorageSizeStatistics storageSize) {
		return GrpcStorageSizeStatistics.newBuilder()
			.setSizeOnDiskInBytes(storageSize.sizeOnDiskInBytes())
			.setLiveBytes(storageSize.liveBytes())
			.setWasteBytes(storageSize.wasteBytes())
			.setCatalogDataStoreLiveBytes(storageSize.catalogDataStoreLiveBytes())
			.setCatalogDataStoreWasteBytes(storageSize.catalogDataStoreWasteBytes())
			.setWalBytes(storageSize.walBytes())
			.setAwaitingDeletionBytes(storageSize.awaitingDeletionBytes())
			.setBlockedByActiveReaderBytes(storageSize.blockedByActiveReaderBytes())
			.setPurgeableBytes(storageSize.purgeableBytes())
			.setBootstrapBytes(storageSize.bootstrapBytes())
			.setUnaccountedBytes(storageSize.unaccountedBytes())
			.build();
	}

	/**
	 * Reads the catalog-level storage decomposition back from the wire.
	 *
	 * @param grpcStorageSize the received component
	 * @return its Java form
	 */
	@Nonnull
	private static StorageSizeStatistics toStorageSizeStatistics(
		@Nonnull GrpcStorageSizeStatistics grpcStorageSize
	) {
		return new StorageSizeStatistics(
			grpcStorageSize.getSizeOnDiskInBytes(),
			grpcStorageSize.getLiveBytes(),
			grpcStorageSize.getWasteBytes(),
			grpcStorageSize.getCatalogDataStoreLiveBytes(),
			grpcStorageSize.getCatalogDataStoreWasteBytes(),
			grpcStorageSize.getWalBytes(),
			grpcStorageSize.getAwaitingDeletionBytes(),
			grpcStorageSize.getBlockedByActiveReaderBytes(),
			grpcStorageSize.getPurgeableBytes(),
			grpcStorageSize.getBootstrapBytes(),
			grpcStorageSize.getUnaccountedBytes()
		);
	}

	/**
	 * Converts the catalog-level storage-part histogram to its gRPC form.
	 *
	 * @param storageComposition the component to convert
	 * @return its gRPC form
	 */
	@Nonnull
	private static GrpcStorageCompositionStatistics toGrpcStorageCompositionStatistics(
		@Nonnull StorageCompositionStatistics storageComposition
	) {
		final GrpcStorageCompositionStatistics.Builder builder = GrpcStorageCompositionStatistics.newBuilder();
		for (final StoragePartUsage usage : storageComposition.catalogParts()) {
			builder.addCatalogParts(toGrpcStoragePartUsage(usage));
		}
		return builder.build();
	}

	/**
	 * Reads the catalog-level storage-part histogram back from the wire.
	 *
	 * @param grpcStorageComposition the received component
	 * @return its Java form
	 */
	@Nonnull
	private static StorageCompositionStatistics toStorageCompositionStatistics(
		@Nonnull GrpcStorageCompositionStatistics grpcStorageComposition
	) {
		return new StorageCompositionStatistics(toStoragePartUsages(grpcStorageComposition.getCatalogPartsList()));
	}

	/**
	 * Converts the catalog-level fragmentation statistics to their gRPC form.
	 *
	 * @param fragmentation the component to convert
	 * @return its gRPC form
	 */
	@Nonnull
	private static GrpcFragmentationStatistics toGrpcFragmentationStatistics(
		@Nonnull FragmentationStatistics fragmentation
	) {
		final GrpcFragmentationStatistics.Builder builder = GrpcFragmentationStatistics.newBuilder()
			.setActiveRecordShare(fragmentation.activeRecordShare())
			.setLiveBytes(fragmentation.liveBytes())
			.setWasteBytes(fragmentation.wasteBytes())
			.setCompactionEligibleNow(fragmentation.compactionEligibleNow())
			.setWasteBytesGenerated(fragmentation.wasteBytesGenerated())
			.setWasteAccumulationRateBytesPerSecond(fragmentation.wasteAccumulationRateBytesPerSecond())
			.setFileSizeCompactionThresholdBytes(fragmentation.fileSizeCompactionThresholdBytes())
			.setMinimalActiveRecordShare(fragmentation.minimalActiveRecordShare())
			.setMaxWasteActiveShare(fragmentation.maxWasteActiveShare())
			.setMinCompactionIntervalMilliseconds(fragmentation.minCompactionIntervalMilliseconds())
			.setCatalogDataStore(toGrpcDataStoreFragmentation(fragmentation.catalogDataStore()));
		if (fragmentation.estimatedCompactionAt() != null) {
			builder.setEstimatedCompactionAt(
				EvitaDataTypesConverter.toGrpcOffsetDateTime(fragmentation.estimatedCompactionAt())
			);
		}
		return builder.build();
	}

	/**
	 * Reads the catalog-level fragmentation statistics back from the wire.
	 *
	 * @param grpcFragmentation the received component
	 * @return its Java form
	 */
	@Nonnull
	private static FragmentationStatistics toFragmentationStatistics(
		@Nonnull GrpcFragmentationStatistics grpcFragmentation
	) {
		return new FragmentationStatistics(
			grpcFragmentation.getActiveRecordShare(),
			grpcFragmentation.getLiveBytes(),
			grpcFragmentation.getWasteBytes(),
			grpcFragmentation.getCompactionEligibleNow(),
			grpcFragmentation.getWasteBytesGenerated(),
			grpcFragmentation.getWasteAccumulationRateBytesPerSecond(),
			grpcFragmentation.hasEstimatedCompactionAt() ?
				EvitaDataTypesConverter.toOffsetDateTime(grpcFragmentation.getEstimatedCompactionAt()) : null,
			toDataStoreFragmentation(grpcFragmentation.getCatalogDataStore()),
			grpcFragmentation.getFileSizeCompactionThresholdBytes(),
			grpcFragmentation.getMinimalActiveRecordShare(),
			grpcFragmentation.getMaxWasteActiveShare(),
			grpcFragmentation.getMinCompactionIntervalMilliseconds()
		);
	}

	/**
	 * Converts the history statistics to their gRPC form.
	 *
	 * @param history the component to convert
	 * @return its gRPC form, with both timestamps left unset when they are unknown
	 */
	@Nonnull
	private static GrpcHistoryStatistics toGrpcHistoryStatistics(@Nonnull HistoryStatistics history) {
		final GrpcHistoryStatistics.Builder builder = GrpcHistoryStatistics.newBuilder()
			.setTimeTravelEnabled(history.timeTravelEnabled())
			.setOldestAvailableCatalogVersion(history.oldestAvailableCatalogVersion())
			.setNewestCatalogVersion(history.newestCatalogVersion())
			.setWalFileCount(history.walFileCount())
			.setWalBytes(history.walBytes())
			.setActiveReaderFloor(history.activeReaderFloor())
			.setAwaitingDeletionFileCount(history.awaitingDeletionFileCount())
			.setAwaitingDeletionBytes(history.awaitingDeletionBytes())
			.setBlockedByActiveReaderBytes(history.blockedByActiveReaderBytes())
			.setPurgeableBytes(history.purgeableBytes());
		if (history.oldestAvailableTimestamp() != null) {
			builder.setOldestAvailableTimestamp(
				EvitaDataTypesConverter.toGrpcOffsetDateTime(history.oldestAvailableTimestamp())
			);
		}
		if (history.newestTimestamp() != null) {
			builder.setNewestTimestamp(EvitaDataTypesConverter.toGrpcOffsetDateTime(history.newestTimestamp()));
		}
		return builder.build();
	}

	/**
	 * Reads the history statistics back from the wire.
	 *
	 * @param grpcHistory the received component
	 * @return its Java form
	 */
	@Nonnull
	private static HistoryStatistics toHistoryStatistics(@Nonnull GrpcHistoryStatistics grpcHistory) {
		return new HistoryStatistics(
			grpcHistory.getTimeTravelEnabled(),
			grpcHistory.getOldestAvailableCatalogVersion(),
			grpcHistory.hasOldestAvailableTimestamp() ?
				EvitaDataTypesConverter.toOffsetDateTime(grpcHistory.getOldestAvailableTimestamp()) : null,
			grpcHistory.getNewestCatalogVersion(),
			grpcHistory.hasNewestTimestamp() ?
				EvitaDataTypesConverter.toOffsetDateTime(grpcHistory.getNewestTimestamp()) : null,
			grpcHistory.getWalFileCount(),
			grpcHistory.getWalBytes(),
			grpcHistory.getActiveReaderFloor(),
			grpcHistory.getAwaitingDeletionFileCount(),
			grpcHistory.getAwaitingDeletionBytes(),
			grpcHistory.getBlockedByActiveReaderBytes(),
			grpcHistory.getPurgeableBytes()
		);
	}

	/**
	 * Converts the catalog-level index summary to its gRPC form.
	 *
	 * @param indexSummary the component to convert
	 * @return its gRPC form
	 */
	@Nonnull
	private static GrpcIndexSummaryStatistics toGrpcIndexSummaryStatistics(
		@Nonnull IndexSummaryStatistics indexSummary
	) {
		return GrpcIndexSummaryStatistics.newBuilder()
			.setTotalIndexCount(indexSummary.totalIndexCount())
			.build();
	}

	/**
	 * Reads the catalog-level index summary back from the wire.
	 *
	 * @param grpcIndexSummary the received component
	 * @return its Java form
	 */
	@Nonnull
	private static IndexSummaryStatistics toIndexSummaryStatistics(
		@Nonnull GrpcIndexSummaryStatistics grpcIndexSummary
	) {
		return new IndexSummaryStatistics(grpcIndexSummary.getTotalIndexCount());
	}

	/**
	 * Converts the catalog-level volatile state to its gRPC form.
	 *
	 * @param volatileState the component to convert
	 * @return its gRPC form, with the timestamp left unset when nothing is being retained
	 */
	@Nonnull
	private static GrpcVolatileStateStatistics toGrpcVolatileStateStatistics(
		@Nonnull VolatileStateStatistics volatileState
	) {
		final GrpcVolatileStateStatistics.Builder builder = GrpcVolatileStateStatistics.newBuilder()
			.setTotalSizeIncludingVolatileDataBytes(volatileState.totalSizeIncludingVolatileDataBytes())
			.setNonFlushedRecordCount(volatileState.nonFlushedRecordCount())
			.setNonFlushedSizeBytes(volatileState.nonFlushedSizeBytes())
			.setCatalogDataStore(toGrpcDataStoreVolatileState(volatileState.catalogDataStore()));
		if (volatileState.oldestRecordKeptTimestamp() != null) {
			builder.setOldestRecordKeptTimestamp(
				EvitaDataTypesConverter.toGrpcOffsetDateTime(volatileState.oldestRecordKeptTimestamp())
			);
		}
		return builder.build();
	}

	/**
	 * Reads the catalog-level volatile state back from the wire.
	 *
	 * @param grpcVolatileState the received component
	 * @return its Java form
	 */
	@Nonnull
	private static VolatileStateStatistics toVolatileStateStatistics(
		@Nonnull GrpcVolatileStateStatistics grpcVolatileState
	) {
		return new VolatileStateStatistics(
			grpcVolatileState.getTotalSizeIncludingVolatileDataBytes(),
			grpcVolatileState.getNonFlushedRecordCount(),
			grpcVolatileState.getNonFlushedSizeBytes(),
			grpcVolatileState.hasOldestRecordKeptTimestamp() ?
				EvitaDataTypesConverter.toOffsetDateTime(grpcVolatileState.getOldestRecordKeptTimestamp()) : null,
			toDataStoreVolatileState(grpcVolatileState.getCatalogDataStore())
		);
	}

	/*
		ENTITY COLLECTION LEVEL COMPONENTS
	 */

	/**
	 * Converts one collection's header counters to their gRPC form.
	 *
	 * @param header the component to convert
	 * @return its gRPC form
	 */
	@Nonnull
	private static GrpcCollectionHeaderInfo toGrpcCollectionHeaderInfo(@Nonnull CollectionHeaderInfo header) {
		return GrpcCollectionHeaderInfo.newBuilder()
			.setEntityTypePrimaryKey(header.entityTypePrimaryKey())
			.setVersion(header.version())
			.setLastPrimaryKey(header.lastPrimaryKey())
			.setLastEntityIndexPrimaryKey(header.lastEntityIndexPrimaryKey())
			.setLastInternalPriceId(header.lastInternalPriceId())
			.setLastKeyId(header.lastKeyId())
			.setMaxRecordSizeBytes(header.maxRecordSizeBytes())
			.build();
	}

	/**
	 * Reads one collection's header counters back from the wire.
	 *
	 * @param grpcHeader the received component
	 * @return its Java form
	 */
	@Nonnull
	private static CollectionHeaderInfo toCollectionHeaderInfo(@Nonnull GrpcCollectionHeaderInfo grpcHeader) {
		return new CollectionHeaderInfo(
			grpcHeader.getEntityTypePrimaryKey(),
			grpcHeader.getVersion(),
			grpcHeader.getLastPrimaryKey(),
			grpcHeader.getLastEntityIndexPrimaryKey(),
			grpcHeader.getLastInternalPriceId(),
			grpcHeader.getLastKeyId(),
			grpcHeader.getMaxRecordSizeBytes()
		);
	}

	/**
	 * Converts one collection's record counts to their gRPC form.
	 *
	 * @param recordCounts the component to convert
	 * @return its gRPC form
	 */
	@Nonnull
	private static GrpcCollectionRecordCounts toGrpcCollectionRecordCounts(
		@Nonnull CollectionRecordCounts recordCounts
	) {
		return GrpcCollectionRecordCounts.newBuilder()
			.setTotalRecords(recordCounts.totalRecords())
			.setLiveRecords(recordCounts.liveRecords())
			.setArchivedRecords(recordCounts.archivedRecords())
			.build();
	}

	/**
	 * Reads one collection's record counts back from the wire.
	 *
	 * @param grpcRecordCounts the received component
	 * @return its Java form
	 */
	@Nonnull
	private static CollectionRecordCounts toCollectionRecordCounts(
		@Nonnull GrpcCollectionRecordCounts grpcRecordCounts
	) {
		return new CollectionRecordCounts(
			grpcRecordCounts.getTotalRecords(),
			grpcRecordCounts.getLiveRecords(),
			grpcRecordCounts.getArchivedRecords()
		);
	}

	/**
	 * Converts one collection's storage decomposition to its gRPC form.
	 *
	 * @param storageSize the component to convert
	 * @return its gRPC form
	 */
	@Nonnull
	private static GrpcCollectionStorageSize toGrpcCollectionStorageSize(
		@Nonnull CollectionStorageSize storageSize
	) {
		return GrpcCollectionStorageSize.newBuilder()
			.setSizeOnDiskInBytes(storageSize.sizeOnDiskInBytes())
			.setLiveBytes(storageSize.liveBytes())
			.setWasteBytes(storageSize.wasteBytes())
			.setAwaitingDeletionBytes(storageSize.awaitingDeletionBytes())
			.setUnaccountedBytes(storageSize.unaccountedBytes())
			.build();
	}

	/**
	 * Reads one collection's storage decomposition back from the wire.
	 *
	 * @param grpcStorageSize the received component
	 * @return its Java form
	 */
	@Nonnull
	private static CollectionStorageSize toCollectionStorageSize(
		@Nonnull GrpcCollectionStorageSize grpcStorageSize
	) {
		return new CollectionStorageSize(
			grpcStorageSize.getSizeOnDiskInBytes(),
			grpcStorageSize.getLiveBytes(),
			grpcStorageSize.getWasteBytes(),
			grpcStorageSize.getAwaitingDeletionBytes(),
			grpcStorageSize.getUnaccountedBytes()
		);
	}

	/**
	 * Converts one collection's storage-part histogram to its gRPC form.
	 *
	 * @param storageComposition the component to convert
	 * @return its gRPC form
	 */
	@Nonnull
	private static GrpcCollectionStorageComposition toGrpcCollectionStorageComposition(
		@Nonnull CollectionStorageComposition storageComposition
	) {
		final GrpcCollectionStorageComposition.Builder builder = GrpcCollectionStorageComposition.newBuilder();
		for (final StoragePartUsage usage : storageComposition.parts()) {
			builder.addParts(toGrpcStoragePartUsage(usage));
		}
		return builder.build();
	}

	/**
	 * Reads one collection's storage-part histogram back from the wire.
	 *
	 * @param grpcStorageComposition the received component
	 * @return its Java form
	 */
	@Nonnull
	private static CollectionStorageComposition toCollectionStorageComposition(
		@Nonnull GrpcCollectionStorageComposition grpcStorageComposition
	) {
		return new CollectionStorageComposition(toStoragePartUsages(grpcStorageComposition.getPartsList()));
	}

	/**
	 * Converts one data store's fragmentation to its gRPC form - a collection's, or the catalog's own.
	 *
	 * @param fragmentation the component to convert
	 * @return its gRPC form
	 */
	@Nonnull
	private static GrpcDataStoreFragmentation toGrpcDataStoreFragmentation(
		@Nonnull DataStoreFragmentation fragmentation
	) {
		final GrpcDataStoreFragmentation.Builder builder = GrpcDataStoreFragmentation.newBuilder()
			.setActiveRecordShare(fragmentation.activeRecordShare())
			.setLiveBytes(fragmentation.liveBytes())
			.setWasteBytes(fragmentation.wasteBytes())
			.setCompactionEligibleNow(fragmentation.compactionEligibleNow())
			.setWasteBytesGenerated(fragmentation.wasteBytesGenerated())
			.setWasteAccumulationRateBytesPerSecond(fragmentation.wasteAccumulationRateBytesPerSecond());
		if (fragmentation.estimatedCompactionAt() != null) {
			builder.setEstimatedCompactionAt(
				EvitaDataTypesConverter.toGrpcOffsetDateTime(fragmentation.estimatedCompactionAt())
			);
		}
		return builder.build();
	}

	/**
	 * Reads one data store's fragmentation back from the wire.
	 *
	 * @param grpcFragmentation the received component
	 * @return its Java form
	 */
	@Nonnull
	private static DataStoreFragmentation toDataStoreFragmentation(
		@Nonnull GrpcDataStoreFragmentation grpcFragmentation
	) {
		return new DataStoreFragmentation(
			grpcFragmentation.getActiveRecordShare(),
			grpcFragmentation.getLiveBytes(),
			grpcFragmentation.getWasteBytes(),
			grpcFragmentation.getCompactionEligibleNow(),
			grpcFragmentation.getWasteBytesGenerated(),
			grpcFragmentation.getWasteAccumulationRateBytesPerSecond(),
			grpcFragmentation.hasEstimatedCompactionAt() ?
				EvitaDataTypesConverter.toOffsetDateTime(grpcFragmentation.getEstimatedCompactionAt()) : null
		);
	}

	/**
	 * Converts one collection's index summary to its gRPC form.
	 *
	 * @param indexSummary the component to convert
	 * @return its gRPC form
	 */
	@Nonnull
	private static GrpcCollectionIndexSummary toGrpcCollectionIndexSummary(
		@Nonnull CollectionIndexSummary indexSummary
	) {
		final GrpcCollectionIndexSummary.Builder builder = GrpcCollectionIndexSummary.newBuilder()
			.setTotalIndexCount(indexSummary.totalIndexCount());
		for (final IndexKindCount kindCount : indexSummary.byKindAndScope()) {
			builder.addByKindAndScope(
				GrpcIndexKindCount.newBuilder()
					.setIndexKind(toGrpcEntityIndexKind(kindCount.indexKind()))
					.setScope(toGrpcScope(kindCount.scope()))
					.setCount(kindCount.count())
					.build()
			);
		}
		return builder.build();
	}

	/**
	 * Reads one collection's index summary back from the wire.
	 *
	 * @param grpcIndexSummary the received component
	 * @return its Java form
	 */
	@Nonnull
	private static CollectionIndexSummary toCollectionIndexSummary(
		@Nonnull GrpcCollectionIndexSummary grpcIndexSummary
	) {
		final List<GrpcIndexKindCount> grpcKindCounts = grpcIndexSummary.getByKindAndScopeList();
		final IndexKindCount[] kindCounts = new IndexKindCount[grpcKindCounts.size()];
		for (int i = 0; i < kindCounts.length; i++) {
			final GrpcIndexKindCount grpcKindCount = grpcKindCounts.get(i);
			kindCounts[i] = new IndexKindCount(
				toEntityIndexKind(grpcKindCount.getIndexKind()),
				toScope(grpcKindCount.getScope()),
				grpcKindCount.getCount()
			);
		}
		return new CollectionIndexSummary(grpcIndexSummary.getTotalIndexCount(), kindCounts);
	}

	/**
	 * Converts one collection's volatile state to its gRPC form.
	 *
	 * @param volatileState the component to convert
	 * @return its gRPC form, with the timestamp left unset when nothing is being retained
	 */
	@Nonnull
	private static GrpcDataStoreVolatileState toGrpcDataStoreVolatileState(
		@Nonnull DataStoreVolatileState volatileState
	) {
		final GrpcDataStoreVolatileState.Builder builder = GrpcDataStoreVolatileState.newBuilder()
			.setTotalSizeIncludingVolatileDataBytes(volatileState.totalSizeIncludingVolatileDataBytes())
			.setNonFlushedRecordCount(volatileState.nonFlushedRecordCount())
			.setNonFlushedSizeBytes(volatileState.nonFlushedSizeBytes());
		if (volatileState.oldestRecordKeptTimestamp() != null) {
			builder.setOldestRecordKeptTimestamp(
				EvitaDataTypesConverter.toGrpcOffsetDateTime(volatileState.oldestRecordKeptTimestamp())
			);
		}
		return builder.build();
	}

	/**
	 * Reads one collection's volatile state back from the wire.
	 *
	 * @param grpcVolatileState the received component
	 * @return its Java form
	 */
	@Nonnull
	private static DataStoreVolatileState toDataStoreVolatileState(
		@Nonnull GrpcDataStoreVolatileState grpcVolatileState
	) {
		return new DataStoreVolatileState(
			grpcVolatileState.getTotalSizeIncludingVolatileDataBytes(),
			grpcVolatileState.getNonFlushedRecordCount(),
			grpcVolatileState.getNonFlushedSizeBytes(),
			grpcVolatileState.hasOldestRecordKeptTimestamp() ?
				EvitaDataTypesConverter.toOffsetDateTime(grpcVolatileState.getOldestRecordKeptTimestamp()) : null
		);
	}

}
