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
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

/**
 * This class allows generating sequence of unique transactional object versions in a thread safe manner. Versions
 * are unique only in single JVM instance and might be reused between JVM instance restarts.
 *
 * There is very little chance of potential issue when the sequence will overflow and start from the beginning. In such
 * a case we might have two different objects with the same version if one is very old (non-changed for a long time) and
 * another is very new. Transactional ids are used for calculation cache hash and the returned results might be wrong.
 *
 * If we detect overflow situation we stop providing new ids and throw exception - this will effectively stop
 * the database from accepting new updates.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Slf4j
public class TransactionalObjectVersion {
	public static final TransactionalObjectVersion SEQUENCE = new TransactionalObjectVersion();
	private final AtomicLong version = new AtomicLong(Long.MIN_VALUE);
	/**
	 * If TRUE, the domain of the version is positive numbers, otherwise negative numbers.
	 */
	private boolean positiveDomain = false;

	/**
	 * Generates new unique id from the sequence.
	 */
	public long nextId() {
		final long id = this.version.incrementAndGet();
		if (!this.positiveDomain && id >= 0) {
			this.positiveDomain = true;
		} if (id == Long.MAX_VALUE || (id < 0 && this.positiveDomain)) {
			log.error(
				"Transactional object version sequence overflowed, which can cause unpredictable results! " +
					"Database cannot accept any new modifications. Please restart database to start counting from the beginning."
			);
			throw new IdentifierOverflowException("Transactional object version sequence overflowed!");
		}
		return id;
	}

}
