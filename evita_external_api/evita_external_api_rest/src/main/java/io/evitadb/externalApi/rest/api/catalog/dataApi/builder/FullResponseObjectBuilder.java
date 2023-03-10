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

package io.evitadb.externalApi.rest.api.catalog.dataApi.builder;

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
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
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor.ParentsOfEntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor.ParentsOfEntityDescriptor.ParentsOfReferenceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyStatisticsDescriptor.HierarchyStatisticsLevelInfoDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor.BucketDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.QueryTelemetryDescriptor;
import io.evitadb.externalApi.rest.api.catalog.builder.CatalogRestBuildingContext;
import io.evitadb.externalApi.rest.api.catalog.dataApi.dto.DataChunkType;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.DataChunkAggregateDescriptor;
import io.evitadb.externalApi.rest.api.model.ObjectDescriptorToOpenApiObjectTransformer;
import io.evitadb.externalApi.rest.api.model.PropertyDescriptorToOpenApiPropertyTransformer;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObject;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObjectUnionType;
import io.evitadb.externalApi.rest.api.openApi.OpenApiProperty;
import io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference;
import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.FIELD_NAME_NAMING_CONVENTION;
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

	public void buildCommonTypes() {
		buildingContext.registerType(BucketDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(HistogramDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(QueryTelemetryDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(FacetRequestImpactDescriptor.THIS.to(objectBuilderTransformer).build());
	}

	@Nonnull
	public OpenApiObject buildFullResponseObject(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                             boolean localized) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final OpenApiObject.Builder responseObjectBuilder = ResponseDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructEntityFullResponseObjectName(entitySchema, localized))
			.property(buildDataChunkProperty(collectionBuildingContext, localized));

		buildExtraResultsProperty(collectionBuildingContext, localized).ifPresent(responseObjectBuilder::property);

		return responseObjectBuilder.build();
	}

	@Nonnull
	private OpenApiProperty buildDataChunkProperty(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                               boolean localized) {
		return ResponseDescriptor.RECORD_PAGE
			.to(propertyBuilderTransformer)
			.type(nonNull(buildDataChunkObject(collectionBuildingContext, localized)))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildDataChunkObject(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                                  boolean localized) {
		final OpenApiObject dataChunkObject = DataChunkAggregateDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructEntityDataChunkAggregateObjectName(collectionBuildingContext.getSchema(), localized))
			.unionType(OpenApiObjectUnionType.ONE_OF)
			.unionDiscriminator(DataChunkAggregateDescriptor.DISCRIMINATOR.name())
			.unionObject(buildRecordPageObject(collectionBuildingContext, localized))
			.unionObject(buildRecordStripObject(collectionBuildingContext, localized))
			.build();

		return buildingContext.registerType(dataChunkObject);
	}

	@Nonnull
	private OpenApiTypeReference buildRecordPageObject(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                                   boolean localized) {
		final OpenApiTypeReference entityObject = typeRefTo(constructEntityObjectName(collectionBuildingContext.getSchema(), localized));

		final OpenApiObject recordPageObject = RecordPageDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructRecordPageObjectName(collectionBuildingContext.getSchema(), localized))
			.property(buildDataChunkDataProperty(entityObject))
			.property(createDataChunkDiscriminatorProperty())
			.build();

		return buildingContext.registerType(recordPageObject);
	}

	@Nonnull
	private OpenApiTypeReference buildRecordStripObject(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                                    boolean localized) {
		final OpenApiTypeReference entityObject = typeRefTo(constructEntityObjectName(collectionBuildingContext.getSchema(), localized));

		final OpenApiObject recordStripObject = RecordStripDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructRecordStripObjectName(collectionBuildingContext.getSchema(), localized))
			.property(buildDataChunkDataProperty(entityObject))
			.property(createDataChunkDiscriminatorProperty())
			.build();

		return buildingContext.registerType(recordStripObject);
	}

	@Nonnull
	private OpenApiProperty buildDataChunkDataProperty(@Nonnull OpenApiTypeReference entityObject) {
		return DataChunkDescriptor.DATA
			.to(propertyBuilderTransformer)
			.type(nonNull(arrayOf(entityObject)))
			.build();
	}

	@Nonnull
	private OpenApiProperty createDataChunkDiscriminatorProperty() {
		return DataChunkAggregateDescriptor.DISCRIMINATOR
			.to(propertyBuilderTransformer)
			.type(nonNull(typeRefTo(DataChunkType.class.getSimpleName())))
			.build();
	}

	@Nonnull
	private Optional<OpenApiProperty> buildExtraResultsProperty(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                                            boolean localized) {
		final Optional<OpenApiTypeReference> extraResultsObject = buildExtraResultsObject(collectionBuildingContext, localized);
		if (extraResultsObject.isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(
			ResponseDescriptor.EXTRA_RESULTS
				.to(propertyBuilderTransformer)
				.type(nonNull(extraResultsObject.get()))
				.build()
		);
	}

	@Nonnull
	private Optional<OpenApiTypeReference> buildExtraResultsObject(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                                               boolean localized) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final OpenApiObject.Builder extraResultObjectBuilder = ExtraResultsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructExtraResultsObjectName(entitySchema, localized));

		final List<OpenApiProperty> extraResultProperties = new ArrayList<>(10);

		buildAttributeHistogramProperty(collectionBuildingContext).ifPresent(extraResultProperties::add);
		buildPriceHistogramProperty(collectionBuildingContext).ifPresent(extraResultProperties::add);
		buildFacetSummaryProperty(collectionBuildingContext, localized).ifPresent(extraResultProperties::add);
		extraResultProperties.addAll(buildHierarchyExtraResultProperties(collectionBuildingContext, localized));
		extraResultProperties.add(ExtraResultsDescriptor.QUERY_TELEMETRY.to(propertyBuilderTransformer).build());

		if (extraResultProperties.isEmpty()) {
			return Optional.empty();
		}

		extraResultProperties.forEach(extraResultObjectBuilder::property);
		return Optional.of(buildingContext.registerType(extraResultObjectBuilder.build()));
	}

	@Nonnull
	private Optional<OpenApiProperty> buildAttributeHistogramProperty(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext) {
		final Optional<OpenApiTypeReference> attributeHistogramObject = buildAttributeHistogramObject(collectionBuildingContext);
		if (attributeHistogramObject.isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(
			ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM
				.to(propertyBuilderTransformer)
				.type(attributeHistogramObject.get())
				.build()
		);
	}

	@Nonnull
	private Optional<OpenApiTypeReference> buildAttributeHistogramObject(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final String objectName = AttributeHistogramDescriptor.THIS.name(entitySchema);
		final Optional<OpenApiTypeReference> existingAttributeHistogramObject = buildingContext.getRegisteredType(objectName);
		if (existingAttributeHistogramObject.isPresent()) {
			return existingAttributeHistogramObject;
		}

		final List<AttributeSchemaContract> attributeSchemas = entitySchema
			.getAttributes()
			.values()
			.stream()
			.filter(attributeSchema -> attributeSchema.isFilterable() &&
				Number.class.isAssignableFrom(attributeSchema.getPlainType()))
			.toList();

		if (attributeSchemas.isEmpty()) {
			return Optional.empty();
		}

		final OpenApiObject.Builder attributeHistogramObjectBuilder = AttributeHistogramDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		attributeSchemas.forEach(attributeSchema -> {
			attributeHistogramObjectBuilder.property(p -> p
				.name(attributeSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
				.type(typeRefTo(HistogramDescriptor.THIS.name())));
		});

		return Optional.of(buildingContext.registerType(attributeHistogramObjectBuilder.build()));
	}

	@Nonnull
	private Optional<OpenApiProperty> buildPriceHistogramProperty(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext) {
		if (collectionBuildingContext.getSchema().getCurrencies().isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(ExtraResultsDescriptor.PRICE_HISTOGRAM
			.to(propertyBuilderTransformer)
			.build());
	}

	@Nonnull
	private Optional<OpenApiProperty> buildFacetSummaryProperty(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                                            boolean localized) {
		final Optional<OpenApiTypeReference> facetSummaryObject = buildFacetSummaryObject(collectionBuildingContext, localized);
		if (facetSummaryObject.isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(
			ExtraResultsDescriptor.FACET_SUMMARY
				.to(propertyBuilderTransformer)
				.type(facetSummaryObject.get())
				.build()
		);
	}

	@Nonnull
	private Optional<OpenApiTypeReference> buildFacetSummaryObject(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                                               boolean localized) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();
		final List<ReferenceSchemaContract> referenceSchemas = entitySchema
			.getReferences()
			.values()
			.stream()
			.filter(ReferenceSchemaContract::isFaceted)
			.toList();

		if (referenceSchemas.isEmpty()) {
			return Optional.empty();
		}

		final OpenApiObject.Builder facetSummaryObjectBuilder = FacetSummaryDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructFacetSummaryObjectName(entitySchema, localized));

		referenceSchemas.forEach(referenceSchema ->
			facetSummaryObjectBuilder.property(buildFacetGroupStatisticsProperty(collectionBuildingContext, referenceSchema, localized)));

		return Optional.of(buildingContext.registerType(facetSummaryObjectBuilder.build()));
	}

	@Nonnull
	private OpenApiProperty buildFacetGroupStatisticsProperty(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                                          @Nonnull ReferenceSchemaContract referenceSchema,
	                                                          boolean localized) {
		final OpenApiTypeReference facetGroupStatisticsObject = buildFacetGroupStatisticsObject(
			collectionBuildingContext,
			referenceSchema,
			localized
		);

		return newProperty()
			.name(referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
			.type(arrayOf(facetGroupStatisticsObject))
			.build();
	}


	@Nonnull
	private OpenApiTypeReference buildFacetGroupStatisticsObject(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                                             @Nonnull ReferenceSchemaContract referenceSchema,
	                                                             boolean localized) {
		final EntitySchemaContract groupEntitySchema = referenceSchema.isReferencedGroupTypeManaged() ?
			Optional.ofNullable(referenceSchema.getReferencedGroupType())
				.flatMap(groupType -> buildingContext
					.getSchema()
					.getEntitySchema(groupType))
				.orElse(null) :
			null;

		final OpenApiTypeReference groupEntityObject = buildReferencedEntityObject(groupEntitySchema, localized);
		final OpenApiTypeReference facetStatisticsObject = buildFacetStatisticsObject(
			collectionBuildingContext,
			referenceSchema,
			localized
		);

		final OpenApiObject facetGroupStatisticsObject = FacetGroupStatisticsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructFacetGroupStatisticsObjectName(collectionBuildingContext.getSchema(), referenceSchema, localized))
			.property(FacetGroupStatisticsDescriptor.GROUP_ENTITY
				.to(propertyBuilderTransformer)
				.type(groupEntityObject))
			.property(FacetGroupStatisticsDescriptor.FACET_STATISTICS
				.to(propertyBuilderTransformer)
				.type(nonNull(arrayOf(facetStatisticsObject))))
			.build();

		return buildingContext.registerType(facetGroupStatisticsObject);
	}

	@Nonnull
	private OpenApiTypeReference buildFacetStatisticsObject(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                                        @Nonnull ReferenceSchemaContract referenceSchema,
	                                                        boolean localized) {
		final EntitySchemaContract facetEntitySchema = referenceSchema.isReferencedEntityTypeManaged()?
			buildingContext
				.getSchema()
				.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType()) :
			null;
		final OpenApiTypeReference facetEntityObject = buildReferencedEntityObject(facetEntitySchema, localized);

		final OpenApiObject facetStatisticsObject = FacetStatisticsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructFacetStatisticsObjectName(collectionBuildingContext.getSchema(), referenceSchema, localized))
			.property(FacetStatisticsDescriptor.FACET_ENTITY
				.to(propertyBuilderTransformer)
				.type(facetEntityObject))
			.build();

		return buildingContext.registerType(facetStatisticsObject);
	}

	@Nonnull
	private OpenApiTypeReference buildReferencedEntityObject(@Nullable EntitySchemaContract referencedEntitySchema,
	                                                         boolean localized) {
		if (referencedEntitySchema != null) {
			return typeRefTo(constructEntityObjectName(referencedEntitySchema, localized));
		} else {
			return typeRefTo(EntityDescriptor.THIS_ENTITY_REFERENCE.name());
		}
	}

	@Nonnull
	private List<OpenApiProperty> buildHierarchyExtraResultProperties(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                                                  boolean localized) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final List<ReferenceSchemaContract> referenceSchemas = entitySchema
			.getReferences()
			.values()
			.stream()
			.filter(referenceSchema -> referenceSchema.isReferencedEntityTypeManaged() &&
				buildingContext.getSchema().getEntitySchema(referenceSchema.getReferencedEntityType())
					.map(EntitySchemaContract::isWithHierarchy)
					.orElseThrow(() -> new OpenApiBuildingError("Reference `" + referenceSchema.getName() + "` should have existing entity schema but no schema found.")))
			.toList();

		if (referenceSchemas.isEmpty() && !entitySchema.isWithHierarchy()) {
			return List.of();
		}

		final List<OpenApiProperty> hierarchyExtraResultProperties = new ArrayList<>(2);

		final OpenApiTypeReference parentsObject = buildParentsObject(
			collectionBuildingContext,
			referenceSchemas,
			localized
		);
		final OpenApiProperty parentsProperty = ExtraResultsDescriptor.HIERARCHY_PARENTS
			.to(propertyBuilderTransformer)
			.type(parentsObject)
			.build();
		hierarchyExtraResultProperties.add(parentsProperty);

		final OpenApiTypeReference hierarchyStatisticsObject = buildHierarchyStatisticsObject(
			collectionBuildingContext,
			referenceSchemas,
			localized
		);
		final OpenApiProperty hierarchyStatisticsProperty = ExtraResultsDescriptor.HIERARCHY_STATISTICS
			.to(propertyBuilderTransformer)
			.type(hierarchyStatisticsObject)
			.build();
		hierarchyExtraResultProperties.add(hierarchyStatisticsProperty);

		return hierarchyExtraResultProperties;
	}

	@Nonnull
	private OpenApiTypeReference buildParentsObject(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                                @Nonnull List<ReferenceSchemaContract> referenceSchemas,
	                                                boolean localized) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final OpenApiObject.Builder parentObjectBuilder = HierarchyParentsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructParentsObjectName(entitySchema, localized));

		if (entitySchema.isWithHierarchy()) {
			parentObjectBuilder.property(buildSelfParentsOfEntityField(collectionBuildingContext, localized));
		}
		referenceSchemas.forEach(referenceSchema ->
			parentObjectBuilder.property(buildParentsOfEntityProperty(collectionBuildingContext, referenceSchema, localized)));

		return buildingContext.registerType(parentObjectBuilder.build());
	}

	@Nonnull
	private OpenApiProperty buildSelfParentsOfEntityField(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                                      boolean localized) {
		final OpenApiTypeReference parentsOfEntityObject = buildSelfParentsOfEntityObject(collectionBuildingContext, localized);
		return HierarchyParentsDescriptor.SELF
			.to(propertyBuilderTransformer)
			.type(arrayOf(parentsOfEntityObject))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildSelfParentsOfEntityObject(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                                            boolean localized) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final OpenApiObject selfParentOfEntityObject = ParentsOfEntityDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructSelfParentsOfEntityObjectName(entitySchema, localized))
			.property(buildSelfParentsOfEntityParentEntitiesField(collectionBuildingContext, localized))
			.build();

		return buildingContext.registerType(selfParentOfEntityObject);
	}

	@Nonnull
	private OpenApiProperty buildSelfParentsOfEntityParentEntitiesField(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                                                    boolean localized) {
		return ParentsOfEntityDescriptor.PARENT_ENTITIES
			.to(propertyBuilderTransformer)
			.type(nonNull(arrayOf(typeRefTo(constructEntityObjectName(collectionBuildingContext.getSchema(), localized)))))
			.build();
	}

	@Nonnull
	private OpenApiProperty buildParentsOfEntityProperty(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                                     @Nonnull ReferenceSchemaContract referenceSchema,
	                                                     boolean localized) {
		final OpenApiTypeReference parentsOfEntityObject = buildParentsOfEntityObject(
			collectionBuildingContext,
			referenceSchema,
			localized
		);

		return newProperty()
			.name(referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
			.type(arrayOf(parentsOfEntityObject))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildParentsOfEntityObject(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                                        @Nonnull ReferenceSchemaContract referenceSchema,
	                                                        boolean localized) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final OpenApiObject parentsOfEntityObject = ParentsOfEntityDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructParentsOfEntityObjectName(entitySchema, referenceSchema, localized))
			.property(buildParentsOfEntityParentEntitiesProperty(referenceSchema, localized))
			.property(buildParentsOfEntityReferencesProperty(collectionBuildingContext, referenceSchema, localized))
			.build();

		return buildingContext.registerType(parentsOfEntityObject);
	}

	@Nonnull
	private OpenApiProperty buildParentsOfEntityParentEntitiesProperty(@Nonnull ReferenceSchemaContract referenceSchema,
	                                                                   boolean localized) {
		final EntitySchemaContract referencedEntitySchema = buildingContext
			.getSchema()
			.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType());
		final String referencedEntityObjectName = constructEntityObjectName(referencedEntitySchema, localized);

		return ParentsOfEntityDescriptor.PARENT_ENTITIES
			.to(propertyBuilderTransformer)
			.type(nonNull(arrayOf(typeRefTo(referencedEntityObjectName))))
			.build();
	}

	@Nonnull
	private OpenApiProperty buildParentsOfEntityReferencesProperty(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                                               @Nonnull ReferenceSchemaContract referenceSchema,
	                                                               boolean localized) {
		final OpenApiTypeReference object = buildParentsOfEntityReferencesObject(
			collectionBuildingContext,
			referenceSchema,
			localized
		);

		return ParentsOfEntityDescriptor.REFERENCES
			.to(propertyBuilderTransformer)
			.type(nonNull(arrayOf(object)))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildParentsOfEntityReferencesObject(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                                                  @Nonnull ReferenceSchemaContract referenceSchema,
	                                                                  boolean localized) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final EntitySchemaContract referencedEntitySchema = buildingContext
			.getSchema()
			.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType());
		final String referencedEntityObjectName = constructEntityObjectName(referencedEntitySchema, localized);

		final OpenApiObject parentsOfEntityReferencesObject = ParentsOfReferenceDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructParentsOfEntityReferencesObjectName(entitySchema, referenceSchema, localized))
			.property(ParentsOfReferenceDescriptor.PARENT_ENTITIES
				.to(propertyBuilderTransformer)
				.type(nonNull(arrayOf(typeRefTo(referencedEntityObjectName)))))
			.build();

		return buildingContext.registerType(parentsOfEntityReferencesObject);
	}

	@Nonnull
	private OpenApiTypeReference buildHierarchyStatisticsObject(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                                            @Nonnull List<ReferenceSchemaContract> referenceSchemas,
	                                                            boolean localized) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final OpenApiObject.Builder hierarchyStatisticsObjectBuilder = HierarchyStatisticsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructHierarchyStatisticsObjectName(entitySchema, localized));

		if (entitySchema.isWithHierarchy()) {
			hierarchyStatisticsObjectBuilder.property(buildSelfLevelInfoProperty(collectionBuildingContext, localized));
		}

		referenceSchemas.forEach(referenceSchema ->
			hierarchyStatisticsObjectBuilder.property(buildLevelInfoProperty(collectionBuildingContext, referenceSchema, localized)));

		return buildingContext.registerType(hierarchyStatisticsObjectBuilder.build());
	}

	@Nonnull
	private OpenApiProperty buildSelfLevelInfoProperty(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                                   boolean localized) {
		final OpenApiTypeReference selfLevelInfoObject = buildSelfLevelInfoObject(collectionBuildingContext, localized);
		return HierarchyStatisticsDescriptor.SELF
			.to(propertyBuilderTransformer)
			.type(arrayOf(selfLevelInfoObject))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildSelfLevelInfoObject(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                                      boolean localized) {
		final String selfLevelInfoObjectName = constructSelfLevelInfoObjectName(collectionBuildingContext.getSchema(), localized);

		final OpenApiObject selfLevelInfoObject = HierarchyStatisticsLevelInfoDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(selfLevelInfoObjectName)
			.property(HierarchyStatisticsLevelInfoDescriptor.CHILDREN_STATISTICS
				.to(propertyBuilderTransformer)
				.type(nonNull(arrayOf(typeRefTo(selfLevelInfoObjectName)))))
			.property(buildSelfLevelInfoEntityProperty(collectionBuildingContext, localized))
			.build();

		return buildingContext.registerType(selfLevelInfoObject);
	}

	@Nonnull
	private OpenApiProperty buildSelfLevelInfoEntityProperty(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                                         boolean localized) {
		final String referencedEntityObjectName = constructEntityObjectName(collectionBuildingContext.getSchema(), localized);

		return HierarchyStatisticsLevelInfoDescriptor.ENTITY
			.to(propertyBuilderTransformer)
			.type(nonNull(typeRefTo(referencedEntityObjectName)))
			.build();
	}

	@Nonnull
	private OpenApiProperty buildLevelInfoProperty(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                               @Nonnull ReferenceSchemaContract referenceSchema,
	                                               boolean localized) {
		final OpenApiTypeReference levelInfoObject = buildLevelInfoObject(collectionBuildingContext, referenceSchema, localized);
		return newProperty()
			.name(referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
			.type(arrayOf(levelInfoObject))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildLevelInfoObject(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                                  @Nonnull ReferenceSchemaContract referenceSchema,
	                                                  boolean localized) {
		final String levelInfoObjectName = constructLevelInfoObjectName(collectionBuildingContext.getSchema(), referenceSchema, localized);

		final OpenApiObject levelInfoObject = HierarchyStatisticsLevelInfoDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(levelInfoObjectName)
			.property(HierarchyStatisticsLevelInfoDescriptor.CHILDREN_STATISTICS
				.to(propertyBuilderTransformer)
				.type(nonNull(arrayOf(typeRefTo(levelInfoObjectName)))))
			.property(buildLevelInfoEntityProperty(referenceSchema, localized))
			.build();

		return buildingContext.registerType(levelInfoObject);
	}

	@Nonnull
	private OpenApiProperty buildLevelInfoEntityProperty(@Nonnull ReferenceSchemaContract referenceSchema,
	                                                     boolean localized) {
		final EntitySchemaContract referencedEntitySchema = buildingContext
			.getSchema()
			.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType());
		final String referencedEntityObjectName = constructEntityObjectName(referencedEntitySchema, localized);

		return HierarchyStatisticsLevelInfoDescriptor.ENTITY
			.to(propertyBuilderTransformer)
			.type(nonNull(typeRefTo(referencedEntityObjectName)))
			.build();
	}
}
