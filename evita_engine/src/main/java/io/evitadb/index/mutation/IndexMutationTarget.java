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

package io.evitadb.index.mutation;

import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.IndexProvider;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Limited view of an `EntityCollection` exposed to {@link IndexMutationExecutor} implementations. Extends
 * {@link IndexProvider} with mutation-executor-specific operations — schema retrieval, expression trigger access,
 * and query-based filter evaluation — while restricting access to collection internals (mutations, persistence,
 * cache, etc.).
 *
 * Implemented by the private `EntityIndexMaintainer` inner class within `EntityCollection`. This inner class also
 * implements `IndexMaintainer<EntityIndexKey, EntityIndex>` — giving `EntityIndexLocalMutationExecutor` access to
 * index creation/removal, while giving `IndexMutationExecutor` implementations access to the narrower surface
 * defined here. Passing the inner class instance (rather than `EntityCollection` itself) prevents callers from
 * being one cast away from the full collection API.
 *
 * The 3 methods inherited from {@link IndexProvider} plus the 3 methods declared here form the complete surface
 * available to executors:
 *
 * | Method                          | Source                | Purpose                                          |
 * |---------------------------------|-----------------------|--------------------------------------------------|
 * | `getOrCreateIndex`              | `IndexProvider`       | ensure a target index exists                     |
 * | `getIndexIfExists`              | `IndexProvider`       | primary index lookup by key                      |
 * | `getIndexByPrimaryKeyIfExists`  | `IndexProvider`       | resolve storage PKs from `ReferencedTypeEntityIndex` |
 * | `getEntitySchema`               | `IndexMutationTarget` | current entity schema for reference schema lookup |
 * | `getTrigger`                    | `IndexMutationTarget` | access pre-translated FilterBy for expression     |
 * | `evaluateFilter`                | `IndexMutationTarget` | run FilterBy against GlobalEntityIndex            |
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface IndexMutationTarget extends IndexProvider<EntityIndexKey, EntityIndex> {

	/**
	 * Returns the current entity schema for this collection. Used by executors to look up
	 * `ReferenceSchemaContract` for the reference being modified.
	 *
	 * Delegates to `EntityCollection.getInternalSchema()`.
	 *
	 * @return the current entity schema, never null
	 */
	@Nonnull
	EntitySchema getEntitySchema();

	/**
	 * Returns the expression trigger for the given reference name, dependency type, and scope. Used by the
	 * executor to access the pre-translated {@link FilterBy} constraint for expression evaluation against
	 * indexes.
	 *
	 * Delegates to a lookup in the cached trigger map built from `ReferenceSchema` at schema load time.
	 *
	 * @param referenceName  the name of the reference with the conditional expression
	 * @param dependencyType how the mutated entity relates to the owner entity
	 * @param scope          the scope of the expression to evaluate
	 * @return the trigger, or null if no conditional expression is defined
	 */
	@Nullable
	ExpressionIndexTrigger getTrigger(
		@Nonnull String referenceName,
		@Nonnull DependencyType dependencyType,
		@Nonnull Scope scope
	);

	/**
	 * Evaluates a {@link FilterBy} constraint against this collection's current indexes and returns the
	 * matching entity PK bitmap. Used by executors to determine which entities currently satisfy the
	 * expression.
	 *
	 * Delegates to the collection's existing query evaluation infrastructure against `GlobalEntityIndex`.
	 *
	 * @param filterBy the filter constraint to evaluate
	 * @return bitmap of entity primary keys matching the filter, never null (may be empty)
	 */
	@Nonnull
	Bitmap evaluateFilter(@Nonnull FilterBy filterBy);

}
