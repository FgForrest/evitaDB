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

package io.evitadb.test.client.query.graphql;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.PriceContent;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.query.require.SeparateEntityContentRequireContainer;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.api.ExternalApiNamingConventions;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ReferenceDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GraphQLEntityDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.PriceBigDecimalFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.ReferenceFieldHeaderDescriptor;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
		PriceContent.class,
		ReferenceContent.class
	);

	public EntityFetchConverter(@Nonnull CatalogSchemaContract catalogSchema,
	                            @Nonnull GraphQLInputJsonPrinter inputJsonPrinter) {
		super(catalogSchema, inputJsonPrinter);
	}

	public void convert(@Nonnull GraphQLOutputFieldsBuilder fieldsBuilder,
	                    @Nullable String entityType,
						@Nullable Locale locale,
	                    @Nullable EntityFetchRequire entityFetch) {
		final Optional<EntitySchemaContract> entitySchema = catalogSchema.getEntitySchema(entityType);

		fieldsBuilder.addPrimitiveField(EntityDescriptor.PRIMARY_KEY);
		if (entitySchema.map(EntitySchemaContract::isWithHierarchy).orElse(false)) {
			fieldsBuilder.addPrimitiveField(GraphQLEntityDescriptor.PARENT_PRIMARY_KEY);
		}

		if (entitySchema.isEmpty() || entityFetch == null || entityFetch.getRequirements().length == 0) {
			return;
		}

		for (EntityContentRequire requirement : entityFetch.getRequirements()) {
			if (!SUPPORTED_CHILDREN.contains(requirement.getClass())) {
				throw new EvitaInternalError("Constraint `" + requirement.getClass().getName() + "` is currently not supported as child of `entityFetch` or `entityGroupFetch`. Someone needs to implement it ¯\\_(ツ)_/¯.");
			}
		}

		convertAttributeContent(fieldsBuilder, locale, entityFetch, entitySchema.get());
		convertPriceContent(fieldsBuilder, locale, entityFetch);
		convertReferenceContents(fieldsBuilder, entityType, locale, entityFetch, entitySchema.get());
	}

	private static void convertAttributeContent(@Nonnull GraphQLOutputFieldsBuilder entityFieldsBuilder,
												@Nullable Locale locale,
	                                            @Nonnull EntityFetchRequire entityFetch,
	                                            @Nonnull EntitySchemaContract entitySchema) {
		final AttributeContent attributeContent = QueryUtils.findConstraint(entityFetch, AttributeContent.class, SeparateEntityContentRequireContainer.class);
		if (attributeContent != null) {
			final String[] attributeNames;
			if (attributeContent.getAttributeNames().length > 0) {
				attributeNames = attributeContent.getAttributeNames();
			} else {
				attributeNames = entitySchema.getAttributes()
					.values()
					.stream()
					.filter(it -> {
						if (!it.isLocalized()) {
							return true;
						}
						return locale != null;
					})
					.map(it -> it.getNameVariant(ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION))
					.toArray(String[]::new);
			}

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

	private static void convertPriceContent(@Nonnull GraphQLOutputFieldsBuilder entityFieldsBuilder,
	                                        @Nullable Locale locale,
	                                        @Nonnull EntityFetchRequire entityFetch) {
		final PriceContent priceContent = QueryUtils.findConstraint(entityFetch, PriceContent.class, SeparateEntityContentRequireContainer.class);
		if (priceContent != null) {
			final PriceContentMode fetchMode = priceContent.getFetchMode();
			if (fetchMode == PriceContentMode.NONE) {
				return;
			} else if (fetchMode == PriceContentMode.RESPECTING_FILTER) {
				entityFieldsBuilder.addObjectField(
					EntityDescriptor.PRICE_FOR_SALE,
					priceForSaleBuilder -> {
						priceForSaleBuilder.addPrimitiveField(
							PriceDescriptor.PRICE_WITHOUT_TAX,
							getPriceValueFieldArgumentsBuilder(locale)
						);
						priceForSaleBuilder.addPrimitiveField(
							PriceDescriptor.PRICE_WITH_TAX,
							getPriceValueFieldArgumentsBuilder(locale)
						);
						priceForSaleBuilder.addPrimitiveField(PriceDescriptor.TAX_RATE);
					}
				);
			} else if (fetchMode == PriceContentMode.ALL) {
				entityFieldsBuilder.addObjectField(
					EntityDescriptor.PRICES,
					pricesBuilder -> {
						pricesBuilder.addPrimitiveField(PriceDescriptor.PRICE_ID);
						pricesBuilder.addPrimitiveField(PriceDescriptor.PRICE_LIST);
						pricesBuilder.addPrimitiveField(PriceDescriptor.CURRENCY);
						pricesBuilder.addPrimitiveField(PriceDescriptor.INNER_RECORD_ID);
						pricesBuilder.addPrimitiveField(PriceDescriptor.SELLABLE);
						pricesBuilder.addPrimitiveField(PriceDescriptor.VALIDITY);
						pricesBuilder.addPrimitiveField(
							PriceDescriptor.PRICE_WITHOUT_TAX,
							getPriceValueFieldArgumentsBuilder(locale)
						);
						pricesBuilder.addPrimitiveField(
							PriceDescriptor.PRICE_WITH_TAX,
							getPriceValueFieldArgumentsBuilder(locale)
						);
						pricesBuilder.addPrimitiveField(PriceDescriptor.TAX_RATE);
					}
				);
			} else {
				throw new EvitaInternalError("Unsupported price content mode `" + fetchMode + "`."); // should never happen
			}
		}
	}

	@Nullable
	private static Consumer<GraphQLOutputFieldsBuilder> getPriceValueFieldArgumentsBuilder(@Nullable Locale locale) {
		if (locale == null) {
			return null;
		}
		return priceWithoutTaxArgumentsBuilder -> {
			priceWithoutTaxArgumentsBuilder.addFieldArgument(PriceBigDecimalFieldHeaderDescriptor.FORMATTED, __ -> Boolean.toString(true));
			priceWithoutTaxArgumentsBuilder.addFieldArgument(PriceBigDecimalFieldHeaderDescriptor.WITH_CURRENCY, __ -> Boolean.toString(true));
		};
	}

	private void convertReferenceContents(@Nonnull GraphQLOutputFieldsBuilder entityFieldsBuilder,
	                                      @Nonnull String entityType,
										  @Nullable Locale locale,
	                                      @Nonnull EntityFetchRequire entityFetch,
	                                      @Nonnull EntitySchemaContract entitySchema) {
		final List<ReferenceContent> referenceContents = QueryUtils.findConstraints(entityFetch, ReferenceContent.class, SeparateEntityContentRequireContainer.class);
		referenceContents.forEach(referenceContent -> {
			for (String referenceName : referenceContent.getReferenceNames()) {
				convertReferenceContent(entityFieldsBuilder, entityType, locale, entitySchema, referenceContent, referenceName);
			}
		});
	}

	private void convertReferenceContent(@Nonnull GraphQLOutputFieldsBuilder entityFieldsBuilder,
	                                     @Nonnull String entityType,
										 @Nullable Locale locale,
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

				referenceContent.getAttributeContent().ifPresent(attributeContent ->
					referenceBuilder.addObjectField(
						ReferenceDescriptor.ATTRIBUTES,
						referenceAttributesBuilder -> {
							for (String attributeName : attributeContent.getAttributeNames()) {
								referenceAttributesBuilder.addPrimitiveField(StringUtils.toCamelCase(attributeName));
							}
						}
					));

				referenceContent.getEntityRequirement().ifPresent(entityRequirement ->
					referenceBuilder.addObjectField(
						ReferenceDescriptor.REFERENCED_ENTITY,
						referencedEntityBuilder -> convert(
							referencedEntityBuilder,
							referenceSchema.getReferencedEntityType(),
							locale,
							entityRequirement
						)
					));

				referenceContent.getGroupEntityRequirement().ifPresent(groupEntityRequirement ->
					referenceBuilder.addObjectField(
						ReferenceDescriptor.GROUP_ENTITY,
						referencedGroupEntityBuilder -> convert(
							referencedGroupEntityBuilder,
							referenceSchema.getReferencedGroupType(),
							locale,
							groupEntityRequirement
						)
					));
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
