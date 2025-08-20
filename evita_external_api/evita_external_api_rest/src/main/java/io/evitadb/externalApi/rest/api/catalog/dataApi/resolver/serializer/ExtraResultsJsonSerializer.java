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

package io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.dto.QueryTelemetryDto;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetGroupStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetRequestImpactDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.extraResult.LevelInfoDescriptor;
import io.evitadb.externalApi.rest.api.resolver.serializer.ObjectJsonSerializer;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.exception.RestQueryResolvingInternalError;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Handles serializing of Evita extra results into JSON structure
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class ExtraResultsJsonSerializer {

	private final EntityJsonSerializer entityJsonSerializer;
	private final ObjectJsonSerializer objectJsonSerializer;

	public ExtraResultsJsonSerializer(@Nonnull EntityJsonSerializer entityJsonSerializer,
	                                  @Nonnull ObjectMapper objectMapper) {
		this.entityJsonSerializer = entityJsonSerializer;
		this.objectJsonSerializer = new ObjectJsonSerializer(objectMapper);
	}

	/**
	 * Performs serialization and returns extra results entity in form of JsonNode
	 *
	 * @return serialized entity or list of entities
	 */
	@Nonnull
	public JsonNode serialize(@Nonnull Map<Class<? extends EvitaResponseExtraResult>, EvitaResponseExtraResult> extraResults,
	                          @Nonnull EntitySchemaContract resultEntitySchema,
	                          @Nonnull CatalogSchemaContract catalogSchema) {
		final ObjectNode rootNode = this.objectJsonSerializer.objectNode();
		for (EvitaResponseExtraResult extraResult : extraResults.values()) {
			if (extraResult instanceof QueryTelemetry queryTelemetry) {
				rootNode.putIfAbsent(ExtraResultsDescriptor.QUERY_TELEMETRY.name(), serializeQueryTelemetry(queryTelemetry));
			} else if (extraResult instanceof AttributeHistogram attributeHistogram) {
				rootNode.putIfAbsent(ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM.name(), serializeAttributeHistogram(attributeHistogram, resultEntitySchema));
			} else if (extraResult instanceof PriceHistogram priceHistogram) {
				rootNode.putIfAbsent(ExtraResultsDescriptor.PRICE_HISTOGRAM.name(), serializePriceHistogram(priceHistogram));
			} else if (extraResult instanceof Hierarchy hierarchyStats) {
				rootNode.putIfAbsent(ExtraResultsDescriptor.HIERARCHY.name(), serializeHierarchy(hierarchyStats, catalogSchema, resultEntitySchema));
			} else if (extraResult instanceof FacetSummary facetSummary) {
				rootNode.putIfAbsent(ExtraResultsDescriptor.FACET_SUMMARY.name(), serializeFacetSummary(facetSummary, catalogSchema, resultEntitySchema));
			}
		}
		return rootNode;
	}

	@Nonnull
	private JsonNode serializeFacetSummary(@Nonnull FacetSummary facetSummary, @Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaContract entitySchema) {
		final Collection<FacetGroupStatistics> facetGroupStatistics = facetSummary.getReferenceStatistics();
		final Map<String, List<FacetGroupStatistics>> groupedStats = createHashMap(entitySchema.getReferences().size());
		for (FacetGroupStatistics facetGroupStatistic : facetGroupStatistics) {
			if (groupedStats.containsKey(facetGroupStatistic.getReferenceName())) {
				groupedStats.get(facetGroupStatistic.getReferenceName()).add(facetGroupStatistic);
			} else {
				final List<FacetGroupStatistics> groupedByReference = new LinkedList<>();
				groupedByReference.add(facetGroupStatistic);
				groupedStats.put(facetGroupStatistic.getReferenceName(), groupedByReference);
			}
		}

		final ObjectNode facetGroupStatsNode = this.objectJsonSerializer.objectNode();
		groupedStats.forEach((key, value) -> {
			final String serializableReferenceName = entitySchema.getReference(key)
				.map(it -> it.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
				.orElseThrow(() -> new RestQueryResolvingInternalError("Cannot find reference schema for `" + key + "` in entity schema `" + entitySchema.getName() + "`."));

			facetGroupStatsNode.putIfAbsent(
				serializableReferenceName,
				serializeFacetSameGroupStatistics(
					value,
					entitySchema.getReference(key).orElseThrow(() -> new RestInternalError("Could not find referenc schema for `" + key + "`.")),
					catalogSchema
				)
			);
		});
		return facetGroupStatsNode;

	}

	@Nonnull
	private JsonNode serializeFacetSameGroupStatistics(@Nonnull List<FacetGroupStatistics> groupStatistics,
	                                                   @Nonnull ReferenceSchemaContract referenceSchema,
	                                                   @Nonnull CatalogSchemaContract catalogSchema) {
		if (referenceSchema.getReferencedGroupType() != null) {
			final ArrayNode sameGroupStatsNode = this.objectJsonSerializer.arrayNode();
			groupStatistics.forEach(stats -> sameGroupStatsNode.add(serializeFacetGroupStatistics(stats, referenceSchema, catalogSchema)));
			return sameGroupStatsNode;
		} else {
			Assert.isPremiseValid(
				groupStatistics.size() == 1,
				() -> new RestInternalError("There should be only one non-grouped facet group for reference `" + referenceSchema.getName() + "` but found `" + groupStatistics.size() + "`.")
			);
			return serializeFacetGroupStatistics(groupStatistics.get(0), referenceSchema, catalogSchema);
		}
	}

	@Nonnull
	private JsonNode serializeFacetGroupStatistics(@Nonnull FacetGroupStatistics groupStatistics,
	                                               @Nonnull ReferenceSchemaContract referenceSchema,
	                                               @Nonnull CatalogSchemaContract catalogSchema) {
		final ObjectNode groupStatsNode = this.objectJsonSerializer.objectNode();
		groupStatsNode.put(FacetGroupStatisticsDescriptor.COUNT.name(), groupStatistics.getCount());

		if (referenceSchema.getReferencedGroupType() != null) {
			groupStatsNode.putIfAbsent(FacetGroupStatisticsDescriptor.GROUP_ENTITY.name(),
				groupStatistics.getGroupEntity() != null ? serializeEntity(groupStatistics.getGroupEntity(), catalogSchema) : null);
		}

		final ArrayNode jsonNodes = this.objectJsonSerializer.arrayNode();
		groupStatistics.getFacetStatistics().forEach(facetStats -> jsonNodes.add(serializeFacetStatistics(facetStats, catalogSchema)));
		groupStatsNode.putIfAbsent(FacetGroupStatisticsDescriptor.FACET_STATISTICS.name(), jsonNodes);
		return groupStatsNode;
	}

	@Nonnull
	private JsonNode serializeFacetStatistics(@Nonnull FacetStatistics facetStatistics, @Nonnull CatalogSchemaContract catalogSchema) {
		final ObjectNode facetStatsNode = this.objectJsonSerializer.objectNode();
		facetStatsNode.putIfAbsent(FacetStatisticsDescriptor.REQUESTED.name(), this.objectJsonSerializer.serializeObject(facetStatistics.isRequested()));
		facetStatsNode.putIfAbsent(FacetStatisticsDescriptor.COUNT.name(), this.objectJsonSerializer.serializeObject(facetStatistics.getCount()));
		if (facetStatistics.getImpact() != null) {
			final ObjectNode impactNode = this.objectJsonSerializer.objectNode();
			final RequestImpact impact = facetStatistics.getImpact();
			impactNode.putIfAbsent(FacetRequestImpactDescriptor.DIFFERENCE.name(), this.objectJsonSerializer.serializeObject(impact.difference()));
			impactNode.putIfAbsent(FacetRequestImpactDescriptor.MATCH_COUNT.name(), this.objectJsonSerializer.serializeObject(impact.matchCount()));
			impactNode.putIfAbsent(FacetRequestImpactDescriptor.HAS_SENSE.name(), this.objectJsonSerializer.serializeObject(impact.hasSense()));

			facetStatsNode.putIfAbsent(FacetStatisticsDescriptor.IMPACT.name(), impactNode);
		}
		facetStatsNode.putIfAbsent(FacetStatisticsDescriptor.FACET_ENTITY.name(), serializeEntity(facetStatistics.getFacetEntity(), catalogSchema));
		return facetStatsNode;
	}

	@Nonnull
	private JsonNode serializeHierarchy(@Nonnull Hierarchy hierarchy,
	                                    @Nonnull CatalogSchemaContract catalogSchema,
	                                    @Nonnull EntitySchemaContract entitySchema) {
		final ObjectNode hierarchyNode = this.objectJsonSerializer.objectNode();

		final Map<String, List<LevelInfo>> selfHierarchy = hierarchy.getSelfHierarchy();
		if (!selfHierarchy.isEmpty()) {
			hierarchyNode.putIfAbsent(HierarchyDescriptor.SELF.name(), serializeHierarchyOf(selfHierarchy, catalogSchema));
		}

		hierarchy.getReferenceHierarchies().forEach((referenceName, hierarchyOfReference) -> {
			final String serializableReferenceName = entitySchema.getReference(referenceName)
				.map(it -> it.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
				.orElseThrow(() -> new RestQueryResolvingInternalError("Cannot find reference schema for `" + referenceName + "` in entity schema `" + entitySchema.getName() + "`."));

			hierarchyNode.putIfAbsent(serializableReferenceName, serializeHierarchyOf(hierarchyOfReference, catalogSchema));
		});

		return hierarchyNode;
	}

	@Nonnull
	private JsonNode serializeHierarchyOf(@Nonnull Map<String, List<LevelInfo>> hierarchyOf, @Nonnull CatalogSchemaContract catalogSchema) {
		final ObjectNode hierarchyOfNode = this.objectJsonSerializer.objectNode();
		hierarchyOf.forEach((outputName, levelInfos) -> {
			hierarchyOfNode.putIfAbsent(outputName, serializeLevelInfos(levelInfos, catalogSchema));
		});
		return hierarchyOfNode;
	}

	@Nonnull
	private JsonNode serializeLevelInfos(@Nonnull List<LevelInfo> levelInfos, @Nonnull CatalogSchemaContract catalogSchema) {
		final ArrayNode levelInfoNodes = this.objectJsonSerializer.arrayNode();
		for (LevelInfo levelInfo : levelInfos) {
			final ObjectNode levelInfoNode = this.objectJsonSerializer.objectNode();

			levelInfoNode.putIfAbsent(LevelInfoDescriptor.ENTITY.name(), serializeEntity(levelInfo.entity(), catalogSchema));
			levelInfoNode.put(LevelInfoDescriptor.REQUESTED.name(), levelInfo.requested());
			Optional.ofNullable(levelInfo.queriedEntityCount())
				.ifPresent(queriedEntityCount -> levelInfoNode.put(LevelInfoDescriptor.QUERIED_ENTITY_COUNT.name(), queriedEntityCount));
			Optional.ofNullable(levelInfo.childrenCount())
				.ifPresent(childrenCount -> levelInfoNode.put(LevelInfoDescriptor.CHILDREN_COUNT.name(), childrenCount));

			final List<LevelInfo> children = levelInfo.children();
			if (!children.isEmpty()) {
				levelInfoNode.putIfAbsent(LevelInfoDescriptor.CHILDREN.name(), serializeLevelInfos(children, catalogSchema));
			}

			levelInfoNodes.add(levelInfoNode);
		}
		return levelInfoNodes;
	}

	@Nonnull
	private JsonNode serializeEntity(@Nonnull EntityClassifier entityDecorator, @Nonnull CatalogSchemaContract catalogSchema) {
		return this.entityJsonSerializer.serialize(new EntitySerializationContext(catalogSchema), entityDecorator);
	}

	@Nonnull
	private JsonNode serializeQueryTelemetry(@Nonnull QueryTelemetry telemetry) {
		return this.objectJsonSerializer.getObjectMapper().valueToTree(QueryTelemetryDto.from(telemetry));
	}

	@Nonnull
	private JsonNode serializeAttributeHistogram(@Nonnull AttributeHistogram attributeHistogram,
	                                             @Nonnull EntitySchemaContract entitySchema) {
		final ObjectNode histogramNode = this.objectJsonSerializer.objectNode();
		for (Entry<String, HistogramContract> entry : attributeHistogram.getHistograms().entrySet()) {
			final String attributeName = entry.getKey();
			final String serializableAttributeName = entitySchema.getAttribute(attributeName)
				.map(it -> it.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
				.orElseThrow(() -> new RestQueryResolvingInternalError("Cannot find attribute schema for `" + attributeName + "` in entity schema `" + entitySchema.getName() + "`."));

			histogramNode.putIfAbsent(serializableAttributeName, this.objectJsonSerializer.getObjectMapper().valueToTree(entry.getValue()));
		}
		return histogramNode;
	}

	@Nonnull
	private JsonNode serializePriceHistogram(@Nonnull PriceHistogram priceHistogram) {
		return this.objectJsonSerializer.getObjectMapper().valueToTree(priceHistogram);
	}
}
