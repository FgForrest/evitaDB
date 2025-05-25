/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.core.sequence;

import io.evitadb.core.EntityCollection;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.Optional.ofNullable;

/**
 * Sequence service is a "singleton" service that provide access to the "current sequences" for assigning new primary
 * keys. Sequences must shared among all instances of {@link EntityCollection} that may exist in parallel (for example
 * there are two simultaneous transaction that alter data of the same collection - either of them can commit or rollback
 * but the primary keys might be already returned to the client).
 *
 * Primary keys returned from sequences don't comply to isolation in ACID sense - and this is same even for standard
 * relational databases - see <a href="https://www.postgresql.org/docs/current/functions-sequence.html">PostgreSQL</a>
 * or <a href="https://stackoverflow.com/questions/2095917/sequences-not-affected-by-transactions">StackOverflow</a>.
 *
 * Sequences are always monotonic and there may be gaps in them when transaction that requested certain primary keys
 * were rolled back.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class SequenceService {
	private final Map<SequenceKey, AtomicInteger> intSequences = new ConcurrentHashMap<>();
	private final Map<SequenceKey, AtomicLong> longSequences = new ConcurrentHashMap<>();

	/**
	 * Method returns existing sequence or creates new. Sequence is initialized with passed `initialValue` or can fast
	 * forward pk sequence to passed value if already exist and is lower. Sequences persistence is out of the scope of
	 * this class/instance.
	 *
	 * It is expected that some process outside will persist the sequence numbers along with the data to the persistent
	 * storage. Upon deserialization this method should be called with previously stored peek number in `initialValue`
	 * argument.
	 *
	 * Multiple threads are expected to use same sequence instance - thus {@link AtomicInteger} is used.
	 */
	public AtomicInteger getOrCreateSequence(@Nonnull String catalog, @Nonnull SequenceType sequenceType, @Nullable Integer initialValue) {
		return getOrCreateSequenceInternal(catalog, sequenceType, null, initialValue);
	}

	/**
	 * Method returns existing sequence or creates new. Sequence is initialized with passed `initialValue` or can fast
	 * forward pk sequence to passed value if already exist and is lower. Sequences persistence is out of the scope of
	 * this class/instance.
	 *
	 * It is expected that some process outside will persist the sequence numbers along with the data to the persistent
	 * storage. Upon deserialization this method should be called with previously stored peek number in `initialValue`
	 * argument.
	 *
	 * Multiple threads are expected to use same sequence instance - thus {@link AtomicInteger} is used.
	 */
	public AtomicInteger getOrCreateSequence(@Nonnull String catalog, @Nonnull SequenceType sequenceType, @Nonnull String entityType, @Nullable Integer initialValue) {
		return getOrCreateSequenceInternal(catalog, sequenceType, entityType, initialValue);
	}

	/**
	 * Method returns existing sequence or creates new. Sequence is initialized with passed `initialValue` or can fast
	 * forward pk sequence to passed value if already exist and is lower. Sequences persistence is out of the scope of
	 * this class/instance.
	 *
	 * It is expected that some process outside will persist the sequence numbers along with the data to the persistent
	 * storage. Upon deserialization this method should be called with previously stored peek number in `initialValue`
	 * argument.
	 *
	 * Multiple threads are expected to use same sequence instance - thus {@link AtomicLong} is used.
	 */
	public AtomicLong getOrCreateSequence(@Nonnull String catalog, @Nonnull SequenceType sequenceType, @Nullable Long initialValue) {
		return getOrCreateSequenceInternal(catalog, sequenceType, null, initialValue);
	}

	/**
	 * Method returns existing sequence or creates new. Sequence is initialized with passed `initialValue` or can fast
	 * forward pk sequence to passed value if already exist and is lower. Sequences persistence is out of the scope of
	 * this class/instance.
	 *
	 * It is expected that some process outside will persist the sequence numbers along with the data to the persistent
	 * storage. Upon deserialization this method should be called with previously stored peek number in `initialValue`
	 * argument.
	 *
	 * Multiple threads are expected to use same sequence instance - thus {@link AtomicLong} is used.
	 */
	public AtomicLong getOrCreateSequence(@Nonnull String catalog, @Nonnull SequenceType sequenceType, @Nonnull String entityType, @Nullable Long initialValue) {
		return getOrCreateSequenceInternal(catalog, sequenceType, entityType, initialValue);
	}

	/*
		PRIVATE METHODS
	 */

	@Nonnull
	private AtomicInteger getOrCreateSequenceInternal(@Nonnull String catalog, @Nonnull SequenceType sequenceType, @Nullable String entityType, @Nullable Integer initialValue) {
		final int theInitialValue = ofNullable(initialValue).orElse(0);
		final AtomicInteger sequence = this.intSequences.computeIfAbsent(
			new SequenceKey(catalog, sequenceType, entityType),
			sequenceKey -> new AtomicInteger(theInitialValue)
		);

		// spinning update - we can only fast forward pk sequence - never go back
		boolean valueNormalized;
		do {
			final int currentValue = sequence.get();
			if (currentValue < theInitialValue) {
				valueNormalized = sequence.compareAndSet(currentValue, theInitialValue);
			} else {
				valueNormalized = true;
			}
		} while (!valueNormalized);

		return sequence;
	}

	@Nonnull
	private AtomicLong getOrCreateSequenceInternal(@Nonnull String catalog, @Nonnull SequenceType sequenceType, @Nullable String entityType, @Nullable Long initialValue) {
		final long theInitialValue = ofNullable(initialValue).orElse(0L);
		final AtomicLong sequence = this.longSequences.computeIfAbsent(
			new SequenceKey(catalog, sequenceType, entityType),
			sequenceKey -> new AtomicLong(theInitialValue)
		);

		// spinning update - we can only fast forward pk sequence - never go back
		boolean valueNormalized;
		do {
			final long currentValue = sequence.get();
			if (currentValue < theInitialValue) {
				valueNormalized = sequence.compareAndSet(currentValue, theInitialValue);
			} else {
				valueNormalized = true;
			}
		} while (!valueNormalized);

		return sequence;
	}

}
