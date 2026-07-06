package io.evitadb.roaringbitmap.art;

/**
 * Discriminator for the concrete {@link Node} kinds that make up the Adaptive Radix Tree.
 *
 * The four `NODE*` constants are the adaptive inner-node sizes ordered by fan-out: a
 * {@link BranchNode} holds 4, 16, 48 or 256 children and is promoted (or demoted) to the next
 * bucket as children are inserted or removed. `LEAF_NODE` marks a {@link LeafNode} that terminates a
 * path and carries the key-to-container mapping.
 *
 * The `ordinal()` of each constant is written verbatim as the first byte of a serialized node (see
 * {@link Node#serialize} / {@link Node#deserialize}), so the declaration order is part of the
 * on-disk format and must not be reordered.
 */
public enum NodeType {
	NODE4,
	NODE16,
	NODE48,
	NODE256,
	LEAF_NODE,
	/**
	 * Sentinel retained for parity with the upstream RoaringBitmap ART; not referenced in this module.
	 */
	DUMMY_ROOT;
}
