package io.evitadb.roaringbitmap.art;

import io.evitadb.roaringbitmap.Container;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.Objects;

/**
 * Iterates over every non-null {@link Container} held by a {@link Containers} store, in ascending
 * container-index order (first level, then within each second-level array). Backs
 * {@link io.evitadb.roaringbitmap.longlong.HighLowContainer}'s container traversal.
 *
 * Advancing happens in {@link #hasNext()}; {@link #next()} only returns the staged container, so
 * {@link #hasNext()} must be called before each {@link #next()}.
 */
public class ContainerIterator implements Iterator<Container> {

	/**
	 * Store being traversed.
	 */
	@Nonnull private final Containers containers;
	/**
	 * Cursor over the first-level list of second-level arrays.
	 */
	@Nonnull private final Iterator<Container[]> containerArrIte;
	/**
	 * Second-level array currently being scanned.
	 */
	@Nullable private Container[] currentSecondLevelArr;
	/**
	 * Length of {@link #currentSecondLevelArr}.
	 */
	private int currentSecondLevelArrSize;
	/**
	 * Scan offset within {@link #currentSecondLevelArr}; sits one past the last returned slot.
	 */
	private int currentSecondLevelArrIdx;
	/**
	 * Index of {@link #currentSecondLevelArr} within the first level.
	 */
	private int currentFistLevelArrIdx;
	/**
	 * Whether the current second-level array has been fully scanned.
	 */
	private boolean currentSecondLevelArrIteOver;
	/**
	 * Container staged by {@link #hasNext()} for the next {@link #next()} to return.
	 */
	@Nullable private Container currentContainer;
	/**
	 * Whether {@link #next()} has already consumed {@link #currentContainer}.
	 */
	private boolean consumedCurrent;

	/**
	 * construct a containers iterator
	 *
	 * @param containers the containers
	 */
	public ContainerIterator(@Nonnull Containers containers) {
		this.containers = containers;
		this.containerArrIte = containers.getContainerArrays().iterator();
		this.currentSecondLevelArrIteOver = true;
		this.consumedCurrent = true;
		this.currentFistLevelArrIdx = -1;
		this.currentSecondLevelArrIdx = 0;
		this.currentSecondLevelArrSize = 0;
	}

	/**
	 * Advances to the next non-null container and stages it for {@link #next()}. Unlike a plain
	 * {@link Iterator}, this method performs the traversal work, so it must be called before each
	 * {@link #next()}.
	 *
	 * @return whether another container is available
	 */
	@Override
	public boolean hasNext() {
		final boolean hasContainer = this.containers.getContainerSize() > 0;
		if (!hasContainer) {
			return false;
		}
		if (!this.consumedCurrent) {
			return true;
		}
		boolean foundOneContainer = false;
		while (this.currentSecondLevelArrIteOver && this.containerArrIte.hasNext()) {
			this.currentSecondLevelArr = this.containerArrIte.next();
			this.currentFistLevelArrIdx++;
			this.currentSecondLevelArrIdx = 0;
			this.currentSecondLevelArrSize = this.currentSecondLevelArr.length;
			while (this.currentSecondLevelArrIdx < this.currentSecondLevelArrSize) {
				final Container container = this.currentSecondLevelArr[this.currentSecondLevelArrIdx];
				if (container != null) {
					this.currentContainer = container;
					this.consumedCurrent = false;
					this.currentSecondLevelArrIteOver = false;
					foundOneContainer = true;
					this.currentSecondLevelArrIdx++;
					break;
				} else {
					this.currentSecondLevelArrIdx++;
				}
			}
		}
		if (!this.currentSecondLevelArrIteOver && !foundOneContainer) {
			while (this.currentSecondLevelArrIdx < this.currentSecondLevelArrSize) {
				final Container container = Objects.requireNonNull(
					this.currentSecondLevelArr,
					"ART container iterator must have a staged second-level array while scanning it"
				)[this.currentSecondLevelArrIdx];
				if (container != null) {
					this.currentContainer = container;
					this.consumedCurrent = false;
					this.currentSecondLevelArrIdx++;
					foundOneContainer = true;
					break;
				} else {
					this.currentSecondLevelArrIdx++;
				}
			}
			if (this.currentSecondLevelArrIdx == this.currentSecondLevelArrSize) {
				this.currentSecondLevelArrIteOver = true;
			}
		}
		return foundOneContainer;
	}

	/**
	 * Returns the container staged by the preceding {@link #hasNext()} and marks it consumed; does no
	 * advancing of its own.
	 *
	 * @return the current container
	 */
	@Override
	@Nonnull
	public Container next() {
		this.consumedCurrent = true;
		return Objects.requireNonNull(
			this.currentContainer,
			"ART container iterator has no staged container; hasNext() must return true before next()"
		);
	}

	/**
	 * @return the packed container index (see {@link Containers#getContainer}) of the container last
	 * returned by {@link #next()}
	 */
	public long getCurrentContainerIdx() {
		final int secondLevelArrIdx = this.currentSecondLevelArrIdx - 1;
		return Containers.toContainerIdx(this.currentFistLevelArrIdx, secondLevelArrIdx);
	}

	/**
	 * replace current container
	 *
	 * @param container the fresh container which is to replace the current old one
	 */
	public void replace(@Nonnull Container container) {
		final int secondLevelArrIdx = this.currentSecondLevelArrIdx - 1;
		this.containers.replace(this.currentFistLevelArrIdx, secondLevelArrIdx, container);
	}
}
