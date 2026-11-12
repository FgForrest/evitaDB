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

package io.evitadb.core.query.algebra.attribute;

/**
 * Tests the sign of a threshold's comparison with a requested value.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public enum ThresholdOperator {
	/** Strictly below the requested value. */
	LESS_THAN,
	/** At or below the requested value. */
	LESS_THAN_EQUALS,
	/** Strictly above the requested value. */
	GREATER_THAN,
	/** At or above the requested value. */
	GREATER_THAN_EQUALS;

	/** Evaluates a comparison result, regardless of its magnitude. */
	public boolean accepts(int comparison) {
		return switch (this) {
			case LESS_THAN -> comparison < 0;
			case LESS_THAN_EQUALS -> comparison <= 0;
			case GREATER_THAN -> comparison > 0;
			case GREATER_THAN_EQUALS -> comparison >= 0;
		};
	}
}
