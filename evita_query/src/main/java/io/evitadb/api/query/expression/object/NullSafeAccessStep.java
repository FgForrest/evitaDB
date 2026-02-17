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

package io.evitadb.api.query.expression.object;

import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import io.evitadb.exception.ExpressionEvaluationException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * An {@link ObjectAccessStep} decorator that provides optional chaining semantics for the wrapped
 * next step. If the operand is null, this step short-circuits the access chain and returns null
 * instead of throwing an {@link ExpressionEvaluationException}.
 *
 * This corresponds to the `?.` and `?[` syntax in EvitaEL. For example, in the expression
 * `$entity?.name`, this step wraps the {@link PropertyAccessStep} for `name` and ensures that
 * a null `$entity` produces null rather than an error.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class NullSafeAccessStep implements ObjectAccessStep {

	@Serial private static final long serialVersionUID = -2195956191525819662L;
	@Nonnull @Getter private final ObjectAccessStep next;

	@Nullable
	@Override
	public Serializable compute(
		@Nonnull ExpressionEvaluationContext context,
		@Nullable Serializable operand
	) throws ExpressionEvaluationException {
		if (operand == null) {
			return null;
		}
		return getNext().compute(context, operand);
	}
}
