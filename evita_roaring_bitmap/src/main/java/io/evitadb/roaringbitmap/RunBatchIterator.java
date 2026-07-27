package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * {@link ContainerBatchIterator} over a {@link RunContainer}: expands the container's
 * `(start, length)` runs into ascending values, each added to the caller's 16-bit key. A single
 * {@link #next} call may emit only part of a run and resume mid-run on the following call via
 * {@link #cursor}. Reusable via {@link #wrap(RunContainer)} so a single instance can be recycled
 * across chunks without allocating.
 */
final class RunBatchIterator implements ContainerBatchIterator {

	/**
	 * Container currently being iterated, or `null` after {@link #releaseContainer()}.
	 */
	@Nullable private RunContainer runs;
	/**
	 * Index of the run currently being expanded within {@link #runs}.
	 */
	private int run = 0;
	/**
	 * Values already emitted within the current run; lets {@link #next} resume a run that spilled past the buffer.
	 */
	private int cursor = 0;

	/**
	 * Creates an iterator positioned at the start of the first run of `runs`.
	 *
	 * @param runs container to iterate
	 */
	public RunBatchIterator(@Nonnull final RunContainer runs) {
		wrap(runs);
	}

	/**
	 * Expands runs into `buffer` until it is full or every run is consumed. Each value is
	 * `key + runStart + offsetWithinRun` (disjoint halves, so the additions act as an OR); a run that
	 * does not fit is split, with {@link #cursor} recording how far it was emitted so the next call
	 * resumes it.
	 */
	@Override
	public int next(final int key, @Nonnull final int[] buffer, final int offset) {
		int consumed = 0;
		final RunContainer container = Objects.requireNonNull(
			this.runs, "RunBatchIterator: container released or never wrapped");
		do {
			final int runStart = (container.getValue(this.run));
			final int runLength = (container.getLength(this.run));
			final int chunkStart = runStart + this.cursor;
			final int usableBufferLength = buffer.length - offset - consumed;
			final int chunkEnd = chunkStart + Math.min(runLength - this.cursor, usableBufferLength - 1);
			final int chunk = chunkEnd - chunkStart + 1;
			for (int i = 0; i < chunk; ++i) {
				buffer[offset + consumed + i] = key + chunkStart + i;
			}
			consumed += chunk;
			if (runStart + runLength == chunkEnd) {
				++this.run;
				this.cursor = 0;
			} else {
				this.cursor += chunk;
			}
		} while ((offset + consumed) < buffer.length && this.run != container.numberOfRuns());
		return consumed;
	}

	/**
	 * More values remain while unconsumed runs are left.
	 */
	@Override
	public boolean hasNext() {
		return this.run < Objects.requireNonNull(this.runs, "RunBatchIterator: container released or never wrapped")
			.numberOfRuns();
	}

	/**
	 * Shallow fork — the copy shares the backing container and resumes from the current run and offset.
	 */
	@Nonnull
	@Override
	public ContainerBatchIterator clone() {
		try {
			return (ContainerBatchIterator) super.clone();
		} catch (CloneNotSupportedException e) {
			// won't happen
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Drops the backing-container reference once drained so it can be garbage-collected.
	 */
	@Override
	public void releaseContainer() {
		this.runs = null;
	}

	/**
	 * Scans runs forward until it finds the one covering `target`, setting {@link #cursor} to
	 * `target`'s offset within it (or to the start of the first run beyond `target`), so the next
	 * emitted value is `>=` `target`.
	 */
	@Override
	public void advanceIfNeeded(final char target) {
		final RunContainer container = Objects.requireNonNull(
			this.runs, "RunBatchIterator: container released or never wrapped");
		do {
			final int runStart = container.getValue(this.run);
			final int runLength = container.getLength(this.run);
			if (runStart > target) {
				this.cursor = 0;
				break;
			}
			final int offset = target - runStart;
			if (offset <= runLength) {
				this.cursor = offset;
				break;
			}
			++this.run;
			this.cursor = 0;
		} while (this.run != container.numberOfRuns());
	}

	/**
	 * Re-targets this iterator at `runs` and rewinds to its first run, letting one instance be reused
	 * across containers without allocation.
	 *
	 * @param runs container to iterate next
	 */
	void wrap(@Nonnull final RunContainer runs) {
		this.runs = runs;
		this.run = 0;
		this.cursor = 0;
	}
}
