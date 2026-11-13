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

package io.evitadb.core.query.algebra.prefetch;

import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.identity.IdentityExempt;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.core.query.algebra.identity.KeyAccumulator;
import io.evitadb.index.bitmap.Bitmap;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * This formula is somewhat similar to {@link ConstantFormula} but it contains virtual entity primary keys created by
 * {@link QueryPlanningContext#translateEntityReference(EntityReferenceContract...)}. We need to differentiate this formula from
 * the standard {@link ConstantFormula} so that we could distinguish these in {@link PrefetchFormulaVisitor}.
 *
 * Existence of this formula forcefully triggers prefetch of entities - there is no other way how to evaluate other
 * filtering constraints on those entities because they might come from different entity collections and their filter
 * indexes cannot be combined easily.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class MultipleEntityFormula extends AbstractFormula {
	/**
	 * Unique identifier of this formula used in {@link AbstractFormula#getClassId()} as the identity discriminator.
	 */
	private static final long CLASS_ID = 7381005856920907843L;
	/**
	 * Contains the bitmap of virtual entity primary keys that were translated from entity references.
	 */
	@IdentityExempt(IdentityExempt.Reason.DECOMPOSED)
	@Getter private final Bitmap directEntityReferences;

	public MultipleEntityFormula(@Nonnull Bitmap directEntityReferences) {
		this.directEntityReferences = directEntityReferences;
		this.initFields();
	}

	/** Captures the detached semantic inputs without evaluating the holder. */
	@Nonnull
	@Override
	protected IdentityRecord identity() {
		return new Identity(this.directEntityReferences.size() > IdentityToken.CONTENT_LIMIT ?
			IdentityToken.OPAQUE : IdentityToken.ofContent(this.directEntityReferences.getArray()));
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		return this.directEntityReferences;
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		throw new UnsupportedOperationException("MultipleEntityFormula represents already translated entity references, cannot be cloned!");
	}

	@Override
	public int getEstimatedCardinality() {
		return this.directEntityReferences.size();
	}

	@Override
	public long getOperationCost() {
		return 0;
	}

	@Override
	public String toString() {
		return "PREFETCH: " + this.directEntityReferences;
	}

	/**
	 * Detached semantic inputs of the holder.
	 *
	 * @param directEntityReferences primary keys of the referenced entities, copied up to
	 *                               {@link IdentityToken#CONTENT_LIMIT} of them and an opaque, query-local token
	 *                               above that limit
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	public record Identity(@Nonnull IdentityToken directEntityReferences) implements IdentityRecord {
		/** Returns the holder's class identifier. */
		@Override
		public long classId() {
			return CLASS_ID;
		}

		/** Includes every semantic input in the structural hash and retained weight. */
		@Override
		public void describeTo(@Nonnull KeyAccumulator accumulator) {
			accumulator.add(this.directEntityReferences);
		}
	}

}
