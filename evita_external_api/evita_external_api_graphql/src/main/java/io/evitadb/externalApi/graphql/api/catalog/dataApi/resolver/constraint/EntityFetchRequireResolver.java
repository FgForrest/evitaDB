/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint;

import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.*;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaProvider;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.HierarchyDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ReferenceDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.model.AttributesProviderDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GraphQLEntityDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.AccompanyingPriceFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.AssociatedDataFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.AttributesFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.ParentsFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.PriceForSaleDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.ReferenceFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetAggregator;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidArgumentException;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidResponseUsageException;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

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
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;
import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * Custom constraint resolver which resolves additional constraints from output fields defined by client, rather
 * than using main query.
 * Resolves {@link EntityFetch} based on which entity fields client specified.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class EntityFetchRequireResolver {

	private static final Set<String> PRICE_FOR_SALE_FIELDS = Set.of(
		GraphQLEntityDescriptor.PRICE_FOR_SALE.name(),
		GraphQLEntityDescriptor.ALL_PRICES_FOR_SALE.name()
	);
	private static final Set<String> CUSTOM_PRICE_FIELDS = Set.of(
		GraphQLEntityDescriptor.PRICE.name(), // todo #538: deprecated, remove
		GraphQLEntityDescriptor.PRICES.name()
	);

	@Nonnull private final Function<String, EntitySchemaContract> entitySchemaFetcher;
	@Nonnull private final FilterConstraintResolver filterConstraintResolver;
	@Nonnull private final OrderConstraintResolver orderConstraintResolver;
	@Nonnull private final RequireConstraintResolver requireConstraintResolver;

	@Nonnull
	public Optional<EntityFetch> resolveEntityFetch(@Nonnull SelectionSetAggregator selectionSetAggregator,
	                                                @Nullable Locale desiredLocale,
	                                                @Nonnull CatalogSchemaContract catalogSchemaContract,
	                                                @Nonnull Set<Locale> allPossibleLocales) {
		return resolveContentRequirements(
			selectionSetAggregator,
			desiredLocale,
			catalogSchemaContract,
			allPossibleLocales
		)
			.map(it -> entityFetch(it.toArray(EntityContentRequire[]::new)));
	}

	@Nonnull
	public Optional<EntityFetch> resolveEntityFetch(@Nonnull SelectionSetAggregator selectionSetAggregator,
	                                                @Nullable Locale desiredLocale,
	                                                @Nullable EntitySchemaContract currentEntitySchema) {
		return resolveContentRequirements(
			selectionSetAggregator,
			desiredLocale,
			currentEntitySchema
		)
			.map(it -> entityFetch(it.toArray(EntityContentRequire[]::new)));
	}

	@Nonnull
	public Optional<EntityGroupFetch> resolveGroupFetch(@Nonnull SelectionSetAggregator selectionSetAggregator,
	                                                    @Nullable Locale desiredLocale,
	                                                    @Nullable EntitySchemaContract currentEntitySchema) {
		return resolveContentRequirements(
			selectionSetAggregator,
			desiredLocale,
			currentEntitySchema
		)
			.map(it -> entityGroupFetch(it.toArray(EntityContentRequire[]::new)));
	}

	@Nonnull
	private Optional<List<EntityContentRequire>> resolveContentRequirements(@Nonnull SelectionSetAggregator selectionSetAggregator,
	                                                                        @Nullable Locale desiredLocale,
	                                                                        @Nonnull CatalogSchemaContract catalogSchemaContract,
	                                                                        @Nonnull Set<Locale> allPossibleLocales) {
		if (!needsEntityBody(selectionSetAggregator)) {
			return Optional.empty();
		}

		final List<EntityContentRequire> entityContentRequires = new LinkedList<>();
		resolveAttributeContent(selectionSetAggregator, catalogSchemaContract).ifPresent(entityContentRequires::add);
		resolveDataInLocales(selectionSetAggregator, desiredLocale, allPossibleLocales).ifPresent(entityContentRequires::add);

		return Optional.of(entityContentRequires);
	}

	@Nonnull
	private Optional<List<EntityContentRequire>> resolveContentRequirements(@Nonnull SelectionSetAggregator selectionSetAggregator,
	                                                                        @Nullable Locale desiredLocale,
	                                                                        @Nullable EntitySchemaContract currentEntitySchema) {
		// no entity schema, no available data to fetch
		if (currentEntitySchema == null) {
			return Optional.empty();
		}

		if (!needsEntityBody(selectionSetAggregator, currentEntitySchema)) {
			return Optional.empty();
		}

		final List<EntityContentRequire> entityContentRequires = new LinkedList<>();
		resolveHierarchyContent(selectionSetAggregator, desiredLocale, currentEntitySchema).ifPresent(entityContentRequires::add);
		resolveAttributeContent(selectionSetAggregator, currentEntitySchema).ifPresent(entityContentRequires::add);
		resolveAssociatedDataContent(selectionSetAggregator, currentEntitySchema).ifPresent(entityContentRequires::add);
		resolvePriceContent(selectionSetAggregator).ifPresent(entityContentRequires::add);
		entityContentRequires.addAll(resolveReferenceContent(selectionSetAggregator, desiredLocale, currentEntitySchema));
		resolveDataInLocales(selectionSetAggregator, desiredLocale, currentEntitySchema.getLocales()).ifPresent(entityContentRequires::add);

		return Optional.of(entityContentRequires);
	}

	private boolean needsEntityBody(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		return needsVersion(selectionSetAggregator) ||
			needsLocales(selectionSetAggregator) ||
			needsAttributes(selectionSetAggregator);
	}

	private boolean needsEntityBody(@Nonnull SelectionSetAggregator selectionSetAggregator, @Nonnull EntitySchemaContract currentEntitySchema) {
		return needsVersion(selectionSetAggregator) ||
			needsParent(selectionSetAggregator) ||
			needsParents(selectionSetAggregator) ||
			needsLocales(selectionSetAggregator) ||
			needsAttributes(selectionSetAggregator) ||
			needsAssociatedData(selectionSetAggregator) ||
			needsPrices(selectionSetAggregator) ||
			needsReferences(selectionSetAggregator, currentEntitySchema);
	}

	private boolean needsVersion(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		return selectionSetAggregator.containsImmediate(GraphQLEntityDescriptor.VERSION.name());
	}

	private boolean needsParent(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		return selectionSetAggregator.containsImmediate(GraphQLEntityDescriptor.PARENT_PRIMARY_KEY.name());
	}

	private boolean needsParents(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		return selectionSetAggregator.containsImmediate(GraphQLEntityDescriptor.PARENTS.name());
	}

	private boolean needsLocales(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		return selectionSetAggregator.containsImmediate(GraphQLEntityDescriptor.LOCALES.name()) ||
			selectionSetAggregator.containsImmediate(GraphQLEntityDescriptor.ALL_LOCALES.name());
	}

	private boolean needsAttributes(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		return selectionSetAggregator.containsImmediate(AttributesProviderDescriptor.ATTRIBUTES.name());
	}

	private boolean needsAssociatedData(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		return selectionSetAggregator.containsImmediate(GraphQLEntityDescriptor.ASSOCIATED_DATA.name());
	}

	private boolean needsPrices(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		return selectionSetAggregator.containsImmediate(GraphQLEntityDescriptor.PRICE.name() + "*") ||
			selectionSetAggregator.containsImmediate(GraphQLEntityDescriptor.MULTIPLE_PRICES_FOR_SALE_AVAILABLE.name()) ||
			selectionSetAggregator.containsImmediate(GraphQLEntityDescriptor.ALL_PRICES_FOR_SALE.name());
	}

	private boolean needsReferences(@Nonnull SelectionSetAggregator selectionSetAggregator, @Nonnull EntitySchemaContract currentEntitySchema) {
		return currentEntitySchema.getReferences()
			.values()
			.stream()
			.map(it -> it.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
			.anyMatch(selectionSetAggregator::containsImmediate);
	}

	@Nonnull
	private Optional<HierarchyContent> resolveHierarchyContent(@Nonnull SelectionSetAggregator selectionSetAggregator,
															   @Nullable Locale desiredLocale,
	                                                           @Nonnull EntitySchemaContract currentEntitySchema) {
		if (!needsParents(selectionSetAggregator) && !needsParent(selectionSetAggregator)) {
			return Optional.empty();
		}

		final List<SelectedField> parentsFields = selectionSetAggregator.getImmediateFields(GraphQLEntityDescriptor.PARENTS.name());
		Assert.isTrue(
			parentsFields.size() <= 1,
			() -> new GraphQLInvalidResponseUsageException("Only one `" + GraphQLEntityDescriptor.PARENTS.name() + "` field is supported.")
		);
		return parentsFields.stream()
			.findFirst()
			.map(parentsField -> {
				final DataLocator hierarchyDataLocator = new HierarchyDataLocator(currentEntitySchema.getName());
				final HierarchyStopAt stopAt = Optional.ofNullable(parentsField.getArguments().get(ParentsFieldHeaderDescriptor.STOP_AT.name()))
					.map(it -> (HierarchyStopAt) requireConstraintResolver.resolve(
						hierarchyDataLocator,
						hierarchyDataLocator,
						ParentsFieldHeaderDescriptor.STOP_AT.name(),
						it
					))
					.orElse(null);

				final EntityFetch entityFetch = resolveEntityFetch(
					SelectionSetAggregator.from(parentsField.getSelectionSet()),
					desiredLocale,
					currentEntitySchema
				).orElse(null);

				return hierarchyContent(stopAt, entityFetch);
			}).or(() -> {
				if (!selectionSetAggregator.getImmediateFields(GraphQLEntityDescriptor.PARENT_PRIMARY_KEY.name()).isEmpty()) {
					// we need only direct parent to be able to return parentPrimaryKey
					return Optional.of(hierarchyContent(stopAt(distance(1))));
				} else {
					return Optional.empty();
				}
			});
	}

	@Nonnull
	private Optional<AttributeContent> resolveAttributeContent(@Nonnull SelectionSetAggregator selectionSetAggregator,
	                                                           @Nonnull AttributeSchemaProvider<?> attributeSchemaProvider) {
		if (!needsAttributes(selectionSetAggregator)) {
			return Optional.empty();
		}

		final String[] neededAttributes = selectionSetAggregator.getImmediateFields(AttributesProviderDescriptor.ATTRIBUTES.name())
			.stream()
			.flatMap(f -> SelectionSetAggregator.getImmediateFields(f.getSelectionSet()).stream())
			.map(f -> attributeSchemaProvider.getAttributeByName(f.getName(), PROPERTY_NAME_NAMING_CONVENTION))
			.filter(Optional::isPresent)
			.map(Optional::get)
			.map(AttributeSchemaContract::getName)
			.collect(Collectors.toUnmodifiableSet())
			.toArray(String[]::new);

		return Optional.of(attributeContent(neededAttributes));
	}

	@Nonnull
	private Optional<AssociatedDataContent> resolveAssociatedDataContent(@Nonnull SelectionSetAggregator selectionSetAggregator,
	                                                                     @Nonnull EntitySchemaContract currentEntitySchema) {
		if (!needsAssociatedData(selectionSetAggregator)) {
			return Optional.empty();
		}

		final String[] neededAssociatedData = selectionSetAggregator.getImmediateFields(GraphQLEntityDescriptor.ASSOCIATED_DATA.name())
			.stream()
			.flatMap(f -> SelectionSetAggregator.getImmediateFields(f.getSelectionSet()).stream())
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
	private Optional<PriceContent> resolvePriceContent(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		if (!needsPrices(selectionSetAggregator)) {
			return Optional.empty();
		}

		if (isCustomPriceFieldPresent(selectionSetAggregator) || isCustomPriceForSaleFieldPresent(selectionSetAggregator)) {
			return Optional.of(priceContentAll());
		} else {
			final String[] accompanyingPriceListsToFetch = resolveAccompanyingPriceLists(selectionSetAggregator);
			return Optional.of(priceContent(PriceContentMode.RESPECTING_FILTER, accompanyingPriceListsToFetch));
		}
	}

	private boolean isCustomPriceFieldPresent(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		return !selectionSetAggregator.getImmediateFields(CUSTOM_PRICE_FIELDS).isEmpty();
	}

	private boolean isCustomPriceForSaleFieldPresent(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		return selectionSetAggregator.getImmediateFields(PRICE_FOR_SALE_FIELDS)
			.stream()
			.anyMatch(f -> !f.getArguments().isEmpty() || isCustomAccompanyingPriceFieldPresent(f));
	}

	private boolean isCustomAccompanyingPriceFieldPresent(@Nonnull SelectedField priceForSaleField) {
		return SelectionSetAggregator.getImmediateFields(PriceForSaleDescriptor.ACCOMPANYING_PRICE.name(), priceForSaleField.getSelectionSet())
			.stream()
			.anyMatch(apf -> apf.getArguments().size() > 1);
	}

	@Nonnull
	private String[] resolveAccompanyingPriceLists(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		return selectionSetAggregator.getImmediateFields(PRICE_FOR_SALE_FIELDS)
			.stream()
			.flatMap(f -> SelectionSetAggregator.getImmediateFields(PriceForSaleDescriptor.ACCOMPANYING_PRICE.name(), f.getSelectionSet())
				.stream()
				.flatMap(apf -> Stream.of((String[]) apf.getArguments().get(AccompanyingPriceFieldHeaderDescriptor.PRICE_LISTS.name()))))
			.toArray(String[]::new);
	}

	@Nonnull
	private List<ReferenceContent> resolveReferenceContent(@Nonnull SelectionSetAggregator selectionSetAggregator,
	                                                       @Nullable Locale desiredLocale,
	                                                       @Nonnull EntitySchemaContract currentEntitySchema) {
		if (!needsReferences(selectionSetAggregator, currentEntitySchema)) {
			return List.of();
		}

		return currentEntitySchema.getReferences()
			.values()
			.stream()
			.map(it -> new FieldsForReferenceHolder(
				it,
				selectionSetAggregator.getImmediateFields(it.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
			))
			.filter(it -> !it.fields().isEmpty())
			.map(it -> new RequirementForReferenceHolder(
				it.referenceSchema(),
				resolveReferenceContentFilter(currentEntitySchema, it).orElse(null),
				resolveReferenceContentOrder(currentEntitySchema, it).orElse(null),
				resolveReferenceAttributeContent(it).orElse(null),
				resolveReferenceEntityRequirement(desiredLocale, it).orElse(null),
				resolveReferenceGroupRequirement(desiredLocale, it).orElse(null)
			))
			.map(it -> {
				if (it.attributeContent() != null) {
					return referenceContentWithAttributes(
						it.referenceSchema().getName(),
						it.filterBy(),
						it.orderBy(),
						it.attributeContent(),
						it.entityRequirement(),
						it.groupRequirement()
					);
				} else {
					return referenceContent(
						it.referenceSchema().getName(),
						it.filterBy(),
						it.orderBy(),
						it.entityRequirement(),
						it.groupRequirement()
					);
				}
			})
			.toList();
	}

	@Nonnull
	private Optional<FilterBy> resolveReferenceContentFilter(@Nonnull EntitySchemaContract currentEntitySchema,
	                                                         @Nonnull FieldsForReferenceHolder fieldsForReferenceHolder) {
		final List<SelectedField> fields = fieldsForReferenceHolder.fields();
		final boolean someFieldHasFilter = fields.stream()
			.anyMatch(it -> it.getArguments().containsKey(ReferenceFieldHeaderDescriptor.FILTER_BY.name()));
		if (!someFieldHasFilter) {
			return Optional.empty();
		}
		Assert.isTrue(
			fields.size() <= 1,
			() -> new GraphQLInvalidArgumentException("Reference filtering is currently supported only if there is only one reference of particular name requested.")
		);

		return Optional.ofNullable(
			(FilterBy) filterConstraintResolver.resolve(
				new ReferenceDataLocator(currentEntitySchema.getName(), fieldsForReferenceHolder.referenceSchema().getName()),
				ReferenceFieldHeaderDescriptor.FILTER_BY.name(),
				fields.get(0).getArguments().get(ReferenceFieldHeaderDescriptor.FILTER_BY.name())
			)
		);
	}

	@Nonnull
	private Optional<OrderBy> resolveReferenceContentOrder(@Nonnull EntitySchemaContract currentEntitySchema,
	                                                       @Nonnull FieldsForReferenceHolder fieldsForReferenceHolder) {
		final List<SelectedField> fields = fieldsForReferenceHolder.fields();
		final boolean someFieldHasFilter = fields.stream()
			.anyMatch(it -> it.getArguments().containsKey(ReferenceFieldHeaderDescriptor.ORDER_BY.name()));
		if (!someFieldHasFilter) {
			return Optional.empty();
		}
		Assert.isTrue(
			fields.size() <= 1,
			() -> new GraphQLInvalidArgumentException("Reference ordering is currently supported only if there is only one reference of particular name requested.")
		);

		return Optional.ofNullable(
			(OrderBy) orderConstraintResolver.resolve(
				new ReferenceDataLocator(currentEntitySchema.getName(), fieldsForReferenceHolder.referenceSchema().getName()),
				ReferenceFieldHeaderDescriptor.ORDER_BY.name(),
				fieldsForReferenceHolder.fields().get(0).getArguments().get(ReferenceFieldHeaderDescriptor.ORDER_BY.name())
			)
		);
	}

	@Nonnull
	private Optional<AttributeContent> resolveReferenceAttributeContent(@Nonnull FieldsForReferenceHolder fieldsForReferenceHolder) {
		final List<DataFetchingFieldSelectionSet> attributeFields = fieldsForReferenceHolder.fields()
			.stream()
			.flatMap(it -> SelectionSetAggregator.getImmediateFields(ReferenceDescriptor.ATTRIBUTES.name(), it.getSelectionSet()).stream())
			.map(SelectedField::getSelectionSet)
			.toList();

		if (attributeFields.isEmpty()) {
			return Optional.empty();
		}

		final String[] neededAttributes = SelectionSetAggregator.getImmediateFields(attributeFields)
			.stream()
			.map(f -> fieldsForReferenceHolder.referenceSchema().getAttributeByName(f.getName(), PROPERTY_NAME_NAMING_CONVENTION))
			.filter(Optional::isPresent)
			.map(Optional::get)
			.map(AttributeSchemaContract::getName)
			.collect(Collectors.toUnmodifiableSet())
			.toArray(String[]::new);

		return Optional.of(attributeContent(neededAttributes));
	}

	@Nonnull
	private Optional<EntityFetch> resolveReferenceEntityRequirement(@Nullable Locale desiredLocale,
	                                                                @Nonnull FieldsForReferenceHolder fieldsForReference) {
		final SelectionSetAggregator referencedEntitySelectionSet = SelectionSetAggregator.from(
			fieldsForReference.fields()
				.stream()
				.flatMap(it2 -> SelectionSetAggregator.getImmediateFields(ReferenceDescriptor.REFERENCED_ENTITY.name(), it2.getSelectionSet()).stream())
				.map(SelectedField::getSelectionSet)
				.toList()
		);

		final EntitySchemaContract referencedEntitySchema = fieldsForReference.referenceSchema().isReferencedEntityTypeManaged()
			? entitySchemaFetcher.apply(fieldsForReference.referenceSchema().getReferencedEntityType())
			: null;

		final Optional<EntityFetch> referencedEntityRequirement = resolveEntityFetch(referencedEntitySelectionSet, desiredLocale, referencedEntitySchema);

		if (referencedEntityRequirement.isEmpty() && !referencedEntitySelectionSet.isEmpty()) {
			return Optional.of(entityFetch()); // if referenced entity was requested we want at least its body everytime
		}
		return referencedEntityRequirement;
	}

	@Nonnull
	private Optional<EntityGroupFetch> resolveReferenceGroupRequirement(@Nullable Locale desiredLocale,
	                                                          @Nonnull FieldsForReferenceHolder fieldsForReference) {
		final SelectionSetAggregator referencedGroupSelectionSet = SelectionSetAggregator.from(
			fieldsForReference.fields()
				.stream()
				.flatMap(it2 -> SelectionSetAggregator.getImmediateFields(ReferenceDescriptor.GROUP_ENTITY.name(), it2.getSelectionSet()).stream())
				.map(SelectedField::getSelectionSet)
				.toList()
		);

		final EntitySchemaContract referencedEntitySchema = fieldsForReference.referenceSchema().isReferencedGroupTypeManaged() ?
			entitySchemaFetcher.apply(fieldsForReference.referenceSchema().getReferencedGroupType()) :
			null;

		return resolveGroupFetch(referencedGroupSelectionSet, desiredLocale, referencedEntitySchema);
	}

	@Nonnull
	private Optional<DataInLocales> resolveDataInLocales(@Nonnull SelectionSetAggregator selectionSetAggregator,
	                                                     @Nullable Locale desiredLocale,
	                                                     @Nonnull Set<Locale> allPossibleLocales) {
		if (!needsAttributes(selectionSetAggregator) && !needsAssociatedData(selectionSetAggregator)) {
			return Optional.empty();
		}

		final Set<Locale> neededLocales = createHashSet(allPossibleLocales.size());
		if (desiredLocale != null) {
			neededLocales.add(desiredLocale);
		}
		neededLocales.addAll(
			selectionSetAggregator.getImmediateFields(GraphQLEntityDescriptor.ATTRIBUTES.name())
				.stream()
				.map(f -> (Locale) f.getArguments().get(AttributesFieldHeaderDescriptor.LOCALE.name()))
				.filter(Objects::nonNull)
				.collect(Collectors.toSet())
		);
		neededLocales.addAll(
			selectionSetAggregator.getImmediateFields(GraphQLEntityDescriptor.ASSOCIATED_DATA.name())
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

	private record FieldsForReferenceHolder(@Nonnull ReferenceSchemaContract referenceSchema,
	                                        @Nonnull List<SelectedField> fields) {
	}

	private record RequirementForReferenceHolder(@Nonnull ReferenceSchemaContract referenceSchema,
	                                             @Nullable FilterBy filterBy,
	                                             @Nullable OrderBy orderBy,
												 @Nullable AttributeContent attributeContent,
	                                             @Nullable EntityFetch entityRequirement,
	                                             @Nullable EntityGroupFetch groupRequirement) {
	}
}
