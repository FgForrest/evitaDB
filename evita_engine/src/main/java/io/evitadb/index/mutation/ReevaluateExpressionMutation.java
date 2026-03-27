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
import java.io.Serial;
import java.io.Serializable;

/**
 * Signals that a cross-entity change occurred that may affect the facet or histogram indexing expression
 * for the given reference. This is the unified mutation for both facet and histogram cross-entity triggers.
 *
 * @param referenceName  reference with the conditional expression
 * @param mutatedEntityPK the group/referenced entity PK that changed
 * @param dependencyType how the mutated entity relates to the owner
 * @param scope          scope of the expression to re-evaluate
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record ReevaluateExpressionMutation(
	@Nonnull String referenceName,
	int mutatedEntityPK,
	@Nonnull DependencyType dependencyType,
	@Nonnull Scope scope
) implements IndexMutation, Serializable {
	@Serial private static final long serialVersionUID = -1L;
}
