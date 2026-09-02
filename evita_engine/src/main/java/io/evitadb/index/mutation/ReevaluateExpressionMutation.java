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
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
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
 * (null for non-localized).
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
 * Identity (equals/hashCode) is based solely on the 4 core fields (referenceName, mutatedEntityPK,
 * dependencyType, scope) — the `preMutationSourceValues` and `previouslyIndexedOwnerPKs` fields are excluded to
 * preserve deduplication semantics in {@code EntityIndexLocalMutationExecutor.collectEntityAttributeTriggers()}.
 * That exclusion is also what lets the pre-pass key its captured state by the mutation itself: the pre-pass and
 * dispatch copies differ only in these two payload fields and therefore compare equal.
 *
 * @param referenceName          reference with the conditional expression
 * @param mutatedEntityPK        the group/referenced entity PK that changed
 * @param dependencyType         how the mutated entity relates to the owner
 * @param scope                  scope of the expression to re-evaluate
 * @param preMutationSourceValues pre-mutation attribute values keyed by attribute name and locale, or null
 * @param previouslyIndexedOwnerPKs owner PKs whose condition held before the batch, keyed by histogram name,
 *                                  or null when no pre-pass ran (facet-only triggers, tests, WAL replay paths
 *                                  without a collector)
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record ReevaluateExpressionMutation(
	@Nonnull String referenceName,
	int mutatedEntityPK,
	@Nonnull DependencyType dependencyType,
	@Nonnull Scope scope,
	@Nullable Map<String, Map<Locale, Serializable>> preMutationSourceValues,
	@Nullable Map<String, Bitmap> previouslyIndexedOwnerPKs
) implements IndexMutation, Serializable {
	@Serial private static final long serialVersionUID = -1L;

	public ReevaluateExpressionMutation(
		@Nonnull String referenceName,
		int mutatedEntityPK,
		@Nonnull DependencyType dependencyType,
		@Nonnull Scope scope,
		@Nullable Map<String, Map<Locale, Serializable>> preMutationSourceValues
	) {
		this(referenceName, mutatedEntityPK, dependencyType, scope, preMutationSourceValues, null);
	}

	/**
	 * Convenience factory for mutations that do not carry pre-mutation values (facet triggers,
	 * condition-only changes, or scenarios where old values are not needed).
	 */
	@Nonnull
	public static ReevaluateExpressionMutation withoutOldValues(
		@Nonnull String referenceName,
		int mutatedEntityPK,
		@Nonnull DependencyType dependencyType,
		@Nonnull Scope scope
	) {
		return new ReevaluateExpressionMutation(referenceName, mutatedEntityPK, dependencyType, scope, null, null);
	}

	/**
	 * Returns a copy of this mutation carrying the supplied pre-pass condition state. Used by
	 * {@code LocalMutationExecutorCollector} to attach the state it captured before the batch was applied,
	 * without disturbing the mutation's identity.
	 *
	 * @param previouslyIndexedOwnerPKs owner PKs whose condition held before the batch, keyed by histogram name
	 * @return a copy carrying the pre-pass state
	 */
	@Nonnull
	public ReevaluateExpressionMutation withPreviouslyIndexedOwnerPKs(
		@Nullable Map<String, Bitmap> previouslyIndexedOwnerPKs
	) {
		return new ReevaluateExpressionMutation(
			this.referenceName, this.mutatedEntityPK, this.dependencyType, this.scope,
			this.preMutationSourceValues, previouslyIndexedOwnerPKs
		);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof ReevaluateExpressionMutation that)) return false;
		return this.mutatedEntityPK == that.mutatedEntityPK
			&& this.referenceName.equals(that.referenceName)
			&& this.dependencyType == that.dependencyType
			&& this.scope == that.scope;
	}

	@Override
	public int hashCode() {
		int result = this.referenceName.hashCode();
		result = 31 * result + this.mutatedEntityPK;
		result = 31 * result + this.dependencyType.hashCode();
		result = 31 * result + this.scope.hashCode();
		return result;
	}
}
