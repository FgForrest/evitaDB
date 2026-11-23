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

package io.evitadb.core.transaction.engine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.requestResponse.progress.Progress;
import io.evitadb.core.exception.EngineMutationFailedException;
import io.evitadb.core.exception.EngineMutationProgressNotFoundException;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.exception.EvitaError;
import io.evitadb.utils.IOUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntConsumer;

/**
 * Tracks every engine mutation under its progress id (the id of its engine transaction) so that a client which
 * received the id - e.g. from an asynchronous call of the GraphQL or REST system API - can subscribe to the progress
 * of the operation and learn how it ended, even when it subscribes only after the operation has finished.
 *
 * **Two tiers of entries.** A running operation is kept as a {@link RunningEntry} holding its {@link Progress}, the
 * current {@link EngineMutationProgressStatus} and the publishers of its subscribers; it never expires and is never
 * evicted. When the operation finishes, the entry is replaced by a {@link FinishedEntry} holding only the compact
 * terminal status, which is retained for {@link ServerOptions#engineMutationProgressRetentionInSeconds()} counted from
 * the completion, and of which at most {@link ServerOptions#engineMutationProgressRetentionLimit()} are kept. Both
 * bounds are enforced by Caffeine: variable expiry gives running entries an unlimited lifetime, and a zero weight
 * keeps them out of the size-based eviction, which therefore only ever picks among finished entries (which one it
 * picks is unspecified). Nothing is persisted - an id is unknown after a restart.
 *
 * **Publishing.** Every subscription gets its own JDK {@link SubmissionPublisher} - one shared publisher could not
 * replay the current state to a late subscriber. The publisher handles demand, buffering, cancellation and the
 * ordering of the reactive-streams signals; what is left here is the snapshot on subscribe, the fan-out under the
 * entry lock and a reserved buffer slot: an intermediate percentage is offered only while at least two slots are
 * free, so a slow subscriber skips intermediate values (they are monotonic and superseded by the next one), while the
 * terminal item always finds a free slot and is never dropped. Success is published as a final item followed by
 * `onComplete`; a failure as `onError` with {@link EngineMutationFailedException}; an unknown or expired id as
 * `onError` with {@link EngineMutationProgressNotFoundException}.
 * When the registry is closed with the engine, open subscriptions end with `onComplete` without a final item
 * (`SubmissionPublisher` cannot end a subscription without a signal).
 *
 * The publishers deliver on an executor that must not reject - a rejecting one would make `SubmissionPublisher`
 * drop the subscriber without any signal.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@Slf4j
public class EngineMutationProgressRegistry implements Closeable {
	/**
	 * Requested buffer capacity of each subscriber's publisher. The publisher rounds it up to a power of two, so the
	 * effective capacity is always read back from the publisher.
	 */
	private static final int SUBSCRIBER_BUFFER_CAPACITY = 4;

	/**
	 * Entries keyed by progress id.
	 */
	private final Cache<UUID, Entry> cache;
	/**
	 * Executor the subscribers' publishers deliver signals on. Must not reject tasks.
	 */
	private final Executor deliveryExecutor;
	/**
	 * Periodic cleanup releasing expired entries on an idle server, absent when nothing is retained.
	 */
	@Nullable private final DelayedAsyncTask maintenanceTask;
	/**
	 * Set once the registry is closed; a closed registry tracks nothing and completes new subscriptions at once.
	 */
	private final AtomicBoolean closed = new AtomicBoolean();

	/**
	 * Creates the registry and schedules its maintenance on the engine scheduler when finished outcomes are retained.
	 *
	 * @param serverOptions    server options providing the retention and the retention limit of finished outcomes
	 * @param scheduler        engine scheduler used for the periodic cleanup of expired entries
	 * @param deliveryExecutor executor the subscribers' publishers deliver on; must not reject tasks
	 * @param ticker           time source used to measure the retention
	 */
	public EngineMutationProgressRegistry(
		@Nonnull ServerOptions serverOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull Executor deliveryExecutor,
		@Nonnull Ticker ticker
	) {
		final long retentionNanos = TimeUnit.SECONDS.toNanos(serverOptions.engineMutationProgressRetentionInSeconds());
		this.deliveryExecutor = deliveryExecutor;
		this.cache = Caffeine.newBuilder()
			.maximumWeight(serverOptions.engineMutationProgressRetentionLimit())
			.weigher((UUID id, Entry entry) -> entry instanceof FinishedEntry ? 1 : 0)
			.expireAfter(new EntryExpiry(retentionNanos))
			.executor(Runnable::run)
			.ticker(ticker)
			.build();
		if (serverOptions.engineMutationProgressRetentionInSeconds() > 0 &&
			serverOptions.engineMutationProgressRetentionLimit() > 0) {
			this.maintenanceTask = new DelayedAsyncTask(
				null, "Engine mutation progress cleanup", scheduler,
				() -> {
					this.cache.cleanUp();
					return 0L;
				},
				serverOptions.engineMutationProgressRetentionInSeconds(), TimeUnit.SECONDS
			);
			this.maintenanceTask.schedule();
		} else {
			this.maintenanceTask = null;
		}
	}

	/**
	 * Starts tracking a running operation. Must be called before the operation is submitted for execution, so that
	 * its completion can never precede the registration.
	 *
	 * @param progressId id of the operation
	 * @param progress   progress of the operation
	 */
	void register(@Nonnull UUID progressId, @Nonnull Progress<?> progress) {
		if (this.closed.get()) {
			return;
		}
		final RunningEntry entry = new RunningEntry(progressId, progress);
		this.cache.put(progressId, entry);
		progress.addProgressListener(entry);
		if (this.closed.get()) {
			// `close` may have swept the cache between the check above and the put
			remove(progressId);
		}
	}

	/**
	 * Transitions a tracked operation to its terminal state: replaces the running entry by the compact terminal
	 * record and publishes the outcome to all current subscribers. A no-op for an untracked id or a closed registry.
	 *
	 * @param progressId id of the operation
	 * @param result     result of a successful operation, null otherwise
	 * @param failure    failure of a failed operation, null for a successful one
	 */
	void complete(@Nonnull UUID progressId, @Nullable Object result, @Nullable Throwable failure) {
		if (this.closed.get()) {
			return;
		}
		final EngineMutationProgressStatus terminal = failure == null ?
			EngineMutationProgressStatus.succeeded(progressId, result) :
			toFailedStatus(progressId, failure);
		final Entry[] previous = new Entry[1];
		// re-weighs and re-expires the entry atomically under Caffeine's per-key lock
		this.cache.asMap().computeIfPresent(
			progressId,
			(id, entry) -> {
				previous[0] = entry;
				return new FinishedEntry(terminal);
			}
		);
		// fan-out happens outside of Caffeine's lock
		if (previous[0] instanceof RunningEntry runningEntry) {
			runningEntry.finish(terminal);
		}
	}

	/**
	 * Forgets a tracked operation without publishing anything - used when the operation could not even be submitted
	 * for execution, so its caller got an exception and no id, and nobody can be subscribed to it.
	 *
	 * @param progressId id of the operation
	 */
	void remove(@Nonnull UUID progressId) {
		final Entry removed = this.cache.asMap().remove(progressId);
		if (removed instanceof RunningEntry runningEntry) {
			runningEntry.terminate();
		}
	}

	/**
	 * Returns a publisher of the progress of the operation with the given id. Each subscriber first receives the
	 * current state of the operation (the terminal one if it has already finished), then every further change.
	 * See the class documentation for the terminal signals.
	 *
	 * @param progressId id of the operation, as returned by {@link Progress#progressId()}
	 * @return publisher of the progress of the operation
	 */
	@Nonnull
	public Flow.Publisher<EngineMutationProgressStatus> subscribe(@Nonnull UUID progressId) {
		return subscriber -> subscribe(progressId, subscriber);
	}

	@Override
	public void close() {
		if (this.closed.compareAndSet(false, true)) {
			if (this.maintenanceTask != null) {
				IOUtils.closeQuietly(this.maintenanceTask::close);
			}
			for (Entry entry : this.cache.asMap().values()) {
				if (entry instanceof RunningEntry runningEntry) {
					runningEntry.terminate();
				}
			}
			this.cache.invalidateAll();
			this.cache.cleanUp();
		}
	}

	/**
	 * Subscribes the subscriber to a new publisher and feeds the publisher with the current state of the operation.
	 *
	 * @param progressId id of the operation
	 * @param subscriber the subscriber
	 */
	private void subscribe(
		@Nonnull UUID progressId,
		@Nonnull Flow.Subscriber<? super EngineMutationProgressStatus> subscriber
	) {
		Objects.requireNonNull(subscriber, "Subscriber must not be null!");
		final SubmissionPublisher<EngineMutationProgressStatus> publisher = new SubmissionPublisher<>(
			this.deliveryExecutor, SUBSCRIBER_BUFFER_CAPACITY
		);
		try {
			// the subscriber must be subscribed first - items offered with no subscriber are lost
			publisher.subscribe(subscriber);
			if (this.closed.get()) {
				publisher.close();
				return;
			}
			final Entry entry = this.cache.getIfPresent(progressId);
			if (entry instanceof RunningEntry runningEntry) {
				runningEntry.attach(publisher);
			} else if (entry instanceof FinishedEntry finishedEntry) {
				publishTerminal(publisher, finishedEntry.status());
			} else {
				publisher.closeExceptionally(new EngineMutationProgressNotFoundException(progressId));
			}
		} catch (RejectedExecutionException ex) {
			// the delivery executor is shutting down together with the engine - nothing can be delivered anymore
			log.debug("Cannot deliver progress of engine mutation `{}`, the executor rejected it.", progressId, ex);
			publisher.close();
		}
	}

	/**
	 * Publishes the terminal state to the publisher and closes it. Never throws, so that one publisher whose
	 * executor rejects the delivery cannot deprive the others of their terminal signal.
	 *
	 * @param publisher publisher of one subscriber
	 * @param terminal  terminal status of the operation
	 */
	private static void publishTerminal(
		@Nonnull SubmissionPublisher<EngineMutationProgressStatus> publisher,
		@Nonnull EngineMutationProgressStatus terminal
	) {
		try {
			if (terminal.status() == EngineMutationProgressStatus.Status.FAILED) {
				publisher.closeExceptionally(
					new EngineMutationFailedException(
						terminal.progressId(), terminal.errorCode(),
						Objects.requireNonNull(terminal.publicMessage())
					)
				);
			} else {
				// a slot is always reserved for the terminal item, the drop handler is never expected to be called
				publisher.offer(terminal, (s, item) -> false);
				// `close` delivers the buffered items before `onComplete`
				publisher.close();
			}
		} catch (RuntimeException ex) {
			log.debug("Cannot deliver the outcome of engine mutation `{}`.", terminal.progressId(), ex);
		}
	}

	/**
	 * Completes the publisher without a terminal item. Never throws.
	 *
	 * @param publisher publisher of one subscriber
	 */
	private static void closeQuietly(@Nonnull SubmissionPublisher<EngineMutationProgressStatus> publisher) {
		try {
			publisher.close();
		} catch (RuntimeException ex) {
			log.debug("Cannot complete an engine mutation progress subscription.", ex);
		}
	}

	/**
	 * Compacts a failure into the terminal status: the `CompletionException` wrapper is removed, an
	 * {@link EvitaError} keeps its error code and public message, any other failure only its type.
	 *
	 * @param progressId id of the operation
	 * @param failure    failure of the operation
	 * @return terminal status of the failed operation
	 */
	@Nonnull
	private static EngineMutationProgressStatus toFailedStatus(@Nonnull UUID progressId, @Nonnull Throwable failure) {
		Throwable cause = failure;
		while ((cause instanceof CompletionException || cause instanceof ExecutionException) &&
			cause.getCause() != null) {
			cause = cause.getCause();
		}
		if (cause instanceof EvitaError evitaError) {
			return EngineMutationProgressStatus.failed(
				progressId, evitaError.getErrorCode(), evitaError.getPublicMessage()
			);
		} else if (cause instanceof CancellationException) {
			return EngineMutationProgressStatus.failed(progressId, null, "The operation was cancelled.");
		} else {
			return EngineMutationProgressStatus.failed(
				progressId, null, "The operation failed with an unexpected error (" +
					cause.getClass().getSimpleName() + ")."
			);
		}
	}

	/**
	 * Entry of the registry.
	 */
	private sealed interface Entry permits RunningEntry, FinishedEntry {
	}

	/**
	 * Compact record of a finished operation.
	 *
	 * @param status terminal status of the operation
	 */
	private record FinishedEntry(@Nonnull EngineMutationProgressStatus status) implements Entry {
	}

	/**
	 * Entry of a running operation. It is the single progress listener of the operation and fans its updates out to
	 * the publishers of all subscribers. All mutable state is guarded by {@link #lock}; the terminal state is kept
	 * here too, so a subscriber that found this entry just before it was replaced in the cache still gets the outcome.
	 */
	private static final class RunningEntry implements Entry, IntConsumer {
		/**
		 * Guards all mutable state of the entry and serializes all producers of the publishers.
		 */
		private final ReentrantLock lock = new ReentrantLock();
		/**
		 * Id of the operation.
		 */
		private final UUID progressId;
		/**
		 * Publishers of the current subscribers.
		 */
		private final List<SubmissionPublisher<EngineMutationProgressStatus>> publishers = new ArrayList<>(2);
		/**
		 * Progress of the operation, released when the operation finishes.
		 */
		@Nullable private Progress<?> progress;
		/**
		 * Last published state of the operation.
		 */
		private EngineMutationProgressStatus current;
		/**
		 * Set when the operation finished (terminal status) or the registry was closed (the current status).
		 */
		private boolean terminated;
		/**
		 * Terminal status of the operation, null until it finishes or when the registry was closed before.
		 */
		@Nullable private EngineMutationProgressStatus terminal;

		RunningEntry(@Nonnull UUID progressId, @Nonnull Progress<?> progress) {
			this.progressId = progressId;
			this.progress = progress;
			this.current = EngineMutationProgressStatus.running(progressId, progress.percentCompleted());
		}

		/**
		 * Receives a progress update. Updates that do not increase the percentage are ignored, and so is 100 % - an
		 * engine progress reports it to its listeners also right before it fails, the terminal state is published
		 * from the completion only.
		 *
		 * @param percentCompleted new percentage of completion
		 */
		@Override
		public void accept(int percentCompleted) {
			this.lock.lock();
			try {
				if (this.terminated || percentCompleted >= 100 || percentCompleted <= this.current.percentCompleted()) {
					return;
				}
				this.current = EngineMutationProgressStatus.running(this.progressId, percentCompleted);
				// iterates over a copy: with inline delivery a subscriber may re-enter `attach` from `onNext` and
				// grow the list
				for (SubmissionPublisher<EngineMutationProgressStatus> publisher : new ArrayList<>(this.publishers)) {
					if (publisher.isClosed() || !publisher.hasSubscribers()) {
						// the subscriber cancelled its subscription
						closeQuietly(publisher);
						this.publishers.remove(publisher);
					} else if (publisher.estimateMaximumLag() < publisher.getMaxBufferCapacity() - 1) {
						// one slot always stays free for the terminal item; producers are serialized by the lock and
						// the consumer only lowers the lag, so the check cannot be outrun
						offerIntermediate(publisher, this.current);
					}
				}
			} finally {
				this.lock.unlock();
			}
		}

		/**
		 * Attaches the publisher of a new subscriber and offers it the current state of the operation.
		 *
		 * @param publisher publisher of the new subscriber
		 */
		void attach(@Nonnull SubmissionPublisher<EngineMutationProgressStatus> publisher) {
			this.lock.lock();
			try {
				if (this.terminated) {
					if (this.terminal == null) {
						closeQuietly(publisher);
					} else {
						publishTerminal(publisher, this.terminal);
					}
				} else {
					this.publishers.add(publisher);
					offerIntermediate(publisher, this.current);
				}
			} finally {
				this.lock.unlock();
			}
		}

		/**
		 * Publishes the terminal state to all subscribers and releases the progress.
		 *
		 * @param terminalStatus terminal status of the operation
		 */
		void finish(@Nonnull EngineMutationProgressStatus terminalStatus) {
			this.lock.lock();
			try {
				if (this.terminated) {
					return;
				}
				this.terminated = true;
				this.terminal = terminalStatus;
				this.current = terminalStatus;
				releaseProgress();
				for (SubmissionPublisher<EngineMutationProgressStatus> publisher : this.publishers) {
					publishTerminal(publisher, terminalStatus);
				}
				this.publishers.clear();
			} finally {
				this.lock.unlock();
			}
		}

		/**
		 * Completes all subscriptions without a terminal item and releases the progress - used when the registry
		 * is closed together with the engine, mirroring how change data capture streams end on shutdown.
		 */
		void terminate() {
			this.lock.lock();
			try {
				if (this.terminated) {
					return;
				}
				this.terminated = true;
				releaseProgress();
				for (SubmissionPublisher<EngineMutationProgressStatus> publisher : this.publishers) {
					closeQuietly(publisher);
				}
				this.publishers.clear();
			} finally {
				this.lock.unlock();
			}
		}

		/**
		 * Removes this entry from the listeners of the progress and forgets the progress. Must be called under the
		 * lock.
		 */
		private void releaseProgress() {
			if (this.progress != null) {
				this.progress.removeProgressListener(this);
				this.progress = null;
			}
		}

		/**
		 * Offers an intermediate item; an item that does not fit the buffer is dropped rather than retried.
		 *
		 * @param publisher publisher of one subscriber
		 * @param status    the item
		 */
		private static void offerIntermediate(
			@Nonnull SubmissionPublisher<EngineMutationProgressStatus> publisher,
			@Nonnull EngineMutationProgressStatus status
		) {
			try {
				publisher.offer(status, (s, item) -> false);
			} catch (RejectedExecutionException ex) {
				log.debug("Cannot deliver progress of engine mutation `{}`, the executor rejected it.",
					status.progressId(), ex);
			}
		}
	}

	/**
	 * Expiry policy: running entries never expire, finished entries expire after the retention counted from their
	 * completion (the moment the running entry was replaced), and a lookup never extends the lifetime.
	 *
	 * @param retentionNanos retention of finished entries in nanoseconds
	 */
	private record EntryExpiry(long retentionNanos) implements Expiry<UUID, Entry> {

		@Override
		public long expireAfterCreate(@Nonnull UUID key, @Nonnull Entry value, long currentTime) {
			return lifetimeOf(value);
		}

		@Override
		public long expireAfterUpdate(
			@Nonnull UUID key, @Nonnull Entry value, long currentTime, long currentDuration
		) {
			return lifetimeOf(value);
		}

		@Override
		public long expireAfterRead(
			@Nonnull UUID key, @Nonnull Entry value, long currentTime, long currentDuration
		) {
			return currentDuration;
		}

		/**
		 * Returns the lifetime of the entry in nanoseconds.
		 *
		 * @param value the entry
		 * @return lifetime of the entry; Caffeine clamps `Long.MAX_VALUE` to its maximum
		 */
		private long lifetimeOf(@Nonnull Entry value) {
			return value instanceof FinishedEntry ? this.retentionNanos : Long.MAX_VALUE;
		}
	}

}
