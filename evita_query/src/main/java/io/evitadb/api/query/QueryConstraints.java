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
import io.evitadb.dataType.Range;
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
	 * in the second argument and value passed in third argument. First argument must be {@link String}, second and third
	 * argument may be any of {@link Comparable} type.
	 * 
	 * Type of the attribute value and second argument must be convertible one to another otherwise `between` function
	 * returns false.
	 * 
	 * Function returns true if value in a filterable attribute of such a name is greater than or equal to value in second argument
	 * and lesser than or equal to value in third argument.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * between('age', 20, 25)
	 * </pre>
	 * 
	 * Function supports attribute arrays and when attribute is of array type `between` returns true if *any of attribute* values
	 * is between the passed interval the value in the query. If we have the attribute `amount` with value `[1, 9]` all
	 * these constraints will match:
	 * 
	 * <pre>
	 * between('amount', 0, 50)
	 * between('amount', 0, 5)
	 * between('amount', 8, 10)
	 * </pre>
	 * 
	 * If attribute is of `Range` type `between` query behaves like overlap - it returns true if examined range and
	 * any of the attribute ranges (see previous paragraph about array types) share anything in common. All of following
	 * constraints return true when we have the attribute `validity` with following `NumberRange` values: `[[2,5],[8,10]]`:
	 * 
	 * <pre>
	 * between(`validity`, 0, 3)
	 * between(`validity`, 0, 100)
	 * between(`validity`, 9, 10)
	 * </pre>
	 * 
	 * ... but these constraints will return false:
	 * 
	 * <pre>
	 * between(`validity`, 11, 15)
	 * between(`validity`, 0, 1)
	 * between(`validity`, 6, 7)
	 * </pre>
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
	 * <pre>
	 * contains('code', 'eve')
	 * </pre>
	 * 
	 * Function supports attribute arrays and when attribute is of array type `contains` returns true if any of attribute
	 * values contains the value in the query. If we have the attribute `code` with value `['cat','mouse','dog']` all these
	 * constraints will match:
	 * 
	 * <pre>
	 * contains('code','mou')
	 * contains('code','o')
	 * </pre>
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
	 * <pre>
	 * startsWith('code', 'vid')
	 * </pre>
	 * 
	 * Function supports attribute arrays and when attribute is of array type `startsWith` returns true if any of attribute
	 * values starts with the value in the query. If we have the attribute `code` with value `['cat','mouse','dog']` all
	 * these constraints will match:
	 * 
	 * <pre>
	 * contains('code','mou')
	 * contains('code','do')
	 * </pre>
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
	 * <pre>
	 * endsWith('code', 'ida')
	 * </pre>
	 * 
	 * Function supports attribute arrays and when attribute is of array type `endsWith` returns true if any of attribute
	 * values ends with the value in the query. If we have the attribute `code` with value `['cat','mouse','dog']` all these
	 * constraints will match:
	 * 
	 * <pre>
	 * contains('code','at')
	 * contains('code','og')
	 * </pre>
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
	 * <pre>
	 * equals('code', 'abc')
	 * </pre>
	 * 
	 * Function supports attribute arrays and when attribute is of array type `equals` returns true if any of attribute values
	 * equals the value in the query. If we have the attribute `code` with value `['A','B','C']` all these constraints will
	 * match:
	 * 
	 * <pre>
	 * equals('code','A')
	 * equals('code','B')
	 * equals('code','C')
	 * </pre>
	*/
	@Nullable
	static <T extends Serializable> AttributeEquals attributeEquals(@Nonnull String attributeName, @Nullable T attributeValue) {
		return attributeValue == null ? null : new AttributeEquals(attributeName, attributeValue);
	}

	/**
	 * This `lessThan` is query that compares value of the attribute with name passed in first argument with the value passed
	 * in the second argument. First argument must be {@link String}, second argument may be any of {@link Comparable} type.
	 * Type of the attribute value and second argument must be convertible one to another otherwise `lessThan` function
	 * returns false.
	 * 
	 * Function returns true if value in a filterable attribute of such a name is less than value in second argument.
	 * 
	 * Function currently doesn't support attribute arrays and when attribute is of array type. Query returns error when this
	 * query is used in combination with array type attribute. This may however change in the future.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * lessThan('age', 20)
	 * </pre>
	*/
	@Nullable
	static <T extends Serializable & Comparable<?>> AttributeLessThan attributeLessThan(@Nonnull String attributeName, @Nullable T attributeValue) {
		return attributeValue == null ? null : new AttributeLessThan(attributeName, attributeValue);
	}

	/**
	 * This `lessThanEquals` is query that compares value of the attribute with name passed in first argument with the value passed
	 * in the second argument. First argument must be {@link String}, second argument may be any of {@link Comparable} type.
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
	 * <pre>
	 * lessThanEquals('age', 20)
	 * </pre>
	*/
	@Nullable
	static <T extends Serializable & Comparable<?>> AttributeLessThanEquals attributeLessThanEquals(@Nonnull String attributeName, @Nullable T attributeValue) {
		return attributeValue == null ? null : new AttributeLessThanEquals(attributeName, attributeValue);
	}

	/**
	 * This `greaterThan` is query that compares value of the attribute with name passed in first argument with the value passed
	 * in the second argument. First argument must be {@link String}, second argument may be any of {@link Comparable} type.
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
	 * <pre>
	 * greaterThan('age', 20)
	 * </pre>
	*/
	@Nullable
	static <T extends Serializable & Comparable<?>> AttributeGreaterThan attributeGreaterThan(@Nonnull String attributeName, @Nullable T attributeValue) {
		return attributeValue == null ? null : new AttributeGreaterThan(attributeName, attributeValue);
	}

	/**
	 * This `greaterThanEquals` is query that compares value of the attribute with name passed in first argument with the value passed
	 * in the second argument. First argument must be {@link String}, second argument may be any of {@link Comparable} type.
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
	 * <pre>
	 * greaterThanEquals('age', 20)
	 * </pre>
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
	 * The constraint `hierarchyWithin` allows you to restrict the search to only those entities that are part of
	 * the hierarchy tree starting with the root node identified by the first argument of this constraint. In e-commerce
	 * systems the typical representative of a hierarchical entity is a category, which will be used in all of our examples.
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - optional name of the queried entity reference schema that represents the relationship to the hierarchical entity
	 *   type, your entity may target different hierarchical entities in different reference types, or it may target
	 *   the same hierarchical entity through multiple semantically different references, and that is why the reference name
	 *   is used instead of the target entity type.
	 * - a single mandatory filter constraint that identifies one or more hierarchy nodes that act as hierarchy roots;
	 *   multiple constraints must be enclosed in AND / OR containers
	 * - optional constraints allow you to narrow the scope of the hierarchy; none or all of the constraints may be present:
	 * 
	 *      - {@link HierarchyDirectRelation}
	 *      - {@link HierarchyHaving}
	 *      - {@link HierarchyExcluding}
	 *      - {@link HierarchyExcludingRoot}
	 * 
	 * The most straightforward usage is filtering the hierarchical entities themselves.
	 * 
	 * <pre>
	 * query(
	 *     collection('Category'),
	 *     filterBy(
	 *         hierarchyWithinSelf(
	 *             attributeEquals('code', 'accessories')
	 *         )
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent('code')
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * The `hierarchyWithin` constraint can also be used for entities that directly reference a hierarchical entity type.
	 * The most common use case from the e-commerce world is a product that is assigned to one or more categories.
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'accessories')
	 *         )
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent('code')
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * Products assigned to two or more subcategories of Accessories category will only appear once in the response
	 * (contrary to what you might expect if you have experience with SQL).
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
	 * The constraint `hierarchyWithin` allows you to restrict the search to only those entities that are part of
	 * the hierarchy tree starting with the root node identified by the first argument of this constraint. In e-commerce
	 * systems the typical representative of a hierarchical entity is a category, which will be used in all of our examples.
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - optional name of the queried entity reference schema that represents the relationship to the hierarchical entity
	 *   type, your entity may target different hierarchical entities in different reference types, or it may target
	 *   the same hierarchical entity through multiple semantically different references, and that is why the reference name
	 *   is used instead of the target entity type.
	 * - a single mandatory filter constraint that identifies one or more hierarchy nodes that act as hierarchy roots;
	 *   multiple constraints must be enclosed in AND / OR containers
	 * - optional constraints allow you to narrow the scope of the hierarchy; none or all of the constraints may be present:
	 * 
	 *      - {@link HierarchyDirectRelation}
	 *      - {@link HierarchyHaving}
	 *      - {@link HierarchyExcluding}
	 *      - {@link HierarchyExcludingRoot}
	 * 
	 * The most straightforward usage is filtering the hierarchical entities themselves.
	 * 
	 * <pre>
	 * query(
	 *     collection('Category'),
	 *     filterBy(
	 *         hierarchyWithinSelf(
	 *             attributeEquals('code', 'accessories')
	 *         )
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent('code')
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * The `hierarchyWithin` constraint can also be used for entities that directly reference a hierarchical entity type.
	 * The most common use case from the e-commerce world is a product that is assigned to one or more categories.
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'accessories')
	 *         )
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent('code')
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * Products assigned to two or more subcategories of Accessories category will only appear once in the response
	 * (contrary to what you might expect if you have experience with SQL).
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
	 * The constraint `hierarchyWithinRoot` allows you to restrict the search to only those entities that are part of
	 * the entire hierarchy tree. In e-commerce systems the typical representative of a hierarchical entity is a category.
	 * 
	 * The single difference to {@link HierarchyWithin} constraint is that it doesn't accept a root node specification.
	 * Because evitaDB accepts multiple root nodes in your entity hierarchy, it may be helpful to imagine there is
	 * an invisible "virtual" top root above all the top nodes (whose parent property remains NULL) you have in your entity
	 * hierarchy and this virtual top root is targeted by this constraint.
	 * 
	 * - The constraint accepts following arguments:
	 * 
	 * - optional name of the queried entity reference schema that represents the relationship to the hierarchical entity
	 *   type, your entity may target different hierarchical entities in different reference types, or it may target
	 *   the same hierarchical entity through multiple semantically different references, and that is why the reference name
	 *   is used instead of the target entity type.
	 * - optional constraints allow you to narrow the scope of the hierarchy; none or all of the constraints may be present:
	 * 
	 *      - {@link HierarchyDirectRelation}
	 *      - {@link HierarchyHaving}
	 *      - {@link HierarchyExcluding}
	 * 
	 * The `hierarchyWithinRoot`, which targets the Category collection itself, returns all categories except those that
	 * would point to non-existent parent nodes, such hierarchy nodes are called orphans and do not satisfy any hierarchy
	 * query.
	 * 
	 * <pre>
	 * query(
	 *     collection('Category'),
	 *     filterBy(
	 *         hierarchyWithinRootSelf()
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent('code')
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * The `hierarchyWithinRoot` constraint can also be used for entities that directly reference a hierarchical entity
	 * type. The most common use case from the e-commerce world is a product that is assigned to one or more categories.
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithinRoot('categories')
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent('code')
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * Products assigned to only one orphan category will be missing from the result. Products assigned to two or more
	 * categories will only appear once in the response (contrary to what you might expect if you have experience with SQL).
	*/
	@Nonnull
	static HierarchyWithinRoot hierarchyWithinRootSelf(@Nullable HierarchySpecificationFilterConstraint... with) {
		return with == null ? new HierarchyWithinRoot() : new HierarchyWithinRoot(with);
	}

	/**
	 * The constraint `hierarchyWithinRoot` allows you to restrict the search to only those entities that are part of
	 * the entire hierarchy tree. In e-commerce systems the typical representative of a hierarchical entity is a category.
	 * 
	 * The single difference to {@link HierarchyWithin} constraint is that it doesn't accept a root node specification.
	 * Because evitaDB accepts multiple root nodes in your entity hierarchy, it may be helpful to imagine there is
	 * an invisible "virtual" top root above all the top nodes (whose parent property remains NULL) you have in your entity
	 * hierarchy and this virtual top root is targeted by this constraint.
	 * 
	 * - The constraint accepts following arguments:
	 * 
	 * - optional name of the queried entity reference schema that represents the relationship to the hierarchical entity
	 *   type, your entity may target different hierarchical entities in different reference types, or it may target
	 *   the same hierarchical entity through multiple semantically different references, and that is why the reference name
	 *   is used instead of the target entity type.
	 * - optional constraints allow you to narrow the scope of the hierarchy; none or all of the constraints may be present:
	 * 
	 *      - {@link HierarchyDirectRelation}
	 *      - {@link HierarchyHaving}
	 *      - {@link HierarchyExcluding}
	 * 
	 * The `hierarchyWithinRoot`, which targets the Category collection itself, returns all categories except those that
	 * would point to non-existent parent nodes, such hierarchy nodes are called orphans and do not satisfy any hierarchy
	 * query.
	 * 
	 * <pre>
	 * query(
	 *     collection('Category'),
	 *     filterBy(
	 *         hierarchyWithinRootSelf()
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent('code')
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * The `hierarchyWithinRoot` constraint can also be used for entities that directly reference a hierarchical entity
	 * type. The most common use case from the e-commerce world is a product that is assigned to one or more categories.
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithinRoot('categories')
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent('code')
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * Products assigned to only one orphan category will be missing from the result. Products assigned to two or more
	 * categories will only appear once in the response (contrary to what you might expect if you have experience with SQL).
	*/
	@Nonnull
	static HierarchyWithinRoot hierarchyWithinRoot(@Nonnull String referenceName, @Nullable HierarchySpecificationFilterConstraint... with) {
		return with == null ? new HierarchyWithinRoot() : new HierarchyWithinRoot(referenceName, with);
	}

	/**
	 * The constraint `having` is a constraint that can only be used within {@link HierarchyWithin} or
	 * {@link HierarchyWithinRoot} parent constraints. It simply makes no sense anywhere else because it changes the default
	 * behavior of those constraints. Hierarchy constraints return all hierarchy children of the parent node or entities
	 * that are transitively or directly related to them, and the parent node itself.
	 * 
	 * The having constraint allows you to set a constraint that must be fulfilled by all categories in the category scope
	 * in order to be accepted by hierarchy within filter. This constraint is especially useful if you want to conditionally
	 * display certain parts of the tree. Imagine you have a category Christmas Sale that should only be available during
	 * a certain period of the year, or a category B2B Partners that should only be accessible to a certain role of users.
	 * All of these scenarios can take advantage of the having constraint (but there are other approaches to solving
	 * the above use cases).
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - one or more mandatory constraints that must be satisfied by all returned hierarchy nodes and that mark the visible
	 *   part of the tree, the implicit relation between constraints is logical conjunction (boolean AND)
	 * 
	 * When the hierarchy constraint targets the hierarchy entity, the children that don't satisfy the inner constraints
	 * (and their children, whether they satisfy them or not) are excluded from the result.
	 * 
	 * For demonstration purposes, let's list all categories within the Accessories category, but only those that are valid
	 * at 01:00 AM on October 1, 2023.
	 * 
	 * <pre>
	 * query(
	 *     collection('Category'),
	 *     filterBy(
	 *         hierarchyWithinSelf(
	 *             attributeEquals('code', 'accessories'),
	 *             having(
	 *                 or(
	 *                     attributeIsNull('validity'),
	 *                     attributeInRange('validity', 2023-10-01T01:00:00-01:00)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent('code')
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * Because the category Christmas electronics has its validity set to be valid only between December 1st and December
	 * 24th, it will be omitted from the result. If it had subcategories, they would also be omitted (even if they had no
	 * validity restrictions).
	 * 
	 * If the hierarchy constraint targets a non-hierarchical entity that references the hierarchical one (typical example
	 * is a product assigned to a category), the having constraint is evaluated against the hierarchical entity (category),
	 * but affects the queried non-hierarchical entities (products). It excludes all products referencing categories that
	 * don't satisfy the having inner constraints.
	 * 
	 * Let's use again our example with Christmas electronics that is valid only between 1st and 24th December. To list all
	 * products available at 01:00 AM on October 1, 2023, issue a following query:
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'accessories'),
	 *             having(
	 *                 or(
	 *                     attributeIsNull('validity'),
	 *                     attributeInRange('validity', 2023-10-01T01:00:00-01:00)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent('code')
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * You can see that Christmas products like Retlux Blue Christmas lightning, Retlux Warm white Christmas lightning or
	 * Emos Candlestick are not present in the listing.
	 * 
	 * <strong>The lookup stops at the first node that doesn't satisfy the constraint!</strong>
	 * 
	 * The hierarchical query traverses from the root nodes to the leaf nodes. For each of the nodes, the engine checks
	 * whether the having constraint is still valid, and if not, it excludes that hierarchy node and all of its child nodes
	 * (entire subtree).
	 * 
	 * <strong>What if the product is linked to two categories - one that meets the constraint and one that does not?</strong>
	 * 
	 * In the situation where the single product, let's say Garmin Vivosmart 5, is in both the excluded category Christmas
	 * Electronics and the included category Smartwatches, it will remain in the query result because there is at least one
	 * product reference that is part of the visible part of the tree.
	*/
	@Nullable
	static HierarchyHaving having(@Nullable FilterConstraint... includeChildTreeConstraints) {
		if (ArrayUtils.isEmpty(includeChildTreeConstraints)) {
			return null;
		}
		return new HierarchyHaving(includeChildTreeConstraints);
	}

	/**
	 * The constraint `excluding` is a constraint that can only be used within {@link HierarchyWithin} or
	 * {@link HierarchyWithinRoot} parent constraints. It simply makes no sense anywhere else because it changes the default
	 * behavior of those constraints. Hierarchy constraints return all hierarchy children of the parent node or entities
	 * that are transitively or directly related to them, and the parent node itself.
	 * 
	 * The excluding constraint allows you to exclude one or more subtrees from the scope of the filter. This constraint is
	 * the exact opposite of the having constraint. If the constraint is true for a hierarchy entity, it and all of its
	 * children are excluded from the query. The excluding constraint is the same as declaring `having(not(expression))`,
	 * but for the sake of readability it has its own constraint.
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - one or more mandatory constraints that must be satisfied by all returned hierarchy nodes and that mark the visible
	 *   part of the tree, the implicit relation between constraints is logical conjunction (boolean AND)
	 * 
	 * When the hierarchy constraint targets the hierarchy entity, the children that satisfy the inner constraints (and
	 * their children, whether they satisfy them or not) are excluded from the result.
	 * 
	 * For demonstration purposes, let's list all categories within the Accessories category, but exclude exactly
	 * the Wireless headphones subcategory.
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'accessories'),
	 *             excluding(
	 *                 attributeEquals('code', 'wireless-headphones')
	 *             )
	 *         )
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent('code')
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * The category Wireless Headphones and all its subcategories will not be shown in the results list.
	 * 
	 * If the hierarchy constraint targets a non-hierarchical entity that references the hierarchical one (typical example
	 * is a product assigned to a category), the excluding constraint is evaluated against the hierarchical entity
	 * (category), but affects the queried non-hierarchical entities (products). It excludes all products referencing
	 * categories that satisfy the excluding inner constraints.
	 * 
	 * Let's go back to our example query that excludes the Wireless Headphones category subtree. To list all products
	 * available in the Accessories category except those related to the Wireless Headphones category or its subcategories,
	 * issue the following query:
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'accessories'),
	 *             excluding(
	 *                 attributeEquals('code', 'wireless-headphones')
	 *             )
	 *         )
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent('code')
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * You can see that wireless headphone products like Huawei FreeBuds 4, Jabra Elite 3 or Adidas FWD-02 Sport are not
	 * present in the listing.
	 * 
	 * When the product is assigned to two categories - one excluded and one part of the visible category tree, the product
	 * remains in the result. See the example.
	 * 
	 * <strong>The lookup stops at the first node that satisfies the constraint!</strong>
	 * 
	 * The hierarchical query traverses from the root nodes to the leaf nodes. For each of the nodes, the engine checks
	 * whether the excluding constraint is satisfied valid, and if so, it excludes that hierarchy node and all of its child
	 * nodes (entire subtree).
	*/
	@Nullable
	static HierarchyExcluding excluding(@Nullable FilterConstraint... excludeChildTreeConstraints) {
		if (ArrayUtils.isEmpty(excludeChildTreeConstraints)) {
			return null;
		}
		return new HierarchyExcluding(excludeChildTreeConstraints);
	}

	/**
	 * The constraint `directRelation` is a constraint that can only be used within {@link HierarchyWithin} or
	 * {@link HierarchyWithinRoot} parent constraints. It simply makes no sense anywhere else because it changes the default
	 * behavior of those constraints. Hierarchy constraints return all hierarchy children of the parent node or entities
	 * that are transitively or directly related to them and the parent node itself. If the directRelation is used as
	 * a sub-constraint, this behavior changes and only direct descendants or directly referencing entities are matched.
	 * 
	 * If the hierarchy constraint targets the hierarchy entity, the `directRelation` will cause only the children of
	 * a direct parent node to be returned. In the case of the hierarchyWithinRoot constraint, the parent is an invisible
	 * "virtual" top root - so only the top-level categories are returned.
	 * 
	 * <pre>
	 * query(
	 *     collection('Category'),
	 *     filterBy(
	 *         hierarchyWithinRootSelf(
	 *             directRelation()
	 *         )
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent('code')
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * If the hierarchy constraint targets a non-hierarchical entity that references the hierarchical one (typical example
	 * is a product assigned to a category), it can only be used in the hierarchyWithin parent constraint.
	 * 
	 * In the case of {@link HierarchyWithinRoot}, the `directRelation` constraint makes no sense because no entity can be
	 * assigned to a "virtual" top parent root.
	 * 
	 * So we can only list products that are directly related to a certain category. We can list products that have
	 * Smartwatches category assigned:
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'smartwatches'),
	 *             directRelation()
	 *         )
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent('code')
	 *         )
	 *     )
	 * )
	 * </pre>
	*/
	@Nonnull
	static HierarchyDirectRelation directRelation() {
		return new HierarchyDirectRelation();
	}

	/**
	 * The constraint `excludingRoot` is a constraint that can only be used within {@link HierarchyWithin} or
	 * {@link HierarchyWithinRoot} parent constraints. It simply makes no sense anywhere else because it changes the default
	 * behavior of those constraints. Hierarchy constraints return all hierarchy children of the parent node or entities
	 * that are transitively or directly related to them and the parent node itself. When the excludingRoot is used as
	 * a sub-constraint, this behavior changes and the parent node itself or the entities directly related to that parent
	 * node are be excluded from the result.
	 * 
	 * If the hierarchy constraint targets the hierarchy entity, the `excludingRoot` will omit the requested parent node
	 * from the result. In the case of the {@link HierarchyWithinRoot} constraint, the parent is an invisible "virtual" top
	 * root, and this constraint makes no sense.
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'accessories'),
	 *             excludingRoot()
	 *         )
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent('code')
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * If the hierarchy constraint targets a non-hierarchical entity that references the hierarchical one (typical example
	 * is a product assigned to a category), the `excludingRoot` constraint can only be used in the {@link HierarchyWithin}
	 * parent constraint.
	 * 
	 * In the case of {@link HierarchyWithinRoot}, the `excludingRoot` constraint makes no sense because no entity can be
	 * assigned to a "virtual" top parent root.
	 * 
	 * Because we learned that Accessories category has no directly assigned products, the `excludingRoot` constraint
	 * presence would not affect the query result. Therefore, we choose Keyboard category for our example. When we list all
	 * products in Keyboard category using {@link HierarchyWithin} constraint, we obtain 20 items. When the `excludingRoot`
	 * constraint is used:
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'keyboards'),
	 *             excludingRoot()
	 *         )
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent('code')
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ... we get only 4 items, which means that 16 were assigned directly to Keyboards category and only 4 of them were
	 * assigned to Exotic keyboards.
	*/
	@Nonnull
	static HierarchyExcludingRoot excludingRoot() {
		return new HierarchyExcludingRoot();
	}

	/**
	 * If any filter constraint of the query targets a localized attribute, the `entityLocaleEquals` must also be provided,
	 * otherwise the query interpreter will return an error. Localized attributes must be identified by both their name and
	 * {@link Locale} in order to be used.
	 * 
	 * Only a single occurrence of entityLocaleEquals is allowed in the filter part of the query. Currently, there is no way
	 * to switch context between different parts of the filter and build queries such as find a product whose name in en-US
	 * is "screwdriver" or in cs is "šroubovák".
	 * 
	 * Also, it's not possible to omit the language specification for a localized attribute and ask questions like: find
	 * a product whose name in any language is "screwdriver".
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'vouchers-for-shareholders')
	 *         ),
	 *         entityLocaleEquals('en')
	 *     ),
	 *     require(
	 *        entityFetch(
	 *            attributeContent('code', 'name')
	 *        )
	 *     )
	 * )
	 * </pre>
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
	 * and time passed in the second argument. First argument must be {@link String}, second argument must be
	 * {@link OffsetDateTime} type. If second argument is not passed - current date and time (now) is used.
	 * Type of the attribute value must implement {@link Range} interface.
	 * 
	 * Function returns true if second argument is greater than or equal to range start (from), and is lesser than
	 * or equal to range end (to).
	 * 
	 * Example:
	 * 
	 * <pre>
	 * inRange('valid', 2020-07-30T20:37:50+00:00)
	 * inRange('age', 18)
	 * </pre>
	 * 
	 * Function supports attribute arrays and when attribute is of array type `inRange` returns true if any of attribute
	 * values has range, that envelopes the passed value the value in the query. If we have the attribute `age` with value
	 * `[[18, 25],[60,65]]` all these constraints will match:
	 * 
	 * <pre>
	 * inRange('age', 18)
	 * inRange('age', 24)
	 * inRange('age', 63)
	 * </pre>
	*/
	@Nullable
	static AttributeInRange attributeInRange(@Nonnull String attributeName, @Nullable OffsetDateTime atTheMoment) {
		return atTheMoment == null ? null : new AttributeInRange(attributeName, atTheMoment);
	}

	/**
	 * This `inRange` is query that compares value of the attribute with name passed in first argument with the date
	 * and time passed in the second argument. First argument must be {@link String}, second argument must be
	 * {@link OffsetDateTime} type. If second argument is not passed - current date and time (now) is used.
	 * Type of the attribute value must implement {@link Range} interface.
	 * 
	 * Function returns true if second argument is greater than or equal to range start (from), and is lesser than
	 * or equal to range end (to).
	 * 
	 * Example:
	 * 
	 * <pre>
	 * inRange('valid', 2020-07-30T20:37:50+00:00)
	 * inRange('age', 18)
	 * </pre>
	 * 
	 * Function supports attribute arrays and when attribute is of array type `inRange` returns true if any of attribute
	 * values has range, that envelopes the passed value the value in the query. If we have the attribute `age` with value
	 * `[[18, 25],[60,65]]` all these constraints will match:
	 * 
	 * <pre>
	 * inRange('age', 18)
	 * inRange('age', 24)
	 * inRange('age', 63)
	 * </pre>
	*/
	@Nullable
	static AttributeInRange attributeInRange(@Nonnull String attributeName, @Nullable Number theValue) {
		return theValue == null ? null : new AttributeInRange(attributeName, theValue);
	}

	/**
	 * This `inRange` is query that compares value of the attribute with name passed in first argument with the date
	 * and time passed in the second argument. First argument must be {@link String}, second argument must be
	 * {@link OffsetDateTime} type. If second argument is not passed - current date and time (now) is used.
	 * Type of the attribute value must implement {@link Range} interface.
	 * 
	 * Function returns true if second argument is greater than or equal to range start (from), and is lesser than
	 * or equal to range end (to).
	 * 
	 * Example:
	 * 
	 * <pre>
	 * inRange('valid', 2020-07-30T20:37:50+00:00)
	 * inRange('age', 18)
	 * </pre>
	 * 
	 * Function supports attribute arrays and when attribute is of array type `inRange` returns true if any of attribute
	 * values has range, that envelopes the passed value the value in the query. If we have the attribute `age` with value
	 * `[[18, 25],[60,65]]` all these constraints will match:
	 * 
	 * <pre>
	 * inRange('age', 18)
	 * inRange('age', 24)
	 * inRange('age', 63)
	 * </pre>
	*/
	@Nonnull
	static AttributeInRange attributeInRangeNow(@Nonnull String attributeName) {
		return new AttributeInRange(attributeName);
	}

	/**
	 * This `inSet` is query that compares value of the attribute with name passed in first argument with all the values passed
	 * in the second, third and additional arguments. First argument must be {@link String}, additional arguments may be any
	 * of {@link Comparable} type.
	 * 
	 * Type of the attribute value and additional arguments must be convertible one to another otherwise `in` function
	 * skips value comparison and ultimately returns false.
	 * 
	 * Function returns true if attribute value is equal to at least one of additional values.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * inSet('level', 1, 2, 3)
	 * </pre>
	 * 
	 * Function supports attribute arrays and when attribute is of array type `inSet` returns true if any of attribute values
	 * equals the value in the query. If we have the attribute `code` with value `['A','B','C']` all these constraints will
	 * match:
	 * 
	 * <pre>
	 * inSet('code','A','D')
	 * inSet('code','A', 'B')
	 * </pre>
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
	 * <pre>
	 * equals('code', 'abc')
	 * </pre>
	 * 
	 * Function supports attribute arrays and when attribute is of array type `equals` returns true if any of attribute values
	 * equals the value in the query. If we have the attribute `code` with value `['A','B','C']` all these constraints will
	 * match:
	 * 
	 * <pre>
	 * equals('code','A')
	 * equals('code','B')
	 * equals('code','C')
	 * </pre>
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
	 * <pre>
	 * equals('code', 'abc')
	 * </pre>
	 * 
	 * Function supports attribute arrays and when attribute is of array type `equals` returns true if any of attribute values
	 * equals the value in the query. If we have the attribute `code` with value `['A','B','C']` all these constraints will
	 * match:
	 * 
	 * <pre>
	 * equals('code','A')
	 * equals('code','B')
	 * equals('code','C')
	 * </pre>
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
	 * - {@link AttributeSpecialValue#NULL}
	 * - {@link AttributeSpecialValue#NOT_NULL}
	 * 
	 * Function returns true if attribute has (explicitly or implicitly) passed special value.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * attributeIs('visible', NULL)
	 * </pre>
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
	 * - {@link AttributeSpecialValue#NULL}
	 * - {@link AttributeSpecialValue#NOT_NULL}
	 * 
	 * Function returns true if attribute has (explicitly or implicitly) passed special value.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * attributeIs('visible', NULL)
	 * </pre>
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
	 * - {@link AttributeSpecialValue#NULL}
	 * - {@link AttributeSpecialValue#NOT_NULL}
	 * 
	 * Function returns true if attribute has (explicitly or implicitly) passed special value.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * attributeIs('visible', NULL)
	 * </pre>
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
	static EntityPrimaryKeyInSet entityPrimaryKeyInSet(@Nullable int[] primaryKey) {
		if (primaryKey == null) {
			return null;
		}
		return new EntityPrimaryKeyInSet(Arrays.stream(primaryKey).boxed().toArray(Integer[]::new));
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
	 * <pre>
	 * facetSummaryOfReference(
	 *    'parameters',
	 *    orderGroupBy(
	 *       attributeNatural('name', OrderDirection.ASC)
	 *    )
	 * )
	 * </pre>
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
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         referenceHaving(
	 *             'brand',
	 *             entityHaving(
	 *                 attributeEquals('code','sony')
	 *             )
	 *         )
	 *     ),
	 *     orderBy(
	 *         referenceProperty(
	 *             'brand',
	 *             attributeNatural('orderInBrand', ASC)
	 *         )
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent('code'),
	 *             referenceContentWithAttributes(
	 *                 'brand',
	 *                 attributeContent('orderInBrand')
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
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
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         attributeEquals('code', 'garmin-vivoactive-4')
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent('code'),
	 *             referenceContent(
	 *                 'parameterValues',
	 *                 orderBy(
	 *                     entityProperty(
	 *                         attributeNatural('code', DESC)
	 *                     )
	 *                 ),
	 *                 entityFetch(
	 *                     attributeContent('code')
	 *                 )
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
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
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     orderBy(
	 *         attributeNatural('orderedQuantity', DESC)
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent('code', 'orderedQuantity')
	 *         )
	 *     )
	 * )
	 * </pre>
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
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     orderBy(
	 *         attributeNatural('orderedQuantity', DESC)
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent('code', 'orderedQuantity')
	 *         )
	 *     )
	 * )
	 * </pre>
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
	 * <pre>
	 * random()
	 * </pre>
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
	 * The requirement triggers the calculation of the Hierarchy data structure for the hierarchy of which it is a part.
	 * 
	 * The hierarchy of self can still be combined with {@link HierarchyOfReference} if the queried entity is a hierarchical
	 * entity that is also connected to another hierarchical entity. Such situations are rather sporadic in reality.
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - specification of one or more reference names that identify the reference to the target hierarchical entity for
	 *   which the menu calculation should be performed; usually only one reference name makes sense, but to adapt
	 *   the constraint to the behavior of other similar constraints, evitaQL accepts multiple reference names for the case
	 *   that the same requirements apply to different references of the queried entity.
	 * - optional argument of type EmptyHierarchicalEntityBehaviour enum allowing you to specify whether or not to return
	 *   empty hierarchical entities (e.g., those that do not have any queried entities that satisfy the current query
	 *   filter constraint assigned to them - either directly or transitively):
	 * 
	 *      - {@link EmptyHierarchicalEntityBehaviour#LEAVE_EMPTY}: empty hierarchical nodes will remain in computed data
	 *        structures
	 *      - {@link EmptyHierarchicalEntityBehaviour#REMOVE_EMPTY}: empty hierarchical nodes are omitted from computed data
	 *        structures
	 * 
	 * - optional ordering constraint that allows you to specify an order of Hierarchy LevelInfo elements in the result
	 *   hierarchy data structure
	 * - mandatory one or more constraints allowing you to instruct evitaDB to calculate menu components; one or all of
	 *   the constraints may be present:
	 * 
	 *      - {@link HierarchyFromRoot}
	 *      - {@link HierarchyFromNode}
	 *      - {@link HierarchySiblings}
	 *      - {@link HierarchyChildren}
	 *      - {@link HierarchyParents}
	*/
	@Nullable
	static HierarchyOfSelf hierarchyOfSelf(@Nullable HierarchyRequireConstraint... requirement) {
		return ArrayUtils.isEmpty(requirement) ? null : new HierarchyOfSelf(null, requirement);
	}

	/**
	 * The requirement triggers the calculation of the Hierarchy data structure for the hierarchy of which it is a part.
	 * 
	 * The hierarchy of self can still be combined with {@link HierarchyOfReference} if the queried entity is a hierarchical
	 * entity that is also connected to another hierarchical entity. Such situations are rather sporadic in reality.
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - specification of one or more reference names that identify the reference to the target hierarchical entity for
	 *   which the menu calculation should be performed; usually only one reference name makes sense, but to adapt
	 *   the constraint to the behavior of other similar constraints, evitaQL accepts multiple reference names for the case
	 *   that the same requirements apply to different references of the queried entity.
	 * - optional argument of type EmptyHierarchicalEntityBehaviour enum allowing you to specify whether or not to return
	 *   empty hierarchical entities (e.g., those that do not have any queried entities that satisfy the current query
	 *   filter constraint assigned to them - either directly or transitively):
	 * 
	 *      - {@link EmptyHierarchicalEntityBehaviour#LEAVE_EMPTY}: empty hierarchical nodes will remain in computed data
	 *        structures
	 *      - {@link EmptyHierarchicalEntityBehaviour#REMOVE_EMPTY}: empty hierarchical nodes are omitted from computed data
	 *        structures
	 * 
	 * - optional ordering constraint that allows you to specify an order of Hierarchy LevelInfo elements in the result
	 *   hierarchy data structure
	 * - mandatory one or more constraints allowing you to instruct evitaDB to calculate menu components; one or all of
	 *   the constraints may be present:
	 * 
	 *      - {@link HierarchyFromRoot}
	 *      - {@link HierarchyFromNode}
	 *      - {@link HierarchySiblings}
	 *      - {@link HierarchyChildren}
	 *      - {@link HierarchyParents}
	*/
	@Nullable
	static HierarchyOfSelf hierarchyOfSelf(
		@Nullable OrderBy orderBy,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		return ArrayUtils.isEmpty(requirement) ? null : new HierarchyOfSelf(orderBy, requirement);
	}

	/**
	 * The requirement triggers the calculation of the Hierarchy data structure for the hierarchies of the referenced entity
	 * type.
	 * 
	 * The hierarchy of reference can still be combined with {@link HierarchyOfSelf} if the queried entity is a hierarchical
	 * entity that is also connected to another hierarchical entity. Such situations are rather sporadic in reality.
	 * 
	 * The `hierarchyOfReference` can be repeated multiple times in a single query if you need different calculation
	 * settings for different reference types.
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - specification of one or more reference names that identify the reference to the target hierarchical entity for
	 *   which the menu calculation should be performed; usually only one reference name makes sense, but to adapt
	 *   the constraint to the behavior of other similar constraints, evitaQL accepts multiple reference names for the case
	 *   that the same requirements apply to different references of the queried entity.
	 * - optional argument of type EmptyHierarchicalEntityBehaviour enum allowing you to specify whether or not to return
	 *   empty hierarchical entities (e.g., those that do not have any queried entities that satisfy the current query
	 *   filter constraint assigned to them - either directly or transitively):
	 * 
	 *      - {@link EmptyHierarchicalEntityBehaviour#LEAVE_EMPTY}: empty hierarchical nodes will remain in computed data
	 *        structures
	 *      - {@link EmptyHierarchicalEntityBehaviour#REMOVE_EMPTY}: empty hierarchical nodes are omitted from computed data
	 *        structures
	 * 
	 * - optional ordering constraint that allows you to specify an order of Hierarchy LevelInfo elements in the result
	 *   hierarchy data structure
	 * - mandatory one or more constraints allowing you to instruct evitaDB to calculate menu components; one or all of
	 *   the constraints may be present:
	 * 
	 *      - {@link HierarchyFromRoot}
	 *      - {@link HierarchyFromNode}
	 *      - {@link HierarchySiblings}
	 *      - {@link HierarchyChildren}
	 *      - {@link HierarchyParents}
	*/
	@Nullable
	static HierarchyOfReference hierarchyOfReference(
		@Nullable String referenceName,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		return hierarchyOfReference(referenceName, null, null, requirement);
	}

	/**
	 * The requirement triggers the calculation of the Hierarchy data structure for the hierarchies of the referenced entity
	 * type.
	 * 
	 * The hierarchy of reference can still be combined with {@link HierarchyOfSelf} if the queried entity is a hierarchical
	 * entity that is also connected to another hierarchical entity. Such situations are rather sporadic in reality.
	 * 
	 * The `hierarchyOfReference` can be repeated multiple times in a single query if you need different calculation
	 * settings for different reference types.
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - specification of one or more reference names that identify the reference to the target hierarchical entity for
	 *   which the menu calculation should be performed; usually only one reference name makes sense, but to adapt
	 *   the constraint to the behavior of other similar constraints, evitaQL accepts multiple reference names for the case
	 *   that the same requirements apply to different references of the queried entity.
	 * - optional argument of type EmptyHierarchicalEntityBehaviour enum allowing you to specify whether or not to return
	 *   empty hierarchical entities (e.g., those that do not have any queried entities that satisfy the current query
	 *   filter constraint assigned to them - either directly or transitively):
	 * 
	 *      - {@link EmptyHierarchicalEntityBehaviour#LEAVE_EMPTY}: empty hierarchical nodes will remain in computed data
	 *        structures
	 *      - {@link EmptyHierarchicalEntityBehaviour#REMOVE_EMPTY}: empty hierarchical nodes are omitted from computed data
	 *        structures
	 * 
	 * - optional ordering constraint that allows you to specify an order of Hierarchy LevelInfo elements in the result
	 *   hierarchy data structure
	 * - mandatory one or more constraints allowing you to instruct evitaDB to calculate menu components; one or all of
	 *   the constraints may be present:
	 * 
	 *      - {@link HierarchyFromRoot}
	 *      - {@link HierarchyFromNode}
	 *      - {@link HierarchySiblings}
	 *      - {@link HierarchyChildren}
	 *      - {@link HierarchyParents}
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
	 * The requirement triggers the calculation of the Hierarchy data structure for the hierarchies of the referenced entity
	 * type.
	 * 
	 * The hierarchy of reference can still be combined with {@link HierarchyOfSelf} if the queried entity is a hierarchical
	 * entity that is also connected to another hierarchical entity. Such situations are rather sporadic in reality.
	 * 
	 * The `hierarchyOfReference` can be repeated multiple times in a single query if you need different calculation
	 * settings for different reference types.
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - specification of one or more reference names that identify the reference to the target hierarchical entity for
	 *   which the menu calculation should be performed; usually only one reference name makes sense, but to adapt
	 *   the constraint to the behavior of other similar constraints, evitaQL accepts multiple reference names for the case
	 *   that the same requirements apply to different references of the queried entity.
	 * - optional argument of type EmptyHierarchicalEntityBehaviour enum allowing you to specify whether or not to return
	 *   empty hierarchical entities (e.g., those that do not have any queried entities that satisfy the current query
	 *   filter constraint assigned to them - either directly or transitively):
	 * 
	 *      - {@link EmptyHierarchicalEntityBehaviour#LEAVE_EMPTY}: empty hierarchical nodes will remain in computed data
	 *        structures
	 *      - {@link EmptyHierarchicalEntityBehaviour#REMOVE_EMPTY}: empty hierarchical nodes are omitted from computed data
	 *        structures
	 * 
	 * - optional ordering constraint that allows you to specify an order of Hierarchy LevelInfo elements in the result
	 *   hierarchy data structure
	 * - mandatory one or more constraints allowing you to instruct evitaDB to calculate menu components; one or all of
	 *   the constraints may be present:
	 * 
	 *      - {@link HierarchyFromRoot}
	 *      - {@link HierarchyFromNode}
	 *      - {@link HierarchySiblings}
	 *      - {@link HierarchyChildren}
	 *      - {@link HierarchyParents}
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
	 * The requirement triggers the calculation of the Hierarchy data structure for the hierarchies of the referenced entity
	 * type.
	 * 
	 * The hierarchy of reference can still be combined with {@link HierarchyOfSelf} if the queried entity is a hierarchical
	 * entity that is also connected to another hierarchical entity. Such situations are rather sporadic in reality.
	 * 
	 * The `hierarchyOfReference` can be repeated multiple times in a single query if you need different calculation
	 * settings for different reference types.
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - specification of one or more reference names that identify the reference to the target hierarchical entity for
	 *   which the menu calculation should be performed; usually only one reference name makes sense, but to adapt
	 *   the constraint to the behavior of other similar constraints, evitaQL accepts multiple reference names for the case
	 *   that the same requirements apply to different references of the queried entity.
	 * - optional argument of type EmptyHierarchicalEntityBehaviour enum allowing you to specify whether or not to return
	 *   empty hierarchical entities (e.g., those that do not have any queried entities that satisfy the current query
	 *   filter constraint assigned to them - either directly or transitively):
	 * 
	 *      - {@link EmptyHierarchicalEntityBehaviour#LEAVE_EMPTY}: empty hierarchical nodes will remain in computed data
	 *        structures
	 *      - {@link EmptyHierarchicalEntityBehaviour#REMOVE_EMPTY}: empty hierarchical nodes are omitted from computed data
	 *        structures
	 * 
	 * - optional ordering constraint that allows you to specify an order of Hierarchy LevelInfo elements in the result
	 *   hierarchy data structure
	 * - mandatory one or more constraints allowing you to instruct evitaDB to calculate menu components; one or all of
	 *   the constraints may be present:
	 * 
	 *      - {@link HierarchyFromRoot}
	 *      - {@link HierarchyFromNode}
	 *      - {@link HierarchySiblings}
	 *      - {@link HierarchyChildren}
	 *      - {@link HierarchyParents}
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
	 * The requirement triggers the calculation of the Hierarchy data structure for the hierarchies of the referenced entity
	 * type.
	 * 
	 * The hierarchy of reference can still be combined with {@link HierarchyOfSelf} if the queried entity is a hierarchical
	 * entity that is also connected to another hierarchical entity. Such situations are rather sporadic in reality.
	 * 
	 * The `hierarchyOfReference` can be repeated multiple times in a single query if you need different calculation
	 * settings for different reference types.
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - specification of one or more reference names that identify the reference to the target hierarchical entity for
	 *   which the menu calculation should be performed; usually only one reference name makes sense, but to adapt
	 *   the constraint to the behavior of other similar constraints, evitaQL accepts multiple reference names for the case
	 *   that the same requirements apply to different references of the queried entity.
	 * - optional argument of type EmptyHierarchicalEntityBehaviour enum allowing you to specify whether or not to return
	 *   empty hierarchical entities (e.g., those that do not have any queried entities that satisfy the current query
	 *   filter constraint assigned to them - either directly or transitively):
	 * 
	 *      - {@link EmptyHierarchicalEntityBehaviour#LEAVE_EMPTY}: empty hierarchical nodes will remain in computed data
	 *        structures
	 *      - {@link EmptyHierarchicalEntityBehaviour#REMOVE_EMPTY}: empty hierarchical nodes are omitted from computed data
	 *        structures
	 * 
	 * - optional ordering constraint that allows you to specify an order of Hierarchy LevelInfo elements in the result
	 *   hierarchy data structure
	 * - mandatory one or more constraints allowing you to instruct evitaDB to calculate menu components; one or all of
	 *   the constraints may be present:
	 * 
	 *      - {@link HierarchyFromRoot}
	 *      - {@link HierarchyFromNode}
	 *      - {@link HierarchySiblings}
	 *      - {@link HierarchyChildren}
	 *      - {@link HierarchyParents}
	*/
	@Nullable
	static HierarchyOfReference hierarchyOfReference(
		@Nullable String[] referenceName,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		return hierarchyOfReference(referenceName, null, null, requirement);
	}

	/**
	 * The requirement triggers the calculation of the Hierarchy data structure for the hierarchies of the referenced entity
	 * type.
	 * 
	 * The hierarchy of reference can still be combined with {@link HierarchyOfSelf} if the queried entity is a hierarchical
	 * entity that is also connected to another hierarchical entity. Such situations are rather sporadic in reality.
	 * 
	 * The `hierarchyOfReference` can be repeated multiple times in a single query if you need different calculation
	 * settings for different reference types.
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - specification of one or more reference names that identify the reference to the target hierarchical entity for
	 *   which the menu calculation should be performed; usually only one reference name makes sense, but to adapt
	 *   the constraint to the behavior of other similar constraints, evitaQL accepts multiple reference names for the case
	 *   that the same requirements apply to different references of the queried entity.
	 * - optional argument of type EmptyHierarchicalEntityBehaviour enum allowing you to specify whether or not to return
	 *   empty hierarchical entities (e.g., those that do not have any queried entities that satisfy the current query
	 *   filter constraint assigned to them - either directly or transitively):
	 * 
	 *      - {@link EmptyHierarchicalEntityBehaviour#LEAVE_EMPTY}: empty hierarchical nodes will remain in computed data
	 *        structures
	 *      - {@link EmptyHierarchicalEntityBehaviour#REMOVE_EMPTY}: empty hierarchical nodes are omitted from computed data
	 *        structures
	 * 
	 * - optional ordering constraint that allows you to specify an order of Hierarchy LevelInfo elements in the result
	 *   hierarchy data structure
	 * - mandatory one or more constraints allowing you to instruct evitaDB to calculate menu components; one or all of
	 *   the constraints may be present:
	 * 
	 *      - {@link HierarchyFromRoot}
	 *      - {@link HierarchyFromNode}
	 *      - {@link HierarchySiblings}
	 *      - {@link HierarchyChildren}
	 *      - {@link HierarchyParents}
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
	 * The requirement triggers the calculation of the Hierarchy data structure for the hierarchies of the referenced entity
	 * type.
	 * 
	 * The hierarchy of reference can still be combined with {@link HierarchyOfSelf} if the queried entity is a hierarchical
	 * entity that is also connected to another hierarchical entity. Such situations are rather sporadic in reality.
	 * 
	 * The `hierarchyOfReference` can be repeated multiple times in a single query if you need different calculation
	 * settings for different reference types.
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - specification of one or more reference names that identify the reference to the target hierarchical entity for
	 *   which the menu calculation should be performed; usually only one reference name makes sense, but to adapt
	 *   the constraint to the behavior of other similar constraints, evitaQL accepts multiple reference names for the case
	 *   that the same requirements apply to different references of the queried entity.
	 * - optional argument of type EmptyHierarchicalEntityBehaviour enum allowing you to specify whether or not to return
	 *   empty hierarchical entities (e.g., those that do not have any queried entities that satisfy the current query
	 *   filter constraint assigned to them - either directly or transitively):
	 * 
	 *      - {@link EmptyHierarchicalEntityBehaviour#LEAVE_EMPTY}: empty hierarchical nodes will remain in computed data
	 *        structures
	 *      - {@link EmptyHierarchicalEntityBehaviour#REMOVE_EMPTY}: empty hierarchical nodes are omitted from computed data
	 *        structures
	 * 
	 * - optional ordering constraint that allows you to specify an order of Hierarchy LevelInfo elements in the result
	 *   hierarchy data structure
	 * - mandatory one or more constraints allowing you to instruct evitaDB to calculate menu components; one or all of
	 *   the constraints may be present:
	 * 
	 *      - {@link HierarchyFromRoot}
	 *      - {@link HierarchyFromNode}
	 *      - {@link HierarchySiblings}
	 *      - {@link HierarchyChildren}
	 *      - {@link HierarchyParents}
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
	 * The requirement triggers the calculation of the Hierarchy data structure for the hierarchies of the referenced entity
	 * type.
	 * 
	 * The hierarchy of reference can still be combined with {@link HierarchyOfSelf} if the queried entity is a hierarchical
	 * entity that is also connected to another hierarchical entity. Such situations are rather sporadic in reality.
	 * 
	 * The `hierarchyOfReference` can be repeated multiple times in a single query if you need different calculation
	 * settings for different reference types.
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - specification of one or more reference names that identify the reference to the target hierarchical entity for
	 *   which the menu calculation should be performed; usually only one reference name makes sense, but to adapt
	 *   the constraint to the behavior of other similar constraints, evitaQL accepts multiple reference names for the case
	 *   that the same requirements apply to different references of the queried entity.
	 * - optional argument of type EmptyHierarchicalEntityBehaviour enum allowing you to specify whether or not to return
	 *   empty hierarchical entities (e.g., those that do not have any queried entities that satisfy the current query
	 *   filter constraint assigned to them - either directly or transitively):
	 * 
	 *      - {@link EmptyHierarchicalEntityBehaviour#LEAVE_EMPTY}: empty hierarchical nodes will remain in computed data
	 *        structures
	 *      - {@link EmptyHierarchicalEntityBehaviour#REMOVE_EMPTY}: empty hierarchical nodes are omitted from computed data
	 *        structures
	 * 
	 * - optional ordering constraint that allows you to specify an order of Hierarchy LevelInfo elements in the result
	 *   hierarchy data structure
	 * - mandatory one or more constraints allowing you to instruct evitaDB to calculate menu components; one or all of
	 *   the constraints may be present:
	 * 
	 *      - {@link HierarchyFromRoot}
	 *      - {@link HierarchyFromNode}
	 *      - {@link HierarchySiblings}
	 *      - {@link HierarchyChildren}
	 *      - {@link HierarchyParents}
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
	 * The `fromRoot` requirement computes the hierarchy tree starting from the "virtual" invisible top root of
	 * the hierarchy, regardless of the potential use of the {@link HierarchyWithin} constraint in the filtering part of
	 * the query. The scope of the calculated information can be controlled by the stopAt constraint. By default,
	 * the traversal goes all the way to the bottom of the hierarchy tree unless you tell it to stop at anywhere.
	 * If you need to access statistical data, use statistics constraint. Calculated data is not affected by
	 * the {@link HierarchyWithin} filter constraint - the query can filter entities using {@link HierarchyWithin} from
	 * category Accessories, while still allowing you to correctly compute menu at root level.
	 * 
	 * Please keep in mind that the full statistic calculation can be particularly expensive in the case of the fromRoot
	 * requirement - it usually requires aggregation for the entire queried dataset (see more information about
	 * the calculation).
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - mandatory String argument specifying the output name for the calculated data structure
	 * - optional one or more constraints that allow you to define the completeness of the hierarchy entities, the scope of
	 *   the traversed hierarchy tree, and the statistics computed along the way; any or all of the constraints may be
	 *   present:
	 * 
	 *      - {@link EntityFetch}
	 *      - {@link HierarchyStopAt}
	 *      - {@link HierarchyStatistics}
	 * 
	 * The following query lists products in category Audio and its subcategories. Along with the returned products, it also
	 * requires a computed megaMenu data structure that lists the top 2 levels of the Category hierarchy tree with
	 * a computed count of child categories for each menu item and an aggregated count of all filtered products that would
	 * fall into the given category.
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'audio')
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             'categories',
	 *             fromRoot(
	 *                 'megaMenu',
	 *                 entityFetch(attributeContent('code')),
	 *                 stopAt(level(2)),
	 *                 statistics(
	 *                     CHILDREN_COUNT,
	 *                     QUERIED_ENTITY_COUNT
	 *                 )
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * The calculated result for `fromRoot` is not affected by the {@link HierarchyWithin} pivot hierarchy node.
	 * If the {@link HierarchyWithin} contains inner constraints {@link HierarchyHaving} or {@link HierarchyExcluding},
	 * the `fromRoot` respects them. The reason is simple: when you render a menu for the query result, you want
	 * the calculated statistics to respect the rules that apply to the {@link HierarchyWithin} so that the calculated
	 * number remains consistent for the end user.
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
	 * The `fromRoot` requirement computes the hierarchy tree starting from the "virtual" invisible top root of
	 * the hierarchy, regardless of the potential use of the {@link HierarchyWithin} constraint in the filtering part of
	 * the query. The scope of the calculated information can be controlled by the stopAt constraint. By default,
	 * the traversal goes all the way to the bottom of the hierarchy tree unless you tell it to stop at anywhere.
	 * If you need to access statistical data, use statistics constraint. Calculated data is not affected by
	 * the {@link HierarchyWithin} filter constraint - the query can filter entities using {@link HierarchyWithin} from
	 * category Accessories, while still allowing you to correctly compute menu at root level.
	 * 
	 * Please keep in mind that the full statistic calculation can be particularly expensive in the case of the fromRoot
	 * requirement - it usually requires aggregation for the entire queried dataset (see more information about
	 * the calculation).
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - mandatory String argument specifying the output name for the calculated data structure
	 * - optional one or more constraints that allow you to define the completeness of the hierarchy entities, the scope of
	 *   the traversed hierarchy tree, and the statistics computed along the way; any or all of the constraints may be
	 *   present:
	 * 
	 *      - {@link EntityFetch}
	 *      - {@link HierarchyStopAt}
	 *      - {@link HierarchyStatistics}
	 * 
	 * The following query lists products in category Audio and its subcategories. Along with the returned products, it also
	 * requires a computed megaMenu data structure that lists the top 2 levels of the Category hierarchy tree with
	 * a computed count of child categories for each menu item and an aggregated count of all filtered products that would
	 * fall into the given category.
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'audio')
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             'categories',
	 *             fromRoot(
	 *                 'megaMenu',
	 *                 entityFetch(attributeContent('code')),
	 *                 stopAt(level(2)),
	 *                 statistics(
	 *                     CHILDREN_COUNT,
	 *                     QUERIED_ENTITY_COUNT
	 *                 )
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * The calculated result for `fromRoot` is not affected by the {@link HierarchyWithin} pivot hierarchy node.
	 * If the {@link HierarchyWithin} contains inner constraints {@link HierarchyHaving} or {@link HierarchyExcluding},
	 * the `fromRoot` respects them. The reason is simple: when you render a menu for the query result, you want
	 * the calculated statistics to respect the rules that apply to the {@link HierarchyWithin} so that the calculated
	 * number remains consistent for the end user.
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
	 * The `fromNode` requirement computes the hierarchy tree starting from the pivot node of the hierarchy, that is
	 * identified by the node inner constraint. The fromNode calculates the result regardless of the potential use of
	 * the {@link HierarchyWithin} constraint in the filtering part of the query. The scope of the calculated information
	 * can be controlled by the {@link HierarchyStopAt} constraint. By default, the traversal goes all the way to the bottom
	 * of the hierarchy tree unless you tell it to stop at anywhere. Calculated data is not affected by
	 * the {@link HierarchyWithin} filter constraint - the query can filter entities using {@link HierarchyWithin} from
	 * category Accessories, while still allowing you to correctly compute menu at different node defined in a `fromNode`
	 * requirement. If you need to access statistical data, use statistics constraint.
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - mandatory String argument specifying the output name for the calculated data structure
	 * - mandatory require constraint node that must match exactly one pivot hierarchical entity that represents the root
	 *   node of the traversed hierarchy subtree.
	 * - optional one or more constraints that allow you to define the completeness of the hierarchy entities, the scope
	 *   of the traversed hierarchy tree, and the statistics computed along the way; any or all of the constraints may be
	 *   present:
	 * 
	 *      - {@link EntityFetch}
	 *      - {@link HierarchyStopAt}
	 *      - {@link HierarchyStatistics}
	 * 
	 * The following query lists products in category Audio and its subcategories. Along with the products returned, it
	 * also returns a computed sideMenu1 and sideMenu2 data structure that lists the flat category list for the categories
	 * Portables and Laptops with a computed count of child categories for each menu item and an aggregated count of all
	 * products that would fall into the given category.
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'audio')
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             'categories',
	 *             fromNode(
	 *                 'sideMenu1',
	 *                 node(
	 *                     filterBy(
	 *                         attributeEquals('code', 'portables')
	 *                     )
	 *                 ),
	 *                 entityFetch(attributeContent('code')),
	 *                 stopAt(distance(1)),
	 *                 statistics(
	 *                     CHILDREN_COUNT,
	 *                     QUERIED_ENTITY_COUNT
	 *                 )
	 *             ),
	 *             fromNode(
	 *                 'sideMenu2',
	 *                 node(
	 *                     filterBy(
	 *                         attributeEquals('code', 'laptops')
	 *                     )
	 *                 ),
	 *                 entityFetch(attributeContent('code')),
	 *                 stopAt(distance(1)),
	 *                 statistics(
	 *                     CHILDREN_COUNT,
	 *                     QUERIED_ENTITY_COUNT
	 *                 )
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * The calculated result for `fromNode` is not affected by the {@link HierarchyWithin} pivot hierarchy node.
	 * If the {@link HierarchyWithin} contains inner constraints {@link HierarchyHaving} or {@link HierarchyExcluding},
	 * the `fromNode` respects them. The reason is simple: when you render a menu for the query result, you want
	 * the calculated statistics to respect the rules that apply to the hierarchyWithin so that the calculated number
	 * remains consistent for the end user.
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
	 * The `fromNode` requirement computes the hierarchy tree starting from the pivot node of the hierarchy, that is
	 * identified by the node inner constraint. The fromNode calculates the result regardless of the potential use of
	 * the {@link HierarchyWithin} constraint in the filtering part of the query. The scope of the calculated information
	 * can be controlled by the {@link HierarchyStopAt} constraint. By default, the traversal goes all the way to the bottom
	 * of the hierarchy tree unless you tell it to stop at anywhere. Calculated data is not affected by
	 * the {@link HierarchyWithin} filter constraint - the query can filter entities using {@link HierarchyWithin} from
	 * category Accessories, while still allowing you to correctly compute menu at different node defined in a `fromNode`
	 * requirement. If you need to access statistical data, use statistics constraint.
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - mandatory String argument specifying the output name for the calculated data structure
	 * - mandatory require constraint node that must match exactly one pivot hierarchical entity that represents the root
	 *   node of the traversed hierarchy subtree.
	 * - optional one or more constraints that allow you to define the completeness of the hierarchy entities, the scope
	 *   of the traversed hierarchy tree, and the statistics computed along the way; any or all of the constraints may be
	 *   present:
	 * 
	 *      - {@link EntityFetch}
	 *      - {@link HierarchyStopAt}
	 *      - {@link HierarchyStatistics}
	 * 
	 * The following query lists products in category Audio and its subcategories. Along with the products returned, it
	 * also returns a computed sideMenu1 and sideMenu2 data structure that lists the flat category list for the categories
	 * Portables and Laptops with a computed count of child categories for each menu item and an aggregated count of all
	 * products that would fall into the given category.
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'audio')
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             'categories',
	 *             fromNode(
	 *                 'sideMenu1',
	 *                 node(
	 *                     filterBy(
	 *                         attributeEquals('code', 'portables')
	 *                     )
	 *                 ),
	 *                 entityFetch(attributeContent('code')),
	 *                 stopAt(distance(1)),
	 *                 statistics(
	 *                     CHILDREN_COUNT,
	 *                     QUERIED_ENTITY_COUNT
	 *                 )
	 *             ),
	 *             fromNode(
	 *                 'sideMenu2',
	 *                 node(
	 *                     filterBy(
	 *                         attributeEquals('code', 'laptops')
	 *                     )
	 *                 ),
	 *                 entityFetch(attributeContent('code')),
	 *                 stopAt(distance(1)),
	 *                 statistics(
	 *                     CHILDREN_COUNT,
	 *                     QUERIED_ENTITY_COUNT
	 *                 )
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * The calculated result for `fromNode` is not affected by the {@link HierarchyWithin} pivot hierarchy node.
	 * If the {@link HierarchyWithin} contains inner constraints {@link HierarchyHaving} or {@link HierarchyExcluding},
	 * the `fromNode` respects them. The reason is simple: when you render a menu for the query result, you want
	 * the calculated statistics to respect the rules that apply to the hierarchyWithin so that the calculated number
	 * remains consistent for the end user.
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
	 * The children requirement computes the hierarchy tree starting at the same hierarchy node that is targeted by
	 * the filtering part of the same query using the {@link HierarchyWithin} or {@link HierarchyWithinRoot} constraints.
	 * The scope of the calculated information can be controlled by the stopAt constraint. By default, the traversal goes
	 * all the way to the bottom of the hierarchy tree unless you tell it to stop at anywhere. If you need to access
	 * statistical data, use the statistics constraint.
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - mandatory String argument specifying the output name for the calculated data structure
	 * - optional one or more constraints that allow you to define the completeness of the hierarchy entities, the scope of
	 *   the traversed hierarchy tree, and the statistics computed along the way; any or all of the constraints may be
	 *   present:
	 * 
	 *      - {@link EntityFetch}
	 *      - {@link HierarchyStopAt}
	 *      - {@link HierarchyStatistics}
	 * 
	 * The following query lists products in category Audio and its subcategories. Along with the products returned, it also
	 * returns a computed subcategories data structure that lists the flat category list the currently focused category
	 * Audio with a computed count of child categories for each menu item and an aggregated count of all products that would
	 * fall into the given category.
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'audio')
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             'categories',
	 *             children(
	 *                 'subcategories',
	 *                 entityFetch(attributeContent('code')),
	 *                 stopAt(distance(1)),
	 *                 statistics(
	 *                     CHILDREN_COUNT,
	 *                     QUERIED_ENTITY_COUNT
	 *                 )
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * The calculated result for children is connected with the {@link HierarchyWithin} pivot hierarchy node (or
	 * the "virtual" invisible top root referred to by the hierarchyWithinRoot constraint). If the {@link HierarchyWithin}
	 * contains inner constraints {@link HierarchyHaving} or {@link HierarchyExcluding}, the children will respect them as
	 * well. The reason is simple: when you render a menu for the query result, you want the calculated statistics to
	 * respect the rules that apply to the hierarchyWithin so that the calculated number remains consistent for the end
	 * user.
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
	 * The children requirement computes the hierarchy tree starting at the same hierarchy node that is targeted by
	 * the filtering part of the same query using the {@link HierarchyWithin} or {@link HierarchyWithinRoot} constraints.
	 * The scope of the calculated information can be controlled by the stopAt constraint. By default, the traversal goes
	 * all the way to the bottom of the hierarchy tree unless you tell it to stop at anywhere. If you need to access
	 * statistical data, use the statistics constraint.
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - mandatory String argument specifying the output name for the calculated data structure
	 * - optional one or more constraints that allow you to define the completeness of the hierarchy entities, the scope of
	 *   the traversed hierarchy tree, and the statistics computed along the way; any or all of the constraints may be
	 *   present:
	 * 
	 *      - {@link EntityFetch}
	 *      - {@link HierarchyStopAt}
	 *      - {@link HierarchyStatistics}
	 * 
	 * The following query lists products in category Audio and its subcategories. Along with the products returned, it also
	 * returns a computed subcategories data structure that lists the flat category list the currently focused category
	 * Audio with a computed count of child categories for each menu item and an aggregated count of all products that would
	 * fall into the given category.
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'audio')
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             'categories',
	 *             children(
	 *                 'subcategories',
	 *                 entityFetch(attributeContent('code')),
	 *                 stopAt(distance(1)),
	 *                 statistics(
	 *                     CHILDREN_COUNT,
	 *                     QUERIED_ENTITY_COUNT
	 *                 )
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * The calculated result for children is connected with the {@link HierarchyWithin} pivot hierarchy node (or
	 * the "virtual" invisible top root referred to by the hierarchyWithinRoot constraint). If the {@link HierarchyWithin}
	 * contains inner constraints {@link HierarchyHaving} or {@link HierarchyExcluding}, the children will respect them as
	 * well. The reason is simple: when you render a menu for the query result, you want the calculated statistics to
	 * respect the rules that apply to the hierarchyWithin so that the calculated number remains consistent for the end
	 * user.
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
	 * The siblings requirement computes the hierarchy tree starting at the same hierarchy node that is targeted by
	 * the filtering part of the same query using the hierarchyWithin. It lists all sibling nodes to the node that is
	 * requested by hierarchyWithin constraint (that's why the siblings has no sense with {@link HierarchyWithinRoot}
	 * constraint - "virtual" top level node cannot have any siblings). Siblings will produce a flat list of siblings unless
	 * the {@link HierarchyStopAt} constraint is used as an inner constraint. The {@link HierarchyStopAt} constraint
	 * triggers a top-down hierarchy traversal from each of the sibling nodes until the {@link HierarchyStopAt} is
	 * satisfied. If you need to access statistical data, use the statistics constraint.
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - mandatory String argument specifying the output name for the calculated data structure
	 * - optional one or more constraints that allow you to define the completeness of the hierarchy entities, the scope
	 *   of the traversed hierarchy tree, and the statistics computed along the way; any or all of the constraints may
	 *   be present:
	 * 
	 *      - {@link EntityFetch}
	 *      - {@link HierarchyStopAt}
	 *      - {@link HierarchyStatistics}
	 * 
	 * The following query lists products in category Audio and its subcategories. Along with the products returned, it also
	 * returns a computed audioSiblings data structure that lists the flat category list the currently focused category
	 * Audio with a computed count of child categories for each menu item and an aggregated count of all products that would
	 * fall into the given category.
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'audio')
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             'categories',
	 *             siblings(
	 *                 'audioSiblings',
	 *                 entityFetch(attributeContent('code')),
	 *                 statistics(
	 *                     CHILDREN_COUNT,
	 *                     QUERIED_ENTITY_COUNT
	 *                 )
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * The calculated result for siblings is connected with the {@link HierarchyWithin} pivot hierarchy node. If
	 * the {@link HierarchyWithin} contains inner constraints {@link HierarchyHaving} or {@link HierarchyExcluding},
	 * the children will respect them as well. The reason is simple: when you render a menu for the query result, you want
	 * the calculated statistics to respect the rules that apply to the hierarchyWithin so that the calculated number
	 * remains consistent for the end user.
	 * 
	 * <strong>Different siblings syntax when used within parents parent constraint</strong>
	 * 
	 * The siblings constraint can be used separately as a child of {@link HierarchyOfSelf} or {@link HierarchyOfReference},
	 * or it can be used as a child constraint of {@link HierarchyParents}. In such a case, the siblings constraint lacks
	 * the first string argument that defines the name for the output data structure. The reason is that this name is
	 * already defined on the enclosing parents constraint, and the siblings constraint simply extends the data available
	 * in its data structure.
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
	 * The siblings requirement computes the hierarchy tree starting at the same hierarchy node that is targeted by
	 * the filtering part of the same query using the hierarchyWithin. It lists all sibling nodes to the node that is
	 * requested by hierarchyWithin constraint (that's why the siblings has no sense with {@link HierarchyWithinRoot}
	 * constraint - "virtual" top level node cannot have any siblings). Siblings will produce a flat list of siblings unless
	 * the {@link HierarchyStopAt} constraint is used as an inner constraint. The {@link HierarchyStopAt} constraint
	 * triggers a top-down hierarchy traversal from each of the sibling nodes until the {@link HierarchyStopAt} is
	 * satisfied. If you need to access statistical data, use the statistics constraint.
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - mandatory String argument specifying the output name for the calculated data structure
	 * - optional one or more constraints that allow you to define the completeness of the hierarchy entities, the scope
	 *   of the traversed hierarchy tree, and the statistics computed along the way; any or all of the constraints may
	 *   be present:
	 * 
	 *      - {@link EntityFetch}
	 *      - {@link HierarchyStopAt}
	 *      - {@link HierarchyStatistics}
	 * 
	 * The following query lists products in category Audio and its subcategories. Along with the products returned, it also
	 * returns a computed audioSiblings data structure that lists the flat category list the currently focused category
	 * Audio with a computed count of child categories for each menu item and an aggregated count of all products that would
	 * fall into the given category.
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'audio')
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             'categories',
	 *             siblings(
	 *                 'audioSiblings',
	 *                 entityFetch(attributeContent('code')),
	 *                 statistics(
	 *                     CHILDREN_COUNT,
	 *                     QUERIED_ENTITY_COUNT
	 *                 )
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * The calculated result for siblings is connected with the {@link HierarchyWithin} pivot hierarchy node. If
	 * the {@link HierarchyWithin} contains inner constraints {@link HierarchyHaving} or {@link HierarchyExcluding},
	 * the children will respect them as well. The reason is simple: when you render a menu for the query result, you want
	 * the calculated statistics to respect the rules that apply to the hierarchyWithin so that the calculated number
	 * remains consistent for the end user.
	 * 
	 * <strong>Different siblings syntax when used within parents parent constraint</strong>
	 * 
	 * The siblings constraint can be used separately as a child of {@link HierarchyOfSelf} or {@link HierarchyOfReference},
	 * or it can be used as a child constraint of {@link HierarchyParents}. In such a case, the siblings constraint lacks
	 * the first string argument that defines the name for the output data structure. The reason is that this name is
	 * already defined on the enclosing parents constraint, and the siblings constraint simply extends the data available
	 * in its data structure.
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
	 * The siblings requirement computes the hierarchy tree starting at the same hierarchy node that is targeted by
	 * the filtering part of the same query using the hierarchyWithin. It lists all sibling nodes to the node that is
	 * requested by hierarchyWithin constraint (that's why the siblings has no sense with {@link HierarchyWithinRoot}
	 * constraint - "virtual" top level node cannot have any siblings). Siblings will produce a flat list of siblings unless
	 * the {@link HierarchyStopAt} constraint is used as an inner constraint. The {@link HierarchyStopAt} constraint
	 * triggers a top-down hierarchy traversal from each of the sibling nodes until the {@link HierarchyStopAt} is
	 * satisfied. If you need to access statistical data, use the statistics constraint.
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - mandatory String argument specifying the output name for the calculated data structure
	 * - optional one or more constraints that allow you to define the completeness of the hierarchy entities, the scope
	 *   of the traversed hierarchy tree, and the statistics computed along the way; any or all of the constraints may
	 *   be present:
	 * 
	 *      - {@link EntityFetch}
	 *      - {@link HierarchyStopAt}
	 *      - {@link HierarchyStatistics}
	 * 
	 * The following query lists products in category Audio and its subcategories. Along with the products returned, it also
	 * returns a computed audioSiblings data structure that lists the flat category list the currently focused category
	 * Audio with a computed count of child categories for each menu item and an aggregated count of all products that would
	 * fall into the given category.
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'audio')
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             'categories',
	 *             siblings(
	 *                 'audioSiblings',
	 *                 entityFetch(attributeContent('code')),
	 *                 statistics(
	 *                     CHILDREN_COUNT,
	 *                     QUERIED_ENTITY_COUNT
	 *                 )
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * The calculated result for siblings is connected with the {@link HierarchyWithin} pivot hierarchy node. If
	 * the {@link HierarchyWithin} contains inner constraints {@link HierarchyHaving} or {@link HierarchyExcluding},
	 * the children will respect them as well. The reason is simple: when you render a menu for the query result, you want
	 * the calculated statistics to respect the rules that apply to the hierarchyWithin so that the calculated number
	 * remains consistent for the end user.
	 * 
	 * <strong>Different siblings syntax when used within parents parent constraint</strong>
	 * 
	 * The siblings constraint can be used separately as a child of {@link HierarchyOfSelf} or {@link HierarchyOfReference},
	 * or it can be used as a child constraint of {@link HierarchyParents}. In such a case, the siblings constraint lacks
	 * the first string argument that defines the name for the output data structure. The reason is that this name is
	 * already defined on the enclosing parents constraint, and the siblings constraint simply extends the data available
	 * in its data structure.
	*/
	@Nullable
	static HierarchySiblings siblings(
		@Nullable EntityFetch entityFetch,
		@Nullable HierarchyOutputRequireConstraint... requirements
	) {
		return new HierarchySiblings(null, entityFetch, requirements);
	}

	/**
	 * The siblings requirement computes the hierarchy tree starting at the same hierarchy node that is targeted by
	 * the filtering part of the same query using the hierarchyWithin. It lists all sibling nodes to the node that is
	 * requested by hierarchyWithin constraint (that's why the siblings has no sense with {@link HierarchyWithinRoot}
	 * constraint - "virtual" top level node cannot have any siblings). Siblings will produce a flat list of siblings unless
	 * the {@link HierarchyStopAt} constraint is used as an inner constraint. The {@link HierarchyStopAt} constraint
	 * triggers a top-down hierarchy traversal from each of the sibling nodes until the {@link HierarchyStopAt} is
	 * satisfied. If you need to access statistical data, use the statistics constraint.
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - mandatory String argument specifying the output name for the calculated data structure
	 * - optional one or more constraints that allow you to define the completeness of the hierarchy entities, the scope
	 *   of the traversed hierarchy tree, and the statistics computed along the way; any or all of the constraints may
	 *   be present:
	 * 
	 *      - {@link EntityFetch}
	 *      - {@link HierarchyStopAt}
	 *      - {@link HierarchyStatistics}
	 * 
	 * The following query lists products in category Audio and its subcategories. Along with the products returned, it also
	 * returns a computed audioSiblings data structure that lists the flat category list the currently focused category
	 * Audio with a computed count of child categories for each menu item and an aggregated count of all products that would
	 * fall into the given category.
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'audio')
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             'categories',
	 *             siblings(
	 *                 'audioSiblings',
	 *                 entityFetch(attributeContent('code')),
	 *                 statistics(
	 *                     CHILDREN_COUNT,
	 *                     QUERIED_ENTITY_COUNT
	 *                 )
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * The calculated result for siblings is connected with the {@link HierarchyWithin} pivot hierarchy node. If
	 * the {@link HierarchyWithin} contains inner constraints {@link HierarchyHaving} or {@link HierarchyExcluding},
	 * the children will respect them as well. The reason is simple: when you render a menu for the query result, you want
	 * the calculated statistics to respect the rules that apply to the hierarchyWithin so that the calculated number
	 * remains consistent for the end user.
	 * 
	 * <strong>Different siblings syntax when used within parents parent constraint</strong>
	 * 
	 * The siblings constraint can be used separately as a child of {@link HierarchyOfSelf} or {@link HierarchyOfReference},
	 * or it can be used as a child constraint of {@link HierarchyParents}. In such a case, the siblings constraint lacks
	 * the first string argument that defines the name for the output data structure. The reason is that this name is
	 * already defined on the enclosing parents constraint, and the siblings constraint simply extends the data available
	 * in its data structure.
	*/
	@Nullable
	static HierarchySiblings siblings(@Nullable HierarchyOutputRequireConstraint... requirements) {
		return new HierarchySiblings(null, requirements);
	}

	/**
	 * The parents requirement computes the hierarchy tree starting at the same hierarchy node that is targeted by
	 * the filtering part of the same query using the hierarchyWithin constraint towards the root of the hierarchy.
	 * The scope of the calculated information can be controlled by the stopAt constraint. By default, the traversal goes
	 * all the way to the top of the hierarchy tree unless you tell it to stop at anywhere. If you need to access
	 * statistical data, use the statistics constraint.
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - mandatory String argument specifying the output name for the calculated data structure
	 * - optional one or more constraints that allow you to define the completeness of the hierarchy entities, the scope
	 *   of the traversed hierarchy tree, and the statistics computed along the way; any or all of the constraints may be
	 *   present:
	 * 
	 *      - {@link HierarchySiblings}
	 *      - {@link EntityFetch}
	 *      - {@link HierarchyStopAt}
	 *      - {@link HierarchyStatistics}
	 * 
	 * The following query lists products in the category Audio and its subcategories. Along with the products returned,
	 * it also returns a computed parentAxis data structure that lists all the parent nodes of the currently focused
	 * category True wireless with a computed count of child categories for each menu item and an aggregated count of all
	 * products that would fall into the given category.
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'true-wireless')
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             'categories',
	 *             parents(
	 *                 'parentAxis',
	 *                 entityFetch(attributeContent('code')),
	 *                 statistics(
	 *                     CHILDREN_COUNT,
	 *                     QUERIED_ENTITY_COUNT
	 *                 )
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * The calculated result for parents is connected with the {@link HierarchyWithin} pivot hierarchy node.
	 * If the {@link HierarchyWithin} contains inner constraints {@link HierarchyHaving} or {@link HierarchyExcluding},
	 * the parents will respect them as well during child nodes / queried entities statistics calculation. The reason is
	 * simple: when you render a menu for the query result, you want the calculated statistics to respect the rules that
	 * apply to the {@link HierarchyWithin} so that the calculated number remains consistent for the end user.
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
	 * The parents requirement computes the hierarchy tree starting at the same hierarchy node that is targeted by
	 * the filtering part of the same query using the hierarchyWithin constraint towards the root of the hierarchy.
	 * The scope of the calculated information can be controlled by the stopAt constraint. By default, the traversal goes
	 * all the way to the top of the hierarchy tree unless you tell it to stop at anywhere. If you need to access
	 * statistical data, use the statistics constraint.
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - mandatory String argument specifying the output name for the calculated data structure
	 * - optional one or more constraints that allow you to define the completeness of the hierarchy entities, the scope
	 *   of the traversed hierarchy tree, and the statistics computed along the way; any or all of the constraints may be
	 *   present:
	 * 
	 *      - {@link HierarchySiblings}
	 *      - {@link EntityFetch}
	 *      - {@link HierarchyStopAt}
	 *      - {@link HierarchyStatistics}
	 * 
	 * The following query lists products in the category Audio and its subcategories. Along with the products returned,
	 * it also returns a computed parentAxis data structure that lists all the parent nodes of the currently focused
	 * category True wireless with a computed count of child categories for each menu item and an aggregated count of all
	 * products that would fall into the given category.
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'true-wireless')
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             'categories',
	 *             parents(
	 *                 'parentAxis',
	 *                 entityFetch(attributeContent('code')),
	 *                 statistics(
	 *                     CHILDREN_COUNT,
	 *                     QUERIED_ENTITY_COUNT
	 *                 )
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * The calculated result for parents is connected with the {@link HierarchyWithin} pivot hierarchy node.
	 * If the {@link HierarchyWithin} contains inner constraints {@link HierarchyHaving} or {@link HierarchyExcluding},
	 * the parents will respect them as well during child nodes / queried entities statistics calculation. The reason is
	 * simple: when you render a menu for the query result, you want the calculated statistics to respect the rules that
	 * apply to the {@link HierarchyWithin} so that the calculated number remains consistent for the end user.
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
	 * The parents requirement computes the hierarchy tree starting at the same hierarchy node that is targeted by
	 * the filtering part of the same query using the hierarchyWithin constraint towards the root of the hierarchy.
	 * The scope of the calculated information can be controlled by the stopAt constraint. By default, the traversal goes
	 * all the way to the top of the hierarchy tree unless you tell it to stop at anywhere. If you need to access
	 * statistical data, use the statistics constraint.
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - mandatory String argument specifying the output name for the calculated data structure
	 * - optional one or more constraints that allow you to define the completeness of the hierarchy entities, the scope
	 *   of the traversed hierarchy tree, and the statistics computed along the way; any or all of the constraints may be
	 *   present:
	 * 
	 *      - {@link HierarchySiblings}
	 *      - {@link EntityFetch}
	 *      - {@link HierarchyStopAt}
	 *      - {@link HierarchyStatistics}
	 * 
	 * The following query lists products in the category Audio and its subcategories. Along with the products returned,
	 * it also returns a computed parentAxis data structure that lists all the parent nodes of the currently focused
	 * category True wireless with a computed count of child categories for each menu item and an aggregated count of all
	 * products that would fall into the given category.
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'true-wireless')
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             'categories',
	 *             parents(
	 *                 'parentAxis',
	 *                 entityFetch(attributeContent('code')),
	 *                 statistics(
	 *                     CHILDREN_COUNT,
	 *                     QUERIED_ENTITY_COUNT
	 *                 )
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * The calculated result for parents is connected with the {@link HierarchyWithin} pivot hierarchy node.
	 * If the {@link HierarchyWithin} contains inner constraints {@link HierarchyHaving} or {@link HierarchyExcluding},
	 * the parents will respect them as well during child nodes / queried entities statistics calculation. The reason is
	 * simple: when you render a menu for the query result, you want the calculated statistics to respect the rules that
	 * apply to the {@link HierarchyWithin} so that the calculated number remains consistent for the end user.
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
	 * The parents requirement computes the hierarchy tree starting at the same hierarchy node that is targeted by
	 * the filtering part of the same query using the hierarchyWithin constraint towards the root of the hierarchy.
	 * The scope of the calculated information can be controlled by the stopAt constraint. By default, the traversal goes
	 * all the way to the top of the hierarchy tree unless you tell it to stop at anywhere. If you need to access
	 * statistical data, use the statistics constraint.
	 * 
	 * The constraint accepts following arguments:
	 * 
	 * - mandatory String argument specifying the output name for the calculated data structure
	 * - optional one or more constraints that allow you to define the completeness of the hierarchy entities, the scope
	 *   of the traversed hierarchy tree, and the statistics computed along the way; any or all of the constraints may be
	 *   present:
	 * 
	 *      - {@link HierarchySiblings}
	 *      - {@link EntityFetch}
	 *      - {@link HierarchyStopAt}
	 *      - {@link HierarchyStatistics}
	 * 
	 * The following query lists products in the category Audio and its subcategories. Along with the products returned,
	 * it also returns a computed parentAxis data structure that lists all the parent nodes of the currently focused
	 * category True wireless with a computed count of child categories for each menu item and an aggregated count of all
	 * products that would fall into the given category.
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'true-wireless')
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             'categories',
	 *             parents(
	 *                 'parentAxis',
	 *                 entityFetch(attributeContent('code')),
	 *                 statistics(
	 *                     CHILDREN_COUNT,
	 *                     QUERIED_ENTITY_COUNT
	 *                 )
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * The calculated result for parents is connected with the {@link HierarchyWithin} pivot hierarchy node.
	 * If the {@link HierarchyWithin} contains inner constraints {@link HierarchyHaving} or {@link HierarchyExcluding},
	 * the parents will respect them as well during child nodes / queried entities statistics calculation. The reason is
	 * simple: when you render a menu for the query result, you want the calculated statistics to respect the rules that
	 * apply to the {@link HierarchyWithin} so that the calculated number remains consistent for the end user.
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
	 * The stopAt container constraint is a service wrapping constraint container that only makes sense in combination with
	 * one of the allowed nested constraints. See the usage examples for specific nested constraints.
	 * 
	 * It accepts one of the following inner constraints:
	 * 
	 * - {@link HierarchyDistance}
	 * - {@link HierarchyLevel}
	 * - {@link HierarchyNode}
	 * 
	 * which define the constraint that stops traversing the hierarchy tree when it's satisfied by a currently traversed
	 * node.
	*/
	@Nullable
	static HierarchyStopAt stopAt(@Nullable HierarchyStopAtRequireConstraint stopConstraint) {
		return stopConstraint == null ? null : new HierarchyStopAt(stopConstraint);
	}

	/**
	 * The node filtering container is an alternative to the {@link HierarchyDistance} and {@link HierarchyLevel}
	 * termination constraints, which is much more dynamic and can produce hierarchy trees of non-uniform depth. Because
	 * the filtering constraint can be satisfied by nodes of widely varying depths, traversal can be highly dynamic.
	 * 
	 * Constraint children define a criterion that determines the point in a hierarchical structure where the traversal
	 * should stop. The traversal stops at the first node that satisfies the filter condition specified in this container.
	 * 
	 * The situations where you'd need this dynamic behavior are few and far between. Unfortunately, we do not have
	 * a meaningful example of this in the demo dataset, so our example query will be slightly off. But for the sake of
	 * demonstration, let's list the entire Accessories hierarchy, but stop traversing at the nodes whose code starts with
	 * the letter `w`.
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'accessories')
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             'categories',
	 *             children(
	 *                 'subMenu',
	 *                 entityFetch(attributeContent('code')),
	 *                 stopAt(
	 *                     node(
	 *                         filterBy(
	 *                             attributeStartsWith('code', 'w')
	 *                         )
	 *                     )
	 *                 )
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	*/
	@Nullable
	static HierarchyNode node(@Nullable FilterBy filterBy) {
		return filterBy == null ? null : new HierarchyNode(filterBy);
	}

	/**
	 * The level constraint can only be used within the stopAt container and limits the hierarchy traversal to stop when
	 * the actual level of the traversed node is equal to a specified constant. The "virtual" top invisible node has level
	 * zero, the top nodes (nodes with NULL parent) have level one, their children have level two, and so on.
	 * 
	 * See the following figure:
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'audio')
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             'categories',
	 *             fromRoot(
	 *                 'megaMenu',
	 *                 entityFetch(attributeContent('code')),
	 *                 stopAt(level(2))
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * The query lists products in Audio category and its subcategories. Along with the products returned, it
	 * also returns a computed megaMenu data structure that lists top two levels of the entire hierarchy.
	*/
	@Nullable
	static HierarchyLevel level(@Nullable Integer level) {
		return level == null ? null : new HierarchyLevel(level);
	}

	/**
	 * The distance constraint can only be used within the {@link HierarchyStopAt} container and limits the hierarchy
	 * traversal to stop when the number of levels traversed reaches the specified constant. The distance is always relative
	 * to the pivot node (the node where the hierarchy traversal starts) and is the same whether we are traversing
	 * the hierarchy top-down or bottom-up. The distance between any two nodes in the hierarchy can be calculated as
	 * `abs(level(nodeA) - level(nodeB))`.
	 * 
	 * The constraint accepts single integer argument `distance`, which defines a maximum relative distance from the pivot
	 * node that can be traversed; the pivot node itself is at distance zero, its direct child or direct parent is
	 * at distance one, each additional step adds a one to the distance.
	 * 
	 * See the following figure when the pivot node is Audio:
	 * 
	 * <pre>
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             'categories',
	 *             attributeEquals('code', 'audio')
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             'categories',
	 *             children(
	 *                 'subcategories',
	 *                 entityFetch(attributeContent('code')),
	 *                 stopAt(distance(1))
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * The following query lists products in category Audio and its subcategories. Along with the products returned, it
	 * also returns a computed subcategories data structure that lists the flat category list the currently focused category
	 * Audio.
	*/
	@Nullable
	static HierarchyDistance distance(@Nullable Integer distance) {
		return distance == null ? null : new HierarchyDistance(distance);
	}

	/**
	 * The statistics constraint allows you to retrieve statistics about the hierarchy nodes that are returned by the
	 * current query. When used it triggers computation of the queriedEntityCount, childrenCount statistics, or both for
	 * each hierarchy node in the returned hierarchy tree.
	 * 
	 * It requires mandatory argument of type {@link StatisticsType} enum that specifies which statistics to compute:
	 * 
	 * - {@link StatisticsType#CHILDREN_COUNT}: triggers calculation of the count of child hierarchy nodes that exist in
	 *   the hierarchy tree below the given node; the count is correct regardless of whether the children themselves are
	 *   requested/traversed by the constraint definition, and respects hierarchyOfReference settings for automatic removal
	 *   of hierarchy nodes that would contain empty result set of queried entities ({@link EmptyHierarchicalEntityBehaviour#REMOVE_EMPTY})
	 * - {@link StatisticsType#QUERIED_ENTITY_COUNT}: triggers the calculation of the total number of queried entities that
	 *   will be returned if the current query is focused on this particular hierarchy node using the hierarchyWithin filter
	 *   constraint (the possible refining constraint in the form of directRelation and excluding-root is not taken into
	 *   account).
	 * 
	 * And optional argument of type {@link StatisticsBase} enum allowing you to specify the base queried entity set that
	 * is the source for statistics calculations:
	 * 
	 * - {@link StatisticsBase#COMPLETE_FILTER}: complete filtering query constraint
	 * - {@link StatisticsBase#WITHOUT_USER_FILTER}: filtering query constraint where the contents of optional userFilter
	 *    are ignored
	 * 
	 * The calculation always ignores hierarchyWithin because the focused part of the hierarchy tree is defined on
	 * the requirement constraint level, but including having/excluding constraints. The having/excluding constraints are
	 * crucial for the calculation of queriedEntityCount (and therefore also affects the value of childrenCount
	 * transitively).
	 * 
	 * <strong>Computational complexity of statistical data calculation</strong>
	 * 
	 * The performance price paid for calculating statistics is not negligible. The calculation of {@link StatisticsType#CHILDREN_COUNT}
	 * is cheaper because it allows to eliminate "dead branches" early and thus conserve the computation cycles.
	 * The calculation of the {@link StatisticsType#QUERIED_ENTITY_COUNT} is more expensive because it requires counting
	 * items up to the last one and must be precise.
	 * 
	 * We strongly recommend that you avoid using {@link StatisticsType#QUERIED_ENTITY_COUNT} for root hierarchy nodes for
	 * large datasets.
	 * 
	 * This query actually has to filter and aggregate all the records in the database, which is obviously quite expensive,
	 * even considering that all the indexes are in-memory. Caching is probably the only way out if you really need
	 * to crunch these numbers.
	*/
	@Nullable
	static HierarchyStatistics statistics(@Nullable StatisticsType... type) {
		return type == null ?
			new HierarchyStatistics(StatisticsBase.WITHOUT_USER_FILTER) :
			new HierarchyStatistics(StatisticsBase.WITHOUT_USER_FILTER, type);
	}

	/**
	 * The statistics constraint allows you to retrieve statistics about the hierarchy nodes that are returned by the
	 * current query. When used it triggers computation of the queriedEntityCount, childrenCount statistics, or both for
	 * each hierarchy node in the returned hierarchy tree.
	 * 
	 * It requires mandatory argument of type {@link StatisticsType} enum that specifies which statistics to compute:
	 * 
	 * - {@link StatisticsType#CHILDREN_COUNT}: triggers calculation of the count of child hierarchy nodes that exist in
	 *   the hierarchy tree below the given node; the count is correct regardless of whether the children themselves are
	 *   requested/traversed by the constraint definition, and respects hierarchyOfReference settings for automatic removal
	 *   of hierarchy nodes that would contain empty result set of queried entities ({@link EmptyHierarchicalEntityBehaviour#REMOVE_EMPTY})
	 * - {@link StatisticsType#QUERIED_ENTITY_COUNT}: triggers the calculation of the total number of queried entities that
	 *   will be returned if the current query is focused on this particular hierarchy node using the hierarchyWithin filter
	 *   constraint (the possible refining constraint in the form of directRelation and excluding-root is not taken into
	 *   account).
	 * 
	 * And optional argument of type {@link StatisticsBase} enum allowing you to specify the base queried entity set that
	 * is the source for statistics calculations:
	 * 
	 * - {@link StatisticsBase#COMPLETE_FILTER}: complete filtering query constraint
	 * - {@link StatisticsBase#WITHOUT_USER_FILTER}: filtering query constraint where the contents of optional userFilter
	 *    are ignored
	 * 
	 * The calculation always ignores hierarchyWithin because the focused part of the hierarchy tree is defined on
	 * the requirement constraint level, but including having/excluding constraints. The having/excluding constraints are
	 * crucial for the calculation of queriedEntityCount (and therefore also affects the value of childrenCount
	 * transitively).
	 * 
	 * <strong>Computational complexity of statistical data calculation</strong>
	 * 
	 * The performance price paid for calculating statistics is not negligible. The calculation of {@link StatisticsType#CHILDREN_COUNT}
	 * is cheaper because it allows to eliminate "dead branches" early and thus conserve the computation cycles.
	 * The calculation of the {@link StatisticsType#QUERIED_ENTITY_COUNT} is more expensive because it requires counting
	 * items up to the last one and must be precise.
	 * 
	 * We strongly recommend that you avoid using {@link StatisticsType#QUERIED_ENTITY_COUNT} for root hierarchy nodes for
	 * large datasets.
	 * 
	 * This query actually has to filter and aggregate all the records in the database, which is obviously quite expensive,
	 * even considering that all the indexes are in-memory. Caching is probably the only way out if you really need
	 * to crunch these numbers.
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
	 * dataInLocalesAll()
	 * ```
	*/
	@Nonnull
	static DataInLocales dataInLocalesAll() {
		return new DataInLocales();
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
	 * dataInLocalesAll()
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
	static ReferenceContent referenceContentAllWithAttributes() {
		return new ReferenceContent((AttributeContent) null);
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
	static ReferenceContent referenceContentAllWithAttributes(@Nullable AttributeContent attributeContent) {
		return new ReferenceContent(attributeContent);
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
	static ReferenceContent referenceContent(@Nullable String referenceName) {
		if (referenceName == null) {
			return new ReferenceContent();
		}
		return new ReferenceContent(referenceName);
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable String... attributeNames) {
		return new ReferenceContent(
			referenceName, null, null,
			attributeContent(attributeNames), null, null
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName) {
		return new ReferenceContent(referenceName, null, null, null, null, null);
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable AttributeContent attributeContent) {
		return new ReferenceContent(
			referenceName, null, null,
			attributeContent, null, null
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
	static ReferenceContent referenceContent(@Nullable String... referenceName) {
		if (referenceName == null) {
			return new ReferenceContent();
		}
		return new ReferenceContent(referenceName);
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
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable EntityFetch entityRequirement) {
		if (referenceName == null && entityRequirement == null) {
			return new ReferenceContent();
		}
		if (referenceName == null) {
			return new ReferenceContent(entityRequirement, null);
		}
		return new ReferenceContent(
			referenceName,  null, null, entityRequirement, null
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(
			referenceName,  null, null,
			null, entityRequirement, null
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(
			referenceName,  null, null,
			attributeContent, entityRequirement, null
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
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable EntityGroupFetch groupEntityRequirement) {
		if (referenceName == null && groupEntityRequirement == null) {
			return new ReferenceContent();
		}
		if (referenceName == null) {
			return new ReferenceContent(null, groupEntityRequirement);
		}
		return new ReferenceContent(referenceName, null, null, null, groupEntityRequirement);
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(
			referenceName, null, null,
			null, null, groupEntityRequirement
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable AttributeContent attributeContent, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(
			referenceName, null, null,
			attributeContent, null, groupEntityRequirement
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
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		if (referenceName == null) {
			return new ReferenceContent(entityRequirement, groupEntityRequirement);
		}
		return new ReferenceContent(referenceName, null, null, entityRequirement, groupEntityRequirement);
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
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement
	) {
		return new ReferenceContent(
			referenceName, null, null,
			entityRequirement, groupEntityRequirement
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
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName, @Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement
	) {
		return new ReferenceContent(
			referenceName, null, null,
			attributeContent, entityRequirement, groupEntityRequirement
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
		return new ReferenceContent(referenceName, filterBy, null, null, null);
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable FilterBy filterBy) {
		return new ReferenceContent(referenceName, filterBy, null, null, null, null);
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable AttributeContent attributeContent) {
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
		return new ReferenceContent(referenceName, filterBy, null, entityRequirement, null);
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(
			referenceName, filterBy, null,
			null, entityRequirement, null
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(
			referenceName, filterBy, null,
			attributeContent, entityRequirement, null
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
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(referenceName, filterBy, null, null, groupEntityRequirement);
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(
			referenceName, filterBy, null,
			null, null, groupEntityRequirement
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable AttributeContent attributeContent, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(
			referenceName, filterBy, null,
			attributeContent, null, groupEntityRequirement
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
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(referenceName, filterBy, null, entityRequirement, groupEntityRequirement);
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(
			referenceName, filterBy, null,
			null, entityRequirement, groupEntityRequirement
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(
			referenceName, filterBy, null,
			attributeContent, entityRequirement, groupEntityRequirement
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
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable OrderBy orderBy) {
		return new ReferenceContent(referenceName, null, orderBy, null, null);
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable OrderBy orderBy) {
		return new ReferenceContent(
			referenceName, null, orderBy,
			null, null, null
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable OrderBy orderBy, @Nullable AttributeContent attributeContent) {
		return new ReferenceContent(
			referenceName, null, orderBy,
			attributeContent, null, null
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
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(referenceName, null, orderBy, entityRequirement, null);
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(
			referenceName, null, orderBy,
			null, entityRequirement, null
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable OrderBy orderBy, @Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(
			referenceName, null, orderBy,
			attributeContent, entityRequirement, null
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
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable OrderBy orderBy, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(referenceName, null, orderBy, null, groupEntityRequirement);
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable OrderBy orderBy, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(
			referenceName, null, orderBy,
			null, null, groupEntityRequirement
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable OrderBy orderBy, @Nullable AttributeContent attributeContent, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(
			referenceName, null, orderBy,
			attributeContent, null, groupEntityRequirement
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
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(referenceName, null, orderBy, entityRequirement, groupEntityRequirement);
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(
			referenceName, null, orderBy,
			null, entityRequirement, groupEntityRequirement
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable OrderBy orderBy, @Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(
			referenceName, null, orderBy,
			attributeContent, entityRequirement, groupEntityRequirement
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
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy) {
		return new ReferenceContent(referenceName, filterBy, orderBy, null, null);
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy) {
		return new ReferenceContent(
			referenceName, filterBy, orderBy,
			null, null, null
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable AttributeContent attributeContent) {
		return new ReferenceContent(
			referenceName, filterBy, orderBy,
			attributeContent, null, null
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
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(referenceName, filterBy, orderBy, entityRequirement, null);
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(
			referenceName, filterBy, orderBy,
			null, entityRequirement, null
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(
			referenceName, filterBy, orderBy,
			attributeContent, entityRequirement, null
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
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(referenceName, filterBy, orderBy, null, groupEntityRequirement);
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(
			referenceName, filterBy, orderBy,
			null, null, groupEntityRequirement
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable AttributeContent attributeContent, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(
			referenceName, filterBy, orderBy,
			attributeContent, null, groupEntityRequirement
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
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(referenceName, filterBy, orderBy, entityRequirement, groupEntityRequirement);
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(
			referenceName, filterBy, orderBy,
			null, entityRequirement, groupEntityRequirement
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
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(
			referenceName, filterBy, orderBy,
			attributeContent, entityRequirement, groupEntityRequirement
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
	static ReferenceContent referenceContentAll(@Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(entityRequirement, null);
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
	static ReferenceContent referenceContentAllWithAttributes(@Nullable EntityFetch entityRequirement) {
		return new ReferenceContent((AttributeContent) null, entityRequirement, null);
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
	static ReferenceContent referenceContentAllWithAttributes(@Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(attributeContent, entityRequirement, null);
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
		return new ReferenceContent(null, groupEntityRequirement);
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
	static ReferenceContent referenceContentAllWithAttributes(@Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent((AttributeContent) null, null, groupEntityRequirement);
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
	static ReferenceContent referenceContentAllWithAttributes(@Nullable AttributeContent attributeContent, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(attributeContent, null, groupEntityRequirement);
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
	static ReferenceContent referenceContentAllWithAttributes(@Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent((AttributeContent) null, entityRequirement, groupEntityRequirement);
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
	static ReferenceContent referenceContentAllWithAttributes(@Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(attributeContent, entityRequirement, groupEntityRequirement);
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
			attributeContentAll(), hierarchyContent(),
			associatedDataContentAll(), priceContentAll(),
			referenceContentAllWithAttributes(), dataInLocalesAll()
		);
	}

	/**
	 * TOBEDONE JNO docs
	*/
	@Nonnull
	static EntityGroupFetch entityGroupFetchAll() {
		return entityGroupFetch(
			attributeContentAll(), hierarchyContent(),
			associatedDataContentAll(), priceContentAll(),
			referenceContentAllWithAttributes(), dataInLocalesAll()
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
			hierarchyContent(), attributeContentAll(), associatedDataContentAll(), priceContentAll(), referenceContentAllWithAttributes(), dataInLocalesAll()
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