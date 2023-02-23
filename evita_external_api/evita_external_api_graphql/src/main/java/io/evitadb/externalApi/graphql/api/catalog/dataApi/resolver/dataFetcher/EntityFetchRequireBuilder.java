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
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.EntityHeaderDescriptor.AssociatedDataFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.EntityHeaderDescriptor.AttributesFieldHeaderDescriptor;
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
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.FIELD_NAME_NAMING_CONVENTION;
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

        final List<EntityContentRequire> entityContentRequires = new LinkedList<>();

        final boolean needsAttributes = selectionSetWrapper.contains(EntityDescriptor.ATTRIBUTES.name());
        final boolean needsAssociatedData = selectionSetWrapper.contains(EntityDescriptor.ASSOCIATED_DATA.name());
        final boolean needsPrices = selectionSetWrapper.contains(EntityDescriptor.PRICE.name() + "*");
        final boolean needsReferences = currentEntitySchema.getReferences()
            .values()
            .stream()
            .map(it -> it.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
            .anyMatch(selectionSetWrapper::contains);
        final boolean needsEntityBody =
            selectionSetWrapper.contains(EntityDescriptor.HIERARCHICAL_PLACEMENT.name()) ||
                selectionSetWrapper.contains(EntityDescriptor.LOCALES.name()) ||
                needsAttributes ||
                needsAssociatedData ||
                needsPrices ||
                needsReferences;
        if (!needsEntityBody) {
            return null;
        }

        if (needsAttributes) {
            final String[] neededAttributes = selectionSetWrapper.getFields(EntityDescriptor.ATTRIBUTES.name())
                .stream()
                .flatMap(f -> SelectionSetWrapper.from(f.getSelectionSet()).getFields("*").stream())
                .map(f -> currentEntitySchema.getAttributeByName(f.getName(), FIELD_NAME_NAMING_CONVENTION))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(AttributeSchemaContract::getName)
                .collect(Collectors.toUnmodifiableSet())
                .toArray(String[]::new);
            entityContentRequires.add(attributeContent(neededAttributes));
        }
        if (needsAssociatedData) {
            final String[] neededAssociatedData = selectionSetWrapper.getFields(EntityDescriptor.ASSOCIATED_DATA.name())
                .stream()
                .flatMap(f -> SelectionSetWrapper.from(f.getSelectionSet()).getFields("*").stream())
                .map(f -> currentEntitySchema.getAssociatedDataByName(f.getName(), FIELD_NAME_NAMING_CONVENTION))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(AssociatedDataSchemaContract::getName)
                .collect(Collectors.toUnmodifiableSet())
                .toArray(String[]::new);
            entityContentRequires.add(associatedDataContent(neededAssociatedData));
        }
        if (needsPrices) {
            final boolean needsAllPrices = selectionSetWrapper.containsAnyOf(EntityDescriptor.PRICE.name(), EntityDescriptor.PRICES.name()) ||
                selectionSetWrapper.getFields(EntityDescriptor.PRICE_FOR_SALE.name()).stream().anyMatch(f -> !f.getArguments().isEmpty());
            entityContentRequires.add(needsAllPrices ? priceContentAll() : priceContent());
        }
        if (needsReferences) {
            currentEntitySchema.getReferences()
                .values()
                .stream()
                .map(it -> new FieldsForReferenceHolder(
                    it,
                    selectionSetWrapper.getFields(it.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
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
                .forEach(entityContentRequires::add);
        }
        if (needsAttributes || needsAssociatedData) {
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
            if (!neededLocales.isEmpty()) {
                entityContentRequires.add(dataInLocales(neededLocales.toArray(Locale[]::new)));
            }
        }

        return entityContentRequires;
    }

    private record FieldsForReferenceHolder(@Nonnull ReferenceSchemaContract referenceSchema, @Nonnull List<SelectedField> fields) {}
    private record RequirementForReferenceHolder(@Nonnull ReferenceSchemaContract referenceSchema,
                                                 @Nullable EntityFetch entityRequirement,
                                                 @Nullable EntityGroupFetch groupRequirement) {}
}
