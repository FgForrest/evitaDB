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

package io.evitadb.api.query;

import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.head.Collection;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.query.visitor.PrettyPrintingVisitor;
import io.evitadb.api.query.visitor.PrettyPrintingVisitor.StringWithParameters;
import io.evitadb.api.query.visitor.QueryPurifierVisitor;
import io.evitadb.utils.PrettyPrintable;
import lombok.EqualsAndHashCode;
import lombok.EqualsAndHashCode.CacheStrategy;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * The primary query object for evitaDB, representing a complete EvitaQL (evita Query Language) statement.
 * A `Query` encapsulates all aspects of data retrieval: which entities to fetch, how to filter them, in what
 * order to return them, and how much data to include in the results.
 *
 * **Query Structure**
 *
 * evitaDB queries are composed of four optional sections, each serving a distinct purpose:
 *
 * 1. **Head** ({@link #getHead()}, {@link #getCollection()}): Specifies the target entity collection and query metadata.
 *    The collection name is mandatory for most operations. Example: `collection("Product")`
 *
 * 2. **FilterBy** ({@link #getFilterBy()}): Defines which entities match the query (analogous to SQL's WHERE clause).
 *    If omitted, all entities in the collection are returned. Example: `filterBy(attributeEquals("code", "PHONE-123"))`
 *
 * 3. **OrderBy** ({@link #getOrderBy()}): Specifies the result ordering (analogous to SQL's ORDER BY clause).
 *    If omitted, entities are ordered by primary key in ascending order. Example: `orderBy(attributeNatural("name", ASC))`
 *
 * 4. **Require** ({@link #getRequire()}): Controls data fetching depth, pagination, and extra computations like
 *    facet statistics or histograms. If omitted, only entity primary keys are returned.
 *    Example: `require(page(1, 20), entityFetch(attributeContent()))`
 *
 * **EvitaQL Syntax**
 *
 * The query language is function-based with nested structure:
 * - Functions have names and arguments in parentheses: `functionName(arg1, arg2, ...)`
 * - Arguments can be scalars (strings, numbers) or nested functions (constraints)
 * - String literals are single-quoted: `'Product'`
 * - Multiple arguments/constraints are comma-separated
 *
 * Example complete query:
 * ```
 * query(
 *     collection('Product'),
 *     filterBy(
 *         and(
 *             attributeEquals('code', 'PHONE-123'),
 *             priceInCurrency('USD')
 *         )
 *     ),
 *     orderBy(
 *         priceNatural(ASC)
 *     ),
 *     require(
 *         page(1, 20),
 *         entityFetch(
 *             attributeContent('code', 'name'),
 *             priceContent()
 *         )
 *     )
 * )
 * ```
 *
 * **Programmatic Query Construction**
 *
 * Queries are typically constructed using Java DSL via {@link QueryConstraints} static methods:
 * ```java
 * Query query = query(
 *     collection("Product"),
 *     filterBy(
 *         and(
 *             attributeEquals("code", "PHONE-123"),
 *             priceInCurrency("USD")
 *         )
 *     ),
 *     orderBy(priceNatural(ASC)),
 *     require(
 *         page(1, 20),
 *         entityFetch(
 *             attributeContent("code", "name"),
 *             priceContent()
 *         )
 *     )
 * );
 * ```
 *
 * **Factory Methods**
 *
 * The class provides numerous static factory methods ({@code query(...)}) accepting different combinations of
 * constraints for convenience. All combinations of head, filter, order, and require constraints are supported,
 * allowing omission of any section.
 *
 * **Query Normalization**
 *
 * Queries can be normalized via {@link #normalizeQuery()} to remove:
 * - Inapplicable constraints (missing required arguments)
 * - Unnecessary containers (single-child wrappers)
 * - Redundant nesting
 *
 * Normalization produces a leaner, more efficient query representation. The normalized state is cached to avoid
 * repeated normalization overhead. Query normalization is performed automatically by the query engine before
 * execution.
 *
 * **String Representation**
 *
 * Queries support conversion to EvitaQL string syntax via {@link #toString()} (compact) or {@link #prettyPrint()}
 * (formatted with indentation). The {@link #toStringWithParameterExtraction()} method enables parameterized
 * query strings for prepared statement-like usage patterns.
 *
 * **Immutability**
 *
 * `Query` instances are immutable. All fields are final, and normalization produces a new instance rather than
 * modifying the original. This enables safe sharing of queries across threads and query reuse without side effects.
 *
 * **Serializability**
 *
 * Queries are serializable for transmission across network boundaries (gRPC, REST APIs). All constraint types
 * and arguments are guaranteed to be serializable.
 *
 * **Thread Safety**
 *
 * Instances are immutable and thread-safe. The same query instance can be executed concurrently by multiple
 * threads without synchronization.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ThreadSafe
@EqualsAndHashCode(of = {"head", "filterBy", "orderBy", "require"}, cacheStrategy = CacheStrategy.LAZY)
public class Query implements Serializable, PrettyPrintable {
	@Serial private static final long serialVersionUID = -1797234436133920949L;

	/**
	 * Head section containing collection specification and query metadata. May be null if only a filter/order/require
	 * is specified without a collection target (used in nested queries).
	 */
	@Nullable private final HeadConstraint head;
	/**
	 * Filter section defining which entities match the query. If null, all entities in the collection are returned.
	 */
	@Nullable private final FilterBy filterBy;
	/**
	 * Order section defining result sorting. If null, entities are ordered by primary key in ascending order.
	 */
	@Nullable private final OrderBy orderBy;
	/**
	 * Require section controlling data fetching and extra computations. If null, only primary keys are returned.
	 */
	@Nullable private final Require require;
	/**
	 * Tracks whether this query instance has been normalized. Normalization is expensive, so we cache the result
	 * to avoid redundant processing. This field is mutable for caching purposes but doesn't affect equality.
	 */
	private boolean normalized;

	/**
	 * Private constructor accepting all four query sections. Use public static factory methods to create instances.
	 *
	 * @param head the head constraint (collection specification), may be null
	 * @param filterBy the filter constraint (WHERE clause), may be null
	 * @param orderBy the order constraint (ORDER BY clause), may be null
	 * @param require the require constraint (data fetching and computations), may be null
	 */
	private Query(@Nullable HeadConstraint head, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable Require require) {
		this.head = head;
		this.filterBy = filterBy;
		this.orderBy = orderBy;
		this.require = require;
		this.normalized = false;
	}

	/*
		SHORTHAND FACTORY METHODS

		These factory methods provide convenient ways to create queries with different combinations of sections.
		All combinations of head, filter, order, and require are supported to avoid forcing callers to pass nulls.
	 */

	/**
	 * Creates a query with only a filter section (no head, order, or require).
	 *
	 * @param filter the filter constraint
	 * @return a new Query instance
	 */
	public static Query query(@Nullable FilterBy filter) {
		return new Query(null, filter, null, null);
	}

	/** Creates a query with filter and order sections. */
	public static Query query(@Nullable FilterBy filter, @Nullable OrderBy order) {
		return new Query(null, filter, order, null);
	}

	/** Creates a query with filter, order, and require sections. */
	public static Query query(@Nullable FilterBy filter, @Nullable OrderBy order, @Nullable Require require) {
		return new Query(null, filter, order, require);
	}

	/** Creates a query with filter and require sections. */
	public static Query query(@Nullable FilterBy filter, @Nullable Require require) {
		return new Query(null, filter, null, require);
	}

	/** Creates a query with only a head section (collection specification). */
	public static Query query(@Nullable HeadConstraint head) {
		return new Query(head, null, null, null);
	}

	/** Creates a query with head and filter sections. */
	public static Query query(@Nullable HeadConstraint head, @Nullable FilterBy filter) {
		return new Query(head, filter, null, null);
	}

	/** Creates a query with head, filter, and order sections. */
	public static Query query(@Nullable HeadConstraint head, @Nullable FilterBy filter, @Nullable OrderBy order) {
		return new Query(head, filter, order, null);
	}

	/**
	 * Creates a complete query with all four sections.
	 * This is the most comprehensive factory method.
	 *
	 * @param head the head constraint (collection specification)
	 * @param filter the filter constraint (WHERE clause)
	 * @param order the order constraint (ORDER BY clause)
	 * @param require the require constraint (data fetching and computations)
	 * @return a new Query instance
	 */
	public static Query query(@Nullable HeadConstraint head, @Nullable FilterBy filter, @Nullable OrderBy order, @Nullable Require require) {
		return new Query(head, filter, order, require);
	}

	/**
	 * Creates a complete query with all four sections (alternative parameter order).
	 * Provided for convenience when order and require parameters are swapped.
	 */
	public static Query query(@Nullable HeadConstraint head, @Nullable FilterBy filter, @Nullable Require require, @Nullable OrderBy order) {
		return new Query(head, filter, order, require);
	}

	/** Creates a query with head and order sections. */
	public static Query query(@Nullable HeadConstraint head, @Nullable OrderBy order) {
		return new Query(head, null, order, null);
	}

	/** Creates a query with head, order, and require sections. */
	public static Query query(@Nullable HeadConstraint head, @Nullable OrderBy order, @Nullable Require require) {
		return new Query(head, null, order, require);
	}

	/** Creates a query with head, filter, and require sections. */
	public static Query query(@Nullable HeadConstraint head, @Nullable FilterBy filter, @Nullable Require require) {
		return new Query(head, filter, null, require);
	}

	/** Creates a query with head and require sections. */
	public static Query query(@Nullable HeadConstraint head, @Nullable Require require) {
		return new Query(head, null, null, require);
	}

	/**
	 * Returns the head constraint containing collection specification and query metadata.
	 *
	 * @return the head constraint, or null if no head section was specified
	 */
	@Nullable
	public HeadConstraint getHead() {
		return this.head;
	}

	/**
	 * Returns the collection name that this query targets.
	 *
	 * This is a convenience method that extracts the {@link Collection} constraint from the head section.
	 * The collection name determines which entity type will be queried.
	 *
	 * @return the collection constraint if present, null otherwise
	 */
	@Nullable
	public Collection getCollection() {
		return this.head == null ? null : QueryUtils.findConstraint(this.head, Collection.class);
	}

	/**
	 * Returns the filter constraint that narrows which entities are returned.
	 *
	 * This corresponds to SQL's WHERE clause. If null, all entities in the collection are returned.
	 *
	 * @return the filter constraint, or null if no filtering is specified
	 */
	@Nullable
	public FilterBy getFilterBy() {
		return this.filterBy;
	}

	/**
	 * Returns the order constraint that controls result sorting.
	 *
	 * This corresponds to SQL's ORDER BY clause. If null, entities are ordered by primary key in
	 * ascending order.
	 *
	 * @return the order constraint, or null if no ordering is specified
	 */
	@Nullable
	public OrderBy getOrderBy() {
		return this.orderBy;
	}

	/**
	 * Returns the require constraint that controls data fetching and extra computations.
	 *
	 * This section specifies:
	 * - How much entity data to fetch (attributes, prices, references, etc.)
	 * - Pagination settings (page number, page size)
	 * - Extra results to compute (facet statistics, histograms, parent lookups)
	 *
	 * If null, only entity primary keys are returned.
	 *
	 * @return the require constraint, or null if no requirements are specified
	 */
	@Nullable
	public Require getRequire() {
		return this.require;
	}

	/**
	 * Normalizes this query by removing inapplicable and unnecessary constraints.
	 *
	 * Normalization performs two optimizations:
	 * 1. **Removes inapplicable constraints**: Constraints with missing required arguments (where
	 *    {@link Constraint#isApplicable()} returns false) are removed from the tree.
	 * 2. **Flattens unnecessary containers**: Container constraints that are not necessary (where
	 *    {@link ConstraintContainer#isNecessary()} returns false — typically single-child containers) are
	 *    removed and their contents are propagated to their parent.
	 *
	 * If this query is already in normalized form, the same instance is returned (no copy is created).
	 * Otherwise, a new normalized query instance is returned. The normalized state is cached to avoid
	 * redundant normalization on subsequent calls.
	 *
	 * This is the simplest normalization method that doesn't apply any custom constraint transformations.
	 *
	 * @return this query if already normalized, or a new normalized copy
	 */
	@Nonnull
	public Query normalizeQuery() {
		return normalizeQuery(null, null, null, null);
	}

	/**
	 * Returns this query or copy of this query without constraints that make no sense or are unnecessary. In other
	 * words - all constraints that has not all required arguments (not {@link Constraint#isApplicable()}) are removed
	 * from the query, all query containers that are {@link ConstraintContainer#isNecessary()} are removed
	 * and their contents are propagated to their parent.
	 *
	 * @deprecated use {@link #normalizeQuery(UnaryOperator, UnaryOperator, UnaryOperator, UnaryOperator)}, this method
	 * is here only to maintain backward compatibility
	 */
	@SuppressWarnings("rawtypes")
	@Deprecated(since = "2024.11", forRemoval = true)
	@Nonnull
	public Query normalizeQuery(
		@Nullable UnaryOperator<Constraint> filterConstraintTranslator,
		@Nullable UnaryOperator<Constraint> orderConstraintTranslator,
		@Nullable UnaryOperator<Constraint> requireConstraintTranslator
	) {
		return normalizeQuery(
			null,
			filterConstraintTranslator,
			orderConstraintTranslator,
			requireConstraintTranslator
		);
	}

	/**
	 * Normalizes this query with custom constraint transformation logic.
	 *
	 * This advanced normalization method allows callers to provide custom transformation functions for each
	 * query section. The translators are applied to each constraint before standard normalization (removing
	 * inapplicable and unnecessary constraints).
	 *
	 * Use cases include:
	 * - Converting between different constraint representations (e.g., internal vs external API formats)
	 * - Applying query rewrite rules for optimization
	 * - Transforming constraints for specific execution contexts
	 *
	 * @param headConstraintTranslator optional transformer for head constraints
	 * @param filterConstraintTranslator optional transformer for filter constraints
	 * @param orderConstraintTranslator optional transformer for order constraints
	 * @param requireConstraintTranslator optional transformer for require constraints
	 * @return this query if already normalized and no transformations are needed, or a new normalized copy
	 */
	/* we need to use raw types because constraint of type A might contain constraints of type B */
	/* i.e. require constraint might contain filtering constraints etc. */
	@SuppressWarnings("rawtypes")
	@Nonnull
	public Query normalizeQuery(
		@Nullable UnaryOperator<Constraint> headConstraintTranslator,
		@Nullable UnaryOperator<Constraint> filterConstraintTranslator,
		@Nullable UnaryOperator<Constraint> orderConstraintTranslator,
		@Nullable UnaryOperator<Constraint> requireConstraintTranslator
	) {
		// short-circuit: avoid costly normalization on already normalized query without translators
		if (this.normalized) {
			return this;
		}

		// apply translators (if provided) and purify each section
		final HeadConstraint normalizedHead = this.head == null ? null : (HeadConstraint) purify(this.head, headConstraintTranslator);
		final FilterBy normalizedFilter = this.filterBy == null ? null : (FilterBy) purify(this.filterBy, filterConstraintTranslator);
		final OrderBy normalizedOrder = this.orderBy == null ? null : (OrderBy) purify(this.orderBy, orderConstraintTranslator);
		final Require normalizedRequire = this.require == null ? null : (Require) purify(this.require, requireConstraintTranslator);

		// if all sections are unchanged (reference equality), mark as normalized and return this instance
		if (
			Objects.equals(this.head, normalizedHead) &&
			Objects.equals(this.filterBy, normalizedFilter) &&
			Objects.equals(this.orderBy, normalizedOrder) &&
			Objects.equals(this.require, normalizedRequire)
		) {
			this.normalized = true;
			return this;
		} else {
			// otherwise create leaner query in normalized form
			final Query normalizedQuery = new Query(normalizedHead, normalizedFilter, normalizedOrder, normalizedRequire);
			normalizedQuery.normalized = true;
			return normalizedQuery;
		}
	}

	/**
	 * Returns a formatted EvitaQL string representation of this query with indentation.
	 *
	 * The output is formatted with tab characters for readability, making it suitable for logging,
	 * debugging, and documentation purposes.
	 *
	 * @return pretty-printed EvitaQL query string with indentation
	 */
	@Nonnull
	public String prettyPrint() {
		return PrettyPrintingVisitor.toString(this, "\t");
	}

	/**
	 * Returns a compact EvitaQL string representation of this query.
	 *
	 * The output is a single-line string without formatting, suitable for compact logging and
	 * serialization.
	 *
	 * @return compact EvitaQL query string
	 */
	@Nonnull
	@Override
	public String toString() {
		return PrettyPrintingVisitor.toString(this);
	}

	/**
	 * Returns an EvitaQL string representation with scalar values extracted as named parameters.
	 *
	 * This method separates the query structure from its scalar arguments, producing:
	 * - A parameterized query string with placeholders (e.g., `?1`, `?2`)
	 * - A map of parameter positions to actual values
	 *
	 * This enables prepared statement-like usage patterns where the query structure can be cached
	 * and reused with different parameter values.
	 *
	 * @return StringWithParameters containing both the parameterized query and the parameter map
	 */
	@Nonnull
	public StringWithParameters toStringWithParameterExtraction() {
		return PrettyPrintingVisitor.toStringWithParameterExtraction(this);
	}

	/**
	 * Purifies a constraint tree by removing inapplicable/unnecessary constraints and optionally applying
	 * a custom transformation function.
	 *
	 * @param constraint the constraint tree to purify
	 * @param translator optional transformation function to apply to each constraint
	 * @return the purified constraint tree, or null if the entire tree becomes inapplicable
	 */
	/* we need to use raw types because constraint of type A might contain constraints of type B */
	/* i.e. require constraint might contain filtering constraints etc. */
	@Nullable
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Constraint purify(@Nonnull Constraint constraint, @Nullable UnaryOperator<Constraint> translator) {
		return QueryPurifierVisitor.purify(constraint, translator);
	}

}
