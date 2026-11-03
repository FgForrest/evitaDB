/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.query.fetch;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.indexSelection.TargetIndexes;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.usage.SchemaCapabilityUsage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;

/**
 * The primary keys of the entities an `entityHaving` or a `groupHaving` of the filter of the fetched references
 * selects - the answer of the nested query over the referenced (or group) collection the row filter tests the target
 * (or the group) of a reference row against.
 *
 * The nested query is planned through {@link FilterByVisitor#getMatchingEntityPrimaryKeys}, in the scopes the node is
 * compiled in, and so shares the memo of nested queries of the logical query: two nodes with the same filter in the
 * same scopes plan one query.
 *
 * **When it is resolved depends on the visitor.** A visitor that only checks the filter
 * ({@link FilterByVisitor#isConstraintCheckOnly()}) resolves the set as soon as it is created: the set of a check is
 * thrown away and never asked, and resolving it is what validates the nested filter and hands the capabilities it
 * requests to the query - whatever data there is. Any other visitor resolves it when the first row reaches the node,
 * so a fetch whose owners hold no row to test plans no nested query.
 *
 * **The restriction narrows the query, never a row verdict.** When a restriction is supplied, the nested filter is
 * prefixed with an `entityPrimaryKeyInSet` of its keys, so the query evaluates only the entities the fetched rows
 * point at instead of the whole collection. The restriction holds every key a row can be *admitted* with, not every
 * key a row is tested with: the restriction of `entityHaving` is also intersected with the keys of the root
 * `entityPrimaryKeyInSet`, and a row whose target lies outside them gets "not selected" here although the
 * unrestricted query might select it. The root conjunct rejects such a row anyway, so no row verdict changes - but
 * the answers of this set are meaningful only inside the filter tree it was compiled for. An empty restriction means
 * no row can be admitted through the set, and no query is planned.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NotThreadSafe
final class NestedSet {
	/**
	 * The visitor the nested query is planned with.
	 */
	@Nonnull private final FilterByVisitor filterByVisitor;
	/**
	 * The entity type the nested filter selects entities of.
	 */
	@Nonnull private final String targetEntityType;
	/**
	 * The nested filter, unwrapped from its `entityHaving` or `groupHaving` container.
	 */
	@Nonnull private final FilterConstraint filter;
	/**
	 * The scopes the nested query is planned in.
	 */
	@Nonnull private final Set<Scope> scopes;
	/**
	 * Supplier of the primary keys the nested query is restricted to, NULL when it is not restricted.
	 */
	@Nullable private final Supplier<int[]> restriction;
	/**
	 * Supplier of the description of the nested query for the telemetry and the messages.
	 */
	@Nonnull private final Supplier<String> taskDescriptionSupplier;
	/**
	 * The resolved primary keys, NULL until the set is resolved.
	 */
	@Nullable private Bitmap primaryKeys;
	/**
	 * Resolved locale of the immediately enclosing reference level, NULL when it has no opinion to inherit.
	 */
	@Nullable private final Locale enclosingLocale;

	/**
	 * Creates the set, resolving it right away when the visitor only checks the filter.
	 *
	 * @param filterByVisitor         the visitor the nested query is planned with
	 * @param targetEntityType        the entity type the nested filter selects entities of
	 * @param filter                  the nested filter, unwrapped from its container
	 * @param scopes                  the scopes the nested query is planned in
	 * @param enclosingLocale         the resolved reference-level locale the nested level inherits without its own
	 * opinion
	 * @param restriction             supplier of the primary keys the nested query is restricted to, NULL for none
	 * @param taskDescriptionSupplier supplier of the description of the nested query
	 */
	NestedSet(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull String targetEntityType,
		@Nonnull FilterConstraint filter,
		@Nonnull Set<Scope> scopes,
		@Nullable Locale enclosingLocale,
		@Nullable Supplier<int[]> restriction,
		@Nonnull Supplier<String> taskDescriptionSupplier
	) {
		this.filterByVisitor = filterByVisitor;
		this.targetEntityType = targetEntityType;
		this.filter = filter;
		this.scopes = scopes;
		this.enclosingLocale = enclosingLocale;
		this.restriction = restriction;
		this.taskDescriptionSupplier = taskDescriptionSupplier;
		if (filterByVisitor.isConstraintCheckOnly()) {
			this.primaryKeys = resolve();
		}
	}

	/**
	 * Tells whether the nested filter selects the entity with the passed primary key, resolving the set on first use.
	 *
	 * @param primaryKey the primary key of the referenced entity or group
	 * @return true when the nested filter selects the entity
	 */
	boolean contains(int primaryKey) {
		Bitmap resolved = this.primaryKeys;
		if (resolved == null) {
			resolved = resolve();
			this.primaryKeys = resolved;
		}
		return resolved.contains(primaryKey);
	}

	/**
	 * Plans and computes the nested query, restricted to the supplied keys when there are any.
	 *
	 * @return the primary keys the nested filter selects
	 */
	@Nonnull
	private Bitmap resolve() {
		final FilterConstraint nestedFilter;
		if (this.restriction == null) {
			nestedFilter = this.filter;
		} else {
			final int[] keys = this.restriction.get();
			if (keys.length == 0) {
				return EmptyBitmap.INSTANCE;
			}
			nestedFilter = new FilterBy(entityPrimaryKeyInSet(keys), this.filter);
		}
		final FilterByVisitor nestedVisitor = createLocaleVisitor();
		final Bitmap matchingKeys = nestedVisitor.getMatchingEntityPrimaryKeys(
			this.targetEntityType, nestedFilter, this.scopes, this.taskDescriptionSupplier
		);
		if (nestedVisitor != this.filterByVisitor) {
			final QueryPlanningContext nestedContext = nestedVisitor.getQueryContext();
			for (final SchemaCapabilityUsage capability : nestedContext.drainRequestedCapabilities()) {
				this.filterByVisitor.getQueryContext().registerRequestedCapability(capability);
			}
		}
		return matchingKeys;
	}

	/**
	 * Supplies the reference level's locale as context rather than as an existence predicate. The nested request
	 * still resolves its own opinion first. A predicate such as `attributeIsNull` may select a target with no data
	 * in the inherited locale, so adding `entityLocaleEquals` would change the filter's meaning.
	 *
	 * @return a visitor inheriting the reference locale, or the original visitor when its context already agrees
	 */
	@Nonnull
	private FilterByVisitor createLocaleVisitor() {
		final QueryPlanningContext ownerContext = this.filterByVisitor.getQueryContext();
		final QueryPlanningContext localeContext = ownerContext.getContextInLocale(
			this.targetEntityType, null, this.enclosingLocale, this.scopes, "inherit reference filter locale"
		);
		if (localeContext == ownerContext) {
			return this.filterByVisitor;
		}
		return this.filterByVisitor.isConstraintCheckOnly() ?
			FilterByVisitor.createConstraintCheckVisitor(localeContext) :
			new FilterByVisitor(localeContext, Collections.emptyList(), TargetIndexes.EMPTY);
	}

}
