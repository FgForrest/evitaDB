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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher;

import graphql.schema.SelectedField;
import io.evitadb.api.query.require.AssociatedDataContent;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.query.require.DataInLocales;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.PriceContent;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.EntityHeaderDescriptor.AssociatedDataFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.EntityHeaderDescriptor.AttributesFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.EntityHeaderDescriptor.PriceFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.EntityHeaderDescriptor.PriceForSaleFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.EntityHeaderDescriptor.PricesFieldHeaderDescriptor;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;
import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * Builds {@link EntityFetch} based on which entity fields client specified.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class EntityFetchRequireBuilder {

    @Nullable
    public static EntityFetch buildEntityRequirement(@Nonnull SelectionSetWrapper selectionSetWrapper,
                                                     @Nullable Locale desiredLocale,
                                                     @Nullable EntitySchemaContract currentEntitySchema,
                                                     @Nonnull Function<String, EntitySchemaContract> entitySchemaFetcher) {
        final List<EntityContentRequire> entityContentRequires = buildContentRequirements(
            selectionSetWrapper,
            desiredLocale,
            currentEntitySchema,
            entitySchemaFetcher
        );
        if (entityContentRequires == null) {
            return null;
        }
        return entityFetch(entityContentRequires.toArray(EntityContentRequire[]::new));
    }

    @Nullable
    public static EntityGroupFetch buildGroupEntityRequirement(@Nonnull SelectionSetWrapper selectionSetWrapper,
                                                               @Nullable Locale desiredLocale,
                                                               @Nullable EntitySchemaContract currentEntitySchema,
                                                               @Nonnull Function<String, EntitySchemaContract> entitySchemaFetcher) {
        final List<EntityContentRequire> entityContentRequires = buildContentRequirements(
            selectionSetWrapper,
            desiredLocale,
            currentEntitySchema,
            entitySchemaFetcher
        );
        if (entityContentRequires == null) {
            return null;
        }
        return entityGroupFetch(entityContentRequires.toArray(EntityContentRequire[]::new));
    }

    @Nullable
    private static List<EntityContentRequire> buildContentRequirements(@Nonnull SelectionSetWrapper selectionSetWrapper,
                                                                       @Nullable Locale desiredLocale,
                                                                       @Nullable EntitySchemaContract currentEntitySchema,
                                                                       @Nonnull Function<String, EntitySchemaContract> entitySchemaFetcher) {
        // no entity schema, no available data to fetch
        if (currentEntitySchema == null) {
            return null;
        }

        if (!needsEntityBody(selectionSetWrapper, currentEntitySchema)) {
            return null;
        }

        final List<EntityContentRequire> entityContentRequires = new LinkedList<>();
        buildAttributeContentRequirement(selectionSetWrapper, currentEntitySchema).ifPresent(entityContentRequires::add);
        buildAssociatedDataContentRequirement(selectionSetWrapper, currentEntitySchema).ifPresent(entityContentRequires::add);
        buildPriceContentRequirement(selectionSetWrapper).ifPresent(entityContentRequires::add);
        entityContentRequires.addAll(buildReferenceContentRequirement(selectionSetWrapper, desiredLocale, currentEntitySchema, entitySchemaFetcher));
        buildDataInLocalesRequirement(selectionSetWrapper, desiredLocale, currentEntitySchema).ifPresent(entityContentRequires::add);

        return entityContentRequires;
    }

    private static boolean needsEntityBody(@Nonnull SelectionSetWrapper selectionSetWrapper, @Nonnull EntitySchemaContract currentEntitySchema) {
        return selectionSetWrapper.contains(EntityDescriptor.HIERARCHICAL_PLACEMENT.name()) ||
            selectionSetWrapper.contains(EntityDescriptor.LOCALES.name()) ||
            needsAttributes(selectionSetWrapper) ||
            needsAssociatedData(selectionSetWrapper) ||
            needsPrices(selectionSetWrapper) ||
            needsReferences(selectionSetWrapper, currentEntitySchema);
    }

    private static boolean needsAttributes(@Nonnull SelectionSetWrapper selectionSetWrapper) {
        return selectionSetWrapper.contains(EntityDescriptor.ATTRIBUTES.name());
    }

    private static boolean needsAssociatedData(@Nonnull SelectionSetWrapper selectionSetWrapper) {
        return selectionSetWrapper.contains(EntityDescriptor.ASSOCIATED_DATA.name());
    }

    private static boolean needsPrices(@Nonnull SelectionSetWrapper selectionSetWrapper) {
        return selectionSetWrapper.contains(EntityDescriptor.PRICE.name() + "*");
    }

    private static boolean needsReferences(@Nonnull SelectionSetWrapper selectionSetWrapper, @Nonnull EntitySchemaContract currentEntitySchema) {
        return currentEntitySchema.getReferences()
            .values()
            .stream()
            .map(it -> it.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
            .anyMatch(selectionSetWrapper::contains);
    }

    @Nonnull
    private static Optional<AttributeContent> buildAttributeContentRequirement(@Nonnull SelectionSetWrapper selectionSetWrapper,
                                                                               @Nonnull EntitySchemaContract currentEntitySchema) {
        if (!needsAttributes(selectionSetWrapper)) {
            return Optional.empty();
        }

        final String[] neededAttributes = selectionSetWrapper.getFields(EntityDescriptor.ATTRIBUTES.name())
            .stream()
            .flatMap(f -> SelectionSetWrapper.from(f.getSelectionSet()).getFields("*").stream())
            .map(f -> currentEntitySchema.getAttributeByName(f.getName(), PROPERTY_NAME_NAMING_CONVENTION))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .map(AttributeSchemaContract::getName)
            .collect(Collectors.toUnmodifiableSet())
            .toArray(String[]::new);

        if (neededAttributes.length == 0) {
            return Optional.empty();
        }
        return Optional.of(attributeContent(neededAttributes));
    }

    @Nonnull
    private static Optional<AssociatedDataContent> buildAssociatedDataContentRequirement(@Nonnull SelectionSetWrapper selectionSetWrapper,
                                                                                         @Nonnull EntitySchemaContract currentEntitySchema) {
        if (!needsAssociatedData(selectionSetWrapper)) {
            return Optional.empty();
        }

        final String[] neededAssociatedData = selectionSetWrapper.getFields(EntityDescriptor.ASSOCIATED_DATA.name())
            .stream()
            .flatMap(f -> SelectionSetWrapper.from(f.getSelectionSet()).getFields("*").stream())
            .map(f -> currentEntitySchema.getAssociatedDataByName(f.getName(), PROPERTY_NAME_NAMING_CONVENTION))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .map(AssociatedDataSchemaContract::getName)
            .collect(Collectors.toUnmodifiableSet())
            .toArray(String[]::new);

        if (neededAssociatedData.length == 0) {
            return Optional.empty();
        }
        return Optional.of(associatedDataContent(neededAssociatedData));
    }

    @Nonnull
    private static Optional<PriceContent> buildPriceContentRequirement(@Nonnull SelectionSetWrapper selectionSetWrapper) {
        if (!needsPrices(selectionSetWrapper)) {
            return Optional.empty();
        }

        if (selectionSetWrapper.getFields(EntityDescriptor.PRICES.name())
            .stream()
            .anyMatch(f -> f.getArguments().get(PricesFieldHeaderDescriptor.PRICE_LISTS.name()) == null)) {
            return Optional.of(priceContentAll());
        } else {
            final Set<String> neededPriceLists = createHashSet(10);

            // check price for sale fields
            neededPriceLists.addAll(
                selectionSetWrapper.getFields(EntityDescriptor.PRICE_FOR_SALE.name())
                    .stream()
                    .map(f -> (String) f.getArguments().get(PriceForSaleFieldHeaderDescriptor.PRICE_LIST.name()))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet())
            );

            // check price fields
            neededPriceLists.addAll(
                selectionSetWrapper.getFields(EntityDescriptor.PRICE.name())
                    .stream()
                    .map(f -> (String) f.getArguments().get(PriceFieldHeaderDescriptor.PRICE_LIST.name()))
                    .collect(Collectors.toSet())
            );

            // check prices fields
            //noinspection unchecked
            neededPriceLists.addAll(
                selectionSetWrapper.getFields(EntityDescriptor.PRICES.name())
                    .stream()
                    .flatMap(f -> ((List<String>) f.getArguments().get(PricesFieldHeaderDescriptor.PRICE_LISTS.name())).stream())
                    .collect(Collectors.toSet())
            );

            return Optional.of(priceContent(neededPriceLists.toArray(String[]::new)));
        }
    }


    @Nonnull
    private static List<ReferenceContent> buildReferenceContentRequirement(@Nonnull SelectionSetWrapper selectionSetWrapper,
                                                                           @Nullable Locale desiredLocale,
                                                                           @Nonnull EntitySchemaContract currentEntitySchema,
                                                                           @Nonnull Function<String, EntitySchemaContract> entitySchemaFetcher) {
        if (!needsReferences(selectionSetWrapper, currentEntitySchema)) {
            return List.of();
        }

        return currentEntitySchema.getReferences()
            .values()
            .stream()
            .map(it -> new FieldsForReferenceHolder(
                it,
                selectionSetWrapper.getFields(it.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
            ))
            .filter(it -> !it.fields().isEmpty())
            .map(it -> new RequirementForReferenceHolder(
                it.referenceSchema(),
                buildEntityRequirement(
                    SelectionSetWrapper.from(
                        it.fields()
                            .stream()
                            .flatMap(it2 -> it2.getSelectionSet().getFields(ReferenceDescriptor.REFERENCED_ENTITY.name()).stream())
                            .map(SelectedField::getSelectionSet)
                            .toList()
                    ),
                    desiredLocale,
                    it.referenceSchema().isReferencedEntityTypeManaged() ? entitySchemaFetcher.apply(it.referenceSchema().getReferencedEntityType()) : null,
                    entitySchemaFetcher
                ),
                buildGroupEntityRequirement(
                    SelectionSetWrapper.from(
                        it.fields()
                            .stream()
                            .flatMap(it2 -> it2.getSelectionSet().getFields(ReferenceDescriptor.GROUP_ENTITY.name()).stream())
                            .map(SelectedField::getSelectionSet)
                            .toList()
                    ),
                    desiredLocale,
                    it.referenceSchema().isReferencedGroupTypeManaged() ? entitySchemaFetcher.apply(it.referenceSchema().getReferencedGroupType()) : null,
                    entitySchemaFetcher
                )
            ))
            .map(it -> referenceContent(it.referenceSchema().getName(), it.entityRequirement(), it.groupRequirement()))
            .toList();
    }

    @Nonnull
    private static Optional<DataInLocales> buildDataInLocalesRequirement(@Nonnull SelectionSetWrapper selectionSetWrapper,
                                                                         @Nullable Locale desiredLocale,
                                                                         @Nonnull EntitySchemaContract currentEntitySchema) {
        if (!needsAttributes(selectionSetWrapper) && !needsAssociatedData(selectionSetWrapper)) {
            return Optional.empty();
        }

        final Set<Locale> neededLocales = createHashSet(currentEntitySchema.getLocales().size());
        if (desiredLocale != null) {
            neededLocales.add(desiredLocale);
        }
        neededLocales.addAll(
            selectionSetWrapper.getFields(EntityDescriptor.ATTRIBUTES.name())
                .stream()
                .map(f -> (Locale) f.getArguments().get(AttributesFieldHeaderDescriptor.LOCALE.name()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet())
        );
        neededLocales.addAll(
            selectionSetWrapper.getFields(EntityDescriptor.ASSOCIATED_DATA.name())
                .stream()
                .map(f -> (Locale) f.getArguments().get(AssociatedDataFieldHeaderDescriptor.LOCALE.name()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet())
        );

        if (neededLocales.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(dataInLocales(neededLocales.toArray(Locale[]::new)));
    }

    private record FieldsForReferenceHolder(@Nonnull ReferenceSchemaContract referenceSchema, @Nonnull List<SelectedField> fields) {}
    private record RequirementForReferenceHolder(@Nonnull ReferenceSchemaContract referenceSchema,
                                                 @Nullable EntityFetch entityRequirement,
                                                 @Nullable EntityGroupFetch groupRequirement) {}
}
