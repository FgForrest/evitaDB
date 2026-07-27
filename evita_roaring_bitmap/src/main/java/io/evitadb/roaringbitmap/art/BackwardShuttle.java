package io.evitadb.roaringbitmap.art;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * {@link AbstractShuttle} tuned for descending traversal: it visits {@link LeafNode}s from the
 * largest key downward. Every direction hook resolves to the "maximum-first, next-smaller" choice:
 * boundary descents start at the maximum child, sibling steps take the next smaller position, and
 * the seek order treats a key as preceding the target when it is numerically larger (unsigned).
 */
public class BackwardShuttle extends AbstractShuttle {

	BackwardShuttle(@Nonnull Art art, @Nullable Containers containers) {
		super(art, containers);
	}

	@Override
	protected boolean currentBeforeHigh(long current, long high) {
		return current > high;
	}

	@Override
	protected int visitedNodeNextPosition(@Nonnull BranchNode node, int pos) {
		return node.getNextSmallerPos(pos);
	}

	@Override
	protected int boundaryNodePosition(@Nonnull BranchNode node, boolean inRunDirection) {
		if (inRunDirection) {
			return node.getMinPos();
		} else {
			return node.getMaxPos();
		}
	}

	@Override
	protected boolean prefixMismatchIsInRunDirection(byte nodeValue, byte highValue) {
		return Byte.toUnsignedInt(nodeValue) > Byte.toUnsignedInt(highValue);
	}

	@Override
	protected int searchMissNextPosition(@Nonnull SearchResult result) {
		return result.getNextSmallerPos();
	}
}
