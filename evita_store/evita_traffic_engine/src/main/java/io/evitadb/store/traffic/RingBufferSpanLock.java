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
 * (one writer - {@code freeMemory()} is {@code synchronized}, so writer-vs-writer cannot happen - plus the export
 * and a few UI readers), so a single {@code synchronized} monitor guarding a small list of held spans is the right
 * size for this problem; no interval tree, no striping.
 *
 * <p>Lock-ordering / deadlock argument: the writer holds the {@code freeMemory} monitor and may wait on this span
 * lock; readers/the exporter hold span tokens and never acquire the {@code freeMemory} monitor while holding one.
 * No cycle exists.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class RingBufferSpanLock {

	/**
	 * Size of the ring buffer file this lock guards - spans wrapping past this size are split into two segments.
	 */
	private final long fileSize;
	/**
	 * Currently held spans. Guarded by (and only ever accessed while holding) this instance's own monitor.
	 */
	private final List<Token> held = new ArrayList<>();
	/**
	 * Spans of exclusive acquisitions that are currently waiting in {@link #acquireExclusive(long, long)}. A new
	 * shared acquisition that conflicts with any of these gives up immediately (writer preference), so a steady
	 * stream of overlapping readers cannot starve a pending writer. Guarded by this instance's own monitor; holds
	 * at most one entry in practice, since {@code freeMemory()} is {@code synchronized} (single writer).
	 */
	private final List<Span> pendingExclusive = new ArrayList<>();

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
	synchronized Token tryAcquireShared(long start, long length) {
		final Span span = toSpan(start, length);
		// give up immediately if the span is exclusively held, or if an exclusive acquisition is already
		// pending on a conflicting span (writer preference - prevents a steady read stream from starving it)
		if (conflicts(span, Token::exclusive) || conflictsWithPending(span)) {
			return null;
		}
		final Token token = new Token(span, false);
		this.held.add(token);
		return token;
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
	synchronized Token acquireExclusive(long start, long length) {
		final Span span = toSpan(start, length);
		// register as a pending exclusive before waiting so that new shared acquisitions on conflicting spans
		// give up immediately (writer preference); the finally guarantees the marker is cleared on every exit,
		// including the interrupted path, so an aborted writer never leaves a phantom span blocking readers
		this.pendingExclusive.add(span);
		try {
			while (conflicts(span, token -> true)) {
				try {
					wait();
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
	}

	/**
	 * Releases a previously acquired token and wakes up any writer waiting on {@link #acquireExclusive(long, long)}.
	 *
	 * @param token the token returned by a previous {@link #tryAcquireShared(long, long)} or
	 *              {@link #acquireExclusive(long, long)} call
	 */
	synchronized void release(@Nonnull Token token) {
		this.held.remove(token);
		notifyAll();
	}

	/**
	 * Splits a {@code [start, start + length)} span into one or two physical segments, wrapping at
	 * {@link #fileSize} if necessary.
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
	 */
	private boolean conflicts(@Nonnull Span candidate, @Nonnull Predicate<Token> filter) {
		for (Token existing : this.held) {
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
	 */
	private boolean conflictsWithPending(@Nonnull Span candidate) {
		for (Span pending : this.pendingExclusive) {
			if (spansOverlap(candidate, pending)) {
				return true;
			}
		}
		return false;
	}

	private static boolean spansOverlap(@Nonnull Span a, @Nonnull Span b) {
		return segmentsOverlap(a.first(), b.first())
			|| (b.second() != null && segmentsOverlap(a.first(), b.second()))
			|| (a.second() != null && segmentsOverlap(a.second(), b.first()))
			|| (a.second() != null && b.second() != null && segmentsOverlap(a.second(), b.second()));
	}

	private static boolean segmentsOverlap(@Nonnull Segment a, @Nonnull Segment b) {
		return DiskRingBuffer.rangesOverlap(a.start(), a.endExclusive() - 1, b.start(), b.endExclusive() - 1);
	}

	/**
	 * One physical, non-wrapping byte range {@code [start, endExclusive)} of a span.
	 */
	private record Segment(long start, long endExclusive) {
	}

	/**
	 * A span normalized into one or two physical segments (two iff it wraps the buffer end).
	 */
	private record Span(@Nonnull Segment first, @Nullable Segment second) {
	}

	/**
	 * A held span lock. Must be released exactly once, in a {@code finally} block, by whoever acquired it.
	 */
	record Token(@Nonnull Span span, boolean exclusive) {
	}

}
