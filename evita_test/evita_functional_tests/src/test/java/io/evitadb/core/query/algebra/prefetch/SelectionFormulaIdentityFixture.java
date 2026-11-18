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

package io.evitadb.core.query.algebra.prefetch;

import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.core.query.algebra.identity.KeyAccumulator;
import io.evitadb.core.query.filter.translator.entity.alternative.LocaleEntityToBitmapFilter;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.identity.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.evitadb.test.identity.CloneOutcome.*;

/**
 * Real construction modes and clone operations for {@link SelectionFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class SelectionFormulaIdentityFixture
	implements IdentityFixture<SelectionFormula> {
	/** Covers the value and locality contracts of the holder. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<SelectionFormula>> modes() {
		final LocaleEntityToBitmapFilter alternative = new LocaleEntityToBitmapFilter(Locale.ENGLISH);
		final EntityToBitmapFilter opaque = new OpaqueBitmapFilter();
		return Map.of(
			"value alternative", new ConstructionMode<>(() -> new SelectionFormula(leaf(1), alternative),
				Map.of("locale", () -> new SelectionFormula(leaf(1), new LocaleEntityToBitmapFilter(Locale.FRENCH)),
					"child", () -> new SelectionFormula(leaf(2), alternative)), Map.of(), Map.of(),
				Map.of("equal child", new ClonePath<>(formula ->
					formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), EQUAL)),
				Set.of(), Set.of("alternative"), Set.of()),
			"opaque alternative", new ConstructionMode<>(null, Map.of(),
				Map.of("opaque filter", () -> new SelectionFormula(leaf(1), opaque)), Map.of(),
				Map.of("equal child", new ClonePath<>(formula ->
					formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), UNEQUAL)),
				Set.of(), Set.of(), Set.of()));
	}

	/** Builds an exact nonempty child. */
	@Nonnull
	private static ConstantFormula leaf(int value) {
		return new ConstantFormula(new BaseBitmap(value));
	}

	/**
	 * Alternative with an explicitly opaque input for selection propagation checks.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	static final class OpaqueBitmapFilter implements EntityToBitmapFilter {
		/** Unique filter identifier. */
		private static final long CLASS_ID = 2340747493244671566L;
		/** Returns the filter identifier. */
		@Override public long getClassId() { return CLASS_ID; }

		/** Returns its opaque input marker. */
		@Nonnull
		@Override public IdentityRecord identity() { return new Identity(IdentityToken.OPAQUE); }

		/** Returns the constant empty result. */
		@Nonnull
		@Override public Bitmap filter(@Nonnull QueryExecutionContext context) { return new BaseBitmap(); }

		/** Declares no requirements. */
		@Nullable
		@Override public EntityFetchRequire getEntityRequire() { return null; }

		/**
		 * Opaque alternative input.
		 *
		 * @param input marker without a value form
		 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
		 */
		record Identity(@Nonnull IdentityToken input) implements IdentityRecord {
			/** Returns the filter identifier. */
			@Override public long classId() { return CLASS_ID; }
			/** Describes the opaque input. */
			@Override public void describeTo(@Nonnull KeyAccumulator accumulator) { accumulator.add(this.input); }
		}
	}

}
