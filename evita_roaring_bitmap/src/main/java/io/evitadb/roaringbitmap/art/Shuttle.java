package io.evitadb.roaringbitmap.art;

import javax.annotation.Nonnull;

/**
 * A stateful cursor that walks the {@link Art} tree's {@link LeafNode}s in key order, yielding one
 * leaf per {@link #moveToNextLeaf()} step. Each leaf pairs the high 48 bits of a key with the index
 * of the value container bound to it, so iterating leaves in order is how the 64-bit bitmap exposes
 * its containers sorted by key.
 *
 * Direction is fixed by the implementation: {@link ForwardShuttle} ascends and
 * {@link BackwardShuttle} descends. A shuttle is positioned exactly once before use, either at the
 * extreme leaf ({@link #initShuttle()}) or at a caller-supplied bound
 * ({@link #initShuttleFrom(long)}); every {@link #moveToNextLeaf()} then advances by one leaf in
 * that direction. Instances hold mutable descent state, are not thread-safe, and each is driven by
 * a single {@link LeafNodeIterator}.
 */
public interface Shuttle {

	/**
	 * Positions the cursor at the extreme leaf in traversal direction: the smallest key for a forward
	 * shuttle, the largest for a backward one. The first {@link #moveToNextLeaf()} then returns it.
	 * Call exactly once before any other method, unless starting from a bound via
	 * {@link #initShuttleFrom(long)}.
	 */
	public void initShuttle();

	/**
	 * Positions the cursor at the leaf whose key equals `key`, or the nearest leaf in traversal
	 * direction when no such leaf exists: the next larger key for a forward shuttle, the next smaller
	 * for a backward one. Use in place of {@link #initShuttle()} to start iteration from a bound.
	 *
	 * @param key the lower bound (forward) or upper bound (backward) to seek to
	 */
	public void initShuttleFrom(long key);

	/**
	 * Advances the cursor to the next leaf in traversal direction and reports whether one exists. The
	 * first call after positioning yields the leaf the shuttle was placed on rather than skipping it.
	 *
	 * @return `true` if a leaf is now current and readable via {@link #getCurrentLeafNode()}, `false`
	 * once the tree is exhausted
	 */
	public boolean moveToNextLeaf();

	/**
	 * Returns the leaf the cursor currently sits on; defined only after a {@link #moveToNextLeaf()}
	 * that returned `true`.
	 *
	 * @return the leaf at the current cursor position
	 */
	@Nonnull
	public LeafNode getCurrentLeafNode();

	/**
	 * Removes the current leaf and its associated value container from the tree, then repairs the
	 * cursor so iteration can continue from the following leaf. Backs
	 * {@link java.util.Iterator#remove()} on {@link LeafNodeIterator}.
	 */
	public void remove();
}
