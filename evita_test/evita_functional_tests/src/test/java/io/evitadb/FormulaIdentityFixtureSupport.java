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

package io.evitadb;

import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.core.query.algebra.identity.TokenList;
import io.evitadb.index.bitmap.BaseBitmap;

import javax.annotation.Nonnull;

/**
 * Formula operands shared by the identity fixtures of unrelated holders: an exact leaf, a leaf whose content exceeds
 * the bounded snapshot, and exact ordered operands above the admission limit.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class FormulaIdentityFixtureSupport {

	/** Prevents instantiation of the static support. */
	private FormulaIdentityFixtureSupport() {
	}

	/** Builds a fresh exact leaf. */
	@Nonnull
	public static ConstantFormula leaf(int value) {
		return new ConstantFormula(new BaseBitmap(value));
	}

	/** Builds a holder over unowned content exceeding the bounded snapshot size. */
	@Nonnull
	public static ConstantFormula opaqueFormula() {
		final int[] values = new int[IdentityToken.CONTENT_LIMIT + 1];
		for (int i = 0; i < values.length; i++) {
			values[i] = i;
		}
		return new ConstantFormula(new BaseBitmap(values));
	}

	/** Builds exact ordered producer operands above the admission limit. */
	@Nonnull
	public static OrFormula oversizedFormula() {
		final io.evitadb.index.bitmap.Bitmap[] bitmaps = new io.evitadb.index.bitmap.Bitmap[TokenList.CAP + 1];
		java.util.Arrays.fill(bitmaps, new io.evitadb.index.bitmap.TransactionalBitmap(new BaseBitmap(1)));
		return new OrFormula(new long[]{1}, bitmaps);
	}
}
