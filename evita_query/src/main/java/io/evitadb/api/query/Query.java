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
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Main transfer object for Evita Query Language. Contains all data and conditions that query what entities will
 * be queried, in what order and how rich the returned results will be.
 *
 * evitaDB query language is composed of nested set of functions. Each function has its name and set of arguments inside
 * round brackets. Arguments and functions are delimited by a comma. Strings are enveloped inside apostrophes. This language
 * is expected to be used by human operators, on the code level query is represented by a query object tree, that can
 * be constructed directly without intermediate string language form. For the sake of documentation human readable form
 * is used here.
 *
 * Query has these four parts:
 *
 * - {@link #getCollection()}: contains collection (mandatory) specification
 * - {@link #getFilterBy()}: contains constraints limiting entities being returned (optional, if missing all are returned)
 * - {@link #getOrderBy()}: defines in what order will the entities return (optional, if missing entities are ordered by primary integer
 * key in ascending order)
 * - {@link #getRequire()}: contains additional information for the query engine, may hold pagination settings, richness of the entities
 * and so on (optional, if missing only primary keys of all the entities are returned)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(of = {"head", "filterBy", "orderBy", "require"}, cacheStrategy = CacheStrategy.LAZY)
public class Query implements Serializable, PrettyPrintable {
	@Serial private static final long serialVersionUID = -1797234436133920949L;

	@Nullable private final HeadConstraint head;
	@Nullable private final FilterBy filterBy;
	@Nullable private final OrderBy orderBy;
	@Nullable private final Require require;
	private boolean normalized;

	private Query(@Nullable HeadConstraint head, @Nullable FilterBy filterBy, @Nullable OrderBy orderBy, @Nullable Require require) {
		this.head = head;
		this.filterBy = filterBy;
		this.orderBy = orderBy;
		this.require = require;
		this.normalized = false;
	}

	/*
		SHORTHAND FACTORY METHODS
	 */

	public static Query query(@Nullable FilterBy filter) {
		return new Query(null, filter, null, null);
	}

	public static Query query(@Nullable FilterBy filter, @Nullable OrderBy order) {
		return new Query(null, filter, order, null);
	}

	public static Query query(@Nullable FilterBy filter, @Nullable OrderBy order, @Nullable Require require) {
		return new Query(null, filter, order, require);
	}

	public static Query query(@Nullable FilterBy filter, @Nullable Require require) {
		return new Query(null, filter, null, require);
	}

	public static Query query(@Nullable HeadConstraint head) {
		return new Query(head, null, null, null);
	}

	public static Query query(@Nullable HeadConstraint head, @Nullable FilterBy filter) {
		return new Query(head, filter, null, null);
	}

	public static Query query(@Nullable HeadConstraint head, @Nullable FilterBy filter, @Nullable OrderBy order) {
		return new Query(head, filter, order, null);
	}

	public static Query query(@Nullable HeadConstraint head, @Nullable FilterBy filter, @Nullable OrderBy order, @Nullable Require require) {
		return new Query(head, filter, order, require);
	}

	public static Query query(@Nullable HeadConstraint head, @Nullable FilterBy filter, @Nullable Require require, @Nullable OrderBy order) {
		return new Query(head, filter, order, require);
	}

	public static Query query(@Nullable HeadConstraint head, @Nullable OrderBy order) {
		return new Query(head, null, order, null);
	}

	public static Query query(@Nullable HeadConstraint head, @Nullable OrderBy order, @Nullable Require require) {
		return new Query(head, null, order, require);
	}

	public static Query query(@Nullable HeadConstraint head, @Nullable FilterBy filter, @Nullable Require require) {
		return new Query(head, filter, null, require);
	}

	public static Query query(@Nullable HeadConstraint head, @Nullable Require require) {
		return new Query(head, null, null, require);
	}

	/**
	 * Returns head constraint that usually contains collection specification.
	 */
	@Nullable
	public HeadConstraint getHead() {
		return this.head;
	}

	/**
	 * Returns type of entity that is expected to be returned. Determines the primary entity collection the query
	 * will be processed against.
	 */
	@Nullable
	public Collection getCollection() {
		return this.head == null ? null : QueryUtils.findConstraint(this.head, Collection.class);
	}

	/**
	 * Returns filter that will be used for narrowing entities in the result.
	 */
	@Nullable
	public FilterBy getFilterBy() {
		return this.filterBy;
	}

	/**
	 * Returns query that will be used to control order or the entities in the result.
	 */
	@Nullable
	public OrderBy getOrderBy() {
		return this.orderBy;
	}

	/**
	 * Returns query that drives entity depth or computation extra results along with the query.
	 */
	@Nullable
	public Require getRequire() {
		return this.require;
	}

	/**
	 * Returns this query or copy of this query without constraints that make no sense or are unnecessary. In other
	 * words - all constraints that has not all required arguments (not {@link Constraint#isApplicable()}) are removed
	 * from the query, all query containers that are {@link ConstraintContainer#isNecessary()} are removed
	 * and their contents are propagated to their parent.
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
	 * Returns this query or copy of this query without constraints that make no sense or are unnecessary. In other
	 * words - all constraints that has not all required arguments (not {@link Constraint#isApplicable()}) are removed
	 * from the query, all query containers that are {@link ConstraintContainer#isNecessary()} are removed
	 * and their contents are propagated to their parent.
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
		// avoid costly normalization on already normalized query
		if (this.normalized) {
			return this;
		}

		final HeadConstraint normalizedHead = this.head == null ? null : (HeadConstraint) purify(this.head, headConstraintTranslator);
		final FilterBy normalizedFilter = this.filterBy == null ? null : (FilterBy) purify(this.filterBy, filterConstraintTranslator);
		final OrderBy normalizedOrder = this.orderBy == null ? null : (OrderBy) purify(this.orderBy, orderConstraintTranslator);
		final Require normalizedRequire = this.require == null ? null : (Require) purify(this.require, requireConstraintTranslator);

		// if normalized constraint are same as originals, this query is in normalized form
		if (Objects.equals(this.filterBy, normalizedFilter) && Objects.equals(this.orderBy, normalizedOrder) && Objects.equals(this.require, normalizedRequire)) {
			this.normalized = true;
			return this;
		} else {
			// otherwise create leaner query in normalized form
			final Query normalizedQuery = new Query(normalizedHead, normalizedFilter, normalizedOrder, normalizedRequire);
			normalizedQuery.normalized = true;
			return normalizedQuery;
		}
	}

	@Nonnull
	public String prettyPrint() {
		return PrettyPrintingVisitor.toString(this, "\t");
	}

	@Nonnull
	@Override
	public String toString() {
		return PrettyPrintingVisitor.toString(this);
	}

	@Nonnull
	public StringWithParameters toStringWithParameterExtraction() {
		return PrettyPrintingVisitor.toStringWithParameterExtraction(this);
	}

	/* we need to use raw types because constraint of type A might contain constraints of type B */
	/* i.e. require constraint might contain filtering constraints etc. */
	@Nullable
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Constraint purify(@Nonnull Constraint constraint, @Nullable UnaryOperator<Constraint> translator) {
		return QueryPurifierVisitor.purify(constraint, translator);
	}

}
