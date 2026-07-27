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

package io.evitadb.core.transaction.memory;

import io.evitadb.api.exception.IdentifierOverflowException;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

/**
 * This class allows generating sequence of unique transactional object versions in a thread safe manner. Versions
 * are unique only in single JVM instance and might be reused between JVM instance restarts.
 *
 * The sequence hands out `1L` first, counts up through {@link Long#MAX_VALUE}, wraps in two's complement to
 * {@link Long#MIN_VALUE} and continues up to `-1L`, so the whole `2^64 - 1` non-zero space is usable. **`0L` is
 * reserved and is never emitted**: it denotes "no transactional layer", which lets an identifier-keyed registry
 * represent absence without any risk of confusing it with a live creator.
 *
 * Returning to `0L` therefore means the entire identifier space has been consumed. At that point the sequence is
 * poisoned and stops providing new ids - this will effectively stop the database from accepting new updates, because
 * handing out a recycled id would silently corrupt the query cache (transactional ids are used to compute cache hashes,
 * so a duplicate id makes a stale cached result look current).
 *
 * **The exhaustion boundary is not guarded against a concurrent race.** Threads that increment the counter past `0L`
 * before the detecting thread poisons it would receive already-used ids. Closing that window would require either a
 * compare-and-set loop or a `volatile` flag read on a path executed for every transactional object in the JVM, and the
 * boundary is unreachable in practice - exhausting `2^64 - 1` ids takes roughly 58 000 years at ten million ids per
 * second. The cost is therefore not paid for a corner that cannot be reached.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
public class TransactionalObjectVersion {
	/**
	 * Reserved identifier denoting "no transactional layer". It is never handed out by {@link #nextId()}, which is what
	 * allows an identifier-keyed layer registry to use it as an unambiguous absence marker.
	 */
	public static final long NO_LAYER_ID = 0L;
	/**
	 * JVM-wide singleton sequence shared by all transactional objects when assigning their version ids.
	 */
	public static final TransactionalObjectVersion SEQUENCE = new TransactionalObjectVersion();
	/**
	 * Backing counter; starts at {@link #NO_LAYER_ID} so that the first id handed out is `1L`, and is incremented on
	 * every {@link #nextId()} call. It wraps naturally from {@link Long#MAX_VALUE} to {@link Long#MIN_VALUE} and is
	 * exhausted only when it returns to {@link #NO_LAYER_ID}.
	 */
	private final AtomicLong version;

	/**
	 * Creates a sequence seeded to an arbitrary position. Visible for tests, which have to observe the wrap-around and
	 * exhaustion behaviour without performing `2^64` increments to reach it.
	 *
	 * @param seed value the counter starts at; the first id handed out is `seed + 1`
	 */
	TransactionalObjectVersion(long seed) {
		this.version = new AtomicLong(seed);
	}

	/**
	 * Creates a sequence positioned so that the first id handed out is `1L`.
	 */
	TransactionalObjectVersion() {
		this(NO_LAYER_ID);
	}

	/**
	 * Returns the next unique id from the sequence. The returned value is never {@link #NO_LAYER_ID}.
	 *
	 * @throws IdentifierOverflowException once the entire identifier space has been handed out, halting further updates
	 *                                     to avoid handing out a duplicate version (see class JavaDoc)
	 */
	public long nextId() {
		final long id = this.version.incrementAndGet();
		if (id == NO_LAYER_ID) {
			// rewind onto the last identifier of the space so that every subsequent call increments back onto
			// NO_LAYER_ID and fails identically, instead of silently resuming at 1 with ids that are already in use
			this.version.set(-1L);
			log.error(
				"Transactional object version sequence exhausted, which can cause unpredictable results! " +
					"Database cannot accept any new modifications. Please restart database to start counting from the beginning."
			);
			throw new IdentifierOverflowException("Transactional object version sequence exhausted!");
		}
		return id;
	}

}
