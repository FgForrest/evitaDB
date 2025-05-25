/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.api.query.expression.evaluate;

import io.evitadb.dataType.expression.PredicateEvaluationContext;

import javax.annotation.Nonnull;
import java.util.Random;

/**
 * Abstract class defining the context for parsing operations. It provides a way to get variable values by their names.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
abstract sealed class AbstractPredicateEvaluationContext implements PredicateEvaluationContext
	permits SingleVariableEvaluationContext, MultiVariableEvaluationContext {
	private final Random random;

	protected AbstractPredicateEvaluationContext() {
		this.random = new Random();
	}

	protected AbstractPredicateEvaluationContext(long seed) {
		this.random = new Random(seed);
	}

	@Nonnull
	@Override
	public Random getRandom() {
		return this.random;
	}
}
