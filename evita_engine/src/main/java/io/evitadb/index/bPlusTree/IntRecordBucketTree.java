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

package io.evitadb.index.bPlusTree;

import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.LongConsumer;
import java.util.function.Predicate;

/**
 * The write surface of an `int` record-set bucket tree: each key maps to a set of `int` record ids (a lean single-record
 * bucket that promotes to a {@link io.evitadb.index.bitmap.TransactionalBitmap} on the second distinct id). This is the
 * default bucket tree backing the inverted and owner-unique indexes. The mutually-exclusive single-`long` write surface
 * lives on {@link LongPayloadBucketTree}; a reference of this type cannot reach it.
 *
 * @param <K> the key (bucket value) type
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface IntRecordBucketTree<K extends Comparable<K>> extends BucketBPlusTree<K> {

	/**
	 * Adds a single record id into the bucket with the specified value, creating the bucket when absent and promoting it
	 * to a multi-record bitmap on the second distinct id.
	 *
	 * @param value the value identifying the bucket
	 * @param pk    the record id to add (may be any int, including negative ids)
	 */
	void addRecord(@Nonnull K value, int pk);

	/**
	 * Adds multiple record ids into the bucket with the specified value, creating the bucket when absent and promoting it
	 * to a multi-record bitmap as needed.
	 *
	 * @param value the value identifying the bucket
	 * @param pks   the record ids to add; must be non-empty (may contain negative ids)
	 */
	void addRecord(@Nonnull K value, @Nonnull int... pks);

	/**
	 * Value-id-reporting variant of {@link #addRecord(Comparable, int)} for a caller that has to learn WHICH distinct
	 * value the insert brought into existence. The id is the one the insert minted inside its own descent, so
	 * reporting a birth costs no descent beyond the one the insert performs anyway — the mirror image of
	 * {@link #removeRecordReportingValueDeath}, and an insert that joins an existing value costs nothing extra at all.
	 *
	 * @param value the value identifying the bucket
	 * @param pk    the record id to add (may be any int, including negative ids)
	 * @return the new value's stable id — or `0`, the "unassigned" sentinel, when the tree carries no value ids — and
	 * {@link TransactionalBucketBPlusTree#NO_CREATED_BUCKET} when no bucket was created, i.e. no value was born
	 */
	int addRecordReportingValueBirth(@Nonnull K value, int pk);

	/**
	 * Value-id-reporting variant of {@link #addRecord(Comparable, int...)} — see
	 * {@link #addRecordReportingValueBirth(Comparable, int)}. However many record ids are added they all land in ONE
	 * bucket, so at most one value can be born.
	 *
	 * @param value the value identifying the bucket
	 * @param pks   the record ids to add; must be non-empty (may contain negative ids)
	 * @return the new value's stable id — or `0`, the "unassigned" sentinel, when the tree carries no value ids — and
	 * {@link TransactionalBucketBPlusTree#NO_CREATED_BUCKET} when no bucket was created, i.e. no value was born
	 */
	int addRecordReportingValueBirth(@Nonnull K value, @Nonnull int... pks);

	/**
	 * Removes one or multiple record ids from the bucket with the specified value, deleting the bucket when it drops to
	 * zero records.
	 *
	 * @param value the value identifying the bucket
	 * @param pks   the record ids to remove; must be non-empty (may contain negative ids)
	 */
	void removeRecord(@Nonnull K value, @Nonnull int... pks);

	/**
	 * Value-id-reporting variant of {@link #removeRecord(Comparable, int...)} for a caller that has to learn WHICH
	 * distinct value the removal took out of existence. The id is read off the slot the removal's own descent already
	 * resolved — and it has to be read there, because a deleted bucket takes its id with it — so reporting a death
	 * costs no descent beyond the one the removal performs anyway, and a removal over a surviving value costs nothing
	 * extra at all.
	 *
	 * @param value the value identifying the bucket
	 * @param pks   the record ids to remove; must be non-empty (may contain negative ids)
	 * @return the dead value's stable id — or `0`, the "unassigned" sentinel, when the tree carries no value ids —
	 * and {@link TransactionalBucketBPlusTree#NO_DELETED_BUCKET} when no bucket was deleted, i.e. no value died
	 */
	int removeRecordReportingValueDeath(@Nonnull K value, @Nonnull int... pks);

	/**
	 * Returns the record set associated with the given value (a lean single-record view, a transactional bitmap, or the
	 * empty bitmap when absent).
	 *
	 * @param value the value to look up (may be null ⇒ empty bitmap)
	 * @return the record set for the value, never null
	 */
	@Nonnull
	Bitmap getRecordsEqualTo(@Nullable K value);

	/**
	 * Resolves one candidate value id, tests the value it names against `valuePredicate`, and returns that value's
	 * record set when the test passes - all from a SINGLE resolution of the bucket's location.
	 *
	 * This is the fused sibling of `valueOf(id)` + `leafVersionOf(id)` + {@link #getRecordsEqualTo(Comparable)}, which
	 * a candidate-verifying caller would otherwise have to chain. That chain resolves the same location three times:
	 * the first two probes are byte-for-byte identical, and the third throws the located slot away to re-find it by a
	 * root-to-leaf descent plus a leaf-local binary search over front-coded keys. Everything the caller needs is
	 * slot-parallel in the leaf the first probe already landed on, so one probe answers all three questions.
	 *
	 * The leaf version reaches `leafVersionSink` for MATCHES ONLY, exactly as a caller chaining the three calls would
	 * arrange: a candidate that fails the predicate contributes no record set, so its leaf is not a page the answer
	 * depends on and must not enter the staleness token set.
	 *
	 * ## The caller owes the transaction check
	 *
	 * The same obligation `valueOf(int)` documents applies unchanged, and for the same reason: the location is
	 * resolved through the value id directory, which describes the last PUBLISHED version of the tree. Under an open
	 * transaction the directory and the leaves disagree, so this method refuses rather than reporting a silent
	 * under-count.
	 *
	 * @param valueId         the candidate id to resolve
	 * @param valuePredicate  the exact test applied to the value the id names, or `null` when the caller already knows
	 *                        every id it passes matches - in which case the key is never read off the slot at all,
	 *                        which on a front-coded column saves a walk back to a restart point and a `String`
	 *                        allocation per candidate
	 * @param containsPatternUtf8 when non-`null`, the UTF-8 bytes a matching key must CONTAIN, applied against the
	 *                            column's stored bytes instead of `valuePredicate` wherever the column can answer that
	 *                            way ({@link ValueColumn#supportsUtf8Matching}). It is the same test the predicate
	 *                            would apply, minus the {@link String} the predicate would need built for it; the
	 *                            caller owes the guarantee that the two agree, including that the pattern survives
	 *                            UTF-8 encoding unchanged
	 * @param leafVersionSink receives the version token of the matched bucket's leaf, and is not called otherwise
	 * @return the matched value's record set, or `null` when the id names nothing live or the predicate rejected it
	 */
	@Nullable
	Bitmap recordsOfMatchingValueId(
		int valueId,
		@Nullable Predicate<K> valuePredicate,
		@Nullable byte[] containsPatternUtf8,
		@Nonnull LongConsumer leafVersionSink
	);

	/**
	 * Computes the record id that precedes the would-be position of `recordId` under `value` in the global sort order
	 * this tree defines (buckets ascend by value, records within a bucket ascend by id), or
	 * {@link EvitaDataTypes#RESERVED_PRIMARY_KEY} when the record belongs to the very first position. The answer is
	 * insensitive to whether `recordId` is already present in the bucket.
	 *
	 * The no-predecessor answer uses the reserved primary key rather than an in-range value such as
	 * {@link Integer#MIN_VALUE}, because evitaDB never assigns that key to an entity — so it cannot collide with a
	 * genuine record id, whereas every other `int` can.
	 *
	 * @param value    the value the inserted record is associated with
	 * @param recordId the record id being inserted
	 * @return the record id to insert after, or {@link EvitaDataTypes#RESERVED_PRIMARY_KEY} when the record belongs
	 * first
	 */
	int computePreviousRecord(@Nonnull K value, int recordId);

}
