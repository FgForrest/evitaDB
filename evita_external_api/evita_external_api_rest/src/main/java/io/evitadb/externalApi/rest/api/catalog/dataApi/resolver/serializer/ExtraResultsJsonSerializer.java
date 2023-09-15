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

package io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.RequestImpact;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.externalApi.api.catalog.dataApi.dto.QueryTelemetryDto;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetGroupStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetRequestImpactDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.extraResult.LevelInfoDescriptor;
import io.evitadb.externalApi.rest.api.resolver.serializer.ObjectJsonSerializer;
import io.evitadb.externalApi.rest.exception.RestQueryResolvingInternalError;
import io.evitadb.externalApi.rest.io.RestHandlingContext;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Function;

/**
 * Handles serializing of Evita extra results into JSON structure
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class ExtraResultsJsonSerializer {

	private final EntityJsonSerializer entityJsonSerializer;
	private final ObjectJsonSerializer objectJsonSerializer;

	private final Function<String, String> referenceNameToFieldName;

	public ExtraResultsJsonSerializer(@Nonnull RestHandlingContext restHandlingContext,
	                                  @Nonnull EntityJsonSerializer entityJsonSerializer,
	                                  @Nonnull Function<String, String> referenceNameToFieldName) {
		this.entityJsonSerializer = entityJsonSerializer;
		this.referenceNameToFieldName = referenceNameToFieldName;
		this.objectJsonSerializer = new ObjectJsonSerializer(restHandlingContext.getObjectMapper());
	}

	/**
	 * Performs serialization and returns extra results entity in form of JsonNode
	 *
	 * @return serialized entity or list of entities
	 */
	@Nonnull
	public JsonNode serialize(@Nonnull Map<Class<? extends EvitaResponseExtraResult>, EvitaResponseExtraResult> extraResults) {
		final ObjectNode rootNode = objectJsonSerializer.objectNode();
		for (EvitaResponseExtraResult extraResult : extraResults.values()) {
			if (extraResult instanceof QueryTelemetry queryTelemetry) {
				rootNode.putIfAbsent(ExtraResultsDescriptor.QUERY_TELEMETRY.name(), serializeQueryTelemetry(queryTelemetry));
			} else if (extraResult instanceof AttributeHistogram attributeHistogram) {
				rootNode.putIfAbsent(ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM.name(), serializeAttributeHistogram(attributeHistogram));
			} else if (extraResult instanceof PriceHistogram priceHistogram) {
				rootNode.putIfAbsent(ExtraResultsDescriptor.PRICE_HISTOGRAM.name(), serializePriceHistogram(priceHistogram));
			} else if (extraResult instanceof Hierarchy hierarchyStats) {
				rootNode.putIfAbsent(ExtraResultsDescriptor.HIERARCHY.name(), serializeHierarchy(hierarchyStats));
			} else if (extraResult instanceof FacetSummary facetSummary) {
				rootNode.putIfAbsent(ExtraResultsDescriptor.FACET_SUMMARY.name(), serializeFacetSummary(facetSummary));
			}
		}
		return rootNode;
	}

	@Nonnull
	private JsonNode serializeFacetSummary(@Nonnull FacetSummary facetSummary) {
		final Collection<FacetGroupStatistics> facetGroupStatistics = facetSummary.getReferenceStatistics();
		final HashMap<String, List<FacetGroupStatistics>> groupedStats = new HashMap<>();
		for (FacetGroupStatistics facetGroupStatistic : facetGroupStatistics) {
			if (groupedStats.containsKey(facetGroupStatistic.getReferenceName())) {
				groupedStats.get(facetGroupStatistic.getReferenceName()).add(facetGroupStatistic);
			} else {
				final List<FacetGroupStatistics> groupedByReference = new LinkedList<>();
				groupedByReference.add(facetGroupStatistic);
				groupedStats.put(facetGroupStatistic.getReferenceName(), groupedByReference);
			}
		}

		final ObjectNode facetGroupStatsNode = objectJsonSerializer.objectNode();
		groupedStats.forEach((key, value) -> facetGroupStatsNode.putIfAbsent(StringUtils.toSpecificCase(key, NamingConvention.CAMEL_CASE),
			serializeFacetSameGroupStatistics(value)));
		return facetGroupStatsNode;

	}

	@Nonnull
	private JsonNode serializeFacetSameGroupStatistics(@Nonnull List<FacetGroupStatistics> groupStatistics) {
		final ArrayNode sameGroupStatsNode = objectJsonSerializer.arrayNode();
		groupStatistics.forEach(stats -> sameGroupStatsNode.add(serializeFacetGroupStatistics(stats)));
		return sameGroupStatsNode;
	}

	@Nonnull
	private JsonNode serializeFacetGroupStatistics(@Nonnull FacetGroupStatistics groupStatistics) {
		final ObjectNode groupStatsNode = objectJsonSerializer.objectNode();
		groupStatsNode.put(FacetGroupStatisticsDescriptor.COUNT.name(), groupStatistics.getCount());

		groupStatsNode.putIfAbsent(FacetGroupStatisticsDescriptor.GROUP_ENTITY.name(),
			groupStatistics.getGroupEntity() != null ? serializeEntity(groupStatistics.getGroupEntity()) : null);

		final ArrayNode jsonNodes = objectJsonSerializer.arrayNode();
		groupStatistics.getFacetStatistics().forEach(facetStats -> jsonNodes.add(serializeFacetStatistics(facetStats)));
		groupStatsNode.putIfAbsent(FacetGroupStatisticsDescriptor.FACET_STATISTICS.name(), jsonNodes);
		return groupStatsNode;
	}

	@Nonnull
	private JsonNode serializeFacetStatistics(@Nonnull FacetStatistics facetStatistics) {
		final ObjectNode facetStatsNode = objectJsonSerializer.objectNode();
		facetStatsNode.putIfAbsent(FacetStatisticsDescriptor.REQUESTED.name(), objectJsonSerializer.serializeObject(facetStatistics.isRequested()));
		facetStatsNode.putIfAbsent(FacetStatisticsDescriptor.COUNT.name(), objectJsonSerializer.serializeObject(facetStatistics.getCount()));
		if (facetStatistics.getImpact() != null) {
			final ObjectNode impactNode = objectJsonSerializer.objectNode();
			final RequestImpact impact = facetStatistics.getImpact();
			impactNode.putIfAbsent(FacetRequestImpactDescriptor.DIFFERENCE.name(), objectJsonSerializer.serializeObject(impact.difference()));
			impactNode.putIfAbsent(FacetRequestImpactDescriptor.MATCH_COUNT.name(), objectJsonSerializer.serializeObject(impact.matchCount()));
			impactNode.putIfAbsent(FacetRequestImpactDescriptor.HAS_SENSE.name(), objectJsonSerializer.serializeObject(impact.hasSense()));

			facetStatsNode.putIfAbsent(FacetStatisticsDescriptor.IMPACT.name(), impactNode);
		}
		facetStatsNode.putIfAbsent(FacetStatisticsDescriptor.FACET_ENTITY.name(), serializeEntity(facetStatistics.getFacetEntity()));
		return facetStatsNode;
	}

	@Nonnull
	private JsonNode serializeHierarchy(@Nonnull Hierarchy hierarchy) {
		final ObjectNode hierarchyNode = objectJsonSerializer.objectNode();

		final Map<String, List<LevelInfo>> selfHierarchy = hierarchy.getSelfHierarchy();
		if (!selfHierarchy.isEmpty()) {
			hierarchyNode.putIfAbsent(HierarchyDescriptor.SELF.name(), serializeHierarchyOf(selfHierarchy));
		}

		hierarchy.getReferenceHierarchies().forEach((referenceName, hierarchyOfReference) -> {
			final String fieldName = referenceNameToFieldName.apply(referenceName);
			Assert.isPremiseValid(
				fieldName != null,
				() -> new RestQueryResolvingInternalError("Reference name `" + referenceName + "` is not mapped to any field name.")
			);
			hierarchyNode.putIfAbsent(fieldName, serializeHierarchyOf(hierarchyOfReference));
		});

		return hierarchyNode;
	}

	@Nonnull
	private JsonNode serializeHierarchyOf(@Nonnull Map<String, List<LevelInfo>> hierarchyOf) {
		final ObjectNode hierarchyOfNode = objectJsonSerializer.objectNode();
		hierarchyOf.forEach((outputName, levelInfos) -> {
			hierarchyOfNode.putIfAbsent(outputName, serializeLevelInfos(levelInfos));
		});
		return hierarchyOfNode;
	}

	@Nonnull
	private JsonNode serializeLevelInfos(@Nonnull List<LevelInfo> levelInfos) {
		final ArrayNode levelInfoNodes = objectJsonSerializer.arrayNode();
		for (LevelInfo levelInfo : levelInfos) {
			final ObjectNode levelInfoNode = objectJsonSerializer.objectNode();

			levelInfoNode.putIfAbsent(LevelInfoDescriptor.ENTITY.name(), serializeEntity(levelInfo.entity()));
			Optional.ofNullable(levelInfo.queriedEntityCount())
				.ifPresent(queriedEntityCount -> levelInfoNode.put(LevelInfoDescriptor.QUERIED_ENTITY_COUNT.name(), queriedEntityCount));
			Optional.ofNullable(levelInfo.childrenCount())
				.ifPresent(childrenCount -> levelInfoNode.put(LevelInfoDescriptor.CHILDREN_COUNT.name(), childrenCount));

			final List<LevelInfo> children = levelInfo.children();
			if (!children.isEmpty()) {
				levelInfoNode.putIfAbsent(LevelInfoDescriptor.CHILDREN.name(), serializeLevelInfos(children));
			}

			levelInfoNodes.add(levelInfoNode);
		}
		return levelInfoNodes;
	}

	@Nonnull
	private JsonNode serializeEntity(@Nonnull EntityClassifier entityDecorator) {
		return entityJsonSerializer.serialize(entityDecorator);
	}

	@Nonnull
	private JsonNode serializeQueryTelemetry(@Nonnull QueryTelemetry telemetry) {
		return objectJsonSerializer.getObjectMapper().valueToTree(QueryTelemetryDto.from(telemetry, true));
	}

	@Nonnull
	private JsonNode serializeAttributeHistogram(@Nonnull AttributeHistogram attributeHistogram) {
		final ObjectNode histogramNode = objectJsonSerializer.objectNode();
		for (Entry<String, HistogramContract> entry : attributeHistogram.getHistograms().entrySet()) {
			histogramNode.putIfAbsent(entry.getKey(), objectJsonSerializer.getObjectMapper().valueToTree(entry.getValue()));
		}
		return histogramNode;
	}

	@Nonnull
	private JsonNode serializePriceHistogram(@Nonnull PriceHistogram priceHistogram) {
		return objectJsonSerializer.getObjectMapper().valueToTree(priceHistogram);
	}
}
