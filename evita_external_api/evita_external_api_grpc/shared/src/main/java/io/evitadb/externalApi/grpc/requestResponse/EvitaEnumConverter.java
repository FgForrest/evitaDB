/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.query.filter.AttributeSpecialValue;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
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
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.grpc.generated.*;
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
			default -> throw new EvitaInternalError("Unrecognized remote query price mode: " + grpcQueryPriceMode);
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
			default -> throw new EvitaInternalError("Unrecognized remote price content mode: " + grpcPriceContentMode);
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
			default -> throw new EvitaInternalError("Unrecognized remote order direction: " + grpcOrderDirection);
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
			default -> throw new EvitaInternalError("Unrecognized remote order behaviour: " + grpcOrderBehaviour);
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
				throw new EvitaInternalError("Unrecognized remote empty hierarchical entity behaviour: " + grpcEmptyHierarchicalEntityBehaviour);
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
			default -> throw new EvitaInternalError("Unrecognized remote statistics base: " + grpcStatisticsBase);
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
			default -> throw new EvitaInternalError("Unrecognized remote statistics type: " + grpcStatisticsType);
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
			default -> throw new EvitaInternalError("Unrecognized remote histogram behavior: " + grpcHistogramBehavior);
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
				throw new EvitaInternalError("Unrecognized remote attribute special value: " + grpcAttributeSpecialValue);
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
				throw new EvitaInternalError("Unrecognized remote facet statistics depth: " + grpcFacetStatisticsDepth);
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
				throw new EvitaInternalError("Unrecognized remote price inner record handling: " + grpcPriceInnerRecordHandling);
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
			default -> throw new EvitaInternalError("Unrecognized remote evolution mode: " + grpcEvolutionMode);
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
			default -> throw new EvitaInternalError("Unrecognized remote evolution mode: " + grpcEvolutionMode);
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
			default -> throw new EvitaInternalError("Unrecognized remote query phase: " + grpcQueryPhase);
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
			default -> throw new EvitaInternalError("Unrecognized remote entity existence: " + grpcEntityExistence);
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
			return GrpcAttributeSchemaType.GLOBAL;
		} else if (EntityAttributeSchemaContract.class.isAssignableFrom(attributeSchemaClass)) {
			return GrpcAttributeSchemaType.ENTITY;
		} else if (AttributeSchemaContract.class.isAssignableFrom(attributeSchemaClass)) {
			return GrpcAttributeSchemaType.REFERENCE;
		} else {
			throw new EvitaInternalError("Unrecognized attribute schema type: " + attributeSchemaClass);
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
			default -> throw new EvitaInternalError("Unrecognized remote attribute uniqueness type: " + type);
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
			default -> throw new EvitaInternalError("Unrecognized remote global attribute uniqueness type: " + type);
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
			case WAIT_FOR_INDEX_PROPAGATION -> GrpcCommitBehavior.WAIT_FOR_INDEX_PROPAGATION;
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
			case WAIT_FOR_INDEX_PROPAGATION -> CommitBehavior.WAIT_FOR_INDEX_PROPAGATION;
			default -> throw new EvitaInternalError("Unrecognized remote commit behavior: " + commitBehaviour);
		};
	}

}
