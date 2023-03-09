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
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor.ParentsOfEntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor.ParentsOfEntityDescriptor.ParentsOfReferenceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyStatisticsDescriptor.HierarchyStatisticsLevelInfoDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor;
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

	@Nonnull private final CollectionDataApiRestBuildingContext entitySchemaBuildingCtx;
	@Nonnull private final PropertyDescriptorToOpenApiPropertyTransformer propertyBuilderTransformer;
	@Nonnull private final ObjectDescriptorToOpenApiObjectTransformer objectBuilderTransformer;

	@Nonnull
	public OpenApiObject buildFullResponseObject(boolean localized) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final OpenApiObject.Builder responseObjectBuilder = ResponseDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructEntityFullResponseObjectName(entitySchema, localized))
			.property(buildDataChunkProperty(localized));

		buildExtraResultsProperty(localized).ifPresent(responseObjectBuilder::property);

		return responseObjectBuilder.build();
	}

	@Nonnull
	private OpenApiProperty buildDataChunkProperty(boolean localized) {
		return ResponseDescriptor.RECORD_PAGE
			.to(propertyBuilderTransformer)
			.type(nonNull(buildDataChunkObject(localized)))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildDataChunkObject(boolean localized) {
		final OpenApiObject dataChunkObject = DataChunkAggregateDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructEntityDataChunkAggregateObjectName(entitySchemaBuildingCtx.getSchema(), localized))
			.unionType(OpenApiObjectUnionType.ONE_OF)
			.unionDiscriminator(DataChunkAggregateDescriptor.DISCRIMINATOR.name())
			.unionObject(buildRecordPageObject(localized))
			.unionObject(buildRecordStripObject(localized))
			.build();

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(dataChunkObject);
	}

	@Nonnull
	private OpenApiTypeReference buildRecordPageObject(boolean localized) {
		final OpenApiTypeReference entityObject = typeRefTo(constructEntityObjectName(entitySchemaBuildingCtx.getSchema(), localized));

		final OpenApiObject recordPageObject = RecordPageDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructRecordPageObjectName(entitySchemaBuildingCtx.getSchema(), localized))
			.property(buildDataChunkDataProperty(entityObject))
			.property(createDataChunkDiscriminatorProperty())
			.build();

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(recordPageObject);
	}

	@Nonnull
	private OpenApiTypeReference buildRecordStripObject(boolean localized) {
		final OpenApiTypeReference entityObject = typeRefTo(constructEntityObjectName(entitySchemaBuildingCtx.getSchema(), localized));

		final OpenApiObject recordStripObject = RecordStripDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructRecordStripObjectName(entitySchemaBuildingCtx.getSchema(), localized))
			.property(buildDataChunkDataProperty(entityObject))
			.property(createDataChunkDiscriminatorProperty())
			.build();

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(recordStripObject);
	}

	@Nonnull
	private OpenApiProperty buildDataChunkDataProperty(OpenApiTypeReference entityObject) {
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
	private Optional<OpenApiProperty> buildExtraResultsProperty(boolean localized) {
		final Optional<OpenApiTypeReference> extraResultsObject = buildExtraResultsObject(localized);
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
	private Optional<OpenApiTypeReference> buildExtraResultsObject(boolean localized) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final OpenApiObject.Builder extraResultObjectBuilder = ExtraResultsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructExtraResultsObjectName(entitySchema, localized));

		final List<OpenApiProperty> extraResultProperties = new ArrayList<>(10);

		buildAttributeHistogramProperty().ifPresent(extraResultProperties::add);
		buildPriceHistogramProperty().ifPresent(extraResultProperties::add);
		buildFacetSummaryProperty(localized).ifPresent(extraResultProperties::add);
		extraResultProperties.addAll(buildHierarchyExtraResultProperties(localized));
		extraResultProperties.add(ExtraResultsDescriptor.QUERY_TELEMETRY.to(propertyBuilderTransformer).build());

		if (extraResultProperties.isEmpty()) {
			return Optional.empty();
		}

		extraResultProperties.forEach(extraResultObjectBuilder::property);
		return Optional.of(entitySchemaBuildingCtx.getCatalogCtx().registerType(extraResultObjectBuilder.build()));
	}

	@Nonnull
	private Optional<OpenApiProperty> buildAttributeHistogramProperty() {
		final Optional<OpenApiTypeReference> attributeHistogramObject = buildAttributeHistogramObject();
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
	private Optional<OpenApiTypeReference> buildAttributeHistogramObject() {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final String objectName = AttributeHistogramDescriptor.THIS.name(entitySchema);
		final Optional<OpenApiTypeReference> existingAttributeHistogramObject = entitySchemaBuildingCtx.getCatalogCtx().getRegisteredType(objectName);
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

		return Optional.of(entitySchemaBuildingCtx.getCatalogCtx().registerType(attributeHistogramObjectBuilder.build()));
	}

	@Nonnull
	private Optional<OpenApiProperty> buildPriceHistogramProperty() {
		if (entitySchemaBuildingCtx.getSchema().getCurrencies().isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(ExtraResultsDescriptor.PRICE_HISTOGRAM
			.to(propertyBuilderTransformer)
			.build());
	}

	@Nonnull
	private Optional<OpenApiProperty> buildFacetSummaryProperty(boolean localized) {
		final Optional<OpenApiTypeReference> facetSummaryObject = buildFacetSummaryObject(localized);
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
	private Optional<OpenApiTypeReference> buildFacetSummaryObject(boolean localized) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();
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
			facetSummaryObjectBuilder.property(buildFacetGroupStatisticsProperty(referenceSchema, localized)));

		return Optional.of(entitySchemaBuildingCtx.getCatalogCtx().registerType(facetSummaryObjectBuilder.build()));
	}

	@Nonnull
	private OpenApiProperty buildFacetGroupStatisticsProperty(@Nonnull ReferenceSchemaContract referenceSchema,
	                                                          boolean localized) {
		final OpenApiTypeReference facetGroupStatisticsObject = buildFacetGroupStatisticsObject(referenceSchema, localized);

		return newProperty()
			.name(referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
			.type(arrayOf(facetGroupStatisticsObject))
			.build();
	}


	@Nonnull
	private OpenApiTypeReference buildFacetGroupStatisticsObject(@Nonnull ReferenceSchemaContract referenceSchema,
	                                                             boolean localized) {
		final EntitySchemaContract groupEntitySchema = referenceSchema.isReferencedGroupTypeManaged() ?
			Optional.ofNullable(referenceSchema.getReferencedGroupType())
				.flatMap(groupType -> entitySchemaBuildingCtx.getCatalogCtx()
					.getSchema()
					.getEntitySchema(groupType))
				.orElse(null) :
			null;

		final OpenApiTypeReference groupEntityObject = buildReferencedEntityObject(groupEntitySchema, localized);
		final OpenApiTypeReference facetStatisticsObject = buildFacetStatisticsObject(referenceSchema, localized);

		final OpenApiObject facetGroupStatisticsObject = FacetGroupStatisticsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructFacetGroupStatisticsObjectName(entitySchemaBuildingCtx.getSchema(), referenceSchema, localized))
			.property(FacetGroupStatisticsDescriptor.GROUP_ENTITY
				.to(propertyBuilderTransformer)
				.type(groupEntityObject))
			.property(FacetGroupStatisticsDescriptor.FACET_STATISTICS
				.to(propertyBuilderTransformer)
				.type(nonNull(arrayOf(facetStatisticsObject))))
			.build();

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(facetGroupStatisticsObject);
	}

	@Nonnull
	private OpenApiTypeReference buildFacetStatisticsObject(@Nonnull ReferenceSchemaContract referenceSchema,
	                                                        boolean localized) {
		final EntitySchemaContract facetEntitySchema = referenceSchema.isReferencedEntityTypeManaged()?
			entitySchemaBuildingCtx.getCatalogCtx()
				.getSchema()
				.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType()) :
			null;
		final OpenApiTypeReference facetEntityObject = buildReferencedEntityObject(facetEntitySchema, localized);

		final OpenApiObject facetStatisticsObject = FacetStatisticsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructFacetStatisticsObjectName(entitySchemaBuildingCtx.getSchema(), referenceSchema, localized))
			.property(FacetStatisticsDescriptor.FACET_ENTITY
				.to(propertyBuilderTransformer)
				.type(facetEntityObject))
			.build();

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(facetStatisticsObject);
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
	private List<OpenApiProperty> buildHierarchyExtraResultProperties(boolean localized) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final List<ReferenceSchemaContract> referenceSchemas = entitySchema
			.getReferences()
			.values()
			.stream()
			.filter(referenceSchema -> referenceSchema.isReferencedEntityTypeManaged() &&
				entitySchemaBuildingCtx.getCatalogCtx().getSchema().getEntitySchema(referenceSchema.getReferencedEntityType())
					.map(EntitySchemaContract::isWithHierarchy)
					.orElseThrow(() -> new OpenApiBuildingError("Reference `" + referenceSchema.getName() + "` should have existing entity schema but no schema found.")))
			.toList();

		if (referenceSchemas.isEmpty() && !entitySchema.isWithHierarchy()) {
			return List.of();
		}

		final List<OpenApiProperty> hierarchyExtraResultProperties = new ArrayList<>(2);

		final OpenApiTypeReference parentsObject = buildParentsObject(referenceSchemas, localized);
		final OpenApiProperty parentsProperty = ExtraResultsDescriptor.HIERARCHY_PARENTS
			.to(propertyBuilderTransformer)
			.type(parentsObject)
			.build();
		hierarchyExtraResultProperties.add(parentsProperty);

		final OpenApiTypeReference hierarchyStatisticsObject = buildHierarchyStatisticsObject(referenceSchemas, localized);
		final OpenApiProperty hierarchyStatisticsProperty = ExtraResultsDescriptor.HIERARCHY_STATISTICS
			.to(propertyBuilderTransformer)
			.type(hierarchyStatisticsObject)
			.build();
		hierarchyExtraResultProperties.add(hierarchyStatisticsProperty);

		return hierarchyExtraResultProperties;
	}

	@Nonnull
	private OpenApiTypeReference buildParentsObject(@Nonnull List<ReferenceSchemaContract> referenceSchemas,
	                                                boolean localized) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final OpenApiObject.Builder parentObjectBuilder = HierarchyParentsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructParentsObjectName(entitySchema, localized));

		if (entitySchema.isWithHierarchy()) {
			parentObjectBuilder.property(buildSelfParentsOfEntityField(localized));
		}
		referenceSchemas.forEach(referenceSchema ->
			parentObjectBuilder.property(buildParentsOfEntityProperty(referenceSchema, localized)));

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(parentObjectBuilder.build());
	}

	@Nonnull
	private OpenApiProperty buildSelfParentsOfEntityField(boolean localized) {
		final OpenApiTypeReference parentsOfEntityObject = buildSelfParentsOfEntityObject(localized);
		return HierarchyParentsDescriptor.SELF
			.to(propertyBuilderTransformer)
			.type(arrayOf(parentsOfEntityObject))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildSelfParentsOfEntityObject(boolean localized) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final OpenApiObject selfParentOfEntityObject = ParentsOfEntityDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructSelfParentsOfEntityObjectName(entitySchema, localized))
			.property(buildSelfParentsOfEntityParentEntitiesField(localized))
			.build();

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(selfParentOfEntityObject);
	}

	@Nonnull
	private OpenApiProperty buildSelfParentsOfEntityParentEntitiesField(boolean localized) {
		return ParentsOfEntityDescriptor.PARENT_ENTITIES
			.to(propertyBuilderTransformer)
			.type(nonNull(arrayOf(typeRefTo(constructEntityObjectName(entitySchemaBuildingCtx.getSchema(), localized)))))
			.build();
	}

	@Nonnull
	private OpenApiProperty buildParentsOfEntityProperty(@Nonnull ReferenceSchemaContract referenceSchema,
	                                                     boolean localized) {
		final OpenApiTypeReference parentsOfEntityObject = buildParentsOfEntityObject(referenceSchema, localized);

		return newProperty()
			.name(referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
			.type(arrayOf(parentsOfEntityObject))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildParentsOfEntityObject(@Nonnull ReferenceSchemaContract referenceSchema,
	                                                        boolean localized) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final OpenApiObject parentsOfEntityObject = ParentsOfEntityDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructParentsOfEntityObjectName(entitySchema, referenceSchema, localized))
			.property(buildParentsOfEntityParentEntitiesProperty(referenceSchema, localized))
			.property(buildParentsOfEntityReferencesProperty(referenceSchema, localized))
			.build();

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(parentsOfEntityObject);
	}

	@Nonnull
	private OpenApiProperty buildParentsOfEntityParentEntitiesProperty(@Nonnull ReferenceSchemaContract referenceSchema,
	                                                                   boolean localized) {
		final EntitySchemaContract referencedEntitySchema = entitySchemaBuildingCtx.getCatalogCtx()
			.getSchema()
			.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType());
		final String referencedEntityObjectName = constructEntityObjectName(referencedEntitySchema, localized);

		return ParentsOfEntityDescriptor.PARENT_ENTITIES
			.to(propertyBuilderTransformer)
			.type(nonNull(arrayOf(typeRefTo(referencedEntityObjectName))))
			.build();
	}

	@Nonnull
	private OpenApiProperty buildParentsOfEntityReferencesProperty(@Nonnull ReferenceSchemaContract referenceSchema,
	                                                               boolean localized) {
		final OpenApiTypeReference object = buildParentsOfEntityReferencesObject(referenceSchema, localized);

		return ParentsOfEntityDescriptor.REFERENCES
			.to(propertyBuilderTransformer)
			.type(nonNull(arrayOf(object)))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildParentsOfEntityReferencesObject(@Nonnull ReferenceSchemaContract referenceSchema,
	                                                                  boolean localized) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final EntitySchemaContract referencedEntitySchema = entitySchemaBuildingCtx.getCatalogCtx()
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

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(parentsOfEntityReferencesObject);
	}

	@Nonnull
	private OpenApiTypeReference buildHierarchyStatisticsObject(@Nonnull List<ReferenceSchemaContract> referenceSchemas,
	                                                            boolean localized) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final OpenApiObject.Builder hierarchyStatisticsObjectBuilder = HierarchyStatisticsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructHierarchyStatisticsObjectName(entitySchema, localized));

		if (entitySchema.isWithHierarchy()) {
			hierarchyStatisticsObjectBuilder.property(buildSelfLevelInfoProperty(localized));
		}

		referenceSchemas.forEach(referenceSchema ->
			hierarchyStatisticsObjectBuilder.property(buildLevelInfoProperty(referenceSchema, localized)));

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(hierarchyStatisticsObjectBuilder.build());
	}

	@Nonnull
	private OpenApiProperty buildSelfLevelInfoProperty(boolean localized) {
		final OpenApiTypeReference selfLevelInfoObject = buildSelfLevelInfoObject(localized);
		return HierarchyStatisticsDescriptor.SELF
			.to(propertyBuilderTransformer)
			.type(arrayOf(selfLevelInfoObject))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildSelfLevelInfoObject(boolean localized) {
		final String selfLevelInfoObjectName = constructSelfLevelInfoObjectName(entitySchemaBuildingCtx.getSchema(), localized);

		final OpenApiObject selfLevelInfoObject = HierarchyStatisticsLevelInfoDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(selfLevelInfoObjectName)
			.property(HierarchyStatisticsLevelInfoDescriptor.CHILDREN_STATISTICS
				.to(propertyBuilderTransformer)
				.type(nonNull(arrayOf(typeRefTo(selfLevelInfoObjectName)))))
			.property(buildSelfLevelInfoEntityProperty(localized))
			.build();

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(selfLevelInfoObject);
	}

	@Nonnull
	private OpenApiProperty buildSelfLevelInfoEntityProperty(boolean localized) {
		final String referencedEntityObjectName = constructEntityObjectName(entitySchemaBuildingCtx.getSchema(), localized);

		return HierarchyStatisticsLevelInfoDescriptor.ENTITY
			.to(propertyBuilderTransformer)
			.type(nonNull(typeRefTo(referencedEntityObjectName)))
			.build();
	}

	@Nonnull
	private OpenApiProperty buildLevelInfoProperty(@Nonnull ReferenceSchemaContract referenceSchema, boolean localized) {
		final OpenApiTypeReference levelInfoObject = buildLevelInfoObject(referenceSchema, localized);
		return newProperty()
			.name(referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
			.type(arrayOf(levelInfoObject))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildLevelInfoObject(@Nonnull ReferenceSchemaContract referenceSchema, boolean localized) {
		final String levelInfoObjectName = constructLevelInfoObjectName(entitySchemaBuildingCtx.getSchema(), referenceSchema, localized);

		final OpenApiObject levelInfoObject = HierarchyStatisticsLevelInfoDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(levelInfoObjectName)
			.property(HierarchyStatisticsLevelInfoDescriptor.CHILDREN_STATISTICS
				.to(propertyBuilderTransformer)
				.type(nonNull(arrayOf(typeRefTo(levelInfoObjectName)))))
			.property(buildLevelInfoEntityProperty(referenceSchema, localized))
			.build();

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(levelInfoObject);
	}

	@Nonnull
	private OpenApiProperty buildLevelInfoEntityProperty(@Nonnull ReferenceSchemaContract referenceSchema,
	                                                     boolean localized) {
		final EntitySchemaContract referencedEntitySchema = entitySchemaBuildingCtx
			.getCatalogCtx()
			.getSchema()
			.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType());
		final String referencedEntityObjectName = constructEntityObjectName(referencedEntitySchema, localized);

		return HierarchyStatisticsLevelInfoDescriptor.ENTITY
			.to(propertyBuilderTransformer)
			.type(nonNull(typeRefTo(referencedEntityObjectName)))
			.build();
	}
}
