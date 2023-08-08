/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.grpc.requestResponse;

import io.evitadb.api.CatalogState;
import io.evitadb.api.query.filter.AttributeSpecialValue;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.CaptureContent;
import io.evitadb.api.requestResponse.cdc.CaptureSite;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.requestResponse.cdc.CaptureResponseType;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Class contains static methods for converting enums from and to gRPC representation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
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
			default -> throw new EvitaInternalError("Unrecognized remote catalog state: " + grpcCatalogState);
		};
	}

	/**
	 * Converts {@link CatalogState} to {@link GrpcCatalogState}.
	 */
	@Nonnull
	public static GrpcCatalogState toGrpcCatalogState(@Nonnull CatalogState catalogState) {
		return switch (catalogState) {
			case WARMING_UP -> GrpcCatalogState.WARMING_UP;
			case ALIVE -> GrpcCatalogState.ALIVE;
		};
	}


	@Nonnull
	public static QueryPriceMode toQueryPriceMode(@Nonnull GrpcQueryPriceMode grpcQueryPriceMode) {
		return switch (grpcQueryPriceMode.getNumber()) {
			case 0 -> QueryPriceMode.WITH_TAX;
			case 1 -> QueryPriceMode.WITHOUT_TAX;
			default -> throw new EvitaInternalError("Unrecognized remote query price mode: " + grpcQueryPriceMode);
		};
	}

	@Nonnull
	public static GrpcQueryPriceMode toGrpcQueryPriceMode(@Nonnull QueryPriceMode queryPriceMode) {
		return switch (queryPriceMode) {
			case WITH_TAX -> GrpcQueryPriceMode.WITH_TAX;
			case WITHOUT_TAX -> GrpcQueryPriceMode.WITHOUT_TAX;
		};
	}

	@Nonnull
	public static PriceContentMode toPriceContentMode(@Nonnull GrpcPriceContentMode grpcPriceContentMode) {
		return switch (grpcPriceContentMode.getNumber()) {
			case 0 -> PriceContentMode.NONE;
			case 1 -> PriceContentMode.RESPECTING_FILTER;
			case 2 -> PriceContentMode.ALL;
			default -> throw new EvitaInternalError("Unrecognized remote price content mode: " + grpcPriceContentMode);
		};
	}

	@Nonnull
	public static GrpcPriceContentMode toGrpcPriceContentMode(@Nonnull PriceContentMode priceContentMode) {
		return switch (priceContentMode) {
			case NONE -> GrpcPriceContentMode.FETCH_NONE;
			case RESPECTING_FILTER -> GrpcPriceContentMode.RESPECTING_FILTER;
			case ALL -> GrpcPriceContentMode.ALL;
		};
	}

	@Nonnull
	public static OrderDirection toOrderDirection(@Nonnull GrpcOrderDirection grpcOrderDirection) {
		return switch (grpcOrderDirection.getNumber()) {
			case 0 -> OrderDirection.ASC;
			case 1 -> OrderDirection.DESC;
			default -> throw new EvitaInternalError("Unrecognized remote order direction: " + grpcOrderDirection);
		};
	}

	@Nonnull
	public static GrpcOrderDirection toGrpcOrderDirection(@Nonnull OrderDirection orderDirection) {
		return switch (orderDirection) {
			case ASC -> GrpcOrderDirection.ASC;
			case DESC -> GrpcOrderDirection.DESC;
		};
	}

	@Nonnull
	public static OrderBehaviour toOrderBehaviour(@Nonnull GrpcOrderBehaviour grpcOrderBehaviour) {
		return switch (grpcOrderBehaviour.getNumber()) {
			case 0 -> OrderBehaviour.NULLS_FIRST;
			case 1 -> OrderBehaviour.NULLS_LAST;
			default -> throw new EvitaInternalError("Unrecognized remote order behaviour: " + grpcOrderBehaviour);
		};
	}

	@Nonnull
	public static GrpcOrderBehaviour toGrpcOrderBehaviour(@Nonnull OrderBehaviour orderBehaviour) {
		return switch (orderBehaviour) {
			case NULLS_FIRST -> GrpcOrderBehaviour.NULLS_FIRST;
			case NULLS_LAST -> GrpcOrderBehaviour.NULLS_LAST;
		};
	}

	@Nonnull
	public static EmptyHierarchicalEntityBehaviour toEmptyHierarchicalEntityBehaviour(@Nonnull GrpcEmptyHierarchicalEntityBehaviour grpcEmptyHierarchicalEntityBehaviour) {
		return switch (grpcEmptyHierarchicalEntityBehaviour.getNumber()) {
			case 0 -> EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY;
			case 1 -> EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY;
			default ->
				throw new EvitaInternalError("Unrecognized remote empty hierarchical entity behaviour: " + grpcEmptyHierarchicalEntityBehaviour);
		};
	}

	@Nonnull
	public static GrpcEmptyHierarchicalEntityBehaviour toGrpcEmptyHierarchicalEntityBehaviour(@Nonnull EmptyHierarchicalEntityBehaviour emptyHierarchicalEntityBehaviour) {
		return switch (emptyHierarchicalEntityBehaviour) {
			case LEAVE_EMPTY -> GrpcEmptyHierarchicalEntityBehaviour.LEAVE_EMPTY;
			case REMOVE_EMPTY -> GrpcEmptyHierarchicalEntityBehaviour.REMOVE_EMPTY;
		};
	}

	@Nonnull
	public static StatisticsBase toStatisticsBase(@Nonnull GrpcStatisticsBase grpcStatisticsBase) {
		return switch (grpcStatisticsBase.getNumber()) {
			case 0 -> StatisticsBase.COMPLETE_FILTER;
			case 1 -> StatisticsBase.WITHOUT_USER_FILTER;
			default -> throw new EvitaInternalError("Unrecognized remote statistics base: " + grpcStatisticsBase);
		};
	}

	@Nonnull
	public static GrpcStatisticsBase toGrpcStatisticsBase(@Nonnull StatisticsBase statisticsBase) {
		return switch (statisticsBase) {
			case COMPLETE_FILTER -> GrpcStatisticsBase.COMPLETE_FILTER;
			case WITHOUT_USER_FILTER -> GrpcStatisticsBase.WITHOUT_USER_FILTER;
		};
	}

	@Nonnull
	public static StatisticsType toStatisticsType(@Nonnull GrpcStatisticsType grpcStatisticsType) {
		return switch (grpcStatisticsType.getNumber()) {
			case 0 -> StatisticsType.CHILDREN_COUNT;
			case 1 -> StatisticsType.QUERIED_ENTITY_COUNT;
			default -> throw new EvitaInternalError("Unrecognized remote statistics type: " + grpcStatisticsType);
		};
	}

	@Nonnull
	public static GrpcStatisticsType toGrpcStatisticsType(@Nonnull StatisticsType statisticsType) {
		return switch (statisticsType) {
			case CHILDREN_COUNT -> GrpcStatisticsType.CHILDREN_COUNT;
			case QUERIED_ENTITY_COUNT -> GrpcStatisticsType.QUERIED_ENTITY_COUNT;
		};
	}

	@Nonnull
	public static AttributeSpecialValue toAttributeSpecialValue(@Nonnull GrpcAttributeSpecialValue grpcAttributeSpecialValue) {
		return switch (grpcAttributeSpecialValue.getNumber()) {
			case 0 -> AttributeSpecialValue.NULL;
			case 1 -> AttributeSpecialValue.NOT_NULL;
			default ->
				throw new EvitaInternalError("Unrecognized remote attribute special value: " + grpcAttributeSpecialValue);
		};
	}

	@Nonnull
	public static GrpcAttributeSpecialValue toGrpcAttributeSpecialValue(@Nonnull AttributeSpecialValue attributeSpecialValue) {
		return switch (attributeSpecialValue) {
			case NULL -> GrpcAttributeSpecialValue.NULL;
			case NOT_NULL -> GrpcAttributeSpecialValue.NOT_NULL;
		};
	}

	@Nonnull
	public static FacetStatisticsDepth toFacetStatisticsDepth(@Nonnull GrpcFacetStatisticsDepth grpcFacetStatisticsDepth) {
		return switch (grpcFacetStatisticsDepth.getNumber()) {
			case 0 -> FacetStatisticsDepth.COUNTS;
			case 1 -> FacetStatisticsDepth.IMPACT;
			default ->
				throw new EvitaInternalError("Unrecognized remote facet statistics depth: " + grpcFacetStatisticsDepth);
		};
	}

	@Nonnull
	public static GrpcFacetStatisticsDepth toGrpcFacetStatisticsDepth(@Nonnull FacetStatisticsDepth facetStatisticsDepth) {
		return switch (facetStatisticsDepth) {
			case COUNTS -> GrpcFacetStatisticsDepth.COUNTS;
			case IMPACT -> GrpcFacetStatisticsDepth.IMPACT;
		};
	}

	@Nonnull
	public static PriceInnerRecordHandling toPriceInnerRecordHandling(@Nonnull GrpcPriceInnerRecordHandling grpcPriceInnerRecordHandling) {
		return switch (grpcPriceInnerRecordHandling.getNumber()) {
			case 0 -> PriceInnerRecordHandling.NONE;
			case 1 -> PriceInnerRecordHandling.FIRST_OCCURRENCE;
			case 2 -> PriceInnerRecordHandling.SUM;
			case 3 -> PriceInnerRecordHandling.UNKNOWN;
			default ->
				throw new EvitaInternalError("Unrecognized remote price inner record handling: " + grpcPriceInnerRecordHandling);
		};
	}

	@Nonnull
	public static GrpcPriceInnerRecordHandling toGrpcPriceInnerRecordHandling(@Nonnull PriceInnerRecordHandling priceInnerRecordHandling) {
		return switch (priceInnerRecordHandling) {
			case NONE -> GrpcPriceInnerRecordHandling.NONE;
			case FIRST_OCCURRENCE -> GrpcPriceInnerRecordHandling.FIRST_OCCURRENCE;
			case SUM -> GrpcPriceInnerRecordHandling.SUM;
			case UNKNOWN -> GrpcPriceInnerRecordHandling.UNKNOWN;
		};
	}

	@Nullable
	public static Cardinality toCardinality(@Nonnull GrpcCardinality grpcCardinality) {
		return switch (grpcCardinality.getNumber()) {
			case 0 -> null;
			case 1 -> Cardinality.ZERO_OR_ONE;
			case 2 -> Cardinality.EXACTLY_ONE;
			case 3 -> Cardinality.ZERO_OR_MORE;
			case 4 -> Cardinality.ONE_OR_MORE;
			default -> throw new EvitaInternalError("Unrecognized remote cardinality: " + grpcCardinality);
		};
	}

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

	@Nonnull
	public static CatalogEvolutionMode toCatalogEvolutionMode(@Nonnull GrpcCatalogEvolutionMode grpcEvolutionMode) {
		return switch (grpcEvolutionMode.getNumber()) {
			case 0 -> CatalogEvolutionMode.ADDING_ENTITY_TYPES;
			default -> throw new EvitaInternalError("Unrecognized remote evolution mode: " + grpcEvolutionMode);
		};
	}

	@Nonnull
	public static GrpcCatalogEvolutionMode toGrpcCatalogEvolutionMode(@Nonnull CatalogEvolutionMode evolutionMode) {
		return switch (evolutionMode) {
			case ADDING_ENTITY_TYPES -> GrpcCatalogEvolutionMode.ADDING_ENTITY_TYPES;
		};
	}

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
			default -> throw new EvitaInternalError("Unrecognized remote evolution mode: " + grpcEvolutionMode);
		};
	}

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
			default -> throw new EvitaInternalError("Unrecognized remote query phase: " + grpcQueryPhase);
		};
	}

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

	@Nonnull
	public static EntityExistence toEntityExistence(@Nonnull GrpcEntityExistence grpcEntityExistence) {
		return switch (grpcEntityExistence.getNumber()) {
			case 0 -> EntityExistence.MAY_EXIST;
			case 1 -> EntityExistence.MUST_NOT_EXIST;
			case 2 -> EntityExistence.MUST_EXIST;
			default -> throw new EvitaInternalError("Unrecognized remote entity existence: " + grpcEntityExistence);
		};
	}

	@Nonnull
	public static GrpcEntityExistence toGrpcEntityExistence(@Nonnull EntityExistence entityExistence) {
		return switch (entityExistence) {
			case MAY_EXIST -> GrpcEntityExistence.MAY_EXIST;
			case MUST_NOT_EXIST -> GrpcEntityExistence.MUST_NOT_EXIST;
			case MUST_EXIST -> GrpcEntityExistence.MUST_EXIST;
		};
	}

	@Nonnull
	public static CaptureContent toCaptureContent(@Nonnull GrpcCaptureContent grpcCaptureContent) {
		return switch (grpcCaptureContent.getNumber()) {
			case 0 -> CaptureContent.HEADER;
			case 1 -> CaptureContent.BODY;
			default -> throw new EvitaInternalError("Unrecognized remote capture content: " + grpcCaptureContent);
		};
	}

	@Nonnull
	public static GrpcCaptureContent toGrpcCaptureContent(@Nonnull CaptureContent captureContent) {
		return switch (captureContent) {
			case HEADER -> GrpcCaptureContent.HEADER;
			case BODY -> GrpcCaptureContent.BODY;
		};
	}

	@Nonnull
	public static Operation toOperation(@Nonnull GrpcOperation grpcOperation) {
		return switch (grpcOperation.getNumber()) {
			case 0 -> Operation.CREATE;
			case 1 -> Operation.UPDATE;
			case 2 -> Operation.REMOVE;
			default -> throw new EvitaInternalError("Unrecognized remote operation: " + grpcOperation);
		};
	}

	@Nonnull
	public static GrpcOperation toGrpcOperation(@Nonnull Operation operation) {
		return switch (operation) {
			case CREATE -> GrpcOperation.CREATE;
			case UPDATE -> GrpcOperation.UPDATE;
			case REMOVE -> GrpcOperation.REMOVE;
		};
	}

	@Nonnull
	public static ClassifierType toClassifierType(@Nonnull GrpcClassifierType grpcClassifierType) {
		return switch (grpcClassifierType.getNumber()) {
			case 0 -> ClassifierType.CATALOG;
			case 1 -> ClassifierType.ENTITY;
			case 2 -> ClassifierType.ATTRIBUTE;
			case 3 -> ClassifierType.ASSOCIATED_DATA;
			case 4 -> ClassifierType.REFERENCE;
			case 5 -> ClassifierType.REFERENCE_ATTRIBUTE;
			default -> throw new EvitaInternalError("Unrecognized remote classifier type: " + grpcClassifierType);
		};
	}

	@Nonnull
	public static GrpcClassifierType toGrpcClassifierType(@Nonnull ClassifierType classifierType) {
		return switch (classifierType) {
			case CATALOG -> GrpcClassifierType.CATALOG;
			case ENTITY -> GrpcClassifierType.ENTITY;
			case ATTRIBUTE -> GrpcClassifierType.ATTRIBUTE;
			case ASSOCIATED_DATA -> GrpcClassifierType.ASSOCIATED_DATA;
			case REFERENCE -> GrpcClassifierType.REFERENCE;
			case REFERENCE_ATTRIBUTE -> GrpcClassifierType.REFERENCE_ATTRIBUTE;
		};
	}

	@Nonnull
	public static CaptureArea toCaptureArea(@Nonnull GrpcCaptureArea grpcCaptureArea) {
		return switch (grpcCaptureArea.getNumber()) {
			case 0 -> CaptureArea.SCHEMA;
			case 1 -> CaptureArea.DATA;
			default -> throw new EvitaInternalError("Unrecognized remote capture area: " + grpcCaptureArea);
		};
	}

	@Nonnull
	public static GrpcCaptureArea toGrpcCaptureArea(@Nonnull CaptureArea captureArea) {
		return switch (captureArea) {
			case SCHEMA -> GrpcCaptureArea.SCHEMA;
			case DATA -> GrpcCaptureArea.DATA;
		};
	}

	@Nonnull
	public static CaptureResponseType toCaptureResponseType(@Nonnull GrpcCaptureResponseType grpcCaptureResponseType) {
		return switch (grpcCaptureResponseType.getNumber()) {
			case 0 -> CaptureResponseType.ACKNOWLEDGEMENT;
			case 1 -> CaptureResponseType.CHANGE;
			default -> throw new EvitaInternalError("Unrecognized remote capture area: " + grpcCaptureResponseType);
		};
	}

	@Nonnull
	public static GrpcCaptureResponseType toGrpcCaptureResponseType(@Nonnull CaptureResponseType captureResponseType) {
		return switch (captureResponseType) {
			case ACKNOWLEDGEMENT -> GrpcCaptureResponseType.ACKNOWLEDGEMENT;
			case CHANGE -> GrpcCaptureResponseType.CHANGE;
		};
	}
}
