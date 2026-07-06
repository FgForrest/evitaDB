package io.evitadb.roaringbitmap.art;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Depth-first walk over an {@link Art}'s leaves in key order, driven by a {@link Shuttle}
 * ({@link ForwardShuttle} for ascending, {@link BackwardShuttle} for descending). Optionally starts
 * from a bound and supports {@link #remove()}, which also frees the removed key's container. Used by
 * {@link KeyIterator} and by {@link io.evitadb.roaringbitmap.longlong.HighLowContainer} range scans.
 *
 * {@link #hasNext()} performs the advance and memoises the leaf until {@link #next()} consumes it.
 */
public class LeafNodeIterator implements Iterator<LeafNode> {

	/**
	 * Stack-based tree walker producing leaves in the requested direction; `null` when the tree is empty.
	 */
	@Nullable private Shuttle shuttle;
	/**
	 * Whether {@link #current} holds a valid leaf.
	 */
	private boolean hasCurrent;
	/**
	 * Leaf produced by the last advance, returned by {@link #next()} and {@link #peekNext()}.
	 */
	@Nullable private LeafNode current;
	/**
	 * True once {@link #hasNext()} has advanced but {@link #next()} has not yet consumed the result.
	 */
	private boolean calledHasNext;
	/**
	 * True when the tree was empty at construction, short-circuiting all traversal.
	 */
	private final boolean isEmpty;

	/**
	 * constructor
	 *
	 * @param art        the ART
	 * @param containers the containers
	 */
	public LeafNodeIterator(@Nonnull Art art, @Nullable Containers containers) {
		this(art, false, containers);
	}

	/**
	 * constructor
	 *
	 * @param art        the ART
	 * @param reverse    false: ascending order,true: the descending order
	 * @param containers the containers
	 */
	public LeafNodeIterator(@Nonnull Art art, boolean reverse, @Nullable Containers containers) {
		this.isEmpty = art.isEmpty();
		if (this.isEmpty) {
			return;
		}
		if (!reverse) {
			this.shuttle = new ForwardShuttle(art, containers);
		} else {
			this.shuttle = new BackwardShuttle(art, containers);
		}
		this.shuttle.initShuttle();
		this.calledHasNext = false;
	}

	/**
	 * constructor
	 *
	 * @param art        the ART
	 * @param reverse    false: ascending order,true: the descending order
	 * @param containers the containers
	 * @param from       starting upper/lower bound
	 */
	public LeafNodeIterator(@Nonnull Art art, boolean reverse, @Nullable Containers containers, long from) {
		this.isEmpty = art.isEmpty();
		if (this.isEmpty) {
			return;
		}
		if (!reverse) {
			this.shuttle = new ForwardShuttle(art, containers);
		} else {
			this.shuttle = new BackwardShuttle(art, containers);
		}
		this.shuttle.initShuttleFrom(from);
		this.calledHasNext = false;
	}

	private boolean advance() {
		final boolean hasLeafNode = Objects.requireNonNull(this.shuttle, "empty iterator has no shuttle")
			.moveToNextLeaf();
		if (hasLeafNode) {
			this.hasCurrent = true;
			this.current = this.shuttle.getCurrentLeafNode();
		} else {
			this.hasCurrent = false;
			this.current = null;
		}
		return hasLeafNode;
	}

	/**
	 * Advances to the next leaf on the first call after each {@link #next()} and memoises the outcome,
	 * so repeated calls without an intervening {@link #next()} are idempotent.
	 *
	 * @return whether another leaf is available
	 */
	@Override
	public boolean hasNext() {
		if (this.isEmpty) {
			return false;
		}
		if (!this.calledHasNext) {
			this.calledHasNext = true;
			return advance();
		} else {
			return this.hasCurrent;
		}
	}

	/**
	 * Returns the next leaf, advancing first if {@link #hasNext()} was not called explicitly.
	 *
	 * @return the next leaf in iteration order
	 * @throws NoSuchElementException when no further leaf exists
	 */
	@Override
	@Nonnull
	public LeafNode next() {
		if (!this.calledHasNext) {
			hasNext();
		}
		if (!this.hasCurrent) {
			throw new NoSuchElementException();
		}
		this.calledHasNext = false;
		return Objects.requireNonNull(
			this.current,
			"ART leaf iterator has no current leaf; hasCurrent guarantees a staged leaf before next() returns"
		);
	}

	@Override
	public void remove() {
		Objects.requireNonNull(this.shuttle, "empty iterator has no shuttle").remove();
	}

	/**
	 * Move this iterator to the leaf that contains `boundval`.
	 *
	 * If no leaf contains `boundval`, then move to the next largest (on forward iterators
	 * or next smallest (on backwards iterators).
	 */
	public void seek(long boundval) {
		Objects.requireNonNull(this.shuttle, "empty iterator has no shuttle").initShuttleFrom(boundval);
		this.calledHasNext = false;
	}

	/**
	 * Return the next leaf without advancing the iterator.
	 *
	 * @return the next leaf
	 */
	@Nonnull
	public LeafNode peekNext() {
		if (!this.calledHasNext) {
			hasNext();
		}
		if (!this.hasCurrent) {
			throw new NoSuchElementException();
		}
		// don't set calledHasNext, so that multiple invocations don't advance
		return Objects.requireNonNull(
			this.current,
			"ART leaf iterator has no current leaf; hasCurrent guarantees a staged leaf before peekNext() returns"
		);
	}
}
