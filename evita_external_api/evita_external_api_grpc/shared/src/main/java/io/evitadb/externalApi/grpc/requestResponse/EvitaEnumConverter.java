/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.CatalogState;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.observability.HealthProblem;
import io.evitadb.api.observability.ReadinessState;
import io.evitadb.api.query.filter.AttributeSpecialValue;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.TraversalMode;
import io.evitadb.api.query.require.*;
import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest.TrafficRecordingType;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingContent;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.api.task.TaskStatus.TaskTrait;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.dataType.ContainerType;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.utils.NamingConvention;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

import static io.evitadb.externalApi.grpc.generated.GrpcTaskSimplifiedState.*;
import static io.evitadb.externalApi.grpc.generated.GrpcTrafficRecordingContent.TRAFFIC_RECORDING_BODY;
import static io.evitadb.externalApi.grpc.generated.GrpcTrafficRecordingContent.TRAFFIC_RECORDING_HEADER;
import static java.util.Optional.empty;
import static java.util.Optional.of;

/**
 * Class contains static methods for converting enums from and to gRPC representation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class EvitaEnumConverter {

	/**
	 * Converts {@link GrpcCatalogState} to {@link CatalogState}.
	 */
	@Nonnull
	public static CatalogState toCatalogState(@Nonnull GrpcCatalogState grpcCatalogState) {
		return switch (grpcCatalogState.getNumber()) {
			case 0 -> CatalogState.WARMING_UP;
			case 1 -> CatalogState.ALIVE;
			case 3 -> CatalogState.CORRUPTED;
			case 4 -> CatalogState.INACTIVE;
			case 5 -> CatalogState.GOING_ALIVE;
			case 6 -> CatalogState.BEING_ACTIVATED;
			case 7 -> CatalogState.BEING_DEACTIVATED;
			case 8 -> CatalogState.BEING_CREATED;
			case 9 -> CatalogState.BEING_DELETED;
			default -> throw new GenericEvitaInternalError("Unrecognized remote catalog state: " + grpcCatalogState);
		};
	}

	/**
	 * Converts {@link CatalogState} to {@link GrpcCatalogState}.
	 */
	@Nonnull
	public static GrpcCatalogState toGrpcCatalogState(@Nullable CatalogState catalogState) {
		if (catalogState == null) {
			return GrpcCatalogState.UNKNOWN_CATALOG_STATE;
		}
		return switch (catalogState) {
			case WARMING_UP -> GrpcCatalogState.WARMING_UP;
			case ALIVE -> GrpcCatalogState.ALIVE;
			case INACTIVE -> GrpcCatalogState.INACTIVE;
			case CORRUPTED -> GrpcCatalogState.CORRUPTED;
			case GOING_ALIVE -> GrpcCatalogState.GOING_ALIVE;
			case BEING_ACTIVATED -> GrpcCatalogState.BEING_ACTIVATED;
			case BEING_DEACTIVATED -> GrpcCatalogState.BEING_DEACTIVATED;
			case BEING_DELETED -> GrpcCatalogState.BEING_DELETED;
			case BEING_CREATED -> GrpcCatalogState.BEING_CREATED;
		};
	}

	/**
	 * Converts {@link GrpcQueryPriceMode} to {@link QueryPriceMode}.
	 *
	 * @param grpcQueryPriceMode the {@link GrpcQueryPriceMode} to be converted
	 * @return the converted {@link QueryPriceMode}
	 * @throws EvitaInternalError if the remote query price mode is unrecognized
	 */
	@Nonnull
	public static QueryPriceMode toQueryPriceMode(@Nonnull GrpcQueryPriceMode grpcQueryPriceMode) {
		return switch (grpcQueryPriceMode.getNumber()) {
			case 0 -> QueryPriceMode.WITH_TAX;
			case 1 -> QueryPriceMode.WITHOUT_TAX;
			default ->
				throw new GenericEvitaInternalError("Unrecognized remote query price mode: " + grpcQueryPriceMode);
		};
	}

	/**
	 * Converts {@link QueryPriceMode} to {@link GrpcQueryPriceMode}.
	 *
	 * @param queryPriceMode the {@link QueryPriceMode} to be converted
	 * @return the converted {@link GrpcQueryPriceMode}
	 */
	@Nonnull
	public static GrpcQueryPriceMode toGrpcQueryPriceMode(@Nonnull QueryPriceMode queryPriceMode) {
		return switch (queryPriceMode) {
			case WITH_TAX -> GrpcQueryPriceMode.WITH_TAX;
			case WITHOUT_TAX -> GrpcQueryPriceMode.WITHOUT_TAX;
		};
	}

	/**
	 * Converts {@link GrpcPriceContentMode} to {@link PriceContentMode}.
	 *
	 * @param grpcPriceContentMode the {@link GrpcPriceContentMode} to be converted
	 * @return the converted {@link PriceContentMode}
	 * @throws EvitaInternalError if an unrecognized remote price content mode is encountered
	 */
	@Nonnull
	public static PriceContentMode toPriceContentMode(@Nonnull GrpcPriceContentMode grpcPriceContentMode) {
		return switch (grpcPriceContentMode.getNumber()) {
			case 0 -> PriceContentMode.NONE;
			case 1 -> PriceContentMode.RESPECTING_FILTER;
			case 2 -> PriceContentMode.ALL;
			default ->
				throw new GenericEvitaInternalError("Unrecognized remote price content mode: " + grpcPriceContentMode);
		};
	}

	/**
	 * Converts {@link PriceContentMode} to {@link GrpcPriceContentMode}.
	 *
	 * @param priceContentMode the {@link PriceContentMode} to be converted
	 * @return the converted {@link GrpcPriceContentMode}
	 */
	@Nonnull
	public static GrpcPriceContentMode toGrpcPriceContentMode(@Nonnull PriceContentMode priceContentMode) {
		return switch (priceContentMode) {
			case NONE -> GrpcPriceContentMode.FETCH_NONE;
			case RESPECTING_FILTER -> GrpcPriceContentMode.RESPECTING_FILTER;
			case ALL -> GrpcPriceContentMode.ALL;
		};
	}

	/**
	 * Converts {@link GrpcOrderDirection} to {@link OrderDirection}.
	 *
	 * @param grpcOrderDirection the {@link GrpcOrderDirection} to be converted
	 * @return the converted {@link OrderDirection}
	 * @throws EvitaInternalError if the remote order direction is unrecognized
	 */
	@Nonnull
	public static OrderDirection toOrderDirection(@Nonnull GrpcOrderDirection grpcOrderDirection) {
		return switch (grpcOrderDirection.getNumber()) {
			case 0 -> OrderDirection.ASC;
			case 1 -> OrderDirection.DESC;
			default ->
				throw new GenericEvitaInternalError("Unrecognized remote order direction: " + grpcOrderDirection);
		};
	}

	/**
	 * Converts {@link OrderDirection} to {@link GrpcOrderDirection}.
	 *
	 * @param orderDirection the {@link OrderDirection} to be converted
	 * @return the converted {@link GrpcOrderDirection}
	 */
	@Nonnull
	public static GrpcOrderDirection toGrpcOrderDirection(@Nonnull OrderDirection orderDirection) {
		return switch (orderDirection) {
			case ASC -> GrpcOrderDirection.ASC;
			case DESC -> GrpcOrderDirection.DESC;
		};
	}

	/**
	 * Converts {@link GrpcOrderBehaviour} to {@link OrderBehaviour}.
	 *
	 * @param grpcOrderBehaviour the {@link GrpcOrderBehaviour} to be converted
	 * @return the converted {@link OrderBehaviour}
	 * @throws EvitaInternalError if the remote order behaviour is unrecognized
	 */
	@Nonnull
	public static OrderBehaviour toOrderBehaviour(@Nonnull GrpcOrderBehaviour grpcOrderBehaviour) {
		return switch (grpcOrderBehaviour.getNumber()) {
			case 0 -> OrderBehaviour.NULLS_FIRST;
			case 1 -> OrderBehaviour.NULLS_LAST;
			default ->
				throw new GenericEvitaInternalError("Unrecognized remote order behaviour: " + grpcOrderBehaviour);
		};
	}

	/**
	 * Converts {@link OrderBehaviour} to {@link GrpcOrderBehaviour}.
	 *
	 * @param orderBehaviour the {@link OrderBehaviour} to be converted
	 * @return the converted {@link GrpcOrderBehaviour}
	 * @throws IllegalArgumentException if the order behaviour is unrecognized
	 */
	@Nonnull
	public static GrpcOrderBehaviour toGrpcOrderBehaviour(@Nonnull OrderBehaviour orderBehaviour) {
		return switch (orderBehaviour) {
			case NULLS_FIRST -> GrpcOrderBehaviour.NULLS_FIRST;
			case NULLS_LAST -> GrpcOrderBehaviour.NULLS_LAST;
		};
	}

	/**
	 * Converts {@link GrpcEmptyHierarchicalEntityBehaviour} to {@link EmptyHierarchicalEntityBehaviour}.
	 *
	 * @param grpcEmptyHierarchicalEntityBehaviour the {@link GrpcEmptyHierarchicalEntityBehaviour} to be converted
	 * @return the converted {@link EmptyHierarchicalEntityBehaviour}
	 * @throws EvitaInternalError if the remote empty hierarchical entity behaviour is unrecognized
	 */
	@Nonnull
	public static EmptyHierarchicalEntityBehaviour toEmptyHierarchicalEntityBehaviour(@Nonnull GrpcEmptyHierarchicalEntityBehaviour grpcEmptyHierarchicalEntityBehaviour) {
		return switch (grpcEmptyHierarchicalEntityBehaviour.getNumber()) {
			case 0 -> EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY;
			case 1 -> EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY;
			default ->
				throw new GenericEvitaInternalError("Unrecognized remote empty hierarchical entity behaviour: " + grpcEmptyHierarchicalEntityBehaviour);
		};
	}

	/**
	 * Converts {@link EmptyHierarchicalEntityBehaviour} to {@link GrpcEmptyHierarchicalEntityBehaviour}.
	 *
	 * @param emptyHierarchicalEntityBehaviour the {@link EmptyHierarchicalEntityBehaviour} to be converted
	 * @return the converted {@link GrpcEmptyHierarchicalEntityBehaviour}
	 */
	@Nonnull
	public static GrpcEmptyHierarchicalEntityBehaviour toGrpcEmptyHierarchicalEntityBehaviour(@Nonnull EmptyHierarchicalEntityBehaviour emptyHierarchicalEntityBehaviour) {
		return switch (emptyHierarchicalEntityBehaviour) {
			case LEAVE_EMPTY -> GrpcEmptyHierarchicalEntityBehaviour.LEAVE_EMPTY;
			case REMOVE_EMPTY -> GrpcEmptyHierarchicalEntityBehaviour.REMOVE_EMPTY;
		};
	}

	/**
	 * Converts {@link GrpcStatisticsBase} to {@link StatisticsBase}.
	 *
	 * @param grpcStatisticsBase the {@link GrpcStatisticsBase} to be converted
	 * @return the converted {@link StatisticsBase}
	 * @throws EvitaInternalError if the remote statistics base is unrecognized
	 */
	@Nonnull
	public static StatisticsBase toStatisticsBase(@Nonnull GrpcStatisticsBase grpcStatisticsBase) {
		return switch (grpcStatisticsBase.getNumber()) {
			case 0 -> StatisticsBase.COMPLETE_FILTER;
			case 1 -> StatisticsBase.WITHOUT_USER_FILTER;
			case 2 -> StatisticsBase.COMPLETE_FILTER_EXCLUDING_SELF_IN_USER_FILTER;
			default ->
				throw new GenericEvitaInternalError("Unrecognized remote statistics base: " + grpcStatisticsBase);
		};
	}

	/**
	 * Converts {@link StatisticsBase} to {@link GrpcStatisticsBase}.
	 *
	 * @param statisticsBase the {@link StatisticsBase} to be converted
	 * @return the converted {@link GrpcStatisticsBase}
	 */
	@Nonnull
	public static GrpcStatisticsBase toGrpcStatisticsBase(@Nonnull StatisticsBase statisticsBase) {
		return switch (statisticsBase) {
			case COMPLETE_FILTER -> GrpcStatisticsBase.COMPLETE_FILTER;
			case WITHOUT_USER_FILTER -> GrpcStatisticsBase.WITHOUT_USER_FILTER;
			case COMPLETE_FILTER_EXCLUDING_SELF_IN_USER_FILTER -> GrpcStatisticsBase.COMPLETE_FILTER_EXCLUDING_SELF_IN_USER_FILTER;
		};
	}

	/**
	 * Converts {@link GrpcStatisticsType} to {@link StatisticsType}.
	 *
	 * @param grpcStatisticsType the {@link GrpcStatisticsType} to be converted
	 * @return the converted {@link StatisticsType}
	 * @throws EvitaInternalError if the given grpcStatisticsType is unrecognized
	 */
	@Nonnull
	public static StatisticsType toStatisticsType(@Nonnull GrpcStatisticsType grpcStatisticsType) {
		return switch (grpcStatisticsType.getNumber()) {
			case 0 -> StatisticsType.CHILDREN_COUNT;
			case 1 -> StatisticsType.QUERIED_ENTITY_COUNT;
			default ->
				throw new GenericEvitaInternalError("Unrecognized remote statistics type: " + grpcStatisticsType);
		};
	}

	/**
	 * Converts {@link StatisticsType} to {@link GrpcStatisticsType}.
	 *
	 * @param statisticsType the {@link StatisticsType} to be converted
	 * @return the converted {@link GrpcStatisticsType}
	 */
	@Nonnull
	public static GrpcStatisticsType toGrpcStatisticsType(@Nonnull StatisticsType statisticsType) {
		return switch (statisticsType) {
			case CHILDREN_COUNT -> GrpcStatisticsType.CHILDREN_COUNT;
			case QUERIED_ENTITY_COUNT -> GrpcStatisticsType.QUERIED_ENTITY_COUNT;
		};
	}

	/**
	 * Converts {@link GrpcHistogramBehavior} to {@link HistogramBehavior}.
	 *
	 * @param grpcHistogramBehavior the {@link GrpcHistogramBehavior} to be converted
	 * @return the converted {@link HistogramBehavior}
	 * @throws EvitaInternalError if the given grpcHistogramBehavior is unrecognized
	 */
	@Nonnull
	public static HistogramBehavior toHistogramBehavior(@Nonnull GrpcHistogramBehavior grpcHistogramBehavior) {
		return switch (grpcHistogramBehavior.getNumber()) {
			case 0 -> HistogramBehavior.STANDARD;
			case 1 -> HistogramBehavior.OPTIMIZED;
			default ->
				throw new GenericEvitaInternalError("Unrecognized remote histogram behavior: " + grpcHistogramBehavior);
		};
	}

	/**
	 * Converts {@link HistogramBehavior} to {@link GrpcHistogramBehavior}.
	 *
	 * @param histogramBehavior the {@link HistogramBehavior} to be converted
	 * @return the converted {@link GrpcHistogramBehavior}
	 */
	@Nonnull
	public static GrpcHistogramBehavior toGrpcHistogramBehavior(@Nonnull HistogramBehavior histogramBehavior) {
		return switch (histogramBehavior) {
			case STANDARD -> GrpcHistogramBehavior.STANDARD;
			case OPTIMIZED -> GrpcHistogramBehavior.OPTIMIZED;
		};
	}

	/**
	 * Converts {@link GrpcManagedReferencesBehaviour} to {@link ManagedReferencesBehaviour}.
	 *
	 * @param grpcManagedReferencesBehaviour the {@link GrpcManagedReferencesBehaviour} to be converted
	 * @return the converted {@link ManagedReferencesBehaviour}
	 * @throws EvitaInternalError if the given grpcManagedReferencesBehaviour is unrecognized
	 */
	@Nonnull
	public static ManagedReferencesBehaviour toManagedReferencesBehaviour(@Nonnull GrpcManagedReferencesBehaviour grpcManagedReferencesBehaviour) {
		return switch (grpcManagedReferencesBehaviour.getNumber()) {
			case 0 -> ManagedReferencesBehaviour.ANY;
			case 1 -> ManagedReferencesBehaviour.EXISTING;
			default ->
				throw new GenericEvitaInternalError("Unrecognized remote histogram behavior: " + grpcManagedReferencesBehaviour);
		};
	}

	/**
	 * Converts {@link ManagedReferencesBehaviour} to {@link GrpcManagedReferencesBehaviour}.
	 *
	 * @param managedReferencesBehaviour the {@link ManagedReferencesBehaviour} to be converted
	 * @return the converted {@link GrpcManagedReferencesBehaviour}
	 */
	@Nonnull
	public static GrpcManagedReferencesBehaviour toGrpcManagedReferencesBehaviour(@Nonnull ManagedReferencesBehaviour managedReferencesBehaviour) {
		return switch (managedReferencesBehaviour) {
			case ANY -> GrpcManagedReferencesBehaviour.ANY;
			case EXISTING -> GrpcManagedReferencesBehaviour.EXISTING;
		};
	}

	/**
	 * Converts {@link GrpcAttributeSpecialValue} to {@link AttributeSpecialValue}.
	 *
	 * @param grpcAttributeSpecialValue the {@link GrpcAttributeSpecialValue} to be converted
	 * @return the converted {@link AttributeSpecialValue}
	 * @throws EvitaInternalError if the remote attribute special value is unrecognized
	 */
	@Nonnull
	public static AttributeSpecialValue toAttributeSpecialValue(@Nonnull GrpcAttributeSpecialValue grpcAttributeSpecialValue) {
		return switch (grpcAttributeSpecialValue.getNumber()) {
			case 0 -> AttributeSpecialValue.NULL;
			case 1 -> AttributeSpecialValue.NOT_NULL;
			default ->
				throw new GenericEvitaInternalError("Unrecognized remote attribute special value: " + grpcAttributeSpecialValue);
		};
	}

	/**
	 * Converts {@link AttributeSpecialValue} to {@link GrpcAttributeSpecialValue}.
	 *
	 * @param attributeSpecialValue the {@link AttributeSpecialValue} to be converted
	 * @return the converted {@link GrpcAttributeSpecialValue}
	 */
	@Nonnull
	public static GrpcAttributeSpecialValue toGrpcAttributeSpecialValue(@Nonnull AttributeSpecialValue attributeSpecialValue) {
		return switch (attributeSpecialValue) {
			case NULL -> GrpcAttributeSpecialValue.NULL;
			case NOT_NULL -> GrpcAttributeSpecialValue.NOT_NULL;
		};
	}

	/**
	 * Converts {@link GrpcFacetStatisticsDepth} to {@link FacetStatisticsDepth}.
	 *
	 * @param grpcFacetStatisticsDepth the {@link GrpcFacetStatisticsDepth} to be converted
	 * @return the converted {@link FacetStatisticsDepth}
	 */
	@Nonnull
	public static FacetStatisticsDepth toFacetStatisticsDepth(@Nonnull GrpcFacetStatisticsDepth grpcFacetStatisticsDepth) {
		return switch (grpcFacetStatisticsDepth.getNumber()) {
			case 0 -> FacetStatisticsDepth.COUNTS;
			case 1 -> FacetStatisticsDepth.IMPACT;
			default ->
				throw new GenericEvitaInternalError("Unrecognized remote facet statistics depth: " + grpcFacetStatisticsDepth);
		};
	}

	/**
	 * Converts {@link FacetRelationType} to {@link GrpcFacetRelationType}.
	 *
	 * @param facetRelationType the {@link FacetRelationType} to be converted
	 * @return the converted {@link GrpcFacetRelationType}
	 */
	@Nonnull
	public static GrpcFacetRelationType toGrpcFacetRelationType(@Nonnull FacetRelationType facetRelationType) {
		return switch (facetRelationType) {
			case CONJUNCTION -> GrpcFacetRelationType.CONJUNCTION;
			case DISJUNCTION -> GrpcFacetRelationType.DISJUNCTION;
			case NEGATION -> GrpcFacetRelationType.NEGATION;
			case EXCLUSIVITY -> GrpcFacetRelationType.EXCLUSIVITY;
		};
	}

	/**
	 * Converts {@link GrpcFacetRelationType} to {@link FacetRelationType}.
	 *
	 * @param grpcFacetRelationType the {@link GrpcFacetRelationType} to be converted
	 * @return the converted {@link FacetRelationType}
	 */
	@Nonnull
	public static FacetRelationType toFacetRelationType(@Nonnull GrpcFacetRelationType grpcFacetRelationType) {
	    return switch (grpcFacetRelationType) {
	        case CONJUNCTION -> FacetRelationType.CONJUNCTION;
	        case DISJUNCTION -> FacetRelationType.DISJUNCTION;
	        case NEGATION -> FacetRelationType.NEGATION;
	        case EXCLUSIVITY -> FacetRelationType.EXCLUSIVITY;
	        default -> throw new GenericEvitaInternalError("Unrecognized remote facet relation type: " + grpcFacetRelationType);
	    };
	}

	/**
	 * Converts {@link FacetGroupRelationLevel} to {@link GrpcFacetGroupRelationLevel}.
	 *
	 * @param facetGroupRelationLevel the {@link FacetGroupRelationLevel} to be converted
	 * @return the converted {@link GrpcFacetGroupRelationLevel}
	 */
	@Nonnull
	public static GrpcFacetGroupRelationLevel toGrpcFacetGroupRelationLevel(@Nonnull FacetGroupRelationLevel facetGroupRelationLevel) {
		return switch (facetGroupRelationLevel) {
			case WITH_DIFFERENT_GROUPS -> GrpcFacetGroupRelationLevel.WITH_DIFFERENT_GROUPS;
			case WITH_DIFFERENT_FACETS_IN_GROUP -> GrpcFacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP;
		};
	}

	/**
	 * Converts {@link GrpcFacetGroupRelationLevel} to {@link FacetGroupRelationLevel}.
	 *
	 * @param grpcFacetGroupRelationLevel the {@link GrpcFacetGroupRelationLevel} to be converted
	 * @return the converted {@link FacetGroupRelationLevel}
	 */
	@Nonnull
	public static FacetGroupRelationLevel toFacetGroupRelationLevel(@Nonnull GrpcFacetGroupRelationLevel grpcFacetGroupRelationLevel) {
	    return switch (grpcFacetGroupRelationLevel) {
	        case WITH_DIFFERENT_GROUPS -> FacetGroupRelationLevel.WITH_DIFFERENT_GROUPS;
	        case WITH_DIFFERENT_FACETS_IN_GROUP -> FacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP;
	        default -> throw new GenericEvitaInternalError("Unrecognized remote facet group relation level: " + grpcFacetGroupRelationLevel);
	    };
	}

	/**
	 * Converts {@link FacetStatisticsDepth} to {@link GrpcFacetStatisticsDepth}.
	 *
	 * @param facetStatisticsDepth the {@link FacetStatisticsDepth} to be converted
	 * @return the converted {@link GrpcFacetStatisticsDepth}
	 */
	@Nonnull
	public static GrpcFacetStatisticsDepth toGrpcFacetStatisticsDepth(@Nonnull FacetStatisticsDepth facetStatisticsDepth) {
		return switch (facetStatisticsDepth) {
			case COUNTS -> GrpcFacetStatisticsDepth.COUNTS;
			case IMPACT -> GrpcFacetStatisticsDepth.IMPACT;
		};
	}

	/**
	 * Converts {@link TraversalMode} to {@link GrpcTraversalMode}.
	 *
	 * @param traversalMode the {@link TraversalMode} to be converted
	 * @return the converted {@link GrpcTraversalMode}
	 */
	@Nonnull
	public static GrpcTraversalMode toGrpcTraversalMode(@Nonnull TraversalMode traversalMode) {
		return switch (traversalMode) {
			case DEPTH_FIRST -> GrpcTraversalMode.DEPTH_FIRST;
			case BREADTH_FIRST -> GrpcTraversalMode.BREADTH_FIRST;
		};
	}

	/**
	 * Converts {@link GrpcTraversalMode} to {@link TraversalMode}.
	 *
	 * @param grpcTraversalMode the {@link GrpcTraversalMode} to be converted
	 * @return the converted {@link TraversalMode}
	 */
	@Nonnull
	public static TraversalMode toTraversalMode(@Nonnull GrpcTraversalMode grpcTraversalMode) {
		return switch (grpcTraversalMode) {
			case DEPTH_FIRST -> TraversalMode.DEPTH_FIRST;
			case BREADTH_FIRST -> TraversalMode.BREADTH_FIRST;
			default -> throw new GenericEvitaInternalError("Unrecognized remote traversal mode: " + grpcTraversalMode);
		};
	}

	/**
	 * Converts {@link GrpcPriceInnerRecordHandling} to {@link PriceInnerRecordHandling}.
	 *
	 * @param grpcPriceInnerRecordHandling the {@link GrpcPriceInnerRecordHandling} to be converted
	 * @return the converted {@link PriceInnerRecordHandling}
	 * @throws EvitaInternalError if the remote price inner record handling is unrecognized
	 */
	@Nonnull
	public static PriceInnerRecordHandling toPriceInnerRecordHandling(@Nonnull GrpcPriceInnerRecordHandling grpcPriceInnerRecordHandling) {
		return switch (grpcPriceInnerRecordHandling.getNumber()) {
			case 0 -> PriceInnerRecordHandling.NONE;
			case 1 -> PriceInnerRecordHandling.LOWEST_PRICE;
			case 2 -> PriceInnerRecordHandling.SUM;
			case 3 -> PriceInnerRecordHandling.UNKNOWN;
			default ->
				throw new GenericEvitaInternalError("Unrecognized remote price inner record handling: " + grpcPriceInnerRecordHandling);
		};
	}

	/**
	 * Converts {@link PriceInnerRecordHandling} to {@link GrpcPriceInnerRecordHandling}.
	 *
	 * @param priceInnerRecordHandling the {@link PriceInnerRecordHandling} to be converted
	 * @return the converted {@link GrpcPriceInnerRecordHandling}
	 */
	@Nonnull
	public static GrpcPriceInnerRecordHandling toGrpcPriceInnerRecordHandling(@Nonnull PriceInnerRecordHandling priceInnerRecordHandling) {
		return switch (priceInnerRecordHandling) {
			case NONE -> GrpcPriceInnerRecordHandling.NONE;
			case LOWEST_PRICE -> GrpcPriceInnerRecordHandling.LOWEST_PRICE;
			case SUM -> GrpcPriceInnerRecordHandling.SUM;
			case UNKNOWN -> GrpcPriceInnerRecordHandling.UNKNOWN;
		};
	}

	/**
	 * Converts {@link GrpcCardinality} to {@link Cardinality}.
	 *
	 * @param grpcCardinality the {@link GrpcCardinality} to be converted
	 * @return the converted {@link Cardinality}, or null if grpcCardinality is 0
	 * @throws EvitaInternalError if the grpcCardinality is unrecognized
	 */
	@Nonnull
	public static Optional<Cardinality> toCardinality(@Nonnull GrpcCardinality grpcCardinality) {
		return switch (grpcCardinality.getNumber()) {
			case 1 -> of(Cardinality.ZERO_OR_ONE);
			case 2 -> of(Cardinality.EXACTLY_ONE);
			case 3 -> of(Cardinality.ZERO_OR_MORE);
			case 4 -> of(Cardinality.ONE_OR_MORE);
			default -> empty();
		};
	}

	/**
	 * Converts {@link Cardinality} to {@link GrpcCardinality}.
	 *
	 * @param cardinality the {@link Cardinality} to be converted
	 * @return the converted {@link GrpcCardinality}, or {@link GrpcCardinality#NOT_SPECIFIED} if cardinality is null
	 */
	@Nonnull
	public static GrpcCardinality toGrpcCardinality(@Nullable Cardinality cardinality) {
		if (cardinality == null) {
			return GrpcCardinality.NOT_SPECIFIED;
		}
		return switch (cardinality) {
			case ZERO_OR_ONE -> GrpcCardinality.ZERO_OR_ONE;
			case EXACTLY_ONE -> GrpcCardinality.EXACTLY_ONE;
			case ZERO_OR_MORE -> GrpcCardinality.ZERO_OR_MORE;
			case ONE_OR_MORE -> GrpcCardinality.ONE_OR_MORE;
		};
	}

	/**
	 * Converts {@link GrpcCatalogEvolutionMode} to {@link CatalogEvolutionMode}.
	 *
	 * @param grpcEvolutionMode the {@link GrpcCatalogEvolutionMode} to be converted
	 * @return the converted {@link CatalogEvolutionMode}
	 * @throws EvitaInternalError if the remote evolution mode is unrecognized
	 */
	@Nonnull
	public static CatalogEvolutionMode toCatalogEvolutionMode(@Nonnull GrpcCatalogEvolutionMode grpcEvolutionMode) {
		return switch (grpcEvolutionMode.getNumber()) {
			case 0 -> CatalogEvolutionMode.ADDING_ENTITY_TYPES;
			default -> throw new GenericEvitaInternalError("Unrecognized remote evolution mode: " + grpcEvolutionMode);
		};
	}

	/**
	 * Converts {@link CatalogEvolutionMode} to {@link GrpcCatalogEvolutionMode}.
	 *
	 * @param evolutionMode the {@link CatalogEvolutionMode} to be converted
	 * @return the converted {@link GrpcCatalogEvolutionMode}
	 */
	@Nonnull
	public static GrpcCatalogEvolutionMode toGrpcCatalogEvolutionMode(@Nonnull CatalogEvolutionMode evolutionMode) {
		return switch (evolutionMode) {
			case ADDING_ENTITY_TYPES -> GrpcCatalogEvolutionMode.ADDING_ENTITY_TYPES;
		};
	}

	/**
	 * Converts {@link GrpcEvolutionMode} to {@link EvolutionMode}.
	 *
	 * @param grpcEvolutionMode the {@link GrpcEvolutionMode} to be converted
	 * @return the converted {@link EvolutionMode}
	 * @throws EvitaInternalError if the remote evolution mode is unrecognized
	 */
	@Nonnull
	public static EvolutionMode toEvolutionMode(@Nonnull GrpcEvolutionMode grpcEvolutionMode) {
		return switch (grpcEvolutionMode.getNumber()) {
			case 0 -> EvolutionMode.ADAPT_PRIMARY_KEY_GENERATION;
			case 1 -> EvolutionMode.ADDING_ATTRIBUTES;
			case 2 -> EvolutionMode.ADDING_ASSOCIATED_DATA;
			case 3 -> EvolutionMode.ADDING_REFERENCES;
			case 4 -> EvolutionMode.ADDING_PRICES;
			case 5 -> EvolutionMode.ADDING_LOCALES;
			case 6 -> EvolutionMode.ADDING_CURRENCIES;
			case 7 -> EvolutionMode.ADDING_HIERARCHY;
			default -> throw new GenericEvitaInternalError("Unrecognized remote evolution mode: " + grpcEvolutionMode);
		};
	}

	/**
	 * Converts {@link EvolutionMode} to {@link GrpcEvolutionMode}.
	 *
	 * @param evolutionMode the {@link EvolutionMode} to be converted
	 * @return the converted {@link GrpcEvolutionMode}
	 */
	@Nonnull
	public static GrpcEvolutionMode toGrpcEvolutionMode(@Nonnull EvolutionMode evolutionMode) {
		return switch (evolutionMode) {
			case ADAPT_PRIMARY_KEY_GENERATION -> GrpcEvolutionMode.ADAPT_PRIMARY_KEY_GENERATION;
			case ADDING_ATTRIBUTES -> GrpcEvolutionMode.ADDING_ATTRIBUTES;
			case ADDING_ASSOCIATED_DATA -> GrpcEvolutionMode.ADDING_ASSOCIATED_DATA;
			case ADDING_REFERENCES -> GrpcEvolutionMode.ADDING_REFERENCES;
			case ADDING_PRICES -> GrpcEvolutionMode.ADDING_PRICES;
			case ADDING_LOCALES -> GrpcEvolutionMode.ADDING_LOCALES;
			case ADDING_CURRENCIES -> GrpcEvolutionMode.ADDING_CURRENCIES;
			case ADDING_HIERARCHY -> GrpcEvolutionMode.ADDING_HIERARCHY;
		};
	}

	/**
	 * Converts {@link GrpcQueryPhase} to {@link QueryPhase}.
	 *
	 * @param grpcQueryPhase the {@link GrpcQueryPhase} to be converted
	 * @return the converted {@link QueryPhase}
	 * @throws EvitaInternalError if the remote query phase is unrecognized
	 */
	@Nonnull
	public static QueryPhase toQueryPhase(@Nonnull GrpcQueryPhase grpcQueryPhase) {
		return switch (grpcQueryPhase.getNumber()) {
			case 0 -> QueryPhase.OVERALL;
			case 1 -> QueryPhase.PLANNING;
			case 2 -> QueryPhase.PLANNING_NESTED_QUERY;
			case 3 -> QueryPhase.PLANNING_INDEX_USAGE;
			case 4 -> QueryPhase.PLANNING_FILTER;
			case 5 -> QueryPhase.PLANNING_FILTER_NESTED_QUERY;
			case 6 -> QueryPhase.PLANNING_FILTER_ALTERNATIVE;
			case 7 -> QueryPhase.PLANNING_SORT;
			case 8 -> QueryPhase.PLANNING_SORT_ALTERNATIVE;
			case 9 -> QueryPhase.PLANNING_EXTRA_RESULT_FABRICATION;
			case 10 -> QueryPhase.PLANNING_EXTRA_RESULT_FABRICATION_ALTERNATIVE;
			case 11 -> QueryPhase.EXECUTION;
			case 12 -> QueryPhase.EXECUTION_PREFETCH;
			case 13 -> QueryPhase.EXECUTION_FILTER;
			case 14 -> QueryPhase.EXECUTION_FILTER_NESTED_QUERY;
			case 15 -> QueryPhase.EXECUTION_SORT_AND_SLICE;
			case 16 -> QueryPhase.EXTRA_RESULTS_FABRICATION;
			case 17 -> QueryPhase.EXTRA_RESULT_ITEM_FABRICATION;
			case 18 -> QueryPhase.FETCHING;
			case 19 -> QueryPhase.FETCHING_REFERENCES;
			case 20 -> QueryPhase.FETCHING_PARENTS;
			default -> throw new GenericEvitaInternalError("Unrecognized remote query phase: " + grpcQueryPhase);
		};
	}

	/**
	 * Converts {@link QueryPhase} to {@link GrpcQueryPhase}.
	 *
	 * @param queryPhase the {@link QueryPhase} to be converted
	 * @return the converted {@link GrpcQueryPhase}
	 */
	@Nonnull
	public static GrpcQueryPhase toGrpcQueryPhase(@Nonnull QueryPhase queryPhase) {
		return switch (queryPhase) {
			case OVERALL -> GrpcQueryPhase.OVERALL;
			case PLANNING -> GrpcQueryPhase.PLANNING;
			case PLANNING_NESTED_QUERY -> GrpcQueryPhase.PLANNING_NESTED_QUERY;
			case PLANNING_INDEX_USAGE -> GrpcQueryPhase.PLANNING_INDEX_USAGE;
			case PLANNING_FILTER -> GrpcQueryPhase.PLANNING_FILTER;
			case PLANNING_FILTER_NESTED_QUERY -> GrpcQueryPhase.PLANNING_FILTER_NESTED_QUERY;
			case PLANNING_FILTER_ALTERNATIVE -> GrpcQueryPhase.PLANNING_FILTER_ALTERNATIVE;
			case PLANNING_SORT -> GrpcQueryPhase.PLANNING_SORT;
			case PLANNING_SORT_ALTERNATIVE -> GrpcQueryPhase.PLANNING_SORT_ALTERNATIVE;
			case PLANNING_EXTRA_RESULT_FABRICATION -> GrpcQueryPhase.PLANNING_EXTRA_RESULT_FABRICATION;
			case PLANNING_EXTRA_RESULT_FABRICATION_ALTERNATIVE ->
				GrpcQueryPhase.PLANNING_EXTRA_RESULT_FABRICATION_ALTERNATIVE;
			case EXECUTION -> GrpcQueryPhase.EXECUTION;
			case EXECUTION_PREFETCH -> GrpcQueryPhase.EXECUTION_PREFETCH;
			case EXECUTION_FILTER -> GrpcQueryPhase.EXECUTION_FILTER;
			case EXECUTION_FILTER_NESTED_QUERY -> GrpcQueryPhase.EXECUTION_FILTER_NESTED_QUERY;
			case EXECUTION_SORT_AND_SLICE -> GrpcQueryPhase.EXECUTION_SORT_AND_SLICE;
			case EXTRA_RESULTS_FABRICATION -> GrpcQueryPhase.EXTRA_RESULTS_FABRICATION;
			case EXTRA_RESULT_ITEM_FABRICATION -> GrpcQueryPhase.EXTRA_RESULT_ITEM_FABRICATION;
			case FETCHING -> GrpcQueryPhase.FETCHING;
			case FETCHING_REFERENCES -> GrpcQueryPhase.FETCHING_REFERENCES;
			case FETCHING_PARENTS -> GrpcQueryPhase.FETCHING_PARENTS;
		};
	}

	/**
	 * Converts {@link GrpcEntityExistence} to {@link EntityExistence}.
	 *
	 * @param grpcEntityExistence the {@link GrpcEntityExistence} to be converted
	 * @return the converted {@link EntityExistence}
	 * @throws EvitaInternalError if the remote entity existence is unrecognized
	 */
	@Nonnull
	public static EntityExistence toEntityExistence(@Nonnull GrpcEntityExistence grpcEntityExistence) {
		return switch (grpcEntityExistence.getNumber()) {
			case 0 -> EntityExistence.MAY_EXIST;
			case 1 -> EntityExistence.MUST_NOT_EXIST;
			case 2 -> EntityExistence.MUST_EXIST;
			default ->
				throw new GenericEvitaInternalError("Unrecognized remote entity existence: " + grpcEntityExistence);
		};
	}

	/**
	 * Converts {@link EntityExistence} to {@link GrpcEntityExistence}.
	 *
	 * @param entityExistence the {@link EntityExistence} to be converted
	 * @return the converted {@link GrpcEntityExistence}
	 */
	@Nonnull
	public static GrpcEntityExistence toGrpcEntityExistence(@Nonnull EntityExistence entityExistence) {
		return switch (entityExistence) {
			case MAY_EXIST -> GrpcEntityExistence.MAY_EXIST;
			case MUST_NOT_EXIST -> GrpcEntityExistence.MUST_NOT_EXIST;
			case MUST_EXIST -> GrpcEntityExistence.MUST_EXIST;
		};
	}

	/**
	 * Converts a subclass of {@link AttributeSchemaContract} to {@link GrpcAttributeSchemaType}.
	 *
	 * @param attributeSchemaClass the class of the attribute schema to be converted
	 * @return the converted {@link GrpcAttributeSchemaType}
	 * @throws EvitaInternalError if the attribute schema type is unrecognized
	 */
	@Nonnull
	public static GrpcAttributeSchemaType toGrpcAttributeSchemaType(@Nonnull Class<? extends AttributeSchemaContract> attributeSchemaClass) {
		if (GlobalAttributeSchemaContract.class.isAssignableFrom(attributeSchemaClass)) {
			return GrpcAttributeSchemaType.GLOBAL_SCHEMA;
		} else if (EntityAttributeSchemaContract.class.isAssignableFrom(attributeSchemaClass)) {
			return GrpcAttributeSchemaType.ENTITY_SCHEMA;
		} else if (AttributeSchemaContract.class.isAssignableFrom(attributeSchemaClass)) {
			return GrpcAttributeSchemaType.REFERENCE_SCHEMA;
		} else {
			throw new GenericEvitaInternalError("Unrecognized attribute schema type: " + attributeSchemaClass);
		}
	}

	/**
	 * Converts {@link GrpcAttributeUniquenessType} to {@link AttributeUniquenessType}.
	 *
	 * @param type the {@link GrpcAttributeUniquenessType} to convert
	 * @return the converted {@link AttributeUniquenessType}
	 */
	@Nonnull
	public static AttributeUniquenessType toAttributeUniquenessType(@Nonnull GrpcAttributeUniquenessType type) {
		return switch (type.getNumber()) {
			case 0 -> AttributeUniquenessType.NOT_UNIQUE;
			case 1 -> AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION;
			case 2 -> AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE;
			default -> throw new GenericEvitaInternalError("Unrecognized remote attribute uniqueness type: " + type);
		};
	}

	/**
	 * Converts {@link AttributeUniquenessType} to {@link GrpcAttributeUniquenessType}.
	 *
	 * @param type the {@link AttributeUniquenessType} to convert
	 * @return the converted {@link GrpcAttributeUniquenessType}
	 */
	@Nonnull
	public static GrpcAttributeUniquenessType toGrpcAttributeUniquenessType(@Nonnull AttributeUniquenessType type) {
		return switch (type) {
			case NOT_UNIQUE -> GrpcAttributeUniquenessType.NOT_UNIQUE;
			case UNIQUE_WITHIN_COLLECTION -> GrpcAttributeUniquenessType.UNIQUE_WITHIN_COLLECTION;
			case UNIQUE_WITHIN_COLLECTION_LOCALE -> GrpcAttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE;
		};
	}

	/**
	 * Converts {@link GrpcGlobalAttributeUniquenessType} to {@link GlobalAttributeUniquenessType}.
	 *
	 * @param type the {@link GrpcGlobalAttributeUniquenessType} to convert
	 * @return the converted {@link GlobalAttributeUniquenessType}
	 */
	@Nonnull
	public static GlobalAttributeUniquenessType toGlobalAttributeUniquenessType(@Nonnull GrpcGlobalAttributeUniquenessType type) {
		return switch (type.getNumber()) {
			case 0 -> GlobalAttributeUniquenessType.NOT_UNIQUE;
			case 1 -> GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG;
			case 2 -> GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG_LOCALE;
			default ->
				throw new GenericEvitaInternalError("Unrecognized remote global attribute uniqueness type: " + type);
		};
	}

	/**
	 * Converts {@link GlobalAttributeUniquenessType} to {@link GrpcGlobalAttributeUniquenessType}.
	 *
	 * @param type the {@link GlobalAttributeUniquenessType} to convert
	 * @return the converted {@link GrpcGlobalAttributeUniquenessType}
	 */
	@Nonnull
	public static GrpcGlobalAttributeUniquenessType toGrpcGlobalAttributeUniquenessType(@Nonnull GlobalAttributeUniquenessType type) {
		return switch (type) {
			case NOT_UNIQUE -> GrpcGlobalAttributeUniquenessType.NOT_GLOBALLY_UNIQUE;
			case UNIQUE_WITHIN_CATALOG -> GrpcGlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG;
			case UNIQUE_WITHIN_CATALOG_LOCALE -> GrpcGlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG_LOCALE;
		};
	}

	/**
	 * Converts the given CommitBehavior to GrpcCommitBehaviour.
	 *
	 * @param commitBehavior the CommitBehavior to convert
	 * @return the corresponding GrpcCommitBehaviour
	 */
	@Nonnull
	public static GrpcCommitBehavior toGrpcCommitBehavior(@Nonnull CommitBehavior commitBehavior) {
		return switch (commitBehavior) {
			case WAIT_FOR_CONFLICT_RESOLUTION -> GrpcCommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION;
			case WAIT_FOR_WAL_PERSISTENCE -> GrpcCommitBehavior.WAIT_FOR_LOG_PERSISTENCE;
			case WAIT_FOR_CHANGES_VISIBLE -> GrpcCommitBehavior.WAIT_FOR_CHANGES_VISIBLE;
		};
	}

	/**
	 * Converts a GrpcCommitBehaviour to a CommitBehavior.
	 *
	 * @param commitBehaviour The GrpcCommitBehavior to convert.
	 * @return The converted CommitBehavior.
	 * @throws EvitaInternalError if the given commitBehaviour is unrecognized.
	 */
	@Nonnull
	public static CommitBehavior toCommitBehavior(@Nonnull GrpcCommitBehavior commitBehaviour) {
		return switch (commitBehaviour) {
			case WAIT_FOR_CONFLICT_RESOLUTION -> CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION;
			case WAIT_FOR_LOG_PERSISTENCE -> CommitBehavior.WAIT_FOR_WAL_PERSISTENCE;
			case WAIT_FOR_CHANGES_VISIBLE -> CommitBehavior.WAIT_FOR_CHANGES_VISIBLE;
			default -> throw new GenericEvitaInternalError("Unrecognized remote commit behavior: " + commitBehaviour);
		};
	}

	/**
	 * Converts a GrpcNamingConvention to a NamingConvention.
	 *
	 * @param namingConvention The GrpcNamingConvention to convert.
	 * @return The converted NamingConvention.
	 */
	@Nonnull
	public static GrpcNamingConvention toGrpcNamingConvention(@Nonnull NamingConvention namingConvention) {
		return switch (namingConvention) {
			case SNAKE_CASE -> GrpcNamingConvention.SNAKE_CASE;
			case CAMEL_CASE -> GrpcNamingConvention.CAMEL_CASE;
			case UPPER_SNAKE_CASE -> GrpcNamingConvention.UPPER_SNAKE_CASE;
			case PASCAL_CASE -> GrpcNamingConvention.PASCAL_CASE;
			case KEBAB_CASE -> GrpcNamingConvention.KEBAB_CASE;
		};
	}

	/**
	 * Converts a NamingConvention to a GrpcNamingConvention.
	 *
	 * @param namingConvention The NamingConvention to convert.
	 * @return The converted GrpcNamingConvention.
	 */
	@Nonnull
	public static NamingConvention toNamingConvention(@Nonnull GrpcNamingConvention namingConvention) {
		return switch (namingConvention) {
			case SNAKE_CASE -> NamingConvention.SNAKE_CASE;
			case CAMEL_CASE -> NamingConvention.CAMEL_CASE;
			case UPPER_SNAKE_CASE -> NamingConvention.UPPER_SNAKE_CASE;
			case PASCAL_CASE -> NamingConvention.PASCAL_CASE;
			case KEBAB_CASE -> NamingConvention.KEBAB_CASE;
			default -> throw new GenericEvitaInternalError("Unrecognized naming convention: " + namingConvention);
		};
	}

	/**
	 * Converts a {@link GrpcChangeCaptureArea} to a {@link CaptureArea}.
	 *
	 * @param area The GrpcChangeCaptureArea to convert.
	 * @return The converted CaptureArea.
	 */
	@Nonnull
	public static CaptureArea toCaptureArea(@Nonnull GrpcChangeCaptureArea area) {
		return switch (area) {
			case DATA -> CaptureArea.DATA;
			case SCHEMA -> CaptureArea.SCHEMA;
			case INFRASTRUCTURE -> CaptureArea.INFRASTRUCTURE;
			default -> throw new GenericEvitaInternalError("Unrecognized capture area: " + area);
		};
	}

	/**
	 * Converts a {@link CaptureArea} to a {@link GrpcChangeCaptureArea}.
	 *
	 * @param area The CaptureArea to convert.
	 * @return The converted GrpcChangeCaptureArea.
	 */
	@Nonnull
	public static GrpcChangeCaptureArea toGrpcChangeCaptureArea(@Nonnull CaptureArea area) {
		return switch (area) {
			case DATA -> GrpcChangeCaptureArea.DATA;
			case SCHEMA -> GrpcChangeCaptureArea.SCHEMA;
			case INFRASTRUCTURE -> GrpcChangeCaptureArea.INFRASTRUCTURE;
		};
	}

	/**
	 * Converts a {@link GrpcChangeCaptureOperation} to an {@link Operation}.
	 *
	 * @param grpcOperation The {@link GrpcChangeCaptureOperation} to convert.
	 * @return The converted {@link Operation}.
	 */
	@Nonnull
	public static Operation toOperation(@Nonnull GrpcChangeCaptureOperation grpcOperation) {
		return switch (grpcOperation) {
			case UPSERT -> Operation.UPSERT;
			case REMOVE -> Operation.REMOVE;
			case TRANSACTION -> Operation.TRANSACTION;
			default -> throw new GenericEvitaInternalError("Unrecognized operation: " + grpcOperation);
		};
	}

	/**
	 * Converts an {@link Operation} to a {@link GrpcChangeCaptureOperation}.
	 *
	 * @param operation The Operation to convert.
	 * @return The converted GrpcOperation.
	 */
	@Nonnull
	public static GrpcChangeCaptureOperation toGrpcOperation(@Nonnull Operation operation) {
		return switch (operation) {
			case UPSERT -> GrpcChangeCaptureOperation.UPSERT;
			case REMOVE -> GrpcChangeCaptureOperation.REMOVE;
			case TRANSACTION -> GrpcChangeCaptureOperation.TRANSACTION;
		};
	}

	/**
	 * Converts a {@link GrpcChangeCaptureContainerType} to a {@link ContainerType}.
	 *
	 * @param GrpcChangeCaptureContainerType The {@link GrpcChangeCaptureContainerType} to convert.
	 * @return The converted {@link ContainerType}.
	 */
	@Nonnull
	public static ContainerType toContainerType(@Nonnull GrpcChangeCaptureContainerType GrpcChangeCaptureContainerType) {
		return switch (GrpcChangeCaptureContainerType) {
			case CONTAINER_CATALOG -> ContainerType.CATALOG;
			case CONTAINER_ENTITY -> ContainerType.ENTITY;
			case CONTAINER_ATTRIBUTE -> ContainerType.ATTRIBUTE;
			case CONTAINER_ASSOCIATED_DATA -> ContainerType.ASSOCIATED_DATA;
			case CONTAINER_REFERENCE -> ContainerType.REFERENCE;
			case CONTAINER_PRICE -> ContainerType.PRICE;
			default -> throw new GenericEvitaInternalError("Unrecognized container type: " + GrpcChangeCaptureContainerType);
		};
	}

	/**
	 * Converts a {@link ContainerType} to a {@link GrpcChangeCaptureContainerType}.
	 *
	 * @param containerType The ContainerType to convert.
	 * @return The converted GrpcChangeCaptureContainerType.
	 */
	@Nonnull
	public static GrpcChangeCaptureContainerType toGrpcChangeCaptureContainerType(@Nonnull ContainerType containerType) {
		return switch (containerType) {
			case CATALOG -> GrpcChangeCaptureContainerType.CONTAINER_CATALOG;
			case ENTITY -> GrpcChangeCaptureContainerType.CONTAINER_ENTITY;
			case ATTRIBUTE -> GrpcChangeCaptureContainerType.CONTAINER_ATTRIBUTE;
			case ASSOCIATED_DATA -> GrpcChangeCaptureContainerType.CONTAINER_ASSOCIATED_DATA;
			case REFERENCE -> GrpcChangeCaptureContainerType.CONTAINER_REFERENCE;
			case PRICE -> GrpcChangeCaptureContainerType.CONTAINER_PRICE;
		};
	}

	/**
	 * Converts a {@link GrpcChangeCaptureContent} to a {@link ChangeCaptureContent}.
	 *
	 * @param content The {@link GrpcChangeCaptureContent} to convert.
	 * @return The converted {@link ChangeCaptureContent}.
	 */
	@Nonnull
	public static ChangeCaptureContent toCaptureContent(@Nonnull GrpcChangeCaptureContent content) {
		return switch (content) {
			case CHANGE_HEADER -> ChangeCaptureContent.HEADER;
			case CHANGE_BODY -> ChangeCaptureContent.BODY;
			default -> throw new GenericEvitaInternalError("Unrecognized capture content: " + content);
		};
	}

	/**
	 * Converts a {@link TrafficRecordingContent} to a {@link GrpcTrafficRecordingContent}.
	 *
	 * @param content The {@link TrafficRecordingContent} to convert.
	 * @return The converted {@link GrpcTrafficRecordingContent}.
	 */
	@Nonnull
	public static GrpcTrafficRecordingContent toGrpcChangeCaptureContent(@Nonnull TrafficRecordingContent content) {
		return switch (content) {
			case HEADER -> TRAFFIC_RECORDING_HEADER;
			case BODY -> TRAFFIC_RECORDING_BODY;
		};
	}

	/**
	 * Converts a {@link GrpcTrafficRecordingContent} to a {@link TrafficRecordingContent}.
	 *
	 * @param content The {@link GrpcTrafficRecordingContent} to convert.
	 * @return The converted {@link TrafficRecordingContent}.
	 */
	@Nonnull
	public static TrafficRecordingContent toCaptureContent(@Nonnull GrpcTrafficRecordingContent content) {
		return switch (content) {
			case TRAFFIC_RECORDING_HEADER -> TrafficRecordingContent.HEADER;
			case TRAFFIC_RECORDING_BODY -> TrafficRecordingContent.BODY;
			default -> throw new GenericEvitaInternalError("Unrecognized capture content: " + content);
		};
	}

	/**
	 * Converts a {@link ChangeCaptureContent} to a {@link GrpcChangeCaptureContent}.
	 *
	 * @param content The ChangeCaptureContent to convert.
	 * @return The converted GrpcChangeCaptureContent.
	 */
	@Nonnull
	public static GrpcChangeCaptureContent toGrpcChangeCaptureContent(@Nonnull ChangeCaptureContent content) {
		return switch (content) {
			case HEADER -> GrpcChangeCaptureContent.CHANGE_HEADER;
			case BODY -> GrpcChangeCaptureContent.CHANGE_BODY;
		};
	}

	/**
	 * Converts a {@link HealthProblem} to a {@link GrpcHealthProblem}.
	 *
	 * @param problem The HealthProblem to convert.
	 * @return The converted GrpcHealthProblem.
	 */
	@Nonnull
	public static GrpcHealthProblem toGrpcHealthProblem(@Nonnull HealthProblem problem) {
		return switch (problem) {
			case MEMORY_SHORTAGE -> GrpcHealthProblem.MEMORY_SHORTAGE;
			case EXTERNAL_API_UNAVAILABLE -> GrpcHealthProblem.EXTERNAL_API_UNAVAILABLE;
			case INPUT_QUEUES_OVERLOADED -> GrpcHealthProblem.INPUT_QUEUES_OVERLOADED;
			case JAVA_INTERNAL_ERRORS -> GrpcHealthProblem.JAVA_INTERNAL_ERRORS;
		};
	}

	/**
	 * Converts a {@link ReadinessState} to a {@link GrpcReadiness}.
	 *
	 * @param readinessState The ReadinessState to convert.
	 * @return The converted GrpcReadiness.
	 */
	@Nonnull
	public static GrpcReadiness toGrpcReadinessState(@Nonnull ReadinessState readinessState) {
		return switch (readinessState) {
			case STARTING -> GrpcReadiness.API_STARTING;
			case READY -> GrpcReadiness.API_READY;
			case STALLING -> GrpcReadiness.API_STALLING;
			case SHUTDOWN -> GrpcReadiness.API_SHUTDOWN;
			case UNKNOWN -> GrpcReadiness.API_UNKNOWN;
		};
	}

	/**
	 * Converts a {@link TaskSimplifiedState} to a {@link GrpcTaskSimplifiedState}.
	 *
	 * @param state the simplified state of the task
	 * @return the corresponding gRPC task simplified state
	 */
	@Nonnull
	public static GrpcTaskSimplifiedState toGrpcSimplifiedStatus(@Nonnull TaskSimplifiedState state) {
		return switch (state) {
			case QUEUED -> TASK_QUEUED;
			case RUNNING -> TASK_RUNNING;
			case FAILED -> TASK_FAILED;
			case FINISHED -> TASK_FINISHED;
			case WAITING_FOR_PRECONDITION -> TASK_WAITING_FOR_PRECONDITION;
		};
	}

	/**
	 * Converts a {@link GrpcTaskSimplifiedState} to a {@link TaskSimplifiedState}.
	 *
	 * @param grpcState the gRPC task simplified state
	 * @return the corresponding simplified state of the task
	 */
	@Nonnull
	public static TaskSimplifiedState toSimplifiedStatus(@Nonnull GrpcTaskSimplifiedState grpcState) {
		return switch (grpcState) {
			case TASK_QUEUED -> TaskSimplifiedState.QUEUED;
			case TASK_RUNNING -> TaskSimplifiedState.RUNNING;
			case TASK_FAILED -> TaskSimplifiedState.FAILED;
			case TASK_FINISHED -> TaskSimplifiedState.FINISHED;
			case TASK_WAITING_FOR_PRECONDITION -> TaskSimplifiedState.WAITING_FOR_PRECONDITION;
			case UNRECOGNIZED ->
				throw new GenericEvitaInternalError("Unrecognized task simplified state: " + grpcState);
		};
	}

	/**
	 * Converts an {@link AttributeInheritanceBehavior} to a {@link GrpcAttributeInheritanceBehavior}.
	 *
	 * @param attributeInheritanceBehavior The {@link AttributeInheritanceBehavior} to convert.
	 * @return The converted {@link GrpcAttributeInheritanceBehavior}.
	 * @throws GenericEvitaInternalError if the conversion cannot be performed.
	 */
	@Nonnull
	public static GrpcAttributeInheritanceBehavior toGrpcAttributeInheritanceBehavior(@Nonnull AttributeInheritanceBehavior attributeInheritanceBehavior) {
		return switch (attributeInheritanceBehavior) {
			case INHERIT_ALL_EXCEPT -> GrpcAttributeInheritanceBehavior.INHERIT_ALL_EXCEPT;
			case INHERIT_ONLY_SPECIFIED -> GrpcAttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED;
		};
	}

	/**
	 * Converts a {@link GrpcAttributeInheritanceBehavior} to an {@link AttributeInheritanceBehavior}.
	 *
	 * @param attributeInheritanceBehavior The {@link GrpcAttributeInheritanceBehavior} to convert.
	 * @return The converted {@link AttributeInheritanceBehavior}.
	 * @throws GenericEvitaInternalError if the conversion cannot be performed.
	 */
	@Nonnull
	public static AttributeInheritanceBehavior toAttributeInheritanceBehavior(@Nonnull GrpcAttributeInheritanceBehavior attributeInheritanceBehavior) {
		return switch (attributeInheritanceBehavior) {
			case INHERIT_ALL_EXCEPT -> AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT;
			case INHERIT_ONLY_SPECIFIED -> AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED;
			default ->
				throw new GenericEvitaInternalError("Unrecognized attribute inheritance behavior: " + attributeInheritanceBehavior);
		};
	}

	/**
	 * Converts a {@link TaskTrait} to a {@link GrpcTaskTrait}.
	 *
	 * @param taskTrait The TaskTrait to convert.
	 * @return The converted GrpcTaskTrait.
	 */
	@Nonnull
	public static GrpcTaskTrait toGrpcTaskTrait(@Nonnull TaskTrait taskTrait) {
		return switch (taskTrait) {
			case CAN_BE_STARTED -> GrpcTaskTrait.TASK_CAN_BE_STARTED;
			case CAN_BE_CANCELLED -> GrpcTaskTrait.TASK_CAN_BE_CANCELLED;
			case NEEDS_TO_BE_STOPPED -> GrpcTaskTrait.TASK_NEEDS_TO_BE_STOPPED;
			default -> throw new GenericEvitaInternalError("Unrecognized task trait: " + taskTrait);
		};
	}

	/**
	 * Converts a {@link GrpcTaskTrait} to a {@link TaskTrait}.
	 *
	 * @param grpcTaskTrait The GrpcTaskTrait to convert.
	 * @return The converted TaskTrait.
	 */
	@Nonnull
	public static TaskTrait toTaskTrait(@Nonnull GrpcTaskTrait grpcTaskTrait) {
		return switch (grpcTaskTrait) {
			case TASK_CAN_BE_STARTED -> TaskTrait.CAN_BE_STARTED;
			case TASK_CAN_BE_CANCELLED -> TaskTrait.CAN_BE_CANCELLED;
			case TASK_NEEDS_TO_BE_STOPPED -> TaskTrait.NEEDS_TO_BE_STOPPED;
			case UNRECOGNIZED -> throw new GenericEvitaInternalError("Unrecognized grpc task trait: " + grpcTaskTrait);
		};
	}

	/**
	 * Converts a {@link ClassifierType} to a {@link GrpcClassifierType}.
	 *
	 * @param key The ClassifierType to convert.
	 * @return The converted GrpcClassifierType.
	 * @throws GenericEvitaInternalError if the conversion cannot be performed.
	 */
	@Nonnull
	public static GrpcClassifierType toGrpcClassifierType(@Nonnull ClassifierType key) {
		return switch (key) {
			case SERVER_NAME -> GrpcClassifierType.CLASSIFIER_TYPE_SERVER_NAME;
			case CATALOG -> GrpcClassifierType.CLASSIFIER_TYPE_CATALOG;
			case ENTITY -> GrpcClassifierType.CLASSIFIER_TYPE_ENTITY;
			case ATTRIBUTE -> GrpcClassifierType.CLASSIFIER_TYPE_ATTRIBUTE;
			case ASSOCIATED_DATA -> GrpcClassifierType.CLASSIFIER_TYPE_ASSOCIATED_DATA;
			case REFERENCE -> GrpcClassifierType.CLASSIFIER_TYPE_REFERENCE;
			case REFERENCE_ATTRIBUTE -> GrpcClassifierType.CLASSIFIER_TYPE_REFERENCE_ATTRIBUTE;
		};
	}

	/**
	 * Converts an {@link Scope} to a {@link GrpcEntityScope}.
	 *
	 * @param scope the scope to convert.
	 * @return the corresponding gRPC scope.
	 * @throws GenericEvitaInternalError if the conversion cannot be performed.
	 */
	@Nonnull
	public static GrpcEntityScope toGrpcScope(@Nonnull Scope scope) {
		return switch (scope) {
			case LIVE -> GrpcEntityScope.SCOPE_LIVE;
			case ARCHIVED -> GrpcEntityScope.SCOPE_ARCHIVED;
		};
	}

	/**
	 * Converts a {@link GrpcEntityScope} to an {@link Scope}.
	 *
	 * @param grpcScope the gRPC scope to convert.
	 * @return the corresponding scope.
	 * @throws GenericEvitaInternalError if the conversion cannot be performed.
	 */
	@Nonnull
	public static Scope toScope(@Nonnull GrpcEntityScope grpcScope) {
		return switch (grpcScope) {
			case SCOPE_LIVE -> Scope.LIVE;
			case SCOPE_ARCHIVED -> Scope.ARCHIVED;
			case UNRECOGNIZED -> throw new GenericEvitaInternalError("Unrecognized gRPC scope: " + grpcScope);
		};
	}

	/**
	 * Converts a {@link GrpcTrafficRecordingType} to a {@link TrafficRecordingType}.
	 * @param recordingType the recording type to convert
	 * @return the corresponding traffic recording type
	 */
	@Nonnull
	public static TrafficRecordingType toTrafficRecordingType(@Nonnull GrpcTrafficRecordingType recordingType) {
		return switch (recordingType) {
			case TRAFFIC_RECORDING_SESSION_START -> TrafficRecordingType.SESSION_START;
			case TRAFFIC_RECORDING_SESSION_FINISH -> TrafficRecordingType.SESSION_CLOSE;
			case TRAFFIC_RECORDING_SOURCE_QUERY -> TrafficRecordingType.SOURCE_QUERY;
			case TRAFFIC_RECORDING_SOURCE_QUERY_STATISTICS -> TrafficRecordingType.SOURCE_QUERY_STATISTICS;
			case TRAFFIC_RECORDING_QUERY -> TrafficRecordingType.QUERY;
			case TRAFFIC_RECORDING_ENRICHMENT -> TrafficRecordingType.ENRICHMENT;
			case TRAFFIC_RECORDING_FETCH -> TrafficRecordingType.FETCH;
			case TRAFFIC_RECORDING_MUTATION -> TrafficRecordingType.MUTATION;
			case UNRECOGNIZED -> throw new GenericEvitaInternalError("Unrecognized traffic recording type: " + recordingType);
		};
	}

	/**
	 * Converts a {@link TrafficRecordingType} to a {@link GrpcTrafficRecordingType}.
	 *
	 * @param recordingType the traffic recording type to convert
	 * @return the corresponding gRPC traffic recording type
	 */
	@Nonnull
	public static GrpcTrafficRecordingType toGrpcTrafficRecordingType(@Nonnull TrafficRecordingType recordingType) {
		return switch (recordingType) {
			case SESSION_START -> GrpcTrafficRecordingType.TRAFFIC_RECORDING_SESSION_START;
			case SESSION_CLOSE -> GrpcTrafficRecordingType.TRAFFIC_RECORDING_SESSION_FINISH;
			case SOURCE_QUERY -> GrpcTrafficRecordingType.TRAFFIC_RECORDING_SOURCE_QUERY;
			case SOURCE_QUERY_STATISTICS -> GrpcTrafficRecordingType.TRAFFIC_RECORDING_SOURCE_QUERY_STATISTICS;
			case QUERY -> GrpcTrafficRecordingType.TRAFFIC_RECORDING_QUERY;
			case ENRICHMENT -> GrpcTrafficRecordingType.TRAFFIC_RECORDING_ENRICHMENT;
			case FETCH -> GrpcTrafficRecordingType.TRAFFIC_RECORDING_FETCH;
			case MUTATION -> GrpcTrafficRecordingType.TRAFFIC_RECORDING_MUTATION;
		};
	}

	/**
	 * Converts a given CommitBehavior to its corresponding GrpcTransactionPhase.
	 *
	 * @param commitBehavior the commit behavior to be converted, must not be null
	 * @return the corresponding GrpcTransactionPhase based on the provided commit behavior
	 */
	@Nonnull
	public static GrpcTransactionPhase toGrpcCommitPhase(@Nonnull CommitBehavior commitBehavior) {
		return switch (commitBehavior) {
			case WAIT_FOR_CONFLICT_RESOLUTION -> GrpcTransactionPhase.CONFLICTS_RESOLVED;
			case WAIT_FOR_WAL_PERSISTENCE -> GrpcTransactionPhase.WAL_PERSISTED;
			case WAIT_FOR_CHANGES_VISIBLE -> GrpcTransactionPhase.CHANGES_VISIBLE;
		};
	}

	/**
	 * Converts a {@link GrpcReferenceIndexType} to a {@link ReferenceIndexType}.
	 *
	 * @param grpcReferenceIndexType the gRPC reference index type to convert.
	 * @return the corresponding reference index type.
	 * @throws GenericEvitaInternalError if the conversion cannot be performed.
	 */
	@Nonnull
	public static ReferenceIndexType toReferenceIndexType(@Nonnull GrpcReferenceIndexType grpcReferenceIndexType) {
		return switch (grpcReferenceIndexType) {
			case REFERENCE_INDEX_TYPE_NONE -> ReferenceIndexType.NONE;
			case REFERENCE_INDEX_TYPE_FOR_FILTERING -> ReferenceIndexType.FOR_FILTERING;
			case REFERENCE_INDEX_TYPE_FOR_FILTERING_AND_PARTITIONING -> ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING;
			case UNRECOGNIZED -> throw new GenericEvitaInternalError("Unrecognized gRPC reference index type: " + grpcReferenceIndexType);
		};
	}

	/**
	 * Converts a {@link ReferenceIndexType} to a {@link GrpcReferenceIndexType}.
	 *
	 * @param referenceIndexType the reference index type to convert.
	 * @return the corresponding gRPC reference index type.
	 */
	@Nonnull
	public static GrpcReferenceIndexType toGrpcReferenceIndexType(@Nonnull ReferenceIndexType referenceIndexType) {
		return switch (referenceIndexType) {
			case NONE -> GrpcReferenceIndexType.REFERENCE_INDEX_TYPE_NONE;
			case FOR_FILTERING -> GrpcReferenceIndexType.REFERENCE_INDEX_TYPE_FOR_FILTERING;
			case FOR_FILTERING_AND_PARTITIONING -> GrpcReferenceIndexType.REFERENCE_INDEX_TYPE_FOR_FILTERING_AND_PARTITIONING;
		};
	}
}
