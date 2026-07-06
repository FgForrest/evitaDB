package io.evitadb.roaringbitmap.art;

import io.evitadb.roaringbitmap.art.Art.Toolkit;
import io.evitadb.roaringbitmap.longlong.LongUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Objects;

/**
 * Shared base for the two {@link Shuttle} directions. Owns the depth-first descent through the
 * {@link Art} tree using an explicit path stack (no recursion is retained between steps) and
 * delegates the choice of *which* child to visit next to the concrete subclass.
 *
 * The tree stores 48-bit keys, so a root-to-leaf path is at most seven nodes deep (`MAX_DEPTH`);
 * {@link #stack} holds that path with `stack[depth]` as the current frontier. Each
 * {@link #moveToNextLeaf()} pops the just-emitted leaf, walks back up the stack to the nearest node
 * that still has an unvisited child in traversal direction, then descends from there to that
 * subtree's boundary leaf.
 *
 * Subclasses fix the direction by implementing a small set of ordering hooks
 * ({@link #boundaryNodePosition}, {@link #visitedNodeNextPosition},
 * {@link #searchMissNextPosition}, {@link #currentBeforeHigh},
 * {@link #prefixMismatchIsInRunDirection}): {@link ForwardShuttle} maps them to ascending order and
 * {@link BackwardShuttle} to descending. "Run direction" throughout these hooks means the direction
 * the shuttle is travelling.
 */
public abstract class AbstractShuttle implements Shuttle {

	/**
	 * Deepest possible root-to-leaf path: six prefix bytes of a 48-bit key plus the leaf itself.
	 */
	protected static final int MAX_DEPTH = 7;
	/**
	 * Depth-first path from the root to the current node; `stack[depth]` is the active frontier.
	 */
	@Nonnull protected NodeEntry[] stack = new NodeEntry[MAX_DEPTH];
	/**
	 * Zero-based index of the top of {@link #stack}; `-1` means uninitialised or exhausted.
	 */
	protected int depth = -1;
	/**
	 * Guards the first {@link #moveToNextLeaf()} so it emits the positioned leaf instead of skipping
	 * to the one after it.
	 */
	protected boolean hasRun = false;
	/**
	 * The tree being traversed.
	 */
	@Nonnull protected Art art;
	/**
	 * Value-container store, consulted only by {@link #remove()}; `null` when removal is unused.
	 */
	@Nullable protected Containers containers;

	public AbstractShuttle(@Nonnull Art art, @Nullable Containers containers) {
		this.art = art;
		this.containers = containers;
	}

	@Override
	public void initShuttle() {
		visitToLeaf(this.art.getRoot(), false);
	}

	@Override
	public void initShuttleFrom(long key) {
		this.depth = -1; // reset
		final byte[] high = LongUtils.highPart(key);
		final long highAsLong = LongUtils.rightShiftHighPart(key);
		visitToLeafFrom(high, 0, this.art.getRoot());
		// If the target container doesn't exist, we may end up in the previous existing leaf here
		if (currentBeforeHigh(getCurrentLeafNode().getKey(), highAsLong)) {
			// Move the following leaf instead
			this.hasRun = true; // make it actually move
			moveToNextLeaf();
		}
		this.hasRun = false; // reset
	}

	@Override
	public boolean moveToNextLeaf() {
		if (this.depth < 0) {
			return false;
		}
		if (!this.hasRun) {
			this.hasRun = true;
			final Node node = this.stack[this.depth].node;
			return node instanceof LeafNode;
		}
		// skip the top leaf node
		final Node node = this.stack[this.depth].node;
		if (node instanceof LeafNode) {
			this.depth--;
		}
		// visit parent node
		while (this.depth >= 0) {
			final NodeEntry currentNodeEntry = this.stack[this.depth];
			if (currentNodeEntry.node instanceof LeafNode) {
				// found current leaf node's next sibling node to benefit the removing operation
				if (this.depth - 1 >= 0) {
					findNextSiblingKeyOfLeafNode();
				}
				return true;
			}
			// visit the next child node
			final BranchNode currentBranchNode = (BranchNode) Objects.requireNonNull(
				currentNodeEntry.node, "ART shuttle stack frame must hold a node during descent");
			int pos;
			int nextPos;
			if (!currentNodeEntry.visited) {
				pos = boundaryNodePosition(currentBranchNode, false);
				currentNodeEntry.position = pos;
				nextPos = pos;
				currentNodeEntry.visited = true;
			} else if (currentNodeEntry.startFromNextSiblingPosition) {
				nextPos = currentNodeEntry.position;
				currentNodeEntry.startFromNextSiblingPosition = false;
			} else {
				pos = currentNodeEntry.position;
				nextPos = visitedNodeNextPosition(currentBranchNode, pos);
			}
			if (nextPos != BranchNode.ILLEGAL_IDX) {
				this.stack[this.depth].position = nextPos;
				this.depth++;
				// add a fresh entry on the top of the visiting stack
				final NodeEntry freshEntry = new NodeEntry();
				freshEntry.node = currentBranchNode.getChild(nextPos);
				this.stack[this.depth] = freshEntry;
			} else {
				// current internal node doesn't have anymore unvisited child,move to a top node
				this.depth--;
			}
		}
		return false;
	}

	@Override
	@Nonnull
	public LeafNode getCurrentLeafNode() {
		final NodeEntry currentNode = this.stack[this.depth];
		return (LeafNode) Objects.requireNonNull(currentNode.node, "ART shuttle is not positioned on a leaf node");
	}

	@Override
	public void remove() {
		final byte[] currentLeafKey = getCurrentLeafNode().getKeyBytes();
		final Toolkit toolkit = this.art.removeSpecifyKey(this.art.getRoot(), currentLeafKey, 0);
		if (toolkit == null) {
			return;
		}
		if (this.containers != null) {
			this.containers.remove(toolkit.matchedContainerId);
		}
		final Node node = toolkit.freshMatchedParentNode;
		if (this.depth - 1 >= 0) {
			// update the parent node to a fresh node as the parent node may changed by the
			// art adaptive removing logic
			final NodeEntry oldEntry = this.stack[this.depth - 1];
			oldEntry.visited = oldEntry.node == node;
			oldEntry.node = node;
			oldEntry.startFromNextSiblingPosition = true;
			if (node instanceof BranchNode) {
				oldEntry.position = ((BranchNode) node).getChildPos(oldEntry.leafNodeNextSiblingKey);
			}
		}
	}

	/**
	 * Orders a landed key against the seek target in traversal direction: `true` when `current` sits
	 * before `high` along the direction of travel (`current < high` for {@link ForwardShuttle},
	 * `current > high` for {@link BackwardShuttle}). {@link #initShuttleFrom(long)} uses it to detect
	 * that a seek for an absent key settled on the wrong side of the target and must advance one leaf.
	 *
	 * @param current the key of the leaf the seek landed on
	 * @param high    the requested seek target
	 * @return `true` if `current` precedes `high` in traversal direction
	 */
	protected abstract boolean currentBeforeHigh(long current, long high);

	/**
	 * Returns the sibling position that follows `pos` in traversal direction: the next larger child
	 * for {@link ForwardShuttle}, the next smaller for {@link BackwardShuttle}, or
	 * {@link BranchNode#ILLEGAL_IDX} when `pos` is already the last child in that direction. Drives
	 * the step from an exhausted child to the next one during {@link #moveToNextLeaf()}.
	 *
	 * @param node the branch node whose children are being walked
	 * @param pos  the position of the child just finished
	 * @return the next sibling position, or {@link BranchNode#ILLEGAL_IDX} if `pos` is the last child
	 */
	protected abstract int visitedNodeNextPosition(@Nonnull BranchNode node, int pos);

	/**
	 * Returns the position of a boundary child of `node`: the extreme child a plain descent steps
	 * into.
	 *
	 * With `inRunDirection == false` this is the near boundary, leading to the first leaf iteration
	 * should emit ({@link ForwardShuttle} the smallest key, {@link BackwardShuttle} the largest). With
	 * `inRunDirection == true` it is the far boundary in the direction of travel, used while seeking
	 * when the target lies past every child here: the shuttle descends to the furthest reachable leaf
	 * so the following step can cross into the neighbouring subtree.
	 *
	 * @param node           the branch node to inspect
	 * @param inRunDirection `false` for the near (first-emitted) boundary, `true` for the far boundary
	 *                       reached when a seek overshoots the node's range
	 * @return the position of the boundary child to descend into
	 */
	protected abstract int boundaryNodePosition(@Nonnull BranchNode node, boolean inRunDirection);

	/**
	 * Resolves the descent direction when a branch's compressed prefix first diverges from the seek
	 * key. Returns `true` when the subtree precedes the target in traversal order: its mismatching
	 * prefix byte compares below the key byte for {@link ForwardShuttle} (`nodeValue < highValue`) or
	 * above it for {@link BackwardShuttle} (`nodeValue > highValue`), so the seek descends to that
	 * subtree's far {@link #boundaryNodePosition} and then steps across into the neighbouring subtree.
	 * Once a prefix byte mismatches, the remaining key bytes can no longer steer the descent.
	 *
	 * @param nodeValue the prefix byte at the point of divergence
	 * @param highValue the corresponding byte of the seek key
	 * @return `true` to head for the subtree's far boundary (subtree precedes the target), `false` for
	 * its near boundary (subtree follows the target)
	 */
	protected abstract boolean prefixMismatchIsInRunDirection(byte nodeValue, byte highValue);

	/**
	 * On a seek that finds no child matching the target key byte, returns the neighbouring child to
	 * continue toward in traversal direction: the next larger child for {@link ForwardShuttle}, the
	 * next smaller for {@link BackwardShuttle}, or {@link BranchNode#ILLEGAL_IDX} when the target
	 * lies past every child, in which case {@link #initShuttleFrom(long)} falls back to the far
	 * {@link #boundaryNodePosition}.
	 *
	 * @param result the miss outcome from {@link BranchNode#getNearestChildPos(byte)}
	 * @return the neighbouring child position, or {@link BranchNode#ILLEGAL_IDX} if the target is
	 * beyond this node's range
	 */
	protected abstract int searchMissNextPosition(@Nonnull SearchResult result);

	private void visitToLeaf(@Nullable Node node, boolean inRunDirection) {
		if (node == null) {
			return;
		}
		if (node == this.art.getRoot()) {
			final NodeEntry nodeEntry = new NodeEntry();
			nodeEntry.node = node;
			this.depth = 0;
			this.stack[this.depth] = nodeEntry;
		}
		if (node instanceof LeafNode) {
			// leaf node's corresponding NodeEntry will not have the position member set.
			if (this.depth - 1 >= 0) {
				findNextSiblingKeyOfLeafNode();
			}
			return;
		}
		if (this.depth == MAX_DEPTH) {
			return;
		}
		final BranchNode branchNode = (BranchNode) node;
		// find next min child
		final int pos = boundaryNodePosition(branchNode, inRunDirection);
		this.stack[this.depth].position = pos;
		this.stack[this.depth].visited = true;
		final Node child = branchNode.getChild(pos);
		final NodeEntry childNodeEntry = new NodeEntry();
		childNodeEntry.node = child;
		this.depth++;
		this.stack[this.depth] = childNodeEntry;
		visitToLeaf(child, inRunDirection);
	}

	private void visitToLeafFrom(@Nonnull byte[] high, int keyDepth, @Nullable Node node) {
		if (node == null) {
			return;
		}
		if (node == this.art.getRoot()) {
			final NodeEntry nodeEntry = new NodeEntry();
			nodeEntry.node = node;
			this.depth = 0;
			this.stack[this.depth] = nodeEntry;
		}
		if (node instanceof LeafNode) {
			// leaf node's corresponding NodeEntry will not have the position member set.
			if (this.depth - 1 >= 0) {
				findNextSiblingKeyOfLeafNode();
			}
			return;
		}
		if (this.depth == MAX_DEPTH) {
			return;
		}

		final BranchNode branchNode = (BranchNode) node;

		final byte branchNodePrefixLength = branchNode.prefixLength();
		if (branchNodePrefixLength > 0) {
			final int commonLength =
				Art.commonPrefixLength(high, keyDepth, high.length, branchNode.prefix, 0, branchNodePrefixLength);
			if (commonLength != branchNodePrefixLength) {
				final byte nodeValue = branchNode.prefix[commonLength];
				final byte highValue = high[keyDepth + commonLength];
				final boolean visitDirection = prefixMismatchIsInRunDirection(nodeValue, highValue);
				// once we miss a single match, there's no point comparing parts of the key anymore
				visitToLeaf(node, visitDirection);
				return;
			}
			// common prefix is the same ,then increase the depth
			keyDepth += branchNode.prefixLength();
		}
		// find next child
		final SearchResult result = branchNode.getNearestChildPos(high[keyDepth]);
		int pos;
		boolean continueAtBoundary = false;
		boolean continueInRunDirection = false;
		switch (result.outcome) {
			case FOUND:
				pos = result.getKeyPos();
				break;
			case NOT_FOUND:
				pos = searchMissNextPosition(result);
				continueAtBoundary = true;
				if (pos == BranchNode.ILLEGAL_IDX) {
					pos = boundaryNodePosition(branchNode, true);
					continueInRunDirection = true;
				}
				break;
			default:
				throw new IllegalStateException("There only two possible search outcomes");
		}
		this.stack[this.depth].position = pos;
		this.stack[this.depth].visited = true;
		final Node child = branchNode.getChild(pos);
		final NodeEntry childNodeEntry = new NodeEntry();
		childNodeEntry.node = child;
		this.depth++;
		this.stack[this.depth] = childNodeEntry;
		if (continueAtBoundary) {
			// once we miss a single match, there's no point comparing parts of the key anymore
			// we just descend as far in run direction as possible
			visitToLeaf(child, continueInRunDirection);
		} else {
			visitToLeafFrom(high, keyDepth + 1, child);
		}
	}

	private void findNextSiblingKeyOfLeafNode() {
		final BranchNode parentNode = (BranchNode) Objects.requireNonNull(
			this.stack[this.depth - 1].node, "ART shuttle parent frame must hold a branch node");
		final int nextSiblingPos = visitedNodeNextPosition(parentNode, this.stack[this.depth - 1].position);
		if (nextSiblingPos != BranchNode.ILLEGAL_IDX) {
			this.stack[this.depth - 1].leafNodeNextSiblingKey = parentNode.getChildKey(nextSiblingPos);
		}
	}

	/**
	 * One frame of the descent {@link #stack}: a node plus bookkeeping to resume the walk.
	 */
	static class NodeEntry {
		/**
		 * The node this frame sits on; `null` only in a freshly allocated, not-yet-filled entry.
		 */
		@Nullable Node node = null;
		/**
		 * Child position last descended into; {@link BranchNode#ILLEGAL_IDX} for a leaf frame.
		 */
		int position = BranchNode.ILLEGAL_IDX;
		/**
		 * Whether a boundary child was already chosen, so the next step advances to a sibling.
		 */
		boolean visited = false;
		/**
		 * Set by {@link AbstractShuttle#remove()} so the walk resumes at the sibling position that
		 * adaptive restructuring left behind, rather than re-picking a boundary child.
		 */
		boolean startFromNextSiblingPosition = false;
		/**
		 * Key of the current leaf's next sibling, cached so this parent frame can be relocated after
		 * {@link AbstractShuttle#remove()} reshapes the branch that held it.
		 */
		byte leafNodeNextSiblingKey;
	}
}
