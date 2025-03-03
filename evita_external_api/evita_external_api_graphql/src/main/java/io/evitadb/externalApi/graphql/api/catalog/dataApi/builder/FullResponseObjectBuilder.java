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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.builder;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.PropertyDataFetcher;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderGroupBy;
import io.evitadb.api.query.require.HierarchyNode;
import io.evitadb.api.query.require.HierarchyStopAt;
import io.evitadb.api.query.require.Spacing;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.RequestImpact;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ExternalEntityTypePointer;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.HierarchyDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.SegmentDataLocator;
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
import io.evitadb.externalApi.graphql.api.builder.BuiltFieldDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.FilterConstraintSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.GraphQLConstraintSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.OrderConstraintSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.RequireConstraintSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.BucketsFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.RecordPageFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.RecordStripFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.*;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.HierarchyParentsHeaderDescriptor.HierarchyParentsSiblingsSpecification;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.RecordPageDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.RecordStripDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult.*;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLInputObjectTransformer;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLObjectTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLArgumentTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLFieldTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLInputFieldTransformer;
import io.evitadb.externalApi.graphql.exception.GraphQLSchemaBuildingError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLList.list;
import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLTypeReference.typeRef;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.graphql.api.dataType.GraphQLScalars.OBJECT;
import static io.evitadb.externalApi.graphql.api.dataType.GraphQLScalars.STRING;

/**
 * Builds schema object representing {@link io.evitadb.api.requestResponse.EvitaResponse} with entities and extra results.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class FullResponseObjectBuilder {

	private static final PropertyDataFetcher<Integer> REQUEST_IMPACT_DIFFERENCE_DATA_FETCHER = PropertyDataFetcher.fetching(RequestImpact::difference);
	private static final PropertyDataFetcher<Integer> REQUEST_IMPACT_MATCH_COUNT_DATA_FETCHER = PropertyDataFetcher.fetching(RequestImpact::matchCount);
	private static final PropertyDataFetcher<Boolean> REQUEST_IMPACT_HAS_SENSE_DATA_FETCHER = PropertyDataFetcher.fetching(RequestImpact::hasSense);

	@Nonnull private final CatalogGraphQLSchemaBuildingContext buildingContext;
	@Nonnull private final PropertyDescriptorToGraphQLArgumentTransformer argumentBuilderTransformer;
	@Nonnull private final ObjectDescriptorToGraphQLObjectTransformer objectBuilderTransformer;
	@Nonnull private final ObjectDescriptorToGraphQLInputObjectTransformer inputObjectBuilderTransformer;
	@Nonnull private final PropertyDescriptorToGraphQLFieldTransformer fieldBuilderTransformer;
	@Nonnull private final PropertyDescriptorToGraphQLInputFieldTransformer inputFieldBuilderTransformer;
	@Nonnull private final FilterConstraintSchemaBuilder filterConstraintSchemaBuilder;
	@Nonnull private final OrderConstraintSchemaBuilder orderConstraintSchemaBuilder;
	@Nonnull private final RequireConstraintSchemaBuilder complementaryRequireConstraintSchemaBuilder;

	public FullResponseObjectBuilder(@Nonnull CatalogGraphQLSchemaBuildingContext buildingContext,
	                                 @Nonnull PropertyDescriptorToGraphQLArgumentTransformer argumentBuilderTransformer,
	                                 @Nonnull ObjectDescriptorToGraphQLObjectTransformer objectBuilderTransformer,
									 @Nonnull ObjectDescriptorToGraphQLInputObjectTransformer inputObjectBuilderTransformer,
	                                 @Nonnull PropertyDescriptorToGraphQLFieldTransformer fieldBuilderTransformer,
									 @Nonnull PropertyDescriptorToGraphQLInputFieldTransformer inputFieldBuilderTransformer,
	                                 @Nonnull GraphQLConstraintSchemaBuildingContext constraintSchemaBuildingContext,
	                                 @Nonnull FilterConstraintSchemaBuilder filterConstraintSchemaBuilder,
	                                 @Nonnull OrderConstraintSchemaBuilder orderConstraintSchemaBuilder) {
		this.buildingContext = buildingContext;
		this.argumentBuilderTransformer = argumentBuilderTransformer;
		this.objectBuilderTransformer = objectBuilderTransformer;
		this.inputObjectBuilderTransformer = inputObjectBuilderTransformer;
		this.fieldBuilderTransformer = fieldBuilderTransformer;
		this.inputFieldBuilderTransformer = inputFieldBuilderTransformer;
		this.filterConstraintSchemaBuilder = filterConstraintSchemaBuilder;
		this.orderConstraintSchemaBuilder = orderConstraintSchemaBuilder;
		this.complementaryRequireConstraintSchemaBuilder = RequireConstraintSchemaBuilder.forComplementaryRequire(
			constraintSchemaBuildingContext,
			new AtomicReference<>(filterConstraintSchemaBuilder)
		);
	}

	public void buildCommonTypes() {
		buildingContext.registerType(BucketDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(buildHistogramObject());
		// TOBEDONE LHO: remove after https://github.com/FgForrest/evitaDB/issues/8 is implemented
		buildingContext.registerType(buildAttributeNamedHistogramObject());
		buildingContext.registerType(buildFacetRequestImpactObject());
	}

	@Nonnull
	public GraphQLObjectType build(@Nonnull EntitySchemaContract entitySchema) {
		final String objectName = ResponseDescriptor.THIS.name(entitySchema);

		final GraphQLObjectType.Builder responseObjectBuilder = ResponseDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		final List<BuiltFieldDescriptor> responseFields = new LinkedList<>();

		responseFields.add(buildRecordPageField(entitySchema));
		responseFields.add(buildRecordStripField(entitySchema));
		buildExtraResultsField(entitySchema).ifPresent(responseFields::add);

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
	private BuiltFieldDescriptor buildRecordPageField(@Nonnull EntitySchemaContract entitySchema) {
		final GraphQLObjectType recordPageObject = buildRecordPageObject(entitySchema);

		final GraphQLInputType spacingConstraint = complementaryRequireConstraintSchemaBuilder.build(
			new SegmentDataLocator(new ManagedEntityTypePointer(entitySchema.getName())),
			Spacing.class
		);

		final GraphQLFieldDefinition recordPageField = ResponseDescriptor.RECORD_PAGE
			.to(fieldBuilderTransformer)
			.type(recordPageObject)
			.argument(RecordPageFieldHeaderDescriptor.NUMBER.to(argumentBuilderTransformer))
			.argument(RecordPageFieldHeaderDescriptor.SIZE.to(argumentBuilderTransformer))
			.argument(RecordPageFieldHeaderDescriptor.SPACING
				.to(argumentBuilderTransformer)
				.type(spacingConstraint))
			.build();

		return new BuiltFieldDescriptor(
			recordPageField,
			RecordPageDataFetcher.getInstance()
		);
	}

	@Nonnull
	private GraphQLObjectType buildRecordPageObject(@Nonnull EntitySchemaContract entitySchema) {
		final String objectName = RecordPageDescriptor.THIS.name(entitySchema);

		return RecordPageDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName)
			.field(RecordPageDescriptor.DATA
				.to(fieldBuilderTransformer)
				.type(nonNull(list(nonNull(typeRef(EntityDescriptor.THIS.name(entitySchema)))))))
			.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildRecordStripField(@Nonnull EntitySchemaContract entitySchema) {
		final GraphQLObjectType recordStripObject = buildRecordStripObject(entitySchema);

		final GraphQLFieldDefinition recordStripField = ResponseDescriptor.RECORD_STRIP
			.to(fieldBuilderTransformer)
			.type(recordStripObject)
			.argument(RecordStripFieldHeaderDescriptor.OFFSET.to(argumentBuilderTransformer))
			.argument(RecordStripFieldHeaderDescriptor.LIMIT.to(argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			recordStripField,
			RecordStripDataFetcher.getInstance()
		);
	}

	@Nonnull
	private GraphQLObjectType buildRecordStripObject(@Nonnull EntitySchemaContract entitySchema) {
		final String objectName = RecordStripDescriptor.THIS.name(entitySchema);

		return RecordStripDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName)
			.field(DataChunkDescriptor.DATA
				.to(fieldBuilderTransformer)
				.type(nonNull(list(nonNull(typeRef(EntityDescriptor.THIS.name(entitySchema)))))))
			.build();
	}

	@Nonnull
	private Optional<BuiltFieldDescriptor> buildExtraResultsField(@Nonnull EntitySchemaContract entitySchema) {
		final Optional<GraphQLObjectType> extraResultsObject = buildExtraResultsObject(entitySchema);
		if (extraResultsObject.isEmpty()) {
			return Optional.empty();
		}

		final GraphQLFieldDefinition extraResultsField = ResponseDescriptor.EXTRA_RESULTS
			.to(fieldBuilderTransformer)
			.type(nonNull(extraResultsObject.get()))
			.build();

		return Optional.of(new BuiltFieldDescriptor(
			extraResultsField,
			ExtraResultsDataFetcher.getInstance()
		));
	}

	@Nonnull
	private Optional<GraphQLObjectType> buildExtraResultsObject(@Nonnull EntitySchemaContract entitySchema) {
		final String objectName = ExtraResultsDescriptor.THIS.name(entitySchema);

		final GraphQLObjectType.Builder extraResultsObjectBuilder = ExtraResultsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		final List<BuiltFieldDescriptor> extraResultFields = new ArrayList<>(10);

		buildAttributeHistogramField(entitySchema).ifPresent(extraResultFields::add);
		// TOBEDONE LHO: remove after https://github.com/FgForrest/evitaDB/issues/8 is implemented
		buildAttributeHistogramsField(entitySchema).ifPresent(extraResultFields::add);
		buildPriceHistogramField(entitySchema).ifPresent(extraResultFields::add);
		buildFacetSummaryField(entitySchema).ifPresent(extraResultFields::add);
		buildHierarchyField(entitySchema).ifPresent(extraResultFields::add);
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
	private Optional<BuiltFieldDescriptor> buildAttributeHistogramField(@Nonnull EntitySchemaContract entitySchema) {
		final Optional<GraphQLObjectType> attributeHistogramObject = buildAttributeHistogramObject(entitySchema);
		if (attributeHistogramObject.isEmpty()) {
			return Optional.empty();
		}

		final GraphQLFieldDefinition attributeHistogramField = ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM
			.to(fieldBuilderTransformer)
			.type(attributeHistogramObject.get())
			.build();

		return Optional.of(new BuiltFieldDescriptor(
			attributeHistogramField,
			AttributeHistogramDataFetcher.getInstance()
		));
	}

	@Nonnull
	private Optional<GraphQLObjectType> buildAttributeHistogramObject(@Nonnull EntitySchemaContract entitySchema) {
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

		final String objectName = AttributeHistogramDescriptor.THIS.name(entitySchema);
		final GraphQLObjectType.Builder attributeHistogramsObjectBuilder = AttributeHistogramDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);
		attributeSchemas.forEach(attributeSchema -> {
			final BuiltFieldDescriptor fieldForSingleAttribute = buildAttributeHistogramForSingleAttributeField(attributeSchema);
			buildingContext.registerFieldToObject(
				objectName,
				attributeHistogramsObjectBuilder,
				fieldForSingleAttribute
			);
		});

		return Optional.of(attributeHistogramsObjectBuilder.build());
	}

	@Nonnull
	private BuiltFieldDescriptor buildAttributeHistogramForSingleAttributeField(@Nonnull AttributeSchemaContract attributeSchema) {
		final GraphQLFieldDefinition attributeFieldForSingleAttribute = newFieldDefinition()
			.name(attributeSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
			.type(typeRef(HistogramDescriptor.THIS.name()))
			.build();
		return new BuiltFieldDescriptor(
			attributeFieldForSingleAttribute,
			new AttributeHistogramForSingleAttributeDataFetcher(attributeSchema)
		);
	}

	// TOBEDONE LHO: remove after https://github.com/FgForrest/evitaDB/issues/8 is implemented
	@Nonnull
	private static Optional<BuiltFieldDescriptor> buildAttributeHistogramsField(@Nonnull EntitySchemaContract entitySchema) {
		final GraphQLFieldDefinition attributeHistogramField = newFieldDefinition()
			.name("attributeHistograms")
			.type(list(nonNull(typeRef("AttributeNamedHistogram"))))
			.argument(a -> a
				.name("attributes")
				.type(nonNull(list(nonNull(STRING)))))
			.build();

		return Optional.of(new BuiltFieldDescriptor(
			attributeHistogramField,
			new AttributeHistogramsDataFetcher(entitySchema)
		));
	}

	@Nonnull
	private Optional<BuiltFieldDescriptor> buildPriceHistogramField(@Nonnull EntitySchemaContract entitySchema) {
		if (entitySchema.getCurrencies().isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(new BuiltFieldDescriptor(
			ExtraResultsDescriptor.PRICE_HISTOGRAM.to(fieldBuilderTransformer).build(),
			PriceHistogramDataFetcher.getInstance()
		));
	}

	@Nonnull
	private Optional<BuiltFieldDescriptor> buildFacetSummaryField(@Nonnull EntitySchemaContract entitySchema) {
		final Optional<GraphQLObjectType> facetSummaryObject = buildFacetSummaryObject(entitySchema);
		if (facetSummaryObject.isEmpty()) {
			return Optional.empty();
		}

		final GraphQLFieldDefinition facetSummaryField = ExtraResultsDescriptor.FACET_SUMMARY
			.to(fieldBuilderTransformer)
			.type(facetSummaryObject.get())
			.build();

		return Optional.of(new BuiltFieldDescriptor(
			facetSummaryField,
			FacetSummaryDataFetcher.getInstance()
		));
	}

	@Nonnull
	private Optional<GraphQLObjectType> buildFacetSummaryObject(@Nonnull EntitySchemaContract entitySchema) {
		final List<ReferenceSchemaContract> referenceSchemas = entitySchema
			.getReferences()
			.values()
			.stream()
			.filter(ReferenceSchemaContract::isFacetedInAnyScope)
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
				entitySchema,
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
	private BuiltFieldDescriptor buildFacetGroupStatisticsField(@Nonnull EntitySchemaContract entitySchema,
	                                                            @Nonnull ReferenceSchemaContract referenceSchema) {
		final GraphQLObjectType facetGroupStatisticsObject = buildFacetGroupStatisticsObject(
			entitySchema,
			referenceSchema
		);

		final boolean isGrouped = referenceSchema.getReferencedGroupType() != null;

		final GraphQLFieldDefinition.Builder facetGroupStatisticsFieldBuilder = newFieldDefinition()
			.name(referenceSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION));
		if (isGrouped) {
			facetGroupStatisticsFieldBuilder.type(list(nonNull(facetGroupStatisticsObject)));
		} else {
			// if there is no group type, then the result will always be a single virtual group covering all facet statistics
			facetGroupStatisticsFieldBuilder.type(facetGroupStatisticsObject);
		}

		if (referenceSchema.getReferencedGroupType() != null) {
			final DataLocator groupEntityDataLocator = new EntityDataLocator(
				referenceSchema.isReferencedGroupTypeManaged()
					? new ManagedEntityTypePointer(referenceSchema.getReferencedGroupType())
					: new ExternalEntityTypePointer(referenceSchema.getReferencedGroupType())
			);
			final GraphQLInputType filterGroupByConstraint = filterConstraintSchemaBuilder.build(groupEntityDataLocator, FilterGroupBy.class);
			final GraphQLInputType orderGroupByConstraint = orderConstraintSchemaBuilder.build(groupEntityDataLocator, OrderGroupBy.class);

			facetGroupStatisticsFieldBuilder
				.argument(FacetGroupStatisticsHeaderDescriptor.FILTER_GROUP_BY
					.to(argumentBuilderTransformer)
					.type(filterGroupByConstraint))
				.argument(FacetGroupStatisticsHeaderDescriptor.ORDER_GROUP_BY
					.to(argumentBuilderTransformer)
					.type(orderGroupByConstraint));
		}

		return new BuiltFieldDescriptor(
			facetGroupStatisticsFieldBuilder.build(),
			isGrouped ? new FacetGroupStatisticsDataFetcher(referenceSchema) : new NonGroupedFacetGroupStatisticsDataFetcher(referenceSchema)
		);
	}

	@Nonnull
	private GraphQLObjectType buildFacetGroupStatisticsObject(@Nonnull EntitySchemaContract entitySchema,
	                                                          @Nonnull ReferenceSchemaContract referenceSchema) {
		final String objectName = FacetGroupStatisticsDescriptor.THIS.name(entitySchema, referenceSchema);

		final GraphQLObjectType.Builder facetGroupStatisticsBuilder = FacetGroupStatisticsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		if (referenceSchema.getReferencedGroupType() != null) {
			buildingContext.registerFieldToObject(
				objectName,
				facetGroupStatisticsBuilder,
				buildFacetGroupEntityField(referenceSchema)
			);
		}

		buildingContext.registerFieldToObject(
			objectName,
			facetGroupStatisticsBuilder,
			buildFacetStatisticsField(entitySchema, referenceSchema)
		);

		return facetGroupStatisticsBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildFacetGroupEntityField(@Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract groupEntitySchema = referenceSchema.isReferencedGroupTypeManaged() ?
			Optional.ofNullable(referenceSchema.getReferencedGroupType())
				.map(groupType -> buildingContext
					.getSchema()
					.getEntitySchemaOrThrowException(groupType))
				.orElse(null) :
			null;

		final GraphQLOutputType groupEntityObject = buildReferencedEntityObject(groupEntitySchema);

		final GraphQLFieldDefinition groupEntityField = FacetGroupStatisticsDescriptor.GROUP_ENTITY
			.to(fieldBuilderTransformer)
			.type(groupEntityObject)
			.build();

		return new BuiltFieldDescriptor(groupEntityField, null);
	}

	@Nonnull
	private BuiltFieldDescriptor buildFacetStatisticsField(@Nonnull EntitySchemaContract entitySchema,
	                                                       @Nonnull ReferenceSchemaContract referenceSchema) {
		final DataLocator facetEntityDataLocator = new EntityDataLocator(
			referenceSchema.isReferencedEntityTypeManaged()
				? new ManagedEntityTypePointer(referenceSchema.getReferencedEntityType())
				: new ExternalEntityTypePointer(referenceSchema.getReferencedEntityType())
		);
		final GraphQLInputType filterByConstraint = filterConstraintSchemaBuilder.build(facetEntityDataLocator, FilterBy.class);
		final GraphQLInputType orderByConstraint = orderConstraintSchemaBuilder.build(facetEntityDataLocator, OrderBy.class);
		final GraphQLObjectType facetStatisticsObject = buildFacetStatisticsObject(entitySchema, referenceSchema);

		final GraphQLFieldDefinition facetStatisticsField = FacetGroupStatisticsDescriptor.FACET_STATISTICS
			.to(fieldBuilderTransformer)
			.type(nonNull(list(nonNull(facetStatisticsObject))))
			.argument(FacetStatisticsHeaderDescriptor.FILTER_BY
				.to(argumentBuilderTransformer)
				.type(filterByConstraint))
			.argument(FacetStatisticsHeaderDescriptor.ORDER_BY
				.to(argumentBuilderTransformer)
				.type(orderByConstraint))
			.build();

		return new BuiltFieldDescriptor(facetStatisticsField, null);
	}

	@Nonnull
	private GraphQLObjectType buildFacetStatisticsObject(@Nonnull EntitySchemaContract entitySchema,
	                                                     @Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract facetEntitySchema = referenceSchema.isReferencedEntityTypeManaged() ?
			buildingContext
				.getSchema()
				.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType()) :
			null;
		final GraphQLOutputType facetEntityObject = buildReferencedEntityObject(facetEntitySchema);

		return FacetStatisticsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(FacetStatisticsDescriptor.THIS.name(entitySchema, referenceSchema))
			.field(FacetStatisticsDescriptor.FACET_ENTITY
				.to(fieldBuilderTransformer)
				.type(facetEntityObject))
			.build();
	}

	@Nonnull
	private Optional<BuiltFieldDescriptor> buildHierarchyField(@Nonnull EntitySchemaContract entitySchema) {
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
			return Optional.empty();
		}

		final GraphQLObjectType hierarchyObject = buildHierarchyObject(entitySchema, referenceSchemas);
		final GraphQLFieldDefinition hierarchyField = ExtraResultsDescriptor.HIERARCHY
			.to(fieldBuilderTransformer)
			.type(hierarchyObject)
			.build();
		return Optional.of(new BuiltFieldDescriptor(
			hierarchyField,
			new HierarchyDataFetcher(entitySchema.getReferences().values())
		));
	}

	@Nonnull
	private GraphQLObjectType buildHierarchyObject(@Nonnull EntitySchemaContract entitySchema,
	                                               @Nonnull List<ReferenceSchemaContract> referenceSchemas) {
		final String objectName = HierarchyDescriptor.THIS.name(entitySchema);
		final GraphQLObjectType.Builder hierarchyStatisticsObjectBuilder = HierarchyDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		if (entitySchema.isWithHierarchy()) {
			buildingContext.registerFieldToObject(
				objectName,
				hierarchyStatisticsObjectBuilder,
				buildHierarchyOfSelfField(entitySchema)
			);
		}
		referenceSchemas.forEach(referenceSchema ->
			buildingContext.registerFieldToObject(
				objectName,
				hierarchyStatisticsObjectBuilder,
				buildHierarchyOfReferenceField(entitySchema, referenceSchema)
			)
		);

		return hierarchyStatisticsObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildHierarchyOfSelfField(@Nonnull EntitySchemaContract entitySchema) {
		final GraphQLObjectType hierarchyOfSelfObject = buildHierarchyOfSelfObject(entitySchema);

		final GraphQLInputType orderByConstraint = orderConstraintSchemaBuilder.build(
			new EntityDataLocator(new ManagedEntityTypePointer(entitySchema.getName()))
		);

		final GraphQLFieldDefinition hierarchyOfSelfField = HierarchyDescriptor.SELF
			.to(fieldBuilderTransformer)
			.type(nonNull(hierarchyOfSelfObject))
			.argument(HierarchyOfSelfHeaderDescriptor.ORDER_BY
				.to(argumentBuilderTransformer)
				.type(orderByConstraint))
			.build();

		return new BuiltFieldDescriptor(hierarchyOfSelfField, null);
	}

	@Nonnull
	private GraphQLObjectType buildHierarchyOfSelfObject(@Nonnull EntitySchemaContract entitySchema) {
		final String objectName = HierarchyOfSelfDescriptor.THIS.name(entitySchema, entitySchema);

		final DataLocator selfHierarchyConstraintDataLocator = new HierarchyDataLocator(
			new ManagedEntityTypePointer(entitySchema.getName())
		);
		final GraphQLInputType nodeConstraint = complementaryRequireConstraintSchemaBuilder.build(selfHierarchyConstraintDataLocator, HierarchyNode.class);
		final GraphQLInputType stopAtConstraint = complementaryRequireConstraintSchemaBuilder.build(selfHierarchyConstraintDataLocator, HierarchyStopAt.class);
		final GraphQLInputObjectType parentsSiblingsSpecification = HierarchyParentsSiblingsSpecification.THIS
			.to(inputObjectBuilderTransformer)
			.name(HierarchyParentsSiblingsSpecification.THIS.name(entitySchema, entitySchema))
			.field(HierarchyParentsSiblingsSpecification.STOP_AT
				.to(inputFieldBuilderTransformer)
				.type(stopAtConstraint))
			.build();

		final GraphQLObjectType selfLevelInfoObject = buildSelfLevelInfoObject(entitySchema);

		final GraphQLObjectType.Builder hierarchyOfSelfObjectBuilder = HierarchyOfSelfDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		buildingContext.registerFieldToObject(
			objectName,
			hierarchyOfSelfObjectBuilder,
			buildHierarchyFromRootField(stopAtConstraint, selfLevelInfoObject)
		);
		buildingContext.registerFieldToObject(
			objectName,
			hierarchyOfSelfObjectBuilder,
			buildHierarchyFromNodeField(nodeConstraint, stopAtConstraint, selfLevelInfoObject)
		);
		buildingContext.registerFieldToObject(
			objectName,
			hierarchyOfSelfObjectBuilder,
			buildHierarchyChildrenField(stopAtConstraint, selfLevelInfoObject)
		);
		buildingContext.registerFieldToObject(
			objectName,
			hierarchyOfSelfObjectBuilder,
			buildHierarchyParentsField(stopAtConstraint, parentsSiblingsSpecification, selfLevelInfoObject)
		);
		buildingContext.registerFieldToObject(
			objectName,
			hierarchyOfSelfObjectBuilder,
			buildHierarchySiblingsField(stopAtConstraint, selfLevelInfoObject)
		);

		return hierarchyOfSelfObjectBuilder.build();
	}

	@Nonnull
	private GraphQLObjectType buildSelfLevelInfoObject(@Nonnull EntitySchemaContract entitySchema) {
		final String objectName = LevelInfoDescriptor.THIS.name(entitySchema, entitySchema);

		final GraphQLObjectType.Builder selfLevelInfoObjectBuilder = LevelInfoDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		buildingContext.registerFieldToObject(
			objectName,
			selfLevelInfoObjectBuilder,
			buildSelfLevelInfoEntityField(entitySchema)
		);

		return selfLevelInfoObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildSelfLevelInfoEntityField(@Nonnull EntitySchemaContract entitySchema) {
		final String referencedEntityObjectName = EntityDescriptor.THIS.name(entitySchema);

		final GraphQLFieldDefinition entityField = LevelInfoDescriptor.ENTITY
			.to(fieldBuilderTransformer)
			.type(nonNull(typeRef(referencedEntityObjectName)))
			.build();

		return new BuiltFieldDescriptor(
			entityField,
			null
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildHierarchyOfReferenceField(@Nonnull EntitySchemaContract entitySchema,
	                                                            @Nonnull ReferenceSchemaContract referenceSchema) {
		final GraphQLObjectType hierarchyOfReferenceObject = buildHierarchyOfReferenceObject(entitySchema, referenceSchema);

		final GraphQLFieldDefinition.Builder hierarchyOfReferenceFieldBuilder = HierarchyDescriptor.REFERENCE
			.to(fieldBuilderTransformer)
			.name(referenceSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
			.description(HierarchyDescriptor.REFERENCE.description(referenceSchema.getReferencedEntityType()))
			.type(nonNull(hierarchyOfReferenceObject))
			.argument(HierarchyOfReferenceHeaderDescriptor.EMPTY_HIERARCHICAL_ENTITY_BEHAVIOUR
				.to(argumentBuilderTransformer));

		final DataLocator hierarchyDataLocator = new EntityDataLocator(
			referenceSchema.isReferencedEntityTypeManaged()
				? new ManagedEntityTypePointer(referenceSchema.getReferencedEntityType())
				: new ExternalEntityTypePointer(referenceSchema.getReferencedEntityType())
		);
		final GraphQLInputType orderByConstraint = orderConstraintSchemaBuilder.build(hierarchyDataLocator);
		hierarchyOfReferenceFieldBuilder.argument(HierarchyOfReferenceHeaderDescriptor.ORDER_BY
			.to(argumentBuilderTransformer)
			.type(orderByConstraint));

		return new BuiltFieldDescriptor(hierarchyOfReferenceFieldBuilder.build(), null);
	}

	@Nonnull
	private GraphQLObjectType buildHierarchyOfReferenceObject(@Nonnull EntitySchemaContract entitySchema,
	                                                          @Nonnull ReferenceSchemaContract referenceSchema) {
		final String objectName = HierarchyOfReferenceDescriptor.THIS.name(entitySchema, referenceSchema);

		final DataLocator referenceHierarchyConstraintDataLocator = new HierarchyDataLocator(
			new ManagedEntityTypePointer(entitySchema.getName()),
			referenceSchema.getName()
		);
		final GraphQLInputType nodeConstraint = complementaryRequireConstraintSchemaBuilder.build(referenceHierarchyConstraintDataLocator, HierarchyNode.class);
		final GraphQLInputType stopAtConstraint = complementaryRequireConstraintSchemaBuilder.build(referenceHierarchyConstraintDataLocator, HierarchyStopAt.class);
		final GraphQLInputObjectType parentsSiblingsSpecification = HierarchyParentsSiblingsSpecification.THIS
			.to(inputObjectBuilderTransformer)
			.name(HierarchyParentsSiblingsSpecification.THIS.name(entitySchema, referenceSchema))
			.field(HierarchyParentsSiblingsSpecification.STOP_AT
				.to(inputFieldBuilderTransformer)
				.type(stopAtConstraint))
			.build();

		final GraphQLObjectType levelInfoObject = buildLevelInfoObject(entitySchema, referenceSchema);

		final GraphQLObjectType.Builder hierarchyOfReferenceObjectBuilder = HierarchyOfReferenceDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		buildingContext.registerFieldToObject(
			objectName,
			hierarchyOfReferenceObjectBuilder,
			buildHierarchyFromRootField(stopAtConstraint, levelInfoObject)
		);
		buildingContext.registerFieldToObject(
			objectName,
			hierarchyOfReferenceObjectBuilder,
			buildHierarchyFromNodeField(nodeConstraint, stopAtConstraint, levelInfoObject)
		);
		buildingContext.registerFieldToObject(
			objectName,
			hierarchyOfReferenceObjectBuilder,
			buildHierarchyChildrenField(stopAtConstraint, levelInfoObject)
		);
		buildingContext.registerFieldToObject(
			objectName,
			hierarchyOfReferenceObjectBuilder,
			buildHierarchyParentsField(stopAtConstraint, parentsSiblingsSpecification, levelInfoObject)
		);
		buildingContext.registerFieldToObject(
			objectName,
			hierarchyOfReferenceObjectBuilder,
			buildHierarchySiblingsField(stopAtConstraint, levelInfoObject)
		);

		return hierarchyOfReferenceObjectBuilder.build();
	}

	@Nonnull
	private GraphQLObjectType buildLevelInfoObject(@Nonnull EntitySchemaContract entitySchema,
	                                               @Nonnull ReferenceSchemaContract referenceSchema) {
		final String objectName = LevelInfoDescriptor.THIS.name(entitySchema, referenceSchema);

		final GraphQLObjectType.Builder levelInfoObjectBuilder = LevelInfoDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

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
		final String referencedEntityObjectName = EntityDescriptor.THIS.name(referencedEntitySchema);

		final GraphQLFieldDefinition entityField = LevelInfoDescriptor.ENTITY
			.to(fieldBuilderTransformer)
			.type(nonNull(typeRef(referencedEntityObjectName)))
			.build();

		return new BuiltFieldDescriptor(
			entityField,
			null
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildHierarchyFromRootField(@Nonnull GraphQLInputType stopAtConstraint,
	                                                         @Nonnull GraphQLObjectType levelInfoObject) {
		return new BuiltFieldDescriptor(
			HierarchyOfDescriptor.FROM_ROOT
				.to(fieldBuilderTransformer)
				.type(nonNull(list(nonNull(levelInfoObject))))
				.argument(HierarchyFromRootHeaderDescriptor.STOP_AT
					.to(argumentBuilderTransformer)
					.type(stopAtConstraint))
				.argument(HierarchyFromRootHeaderDescriptor.STATISTICS_BASE
					.to(argumentBuilderTransformer))
				.build(),
			SpecificHierarchyDataFetcher.getInstance()
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildHierarchyFromNodeField(@Nonnull GraphQLInputType nodeConstraint,
	                                                         @Nonnull GraphQLInputType stopAtConstraint,
	                                                         @Nonnull GraphQLObjectType levelInfoObject) {
		return new BuiltFieldDescriptor(
			HierarchyOfDescriptor.FROM_NODE
				.to(fieldBuilderTransformer)
				.type(nonNull(list(nonNull(levelInfoObject))))
				.argument(HierarchyFromNodeHeaderDescriptor.NODE
					.to(argumentBuilderTransformer)
					.type(nonNull(nodeConstraint)))
				.argument(HierarchyFromNodeHeaderDescriptor.STOP_AT
					.to(argumentBuilderTransformer)
					.type(stopAtConstraint))
				.argument(HierarchyFromNodeHeaderDescriptor.STATISTICS_BASE
					.to(argumentBuilderTransformer))
				.build(),
			SpecificHierarchyDataFetcher.getInstance()
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildHierarchyChildrenField(@Nonnull GraphQLInputType stopAtConstraint,
	                                                         @Nonnull GraphQLObjectType levelInfoObject) {
		return new BuiltFieldDescriptor(
			HierarchyOfDescriptor.CHILDREN
				.to(fieldBuilderTransformer)
				.type(nonNull(list(nonNull(levelInfoObject))))
				.argument(HierarchyChildrenHeaderDescriptor.STOP_AT
					.to(argumentBuilderTransformer)
					.type(stopAtConstraint))
				.argument(HierarchyChildrenHeaderDescriptor.STATISTICS_BASE
					.to(argumentBuilderTransformer))
				.build(),
			SpecificHierarchyDataFetcher.getInstance()
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildHierarchyParentsField(@Nonnull GraphQLInputType stopAtConstraint,
	                                                        @Nonnull GraphQLInputObjectType parentsSiblingsSpecification,
	                                                        @Nonnull GraphQLObjectType levelInfoObject) {
		return new BuiltFieldDescriptor(
			HierarchyOfDescriptor.PARENTS
				.to(fieldBuilderTransformer)
				.type(nonNull(list(nonNull(levelInfoObject))))
				.argument(HierarchyParentsHeaderDescriptor.STOP_AT
					.to(argumentBuilderTransformer)
					.type(stopAtConstraint))
				.argument(HierarchyParentsHeaderDescriptor.STATISTICS_BASE
					.to(argumentBuilderTransformer))
				.argument(HierarchyParentsHeaderDescriptor.SIBLINGS
					.to(argumentBuilderTransformer)
					.type(parentsSiblingsSpecification))
				.build(),
			SpecificHierarchyDataFetcher.getInstance()
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildHierarchySiblingsField(@Nonnull GraphQLInputType stopAtConstraint,
	                                                         @Nonnull GraphQLObjectType levelInfoObject) {
		return new BuiltFieldDescriptor(
			HierarchyOfDescriptor.SIBLINGS
				.to(fieldBuilderTransformer)
				.type(nonNull(list(nonNull(levelInfoObject))))
				.argument(HierarchySiblingsHeaderDescriptor.STOP_AT
					.to(argumentBuilderTransformer)
					.type(stopAtConstraint))
				.argument(HierarchySiblingsHeaderDescriptor.STATISTICS_BASE
					.to(argumentBuilderTransformer))
				.build(),
			SpecificHierarchyDataFetcher.getInstance()
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildQueryTelemetryField() {
		return new BuiltFieldDescriptor(
			ExtraResultsDescriptor.QUERY_TELEMETRY
				.to(fieldBuilderTransformer)
				.type(nonNull(OBJECT)) // workaround because GQL doesn't support infinite recursive structures
				.build(),
			QueryTelemetryDataFetcher.getInstance()
		);
	}

	@Nonnull
	private static GraphQLOutputType buildReferencedEntityObject(@Nullable EntitySchemaContract referencedEntitySchema) {
		if (referencedEntitySchema != null) {
			return typeRef(EntityDescriptor.THIS.name(referencedEntitySchema));
		} else {
			return typeRef(EntityDescriptor.THIS_REFERENCE.name());
		}
	}


	@Nonnull
	private GraphQLObjectType buildHistogramObject() {
		return HistogramDescriptor.THIS
			.to(objectBuilderTransformer)
			.field(HistogramDescriptor.BUCKETS
				.to(fieldBuilderTransformer)
				.argument(BucketsFieldHeaderDescriptor.REQUESTED_COUNT.to(argumentBuilderTransformer))
				.argument(BucketsFieldHeaderDescriptor.BEHAVIOR.to(argumentBuilderTransformer)))
			.build();
	}

	// TOBEDONE LHO: remove after https://github.com/FgForrest/evitaDB/issues/8 is implemented
	@Nonnull
	private GraphQLObjectType buildAttributeNamedHistogramObject() {
		return HistogramDescriptor.THIS
			.to(objectBuilderTransformer)
			.name("AttributeNamedHistogram")
			.field(f -> f.name("attributeName").type(nonNull(STRING)))
			.field(HistogramDescriptor.BUCKETS
				.to(fieldBuilderTransformer)
				.argument(BucketsFieldHeaderDescriptor.REQUESTED_COUNT.to(argumentBuilderTransformer))
				.argument(BucketsFieldHeaderDescriptor.BEHAVIOR.to(argumentBuilderTransformer)))
			.build();
	}

	@Nonnull
	private GraphQLObjectType buildFacetRequestImpactObject() {
		// register custom data fetcher because of the request impact being java record
		buildingContext.registerDataFetcher(
			FacetRequestImpactDescriptor.THIS,
			FacetRequestImpactDescriptor.DIFFERENCE,
			REQUEST_IMPACT_DIFFERENCE_DATA_FETCHER
		);
		buildingContext.registerDataFetcher(
			FacetRequestImpactDescriptor.THIS,
			FacetRequestImpactDescriptor.MATCH_COUNT,
			REQUEST_IMPACT_MATCH_COUNT_DATA_FETCHER
		);
		buildingContext.registerDataFetcher(
			FacetRequestImpactDescriptor.THIS,
			FacetRequestImpactDescriptor.HAS_SENSE,
			REQUEST_IMPACT_HAS_SENSE_DATA_FETCHER
		);

		return FacetRequestImpactDescriptor.THIS
			.to(objectBuilderTransformer)
			.build();
	}
}
