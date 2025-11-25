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

import graphql.schema.SelectedField;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.RequireConstraint;
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
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferencePageDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceStripDescriptor;
import io.evitadb.externalApi.api.catalog.model.VersionedDescriptor;
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
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidResponseUsageException;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.AbstractMap.SimpleEntry;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
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
	public Optional<EntityFetch> resolveEntityFetch(
		@Nonnull SelectionSetAggregator selectionSetAggregator,
		@Nullable Locale desiredLocale,
		@Nullable EntitySchemaContract currentEntitySchema
	) {
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
	private static Optional<List<EntityContentRequire>> resolveContentRequirements(
		@Nonnull SelectionSetAggregator selectionSetAggregator,
		@Nullable Locale desiredLocale,
		@Nonnull CatalogSchemaContract catalogSchemaContract,
		@Nonnull Set<Locale> allPossibleLocales
	) {
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
		entityContentRequires.addAll(resolveReferenceContents(selectionSetAggregator, desiredLocale, currentEntitySchema));
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
	private Collection<ReferenceContent> resolveReferenceContents(
		@Nonnull SelectionSetAggregator selectionSetAggregator,
		@Nullable Locale desiredLocale,
		@Nonnull EntitySchemaContract currentEntitySchema
	) {
		if (!needsReferences(selectionSetAggregator, currentEntitySchema)) {
			return List.of();
		}

		return currentEntitySchema.getReferences()
			.values()
			.stream()
			.flatMap(referenceSchema -> Stream.of(
				// basic reference fields
				selectionSetAggregator.getImmediateFields(EntityDescriptor.REFERENCE.name(referenceSchema))
					.stream()
					.map(basicReferenceField -> resolveReferenceContentFromBasicField(
						basicReferenceField,
						desiredLocale,
						currentEntitySchema,
						referenceSchema
					)),
				// reference page fields
				selectionSetAggregator.getImmediateFields(EntityDescriptor.REFERENCE_PAGE.name(referenceSchema))
					.stream()
					.map(referencePageField -> resolveReferenceContentFromPageField(
						referencePageField,
						desiredLocale,
						currentEntitySchema,
						referenceSchema
					)),
				// reference strip fields
				selectionSetAggregator.getImmediateFields(EntityDescriptor.REFERENCE_STRIP.name(referenceSchema))
					.stream()
					.map(referenceStripField -> resolveReferenceContentFromStripField(
						referenceStripField,
						desiredLocale,
						currentEntitySchema,
						referenceSchema
					))
			)
				.flatMap(it -> it)
				.map(referenceContent -> new SimpleEntry<>(
					referenceContent.getInstanceName(),
					referenceContent
				)))
			.collect(Collectors.toMap(
				Entry::getKey,
				Entry::getValue,
				(c, c2) -> {
					throw new GraphQLInvalidResponseUsageException(
						"Duplicate references (" + c.getInstanceName() + ") requested. " +
						"Each field representing a particular reference must have unique alias, or there must be only " +
						"one field for a particular reference."
					);
				}))
			.values();
	}

	@Nonnull
	private ReferenceContent resolveReferenceContentFromBasicField(
		@Nonnull SelectedField basicReferenceField,
		@Nullable Locale desiredLocale,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final SelectionSetAggregator nestedFields = SelectionSetAggregator.from(basicReferenceField.getSelectionSet());

		final String instanceName = resolveReferenceContentInstanceName(basicReferenceField);
		final String referenceName = referenceSchema.getName();
		final FilterBy filterBy = resolveReferenceContentFilter(basicReferenceField, currentEntitySchema, referenceSchema);
		final OrderBy orderBy = resolveReferenceContentOrder(basicReferenceField, currentEntitySchema, referenceSchema);

		final AttributeContent attributeContent = resolveReferenceContentAttributes(nestedFields, referenceSchema);
		final EntityFetch entityFetch = resolveReferenceContentEntityFetch(nestedFields, desiredLocale, referenceSchema);
		final EntityGroupFetch entityGroupFetch = resolveReferenceContentEntityGroupFetch(nestedFields, desiredLocale, referenceSchema);
		final ChunkingRequireConstraint chunking = resolveReferenceContentChunkingFromBasicField(basicReferenceField);

		return new ReferenceContent(
			instanceName,
			ManagedReferencesBehaviour.ANY,
			new String[] { referenceName },
			new RequireConstraint[]{
				attributeContent,
				entityFetch,
				entityGroupFetch,
				chunking
			},
			new Constraint[]{
				filterBy,
				orderBy,
			}
		);
	}

	@Nonnull
	private ReferenceContent resolveReferenceContentFromPageField(
		@Nonnull SelectedField referencePageField,
		@Nullable Locale desiredLocale,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final SelectionSetAggregator nestedFields = SelectionSetAggregator.from(referencePageField.getSelectionSet());

		final String instanceName = resolveReferenceContentInstanceName(referencePageField);
		final String referenceName = referenceSchema.getName();
		final FilterBy filterBy = resolveReferenceContentFilter(referencePageField, currentEntitySchema, referenceSchema);
		final OrderBy orderBy = resolveReferenceContentOrder(referencePageField, currentEntitySchema, referenceSchema);

		final SelectionSetAggregator referenceBodyFields = SelectionSetAggregator.fromFields(nestedFields.getImmediateFields(ReferencePageDescriptor.DATA.name()));
		final AttributeContent attributeContent = resolveReferenceContentAttributes(referenceBodyFields, referenceSchema);
		final EntityFetch entityFetch = resolveReferenceContentEntityFetch(referenceBodyFields, desiredLocale, referenceSchema);
		final EntityGroupFetch entityGroupFetch = resolveReferenceContentEntityGroupFetch(referenceBodyFields, desiredLocale, referenceSchema);
		final ChunkingRequireConstraint chunking = resolveReferenceContentChunkingFromPageField(referencePageField, nestedFields);

		return new ReferenceContent(
			instanceName,
			ManagedReferencesBehaviour.ANY,
			new String[] { referenceName },
			new RequireConstraint[]{
				attributeContent,
				entityFetch,
				entityGroupFetch,
				chunking
			},
			new Constraint[]{
				filterBy,
				orderBy,
			}
		);
	}

	@Nonnull
	private ReferenceContent resolveReferenceContentFromStripField(
		@Nonnull SelectedField referenceStripField,
		@Nullable Locale desiredLocale,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final SelectionSetAggregator nestedFields = SelectionSetAggregator.from(referenceStripField.getSelectionSet());

		final String instanceName = resolveReferenceContentInstanceName(referenceStripField);
		final String referenceName = referenceSchema.getName();
		final FilterBy filterBy = resolveReferenceContentFilter(referenceStripField, currentEntitySchema, referenceSchema);
		final OrderBy orderBy = resolveReferenceContentOrder(referenceStripField, currentEntitySchema, referenceSchema);

		final SelectionSetAggregator referenceBodyFields = SelectionSetAggregator.fromFields(nestedFields.getImmediateFields(ReferenceStripDescriptor.DATA.name()));
		final AttributeContent attributeContent = resolveReferenceContentAttributes(referenceBodyFields, referenceSchema);
		final EntityFetch entityFetch = resolveReferenceContentEntityFetch(referenceBodyFields, desiredLocale, referenceSchema);
		final EntityGroupFetch entityGroupFetch = resolveReferenceContentEntityGroupFetch(referenceBodyFields, desiredLocale, referenceSchema);
		final ChunkingRequireConstraint chunking = resolveReferenceContentChunkingFromStripField(referenceStripField, nestedFields);

		return new ReferenceContent(
			instanceName,
			ManagedReferencesBehaviour.ANY,
			new String[] { referenceName },
			new RequireConstraint[]{
				attributeContent,
				entityFetch,
				entityGroupFetch,
				chunking
			},
			new Constraint[]{
				filterBy,
				orderBy,
			}
		);
	}

	@Nonnull
	private static String resolveReferenceContentInstanceName(@Nonnull SelectedField referenceField) {
		return referenceField.getAlias() != null
			? referenceField.getAlias()
			: referenceField.getName();
	}

	@Nullable
	private FilterBy resolveReferenceContentFilter(
		@Nonnull SelectedField referenceField,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final Object filterBy = referenceField.getArguments().get(ReferenceFieldHeaderDescriptor.FILTER_BY.name());
		if (filterBy == null) {
			return null;
		}

		return (FilterBy) this.filterConstraintResolver.resolve(
			new InlineReferenceDataLocator(new ManagedEntityTypePointer(currentEntitySchema.getName()), referenceSchema.getName()),
			ReferenceFieldHeaderDescriptor.FILTER_BY.name(),
			filterBy
		);
	}

	@Nullable
	private OrderBy resolveReferenceContentOrder(
		@Nonnull SelectedField referenceField,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final Object orderBy = referenceField.getArguments().get(ReferenceFieldHeaderDescriptor.ORDER_BY.name());
		if (orderBy == null) {
			return null;
		}

		return (OrderBy) this.orderConstraintResolver.resolve(
			new InlineReferenceDataLocator(new ManagedEntityTypePointer(currentEntitySchema.getName()), referenceSchema.getName()),
			ReferenceFieldHeaderDescriptor.ORDER_BY.name(),
			orderBy
		);
	}

	@Nullable
	private static AttributeContent resolveReferenceContentAttributes(
		@Nonnull SelectionSetAggregator referenceBodyFields,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final List<SelectedField> attributesFields = referenceBodyFields.getImmediateFields(AttributesProviderDescriptor.ATTRIBUTES.name());
		if (attributesFields.isEmpty()) {
			return null;
		}

		final String[] neededAttributes = SelectionSetAggregator.getImmediateFields(
			attributesFields.stream().map(SelectedField::getSelectionSet).toList()
		)
			.stream()
			.map(f -> referenceSchema.getAttributeByName(f.getName(), PROPERTY_NAME_NAMING_CONVENTION))
			.filter(Optional::isPresent)
			.map(Optional::get)
			.map(AttributeSchemaContract::getName)
			.collect(Collectors.toUnmodifiableSet())
			.toArray(String[]::new);

		return attributeContent(neededAttributes);
	}

	@Nullable
	private EntityFetch resolveReferenceContentEntityFetch(
		@Nonnull SelectionSetAggregator referenceBodyFields,
		@Nullable Locale desiredLocale,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final List<SelectedField> referencedEntityFields = referenceBodyFields.getImmediateFields(ReferenceDescriptor.REFERENCED_ENTITY.name());
		if (referencedEntityFields.isEmpty()) {
			// todo lho test
//			return null;
			return entityFetch();
		}

		final EntitySchemaContract referencedEntitySchema = referenceSchema.isReferencedEntityTypeManaged()
			? this.entitySchemaFetcher.apply(referenceSchema.getReferencedEntityType())
			: null;

		return resolveEntityFetch(
			SelectionSetAggregator.fromFields(referencedEntityFields),
			desiredLocale,
			referencedEntitySchema
		)
			// todo lho test
//			.orElse(null);
			.orElseGet(QueryConstraints::entityFetch);
	}

	@Nullable
	private EntityGroupFetch resolveReferenceContentEntityGroupFetch(
		@Nonnull SelectionSetAggregator referenceBodyFields,
		@Nullable Locale desiredLocale,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final List<SelectedField> referencedGroupFields = referenceBodyFields.getImmediateFields(ReferenceDescriptor.GROUP_ENTITY.name());
		if (referencedGroupFields.isEmpty()) {
			return null;
		}

		final EntitySchemaContract referencedEntitySchema = referenceSchema.isReferencedGroupTypeManaged() ?
			this.entitySchemaFetcher.apply(referenceSchema.getReferencedGroupType()) :
			null;

		return resolveGroupFetch(
			SelectionSetAggregator.fromFields(referencedGroupFields),
			desiredLocale,
			referencedEntitySchema
		)
			.orElse(null);
	}

	@Nullable
	private static ChunkingRequireConstraint resolveReferenceContentChunkingFromBasicField(
		@Nonnull SelectedField referenceField
	) {
		final Integer limitArgument = (Integer) referenceField.getArguments().get(ReferencesFieldHeaderDescriptor.LIMIT.name());
		if (limitArgument == null) {
			return null;
		}
		return strip(null, limitArgument);
	}

	@Nonnull
	private static ChunkingRequireConstraint resolveReferenceContentChunkingFromPageField(
		@Nonnull SelectedField referenceField,
		@Nonnull SelectionSetAggregator nestedFields
	) {
		if (isOnlyTotalCountIsDesiredForReferenceDataChunk(nestedFields)) {
			// performance optimization, we don't need actual references
			return page(1, 0);
		}
		final Integer pageNumber = (Integer) referenceField.getArguments().get(PaginatedListFieldHeaderDescriptor.NUMBER.name());
		final Integer pageSize = (Integer) referenceField.getArguments().get(PaginatedListFieldHeaderDescriptor.SIZE.name());
		return page(pageNumber, pageSize);
	}

	@Nonnull
	private static ChunkingRequireConstraint resolveReferenceContentChunkingFromStripField(
		@Nonnull SelectedField referenceField,
		@Nonnull SelectionSetAggregator nestedFields
	) {
		if (isOnlyTotalCountIsDesiredForReferenceDataChunk(nestedFields)) {
			// performance optimization, we don't need actual references
			return page(1, 0);
		}
		final Integer offset = (Integer) referenceField.getArguments().get(StripListFieldHeaderDescriptor.OFFSET.name());
		final Integer limit = (Integer) referenceField.getArguments().get(StripListFieldHeaderDescriptor.LIMIT.name());
		return strip(offset, limit);
	}

	private static boolean isOnlyTotalCountIsDesiredForReferenceDataChunk(@Nonnull SelectionSetAggregator referenceNestedFields) {
		final List<SelectedField> dataChuckInnerFields = referenceNestedFields.getImmediateFields();

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
