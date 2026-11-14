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
import java.util.Objects;

/**
 * Detached operand identity, exact producer version, bounded content, or opaque marker.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public sealed interface IdentityToken permits IdentityToken.ProducerId, IdentityToken.Exact, IdentityToken.Opaque {
	/** Maximum copied integer content. */
	int CONTENT_LIMIT = 1_024;
	/** Marker binding a key to its holder instance. */
	IdentityToken OPAQUE = Opaque.INSTANCE;

	/** Derives an operand identity without retaining the bitmap or its backing storage. */
	@Nonnull
	static IdentityToken of(@Nonnull Bitmap bitmap) {
		if (bitmap instanceof final TransactionalLayerProducer<?, ?> producer) {
			return new ProducerId(producer.getId(), TokenRole.DIRECT);
		}
		return bitmap.size() > CONTENT_LIMIT ? OPAQUE : ofContent(bitmap.getArray());
	}

	/** Copies bounded integer content, otherwise returns the opaque marker. */
	@Nonnull
	static IdentityToken ofContent(@Nonnull int[] values) {
		return values.length > CONTENT_LIMIT ? OPAQUE : new Exact(new IntValues(values));
	}

	/** Names a deterministic derived operand of a versioned owner. */
	@Nonnull
	static IdentityToken derived(long ownerId, @Nonnull TokenRole role) {
		return new ProducerId(ownerId, role);
	}

	/** Returns the operand's precomputed or constant-time hash. */
	long hash64();
	/** Returns retained identity weight. */
	int weight();
	/** Returns whether this operand has no value identity. */
	boolean isOpaque();

	/**
	 * Versioned producer identity qualified by its derivation role.
	 *
	 * @param id producer version
	 * @param role operand derivation
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	record ProducerId(long id, @Nonnull TokenRole role) implements IdentityToken {
		/** Requires a named role. */
		public ProducerId {
			Objects.requireNonNull(role);
		}

		/** Hashes both producer and role. */
		@Override
		public long hash64() {
			return KeyAccumulator.producerHash(this.id, this.role);
		}

		/** A producer identifier occupies one weight unit. */
		@Override
		public int weight() {
			return 1;
		}

		/** Producer identifiers are exact. */
		@Override
		public boolean isOpaque() {
			return false;
		}
	}

	/**
	 * Exact copied content.
	 *
	 * @param values detached integer values
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	record Exact(@Nonnull IntValues values) implements IdentityToken {
		/** Requires copied content. */
		public Exact {
			Objects.requireNonNull(values);
		}

		/** Distinguishes content from producer identifiers. */
		@Override
		public long hash64() {
			return KeyAccumulator.mix(2, this.values.hash64());
		}

		/** Charges every retained integer. */
		@Override
		public int weight() {
			return this.values.size();
		}

		/** Bounded copied content is exact. */
		@Override
		public boolean isOpaque() {
			return false;
		}
	}

	/**
	 * Stateless marker for an operand without a bounded value identity.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	final class Opaque implements IdentityToken {
		/** Shared marker; the holder, rather than this marker, discriminates opaque keys. */
		static final Opaque INSTANCE = new Opaque();
		/** Prevents creation of additional markers. */
		private Opaque() {
		}

		/** Returns the opaque kind discriminator. */
		@Override
		public long hash64() {
			return 3;
		}

		/** Charges the marker reference. */
		@Override
		public int weight() {
			return 1;
		}

		/** Opaque operands bind their key to its owner. */
		@Override
		public boolean isOpaque() {
			return true;
		}
	}
}
