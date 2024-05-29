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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.lab.api.builder;

import io.evitadb.externalApi.api.catalog.dataApi.model.DataChunkDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetGroupStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetRequestImpactDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor.BucketDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.QueryTelemetryDescriptor;
import io.evitadb.externalApi.lab.api.model.GenericDataChunkUnionDescriptor;
import io.evitadb.externalApi.lab.api.model.GenericRecordPageDescriptor;
import io.evitadb.externalApi.lab.api.model.GenericRecordStripDescriptor;
import io.evitadb.externalApi.lab.api.model.GenericResponseDescriptor;
import io.evitadb.externalApi.lab.api.model.entity.GenericEntityDescriptor;
import io.evitadb.externalApi.lab.api.model.extraResult.GenericAttributeHistogramDescriptor;
import io.evitadb.externalApi.lab.api.model.extraResult.GenericExtraResultsDescriptor;
import io.evitadb.externalApi.lab.api.model.extraResult.GenericFacetSummaryDescriptor;
import io.evitadb.externalApi.lab.api.model.extraResult.GenericFacetSummaryDescriptor.GenericFacetGroupStatisticsDescriptor;
import io.evitadb.externalApi.lab.api.model.extraResult.GenericFacetSummaryDescriptor.GenericFacetStatisticsDescriptor;
import io.evitadb.externalApi.lab.api.model.extraResult.GenericHierarchyDescriptor;
import io.evitadb.externalApi.lab.api.model.extraResult.GenericLevelInfoDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.DataChunkUnionDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.extraResult.LevelInfoDescriptor;
import io.evitadb.externalApi.rest.api.model.ObjectDescriptorToOpenApiDictionaryTransformer;
import io.evitadb.externalApi.rest.api.model.ObjectDescriptorToOpenApiObjectTransformer;
import io.evitadb.externalApi.rest.api.model.ObjectDescriptorToOpenApiUnionTransformer;
import io.evitadb.externalApi.rest.api.model.PropertyDescriptorToOpenApiPropertyTransformer;
import io.evitadb.externalApi.rest.api.openApi.OpenApiDictionary;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObject;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObjectUnionType;
import io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference;
import io.evitadb.externalApi.rest.api.openApi.OpenApiUnion;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiArray.arrayOf;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiNonNull.nonNull;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;

/**
 * Builder for full query response. Response contains in this case not only main entity data but also additional data
 * like facets, statistics, etc.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class GenericFullResponseObjectBuilder {

	@Nonnull private final LabApiBuildingContext buildingContext;
	@Nonnull private final PropertyDescriptorToOpenApiPropertyTransformer propertyBuilderTransformer;
	@Nonnull private final ObjectDescriptorToOpenApiObjectTransformer objectBuilderTransformer;
	@Nonnull private final ObjectDescriptorToOpenApiUnionTransformer unionBuilderTransformer;
	@Nonnull private final ObjectDescriptorToOpenApiDictionaryTransformer dictionaryBuilderTransformer;

	public void buildCommonTypes() {
		buildingContext.registerType(BucketDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(HistogramDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(GenericAttributeHistogramDescriptor.THIS
			.to(dictionaryBuilderTransformer)
			.valueType(typeRefTo(HistogramDescriptor.THIS.name()))
			.build());
		buildingContext.registerType(QueryTelemetryDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(FacetRequestImpactDescriptor.THIS.to(objectBuilderTransformer).build());

		buildFullResponseObject();
		buildExtraResultsObject();
		buildDataChunkObject();
		buildRecordPageObject();
		buildRecordStripObject();
		buildFacetSummaryObject();
		buildFacetGroupStatisticsObject();
		buildFacetStatisticsObject();
		buildHierarchyObject();
		buildLevelInfoObject();
	}

	@Nonnull
	private OpenApiTypeReference buildFullResponseObject() {
		final OpenApiObject.Builder responseObjectBuilder = GenericResponseDescriptor.THIS
			.to(objectBuilderTransformer)
			.property(GenericResponseDescriptor.RECORD_PAGE.to(propertyBuilderTransformer).type(nonNull(typeRefTo(GenericDataChunkUnionDescriptor.THIS.name()))))
			.property(GenericResponseDescriptor.EXTRA_RESULTS.to(propertyBuilderTransformer).type(nonNull(typeRefTo(GenericExtraResultsDescriptor.THIS.name()))));

		return buildingContext.registerType(responseObjectBuilder.build());
	}

	@Nonnull
	private OpenApiTypeReference buildDataChunkObject() {
		final OpenApiUnion dataChunkObject = GenericDataChunkUnionDescriptor.THIS
			.to(unionBuilderTransformer)
			.type(OpenApiObjectUnionType.ONE_OF)
			.discriminator(DataChunkUnionDescriptor.DISCRIMINATOR.name())
			.object(typeRefTo(GenericRecordPageDescriptor.THIS.name()))
			.object(typeRefTo(GenericRecordStripDescriptor.THIS.name()))
			.build();

		return buildingContext.registerType(dataChunkObject);
	}

	@Nonnull
	private OpenApiTypeReference buildRecordPageObject() {
		final OpenApiObject recordPageObject = GenericRecordPageDescriptor.THIS
			.to(objectBuilderTransformer)
			.property(DataChunkDescriptor.DATA
				.to(propertyBuilderTransformer)
				.type(nonNull(arrayOf(typeRefTo(GenericEntityDescriptor.THIS.name())))))
			.property(DataChunkUnionDescriptor.DISCRIMINATOR
				.to(propertyBuilderTransformer)
				.type(nonNull(typeRefTo(GenericDataChunkUnionDescriptor.THIS.name()))))
			.build();

		return buildingContext.registerType(recordPageObject);
	}

	@Nonnull
	private OpenApiTypeReference buildRecordStripObject() {
		final OpenApiObject recordStripObject = GenericRecordStripDescriptor.THIS
			.to(objectBuilderTransformer)
			.property(DataChunkDescriptor.DATA
				.to(propertyBuilderTransformer)
				.type(nonNull(arrayOf(typeRefTo(GenericEntityDescriptor.THIS.name())))))
			.property(DataChunkUnionDescriptor.DISCRIMINATOR
				.to(propertyBuilderTransformer)
				.type(nonNull(typeRefTo(GenericDataChunkUnionDescriptor.THIS.name()))))
			.build();

		return buildingContext.registerType(recordStripObject);
	}


	@Nonnull
	private OpenApiTypeReference buildExtraResultsObject() {
		final OpenApiObject.Builder extraResultObjectBuilder = GenericExtraResultsDescriptor.THIS
			.to(objectBuilderTransformer)
			.property(ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM.to(propertyBuilderTransformer)
				.type(typeRefTo(GenericAttributeHistogramDescriptor.THIS.name()))
				.build())
			.property(ExtraResultsDescriptor.PRICE_HISTOGRAM.to(propertyBuilderTransformer).build())
			.property(ExtraResultsDescriptor.FACET_SUMMARY
				.to(propertyBuilderTransformer)
				.type(typeRefTo(GenericFacetSummaryDescriptor.THIS.name())))
			.property(ExtraResultsDescriptor.HIERARCHY
				.to(propertyBuilderTransformer)
				.type(typeRefTo(GenericHierarchyDescriptor.THIS.name())))
			.property(ExtraResultsDescriptor.QUERY_TELEMETRY.to(propertyBuilderTransformer).build());

		return buildingContext.registerType(extraResultObjectBuilder.build());
	}

	@Nonnull
	private OpenApiTypeReference buildFacetSummaryObject() {
		final OpenApiDictionary facetSummaryObject = GenericFacetSummaryDescriptor.THIS
			.to(dictionaryBuilderTransformer)
			.valueType(typeRefTo(GenericFacetGroupStatisticsDescriptor.THIS.name()))
			.build();

		return buildingContext.registerType(facetSummaryObject);
	}

	@Nonnull
	private OpenApiTypeReference buildFacetGroupStatisticsObject() {
		final OpenApiObject facetGroupStatisticsObject = GenericFacetGroupStatisticsDescriptor.THIS
			.to(objectBuilderTransformer)
			.property(FacetGroupStatisticsDescriptor.GROUP_ENTITY
				.to(propertyBuilderTransformer)
				.type(typeRefTo(GenericEntityDescriptor.THIS.name())))
			.property(FacetGroupStatisticsDescriptor.FACET_STATISTICS
				.to(propertyBuilderTransformer)
				.type(nonNull(arrayOf(typeRefTo(GenericFacetStatisticsDescriptor.THIS.name())))))
			.build();

		return buildingContext.registerType(facetGroupStatisticsObject);
	}

	@Nonnull
	private OpenApiTypeReference buildFacetStatisticsObject() {
		final OpenApiObject facetStatisticsObject = GenericFacetStatisticsDescriptor.THIS
			.to(objectBuilderTransformer)
			.property(FacetStatisticsDescriptor.FACET_ENTITY
				.to(propertyBuilderTransformer)
				.type(typeRefTo(GenericEntityDescriptor.THIS.name())))
			.build();

		return buildingContext.registerType(facetStatisticsObject);
	}

	@Nonnull
	private OpenApiTypeReference buildHierarchyObject() {
		final OpenApiDictionary hierarchyStatisticsObject = GenericHierarchyDescriptor.THIS
			.to(dictionaryBuilderTransformer)
			.valueType(arrayOf(typeRefTo(GenericLevelInfoDescriptor.THIS.name())))
			.build();

		return buildingContext.registerType(hierarchyStatisticsObject);
	}

	@Nonnull
	private OpenApiTypeReference buildLevelInfoObject() {
		final OpenApiObject selfLevelInfoObject = GenericLevelInfoDescriptor.THIS
			.to(objectBuilderTransformer)
			.property(LevelInfoDescriptor.ENTITY
				.to(propertyBuilderTransformer)
				.type(nonNull(typeRefTo(GenericEntityDescriptor.THIS.name()))))
			.property(LevelInfoDescriptor.CHILDREN
				.to(propertyBuilderTransformer)
				.type(nonNull(arrayOf(typeRefTo(GenericLevelInfoDescriptor.THIS.name())))))
			.build();

		return buildingContext.registerType(selfLevelInfoObject);
	}
}
