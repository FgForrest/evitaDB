/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.dataType.expression;


import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.exception.ExpressionEvaluationException;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Class wraps an {@link ExpressionNode} object and provides a way to evaluate the expression.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
@EqualsAndHashCode
public class Expression implements ExpressionNode {
	@Serial private static final long serialVersionUID = 661548006498130632L;
	private final ExpressionNode root;

	@Nonnull
	@Override
	public Serializable compute(@Nonnull PredicateEvaluationContext context) throws ExpressionEvaluationException {
		return this.root.compute(context);
	}

	@Nonnull
	@Override
	public BigDecimalNumberRange determinePossibleRange() throws UnsupportedDataTypeException {
		return this.root.determinePossibleRange();
	}

	@Override
	public String toString() {
		return EvitaDataTypes.formatValue(this.root.toString());
	}
}
