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
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.dataType.Scope;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Set;
import java.util.function.Function;

/**
 * Generic base for expression-based index triggers. Each trigger wraps a parsed expression together with pre-built
 * Proxycian proxy classes, a state recipe derived from `AccessedDataFinder` path analysis, and a pre-translated
 * {@link FilterBy} constraint tree for index-based evaluation.
 *
 * Built at schema load/change time. Supports two evaluation modes:
 *
 * - **Per-entity evaluation** via {@link #evaluate} — used for local triggers (inline in `ReferenceIndexMutator`)
 * - **Index-based query evaluation** via {@link #getFilterByConstraint()} — used for cross-entity triggers
 *   (the executor runs the constraint against indexes, no per-entity storage access needed)
 *
 * The {@link FilterBy} constraint is built at schema time by `ExpressionToQueryTranslator`. If the expression
 * cannot be translated (e.g., dynamic attribute paths, direct cross-to-local comparisons), an exception is thrown
 * at schema load time — non-translatable expressions are rejected.
 *
 * This trigger does NOT:
 *
 * - resolve affected entity PKs (the `IndexMutationExecutor`'s job on the target side)
 * - modify indexes (the `IndexMutationExecutor`'s job)
 * - wrap results into `EntityIndexMutation` (the source executor's job)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ThreadSafe
public interface ExpressionIndexTrigger {

	/**
	 * Entity type owning the reference with the expression (the target collection — e.g., "product").
	 */
	@Nonnull
	String getOwnerEntityType();

	/**
	 * Name of the reference carrying the conditional expression (e.g., "parameter").
	 */
	@Nonnull
	String getReferenceName();

	/**
	 * Scope to which this trigger applies. A reference with expressions in multiple scopes produces one trigger
	 * per scope.
	 */
	@Nonnull
	Scope getScope();

	/**
	 * How the mutated entity relates to the owner entity: {@link DependencyType#REFERENCED_ENTITY_ATTRIBUTE},
	 * {@link DependencyType#GROUP_ENTITY_ATTRIBUTE},
	 * {@link DependencyType#REFERENCED_ENTITY_REFERENCE_ATTRIBUTE}, or
	 * {@link DependencyType#GROUP_ENTITY_REFERENCE_ATTRIBUTE}. Returns `null` for local-only triggers (expressions
	 * that reference only `$entity.*` and `$reference.attributes['x']`) — these are handled inline in
	 * `ReferenceIndexMutator` and do not need cross-entity registry entries.
	 */
	@Nullable
	DependencyType getDependencyType();

	/**
	 * Returns the name of the reference on the target entity (referenced or group) whose attributes this expression
	 * reads. Non-null only for {@link DependencyType#REFERENCED_ENTITY_REFERENCE_ATTRIBUTE} and
	 * {@link DependencyType#GROUP_ENTITY_REFERENCE_ATTRIBUTE}. Returns `null` for entity-attribute dependencies
	 * and local-only triggers.
	 */
	@Nullable
	String getDependentReferenceName();

	/**
	 * Attribute names on the mutated entity (group or referenced) that this expression reads. Used by the detection
	 * step to skip triggers whose dependent attributes were not changed by the current mutation. Returns an empty
	 * set for local-only triggers (no cross-entity attributes to track).
	 */
	@Nonnull
	Set<String> getDependentAttributes();

	/**
	 * Returns the full expression pre-translated to an evitaDB {@link FilterBy} constraint tree. Built at schema
	 * load time by `ExpressionToQueryTranslator`.
	 *
	 * At trigger time, the executor **parameterizes** this constraint by injecting a PK-scoping clause for the
	 * specific mutated entity:
	 *
	 * - {@link DependencyType#GROUP_ENTITY_ATTRIBUTE}: adds
	 *   `groupHaving(entityPrimaryKeyInSet(mutatedPK))` within the `referenceHaving` clause
	 * - {@link DependencyType#REFERENCED_ENTITY_ATTRIBUTE}: adds
	 *   `entityHaving(entityPrimaryKeyInSet(mutatedPK))` within the `referenceHaving` clause
	 *
	 * For local-only triggers ({@link #getDependencyType()} returns `null`), this method throws
	 * {@link UnsupportedOperationException} — local-only expressions are evaluated exclusively via
	 * {@link #evaluate(int, ReferenceKey, WritableEntityStorageContainerAccessor, Function)}.
	 *
	 * @throws UnsupportedOperationException if this is a local-only trigger
	 */
	@Nonnull
	FilterBy getFilterByConstraint();

	/**
	 * Evaluates the expression for a specific owner entity and reference. Used for **local triggers** only
	 * (inline in `ReferenceIndexMutator`).
	 *
	 * Instantiates pre-built Proxycian proxy classes backed by StoragePart data (fetched per the pre-computed
	 * state recipe), binds them as expression variables (`$entity`, `$reference`), and computes the expression
	 * result.
	 *
	 * NOT used for cross-entity triggers — those use {@link #getFilterByConstraint()} for index-based evaluation
	 * instead.
	 *
	 * @param ownerEntityPK   primary key of the entity owning the reference
	 * @param referenceKey    identifies the specific reference instance
	 * @param storageAccessor accessor for fetching required StorageParts
	 * @param schemaResolver  function resolving entity type name to entity schema
	 * @return `true` if the index entry should exist, `false` otherwise
	 */
	boolean evaluate(
		int ownerEntityPK,
		@Nonnull ReferenceKey referenceKey,
		@Nonnull WritableEntityStorageContainerAccessor storageAccessor,
		@Nonnull Function<String, EntitySchemaContract> schemaResolver
	);

}
