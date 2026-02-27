/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.reference;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLInterfaceType;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.InlineReferenceDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference.WithNamedReferenceDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.CollectionGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.EntityObjectBuilder.EntityObjectVariant;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.FilterConstraintSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.OrderConstraintSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.PaginatedListFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.StripListFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.ReferenceFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.ReferencesFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLInterfaceTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLArgumentTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLFieldTransformer;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

import static graphql.schema.GraphQLList.list;
import static graphql.schema.GraphQLNonNull.nonNull;

/**
 * Builds interface from {@link WithNamedReferenceDescriptor} for a specific reference definition.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class WithNamedReferenceInterfaceBuilder {

	@Nonnull private final CatalogGraphQLSchemaBuildingContext buildingContext;
	@Nonnull private final FilterConstraintSchemaBuilder filterConstraintSchemaBuilder;
	@Nonnull private final OrderConstraintSchemaBuilder orderConstraintSchemaBuilder;
	@Nonnull private final PropertyDescriptorToGraphQLArgumentTransformer argumentBuilderTransformer;
	@Nonnull private final ObjectDescriptorToGraphQLInterfaceTransformer interfaceBuilderTransformer;
	@Nonnull private final PropertyDescriptorToGraphQLFieldTransformer fieldBuilderTransformer;

	@Nonnull private final ReferenceDefinitionInterfaceBuilder referenceDefinitionInterfaceBuilder;
	@Nonnull private final ReferenceDefinitionPageInterfaceBuilder referenceDefinitionPageInterfaceBuilder;
	@Nonnull private final ReferenceDefinitionStripInterfaceBuilder referenceDefinitionStripInterfaceBuilder;

	/**
	 * It obtains from cache or creates a new interface for the passed reference schema. If another reference schema
	 * is similarly enough (instances of {@link WithNamedReferenceKey} for both schemas are equal), then the same interface
	 * is reused from cache.
	 * <p>
	 * This means that when the cache is empty, each such first reference schema acts as a
	 * schema representing the rest of the schemas matching the {@link WithNamedReferenceKey}. This is due to the following limitations:
	 * - constraint schema builder needs a specific instance of reference schema
	 * - evitaDB core treats each {@link ReferenceSchemaContract} as separate schema with no interlinked between each other
	 */
	@Nonnull
	public GraphQLInterfaceType getOrBuild(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull EntityObjectVariant fieldsVariant
	) {
		final WithNamedReferenceKey withNamedReferenceKey = new WithNamedReferenceKey(
			referenceSchema,
			fieldsVariant,
			collectionBuildingContext.getSchema().getName(),
			referenceSchema.getName(),
			fieldsVariant
		);
		final String withReferenceKeyHash = Long.toHexString(withNamedReferenceKey.toHash());

		return this.buildingContext.getOrComputeWithNamedReferenceInterface(
			withNamedReferenceKey,
			() -> {
				final String interfaceName = WithNamedReferenceDescriptor.THIS_INTERFACE.name(
					referenceSchema,
					withReferenceKeyHash
				);

				final GraphQLInterfaceType.Builder interfaceBuilder = WithNamedReferenceDescriptor.THIS_INTERFACE.to(this.interfaceBuilderTransformer)
					.name(interfaceName)
					.description(WithNamedReferenceDescriptor.THIS_INTERFACE.description(
						referenceSchema.getName(),
						referenceSchema.getReferencedEntityType(),
						withReferenceKeyHash
					));

				// inline query args for the reference definition
				final InlineReferenceDataLocator referenceDataLocator = new InlineReferenceDataLocator(
					new ManagedEntityTypePointer(collectionBuildingContext.getSchema().getName()),
					referenceSchema.getName()
				);
				final GraphQLInputType filterArgumentType = this.filterConstraintSchemaBuilder.build(referenceDataLocator);
				final GraphQLInputType orderArgumentType = this.orderConstraintSchemaBuilder.build(referenceDataLocator);

				// the target named reference interface for the reference definition
				final GraphQLInterfaceType referenceDefinitionInterface = this.referenceDefinitionInterfaceBuilder.getOrBuild(
					collectionBuildingContext,
					referenceSchema
				);

				interfaceBuilder.field(
					buildReferenceField(
						referenceSchema,
						filterArgumentType,
						orderArgumentType,
						referenceDefinitionInterface
					)
				);

				if (EntityObjectVariant.DEFAULT.equals(fieldsVariant) && isReferenceList(referenceSchema)) {
					interfaceBuilder.field(
						buildReferencePageField(
							collectionBuildingContext,
							referenceSchema,
							filterArgumentType,
							orderArgumentType,
							referenceDefinitionInterface
						)
					);

					interfaceBuilder.field(
						buildReferenceStripField(
							collectionBuildingContext,
							referenceSchema,
							filterArgumentType,
							orderArgumentType,
							referenceDefinitionInterface
						)
					);
				}

				return interfaceBuilder.build();
			});
	}

	@Nonnull
	private GraphQLFieldDefinition buildReferenceField(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull GraphQLInputType filterArgumentType,
		@Nonnull GraphQLInputType orderArgumentType,
		@Nonnull GraphQLInterfaceType referenceInterface
	) {
		final GraphQLFieldDefinition.Builder referenceFieldBuilder = WithNamedReferenceDescriptor.REFERENCE
			.to(this.fieldBuilderTransformer)
			.name(WithNamedReferenceDescriptor.REFERENCE.name(referenceSchema))
			.description(referenceSchema.getDescription())
			.deprecate(referenceSchema.getDeprecationNotice())
			.argument(
				ReferenceFieldHeaderDescriptor.FILTER_BY
					.to(this.argumentBuilderTransformer)
					.type(filterArgumentType)
			)
			.argument(
				ReferenceFieldHeaderDescriptor.ORDER_BY
					.to(this.argumentBuilderTransformer)
					.type(orderArgumentType)
			);

		if (isReferenceList(referenceSchema)) {
			referenceFieldBuilder.argument(
				ReferencesFieldHeaderDescriptor.LIMIT
					.to(this.argumentBuilderTransformer)
			);
		}

		final Cardinality referenceCardinality = referenceSchema.getCardinality();
		if (referenceCardinality.getMax() > 1) {
			referenceFieldBuilder.type(nonNull(list(nonNull(referenceInterface))));
		} else if (referenceCardinality.getMin() == 1) {
			referenceFieldBuilder.type(nonNull(referenceInterface));
		} else {
			referenceFieldBuilder.type(referenceInterface);
		}

		return referenceFieldBuilder.build();
	}

	@Nonnull
	private GraphQLFieldDefinition buildReferencePageField(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull GraphQLInputType filterArgumentType,
		@Nonnull GraphQLInputType orderArgumentType,
		@Nonnull GraphQLInterfaceType referenceInterface
	) {
		return WithNamedReferenceDescriptor.REFERENCE_PAGE
			.to(this.fieldBuilderTransformer)
			.name(WithNamedReferenceDescriptor.REFERENCE_PAGE.name(referenceSchema))
			.description(referenceSchema.getDescription())
			.deprecate(referenceSchema.getDeprecationNotice())
			.type(this.referenceDefinitionPageInterfaceBuilder.getOrBuild(collectionBuildingContext, referenceSchema))
			.argument(ReferenceFieldHeaderDescriptor.FILTER_BY
				.to(this.argumentBuilderTransformer)
				.type(filterArgumentType))
			.argument(ReferenceFieldHeaderDescriptor.ORDER_BY
				.to(this.argumentBuilderTransformer)
				.type(orderArgumentType))
			.argument(PaginatedListFieldHeaderDescriptor.NUMBER
				.to(this.argumentBuilderTransformer))
			.argument(PaginatedListFieldHeaderDescriptor.SIZE
				.to(this.argumentBuilderTransformer))
			.build();
	}

	@Nonnull
	private GraphQLFieldDefinition buildReferenceStripField(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull GraphQLInputType filterArgumentType,
		@Nonnull GraphQLInputType orderArgumentType,
		@Nonnull GraphQLInterfaceType referenceInterface
	) {
		return WithNamedReferenceDescriptor.REFERENCE_STRIP
			.to(this.fieldBuilderTransformer)
			.name(WithNamedReferenceDescriptor.REFERENCE_STRIP.name(referenceSchema))
			.description(referenceSchema.getDescription())
			.deprecate(referenceSchema.getDeprecationNotice())
			.type(this.referenceDefinitionStripInterfaceBuilder.getOrBuild(collectionBuildingContext, referenceSchema))
			.argument(ReferenceFieldHeaderDescriptor.FILTER_BY
				.to(this.argumentBuilderTransformer)
				.type(filterArgumentType))
			.argument(ReferenceFieldHeaderDescriptor.ORDER_BY
				.to(this.argumentBuilderTransformer)
				.type(orderArgumentType))
			.argument(StripListFieldHeaderDescriptor.OFFSET
				.to(this.argumentBuilderTransformer))
			.argument(StripListFieldHeaderDescriptor.LIMIT
				.to(this.argumentBuilderTransformer))
			.build();
	}

	private static boolean isReferenceList(@Nonnull ReferenceSchemaContract referenceSchema) {
		return referenceSchema.getCardinality().getMax() > 1;
	}
}
