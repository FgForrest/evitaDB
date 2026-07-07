package io.evitadb.roaringbitmap.art;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * {@link AbstractShuttle} tuned for ascending traversal: it visits {@link LeafNode}s from the
 * smallest key upward. Every direction hook resolves to the "minimum-first, next-larger" choice:
 * boundary descents start at the minimum child, sibling steps take the next larger position, and
 * the seek order treats a key as preceding the target when it is numerically smaller (unsigned).
 */
public class ForwardShuttle extends AbstractShuttle {

	ForwardShuttle(@Nonnull Art art, @Nullable Containers containers) {
		super(art, containers);
	}

	@Override
	protected boolean currentBeforeHigh(long current, long high) {
		return current < high;
	}

	@Override
	protected int visitedNodeNextPosition(@Nonnull BranchNode node, int pos) {
		return node.getNextLargerPos(pos);
	}

	@Override
	protected int boundaryNodePosition(@Nonnull BranchNode node, boolean inRunDirection) {
		if (inRunDirection) {
			return node.getMaxPos();
		} else {
			return node.getMinPos();
		}
	}

	@Override
	protected boolean prefixMismatchIsInRunDirection(byte nodeValue, byte highValue) {
		return Byte.toUnsignedInt(nodeValue) < Byte.toUnsignedInt(highValue);
	}

	@Override
	protected int searchMissNextPosition(@Nonnull SearchResult result) {
		return result.getNextLargerPos();
	}
}
