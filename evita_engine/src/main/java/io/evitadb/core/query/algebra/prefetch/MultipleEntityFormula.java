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

package io.evitadb.core.query.algebra.prefetch;

import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.index.bitmap.Bitmap;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;

/**
 * This formula is somewhat similar to {@link ConstantFormula} but it contains virtual entity primary keys created by
 * {@link QueryContext#translateEntityReference(EntityReferenceContract...)}. We need to differentiate this formula from
 * the standard {@link ConstantFormula} so that we could distinguish these in {@link PrefetchFormulaVisitor}.
 *
 * Existence of this formula forcefully triggers prefetch of entities - there is no other way how to evaluate other
 * filtering constraints on those entities because they might come from different entity collections and their filter
 * indexes cannot be combined easily.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class MultipleEntityFormula extends AbstractFormula {
	private static final long CLASS_ID = 7381005856920907843L;
	@Getter private final Bitmap directEntityReferences;

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return 0;
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		return directEntityReferences;
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		throw new UnsupportedOperationException("MultipleEntityFormula represents already translated entity references, cannot be cloned!");
	}

	@Override
	public int getEstimatedCardinality() {
		return directEntityReferences.size();
	}

	@Override
	public long getOperationCost() {
		return 0;
	}
}
