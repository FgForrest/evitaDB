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

package io.evitadb.core.query.algebra.identity;

import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import java.util.Arrays;

/**
 * Detached operand sequence with ordered or canonical value semantics and retained multiplicity.
 * Direct producer operands use compact primitive storage.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Immutable
public final class TokenList {
	/** Maximum operand count admitted across queries. */
	public static final int CAP = 4_096;
	/** Compact direct producer identifiers, or null for mixed tokens. */
	@Nullable private final long[] producerIds;
	/** Mixed tokens, or null for compact direct producers. */
	@Nullable private final IdentityToken[] tokens;
	/** Precomputed sequence hash. */
	private final long hash64;
	/** Saturating retained weight. */
	private final int weight;
	/** Some operand is opaque. */
	private final boolean opaque;

	/** Captures an owned primitive sequence. */
	private TokenList(@Nonnull long[] producerIds) {
		this.producerIds = producerIds;
		this.tokens = null;
		this.weight = producerIds.length;
		this.opaque = false;
		long hash = KeyAccumulator.mix(0, producerIds.length);
		for (final long id : producerIds) {
			hash = KeyAccumulator.mix(hash, KeyAccumulator.producerHash(id, TokenRole.DIRECT));
		}
		this.hash64 = hash;
	}

	/** Captures an owned mixed-token sequence. */
	private TokenList(@Nonnull IdentityToken[] tokens) {
		this.tokens = tokens;
		this.producerIds = null;
		long hash = KeyAccumulator.mix(0, tokens.length);
		int weight = 0;
		boolean opaque = false;
		for (final IdentityToken token : tokens) {
			hash = KeyAccumulator.mix(hash, token.hash64());
			weight = KeyAccumulator.sumWeight(weight, token.weight());
			opaque |= token.isOpaque();
		}
		this.hash64 = hash;
		this.weight = weight;
		this.opaque = opaque;
	}

	/** Builds a detached ordered operand list. */
	@Nonnull
	public static TokenList ordered(@Nonnull Bitmap[] bitmaps) {
		return fromBitmaps(bitmaps, false);
	}

	/** Builds a detached canonical operand multiset. */
	@Nonnull
	public static TokenList canonical(@Nonnull Bitmap[] bitmaps) {
		return fromBitmaps(bitmaps, true);
	}

	/** Copies producer identifiers and optionally sorts them, keeping duplicates. */
	@Nonnull
	public static TokenList ofProducerIds(@Nonnull long[] ids, boolean canonical) {
		final long[] copy = ids.clone();
		if (canonical) {
			Arrays.sort(copy);
		}
		return new TokenList(copy);
	}

	/** Selects primitive storage when all bitmaps are transactional producers. */
	@Nonnull
	private static TokenList fromBitmaps(@Nonnull Bitmap[] bitmaps, boolean canonical) {
		boolean direct = true;
		for (final Bitmap bitmap : bitmaps) {
			direct &= bitmap instanceof TransactionalLayerProducer<?, ?>;
		}
		if (direct) {
			final long[] ids = new long[bitmaps.length];
			for (int i = 0; i < bitmaps.length; i++) {
				ids[i] = ((TransactionalLayerProducer<?, ?>) bitmaps[i]).getId();
			}
			if (canonical) {
				Arrays.sort(ids);
			}
			return new TokenList(ids);
		}
		final IdentityToken[] tokens = new IdentityToken[bitmaps.length];
		for (int i = 0; i < bitmaps.length; i++) {
			tokens[i] = IdentityToken.of(bitmaps[i]);
		}
		if (canonical) {
			Arrays.sort(tokens, TokenList::compareTokens);
		}
		return new TokenList(tokens);
	}

	/** Orders producer values before exact content before opaque markers. */
	private static int compareTokens(@Nonnull IdentityToken left, @Nonnull IdentityToken right) {
		if (left instanceof final IdentityToken.ProducerId first) {
			if (right instanceof final IdentityToken.ProducerId second) {
				final int idOrder = Long.compare(first.id(), second.id());
				return idOrder == 0 ? first.role().compareTo(second.role()) : idOrder;
			}
			return -1;
		}
		if (right instanceof IdentityToken.ProducerId) {
			return 1;
		}
		if (left instanceof final IdentityToken.Exact first) {
			if (right instanceof final IdentityToken.Exact second) {
				final int hashOrder = Long.compare(first.hash64(), second.hash64());
				return hashOrder == 0 ? first.values().compareTo(second.values()) : hashOrder;
			}
			return -1;
		}
		return right instanceof IdentityToken.Exact ? 1 : 0;
	}

	/** Returns sequence length. */
	public int size() {
		return this.producerIds == null ? this.tokens.length : this.producerIds.length;
	}

	/** Returns precomputed sequence hash. */
	public long hash64() {
		return this.hash64;
	}

	/** Returns retained operand weight. */
	public int weight() {
		return this.weight;
	}

	/** Reports opaque operands. */
	public boolean isOpaque() {
		return this.opaque;
	}

	/** Reports opacity or excess operand count. */
	public boolean isQueryLocal() {
		return this.opaque || size() > CAP;
	}

	/** Compares value sequences across compact and mixed representations. */
	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof final TokenList that) || size() != that.size()) {
			return false;
		}
		if (this.producerIds != null && that.producerIds != null) {
			return Arrays.equals(this.producerIds, that.producerIds);
		}
		if (this.tokens != null && that.tokens != null) {
			return Arrays.equals(this.tokens, that.tokens);
		}
		final long[] ids = this.producerIds == null ? that.producerIds : this.producerIds;
		final IdentityToken[] values = this.tokens == null ? that.tokens : this.tokens;
		for (int i = 0; i < ids.length; i++) {
			if (!(values[i] instanceof final IdentityToken.ProducerId producer) ||
				producer.role() != TokenRole.DIRECT || producer.id() != ids[i]) {
				return false;
			}
		}
		return true;
	}

	/** Folds the sequence hash. */
	@Override
	public int hashCode() {
		return Long.hashCode(this.hash64);
	}
}
