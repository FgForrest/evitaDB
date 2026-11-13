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

package io.evitadb.core.query.algebra.base;

import io.evitadb.core.query.algebra.AbstractCacheableFormula;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Abstract ancestor for cacheable formulas that can operate on raw {@link Bitmap} operands in addition to composed
 * inner formulas. Shares raw bitmap operands and their accessor between {@link AndFormula} and {@link OrFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public abstract class AbstractBitmapCacheableFormula extends AbstractCacheableFormula {
	/**
	 * Shared empty array returned by {@link #getBitmaps()} when no bitmap operands are present.
	 */
	private static final Bitmap[] EMPTY_BITMAP_ARRAY = new Bitmap[0];

	/**
	 * Raw bitmap operands this formula operates on. May be `null` when the formula operates solely on inner formulas.
	 */
	protected final Bitmap[] bitmaps;

	protected AbstractBitmapCacheableFormula(
		@Nullable Bitmap[] bitmaps
	) {
		this.bitmaps = bitmaps;
	}

	/**
	 * Returns the raw bitmap operands of this formula, or an empty array if this formula operates solely on inner
	 * formulas.
	 */
	@Nonnull
	public Bitmap[] getBitmaps() {
		return this.bitmaps == null ? EMPTY_BITMAP_ARRAY : this.bitmaps;
	}

}
