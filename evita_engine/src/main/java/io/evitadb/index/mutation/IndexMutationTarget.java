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
import io.evitadb.core.expression.trigger.DependencyType;
import io.evitadb.core.expression.trigger.ExpressionIndexTrigger;
import io.evitadb.core.expression.trigger.HistogramExpressionTrigger;
import io.evitadb.core.expression.trigger.HistogramValueSource;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.IndexProvider;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/**
 * Narrow view of an entity collection exposed to stateless {@link IndexMutationExecutor} implementations. Provides
 * access to schema, expression triggers, filter evaluation, and cross-entity index lookups without exposing the full
 * collection API.
 *
 * @see IndexMutationExecutor
 * @see IndexMutationExecutorRegistry
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface IndexMutationTarget extends IndexProvider<EntityIndexKey, EntityIndex> {

	/**
	 * Returns the current internal schema for this entity collection.
	 */
	@Nonnull
	EntitySchema getEntitySchema();

	/**
	 * Returns the {@link ExpressionIndexTrigger} for the given reference/dependency/scope combination, or `null` if
	 * no conditional expression is defined for that combination.
	 *
	 * @param referenceName  name of the reference carrying the conditional expression
	 * @param dependencyType relationship between the mutated entity and the owner entity
	 * @param scope          the scope for which the trigger should be retrieved
	 * @return the matching trigger, or `null` if none is registered
	 */
	@Nullable
	ExpressionIndexTrigger getTrigger(
		@Nonnull String referenceName,
		@Nonnull DependencyType dependencyType,
		@Nonnull Scope scope
	);

	/**
	 * Evaluates a {@link FilterBy} constraint against this collection's global entity index for the specified scope
	 * and returns the matching entity primary keys as a {@link Bitmap}.
	 *
	 * @param filterBy the filter constraint to evaluate
	 * @param scope    the scope whose global entity index should serve as the evaluation target
	 * @return bitmap of matching entity primary keys, may be empty
	 */
	@Nonnull
	Bitmap evaluateFilter(@Nonnull FilterBy filterBy, @Nonnull Scope scope);

	/**
	 * Returns all {@link HistogramExpressionTrigger} instances registered for the given reference name and scope.
	 * Multiple triggers may exist per reference when several named histogram definitions are configured.
	 *
	 * @param referenceName name of the reference carrying the bucketed histogram definitions
	 * @param scope         the scope for which the histogram triggers should be retrieved
	 * @return collection of histogram triggers, empty if none are defined
	 */
	@Nonnull
	Collection<HistogramExpressionTrigger> getHistogramTriggers(
		@Nonnull String referenceName,
		@Nonnull Scope scope
	);

	/**
	 * Returns the {@link FilterIndex} for the named attribute from another entity type's global index. Used when
	 * histogram bucket values are sourced from a referenced entity's attribute
	 * ({@link HistogramValueSource#REFERENCED_ENTITY_ATTRIBUTE}).
	 *
	 * May return `null` during initial import when the source collection has not yet been initialized.
	 *
	 * @param entityType    entity type of the foreign collection to look up
	 * @param attributeName name of the attribute whose {@link FilterIndex} should be returned
	 * @param locale        locale for localized attributes, or `null` for non-localized
	 * @return the filter index, or `null` if the collection or index does not yet exist
	 */
	@Nullable
	FilterIndex getSourceFilterIndex(
		@Nonnull String entityType,
		@Nonnull String attributeName,
		@Nullable Locale locale
	);

	/**
	 * Returns the set of locales declared in the entity schema of the given entity type. Used by histogram
	 * processing to iterate locale-specific FilterIndexes for localized source attributes.
	 *
	 * @param entityType the entity type whose schema locales should be returned
	 * @return the set of locales; never null, may be empty
	 */
	@Nonnull
	Set<Locale> getEntitySchemaLocales(@Nonnull String entityType);

}
