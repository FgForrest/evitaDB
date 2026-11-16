/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.query;

import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.filter.*;
import io.evitadb.api.query.head.Collection;
import io.evitadb.api.query.head.Head;
import io.evitadb.api.query.head.Label;
import io.evitadb.api.query.order.*;
import io.evitadb.api.query.require.*;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

import static io.evitadb.api.query.ConstraintArguments.*;
import static java.util.Optional.ofNullable;

/**
 * Factory interface providing static factory methods for constructing evitaDB query constraints. This interface serves
 * as the primary API for building type-safe queries in Java, offering an ergonomic alternative to constructing
 * constraint objects directly.
 *
 * The factory methods in this interface mirror the structure and naming conventions of EvitaQL (evitaDB Query Language),
 * the string-based query language for evitaDB. This design allows developers to write queries in Java that closely
 * resemble their EvitaQL counterparts, improving readability and reducing the mental mapping between the two forms.
 *
 * **Design Rationale:**
 *
 * evitaDB queries are built from immutable constraint trees. While constraint objects can be instantiated directly
 * via constructors, this interface provides several advantages:
 *
 * - **Discoverability**: IDE auto-completion reveals all available constraints when importing static methods from
 *   this interface.
 * - **Readability**: Method names match EvitaQL syntax, making queries self-documenting.
 * - **Type Safety**: Factory methods enforce correct argument types at compile time.
 * - **Null Handling**: A null value omits its constraint unless the constraint gives null a meaning (an open bound,
 *   the default page). A null or blank name throws `EvitaInvalidUsageException`. A null enum selects its documented
 *   default and throws only where there is none (`inScope`, the special value of `attributeIs`, the mode of
 *   `priceContent`).
 * - **Consistency**: Provides a uniform API across all constraint types (head, filter, order, require).
 *
 * **Usage Pattern:**
 *
 * This interface is typically used via static imports in application code:
 *
 * ```java
 * import static io.evitadb.api.query.QueryConstraints.*;
 *
 * Query query = query(
 *     collection("Product"),
 *     filterBy(
 *         and(
 *             equals("visible", true),
 *             or(
 *                 equals("code", "PROD-123"),
 *                 startsWith("name", "Smart")
 *             ),
 *             priceBetween(100, 1000)
 *         )
 *     ),
 *     orderBy(
 *         descending("priority"),
 *         priceDescending()
 *     ),
 *     require(
 *         page(1, 20),
 *         entityFetch(
 *             attributeContent("code", "name", "description"),
 *             priceContentRespectingFilter()
 *         ),
 *         facetSummary()
 *     )
 * );
 * ```
 *
 * **Constraint Categories:**
 *
 * The factory methods are organized into four primary categories matching the structure of evitaDB queries:
 *
 * 1. **Head Constraints** (`head`, `collection`, `label`): Define the target entity collection and query metadata.
 * 2. **Filter Constraints** (`filterBy`, `and`, `or`, `not`, `equals`, `greaterThan`, etc.): Define which entities
 *    are included in results.
 * 3. **Order Constraints** (`orderBy`, `ascending`, `descending`, `priceAscending`, etc.): Define result ordering.
 * 4. **Require Constraints** (`require`, `entityFetch`, `page`, `facetSummary`, etc.): Define what data is fetched
 *    and what extra computations are performed.
 *
 * **Key Behavioral Contracts:**
 *
 * - **Immutability**: All constraint objects returned by these factory methods are immutable. Modifying a query
 *   requires constructing a new constraint tree.
 * - **Null Tolerance**: Nullable values allow conditional query building. Null items of a value list are ignored,
 *   and so are blank price-list names: a null filter value list omits the filter, one with only null items matches
 *   nothing, and elsewhere a null list means an empty one. A null or blank item of a name or enum list throws.
 *   A container that means nothing without children is omitted when all its children were omitted.
 * - **Validation**: Factory methods perform basic validation (e.g., non-null checks for mandatory parameters) but
 *   do not validate against schema — schema validation occurs at query execution time.
 * - **Varargs Support**: Container constraints typically accept varargs, allowing flexible composition without
 *   explicit array construction.
 *
 * **Thread Safety:**
 *
 * This interface is stateless and all factory methods are thread-safe. The constraint objects they produce are
 * immutable and can be safely shared across threads.
 *
 * **Relationship to EvitaQL:**
 *
 * EvitaQL is the string-based query language parsed via {@link QueryParser}. The factory methods in this interface
 * provide the programmatic equivalent. For example:
 *
 * - EvitaQL: `filterBy(equals('code', 'ABC'))`
 * - Java: `filterBy(equals("code", "ABC"))`
 *
 * Both produce identical constraint trees internally.
 *
 * @see Query
 * @see Constraint
 * @see FilterConstraint
 * @see OrderConstraint
 * @see RequireConstraint
 * @see HeadConstraint
 * @see QueryParser
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@SuppressWarnings({"DataFlowIssue", "ConstantValue"})
public interface QueryConstraints {

	/*
		HEADING
	 */

	/**
	 * Groups collection and label constraints in the query header, allowing you to specify both the target entity collection and custom query labels. Use `head()` when you need to add labels alongside the collection; otherwise, collection can be used directly.
	 *
	 * ```evitaql
	 * query(
	 *    head(
	 *       collection('Product'),
	 *       label('query-name', 'List all products')
	 *    ),
	 *    filterBy(
	 *       attributeEquals('visible', true)
	 *    )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/header/header#head)
	 *
	 * @see io.evitadb.api.query.head.Head
	 */
	@SourceHash("5e246a835ed310e6982b80879d707f84")
	@Nullable
	static Head head(@Nullable HeadConstraint... headConstraint) {
		return ArrayUtils.isEmptyOrItsValuesNull(headConstraint) ? null : new Head(headConstraint);
	}

	/**
	 * Specifies the target entity collection (type) for the query, making it mandatory unless the filter uses a globally unique attribute (e.g., entityPrimaryKeyInSet or unique attributeEquals), in which case evitaDB infers the collection automatically. Accepts a single collection name.
	 *
	 * ```evitaql
	 * query(
	 *    collection('Product'),
	 *    filterBy(
	 *       attributeEquals('code', 'garmin-fenix')
	 *    )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/header/header#collection)
	 *
	 * @see io.evitadb.api.query.head.Collection
	 */
	@SourceHash("5da5b90a195e8fe5b26664361476230f")
	@Nonnull
	static Collection collection(@Nonnull String entityType) {
		requireName(entityType, "entityType");
		return new Collection(entityType);
	}

	/**
	 * Attaches a custom key-value metadata label to the query, enabling traceability and context tagging throughout execution. Labels are included in diagnostics (like OpenTelemetry traces and traffic logs) to help identify query origin, business purpose, or user action.
	 *
	 * ```evitaql
	 * head(
	 *    label('query-name', 'List all products'),
	 *    label('page-url', '/products')
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/header/header#label)
	 *
	 * @see io.evitadb.api.query.head.Label
	 */
	@SourceHash("86a4e77ed865ec8b31ae4c70eaca6cc6")
	@Nullable
	static <T extends Comparable<T> & Serializable> Label label(@Nonnull String name, @Nullable T value) {
		requireName(name, "name");
		return value == null ? null : new Label(name, value);
	}

	/*
		FILTERING
	 */

	/**
	 * Defines the root filtering section of a query, analogous to SQL's `WHERE` clause. `filterBy` groups one or more filter constraints, combining them with logical AND by default. Use explicit logical operators for OR/NOT logic. Omitted if empty.
	 *
	 * ```evitaql
	 * filterBy(
	 *     attributeEquals("available", true),
	 *     attributeEquals("category", "Electronics")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/basics#filter-by)
	 *
	 * @see io.evitadb.api.query.filter.FilterBy
	 */
	@SourceHash("a994fc4f84378ec1343a5d573a2603cc")
	@Nullable
	static FilterBy filterBy(@Nullable FilterConstraint... constraint) {
		return ArrayUtils.isEmptyOrItsValuesNull(constraint) ? null : new FilterBy(constraint);
	}

	/**
	 * Filters facet groups included in a facet summary by applying constraints to their attributes, allowing you to show only groups matching specific criteria (e.g., visibility, priority). Constraints are combined with AND by default; use logical operators for custom logic. Only valid within facetSummary requirements.
	 *
	 * ```evitaql
	 * facetSummary(
	 *   COUNTS,
	 *   filterGroupBy(
	 *     attributeEquals("visible", true)
	 *   )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/basics#filter-by)
	 *
	 * @see io.evitadb.api.query.filter.FilterGroupBy
	 */
	@SourceHash("075f2ec00a842fa41443c109acb094da")
	@Nullable
	static FilterGroupBy filterGroupBy(@Nullable FilterConstraint... constraint) {
		return ArrayUtils.isEmptyOrItsValuesNull(constraint) ? null : new FilterGroupBy(constraint);
	}

	/**
	 * Combines multiple filter constraints into a logical AND, returning only entities that satisfy all child conditions simultaneously. Use `and` to explicitly group constraints when nesting within other logical operators or to clarify intent, though evitaDB defaults to conjunction when multiple constraints are listed together. A single-child `and` is redundant and optimized away; an empty `and` is invalid.
	 *
	 * ```evitaql
	 * filterBy(
	 *   and(
	 *     attributeEquals("available", true),
	 *     priceBetween(100, 500)
	 *   )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/logical#and)
	 *
	 * @see io.evitadb.api.query.filter.And
	 */
	@SourceHash("d0a246bbda3979243883d6a0a536563c")
	@Nullable
	static And and(@Nullable FilterConstraint... constraints) {
		if (ArrayUtils.isEmptyOrItsValuesNull(constraints)) {
			return null;
		}
		return new And(constraints);
	}

	/**
	 * Combines multiple filter constraints using logical OR, returning entities that match at least one child constraint. Use `or` to explicitly require any single condition to be satisfied, producing the union of matched entities. Redundant with one child; not valid empty.
	 *
	 * ```evitaql
	 * filterBy(
	 *   or(
	 *     attributeEquals("brand", "Nike"),
	 *     attributeEquals("brand", "Adidas")
	 *   )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/logical#or)
	 *
	 * @see io.evitadb.api.query.filter.Or
	 */
	@SourceHash("cb46b07d38db7d7b5475b7118506ff22")
	@Nullable
	static Or or(@Nullable FilterConstraint... constraints) {
		if (ArrayUtils.isEmptyOrItsValuesNull(constraints)) {
			return null;
		}
		return new Or(constraints);
	}

	/**
	 * Creates a logical NOT constraint that inverts the result of its single child, excluding all entities matching the child and including all others. Use to express negative conditions in filters, references, or facets; only one child constraint is allowed.
	 *
	 * ```evitaql
	 * filterBy(
	 *   not(
	 *     attributeEquals("onSale", true)
	 *   )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/logical#not)
	 *
	 * @see io.evitadb.api.query.filter.Not
	 */
	@SourceHash("2d5dbbbf09209b01bd0b1f3adb3626eb")
	@Nullable
	static Not not(@Nullable FilterConstraint constraint) {
		return constraint == null ? null : new Not(constraint);
	}

	/**
	 * Filters entities by requiring at least one reference of the specified name that satisfies the given constraints, switching the filtering context to the referenced entity or the reference itself. Behaves like SQL's `EXISTS` for references, supporting attribute, entity, and primary key filtering.
	 *
	 * ```evitaql
	 * referenceHaving(
	 *     "brand",
	 *     entityHaving(
	 *         attributeEquals("code", "apple")
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/references#reference-having)
	 *
	 * @see io.evitadb.api.query.filter.ReferenceHaving
	 */
	@SourceHash("39c0a65a0262d45fbcfe542cbbaf496e")
	@Nullable
	static ReferenceHaving referenceHaving(@Nonnull String referenceName, @Nullable FilterConstraint... constraint) {
		requireName(referenceName, "referenceName");
		return new ReferenceHaving(referenceName, constraint);
	}

	/**
	 * Separates user-controlled filters (e.g., selected facets, price ranges) from mandatory system constraints, enabling EvitaDB to temporarily relax or exclude these conditions during facet summary and histogram computations for broader result statistics. Forbids nesting of certain context-defining constraints inside. 
	 *
	 * ```evitaql
	 * userFilter(
	 *     attributeEquals('available', true),
	 *     facetHaving('brand', entityHaving(attributeInSet('code', 'apple', 'samsung'))),
	 *     priceBetween(100, 500)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/behavioral#user-filter)
	 *
	 * @see io.evitadb.api.query.filter.UserFilter
	 */
	@SourceHash("f424d22c8bc862ad368c959a6ab7871d")
	@Nullable
	static UserFilter userFilter(@Nullable FilterConstraint... constraints) {
		if (ArrayUtils.isEmptyOrItsValuesNull(constraints)) {
			return null;
		}
		return new UserFilter(withoutNullValues(constraints));
	}

	/**
	 * Filters entities where the given attribute value falls within the specified inclusive range; supports single, array, and Range-typed attributes (with overlap semantics for ranges). Type-safe, locale-aware, and optimized for index scans. At least one bound must be non-null.
	 *
	 * ```evitaql
	 * filterBy(
	 *   attributeBetween("price", 50.00, 100.00)
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/comparable#attribute-between)
	 *
	 * @see io.evitadb.api.query.filter.AttributeBetween
	 */
	@SourceHash("d1e640d67f7b2a4205ab8452ce256fc9")
	@Nullable
	static <T extends Serializable> AttributeBetween attributeBetween(@Nonnull String attributeName, @Nullable T from,
		@Nullable T to) {
		requireName(attributeName, "attributeName");
		if (from == null && to == null) {
			return null;
		} else {
			return new AttributeBetween(attributeName, from, to);
		}
	}

	/**
	 * Filters entities where the specified string attribute contains the given substring anywhere in its value, using case-sensitive, UTF-8 matching (like Java's `String.contains`). Works with array attributes (matches if any element contains the substring). Attribute must be filterable or unique and of type String.
	 *
	 * ```evitaql
	 * filterBy(
	 *     attributeContains("description", "wireless")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/string#attribute-contains)
	 *
	 * @see io.evitadb.api.query.filter.AttributeContains
	 */
	@SourceHash("58026ac61d1a7f676d97b83f0ddaa327")
	@Nullable
	static AttributeContains attributeContains(@Nonnull String attributeName, @Nullable String textToSearch) {
		requireName(attributeName, "attributeName");
		return textToSearch == null ? null : new AttributeContains(attributeName, textToSearch);
	}

	/**
	 * Filters entities where the specified string attribute starts with the given prefix, using case-sensitive, UTF-8-aware matching (like Java's `String.startsWith`). Works with single or array attributes (matches if any array element starts with the prefix). Attribute must be filterable or unique and of type String.
	 *
	 * ```evitaql
	 * filterBy(
	 *     attributeStartsWith("sku", "ELEC")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/string#attribute-starts-with)
	 *
	 * @see io.evitadb.api.query.filter.AttributeStartsWith
	 */
	@SourceHash("978aa47eaf2c84ed1c55a93620489058")
	@Nullable
	static AttributeStartsWith attributeStartsWith(@Nonnull String attributeName, @Nullable String textToSearch) {
		requireName(attributeName, "attributeName");
		return textToSearch == null ? null : new AttributeStartsWith(attributeName, textToSearch);
	}

	/**
	 * Filters entities where the specified string attribute ends with a given suffix, using case-sensitive, UTF-8 aware matching identical to Java's `String.endsWith`. Works with both single and array attributes (matches if any array element ends with the suffix). Attribute must be filterable or unique and of type String.
	 *
	 * ```evitaql
	 * filterBy(
	 *     attributeEndsWith("filename", ".pdf")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/string#attribute-ends-with)
	 *
	 * @see io.evitadb.api.query.filter.AttributeEndsWith
	 */
	@SourceHash("e5efefa596fdf401f143a58d824d3383")
	@Nullable
	static AttributeEndsWith attributeEndsWith(@Nonnull String attributeName, @Nullable String textToSearch) {
		requireName(attributeName, "attributeName");
		return textToSearch == null ? null : new AttributeEndsWith(attributeName, textToSearch);
	}

	/**
	 * Filters entities where the specified attribute exactly equals the given value, using type-safe comparison. Works for filterable or unique attributes, supports case-sensitive string and locale-aware comparisons, and matches any element for array attributes.
	 *
	 * ```evitaql
	 * filterBy(
	 *   attributeEquals("status", "PUBLISHED")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/comparable#attribute-equals)
	 *
	 * @see io.evitadb.api.query.filter.AttributeEquals
	 */
	@SourceHash("7aefcc60ae8053e13627286dc516bb04")
	@Nullable
	static <T extends Serializable> AttributeEquals attributeEquals(@Nonnull String attributeName,
		@Nullable T attributeValue) {
		requireName(attributeName, "attributeName");
		return attributeValue == null ? null : new AttributeEquals(attributeName, attributeValue);
	}

	/**
	 * Filters entities where the specified attribute's value is strictly less than the given threshold, enabling exclusive upper-bound queries for numeric, date, or ordered data. Supports type-safe comparison, locale-aware string collation, and matches if any array element is less. Use with lower-bound constraints for range queries.
	 *
	 * ```evitaql
	 * filterBy(
	 *   attributeLessThan("price", 100.00)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/comparable#attribute-less-than)
	 *
	 * @see io.evitadb.api.query.filter.AttributeLessThan
	 */
	@SourceHash("7ba6101cae348339c843ed62aa462c36")
	@Nullable
	static <T extends Serializable> AttributeLessThan attributeLessThan(@Nonnull String attributeName,
		@Nullable T attributeValue) {
		requireName(attributeName, "attributeName");
		return attributeValue == null ? null : new AttributeLessThan(attributeName, attributeValue);
	}

	/**
	 * Filters entities where the specified attribute value is less than or equal to the given threshold, including the boundary value in the result. Supports type-safe, locale-aware comparisons and matches if any element of an array attribute meets the condition. Ideal for inclusive upper bounds in range queries.
	 *
	 * ```evitaql
	 * filterBy(
	 *     attributeLessThanEquals("price", 100.00)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/comparable#attribute-less-than-equals)
	 *
	 * @see io.evitadb.api.query.filter.AttributeLessThanEquals
	 */
	@SourceHash("468105812a45da3111ff99827ac0d971")
	@Nullable
	static <T extends Serializable> AttributeLessThanEquals attributeLessThanEquals(@Nonnull String attributeName,
		@Nullable T attributeValue) {
		requireName(attributeName, "attributeName");
		return attributeValue == null ? null : new AttributeLessThanEquals(attributeName, attributeValue);
	}

	/**
	 * Filters entities where the specified attribute's value is strictly greater than the given threshold, establishing an exclusive lower bound for numeric, date, string, or boolean fields. Supports type-safe comparison and matches if any element in an array attribute exceeds the threshold. Commonly used for range queries and can be combined with upper-bound constraints for interval filtering.
	 *
	 * ```evitaql
	 * filterBy(
	 *     attributeGreaterThan("price", 100.00)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/comparable#attribute-greater-than)
	 *
	 * @see io.evitadb.api.query.filter.AttributeGreaterThan
	 */
	@SourceHash("6697d8a407fb8c0c265bc92353460938")
	@Nullable
	static <T extends Serializable> AttributeGreaterThan attributeGreaterThan(@Nonnull String attributeName,
		@Nullable T attributeValue) {
		requireName(attributeName, "attributeName");
		return attributeValue == null ? null : new AttributeGreaterThan(attributeName, attributeValue);
	}

	/**
	 * Filters entities where the specified attribute's value is greater than or equal to the given threshold, including the boundary value itself. Supports type-safe, locale-aware comparisons and matches if any element in an array attribute meets the condition. Ideal for inclusive lower bounds in range queries.
	 *
	 * ```evitaql
	 * filterBy(
	 *     attributeGreaterThanEquals("price", 50.00)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/comparable#attribute-greater-than-equals)
	 *
	 * @see io.evitadb.api.query.filter.AttributeGreaterThanEquals
	 */
	@SourceHash("3c5f598d53e7ba5873455cbe0accb8e9")
	@Nullable
	static <T extends Serializable> AttributeGreaterThanEquals attributeGreaterThanEquals(@Nonnull String attributeName,
		@Nullable T attributeValue) {
		requireName(attributeName, "attributeName");
		return attributeValue == null ? null : new AttributeGreaterThanEquals(attributeName, attributeValue);
	}

	/**
	 * Filters entities to those with at least one price in any of the specified price lists, using the left-to-right order to determine price selection priority. Only one `priceInPriceLists` constraint is allowed per query, and it cannot be nested in user filters. Combine with currency and validity constraints for full "price for sale" logic.
	 *
	 * ```evitaql
	 * filterBy(
	 *   and(
	 *     priceInCurrency("EUR"),
	 *     priceInPriceLists("vip", "reference", "basic"),
	 *     priceValidInNow()
	 *   )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/price#price-in-price-lists)
	 *
	 * @see io.evitadb.api.query.filter.PriceInPriceLists
	 */
	@SourceHash("1d8075e921e5196ef6040f415c3f6fea")
	@Nullable
	static PriceInPriceLists priceInPriceLists(@Nullable String... priceList) {
		final String[] normalizedPriceList = withoutUndefinedPriceLists(priceList);
		if (normalizedPriceList == null) {
			return null;
		}
		if (normalizedPriceList.length == 0) {
			return new PriceInPriceLists();
		}
		return new PriceInPriceLists(normalizedPriceList);
	}

	/**
	 * Filters entities to those with at least one price in the specified ISO 4217 currency (e.g., "EUR", "USD"). Only one `priceInCurrency` constraint is allowed per query, and it cannot be combined with multiple currencies or nested in user filters. Used with `priceInPriceLists` and `priceValidIn` to determine the "price for sale" per entity.
	 *
	 * ```evitaql
	 * filterBy(
	 *   priceInCurrency("EUR")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/price#price-in-currency)
	 *
	 * @see io.evitadb.api.query.filter.PriceInCurrency
	 */
	@SourceHash("275072404f4658c7a98a64fd11a7d2e8")
	@Nullable
	static PriceInCurrency priceInCurrency(@Nullable String currency) {
		return currency == null ? null : new PriceInCurrency(currency);
	}

	/**
	 * Filters entities to those with at least one price defined in the specified ISO 4217 currency (e.g., "EUR"). Only a single currency can be set per query, and this constraint cannot be nested or combined with others for OR logic. Used with `priceInPriceLists` and `priceValidIn` to determine the "price for sale" per entity.
	 *
	 * ```evitaql
	 * filterBy(
	 *   priceInCurrency("USD")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/price#price-in-currency)
	 *
	 * @see io.evitadb.api.query.filter.PriceInCurrency
	 */
	@SourceHash("faa1faae90188984881fe17835f15ce7")
	@Nullable
	static PriceInCurrency priceInCurrency(@Nullable Currency currency) {
		return currency == null ? null : new PriceInCurrency(currency);
	}

	/**
	 * Filters hierarchical entities to those within the subtree rooted at nodes matching the given filter, including the root(s) and all direct and transitive descendants. Optionally refines results with hierarchy specification constraints (e.g., only direct children, subtree exclusions). Use for efficient category, region, or org chart queries on self-hierarchical entity collections.
	 *
	 * ```evitaql
	 * filterBy(
	 *     hierarchyWithinSelf(
	 *         attributeEquals("code", "accessories")
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/hierarchy#hierarchy-within)
	 *
	 * @see io.evitadb.api.query.filter.HierarchyWithin
	 */
	@SourceHash("a3ce2417614a8dfa9105aec63a57584b")
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
	 * Filters entities to those within a hierarchy subtree rooted at nodes matching `ofParent`, following the specified `referenceName` (for referenced hierarchies). Includes all direct and transitive descendants unless refined by additional hierarchy specification constraints (e.g., directRelation, excluding). Deduplicates results when entities reference multiple nodes in the subtree. Only one hierarchyWithin constraint is allowed per query.
	 *
	 * ```evitaql
	 * filterBy(
	 *     hierarchyWithin(
	 *         "categories",
	 *         attributeEquals("code", "accessories"),
	 *         directRelation()
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/hierarchy#hierarchy-within)
	 *
	 * @see io.evitadb.api.query.filter.HierarchyWithin
	 */
	@SourceHash("fb5f82c61e6ab05bc1d7cbc219ae84a0")
	@Nullable
	static HierarchyWithin hierarchyWithin(@Nonnull String referenceName, @Nullable FilterConstraint ofParent,
		@Nullable HierarchySpecificationFilterConstraint... with) {
		requireName(referenceName, "referenceName");
		if (ofParent == null) {
			return null;
		} else if (with == null) {
			return new HierarchyWithin(referenceName, ofParent);
		} else {
			return new HierarchyWithin(referenceName, ofParent, with);
		}
	}

	/**
	 * Restricts results to all entities in the entire hierarchy tree, treating all top-level nodes as children of a virtual root. Use for self-hierarchical queries (e.g., categories), with optional filters to refine which nodes or subtrees are included. Orphans are excluded.
	 *
	 * ```evitaql
	 * filterBy(
	 *     hierarchyWithinRootSelf()
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/hierarchy#hierarchy-within-root)
	 *
	 * @see io.evitadb.api.query.filter.HierarchyWithinRoot
	 */
	@SourceHash("2b096d2448532a2475705b53745a6d11")
	@Nonnull
	static HierarchyWithinRoot hierarchyWithinRootSelf(@Nullable HierarchySpecificationFilterConstraint... with) {
		return ArrayUtils.isEmptyOrItsValuesNull(with) ? new HierarchyWithinRoot() :
			new HierarchyWithinRoot(withoutNullValues(with));
	}

	/**
	 * Filters entities to those within the entire hierarchy tree, treating all top-level nodes as children of a virtual root. Use `referenceName` to target referenced hierarchies (e.g., products by category). Refine with specification constraints like `excluding`, `having`, or `directRelation`. Orphaned nodes are always excluded. Only one hierarchy constraint per query is allowed.
	 *
	 * ```evitaql
	 * filterBy(
	 *     hierarchyWithinRoot(
	 *         "categories",
	 *         excluding(attributeEquals("clearance", true))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/hierarchy#hierarchy-within-root)
	 *
	 * @see io.evitadb.api.query.filter.HierarchyWithinRoot
	 */
	@SourceHash("ac9f7ba074c4181d7156351c92d8708e")
	@Nonnull
	static HierarchyWithinRoot hierarchyWithinRoot(@Nonnull String referenceName,
		@Nullable HierarchySpecificationFilterConstraint... with) {
		requireName(referenceName, "referenceName");
		return new HierarchyWithinRoot(referenceName,
			ArrayUtils.isEmptyOrItsValuesNull(with) ? new HierarchySpecificationFilterConstraint[0] :
				withoutNullValues(with));
	}

	/**
	 * Filters hierarchy subtrees by requiring every node from root to leaf to satisfy the specified filter constraints; if a node fails, it and all descendants are excluded from results. Use only within `hierarchyWithin` or `hierarchyWithinRoot` to conditionally include branches based on attributes like validity, status, or access. Early termination ensures efficient pruning of entire subtrees when an ancestor fails the filter.
	 *
	 * ```evitaql
	 * hierarchyWithinRoot(
	 *     "categories",
	 *     having(attributeEquals("status", "ACTIVE"))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/hierarchy#having)
	 *
	 * @see io.evitadb.api.query.filter.HierarchyHaving
	 */
	@SourceHash("1af5ff33fa779df03a1a9eea2595cdee")
	@Nullable
	static HierarchyHaving having(@Nullable FilterConstraint... includeChildTreeConstraints) {
		if (ArrayUtils.isEmptyOrItsValuesNull(includeChildTreeConstraints)) {
			return null;
		}
		return new HierarchyHaving(includeChildTreeConstraints);
	}

	/**
	 * Filters hierarchy subtrees to include only those where at least one node (including descendants) matches all specified filter constraints. Unlike `having`, it scans the entire subtree before deciding, enabling "existence" queries such as finding categories containing featured products.
	 *
	 * ```evitaql
	 * hierarchyWithinRoot(
	 *     "categories",
	 *     anyHaving(attributeEquals("featured", true))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/hierarchy#anyHaving)
	 *
	 * @see io.evitadb.api.query.filter.HierarchyAnyHaving
	 */
	@SourceHash("b6ff77d368ae895a655cf3de616e64ce")
	@Nullable
	static HierarchyAnyHaving anyHaving(@Nullable FilterConstraint... includeChildTreeConstraints) {
		if (ArrayUtils.isEmptyOrItsValuesNull(includeChildTreeConstraints)) {
			return null;
		}
		return new HierarchyAnyHaving(includeChildTreeConstraints);
	}

	/**
	 * Excludes entire subtrees from hierarchy query results by removing nodes that match the given filter constraints and all their descendants, using early termination for efficiency. Only one `excluding` constraint is evaluated per query, and it must be used within `hierarchyWithin` or `hierarchyWithinRoot`. Useful for hiding categories like clearance, inactive, or restricted sections in both self- and reference-hierarchical queries.
	 *
	 * ```evitaql
	 * hierarchyWithin(
	 *   "categories",
	 *   attributeEquals("code", "electronics"),
	 *   excluding(attributeEquals("clearance", true))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/hierarchy#excluding)
	 *
	 * @see io.evitadb.api.query.filter.HierarchyExcluding
	 */
	@SourceHash("7da676f2e8ba1ed26d8bd73f489eac24")
	@Nullable
	static HierarchyExcluding excluding(@Nullable FilterConstraint... excludeChildTreeConstraints) {
		if (ArrayUtils.isEmptyOrItsValuesNull(excludeChildTreeConstraints)) {
			return null;
		}
		return new HierarchyExcluding(excludeChildTreeConstraints);
	}

	/**
	 * Limits hierarchy queries to only the immediate (direct) children of the specified parent node(s), excluding all transitive descendants. Use within `hierarchyWithin` or `hierarchyWithinRoot` to fetch just the next level—ideal for navigation menus or direct assignments. Combine with `excludingRoot()` to omit the parent, or with filters like `having()` for refined results.
	 *
	 * ```evitaql
	 * hierarchyWithin(
	 *   "categories",
	 *   attributeEquals("code", "electronics"),
	 *   directRelation()
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/hierarchy#direct-relation)
	 *
	 * @see io.evitadb.api.query.filter.HierarchyDirectRelation
	 */
	@SourceHash("802cf5c28c5a625a7f43a4a99685e4ba")
	@Nonnull
	static HierarchyDirectRelation directRelation() {
		return new HierarchyDirectRelation();
	}

	/**
	 * Excludes the parent node itself from hierarchy query results while retaining all its children and descendants. Use within `hierarchyWithin` to omit the parent entity (e.g., a category) but include its subcategories or referenced entities. Not valid with `hierarchyWithinRoot`. Useful for category landing pages or navigation where the parent should not appear in results.
	 *
	 * ```evitaql
	 * hierarchyWithin(
	 *     "categories",
	 *     attributeEquals("code", "electronics"),
	 *     excludingRoot()
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/hierarchy#excluding-root)
	 *
	 * @see io.evitadb.api.query.filter.HierarchyExcludingRoot
	 */
	@SourceHash("59459aadf51b90bf52a3ff0074440588")
	@Nonnull
	static HierarchyExcludingRoot excludingRoot() {
		return new HierarchyExcludingRoot();
	}

	/**
	 * Sets the locale context for all localized attributes and associated data in the query, ensuring filters, sorting, and fetched data use the specified IETF BCP 47 locale (e.g., `en-US`). Mandatory when querying localized properties; omitting it causes a validation error if localized attributes are referenced.
	 *
	 * ```evitaql
	 * filterBy(
	 *     entityLocaleEquals("en-US"),
	 *     attributeContains("name", "phone")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/locale#entity-locale-equals)
	 *
	 * @see io.evitadb.api.query.filter.EntityLocaleEquals
	 */
	@SourceHash("2ac6cbecfc13f45dfe0db234d149984f")
	@Nullable
	static EntityLocaleEquals entityLocaleEquals(@Nullable Locale locale) {
		return locale == null ? null : new EntityLocaleEquals(locale);
	}

	/**
	 * Filters referenced entities by their own attributes or properties within a reference or facet context, shifting the filter scope from the relationship to the referenced entity itself. All child constraints are combined with logical AND and apply to the referenced entity, not the enclosing entity or the reference. Requires the reference schema to have the REFERENCED_ENTITY component enabled.
	 *
	 * ```evitaql
	 * referenceHaving(
	 *     "brand",
	 *     entityHaving(
	 *         attributeEquals("code", "apple")
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/references#entity-having)
	 *
	 * @see io.evitadb.api.query.filter.EntityHaving
	 */
	@SourceHash("3e7cb1f0ce6e0abb018a095e0a01b4e1")
	@Nullable
	static EntityHaving entityHaving(@Nullable FilterConstraint... filterConstraints) {
		return ArrayUtils.isEmptyOrItsValuesNull(filterConstraints) ? null : new EntityHaving(filterConstraints);
	}

	/**
	 * Filters based on attributes or properties of the group entity associated with a reference, shifting the filtering scope from the reference or referenced entity to its group. Only usable within `referenceHaving` or `facetHaving`, and requires the reference schema to support group entities. All child constraints must match the same group entity; multiple children are combined with logical AND.
	 *
	 * ```evitaql
	 * referenceHaving(
	 *     "brand",
	 *     groupHaving(
	 *         attributeEquals("code", "store-london")
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/references#group-having)
	 *
	 * @see io.evitadb.api.query.filter.GroupHaving
	 */
	@SourceHash("41027634beeb9fa7a6d2ff94eeb32e70")
	@Nullable
	static GroupHaving groupHaving(@Nullable FilterConstraint... filterConstraints) {
		return ArrayUtils.isEmptyOrItsValuesNull(filterConstraints) ? null : new GroupHaving(filterConstraints);
	}

	/**
	 * Filters entities where the given `OffsetDateTime` value falls within the inclusive boundaries of a range-type attribute (e.g., `DateTimeRange`), supporting unbounded ranges and arrays (matches if any range contains the value). Attribute must be filterable and of a compatible range type.
	 *
	 * ```evitaql
	 * filterBy(
	 *     attributeInRange("promotionValidity", OffsetDateTime.parse("2024-06-15T00:00:00Z"))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/range#attribute-in-range)
	 *
	 * @see io.evitadb.api.query.filter.AttributeInRange
	 */
	@SourceHash("901e300f89584a13c4e220e5afc4ecc0")
	@Nullable
	static AttributeInRange attributeInRange(@Nonnull String attributeName, @Nullable OffsetDateTime atTheMoment) {
		requireName(attributeName, "attributeName");
		return atTheMoment == null ? null : new AttributeInRange(attributeName, atTheMoment);
	}

	/**
	 * Filters entities where the given numeric value falls within the inclusive boundaries of a range-type attribute (e.g., IntegerNumberRange, LongNumberRange). Returns true if `attribute.from <= value <= attribute.to`, supporting unbounded ranges and arrays (matches if any range contains the value).
	 *
	 * ```evitaql
	 * filterBy(
	 *     attributeInRange("quantityBracket", 10)
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/range#attribute-in-range)
	 *
	 * @see io.evitadb.api.query.filter.AttributeInRange
	 */
	@SourceHash("2387209939ff9565d0af251c8b2bcdd9")
	@Nullable
	static AttributeInRange attributeInRange(@Nonnull String attributeName, @Nullable Number theValue) {
		requireName(attributeName, "attributeName");
		return theValue == null ? null : new AttributeInRange(attributeName, theValue);
	}

	/**
	 * Filters entities whose range-type attribute contains the current system date and time, enabling real-time temporal validity checks (e.g., promotions or availability windows). Works only with filterable range attributes; both range boundaries are inclusive.
	 *
	 * ```evitaql
	 * filterBy(
	 *     attributeInRangeNow("promotionValidity")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/range#attribute-in-range)
	 *
	 * @see io.evitadb.api.query.filter.AttributeInRange
	 */
	@SourceHash("1680966ff5b72dfe18a0151c75b17b25")
	@Nullable
	static AttributeInRange attributeInRangeNow(@Nonnull String attributeName) {
		requireName(attributeName, "attributeName");
		return new AttributeInRange(attributeName);
	}

	/**
	 * Filters entities where the specified attribute equals any value from the provided set, offering a concise, high-performance alternative to multiple `attributeEquals` constraints. Supports type-safe, case-sensitive matching and works with both scalar and array attributes (matches if any array element is in the set).
	 *
	 * ```evitaql
	 * filterBy(
	 *   attributeInSet("status", "PUBLISHED", "FEATURED", "PROMOTED")
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/comparable#attribute-in-set)
	 *
	 * @see io.evitadb.api.query.filter.AttributeInSet
	 */
	@SourceHash("f420894e8ac4ef178c2c0eea29117cda")
	@SuppressWarnings("unchecked")
	@Nullable
	static <T extends Serializable> AttributeInSet attributeInSet(@Nonnull String attributeName, @Nullable T... set) {
		requireName(attributeName, "attributeName");
		final T[] normalizedSet = withoutNullValues(set);
		return normalizedSet == null ? null : new AttributeInSet(attributeName, normalizedSet);
	}

	/**
	 * Filters entities where the specified attribute exactly equals `false`. Useful for precise matching against boolean attributes, treating `false` as numeric zero. Works with filterable or unique attributes, including arrays (matches if any element is `false`).
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/comparable#attribute-equals)
	 *
	 * @see io.evitadb.api.query.filter.AttributeEquals
	 */
	@SourceHash("718d0813b4684b02b33966c8c37912a8")
	@Nullable
	static AttributeEquals attributeEqualsFalse(@Nonnull String attributeName) {
		requireName(attributeName, "attributeName");
		return new AttributeEquals(attributeName, Boolean.FALSE);
	}

	/**
	 * Filters entities where the specified attribute exactly equals `true`, using type-safe comparison. Useful for boolean flags stored as attributes; matches only if the attribute value is `true`. For array attributes, matches if any element is `true`.
	 *
	 * ```evitaql
	 * filterBy(
	 *   attributeEqualsTrue("featured")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/comparable#attribute-equals)
	 *
	 * @see io.evitadb.api.query.filter.AttributeEquals
	 */
	@SourceHash("57684834e13ce6bca64390d8b531a18a")
	@Nullable
	static AttributeEquals attributeEqualsTrue(@Nonnull String attributeName) {
		requireName(attributeName, "attributeName");
		return new AttributeEquals(attributeName, Boolean.TRUE);
	}

	/**
	 * Filters entities by the presence or absence of a specific attribute, distinguishing between attributes that are missing (null) and those that are set (not null), regardless of value. For arrays, checks if the attribute itself exists (empty array is NOT_NULL). Use to detect missing data, enforce required fields, or filter by optional attribute existence.
	 *
	 * ```evitaql
	 * filterBy(
	 *   attributeIs("description", NULL)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/comparable#attribute-is)
	 *
	 * @see io.evitadb.api.query.filter.AttributeIs
	 */
	@SourceHash("abd10daf6d7cc006c5b08b117d3ba9d7")
	@Nullable
	static AttributeIs attributeIs(@Nonnull String attributeName, @Nonnull AttributeSpecialValue specialValue) {
		requireName(attributeName, "attributeName");
		Assert.notNull(specialValue, "Argument `specialValue` must not be null: it has no default.");
		return new AttributeIs(attributeName, specialValue);
	}

	/**
	 * Filters entities where the specified attribute is either missing or explicitly set to null, enabling detection of absent or unset values. Essential for distinguishing between missing and present data, including for array attributes (missing array is NULL, empty array is NOT_NULL). Does not compare values—only existence is checked.
	 *
	 * ```evitaql
	 * filterBy(
	 *   attributeIsNull("description")
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/comparable#attribute-is)
	 *
	 * @see io.evitadb.api.query.filter.AttributeIs
	 */
	@SourceHash("273f0d3bec4d5835408ece12065370ce")
	@Nullable
	static AttributeIs attributeIsNull(@Nonnull String attributeName) {
		requireName(attributeName, "attributeName");
		return new AttributeIs(attributeName, AttributeSpecialValue.NULL);
	}

	/**
	 * Filters entities to include only those where the specified attribute exists and is not null, distinguishing between missing and present values regardless of their content (including empty arrays, empty strings, or zero). Use this to require mandatory fields or detect optional features; for arrays, checks if the array itself is set, not its elements.
	 *
	 * ```evitaql
	 * filterBy(
	 *   attributeIsNotNull("description")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/comparable#attribute-is)
	 *
	 * @see io.evitadb.api.query.filter.AttributeIs
	 */
	@SourceHash("8b7fcc95cba8ba7a66f4f4bc49196e40")
	@Nullable
	static AttributeIs attributeIsNotNull(@Nonnull String attributeName) {
		requireName(attributeName, "attributeName");
		return new AttributeIs(attributeName, AttributeSpecialValue.NOT_NULL);
	}

	/**
	 * Filters entities to those whose computed "price for sale" falls within the inclusive `[from, to]` range, based on active currency, price list, and validity constraints. Place inside `userFilter` for correct facet/histogram behavior; use `null` for unbounded limits.
	 *
	 * ```evitaql
	 * filterBy(
	 *   and(
	 *     priceInCurrency("USD"),
	 *     priceInPriceLists("basic"),
	 *     priceValidInNow(),
	 *     userFilter(
	 *       priceBetween(50.00, 200.00)
	 *     )
	 *   )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/price#price-between)
	 *
	 * @see io.evitadb.api.query.filter.PriceBetween
	 */
	@SourceHash("d4c0abe76fce724dcfe63e5d0bce5b42")
	@Nullable
	static PriceBetween priceBetween(@Nullable BigDecimal from, @Nullable BigDecimal to) {
		if (from == null && to == null) {
			return null;
		} else {
			return new PriceBetween(from, to);
		}
	}

	/**
	 * Filters entities to those with at least one price valid at the specified date and time, considering prices with no validity range as always valid. Use with `priceInCurrency` and `priceInPriceLists` to select the "price for sale" for a given moment.
	 *
	 * ```evitaql
	 * filterBy(
	 *   priceValidIn(2025-12-25T00:00:00Z)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/filtering/price#price-valid-in)
	 *
	 * @see io.evitadb.api.query.filter.PriceValidIn
	 */
	@SourceHash("e38e30a44d523ef1a0e7583403e53c59")
	@Nullable
	static PriceValidIn priceValidIn(@Nullable OffsetDateTime theMoment) {
		return theMoment == null ? null : new PriceValidIn(theMoment);
	}

	/**
	 * Filters entities to those with at least one price valid at the moment of query execution, using the current system time. Supports dynamic pricing scenarios (e.g., sales, scheduled changes) and enables caching with time-sensitive results. Only one such constraint per query is allowed.
	 *
	 * ```evitaql
	 * filterBy(
	 *   and(
	 *     priceInCurrency("USD"),
	 *     priceInPriceLists("sale", "default"),
	 *     priceValidInNow()
	 *   )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/filtering/price#price-valid-in)
	 *
	 * @see io.evitadb.api.query.filter.PriceValidIn
	 */
	@SourceHash("e4e1631b7a8434d7a6033ca0fbdb94f4")
	@Nonnull
	static PriceValidIn priceValidInNow() {
		return new PriceValidIn();
	}

	/**
	 * Filters entities by faceted references, selecting facets (referenced entities) via nested constraints and returning entities referencing them. Facets from the same group are OR-ed, groups are AND-ed by default; integrates with `facetSummary` for impact stats in faceted navigation. Use inside `userFilter` for drill-down UIs. Differs from `referenceHaving` by evaluating constraints per facet, not per reference.
	 *
	 * ```evitaql
	 * userFilter(
	 *     facetHaving(
	 *         "brand",
	 *         entityHaving(
	 *             attributeInSet("code", "amazon")
	 *         )
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/references#facet-having)
	 *
	 * @see io.evitadb.api.query.filter.FacetHaving
	 */
	@SourceHash("97dda9ca64f792ad54c17848d9a0aaf6")
	@Nullable
	static FacetHaving facetHaving(@Nonnull String referenceName, @Nullable FilterConstraint... constraint) {
		requireName(referenceName, "referenceName");
		return ArrayUtils.isEmptyOrItsValuesNull(constraint) ? null : new FacetHaving(referenceName, constraint);
	}

	/**
	 * Narrows a reference histogram to the specified inclusive `[from, to]` range for the given reference, enabling slider-driven filtering on numeric attributes (e.g., parameter values). At least one bound must be non-null, and `from` must not exceed `to`. Used in `userFilter` for interactive range selection without contracting the histogram's full span.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/references#histogram-having)
	 *
	 * @see io.evitadb.api.query.filter.HistogramHaving
	 */
	@SourceHash("feecfe86505838e9cfbbc3997e329ead")
	@Nullable
	static <T extends Number> HistogramHaving histogramHaving(
		@Nonnull String referenceName,
		@Nullable T from,
		@Nullable T to
	) {
		requireName(referenceName, "referenceName");
		if (from == null && to == null) {
			return null;
		}
		return new HistogramHaving(referenceName, null, toBigDecimal(from), toBigDecimal(to), null);
	}

	/**
	 * Narrows a reference histogram to a specific `[from, to]` range (inclusive) for the given reference and histogram, enabling precise slider-driven filtering on numeric attributes (e.g. height, weight) within a reference. At least one bound must be non-null, and `from` must not exceed `to`. Used inside `userFilter` to filter results and maintain independent histogram spans for UI sliders.
	 *
	 * ```evitaql
	 * userFilter(
	 *     histogramHaving("parameterValues", "basicUnitValue", 50, 120)
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/references#histogram-having)
	 *
	 * @see io.evitadb.api.query.filter.HistogramHaving
	 */
	@SourceHash("acc59c51ad1adeac0119d2ace8ab7e5b")
	@Nullable
	static <T extends Number> HistogramHaving histogramHaving(
		@Nonnull String referenceName,
		@Nonnull String histogramName,
		@Nullable T from,
		@Nullable T to
	) {
		requireName(referenceName, "referenceName");
		requireName(histogramName, "histogramName");
		if (from == null && to == null) {
			return null;
		}
		return new HistogramHaving(referenceName, histogramName, toBigDecimal(from), toBigDecimal(to), null);
	}

	/**
	 * Narrows a reference histogram to a specific `[from, to]` range (inclusive) for a given reference, optionally within a selected group via a `GroupHaving` filter. Used in `userFilter` for slider-driven range selection without contracting the histogram baseline, and outside `userFilter` as a direct filter. At least one bound must be non-null, and `from` must not exceed `to`.
	 *
	 * ```evitaql
	 * userFilter(
	 *   histogramHaving(
	 *     "parameterValues", 50, 120,
	 *     groupHaving(attributeEquals("code", "height"))
	 *   )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/references#histogram-having)
	 *
	 * @see io.evitadb.api.query.filter.HistogramHaving
	 */
	@SourceHash("285b9e5fb30a63a609b08fa50a2869f0")
	@Nullable
	static <T extends Number> HistogramHaving histogramHaving(
		@Nonnull String referenceName,
		@Nullable T from,
		@Nullable T to,
		@Nullable GroupHaving groupHaving
	) {
		requireName(referenceName, "referenceName");
		if (from == null && to == null) {
			return null;
		}
		return new HistogramHaving(referenceName, null, toBigDecimal(from), toBigDecimal(to), groupHaving);
	}

	/**
	 * Narrows a reference histogram to a specific inclusive `[from, to]` range, optionally within a selected group for grouped histograms. Ideal for slider-driven filtering on references with multiple histograms (e.g., `height`, `weight`). At least one bound must be non-null, and `from` ≤ `to` if both are set. Used in `userFilter`, it also preserves the histogram's original `[min, max]` span for sibling sliders.
	 *
	 * ```evitaql
	 * userFilter(
	 *     histogramHaving(
	 *         "parameterValues", "basicUnitValue",
	 *         50, 120,
	 *         groupHaving(attributeEquals("code", "height"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/references#histogram-having)
	 *
	 * @see io.evitadb.api.query.filter.HistogramHaving
	 */
	@SourceHash("196ca7246387b6b210a6c4dfc9cc35a3")
	@Nullable
	static <T extends Number> HistogramHaving histogramHaving(
		@Nonnull String referenceName,
		@Nonnull String histogramName,
		@Nullable T from,
		@Nullable T to,
		@Nullable GroupHaving groupHaving
	) {
		requireName(referenceName, "referenceName");
		requireName(histogramName, "histogramName");
		if (from == null && to == null) {
			return null;
		}
		return new HistogramHaving(referenceName, histogramName, toBigDecimal(from), toBigDecimal(to), groupHaving);
	}

	/**
	 * Converts an arbitrary {@link Number} to {@link BigDecimal} losslessly for the histogram-bound
	 * factories above. {@code BigDecimal} round-trips through {@code Number#toString()} preserves the
	 * exact decimal representation for every Evita-supported numeric type (Byte/Short/Integer/Long via
	 * their integral string form, BigDecimal via its own string form), avoiding the precision loss that
	 * {@code BigDecimal.valueOf(double)} would introduce for non-trivial decimals.
	 */
	@Nullable
	private static BigDecimal toBigDecimal(@Nullable Number value) {
		if (value == null) {
			return null;
		}
		return value instanceof BigDecimal bd ? bd : new BigDecimal(value.toString());
	}

	/**
	 * Modifies `facetHaving` to include not just directly referenced hierarchical entities but also all their descendants in facet filtering. Use only within `facetHaving` on hierarchical references; using it elsewhere causes an error. Enables intuitive hierarchical facet navigation.
	 *
	 * ```evitaql
	 * facetHaving(
	 *     "categories",
	 *     entityHaving(attributeEquals("code", "accessories")),
	 *     includingChildren()
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/references#including-children-having)
	 *
	 * @see io.evitadb.api.query.filter.FacetIncludingChildren
	 */
	@SourceHash("56dd42efa2d099a240773cf0601283f2")
	@Nonnull
	static FacetIncludingChildren includingChildren() {
		return new FacetIncludingChildren();
	}

	/**
	 * Modifies hierarchical facet filtering to include only those child entities of the matched facet that satisfy the provided filter constraint. Used within `facetHaving` for hierarchical references, enabling precise descendant inclusion in facet navigation.
	 *
	 * ```evitaql
	 * facetHaving(
	 *     "categories",
	 *     entityHaving(attributeEquals("code", "accessories")),
	 *     includingChildrenHaving(attributeInRangeNow("validity"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/references#including-children-having)
	 *
	 * @see io.evitadb.api.query.filter.FacetIncludingChildren
	 */
	@SourceHash("1bce0e2b55e34e511ca29df58778c64f")
	@Nonnull
	static FacetIncludingChildren includingChildrenHaving(@Nullable FilterConstraint filterConstraint) {
		return filterConstraint == null ? new FacetIncludingChildren() : new FacetIncludingChildren(filterConstraint);
	}

	/**
	 * Modifies hierarchical facet filtering to include all descendants of a referenced entity except those matching the provided filter constraint. Use within `facetHaving` on hierarchical references to exclude specific child branches (e.g., invisible or inactive categories) from both result matching and facet statistics. Exclusion applies recursively: if a child is excluded, its descendants are too.
	 *
	 * ```evitaql
	 * facetHaving(
	 *     "categories",
	 *     entityHaving(attributeEquals("code", "accessories")),
	 *     includingChildrenExcept(attributeEquals("visible", "INVISIBLE"))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/references#including-children-except)
	 *
	 * @see io.evitadb.api.query.filter.FacetIncludingChildrenExcept
	 */
	@SourceHash("98e56626615e819e54bc6daa76f65422")
	@Nonnull
	static FacetIncludingChildrenExcept includingChildrenExcept(@Nullable FilterConstraint filterConstraint) {
		return filterConstraint == null ? new FacetIncludingChildrenExcept() : new FacetIncludingChildrenExcept(filterConstraint);
	}

	/**
	 * Filters entities by exact primary key match, enabling rapid retrieval of specific entities using highly optimized bitmap indexes. Equivalent to an OR of primary key equality checks; supports ENTITY, REFERENCE, INLINE_REFERENCE, and FACET domains. Nulls are ignored, and an empty array matches no entities. Results are sorted by primary key unless overridden.
	 *
	 * ```evitaql
	 * filterBy(
	 *     entityPrimaryKeyInSet(1, 5, 8, 13)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/constant#entity-primary-key-in-set)
	 *
	 * @see io.evitadb.api.query.filter.EntityPrimaryKeyInSet
	 */
	@SourceHash("c43996b14fc949342db934b98915ec98")
	@Nullable
	static EntityPrimaryKeyInSet entityPrimaryKeyInSet(@Nullable Integer... primaryKey) {
		final Integer[] normalizedPrimaryKey = withoutNullValues(primaryKey);
		return normalizedPrimaryKey == null ? null : new EntityPrimaryKeyInSet(normalizedPrimaryKey);
	}

	/**
	 * Filters entities by exact primary key match against one or more specified keys, enabling the fastest entity retrieval in evitaDB via bitmap indexes. Equivalent to a logical OR of primary key equality checks. Nulls are ignored; empty arrays match no entities.
	 *
	 * ```evitaql
	 * filterBy(
	 *     entityPrimaryKeyInSet(1, 5, 8, 13)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/constant#entity-primary-key-in-set)
	 *
	 * @see io.evitadb.api.query.filter.EntityPrimaryKeyInSet
	 */
	@SourceHash("4ec1c4b5e935583648169bf33c39b3c3")
	@Nullable
	static EntityPrimaryKeyInSet entityPrimaryKeyInSet(@Nullable int[] primaryKey) {
		if (primaryKey == null) {
			return null;
		}
		return new EntityPrimaryKeyInSet(Arrays.stream(primaryKey).boxed().toArray(Integer[]::new));
	}

	/**
	 * Filters entities to only those with a primary key strictly greater than the specified value, enabling efficient pagination or range queries based on entity identifiers.
	 *
	 * ```evitaql
	 * filterBy(
	 *   entityPrimaryKeyGreaterThan(100)
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/comparable#entity-primary-key-greater-than)
	 *
	 * @see io.evitadb.api.query.filter.EntityPrimaryKeyGreaterThan
	 */
	@SourceHash("bb659041d776dea0de9c5ac777b9437a")
	@Nullable
	static EntityPrimaryKeyGreaterThan entityPrimaryKeyGreaterThan(@Nullable Integer primaryKey) {
		return primaryKey == null ? null : new EntityPrimaryKeyGreaterThan(primaryKey);
	}

	/**
	 * Filters entities to include only those with a primary key greater than or equal to the specified value, enabling efficient pagination or range queries based on entity identifiers.
	 *
	 * ```evitaql
	 * filterBy(
	 *   entityPrimaryKeyGreaterThanEquals(100)
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/comparable#entity-primary-key-greater-than-equals)
	 *
	 * @see io.evitadb.api.query.filter.EntityPrimaryKeyGreaterThanEquals
	 */
	@SourceHash("a4fc3dd7946a2bdc1a206bc0c2923f93")
	@Nullable
	static EntityPrimaryKeyGreaterThanEquals entityPrimaryKeyGreaterThanEquals(@Nullable Integer primaryKey) {
		return primaryKey == null ? null : new EntityPrimaryKeyGreaterThanEquals(primaryKey);
	}

	/**
	 * Filters entities to only those with a primary key strictly less than the specified value. Useful for pagination or retrieving entities in a specific key range.
	 *
	 * ```evitaql
	 * filterBy(
	 *   entityPrimaryKeyLessThan(100)
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/comparable#entity-primary-key-less-than)
	 *
	 * @see io.evitadb.api.query.filter.EntityPrimaryKeyLessThan
	 */
	@SourceHash("181fcd269ad2caca457827cec3215959")
	@Nullable
	static EntityPrimaryKeyLessThan entityPrimaryKeyLessThan(@Nullable Integer primaryKey) {
		return primaryKey == null ? null : new EntityPrimaryKeyLessThan(primaryKey);
	}

	/**
	 * Filters entities to include only those with a primary key less than or equal to the specified value, enabling efficient range queries based on entity identifiers.
	 *
	 * ```evitaql
	 * filterBy(
	 *   entityPrimaryKeyLessThanEquals(100)
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/comparable#entity-primary-key-less-than-equals)
	 *
	 * @see io.evitadb.api.query.filter.EntityPrimaryKeyLessThanEquals
	 */
	@SourceHash("3262fdd17f5cf9f15e08bf9c66a85679")
	@Nullable
	static EntityPrimaryKeyLessThanEquals entityPrimaryKeyLessThanEquals(@Nullable Integer primaryKey) {
		return primaryKey == null ? null : new EntityPrimaryKeyLessThanEquals(primaryKey);
	}

	/**
	 * Filters entities to those whose primary key is within the specified inclusive range; at least one of `from` or `to` must be non-null. Useful for paginating or restricting results by primary key intervals.
	 *
	 * ```evitaql
	 * filterBy(
	 *   entityPrimaryKeyBetween(10, 100)
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/comparable#entity-primary-key-between)
	 *
	 * @see io.evitadb.api.query.filter.EntityPrimaryKeyBetween
	 */
	@SourceHash("5885b9b54b0893c1c3dfc0e09cef5022")
	@Nullable
	static EntityPrimaryKeyBetween entityPrimaryKeyBetween(@Nullable Integer from, @Nullable Integer to) {
		if (from == null && to == null) {
			return null;
		}
		return new EntityPrimaryKeyBetween(from, to);
	}

	/**
	 * Restricts the application of child filtering constraints to a specific data scope (LIVE or ARCHIVED), enabling multi-scope queries with tailored filtering logic for each scope based on their indexing capabilities. Prevents query failures by isolating scope-specific constraints and disallows nesting within the same evaluation context.
	 *
	 * ```evitaql
	 * filterBy(
	 *     and(
	 *         inScope(LIVE, attributeEquals('name', 'LED TV')),
	 *         inScope(ARCHIVED, attributeEquals('archivedReason', 'outdated')),
	 *         scope(LIVE, ARCHIVED)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/behavioral#in-scope)
	 *
	 * @see io.evitadb.api.query.filter.FilterInScope
	 */
	@SourceHash("75c0caef03ae27c3fc823af0ae8bf0f6")
	@Nullable
	static FilterInScope inScope(@Nonnull Scope scope, @Nullable FilterConstraint... constraints) {
		Assert.notNull(scope, "Argument `scope` must not be null: it has no default.");
		return ArrayUtils.isEmptyOrItsValuesNull(constraints) ? null : new FilterInScope(scope, constraints);
	}

	/**
	 * Specifies which data scopes (LIVE, ARCHIVED, or both) the query searches within. By default, only LIVE entities are included; ARCHIVED must be explicitly requested. If an entity exists in multiple scopes, the first listed scope takes priority. Uniqueness is enforced per scope, and filtering applies to all unless wrapped in `inScope`. Scope order matters for result precedence.
	 *
	 * ```evitaql
	 * filterBy(
	 *     scope(LIVE, ARCHIVED)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/constant#scope)
	 *
	 * @see io.evitadb.api.query.filter.EntityScope
	 */
	@SourceHash("94d0c0cd2746642b698e0ca185657285")
	@Nullable
	static EntityScope scope(@Nullable Scope... scope) {
		requireNonNullItems(scope, "scope");
		return ArrayUtils.isEmptyOrItsValuesNull(scope) ? null : new EntityScope(scope);
	}

	/*
		ORDERING
	 */

	/**
	 * Defines the mandatory container for specifying entity ordering in queries. Sorting in evitaDB uses pre-built sort indexes for high performance, but its multi-attribute sorting differs from typical databases: secondary attributes only apply when the primary is missing, otherwise ties are resolved by primary key. For custom multi-attribute order, define a sortable attribute compound. Supports specifying sort direction and NULL handling per attribute.
	 *
	 * ```evitaql
	 * orderBy(
	 *     ascending("code"),
	 *     ascending("create"),
	 *     priceDescending()
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/basics#order-by)
	 *
	 * @see io.evitadb.api.query.order.OrderBy
	 */
	@SourceHash("7f51c3ccfb4ba2a78fb8cb12ff402eec")
	@Nullable
	static OrderBy orderBy(@Nullable OrderConstraint... constraints) {
		if (ArrayUtils.isEmptyOrItsValuesNull(constraints)) {
			return null;
		}
		return new OrderBy(constraints);
	}

	/**
	 * Limits enclosed ordering constraints to entities within the specified scope, ensuring that ordering is only applied where supported (e.g., only in LIVE scope if an attribute isn't indexed elsewhere). Cannot be nested in the same evaluation context.
	 *
	 * ```evitaql
	 * orderBy(
	 *    inScope(LIVE, attributeNatural("name", ASC)),
	 *    attributeNatural("code", DESC)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/behavioral#in-scope)
	 *
	 * @see io.evitadb.api.query.order.OrderInScope
	 */
	@SourceHash("4fc249979cb3f5da882848c9527b884c")
	@Nullable
	static OrderInScope inScope(@Nonnull Scope scope, @Nullable OrderConstraint... constraints) {
		Assert.notNull(scope, "Argument `scope` must not be null: it has no default.");
		return ArrayUtils.isEmptyOrItsValuesNull(constraints) ? null : new OrderInScope(scope, constraints);
	}

	/**
	 * Defines ordering constraints for facet group entities within a reference, allowing you to control the sort order of groups (such as parameter groups) independently from the order of references within each group. Use in combination with `orderBy` for granular sorting of both groups and their members.
	 *
	 * ```evitaql
	 * orderGroupBy(
	 *     attributeNatural("name", ASC)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/basics#order-by)
	 *
	 * @see io.evitadb.api.query.order.OrderGroupBy
	 */
	@SourceHash("aab3021a870dff90f771dbb23cfa67f9")
	@Nullable
	static OrderGroupBy orderGroupBy(@Nullable OrderConstraint... constraints) {
		if (ArrayUtils.isEmptyOrItsValuesNull(constraints)) {
			return null;
		}
		return new OrderGroupBy(constraints);
	}

	/**
	 * Defines multiple ordering segments within a single query, each with its own sorting and optional filtering logic. Entities are processed by each segment in order: sorted and limited as specified, then excluded from subsequent segments. Remaining entities are appended by primary key. Useful for complex, prioritized result ordering.
	 *
	 * ```evitaql
	 * orderBy(
	 *    segments(
	 *       segment(orderBy(attributeNatural("orderedQuantity", DESC)), limit(3)),
	 *       segment(entityHaving(attributeEquals("new", true)), orderBy(random()), limit(2)),
	 *       segment(orderBy(ascending("code"), ascending("create")))
	 *    )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/segment)
	 *
	 * @see io.evitadb.api.query.order.Segments
	 */
	@SourceHash("664e1ac4a4e2cf10240ab133179dbadb")
	@Nullable
	static Segments segments(@Nullable Segment... constraints) {
		if (ArrayUtils.isEmptyOrItsValuesNull(constraints)) {
			return null;
		}
		return new Segments(constraints);
	}

	/**
	 * Defines a segment within a segmented ordering, specifying how a portion of the filtered results should be sorted. Entities matching the segment's ordering are extracted (all if no limit is set), excluded from later segments, and must have all attributes required for sorting. Nested segments are not allowed.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/segment)
	 *
	 * @see io.evitadb.api.query.order.Segment
	 */
	@SourceHash("ee321b89a0e2fabd719bc73047624ef9")
	@Nullable
	static Segment segment(@Nonnull OrderBy orderBy) {
		if (orderBy == null) {
			return null;
		}
		return new Segment(orderBy);
	}

	/**
	 * Defines a segment that sorts the current result set using the provided ordering and extracts up to the specified limit of entities. Entities selected by this segment are excluded from subsequent segments. If no limit is set, all matching entities are taken.
	 *
	 * ```evitaql
	 * segment(
	 *    orderBy(attributeNatural("score", DESC)),
	 *    limit(5)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/segment)
	 *
	 * @see io.evitadb.api.query.order.Segment
	 */
	@SourceHash("11e88b0577dba622bd32223063eceff4")
	@Nullable
	static Segment segment(
		@Nonnull OrderBy orderBy,
		@Nullable SegmentLimit limit
	) {
		if (orderBy == null) {
			return null;
		}
		return new Segment(orderBy, limit);
	}

	/**
	 * Defines a single segment within a segments container, applying an optional filter and a mandatory ordering to select and sort a portion of the result. Entities matched by this segment are excluded from subsequent segments. Nested segments are not allowed.
	 *
	 * ```evitaql
	 * segment(
	 *    entityHaving(attributeEquals("new", true)),
	 *    orderBy(attributeNatural("orderedQuantity", DESC))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/segment)
	 *
	 * @see io.evitadb.api.query.order.Segment
	 */
	@SourceHash("91d5d48f3dfeb5ab2f4598852f688c9a")
	@Nullable
	static Segment segment(
		@Nullable EntityHaving entityHaving,
		@Nonnull OrderBy orderBy
	) {
		if (orderBy == null) {
			return null;
		}
		return new Segment(entityHaving, orderBy);
	}

	/**
	 * Defines a segment within a segmented ordering, specifying an optional filter to select entities, a mandatory sorting order, and an optional limit on how many entities to extract. Entities extracted by a segment are excluded from later segments. Nested segments are not allowed.
	 *
	 * ```evitaql
	 * segment(
	 *    entityHaving(attributeEquals("new", true)),
	 *    orderBy(attributeNatural("orderedQuantity", DESC)),
	 *    limit(3)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/segment)
	 *
	 * @see io.evitadb.api.query.order.Segment
	 */
	@SourceHash("78565374f635be54fd083021e6e9d089")
	@Nullable
	static Segment segment(
		@Nullable EntityHaving entityHaving,
		@Nonnull OrderBy orderBy,
		@Nullable SegmentLimit limit
	) {
		if (orderBy == null) {
			return null;
		}
		return new Segment(entityHaving, orderBy, limit);
	}

	/**
	 * Restricts the number of entities returned within a specific segment when used inside a `segment` container, ensuring only the specified maximum count of entities are included in that segment's result set.
	 *
	 * ```evitaql
	 * segment(
	 *    orderBy(attributeNatural("orderedQuantity", DESC)),
	 *    limit(3)
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/segment#limit)
	 *
	 * @see io.evitadb.api.query.order.SegmentLimit
	 */
	@SourceHash("010463acd97c829ac6b69da7c063c1ec")
	@Nullable
	static SegmentLimit limit(
		@Nullable Integer limit
	) {
		if (limit == null) {
			return null;
		}
		return new SegmentLimit(limit);
	}

	/**
	 * Sorts entities by their primary key values in the specified order. Use with `DESC` to reverse the default ascending order; using `ASC` is redundant since entities are naturally sorted by primary key in ascending order.
	 *
	 * ```evitaql
	 * orderBy(
	 *    entityPrimaryKeyNatural(DESC)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/comparable#primary-key-natural)
	 *
	 * @see io.evitadb.api.query.order.EntityPrimaryKeyNatural
	 */
	@SourceHash("b691899b5c4752aea8e4d825d9cb7a03")
	@Nonnull
	static EntityPrimaryKeyNatural entityPrimaryKeyNatural(@Nullable OrderDirection direction) {
		return new EntityPrimaryKeyNatural(direction == null ? OrderDirection.ASC : direction);
	}

	/**
	 * Sorts entities by their primary key in the exact order specified in a preceding `entityPrimaryKeyInSet` filter. Useful for preserving custom or external ordering, such as relevancy from another system. Requires `entityPrimaryKeyInSet` in the filter.
	 *
	 * ```evitaql
	 * orderBy(
	 *    entityPrimaryKeyInFilter()
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/constant#exact-entity-primary-key-order-used-in-filter)
	 *
	 * @see io.evitadb.api.query.order.EntityPrimaryKeyInFilter
	 */
	@SourceHash("9083d8b7ef35c8ee1a7878a704f36cda")
	@Nonnull
	static EntityPrimaryKeyInFilter entityPrimaryKeyInFilter() {
		return new EntityPrimaryKeyInFilter();
	}

	/**
	 * Sorts entities by their primary keys in the exact order specified by the provided arguments. Entities with primary keys not listed will appear at the end, sorted by their primary key or by subsequent ordering constraints.
	 *
	 * ```evitaql
	 * orderBy(
	 *    entityPrimaryKeyExact(5, 1, 8)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/constant#exact-entity-primary-key-order)
	 *
	 * @see io.evitadb.api.query.order.EntityPrimaryKeyExact
	 */
	@SourceHash("434c7170d6f1944108fd377e4f3e62b7")
	@Nullable
	static EntityPrimaryKeyExact entityPrimaryKeyExact(@Nullable Integer... primaryKey) {
		final Integer[] normalizedPrimaryKey = withoutNullValues(primaryKey);
		return ArrayUtils.isEmpty(normalizedPrimaryKey) ? null : new EntityPrimaryKeyExact(normalizedPrimaryKey);
	}

	/**
	 * Sorts entities by the order of attribute values specified in a matching `attributeInSet` filter for the same attribute. Ensures output order mirrors the input array, useful for preserving external relevance or custom sort orders. Requires a single matching filter.
	 *
	 * ```evitaql
	 * orderBy(
	 *    attributeSetInFilter("code")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/constant#exact-entity-attribute-value-order-used-in-filter)
	 *
	 * @see io.evitadb.api.query.order.AttributeSetInFilter
	 */
	@SourceHash("6a1f1d0e0e14e291cd7ba46c471afafd")
	@Nullable
	static AttributeSetInFilter attributeSetInFilter(@Nonnull String attributeName) {
		requireName(attributeName, "attributeName");
		return new AttributeSetInFilter(attributeName);
	}

	/**
	 * Sorts entities by the specified attribute, returning them in the exact order of attribute values provided as arguments. Entities missing the attribute appear last, ordered by primary key or subsequent ordering constraints.
	 *
	 * ```evitaql
	 * orderBy(
	 *    attributeSetExact("code", "t-shirt", "sweater", "pants")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/constant#exact-entity-attribute-value-order)
	 *
	 * @see io.evitadb.api.query.order.AttributeSetExact
	 */
	@SourceHash("8b1007ea63c06df4430ef9501fcd2ea4")
	@Nullable
	static AttributeSetExact attributeSetExact(@Nonnull String attributeName,
		@Nullable Serializable... attributeValues) {
		requireName(attributeName, "attributeName");
		final Serializable[] normalizedAttributeValues = withoutNullValues(attributeValues);
		return ArrayUtils.isEmpty(normalizedAttributeValues) ? null :
			new AttributeSetExact(attributeName, normalizedAttributeValues);
	}

	/**
	 * Sorts entities by attributes of a specific reference (e.g., a product's brand or category), using the referenced attribute(s) as the sort key. For 1:N or hierarchical references, the default behavior picks the relevant reference for ordering, but you can override this with a reference ordering specification. Use in `orderBy` to sort by reference attributes.
	 *
	 * ```evitaql
	 * orderBy(
	 *     referenceProperty(
	 *         "brand",
	 *         attributeNatural("orderInBrand", ASC)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/reference#reference-property)
	 *
	 * @see io.evitadb.api.query.order.ReferenceProperty
	 */
	@SourceHash("356618fd59c4146cf4a0513d363dda58")
	@Nullable
	static ReferenceProperty referenceProperty(@Nonnull String referenceName,
		@Nullable OrderConstraint... constraints) {
		requireName(referenceName, "referenceName");
		if (ArrayUtils.isEmptyOrItsValuesNull(constraints)) {
			return null;
		}
		return new ReferenceProperty(referenceName, constraints);
	}

	/**
	 * Orders entities by traversing referenced hierarchical entities using the specified ordering constraints, then orders main entities by a property of the reference. Use within `referenceProperty` for hierarchical one-to-many references to control traversal mode and node order; mutually exclusive with `pickFirstByEntityProperty`.
	 *
	 * ```evitaql
	 * referenceProperty(
	 *     "categories",
	 *     traverseByEntityProperty(
	 *         attributeNatural("order", ASC)
	 *     ),
	 *     attributeNatural("orderInCategory", ASC)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/reference#traverse-by-entity-property)
	 *
	 * @see io.evitadb.api.query.order.TraverseByEntityProperty
	 */
	@SourceHash("8c7d1d69dcf7f3403769c371a17b0b7d")
	@Nonnull
	static TraverseByEntityProperty traverseByEntityProperty(@Nullable OrderConstraint... constraints) {
		if (ArrayUtils.isEmptyOrItsValuesNull(constraints)) {
			return new TraverseByEntityProperty(null, entityPrimaryKeyNatural(OrderDirection.ASC));
		} else {
			return new TraverseByEntityProperty(null, constraints);
		}
	}

	/**
	 * Specifies hierarchical traversal and ordering of referenced entities within the `referenceProperty` ordering constraint, allowing you to control whether referenced entities (e.g., categories) are traversed in depth-first or breadth-first order, and how nodes at each level are sorted. Mutually exclusive with `pickFirstByEntityProperty`; use only when you need non-default traversal or ordering for hierarchical references.
	 *
	 * ```evitaql
	 * referenceProperty(
	 *     "categories",
	 *     traverseByEntityProperty(
	 *         BREADTH_FIRST, attributeNatural("order", ASC)
	 *     ),
	 *     attributeNatural("orderInCategory", ASC)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/reference#traverse-by-entity-property)
	 *
	 * @see io.evitadb.api.query.order.TraverseByEntityProperty
	 */
	@SourceHash("e71c0898a268d5c42952a0faeaef1cc8")
	@Nonnull
	static TraverseByEntityProperty traverseByEntityProperty(@Nullable TraversalMode traversalMode, @Nullable OrderConstraint... constraints) {
		if (ArrayUtils.isEmptyOrItsValuesNull(constraints)) {
			return new TraverseByEntityProperty(traversalMode, entityPrimaryKeyNatural(OrderDirection.ASC));
		} else {
			return new TraverseByEntityProperty(traversalMode, constraints);
		}
	}

	/**
	 * Defines which reference (in a 1:N relationship) should be picked for ordering by evaluating the provided ordering constraints and selecting the first matching reference; use within `referenceProperty` to control which reference's property drives the sort. Mutually exclusive with `traverseByEntityProperty`. If omitted, a default pick order is applied.
	 *
	 * ```evitaql
	 * orderBy(
	 *     referenceProperty(
	 *         "stocks",
	 *         pickFirstByEntityProperty(
	 *             attributeSetExact("code", "main")
	 *         ),
	 *         attributeNatural("quantityOnStock", DESC)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/reference#pick-first-by-entity-property)
	 *
	 * @see io.evitadb.api.query.order.PickFirstByEntityProperty
	 */
	@SourceHash("3132d4882fab6122342e89df1e1fcbf2")
	@Nullable
	static PickFirstByEntityProperty pickFirstByEntityProperty(@Nullable OrderConstraint... constraints) {
		if (ArrayUtils.isEmptyOrItsValuesNull(constraints)) {
			return null;
		}
		return new PickFirstByEntityProperty(constraints);
	}

	/**
	 * Changes the ordering context within a reference to use attributes of the referenced entity, rather than the reference itself. Useful for sorting referenced entities (e.g., sorting `Parameter` references in `Product` by the `Parameter`'s attributes).
	 *
	 * ```evitaql
	 * referenceContent(
	 *     "parameterValues",
	 *     orderBy(
	 *         entityProperty(
	 *             attributeNatural("code", DESC)
	 *         )
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/reference#entity-property)
	 *
	 * @see io.evitadb.api.query.order.EntityProperty
	 */
	@SourceHash("c920b45a7b68d6d4e134008303c8fd80")
	@Nullable
	static EntityProperty entityProperty(@Nullable OrderConstraint... constraints) {
		if (ArrayUtils.isEmptyOrItsValuesNull(constraints)) {
			return null;
		}
		return new EntityProperty(constraints);
	}

	/**
	 * Orders referenced entities by attributes of their entity group, rather than the reference itself, when used within `referenceContent`. Useful for sorting references (e.g., product parameters) by group-level attributes like priority or name.
	 *
	 * ```evitaql
	 * orderBy(
	 *     entityGroupProperty(
	 *         attributeNatural("code", DESC)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/reference#entity-group-property)
	 *
	 * @see io.evitadb.api.query.order.EntityGroupProperty
	 */
	@SourceHash("61dd542e9ee76592359fe6ea52a8654f")
	@Nullable
	static EntityGroupProperty entityGroupProperty(@Nullable OrderConstraint... constraints) {
		if (ArrayUtils.isEmptyOrItsValuesNull(constraints)) {
			return null;
		}
		return new EntityGroupProperty(constraints);
	}

	/**
	 * Sorts entities by the natural order of a specified attribute (numeric, alphabetical, or temporal), with optional direction (default is ascending). For localized attributes, use `EntityLocaleEquals` in filtering to ensure locale-aware sorting. Note: evitaDB sorts by the first attribute, then by the second only for entities missing the first; otherwise, ties are broken by primary key. For conventional multi-attribute sorting, use a sortable attribute compound.
	 *
	 * ```evitaql
	 * orderBy(
	 *     attributeNatural("orderedQuantity", DESC)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/comparable#attribute-natural)
	 *
	 * @see io.evitadb.api.query.order.AttributeNatural
	 */
	@SourceHash("e9d7a89057f43a84b3cad06f973a10db")
	@Nullable
	static AttributeNatural attributeNatural(@Nonnull String attributeName) {
		requireName(attributeName, "attributeName");
		return new AttributeNatural(attributeName);
	}

	/**
	 * Sorts entities by the natural order of a specified attribute (numeric, alphabetical, or temporal), using the given direction (default: ascending). For localized attributes, set the locale in `filterBy` to ensure culturally correct ordering. Note: evitaDB sorts by the first attribute, then by the second only for entities missing the first; ties are broken by primary key. For classic multi-attribute sorting, use a sortable attribute compound.
	 *
	 * ```evitaql
	 * orderBy(
	 *     attributeNatural("orderedQuantity", DESC)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/comparable#attribute-natural)
	 *
	 * @see io.evitadb.api.query.order.AttributeNatural
	 */
	@SourceHash("a0b33739a421e2d55adbf46e981b4877")
	@Nullable
	static AttributeNatural attributeNatural(@Nonnull String attributeName, @Nullable OrderDirection orderDirection) {
		requireName(attributeName, "attributeName");
		return new AttributeNatural(attributeName, orderDirection == null ? OrderDirection.ASC : orderDirection);
	}

	/**
	 * Sorts entities by their selling price in natural numeric order, using the price variant (with or without tax) as determined by the query's `PriceType` requirement (default is price with tax). Requires relevant price constraints in `filterBy`.
	 *
	 * ```evitaql
	 * orderBy(
	 *   priceNatural()
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/price#price-natural)
	 *
	 * @see io.evitadb.api.query.order.PriceNatural
	 */
	@SourceHash("0aa7ee0b51900fc2dcd26192398666e9")
	@Nonnull
	static PriceNatural priceNatural() {
		return new PriceNatural();
	}

	/**
	 * Sorts entities by their selling price in natural numeric order, using the specified order direction. The price variant (with or without tax) is determined by the query's `PriceType` requirement (default is with tax). Only works if price constraints are present in `filterBy`.
	 *
	 * ```evitaql
	 * orderBy(
	 *   priceNatural(DESC)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/price#price-natural)
	 *
	 * @see io.evitadb.api.query.order.PriceNatural
	 */
	@SourceHash("a3be17c5d443cada152dd3728822645d")
	@Nonnull
	static PriceNatural priceNatural(@Nullable OrderDirection orderDirection) {
		return new PriceNatural(orderDirection == null ? OrderDirection.ASC : orderDirection);
	}

	/**
	 * Sorts entities by the difference between their selling price and a discounted price, calculated using the specified price lists. If the discount is negative, it's treated as zero. By default, products with the largest discount appear first (DESC order).
	 * ```evitaql
	 * priceDiscount("discount", "basic")
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/price#price-discount)
	 *
	 * @see io.evitadb.api.query.order.PriceDiscount
	 */
	@SourceHash("0dbfe4c6fd3c42e705793b0d634779d0")
	@Nullable
	static PriceDiscount priceDiscount(@Nullable String... inPriceLists) {
		final String[] normalizedInPriceLists = withoutUndefinedPriceLists(inPriceLists);
		return ArrayUtils.isEmpty(normalizedInPriceLists) ? null :
			new PriceDiscount(OrderDirection.DESC, normalizedInPriceLists);
	}

	/**
	 * Sorts entities by the difference between their selling price and a discounted price, using the specified price lists to calculate the discount. Negative discounts are treated as zero. Defaults to descending order, showing largest discounts first. Price type (with/without tax) follows the query's `PriceType` requirement.
	 *
	 * ```evitaql
	 * priceDiscount(DESC, "discount", "basic")
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/price#price-discount)
	 *
	 * @see io.evitadb.api.query.order.PriceDiscount
	 */
	@SourceHash("d68099450336267c627e01285db8063d")
	@Nullable
	static PriceDiscount priceDiscount(@Nullable OrderDirection orderDirection, @Nullable String... inPriceLists) {
		final String[] normalizedInPriceLists = withoutUndefinedPriceLists(inPriceLists);
		return ArrayUtils.isEmpty(normalizedInPriceLists) ? null :
			new PriceDiscount(orderDirection == null ? OrderDirection.DESC : orderDirection, normalizedInPriceLists);
	}

	/**
	 * Returns entities in a random order each time the query is executed, providing a unique listing for every access. This constraint takes no arguments and is ideal for scenarios where non-deterministic ordering is desired.
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/random#random)
	 *
	 * @see io.evitadb.api.query.order.Random
	 */
	@SourceHash("f57d2b98984cbeee101560332400d38c")
	@Nonnull
	static Random random() {
		return Random.INSTANCE;
	}

	/**
	 * Returns entities in a random order, but the randomness is deterministic for the given seed value—useful for testing or consistent user experiences. Each identical seed produces the same randomized order, ensuring repeatability.
	 *
	 * ```evitaql
	 * randomWithSeed(42)
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/random#random)
	 *
	 * @see io.evitadb.api.query.order.Random
	 */
	@SourceHash("b96c191052357c30f598355a6d38ef9f")
	@Nonnull
	static Random randomWithSeed(long seed) {
		return new Random(seed);
	}

	/*
		requirement
	 */

	/**
	 * Defines the `require` section of an EvitaQL query, specifying side computations like paging, entity content, or extra results to enrich the response without affecting matched entities or their order. Each constraint type can appear only once; an empty `require` is ignored.
	 *
	 * ```evitaql
	 * require(
	 *     page(1, 20),
	 *     entityFetch(attributeContentAll())
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/basics#require)
	 *
	 * @see io.evitadb.api.query.require.Require
	 */
	@SourceHash("db5d4a8eac114a0340c185cba2c6d0fb")
	@Nullable
	static Require require(@Nullable RequireConstraint... constraints) {
		if (ArrayUtils.isEmptyOrItsValuesNull(constraints)) {
			return null;
		}
		return new Require(constraints);
	}

	/**
	 * Restricts the enclosed require constraints to apply only when processing entities in the specified scope (LIVE or ARCHIVED), ensuring requirements incompatible with a scope (e.g., facetSummary in ARCHIVED) are safely ignored there. At least one unique RequireConstraint is required; nesting is disallowed within the same evaluation context.
	 *
	 * ```evitaql
	 * require(
	 *    inScope(LIVE, facetSummary())
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/require/behavioral#in-scope)
	 *
	 * @see io.evitadb.api.query.require.RequireInScope
	 */
	@SourceHash("59275d1389fd3e6825ba0099f76f30c2")
	@Nullable
	static RequireInScope inScope(@Nonnull Scope scope, @Nullable RequireConstraint... constraints) {
		Assert.notNull(scope, "Argument `scope` must not be null: it has no default.");
		return ArrayUtils.isEmptyOrItsValuesNull(constraints) ? null : new RequireInScope(scope, constraints);
	}

	/**
	 * Computes a value-distribution histogram for one or more numeric filterable attributes, returning the result in the extra-results section for use in range-filter UI widgets (e.g., price sliders). Only entities matching the mandatory filter (outside any `userFilter`) are considered, ensuring the histogram remains useful even as users narrow ranges. At least one attribute name must be provided; the default bucket behavior is `STANDARD`.  
	 * ```evitaql
	 * attributeHistogram(20, "price")
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/histogram#attribute-histogram)
	 *
	 * @see io.evitadb.api.query.require.AttributeHistogram
	 */
	@SourceHash("e7fb93a1d4b42b49a43cf1e477557c99")
	@Nullable
	static AttributeHistogram attributeHistogram(int requestedBucketCount, @Nullable String... attributeName) {
		requireNonNullItems(attributeName, "attributeName");
		if (ArrayUtils.isEmptyOrItsValuesNull(attributeName)) {
			return null;
		}
		return new AttributeHistogram(requestedBucketCount, attributeName);
	}

	/**
	 * Computes a value-distribution histogram for one or more numeric filterable attributes, returning the result in extra-results for use in UI range filters (e.g., price sliders). Only entities matching the mandatory filter are included; user-narrowed ranges do not restrict the histogram, preventing dead ends. Supports configurable bucket count, boundary behavior, and multiple attributes. Not applicable if no attribute names are provided.
	 *
	 * ```evitaql
	 * attributeHistogram(20, EQUALIZED, "price")
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/histogram#attribute-histogram)
	 *
	 * @see io.evitadb.api.query.require.AttributeHistogram
	 */
	@SourceHash("debde7d476a48e65165c33a8ccc974b6")
	@Nullable
	static AttributeHistogram attributeHistogram(int requestedBucketCount, @Nullable HistogramBehavior behavior,
		@Nullable String... attributeName) {
		requireNonNullItems(attributeName, "attributeName");
		if (ArrayUtils.isEmptyOrItsValuesNull(attributeName)) {
			return null;
		}
		return new AttributeHistogram(requestedBucketCount, behavior, attributeName);
	}

	/**
	 * Requests computation of histogram statistics for one or more named histogram indexes within a reference summary, producing the specified number of equal-width buckets (including empty ones) for each index. Use as a child of `referenceSummary` or `referenceSummaryOfReference` to retrieve histogram data alongside facet statistics. At least one index name must be provided; otherwise, the constraint is ignored.
	 *
	 * ```evitaql
	 * referenceSummary(
	 *     COUNTS,
	 *     histogramStatistics(20, "priceIndex")
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#histogram-statistics)
	 *
	 * @see io.evitadb.api.query.require.ReferenceHistogramStatistics
	 */
	@SourceHash("11258c3be38da1e54865fa8e3aed91fc")
	@Nullable
	static ReferenceHistogramStatistics histogramStatistics(int requestedBucketCount, @Nullable String... indexNames) {
		requireNonNullItems(indexNames, "indexNames");
		if (ArrayUtils.isEmptyOrItsValuesNull(indexNames)) {
			return null;
		}
		return new ReferenceHistogramStatistics(requestedBucketCount, indexNames);
	}

	/**
	 * Requests computation of histogram statistics for one or more named histogram indexes within a reference summary, producing the specified number of buckets per index. The `behavior` argument controls bucket boundaries and handling of empty buckets, supporting modes like `STANDARD`, `OPTIMIZED`, and `EQUALIZED`. At least one index name must be provided; otherwise, the constraint is ignored.
	 *
	 * ```evitaql
	 * histogramStatistics(10, OPTIMIZED, "ratingIndex", "weightIndex")
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#histogram-statistics)
	 *
	 * @see io.evitadb.api.query.require.ReferenceHistogramStatistics
	 */
	@SourceHash("577fe34dd045212340f8bd33bc379431")
	@Nullable
	static ReferenceHistogramStatistics histogramStatistics(int requestedBucketCount,
		@Nullable HistogramBehavior behavior, @Nullable String... indexNames) {
		requireNonNullItems(indexNames, "indexNames");
		if (ArrayUtils.isEmptyOrItsValuesNull(indexNames)) {
			return null;
		}
		return new ReferenceHistogramStatistics(requestedBucketCount, behavior, indexNames);
	}

	/**
	 * Requests computation of histogram statistics for specified histogram indexes within a reference summary, producing the given number of buckets per index and optionally fetching referenced entities with the provided `EntityFetch` constraint. At least one index name must be supplied; otherwise, the constraint is ignored. The default bucket behavior is `STANDARD`, generating equal-width buckets, including empty ones.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#histogram-statistics)
	 *
	 * @see io.evitadb.api.query.require.ReferenceHistogramStatistics
	 */
	@SourceHash("d177ff7205a36c2c27835c74872e4ff4")
	@Nullable
	static ReferenceHistogramStatistics histogramStatistics(int requestedBucketCount, @Nullable EntityFetch entityFetch,
		@Nullable String... indexNames) {
		requireNonNullItems(indexNames, "indexNames");
		if (ArrayUtils.isEmptyOrItsValuesNull(indexNames)) {
			return null;
		}
		return new ReferenceHistogramStatistics(requestedBucketCount, null, entityFetch, indexNames);
	}

	/**
	 * Requests computation of histogram statistics for one or more named histogram indexes within a reference summary, producing the specified number of buckets using the chosen behavior (e.g., `STANDARD`, `OPTIMIZED`, `EQUALIZED`). Optionally fetches referenced entities alongside histogram data. At least one index name must be provided; otherwise, the constraint is ignored.
	 *
	 * ```evitaql
	 * histogramStatistics(20, STANDARD, entityFetch(attributeContent()), "priceIndex")
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#histogram-statistics)
	 *
	 * @see io.evitadb.api.query.require.ReferenceHistogramStatistics
	 */
	@SourceHash("42dc234624b7f42ff8f2bc1215edf955")
	@Nullable
	static ReferenceHistogramStatistics histogramStatistics(
		int requestedBucketCount,
		@Nullable HistogramBehavior behavior,
		@Nullable EntityFetch entityFetch,
		@Nullable String... indexNames
	) {
		requireNonNullItems(indexNames, "indexNames");
		if (ArrayUtils.isEmptyOrItsValuesNull(indexNames)) {
			return null;
		}
		return new ReferenceHistogramStatistics(requestedBucketCount, behavior, entityFetch, indexNames);
	}

	/**
	 * Computes a histogram of entity sale prices, dividing the price range into the specified number of buckets for use in UI elements like price sliders. Only entities passing the main filter are included; userFilter price/attribute constraints are ignored to avoid narrowing the range. Uses price with tax unless a different price type is specified. Defaults to `STANDARD` behavior, producing the exact number of equal-width buckets.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/histogram#price-histogram)
	 *
	 * @see io.evitadb.api.query.require.PriceHistogram
	 */
	@SourceHash("654ad9a20123b306795d639859288efb")
	@Nonnull
	static PriceHistogram priceHistogram(int requestedBucketCount) {
		return new PriceHistogram(requestedBucketCount);
	}

	/**
	 * Computes a value-distribution histogram of entity sale prices, returning it in extra-results for powering UI elements like price sliders. Buckets are based on entities passing the mandatory filter (ignoring userFilter price/attribute ranges). Choose bucket count and behavior (standard, optimized, or equalized) to control distribution and density. Price type (with/without tax) follows the `priceType` constraint or defaults to with-tax.
	 *
	 * ```evitaql
	 * priceHistogram(20, OPTIMIZED)
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/histogram#price-histogram)
	 *
	 * @see io.evitadb.api.query.require.PriceHistogram
	 */
	@SourceHash("6adc2983a68dd13c6ffb7fbe2440224f")
	@Nonnull
	static PriceHistogram priceHistogram(int requestedBucketCount, @Nullable HistogramBehavior behavior) {
		return new PriceHistogram(requestedBucketCount, behavior);
	}

	/**
	 * Overrides the default OR logic between facets within a group for the specified reference, requiring all selected facets in the targeted groups (filtered by the provided group entity filter) to match simultaneously (AND). This makes facet selection more restrictive.
	 *
	 * ```evitaql
	 * require(
	 *     facetGroupsConjunction(
	 *         "parameterValues",
	 *         filterBy(attributeInSet("code", "color"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-conjunction)
	 *
	 * @see io.evitadb.api.query.require.FacetGroupsConjunction
	 */
	@SourceHash("a3874513915da07db96ac36c2c74508a")
	@Nullable
	static FacetGroupsConjunction facetGroupsConjunction(@Nonnull String referenceName, @Nullable FilterBy filterBy) {
		requireName(referenceName, "referenceName");
		return new FacetGroupsConjunction(referenceName, filterBy);
	}

	/**
	 * Overrides the default OR logic between facets within or across groups for a given reference, enforcing AND (conjunction) at the specified relation level. This means all selected facets must match simultaneously, making queries more restrictive and affecting facet impact predictions.
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-conjunction)
	 *
	 * @see io.evitadb.api.query.require.FacetGroupsConjunction
	 */
	@SourceHash("bdc69fa75d4a6a4a79785a5af218a77b")
	@Nullable
	static FacetGroupsConjunction facetGroupsConjunction(@Nonnull String referenceName,
		@Nullable FacetGroupRelationLevel facetGroupRelationLevel) {
		requireName(referenceName, "referenceName");
		return new FacetGroupsConjunction(referenceName, facetGroupRelationLevel, null);
	}

	/**
	 * Overrides the default OR logic between facets in the specified reference, enforcing AND (conjunction) at the chosen group relation level. Use to require all selected facets (e.g., colors) within or across groups to match simultaneously, making queries more restrictive.
	 *
	 * ```evitaql
	 * require(
	 *     facetGroupsConjunction(
	 *         "parameterValues",
	 *         WITH_DIFFERENT_FACETS_IN_GROUP,
	 *         filterBy(attributeInSet("code", "color"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-conjunction)
	 *
	 * @see io.evitadb.api.query.require.FacetGroupsConjunction
	 */
	@SourceHash("6b107b1fe3d5d85c9128c1a5308f66da")
	@Nullable
	static FacetGroupsConjunction facetGroupsConjunction(@Nonnull String referenceName,
		@Nullable FacetGroupRelationLevel facetGroupRelationLevel, @Nullable FilterBy filterBy) {
		requireName(referenceName, "referenceName");
		return new FacetGroupsConjunction(referenceName, facetGroupRelationLevel, filterBy);
	}

	/**
	 * Overrides the default OR logic between facets within a group for the specified reference, enforcing AND (conjunction) so that only entities matching all selected facets in the group are returned. Useful for stricter filtering where all chosen facets must be present. [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-conjunction)
	 *
	 * @see io.evitadb.api.query.require.FacetGroupsConjunction
	 */
	@SourceHash("89f57658f87561389a844c4c53cad9fd")
	@Nullable
	static FacetGroupsConjunction facetGroupsConjunction(@Nonnull String referenceName) {
		requireName(referenceName, "referenceName");
		return new FacetGroupsConjunction(referenceName, null);
	}

	/**
	 * Overrides the default AND logic between different facet groups of the specified reference, applying OR (disjunction) instead for groups matching the given filter. Entities matching any targeted group will be included, expanding result sets compared to the default.  
	 * ```evitaql
	 * facetGroupsDisjunction(
	 *     "parameterValues",
	 *     filterBy(attributeInSet("code", "color", "tags"))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-disjunction)
	 *
	 * @see io.evitadb.api.query.require.FacetGroupsDisjunction
	 */
	@SourceHash("73eed23390309e4ee0bf16376bfd48ad")
	@Nullable
	static FacetGroupsDisjunction facetGroupsDisjunction(@Nonnull String referenceName, @Nullable FilterBy filterBy) {
		requireName(referenceName, "referenceName");
		return new FacetGroupsDisjunction(referenceName, filterBy);
	}

	/**
	 * Overrides the default logical AND between different facet groups of the specified reference, applying logical OR (disjunction) at the chosen relation level. This allows entities to match if they satisfy any targeted group, not all, expanding result sets. [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-disjunction)
	 *
	 * @see io.evitadb.api.query.require.FacetGroupsDisjunction
	 */
	@SourceHash("d10bd3845949f63dd52ebe894d84b668")
	@Nullable
	static FacetGroupsDisjunction facetGroupsDisjunction(@Nonnull String referenceName,
		@Nullable FacetGroupRelationLevel facetGroupRelationLevel) {
		requireName(referenceName, "referenceName");
		return new FacetGroupsDisjunction(referenceName, facetGroupRelationLevel, null);
	}

	/**
	 * Overrides the default AND logic between different facet groups of a reference, applying OR (disjunction) at the specified relation level and for optionally filtered groups. This makes entities matching any targeted group sufficient, expanding result sets versus the default.  
	 * ```evitaql
	 * facetGroupsDisjunction(
	 *     "parameterValues",
	 *     WITH_DIFFERENT_GROUPS,
	 *     filterBy(attributeInSet("code", "color", "tags"))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-disjunction)
	 *
	 * @see io.evitadb.api.query.require.FacetGroupsDisjunction
	 */
	@SourceHash("36e4c211c96381e665044193b51a1be2")
	@Nullable
	static FacetGroupsDisjunction facetGroupsDisjunction(@Nonnull String referenceName,
		@Nullable FacetGroupRelationLevel facetGroupRelationLevel, @Nullable FilterBy filterBy) {
		requireName(referenceName, "referenceName");
		return new FacetGroupsDisjunction(referenceName, facetGroupRelationLevel, filterBy);
	}

	/**
	 * Overrides the default AND logic between different facet groups of the specified reference, applying OR (disjunction) instead. This means entities matching any selected group (e.g., *blue* **or** *large*) are returned, rather than requiring all. Applies only to the given reference; other references still combine with AND.  
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-disjunction)
	 *
	 * @see io.evitadb.api.query.require.FacetGroupsDisjunction
	 */
	@SourceHash("527f2d4129d05881a2440f82e42322ed")
	@Nullable
	static FacetGroupsDisjunction facetGroupsDisjunction(@Nonnull String referenceName) {
		requireName(referenceName, "referenceName");
		return new FacetGroupsDisjunction(referenceName, null);
	}

	/**
	 * Inverts facet selection for specified groups of a reference, returning entities that do **not** have the selected facet(s). Use to exclude unwanted characteristics (e.g., "exclude discontinued items"). Negation applies to groups matching the `filterBy` constraint; predicted result counts reflect the exclusion and are typically much larger than inclusive facet counts.
	 *
	 * ```evitaql
	 * facetGroupsNegation(
	 *     "parameterValues",
	 *     filterBy(attributeInSet("code", "ram-memory"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-negation)
	 *
	 * @see io.evitadb.api.query.require.FacetGroupsNegation
	 */
	@SourceHash("cdda2305fab0a379b1b2aef26d15768f")
	@Nullable
	static FacetGroupsNegation facetGroupsNegation(@Nonnull String referenceName, @Nullable FilterBy filterBy) {
		requireName(referenceName, "referenceName");
		return new FacetGroupsNegation(referenceName, filterBy);
	}

	/**
	 * Inverts facet selection for specified groups of a reference, returning entities that do **not** have the selected facet (logical NOT). Use to exclude items with unwanted characteristics. Negation can apply at facet or group level, and can be targeted to specific groups via a filter. Affects both filtering and facet impact predictions, often resulting in much larger predicted counts for negated facets.
	 *
	 * ```evitaql
	 * facetGroupsNegation(
	 *     "parameterValues",
	 *     WITH_DIFFERENT_GROUPS,
	 *     filterBy(attributeInSet("code", "ram-memory"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-negation)
	 *
	 * @see io.evitadb.api.query.require.FacetGroupsNegation
	 */
	@SourceHash("b987423ddd533743e88dcb9d38e67be8")
	@Nullable
	static FacetGroupsNegation facetGroupsNegation(@Nonnull String referenceName,
		@Nullable FacetGroupRelationLevel facetGroupRelationLevel, @Nullable FilterBy filterBy) {
		requireName(referenceName, "referenceName");
		return new FacetGroupsNegation(referenceName, facetGroupRelationLevel, filterBy);
	}

	/**
	 * Inverts facet selection for specified groups of a faceted reference, returning entities that do **not** have the selected facet (logical NOT). The `facetGroupRelationLevel` argument controls whether negation applies within groups or across groups. Useful for queries like "exclude products with this property" and impacts facet summary predictions by showing how many results remain after exclusion.
	 *
	 * ```evitaql
	 * require(
	 *     facetGroupsNegation("parameterValues", WITH_DIFFERENT_GROUPS)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-negation)
	 *
	 * @see io.evitadb.api.query.require.FacetGroupsNegation
	 */
	@SourceHash("c196cc0af05c5164e7ef9325e88c4b35")
	@Nullable
	static FacetGroupsNegation facetGroupsNegation(@Nonnull String referenceName,
		@Nullable FacetGroupRelationLevel facetGroupRelationLevel) {
		requireName(referenceName, "referenceName");
		return new FacetGroupsNegation(referenceName, facetGroupRelationLevel, null);
	}

	/**
	 * Inverts facet selection for the specified reference, returning entities that do **not** reference the selected facets—useful for exclusion filters (e.g., "exclude discontinued items"). Negation applies to all groups of the reference by default.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-negation)
	 *
	 * @see io.evitadb.api.query.require.FacetGroupsNegation
	 */
	@SourceHash("e4b491e077b6b572a0759dc88311fd79")
	@Nullable
	static FacetGroupsNegation facetGroupsNegation(@Nonnull String referenceName) {
		requireName(referenceName, "referenceName");
		return new FacetGroupsNegation(referenceName, null);
	}

	/**
	 * Marks facet options in groups of the specified reference as mutually exclusive, so only one facet per group can be selected at a time. This alters impact predictions to reflect "replace" rather than "add" logic for those groups, ideal for radio button UI. Use a filter to target specific groups.
	 *
	 * ```evitaql
	 * facetGroupsExclusivity(
	 *     "parameterValues",
	 *     filterBy(attributeInSet("code", "ram-memory"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-exclusivity)
	 *
	 * @see io.evitadb.api.query.require.FacetGroupsExclusivity
	 */
	@SourceHash("90be13a342915b94280a1dc9bbccd569")
	@Nullable
	static FacetGroupsExclusivity facetGroupsExclusivity(@Nonnull String referenceName, @Nullable FilterBy filterBy) {
		requireName(referenceName, "referenceName");
		return new FacetGroupsExclusivity(referenceName, filterBy);
	}

	/**
	 * Marks facet options in selected groups of the given reference as mutually exclusive, so only one can be chosen at a time. This changes impact predictions to show the result count as if the current selection was replaced, not added. Use for radio-button style UI in facets.
	 *
	 * ```evitaql
	 * facetGroupsExclusivity(
	 *     "parameterValues",
	 *     WITH_DIFFERENT_FACETS_IN_GROUP,
	 *     filterBy(attributeInSet("code", "ram-memory"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-exclusivity)
	 *
	 * @see io.evitadb.api.query.require.FacetGroupsExclusivity
	 */
	@SourceHash("262638a488fef9fa5aa0b2b32c94fe6b")
	@Nullable
	static FacetGroupsExclusivity facetGroupsExclusivity(@Nonnull String referenceName,
		@Nullable FacetGroupRelationLevel facetGroupRelationLevel, @Nullable FilterBy filterBy) {
		requireName(referenceName, "referenceName");
		return new FacetGroupsExclusivity(referenceName, facetGroupRelationLevel, filterBy);
	}

	/**
	 * Declares that facet options in the specified reference's groups are mutually exclusive at the chosen relation level, so only one facet may be selected at a time per group or across groups. This affects impact predictions in facet summaries, making them reflect "replacement" rather than "addition" semantics. Use for facets best represented as radio buttons in the UI.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-exclusivity)
	 *
	 * @see io.evitadb.api.query.require.FacetGroupsExclusivity
	 */
	@SourceHash("d1b8d3224e7ce06f7512d07800d2a5b1")
	@Nullable
	static FacetGroupsExclusivity facetGroupsExclusivity(@Nonnull String referenceName,
		@Nullable FacetGroupRelationLevel facetGroupRelationLevel) {
		requireName(referenceName, "referenceName");
		return new FacetGroupsExclusivity(referenceName, facetGroupRelationLevel, null);
	}

	/**
	 * Marks all facet groups of the specified reference as mutually exclusive, so only one facet can be selected at a time within each group. This changes impact predictions to reflect replacing the current selection with a new one, ideal for radio button UI. [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-exclusivity)
	 *
	 * @see io.evitadb.api.query.require.FacetGroupsExclusivity
	 */
	@SourceHash("6f9d77baeecfe25986dd6ddb9be910ad")
	@Nullable
	static FacetGroupsExclusivity facetGroupsExclusivity(@Nonnull String referenceName) {
		requireName(referenceName, "referenceName");
		return new FacetGroupsExclusivity(referenceName, null);
	}

	/**
	 * Sets default logical relation types for combining selected facets within the same group and across different groups of the same reference, affecting all faceted references in the query unless overridden by per-group constraints. Both arguments are required; null uses built-in defaults (OR within group, AND across groups). Scope is per-query.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummary(IMPACT),
	 *     facetCalculationRules(CONJUNCTION, DISJUNCTION)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-calculation-rules)
	 *
	 * @see io.evitadb.api.query.require.FacetCalculationRules
	 */
	@SourceHash("de0c7d21e367fb55279d7738e4c151dc")
	@Nonnull
	static FacetCalculationRules facetCalculationRules(@Nullable FacetRelationType facetsWithSameGroup, @Nullable FacetRelationType facetsWithDifferentGroups) {
		return new FacetCalculationRules(facetsWithSameGroup, facetsWithDifferentGroups);
	}

	/**
	 * Computes hierarchical data structures from the queried entity type itself (not from a referenced hierarchy), enabling navigation menus, breadcrumbs, or tree views for entities like categories. Requires at least one sub-constraint (e.g., fromRoot, fromNode, children, parents, siblings) to specify the hierarchy portion to compute. Optionally accepts an orderBy constraint to control node sorting; if omitted, natural order is used. Not applicable without sub-constraints.
	 *
	 * ```evitaql
	 * require(
	 *     hierarchyOfSelf(
	 *         fromRoot(
	 *             entityFetch(attributeContent("name"))
	 *         )
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#hierarchy-of-self)
	 *
	 * @see io.evitadb.api.query.require.HierarchyOfSelf
	 */
	@SourceHash("9fa475be4ba60dd896faed799a59de59")
	@Nullable
	static HierarchyOfSelf hierarchyOfSelf(@Nullable HierarchyRequireConstraint... requirement) {
		return ArrayUtils.isEmptyOrItsValuesNull(requirement) ? null : new HierarchyOfSelf(null, requirement);
	}

	/**
	 * Computes hierarchical data structures from the queried entity type itself (not from referenced entities), enabling navigation menus, breadcrumbs, or tree views for the entity’s own hierarchy. Requires at least one sub-constraint to specify which part of the tree to fetch; can include an `orderBy` to control node ordering. Not applicable if no sub-constraint is provided.
	 *
	 * ```evitaql
	 * require(
	 *     hierarchyOfSelf(
	 *         orderBy(attributeNatural("order")),
	 *         fromRoot(
	 *             "megaMenu",
	 *             entityFetch(attributeContent("code", "name"))
	 *         )
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#hierarchy-of-self)
	 *
	 * @see io.evitadb.api.query.require.HierarchyOfSelf
	 */
	@SourceHash("e6631cd06373e6da8bc87fe25365ef0e")
	@Nullable
	static HierarchyOfSelf hierarchyOfSelf(
		@Nullable OrderBy orderBy,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		return ArrayUtils.isEmptyOrItsValuesNull(requirement) ? null : new HierarchyOfSelf(orderBy, requirement);
	}

	/**
	 * Computes hierarchy data structures for an entity type referenced by the queried entity, enabling navigation or aggregation over related hierarchies (e.g., building a category tree for products via the `categories` reference). Supports multiple references, empty node handling, and custom traversal via sub-constraints.
	 *
	 * ```evitaql
	 * require(
	 *     hierarchyOfReference(
	 *         "categories",
	 *         fromRoot(
	 *             "megaMenu",
	 *             entityFetch(attributeContent("code"))
	 *         )
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#hierarchy-of-reference)
	 *
	 * @see io.evitadb.api.query.require.HierarchyOfReference
	 */
	@SourceHash("df49e477646905850447f4ffa1f11f8e")
	@Nullable
	static HierarchyOfReference hierarchyOfReference(
		@Nonnull String referenceName,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		requireName(referenceName, "referenceName");
		return hierarchyOfReference(referenceName, null, null, requirement);
	}

	/**
	 * Computes hierarchical data structures from a referenced hierarchical entity type (e.g., categories for products) via the specified reference name(s). Allows custom ordering of hierarchy levels and supports flexible traversal via sub-constraints. Useful for navigation trees or category menus based on referenced hierarchies.
	 *
	 * ```evitaql
	 * require(
	 *     hierarchyOfReference(
	 *         "categories",
	 *         orderBy(attributeNatural("name", ASC)),
	 *         fromRoot(...)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#hierarchy-of-reference)
	 *
	 * @see io.evitadb.api.query.require.HierarchyOfReference
	 */
	@SourceHash("728ef2c1e2a8379eeac29210e8852a70")
	@Nullable
	static HierarchyOfReference hierarchyOfReference(
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		requireName(referenceName, "referenceName");
		return hierarchyOfReference(referenceName, null, orderBy, requirement);
	}

	/**
	 * Computes hierarchical data structures from an entity type referenced by the queried entity, enabling navigation or aggregation over related hierarchies (e.g., building a category tree for products via the `categories` reference). Allows specifying how empty nodes are treated (pruned or retained) and supports custom traversal or sorting via sub-constraints. Can be used multiple times per query for different references or traversal needs.
	 *
	 * ```evitaql
	 * require(
	 *     hierarchyOfReference(
	 *         "categories",
	 *         LEAVE_EMPTY,
	 *         fromRoot(
	 *             entityFetch(attributeContent("code"))
	 *         )
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#hierarchy-of-reference)
	 *
	 * @see io.evitadb.api.query.require.HierarchyOfReference
	 */
	@SourceHash("00bb2db61bda16efc6b0ac11e36b2157")
	@Nullable
	static HierarchyOfReference hierarchyOfReference(
		@Nonnull String referenceName,
		@Nullable EmptyHierarchicalEntityBehaviour emptyHierarchicalEntityBehaviour,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		requireName(referenceName, "referenceName");
		return ArrayUtils.isEmptyOrItsValuesNull(requirement) ?
			null :
			new HierarchyOfReference(
				referenceName,
				ofNullable(emptyHierarchicalEntityBehaviour).orElse(EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY),
				requirement
			);
	}

	/**
	 * Computes hierarchy data structures for a hierarchical entity type referenced by the queried entity, such as building a category tree for products via a named reference. Supports customizing empty node handling, ordering, and traversal strategy. Can be used multiple times per query for different references.
	 *
	 * ```evitaql
	 * require(
	 *     hierarchyOfReference(
	 *         "categories",
	 *         REMOVE_EMPTY,
	 *         orderBy(attributeNatural("name", ASC)),
	 *         fromRoot(
	 *             entityFetch(attributeContent("code"))
	 *         )
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#hierarchy-of-reference)
	 *
	 * @see io.evitadb.api.query.require.HierarchyOfReference
	 */
	@SourceHash("cb2bf55e76e087929aae8733cf6ff90a")
	@Nullable
	static HierarchyOfReference hierarchyOfReference(
		@Nonnull String referenceName,
		@Nullable EmptyHierarchicalEntityBehaviour emptyHierarchicalEntityBehaviour,
		@Nullable OrderBy orderBy,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		requireName(referenceName, "referenceName");
		return ArrayUtils.isEmptyOrItsValuesNull(requirement) ?
			null :
			new HierarchyOfReference(
				referenceName,
				ofNullable(emptyHierarchicalEntityBehaviour).orElse(EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY),
				orderBy,
				requirement
			);
	}

	/**
	 * Computes hierarchical data structures for entities referenced by the queried entity, enabling navigation or aggregation over a referenced hierarchy (e.g., building a category tree for products via their `categories` reference). Supports multiple references, custom empty-node handling, and ordering. Requires at least one sub-constraint to define traversal logic.
	 *
	 * ```evitaql
	 * require(
	 *     hierarchyOfReference(
	 *         "categories",
	 *         fromRoot(
	 *             entityFetch(attributeContent("code"))
	 *         )
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#hierarchy-of-reference)
	 *
	 * @see io.evitadb.api.query.require.HierarchyOfReference
	 */
	@SourceHash("a72d498e1e0a920d4f96c55ff80ae836")
	@Nullable
	static HierarchyOfReference hierarchyOfReference(
		@Nullable String[] referenceName,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		requireNonNullItems(referenceName, "referenceName");
		return hierarchyOfReference(referenceName, null, null, requirement);
	}

	/**
	 * Computes hierarchical data structures for entity types referenced by the queried entity, enabling traversal and aggregation (e.g., building a category tree for products via the `categories` reference). Supports specifying one or more reference names, optional ordering of hierarchy levels, and fine-grained control over inclusion of empty nodes. Requires at least one hierarchy traversal sub-constraint (e.g., `fromRoot`, `fromNode`). Can be used multiple times per query for different references.
	 *
	 * ```evitaql
	 * require(
	 *     hierarchyOfReference(
	 *         "categories",
	 *         orderBy(attributeNatural("name", ASC)),
	 *         fromRoot(
	 *             entityFetch(attributeContent("code"))
	 *         )
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#hierarchy-of-reference)
	 *
	 * @see io.evitadb.api.query.require.HierarchyOfReference
	 */
	@SourceHash("986b639a1bca355261b1ce36dc0ec1ac")
	@Nullable
	static HierarchyOfReference hierarchyOfReference(
		@Nullable String[] referenceName,
		@Nullable OrderBy orderBy,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		requireNonNullItems(referenceName, "referenceName");
		return hierarchyOfReference(referenceName, null, orderBy, requirement);
	}

	/**
	 * Computes hierarchical data structures for entities referenced by the queried entity, enabling traversal and analysis of a related hierarchy (e.g., category trees for products via a `categories` reference). Supports specifying reference names, empty node handling, and custom ordering. Must include at least one sub-constraint to define traversal (e.g., fromRoot, fromNode, children, parents, or siblings). Can be used multiple times for different references in a single query.
	 *
	 * ```evitaql
	 * require(
	 *     hierarchyOfReference(
	 *         "categories",
	 *         LEAVE_EMPTY,
	 *         fromRoot(
	 *             entityFetch(attributeContent("code"))
	 *         )
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#hierarchy-of-reference)
	 *
	 * @see io.evitadb.api.query.require.HierarchyOfReference
	 */
	@SourceHash("bf886934bf85560d30ca436ea77fd1e0")
	@Nullable
	static HierarchyOfReference hierarchyOfReference(
		@Nullable String[] referenceName,
		@Nullable EmptyHierarchicalEntityBehaviour emptyHierarchicalEntityBehaviour,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		requireNonNullItems(referenceName, "referenceName");
		if (referenceName == null || ArrayUtils.isEmptyOrItsValuesNull(referenceName)) {
			return null;
		}
		if (ArrayUtils.isEmptyOrItsValuesNull(requirement)) {
			return null;
		}
		return new HierarchyOfReference(
			referenceName,
			ofNullable(emptyHierarchicalEntityBehaviour).orElse(EmptyHierarchicalEntityBehaviour.REMOVE_EMPTY),
			requirement
		);
	}

	/**
	 * Computes hierarchy data structures from a hierarchical entity type reached via one or more named references of the queried entity (e.g., building a category tree for products via their `categories` reference). Unlike `hierarchyOfSelf`, this operates on referenced hierarchies and can be used multiple times per query for different references. Supports options for pruning or retaining empty nodes and custom ordering of hierarchy levels. At least one traversal sub-constraint (e.g., `fromRoot`, `fromNode`) is required.
	 *
	 * ```evitaql
	 * require(
	 *     hierarchyOfReference(
	 *         "categories",
	 *         LEAVE_EMPTY,
	 *         orderBy(attributeNatural("name", ASC)),
	 *         fromRoot("menu", entityFetch(attributeContent("code")))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#hierarchy-of-reference)
	 *
	 * @see io.evitadb.api.query.require.HierarchyOfReference
	 */
	@SourceHash("95fea8b0d0e6c894c1751f702c7c4ac6")
	@Nullable
	static HierarchyOfReference hierarchyOfReference(
		@Nullable String[] referenceName,
		@Nullable EmptyHierarchicalEntityBehaviour emptyHierarchicalEntityBehaviour,
		@Nullable OrderBy orderBy,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		requireNonNullItems(referenceName, "referenceName");
		if (referenceName == null || ArrayUtils.isEmptyOrItsValuesNull(referenceName)) {
			return null;
		}
		if (ArrayUtils.isEmptyOrItsValuesNull(requirement)) {
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
	 * Starts hierarchy traversal from the invisible top root, building a complete tree regardless of any `hierarchyWithin` filter. Useful for rendering full navigation menus even when the query is scoped to a subtree. Respects `having`/`excluding` filters for statistics, but always starts from the top. Use `stopAt` to limit depth and `statistics` for node counts; full entity counts can be expensive on large datasets.
	 *
	 * ```evitaql
	 * require(
	 *     hierarchyOfReference(
	 *         "categories",
	 *         fromRoot(
	 *             "megaMenu",
	 *             stopAt(level(2)),
	 *             statistics(CHILDREN_COUNT)
	 *         )
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#from-root)
	 *
	 * @see io.evitadb.api.query.require.HierarchyFromRoot
	 */
	@SourceHash("950d45c1c66f284be1bcfd2f313c723f")
	@Nullable
	static HierarchyFromRoot fromRoot(
		@Nonnull String outputName,
		@Nullable HierarchyOutputRequireConstraint... requirement
	) {
		requireName(outputName, "outputName");
		return requirement == null ? new HierarchyFromRoot(outputName) : new HierarchyFromRoot(outputName,
				requirement);
	}

	/**
	 * Computes a hierarchy tree from the invisible top root, ignoring any subtree filters from `hierarchyWithin`, making it ideal for full navigation structures like mega-menus. Traversal depth and statistical aggregation can be controlled with inner constraints. For large datasets, limit depth or statistics for better performance.
	 *
	 * ```evitaql
	 * require(
	 *     hierarchyOfReference(
	 *         "categories",
	 *         fromRoot(
	 *             "megaMenu",
	 *             entityFetch(attributeContent("code")),
	 *             stopAt(level(2)),
	 *             statistics(CHILDREN_COUNT, QUERIED_ENTITY_COUNT)
	 *         )
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#from-root)
	 *
	 * @see io.evitadb.api.query.require.HierarchyFromRoot
	 */
	@SourceHash("a6640ad0ea61a9f8f40e17cb0a2c54fd")
	@Nullable
	static HierarchyFromRoot fromRoot(
		@Nonnull String outputName,
		@Nullable EntityFetch entityFetch,
		@Nullable HierarchyOutputRequireConstraint... requirement
	) {
		requireName(outputName, "outputName");
		return entityFetch == null ? new HierarchyFromRoot(outputName,
				requirement) : new HierarchyFromRoot(outputName, entityFetch, requirement);
	}

	/**
	 * Anchors hierarchy traversal at a dynamically selected pivot node, identified by a nested `HierarchyNode` filter, allowing computation of a subtree rooted at any arbitrary node regardless of the main query filter. Useful for rendering multiple independent side-menus or sub-menus in a single query. Traversal descends to leaf nodes by default, but can be limited with `HierarchyStopAt`. The output is registered under the specified `outputName` in the results map. Inner constraints like `EntityFetch`, `HierarchyStopAt`, and `HierarchyStatistics` are supported.
	 *
	 * ```evitaql
	 * fromNode(
	 *     "sideMenu",
	 *     node(filterBy(attributeEquals("code", "portables"))),
	 *     entityFetch(attributeContent("code")),
	 *     stopAt(distance(1)),
	 *     statistics(CHILDREN_COUNT, QUERIED_ENTITY_COUNT)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#from-node)
	 *
	 * @see io.evitadb.api.query.require.HierarchyFromNode
	 */
	@SourceHash("b4fbf7a580a4f69aada9b63f040ecd3a")
	@Nullable
	static HierarchyFromNode fromNode(
		@Nonnull String outputName,
		@Nullable HierarchyNode node,
		@Nullable HierarchyOutputRequireConstraint... requirement
	) {
		requireName(outputName, "outputName");
		if (node == null) {
			return null;
		} else {
			return requirement == null ?
				new HierarchyFromNode(outputName, node) :
				new HierarchyFromNode(outputName, node, requirement);
		}
	}

	/**
	 * Anchors hierarchy traversal at a dynamically selected pivot node, identified by a nested `HierarchyNode` filter, allowing computation of a subtree from any arbitrary node—independent of the main query filter. Useful for rendering multiple independent menus or subtrees in one query. By default, traversal descends to leaves; use `stopAt` to limit depth. The output is registered under the specified `outputName` in extra results.
	 *
	 * ```evitaql
	 * fromNode(
	 *     "sideMenu",
	 *     node(filterBy(attributeEquals("code", "portables"))),
	 *     entityFetch(attributeContent("code")),
	 *     stopAt(distance(1)),
	 *     statistics(CHILDREN_COUNT, QUERIED_ENTITY_COUNT)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#from-node)
	 *
	 * @see io.evitadb.api.query.require.HierarchyFromNode
	 */
	@SourceHash("274ae018bd39769f637e02f200e36b65")
	@Nullable
	static HierarchyFromNode fromNode(
		@Nonnull String outputName,
		@Nullable HierarchyNode node,
		@Nullable EntityFetch entityFetch,
		@Nullable HierarchyOutputRequireConstraint... requirement
	) {
		requireName(outputName, "outputName");
		if (node == null) {
			return null;
		} else {
			return entityFetch == null ?
				new HierarchyFromNode(outputName, node, requirement) :
				new HierarchyFromNode(outputName, node, entityFetch, requirement);
		}
	}

	/**
	 * Computes the hierarchy subtree rooted at the node targeted by the `hierarchyWithin` or `hierarchyWithinRoot` filter, traversing downward through all descendants unless limited by a `stopAt` constraint. Ideal for rendering sub-navigation beneath the current category, with options to fetch entity data, restrict traversal depth, and compute per-node statistics. The subtree is registered under the specified `outputName` in the extra results.
	 *
	 * ```evitaql
	 * require(
	 *     hierarchyOfReference(
	 *         "categories",
	 *         children(
	 *             "subcategories",
	 *             entityFetch(attributeContent("code")),
	 *             stopAt(distance(1)),
	 *             statistics(CHILDREN_COUNT, QUERIED_ENTITY_COUNT)
	 *         )
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#children)
	 *
	 * @see io.evitadb.api.query.require.HierarchyChildren
	 */
	@SourceHash("1a61ef93b40789667e6bb231f3668ba2")
	@Nullable
	static HierarchyChildren children(
		@Nonnull String outputName,
		@Nullable EntityFetch entityFetch,
		@Nullable HierarchyOutputRequireConstraint... requirement
	) {
		requireName(outputName, "outputName");
		return new HierarchyChildren(outputName, entityFetch, requirement);
	}

	/**
	 * Computes the hierarchy subtree rooted at the node targeted by `hierarchyWithin` or `hierarchyWithinRoot`, traversing downward through all descendants unless limited by `stopAt`. Useful for rendering sub-navigation (e.g., direct subcategories). Inner constraints can limit depth, fetch entity data, or compute statistics.
	 * ```evitaql
	 * children(
	 *     "subcategories",
	 *     entityFetch(attributeContent("code")),
	 *     stopAt(distance(1)),
	 *     statistics(CHILDREN_COUNT, QUERIED_ENTITY_COUNT)
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#children)
	 *
	 * @see io.evitadb.api.query.require.HierarchyChildren
	 */
	@SourceHash("f92836d074794751d614118d21cb26d1")
	@Nullable
	static HierarchyChildren children(
		@Nonnull String outputName,
		@Nullable HierarchyOutputRequireConstraint... requirement
	) {
		requireName(outputName, "outputName");
		return new HierarchyChildren(outputName, requirement);
	}

	/**
	 * Returns all sibling nodes of the hierarchy node targeted by `hierarchyWithin`, i.e., other children of the same parent, useful for "alternatives at the same level" navigation. Produces a flat list unless combined with `HierarchyStopAt`, which enables tree traversal. Only valid for non-root nodes; when used directly under `HierarchyOfSelf` or `HierarchyOfReference`, `outputName` is required and registers the result under that key. Respects `hierarchyWithin` filter constraints and supports optional entity fetching, traversal limits, and statistics.
	 *
	 * ```evitaql
	 * require(
	 *     hierarchyOfReference(
	 *         "categories",
	 *         siblings(
	 *             "audioSiblings",
	 *             entityFetch(attributeContent("code")),
	 *             statistics(CHILDREN_COUNT, QUERIED_ENTITY_COUNT)
	 *         )
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#siblings)
	 *
	 * @see io.evitadb.api.query.require.HierarchySiblings
	 */
	@SourceHash("5e151fed54c550759f6aa1351b86c889")
	@Nullable
	static HierarchySiblings siblings(
		@Nonnull String outputName,
		@Nullable EntityFetch entityFetch,
		@Nullable HierarchyOutputRequireConstraint... requirements
	) {
		requireName(outputName, "outputName");
		return new HierarchySiblings(outputName, entityFetch, requirements);
	}

	/**
	 * Returns all sibling nodes of the hierarchy node targeted by `hierarchyWithin`, i.e., other children of the same parent, useful for "alternatives at the same level" navigation. By default, returns a flat list; add `HierarchyStopAt` to traverse deeper. Must not be used with `hierarchyWithinRoot`, as root has no siblings. Use `outputName` to register results when used directly; omit it when nested in `parents`. Supports inner constraints for fetching data, limiting traversal, and computing statistics.
	 *
	 * ```evitaql
	 * siblings(
	 *     "audioSiblings",
	 *     entityFetch(attributeContent("code")),
	 *     statistics(CHILDREN_COUNT, QUERIED_ENTITY_COUNT)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#siblings)
	 *
	 * @see io.evitadb.api.query.require.HierarchySiblings
	 */
	@SourceHash("ef82fc9745d38665d844a4787324a86a")
	@Nullable
	static HierarchySiblings siblings(
		@Nonnull String outputName,
		@Nullable HierarchyOutputRequireConstraint... requirements
	) {
		requireName(outputName, "outputName");
		return new HierarchySiblings(outputName, requirements);
	}

	/**
	 * Returns all sibling nodes of the hierarchy node targeted by the `hierarchyWithin` filter, i.e., other children sharing the same parent. By default, yields a flat list; use `HierarchyStopAt` to traverse deeper. Respects `hierarchyWithin`'s filtering and supports fetching entity data or statistics per sibling.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#siblings)
	 *
	 * @see io.evitadb.api.query.require.HierarchySiblings
	 */
	@SourceHash("9b1240a31969df2c6cb5ea669226b69c")
	@Nullable
	static HierarchySiblings siblings(
		@Nullable EntityFetch entityFetch,
		@Nullable HierarchyOutputRequireConstraint... requirements
	) {
		return new HierarchySiblings(null, entityFetch, requirements);
	}

	/**
	 * Returns all sibling nodes of the hierarchy node targeted by `hierarchyWithin`, i.e., all other children sharing the same parent. By default, produces a flat sibling list; add `stopAt` to traverse deeper. Not applicable to root nodes. Inner constraints can fetch data or compute statistics.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#siblings)
	 *
	 * @see io.evitadb.api.query.require.HierarchySiblings
	 */
	@SourceHash("143ac8f9eb2aa9ccdc128fd7c47484af")
	@Nullable
	static HierarchySiblings siblings(@Nullable HierarchyOutputRequireConstraint... requirements) {
		return new HierarchySiblings(null, requirements);
	}

	/**
	 * Computes the ancestor axis (parent chain) of the hierarchy node targeted by `hierarchyWithin`, returning an ordered list of ancestor nodes up to the root. Supports limiting traversal depth, enriching ancestors with siblings, and fetching custom entity data or statistics. The result is registered under the specified `outputName` in extra results.
	 *
	 * ```evitaql
	 * parents(
	 *     "parentAxis",
	 *     entityFetch(attributeContent("code")),
	 *     statistics(CHILDREN_COUNT, QUERIED_ENTITY_COUNT)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#parents)
	 *
	 * @see io.evitadb.api.query.require.HierarchyParents
	 */
	@SourceHash("a03d1663d9ad6b546eeae2e89f917b0f")
	@Nullable
	static HierarchyParents parents(
		@Nonnull String outputName,
		@Nullable EntityFetch entityFetch,
		@Nullable HierarchyOutputRequireConstraint... requirements
	) {
		requireName(outputName, "outputName");
		return new HierarchyParents(outputName, entityFetch, requirements);
	}

	/**
	 * Computes the ordered list of ancestor nodes (parent axis) for the hierarchy node targeted by `hierarchyWithin`, enabling breadcrumb navigation from the direct parent up to the root. Supports limiting traversal depth, enriching ancestors with their siblings, fetching specific entity data, and computing per-node statistics. The result is registered under the provided `outputName` in extra results.
	 *
	 * ```evitaql
	 * parents(
	 *     "parentAxis",
	 *     entityFetch(attributeContent("code")),
	 *     siblings(),
	 *     statistics(CHILDREN_COUNT)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#parents)
	 *
	 * @see io.evitadb.api.query.require.HierarchyParents
	 */
	@SourceHash("13357119d16db7acff66fec847f95b1a")
	@Nullable
	static HierarchyParents parents(
		@Nonnull String outputName,
		@Nullable EntityFetch entityFetch,
		@Nullable HierarchySiblings siblings,
		@Nullable HierarchyOutputRequireConstraint... requirements
	) {
		requireName(outputName, "outputName");
		if (siblings == null) {
			return entityFetch == null ?
				new HierarchyParents(outputName, requirements) : new HierarchyParents(outputName, entityFetch, requirements);
		} else {
			return entityFetch == null ?
				new HierarchyParents(outputName, siblings, requirements) : new HierarchyParents(outputName, entityFetch, siblings, requirements);
		}
	}

	/**
	 * Computes the ordered list of ancestor nodes (parent axis) for the hierarchy node targeted by `hierarchyWithin`, ascending from the direct parent up to the root. Useful for rendering breadcrumbs; supports limiting traversal depth, enriching ancestors with siblings, and fetching stats or entity data. The result is registered under the given `outputName` in extra results.
	 *
	 * ```evitaql
	 * require(
	 *     hierarchyOfReference(
	 *         "categories",
	 *         parents(
	 *             "parentAxis",
	 *             entityFetch(attributeContent("code")),
	 *             statistics(CHILDREN_COUNT, QUERIED_ENTITY_COUNT)
	 *         )
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#parents)
	 *
	 * @see io.evitadb.api.query.require.HierarchyParents
	 */
	@SourceHash("1542498957921e8d652d2727f6415088")
	@Nullable
	static HierarchyParents parents(
		@Nonnull String outputName,
		@Nullable HierarchyOutputRequireConstraint... requirements
	) {
		requireName(outputName, "outputName");
		return new HierarchyParents(outputName, requirements);
	}

	/**
	 * Computes the ancestor (parent) axis for a hierarchy node targeted by `hierarchyWithin`, returning an ordered list of ancestor nodes from direct parent up to the root. Optionally enriches each ancestor with its siblings for expanded breadcrumb navigation. Use inner constraints to fetch entity data, limit traversal depth, or compute statistics. The result is registered under the provided `outputName` in extra results.
	 *
	 * ```evitaql
	 * parents(
	 *     "parentAxis",
	 *     siblings(),
	 *     entityFetch(attributeContent("code")),
	 *     statistics(CHILDREN_COUNT)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#parents)
	 *
	 * @see io.evitadb.api.query.require.HierarchyParents
	 */
	@SourceHash("b3b341aec4acd349df4ccccc000a7a54")
	@Nullable
	static HierarchyParents parents(
		@Nonnull String outputName,
		@Nullable HierarchySiblings siblings,
		@Nullable HierarchyOutputRequireConstraint... requirements
	) {
		requireName(outputName, "outputName");
		return siblings == null ?
				new HierarchyParents(outputName, requirements) :
				new HierarchyParents(outputName, siblings, requirements);
	}

	/**
	 * Defines a termination condition for hierarchy traversal, used inside hierarchy requirements to prevent unbounded descent or ascent. Accepts a single strategy—distance, level, or node filter—to control when traversal stops, optimizing performance for large trees.
	 *
	 * ```evitaql
	 * hierarchyOfReference(
	 *     "categories",
	 *     fromRoot(
	 *         "megaMenu",
	 *         stopAt(level(2))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#stop-at)
	 *
	 * @see io.evitadb.api.query.require.HierarchyStopAt
	 */
	@SourceHash("90136c3096d21199b18189803497fd92")
	@Nullable
	static HierarchyStopAt stopAt(@Nullable HierarchyStopAtRequireConstraint stopConstraint) {
		return stopConstraint == null ? null : new HierarchyStopAt(stopConstraint);
	}

	/**
	 * Defines a dynamic termination point for hierarchy traversal, stopping at the first node that matches the provided filter condition. Unlike fixed-depth constraints, `node` enables non-uniform traversal depths, adapting to where the filter is satisfied in each branch. Use only within `stopAt` or `fromNode` containers.
	 *
	 * ```evitaql
	 * stopAt(
	 *     node(filterBy(attributeStartsWith("code", "w")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#node)
	 *
	 * @see io.evitadb.api.query.require.HierarchyNode
	 */
	@SourceHash("391eda6ebbf4d236440bbd17a1bddd31")
	@Nullable
	static HierarchyNode node(@Nullable FilterBy filterBy) {
		return filterBy == null ? null : new HierarchyNode(filterBy);
	}

	/**
	 * Specifies an absolute hierarchy depth at which traversal stops, counting from the virtual root (level 1 = top-level nodes, level 2 = their children, etc.). The node at the given level is included, but its children are not. Use within `HierarchyStopAt` to cap tree depth uniformly, regardless of the pivot node's position.
	 *
	 * ```evitaql
	 * stopAt(level(2))
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#level)
	 *
	 * @see io.evitadb.api.query.require.HierarchyLevel
	 */
	@SourceHash("0e12bd4373e5224027c7da20111d87fb")
	@Nullable
	static HierarchyLevel level(@Nullable Integer level) {
		return level == null ? null : new HierarchyLevel(level);
	}

	/**
	 * Limits hierarchy traversal to nodes within the specified number of edge hops from the pivot node, counting both up and down the tree. A distance of 0 returns only the pivot; 1 returns direct children or parent, and so on. Use only as the inner constraint of `stopAt` for relative traversal depth. Not to be confused with absolute-level or filter-based stopping.
	 *
	 * ```evitaql
	 * stopAt(distance(1))
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#distance)
	 *
	 * @see io.evitadb.api.query.require.HierarchyDistance
	 */
	@SourceHash("eeba1b60b2dd7e622e5f72c51dfcd85e")
	@Nullable
	static HierarchyDistance distance(@Nullable Integer distance) {
		return distance == null ? null : new HierarchyDistance(distance);
	}

	/**
	 * Requests statistical metadata for each hierarchy node in a hierarchy traversal, such as the number of child nodes (`CHILDREN_COUNT`) or the total matching entities under a node (`QUERIED_ENTITY_COUNT`). By default, counts exclude user-selected filters for stable faceted navigation. Computing entity counts for root nodes can be expensive on large datasets.
	 *
	 * ```evitaql
	 * require(
	 *     hierarchyOfReference(
	 *         "categories",
	 *         children(
	 *             statistics(CHILDREN_COUNT, QUERIED_ENTITY_COUNT)
	 *         )
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#statistics)
	 *
	 * @see io.evitadb.api.query.require.HierarchyStatistics
	 */
	@SourceHash("db327f34664ff059c739ccc2c2c1104b")
	@Nullable
	static HierarchyStatistics statistics(@Nullable StatisticsType... type) {
		requireNonNullItems(type, "type");
		return type == null ?
			new HierarchyStatistics(StatisticsBase.WITHOUT_USER_FILTER) :
			new HierarchyStatistics(StatisticsBase.WITHOUT_USER_FILTER, type);
	}

	/**
	 * Requests statistical metadata for each returned hierarchy node, such as the number of child nodes (`CHILDREN_COUNT`) or the total matching entities under the node (`QUERIED_ENTITY_COUNT`). You can specify which filter context to use for counts via `StatisticsBase`. Note: `QUERIED_ENTITY_COUNT` can be expensive on large datasets; always consider traversal limits or caching.
	 *
	 * ```evitaql
	 * require(
	 *     hierarchyOfReference(
	 *         "categories",
	 *         fromRoot(
	 *             "megaMenu",
	 *             statistics(COMPLETE_FILTER, CHILDREN_COUNT, QUERIED_ENTITY_COUNT)
	 *         )
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#statistics)
	 *
	 * @see io.evitadb.api.query.require.HierarchyStatistics
	 */
	@SourceHash("7725fc357ae1b50e7682bcf3814f6440")
	@Nullable
	static HierarchyStatistics statistics(@Nullable StatisticsBase base, @Nullable StatisticsType... type) {
		requireNonNullItems(type, "type");
		return new HierarchyStatistics(base == null ? StatisticsBase.WITHOUT_USER_FILTER : base,
			type == null ? new StatisticsType[0] : type);
	}

	/**
	 * Loads full entity bodies from storage, optionally including specific data containers like attributes, prices, or references as specified by sub-requirements. Use only the data you need—each sub-requirement increases I/O and transfer cost. Merges duplicate requirements by superset, but conflicting ones throw an exception.
	 *
	 * ```evitaql
	 * require(
	 *     entityFetch(
	 *         attributeContent("code", "name"),
	 *         priceContentRespectingFilter()
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#entity-fetch)
	 *
	 * @see io.evitadb.api.query.require.EntityFetch
	 */
	@SourceHash("ccc9c82c1dcc7ab5638fa87e0bf6409e")
	@Nonnull
	static EntityFetch entityFetch(@Nullable EntityContentRequire... requirements) {
		if (requirements == null) {
			return new EntityFetch();
		}
		return new EntityFetch(requirements);
	}

	/**
	 * Retrieves the bodies of group entities associated with references, allowing you to specify which data to load for each group (attributes, associated data, prices, hierarchy, or references). Use only within `referenceContent`. Duplicate sub-requirements are folded as in `entityFetch`.
	 *
	 * ```evitaql
	 * require(
	 *     entityFetch(
	 *         referenceContent(
	 *             "parameterValues",
	 *             entityGroupFetch(
	 *                 attributeContent("code", "name")
	 *             )
	 *         )
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#entity-group-fetch)
	 *
	 * @see io.evitadb.api.query.require.EntityGroupFetch
	 */
	@SourceHash("3f5aed2fc059b9f03656503e74429f2e")
	@Nonnull
	static EntityGroupFetch entityGroupFetch(@Nullable EntityContentRequire... requirements) {
		if (requirements == null) {
			return new EntityGroupFetch();
		}
		return new EntityGroupFetch(requirements);
	}

	/**
	 * Fetches all attributes of an entity or reference, including both localized and non-localized values (if a locale context is set). Use when most or all attributes are needed, as this does not increase I/O but ensures the client receives the complete attribute set.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     attributeContentAll()
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#attribute-content)
	 *
	 * @see io.evitadb.api.query.require.AttributeContent
	 */
	@SourceHash("355b4e258314e42600fb951afb71f016")
	@Nonnull
	static AttributeContent attributeContentAll() {
		return new AttributeContent();
	}

	/**
	 * Fetches one or more specified entity or reference attribute values into the result. Only non-localized (global) attributes are returned unless a locale context is set in the query. Fetching a subset of attributes limits network transfer, not disk I/O. Multiple `attributeContent` constraints in a query are merged, and requesting all attributes (via `attributeContentAll()`) overrides named selections.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     attributeContent("code", "name")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#attribute-content)
	 *
	 * @see io.evitadb.api.query.require.AttributeContent
	 */
	@SourceHash("bf6e2f26fbf3737423eea5f069580516")
	@Nonnull
	static AttributeContent attributeContent(@Nullable String... attributeName) {
		requireNonNullItems(attributeName, "attributeName");
		if (attributeName == null) {
			return new AttributeContent();
		}
		return new AttributeContent(attributeName);
	}

	/**
	 * Fetches all associated data items stored on the entity, including both localized and non-localized data (if a locale context is active). Use with care, as each item is loaded separately and may impact performance if many items exist.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#associated-data-content)
	 *
	 * @see io.evitadb.api.query.require.AssociatedDataContent
	 */
	@SourceHash("a0e69f4cbba8f4f27c28d21716f7e8f9")
	@Nonnull
	static AssociatedDataContent associatedDataContentAll() {
		return new AssociatedDataContent();
	}

	/**
	 * Fetches one or more named associated data items—arbitrary, unstructured data stored with an entity but not used for filtering or sorting—into the entity result. Only specified items are loaded, optimizing I/O and memory. Localized data is included only if a locale context is set.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     associatedDataContent("description", "gallery-3d")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#associated-data-content)
	 *
	 * @see io.evitadb.api.query.require.AssociatedDataContent
	 */
	@SourceHash("b0266b134ceeec165c161dec414571a2")
	@Nonnull
	static AssociatedDataContent associatedDataContent(@Nullable String... associatedDataName) {
		requireNonNullItems(associatedDataName, "associatedDataName");
		if (associatedDataName == null) {
			return new AssociatedDataContent();
		}
		return new AssociatedDataContent(associatedDataName);
	}

	/**
	 * Includes all localized attribute and associated data variants, along with global data, in the returned entities—regardless of locale. Useful for exporting or displaying every translation at once; only one such requirement is allowed per query.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#data-in-locales)
	 *
	 * @see io.evitadb.api.query.require.DataInLocales
	 */
	@SourceHash("3e9235be7ed73551e339b2a7308bc09d")
	@Nonnull
	static DataInLocales dataInLocalesAll() {
		return new DataInLocales();
	}

	/**
	 * Specifies which localized attribute and associated data variants to include in fetched entities, alongside global (non-localized) data. Use when you need data for specific locales, especially without a locale filter or when fetching multiple locales at once. Only one `dataInLocales` requirement is allowed per query.
	 *
	 * ```evitaql
	 * entityFetch(
	 *   attributeContent(),
	 *   dataInLocales("en-US", "cs-CZ")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#data-in-locales)
	 *
	 * @see io.evitadb.api.query.require.DataInLocales
	 */
	@SourceHash("f9f6ad0b964abf10beee5abbf89c86ad")
	@Nonnull
	static DataInLocales dataInLocales(@Nullable Locale... locale) {
		final Locale[] normalizedLocale = withoutNullValues(locale);
		if (normalizedLocale == null) {
			return new DataInLocales();
		}
		return new DataInLocales(normalizedLocale);
	}

	/**
	 * Fetches all references of all types from the entity, returning the primary keys of referenced entities and any nested entity/group bodies if specified. Reference attributes are not included—use `referenceContentAllWithAttributes()` for those. Must be used inside `entityFetch`.  
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("883aa9beb571a4f0ff9d7b2288aa7f50")
	@Nonnull
	static ReferenceContent referenceContentAll() {
		return new ReferenceContent();
	}

	/**
	 * Fetches all references of all types from the entity, including both the primary keys of referenced entities and all attributes stored on each reference record. Use inside `entityFetch` to retrieve complete reference data for every association on the entity.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("84ed4a05129bcddabeb26a2aeaf56219")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes() {
		return new ReferenceContent(AttributeContent.ALL_ATTRIBUTES);
	}

	/**
	 * Fetches all references of all types from the entity, including both the primary keys of referenced entities and all reference-level attributes as specified by the given `attributeContent`. Use inside `entityFetch` to retrieve complete reference metadata for every reference type.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
	 *         attributeContent("sortOrder", "isPrimary")
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("6142a4e360c8448aba97d6bb3a3fe579")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(@Nullable AttributeContent attributeContent) {
		return new ReferenceContent(
			ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES)
		);
	}

	/**
	 * Fetches all references of all types from the entity, including their reference-level attributes as specified by the given `attributeContent`, and limits the result set per reference type using the provided chunking constraint (e.g., pagination). Use inside `entityFetch` to retrieve both reference metadata and referenced entity keys/bodies for all associations.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
	 *         attributeContent("sortOrder"),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("1413ad7bd7012bca60f8232f8143ddb5")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(@Nullable AttributeContent attributeContent, @Nullable ChunkingRequireConstraint chunk) {
		return new ReferenceContent(
			ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
			null,
			null,
			chunk
		);
	}

	/**
	 * Fetches the specified reference group(s) from an entity, returning the primary keys of referenced entities and, optionally, their bodies if nested fetches are provided. Use inside `entityFetch` to include references; without it, references are omitted from results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent("brand")
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("5ff40eb0b169cee4315de8b9c431ac2f")
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName);
	}

	/**
	 * Fetches the specified named reference(s) from an entity, including both the primary keys of the referenced entities and the selected reference-level attributes (metadata on the relationship itself). Use inside `entityFetch` to retrieve references and their attributes; without this, references and their metadata are omitted from results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes("brand", "isPrimary", "sortOrder")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("d9a977489f250f66eebbf4691b807c74")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName,
		@Nullable String... attributeNames) {
		requireName(referenceName, "referenceName");
		requireNonNullItems(attributeNames, "attributeNames");
		return new ReferenceContent(
				referenceName, null, null,
				attributeContent(attributeNames), null, null, null
			);
	}

	/**
	 * Fetches the specified reference group from an entity, returning both the primary keys of referenced entities and all attributes stored on the reference record (metadata about the relationship). Place inside `entityFetch` to include reference data in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes("brand")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("9dfc88d7ce3a35f9f3601691731e819c")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, AttributeContent.ALL_ATTRIBUTES);
	}


	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all attributes stored on the reference record itself. Use inside `entityFetch` to retrieve reference metadata (e.g., sort order, flags) alongside referenced entity info.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         attributeContent("sortOrder", "isPrimary")
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("230e1dfc177b458300825af84f6f5999")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName,
		@Nullable AttributeContent attributeContent) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
			referenceName, null, null,
			ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
			null, null, null
		);
	}

	/**
	 * Fetches one or more named reference groups from an entity, returning the primary keys of referenced entities and, if nested constraints are provided, their full bodies. Must be used inside `entityFetch`; without it, references are excluded from results. By default, only reference primary keys are returned—reference attributes require a different constraint. Supports advanced filtering, ordering, and chunking for large reference sets.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent("brand", "categories")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("b3b0cce5f1debf77800d8b21f7ff3de1")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String... referenceName) {
		requireNonNullItems(referenceName, "referenceName");
		if (referenceName == null) {
			return new ReferenceContent();
		}
		return new ReferenceContent(referenceName);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, the full bodies of the referenced entities as defined by the nested `EntityFetch` constraint. Place inside `entityFetch` to include references in results. By default, only the reference keys and requested referenced entity data are returned, not reference-level attributes. Reference filtering, ordering, and pagination are supported via nested constraints.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "brand",
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("f63ec01586328d5f837958192f67d90a")
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable EntityFetch entityRequirement) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
			referenceName, null, null, entityRequirement, null, null
		);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the reference's primary keys and its reference-level attributes, and optionally the full bodies of referenced entities as defined by the nested `EntityFetch`. Place inside `entityFetch` to retrieve associated references with their metadata. Supports advanced filtering, ordering, and chunking; see docs for merging and aliasing rules.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "brand",
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("d674dcd31bb29a6eca89db0d2fd220a1")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName,
		@Nullable EntityFetch entityRequirement) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
			referenceName, null, null,
			AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, null
		);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys and all attributes stored on the reference record, plus the full body of the referenced entity as defined by the nested `entityFetch`. Use inside `entityFetch` to retrieve associations (e.g., product → category) along with reference-level metadata and referenced entity details. Reference filtering, ordering, and chunking can be applied via nested constraints.  
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         attributeContent("isPrimary", "sortOrder"),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("6e0e4ad1d170b4e4a0099ea93e15dc3a")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName,
		@Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
			referenceName, null, null,
			ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
			entityRequirement, null, null
		);
	}

	/**
	 * Fetches the specified reference group(s) from an entity, returning the primary keys of referenced entities and, if requested, the full bodies of their group entities via a nested `entityGroupFetch`. Place inside `entityFetch` to include references in results; without it, references are omitted.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent("categories", entityGroupFetch(attributeContent("code")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("4d1910f3b7caf27db329ab663cd8d16b")
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName,
		@Nullable EntityGroupFetch groupEntityRequirement) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, null, null, null, groupEntityRequirement, null);
	}

	/**
	 * Fetches the specified reference group from an entity, returning both the primary keys and all reference-level attributes, along with the full bodies of referenced group entities as defined by the nested `EntityGroupFetch`. Place inside `entityFetch` to include references in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("5aff16e0dc994d4e01d86e1c29eb1798")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName,
		@Nullable EntityGroupFetch groupEntityRequirement) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
			referenceName, null, null,
			AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, null
		);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record itself. Optionally, also fetches the full body of the referenced group entity as specified. Use inside `entityFetch` to retrieve association metadata along with referenced entities.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         attributeContent("sortOrder"),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("b847f5eab24c4027fbc4a9590e5aa3c5")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nonnull String referenceName,
		@Nullable AttributeContent attributeContent, @Nullable EntityGroupFetch groupEntityRequirement) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
			referenceName, null, null,
			ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
			null, groupEntityRequirement, null
		);
	}

	/**
	 * Fetches the specified reference group(s) from an entity, returning the primary keys of referenced entities and, if requested, their full bodies via nested `entityFetch` and `entityGroupFetch` constraints. Place inside `entityFetch` to include references in results; without it, references are omitted. Reference attributes are not included—use `referenceContentWithAttributes` for those. Supports filtering, ordering, and pagination for advanced reference selection.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "parameterValues",
	 *         entityFetch(attributeContent("code")),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("d8394fc73b1cf6d20ddefe06df9ce78d")
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, null, null, entityRequirement, groupEntityRequirement, null);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity/group bodies (as defined by nested `entityFetch`/`entityGroupFetch`) and all attributes stored on the reference record itself. Use inside `entityFetch` to retrieve associations (e.g., product → brand) with their metadata. Reference-level filters, ordering, and chunking can be applied via nested constraints.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "parameterValues",
	 *         entityFetch(attributeContent("code")),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("c0b415cfa16bdbec3f815f07262852e4")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
			referenceName, null, null,
			AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement, null
		);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all attributes stored on the reference record itself. Optionally, fetches full bodies of referenced entities and their groups. Use inside `entityFetch` to retrieve reference-level metadata and related entities in one step.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "parameterValues",
	 *         attributeContent("sortOrder"),
	 *         entityFetch(attributeContent("code")),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("9a7a7d82bd4982f21b8bcacc6f3d7e37")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, null
			);
	}


	/**
	 * Fetches references of the specified entity types from the parent entity, returning their primary keys and, optionally, the full bodies of the referenced entities as defined by the nested `entityFetch` constraint. Reference attributes are not included unless explicitly requested. Place inside `entityFetch` to retrieve associations like product→brand or product→categories.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         ["brand", "categories"],
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("de3c893f9354fb64539d70303ad57f48")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String[] referencedEntityTypes,
		@Nullable EntityFetch entityRequirement) {
		requireNonNullItems(referencedEntityTypes, "referencedEntityTypes");
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
	 * Fetches references of the specified types from an entity, returning their primary keys and, if provided, the bodies of referenced group entities as defined by the nested `EntityGroupFetch` constraint. Place inside `entityFetch` to include references in results; by default, only reference primary keys and group entity data are returned, not reference attributes. Combine with other constraints for filtering, ordering, or fetching referenced entity bodies as needed.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("83c0ddc878dae46f5dfa9f1648586fbe")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String[] referencedEntityTypes,
		@Nullable EntityGroupFetch groupEntityRequirement) {
		requireNonNullItems(referencedEntityTypes, "referencedEntityTypes");
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
	 * Fetches references of the specified types from an entity, returning the primary keys of referenced entities and, if nested, their full bodies via `entityFetch` and `entityGroupFetch`. Use inside `entityFetch` to include references; without it, references are omitted. Reference attributes are not included—use `referenceContentWithAttributes` for those. Supports filtering, ordering, and pagination of references. Merges with other `referenceContent` constraints by key, uniting nested requirements and resolving behavioral differences as described.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "parameterValues",
	 *         entityFetch(attributeContent("code")),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("86895ec12b20c66233fb055b6aeacd32")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String[] referencedEntityTypes,
		@Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		requireNonNullItems(referencedEntityTypes, "referencedEntityTypes");
		if (referencedEntityTypes != null) {
			return new ReferenceContent(referencedEntityTypes, entityRequirement, groupEntityRequirement);
		} else {
			return new ReferenceContent(entityRequirement, groupEntityRequirement);
		}
	}

	/**
	 * Fetches references of the specified type from an entity, including only those that match the provided filter on reference-level attributes or metadata. Returns the primary keys of the referenced entities and, if nested, their bodies. Place inside `entityFetch`.  
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("143477f4fea0f674009149e134b2a3d2")
	@Nullable
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, filterBy, null, null, null, null);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record itself. Allows filtering references by reference-level attributes using the provided `filterBy` constraint. Must be used within `entityFetch`.  
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("cb453b0863a39a63ff9c815de7b6cc69")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, null,
				AttributeContent.ALL_ATTRIBUTES, null, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all reference-level attributes. Supports filtering references by reference attributes via `filterBy`, and returns the requested reference attributes using `attributeContent`. Place inside `entityFetch` to retrieve references with metadata; without this, references and their attributes are omitted.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         attributeContent("sortOrder")
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("d510a20a549e8354fdaf0b273fffc615")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable AttributeContent attributeContent
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, the full bodies of referenced entities. Supports filtering references by reference-level attributes or referenced entity attributes via `filterBy`. Place inside `entityFetch` to include references in results; without it, references are omitted.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("cc54589597865a8be2f5cc1a743fe362")
	@Nullable
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy,
		@Nullable EntityFetch entityRequirement) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, filterBy, null, entityRequirement, null, null);
	}

	/**
	 * Fetches the specified named reference from an entity, including both the reference's primary keys and its reference-level attributes, and allows filtering references by criteria on the reference record. Optionally, fetches the full body of the referenced entity. Place inside `entityFetch` to retrieve references; without it, references are omitted from results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("56daafc6e6e65dfd28b14c69c53f4407")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityFetch entityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, null,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes. Supports filtering references by their attributes, fetching the full bodies of referenced entities, and combining these requirements in a single query. Use when you need both the association metadata and the referenced entity data in one result.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "parameterValues",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         attributeContent("sortOrder"),
	 *         entityFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("a65b4806afe5df63f51b8eb6ed7785c8")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning the primary keys of referenced entities whose reference records match the given filter. Optionally, also fetches the bodies of referenced group entities as specified. Place inside `entityFetch` to include references in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         entityGroupFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("db85f8bc682a257877265fc47bb76ca5")
	@Nullable
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy,
		@Nullable EntityGroupFetch groupEntityRequirement) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, filterBy, null, null, groupEntityRequirement, null);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes, with optional filtering on reference attributes and optional fetching of group entity bodies. Place inside `entityFetch` to retrieve associated references; without it, references are omitted. Filtering applies to reference attributes, not the referenced entity itself.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("5b0f76d7f577ec314660a08c733cedd2")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, null,
				AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the given name, including both the primary keys and all reference-level attributes, with optional filtering on reference attributes, and allows fetching the full bodies of referenced group entities. Place inside `entityFetch` to retrieve associations and their metadata.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         attributeContent("sortOrder"),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("a700df424a28da37b80b703341ff8ccb")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, optionally filtering which references are returned and loading the full bodies of referenced entities and/or their groups. Filtering applies to reference attributes; use `entityHaving` for referenced entity filters. Reference attributes are not included—use `referenceContentWithAttributes` if needed. References to missing entities are included by default. Multiple `referenceContent` constraints for the same reference type are merged, with filters and chunking required to match.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "parameterValues",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         entityFetch(attributeContent("code")),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("c5a3d2eb5e15be56af2902e34ace16f3")
	@Nullable
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy,
		@Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, filterBy, null, entityRequirement, groupEntityRequirement, null);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record. Optionally filters the references, fetches full bodies of referenced entities and their groups, and supports advanced configurations like filtering, ordering, and pagination. Use inside `entityFetch` to retrieve reference-level metadata along with referenced entities.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "parameterValues",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         entityFetch(attributeContent("code")),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("d16714dab18f20fca70389be3224240b")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, null,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including their primary keys, reference-level attributes, and optionally the full bodies of referenced entities and their groups. Supports filtering references (by reference or referenced entity attributes), custom ordering, and combining multiple requirements. Returns only references matching the filter; reference attributes are included in the result.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "parameterValues",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         attributeContent("sortOrder"),
	 *         entityFetch(attributeContent("code")),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("ff525ec330efa859d1f7c6333f36ec73")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, referenced entity bodies. The `orderBy` argument customizes the order of returned references using reference attributes or referenced entity properties.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent("categories", orderBy(attributeNatural("priority", DESC)))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("763a9456d90942087af35db8499f8a28")
	@Nullable
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable OrderBy orderBy) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, null, orderBy, null, null, null);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes (metadata on the association itself). The returned references are ordered according to the provided `orderBy` constraint, which operates on reference attributes. Use within `entityFetch` to retrieve rich reference data for advanced querying and result shaping.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         orderBy(attributeNatural("sortOrder", ASC))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("4bca106c9bf082505952d12aa7b37c27")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, null, orderBy,
				AttributeContent.ALL_ATTRIBUTES, null, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes, and orders the references according to the provided order. Place inside `entityFetch` to retrieve associations (e.g., product → categories) with their metadata and in a custom order.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         attributeContent("isPrimary", "sortOrder")
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("f143d0bc94e22d228a88aca2183f31b7")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, null, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, null, null
			);
	}

	/**
	 * Fetches the specified reference group by name, returning reference primary keys and, optionally, the full bodies of referenced entities as defined by the nested `entityFetch`. References are ordered according to the provided `orderBy` constraint, which operates on reference attributes by default; use `entityProperty` to sort by referenced entity attributes. Must be used within `entityFetch`. 
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         orderBy(attributeNatural("priority", DESC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("14b666606090d366b7957a1ee24c5ea0")
	@Nullable
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, null, orderBy, entityRequirement, null, null);
	}

	/**
	 * Fetches references of the specified type, including both their primary keys and all reference-level attributes, with results ordered according to the provided `orderBy` constraint. Optionally, fetches the full body of referenced entities as specified by the nested `entityRequirement`. Use inside `entityFetch` to retrieve associated entities and metadata in a single query.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("da8b97f2434dba70f1e2e44e7da499b3")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, null, orderBy,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type, returning their primary keys, reference-level attributes, and optionally the bodies of referenced entities. Allows custom ordering of references and inclusion of specific reference attributes. Must be used inside `entityFetch`.  
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         attributeContent("isPrimary"),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("0e7cb7b1f4618d8d0722ff8ffa5be6af")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, null, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, ordering them according to the provided `orderBy` constraint (which operates on reference attributes by default) and optionally fetching the bodies of referenced group entities as defined by `groupEntityRequirement`. Returns only the referenced entity primary keys and any nested group entity data; reference-level attributes are not included unless using a variant with attributes. Must be used inside `entityFetch` to include references in the result.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         entityGroupFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("71124e64b0e36be514cee4450debebf1")
	@Nullable
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable OrderBy orderBy,
		@Nullable EntityGroupFetch groupEntityRequirement) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, null, orderBy, null, groupEntityRequirement, null);
	}

	/**
	 * Fetches references of the specified type, including their reference-level attributes and optionally the full bodies of referenced group entities. Allows custom ordering of references by reference attributes or group entity properties. Must be used within `entityFetch`. 
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         entityGroupFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("41f417ea4caf9d2a9793f4bb70ba89c7")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, null, orderBy,
				AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type, returning both the referenced entity primary keys and all reference-level attributes, with optional ordering and nested group entity fetch. Use inside `entityFetch` to retrieve associations (e.g., product → categories) along with metadata stored on the reference record. Ordering is applied to reference attributes; use `entityProperty` to sort by referenced entity attributes.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         attributeContent("isPrimary"),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("e333c69ecf8d76e35d62d5ac63e19654")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, null, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, the full bodies of referenced entities and their groups. Supports custom ordering of references and nested fetches for related entities. Place inside `entityFetch` to include references in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "parameterValues",
	 *         orderBy(entityProperty(attributeNatural("name", ASC))),
	 *         entityFetch(attributeContent("name")),
	 *         entityGroupFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("dfe535492433b8d8b91a418bc73ce9e3")
	@Nullable
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, null, orderBy, entityRequirement, groupEntityRequirement, null);
	}

	/**
	 * Fetches references of the specified type from an entity, returning both the primary keys and all reference-level attributes, and optionally loads the full bodies of referenced entities and their groups. You can specify custom ordering of the references and nest `entityFetch`/`entityGroupFetch` to retrieve related entity data. Use this when you need both the relationship metadata and the referenced entities themselves.
	 *
	 * ```evitaql
	 * referenceContentWithAttributes(
	 *     "categories",
	 *     orderBy(attributeNatural("sortOrder", ASC)),
	 *     entityFetch(attributeContent("name")),
	 *     entityGroupFetch(attributeContent("code"))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("27b76000d9e58285bfd9edd37eb9e4a8")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, null, orderBy,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes, with optional ordering, nested fetching of referenced entity bodies, and group entity bodies. Use inside `entityFetch` to retrieve associations like product → categories, including metadata about the relationship itself. Combine with `orderBy` to control reference order, and with `attributeContent` to specify which reference attributes to include.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         attributeContent("isPrimary"),
	 *         entityFetch(attributeContent("name")),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("86cf5a18da80e16bdcfc9d525e976e83")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, null, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references named by `referenceName` from an entity, returning their primary keys and, optionally, referenced entity/group bodies. Use nested `filterBy` to filter references (by reference attributes or, via `entityHaving`, by referenced entity attributes) and `orderBy` to sort them (by reference or referenced entity attributes). Only references matching the filter are included, ordered as specified. Must be used inside `entityFetch`; without it, references are omitted.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("bba89aacf1e166a4e95d69303ae336a5")
	@Nullable
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, filterBy, orderBy, null, null, null);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes. Supports filtering and ordering of references via `filterBy` (on reference attributes) and `orderBy` (on reference or referenced entity attributes). Place inside `entityFetch` to retrieve associated references with metadata.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("d26a6533ab7e6f3feddee733a92ba8ce")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, orderBy,
				AttributeContent.ALL_ATTRIBUTES, null, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record itself. Supports filtering and ordering references by their attributes, and allows specifying which reference attributes to retrieve.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         attributeContent("isPrimary", "sortOrder")
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("5083b8e9b429c0498bd26bf100eac70e")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, allowing you to filter and order the reference set, and to fetch the full bodies of referenced entities. Filtering applies to reference attributes; use `entityHaving` to filter by referenced entity data. Returns only reference primary keys and any nested entity bodies, not reference attributes—use `referenceContentWithAttributes` for those.  
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "parameterValues",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         entityFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("881676b44bdb43fe5ff2986eaba35155")
	@Nullable
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, filterBy, orderBy, entityRequirement, null, null);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all reference-level attributes. Supports filtering and ordering references by their attributes or referenced entity properties, and can load full referenced entity bodies via nested `entityFetch`. Use inside `entityFetch` to retrieve associated references with fine-grained control over which references and attributes are included.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("092fb3d3498c37a7ff620db966ec3e5f")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, orderBy,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including their primary keys, reference-level attributes, and optionally the full bodies of referenced entities. Supports filtering and ordering references by reference or referenced entity attributes. Place inside `entityFetch` to retrieve associated references with fine-grained control over which references and attributes are included.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         attributeContent("isPrimary", "sortOrder"),
	 *         entityFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("cb1d4f13f2d0670e2fd9f8b86b35f870")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, group entity bodies. Supports filtering references by reference-level attributes (via `filterBy`) and ordering them (via `orderBy`). Place inside `entityFetch`; without it, references are omitted from results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         entityGroupFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("2b5d266428c826d1c8cf12cc69c0bad7")
	@Nullable
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy, @Nullable EntityGroupFetch groupEntityRequirement) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, filterBy, orderBy, null, groupEntityRequirement, null);
	}

	/**
	 * Fetches references of the specified type from an entity, including their reference-level attributes, and allows filtering, ordering, and fetching of referenced group entity bodies. Filtering and ordering operate on reference attributes unless wrapped in entity/group containers. Use in `entityFetch` to retrieve associations like product→brand or product→categories, optionally filtering or sorting the references and including group entity data.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         entityGroupFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("b43842091083f4d9ec4b1464aed1091b")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, orderBy,
				AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record itself. Supports filtering and ordering references by reference attributes, fetching group entity bodies, and combining multiple requirements for the same reference type. Use when you need reference metadata (e.g., "sortOrder") along with the reference itself.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         attributeContent("isPrimary", "sortOrder"),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("9fe92bb6fed5421444eb55c674eaf4f6")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, optionally filtering and ordering them, and can load full bodies of referenced entities and their groups. Filtering applies to reference attributes unless wrapped in `entityHaving`. Multiple `referenceContent` constraints can be combined if their keys match, with rules for merging filters, ordering, and chunking. By default, all references are returned regardless of target existence; use `referenceContent(EXISTING, ...)` to suppress missing targets.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "parameterValues",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         entityFetch(attributeContent("code")),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("57d685f0774a63b12aec1cdf8ab80969")
	@Nullable
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, filterBy, orderBy, entityRequirement, groupEntityRequirement, null);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all reference-level attributes. Supports filtering and ordering of references (by reference attributes or referenced entity properties), and can fetch full bodies of referenced entities and their groups. Place inside `entityFetch` to retrieve reference associations with metadata and related entity data.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         entityFetch(attributeContent("name")),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("ace25254b599f4e4595425ac121aa2ad")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, orderBy,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the given name from an entity, including both the referenced entity primary keys and all attributes stored on the reference record. Supports filtering and ordering references (by reference attributes or referenced entity fields), and can fetch full bodies of referenced entities and their groups. Use inside `entityFetch` to retrieve reference-level metadata along with associated entities.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "parameterValues",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         attributeContent("isPrimary", "sortOrder"),
	 *         entityFetch(attributeContent("code")),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("0585662696e7127de261992d72d1825d")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Retrieves all references of all types for each entity, returning the primary keys of referenced entities and, if specified, their full bodies via the nested `EntityFetch` constraint. Reference attributes are not included; use `referenceContentAllWithAttributes()` to fetch those as well. Must be used inside `entityFetch`.  
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("56ac4f7d1e8019c93a83231533f48afa")
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(entityRequirement, null);
	}

	/**
	 * Retrieves all reference groups from the entity, including both the primary keys of referenced entities and all attributes stored on each reference record. Optionally, fetches the full bodies of referenced entities as specified by the nested `entityFetch`. Use inside `entityFetch` to ensure references and their metadata are included in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("572951b0f0ff62b55d3020644f016d51")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(@Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(
			AttributeContent.ALL_ATTRIBUTES,
			entityRequirement,
			null
		);
	}

	/**
	 * Fetches all references of all types from the entity, including their primary keys and all reference-level attributes, and optionally loads the full bodies of referenced entities as specified. Use inside `entityFetch` to retrieve complete reference and attribute data for all associations.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
	 *         attributeContent("isPrimary", "sortOrder"),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("bc3fed5c1a3c88182114ed822adf2ca6")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(@Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(
			ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
			entityRequirement,
			null
		);
	}

	/**
	 * Fetches all references of all types for each entity, returning their primary keys and, if specified, the full bodies of their group entities according to the provided `EntityGroupFetch` constraint. Reference attributes are not included; use `referenceContentAllWithAttributes()` to fetch those as well. Must be used inside `entityFetch`.  
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("0ce72a0d5aa24e31e94373ddde717518")
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(null, groupEntityRequirement);
	}

	/**
	 * Fetches all references of all types for the entity, including both the primary keys and all reference-level attributes, and allows fetching the full bodies of referenced group entities as specified by the provided `EntityGroupFetch` constraint. Use inside `entityFetch` to retrieve complete reference data for all associations.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("fb9ed4682cb989d1a09f5ba957d740d6")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(@Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(
			AttributeContent.ALL_ATTRIBUTES,
			null,
			groupEntityRequirement
		);
	}

	/**
	 * Fetches all references of all types from the entity, including the primary keys of referenced entities, all reference-level attributes (using the provided attribute content), and optionally the full bodies of referenced group entities. Use inside `entityFetch` to retrieve complete reference information for all associations.  
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("d95764efbee700bb8223535a42a2df17")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(@Nullable AttributeContent attributeContent, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(
			ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
			null,
			groupEntityRequirement
		);
	}

	/**
	 * Fetches all references of all types for each entity, returning their primary keys and, optionally, the full bodies of referenced entities and their groups as specified by nested `entityFetch` and `entityGroupFetch` constraints. Reference attributes are not included; use `referenceContentAllWithAttributes()` to fetch those. Must be placed inside `entityFetch`.  
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAll(
	 *         entityFetch(attributeContent("name")),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("c97d12ec9525a611c803772c0b5dc646")
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(entityRequirement, groupEntityRequirement);
	}

	/**
	 * Fetches all references of all types for each entity, returning only the referenced entity primary keys (not reference attributes). Use this variant with a chunking constraint (e.g., `page` or `strip`) to limit the number of references returned per entity, which is useful for handling large reference sets efficiently.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAll(page(1, 20))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("67c327485e37b085cb37de62d955b03c")
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable ChunkingRequireConstraint chunk) {
		return new ReferenceContent(
			(EntityFetch) null,
			null,
			chunk
		);
	}

	/**
	 * Fetches all references of all types for each entity, including all reference-level attributes and applying the specified chunking (pagination) constraint to limit the number of references returned per entity. Useful for efficiently retrieving large reference sets with their metadata.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("3905663d4c2c15230b7e2210232b6423")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(@Nullable ChunkingRequireConstraint chunk) {
		return new ReferenceContent(
			AttributeContent.ALL_ATTRIBUTES,
			null,
			null,
			chunk
		);
	}

	/**
	 * Fetches all references of all types from the entity, including both the primary keys and all reference-level attributes, and optionally loads the full bodies of referenced entities and their group entities as specified. Use inside `entityFetch` to retrieve comprehensive reference data for all associations.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
	 *         entityFetch(attributeContent("code")),
	 *         entityGroupFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("396b3cfa74a88f05ac33bebf426b659e")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return new ReferenceContent(
			AttributeContent.ALL_ATTRIBUTES,
			entityRequirement,
			groupEntityRequirement
		);
	}

	/**
	 * Fetches all references of all types from the entity, including their primary keys, all reference-level attributes (as specified by `attributeContent`), and optionally the full bodies of referenced entities and their groups. Use inside `entityFetch` to retrieve all associations in detail.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
	 *         attributeContent("sortOrder"),
	 *         entityFetch(attributeContent("code")),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("285fbaec43d8952ebc7e52d80f77c5e1")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return new ReferenceContent(
			ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
			entityRequirement,
			groupEntityRequirement
		);
	}

	/**
	 * Fetches all references of all types from the entity, returning the primary keys of referenced entities and, optionally, their full bodies. The `managedReferencesBehaviour` parameter controls whether to include references to non-existent entities (`ANY`) or only those with existing targets (`EXISTING`). Use inside `entityFetch` to retrieve all reference associations for each entity.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("238d4adb32d44ef50fb7b8c515a4a20b")
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable ManagedReferencesBehaviour managedReferencesBehaviour) {
		return new ReferenceContent(managedReferencesBehaviour);
	}

	/**
	 * Fetches all references of all types from the entity, including both the primary keys of referenced entities and all reference-level attributes (metadata on the association itself). The `managedReferencesBehaviour` parameter controls whether to include references pointing to non-existent entities (`ANY`) or only those with existing targets (`EXISTING`). Use inside `entityFetch` to retrieve complete reference info for all associations.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("3b504b22ebda76f4424134790b717cfa")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(@Nullable ManagedReferencesBehaviour managedReferencesBehaviour) {
		return new ReferenceContent(managedReferencesBehaviour, AttributeContent.ALL_ATTRIBUTES);
	}

	/**
	 * Fetches all references of all types from the entity, including all reference-level attributes as specified, and returns the primary keys of referenced entities. The `managedReferencesBehaviour` controls whether only references to existing entities are included. Must be used within `entityFetch`.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(EXISTING, attributeContent("sortOrder", "isPrimary"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("c8839e653c62c864f9a9ad600af12600")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable AttributeContent attributeContent
	) {
		return new ReferenceContent(
			managedReferencesBehaviour,
			ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES)
		);
	}


	/**
	 * Fetches references of the specified type from an entity, returning referenced entity primary keys and optionally their bodies. Use `managedReferencesBehaviour` to control whether references to missing entities are included. Place inside `entityFetch`; without it, references are omitted.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("9979b9b05d276644559371327c863b57")
	@Nonnull
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(managedReferencesBehaviour, referenceName);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of the referenced entities and the selected reference-level attributes. The `managedReferencesBehaviour` controls whether to include references pointing to non-existent entities. Must be used inside `entityFetch`.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", "sortOrder", "isPrimary")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("84c0cd18d545586ad2fb6a79bd988a0f")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable String... attributeNames
	) {
		requireName(referenceName, "referenceName");
		requireNonNullItems(attributeNames, "attributeNames");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				attributeContent(attributeNames), null, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning both the referenced entity primary keys and all attributes stored on the reference record itself. You can control whether to include only references pointing to existing entities or all references using the `managedReferencesBehaviour` parameter. Place inside `entityFetch` to retrieve reference data and metadata for associations like product → brand or categories.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("224570660c768ee1941847b63e976658")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				AttributeContent.ALL_ATTRIBUTES, null, null, null
			);
	}


	/**
	 * Fetches references of the specified type from an entity, returning both the primary keys of the referenced entities and all reference-level attributes. You can control whether to include only references to existing entities or all references using `ManagedReferencesBehaviour`. Place inside `entityFetch` to retrieve associations like product → brand, including metadata about the relationship. Nested constraints allow fetching referenced entity bodies, filtering, ordering, and pagination.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", attributeContent("isPrimary", "sortOrder"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("76ba2d0070c2ad1da043e2f4c5e799ba")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable AttributeContent attributeContent
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, null, null
			);
	}

	/**
	 * Fetches one or more named references from an entity, returning the primary keys of the referenced entities and, optionally, their full bodies. The `managedReferencesBehaviour` parameter controls whether to include references to non-existent entities (`ANY`) or only those pointing to existing ones (`EXISTING`). Place inside `entityFetch`; without it, references are omitted from results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", "categories")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("82f4a7b285c08c0a93174e7f2b77db4f")
	@Nonnull
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String... referenceName
	) {
		requireNonNullItems(referenceName, "referenceName");
		if (referenceName == null) {
			return new ReferenceContent(managedReferencesBehaviour);
		}
		return new ReferenceContent(managedReferencesBehaviour, referenceName);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, the full bodies of the referenced entities. The `managedReferencesBehaviour` controls whether to include references to non-existent entities. Place inside `entityFetch` to retrieve references; without it, references are omitted from results. Supports nested fetch, filter, order, and pagination constraints for fine-grained control.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", entityFetch(attributeContent("name")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("e4909ebca0db3119f4efbe70bd431855")
	@Nonnull
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable EntityFetch entityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
			managedReferencesBehaviour, referenceName, null, null, entityRequirement, null, null
		);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity's primary keys and any attributes stored on the reference record itself. Optionally loads the full body of the referenced entity and supports filtering, ordering, and referential integrity control via `ManagedReferencesBehaviour`. Use inside `entityFetch` to retrieve associations (e.g., product → brand) with reference-level metadata.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", entityFetch(attributeContent("name")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("8c348e190b1ab5d61e8a1e2b4a75d97d")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable EntityFetch entityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity's primary keys and the attributes stored on the reference record itself. Optionally, retrieves the full body of the referenced entity and controls whether to include only references to existing entities or all references. Place inside `entityFetch` to access reference-level metadata and referenced entity data in one step.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", attributeContent("isPrimary"), entityFetch(attributeContent("name")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("c2b55b414a5515c914e4ca6150e2570f")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning primary keys and, if requested, group entity bodies. The `managedReferencesBehaviour` controls whether to include references to missing entities. Place inside `entityFetch` to retrieve references; without it, references are omitted.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", entityGroupFetch(attributeContent("code")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("b764872208d1095c3ddbdfba233f225d")
	@Nonnull
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
			managedReferencesBehaviour, referenceName, null, null, null, groupEntityRequirement, null
		);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys and all attributes stored on the reference record, and optionally fetches the full body of the referenced group entity. The `managedReferencesBehaviour` controls whether only references to existing target entities are returned. Place inside `entityFetch` to retrieve reference associations with their metadata.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", entityGroupFetch(attributeContent("name")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("96cf143994bf5c0d7bf9aec9f0d06260")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all attributes stored on the reference record itself. Optionally, you can control whether only references to existing entities are returned, and fetch group entity bodies with their own attribute content. Use inside `entityFetch` to retrieve associations and their metadata in a single query.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", attributeContent("sortOrder"), entityGroupFetch(attributeContent("code")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("79d8d2959439859c79b74caffe7aaac4")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning referenced entity primary keys and optionally their full bodies or group entities. The `managedReferencesBehaviour` controls whether to include references to missing entities. Place inside `entityFetch` to retrieve associations like product→brand or product→categories; without it, references are omitted. Supports nested fetches for referenced entities and groups.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand",
	 *         entityFetch(attributeContent("code")),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("831e435315120e377acd4726415b0d55")
	@Nonnull
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
			managedReferencesBehaviour, referenceName, null, null,
			entityRequirement, groupEntityRequirement, null
		);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all attributes stored on the reference record itself. Optionally loads full bodies of referenced entities and their groups, and can control whether to include only references to existing entities using `ManagedReferencesBehaviour`. Must be used within `entityFetch` to include references in the result.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", entityFetch(attributeContent("name")), null)
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("acbbf59c2a91c308b0e8dbeb8acc3568")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all attributes stored on the reference record itself. Optionally, fetches full bodies of referenced entities and their groups, and controls whether to return references pointing to non-existent entities via `managedReferencesBehaviour`. Use this to retrieve reference-level metadata alongside referenced entity data.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", attributeContent("isPrimary"), entityFetch(attributeContent("name")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("ad5aa0c3a591968ff648685b9f49d675")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, null
			);
	}


	/**
	 * Fetches references of the specified types from an entity, returning their primary keys and, optionally, the full bodies of referenced entities. The `managedReferencesBehaviour` controls whether only references to existing entities are included. Place inside `entityFetch` to retrieve associations such as product → brand or categories, and use nested constraints to fetch referenced entity bodies or filter/order references.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", entityFetch(attributeContent("code")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("06db3e90845212a7eb992873a31885d2")
	@Nonnull
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String[] referencedEntityTypes,
		@Nullable EntityFetch entityRequirement
	) {
		requireNonNullItems(referencedEntityTypes, "referencedEntityTypes");
		if (referencedEntityTypes == null && entityRequirement == null) {
			return new ReferenceContent(managedReferencesBehaviour);
		}
		if (referencedEntityTypes == null) {
			return new ReferenceContent(managedReferencesBehaviour, entityRequirement, null);
		}
		return new ReferenceContent(
			managedReferencesBehaviour,
			referencedEntityTypes,
			entityRequirement,
			null
		);
	}

	/**
	 * Fetches references of the specified types from an entity, returning their primary keys and, if requested, the full bodies of their group entities. The `managedReferencesBehaviour` parameter controls whether only references to existing entities are included. Place inside `entityFetch` to retrieve references; otherwise, they are omitted from results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", entityGroupFetch(attributeContent("code")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("73a25d21bd35a2a104c89a3744b49234")
	@Nonnull
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String[] referencedEntityTypes,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireNonNullItems(referencedEntityTypes, "referencedEntityTypes");
		if (referencedEntityTypes == null && groupEntityRequirement == null) {
			return new ReferenceContent(managedReferencesBehaviour);
		}
		if (referencedEntityTypes == null) {
			return new ReferenceContent(managedReferencesBehaviour, null, groupEntityRequirement);
		}
		return new ReferenceContent(
			managedReferencesBehaviour,
			referencedEntityTypes,
			null,
			groupEntityRequirement
		);
	}

	/**
	 * Fetches one or more named references from an entity, returning the primary keys of referenced entities and, optionally, their full bodies or group entities. Use `managedReferencesBehaviour` to control whether missing targets are included. Place inside `entityFetch` to include references in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", entityFetch(attributeContent("name")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("40de3cef11ee1371affafb180142c01b")
	@Nonnull
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String[] referencedEntityTypes,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireNonNullItems(referencedEntityTypes, "referencedEntityTypes");
		if (referencedEntityTypes != null) {
			return new ReferenceContent(managedReferencesBehaviour, referencedEntityTypes, entityRequirement, groupEntityRequirement);
		} else {
			return new ReferenceContent(managedReferencesBehaviour, entityRequirement, groupEntityRequirement);
		}
	}

	/**
	 * Fetches references of the specified type from an entity, optionally filtering them by reference-level attributes. The `managedReferencesBehaviour` controls whether to include references to missing entities. Place inside `entityFetch` to retrieve references; without it, references are omitted.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", filterBy(attributeEquals("isPrimary", true)))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("6bbd96b5a4939bd6d4820fc4305c9754")
	@Nullable
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, null, (EntityFetch) null, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning both the primary keys and all reference-level attributes, with optional filtering on reference attributes. The `managedReferencesBehaviour` controls whether only references to existing entities are included. Place inside `entityFetch`.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", filterBy(attributeEquals("isPrimary", true)))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("4e23254dfbf8be561edc4f7f10218f35")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, null,
				AttributeContent.ALL_ATTRIBUTES, null, null, null
			);
	}

	/**
	 * Fetches references of the given type from an entity, including both the primary keys of referenced entities and all attributes stored on the reference record. You can specify whether to include only references to existing entities, filter references by their attributes, and fetch reference-level attributes. Use inside `entityFetch` to retrieve associations like product→brand or product→categories, including metadata about the relationship.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", filterBy(attributeEquals("isPrimary", true)), attributeContent("sortOrder"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("1a60400a92780c1e8155c149db3af6d7")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable AttributeContent attributeContent
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, the full bodies of referenced entities. Allows filtering references by reference-level attributes, controlling inclusion of references to missing entities via `managedReferencesBehaviour`, and specifying nested entity fetches. Combine with `filterBy` to limit references or with `entityFetch` to load referenced entity data.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", filterBy(attributeEquals("isPrimary", true)), entityFetch(attributeContent("name")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("69a49e17561fdf2db760003f6ff3b8b7")
	@Nullable
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityFetch entityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, null, entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning both the reference's primary keys and its reference-level attributes. You can control whether to include only references to existing entities, filter references by reference attributes, and fetch full bodies of referenced entities. Use inside `entityFetch` to include reference data in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", filterBy(attributeEquals("isPrimary", true)), entityFetch(attributeContent("name")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("5d3c6ab488168d9df8c5f2fbe812f51e")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityFetch entityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, null,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including reference-level attributes, with optional filtering on reference attributes, custom managed reference behavior (e.g., only existing targets), and nested fetching of referenced entity bodies. Use this to retrieve both the association metadata and the referenced entities in a single query.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         EXISTING, "brand",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         attributeContent("sortOrder"),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("998484156da210f0db9713aa264a3ad7")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified name from an entity, optionally filtering them by reference-level attributes and including the full bodies of referenced group entities. The `managedReferencesBehaviour` controls whether references to missing entities are included. Place inside `entityFetch` to retrieve associations like product → brand or categories, with fine-grained control over which references are returned and how they're filtered.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", filterBy(attributeEquals("isPrimary", true)), entityGroupFetch(attributeContent("code")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("c4a6b99dcfa9805302bf0eefabf6a4d3")
	@Nullable
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName,
				filterBy, null, null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of the referenced entities and all attributes stored on the reference record. You can control whether to include only references to existing entities or all references using `ManagedReferencesBehaviour`. Optionally, filter which references are returned and fetch the full bodies of referenced group entities. Place inside `entityFetch` to retrieve reference associations with metadata.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "categories", filterBy(attributeEquals("isPrimary", true)), entityGroupFetch(attributeContent("code")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("901797cc4aabf27a19c3cb5c99e950c8")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, null,
				AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the given name from an entity, returning their primary keys, all reference-level attributes, and optionally the bodies of referenced group entities. Allows filtering references by reference attributes, controlling inclusion of missing targets via `ManagedReferencesBehaviour`, and specifying which reference/group attributes to fetch. Use for advanced reference retrieval with attribute and group body needs.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", filterBy(attributeEquals("isPrimary", true)), attributeContent("sortOrder"), entityGroupFetch(attributeContent("code")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("57a97a179f7cea6839e5f202ac2d780f")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, optionally filtering them by reference-level attributes, controlling inclusion of only existing targets, and loading full bodies of referenced entities and/or their groups. Use nested `filterBy`, `entityFetch`, or `entityGroupFetch` for fine-grained control. References are omitted unless this constraint is present in `entityFetch`.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", filterBy(attributeEquals("isPrimary", true)), entityFetch(attributeContent("code")), null)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("fb1d6f20af1a1d27f2603a5d1c863d3a")
	@Nullable
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, null,
				entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including their primary keys, reference-level attributes, and optionally the full bodies of referenced entities or groups. Supports filtering, ordering, and pagination of references, and can restrict results to only those referencing existing entities. Use inside `entityFetch` to retrieve associations like product → brand or categories, with fine-grained control over which references and attributes are returned.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         EXISTING, "brand",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         entityFetch(attributeContent("name")),
	 *         null
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("153422f1c73d4ed1cfaaab2e33215337")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, null,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including their primary keys, reference-level attributes, and optionally the full bodies of referenced entities or groups. Supports filtering, ordering, and pagination on references, and can restrict results to only those pointing to existing entities. Use inside `entityFetch` to retrieve rich association data.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         EXISTING, "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         attributeContent("sortOrder"),
	 *         entityFetch(attributeContent("name")),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("5cb65bba1afff3ba7db5d91797d1591e")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, referenced entity bodies. The `managedReferencesBehaviour` controls whether only references to existing entities are included. Use nested constraints for ordering, filtering, or fetching referenced entity data. 
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", orderBy(attributeNatural("sortOrder", ASC)))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("e9e32458aba362743805dfa180458e55")
	@Nullable
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, orderBy, (EntityFetch) null, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes, with optional ordering and control over whether to include only references to existing entities. Use this to retrieve association metadata and order references by custom criteria.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "categories", orderBy(attributeNatural("sortOrder", ASC)))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("2e5fc409621e87fd94ea9d3831bcd884")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, orderBy,
				AttributeContent.ALL_ATTRIBUTES, null, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including their primary keys, reference-level attributes, and optionally orders them using the provided `orderBy` constraint. Only references matching the `managedReferencesBehaviour` (e.g., EXISTING) are returned. Use inside `entityFetch` to retrieve both reference metadata and referenced entity details.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", orderBy(attributeNatural("sortOrder", ASC)), attributeContent("isPrimary"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("7c5e1ed17781a9bf9e0a29fe271dba80")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, null, null
			);
	}

	/**
	 * Fetches the specified reference group from an entity, returning referenced entity primary keys and, optionally, their full bodies. The `managedReferencesBehaviour` controls whether only existing references are included. Use `orderBy` to sort references by reference or referenced entity attributes, and nest `entityFetch` to fetch referenced entity data. Place inside `entityFetch`; without it, references are omitted.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", orderBy(attributeNatural("name", ASC)), entityFetch(attributeContent("code")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("f806f28744f099bec6a4f57def247654")
	@Nullable
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, orderBy, entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes. Allows filtering out references to non-existent entities via `managedReferencesBehaviour`, custom ordering of references, and fetching full bodies of referenced entities. Use inside `entityFetch` to retrieve rich association data and metadata for references.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", orderBy(attributeNatural("sortOrder", ASC)), entityFetch(attributeContent("name")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("8f6e88867d89d9d87ddb0a384f62db42")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, orderBy,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys, reference-level attributes, and optionally the full bodies of referenced entities. Supports filtering non-existent references via `managedReferencesBehaviour`, custom ordering, and nested attribute/entity fetches. Use inside `entityFetch` to include reference metadata and related entity data in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", orderBy(attributeNatural("sortOrder", ASC)), attributeContent("isPrimary"), entityFetch(attributeContent("name")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("5d6b8f60e706f99cfe697d1a7479f644")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, the full bodies of referenced group entities. Controls whether to include references pointing to missing entities via `managedReferencesBehaviour`, and allows custom ordering of references using `orderBy`. Place inside `entityFetch` to include references in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "categories", orderBy(attributeNatural("order", ASC)), entityGroupFetch(attributeContent("name")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("6ad9cf29d38f7af849c48c96db087382")
	@Nullable
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, orderBy, null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the given name from an entity, returning their primary keys, all reference-level attributes, and optionally the group entity body (using the provided group fetch constraint). Only references matching the specified managed reference behaviour are included, and their order can be customized via the `orderBy` constraint.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "categories", orderBy(attributeNatural("sortOrder", ASC)), entityGroupFetch(attributeContent("code")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("479223a3aec01214e384f27d1b19c71e")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, orderBy,
				AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type, including their primary keys, reference-level attributes, and optionally referenced group entity bodies, with control over whether to include only existing referenced entities. Supports custom ordering of references by reference attributes or referenced entity properties.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "categories", orderBy(attributeNatural("sortOrder", ASC)), attributeContent("isPrimary"), entityGroupFetch(attributeContent("code")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("cf98caae1af3cce13da939733b185ba2")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, the full bodies of referenced entities and their groups. You can control whether only existing references are included, apply custom ordering (by reference or referenced entity attributes), and nest `entityFetch` or `entityGroupFetch` to load referenced data. Place inside `entityFetch` to include references in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", orderBy(attributeNatural("sortOrder", ASC)), entityFetch(attributeContent("name")), null)
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("ef1587c3b10ec2a5ad691557324abb0b")
	@Nullable
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, orderBy,
				entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys, all reference-level attributes, and optionally the full bodies of referenced and group entities. Allows filtering out references to missing entities via `ManagedReferencesBehaviour`, and supports custom ordering and nested fetches for referenced entities and groups.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", orderBy(), entityFetch(attributeContent("name")), null)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("abd9577b5d2c0ad224d441c9878ec9f9")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, orderBy,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys, all attributes stored on the reference record, and optionally the full bodies of referenced entities and groups. Supports filtering, ordering, and managed reference behavior to include only existing targets if desired.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", orderBy(attributeNatural("sortOrder", ASC)), attributeContent("isPrimary"), entityFetch(attributeContent("name")), null)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("ece6ed823eb5e80c98b7d7c51bc0a9fe")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the given type from an entity, optionally filtering and ordering them, and controlling whether only references to existing entities are included. Place inside `entityFetch` to retrieve reference primary keys and, if nested, referenced entity bodies. Filtering and ordering apply to reference attributes unless wrapped in `entityHaving` or `entityProperty`. Use `ManagedReferencesBehaviour.EXISTING` to suppress references to missing entities.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", filterBy(attributeEquals("isPrimary", true)), orderBy(attributeNatural("sortOrder", ASC)))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("7cdafd5156f35ac75ee2985eac420af5")
	@Nullable
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, orderBy, (EntityFetch) null, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including their primary keys and all reference-level attributes, with optional filtering and ordering. Controls whether to include only references to existing entities or all, based on `managedReferencesBehaviour`. Filtering and ordering operate on reference attributes; to target referenced entity data, use `entityHaving`. Must be used inside `entityFetch`.  
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", filterBy(attributeEquals("isPrimary", true)), orderBy(attributeNatural("sortOrder", ASC)))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("8d4260ba35e9d92f5deba62441dad057")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, orderBy,
				AttributeContent.ALL_ATTRIBUTES, null, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including reference-level attributes, with optional filtering, ordering, and managed references behavior (e.g., only existing targets). Use this to retrieve both reference metadata and referenced entity keys or bodies.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", filterBy(attributeEquals("isPrimary", true)), orderBy(attributeNatural("sortOrder", ASC)), attributeContent("isPrimary", "sortOrder"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("e49659f6b6734cf68d044b55496b10f6")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, optionally filtering and ordering them, and can include the full body of each referenced entity. The `managedReferencesBehaviour` controls whether only existing referenced entities are returned. Place inside `entityFetch` to retrieve reference associations; without it, references are omitted.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", filterBy(attributeEquals("isPrimary", true)), orderBy(attributeNatural("sortOrder", ASC)), entityFetch(attributeContent("name")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("d2c2cbf5b79a2cf78e5f0c58b5fa0e54")
	@Nullable
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, orderBy, entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity's primary keys and all attributes stored on the reference record itself. Supports filtering, ordering, and loading full referenced entity bodies. Use `ManagedReferencesBehaviour.EXISTING` to suppress references to missing entities.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", filterBy(), orderBy(), entityFetch(attributeContent("code")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("7a2cc927117929da0193804dcab12fae")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, orderBy,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning both the referenced entity primary keys and all reference-level attributes. Supports filtering and ordering references (by their attributes or referenced entity properties), and can load full referenced entity bodies. The `managedReferencesBehaviour` controls whether references to missing entities are included. Must be used inside `entityFetch`.  
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", filterBy(attributeEquals("isPrimary", true)), orderBy(attributeNatural("sortOrder", ASC)), attributeContent("isPrimary", "sortOrder"), entityFetch(attributeContent("name")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("95c05565f06d8046644447d79ed9927e")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning primary keys of referenced entities and optionally their group entity bodies. Supports filtering and ordering references (by reference or referenced entity attributes), and controls whether to include references to non-existent entities via the managedReferencesBehaviour. Place inside `entityFetch` to include references in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", filterBy(attributeEquals("isPrimary", true)), orderBy(attributeNatural("sortOrder", ASC)), entityGroupFetch(attributeContent("name")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("899e4175f6dcad6de46e2ceda711646e")
	@Nullable
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, orderBy, null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including reference-level attributes, with optional filtering, ordering, and group entity body loading. The `managedReferencesBehaviour` controls whether references to non-existent entities are included. Place inside `entityFetch` to retrieve associations like product→brand or product→categories, and use nested constraints to filter or sort references or to fetch group entity data.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", filterBy(attributeEquals("isPrimary", true)), orderBy(attributeNatural("sortOrder", ASC)), entityGroupFetch(attributeContent("code")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("067c3078a4fc0a612a19bd4c1698d829")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, orderBy,
				AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning both their primary keys and all reference-level attributes. Supports filtering and ordering references, loading referenced group entity bodies, and controlling whether to include references to missing entities via `managedReferencesBehaviour`. Use inside `entityFetch` to retrieve full reference metadata and related entities.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "categories", filterBy(attributeEquals("isPrimary", true)), orderBy(attributeNatural("sortOrder", ASC)), attributeContent("isPrimary", "sortOrder"), entityGroupFetch(attributeContent("code")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("7399497fd0130e41e02ce959d111a370")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, referenced entity/group bodies. Supports filtering, ordering, and controlling whether to include references to missing entities. Place inside `entityFetch` to retrieve references.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", filterBy(attributeEquals("isPrimary", true)), orderBy(attributeNatural("sortOrder", ASC)), entityFetch(attributeContent("name")), null)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("63540f1c78b3917dd0226befbfd48df2")
	@Nullable
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, orderBy, entityRequirement, groupEntityRequirement,
					null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes. Allows filtering, ordering, and chunking of references, and can load full bodies of referenced and group entities. Use `ManagedReferencesBehaviour.EXISTING` to suppress references to missing entities. Combine with nested constraints for advanced filtering and ordering.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         EXISTING, "brand",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("5c9f9bdae997ef9715aae9569a44c975")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, orderBy,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning both the referenced entity primary keys and all reference-level attributes. Supports filtering and ordering of references, fetching referenced entity/group bodies, and controlling inclusion of references to non-existent entities via `ManagedReferencesBehaviour`. Use inside `entityFetch` to include reference metadata and related entities in the result.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", null, null, attributeContent("isPrimary"), entityFetch(attributeContent("name")), null)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("122ecdba4edf517591df7f136a7019bf")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches all references of all types from the entity, returning the primary keys of referenced entities and, optionally, their full bodies as specified by the nested `entityRequirement`. The `managedReferencesBehaviour` parameter controls whether to include references to non-existent entities. Must be used within `entityFetch`.  
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("3485ceb0909dae11d3ca728a435ab8b8")
	@Nonnull
	static ReferenceContent referenceContentAll(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable EntityFetch entityRequirement
	) {
		return new ReferenceContent(managedReferencesBehaviour, entityRequirement, null);
	}

	/**
	 * Fetches all references of all types for an entity, including all reference-level attributes and, optionally, the full bodies of referenced entities. Use `managedReferencesBehaviour` to control whether references to non-existent entities are included. Place inside `entityFetch` to retrieve references; without it, references are omitted.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(EXISTING, entityFetch(attributeContent("name")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("08d66be3c9b8cd9aed0bf71dd724620d")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable EntityFetch entityRequirement
	) {
		return new ReferenceContent(
			managedReferencesBehaviour, AttributeContent.ALL_ATTRIBUTES, entityRequirement, null
		);
	}

	/**
	 * Fetches all references of all types from the entity, including their reference-level attributes and, optionally, the full bodies of referenced entities. The `managedReferencesBehaviour` parameter controls whether only references to existing entities are returned. Use inside `entityFetch` to retrieve complete reference info for all associations.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(EXISTING, attributeContent("sortOrder"), entityFetch(attributeContent("code")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("1fd71207d00532c35cde706cad248217")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement
	) {
		return new ReferenceContent(
			managedReferencesBehaviour,
			ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
			entityRequirement, null
		);
	}

	/**
	 * Fetches all reference groups of all types for each entity, returning the primary keys of referenced entities and, optionally, their group entity bodies as specified by the nested `EntityGroupFetch`. The `ManagedReferencesBehaviour` controls whether references to non-existent entities are included. Use inside `entityFetch` to retrieve all references with group data.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("b1855137b5fedf4a0fd4c2c1fc33998a")
	@Nonnull
	static ReferenceContent referenceContentAll(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return new ReferenceContent(managedReferencesBehaviour, null, groupEntityRequirement);
	}

	/**
	 * Fetches all references of all types from the entity, including their reference-level attributes and optionally the full bodies of referenced group entities. The `managedReferencesBehaviour` parameter controls whether to include references to non-existent entities. Place inside `entityFetch` to retrieve all reference associations with attributes.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(EXISTING, entityGroupFetch(attributeContent("name")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("19d7e379f004dfd5a4f18bcc39e560be")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return new ReferenceContent(
			managedReferencesBehaviour, AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement
		);
	}

	/**
	 * Fetches all references of all types from the entity, including their reference-level attributes and, optionally, the bodies of referenced group entities. Use `managedReferencesBehaviour` to control whether only references to existing entities are returned. Place inside `entityFetch` to include references in the result.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(EXISTING, attributeContent("code"), entityGroupFetch(attributeContent("name")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("c295755abcd447f26e49585a195f744f")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return new ReferenceContent(
			managedReferencesBehaviour,
			ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
			null, groupEntityRequirement
		);
	}

	/**
	 * Fetches all references of all types for each entity, returning the primary keys of referenced entities and, optionally, their full bodies and group entities. Use `managedReferencesBehaviour` to control whether to include references to non-existent entities. Nested `entityRequirement` and `groupEntityRequirement` allow fetching referenced entity and group bodies.  
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("4be62f7bbb5ef63ece4298cde729c969")
	@Nonnull
	static ReferenceContent referenceContentAll(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return new ReferenceContent(managedReferencesBehaviour, entityRequirement, groupEntityRequirement);
	}

	/**
	 * Retrieves all references of all types from the entity, including their reference-level attributes and, optionally, the full bodies of referenced and group entities. Use `managedReferencesBehaviour` to control whether only existing referenced entities are included. 
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(EXISTING, entityFetch(attributeContent("name")), entityGroupFetch())
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("4615a4e276a63d6b0770fa235a568717")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return new ReferenceContent(
			managedReferencesBehaviour, AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement
		);
	}

	/**
	 * Fetches all references of all types from the entity, including all reference-level attributes, with optional filtering for only existing referenced entities. Also allows fetching full bodies of referenced entities and their groups, plus reference attributes.  
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(EXISTING, attributeContent("sortOrder"), entityFetch(attributeContent("code")), entityGroupFetch(attributeContent("code")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("9790ddae7f9e784c86ae767abf60f35c")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return new ReferenceContent(
			managedReferencesBehaviour,
			ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
			entityRequirement, groupEntityRequirement
		);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and optionally limiting the number of references using a chunking constraint (e.g., page or strip). Use inside `entityFetch` to include references; without it, references are omitted.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent("categories", page(1, 10))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("728c895da7540b28bdc0a4631702d5df")
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable ChunkingRequireConstraint chunk) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, null, null, null, null, chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and selected reference-level attributes. Supports chunking to paginate large reference sets. Place inside `entityFetch` to retrieve associated references and their metadata.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         page(1, 10),
	 *         "isPrimary", "sortOrder"
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("2b1d8b87165bd075fcaaffc6b729c12c")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable ChunkingRequireConstraint chunk,
		@Nullable String... attributeNames
	) {
		requireName(referenceName, "referenceName");
		requireNonNullItems(attributeNames, "attributeNames");
		return new ReferenceContent(
				referenceName, null, null,
				attributeContent(attributeNames), null, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all reference-level attributes (metadata on the association itself). Supports chunking via a paging or strip constraint to limit the number of returned references—useful for entities with large reference sets. Place inside `entityFetch` to retrieve reference details along with their attributes.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes("categories", page(1, 20))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("dbff503150815f1fdd6447ca8d8539fe")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, null, null,
				AttributeContent.ALL_ATTRIBUTES, null, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record itself. Optionally, you can limit the returned references using a chunking constraint (e.g., pagination). Place inside `entityFetch` to retrieve reference-level metadata alongside the reference keys.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         attributeContent("sortOrder", "isPrimary"),
	 *         page(1, 20)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("bb389cabbb4489c424146def2579e571")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, null, chunk
			);
	}

	/**
	 * Fetches references with the given names from an entity, returning their primary keys and, optionally, referenced entity/group bodies. Supports paginating large reference sets via a chunking constraint. Place inside `entityFetch`; without it, references are excluded.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(page(1, 10), "categories", "brand")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("0cf6b07fb2e82248e150019e369b3486")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable ChunkingRequireConstraint chunk,
		@Nullable String... referenceName) {
		requireNonNullItems(referenceName, "referenceName");
		if (referenceName == null) {
			return new ReferenceContent(chunk);
		}
		return new ReferenceContent(referenceName, null, null, chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, the full bodies of referenced entities (via nested `entityFetch`). Supports chunking large reference sets using a `Page` or `Strip` constraint. Place inside `entityFetch` to include references in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         entityFetch(attributeContent("name")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("691c1098f57145ca24dd0cc13745ecc5")
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, null, null, entityRequirement, null, chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys and all reference-level attributes, and loads the full bodies of referenced entities as defined by the nested `entityRequirement`. Supports chunking large reference sets via the `chunk` parameter. Use inside `entityFetch` to retrieve associated entities and their metadata.  
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         entityFetch(attributeContent("name")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("0aa41279d763eb508b49604236b09f5e")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, null, null,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning both the referenced entity primary keys and all reference-level attributes. Optionally, fetches full referenced entity bodies and supports chunking for large reference sets. Use inside `entityFetch` to include associations (e.g., product → brand) with their metadata and referenced entity data as needed.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "brand",
	 *         attributeContent("isPrimary"),
	 *         entityFetch(attributeContent("name")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("fd22fd716ee4c469ee9600167914841b")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning referenced entity primary keys and, if requested, group entity bodies via `groupEntityRequirement`. Use `chunk` to paginate large reference sets. Must be nested in `entityFetch`; without it, references are omitted.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         entityGroupFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("32023c77d3ddfeab418ae4a2c14c1213")
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName,
		@Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, null, null, null, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches the specified reference group from an entity, returning both the primary keys and all reference-level attributes for each referenced entity. Optionally, fetches the full bodies of the referenced group entities and supports chunking large reference sets. Use inside `entityFetch` to include references and their metadata in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         entityGroupFetch(attributeContent("name")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("ae9fe1773bd370b3acb0474e77cfc9a0")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, null, null,
				AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all attributes stored on the reference record itself. Optionally, you can fetch group entity bodies, reference-level attributes, and paginate large reference sets. Use within `entityFetch` to access associations (e.g., product → categories) and their metadata.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         attributeContent("sortOrder"),
	 *         entityGroupFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("10c766d5a7e4a24c0311490bfc5980b4")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, if requested, the full bodies of referenced entities and their groups. Supports chunking large reference sets via pagination. Place inside `entityFetch`; references are omitted without it.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         entityFetch(attributeContent("name")),
	 *         entityGroupFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("a9b67c7e6abef5cf10a09d9953091e9a")
	@Nonnull
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, null, null, entityRequirement, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys and all reference-level attributes, and optionally the full bodies of referenced entities and their groups. Supports chunking for large reference sets. Use inside `entityFetch` to retrieve associations like product → categories with their metadata.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         entityFetch(attributeContent("name")),
	 *         entityGroupFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("fc20e1ff28773fae3dba8b7106eea1aa")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, null, null,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes. Optionally, fetches full bodies of referenced entities and their groups, and supports filtering, ordering, and chunking of the reference set. Use within `entityFetch` to retrieve associated references with their metadata and related entity data.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "parameterValues",
	 *         attributeContent("sortOrder"),
	 *         entityFetch(attributeContent("code")),
	 *         entityGroupFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("236f2397f15511006874a4cf842a2bd2")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified types from an entity, returning their primary keys and, if nested requirements are provided, the full bodies of referenced entities. Supports chunking for large reference sets. Place inside `entityFetch` to include references in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         ["categories", "brand"],
	 *         entityFetch(attributeContent("name")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("f86afe84f9c45dda0017756e044ff743")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String[] referencedEntityTypes,
		@Nullable EntityFetch entityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		requireNonNullItems(referencedEntityTypes, "referencedEntityTypes");
		if (referencedEntityTypes == null && entityRequirement == null) {
			return new ReferenceContent(chunk);
		}
		if (referencedEntityTypes == null) {
			return new ReferenceContent(entityRequirement, null, chunk);
		}
		return new ReferenceContent(referencedEntityTypes, entityRequirement, null, chunk);
	}

	/**
	 * Fetches references of the specified types from an entity, returning their primary keys and, if requested, the bodies of referenced group entities. Supports chunking to limit the number of references returned. Use inside `entityFetch` to include references in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         ["categories", "brands"],
	 *         entityGroupFetch(attributeContent("name")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("7584a39449fdd34ab62ba39bb8079b85")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String[] referencedEntityTypes,
		@Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		requireNonNullItems(referencedEntityTypes, "referencedEntityTypes");
		if (referencedEntityTypes == null && groupEntityRequirement == null) {
			return new ReferenceContent(chunk);
		}
		if (referencedEntityTypes == null) {
			return new ReferenceContent(null, groupEntityRequirement, chunk);
		}
		return new ReferenceContent(referencedEntityTypes, null, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches references of the specified types from an entity, returning their primary keys and, optionally, the full bodies of referenced entities and their groups. Supports chunking for large reference sets. Place inside `entityFetch` to include references in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         ["categories", "brands"],
	 *         entityFetch(attributeContent("name")),
	 *         entityGroupFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("f1ab37c99420870912ac23aa01de2071")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String[] referencedEntityTypes,
		@Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk) {
		requireNonNullItems(referencedEntityTypes, "referencedEntityTypes");
		if (referencedEntityTypes != null) {
			return new ReferenceContent(referencedEntityTypes, entityRequirement, groupEntityRequirement, chunk);
		} else {
			return new ReferenceContent(entityRequirement, groupEntityRequirement, chunk);
		}
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and optionally limiting the result set with a filter and pagination (chunking). Place inside `entityFetch` to include references; without it, references are omitted from results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         filterBy(attributeEquals("isFeatured", true)),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("674a59aef39cd7c25fcc9c6686d84d83")
	@Nullable
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy,
		@Nullable ChunkingRequireConstraint chunk) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, filterBy, null, null, null, chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes. Supports filtering references by their attributes or referenced entity properties, and paginating large reference sets using chunking. Must be used inside `entityFetch`; without it, references are omitted from results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("78badeaf50ef0178751f524b75786cc9")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, null,
				AttributeContent.ALL_ATTRIBUTES, null, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record itself. Supports filtering references by reference attributes, selecting which reference attributes to include, and paginating large reference sets. Use inside `entityFetch` to retrieve associations (e.g., product → categories) with full reference metadata.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         attributeContent("isPrimary"),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("55635ba21a67862e4424f1b32ca81f6b")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable AttributeContent attributeContent,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, full referenced entity bodies. Supports filtering references (by reference attributes or referenced entity properties), paginating large sets, and combining with nested `entityFetch` for referenced entity data. Reference attributes are not included; use `referenceContentWithAttributes` for those.  
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         entityFetch(attributeContent("name")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("780c07bf46a5f6dd35d5d6a7d061a0d8")
	@Nullable
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy,
		@Nullable EntityFetch entityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, filterBy, null, entityRequirement, null, chunk);
	}

	/**
	 * Fetches the specified reference group(s) from an entity, returning both the referenced entity primary keys and all reference-level attributes. Supports filtering references by their own attributes, fetching full referenced entity bodies, and paginating large reference sets. Use inside `entityFetch` to include references in results; without it, references are omitted.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         entityFetch(attributeContent("name")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("a4ee600610fac745a541a2724e6fe388")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, null,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all reference-level attributes. Supports filtering references (by their own attributes or via nested entityHaving), fetching referenced entity bodies, and paginating large reference sets. Must be used inside `entityFetch`. Merges with other referenceContent constraints by uniting requested attributes and bodies, but conflicting filters or chunking are refused.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         attributeContent("sortOrder"),
	 *         entityFetch(attributeContent("name")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("39f05cdf7acd389bd818d331f2b78287")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, referenced group entity bodies, filtered and paginated as requested. Place inside `entityFetch` to include references in results. Filtering operates on reference attributes; use `entityHaving` to filter by referenced entity data. Combine with chunking to paginate large reference sets.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         entityGroupFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("2b1a0a142751238c3745c830a660e0c2")
	@Nullable
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy,
		@Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, filterBy, null, null, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record. Supports filtering references (by reference or referenced entity attributes), fetching group entity bodies, and paginating large reference sets. Must be used within `entityFetch`. Reference attributes are included only with this variant.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         entityGroupFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("7a9816f3d6b2249744d24feaf08248fb")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, null,
				AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes, with optional filtering, group entity fetching, and chunking. Filtering applies to reference attributes unless wrapped in `entityHaving`. Use this to retrieve rich reference metadata and control which references are returned and how they are paginated.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         attributeContent("sortOrder"),
	 *         entityGroupFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("dc6a7dff339d881bc1dcb15840d53af0")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the given type from an entity, returning referenced entity primary keys and optionally their full bodies, filtered and paginated as specified. Supports filtering on reference attributes, loading referenced/group entity data, and chunking large reference sets. Reference attributes are not included; use `referenceContentWithAttributes` to fetch them.  
	 * ```evitaql
	 * referenceContent(
	 *     "categories",
	 *     filterBy(attributeEquals("isPrimary", true)),
	 *     entityFetch(attributeContent("name")),
	 *     null,
	 *     page(1, 10)
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("7a9cc065c8513259e65186ef67de8c3d")
	@Nullable
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy,
		@Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, filterBy, null, entityRequirement, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes. Supports filtering, fetching referenced entity/group bodies, and chunking for large sets. Use inside `entityFetch` to retrieve associated references with their attributes and optionally filter or paginate the results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         entityFetch(attributeContent("name")),
	 *         null,
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("e4baafad8033fcc1336db85987883a6b")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, null,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes. Supports filtering and chunking on reference records, and can nest entity/group fetches for related data. Use to retrieve full reference metadata and bodies in one step.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         attributeContent("sortOrder"),
	 *         entityFetch(attributeContent("code")),
	 *         entityGroupFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("813d4603c30d8ac7d53f0aae3ce62508")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, referenced entity/group bodies. References are ordered as specified by `orderBy` (default: by primary key), and the number of returned references can be limited with `chunk`. Use within `entityFetch` to include associations like product → categories, brands, etc.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent("categories", orderBy(attributeNatural("name", ASC)), page(1, 10))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("8c5f9f25a228073b1942aafa3d5e0048")
	@Nullable
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable OrderBy orderBy,
		@Nullable ChunkingRequireConstraint chunk) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, null, orderBy, null, null, chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes, with optional ordering and chunking (pagination) applied to the returned references. Use this to retrieve reference metadata and control result order/size in one step.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("c789153635cf61d3d65ac0234e0bdee5")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable ChunkingRequireConstraint chunk) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, null, orderBy,
				AttributeContent.ALL_ATTRIBUTES, null, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all attributes stored on the reference record itself. Supports custom ordering and chunking (pagination) of the reference set. Use when you need both reference metadata and efficient navigation of large or ordered reference lists.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         attributeContent("isPrimary"),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("4dc2ab7af92600d95ec55bc778c4811b")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, null, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning referenced entity primary keys and, if requested, their full bodies. Allows ordering references via `orderBy` (on reference attributes or referenced entity properties), and paginating large sets with chunking. Place inside `entityFetch` to include references in results; without it, references are omitted. For reference attributes, use `referenceContentWithAttributes`.  
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         entityFetch(attributeContent("name")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("0f6d7f298e94868f759496ac2ae55c82")
	@Nullable
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, null, orderBy, entityRequirement, null, chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all reference-level attributes. Allows ordering the references, fetching full referenced entity bodies, and paginating large reference sets. Place inside `entityFetch` to retrieve reference data; without it, references are omitted.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         entityFetch(attributeContent("name")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("abc0a3d4e75705273174bb6724bf914f")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
			referenceName, null, orderBy,
			AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, chunk
		);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys and all reference-level attributes, with optional ordering, nested referenced entity fetch, and chunking (pagination). Use inside `entityFetch` to retrieve associations like product → categories, including metadata about the relationship itself. Ordering applies to reference attributes; chunking limits the number of references returned. 
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         attributeContent("isPrimary"),
	 *         entityFetch(attributeContent("name")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("3646f15207beb61c42b3ce10484b4bbc")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, null, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning the primary keys of referenced entities and, if requested, their group entity bodies. Allows ordering references via `orderBy` and paginating large sets with chunking. Place inside `entityFetch` to include references in results; without it, references are omitted.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         orderBy(attributeNatural("priority", DESC)),
	 *         entityGroupFetch(attributeContent("name")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("19e4d46e241598f172beb999ee5f1090")
	@Nullable
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable OrderBy orderBy,
		@Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, null, orderBy, null, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches references of the specified type, including their reference-level attributes, with optional ordering, group entity fetch, and chunking (pagination). Use inside `entityFetch` to retrieve both reference metadata and referenced entity/group bodies. References are ordered and paginated as specified, and group entity bodies are loaded according to the nested constraint.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         entityGroupFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("781d4d04317784407285118786743174")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, null, orderBy,
				AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including their primary keys, reference-level attributes, and optionally referenced group entity bodies. Supports custom ordering and pagination of references. Use inside `entityFetch` to retrieve associated references with their metadata and related group data.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         attributeContent("isPrimary"),
	 *         entityGroupFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("d1869482f8fea1337256c205c2bf11a9")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, null, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and optionally the full bodies of referenced and group entities. Supports custom ordering via `orderBy` and chunking for pagination. Place inside `entityFetch`.  
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         orderBy(attributeNatural("priority", DESC)),
	 *         entityFetch(attributeContent("name")),
	 *         entityGroupFetch(attributeContent("type")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("59e155af15d25d8ed84dfbcfdf9afb92")
	@Nullable
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, null, orderBy, entityRequirement, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes. Allows ordering the references, fetching full bodies of referenced entities and their groups, and paginating large reference sets. Use inside `entityFetch` to retrieve rich association data for a named reference.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         entityFetch(attributeContent("name")),
	 *         entityGroupFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("8edba9e1afa0fdf461273fbc73df6f67")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, null, orderBy,
				AttributeContent.ALL_ATTRIBUTES,
				entityRequirement, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including reference-level attributes, with optional ordering, attribute selection, referenced entity/group bodies, and chunking (pagination). Use inside `entityFetch` to retrieve both the referenced entity primary keys and metadata stored on the reference record. Reference ordering and filtering apply to attributes on the reference itself unless wrapped in `entityHaving`. For large reference sets, use chunking to limit results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "parameterValues",
	 *         orderBy(entityProperty(attributeNatural("name", ASC))),
	 *         attributeContent("code"),
	 *         entityFetch(attributeContent("code")),
	 *         entityGroupFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("0d6696c920c5d1bdcb56ceac6e968cab")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, null, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches all references of all types for each entity, returning their primary keys and, optionally, the full bodies of referenced entities (as specified by `entityRequirement`). Supports chunking via `chunk` to paginate large reference sets. Reference attributes are not included; use `referenceContentAllWithAttributes()` for those. Must be used inside `entityFetch`.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAll(
	 *         entityFetch(attributeContent("name")),
	 *         page(1, 20)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("0ae8563af631dbf18e3a764b207dc151")
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable EntityFetch entityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		return new ReferenceContent(entityRequirement, null, chunk);
	}

	/**
	 * Fetches all references of all types from the entity, including their reference-level attributes and optionally the full bodies of referenced entities (via `entityFetch`). Supports chunking large reference sets with pagination or strip constraints. Use inside `entityFetch` to retrieve comprehensive reference data for each entity.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
	 *         entityFetch(attributeContent("name")),
	 *         page(1, 20)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("3e3c28d99afd71c0949db51a3aa9434b")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(
		@Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return new ReferenceContent(AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, chunk);
	}

	/**
	 * Fetches all references of all types for each entity, returning both the primary keys and all reference-level attributes, and optionally the full bodies of referenced entities and their groups. Supports filtering, ordering, and chunking of large reference sets.  
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
	 *         attributeContent("sortOrder"),
	 *         entityFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("8065d9e8f017f929b87525c3d60c9157")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return new ReferenceContent(
			ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
			entityRequirement, null, chunk
		);
	}

	/**
	 * Fetches all references of all types for each entity, returning the primary keys of referenced entities and, if specified, the bodies of their group entities. Supports chunking to limit the number of references returned per entity. Reference attributes are not included; use `referenceContentAllWithAttributes()` for those.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("ba9cba315a1448f9a6e4d1c988e3a567")
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		return new ReferenceContent(null, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches all references of all types from the entity, including reference-level attributes and optionally the bodies of referenced group entities, with support for chunking large reference sets. Use inside `entityFetch` to retrieve all associations and their metadata in one request.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
	 *         entityGroupFetch(attributeContent("code")),
	 *         page(1, 20)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("65a0efe784b3b8e3aa80fc685b4b8f92")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return new ReferenceContent(
			AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, chunk
		);
	}

	/**
	 * Fetches all references of all types from the entity, including their primary keys and all reference-level attributes, with optional fetching of group entity bodies and support for chunking large reference sets. Use within `entityFetch` to ensure references and their metadata are included in the result.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
	 *         attributeContent("sortOrder"),
	 *         entityGroupFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("24b7ce04f62df5e506c4ab9b8fbb9b0f")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return new ReferenceContent(
			ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
			null, groupEntityRequirement, chunk
		);
	}

	/**
	 * Fetches all reference groups of all types for each entity, returning referenced entity primary keys and, if specified, their full bodies and group entities. Supports chunking to limit result size. Reference-level attributes are excluded; use `referenceContentAllWithAttributes()` to include them.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("43f8af6b6fedc53a1c6048a983a44abf")
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		return new ReferenceContent(entityRequirement, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches all references of all types for each entity, including both the primary keys and all reference-level attributes, and optionally loads the full bodies of referenced entities and their groups. Supports chunking for large reference sets.  
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
	 *         entityFetch(attributeContent("name")),
	 *         entityGroupFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("92a58113c3821cb73e40925bb88a379e")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return new ReferenceContent(
			AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement, chunk
		);
	}

	/**
	 * Fetches all references of all types from the entity, including their reference-level attributes and optionally the full bodies of referenced entities and their groups. Supports filtering, ordering, and pagination of references via nested constraints. Use inside `entityFetch` to retrieve complete reference details for all associations.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
	 *         attributeContent("sortOrder"),
	 *         entityFetch(attributeContent("code")),
	 *         entityGroupFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("6d5de12bd76572afb41658bb6010aa9d")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return new ReferenceContent(
			ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
			entityRequirement, groupEntityRequirement, chunk
		);
	}

	/**
	 * Fetches all references of all types for each entity, returning only the referenced entities' primary keys. You can control whether to include references to non-existent entities using `ManagedReferencesBehaviour`, and limit the number of references returned with a chunking constraint.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("e90f204d9719c33a6c18a2ddaf8252c4")
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable ManagedReferencesBehaviour managedReferencesBehaviour, @Nullable ChunkingRequireConstraint chunk) {
		return new ReferenceContent(managedReferencesBehaviour, chunk);
	}

	/**
	 * Fetches all references of all types from the entity, including reference-level attributes, and returns referenced entity primary keys and optional bodies. Controls inclusion of references to missing entities via `managedReferencesBehaviour`, and paginates results using the provided chunking constraint.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("9d2c919c5f7504009bdfd544ec05f8d9")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return new ReferenceContent(managedReferencesBehaviour, AttributeContent.ALL_ATTRIBUTES, chunk);
	}

	/**
	 * Fetches all references of all types from the entity, including their primary keys and all reference-level attributes, with optional chunking for large sets. Use `managedReferencesBehaviour` to control whether to include references to non-existent entities. Combine with `attributeContent` to specify which reference attributes to fetch.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(EXISTING, attributeContent("sortOrder"), page(1, 20))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("fb679d1c587b63fb85fafe3c99de8cdb")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable AttributeContent attributeContent,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return new ReferenceContent(
			managedReferencesBehaviour,
			ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
			chunk
		);
	}

	/**
	 * Fetches references of the specified type from an entity, returning referenced entity primary keys and optionally their full bodies. The `managedReferencesBehaviour` controls whether to include references to non-existent entities. Use `chunk` to paginate large reference sets. Place inside `entityFetch` to include references in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "categories", page(1, 20))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("7b9bb1f6714a6bb3f79c16435d9f6da8")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName, @Nullable ChunkingRequireConstraint chunk) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(managedReferencesBehaviour, referenceName, null, null, null, null, chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, returning both the referenced entity primary keys and the attributes stored on the reference record. You can control whether to include only references to existing entities, paginate large reference sets, and specify which reference attributes to return. Place inside `entityFetch` to retrieve associations like product → brand or categories, including reference-level metadata. Merges with other referenceContent constraints by uniting attributes and nested bodies, but conflicting filters or chunking are refused.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "categories", page(1, 10), "sortOrder", "isPrimary")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("e16ef398f5c291dd70579ff6d328833a")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable ChunkingRequireConstraint chunk,
		@Nullable String... attributeNames
	) {
		requireName(referenceName, "referenceName");
		requireNonNullItems(attributeNames, "attributeNames");
		return new ReferenceContent(managedReferencesBehaviour, referenceName, null, null,
				attributeContent(attributeNames), null, null, chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all reference-level attributes, with control over whether to include only references to existing entities or all references. Supports chunking to paginate large reference sets.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("63ac32902d41567a49afc13421379810")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				AttributeContent.ALL_ATTRIBUTES, null, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all reference-level attributes. You can control whether to return only references to existing entities, and limit the result set using chunking (pagination). Use this to retrieve reference metadata (like "sortOrder") along with referenced entity links.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "categories", attributeContent("sortOrder"), page(1, 10))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("e3e4242ce5a46bea5cdbc836a0e189d0")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, null, chunk
			);
	}

	/**
	 * Fetches references of specified types from an entity, returning their primary keys and, optionally, referenced entity bodies. The `managedReferencesBehaviour` controls whether to include references to missing entities, and `chunk` enables pagination of large reference sets. Place inside `entityFetch` to retrieve associations like product → brand or categories; without it, references are omitted.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, page(1, 10), "categories", "brand")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("f47adf2e0574c82f3818bea1306ad660")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable ChunkingRequireConstraint chunk, @Nullable String... referenceName) {
		requireNonNullItems(referenceName, "referenceName");
		if (referenceName == null) {
			return new ReferenceContent(managedReferencesBehaviour, chunk);
		}
		return new ReferenceContent(managedReferencesBehaviour, referenceName, null, null, chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, the full bodies of referenced entities. The `managedReferencesBehaviour` controls whether to include only references to existing entities or all references. Supports nested `entityFetch` for loading referenced entity bodies and chunking for paginating large reference sets. Reference attributes are not included—use `referenceContentWithAttributes` for those. Place inside `entityFetch` to retrieve references; otherwise, they are omitted.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", entityFetch(attributeContent("name")), page(1, 10))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("6818358426b27025b621a18779897293")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName, @Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(managedReferencesBehaviour, referenceName, null, null, entityRequirement, null,
			chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, including their primary keys, reference-level attributes, and optionally the full bodies of referenced entities. You can control whether to include only references to existing entities, and paginate large reference sets. Place inside `entityFetch`.  
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "categories", entityFetch(attributeContent("name")), page(1, 10))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("f205a7c14621d88c2e2b198c30b29453")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, chunk
			);
	}

	/**
	 * Fetches references of the given name from an entity, including both the referenced entities' primary keys and all attributes stored on the reference record itself. Optionally, you can specify whether to include only references to existing entities, fetch referenced entity bodies, and paginate large reference sets. Useful for retrieving rich association metadata and referenced data in one request.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", attributeContent("isPrimary"), entityFetch(attributeContent("code")), page(1, 10))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("28be01a69cb5c82e1531a7c4d6121825")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning primary keys of referenced entities and optionally their group entity bodies. Use `managedReferencesBehaviour` to control whether only references to existing entities are included. Combine with chunking to limit the number of references fetched. Place inside `entityFetch` to include references in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "categories", entityGroupFetch(attributeContent("name")), page(1, 10))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("29414beae0c02bfda67d296a6c5f912e")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName, @Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(managedReferencesBehaviour, referenceName, null, null, null, groupEntityRequirement,
			chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all reference-level attributes. You can control whether only existing referenced entities are returned using `ManagedReferencesBehaviour`. Optionally fetches group entity bodies and supports chunking to limit the number of returned references.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", entityGroupFetch(attributeContent("code")), page(1, 10))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("06f7de41b1d58d0b3c4df9e06b8e805e")

	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including reference-level attributes, with optional filtering for existing targets, group entity fetching, and chunking for large sets. Use this to retrieve both the referenced entity primary keys and metadata about each association.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "categories", attributeContent("sortOrder"))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("5ce82eb4039487b24d6838e70316ccea")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, full referenced entity and group bodies. Controls whether to include references to non-existent entities via `managedReferencesBehaviour`. Supports nested fetches, filtering, ordering, and chunking for fine-grained control over reference data retrieval.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", entityFetch(attributeContent("name")), null, null)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("63f5d0d04338efe74a0df6f4fd91e244")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName, @Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(managedReferencesBehaviour, referenceName, null, null, entityRequirement,
			groupEntityRequirement, chunk);
	}

	/**
	 * Fetches references of the specified name from an entity, including both the referenced entity/group bodies and all reference-level attributes. Allows filtering out references to missing entities via `managedReferencesBehaviour`, and supports nested fetches and chunking for large sets.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", entityFetch(attributeContent("code")), null, page(1, 10))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("2696d6fe8b77c0c9ca138c2fadca9a24")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including their primary keys, reference-level attributes, and optionally the full bodies of referenced and group entities. Supports filtering, ordering, and chunking of references, and can suppress references to missing entities based on the chosen `ManagedReferencesBehaviour`. Use inside `entityFetch` to retrieve associations and their metadata.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         EXISTING, "brand",
	 *         attributeContent("isPrimary"),
	 *         entityFetch(attributeContent("name")),
	 *         null,
	 *         null
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("e8e8126b90ba3d1717e97cf11cd8392b")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, allowing you to filter, order, and paginate the reference set. Returns referenced entity primary keys and, if nested constraints are provided, can fetch referenced entity bodies or groups. Filtering and ordering operate on reference attributes by default; use `entityHaving` or `entityProperty` to target referenced entities. Multiple `referenceContent` constraints for the same reference type are merged, but conflicting filters, orders, or chunking are refused.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("98e9af8fd41b1a48d0afb076f91d391f")
	@Nullable
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy, @Nullable ChunkingRequireConstraint chunk) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, filterBy, orderBy, null, null, chunk);
	}

	/**
	 * Fetches references of the specified type, including their primary keys, all reference-level attributes, and optionally applies filtering, ordering, and pagination to the reference set. Place inside `entityFetch` to retrieve associated entities and metadata; without it, references are omitted from results. Filtering and ordering operate on reference attributes unless wrapped for entity/group properties.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("854d548dea518ffeba24793fc210aa6c")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, orderBy,
				AttributeContent.ALL_ATTRIBUTES, null, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including reference-level attributes, with optional filtering, ordering, and pagination. Filtering and ordering apply to reference attributes unless wrapped for referenced entity fields. Only references matching the filter are returned, ordered and chunked as specified.  
	 * ```evitaql
	 * referenceContentWithAttributes(
	 *     "categories",
	 *     filterBy(attributeEquals("isPrimary", true)),
	 *     orderBy(attributeNatural("sortOrder", ASC)),
	 *     attributeContent("isPrimary", "sortOrder"),
	 *     page(1, 10)
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("ca28f09eb19ee64057bc6ead562ee71d")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, referenced entity bodies, filtered, ordered, and paginated as requested. Filtering and ordering operate on reference attributes unless wrapped for entity/group properties. Use inside `entityFetch`.  
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "parameterValues",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         entityFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("fb350f8fd287b71561f094dadde6bc00")
	@Nullable
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, filterBy, orderBy, entityRequirement, null, chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record itself. Supports filtering, ordering, and chunking of references, and can nest entity/group fetches to retrieve full referenced bodies. Filtering and ordering operate on reference attributes unless wrapped for referenced entity properties. Returns only references matching all criteria, with merging rules for multiple requirements.  
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         entityFetch(attributeContent("name")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("275f13543187dfd86ebf899e554af3e3")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, orderBy,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including their primary keys, reference-level attributes, and optionally the full bodies of referenced entities or groups. Supports filtering, ordering, and chunking of references, with filters applying to reference attributes unless wrapped for entity-level filtering. By default, includes references even if the target entity doesn't exist; use managed reference behavior to restrict this. Merges with other referenceContent constraints by uniting attributes and requirements, but conflicting filters, orders, or chunking are refused.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         attributeContent("isPrimary", "sortOrder"),
	 *         entityFetch(attributeContent("name")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("0869ad6d60287ce860edc55350f0b10c")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and optionally referenced group entities, filtered, ordered, and chunked as specified. Place inside `entityFetch` to include references in results; supports filtering and ordering by reference attributes, and pagination for large sets.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         entityGroupFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("65eecfe31cd6cf7ce5b04cac2719ac95")
	@Nullable
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy, @Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, filterBy, orderBy, null, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches references of the specified type, including their reference-level attributes, with optional filtering, ordering, group entity fetching, and chunking. Filtering and ordering apply to reference attributes; use `entityHaving` for referenced entity criteria. Only references matching the filter and chunk constraints are returned, and reference attributes are always included.
	 *
	 * ```evitaql
	 * referenceContentWithAttributes(
	 *     "parameterValues",
	 *     filterBy(attributeEquals("isPrimary", true)),
	 *     orderBy(attributeNatural("sortOrder", ASC)),
	 *     entityGroupFetch(attributeContent("code")),
	 *     page(1, 10)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("81d9b75f3cfa47566cb7c47f9528ac1b")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy, @Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, orderBy,
				AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning both the referenced entity primary keys and all attributes stored on the reference record. Supports filtering, ordering, group entity fetching, and pagination of references. Use to retrieve association metadata (e.g., "sortOrder") alongside referenced entities. Returns only references matching the filter, ordered as requested, and paged if chunking is provided.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         attributeContent("isPrimary", "sortOrder"),
	 *         entityGroupFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("60b535d56b3e982a52c38f6e14c45140")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, optionally filtering, ordering, paginating, and loading full referenced entity or group bodies. Reference attributes are not included—use `referenceContentWithAttributes` for those. Filtering and ordering operate on reference record attributes unless wrapped for referenced entities. Multiple `referenceContent` constraints on the same reference type are merged, but conflicting filters or chunking are refused.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         entityFetch(attributeContent("name")),
	 *         null,
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("a0b3193a8ebefb717758445d429b9ed6")
	@Nullable
	static ReferenceContent referenceContent(@Nonnull String referenceName, @Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(referenceName, filterBy, orderBy, entityRequirement, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, returning both the referenced entity primary keys and all reference-level attributes. Supports filtering, ordering, and chunking of references, and can nest entity/group fetches to retrieve referenced entity bodies. Use when you need both the association metadata and referenced entity data in a single query.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "parameterValues",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         entityFetch(attributeContent("code")),
	 *         entityGroupFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("cc0b1e628b14fe90a3f0dbf77a0634d2")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, orderBy,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes. Supports filtering and ordering references (by reference or referenced entity attributes), fetching referenced entity/group bodies, and paginating large reference sets. Returns only references matching the provided filter and order, with full attribute data for each reference.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         attributeContent("isPrimary", "sortOrder"),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("28fbd9dd23e50cee402e09627b4e95ec")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		requireName(referenceName, "referenceName");
		return new ReferenceContent(
				referenceName, filterBy, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches all references of all types for each entity, returning the primary keys of referenced entities and, optionally, their full bodies via nested `entityFetch`. The `managedReferencesBehaviour` controls whether to include references to missing entities. Supports pagination with a chunking constraint. For reference-level attributes, use `referenceContentAllWithAttributes()`.  
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("d5c0d277902a114764d453f16339eafc")
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable ManagedReferencesBehaviour managedReferencesBehaviour, @Nullable EntityFetch entityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		return new ReferenceContent(managedReferencesBehaviour, entityRequirement, null, chunk);
	}

	/**
	 * Fetches all references of all types for an entity, including their reference-level attributes, with optional full bodies of referenced entities and chunking for large sets. Controls whether to include only references to existing entities or all, based on the specified `ManagedReferencesBehaviour`. Use inside `entityFetch` to retrieve complete reference information for each entity.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(EXISTING, entityFetch(attributeContent("code")), page(1, 10))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("4375b76b32978f8ed442e477dd443197")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return new ReferenceContent(
			managedReferencesBehaviour, AttributeContent.ALL_ATTRIBUTES,
			entityRequirement, null, chunk
		);
	}

	/**
	 * Fetches all references of all types from the entity, including their reference-level attributes, with optional referenced entity bodies and chunking. The `managedReferencesBehaviour` controls whether to include only references to existing entities. Use within `entityFetch` to retrieve full reference info for all associations.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(EXISTING, attributeContent("sortOrder"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("b163d9178663f2d850409bab3c546ef3")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return new ReferenceContent(
			managedReferencesBehaviour,
			ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
			entityRequirement, null, chunk
		);
	}

	/**
	 * Fetches all references of all types for each entity, returning the primary keys of referenced entities and, optionally, their group entity bodies and paginated chunks. The `managedReferencesBehaviour` controls whether only existing references are included. Use this to retrieve all references with optional group fetching and paging in a single call.  
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("4d1e111e6fb5f2459d26a20560c7df41")
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable ManagedReferencesBehaviour managedReferencesBehaviour, @Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		return new ReferenceContent(managedReferencesBehaviour, null, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches all references of all types from the entity, including their reference-level attributes, with optional filtering for references whose target entities exist (`EXISTING`) or any (`ANY`). Allows fetching group entity bodies and paginating large reference sets. Use inside `entityFetch` to include all reference associations with their metadata.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(EXISTING, entityGroupFetch(attributeContent("code")), page(1, 10))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("b777360f2fdb771383977fec45e91e98")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return new ReferenceContent(
			managedReferencesBehaviour, AttributeContent.ALL_ATTRIBUTES,
			null, groupEntityRequirement, chunk
		);
	}

	/**
	 * Fetches all references of all types from the entity, including reference-level attributes and optionally group entity bodies, with control over inclusion of references to non-existent entities via `ManagedReferencesBehaviour`. Supports chunking for large reference sets.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(EXISTING, attributeContent("isPrimary"), entityGroupFetch(attributeContent("code")), page(1, 10))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("5dcfba91e390f8612d57be233ca315c0")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return new ReferenceContent(
			managedReferencesBehaviour,
			ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
			null, groupEntityRequirement, chunk
		);
	}

	/**
	 * Fetches all references of all types from the entity, returning referenced entity primary keys and optionally their full bodies and group entities. Allows specifying whether to include only references to existing entities, fetching nested entity/group bodies, and paginating large reference sets. Use within `entityFetch` to retrieve all references at once, with control over referential integrity and chunking.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAll(EXISTING, entityFetch(attributeContent("code")), entityGroupFetch(attributeContent("name")), page(1, 10))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("6811fa14f61f254728a6b878b8d91737")
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable ManagedReferencesBehaviour managedReferencesBehaviour, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		return new ReferenceContent(managedReferencesBehaviour, entityRequirement, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches all references of all types from entities, including their primary keys, all reference-level attributes, and optionally the full bodies of referenced entities and groups. Honors the specified managed references behavior, supports chunking, and can nest entity/group fetches for richer results. 
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(EXISTING, entityFetch(attributeContent("code")), entityGroupFetch(attributeContent("name")), page(1, 10))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("259a81d53e5196b1d5c8c1ed04432e5c")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return new ReferenceContent(
			managedReferencesBehaviour, AttributeContent.ALL_ATTRIBUTES,
			entityRequirement, groupEntityRequirement, chunk
		);
	}

	/**
	 * Fetches all references of all types for each entity, including reference-level attributes, with optional nested fetches for referenced entities and groups. Supports filtering out references to missing entities via `ManagedReferencesBehaviour`. Chunking can limit large reference sets.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(EXISTING, attributeContent("sortOrder"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("da2a2b9e35549e78bfc43500bc2142de")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return new ReferenceContent(
			managedReferencesBehaviour,
			ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
			entityRequirement, groupEntityRequirement, chunk
		);
	}

	/**
	 * Fetches the full chain of ancestor primary keys for a hierarchical entity, from its immediate parent up to the root, without loading any additional data for parent nodes. Use inside `entityFetch` for simple breadcrumb-style hierarchy placement.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#hierarchy-content)
	 *
	 * @see io.evitadb.api.query.require.HierarchyContent
	 */
	@SourceHash("16fbfdb84f3b94b6d346cf51d35c7f9c")
	@Nonnull
	static HierarchyContent hierarchyContent() {
		return new HierarchyContent();
	}

	/**
	 * Fetches the ancestor chain (parent primary keys) of a hierarchical entity, limiting traversal by the given `stopAt` constraint (e.g., only up to the immediate parent or grandparent). No parent entity bodies are loaded—only the ancestor keys are returned.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     hierarchyContent(
	 *         stopAt(distance(1))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#hierarchy-content)
	 *
	 * @see io.evitadb.api.query.require.HierarchyContent
	 */
	@SourceHash("5fe6d3ef177f85bc8bb60e1dc1c2acb0")
	@Nonnull
	static HierarchyContent hierarchyContent(@Nullable HierarchyStopAt stopAt) {
		return stopAt == null ? new HierarchyContent() : new HierarchyContent(stopAt);
	}

	/**
	 * Fetches the full chain of ancestor entities for a hierarchical entity, loading the complete body of each parent node as specified by the nested `EntityFetch`. Useful for retrieving breadcrumb-style hierarchy data with parent attributes or associated data included.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     hierarchyContent(
	 *         entityFetch(
	 *             attributeContent("code", "name")
	 *         )
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#hierarchy-content)
	 *
	 * @see io.evitadb.api.query.require.HierarchyContent
	 */
	@SourceHash("b7ac0fde222392b377ac604c04c6c9a1")
	@Nonnull
	static HierarchyContent hierarchyContent(@Nullable EntityFetch entityFetch) {
		return entityFetch == null ? new HierarchyContent() : new HierarchyContent(entityFetch);
	}

	/**
	 * Fetches the full ancestor chain of a hierarchical entity up to the specified stop point, loading the complete body for each parent node as defined by the nested `EntityFetch`. Use to retrieve parent entities with selected data (attributes, associated data, etc.) up to a certain depth.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     hierarchyContent(
	 *         stopAt(distance(2)),
	 *         entityFetch(
	 *             attributeContent("code", "name")
	 *         )
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#hierarchy-content)
	 *
	 * @see io.evitadb.api.query.require.HierarchyContent
	 */
	@SourceHash("349ac12fb02d47601348a4b54c3e5eb1")
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
	 * Fetches the full ancestor chain (parent primary keys) for a hierarchical entity, controlling how traversal behaves if an ancestor's body can't be loaded. Use `MATCHING` (default) to cut the chain below unmaterializable ancestors, or `COMPLETE` to include them as bodyless pointers.  
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#hierarchy-content)
	 *
	 * @see io.evitadb.api.query.require.HierarchyContent
	 */
	@SourceHash("0653b7b55615f26a92e73892f90d8c57")
	@Nonnull
	static HierarchyContent hierarchyContent(@Nullable HierarchyParentsBehaviour parentsBehaviour) {
		return new HierarchyContent(parentsBehaviour);
	}

	/**
	 * Fetches the ancestor chain of a hierarchical entity, returning parent primary keys up to the root, but limits traversal depth via `stopAt` and controls behavior above unmaterializable ancestors using the specified `parentsBehaviour` (MATCHING or COMPLETE).
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#hierarchy-content)
	 *
	 * @see io.evitadb.api.query.require.HierarchyContent
	 */
	@SourceHash("a4add517a5e5ad06f7ca845db23b79af")
	@Nonnull
	static HierarchyContent hierarchyContent(
		@Nullable HierarchyParentsBehaviour parentsBehaviour,
		@Nullable HierarchyStopAt stopAt
	) {
		return new HierarchyContent(parentsBehaviour, stopAt);
	}

	/**
	 * Fetches the ancestor chain of a hierarchical entity, returning each parent up to the root. With `parentsBehaviour`, controls whether traversal continues above missing or unmaterializable ancestors, and with nested `entityFetch`, loads full parent bodies as specified.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     hierarchyContent(
	 *         COMPLETE,
	 *         entityFetch(
	 *             attributeContent("code", "name")
	 *         )
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#hierarchy-content)
	 *
	 * @see io.evitadb.api.query.require.HierarchyContent
	 */
	@SourceHash("d01166e2c258fb7d1ddcb8a05c9fa3bb")
	@Nonnull
	static HierarchyContent hierarchyContent(
		@Nullable HierarchyParentsBehaviour parentsBehaviour,
		@Nullable EntityFetch entityFetch
	) {
		return new HierarchyContent(parentsBehaviour, entityFetch);
	}

	/**
	 * Fetches the ancestor chain of a hierarchical entity, returning parent primary keys up to the root. Use `parentsBehaviour` to control behavior when a parent body can't be loaded, `stopAt` to limit depth, and `entityFetch` to specify which parent data to load.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     hierarchyContent(
	 *         COMPLETE,
	 *         stopAt(distance(2)),
	 *         entityFetch(
	 *             attributeContent("code", "name")
	 *         )
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#hierarchy-content)
	 *
	 * @see io.evitadb.api.query.require.HierarchyContent
	 */
	@SourceHash("091a261ad52715b5487452637106d892")
	@Nonnull
	static HierarchyContent hierarchyContent(
		@Nullable HierarchyParentsBehaviour parentsBehaviour,
		@Nullable HierarchyStopAt stopAt,
		@Nullable EntityFetch entityFetch
	) {
		return new HierarchyContent(parentsBehaviour, stopAt, entityFetch);
	}

	/**
	 * Controls which prices are loaded with an entity by specifying a fetch mode (NONE, RESPECTING_FILTER, or ALL) and optional extra price-list names. Extra price lists supplement, but do not affect, filtering or entity eligibility. Use within `entityFetch`.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     priceContentRespectingFilter("reference")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#price-content)
	 *
	 * @see io.evitadb.api.query.require.PriceContent
	 */
	@SourceHash("cc753b21aa1bddabcb6e85700ab316f4")
	@Nullable
	static PriceContent priceContent(@Nonnull PriceContentMode contentMode, @Nullable String... priceLists) {
		Assert.notNull(contentMode, "Argument `contentMode` must not be null: it has no default.");
		final String[] normalizedPriceLists = withoutUndefinedPriceLists(priceLists);
		if (ArrayUtils.isEmptyOrItsValuesNull(normalizedPriceLists)) {
			return new PriceContent(contentMode);
		} else {
			return new PriceContent(contentMode, normalizedPriceLists);
		}
	}

	/**
	 * Returns all prices stored on the entity, regardless of any price filter constraints. Use this mode when you need the complete set of price records for each entity, such as in administration or price management scenarios. Filtering still determines which entities are returned, but all their prices are included.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     priceContentAll()
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#price-content)
	 *
	 * @see io.evitadb.api.query.require.PriceContent
	 */
	@SourceHash("caa812eee6ee3d4be92d6f6370b963b4")
	@Nonnull
	static PriceContent priceContentAll() {
		return PriceContent.all();
	}

	/**
	 * Controls which prices are loaded with an entity, returning only those matching the current price filter (as defined by `priceInPriceLists`). Optionally, specify extra price-list names to include additional non-filtered prices for comparison or UI needs.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     priceContentRespectingFilter("reference")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#price-content)
	 *
	 * @see io.evitadb.api.query.require.PriceContent
	 */
	@SourceHash("d94ca3da76b7c8833a769307f15d03ff")
	@Nonnull
	static PriceContent priceContentRespectingFilter(@Nullable String... priceLists) {
		final String[] normalizedPriceLists = withoutUndefinedPriceLists(priceLists);
		return PriceContent.respectingFilter(normalizedPriceLists == null ? new String[0] : normalizedPriceLists);
	}

	/**
	 * Defines the default ordered list of price-list names to use when calculating accompanying prices (e.g., "list price", "previous price") for entities in the query. Acts as a fallback for parameterless `accompanyingPriceContent()` constraints, reducing repetition in nested fetches. The order matters: the first matching price in the list is used. Explicit price lists in `accompanyingPriceContent` override this default.
	 *
	 * ```evitaql
	 * defaultAccompanyingPriceLists("reference", "basic")
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/price#accompanying-price)
	 *
	 * @see io.evitadb.api.query.require.DefaultAccompanyingPriceLists
	 */
	@SourceHash("50bd588de6d230672757a0658ba75446")
	@Nullable
	static DefaultAccompanyingPriceLists defaultAccompanyingPriceLists(@Nullable String... priceList) {
		final String[] normalizedPriceList = withoutUndefinedPriceLists(priceList);
		if (ArrayUtils.isEmpty(normalizedPriceList)) {
			return null;
		}
		return new DefaultAccompanyingPriceLists(normalizedPriceList);
	}

	/**
	 * Requests calculation of an accompanying price labeled `"default"` using the price-list sequence defined by the `DefaultAccompanyingPriceLists` constraint in the same query. This constraint must be used together with a selling price calculation and cannot be combined with explicit price-list forms for the same name.  
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/price#accompanying-price)
	 *
	 * @see io.evitadb.api.query.require.AccompanyingPriceContent
	 */
	@SourceHash("ef031aafb5c70d1679c7ad7e8f9c4cea")
	@Nullable
	static AccompanyingPriceContent accompanyingPriceContentDefault() {
		return new AccompanyingPriceContent();
	}

	/**
	 * Requests calculation of an accompanying price (e.g., "original" or "reference" price) alongside the selling price, using a custom-ordered list of price lists but sharing currency, date, and type rules. The name labels the result; multiple named constraints can coexist. Requires selling price calculation in the same query.
	 *
	 * ```evitaql
	 * accompanyingPriceContent(
	 *     "originalPrice",
	 *     "reference",
	 *     "basic"
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/price#accompanying-price)
	 *
	 * @see io.evitadb.api.query.require.AccompanyingPriceContent
	 */
	@SourceHash("7b8e86cfad3463e699fe1d18dbe20da8")
	@Nullable
	static AccompanyingPriceContent accompanyingPriceContent(@Nonnull String name, @Nullable String... priceList) {
		requireName(name, "name");
		final String[] normalizedPriceList = withoutUndefinedPriceLists(priceList);
		if (ArrayUtils.isEmptyOrItsValuesNull(normalizedPriceList)) {
			return new AccompanyingPriceContent(name);
		}
		return new AccompanyingPriceContent(name, normalizedPriceList);
	}

	/**
	 * Selects whether prices with tax or without tax are used as the operative value for all price-sensitive query operations, affecting filters, sorting, and histograms. Defaults to WITH_TAX for B2C; set to WITHOUT_TAX for B2B use cases.
	 *
	 * ```evitaql
	 * priceType(WITHOUT_TAX)
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/price#price-type)
	 *
	 * @see io.evitadb.api.query.require.PriceType
	 */
	@SourceHash("85382b315ef88906f349358adc74ab9d")
	@Nonnull
	static PriceType priceType(@Nullable QueryPriceMode priceMode) {
		return new PriceType(priceMode == null ? QueryPriceMode.WITH_TAX : priceMode);
	}

	/**
	 * Returns a slice of entities using classic page-number pagination, with defaults of page 1 and size 20 if arguments are null. If the requested page exceeds the last, the first page is returned. Page size 0 yields an empty result but includes total count metadata.
	 *
	 * ```evitaql
	 * require(
	 *    page(1, 24)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/paging#page)
	 *
	 * @see io.evitadb.api.query.require.Page
	 */
	@SourceHash("31cbdf2cc1b604e75ac40907f9f0e568")
	@Nonnull
	static Page page(@Nullable Integer pageNumber, @Nullable Integer pageSize) {
		return new Page(pageNumber, pageSize);
	}

	/**
	 * Limits the result set to a specific page and size using classic page-number pagination, with optional spacing rules to reserve slots for non-entity content (e.g., ads). Defaults to page 1, size 20 if arguments are null. If the requested page is out of range, the first page is returned instead of an empty result.
	 *
	 * ```evitaql
	 * require(
	 *    page(
	 *       1, 20,
	 *       spacing(
	 *          gap(2, "($pageNumber - 1) % 2 == 0 && $pageNumber <= 6"),
	 *          gap(1, "$pageNumber % 2 == 0 && $pageNumber <= 6")
	 *       )
	 *    )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/paging#page)
	 *
	 * @see io.evitadb.api.query.require.Page
	 */
	@SourceHash("4616490b5234d2eeac58d0f1d753b4a9")
	@Nonnull
	static Page page(@Nullable Integer pageNumber, @Nullable Integer pageSize, @Nullable Spacing spacing) {
		return new Page(pageNumber, pageSize, spacing);
	}

	/**
	 * Defines a set of gap rules that reserve visual slots on specific pages for non-entity content (e.g., ads or banners) when paginating results. Each gap applies based on a boolean expression evaluated against the current page number, reducing the number of entities returned. Only effective within a `page` constraint and ignored if empty.
	 *
	 * ```evitaql
	 * require(
	 *    page(
	 *       1, 20,
	 *       spacing(
	 *          gap(2, "($pageNumber - 1) % 2 == 0 && $pageNumber <= 6"),
	 *          gap(1, "$pageNumber % 2 == 0 && $pageNumber <= 6")
	 *       )
	 *    )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/paging#spacing)
	 *
	 * @see io.evitadb.api.query.require.Spacing
	 */
	@SourceHash("09cdb5ac26a4c069f6fedb5fd17f7e72")
	@Nullable
	static Spacing spacing(@Nullable SpacingGap... gaps) {
		if (ArrayUtils.isEmptyOrItsValuesNull(gaps)) {
			return null;
		} else {
			return new Spacing(gaps);
		}
	}

	/**
	 * Reserves a fixed number of entity slots (gaps) on specific pages within a `spacing` container, reducing the number of entities returned on those pages. The gap applies only when the provided boolean expression (using `$pageNumber`) evaluates to true. Multiple gaps on the same page are additive.
	 *
	 * ```evitaql
	 * spacing(
	 *    gap(2, "($pageNumber - 1) % 2 == 0 && $pageNumber <= 6"),
	 *    gap(1, "$pageNumber % 2 == 0 && $pageNumber <= 6")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/paging#spacing-gap)
	 *
	 * @see io.evitadb.api.query.require.SpacingGap
	 */
	@SourceHash("dbc93babd34b385b0d68b5e27fbfc162")
	@Nullable
	static SpacingGap gap(int size, @Nullable Expression expression) {
		if (expression == null) {
			return null;
		} else {
			return new SpacingGap(size, expression);
		}
	}

	/**
	 * Reserves a fixed number of visual slots (`size`) on pages where the boolean `expression` (using `$pageNumber`) evaluates to true, within a `spacing` container. Multiple gaps on the same page are summed, reducing the number of returned entities accordingly.
	 *
	 * ```evitaql
	 * spacing(
	 *    gap(2, "($pageNumber - 1) % 2 == 0 && $pageNumber <= 6"),
	 *    gap(1, "$pageNumber % 2 == 0 && $pageNumber <= 6")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/paging#spacing-gap)
	 *
	 * @see io.evitadb.api.query.require.SpacingGap
	 */
	@SourceHash("a06bb76ca1f76d29599defaca67cda76")
	@Nullable
	static SpacingGap gap(int size, @Nullable String expression) {
		if (expression == null) {
			return null;
		} else {
			return new SpacingGap(size, ExpressionFactory.parse(expression));
		}
	}

	/**
	 * Returns a slice of entities using offset/limit pagination, ideal for infinite-scroll or scroll-position tracking. Offset and limit default to 0 and 20 if null. If offset exceeds available records, returns the first strip. No page concept—offset is direct.
	 *
	 * ```evitaql
	 * require(
	 *    strip(52, 24)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/paging#strip)
	 *
	 * @see io.evitadb.api.query.require.Strip
	 */
	@SourceHash("0bfa0be226d2f0aaa4e1854d2e3954df")
	@Nonnull
	static Strip strip(@Nullable Integer offset, @Nullable Integer limit) {
		return new Strip(offset, limit);
	}

	/**
	 * Triggers calculation of facet statistics for all faceted references in the entity schema, returning counts of matching entities per facet option (excluding the `userFilter` part of the query). By default, computes only counts; use additional arguments for impact prediction, filtering, ordering, or fetching related entity data.  
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("e60e70a37771e1a85f8bd20e3e867292")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary() {
		return new FacetSummary();
	}

	/**
	 * Triggers calculation of a facet summary with statistics for all faceted references in the entity schema, reflecting the current query scope except for the `userFilter` part. The `statisticsDepth` controls whether only counts or also impact predictions are computed. You can attach one `EntityFetch` and/or one `EntityGroupFetch` to control which data is loaded for facet and group entities. Use filters or ordering to limit or sort the summary, but these apply uniformly across all references. For per-reference customization, use `facetSummaryOfReference`.  
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         IMPACT,
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("e4843cfb97ec8dc4c305bcfe0fbd9ecb")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(@Nullable FacetStatisticsDepth statisticsDepth, @Nullable EntityFetchRequire... requirements) {
		return facetSummary(statisticsDepth, null, null, null, null, requirements);
	}

	/**
	 * Triggers calculation of a facet summary extra result for all faceted references, reporting counts (or impact predictions) for each facet option, grouped by reference and group. The summary reflects the current query minus the `userFilter` part, and you can filter, sort, or fetch additional data for facet entities. Use this to power faceted navigation UIs and get real-time facet statistics.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         IMPACT,
	 *         filterBy(attributeStartsWith("code", "a")),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("691f647a8a1e929bc0bbe2bc3040ffb6")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy facetFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(statisticsDepth, facetFilterBy, null, facetOrderBy, null, requirements);
	}

	/**
	 * Triggers calculation of a facet summary for all faceted references, providing statistics for each facet option and group, scoped to the current query minus the `userFilter`. Controls summary depth, filtering, ordering, and fetches for facet and group entities. Only one entity/group fetch allowed. Filters/orderings apply across all references; use `facetSummaryOfReference` for per-reference overrides.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         IMPACT,
	 *         filterBy(attributeStartsWith("code", "a")),
	 *         filterGroupBy(attributeEquals("visible", true)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("126a68507e2843586ef82ba7de155720")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(statisticsDepth, filterBy, facetGroupFilterBy, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Triggers calculation of a facet summary with the specified statistics depth, group filtering, and ordering for all faceted references, reflecting the current query scope except for the `userFilter` part. Attach entity/group fetches to control loaded data. Use filters and ordering to limit and sort the summary, but note these apply only to summary contents, not entity counts.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         IMPACT,
	 *         filterGroupBy(attributeEquals("visible", true)),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("295cdb1350ce71c1e9be2f863c2b0cb5")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(statisticsDepth, null, facetGroupFilterBy, orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * Triggers calculation of a facet summary for all faceted references, returning statistics for each facet option and group, based on the current query scope (excluding `userFilter`). Use `statisticsDepth` to control whether only counts or also impact predictions are computed. You may filter or order facets/groups, and specify which data to fetch for facet entities and their groups. For reference-specific customization, use `facetSummaryOfReference`.  
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         IMPACT,
	 *         filterBy(attributeStartsWith("code", "a")),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("0d9a13f759fff8606b1c611e563d8389")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(statisticsDepth, filterBy, null, orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * Triggers calculation of a facet summary extra result for all faceted references, providing counts (or impact predictions) for each facet option and group, based on the current query scope minus the `userFilter`. Supports filtering, ordering, and fetch customization for facets and groups. Only one entity and one group fetch constraint allowed; filters/orderings apply across all references. For reference-specific logic, use `facetSummaryOfReference`.  
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         IMPACT,
	 *         filterBy(attributeEquals("visible", true)),
	 *         filterGroupBy(attributeEquals("active", true)),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("9ba2aee6149a483441ec8ce0492c6542")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(statisticsDepth, filterBy, facetGroupFilterBy, orderBy, null, requirements);
	}

	/**
	 * Triggers calculation of facet statistics for all faceted references, returning counts (or impact predictions) for each facet/group, based on the current query scope minus `userFilter`. Supports filtering, ordering, and fetch customization for facets/groups.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         IMPACT,
	 *         filterBy(attributeStartsWith("code", "a")),
	 *         filterGroupBy(attributeEquals("visible", true)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("7791106018b551cdb85aaeb2d53d6c6f")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(statisticsDepth, filterBy, facetGroupFilterBy, null, null, requirements);
	}

	/**
	 * Triggers calculation of a facet summary extra result for all faceted references, providing counts (or impact predictions) for each facet option, grouped by reference and group. Use `statisticsDepth` to control detail level, and `orderBy`/`orderGroupBy` to sort facets and groups. Only one entity fetch and one group fetch constraint are allowed. Filters and ordering apply globally; for per-reference customization, use `facetSummaryOfReference`.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         IMPACT,
	 *         orderBy(attributeNatural("name", ASC)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("7f87a5d2970efb2d9588a1568112320a")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(statisticsDepth, null, null, orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * Triggers calculation of a facet summary extra result for all faceted references, providing per-facet counts or impact predictions (depending on `statisticsDepth`) for entities matching the current query *without* the `userFilter` part. You can filter, sort, or fetch additional data for facets and their groups using child constraints. For large datasets, use filters to limit summary size.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         IMPACT,
	 *         filterBy(attributeStartsWith("code", "a")),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("6b1f325dd1d2762c82f717b45cef5bf4")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(statisticsDepth, filterBy, null, null, null, requirements);
	}

	/**
	 * Triggers calculation of a facet summary for all faceted references, returning counts (or impact predictions) for each facet option, grouped by reference and group. The `statisticsDepth` selects between simple counts and impact estimation, while `orderBy` controls facet ordering. Attach at most one `entityFetch` and one `entityGroupFetch` to control loaded data for facet entities and groups. Use filters and ordering only for properties shared by all referenced types.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummary(IMPACT, orderBy(attributeNatural("name", ASC)), entityFetch(attributeContent("name")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("2c59b451bd0eed71e501c40bc89e8b96")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(statisticsDepth, null, null, orderBy, null, requirements);
	}

	/**
	 * Triggers calculation of a multi-level facet summary for all faceted references, reporting counts (or impact predictions) for each facet option and group, based on the current query minus its `userFilter`. Allows filtering facet groups and fetching extra data for facet/group entities.  
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         IMPACT,
	 *         filterGroupBy(attributeEquals("visible", true)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("bd6910ae7222ac1d4fbd9522f1a401f1")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(statisticsDepth, null, facetGroupFilterBy, null, null, requirements);
	}

	/**
	 * Triggers calculation of a facet summary for all faceted references, providing counts or impact predictions for each facet option and group, ordered by the specified group ordering. Only one entity/group fetch constraint each is allowed; filters and ordering apply globally.  
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         IMPACT,
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("7504353e5411c528489e95721716ad5d")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(statisticsDepth, null, null, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Triggers calculation of facet statistics for all faceted references in the entity schema, organizing results by reference, group, and facet. The `statisticsDepth` controls whether only counts or also impact predictions are computed. You can filter groups, order facets, and specify fetch requirements for returned facet and group entities. Used for building faceted navigation UIs reflecting the current query scope (excluding `userFilter`). 
	 *
	 * ```evitaql
	 * facetSummary(
	 *     IMPACT,
	 *     filterGroupBy(attributeEquals("visible", true)),
	 *     orderBy(attributeNatural("name", ASC)),
	 *     entityFetch(attributeContent("name"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("d62c6217dbd2072452433a1bda4ae5b9")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(statisticsDepth, null, facetGroupFilterBy, orderBy, null, requirements);
	}

	/**
	 * Triggers calculation of facet statistics for all faceted references in the entity schema, returning counts (or impact predictions) for each facet/group, based on the current query minus its `userFilter`. Supports filtering, ordering, and entity fetch customization for facets and groups. Use filters to limit summary size; only one fetch constraint per type allowed.  
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         IMPACT,
	 *         filterBy(attributeStartsWith("code", "a")),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("006fb294ffaa80d8e46d0bfae71369d3")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(statisticsDepth, filterBy, null, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Triggers calculation of a facet summary extra result, providing statistics for all faceted references in the schema. Computes counts (or impact predictions) for each facet option, grouped by reference and group, based on the query scope minus the `userFilter`. You can filter or order facet groups, and attach fetch requirements for group entities. For reference-specific filtering or overrides, use `facetSummaryOfReference`.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         IMPACT,
	 *         filterGroupBy(attributeEquals("visible", true)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityGroupFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("efbb0593925a1434414eb22ba6a2b86a")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(statisticsDepth, null, facetGroupFilterBy, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Triggers calculation of facet statistics for all faceted references, organizing results by reference, group, and facet, and counting entities as if the query's `userFilter` were omitted. Controls summary depth, filtering, ordering, and fetched data for facets and groups. Reference-specific overrides use `facetSummaryOfReference`.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         IMPACT,
	 *         filterBy(attributeStartsWith("code", "a")),
	 *         filterGroupBy(attributeEquals("visible", true)),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name")),
	 *         entityGroupFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("c6d6aac9cc81748e158c13edb5dce6f3")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy facetFilterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		if (statisticsDepth == null) {
			statisticsDepth = FacetStatisticsDepth.COUNTS;
		}
		if (ArrayUtils.isEmptyOrItsValuesNull(requirements)) {
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
			withoutNullValues(requirements)
		);
	}

	/**
	 * Triggers calculation of a facet summary with statistics for all faceted references, reflecting the current query scope except for `userFilter`. Attach at most one `entityFetch` and one `entityGroupFetch` to control which data is loaded for facet and group entities. For reference-specific filtering or ordering, use `facetSummaryOfReference` instead.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("0fc60f75631570ddffbb08a942220886")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(@Nullable EntityFetchRequire... requirements) {
		return facetSummary(FacetStatisticsDepth.COUNTS, null, null, null, null, requirements);
	}

	/**
	 * Triggers calculation of a facet summary with statistics for all faceted references, applying the given filter and order to which facet options appear in the summary (not to counted entities). Attach at most one entityFetch and one entityGroupFetch; using more throws an error.  
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         filterBy(attributeStartsWith("code", "a")),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("c46f12d3cb21965803c04d37ad4ff041")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FilterBy facetFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(FacetStatisticsDepth.COUNTS, facetFilterBy, null, facetOrderBy, null, requirements);
	}

	/**
	 * Calculates a facet summary for all faceted references in the entity schema, showing counts and stats for each facet option and group, based on the current query scope (excluding `userFilter`). You can filter and order which facets/groups appear, and specify what data to fetch for facet and group entities. Filters only affect the summary output, not the entity counts. For reference-specific customization, use `facetSummaryOfReference`.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         filterBy(attributeStartsWith("code", "a")),
	 *         filterGroupBy(attributeEquals("visible", true)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("2bbd2a90a66345668210e7bd5a1cd118")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(FacetStatisticsDepth.COUNTS, filterBy, facetGroupFilterBy, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Triggers calculation of a facet summary with statistics for all faceted references, grouping options by reference and group, and allowing filtering and ordering of facet groups and options. Filters only affect which facets appear, not entity counts. Attach at most one entity/group fetch constraint each.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         filterGroupBy(attributeEquals("visible", true)),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("846bec9422abf76ee3c3049e57a5eec6")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(FacetStatisticsDepth.COUNTS, null, facetGroupFilterBy, orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * Triggers calculation of facet statistics for all faceted references, organizing results by reference, group, and facet option. Counts reflect entities matching the query minus any `userFilter`. You can filter and order which facets and groups appear, and fetch extra data for facet/group entities.  
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         filterBy(attributeStartsWith("code", "a")),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("a07e7d55b78ef9aec2f0ee979c067b16")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(FacetStatisticsDepth.COUNTS, filterBy, null, orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary for all faceted references in the schema, returning per-reference, per-group, and per-facet statistics for the current query scope (excluding `userFilter`). Use optional filters and ordering to control which facet options and groups appear in the summary and their order; entity and group fetch constraints specify which data to load for facet and group entities. Filters only affect summary content, not the counted entities. For reference-specific customization, use `facetSummaryOfReference`.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         filterBy(attributeStartsWith("code", "a")),
	 *         filterGroupBy(attributeEquals("visible", true)),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("2e8a309456646fd06a88826150340b19")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(FacetStatisticsDepth.COUNTS, filterBy, facetGroupFilterBy, orderBy, null, requirements);
	}

	/**
	 * Triggers calculation of a facet summary for all faceted references, reporting counts (or impact predictions) for each selectable facet option and group, based on the current query scope minus the `userFilter`. You can filter or sort which facets and groups appear, and control which data is fetched for facet and group entities. Use reference-specific constraints for advanced scenarios.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         filterBy(attributeEquals("visible", true)),
	 *         filterGroupBy(attributeEquals("active", true)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("3e754b04e2cfc81f786ce848d19cc008")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(FacetStatisticsDepth.COUNTS, filterBy, facetGroupFilterBy, null, null, requirements);
	}

	/**
	 * Triggers calculation of facet statistics for all faceted references, organizing results by reference, group, and facet option. Allows sorting facet options and groups via `orderBy` and `orderGroupBy`, and supports entity/group fetch customization. Only one fetch constraint per type is allowed.  
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         orderBy(attributeNatural("name", ASC)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("e7f99baa04fbbd0c4851713da1ba70dc")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(FacetStatisticsDepth.COUNTS, null, null, orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * Triggers calculation of facet statistics for all faceted references, counting entities matching the current query except for the `userFilter` part. Optionally filters which facet options appear and controls which data is fetched for facet entities and groups.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         filterBy(attributeStartsWith("code", "a")),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("01aa081531cfdd1b3772c01eb45e32f8")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FilterBy filterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(FacetStatisticsDepth.COUNTS, filterBy, null, null, null, requirements);
	}

	/**
	 * Triggers calculation of facet statistics for all faceted references, organizing results by reference, group, and facet option. This variant lets you specify ordering of facet options and optionally attach fetch or filtering constraints to control which facet/group data is loaded.  
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("405d27475a2ad1ff14dd78362e062369")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(FacetStatisticsDepth.COUNTS, null, null, orderBy, null, requirements);
	}

	/**
	 * Triggers calculation of a facet summary extra result, providing statistics for all faceted references in the schema. The summary counts entities matching the query (excluding `userFilter`), is organized by references, groups, and facets, and can be filtered by group and customized with entity fetch requirements. Use this variant to filter which facet groups appear in the summary and to control loading of facet/group entity data.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         filterGroupBy(attributeEquals("visible", true)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("48cafe4c779c26a15bc5d6fa089fbb52")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(FacetStatisticsDepth.COUNTS, null, facetGroupFilterBy, null, null, requirements);
	}

	/**
	 * Triggers calculation of a facet summary with statistics for all faceted references, organizing results by reference, group, and facet. You can control the ordering of facet groups and specify fetch requirements for facet entities or groups. The summary reflects the current query scope (excluding `userFilter`).  
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("e1c53f2e0dea4092c854a895e875fab7")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(FacetStatisticsDepth.COUNTS, null, null, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Triggers calculation of a facet summary extra result, returning statistics for all faceted references in the schema. The summary reflects the current query scope (excluding `userFilter`), is organized by reference, group, and facet, and can be filtered or ordered by group and facet properties. Attach at most one `entityFetch` and one `entityGroupFetch` to control loaded data; passing more throws an exception. Use reference-specific constraints for advanced filtering or ordering.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("1b14fe191792400bda78fcfa2ff7c170")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(FacetStatisticsDepth.COUNTS, null, facetGroupFilterBy, orderBy, null, requirements);
	}

	/**
	 * Triggers calculation of facet statistics for all faceted references, grouping options by reference and group, and counting matching entities as if the `userFilter` were omitted. You can filter or order facet groups, and fetch extra data for facets or groups.  
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         filterBy(attributeStartsWith("code", "a")),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("2b3418117704be85dfbb884b5e8dd45b")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FilterBy filterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(FacetStatisticsDepth.COUNTS, filterBy, null, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Triggers calculation of a facet summary extra result for all faceted references, grouping facet options by reference and group, and including only those groups and options matching the provided group filter and order. Use to control which facet groups appear and their order in the summary.  
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         filterGroupBy(attributeEquals("visible", true)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("39f77af451211ba13dd2349dd4b3db9b")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(FacetStatisticsDepth.COUNTS, null, facetGroupFilterBy, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary with statistics for all faceted references, organizing results by reference, group, and facet option. You can filter or sort which facets and groups appear in the summary using `FilterBy`, `FilterGroupBy`, `OrderBy`, and `OrderGroupBy`. These filters only affect the summary's content, not the entity counts, and must use properties shared across all referenced types. Attach at most one `EntityFetch` and one `EntityGroupFetch` to control loaded data for facets and groups. For reference-specific customization, use `facetSummaryOfReference`.  
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         filterBy(attributeStartsWith("code", "a")),
	 *         filterGroupBy(attributeEquals("visible", true)),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name")),
	 *         entityGroupFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("358ddf2cc8fcccae6c63151901c4ea91")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FilterBy facetFilterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		if (ArrayUtils.isEmptyOrItsValuesNull(requirements)) {
			return new FacetSummary(
				FacetStatisticsDepth.COUNTS,
				facetFilterBy, facetGroupFilterBy,
				facetOrderBy, facetGroupOrderBy
			);
		}
		return new FacetSummary(
			FacetStatisticsDepth.COUNTS,
			facetFilterBy, facetGroupFilterBy,
			facetOrderBy, facetGroupOrderBy,
			withoutNullValues(requirements)
		);
	}
	/**
	 * Triggers calculation of the facet summary for a single named reference, replacing any generic `facetSummary` for that reference. All requirements (fetches, filters, ordering) must be specified explicitly, as nothing is inherited from the generic constraint. Use when you need custom facet statistics or fetches for a specific reference.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("50b8e80409b415fe0e1375dbd2e5c082")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(@Nonnull String referenceName,
		@Nullable EntityFetchRequire... requirements) {
		requireName(referenceName, "referenceName");
		return new FacetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, fully overriding any generic `facetSummary` for that reference. Specify the reference name, statistics depth (`COUNTS` or `IMPACT`), and fetch requirements for facet/group entities. All filter/order logic and fetches must be defined here, as nothing is inherited from the generic constraint.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         IMPACT,
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("62de85c336578d85663ee7d5fc31ca94")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth, @Nullable EntityFetchRequire... requirements) {
		requireName(referenceName, "referenceName");
		return statisticsDepth == null ?
			new FacetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, requirements) :
			new FacetSummaryOfReference(referenceName, statisticsDepth, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, fully overriding any generic `facetSummary` for that reference. Lets you specify statistics depth, filters, ordering, and fetch requirements tailored to the referenced entity type. No settings are inherited from the generic constraint; all requirements must be defined explicitly for the targeted reference.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         COUNTS,
	 *         filterBy(attributeEquals("origin", "EU")),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("82fdc0595cba97a54353e031d6d8fc19")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy facetFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, statisticsDepth, facetFilterBy, null, facetOrderBy, null,
			requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, fully overriding any generic `facetSummary` for that reference. Specify the reference name, statistics depth, and custom filter/order constraints targeting properties of the referenced entity type. Use this to apply per-reference logic, including entity and group fetches, which are not inherited from generic settings.
	 *
	 * ```evitaql
	 * facetSummaryOfReference(
	 *     "brand",
	 *     IMPACT,
	 *     filterBy(attributeEquals("country", "US")),
	 *     filterGroupBy(attributeInSet("type", "premium")),
	 *     orderGroupBy(attributeNatural("name", ASC)),
	 *     entityFetch(attributeContent("name"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("b821b05704b8842d8819b4d10c01c7c6")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, statisticsDepth, filterBy, facetGroupFilterBy, null,
			facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, fully overriding any generic `facetSummary` for that reference. Specify the reference name, statistics depth, group filtering, ordering, and fetch requirements. Filters and ordering apply to properties of the referenced entity only; nothing is inherited from the generic constraint.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         IMPACT,
	 *         filterGroupBy(attributeInSet("code", "premium", "budget")),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("e7e6ab07f28232a72a0319224f9265b5")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, statisticsDepth, null, facetGroupFilterBy, orderBy,
			facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, fully overriding any generic `facetSummary` for that reference. Lets you specify statistics depth, filters, and ordering using properties unique to the referenced entity. All requirements must be defined explicitly; nothing is inherited from the generic constraint.
	 *
	 * ```evitaql
	 * facetSummaryOfReference(
	 *     "brand",
	 *     IMPACT,
	 *     filterBy(attributeEquals("country", "US")),
	 *     orderBy(attributeNatural("name", ASC)),
	 *     orderGroupBy(attributeNatural("name", ASC)),
	 *     entityFetch(attributeContent("name"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("c2e07561c9aa6a6bd201f3e9b1105439")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, statisticsDepth, filterBy, null, orderBy, facetGroupOrderBy,
			requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, fully overriding any generic `facetSummary` for that reference. Lets you specify statistics depth, per-reference filters, ordering, and entity fetches—none of the generic settings are inherited or merged.
	 *
	 * ```evitaql
	 * facetSummaryOfReference(
	 *     "parameterValues",
	 *     IMPACT,
	 *     filterBy(attributeContains("code", "memory")),
	 *     filterGroupBy(attributeInSet("code", "ram-memory", "rom-memory")),
	 *     orderBy(attributeNatural("name", ASC)),
	 *     entityFetch(attributeContent("code", "name"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("e8ccaa491d57944440faef85cb35ccfe")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, statisticsDepth, filterBy, facetGroupFilterBy, orderBy, null,
			requirements);
	}

	/**
	 * Calculates the facet summary for a single named reference, fully overriding any generic `facetSummary` for that reference. Lets you specify statistics depth, filters, ordering, and entity fetches specific to the targeted reference. No settings are inherited or merged from the generic constraint; all requirements must be defined explicitly. Useful for fine-grained control over which facets and groups appear, their order, and what data is fetched for each option or group.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         COUNTS,
	 *         filterBy(attributeEquals("isActive", true)),
	 *         filterGroupBy(attributeInSet("type", "premium")),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("b7323272c19a2488605568877e61e306")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, statisticsDepth, filterBy, facetGroupFilterBy, null, null,
			requirements);
	}

	/**
	 * Calculates facet summary for a single named reference, fully overriding any generic `facetSummary` for that reference. Specify statistics depth (COUNTS or IMPACT), custom ordering for facets and groups, and fetch requirements for facet/group entities. No settings are inherited from the generic constraint; all requirements must be defined here.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         COUNTS,
	 *         orderBy(attributeNatural("name", ASC)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("9499b53fa8ba28df9c39a5f8a50595f2")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, statisticsDepth, null, null, orderBy, facetGroupOrderBy,
			requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Specify the reference name, statistics depth, filters, and fetch requirements; nothing from the generic constraint is inherited or merged.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "parameterValues",
	 *         IMPACT,
	 *         filterBy(attributeContains("code", "memory")),
	 *         entityFetch(attributeContent("code", "name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("60b6213b6ff103c3086c157b8c21a983")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, statisticsDepth, filterBy, null, null, null, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, fully overriding any generic `facetSummary` for that reference. Specify the reference name, statistics depth, ordering, and fetch requirements; filters and ordering can target any property of the referenced entity. No settings are inherited from the generic constraint—define all requirements explicitly for the targeted reference.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         COUNTS,
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("aedb66b3ddd54bad3add01ac1369292d")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, statisticsDepth, null, null, orderBy, null, requirements);
	}

	/**
	 * Calculates facet statistics for a single named reference, fully overriding any generic `facetSummary` for that reference. Specify the reference name, statistics depth, group filtering, and fetch requirements. All filtering and ordering targets properties of the referenced entity, not the relation. Use to customize facet summaries per reference; constraints are not merged with generic settings.
	 *
	 * ```evitaql
	 * facetSummaryOfReference(
	 *     "brand",
	 *     COUNTS,
	 *     filterGroupBy(attributeInSet("code", "premium", "budget")),
	 *     entityFetch(attributeContent("name"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("7fa8108674505f49d7fb666922d4b100")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, statisticsDepth, null, facetGroupFilterBy, null, null,
			requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, fully overriding any generic `facetSummary` for that reference. Specify the reference name, statistics depth, group ordering, and fetch requirements; all settings apply only to the targeted reference and are not merged with generic constraints.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("9ce08388ce44d6f4cbef900b8fa2fb8b")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, statisticsDepth, null, null, null, facetGroupOrderBy,
			requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. All filter, order, and fetch constraints apply only to the referenced entity type, not the relation. Define all requirements explicitly, as nothing is inherited from the generic constraint.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         IMPACT,
	 *         filterGroupBy(attributeInSet("popularity", "high")),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("275f1cfe3fc6278196e267a82bc0d7b8")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, statisticsDepth, null, facetGroupFilterBy, orderBy, null,
			requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Specify the reference name, statistics depth, and optional filters, group ordering, and fetch requirements. All settings are isolated—nothing is inherited from the generic summary.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         COUNTS,
	 *         filterBy(attributeEquals("origin", "EU")),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("e678b3aef6c11b268953311c29055fa1")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, statisticsDepth, filterBy, null, null, facetGroupOrderBy,
			requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, fully overriding any generic `facetSummary` for that reference. Specify the reference name, statistics depth (COUNTS or IMPACT), group filtering and ordering, and fetch requirements for facet/group entities. All filter/order constraints apply only to properties of the referenced entity, not the relation itself. Nothing from the generic `facetSummary` is inherited for this reference.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         IMPACT,
	 *         filterGroupBy(attributeInSet("code", "premium", "budget")),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("5cc11eaba481ec6f231bfdacf0ea59f5")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, statisticsDepth, null, facetGroupFilterBy, null,
			facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, fully overriding any generic `facetSummary` for that reference. Lets you specify statistics depth, filters, ordering, and fetch requirements unique to the referenced entity type. Constraints are not merged—define all requirements explicitly for the targeted reference.
	 *
	 * ```evitaql
	 * facetSummaryOfReference(
	 *     "parameterValues",
	 *     IMPACT,
	 *     filterBy(attributeContains("code", "memory")),
	 *     filterGroupBy(attributeInSet("code", "ram-memory", "rom-memory")),
	 *     orderBy(attributeNatural("name", ASC)),
	 *     orderGroupBy(attributeNatural("name", ASC)),
	 *     entityFetch(attributeContent("code", "name")),
	 *     entityGroupFetch(attributeContent("code"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("51e9448764580ef9515a9fdd9fecee4b")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy facetFilterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		if (statisticsDepth == null) {
			statisticsDepth = FacetStatisticsDepth.COUNTS;
		}
		if (ArrayUtils.isEmptyOrItsValuesNull(requirements)) {
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
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Filters and ordering can target any property of the referenced entity. Inherits nothing from the generic constraint—define all requirements explicitly.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         filterBy(attributeEquals("active", true)),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("d062211a817eac1c8276f9d7f50559ce")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FilterBy facetFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, facetFilterBy, null, facetOrderBy,
			null, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, fully overriding any generic `facetSummary` for that reference. Filters and ordering can target any property of the referenced entity, and you must specify all requirements explicitly. Use when you need per-reference control over which facets and groups appear, their sort order, and which data is fetched for facet and group entities.
	 *
	 * ```evitaql
	 * facetSummaryOfReference(
	 *     "brand",
	 *     filterBy(attributeEquals("origin", "EU")),
	 *     filterGroupBy(attributeInSet("category", "premium")),
	 *     orderGroupBy(attributeNatural("name", ASC)),
	 *     entityFetch(attributeContent("name"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("608e73b1263fdbfd5ce2bc4ece67d1f9")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, filterBy, facetGroupFilterBy, null,
			facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. All filters, ordering, and entity fetches apply only to the targeted reference and must be specified explicitly, as nothing is inherited from the generic constraint.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         filterGroupBy(attributeInSet("type", "premium")),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("f6dce78b9f36e679d7034e8605dd30b8")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, null, facetGroupFilterBy, orderBy,
			facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates the facet summary for a single named reference, fully overriding any generic `facetSummary` settings for that reference. Filters and ordering can target any property of the referenced entity, and all requirements must be specified explicitly for this reference.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         filterBy(attributeEquals("country", "US")),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("3ce1b5ad8232bbc03944031901d5b4c6")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, filterBy, null, orderBy,
			facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, completely overriding any generic `facetSummary` for that reference. Specify the reference name and optionally filter, group filter, and order constraints targeting properties of the referenced entity. Only one `entityFetch` or `entityGroupFetch` can be attached. Use when you need per-reference facet statistics or custom filtering/sorting not possible with the generic constraint.
	 *
	 * ```evitaql
	 * facetSummaryOfReference(
	 *     "brand",
	 *     filterBy(attributeEquals("country", "US")),
	 *     filterGroupBy(attributeInSet("type", "premium")),
	 *     orderBy(attributeNatural("name", ASC)),
	 *     entityFetch(attributeContent("name"))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("a706457701a5f92d96a82866a2f27064")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, filterBy, facetGroupFilterBy,
			orderBy, null, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Filters and fetches apply only to the referenced entity, letting you target its specific properties. Other references are unaffected unless explicitly named.
	 *
	 * ```evitaql
	 * facetSummaryOfReference(
	 *     "brand",
	 *     filterBy(attributeEquals("code", "apple")),
	 *     filterGroupBy(attributeInSet("code", "premium")),
	 *     entityFetch(attributeContent("name"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("f8f484481be449f95bedfa84bf00a48f")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, filterBy, facetGroupFilterBy, null,
			null, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. All filter and order constraints apply only to properties of the referenced entity, not the reference relation. No settings are inherited from the generic constraint—define all requirements explicitly.
	 *
	 * ```evitaql
	 * facetSummaryOfReference(
	 *     "brand",
	 *     orderBy(attributeNatural("name", ASC)),
	 *     orderGroupBy(attributeNatural("name", ASC)),
	 *     entityFetch(attributeContent("name"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("9aa36fd0c61f5df3f3ae5e8b8833b7d8")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, null, null, orderBy,
			facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. All filtering, ordering, and entity fetches apply only to the specified reference's facet or group entities; nothing is inherited from generic settings.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         filterBy(attributeEquals("code", "amazon")),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("ef9cf5d137001123cd5c3287217ee0a2")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, filterBy, null, null, null,
			requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, fully overriding any generic `facetSummary` for that reference. Allows custom ordering and fetch requirements specific to the referenced entity type. Only properties of the referenced entity can be used for ordering or fetching; nothing is inherited from the generic constraint.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("ca40fc3deea5fc3da0542e66fb4d1fef")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, null, null, orderBy, null,
			requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Filters and fetches apply only to the referenced entity type, allowing fine-grained control over which facet groups and options are included and what data is loaded. No settings are inherited from the generic constraint.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         filterGroupBy(attributeInSet("code", "premium", "budget")),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("907beab72b81a7f6dfbf322cb47798a7")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, null, facetGroupFilterBy, null, null,
			requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. All filters, ordering, and fetches must be specified explicitly, as nothing is inherited from the generic constraint. Use this to apply custom sorting of facet groups and fetch requirements for a specific reference.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("b808b8cd2d28b26c43f889fd4aab158f")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, null, null, null, facetGroupOrderBy,
			requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Filters and ordering can target any property of the referenced entity. All requirements must be specified explicitly; nothing is inherited from the generic constraint.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         filterGroupBy(attributeInSet("code", "premium")),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("f782d613c26e3e04a28ac22c8d3011a1")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, null, facetGroupFilterBy, orderBy,
			null, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. All filters, ordering, and fetches apply only to the targeted reference's entities or groups; nothing is inherited from the generic constraint. Use this to fully control which facets and groups appear, their order, and what data is fetched for a specific reference.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         filterBy(attributeInSet("code", "sony", "kindle")),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("ba3e01798257b7ffc401c9d925dfef96")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, filterBy, null, null,
			facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, fully overriding any generic `facetSummary` for that reference. Filters and ordering can target any property of the referenced entity, and entity/group fetches are defined per reference. No settings are inherited from the generic constraint.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("6d552923dab1e32289da904da1f96ccd")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, null, facetGroupFilterBy, null,
			facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Specify filters and ordering on the referenced entity, and attach custom fetch requirements. No settings are inherited from the generic constraint.
	 *
	 * ```evitaql
	 * facetSummaryOfReference(
	 *     "parameterValues",
	 *     filterBy(attributeContains("code", "memory")),
	 *     filterGroupBy(attributeInSet("code", "ram-memory", "rom-memory")),
	 *     orderBy(attributeNatural("name", ASC)),
	 *     orderGroupBy(attributeNatural("name", ASC)),
	 *     entityFetch(attributeContent("code", "name")),
	 *     entityGroupFetch(attributeContent("code"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("07805e517b66a4414a1ee89f69852ac8")
	@Deprecated(since = "2026.2", forRemoval = true)
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FilterBy facetFilterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		if (ArrayUtils.isEmptyOrItsValuesNull(requirements)) {
			return new FacetSummaryOfReference(
				referenceName, FacetStatisticsDepth.COUNTS,
				facetFilterBy, facetGroupFilterBy,
				facetOrderBy, facetGroupOrderBy
			);
		}
		return new FacetSummaryOfReference(
			referenceName,
			FacetStatisticsDepth.COUNTS,
			facetFilterBy, facetGroupFilterBy,
			facetOrderBy, facetGroupOrderBy,
			requirements
		);
	}

	/**
	 * Calculates a summary of all faceted references in the result set, providing counts (and optionally impact predictions) for each selectable reference option and group, based on the current query scope excluding the `userFilter`. Use for building faceted navigation UIs.  
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("e5ed9affc6cdd8fcd049aec786e0f275")
	@Nonnull
	static ReferenceSummary referenceSummary() {
		return new ReferenceSummary();
	}

	/**
	 * Triggers calculation of a reference summary extra result, providing statistics for all faceted references in the entity schema. Counts are computed for entities matching the query, omitting the `userFilter` part, with configurable detail level (`COUNTS` or `IMPACT`). Supports filtering, sorting, and fetching of referenced entities and groups; only one fetch constraint per type is allowed. For reference-specific customization, use `referenceSummaryOfReference`.  
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         IMPACT,
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("1fe031c58431ddfb6cd77490a026b8ab")
	@Nonnull
	static ReferenceSummary referenceSummary(@Nullable FacetStatisticsDepth statisticsDepth, @Nullable EntityFetchRequire... requirements) {
		return referenceSummary(statisticsDepth, null, null, null, null, requirements);
	}

	/**
	 * Triggers calculation of a faceted reference summary, returning counts (or impact predictions) for all faceted references in the result set, excluding userFilter constraints. Supports filtering, ordering, and fetch customization for reference options and groups.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         IMPACT,
	 *         filterBy(attributeEquals("visible", true)),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("02ac76391ab5e009f21ab08a6616179a")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy facetFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(statisticsDepth, facetFilterBy, null, facetOrderBy, null, requirements);
	}

	/**
	 * Triggers calculation of a faceted reference summary, returning counts (or impact predictions) for each faceted reference and group, filtered and ordered as specified. Filters only affect which options appear, not entity counts. Attach at most one entity/group fetch constraint each; using more throws. For large datasets, limit summary with filters for performance.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         IMPACT,
	 *         filterBy(attributeStartsWith("code", "a")),
	 *         filterGroupBy(attributeEquals("visible", true)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("e0b635b90f7f1eb8b392a3e665ac675d")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(statisticsDepth, filterBy, facetGroupFilterBy, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Triggers calculation of the reference summary extra result, providing statistics for all faceted references in the entity schema. Computes counts or impact predictions for reference options, supports filtering and ordering of options/groups, and allows fetching reference/group entity data. Useful for building faceted navigation UIs; filters/orderings apply only to summary content, not entity counts.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         IMPACT,
	 *         filterGroupBy(attributeEquals("visible", true)),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("a67145bbb599981046feaac4251967e1")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(statisticsDepth, null, facetGroupFilterBy, orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * Triggers calculation of a summary of all faceted references, providing counts (or impact predictions) for each selectable option and group, based on the current query scope minus any `userFilter`. Supports filtering, ordering, and fetch customization for reference and group entities. Use to power faceted navigation UIs with up-to-date option statistics.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         IMPACT,
	 *         filterBy(attributeEquals("visible", true)),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("0bd7f049fe75386bfb686e5d7a69cb38")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(statisticsDepth, filterBy, null, orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * Triggers calculation of a summary of all faceted references, providing counts (or impact predictions) for each selectable option and group, based on the current query minus any `userFilter`. Supports filtering and ordering of reference options/groups, and allows fetching reference/group entity data. Only one entity and one group fetch constraint are allowed; filters/orderings apply globally across all references. Use `ReferenceSummaryOfReference` for per-reference overrides.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         IMPACT,
	 *         filterBy(attributeStartsWith("code", "a")),
	 *         filterGroupBy(attributeEquals("visible", true)),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("f45b2881f9333d273f10da32aa293886")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(statisticsDepth, filterBy, facetGroupFilterBy, orderBy, null, requirements);
	}

	/**
	 * Triggers calculation of a summary of all faceted references, grouping options and groups with counts (or impact predictions) for each, based on the current query scope except for `userFilter`. Supports optional filtering, ordering, and fetch requirements for references and groups. Use to power faceted navigation UIs with reference statistics.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         IMPACT,
	 *         filterBy(attributeStartsWith("code", "a")),
	 *         filterGroupBy(attributeEquals("visible", true)),
	 *         entityFetch(attributeContent("name")),
	 *         entityGroupFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("539a23f6b25117828ad0e64608f14106")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(statisticsDepth, filterBy, facetGroupFilterBy, null, null, requirements);
	}

	/**
	 * Triggers calculation of reference statistics for all faceted references, organizing results by reference, group, and option. Controls depth (counts or impact), ordering, and fetches for referenced entities/groups. Filters and ordering apply across all references; use `referenceSummaryOfReference` for per-reference customizations.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         IMPACT,
	 *         orderBy(attributeNatural("name", ASC)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("12b2565b603ce7aa701f645c1289f6fb")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(statisticsDepth, null, null, orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a summary of all faceted references in the result set, providing counts (or impact predictions) for each selectable option and group, based on the current query minus the `userFilter`. You can filter, sort, and control fetched data for reference options and groups. Use this to power faceted navigation UIs with real-time option counts or impact estimates.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         IMPACT,
	 *         filterBy(attributeStartsWith("code", "a")),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("f98b53dc17cdc8a20e8c0de18256101f")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(statisticsDepth, filterBy, null, null, null, requirements);
	}

	/**
	 * Calculates a summary of all faceted references in the result set, providing counts (or impact predictions) for each selectable option, grouped by reference and group, based on the query scope excluding the `userFilter`. Supports ordering of reference options and attaching fetch requirements for referenced entities or groups.  
	 * ```evitaql
	 * require(
	 *     referenceSummary(IMPACT, orderBy(attributeNatural("name", ASC)), entityFetch(attributeContent("name")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("6b15aa6baec9c42dc1f0c1b3ba4b58be")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(statisticsDepth, null, null, orderBy, null, requirements);
	}

	/**
	 * Triggers calculation of a reference summary extra result for all faceted references, providing reference/group/option statistics for the current query scope (excluding `userFilter`). You can control statistics depth (`COUNTS` or `IMPACT`), filter groups, and fetch reference/group entity data. Use filters and ordering to limit or sort summary options, but these apply only to the summary, not to counted entities. For reference-specific customization, use `referenceSummaryOfReference`.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         IMPACT,
	 *         filterGroupBy(attributeEquals("visible", true)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("c74ab6eda5d2965b98c6c92fba3ed7e9")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(statisticsDepth, null, facetGroupFilterBy, null, null, requirements);
	}

	/**
	 * Triggers calculation of reference statistics for all faceted references, providing counts or impact predictions for each selectable option and group, based on the current query scope (excluding `userFilter`). Supports ordering of groups and attaching fetch/filter constraints for reference and group entities. For details on advanced filtering, ordering, and performance considerations, see the documentation.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("277665ca08d346e2cba7cf556e6c4c6c")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(statisticsDepth, null, null, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Triggers calculation of the reference summary extra result, providing statistics for all faceted references in the entity schema. Computes counts (or impact predictions) for each reference option, grouped and ordered as specified, and allows filtering of which options and groups appear in the summary. Entity and group fetch requirements can be attached. The summary reflects the query scope minus any `userFilter` constraints.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         IMPACT,
	 *         filterGroupBy(attributeEquals("visible", true)),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("c9b246ba916518afe97da5a5a210aee8")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(statisticsDepth, null, facetGroupFilterBy, orderBy, null, requirements);
	}

	/**
	 * Triggers calculation of a summary of all faceted references in the result set, providing counts (or impact predictions) for each option and group, based on the current query minus any `userFilter`. Supports filtering, ordering, and fetch customization for reference and group entities. Use filters/orderings only for properties shared by all referenced types.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         IMPACT,
	 *         filterBy(attributeStartsWith("code", "a")),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("1011252dfe874b19f159da287f64676b")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(statisticsDepth, filterBy, null, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Triggers calculation of a reference summary with the specified statistics depth (`COUNTS` or `IMPACT`), applying group-level filtering and ordering to faceted references. Attach entity/group fetches to control loaded data. Filters/orderings only affect summary display, not entity counts.  
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         IMPACT,
	 *         filterGroupBy(attributeEquals("visible", true)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("cb35a420d683e17370f9a8121acefbf2")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(statisticsDepth, null, facetGroupFilterBy, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Triggers calculation of a reference summary with customizable statistics depth, filtering, and ordering for all faceted references in the entity schema. Counts reflect the current query scope without the `userFilter` part. Supports fine-grained control over which reference options and groups are included and how they are sorted, as well as what data is fetched for referenced entities and groups. Use filters and ordering only for properties shared by all referenced types. For reference-specific needs, use `referenceSummaryOfReference`.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         IMPACT,
	 *         filterBy(attributeStartsWith("code", "a")),
	 *         filterGroupBy(attributeEquals("visible", true)),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name")),
	 *         entityGroupFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("a3e78c2be3136ed62871f2f778c6323d")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy facetFilterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		if (statisticsDepth == null) {
			statisticsDepth = FacetStatisticsDepth.COUNTS;
		}
		if (ArrayUtils.isEmptyOrItsValuesNull(requirements)) {
			return new ReferenceSummary(
				statisticsDepth,
				facetFilterBy, facetGroupFilterBy,
				facetOrderBy, facetGroupOrderBy
			);
		}
		return new ReferenceSummary(
			statisticsDepth,
			facetFilterBy, facetGroupFilterBy,
			facetOrderBy, facetGroupOrderBy,
			requirements
		);
	}

	/**
	 * Triggers calculation of a reference summary for all faceted references, providing counts (and optionally impact predictions) of matching entities per reference option and group, based on the current query scope minus any `userFilter`. Attach at most one `entityFetch` and one `entityGroupFetch` to control which data is loaded for referenced entities and their groups. Use filters and ordering to limit or sort the summary, but these apply uniformly across all references.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("460f337350c5a4df6b6428ad4ae94d5e")
	@Nonnull
	static ReferenceSummary referenceSummary(@Nullable EntityFetchRequire... requirements) {
		return referenceSummary(FacetStatisticsDepth.COUNTS, null, null, null, null, requirements);
	}

	/**
	 * Calculates a summary of all faceted references in the result set, showing counts (or impact predictions) for each reference option and group, based on the current query scope minus any `userFilter`. Use filters and ordering to limit or sort the summary; attach entity fetch requirements for reference and group details.  
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         filterBy(attributeStartsWith("code", "a")),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("0eb2a3fa7772b9e11b1782ade6f13c59")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FilterBy facetFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(FacetStatisticsDepth.COUNTS, facetFilterBy, null, facetOrderBy, null, requirements);
	}

	/**
	 * Triggers calculation of a summary of all faceted references, showing counts (or impact predictions) for each option and group, filtered and ordered by the provided constraints. Filters only affect which options appear, not entity counts; fetches control loaded data.  
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         filterBy(attributeEquals("visible", true)),
	 *         filterGroupBy(attributeEquals("active", true)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("2c3d37b8c59a42f7dc4d827c6be6e19d")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(FacetStatisticsDepth.COUNTS, filterBy, facetGroupFilterBy, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Triggers calculation of a faceted reference summary, providing counts (and optionally impact predictions) for each reference option and group, based on the current query minus the `userFilter`. You can filter and order reference options/groups, and specify which data to fetch for referenced entities and their groups. Use when you need aggregated stats for faceted navigation across all faceted references.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         filterGroupBy(attributeEquals("visible", true)),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("edd3c06c83b5c4d4c009f16dac0f27a4")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(FacetStatisticsDepth.COUNTS, null, facetGroupFilterBy, orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * Triggers calculation of a reference summary containing statistics for all faceted references, using the current query scope (excluding `userFilter`). Allows filtering and ordering of reference options and groups, and specifying fetch requirements for referenced entities. Use to power faceted navigation UIs with counts or impact predictions for selectable options.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         filterBy(attributeEquals("visible", true)),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("2261406abe77d8aac1cfe9666598b4b6")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(FacetStatisticsDepth.COUNTS, filterBy, null, orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * Triggers calculation of a reference summary with statistics for all faceted references, allowing you to filter and order both reference options and groups in the summary. Filters only affect which options appear, not entity counts. Attach at most one entityFetch and one entityGroupFetch. For reference-specific filtering, use `referenceSummaryOfReference` instead.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         filterBy(attributeStartsWith("code", "a")),
	 *         filterGroupBy(attributeEquals("visible", true)),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("170a800df7111c17b8f2ec5f1bdaea66")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(FacetStatisticsDepth.COUNTS, filterBy, facetGroupFilterBy, orderBy, null, requirements);
	}

	/**
	 * Triggers calculation of the reference summary extra result, providing statistics for all faceted references in the entity schema. Counts are based on the current query minus any `userFilter`, and you can filter or fetch reference/group data using child constraints.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         filterBy(attributeStartsWith("code", "a")),
	 *         filterGroupBy(attributeEquals("visible", true)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("175eb585e8ed299fc58ab6fd523acab7")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(FacetStatisticsDepth.COUNTS, filterBy, facetGroupFilterBy, null, null, requirements);
	}

	/**
	 * Triggers calculation of a reference summary extra result, providing statistics for all faceted references in the entity schema. Allows sorting of both reference options and their groups, and can include fetch requirements for referenced entities or groups. Counts reflect the current query scope minus any userFilter, and summary can be filtered or customized further. Only one entityFetch and one entityGroupFetch may be attached.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("6236ff4e74e6a41be41a3b7dd96af84d")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(FacetStatisticsDepth.COUNTS, null, null, orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * Triggers calculation of a summary with counts for all faceted references, grouping and filtering the options according to the provided `filterBy` and fetch requirements. The summary reflects the current query scope, omitting only the `userFilter` part, and is useful for building faceted navigation UIs.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         filterBy(attributeStartsWith("code", "a")),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("58a1847d749947a437630bea52b3f60b")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FilterBy filterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(FacetStatisticsDepth.COUNTS, filterBy, null, null, null, requirements);
	}

	/**
	 * Triggers calculation of a summary of all faceted references, providing counts (and optionally impact predictions) for each reference option and group, based on the current query scope minus the `userFilter`. You can specify ordering for reference options and fetch requirements for referenced entities or groups.  
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("c9effcd336077ff4edd12936f929d504")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(FacetStatisticsDepth.COUNTS, null, null, orderBy, null, requirements);
	}

	/**
	 * Triggers calculation of a summary of all faceted references, grouping options by reference and group, and counting matching entities as if the `userFilter` were omitted. You can filter which groups appear and control fetched data for referenced entities and groups.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         filterGroupBy(attributeEquals("visible", true)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("c9612953ffe77f903367465007fa414e")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(FacetStatisticsDepth.COUNTS, null, facetGroupFilterBy, null, null, requirements);
	}

	/**
	 * Triggers calculation of a summary of all faceted references in the result set, returning counts (and optionally impact predictions) for each reference option and group, based on the current query scope minus any `userFilter`. Allows ordering of reference groups and fine-grained control over which reference/group data is fetched. Use filters and ordering constraints to limit or sort the summary; for reference-specific needs, use `referenceSummaryOfReference`.  
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("e4e6297a97de77f34cd40e4d134bde68")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(FacetStatisticsDepth.COUNTS, null, null, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Triggers calculation of a summary with statistics for all faceted references in the entity schema, organized by reference and group, reflecting the current query scope except for `userFilter`. Allows filtering and ordering of reference options and groups, and supports fetching extra data for referenced entities or groups. Use to power faceted navigation UIs with counts and optionally impact predictions.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         filterGroupBy(attributeEquals("visible", true)),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("cc6aa3a29f85248a80a8eb1a99d87550")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(FacetStatisticsDepth.COUNTS, null, facetGroupFilterBy, orderBy, null, requirements);
	}

	/**
	 * Triggers calculation of a reference summary for all faceted references, providing counts (and optionally impact predictions) for each reference option and group, based on the current query minus any `userFilter`. You can filter or order the summary by reference/group attributes and control which data is fetched for referenced entities and groups. Use filters to limit summary size, but note they only affect summary contents, not entity counts.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         filterBy(attributeStartsWith("code", "a")),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("801014d87de048f1e67dfe07f5df2dd2")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FilterBy filterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(FacetStatisticsDepth.COUNTS, filterBy, null, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Produces a reference summary extra result for all faceted references, grouping and ordering reference groups according to the provided filters and sorters. Entity fetch requirements can be attached for reference and group entities. Only one fetch constraint of each type may be included. Filtering and ordering apply to which reference options and groups are shown, but do not affect entity counts. For reference-specific filtering or overrides, use `referenceSummaryOfReference`.  
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("d2883f0687d867b1129385cde9ab862b")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceSummary(FacetStatisticsDepth.COUNTS, null, facetGroupFilterBy, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Triggers calculation of the reference summary extra result, providing counts and optional impact predictions for all faceted references in the current query scope (excluding `userFilter`). Supports filtering, ordering, and fetch customization for reference options and groups. Use filters to limit summary size; for reference-specific logic, prefer `referenceSummaryOfReference`.  
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         filterBy(attributeEquals("visible", true)),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("6c3ed7b091a1c18458ddaa8967eaaffa")
	@Nonnull
	static ReferenceSummary referenceSummary(
		@Nullable FilterBy facetFilterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		if (ArrayUtils.isEmptyOrItsValuesNull(requirements)) {
			return new ReferenceSummary(
				FacetStatisticsDepth.COUNTS,
				facetFilterBy, facetGroupFilterBy,
				facetOrderBy, facetGroupOrderBy
			);
		}
		return new ReferenceSummary(
			FacetStatisticsDepth.COUNTS,
			facetFilterBy, facetGroupFilterBy,
			facetOrderBy, facetGroupOrderBy,
			requirements
		);
	}

	/**
	 * Triggers calculation of reference summary statistics for all faceted references, including entity and group fetches, with the specified statistics depth (`COUNTS` or `IMPACT`). Also computes histograms for reference options if histogram statistics are provided.  
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("cf3164b2a7d83f54e7710d499789cfac")
	@Nonnull
	static ReferenceSummary referenceSummaryWithHistograms(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable EntityFetch entityFetch,
		@Nullable EntityGroupFetch entityGroupFetch,
		@Nullable ReferenceHistogramStatistics... histogramStatistics
	) {
		final RequireConstraint[] children = mergeReferenceSummaryChildren(entityFetch, entityGroupFetch, histogramStatistics);
		return new ReferenceSummary(
			statisticsDepth == null ? FacetStatisticsDepth.COUNTS : statisticsDepth,
			children
		);
	}

	/**
	 * Triggers calculation of a faceted reference summary, returning counts (or impact predictions) for each reference option and group, filtered, ordered, and enriched per arguments. Optionally includes histogram statistics for reference options. Use to power faceted navigation UIs with advanced filtering and analytics.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummary(
	 *         IMPACT,
	 *         filterBy(attributeEquals("type", "premium")),
	 *         entityFetch(attributeContent("name")),
	 *         histogramStatistics(10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummary
	 */
	@SourceHash("02e66a66d948c1666fb255f579c10f2b")
	@Nonnull
	static ReferenceSummary referenceSummaryWithHistograms(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy facetFilterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetch entityFetch,
		@Nullable EntityGroupFetch entityGroupFetch,
		@Nullable ReferenceHistogramStatistics... histogramStatistics
	) {
		final RequireConstraint[] children = mergeReferenceSummaryChildren(entityFetch, entityGroupFetch, histogramStatistics);
		return new ReferenceSummary(
			statisticsDepth == null ? FacetStatisticsDepth.COUNTS : statisticsDepth,
			facetFilterBy, facetGroupFilterBy,
			facetOrderBy, facetGroupOrderBy,
			children
		);
	}

	/**
	 * Merges the entity fetch requirements and histogram statistics constraints into a single array of children
	 * for the `ReferenceSummary` / `ReferenceSummaryOfReference` constraints. Null elements are skipped.
	 */
	@Nonnull
	private static RequireConstraint[] mergeReferenceSummaryChildren(
		@Nullable EntityFetch entityFetch,
		@Nullable EntityGroupFetch entityGroupFetch,
		@Nullable ReferenceHistogramStatistics... histogramStatistics
	) {
		final int fixedCount = (entityFetch == null ? 0 : 1) + (entityGroupFetch == null ? 0 : 1);
		final int histogramCount = histogramStatistics == null
			? 0
			: (int) Arrays.stream(histogramStatistics).filter(Objects::nonNull).count();
		final RequireConstraint[] children = new RequireConstraint[fixedCount + histogramCount];
		int index = 0;
		if (entityFetch != null) {
			children[index++] = entityFetch;
		}
		if (entityGroupFetch != null) {
			children[index++] = entityGroupFetch;
		}
		if (histogramStatistics != null) {
			for (ReferenceHistogramStatistics stats : histogramStatistics) {
				if (stats != null) {
					children[index++] = stats;
				}
			}
		}
		return children;
	}

	/**
	 * Calculates a reference summary for a single named reference, overriding any generic `referenceSummary` for that reference. All filter, order, and fetch requirements apply only to the targeted reference and must be fully specified, as nothing is inherited or merged from the generic constraint.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("44a7a7dce5463e1ae0ee74e1ee4f67d9")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(@Nonnull String referenceName,
		@Nullable EntityFetchRequire... requirements) {
		requireName(referenceName, "referenceName");
		return new ReferenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, requirements);
	}

	/**
	 * Calculates a reference summary for a single named reference, fully overriding any generic `referenceSummary` for that reference. Specify the reference name, statistics depth (`COUNTS` or `IMPACT`), and any fetch/filter/order constraints for the referenced entity or its group. Constraints are not merged: all requirements for this reference must be defined here. Use when you need per-reference control over statistics, filtering, or data fetching.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         IMPACT,
	 *         filterBy(attributeEquals("priority", "premium")),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("3049a97819704990c90a0428dd875ee2")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth, @Nullable EntityFetchRequire... requirements) {
		requireName(referenceName, "referenceName");
		return statisticsDepth == null ?
			new ReferenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, requirements) :
			new ReferenceSummaryOfReference(referenceName, statisticsDepth, requirements);
	}

	/**
	 * Calculates a reference summary for a single named reference, overriding any generic `referenceSummary` for that reference. Specify the reference name, statistics depth (COUNTS or IMPACT), and optional filter/order constraints targeting properties of the referenced entity. All requirements for this reference must be defined here; nothing is inherited or merged from the generic constraint.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         COUNTS,
	 *         filterBy(attributeEquals("code", "apple")),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("35d14a6006e2f03b5a930e583cf9c859")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy facetFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, statisticsDepth, facetFilterBy, null, facetOrderBy, null,
			requirements);
	}

	/**
	 * Calculates a reference summary for a single named reference, overriding any generic `referenceSummary` for that reference. Specify statistics depth, filters, ordering, and fetches unique to the targeted reference; settings are not merged with the generic constraint.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "parameterValues",
	 *         IMPACT,
	 *         filterBy(attributeContains("code", "memory")),
	 *         filterGroupBy(attributeInSet("code", "ram-memory", "rom-memory")),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("code", "name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("892a13e3baff2966f9ef30fa763a5ef8")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, statisticsDepth, filterBy, facetGroupFilterBy, null,
			facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `referenceSummary` for that reference. Specify the reference name, statistics depth (`COUNTS` or `IMPACT`), and fine-grained filters, ordering, and entity fetches for reference and group entities. Constraints are never merged with the generic summary—define all requirements explicitly here. Use when you need per-reference filtering, sorting, or fetch behavior not shared with other references.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         IMPACT,
	 *         filterGroupBy(attributeEquals("code", "premium")),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("code", "name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("9d981632b6ce7fb6bec21a6d84b87fc7")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, statisticsDepth, null, facetGroupFilterBy, orderBy,
			facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `referenceSummary` for that reference. Specify the reference name, statistics depth, filters, and ordering—none of the generic settings are inherited. All filter/order constraints target properties of the referenced entity, not the relation. Attach at most one `EntityFetch` and one `EntityGroupFetch`. Use when you need per-reference customization of statistics, filtering, or fetched data.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "parameterValues",
	 *         IMPACT,
	 *         filterBy(attributeContains("code", "memory")),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("code", "name")),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("6c9c3e3b981d77f0200e22708f3fa606")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, statisticsDepth, filterBy, null, orderBy, facetGroupOrderBy,
			requirements);
	}

	/**
	 * Calculates a reference summary for a single named reference, overriding any generic `referenceSummary` constraints for that reference. All filtering, ordering, and entity fetch requirements must be specified explicitly, as nothing is inherited or merged from the generic constraint. Use this when you need per-reference statistics depth, filters, or fetches that differ from the baseline. Filters and ordering target properties of the referenced entity only.
	 *
	 * ```evitaql
	 * referenceSummaryOfReference(
	 *     "parameterValues",
	 *     IMPACT,
	 *     filterBy(attributeContains("code", "memory")),
	 *     orderBy(attributeNatural("name", ASC)),
	 *     entityFetch(attributeContent("code", "name"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("75bd52d94c40c95d0468b1c81ae9b35c")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, statisticsDepth, filterBy, facetGroupFilterBy, orderBy, null,
			requirements);
	}

	/**
	 * Calculates a reference summary for a single named reference, fully overriding any generic `referenceSummary` constraints for that reference. Lets you specify statistics depth, filters, ordering, and fetch requirements tailored to the targeted reference. Filters and ordering apply to properties of the referenced entity only. Use when you need per-reference control over summary calculation; constraints are never merged with the generic baseline.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         IMPACT,
	 *         filterBy(attributeEquals("origin", "EU")),
	 *         filterGroupBy(attributeInSet("popularity", "high")),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("b96578ce67c0707d19c6dd0f540bf19c")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, statisticsDepth, filterBy, facetGroupFilterBy, null, null,
			requirements);
	}

	/**
	 * Calculates a reference summary for a single named reference, fully overriding any generic `referenceSummary` for that reference. Specify statistics depth, custom ordering, and entity fetches; filters and ordering can use any property of the referenced entity. Nothing from the generic summary is inherited—define all requirements explicitly for this reference.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         IMPACT,
	 *         orderBy(attributeNatural("name", ASC)),
	 *         orderGroupBy(attributeNatural("priority", DESC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("89c6b4b6740d51617d6368ae895506a4")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, statisticsDepth, null, null, orderBy, facetGroupOrderBy,
			requirements);
	}

	/**
	 * Calculates a reference summary for a single named reference, fully overriding any generic `referenceSummary` constraints for that reference. Lets you specify statistics depth, filters, ordering, and entity fetches unique to the targeted reference; nothing is inherited from the generic constraint. Use when you need per-reference control over summary computation, filtering, and data loading.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "parameterValues",
	 *         IMPACT,
	 *         filterBy(attributeContains("code", "memory")),
	 *         entityFetch(attributeContent("code", "name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("0db8ebe1f25216ed4a20a9a09b950320")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, statisticsDepth, filterBy, null, null, null, requirements);
	}

	/**
	 * Calculates a reference summary for a single named reference, fully overriding any generic `referenceSummary` for that reference. Specify the reference name, statistics depth (`COUNTS` or `IMPACT`), custom ordering, and entity fetch requirements. Filters and ordering can target any property of the referenced entity, and nothing is inherited from the generic summary. Use when you need per-reference control over statistics, filtering, ordering, or fetched data.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         IMPACT,
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("code", "name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("cfea356ceaa4b2d9adf6f499b91f2ea4")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, statisticsDepth, null, null, orderBy, null, requirements);
	}

	/**
	 * Calculates a reference summary for a single named reference, fully overriding any generic `referenceSummary` constraints for that reference. Specify the reference name, statistics depth (`COUNTS` or `IMPACT`), group filtering, and fetch requirements. All filters and ordering apply only to properties of the referenced entity or group, not the reference relation. Use when you need per-reference control over summary calculation, filters, sorting, or fetched data—nothing is inherited from the generic summary.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         COUNTS,
	 *         filterGroupBy(attributeInSet("code", "premium", "budget")),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("5b818ba92548a7f1da2ff2041894cfeb")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, statisticsDepth, null, facetGroupFilterBy, null, null,
			requirements);
	}

	/**
	 * Calculates a reference summary for a single named reference, fully overriding any generic `referenceSummary` for that reference. Specify the reference name, statistics depth (`COUNTS` or `IMPACT`), group ordering, and fetch requirements. Filters and ordering can target any property of the referenced entity, and nothing is inherited from the generic constraint. Use when you need per-reference control over summary calculation.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("640b0f332096fca7d4298aa00575d6d3")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, statisticsDepth, null, null, null, facetGroupOrderBy,
			requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, fully overriding any generic `referenceSummary` for that reference. Specify the reference name, statistics depth (COUNTS or IMPACT), group filtering, ordering, and fetch requirements. Filters and ordering can target any property of the referenced entity, not just shared ones. This constraint never merges with the generic summary—define all requirements explicitly for the targeted reference.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         IMPACT,
	 *         filterGroupBy(attributeEquals("code", "premium")),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("94f98ed4164e7ebdc981ff13c1df7efb")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, statisticsDepth, null, facetGroupFilterBy, orderBy, null,
			requirements);
	}

	/**
	 * Calculates a reference summary for a single named reference, fully overriding any generic `referenceSummary` constraints for that reference. Specify the reference, statistics depth (`COUNTS` or `IMPACT`), and custom filter/order constraints targeting properties of the referenced entity. Only one `entityFetch` and one `entityGroupFetch` are allowed. No settings from the generic summary are inherited—define all requirements explicitly for the targeted reference.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         IMPACT,
	 *         filterBy(attributeEquals("origin", "EU")),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("007bac32004d0a0b50b85f9a9c179ae2")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, statisticsDepth, filterBy, null, null, facetGroupOrderBy,
			requirements);
	}

	/**
	 * Calculates a reference summary for a single named reference, fully overriding any generic `referenceSummary` for that reference. Specify the reference name, statistics depth (`COUNTS` or `IMPACT`), group filtering and ordering, and fetch requirements. All filter/order constraints apply to properties of the referenced entity or group only. Use this to tailor statistics and data loading for one reference without inheriting settings from the generic summary.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         IMPACT,
	 *         filterGroupBy(attributeEquals("region", "EU")),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("c216a349ab6950db0af3803f6f7623a9")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, statisticsDepth, null, facetGroupFilterBy, null,
			facetGroupOrderBy, requirements);
	}

	/**
	 * Produces a reference summary for a single named reference, overriding any generic `referenceSummary` for that reference. All filters, ordering, and entity fetches must be specified explicitly, as nothing is inherited or merged. Use this to apply custom statistics depth, filtering, ordering, and fetch requirements for one reference without affecting others.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "parameterValues",
	 *         IMPACT,
	 *         filterBy(attributeContains("code", "memory")),
	 *         filterGroupBy(attributeInSet("code", "ram-memory", "rom-memory")),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("code", "name")),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("d23a6d76817b0cf29d4e193969875f8d")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy facetFilterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		if (statisticsDepth == null) {
			statisticsDepth = FacetStatisticsDepth.COUNTS;
		}
		if (ArrayUtils.isEmptyOrItsValuesNull(requirements)) {
			return new ReferenceSummaryOfReference(
				referenceName, statisticsDepth,
				facetFilterBy, facetGroupFilterBy,
				facetOrderBy, facetGroupOrderBy
			);
		}
		return new ReferenceSummaryOfReference(
			referenceName,
			statisticsDepth,
			facetFilterBy, facetGroupFilterBy,
			facetOrderBy, facetGroupOrderBy,
			requirements
		);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `referenceSummary` for that reference. All filtering, ordering, and entity fetches apply only to the targeted reference and must be specified explicitly—nothing is inherited or merged from the generic constraint.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         filterBy(attributeEquals("code", "samsung")),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("544c1ae91cdef3b0a6980ac9e45f7900")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FilterBy facetFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, facetFilterBy, null,
			facetOrderBy, null, requirements);
	}

	/**
	 * Calculates a reference summary for a single named reference, fully overriding any generic `referenceSummary` for that reference. Filters and ordering can target any property of the referenced entity. No requirements are inherited from the generic summary constraint.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         filterBy(attributeEquals("code", "samsung")),
	 *         filterGroupBy(attributeInSet("code", "premium")),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("a4a06ce3cefc714d7cb3cdaed4b19a10")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, filterBy, facetGroupFilterBy,
			null, facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a reference summary for a specific named reference, overriding any generic `referenceSummary` constraints for that reference. All filters, ordering, and fetches apply only to the targeted reference and must be fully specified, as nothing is inherited or merged from the generic constraint. Use this to apply custom statistics, filtering, and sorting logic for a single reference, especially when its properties differ from others.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "parameterValues",
	 *         filterGroupBy(attributeInSet("code", "ram-memory", "rom-memory")),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("code", "name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("5f014b422ad9401c117905660d0a4de9")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, null, facetGroupFilterBy,
			orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a reference summary for a single named reference, overriding any generic `referenceSummary` for that reference. All filter and order constraints apply only to the referenced entity's properties, and requirements are not merged with the generic constraint. Use this to customize statistics, filtering, ordering, and entity fetches for a specific reference; nothing from the generic summary is inherited.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         filterBy(attributeEquals("code", "apple")),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("0a45337668159531240f6d9d0f3d7f4d")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, filterBy, null, orderBy,
			facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a reference summary for a single named reference, overriding any generic `referenceSummary` for that reference. Specify the reference name and optionally filter, group, order, and fetch requirements for referenced entities. Constraints are not merged—this constraint fully replaces the generic baseline for the targeted reference. All filter/order constraints apply to the referenced entity, not the reference relation. Use when you need per-reference customization in facet summaries.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         filterBy(attributeEquals("code", "sony")),
	 *         null,
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("554dbe7c3c08603f4b5e9765425a47c5")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, filterBy, facetGroupFilterBy,
			orderBy, null, requirements);
	}

	/**
	 * Calculates a reference summary for a single named reference, overriding any generic `referenceSummary` constraints for that reference. Only the specified reference is affected, and all filters, ordering, and entity fetches must be defined explicitly—nothing is inherited or merged from the generic constraint. Filters and ordering can target any property of the referenced entity or group. Use this to apply custom summary logic to one reference while others use generic settings.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         filterBy(attributeEquals("active", true)),
	 *         filterGroupBy(attributeInSet("category", "premium")),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("0f165dee22607c63e6eb0e124d930e1e")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, filterBy, facetGroupFilterBy,
			null, null, requirements);
	}

	/**
	 * Calculates a reference summary for a single named reference, overriding any generic `referenceSummary` for that reference. All filtering and ordering apply only to the referenced entity's properties, and entity fetches must be specified explicitly. This constraint never merges with the generic summary—its requirements are fully independent.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         orderBy(attributeNatural("name", ASC)),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("7893e10308613fe28fbcc0e2124665d1")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, null, null, orderBy,
			facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a reference summary for a single named reference, overriding any generic `referenceSummary` for that reference. All filtering, ordering, and entity fetches must be specified explicitly, as nothing is inherited or merged from the generic constraint. Use this to target a specific reference with custom filters or fetches; only that reference is affected.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         filterBy(attributeEquals("code", "sony")),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("77f73625bc143b2e30c20aaedaa398f9")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, filterBy, null, null, null,
			requirements);
	}

	/**
	 * Calculates a reference summary for a single named reference, overriding any generic `referenceSummary` for that reference. Defines its own filters, ordering, and fetches—nothing is inherited or merged from the generic constraint. Use to target reference-specific properties and behaviors.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("97f729221fcbe0e28053d255c3d238f3")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, null, null, orderBy, null,
			requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `referenceSummary` for that reference. Only the specified reference is affected; constraints are not merged, so all requirements (filters, ordering, fetches) must be defined here. Filters and ordering can target any property of the referenced entity or group entity. Use when you need per-reference customization of facet statistics and output.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         filterGroupBy(attributeInSet("code", "premium", "budget")),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("f37d1988e2206d7ae4ca1cdf9ff8591b")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, null, facetGroupFilterBy, null,
			null, requirements);
	}

	/**
	 * Produces a reference summary for a single named reference, overriding any generic `referenceSummary` for that reference. All filtering, ordering, and entity fetches apply only to the targeted reference, and nothing is inherited from the generic constraint. Use this when you need custom statistics or fetches for a specific reference, such as applying group ordering or fetching different attributes than the baseline.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("code", "name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("e7c5991d1055710fab059bade9dc1842")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, null, null, null,
			facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a reference summary for a single named reference, overriding any generic `referenceSummary` for that reference. Filters and ordering can target any property of the referenced entity. All requirements must be specified explicitly; nothing is inherited from the generic constraint.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         filterGroupBy(attributeEquals("code", "premium")),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("29a12049ae2097130a743a614f901516")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, null, facetGroupFilterBy,
			orderBy, null, requirements);
	}

	/**
	 * Calculates a reference summary for a single named reference, overriding any generic `referenceSummary` for that reference. All filters, ordering, and fetches apply only to the specified reference and do not inherit from the generic constraint. Use this to target reference-specific properties or behaviors that differ from the baseline. Only one `EntityFetch` and one `EntityGroupFetch` may be provided.
	 *
	 * ```evitaql
	 * referenceSummaryOfReference(
	 *     "brand",
	 *     filterBy(attributeEquals("code", "amazon")),
	 *     orderGroupBy(attributeNatural("name", ASC)),
	 *     entityFetch(attributeContent("name"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("33fcb9e381c9c475a94d50d80f090c67")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, filterBy, null, null,
			facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a reference summary for a single named reference, overriding any generic `referenceSummary` for that reference. Filters and ordering can target any property of the referenced entity or group. Inherits nothing from the generic constraint—define all requirements explicitly.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         filterGroupBy(attributeEquals("type", "premium")),
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("ec07606833677e919f9c795908a2084d")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		return referenceSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, null, facetGroupFilterBy, null,
			facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a reference summary for a single named reference, overriding any generic `referenceSummary` for that reference. Defines its own filters, ordering, and fetches for the targeted reference type—nothing is inherited or merged. Use to customize statistics and data loading for a specific reference, with all filter/order constraints applying to the referenced entity only.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         filterBy(attributeEquals("code", "apple")),
	 *         null,
	 *         orderBy(attributeNatural("name", ASC))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("b042675011fd6c9815b30c8bab585c98")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReference(
		@Nonnull String referenceName,
		@Nullable FilterBy facetFilterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		requireName(referenceName, "referenceName");
		if (ArrayUtils.isEmptyOrItsValuesNull(requirements)) {
			return new ReferenceSummaryOfReference(
				referenceName, FacetStatisticsDepth.COUNTS,
				facetFilterBy, facetGroupFilterBy,
				facetOrderBy, facetGroupOrderBy
			);
		}
		return new ReferenceSummaryOfReference(
			referenceName,
			FacetStatisticsDepth.COUNTS,
			facetFilterBy, facetGroupFilterBy,
			facetOrderBy, facetGroupOrderBy,
			requirements
		);
	}

	/**
	 * Calculates a reference summary for a single named reference, overriding any generic `referenceSummary` constraints for that reference. Specify the reference name, statistics depth, entity fetches, and optional histogram statistics; nothing from the generic constraint is inherited or merged. Use this to target reference-specific filtering, ordering, and fetch requirements, especially when you need different summary logic for a particular reference.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         IMPACT,
	 *         entityFetch(attributeContent("name")),
	 *         entityGroupFetch(attributeContent("code")),
	 *         histogramStatistics(attributeHistogram("popularity", 5))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("e36eee1e6618ab86a08f0ff6577722f2")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReferenceWithHistograms(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable EntityFetch entityFetch,
		@Nullable EntityGroupFetch entityGroupFetch,
		@Nullable ReferenceHistogramStatistics... histogramStatistics
	) {
		requireName(referenceName, "referenceName");
		final RequireConstraint[] children = mergeReferenceSummaryChildren(entityFetch, entityGroupFetch,
			histogramStatistics);
		return new ReferenceSummaryOfReference(
			referenceName,
			statisticsDepth == null ? FacetStatisticsDepth.COUNTS : statisticsDepth,
			children
		);
	}

	/**
	 * Calculates a reference summary for a single named reference, overriding any generic `referenceSummary` for that reference. Lets you specify statistics depth, filters, ordering, entity fetches, and histogram statistics for the targeted reference only—these settings are not merged with the generic constraint. Use this when you need per-reference control over summary calculation, including histograms, filters, and fetches for reference or group entities.
	 *
	 * ```evitaql
	 * require(
	 *     referenceSummaryOfReference(
	 *         "brand",
	 *         IMPACT,
	 *         filterBy(attributeEquals("origin", "domestic")),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name")),
	 *         entityGroupFetch(attributeContent("code")),
	 *         histogramStatistics(10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.ReferenceSummaryOfReference
	 */
	@SourceHash("a403cd7e0cbe8d8448147b42c94c95a5")
	@Nullable
	static ReferenceSummaryOfReference referenceSummaryOfReferenceWithHistograms(
		@Nonnull String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy facetFilterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetch entityFetch,
		@Nullable EntityGroupFetch entityGroupFetch,
		@Nullable ReferenceHistogramStatistics... histogramStatistics
	) {
		requireName(referenceName, "referenceName");
		final RequireConstraint[] children = mergeReferenceSummaryChildren(entityFetch, entityGroupFetch,
			histogramStatistics);
		return new ReferenceSummaryOfReference(
			referenceName,
			statisticsDepth == null ? FacetStatisticsDepth.COUNTS : statisticsDepth,
			facetFilterBy, facetGroupFilterBy,
			facetOrderBy, facetGroupOrderBy,
			children
		);
	}

	/**
	 * Enables detailed query execution profiling, capturing a hierarchical breakdown of all processing phases, their timings, and arguments. Telemetry is included only when explicitly requested, as it adds overhead; use it for debugging or profiling, not in production hot paths. The response includes a tree of operations, their durations, and phase-specific details to help diagnose performance bottlenecks.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/debug#query-telemetry)
	 *
	 * @see io.evitadb.api.query.require.QueryTelemetry
	 */
	@SourceHash("ab12bc4a4c1081e5637274c937072740")
	@Nonnull
	static QueryTelemetry queryTelemetry() {
		return new QueryTelemetry();
	}

	/**
	 * Enables detailed query execution profiling, returning a hierarchical breakdown of all phases (planning, filtering, etc.) with timing, arguments, and sub-steps in the extra-results. Use for debugging or profiling; incurs overhead and is never implicit. Pass `PLAN` to also include the internal formula tree and planner decisions. Not recommended for production hot paths.
	 *
	 * ```evitaql
	 * queryTelemetry()
	 * queryTelemetry(PLAN)
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/debug#query-telemetry)
	 *
	 * @see io.evitadb.api.query.require.QueryTelemetry
	 */
	@SourceHash("234e4a08ed8de25067a80055336175e5")
	@Nonnull
	static QueryTelemetry queryTelemetry(@Nullable QueryTelemetryContent content) {
		return new QueryTelemetry(content);
	}

	/**
	 * This `debug` require is targeted for internal purposes only and is not exposed in public evitaDB API.
	*/
	@Nullable
	static Debug debug(@Nullable DebugMode... debugMode) {
		requireNonNullItems(debugMode, "debugMode");
		return ArrayUtils.isEmptyOrItsValuesNull(debugMode) ? null : new Debug(debugMode);
	}

	/**
	 * Triggers loading of the full entity body, including all attributes, associated data, prices, references, and hierarchy information, for each matched entity. Use with care, as fetching all data increases I/O and network load.  
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#entity-fetch)
	 *
	 * @see io.evitadb.api.query.require.EntityFetch
	 */
	@SourceHash("f31b657fe14d5493cd66eaed5e8c8116")
	@Nonnull
	static EntityFetch entityFetchAll() {
		return entityFetch(
			attributeContentAll(), hierarchyContent(),
			associatedDataContentAll(), priceContentAll(),
			referenceContentAllWithAttributes(), dataInLocalesAll()
		);
	}

	/**
	 * Retrieves the bodies of group entities associated with references, enabling access to group-level data (e.g., group names or attributes) for referenced items. Only meaningful inside `referenceContent`. With no arguments, loads just the group entity body.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#entity-group-fetch)
	 *
	 * @see io.evitadb.api.query.require.EntityGroupFetch
	 */
	@SourceHash("b31ef032cf1f35487b48463028e385da")
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
		if (ArrayUtils.isEmptyOrItsValuesNull(combineWith)) {
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
		if (ArrayUtils.isEmptyOrItsValuesNull(combineWith)) {
			return entityFetchAllContent();
		} else {
			return ArrayUtils.mergeArrays(
				entityFetchAllContent(),
				combineWith
			);
		}
	}

}
