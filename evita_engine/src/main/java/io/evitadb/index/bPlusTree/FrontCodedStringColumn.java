/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

import io.evitadb.utils.ArrayUtils.InsertionPosition;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

import static io.evitadb.utils.ArrayUtils.EMPTY_BYTE_ARRAY;
import static io.evitadb.utils.ArrayUtils.EMPTY_INT_ARRAY;

/**
 * Front-coded (prefix-compressed) {@link ValueColumn} for {@link String} keys. Instead of one boxed {@link String}
 * object per bucket (a ~40–56 byte object header + `char[]` per value), the column stores the leaf's distinct values
 * in a single contiguous {@code byte[]} blob, each value encoded as the bytes it does **not** share with its physical
 * predecessor:
 *
 * ```
 * per entry:  varint(sharedPrefixLength)  varint(suffixLength)  suffixBytes(UTF-8)
 * ```
 *
 * Every {@link #RESTART_INTERVAL}-th entry is a *restart point* — stored in full ({@code sharedPrefixLength == 0}) with
 * its blob offset recorded in {@link #restartOffsets} — so any entry is reconstructed by seeking the enclosing restart
 * and decoding at most {@code RESTART_INTERVAL - 1} forward steps. This is the Lucene term-dictionary layout, measured
 * at ~10 B/bucket vs ~96–117 B for the boxed column on real high-cardinality string attributes (codes / EANs / URLs).
 *
 * **Order independence.** Front-coding is orthogonal to the tree's key order: the column stores values in whatever
 * physical order the leaf inserts them (natural codepoint order for a non-localized attribute, collation order for a
 * localized one), and {@link #findKeyPosition} decodes each candidate back to a real {@link String} and compares it
 * through the supplied comparator. The stored byte order never has to match the comparator, so a single implementation
 * serves both localized and non-localized string attributes — the factory selects it for every {@link String} key.
 *
 * **Slot contract.** The leaf drives every column as a fixed-capacity, slot-indexed array kept in lockstep with its
 * {@code int[]} record column. A variable-length blob honours that contract by tracking a live entry count
 * ({@link #size}, always equal to the leaf's {@code peek + 1}) and emulating each array-slot operation
 * ({@link #insertKeyAt} / {@link #removeKeyAt} / {@link #copyRangeTo} / {@link #fillEmpty} / {@link #clearAt}) by
 * decoding the affected entries, applying the exact {@code System.arraycopy} slot semantics, and re-encoding a fresh
 * dense blob. The hot single-slot mutators ({@link #insertKeyAt} / {@link #removeKeyAt} / {@link #copyRangeTo}) decode
 * into the thread-local {@link DecodeScratch#flat} buffer ({@link #decodeAllToFlat}) — one reused {@code byte[]} +
 * {@code int[]} offset table splice like an in-place array move, instead of allocating {@link #size} individual
 * {@code byte[]} entries; the colder {@link #clearAt} / {@link #fillEmpty} (pure tail truncation) still go through
 * {@link #decodeAllBytes}'s {@code byte[][]}. Either way the transient decode state lives only for the duration of the
 * operation, so the column's retained footprint is just the trimmed blob plus the sparse restart index. A mutation is
 * therefore {@code O(size)} — the same asymptotic cost as the boxed column's reference {@code System.arraycopy}, and
 * {@code size} is bounded by the leaf block size — so no hot path regresses asymptotically. The only added cost is the
 * transient {@link String} allocated per decoded candidate on the search path; this trades a small amount of transient
 * allocation for a large retained-heap reduction, which is the explicit goal here (the query algebra already allocates
 * {@code RoaringBitmap}s). A zero-allocation scratch-{@code CharSequence} compare is a possible future refinement.
 *
 * **Scratch contract.** The per-hop decode buffer, the flat-buffer decode pair and the encode buffer are reused
 * across calls via a {@link ThreadLocal} {@link DecodeScratch} (grown on demand, never shrunk) so the high-frequency
 * search and mutation paths allocate no per-call {@code byte[]}. The reuse is safe because nothing thread-local ever
 * escapes: {@link #decodeAt} copies its result into a fresh {@link String}, {@link #decodeAllBytes} /
 * {@link #decodeRangeBytes} copy each entry into a caller-owned {@code byte[]}, {@link #decodeAllToFlat}'s flat
 * buffer is only ever read back by the very call that produced it, and {@link #encode} always trims the scratch
 * buffer into a freshly allocated {@link #data} blob — so no retained column state aliases the scratch, and a
 * mutation on one column (or one MVCC transaction layer) cannot leak into another that later reuses the same
 * thread's scratch.
 *
 * Selected by {@link ValueColumnFactory#forKey} for every {@link String} attribute, localized or not.
 *
 * @param <M> the boxed key type as seen by the tree's generic API (always {@link String} in practice)
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class FrontCodedStringColumn<M extends Comparable<M>> implements ValueColumn<M> {
	/**
	 * Number of entries between restart points. Every {@code RESTART_INTERVAL}-th entry is stored in full so random
	 * access decodes at most {@code RESTART_INTERVAL - 1} forward steps; the per-entry restart-index overhead is
	 * {@code 4 / RESTART_INTERVAL} bytes. Matches the value validated by the bucket-store memory spike.
	 */
	private static final int RESTART_INTERVAL = 16;
	/**
	 * Minimum initial size of the encode scratch buffer (floor for the {@code n * BYTES_PER_ENTRY_ESTIMATE} estimate).
	 */
	private static final int MIN_BUFFER_BYTES = 16;
	/**
	 * Rough initial blob-size estimate per entry, used only to pre-size the encode scratch buffer (it grows on demand).
	 */
	private static final int BYTES_PER_ENTRY_ESTIMATE = 4;
	/**
	 * Worst-case header bytes per entry: two length varints, each at most 5 bytes for a 32-bit value.
	 */
	private static final int MAX_ENTRY_HEADER_BYTES = 10;
	/**
	 * Initial size of the single scratch buffer reused across the forward hops of one {@link #decodeAt} / {@link
	 * #decodeAll} decode. Sized for typical high-cardinality string keys (codes / EANs / URLs, ~10–40 B) so the common
	 * decode never has to grow the scratch; longer keys grow it on demand.
	 */
	private static final int DECODE_SCRATCH_BYTES = 48;
	/**
	 * Shared empty byte array used as the "no predecessor" sentinel at the start of a decode and for an empty key.
	 */
	private static final byte[] EMPTY_BYTES = EMPTY_BYTE_ARRAY;
	/**
	 * Shared empty restart index so an empty column allocates none.
	 */
	private static final int[] EMPTY_OFFSETS = EMPTY_INT_ARRAY;
	/**
	 * Premise-failure message for a corrupt blob whose entry claims a shared prefix longer than its decoded predecessor.
	 * Held as a compile-time constant so the per-hop decode check stays allocation-free.
	 */
	private static final String CORRUPT_BLOB_MESSAGE =
		"Front-coded blob is corrupt: shared prefix exceeds decoded predecessor length!";

	/**
	 * Per-thread scratch buffers reused across every decode/encode on the calling thread. Holding them in a
	 * {@link ThreadLocal} removes the per-call {@code byte[]} the search path ({@link #decodeAt}, invoked on every
	 * binary-search hop of every mutation) and the encode path would otherwise allocate — the dominant young-gen churn
	 * on the high-cardinality string warmup path.
	 *
	 * The reuse is safe because nothing thread-local ever escapes: {@link #decodeAt} copies its result into a fresh
	 * {@link String}, {@link #decodeAllBytes} copies each entry into a caller-owned {@code byte[]}, {@link #decodeAllToFlat}
	 * is only ever read by the same call stack that produced it (never stored on the column), and {@link #encode} always
	 * trims {@link #encodeBuf} into a freshly allocated {@link #data} blob — so no retained column state aliases the
	 * scratch, and a mutation on one column (or one MVCC transaction layer) cannot leak into another that later reuses
	 * the same thread's scratch. All buffers grow on demand (doubling), are never shrunk, and there is exactly one
	 * holder per thread, so a 32-thread commit pool retains at most 32 holders (kilobytes each).
	 */
	private static final class DecodeScratch {
		/** Decode buffer reused across the forward hops of one {@link #decodeAt} / {@link #decodeAllBytes} decode. */
		byte[] cur = new byte[DECODE_SCRATCH_BYTES];
		/** Encode buffer the front-coded blob is assembled into before it is trimmed into a fresh {@link #data}. */
		byte[] encodeBuf = EMPTY_BYTES;
		/**
		 * Concatenated full decoded key bytes reused by {@link #decodeAllToFlat}: entry {@code i} occupies
		 * {@code flat[offsets[i] .. offsets[i + 1])}. Replaces a transient {@code byte[]} per entry with one reused
		 * buffer for the slot mutators ({@link #insertKeyAt} / {@link #removeKeyAt} / {@link #copyRangeTo}).
		 */
		byte[] flat = EMPTY_BYTES;
		/** Entry boundary table paired with {@link #flat}; length is always {@code >= size + 1}. */
		int[] offsets = EMPTY_INT_ARRAY;
	}

	/**
	 * Thread-local {@link DecodeScratch} holder. See {@link DecodeScratch} for the reuse and non-aliasing contract.
	 */
	private static final ThreadLocal<DecodeScratch> SCRATCH = ThreadLocal.withInitial(DecodeScratch::new);

	/**
	 * The fixed leaf block size — the value returned by {@link #capacity()}. The blob itself is variable-length and
	 * never sized to this; the leaf uses it only to size its parallel record column and to bound tail clears.
	 */
	private final int capacity;
	/**
	 * The number of live entries currently encoded in {@link #data}, kept equal to the owning leaf's {@code peek + 1}.
	 */
	private int size;
	/**
	 * Live byte length of {@link #data} (the backing array is trimmed to exactly this on every re-encode).
	 */
	private int dataLength;
	/**
	 * The front-coded blob holding the {@link #size} live entries back to back (see the class javadoc for the layout).
	 */
	@Nonnull private byte[] data;
	/**
	 * Byte offset into {@link #data} of every {@link #RESTART_INTERVAL}-th entry (the full-key restart points); length
	 * is {@code ceil(size / RESTART_INTERVAL)}.
	 */
	@Nonnull private int[] restartOffsets;

	/**
	 * Creates an empty column for a leaf of the given block size.
	 *
	 * @param capacity the leaf block size (== {@link #capacity()})
	 */
	FrontCodedStringColumn(int capacity) {
		this.capacity = capacity;
		resetToEmpty();
	}

	/**
	 * Internal constructor adopting pre-built state (duplicate path).
	 *
	 * @param capacity       the leaf block size
	 * @param size           the live entry count
	 * @param dataLength     the live byte length of {@code data}
	 * @param data           the front-coded blob (adopted as-is, already trimmed)
	 * @param restartOffsets the restart-offset index (adopted as-is)
	 */
	private FrontCodedStringColumn(int capacity, int size, int dataLength, @Nonnull byte[] data,
	                               @Nonnull int[] restartOffsets) {
		this.capacity = capacity;
		this.size = size;
		this.dataLength = dataLength;
		this.data = data;
		this.restartOffsets = restartOffsets;
	}

	@Override
	public int capacity() {
		return this.capacity;
	}

	@Nonnull
	@Override
	public ValueColumn<M> allocate(int capacity) {
		return new FrontCodedStringColumn<>(capacity);
	}

	@Nonnull
	@Override
	public ValueColumn<M> duplicate() {
		// structural share, not a copy: `data` / `restartOffsets` are safe to alias because every mutator
		// (insertKeyAt / removeKeyAt / copyRangeTo / clearAt / fillEmpty) replaces both by WHOLE REFERENCE via
		// encode(), never edits their bytes in place - so the new layer and this column can never observe each
		// other's writes even though they start out pointing at the same arrays. If a future in-place `data` edit
		// ever lands (reusing slack instead of trimming on every encode), this share must become copy-on-first-write.
		return new FrontCodedStringColumn<>(
			this.capacity, this.size, this.dataLength,
			this.data,
			this.restartOffsets
		);
	}

	@Nonnull
	@Override
	@SuppressWarnings("unchecked")
	public M keyAt(int index) {
		// boxing boundary — decoded exactly where the boxed leaf would have materialized the key
		return (M) decodeAt(index);
	}

	@Override
	public void insertKeyAt(int index, @Nonnull M value) {
		final DecodeScratch scratch = SCRATCH.get();
		decodeAllToFlat(scratch);
		final int n = this.size;
		// the only key that has to be (re-)encoded from a String is the freshly inserted one
		final byte[] newKeyBytes = ((String) value).getBytes(StandardCharsets.UTF_8);
		final int[] offsets = ensureIntCapacity(scratch.offsets, n + 2);
		final int insertOffset = offsets[index];
		final int tailLen = offsets[n] - insertOffset;
		byte[] flat = ensureCapacity(scratch.flat, offsets[n] + newKeyBytes.length);
		// open a gap of newKeyBytes.length at insertOffset, shifting the tail right
		System.arraycopy(flat, insertOffset, flat, insertOffset + newKeyBytes.length, tailLen);
		System.arraycopy(newKeyBytes, 0, flat, insertOffset, newKeyBytes.length);
		// shift the offset table right by one slot from `index` on, rebasing each shifted boundary by the new key's
		// length; offsets[index] itself is untouched (it already points at the gap the new key now occupies)
		for (int i = n; i >= index; i--) {
			offsets[i + 1] = offsets[i] + newKeyBytes.length;
		}
		scratch.flat = flat;
		scratch.offsets = offsets;
		encode(flat, offsets, n + 1);
	}

	@Override
	public void removeKeyAt(int index) {
		final DecodeScratch scratch = SCRATCH.get();
		decodeAllToFlat(scratch);
		final int n = this.size;
		final int[] offsets = scratch.offsets;
		final byte[] flat = scratch.flat;
		final int removeStart = offsets[index];
		final int removeLen = offsets[index + 1] - removeStart;
		final int tailLen = offsets[n] - offsets[index + 1];
		// close the gap left by the removed entry, shifting the tail left
		System.arraycopy(flat, offsets[index + 1], flat, removeStart, tailLen);
		// rebase every boundary from `index` on by the removed entry's length (reads offsets[i + 1] before it is
		// itself overwritten, since the loop walks left to right)
		for (int i = index; i < n; i++) {
			offsets[i] = offsets[i + 1] - removeLen;
		}
		encode(flat, offsets, n - 1);
	}

	@Override
	public void clearAt(int index) {
		// the leaf calls this only to release the freed last slot after removeKeyAt already dropped the entry, so the
		// slot is already absent (index == size); a defensive truncate keeps it a strict no-op for any slot >= size
		if (index < this.size) {
			encode(decodeAllBytes(), index);
		}
	}

	@Override
	public void copyRangeTo(int srcPos, @Nonnull ValueColumn<M> dst, int dstPos, int length) {
		final DecodeScratch scratch = SCRATCH.get();
		// snapshot the moved slice into a small, caller-owned array FIRST (handles dst == this, including the
		// overlapping right-shift in stealFromLeft): the destination decode below reuses the same thread-local
		// scratch this snapshot must survive
		final byte[][] slice = decodeRangeBytes(scratch, srcPos, srcPos + length);

		final FrontCodedStringColumn<M> target = asSameKind(dst);
		target.decodeAllToFlat(scratch);
		final int oldSize = target.size;
		final int newSize = Math.max(oldSize, dstPos + length);
		final byte[] srcFlat = scratch.flat;
		final int[] srcOffsets = scratch.offsets;

		// splice three segments into a small call-local buffer: the unchanged prefix [0, dstPos) (gap-filled with
		// empty keys past oldSize — see the note below), the moved slice, and the unchanged suffix [dstPos + length,
		// oldSize). Sizes change (the slice's total byte length rarely matches what it overwrites), so this has to be
		// assembled fresh rather than mutated in place; bounded by the leaf block size, this is one array pair, not
		// `length` individually-allocated entries.
		final int prefixCount = Math.min(dstPos, oldSize);
		int outLen = srcOffsets[prefixCount];
		for (final byte[] key : slice) {
			outLen += key.length;
		}
		if (dstPos + length < oldSize) {
			outLen += srcOffsets[oldSize] - srcOffsets[dstPos + length];
		}
		final byte[] outFlat = new byte[outLen];
		final int[] outOffsets = new int[newSize + 1];
		int pos = 0;
		int idx = 0;
		for (int i = 0; i < prefixCount; i++) {
			final int len = srcOffsets[i + 1] - srcOffsets[i];
			System.arraycopy(srcFlat, srcOffsets[i], outFlat, pos, len);
			outOffsets[idx++] = pos;
			pos += len;
		}
		// A right-shift (dstPos > oldSize, used by the leaf steal/merge rebalance to open room at the front) leaves
		// the slots between the old live end and dstPos logically empty; the caller always fills them with a second
		// copy before the column is read. Empty (zero-length) keys are a valid front-coded entry, unlike a null
		// slot in a fixed array, so no sentinel byte array is needed here.
		for (int i = prefixCount; i < dstPos; i++) {
			outOffsets[idx++] = pos;
		}
		for (final byte[] key : slice) {
			System.arraycopy(key, 0, outFlat, pos, key.length);
			outOffsets[idx++] = pos;
			pos += key.length;
		}
		for (int i = dstPos + length; i < oldSize; i++) {
			final int len = srcOffsets[i + 1] - srcOffsets[i];
			System.arraycopy(srcFlat, srcOffsets[i], outFlat, pos, len);
			outOffsets[idx++] = pos;
			pos += len;
		}
		outOffsets[idx] = pos;
		target.encode(outFlat, outOffsets, newSize);
	}

	@Override
	public void fillEmpty(int fromInclusive, int toExclusive) {
		// truncate the live tail to [0, fromInclusive); slots are size-authoritative so toExclusive only asserts bounds
		if (fromInclusive < this.size) {
			encode(decodeAllBytes(), fromInclusive);
		}
	}

	@Nonnull
	@Override
	@SuppressWarnings("unchecked")
	public InsertionPosition findKeyPosition(
		@Nonnull M key,
		int from, int to,
		@Nullable Comparator<M> comparator
	) {
		final String probe = (String) key;
		int lo = from;
		int hi = to - 1;
		while (lo <= hi) {
			final int mid = (lo + hi) >>> 1;
			final String candidate = decodeAt(mid);
			final int cmp = comparator != null
				? comparator.compare((M) candidate, key)
				: candidate.compareTo(probe);
			if (cmp < 0) {
				lo = mid + 1;
			} else if (cmp > 0) {
				hi = mid - 1;
			} else {
				return new InsertionPosition(mid, true);
			}
		}
		return new InsertionPosition(lo, false);
	}

	@Override
	public void appendKey(@Nonnull StringBuilder sb, int index) {
		sb.append(decodeAt(index));
	}

	@Nonnull
	@Override
	@SuppressWarnings("unchecked")
	public M[] asBoxedArray() {
		// cold path only (consistency verification / toString) — length matches capacity, tail slots stay null
		final String[] boxed = new String[this.capacity];
		final String[] decoded = decodeAll();
		System.arraycopy(decoded, 0, boxed, 0, this.size);
		return (M[]) boxed;
	}

	/**
	 * Decodes the key at the given live index by seeking the enclosing restart point and walking forward.
	 *
	 * @param index the live slot to decode (must be {@code < size})
	 * @return the decoded value
	 */
	@Nonnull
	private String decodeAt(int index) {
		final int restart = index / RESTART_INTERVAL;
		final int base = restart * RESTART_INTERVAL;
		int pos = this.restartOffsets[restart];
		// one scratch buffer reused across every forward hop AND across calls (thread-local): front-coding shares each
		// entry's prefix with its physical predecessor (shared <= the length we just decoded), so the first `shared`
		// bytes are already present in `cur` from the previous hop - only the suffix from offset `shared` onward is
		// overwritten. No per-hop byte[] and no per-hop prefix copy; borrowing the thread-local scratch also removes the
		// per-call allocation that dominated the search-path churn. The varint-read loop stays inlined (a shared helper
		// would force a per-call holder).
		final DecodeScratch scratch = SCRATCH.get();
		byte[] cur = scratch.cur;
		int curLen = 0;
		for (int j = base; j <= index; j++) {
			// read varint sharedPrefixLength
			int shared = 0;
			int shift = 0;
			byte b;
			do {
				b = this.data[pos++];
				shared |= (b & 0x7F) << shift;
				shift += 7;
			} while ((b & 0x80) != 0);
			// read varint suffixLength
			int suffixLen = 0;
			shift = 0;
			do {
				b = this.data[pos++];
				suffixLen |= (b & 0x7F) << shift;
				shift += 7;
			} while ((b & 0x80) != 0);
			// front-coding invariant: a shared prefix can never exceed the predecessor we already decoded - check it so
			// a corrupt blob fails fast (the original threw AIOOBE via the prefix arraycopy) instead of returning a wrong
			// key. A constant message keeps this per-hop check allocation-free on the decode hot path.
			Assert.isPremiseValid(shared <= curLen, CORRUPT_BLOB_MESSAGE);
			final int total = shared + suffixLen;
			if (total > cur.length) {
				// grow preserving the already-decoded prefix (the first `shared` <= curLen bytes stay valid)
				cur = Arrays.copyOf(cur, Math.max(total, cur.length << 1));
			}
			System.arraycopy(this.data, pos, cur, shared, suffixLen);
			pos += suffixLen;
			curLen = total;
		}
		// write the (possibly grown) buffer back so the growth is reused by the next call on this thread
		scratch.cur = cur;
		// the explicit length is load-bearing: it stops a shorter entry from leaking stale tail bytes left in the reused
		// scratch by a longer predecessor
		return new String(cur, 0, curLen, StandardCharsets.UTF_8);
	}

	/**
	 * Decodes all live entries sequentially into a fresh {@code byte[][]} of length {@link #size}, each element holding
	 * one entry's raw UTF-8 bytes. This is the byte-level workhorse the slot mutators use: re-encoding via
	 * {@link #encode(byte[][], int)} then never has to turn the unchanged keys back into bytes, so a single mutation no
	 * longer allocates {@link #size} {@link String} objects plus {@link #size} {@code getBytes()} arrays.
	 *
	 * @return the decoded live entries' raw UTF-8 bytes, in physical order; every element is a freshly allocated,
	 *         caller-owned {@code byte[]} that aliases neither the column's internal {@link #data} blob nor the
	 *         decode scratch, so a caller may keep, mutate, or replace any slot — which is exactly what lets the
	 *         slot mutators stamp a new key into one slot before re-{@link #encode(byte[][], int)}
	 */
	@Nonnull
	private byte[][] decodeAllBytes() {
		final byte[][] out = new byte[this.size][];
		int pos = 0;
		// one scratch buffer reused across every entry AND across calls (thread-local): each entry shares its prefix
		// with the previous one, so the first `shared` bytes already sit in `cur`; only the suffix is overwritten. Each
		// out[i] is a fresh copy, so it never aliases the scratch and nothing thread-local escapes this method.
		final DecodeScratch scratch = SCRATCH.get();
		byte[] cur = scratch.cur;
		int curLen = 0;
		for (int i = 0; i < this.size; i++) {
			int shared = 0;
			int shift = 0;
			byte b;
			do {
				b = this.data[pos++];
				shared |= (b & 0x7F) << shift;
				shift += 7;
			} while ((b & 0x80) != 0);
			int suffixLen = 0;
			shift = 0;
			do {
				b = this.data[pos++];
				suffixLen |= (b & 0x7F) << shift;
				shift += 7;
			} while ((b & 0x80) != 0);
			// front-coding invariant (constant message keeps this per-entry check allocation-free)
			Assert.isPremiseValid(shared <= curLen, CORRUPT_BLOB_MESSAGE);
			final int total = shared + suffixLen;
			if (total > cur.length) {
				cur = Arrays.copyOf(cur, Math.max(total, cur.length << 1));
			}
			System.arraycopy(this.data, pos, cur, shared, suffixLen);
			pos += suffixLen;
			out[i] = Arrays.copyOf(cur, total);
			curLen = total;
		}
		// write the (possibly grown) buffer back so the growth is reused by the next call on this thread
		scratch.cur = cur;
		return out;
	}

	/**
	 * Decodes every live entry into the calling thread's {@link DecodeScratch#flat} / {@link DecodeScratch#offsets}:
	 * entry {@code i}'s full decoded bytes occupy {@code flat[offsets[i] .. offsets[i + 1])}. This is the flat-buffer
	 * counterpart of {@link #decodeAllBytes} used by the hot slot mutators ({@link #insertKeyAt} / {@link #removeKeyAt}
	 * / {@link #copyRangeTo}): one reused {@code byte[]} plus one reused {@code int[]} offset table replace the
	 * {@code size} individually-allocated entry arrays {@link #decodeAllBytes} produces, so a single mutation no
	 * longer allocates {@link #size} small objects.
	 *
	 * Each entry is written as a *self-contained* copy of its full bytes (not front-coded) so the buffer can be
	 * spliced with a plain {@code System.arraycopy} by the caller; the shared-prefix compression is re-derived once,
	 * on the way back out, by {@link #encode(byte[], int[], int)}. Because entries are appended left to right without
	 * gaps, entry {@code i}'s shared prefix (if any) already sits at {@code flat[offsets[i - 1] .. offsets[i - 1] +
	 * shared)} — the immediately preceding entry, still resident earlier in the same buffer — so no separate rolling
	 * "current key" buffer is needed the way {@link #decodeAt} / {@link #decodeAllBytes} need one.
	 *
	 * @param scratch the calling thread's scratch (already fetched by the caller, which reuses it afterward)
	 */
	private void decodeAllToFlat(@Nonnull DecodeScratch scratch) {
		final int n = this.size;
		int[] offsets = ensureIntCapacity(scratch.offsets, n + 1);
		byte[] flat = scratch.flat;
		int pos = 0;
		int flatPos = 0;
		for (int i = 0; i < n; i++) {
			offsets[i] = flatPos;
			// read varint sharedPrefixLength
			int shared = 0;
			int shift = 0;
			byte b;
			do {
				b = this.data[pos++];
				shared |= (b & 0x7F) << shift;
				shift += 7;
			} while ((b & 0x80) != 0);
			// read varint suffixLength
			int suffixLen = 0;
			shift = 0;
			do {
				b = this.data[pos++];
				suffixLen |= (b & 0x7F) << shift;
				shift += 7;
			} while ((b & 0x80) != 0);
			final int prevLen = i == 0 ? 0 : flatPos - offsets[i - 1];
			// front-coding invariant (constant message keeps this per-entry check allocation-free)
			Assert.isPremiseValid(shared <= prevLen, CORRUPT_BLOB_MESSAGE);
			final int total = shared + suffixLen;
			flat = ensureCapacity(flat, flatPos + total);
			if (shared > 0) {
				// the shared prefix is already sitting at the previous entry's location, earlier in this same buffer
				System.arraycopy(flat, offsets[i - 1], flat, flatPos, shared);
			}
			System.arraycopy(this.data, pos, flat, flatPos + shared, suffixLen);
			pos += suffixLen;
			flatPos += total;
		}
		offsets[n] = flatPos;
		scratch.flat = flat;
		scratch.offsets = offsets;
	}

	/**
	 * Decodes only the live entries in {@code [fromInclusive, toExclusive)} into a fresh, caller-owned
	 * {@code byte[][]} (each element a freshly allocated copy aliasing neither {@link #data} nor the scratch). Used by
	 * {@link #copyRangeTo} to snapshot the small moved slice *before* decoding the destination column into the shared
	 * thread-local scratch (which would otherwise clobber an in-scratch slice when {@code dst == this}).
	 *
	 * @param scratch       the calling thread's scratch (already fetched by the caller)
	 * @param fromInclusive the first live index to decode (restart-seeks to its enclosing restart point)
	 * @param toExclusive   the exclusive end index
	 * @return the decoded {@code [fromInclusive, toExclusive)} entries' raw UTF-8 bytes, in physical order
	 */
	@Nonnull
	private byte[][] decodeRangeBytes(@Nonnull DecodeScratch scratch, int fromInclusive, int toExclusive) {
		final int count = toExclusive - fromInclusive;
		final byte[][] out = new byte[count][];
		if (count == 0) {
			return out;
		}
		final int restart = fromInclusive / RESTART_INTERVAL;
		final int base = restart * RESTART_INTERVAL;
		int pos = this.restartOffsets[restart];
		byte[] cur = scratch.cur;
		int curLen = 0;
		// walk from the enclosing restart point, decoding (but discarding) entries before fromInclusive so the
		// shared-prefix chain is reconstructed correctly, same as decodeAt's restart-seek-then-walk
		for (int j = base; j < toExclusive; j++) {
			int shared = 0;
			int shift = 0;
			byte b;
			do {
				b = this.data[pos++];
				shared |= (b & 0x7F) << shift;
				shift += 7;
			} while ((b & 0x80) != 0);
			int suffixLen = 0;
			shift = 0;
			do {
				b = this.data[pos++];
				suffixLen |= (b & 0x7F) << shift;
				shift += 7;
			} while ((b & 0x80) != 0);
			Assert.isPremiseValid(shared <= curLen, CORRUPT_BLOB_MESSAGE);
			final int total = shared + suffixLen;
			if (total > cur.length) {
				cur = Arrays.copyOf(cur, Math.max(total, cur.length << 1));
			}
			System.arraycopy(this.data, pos, cur, shared, suffixLen);
			pos += suffixLen;
			curLen = total;
			if (j >= fromInclusive) {
				out[j - fromInclusive] = Arrays.copyOf(cur, total);
			}
		}
		scratch.cur = cur;
		return out;
	}

	/**
	 * Decodes all live entries into a fresh {@code String[]} of length {@link #size}. Cold path only (consistency
	 * verification / {@link #asBoxedArray}); the hot slot mutators use {@link #decodeAllBytes} instead.
	 *
	 * @return the decoded live values in physical order
	 */
	@Nonnull
	private String[] decodeAll() {
		final byte[][] raw = decodeAllBytes();
		final String[] out = new String[raw.length];
		for (int i = 0; i < raw.length; i++) {
			out[i] = new String(raw[i], StandardCharsets.UTF_8);
		}
		return out;
	}

	/**
	 * Re-encodes the first {@code n} entries of {@code keys} (raw UTF-8 key bytes) into a fresh trimmed blob + restart
	 * index, replacing this column's state. {@code keys.length} may exceed {@code n} (only the live prefix is encoded).
	 *
	 * Operating on raw {@code byte[]} keys rather than {@link String}s is deliberate: the slot mutators decode the live
	 * entries straight to their UTF-8 bytes ({@link #decodeAllBytes}), so the unchanged keys never round-trip through a
	 * {@link String} object and a second {@code getBytes()} encode here — only the one newly-inserted key is encoded.
	 *
	 * @param keys the source key bytes (at least {@code n} non-null entries)
	 * @param n    the number of entries to encode
	 */
	private void encode(@Nonnull byte[][] keys, int n) {
		if (n == 0) {
			resetToEmpty();
			return;
		}
		final int[] restarts = newRestartTable(n);
		final DecodeScratch scratch = SCRATCH.get();
		byte[] buf = acquireEncodeBuf(scratch, n);
		int len = 0;
		byte[] prev = EMPTY_BYTES;
		for (int i = 0; i < n; i++) {
			final byte[] keyBytes = keys[i];
			final int shared;
			if (i % RESTART_INTERVAL == 0) {
				restarts[i / RESTART_INTERVAL] = len;
				shared = 0;
			} else {
				shared = commonPrefix(prev, keyBytes);
			}
			final int suffixLen = keyBytes.length - shared;
			buf = ensureCapacity(buf, len + MAX_ENTRY_HEADER_BYTES + suffixLen);
			len = writeVarInt(buf, len, shared);
			len = writeVarInt(buf, len, suffixLen);
			System.arraycopy(keyBytes, shared, buf, len, suffixLen);
			len += suffixLen;
			prev = keyBytes;
		}
		finishEncode(scratch, buf, len, restarts, n);
	}

	/**
	 * Re-encodes the first {@code n} entries of the flat buffer (entry {@code i} is {@code flat[offsets[i] ..
	 * offsets[i + 1])}) into a fresh trimmed blob + restart index, replacing this column's state. Flat-buffer
	 * counterpart of {@link #encode(byte[][], int)} used by the slot mutators that produce a
	 * {@link DecodeScratch#flat} / {@link DecodeScratch#offsets} pair instead of a {@code byte[][]}.
	 *
	 * @param flat    the source key bytes, concatenated (at least {@code offsets[n]} bytes)
	 * @param offsets the entry boundary table (at least {@code n + 1} entries)
	 * @param n       the number of entries to encode
	 */
	private void encode(@Nonnull byte[] flat, @Nonnull int[] offsets, int n) {
		if (n == 0) {
			resetToEmpty();
			return;
		}
		final int[] restarts = newRestartTable(n);
		final DecodeScratch scratch = SCRATCH.get();
		byte[] buf = acquireEncodeBuf(scratch, n);
		int len = 0;
		int prevStart = 0;
		int prevLen = 0;
		for (int i = 0; i < n; i++) {
			final int start = offsets[i];
			final int keyLen = offsets[i + 1] - start;
			final int shared;
			if (i % RESTART_INTERVAL == 0) {
				restarts[i / RESTART_INTERVAL] = len;
				shared = 0;
			} else {
				shared = commonPrefix(flat, prevStart, prevLen, start, keyLen);
			}
			final int suffixLen = keyLen - shared;
			buf = ensureCapacity(buf, len + MAX_ENTRY_HEADER_BYTES + suffixLen);
			len = writeVarInt(buf, len, shared);
			len = writeVarInt(buf, len, suffixLen);
			System.arraycopy(flat, start + shared, buf, len, suffixLen);
			len += suffixLen;
			prevStart = start;
			prevLen = keyLen;
		}
		finishEncode(scratch, buf, len, restarts, n);
	}

	/**
	 * Resets this column to the empty state (shared by both {@code encode} overloads' {@code n == 0} branch and the
	 * public no-arg constructor).
	 */
	private void resetToEmpty() {
		this.data = EMPTY_BYTES;
		this.dataLength = 0;
		this.restartOffsets = EMPTY_OFFSETS;
		this.size = 0;
	}

	/**
	 * Allocates the restart-offset index for {@code n} entries (shared by both {@code encode} overloads).
	 *
	 * @param n the number of entries to be encoded
	 * @return a fresh, zeroed restart-offset table of the correct length
	 */
	@Nonnull
	private static int[] newRestartTable(int n) {
		return new int[(n + RESTART_INTERVAL - 1) / RESTART_INTERVAL];
	}

	/**
	 * Borrows the thread-local encode buffer (grown on demand) instead of allocating a fresh one per encode; shared
	 * by both {@code encode} overloads.
	 *
	 * @param scratch the calling thread's scratch
	 * @param n       the number of entries to be encoded (sizing hint only)
	 * @return a buffer of at least the estimated required capacity
	 */
	@Nonnull
	private static byte[] acquireEncodeBuf(@Nonnull DecodeScratch scratch, int n) {
		return ensureCapacity(scratch.encodeBuf, Math.max(MIN_BUFFER_BYTES, n * BYTES_PER_ENTRY_ESTIMATE));
	}

	/**
	 * Commits the encode buffer's live prefix as this column's new state; shared by both {@code encode} overloads.
	 *
	 * `buf` is shared thread-local scratch, so this ALWAYS copies out a fresh trimmed blob - never adopts `buf`
	 * into {@link #data} directly, or retained column state would alias the scratch and the next encode on this
	 * thread would corrupt it. This copy trims to the exact live length (minimal retained footprint) and is the
	 * load-bearing invariant that keeps the thread-local reuse MVCC-safe.
	 *
	 * @param scratch  the calling thread's scratch (the grown buffer is written back for reuse)
	 * @param buf      the (possibly grown) encode buffer holding the freshly encoded blob in {@code buf[0, len)}
	 * @param len      the live length of the encoded blob within {@code buf}
	 * @param restarts the restart-offset index built alongside the encode
	 * @param n        the number of entries encoded
	 */
	private void finishEncode(
		@Nonnull DecodeScratch scratch, @Nonnull byte[] buf, int len, @Nonnull int[] restarts, int n
	) {
		scratch.encodeBuf = buf;
		this.data = Arrays.copyOf(buf, len);
		this.dataLength = len;
		this.restartOffsets = restarts;
		this.size = n;
	}

	/**
	 * Narrows a sibling column to the same concrete kind (one attribute index = one value type ⇒ always holds).
	 *
	 * @param other the sibling column
	 * @return {@code other} as a {@link FrontCodedStringColumn}
	 */
	@Nonnull
	private FrontCodedStringColumn<M> asSameKind(@Nonnull ValueColumn<M> other) {
		if (other instanceof FrontCodedStringColumn<M> frontCoded) {
			return frontCoded;
		}
		throw new IllegalArgumentException(
			"Cannot mix value column kinds within one tree: " + other.getClass().getName());
	}

	/**
	 * Returns the length of the common leading byte run of two arrays.
	 *
	 * @param a the first array
	 * @param b the second array
	 * @return the shared prefix length
	 */
	private static int commonPrefix(@Nonnull byte[] a, @Nonnull byte[] b) {
		final int min = Math.min(a.length, b.length);
		int i = 0;
		while (i < min && a[i] == b[i]) {
			i++;
		}
		return i;
	}

	/**
	 * Returns the length of the common leading byte run of two ranges within the same array (the flat-buffer
	 * counterpart of {@link #commonPrefix(byte[], byte[])} used by {@link #encode(byte[], int[], int)}).
	 *
	 * @param arr    the backing array holding both ranges
	 * @param aStart the start offset of the first (predecessor) range
	 * @param aLen   the length of the first range
	 * @param bStart the start offset of the second (current) range
	 * @param bLen   the length of the second range
	 * @return the shared prefix length
	 */
	private static int commonPrefix(@Nonnull byte[] arr, int aStart, int aLen, int bStart, int bLen) {
		final int min = Math.min(aLen, bLen);
		int i = 0;
		while (i < min && arr[aStart + i] == arr[bStart + i]) {
			i++;
		}
		return i;
	}

	/**
	 * Writes an unsigned LEB128 varint and returns the advanced position.
	 *
	 * @param buf   the target buffer (guaranteed to have room)
	 * @param pos   the write position
	 * @param value the non-negative value to write
	 * @return the position after the written bytes
	 */
	private static int writeVarInt(@Nonnull byte[] buf, int pos, int value) {
		int v = value;
		while ((v & ~0x7F) != 0) {
			buf[pos++] = (byte) ((v & 0x7F) | 0x80);
			v >>>= 7;
		}
		buf[pos++] = (byte) v;
		return pos;
	}

	/**
	 * Grows the buffer (doubling) if it cannot hold {@code required} bytes.
	 *
	 * @param buf      the current buffer
	 * @param required the minimum required length
	 * @return a buffer of at least {@code required} length (the same instance when already large enough)
	 */
	@Nonnull
	private static byte[] ensureCapacity(@Nonnull byte[] buf, int required) {
		if (buf.length >= required) {
			return buf;
		}
		int newLength = buf.length << 1;
		if (newLength < required) {
			newLength = required;
		}
		return Arrays.copyOf(buf, newLength);
	}

	/**
	 * Grows the offset table (doubling) if it cannot hold {@code required} entries. Mirrors {@link #ensureCapacity}
	 * for the {@link DecodeScratch#offsets} table.
	 *
	 * @param buf      the current offset table
	 * @param required the minimum required length
	 * @return a table of at least {@code required} length (the same instance when already large enough)
	 */
	@Nonnull
	private static int[] ensureIntCapacity(@Nonnull int[] buf, int required) {
		if (buf.length >= required) {
			return buf;
		}
		int newLength = buf.length << 1;
		if (newLength < required) {
			newLength = required;
		}
		return Arrays.copyOf(buf, newLength);
	}
}
