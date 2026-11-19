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

package io.evitadb.api.configuration;

import io.evitadb.utils.Assert;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;

/**
 * Query-side fulltext options shared by all catalogs. Lengths count Unicode code points after the analyzer's
 * normalization. Each stem variant uses its own length. All options can change without reindexing.
 *
 * @param minLengthOneTypo shortest query term allowed one typo
 * @param minLengthTwoTypos shortest query term allowed two typos
 * @param nonFuzzyPrefix leading code points that must match exactly
 * @param transpositions whether adjacent swapped characters count as one typo
 * @param digitsExact whether terms containing a Unicode digit must match exactly
 * @param prefixLeg whether an unfinished final word can complete to longer words
 * @param minFragmentLength shortest fragment allowed unfinished-word expansion
 * @param openEndedStartLength shortest fragment allowed completion with typos
 */
@Immutable
public record FulltextOptions(
	int minLengthOneTypo,
	int minLengthTwoTypos,
	int nonFuzzyPrefix,
	boolean transpositions,
	boolean digitsExact,
	boolean prefixLeg,
	int minFragmentLength,
	int openEndedStartLength
) {
	/** Built-in value for `minLengthOneTypo`. */
	public static final int DEFAULT_MIN_LENGTH_ONE_TYPO = 4;
	/** Built-in value for `minLengthTwoTypos`. */
	public static final int DEFAULT_MIN_LENGTH_TWO_TYPOS = 8;
	/** Built-in value for `nonFuzzyPrefix`. */
	public static final int DEFAULT_NON_FUZZY_PREFIX = 1;
	/** Built-in value for `transpositions`. */
	public static final boolean DEFAULT_TRANSPOSITIONS = true;
	/** Built-in value for `digitsExact`. */
	public static final boolean DEFAULT_DIGITS_EXACT = true;
	/** Built-in value for `prefixLeg`. */
	public static final boolean DEFAULT_PREFIX_LEG = true;
	/** Built-in value for `minFragmentLength`. */
	public static final int DEFAULT_MIN_FRAGMENT_LENGTH = 4;
	/** Built-in value for `openEndedStartLength`. */
	public static final int DEFAULT_OPEN_ENDED_START_LENGTH = 4;

	/**
	 * Validates user input; invalid values name their configuration key.
	 *
	 * @param minLengthOneTypo shortest query term allowed one typo
	 * @param minLengthTwoTypos shortest query term allowed two typos
	 * @param nonFuzzyPrefix leading code points that must match exactly
	 * @param transpositions whether adjacent swapped characters count as one typo
	 * @param digitsExact whether terms containing a Unicode digit must match exactly
	 * @param prefixLeg whether an unfinished final word can complete to longer words
	 * @param minFragmentLength shortest fragment allowed unfinished-word expansion
	 * @param openEndedStartLength shortest fragment allowed completion with typos
	 */
	public FulltextOptions {
		Assert.isTrue(minLengthOneTypo >= 1, "fulltext.minLengthOneTypo must be at least 1.");
		Assert.isTrue(minLengthTwoTypos >= minLengthOneTypo,
			"fulltext.minLengthTwoTypos must be at least fulltext.minLengthOneTypo.");
		Assert.isTrue(nonFuzzyPrefix >= 0, "fulltext.nonFuzzyPrefix must be at least 0.");
		Assert.isTrue(minFragmentLength >= 1, "fulltext.minFragmentLength must be at least 1.");
		Assert.isTrue(openEndedStartLength >= 1, "fulltext.openEndedStartLength must be at least 1.");
	}

	/** Creates the built-in submitted-search options. */
	public FulltextOptions() {
		this(
			DEFAULT_MIN_LENGTH_ONE_TYPO,
			DEFAULT_MIN_LENGTH_TWO_TYPOS,
			DEFAULT_NON_FUZZY_PREFIX,
			DEFAULT_TRANSPOSITIONS,
			DEFAULT_DIGITS_EXACT,
			DEFAULT_PREFIX_LEG,
			DEFAULT_MIN_FRAGMENT_LENGTH,
			DEFAULT_OPEN_ENDED_START_LENGTH
		);
	}

	/**
	 * Creates a builder using the built-in defaults.
	 * @return a new options builder
	 */
	@Nonnull
	public static Builder builder() {
		return new Builder(new FulltextOptions());
	}

	/** Standard builder for immutable fulltext options. */
	@ToString
	public static class Builder {
		/** Shortest term allowed one typo. */
		private int minLengthOneTypo;
		/** Shortest term allowed two typos. */
		private int minLengthTwoTypos;
		/** Leading code points protected from edits. */
		private int nonFuzzyPrefix;
		/** Whether an adjacent swap counts as one typo. */
		private boolean transpositions;
		/** Whether digit-containing terms receive no typo allowance. */
		private boolean digitsExact;
		/** Whether the unfinished final word may complete to longer words. */
		private boolean prefixLeg;
		/** Shortest fragment permitted unfinished-word expansion. */
		private int minFragmentLength;
		/** Shortest fragment permitted completion with typos. */
		private int openEndedStartLength;

		/**
		 * Copies all supplied options.
		 * @param options options to copy
		 */
		Builder(@Nonnull FulltextOptions options) {
			this.minLengthOneTypo = options.minLengthOneTypo();
			this.minLengthTwoTypos = options.minLengthTwoTypos();
			this.nonFuzzyPrefix = options.nonFuzzyPrefix();
			this.transpositions = options.transpositions();
			this.digitsExact = options.digitsExact();
			this.prefixLeg = options.prefixLeg();
			this.minFragmentLength = options.minFragmentLength();
			this.openEndedStartLength = options.openEndedStartLength();
		}

		/**
		 * Sets shortest query term allowed one typo.
		 * @param minLengthOneTypo shortest query term allowed one typo
		 * @return this builder
		 */
		@Nonnull
		public Builder minLengthOneTypo(int minLengthOneTypo) {
			this.minLengthOneTypo = minLengthOneTypo;
			return this;
		}

		/**
		 * Sets shortest query term allowed two typos.
		 * @param minLengthTwoTypos shortest query term allowed two typos
		 * @return this builder
		 */
		@Nonnull
		public Builder minLengthTwoTypos(int minLengthTwoTypos) {
			this.minLengthTwoTypos = minLengthTwoTypos;
			return this;
		}

		/**
		 * Sets leading code points that must match exactly.
		 * @param nonFuzzyPrefix leading code points that must match exactly
		 * @return this builder
		 */
		@Nonnull
		public Builder nonFuzzyPrefix(int nonFuzzyPrefix) {
			this.nonFuzzyPrefix = nonFuzzyPrefix;
			return this;
		}

		/**
		 * Sets whether adjacent swapped characters count as one typo.
		 * @param transpositions whether adjacent swapped characters count as one typo
		 * @return this builder
		 */
		@Nonnull
		public Builder transpositions(boolean transpositions) {
			this.transpositions = transpositions;
			return this;
		}

		/**
		 * Sets whether terms containing a Unicode digit must match exactly.
		 * @param digitsExact whether terms containing a Unicode digit must match exactly
		 * @return this builder
		 */
		@Nonnull
		public Builder digitsExact(boolean digitsExact) {
			this.digitsExact = digitsExact;
			return this;
		}

		/**
		 * Sets whether an unfinished final word can complete to longer words.
		 * @param prefixLeg whether an unfinished final word can complete to longer words
		 * @return this builder
		 */
		@Nonnull
		public Builder prefixLeg(boolean prefixLeg) {
			this.prefixLeg = prefixLeg;
			return this;
		}

		/**
		 * Sets shortest fragment allowed unfinished-word expansion.
		 * @param minFragmentLength shortest fragment allowed unfinished-word expansion
		 * @return this builder
		 */
		@Nonnull
		public Builder minFragmentLength(int minFragmentLength) {
			this.minFragmentLength = minFragmentLength;
			return this;
		}

		/**
		 * Sets shortest fragment allowed completion with typos.
		 * @param openEndedStartLength shortest fragment allowed completion with typos
		 * @return this builder
		 */
		@Nonnull
		public Builder openEndedStartLength(int openEndedStartLength) {
			this.openEndedStartLength = openEndedStartLength;
			return this;
		}

		/**
		 * Builds and validates the options.
		 * @return validated fulltext options
		 */
		@Nonnull
		public FulltextOptions build() {
			return new FulltextOptions(
				this.minLengthOneTypo,
				this.minLengthTwoTypos,
				this.nonFuzzyPrefix,
				this.transpositions,
				this.digitsExact,
				this.prefixLeg,
				this.minFragmentLength,
				this.openEndedStartLength
			);
		}
	}
}
