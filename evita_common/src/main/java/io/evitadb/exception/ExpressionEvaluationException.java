/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.exception;


import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * Represents an exception that occurs during the evaluation of an expression.
 * This exception is thrown when the evaluation process encounters an error,
 * indicating that there is an issue with the expression or its evaluation context.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class ExpressionEvaluationException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 7564732111737294881L;

	public ExpressionEvaluationException(@Nonnull String privateMessage, @Nonnull String publicMessage, @Nonnull Throwable cause) {
		super(privateMessage, publicMessage, cause);
	}

	public ExpressionEvaluationException(@Nonnull String privateMessage, @Nonnull String publicMessage) {
		super(privateMessage, publicMessage);
	}

	public ExpressionEvaluationException(@Nonnull String publicMessage) {
		super(publicMessage, publicMessage);
	}

	/**
	 * Asserts that the given operand is non-null and {@link Comparable}, throwing
	 * an {@link ExpressionEvaluationException} with a descriptive message if not.
	 *
	 * This helper consolidates the two-step validation (null check + type check) that
	 * is repeated in every comparison operator (`>`, `>=`, `<`, `<=`).
	 *
	 * @param value        the computed operand value
	 * @param operatorName the human-readable operator name (e.g. "Greater than")
	 * @param side         "left" or "right"
	 */
	public static void assertComparableOperand(
		@Nullable Serializable value,
		@Nonnull String operatorName,
		@Nonnull String side
	) {
		if (value == null) {
			throw new ExpressionEvaluationException(
				operatorName + " function " + side + " operand evaluated to null"
					+ " — the referenced data may be missing or not yet available.",
				operatorName + " function " + side + " operand must not be null."
			);
		}
		if (!(value instanceof Comparable)) {
			throw new ExpressionEvaluationException(
				operatorName + " function " + side + " operand of type "
					+ value.getClass().getSimpleName() + " must be comparable!",
				operatorName + " function operand must be comparable!"
			);
		}
	}

}
