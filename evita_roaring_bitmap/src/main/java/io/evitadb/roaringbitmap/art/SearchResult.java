package io.evitadb.roaringbitmap.art;

import javax.annotation.Nonnull;

/**
 * Outcome of searching a {@link BranchNode} for the child matching a single key byte. Beyond a hit or
 * miss it carries the flanking child positions, which the shuttles use to continue a range scan at
 * the nearest neighbour when the exact byte is absent.
 */
class SearchResult {

	/**
	 * Whether the searched key byte matched a child.
	 */
	static enum Outcome {
		/**
		 * The key byte matched a child; its position is available via {@link SearchResult#getKeyPos()}.
		 */
		FOUND,
		/**
		 * No child matched; the flanking smaller/larger positions are available instead.
		 */
		NOT_FOUND
	}

	/**
	 * Whether the key byte was found among the children.
	 */
	@Nonnull final Outcome outcome;

	/**
	 * Position of the child at or below the searched key: the exact match when {@link Outcome#FOUND},
	 * otherwise the nearest smaller child, or {@link BranchNode#ILLEGAL_IDX} when none is smaller.
	 */
	private final int lessOrEqualPos;

	/**
	 * Position of the nearest child above the searched key; meaningful only on {@link Outcome#NOT_FOUND}.
	 */
	private final int greaterPos;

	private SearchResult(@Nonnull Outcome outcome, int lessOrEqualPos, int greaterPos) {
		this.outcome = outcome;
		this.lessOrEqualPos = lessOrEqualPos;
		this.greaterPos = greaterPos;
	}

	/**
	 * Creates a {@link Outcome#FOUND} result for an exact child match.
	 *
	 * @param keyPos position of the matching child
	 */
	@Nonnull
	static SearchResult found(int keyPos) {
		return new SearchResult(Outcome.FOUND, keyPos, BranchNode.ILLEGAL_IDX);
	}

	/**
	 * Creates a {@link Outcome#NOT_FOUND} result carrying the flanking child positions.
	 *
	 * @param lowerPos  nearest smaller child position, or {@link BranchNode#ILLEGAL_IDX} if none
	 * @param higherPos nearest larger child position, or {@link BranchNode#ILLEGAL_IDX} if none
	 */
	@Nonnull
	static SearchResult notFound(int lowerPos, int higherPos) {
		return new SearchResult(Outcome.NOT_FOUND, lowerPos, higherPos);
	}

	/**
	 * @return whether an exact match position is available (i.e. the outcome is {@link Outcome#FOUND}).
	 */
	boolean hasKeyPos() {
		if (this.outcome == Outcome.FOUND) {
			// this would be an illegal state
			assert this.lessOrEqualPos != BranchNode.ILLEGAL_IDX;
			return true;
		}
		return false;
	}

	/**
	 * @return the exact match position
	 * @throws IllegalAccessError when the outcome is not {@link Outcome#FOUND}
	 */
	int getKeyPos() {
		if (this.outcome == Outcome.FOUND) {
			return this.lessOrEqualPos;
		}
		throw new IllegalAccessError("Only results with outcome FOUND have this field!");
	}

	/**
	 * @return whether a nearest-smaller child position is available on a miss.
	 */
	boolean hasNextSmallerPos() {
		return this.outcome == Outcome.NOT_FOUND && this.lessOrEqualPos != BranchNode.ILLEGAL_IDX;
	}

	/**
	 * @return the nearest-smaller child position
	 * @throws IllegalAccessError when the outcome is not {@link Outcome#NOT_FOUND}
	 */
	int getNextSmallerPos() {
		if (this.outcome == Outcome.NOT_FOUND) {
			return this.lessOrEqualPos;
		}
		throw new IllegalAccessError("Only results with outcome NOT_FOUND have this field!");
	}

	/**
	 * @return whether a nearest-larger child position is available on a miss.
	 */
	boolean hasNextLargerPos() {
		return this.outcome == Outcome.NOT_FOUND && this.greaterPos != BranchNode.ILLEGAL_IDX;
	}

	/**
	 * @return the nearest-larger child position
	 * @throws IllegalAccessError when the outcome is not {@link Outcome#NOT_FOUND}
	 */
	int getNextLargerPos() {
		if (this.outcome == Outcome.NOT_FOUND) {
			return this.greaterPos;
		}
		throw new IllegalAccessError("Only results with outcome NOT_FOUND have this field!");
	}
}
