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

package io.evitadb.spike.vector;

import org.apache.lucene.util.Bits;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;

/**
 * The candidate set of a filtered search (spec §2.5) as the walk consults it: one membership test per visited node.
 * Every strategy uses the shape evitaDB would hand over, a `RoaringBitmap` of primary keys behind the ordinal map
 * (spec §2.4). Three materializations over ordinals are compared with it at the dense end (spec §4.4): a `boolean[]`
 * (what the recall harness used before), a flat `long[]` bitset, and a `RoaringBitmap` of the ordinals.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public interface Acceptor {

	/**
	 * @param ordinal a vector ordinal
	 * @return whether the ordinal is in the candidate set
	 */
	boolean accept(int ordinal);

	/**
	 * @return the number of ordinals in the candidate set
	 */
	int count();

	/**
	 * @return the exclusive upper bound of the ordinals the walk may ask about
	 */
	int length();

	/**
	 * @return the candidate set as Lucene's acceptance bits, delegating every test to this acceptor
	 */
	@Nonnull
	default Bits asLuceneBits() {
		final Acceptor self = this;
		return new Bits() {
			@Override
			public boolean get(int index) {
				return self.accept(index);
			}

			@Override
			public int length() {
				return self.length();
			}
		};
	}

	/**
	 * Which representation an acceptor is built in.
	 */
	enum Representation {
		/** The filter's `RoaringBitmap` of primary keys, tested through the ordinal map: what evitaDB hands over. */
		PRIMARY_KEY_BITMAP,
		/** One `boolean` per ordinal. */
		BOOLEAN_ARRAY,
		/** One bit per ordinal in a `long[]`. */
		FLAT_BITSET,
		/** A `RoaringBitmap` of the accepted ordinals. */
		ROARING
	}

	/**
	 * @param filter         the filter
	 * @param representation the representation to build
	 * @return the acceptor of the filter's members in the representation
	 */
	@Nonnull
	static Acceptor of(@Nonnull ClickRecallScenario.Filter filter, @Nonnull Representation representation) {
		if (representation == Representation.PRIMARY_KEY_BITMAP) {
			return new PrimaryKeyAcceptor(filter, filter.count());
		}
		return of(filter.accept(), representation);
	}

	/**
	 * @param members        whether an ordinal is accepted
	 * @param representation the representation to build, any but the primary-key bitmap, which needs the filter
	 * @return the acceptor of the members in the representation
	 */
	@Nonnull
	static Acceptor of(@Nonnull boolean[] members, @Nonnull Representation representation) {
		int count = 0;
		for (final boolean member : members) {
			if (member) {
				count++;
			}
		}
		return switch (representation) {
			case BOOLEAN_ARRAY -> new BooleanArrayAcceptor(members, count);
			case FLAT_BITSET -> {
				final long[] words = new long[(members.length + 63) >>> 6];
				for (int i = 0; i < members.length; i++) {
					if (members[i]) {
						words[i >>> 6] |= 1L << i;
					}
				}
				yield new FlatBitSetAcceptor(words, count, members.length);
			}
			case ROARING -> {
				final RoaringBitmap bitmap = new RoaringBitmap();
				for (int i = 0; i < members.length; i++) {
					if (members[i]) {
						bitmap.add(i);
					}
				}
				bitmap.runOptimize();
				yield new RoaringAcceptor(bitmap, count, members.length);
			}
			case PRIMARY_KEY_BITMAP -> throw new IllegalArgumentException(
				"The primary-key bitmap is built from a filter, not from a `boolean[]`."
			);
		};
	}

	/**
	 * @param members whether an ordinal is accepted
	 * @param count   number of accepted ordinals
	 */
	record BooleanArrayAcceptor(@Nonnull boolean[] members, int count) implements Acceptor {
		@Override
		public boolean accept(int ordinal) {
			return this.members[ordinal];
		}

		@Override
		public int length() {
			return this.members.length;
		}
	}

	/**
	 * @param words  the bits, ordinal `i` at bit `i & 63` of word `i >>> 6`
	 * @param count  number of accepted ordinals
	 * @param length the exclusive upper bound of the ordinals
	 */
	record FlatBitSetAcceptor(@Nonnull long[] words, int count, int length) implements Acceptor {
		@Override
		public boolean accept(int ordinal) {
			return (this.words[ordinal >>> 6] & (1L << ordinal)) != 0;
		}
	}

	/**
	 * @param bitmap the accepted ordinals
	 * @param count  number of accepted ordinals
	 * @param length the exclusive upper bound of the ordinals
	 */
	record RoaringAcceptor(@Nonnull RoaringBitmap bitmap, int count, int length) implements Acceptor {
		@Override
		public boolean accept(int ordinal) {
			return this.bitmap.contains(ordinal);
		}
	}

	/**
	 * @param filter the filter holding the bitmap of primary keys and the ordinal map
	 * @param count  number of accepted ordinals
	 */
	record PrimaryKeyAcceptor(@Nonnull ClickRecallScenario.Filter filter, int count) implements Acceptor {
		@Override
		public boolean accept(int ordinal) {
			return this.filter.contains(ordinal);
		}

		@Override
		public int length() {
			return this.filter.ordinals().ordinalCount();
		}
	}
}
