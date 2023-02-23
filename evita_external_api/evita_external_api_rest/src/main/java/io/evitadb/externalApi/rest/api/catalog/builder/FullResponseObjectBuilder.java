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
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.api.catalog.dataApi.model.DataChunkDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.RecordPageDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.RecordStripDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ResponseDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetGroupStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor.ParentsOfEntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor.ParentsOfEntityDescriptor.ParentsOfReferenceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyStatisticsDescriptor.HierarchyStatisticsLevelInfoDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.QueryTelemetryDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.PropertyDescriptorToOpenApiSchemaTransformer.Property;
import io.evitadb.externalApi.rest.api.catalog.model.DataChunkAggregateDescriptor;
import io.evitadb.externalApi.rest.exception.OpenApiSchemaBuildingError;
import io.evitadb.externalApi.rest.io.model.DataChunk;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.Schema;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.FIELD_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.rest.api.catalog.builder.NamesConstructor.*;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createArraySchemaOf;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createObjectSchema;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createReferenceSchema;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaPropertyUtils.addProperty;
import static io.evitadb.externalApi.rest.api.catalog.builder.transformer.Transformers.OBJECT_TRANSFORMER;
import static io.evitadb.externalApi.rest.api.catalog.builder.transformer.Transformers.PROPERTY_TRANSFORMER;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Builder for full query response. Response contains in this case not only main entity data but also additional data
 * like facets, statistics, etc.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class FullResponseObjectBuilder {

	private final OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx;
	private final boolean localizedUrl;

	@Nonnull
	public Schema<Object> buildFullResponseObject() {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final Schema<Object> responseObject = ResponseDescriptor.THIS
			.to(OBJECT_TRANSFORMER);
		responseObject.name(constructEntityFullResponseObjectName(entitySchema, !localizedUrl));

		addProperty(responseObject, buildDataChunkProperty());
		buildExtraResultsObject().ifPresent(it -> addProperty(responseObject, ResponseDescriptor.EXTRA_RESULTS, it, false));

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(responseObject);
	}

	@Nonnull
	private Property buildDataChunkProperty() {
		final Schema<Object> dataChunkObject = buildDataChunkObject();
		dataChunkObject.name(ResponseDescriptor.RECORD_PAGE.name());
		return new Property(dataChunkObject, true);
	}

	@Nonnull
	private Schema<Object> buildDataChunkObject() {
		final Schema<Object> dataChunkObject = DataChunkDescriptor.THIS.to(OBJECT_TRANSFORMER);
		dataChunkObject.name(constructEntityDataChunkObjectName(entitySchemaBuildingCtx.getSchema(), !localizedUrl));
		dataChunkObject
			.addOneOfItem(buildRecordPageObject())
			.addOneOfItem(buildRecordStripObject())
			.discriminator(new Discriminator().propertyName(DataChunkAggregateDescriptor.DISCRIMINATOR.name()));
		return entitySchemaBuildingCtx.getCatalogCtx().registerType(dataChunkObject);
	}

	@Nonnull
	private Schema<Object> buildRecordPageObject() {
		final Schema<Object> recordPageObject = RecordPageDescriptor.THIS
			.to(OBJECT_TRANSFORMER);
		recordPageObject.name(constructRecordPageObjectName(entitySchemaBuildingCtx.getSchema(), !localizedUrl));

		//this is correct as for localized URL (i.e. one locale) is non-localized entity sufficient
		final var entityObject = localizedUrl ? entitySchemaBuildingCtx.getEntityObject() : entitySchemaBuildingCtx.getLocalizedEntityObject();
		final ArraySchema dataPropertySchema = createArraySchemaOf(entityObject);
		dataPropertySchema
			.name(DataChunkDescriptor.DATA.name())
			.description(DataChunkDescriptor.DATA.description());
		addProperty(recordPageObject, new Property(dataPropertySchema, DataChunkDescriptor.DATA.type() != null && DataChunkDescriptor.DATA.type().nonNull()));

		addProperty(recordPageObject, createRecordPageTypeProperty());
		addProperty(recordPageObject, RecordPageDescriptor.PAGE_SIZE);
		addProperty(recordPageObject, RecordPageDescriptor.PAGE_NUMBER);
		addProperty(recordPageObject, RecordPageDescriptor.LAST_PAGE_NUMBER);
		addProperty(recordPageObject, RecordPageDescriptor.FIRST_PAGE_ITEM_NUMBER);
		addProperty(recordPageObject, RecordPageDescriptor.LAST_PAGE_ITEM_NUMBER);
		addProperty(recordPageObject, DataChunkDescriptor.TOTAL_RECORD_COUNT);
		addProperty(recordPageObject, DataChunkDescriptor.FIRST);
		addProperty(recordPageObject, DataChunkDescriptor.LAST);
		addProperty(recordPageObject, DataChunkDescriptor.HAS_PREVIOUS);
		addProperty(recordPageObject, DataChunkDescriptor.HAS_NEXT);
		addProperty(recordPageObject, DataChunkDescriptor.SINGLE_PAGE);
		addProperty(recordPageObject, DataChunkDescriptor.EMPTY);

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(recordPageObject);
	}

	@Nonnull
	private Schema<Object> buildRecordStripObject() {
		final Schema<Object> recordStringObject = createObjectSchema();
		recordStringObject
			.name(constructRecordStripObjectName(entitySchemaBuildingCtx.getSchema(), !localizedUrl));

		//this is correct as for localized URL (i.e. one locale) is non-localized entity sufficient
		final var entitySchema = localizedUrl ? entitySchemaBuildingCtx.getEntityObject() : entitySchemaBuildingCtx.getLocalizedEntityObject();
		final ArraySchema dataPropertySchema = createArraySchemaOf(entitySchema);
		dataPropertySchema
			.name(DataChunkDescriptor.DATA.name())
			.description(DataChunkDescriptor.DATA.description());
		addProperty(recordStringObject, new Property(dataPropertySchema, DataChunkDescriptor.DATA.type() != null && DataChunkDescriptor.DATA.type().nonNull()));

		addProperty(recordStringObject, createRecordPageTypeProperty());
		addProperty(recordStringObject, RecordStripDescriptor.OFFSET);
		addProperty(recordStringObject, RecordStripDescriptor.LIMIT);
		addProperty(recordStringObject, DataChunkDescriptor.TOTAL_RECORD_COUNT);
		addProperty(recordStringObject, DataChunkDescriptor.FIRST);
		addProperty(recordStringObject, DataChunkDescriptor.LAST);
		addProperty(recordStringObject, DataChunkDescriptor.HAS_PREVIOUS);
		addProperty(recordStringObject, DataChunkDescriptor.HAS_NEXT);
		addProperty(recordStringObject, DataChunkDescriptor.SINGLE_PAGE);
		addProperty(recordStringObject, DataChunkDescriptor.EMPTY);

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(recordStringObject);
	}

	@Nonnull
	private static Property createRecordPageTypeProperty() {
		final Property typeProperty = DataChunkAggregateDescriptor.DISCRIMINATOR.to(PROPERTY_TRANSFORMER);
		typeProperty.schema().addEnumItemObject(DataChunk.RECORD_PAGE);
		typeProperty.schema().addEnumItemObject(DataChunk.RECORD_STRIP);
		return typeProperty;
	}

	@Nonnull
	private Optional<Schema<Object>> buildExtraResultsObject() {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final Schema<Object> extraResultObject = createObjectSchema();
		extraResultObject
			.name(constructExtraResultsObjectName(entitySchema, !localizedUrl))
			.description(ExtraResultsDescriptor.THIS.description());

		final Map<PropertyDescriptor, Schema<Object>> extraResultFields = createHashMap(10);

		buildAttributeHistogramObject().ifPresent(object -> extraResultFields.put(ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM, object));
		buildPriceHistogramObject().ifPresent(object -> extraResultFields.put(ExtraResultsDescriptor.PRICE_HISTOGRAM, object));
		buildQueryTelemetryObject().ifPresent(object -> extraResultFields.put(ExtraResultsDescriptor.QUERY_TELEMETRY, object));
		buildFacetSummaryProperty().ifPresent(object -> extraResultFields.put(ExtraResultsDescriptor.FACET_SUMMARY, object));
		buildAndAddHierarchyExtraResultFields(extraResultFields);

		if (extraResultFields.isEmpty()) {
			return Optional.empty();
		}
		extraResultFields.forEach((descriptor, property) -> addProperty(extraResultObject, descriptor, property, descriptor.type() != null && descriptor.type().nonNull()));

		return Optional.of(entitySchemaBuildingCtx.getCatalogCtx().registerType(extraResultObject));
	}

	private void buildAndAddHierarchyExtraResultFields(@Nonnull final Map<PropertyDescriptor, Schema<Object>> extraResultFields) {
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
			return;
		}

		extraResultFields.put(ExtraResultsDescriptor.HIERARCHY_PARENTS, buildParentsObject(referenceSchemas));
		extraResultFields.put(ExtraResultsDescriptor.HIERARCHY_STATISTICS, buildHierarchyStatisticsObject(referenceSchemas));
	}

	@Nonnull
	private Schema<Object> buildHierarchyStatisticsObject(@Nonnull List<ReferenceSchemaContract> referenceSchemas) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final Schema<Object> hierarchyStatisticsObject = HierarchyStatisticsDescriptor.THIS
			.to(OBJECT_TRANSFORMER);
		hierarchyStatisticsObject.name(constructHierarchyStatisticsObjectName(entitySchema, !localizedUrl));

		if (entitySchema.isWithHierarchy()) {
			addProperty(hierarchyStatisticsObject, HierarchyStatisticsDescriptor.SELF, createArraySchemaOf(buildSelfLevelInfoObject()), true);
		}

		referenceSchemas.forEach(referenceSchema -> {
			final Schema<Object> parentsOfEntityObject = createArraySchemaOf(buildLevelInfoObject(referenceSchema));
			parentsOfEntityObject.name(referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION));
			addProperty(hierarchyStatisticsObject, new Property(parentsOfEntityObject, true));
		});

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(hierarchyStatisticsObject);
	}

	@Nonnull
	private Schema<Object> buildLevelInfoObject(@Nonnull ReferenceSchemaContract referenceSchema) {
		final Schema<Object> levelInfoObject = HierarchyStatisticsLevelInfoDescriptor.THIS
			.to(OBJECT_TRANSFORMER);
		levelInfoObject.name(constructLevelInfoObjectName(entitySchemaBuildingCtx.getSchema(), referenceSchema, !localizedUrl));

		final ArraySchema childrenStatsObject = createArraySchemaOf(createReferenceSchema(levelInfoObject));
		addProperty(levelInfoObject, HierarchyStatisticsLevelInfoDescriptor.CHILDREN_STATISTICS, childrenStatsObject, true);

		addProperty(levelInfoObject, HierarchyStatisticsLevelInfoDescriptor.ENTITY, getEntitySchema(entitySchemaBuildingCtx.getSchema()), true);

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(levelInfoObject);
	}

	@Nonnull
	private Schema<Object> buildParentsObject(@Nonnull List<ReferenceSchemaContract> referenceSchemas) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final Schema<Object> parentObject = HierarchyParentsDescriptor.THIS
			.to(OBJECT_TRANSFORMER);
		parentObject.name(constructParentsObjectName(entitySchema, !localizedUrl));

		if (entitySchema.isWithHierarchy()) {
			final Schema<Object> parentsOfEntityObject = buildSelfParentsOfEntityObject();
			addProperty(parentObject, HierarchyParentsDescriptor.SELF, createArraySchemaOf(parentsOfEntityObject), true);
		}
		referenceSchemas.forEach(referenceSchema -> {
			final Schema<Object> parentsOfEntityObject = createArraySchemaOf(buildParentsOfEntityObject(referenceSchema));
			parentsOfEntityObject.name(referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION));
			addProperty(parentObject, new Property(parentsOfEntityObject, true));
		});

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(parentObject);
	}

	@Nonnull
	private Schema<Object> buildSelfLevelInfoObject() {
		final Schema<Object> levelInfoObject = HierarchyStatisticsLevelInfoDescriptor.THIS
			.to(OBJECT_TRANSFORMER);
		levelInfoObject.name(constructSelfLevelInfoObjectName(entitySchemaBuildingCtx.getSchema(), !localizedUrl));

		final ArraySchema childrenStatsObject = createArraySchemaOf(createReferenceSchema(levelInfoObject));
		addProperty(levelInfoObject, HierarchyStatisticsLevelInfoDescriptor.CHILDREN_STATISTICS, childrenStatsObject, true);

		addProperty(levelInfoObject, HierarchyStatisticsLevelInfoDescriptor.ENTITY, getEntitySchema(entitySchemaBuildingCtx.getSchema()), true);

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(levelInfoObject);
	}

	@Nonnull
	private Schema<Object> buildParentsOfEntityObject(@Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final Schema<Object> parentsOfEntityObject = ParentsOfEntityDescriptor.THIS
			.to(OBJECT_TRANSFORMER);
		parentsOfEntityObject.name(constructParentsOfEntityObjectName(entitySchema, referenceSchema, !localizedUrl));

		addProperty(parentsOfEntityObject, ParentsOfEntityDescriptor.PARENT_ENTITIES, buildParentsOfEntityParentEntitiesObject(referenceSchema), true);
		addProperty(parentsOfEntityObject, ParentsOfEntityDescriptor.REFERENCES, createArraySchemaOf(buildParentsOfEntityReferencesObject(referenceSchema)), true);

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(parentsOfEntityObject);
	}

	@Nonnull
	private Schema<Object> buildParentsOfEntityReferencesObject(@Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final EntitySchemaContract referencedEntitySchema = entitySchemaBuildingCtx.getCatalogCtx()
			.getSchema()
			.getEntitySchema(referenceSchema.getReferencedEntityType())
			.orElseThrow(() -> new OpenApiSchemaBuildingError("Could not find referenced entity schema `" + referenceSchema.getReferencedEntityType() + "` for references object of parents."));
		final String referencedEntityName = NamesConstructor.constructEntityName(referencedEntitySchema, !localizedUrl);

		final Schema<Object> entityReferencesObject = ParentsOfReferenceDescriptor.THIS
			.to(OBJECT_TRANSFORMER);
		entityReferencesObject.name(constructParentsOfEntityReferencesObjectName(entitySchema, referenceSchema, !localizedUrl));

		final ArraySchema parentEntities = createArraySchemaOf(createReferenceSchema(referencedEntityName));
		addProperty(entityReferencesObject, ParentsOfReferenceDescriptor.PARENT_ENTITIES, parentEntities, true);

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(entityReferencesObject);
	}

	@Nonnull
	private Schema<Object> buildParentsOfEntityParentEntitiesObject(@Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract referencedEntitySchema = entitySchemaBuildingCtx.getCatalogCtx()
			.getSchema()
			.getEntitySchema(referenceSchema.getReferencedEntityType())
			.orElseThrow(() -> new OpenApiSchemaBuildingError("Could not find referenced entity schema `" + referenceSchema.getReferencedEntityType() + "` for parent entities object of parents."));
		final String referencedEntityName = NamesConstructor.constructEntityName(referencedEntitySchema, !localizedUrl);

		final ArraySchema parentEntities = createArraySchemaOf(createReferenceSchema(referencedEntityName));
		parentEntities.description(ParentsOfEntityDescriptor.PARENT_ENTITIES.description());
		return parentEntities;
	}

	@Nonnull
	private Schema<Object> buildSelfParentsOfEntityObject() {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final Schema<Object> selfParentOfEntityObject = ParentsOfEntityDescriptor.THIS
			.to(OBJECT_TRANSFORMER);
		selfParentOfEntityObject.name(constructSelfParentsOfEntityObjectName(entitySchema, !localizedUrl));

		addProperty(selfParentOfEntityObject, ParentsOfEntityDescriptor.PARENT_ENTITIES, buildSelfParentsOfEntityParentEntitiesField(), true);

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(selfParentOfEntityObject);
	}

	@Nonnull
	private Schema<Object> buildSelfParentsOfEntityParentEntitiesField() {
		final Schema<Object> entitySchema = getEntitySchema(entitySchemaBuildingCtx.getSchema());

		final ArraySchema parentEntities = createArraySchemaOf(entitySchema);
		parentEntities.description(ParentsOfEntityDescriptor.PARENT_ENTITIES.description());
		return parentEntities;
	}

	private Schema<Object> getEntitySchema(@Nonnull EntitySchemaContract entitySchema) {
		final String referencedEntityObjectName = NamesConstructor.constructEntityName(entitySchema, !localizedUrl);
		return entitySchemaBuildingCtx.getCatalogCtx().getRegisteredType(referencedEntityObjectName)
			.orElseThrow(() -> new OpenApiSchemaBuildingError("Can't find entity " + referencedEntityObjectName + " in OpenAPI schemas."));
	}

	@Nonnull
	private Optional<Schema<Object>> buildFacetSummaryProperty() {
		final boolean hasFacets = entitySchemaBuildingCtx.getSchema()
			.getReferences()
			.values()
			.stream()
			.anyMatch(ReferenceSchemaContract::isFaceted);

		if (!hasFacets) {
			return Optional.empty();
		}

		return Optional.of(buildFacetSummaryObject());
	}

	@Nonnull
	private Optional<Schema<Object>> buildAttributeHistogramObject() {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final String objectName = constructAttributeHistogramObjectName(entitySchema);
		final Optional<Schema<Object>> existingAttributeHistogramObject = entitySchemaBuildingCtx.getCatalogCtx().getRegisteredType(objectName);
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

		final Schema<Object> histogramAttributeObject = createObjectSchema();
		histogramAttributeObject.name(objectName);

		attributeSchemas.forEach(attributeSchema -> {
			final Schema<Object> histogramProperty = createReferenceSchema(HistogramDescriptor.THIS);
			histogramProperty.name(attributeSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION));
			addProperty(histogramAttributeObject, new Property(histogramProperty, false));
		});

		return Optional.of(entitySchemaBuildingCtx.getCatalogCtx().registerType(histogramAttributeObject));
	}

	@Nonnull
	private Optional<Schema<Object>> buildPriceHistogramObject() {
		if (entitySchemaBuildingCtx.getSchema().getCurrencies().isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(createReferenceSchema(HistogramDescriptor.THIS));
	}

	@Nonnull
	private static Optional<Schema<Object>> buildQueryTelemetryObject() {
		return Optional.of(createReferenceSchema(QueryTelemetryDescriptor.THIS.name()));
	}

	@Nonnull
	private Schema<Object> buildFacetSummaryObject() {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final Schema<Object> summaryObjectSchema = createObjectSchema();
		summaryObjectSchema.name(constructFacetSummaryObjectName(entitySchema, !localizedUrl));

		final List<ReferenceSchemaContract> referenceSchemas = entitySchema
			.getReferences()
			.values()
			.stream()
			.filter(ReferenceSchemaContract::isFaceted)
			.toList();

		referenceSchemas.forEach(referenceSchema -> {
			final ArraySchema facetGroupStatisticsProperty = createArraySchemaOf(buildFacetGroupStatisticsObject(referenceSchema));
			facetGroupStatisticsProperty.name(referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION));
			addProperty(summaryObjectSchema, new Property(facetGroupStatisticsProperty, false));
		});

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(summaryObjectSchema);
	}


	@Nonnull
	private Schema<Object> buildFacetGroupStatisticsObject(@Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract groupEntitySchema = referenceSchema.isReferencedGroupTypeManaged() ?
			(Optional.ofNullable(referenceSchema.getReferencedGroupType())
				.flatMap(groupType -> entitySchemaBuildingCtx.getCatalogCtx()
					.getSchema()
					.getEntitySchema(groupType))
				.orElse(null)
			) : null;

		final Schema<Object> groupEntityObject = buildReferencedEntityObject(groupEntitySchema);
		final Schema<Object> facetStatisticsObject = createArraySchemaOf(buildFacetStatisticsObject(referenceSchema));

		final Schema<Object> groupStatisticsSchema = createObjectSchema();
		groupStatisticsSchema.name(constructFacetGroupStatisticsObjectName(entitySchemaBuildingCtx.getSchema(), referenceSchema, !localizedUrl));
		addProperty(groupStatisticsSchema, FacetGroupStatisticsDescriptor.GROUP_ENTITY, groupEntityObject, false);
		addProperty(groupStatisticsSchema, FacetGroupStatisticsDescriptor.FACET_STATISTICS, facetStatisticsObject, false);

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(groupStatisticsSchema);
	}

	@Nonnull
	private Schema<Object> buildFacetStatisticsObject(@Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract facetEntitySchema = referenceSchema.isReferencedEntityTypeManaged()?
			(entitySchemaBuildingCtx.getCatalogCtx()
			.getSchema()
			.getEntitySchema(referenceSchema.getReferencedEntityType())
			.orElseThrow(() -> new EvitaInternalError("The reference `" + referenceSchema.getReferencedEntityType() + "` refers to unknown entity!"))
			):null;

		final Schema<Object> facetStatisticsSchema = FacetStatisticsDescriptor.THIS
			.to(OBJECT_TRANSFORMER);
		facetStatisticsSchema.name(constructFacetStatisticsObjectName(entitySchemaBuildingCtx.getSchema(), referenceSchema, !localizedUrl));
		addProperty(facetStatisticsSchema, FacetStatisticsDescriptor.FACET_ENTITY, buildReferencedEntityObject(facetEntitySchema), false);

		return entitySchemaBuildingCtx.getCatalogCtx().registerType(facetStatisticsSchema);
	}

	@Nonnull
	private Schema<Object> buildReferencedEntityObject(@Nullable EntitySchemaContract referencedEntitySchema) {
		if (referencedEntitySchema != null) {
			return createReferenceSchema(constructEntityName(referencedEntitySchema, !localizedUrl));
		} else {
			return createReferenceSchema(EntityDescriptor.THIS_ENTITY_REFERENCE);
		}
	}

}
