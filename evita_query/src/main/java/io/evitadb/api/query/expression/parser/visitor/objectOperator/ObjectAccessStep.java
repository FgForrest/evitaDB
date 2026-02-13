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

package io.evitadb.api.query.expression.parser.visitor.objectOperator;

import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import io.evitadb.exception.ExpressionEvaluationException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * Represents a single step in a chained object access expression within EvitaEL. Steps are linked
 * together to form an access chain that is evaluated left-to-right against a source operand. Each
 * step receives the result of the previous step (or the initial operand) and produces the next
 * intermediate value.
 *
 * Implementations include:
 *
 * - {@link PropertyAccessStep} for dot-notation property access (`.property`)
 * - {@link ElementAccessStep} for bracket-notation element access (`[index]` or `['key']`)
 * - {@link SpreadAccessStep} for spread/map operations (`.*[expr]`, `.*![expr]`)
 * - {@link NullSafeAccessStep} for optional chaining (`?.`, `?[`)
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public interface ObjectAccessStep extends Serializable {

	@Nonnull Serializable getAccessedIdentifier();

	@Nullable Serializable compute(
		@Nonnull ExpressionEvaluationContext context,
		@Nullable Serializable operand
	) throws ExpressionEvaluationException;

	@Nullable
	ObjectAccessStep getNext();
}
