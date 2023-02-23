/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.algebra.infra;

import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.index.bitmap.Bitmap;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;

/**
 * This formula is placeholder for formula, that should be excluded from computational tree. This may happen when there
 * are multiple constraints in the query can be merged into a single formula. This merged formula is then created only
 * by single translator and the rest of the translator skip themselves by this formula. When the constraints are not
 * combined and are used solely, each of the translator may still produce valid formula.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class SkipFormula extends AbstractFormula {
	public static final SkipFormula INSTANCE = new SkipFormula();
	private static final String ERROR_CANNOT_BE_USED = "This formula is not expected to be used whatsoever!";

	private SkipFormula() {
		super();
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		throw new UnsupportedOperationException(ERROR_CANNOT_BE_USED);
	}

	@Override
	public long getOperationCost() {
		throw new UnsupportedOperationException(ERROR_CANNOT_BE_USED);
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		throw new UnsupportedOperationException(ERROR_CANNOT_BE_USED);
	}

	@Override
	public int getEstimatedCardinality() {
		throw new UnsupportedOperationException(ERROR_CANNOT_BE_USED);
	}

	@Override
	public String toString() {
		return "SKIP";
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		throw new UnsupportedOperationException(ERROR_CANNOT_BE_USED);
	}

	@Override
	protected long getClassId() {
		throw new UnsupportedOperationException(ERROR_CANNOT_BE_USED);
	}

}
