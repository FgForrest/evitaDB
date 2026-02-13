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

import io.evitadb.api.query.expression.evaluate.object.accessor.ObjectAccessorRegistry;
import io.evitadb.api.query.expression.evaluate.object.accessor.ObjectPropertyAccessor;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import io.evitadb.exception.ExpressionEvaluationException;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * An {@link ObjectAccessStep} that resolves a named property on the current operand using
 * dot-notation syntax (`.propertyName`). The property is resolved via
 * {@link ObjectPropertyAccessor} looked up from the {@link ObjectAccessorRegistry}.
 *
 * For example, in the expression `$entity.primaryKey`, this step handles the `.primaryKey` access.
 * If the operand is null, an {@link ExpressionEvaluationException} is thrown — use
 * {@link NullSafeAccessStep} (`?.property`) to handle nullable operands gracefully.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
@EqualsAndHashCode
public class PropertyAccessStep implements ObjectAccessStep {
	@Serial private static final long serialVersionUID = 2760082902212762061L;

	@Nonnull private final String propertyIdentifier;

	@Getter @Nullable private final ObjectAccessStep next;

	@Nonnull
	public String getAccessedIdentifier() {
		return this.propertyIdentifier;
	}

	@Nullable
	@Override
	public Serializable compute(
		@Nonnull ExpressionEvaluationContext context,
		@Nullable Serializable operand
	) throws ExpressionEvaluationException {
		if (operand == null) {
			throw new ExpressionEvaluationException(
				"Cannot access property `" + this.propertyIdentifier + "`, object is null. If this is expected, use " +
					"optional chaining (`?.property`) instead."
			);
		}

		final ObjectAccessorRegistry registry = ObjectAccessorRegistry.getInstance();
		final ObjectPropertyAccessor propertyAccessor = registry.getPropertyAccessor(operand.getClass())
			.orElseThrow(
				() -> new ExpressionEvaluationException(
					"Property accessor for class `" + operand.getClass().getName() + "` not found.",
					"Cannot access property `" + this.propertyIdentifier + "`. Not supported."
				)
			);

		final Serializable result = propertyAccessor.get(operand, this.propertyIdentifier);
		if (getNext() == null) {
			return result;
		}

		return getNext().compute(context, result);
	}
}
