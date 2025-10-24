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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.filter.AttributeContains;
import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.FacetHaving;
import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.filter.PriceBetween;
import io.evitadb.api.query.filter.PriceInPriceLists;
import io.evitadb.api.query.filter.PriceValidIn;
import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.order.PriceNatural;
import io.evitadb.api.query.require.AssociatedDataContent;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.query.require.HierarchyContent;
import io.evitadb.api.query.require.HierarchyOfSelf;
import io.evitadb.api.query.require.PriceContent;
import io.evitadb.api.query.require.PriceHistogram;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.Versioned;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetStatistics;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaEditor.ReflectedReferenceSchemaBuilder;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.data.ComplexDataObjectConverter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Interface follows the <a href="https://en.wikipedia.org/wiki/Builder_pattern">builder pattern</a> allowing to alter
 * the data that are available on the read-only {@link EntitySchemaContract} interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface EntitySchemaEditor<S extends EntitySchemaEditor<S>> extends
		EntitySchemaContract,
		NamedSchemaWithDeprecationEditor<S>,
		AttributeProviderSchemaEditor<S, EntityAttributeSchemaContract, EntityAttributeSchemaEditor.EntityAttributeSchemaBuilder>,
		SortableAttributeCompoundSchemaProviderEditor<S, EntityAttributeSchemaContract, EntitySortableAttributeCompoundSchemaContract>
{

	/**
	 * Method allows injection of {@link CatalogSchemaContract accessor} that provides accurate global attribute
	 * definition. This method is required to be used only in situation when both entity schema and catalog schema
	 * are altered at the same time and entity schema editor needs to access the up-to-date information from the catalog
	 * schema that may not yet have been applied to the evitaDB.
	 */
	@Nonnull
	S cooperatingWith(@Nonnull Supplier<CatalogSchemaContract> catalogSupplier);

	/**
	 * Sets strict verification mode for entities of this type. All attributes, references, associated data and languages
	 * will be checked against the entity definition (schema) and validation errors will result in upsert refusal.
	 *
	 * This mode is recommended if you want to strictly control schema and define structure up-front.
	 */
	@Nonnull
	S verifySchemaStrictly();

	/**
	 * This is slightly relaxed mode of the schema evolution. All existing attributes, references, associated data and
	 * languages are checked against the entity definition (schema) but adding new attribute, reference, associated data
	 * or language may be allowed with default settings if you specify {@link EvolutionMode} for it.
	 */
	@Nonnull
	S verifySchemaButAllow(@Nonnull EvolutionMode... evolutionMode);

	/**
	 * This is lax mode of the schema evolution. All existing attributes, references, associated data and
	 * languages are checked against the entity definition (schema) but adding new attribute, reference, associated data
	 * or language may is allowed and added to the schema with the default settings.
	 */
	@Nonnull
	S verifySchemaButCreateOnTheFly();

	/**
	 * Specifies that entities of this type will have primary keys generated by Evita and not provided from outside.
	 */
	@Nonnull
	S withGeneratedPrimaryKey();

	/**
	 * Specifies that entities of this type will have primary keys provided from outside.
	 */
	@Nonnull
	S withoutGeneratedPrimaryKey();

	/**
	 * Enables hierarchy structure for this type of entity. Entities may have {@link Entity#getParent()}
	 * defined on them. That means that entity may refer to single parent entity and may be
	 * referred by multiple child entities. Hierarchy is always composed of entities of same type.
	 * Each entity must be part of at most single hierarchy (tree).
	 *
	 * Hierarchy can limit returned entities by using filtering constraints {@link HierarchyWithin}. It's also used for
	 * computation of extra data - such as {@link HierarchyContent}. It can also invert type of returned
	 * entities in case requirement {@link HierarchyOfSelf} is used.
	 *
	 * Method automatically enables hierarchy indexes for default scope ({@link Scope#LIVE)).
	 */
	@Nonnull
	default S withHierarchy() {
		return withHierarchyIndexedInScope(Scope.DEFAULT_SCOPE);
	}

	/**
	 * Enables hierarchy structure for this type of entity. Entities may have {@link Entity#getParent()}
	 * defined on them. That means that entity may refer to single parent entity and may be
	 * referred by multiple child entities. Hierarchy is always composed of entities of same type.
	 * Each entity must be part of at most single hierarchy (tree).
	 *
	 * Hierarchy can limit returned entities by using filtering constraints {@link HierarchyWithin}. It's also used for
	 * computation of extra data - such as {@link HierarchyContent}. It can also invert type of returned
	 * entities in case requirement {@link HierarchyOfSelf} is used.
	 *
	 * Enables hierarchy indexes for specified scopes. Entities can be filtered and extra information calculated
	 * by hierarchy structure in these scopes.
	 */
	@Nonnull
	S withHierarchyIndexedInScope(@Nonnull Scope... inScopes);

	/**
	 * Disables hierarchy structure for this type of entity. This is default setting for new entity types.
	 */
	@Nonnull
	S withoutHierarchy();

	/**
	 * Disables hierarchy indexes for specified scopes but leaves the hierarchical information available on the entity
	 * type itself. Entities can't be filtered and extra information calculated by hierarchy structure in these scopes
	 * anymore. If you want to remove hierarchy information completely use {@link #withoutHierarchy()} method.
	 */
	@Nonnull
	S withoutHierarchyIndexedInScope(@Nonnull Scope... inScopes);

	/**
	 * Enables price related data for this type of entity. Entities may have {@link Entity#getPrices()} defined on them.
	 *
	 * Prices are specific to a very few entity types, but because correct price computation is very complex in e-commerce
	 * systems and highly affects performance of the entities filtering and sorting, they deserve first class support
	 * in entity model. It is pretty common in B2B systems single product has assigned dozens of prices for the different
	 * customers.
	 *
	 * Specifying prices on entity allows usage of {@link PriceValidIn}, {@link PriceBetween}, {@link QueryPriceMode}
	 * and {@link PriceInPriceLists} filtering constraints and also {@link PriceNatural}, ordering of the entities.
	 * Additional requirements {@link PriceHistogram}, {@link PriceContent} can be used in query as well. If the price
	 * information is not indexed, it is still available on the entity itself (i.e. entity can define its price), but it
	 * is not possible to work with the price information in any other way (calculating price histogram, filtering,
	 * sorting by price, etc.).
	 *
	 * Method automatically enables price indexes for default scope ({@link Scope#LIVE)).
	 *
	 * This method variant expects that prices may have up to two decimal places.
	 */
	@Nonnull
	default S withPrice() {
		return withPriceIndexedInScope(Scope.DEFAULT_SCOPE);
	}

	/**
	 * Enables price related data for this type of entity. Entities may have {@link Entity#getPrices()} defined on them.
	 *
	 * Prices are specific to a very few entity types, but because correct price computation is very complex in e-commerce
	 * systems and highly affects performance of the entities filtering and sorting, they deserve first class support
	 * in entity model. It is pretty common in B2B systems single product has assigned dozens of prices for the different
	 * customers.
	 *
	 * Specifying prices on entity allows usage of {@link PriceValidIn}, {@link PriceBetween}, {@link QueryPriceMode}
	 * and {@link PriceInPriceLists} filtering constraints and also {@link PriceNatural}, ordering of the entities.
	 * Additional requirements {@link PriceHistogram}, {@link PriceContent} can be used in query as well. If the price
	 * information is not indexed, it is still available on the entity itself (i.e. entity can define its price), but it
	 * is not possible to work with the price information in any other way (calculating price histogram, filtering,
	 * sorting by price, etc.).
	 *
	 * Method enables price indexes for all specified scopes.
	 *
	 * This method will use two decimal places for indexing price information in the indexes. The original price
	 * information in the entity data will remain precise in the form of {@link BigDecimal} with "infinite" decimal
	 * places.
	 */
	@Nonnull
	S withPriceIndexedInScope(@Nonnull Scope... inScopes);

	/**
	 * Enables price related data for this type of entity. Entities may have {@link Entity#getPrices()} defined on them.
	 *
	 * Prices are specific to a very few entity types, but because correct price computation is very complex in e-commerce
	 * systems and highly affects performance of the entities filtering and sorting, they deserve first class support
	 * in entity model. It is pretty common in B2B systems single product has assigned dozens of prices for the different
	 * customers.
	 *
	 * Specifying prices on entity allows usage of {@link PriceValidIn}, {@link PriceBetween}, {@link QueryPriceMode}
	 * and {@link PriceInPriceLists} filtering constraints and also {@link PriceNatural}, ordering of the entities.
	 * Additional requirements {@link PriceHistogram}, {@link PriceContent} can be used in query as well. If the price
	 * information is not indexed, it is still available on the entity itself (i.e. entity can define its price), but it
	 * is not possible to work with the price information in any other way (calculating price histogram, filtering,
	 * sorting by price, etc.).
	 *
	 * Method automatically enables price indexes for default scope ({@link Scope#LIVE)).
	 *
	 * This method will use specified number of decimal places for indexing price information in the indexes.
	 * The original price information in the entity data will remain precise in the form of {@link BigDecimal} with
	 * "infinite" decimal places.
	 */
	@Nonnull
	default S withPrice(int indexedDecimalPlaces) {
		return withPriceIndexedInScope(indexedDecimalPlaces, Scope.DEFAULT_SCOPE);
	}

	/**
	 * Enables price related data for this type of entity. Entities may have {@link Entity#getPrices()} defined on them.
	 *
	 * Prices are specific to a very few entity types, but because correct price computation is very complex in e-commerce
	 * systems and highly affects performance of the entities filtering and sorting, they deserve first class support
	 * in entity model. It is pretty common in B2B systems single product has assigned dozens of prices for the different
	 * customers.
	 *
	 * Specifying prices on entity allows usage of {@link PriceValidIn}, {@link PriceBetween}, {@link QueryPriceMode}
	 * and {@link PriceInPriceLists} filtering constraints and also {@link PriceNatural}, ordering of the entities.
	 * Additional requirements {@link PriceHistogram}, {@link PriceContent} can be used in query as well. If the price
	 * information is not indexed, it is still available on the entity itself (i.e. entity can define its price), but it
	 * is not possible to work with the price information in any other way (calculating price histogram, filtering,
	 * sorting by price, etc.).
	 *
	 * Method enables price indexes for all specified scopes.
	 *
	 * This method will use specified number of decimal places for indexing price information in the indexes.
	 * The original price information in the entity data will remain precise in the form of {@link BigDecimal} with
	 * "infinite" decimal places.
	 */
	@Nonnull
	S withPriceIndexedInScope(int indexedDecimalPlaces, @Nonnull Scope... inScopes);

	/**
	 * Enables price related data for this type of entity. Entities may have {@link Entity#getPrices()} defined on them.
	 *
	 * Prices are specific to a very few entity types, but because correct price computation is very complex in e-commerce
	 * systems and highly affects performance of the entities filtering and sorting, they deserve first class support
	 * in entity model. It is pretty common in B2B systems single product has assigned dozens of prices for the different
	 * customers.
	 *
	 * Specifying prices on entity allows usage of {@link PriceValidIn}, {@link PriceBetween}, {@link QueryPriceMode}
	 * and {@link PriceInPriceLists} filtering constraints and also {@link PriceNatural}, ordering of the entities.
	 * Additional requirements {@link PriceHistogram}, {@link PriceContent} can be used in query as well. If the price
	 * information is not indexed, it is still available on the entity itself (i.e. entity can define its price), but it
	 * is not possible to work with the price information in any other way (calculating price histogram, filtering,
	 * sorting by price, etc.).
	 *
	 * Method automatically enables price indexes for default scope ({@link Scope#LIVE)).
	 *
	 * This method will use two decimal places for indexing price information in the indexes. The original price
	 * information in the entity data will remain precise in the form of {@link BigDecimal} with "infinite" decimal
	 * places.
	 *
	 * Method also specifies a limited set of allowed currencies for the prices. If you want to allow all currencies
	 * specify {@link EvolutionMode#ADDING_CURRENCIES} in the {@link #verifySchemaButAllow(EvolutionMode...)} method.
	 * This will add currencies to the schema automatically as they are encountered in the data.
	 */
	@Nonnull
	default S withPriceInCurrency(@Nonnull Currency... currency) {
		return withIndexedPriceInCurrency(currency, Scope.DEFAULT_SCOPE);
	}

	/**
	 * Enables price related data for this type of entity. Entities may have {@link Entity#getPrices()} defined on them.
	 *
	 * Prices are specific to a very few entity types, but because correct price computation is very complex in e-commerce
	 * systems and highly affects performance of the entities filtering and sorting, they deserve first class support
	 * in entity model. It is pretty common in B2B systems single product has assigned dozens of prices for the different
	 * customers.
	 *
	 * Specifying prices on entity allows usage of {@link PriceValidIn}, {@link PriceBetween}, {@link QueryPriceMode}
	 * and {@link PriceInPriceLists} filtering constraints and also {@link PriceNatural}, ordering of the entities.
	 * Additional requirements {@link PriceHistogram}, {@link PriceContent} can be used in query as well. If the price
	 * information is not indexed, it is still available on the entity itself (i.e. entity can define its price), but it
	 * is not possible to work with the price information in any other way (calculating price histogram, filtering,
	 * sorting by price, etc.).
	 *
	 * Method automatically enables price indexes in a particular set of scopes.
	 *
	 * This method will use two decimal places for indexing price information in the indexes. The original price
	 * information in the entity data will remain precise in the form of {@link BigDecimal} with "infinite" decimal
	 * places.
	 *
	 * Method also specifies a limited set of allowed currencies for the prices. If you want to allow all currencies
	 * specify {@link EvolutionMode#ADDING_CURRENCIES} in the {@link #verifySchemaButAllow(EvolutionMode...)} method.
	 * This will add currencies to the schema automatically as they are encountered in the data.
	 */
	@Nonnull
	S withIndexedPriceInCurrency(@Nonnull Currency[] currency, @Nonnull Scope... inScopes);

	/**
	 * Enables price related data for this type of entity. Entities may have {@link Entity#getPrices()} defined on them.
	 *
	 * Prices are specific to a very few entity types, but because correct price computation is very complex in e-commerce
	 * systems and highly affects performance of the entities filtering and sorting, they deserve first class support
	 * in entity model. It is pretty common in B2B systems single product has assigned dozens of prices for the different
	 * customers.
	 *
	 * Specifying prices on entity allows usage of {@link PriceValidIn}, {@link PriceBetween}, {@link QueryPriceMode}
	 * and {@link PriceInPriceLists} filtering constraints and also {@link PriceNatural}, ordering of the entities.
	 * Additional requirements {@link PriceHistogram}, {@link PriceContent} can be used in query as well. If the price
	 * information is not indexed, it is still available on the entity itself (i.e. entity can define its price), but it
	 * is not possible to work with the price information in any other way (calculating price histogram, filtering,
	 * sorting by price, etc.).
	 *
	 * Method automatically enables price indexes for default scope ({@link Scope#LIVE)).
	 *
	 * This method will use specified number of decimal places for indexing price information in the indexes.
	 * The original price information in the entity data will remain precise in the form of {@link BigDecimal} with
	 * "infinite" decimal places.
	 *
	 * Method also specifies a limited set of allowed currencies for the prices. If you want to allow all currencies
	 * specify {@link EvolutionMode#ADDING_CURRENCIES} in the {@link #verifySchemaButAllow(EvolutionMode...)} method.
	 * This will add currencies to the schema automatically as they are encountered in the data.
	 */
	@Nonnull
	default S withPriceInCurrency(int indexedPricePlaces, @Nonnull Currency... currency) {
		return withPriceInCurrencyIndexedInScope(indexedPricePlaces, currency, Scope.DEFAULT_SCOPE);
	}

	/**
	 * Enables price related data for this type of entity. Entities may have {@link Entity#getPrices()} defined on them.
	 *
	 * Prices are specific to a very few entity types, but because correct price computation is very complex in e-commerce
	 * systems and highly affects performance of the entities filtering and sorting, they deserve first class support
	 * in entity model. It is pretty common in B2B systems single product has assigned dozens of prices for the different
	 * customers.
	 *
	 * Specifying prices on entity allows usage of {@link PriceValidIn}, {@link PriceBetween}, {@link QueryPriceMode}
	 * and {@link PriceInPriceLists} filtering constraints and also {@link PriceNatural}, ordering of the entities.
	 * Additional requirements {@link PriceHistogram}, {@link PriceContent} can be used in query as well. If the price
	 * information is not indexed, it is still available on the entity itself (i.e. entity can define its price), but it
	 * is not possible to work with the price information in any other way (calculating price histogram, filtering,
	 * sorting by price, etc.).
	 *
	 * Method automatically enables price indexes in a particular set of scopes.
	 *
	 * This method will use specified number of decimal places for indexing price information in the indexes.
	 * The original price information in the entity data will remain precise in the form of {@link BigDecimal} with
	 * "infinite" decimal places.
	 *
	 * Method also specifies a limited set of allowed currencies for the prices. If you want to allow all currencies
	 * specify {@link EvolutionMode#ADDING_CURRENCIES} in the {@link #verifySchemaButAllow(EvolutionMode...)} method.
	 * This will add currencies to the schema automatically as they are encountered in the data.
	 */
	@Nonnull
	S withPriceInCurrencyIndexedInScope(int indexedPricePlaces, @Nonnull Currency[] currency, @Nonnull Scope... inScopes);

	/**
	 * Disables price related data for this type of entity. This is default setting for new entity types.
	 */
	@Nonnull
	S withoutPrice();

	/**
	 * Disables price indexes for specified scopes but leaves the price information available on the entity
	 * type itself. Entities can't be filtered and extra information calculated by price structure in these scopes
	 * anymore. If you want to remove price information completely use {@link #withoutPrice()} method.
	 */
	@Nonnull
	S withoutPriceIndexedInScope(@Nonnull Scope... inScopes);

	/**
	 * Disables set of allowed currencies for this entity type but leaves the price information available on the entity
	 * type itself. Entities can't be filtered and extra information calculated by price structure in these scopes
	 * anymore. If you want to remove price information completely use {@link #withoutPrice()} method.
	 *
	 * Supported currencies can be removed only when there is no single price in specified currency present.
	 */
	@Nonnull
	S withoutPriceInCurrency(@Nonnull Currency currency);

	/**
	 * Adds specific {@link Locale} to the set of possible locales (languages) that can be used when specifying localized
	 * {@link AttributeSchemaContract} or {@link AssociatedDataSchemaContract}.
	 *
	 * Allows using {@link EntityLocaleEquals} filtering query in query.
	 */
	@Nonnull
	S withLocale(@Nonnull Locale... locale);

	/**
	 * Removes specific {@link Locale} from the set of possible locales (languages) that can be used when specifying
	 * localized {@link AttributeSchemaContract} or {@link AssociatedDataSchemaContract}.
	 *
	 * Supported locale can be removed only when there is no attribute or associated data in specified locale present.
	 */
	@Nonnull
	S withoutLocale(@Nonnull Locale locale);

	/**
	 * Adds new {@link AttributeSchemaContract} to the set of allowed attributes of the entity or updates existing.
	 *
	 * If you update existing associated data type all data must be specified again, nothing is preserved.
	 *
	 * Entity (global) attributes allows defining set of data that are fetched in bulk along with the entity body.
	 * Attributes may be indexed for fast filtering ({@link AttributeSchemaContract#isFilterable()}) or can be used to sort along
	 * ({@link AttributeSchemaContract#isSortable()}). Attributes are not automatically indexed in order not to waste precious
	 * memory space for data that will never be used in search queries.
	 *
	 * Filtering in attributes is executed by using constraints like {@link io.evitadb.api.query.filter.And},
	 * {@link io.evitadb.api.query.filter.Not}, {@link AttributeEquals}, {@link AttributeContains}
	 * and many others. Sorting can be achieved with {@link AttributeNatural} or others.
	 *
	 * Attributes are not recommended for bigger data as they are all loaded at once when {@link AttributeContent}
	 * requirement is used. Large data that are occasionally used store in {@link io.evitadb.api.requestResponse.data.structure.AssociatedData}.
	 */
	@Nonnull
	S withGlobalAttribute(@Nonnull String attributeName);

	/**
	 * Adds new {@link AssociatedDataSchemaContract} to the set of allowed associated data of the entity or updates existing.
	 *
	 * If you update existing associated data type all data must be specified again, nothing is preserved.
	 *
	 * Associated data carry additional data entries that are never used for filtering / sorting but may be needed to be fetched
	 * along with entity in order to present data to the target consumer (i.e. user / API / bot). Associated data may be stored
	 * in slower storage and may contain wide range of data types - from small ones (i.e. numbers, strings, dates) up to large
	 * binary arrays representing entire files (i.e. pictures, documents).
	 *
	 * The search query must contain specific {@link AssociatedDataContent} requirement in order
	 * associated data are fetched along with the entity. Associated data are stored and fetched separately by their name.
	 *
	 * @param ofType type of the entity. Must be one of {@link EvitaDataTypes#getSupportedDataTypes()}
	 *               types or may represent complex type - which is POJO that can be automatically
	 *               ({@link ComplexDataObjectConverter}) converted to the set of basic types.
	 */
	@Nonnull
	S withAssociatedData(@Nonnull String dataName, @Nonnull Class<? extends Serializable> ofType);

	/**
	 * Adds new {@link AssociatedDataSchemaContract} to the set of allowed associated data of the entity or updates existing.
	 *
	 * If you update existing associated data type all data must be specified again, nothing is preserved.
	 *
	 * Associated data carry additional data entries that are never used for filtering / sorting but may be needed to be fetched
	 * along with entity in order to present data to the target consumer (i.e. user / API / bot). Associated data may be stored
	 * in slower storage and may contain wide range of data types - from small ones (i.e. numbers, strings, dates) up to large
	 * binary arrays representing entire files (i.e. pictures, documents).
	 *
	 * The search query must contain specific {@link AssociatedDataContent} requirement in order
	 * associated data are fetched along with the entity. Associated data are stored and fetched separately by their name.
	 *
	 * @param ofType  type of the entity. Must be one of {@link EvitaDataTypes#getSupportedDataTypes()}
	 *                types or may represent complex type - which is POJO that can be automatically
	 *                ({@link ComplexDataObjectConverter}) converted to the set of basic types.
	 * @param whichIs lambda that allows to specify attributes of the attribute itself
	 */
	@Nonnull
	S withAssociatedData(@Nonnull String dataName, @Nonnull Class<? extends Serializable> ofType, @Nullable Consumer<AssociatedDataSchemaEditor> whichIs);

	/**
	 * Removes specific {@link AssociatedDataSchemaContract} from the set of allowed associated data of the entity.
	 */
	@Nonnull
	S withoutAssociatedData(@Nonnull String dataName);

	/**
	 * Adds new {@link ReferenceSchemaContract} to the set of allowed references of the entity or updates existing.
	 *
	 * If you update existing reference type - existing {@link ReferenceSchemaContract#getReferencedGroupType()} and {@link ReferenceSchemaContract#getAttributes()}
	 * are preserved unless you make changes to them.
	 *
	 * The references refer to other entities (of same or different entity type).
	 * Allows entity filtering (but not sorting) of the entities by using {@link FacetHaving} query
	 * and statistics computation if when {@link FacetStatistics} requirement is used. Reference
	 * is uniquely represented by int positive number (max. 2<sup>63</sup>-1) and {@link Serializable} entity type and can be
	 * part of multiple reference groups, that are also represented by int and {@link Serializable} entity type.
	 *
	 * Reference id in one entity is unique and belongs to single reference group id. Among multiple entities reference may be part
	 * of different reference groups. Referenced entity type may represent type of another Evita entity or may refer
	 * to anything unknown to Evita that posses unique int key and is maintained by external systems (fe. tag assignment,
	 * group assignment, category assignment, stock assignment and so on). Not all these data needs to be present in
	 * Evita.
	 *
	 * References may carry additional key-value data linked to this entity relation (fe. item count present on certain stock).
	 */
	@Nonnull
	S withReferenceTo(@Nonnull String name, @Nonnull String externalEntityType, @Nonnull Cardinality cardinality);

	/**
	 * Adds new {@link ReferenceSchemaContract} to the set of allowed references of the entity or updates existing.
	 *
	 * If you update existing reference type - existing {@link ReferenceSchemaContract#getReferencedGroupType()} and {@link ReferenceSchemaContract#getAttributes()}
	 * are preserved unless you make changes to them.
	 *
	 * The references refer to other entities (of same or different entity type).
	 * Allows entity filtering (but not sorting) of the entities by using {@link FacetHaving} query
	 * and statistics computation if when {@link FacetStatistics} requirement is used. Reference
	 * is uniquely represented by int positive number (max. 2<sup>63</sup>-1) and {@link Serializable} entity type and can be
	 * part of multiple reference groups, that are also represented by int and {@link Serializable} entity type.
	 *
	 * Reference id in one entity is unique and belongs to single reference group id. Among multiple entities reference may be part
	 * of different reference groups. Referenced entity type may represent type of another Evita entity or may refer
	 * to anything unknown to Evita that posses unique int key and is maintained by external systems (fe. tag assignment,
	 * group assignment, category assignment, stock assignment and so on). Not all these data needs to be present in
	 * Evita.
	 *
	 * References may carry additional key-value data linked to this entity relation (fe. item count present on certain stock).
	 *
	 * @param whichIs lambda that allows to define reference specifics
	 */
	@Nonnull
	S withReferenceTo(
		@Nonnull String name,
		@Nonnull String externalEntityType,
		@Nonnull Cardinality cardinality,
		@Nullable Consumer<ReferenceSchemaEditor.ReferenceSchemaBuilder> whichIs
	);

	/**
	 * Adds new {@link ReferenceSchemaContract} to the set of allowed references of the entity or updates existing.
	 * {@link Reference#getReferenceName()} ()} will represent {@link Entity#getPrimaryKey()} of Evita managed entity of this
	 * {@link EntitySchemaContract#getName()}.
	 *
	 * If you update existing reference type - existing {@link ReferenceSchemaContract#getReferencedGroupType()} and {@link ReferenceSchemaContract#getAttributes()}
	 * are preserved unless you make changes to them.
	 *
	 * The references refer to other entities (of same or different entity type).
	 * Allows entity filtering (but not sorting) of the entities by using {@link FacetHaving} query
	 * and statistics computation if when {@link FacetStatistics} requirement is used. Reference
	 * is uniquely represented by int positive number (max. 2<sup>63</sup>-1) and {@link Serializable} entity type and can be
	 * part of multiple reference groups, that are also represented by int and {@link Serializable} entity type.
	 *
	 * Reference id in one entity is unique and belongs to single reference group id. Among multiple entities reference may be part
	 * of different reference groups. Referenced entity type may represent type of another Evita entity or may refer
	 * to anything unknown to Evita that posses unique int key and is maintained by external systems (fe. tag assignment,
	 * group assignment, category assignment, stock assignment and so on). Not all these data needs to be present in
	 * Evita.
	 *
	 * References may carry additional key-value data linked to this entity relation (fe. item count present on certain stock).
	 */
	@Nonnull
	S withReferenceToEntity(@Nonnull String name, @Nonnull String entityType, @Nonnull Cardinality cardinality);

	/**
	 * Adds new {@link ReferenceSchemaContract} to the set of allowed references of the entity or updates existing.
	 * {@link Reference#getReferenceName()} will represent {@link Entity#getPrimaryKey()} of Evita managed entity of this
	 * {@link EntitySchemaContract#getName()}.
	 *
	 * If you update existing reference type - existing {@link ReferenceSchemaContract#getReferencedGroupType()} and {@link ReferenceSchemaContract#getAttributes()}
	 * are preserved unless you make changes to them.
	 *
	 * The references refer to other entities (of same or different entity type).
	 * Allows entity filtering (but not sorting) of the entities by using {@link FacetHaving} query
	 * and statistics computation if when {@link FacetStatistics} requirement is used. Reference
	 * is uniquely represented by int positive number (max. 2<sup>63</sup>-1) and {@link Serializable} entity type and can be
	 * part of multiple reference groups, that are also represented by int and {@link Serializable} entity type.
	 *
	 * Reference id in one entity is unique and belongs to single reference group id. Among multiple entities reference may be part
	 * of different reference groups. Referenced entity type may represent type of another Evita entity or may refer
	 * to anything unknown to Evita that posses unique int key and is maintained by external systems (fe. tag assignment,
	 * group assignment, category assignment, stock assignment and so on). Not all these data needs to be present in
	 * Evita.
	 *
	 * References may carry additional key-value data linked to this entity relation (fe. item count present on certain stock).
	 *
	 * @param name name of the reference
	 * @param entityType name of the target entity this reference relates to (either managed by evitaDB or not)
	 * @param cardinality the cardinality of references for a given entity
	 * @param whichIs lambda that allows to define reference specifics
	 */
	@Nonnull
	S withReferenceToEntity(
		@Nonnull String name,
		@Nonnull String entityType,
		@Nonnull Cardinality cardinality,
		@Nullable Consumer<ReferenceSchemaEditor.ReferenceSchemaBuilder> whichIs
	);

	/**
	 * Adds new {@link ReflectedReferenceSchemaContract} to the set of allowed references of the entity or updates existing.
	 * {@link Reference#getReferenceName()} will represent {@link Entity#getPrimaryKey()} of Evita managed entity of this
	 * {@link EntitySchemaContract#getName()}.
	 *
	 * Reflected reference sets up a bi-directional relation with target entity by selecting appropriate existing
	 * uni-directional reference in the target entity schema. By default the reflected reference inherits all the reference
	 * attributes of the original relation, cardinality, description and other properties. You can redefine most of them
	 * using {@link ReflectedReferenceSchemaEditor.ReflectedReferenceSchemaBuilder}. You may also define new unique
	 * reference attributes which will be visible only from this entity point of view.
	 *
	 * References may carry additional key-value data linked to this entity relation (fe. item count present on certain stock).
	 *
	 * @param name name of the reference
	 * @param entityType name of the target entity this reference relates to
	 * @param reflectedReferenceName name of the reference in the target entity, this reference should reflect
	 */
	@Nonnull
	S withReflectedReferenceToEntity(
		@Nonnull String name,
		@Nonnull String entityType,
		@Nonnull String reflectedReferenceName
	);

	/**
	 * Adds new {@link ReflectedReferenceSchemaContract} to the set of allowed references of the entity or updates existing.
	 * {@link Reference#getReferenceName()} will represent {@link Entity#getPrimaryKey()} of Evita managed entity of this
	 * {@link EntitySchemaContract#getName()}.
	 *
	 * Reflected reference sets up a bi-directional relation with target entity by selecting appropriate existing
	 * uni-directional reference in the target entity schema. By default the reflected reference inherits all the reference
	 * attributes of the original relation, cardinality, description and other properties. You can redefine most of them
	 * using {@link ReflectedReferenceSchemaEditor.ReflectedReferenceSchemaBuilder}. You may also define new unique
	 * reference attributes which will be visible only from this entity point of view.
	 *
	 * References may carry additional key-value data linked to this entity relation (fe. item count present on certain stock).
	 *
	 * @param referenceName          name of the reference
	 * @param entityType             name of the target entity this reference relates to
	 * @param reflectedReferenceName name of the reference in the target entity, this reference should reflect
	 * @param whichIs                lambda that allows to define reference specifics
	 */
	@Nonnull
	S withReflectedReferenceToEntity(
		@Nonnull String referenceName,
		@Nonnull String entityType,
		@Nonnull String reflectedReferenceName,
		@Nullable Consumer<ReflectedReferenceSchemaBuilder> whichIs
	);

	/**
	 * Removes specific {@link ReferenceSchemaContract} of {@link ReflectedReferenceSchemaContract} from the set of
	 * allowed references of the entity.
	 */
	@Nonnull
	S withoutReferenceTo(@Nonnull String name);

	/**
	 * Interface that simply combines {@link EntitySchemaEditor} and {@link EntitySchemaContract} entity contracts
	 * together. Builder produces either {@link ModifyEntitySchemaMutation} that describes all changes to be made on
	 * the {@link EntitySchemaContract} instance to get it to "up-to-date" state or can provide already built
	 * {@link EntitySchemaContract} that may not represent globally "up-to-date" state because it is based on
	 * the version of the entity known when builder was created.
	 *
	 * Mutation allows Evita to perform surgical updates on the latest version of the {@link EntitySchemaContract}
	 * object that is in the database at the time update request arrives.
	 */
	@NotThreadSafe
	interface EntitySchemaBuilder
		extends EntitySchemaEditor<EntitySchemaEditor.EntitySchemaBuilder> {

		/**
		 * Returns {@link ModifyEntitySchemaMutation} instance that contains array of {@link EntitySchemaMutation}
		 * describing what changes occurred in the builder, and which should be applied on the existing
		 * {@link EntitySchemaContract} version.
		 *
		 * Each mutation increases {@link Versioned#version()} of the modified object and allows to detect race
		 * conditions based on "optimistic locking" mechanism in very granular way.
		 */
		@Nonnull
		Optional<ModifyEntitySchemaMutation> toMutation();

		/**
		 * Returns built "local up-to-date" {@link Entity} instance that may not represent globally "up-to-date" state
		 * because it is based on the version of the entity known when builder was created.
		 *
		 * This method is particularly useful for tests.
		 */
		@Nonnull
		EntitySchemaContract toInstance();

		/**
		 * The method is a shortcut for calling {@link EvitaSessionContract#updateEntitySchema(ModifyEntitySchemaMutation)}
		 * the other way around. Method simplifies the statements, makes them more readable and in combination with
		 * builder pattern usage it's also easier to use.
		 *
		 * @param session to use for updating the modified (built) schema
		 */
		default int updateVia(@Nonnull EvitaSessionContract session) {
			return session.updateEntitySchema(this);
		}

		/**
		 * The method is a shortcut for calling {@link EvitaSessionContract#updateAndFetchEntitySchema(ModifyEntitySchemaMutation)}
		 * the other way around. Method simplifies the statements, makes them more readable and in combination with
		 * builder pattern usage it's also easier to use.
		 *
		 * @param session to use for updating the modified (built) schema
		 */
		@Nonnull
		default SealedEntitySchema updateAndFetchVia(@Nonnull EvitaSessionContract session) {
			return session.updateAndFetchEntitySchema(this);
		}

	}

}
