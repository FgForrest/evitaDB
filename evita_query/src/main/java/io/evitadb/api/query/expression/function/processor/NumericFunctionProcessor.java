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

package io.evitadb.api.query.expression.function.processor;

import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Extension of {@link FunctionProcessor} for functions that produce numeric results. In addition to evaluating
 * the function, implementations can determine the possible output value range via
 * {@link #determinePossibleRange(List)}. This range information is used to limit the range of tested variable
 * values during expression evaluation, enabling optimizations such as attribute histogram computation.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public interface NumericFunctionProcessor extends FunctionProcessor {

	/**
	 * Determines the possible numeric range of values for this expression node. In other words tries to identify
	 * the minimum and maximum values that the expression can evaluate to (if it's possible to determine them).
	 * This method can be used to limit the range of tested variable values in order to optimize the evaluation
	 * of the expression.
	 *
	 * @param argumentPossibleRanges possible range for each argument
	 *
	 * @return a BigDecimalNumberRange representing the possible range of values
	 * @throws UnsupportedDataTypeException if the data type is not supported
	 */
	@Nonnull
	BigDecimalNumberRange determinePossibleRange(@Nonnull List<BigDecimalNumberRange> argumentPossibleRanges) throws UnsupportedDataTypeException;

}
