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

package io.evitadb.externalApi.rest.api.catalog.dataApi.builder;

import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.DataChunkDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.RecordPageDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.RecordStripDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ResponseDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.AttributeHistogramDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetGroupStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetRequestImpactDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor.BucketDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.QueryTelemetryDescriptor;
import io.evitadb.externalApi.rest.api.catalog.builder.CatalogRestBuildingContext;
import io.evitadb.externalApi.rest.api.catalog.dataApi.dto.DataChunkType;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.DataChunkUnionDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.extraResult.HierarchyOfDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.extraResult.LevelInfoDescriptor;
import io.evitadb.externalApi.rest.api.model.ObjectDescriptorToOpenApiDictionaryTransformer;
import io.evitadb.externalApi.rest.api.model.ObjectDescriptorToOpenApiObjectTransformer;
import io.evitadb.externalApi.rest.api.model.UnionDescriptorToOpenApiUnionTransformer;
import io.evitadb.externalApi.rest.api.model.PropertyDescriptorToOpenApiPropertyTransformer;
import io.evitadb.externalApi.rest.api.openApi.OpenApiDictionary;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObject;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObjectUnionType;
import io.evitadb.externalApi.rest.api.openApi.OpenApiProperty;
import io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference;
import io.evitadb.externalApi.rest.api.openApi.OpenApiUnion;
import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.rest.api.catalog.dataApi.builder.DataApiNamesConstructor.*;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiArray.arrayOf;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiNonNull.nonNull;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiProperty.newProperty;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;

/**
 * Builder for full query response. Response contains in this case not only main entity data but also additional data
 * like facets, statistics, etc.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class FullResponseObjectBuilder {

	@Nonnull private final CatalogRestBuildingContext buildingContext;
	@Nonnull private final PropertyDescriptorToOpenApiPropertyTransformer propertyBuilderTransformer;
	@Nonnull private final ObjectDescriptorToOpenApiObjectTransformer objectBuilderTransformer;
	@Nonnull private final UnionDescriptorToOpenApiUnionTransformer unionBuilderTransformer;
	@Nonnull private final ObjectDescriptorToOpenApiDictionaryTransformer dictionaryBuilderTransformer;

	public void buildCommonTypes() {
		this.buildingContext.registerType(BucketDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(HistogramDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(QueryTelemetryDescriptor.THIS.to(this.objectBuilderTransformer).build());
		this.buildingContext.registerType(FacetRequestImpactDescriptor.THIS.to(this.objectBuilderTransformer).build());
	}

	@Nonnull
	public OpenApiObject buildFullResponseObject(@Nonnull EntitySchemaContract entitySchema,
	                                             boolean localized) {
		final OpenApiObject.Builder responseObjectBuilder = ResponseDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(constructEntityFullResponseObjectName(entitySchema, localized))
			.property(buildDataChunkProperty(entitySchema, localized));

		buildExtraResultsProperty(entitySchema, localized).ifPresent(responseObjectBuilder::property);

		return responseObjectBuilder.build();
	}

	@Nonnull
	private OpenApiProperty buildDataChunkProperty(@Nonnull EntitySchemaContract entitySchema,
	                                               boolean localized) {
		return ResponseDescriptor.RECORD_PAGE
			.to(this.propertyBuilderTransformer)
			.type(nonNull(buildDataChunkObject(entitySchema, localized)))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildDataChunkObject(@Nonnull EntitySchemaContract entitySchema,
	                                                  boolean localized) {
		final OpenApiUnion dataChunkObject = DataChunkUnionDescriptor.THIS
			.to(this.unionBuilderTransformer)
			.name(constructEntityDataChunkAggregateObjectName(entitySchema, localized))
			.object(buildRecordPageObject(entitySchema, localized))
			.object(buildRecordStripObject(entitySchema, localized))
			.build();

		return this.buildingContext.registerType(dataChunkObject);
	}

	@Nonnull
	private OpenApiTypeReference buildRecordPageObject(@Nonnull EntitySchemaContract entitySchema,
	                                                   boolean localized) {
		final OpenApiTypeReference entityObject = typeRefTo(constructEntityObjectName(entitySchema, localized));

		final OpenApiObject recordPageObject = RecordPageDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(constructRecordPageObjectName(entitySchema, localized))
			.property(buildDataChunkDataProperty(entityObject))
			.property(createDataChunkDiscriminatorProperty())
			.build();

		return this.buildingContext.registerType(recordPageObject);
	}

	@Nonnull
	private OpenApiTypeReference buildRecordStripObject(@Nonnull EntitySchemaContract entitySchema,
	                                                    boolean localized) {
		final OpenApiTypeReference entityObject = typeRefTo(constructEntityObjectName(entitySchema, localized));

		final OpenApiObject recordStripObject = RecordStripDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(constructRecordStripObjectName(entitySchema, localized))
			.property(buildDataChunkDataProperty(entityObject))
			.property(createDataChunkDiscriminatorProperty())
			.build();

		return this.buildingContext.registerType(recordStripObject);
	}

	@Nonnull
	private OpenApiProperty buildDataChunkDataProperty(@Nonnull OpenApiTypeReference entityObject) {
		return DataChunkDescriptor.DATA
			.to(this.propertyBuilderTransformer)
			.type(nonNull(arrayOf(entityObject)))
			.build();
	}

	@Nonnull
	private OpenApiProperty createDataChunkDiscriminatorProperty() {
		return DataChunkUnionDescriptor.DISCRIMINATOR
			.to(this.propertyBuilderTransformer)
			.type(nonNull(typeRefTo(DataChunkType.class.getSimpleName())))
			.build();
	}

	@Nonnull
	private Optional<OpenApiProperty> buildExtraResultsProperty(@Nonnull EntitySchemaContract entitySchema,
	                                                            boolean localized) {
		final Optional<OpenApiTypeReference> extraResultsObject = buildExtraResultsObject(entitySchema, localized);
		if (extraResultsObject.isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(
			ResponseDescriptor.EXTRA_RESULTS
				.to(this.propertyBuilderTransformer)
				.type(nonNull(extraResultsObject.get()))
				.build()
		);
	}

	@Nonnull
	private Optional<OpenApiTypeReference> buildExtraResultsObject(@Nonnull EntitySchemaContract entitySchema,
	                                                               boolean localized) {
		final OpenApiObject.Builder extraResultObjectBuilder = ExtraResultsDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(constructExtraResultsObjectName(entitySchema, localized));

		final List<OpenApiProperty> extraResultProperties = new ArrayList<>(10);

		buildAttributeHistogramProperty(entitySchema).ifPresent(extraResultProperties::add);
		buildPriceHistogramProperty(entitySchema).ifPresent(extraResultProperties::add);
		buildFacetSummaryProperty(entitySchema, localized).ifPresent(extraResultProperties::add);
		buildHierarchyProperty(entitySchema, localized).ifPresent(extraResultProperties::add);
		extraResultProperties.add(ExtraResultsDescriptor.QUERY_TELEMETRY.to(this.propertyBuilderTransformer).build());

		if (extraResultProperties.isEmpty()) {
			return Optional.empty();
		}

		extraResultProperties.forEach(extraResultObjectBuilder::property);
		return Optional.of(this.buildingContext.registerType(extraResultObjectBuilder.build()));
	}

	@Nonnull
	private Optional<OpenApiProperty> buildAttributeHistogramProperty(@Nonnull EntitySchemaContract entitySchema) {
		final Optional<OpenApiTypeReference> attributeHistogramObject = buildAttributeHistogramObject(entitySchema);
		if (attributeHistogramObject.isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(
			ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM
				.to(this.propertyBuilderTransformer)
				.type(attributeHistogramObject.get())
				.build()
		);
	}

	@Nonnull
	private Optional<OpenApiTypeReference> buildAttributeHistogramObject(@Nonnull EntitySchemaContract entitySchema) {
		final String objectName = AttributeHistogramDescriptor.THIS.name(entitySchema);
		final Optional<OpenApiTypeReference> existingAttributeHistogramObject = this.buildingContext.getRegisteredType(objectName);
		if (existingAttributeHistogramObject.isPresent()) {
			return existingAttributeHistogramObject;
		}

		final List<EntityAttributeSchemaContract> attributeSchemas = entitySchema
			.getAttributes()
			.values()
			.stream()
			.filter(attributeSchema -> attributeSchema.isFilterableInAnyScope() &&
				Number.class.isAssignableFrom(attributeSchema.getPlainType()))
			.toList();

		if (attributeSchemas.isEmpty()) {
			return Optional.empty();
		}

		final OpenApiObject.Builder attributeHistogramObjectBuilder = AttributeHistogramDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(objectName);

		attributeSchemas.forEach(attributeSchema -> {
			attributeHistogramObjectBuilder.property(p -> p
				.name(attributeSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
				.type(typeRefTo(HistogramDescriptor.THIS.name())));
		});

		return Optional.of(this.buildingContext.registerType(attributeHistogramObjectBuilder.build()));
	}

	@Nonnull
	private Optional<OpenApiProperty> buildPriceHistogramProperty(@Nonnull EntitySchemaContract entitySchema) {
		if (entitySchema.getCurrencies().isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(ExtraResultsDescriptor.PRICE_HISTOGRAM
			.to(this.propertyBuilderTransformer)
			.build());
	}

	@Nonnull
	private Optional<OpenApiProperty> buildFacetSummaryProperty(@Nonnull EntitySchemaContract entitySchema,
	                                                            boolean localized) {
		final Optional<OpenApiTypeReference> facetSummaryObject = buildFacetSummaryObject(entitySchema, localized);
		if (facetSummaryObject.isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(
			ExtraResultsDescriptor.FACET_SUMMARY
				.to(this.propertyBuilderTransformer)
				.type(facetSummaryObject.get())
				.build()
		);
	}

	@Nonnull
	private Optional<OpenApiTypeReference> buildFacetSummaryObject(@Nonnull EntitySchemaContract entitySchema,
	                                                               boolean localized) {
		final List<ReferenceSchemaContract> referenceSchemas = entitySchema
			.getReferences()
			.values()
			.stream()
			.filter(ReferenceSchemaContract::isFacetedInAnyScope)
			.toList();

		if (referenceSchemas.isEmpty()) {
			return Optional.empty();
		}

		final OpenApiObject.Builder facetSummaryObjectBuilder = FacetSummaryDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(constructFacetSummaryObjectName(entitySchema, localized));

		referenceSchemas.forEach(referenceSchema ->
			facetSummaryObjectBuilder.property(buildFacetGroupStatisticsProperty(entitySchema, referenceSchema, localized)));

		return Optional.of(this.buildingContext.registerType(facetSummaryObjectBuilder.build()));
	}

	@Nonnull
	private OpenApiProperty buildFacetGroupStatisticsProperty(@Nonnull EntitySchemaContract entitySchema,
	                                                          @Nonnull ReferenceSchemaContract referenceSchema,
	                                                          boolean localized) {
		final OpenApiTypeReference facetGroupStatisticsObject = buildFacetGroupStatisticsObject(
			entitySchema,
			referenceSchema,
			localized
		);

		final boolean isGrouped = referenceSchema.getReferencedGroupType() != null;

		final OpenApiProperty.Builder facetGroupStatisticsFieldBuilder = newProperty()
			.name(referenceSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION));
		if (isGrouped) {
			facetGroupStatisticsFieldBuilder.type(arrayOf(facetGroupStatisticsObject));
		} else {
			facetGroupStatisticsFieldBuilder.type(facetGroupStatisticsObject);
		}

		return facetGroupStatisticsFieldBuilder
			.build();
	}


	@Nonnull
	private OpenApiTypeReference buildFacetGroupStatisticsObject(@Nonnull EntitySchemaContract entitySchema,
	                                                             @Nonnull ReferenceSchemaContract referenceSchema,
	                                                             boolean localized) {
		final EntitySchemaContract groupEntitySchema = referenceSchema.isReferencedGroupTypeManaged() ?
			Optional.ofNullable(referenceSchema.getReferencedGroupType())
				.flatMap(groupType -> this.buildingContext
					.getSchema()
					.getEntitySchema(groupType))
				.orElse(null) :
			null;

		final OpenApiTypeReference groupEntityObject = buildReferencedEntityObject(groupEntitySchema, localized);
		final OpenApiTypeReference facetStatisticsObject = buildFacetStatisticsObject(
			entitySchema,
			referenceSchema,
			localized
		);

		final OpenApiObject.Builder facetGroupStatisticsObjectBuilder = FacetGroupStatisticsDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(constructFacetGroupStatisticsObjectName(entitySchema, referenceSchema, localized));

		if (referenceSchema.getReferencedGroupType() != null) {
			facetGroupStatisticsObjectBuilder
				.property(FacetGroupStatisticsDescriptor.GROUP_ENTITY
					.to(this.propertyBuilderTransformer)
					.type(groupEntityObject));
		}

		facetGroupStatisticsObjectBuilder
			.property(FacetGroupStatisticsDescriptor.FACET_STATISTICS
				.to(this.propertyBuilderTransformer)
				.type(nonNull(arrayOf(facetStatisticsObject))));

		return this.buildingContext.registerType(facetGroupStatisticsObjectBuilder.build());
	}

	@Nonnull
	private OpenApiTypeReference buildFacetStatisticsObject(@Nonnull EntitySchemaContract entitySchema,
	                                                        @Nonnull ReferenceSchemaContract referenceSchema,
	                                                        boolean localized) {
		final EntitySchemaContract facetEntitySchema = referenceSchema.isReferencedEntityTypeManaged()?
			this.buildingContext
				.getSchema()
				.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType()) :
			null;
		final OpenApiTypeReference facetEntityObject = buildReferencedEntityObject(facetEntitySchema, localized);

		final OpenApiObject facetStatisticsObject = FacetStatisticsDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(constructFacetStatisticsObjectName(entitySchema, referenceSchema, localized))
			.property(FacetStatisticsDescriptor.FACET_ENTITY
				.to(this.propertyBuilderTransformer)
				.type(facetEntityObject))
			.build();

		return this.buildingContext.registerType(facetStatisticsObject);
	}

	@Nonnull
	private OpenApiTypeReference buildReferencedEntityObject(@Nullable EntitySchemaContract referencedEntitySchema,
	                                                         boolean localized) {
		if (referencedEntitySchema != null) {
			return typeRefTo(constructEntityObjectName(referencedEntitySchema, localized));
		} else {
			return typeRefTo(EntityDescriptor.THIS_REFERENCE.name());
		}
	}

	@Nonnull
	private Optional<OpenApiProperty> buildHierarchyProperty(@Nonnull EntitySchemaContract entitySchema,
	                                                         boolean localized) {
		final List<ReferenceSchemaContract> referenceSchemas = entitySchema
			.getReferences()
			.values()
			.stream()
			.filter(referenceSchema -> referenceSchema.isReferencedEntityTypeManaged() &&
				this.buildingContext.getSchema().getEntitySchema(referenceSchema.getReferencedEntityType())
					.map(EntitySchemaContract::isWithHierarchy)
					.orElseThrow(() -> new OpenApiBuildingError("Reference `" + referenceSchema.getName() + "` should have existing entity schema but no schema found.")))
			.toList();

		if (referenceSchemas.isEmpty() && !entitySchema.isWithHierarchy()) {
			return Optional.empty();
		}

		final OpenApiTypeReference hierarchyObject = buildHierarchyObject(entitySchema, referenceSchemas, localized);
		return Optional.of(
			ExtraResultsDescriptor.HIERARCHY
				.to(this.propertyBuilderTransformer)
				.type(hierarchyObject)
				.build()
		);
	}

	@Nonnull
	private OpenApiTypeReference buildHierarchyObject(@Nonnull EntitySchemaContract entitySchema,
	                                                  @Nonnull List<ReferenceSchemaContract> referenceSchemas,
	                                                  boolean localized) {
		final OpenApiObject.Builder hierarchyStatisticsObjectBuilder = HierarchyDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(constructHierarchyObjectName(entitySchema, localized));

		if (entitySchema.isWithHierarchy()) {
			hierarchyStatisticsObjectBuilder.property(buildHierarchyOfSelfProperty(entitySchema, localized));
		}

		referenceSchemas.forEach(referenceSchema ->
			hierarchyStatisticsObjectBuilder.property(buildHierarchyOfReferenceProperty(entitySchema, referenceSchema, localized)));

		return this.buildingContext.registerType(hierarchyStatisticsObjectBuilder.build());
	}

	@Nonnull
	private OpenApiProperty buildHierarchyOfSelfProperty(@Nonnull EntitySchemaContract entitySchema,
	                                                     boolean localized) {
		final OpenApiTypeReference hierarchyOfSelfObject = buildHierarchyOfSelfObject(entitySchema, localized);
		return HierarchyDescriptor.SELF
			.to(this.propertyBuilderTransformer)
			.type(nonNull(hierarchyOfSelfObject))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildHierarchyOfSelfObject(@Nonnull EntitySchemaContract entitySchema,
	                                                        boolean localized) {
		final OpenApiTypeReference selfLevelInfoObject = buildSelfLevelInfoObject(entitySchema, localized);
		final OpenApiDictionary hierarchyOfSelfObject = HierarchyOfDescriptor.THIS
			.to(this.dictionaryBuilderTransformer)
			.name(constructHierarchyOfSelfObjectName(entitySchema, localized))
			.valueType(arrayOf(selfLevelInfoObject))
			.build();
		return this.buildingContext.registerType(hierarchyOfSelfObject);
	}

	@Nonnull
	private OpenApiTypeReference buildSelfLevelInfoObject(@Nonnull EntitySchemaContract entitySchema,
	                                                      boolean localized) {
		final String objectName = constructSelfLevelInfoObjectName(entitySchema, localized);

		final OpenApiObject selfLevelInfoObject = LevelInfoDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(objectName)
			.property(LevelInfoDescriptor.ENTITY
				.to(this.propertyBuilderTransformer)
				.type(nonNull(typeRefTo(constructEntityObjectName(entitySchema, localized)))))
			.property(LevelInfoDescriptor.CHILDREN
				.to(this.propertyBuilderTransformer)
				.type(nonNull(arrayOf(typeRefTo(objectName)))))
			.build();

		return this.buildingContext.registerType(selfLevelInfoObject);
	}

	@Nonnull
	private OpenApiProperty buildHierarchyOfReferenceProperty(@Nonnull EntitySchemaContract entitySchema,
															  @Nonnull ReferenceSchemaContract referenceSchema,
                                                              boolean localized) {
		final OpenApiTypeReference hierarchyOfReferenceObject = buildHierarchyOfReferenceObject(entitySchema, referenceSchema, localized);
		return HierarchyDescriptor.REFERENCE
			.to(this.propertyBuilderTransformer)
			.name(referenceSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
			.description(HierarchyDescriptor.REFERENCE.description(referenceSchema.getReferencedEntityType()))
			.type(nonNull(hierarchyOfReferenceObject))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildHierarchyOfReferenceObject(@Nonnull EntitySchemaContract entitySchema,
	                                                             @Nonnull ReferenceSchemaContract referenceSchema,
	                                                             boolean localized) {
		final OpenApiTypeReference levelInfoObject = buildLevelInfoObject(entitySchema, referenceSchema, localized);
		final OpenApiDictionary hierarchyOfReferenceObject = HierarchyOfDescriptor.THIS
			.to(this.dictionaryBuilderTransformer)
			.name(constructHierarchyOfReferenceObjectName(entitySchema, referenceSchema, localized))
			.valueType(arrayOf(levelInfoObject))
			.build();
		return this.buildingContext.registerType(hierarchyOfReferenceObject);
	}


	@Nonnull
	private OpenApiTypeReference buildLevelInfoObject(@Nonnull EntitySchemaContract entitySchema,
	                                                  @Nonnull ReferenceSchemaContract referenceSchema,
	                                                  boolean localized) {
		final String levelInfoObjectName = constructLevelInfoObjectName(entitySchema, referenceSchema, localized);

		final OpenApiObject levelInfoObject = LevelInfoDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(levelInfoObjectName)
			.property(buildLevelInfoEntityProperty(referenceSchema, localized))
			.property(LevelInfoDescriptor.CHILDREN
				.to(this.propertyBuilderTransformer)
				.type(nonNull(arrayOf(typeRefTo(levelInfoObjectName)))))
			.build();

		return this.buildingContext.registerType(levelInfoObject);
	}

	@Nonnull
	private OpenApiProperty buildLevelInfoEntityProperty(@Nonnull ReferenceSchemaContract referenceSchema,
	                                                     boolean localized) {
		final EntitySchemaContract referencedEntitySchema = this.buildingContext
			.getSchema()
			.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType());
		final String referencedEntityObjectName = constructEntityObjectName(referencedEntitySchema, localized);

		return LevelInfoDescriptor.ENTITY
			.to(this.propertyBuilderTransformer)
			.type(nonNull(typeRefTo(referencedEntityObjectName)))
			.build();
	}
}
