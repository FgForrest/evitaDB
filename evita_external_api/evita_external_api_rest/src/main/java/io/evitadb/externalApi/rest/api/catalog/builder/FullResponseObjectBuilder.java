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

package io.evitadb.externalApi.rest.api.catalog.builder;

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
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.ObjectDescriptorToOpenApiObjectTransformer;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.PropertyDescriptorToOpenApiPropertyTransformer;
import io.evitadb.externalApi.rest.api.catalog.model.DataChunkAggregateDescriptor;
import io.evitadb.externalApi.rest.api.dto.DataChunkType;
import io.evitadb.externalApi.rest.api.dto.OpenApiObject;
import io.evitadb.externalApi.rest.api.dto.OpenApiObjectUnionType;
import io.evitadb.externalApi.rest.api.dto.OpenApiProperty;
import io.evitadb.externalApi.rest.api.dto.OpenApiTypeReference;
import io.evitadb.externalApi.rest.exception.OpenApiSchemaBuildingError;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.FIELD_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.rest.api.catalog.builder.NamesConstructor.*;
import static io.evitadb.externalApi.rest.api.dto.OpenApiArray.arrayOf;
import static io.evitadb.externalApi.rest.api.dto.OpenApiNonNull.nonNull;
import static io.evitadb.externalApi.rest.api.dto.OpenApiProperty.newProperty;
import static io.evitadb.externalApi.rest.api.dto.OpenApiTypeReference.typeRefTo;

/**
 * Builder for full query response. Response contains in this case not only main entity data but also additional data
 * like facets, statistics, etc.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class FullResponseObjectBuilder {

	@Nonnull private final OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx;
	@Nonnull private final PropertyDescriptorToOpenApiPropertyTransformer propertyBuilderTransformer;
	@Nonnull private final ObjectDescriptorToOpenApiObjectTransformer objectBuilderTransformer;
	private final boolean localizedUrl;

	@Nonnull
	public OpenApiTypeReference buildFullResponseObject() {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final OpenApiObject.Builder responseObjectBuilder = ResponseDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructEntityFullResponseObjectName(entitySchema, !localizedUrl))
			.property(buildDataChunkProperty());

		buildExtraResultsProperty().ifPresent(responseObjectBuilder::property);

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(responseObjectBuilder.build());
	}

	@Nonnull
	private OpenApiProperty buildDataChunkProperty() {
		return ResponseDescriptor.RECORD_PAGE
			.to(propertyBuilderTransformer)
			.type(nonNull(buildDataChunkObject()))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildDataChunkObject() {
		final OpenApiObject dataChunkObject = DataChunkAggregateDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructEntityDataChunkAggregateObjectName(entitySchemaBuildingCtx.getSchema(), !localizedUrl))
			.unionType(OpenApiObjectUnionType.ONE_OF)
			.unionDiscriminator(DataChunkAggregateDescriptor.DISCRIMINATOR.name())
			.unionObject(buildRecordPageObject())
			.unionObject(buildRecordStripObject())
			.build();

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(dataChunkObject);
	}

	@Nonnull
	private OpenApiTypeReference buildRecordPageObject() {
		//this is correct as for localized URL (i.e. one locale) is non-localized entity sufficient
		// todo lho the localizedUrl logic is confusing
		final OpenApiTypeReference entityObject = typeRefTo(constructEntityObjectName(entitySchemaBuildingCtx.getSchema(), !localizedUrl));

		final OpenApiObject recordPageObject = RecordPageDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructRecordPageObjectName(entitySchemaBuildingCtx.getSchema(), !localizedUrl))
			.property(buildDataChunkDataProperty(entityObject))
			.property(createDataChunkDiscriminatorProperty())
			.build();

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(recordPageObject);
	}

	@Nonnull
	private OpenApiTypeReference buildRecordStripObject() {
		//this is correct as for localized URL (i.e. one locale) is non-localized entity sufficient
		// todo lho the localizedUrl logic is confusing
		final OpenApiTypeReference entityObject = typeRefTo(constructEntityObjectName(entitySchemaBuildingCtx.getSchema(), !localizedUrl));

		final OpenApiObject recordStripObject = RecordStripDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructRecordStripObjectName(entitySchemaBuildingCtx.getSchema(), !localizedUrl))
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
	private Optional<OpenApiProperty> buildExtraResultsProperty() {
		final Optional<OpenApiTypeReference> extraResultsObject = buildExtraResultsObject();
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
	private Optional<OpenApiTypeReference> buildExtraResultsObject() {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final OpenApiObject.Builder extraResultObjectBuilder = ExtraResultsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructExtraResultsObjectName(entitySchema, !localizedUrl));

		final List<OpenApiProperty> extraResultProperties = new ArrayList<>(10);

		buildAttributeHistogramProperty().ifPresent(extraResultProperties::add);
		buildPriceHistogramProperty().ifPresent(extraResultProperties::add);
		buildFacetSummaryProperty().ifPresent(extraResultProperties::add);
		extraResultProperties.addAll(buildHierarchyExtraResultProperties());
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
	private Optional<OpenApiProperty> buildFacetSummaryProperty() {
		final Optional<OpenApiTypeReference> facetSummaryObject = buildFacetSummaryObject();
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
	private Optional<OpenApiTypeReference> buildFacetSummaryObject() {
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
			.name(constructFacetSummaryObjectName(entitySchema, !localizedUrl));

		referenceSchemas.forEach(referenceSchema ->
			facetSummaryObjectBuilder.property(buildFacetGroupStatisticsProperty(referenceSchema)));

		return Optional.of(entitySchemaBuildingCtx.getCatalogCtx().registerType(facetSummaryObjectBuilder.build()));
	}

	@Nonnull
	private OpenApiProperty buildFacetGroupStatisticsProperty(@Nonnull ReferenceSchemaContract referenceSchema) {
		final OpenApiTypeReference facetGroupStatisticsObject = buildFacetGroupStatisticsObject(referenceSchema);

		return newProperty()
			.name(referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
			.type(arrayOf(facetGroupStatisticsObject))
			.build();
	}


	@Nonnull
	private OpenApiTypeReference buildFacetGroupStatisticsObject(@Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract groupEntitySchema = referenceSchema.isReferencedGroupTypeManaged() ?
			Optional.ofNullable(referenceSchema.getReferencedGroupType())
				.flatMap(groupType -> entitySchemaBuildingCtx.getCatalogCtx()
					.getSchema()
					.getEntitySchema(groupType))
				.orElse(null) :
			null;

		final OpenApiTypeReference groupEntityObject = buildReferencedEntityObject(groupEntitySchema);
		final OpenApiTypeReference facetStatisticsObject = buildFacetStatisticsObject(referenceSchema);

		final OpenApiObject facetGroupStatisticsObject = FacetGroupStatisticsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructFacetGroupStatisticsObjectName(entitySchemaBuildingCtx.getSchema(), referenceSchema, !localizedUrl))
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
	private OpenApiTypeReference buildFacetStatisticsObject(@Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract facetEntitySchema = referenceSchema.isReferencedEntityTypeManaged()?
			entitySchemaBuildingCtx.getCatalogCtx()
				.getSchema()
				.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType()) :
			null;
		final OpenApiTypeReference facetEntityObject = buildReferencedEntityObject(facetEntitySchema);

		final OpenApiObject facetStatisticsObject = FacetStatisticsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructFacetStatisticsObjectName(entitySchemaBuildingCtx.getSchema(), referenceSchema, !localizedUrl))
			.property(FacetStatisticsDescriptor.FACET_ENTITY
				.to(propertyBuilderTransformer)
				.type(facetEntityObject))
			.build();

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(facetStatisticsObject);
	}

	@Nonnull
	private OpenApiTypeReference buildReferencedEntityObject(@Nullable EntitySchemaContract referencedEntitySchema) {
		if (referencedEntitySchema != null) {
			return typeRefTo(constructEntityObjectName(referencedEntitySchema, !localizedUrl));
		} else {
			return typeRefTo(EntityDescriptor.THIS_ENTITY_REFERENCE.name());
		}
	}

	@Nonnull
	private List<OpenApiProperty> buildHierarchyExtraResultProperties() {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final List<ReferenceSchemaContract> referenceSchemas = entitySchema
			.getReferences()
			.values()
			.stream()
			.filter(referenceSchema -> referenceSchema.isReferencedEntityTypeManaged() &&
				entitySchemaBuildingCtx.getCatalogCtx().getSchema().getEntitySchema(referenceSchema.getReferencedEntityType())
					.map(EntitySchemaContract::isWithHierarchy)
					.orElseThrow(() -> new OpenApiSchemaBuildingError("Reference `" + referenceSchema.getName() + "` should have existing entity schema but no schema found.")))
			.toList();

		if (referenceSchemas.isEmpty() && !entitySchema.isWithHierarchy()) {
			return List.of();
		}

		final List<OpenApiProperty> hierarchyExtraResultProperties = new ArrayList<>(2);

		final OpenApiTypeReference parentsObject = buildParentsObject(referenceSchemas);
		final OpenApiProperty parentsProperty = ExtraResultsDescriptor.HIERARCHY_PARENTS
			.to(propertyBuilderTransformer)
			.type(parentsObject)
			.build();
		hierarchyExtraResultProperties.add(parentsProperty);

		final OpenApiTypeReference hierarchyStatisticsObject = buildHierarchyStatisticsObject(referenceSchemas);
		final OpenApiProperty hierarchyStatisticsProperty = ExtraResultsDescriptor.HIERARCHY_STATISTICS
			.to(propertyBuilderTransformer)
			.type(hierarchyStatisticsObject)
			.build();
		hierarchyExtraResultProperties.add(hierarchyStatisticsProperty);

		return hierarchyExtraResultProperties;
	}

	@Nonnull
	private OpenApiTypeReference buildParentsObject(@Nonnull List<ReferenceSchemaContract> referenceSchemas) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final OpenApiObject.Builder parentObjectBuilder = HierarchyParentsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructParentsObjectName(entitySchema, !localizedUrl));

		if (entitySchema.isWithHierarchy()) {
			parentObjectBuilder.property(buildSelfParentsOfEntityField());
		}
		referenceSchemas.forEach(referenceSchema ->
			parentObjectBuilder.property(buildParentsOfEntityProperty(referenceSchema)));

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(parentObjectBuilder.build());
	}

	@Nonnull
	private OpenApiProperty buildSelfParentsOfEntityField() {
		final OpenApiTypeReference parentsOfEntityObject = buildSelfParentsOfEntityObject();
		return HierarchyParentsDescriptor.SELF
			.to(propertyBuilderTransformer)
			.type(arrayOf(parentsOfEntityObject))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildSelfParentsOfEntityObject() {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final OpenApiObject selfParentOfEntityObject = ParentsOfEntityDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructSelfParentsOfEntityObjectName(entitySchema, !localizedUrl))
			.property(buildSelfParentsOfEntityParentEntitiesField())
			.build();

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(selfParentOfEntityObject);
	}

	@Nonnull
	private OpenApiProperty buildSelfParentsOfEntityParentEntitiesField() {
		return ParentsOfEntityDescriptor.PARENT_ENTITIES
			.to(propertyBuilderTransformer)
			.type(nonNull(arrayOf(typeRefTo(constructEntityObjectName(entitySchemaBuildingCtx.getSchema(), !localizedUrl)))))
			.build();
	}

	@Nonnull
	private OpenApiProperty buildParentsOfEntityProperty(@Nonnull ReferenceSchemaContract referenceSchema) {
		final OpenApiTypeReference parentsOfEntityObject = buildParentsOfEntityObject(referenceSchema);

		return newProperty()
			.name(referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
			.type(arrayOf(parentsOfEntityObject))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildParentsOfEntityObject(@Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final OpenApiObject parentsOfEntityObject = ParentsOfEntityDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructParentsOfEntityObjectName(entitySchema, referenceSchema, !localizedUrl))
			.property(buildParentsOfEntityParentEntitiesProperty(referenceSchema))
			.property(buildParentsOfEntityReferencesProperty(referenceSchema))
			.build();

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(parentsOfEntityObject);
	}

	@Nonnull
	private OpenApiProperty buildParentsOfEntityParentEntitiesProperty(@Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract referencedEntitySchema = entitySchemaBuildingCtx.getCatalogCtx()
			.getSchema()
			.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType());
		final String referencedEntityObjectName = constructEntityObjectName(referencedEntitySchema, !localizedUrl);

		return ParentsOfEntityDescriptor.PARENT_ENTITIES
			.to(propertyBuilderTransformer)
			.type(nonNull(arrayOf(typeRefTo(referencedEntityObjectName))))
			.build();
	}

	@Nonnull
	private OpenApiProperty buildParentsOfEntityReferencesProperty(@Nonnull ReferenceSchemaContract referenceSchema) {
		final OpenApiTypeReference object = buildParentsOfEntityReferencesObject(referenceSchema);

		return ParentsOfEntityDescriptor.REFERENCES
			.to(propertyBuilderTransformer)
			.type(nonNull(arrayOf(object)))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildParentsOfEntityReferencesObject(@Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final EntitySchemaContract referencedEntitySchema = entitySchemaBuildingCtx.getCatalogCtx()
			.getSchema()
			.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType());
		final String referencedEntityObjectName = constructEntityObjectName(referencedEntitySchema, !localizedUrl);

		final OpenApiObject parentsOfEntityReferencesObject = ParentsOfReferenceDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructParentsOfEntityReferencesObjectName(entitySchema, referenceSchema, !localizedUrl))
			.property(ParentsOfReferenceDescriptor.PARENT_ENTITIES
				.to(propertyBuilderTransformer)
				.type(nonNull(arrayOf(typeRefTo(referencedEntityObjectName)))))
			.build();

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(parentsOfEntityReferencesObject);
	}

	@Nonnull
	private OpenApiTypeReference buildHierarchyStatisticsObject(@Nonnull List<ReferenceSchemaContract> referenceSchemas) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final OpenApiObject.Builder hierarchyStatisticsObjectBuilder = HierarchyStatisticsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(constructHierarchyStatisticsObjectName(entitySchema, !localizedUrl));

		if (entitySchema.isWithHierarchy()) {
			hierarchyStatisticsObjectBuilder.property(buildSelfLevelInfoProperty());
		}

		referenceSchemas.forEach(referenceSchema ->
			hierarchyStatisticsObjectBuilder.property(buildLevelInfoProperty(referenceSchema)));

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(hierarchyStatisticsObjectBuilder.build());
	}

	@Nonnull
	private OpenApiProperty buildSelfLevelInfoProperty() {
		final OpenApiTypeReference selfLevelInfoObject = buildSelfLevelInfoObject();
		return HierarchyStatisticsDescriptor.SELF
			.to(propertyBuilderTransformer)
			.type(arrayOf(selfLevelInfoObject))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildSelfLevelInfoObject() {
		final String selfLevelInfoObjectName = constructSelfLevelInfoObjectName(entitySchemaBuildingCtx.getSchema(), !localizedUrl);

		final OpenApiObject selfLevelInfoObject = HierarchyStatisticsLevelInfoDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(selfLevelInfoObjectName)
			.property(HierarchyStatisticsLevelInfoDescriptor.CHILDREN_STATISTICS
				.to(propertyBuilderTransformer)
				.type(nonNull(arrayOf(typeRefTo(selfLevelInfoObjectName)))))
			.property(buildSelfLevelInfoEntityProperty())
			.build();

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(selfLevelInfoObject);
	}

	@Nonnull
	private OpenApiProperty buildSelfLevelInfoEntityProperty() {
		final String referencedEntityObjectName = constructEntityObjectName(entitySchemaBuildingCtx.getSchema(), !localizedUrl);

		return HierarchyStatisticsLevelInfoDescriptor.ENTITY
			.to(propertyBuilderTransformer)
			.type(nonNull(typeRefTo(referencedEntityObjectName)))
			.build();
	}

	@Nonnull
	private OpenApiProperty buildLevelInfoProperty(@Nonnull ReferenceSchemaContract referenceSchema) {
		final OpenApiTypeReference levelInfoObject = buildLevelInfoObject(referenceSchema);
		return newProperty()
			.name(referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
			.type(arrayOf(levelInfoObject))
			.build();
	}

	@Nonnull
	private OpenApiTypeReference buildLevelInfoObject(@Nonnull ReferenceSchemaContract referenceSchema) {
		final String levelInfoObjectName = constructLevelInfoObjectName(entitySchemaBuildingCtx.getSchema(), referenceSchema, !localizedUrl);

		final OpenApiObject levelInfoObject = HierarchyStatisticsLevelInfoDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(levelInfoObjectName)
			.property(HierarchyStatisticsLevelInfoDescriptor.CHILDREN_STATISTICS
				.to(propertyBuilderTransformer)
				.type(nonNull(arrayOf(typeRefTo(levelInfoObjectName)))))
			.property(buildLevelInfoEntityProperty(referenceSchema))
			.build();

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(levelInfoObject);
	}

	@Nonnull
	private OpenApiProperty buildLevelInfoEntityProperty(@Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract referencedEntitySchema = entitySchemaBuildingCtx
			.getCatalogCtx()
			.getSchema()
			.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType());
		final String referencedEntityObjectName = constructEntityObjectName(referencedEntitySchema, !localizedUrl);

		return HierarchyStatisticsLevelInfoDescriptor.ENTITY
			.to(propertyBuilderTransformer)
			.type(nonNull(typeRefTo(referencedEntityObjectName)))
			.build();
	}
}
