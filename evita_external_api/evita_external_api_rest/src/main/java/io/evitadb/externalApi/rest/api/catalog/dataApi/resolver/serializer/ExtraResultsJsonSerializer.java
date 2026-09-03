/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.FacetSummaryOfReference;
import io.evitadb.api.query.require.HierarchyOfReference;
import io.evitadb.api.query.require.HierarchyOfSelf;
import io.evitadb.api.query.require.HierarchyRequireConstraint;
import io.evitadb.api.query.require.ReferenceHistogramStatistics;
import io.evitadb.api.query.require.ReferenceSummaryOfReference;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.RequestImpact;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.dto.QueryTelemetryDto;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetGroupStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ReferenceHistogramDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ReferenceSummaryDescriptor.EntityFacetStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ReferenceSummaryDescriptor.FacetRequestImpactDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ReferenceSummaryDescriptor.ReferenceGroupStatisticsDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.extraResult.LevelInfoDescriptor;
import io.evitadb.externalApi.rest.api.resolver.serializer.ObjectJsonSerializer;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.exception.RestQueryResolvingInternalError;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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

	public ExtraResultsJsonSerializer(
		@Nonnull EntityJsonSerializer entityJsonSerializer,
		@Nonnull ObjectMapper objectMapper
	) {
		this.entityJsonSerializer = entityJsonSerializer;
		this.objectJsonSerializer = new ObjectJsonSerializer(objectMapper);
	}

	/**
	 * Performs serialization and returns extra results entity in form of JsonNode
	 *
	 * @return serialized entity or list of entities
	 */
	@Nonnull
	public JsonNode serialize(
		@Nonnull Query query,
		@Nonnull Map<Class<? extends EvitaResponseExtraResult>, EvitaResponseExtraResult> extraResults,
		@Nonnull EntitySchemaContract resultEntitySchema,
		@Nonnull CatalogSchemaContract catalogSchema
	) {
		final ObjectNode rootNode = this.objectJsonSerializer.objectNode();
		for (EvitaResponseExtraResult extraResult : extraResults.values()) {
			if (extraResult instanceof QueryTelemetry queryTelemetry) {
				rootNode.putIfAbsent(
					ExtraResultsDescriptor.QUERY_TELEMETRY.name(), serializeQueryTelemetry(queryTelemetry));
			} else if (extraResult instanceof AttributeHistogram attributeHistogram) {
				rootNode.putIfAbsent(
					ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM.name(),
					serializeAttributeHistogram(attributeHistogram, resultEntitySchema, catalogSchema)
				);
			} else if (extraResult instanceof PriceHistogram priceHistogram) {
				rootNode.putIfAbsent(
					ExtraResultsDescriptor.PRICE_HISTOGRAM.name(),
					// a price histogram carries no boundary entities, so the context they would be written under is
					// never consulted and there is no requirement to look up for it
					serializeHistogram(priceHistogram, new EntitySerializationContext(catalogSchema))
				);
			} else if (extraResult instanceof Hierarchy hierarchyStats) {
				rootNode.putIfAbsent(
					ExtraResultsDescriptor.HIERARCHY.name(),
					serializeHierarchy(hierarchyStats, query, catalogSchema, resultEntitySchema)
				);
			} else if (extraResult instanceof ReferenceSummary referenceSummary) {
				// matches both the canonical ReferenceSummary and its deprecated FacetSummary subclass; the
				// downstream branch below picks the right output field based on which require constraint was
				// actually used in the request
				final Require require = query.getRequire();
				if (require == null) {
					throw new RestQueryResolvingInternalError(
						"Reference summary / Facet summary extra result is present but require constraint is not present in query.",
						"Internal error during reference summary / facet summary extra result serialization."
					);
				}

				if (referenceSummary instanceof FacetSummary facetSummary) {
					// TOBEDONE remove this branch when FacetSummary DTO is removed; only the else path stays
					final io.evitadb.api.query.require.FacetSummary facetSummaryRequire = QueryUtils.findConstraint(
						require,
						io.evitadb.api.query.require.FacetSummary.class
					);
					final List<FacetSummaryOfReference> facetSummaryOfReferencesRequire = QueryUtils.findConstraints(
						require,
						FacetSummaryOfReference.class
					);
					if (facetSummaryRequire != null || !facetSummaryOfReferencesRequire.isEmpty()) {
						rootNode.putIfAbsent(
							ExtraResultsDescriptor.FACET_SUMMARY.name(),
							serializeFacetSummary(facetSummary, require, catalogSchema, resultEntitySchema)
						);
						continue;
					}
				}
				rootNode.putIfAbsent(
					ExtraResultsDescriptor.REFERENCE_SUMMARY.name(),
					serializeReferenceSummary(referenceSummary, require, catalogSchema, resultEntitySchema)
				);
			}
		}
		return rootNode;
	}

	/**
	 * Serializes the reference summary extra result.
	 *
	 * Every reference's statistics are serialized under the requirements its entities were actually fetched with -
	 * the `entityFetch` / `entityGroupFetch` written inside the `referenceSummary` requirements covering that
	 * reference - so that the entity serializer reads what the caller asked for instead of guessing it back from the
	 * returned entities. The query's top-level `entityFetch` is deliberately not consulted: it describes the queried
	 * collection, while a summary reports entities of the referenced one.
	 *
	 * @param referenceSummary the computed summary to serialize
	 * @param require          the require part of the query the summary was computed for
	 * @param catalogSchema    the schema of the catalog the reported entities belong to
	 * @param entitySchema     the schema of the queried collection
	 * @return the serialized reference summary
	 */
	@Nonnull
	private JsonNode serializeReferenceSummary(
		@Nonnull ReferenceSummary referenceSummary,
		@Nonnull Require require,
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaContract entitySchema
	) {
		final Collection<? extends ReferenceGroupStatistics> referenceGroupStatistics =
			referenceSummary.getReferenceStatistics();
		final Map<String, List<ReferenceGroupStatistics>> groupedStats = createHashMap(
			entitySchema.getReferences().size());
		for (ReferenceGroupStatistics referenceGroupStatistic : referenceGroupStatistics) {
			if (groupedStats.containsKey(referenceGroupStatistic.getReferenceName())) {
				groupedStats.get(referenceGroupStatistic.getReferenceName()).add(referenceGroupStatistic);
			} else {
				final List<ReferenceGroupStatistics> groupedByReference = new LinkedList<>();
				groupedByReference.add(referenceGroupStatistic);
				groupedStats.put(referenceGroupStatistic.getReferenceName(), groupedByReference);
			}
		}

		final ObjectNode referenceGroupStatsNode = this.objectJsonSerializer.objectNode();
		groupedStats.forEach((key, value) -> {
			final String serializableReferenceName = entitySchema.getReference(key)
				.map(it -> it.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
				.orElseThrow(() -> new RestQueryResolvingInternalError(
					"Cannot find reference schema for `" + key + "` in entity schema `" + entitySchema.getName() + "`."
				));

			referenceGroupStatsNode.putIfAbsent(
				serializableReferenceName,
				serializeReferenceSameGroupStatistics(
					value,
					entitySchema.getReference(key)
						.orElseThrow(() -> new RestInternalError("Could not find referenc schema for `" + key + "`.")),
					referenceSummaryContexts(require, key, catalogSchema)
				)
			);
		});
		return referenceGroupStatsNode;
	}

	@Nonnull
	private JsonNode serializeReferenceSameGroupStatistics(
		@Nonnull List<ReferenceGroupStatistics> groupStatistics,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceSummaryContexts serializationContexts
	) {
		if (referenceSchema.getReferencedGroupType() != null) {
			final ArrayNode sameGroupStatsNode = this.objectJsonSerializer.arrayNode();
			groupStatistics
				.forEach(
					stats ->
						sameGroupStatsNode.add(
							serializeReferenceGroupStatistics(
								stats,
								referenceSchema,
								serializationContexts
							)
						)
				);
			return sameGroupStatsNode;
		} else {
			Assert.isPremiseValid(
				groupStatistics.size() == 1,
				() -> new RestInternalError(
					"There should be only one non-grouped facet group for reference `" +
						referenceSchema.getName() + "` but found `" + groupStatistics.size() + "`."
				)
			);
			return serializeReferenceGroupStatistics(groupStatistics.get(0), referenceSchema, serializationContexts);
		}
	}

	@Nonnull
	private JsonNode serializeReferenceGroupStatistics(
		@Nonnull ReferenceGroupStatistics groupStatistics,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceSummaryContexts serializationContexts
	) {
		final ObjectNode groupStatsNode = this.objectJsonSerializer.objectNode();
		groupStatsNode.put(ReferenceGroupStatisticsDescriptor.COUNT.name(), groupStatistics.getCount());

		if (referenceSchema.getReferencedGroupType() != null) {
			final EntityClassifier groupEntity = groupStatistics.getGroupEntity();
			groupStatsNode.putIfAbsent(
				ReferenceGroupStatisticsDescriptor.GROUP_ENTITY.name(),
				groupEntity != null
					? this.entityJsonSerializer.serialize(serializationContexts.groupEntity(), groupEntity)
					: null
			);
		}

		final ArrayNode jsonNodes = this.objectJsonSerializer.arrayNode();
		groupStatistics
			.getFacetStatistics()
			.forEach(
				facetStats ->
					jsonNodes.add(
						serializeFacetStatistics(
							facetStats,
							serializationContexts.referencedEntity()
						)
					)
			);
		groupStatsNode.putIfAbsent(ReferenceGroupStatisticsDescriptor.FACET_STATISTICS.name(), jsonNodes);

		// serialize named histogram statistics wrapped under a `histogramStatistics` object
		final Map<String, HistogramContract> histogramStats = groupStatistics.getHistogramStatistics();
		if (!histogramStats.isEmpty()) {
			final ObjectNode histogramStatisticsNode = this.objectJsonSerializer.objectNode();
			for (Entry<String, HistogramContract> entry : histogramStats.entrySet()) {
				histogramStatisticsNode.putIfAbsent(
					entry.getKey(),
					serializeHistogram(
						entry.getValue(), serializationContexts.forHistogramAnchor(entry.getKey())
					)
				);
			}
			groupStatsNode.putIfAbsent(
				ReferenceGroupStatisticsDescriptor.HISTOGRAM_STATISTICS.name(),
				histogramStatisticsNode
			);
		}
		return groupStatsNode;
	}

	/**
	 * Serializes a histogram — attribute, price or reference-scope — using explicit field emission
	 * so Jackson's bean reflection never walks the `Optional<SealedEntity>` anchor getters (which
	 * `jackson-databind` rejects without the `jdk8` datatype module). Anchor referenced entities are
	 * only emitted when present, so attribute / price histograms (which never carry them) produce
	 * the same shape they did before the anchors were introduced.
	 *
	 * @param histogram     the histogram to serialize
	 * @param anchorContext the context the anchor referenced entities are written under - it carries the requirement
	 *                      they were fetched with, which is the histogram's own `entityFetch` rather than the one
	 *                      the surrounding summary fetched its facets with
	 * @return the serialized histogram
	 */
	@Nonnull
	private JsonNode serializeHistogram(
		@Nonnull HistogramContract histogram,
		@Nonnull EntitySerializationContext anchorContext
	) {
		final ObjectNode histogramNode = this.objectJsonSerializer.objectNode();
		histogramNode.putIfAbsent(
			HistogramDescriptor.MIN.name(),
			this.objectJsonSerializer.serializeObject(histogram.getMin())
		);
		histogramNode.putIfAbsent(
			HistogramDescriptor.MAX.name(),
			this.objectJsonSerializer.serializeObject(histogram.getMax())
		);
		histogramNode.put(HistogramDescriptor.OVERALL_COUNT.name(), histogram.getOverallCount());
		histogramNode.putIfAbsent(
			HistogramDescriptor.BUCKETS.name(),
			this.objectJsonSerializer.getObjectMapper().valueToTree(histogram.getBuckets())
		);
		histogram.getMinReferencedEntity().ifPresent(entity -> histogramNode.putIfAbsent(
			ReferenceHistogramDescriptor.MIN_REFERENCED_ENTITY.name(),
			this.entityJsonSerializer.serialize(anchorContext, entity)
		));
		histogram.getMaxReferencedEntity().ifPresent(entity -> histogramNode.putIfAbsent(
			ReferenceHistogramDescriptor.MAX_REFERENCED_ENTITY.name(),
			this.entityJsonSerializer.serialize(anchorContext, entity)
		));
		return histogramNode;
	}

	/**
	 * Serializes the deprecated facet summary extra result.
	 *
	 * Reads the requirements its entities were fetched with off the `facetSummary` requirements the same way
	 * {@link #serializeReferenceSummary(ReferenceSummary, Require, CatalogSchemaContract, EntitySchemaContract)}
	 * reads them off the `referenceSummary` ones.
	 *
	 * @param facetSummary  the computed summary to serialize
	 * @param require       the require part of the query the summary was computed for
	 * @param catalogSchema the schema of the catalog the reported entities belong to
	 * @param entitySchema  the schema of the queried collection
	 * @return the serialized facet summary
	 */
	@Nonnull
	private JsonNode serializeFacetSummary(
		@Nonnull FacetSummary facetSummary,
		@Nonnull Require require,
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaContract entitySchema
	) {
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
				.orElseThrow(() -> new RestQueryResolvingInternalError(
					"Cannot find reference schema for `" + key + "` in entity schema `" + entitySchema.getName() + "`."
				));

			facetGroupStatsNode.putIfAbsent(
				serializableReferenceName,
				serializeFacetSameGroupStatistics(
					value,
					entitySchema.getReference(key)
						.orElseThrow(() -> new RestInternalError("Could not find referenc schema for `" + key + "`.")),
					facetSummaryContexts(require, key, catalogSchema)
				)
			);
		});
		return facetGroupStatsNode;
	}


	@Nonnull
	private JsonNode serializeFacetSameGroupStatistics(
		@Nonnull List<FacetGroupStatistics> groupStatistics,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceSummaryContexts serializationContexts
	) {
		if (referenceSchema.getReferencedGroupType() != null) {
			final ArrayNode sameGroupStatsNode = this.objectJsonSerializer.arrayNode();
			groupStatistics.forEach(stats ->
				sameGroupStatsNode.add(
					serializeFacetGroupStatistics(stats, referenceSchema, serializationContexts))
			);
			return sameGroupStatsNode;
		} else {
			Assert.isPremiseValid(
				groupStatistics.size() == 1,
				() -> new RestInternalError(
					"There should be only one non-grouped facet group for reference `" +
						referenceSchema.getName() + "` but found `" + groupStatistics.size() + "`."
				)
			);
			return serializeFacetGroupStatistics(groupStatistics.get(0), referenceSchema, serializationContexts);
		}
	}

	@Nonnull
	private JsonNode serializeFacetGroupStatistics(
		@Nonnull FacetGroupStatistics groupStatistics,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferenceSummaryContexts serializationContexts
	) {
		final ObjectNode groupStatsNode = this.objectJsonSerializer.objectNode();
		groupStatsNode.put(FacetGroupStatisticsDescriptor.COUNT.name(), groupStatistics.getCount());

		if (referenceSchema.getReferencedGroupType() != null) {
			final EntityClassifier groupEntity = groupStatistics.getGroupEntity();
			groupStatsNode.putIfAbsent(
				FacetGroupStatisticsDescriptor.GROUP_ENTITY.name(),
				groupEntity != null
					? this.entityJsonSerializer.serialize(serializationContexts.groupEntity(), groupEntity)
					: null
			);
		}

		final ArrayNode jsonNodes = this.objectJsonSerializer.arrayNode();
		groupStatistics.getFacetStatistics().forEach(facetStats ->
			jsonNodes.add(
				serializeFacetStatistics(facetStats, serializationContexts.referencedEntity()))
		);
		groupStatsNode.putIfAbsent(FacetGroupStatisticsDescriptor.FACET_STATISTICS.name(), jsonNodes);
		return groupStatsNode;
	}

	@Nonnull
	private JsonNode serializeFacetStatistics(
		@Nonnull FacetStatistics facetStatistics,
		@Nonnull EntitySerializationContext facetEntityContext
	) {
		final ObjectNode facetStatsNode = this.objectJsonSerializer.objectNode();
		facetStatsNode.putIfAbsent(
			EntityFacetStatisticsDescriptor.REQUESTED.name(),
			this.objectJsonSerializer.serializeObject(facetStatistics.isRequested())
		);
		facetStatsNode.putIfAbsent(
			EntityFacetStatisticsDescriptor.COUNT.name(),
			this.objectJsonSerializer.serializeObject(facetStatistics.getCount())
		);
		if (facetStatistics.getImpact() != null) {
			final ObjectNode impactNode = this.objectJsonSerializer.objectNode();
			final RequestImpact impact = facetStatistics.getImpact();
			impactNode.putIfAbsent(
				FacetRequestImpactDescriptor.DIFFERENCE.name(),
				this.objectJsonSerializer.serializeObject(impact.difference())
			);
			impactNode.putIfAbsent(
				FacetRequestImpactDescriptor.MATCH_COUNT.name(),
				this.objectJsonSerializer.serializeObject(impact.matchCount())
			);
			impactNode.putIfAbsent(
				FacetRequestImpactDescriptor.HAS_SENSE.name(),
				this.objectJsonSerializer.serializeObject(impact.hasSense())
			);

			facetStatsNode.putIfAbsent(EntityFacetStatisticsDescriptor.IMPACT.name(), impactNode);
		}
		facetStatsNode.putIfAbsent(
			EntityFacetStatisticsDescriptor.FACET_ENTITY.name(),
			this.entityJsonSerializer.serialize(facetEntityContext, facetStatistics.getFacetEntity())
		);
		return facetStatsNode;
	}

	/**
	 * Serializes the hierarchy statistics extra result.
	 *
	 * Every hierarchy is serialized under the requirement its nodes were actually fetched with - the `entityFetch`
	 * written inside the hierarchy constraint that produced it - so that the entity serializer reads what the caller
	 * asked for instead of guessing it back from the returned nodes. The query's top-level `entityFetch` is
	 * deliberately not consulted: it describes a possibly different collection, and a statistics tree is routinely
	 * asked for by a query that returns nothing but entity references.
	 *
	 * @param hierarchy     the computed hierarchies to serialize
	 * @param query         the query the hierarchies were computed for
	 * @param catalogSchema the schema of the catalog the nodes belong to
	 * @param entitySchema  the schema of the queried collection
	 * @return the serialized hierarchy statistics
	 */
	@Nonnull
	private JsonNode serializeHierarchy(
		@Nonnull Hierarchy hierarchy,
		@Nonnull Query query,
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaContract entitySchema
	) {
		final ObjectNode hierarchyNode = this.objectJsonSerializer.objectNode();

		final Map<String, List<LevelInfo>> selfHierarchy = hierarchy.getSelfHierarchy();
		if (!selfHierarchy.isEmpty()) {
			hierarchyNode.putIfAbsent(
				HierarchyDescriptor.SELF.name(),
				serializeHierarchyOf(
					selfHierarchy, QueryUtils.findRequires(query, HierarchyOfSelf.class), catalogSchema
				)
			);
		}

		hierarchy.getReferenceHierarchies().forEach((referenceName, hierarchyOfReference) -> {
			final String serializableReferenceName = entitySchema.getReference(referenceName)
				.map(it -> it.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
				.orElseThrow(() -> new RestQueryResolvingInternalError(
					"Cannot find reference schema for `" + referenceName + "` in entity schema `" + entitySchema.getName() + "`."
				));

			hierarchyNode.putIfAbsent(
				serializableReferenceName,
				serializeHierarchyOf(
					hierarchyOfReference, findHierarchiesOfReference(query, referenceName), catalogSchema
				)
			);
		});

		return hierarchyNode;
	}

	/**
	 * Serializes one hierarchy - the queried collection's own or one of a reference - output name by output name.
	 *
	 * @param hierarchyOf          the computed hierarchies, keyed by the output name of the requirement producing them
	 * @param hierarchyConstraints the constraints the hierarchies were computed for, empty when the query carries none
	 * @param catalogSchema        the schema of the catalog the nodes belong to
	 * @return the serialized hierarchy
	 */
	@Nonnull
	private JsonNode serializeHierarchyOf(
		@Nonnull Map<String, List<LevelInfo>> hierarchyOf,
		@Nonnull List<? extends RequireConstraint> hierarchyConstraints,
		@Nonnull CatalogSchemaContract catalogSchema
	) {
		final ObjectNode hierarchyOfNode = this.objectJsonSerializer.objectNode();
		hierarchyOf.forEach((outputName, levelInfos) -> {
			final EntitySerializationContext serializationContext = new EntitySerializationContext(
				catalogSchema, findNodeRequirement(hierarchyConstraints, outputName)
			);
			hierarchyOfNode.putIfAbsent(outputName, serializeLevelInfos(levelInfos, serializationContext));
		});
		return hierarchyOfNode;
	}

	@Nonnull
	private JsonNode serializeLevelInfos(
		@Nonnull List<LevelInfo> levelInfos,
		@Nonnull EntitySerializationContext serializationContext
	) {
		final ArrayNode levelInfoNodes = this.objectJsonSerializer.arrayNode();
		for (LevelInfo levelInfo : levelInfos) {
			final ObjectNode levelInfoNode = this.objectJsonSerializer.objectNode();

			levelInfoNode.putIfAbsent(
				LevelInfoDescriptor.ENTITY.name(),
				this.entityJsonSerializer.serialize(serializationContext, levelInfo.entity())
			);
			levelInfoNode.put(LevelInfoDescriptor.REQUESTED.name(), levelInfo.requested());
			Optional.ofNullable(levelInfo.queriedEntityCount())
				.ifPresent(queriedEntityCount ->
					           levelInfoNode.put(LevelInfoDescriptor.QUERIED_ENTITY_COUNT.name(), queriedEntityCount)
				);
			Optional.ofNullable(levelInfo.childrenCount())
				.ifPresent(
					childrenCount -> levelInfoNode.put(LevelInfoDescriptor.CHILDREN_COUNT.name(), childrenCount));

			final List<LevelInfo> children = levelInfo.children();
			if (!children.isEmpty()) {
				levelInfoNode.putIfAbsent(
					LevelInfoDescriptor.CHILDREN.name(), serializeLevelInfos(children, serializationContext));
			}

			levelInfoNodes.add(levelInfoNode);
		}
		return levelInfoNodes;
	}

	/**
	 * Returns every `hierarchyOfReference` requirement of `query` that covers `referenceName` - a requirement naming
	 * several references covers each of them.
	 *
	 * @param query         the query the hierarchies were computed for
	 * @param referenceName name of the reference the hierarchy was computed for
	 * @return the requirements covering the reference, empty when the query carries none
	 */
	@Nonnull
	private static List<HierarchyOfReference> findHierarchiesOfReference(
		@Nonnull Query query,
		@Nonnull String referenceName
	) {
		final List<HierarchyOfReference> coveringConstraints = new LinkedList<>();
		for (final HierarchyOfReference candidate : QueryUtils.findRequires(query, HierarchyOfReference.class)) {
			for (final String candidateReferenceName : candidate.getReferenceNames()) {
				if (candidateReferenceName.equals(referenceName)) {
					coveringConstraints.add(candidate);
					break;
				}
			}
		}
		return coveringConstraints;
	}

	/**
	 * Returns the requirement the nodes published under `outputName` were fetched with, or NULL when they were fetched
	 * as bare classifiers - or when the constraint that produced them could not be found at all.
	 *
	 * The requirement is what tells the entity serializer whether an ancestor arriving without a body is a bodyless
	 * pointer standing in for a body that could not be materialized, or a plain primary key of a chain no body was
	 * ever asked for; a node's ancestor axis is reported under
	 * {@link io.evitadb.api.query.require.HierarchyParentsBehaviour#COMPLETE} and the two are indistinguishable in
	 * the data itself.
	 *
	 * @param hierarchyConstraints the constraints the hierarchies were computed for, empty when the query carries none
	 * @param outputName           the output name the hierarchy is published under
	 * @return the requirement the nodes were fetched with, or NULL
	 */
	@Nullable
	private static EntityFetch findNodeRequirement(
		@Nonnull List<? extends RequireConstraint> hierarchyConstraints,
		@Nonnull String outputName
	) {
		for (final RequireConstraint hierarchyConstraint : hierarchyConstraints) {
			final List<HierarchyRequireConstraint> requirements = QueryUtils.findConstraints(
				hierarchyConstraint, HierarchyRequireConstraint.class
			);
			for (final HierarchyRequireConstraint requirement : requirements) {
				if (outputName.equals(requirement.getOutputName())) {
					return findEntityFetch(requirement);
				}
			}
		}
		return null;
	}

	/**
	 * Returns the body requirement written directly inside `hierarchyRequirement`, or NULL when it asks for none and
	 * its nodes are therefore bare {@link io.evitadb.api.requestResponse.data.structure.EntityReference}s.
	 *
	 * Only the direct children are examined on purpose: a requirement nested inside this one - `siblings` written
	 * inside `parents`, say - carries a body requirement of its own that does not describe this one's nodes.
	 *
	 * @param hierarchyRequirement the requirement to read the body requirement off
	 * @return the body requirement, or NULL
	 */
	@Nullable
	private static EntityFetch findEntityFetch(@Nonnull HierarchyRequireConstraint hierarchyRequirement) {
		Assert.isPremiseValid(
			hierarchyRequirement instanceof ConstraintContainer,
			() -> new RestInternalError(
				"Hierarchy requirement `" + hierarchyRequirement.getName() +
					"` is expected to be a constraint container."
			)
		);
		for (final Constraint<?> child : ((ConstraintContainer<?>) hierarchyRequirement).getChildren()) {
			if (child instanceof EntityFetch entityFetch) {
				return entityFetch;
			}
		}
		return null;
	}

	/**
	 * Builds the contexts the summary of `referenceName` produced by a `referenceSummary` requirement is written
	 * under.
	 *
	 * A `referenceSummaryOfReference` naming the reference does not stand alone: the engine extends the requirement
	 * of the `referenceSummary` covering every reference with it, and the same reduction is repeated here so that the
	 * serializer reads the requirement the entities were really fetched with.
	 *
	 * @param require       the require part of the query the summary was computed for
	 * @param referenceName name of the reference the summary was computed for
	 * @param catalogSchema the schema of the catalog the reported entities belong to
	 * @return the contexts of the reference's summary
	 */
	@Nonnull
	private static ReferenceSummaryContexts referenceSummaryContexts(
		@Nonnull Require require,
		@Nonnull String referenceName,
		@Nonnull CatalogSchemaContract catalogSchema
	) {
		final io.evitadb.api.query.require.ReferenceSummary defaultRequire = QueryUtils.findConstraint(
			require, io.evitadb.api.query.require.ReferenceSummary.class
		);
		EntityFetch referencedEntityRequirement = defaultRequire == null ?
			null : defaultRequire.getReferenceEntityRequirement().orElse(null);
		EntityGroupFetch groupEntityRequirement = defaultRequire == null ?
			null : defaultRequire.getGroupEntityRequirement().orElse(null);
		final Map<String, EntitySerializationContext> histogramAnchors = createHashMap(8);
		collectHistogramAnchors(defaultRequire, catalogSchema, histogramAnchors);

		final List<ReferenceSummaryOfReference> specificRequires = QueryUtils.findConstraints(
			require, ReferenceSummaryOfReference.class
		);
		for (final ReferenceSummaryOfReference specificRequire : specificRequires) {
			if (!referenceName.equals(specificRequire.getReferenceName())) {
				continue;
			}
			referencedEntityRequirement = EntityFetchRequire.combineRequirements(
				specificRequire.getReferenceEntityRequirement().orElse(null), referencedEntityRequirement
			);
			groupEntityRequirement = EntityFetchRequire.combineRequirements(
				specificRequire.getGroupEntityRequirement().orElse(null), groupEntityRequirement
			);
			collectHistogramAnchors(specificRequire, catalogSchema, histogramAnchors);
		}

		return new ReferenceSummaryContexts(
			new EntitySerializationContext(catalogSchema, referencedEntityRequirement),
			new EntitySerializationContext(catalogSchema, groupEntityRequirement),
			histogramAnchors,
			catalogSchema
		);
	}

	/**
	 * Builds the contexts the summary of `referenceName` produced by the deprecated `facetSummary` requirement is
	 * written under. That summary reports no histograms, and therefore no anchor entities either.
	 *
	 * @param require       the require part of the query the summary was computed for
	 * @param referenceName name of the reference the summary was computed for
	 * @param catalogSchema the schema of the catalog the reported entities belong to
	 * @return the contexts of the reference's summary
	 */
	@Nonnull
	private static ReferenceSummaryContexts facetSummaryContexts(
		@Nonnull Require require,
		@Nonnull String referenceName,
		@Nonnull CatalogSchemaContract catalogSchema
	) {
		final io.evitadb.api.query.require.FacetSummary defaultRequire = QueryUtils.findConstraint(
			require, io.evitadb.api.query.require.FacetSummary.class
		);
		EntityFetch facetEntityRequirement = defaultRequire == null ?
			null : defaultRequire.getFacetEntityRequirement().orElse(null);
		EntityGroupFetch groupEntityRequirement = defaultRequire == null ?
			null : defaultRequire.getGroupEntityRequirement().orElse(null);

		final List<FacetSummaryOfReference> specificRequires = QueryUtils.findConstraints(
			require, FacetSummaryOfReference.class
		);
		for (final FacetSummaryOfReference specificRequire : specificRequires) {
			if (!referenceName.equals(specificRequire.getReferenceName())) {
				continue;
			}
			facetEntityRequirement = EntityFetchRequire.combineRequirements(
				specificRequire.getFacetEntityRequirement().orElse(null), facetEntityRequirement
			);
			groupEntityRequirement = EntityFetchRequire.combineRequirements(
				specificRequire.getGroupEntityRequirement().orElse(null), groupEntityRequirement
			);
		}

		return new ReferenceSummaryContexts(
			new EntitySerializationContext(catalogSchema, facetEntityRequirement),
			new EntitySerializationContext(catalogSchema, groupEntityRequirement),
			Map.of(),
			catalogSchema
		);
	}

	/**
	 * Collects the anchor-entity requirement of every named histogram written inside `summaryRequire` into
	 * `histogramAnchors`, keyed by the name the histogram is published under.
	 *
	 * The requirements of a `referenceSummaryOfReference` are collected after - and therefore replace - those of the
	 * `referenceSummary` covering every reference, matching the engine, which fetches an anchor entity through the
	 * requirement of the single histogram request that registered its primary key.
	 *
	 * @param summaryRequire   the reference summary requirement to read the histograms off, NULL when the query
	 *                         carries none
	 * @param catalogSchema    the schema of the catalog the anchor entities belong to
	 * @param histogramAnchors the map to collect into
	 */
	private static void collectHistogramAnchors(
		@Nullable RequireConstraint summaryRequire,
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull Map<String, EntitySerializationContext> histogramAnchors
	) {
		if (summaryRequire == null) {
			return;
		}
		final List<ReferenceHistogramStatistics> histogramRequires = QueryUtils.findConstraints(
			summaryRequire, ReferenceHistogramStatistics.class
		);
		for (final ReferenceHistogramStatistics histogramRequire : histogramRequires) {
			final EntitySerializationContext anchorContext = new EntitySerializationContext(
				catalogSchema, histogramRequire.getEntityFetch().orElse(null)
			);
			for (final String histogramName : histogramRequire.getIndexNames()) {
				histogramAnchors.put(histogramName, anchorContext);
			}
		}
	}

	@Nonnull
	private JsonNode serializeQueryTelemetry(@Nonnull QueryTelemetry telemetry) {
		return this.objectJsonSerializer.getObjectMapper().valueToTree(QueryTelemetryDto.from(telemetry));
	}

	@Nonnull
	private JsonNode serializeAttributeHistogram(
		@Nonnull AttributeHistogram attributeHistogram,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull CatalogSchemaContract catalogSchema
	) {
		final ObjectNode histogramNode = this.objectJsonSerializer.objectNode();
		// an attribute histogram carries no boundary entities, so the context they would be written under is never
		// consulted and there is no requirement to look up for it
		final EntitySerializationContext anchorContext = new EntitySerializationContext(catalogSchema);
		for (Entry<String, HistogramContract> entry : attributeHistogram.getHistograms().entrySet()) {
			final String attributeName = entry.getKey();
			final String serializableAttributeName = entitySchema.getAttribute(attributeName)
				.map(it -> it.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
				.orElseThrow(() -> new RestQueryResolvingInternalError(
					"Cannot find attribute schema for `" + attributeName + "` in entity schema `" + entitySchema.getName() + "`."
				));

			histogramNode.putIfAbsent(serializableAttributeName, serializeHistogram(entry.getValue(), anchorContext));
		}
		return histogramNode;
	}

	/**
	 * The serialization contexts one reference's summary is written under - one per kind of entity the summary can
	 * carry, each built from the requirement the entities of that kind were actually fetched with.
	 *
	 * The requirement is what tells the entity serializer whether an ancestor arriving without a body is a bodyless
	 * pointer standing in for a body that could not be materialized, or a plain primary key of a chain no body was
	 * ever asked for - the two are indistinguishable in the data itself, and reading the answer off the returned
	 * chain fails exactly when no ancestor at all could be materialized.
	 *
	 * @param referencedEntity  context of the facet / referenced entities reported by the summary
	 * @param groupEntity       context of the group entities reported by the summary
	 * @param histogramAnchors  contexts of the anchor entities of the named histograms, keyed by histogram name
	 * @param catalogSchema     the schema of the catalog the reported entities belong to
	 */
	private record ReferenceSummaryContexts(
		@Nonnull EntitySerializationContext referencedEntity,
		@Nonnull EntitySerializationContext groupEntity,
		@Nonnull Map<String, EntitySerializationContext> histogramAnchors,
		@Nonnull CatalogSchemaContract catalogSchema
	) {

		/**
		 * Returns the context the anchor entities of the histogram published under `histogramName` are written under.
		 *
		 * @param histogramName the name the histogram is published under
		 * @return the context of its anchor entities
		 */
		@Nonnull
		EntitySerializationContext forHistogramAnchor(@Nonnull String histogramName) {
			final EntitySerializationContext anchorContext = this.histogramAnchors.get(histogramName);
			// a histogram whose requirement could not be found is served by a context carrying none, leaving the
			// serializer to infer what the caller asked for - the fallback every endpoint that cannot supply
			// a requirement relies on
			return anchorContext == null ? new EntitySerializationContext(this.catalogSchema) : anchorContext;
		}
	}
}
