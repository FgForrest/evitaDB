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

package io.evitadb.core.expression.query;

import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Thrown at schema load time when a `facetedPartially` expression contains constructs
 * that cannot be translated into an evitaDB `FilterBy` constraint tree.
 *
 * Non-translatable constructs include dynamic attribute paths, direct cross-to-local
 * comparisons, unsupported operators (arithmetic, XOR, functions), and associated data
 * access paths. The exception message identifies the specific unsupported construct
 * and explains why it cannot be translated.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class NonTranslatableExpressionException extends EvitaInvalidUsageException {

	@Serial private static final long serialVersionUID = -4839205734059823741L;

	public NonTranslatableExpressionException(@Nonnull String message) {
		super(message);
	}
}
