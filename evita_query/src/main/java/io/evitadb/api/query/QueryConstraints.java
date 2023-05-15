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

package io.evitadb.api.query;

import io.evitadb.api.query.filter.*;
import io.evitadb.api.query.head.Collection;
import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.order.EntityProperty;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.OrderGroupBy;
import io.evitadb.api.query.order.PriceNatural;
import io.evitadb.api.query.order.Random;
import io.evitadb.api.query.order.ReferenceProperty;
import io.evitadb.api.query.require.*;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static java.util.Optional.ofNullable;

/**
 * Factory class for creating {@link Constraint} instances.
 * This factory class is handy so that developer doesn't need to remember all possible query variants and could
 * easily construct queries similar to textual format of the EQL.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface QueryConstraints {

	/*
		HEADING
	 */

	/**
	 * Each query must specify collection. This mandatory {@link String} query controls what collection
	 * the query will be applied on.
	 *
	 * Sample of the header is:
	 *
	 * ```
	 * entities("category")
	 * ```
	 */
	@Nonnull
	static Collection collection(@Nonnull String entityType) {
		return new Collection(entityType);
	}

	/*
		FILTERING
	 */

	/**
	 * This `filterBy` is container for filtering constraints. It is mandatory container when any filtering is to be used.
	 * This container allows only one children container with the filtering condition.
	 *
	 * Example:
	 *
	 * ```
	 * filterBy(
	 * and(
	 * isNotNull("code"),
	 * or(
	 * equals("code", "ABCD"),
	 * startsWith("title", "Knife")
	 * )
	 * )
	 * )
	 * ```
	 */
	@Nullable
	static FilterBy filterBy(@Nullable FilterConstraint... constraint) {
		return constraint == null ? null : new FilterBy(constraint);
	}

	/**
	 * The container encapsulating filter constraint limiting the facet groups returned in facet summary.
	 * TOBEDONE JNO - alter documentation
	 */
	@Nullable
	static FilterGroupBy filterGroupBy(@Nullable FilterConstraint constraint) {
		return constraint == null ? null : new FilterGroupBy(constraint);
	}

	/**
	 * This `and` is container query that contains two or more inner constraints which output is combined by
	 * <a href="https://en.wikipedia.org/wiki/Logical_conjunction">logical AND</a>.
	 *
	 * Example:
	 *
	 * ```
	 * and(
	 * isTrue("visible"),
	 * validInTime(2020-07-30T07:28:13+00:00)
	 * )
	 * ```
	 */
	@Nullable
	static And and(@Nullable FilterConstraint... constraints) {
		if (constraints == null) {
			return null;
		}
		return new And(constraints);
	}

	/**
	 * This `or` is container query that contains two or more inner constraints which output is combined by
	 * <a href="https://en.wikipedia.org/wiki/Logical_disjunction">logical OR</a>.
	 *
	 * Example:
	 *
	 * ```
	 * or(
	 * isTrue("visible"),
	 * greaterThan("price", 20)
	 * )
	 * ```
	 */
	@Nullable
	static Or or(@Nullable FilterConstraint... constraints) {
		if (constraints == null) {
			return null;
		}
		return new Or(constraints);
	}

	/**
	 * This `not` is container query that contains single inner query which output is negated. Behaves as
	 * <a href="https://en.wikipedia.org/wiki/Negation">logical NOT</a>.
	 *
	 * Example:
	 *
	 * ```
	 * not(
	 * primaryKey(1,2,3)
	 * )
	 * ```
	 */
	@Nullable
	static Not not(@Nullable FilterConstraint constraint) {
		return constraint == null ? null : new Not(constraint);
	}

	/**
	 * This `referenceAttribute` container is filtering query that filters returned entities by their reference
	 * attributes that are examined whether they fulfill all the inner conditions.
	 *
	 * Example:
	 *
	 * ```
	 * referenceHavingAttribute(
	 * "CATEGORY",
	 * eq("code", "KITCHENWARE")
	 * )
	 * ```
	 *
	 * or
	 *
	 * ```
	 * referenceHavingAttribute(
	 * "CATEGORY",
	 * and(
	 * isTrue("visible"),
	 *
	 * )
	 * )
	 * ```
	 */
	@Nullable
	static ReferenceHaving referenceHaving(@Nonnull String referenceName, @Nullable FilterConstraint... constraint) {
		return ArrayUtils.isEmpty(constraint) ? null : new ReferenceHaving(referenceName, constraint);
	}

	/**
	 * This `userFilter` is a container query that could contain any constraints
	 * except [priceInPriceLists](#price-in-price-lists),
	 * [language](#language), [priceInCurrency](#price-in-currency), [priceValidInTime](#price-valid-in-time),
	 * [with hierarchy](#within-hierarchy).
	 *
	 * These constraints should react to the settings defined by the end user and must be isolated from the base filter so
	 * that [facetSummary](#facet-summary) logic can distinguish base filtering query for a facet summary computation.
	 * Facet summary must define so-called baseline count - i.e. count of the entities that match system constraints but no
	 * optional constraints defined by the user has been applied yet on them. This baseline is also used
	 * for [facet statistics](#facet-statistics) computation.
	 *
	 * This query might be used even without [facetSummary](#facet-summary) - when the result facet counts are not
	 * required but still we want the facets use for filtering.
	 *
	 * Only single `userFilter` query can be used in the query.
	 *
	 * Example:
	 *
	 * ```
	 * userFilter(
	 * greaterThanEq("memory", 8),
	 * priceBetween(150.25, 220.0),
	 * facet("parameter", 4, 15)
	 * )
	 * ```
	 *
	 * Even more complex queries are supported (although it is hard to make up some real life example for such):
	 *
	 * ```
	 * filterBy(
	 * and(
	 * or(
	 * referenceHavingAttribute("CATEGORY", eq(code, "abc")),
	 * referenceHavingAttribute("STOCK", eq(market, "asia")),
	 * ),
	 * eq(visibility, true),
	 * userFilter(
	 * or(
	 * and(
	 * greaterThanEq("memory", 8),
	 * priceBetween(150.25, 220.0)
	 * ),
	 * and(
	 * greaterThanEq("memory", 16),
	 * priceBetween(800.0, 1600.0)
	 * ),
	 * ),
	 * facet("parameter", 4, 15)
	 * )
	 * )
	 * ),
	 * require(
	 * facetGroupDisjunction("parameterType", 4),
	 * negatedFacets("parameterType", 8),
	 * )
	 *
	 * ```
	 *
	 * User filter envelopes the part of the query that is affected by user selection and that is optional. All constraints
	 * outside user filter are considered mandatory and must never be altered by [facet summary](#facet-summary) computational
	 * logic.
	 *
	 * Base count of the facets are computed for query having `userFilter` container contents stripped off. The "what-if"
	 * counts requested by [impact argument](#facet-summary) are computed from the query including `userFilter` creating
	 * multiple sub-queries checking the result for each additional facet selection.
	 *
	 * [Facet](#facet) filtering constraints must be direct children of the `userFilter` container. Their relationship is by
	 * default as follows: facets of the same type within same group are combined by conjunction (OR), facets of different
	 * types / groups are combined by disjunction (AND). This default behaviour can be controlled exactly by using any of
	 * following require constraints:
	 *
	 * - [facet groups conjunction](#facet-groups-conjunction) - changes relationship between facets in the same group
	 * - [facet groups disjunction](#facet-groups-disjunction) - changes relationship between facet groups
	 *
	 * All constraints placed directly inside `userFilter` are combined with by conjunction (AND). Other than `facet` filtering
	 * constraints (as seen in example) may represent user conditions in non-faceted inputs, such as interval inputs.
	 *
	 * ***Note:** this query might be a subject to change and affects advanced searching queries such as exclusion facet
	 * groups (i.e. facet in group are not represented as multi-select/checkboxes but as exlusive select/radio) or conditional
	 * filters (which can be used to apply a certain filter only if it would produce non-empty result, this is good for
	 * "sticky" filters).*
	 */
	@Nullable
	static UserFilter userFilter(@Nullable FilterConstraint... constraints) {
		if (constraints == null) {
			return null;
		}
		return new UserFilter(constraints);
	}

	/**
	 * This `between` is query that compares value of the attribute with name passed in first argument with the value passed
	 * in the second argument and value passed in third argument. First argument must be {@link String},
	 * second and third argument may be any of {@link Comparable} type.
	 * Type of the attribute value and second argument must be convertible one to another otherwise `between` function
	 * returns false.
	 *
	 * Function returns true if value in a filterable attribute of such a name is greater than or equal to value in second argument
	 * and lesser than or equal to value in third argument.
	 *
	 * Example:
	 *
	 * ```
	 * between("age", 20, 25)
	 * ```
	 */
	@Nullable
	static <T extends Serializable & Comparable<?>> AttributeBetween attributeBetween(@Nonnull String attributeName, @Nullable T from, @Nullable T to) {
		if (from == null && to == null) {
			return null;
		} else {
			return new AttributeBetween(attributeName, from, to);
		}
	}

	/**
	 * This `contains` is query that searches value of the attribute with name passed in first argument for presence of the
	 * {@link String} value passed in the second argument.
	 *
	 * Function returns true if attribute value contains secondary argument (starting with any position). Function is case
	 * sensitive and comparison is executed using `UTF-8` encoding (Java native).
	 *
	 * Example:
	 *
	 * ```
	 * contains("code", "eve")
	 * ```
	 */
	@Nullable
	static AttributeContains attributeContains(@Nonnull String attributeName, @Nullable String textToSearch) {
		return textToSearch == null ? null : new AttributeContains(attributeName, textToSearch);
	}

	/**
	 * This `startsWith` is query that searches value of the attribute with name passed in first argument for presence of the
	 * {@link String} value passed in the second argument.
	 *
	 * Function returns true if attribute value contains secondary argument (from first position). InSet other words attribute
	 * value starts with string passed in second argument. Function is case sensitive and comparison is executed using `UTF-8`
	 * encoding (Java native).
	 *
	 * Example:
	 *
	 * ```
	 * startsWith("code", "vid")
	 * ```
	 */
	@Nullable
	static AttributeStartsWith attributeStartsWith(@Nonnull String attributeName, @Nullable String textToSearch) {
		return textToSearch == null ? null : new AttributeStartsWith(attributeName, textToSearch);
	}

	/**
	 * This `endsWith` is query that searches value of the attribute with name passed in first argument for presence of the
	 * {@link String} value passed in the second argument.
	 *
	 * Function returns true if attribute value contains secondary argument (using reverse lookup from last position).
	 * InSet other words attribute value ends with string passed in second argument. Function is case sensitive and comparison
	 * is executed using `UTF-8` encoding (Java native).
	 *
	 * Example:
	 *
	 * ```
	 * endsWith("code", "ida")
	 * ```
	 */
	@Nullable
	static AttributeEndsWith attributeEndsWith(@Nonnull String attributeName, @Nullable String textToSearch) {
		return textToSearch == null ? null : new AttributeEndsWith(attributeName, textToSearch);
	}

	/**
	 * This `equals` is query that compares value of the attribute with name passed in first argument with the value passed
	 * in the second argument. First argument must be {@link String}, second argument may be any of {@link Comparable} type.
	 * Type of the attribute value and second argument must be convertible one to another otherwise `equals` function
	 * returns false.
	 *
	 * Function returns true if both values are equal.
	 *
	 * Example:
	 *
	 * ```
	 * equals("code", "abc")
	 * ```
	 */
	@Nullable
	static <T extends Serializable> AttributeEquals attributeEquals(@Nonnull String attributeName, @Nullable T attributeValue) {
		return attributeValue == null ? null : new AttributeEquals(attributeName, attributeValue);
	}

	/**
	 * This `lessThan` is query that compares value of the attribute with name passed in first argument with the value passed
	 * in the second argument. First argument must be {@link String},
	 * second argument may be any of {@link Comparable} type.
	 * Type of the attribute value and second argument must be convertible one to another otherwise `lessThan` function
	 * returns false.
	 *
	 * Function returns true if value in a filterable attribute of such a name is greater than value in second argument.
	 *
	 * Example:
	 *
	 * ```
	 * lessThan("age", 20)
	 * ```
	 */
	@Nullable
	static <T extends Serializable & Comparable<?>> AttributeLessThan attributeLessThan(@Nonnull String attributeName, @Nullable T attributeValue) {
		return attributeValue == null ? null : new AttributeLessThan(attributeName, attributeValue);
	}

	/**
	 * This `lessThanEquals` is query that compares value of the attribute with name passed in first argument with the value passed
	 * in the second argument. First argument must be {@link String},
	 * second argument may be any of {@link Comparable} type.
	 * Type of the attribute value and second argument must be convertible one to another otherwise `lessThanEquals` function
	 * returns false.
	 *
	 * Function returns true if value in a filterable attribute of such a name is lesser than value in second argument or
	 * equal.
	 *
	 * Example:
	 *
	 * ```
	 * lessThanEquals("age", 20)
	 * ```
	 */
	@Nullable
	static <T extends Serializable & Comparable<?>> AttributeLessThanEquals attributeLessThanEquals(@Nonnull String attributeName, @Nullable T attributeValue) {
		return attributeValue == null ? null : new AttributeLessThanEquals(attributeName, attributeValue);
	}

	/**
	 * This `greaterThan` is query that compares value of the attribute with name passed in first argument with the value passed
	 * in the second argument. First argument must be {@link String},
	 * second argument may be any of {@link Comparable} type.
	 * Type of the attribute value and second argument must be convertible one to another otherwise `greaterThan` function
	 * returns false.
	 *
	 * Function returns true if value in a filterable attribute of such a name is greater than value in second argument.
	 *
	 * Example:
	 *
	 * ```
	 * greaterThan("age", 20)
	 * ```
	 */
	@Nullable
	static <T extends Serializable & Comparable<?>> AttributeGreaterThan attributeGreaterThan(@Nonnull String attributeName, @Nullable T attributeValue) {
		return attributeValue == null ? null : new AttributeGreaterThan(attributeName, attributeValue);
	}

	/**
	 * This `greaterThanEquals` is query that compares value of the attribute with name passed in first argument with the value passed
	 * in the second argument. First argument must be {@link String},
	 * second argument may be any of {@link Comparable} type.
	 * Type of the attribute value and second argument must be convertible one to another otherwise `greaterThanEquals` function
	 * returns false.
	 *
	 * Function returns true if value in a filterable attribute of such a name is greater than value in second argument or
	 * equal.
	 *
	 * Example:
	 *
	 * ```
	 * greaterThanEquals("age", 20)
	 * ```
	 */
	@Nullable
	static <T extends Serializable & Comparable<?>> AttributeGreaterThanEquals attributeGreaterThanEquals(@Nonnull String attributeName, @Nullable T attributeValue) {
		return attributeValue == null ? null : new AttributeGreaterThanEquals(attributeName, attributeValue);
	}

	/**
	 * This `priceInPriceLists` is query accepts one or more [Integer](https://docs.oracle.com/javase/8/docs/api/java/lang/Integer.html)
	 * arguments that represents primary keys of price lists.
	 *
	 * Function returns true if entity has at least one price in any of specified price lists. This function is also affected by
	 * [priceInCurrency](#price-in-currency) function that limits the examined prices as well. The order of the price lists
	 * passed in the argument is crucial, because it defines the priority of the price lists. Let's have a product with
	 * following prices:
	 *
	 * | priceList       | currency | priceWithTax |
	 * |-----------------|----------|--------------|
	 * | basic           | EUR      | 999.99       |
	 * | registered_user | EUR      | 979.00       |
	 * | b2c_discount    | EUR      | 929.00       |
	 * | b2b_discount    | EUR      | 869.00       |
	 *
	 * If query contains:
	 *
	 * `and(
	 * priceInCurrency("EUR"),
	 * priceInPriceLists("basic", "b2b_discount"),
	 * priceBetween(800.0, 900.0)
	 * )`
	 *
	 * The product will not be found - because query engine will use first defined price for the price lists in defined order.
	 * It's in our case the price `999.99`, which is not in the defined price interval 800 € - 900 €. If the price lists in
	 * arguments gets switched to `priceInPriceLists("b2b_discount", "basic")`, the product will be returned, because the first
	 * price is now from `b2b_discount` price list - 869 € and this price is within defined interval.
	 *
	 * This query affect also the prices accessible in returned entities. By default, (unless [prices](#prices) requirement
	 * has ALL mode used), returned entities will contain only prices from specified price lists. In other words if entity has
	 * two prices - one from price list `1` and second from price list `2` and `priceInPriceLists(1)` is used in the query
	 * returned entity would have only first price fetched along with it.
	 *
	 * The non-sellable prices are not taken into an account in the search - for example if the product has only non-sellable
	 * price it will never be returned when {@link PriceInPriceLists} query or any other price query is used in the
	 * query. Non-sellable prices behaves like they don't exist. These non-sellable prices still remain accessible for reading
	 * on fetched entity in case the product is found by sellable price satisfying the filter. If you have specific price list
	 * reserved for non-sellable prices you may still use it in {@link PriceInPriceLists} query. It won't affect the set
	 * of returned entities, but it will ensure you can access those non-sellable prices on entities even when
	 * {@link PriceContentMode#RESPECTING_FILTER} is used in {@link PriceContent} requirement is used.
	 *
	 * Only single `priceInPriceLists` query can be used in the query. Constraint must be defined when other price related
	 * constraints are used in the query.
	 *
	 * Example:
	 *
	 * ```
	 * priceInPriceLists(1, 5, 6)
	 * ```
	 */
	@Nullable
	static PriceInPriceLists priceInPriceLists(@Nullable String... priceList) {
		if (priceList == null) {
			return null;
		}
		return new PriceInPriceLists(priceList);
	}

	/**
	 * This `priceInCurrency` is query accepts single {@link String}
	 * argument that represents [currency](https://en.wikipedia.org/wiki/ISO_4217) in ISO 4217 code.
	 *
	 * Function returns true if entity has at least one price with specified currency. This function is also affected by
	 * {@link PriceInPriceLists} function that limits the examined prices as well. When this query
	 * is used in the query returned entities will contain only prices matching specified locale. In other words if entity has
	 * two prices: USD and CZK and `priceInCurrency("CZK")` is used in query returned entity would have only Czech crown prices
	 * fetched along with it.
	 *
	 * Only single `priceInCurrency` query can be used in the query.
	 *
	 * Example:
	 *
	 * ```
	 * priceInCurrency("USD")
	 * ```
	 */
	@Nullable
	static PriceInCurrency priceInCurrency(@Nullable String currency) {
		return currency == null ? null : new PriceInCurrency(currency);
	}

	/**
	 * This `priceInCurrency` is query accepts single {@link String}
	 * argument that represents [currency](https://en.wikipedia.org/wiki/ISO_4217) in ISO 4217 code.
	 *
	 * Function returns true if entity has at least one price with specified currency. This function is also affected by
	 * {@link PriceInPriceLists} function that limits the examined prices as well. When this query
	 * is used in the query returned entities will contain only prices matching specified locale. In other words if entity has
	 * two prices: USD and CZK and `priceInCurrency("CZK")` is used in query returned entity would have only Czech crown prices
	 * fetched along with it.
	 *
	 * Only single `priceInCurrency` query can be used in the query.
	 *
	 * Example:
	 *
	 * ```
	 * priceInCurrency("USD")
	 * ```
	 */
	@Nullable
	static PriceInCurrency priceInCurrency(@Nullable Currency currency) {
		return currency == null ? null : new PriceInCurrency(currency);
	}

	/**
	 * This `withinHierarchy` query accepts {@link String}
	 * entity type in first argument, primary key of [Integer](https://docs.oracle.com/javase/8/docs/api/java/lang/Integer.html)
	 * type of entity with [hierarchical placement](../model/entity_model.md#hierarchical-placement) in second argument. There
	 * are also optional third and fourth arguments - see optional arguments {@link HierarchyDirectRelation}, {@link HierarchyExcludingRoot}
	 * and {@link HierarchyExcluding}.
	 *
	 * Constraint can also have only one numeric argument representing primary key of [Integer](https://docs.oracle.com/javase/8/docs/api/java/lang/Integer.html)
	 * the very same entity type in case this entity has [hierarchical placement](../model/entity_model.md#hierarchical-placement)
	 * defined. This format of the query may be used for example for returning category sub-tree (where we want to return
	 * category entities and also query them by their own hierarchy placement).
	 *
	 * Function returns true if entity has at least one [reference](../model/entity_model.md#references) that relates to specified entity
	 * type and entity either directly or relates to any other entity of the same type with [hierarchical placement](../model/entity_model.md#hierarchical-placement)
	 * subordinate to the directly related entity placement (in other words is present in its sub-tree).
	 *
	 * Let's have following hierarchical tree of categories (primary keys are in brackets):
	 *
	 * - TV (1)
	 * - Crt (2)
	 * - LCD (3)
	 * - big (4)
	 * - small (5)
	 * - Plasma (6)
	 * - Fridges (7)
	 *
	 * When query `withinHierarchy("category", 1)` is used in a query targeting product entities only products that
	 * relates directly to categories: `TV`, `Crt`, `LCD`, `big`, `small` and `Plasma` will be returned. Products in `Fridges`
	 * will be omitted because they are not in a sub-tree of `TV` hierarchy.
	 *
	 * Only single `withinHierarchy` query can be used in the query.
	 *
	 * Example:
	 *
	 * ```
	 * withinHierarchy("category", 4)
	 * ```
	 *
	 * If you want to query the entity that you're querying on you can also omit entity type specification. See example:
	 *
	 * ```
	 * query(
	 * entities("CATEGORY"),
	 * filterBy(
	 * withinHierarchy(5)
	 * )
	 * )
	 * ```
	 *
	 * This query will return all categories that belong to the sub-tree of category with primary key equal to 5.
	 *
	 * If you want to list all entities from the root level you need to use different query - `withinRootHierarchy` that
	 * has the same notation but doesn't specify the id of the root level entity:
	 *
	 * ```
	 * query(
	 * entities("CATEGORY"),
	 * filterBy(
	 * withinRootHierarchy()
	 * )
	 * )
	 * ```
	 *
	 * This query will return all categories within `CATEGORY` entity.
	 *
	 * You may use this query to list entities that refers to the hierarchical entities:
	 *
	 * ```
	 * query(
	 * entities("PRODUCT"),
	 * filterBy(
	 * withinRootHierarchy("CATEGORY")
	 * )
	 * )
	 * ```
	 *
	 * This query returns all products that are attached to any category. Although, this query doesn't make much sense it starts
	 * to be useful when combined with additional inner constraints described in following paragraphs.
	 *
	 * You can use additional sub constraints in `withinHierarchy` query: {@link HierarchyDirectRelation}, {@link HierarchyExcludingRoot}
	 * and {@link HierarchyExcluding}
	 */
	@Nullable
	static HierarchyWithin hierarchyWithinSelf(@Nullable FilterConstraint ofParent, @Nullable HierarchySpecificationFilterConstraint... with) {
		if (ofParent == null) {
			return null;
		} else if (with == null) {
			return new HierarchyWithin(ofParent);
		} else {
			return new HierarchyWithin(ofParent, with);
		}
	}

	/**
	 * This `withinHierarchy` query accepts {@link String}
	 * entity type in first argument, primary key of [Integer](https://docs.oracle.com/javase/8/docs/api/java/lang/Integer.html)
	 * type of entity with [hierarchical placement](../model/entity_model.md#hierarchical-placement) in second argument. There
	 * are also optional third and fourth arguments - see optional arguments {@link HierarchyDirectRelation}, {@link HierarchyExcludingRoot}
	 * and {@link HierarchyExcluding}.
	 *
	 * Constraint can also have only one numeric argument representing primary key of [Integer](https://docs.oracle.com/javase/8/docs/api/java/lang/Integer.html)
	 * the very same entity type in case this entity has [hierarchical placement](../model/entity_model.md#hierarchical-placement)
	 * defined. This format of the query may be used for example for returning category sub-tree (where we want to return
	 * category entities and also query them by their own hierarchy placement).
	 *
	 * Function returns true if entity has at least one [reference](../model/entity_model.md#references) that relates to specified entity
	 * type and entity either directly or relates to any other entity of the same type with [hierarchical placement](../model/entity_model.md#hierarchical-placement)
	 * subordinate to the directly related entity placement (in other words is present in its sub-tree).
	 *
	 * Let's have following hierarchical tree of categories (primary keys are in brackets):
	 *
	 * - TV (1)
	 * - Crt (2)
	 * - LCD (3)
	 * - big (4)
	 * - small (5)
	 * - Plasma (6)
	 * - Fridges (7)
	 *
	 * When query `withinHierarchy("category", 1)` is used in a query targeting product entities only products that
	 * relates directly to categories: `TV`, `Crt`, `LCD`, `big`, `small` and `Plasma` will be returned. Products in `Fridges`
	 * will be omitted because they are not in a sub-tree of `TV` hierarchy.
	 *
	 * Only single `withinHierarchy` query can be used in the query.
	 *
	 * Example:
	 *
	 * ```
	 * withinHierarchy("category", 4)
	 * ```
	 *
	 * If you want to query the entity that you're querying on you can also omit entity type specification. See example:
	 *
	 * ```
	 * query(
	 * entities("CATEGORY"),
	 * filterBy(
	 * withinHierarchy(5)
	 * )
	 * )
	 * ```
	 *
	 * This query will return all categories that belong to the sub-tree of category with primary key equal to 5.
	 *
	 * If you want to list all entities from the root level you need to use different query - `withinRootHierarchy` that
	 * has the same notation but doesn't specify the id of the root level entity:
	 *
	 * ```
	 * query(
	 * entities("CATEGORY"),
	 * filterBy(
	 * withinRootHierarchy()
	 * )
	 * )
	 * ```
	 *
	 * This query will return all categories within `CATEGORY` entity.
	 *
	 * You may use this query to list entities that refers to the hierarchical entities:
	 *
	 * ```
	 * query(
	 * entities("PRODUCT"),
	 * filterBy(
	 * withinRootHierarchy("CATEGORY")
	 * )
	 * )
	 * ```
	 *
	 * This query returns all products that are attached to any category. Although, this query doesn't make much sense it starts
	 * to be useful when combined with additional inner constraints described in following paragraphs.
	 *
	 * You can use additional sub constraints in `withinHierarchy` query: {@link HierarchyDirectRelation}, {@link HierarchyExcludingRoot}
	 * and {@link HierarchyExcluding}
	 */
	@Nullable
	static HierarchyWithin hierarchyWithin(@Nonnull String referenceName, @Nullable FilterConstraint ofParent, @Nullable HierarchySpecificationFilterConstraint... with) {
		if (ofParent == null) {
			return null;
		} else if (with == null) {
			return new HierarchyWithin(referenceName, ofParent);
		} else {
			return new HierarchyWithin(referenceName, ofParent, with);
		}
	}

	/**
	 * This `withinRootHierarchy` query accepts {@link String}
	 * entity type in first argument. There are also optional argument - see {@link HierarchyExcluding}.
	 *
	 * Function returns true if entity has at least one [reference](../model/entity_model.md#references) that relates to specified entity
	 * type and entity either directly or relates to any other entity of the same type with [hierarchical placement](../model/entity_model.md#hierarchical-placement)
	 * subordinate to the directly related entity placement (in other words is present in its sub-tree).
	 *
	 * Let's have following hierarchical tree of categories (primary keys are in brackets):
	 *
	 * - TV (1)
	 * - Crt (2)
	 * - LCD (3)
	 * - big (4)
	 * - small (5)
	 * - Plasma (6)
	 * - Fridges (7)
	 *
	 * When query `withinRootHierarchy("category")` is used in a query targeting product entities all products that
	 * relates to any of categories will be returned.
	 *
	 * Only single `withinRootHierarchy` query can be used in the query.
	 *
	 * Example:
	 *
	 * ```
	 * withinRootHierarchy("category")
	 * ```
	 *
	 * If you want to query the entity that you're querying on you can also omit entity type specification. See example:
	 *
	 * ```
	 * query(
	 * entities("CATEGORY"),
	 * filterBy(
	 * withinRootHierarchy()
	 * )
	 * )
	 * ```
	 *
	 * This query will return all categories within `CATEGORY` entity.
	 *
	 * You may use this query to list entities that refers to the hierarchical entities:
	 *
	 * ```
	 * query(
	 * entities("PRODUCT"),
	 * filterBy(
	 * withinRootHierarchy("CATEGORY")
	 * )
	 * )
	 * ```
	 *
	 * This query returns all products that are attached to any category.
	 */
	@Nonnull
	static HierarchyWithinRoot hierarchyWithinRootSelf(@Nullable HierarchySpecificationFilterConstraint... with) {
		return with == null ? new HierarchyWithinRoot() : new HierarchyWithinRoot(with);
	}

	/**
	 * This `withinRootHierarchy` query accepts {@link String}
	 * entity type in first argument. There are also optional argument - see {@link HierarchyExcluding}.
	 *
	 * Function returns true if entity has at least one [reference](../model/entity_model.md#references) that relates to specified entity
	 * type and entity either directly or relates to any other entity of the same type with [hierarchical placement](../model/entity_model.md#hierarchical-placement)
	 * subordinate to the directly related entity placement (in other words is present in its sub-tree).
	 *
	 * Let's have following hierarchical tree of categories (primary keys are in brackets):
	 *
	 * - TV (1)
	 * - Crt (2)
	 * - LCD (3)
	 * - big (4)
	 * - small (5)
	 * - Plasma (6)
	 * - Fridges (7)
	 *
	 * When query `withinRootHierarchy("category")` is used in a query targeting product entities all products that
	 * relates to any of categories will be returned.
	 *
	 * Only single `withinRootHierarchy` query can be used in the query.
	 *
	 * Example:
	 *
	 * ```
	 * withinRootHierarchy("category")
	 * ```
	 *
	 * If you want to query the entity that you're querying on you can also omit entity type specification. See example:
	 *
	 * ```
	 * query(
	 * entities("CATEGORY"),
	 * filterBy(
	 * withinRootHierarchy()
	 * )
	 * )
	 * ```
	 *
	 * This query will return all categories within `CATEGORY` entity.
	 *
	 * You may use this query to list entities that refers to the hierarchical entities:
	 *
	 * ```
	 * query(
	 * entities("PRODUCT"),
	 * filterBy(
	 * withinRootHierarchy("CATEGORY")
	 * )
	 * )
	 * ```
	 *
	 * This query returns all products that are attached to any category.
	 */
	@Nonnull
	static HierarchyWithinRoot hierarchyWithinRoot(@Nonnull String referenceName, @Nullable HierarchySpecificationFilterConstraint... with) {
		return with == null ? new HierarchyWithinRoot() : new HierarchyWithinRoot(referenceName, with);
	}

	// TOBEDONE JNO: docs
	@Nullable
	static HierarchyHaving having(@Nullable FilterConstraint... includeChildTreeConstraints) {
		if (ArrayUtils.isEmpty(includeChildTreeConstraints)) {
			return null;
		}
		return new HierarchyHaving(includeChildTreeConstraints);
	}

	/**
	 * If you use `excludingRoot` sub-query in `withinHierarchy` parent, you can specify one or more
	 * [Integer](https://docs.oracle.com/javase/8/docs/api/java/lang/Integer.html) primary keys of the underlying
	 * entities which hierarchical subtree should be excluded from examination.
	 *
	 * Exclusion arguments allows excluding certain parts of the hierarchy tree from examination. This feature is used in
	 * environments where certain sub-trees can be made "invisible" and should not be accessible to users, although they are
	 * still part of the database.
	 *
	 * Let's have following hierarchical tree of categories (primary keys are in brackets):
	 *
	 * - TV (1)
	 * - Crt (2)
	 * - LCD (3)
	 * - big (4)
	 * - small (5)
	 * - Plasma (6)
	 * - Fridges (7)
	 *
	 * When query `withinHierarchy("category", 1, excluding(3))` is used in a query targeting product entities,
	 * only products that relate directly to categories: `TV`, `Crt` and `Plasma` will be returned. Products in `Fridges` will
	 * be omitted because they are not in a sub-tree of `TV` hierarchy and products in `LCD` sub-tree will be omitted because
	 * they're part of the excluded sub-trees.
	 */
	@Nullable
	static HierarchyExcluding excluding(@Nullable FilterConstraint... excludeChildTreeConstraints) {
		if (ArrayUtils.isEmpty(excludeChildTreeConstraints)) {
			return null;
		}
		return new HierarchyExcluding(excludeChildTreeConstraints);
	}

	/**
	 * This query can be used only as sub query of `withinHierarchy` or `withinRootHierarchy`.
	 * If you use `directRelation` sub-query fetching products related to category - only products that are directly
	 * related to that category will be returned in the response.
	 *
	 * Let's have the following category tree:
	 *
	 * - TV (1)
	 * - Crt (2)
	 * - LCD (3)
	 * - AMOLED (4)
	 *
	 * These categories are related by following products:
	 *
	 * - TV (1):
	 * - Product Philips 32"
	 * - Product Samsung 24"
	 * - Crt (2):
	 * - Product Ilyiama 15"
	 * - Product Panasonic 17"
	 * - LCD (3):
	 * - Product BenQ 32"
	 * - Product LG 28"
	 * - AMOLED (4):
	 * - Product Samsung 32"
	 *
	 * When using this query:
	 *
	 * ```
	 * query(
	 * entities("PRODUCT"),
	 * filterBy(
	 * withinHierarchy("CATEGORY", 1)
	 * )
	 * )
	 * ```
	 *
	 * All products will be returned.
	 *
	 * When this query is used:
	 *
	 * ```
	 * query(
	 * entities("PRODUCT"),
	 * filterBy(
	 * withinHierarchy("CATEGORY", 1, directRelation())
	 * )
	 * )
	 * ```
	 *
	 * Only products directly related to TV category will be returned - i.e.: Philips 32" and Samsung 24". Products related
	 * to sub-categories of TV category will be omitted.
	 *
	 * You can also use this hint to browse the hierarchy of the entity itself - to fetch subcategories of category.
	 * If you use this query:
	 *
	 * ```
	 * query(
	 * entities("CATEGORY"),
	 * filterBy(
	 * withinHierarchy(1)
	 * )
	 * )
	 * ```
	 *
	 * All categories under the category subtree of `TV (1)` will be listed (this means categories `TV`, `Crt`, `LCD`, `AMOLED`).
	 * If you use this query:
	 *
	 * ```
	 * query(
	 * entities("CATEGORY"),
	 * filterBy(
	 * withinHierarchy(1, directRelation())
	 * )
	 * )
	 * ```
	 *
	 * Only direct sub-categories of category `TV (1)` will be listed (this means categories `Crt` and `LCD`).
	 * You can also use this hint with query `withinRootHierarchy`:
	 *
	 * ```
	 * query(
	 * entities("CATEGORY"),
	 * filterBy(
	 * withinRootHierarchy()
	 * )
	 * )
	 * ```
	 *
	 * All categories in entire tree will be listed.
	 *
	 * When using this query:
	 *
	 * ```
	 * query(
	 * entities("CATEGORY"),
	 * filterBy(
	 * withinHierarchy(directRelation())
	 * )
	 * )
	 * ```
	 *
	 * Which would return only category `TV (1)`.
	 *
	 * As you can see {@link HierarchyExcludingRoot} and {@link HierarchyDirectRelation} are mutually exclusive.
	 */
	@Nonnull
	static HierarchyDirectRelation directRelation() {
		return new HierarchyDirectRelation();
	}

	/**
	 * If you use `excludingRoot` sub-query in `withinHierarchy` parent, response will contain only children of the
	 * entity specified in `withinHierarchy` or entities related to those children entities - if the `withinHierarchy` targes
	 * different entity types.
	 *
	 * Let's have following category tree:
	 *
	 * - TV (1)
	 * - Crt (2)
	 * - LCD (3)
	 *
	 * These categories are related by following products:
	 *
	 * - TV (1):
	 * - Product Philips 32"
	 * - Product Samsung 24"
	 * - Crt (2):
	 * - Product Ilyiama 15"
	 * - Product Panasonic 17"
	 * - LCD (3):
	 * - Product BenQ 32"
	 * - Product LG 28"
	 *
	 * When using this query:
	 *
	 * ```
	 * query(
	 * entities("PRODUCT"),
	 * filterBy(
	 * withinHierarchy("CATEGORY", 1)
	 * )
	 * )
	 * ```
	 *
	 * All products will be returned.
	 * When this query is used:
	 *
	 * ```
	 * query(
	 * entities("PRODUCT"),
	 * filterBy(
	 * withinHierarchy("CATEGORY", 1, excludingRoot())
	 * )
	 * )
	 * ```
	 *
	 * Only products related to sub-categories of the TV category will be returned - i.e.: Ilyiama 15", Panasonic 17" and
	 * BenQ 32", LG 28". The products related directly to TV category will not be returned.
	 *
	 * As you can see {@link HierarchyExcludingRoot} and {@link HierarchyDirectRelation} are mutually exclusive.
	 */
	@Nonnull
	static HierarchyExcludingRoot excludingRoot() {
		return new HierarchyExcludingRoot();
	}

	/**
	 * This `language` is query accepts single {@link Locale} argument.
	 *
	 * Function returns true if entity has at least one localized attribute or associated data that  targets specified locale.
	 *
	 * If require part of the query doesn't contain {@link DataInLocales} requirement that
	 * would specify the requested data localization, this filtering query implicitly sets requirement to the passed
	 * language argument. In other words if entity has two localizations: `en-US` and `cs-CZ` and `language('cs-CZ')` is
	 * used in query, returned entity would have only Czech localization of attributes and associated data fetched along
	 * with it (and also attributes that are locale agnostic).
	 *
	 * If query contains no language query filtering logic is applied only on "global" (i.e. language agnostic)
	 * attributes.
	 *
	 * Only single `language` query can be used in the query.
	 *
	 * Example:
	 *
	 * ```
	 * language('en-US')
	 * ```
	 */
	@Nullable
	static EntityLocaleEquals entityLocaleEquals(@Nullable Locale locale) {
		return locale == null ? null : new EntityLocaleEquals(locale);
	}

	// TOBEDONE JNO: docs
	@Nullable
	static EntityHaving entityHaving(@Nullable FilterConstraint filterConstraint) {
		return filterConstraint == null ? null : new EntityHaving(filterConstraint);
	}

	/**
	 * This `inRange` is query that compares value of the attribute with name passed in first argument with the date
	 * and time passed in the second argument. First argument must be {@link String},
	 * second argument must be {@link OffsetDateTime} type.
	 * Type of the attribute value must implement [Range](classes/range_interface.md) interface.
	 *
	 * Function returns true if second argument is greater than or equal to range start (from), and is lesser than
	 * or equal to range end (to).
	 *
	 * Example:
	 *
	 * ```
	 * inRange("valid", 2020-07-30T20:37:50+00:00)
	 * ```
	 */
	@Nullable
	static AttributeInRange attributeInRange(@Nonnull String attributeName, @Nullable OffsetDateTime atTheMoment) {
		return atTheMoment == null ? null : new AttributeInRange(attributeName, atTheMoment);
	}

	/**
	 * This `inRange` is query that compares value of the attribute with name passed in first argument with
	 * the number passed in the second argument. First argument must be {@link String},
	 * second argument must be {@link Number} type.
	 * Type of the attribute value must implement [Range](classes/range_interface.md) interface.
	 *
	 * Function returns true if second argument is greater than or equal to range start (from), and is lesser than
	 * or equal to range end (to).
	 *
	 * Example:
	 *
	 * ```
	 * inRange("age", 18)
	 * ```
	 */
	@Nullable
	static AttributeInRange attributeInRange(@Nonnull String attributeName, @Nullable Number theValue) {
		return theValue == null ? null : new AttributeInRange(attributeName, theValue);
	}

	/**
	 * This `inRange` is query that compares value of the attribute with name passed in first argument with current
	 * date and time.
	 * Type of the attribute value must implement [Range](classes/range_interface.md) interface.
	 *
	 * Function returns true if second argument is greater than or equal to range start (from), and is lesser than
	 * or equal to range end (to).
	 *
	 * Example:
	 *
	 * ```
	 * inRange("valid")
	 * ```
	 */
	@Nonnull
	static AttributeInRange attributeInRangeNow(@Nonnull String attributeName) {
		return new AttributeInRange(attributeName);
	}

	/**
	 * This `inSet` is query that compares value of the attribute with name passed in first argument with all the values passed
	 * in the second, third and additional arguments. First argument must be {@link String},
	 * additional arguments may be any of {@link Comparable} type.
	 * Type of the attribute value and additional arguments must be convertible one to another otherwise `in` function
	 * skips value comparison and ultimately returns false.
	 *
	 * Function returns true if attribute value is equal to at least one of additional values.
	 *
	 * Example:
	 *
	 * ```
	 * inSet("level", 1, 2, 3)
	 * ```
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	static <T extends Serializable> AttributeInSet attributeInSet(@Nonnull String attributeName, @Nullable T... set) {
		if (set == null) {
			return null;
		}
		final List<T> args = Arrays.stream(set).filter(Objects::nonNull).toList();
		if (args.isEmpty()) {
			return null;
		} else if (args.size() == set.length) {
			return new AttributeInSet(attributeName, set);
		} else {
			final T[] limitedSet = (T[]) Array.newInstance(set.getClass().getComponentType(), args.size());
			for (int i = 0; i < args.size(); i++) {
				limitedSet[i] = args.get(i);
			}
			return new AttributeInSet(attributeName, limitedSet);
		}
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static AttributeEquals attributeEqualsFalse(@Nonnull String attributeName) {
		return new AttributeEquals(attributeName, Boolean.FALSE);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static AttributeEquals attributeEqualsTrue(@Nonnull String attributeName) {
		return new AttributeEquals(attributeName, Boolean.TRUE);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nullable
	static AttributeIs attributeIs(@Nonnull String attributeName, @Nullable AttributeSpecialValue specialValue) {
		if (specialValue == null) {
			return null;
		}
		return new AttributeIs(attributeName, specialValue);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static AttributeIs attributeIsNull(@Nonnull String attributeName) {
		return new AttributeIs(attributeName, AttributeSpecialValue.NULL);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static AttributeIs attributeIsNotNull(@Nonnull String attributeName) {
		return new AttributeIs(attributeName, AttributeSpecialValue.NOT_NULL);
	}

	/**
	 * This `priceBetween` query accepts two {@link BigDecimal} arguments that represents lower and higher price
	 * bounds (inclusive).
	 *
	 * Function returns true if entity has sellable price in most prioritized price list according to {@link PriceInPriceLists}
	 * query greater than or equal to passed lower bound and lesser than or equal to passed higher bound. This function
	 * is also affected by other price related constraints such as {@link PriceInCurrency} functions that limits the examined
	 * prices as well.
	 *
	 * Most prioritized price term relates to [price computation algorithm](price_computation.md) described in special article.
	 *
	 * By default, price with tax is used for filtering, you can change this by using {@link PriceType} require query.
	 * Non-sellable prices doesn't participate in the filtering at all.
	 *
	 * Only single `priceBetween` query can be used in the query.
	 *
	 * Example:
	 *
	 * ```
	 * priceBetween(150.25, 220.0)
	 * ```
	 */
	@Nullable
	static PriceBetween priceBetween(@Nullable BigDecimal from, @Nullable BigDecimal to) {
		if (from == null && to == null) {
			return null;
		} else {
			return new PriceBetween(from, to);
		}
	}

	/**
	 * This `priceValidIn` is query accepts single {@link OffsetDateTime}
	 * argument that represents the moment in time for which entity price must be valid.
	 *
	 * Function returns true if entity has at least one price which validity start (valid from) is lesser or equal to passed
	 * date and time and validity end (valid to) is greater or equal to passed date and time. This function is also affected by
	 * {@link PriceInCurrency} and {@link PriceInPriceLists} functions that limits the examined prices as well.
	 * When this query is used in the query returned entities will contain only prices which validity settings match
	 * specified date and time.
	 *
	 * Only single `priceValidIn` query can be used in the query.
	 *
	 * Example:
	 *
	 * ```
	 * priceValidIn(2020-07-30T20:37:50+00:00)
	 * ```
	 */
	@Nullable
	static PriceValidIn priceValidIn(@Nullable OffsetDateTime theMoment) {
		return theMoment == null ? null : new PriceValidIn(theMoment);
	}

	/**
	 * This `priceValidIn` is query uses current date and time for price validity examination.
	 *
	 * Function returns true if entity has at least one price which validity start (valid from) is lesser or equal to passed
	 * date and time and validity end (valid to) is greater or equal to passed date and time. This function is also affected by
	 * {@link PriceInCurrency} and {@link PriceInPriceLists} functions that limits the examined prices as well.
	 * When this query is used in the query returned entities will contain only prices which validity settings match
	 * specified date and time.
	 *
	 * Only single `priceValidIn` query can be used in the query.
	 *
	 * Example:
	 *
	 * ```
	 * priceValidIn()
	 * ```
	 */
	@Nonnull
	static PriceValidIn priceValidNow() {
		return new PriceValidIn();
	}

	/**
	 * This `facet` query accepts {@link String}
	 * entity type in first argument and one or more
	 * additional [Integer](https://docs.oracle.com/javase/8/docs/api/java/lang/Integer.html)
	 * arguments that represents [facets](../model/entity_model.md#facets) that entity is required to have in order to match
	 * this query.
	 *
	 * Function returns true if entity has a facet for specified entity type and matches passed primary keys in additional
	 * arguments. By matching we mean, that entity has to have any of its facet (with particular type) primary keys equal to at
	 * least one primary key specified in additional arguments.
	 *
	 * Example:
	 *
	 * ```
	 * query(
	 * entities("product"),
	 * filterBy(
	 * userFilter(
	 * facet("category", 4, 5),
	 * facet("group", 7, 13)
	 * )
	 * )
	 * )
	 * ```
	 *
	 * Constraint may be used only in [user filter](#user-filter) container. By default, facets of the same type within same
	 * group are combined by conjunction (OR), facets of different types / groups are combined by disjunction (AND). This
	 * default behaviour can be controlled exactly by using any of following require constraints:
	 *
	 * - [facet groups conjunction](#facet-groups-conjunction) - changes relationship between facets in the same group
	 * - [facet groups disjunction](#facet-groups-disjunction) - changes relationship between facet groups
	 *
	 * ***Note:** you may ask why facet relation is specified by [require](#require) and not directly part of
	 * the [filter](#filter)
	 * body. The reason is simple - facet relation in certain group is usually specified system-wide and doesn't change in time
	 * frequently. This means that it could be easily cached and passing this information in an extra require simplifies query
	 * construction process.*
	 *
	 * *Another reason is that we need to know relationships among facet groups even for types/groups that hasn't yet been
	 * selected by the user in order to be able to compute [facet summary](#facet-summary) output.*
	 */
	@Nullable
	static FacetHaving facetHaving(@Nonnull String referenceName, @Nullable FilterConstraint... constraint) {
		return ArrayUtils.isEmpty(constraint) ? null : new FacetHaving(referenceName, constraint);
	}

	/**
	 * This `primaryKey` is query that accepts set of {@link Integer}
	 * that represents primary keys of the entities that should be returned.
	 *
	 * Function returns true if entity primary key is part of the passed set of integers.
	 * This form of entity lookup function is the fastest one.
	 *
	 * Only single `primaryKey` query can be used in the query.
	 *
	 * Example:
	 *
	 * ```
	 * primaryKey(1, 2, 3)
	 * ```
	 */
	@Nullable
	static EntityPrimaryKeyInSet entityPrimaryKeyInSet(@Nullable Integer... primaryKey) {
		if (primaryKey == null) {
			return null;
		}
		return new EntityPrimaryKeyInSet(primaryKey);
	}

	/*
		ORDERING
	 */

	/**
	 * This `orderBy` is container for ordering that contains two or more inner ordering functions which output is combined. Ordering
	 * process is as follows:
	 *
	 * - first ordering evaluated, entities missing requested attribute value are excluded to intermediate bucket
	 * - next ordering is evaluated using entities present in an intermediate bucket, entities missing requested attribute
	 * are excluded to new intermediate bucket
	 * - second step is repeated until all orderings are processed
	 * - content of the last intermediate bucket is appended to the result ordered by the primary key in ascending order
	 *
	 * Entities with same (equal) values must not be subject to secondary ordering rules and may be sorted randomly within
	 * the scope of entities with the same value (this is subject to change, currently this behaviour differs from the one
	 * used by relational databases - but might be more performant).
	 *
	 * Example:
	 *
	 * ```
	 * orderBy(
	 * attribute("code", ASC),
	 * attribute("created", DESC),
	 * price(DESC)
	 * )
	 * ```
	 */
	@Nullable
	static OrderBy orderBy(@Nullable OrderConstraint... constraints) {
		if (constraints == null) {
			return null;
		}
		return new OrderBy(constraints);
	}

	/**
	 * The container encapsulates order constraints that control the order of the facet groups in facet summary.
	 * TOBEDONE JNO - document me
	 *
	 * @param constraints
	 * @return
	 */
	@Nullable
	static OrderGroupBy orderGroupBy(@Nullable OrderConstraint... constraints) {
		if (constraints == null) {
			return null;
		}
		return new OrderGroupBy(constraints);
	}

	/**
	 * This `referenceAttribute` container is ordering that sorts returned entities by reference attributes. Ordering is
	 * specified by inner constraints. Price related orderings cannot be used here, because references don't posses of prices.
	 *
	 * Example:
	 *
	 * ```
	 * referenceAttribute(
	 * "CATEGORY",
	 * attribute("categoryPriority", ASC)
	 * )
	 * ```
	 *
	 * or
	 *
	 * ```
	 * referenceAttribute(
	 * "CATEGORY",
	 * attribute("categoryPriority", ASC),
	 * attribute("stockPriority", DESC)
	 * )
	 * ```
	 */
	@Nullable
	static ReferenceProperty referenceProperty(@Nonnull String referenceName, @Nullable OrderConstraint... constraints) {
		if (constraints == null) {
			return null;
		}
		return new ReferenceProperty(referenceName, constraints);
	}

	/**
	 * This `referenceAttribute` container is ordering that sorts returned entities by reference attributes. Ordering is
	 * specified by inner constraints. Price related orderings cannot be used here, because references don't posses of prices.
	 *
	 * Example:
	 *
	 * ```
	 * referenceAttribute(
	 * "CATEGORY",
	 * attribute("categoryPriority", ASC)
	 * )
	 * ```
	 *
	 * or
	 *
	 * ```
	 * referenceAttribute(
	 * "CATEGORY",
	 * attribute("categoryPriority", ASC),
	 * attribute("stockPriority", DESC)
	 * )
	 * ```
	 */
	@Nullable
	static EntityProperty entityProperty(@Nullable OrderConstraint... constraints) {
		if (constraints == null) {
			return null;
		}
		return new EntityProperty(constraints);
	}

	/**
	 * Sorts returned entities by values in attribute with name passed in the first argument
	 * by ascending order direction. First argument must be of {@link String} type.
	 * Ordering is executed by natural order of the {@link Comparable}
	 * type.
	 * Example:
	 * ```
	 * attribute("married")
	 * ```
	 */
	@Nonnull
	static AttributeNatural attributeNatural(@Nonnull String attributeName) {
		return new AttributeNatural(attributeName);
	}

	/**
	 * Sorts returned entities by values in attribute with name passed in the first argument
	 * and order direction in second. First argument must be of {@link String} type. Second argument must be one of
	 * {@link OrderDirection} enum.
	 * Ordering is executed by natural order of the {@link Comparable}
	 * type.
	 * Example:
	 * ```
	 * attribute("age", ASC)
	 * ```
	 */
	@Nonnull
	static AttributeNatural attributeNatural(@Nonnull String attributeName, @Nonnull OrderDirection orderDirection) {
		return new AttributeNatural(attributeName, orderDirection);
	}

	/**
	 * This `price` is ordering that sorts returned entities by most priority price in the default ascending order.
	 * Most priority price relates to [price computation algorithm](price_computation.md) described in special article.
	 * Example:
	 * ```
	 * price()
	 * ```
	 */
	@Nonnull
	static PriceNatural priceNatural() {
		return new PriceNatural();
	}

	/**
	 * This `price` is ordering that sorts returned entities by most priority price in defined order direction in the first
	 * optional argument.
	 * Most priority price relates to [price computation algorithm](price_computation.md) described in special article.
	 * Example:
	 * ```
	 * price(DESC)
	 * ```
	 */
	@Nonnull
	static PriceNatural priceNatural(@Nonnull OrderDirection orderDirection) {
		return new PriceNatural(orderDirection);
	}

	/**
	 * This `random` is ordering that sorts returned entities in random order.
	 *
	 * Example:
	 *
	 * ```
	 * random()
	 * ```
	 */
	@Nonnull
	static Random random() {
		return new Random();
	}

	/*
		requirement
	 */

	/**
	 * This `requirement` is container for additonal requirements. It contains two or more inner requirement functions.
	 *
	 * Example:
	 *
	 * ```
	 * require(
	 * page(1, 2),
	 * entityBody()
	 * )
	 * ```
	 */
	@Nullable
	static Require require(@Nullable RequireConstraint... constraints) {
		if (constraints == null) {
			return null;
		}
		return new Require(constraints);
	}

	/**
	 * This `attributeHistogram` requirement usage triggers computing and adding an object to the result index. It has single
	 * argument that states the number of histogram buckets (columns) that can be safely visualized to the user. Usually
	 * there is fixed size area dedicated to the histogram visualisation and there is no sense to return histogram with
	 * so many buckets (columns) that wouldn't be possible to render. For example - if there is 200px size for the histogram
	 * and we want to dedicate 10px for one column, it's wise to ask for 20 buckets.
	 *
	 * It accepts one or more {@link String} arguments as second, third (and so on) argument that specify filterable attribute
	 * name for which [histograms](https://en.wikipedia.org/wiki/Histogram) should be computed. Attribute must contain only
	 * numeric values in order to compute histogram data.
	 *
	 * When this requirement is used an additional object {@link java.util.Map} is
	 * stored to result. Key of this map is {@link String} of attribute
	 * name and value is the [Histogram](classes/histogram.md).
	 *
	 * Example:
	 *
	 * ```
	 * attributeHistogram(20, "width", "height")
	 * ```
	 */
	@Nullable
	static AttributeHistogram attributeHistogram(int requestedBucketCount, @Nullable String... attributeName) {
		if (ArrayUtils.isEmpty(attributeName)) {
			return null;
		}
		return new AttributeHistogram(requestedBucketCount, attributeName);
	}

	/**
	 * This `priceHistogram` requirement usage triggers computing and adding an object to the result index. It has single
	 * argument that states the number of histogram buckets (columns) that can be safely visualized to the user. Usually
	 * there is fixed size area dedicated to the histogram visualisation and there is no sense to return histogram with
	 * so many buckets (columns) that wouldn't be possible to render. For example - if there is 200px size for the histogram
	 * and we want to dedicate 10px for one column, it's wise to ask for 20 buckets.
	 *
	 * When this requirement is used an additional object {@link PriceHistogram} is stored to the result. Histogram
	 * contains statistics on price layout in the query result.
	 *
	 * Example:
	 *
	 * ```
	 * priceHistogram(20)
	 * ```
	 */
	@Nonnull
	static PriceHistogram priceHistogram(int requestedBucketCount) {
		return new PriceHistogram(requestedBucketCount);
	}

	/**
	 * This `facetGroupsConjunction` require allows specifying inter-facet relation inside facet groups of certain primary ids.
	 * First mandatory argument specifies entity type of the facet group, secondary argument allows to define one more facet
	 * group ids which inner facets should be considered conjunctive.
	 *
	 * This require query changes default behaviour stating that all facets inside same facet group are combined by OR
	 * relation (eg. disjunction). Constraint has sense only when [facet](#facet) query is part of the query.
	 *
	 * Example:
	 *
	 * <pre>
	 * query(
	 *    entities("product"),
	 *    filterBy(
	 *       userFilter(
	 *          facet("group", 1, 2),
	 *          facet("parameterType", 11, 12, 22)
	 *       )
	 *    ),
	 *    require(
	 *       facetGroupsConjunction("parameterType", 1, 8, 15)
	 *    )
	 * )
	 * </pre>
	 *
	 * This statement means, that facets in `parameterType` groups `1`, `8`, `15` will be joined with boolean AND relation when
	 * selected.
	 *
	 * Let's have this facet/group situation:
	 *
	 * Color `parameterType` (group id: 1):
	 *
	 * - blue (facet id: 11)
	 * - red (facet id: 12)
	 *
	 * Size `parameterType` (group id: 2):
	 *
	 * - small (facet id: 21)
	 * - large (facet id: 22)
	 *
	 * Flags `tag` (group id: 3):
	 *
	 * - action products (facet id: 31)
	 * - new products (facet id: 32)
	 *
	 * When user selects facets: blue (11), red (12) by default relation would be: get all entities that have facet blue(11) OR
	 * facet red(12). If require `facetGroupsConjunction("parameterType", 1)` is passed in the query filtering condition will
	 * be composed as: blue(11) AND red(12)
	 */
	@Nullable
	static FacetGroupsConjunction facetGroupsConjunction(@Nonnull String referenceName, @Nullable FilterBy filterBy) {
		if (filterBy == null || !filterBy.isApplicable()) {
			return null;
		}
		return new FacetGroupsConjunction(referenceName, filterBy);
	}

	/**
	 * This `facetGroupsDisjunction` require query allows specifying facet relation among different facet groups of certain
	 * primary ids. First mandatory argument specifies entity type of the facet group, secondary argument allows to define one
	 * more facet group ids that should be considered disjunctive.
	 *
	 * This require query changes default behaviour stating that facets between two different facet groups are combined by
	 * AND relation and changes it to the disjunction relation instead.
	 *
	 * Example:
	 *
	 * <pre>
	 * query(
	 *    entities("product"),
	 *    filterBy(
	 *       userFilter(
	 *          facet("group", 1, 2),
	 *          facet("parameterType", 11, 12, 22)
	 *       )
	 *    ),
	 *    require(
	 *       facetGroupsDisjunction("parameterType", 1, 2)
	 *    )
	 * )
	 * </pre>
	 *
	 * This statement means, that facets in `parameterType` facet groups `1`, `2` will be joined with the rest of the query by
	 * boolean OR relation when selected.
	 *
	 * Let's have this facet/group situation:
	 *
	 * Color `parameterType` (group id: 1):
	 *
	 * - blue (facet id: 11)
	 * - red (facet id: 12)
	 *
	 * Size `parameterType` (group id: 2):
	 *
	 * - small (facet id: 21)
	 * - large (facet id: 22)
	 *
	 * Flags `tag` (group id: 3):
	 *
	 * - action products (facet id: 31)
	 * - new products (facet id: 32)
	 *
	 * When user selects facets: blue (11), large (22), new products (31) - the default meaning would be: get all entities that
	 * have facet blue as well as facet large and action products tag (AND). If require `facetGroupsDisjunction("tag", 3)`
	 * is passed in the query, filtering condition will be composed as: (`blue(11)` AND `large(22)`) OR `new products(31)`
	 */
	@Nullable
	static FacetGroupsDisjunction facetGroupsDisjunction(@Nonnull String referenceName, @Nullable FilterBy filterBy) {
		if (filterBy == null || !filterBy.isApplicable()) {
			return null;
		}
		return new FacetGroupsDisjunction(referenceName, filterBy);
	}

	/**
	 * This `facetGroupsNegation` requirement allows specifying facet relation inside facet groups of certain primary ids. Negative facet
	 * groups results in omitting all entities that have requested facet in query result. First mandatory argument specifies
	 * entity type of the facet group, secondary argument allows to define one more facet group ids that should be considered
	 * negative.
	 *
	 * Example:
	 *
	 * ```
	 * facetGroupsNegation("parameterType", 1, 8, 15)
	 * ```
	 *
	 * This statement means, that facets in "parameterType" groups `1`, `8`, `15` will be joined with boolean AND NOT relation
	 * when selected.
	 */
	@Nullable
	static FacetGroupsNegation facetGroupsNegation(@Nonnull String referenceName, @Nullable FilterBy filterBy) {
		if (filterBy == null || !filterBy.isApplicable()) {
			return null;
		}
		return new FacetGroupsNegation(referenceName, filterBy);
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyOfSelf hierarchyOfSelf(@Nullable HierarchyRequireConstraint... requirement) {
		return ArrayUtils.isEmpty(requirement) ? null : new HierarchyOfSelf(null, requirement);
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyOfSelf hierarchyOfSelf(
		@Nullable OrderBy orderBy,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		return ArrayUtils.isEmpty(requirement) ? null : new HierarchyOfSelf(orderBy, requirement);
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyOfReference hierarchyOfReference(
		@Nullable String referenceName,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		return hierarchyOfReference(referenceName, null, null, requirement);
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyOfReference hierarchyOfReference(
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		return hierarchyOfReference(referenceName, null, orderBy, requirement);
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyOfReference hierarchyOfReference(
		@Nullable String referenceName,
		@Nullable EmptyHierarchicalEntityBehaviour emptyHierarchicalEntityBehaviour,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		return referenceName == null || ArrayUtils.isEmpty(requirement) ?
			null :
			new HierarchyOfReference(
				referenceName,
				ofNullable(emptyHierarchicalEntityBehaviour).orElse(EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY),
				requirement
			);
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyOfReference hierarchyOfReference(
		@Nullable String referenceName,
		@Nullable EmptyHierarchicalEntityBehaviour emptyHierarchicalEntityBehaviour,
		@Nullable OrderBy orderBy,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		return referenceName == null || ArrayUtils.isEmpty(requirement) ?
			null :
			new HierarchyOfReference(
				referenceName,
				ofNullable(emptyHierarchicalEntityBehaviour).orElse(EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY),
				orderBy,
				requirement
			);
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyOfReference hierarchyOfReference(
		@Nullable String[] referenceName,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		return hierarchyOfReference(referenceName, null, null, requirement);
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyOfReference hierarchyOfReference(
		@Nullable String[] referenceName,
		@Nullable OrderBy orderBy,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		return hierarchyOfReference(referenceName, null, orderBy, requirement);
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyOfReference hierarchyOfReference(
		@Nullable String[] referenceName,
		@Nullable EmptyHierarchicalEntityBehaviour emptyHierarchicalEntityBehaviour,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		if (referenceName == null || ArrayUtils.isEmpty(referenceName)) {
			return null;
		}
		if (ArrayUtils.isEmpty(requirement)) {
			return null;
		}
		return new HierarchyOfReference(
			referenceName,
			ofNullable(emptyHierarchicalEntityBehaviour).orElse(EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY),
			requirement
		);
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyOfReference hierarchyOfReference(
		@Nullable String[] referenceName,
		@Nullable EmptyHierarchicalEntityBehaviour emptyHierarchicalEntityBehaviour,
		@Nullable OrderBy orderBy,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		if (referenceName == null || ArrayUtils.isEmpty(referenceName)) {
			return null;
		}
		if (ArrayUtils.isEmpty(requirement)) {
			return null;
		}
		return new HierarchyOfReference(
			referenceName,
			ofNullable(emptyHierarchicalEntityBehaviour).orElse(EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY),
			orderBy,
			requirement
		);
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyFromRoot fromRoot(
		@Nullable String outputName,
		@Nullable HierarchyOutputRequireConstraint... requirement
	) {
		if (outputName == null) {
			return null;
		} else {
			return requirement == null ? new HierarchyFromRoot(outputName) : new HierarchyFromRoot(outputName, requirement);
		}
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyFromRoot fromRoot(
		@Nullable String outputName,
		@Nullable EntityFetch entityFetch,
		@Nullable HierarchyOutputRequireConstraint... requirement
	) {
		if (outputName == null) {
			return null;
		} else {
			return entityFetch == null ? new HierarchyFromRoot(outputName, requirement) : new HierarchyFromRoot(outputName, entityFetch, requirement);
		}
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyFromNode fromNode(
		@Nullable String outputName,
		@Nonnull HierarchyNode node,
		@Nullable HierarchyOutputRequireConstraint... requirement
	) {
		if (outputName == null) {
			return null;
		} else {
			return requirement == null ?
				new HierarchyFromNode(outputName, node) :
				new HierarchyFromNode(outputName, node, requirement);
		}
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyFromNode fromNode(
		@Nullable String outputName,
		@Nonnull HierarchyNode node,
		@Nullable EntityFetch entityFetch,
		@Nullable HierarchyOutputRequireConstraint... requirement
	) {
		if (outputName == null) {
			return null;
		} else {
			return entityFetch == null ?
				new HierarchyFromNode(outputName, node, requirement) :
				new HierarchyFromNode(outputName, node, entityFetch, requirement);
		}
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyChildren children(
		@Nullable String outputName,
		@Nullable EntityFetch entityFetch,
		@Nullable HierarchyOutputRequireConstraint... requirement
	) {
		if (outputName == null) {
			return null;
		} else {
			return new HierarchyChildren(outputName, entityFetch, requirement);
		}
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyChildren children(
		@Nullable String outputName,
		@Nullable HierarchyOutputRequireConstraint... requirement
	) {
		if (outputName == null) {
			return null;
		} else {
			return new HierarchyChildren(outputName, requirement);
		}
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchySiblings siblings(
		@Nullable String outputName,
		@Nullable EntityFetch entityFetch,
		@Nullable HierarchyOutputRequireConstraint... requirements
	) {
		if (outputName == null) {
			return null;
		} else {
			return new HierarchySiblings(outputName, entityFetch, requirements);
		}
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchySiblings siblings(
		@Nullable String outputName,
		@Nullable HierarchyOutputRequireConstraint... requirements
	) {
		if (outputName == null) {
			return null;
		} else {
			return new HierarchySiblings(outputName, requirements);
		}
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchySiblings siblings(
		@Nullable EntityFetch entityFetch,
		@Nullable HierarchyOutputRequireConstraint... requirements
	) {
		return new HierarchySiblings(null, entityFetch, requirements);
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchySiblings siblings(@Nullable HierarchyOutputRequireConstraint... requirements) {
		return new HierarchySiblings(null, requirements);
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyParents parents(
		@Nullable String outputName,
		@Nullable EntityFetch entityFetch,
		@Nullable HierarchyOutputRequireConstraint... requirements
	) {
		if (outputName == null) {
			return null;
		} else {
			return new HierarchyParents(outputName, entityFetch, requirements);
		}
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyParents parents(
		@Nullable String outputName,
		@Nullable EntityFetch entityFetch,
		@Nullable HierarchySiblings siblings,
		@Nullable HierarchyOutputRequireConstraint... requirements
	) {
		if (outputName == null) {
			return null;
		} else if (siblings == null) {
			return entityFetch == null ?
				new HierarchyParents(outputName, requirements) : new HierarchyParents(outputName, entityFetch, requirements);
		} else {
			return entityFetch == null ?
				new HierarchyParents(outputName, siblings, requirements) : new HierarchyParents(outputName, entityFetch, siblings, requirements);
		}
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyParents parents(
		@Nullable String outputName,
		@Nullable HierarchyOutputRequireConstraint... requirements
	) {
		if (outputName == null) {
			return null;
		} else {
			return new HierarchyParents(outputName, requirements);
		}
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyParents parents(
		@Nullable String outputName,
		@Nullable HierarchySiblings siblings,
		@Nullable HierarchyOutputRequireConstraint... requirements
	) {
		if (outputName == null) {
			return null;
		} else {
			return siblings == null ?
				new HierarchyParents(outputName, requirements) :
				new HierarchyParents(outputName, siblings, requirements);
		}
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyStopAt stopAt(@Nullable HierarchyStopAtRequireConstraint stopConstraint) {
		return stopConstraint == null ? null : new HierarchyStopAt(stopConstraint);
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyNode node(@Nullable FilterBy filterBy) {
		return filterBy == null ? null : new HierarchyNode(filterBy);
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyLevel level(@Nullable Integer level) {
		return level == null ? null : new HierarchyLevel(level);
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyDistance distance(@Nullable Integer distance) {
		return distance == null ? null : new HierarchyDistance(distance);
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyStatistics statistics(@Nullable StatisticsType... type) {
		return type == null ?
			new HierarchyStatistics(StatisticsBase.WITHOUT_USER_FILTER) :
			new HierarchyStatistics(StatisticsBase.WITHOUT_USER_FILTER, type);
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nullable
	static HierarchyStatistics statistics(@Nullable StatisticsBase base, @Nullable StatisticsType... type) {
		if (base == null) {
			return null;
		} else {
			return type == null ? new HierarchyStatistics(base) : new HierarchyStatistics(base, type);
		}
	}

	// TOBEDONE JNO: docs
	@Nonnull
	static EntityFetch entityFetch(@Nullable EntityContentRequire... requirements) {
		if (requirements == null) {
			return new EntityFetch();
		}
		return new EntityFetch(requirements);
	}

	// TOBEDONE JNO: docs
	@Nonnull
	static EntityGroupFetch entityGroupFetch(@Nullable EntityContentRequire... requirements) {
		if (requirements == null) {
			return new EntityGroupFetch();
		}
		return new EntityGroupFetch(requirements);
	}

	/**
	 * This `attributes` requirement changes default behaviour of the query engine returning only entity primary keys in the result. When
	 * this requirement is used result contains [entity bodies](entity_model.md) except `associated data` that could
	 * become big. These type of data can be fetched either lazily or by specifying additional requirements in the query.
	 *
	 * This requirement implicitly triggers fetching of entity body because attributes cannot be returned without entity.
	 * [Localized interface](classes/localized_interface.md) attributes are returned according to {@link EntityLocaleEquals}
	 * query.
	 *
	 * Example:
	 *
	 * ```
	 * attributes()
	 * ```
	 */
	@Nonnull
	static AttributeContent attributeContent(@Nullable String... attributeName) {
		if (attributeName == null) {
			return new AttributeContent();
		}
		return new AttributeContent(attributeName);
	}

	/**
	 * This `associatedData` requirement changes default behaviour of the query engine returning only entity primary keys in the result. When
	 * this requirement is used result contains [entity bodies](entity_model.md) along with associated data with names specified in
	 * one or more arguments of this requirement.
	 *
	 * This requirement implicitly triggers fetching of entity body because attributes cannot be returned without entity.
	 * [Localized interface](classes/localized_interface.md) associated data is returned according to {@link EntityLocaleEquals}
	 * query. requirement might be combined with {@link AttributeContent} requirement.
	 *
	 * Example:
	 *
	 * ```
	 * associatedData("description", 'gallery-3d')
	 * ```
	 */
	@Nonnull
	static AssociatedDataContent associatedDataContent(@Nullable String... associatedDataName) {
		if (associatedDataName == null) {
			return new AssociatedDataContent();
		}
		return new AssociatedDataContent(associatedDataName);
	}

	/**
	 * This `dataInLocales` query is require query that accepts zero or more {@link Locale} arguments. When this
	 * require query is used, result contains [entity attributes and associated data](../model/entity_model.md)
	 * localized in required languages as well as global ones. If query contains no argument, data localized to all
	 * languages are returned. If query is not present in the query, only global attributes and associated data are
	 * returned.
	 *
	 * **Note:** if {@link EntityLocaleEquals}is used in the filter part of the query and `dataInLanguage`
	 * require query is missing, the system implicitly uses `dataInLanguage` matching the language in filter query.
	 *
	 * Only single `language` query can be used in the query.
	 *
	 * Example that fetches only global and `en-US` localized attributes and associated data (considering there are multiple
	 * language localizations):
	 *
	 * ```
	 * dataInLocales('en-US')
	 * ```
	 *
	 * Example that fetches all available global and localized data:
	 *
	 * ```
	 * dataInLocales()
	 * ```
	 */
	@Nonnull
	static DataInLocales dataInLocales(@Nullable Locale... locale) {
		if (locale == null) {
			return new DataInLocales();
		}
		return new DataInLocales(locale);
	}

	/**
	 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entites
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * This requirement implicitly triggers fetching of entity body because references cannot be returned without entity.
	 *
	 * Example:
	 *
	 * ```
	 * references(CATEGORY, "stocks")
	 * ```
	 */
	@Nonnull
	static ReferenceContent referenceContent() {
		return new ReferenceContent();
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String referencedEntityType) {
		if (referencedEntityType == null) {
			return new ReferenceContent();
		}
		return new ReferenceContent(referencedEntityType);
	}

	/**
	 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entites
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * This requirement implicitly triggers fetching of entity body because references cannot be returned without entity.
	 *
	 * Example:
	 *
	 * ```
	 * references(CATEGORY, "stocks")
	 * ```
	 */
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String... referencedEntityType) {
		if (referencedEntityType == null) {
			return new ReferenceContent();
		}
		return new ReferenceContent(referencedEntityType);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String referencedEntityType, @Nullable EntityFetch entityRequirement) {
		if (referencedEntityType == null && entityRequirement == null) {
			return new ReferenceContent();
		}
		if (referencedEntityType == null) {
			return new ReferenceContent(entityRequirement);
		}
		if (entityRequirement == null) {
			return new ReferenceContent(referencedEntityType);
		}
		return new ReferenceContent(referencedEntityType, entityRequirement);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String referencedEntityType, @Nullable EntityGroupFetch groupEntityRequirement) {
		if (referencedEntityType == null && groupEntityRequirement == null) {
			return new ReferenceContent();
		}
		if (referencedEntityType == null) {
			return new ReferenceContent(groupEntityRequirement);
		}
		if (groupEntityRequirement == null) {
			return new ReferenceContent(referencedEntityType);
		}
		return new ReferenceContent(referencedEntityType, groupEntityRequirement);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String referencedEntityType, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		if (referencedEntityType == null) {
			return new ReferenceContent(entityRequirement, groupEntityRequirement);
		}
		return new ReferenceContent(referencedEntityType, entityRequirement, groupEntityRequirement);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String[] referencedEntityTypes, @Nullable EntityFetch entityRequirement) {
		if (referencedEntityTypes == null && entityRequirement == null) {
			return new ReferenceContent();
		}
		if (referencedEntityTypes == null) {
			return new ReferenceContent(entityRequirement);
		}
		if (entityRequirement == null) {
			return new ReferenceContent(referencedEntityTypes);
		}
		return new ReferenceContent(
			referencedEntityTypes,
			entityRequirement
		);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String[] referencedEntityTypes, @Nullable EntityGroupFetch groupEntityRequirement) {
		if (referencedEntityTypes == null && groupEntityRequirement == null) {
			return new ReferenceContent();
		}
		if (referencedEntityTypes == null) {
			return new ReferenceContent(groupEntityRequirement);
		}
		if (groupEntityRequirement == null) {
			return new ReferenceContent(referencedEntityTypes);
		}
		return new ReferenceContent(
			referencedEntityTypes,
			groupEntityRequirement
		);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String[] referencedEntityTypes, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		if (referencedEntityTypes != null) {
			return new ReferenceContent(referencedEntityTypes, entityRequirement, groupEntityRequirement);
		} else {
			return new ReferenceContent(entityRequirement, groupEntityRequirement);
		}
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy) {
		if (filterBy == null) {
			return new ReferenceContent(referenceName);
		}
		return new ReferenceContent(referenceName, filterBy);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable EntityFetch entityRequirement) {
		if (filterBy == null) {
			return referenceContent(referenceName, entityRequirement);
		}
		if (entityRequirement == null) {
			return new ReferenceContent(referenceName, filterBy);
		}
		return new ReferenceContent(
			referenceName,
			filterBy,
			entityRequirement
		);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable EntityGroupFetch groupEntityRequirement) {
		if (filterBy == null) {
			return referenceContent(referenceName, groupEntityRequirement);
		}
		if (groupEntityRequirement == null) {
			return new ReferenceContent(referenceName, filterBy);
		}
		return new ReferenceContent(
			referenceName,
			filterBy,
			groupEntityRequirement
		);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		if (filterBy == null) {
			return new ReferenceContent(referenceName, entityRequirement, groupEntityRequirement);
		}
		return new ReferenceContent(referenceName, filterBy, entityRequirement, groupEntityRequirement);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable OrderBy orderBy) {
		if (orderBy == null) {
			return new ReferenceContent(referenceName);
		}
		return new ReferenceContent(referenceName, orderBy);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement) {
		if (orderBy == null) {
			return referenceContent(referenceName, entityRequirement);
		}
		if (entityRequirement == null) {
			return new ReferenceContent(referenceName, orderBy);
		}
		return new ReferenceContent(
			referenceName,
			orderBy,
			entityRequirement
		);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable OrderBy orderBy, @Nullable EntityGroupFetch groupEntityRequirement) {
		if (orderBy == null) {
			return referenceContent(referenceName, groupEntityRequirement);
		}
		if (groupEntityRequirement == null) {
			return new ReferenceContent(referenceName, orderBy);
		}
		return new ReferenceContent(
			referenceName,
			orderBy,
			groupEntityRequirement
		);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		if (orderBy == null) {
			return new ReferenceContent(referenceName, entityRequirement, groupEntityRequirement);
		}
		return new ReferenceContent(referenceName, orderBy, entityRequirement, groupEntityRequirement);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy) {
		if (filterBy == null && orderBy == null) {
			return new ReferenceContent(referenceName);
		}
		if (filterBy == null) {
			return referenceContent(referenceName, orderBy);
		}
		if (orderBy == null) {
			return referenceContent(referenceName, filterBy);
		}
		return new ReferenceContent(referenceName, filterBy, orderBy);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement) {
		if (filterBy == null && orderBy == null) {
			return referenceContent(referenceName, entityRequirement);
		}
		if (filterBy == null) {
			return referenceContent(referenceName, orderBy, entityRequirement);
		}
		if (orderBy == null) {
			return referenceContent(referenceName, filterBy, entityRequirement);
		}
		if (entityRequirement == null) {
			return referenceContent(referenceName, filterBy, orderBy);
		}
		return new ReferenceContent(
			referenceName,
			filterBy,
			orderBy,
			entityRequirement
		);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable EntityGroupFetch groupEntityRequirement) {
		if (filterBy == null && orderBy == null) {
			return referenceContent(referenceName, groupEntityRequirement);
		}
		if (filterBy == null) {
			return referenceContent(referenceName, orderBy, groupEntityRequirement);
		}
		if (orderBy == null) {
			return referenceContent(referenceName, filterBy, groupEntityRequirement);
		}
		if (groupEntityRequirement == null) {
			return referenceContent(referenceName, filterBy, orderBy);
		}
		return new ReferenceContent(
			referenceName,
			filterBy,
			orderBy,
			groupEntityRequirement
		);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		if (filterBy == null && orderBy == null) {
			return referenceContent(referenceName, entityRequirement, groupEntityRequirement);
		}
		if (filterBy == null) {
			return referenceContent(referenceName, orderBy, entityRequirement, groupEntityRequirement);
		}
		if (orderBy == null) {
			return referenceContent(referenceName, filterBy, entityRequirement, groupEntityRequirement);
		}
		return new ReferenceContent(referenceName, filterBy, orderBy, entityRequirement, groupEntityRequirement);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static ReferenceContent referenceContent(@Nullable EntityFetch entityRequirement) {
		return referenceContent((String) null, entityRequirement);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static ReferenceContent referenceContent(@Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceContent((String) null, groupEntityRequirement);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static ReferenceContent referenceContent(@Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(entityRequirement, groupEntityRequirement);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static HierarchyContent hierarchyContent() {
		return new HierarchyContent();
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static HierarchyContent hierarchyContent(@Nullable HierarchyStopAt stopAt) {
		return stopAt == null ? new HierarchyContent() : new HierarchyContent(stopAt);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static HierarchyContent hierarchyContent(@Nullable EntityFetch entityFetch) {
		return entityFetch == null ? new HierarchyContent() : new HierarchyContent(entityFetch);
	}

	// TOBEDONE JNO: add docs after docs revision
	@Nonnull
	static HierarchyContent hierarchyContent(@Nullable HierarchyStopAt stopAt, @Nullable EntityFetch entityFetch) {
		if (stopAt == null && entityFetch == null) {
			return new HierarchyContent();
		} else if (entityFetch != null) {
			return stopAt == null ? new HierarchyContent(entityFetch) : new HierarchyContent(stopAt, entityFetch);
		} else {
			return new HierarchyContent(stopAt);
		}
	}

	/**
	 * This `prices` requirement changes default behaviour of the query engine returning only entity primary keys in the result. When
	 * this requirement is used result contains [entity prices](entity_model.md).
	 *
	 * This requirement implicitly triggers fetching of entity body because prices cannot be returned without entity.
	 * When price constraints are used returned prices are filtered according to them by default. This behaviour might be
	 * changed, however.
	 *
	 * Accepts single {@link PriceContentMode} parameter. When {@link PriceContentMode#ALL} all prices of the entity are returned
	 * regardless of the input query constraints otherwise prices are filtered by those constraints. Default is {@link PriceContentMode#RESPECTING_FILTER}.
	 *
	 * Example:
	 *
	 * ```
	 * prices() // defaults to respecting filter
	 * ```
	 * ```
	 * allPrices()
	 * ```
	 */
	@Nonnull
	static PriceContent priceContent(@Nullable String... priceLists) {
		if (ArrayUtils.isEmpty(priceLists)) {
			return new PriceContent(PriceContentMode.RESPECTING_FILTER);
		} else {
			return new PriceContent(PriceContentMode.RESPECTING_FILTER, priceLists);
		}
	}

	/**
	 * This `prices` requirement changes default behaviour of the query engine returning only entity primary keys in the result. When
	 * this requirement is used result contains [entity prices](entity_model.md).
	 *
	 * This requirement implicitly triggers fetching of entity body because prices cannot be returned without entity.
	 * When price constraints are used returned prices are filtered according to them by default. This behaviour might be
	 * changed however.
	 *
	 * Accepts single {@link PriceContentMode} parameter. When {@link PriceContentMode#ALL} all prices of the entity are returned
	 * regardless of the input query constraints otherwise prices are filtered by those constraints. Default is {@link PriceContentMode#RESPECTING_FILTER}.
	 *
	 * Example:
	 *
	 * ```
	 * prices() // defaults to respecting filter
	 * ```
	 * ```
	 * allPrices()
	 * ```
	 */
	@Nonnull
	static PriceContent priceContentAll() {
		return new PriceContent(PriceContentMode.ALL);
	}

	/**
	 * This `useOfPrice` require query can be used to control the form of prices that will be used for computation in
	 * {@link io.evitadb.api.query.filter.PriceBetween} filtering, and {@link PriceNatural},
	 * ordering. Also {@link PriceHistogram} is sensitive to this setting.
	 * <p>
	 * By default, end customer form of price (e.g. price with tax) is used in all above-mentioned constraints. This could
	 * be changed by using this requirement query. It has single argument that can have one of the following values:
	 * <p>
	 * - WITH_TAX
	 * - WITHOUT_TAX
	 * <p>
	 * Example:
	 * <p>
	 * ```
	 * useOfPrice(WITH_TAX)
	 * ```
	 */
	@Nonnull
	static PriceType priceType(@Nonnull QueryPriceMode priceMode) {
		return new PriceType(priceMode);
	}

	/**
	 * This `page` query controls count of the entities in the query output. It allows specifying 2 arguments in following order:
	 *
	 * - **[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) pageNumber**: number of the page of
	 * results that are expected to be returned, starts with 1, must be greater than zero (mandatory)
	 * - **[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) pageSize**: number of entities on
	 * a single page, must be greater than zero (mandatory)
	 *
	 * Example - return first page with 24 items:
	 *
	 * ```
	 * page(1, 24)
	 * ```
	 */
	@Nonnull
	static Page page(@Nullable Integer pageNumber, @Nullable Integer pageSize) {
		return new Page(pageNumber, pageSize);
	}

	/**
	 * This `strip` query controls count of the entities in the query output. It allows specifying 2 arguments in following order:
	 *
	 * - **[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) offset**: number of the items that
	 * should be omitted in the result, must be greater than or equals to zero (mandatory)
	 * - **[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) limit**: number of entities on
	 * that should be returned, must be greater than zero (mandatory)
	 *
	 * Example - return 24 records from index 52:
	 *
	 * ```
	 * strip(52, 24)
	 * ```
	 */
	@Nonnull
	static Strip strip(@Nullable Integer offset, @Nullable Integer limit) {
		return new Strip(offset, limit);
	}

	/**
	 * This `facetSummary` requirement usage triggers computing and adding an object to the result index. It cooperates
	 * with {@link HierarchyWithin} filtering query that must be used in the query as well. The object is quite
	 * complex but allows rendering entire facet listing to e-commerce users. It contains information about all facets
	 * present in current hierarchy view along with count of requested entities that have those facets assigned.
	 *
	 * Facet summary respects current query filtering constraints excluding the conditions inside {@link UserFilter}
	 * container query.
	 *
	 * When this requirement is used an additional object {@link FacetSummary} is stored to result.
	 *
	 * Optionally accepts single enum argument:
	 *
	 * - COUNT: only counts of facets will be computed
	 * - IMPACT: counts and selection impact for non-selected facets will be computed
	 *
	 * Example:
	 *
	 * ```
	 * facetSummary()
	 * facetSummary(COUNT) //same as previous row - default
	 * facetSummary(IMPACT)
	 * ```
	 */
	@Nonnull
	static FacetSummary facetSummary() {
		return new FacetSummary();
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nonnull
	static FacetSummary facetSummary(@Nullable FacetStatisticsDepth statisticsDepth, @Nullable EntityRequire... requirements) {
		return statisticsDepth == null ?
			new FacetSummary(FacetStatisticsDepth.COUNTS, requirements) :
			new FacetSummary(statisticsDepth, requirements);
	}

	// TOBEDONE JNO: docs
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy facetFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable EntityRequire... requirements
	) {
		return facetSummary(statisticsDepth, facetFilterBy, null, facetOrderBy, null, requirements);
	}

	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityRequire... requirements
	) {
		return facetSummary(statisticsDepth, null, facetGroupFilterBy, null, facetGroupOrderBy, requirements);
	}

	// TOBEDONE JNO: docs
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy facetFilterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityRequire... requirements
	) {
		if (statisticsDepth == null) {
			statisticsDepth = FacetStatisticsDepth.COUNTS;
		}
		if (ArrayUtils.isEmpty(requirements)) {
			return new FacetSummary(
				statisticsDepth,
				facetFilterBy, facetGroupFilterBy,
				facetOrderBy, facetGroupOrderBy
			);
		}
		return new FacetSummary(
			statisticsDepth,
			facetFilterBy, facetGroupFilterBy,
			facetOrderBy, facetGroupOrderBy,
			requirements
		);
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nonnull
	static FacetSummaryOfReference facetSummaryOfReference(@Nonnull String referenceName, @Nullable EntityRequire... requirements) {
		return new FacetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, requirements);
	}

	/**
	 * TOBEDONE JNO: docs
	 */
	@Nonnull
	static FacetSummaryOfReference facetSummaryOfReference(@Nonnull String referenceName, @Nullable FacetStatisticsDepth statisticsDepth, @Nullable EntityRequire... requirements) {
		return statisticsDepth == null ?
			new FacetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, requirements) :
			new FacetSummaryOfReference(referenceName, statisticsDepth, requirements);
	}

	// TOBEDONE JNO: docs
	@Nonnull
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy facetFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable EntityRequire... requirements
	) {
		return facetSummaryOfReference(referenceName, statisticsDepth, facetFilterBy, null, facetOrderBy, null, requirements);
	}

	@Nonnull
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityRequire... requirements
	) {
		return facetSummaryOfReference(referenceName, statisticsDepth, null, facetGroupFilterBy, null, facetGroupOrderBy, requirements);
	}

	// TOBEDONE JNO: docs
	@Nonnull
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy facetFilterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityRequire... requirements
	) {
		if (statisticsDepth == null) {
			statisticsDepth = FacetStatisticsDepth.COUNTS;
		}
		if (ArrayUtils.isEmpty(requirements)) {
			return new FacetSummaryOfReference(
				referenceName, statisticsDepth,
				facetFilterBy, facetGroupFilterBy,
				facetOrderBy, facetGroupOrderBy
			);
		}
		return new FacetSummaryOfReference(
			referenceName,
			statisticsDepth,
			facetFilterBy, facetGroupFilterBy,
			facetOrderBy, facetGroupOrderBy,
			requirements
		);
	}

	/**
	 * This `queryTelemetry` requirement triggers creation of the {@link QueryTelemetry} DTO and including it the evitaDB
	 * response.
	 *
	 * Example:
	 *
	 * ```
	 * queryTelemetry()
	 * ```
	 */
	static QueryTelemetry queryTelemetry() {
		return new QueryTelemetry();
	}

	/**
	 * This `debug` requirement triggers special query evaluation behaviour that allows checking consistency
	 * of the result among different indexes or cached variants.
	 *
	 * Example:
	 *
	 * ```
	 * debug(VERIFY_ALTERNATIVE_INDEX_RESULTS, VERIFY_POSSIBLE_CACHING_TREES)
	 * ```
	 *
	 * @see DebugMode for more information
	 */
	static Debug debug(@Nonnull DebugMode... debugMode) {
		return new Debug(debugMode);
	}

	/**
	 * This method returns array of all requirements that are necessary to load full content of the entity including
	 * all language specific attributes, all prices, all references and all associated data.
	 */
	@Nonnull
	static EntityFetch entityFetchAll() {
		return entityFetch(
			attributeContent(), hierarchyContent(), associatedDataContent(), priceContentAll(), referenceContent(), dataInLocales()
		);
	}

	/**
	 * This method returns array of all requirements that are necessary to load full content of the entity including
	 * all language specific attributes, all prices, all references and all associated data.
	 */
	@Nonnull
	static RequireConstraint[] entityFetchAllAnd(@Nonnull RequireConstraint... combineWith) {
		if (ArrayUtils.isEmpty(combineWith)) {
			return new RequireConstraint[]{entityFetchAll()};
		} else {
			return ArrayUtils.mergeArrays(
				new RequireConstraint[]{
					entityFetchAll()
				},
				combineWith
			);
		}
	}

	/**
	 * This method returns array of all requirements that are necessary to load full content of the entity including
	 * all language specific attributes, all prices, all references and all associated data.
	 */
	@Nonnull
	static EntityContentRequire[] entityFetchAllContent() {
		return new EntityContentRequire[]{
			attributeContent(), associatedDataContent(), priceContentAll(), referenceContent(), dataInLocales()
		};
	}

	/**
	 * This method returns array of all requirements that are necessary to load full content of the entity including
	 * all language specific attributes, all prices, all references and all associated data.
	 */
	@Nonnull
	static EntityContentRequire[] entityFetchAllContentAnd(@Nullable EntityContentRequire... combineWith) {
		if (ArrayUtils.isEmpty(combineWith)) {
			return entityFetchAllContent();
		} else {
			return ArrayUtils.mergeArrays(
				entityFetchAllContent(),
				combineWith
			);
		}
	}

}
