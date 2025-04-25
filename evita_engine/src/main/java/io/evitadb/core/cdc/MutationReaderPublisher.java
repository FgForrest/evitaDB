/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.cdc;


import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.IOUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * This implementation of the publisher is used to read mutations from the database and publish them to subscribers.
 * It works in two distinct modes (hence two constructors):
 *
 * 1. Lagging mode - the publisher reads all mutations from the database and publishes them to a single subscriber
 * as long as the subscriber is able to consume them. The publisher will stop reading when the subscriber is saturated.
 * When the subscriber frees its buffer, it must call {@link #readAll()} to continue reading.
 * 2. Tracking mode - the publisher reads all mutations and follows the current catalog version in the system. Mutations
 * are published to all subscribers that can keep up. When the subscriber can't keep up - it's subscription is canceled
 * and the subscriber is discarded using `discardOnLagging` lambda (management system should provision separate
 * MutationReaderPublisher in lagging mode for such subscriber). The publisher in this tracking mode needs to be notified
 * about new catalog version available in the system by calling {@link #readAll()} method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
public class MutationReaderPublisher<T extends Flow.Subscriber<Mutation>> extends SubmissionPublisher<Mutation> implements AutoCloseable {
	/**
	 * Lambda provides the current catalog version used in the system (visible to new sessions).
	 */
	private final LongSupplier versionSupplier;
	/**
	 * Lambda provides the stream of mutations from the database.
	 */
	private final LongFunction<Stream<Mutation>> mutationSupplier;
	/**
	 * Lambda that is called when the subscribers reach the current catalog version retrieved from {@link #versionSupplier}.
	 */
	@Nullable private final Consumer<T> discardOnReachingCurrentVersion;
	/**
	 * Lambda that is called when the subscriber is lagging behind and can't keep up with the publisher.
	 */
	private final BiPredicate<Subscriber<? super Mutation>, Mutation> onDrop;
	/**
	 * Flag indicating whether the publisher should continue reading mutations even if the subscriber is saturated.
	 * This flag is true for `tracking mode` of this publisher and false for `lagging mode` of this publisher.
	 */
	private final boolean continueOnSubscriberSaturation;
	/**
	 * Map of all subscribers that are registered to this publisher with associated subscriptions.
	 */
	private final Map<T, Subscription> subscriptions;
	/**
	 * Lock to synchronize reading of the publisher.
	 */
	private final ReentrantLock lock = new ReentrantLock();
	/**
	 * Last successfully consumed version by the subscribers.
	 */
	private long sinceCatalogVersion;
	/**
	 * Last successfully consumed index by the subscribers.
	 */
	private int sinceIndex;
	/**
	 * Temporary variable to store the last mutation read from the mutation stream.
	 */
	@Nullable private Mutation lastReadMutation;
	/**
	 * Temporary variable to store the current catalog version of the last mutation read from the mutation stream.
	 */
	private long currentCatalogVersion;
	/**
	 * Temporary variable to store the current index of the last mutation read from the mutation stream.
	 */
	private int currentIndex;
	/**
	 * Flag indicating whether the subscriber is saturated and can't keep up with the publisher.
	 */
	private boolean subscriberSaturated;
	/**
	 * Remembered mutation stream for reading mutations from the database.
	 */
	@Nullable private Stream<Mutation> mutationStream;

	public MutationReaderPublisher(
		long sinceCatalogVersion,
		int sinceIndex,
		@Nonnull LongSupplier versionSupplier,
		@Nonnull LongFunction<Stream<Mutation>> mutationSupplier,
		@Nullable Consumer<T> discardOnLagging,
		@Nullable Consumer<T> discardOnReachingCurrentVersion
	) {
		this.sinceCatalogVersion = sinceCatalogVersion;
		this.sinceIndex = sinceIndex;
		this.versionSupplier = versionSupplier;
		this.discardOnReachingCurrentVersion = discardOnReachingCurrentVersion;
		this.mutationSupplier = mutationSupplier;
		this.subscriptions = CollectionUtils.createConcurrentHashMap(32);
		// if there is logic that discards lagging subscribers, we should continue reading changes even if any of subscribers doesn't keep up
		this.continueOnSubscriberSaturation = discardOnLagging != null;
		// on drop, register to lagging mutation publishers
		this.onDrop = (subscriber, ccc) -> {
			//noinspection unchecked
			final T theSubscriber = (T) subscriber;
			this.subscriberSaturated = true;
			if (discardOnLagging != null) {
				final Subscription subscription = this.subscriptions.remove(theSubscriber);
				// cancel the subscription to prevent further events from being sent unless reattached to lagging publisher
				Assert.isPremiseValid(subscription != null, "Subscriber must be registered in the map!");
				subscription.cancel();
				discardOnLagging.accept(theSubscriber);
			}
			// never retry delivery
			return false;
		};
	}

	/**
	 * Reads all available mutations from the catalog starting from a specific catalog version
	 * and index, and processes them for registered subscribers. This method is executed
	 * in a thread-safe manner using a lock to ensure only one thread can invoke it at a time.
	 */
	public void readAll() {
		// read should always be called from the same thread
		this.lock.lock();
		try {
			log.debug(
				"Reading all mutations from the catalog version {} and index {} and sending them to {} subscriber(s).",
				this.sinceCatalogVersion,
				this.sinceIndex,
				this.subscriptions.size()
			);
			// when the read is called, we start observing the saturation from the beginning
			this.subscriberSaturated = false;
			Assert.isPremiseValid(this.mutationStream == null, "Mutation stream must be null!");
			this.mutationStream = this.mutationSupplier.apply(this.sinceCatalogVersion);
			this.currentIndex = 0;

			final long readItems = Stream.concat(
				ofNullable(this.lastReadMutation).stream(),
				this.mutationStream
			)
				.dropWhile(
					mutation -> {
						// first mutation in the stream must always be transaction mutation
						if (mutation instanceof TransactionMutation txMutation) {
							this.currentCatalogVersion = txMutation.getCatalogVersion();
						}
						Assert.isPremiseValid(
							this.currentCatalogVersion > 0,
							"Catalog version must be greater than 0!"
						);
						this.lastReadMutation = mutation;
						// drop all mutations that are older than the requested catalog version and index of the mutation in the transaction block
						return this.sinceCatalogVersion < this.currentCatalogVersion ||
							(this.sinceCatalogVersion == this.currentCatalogVersion && this.currentIndex++ < this.sinceIndex);
					}
				)
				.takeWhile(
					mutation -> {
						if (mutation instanceof TransactionMutation txMutation && txMutation.getCatalogVersion() == this.versionSupplier.getAsLong()) {
							// reader must never get ahead of the currently used catalog version
							if (this.discardOnReachingCurrentVersion != null) {
								for (Entry<T, Subscription> entry : this.subscriptions.entrySet()) {
									entry.getValue().cancel();
									this.discardOnReachingCurrentVersion.accept(entry.getKey());
								}
								this.subscriptions.clear();
							}
							return false;
						} else {
							// publish the mutation to all subscribers
							offer(mutation, 0, TimeUnit.MILLISECONDS, this.onDrop);
							// if the subscribers keep up, or when we should continue reading even if they don't
							// return true, otherwise false to stop reading more records
							if (!this.subscriberSaturated || this.continueOnSubscriberSaturation) {
								// there is no pending mutation that needs to be published in the next read round
								this.lastReadMutation = null;
								this.sinceCatalogVersion = this.currentCatalogVersion;
								this.sinceIndex = this.currentIndex;
								return true;
							} else {
								// and remember the last catalog version and index that was succesfully consumed
								return false;
							}
						}
					}
				).count();

			log.debug(
				"Read {} items from the catalog and reached version {} and index {}.",
				readItems, this.sinceCatalogVersion, this.sinceIndex
			);

			// if there are no subscribers left, we can close the publisher
			if (!hasSubscribers()) {
				log.debug("No subscribers left, closing publisher.");
				this.close();
			}
		} catch (Exception ex) {
			log.error(
				"Error while reading mutations from the database: {}",
				ex.getMessage(), ex
			);
		} finally {
			// if we don't follow the current catalog version, we close publisher after finishing the read
			// the lagging publishers have only one subscriber and we don't want to keep resources open for them
			// the publisher following current catalog version has many subscribers and never stops reading, so it's
			// possible to leave it open
			if (this.discardOnReachingCurrentVersion != null) {
				log.debug("Closing mutation stream - reading finished.");
				closeMutationStream();
			}
			this.lock.unlock();
		}
	}

	@Override
	public void subscribe(Subscriber<? super Mutation> subscriber) {
		Assert.isPremiseValid(
			this.continueOnSubscriberSaturation || !this.hasSubscribers(),
			"In lagging mode the publisher can have only one subscriber!"
		);
		super.subscribe(subscriber);
	}

	@Override
	public void close() {
		this.lock.lock();
		try {
			closeMutationStream();
			super.close();
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Closes the current mutation stream if it is an instance of {@link Closeable}.
	 * This method ensures that any resources associated with the mutation stream
	 * are released quietly. After closing, the mutation stream reference is set to null.
	 */
	private void closeMutationStream() {
		if (this.mutationStream instanceof Closeable closeable) {
			IOUtils.closeQuietly(closeable::close);
		}
		this.mutationStream = null;
	}

}
