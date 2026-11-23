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

import io.evitadb.core.expression.trigger.DependencyType;
import io.evitadb.dataType.Scope;
import io.evitadb.index.mutation.storagePart.ContainerizedLocalMutationExecutor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Locale;
import java.util.Map;

/**
 * Signals that a cross-entity change occurred that may affect the facet or histogram indexing expression
 * for the given reference. This is the unified mutation for both facet and histogram cross-entity triggers.
 *
 * The optional `preMutationSourceValues` field carries pre-mutation attribute values captured during the container
 * implicit-mutation phase (before the source entity's FilterIndex was updated). These enable deterministic histogram
 * removal by providing the exact old values for `remove(oldValue, ownerPK)` operations, eliminating the need for
 * histogram bucket scanning or fallback re-indexing. The map is keyed by attribute name, then by locale
 * (null for non-localized). A locale mapped to a `null` value means the attribute was unset before the mutation;
 * a locale missing from the map means the attribute was not mutated in it. The two must not be conflated: an unset
 * value source contributed nothing to remove, whereas an unmutated one still contributes its current value.
 *
 * The optional `previouslyIndexedOwnerPKs` field carries, per histogram name, the owner PKs whose histogram
 * condition held **before** any of this batch's local mutations were applied. It is captured by
 * {@code LocalMutationExecutorCollector}'s read-only pre-pass, because by the time this mutation is dispatched
 * every readable source — indexes and storage containers alike — already reflects the post-mutation state.
 *
 * Without it the removal side of the executor's remove-before-add has no way to tell *"this reference contributed
 * (value → owner)"* from *"somebody's contribution for (value, owner) exists"*: the histogram is a multiset gated
 * by {@code AttributeCardinalityIndex}, so removing on mere bucket membership consumes a **sibling** reference's
 * cardinality unit whenever two of an owner's references normalise to the same bucket key. The counter then reads
 * one where it should read two, and the next legitimate removal evicts the owner from the histogram entirely.
 *
 * The optional `storageAccessor` field carries the storage-part executor of the entity whose mutation emitted this
 * signal. The executor reads an attribute of a referenced entity through the same chain the owner's own upsert reads
 * it: its own cached parts for the mutated entity, the executor of another entity mutated in the same root mutation,
 * or the parts read once for an entity the root mutation does not change. The histogram executor reads stored values
 * through it where the source `FilterIndex` cannot tell what the owner's upsert would write - an array repeating an
 * element, which the index folds onto one key, or an empty array, which it does not record at all. The executor is
 * live state: the field is valid only while {@code LocalMutationExecutorCollector} dispatches this mutation within
 * the root mutation that created the executor, and the mutation is therefore never stored or serialized. Every
 * dispatch path runs through the collector, WAL replay included, which regenerates these mutations.
 *
 * Identity (equals/hashCode) is based solely on the 4 core fields (referenceName, mutatedEntityPK,
 * dependencyType, scope) — the `preMutationSourceValues`, `previouslyIndexedOwnerPKs` and `storageAccessor` fields
 * are excluded to preserve deduplication semantics in
 * {@code EntityIndexLocalMutationExecutor.collectEntityAttributeTriggers()}. That exclusion is also what lets the
 * pre-pass key its captured state by the mutation itself: the pre-pass and dispatch copies differ only in these
 * payload fields and therefore compare equal. {@link #toString()} prints the same 4 fields.
 *
 * @param referenceName          reference with the conditional expression
 * @param mutatedEntityPK        the group/referenced entity PK that changed
 * @param dependencyType         how the mutated entity relates to the owner
 * @param scope                  scope of the expression to re-evaluate
 * @param preMutationSourceValues pre-mutation attribute values keyed by attribute name and locale, or null
 * @param previouslyIndexedOwnerPKs the condition's answer captured before the batch, keyed by histogram name,
 *                                  or null when no pre-pass ran (facet-only triggers, tests constructing the
 *                                  mutation directly)
 * @param storageAccessor        the storage-part executor of the entity whose mutation emitted this signal, or null
 *                               when the collector attached no pre-pass state (facet-only triggers, tests
 *                               constructing the mutation directly)
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record ReevaluateExpressionMutation(
	@Nonnull String referenceName,
	int mutatedEntityPK,
	@Nonnull DependencyType dependencyType,
	@Nonnull Scope scope,
	@Nullable Map<String, Map<Locale, Serializable>> preMutationSourceValues,
	@Nullable Map<String, ContributionVerdicts> previouslyIndexedOwnerPKs,
	@Nullable ContainerizedLocalMutationExecutor storageAccessor
) implements IndexMutation {

	/**
	 * Creates the signal carrying the pre-mutation values of the mutated entity, without any pre-pass state.
	 *
	 * @param referenceName           reference with the conditional expression
	 * @param mutatedEntityPK         the group/referenced entity PK that changed
	 * @param dependencyType          how the mutated entity relates to the owner
	 * @param scope                   scope of the expression to re-evaluate
	 * @param preMutationSourceValues pre-mutation attribute values keyed by attribute name and locale, or null
	 */
	public ReevaluateExpressionMutation(
		@Nonnull String referenceName,
		int mutatedEntityPK,
		@Nonnull DependencyType dependencyType,
		@Nonnull Scope scope,
		@Nullable Map<String, Map<Locale, Serializable>> preMutationSourceValues
	) {
		this(referenceName, mutatedEntityPK, dependencyType, scope, preMutationSourceValues, null, null);
	}

	/**
	 * Convenience factory for mutations that do not carry pre-mutation values (facet triggers,
	 * condition-only changes, or scenarios where old values are not needed).
	 *
	 * @param referenceName   reference with the conditional expression
	 * @param mutatedEntityPK the group/referenced entity PK that changed
	 * @param dependencyType  how the mutated entity relates to the owner
	 * @param scope           scope of the expression to re-evaluate
	 * @return the signal without pre-mutation values and without pre-pass state
	 */
	@Nonnull
	public static ReevaluateExpressionMutation withoutOldValues(
		@Nonnull String referenceName,
		int mutatedEntityPK,
		@Nonnull DependencyType dependencyType,
		@Nonnull Scope scope
	) {
		return new ReevaluateExpressionMutation(
			referenceName, mutatedEntityPK, dependencyType, scope, null, null, null
		);
	}

	/**
	 * Returns a copy of this mutation carrying the supplied pre-pass condition state and the storage-part executor
	 * of the entity whose mutation emitted it. Used by {@code LocalMutationExecutorCollector} to attach, in one copy,
	 * the state it captured before the batch was applied and the executor the dispatch reads stored values through,
	 * without disturbing the mutation's identity.
	 *
	 * @param previouslyIndexedOwnerPKs the condition's answer captured before the batch, keyed by histogram name
	 * @param storageAccessor           the storage-part executor of the entity whose mutation emitted this signal
	 * @return a copy carrying the pre-pass state and the executor
	 */
	@Nonnull
	public ReevaluateExpressionMutation withPreMutationState(
		@Nullable Map<String, ContributionVerdicts> previouslyIndexedOwnerPKs,
		@Nullable ContainerizedLocalMutationExecutor storageAccessor
	) {
		return new ReevaluateExpressionMutation(
			this.referenceName, this.mutatedEntityPK, this.dependencyType, this.scope,
			this.preMutationSourceValues, previouslyIndexedOwnerPKs, storageAccessor
		);
	}

	/**
	 * Unlike the record default, compares only the 4 identity fields (`referenceName`, `mutatedEntityPK`,
	 * `dependencyType`, `scope`); the payload fields take no part, so a copy carrying captured state equals the
	 * mutation it was made from.
	 *
	 * @param o the object to compare with
	 * @return `true` when `o` is a mutation with the same 4 identity fields
	 */
	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof ReevaluateExpressionMutation that)) return false;
		return this.mutatedEntityPK == that.mutatedEntityPK
			&& this.referenceName.equals(that.referenceName)
			&& this.dependencyType == that.dependencyType
			&& this.scope == that.scope;
	}

	/**
	 * Unlike the record default, hashes only the 4 identity fields {@link #equals(Object)} compares.
	 *
	 * @return the hash of the 4 identity fields
	 */
	@Override
	public int hashCode() {
		int result = this.referenceName.hashCode();
		result = 31 * result + this.mutatedEntityPK;
		result = 31 * result + this.dependencyType.hashCode();
		result = 31 * result + this.scope.hashCode();
		return result;
	}

	/**
	 * Describes the mutation by the 4 core fields that make up its identity. The payload is left out: the captured
	 * maps can be large, and the storage-part executor is live state with no meaningful text.
	 *
	 * @return the description
	 */
	@Nonnull
	@Override
	public String toString() {
		return "ReevaluateExpressionMutation[referenceName=" + this.referenceName +
			", mutatedEntityPK=" + this.mutatedEntityPK +
			", dependencyType=" + this.dependencyType +
			", scope=" + this.scope + "]";
	}
}
