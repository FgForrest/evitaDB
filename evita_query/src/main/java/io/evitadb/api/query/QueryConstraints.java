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
import io.evitadb.api.query.order.*;
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
	 * Each query must specify collection. This mandatory {@link Serializable} query controls what collection
	 * the query will be applied on.
	 *
	 * Sample of the header is:
	 *
	 * ```
	 * collection('category')
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
	 *     and(
	 *        isNotNull('code'),
	 *        or(
	 *           equals('code', 'ABCD'),
	 *           startsWith('title', 'Knife')
	 *        )
	 *     )
	 * )
	 * ```
	*/
	@Nullable
	static FilterBy filterBy(@Nullable FilterConstraint... constraint) {
		return constraint == null ? null : new FilterBy(constraint);
	}

	/**
	 * TOBEDONE JNO - document me
	*/
	@Nullable
	static FilterGroupBy filterGroupBy(@Nullable FilterConstraint... constraint) {
		return constraint == null ? null : new FilterGroupBy(constraint);
	}

	/**
	 * The `and` container represents a <a href="https://en.wikipedia.org/wiki/Logical_conjunction">logical conjunction</a>,
	 * that is demonstrated on following table:
	 *
	 * <table>
	 *     <thead>
	 *         <tr>
	 *             <th align="center">A</th>
	 *             <th align="center">B</th>
	 *             <th align="center">A ∧ B</th>
	 *         </tr>
	 *     </thead>
	 *     <tbody>
	 *         <tr>
	 *             <td align="center">True</td>
	 *             <td align="center">True</td>
	 *             <td align="center">True</td>
	 *         </tr>
	 *         <tr>
	 *             <td align="center">True</td>
	 *             <td align="center">False</td>
	 *             <td align="center">False</td>
	 *         </tr>
	 *         <tr>
	 *             <td align="center">False</td>
	 *             <td align="center">True</td>
	 *             <td align="center">False</td>
	 *         </tr>
	 *         <tr>
	 *             <td align="center">False</td>
	 *             <td align="center">False</td>
	 *             <td align="center">False</td>
	 *         </tr>
	 *     </tbody>
	 * </table>
	 *
	 * The following query:
	 *
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         and(
	 *             entityPrimaryKeyInSet(110066, 106742, 110513),
	 *             entityPrimaryKeyInSet(110066, 106742),
	 *             entityPrimaryKeyInSet(107546, 106742,  107546)
	 *         )
	 *     )
	 * )
	 * </pre>
	 *
	 * ... returns a single result - product with entity primary key 106742, which is the only one that all three
	 * `entityPrimaryKeyInSet` constraints have in common.
	*/
	@Nullable
	static And and(@Nullable FilterConstraint... constraints) {
		if (constraints == null) {
			return null;
		}
		return new And(constraints);
	}

	/**
	 * The `and` container represents a <a href="https://en.wikipedia.org/wiki/Logical_conjunction">logical conjunction</a>,
	 * that is demonstrated on following table:
	 *
	 * <table>
	 *     <thead>
	 *         <tr>
	 *             <th align="center">A</th>
	 *             <th align="center">B</th>
	 *             <th align="center">A ∨ B</th>
	 *         </tr>
	 *     </thead>
	 *     <tbody>
	 *         <tr>
	 *             <td align="center">True</td>
	 *             <td align="center">True</td>
	 *             <td align="center">True</td>
	 *         </tr>
	 *         <tr>
	 *             <td align="center">True</td>
	 *             <td align="center">False</td>
	 *             <td align="center">True</td>
	 *         </tr>
	 *         <tr>
	 *             <td align="center">False</td>
	 *             <td align="center">True</td>
	 *             <td align="center">True</td>
	 *         </tr>
	 *         <tr>
	 *             <td align="center">False</td>
	 *             <td align="center">False</td>
	 *             <td align="center">False</td>
	 *         </tr>
	 *     </tbody>
	 * </table>
	 *
	 * The following query:
	 *
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         and(
	 *             entityPrimaryKeyInSet(110066, 106742, 110513),
	 *             entityPrimaryKeyInSet(110066, 106742),
	 *             entityPrimaryKeyInSet(107546, 106742,  107546)
	 *         )
	 *     )
	 * )
	 * </pre>
	 *
	 * ... returns four results representing a combination of all primary keys used in the `entityPrimaryKeyInSet`
	 * constraints.
	*/
	@Nullable
	static Or or(@Nullable FilterConstraint... constraints) {
		if (constraints == null) {
			return null;
		}
		return new Or(constraints);
	}

	/**
	 * The `not` container represents a <a href="https://en.wikipedia.org/wiki/Negation">logical negation</a>, that is
	 * demonstrated on following table:
	 *
	 * <table>
	 *     <thead>
	 *         <tr>
	 *             <th align="center">A</th>
	 *             <th align="center">¬ A</th>
	 *         </tr>
	 *     </thead>
	 *     <tbody>
	 *         <tr>
	 *             <td align="center">True</td>
	 *             <td align="center">False</td>
	 *         </tr>
	 *         <tr>
	 *             <td align="center">False</td>
	 *             <td align="center">True</td>
	 *         </tr>
	 *     </tbody>
	 * </table>
	 *
	 * The following query:
	 *
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         not(
	 *             entityPrimaryKeyInSet(110066, 106742, 110513)
	 *         )
	 *     )
	 * )
	 * </pre>
	 *
	 * ... returns thousands of results excluding the entities with primary keys mentioned in `entityPrimaryKeyInSet`
	 * constraint. Because this situation is hard to visualize - let's narrow our super set to only a few entities:
	 *
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         entityPrimaryKeyInSet(110513, 66567, 106742, 66574, 66556, 110066),
	 *         not(
	 *             entityPrimaryKeyInSet(110066, 106742, 110513)
	 *         )
	 *     )
	 * )
	 * </pre>
	 *
	 * ... which returns only three products that were not excluded by the following `not` constraint.
	*/
	@Nullable
	static Not not(@Nullable FilterConstraint constraint) {
		return constraint == null ? null : new Not(constraint);
	}

	/**
	 * This `referenceAttribute` container is filtering query that filters returned entities by their reference
	 * attributes that must match the inner condition.
	 *
	 * Example:
	 *
	 * ```
	 * referenceHavingAttribute(
	 * 'CATEGORY',
	 * eq('code', 'KITCHENWARE')
	 * )
	 * ```
	 *
	 * or
	 *
	 * ```
	 * referenceHavingAttribute(
	 * 'CATEGORY',
	 * and(
	 * isTrue('visible'),
	 * eq('code', 'KITCHENWARE')
	 * )
	 * )
	 * ```
	 *
	 * TOBEDONE JNO - consider renaming to `referenceMatching`
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
	 * greaterThanEq('memory', 8),
	 * priceBetween(150.25, 220.0),
	 * facet('parameter', 4, 15)
	 * )
	 * ```
	 *
	 * Even more complex queries are supported (although it is hard to make up some real life example for such):
	 *
	 * ```
	 * filterBy(
	 * and(
	 * or(
	 * referenceHavingAttribute('CATEGORY', eq(code, 'abc')),
	 * referenceHavingAttribute('STOCK', eq(market, 'asia')),
	 * ),
	 * eq(visibility, true),
	 * userFilter(
	 * or(
	 * and(
	 * greaterThanEq('memory', 8),
	 * priceBetween(150.25, 220.0)
	 * ),
	 * and(
	 * greaterThanEq('memory', 16),
	 * priceBetween(800.0, 1600.0)
	 * ),
	 * ),
	 * facet('parameter', 4, 15)
	 * )
	 * )
	 * ),
	 * require(
	 * facetGroupDisjunction('parameterType', 4),
	 * negatedFacets('parameterType', 8),
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
	 * between('age', 20, 25)
	 * ```
	 *
	 * Function supports attribute arrays and when attribute is of array type `between` returns true if *any of attribute* values
	 * is between the passed interval the value in the query. If we have the attribute `amount` with value `[1, 9]` all
	 * these constraints will match:
	 *
	 * ```
	 * between('amount', 0, 50)
	 * between('amount', 0, 5)
	 * between('amount', 8, 10)
	 * ```
	 *
	 * If attribute is of `Range` type `between` query behaves like overlap - it returns true if examined range and
	 * any of the attribute ranges (see previous paragraph about array types) share anything in common. All of following
	 * constraints return true when we have the attribute `validity` with following `NumberRange` values: `[[2,5],[8,10]]`:
	 *
	 * ```
	 * between(`validity`, 0, 3)
	 * between(`validity`, 0, 100)
	 * between(`validity`, 9, 10)
	 * ```
	 *
	 * ... but these constraints will return false:
	 *
	 * ```
	 * between(`validity`, 11, 15)
	 * between(`validity`, 0, 1)
	 * between(`validity`, 6, 7)
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
	 * contains('code', 'eve')
	 * ```
	 *
	 * Function supports attribute arrays and when attribute is of array type `contains` returns true if *any of attribute* values
	 * contains the value in the query. If we have the attribute `code` with value `['cat','mouse','dog']` all these constraints will
	 * match:
	 *
	 * ```
	 * contains('code','mou')
	 * contains('code','o')
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
	 * startsWith('code', 'vid')
	 * ```
	 *
	 * Function supports attribute arrays and when attribute is of array type `startsWith` returns true if *any of attribute* values
	 * starts with the value in the query. If we have the attribute `code` with value `['cat','mouse','dog']` all these
	 * constraints will match:
	 *
	 * ```
	 * contains('code','mou')
	 * contains('code','do')
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
	 * endsWith('code', 'ida')
	 * ```
	 *
	 * Function supports attribute arrays and when attribute is of array type `endsWith` returns true if *any of attribute* values
	 * ends with the value in the query. If we have the attribute `code` with value `['cat','mouse','dog']` all these
	 * constraints will match:
	 *
	 * ```
	 * contains('code','at')
	 * contains('code','og')
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
	 * equals('code', 'abc')
	 * ```
	 *
	 * Function supports attribute arrays and when attribute is of array type `equals` returns true if *any of attribute* values
	 * equals the value in the query. If we have the attribute `code` with value `['A','B','C']` all these constraints will
	 * match:
	 *
	 * ```
	 * equals('code','A')
	 * equals('code','B')
	 * equals('code','C')
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
	 * Function returns true if value in a filterable attribute of such a name is lesser than value in second argument.
	 *
	 * Function currently doesn't support attribute arrays and when attribute is of array type. Query returns error when this
	 * query is used in combination with array type attribute. This may however change in the future.
	 *
	 * Example:
	 *
	 * ```
	 * lessThan('age', 20)
	 * ```
	 *
	 * TOBEDONE JNO - rename to "lesserThan"
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
	 * Function currently doesn't support attribute arrays and when attribute is of array type. Query returns error when this
	 * query is used in combination with array type attribute. This may however change in the future.
	 *
	 * Example:
	 *
	 * ```
	 * lessThanEquals('age', 20)
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
	 * Function currently doesn't support attribute arrays and when attribute is of array type. Query returns error when this
	 * query is used in combination with array type attribute. This may however change in the future.
	 *
	 * Example:
	 *
	 * ```
	 * greaterThan('age', 20)
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
	 * Function currently doesn't support attribute arrays and when attribute is of array type. Query returns error when this
	 * query is used in combination with array type attribute. This may however change in the future.
	 *
	 * Example:
	 *
	 * ```
	 * greaterThanEquals('age', 20)
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
	 *     priceInCurrency('EUR'),
	 *     priceInPriceLists('basic', 'b2b_discount'),
	 *     priceBetween(800.0, 900.0)
	 * )`
	 *
	 * The product will not be found - because query engine will use first defined price for the price lists in defined order.
	 * It's in our case the price `999.99`, which is not in the defined price interval 800 € - 900 €. If the price lists in
	 * arguments gets switched to `priceInPriceLists('b2b_discount', 'basic')`, the product will be returned, because the first
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
	 * argument that represents [currency](https://en.wikipedia.org/wiki/ISO_4217) in ISO 4217 code or direct {@link Currency}
	 * instance.
	 *
	 * Function returns true if entity has at least one price with specified currency. This function is also affected by
	 * {@link PriceInPriceLists} function that limits the examined prices as well. When this query
	 * is used in the query returned entities will contain only prices matching specified locale. In other words if entity has
	 * two prices: USD and CZK and `priceInCurrency('CZK')` is used in query returned entity would have only Czech crown prices
	 * fetched along with it.
	 *
	 * Only single `priceInCurrency` query can be used in the query. Constraint must be defined when other price related
	 * constraints are used in the query.
	 *
	 * Example:
	 *
	 * ```
	 * priceInCurrency('USD')
	 * ```
	*/
	@Nullable
	static PriceInCurrency priceInCurrency(@Nullable String currency) {
		return currency == null ? null : new PriceInCurrency(currency);
	}

	/**
	 * This `priceInCurrency` is query accepts single {@link String}
	 * argument that represents [currency](https://en.wikipedia.org/wiki/ISO_4217) in ISO 4217 code or direct {@link Currency}
	 * instance.
	 *
	 * Function returns true if entity has at least one price with specified currency. This function is also affected by
	 * {@link PriceInPriceLists} function that limits the examined prices as well. When this query
	 * is used in the query returned entities will contain only prices matching specified locale. In other words if entity has
	 * two prices: USD and CZK and `priceInCurrency('CZK')` is used in query returned entity would have only Czech crown prices
	 * fetched along with it.
	 *
	 * Only single `priceInCurrency` query can be used in the query. Constraint must be defined when other price related
	 * constraints are used in the query.
	 *
	 * Example:
	 *
	 * ```
	 * priceInCurrency('USD')
	 * ```
	*/
	@Nullable
	static PriceInCurrency priceInCurrency(@Nullable Currency currency) {
		return currency == null ? null : new PriceInCurrency(currency);
	}

	/**
	 * This `withinHierarchy` query accepts [Serializable](https://docs.oracle.com/javase/8/docs/api/java/io/Serializable.html)
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
	 * When query `withinHierarchy('category', 1)` is used in a query targeting product entities only products that
	 * relates directly to categories: `TV`, `Crt`, `LCD`, `big`, `small` and `Plasma` will be returned. Products in `Fridges`
	 * will be omitted because they are not in a sub-tree of `TV` hierarchy.
	 *
	 * Only single `withinHierarchy` query can be used in the query.
	 *
	 * Example:
	 *
	 * ```
	 * withinHierarchy('category', 4)
	 * ```
	 *
	 * If you want to query the entity that you're querying on you can also omit entity type specification. See example:
	 *
	 * ```
	 * query(
	 * entities('CATEGORY'),
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
	 * entities('CATEGORY'),
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
	 * entities('PRODUCT'),
	 * filterBy(
	 * withinRootHierarchy('CATEGORY')
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
	 * This `withinHierarchy` query accepts [Serializable](https://docs.oracle.com/javase/8/docs/api/java/io/Serializable.html)
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
	 * When query `withinHierarchy('category', 1)` is used in a query targeting product entities only products that
	 * relates directly to categories: `TV`, `Crt`, `LCD`, `big`, `small` and `Plasma` will be returned. Products in `Fridges`
	 * will be omitted because they are not in a sub-tree of `TV` hierarchy.
	 *
	 * Only single `withinHierarchy` query can be used in the query.
	 *
	 * Example:
	 *
	 * ```
	 * withinHierarchy('category', 4)
	 * ```
	 *
	 * If you want to query the entity that you're querying on you can also omit entity type specification. See example:
	 *
	 * ```
	 * query(
	 * entities('CATEGORY'),
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
	 * entities('CATEGORY'),
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
	 * entities('PRODUCT'),
	 * filterBy(
	 * withinRootHierarchy('CATEGORY')
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
	 * This `withinRootHierarchy` query accepts [Serializable](https://docs.oracle.com/javase/8/docs/api/java/io/Serializable.html)
	 * entity type in first argument. There are also optional second and third arguments - see optional arguments {@link HierarchyDirectRelation},
	 * and {@link HierarchyExcluding}.
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
	 * When query `withinRootHierarchy('category')` is used in a query targeting product entities all products that
	 * relates to any of categories will be returned.
	 *
	 * Only single `withinRootHierarchy` query can be used in the query.
	 *
	 * Example:
	 *
	 * ```
	 * withinRootHierarchy('category')
	 * ```
	 *
	 * If you want to query the entity that you're querying on you can also omit entity type specification. See example:
	 *
	 * ```
	 * query(
	 * entities('CATEGORY'),
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
	 * entities('PRODUCT'),
	 * filterBy(
	 * withinRootHierarchy('CATEGORY')
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
	 * This `withinRootHierarchy` query accepts [Serializable](https://docs.oracle.com/javase/8/docs/api/java/io/Serializable.html)
	 * entity type in first argument. There are also optional second and third arguments - see optional arguments {@link HierarchyDirectRelation},
	 * and {@link HierarchyExcluding}.
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
	 * When query `withinRootHierarchy('category')` is used in a query targeting product entities all products that
	 * relates to any of categories will be returned.
	 *
	 * Only single `withinRootHierarchy` query can be used in the query.
	 *
	 * Example:
	 *
	 * ```
	 * withinRootHierarchy('category')
	 * ```
	 *
	 * If you want to query the entity that you're querying on you can also omit entity type specification. See example:
	 *
	 * ```
	 * query(
	 * entities('CATEGORY'),
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
	 * entities('PRODUCT'),
	 * filterBy(
	 * withinRootHierarchy('CATEGORY')
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

	/**
	 * TOBEDONE JNO - write javadoc
	*/
	@Nullable
	static HierarchyHaving having(@Nullable FilterConstraint... includeChildTreeConstraints) {
		if (ArrayUtils.isEmpty(includeChildTreeConstraints)) {
			return null;
		}
		return new HierarchyHaving(includeChildTreeConstraints);
	}

	/**
	 * If you use {@link HierarchyExcludingRoot} sub-query in {@link HierarchyWithin} parent, you can specify one or more
	 * Integer primary keys of the underlying entities which hierarchical subtree should be excluded from examination.
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
	 * When query `withinHierarchy('category', 1, excluding(3))` is used in a query targeting product entities,
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
	 *     - Crt (2)
	 *     - LCD (3)
	 *        - AMOLED (4)
	 *
	 * These categories are related by following products:
	 *
	 * - TV (1):
	 *     - Product Philips 32"
	 *     - Product Samsung 24"
	 *     - Crt (2):
	 *         - Product Ilyiama 15"
	 *         - Product Panasonic 17"
	 *     - LCD (3):
	 *         - Product BenQ 32"
	 *         - Product LG 28"
	 *         - AMOLED (4):
	 *             - Product Samsung 32"
	 *
	 * When using this query:
	 *
	 * ```
	 * query(
	 *    entities('PRODUCT'),
	 *    filterBy(
	 *       withinHierarchy('CATEGORY', 1)
	 *    )
	 * )
	 * ```
	 *
	 * All products will be returned.
	 *
	 * When this query is used:
	 *
	 * ```
	 * query(
	 *    entities('PRODUCT'),
	 *    filterBy(
	 *       withinHierarchy('CATEGORY', 1, directRelation())
	 *    )
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
	 *    entities('CATEGORY'),
	 *    filterBy(
	 *       withinHierarchy(1)
	 *    )
	 * )
	 * ```
	 *
	 * All categories under the category subtree of `TV (1)` will be listed (this means categories `TV`, `Crt`, `LCD`, `AMOLED`).
	 * If you use this query:
	 *
	 * ```
	 * query(
	 *    entities('CATEGORY'),
	 *    filterBy(
	 *       withinHierarchy(1, directRelation())
	 *    )
	 * )
	 * ```
	 *
	 * Only direct sub-categories of category `TV (1)` will be listed (this means categories `Crt` and `LCD`).
	 * You can also use this hint with query `withinRootHierarchy`:
	 *
	 * ```
	 * query(
	 *    entities('CATEGORY'),
	 *    filterBy(
	 *       withinRootHierarchy()
	 *    )
	 * )
	 * ```
	 *
	 * All categories in entire tree will be listed.
	 *
	 * When using this query:
	 *
	 * ```
	 * query(
	 *    entities('CATEGORY'),
	 *    filterBy(
	 *       withinHierarchy(directRelation())
	 *    )
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
	 * entity specified in `withinHierarchy` or entities related to those children entities - if the `withinHierarchy` targets
	 * different entity types.
	 *
	 * Let's have following category tree:
	 *
	 * - TV (1)
	 *     - Crt (2)
	 *     - LCD (3)
	 *
	 * These categories are related by following products:
	 *
	 * - TV (1):
	 *     - Product Philips 32"
	 *     - Product Samsung 24"
	 *     - Crt (2):
	 *         - Product Ilyiama 15"
	 *         - Product Panasonic 17"
	 *     - LCD (3):
	 *         - Product BenQ 32"
	 *         - Product LG 28"
	 *
	 * When using this query:
	 *
	 * ```
	 * query(
	 *    entities('PRODUCT'),
	 *    filterBy(
	 *       withinHierarchy('CATEGORY', 1)
	 *    )
	 * )
	 * ```
	 *
	 * All products will be returned.
	 * When this query is used:
	 *
	 * ```
	 * query(
	 *    entities('PRODUCT'),
	 *    filterBy(
	 *       withinHierarchy('CATEGORY', 1, excludingRoot())
	 *    )
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

	/**
	 * Container allowing to filter entities by having references to entities managed by evitaDB that
	 * match inner filtering constraints. This container resembles the SQL inner join clauses where the `entityHaving`
	 * contains the filtering condition on particular join.
	*/
	@Nullable
	static EntityHaving entityHaving(@Nullable FilterConstraint filterConstraint) {
		return filterConstraint == null ? null : new EntityHaving(filterConstraint);
	}

	/**
	 * This `inRange` is query that compares value of the attribute with name passed in first argument with the date
	 * and time passed in the second argument. First argument must be {@link String}, second argument must be {@link OffsetDateTime}
	 * type. If second argument is not passed - current date and time (now) is used.
	 * Type of the attribute value must implement [Range](classes/range_interface.md) interface.
	 *
	 * Function returns true if second argument is greater than or equal to range start (from), and is lesser than
	 * or equal to range end (to).
	 *
	 * Example:
	 *
	 * ```
	 * inRange('valid', 2020-07-30T20:37:50+00:00)
	 * inRange('age', 18)
	 * ```
	 *
	 * Function supports attribute arrays and when attribute is of array type `inRange` returns true if *any of attribute* values
	 * has range, that envelopes the passed value the value in the query. If we have the attribute `age` with value
	 * `[[18, 25],[60,65]]` all these constraints will match:
	 *
	 * ```
	 * inRange('age', 18)
	 * inRange('age', 24)
	 * inRange('age', 63)
	 * ```
	*/
	@Nullable
	static AttributeInRange attributeInRange(@Nonnull String attributeName, @Nullable OffsetDateTime atTheMoment) {
		return atTheMoment == null ? null : new AttributeInRange(attributeName, atTheMoment);
	}

	/**
	 * This `inRange` is query that compares value of the attribute with name passed in first argument with the date
	 * and time passed in the second argument. First argument must be {@link String}, second argument must be {@link OffsetDateTime}
	 * type. If second argument is not passed - current date and time (now) is used.
	 * Type of the attribute value must implement [Range](classes/range_interface.md) interface.
	 *
	 * Function returns true if second argument is greater than or equal to range start (from), and is lesser than
	 * or equal to range end (to).
	 *
	 * Example:
	 *
	 * ```
	 * inRange('valid', 2020-07-30T20:37:50+00:00)
	 * inRange('age', 18)
	 * ```
	 *
	 * Function supports attribute arrays and when attribute is of array type `inRange` returns true if *any of attribute* values
	 * has range, that envelopes the passed value the value in the query. If we have the attribute `age` with value
	 * `[[18, 25],[60,65]]` all these constraints will match:
	 *
	 * ```
	 * inRange('age', 18)
	 * inRange('age', 24)
	 * inRange('age', 63)
	 * ```
	*/
	@Nullable
	static AttributeInRange attributeInRange(@Nonnull String attributeName, @Nullable Number theValue) {
		return theValue == null ? null : new AttributeInRange(attributeName, theValue);
	}

	/**
	 * This `inRange` is query that compares value of the attribute with name passed in first argument with the date
	 * and time passed in the second argument. First argument must be {@link String}, second argument must be {@link OffsetDateTime}
	 * type. If second argument is not passed - current date and time (now) is used.
	 * Type of the attribute value must implement [Range](classes/range_interface.md) interface.
	 *
	 * Function returns true if second argument is greater than or equal to range start (from), and is lesser than
	 * or equal to range end (to).
	 *
	 * Example:
	 *
	 * ```
	 * inRange('valid', 2020-07-30T20:37:50+00:00)
	 * inRange('age', 18)
	 * ```
	 *
	 * Function supports attribute arrays and when attribute is of array type `inRange` returns true if *any of attribute* values
	 * has range, that envelopes the passed value the value in the query. If we have the attribute `age` with value
	 * `[[18, 25],[60,65]]` all these constraints will match:
	 *
	 * ```
	 * inRange('age', 18)
	 * inRange('age', 24)
	 * inRange('age', 63)
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
	 * inSet('level', 1, 2, 3)
	 * ```
	 *
	 * Function supports attribute arrays and when attribute is of array type `inSet` returns true if *any of attribute* values
	 * equals the value in the query. If we have the attribute `code` with value `['A','B','C']` all these constraints will
	 * match:
	 *
	 * ```
	 * inSet('code','A','D')
	 * inSet('code','A', 'B')
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
	 * equals('code', 'abc')
	 * ```
	 *
	 * Function supports attribute arrays and when attribute is of array type `equals` returns true if *any of attribute* values
	 * equals the value in the query. If we have the attribute `code` with value `['A','B','C']` all these constraints will
	 * match:
	 *
	 * ```
	 * equals('code','A')
	 * equals('code','B')
	 * equals('code','C')
	 * ```
	*/
	@Nonnull
	static AttributeEquals attributeEqualsFalse(@Nonnull String attributeName) {
		return new AttributeEquals(attributeName, Boolean.FALSE);
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
	 * equals('code', 'abc')
	 * ```
	 *
	 * Function supports attribute arrays and when attribute is of array type `equals` returns true if *any of attribute* values
	 * equals the value in the query. If we have the attribute `code` with value `['A','B','C']` all these constraints will
	 * match:
	 *
	 * ```
	 * equals('code','A')
	 * equals('code','B')
	 * equals('code','C')
	 * ```
	*/
	@Nonnull
	static AttributeEquals attributeEqualsTrue(@Nonnull String attributeName) {
		return new AttributeEquals(attributeName, Boolean.TRUE);
	}

	/**
	 * This `attributeIs` is query that checks attribute for "special" value or constant that cannot be compared through
	 * {@link Comparable} of attribute with name passed in first argument.
	 * First argument must be {@link String}. Second is one of the {@link AttributeSpecialValue special values}:
	 *
	 * - NULL
	 * - NOT_NULL
	 *
	 * Function returns true if attribute has (explicitly or implicitly) passed special value.
	 *
	 * Example:
	 *
	 * ```
	 * attributeIs('visible', NULL)
	 * ```
	 *
	 * Function supports attribute arrays in the same way as plain values.
	*/
	@Nullable
	static AttributeIs attributeIs(@Nonnull String attributeName, @Nullable AttributeSpecialValue specialValue) {
		if (specialValue == null) {
			return null;
		}
		return new AttributeIs(attributeName, specialValue);
	}

	/**
	 * This `attributeIs` is query that checks attribute for "special" value or constant that cannot be compared through
	 * {@link Comparable} of attribute with name passed in first argument.
	 * First argument must be {@link String}. Second is one of the {@link AttributeSpecialValue special values}:
	 *
	 * - NULL
	 * - NOT_NULL
	 *
	 * Function returns true if attribute has (explicitly or implicitly) passed special value.
	 *
	 * Example:
	 *
	 * ```
	 * attributeIs('visible', NULL)
	 * ```
	 *
	 * Function supports attribute arrays in the same way as plain values.
	*/
	@Nonnull
	static AttributeIs attributeIsNull(@Nonnull String attributeName) {
		return new AttributeIs(attributeName, AttributeSpecialValue.NULL);
	}

	/**
	 * This `attributeIs` is query that checks attribute for "special" value or constant that cannot be compared through
	 * {@link Comparable} of attribute with name passed in first argument.
	 * First argument must be {@link String}. Second is one of the {@link AttributeSpecialValue special values}:
	 *
	 * - NULL
	 * - NOT_NULL
	 *
	 * Function returns true if attribute has (explicitly or implicitly) passed special value.
	 *
	 * Example:
	 *
	 * ```
	 * attributeIs('visible', NULL)
	 * ```
	 *
	 * Function supports attribute arrays in the same way as plain values.
	*/
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
	 * If argument is not passed - current date and time (now) is used.
	 *
	 * Function returns true if entity has at least one price which validity start (valid from) is lesser or equal to passed
	 * date and time and validity end (valid to) is greater or equal to passed date and time. This function is also affected by
	 * {@link PriceInCurrency} and {@link PriceInPriceLists} functions that limits the examined prices as well.
	 * When this query is used in the query returned entities will contain only prices which validity settings match
	 * specified date and time.
	 *
	 * Only single `priceValidIn` query can be used in the query. Validity of the prices will not be taken into an account
	 * when `priceValidIn` is not used in the query.
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
	 * This `priceValidIn` is query accepts single {@link OffsetDateTime}
	 * argument that represents the moment in time for which entity price must be valid.
	 * If argument is not passed - current date and time (now) is used.
	 *
	 * Function returns true if entity has at least one price which validity start (valid from) is lesser or equal to passed
	 * date and time and validity end (valid to) is greater or equal to passed date and time. This function is also affected by
	 * {@link PriceInCurrency} and {@link PriceInPriceLists} functions that limits the examined prices as well.
	 * When this query is used in the query returned entities will contain only prices which validity settings match
	 * specified date and time.
	 *
	 * Only single `priceValidIn` query can be used in the query. Validity of the prices will not be taken into an account
	 * when `priceValidIn` is not used in the query.
	 *
	 * Example:
	 *
	 * ```
	 * priceValidIn(2020-07-30T20:37:50+00:00)
	 * ```
	*/
	@Nonnull
	static PriceValidIn priceValidInNow() {
		return new PriceValidIn();
	}

	/**
	 * This `facet` query accepts [Serializable](https://docs.oracle.com/javase/8/docs/api/java/io/Serializable.html)
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
	 * entities('product'),
	 * filterBy(
	 * userFilter(
	 * facet('category', 4, 5),
	 * facet('group', 7, 13)
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
	 * This `orderBy` is container for ordering. It is mandatory container when any ordering is to be used. Ordering
	 * process is as follows:
	 *
	 * - first ordering evaluated, entities missing requested attribute value are excluded to intermediate bucket
	 * - next ordering is evaluated using entities present in an intermediate bucket, entities missing requested attribute
	 *   are excluded to new intermediate bucket
	 * - second step is repeated until all orderings are processed
	 * - content of the last intermediate bucket is appended to the result ordered by the primary key in ascending order
	 *
	 * Entities with same (equal) values must not be subject to secondary ordering rules and may be sorted randomly within
	 * the scope of entities with the same value (this is subject to change, currently this behaviour differs from the one
	 * used by relational databases - but is way faster).
	 *
	 * Example:
	 *
	 * ```
	 * orderBy(
	 *     ascending('code'),
	 *     ascending('create'),
	 *     priceDescending()
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
	 * This `orderByGroup` is container for ordering groups within {@link FacetSummary}. This ordering constraint cannot
	 * be used anywhere else.
	 *
	 * Example:
	 *
	 * ```
	 * facetSummaryOfReference(
	 *    'parameters',
	 *    orderGroupBy(
	 *       attributeNatural('name', OrderDirection.ASC)
	 *    )
	 * )
	 * ```
	*/
	@Nullable
	static OrderGroupBy orderGroupBy(@Nullable OrderConstraint... constraints) {
		if (constraints == null) {
			return null;
		}
		return new OrderGroupBy(constraints);
	}

	/**
	 * The constraint allows to sort output entities by primary key values in the exact order that was used for filtering
	 * them. The constraint requires presence of exactly one {@link EntityPrimaryKeyInSet} constraint in filter part of
	 * the query. It uses {@link EntityPrimaryKeyInSet#getPrimaryKeys()} array for sorting the output of the query.
	 *
	 * Example usage:
	 *
	 * <pre>
	 * query(
	 *    filterBy(
	 *       entityPrimaryKeyInSet(5, 1, 8)
	 *    ),
	 *    orderBy(
	 *       entityPrimaryKeyInFilter()
	 *    )
	 * )
	 * </pre>
	 *
	 * The example will return the selected entities (if present) in the exact order that was used for array filtering them.
	 * The ordering constraint is particularly useful when you have sorted set of entity primary keys from an external
	 * system which needs to be maintained (for example, it represents a relevancy of those entities).
	*/
	@Nonnull
	static EntityPrimaryKeyInFilter entityPrimaryKeyInFilter() {
		return new EntityPrimaryKeyInFilter();
	}

	/**
	 * The constraint allows to sort output entities by primary key values in the exact order that is specified in
	 * the arguments of this constraint.
	 *
	 * Example usage:
	 *
	 * <pre>
	 * query(
	 *    filterBy(
	 *       attributeEqualsTrue("shortcut")
	 *    ),
	 *    orderBy(
	 *       entityPrimaryKeyExact(5, 1, 8)
	 *    )
	 * )
	 * </pre>
	 *
	 * The example will return the selected entities (if present) in the exact order that is stated in the argument of
	 * this ordering constraint. If there are entities, whose primary keys are not present in the argument, then they
	 * will be present at the end of the output in ascending order of their primary keys (or they will be sorted by
	 * additional ordering constraint in the chain).
	*/
	@Nullable
	static EntityPrimaryKeyExact entityPrimaryKeyExact(@Nullable Integer... primaryKey) {
		if (ArrayUtils.isEmpty(primaryKey)) {
			return null;
		}
		return new EntityPrimaryKeyExact(primaryKey);
	}

	/**
	 * The constraint allows to sort output entities by attribute values in the exact order that was used for filtering
	 * them. The constraint requires presence of exactly one {@link AttributeInSet} constraint in filter part of the query
	 * that relates to the attribute with the same name as is used in the first argument of this constraint.
	 * It uses {@link AttributeInSet#getAttributeValues()} array for sorting the output of the query.
	 *
	 * Example usage:
	 *
	 * <pre>
	 * query(
	 *    filterBy(
	 *       attributeInSet('code', 't-shirt', 'sweater', 'pants')
	 *    ),
	 *    orderBy(
	 *       attributeSetInFilter()
	 *    )
	 * )
	 * </pre>
	 *
	 * The example will return the selected entities (if present) in the exact order of their attribute `code` that was used
	 * for array filtering them. The ordering constraint is particularly useful when you have sorted set of attribute values
	 * from an external system which needs to be maintained (for example, it represents a relevancy of those entities).
	*/
	@Nullable
	static AttributeSetInFilter attributeSetInFilter(@Nullable String attributeName) {
		if (attributeName == null || attributeName.isBlank()) {
			return null;
		}
		return new AttributeSetInFilter(attributeName);
	}

	/**
	 * The constraint allows output entities to be sorted by attribute values in the exact order specified in the 2nd through
	 * Nth arguments of this constraint.
	 *
	 * Example usage:
	 *
	 * <pre>
	 * query(
	 *    filterBy(
	 *       attributeEqualsTrue("shortcut")
	 *    ),
	 *    orderBy(
	 *       attributeSetExact('code', 't-shirt', 'sweater', 'pants')
	 *    )
	 * )
	 * </pre>
	 *
	 * The example will return the selected entities (if present) in the exact order of their `code` attributes that is
	 * stated in the second to Nth argument of this ordering constraint. If there are entities, that have not the attribute
	 * `code` , then they will be present at the end of the output in ascending order of their primary keys (or they will be
	 * sorted by additional ordering constraint in the chain).
	*/
	@Nullable
	static AttributeSetExact attributeSetExact(@Nullable String attributeName, @Nullable Serializable... attributeValues) {
		if (attributeName == null || attributeName.isBlank() || ArrayUtils.isEmpty(attributeValues)) {
			return null;
		}
		return new AttributeSetExact(attributeName, attributeValues);
	}

	/**
	 * Sorting by reference attribute is not as common as sorting by entity attributes, but it allows you to sort entities
	 * that are in a particular category or have a particular brand specifically by the priority/order for that particular
	 * relationship.
	 *
	 * To sort products related to a "Sony" brand by the `priority` attribute set on the reference, you need to use the
	 * following constraint:
	 *
	 * Example:
	 *
	 * ```
	 * referenceAttribute(
	 *    'brand',
	 *    attributeNatural('brandPriority', DESC)
	 * )
	 * ```
	 *
	 * **The `referenceProperty` is implicit in requirement `referenceContent`**
	 *
	 * In the `orderBy` clause within the {@link io.evitadb.api.query.require.ReferenceContent} requirement,
	 * the `referenceProperty` constraint is implicit and must not be repeated. All attribute order constraints
	 * in `referenceContent` automatically refer to the reference attributes, unless the {@link EntityProperty}
	 * container is used there.
	 *
	 * The example is based on a simple one-to-zero-or-one reference (a product can have at most one reference to a brand
	 * entity). The response will only return the products that have a reference to the "Sony" brand, all of which contain the
	 * `orderInBrand` attribute (since it's marked as a non-nullable attribute). Because the example is so simple, the returned
	 * result can be anticipated.
	 *
	 * ## Behaviour of zero or one to many references ordering
	 *
	 * The situation is more complicated when the reference is one-to-many. What is the expected result of a query that
	 * involves ordering by a property on a reference attribute? Is it wise to allow such ordering query in this case?
	 *
	 * We decided to allow it and bind it with the following rules:
	 *
	 * ### Non-hierarchical entity
	 *
	 * If the referenced entity is **non-hierarchical**, and the returned entity references multiple entities, only
	 * the reference with the lowest primary key of the referenced entity, while also having the order property set, will be
	 * used for ordering.
	 *
	 * ### Hierarchical entity
	 *
	 * If the referenced entity is **hierarchical** and the returned entity references multiple entities, the reference used
	 * for ordering is the one that contains the order property and is the closest hierarchy node to the root of the filtered
	 * hierarchy node.
	 *
	 * It sounds complicated, but it's really quite simple. If you list products of a certain category and at the same time
	 * order them by a property "priority" set on the reference to the category, the first products will be those directly
	 * related to the category, ordered by "priority", followed by the products of the first child category, and so on,
	 * maintaining the depth-first order of the category tree.
	*/
	@Nullable
	static ReferenceProperty referenceProperty(@Nonnull String referenceName, @Nullable OrderConstraint... constraints) {
		if (constraints == null) {
			return null;
		}
		return new ReferenceProperty(referenceName, constraints);
	}

	/**
	 * The `entityProperty` ordering constraint can only be used within the {@link ReferenceContent} requirement. It allows
	 * to change the context of the reference ordering from attributes of the reference itself to attributes of the entity
	 * the reference points to.
	 *
	 * In other words, if the `Product` entity has multiple references to `Parameter` entities, you can sort those references
	 * by, for example, the `priority` or `name` attribute of the `Parameter` entity.
	 *
	 * Example:
	 *
	 * ```
	 * referenceContent(
	 *    'parameters',
	 *    entityProperty(
	 *       attributeNatural('priority', DESC)
	 *    )
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
	 * The constraint allows output entities to be sorted by their attributes in their natural order (numeric, alphabetical,
	 * temporal). It requires specification of a single attribute and the direction of the ordering (default ordering is
	 * {@link OrderDirection#ASC}).
	 *
	 * Ordering is executed by natural order of the {@link Comparable} type.
	 *
	 * Example:
	 *
	 * ```
	 * attributeNatural('married')
	 * attributeNatural('age', ASC)
	 * ```
	 *
	 * If you want to sort products by their name, which is a localized attribute, you need to specify the {@link EntityLocaleEquals}
	 * constraint in the {@link FilterBy} part of the query. The correct {@link java.text.Collator} is used to
	 * order the localized attribute string, so that the order is consistent with the national customs of the language.
	 *
	 * The sorting mechanism of evitaDB is somewhat different from what you might be used to. If you sort entities by two
	 * attributes in an `orderBy` clause of the query, evitaDB sorts them first by the first attribute (if present) and then
	 * by the second (but only those where the first attribute is missing). If two entities have the same value of the first
	 * attribute, they are not sorted by the second attribute, but by the primary key (in ascending order).
	 *
	 * If we want to use fast "pre-sorted" indexes, there is no other way to do it, because the secondary order would not be
	 * known until a query time. If you want to sort by multiple attributes in the conventional way, you need to define the
	 * sortable attribute compound in advance and use its name instead of the default attribute name. The sortable attribute
	 * compound will cover multiple attributes and prepares a special sort index for this particular combination of
	 * attributes, respecting the predefined order and NULL values behaviour. In the query, you can then use the compound
	 * name instead of the default attribute name and achieve the expected results.
	*/
	@Nonnull
	static AttributeNatural attributeNatural(@Nonnull String attributeName) {
		return new AttributeNatural(attributeName);
	}

	/**
	 * The constraint allows output entities to be sorted by their attributes in their natural order (numeric, alphabetical,
	 * temporal). It requires specification of a single attribute and the direction of the ordering (default ordering is
	 * {@link OrderDirection#ASC}).
	 *
	 * Ordering is executed by natural order of the {@link Comparable} type.
	 *
	 * Example:
	 *
	 * ```
	 * attributeNatural('married')
	 * attributeNatural('age', ASC)
	 * ```
	 *
	 * If you want to sort products by their name, which is a localized attribute, you need to specify the {@link EntityLocaleEquals}
	 * constraint in the {@link FilterBy} part of the query. The correct {@link java.text.Collator} is used to
	 * order the localized attribute string, so that the order is consistent with the national customs of the language.
	 *
	 * The sorting mechanism of evitaDB is somewhat different from what you might be used to. If you sort entities by two
	 * attributes in an `orderBy` clause of the query, evitaDB sorts them first by the first attribute (if present) and then
	 * by the second (but only those where the first attribute is missing). If two entities have the same value of the first
	 * attribute, they are not sorted by the second attribute, but by the primary key (in ascending order).
	 *
	 * If we want to use fast "pre-sorted" indexes, there is no other way to do it, because the secondary order would not be
	 * known until a query time. If you want to sort by multiple attributes in the conventional way, you need to define the
	 * sortable attribute compound in advance and use its name instead of the default attribute name. The sortable attribute
	 * compound will cover multiple attributes and prepares a special sort index for this particular combination of
	 * attributes, respecting the predefined order and NULL values behaviour. In the query, you can then use the compound
	 * name instead of the default attribute name and achieve the expected results.
	*/
	@Nonnull
	static AttributeNatural attributeNatural(@Nonnull String attributeName, @Nonnull OrderDirection orderDirection) {
		return new AttributeNatural(attributeName, orderDirection);
	}

	/**
	 * This `price` is ordering that sorts returned entities by most priority price in defined order direction in the first
	 * optional argument.
	 * Most priority price relates to [price computation algorithm](price_computation.md) described in special article.
	 *
	 * Example:
	 *
	 * ```
	 * price()
	 * price(DESC)
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
	 *
	 * Example:
	 *
	 * ```
	 * price()
	 * price(DESC)
	 * ```
	*/
	@Nonnull
	static PriceNatural priceNatural(@Nonnull OrderDirection orderDirection) {
		return new PriceNatural(orderDirection);
	}

	/**
	 * Random ordering is useful in situations where you want to present the end user with the unique entity listing every
	 * time he/she accesses it. The constraint makes the order of the entities in the result random and does not take any
	 * arguments.
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
	 * This `require` is container for listing all additional requirements for th equery. It is mandatory container when
	 * any requirement query is to be used.
	 *
	 * Example:
	 *
	 * ```
	 * require(
	 *     page(1, 2),
	 *     entityBody()
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
	 * name and value is the {@link AttributeHistogram}.
	 *
	 * Example:
	 *
	 * ```
	 * attributeHistogram(20, 'width', 'height')
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
	 * When this requirement is used an additional object {@link io.evitadb.api.requestResponse.extraResult.Histogram} is stored to the result.
	 * Histogram contains statistics on price layout in the query result.
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
	 * This require constraint changes default behaviour stating that all facets inside same facet group are combined by OR
	 * relation (eg. disjunction). Constraint has sense only when [facet](#facet) constraint is part of the query.
	 *
	 * Example:
	 *
	 * <pre>
	 * query(
	 *    entities('product'),
	 *    filterBy(
	 *       userFilter(
	 *          facet('group', 1, 2),
	 *          facet('parameterType', 11, 12, 22)
	 *       )
	 *    ),
	 *    require(
	 *       facetGroupsConjunction('parameterType', 1, 8, 15)
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
	 * facet red(12). If require `facetGroupsConjunction('parameterType', 1)` is passed in the query filtering condition will
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
	 * This `facetGroupsDisjunction` require constraint allows specifying facet relation among different facet groups of certain
	 * primary ids. First mandatory argument specifies entity type of the facet group, secondary argument allows to define one
	 * more facet group ids that should be considered disjunctive.
	 *
	 * This require constraint changes default behaviour stating that facets between two different facet groups are combined by
	 * AND relation and changes it to the disjunction relation instead.
	 *
	 * Example:
	 *
	 * <pre>
	 * query(
	 *    entities('product'),
	 *    filterBy(
	 *       userFilter(
	 *          facet('group', 1, 2),
	 *          facet('parameterType', 11, 12, 22)
	 *       )
	 *    ),
	 *    require(
	 *       facetGroupsDisjunction('parameterType', 1, 2)
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
	 * have facet blue as well as facet large and action products tag (AND). If require `facetGroupsDisjunction('tag', 3)`
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
	 * facetGroupsNegation('parameterType', 1, 8, 15)
	 * ```
	 *
	 * This statement means, that facets in 'parameterType' groups `1`, `8`, `15` will be joined with boolean AND NOT relation
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
	 * This `hierarchyStatistics` require query triggers computing the statistics for referenced hierarchical entities
	 * and adds an object to the result index. It has at least one {@link Serializable}
	 * argument that specifies type of hierarchical entity that this entity relates to. Additional arguments allow passing
	 * requirements for fetching the referenced entity contents so that there are no other requests to the evitaDB necessary
	 * and all data are fetched in single query.
	 *
	 * When this require query is used an additional object is stored to result index:
	 *
	 * - **HierarchyStatistics**
	 * this object is organized in the tree structure that reflects the hierarchy of the entities of desired type that are
	 * referenced by entities returned by primary query, for each tree entity there is a number that represents the count of
	 * currently queried entities that relates to that referenced hierarchical entity and match the query filter - either
	 * directly or to some subordinate entity of this hierarchical entity
	 *
	 * Example:
	 *
	 * <pre>
	 * hierarchyStatisticsOfReference('category')
	 * hierarchyStatisticsOfReference('category', entityBody(), attributes())
	 * </pre>
	 *
	 * This require query is usually used when hierarchical menu rendering is needed. For example when we need to render
	 * menu for entire e-commerce site, but we want to take excluded subtrees into an account and also reflect the filtering
	 * conditions that may filter out dozens of products (and thus leading to empty categories) we can invoke following query:
	 *
	 * <pre>
	 * query(
	 *     entities('PRODUCT'),
	 *     filterBy(
	 *         and(
	 *             eq('visible', true),
	 *             inRange('valid', 2020-07-30T20:37:50+00:00),
	 *             priceInCurrency('USD'),
	 *             priceValidIn(2020-07-30T20:37:50+00:00),
	 *             priceInPriceLists('vip', 'standard'),
	 *             withinRootHierarchy('CATEGORY', excluding(3, 7))
	 *         )
	 *     ),
	 *     require(
	 *         page(1, 20),
	 *         hierarchyStatisticsOfReference('CATEGORY', entityBody(), attributes())
	 *     )
	 * )
	 * </pre>
	 *
	 * This query would return first page with 20 products (omitting hundreds of others on additional pages) but also returns a
	 * HierarchyStatistics in additional data. This object may contain following structure:
	 *
	 * <pre>
	 * Electronics -> 1789
	 *     TV -> 126
	 *         LED -> 90
	 *         CRT -> 36
	 *     Washing machines -> 190
	 *         Slim -> 40
	 *         Standard -> 40
	 *         With drier -> 23
	 *         Top filling -> 42
	 *         Smart -> 45
	 *     Cell phones -> 350
	 *     Audio / Video -> 230
	 *     Printers -> 80
	 * </pre>
	 *
	 * The tree will contain category entities loaded with `attributes` instead the names you see in the example. The number
	 * after the arrow represents the count of the products that are referencing this category (either directly or some of its
	 * children). You can see there are only categories that are valid for the passed query - excluded category subtree will
	 * not be part of the category listing (query filters out all products with excluded category tree) and there is also no
	 * category that happens to be empty (e.g. contains no products or only products that don't match the filter query).
	 *
	 * TOBEDONE JNO: review docs
	*/
	@Nullable
	static HierarchyOfSelf hierarchyOfSelf(@Nullable HierarchyRequireConstraint... requirement) {
		return ArrayUtils.isEmpty(requirement) ? null : new HierarchyOfSelf(null, requirement);
	}

	/**
	 * This `hierarchyStatistics` require query triggers computing the statistics for referenced hierarchical entities
	 * and adds an object to the result index. It has at least one {@link Serializable}
	 * argument that specifies type of hierarchical entity that this entity relates to. Additional arguments allow passing
	 * requirements for fetching the referenced entity contents so that there are no other requests to the evitaDB necessary
	 * and all data are fetched in single query.
	 *
	 * When this require query is used an additional object is stored to result index:
	 *
	 * - **HierarchyStatistics**
	 * this object is organized in the tree structure that reflects the hierarchy of the entities of desired type that are
	 * referenced by entities returned by primary query, for each tree entity there is a number that represents the count of
	 * currently queried entities that relates to that referenced hierarchical entity and match the query filter - either
	 * directly or to some subordinate entity of this hierarchical entity
	 *
	 * Example:
	 *
	 * <pre>
	 * hierarchyStatisticsOfReference('category')
	 * hierarchyStatisticsOfReference('category', entityBody(), attributes())
	 * </pre>
	 *
	 * This require query is usually used when hierarchical menu rendering is needed. For example when we need to render
	 * menu for entire e-commerce site, but we want to take excluded subtrees into an account and also reflect the filtering
	 * conditions that may filter out dozens of products (and thus leading to empty categories) we can invoke following query:
	 *
	 * <pre>
	 * query(
	 *     entities('PRODUCT'),
	 *     filterBy(
	 *         and(
	 *             eq('visible', true),
	 *             inRange('valid', 2020-07-30T20:37:50+00:00),
	 *             priceInCurrency('USD'),
	 *             priceValidIn(2020-07-30T20:37:50+00:00),
	 *             priceInPriceLists('vip', 'standard'),
	 *             withinRootHierarchy('CATEGORY', excluding(3, 7))
	 *         )
	 *     ),
	 *     require(
	 *         page(1, 20),
	 *         hierarchyStatisticsOfReference('CATEGORY', entityBody(), attributes())
	 *     )
	 * )
	 * </pre>
	 *
	 * This query would return first page with 20 products (omitting hundreds of others on additional pages) but also returns a
	 * HierarchyStatistics in additional data. This object may contain following structure:
	 *
	 * <pre>
	 * Electronics -> 1789
	 *     TV -> 126
	 *         LED -> 90
	 *         CRT -> 36
	 *     Washing machines -> 190
	 *         Slim -> 40
	 *         Standard -> 40
	 *         With drier -> 23
	 *         Top filling -> 42
	 *         Smart -> 45
	 *     Cell phones -> 350
	 *     Audio / Video -> 230
	 *     Printers -> 80
	 * </pre>
	 *
	 * The tree will contain category entities loaded with `attributes` instead the names you see in the example. The number
	 * after the arrow represents the count of the products that are referencing this category (either directly or some of its
	 * children). You can see there are only categories that are valid for the passed query - excluded category subtree will
	 * not be part of the category listing (query filters out all products with excluded category tree) and there is also no
	 * category that happens to be empty (e.g. contains no products or only products that don't match the filter query).
	 *
	 * TOBEDONE JNO: review docs
	*/
	@Nullable
	static HierarchyOfSelf hierarchyOfSelf(
		@Nullable OrderBy orderBy,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		return ArrayUtils.isEmpty(requirement) ? null : new HierarchyOfSelf(orderBy, requirement);
	}

	/**
	 * This `hierarchyStatistics` require query triggers computing the statistics for referenced hierarchical entities
	 * and adds an object to the result index. It has at least one {@link Serializable}
	 * argument that specifies type of hierarchical entity that this entity relates to. Additional arguments allow passing
	 * requirements for fetching the referenced entity contents so that there are no other requests to the evitaDB necessary
	 * and all data are fetched in single query.
	 *
	 * When this require query is used an additional object is stored to result index:
	 *
	 * - **HierarchyStatistics**
	 * this object is organized in the tree structure that reflects the hierarchy of the entities of desired type that are
	 * referenced by entities returned by primary query, for each tree entity there is a number that represents the count of
	 * currently queried entities that relates to that referenced hierarchical entity and match the query filter - either
	 * directly or to some subordinate entity of this hierarchical entity
	 *
	 * Example:
	 *
	 * <pre>
	 * hierarchyStatisticsOfReference('category')
	 * hierarchyStatisticsOfReference('category', entityBody(), attributes())
	 * </pre>
	 *
	 * This require query is usually used when hierarchical menu rendering is needed. For example when we need to render
	 * menu for entire e-commerce site, but we want to take excluded subtrees into an account and also reflect the filtering
	 * conditions that may filter out dozens of products (and thus leading to empty categories) we can invoke following query:
	 *
	 * <pre>
	 * query(
	 *     entities('PRODUCT'),
	 *     filterBy(
	 *         and(
	 *             eq('visible', true),
	 *             inRange('valid', 2020-07-30T20:37:50+00:00),
	 *             priceInCurrency('USD'),
	 *             priceValidIn(2020-07-30T20:37:50+00:00),
	 *             priceInPriceLists('vip', 'standard'),
	 *             withinRootHierarchy('CATEGORY', excluding(3, 7))
	 *         )
	 *     ),
	 *     require(
	 *         page(1, 20),
	 *         hierarchyStatisticsOfSelf('CATEGORY', entityBody(), attributes())
	 *     )
	 * )
	 * </pre>
	 *
	 * This query would return first page with 20 products (omitting hundreds of others on additional pages) but also returns a
	 * HierarchyStatistics in additional data. This object may contain following structure:
	 *
	 * <pre>
	 * Electronics -> 1789
	 *     TV -> 126
	 *         LED -> 90
	 *         CRT -> 36
	 *     Washing machines -> 190
	 *         Slim -> 40
	 *         Standard -> 40
	 *         With drier -> 23
	 *         Top filling -> 42
	 *         Smart -> 45
	 *     Cell phones -> 350
	 *     Audio / Video -> 230
	 *     Printers -> 80
	 * </pre>
	 *
	 * The tree will contain category entities loaded with `attributes` instead the names you see in the example. The number
	 * after the arrow represents the count of the products that are referencing this category (either directly or some of its
	 * children). You can see there are only categories that are valid for the passed query - excluded category subtree will
	 * not be part of the category listing (query filters out all products with excluded category tree) and there is also no
	 * category that happens to be empty (e.g. contains no products or only products that don't match the filter query).
	 *
	 * TOBEDONE JNO: review docs
	*/
	@Nullable
	static HierarchyOfReference hierarchyOfReference(
		@Nullable String referenceName,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		return hierarchyOfReference(referenceName, null, null, requirement);
	}

	/**
	 * This `hierarchyStatistics` require query triggers computing the statistics for referenced hierarchical entities
	 * and adds an object to the result index. It has at least one {@link Serializable}
	 * argument that specifies type of hierarchical entity that this entity relates to. Additional arguments allow passing
	 * requirements for fetching the referenced entity contents so that there are no other requests to the evitaDB necessary
	 * and all data are fetched in single query.
	 *
	 * When this require query is used an additional object is stored to result index:
	 *
	 * - **HierarchyStatistics**
	 * this object is organized in the tree structure that reflects the hierarchy of the entities of desired type that are
	 * referenced by entities returned by primary query, for each tree entity there is a number that represents the count of
	 * currently queried entities that relates to that referenced hierarchical entity and match the query filter - either
	 * directly or to some subordinate entity of this hierarchical entity
	 *
	 * Example:
	 *
	 * <pre>
	 * hierarchyStatisticsOfReference('category')
	 * hierarchyStatisticsOfReference('category', entityBody(), attributes())
	 * </pre>
	 *
	 * This require query is usually used when hierarchical menu rendering is needed. For example when we need to render
	 * menu for entire e-commerce site, but we want to take excluded subtrees into an account and also reflect the filtering
	 * conditions that may filter out dozens of products (and thus leading to empty categories) we can invoke following query:
	 *
	 * <pre>
	 * query(
	 *     entities('PRODUCT'),
	 *     filterBy(
	 *         and(
	 *             eq('visible', true),
	 *             inRange('valid', 2020-07-30T20:37:50+00:00),
	 *             priceInCurrency('USD'),
	 *             priceValidIn(2020-07-30T20:37:50+00:00),
	 *             priceInPriceLists('vip', 'standard'),
	 *             withinRootHierarchy('CATEGORY', excluding(3, 7))
	 *         )
	 *     ),
	 *     require(
	 *         page(1, 20),
	 *         hierarchyStatisticsOfSelf('CATEGORY', entityBody(), attributes())
	 *     )
	 * )
	 * </pre>
	 *
	 * This query would return first page with 20 products (omitting hundreds of others on additional pages) but also returns a
	 * HierarchyStatistics in additional data. This object may contain following structure:
	 *
	 * <pre>
	 * Electronics -> 1789
	 *     TV -> 126
	 *         LED -> 90
	 *         CRT -> 36
	 *     Washing machines -> 190
	 *         Slim -> 40
	 *         Standard -> 40
	 *         With drier -> 23
	 *         Top filling -> 42
	 *         Smart -> 45
	 *     Cell phones -> 350
	 *     Audio / Video -> 230
	 *     Printers -> 80
	 * </pre>
	 *
	 * The tree will contain category entities loaded with `attributes` instead the names you see in the example. The number
	 * after the arrow represents the count of the products that are referencing this category (either directly or some of its
	 * children). You can see there are only categories that are valid for the passed query - excluded category subtree will
	 * not be part of the category listing (query filters out all products with excluded category tree) and there is also no
	 * category that happens to be empty (e.g. contains no products or only products that don't match the filter query).
	 *
	 * TOBEDONE JNO: review docs
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
	 * This `hierarchyStatistics` require query triggers computing the statistics for referenced hierarchical entities
	 * and adds an object to the result index. It has at least one {@link Serializable}
	 * argument that specifies type of hierarchical entity that this entity relates to. Additional arguments allow passing
	 * requirements for fetching the referenced entity contents so that there are no other requests to the evitaDB necessary
	 * and all data are fetched in single query.
	 *
	 * When this require query is used an additional object is stored to result index:
	 *
	 * - **HierarchyStatistics**
	 * this object is organized in the tree structure that reflects the hierarchy of the entities of desired type that are
	 * referenced by entities returned by primary query, for each tree entity there is a number that represents the count of
	 * currently queried entities that relates to that referenced hierarchical entity and match the query filter - either
	 * directly or to some subordinate entity of this hierarchical entity
	 *
	 * Example:
	 *
	 * <pre>
	 * hierarchyStatisticsOfReference('category')
	 * hierarchyStatisticsOfReference('category', entityBody(), attributes())
	 * </pre>
	 *
	 * This require query is usually used when hierarchical menu rendering is needed. For example when we need to render
	 * menu for entire e-commerce site, but we want to take excluded subtrees into an account and also reflect the filtering
	 * conditions that may filter out dozens of products (and thus leading to empty categories) we can invoke following query:
	 *
	 * <pre>
	 * query(
	 *     entities('PRODUCT'),
	 *     filterBy(
	 *         and(
	 *             eq('visible', true),
	 *             inRange('valid', 2020-07-30T20:37:50+00:00),
	 *             priceInCurrency('USD'),
	 *             priceValidIn(2020-07-30T20:37:50+00:00),
	 *             priceInPriceLists('vip', 'standard'),
	 *             withinRootHierarchy('CATEGORY', excluding(3, 7))
	 *         )
	 *     ),
	 *     require(
	 *         page(1, 20),
	 *         hierarchyStatisticsOfSelf('CATEGORY', entityBody(), attributes())
	 *     )
	 * )
	 * </pre>
	 *
	 * This query would return first page with 20 products (omitting hundreds of others on additional pages) but also returns a
	 * HierarchyStatistics in additional data. This object may contain following structure:
	 *
	 * <pre>
	 * Electronics -> 1789
	 *     TV -> 126
	 *         LED -> 90
	 *         CRT -> 36
	 *     Washing machines -> 190
	 *         Slim -> 40
	 *         Standard -> 40
	 *         With drier -> 23
	 *         Top filling -> 42
	 *         Smart -> 45
	 *     Cell phones -> 350
	 *     Audio / Video -> 230
	 *     Printers -> 80
	 * </pre>
	 *
	 * The tree will contain category entities loaded with `attributes` instead the names you see in the example. The number
	 * after the arrow represents the count of the products that are referencing this category (either directly or some of its
	 * children). You can see there are only categories that are valid for the passed query - excluded category subtree will
	 * not be part of the category listing (query filters out all products with excluded category tree) and there is also no
	 * category that happens to be empty (e.g. contains no products or only products that don't match the filter query).
	 *
	 * TOBEDONE JNO: review docs
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
	 * This `hierarchyStatistics` require query triggers computing the statistics for referenced hierarchical entities
	 * and adds an object to the result index. It has at least one {@link Serializable}
	 * argument that specifies type of hierarchical entity that this entity relates to. Additional arguments allow passing
	 * requirements for fetching the referenced entity contents so that there are no other requests to the evitaDB necessary
	 * and all data are fetched in single query.
	 *
	 * When this require query is used an additional object is stored to result index:
	 *
	 * - **HierarchyStatistics**
	 * this object is organized in the tree structure that reflects the hierarchy of the entities of desired type that are
	 * referenced by entities returned by primary query, for each tree entity there is a number that represents the count of
	 * currently queried entities that relates to that referenced hierarchical entity and match the query filter - either
	 * directly or to some subordinate entity of this hierarchical entity
	 *
	 * Example:
	 *
	 * <pre>
	 * hierarchyStatisticsOfReference('category')
	 * hierarchyStatisticsOfReference('category', entityBody(), attributes())
	 * </pre>
	 *
	 * This require query is usually used when hierarchical menu rendering is needed. For example when we need to render
	 * menu for entire e-commerce site, but we want to take excluded subtrees into an account and also reflect the filtering
	 * conditions that may filter out dozens of products (and thus leading to empty categories) we can invoke following query:
	 *
	 * <pre>
	 * query(
	 *     entities('PRODUCT'),
	 *     filterBy(
	 *         and(
	 *             eq('visible', true),
	 *             inRange('valid', 2020-07-30T20:37:50+00:00),
	 *             priceInCurrency('USD'),
	 *             priceValidIn(2020-07-30T20:37:50+00:00),
	 *             priceInPriceLists('vip', 'standard'),
	 *             withinRootHierarchy('CATEGORY', excluding(3, 7))
	 *         )
	 *     ),
	 *     require(
	 *         page(1, 20),
	 *         hierarchyStatisticsOfSelf('CATEGORY', entityBody(), attributes())
	 *     )
	 * )
	 * </pre>
	 *
	 * This query would return first page with 20 products (omitting hundreds of others on additional pages) but also returns a
	 * HierarchyStatistics in additional data. This object may contain following structure:
	 *
	 * <pre>
	 * Electronics -> 1789
	 *     TV -> 126
	 *         LED -> 90
	 *         CRT -> 36
	 *     Washing machines -> 190
	 *         Slim -> 40
	 *         Standard -> 40
	 *         With drier -> 23
	 *         Top filling -> 42
	 *         Smart -> 45
	 *     Cell phones -> 350
	 *     Audio / Video -> 230
	 *     Printers -> 80
	 * </pre>
	 *
	 * The tree will contain category entities loaded with `attributes` instead the names you see in the example. The number
	 * after the arrow represents the count of the products that are referencing this category (either directly or some of its
	 * children). You can see there are only categories that are valid for the passed query - excluded category subtree will
	 * not be part of the category listing (query filters out all products with excluded category tree) and there is also no
	 * category that happens to be empty (e.g. contains no products or only products that don't match the filter query).
	 *
	 * TOBEDONE JNO: review docs
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
	 * This `hierarchyStatistics` require query triggers computing the statistics for referenced hierarchical entities
	 * and adds an object to the result index. It has at least one {@link Serializable}
	 * argument that specifies type of hierarchical entity that this entity relates to. Additional arguments allow passing
	 * requirements for fetching the referenced entity contents so that there are no other requests to the evitaDB necessary
	 * and all data are fetched in single query.
	 *
	 * When this require query is used an additional object is stored to result index:
	 *
	 * - **HierarchyStatistics**
	 * this object is organized in the tree structure that reflects the hierarchy of the entities of desired type that are
	 * referenced by entities returned by primary query, for each tree entity there is a number that represents the count of
	 * currently queried entities that relates to that referenced hierarchical entity and match the query filter - either
	 * directly or to some subordinate entity of this hierarchical entity
	 *
	 * Example:
	 *
	 * <pre>
	 * hierarchyStatisticsOfReference('category')
	 * hierarchyStatisticsOfReference('category', entityBody(), attributes())
	 * </pre>
	 *
	 * This require query is usually used when hierarchical menu rendering is needed. For example when we need to render
	 * menu for entire e-commerce site, but we want to take excluded subtrees into an account and also reflect the filtering
	 * conditions that may filter out dozens of products (and thus leading to empty categories) we can invoke following query:
	 *
	 * <pre>
	 * query(
	 *     entities('PRODUCT'),
	 *     filterBy(
	 *         and(
	 *             eq('visible', true),
	 *             inRange('valid', 2020-07-30T20:37:50+00:00),
	 *             priceInCurrency('USD'),
	 *             priceValidIn(2020-07-30T20:37:50+00:00),
	 *             priceInPriceLists('vip', 'standard'),
	 *             withinRootHierarchy('CATEGORY', excluding(3, 7))
	 *         )
	 *     ),
	 *     require(
	 *         page(1, 20),
	 *         hierarchyStatisticsOfSelf('CATEGORY', entityBody(), attributes())
	 *     )
	 * )
	 * </pre>
	 *
	 * This query would return first page with 20 products (omitting hundreds of others on additional pages) but also returns a
	 * HierarchyStatistics in additional data. This object may contain following structure:
	 *
	 * <pre>
	 * Electronics -> 1789
	 *     TV -> 126
	 *         LED -> 90
	 *         CRT -> 36
	 *     Washing machines -> 190
	 *         Slim -> 40
	 *         Standard -> 40
	 *         With drier -> 23
	 *         Top filling -> 42
	 *         Smart -> 45
	 *     Cell phones -> 350
	 *     Audio / Video -> 230
	 *     Printers -> 80
	 * </pre>
	 *
	 * The tree will contain category entities loaded with `attributes` instead the names you see in the example. The number
	 * after the arrow represents the count of the products that are referencing this category (either directly or some of its
	 * children). You can see there are only categories that are valid for the passed query - excluded category subtree will
	 * not be part of the category listing (query filters out all products with excluded category tree) and there is also no
	 * category that happens to be empty (e.g. contains no products or only products that don't match the filter query).
	 *
	 * TOBEDONE JNO: review docs
	*/
	@Nullable
	static HierarchyOfReference hierarchyOfReference(
		@Nullable String[] referenceName,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		return hierarchyOfReference(referenceName, null, null, requirement);
	}

	/**
	 * This `hierarchyStatistics` require query triggers computing the statistics for referenced hierarchical entities
	 * and adds an object to the result index. It has at least one {@link Serializable}
	 * argument that specifies type of hierarchical entity that this entity relates to. Additional arguments allow passing
	 * requirements for fetching the referenced entity contents so that there are no other requests to the evitaDB necessary
	 * and all data are fetched in single query.
	 *
	 * When this require query is used an additional object is stored to result index:
	 *
	 * - **HierarchyStatistics**
	 * this object is organized in the tree structure that reflects the hierarchy of the entities of desired type that are
	 * referenced by entities returned by primary query, for each tree entity there is a number that represents the count of
	 * currently queried entities that relates to that referenced hierarchical entity and match the query filter - either
	 * directly or to some subordinate entity of this hierarchical entity
	 *
	 * Example:
	 *
	 * <pre>
	 * hierarchyStatisticsOfReference('category')
	 * hierarchyStatisticsOfReference('category', entityBody(), attributes())
	 * </pre>
	 *
	 * This require query is usually used when hierarchical menu rendering is needed. For example when we need to render
	 * menu for entire e-commerce site, but we want to take excluded subtrees into an account and also reflect the filtering
	 * conditions that may filter out dozens of products (and thus leading to empty categories) we can invoke following query:
	 *
	 * <pre>
	 * query(
	 *     entities('PRODUCT'),
	 *     filterBy(
	 *         and(
	 *             eq('visible', true),
	 *             inRange('valid', 2020-07-30T20:37:50+00:00),
	 *             priceInCurrency('USD'),
	 *             priceValidIn(2020-07-30T20:37:50+00:00),
	 *             priceInPriceLists('vip', 'standard'),
	 *             withinRootHierarchy('CATEGORY', excluding(3, 7))
	 *         )
	 *     ),
	 *     require(
	 *         page(1, 20),
	 *         hierarchyStatisticsOfSelf('CATEGORY', entityBody(), attributes())
	 *     )
	 * )
	 * </pre>
	 *
	 * This query would return first page with 20 products (omitting hundreds of others on additional pages) but also returns a
	 * HierarchyStatistics in additional data. This object may contain following structure:
	 *
	 * <pre>
	 * Electronics -> 1789
	 *     TV -> 126
	 *         LED -> 90
	 *         CRT -> 36
	 *     Washing machines -> 190
	 *         Slim -> 40
	 *         Standard -> 40
	 *         With drier -> 23
	 *         Top filling -> 42
	 *         Smart -> 45
	 *     Cell phones -> 350
	 *     Audio / Video -> 230
	 *     Printers -> 80
	 * </pre>
	 *
	 * The tree will contain category entities loaded with `attributes` instead the names you see in the example. The number
	 * after the arrow represents the count of the products that are referencing this category (either directly or some of its
	 * children). You can see there are only categories that are valid for the passed query - excluded category subtree will
	 * not be part of the category listing (query filters out all products with excluded category tree) and there is also no
	 * category that happens to be empty (e.g. contains no products or only products that don't match the filter query).
	 *
	 * TOBEDONE JNO: review docs
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
	 * This `hierarchyStatistics` require query triggers computing the statistics for referenced hierarchical entities
	 * and adds an object to the result index. It has at least one {@link Serializable}
	 * argument that specifies type of hierarchical entity that this entity relates to. Additional arguments allow passing
	 * requirements for fetching the referenced entity contents so that there are no other requests to the evitaDB necessary
	 * and all data are fetched in single query.
	 *
	 * When this require query is used an additional object is stored to result index:
	 *
	 * - **HierarchyStatistics**
	 * this object is organized in the tree structure that reflects the hierarchy of the entities of desired type that are
	 * referenced by entities returned by primary query, for each tree entity there is a number that represents the count of
	 * currently queried entities that relates to that referenced hierarchical entity and match the query filter - either
	 * directly or to some subordinate entity of this hierarchical entity
	 *
	 * Example:
	 *
	 * <pre>
	 * hierarchyStatisticsOfReference('category')
	 * hierarchyStatisticsOfReference('category', entityBody(), attributes())
	 * </pre>
	 *
	 * This require query is usually used when hierarchical menu rendering is needed. For example when we need to render
	 * menu for entire e-commerce site, but we want to take excluded subtrees into an account and also reflect the filtering
	 * conditions that may filter out dozens of products (and thus leading to empty categories) we can invoke following query:
	 *
	 * <pre>
	 * query(
	 *     entities('PRODUCT'),
	 *     filterBy(
	 *         and(
	 *             eq('visible', true),
	 *             inRange('valid', 2020-07-30T20:37:50+00:00),
	 *             priceInCurrency('USD'),
	 *             priceValidIn(2020-07-30T20:37:50+00:00),
	 *             priceInPriceLists('vip', 'standard'),
	 *             withinRootHierarchy('CATEGORY', excluding(3, 7))
	 *         )
	 *     ),
	 *     require(
	 *         page(1, 20),
	 *         hierarchyStatisticsOfSelf('CATEGORY', entityBody(), attributes())
	 *     )
	 * )
	 * </pre>
	 *
	 * This query would return first page with 20 products (omitting hundreds of others on additional pages) but also returns a
	 * HierarchyStatistics in additional data. This object may contain following structure:
	 *
	 * <pre>
	 * Electronics -> 1789
	 *     TV -> 126
	 *         LED -> 90
	 *         CRT -> 36
	 *     Washing machines -> 190
	 *         Slim -> 40
	 *         Standard -> 40
	 *         With drier -> 23
	 *         Top filling -> 42
	 *         Smart -> 45
	 *     Cell phones -> 350
	 *     Audio / Video -> 230
	 *     Printers -> 80
	 * </pre>
	 *
	 * The tree will contain category entities loaded with `attributes` instead the names you see in the example. The number
	 * after the arrow represents the count of the products that are referencing this category (either directly or some of its
	 * children). You can see there are only categories that are valid for the passed query - excluded category subtree will
	 * not be part of the category listing (query filters out all products with excluded category tree) and there is also no
	 * category that happens to be empty (e.g. contains no products or only products that don't match the filter query).
	 *
	 * TOBEDONE JNO: review docs
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
	 * This `hierarchyStatistics` require query triggers computing the statistics for referenced hierarchical entities
	 * and adds an object to the result index. It has at least one {@link Serializable}
	 * argument that specifies type of hierarchical entity that this entity relates to. Additional arguments allow passing
	 * requirements for fetching the referenced entity contents so that there are no other requests to the evitaDB necessary
	 * and all data are fetched in single query.
	 *
	 * When this require query is used an additional object is stored to result index:
	 *
	 * - **HierarchyStatistics**
	 * this object is organized in the tree structure that reflects the hierarchy of the entities of desired type that are
	 * referenced by entities returned by primary query, for each tree entity there is a number that represents the count of
	 * currently queried entities that relates to that referenced hierarchical entity and match the query filter - either
	 * directly or to some subordinate entity of this hierarchical entity
	 *
	 * Example:
	 *
	 * <pre>
	 * hierarchyStatisticsOfReference('category')
	 * hierarchyStatisticsOfReference('category', entityBody(), attributes())
	 * </pre>
	 *
	 * This require query is usually used when hierarchical menu rendering is needed. For example when we need to render
	 * menu for entire e-commerce site, but we want to take excluded subtrees into an account and also reflect the filtering
	 * conditions that may filter out dozens of products (and thus leading to empty categories) we can invoke following query:
	 *
	 * <pre>
	 * query(
	 *     entities('PRODUCT'),
	 *     filterBy(
	 *         and(
	 *             eq('visible', true),
	 *             inRange('valid', 2020-07-30T20:37:50+00:00),
	 *             priceInCurrency('USD'),
	 *             priceValidIn(2020-07-30T20:37:50+00:00),
	 *             priceInPriceLists('vip', 'standard'),
	 *             withinRootHierarchy('CATEGORY', excluding(3, 7))
	 *         )
	 *     ),
	 *     require(
	 *         page(1, 20),
	 *         hierarchyStatisticsOfSelf('CATEGORY', entityBody(), attributes())
	 *     )
	 * )
	 * </pre>
	 *
	 * This query would return first page with 20 products (omitting hundreds of others on additional pages) but also returns a
	 * HierarchyStatistics in additional data. This object may contain following structure:
	 *
	 * <pre>
	 * Electronics -> 1789
	 *     TV -> 126
	 *         LED -> 90
	 *         CRT -> 36
	 *     Washing machines -> 190
	 *         Slim -> 40
	 *         Standard -> 40
	 *         With drier -> 23
	 *         Top filling -> 42
	 *         Smart -> 45
	 *     Cell phones -> 350
	 *     Audio / Video -> 230
	 *     Printers -> 80
	 * </pre>
	 *
	 * The tree will contain category entities loaded with `attributes` instead the names you see in the example. The number
	 * after the arrow represents the count of the products that are referencing this category (either directly or some of its
	 * children). You can see there are only categories that are valid for the passed query - excluded category subtree will
	 * not be part of the category listing (query filters out all products with excluded category tree) and there is also no
	 * category that happens to be empty (e.g. contains no products or only products that don't match the filter query).
	 *
	 * TOBEDONE JNO: review docs
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

	/**
	 * TOBEDONE JNO docs
	*/
	@Nonnull
	static EntityFetch entityFetch(@Nullable EntityContentRequire... requirements) {
		if (requirements == null) {
			return new EntityFetch();
		}
		return new EntityFetch(requirements);
	}

	/**
	 * TOBEDONE JNO docs
	*/
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
	 * This requirement implicitly triggers {@link EntityBodyFetch} requirement because attributes cannot be returned without entity.
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
	static AttributeContent attributeContentAll() {
		return new AttributeContent();
	}

	/**
	 * This `attributes` requirement changes default behaviour of the query engine returning only entity primary keys in the result. When
	 * this requirement is used result contains [entity bodies](entity_model.md) except `associated data` that could
	 * become big. These type of data can be fetched either lazily or by specifying additional requirements in the query.
	 *
	 * This requirement implicitly triggers {@link EntityBodyFetch} requirement because attributes cannot be returned without entity.
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
	 * This requirement implicitly triggers {@link EntityBodyFetch} requirement because attributes cannot be returned without entity.
	 * [Localized interface](classes/localized_interface.md) associated data is returned according to {@link EntityLocaleEquals}
	 * query. Requirement might be combined with {@link AttributeContent} requirement.
	 *
	 * Example:
	 *
	 * ```
	 * associatedData('description', 'gallery-3d')
	 * ```
	*/
	@Nonnull
	static AssociatedDataContent associatedDataContentAll() {
		return new AssociatedDataContent();
	}

	/**
	 * This `associatedData` requirement changes default behaviour of the query engine returning only entity primary keys in the result. When
	 * this requirement is used result contains [entity bodies](entity_model.md) along with associated data with names specified in
	 * one or more arguments of this requirement.
	 *
	 * This requirement implicitly triggers {@link EntityBodyFetch} requirement because attributes cannot be returned without entity.
	 * [Localized interface](classes/localized_interface.md) associated data is returned according to {@link EntityLocaleEquals}
	 * query. Requirement might be combined with {@link AttributeContent} requirement.
	 *
	 * Example:
	 *
	 * ```
	 * associatedData('description', 'gallery-3d')
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
	 * localized in required languages as well as global ones. If query contains no argument, global data and data
	 * localized to all languages are returned. If query is not present in the query, only global attributes and
	 * associated data are returned.
	 *
	 * **Note:** if {@link EntityLocaleEquals}is used in the filter part of the query and `dataInLanguage`
	 * require query is missing, the system implicitly uses `dataInLanguage` matching the language in filter query.
	 *
	 * Only single `dataInLanguage` query can be used in the query.
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
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * references()
	 * references(CATEGORY)
	 * references(CATEGORY, 'stocks', entityBody())
	 * references(CATEGORY, filterBy(attributeEquals('code', 10)), entityBody())
	 * ```
	*/
	@Nonnull
	static ReferenceContent referenceContentAll() {
		return new ReferenceContent();
	}

	/**
	 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * references()
	 * references(CATEGORY)
	 * references(CATEGORY, 'stocks', entityBody())
	 * references(CATEGORY, filterBy(attributeEquals('code', 10)), entityBody())
	 * ```
	*/
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String referencedEntityType) {
		if (referencedEntityType == null) {
			return new ReferenceContent();
		}
		return new ReferenceContent(referencedEntityType);
	}

	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referencedEntityType, @Nullable AttributeContent attributeContent) {
		return new ReferenceContent(referencedEntityType, null, null, attributeContent, null, null);
	}

	/**
	 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * references()
	 * references(CATEGORY)
	 * references(CATEGORY, 'stocks', entityBody())
	 * references(CATEGORY, filterBy(attributeEquals('code', 10)), entityBody())
	 * ```
	*/
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String... referencedEntityType) {
		if (referencedEntityType == null) {
			return new ReferenceContent();
		}
		return new ReferenceContent(referencedEntityType);
	}

	/**
	 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * references()
	 * references(CATEGORY)
	 * references(CATEGORY, 'stocks', entityBody())
	 * references(CATEGORY, filterBy(attributeEquals('code', 10)), entityBody())
	 * ```
	*/
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String referencedEntityType, @Nullable EntityFetch entityRequirement) {
		if (referencedEntityType == null && entityRequirement == null) {
			return new ReferenceContent();
		}
		if (referencedEntityType == null) {
			return new ReferenceContent(entityRequirement, null);
		}
		return new ReferenceContent(referencedEntityType,  null, null, null, entityRequirement, null);
	}

	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referencedEntityType, @Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(referencedEntityType,  null, null, attributeContent, entityRequirement, null);
	}

	/**
	 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * references()
	 * references(CATEGORY)
	 * references(CATEGORY, 'stocks', entityBody())
	 * references(CATEGORY, filterBy(attributeEquals('code', 10)), entityBody())
	 * ```
	*/
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String referencedEntityType, @Nullable EntityGroupFetch groupEntityRequirement) {
		if (referencedEntityType == null && groupEntityRequirement == null) {
			return new ReferenceContent();
		}
		if (referencedEntityType == null) {
			return new ReferenceContent(null, groupEntityRequirement);
		}
		return new ReferenceContent(referencedEntityType, null, null, null, null, groupEntityRequirement);
	}

	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referencedEntityType, @Nullable AttributeContent attributeContent, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(referencedEntityType, null, null, attributeContent, null, groupEntityRequirement);
	}

	/**
	 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * references()
	 * references(CATEGORY)
	 * references(CATEGORY, 'stocks', entityBody())
	 * references(CATEGORY, filterBy(attributeEquals('code', 10)), entityBody())
	 * ```
	*/
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String referencedEntityType, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		if (referencedEntityType == null) {
			return new ReferenceContent(entityRequirement, groupEntityRequirement);
		}
		return new ReferenceContent(referencedEntityType, null, null, null, entityRequirement, groupEntityRequirement);
	}

	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referencedEntityType, @Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(referencedEntityType, null, null, null, entityRequirement, groupEntityRequirement);
	}


	/**
	 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * references()
	 * references(CATEGORY)
	 * references(CATEGORY, 'stocks', entityBody())
	 * references(CATEGORY, filterBy(attributeEquals('code', 10)), entityBody())
	 * ```
	*/
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String[] referencedEntityTypes, @Nullable EntityFetch entityRequirement) {
		if (referencedEntityTypes == null && entityRequirement == null) {
			return new ReferenceContent();
		}
		if (referencedEntityTypes == null) {
			return new ReferenceContent(entityRequirement, null);
		}
		return new ReferenceContent(
			referencedEntityTypes,
			entityRequirement,
			null
		);
	}

	/**
	 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * references()
	 * references(CATEGORY)
	 * references(CATEGORY, 'stocks', entityBody())
	 * references(CATEGORY, filterBy(attributeEquals('code', 10)), entityBody())
	 * ```
	*/
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String[] referencedEntityTypes, @Nullable EntityGroupFetch groupEntityRequirement) {
		if (referencedEntityTypes == null && groupEntityRequirement == null) {
			return new ReferenceContent();
		}
		if (referencedEntityTypes == null) {
			return new ReferenceContent(null, groupEntityRequirement);
		}
		return new ReferenceContent(
			referencedEntityTypes,
			null,
			groupEntityRequirement
		);
	}

	/**
	 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * references()
	 * references(CATEGORY)
	 * references(CATEGORY, 'stocks', entityBody())
	 * references(CATEGORY, filterBy(attributeEquals('code', 10)), entityBody())
	 * ```
	*/
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String[] referencedEntityTypes, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		if (referencedEntityTypes != null) {
			return new ReferenceContent(referencedEntityTypes, entityRequirement, groupEntityRequirement);
		} else {
			return new ReferenceContent(entityRequirement, groupEntityRequirement);
		}
	}

	/**
	 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * references()
	 * references(CATEGORY)
	 * references(CATEGORY, 'stocks', entityBody())
	 * references(CATEGORY, filterBy(attributeEquals('code', 10)), entityBody())
	 * ```
	*/
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy) {
		return new ReferenceContent(referenceName, filterBy, null, null, null, null);
	}

	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable AttributeContent attributeContent) {
		return new ReferenceContent(referenceName, filterBy, null, attributeContent, null, null);
	}

	/**
	 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * references()
	 * references(CATEGORY)
	 * references(CATEGORY, 'stocks', entityBody())
	 * references(CATEGORY, filterBy(attributeEquals('code', 10)), entityBody())
	 * ```
	*/
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(referenceName, filterBy, null, null, entityRequirement, null);
	}

	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(referenceName, filterBy, null, attributeContent, entityRequirement, null);
	}

	/**
	 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * references()
	 * references(CATEGORY)
	 * references(CATEGORY, 'stocks', entityBody())
	 * references(CATEGORY, filterBy(attributeEquals('code', 10)), entityBody())
	 * ```
	*/
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(referenceName, filterBy, null, null, null, groupEntityRequirement);
	}

	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable AttributeContent attributeContent, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(referenceName, filterBy, null, attributeContent, null, groupEntityRequirement);
	}

	/**
	 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * references()
	 * references(CATEGORY)
	 * references(CATEGORY, 'stocks', entityBody())
	 * references(CATEGORY, filterBy(attributeEquals('code', 10)), entityBody())
	 * ```
	*/
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(referenceName, filterBy, null, null, entityRequirement, groupEntityRequirement);
	}

	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(referenceName, filterBy, null, attributeContent, entityRequirement, groupEntityRequirement);
	}

	/**
	 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * references()
	 * references(CATEGORY)
	 * references(CATEGORY, 'stocks', entityBody())
	 * references(CATEGORY, filterBy(attributeEquals('code', 10)), entityBody())
	 * ```
	*/
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable OrderBy orderBy) {
		return new ReferenceContent(referenceName, null, orderBy, null, null, null);
	}

	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable OrderBy orderBy, @Nullable AttributeContent attributeContent) {
		return new ReferenceContent(referenceName, null, orderBy, attributeContent, null, null);
	}

	/**
	 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * references()
	 * references(CATEGORY)
	 * references(CATEGORY, 'stocks', entityBody())
	 * references(CATEGORY, filterBy(attributeEquals('code', 10)), entityBody())
	 * ```
	*/
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(referenceName, null, orderBy, null, entityRequirement, null);
	}

	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable OrderBy orderBy, @Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(referenceName, null, orderBy, attributeContent, entityRequirement, null);
	}

	/**
	 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * references()
	 * references(CATEGORY)
	 * references(CATEGORY, 'stocks', entityBody())
	 * references(CATEGORY, filterBy(attributeEquals('code', 10)), entityBody())
	 * ```
	*/
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable OrderBy orderBy, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(referenceName, null, orderBy, null, null, groupEntityRequirement);
	}

	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable OrderBy orderBy, @Nullable AttributeContent attributeContent, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(referenceName, null, orderBy, attributeContent, null, groupEntityRequirement);
	}

	/**
	 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * references()
	 * references(CATEGORY)
	 * references(CATEGORY, 'stocks', entityBody())
	 * references(CATEGORY, filterBy(attributeEquals('code', 10)), entityBody())
	 * ```
	*/
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(referenceName, null, orderBy, null, entityRequirement, groupEntityRequirement);
	}

	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable OrderBy orderBy, @Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(referenceName, null, orderBy, attributeContent, entityRequirement, groupEntityRequirement);
	}

	/**
	 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * references()
	 * references(CATEGORY)
	 * references(CATEGORY, 'stocks', entityBody())
	 * references(CATEGORY, filterBy(attributeEquals('code', 10)), entityBody())
	 * ```
	*/
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy) {
		return new ReferenceContent(referenceName, filterBy, orderBy, null, null, null);
	}

	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable AttributeContent attributeContent) {
		return new ReferenceContent(referenceName, filterBy, orderBy, attributeContent, null, null);
	}

	/**
	 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * references()
	 * references(CATEGORY)
	 * references(CATEGORY, 'stocks', entityBody())
	 * references(CATEGORY, filterBy(attributeEquals('code', 10)), entityBody())
	 * ```
	*/
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(referenceName, filterBy, orderBy, null, entityRequirement, null);
	}

	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(referenceName, filterBy, orderBy, attributeContent, entityRequirement, null);
	}

	/**
	 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * references()
	 * references(CATEGORY)
	 * references(CATEGORY, 'stocks', entityBody())
	 * references(CATEGORY, filterBy(attributeEquals('code', 10)), entityBody())
	 * ```
	*/
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(referenceName, filterBy, orderBy, null, null, groupEntityRequirement);
	}

	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable AttributeContent attributeContent, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(referenceName, filterBy, orderBy, attributeContent, null, groupEntityRequirement);
	}

	/**
	 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * references()
	 * references(CATEGORY)
	 * references(CATEGORY, 'stocks', entityBody())
	 * references(CATEGORY, filterBy(attributeEquals('code', 10)), entityBody())
	 * ```
	*/
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(referenceName, filterBy, orderBy, null, entityRequirement, groupEntityRequirement);
	}

	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(referenceName, filterBy, orderBy, attributeContent, entityRequirement, groupEntityRequirement);
	}

	/**
	 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * references()
	 * references(CATEGORY)
	 * references(CATEGORY, 'stocks', entityBody())
	 * references(CATEGORY, filterBy(attributeEquals('code', 10)), entityBody())
	 * ```
	*/
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable EntityFetch entityRequirement) {
		return referenceContent((String) null, entityRequirement);
	}

	/**
	 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * references()
	 * references(CATEGORY)
	 * references(CATEGORY, 'stocks', entityBody())
	 * references(CATEGORY, filterBy(attributeEquals('code', 10)), entityBody())
	 * ```
	*/
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceContent((String) null, groupEntityRequirement);
	}

	/**
	 * This `references` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with references with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * references()
	 * references(CATEGORY)
	 * references(CATEGORY, 'stocks', entityBody())
	 * references(CATEGORY, filterBy(attributeEquals('code', 10)), entityBody())
	 * ```
	*/
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(entityRequirement, groupEntityRequirement);
	}

	/**
	 * This `hierarchyContent` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with hierarchyContent with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * hierarchyContent()
	 * hierarchyContent(CATEGORY)
	 * hierarchyContent(CATEGORY, 'stocks', entityBody())
	 * hierarchyContent(CATEGORY, stopAt(distance(4)), entityBody())
	 * ```
	*/
	@Nonnull
	static HierarchyContent hierarchyContent() {
		return new HierarchyContent();
	}

	/**
	 * This `hierarchyContent` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with hierarchyContent with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * hierarchyContent()
	 * hierarchyContent(CATEGORY)
	 * hierarchyContent(CATEGORY, 'stocks', entityBody())
	 * hierarchyContent(CATEGORY, stopAt(distance(4)), entityBody())
	 * ```
	*/
	@Nonnull
	static HierarchyContent hierarchyContent(@Nullable HierarchyStopAt stopAt) {
		return stopAt == null ? new HierarchyContent() : new HierarchyContent(stopAt);
	}

	/**
	 * This `hierarchyContent` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with hierarchyContent with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * hierarchyContent()
	 * hierarchyContent(CATEGORY)
	 * hierarchyContent(CATEGORY, 'stocks', entityBody())
	 * hierarchyContent(CATEGORY, stopAt(distance(4)), entityBody())
	 * ```
	*/
	@Nonnull
	static HierarchyContent hierarchyContent(@Nullable EntityFetch entityFetch) {
		return entityFetch == null ? new HierarchyContent() : new HierarchyContent(entityFetch);
	}

	/**
	 * This `hierarchyContent` requirement changes default behaviour of the query engine returning only entity primary keys in the result.
	 * When this requirement is used result contains [entity bodies](entity_model.md) along with hierarchyContent with to entities
	 * or external objects specified in one or more arguments of this requirement.
	 *
	 * Example:
	 *
	 * ```
	 * hierarchyContent()
	 * hierarchyContent(CATEGORY)
	 * hierarchyContent(CATEGORY, 'stocks', entityBody())
	 * hierarchyContent(CATEGORY, stopAt(distance(4)), entityBody())
	 * ```
	*/
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
	 * This requirement implicitly triggers {@link EntityFetch} requirement because prices cannot be returned without entity.
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
	 * prices(RESPECTING_FILTER)
	 * prices(ALL)
	 * prices(NONE)
	 * ```
	*/
	@Nullable
	static PriceContent priceContent(@Nullable PriceContentMode contentMode, @Nullable String... priceLists) {
		if (contentMode == null) {
			return null;
		}
		if (ArrayUtils.isEmpty(priceLists)) {
			return new PriceContent(contentMode);
		} else {
			return new PriceContent(contentMode, priceLists);
		}
	}

	/**
	 * This `prices` requirement changes default behaviour of the query engine returning only entity primary keys in the result. When
	 * this requirement is used result contains [entity prices](entity_model.md).
	 *
	 * This requirement implicitly triggers {@link EntityFetch} requirement because prices cannot be returned without entity.
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
	 * prices(RESPECTING_FILTER)
	 * prices(ALL)
	 * prices(NONE)
	 * ```
	*/
	@Nonnull
	static PriceContent priceContentAll() {
		return PriceContent.all();
	}

	/**
	 * This `prices` requirement changes default behaviour of the query engine returning only entity primary keys in the result. When
	 * this requirement is used result contains [entity prices](entity_model.md).
	 *
	 * This requirement implicitly triggers {@link EntityFetch} requirement because prices cannot be returned without entity.
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
	 * prices(RESPECTING_FILTER)
	 * prices(ALL)
	 * prices(NONE)
	 * ```
	*/
	@Nonnull
	static PriceContent priceContentRespectingFilter(@Nullable String... priceLists) {
		return PriceContent.respectingFilter(priceLists);
	}

	/**
	 * This `useOfPrice` require query can be used to control the form of prices that will be used for computation in
	 * {@link io.evitadb.api.query.filter.PriceBetween} filtering, and {@link PriceNatural},
	 * ordering. Also {@link PriceHistogram} is sensitive to this setting.
	 *
	 * By default, end customer form of price (e.g. price with tax) is used in all above-mentioned constraints. This could
	 * be changed by using this requirement query. It has single argument that can have one of the following values:
	 *
	 * - WITH_TAX
	 * - WITHOUT_TAX
	 *
	 * Example:
	 *
	 * ```
	 * useOfPrice(WITH_TAX)
	 * ```
	*/
	@Nonnull
	static PriceType priceType(@Nonnull QueryPriceMode priceMode) {
		return new PriceType(priceMode);
	}

	/**
	 * This `page` constraint controls count of the entities in the query output. It allows specifying 2 arguments in following order:
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
	 *   should be omitted in the result, must be greater than or equals to zero (mandatory)
	 * - **[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) limit**: number of entities on
	 *   that should be returned, must be greater than zero (mandatory)
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
	 * This `facetSummary` requirement usage triggers computing and adding an object to the result index. The object is
	 * quite complex but allows rendering entire facet listing to e-commerce users. It contains information about all
	 * facets present in current hierarchy view along with count of requested entities that have those facets assigned.
	 *
	 * Facet summary respects current query filtering constraints excluding the conditions inside {@link UserFilter}
	 * container constraint.
	 *
	 * When this requirement is used an additional object {@link io.evitadb.api.requestResponse.extraResult.FacetSummary} is stored to result.
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
	 * This `facetSummary` requirement usage triggers computing and adding an object to the result index. The object is
	 * quite complex but allows rendering entire facet listing to e-commerce users. It contains information about all
	 * facets present in current hierarchy view along with count of requested entities that have those facets assigned.
	 *
	 * Facet summary respects current query filtering constraints excluding the conditions inside {@link UserFilter}
	 * container constraint.
	 *
	 * When this requirement is used an additional object {@link io.evitadb.api.requestResponse.extraResult.FacetSummary} is stored to result.
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
	static FacetSummary facetSummary(@Nullable FacetStatisticsDepth statisticsDepth, @Nullable EntityRequire... requirements) {
		return statisticsDepth == null ?
			new FacetSummary(FacetStatisticsDepth.COUNTS, requirements) :
			new FacetSummary(statisticsDepth, requirements);
	}

	/**
	 * This `facetSummary` requirement usage triggers computing and adding an object to the result index. The object is
	 * quite complex but allows rendering entire facet listing to e-commerce users. It contains information about all
	 * facets present in current hierarchy view along with count of requested entities that have those facets assigned.
	 *
	 * Facet summary respects current query filtering constraints excluding the conditions inside {@link UserFilter}
	 * container constraint.
	 *
	 * When this requirement is used an additional object {@link io.evitadb.api.requestResponse.extraResult.FacetSummary} is stored to result.
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

	/**
	 * This `facetSummary` requirement usage triggers computing and adding an object to the result index. The object is
	 * quite complex but allows rendering entire facet listing to e-commerce users. It contains information about all
	 * facets present in current hierarchy view along with count of requested entities that have those facets assigned.
	 *
	 * Facet summary respects current query filtering constraints excluding the conditions inside {@link UserFilter}
	 * container constraint.
	 *
	 * When this requirement is used an additional object {@link io.evitadb.api.requestResponse.extraResult.FacetSummary} is stored to result.
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
	 * This `facetSummary` requirement usage triggers computing and adding an object to the result index. The object is
	 * quite complex but allows rendering entire facet listing to e-commerce users. It contains information about all
	 * facets present in current hierarchy view along with count of requested entities that have those facets assigned.
	 *
	 * Facet summary respects current query filtering constraints excluding the conditions inside {@link UserFilter}
	 * container constraint.
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
	static FacetSummaryOfReference facetSummaryOfReference(@Nonnull String referenceName, @Nullable EntityRequire... requirements) {
		return new FacetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, requirements);
	}

	/**
	 * This `facetSummary` requirement usage triggers computing and adding an object to the result index. The object is
	 * quite complex but allows rendering entire facet listing to e-commerce users. It contains information about all
	 * facets present in current hierarchy view along with count of requested entities that have those facets assigned.
	 *
	 * Facet summary respects current query filtering constraints excluding the conditions inside {@link UserFilter}
	 * container constraint.
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
	static FacetSummaryOfReference facetSummaryOfReference(@Nonnull String referenceName, @Nullable FacetStatisticsDepth statisticsDepth, @Nullable EntityRequire... requirements) {
		return statisticsDepth == null ?
			new FacetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, requirements) :
			new FacetSummaryOfReference(referenceName, statisticsDepth, requirements);
	}

	/**
	 * This `facetSummary` requirement usage triggers computing and adding an object to the result index. The object is
	 * quite complex but allows rendering entire facet listing to e-commerce users. It contains information about all
	 * facets present in current hierarchy view along with count of requested entities that have those facets assigned.
	 *
	 * Facet summary respects current query filtering constraints excluding the conditions inside {@link UserFilter}
	 * container constraint.
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

	/**
	 * This `facetSummary` requirement usage triggers computing and adding an object to the result index. The object is
	 * quite complex but allows rendering entire facet listing to e-commerce users. It contains information about all
	 * facets present in current hierarchy view along with count of requested entities that have those facets assigned.
	 *
	 * Facet summary respects current query filtering constraints excluding the conditions inside {@link UserFilter}
	 * container constraint.
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
	 * This `queryTelemetry` requirement triggers creation of the {@link io.evitadb.api.requestResponse.extraResult.QueryTelemetry} DTO and including it the evitaDB
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
	 * This `debug` require is targeted for internal purposes only and is not exposed in public evitaDB API.
	*/
	static Debug debug(@Nonnull DebugMode... debugMode) {
		return new Debug(debugMode);
	}

	/**
	 * TOBEDONE JNO docs
	*/
	@Nonnull
	static EntityFetch entityFetchAll() {
		return entityFetch(
			attributeContentAll(), hierarchyContent(), associatedDataContentAll(), priceContentAll(), referenceContentAll(), dataInLocales()
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
	 * This interface marks all requirements that can be used for loading additional data to existing entity.
	*/
	@Nonnull
	static EntityContentRequire[] entityFetchAllContent() {
		return new EntityContentRequire[]{
			attributeContent(), associatedDataContent(), priceContentAll(), referenceContentAll(), dataInLocales()
		};
	}

	/**
	 * This interface marks all requirements that can be used for loading additional data to existing entity.
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