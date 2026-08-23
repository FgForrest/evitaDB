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

import com.google.protobuf.Int32Value;
import com.google.protobuf.StringValue;
import io.evitadb.api.CatalogState;
import io.evitadb.api.statistics.ActivityStatistics;
import io.evitadb.api.statistics.BrowsedIndex;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.statistics.IndexBrowseCriteria;
import io.evitadb.api.statistics.IndexBrowseResult;
import io.evitadb.api.statistics.DataStoreFragmentation;
import io.evitadb.api.statistics.CatalogIdentity;
import io.evitadb.api.statistics.CatalogIndexCardinality;
import io.evitadb.api.statistics.CatalogIndexCardinality.GlobalUniqueIndexCardinality;
import io.evitadb.api.statistics.CatalogStatistics;
import io.evitadb.api.statistics.CatalogStatisticsComponent;
import io.evitadb.api.statistics.CollectionHeaderInfo;
import io.evitadb.api.statistics.CollectionIndexCardinality;
import io.evitadb.api.statistics.CollectionIndexCardinality.AttributeCardinality;
import io.evitadb.api.statistics.CollectionIndexCardinality.IndexCardinality;
import io.evitadb.api.statistics.CollectionIndexSummary;
import io.evitadb.api.statistics.CollectionIndexSummary.IndexTypeCount;
import io.evitadb.api.statistics.CollectionRecordCounts;
import io.evitadb.api.statistics.CollectionStorageComposition;
import io.evitadb.api.statistics.CollectionStorageSize;
import io.evitadb.api.statistics.DataStoreVolatileState;
import io.evitadb.api.statistics.IndexDetail;
import io.evitadb.api.statistics.CollectionsInfo;
import io.evitadb.api.statistics.CollectionsInfo.CollectionInfo;
import io.evitadb.api.statistics.CommitPipelineStatistics;
import io.evitadb.api.statistics.ComponentAvailability;
import io.evitadb.api.statistics.ComponentStatus;
import io.evitadb.api.statistics.DurabilityStatistics;
import io.evitadb.api.statistics.EntityCollectionStatistics;
import io.evitadb.api.statistics.FragmentationStatistics;
import io.evitadb.api.statistics.HistoryStatistics;
import io.evitadb.api.statistics.IndexSummaryStatistics;
import io.evitadb.api.statistics.RecordCounts;
import io.evitadb.api.statistics.SchemaCapabilityUsageStatistics;
import io.evitadb.api.statistics.SessionStatistics;
import io.evitadb.api.statistics.StorageCompositionStatistics;
import io.evitadb.api.statistics.StoragePartUsage;
import io.evitadb.api.statistics.StorageSizeStatistics;
import io.evitadb.api.statistics.VolatileStateStatistics;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toAttributeIndexType;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toCatalogStatisticsComponent;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toComponentAvailability;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toEntityIndexType;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcAttributeIndexType;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcCatalogStatisticsComponent;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcComponentAvailability;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcEntityIndexType;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcOrderDirection;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcSchemaCapability;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcSchemaElementKind;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcScope;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcIndexBrowseOrdering;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toIndexBrowseOrdering;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toOrderDirection;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toSchemaCapability;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toSchemaElementKind;
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
		if (statistics.activity() != null) {
			builder.setActivity(toGrpcActivityStatistics(statistics.activity()));
		}
		if (statistics.durability() != null) {
			builder.setDurability(toGrpcDurabilityStatistics(statistics.durability()));
		}
		if (statistics.indexCardinality() != null) {
			builder.setIndexCardinality(toGrpcCatalogIndexCardinality(statistics.indexCardinality()));
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
			snapshot.hasActivity() ? toActivityStatistics(snapshot.getActivity()) : null,
			snapshot.hasDurability() ? toDurabilityStatistics(snapshot.getDurability()) : null,
			snapshot.hasStorageSize() ? toStorageSizeStatistics(snapshot.getStorageSize()) : null,
			snapshot.hasStorageComposition() ?
				toStorageCompositionStatistics(snapshot.getStorageComposition()) : null,
			snapshot.hasFragmentation() ? toFragmentationStatistics(snapshot.getFragmentation()) : null,
			snapshot.hasHistory() ? toHistoryStatistics(snapshot.getHistory()) : null,
			snapshot.hasIndexSummary() ? toIndexSummaryStatistics(snapshot.getIndexSummary()) : null,
			snapshot.hasIndexCardinality() ? toCatalogIndexCardinality(snapshot.getIndexCardinality()) : null,
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
		if (statistics.indexCardinality() != null) {
			builder.setIndexCardinality(toGrpcCollectionIndexCardinality(statistics.indexCardinality()));
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
			snapshot.hasIndexCardinality() ?
				toCollectionIndexCardinality(snapshot.getIndexCardinality()) : null,
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
	 * The availability is reported exactly as the server sent it - a decoder's job is to say what the peer said, not
	 * to second-guess it. The **reason** is reconciled with it, because {@link ComponentStatus} guarantees that a
	 * non-delivered status carries an explanation and a delivered one carries none, and the wire cannot be trusted to
	 * honour that: a peer running an older or simply broken build can send an unavailability with no reason at all.
	 * Rather than let that through and produce the silent "unavailable, no explanation" the component model exists to
	 * prevent, a placeholder is substituted saying precisely that the peer omitted it.
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
			final ComponentAvailability availability = toComponentAvailability(grpcStatus.getAvailability());
			final String receivedReason = grpcStatus.hasReason() ? grpcStatus.getReason().getValue() : null;
			statuses.put(
				component,
				availability == ComponentAvailability.DELIVERED ?
					ComponentStatus.delivered(component) :
					ComponentStatus.unavailable(
						component,
						availability,
						receivedReason == null || receivedReason.isBlank() ?
							"The server reported this component as " + availability +
								" but sent no explanation with it." :
							receivedReason
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
	 * Converts the write activity counters to their gRPC form.
	 *
	 * @param activity the component to convert
	 * @return its gRPC form
	 */
	@Nonnull
	private static GrpcActivityStatistics toGrpcActivityStatistics(@Nonnull ActivityStatistics activity) {
		return GrpcActivityStatistics.newBuilder()
			.setTransactionsCommitted(activity.transactionsCommitted())
			.setTransactionsRolledBack(activity.transactionsRolledBack())
			.setTransactionsConflicted(activity.transactionsConflicted())
			.setMutationsApplied(activity.mutationsApplied())
			.setWalBytesAppended(activity.walBytesAppended())
			.setPipelineDepth(activity.pipelineDepth())
			.setTransactionsPerSecond(activity.transactionsPerSecond())
			.setMutationsPerSecond(activity.mutationsPerSecond())
			.setWalBytesPerSecond(activity.walBytesPerSecond())
			.setCountingSince(EvitaDataTypesConverter.toGrpcOffsetDateTime(activity.countingSince()))
			.build();
	}

	/**
	 * Reads the write activity counters back from the wire.
	 *
	 * @param grpcActivity the received component
	 * @return its Java form
	 */
	@Nonnull
	private static ActivityStatistics toActivityStatistics(@Nonnull GrpcActivityStatistics grpcActivity) {
		return new ActivityStatistics(
			grpcActivity.getTransactionsCommitted(),
			grpcActivity.getTransactionsRolledBack(),
			grpcActivity.getTransactionsConflicted(),
			grpcActivity.getMutationsApplied(),
			grpcActivity.getWalBytesAppended(),
			grpcActivity.getPipelineDepth(),
			grpcActivity.getTransactionsPerSecond(),
			grpcActivity.getMutationsPerSecond(),
			grpcActivity.getWalBytesPerSecond(),
			EvitaDataTypesConverter.toOffsetDateTime(grpcActivity.getCountingSince())
		);
	}

	/**
	 * Converts the deferred-durability fence readings to their gRPC form.
	 *
	 * @param durability the component to convert
	 * @return its gRPC form
	 */
	@Nonnull
	private static GrpcDurabilityStatistics toGrpcDurabilityStatistics(@Nonnull DurabilityStatistics durability) {
		final GrpcDurabilityStatistics.Builder builder = GrpcDurabilityStatistics.newBuilder()
			.setCheckpointIntervalMillis(durability.checkpointIntervalMillis())
			.setLastCadenceMillis(durability.lastCadenceMillis())
			.setLastFenceDepthMillis(durability.lastFenceDepthMillis())
			.setLastFilesForced(durability.lastFilesForced())
			.setLastForceDurationMillis(durability.lastForceDurationMillis())
			.setCheckpointsCompleted(durability.checkpointsCompleted())
			.setCountingSince(EvitaDataTypesConverter.toGrpcOffsetDateTime(durability.countingSince()));
		// left unset when no checkpoint has completed yet - an epoch-zero instant would read as a checkpoint taken in
		// 1970 rather than as one that has not happened
		if (durability.lastCheckpointAt() != null) {
			builder.setLastCheckpointAt(
				EvitaDataTypesConverter.toGrpcOffsetDateTime(durability.lastCheckpointAt())
			);
		}
		return builder.build();
	}

	/**
	 * Reads the deferred-durability fence readings back from the wire.
	 *
	 * @param grpcDurability the received component
	 * @return its Java form
	 */
	@Nonnull
	private static DurabilityStatistics toDurabilityStatistics(@Nonnull GrpcDurabilityStatistics grpcDurability) {
		return new DurabilityStatistics(
			grpcDurability.getCheckpointIntervalMillis(),
			grpcDurability.getLastCadenceMillis(),
			grpcDurability.getLastFenceDepthMillis(),
			grpcDurability.getLastFilesForced(),
			grpcDurability.getLastForceDurationMillis(),
			grpcDurability.getCheckpointsCompleted(),
			grpcDurability.hasLastCheckpointAt() ?
				EvitaDataTypesConverter.toOffsetDateTime(grpcDurability.getLastCheckpointAt()) : null,
			EvitaDataTypesConverter.toOffsetDateTime(grpcDurability.getCountingSince())
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
		final GrpcCollectionHeaderInfo.Builder builder = GrpcCollectionHeaderInfo.newBuilder()
			.setEntityTypePrimaryKey(header.entityTypePrimaryKey())
			.setVersion(header.version())
			.setLastPrimaryKey(header.lastPrimaryKey())
			.setLastEntityIndexPrimaryKey(header.lastEntityIndexPrimaryKey())
			.setLastInternalPriceId(header.lastInternalPriceId())
			.setLastKeyId(header.lastKeyId())
			.setMaxRecordSizeBytes(header.maxRecordSizeBytes());
		// left unset for a header written before 2026.3 - setting it unconditionally would send an epoch-zero instant
		// that a client cannot tell apart from a real timestamp
		if (header.lastModified() != null) {
			builder.setLastModified(EvitaDataTypesConverter.toGrpcOffsetDateTime(header.lastModified()));
		}
		return builder.build();
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
			grpcHeader.getMaxRecordSizeBytes(),
			grpcHeader.hasLastModified() ?
				EvitaDataTypesConverter.toOffsetDateTime(grpcHeader.getLastModified()) : null
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
		for (final IndexTypeCount typeCount : indexSummary.byTypeAndScope()) {
			builder.addByTypeAndScope(
				GrpcIndexTypeCount.newBuilder()
					.setIndexType(toGrpcEntityIndexType(typeCount.indexType()))
					.setScope(toGrpcScope(typeCount.scope()))
					.setCount(typeCount.count())
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
		final List<GrpcIndexTypeCount> grpcKindCounts = grpcIndexSummary.getByTypeAndScopeList();
		final IndexTypeCount[] typeCounts = new IndexTypeCount[grpcKindCounts.size()];
		for (int i = 0; i < typeCounts.length; i++) {
			final GrpcIndexTypeCount grpcKindCount = grpcKindCounts.get(i);
			typeCounts[i] = new IndexTypeCount(
				toEntityIndexType(grpcKindCount.getIndexType()),
				toScope(grpcKindCount.getScope()),
				grpcKindCount.getCount()
			);
		}
		return new CollectionIndexSummary(grpcIndexSummary.getTotalIndexCount(), typeCounts);
	}

	/**
	 * Converts the catalog-level index cardinality to its gRPC form.
	 *
	 * @param cardinality the component to convert
	 * @return the gRPC form of the component
	 */
	@Nonnull
	private static GrpcCatalogIndexCardinality toGrpcCatalogIndexCardinality(
		@Nonnull CatalogIndexCardinality cardinality
	) {
		final GrpcCatalogIndexCardinality.Builder builder = GrpcCatalogIndexCardinality.newBuilder();
		for (final GlobalUniqueIndexCardinality index : cardinality.globalUniqueIndexes()) {
			final GrpcGlobalUniqueIndexCardinality.Builder indexBuilder = GrpcGlobalUniqueIndexCardinality.newBuilder()
				.setAttributeName(index.attributeName())
				.setScope(toGrpcScope(index.scope()))
				.setDistinctValueCount(index.distinctValueCount());
			if (index.locale() != null) {
				indexBuilder.setLocale(EvitaDataTypesConverter.toGrpcLocale(index.locale()));
			}
			builder.addGlobalUniqueIndexes(indexBuilder.build());
		}
		return builder.build();
	}

	/**
	 * Converts the catalog-level index cardinality from its gRPC form.
	 *
	 * @param cardinality the gRPC form of the component
	 * @return the component
	 */
	@Nonnull
	private static CatalogIndexCardinality toCatalogIndexCardinality(
		@Nonnull GrpcCatalogIndexCardinality cardinality
	) {
		final List<GrpcGlobalUniqueIndexCardinality> indexes = cardinality.getGlobalUniqueIndexesList();
		final GlobalUniqueIndexCardinality[] result = new GlobalUniqueIndexCardinality[indexes.size()];
		for (int i = 0; i < result.length; i++) {
			final GrpcGlobalUniqueIndexCardinality index = indexes.get(i);
			result[i] = new GlobalUniqueIndexCardinality(
				index.getAttributeName(),
				index.hasLocale() ? EvitaDataTypesConverter.toLocale(index.getLocale()) : null,
				toScope(index.getScope()),
				index.getDistinctValueCount()
			);
		}
		return new CatalogIndexCardinality(result);
	}

	/**
	 * Converts one collection's index cardinality readings to their gRPC form.
	 *
	 * @param indexCardinality the component to convert
	 * @return its gRPC form, with the discriminator left unset for the global index and the reference cardinality
	 * left unset for every index that tracks no references
	 */
	@Nonnull
	private static GrpcCollectionIndexCardinality toGrpcCollectionIndexCardinality(
		@Nonnull CollectionIndexCardinality indexCardinality
	) {
		final GrpcCollectionIndexCardinality.Builder builder = GrpcCollectionIndexCardinality.newBuilder()
			.setOmittedIndexCount(indexCardinality.omittedIndexCount());
		for (final IndexCardinality index : indexCardinality.indexes()) {
			builder.addIndexes(toGrpcIndexCardinality(index));
		}
		return builder.build();
	}

	/**
	 * Converts the cardinality readings of one index to their gRPC form.
	 *
	 * Shared by the collection-level component and by the single-index detail, so a client reading the same index
	 * through either route is told the same thing in the same shape.
	 *
	 * @param index the readings to convert
	 * @return their gRPC form, with the discriminator left unset for the global index and the reference cardinality
	 * left unset for an index that tracks no references
	 */
	@Nonnull
	public static GrpcIndexCardinality toGrpcIndexCardinality(@Nonnull IndexCardinality index) {
		final GrpcIndexCardinality.Builder indexBuilder = GrpcIndexCardinality.newBuilder()
			.setScope(toGrpcScope(index.scope()));
		// left unset rather than defaulted for a catalog index, which has neither a kind nor a primary-key bitmap -
		// `INDEX_TYPE_UNSPECIFIED` and `0` are the readings of a default-constructed message, not of such an index
		if (index.indexType() != null) {
			indexBuilder.setIndexType(toGrpcEntityIndexType(index.indexType()));
		}
		if (index.entityCount() != null) {
			indexBuilder.setEntityCount(Int32Value.of(index.entityCount()));
		}
		if (index.discriminator() != null) {
			indexBuilder.setDiscriminator(StringValue.of(index.discriminator()));
		}
		if (index.referencedEntityCount() != null) {
			indexBuilder.setReferencedEntityCount(Int32Value.of(index.referencedEntityCount()));
		}
		for (final AttributeCardinality attribute : index.attributes()) {
			indexBuilder.addAttributes(toGrpcAttributeCardinality(attribute));
		}
		return indexBuilder.build();
	}

	/**
	 * Converts the full description of one index to its gRPC form.
	 *
	 * @param detail the description to convert
	 * @return its gRPC form, with the entity type left unset for an index the catalog holds itself
	 */
	@Nonnull
	public static GrpcIndexDetail toGrpcIndexDetail(
		@Nonnull IndexDetail detail
	) {
		final GrpcIndexDetail.Builder builder = GrpcIndexDetail.newBuilder()
			.setIndexPrimaryKey(detail.indexPrimaryKey())
			.setHeapSizeInBytes(detail.heapSizeInBytes())
			.setCardinality(toGrpcIndexCardinality(detail.cardinality()))
			.setQueryCount(detail.queryCount())
			.setUpdateCount(detail.updateCount())
			// stamped explicitly rather than left to the field default, which is `false` - a tracking server that
			// forgot to set this would report every index as unmeasured and hide the whole surface
			.setMeasured(detail.measured());
		if (detail.entityType() != null) {
			builder.setEntityType(StringValue.of(detail.entityType()));
		}
		// the stamps are message-typed rather than a zero sentinel, so "never since the catalog was loaded" is
		// expressible - an epoch-zero instant would render as a date in 1970
		if (detail.lastQueriedAt() != null) {
			builder.setLastQueriedAt(EvitaDataTypesConverter.toGrpcOffsetDateTime(detail.lastQueriedAt()));
		}
		if (detail.lastUpdatedAt() != null) {
			builder.setLastUpdatedAt(EvitaDataTypesConverter.toGrpcOffsetDateTime(detail.lastUpdatedAt()));
		}
		// null only on a description that itself came from a pre-observedSince server - the absence travels onward
		// rather than being replaced by a fabricated instant
		if (detail.observedSince() != null) {
			builder.setObservedSince(EvitaDataTypesConverter.toGrpcOffsetDateTime(detail.observedSince()));
		}
		return builder.build();
	}

	/**
	 * Reads the full description of one index back from the wire.
	 *
	 * @param grpcDetail the received description
	 * @return its Java form, with every unset optional field left null
	 */
	@Nonnull
	public static IndexDetail toIndexDetail(
		@Nonnull GrpcIndexDetail grpcDetail
	) {
		return new IndexDetail(
			grpcDetail.hasEntityType() ? grpcDetail.getEntityType().getValue() : null,
			grpcDetail.getIndexPrimaryKey(),
			grpcDetail.getHeapSizeInBytes(),
			toIndexCardinality(grpcDetail.getCardinality()),
			grpcDetail.getQueryCount(),
			grpcDetail.getUpdateCount(),
			grpcDetail.hasLastQueriedAt() ?
				EvitaDataTypesConverter.toOffsetDateTime(grpcDetail.getLastQueriedAt()) : null,
			grpcDetail.hasLastUpdatedAt() ?
				EvitaDataTypesConverter.toOffsetDateTime(grpcDetail.getLastUpdatedAt()) : null,
			// a server predating the field sends nothing, and nothing may stand in for the missing window: any
			// substituted instant fabricates one - the epoch a decades-long window that turns "never queried in the
			// last week" falsely true, "now" a zero-length one that turns every rate infinite. Absence is the truth
			grpcDetail.hasObservedSince() ?
				EvitaDataTypesConverter.toOffsetDateTime(grpcDetail.getObservedSince()) : null
		,
			grpcDetail.getMeasured()
		);
	}

	/**
	 * Converts the readings of one attribute index to their gRPC form.
	 *
	 * @param attribute the readings to convert
	 * @return their gRPC form, with the reference name left unset for an entity-level attribute and the locale left
	 * unset for one that is not localized
	 */
	@Nonnull
	private static GrpcAttributeCardinality toGrpcAttributeCardinality(@Nonnull AttributeCardinality attribute) {
		final GrpcAttributeCardinality.Builder builder = GrpcAttributeCardinality.newBuilder()
			.setAttributeName(attribute.attributeName())
			.setIndexType(toGrpcAttributeIndexType(attribute.indexType()))
			.setDistinctValueCount(attribute.distinctValueCount())
			.setRecordsCovered(attribute.recordsCovered());
		if (attribute.referenceName() != null) {
			builder.setReferenceName(StringValue.of(attribute.referenceName()));
		}
		if (attribute.locale() != null) {
			builder.setLocale(EvitaDataTypesConverter.toGrpcLocale(attribute.locale()));
		}
		return builder.build();
	}

	/**
	 * Reads one collection's index cardinality readings back from the wire.
	 *
	 * @param grpcIndexCardinality the received component
	 * @return its Java form, with every unset optional field left null
	 */
	@Nonnull
	private static CollectionIndexCardinality toCollectionIndexCardinality(
		@Nonnull GrpcCollectionIndexCardinality grpcIndexCardinality
	) {
		final List<GrpcIndexCardinality> grpcIndexes = grpcIndexCardinality.getIndexesList();
		final IndexCardinality[] indexes = new IndexCardinality[grpcIndexes.size()];
		for (int i = 0; i < indexes.length; i++) {
			indexes[i] = toIndexCardinality(grpcIndexes.get(i));
		}
		return new CollectionIndexCardinality(indexes, grpcIndexCardinality.getOmittedIndexCount());
	}

	/**
	 * Reads the cardinality readings of one index back from the wire.
	 *
	 * @param grpcIndex the received readings
	 * @return their Java form, with every unset optional field left null
	 */
	@Nonnull
	public static IndexCardinality toIndexCardinality(@Nonnull GrpcIndexCardinality grpcIndex) {
		final List<GrpcAttributeCardinality> grpcAttributes = grpcIndex.getAttributesList();
		final AttributeCardinality[] attributes = new AttributeCardinality[grpcAttributes.size()];
		for (int j = 0; j < attributes.length; j++) {
			attributes[j] = toAttributeCardinality(grpcAttributes.get(j));
		}
		return new IndexCardinality(
			grpcIndex.hasIndexType() ? toEntityIndexType(grpcIndex.getIndexType()) : null,
			toScope(grpcIndex.getScope()),
			grpcIndex.hasDiscriminator() ? grpcIndex.getDiscriminator().getValue() : null,
			grpcIndex.hasEntityCount() ? grpcIndex.getEntityCount().getValue() : null,
			grpcIndex.hasReferencedEntityCount() ? grpcIndex.getReferencedEntityCount().getValue() : null,
			attributes
		);
	}

	/**
	 * Reads the readings of one attribute index back from the wire.
	 *
	 * @param grpcAttribute the received readings
	 * @return their Java form, with every unset optional field left null
	 */
	@Nonnull
	private static AttributeCardinality toAttributeCardinality(@Nonnull GrpcAttributeCardinality grpcAttribute) {
		return new AttributeCardinality(
			grpcAttribute.getAttributeName(),
			grpcAttribute.hasReferenceName() ? grpcAttribute.getReferenceName().getValue() : null,
			grpcAttribute.hasLocale() ? EvitaDataTypesConverter.toLocale(grpcAttribute.getLocale()) : null,
			toAttributeIndexType(grpcAttribute.getIndexType()),
			grpcAttribute.getDistinctValueCount(),
			grpcAttribute.getRecordsCovered()
		);
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

	/**
	 * Converts one browsed index descriptor to its gRPC form.
	 *
	 * @param index the descriptor to convert
	 * @return its gRPC form, with the reference name left unset for a global index, the discriminator primary key
	 * left unset for an index covering a whole reference type, and the entity type, kind and entity count all left
	 * unset for an index the catalog holds itself
	 */
	@Nonnull
	public static GrpcBrowsedIndex toGrpcBrowsedIndex(@Nonnull BrowsedIndex index) {
		final GrpcBrowsedIndex.Builder builder = GrpcBrowsedIndex.newBuilder()
			.setIndexPrimaryKey(index.indexPrimaryKey())
			.setScope(toGrpcScope(index.scope()))
			.setQueryCount(index.queryCount())
			.setUpdateCount(index.updateCount())
			// see `toGrpcIndexDetail` for why this is never left to the field default
			.setMeasured(index.measured());
		// null only on a row that itself came from a pre-observedSince server - the absence travels onward rather
		// than being replaced by a fabricated instant
		if (index.observedSince() != null) {
			builder.setObservedSince(EvitaDataTypesConverter.toGrpcOffsetDateTime(index.observedSince()));
		}
		if (index.entityType() != null) {
			builder.setEntityType(StringValue.of(index.entityType()));
		}
		// see `toGrpcIndexCardinality` - a catalog index leaves both unset rather than carrying a default that would be
		// indistinguishable from one
		if (index.indexType() != null) {
			builder.setIndexType(toGrpcEntityIndexType(index.indexType()));
		}
		if (index.entityCount() != null) {
			builder.setEntityCount(Int32Value.of(index.entityCount()));
		}
		if (index.discriminator() != null) {
			builder.setDiscriminator(StringValue.of(index.discriminator()));
		}
		if (index.referenceName() != null) {
			builder.setReferenceName(StringValue.of(index.referenceName()));
		}
		if (index.discriminatorPrimaryKey() != null) {
			builder.setDiscriminatorPrimaryKey(Int32Value.of(index.discriminatorPrimaryKey()));
		}
		// the stamps are message-typed rather than a zero sentinel, so "never since the catalog was loaded" is
		// expressible - an epoch-zero instant would render as a date in 1970
		if (index.lastQueriedAt() != null) {
			builder.setLastQueriedAt(EvitaDataTypesConverter.toGrpcOffsetDateTime(index.lastQueriedAt()));
		}
		if (index.lastUpdatedAt() != null) {
			builder.setLastUpdatedAt(EvitaDataTypesConverter.toGrpcOffsetDateTime(index.lastUpdatedAt()));
		}
		return builder.build();
	}

	/**
	 * Reads one browsed index descriptor back from the wire.
	 *
	 * @param grpcIndex the received descriptor
	 * @return its Java form, with every unset optional field left null
	 */
	@Nonnull
	public static BrowsedIndex toBrowsedIndex(@Nonnull GrpcBrowsedIndex grpcIndex) {
		return new BrowsedIndex(
			grpcIndex.hasEntityType() ? grpcIndex.getEntityType().getValue() : null,
			grpcIndex.getIndexPrimaryKey(),
			grpcIndex.hasIndexType() ? toEntityIndexType(grpcIndex.getIndexType()) : null,
			toScope(grpcIndex.getScope()),
			grpcIndex.hasDiscriminator() ? grpcIndex.getDiscriminator().getValue() : null,
			grpcIndex.hasReferenceName() ? grpcIndex.getReferenceName().getValue() : null,
			grpcIndex.hasDiscriminatorPrimaryKey() ? grpcIndex.getDiscriminatorPrimaryKey().getValue() : null,
			grpcIndex.hasEntityCount() ? grpcIndex.getEntityCount().getValue() : null,
			grpcIndex.getQueryCount(),
			grpcIndex.getUpdateCount(),
			grpcIndex.hasLastQueriedAt() ?
				EvitaDataTypesConverter.toOffsetDateTime(grpcIndex.getLastQueriedAt()) : null,
			grpcIndex.hasLastUpdatedAt() ?
				EvitaDataTypesConverter.toOffsetDateTime(grpcIndex.getLastUpdatedAt()) : null,
			// see toIndexDetail for why an old server's silence decodes to an absence rather than to any instant
			grpcIndex.hasObservedSince() ?
				EvitaDataTypesConverter.toOffsetDateTime(grpcIndex.getObservedSince()) : null
,
			grpcIndex.getMeasured()
		);
	}

	/**
	 * Reads a whole page of browsed indexes back from the wire.
	 *
	 * @param grpcResponse the received page
	 * @return its Java form
	 */
	@Nonnull
	public static IndexBrowseResult toIndexBrowseResult(
		@Nonnull GrpcIndexBrowseResponse grpcResponse
	) {
		final List<GrpcBrowsedIndex> grpcIndexes = grpcResponse.getIndexesList();
		final BrowsedIndex[] indexes = new BrowsedIndex[grpcIndexes.size()];
		for (int i = 0; i < indexes.length; i++) {
			indexes[i] = toBrowsedIndex(grpcIndexes.get(i));
		}
		return new IndexBrowseResult(
			grpcResponse.getCatalogVersion(),
			grpcResponse.getPageNumber(),
			grpcResponse.getPageSize(),
			grpcResponse.getTotalRecordCount(),
			indexes
		);
	}

	/**
	 * Converts an index browse request to its gRPC form.
	 *
	 * @param catalogName name of the catalog holding the indexes
	 * @param entityType  name of the entity collection whose indexes to browse, or null to browse the indexes the
	 *                    catalog holds itself
	 * @param criteria    the selection, ordering and paging to send
	 * @return the request as it goes on the wire
	 */
	@Nonnull
	public static GrpcIndexBrowseRequest toGrpcIndexBrowseRequest(
		@Nonnull String catalogName,
		@Nullable String entityType,
		@Nonnull IndexBrowseCriteria criteria
	) {
		final GrpcIndexBrowseRequest.Builder builder =
			GrpcIndexBrowseRequest.newBuilder()
				.setCatalogName(catalogName)
				.setPageNumber(criteria.pageNumber())
				.setPageSize(criteria.pageSize())
				.setOrdering(toGrpcIndexBrowseOrdering(criteria.ordering()))
				.setDirection(toGrpcOrderDirection(criteria.direction()))
				.addAllReferenceNames(criteria.referenceNames());
		for (final EntityIndexType indexType : criteria.indexTypes()) {
			builder.addIndexTypes(toGrpcEntityIndexType(indexType));
		}
		for (final Scope scope : criteria.scopes()) {
			builder.addScopes(toGrpcScope(scope));
		}
		// unset selects the catalog's own indexes; an empty string would be a collection name that cannot exist, which
		// is why absence is carried by a wrapper rather than by a sentinel value
		if (entityType != null) {
			builder.setEntityType(StringValue.of(entityType));
		}
		return builder.build();
	}

	/**
	 * Reads the selection, ordering and paging of an index browse request off the wire.
	 *
	 * An empty repeated filter means that category does not filter, so it maps to an empty set rather than to "every
	 * value" - the two are equivalent in effect, and the empty set is what the criteria document.
	 *
	 * The ordering key and its direction are read as the two fields they are, and the pair is validated by the
	 * criteria rather than here - `MAP_ORDER` with `DESC` is the one combination that does not exist, and it is
	 * rejected in exactly one place so that an embedded caller and a remote one are told the same thing.
	 *
	 * @param grpcRequest the received request
	 * @return its Java form
	 * @throws EvitaInvalidUsageException when the ordering is unknown, the key and direction do not pair, or the
	 *                                    paging is out of range
	 */
	@Nonnull
	public static IndexBrowseCriteria toIndexBrowseCriteria(
		@Nonnull GrpcIndexBrowseRequest grpcRequest
	) {
		final Set<EntityIndexType> indexTypes = EnumSet.noneOf(EntityIndexType.class);
		for (final GrpcEntityIndexType grpcIndexType : grpcRequest.getIndexTypesList()) {
			indexTypes.add(toEntityIndexType(grpcIndexType));
		}
		final Set<Scope> scopes = EnumSet.noneOf(Scope.class);
		for (final GrpcEntityScope grpcScope : grpcRequest.getScopesList()) {
			scopes.add(toScope(grpcScope));
		}
		return new IndexBrowseCriteria(
			grpcRequest.getPageNumber(),
			grpcRequest.getPageSize(),
			toIndexBrowseOrdering(grpcRequest.getOrdering()),
			toOrderDirection(grpcRequest.getDirection()),
			indexTypes,
			scopes,
			new HashSet<>(grpcRequest.getReferenceNamesList())
		);
	}

	/**
	 * Converts one schema-capability usage row to its gRPC form.
	 *
	 * @param usage the row to convert
	 * @return its gRPC form, with the entity type left unset for a row the catalog owns and the container name left
	 * unset for an element its owner declares directly
	 */
	@Nonnull
	public static GrpcSchemaCapabilityUsage toGrpcSchemaCapabilityUsage(
		@Nonnull SchemaCapabilityUsageStatistics usage
	) {
		final GrpcSchemaCapabilityUsage.Builder builder = GrpcSchemaCapabilityUsage.newBuilder()
			.setElementKind(toGrpcSchemaElementKind(usage.elementKind()))
			.setElementName(usage.elementName())
			.setCapability(toGrpcSchemaCapability(usage.capability()))
			.setScope(toGrpcScope(usage.scope()))
			.setRequestedCount(usage.requestedCount())
			.setUpdatedCount(usage.updatedCount())
			// never absent - a capability is observed from the moment it exists, which is what makes a zero count
			// readable as "not once in this long" rather than as an unqualified zero
			.setObservedSince(EvitaDataTypesConverter.toGrpcOffsetDateTime(usage.observedSince()))
			// see `toGrpcIndexDetail` for why this is never left to the field default
			.setMeasured(usage.measured());
		// both absences are statements about the owner, and an unset wrapper is what keeps them apart from an owner
		// genuinely named by the empty string
		if (usage.entityType() != null) {
			builder.setEntityType(StringValue.of(usage.entityType()));
		}
		if (usage.containerName() != null) {
			builder.setContainerName(StringValue.of(usage.containerName()));
		}
		// the stamps are message-typed rather than a zero sentinel, so "never since the catalog was loaded" is
		// expressible - an epoch-zero instant would render as a date in 1970
		if (usage.lastRequestedAt() != null) {
			builder.setLastRequestedAt(EvitaDataTypesConverter.toGrpcOffsetDateTime(usage.lastRequestedAt()));
		}
		if (usage.lastUpdatedAt() != null) {
			builder.setLastUpdatedAt(EvitaDataTypesConverter.toGrpcOffsetDateTime(usage.lastUpdatedAt()));
		}
		return builder.build();
	}

	/**
	 * Reads one schema-capability usage row back from the wire.
	 *
	 * @param grpcUsage the received row
	 * @return its Java form, with every unset optional field left null
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the observation window is missing - see below
	 */
	@Nonnull
	public static SchemaCapabilityUsageStatistics toSchemaCapabilityUsage(
		@Nonnull GrpcSchemaCapabilityUsage grpcUsage
	) {
		// unlike the per-index rows, whose observation window was added to an existing message and is therefore absent
		// from an older server's answer, this whole procedure is new: any server able to answer it at all sets the
		// window. A missing one is a broken sender rather than an old one, and it is rejected rather than substituted,
		// because no instant can stand in for an unknown window - the epoch would fabricate a decades-long one that
		// turns "never requested in the last week" falsely true, "now" a zero-length one that turns every rate infinite
		Assert.isPremiseValid(
			grpcUsage.hasObservedSince(),
			() -> "Schema capability usage of `" + grpcUsage.getElementName() + "` arrived without its observation " +
				"window, which no instant may stand in for."
		);
		return new SchemaCapabilityUsageStatistics(
			grpcUsage.hasEntityType() ? grpcUsage.getEntityType().getValue() : null,
			toSchemaElementKind(grpcUsage.getElementKind()),
			grpcUsage.hasContainerName() ? grpcUsage.getContainerName().getValue() : null,
			grpcUsage.getElementName(),
			toSchemaCapability(grpcUsage.getCapability()),
			toScope(grpcUsage.getScope()),
			grpcUsage.getRequestedCount(),
			grpcUsage.getUpdatedCount(),
			grpcUsage.hasLastRequestedAt() ?
				EvitaDataTypesConverter.toOffsetDateTime(grpcUsage.getLastRequestedAt()) : null,
			grpcUsage.hasLastUpdatedAt() ?
				EvitaDataTypesConverter.toOffsetDateTime(grpcUsage.getLastUpdatedAt()) : null,
			EvitaDataTypesConverter.toOffsetDateTime(grpcUsage.getObservedSince()),
			grpcUsage.getMeasured()
		);
	}

	/**
	 * Reads a whole schema-capability usage listing back from the wire, keeping the order the server sent it in.
	 *
	 * @param grpcResponse the received listing
	 * @return its Java form
	 */
	@Nonnull
	public static List<SchemaCapabilityUsageStatistics> toSchemaCapabilityUsages(
		@Nonnull GrpcSchemaCapabilityUsageResponse grpcResponse
	) {
		final List<GrpcSchemaCapabilityUsage> grpcUsages = grpcResponse.getCapabilitiesList();
		final List<SchemaCapabilityUsageStatistics> usages = new ArrayList<>(grpcUsages.size());
		for (int i = 0; i < grpcUsages.size(); i++) {
			usages.add(toSchemaCapabilityUsage(grpcUsages.get(i)));
		}
		return usages;
	}

}
