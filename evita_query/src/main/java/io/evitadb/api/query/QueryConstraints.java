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
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.Range;
import io.evitadb.dataType.StripList;
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

	/*+
		HEADING
	 */

	/**
	 * Each query must specify collection. This mandatory {@link String} entity type controls what collection
	 * the query will be applied on.
	 * 
	 * Sample of the header is:
	 * 
	 * <pre>
	 * collection('category')
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/basics#header">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static Collection collection(@Nonnull String entityType) {
		return new Collection(entityType);
	}

	/*
		FILTERING
	 */

	/**
	 * Filtering constraints allow you to select only a few entities from many that exist in the target collection. It's
	 * similar to the "where" clause in SQL. FilterBy container might contain one or more sub-constraints, that are combined
	 * by logical disjunction (AND).
	 * 
	 * Example:
	 * 
	 * <pre>
	 * filterBy(
	 *    isNotNull("code"),
	 *    or(
	 *       equals("code", "ABCD"),
	 *       startsWith("title", "Knife")
	 *    )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/basics#filter-by">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static FilterBy filterBy(@Nullable FilterConstraint... constraint) {
		return constraint == null ? null : new FilterBy(constraint);
	}

	/**
	 * Filtering constraints allow you to select only a few entities from many that exist in the target collection. It's
	 * similar to the "where" clause in SQL. FilterGroupBy container might contain one or more sub-constraints, that are
	 * combined by logical disjunction (AND).
	 * 
	 * The `filterGroupBy` is equivalent to {@link FilterBy}, but can be used only within {@link FacetSummary} container
	 * and defines the filter constraints limiting the facet groups returned in facet summary.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * filterGroupBy(
	 *    isNotNull("code"),
	 *    or(
	 *       equals("code", "ABCD"),
	 *       startsWith("title", "Knife")
	 *    )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/basics#filter-by">Visit detailed user documentation</a></p>
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
	 *     collection("Product"),
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/logical#and">Visit detailed user documentation</a></p>
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
	 *     collection("Product"),
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/logical#or">Visit detailed user documentation</a></p>
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
	 *     collection("Product"),
	 *     filterBy(
	 *         not(
	 *             entityPrimaryKeyInSet(110066, 106742, 110513)
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ... returns thousands of results excluding the entities with primary keys mentioned in `entityPrimaryKeyInSet`
	 * constraint. Because this situation is hard to visualize - let"s narrow our super set to only a few entities:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/logical#not">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static Not not(@Nullable FilterConstraint constraint) {
		return constraint == null ? null : new Not(constraint);
	}

	/**
	 * The `referenceHaving` constraint eliminates entities which has no reference of particular name satisfying set of
	 * filtering constraints. You can examine either the attributes specified on the relation itself or wrap the filtering
	 * constraint in {@link EntityHaving} constraint to examine the attributes of the referenced entity.
	 * The constraint is similar to SQL <a href="https://www.w3schools.com/sql/sql_exists.asp">`EXISTS`</a> operator.
	 * 
	 * Example (select entities having reference brand with category attribute equal to alternativeProduct):
	 * 
	 * <pre>
	 * referenceHavingAttribute(
	 *     "brand",
	 *     attributeEquals("category", "alternativeProduct")
	 * )
	 * </pre>
	 * 
	 * Example (select entities having any reference brand):
	 * 
	 * <pre>
	 * referenceHavingAttribute("brand")
	 * </pre>
	 * 
	 * Example (select entities having any reference brand of primary key 1):
	 * 
	 * <pre>
	 * referenceHavingAttribute(
	 *     "brand",
	 *     entityPrimaryKeyInSet(1)
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/references#reference-having">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceHaving referenceHaving(@Nullable String referenceName, @Nullable FilterConstraint... constraint) {
		return referenceName == null ? null : new ReferenceHaving(referenceName, constraint);
	}

	/**
	 * The `userFilter` works identically to the and constraint, but it distinguishes the filter scope, which is controlled
	 * by the user through some kind of user interface, from the rest of the query, which contains the mandatory constraints
	 * on the result set. The user-defined scope can be modified during certain calculations (such as the facet or histogram
	 * calculation), while the mandatory part outside of `userFilter` cannot.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * userFilter(
	 *   facetHaving(
	 *     "brand",
	 *     entityHaving(
	 *       attributeInSet("code", "amazon")
	 *     )
	 *   )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/behavioral#user-filter">Visit detailed user documentation</a></p>
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
	 * between("age", 20, 25)
	 * </pre>
	 * 
	 * Function supports attribute arrays and when attribute is of array type `between` returns true if *any of attribute* values
	 * is between the passed interval the value in the query. If we have the attribute `amount` with value `[1, 9]` all
	 * these constraints will match:
	 * 
	 * <pre>
	 * between("amount", 0, 50)
	 * between("amount", 0, 5)
	 * between("amount", 8, 10)
	 * </pre>
	 * 
	 * If attribute is of `Range` type `between` query behaves like overlap - it returns true if examined range and
	 * any of the attribute ranges (see previous paragraph about array types) share anything in common. All the following
	 * constraints return true when we have the attribute `validity` with following `NumberRange` values: `[[2,5],[8,10]]`:
	 * 
	 * <pre>
	 * between("validity", 0, 3)
	 * between("validity", 0, 100)
	 * between("validity", 9, 10)
	 * </pre>
	 * 
	 * ... but these constraints will return false:
	 * 
	 * <pre>
	 * between("validity", 11, 15)
	 * between("validity", 0, 1)
	 * between("validity", 6, 7)
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/comparable#attribute-between">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static <T extends Serializable & Comparable<?>> AttributeBetween attributeBetween(@Nullable String attributeName, @Nullable T from, @Nullable T to) {
		if (attributeName == null || (from == null && to == null)) {
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
	 * contains("code", "evitaDB")
	 * </pre>
	 * 
	 * Function supports attribute arrays and when attribute is of array type `contains` returns true if any of attribute
	 * values contains the value in the query. If we have the attribute `code` with value `["cat","mouse","dog"]` all these
	 * constraints will match:
	 * 
	 * <pre>
	 * contains("code","mou")
	 * contains("code","o")
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/string#attribute-contains">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static AttributeContains attributeContains(@Nullable String attributeName, @Nullable String textToSearch) {
		return attributeName == null || textToSearch == null ? null : new AttributeContains(attributeName, textToSearch);
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
	 * startsWith("code", "vid")
	 * </pre>
	 * 
	 * Function supports attribute arrays and when attribute is of array type `startsWith` returns true if any of attribute
	 * values starts with the value in the query. If we have the attribute `code` with value `["cat","mouse","dog"]` all
	 * these constraints will match:
	 * 
	 * <pre>
	 * contains("code","mou")
	 * contains("code","do")
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/string#attribute-starts-with">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static AttributeStartsWith attributeStartsWith(@Nullable String attributeName, @Nullable String textToSearch) {
		return attributeName == null || textToSearch == null ? null : new AttributeStartsWith(attributeName, textToSearch);
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
	 * endsWith("code", "ida")
	 * </pre>
	 * 
	 * Function supports attribute arrays and when attribute is of array type `endsWith` returns true if any of attribute
	 * values ends with the value in the query. If we have the attribute `code` with value `["cat","mouse","dog"]` all these
	 * constraints will match:
	 * 
	 * <pre>
	 * contains("code","at")
	 * contains("code","og")
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/string#attribute-ends-with">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static AttributeEndsWith attributeEndsWith(@Nullable String attributeName, @Nullable String textToSearch) {
		return attributeName == null || textToSearch == null ? null : new AttributeEndsWith(attributeName, textToSearch);
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
	 * equals("code", "abc")
	 * </pre>
	 * 
	 * Function supports attribute arrays and when attribute is of array type `equals` returns true if any of attribute values
	 * equals the value in the query. If we have the attribute `code` with value `["A","B","C"]` all these constraints will
	 * match:
	 * 
	 * <pre>
	 * equals("code","A")
	 * equals("code","B")
	 * equals("code","C")
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/comparable#attribute-equals">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static <T extends Serializable> AttributeEquals attributeEquals(@Nullable String attributeName, @Nullable T attributeValue) {
		return attributeName == null || attributeValue == null ? null : new AttributeEquals(attributeName, attributeValue);
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
	 * lessThan("age", 20)
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/comparable#attribute-less-than">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static <T extends Serializable & Comparable<?>> AttributeLessThan attributeLessThan(@Nullable String attributeName, @Nullable T attributeValue) {
		return attributeName == null || attributeValue == null ? null : new AttributeLessThan(attributeName, attributeValue);
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
	 * lessThanEquals("age", 20)
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/comparable#attribute-less-than-equals">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static <T extends Serializable & Comparable<?>> AttributeLessThanEquals attributeLessThanEquals(@Nullable String attributeName, @Nullable T attributeValue) {
		return attributeName == null || attributeValue == null ? null : new AttributeLessThanEquals(attributeName, attributeValue);
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
	 * greaterThan("age", 20)
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/comparable#attribute-greater-than">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static <T extends Serializable & Comparable<?>> AttributeGreaterThan attributeGreaterThan(@Nullable String attributeName, @Nullable T attributeValue) {
		return attributeName == null || attributeValue == null ? null : new AttributeGreaterThan(attributeName, attributeValue);
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
	 * greaterThanEquals("age", 20)
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/comparable#attribute-greater-than-equals">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static <T extends Serializable & Comparable<?>> AttributeGreaterThanEquals attributeGreaterThanEquals(@Nullable String attributeName, @Nullable T attributeValue) {
		return attributeName == null || attributeValue == null ? null : new AttributeGreaterThanEquals(attributeName, attributeValue);
	}

	/**
	 * The `priceInPriceLists` constraint defines the allowed set(s) of price lists that the entity must have to be included
	 * in the result set. The order of the price lists in the argument is important for the final price for sale calculation
	 * - see the <a href="https://evitadb.io/documentation/deep-dive/price-for-sale-calculation">price for sale calculation
	 * algorithm documentation</a>. Price list names are represented by plain String and are case-sensitive. Price lists
	 * don't have to be stored in the database as an entity, and if they are, they are not currently associated with
	 * the price list code defined in the prices of other entities. The pricing structure is simple and flat for now
	 * (but this may change in the future).
	 * 
	 * Except for the <a href="https://evitadb.io/documentation/query/filtering/price?lang=evitaql#typical-usage-of-price-constraints">standard use-case</a>
	 * you can also create query with this constraint only:
	 * 
	 * <pre>
	 * priceInPriceLists(
	 *     "vip-group-1-level",
	 *     "vip-group-2-level",
	 *     "vip-group-3-level"
	 * )
	 * </pre>
	 * 
	 * Warning: Only a single occurrence of any of this constraint is allowed in the filter part of the query.
	 * Currently, there is no way to switch context between different parts of the filter and build queries such as find
	 * a product whose price is either in "CZK" or "EUR" currency at this or that time using this constraint.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/price#price-in-price-lists">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static PriceInPriceLists priceInPriceLists(@Nullable String... priceList) {
		if (priceList == null) {
			return null;
		}
		// if the array is empty - it was deliberate action which needs to produce empty result of the query
		if (priceList.length == 0) {
			return new PriceInPriceLists(priceList);
		}
		final String[] normalizeNames = Arrays.stream(priceList).filter(Objects::nonNull).filter(it -> !it.isBlank()).toArray(String[]::new);
		// the array was not empty, but contains only null values - this may not be deliberate action - for example
		// the initalization was like `priceInPriceLists(nullVariable)` and this should exclude the constraint
		if (normalizeNames.length == 0) {
			return null;
		}
		// otherwise propagate only non-null values
		return normalizeNames.length == priceList.length ?
			new PriceInPriceLists(priceList) : new PriceInPriceLists(normalizeNames);
	}

	/**
	 * The `priceInCurrency` constraint can be used to limit the result set to entities that have a price in the specified
	 * currency. Except for the <a href="https://evitadb.io/documentation/query/filtering/price?lang=evitaql#typical-usage-of-price-constraints">standard use-case</a>
	 * you can also create query with this constraint only:
	 * 
	 * <pre>
	 * priceInCurrency("EUR")
	 * </pre>
	 * 
	 * Warning: Only a single occurrence of any of this constraint is allowed in the filter part of the query.
	 * Currently, there is no way to switch context between different parts of the filter and build queries such as find
	 * a product whose price is either in "CZK" or "EUR" currency at this or that time using this constraint.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/price#price-in-currency">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static PriceInCurrency priceInCurrency(@Nullable String currency) {
		return currency == null ? null : new PriceInCurrency(currency);
	}

	/**
	 * The `priceInCurrency` constraint can be used to limit the result set to entities that have a price in the specified
	 * currency. Except for the <a href="https://evitadb.io/documentation/query/filtering/price?lang=evitaql#typical-usage-of-price-constraints">standard use-case</a>
	 * you can also create query with this constraint only:
	 * 
	 * <pre>
	 * priceInCurrency("EUR")
	 * </pre>
	 * 
	 * Warning: Only a single occurrence of any of this constraint is allowed in the filter part of the query.
	 * Currently, there is no way to switch context between different parts of the filter and build queries such as find
	 * a product whose price is either in "CZK" or "EUR" currency at this or that time using this constraint.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/price#price-in-currency">Visit detailed user documentation</a></p>
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
	 *     collection("Category"),
	 *     filterBy(
	 *         hierarchyWithinSelf(
	 *             attributeEquals("code", "accessories")
	 *         )
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent("code")
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "accessories")
	 *         )
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent("code")
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * Products assigned to two or more subcategories of Accessories category will only appear once in the response
	 * (contrary to what you might expect if you have experience with SQL).
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/hierarchy#hierarchy-within">Visit detailed user documentation</a></p>
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
	 *     collection("Category"),
	 *     filterBy(
	 *         hierarchyWithinSelf(
	 *             attributeEquals("code", "accessories")
	 *         )
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent("code")
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "accessories")
	 *         )
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent("code")
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * Products assigned to two or more subcategories of Accessories category will only appear once in the response
	 * (contrary to what you might expect if you have experience with SQL).
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/hierarchy#hierarchy-within">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static HierarchyWithin hierarchyWithin(@Nullable String referenceName, @Nullable FilterConstraint ofParent, @Nullable HierarchySpecificationFilterConstraint... with) {
		if (ofParent == null || referenceName == null) {
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
	 *     collection("Category"),
	 *     filterBy(
	 *         hierarchyWithinRootSelf()
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent("code")
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithinRoot("categories")
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent("code")
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * Products assigned to only one orphan category will be missing from the result. Products assigned to two or more
	 * categories will only appear once in the response (contrary to what you might expect if you have experience with SQL).
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/hierarchy#hierarchy-within-root">Visit detailed user documentation</a></p>
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
	 *     collection("Category"),
	 *     filterBy(
	 *         hierarchyWithinRootSelf()
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent("code")
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithinRoot("categories")
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent("code")
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * Products assigned to only one orphan category will be missing from the result. Products assigned to two or more
	 * categories will only appear once in the response (contrary to what you might expect if you have experience with SQL).
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/hierarchy#hierarchy-within-root">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static HierarchyWithinRoot hierarchyWithinRoot(@Nullable String referenceName, @Nullable HierarchySpecificationFilterConstraint... with) {
		return referenceName == null || with == null ? new HierarchyWithinRoot() : new HierarchyWithinRoot(referenceName, with);
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "accessories"),
	 *             having(
	 *                 or(
	 *                     attributeIsNull("validity"),
	 *                     attributeInRange("validity", 2023-10-01T01:00:00-01:00)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent("code")
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/hierarchy#having">Visit detailed user documentation</a></p>
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "accessories"),
	 *             excluding(
	 *                 attributeEquals("code", "wireless-headphones")
	 *             )
	 *         )
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent("code")
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "accessories"),
	 *             excluding(
	 *                 attributeEquals("code", "wireless-headphones")
	 *             )
	 *         )
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent("code")
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/hierarchy#excluding">Visit detailed user documentation</a></p>
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "smartwatches"),
	 *             directRelation()
	 *         )
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent("code")
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/hierarchy#direct-relation">Visit detailed user documentation</a></p>
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "accessories"),
	 *             excludingRoot()
	 *         )
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent("code")
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "keyboards"),
	 *             excludingRoot()
	 *         )
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent("code")
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ... we get only 4 items, which means that 16 were assigned directly to Keyboards category and only 4 of them were
	 * assigned to Exotic keyboards.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/hierarchy#excluding-root">Visit detailed user documentation</a></p>
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "vouchers-for-shareholders")
	 *         ),
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *        entityFetch(
	 *            attributeContent("code", "name")
	 *        )
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/locale#entity-locale-equals">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static EntityLocaleEquals entityLocaleEquals(@Nullable Locale locale) {
		return locale == null ? null : new EntityLocaleEquals(locale);
	}

	/**
	 * The `entityHaving` constraint is used to examine the attributes or other filterable properties of the referenced
	 * entity. It can only be used within the referenceHaving constraint, which defines the name of the entity reference
	 * that identifies the target entity to be subjected to the filtering restrictions in the entityHaving constraint.
	 * The filtering constraints for the entity can use entire range of filtering operators.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * referenceHaving(
	 *     "brand",
	 *     entityHaving(
	 *         attributeEquals("code", "apple")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/references#entity-having">Visit detailed user documentation</a></p>
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
	 * inRange("valid", 2020-07-30T20:37:50+00:00)
	 * inRange("age", 18)
	 * </pre>
	 * 
	 * Function supports attribute arrays and when attribute is of array type `inRange` returns true if any of attribute
	 * values has range, that envelopes the passed value the value in the query. If we have the attribute `age` with value
	 * `[[18, 25],[60,65]]` all these constraints will match:
	 * 
	 * <pre>
	 * inRange("age", 18)
	 * inRange("age", 24)
	 * inRange("age", 63)
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/range#attribute-in-range">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static AttributeInRange attributeInRange(@Nullable String attributeName, @Nullable OffsetDateTime atTheMoment) {
		return attributeName == null || atTheMoment == null ? null : new AttributeInRange(attributeName, atTheMoment);
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
	 * inRange("valid", 2020-07-30T20:37:50+00:00)
	 * inRange("age", 18)
	 * </pre>
	 * 
	 * Function supports attribute arrays and when attribute is of array type `inRange` returns true if any of attribute
	 * values has range, that envelopes the passed value the value in the query. If we have the attribute `age` with value
	 * `[[18, 25],[60,65]]` all these constraints will match:
	 * 
	 * <pre>
	 * inRange("age", 18)
	 * inRange("age", 24)
	 * inRange("age", 63)
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/range#attribute-in-range">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static AttributeInRange attributeInRange(@Nullable String attributeName, @Nullable Number theValue) {
		return attributeName == null || theValue == null ? null : new AttributeInRange(attributeName, theValue);
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
	 * inRange("valid", 2020-07-30T20:37:50+00:00)
	 * inRange("age", 18)
	 * </pre>
	 * 
	 * Function supports attribute arrays and when attribute is of array type `inRange` returns true if any of attribute
	 * values has range, that envelopes the passed value the value in the query. If we have the attribute `age` with value
	 * `[[18, 25],[60,65]]` all these constraints will match:
	 * 
	 * <pre>
	 * inRange("age", 18)
	 * inRange("age", 24)
	 * inRange("age", 63)
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/range#attribute-in-range">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static AttributeInRange attributeInRangeNow(@Nullable String attributeName) {
		return attributeName == null ? null : new AttributeInRange(attributeName);
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
	 * inSet("level", 1, 2, 3)
	 * </pre>
	 * 
	 * Function supports attribute arrays and when attribute is of array type `inSet` returns true if any of attribute values
	 * equals the value in the query. If we have the attribute `code` with value `["A","B","C"]` all these constraints will
	 * match:
	 * 
	 * <pre>
	 * inSet("code","A","D")
	 * inSet("code","A", "B")
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/comparable#attribute-in-set">Visit detailed user documentation</a></p>
	*/
	@SuppressWarnings("unchecked")
	@Nullable
	static <T extends Serializable> AttributeInSet attributeInSet(@Nullable String attributeName, @Nullable T... set) {
		// if the array is empty - it was deliberate action which needs to produce empty result of the query
		if (attributeName == null || set == null) {
			return null;
		}
		final List<T> args = Arrays.stream(set).filter(Objects::nonNull).toList();
		if (args.size() == set.length) {
			return new AttributeInSet(attributeName, set);
		} else if (args.isEmpty()) {
			// the array was not empty, but contains only null values - this may not be deliberate action - for example
			// the initalization was like `attributeInSet("attrName", nullVariable)` and this should exclude the constraint
			return null;
		} else {
			// otherwise propagate only non-null values
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
	 * equals("code", "abc")
	 * </pre>
	 * 
	 * Function supports attribute arrays and when attribute is of array type `equals` returns true if any of attribute values
	 * equals the value in the query. If we have the attribute `code` with value `["A","B","C"]` all these constraints will
	 * match:
	 * 
	 * <pre>
	 * equals("code","A")
	 * equals("code","B")
	 * equals("code","C")
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/comparable#attribute-equals">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static AttributeEquals attributeEqualsFalse(@Nullable String attributeName) {
		return attributeName == null ? null : new AttributeEquals(attributeName, Boolean.FALSE);
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
	 * equals("code", "abc")
	 * </pre>
	 * 
	 * Function supports attribute arrays and when attribute is of array type `equals` returns true if any of attribute values
	 * equals the value in the query. If we have the attribute `code` with value `["A","B","C"]` all these constraints will
	 * match:
	 * 
	 * <pre>
	 * equals("code","A")
	 * equals("code","B")
	 * equals("code","C")
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/comparable#attribute-equals">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static AttributeEquals attributeEqualsTrue(@Nullable String attributeName) {
		return attributeName == null ? null : new AttributeEquals(attributeName, Boolean.TRUE);
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
	 * attributeIs("visible", NULL)
	 * </pre>
	 * 
	 * Function supports attribute arrays in the same way as plain values.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/comparable#attribute-is">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static AttributeIs attributeIs(@Nullable String attributeName, @Nullable AttributeSpecialValue specialValue) {
		if (attributeName == null || specialValue == null) {
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
	 * attributeIs("visible", NULL)
	 * </pre>
	 * 
	 * Function supports attribute arrays in the same way as plain values.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/comparable#attribute-is">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static AttributeIs attributeIsNull(@Nullable String attributeName) {
		return attributeName == null ? null : new AttributeIs(attributeName, AttributeSpecialValue.NULL);
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
	 * attributeIs("visible", NULL)
	 * </pre>
	 * 
	 * Function supports attribute arrays in the same way as plain values.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/comparable#attribute-is">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static AttributeIs attributeIsNotNull(@Nullable String attributeName) {
		return attributeName == null ? null : new AttributeIs(attributeName, AttributeSpecialValue.NOT_NULL);
	}

	/**
	 * The `priceBetween` constraint restricts the result set to items that have a price for sale within the specified price
	 * range. This constraint is typically set by the user interface to allow the user to filter products by price, and
	 * should be nested inside the userFilter constraint container so that it can be properly handled by the facet or
	 * histogram computations.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * priceBetween(150.25, 220.0)
	 * </pre>
	 * 
	 * Warning: Only a single occurrence of any of this constraint is allowed in the filter part of the query.
	 * Currently, there is no way to switch context between different parts of the filter and build queries such as find
	 * a product whose price is either in "CZK" or "EUR" currency at this or that time using this constraint.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/price#price-between">Visit detailed user documentation</a></p>
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
	 * The `priceValidIn` excludes all entities that don't have a valid price for sale at the specified date and time. If
	 * the price doesn't have a validity property specified, it passes all validity checks.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * priceValidIn(2020-07-30T20:37:50+00:00)
	 * </pre>
	 * 
	 * Warning: Only a single occurrence of any of this constraint is allowed in the filter part of the query.
	 * Currently, there is no way to switch context between different parts of the filter and build queries such as find
	 * a product whose price is either in "CZK" or "EUR" currency at this or that time using this constraint.
	 * 
	 * <p><a href="https://evitadb.io/documentation/filtering/price#price-valid-in">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static PriceValidIn priceValidIn(@Nullable OffsetDateTime theMoment) {
		return theMoment == null ? null : new PriceValidIn(theMoment);
	}

	/**
	 * The `priceValidIn` excludes all entities that don't have a valid price for sale at the specified date and time. If
	 * the price doesn't have a validity property specified, it passes all validity checks.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * priceValidIn(2020-07-30T20:37:50+00:00)
	 * </pre>
	 * 
	 * Warning: Only a single occurrence of any of this constraint is allowed in the filter part of the query.
	 * Currently, there is no way to switch context between different parts of the filter and build queries such as find
	 * a product whose price is either in "CZK" or "EUR" currency at this or that time using this constraint.
	 * 
	 * <p><a href="https://evitadb.io/documentation/filtering/price#price-valid-in">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static PriceValidIn priceValidInNow() {
		return new PriceValidIn();
	}

	/**
	 * The `facetHaving` filtering constraint is typically placed inside the {@link UserFilter} constraint container and
	 * represents the user's request to drill down the result set by a particular facet. The `facetHaving` constraint works
	 * exactly like the referenceHaving constraint, but works in conjunction with the facetSummary requirement to correctly
	 * calculate the facet statistics and impact predictions. When used outside the userFilter constraint container,
	 * the `facetHaving` constraint behaves like the {@link ReferenceHaving} constraint.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * userFilter(
	 *   facetHaving(
	 *     "brand",
	 *     entityHaving(
	 *       attributeInSet("code", "amazon")
	 *     )
	 *   )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/references#facet-having">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static FacetHaving facetHaving(@Nullable String referenceName, @Nullable FilterConstraint... constraint) {
		return referenceName == null || ArrayUtils.isEmpty(constraint) ? null : new FacetHaving(referenceName, constraint);
	}

	/**
	 * The `entityPrimaryKeyInSet` constraint limits the list of returned entities by exactly specifying their entity
	 * primary keys.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * primaryKey(1, 2, 3)
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/constant#entity-primary-key-in-set">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static EntityPrimaryKeyInSet entityPrimaryKeyInSet(@Nullable Integer... primaryKey) {
		if (primaryKey == null) {
			return null;
		}
		// if the array is empty - it was deliberate action which needs to produce empty result of the query
		if (primaryKey.length == 0) {
			return new EntityPrimaryKeyInSet(primaryKey);
		}
		final Integer[] normalizedPks = Arrays.stream(primaryKey).filter(Objects::nonNull).toArray(Integer[]::new);
		// the array was not empty, but contains only null values - this may not be deliberate action - for example
		// the initalization was like `entityPrimaryKeyInSet(nullVariable)` and this should exclude the constraint
		if (normalizedPks.length == 0) {
			return null;
		}
		// otherwise propagate only non-null values
		return normalizedPks.length == primaryKey.length ?
			new EntityPrimaryKeyInSet(primaryKey) : new EntityPrimaryKeyInSet(normalizedPks);
	}

	/**
	 * The `entityPrimaryKeyInSet` constraint limits the list of returned entities by exactly specifying their entity
	 * primary keys.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * primaryKey(1, 2, 3)
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/filtering/constant#entity-primary-key-in-set">Visit detailed user documentation</a></p>
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
	 * This `orderBy` is container for ordering. It is mandatory container when any ordering is to be used.
	 * evitaDB requires a previously prepared sort index to be able to sort entities. This fact makes sorting much faster
	 * than ad-hoc sorting by attribute value. Also, the sorting mechanism of evitaDB is somewhat different from what you
	 * might be used to. If you sort entities by two attributes in an orderBy clause of the query, evitaDB sorts them first
	 * by the first attribute (if present) and then by the second (but only those where the first attribute is missing).
	 * If two entities have the same value of the first attribute, they are not sorted by the second attribute, but by the
	 * primary key (in ascending order). If we want to use fast "pre-sorted" indexes, there is no other way to do it,
	 * because the secondary order would not be known until a query time.
	 * 
	 * This default sorting behavior by multiple attributes is not always desirable, so evitaDB allows you to define
	 * a sortable attribute compound, which is a virtual attribute composed of the values of several other attributes.
	 * evitaDB also allows you to specify the order of the "pre-sorting" behavior (ascending/descending) for each of these
	 * attributes, and also the behavior for NULL values (first/last) if the attribute is completely missing in the entity.
	 * The sortable attribute compound is then used in the orderBy clause of the query instead of specifying the multiple
	 * individual attributes to achieve the expected sorting behavior while maintaining the speed of the "pre-sorted"
	 * indexes.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * orderBy(
	 *     ascending("code"),
	 *     ascending("create"),
	 *     priceDescending()
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/basics#order-by">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static OrderBy orderBy(@Nullable OrderConstraint... constraints) {
		if (constraints == null) {
			return null;
		}
		return new OrderBy(constraints);
	}

	/**
	 * The `entityGroupProperty` ordering constraint can only be used within the {@link ReferenceContent} requirement.
	 * It allows the context of the reference ordering to be changed from attributes of the reference itself to attributes
	 * of the group entity within which the reference is aggregated.
	 * 
	 * In other words, if the Product entity has multiple references to ParameterValue entities that are grouped by their
	 * assignment to the Parameter entity, you can sort those references primarily by the name attribute of the grouping
	 * entity, and secondarily by the name attribute of the referenced entity. Let's look at an example:
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         attributeEquals("code", "garmin-vivoactive-4"),
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent("code"),
	 *             referenceContent(
	 *                 "parameterValues",
	 *                 orderBy(
	 *                     entityGroupProperty(
	 *                         attributeNatural("name", ASC)
	 *                     ),
	 *                     entityProperty(
	 *                         attributeNatural("name", ASC)
	 *                     )
	 *                 ),
	 *                 entityFetch(
	 *                     attributeContent("name")
	 *                 ),
	 *                 entityGroupFetch(
	 *                     attributeContent("name")
	 *                 )
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/basics#order-by">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static OrderGroupBy orderGroupBy(@Nullable OrderConstraint... constraints) {
		if (constraints == null) {
			return null;
		}
		return new OrderGroupBy(constraints);
	}

	/**
	 * The constraint allows to sort output entities by primary key values in the exact order.
	 * 
	 * Example usage:
	 * 
	 * <pre>
	 * query(
	 *    orderBy(
	 *       entityPrimaryKeyNatural(DESC)
	 *    )
	 * )
	 * </pre>
	 * 
	 * The example will return the selected entities (if present) in the descending order of their primary keys. Since
	 * the entities are by default ordered by their primary key in ascending order, it has no sense to use this constraint
	 * with {@link OrderDirection#ASC} direction.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/ordering/comparable#primary-key-natural">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static EntityPrimaryKeyNatural entityPrimaryKeyNatural(@Nullable OrderDirection direction) {
		return new EntityPrimaryKeyNatural(direction == null ? OrderDirection.ASC : direction);
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/ordering/constant#exact-entity-primary-key-order-used-in-filter">Visit detailed user documentation</a></p>
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/ordering/constant#exact-entity-primary-key-order">Visit detailed user documentation</a></p>
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
	 *       attributeInSet("code", "t-shirt", "sweater", "pants")
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/ordering/constant#exact-entity-attribute-value-order-used-in-filter">Visit detailed user documentation</a></p>
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
	 *       attributeSetExact("code", "t-shirt", "sweater", "pants")
	 *    )
	 * )
	 * </pre>
	 * 
	 * The example will return the selected entities (if present) in the exact order of their `code` attributes that is
	 * stated in the second to Nth argument of this ordering constraint. If there are entities, that have not the attribute
	 * `code` , then they will be present at the end of the output in ascending order of their primary keys (or they will be
	 * sorted by additional ordering constraint in the chain).
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/ordering/constant#exact-entity-attribute-value-order">Visit detailed user documentation</a></p>
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
	 *     collection("Product"),
	 *     filterBy(
	 *         referenceHaving(
	 *             "brand",
	 *             entityHaving(
	 *                 attributeEquals("code","sony")
	 *             )
	 *         )
	 *     ),
	 *     orderBy(
	 *         referenceProperty(
	 *             "brand",
	 *             attributeNatural("orderInBrand", ASC)
	 *         )
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent("code"),
	 *             referenceContentWithAttributes(
	 *                 "brand",
	 *                 attributeContent("orderInBrand")
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
	 * in `referenceContent` automatically refer to the reference attributes, unless the {@link EntityProperty} or
	 * {@link EntityGroupProperty} container is used there.
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/ordering/reference#reference-property">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceProperty referenceProperty(@Nullable String referenceName, @Nullable OrderConstraint... constraints) {
		if (referenceName == null || constraints == null) {
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
	 *     collection("Product"),
	 *     filterBy(
	 *         attributeEquals("code", "garmin-vivoactive-4")
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent("code"),
	 *             referenceContent(
	 *                 "parameterValues",
	 *                 orderBy(
	 *                     entityProperty(
	 *                         attributeNatural("code", DESC)
	 *                     )
	 *                 ),
	 *                 entityFetch(
	 *                     attributeContent("code")
	 *                 )
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/ordering/reference#entity-property">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static EntityProperty entityProperty(@Nullable OrderConstraint... constraints) {
		if (constraints == null) {
			return null;
		}
		return new EntityProperty(constraints);
	}

	/**
	 * The `entityGroupProperty` ordering constraint can only be used within the {@link ReferenceContent} requirement. It
	 * allows to change the context of the reference ordering from attributes of the reference itself to attributes of
	 * the entity group the reference is aggregated within.
	 * 
	 * In other words, if the `Product` entity has multiple references to `Parameter` entities (blue/red/yellow) grouped
	 * within `ParameterType` (color) entity, you can sort those references by, for example, the `priority` or `name`
	 * attribute of the `ParameterType` entity.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         attributeEquals("code", "garmin-vivoactive-4")
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent("code"),
	 *             referenceContent(
	 *                 "parameterValues",
	 *                 orderBy(
	 *                     entityGroupProperty(
	 *                         attributeNatural("code", DESC)
	 *                     )
	 *                 ),
	 *                 entityFetch(
	 *                     attributeContent("code")
	 *                 )
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * Most of the time, you will want to group primarily by a group property and secondarily by a referenced entity
	 * property, which can be achieved in the following way:
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         attributeEquals("code", "garmin-vivoactive-4")
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent("code"),
	 *             referenceContent(
	 *                 "parameterValues",
	 *                 orderBy(
	 *                     entityGroupProperty(
	 *                         attributeNatural("code", DESC)
	 *                     ),
	 *                     entityProperty(
	 *                         attributeNatural("code", DESC)
	 *                     )
	 *                 ),
	 *                 entityFetch(
	 *                     attributeContent("code")
	 *                 )
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/ordering/reference#entity-group-property">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static EntityGroupProperty entityGroupProperty(@Nullable OrderConstraint... constraints) {
		if (constraints == null) {
			return null;
		}
		return new EntityGroupProperty(constraints);
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
	 *     collection("Product"),
	 *     orderBy(
	 *         attributeNatural("orderedQuantity", DESC)
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent("code", "orderedQuantity")
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/ordering/comparable#attribute-natural">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static AttributeNatural attributeNatural(@Nullable String attributeName) {
		return attributeName == null ? null : new AttributeNatural(attributeName);
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
	 *     collection("Product"),
	 *     orderBy(
	 *         attributeNatural("orderedQuantity", DESC)
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent("code", "orderedQuantity")
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/ordering/comparable#attribute-natural">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static AttributeNatural attributeNatural(@Nullable String attributeName, @Nullable OrderDirection orderDirection) {
		return attributeName == null ? null :
			new AttributeNatural(attributeName, orderDirection == null ? OrderDirection.ASC : orderDirection);
	}

	/**
	 * The `priceNatural` constraint allows output entities to be sorted by their selling price in their natural numeric
	 * order. It requires only the order direction and the price constraints in the `filterBy` section of the query.
	 * The price variant (with or without tax) is determined by the {@link PriceType} requirement of the query (price with
	 * tax is used by default).
	 * 
	 * Please read the <a href="https://evitadb.io/documentation/deep-dive/price-for-sale-calculation">price for sale
	 * calculation algorithm documentation</a> to understand how the price for sale is calculated.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * priceNatural()
	 * priceNatural(DESC)
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/ordering/price#price-natural">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static PriceNatural priceNatural() {
		return new PriceNatural();
	}

	/**
	 * The `priceNatural` constraint allows output entities to be sorted by their selling price in their natural numeric
	 * order. It requires only the order direction and the price constraints in the `filterBy` section of the query.
	 * The price variant (with or without tax) is determined by the {@link PriceType} requirement of the query (price with
	 * tax is used by default).
	 * 
	 * Please read the <a href="https://evitadb.io/documentation/deep-dive/price-for-sale-calculation">price for sale
	 * calculation algorithm documentation</a> to understand how the price for sale is calculated.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * priceNatural()
	 * priceNatural(DESC)
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/ordering/price#price-natural">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static PriceNatural priceNatural(@Nullable OrderDirection orderDirection) {
		return new PriceNatural(orderDirection == null ? OrderDirection.ASC : orderDirection);
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/ordering/random#random">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static Random random() {
		return new Random();
	}

	/*
		requirement
	 */

	/**
	 * Requirements have no direct parallel in other database languages. They define sideway calculations, paging,
	 * the amount of data fetched for each returned entity, and so on, but never affect the number or order of returned
	 * entities. They also allow to compute additional calculations that relate to the returned entities, but contain
	 * other contextual data - for example hierarchy data for creating menus, facet summary for parametrized filter,
	 * histograms for charts, and so on.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * require(
	 *     page(1, 2),
	 *     entityFetch()
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/basics#require">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static Require require(@Nullable RequireConstraint... constraints) {
		if (constraints == null) {
			return null;
		}
		return new Require(constraints);
	}

	/**
	 * The `attributeHistogram` can be computed from any filterable attribute whose type is numeric. The histogram is
	 * computed only from the attributes of elements that match the current mandatory part of the filter. The interval
	 * related constraints - i.e. {@link AttributeBetween} and {@link PriceBetween} in the userFilter part are excluded for
	 * the sake of histogram calculation. If this weren't the case, the user narrowing the filtered range based on
	 * the histogram results would be driven into a narrower and narrower range and eventually into a dead end.
	 * 
	 * It accepts two plus arguments:
	 * 
	 * 1. The number of buckets (columns) the histogram should contain.
	 * 2. The behavior of the histogram calculation - either STANDARD (default), where the exactly requested bucket count
	 *    is returned or OPTIMIZED, where the number of columns is reduced if the data is scarce and there would be big gaps
	 *    (empty buckets) between buckets. This leads to more compact histograms, which provide better user experience.
	 * 3. variable number of attribute names for which the histogram should be computed.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * attributeHistogram(5, "width", "height")
	 * attributeHistogram(5, OPTIMIZED, "width", "height")
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/histogram#attribute-histogram">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static AttributeHistogram attributeHistogram(int requestedBucketCount, @Nullable String... attributeName) {
		if (ArrayUtils.isEmpty(attributeName)) {
			return null;
		}
		return new AttributeHistogram(requestedBucketCount, attributeName);
	}

	/**
	 * The `attributeHistogram` can be computed from any filterable attribute whose type is numeric. The histogram is
	 * computed only from the attributes of elements that match the current mandatory part of the filter. The interval
	 * related constraints - i.e. {@link AttributeBetween} and {@link PriceBetween} in the userFilter part are excluded for
	 * the sake of histogram calculation. If this weren't the case, the user narrowing the filtered range based on
	 * the histogram results would be driven into a narrower and narrower range and eventually into a dead end.
	 * 
	 * It accepts two plus arguments:
	 * 
	 * 1. The number of buckets (columns) the histogram should contain.
	 * 2. The behavior of the histogram calculation - either STANDARD (default), where the exactly requested bucket count
	 *    is returned or OPTIMIZED, where the number of columns is reduced if the data is scarce and there would be big gaps
	 *    (empty buckets) between buckets. This leads to more compact histograms, which provide better user experience.
	 * 3. variable number of attribute names for which the histogram should be computed.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * attributeHistogram(5, "width", "height")
	 * attributeHistogram(5, OPTIMIZED, "width", "height")
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/histogram#attribute-histogram">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static AttributeHistogram attributeHistogram(int requestedBucketCount, @Nullable HistogramBehavior behavior, @Nullable String... attributeName) {
		if (ArrayUtils.isEmpty(attributeName)) {
			return null;
		}
		return new AttributeHistogram(requestedBucketCount, behavior, attributeName);
	}

	/**
	 * The `priceHistogram` is computed from the price for sale. The interval related constraints - i.e. {@link AttributeBetween}
	 * and {@link PriceBetween} in the userFilter part are excluded for the sake of histogram calculation. If this weren't
	 * the case, the user narrowing the filtered range based on the histogram results would be driven into a narrower and
	 * narrower range and eventually into a dead end.
	 * 
	 * It accepts two arguments:
	 * 
	 * 1. The number of buckets (columns) the histogram should contain.
	 * 2. The behavior of the histogram calculation - either STANDARD (default), where the exactly requested bucket count
	 *    is returned or OPTIMIZED, where the number of columns is reduced if the data is scarce and there would be big gaps
	 *    (empty buckets) between buckets. This leads to more compact histograms, which provide better user experience.
	 * 
	 * The priceType requirement the source price property for the histogram computation. If no requirement, the histogram
	 * visualizes the price with tax.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * priceHistogram(20)
	 * priceHistogram(20, OPTIMIZED)
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/histogram#price-histogram">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static PriceHistogram priceHistogram(int requestedBucketCount) {
		return new PriceHistogram(requestedBucketCount);
	}

	/**
	 * The `priceHistogram` is computed from the price for sale. The interval related constraints - i.e. {@link AttributeBetween}
	 * and {@link PriceBetween} in the userFilter part are excluded for the sake of histogram calculation. If this weren't
	 * the case, the user narrowing the filtered range based on the histogram results would be driven into a narrower and
	 * narrower range and eventually into a dead end.
	 * 
	 * It accepts two arguments:
	 * 
	 * 1. The number of buckets (columns) the histogram should contain.
	 * 2. The behavior of the histogram calculation - either STANDARD (default), where the exactly requested bucket count
	 *    is returned or OPTIMIZED, where the number of columns is reduced if the data is scarce and there would be big gaps
	 *    (empty buckets) between buckets. This leads to more compact histograms, which provide better user experience.
	 * 
	 * The priceType requirement the source price property for the histogram computation. If no requirement, the histogram
	 * visualizes the price with tax.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * priceHistogram(20)
	 * priceHistogram(20, OPTIMIZED)
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/histogram#price-histogram">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static PriceHistogram priceHistogram(int requestedBucketCount, @Nullable HistogramBehavior behavior) {
		return new PriceHistogram(requestedBucketCount, behavior);
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
	 *    entities("product"),
	 *    filterBy(
	 *       userFilter(
	 *          facet("group", 1, 2),
	 *          facet(
	 *             "parameterType",
	 *             entityPrimaryKeyInSet(11, 12, 22)
	 *          )
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
	 * facet red(12). If require `facetGroupsConjunction('parameterType', 1)` is passed in the query filtering condition will
	 * be composed as: blue(11) AND red(12)
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-groups-conjunction">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static FacetGroupsConjunction facetGroupsConjunction(@Nullable String referenceName, @Nullable FilterBy filterBy) {
		return referenceName == null ? null : new FacetGroupsConjunction(referenceName, filterBy);
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
	 *    entities("product"),
	 *    filterBy(
	 *       userFilter(
	 *          facet("group", 1, 2),
	 *          facet(
	 *             "parameterType",
	 *             entityPrimaryKeyInSet(11, 12, 22)
	 *          )
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
	 * facet red(12). If require `facetGroupsConjunction('parameterType', 1)` is passed in the query filtering condition will
	 * be composed as: blue(11) AND red(12)
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-groups-conjunction">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static FacetGroupsConjunction facetGroupsConjunction(@Nullable String referenceName) {
		return referenceName == null ? null : new FacetGroupsConjunction(referenceName, null);
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
	 *    entities("product"),
	 *    filterBy(
	 *       userFilter(
	 *          facet("group", 1, 2),
	 *          facet(
	 *             "parameterType",
	 *             entityPrimaryKeyInSet(11, 12, 22)
	 *          )
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
	 * have facet blue as well as facet large and action products tag (AND). If require `facetGroupsDisjunction('tag', 3)`
	 * is passed in the query, filtering condition will be composed as: (`blue(11)` AND `large(22)`) OR `new products(31)`
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-groups-disjunction">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static FacetGroupsDisjunction facetGroupsDisjunction(@Nullable String referenceName, @Nullable FilterBy filterBy) {
		return referenceName == null ? null : new FacetGroupsDisjunction(referenceName, filterBy);
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
	 *    entities("product"),
	 *    filterBy(
	 *       userFilter(
	 *          facet("group", 1, 2),
	 *          facet(
	 *             "parameterType",
	 *             entityPrimaryKeyInSet(11, 12, 22)
	 *          )
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
	 * have facet blue as well as facet large and action products tag (AND). If require `facetGroupsDisjunction('tag', 3)`
	 * is passed in the query, filtering condition will be composed as: (`blue(11)` AND `large(22)`) OR `new products(31)`
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-groups-disjunction">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static FacetGroupsDisjunction facetGroupsDisjunction(@Nullable String referenceName) {
		return referenceName == null ? null : new FacetGroupsDisjunction(referenceName, null);
	}

	/**
	 * The `facetGroupsNegation` changes the behavior of the facet option in all facet groups specified in the filterBy
	 * constraint. Instead of returning only those items that have a reference to that particular faceted entity, the query
	 * result will return only those items that don't have a reference to it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     require(
	 *         facetSummaryOfReference(
	 *             "parameterValues",
	 *             IMPACT,
	 *             filterBy(attributeContains("code", "4")),
	 *             filterGroupBy(attributeInSet("code", "ram-memory", "rom-memory")),
	 *             entityFetch(attributeContent("code")),
	 *             entityGroupFetch(attributeContent("code"))
	 *         ),
	 *         facetGroupsNegation(
	 *             "parameterValues",
	 *             filterBy(
	 *               attributeInSet("code", "ram-memory")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * The predicted results in the negated groups are far greater than the numbers produced by the default behavior.
	 * Selecting any option in the RAM facet group predicts returning thousands of results, while the ROM facet group with
	 * default behavior predicts only a dozen of them.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-groups-negation">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static FacetGroupsNegation facetGroupsNegation(@Nullable String referenceName, @Nullable FilterBy filterBy) {
		return referenceName == null ? null : new FacetGroupsNegation(referenceName, filterBy);
	}

	/**
	 * The `facetGroupsNegation` changes the behavior of the facet option in all facet groups specified in the filterBy
	 * constraint. Instead of returning only those items that have a reference to that particular faceted entity, the query
	 * result will return only those items that don't have a reference to it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     require(
	 *         facetSummaryOfReference(
	 *             "parameterValues",
	 *             IMPACT,
	 *             filterBy(attributeContains("code", "4")),
	 *             filterGroupBy(attributeInSet("code", "ram-memory", "rom-memory")),
	 *             entityFetch(attributeContent("code")),
	 *             entityGroupFetch(attributeContent("code"))
	 *         ),
	 *         facetGroupsNegation(
	 *             "parameterValues",
	 *             filterBy(
	 *               attributeInSet("code", "ram-memory")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * The predicted results in the negated groups are far greater than the numbers produced by the default behavior.
	 * Selecting any option in the RAM facet group predicts returning thousands of results, while the ROM facet group with
	 * default behavior predicts only a dozen of them.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-groups-negation">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static FacetGroupsNegation facetGroupsNegation(@Nullable String referenceName) {
		return referenceName == null ? null : new FacetGroupsNegation(referenceName, null);
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#hierarchy-of-self">Visit detailed user documentation</a></p>
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#hierarchy-of-self">Visit detailed user documentation</a></p>
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
	 *        structures (default behavior)
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#hierarchy-of-reference">Visit detailed user documentation</a></p>
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
	 *        structures (default behavior)
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#hierarchy-of-reference">Visit detailed user documentation</a></p>
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
	 *        structures (default behavior)
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#hierarchy-of-reference">Visit detailed user documentation</a></p>
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
	 *        structures (default behavior)
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#hierarchy-of-reference">Visit detailed user documentation</a></p>
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
	 *        structures (default behavior)
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#hierarchy-of-reference">Visit detailed user documentation</a></p>
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
	 *        structures (default behavior)
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#hierarchy-of-reference">Visit detailed user documentation</a></p>
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
	 *        structures (default behavior)
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#hierarchy-of-reference">Visit detailed user documentation</a></p>
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
	 *        structures (default behavior)
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#hierarchy-of-reference">Visit detailed user documentation</a></p>
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "audio")
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             "categories",
	 *             fromRoot(
	 *                 "megaMenu",
	 *                 entityFetch(attributeContent("code")),
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#from-root">Visit detailed user documentation</a></p>
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "audio")
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             "categories",
	 *             fromRoot(
	 *                 "megaMenu",
	 *                 entityFetch(attributeContent("code")),
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#from-root">Visit detailed user documentation</a></p>
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "audio")
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             "categories",
	 *             fromNode(
	 *                 "sideMenu1",
	 *                 node(
	 *                     filterBy(
	 *                         attributeEquals("code", "portables")
	 *                     )
	 *                 ),
	 *                 entityFetch(attributeContent("code")),
	 *                 stopAt(distance(1)),
	 *                 statistics(
	 *                     CHILDREN_COUNT,
	 *                     QUERIED_ENTITY_COUNT
	 *                 )
	 *             ),
	 *             fromNode(
	 *                 "sideMenu2",
	 *                 node(
	 *                     filterBy(
	 *                         attributeEquals("code", "laptops")
	 *                     )
	 *                 ),
	 *                 entityFetch(attributeContent("code")),
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#from-node">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static HierarchyFromNode fromNode(
		@Nullable String outputName,
		@Nullable HierarchyNode node,
		@Nullable HierarchyOutputRequireConstraint... requirement
	) {
		if (outputName == null || node == null) {
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "audio")
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             "categories",
	 *             fromNode(
	 *                 "sideMenu1",
	 *                 node(
	 *                     filterBy(
	 *                         attributeEquals("code", "portables")
	 *                     )
	 *                 ),
	 *                 entityFetch(attributeContent("code")),
	 *                 stopAt(distance(1)),
	 *                 statistics(
	 *                     CHILDREN_COUNT,
	 *                     QUERIED_ENTITY_COUNT
	 *                 )
	 *             ),
	 *             fromNode(
	 *                 "sideMenu2",
	 *                 node(
	 *                     filterBy(
	 *                         attributeEquals("code", "laptops")
	 *                     )
	 *                 ),
	 *                 entityFetch(attributeContent("code")),
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#from-node">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static HierarchyFromNode fromNode(
		@Nullable String outputName,
		@Nullable HierarchyNode node,
		@Nullable EntityFetch entityFetch,
		@Nullable HierarchyOutputRequireConstraint... requirement
	) {
		if (outputName == null || node == null) {
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "audio")
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             "categories",
	 *             children(
	 *                 "subcategories",
	 *                 entityFetch(attributeContent("code")),
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#children">Visit detailed user documentation</a></p>
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "audio")
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             "categories",
	 *             children(
	 *                 "subcategories",
	 *                 entityFetch(attributeContent("code")),
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#children">Visit detailed user documentation</a></p>
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "audio")
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             "categories",
	 *             siblings(
	 *                 "audioSiblings",
	 *                 entityFetch(attributeContent("code")),
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#siblings">Visit detailed user documentation</a></p>
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "audio")
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             "categories",
	 *             siblings(
	 *                 "audioSiblings",
	 *                 entityFetch(attributeContent("code")),
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#siblings">Visit detailed user documentation</a></p>
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "audio")
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             "categories",
	 *             siblings(
	 *                 "audioSiblings",
	 *                 entityFetch(attributeContent("code")),
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#siblings">Visit detailed user documentation</a></p>
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "audio")
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             "categories",
	 *             siblings(
	 *                 "audioSiblings",
	 *                 entityFetch(attributeContent("code")),
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#siblings">Visit detailed user documentation</a></p>
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "true-wireless")
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             "categories",
	 *             parents(
	 *                 "parentAxis",
	 *                 entityFetch(attributeContent("code")),
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#parents">Visit detailed user documentation</a></p>
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "true-wireless")
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             "categories",
	 *             parents(
	 *                 "parentAxis",
	 *                 entityFetch(attributeContent("code")),
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#parents">Visit detailed user documentation</a></p>
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "true-wireless")
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             "categories",
	 *             parents(
	 *                 "parentAxis",
	 *                 entityFetch(attributeContent("code")),
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#parents">Visit detailed user documentation</a></p>
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "true-wireless")
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             "categories",
	 *             parents(
	 *                 "parentAxis",
	 *                 entityFetch(attributeContent("code")),
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#parents">Visit detailed user documentation</a></p>
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#stop-at">Visit detailed user documentation</a></p>
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "accessories")
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             "categories",
	 *             children(
	 *                 "subMenu",
	 *                 entityFetch(attributeContent("code")),
	 *                 stopAt(
	 *                     node(
	 *                         filterBy(
	 *                             attributeStartsWith("code", "w")
	 *                         )
	 *                     )
	 *                 )
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#node">Visit detailed user documentation</a></p>
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "audio")
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             "categories",
	 *             fromRoot(
	 *                 "megaMenu",
	 *                 entityFetch(attributeContent("code")),
	 *                 stopAt(level(2))
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * The query lists products in Audio category and its subcategories. Along with the products returned, it
	 * also returns a computed megaMenu data structure that lists top two levels of the entire hierarchy.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#level">Visit detailed user documentation</a></p>
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
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "audio")
	 *         )
	 *     ),
	 *     require(
	 *         hierarchyOfReference(
	 *             "categories",
	 *             children(
	 *                 "subcategories",
	 *                 entityFetch(attributeContent("code")),
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#distance">Visit detailed user documentation</a></p>
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#statistics">Visit detailed user documentation</a></p>
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#statistics">Visit detailed user documentation</a></p>
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
	 * The `entityFetch` requirement is used to trigger loading one or more entity data containers from the disk by its
	 * primary key. This operation requires a disk access unless the entity is already loaded in the database cache
	 * (frequently fetched entities have higher chance to stay in the cache).
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Brand"),
	 *     filterBy(
	 *         entityPrimaryKeyInSet(64703),
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent("code", "name")
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * See internal contents available for fetching in {@link EntityContentRequire}:
	 * 
	 * - {@link AttributeContent}
	 * - {@link AssociatedDataContent}
	 * - {@link PriceContent}
	 * - {@link HierarchyContent}
	 * - {@link ReferenceContent}
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#entity-fetch">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static EntityFetch entityFetch(@Nullable EntityContentRequire... requirements) {
		if (requirements == null) {
			return new EntityFetch();
		}
		return new EntityFetch(requirements);
	}

	/**
	 * The `entityGroupFetch` requirement is similar to {@link EntityFetch} but is used to trigger loading one or more
	 * referenced group entities in the {@link ReferenceContent} parent.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Brand"),
	 *     filterBy(
	 *         entityPrimaryKeyInSet(64703),
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             referenceContent(
	 *                "parameterValues",
	 *                entityGroupFetch(
	 *                   attributeContent("code", "name")
	 *                )
	 *              )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * See internal contents available for fetching in {@link EntityContentRequire}:
	 * 
	 * - {@link AttributeContent}
	 * - {@link AssociatedDataContent}
	 * - {@link PriceContent}
	 * - {@link HierarchyContent}
	 * - {@link ReferenceContent}
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#entity-group-fetch">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static EntityGroupFetch entityGroupFetch(@Nullable EntityContentRequire... requirements) {
		if (requirements == null) {
			return new EntityGroupFetch();
		}
		return new EntityGroupFetch(requirements);
	}

	/**
	 * The `attributeContent` requirement is used to retrieve one or more entity or reference attributes. Localized attributes
	 * are only fetched if there is a locale context in the query, either by using the {@link EntityLocaleEquals} filter
	 * constraint or the dataInLocales require constraint.
	 * 
	 * All entity attributes are fetched from disk in bulk, so specifying only a few of them in the `attributeContent`
	 * requirement only reduces the amount of data transferred over the network. It's not bad to fetch all the attributes
	 * of an entity using `attributeContentAll`.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code", "name")
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#attribute-content">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static AttributeContent attributeContentAll() {
		return new AttributeContent();
	}

	/**
	 * The `attributeContent` requirement is used to retrieve one or more entity or reference attributes. Localized attributes
	 * are only fetched if there is a locale context in the query, either by using the {@link EntityLocaleEquals} filter
	 * constraint or the dataInLocales require constraint.
	 * 
	 * All entity attributes are fetched from disk in bulk, so specifying only a few of them in the `attributeContent`
	 * requirement only reduces the amount of data transferred over the network. It's not bad to fetch all the attributes
	 * of an entity using `attributeContentAll`.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code", "name")
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#attribute-content">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static AttributeContent attributeContent(@Nullable String... attributeName) {
		if (attributeName == null) {
			return new AttributeContent();
		}
		return new AttributeContent(attributeName);
	}

	/**
	 * This `associatedData` requirement changes default behaviour of the query engine returning only entity primary keys in
	 * the result. When this requirement is used result contains entity bodies along with associated data with names
	 * specified in one or more arguments of this requirement.
	 * 
	 * This requirement implicitly triggers {@link EntityFetch} requirement because attributes cannot be returned without entity.
	 * Localized associated data is returned according to {@link EntityLocaleEquals} query. Requirement might be combined
	 * with {@link AttributeContent} requirement.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * associatedData("description", "gallery-3d")
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#associated-data-content">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static AssociatedDataContent associatedDataContentAll() {
		return new AssociatedDataContent();
	}

	/**
	 * This `associatedData` requirement changes default behaviour of the query engine returning only entity primary keys in
	 * the result. When this requirement is used result contains entity bodies along with associated data with names
	 * specified in one or more arguments of this requirement.
	 * 
	 * This requirement implicitly triggers {@link EntityFetch} requirement because attributes cannot be returned without entity.
	 * Localized associated data is returned according to {@link EntityLocaleEquals} query. Requirement might be combined
	 * with {@link AttributeContent} requirement.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * associatedData("description", "gallery-3d")
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#associated-data-content">Visit detailed user documentation</a></p>
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
	 * <pre>
	 * dataInLocales("en-US")
	 * </pre>
	 * 
	 * Example that fetches all available global and localized data:
	 * 
	 * <pre>
	 * dataInLocalesAll()
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#data-in-locales">Visit detailed user documentation</a></p>
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
	 * <pre>
	 * dataInLocales("en-US")
	 * </pre>
	 * 
	 * Example that fetches all available global and localized data:
	 * 
	 * <pre>
	 * dataInLocalesAll()
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#data-in-locales">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static DataInLocales dataInLocales(@Nullable Locale... locale) {
		if (locale == null) {
			return new DataInLocales();
		}
		return new DataInLocales(locale);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static ReferenceContent referenceContentAll() {
		return new ReferenceContent();
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes() {
		return new ReferenceContent((AttributeContent) null);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(@Nullable AttributeContent attributeContent) {
		return new ReferenceContent(attributeContent);
	}


	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String referenceName) {
		if (referenceName == null) {
			return new ReferenceContent();
		}
		return new ReferenceContent(referenceName);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable String... attributeNames) {
		if (referenceName == null) {
			return null;
		} else {
			return new ReferenceContent(
				referenceName, null, null,
				attributeContent(attributeNames), null, null
			);
		}
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName) {
		return referenceName == null ? null : new ReferenceContent(referenceName, null, null, null, null, null);
	}


	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable AttributeContent attributeContent) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, null, null,
			attributeContent, null, null
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String... referenceName) {
		if (referenceName == null) {
			return new ReferenceContent();
		}
		return new ReferenceContent(referenceName);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
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
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable EntityFetch entityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName,  null, null,
			null, entityRequirement, null
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName,  null, null,
			attributeContent, entityRequirement, null
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
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
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, null, null,
			null, null, groupEntityRequirement
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable AttributeContent attributeContent, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, null, null,
			attributeContent, null, groupEntityRequirement
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		if (referenceName == null) {
			return new ReferenceContent(entityRequirement, groupEntityRequirement);
		}
		return new ReferenceContent(referenceName, null, null, entityRequirement, groupEntityRequirement);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, null, null,
			entityRequirement, groupEntityRequirement
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName, @Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, null, null,
			attributeContent, entityRequirement, groupEntityRequirement
		);
	}


	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
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
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
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
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
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
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable FilterBy filterBy) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, null, null, null);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable FilterBy filterBy) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, null, null, null, null);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable AttributeContent attributeContent) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, null, attributeContent, null, null);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable EntityFetch entityRequirement) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, null, entityRequirement, null);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable EntityFetch entityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, filterBy, null,
			null, entityRequirement, null
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, filterBy, null,
			attributeContent, entityRequirement, null
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, null, null, groupEntityRequirement);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, filterBy, null,
			null, null, groupEntityRequirement
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable AttributeContent attributeContent, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, filterBy, null,
			attributeContent, null, groupEntityRequirement
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, null, entityRequirement, groupEntityRequirement);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, filterBy, null,
			null, entityRequirement, groupEntityRequirement
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, filterBy, null,
			attributeContent, entityRequirement, groupEntityRequirement
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable OrderBy orderBy) {
		return referenceName == null ? null : new ReferenceContent(referenceName, null, orderBy, null, null);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable OrderBy orderBy) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, null, orderBy,
			null, null, null
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable OrderBy orderBy, @Nullable AttributeContent attributeContent) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, null, orderBy,
			attributeContent, null, null
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement) {
		return referenceName == null ? null : new ReferenceContent(referenceName, null, orderBy, entityRequirement, null);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, null, orderBy,
			null, entityRequirement, null
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable OrderBy orderBy, @Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, null, orderBy,
			attributeContent, entityRequirement, null
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable OrderBy orderBy, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(referenceName, null, orderBy, null, groupEntityRequirement);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable OrderBy orderBy, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, null, orderBy,
			null, null, groupEntityRequirement
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable OrderBy orderBy, @Nullable AttributeContent attributeContent, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, null, orderBy,
			attributeContent, null, groupEntityRequirement
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(referenceName, null, orderBy, entityRequirement, groupEntityRequirement);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, null, orderBy,
			null, entityRequirement, groupEntityRequirement
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable OrderBy orderBy, @Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, null, orderBy,
			attributeContent, entityRequirement, groupEntityRequirement
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, orderBy, null, null);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, filterBy, orderBy,
			null, null, null
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable AttributeContent attributeContent) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, filterBy, orderBy,
			attributeContent, null, null
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, orderBy, entityRequirement, null);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, filterBy, orderBy,
			null, entityRequirement, null
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, filterBy, orderBy,
			attributeContent, entityRequirement, null
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, orderBy, null, groupEntityRequirement);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, filterBy, orderBy,
			null, null, groupEntityRequirement
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable AttributeContent attributeContent, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, filterBy, orderBy,
			attributeContent, null, groupEntityRequirement
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, orderBy, entityRequirement, groupEntityRequirement);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, filterBy, orderBy,
			null, entityRequirement, groupEntityRequirement
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, filterBy, orderBy,
			attributeContent, entityRequirement, groupEntityRequirement
		);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(entityRequirement, null);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(@Nullable EntityFetch entityRequirement) {
		return new ReferenceContent((AttributeContent) null, entityRequirement, null);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(@Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(attributeContent, entityRequirement, null);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(null, groupEntityRequirement);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(@Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent((AttributeContent) null, null, groupEntityRequirement);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(@Nullable AttributeContent attributeContent, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(attributeContent, null, groupEntityRequirement);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(entityRequirement, groupEntityRequirement);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(@Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent((AttributeContent) null, entityRequirement, groupEntityRequirement);
	}

	/**
	 * The `referenceContent` requirement allows you to access the information about the references the entity has towards
	 * other entities (either managed by evitaDB itself or by any other external system). This variant of referenceContent
	 * doesn't return the attributes set on the reference itself - if you need those attributes, use the
	 * `referenceContentWithAttributes` variant of it.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    attributeContent("code"),
	 *    referenceContent("brand"),
	 *    referenceContent("categories")
	 * )
	 * </pre>
	 * 
	 * ## Referenced entity (group) fetching
	 * 
	 * In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies
	 * and the bodies of the groups the references refer to. One such common scenario is fetching the parameters of
	 * a product:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering references
	 * 
	 * Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case,
	 * you can use the filter constraint to filter out the references you don't need.
	 * 
	 * The referenceContent filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the {@link EntityHaving} container constraint.
	 * 
	 * For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that
	 * are part of group which contains an attribute `isVisibleInDetail` set to TRUE.To fetch only those parameters,
	 * use the following query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(
	 *         entityHaving(
	 *             referenceHaving(
	 *                 "parameter",
	 *                 entityHaving(
	 *                     attributeEquals("isVisibleInDetail", true)
	 *                 )
	 *             )
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("code")
	 *     ),
	 *     entityGroupFetch(
	 *         attributeContent("code", "isVisibleInDetail")
	 *     )
	 * )
	 * </pre>
	 * 
	 * ##Ordering references
	 * 
	 * By default, the references are ordered by the primary key of the referenced entity. If you want to order
	 * the references by a different property - either the attribute set on the reference itself or the property of the
	 * referenced entity - you can use the order constraint inside the referenceContent requirement.
	 * 
	 * The `referenceContent` filter implicitly targets the attributes on the same reference it points to, so you don't need
	 * to specify a referenceHaving constraint. However, if you need to declare constraints on referenced entity attributes,
	 * you must wrap them in the entityHaving container constraint.
	 * 
	 * Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
	 * query:
	 * 
	 * <pre>
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("name", ASC)
	 *         )
	 *     ),
	 *     entityFetch(
	 *         attributeContent("name")
	 *     )
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#reference-content">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(@Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(attributeContent, entityRequirement, groupEntityRequirement);
	}

	/**
	 * The `hierarchyContent` requirement allows you to access the information about the hierarchical placement of
	 * the entity.
	 * 
	 * If no additional constraints are specified, entity will contain a full chain of parent primary keys up to the root
	 * of a hierarchy tree. You can limit the size of the chain by using a stopAt constraint - for example, if you're only
	 * interested in a direct parent of each entity returned, you can use a stopAt(distance(1)) constraint. The result is
	 * similar to using a parents constraint, but is limited in that it doesn't provide information about statistics and
	 * the ability to list siblings of the entity parents. On the other hand, it's easier to use - since the hierarchy
	 * placement is directly available in the retrieved entity object.
	 * 
	 * If you provide a nested entityFetch constraint, the hierarchy information will contain the bodies of the parent
	 * entities in the required width. The attributeContent inside the entityFetch allows you to access the attributes
	 * of the parent entities, etc.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    hierarchyContent()
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#hierarchy-content">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static HierarchyContent hierarchyContent() {
		return new HierarchyContent();
	}

	/**
	 * The `hierarchyContent` requirement allows you to access the information about the hierarchical placement of
	 * the entity.
	 * 
	 * If no additional constraints are specified, entity will contain a full chain of parent primary keys up to the root
	 * of a hierarchy tree. You can limit the size of the chain by using a stopAt constraint - for example, if you're only
	 * interested in a direct parent of each entity returned, you can use a stopAt(distance(1)) constraint. The result is
	 * similar to using a parents constraint, but is limited in that it doesn't provide information about statistics and
	 * the ability to list siblings of the entity parents. On the other hand, it's easier to use - since the hierarchy
	 * placement is directly available in the retrieved entity object.
	 * 
	 * If you provide a nested entityFetch constraint, the hierarchy information will contain the bodies of the parent
	 * entities in the required width. The attributeContent inside the entityFetch allows you to access the attributes
	 * of the parent entities, etc.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    hierarchyContent()
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#hierarchy-content">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static HierarchyContent hierarchyContent(@Nullable HierarchyStopAt stopAt) {
		return stopAt == null ? new HierarchyContent() : new HierarchyContent(stopAt);
	}

	/**
	 * The `hierarchyContent` requirement allows you to access the information about the hierarchical placement of
	 * the entity.
	 * 
	 * If no additional constraints are specified, entity will contain a full chain of parent primary keys up to the root
	 * of a hierarchy tree. You can limit the size of the chain by using a stopAt constraint - for example, if you're only
	 * interested in a direct parent of each entity returned, you can use a stopAt(distance(1)) constraint. The result is
	 * similar to using a parents constraint, but is limited in that it doesn't provide information about statistics and
	 * the ability to list siblings of the entity parents. On the other hand, it's easier to use - since the hierarchy
	 * placement is directly available in the retrieved entity object.
	 * 
	 * If you provide a nested entityFetch constraint, the hierarchy information will contain the bodies of the parent
	 * entities in the required width. The attributeContent inside the entityFetch allows you to access the attributes
	 * of the parent entities, etc.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    hierarchyContent()
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#hierarchy-content">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static HierarchyContent hierarchyContent(@Nullable EntityFetch entityFetch) {
		return entityFetch == null ? new HierarchyContent() : new HierarchyContent(entityFetch);
	}

	/**
	 * The `hierarchyContent` requirement allows you to access the information about the hierarchical placement of
	 * the entity.
	 * 
	 * If no additional constraints are specified, entity will contain a full chain of parent primary keys up to the root
	 * of a hierarchy tree. You can limit the size of the chain by using a stopAt constraint - for example, if you're only
	 * interested in a direct parent of each entity returned, you can use a stopAt(distance(1)) constraint. The result is
	 * similar to using a parents constraint, but is limited in that it doesn't provide information about statistics and
	 * the ability to list siblings of the entity parents. On the other hand, it's easier to use - since the hierarchy
	 * placement is directly available in the retrieved entity object.
	 * 
	 * If you provide a nested entityFetch constraint, the hierarchy information will contain the bodies of the parent
	 * entities in the required width. The attributeContent inside the entityFetch allows you to access the attributes
	 * of the parent entities, etc.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * entityFetch(
	 *    hierarchyContent()
	 * )
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#hierarchy-content">Visit detailed user documentation</a></p>
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
	 * The `priceContent` requirement allows you to access the information about the prices of the entity.
	 * 
	 * If the {@link PriceContentMode#RESPECTING_FILTER} mode is used, the `priceContent` requirement will only retrieve
	 * the prices selected by the {@link PriceInPriceLists} constraint. If the enum {@link PriceContentMode#NONE} is
	 * specified, no prices are returned at all, if the enum {@link PriceContentMode#ALL} is specified, all prices of
	 * the entity are returned regardless of the priceInPriceLists constraint in the filter (the constraint still controls
	 * whether the entity is returned at all).
	 * 
	 * You can also add additional price lists to the list of price lists passed in the priceInPriceLists constraint by
	 * specifying the price list names as string arguments to the `priceContent` requirement. This is useful if you want to
	 * fetch non-indexed prices of the entity that cannot (and are not intended to) be used to filter the entities, but you
	 * still want to fetch them to display in the UI for the user.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * priceContentRespectingFilter()
	 * priceContentRespectingFilter("reference")
	 * priceContentAll()
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#price-content">Visit detailed user documentation</a></p>
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
	 * The `priceContent` requirement allows you to access the information about the prices of the entity.
	 * 
	 * If the {@link PriceContentMode#RESPECTING_FILTER} mode is used, the `priceContent` requirement will only retrieve
	 * the prices selected by the {@link PriceInPriceLists} constraint. If the enum {@link PriceContentMode#NONE} is
	 * specified, no prices are returned at all, if the enum {@link PriceContentMode#ALL} is specified, all prices of
	 * the entity are returned regardless of the priceInPriceLists constraint in the filter (the constraint still controls
	 * whether the entity is returned at all).
	 * 
	 * You can also add additional price lists to the list of price lists passed in the priceInPriceLists constraint by
	 * specifying the price list names as string arguments to the `priceContent` requirement. This is useful if you want to
	 * fetch non-indexed prices of the entity that cannot (and are not intended to) be used to filter the entities, but you
	 * still want to fetch them to display in the UI for the user.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * priceContentRespectingFilter()
	 * priceContentRespectingFilter("reference")
	 * priceContentAll()
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#price-content">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static PriceContent priceContentAll() {
		return PriceContent.all();
	}

	/**
	 * The `priceContent` requirement allows you to access the information about the prices of the entity.
	 * 
	 * If the {@link PriceContentMode#RESPECTING_FILTER} mode is used, the `priceContent` requirement will only retrieve
	 * the prices selected by the {@link PriceInPriceLists} constraint. If the enum {@link PriceContentMode#NONE} is
	 * specified, no prices are returned at all, if the enum {@link PriceContentMode#ALL} is specified, all prices of
	 * the entity are returned regardless of the priceInPriceLists constraint in the filter (the constraint still controls
	 * whether the entity is returned at all).
	 * 
	 * You can also add additional price lists to the list of price lists passed in the priceInPriceLists constraint by
	 * specifying the price list names as string arguments to the `priceContent` requirement. This is useful if you want to
	 * fetch non-indexed prices of the entity that cannot (and are not intended to) be used to filter the entities, but you
	 * still want to fetch them to display in the UI for the user.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * priceContentRespectingFilter()
	 * priceContentRespectingFilter("reference")
	 * priceContentAll()
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#price-content">Visit detailed user documentation</a></p>
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
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/price#price-type">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static PriceType priceType(@Nullable QueryPriceMode priceMode) {
		return new PriceType(priceMode == null ? QueryPriceMode.WITH_TAX : priceMode);
	}

	/**
	 * The `page` requirement controls the number and slice of entities returned in the query response. If no page
	 * requirement is used in the query, the default page 1 with the default page size 20 is used. If the requested page
	 * exceeds the number of available pages, a result with the first page is returned. An empty result is only returned if
	 * the query returns no result at all or the page size is set to zero. By automatically returning the first page result
	 * when the requested page is exceeded, we try to avoid the need to issue a secondary request to fetch the data.
	 * 
	 * The information about the actual returned page and data statistics can be found in the query response, which is
	 * wrapped in a so-called data chunk object. In case of the page constraint, the {@link PaginatedList} is used as data
	 * chunk object.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * page(1, 24)
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/paging#page">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static Page page(@Nullable Integer pageNumber, @Nullable Integer pageSize) {
		return new Page(pageNumber, pageSize);
	}

	/**
	 * The `strip` requirement controls the number and slice of entities returned in the query response. If the requested
	 * strip exceeds the number of available records, a result from the zero offset with retained limit is returned.
	 * An empty result is only returned if the query returns no result at all or the limit is set to zero. By automatically
	 * returning the first strip result when the requested page is exceeded, we try to avoid the need to issue a secondary
	 * request to fetch the data.
	 * 
	 * The information about the actual returned page and data statistics can be found in the query response, which is
	 * wrapped in a so-called data chunk object. In case of the strip constraint, the {@link StripList} is used as data
	 * chunk object.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * strip(52, 24)
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/paging#strip">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static Strip strip(@Nullable Integer offset, @Nullable Integer limit) {
		return new Strip(offset, limit);
	}

	/**
	 * The `facetSummary` request triggers the calculation of the FacetSummary containing the facet summary calculation.
	 * The calculated facet summary will contain all entity references marked as faceted in the entity schema. The facet
	 * summary can be further modified by the facet summary of reference constraint, which allows you to override
	 * the general facet summary behavior specified in the generic facet summary require constraint.
	 * 
	 * The faceted property affects the size of the indexes kept in memory and the scale / complexity of the general facet
	 * summary (i.e. the summary generated by the facetSummary request). It is recommended to mark only the references used
	 * for faceted filtering as faceted to keep the indexes small and the calculation of the facet summary in the user
	 * interface fast and simple. The combinatorial complexity of the facet summary is quite high for large datasets, and
	 * you may be forced to optimize it by narrowing the summary using the filtering facility or selecting only a few
	 * references for the summary.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * The `facetSummary` requirement triggers the calculation of the {@link FacetSummary} extra result. The facet summary
	 * is always computed as a side result of the main entity query and respects any filtering constraints placed on the
	 * queried entities.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static FacetSummary facetSummary() {
		return new FacetSummary();
	}

	/**
	 * The `facetSummary` request triggers the calculation of the FacetSummary containing the facet summary calculation.
	 * The calculated facet summary will contain all entity references marked as faceted in the entity schema. The facet
	 * summary can be further modified by the facet summary of reference constraint, which allows you to override
	 * the general facet summary behavior specified in the generic facet summary require constraint.
	 * 
	 * The faceted property affects the size of the indexes kept in memory and the scale / complexity of the general facet
	 * summary (i.e. the summary generated by the facetSummary request). It is recommended to mark only the references used
	 * for faceted filtering as faceted to keep the indexes small and the calculation of the facet summary in the user
	 * interface fast and simple. The combinatorial complexity of the facet summary is quite high for large datasets, and
	 * you may be forced to optimize it by narrowing the summary using the filtering facility or selecting only a few
	 * references for the summary.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * The `facetSummary` requirement triggers the calculation of the {@link FacetSummary} extra result. The facet summary
	 * is always computed as a side result of the main entity query and respects any filtering constraints placed on the
	 * queried entities.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static FacetSummary facetSummary(@Nullable FacetStatisticsDepth statisticsDepth, @Nullable EntityRequire... requirements) {
		return statisticsDepth == null ?
			new FacetSummary(FacetStatisticsDepth.COUNTS, requirements) :
			new FacetSummary(statisticsDepth, requirements);
	}

	/**
	 * The `facetSummary` request triggers the calculation of the FacetSummary containing the facet summary calculation.
	 * The calculated facet summary will contain all entity references marked as faceted in the entity schema. The facet
	 * summary can be further modified by the facet summary of reference constraint, which allows you to override
	 * the general facet summary behavior specified in the generic facet summary require constraint.
	 * 
	 * The faceted property affects the size of the indexes kept in memory and the scale / complexity of the general facet
	 * summary (i.e. the summary generated by the facetSummary request). It is recommended to mark only the references used
	 * for faceted filtering as faceted to keep the indexes small and the calculation of the facet summary in the user
	 * interface fast and simple. The combinatorial complexity of the facet summary is quite high for large datasets, and
	 * you may be forced to optimize it by narrowing the summary using the filtering facility or selecting only a few
	 * references for the summary.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * The `facetSummary` requirement triggers the calculation of the {@link FacetSummary} extra result. The facet summary
	 * is always computed as a side result of the main entity query and respects any filtering constraints placed on the
	 * queried entities.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary">Visit detailed user documentation</a></p>
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

	/**
	 * The `facetSummary` request triggers the calculation of the FacetSummary containing the facet summary calculation.
	 * The calculated facet summary will contain all entity references marked as faceted in the entity schema. The facet
	 * summary can be further modified by the facet summary of reference constraint, which allows you to override
	 * the general facet summary behavior specified in the generic facet summary require constraint.
	 * 
	 * The faceted property affects the size of the indexes kept in memory and the scale / complexity of the general facet
	 * summary (i.e. the summary generated by the facetSummary request). It is recommended to mark only the references used
	 * for faceted filtering as faceted to keep the indexes small and the calculation of the facet summary in the user
	 * interface fast and simple. The combinatorial complexity of the facet summary is quite high for large datasets, and
	 * you may be forced to optimize it by narrowing the summary using the filtering facility or selecting only a few
	 * references for the summary.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * The `facetSummary` requirement triggers the calculation of the {@link FacetSummary} extra result. The facet summary
	 * is always computed as a side result of the main entity query and respects any filtering constraints placed on the
	 * queried entities.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityRequire... requirements
	) {
		return facetSummary(statisticsDepth, filterBy, facetGroupFilterBy, null, facetGroupOrderBy, requirements);
	}

	/**
	 * The `facetSummary` request triggers the calculation of the FacetSummary containing the facet summary calculation.
	 * The calculated facet summary will contain all entity references marked as faceted in the entity schema. The facet
	 * summary can be further modified by the facet summary of reference constraint, which allows you to override
	 * the general facet summary behavior specified in the generic facet summary require constraint.
	 * 
	 * The faceted property affects the size of the indexes kept in memory and the scale / complexity of the general facet
	 * summary (i.e. the summary generated by the facetSummary request). It is recommended to mark only the references used
	 * for faceted filtering as faceted to keep the indexes small and the calculation of the facet summary in the user
	 * interface fast and simple. The combinatorial complexity of the facet summary is quite high for large datasets, and
	 * you may be forced to optimize it by narrowing the summary using the filtering facility or selecting only a few
	 * references for the summary.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * The `facetSummary` requirement triggers the calculation of the {@link FacetSummary} extra result. The facet summary
	 * is always computed as a side result of the main entity query and respects any filtering constraints placed on the
	 * queried entities.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityRequire... requirements
	) {
		return facetSummary(statisticsDepth, null, facetGroupFilterBy, orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * The `facetSummary` request triggers the calculation of the FacetSummary containing the facet summary calculation.
	 * The calculated facet summary will contain all entity references marked as faceted in the entity schema. The facet
	 * summary can be further modified by the facet summary of reference constraint, which allows you to override
	 * the general facet summary behavior specified in the generic facet summary require constraint.
	 * 
	 * The faceted property affects the size of the indexes kept in memory and the scale / complexity of the general facet
	 * summary (i.e. the summary generated by the facetSummary request). It is recommended to mark only the references used
	 * for faceted filtering as faceted to keep the indexes small and the calculation of the facet summary in the user
	 * interface fast and simple. The combinatorial complexity of the facet summary is quite high for large datasets, and
	 * you may be forced to optimize it by narrowing the summary using the filtering facility or selecting only a few
	 * references for the summary.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * The `facetSummary` requirement triggers the calculation of the {@link FacetSummary} extra result. The facet summary
	 * is always computed as a side result of the main entity query and respects any filtering constraints placed on the
	 * queried entities.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityRequire... requirements
	) {
		return facetSummary(statisticsDepth, filterBy, null, orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * The `facetSummary` request triggers the calculation of the FacetSummary containing the facet summary calculation.
	 * The calculated facet summary will contain all entity references marked as faceted in the entity schema. The facet
	 * summary can be further modified by the facet summary of reference constraint, which allows you to override
	 * the general facet summary behavior specified in the generic facet summary require constraint.
	 * 
	 * The faceted property affects the size of the indexes kept in memory and the scale / complexity of the general facet
	 * summary (i.e. the summary generated by the facetSummary request). It is recommended to mark only the references used
	 * for faceted filtering as faceted to keep the indexes small and the calculation of the facet summary in the user
	 * interface fast and simple. The combinatorial complexity of the facet summary is quite high for large datasets, and
	 * you may be forced to optimize it by narrowing the summary using the filtering facility or selecting only a few
	 * references for the summary.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * The `facetSummary` requirement triggers the calculation of the {@link FacetSummary} extra result. The facet summary
	 * is always computed as a side result of the main entity query and respects any filtering constraints placed on the
	 * queried entities.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityRequire... requirements
	) {
		return facetSummary(statisticsDepth, filterBy, facetGroupFilterBy, orderBy, null, requirements);
	}

	/**
	 * The `facetSummary` request triggers the calculation of the FacetSummary containing the facet summary calculation.
	 * The calculated facet summary will contain all entity references marked as faceted in the entity schema. The facet
	 * summary can be further modified by the facet summary of reference constraint, which allows you to override
	 * the general facet summary behavior specified in the generic facet summary require constraint.
	 * 
	 * The faceted property affects the size of the indexes kept in memory and the scale / complexity of the general facet
	 * summary (i.e. the summary generated by the facetSummary request). It is recommended to mark only the references used
	 * for faceted filtering as faceted to keep the indexes small and the calculation of the facet summary in the user
	 * interface fast and simple. The combinatorial complexity of the facet summary is quite high for large datasets, and
	 * you may be forced to optimize it by narrowing the summary using the filtering facility or selecting only a few
	 * references for the summary.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * The `facetSummary` requirement triggers the calculation of the {@link FacetSummary} extra result. The facet summary
	 * is always computed as a side result of the main entity query and respects any filtering constraints placed on the
	 * queried entities.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityRequire... requirements
	) {
		return facetSummary(statisticsDepth, filterBy, facetGroupFilterBy, null, null, requirements);
	}

	/**
	 * The `facetSummary` request triggers the calculation of the FacetSummary containing the facet summary calculation.
	 * The calculated facet summary will contain all entity references marked as faceted in the entity schema. The facet
	 * summary can be further modified by the facet summary of reference constraint, which allows you to override
	 * the general facet summary behavior specified in the generic facet summary require constraint.
	 * 
	 * The faceted property affects the size of the indexes kept in memory and the scale / complexity of the general facet
	 * summary (i.e. the summary generated by the facetSummary request). It is recommended to mark only the references used
	 * for faceted filtering as faceted to keep the indexes small and the calculation of the facet summary in the user
	 * interface fast and simple. The combinatorial complexity of the facet summary is quite high for large datasets, and
	 * you may be forced to optimize it by narrowing the summary using the filtering facility or selecting only a few
	 * references for the summary.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * The `facetSummary` requirement triggers the calculation of the {@link FacetSummary} extra result. The facet summary
	 * is always computed as a side result of the main entity query and respects any filtering constraints placed on the
	 * queried entities.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityRequire... requirements
	) {
		return facetSummary(statisticsDepth, null, null, orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * The `facetSummary` request triggers the calculation of the FacetSummary containing the facet summary calculation.
	 * The calculated facet summary will contain all entity references marked as faceted in the entity schema. The facet
	 * summary can be further modified by the facet summary of reference constraint, which allows you to override
	 * the general facet summary behavior specified in the generic facet summary require constraint.
	 * 
	 * The faceted property affects the size of the indexes kept in memory and the scale / complexity of the general facet
	 * summary (i.e. the summary generated by the facetSummary request). It is recommended to mark only the references used
	 * for faceted filtering as faceted to keep the indexes small and the calculation of the facet summary in the user
	 * interface fast and simple. The combinatorial complexity of the facet summary is quite high for large datasets, and
	 * you may be forced to optimize it by narrowing the summary using the filtering facility or selecting only a few
	 * references for the summary.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * The `facetSummary` requirement triggers the calculation of the {@link FacetSummary} extra result. The facet summary
	 * is always computed as a side result of the main entity query and respects any filtering constraints placed on the
	 * queried entities.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable EntityRequire... requirements
	) {
		return facetSummary(statisticsDepth, filterBy, null, null, null, requirements);
	}

	/**
	 * The `facetSummary` request triggers the calculation of the FacetSummary containing the facet summary calculation.
	 * The calculated facet summary will contain all entity references marked as faceted in the entity schema. The facet
	 * summary can be further modified by the facet summary of reference constraint, which allows you to override
	 * the general facet summary behavior specified in the generic facet summary require constraint.
	 * 
	 * The faceted property affects the size of the indexes kept in memory and the scale / complexity of the general facet
	 * summary (i.e. the summary generated by the facetSummary request). It is recommended to mark only the references used
	 * for faceted filtering as faceted to keep the indexes small and the calculation of the facet summary in the user
	 * interface fast and simple. The combinatorial complexity of the facet summary is quite high for large datasets, and
	 * you may be forced to optimize it by narrowing the summary using the filtering facility or selecting only a few
	 * references for the summary.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * The `facetSummary` requirement triggers the calculation of the {@link FacetSummary} extra result. The facet summary
	 * is always computed as a side result of the main entity query and respects any filtering constraints placed on the
	 * queried entities.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable OrderBy orderBy,
		@Nullable EntityRequire... requirements
	) {
		return facetSummary(statisticsDepth, null, null, orderBy, null, requirements);
	}

	/**
	 * The `facetSummary` request triggers the calculation of the FacetSummary containing the facet summary calculation.
	 * The calculated facet summary will contain all entity references marked as faceted in the entity schema. The facet
	 * summary can be further modified by the facet summary of reference constraint, which allows you to override
	 * the general facet summary behavior specified in the generic facet summary require constraint.
	 * 
	 * The faceted property affects the size of the indexes kept in memory and the scale / complexity of the general facet
	 * summary (i.e. the summary generated by the facetSummary request). It is recommended to mark only the references used
	 * for faceted filtering as faceted to keep the indexes small and the calculation of the facet summary in the user
	 * interface fast and simple. The combinatorial complexity of the facet summary is quite high for large datasets, and
	 * you may be forced to optimize it by narrowing the summary using the filtering facility or selecting only a few
	 * references for the summary.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * The `facetSummary` requirement triggers the calculation of the {@link FacetSummary} extra result. The facet summary
	 * is always computed as a side result of the main entity query and respects any filtering constraints placed on the
	 * queried entities.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityRequire... requirements
	) {
		return facetSummary(statisticsDepth, null, facetGroupFilterBy, null, null, requirements);
	}

	/**
	 * The `facetSummary` request triggers the calculation of the FacetSummary containing the facet summary calculation.
	 * The calculated facet summary will contain all entity references marked as faceted in the entity schema. The facet
	 * summary can be further modified by the facet summary of reference constraint, which allows you to override
	 * the general facet summary behavior specified in the generic facet summary require constraint.
	 * 
	 * The faceted property affects the size of the indexes kept in memory and the scale / complexity of the general facet
	 * summary (i.e. the summary generated by the facetSummary request). It is recommended to mark only the references used
	 * for faceted filtering as faceted to keep the indexes small and the calculation of the facet summary in the user
	 * interface fast and simple. The combinatorial complexity of the facet summary is quite high for large datasets, and
	 * you may be forced to optimize it by narrowing the summary using the filtering facility or selecting only a few
	 * references for the summary.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * The `facetSummary` requirement triggers the calculation of the {@link FacetSummary} extra result. The facet summary
	 * is always computed as a side result of the main entity query and respects any filtering constraints placed on the
	 * queried entities.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityRequire... requirements
	) {
		return facetSummary(statisticsDepth, null, null, null, facetGroupOrderBy, requirements);
	}

	/**
	 * The `facetSummary` request triggers the calculation of the FacetSummary containing the facet summary calculation.
	 * The calculated facet summary will contain all entity references marked as faceted in the entity schema. The facet
	 * summary can be further modified by the facet summary of reference constraint, which allows you to override
	 * the general facet summary behavior specified in the generic facet summary require constraint.
	 * 
	 * The faceted property affects the size of the indexes kept in memory and the scale / complexity of the general facet
	 * summary (i.e. the summary generated by the facetSummary request). It is recommended to mark only the references used
	 * for faceted filtering as faceted to keep the indexes small and the calculation of the facet summary in the user
	 * interface fast and simple. The combinatorial complexity of the facet summary is quite high for large datasets, and
	 * you may be forced to optimize it by narrowing the summary using the filtering facility or selecting only a few
	 * references for the summary.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * The `facetSummary` requirement triggers the calculation of the {@link FacetSummary} extra result. The facet summary
	 * is always computed as a side result of the main entity query and respects any filtering constraints placed on the
	 * queried entities.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityRequire... requirements
	) {
		return facetSummary(statisticsDepth, null, facetGroupFilterBy, orderBy, null, requirements);
	}

	/**
	 * The `facetSummary` request triggers the calculation of the FacetSummary containing the facet summary calculation.
	 * The calculated facet summary will contain all entity references marked as faceted in the entity schema. The facet
	 * summary can be further modified by the facet summary of reference constraint, which allows you to override
	 * the general facet summary behavior specified in the generic facet summary require constraint.
	 * 
	 * The faceted property affects the size of the indexes kept in memory and the scale / complexity of the general facet
	 * summary (i.e. the summary generated by the facetSummary request). It is recommended to mark only the references used
	 * for faceted filtering as faceted to keep the indexes small and the calculation of the facet summary in the user
	 * interface fast and simple. The combinatorial complexity of the facet summary is quite high for large datasets, and
	 * you may be forced to optimize it by narrowing the summary using the filtering facility or selecting only a few
	 * references for the summary.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * The `facetSummary` requirement triggers the calculation of the {@link FacetSummary} extra result. The facet summary
	 * is always computed as a side result of the main entity query and respects any filtering constraints placed on the
	 * queried entities.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityRequire... requirements
	) {
		return facetSummary(statisticsDepth, filterBy, null, null, facetGroupOrderBy, requirements);
	}

	/**
	 * The `facetSummary` request triggers the calculation of the FacetSummary containing the facet summary calculation.
	 * The calculated facet summary will contain all entity references marked as faceted in the entity schema. The facet
	 * summary can be further modified by the facet summary of reference constraint, which allows you to override
	 * the general facet summary behavior specified in the generic facet summary require constraint.
	 * 
	 * The faceted property affects the size of the indexes kept in memory and the scale / complexity of the general facet
	 * summary (i.e. the summary generated by the facetSummary request). It is recommended to mark only the references used
	 * for faceted filtering as faceted to keep the indexes small and the calculation of the facet summary in the user
	 * interface fast and simple. The combinatorial complexity of the facet summary is quite high for large datasets, and
	 * you may be forced to optimize it by narrowing the summary using the filtering facility or selecting only a few
	 * references for the summary.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * The `facetSummary` requirement triggers the calculation of the {@link FacetSummary} extra result. The facet summary
	 * is always computed as a side result of the main entity query and respects any filtering constraints placed on the
	 * queried entities.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary">Visit detailed user documentation</a></p>
	*/
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
	 * The `facetSummary` request triggers the calculation of the FacetSummary containing the facet summary calculation.
	 * The calculated facet summary will contain all entity references marked as faceted in the entity schema. The facet
	 * summary can be further modified by the facet summary of reference constraint, which allows you to override
	 * the general facet summary behavior specified in the generic facet summary require constraint.
	 * 
	 * The faceted property affects the size of the indexes kept in memory and the scale / complexity of the general facet
	 * summary (i.e. the summary generated by the facetSummary request). It is recommended to mark only the references used
	 * for faceted filtering as faceted to keep the indexes small and the calculation of the facet summary in the user
	 * interface fast and simple. The combinatorial complexity of the facet summary is quite high for large datasets, and
	 * you may be forced to optimize it by narrowing the summary using the filtering facility or selecting only a few
	 * references for the summary.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * The `facetSummary` requirement triggers the calculation of the {@link FacetSummary} extra result. The facet summary
	 * is always computed as a side result of the main entity query and respects any filtering constraints placed on the
	 * queried entities.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary">Visit detailed user documentation</a></p>
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
	 * The `facetSummaryOfReference` requirement triggers the calculation of the {@link FacetSummary} for a specific
	 * reference. When a generic {@link FacetSummary} requirement is specified, this require constraint overrides
	 * the default constraints from the generic requirement to constraints specific to this particular reference.
	 * By combining the generic facetSummary and facetSummaryOfReference, you define common requirements for the facet
	 * summary calculation, and redefine them only for references where they are insufficient.
	 * 
	 * The `facetSummaryOfReference` requirements redefine all constraints from the generic facetSummary requirement.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(@Nullable String referenceName, @Nullable EntityRequire... requirements) {
		return referenceName == null ? null : new FacetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, requirements);
	}

	/**
	 * The `facetSummaryOfReference` requirement triggers the calculation of the {@link FacetSummary} for a specific
	 * reference. When a generic {@link FacetSummary} requirement is specified, this require constraint overrides
	 * the default constraints from the generic requirement to constraints specific to this particular reference.
	 * By combining the generic facetSummary and facetSummaryOfReference, you define common requirements for the facet
	 * summary calculation, and redefine them only for references where they are insufficient.
	 * 
	 * The `facetSummaryOfReference` requirements redefine all constraints from the generic facetSummary requirement.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(@Nullable String referenceName, @Nullable FacetStatisticsDepth statisticsDepth, @Nullable EntityRequire... requirements) {
		if (referenceName == null) {
			return null;
		}
		return statisticsDepth == null ?
			new FacetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, requirements) :
			new FacetSummaryOfReference(referenceName, statisticsDepth, requirements);
	}

	/**
	 * The `facetSummaryOfReference` requirement triggers the calculation of the {@link FacetSummary} for a specific
	 * reference. When a generic {@link FacetSummary} requirement is specified, this require constraint overrides
	 * the default constraints from the generic requirement to constraints specific to this particular reference.
	 * By combining the generic facetSummary and facetSummaryOfReference, you define common requirements for the facet
	 * summary calculation, and redefine them only for references where they are insufficient.
	 * 
	 * The `facetSummaryOfReference` requirements redefine all constraints from the generic facetSummary requirement.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy facetFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable EntityRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, facetFilterBy, null, facetOrderBy, null, requirements);
	}

	/**
	 * The `facetSummaryOfReference` requirement triggers the calculation of the {@link FacetSummary} for a specific
	 * reference. When a generic {@link FacetSummary} requirement is specified, this require constraint overrides
	 * the default constraints from the generic requirement to constraints specific to this particular reference.
	 * By combining the generic facetSummary and facetSummaryOfReference, you define common requirements for the facet
	 * summary calculation, and redefine them only for references where they are insufficient.
	 * 
	 * The `facetSummaryOfReference` requirements redefine all constraints from the generic facetSummary requirement.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, filterBy, facetGroupFilterBy, null, facetGroupOrderBy, requirements);
	}

	/**
	 * The `facetSummaryOfReference` requirement triggers the calculation of the {@link FacetSummary} for a specific
	 * reference. When a generic {@link FacetSummary} requirement is specified, this require constraint overrides
	 * the default constraints from the generic requirement to constraints specific to this particular reference.
	 * By combining the generic facetSummary and facetSummaryOfReference, you define common requirements for the facet
	 * summary calculation, and redefine them only for references where they are insufficient.
	 * 
	 * The `facetSummaryOfReference` requirements redefine all constraints from the generic facetSummary requirement.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, null, facetGroupFilterBy, orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * The `facetSummaryOfReference` requirement triggers the calculation of the {@link FacetSummary} for a specific
	 * reference. When a generic {@link FacetSummary} requirement is specified, this require constraint overrides
	 * the default constraints from the generic requirement to constraints specific to this particular reference.
	 * By combining the generic facetSummary and facetSummaryOfReference, you define common requirements for the facet
	 * summary calculation, and redefine them only for references where they are insufficient.
	 * 
	 * The `facetSummaryOfReference` requirements redefine all constraints from the generic facetSummary requirement.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, filterBy, null, orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * The `facetSummaryOfReference` requirement triggers the calculation of the {@link FacetSummary} for a specific
	 * reference. When a generic {@link FacetSummary} requirement is specified, this require constraint overrides
	 * the default constraints from the generic requirement to constraints specific to this particular reference.
	 * By combining the generic facetSummary and facetSummaryOfReference, you define common requirements for the facet
	 * summary calculation, and redefine them only for references where they are insufficient.
	 * 
	 * The `facetSummaryOfReference` requirements redefine all constraints from the generic facetSummary requirement.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, filterBy, facetGroupFilterBy, orderBy, null, requirements);
	}

	/**
	 * The `facetSummaryOfReference` requirement triggers the calculation of the {@link FacetSummary} for a specific
	 * reference. When a generic {@link FacetSummary} requirement is specified, this require constraint overrides
	 * the default constraints from the generic requirement to constraints specific to this particular reference.
	 * By combining the generic facetSummary and facetSummaryOfReference, you define common requirements for the facet
	 * summary calculation, and redefine them only for references where they are insufficient.
	 * 
	 * The `facetSummaryOfReference` requirements redefine all constraints from the generic facetSummary requirement.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, filterBy, facetGroupFilterBy, null, null, requirements);
	}

	/**
	 * The `facetSummaryOfReference` requirement triggers the calculation of the {@link FacetSummary} for a specific
	 * reference. When a generic {@link FacetSummary} requirement is specified, this require constraint overrides
	 * the default constraints from the generic requirement to constraints specific to this particular reference.
	 * By combining the generic facetSummary and facetSummaryOfReference, you define common requirements for the facet
	 * summary calculation, and redefine them only for references where they are insufficient.
	 * 
	 * The `facetSummaryOfReference` requirements redefine all constraints from the generic facetSummary requirement.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, null, null, orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * The `facetSummaryOfReference` requirement triggers the calculation of the {@link FacetSummary} for a specific
	 * reference. When a generic {@link FacetSummary} requirement is specified, this require constraint overrides
	 * the default constraints from the generic requirement to constraints specific to this particular reference.
	 * By combining the generic facetSummary and facetSummaryOfReference, you define common requirements for the facet
	 * summary calculation, and redefine them only for references where they are insufficient.
	 * 
	 * The `facetSummaryOfReference` requirements redefine all constraints from the generic facetSummary requirement.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable EntityRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, filterBy, null, null, null, requirements);
	}

	/**
	 * The `facetSummaryOfReference` requirement triggers the calculation of the {@link FacetSummary} for a specific
	 * reference. When a generic {@link FacetSummary} requirement is specified, this require constraint overrides
	 * the default constraints from the generic requirement to constraints specific to this particular reference.
	 * By combining the generic facetSummary and facetSummaryOfReference, you define common requirements for the facet
	 * summary calculation, and redefine them only for references where they are insufficient.
	 * 
	 * The `facetSummaryOfReference` requirements redefine all constraints from the generic facetSummary requirement.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable OrderBy orderBy,
		@Nullable EntityRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, null, null, orderBy, null, requirements);
	}

	/**
	 * The `facetSummaryOfReference` requirement triggers the calculation of the {@link FacetSummary} for a specific
	 * reference. When a generic {@link FacetSummary} requirement is specified, this require constraint overrides
	 * the default constraints from the generic requirement to constraints specific to this particular reference.
	 * By combining the generic facetSummary and facetSummaryOfReference, you define common requirements for the facet
	 * summary calculation, and redefine them only for references where they are insufficient.
	 * 
	 * The `facetSummaryOfReference` requirements redefine all constraints from the generic facetSummary requirement.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, null, facetGroupFilterBy, null, null, requirements);
	}

	/**
	 * The `facetSummaryOfReference` requirement triggers the calculation of the {@link FacetSummary} for a specific
	 * reference. When a generic {@link FacetSummary} requirement is specified, this require constraint overrides
	 * the default constraints from the generic requirement to constraints specific to this particular reference.
	 * By combining the generic facetSummary and facetSummaryOfReference, you define common requirements for the facet
	 * summary calculation, and redefine them only for references where they are insufficient.
	 * 
	 * The `facetSummaryOfReference` requirements redefine all constraints from the generic facetSummary requirement.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, null, null, null, facetGroupOrderBy, requirements);
	}

	/**
	 * The `facetSummaryOfReference` requirement triggers the calculation of the {@link FacetSummary} for a specific
	 * reference. When a generic {@link FacetSummary} requirement is specified, this require constraint overrides
	 * the default constraints from the generic requirement to constraints specific to this particular reference.
	 * By combining the generic facetSummary and facetSummaryOfReference, you define common requirements for the facet
	 * summary calculation, and redefine them only for references where they are insufficient.
	 * 
	 * The `facetSummaryOfReference` requirements redefine all constraints from the generic facetSummary requirement.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, null, facetGroupFilterBy, orderBy, null, requirements);
	}

	/**
	 * The `facetSummaryOfReference` requirement triggers the calculation of the {@link FacetSummary} for a specific
	 * reference. When a generic {@link FacetSummary} requirement is specified, this require constraint overrides
	 * the default constraints from the generic requirement to constraints specific to this particular reference.
	 * By combining the generic facetSummary and facetSummaryOfReference, you define common requirements for the facet
	 * summary calculation, and redefine them only for references where they are insufficient.
	 * 
	 * The `facetSummaryOfReference` requirements redefine all constraints from the generic facetSummary requirement.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, filterBy, null, null, facetGroupOrderBy, requirements);
	}

	/**
	 * The `facetSummaryOfReference` requirement triggers the calculation of the {@link FacetSummary} for a specific
	 * reference. When a generic {@link FacetSummary} requirement is specified, this require constraint overrides
	 * the default constraints from the generic requirement to constraints specific to this particular reference.
	 * By combining the generic facetSummary and facetSummaryOfReference, you define common requirements for the facet
	 * summary calculation, and redefine them only for references where they are insufficient.
	 * 
	 * The `facetSummaryOfReference` requirements redefine all constraints from the generic facetSummary requirement.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, null, facetGroupFilterBy, null, facetGroupOrderBy, requirements);
	}

	/**
	 * The `facetSummaryOfReference` requirement triggers the calculation of the {@link FacetSummary} for a specific
	 * reference. When a generic {@link FacetSummary} requirement is specified, this require constraint overrides
	 * the default constraints from the generic requirement to constraints specific to this particular reference.
	 * By combining the generic facetSummary and facetSummaryOfReference, you define common requirements for the facet
	 * summary calculation, and redefine them only for references where they are insufficient.
	 * 
	 * The `facetSummaryOfReference` requirements redefine all constraints from the generic facetSummary requirement.
	 * 
	 * ## Facet calculation rules
	 * 
	 * 1. The facet summary is calculated only for entities that are returned in the current query result.
	 * 2. The calculation respects any filter constraints placed outside the 'userFilter' container.
	 * 3. The default relation between facets within a group is logical disjunction (logical OR).
	 * 4. The default relation between facets in different groups / references is a logical AND.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Product"),
	 *     filterBy(
	 *         hierarchyWithin(
	 *             "categories",
	 *             attributeEquals("code", "e-readers")
	 *         )
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         facetSummary(
	 *             COUNTS,
	 *             entityFetch(
	 *                 attributeContent("name")
	 *             ),
	 *             entityGroupFetch(
	 *                 attributeContent("name")
	 *             )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * ## Filtering facet summary
	 * 
	 * The facet summary sometimes gets very big, and besides the fact that it is not very useful to show all facet options
	 * in the user interface, it also takes a lot of time to calculate it. To limit the facet summary, you can use the
	 * {@link FilterBy} and {@link FilterGroupBy} (which is the same as filterBy, but it filters the entire facet group
	 * instead of individual facets) constraints.
	 * 
	 * If you add the filtering constraints to the facetSummary requirement, you can only refer to filterable properties
	 * that are shared by all referenced entities. This may not be feasible in some cases, and you will need to split
	 * the generic facetSummary requirement into multiple individual {@link FacetSummaryOfReference} requirements with
	 * specific filters for each reference type.
	 * 
	 * The filter conditions can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * ## Ordering facet summary
	 * 
	 * Typically, the facet summary is ordered in some way to present the most relevant facet options first. The same is
	 * true for ordering facet groups. To sort the facet summary items the way you like, you can use the {@link OrderBy} and
	 * {@link OrderGroupBy} (which is the same as orderBy but it sorts the facet groups instead of the individual facets)
	 * constraints.
	 * 
	 * If you add the ordering constraints to the facetSummary requirement, you can only refer to sortable properties that
	 * are shared by all referenced entities. This may not be feasible in some cases, and you will need to split the generic
	 * facetSummary requirement into multiple individual facetSummaryOfReference requirements with specific ordering
	 * constraints for each reference type.
	 * 
	 * The ordering constraints can only target properties on the target entity and cannot target reference attributes in
	 * the source entity that are specific to a relationship with the target entity.
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference">Visit detailed user documentation</a></p>
	*/
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy facetFilterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityRequire... requirements
	) {
		if (referenceName == null) {
			return null;
		}
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
	 * <pre>
	 * queryTelemetry()
	 * </pre>
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/debug#query-telemetry">Visit detailed user documentation</a></p>
	*/
	@Nonnull
	static QueryTelemetry queryTelemetry() {
		return new QueryTelemetry();
	}

	/**
	 * This `debug` require is targeted for internal purposes only and is not exposed in public evitaDB API.
	*/
	@Nullable
	static Debug debug(@Nullable DebugMode... debugMode) {
		return ArrayUtils.isEmpty(debugMode) ? null : new Debug(debugMode);
	}

	/**
	 * The `entityFetch` requirement is used to trigger loading one or more entity data containers from the disk by its
	 * primary key. This operation requires a disk access unless the entity is already loaded in the database cache
	 * (frequently fetched entities have higher chance to stay in the cache).
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Brand"),
	 *     filterBy(
	 *         entityPrimaryKeyInSet(64703),
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             attributeContent("code", "name")
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * See internal contents available for fetching in {@link EntityContentRequire}:
	 * 
	 * - {@link AttributeContent}
	 * - {@link AssociatedDataContent}
	 * - {@link PriceContent}
	 * - {@link HierarchyContent}
	 * - {@link ReferenceContent}
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#entity-fetch">Visit detailed user documentation</a></p>
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
	 * The `entityGroupFetch` requirement is similar to {@link EntityFetch} but is used to trigger loading one or more
	 * referenced group entities in the {@link ReferenceContent} parent.
	 * 
	 * Example:
	 * 
	 * <pre>
	 * query(
	 *     collection("Brand"),
	 *     filterBy(
	 *         entityPrimaryKeyInSet(64703),
	 *         entityLocaleEquals("en")
	 *     ),
	 *     require(
	 *         entityFetch(
	 *             referenceContent(
	 *                "parameterValues",
	 *                entityGroupFetch(
	 *                   attributeContent("code", "name")
	 *                )
	 *              )
	 *         )
	 *     )
	 * )
	 * </pre>
	 * 
	 * See internal contents available for fetching in {@link EntityContentRequire}:
	 * 
	 * - {@link AttributeContent}
	 * - {@link AssociatedDataContent}
	 * - {@link PriceContent}
	 * - {@link HierarchyContent}
	 * - {@link ReferenceContent}
	 * 
	 * <p><a href="https://evitadb.io/documentation/query/requirements/fetching#entity-group-fetch">Visit detailed user documentation</a></p>
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
	static RequireConstraint[] entityFetchAllAnd(@Nullable RequireConstraint... combineWith) {
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