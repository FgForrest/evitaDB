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

package io.evitadb.core.query.algebra.reference;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.core.query.algebra.identity.TokenList;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.test.identity.*;

import javax.annotation.Nonnull;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static io.evitadb.test.identity.CloneOutcome.*;

/**
 * Real construction modes and clone operations for {@link ReferencedOwnerExistenceFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ReferencedOwnerExistenceFormulaIdentityFixture
	implements IdentityFixture<ReferencedOwnerExistenceFormula> {
	/** Covers the value and locality contracts of the holder. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<ReferencedOwnerExistenceFormula>> modes() {
		final Formula[] owners = {leaf(1), leaf(2)};
		final Formula[] swapped = {owners[1], owners[0]};
		final int[] largeOwners = new int[IdentityToken.CONTENT_LIMIT + 1];
		final Formula[] largeFormulas = new Formula[largeOwners.length];
		for (int i = 0; i < largeOwners.length; i++) {
			largeOwners[i] = i;
			largeFormulas[i] = leaf(1);
		}
		final int[] content = new int[IdentityToken.CONTENT_LIMIT + 1];
		for (int i = 0; i < content.length; i++) {
			content[i] = i;
		}
		final Formula opaqueOwner = new ConstantFormula(new BaseBitmap(content));
		final Formula oversizedOwner = new OrFormula(new long[]{1}, repeatedBitmaps());
		return Map.of(
			"value owners", new ConstructionMode<>(
				() -> create(new int[]{10, 20}, owners, 1),
				Map.of("owners", () -> create(new int[]{10, 21}, owners, 1),
					"association", () -> create(new int[]{10, 20}, swapped, 1)),
				Map.of(), Map.of("large side key", () -> create(new int[]{10}, new Formula[]{oversizedOwner}, 1)),
				Map.of("equal child", new ClonePath<>(formula ->
					formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), EQUAL),
					"no children", new ClonePath<>(formula -> formula.getCloneWithInnerFormulas(), UNEQUAL)),
				Set.of(), Set.of("ownerPrimaryKeys"), Set.of()),
			"opaque owners", new ConstructionMode<>(null, Map.of(),
				Map.of("large owner keys", () -> create(largeOwners, largeFormulas, 1),
					"opaque side key", () -> create(new int[]{10}, new Formula[]{opaqueOwner}, 1)), Map.of(),
				Map.of("equal child", new ClonePath<>(formula ->
					formula.getCloneWithInnerFormulas(formula.getInnerFormulas()), UNEQUAL),
					"no children", new ClonePath<>(formula -> formula.getCloneWithInnerFormulas(), UNEQUAL)),
				Set.of(), Set.of(), Set.of()));
	}

	/** Builds an exact nonempty child. */
	@Nonnull
	private static ConstantFormula leaf(int value) {
		return new ConstantFormula(new BaseBitmap(value));
	}

	/** Builds an owner association with a fixed narrowing child. */
	@Nonnull
	private static ReferencedOwnerExistenceFormula create(
		@Nonnull int[] owners, @Nonnull Formula[] formulas, long indexId
	) {
		return new ReferencedOwnerExistenceFormula(owners, formulas, leaf(1), indexId);
	}

	/** Builds an exact operand list above the admission limit. */
	@Nonnull
	private static Bitmap[] repeatedBitmaps() {
		final Bitmap[] bitmaps = new Bitmap[TokenList.CAP + 1];
		Arrays.fill(bitmaps, new TransactionalBitmap(new BaseBitmap(1)));
		return bitmaps;
	}

}
