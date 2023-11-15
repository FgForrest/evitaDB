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

import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.require.*;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaProvider;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.api.ExternalApiNamingConventions;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.HierarchyDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ReferenceDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GraphQLEntityDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.AssociatedDataFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.AttributesFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.ParentsFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.PriceBigDecimalFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.ReferenceFieldHeaderDescriptor;
import io.evitadb.test.client.query.graphql.GraphQLOutputFieldsBuilder.Argument;
import io.evitadb.test.client.query.graphql.GraphQLOutputFieldsBuilder.ArgumentSupplier;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
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

	public EntityFetchConverter(@Nonnull CatalogSchemaContract catalogSchema,
	                            @Nonnull GraphQLInputJsonPrinter inputJsonPrinter) {
		super(catalogSchema, inputJsonPrinter);
	}

	public void convert(@Nonnull GraphQLOutputFieldsBuilder fieldsBuilder,
	                    @Nullable String entityType,
						@Nullable Locale locale,
	                    @Nullable EntityFetchRequire entityFetch) {
		final Optional<EntitySchemaContract> entitySchema = Optional.ofNullable(entityType).flatMap(catalogSchema::getEntitySchema);

		fieldsBuilder.addPrimitiveField(EntityDescriptor.PRIMARY_KEY);

		if (entitySchema.isEmpty() || entityFetch == null || entityFetch.getRequirements().length == 0) {
			return;
		}

		final Set<Locale> requiredLocales = Optional.ofNullable(QueryUtils.findConstraint(entityFetch, DataInLocales.class))
			.map(it -> {
				if (it.isAllRequested()) {
					return entitySchema.get().getLocales();
				}
				return Set.of(it.getLocales());
			})
			.orElse(null);

		convertHierarchyContent(
			fieldsBuilder,
			QueryUtils.findConstraint(entityFetch, HierarchyContent.class, SeparateEntityContentRequireContainer.class),
			locale,
			entityFetch,
			entitySchema.get()
		);
		convertAttributeContent(
			fieldsBuilder,
			QueryUtils.findConstraint(entityFetch, AttributeContent.class, SeparateEntityContentRequireContainer.class),
			locale,
			requiredLocales,
			entitySchema.get()
		);
		convertAssociatedDataContent(
			fieldsBuilder,
			QueryUtils.findConstraint(entityFetch, AssociatedDataContent.class, SeparateEntityContentRequireContainer.class),
			locale,
			requiredLocales,
			entitySchema.get()
		);
		convertPriceContent(
			fieldsBuilder,
			QueryUtils.findConstraint(entityFetch, PriceContent.class, SeparateEntityContentRequireContainer.class),
			locale
		);
		convertReferenceContents(
			fieldsBuilder,
			QueryUtils.findConstraints(entityFetch, ReferenceContent.class, SeparateEntityContentRequireContainer.class),
			entityType,
			locale,
			requiredLocales,
			entitySchema.get()
		);
	}

	private void convertHierarchyContent(@Nonnull GraphQLOutputFieldsBuilder entityFieldsBuilder,
										 @Nullable HierarchyContent hierarchyContent,
	                                     @Nullable Locale locale,
	                                     @Nonnull EntityFetchRequire entityFetch,
	                                     @Nonnull EntitySchemaContract entitySchema) {
		if (hierarchyContent != null) {
			entityFieldsBuilder.addPrimitiveField(GraphQLEntityDescriptor.PARENT_PRIMARY_KEY);
			entityFieldsBuilder.addObjectField(
				GraphQLEntityDescriptor.PARENTS,
				parentsFieldsBuilder -> convert(
					parentsFieldsBuilder,
					entitySchema.getName(),
					locale,
					hierarchyContent.getEntityFetch().orElse(null)
				),
				getHierarchyContentArguments(hierarchyContent, entitySchema)
			);
		}
	}

	@Nonnull
	private ArgumentSupplier[] getHierarchyContentArguments(@Nonnull HierarchyContent hierarchyContent,
	                                                        @Nonnull EntitySchemaContract entitySchema) {
		final Optional<HierarchyStopAt> stopAt = hierarchyContent.getStopAt();
		if (stopAt.isEmpty()) {
			return new ArgumentSupplier[0];
		}

		return new ArgumentSupplier[] {
			offset -> new Argument(
				ParentsFieldHeaderDescriptor.STOP_AT,
				offset,
				convertRequireConstraint(new HierarchyDataLocator(entitySchema.getName()), stopAt.get())
					.orElseThrow()
			)
		};
	}

	private void convertAttributeContent(@Nonnull GraphQLOutputFieldsBuilder fieldsBuilder,
										 @Nullable AttributeContent attributeContent,
	                                     @Nullable Locale filterLocale,
										 @Nullable Set<Locale> requiredLocales,
	                                     @Nonnull AttributeSchemaProvider<? extends AttributeSchemaContract> attributeSchemaProvider) {
		if (attributeContent != null) {
			final List<? extends AttributeSchemaContract> attributesToFetch;

			if (!attributeContent.isAllRequested()) {
				attributesToFetch = Arrays.stream(attributeContent.getAttributeNames())
					.map(it -> attributeSchemaProvider.getAttribute(it).orElse(null))
					.toList();
			} else {
				attributesToFetch = attributeSchemaProvider.getAttributes()
					.values()
					.stream()
					.filter(it -> {
						if (!it.isLocalized()) {
							return true;
						}
						return filterLocale != null || requiredLocales != null;
					})
					.toList();
				if (attributesToFetch.isEmpty()) {
					throw new EvitaInternalError("There are no attributes to fetch for schema `" + ((NamedSchemaContract)attributeSchemaProvider).getName() + "`. This is strange, this can produce invalid query!");
				}
			}

			if (requiredLocales == null) {
				// there will be max one locale from filter
				fieldsBuilder.addObjectField(
					EntityDescriptor.ATTRIBUTES,
					getAttributesFieldsBuilder(attributesToFetch)
				);
			} else if (requiredLocales.size() == 1) {
				fieldsBuilder.addObjectField(
					EntityDescriptor.ATTRIBUTES,
					getAttributesFieldsBuilder(attributesToFetch),
					offset -> new Argument(AttributesFieldHeaderDescriptor.LOCALE, offset, requiredLocales.iterator().next())
				);
			} else {
				final List<? extends AttributeSchemaContract> globalAttributes = attributesToFetch.stream().filter(it -> !it.isLocalized()).toList();
				fieldsBuilder.addObjectField(
					EntityDescriptor.ATTRIBUTES.name() + "Global",
					EntityDescriptor.ATTRIBUTES,
					getAttributesFieldsBuilder(globalAttributes)
				);

				final List<? extends AttributeSchemaContract> localizedAttributes = attributesToFetch.stream().filter(AttributeSchemaContract::isLocalized).toList();
				for (Locale locale : requiredLocales) {
					fieldsBuilder.addObjectField(
						EntityDescriptor.ATTRIBUTES.name() + StringUtils.toPascalCase(locale.toString()),
						EntityDescriptor.ATTRIBUTES,
						getAttributesFieldsBuilder(localizedAttributes),
						offset -> new Argument(AttributesFieldHeaderDescriptor.LOCALE, offset, locale)
					);
				}
			}
		}
	}

	@Nonnull
	private Consumer<GraphQLOutputFieldsBuilder> getAttributesFieldsBuilder(@Nonnull List<? extends AttributeSchemaContract> attributes) {
		return attributesBuilder -> {
			for (AttributeSchemaContract attribute : attributes) {
				attributesBuilder.addPrimitiveField(attribute.getNameVariant(ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION));
			}
		};
	}

	private void convertAssociatedDataContent(@Nonnull GraphQLOutputFieldsBuilder entityFieldsBuilder,
	                                          @Nullable AssociatedDataContent associatedDataContent,
	                                          @Nullable Locale filterLocale,
	                                          @Nullable Set<Locale> requiredLocales,
	                                          @Nonnull EntitySchemaContract entitySchema) {
		if (associatedDataContent != null) {
			final List<AssociatedDataSchemaContract> associatedDataToFetch;

			if (!associatedDataContent.isAllRequested()) {
				associatedDataToFetch = Arrays.stream(associatedDataContent.getAssociatedDataNames())
					.map(it -> entitySchema.getAssociatedData(it).orElse(null))
					.toList();
			} else {
				associatedDataToFetch = entitySchema.getAssociatedData()
					.values()
					.stream()
					.filter(it -> {
						if (!it.isLocalized()) {
							return true;
						}
						return filterLocale != null || requiredLocales != null;
					})
					.toList();
			}

			if (requiredLocales == null) {
				// there will be max one locale from filter
				entityFieldsBuilder.addObjectField(
					EntityDescriptor.ASSOCIATED_DATA,
					getAssociatedDataFieldsBuilder(associatedDataToFetch)
				);
			} else if (requiredLocales.size() == 1) {
				entityFieldsBuilder.addObjectField(
					EntityDescriptor.ASSOCIATED_DATA,
					getAssociatedDataFieldsBuilder(associatedDataToFetch),
					offset -> new Argument(AssociatedDataFieldHeaderDescriptor.LOCALE, offset, requiredLocales.iterator().next())
				);
			} else {
				final List<AssociatedDataSchemaContract> globalAssociatedData = associatedDataToFetch.stream().filter(it -> !it.isLocalized()).toList();
				entityFieldsBuilder.addObjectField(
					EntityDescriptor.ASSOCIATED_DATA.name() + "Global",
					EntityDescriptor.ASSOCIATED_DATA,
					getAssociatedDataFieldsBuilder(globalAssociatedData)
				);

				final List<AssociatedDataSchemaContract> localizedAssociatedData = associatedDataToFetch.stream().filter(AssociatedDataSchemaContract::isLocalized).toList();
				for (Locale locale : requiredLocales) {
					entityFieldsBuilder.addObjectField(
						EntityDescriptor.ASSOCIATED_DATA.name() + StringUtils.toPascalCase(locale.toString()),
						EntityDescriptor.ASSOCIATED_DATA,
						getAssociatedDataFieldsBuilder(localizedAssociatedData),
						offset -> new Argument(AssociatedDataFieldHeaderDescriptor.LOCALE, offset, locale)
					);
				}
			}
		}
	}

	@Nonnull
	private Consumer<GraphQLOutputFieldsBuilder> getAssociatedDataFieldsBuilder(@Nonnull List<AssociatedDataSchemaContract> associatedDataSchemas) {
		return attributesBuilder -> {
			for (AssociatedDataSchemaContract associatedDataSchema : associatedDataSchemas) {
				attributesBuilder.addPrimitiveField(associatedDataSchema.getNameVariant(ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION));
			}
		};
	}

	private void convertPriceContent(@Nonnull GraphQLOutputFieldsBuilder entityFieldsBuilder,
	                                 @Nullable PriceContent priceContent,
                                     @Nullable Locale locale) {
		if (priceContent != null) {
			final PriceContentMode fetchMode = priceContent.getFetchMode();
			if (fetchMode == PriceContentMode.NONE) {
				return;
			} else if (fetchMode == PriceContentMode.RESPECTING_FILTER) {
				entityFieldsBuilder.addObjectField(
					EntityDescriptor.PRICE_FOR_SALE,
					priceForSaleBuilder -> {
						priceForSaleBuilder.addPrimitiveField(PriceDescriptor.PRICE_WITHOUT_TAX, getPriceValueFieldArguments(locale));
						priceForSaleBuilder.addPrimitiveField(PriceDescriptor.PRICE_WITH_TAX, getPriceValueFieldArguments(locale));
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
						pricesBuilder.addPrimitiveField(PriceDescriptor.PRICE_WITHOUT_TAX, getPriceValueFieldArguments(locale));
						pricesBuilder.addPrimitiveField(PriceDescriptor.PRICE_WITH_TAX, getPriceValueFieldArguments(locale));
						pricesBuilder.addPrimitiveField(PriceDescriptor.TAX_RATE);
					}
				);
			} else {
				throw new EvitaInternalError("Unsupported price content mode `" + fetchMode + "`."); // should never happen
			}
		}
	}

	@Nonnull
	private ArgumentSupplier[] getPriceValueFieldArguments(@Nullable Locale locale) {
		if (locale == null) {
			return new ArgumentSupplier[0];
		}
		return new ArgumentSupplier[] {
			offset -> new Argument(PriceBigDecimalFieldHeaderDescriptor.FORMATTED, offset, true),
			offset -> new Argument(PriceBigDecimalFieldHeaderDescriptor.WITH_CURRENCY, offset, true)
		};
	}

	private void convertReferenceContents(@Nonnull GraphQLOutputFieldsBuilder entityFieldsBuilder,
	                                      @Nonnull List<ReferenceContent> referenceContents,
	                                      @Nonnull String entityType,
										  @Nullable Locale filterLocale,
										  @Nullable Set<Locale> requiredLocales,
	                                      @Nonnull EntitySchemaContract entitySchema) {
		referenceContents.forEach(referenceContent -> {
			for (String referenceName : referenceContent.getReferenceNames()) {
				convertReferenceContent(
					entityFieldsBuilder,
					entityType,
					filterLocale,
					requiredLocales,
					entitySchema,
					referenceContent,
					referenceName
				);
			}
		});
	}

	private void convertReferenceContent(@Nonnull GraphQLOutputFieldsBuilder entityFieldsBuilder,
	                                     @Nonnull String entityType,
										 @Nullable Locale filterLocale,
										 @Nullable Set<Locale> requiredLocales,
	                                     @Nonnull EntitySchemaContract entitySchema,
	                                     @Nonnull ReferenceContent referenceContent,
	                                     @Nonnull String referenceName) {
		// convert requirements into output fields
		entityFieldsBuilder.addObjectField(
			StringUtils.toCamelCase(referenceName),
			referenceBuilder -> {
				final ReferenceSchemaContract referenceSchema = entitySchema.getReference(referenceName).orElseThrow();

				referenceBuilder.addPrimitiveField(ReferenceDescriptor.REFERENCED_PRIMARY_KEY);

				referenceContent.getAttributeContent().ifPresent(attributeContent -> convertAttributeContent(
					referenceBuilder,
					attributeContent,
					filterLocale,
					requiredLocales,
					referenceSchema
				));

				referenceContent.getEntityRequirement().ifPresent(entityRequirement ->
					referenceBuilder.addObjectField(
						ReferenceDescriptor.REFERENCED_ENTITY,
						referencedEntityBuilder -> convert(
							referencedEntityBuilder,
							referenceSchema.getReferencedEntityType(),
							filterLocale,
							entityRequirement
						)
					));

				referenceContent.getGroupEntityRequirement().ifPresent(groupEntityRequirement ->
					referenceBuilder.addObjectField(
						ReferenceDescriptor.GROUP_ENTITY,
						referencedGroupEntityBuilder -> convert(
							referencedGroupEntityBuilder,
							referenceSchema.getReferencedGroupType(),
							filterLocale,
							groupEntityRequirement
						)
					));
			},
			getReferenceContentArguments(entityType, referenceContent, referenceName)
		);
	}

	@Nonnull
	private ArgumentSupplier[] getReferenceContentArguments(@Nonnull String entityType,
	                                                                          @Nonnull ReferenceContent referenceContent,
	                                                                          @Nonnull String referenceName) {
		if (referenceContent.getFilterBy().isEmpty() && referenceContent.getOrderBy().isEmpty()) {
			return new ArgumentSupplier[0];
		}

		final ReferenceDataLocator referenceDataLocator = new ReferenceDataLocator(entityType, referenceName);
		final List<ArgumentSupplier> arguments = new ArrayList<>(2);

		if (referenceContent.getFilterBy().isPresent()) {
			arguments.add(
				offset -> new Argument(
					ReferenceFieldHeaderDescriptor.FILTER_BY,
					offset,
					convertFilterConstraint(referenceDataLocator, referenceContent.getFilterBy().get())
						.orElseThrow()
				)
			);
		}

		if (referenceContent.getOrderBy().isPresent()) {
			arguments.add(
				offset -> new Argument(
					ReferenceFieldHeaderDescriptor.ORDER_BY,
					offset,
					convertOrderConstraint(referenceDataLocator, referenceContent.getOrderBy().get())
						.orElseThrow()
				)
			);
		}

		return arguments.toArray(ArgumentSupplier[]::new);
	}
}
