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
 * The optional `preMutationSourceValues` field carries pre-mutation attribute values captured during Step 5a
 * (before the source entity's FilterIndex was updated). These enable deterministic histogram removal by providing
 * the exact old values for `remove(oldValue, ownerPK)` operations, eliminating the need for histogram bucket
 * scanning or fallback re-indexing. The map is keyed by attribute name, then by locale (null for non-localized).
 *
 * Identity (equals/hashCode) is based solely on the 4 core fields (referenceName, mutatedEntityPK,
 * dependencyType, scope) — the `preMutationSourceValues` field is excluded to preserve deduplication semantics
 * in {@code EntityIndexLocalMutationExecutor.collectEntityAttributeTriggers()}.
 *
 * @param referenceName          reference with the conditional expression
 * @param mutatedEntityPK        the group/referenced entity PK that changed
 * @param dependencyType         how the mutated entity relates to the owner
 * @param scope                  scope of the expression to re-evaluate
 * @param preMutationSourceValues pre-mutation attribute values keyed by attribute name and locale, or null
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record ReevaluateExpressionMutation(
	@Nonnull String referenceName,
	int mutatedEntityPK,
	@Nonnull DependencyType dependencyType,
	@Nonnull Scope scope,
	@Nullable Map<String, Map<Locale, Serializable>> preMutationSourceValues
) implements IndexMutation, Serializable {
	@Serial private static final long serialVersionUID = -1L;

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
		return new ReevaluateExpressionMutation(referenceName, mutatedEntityPK, dependencyType, scope, null);
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
