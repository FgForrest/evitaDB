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
import io.evitadb.externalApi.api.catalog.dataApi.constraint.InlineReferenceDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
import io.evitadb.externalApi.api.catalog.dataApi.model.AttributesProviderDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.DataChunkDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.api.catalog.model.VersionedDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GraphQLEntityDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.PaginatedListFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.StripListFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.AccompanyingPriceFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.AssociatedDataFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.AttributesFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.ParentsFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.PriceForSaleDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.ReferenceFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.ReferencesFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetAggregator;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
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
		GraphQLEntityDescriptor.PRICE.name(), // TOBEDONE #538: deprecated, remove
		EntityDescriptor.PRICES.name()
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
		entityContentRequires.addAll(resolveAccompanyingPriceContents(selectionSetAggregator));
		entityContentRequires.addAll(resolveReferenceContent(selectionSetAggregator, desiredLocale, currentEntitySchema));
		resolveDataInLocales(selectionSetAggregator, desiredLocale, currentEntitySchema.getLocales()).ifPresent(entityContentRequires::add);

		return Optional.of(entityContentRequires);
	}

	private static boolean needsEntityBody(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		return needsVersion(selectionSetAggregator) ||
			needsLocales(selectionSetAggregator) ||
			needsAttributes(selectionSetAggregator);
	}

	private static boolean needsEntityBody(@Nonnull SelectionSetAggregator selectionSetAggregator, @Nonnull EntitySchemaContract currentEntitySchema) {
		return needsVersion(selectionSetAggregator) ||
			needsScope(selectionSetAggregator) ||
			needsParent(selectionSetAggregator) ||
			needsParents(selectionSetAggregator) ||
			needsLocales(selectionSetAggregator) ||
			needsAttributes(selectionSetAggregator) ||
			needsAssociatedData(selectionSetAggregator) ||
			needsPrices(selectionSetAggregator) ||
			needsReferences(selectionSetAggregator, currentEntitySchema);
	}

	private static boolean needsVersion(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		return selectionSetAggregator.containsImmediate(VersionedDescriptor.VERSION.name());
	}

	private static boolean needsScope(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		return selectionSetAggregator.containsImmediate(EntityDescriptor.SCOPE.name());
	}

	private static boolean needsParent(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		return selectionSetAggregator.containsImmediate(GraphQLEntityDescriptor.PARENT_PRIMARY_KEY.name());
	}

	private static boolean needsParents(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		return selectionSetAggregator.containsImmediate(GraphQLEntityDescriptor.PARENTS.name());
	}

	private static boolean needsLocales(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		return selectionSetAggregator.containsImmediate(EntityDescriptor.LOCALES.name()) ||
			selectionSetAggregator.containsImmediate(EntityDescriptor.ALL_LOCALES.name());
	}

	private static boolean needsAttributes(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		return selectionSetAggregator.containsImmediate(AttributesProviderDescriptor.ATTRIBUTES.name());
	}

	private static boolean needsAssociatedData(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		return selectionSetAggregator.containsImmediate(EntityDescriptor.ASSOCIATED_DATA.name());
	}

	private static boolean needsPrices(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		return selectionSetAggregator.containsImmediate(GraphQLEntityDescriptor.PRICE.name() + "*") ||
			selectionSetAggregator.containsImmediate(EntityDescriptor.MULTIPLE_PRICES_FOR_SALE_AVAILABLE.name()) ||
			selectionSetAggregator.containsImmediate(GraphQLEntityDescriptor.ALL_PRICES_FOR_SALE.name());
	}

	private static boolean needsReferences(@Nonnull SelectionSetAggregator selectionSetAggregator, @Nonnull EntitySchemaContract currentEntitySchema) {
		return currentEntitySchema.getReferences()
			.values()
			.stream()
			.map(it -> it.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION) + "*")
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
				final DataLocator hierarchyDataLocator = new HierarchyDataLocator(new ManagedEntityTypePointer(currentEntitySchema.getName()));
				final HierarchyStopAt stopAt = Optional.ofNullable(parentsField.getArguments().get(ParentsFieldHeaderDescriptor.STOP_AT.name()))
					.map(it -> (HierarchyStopAt) this.requireConstraintResolver.resolve(
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
	private static Optional<AttributeContent> resolveAttributeContent(@Nonnull SelectionSetAggregator selectionSetAggregator,
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
	private static Optional<AssociatedDataContent> resolveAssociatedDataContent(@Nonnull SelectionSetAggregator selectionSetAggregator,
	                                                                            @Nonnull EntitySchemaContract currentEntitySchema) {
		if (!needsAssociatedData(selectionSetAggregator)) {
			return Optional.empty();
		}

		final String[] neededAssociatedData = selectionSetAggregator.getImmediateFields(
			                                                            EntityDescriptor.ASSOCIATED_DATA.name())
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
	private static Optional<PriceContent> resolvePriceContent(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		if (!needsPrices(selectionSetAggregator)) {
			return Optional.empty();
		}

		if (isCustomPriceFieldPresent(selectionSetAggregator) || isCustomPriceForSaleFieldPresent(selectionSetAggregator)) {
			return Optional.of(priceContentAll());
		} else {
			//noinspection DataFlowIssue
			return Optional.of(priceContent(PriceContentMode.RESPECTING_FILTER));
		}
	}

	private static boolean isCustomPriceFieldPresent(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		return !selectionSetAggregator.getImmediateFields(CUSTOM_PRICE_FIELDS).isEmpty();
	}

	private static boolean isCustomPriceForSaleFieldPresent(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		return selectionSetAggregator.getImmediateFields(PRICE_FOR_SALE_FIELDS)
			.stream()
			.anyMatch(f -> !f.getArguments().isEmpty());
	}

	@Nonnull
	private static String[] resolveAccompanyingPriceLists(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		//noinspection unchecked
		return selectionSetAggregator.getImmediateFields(PRICE_FOR_SALE_FIELDS)
			.stream()
			.flatMap(f -> SelectionSetAggregator.getImmediateFields(PriceForSaleDescriptor.ACCOMPANYING_PRICE.name(), f.getSelectionSet())
				.stream()
				.flatMap(apf -> ((List<String>) apf.getArguments().get(AccompanyingPriceFieldHeaderDescriptor.PRICE_LISTS.name())).stream()))
			.toArray(String[]::new);
	}

	/**
	 * Resolves {@link AccompanyingPriceContent} for default `priceForSale` field (i.e., without parameters). Custom
	 * `priceForSale` fields are computed during serialization manually.
	 */
	@Nonnull
	private static List<AccompanyingPriceContent> resolveAccompanyingPriceContents(@Nonnull SelectionSetAggregator selectionSetAggregator) {
		return selectionSetAggregator.getImmediateFields(PRICE_FOR_SALE_FIELDS)
			.stream()
			.filter(f -> f.getArguments().isEmpty())
			.flatMap(f -> SelectionSetAggregator.getImmediateFields(PriceForSaleDescriptor.ACCOMPANYING_PRICE.name(), f.getSelectionSet())
				.stream()
				.map(apf -> {
					final String priceName = apf.getAlias() != null ? apf.getAlias() : apf.getName();
					if (apf.getArguments().isEmpty()) {
						return accompanyingPriceContent(priceName);
					} else {
						//noinspection unchecked
						final String[] priceLists = ((List<String>) apf.getArguments().get(AccompanyingPriceFieldHeaderDescriptor.PRICE_LISTS.name())).toArray(String[]::new);
						return accompanyingPriceContent(priceName, priceLists);
					}
				}))
			.toList();
	}

	@Nonnull
	private List<ReferenceContent> resolveReferenceContent(@Nonnull SelectionSetAggregator selectionSetAggregator,
	                                                       @Nullable Locale desiredLocale,
	                                                       @Nonnull EntitySchemaContract currentEntitySchema) {
		if (!needsReferences(selectionSetAggregator, currentEntitySchema)) {
			return List.of();
		}

		final List<ReferenceContent> referenceContents = new LinkedList<>();
		for (final ReferenceSchemaContract referenceSchema : currentEntitySchema.getReferences().values()) {
			final String baseReferenceFieldName = EntityDescriptor.REFERENCE.name(referenceSchema);
			final String referencePageFieldName = EntityDescriptor.REFERENCE_PAGE.name(referenceSchema);
			final String referenceStripFieldName = EntityDescriptor.REFERENCE_STRIP.name(referenceSchema);
			final List<SelectedField> fieldsForReference = selectionSetAggregator.getImmediateFields(Set.of(
				baseReferenceFieldName,
				referencePageFieldName,
				referenceStripFieldName
			));
			if (fieldsForReference.isEmpty()) {
				continue;
			}

			final FieldsForReferenceHolder fieldsForReferenceHolder = new FieldsForReferenceHolder(
				referenceSchema,
				fieldsForReference,
				baseReferenceFieldName,
				referencePageFieldName,
				referenceStripFieldName
			);

			final RequirementForReferenceHolder requirementForReferenceHolder = new RequirementForReferenceHolder(
				fieldsForReferenceHolder.referenceSchema(),
				resolveReferenceContentFilter(currentEntitySchema, fieldsForReferenceHolder).orElse(null),
				resolveReferenceContentOrder(currentEntitySchema, fieldsForReferenceHolder).orElse(null),
				resolveReferenceAttributeContent(fieldsForReferenceHolder).orElse(null),
				resolveReferenceEntityRequirement(desiredLocale, fieldsForReferenceHolder).orElse(null),
				resolveReferenceGroupRequirement(desiredLocale, fieldsForReferenceHolder).orElse(null),
				resolveReferenceChunkingRequirement(fieldsForReferenceHolder).orElse(null)
			);

			if (requirementForReferenceHolder.attributeContent() != null) {
				referenceContents.add(referenceContentWithAttributes(
					requirementForReferenceHolder.referenceSchema().getName(),
					requirementForReferenceHolder.filterBy(),
					requirementForReferenceHolder.orderBy(),
					requirementForReferenceHolder.attributeContent(),
					requirementForReferenceHolder.entityRequirement(),
					requirementForReferenceHolder.groupRequirement(),
					requirementForReferenceHolder.chunk()
				));
			} else {
				referenceContents.add(referenceContent(
					requirementForReferenceHolder.referenceSchema().getName(),
					requirementForReferenceHolder.filterBy(),
					requirementForReferenceHolder.orderBy(),
					requirementForReferenceHolder.entityRequirement(),
					requirementForReferenceHolder.groupRequirement(),
					requirementForReferenceHolder.chunk()
				));
			}
		}

		return referenceContents;
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
			(FilterBy) this.filterConstraintResolver.resolve(
				new InlineReferenceDataLocator(new ManagedEntityTypePointer(currentEntitySchema.getName()), fieldsForReferenceHolder.referenceSchema().getName()),
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
			(OrderBy) this.orderConstraintResolver.resolve(
				new InlineReferenceDataLocator(new ManagedEntityTypePointer(currentEntitySchema.getName()), fieldsForReferenceHolder.referenceSchema().getName()),
				ReferenceFieldHeaderDescriptor.ORDER_BY.name(),
				fieldsForReferenceHolder.fields().get(0).getArguments().get(ReferenceFieldHeaderDescriptor.ORDER_BY.name())
			)
		);
	}

	@Nonnull
	private static Optional<AttributeContent> resolveReferenceAttributeContent(@Nonnull FieldsForReferenceHolder fieldsForReferenceHolder) {
		final List<DataFetchingFieldSelectionSet> attributeFields = fieldsForReferenceHolder.fields()
			.stream()
			.flatMap(it -> SelectionSetAggregator.getImmediateFields(AttributesProviderDescriptor.ATTRIBUTES.name(), it.getSelectionSet()).stream())
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
		final SelectionSetAggregator referencedEntitySelectionSet = resolveReferencedEntitySelectionSet(fieldsForReference, ReferenceDescriptor.REFERENCED_ENTITY);

		final EntitySchemaContract referencedEntitySchema = fieldsForReference.referenceSchema().isReferencedEntityTypeManaged()
			? this.entitySchemaFetcher.apply(fieldsForReference.referenceSchema().getReferencedEntityType())
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
		final SelectionSetAggregator referencedGroupSelectionSet = resolveReferencedEntitySelectionSet(fieldsForReference, ReferenceDescriptor.GROUP_ENTITY);

		final EntitySchemaContract referencedEntitySchema = fieldsForReference.referenceSchema().isReferencedGroupTypeManaged() ?
			this.entitySchemaFetcher.apply(fieldsForReference.referenceSchema().getReferencedGroupType()) :
			null;

		return resolveGroupFetch(referencedGroupSelectionSet, desiredLocale, referencedEntitySchema);
	}

	@Nonnull
	private static SelectionSetAggregator resolveReferencedEntitySelectionSet(@Nonnull FieldsForReferenceHolder fieldsForReference,
	                                                                          @Nonnull PropertyDescriptor referencedEntityField) {
		return SelectionSetAggregator.from(
			Stream.concat(
					fieldsForReference.fields()
						.stream()
						.filter(it -> it.getName().equals(fieldsForReference.baseReferenceFieldName()))
						.flatMap(it -> SelectionSetAggregator.getImmediateFields(referencedEntityField.name(), it.getSelectionSet()).stream())
						.map(SelectedField::getSelectionSet),
					fieldsForReference.fields()
						.stream()
						.filter(it -> it.getName().equals(fieldsForReference.referencePageFieldName()) || it.getName().equals(fieldsForReference.referenceStripFieldName()))
						.flatMap(it -> SelectionSetAggregator.getImmediateFields(DataChunkDescriptor.DATA.name(), it.getSelectionSet()).stream())
						.flatMap(it -> SelectionSetAggregator.getImmediateFields(referencedEntityField.name(), it.getSelectionSet()).stream())
						.map(SelectedField::getSelectionSet)
				)
				.toList()
		);
	}

	@Nonnull
	private Optional<ChunkingRequireConstraint> resolveReferenceChunkingRequirement(@Nonnull FieldsForReferenceHolder fieldsForReferenceHolder) {
		final List<SelectedField> fields = fieldsForReferenceHolder.fields();
		final boolean hasChunkingArgument = fields.stream().anyMatch(field ->
				(
					field.getName().equals(fieldsForReferenceHolder.baseReferenceFieldName()) &&
					field.getArguments().containsKey(ReferencesFieldHeaderDescriptor.LIMIT.name())
				) ||
				field.getName().equals(fieldsForReferenceHolder.referencePageFieldName()) ||
				field.getName().equals(fieldsForReferenceHolder.referenceStripFieldName())
		);
		if (!hasChunkingArgument) {
			return Optional.empty();
		}

		Assert.isTrue(
			fields.size() == 1,
			() -> new GraphQLInvalidResponseUsageException(
				"There may be only one reference field in entity if there is reference field with chunking."
			)
		);

		final SelectedField dataChunkField = fields.get(0);
		if (dataChunkField.getName().equals(fieldsForReferenceHolder.baseReferenceFieldName())) {
			return Optional.of(strip(
				null,
				(Integer) dataChunkField.getArguments().get(ReferencesFieldHeaderDescriptor.LIMIT.name())
			));
		} else if (dataChunkField.getName().equals(fieldsForReferenceHolder.referencePageFieldName())) {
			if (isOnlyTotalCountIsDesiredForReferenceDataChunk(dataChunkField)) {
				// performance optimization, we don't need actual references
				return Optional.of(page(1, 0));
			}
			return Optional.of(page(
				(Integer) dataChunkField.getArguments().get(PaginatedListFieldHeaderDescriptor.NUMBER.name()),
				(Integer) dataChunkField.getArguments().get(PaginatedListFieldHeaderDescriptor.SIZE.name())
			));
		} else if (dataChunkField.getName().equals(fieldsForReferenceHolder.referenceStripFieldName())) {
			if (isOnlyTotalCountIsDesiredForReferenceDataChunk(dataChunkField)) {
				// performance optimization, we don't need actual references
				return Optional.of(strip(0, 0));
			}
			return Optional.of(strip(
				(Integer) dataChunkField.getArguments().get(StripListFieldHeaderDescriptor.OFFSET.name()),
				(Integer) dataChunkField.getArguments().get(StripListFieldHeaderDescriptor.LIMIT.name())
			));
		} else {
			throw new GraphQLInternalError(
				"Invalid reference data chunk field.",
				"There is supposed to be a data chunk field, but has invalid name `" + dataChunkField.getName() + "`."
			);
		}
	}

	private static boolean isOnlyTotalCountIsDesiredForReferenceDataChunk(@Nonnull SelectedField dataChunkField) {
		final List<SelectedField> dataChuckInnerFields = dataChunkField.getSelectionSet().getImmediateFields();

		return dataChuckInnerFields.size() == 1 &&
			dataChuckInnerFields.get(0).getName().equals(DataChunkDescriptor.TOTAL_RECORD_COUNT.name());
	}

	@Nonnull
	private static Optional<DataInLocales> resolveDataInLocales(@Nonnull SelectionSetAggregator selectionSetAggregator,
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
			selectionSetAggregator.getImmediateFields(AttributesProviderDescriptor.ATTRIBUTES.name())
				.stream()
				.map(f -> (Locale) f.getArguments().get(AttributesFieldHeaderDescriptor.LOCALE.name()))
				.filter(Objects::nonNull)
				.collect(Collectors.toSet())
		);
		neededLocales.addAll(
			selectionSetAggregator.getImmediateFields(EntityDescriptor.ASSOCIATED_DATA.name())
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
	                                        @Nonnull List<SelectedField> fields,
	                                        @Nonnull String baseReferenceFieldName,
	                                        @Nonnull String referencePageFieldName,
	                                        @Nonnull String referenceStripFieldName) {
	}

	private record RequirementForReferenceHolder(@Nonnull ReferenceSchemaContract referenceSchema,
	                                             @Nullable FilterBy filterBy,
	                                             @Nullable OrderBy orderBy,
												 @Nullable AttributeContent attributeContent,
	                                             @Nullable EntityFetch entityRequirement,
	                                             @Nullable EntityGroupFetch groupRequirement,
	                                             @Nullable ChunkingRequireConstraint chunk) {
	}
}
