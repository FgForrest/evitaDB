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
 * - **Null Handling**: Many factory methods return `null` when given null or empty arguments, allowing queries to
 *   be built conditionally without explicit null checks.
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
 * - **Null Tolerance**: Many factory methods accept nullable arguments and return `null` when constraints cannot
 *   be meaningfully constructed (e.g., when all arguments are null or empty). This allows for fluent conditional
 *   query building.
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
	static <T extends Comparable<T> & Serializable> Label label(@Nullable String name, @Nullable T value) {
		return name == null || name.isBlank() || value == null ? null : new Label(name, value);
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
		return constraint == null ? null : new FilterBy(constraint);
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
		return constraint == null ? null : new FilterGroupBy(constraint);
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
		if (constraints == null) {
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
		if (constraints == null) {
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
	static ReferenceHaving referenceHaving(@Nullable String referenceName, @Nullable FilterConstraint... constraint) {
		return referenceName == null ? null : new ReferenceHaving(referenceName, constraint);
	}

	/**
	 * Separates user-controlled filters (e.g., facets, price ranges) from mandatory system constraints, enabling the query engine to relax or exclude these filters during facet summary and histogram computations for broader result statistics. Forbidden children include locale, price context, hierarchy, reference filters, and nested userFilter to ensure consistent semantics.
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
	@SourceHash("8a9c25cfa49ae405d36dd1d17b10542e")
	@Nullable
	static UserFilter userFilter(@Nullable FilterConstraint... constraints) {
		if (constraints == null) {
			return null;
		}
		return new UserFilter(constraints);
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
	static <T extends Serializable> AttributeBetween attributeBetween(@Nullable String attributeName, @Nullable T from, @Nullable T to) {
		if (attributeName == null || (from == null && to == null)) {
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
	static AttributeContains attributeContains(@Nullable String attributeName, @Nullable String textToSearch) {
		return attributeName == null || textToSearch == null ? null : new AttributeContains(attributeName, textToSearch);
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
	static AttributeStartsWith attributeStartsWith(@Nullable String attributeName, @Nullable String textToSearch) {
		return attributeName == null || textToSearch == null ? null : new AttributeStartsWith(attributeName, textToSearch);
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
	static AttributeEndsWith attributeEndsWith(@Nullable String attributeName, @Nullable String textToSearch) {
		return attributeName == null || textToSearch == null ? null : new AttributeEndsWith(attributeName, textToSearch);
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
	static <T extends Serializable> AttributeEquals attributeEquals(@Nullable String attributeName, @Nullable T attributeValue) {
		return attributeName == null || attributeValue == null ? null : new AttributeEquals(attributeName, attributeValue);
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
	static <T extends Serializable> AttributeLessThan attributeLessThan(@Nullable String attributeName, @Nullable T attributeValue) {
		return attributeName == null || attributeValue == null ? null : new AttributeLessThan(attributeName, attributeValue);
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
	static <T extends Serializable> AttributeLessThanEquals attributeLessThanEquals(@Nullable String attributeName, @Nullable T attributeValue) {
		return attributeName == null || attributeValue == null ? null : new AttributeLessThanEquals(attributeName, attributeValue);
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
	static <T extends Serializable> AttributeGreaterThan attributeGreaterThan(@Nullable String attributeName, @Nullable T attributeValue) {
		return attributeName == null || attributeValue == null ? null : new AttributeGreaterThan(attributeName, attributeValue);
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
	static <T extends Serializable> AttributeGreaterThanEquals attributeGreaterThanEquals(@Nullable String attributeName, @Nullable T attributeValue) {
		return attributeName == null || attributeValue == null ? null : new AttributeGreaterThanEquals(attributeName, attributeValue);
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
		return with == null ? new HierarchyWithinRoot() : new HierarchyWithinRoot(with);
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
	static HierarchyWithinRoot hierarchyWithinRoot(@Nullable String referenceName, @Nullable HierarchySpecificationFilterConstraint... with) {
		return referenceName == null || with == null ? new HierarchyWithinRoot() : new HierarchyWithinRoot(referenceName, with);
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
	 * Sets the locale context for all localized attributes and associated data in the query, ensuring filters, sorting, and fetched values use the specified language/region (IETF BCP 47 format). Required when accessing localized data; only one locale per query is allowed.
	 *
	 * ```evitaql
	 * filterBy(
	 *   entityLocaleEquals("en-US"),
	 *   attributeContains("name", "phone")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/locale#entity-locale-equals)
	 *
	 * @see io.evitadb.api.query.filter.EntityLocaleEquals
	 */
	@SourceHash("e0d256276b3590b41703caf39f84089d")
	@Nullable
	static EntityLocaleEquals entityLocaleEquals(@Nullable Locale locale) {
		return locale == null ? null : new EntityLocaleEquals(locale);
	}

	/**
	 * Filters referenced entities by their attributes or properties within a reference context, shifting the filter scope from the relation to the referenced entity itself. Use only as a child of `referenceHaving` or `facetHaving`. Accepts one filter constraint; combine multiple conditions with logical containers like `and` or `or`.
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
	@SourceHash("72622cf09917785d794cf6e49573f69f")
	@Nullable
	static EntityHaving entityHaving(@Nullable FilterConstraint filterConstraint) {
		return filterConstraint == null ? null : new EntityHaving(filterConstraint);
	}

	/**
	 * Filters based on attributes or properties of the group entity associated with a reference, shifting the filtering scope from the reference or referenced entity to the group entity. Use only within `referenceHaving` or `facetHaving`. Accepts a single filter constraint; combine multiple conditions with logical containers like `and` or `or`.
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
	@SourceHash("f61398e58d2ef2cbf7c00de2f2309b6b")
	@Nullable
	static GroupHaving groupHaving(@Nullable FilterConstraint filterConstraint) {
		return filterConstraint == null ? null : new GroupHaving(filterConstraint);
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
	static AttributeInRange attributeInRange(@Nullable String attributeName, @Nullable OffsetDateTime atTheMoment) {
		return attributeName == null || atTheMoment == null ? null : new AttributeInRange(attributeName, atTheMoment);
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
	static AttributeInRange attributeInRange(@Nullable String attributeName, @Nullable Number theValue) {
		return attributeName == null || theValue == null ? null : new AttributeInRange(attributeName, theValue);
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
	static AttributeInRange attributeInRangeNow(@Nullable String attributeName) {
		return attributeName == null ? null : new AttributeInRange(attributeName);
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
	 * Filters entities where the specified attribute exactly equals `false`. Useful for precise matching against boolean attributes, treating `false` as numeric zero. Works with filterable or unique attributes, including arrays (matches if any element is `false`).
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/comparable#attribute-equals)
	 *
	 * @see io.evitadb.api.query.filter.AttributeEquals
	 */
	@SourceHash("718d0813b4684b02b33966c8c37912a8")
	@Nullable
	static AttributeEquals attributeEqualsFalse(@Nullable String attributeName) {
		return attributeName == null ? null : new AttributeEquals(attributeName, Boolean.FALSE);
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
	static AttributeEquals attributeEqualsTrue(@Nullable String attributeName) {
		return attributeName == null ? null : new AttributeEquals(attributeName, Boolean.TRUE);
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
	static AttributeIs attributeIs(@Nullable String attributeName, @Nullable AttributeSpecialValue specialValue) {
		if (attributeName == null || specialValue == null) {
			return null;
		}
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
	static AttributeIs attributeIsNull(@Nullable String attributeName) {
		return attributeName == null ? null : new AttributeIs(attributeName, AttributeSpecialValue.NULL);
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
	static AttributeIs attributeIsNotNull(@Nullable String attributeName) {
		return attributeName == null ? null : new AttributeIs(attributeName, AttributeSpecialValue.NOT_NULL);
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
	 * Filters entities by faceted references (e.g., brand, color) for drill-down navigation and facet statistics. When used inside `userFilter`, it enables accurate "what-if" predictions in conjunction with `facetSummary`, excluding itself from impact calculations. Outside `userFilter`, it acts like `referenceHaving`.
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
	@SourceHash("2a5eaf779bca8ed3cdbca87d3556b023")
	@Nullable
	static FacetHaving facetHaving(@Nullable String referenceName, @Nullable FilterConstraint... constraint) {
		return referenceName == null || ArrayUtils.isEmptyOrItsValuesNull(constraint) ? null : new FacetHaving(referenceName, constraint);
	}

	/**
	 * Modifies `facetHaving` to include not just entities directly referencing a hierarchical facet (e.g., a category), but also those referencing any of its descendants. Use only with hierarchical references; errors if used otherwise.
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
	@SourceHash("6f09433eef54f4b7482ca28d6a978ab9")
	@Nonnull
	static FacetIncludingChildren includingChildren() {
		return new FacetIncludingChildren();
	}

	/**
	 * Modifies hierarchical facet filtering to include only child entities that match the given filter constraint, allowing fine-grained control over which descendants of a faceted entity are considered in `facetHaving`. Use only with hierarchical references.
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
	@SourceHash("001a3b5943fb94c22a731667eaa90dc3")
	@Nonnull
	static FacetIncludingChildren includingChildrenHaving(@Nullable FilterConstraint filterConstraint) {
		return filterConstraint == null ? new FacetIncludingChildren() : new FacetIncludingChildren(filterConstraint);
	}

	/**
	 * Modifies hierarchical facet filtering to include entities referencing a faceted entity or any of its descendants, **excluding** children that match the given filter. Use within `facetHaving` for hierarchical references to blacklist certain branches from facet stats and matches.
	 *
	 * ```evitaql
	 * facetHaving(
	 *     "categories",
	 *     entityHaving(attributeEquals("code", "accessories")),
	 *     includingChildrenExcept(attributeEquals("visible", "INVISIBLE"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/references#including-children-except)
	 *
	 * @see io.evitadb.api.query.filter.FacetIncludingChildrenExcept
	 */
	@SourceHash("19b527716aa74e5578533e4d27a351f7")
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
	 * Restricts child filtering constraints to a specific data scope (LIVE or ARCHIVED), enabling scope-aware queries where different filters apply based on indexing and data availability in each scope. Prevents query failures when attributes are indexed only in certain scopes and allows flexible, scope-specific filtering logic.
	 *
	 * ```evitaql
	 * filterBy(
	 *     and(
	 *         inScope(LIVE, attributeEquals('name', 'LED TV')),
	 *         scope(LIVE, ARCHIVED)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/behavioral#in-scope)
	 *
	 * @see io.evitadb.api.query.filter.FilterInScope
	 */
	@SourceHash("773df310836b207593c2688b05682204")
	@Nullable
	static FilterInScope inScope(@Nullable Scope scope, @Nullable FilterConstraint... constraints) {
		return scope == null || ArrayUtils.isEmptyOrItsValuesNull(constraints) ? null : new FilterInScope(scope, constraints);
	}

	/**
	 * Specifies which data scopes (LIVE, ARCHIVED, or both) the query should search within. By default, only LIVE entities are queried; use this constraint to include ARCHIVED (soft-deleted) data or combine both. Duplicates are resolved by scope order, and uniqueness is enforced per scope.
	 *
	 * ```evitaql
	 * query(
	 *     collection('Product'),
	 *     filterBy(
	 *         scope(LIVE, ARCHIVED)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/behavioral#scope)
	 *
	 * @see io.evitadb.api.query.filter.EntityScope
	 */
	@SourceHash("ca64ab4dbecd576b2d2b7fc691f8cc1f")
	@Nullable
	static EntityScope scope(@Nullable Scope... scope) {
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
		if (constraints == null) {
			return null;
		}
		return new OrderBy(constraints);
	}

	/**
	 * Limits the enclosed ordering constraints to entities within the specified scope, ensuring those constraints are only applied where valid. Useful when ordering by attributes that may not be indexed or available in all scopes, preventing query failures.
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
	@SourceHash("523478cdf907e245f0d440512e3b9112")
	@Nullable
	static OrderInScope inScope(@Nullable Scope scope, @Nullable OrderConstraint... constraints) {
		return scope == null || ArrayUtils.isEmptyOrItsValuesNull(constraints) ? null : new OrderInScope(scope, constraints);
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
		if (constraints == null) {
			return null;
		}
		return new OrderGroupBy(constraints);
	}

	/**
	 * Defines multiple ordering segments within a single query, each with its own sorting and optional filtering and limit. Each segment extracts entities from the filtered result, applies its order and limit, and removes them before processing the next segment. Remaining entities are appended in primary key order. Segments enable complex, prioritized ordering beyond standard multi-clause `orderBy`.
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
	@SourceHash("078ba1fd7ff87b06c5c8f4096145288c")
	@Nullable
	static Segments segments(@Nullable Segment... constraints) {
		if (ArrayUtils.isEmptyOrItsValuesNull(constraints)) {
			return null;
		}
		return new Segments(constraints);
	}

	/**
	 * Defines a segment within an ordering pipeline, specifying how a portion of filtered results should be sorted using the provided `OrderBy` clause. All entities matched by this segment are excluded from subsequent segments. If no limit or filter is set, all remaining entities are sorted as specified.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/segment)
	 *
	 * @see io.evitadb.api.query.order.Segment
	 */
	@SourceHash("4d7e601530242040ce98546b66561355")
	@Nullable
	static Segment segment(@Nonnull OrderBy orderBy) {
		if (orderBy == null) {
			return null;
		}
		return new Segment(orderBy);
	}

	/**
	 * Defines a segment within a segments container that sorts the filtered result using the specified `orderBy` clause and extracts up to the given `limit` of entities. Entities selected by this segment are excluded from subsequent segments. If no limit is provided, all matching entities are taken.
	 *
	 * ```evitaql
	 * segment(
	 *    orderBy(attributeNatural("orderedQuantity", DESC)),
	 *    limit(3)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/segment)
	 *
	 * @see io.evitadb.api.query.order.Segment
	 */
	@SourceHash("45d8939ba6768768296253f745c46d82")
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
	 * Defines a segment within a segmented ordering, applying the given filter to select entities for this segment and sorting them according to the specified order. Entities matched here are excluded from later segments. All matching entities are included unless limited elsewhere.
	 *
	 * ```evitaql
	 * segment(
	 *    entityHaving(
	 *       attributeEquals("category", "electronics")
	 *    ),
	 *    orderBy(
	 *       attributeNatural("popularity", DESC)
	 *    )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/segment)
	 *
	 * @see io.evitadb.api.query.order.Segment
	 */
	@SourceHash("4799cae4fa8b00d82288bbb724fdc89f")
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
	 * Defines a segment within a segmented ordering, applying an optional filter to select entities, a mandatory sorting order, and an optional limit on the number of entities to extract. Entities chosen by this segment are excluded from subsequent segments.
	 *
	 * ```evitaql
	 * segment(
	 *    entityHaving(attributeEquals("new", true)),
	 *    orderBy(attributeNatural("priority", DESC)),
	 *    limit(5)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/ordering/segment)
	 *
	 * @see io.evitadb.api.query.order.Segment
	 */
	@SourceHash("69105f3830547662be41b4a24267301e")
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
		if (ArrayUtils.isEmptyOrItsValuesNull(primaryKey)) {
			return null;
		}
		return new EntityPrimaryKeyExact(primaryKey);
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
	static AttributeSetInFilter attributeSetInFilter(@Nullable String attributeName) {
		if (attributeName == null || attributeName.isBlank()) {
			return null;
		}
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
	static AttributeSetExact attributeSetExact(@Nullable String attributeName, @Nullable Serializable... attributeValues) {
		if (attributeName == null || attributeName.isBlank() || ArrayUtils.isEmptyOrItsValuesNull(attributeValues)) {
			return null;
		}
		return new AttributeSetExact(attributeName, attributeValues);
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
	static ReferenceProperty referenceProperty(@Nullable String referenceName, @Nullable OrderConstraint... constraints) {
		if (referenceName == null || ArrayUtils.isEmptyOrItsValuesNull(constraints)) {
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
		if (constraints == null) {
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
		if (constraints == null) {
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
	static AttributeNatural attributeNatural(@Nullable String attributeName) {
		return attributeName == null ? null : new AttributeNatural(attributeName);
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
	static AttributeNatural attributeNatural(@Nullable String attributeName, @Nullable OrderDirection orderDirection) {
		return attributeName == null ? null :
			new AttributeNatural(attributeName, orderDirection == null ? OrderDirection.ASC : orderDirection);
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
	@Nonnull
	static PriceDiscount priceDiscount(@Nonnull String... inPriceLists) {
		return new PriceDiscount(inPriceLists);
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
	@Nonnull
	static PriceDiscount priceDiscount(@Nullable OrderDirection orderDirection, @Nonnull String... inPriceLists) {
		return new PriceDiscount(orderDirection == null ? OrderDirection.DESC : orderDirection, inPriceLists);
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
		if (constraints == null) {
			return null;
		}
		return new Require(constraints);
	}

	/**
	 * Restricts the enclosed require constraints to apply only within the specified entity scope (LIVE or ARCHIVED), ensuring that requirements unsupported in certain scopes (like facet summaries in ARCHIVED) do not cause query failures. All children must be unique RequireConstraints; at least one is required.
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
	@SourceHash("f68fdc318b95a0c07488dea8b3cd91cc")
	@Nullable
	static RequireInScope inScope(@Nullable Scope scope, @Nullable RequireConstraint... constraints) {
		return scope == null || ArrayUtils.isEmptyOrItsValuesNull(constraints) ? null : new RequireInScope(scope, constraints);
	}

	/**
	 * Computes a value-distribution histogram for one or more numeric filterable attributes, returning the result in extra-results for use in range-filter UI widgets. Only entities matching the mandatory filter are included; userFilter range constraints are ignored to avoid dead ends. At least one attribute name must be provided. Uses STANDARD bucket behavior by default.
	 *
	 * ```evitaql
	 * attributeHistogram(20, "price")
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/histogram#attribute-histogram)
	 *
	 * @see io.evitadb.api.query.require.AttributeHistogram
	 */
	@SourceHash("4cfb5689db5ceec235262db5d5cf10b9")
	@Nullable
	static AttributeHistogram attributeHistogram(int requestedBucketCount, @Nullable String... attributeName) {
		if (ArrayUtils.isEmptyOrItsValuesNull(attributeName)) {
			return null;
		}
		return new AttributeHistogram(requestedBucketCount, attributeName);
	}

	/**
	 * Computes a value-distribution histogram for one or more numeric filterable attributes, returning the result in extra-results for UI range filters (e.g., price sliders). Only entities matching the mandatory filter are included; userFilter range constraints are ignored to prevent dead ends. Supports different bucket behaviors (standard, optimized, equalized) and requires at least one attribute name.
	 *
	 * ```evitaql
	 * attributeHistogram(20, EQUALIZED, "price")
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/histogram#attribute-histogram)
	 *
	 * @see io.evitadb.api.query.require.AttributeHistogram
	 */
	@SourceHash("e3d44d26d004e0a85aaffab6f6ee19b1")
	@Nullable
	static AttributeHistogram attributeHistogram(int requestedBucketCount, @Nullable HistogramBehavior behavior, @Nullable String... attributeName) {
		if (ArrayUtils.isEmptyOrItsValuesNull(attributeName)) {
			return null;
		}
		return new AttributeHistogram(requestedBucketCount, behavior, attributeName);
	}

	/**
	 * Computes a price-distribution histogram for entities matching the main `filterBy` conditions, excluding any price or attribute range narrowing from `userFilter`. The histogram aids UI elements like price sliders and defaults to 20 equal-width buckets using the price with tax unless overridden.
	 * ```evitaql
	 * priceHistogram(20)
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/histogram#price-histogram)
	 *
	 * @see io.evitadb.api.query.require.PriceHistogram
	 */
	@SourceHash("7522963a5b80a1625d04ea1da62a888e")
	@Nonnull
	static PriceHistogram priceHistogram(int requestedBucketCount) {
		return new PriceHistogram(requestedBucketCount);
	}

	/**
	 * Computes a price-distribution histogram for entities matching the mandatory filter, ignoring user-specified price or attribute ranges. The histogram's bucket count and boundary behavior are configurable, supporting standard, optimized, or frequency-equalized modes. Useful for powering price sliders or similar UI elements.
	 *
	 * ```evitaql
	 * priceHistogram(20, OPTIMIZED)
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/histogram#price-histogram)
	 *
	 * @see io.evitadb.api.query.require.PriceHistogram
	 */
	@SourceHash("fc2bdb417a03ba9e356e3f746ad86b62")
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
	static FacetGroupsConjunction facetGroupsConjunction(@Nullable String referenceName, @Nullable FilterBy filterBy) {
		return referenceName == null ? null : new FacetGroupsConjunction(referenceName, filterBy);
	}

	/**
	 * Overrides the default OR logic between facets within or across groups for a given reference, enforcing AND (conjunction) at the specified relation level. This means all selected facets must match simultaneously, making queries more restrictive and affecting facet impact predictions.
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-conjunction)
	 *
	 * @see io.evitadb.api.query.require.FacetGroupsConjunction
	 */
	@SourceHash("bdc69fa75d4a6a4a79785a5af218a77b")
	@Nullable
	static FacetGroupsConjunction facetGroupsConjunction(@Nullable String referenceName, @Nullable FacetGroupRelationLevel facetGroupRelationLevel) {
		return referenceName == null ? null : new FacetGroupsConjunction(referenceName, facetGroupRelationLevel, null);
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
	static FacetGroupsConjunction facetGroupsConjunction(@Nullable String referenceName, @Nullable FacetGroupRelationLevel facetGroupRelationLevel, @Nullable FilterBy filterBy) {
		return referenceName == null ? null : new FacetGroupsConjunction(referenceName, facetGroupRelationLevel, filterBy);
	}

	/**
	 * Overrides the default OR logic between facets within a group for the specified reference, enforcing AND (conjunction) so that only entities matching all selected facets in the group are returned. Useful for stricter filtering where all chosen facets must be present. [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-conjunction)
	 *
	 * @see io.evitadb.api.query.require.FacetGroupsConjunction
	 */
	@SourceHash("89f57658f87561389a844c4c53cad9fd")
	@Nullable
	static FacetGroupsConjunction facetGroupsConjunction(@Nullable String referenceName) {
		return referenceName == null ? null : new FacetGroupsConjunction(referenceName, null);
	}

	/**
	 * Overrides the default AND logic between different facet groups for the specified reference, applying OR (disjunction) instead, so entities matching any of the selected groups are included. Use `filterBy` to target specific groups; otherwise, all groups are affected. This expands result sets and impacts facet summary calculations.
	 *
	 * ```evitaql
	 * facetGroupsDisjunction(
	 *     "parameterValues",
	 *     filterBy(attributeInSet("code", "color", "tags"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-disjunction)
	 *
	 * @see io.evitadb.api.query.require.FacetGroupsDisjunction
	 */
	@SourceHash("2fc451592ee9d7dd7fdcd3e318df7a5c")
	@Nullable
	static FacetGroupsDisjunction facetGroupsDisjunction(@Nullable String referenceName, @Nullable FilterBy filterBy) {
		return referenceName == null ? null : new FacetGroupsDisjunction(referenceName, filterBy);
	}

	/**
	 * Overrides the default AND logic between different facet groups for the specified reference, applying OR (disjunction) at the chosen relation level. This means entities matching any targeted group will be included, broadening result sets compared to the default.
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-disjunction)
	 *
	 * @see io.evitadb.api.query.require.FacetGroupsDisjunction
	 */
	@SourceHash("e1d80bef7c2c47b8f6722681b9cf48c3")
	@Nullable
	static FacetGroupsDisjunction facetGroupsDisjunction(@Nullable String referenceName, @Nullable FacetGroupRelationLevel facetGroupRelationLevel) {
		return referenceName == null ? null : new FacetGroupsDisjunction(referenceName, facetGroupRelationLevel, null);
	}

	/**
	 * Overrides the default AND logic between different facet groups for the specified reference, applying OR (disjunction) at the chosen relation level. This allows entities matching any of the targeted groups to be returned, expanding result sets. Use an optional filter to restrict which groups are affected.
	 *
	 * ```evitaql
	 * facetGroupsDisjunction(
	 *     "parameterValues",
	 *     WITH_DIFFERENT_GROUPS,
	 *     filterBy(attributeInSet("code", "color", "tags"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-disjunction)
	 *
	 * @see io.evitadb.api.query.require.FacetGroupsDisjunction
	 */
	@SourceHash("8be53373ba64a2d5672e2c3b3c5e31f7")
	@Nullable
	static FacetGroupsDisjunction facetGroupsDisjunction(@Nullable String referenceName, @Nullable FacetGroupRelationLevel facetGroupRelationLevel, @Nullable FilterBy filterBy) {
		return referenceName == null ? null : new FacetGroupsDisjunction(referenceName, facetGroupRelationLevel, filterBy);
	}

	/**
	 * Overrides the default logical AND between different facet groups for the specified reference, applying logical OR instead. This means entities matching any of the selected groups will be returned, broadening results compared to the default behavior. Use to relax group-level facet filtering for a reference.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-disjunction)
	 *
	 * @see io.evitadb.api.query.require.FacetGroupsDisjunction
	 */
	@SourceHash("e368a97b378b9f64ef6ac52c85548ea4")
	@Nullable
	static FacetGroupsDisjunction facetGroupsDisjunction(@Nullable String referenceName) {
		return referenceName == null ? null : new FacetGroupsDisjunction(referenceName, null);
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
	static FacetGroupsNegation facetGroupsNegation(@Nullable String referenceName, @Nullable FilterBy filterBy) {
		return referenceName == null ? null : new FacetGroupsNegation(referenceName, filterBy);
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
	static FacetGroupsNegation facetGroupsNegation(@Nullable String referenceName, @Nullable FacetGroupRelationLevel facetGroupRelationLevel, @Nullable FilterBy filterBy) {
		return referenceName == null ? null : new FacetGroupsNegation(referenceName, facetGroupRelationLevel, filterBy);
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
	static FacetGroupsNegation facetGroupsNegation(@Nullable String referenceName, @Nullable FacetGroupRelationLevel facetGroupRelationLevel) {
		return referenceName == null ? null : new FacetGroupsNegation(referenceName, facetGroupRelationLevel, null);
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
	static FacetGroupsNegation facetGroupsNegation(@Nullable String referenceName) {
		return referenceName == null ? null : new FacetGroupsNegation(referenceName, null);
	}

	/**
	 * Marks facet options in the specified groups of a faceted reference as mutually exclusive, so only one facet can be selected per group at a time. This changes impact predictions to reflect replacing the current selection with a new one, not adding to it. Use this to ensure UI renders such groups as radio buttons, and to override global exclusivity for targeted groups.
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
	@SourceHash("ee884a58497b9f1243a4d044f3229628")
	@Nullable
	static FacetGroupsExclusivity facetGroupsExclusivity(@Nullable String referenceName, @Nullable FilterBy filterBy) {
		return referenceName == null ? null : new FacetGroupsExclusivity(referenceName, filterBy);
	}

	/**
	 * Marks facet options in specified groups of a faceted reference as mutually exclusive, so only one facet can be selected at a time at the chosen relation level. This changes impact predictions to reflect replacing, not adding, selections—ideal for radio-button UIs. Use the optional filter to target specific groups; takes precedence over global exclusivity rules.
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
	@SourceHash("c50a382ccd8419c054840285a7ae8058")
	@Nullable
	static FacetGroupsExclusivity facetGroupsExclusivity(@Nullable String referenceName, @Nullable FacetGroupRelationLevel facetGroupRelationLevel, @Nullable FilterBy filterBy) {
		return referenceName == null ? null : new FacetGroupsExclusivity(referenceName, facetGroupRelationLevel, filterBy);
	}

	/**
	 * Marks facet options in the specified reference as mutually exclusive at the chosen group relation level, ensuring only one facet can be selected at a time within or across groups. This affects impact predictions in facet summaries, making them reflect the result count if you replaced your current selection with another option, not added to it. Use for UI scenarios where radio button behavior is needed.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-exclusivity)
	 *
	 * @see io.evitadb.api.query.require.FacetGroupsExclusivity
	 */
	@SourceHash("d28e37a6f4f8cbb2d74c2c29dacf16d3")
	@Nullable
	static FacetGroupsExclusivity facetGroupsExclusivity(@Nullable String referenceName, @Nullable FacetGroupRelationLevel facetGroupRelationLevel) {
		return referenceName == null ? null : new FacetGroupsExclusivity(referenceName, facetGroupRelationLevel, null);
	}

	/**
	 * Marks all facet groups of the specified reference as mutually exclusive, so only one facet option per group can be selected at a time. This affects impact predictions in facet summaries, making them reflect "replace" rather than "add" semantics, and is ideal for UI scenarios where radio buttons (single selection) are appropriate.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-groups-exclusivity)
	 *
	 * @see io.evitadb.api.query.require.FacetGroupsExclusivity
	 */
	@SourceHash("3b5ea1b6055ea16c8d2514d978373652")
	@Nullable
	static FacetGroupsExclusivity facetGroupsExclusivity(@Nullable String referenceName) {
		return referenceName == null ? null : new FacetGroupsExclusivity(referenceName, null);
	}

	/**
	 * Sets global default relation types for combining selected facets within the same group and across different groups or references in a query. Overrides built-in defaults (OR within group, AND across groups) unless per-group constraints are specified, which always take precedence. Applies only to the current query.
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
	@SourceHash("4eaa3b3ca7efe0957153d2244cfc3df2")
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
		@Nullable String referenceName,
		@Nullable HierarchyRequireConstraint... requirement
	) {
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
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable HierarchyRequireConstraint... requirement
	) {
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
		@Nullable String referenceName,
		@Nullable EmptyHierarchicalEntityBehaviour emptyHierarchicalEntityBehaviour,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		return referenceName == null || ArrayUtils.isEmptyOrItsValuesNull(requirement) ?
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
		@Nullable String referenceName,
		@Nullable EmptyHierarchicalEntityBehaviour emptyHierarchicalEntityBehaviour,
		@Nullable OrderBy orderBy,
		@Nullable HierarchyRequireConstraint... requirement
	) {
		return referenceName == null || ArrayUtils.isEmptyOrItsValuesNull(requirement) ?
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
	 * Limits hierarchy traversal to nodes within the specified number of edge hops from the pivot node, regardless of traversal direction. Use this to fetch relatives at a distance relative to the pivot's position (e.g., direct children with distance 1). Must be used as the sole inner constraint of `HierarchyStopAt`. Distance must be greater than zero.
	 *
	 * ```evitaql
	 * stopAt(distance(1))
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#distance)
	 *
	 * @see io.evitadb.api.query.require.HierarchyDistance
	 */
	@SourceHash("73e82857c4cea06ef70cdf777618f9f1")
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
		if (base == null) {
			return null;
		} else {
			return type == null ? new HierarchyStatistics(base) : new HierarchyStatistics(base, type);
		}
	}

	/**
	 * Loads full entity bodies from storage, enabling access to more than just primary keys in query results. Accepts sub-requirements to fetch only specific data containers (attributes, prices, references, etc.), minimizing unnecessary I/O. Empty `entityFetch()` loads only the entity's core metadata. Sub-requirements are merged if `entityFetch` appears multiple times in a query.
	 *
	 * ```evitaql
	 * require(
	 *     entityFetch(
	 *         attributeContent("code", "name")
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#entity-fetch)
	 *
	 * @see io.evitadb.api.query.require.EntityFetch
	 */
	@SourceHash("12e7fd395e966a1c144f3dc9c9699417")
	@Nonnull
	static EntityFetch entityFetch(@Nullable EntityContentRequire... requirements) {
		if (requirements == null) {
			return new EntityFetch();
		}
		return new EntityFetch(requirements);
	}

	/**
	 * Retrieves the bodies of group entities associated with references, allowing you to fetch group-level data (such as names or attributes) for referenced entities. Only meaningful within `referenceContent`. Accepts any combination of entity content sub-requirements to specify which data to load for each group entity.
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
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#entity-group-fetch)
	 *
	 * @see io.evitadb.api.query.require.EntityGroupFetch
	 */
	@SourceHash("8af199eff05f7b365771395a75288bdc")
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
		if (locale == null) {
			return new DataInLocales();
		}
		return new DataInLocales(locale);
	}

	/**
	 * Fetches all references of all types for the entity, returning the primary keys of referenced entities (but not reference-level attributes). Place inside `entityFetch` to include references in results; use `referenceContentAllWithAttributes()` to also fetch reference attributes.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("4d89e70b2df2dfa17a2f5c16643fda40")
	@Nonnull
	static ReferenceContent referenceContentAll() {
		return new ReferenceContent();
	}

	/**
	 * Fetches all references of all types from the entity, including both the primary keys of referenced entities and all attributes stored on each reference record. This is the broadest form, ensuring complete reference and metadata retrieval in a single call.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("b3696d99e817e6ac45f0d23f96edd5d0")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes() {
		return new ReferenceContent(AttributeContent.ALL_ATTRIBUTES);
	}

	/**
	 * Fetches all references of all types from the entity, including both the primary keys of referenced entities and all attributes stored on each reference record, as specified by the provided `attributeContent`. Use inside `entityFetch` to retrieve complete reference metadata.
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
	@SourceHash("af88ed7a2ff20f8f84fcccf16b6ec610")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(@Nullable AttributeContent attributeContent) {
		return new ReferenceContent(
			ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES)
		);
	}

	/**
	 * Fetches all references of all types from the entity, including all reference-level attributes as specified by `attributeContent`, and supports chunking large reference sets with a `chunk` constraint. Use inside `entityFetch` to retrieve complete reference info for every association.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
	 *         attributeContent("sortOrder"),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("efd692750b2b9b953640935b45e78201")
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
	 * Fetches the specified reference group(s) from an entity, returning only the primary keys of referenced entities (not reference attributes or full bodies). Place inside `entityFetch` to include references in results; without it, references are omitted.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent("brand"),
	 *     referenceContent("categories")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("601b7ea4e75d2df92a12a1a99b27d0e3")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String referenceName) {
		if (referenceName == null) {
			return new ReferenceContent();
		}
		return new ReferenceContent(referenceName);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and selected reference-level attributes (metadata on the relationship itself). Place inside `entityFetch` to retrieve associations and their attributes.
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
	@SourceHash("d6e056c68193eeb3f381fcb7c494dc52")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable String... attributeNames) {
		if (referenceName == null) {
			return null;
		} else {
			return new ReferenceContent(
				referenceName, null, null,
				attributeContent(attributeNames), null, null, null
			);
		}
	}

	/**
	 * Fetches the specified named reference group from an entity, including both the primary keys of referenced entities and all attributes stored on the reference record (metadata about the relationship). Place inside `entityFetch` to retrieve full reference details.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes("categories", attributeContent("sortOrder"))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("84edbe6adac49a49723f88a734fc0438")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName) {
		return referenceName == null ? null : new ReferenceContent(referenceName, AttributeContent.ALL_ATTRIBUTES);
	}


	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record itself. Use inside `entityFetch` when you need metadata about the association, not just the link.
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "parameterValues",
	 *         attributeContent("sortOrder", "isPrimary")
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("a7a0c3b4079ccfbe2a5e50ff016b78b7")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable AttributeContent attributeContent) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, null, null,
			ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
			null, null, null
		);
	}

	/**
	 * Fetches one or more named reference groups from an entity, returning the primary keys of referenced entities and optionally their full bodies. Place inside `entityFetch` to include references in results; without it, references are omitted.
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
	@SourceHash("1857a47c4744ac841592345690f99550")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String... referenceName) {
		if (referenceName == null) {
			return new ReferenceContent();
		}
		return new ReferenceContent(referenceName);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, if requested, the full bodies of referenced entities via a nested `entityFetch`. Place inside `entityFetch` to include references in results; without it, references are omitted.
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
	@SourceHash("2335a6122144bb225ef6bf6a98be8e6c")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable EntityFetch entityRequirement) {
		if (referenceName == null && entityRequirement == null) {
			return new ReferenceContent();
		}
		if (referenceName == null) {
			return new ReferenceContent(entityRequirement, null);
		}
		return new ReferenceContent(
			referenceName, null, null, entityRequirement, null, null
		);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all attributes stored on the reference record itself. Optionally, fetches the full body of each referenced entity using the provided `entityFetch` constraint. Use this to retrieve reference metadata alongside referenced entity data in a single query.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "parameterValues",
	 *         entityFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("7abd573cf5cbaa2d0a5d9d1b03d80249")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable EntityFetch entityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, null, null,
			AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, null
		);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the reference's primary keys and its reference-level attributes, and optionally fetches the full bodies of the referenced entities. Place inside `entityFetch` to retrieve associated data and metadata for relationships such as product → brand. Use nested constraints to filter, order, or paginate references as needed.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "brand",
	 *         attributeContent("isPrimary"),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("1351f6db100ce3e6f3ec22bbeb37aa3e")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, null, null,
			ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
			entityRequirement, null, null
		);
	}

	/**
	 * Fetches the specified reference group from an entity, returning the primary keys of referenced entities and, if requested, the bodies of their group entities using a nested `entityGroupFetch`. Place inside `entityFetch` to include references in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("017023b97be510d15dd1d3e77eff226f")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable EntityGroupFetch groupEntityRequirement) {
		if (referenceName == null && groupEntityRequirement == null) {
			return new ReferenceContent();
		}
		if (referenceName == null) {
			return new ReferenceContent(null, groupEntityRequirement);
		}
		return new ReferenceContent(referenceName, null, null, null, groupEntityRequirement, null);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all attributes stored on the reference record. Optionally, fetches the full bodies of associated reference group entities as defined by the nested `EntityGroupFetch`.
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("837cce151783b4b915a2c570399bb21f")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, null, null,
			AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, null
		);
	}

	/**
	 * Fetches references of the specified type, including both the referenced entity primary keys and all attributes stored on the reference record itself. Optionally, fetches the referenced group entity bodies with the given requirements. Use inside `entityFetch` to retrieve associations (e.g., product → categories) with their metadata.
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
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("cdbe065da81e7511c52a3c777a7f2d4b")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(@Nullable String referenceName, @Nullable AttributeContent attributeContent, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, null, null,
			ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
			null, groupEntityRequirement, null
		);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, if requested, the full bodies of referenced entities and their groups. Place inside `entityFetch` to include references in results. Does not fetch reference attributes—use `referenceContentWithAttributes` for that. Supports nested fetches for referenced entities and groups.
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
	@SourceHash("9cb0ce1be5f703434a1f334d98f9d1d0")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		if (referenceName == null) {
			return new ReferenceContent(entityRequirement, groupEntityRequirement);
		}
		return new ReferenceContent(referenceName, null, null, entityRequirement, groupEntityRequirement, null);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all attributes stored on the reference record itself. Optionally, loads full bodies of referenced entities and their groups via nested `entityFetch` and `entityGroupFetch` constraints. Use this when you need both reference metadata and related entity data in a single query.
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
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
	@SourceHash("82d800511a16915e3f2e5fb04a33d293")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ? null : new ReferenceContent(
			referenceName, null, null,
			AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement, null
		);
	}

	/**
	 * Fetches the specified reference group from an entity, returning both the referenced entities' primary keys and the attributes stored on the reference record itself. Optionally, fetches full bodies of referenced and group entities. Place inside `entityFetch` to include references in results.
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
	@SourceHash("d42c46351a3c1601e77c7e52641a50f5")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, null
			);
	}


	/**
	 * Fetches references of the specified types from an entity, returning their primary keys and, if provided, the full bodies of the referenced entities via nested `entityFetch`. Place inside `entityFetch` to include references in results; without it, references are omitted.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         ["brand", "categories"],
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("94850278aa03053b2965b81f1319e8b1")
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
	 * Fetches references of the specified types from an entity, including the primary keys of referenced entities and, if requested, the bodies of their group entities via a nested `entityGroupFetch`. Place inside `entityFetch` to retrieve associations like product → categories or brands. Reference attributes are not included—use the `WithAttributes` variant for those.
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         ["categories", "brand"],
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("58d182a75b4954004ed42474b8f78f16")
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
	 * Fetches references of the specified types from an entity, returning their primary keys and, if requested, the full bodies of referenced and group entities. Place inside `entityFetch` to include references in results; supports nested `entityFetch`/`entityGroupFetch` for deep loading.
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         ["parameterValues"],
	 *         entityFetch(attributeContent("code")),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("9f880d63eb796c1b4c2c999e29774563")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String[] referencedEntityTypes, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		if (referencedEntityTypes != null) {
			return new ReferenceContent(referencedEntityTypes, entityRequirement, groupEntityRequirement);
		} else {
			return new ReferenceContent(entityRequirement, groupEntityRequirement);
		}
	}

	/**
	 * Fetches references of the specified type from an entity, including only those that match the provided filter on reference-level attributes. Returns referenced entity primary keys and, if nested, their bodies. Place inside `entityFetch` to include references in results.
	 *
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
	@SourceHash("d3bcafb0738726e6c006fe5ef73270ec")
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable FilterBy filterBy) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, null, null, null, null);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record. Supports filtering references by their own attributes using a nested `filterBy` constraint.
	 *
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
	@SourceHash("3f2fa0195d22f880427fe8d5a5df758d")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, null,
				AttributeContent.ALL_ATTRIBUTES, null, null, null
			);
	}

	/**
	 * Fetches references of the given type from an entity, including both the reference's primary keys and its reference-level attributes, with optional filtering on reference attributes. Useful for retrieving metadata about relationships, not just linked entities.
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
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("b22e4ce9779079137f9e1b10de0e7719")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable AttributeContent attributeContent
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, full referenced entity bodies. Supports filtering references by reference-level attributes (via `filterBy`) and fetching referenced entities with custom requirements. Place inside `entityFetch` to include references in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         entityFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("8af3a6ceec3ef151533913771796512a")
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable EntityFetch entityRequirement) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, null, entityRequirement, null, null);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity bodies (as defined by the nested `EntityFetch`) and all attributes stored on the reference record itself. Supports filtering references by reference-level attributes using `filterBy`.
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
	@SourceHash("e7cc1b5a6a3781389a09689457b788d7")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityFetch entityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, null,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including reference-level attributes, with optional filtering on reference records, and loads the full bodies of referenced entities as defined. Use inside `entityFetch` to retrieve associations with metadata.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "parameterValues",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         attributeContent("code"),
	 *         entityFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("577d127e0c8b5c8c95306d360dc6986d")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, if requested, group entity bodies. Allows filtering references by attributes on the reference record. Place inside `entityFetch` to include references in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("89412160a423613736aabee827d99d8b")
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, null, null, groupEntityRequirement, null);
	}

	/**
	 * Fetches references of the specified type, including both the referenced entity primary keys and all attributes stored on the reference record. Supports filtering the reference set and fetching group entity bodies. Place inside `entityFetch` to include references in results.
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
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("4c5f4392bf920ba4c314b7251f8830d3")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, null,
				AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type, including both the referenced entity primary keys and all attributes stored on the reference record itself. Supports filtering references, fetching group entity bodies, and is placed inside `entityFetch`.
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
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("b58d42e4b036ce8266e5384c3a7db8e6")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, optionally filtering which references are included, and allows fetching full bodies of referenced entities and their groups. Place inside `entityFetch` to retrieve associated references; supports nested filtering, entity/group fetches, and custom ordering for fine-grained control.
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
	@SourceHash("0a862dcb0593900612f3e0ca06cbf160")
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, null, entityRequirement, groupEntityRequirement, null);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entities' primary keys and all reference-level attributes. Supports filtering references, fetching referenced entity/group bodies, and is placed inside `entityFetch`.
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
	@SourceHash("5fcc8ffc076ac25a33a75f2f7872d1aa")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, null,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches a named reference group from an entity, returning both the primary keys of referenced entities and all reference-level attributes. Supports filtering references, fetching referenced entity/group bodies, and customizing the result via nested constraints.
	 *
	 * ```evitaql
	 * referenceContentWithAttributes(
	 *     "parameterValues",
	 *     filterBy(attributeEquals("isPrimary", true)),
	 *     attributeContent("sortOrder"),
	 *     entityFetch(attributeContent("code")),
	 *     entityGroupFetch(attributeContent("code"))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("b8cfc4ba7baf12575b8b924e09f4e3e0")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, ordering them using the provided `orderBy` constraint. Returns primary keys of referenced entities (and optionally their bodies if nested fetches are used). Place inside `entityFetch` to include references in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent("categories", orderBy(attributeNatural("order", ASC)))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("0c2e63cf2a5e438c9a5ede98d34e468a")
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable OrderBy orderBy) {
		return referenceName == null ? null : new ReferenceContent(referenceName, null, orderBy, null, null, null);
	}

	/**
	 * Fetches references of the specified type, including both the referenced entity primary keys and all reference-level attributes, and orders the references using the provided `orderBy` constraint. Use within `entityFetch` to retrieve enriched reference info with custom sorting.
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
	@SourceHash("d83cc13283a1d8a532c4603cf12d4c77")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable OrderBy orderBy
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, null, orderBy,
				AttributeContent.ALL_ATTRIBUTES, null, null, null
			);
	}

	/**
	 * Fetches references of the specified type, including their reference-level attributes, and orders them using the provided `orderBy` constraint. Use inside `entityFetch` to retrieve both the primary keys and metadata about the relationship itself.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         attributeContent("isPrimary")
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("7d5f273f7e07a3cdbe4a546607bfdb3a")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, null, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, null, null
			);
	}

	/**
	 * Fetches a named reference group from an entity, returning the referenced entity primary keys ordered by a custom rule, and optionally loads the full bodies of referenced entities as specified. Place inside `entityFetch` to include references in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         orderBy(attributeNatural("order", DESC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("d2eb8c6abd6e21ef131833e3bd74f832")
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement) {
		return referenceName == null ? null : new ReferenceContent(referenceName, null, orderBy, entityRequirement, null, null);
	}

	/**
	 * Fetches references of the specified type, including both their primary keys and all reference-level attributes, with optional ordering and nested fetching of referenced entity bodies. Use inside `entityFetch` to retrieve enriched reference data with custom ordering.
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
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("906e52f8789413988fac9ce451789942")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, null, orderBy,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type, including their reference-level attributes, ordered as defined, and optionally loads full bodies of referenced entities. Useful for retrieving both the association metadata and related entity details in one query.
	 *
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
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("6480b57adf739d650dbce53a01ee912b")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, null, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, ordering them with the provided `orderBy` constraint and optionally fetching bodies of their group entities. Place inside `entityFetch` to include references; by default, only primary keys are returned.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         orderBy(attributeNatural("order", ASC)),
	 *         entityGroupFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("0821e240f6e1e1c6fcd9a342d4387102")
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable OrderBy orderBy, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(referenceName, null, orderBy, null, groupEntityRequirement, null);
	}

	/**
	 * Fetches references of the given name, including their attributes and optionally the bodies of referenced group entities, ordered as specified. Use inside `entityFetch` to retrieve both reference metadata and group entity details. Supports advanced ordering and attribute selection.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("3acc5eb9ba3d060a7c80d21b634d2579")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, null, orderBy,
				AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type, including their reference-level attributes, ordered as defined. Optionally fetches group entity bodies and their attributes. Use for scenarios needing both reference metadata and associated group entity details.
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
	@SourceHash("56ad4250ee96b1cbea3eb53333b4d969")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, null, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, ordering them per the given `orderBy` constraint and optionally loading full bodies of referenced entities and their groups. Place inside `entityFetch` to retrieve associations like product→brand or product→categories, with support for nested fetches and ordering by reference or referenced entity attributes.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "parameterValues",
	 *         orderBy(entityProperty(attributeNatural("name", ASC))),
	 *         entityFetch(attributeContent("name")),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("265df60def6bf390205cf1b75d6af534")
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(referenceName, null, orderBy, entityRequirement, groupEntityRequirement, null);
	}

	/**
	 * Fetches references of the specified type, including both the referenced entity/group bodies and all reference-level attributes, with optional ordering. Place inside `entityFetch` to retrieve full reference details and sort references as needed.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         entityFetch(attributeContent("name")),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("aff5270f6f3a39c54883d61ce763f6a5")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, null, orderBy,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record itself. Supports ordering references, fetching referenced entity/group bodies, and retrieving reference-level attributes in one call.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "parameterValues",
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         attributeContent("isPrimary"),
	 *         entityFetch(attributeContent("code")),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("b85f97cdedfb5aaead91382ddf933a89")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, null, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and optionally filtering and ordering the reference set. Filtering and ordering apply to reference-level attributes; use nested constraints for referenced entity fields. Place inside `entityFetch` to include references in results.
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
	@SourceHash("e736fd3fe28a39b955c8a47cee737703")
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, orderBy, null, null, null);
	}

	/**
	 * Fetches references of the specified type, including both their primary keys and all reference-level attributes, with optional filtering and ordering applied to the reference records themselves. Place inside `entityFetch` to retrieve enriched association data.
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
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("f9aa22a99703b160128df33747f4f8d4")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, orderBy,
				AttributeContent.ALL_ATTRIBUTES, null, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all attributes stored on the reference record itself. Supports filtering and ordering of references based on reference attributes. Place inside `entityFetch` to retrieve reference data along with entity results.
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
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("2f8b0a47ba2b7b077711023866217372")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, optionally filtering and ordering them by reference attributes, and fetching the full bodies of referenced entities using nested constraints. Use for fine-grained control over which references and referenced entities are returned.
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
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("d21afe52df336169d0fa0e6cb8df1e66")
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, orderBy, entityRequirement, null, null);
	}

	/**
	 * Fetches references of the specified type, including their reference-level attributes, with optional filtering, ordering, and nested entity body fetching. Use this to retrieve both the association metadata and the referenced entities in a single query.
	 *
	 * ```evitaql
	 * referenceContentWithAttributes(
	 *     "categories",
	 *     filterBy(attributeEquals("isPrimary", true)),
	 *     orderBy(attributeNatural("sortOrder", ASC)),
	 *     entityFetch(attributeContent("name"))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("c6b637df05599f790c64f79021f02b79")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, orderBy,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type, including both the referenced entity primary keys and all attributes stored on the reference record itself. Supports filtering and ordering references by reference-level attributes, and can fetch full bodies of referenced entities. Use for advanced scenarios needing both reference metadata and related entity data.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "parameterValues",
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
	@SourceHash("2bd78ef297d34a1513c53dff41aaa91b")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, null, null
			);
	}

	/**
	 * Fetches a named reference group from an entity, returning the referenced entities' primary keys and, if requested, their group entity bodies. Supports filtering and ordering of references—filters apply to reference attributes unless wrapped in `entityHaving`. Place inside `entityFetch`.
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "parameterValues",
	 *         filterBy(attributeEquals("isActive", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("e954453e072aacd375b74671d603ec44")
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, orderBy, null, groupEntityRequirement, null);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record. Supports filtering and ordering references (by reference attributes), and can fetch group entity bodies as well.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("2fa17a7f561b1b1edf31b9491516d755")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, orderBy,
				AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes. Supports filtering and ordering on reference attributes, fetching group entity bodies, and customizing attribute selection for each reference.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "parameterValues",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         attributeContent("code", "isPrimary"),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("988e20452f82814153ef240340a8bf5c")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, optionally filtering and ordering them, and can load full bodies of referenced entities and their groups. Reference-level filters/orderings apply to the relationship; use nested `entityFetch`/`entityGroupFetch` to fetch referenced data. By default, returns reference primary keys unless attributes are explicitly requested.
	 *
	 * ```evitaql
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(attributeEquals("isPrimary", true)),
	 *     orderBy(attributeNatural("sortOrder", ASC)),
	 *     entityFetch(attributeContent("code")),
	 *     entityGroupFetch(attributeContent("code"))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("7499b83f1a5ba8464274e488839707d2")
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, orderBy, entityRequirement, groupEntityRequirement, null);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record. Supports filtering and ordering of references, and can fetch full bodies of referenced entities and their groups. Useful for accessing metadata about relationships (e.g., "sortOrder") alongside referenced entities.
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
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("68b232e8d91425242fd891561ca2817f")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, orderBy,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type, including their reference-level attributes, with optional filtering, ordering, and nested fetching of referenced entity and group bodies. Use this to retrieve both reference metadata and related entity details in one query.
	 *
	 * ```evitaql
	 * referenceContentWithAttributes(
	 *     "categories",
	 *     filterBy(attributeEquals("isPrimary", true)),
	 *     orderBy(attributeNatural("sortOrder", ASC)),
	 *     attributeContent("isPrimary", "sortOrder"),
	 *     entityFetch(attributeContent("name")),
	 *     entityGroupFetch(attributeContent("code"))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("deafcbb4acd01b2a02a7e6d946beec99")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches all references of all types for an entity, returning the primary keys of referenced entities and, if specified, their full bodies via a nested `entityFetch`. Reference attributes are not included—use `referenceContentAllWithAttributes` for those.
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("40a9c201a77dfdb52a3ebbb399baa8a5")
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(entityRequirement, null);
	}

	/**
	 * Fetches all references of all types from the entity, including both the primary keys of referenced entities and all attributes stored on each reference record. Optionally, fetches the full bodies of referenced entities as specified by the nested `EntityFetch` constraint.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
	 *         entityFetch(attributeContent("code", "name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("ace2c194cd92cfc943053ff6359b8519")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(@Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(
			AttributeContent.ALL_ATTRIBUTES,
			entityRequirement,
			null
		);
	}

	/**
	 * Fetches all references of all types from the entity, including both the primary keys and all reference-level attributes, and loads the full bodies of referenced entities according to the specified `entityFetch` constraint. Use inside `entityFetch` to retrieve comprehensive reference data.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
	 *         attributeContent("sortOrder"),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("0ec05df6ce093c6795fee8bcd54c445f")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(@Nullable AttributeContent attributeContent, @Nullable EntityFetch entityRequirement) {
		return new ReferenceContent(
			ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
			entityRequirement,
			null
		);
	}

	/**
	 * Fetches all references of all types for the entity, including the primary keys of referenced entities and, optionally, the full bodies of their group entities as specified by the nested `EntityGroupFetch` constraint. Reference attributes are not included; use `referenceContentAllWithAttributes()` for that. Must be used within `entityFetch`.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("852862db49fa147c9b54f58fe98e6452")
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(null, groupEntityRequirement);
	}

	/**
	 * Fetches all references of all types from the entity, including both the primary keys of referenced entities and all attributes stored on each reference record, and allows specifying how to fetch the bodies of referenced group entities via `groupEntityRequirement`.
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("f7d9044c998686f067e721628e622a2a")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(@Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(
			AttributeContent.ALL_ATTRIBUTES,
			null,
			groupEntityRequirement
		);
	}

	/**
	 * Fetches all references of all types for the entity, including all reference-level attributes as specified, and optionally loads full bodies of referenced group entities. Use inside `entityFetch` to retrieve comprehensive reference and group data for each entity.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
	 *         attributeContent("isPrimary", "sortOrder"),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("f799172ceb1736ea34a316590ee413c5")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(@Nullable AttributeContent attributeContent, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(
			ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
			null,
			groupEntityRequirement
		);
	}

	/**
	 * Fetches all references of all types for an entity, returning the primary keys of referenced entities and, if specified, their full bodies and group entities using nested `entityFetch` and `entityGroupFetch` constraints. Reference attributes are not included; use `referenceContentAllWithAttributes` for those.
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("9c236b5c950283ac5089a818c01064da")
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement) {
		return new ReferenceContent(entityRequirement, groupEntityRequirement);
	}

	/**
	 * Fetches all references of all types for an entity, returning their primary keys and, if specified, paginates or chunks the reference set using the provided chunking constraint. Reference attributes are not included—use `referenceContentAllWithAttributes()` for those.
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("34eb539fe7cd260d77f49b34036bb20d")
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable ChunkingRequireConstraint chunk) {
		return new ReferenceContent(
			(EntityFetch) null,
			null,
			chunk
		);
	}

	/**
	 * Fetches all reference groups of all types from the entity, including both the primary keys of referenced entities and all attributes stored on the reference records. Supports chunking (pagination) of large reference sets via the provided chunking constraint.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("3688e788796d1eeb6aa98392d1b823c7")
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
	 * Fetches all references of all types from the entity, including both the primary keys of referenced entities and all attributes stored on the reference records. Optionally loads full bodies of referenced entities and their groups via nested `entityFetch` and `entityGroupFetch` constraints.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
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
	@SourceHash("809148d60070521ed41742fbc51fe673")
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
	 * Fetches all references of all types from the entity, including their reference-level attributes and, optionally, the full bodies of referenced entities and their groups as specified. Use inside `entityFetch` to retrieve comprehensive reference data in one call.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
	 *         attributeContent("sortOrder"),
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
	@SourceHash("7fefd2c3e7744b4c5417af973074fa21")
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
	 * Fetches all references of all types from the entity, returning the primary keys of referenced entities (and optionally their bodies), with control over whether to include only references to existing entities or all references via `ManagedReferencesBehaviour`. Reference-level attributes are not included; use `referenceContentAllWithAttributes()` to fetch them.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("b3d9a6a1ac45460ec03dda95a61160b8")
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable ManagedReferencesBehaviour managedReferencesBehaviour) {
		return new ReferenceContent(managedReferencesBehaviour);
	}

	/**
	 * Fetches all references of all types from the entity, including both the referenced entity primary keys and all reference-level attributes, using the specified managed references behaviour to control whether missing target entities are included.
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("c702d0aadcdc516fd0dd09bce346767d")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(@Nullable ManagedReferencesBehaviour managedReferencesBehaviour) {
		return new ReferenceContent(managedReferencesBehaviour, AttributeContent.ALL_ATTRIBUTES);
	}

	/**
	 * Fetches all references of all types from the entity, including all reference-level attributes as specified, and controls which references are returned based on the managedReferencesBehaviour (e.g., only those pointing to existing entities). Use inside `entityFetch` to retrieve complete reference info.
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
	@SourceHash("f2e9f639e9e3db326e30a296e6214973")
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
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, referenced entity bodies. The `managedReferencesBehaviour` controls whether to include only references to existing entities or all references, regardless of target existence.
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
	@SourceHash("7ad8184cf2e7e053e314d2145eb603c1")
	@Nonnull
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName
	) {
		if (referenceName == null) {
			return new ReferenceContent(managedReferencesBehaviour);
		}
		return new ReferenceContent(managedReferencesBehaviour, referenceName);
	}

	/**
	 * Fetches references of the specified type from an entity, returning both the primary keys of referenced entities and the selected reference-level attributes. Optionally restricts to references with existing targets via `managedReferencesBehaviour`. Place inside `entityFetch`.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", "isPrimary", "sortOrder")
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("2709cb5d69cb5a104cc9053e05873eb7")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable String... attributeNames
	) {
		if (referenceName == null) {
			return null;
		} else {
			return new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				attributeContent(attributeNames), null, null, null
			);
		}
	}

	/**
	 * Fetches references of the specified type, returning both the referenced entity primary keys and all attributes stored on the reference record. Use `managedReferencesBehaviour` to control whether only existing references are included. Place inside `entityFetch`.
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
	@SourceHash("87326b3c69584c0f5bbf5376847caa76")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				AttributeContent.ALL_ATTRIBUTES, null, null, null
			);
	}


	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and the attributes stored on the reference record itself. Allows control over whether to include only references pointing to existing entities or all references, and supports fetching reference attributes via nested `attributeContent`. Use inside `entityFetch` for fine-grained reference and attribute retrieval.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", attributeContent("isPrimary"))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("78a20f4a0dbf25b4ca7055b1a85466ed")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable AttributeContent attributeContent
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, null, null
			);
	}

	/**
	 * Fetches one or more named references from an entity, returning the primary keys of referenced entities and, optionally, their full bodies. The `managedReferencesBehaviour` parameter controls whether to include references to non-existent entities. Must be used inside `entityFetch`.
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
	@SourceHash("5218cb28a5c68deb5c8e2fed55f2b21a")
	@Nonnull
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String... referenceName
	) {
		if (referenceName == null) {
			return new ReferenceContent(managedReferencesBehaviour);
		}
		return new ReferenceContent(managedReferencesBehaviour, referenceName);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, the full bodies of referenced entities. Use `managedReferencesBehaviour` to control whether only existing referenced entities are returned. Place inside `entityFetch`.
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", entityFetch(attributeContent("name")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("b9e5a17b16f002c787430f828644534f")
	@Nonnull
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable EntityFetch entityRequirement
	) {
		if (referenceName == null && entityRequirement == null) {
			return new ReferenceContent(managedReferencesBehaviour);
		}
		if (referenceName == null) {
			return new ReferenceContent(managedReferencesBehaviour, entityRequirement, null);
		}
		return new ReferenceContent(
			managedReferencesBehaviour, referenceName, null, null, entityRequirement, null, null
		);
	}

	/**
	 * Fetches the specified reference group from an entity, including both reference-level attributes and the full body of referenced entities as defined by the nested `EntityFetch`. Use `managedReferencesBehaviour` to control whether only existing referenced entities are returned. Place inside `entityFetch` to retrieve associations (e.g., product → brand) with their metadata and referenced entity data.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", entityFetch(attributeContent("name")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("afee7766531b054f9da2dd200ef7ed53")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable EntityFetch entityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning both the referenced entity's primary keys and all attributes stored on the reference record. Optionally loads full referenced entity bodies and supports filtering non-existent targets via `ManagedReferencesBehaviour`. Use for cases where reference-level metadata is needed alongside referenced entities.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", attributeContent("isPrimary"), entityFetch(attributeContent("code")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("427d2c98aeb310b3c591e5e701b4fa1a")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, controlling whether only existing referenced entities are included (`EXISTING`) or all references regardless of target existence (`ANY`). Optionally fetches group entity bodies for each reference.
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", entityGroupFetch(attributeContent("code")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("a095f6ac635859e7556125d4f01a4580")
	@Nonnull
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		if (referenceName == null && groupEntityRequirement == null) {
			return new ReferenceContent(managedReferencesBehaviour);
		}
		if (referenceName == null) {
			return new ReferenceContent(managedReferencesBehaviour, null, groupEntityRequirement);
		}
		return new ReferenceContent(
			managedReferencesBehaviour, referenceName, null, null, null, groupEntityRequirement, null
		);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes, with optional group entity body loading. Use `managedReferencesBehaviour` to control whether only existing references are returned. Aliasing, filtering, ordering, and pagination are supported for advanced use cases.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "categories", entityGroupFetch(attributeContent("code")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("e7e90ceafe772eb6f6eb5125327bd278")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored directly on the reference record. You can control whether to include only references to existing entities, and optionally fetch group entity bodies or filter/order references.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", attributeContent("isPrimary"), entityGroupFetch(attributeContent("code")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("962f036d6c1b0fd5a8c79b31a93bbc48")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and optionally the full bodies of referenced entities and/or their groups. The `managedReferencesBehaviour` controls whether only existing references are returned. Place inside `entityFetch` to include references in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", entityFetch(attributeContent("name")), null)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("ad9e842984c95e0589a1d8e139789532")
	@Nonnull
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		if (referenceName == null) {
			return new ReferenceContent(managedReferencesBehaviour, entityRequirement, groupEntityRequirement);
		}
		return new ReferenceContent(
			managedReferencesBehaviour, referenceName, null, null,
			entityRequirement, groupEntityRequirement, null
		);
	}

	/**
	 * Fetches references of the specified type, including both the referenced entity primary keys and all reference-level attributes, with optional full bodies of referenced and group entities. Controls whether to include only references to existing entities or all references, and supports nested fetches for rich reference data retrieval.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", entityFetch(attributeContent("name")), null)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("872756bb7b0387fa0f1de85c0918326c")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes. Optionally loads full bodies of referenced and group entities, and controls inclusion of references to non-existent entities via `managedReferencesBehaviour`. Supports nested filtering, ordering, and pagination for fine-grained control.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", attributeContent("isPrimary"), entityFetch(attributeContent("code")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("e0a47bd9334eabd50f7bb8c5a66737f2")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, null
			);
	}


	/**
	 * Fetches references of the specified types from an entity, returning their primary keys and, optionally, the full bodies of referenced entities per the nested `entityFetch`. The `managedReferencesBehaviour` controls whether to include only references to existing entities or all references. Place inside `entityFetch` to retrieve associations like product→brand or product→categories; without this, references are omitted from results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", entityFetch(attributeContent("name")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("24c6b0fa5c3ae1c7eaacda5157653a52")
	@Nonnull
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String[] referencedEntityTypes,
		@Nullable EntityFetch entityRequirement
	) {
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
	 * Fetches references of the specified types from an entity, returning their primary keys and, optionally, group entity bodies per the nested `EntityGroupFetch`. The `managedReferencesBehaviour` controls whether only references to existing entities are included. Place inside `entityFetch`.
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", entityGroupFetch(attributeContent("code")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("ac8b3dd394fc3447769b8030718911c9")
	@Nonnull
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String[] referencedEntityTypes,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
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
	 * Fetches one or more named references from an entity, returning their primary keys and, optionally, full referenced entity and group bodies. Controls inclusion of references to non-existent entities via `ManagedReferencesBehaviour`. Place inside `entityFetch` to retrieve associations like product→brand or product→categories, with optional nested fetches for referenced entities or groups.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", entityFetch(attributeContent("name")), null)
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("75b5ed55e669f0f2faa2a2643b094aa5")
	@Nonnull
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String[] referencedEntityTypes,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		if (referencedEntityTypes != null) {
			return new ReferenceContent(managedReferencesBehaviour, referencedEntityTypes, entityRequirement, groupEntityRequirement);
		} else {
			return new ReferenceContent(managedReferencesBehaviour, entityRequirement, groupEntityRequirement);
		}
	}

	/**
	 * Fetches references of the specified type from an entity, applying the given managed references behaviour (e.g., only existing targets) and filters on reference attributes. Use inside `entityFetch` to control which references are returned and how they're filtered.
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
	@SourceHash("fb6dd081e1ba9c609bd322fdaabcf820")
	@Nullable
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable FilterBy filterBy
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, null, (EntityFetch) null, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record. Allows filtering references by their attributes and controlling whether only existing referenced entities are included via `managedReferencesBehaviour`.
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
	@SourceHash("a99eb4f5d4f24e40e02bfffe3e7f493b")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable FilterBy filterBy
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, null,
				AttributeContent.ALL_ATTRIBUTES, null, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record. Allows filtering references, controlling inclusion of only existing targets, and specifying which reference attributes to return.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", filterBy(attributeEquals("isPrimary", true)), attributeContent("sortOrder"))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("c1b94a18f0b1a1f8270b1ac6d217f33b")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable AttributeContent attributeContent
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, the full bodies of referenced entities. Allows filtering references (by reference attributes), customizing inclusion of only existing targets, and nesting entity fetches for referenced entities. Use for fine-grained control over which references and referenced entity data are returned.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", filterBy(attributeEquals("isPrimary", true)), entityFetch(attributeContent("code")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("5398cf7b13a5812d4d4af9dd8ce013b1")
	@Nullable
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityFetch entityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, null, entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity bodies (as defined by `entityRequirement`) and all reference-level attributes. You can filter which references are returned using `filterBy`, and control whether to include only references to existing entities or all references via `managedReferencesBehaviour`.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", filterBy(attributeEquals("isPrimary", true)), entityFetch(attributeContent("name")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("95833cf2b0db6493b4a1a25d9659bce2")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityFetch entityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, null,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all attributes stored on the reference record. Allows filtering references, controlling inclusion of only existing targets, and fetching referenced entity bodies.
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
	@SourceHash("bdbf81bb0ca9d268f22a7aa349ca8d7c")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and optionally group entity bodies. Allows filtering references (by reference attributes), controlling inclusion of non-existent targets, and fetching group data. Place inside `entityFetch`.
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", filterBy(attributeEquals("isPrimary", true)), entityGroupFetch(attributeContent("code")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("df50cf70fa7e935db8312339caafa44b")
	@Nullable
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName,
				filterBy, null, null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record. Allows filtering references, controlling inclusion of missing targets via `managedReferencesBehaviour`, and fetching group entity bodies.
	 * ```evitaql
	 * referenceContentWithAttributes(
	 *     EXISTING,
	 *     "categories",
	 *     filterBy(attributeEquals("isPrimary", true)),
	 *     entityGroupFetch(attributeContent("code"))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("a02042b2e8471477797502140a248dbf")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, null,
				AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record itself. Supports filtering references, controlling inclusion of missing targets, and fetching group entity data.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         EXISTING, "brand",
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
	@SourceHash("56635073e83f999934b005df9b1c125e")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, full referenced entity and group bodies. Supports filtering, ordering, and pagination on reference records. Use `managedReferencesBehaviour` to control whether only existing referenced entities are included. Nested constraints allow fine-grained selection and retrieval of reference and referenced entity data.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", filterBy(attributeEquals("isPrimary", true)), entityFetch(attributeContent("name")), null)
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("5040b1ca919f7d808bf22009fff049e5")
	@Nullable
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, null,
				entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes, with optional filtering and nested fetches for referenced and group entities. Use `managedReferencesBehaviour` to control whether only existing referenced entities are included.
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", filterBy(...), entityFetch(...), entityGroupFetch(...))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("350ba1fae2a10e742c7365ba06c02c20")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, null,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record itself. Supports filtering, custom reference attribute selection, and nested fetches for referenced entity/group bodies. The `managedReferencesBehaviour` controls whether to include only existing referenced entities or all references.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         EXISTING,
	 *         "brand",
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
	@SourceHash("8ab6371c689f3ed172434b965a040716")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and optionally referenced entity bodies, applying the given managed references behavior (e.g., only existing targets) and custom ordering. Use inside `entityFetch` to include references in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", orderBy(attributeNatural("priority", DESC)))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("dcaa2b25ba8570bb193c792f4b57fbfa")
	@Nullable
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable OrderBy orderBy
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, orderBy, (EntityFetch) null, null, null
			);
	}

	/**
	 * Fetches references of the specified type, including both the referenced entity primary keys and all reference-level attributes, with optional ordering and control over whether to include only existing targets or all references. Useful for retrieving rich relationship metadata.
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
	@SourceHash("f17000c1317cea8e95a6b8e96866ee92")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable OrderBy orderBy
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, orderBy,
				AttributeContent.ALL_ATTRIBUTES, null, null, null
			);
	}

	/**
	 * Fetches references of the given type with the specified managed references behavior, returning both the reference primary keys and all reference-level attributes. Supports custom ordering and inclusion of referenced entity bodies. Use for fine-grained control over reference fetching.
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
	@SourceHash("bfea56c231840f5639ccff07141fd5d7")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, full referenced entity bodies. Controls whether to include only references to existing entities (`EXISTING`) or all (`ANY`), and allows ordering and nested entity fetches for referenced entities.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", orderBy(attributeNatural("name", ASC)), entityFetch(attributeContent("name")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("54f9d49f1868b165256ee52ccdb33a30")
	@Nullable
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, orderBy, entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type, including both the referenced entity's primary keys and all reference-level attributes, with optional ordering and nested entity body loading. The `managedReferencesBehaviour` controls whether only existing referenced entities are returned. Useful for retrieving associations with full metadata and referenced entity details in a single query.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "categories", orderBy(attributeNatural("sortOrder", ASC)), entityFetch(attributeContent("name")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("6b2db93557da183bc2f532be0b37d829")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, orderBy,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning both the referenced entity primary keys and all attributes stored on the reference record. Optionally filters out references to non-existent entities (via `managedReferencesBehaviour`), applies custom ordering, fetches reference attributes, and can load full bodies of referenced entities. Use inside `entityFetch` to retrieve rich association data and metadata in one step.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         EXISTING, "categories",
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
	@SourceHash("7e96ece6d49f75315f8a5d8c235aa6a1")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and optionally fetching group entity bodies, with results ordered by a custom `orderBy` constraint. Controls whether only existing target entities are included via `managedReferencesBehaviour`. Place inside `entityFetch` to retrieve reference data; without it, references are omitted.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "categories", orderBy(attributeNatural("order", ASC)), entityGroupFetch(attributeContent("name")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("cba1e5e6945cd0455ca90c9848d440e7")
	@Nullable
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, orderBy, null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes. Supports filtering out non-existent targets via `managedReferencesBehaviour`, custom ordering, and fetching group entity bodies.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "categories", orderBy(attributeNatural("sortOrder", ASC)), entityGroupFetch(attributeContent("code")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("837416c56c0706573f5153a800a303b1")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, orderBy,
				AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type, including both their primary keys and all reference-level attributes, with optional ordering and group entity fetching. Use `managedReferencesBehaviour` to control whether only existing referenced entities are returned. Supports advanced filtering, ordering, and fetching of group entity bodies.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         EXISTING, "categories",
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
	@SourceHash("d33b1fbbf4864132340eb90d9debf40d")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning referenced entity primary keys and optionally their full bodies or group entities. Allows filtering out references to non-existent entities via `managedReferencesBehaviour`, and supports custom ordering, nested entity/group fetches, and aliasing. Use inside `entityFetch` to include references in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", orderBy(attributeNatural("name", ASC)), entityFetch(attributeContent("name")), null)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("4e0ad559c3672a36696eec9f1960075a")
	@Nullable
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, orderBy,
				entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type with the given managed references behavior, returning both the referenced entity primary keys and all reference-level attributes. Optionally, fetches full bodies of referenced entities and their groups, and orders references as specified.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", orderBy(attributeNatural("sortOrder", ASC)), entityFetch(attributeContent("name")), entityGroupFetch(attributeContent("code")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("daad26329f6b7ceb4db048ab9d74406a")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, orderBy,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type, including both their primary keys and reference-level attributes, with optional filtering by managed references behaviour, ordering, and nested fetching of referenced entities or groups. Use for advanced reference retrieval with attribute access.
	 *
	 * ```evitaql
	 * referenceContentWithAttributes(
	 *     EXISTING, "categories",
	 *     orderBy(attributeNatural("sortOrder", ASC)),
	 *     attributeContent("isPrimary"),
	 *     entityFetch(attributeContent("name")),
	 *     entityGroupFetch(attributeContent("code"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("2093772b364b451c80d3471245ec363f")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, optionally filtering and ordering them, and controlling whether to include only references to existing entities or all references. Place inside `entityFetch` to retrieve reference primary keys and, if nested, referenced entity bodies. Filtering and ordering apply to reference-level attributes by default.
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
	@SourceHash("03470e4c53fa113544387d86386f039b")
	@Nullable
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, orderBy, (EntityFetch) null, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes, with optional filtering and ordering. Use `managedReferencesBehaviour` to control whether only existing referenced entities are returned.
	 *
	 * ```evitaql
	 * referenceContentWithAttributes(
	 *     EXISTING, "brand",
	 *     filterBy(attributeEquals("isPrimary", true)),
	 *     orderBy(attributeNatural("sortOrder", ASC))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("a58ed7c8a90459187e5529117c4cffe7")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, orderBy,
				AttributeContent.ALL_ATTRIBUTES, null, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including their primary keys, reference-level attributes, and optionally filters, orders, and limits the result set. You can control whether only existing referenced entities are included and fetch referenced entity bodies as needed.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         EXISTING, "brand",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         attributeContent("isPrimary", "sortOrder")
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("25f56d71cb979c03607c310ec9bfd367")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, optionally filtering, ordering, and loading referenced entity bodies. The `managedReferencesBehaviour` controls whether to include references to non-existent entities. Place inside `entityFetch` to retrieve references.
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
	@SourceHash("30ce5b8dcf1aa70b41233b36446ab4e8")
	@Nullable
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, orderBy, entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes, with optional filtering, ordering, and nested entity body fetching. The `managedReferencesBehaviour` controls whether to include only references pointing to existing entities or all references. Place inside `entityFetch` to retrieve associated references with fine-grained control over which references are included and how they are presented.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", filterBy(attributeEquals("isPrimary", true)), orderBy(attributeNatural("sortOrder", ASC)), entityFetch(attributeContent("name")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("2fe223bc82e21b0874ce8d81ab3a57be")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, orderBy,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both reference-level attributes and the full bodies of referenced entities. Supports filtering, ordering, and referential integrity control via `ManagedReferencesBehaviour`. Use this to retrieve rich reference metadata and related entity data in a single query.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         EXISTING, "categories",
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
	@SourceHash("2368e9be92fa04464c1774abf29150da")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, null, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, referenced group entity bodies. Controls whether to include only references to existing entities, applies reference-level filtering and ordering, and supports fetching group entity data. Reference attributes are not included—use `referenceContentWithAttributes` for those. Must be used within `entityFetch`.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", filterBy(attributeEquals("isPrimary", true)), orderBy(attributeNatural("sortOrder", ASC)), entityGroupFetch(attributeContent("code")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("df369f23440ed34878d8e96231705f26")
	@Nullable
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, orderBy, null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all reference-level attributes. Supports filtering and ordering on reference attributes, optional group entity fetching, and can control inclusion of references to non-existent entities via `managedReferencesBehaviour`.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         EXISTING, "brand",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         entityGroupFetch(attributeContent("code"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("f8f639757659a75f7d231792e65355a9")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, orderBy,
				AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all attributes stored on the reference record. Supports filtering and ordering by reference or referenced entity attributes, group entity fetching, and controls whether to include only references to existing entities.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         EXISTING, "brand",
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
	@SourceHash("3c3c10069028405efb6c4bee7877a1a6")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, with control over inclusion of only existing targets, filtering and ordering of references, and optional fetching of referenced entity/group bodies. Filtering and ordering apply to reference attributes unless wrapped for entity properties.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "brand", filterBy(attributeEquals("isPrimary", true)), orderBy(attributeNatural("sortOrder", ASC)), entityFetch(attributeContent("name")), null)
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("268c508968c3854519dd60248beedb8e")
	@Nullable
	static ReferenceContent referenceContent(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, orderBy, entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both their primary keys and all reference-level attributes, with optional filtering, ordering, and nested fetching of referenced entity and group bodies. Controls whether to include only references to existing entities or all references using `managedReferencesBehaviour`.
	 *
	 * ```evitaql
	 * referenceContentWithAttributes(
	 *     EXISTING,
	 *     "brand",
	 *     filterBy(attributeEquals("isPrimary", true)),
	 *     orderBy(attributeNatural("sortOrder", ASC)),
	 *     entityFetch(attributeContent("name")),
	 *     entityGroupFetch(attributeContent("code"))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("fb0674c7cf15b09f864806bf58790abd")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, orderBy,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record. Supports filtering, ordering, and pagination of references, and can fetch full bodies of referenced entities and their groups. The `managedReferencesBehaviour` controls whether only references to existing entities are returned.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         EXISTING, "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         attributeContent("isPrimary", "sortOrder"),
	 *         entityFetch(attributeContent("name")),
	 *         entityGroupFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("d0a55a17bffa3c8e2fedb3715c7a3e89")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, filterBy, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, null
			);
	}

	/**
	 * Fetches all references of all types from the entity, returning referenced entity primary keys and, optionally, their full bodies as specified by the nested `EntityFetch`. The `managedReferencesBehaviour` parameter controls whether only references to existing entities are returned or all references, regardless of target existence.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("01726d5761205b5fcfea96aecefd22f0")
	@Nonnull
	static ReferenceContent referenceContentAll(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable EntityFetch entityRequirement
	) {
		return new ReferenceContent(managedReferencesBehaviour, entityRequirement, null);
	}

	/**
	 * Fetches all references of all types from the entity, including their reference-level attributes and the full bodies of referenced entities as specified by the nested `EntityFetch`. The `ManagedReferencesBehaviour` controls whether only references to existing entities are returned. Useful for retrieving complete reference data with attributes in one call.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(EXISTING, entityFetch(attributeContentAll()))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("6b930e057109e3a467eb7095cf32dc8c")
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
	 * Fetches all references of all types from the entity, including their reference-level attributes and, optionally, the full bodies of referenced entities. The `managedReferencesBehaviour` controls whether to include references to non-existent entities. Place inside `entityFetch` to retrieve comprehensive reference data for each entity.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(EXISTING, attributeContent("sortOrder"), entityFetch(attributeContent("name")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("07870975764e792a2fc108e44f412b81")
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
	 * Fetches all references of all types for an entity, returning the primary keys of referenced entities and, optionally, the full bodies of their group entities. The `managedReferencesBehaviour` controls whether only existing references are included. Place inside `entityFetch`.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAll(EXISTING, entityGroupFetch(attributeContent("code")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("8866f7bcd2f164a81f3be0ce18b5f484")
	@Nonnull
	static ReferenceContent referenceContentAll(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return new ReferenceContent(managedReferencesBehaviour, null, groupEntityRequirement);
	}

	/**
	 * Fetches all references of all types from the entity, including reference-level attributes and group entity bodies as specified, with control over whether only references to existing entities are returned (`EXISTING`) or all references (`ANY`). Use inside `entityFetch` to retrieve complete reference data for each entity.
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
	@SourceHash("50998b4e5a4f147015801fd270c70ba4")
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
	 * Fetches all references of all types from the entity, including all reference-level attributes, with optional control over whether to include only references to existing entities. Allows specifying which reference attributes to fetch and how to load referenced group entities.
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(EXISTING, attributeContent("isPrimary"), entityGroupFetch(attributeContent("code")))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("ddabb462019c59cd955cb65cc3a2e24d")
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
	 * Fetches all references of all types from an entity, returning the primary keys of referenced entities and optionally their full bodies or group entities, with control over whether to include only existing references or all. Place inside `entityFetch` to include references in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAll(EXISTING, entityFetch(attributeContent("name")), entityGroupFetch(attributeContent("code")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("6b94260c0507b7444cf08f98896ef884")
	@Nonnull
	static ReferenceContent referenceContentAll(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement
	) {
		return new ReferenceContent(managedReferencesBehaviour, entityRequirement, groupEntityRequirement);
	}

	/**
	 * Fetches all reference groups of all types from an entity, including both the primary keys and all reference-level attributes, and loads full bodies of referenced and group entities as specified. The `managedReferencesBehaviour` controls whether only existing or all references are returned.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(EXISTING, entityFetch(attributeContent("name")), entityGroupFetch(attributeContent("code")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("d43d3385f13804761182d51cbfcadbe6")
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
	 * Fetches all references of all types from the entity, including their reference-level attributes, with optional fetching of referenced entity and group bodies. The `managedReferencesBehaviour` controls whether only references to existing entities are included. Useful for retrieving complete association data in one call.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(EXISTING, attributeContentAll(), entityFetch(attributeContent("name")), entityGroupFetch(attributeContent("code")))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("1b16549981cd895ece4703cebd0184a5")
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
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, paginates or chunks the result using a chunking constraint. Place inside `entityFetch`; without it, references are excluded from results.
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
	@SourceHash("5ab005ac46d04b90accbbd268d31659e")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable ChunkingRequireConstraint chunk) {
		if (referenceName == null) {
			return new ReferenceContent(chunk);
		}
		return new ReferenceContent(referenceName, null, null, null, null, chunk);
	}

	/**
	 * Fetches references of the specified type, including both their primary keys and the listed reference-level attributes, with optional chunking for large sets. Place inside `entityFetch` to retrieve reference metadata and attributes in one step.
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
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("cafc6951a3fb3461c75eab183912a7ab")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable ChunkingRequireConstraint chunk,
		@Nullable String... attributeNames
	) {
		if (referenceName == null) {
			return null;
		} else {
			return new ReferenceContent(
				referenceName, null, null,
				attributeContent(attributeNames), null, null, chunk
			);
		}
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record itself. Supports pagination or chunking via the provided chunking constraint.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("254fbd4240b01c6a6017475f6189af71")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, null, null,
				AttributeContent.ALL_ATTRIBUTES, null, null, chunk
			);
	}

	/**
	 * Fetches references of the given type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record itself. Optionally, you can specify which reference attributes to fetch and paginate large reference sets using a chunking constraint.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
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
	@SourceHash("f2fdd3950643cbb8923167e99e203ac7")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, null, chunk
			);
	}

	/**
	 * Fetches one or more named references from an entity, returning the primary keys of referenced entities and optionally their full bodies. Supports chunking (pagination) of large reference sets via a chunking constraint. Place inside `entityFetch` to include references in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(page(1, 10), "categories", "brand")
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("5693afe47dd4b967931f0fed7b53e06a")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable ChunkingRequireConstraint chunk, @Nullable String... referenceName) {
		if (referenceName == null) {
			return new ReferenceContent(chunk);
		}
		return new ReferenceContent(referenceName, null, null, chunk);
	}

	/**
	 * Fetches the specified reference group from an entity, returning primary keys of referenced entities and, if requested, their full bodies. Supports nested `EntityFetch` to load referenced entities, and chunking constraints for pagination of large reference sets.
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         entityFetch(attributeContent("name")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("efda12b5cb0d4b174a5d504d140966cb")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable EntityFetch entityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		if (referenceName == null && entityRequirement == null) {
			return new ReferenceContent(chunk);
		}
		if (referenceName == null) {
			return new ReferenceContent(entityRequirement, null, chunk);
		}
		return new ReferenceContent(referenceName, null, null, entityRequirement, null, chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity bodies (as defined by `entityRequirement`) and all attributes stored on the reference record itself. Supports chunking large reference sets via `chunk`. Use inside `entityFetch` to retrieve full reference details, including metadata about the relationship.
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
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("25bd47e82d8e49f5db85b12dc895073c")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, null, null,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all attributes stored on the reference record. Optionally, also fetches the referenced entity bodies and limits the reference set using chunking.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
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
	@SourceHash("b9b736a04b52db98bbac9651b6998e67")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, the bodies of referenced group entities. Supports chunking to limit the number of references returned. Place inside `entityFetch` to include references in results.
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
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("3cdedcf68d17bf5a842b71e8fb706ff3")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		if (referenceName == null && groupEntityRequirement == null) {
			return new ReferenceContent(chunk);
		}
		if (referenceName == null) {
			return new ReferenceContent(null, groupEntityRequirement, chunk);
		}
		return new ReferenceContent(referenceName, null, null, null, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches references of the specified type, including both the primary keys of referenced entities and all attributes stored on the reference record itself. Optionally, fetches group entity bodies and supports chunking (pagination) of large reference sets.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
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
	@SourceHash("5794b17b86bc7d035af5b4a2a76898fd")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, null, null,
				AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type, including both the referenced entity primary keys and all reference-level attributes, with optional fetching of group entity bodies and chunking for large sets. Use inside `entityFetch` to retrieve associations and their metadata.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         attributeContent("isPrimary", "sortOrder"),
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
	@SourceHash("f76c0a79ee75992e0c665bed5cc3bf92")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches a named reference group from an entity, returning referenced entity primary keys and, if specified, full referenced and group entity bodies. Supports chunking for large reference sets. Place inside `entityFetch` to include references in results.
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
	@SourceHash("9fd9551020c4834506d20a5ee86a09d0")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		if (referenceName == null) {
			return new ReferenceContent(entityRequirement, groupEntityRequirement, chunk);
		}
		return new ReferenceContent(referenceName, null, null, entityRequirement, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches references of the specified type, including both the referenced entities' primary keys and all attributes stored on the reference record itself. Optionally loads full bodies of referenced entities and their groups, and supports chunking for large reference sets.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "parameterValues",
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
	@SourceHash("395f3b54de364c8f20935b1432733a03")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, null, null,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record itself. Optionally, you can fetch full referenced entity/group bodies and paginate large reference sets. Filtering and ordering can be applied to reference or referenced entity attributes.
	 *
	 * ```evitaql
	 * referenceContentWithAttributes(
	 *     "parameterValues",
	 *     attributeContent("sortOrder"),
	 *     entityFetch(attributeContent("code")),
	 *     entityGroupFetch(attributeContent("code")),
	 *     page(1, 10)
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("48ab9a8918e0ba8fcab9a44792967188")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified types from the entity, returning their primary keys and, optionally, full referenced entity bodies (via nested `entityFetch`). Supports chunking large reference sets with `page` or `strip`. Place inside `entityFetch` to include references in results.
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
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("e511852154cb670bc1556a63e3f18cf3")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String[] referencedEntityTypes, @Nullable EntityFetch entityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		if (referencedEntityTypes == null && entityRequirement == null) {
			return new ReferenceContent(chunk);
		}
		if (referencedEntityTypes == null) {
			return new ReferenceContent(entityRequirement, null, chunk);
		}
		return new ReferenceContent(referencedEntityTypes, entityRequirement, null, chunk);
	}

	/**
	 * Fetches references of the specified types from an entity, returning their primary keys and, optionally, group entity bodies and paginated chunks. Place inside `entityFetch` to include references in results; supports group fetching and chunking for large sets.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         ["categories", "brand"],
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
	@SourceHash("f67ba879de6e1e7f32a7c7a126a56ae2")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String[] referencedEntityTypes, @Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		if (referencedEntityTypes == null && groupEntityRequirement == null) {
			return new ReferenceContent(chunk);
		}
		if (referencedEntityTypes == null) {
			return new ReferenceContent(null, groupEntityRequirement, chunk);
		}
		return new ReferenceContent(referencedEntityTypes, null, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches references of the specified types from an entity, returning their primary keys and, if requested, the full bodies of referenced and group entities. Supports chunking for large reference sets. Reference attributes are not included by default.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         ["brand", "categories"],
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
	@SourceHash("2a826f8159ef2c9930e76830fca21a4f")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable String[] referencedEntityTypes, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		if (referencedEntityTypes != null) {
			return new ReferenceContent(referencedEntityTypes, entityRequirement, groupEntityRequirement, chunk);
		} else {
			return new ReferenceContent(entityRequirement, groupEntityRequirement, chunk);
		}
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and optionally applying a filter and pagination (chunking). Place inside `entityFetch` to include only references matching the filter and within the requested chunk; referenced entity bodies and attributes are not loaded unless nested constraints are added.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("5bc4f8602db5210d6f7664247421f424")
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable ChunkingRequireConstraint chunk) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, null, null, null, chunk);
	}

	/**
	 * Fetches references of the specified type, including both the referenced entity primary keys and all attributes stored on the reference record. Supports filtering references by reference-level attributes and paginating large reference sets using chunking constraints.
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
	@SourceHash("390d8c718dd7fff013ebf5e745e7f591")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, null,
				AttributeContent.ALL_ATTRIBUTES, null, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type, including both the referenced entity primary keys and all reference-level attributes. Supports filtering references (by reference attributes), fetching specific reference attributes, and paginating large reference sets. Place inside `entityFetch` to retrieve enriched reference data.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         attributeContent("isPrimary", "sortOrder"),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("14e471b5c02eba449a7322c826769a26")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable AttributeContent attributeContent,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, the full bodies of referenced entities. Supports filtering references (by reference attributes or referenced entity attributes), fetching referenced entity data, and paginating large reference sets. Must be used within `entityFetch`; references are omitted otherwise.
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
	@SourceHash("1cbb3f9fabfe5ca2d7c77ed9e1bc14f0")
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable EntityFetch entityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, null, entityRequirement, null, chunk);
	}

	/**
	 * Fetches references of the given type, including both the referenced entity primary keys and all attributes stored on the reference record itself. Supports filtering references (by reference attributes), fetching referenced entity bodies, and chunking large reference sets. Place inside `entityFetch` to retrieve enriched reference data for associations.
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
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("846f9a86a27c58d3d6d283dca99fb615")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, null,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record. Supports filtering references, fetching referenced entity bodies, and chunking large reference sets. Use to access reference metadata alongside referenced entities.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "parameterValues",
	 *         filterBy(attributeEquals("isPrimary", true)),
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
	@SourceHash("cf43af6722f47160ca9b6173d7d4fce0")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and optionally group entity bodies. Supports filtering references (by reference attributes), fetching group entity details, and paginating large reference sets. Place inside `entityFetch` to include references in results.
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
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("247a81f6153f48ec6caf68ced8a11e1f")
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, null, null, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches references of the specified type, including both the primary keys of referenced entities and all attributes stored on the reference record. Supports filtering, fetching group entity bodies, and paginating large reference sets. Place inside `entityFetch` to retrieve enriched reference data.
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
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("cf0d59877ffbf4fbca4e8ab3acda2e0c")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, null,
				AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all reference-level attributes. Allows filtering, fetching of referenced group entities, and chunking large reference sets. Useful for retrieving rich association data with metadata.
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
	@SourceHash("7fbb65d74d705dfbe2fa558cda5c1bd8")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, optionally filtering, ordering, paginating, and loading the bodies of referenced entities or their groups. Reference attributes are not included—use `referenceContentWithAttributes` for those. Place inside `entityFetch`.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
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
	@SourceHash("18f06936999a6f8a1a395596a5968443")
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, null, entityRequirement, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes. Supports filtering, ordering, pagination, and loading full referenced entity/group bodies. Place inside `entityFetch`.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "parameterValues",
	 *         filterBy(attributeEquals("isPrimary", true)),
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
	@SourceHash("988c57acfed421e7346f05086d935dee")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, null,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including their primary keys, reference-level attributes, and optionally the full bodies of referenced entities or groups. Supports filtering, ordering, and pagination of references. Reference attributes are included in the result, unlike the basic `referenceContent`.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "parameterValues",
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
	@SourceHash("6758bbdde4dc96265d8ffc7f8000a70d")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, referenced entity bodies. Use nested `orderBy` to sort references (by reference attributes or referenced entity attributes) and `chunk` to paginate large reference sets.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("3a15a77c7e858c8babc29489649ac40f")
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable OrderBy orderBy, @Nullable ChunkingRequireConstraint chunk) {
		return referenceName == null ? null : new ReferenceContent(referenceName, null, orderBy, null, null, chunk);
	}

	/**
	 * Fetches references of the specified type, including both the referenced entity primary keys and all attributes stored on the reference record itself. Supports custom ordering and chunking (pagination) of the reference set. Place inside `entityFetch` to retrieve reference metadata alongside entity data.
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
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("add236ffdeafec3b97f18b5d99f820f3")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable ChunkingRequireConstraint chunk) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, null, orderBy,
				AttributeContent.ALL_ATTRIBUTES, null, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type, including both reference-level attributes and referenced entity primary keys. Supports custom ordering, attribute selection, and chunking (pagination) of the reference set. Use inside `entityFetch` to retrieve associations with metadata.
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
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("e5f6225a75058f8771fde8b01b68499b")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, null, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, full referenced entity bodies. Supports ordering references (by reference or referenced entity attributes), nested entity fetches, and chunking for large sets. Place inside `entityFetch` to include references in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         orderBy(attributeNatural("priority", DESC)),
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
	@SourceHash("64b01a1f3afa8cdbbd43ef51c3d6501e")
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		return referenceName == null ? null : new ReferenceContent(referenceName, null, orderBy, entityRequirement, null, chunk);
	}

	/**
	 * Fetches references of the specified type, including both their primary keys and all reference-level attributes, with optional ordering, full referenced entity bodies, and chunking for large sets. Use inside `entityFetch` to retrieve rich relationship data and metadata.
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
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("f00b2373b614feab9763c114719a024f")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null : new ReferenceContent(
			referenceName, null, orderBy,
			AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, chunk
		);
	}

	/**
	 * Fetches references of the specified type from an entity, including reference-level attributes, with optional ordering, nested attribute/entity fetches, and chunking for pagination. Use to retrieve both the reference metadata and details of referenced entities in a single query.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "parameterValues",
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         attributeContent("isPrimary"),
	 *         entityFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("45820172e205af69f55caeefe418cc6d")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, null, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, referenced group entity bodies. Allows custom ordering and chunking (pagination) of references, and supports fetching group entity data via nested constraints.
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         orderBy(attributeNatural("order", ASC)),
	 *         entityGroupFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("ed8052c0df3e6e27be6580c258af0f1b")
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable OrderBy orderBy, @Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		return referenceName == null ? null : new ReferenceContent(referenceName, null, orderBy, null, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches references of the specified type, including both the primary keys of referenced entities and all attributes stored on the reference record itself. Supports custom ordering, group entity fetching, and chunking for large reference sets. Use when you need both reference metadata and referenced entity info.
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
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("1a129cf258104803084e2825f9c9abd2")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, null, orderBy,
				AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type, including both the primary keys of referenced entities and all attributes stored on the reference record itself. Supports custom ordering, attribute selection, group entity fetching, and chunking for large reference sets.
	 *
	 * ```evitaql
	 * referenceContentWithAttributes(
	 *     "categories",
	 *     orderBy(attributeNatural("sortOrder", ASC)),
	 *     attributeContent("isPrimary"),
	 *     entityGroupFetch(attributeContent("code")),
	 *     page(1, 10)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("86caa23f823b7f3a070c50cbf6c1db9f")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, null, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, the full bodies of referenced and group entities. Supports custom ordering and chunking (pagination) of references. Place inside `entityFetch` to include references in results.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         orderBy(attributeNatural("order", ASC)),
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
	@SourceHash("4f6c3422468f795df95c00d42c2da42a")
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		return referenceName == null ? null : new ReferenceContent(referenceName, null, orderBy, entityRequirement, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity/group bodies and all reference-level attributes. Supports custom ordering, nested entity/group fetches, and chunking for large sets. Use to retrieve rich association data with metadata.
	 *
	 * ```evitaql
	 * referenceContentWithAttributes(
	 *     "categories",
	 *     orderBy(attributeNatural("sortOrder", ASC)),
	 *     entityFetch(attributeContent("name")),
	 *     entityGroupFetch(attributeContent("code")),
	 *     page(1, 10)
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("6cab4f02c0baeb76eb1ab62de0ead7de")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, null, orderBy,
				AttributeContent.ALL_ATTRIBUTES,
				entityRequirement, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type, including both their primary keys and all reference-level attributes, with optional ordering, attribute selection, referenced entity/group fetching, and chunking. Use to retrieve rich reference metadata and related entities in a single query.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         attributeContent("isPrimary"),
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
	@SourceHash("f682e4ca2e9b0eee6a1448b383f40769")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, null, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches all references of all types for an entity, returning referenced entity primary keys and, if specified, their full bodies via nested `entityFetch`. Supports chunking large reference sets with `page` or `strip`. Reference attributes are not included by default.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAll(
	 *         entityFetch(attributeContent("code")),
	 *         page(1, 20)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("e6a816a8207a8fe4f69dc9f3ca72eb03")
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable EntityFetch entityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		return new ReferenceContent(entityRequirement, null, chunk);
	}

	/**
	 * Fetches all references of all types from the entity, including their reference-level attributes and, optionally, the full bodies of referenced entities and chunking for large sets. Use inside `entityFetch` to retrieve complete reference info for each entity.
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
	@SourceHash("f1b5db875deff11dd79fee2c9a437855")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(
		@Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return new ReferenceContent(AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, chunk);
	}

	/**
	 * Fetches all references of all types from the entity, including their reference-level attributes, and optionally loads full bodies of referenced entities and paginates large reference sets. Use inside `entityFetch` to retrieve comprehensive reference data.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
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
	@SourceHash("f1dbb3f2f0fec10ffe47327276974a2a")
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
	 * Fetches all references of all types from the entity, including their group entity bodies (as specified by `groupEntityRequirement`), and limits the returned references using the provided chunking constraint (such as pagination). Reference attributes are not included—use `referenceContentAllWithAttributes` for that.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("752ed61b640868fca1ecb5a0485576c3")
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		return new ReferenceContent(null, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches all references of all types from the entity, including both the primary keys of referenced entities and all attributes stored on the reference records. Allows fetching group entity bodies and paginating large reference sets via chunking constraints.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
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
	@SourceHash("67b7e690b1247a6236fa8bfa16724a2e")
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
	 * Fetches all references of all types from the entity, including their reference-level attributes, optionally fetching group entity bodies and limiting the number of references returned via chunking. Use inside `entityFetch` to retrieve full reference metadata and related group data.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
	 *         attributeContent("sortOrder"),
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
	@SourceHash("3981c374bfa4f9d8144817adeb6629eb")
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
	 * Fetches all references of all types for an entity, including the primary keys of referenced entities and, optionally, their full bodies and group entities. Supports nested entity/group fetches and chunking to limit result size.
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAll(
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
	@SourceHash("f1611f32fafb0c82da600277e330a623")
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		return new ReferenceContent(entityRequirement, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches all references of all types from the entity, including both reference-level attributes and the full bodies of referenced entities and their groups, with optional chunking for large reference sets. Useful for retrieving complete reference details in one call.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
	 *         entityFetch(attributeContentAll()),
	 *         entityGroupFetch(attributeContentAll()),
	 *         page(1, 20)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("b31e9dc4ae6d95ca564420008331c668")
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
	 * Fetches all references of all types from the entity, including reference-level attributes, and optionally loads full bodies of referenced entities and their groups. Supports filtering, ordering, and pagination of references via nested constraints.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
	 *         attributeContent("sortOrder"),
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
	@SourceHash("ca7c47b0b06e51f5a610ab2f0876f109")
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
	 * Fetches all references of all types from the entity, returning primary keys of referenced entities and, optionally, their bodies, with control over whether to include only existing targets or all references. Supports chunking for large reference sets.
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("9f41ed071a7b10839b1c498c278aeaa0")
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable ManagedReferencesBehaviour managedReferencesBehaviour, @Nullable ChunkingRequireConstraint chunk) {
		return new ReferenceContent(managedReferencesBehaviour, chunk);
	}

	/**
	 * Fetches all references of all types from the entity, including reference-level attributes, with optional control over which references are included (e.g., only those pointing to existing entities) and support for paginating large reference sets via chunking constraints.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("e28d95796523f36a0a3621ef6fdbf69a")
	@Nonnull
	static ReferenceContent referenceContentAllWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return new ReferenceContent(managedReferencesBehaviour, AttributeContent.ALL_ATTRIBUTES, chunk);
	}

	/**
	 * Fetches all references of all types for an entity, including all reference-level attributes, with control over which references are included (e.g., only those pointing to existing entities), which attributes to fetch, and how to paginate large reference sets.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("25e9fb1a398f80bc334d6ffc16386f90")
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
	 * Fetches references of the specified type from an entity, returning their primary keys and optionally paginating the result set with a chunking constraint. The `managedReferencesBehaviour` controls whether to include references to non-existent entities. Place inside `entityFetch` to retrieve references; without it, references are omitted.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(EXISTING, "categories", page(1, 10))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("d2971f7834131f5bc0bf9d6d4070796f")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable ManagedReferencesBehaviour managedReferencesBehaviour, @Nullable String referenceName, @Nullable ChunkingRequireConstraint chunk) {
		if (referenceName == null) {
			return new ReferenceContent(managedReferencesBehaviour, chunk);
		}
		return new ReferenceContent(managedReferencesBehaviour, referenceName, null, null, null, null, chunk);
	}

	/**
	 * Fetches references of the given name from an entity, including both the referenced entity primary keys and specified reference-level attributes. Supports filtering out references to non-existent entities via `managedReferencesBehaviour`, and can paginate large reference sets using a chunking constraint.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "categories", page(1, 10), "sortOrder", "isPrimary")
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("df895c4de29fac76488ad5f752ff68f0")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable ChunkingRequireConstraint chunk,
		@Nullable String... attributeNames
	) {
		if (referenceName == null) {
			return null;
		} else {
			return new ReferenceContent(managedReferencesBehaviour, referenceName, null, null, attributeContent(attributeNames), null, null, chunk);
		}
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes, with optional chunking (pagination) and control over whether to include only existing referenced entities or all references. Suitable for retrieving rich association metadata in large or partially managed reference sets.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "categories", page(1, 20))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("5b89b6785a86df96b5297916751972b5")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				AttributeContent.ALL_ATTRIBUTES, null, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes, with optional filtering by reference existence, attribute selection, and chunking for large sets. Supports nested fetches for referenced entities and groups.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", attributeContent("isPrimary"), page(1, 10))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("c65dba2d3034a9b2032dd9e36f35a0dc")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, null, chunk
			);
	}

	/**
	 * Fetches one or more named references from an entity, returning their primary keys and, optionally, referenced entity bodies, with control over whether to include only existing targets and how many references to return using chunking. Place inside `entityFetch`; references are omitted otherwise.
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
	@SourceHash("a88b0f537385abac40982bd9b622a330")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable ManagedReferencesBehaviour managedReferencesBehaviour, @Nullable ChunkingRequireConstraint chunk, @Nullable String... referenceName) {
		if (referenceName == null) {
			return new ReferenceContent(managedReferencesBehaviour, chunk);
		}
		return new ReferenceContent(managedReferencesBehaviour, referenceName, null, null, chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, the full bodies of referenced entities. Controls whether to include references to non-existent entities via `ManagedReferencesBehaviour`. Supports nested `EntityFetch` for referenced entity bodies and chunking for large reference sets.
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
	@SourceHash("113af5eff3adca6c11cf2d0530cd7a59")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable ManagedReferencesBehaviour managedReferencesBehaviour, @Nullable String referenceName, @Nullable EntityFetch entityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		if (referenceName == null && entityRequirement == null) {
			return new ReferenceContent(managedReferencesBehaviour, chunk);
		}
		if (referenceName == null) {
			return new ReferenceContent(managedReferencesBehaviour, entityRequirement, null, chunk);
		}
		return new ReferenceContent(managedReferencesBehaviour, referenceName, null, null, entityRequirement, null, chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity's primary keys and all attributes stored on the reference record. Supports filtering, ordering, and chunking (pagination) of references, and can control whether to include only references to existing entities or all references via `ManagedReferencesBehaviour`.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", entityFetch(attributeContent("name")), page(1, 10))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("04b2ca5c6a5369d96d8a12713614502c")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including their primary keys, all reference-level attributes, and optionally the full bodies of referenced entities. Lets you control inclusion of references to non-existent entities, filter/order references, and paginate large sets.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         EXISTING, "categories",
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
	@SourceHash("6dc1a4a7cbb3030bd7c9cd2b7b982dcf")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning primary keys of referenced entities and optionally their group entities, with control over including only existing targets. Supports chunking for large reference sets. Place inside `entityFetch`.
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
	@SourceHash("232b21d3eb2c89537d0750e244b8b349")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable ManagedReferencesBehaviour managedReferencesBehaviour, @Nullable String referenceName, @Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		if (referenceName == null && groupEntityRequirement == null) {
			return new ReferenceContent(managedReferencesBehaviour, chunk);
		}
		if (referenceName == null) {
			return new ReferenceContent(managedReferencesBehaviour, null, groupEntityRequirement, chunk);
		}
		return new ReferenceContent(managedReferencesBehaviour, referenceName, null, null, null, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches references of the specified type, including both the referenced entity primary keys and all attributes stored on the reference record, with optional group entity fetching and chunking. The `managedReferencesBehaviour` controls whether only references to existing entities are returned. Use inside `entityFetch` to retrieve reference metadata and optionally limit results for large reference sets.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "categories", entityGroupFetch(attributeContent("code")), page(1, 10))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("346751ca1432fe3cbaaf69eaadd00ebd")

	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning both the primary keys of referenced entities and all reference-level attributes. Allows filtering by reference or referenced entity attributes, custom ordering, and chunking. Use `managedReferencesBehaviour` to control whether only existing references are included. Supports nested entity/group fetches for loading referenced bodies.
	 *
	 * ```evitaql
	 * referenceContentWithAttributes(
	 *     EXISTING, "brand",
	 *     attributeContent("isPrimary"),
	 *     entityGroupFetch(attributeContent("code")),
	 *     page(1, 10)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("efa7e7eabcca07272ba9af25a9dd82e1")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning referenced entity primary keys and optionally their full bodies or group entities. Supports filtering, ordering, pagination, and controls whether to include only existing referenced entities. Place inside `entityFetch` to include references in results.
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
	@SourceHash("ad737065e09184fc07082251dc94e2c4")
	@Nonnull
	static ReferenceContent referenceContent(@Nullable ManagedReferencesBehaviour managedReferencesBehaviour, @Nullable String referenceName, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		if (referenceName == null) {
			return new ReferenceContent(managedReferencesBehaviour, entityRequirement, groupEntityRequirement, chunk);
		}
		return new ReferenceContent(managedReferencesBehaviour, referenceName, null, null, entityRequirement, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the primary keys of referenced entities and all reference-level attributes (metadata on the association). Optionally loads full bodies of referenced and group entities, applies chunking, and controls inclusion of references to non-existent entities via `ManagedReferencesBehaviour`.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(EXISTING, "brand", entityFetch(attributeContent("name")), null, page(1, 10))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("6db5f35f17ce2565e667f68e2023cb6b")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type, including both the referenced entity primary keys and all reference-level attributes, with optional fetching of referenced entity/group bodies, filtering, ordering, and chunking. Use `managedReferencesBehaviour` to control inclusion of references to non-existent entities.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         EXISTING, "categories",
	 *         attributeContent("isPrimary"),
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
	@SourceHash("ef072b63199fdd587f6cd1e6e32f5ac5")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nullable String referenceName,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				managedReferencesBehaviour, referenceName, null, null,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, applying optional filtering, ordering, and chunking (pagination) to the reference set. Returns reference primary keys and, if nested, referenced entity/group bodies. Filtering and ordering target reference attributes by default; use `entityHaving` or `entityProperty` to act on referenced entity data.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "parameterValues",
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
	@SourceHash("03fc62f3c3d1e6af2aa2d7255b653711")
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable ChunkingRequireConstraint chunk) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, orderBy, null, null, chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record. Supports filtering, ordering, and chunking (pagination) of references. Place inside `entityFetch` to retrieve reference metadata and optionally fetch referenced entity bodies or groups.
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "parameterValues",
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
	@SourceHash("0ad0e600bac4600a2bc29779a9b21d2c")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, orderBy,
				AttributeContent.ALL_ATTRIBUTES, null, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type, including both the referenced entity primary keys and all attributes stored on the reference record itself. Supports filtering, ordering, and chunking of references, and can fetch referenced entity bodies via nested constraints.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "parameterValues",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         attributeContent("isPrimary", "sortOrder"),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("904b9f373a34c322e246637aa3ef1349")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and, optionally, full referenced entity bodies, with support for filtering, ordering, and pagination. Filtering and ordering target reference attributes unless wrapped for entity attributes.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContent(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
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
	@SourceHash("53be346ddbdd7e2bfcd85c5473cb43cc")
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, orderBy, entityRequirement, null, chunk);
	}

	/**
	 * Fetches references of the specified type, including their reference-level attributes, with optional filtering, ordering, referenced entity body loading, and chunking. Use this to retrieve both the association metadata and referenced entities in a single query.
	 *
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
	@SourceHash("1fd2c49e1a75b9ee8ade516225fcc264")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, orderBy,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all attributes stored on the reference record. Supports filtering, ordering, and chunking of references, and can nest entity/group fetches for full referenced bodies.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "parameterValues",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         attributeContent("isPrimary", "sortOrder"),
	 *         entityFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("3cf72707fddccf6467845e65e971cdba")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable ChunkingRequireConstraint chunk) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, null, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, returning their primary keys and optionally loading group entity bodies, filtering, ordering, and paginating the reference set. Reference-level filters and ordering apply to reference attributes; use `entityHaving` to filter by referenced entity data.
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
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("3f1a6f2d7e644897f7c91c9248570120")
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, orderBy, null, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes. Supports filtering, ordering, group entity fetching, and chunking for large reference sets. Place inside `entityFetch` to retrieve enriched reference data and metadata.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
	 *         orderBy(attributeNatural("sortOrder", ASC)),
	 *         entityGroupFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("93e4a685af7931054c35ababd666994e")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy, @Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, orderBy,
				AttributeContent.ALL_ATTRIBUTES, null, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes. Supports filtering, ordering, group entity fetching, and chunking for large reference sets; must be used inside `entityFetch`.
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
	@SourceHash("a8ba668323c06a7d7d1d2505db3311e1")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				null, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, optionally filtering, ordering, paginating, and loading referenced entity/group bodies. Reference-level filters/orderings act on the reference record; use nested entityHaving for target entity criteria. By default, only reference primary keys are returned—use the attributes variant to fetch reference attributes as well.
	 *
	 * ```evitaql
	 * referenceContent(
	 *     "parameterValues",
	 *     filterBy(attributeEquals("isPrimary", true)),
	 *     orderBy(attributeNatural("sortOrder", ASC)),
	 *     entityFetch(attributeContent("code")),
	 *     entityGroupFetch(attributeContent("code")),
	 *     page(1, 10)
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("4c72ae5b9b7de0dd64919a401de39a10")
	@Nullable
	static ReferenceContent referenceContent(@Nullable String referenceName, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		return referenceName == null ? null : new ReferenceContent(referenceName, filterBy, orderBy, entityRequirement, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes. Supports filtering, ordering, and pagination of references, and can fetch full bodies of referenced and group entities. Use this to retrieve rich association data and metadata in one call.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentWithAttributes(
	 *         "categories",
	 *         filterBy(attributeEquals("isPrimary", true)),
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
	@SourceHash("8788fa7d71eb3b8c13bebebfd052d572")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, orderBy,
				AttributeContent.ALL_ATTRIBUTES, entityRequirement, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches references of the specified type from an entity, including both the referenced entity primary keys and all reference-level attributes. Supports filtering, ordering, attribute selection, nested entity/group fetches, and chunking for large sets. Use to retrieve rich reference metadata alongside referenced entity data.
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
	@SourceHash("3d2a05d44aabf50137de81cbed08fcbc")
	@Nullable
	static ReferenceContent referenceContentWithAttributes(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable AttributeContent attributeContent,
		@Nullable EntityFetch entityRequirement,
		@Nullable EntityGroupFetch groupEntityRequirement,
		@Nullable ChunkingRequireConstraint chunk
	) {
		return referenceName == null ?
			null :
			new ReferenceContent(
				referenceName, filterBy, orderBy,
				ofNullable(attributeContent).orElse(AttributeContent.ALL_ATTRIBUTES),
				entityRequirement, groupEntityRequirement, chunk
			);
	}

	/**
	 * Fetches all references of all types from the entity, returning the primary keys of referenced entities and optionally their full bodies, with control over inclusion of references to non-existent entities via `ManagedReferencesBehaviour`. Supports nested `EntityFetch` for loading referenced entity data and chunking constraints for pagination.
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("fdb0711f82ffa7e7b8a49e9611064022")
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable ManagedReferencesBehaviour managedReferencesBehaviour, @Nullable EntityFetch entityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		return new ReferenceContent(managedReferencesBehaviour, entityRequirement, null, chunk);
	}

	/**
	 * Fetches all reference groups for an entity, including reference-level attributes and the full bodies of referenced entities, with optional chunking for large sets. The `managedReferencesBehaviour` controls whether only existing references are included.
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(EXISTING, entityFetch(attributeContent("name")), page(1, 10))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("334c7416dd543bfad927bb09b2719ccc")
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
	 * Fetches all references of all types for an entity, including reference-level attributes, with optional control over which references are included (e.g., only those pointing to existing entities), referenced entity body fetching, and chunking for large sets. Supports advanced filtering, ordering, and pagination of references.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
	 *         EXISTING,
	 *         attributeContent("sortOrder"),
	 *         entityFetch(attributeContent("code")),
	 *         page(1, 10)
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("28576e3ffcb8fd6dad2d1a209809b0f4")
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
	 * Fetches all references of all types from the entity, returning the primary keys of referenced entities and optionally their group entity bodies, with control over inclusion of only existing references and support for chunking large reference sets.
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAll(EXISTING, entityGroupFetch(attributeContent("code")), page(1, 10))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("eb599ab11195440dc2987252fe5ee43a")
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable ManagedReferencesBehaviour managedReferencesBehaviour, @Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		return new ReferenceContent(managedReferencesBehaviour, null, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches all references of all types for the entity, including all reference-level attributes, with optional filtering for only existing referenced entities, group entity body fetching, and chunking for large reference sets. Use for comprehensive reference retrieval with full metadata.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(EXISTING, entityGroupFetch(attributeContent("code")), page(1, 20))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("17d7c5d73271d80cb33a235fbe803ddb")
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
	 * Fetches all references of all types from the entity, including all reference-level attributes, with optional control over referenced entity existence, group entity fetching, and chunking for large sets. Useful for retrieving complete reference info with flexible filtering and pagination.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(EXISTING, attributeContentAll(), entityGroupFetch(attributeContent("code")), page(1, 20))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("9611ae740f4f14b5b4e6db36e6bbdd42")
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
	 * Fetches all references of all types from the entity, returning referenced entity primary keys and, optionally, their full bodies and groups. Use `managedReferencesBehaviour` to control inclusion of references to non-existent entities. Supports nested fetch, filtering, ordering, and pagination for large reference sets.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAll(EXISTING, entityFetch(attributeContent("name")), entityGroupFetch(attributeContent("code")), page(1, 10))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#reference-content)
	 *
	 * @see io.evitadb.api.query.require.ReferenceContent
	 */
	@SourceHash("f10fafbfed6a8566e8f05667511a9660")
	@Nonnull
	static ReferenceContent referenceContentAll(@Nullable ManagedReferencesBehaviour managedReferencesBehaviour, @Nullable EntityFetch entityRequirement, @Nullable EntityGroupFetch groupEntityRequirement, @Nullable ChunkingRequireConstraint chunk) {
		return new ReferenceContent(managedReferencesBehaviour, entityRequirement, groupEntityRequirement, chunk);
	}

	/**
	 * Fetches all references of all types from the entity, including reference-level attributes, and allows control over which references are included (e.g., only those with existing targets). Supports fetching full bodies of referenced entities and their groups, as well as chunking for large reference sets.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
	 *         EXISTING,
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
	@SourceHash("f18823507364d2975a89cee40d2d78f5")
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
	 * Fetches all references of all types from the entity, including reference-level attributes, with optional control over referenced entity existence, attribute selection, referenced entity/group body fetching, filtering, ordering, and pagination. Use this to retrieve comprehensive reference data in one call.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     referenceContentAllWithAttributes(
	 *         EXISTING,
	 *         attributeContent("sortOrder"),
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
	@SourceHash("975ffcff6e30ff8a0cfea9c4ed81ac4e")
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
	 * Fetches the ancestor chain (parent primary keys up to the root) for a hierarchical entity, enabling breadcrumb-style navigation. Returns only IDs by default; use nested constraints to limit depth or load parent entity data. Only valid inside `entityFetch` for hierarchical schemas.
	 *
	 * ```evitaql
	 * entityFetch(
	 *     hierarchyContent()
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#hierarchy-content)
	 *
	 * @see io.evitadb.api.query.require.HierarchyContent
	 */
	@SourceHash("8738a87be2ff08a98791e93a3e98df2e")
	@Nonnull
	static HierarchyContent hierarchyContent() {
		return new HierarchyContent();
	}

	/**
	 * Fetches the ancestor chain (parent primary keys) for a hierarchical entity, stopping at the specified depth or condition via `stopAt`. Use inside `entityFetch` to retrieve breadcrumb-style placement without loading parent entity bodies.
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
	@SourceHash("57f1ad34e1c371e5168565a3a9afac7f")
	@Nonnull
	static HierarchyContent hierarchyContent(@Nullable HierarchyStopAt stopAt) {
		return stopAt == null ? new HierarchyContent() : new HierarchyContent(stopAt);
	}

	/**
	 * Fetches the full chain of ancestor entities (from immediate parent up to the root) for entities in a hierarchy, loading the complete body of each parent node according to the nested `EntityFetch` specification. Use inside `entityFetch` for breadcrumb-style ancestor data.
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
	@SourceHash("b32fee6dfa52371a4a1fdf66571509f6")
	@Nonnull
	static HierarchyContent hierarchyContent(@Nullable EntityFetch entityFetch) {
		return entityFetch == null ? new HierarchyContent() : new HierarchyContent(entityFetch);
	}

	/**
	 * Fetches the ancestor chain of a hierarchical entity, returning parent nodes up to a specified depth (`stopAt`). Optionally loads full parent entity data using nested `entityFetch`, allowing you to specify which attributes or data to include for each ancestor. Use for lightweight breadcrumb-style hierarchy info.
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
	@SourceHash("18f91d21e283a83e4fb8a2fe9b04c62f")
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
	static PriceContent priceContent(@Nullable PriceContentMode contentMode, @Nullable String... priceLists) {
		if (contentMode == null) {
			return null;
		}
		if (ArrayUtils.isEmptyOrItsValuesNull(priceLists)) {
			return new PriceContent(contentMode);
		} else {
			return new PriceContent(contentMode, priceLists);
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
		return PriceContent.respectingFilter(priceLists);
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
		if (priceList == null) {
			return null;
		}
		// if the array is empty - it was deliberate action which needs to produce empty result of the query
		if (priceList.length == 0) {
			return null;
		}
		final String[] normalizeNames = Arrays.stream(priceList).filter(Objects::nonNull).filter(it -> !it.isBlank()).toArray(String[]::new);
		// the array was not empty, but contains only null values - this may not be deliberate action - for example
		// the initalization was like `accompanyingPrice(nullVariable)` and this should exclude the constraint
		if (normalizeNames.length == 0) {
			return null;
		}
		// otherwise propagate only non-null values
		return normalizeNames.length == priceList.length ?
			new DefaultAccompanyingPriceLists(priceList) : new DefaultAccompanyingPriceLists(normalizeNames);
	}

	/**
	 * Requests calculation of an accompanying price (e.g., "original price" or "reference price") alongside the selling price, using a shared currency and validity but a custom price-list sequence defined by `DefaultAccompanyingPriceLists`. Requires selling price to be fetched as well.
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/price#accompanying-price)
	 *
	 * @see io.evitadb.api.query.require.AccompanyingPriceContent
	 */
	@SourceHash("e8de857ea6f8e2daf2bbbbe3226c3712")
	@Nullable
	static AccompanyingPriceContent accompanyingPriceContentDefault() {
		return new AccompanyingPriceContent();
	}

	/**
	 * Requests calculation and inclusion of an accompanying price—such as an "original" or "reference" price—using a custom price-list sequence, labeled by the provided name. Must be combined with a selling price requirement or filter; order of price lists matters.
	 *
	 * ```evitaql
	 * entityFetch(
	 *   priceContentRespectingFilter(),
	 *   accompanyingPriceContent("originalPrice", "reference", "basic")
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/price#accompanying-price)
	 *
	 * @see io.evitadb.api.query.require.AccompanyingPriceContent
	 */
	@SourceHash("33f1a643963924948b0d9fd8e94d569f")
	@Nullable
	static AccompanyingPriceContent accompanyingPriceContent(@Nullable String name, @Nullable String... priceList) {
		// name is required argument
		if (name == null || name.isBlank()) {
			return null;
		}
		if (ArrayUtils.isEmptyOrItsValuesNull(priceList)) {
			return new AccompanyingPriceContent(name);
		}
		final String[] normalizeNames = Arrays.stream(priceList).filter(Objects::nonNull).filter(it -> !it.isBlank()).toArray(String[]::new);
		// the array was not empty, but contains only null values - this may not be deliberate action - for example
		// the initalization was like `accompanyingPrice(nullVariable)` and this should exclude the constraint
		if (normalizeNames.length == 0) {
			return new AccompanyingPriceContent(name);
		}
		// otherwise propagate only non-null values
		return normalizeNames.length == priceList.length ?
			new AccompanyingPriceContent(name, priceList) : new AccompanyingPriceContent(name, normalizeNames);
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
	 * Calculates a summary of all faceted references in the current query, providing counts of matching entities for each facet option within the result set. Useful for building faceted navigation UIs; only entities matching the main query are counted.
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("b279b48460da86ca232260da30de183a")
	@Nonnull
	static FacetSummary facetSummary() {
		return new FacetSummary();
	}

	/**
	 * Triggers calculation of facet statistics for all faceted references in the current query scope, returning counts (or impact predictions) for each selectable facet option. Supports optional fetch, filtering, and ordering of facet entities/groups; only one fetch per type allowed.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         IMPACT,
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("6ff5fd5e9ef298d9ab371bfaaf0be53c")
	@Nonnull
	static FacetSummary facetSummary(@Nullable FacetStatisticsDepth statisticsDepth, @Nullable EntityFetchRequire... requirements) {
		return statisticsDepth == null ?
			new FacetSummary(FacetStatisticsDepth.COUNTS, requirements) :
			new FacetSummary(statisticsDepth, requirements);
	}

	/**
	 * Triggers calculation of facet statistics for all faceted references in the result set, reflecting only entities matching the current query. Controls the detail level (counts or impact), and allows filtering, ordering, and fetch customization for facet options and groups. Use filters and ordering only on properties shared by all referenced types.
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
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("4d4f1aa0bdd23775ff9f60cd5608a03b")
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
	 * Triggers calculation of a facet summary for all faceted references in the schema, returning counts (or impact predictions) for each selectable facet option within the current query result. Supports filtering and ordering of facet options/groups, and allows fetching extra data for facet entities and groups. Only one fetch constraint per type is allowed; filters/orderings apply to all references uniformly. For reference-specific customization, use `facetSummaryOfReference`.
	 * ```evitaql
	 * facetSummary(
	 *     IMPACT,
	 *     filterBy(attributeStartsWith("code", "a")),
	 *     filterGroupBy(attributeEquals("visible", true)),
	 *     orderGroupBy(attributeNatural("name", ASC)),
	 *     entityFetch(attributeContent("name")),
	 *     entityGroupFetch(attributeContent("name"))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("8870f732516794e5e107ae2f82afbd23")
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
	 * Triggers calculation of a facet summary extra result, providing statistics for all faceted references in the current query scope. Supports configurable statistics depth (`COUNTS` or `IMPACT`), group and facet filtering, ordering, and fetch requirements for facets and groups. Only one fetch constraint per type is allowed. Use filters to limit summary size; cross-reference filtering/sorting must target shared properties.
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
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("fbbadb7b5d37c1b91cad13a085c3512f")
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
	 * Calculates a hierarchical summary of all faceted references in the current query result, providing counts (or impact predictions) for each facet option, with optional filtering, ordering, and entity/group fetch customization. Only entities matching the main query are counted; filters/orderings apply to summary display, not entity selection. Use to power dynamic faceted navigation UIs.
	 *
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
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("97bddd46753aaae340ebc2fbcb26b981")
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
	 * Triggers calculation of facet statistics for all faceted references in the current query scope, organizing results by reference, group, and facet. Supports configurable statistics depth (`COUNTS` or `IMPACT`), filtering, ordering, and fetch requirements for facet entities and groups. Filters and ordering apply only to the summary, not to counted entities, and must reference properties shared by all referenced types. Only one `EntityFetch` and one `EntityGroupFetch` are allowed. For reference-specific customization, use `facetSummaryOfReference`.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         IMPACT,
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
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("7ce163f20df1005cca5377b605daab3c")
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
	 * Triggers calculation of a facet summary extra result, providing counts (or impact predictions) for all faceted references in the current query scope. You can filter or order which facets/groups appear, and control data fetched for facet/group entities. Only one entityFetch and one entityGroupFetch are allowed. Filters affect summary display, not entity counts.
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
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("0a62e26c5b95a6aa2016b7f9fc0f9ce8")
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
	 * Triggers calculation of a facet summary with statistics for all faceted references in the current query result. Use `statisticsDepth` to control detail (counts or impact prediction), and `orderBy`/`orderGroupBy` to sort facets/groups. Only one `entityFetch` and one `entityGroupFetch` are allowed; passing more throws an exception.
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
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("416fb7edd4896b0417f769d1c2585548")
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
	 * Triggers calculation of a multi-level facet summary for all faceted references in the current query, reporting counts (or impact predictions) for each facet option, with optional filtering, ordering, and fetch customization for facets/groups. Only entities matching the main query are counted.
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
	@SourceHash("fc3b488b188fc7604b7c1f708df3fbff")
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(statisticsDepth, filterBy, null, null, null, requirements);
	}

	/**
	 * Calculates a hierarchical summary of all faceted references in the result set, including counts (or impact predictions) for each facet option, with optional ordering of facets/groups and custom entity/group fetch requirements. Only one fetch constraint per type is allowed.
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
	@SourceHash("7b7c4d485c3aa82d284d7c27bf913bcc")
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(statisticsDepth, null, null, orderBy, null, requirements);
	}

	/**
	 * Triggers calculation of a facet summary extra result, providing statistics for all faceted references in the current query scope. You can specify the statistics depth (`COUNTS` or `IMPACT`), filter facet groups, and control which data is loaded for facet entities or groups. Only one `EntityFetch` and one `EntityGroupFetch` can be attached; passing more throws an exception.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("cf7b1db1de2e1172f996696cb59fee5f")
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(statisticsDepth, null, facetGroupFilterBy, null, null, requirements);
	}

	/**
	 * Triggers calculation of a facet summary for all faceted references in the schema, returning counts (or impact predictions) for each facet option in the current query result. You can control the statistics depth, group ordering, and which data is fetched for facet entities or groups. Only one `EntityFetch` and one `EntityGroupFetch` are allowed. Filters and ordering apply uniformly across all references. For reference-specific customization, use `facetSummaryOfReference`.
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
	@SourceHash("6a263c4a352c2bbf289d8331c03aa160")
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(statisticsDepth, null, null, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a hierarchical summary of all faceted references in the result set, showing counts (or impact predictions) for each facet option, grouped by reference and group. You can filter or order facets/groups, and control which data is fetched for facet and group entities.
	 * ```evitaql
	 * facetSummary(IMPACT, filterGroupBy(attributeEquals("visible", true)), orderBy(attributeNatural("name", ASC)), entityFetch(attributeContent("name")))
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("7613d65b0566b0f38bb96b7c559445c2")
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
	 * Triggers calculation of a facet summary extra result, providing statistics for all faceted entity references in the current query scope. Lets you control statistics depth (`COUNTS` or `IMPACT`), filter or order facet groups, and fetch related entity data. Only one `EntityFetch` and one `EntityGroupFetch` can be attached; filters/orderings apply across all references. For reference-specific customization, use `facetSummaryOfReference`.
	 *
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
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("943d0318a23a9bbc45252550352fb47d")
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
	 * Triggers calculation of facet statistics for all faceted references in the current query scope, organizing results by reference, group, and facet option. Use `statisticsDepth` to control whether only counts or also impact predictions are computed. Attach group filters and ordering to limit or sort facet groups in the summary. Only one `EntityFetch` and one `EntityGroupFetch` may be supplied. For large datasets, filter or limit the summary for performance.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         IMPACT,
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
	@SourceHash("e51ff0f5dabcfcfb48740903b6583070")
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
	 * Calculates a facet summary for all faceted references in the current query scope, returning counts (or impact predictions) for each facet option, grouped by reference and group. You can filter or order facets/groups, and control fetched data for facet and group entities. Use filters to limit summary size; cross-reference filters/orderings must target properties shared by all referenced types.
	 *
	 * ```evitaql
	 * require(
	 *   facetSummary(
	 *     IMPACT,
	 *     filterBy(attributeStartsWith("code", "a")),
	 *     filterGroupBy(attributeEquals("visible", true)),
	 *     orderBy(attributeNatural("name", ASC)),
	 *     orderGroupBy(attributeNatural("name", ASC)),
	 *     entityFetch(attributeContent("name")),
	 *     entityGroupFetch(attributeContent("name"))
	 *   )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("4c88bb0086094051cfd0dca10969c8a5")
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
			requirements
		);
	}

	/**
	 * Triggers calculation of a facet summary for all faceted references in the entity schema, providing counts (and optionally impact predictions) for each facet option within the current query result. Attach at most one entityFetch and/or entityGroupFetch to control which data is loaded for facet entities and their groups.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("eb5f7cca528cde1d94fac7368dda1b63")
	@Nonnull
	static FacetSummary facetSummary(@Nullable EntityFetchRequire... requirements) {
		return new FacetSummary(FacetStatisticsDepth.COUNTS, requirements);
	}

	/**
	 * Calculates a facet summary for all faceted references in the query result, counting only entities matching the current filter. You can filter and order which facet options appear in the summary and fetch extra data for facet entities or groups.
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         filterBy(attributeEquals("visible", true)),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("4f94a019e1179ee56779f5a8056826e2")
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FilterBy facetFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(FacetStatisticsDepth.COUNTS, facetFilterBy, null, facetOrderBy, null, requirements);
	}

	/**
	 * Triggers calculation of a facet summary, providing statistics for all faceted references in the schema, scoped to the current query's result set. You can filter and order which facet options and groups appear in the summary using `filterBy`, `filterGroupBy`, and `orderGroupBy`, while controlling loaded data for facet entities via fetch requirements. Filters only affect the summary's contents, not the counted entities.
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("2f32030f266096c171cd31b0e05d2124")
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
	 * Triggers calculation of a facet summary with statistics for all faceted references in the current query scope, applying optional group filtering, facet and group ordering, and fetch requirements for facet and group entities. Filtering and ordering apply only to the summary output, not the counted entities. Only one entity and one group fetch constraint may be supplied.
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
	@SourceHash("8b3c2e198f6187dc45a93a26becd98d6")
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
	 * Triggers calculation of facet statistics for all faceted references in the entity schema, reflecting only entities matching the current query. Allows filtering and ordering of facet options and groups, and controls which data is loaded for facet and group entities. Use to power faceted navigation UIs with accurate counts and customizable result structure.
	 *
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
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("0a58ffb6515ee08d750599e11201c1be")
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
	 * Calculates a hierarchical summary of all faceted references in the current query result, counting how many entities match each facet option and grouping them by reference and group. You can filter or order which facets and groups appear, and specify what data to fetch for facet entities and their groups. Filters only affect summary visibility, not entity counts. For reference-specific logic, use `facetSummaryOfReference`.
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
	@SourceHash("f9d53f9ecd3487f6afa338477df4722d")
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
	 * Triggers calculation of a facet summary extra result, providing statistics for all faceted references in the schema, filtered to entities matching the current query. Supports filtering and ordering of facet options/groups, and controls which data is loaded for facet/group entities. Use filters to limit summary size; filters only affect which facets are shown, not entity counts.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummary(
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
	@SourceHash("f4d86828957f8b15f13364d7024cc351")
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(FacetStatisticsDepth.COUNTS, filterBy, facetGroupFilterBy, null, null, requirements);
	}

	/**
	 * Triggers calculation of facet statistics for all faceted references in the result set, organizing data by reference, group, and facet option. Allows sorting of facet options and groups, and can fetch additional data for facet entities or groups. Only one fetch constraint per type is allowed.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("e1871e1bf32ae260c57f403fb7a4de4b")
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(FacetStatisticsDepth.COUNTS, null, null, orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary for all faceted references in the current query scope, counting only entities matching the main filter. You can filter, order, and control which data is fetched for facet entities or groups. Filters here affect only the summary, not the main result.
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
	@SourceHash("c4762843267c315836c65b05e9558d67")
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FilterBy filterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(FacetStatisticsDepth.COUNTS, filterBy, null, null, null, requirements);
	}

	/**
	 * Triggers calculation of a facet summary with statistics for all faceted references in the result, applying the given facet ordering and optional fetch/filter/group constraints. Only entities matching the current query are counted; see docs for cross-reference rules.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummary(
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
	@SourceHash("735697c3bfa23f9c815f657787ace946")
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(FacetStatisticsDepth.COUNTS, null, null, orderBy, null, requirements);
	}

	/**
	 * Triggers calculation of a facet summary for all faceted references, showing statistics (counts by default) for each facet group and option, but only for entities matching the current query. Use `facetGroupFilterBy` to filter which facet groups appear in the summary. Attach at most one `entityFetch` and one `entityGroupFetch` to control loaded data for facets and groups.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("a088b97fb0147df77153b6fcf7e4975a")
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(FacetStatisticsDepth.COUNTS, null, facetGroupFilterBy, null, null, requirements);
	}

	/**
	 * Triggers calculation of facet statistics for all faceted references in the result, organizing them by reference and group, and allows sorting facet groups via `orderGroupBy`. Attach fetch or filter constraints to control loaded data or limit summary size.
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         orderGroupBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("1ebd4c7d2fe7d841e5b043c9bd6d2176")
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(FacetStatisticsDepth.COUNTS, null, null, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Triggers calculation of facet statistics for all faceted references in the current query scope, organizing results by reference, group, and option. Allows filtering facet groups, ordering facet options, and specifying fetch requirements for facet entities and groups.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         filterGroupBy(attributeEquals("visible", true)),
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
	@SourceHash("64b5751fa5425f656a8b0f55a040b7fa")
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(FacetStatisticsDepth.COUNTS, null, facetGroupFilterBy, orderBy, null, requirements);
	}

	/**
	 * Triggers calculation of facet statistics for all faceted references in the entity schema, reflecting only entities matching the current query. Allows filtering and ordering of facet groups, and supports fetching additional data for facet/group entities. Use to power faceted navigation UIs.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummary(
	 *         filterBy(attributeStartsWith("code", "a")),
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
	@SourceHash("c6ee01706e5e912dc76ab256ce5a4f02")
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FilterBy filterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(FacetStatisticsDepth.COUNTS, filterBy, null, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a summary of all faceted references in the result set, grouping facet options and groups, and allowing you to filter and order which facet groups appear in the summary. Attach at most one entity/group fetch constraint each; filters only affect summary visibility, not counts.
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
	@SourceHash("91839eb46ddb6234b40eac61e0c0f33c")
	@Nonnull
	static FacetSummary facetSummary(
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return facetSummary(FacetStatisticsDepth.COUNTS, null, facetGroupFilterBy, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary for all faceted references in the current query scope, returning statistics (counts by default) for each facet option and group. Optionally filters and orders facet options/groups, and fetches extra data for facet entities. Use filters to limit summary size; filters only affect which facets appear, not entity counts. For reference-specific customization, use `facetSummaryOfReference`.
	 *
	 * ```evitaql
	 * facetSummary(
	 *     filterBy(attributeStartsWith("code", "a")),
	 *     filterGroupBy(attributeEquals("visible", true)),
	 *     orderBy(attributeNatural("name", ASC)),
	 *     orderGroupBy(attributeNatural("name", ASC)),
	 *     entityFetch(attributeContent("name")),
	 *     entityGroupFetch(attributeContent("name"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary)
	 *
	 * @see io.evitadb.api.query.require.FacetSummary
	 */
	@SourceHash("c6de73febd52604de3cbb21f8844bdcb")
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
			requirements
		);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Only the specified reference is affected; all filter, order, and fetch requirements target properties of the referenced entity. Use this to customize facet stats, filtering, and loading for one reference without impacting others.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("4e78aea8ee1ee28e8902026726e66c9b")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(@Nullable String referenceName, @Nullable EntityFetchRequire... requirements) {
		return referenceName == null ? null : new FacetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Lets you specify the statistics depth (`COUNTS` or `IMPACT`) and attach fetch requirements for facet or group entities. Only affects the targeted reference; other references remain unaffected unless they have their own `facetSummaryOfReference`. Filter and order constraints can reference any property of the referenced entity.
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
	@SourceHash("5428761dbfd45b28757eda959aba0f9e")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(@Nullable String referenceName, @Nullable FacetStatisticsDepth statisticsDepth, @Nullable EntityFetchRequire... requirements) {
		if (referenceName == null) {
			return null;
		}
		return statisticsDepth == null ?
			new FacetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, requirements) :
			new FacetSummaryOfReference(referenceName, statisticsDepth, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Lets you specify statistics depth (`COUNTS` or `IMPACT`), and apply filters, ordering, and fetch requirements specific to the referenced entity type. Use when you need per-reference facet control or want to filter/sort facet options by reference-specific properties.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         IMPACT,
	 *         filterBy(attributeEquals("country", "US")),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("6ab9542b9d85af05d91b60366c8a9f65")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy facetFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, facetFilterBy, null, facetOrderBy, null, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Lets you specify statistics depth, filters, group filters, group ordering, and fetch requirements targeting properties of the referenced entity only.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
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
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("ede4b947263da7ce86196d6c60e0357f")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, filterBy, facetGroupFilterBy, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Lets you specify statistics depth, group filtering, and ordering using properties of the referenced entity. Filters and sorting apply only to the targeted reference, not others.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         COUNTS,
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
	@SourceHash("d0617b06c20ac9a3e2d0f13bcb4b26c5")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, null, facetGroupFilterBy, orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Lets you control statistics depth (`COUNTS` or `IMPACT`), filter and order facet options/groups using any property of the referenced entity, and specify fetch requirements for facet and group entities. Constraints are never merged—per-reference settings fully replace generic ones for the targeted reference.
	 *
	 * ```evitaql
	 * facetSummaryOfReference(
	 *     "brand",
	 *     IMPACT,
	 *     filterBy(attributeContains("name", "eco")),
	 *     orderBy(attributeNatural("name", ASC)),
	 *     orderGroupBy(attributeNatural("name", ASC)),
	 *     entityFetch(attributeContent("name"))
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("176b2cdd5fc52b3f5a1ad62f8462afc0")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, filterBy, null, orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary for a specific reference (e.g., `"brand"`), overriding any generic `facetSummary` for that reference. Lets you set statistics depth (`COUNTS` or `IMPACT`), filter and order facet options/groups using properties of the referenced entity, and control which attributes are fetched. Filters/orderings apply only to the targeted reference; constraints are not merged with generic settings.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         IMPACT,
	 *         filterBy(attributeContains("name", "eco")),
	 *         null,
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
	@SourceHash("73f6e0a71ebf0b693819dff4a0cd0780")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, filterBy, facetGroupFilterBy, orderBy, null, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Lets you specify statistics depth, filters, and fetches tailored to the referenced entity type—ideal for fine-grained control over which facets and groups appear and what data is loaded.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "parameterValues",
	 *         IMPACT,
	 *         filterBy(attributeContains("code", "memory")),
	 *         filterGroupBy(attributeInSet("code", "ram-memory", "rom-memory")),
	 *         entityFetch(attributeContent("code", "name"))
	 *     )
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("5ef41365a00ff03111ceb4881cd13d82")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, filterBy, facetGroupFilterBy, null, null, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Lets you specify statistics depth, sorting of facet options and groups, and fetch requirements for facet and group entities. All filter/order constraints target properties of the referenced entity only.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("8c892f31d6e474ce5e89a913ea06e24e")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, null, null, orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference; lets you specify statistics depth, filter and fetch constraints tailored to the referenced entity type, and supports custom filtering, ordering, and fetches for facet and group entities.
	 *
	 * ```evitaql
	 * facetSummaryOfReference(
	 *     "brand",
	 *     IMPACT,
	 *     filterBy(attributeContains("name", "eco")),
	 *     entityFetch(attributeContent("name"))
	 * )
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("1a58d772bd7fbf065ea3c99431655b24")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, filterBy, null, null, null, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Lets you specify statistics depth, custom ordering, and fetch requirements for facet entities. Only affects the targeted reference; constraints are not merged with the generic summary.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("35fd4a069541bd5f7274f0fabe2009b9")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, null, null, orderBy, null, requirements);
	}

	/**
	 * Calculates facet summary statistics for a single named reference, overriding any generic `facetSummary` for that reference. Lets you specify the statistics depth (COUNTS or IMPACT), filter groups, and fetch requirements for facet entities or groups. Other faceted references remain unaffected unless targeted by their own `facetSummaryOfReference`.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("af266a1d02473f5b090094185517ab59")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, null, facetGroupFilterBy, null, null, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Lets you specify statistics depth (`COUNTS` or `IMPACT`), group ordering, and fetch requirements for facet/group entities. Constraints are never merged with the generic summary; only the targeted reference is affected.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("d7c25389f3bdb9bb2dcc8cf95c42f7ad")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, null, null, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Lets you specify statistics depth, group filtering, ordering, and entity fetches tailored to the referenced entity type. Use for fine-grained, per-reference facet control.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         IMPACT,
	 *         filterGroupBy(attributeInSet("code", "premium", "budget")),
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
	@SourceHash("c6b5a5ac705187d90d04b303747a2449")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, null, facetGroupFilterBy, orderBy, null, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Lets you specify the statistics depth, filter/group/order constraints, and fetch requirements tailored to properties of the referenced entity. Other references remain unaffected unless explicitly targeted.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         IMPACT,
	 *         filterBy(attributeEquals("country", "US")),
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
	@SourceHash("c58b1640e014dab92defe49ec6fe08ce")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy filterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, filterBy, null, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates facet statistics for a single named reference, overriding any generic `facetSummary` for that reference. Lets you set the statistics depth, filter and order facet groups, and specify fetch requirements for facet/group entities. Other references remain unaffected unless they have their own `facetSummaryOfReference`.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         IMPACT,
	 *         filterGroupBy(attributeEquals("type", "premium")),
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
	@SourceHash("426705d57190ebf38a205f0dc0236ce0")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, statisticsDepth, null, facetGroupFilterBy, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Lets you specify statistics depth, filter and order facet options/groups, and control which attributes are fetched for facet and group entities. Filters and ordering target properties of the referenced entity only. Use when you need per-reference facet logic or richer filtering than the generic summary allows.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
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
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("dcfe8f70833591fb61121d4bca919a25")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FacetStatisticsDepth statisticsDepth,
		@Nullable FilterBy facetFilterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		if (referenceName == null) {
			return null;
		}
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
	 * Calculates a facet summary for a single named reference, overriding or replacing any generic `facetSummary` for that reference. Allows custom filtering and ordering of facet options using any filterable/sortable property of the referenced entity. Attach at most one `EntityFetch` and one `EntityGroupFetch`. Other references are unaffected unless specified separately.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         filterBy(attributeEquals("code", "amazon")),
	 *         orderBy(attributeNatural("name", ASC)),
	 *         entityFetch(attributeContent("name"))
	 *     )
	 * )
	 * ```
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("905fb2301a5afb116c26e9449495add1")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FilterBy facetFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, facetFilterBy, null, facetOrderBy, null, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Lets you filter, group, and order facet options/groups using any property of the referenced entity, and attach fetch requirements for facet/group entities.
	 *
	 * ```evitaql
	 * require(
	 *     facetSummaryOfReference(
	 *         "brand",
	 *         filterBy(attributeEquals("country", "US")),
	 *         filterGroupBy(attributeInSet("type", "premium")),
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
	@SourceHash("486a5b3c38a00683af151e5bb61143d4")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, filterBy, facetGroupFilterBy, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Lets you filter and order facet options/groups using any property of the referenced entity, and attach fetch requirements for facets or groups. Other references remain unaffected unless specified.
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("12ecd008112f34be1dfd9f310c9e2232")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, null, facetGroupFilterBy, orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Allows custom filtering and ordering of facet options/groups using properties of the referenced entity. Only one `EntityFetch` and one `EntityGroupFetch` allowed.
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
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("5de1172be901e054d321545897f1620a")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, filterBy, null, orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Allows custom filtering and ordering on properties of the referenced entity, and supports attaching fetch requirements for facet or group entities. Other faceted references remain unaffected unless explicitly targeted.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("b93463f355149ed7151e950b091ec8e6")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, filterBy, facetGroupFilterBy, orderBy, null, requirements);
	}

	/**
	 * Calculates the facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Allows custom filtering and grouping on properties of the referenced entity, and supports additional entity fetch requirements. Other faceted references remain unaffected unless explicitly targeted.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("418c09ce1e225ce245964b05e749ded5")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, filterBy, facetGroupFilterBy, null, null, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Lets you specify custom ordering of facet options and groups, plus entity fetch requirements, all targeting properties of the referenced entity type. Other references remain unaffected unless explicitly configured.
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("cc63437a2bf6810996f3d4af9d62adad")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, null, null, orderBy, facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary for a specific named reference, overriding any generic `facetSummary` for that reference. Lets you filter which facet options appear and control which attributes are fetched for facet entities. Only affects the targeted reference; others remain unchanged.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("c64dd53142a228f969efbe2f09512ee0")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, filterBy, null, null, null, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Allows custom ordering and fetch requirements specific to the referenced entity type. Filters and orders can only target properties of the referenced entity, not the reference relation itself.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("e7fb5befe389be22453a614a76ff05a5")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, null, null, orderBy, null, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Lets you filter which facet groups appear and specify what data to fetch for facet or group entities. Other references are unaffected.
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
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("a3263ecefbb83a89e409012c13a66fdf")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, null, facetGroupFilterBy, null, null, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Lets you specify group ordering and fetch requirements for facet/group entities, targeting only the referenced entity's properties. Other references remain unaffected unless explicitly configured.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("26ec2cd37ebe49e23f82ec5f306c167b")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, null, null, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Allows custom filtering and ordering of facet options and groups using properties of the referenced entity. Only one `EntityFetch` and one `EntityGroupFetch` may be attached. Other references remain unaffected unless specified.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("0d722532dc70ce0b4a414e2442a6013e")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy orderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, null, facetGroupFilterBy, orderBy, null, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Allows filtering and ordering facet groups by properties of the referenced entity, and supports custom entity/group fetch requirements. Other faceted references are unaffected unless targeted separately.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("467570c5c779d8c4cc8feb9902abac4d")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FilterBy filterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, filterBy, null, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference. Allows fine-grained filtering and ordering of facet groups based on properties of the referenced entity, and supports entity/group fetch requirements for tailored data loading.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/facet#facet-summary-of-reference)
	 *
	 * @see io.evitadb.api.query.require.FacetSummaryOfReference
	 */
	@SourceHash("cc477fcbfb6476dd21cf22770ee9d791")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		return referenceName == null ? null :
			facetSummaryOfReference(referenceName, FacetStatisticsDepth.COUNTS, null, facetGroupFilterBy, null, facetGroupOrderBy, requirements);
	}

	/**
	 * Calculates a facet summary for a single named reference, overriding any generic `facetSummary` for that reference and allowing custom filtering, grouping, and ordering on properties of the referenced entity. Use this to target one reference with unique rules and fetches.
	 *
	 * ```evitaql
	 * facetSummaryOfReference(
	 *     "brand",
	 *     filterBy(attributeEquals("isActive", true)),
	 *     filterGroupBy(attributeInSet("type", "premium")),
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
	@SourceHash("89334d19a21bd5d49286428fbce66549")
	@Nullable
	static FacetSummaryOfReference facetSummaryOfReference(
		@Nullable String referenceName,
		@Nullable FilterBy facetFilterBy,
		@Nullable FilterGroupBy facetGroupFilterBy,
		@Nullable OrderBy facetOrderBy,
		@Nullable OrderGroupBy facetGroupOrderBy,
		@Nullable EntityFetchRequire... requirements
	) {
		if (referenceName == null) {
			return null;
		}
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
	 * Enables detailed query execution metrics, returning a hierarchical breakdown of all processing phases (parsing, filtering, etc.), their arguments, timings, and sub-steps in the extra-results. Explicitly include for debugging or profiling; avoid in production due to overhead.
	 *
	 * ```evitaql
	 * queryTelemetry()
	 * ```
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/debug#query-telemetry)
	 *
	 * @see io.evitadb.api.query.require.QueryTelemetry
	 */
	@SourceHash("70c00683316e940545a00ee784f5d65d")
	@Nonnull
	static QueryTelemetry queryTelemetry() {
		return new QueryTelemetry();
	}

	/**
	 * This `debug` require is targeted for internal purposes only and is not exposed in public evitaDB API.
	*/
	@Nullable
	static Debug debug(@Nullable DebugMode... debugMode) {
		return ArrayUtils.isEmptyOrItsValuesNull(debugMode) ? null : new Debug(debugMode);
	}

	/**
	 * Fetches the full entity body from storage, including locale, scope, and schema reference, but omits attributes, associated data, prices, and references unless specified by sub-requirements. Use to retrieve basic entity details beyond just primary keys.
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#entity-fetch)
	 *
	 * @see io.evitadb.api.query.require.EntityFetch
	 */
	@SourceHash("e320ca1d7e1b3c7d5f8de55d0faba58f")
	@Nonnull
	static EntityFetch entityFetchAll() {
		return entityFetch(
			attributeContentAll(), hierarchyContent(),
			associatedDataContentAll(), priceContentAll(),
			referenceContentAllWithAttributes(), dataInLocalesAll()
		);
	}

	/**
	 * Retrieves the bodies of group entities associated with references, analogous to `entityFetch` but for reference groups. Used within `referenceContent`, this variant loads only the group entity body, omitting attributes, associated data, prices, and other containers.
	 *
	 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/fetching#entity-group-fetch)
	 *
	 * @see io.evitadb.api.query.require.EntityGroupFetch
	 */
	@SourceHash("e148e3bff43d736d71b1f036c6165379")
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
