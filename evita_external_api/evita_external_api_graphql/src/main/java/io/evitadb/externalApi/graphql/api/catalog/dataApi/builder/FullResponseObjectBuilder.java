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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.PropertyDataFetcher;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.RequestImpact;
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
import io.evitadb.externalApi.graphql.api.builder.BuiltFieldDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.ResponseHeaderDescriptor.BucketsFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.ResponseHeaderDescriptor.QueryTelemetryFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.ResponseHeaderDescriptor.RecordPageFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.ResponseHeaderDescriptor.RecordStripFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.RecordPageDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.RecordStripDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.SingleParentsOfReferenceDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult.AttributeHistogramDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult.AttributeHistogramsDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult.ExtraResultsDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult.FacetGroupStatisticsDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult.FacetSummaryDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult.HierarchyParentsDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult.HierarchyStatisticsDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult.PriceHistogramDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult.QueryTelemetryDataFetcher;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLObjectTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLArgumentTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLFieldTransformer;
import io.evitadb.externalApi.graphql.exception.GraphQLSchemaBuildingError;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLList.list;
import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLTypeReference.typeRef;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.FIELD_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.TYPE_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.graphql.api.dataType.GraphQLScalars.OBJECT;
import static io.evitadb.externalApi.graphql.api.dataType.GraphQLScalars.STRING;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2023
 */
@RequiredArgsConstructor
public class FullResponseObjectBuilder {

	private static final ObjectMapper QUERY_TELEMETRY_OBJECT_MAPPER = new ObjectMapper();

	@Nonnull private final CatalogGraphQLSchemaBuildingContext buildingContext;
	@Nonnull private final PropertyDescriptorToGraphQLArgumentTransformer argumentBuilderTransformer;
	@Nonnull private final ObjectDescriptorToGraphQLObjectTransformer objectBuilderTransformer;
	@Nonnull private final PropertyDescriptorToGraphQLFieldTransformer fieldBuilderTransformer;

	public void buildCommonTypes() {
		buildingContext.registerType(BucketDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(buildHistogramObject());
		// todo lho: remove after https://gitlab.fg.cz/hv/evita/-/issues/120 is implemented
		buildingContext.registerType(buildAttributeNamedHistogramObject());
		buildingContext.registerType(buildFacetRequestImpactObject());
	}

	@Nonnull
	public GraphQLObjectType build(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();
		final String objectName = ResponseDescriptor.THIS.name(entitySchema);

		final GraphQLObjectType.Builder responseObjectBuilder = ResponseDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		final List<BuiltFieldDescriptor> responseFields = new LinkedList<>();

		responseFields.add(buildRecordPageField(collectionBuildingContext));
		responseFields.add(buildRecordStripField(collectionBuildingContext));
		buildExtraResultsField(collectionBuildingContext).ifPresent(responseFields::add);

		responseFields.forEach(responseField ->
			buildingContext.registerFieldToObject(
				objectName,
				responseObjectBuilder,
				responseField
			)
		);

		return responseObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildRecordPageField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final GraphQLObjectType recordPageObject = buildRecordPageObject(collectionBuildingContext);

		final GraphQLFieldDefinition recordPageField = ResponseDescriptor.RECORD_PAGE
			.to(fieldBuilderTransformer)
			.type(recordPageObject)
			.argument(RecordPageFieldHeaderDescriptor.NUMBER.to(argumentBuilderTransformer))
			.argument(RecordPageFieldHeaderDescriptor.SIZE.to(argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			recordPageField,
			new RecordPageDataFetcher()
		);
	}

	@Nonnull
	private GraphQLObjectType buildRecordPageObject(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final String objectName = RecordPageDescriptor.THIS.name(entitySchema);

		return RecordPageDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName)
			.field(DataChunkDescriptor.DATA
				.to(fieldBuilderTransformer)
				.type(nonNull(list(nonNull(typeRef(EntityDescriptor.THIS.name(entitySchema)))))))
			.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildRecordStripField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final GraphQLObjectType recordStripObject = buildRecordStripObject(collectionBuildingContext);

		final GraphQLFieldDefinition recordStripField = ResponseDescriptor.RECORD_STRIP
			.to(fieldBuilderTransformer)
			.type(recordStripObject)
			.argument(RecordStripFieldHeaderDescriptor.OFFSET.to(argumentBuilderTransformer))
			.argument(RecordStripFieldHeaderDescriptor.LIMIT.to(argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			recordStripField,
			new RecordStripDataFetcher()
		);
	}

	@Nonnull
	private GraphQLObjectType buildRecordStripObject(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final String objectName = RecordStripDescriptor.THIS.name(collectionBuildingContext.getSchema());

		return RecordStripDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName)
			.field(DataChunkDescriptor.DATA
				.to(fieldBuilderTransformer)
				.type(nonNull(list(nonNull(typeRef(EntityDescriptor.THIS.name(collectionBuildingContext.getSchema())))))))
			.build();
	}

	@Nonnull
	private Optional<BuiltFieldDescriptor> buildExtraResultsField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final Optional<GraphQLObjectType> extraResultsObject = buildExtraResultsObject(collectionBuildingContext);
		if (extraResultsObject.isEmpty()) {
			return Optional.empty();
		}

		final GraphQLFieldDefinition extraResultsField = ResponseDescriptor.EXTRA_RESULTS
			.to(fieldBuilderTransformer)
			.type(nonNull(extraResultsObject.get()))
			.build();

		return Optional.of(new BuiltFieldDescriptor(
			extraResultsField,
			new ExtraResultsDataFetcher()
		));
	}

	@Nonnull
	private Optional<GraphQLObjectType> buildExtraResultsObject(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final String objectName = ExtraResultsDescriptor.THIS.name(entitySchema);

		final GraphQLObjectType.Builder extraResultsObjectBuilder = ExtraResultsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		final List<BuiltFieldDescriptor> extraResultFields = new ArrayList<>(10);

		buildAttributeHistogramField(collectionBuildingContext).ifPresent(extraResultFields::add);
		// todo lho: remove after https://gitlab.fg.cz/hv/evita/-/issues/120 is implemented
		buildAttributeHistogramsField(collectionBuildingContext).ifPresent(extraResultFields::add);
		buildPriceHistogramField(collectionBuildingContext).ifPresent(extraResultFields::add);
		buildFacetSummaryField(collectionBuildingContext).ifPresent(extraResultFields::add);
		extraResultFields.addAll(buildHierarchyExtraResultFields(collectionBuildingContext));
		extraResultFields.add(buildQueryTelemetryField());

		if (extraResultFields.isEmpty()) {
			return Optional.empty();
		}

		extraResultFields.forEach(extraResultField ->
			buildingContext.registerFieldToObject(
				objectName,
				extraResultsObjectBuilder,
				extraResultField
			)
		);
		return Optional.of(extraResultsObjectBuilder.build());
	}

	@Nonnull
	private Optional<BuiltFieldDescriptor> buildAttributeHistogramField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final Optional<GraphQLObjectType> attributeHistogramObject = buildAttributeHistogramObject(collectionBuildingContext);
		if (attributeHistogramObject.isEmpty()) {
			return Optional.empty();
		}

		final GraphQLFieldDefinition attributeHistogramField = ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM
			.to(fieldBuilderTransformer)
			.type(attributeHistogramObject.get())
			.build();

		return Optional.of(new BuiltFieldDescriptor(
			attributeHistogramField,
			new AttributeHistogramDataFetcher()
		));
	}

	@Nonnull
	private Optional<GraphQLObjectType> buildAttributeHistogramObject(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

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

		final GraphQLObjectType.Builder attributeHistogramsObjectBuilder = AttributeHistogramDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(AttributeHistogramDescriptor.THIS.name(entitySchema));
		attributeSchemas.forEach(attributeSchema ->
			attributeHistogramsObjectBuilder.field(f -> f
				.name(attributeSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
				.type(typeRef(HistogramDescriptor.THIS.name())))
		);

		return Optional.of(attributeHistogramsObjectBuilder.build());
	}

	// todo lho: remove after https://gitlab.fg.cz/hv/evita/-/issues/120 is implemented
	@Nonnull
	private static Optional<BuiltFieldDescriptor> buildAttributeHistogramsField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final GraphQLFieldDefinition attributeHistogramField = newFieldDefinition()
			.name("attributeHistograms")
			.type(list(nonNull(typeRef("AttributeNamedHistogram"))))
			.argument(a -> a
				.name("attributes")
				.type(nonNull(list(nonNull(STRING)))))
			.build();

		return Optional.of(new BuiltFieldDescriptor(
			attributeHistogramField,
			new AttributeHistogramsDataFetcher(collectionBuildingContext.getSchema())
		));
	}

	@Nonnull
	private Optional<BuiltFieldDescriptor> buildPriceHistogramField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		if (collectionBuildingContext.getSchema().getCurrencies().isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(new BuiltFieldDescriptor(
			ExtraResultsDescriptor.PRICE_HISTOGRAM.to(fieldBuilderTransformer).build(),
			new PriceHistogramDataFetcher()
		));
	}

	@Nonnull
	private Optional<BuiltFieldDescriptor> buildFacetSummaryField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final Optional<GraphQLObjectType> facetSummaryObject = buildFacetSummaryObject(collectionBuildingContext);
		if (facetSummaryObject.isEmpty()) {
			return Optional.empty();
		}

		final GraphQLFieldDefinition facetSummaryField = ExtraResultsDescriptor.FACET_SUMMARY
			.to(fieldBuilderTransformer)
			.type(facetSummaryObject.get())
			.build();

		return Optional.of(new BuiltFieldDescriptor(
			facetSummaryField,
			new FacetSummaryDataFetcher()
		));
	}

	@Nonnull
	private Optional<GraphQLObjectType> buildFacetSummaryObject(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
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

		final String objectName = FacetSummaryDescriptor.THIS.name(entitySchema);

		final GraphQLObjectType.Builder facetSummaryObjectBuilder = FacetSummaryDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);


		referenceSchemas.forEach(referenceSchema -> {
			final BuiltFieldDescriptor facetGroupStatisticsField = buildFacetGroupStatisticsField(
				collectionBuildingContext,
				referenceSchema
			);

			buildingContext.registerFieldToObject(
				objectName,
				facetSummaryObjectBuilder,
				facetGroupStatisticsField
			);
		});

		return Optional.of(facetSummaryObjectBuilder.build());
	}

	@Nonnull
	private BuiltFieldDescriptor buildFacetGroupStatisticsField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
	                                                            @Nonnull ReferenceSchemaContract referenceSchema) {
		final GraphQLObjectType facetGroupStatisticsObject = buildFacetGroupStatisticsObject(
			collectionBuildingContext,
			referenceSchema
		);

		final GraphQLFieldDefinition facetGroupStatisticsField =  newFieldDefinition()
			.name(referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
			.type(list(nonNull(facetGroupStatisticsObject)))
			.build();

		return new BuiltFieldDescriptor(
			facetGroupStatisticsField,
			new FacetGroupStatisticsDataFetcher(referenceSchema)
		);
	}

	@Nonnull
	private GraphQLObjectType buildFacetGroupStatisticsObject(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
	                                                          @Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract groupEntitySchema = referenceSchema.isReferencedGroupTypeManaged() ?
			Optional.ofNullable(referenceSchema.getReferencedGroupType())
				.map(groupType -> buildingContext
					.getSchema()
					.getEntitySchemaOrThrowException(groupType))
				.orElse(null) :
			null;

		final GraphQLOutputType groupEntityObject = buildReferencedEntityObject(groupEntitySchema);
		final GraphQLObjectType facetStatisticsObject = buildFacetStatisticsObject(collectionBuildingContext, referenceSchema);

		return FacetGroupStatisticsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(FacetGroupStatisticsDescriptor.THIS.name(collectionBuildingContext.getSchema(), referenceSchema))
			.field(FacetGroupStatisticsDescriptor.GROUP_ENTITY
				.to(fieldBuilderTransformer)
				.type(groupEntityObject))
			.field(FacetGroupStatisticsDescriptor.FACET_STATISTICS
				.to(fieldBuilderTransformer)
				.type(nonNull(list(nonNull(facetStatisticsObject)))))
			.build();
	}

	@Nonnull
	private GraphQLObjectType buildFacetStatisticsObject(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
	                                                     @Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract facetEntitySchema = referenceSchema.isReferencedEntityTypeManaged() ?
			buildingContext
				.getSchema()
				.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType()) :
			null;
		final GraphQLOutputType facetEntityObject = buildReferencedEntityObject(facetEntitySchema);

		return FacetStatisticsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(FacetStatisticsDescriptor.THIS.name(collectionBuildingContext.getSchema(), referenceSchema))
			.field(FacetStatisticsDescriptor.FACET_ENTITY
				.to(fieldBuilderTransformer)
				.type(facetEntityObject))
			.build();
	}

	@Nonnull
	private List<BuiltFieldDescriptor> buildHierarchyExtraResultFields(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final List<ReferenceSchemaContract> referenceSchemas = entitySchema
			.getReferences()
			.values()
			.stream()
			.filter(referenceSchema -> referenceSchema.isReferencedEntityTypeManaged() &&
				buildingContext.getSchema().getEntitySchema(referenceSchema.getReferencedEntityType())
					.map(EntitySchemaContract::isWithHierarchy)
					.orElseThrow(() -> new GraphQLSchemaBuildingError("Reference `" + referenceSchema.getName() + "` should have existing entity schema but no schema found.")))
			.toList();

		if (referenceSchemas.isEmpty() && !entitySchema.isWithHierarchy()) {
			return List.of();
		}

		final List<BuiltFieldDescriptor> hierarchyExtraResultFields = new ArrayList<>(2);

		final GraphQLObjectType parentsObject = buildParentsObject(collectionBuildingContext, referenceSchemas);
		final GraphQLFieldDefinition parentsField = ExtraResultsDescriptor.HIERARCHY_PARENTS
			.to(fieldBuilderTransformer)
			.type(parentsObject)
			.build();
		hierarchyExtraResultFields.add(new BuiltFieldDescriptor(
			parentsField,
			new HierarchyParentsDataFetcher(entitySchema.getReferences().values())
		));

		final GraphQLObjectType hierarchyStatisticsObject = buildHierarchyStatisticsObject(collectionBuildingContext, referenceSchemas);
		final GraphQLFieldDefinition hierarchyStatisticsField = ExtraResultsDescriptor.HIERARCHY_STATISTICS
			.to(fieldBuilderTransformer)
			.type(hierarchyStatisticsObject)
			.build();
		hierarchyExtraResultFields.add(new BuiltFieldDescriptor(
			hierarchyStatisticsField,
			new HierarchyStatisticsDataFetcher(entitySchema.getReferences().values())
		));

		return hierarchyExtraResultFields;
	}

	@Nonnull
	private GraphQLObjectType buildParentsObject(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
	                                             @Nonnull List<ReferenceSchemaContract> referenceSchemas) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final String objectName = HierarchyParentsDescriptor.THIS.name(entitySchema);

		final GraphQLObjectType.Builder parentsObjectBuilder = HierarchyParentsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		if (entitySchema.isWithHierarchy()) {
			buildingContext.registerFieldToObject(
				objectName,
				parentsObjectBuilder,
				buildSelfParentsOfEntityField(collectionBuildingContext)
			);
		}
		referenceSchemas.forEach(referenceSchema ->
			buildingContext.registerFieldToObject(
				objectName,
				parentsObjectBuilder,
				buildParentsOfEntityField(collectionBuildingContext, referenceSchema)
			)
		);

		return parentsObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildSelfParentsOfEntityField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final GraphQLObjectType parentsOfEntityObject = buildSelfParentsOfEntityObject(collectionBuildingContext);

		final GraphQLFieldDefinition parentsField = HierarchyParentsDescriptor.SELF
			.to(fieldBuilderTransformer)
			.type(list(nonNull(parentsOfEntityObject)))
			.build();

		return new BuiltFieldDescriptor(parentsField, null);
	}

	@Nonnull
	private GraphQLObjectType buildSelfParentsOfEntityObject(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final String objectName = ParentsOfEntityDescriptor.THIS.name(entitySchema, entitySchema);

		final GraphQLObjectType.Builder parentsOfEntityObjectBuilder = ParentsOfEntityDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		buildingContext.registerFieldToObject(
			objectName,
			parentsOfEntityObjectBuilder,
			buildSelfParentsOfEntityParentEntitiesField(collectionBuildingContext)
		);

		return parentsOfEntityObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildSelfParentsOfEntityParentEntitiesField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final GraphQLFieldDefinition parentEntitiesField = ParentsOfEntityDescriptor.PARENT_ENTITIES
			.to(fieldBuilderTransformer)
			.type(nonNull(list(nonNull(typeRef(EntityDescriptor.THIS.name(collectionBuildingContext.getSchema()))))))
			.build();

		return new BuiltFieldDescriptor(
			parentEntitiesField,
			new SingleParentsOfReferenceDataFetcher()
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildParentsOfEntityField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
	                                                       @Nonnull ReferenceSchemaContract referenceSchema) {
		final GraphQLObjectType parentsOfEntityObject = buildParentsOfEntityObject(collectionBuildingContext, referenceSchema);

		final GraphQLFieldDefinition singleParentsField = newFieldDefinition()
			.name(referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
			.type(list(nonNull(parentsOfEntityObject)))
			.build();

		return new BuiltFieldDescriptor(singleParentsField, null);
	}

	@Nonnull
	private GraphQLObjectType buildParentsOfEntityObject(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
	                                                     @Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final String objectName = ParentsOfEntityDescriptor.THIS.name(entitySchema, referenceSchema);

		final GraphQLObjectType.Builder parentsOfEntityObjectBuilder = ParentsOfEntityDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		buildingContext.registerFieldToObject(
			objectName,
			parentsOfEntityObjectBuilder,
			buildParentsOfEntityParentEntitiesField(referenceSchema)
		);

		buildingContext.registerFieldToObject(
			objectName,
			parentsOfEntityObjectBuilder,
			buildParentsOfEntityReferencesField(collectionBuildingContext, referenceSchema)
		);

		return parentsOfEntityObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildParentsOfEntityReferencesField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
	                                                                 @Nonnull ReferenceSchemaContract referenceSchema) {
		final GraphQLObjectType object = buildParentsOfEntityReferencesObject(collectionBuildingContext, referenceSchema);

		final GraphQLFieldDefinition referencesField = ParentsOfEntityDescriptor.REFERENCES
			.to(fieldBuilderTransformer)
			.type(nonNull(list(nonNull(object))))
			.build();

		return new BuiltFieldDescriptor(referencesField, null);
	}

	@Nonnull
	private GraphQLObjectType buildParentsOfEntityReferencesObject(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
	                                                               @Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract referencedEntitySchema = buildingContext
			.getSchema()
			.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType());
		final String referencedEntityObjectName = referencedEntitySchema.getNameVariant(TYPE_NAME_NAMING_CONVENTION);

		return ParentsOfReferenceDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(ParentsOfReferenceDescriptor.THIS.name(collectionBuildingContext.getSchema(), referenceSchema))
			.field(ParentsOfReferenceDescriptor.PARENT_ENTITIES
				.to(fieldBuilderTransformer)
				.type(nonNull(list(nonNull(typeRef(referencedEntityObjectName))))))
			.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildParentsOfEntityParentEntitiesField(@Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract referencedEntitySchema = buildingContext
			.getSchema()
			.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType());
		final String referencedEntityObjectName = referencedEntitySchema.getNameVariant(TYPE_NAME_NAMING_CONVENTION);

		final GraphQLFieldDefinition parentEntitiesField = ParentsOfEntityDescriptor.PARENT_ENTITIES
			.to(fieldBuilderTransformer)
			.type(nonNull(list(nonNull(typeRef(referencedEntityObjectName)))))
			.build();

		return new BuiltFieldDescriptor(
			parentEntitiesField,
			new SingleParentsOfReferenceDataFetcher()
		);
	}

	@Nonnull
	private GraphQLObjectType buildHierarchyStatisticsObject(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
	                                                         @Nonnull List<ReferenceSchemaContract> referenceSchemas) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final String objectName = HierarchyStatisticsDescriptor.THIS.name(entitySchema);
		final GraphQLObjectType.Builder hierarchyStatisticsObjectBuilder = HierarchyStatisticsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		if (entitySchema.isWithHierarchy()) {
			buildingContext.registerFieldToObject(
				objectName,
				hierarchyStatisticsObjectBuilder,
				buildSelfLevelInfoField(collectionBuildingContext)
			);
		}
		referenceSchemas.forEach(referenceSchema ->
			buildingContext.registerFieldToObject(
				objectName,
				hierarchyStatisticsObjectBuilder,
				buildLevelInfoField(collectionBuildingContext, referenceSchema)
			)
		);

		return hierarchyStatisticsObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildSelfLevelInfoField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final GraphQLObjectType selfLevelInfoObject = buildSelfLevelInfoObject(collectionBuildingContext);
		final GraphQLFieldDefinition selfLevelInfoField = HierarchyStatisticsDescriptor.SELF
			.to(fieldBuilderTransformer)
			.type(list(nonNull(selfLevelInfoObject)))
			.build();
		return new BuiltFieldDescriptor(selfLevelInfoField, null);
	}

	@Nonnull
	private GraphQLObjectType buildSelfLevelInfoObject(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final String objectName = HierarchyStatisticsLevelInfoDescriptor.THIS.name(entitySchema, entitySchema);

		final GraphQLObjectType.Builder selfLevelInfoObjectBuilder = HierarchyStatisticsLevelInfoDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName)
			.field(HierarchyStatisticsLevelInfoDescriptor.CHILDREN_STATISTICS
				.to(fieldBuilderTransformer)
				.type(nonNull(list(nonNull(typeRef(objectName))))));

		buildingContext.registerFieldToObject(
			objectName,
			selfLevelInfoObjectBuilder,
			buildSelfLevelInfoEntityField(collectionBuildingContext)
		);

		return selfLevelInfoObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildSelfLevelInfoEntityField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();
		final String referencedEntityObjectName = entitySchema.getNameVariant(TYPE_NAME_NAMING_CONVENTION);

		final GraphQLFieldDefinition entityField = HierarchyStatisticsLevelInfoDescriptor.ENTITY
			.to(fieldBuilderTransformer)
			.type(nonNull(typeRef(referencedEntityObjectName)))
			.build();

		return new BuiltFieldDescriptor(
			entityField,
			null
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildLevelInfoField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
	                                                 @Nonnull ReferenceSchemaContract referenceSchema) {
		final GraphQLObjectType levelInfoObject = buildLevelInfoObject(collectionBuildingContext, referenceSchema);
		final GraphQLFieldDefinition levelInfoField = newFieldDefinition()
			.name(referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
			.type(list(nonNull(levelInfoObject)))
			.build();
		return new BuiltFieldDescriptor(levelInfoField, null);
	}

	@Nonnull
	private GraphQLObjectType buildLevelInfoObject(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
	                                               @Nonnull ReferenceSchemaContract referenceSchema) {
		final String objectName = HierarchyStatisticsLevelInfoDescriptor.THIS.name(collectionBuildingContext.getSchema(), referenceSchema);

		final GraphQLObjectType.Builder levelInfoObjectBuilder = HierarchyStatisticsLevelInfoDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName)
			.field(HierarchyStatisticsLevelInfoDescriptor.CHILDREN_STATISTICS
				.to(fieldBuilderTransformer)
				.type(nonNull(list(nonNull(typeRef(objectName))))));

		buildingContext.registerFieldToObject(
			objectName,
			levelInfoObjectBuilder,
			buildLevelInfoEntityField(referenceSchema)
		);

		return levelInfoObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildLevelInfoEntityField(@Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract referencedEntitySchema = buildingContext
			.getSchema()
			.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType());
		final String referencedEntityObjectName = referencedEntitySchema.getNameVariant(TYPE_NAME_NAMING_CONVENTION);

		final GraphQLFieldDefinition entityField = HierarchyStatisticsLevelInfoDescriptor.ENTITY
			.to(fieldBuilderTransformer)
			.type(nonNull(typeRef(referencedEntityObjectName)))
			.build();

		return new BuiltFieldDescriptor(
			entityField,
			null
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildQueryTelemetryField() {
		return new BuiltFieldDescriptor(
			ExtraResultsDescriptor.QUERY_TELEMETRY
				.to(fieldBuilderTransformer)
				.type(nonNull(OBJECT)) // workaround because GQL doesn't support infinite recursive structures
				.argument(QueryTelemetryFieldHeaderDescriptor.FORMATTED.to(argumentBuilderTransformer))
				.build(),
			new QueryTelemetryDataFetcher(QUERY_TELEMETRY_OBJECT_MAPPER)
		);
	}

	@Nonnull
	private static GraphQLOutputType buildReferencedEntityObject(@Nullable EntitySchemaContract referencedEntitySchema) {
		if (referencedEntitySchema != null) {
			return typeRef(EntityDescriptor.THIS.name(referencedEntitySchema));
		} else {
			return typeRef(EntityDescriptor.THIS_ENTITY_REFERENCE.name());
		}
	}


	@Nonnull
	private GraphQLObjectType buildHistogramObject() {
		return HistogramDescriptor.THIS
			.to(objectBuilderTransformer)
			.field(HistogramDescriptor.BUCKETS
				.to(fieldBuilderTransformer)
				.argument(BucketsFieldHeaderDescriptor.REQUESTED_COUNT.to(argumentBuilderTransformer)))
			.build();
	}

	// todo lho: remove after https://gitlab.fg.cz/hv/evita/-/issues/120 is implemented
	@Nonnull
	private GraphQLObjectType buildAttributeNamedHistogramObject() {
		return HistogramDescriptor.THIS
			.to(objectBuilderTransformer)
			.name("AttributeNamedHistogram")
			.field(f -> f.name("attributeName").type(nonNull(STRING)))
			.field(HistogramDescriptor.BUCKETS
				.to(fieldBuilderTransformer)
				.argument(BucketsFieldHeaderDescriptor.REQUESTED_COUNT.to(argumentBuilderTransformer)))
			.build();
	}

	@Nonnull
	private GraphQLObjectType buildFacetRequestImpactObject() {
		// register custom data fetcher because of the request impact being java record
		buildingContext.registerDataFetcher(
			FacetRequestImpactDescriptor.THIS,
			FacetRequestImpactDescriptor.DIFFERENCE,
			PropertyDataFetcher.fetching(RequestImpact::difference)
		);
		buildingContext.registerDataFetcher(
			FacetRequestImpactDescriptor.THIS,
			FacetRequestImpactDescriptor.MATCH_COUNT,
			PropertyDataFetcher.fetching(RequestImpact::matchCount)
		);
		buildingContext.registerDataFetcher(
			FacetRequestImpactDescriptor.THIS,
			FacetRequestImpactDescriptor.HAS_SENSE,
			PropertyDataFetcher.fetching(RequestImpact::hasSense)
		);

		return FacetRequestImpactDescriptor.THIS
			.to(objectBuilderTransformer)
			.build();
	}
}
