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

package io.evitadb.index.invertedIndex;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Learns when a DISTINCT VALUE of a shared value tree comes into existence and when it ceases to - the two moments a
 * structure keyed by {@link ValueIdAllocator value ids} has to react to, and the only two.
 *
 * # Why this exists at all
 *
 * A bucket of the shared value tree is created and deleted inside a leaf, immediately, with no tombstone tier and no
 * notification. A consumer that maintains its own structure keyed by value ids therefore had no way of learning that
 * an id it holds has stopped naming anything; this sink is that notification.
 *
 * # Why it is passed in rather than registered
 *
 * A sink is handed to {@link InvertedIndex#addRecord(Serializable, int, ValueLifecycleSink)} and its siblings **per
 * call**; the tree stores nothing. A registered listener would have to be re-bound every time the write path
 * re-shells its structures - every commit produces a fresh `InvertedIndex`, a fresh `AttributeIndex` and a fresh
 * owning entity index, and a shared value tree is additionally created lazily on the first write to its attribute -
 * and a single missed re-bind would stop maintenance with no symptom until a query silently under-reported. A
 * threaded parameter is checked by the compiler at every hop and has no state to go stale.
 *
 * # What a sink may assume
 *
 * - It is called ONLY when a bucket was genuinely born or died - never for a write that joined an existing value,
 *   which is what keeps churn on an existing value free of any work on the consumer's side.
 * - The value it receives is the NORMALIZED form the tree stores, i.e. the form
 *   {@link InvertedIndex#getValueById(int)} would hand back, so a consumer never normalizes again.
 * - It runs on the write path, inside whatever transaction that write belongs to, so a consumer that keeps
 *   transactional state records the change in its own diff layer exactly as the tree does.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface ValueLifecycleSink {

	/**
	 * Announces that `normalizedValue` is a value the tree has never held before and has just been minted the stable
	 * id `valueId`.
	 *
	 * @param valueId         the freshly minted stable id of the value
	 * @param normalizedValue the value in the canonical form the tree stores it in
	 */
	void valueCreated(int valueId, @Nonnull Serializable normalizedValue);

	/**
	 * Announces that `normalizedValue` has lost its last record and its bucket has been deleted, so `valueId` no
	 * longer names anything. The id is never handed to another value while the tree lives, so a consumer may treat
	 * it as permanently retired rather than merely currently unused.
	 *
	 * @param valueId         the stable id the departing value held
	 * @param normalizedValue the value in the canonical form the tree stored it in
	 */
	void valueRemoved(int valueId, @Nonnull Serializable normalizedValue);

}
