/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.traffic;

import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * Replaces {@link java.nio.channels.FileChannel} region locks for the {@link DiskRingBuffer} disk buffer file.
 * OS-level {@code FileChannel} locks are JVM-wide, so two overlapping same-JVM lock requests throw
 * {@link java.nio.channels.OverlappingFileLockException} immediately instead of queueing - they only ever block
 * across processes, which this single-process transient buffer never has. This class provides the equivalent
 * span-granular locking entirely in-JVM, with an asymmetric conflict policy tailored to the writer/reader roles:
 *
 * - a reader/exporter gives up immediately (never blocks a request thread) if its span is exclusively held
 *   *or* an exclusive acquisition is already pending on a conflicting span (writer preference);
 * - the writer (flush drain) that finds its span shared-held waits until the sharers release; writer preference
 *   guarantees no new conflicting sharer can be granted while it waits, so the wait is bounded by the in-flight
 *   session copies that were already holding the span when the writer arrived;
 * - two readers on overlapping spans always share freely.
 *
 * A span may wrap the physical end of the ring buffer file, in which case it is represented as two segments;
 * two spans conflict iff any of their segments overlap. At any moment there are at most a handful of holders
 * (one writer - the recorder's drain lock serializes writers - plus the export and a few UI readers), so a single
 * `ReentrantLock` guarding a small list of held spans is the right size for this problem; no interval tree,
 * no striping.
 *
 * Lock-ordering / deadlock argument: the writer holds the recorder's drain lock and may wait on this span
 * lock's condition; readers/the exporter hold span tokens and never acquire the drain lock while holding one.
 * {@link DiskRingBuffer#exportSnapshot} invokes its session consumer while holding a shared span token, so that
 * consumer must never drain or export traffic recordings. With this restriction, no cycle exists.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class RingBufferSpanLock {

	/**
	 * Size of the ring buffer file this lock guards - spans wrapping past this size are split into two segments.
	 */
	private final long fileSize;
	/**
	 * Serializes span acquisition, release and pending-writer bookkeeping.
	 */
	private final ReentrantLock lock = new ReentrantLock();
	/**
	 * Wakes exclusive acquisitions when a held span is released.
	 */
	private final Condition spanReleased = this.lock.newCondition();
	/**
	 * Currently held spans. Guarded by {@link #lock}.
	 */
	private final List<Token> held = new ArrayList<>();
	/**
	 * Spans of exclusive acquisitions that are currently waiting in {@link #acquireExclusive(long, long)}. A new
	 * shared acquisition that conflicts with any of these gives up immediately (writer preference), so a steady
	 * stream of overlapping readers cannot starve a pending writer. Guarded by {@link #lock}; holds at most one
	 * entry in practice, since the recorder's drain lock serializes writers.
	 */
	private final List<Span> pendingExclusive = new ArrayList<>();

	/**
	 * Creates span coordination for a ring buffer of the given size.
	 *
	 * @param fileSize physical size of the ring buffer file in bytes
	 */
	RingBufferSpanLock(long fileSize) {
		this.fileSize = fileSize;
	}

	/**
	 * Attempts to acquire a shared lock over the given span. Returns {@code null} immediately - never blocks -
	 * if the span conflicts with a currently held exclusive span; shared-vs-shared always succeeds.
	 *
	 * @param start  starting position of the span, in {@code [0, fileSize)}
	 * @param length length of the span in bytes
	 * @return a token to release, or {@code null} if the span is currently exclusively held
	 */
	@Nullable
	Token tryAcquireShared(long start, long length) {
		this.lock.lock();
		try {
			final Span span = toSpan(start, length);
			// give up immediately if the span is exclusively held, or if an exclusive acquisition is already
			// pending on a conflicting span (writer preference - prevents a steady read stream from starving it)
			if (conflicts(span, Token::exclusive) || conflictsWithPending(span)) {
				return null;
			}
			final Token token = new Token(span, false);
			this.held.add(token);
			return token;
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Acquires an exclusive lock over the given span, waiting (bounded in practice by one session copy) while
	 * the span conflicts with any currently held span, shared or exclusive.
	 *
	 * @param start  starting position of the span, in {@code [0, fileSize)}
	 * @param length length of the span in bytes
	 * @return a token to release
	 */
	@Nonnull
	Token acquireExclusive(long start, long length) {
		this.lock.lock();
		try {
			final Span span = toSpan(start, length);
			// register as a pending exclusive before waiting so that new shared acquisitions on conflicting spans
			// give up immediately (writer preference); the finally guarantees the marker is cleared on every exit,
			// including the interrupted path, so an aborted writer never leaves a phantom span blocking readers
			this.pendingExclusive.add(span);
			try {
				while (conflicts(span, token -> true)) {
					try {
						this.spanReleased.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new GenericEvitaInternalError(
							"Interrupted while waiting for an exclusive span lock on the traffic recording disk buffer."
						);
					}
				}
			} finally {
				this.pendingExclusive.remove(span);
			}
			final Token token = new Token(span, true);
			this.held.add(token);
			return token;
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Releases a previously acquired token and wakes up any writer waiting on {@link #acquireExclusive(long, long)}.
	 *
	 * @param token the token returned by a previous {@link #tryAcquireShared(long, long)} or
	 *              {@link #acquireExclusive(long, long)} call
	 */
	void release(@Nonnull Token token) {
		this.lock.lock();
		try {
			this.held.remove(token);
			this.spanReleased.signalAll();
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Splits a {@code [start, start + length)} span into one or two physical segments, wrapping at
	 * {@link #fileSize} if necessary.
	 *
	 * @param start starting position of the span in the file
	 * @param length length of the span in bytes
	 * @return the span split at the physical end of the file
	 */
	@Nonnull
	private Span toSpan(long start, long length) {
		final long endExclusive = start + length;
		if (endExclusive <= this.fileSize) {
			return new Span(new Segment(start, endExclusive), null);
		} else {
			return new Span(
				new Segment(start, this.fileSize),
				new Segment(0, endExclusive - this.fileSize)
			);
		}
	}

	/**
	 * Checks whether the candidate span overlaps any currently held span accepted by the given filter.
	 *
	 * @param candidate the span requesting acquisition
	 * @param filter selects held tokens that conflict with the acquisition
	 * @return true if a selected held token overlaps the candidate
	 */
	private boolean conflicts(@Nonnull Span candidate, @Nonnull Predicate<Token> filter) {
		for (final Token existing : this.held) {
			if (filter.test(existing) && spansOverlap(candidate, existing.span())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Checks whether the candidate span overlaps any span whose exclusive acquisition is currently pending in
	 * {@link #acquireExclusive(long, long)} - the basis of the writer-preference give-up in
	 * {@link #tryAcquireShared(long, long)}.
	 *
	 * @param candidate the span requesting shared acquisition
	 * @return true if a pending writer overlaps the candidate
	 */
	private boolean conflictsWithPending(@Nonnull Span candidate) {
		for (final Span pending : this.pendingExclusive) {
			if (spansOverlap(candidate, pending)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Checks every physical segment pair for overlap.
	 *
	 * @param a the first span to compare
	 * @param b the second span to compare
	 * @return true if any physical segments overlap
	 */
	private static boolean spansOverlap(@Nonnull Span a, @Nonnull Span b) {
		return segmentsOverlap(a.first(), b.first())
			|| (b.second() != null && segmentsOverlap(a.first(), b.second()))
			|| (a.second() != null && segmentsOverlap(a.second(), b.first()))
			|| (a.second() != null && b.second() != null && segmentsOverlap(a.second(), b.second()));
	}

	/**
	 * Checks whether two physical segments overlap.
	 *
	 * @param a the first non-wrapping segment
	 * @param b the second non-wrapping segment
	 * @return true if the segments share any byte position
	 */
	private static boolean segmentsOverlap(@Nonnull Segment a, @Nonnull Segment b) {
		return DiskRingBuffer.rangesOverlap(a.start(), a.endExclusive() - 1, b.start(), b.endExclusive() - 1);
	}

	/**
	 * One physical, non-wrapping byte range {@code [start, endExclusive)} of a span.
	 *
	 * @param start first byte position in the segment
	 * @param endExclusive first byte position after the segment
	 */
	private record Segment(long start, long endExclusive) {
	}

	/**
	 * A span normalized into one or two physical segments (two iff it wraps the buffer end).
	 *
	 * @param first the segment from the starting position toward the physical file end
	 * @param second the segment from position zero after wrapping, or null for a non-wrapping span
	 */
	private record Span(@Nonnull Segment first, @Nullable Segment second) {
	}

	/**
	 * A held span lock. Must be released exactly once, in a {@code finally} block, by whoever acquired it.
	 *
	 * @param span the protected physical byte ranges
	 * @param exclusive whether the token excludes both readers and writers
	 */
	record Token(@Nonnull Span span, boolean exclusive) {
	}

}
