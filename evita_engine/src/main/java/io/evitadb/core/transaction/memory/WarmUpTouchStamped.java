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

package io.evitadb.core.transaction.memory;

/**
 * SPI implemented by a participant of the WARM_UP write path that carries its own first-touch mark for
 * {@link WarmUpSavepoint}, instead of being looked up in a per-savepoint identity map.
 *
 * The mark is one `long` slot holding the stamp of the savepoint that most recently captured this participant's
 * pre-image (see {@link WarmUpSavepoint#open()} for how stamps are drawn). "Has this instance already been captured
 * inside the savepoint currently open" is then a field compare against that savepoint's stamp, which is what the
 * identity map used to answer with a hash and a probe. The bulk-ingest write path touches on the order of sixty
 * participants per entity, and the map's share of that was measured at 461 ms per 100k entities — the whole reason
 * this interface exists.
 *
 * **Requirements on the field backing it.** They are what make the compare a valid substitute for map membership, and
 * a violation of any of them silently SKIPS a capture, which is rollback corruption rather than a slow path:
 *
 * - **Plain `long`, not volatile and not `int`.** Access is confined to the single warm-up writer thread (see the
 *   thread-confinement section of {@link WarmUpSavepoint}), so no memory barrier is needed; `long` rather than `int`
 *   because a wrapped sequence could hand a later savepoint a value a stale mark already holds.
 * - **Default 0.** The sequence starts at 1, so a participant that has never been captured cannot match any savepoint.
 * - **Never cleared.** A closing savepoint leaves its stamp behind on every participant it captured; the next
 *   savepoint carries a fresh value, which no stale mark can equal.
 * - **Never serialized, never copied.** The mark describes one in-memory instance's relationship to one open
 *   savepoint, so it must not travel through Kryo, through a memento, or through
 *   {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory} — a copy carrying a live stamp would
 *   claim to have been captured when nothing captured it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see WarmUpSavepoint
 */
public interface WarmUpTouchStamped {

	/**
	 * Returns the stamp of the savepoint that most recently captured this participant's pre-image, or `0` when none
	 * ever has.
	 *
	 * @return the stamp of the capturing savepoint, `0` when never captured
	 */
	long getWarmUpTouchStamp();

	/**
	 * Records that the savepoint identified by `stamp` has captured this participant's pre-image, so its later touches
	 * inside the same savepoint answer that no further capture is needed. Called only by {@link WarmUpSavepoint}, and
	 * only from the warm-up delegate branch.
	 *
	 * @param stamp the capturing savepoint's stamp
	 */
	void setWarmUpTouchStamp(long stamp);

}
