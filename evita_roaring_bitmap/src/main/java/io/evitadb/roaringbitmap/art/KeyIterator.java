package io.evitadb.roaringbitmap.art;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.Objects;

/**
 * Iterates the tree's 48-bit keys in ascending order, exposing each key as raw bytes or as a `long`
 * along with its container index. A thin adapter over {@link LeafNodeIterator}; backs
 * {@link io.evitadb.roaringbitmap.longlong.HighLowContainer}'s key traversal.
 *
 * As with the underlying leaf iterator, {@link #hasNext()} performs the advance and caches the leaf,
 * so it must be called before each {@link #next()}.
 */
public class KeyIterator implements Iterator<byte[]> {

	/**
	 * Leaf cached by the last {@link #hasNext()} call; its key and container index feed the accessors.
	 */
	@Nullable private LeafNode current;
	/**
	 * Underlying leaf traversal this iterator adapts to keys.
	 */
	@Nonnull private final LeafNodeIterator leafNodeIterator;

	public KeyIterator(@Nonnull Art art, @Nullable Containers containers) {
		this.leafNodeIterator = new LeafNodeIterator(art, containers);
		this.current = null;
	}

	/**
	 * Advances to the next leaf and caches it for the {@link #next()}, {@link #nextKey()} and
	 * {@link #currentContainerIdx()} accessors. Must be called before each {@link #next()}.
	 *
	 * @return whether another key is available
	 */
	@Override
	public boolean hasNext() {
		final boolean hasNext = this.leafNodeIterator.hasNext();
		if (hasNext) {
			this.current = this.leafNodeIterator.next();
		}
		return hasNext;
	}

	/**
	 * Returns the key cached by the preceding {@link #hasNext()} as a 6-byte array; does no advancing.
	 *
	 * @return the current key's bytes
	 */
	@Override
	@Nonnull
	public byte[] next() {
		return Objects.requireNonNull(
				this.current, "ART key iterator has no cached leaf; hasNext() must return true before next()")
			.getKeyBytes();
	}

	/**
	 * Returns the following key's bytes without advancing this iterator.
	 *
	 * @return the next key's bytes
	 */
	@Nonnull
	public byte[] peekNext() {
		return this.leafNodeIterator.peekNext().getKeyBytes();
	}

	/**
	 * @return the current key as a `long` (its 48 significant bits right-aligned).
	 */
	public long nextKey() {
		return Objects.requireNonNull(
			this.current, "ART key iterator has no cached leaf; hasNext() must return true before nextKey()").getKey();
	}

	/**
	 * @return the container index associated with the current key.
	 */
	public long currentContainerIdx() {
		return Objects.requireNonNull(
			this.current,
			"ART key iterator has no cached leaf; hasNext() must return true before currentContainerIdx()"
		).getContainerIdx();
	}

	@Override
	public void remove() {
		this.leafNodeIterator.remove();
	}
}
