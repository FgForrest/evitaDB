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

package io.evitadb.documentation.graphql;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.query.require.SeparateEntityContentRequireContainer;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ReferenceDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GraphQLEntityDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.ReferenceFieldHeaderDescriptor;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Converts {@link EntityFetch} or {@link EntityGroupFetch} require constraint from {@link io.evitadb.api.query.Query} into
 * GraphQL output fields for query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class EntityFetchConverter extends RequireConverter {

	private static final Set<Class<? extends Constraint<RequireConstraint>>> SUPPORTED_CHILDREN = Set.of(
		AttributeContent.class,
		ReferenceContent.class
	);

	public EntityFetchConverter(@Nonnull CatalogSchemaContract catalogSchema,
	                            @Nonnull GraphQLInputJsonPrinter inputJsonPrinter) {
		super(catalogSchema, inputJsonPrinter);
	}

	public void convert(@Nonnull CatalogSchemaContract catalogSchema,
	                    @Nonnull GraphQLOutputFieldsBuilder fieldsBuilder,
	                    @Nonnull String entityType,
	                    @Nullable EntityFetchRequire entityFetch) {
		final EntitySchemaContract entitySchema = catalogSchema.getEntitySchemaOrThrowException(entityType);

		fieldsBuilder.addPrimitiveField(EntityDescriptor.PRIMARY_KEY);
		if (entitySchema.isWithHierarchy()) {
			fieldsBuilder.addPrimitiveField(GraphQLEntityDescriptor.PARENT_PRIMARY_KEY);
		}

		if (entityFetch == null || entityFetch.getRequirements().length == 0) {
			return;
		}

		Assert.isPremiseValid(
			Arrays.stream(entityFetch.getRequirements())
				.allMatch(it -> SUPPORTED_CHILDREN.contains(it.getClass())),
			"Unsupported entityFetch child constraint used."
		);

		convertAttributeContent(fieldsBuilder, entityFetch);
		convertReferenceContents(catalogSchema, fieldsBuilder, entityType, entityFetch, entitySchema);
	}

	private static void convertAttributeContent(@Nonnull GraphQLOutputFieldsBuilder entityFieldsBuilder,
	                                            @Nonnull EntityFetchRequire entityFetch) {
		final AttributeContent attributeContent = QueryUtils.findConstraint(entityFetch, AttributeContent.class, SeparateEntityContentRequireContainer.class);
		if (attributeContent != null) {
			final String[] attributeNames = attributeContent.getAttributeNames();
			Assert.isPremiseValid(attributeNames.length > 0, "Fetching all attributes is not supported by GraphQL.");

			entityFieldsBuilder.addObjectField(
				EntityDescriptor.ATTRIBUTES,
				attributesBuilder -> {
					for (String attributeName : attributeNames) {
						attributesBuilder.addPrimitiveField(StringUtils.toCamelCase(attributeName));
					}
				}
			);
		}
	}

	private void convertReferenceContents(@Nonnull CatalogSchemaContract catalogSchema,
	                                      @Nonnull GraphQLOutputFieldsBuilder entityFieldsBuilder,
	                                      @Nonnull String entityType,
	                                      @Nonnull EntityFetchRequire entityFetch,
	                                      @Nonnull EntitySchemaContract entitySchema) {
		final List<ReferenceContent> referenceContents = QueryUtils.findConstraints(entityFetch, ReferenceContent.class, SeparateEntityContentRequireContainer.class);
		referenceContents.forEach(referenceContent -> {
			for (String referenceName : referenceContent.getReferenceNames()) {
				convertReferenceContent(catalogSchema, entityFieldsBuilder, entityType, entitySchema, referenceContent, referenceName);
			}
		});
	}

	private void convertReferenceContent(@Nonnull CatalogSchemaContract catalogSchema,
	                                     @Nonnull GraphQLOutputFieldsBuilder entityFieldsBuilder,
	                                     @Nonnull String entityType,
	                                     @Nonnull EntitySchemaContract entitySchema,
	                                     @Nonnull ReferenceContent referenceContent,
	                                     @Nonnull String referenceName) {
		// convert requirements into output fields
		entityFieldsBuilder.addObjectField(
			StringUtils.toCamelCase(referenceName),
			getReferenceContentArgumentsBuilder(entityType, referenceContent, referenceName),
			referenceBuilder -> {
				final ReferenceSchemaContract referenceSchema = entitySchema.getReference(referenceName).orElseThrow();

				referenceBuilder.addPrimitiveField(ReferenceDescriptor.REFERENCED_PRIMARY_KEY);

				if (referenceContent.getEntityRequirement().isPresent()) {
					referenceBuilder.addObjectField(
						ReferenceDescriptor.REFERENCED_ENTITY,
						referencedEntityBuilder -> convert(
							catalogSchema,
							referencedEntityBuilder,
							referenceSchema.getReferencedEntityType(),
							referenceContent.getEntityRequirement().get()
						)
					);
				}

				if (referenceContent.getGroupEntityRequirement().isPresent()) {
					referenceBuilder.addObjectField(
						ReferenceDescriptor.GROUP_ENTITY,
						referencedGroupEntityBuilder -> convert(
							catalogSchema,
							referencedGroupEntityBuilder,
							referenceSchema.getReferencedGroupType(),
							referenceContent.getGroupEntityRequirement().get()
						)
					);
				}

				final Collection<AttributeSchemaContract> referenceAttributes = referenceSchema.getAttributes().values();
				if (!referenceAttributes.isEmpty()) {
					referenceBuilder.addObjectField(
						ReferenceDescriptor.ATTRIBUTES,
						referenceAttributesBuilder -> {
							for (AttributeSchemaContract attribute : referenceAttributes) {
								referenceAttributesBuilder.addPrimitiveField(StringUtils.toCamelCase(attribute.getName()));
							}
						}
					);
				}
			}
		);
	}

	@Nullable
	private Consumer<GraphQLOutputFieldsBuilder> getReferenceContentArgumentsBuilder(@Nonnull String entityType,
	                                                                                 @Nonnull ReferenceContent referenceContent,
	                                                                                 @Nonnull String referenceName) {
		if (referenceContent.getFilterBy().isEmpty() && referenceContent.getOrderBy().isEmpty()) {
			return null;
		}

		return referenceFieldArgumentsBuilder -> {
			final ReferenceDataLocator referenceDataLocator = new ReferenceDataLocator(entityType, referenceName);

			if (referenceContent.getFilterBy().isPresent()) {
				referenceFieldArgumentsBuilder.addFieldArgument(
					ReferenceFieldHeaderDescriptor.FILTER_BY,
					offset -> convertFilterConstraint(referenceDataLocator, referenceContent.getFilterBy().get(), offset)
						.orElseThrow()
				);
			}

			if (referenceContent.getOrderBy().isPresent()) {
				referenceFieldArgumentsBuilder.addFieldArgument(
					ReferenceFieldHeaderDescriptor.ORDER_BY,
					offset -> convertOrderConstraint(referenceDataLocator, referenceContent.getOrderBy().get(), offset)
						.orElseThrow()
				);
			}
		};
	}
}
