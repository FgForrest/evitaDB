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
 * localized one), and {@link #findKeyPosition} compares each candidate against the supplied comparator — decoding it
 * back to a real {@link String} first in the general case, or comparing raw UTF-8 bytes without decoding for the
 * BMP-safe/natural-order subset (see the "BMP-safe byte-compare fast path" section below). The stored byte order
 * never has to match the comparator, so a single implementation serves both localized and non-localized string
 * attributes — the factory selects it for every {@link String} key.
 *
 * **Slot contract.** The leaf drives every column as a fixed-capacity, slot-indexed array kept in lockstep with its
 * {@code int[]} record column. A variable-length blob honours that contract by tracking a live entry count
 * ({@link #size}, always equal to the leaf's {@code peek + 1}) and emulating each array-slot operation
 * ({@link #insertKeyAt} / {@link #removeKeyAt} / {@link #copyRangeTo} / {@link #fillEmpty} / {@link #clearAt}) by
 * decoding the affected entries, applying the exact {@code System.arraycopy} slot semantics, and re-encoding a fresh
 * dense blob. The hot mutators ({@link #insertKeyAt} / {@link #removeKeyAt} / {@link #copyRangeTo}) decode into
 * thread-local flat {@code byte[]} + {@code int[]} offset-table buffers ({@link #decodeAllToFlat} /
 * {@link #decodeRangeToFlat}) and splice like an in-place array move, instead of allocating {@link #size} (or
 * {@code length}) individual {@code byte[]} entries; {@link #copyRangeTo} additionally assembles its spliced output
 * into a third reused flat/offset pair rather than a fresh array per call. The colder {@link #clearAt} /
 * {@link #fillEmpty} (pure tail truncation) still go through {@link #decodeAllBytes}'s {@code byte[][]}. Either way
 * the transient decode state lives only for the duration of the
 * operation, so the column's retained footprint is just the trimmed blob plus the sparse restart index. A mutation is
 * therefore {@code O(size)} — the same asymptotic cost as the boxed column's reference {@code System.arraycopy}, and
 * {@code size} is bounded by the leaf block size — so no hot path regresses asymptotically. The transient {@link String}
 * decoded per candidate on the search path is confined to the fallback comparison — a supplementary character, a
 * localized comparator, or a non-natural-order tree; the BMP-safe/natural-order subset instead compares raw UTF-8
 * bytes with zero allocation (see the "BMP-safe byte-compare fast path" section below). Where the fallback still
 * applies, this trades a small amount of transient allocation for a large retained-heap reduction, which is the
 * explicit goal here (the query algebra already allocates {@code RoaringBitmap}s).
 *
 * **Scratch contract.** The per-hop decode buffer, the three flat-buffer decode/assembly pairs and the encode
 * buffer are reused across calls via a {@link ThreadLocal} {@link DecodeScratch} (grown on demand, never shrunk) so
 * the high-frequency search and mutation paths allocate no per-call {@code byte[]}. The reuse is safe because
 * nothing thread-local ever escapes: {@link #decodeAtString} copies its result into a fresh {@link String},
 * {@link #decodeAtBytes}'s raw-byte fast path leaves its result in {@link DecodeScratch#cur} but only for the
 * duration of the compare call that requested it and never retains a reference beyond it, {@link #decodeAllBytes}
 * copies each entry into a caller-owned {@code byte[]}, {@link #decodeAllToFlat} /
 * {@link #decodeRangeToFlat} / {@link #copyRangeTo}'s assembly buffer are only ever read back by the very call that
 * produced them, and {@link #encode} always trims the scratch buffer into a freshly allocated {@link #data} blob —
 * so no retained column state aliases the scratch, and a mutation on one column (or one MVCC transaction layer)
 * cannot leak into another that later reuses the same thread's scratch.
 *
 * **BMP-safe byte-compare fast path.** {@link #findKeyPosition} normally decodes each candidate to a {@link String}
 * and compares via the supplied comparator — correct for any comparator, but it pays a {@code new String(...)}
 * allocation per binary-search hop. Raw UTF-8 byte order equals {@link String#compareTo} order **iff every operand
 * is BMP-only** (no supplementary/surrogate codepoint). For the CORPUS side, a supplementary character is, in valid
 * UTF-8, exactly a 4-byte sequence whose lead byte is {@code >= 0xF0}, so "no suffix byte {@code >= 0xF0}" is a
 * single-threshold, allocation-free predicate that detects BMP-safety while {@link #encode} already scans every
 * suffix byte once (see {@link #bmpSafe}). For the PROBE side, the check instead scans the original {@link String}'s
 * UTF-16 chars for a surrogate code unit directly ({@link #isBmpSafe(String)}), rather than post-encoding it first:
 * a byte-based check would miss a lone (unpaired) surrogate, which {@link String#getBytes(java.nio.charset.Charset)}
 * silently replaces with an in-range replacement byte even though {@link String#compareTo} still compares it at its
 * true, out-of-BMP-range code-unit value. When the column is BMP-safe, was constructed under natural order
 * ({@link #naturalOrderSafe}), the caller's comparator is natural order too, and the probe itself is BMP-safe,
 * {@link #findKeyPosition} skips the {@link String} entirely and compares raw UTF-8 bytes — same restart-walk, same
 * scratch reuse, just no allocation on the compare. Any operand outside this predicate (a supplementary character, a
 * localized comparator, a non-natural-order tree) falls through to the always-correct {@link String} path.
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
	 * The lowest UTF-8 lead byte of a 4-byte (supplementary-plane) sequence. A suffix byte {@code >=} this threshold
	 * proves the entry is not BMP-only; see the class javadoc "BMP-safe byte-compare fast path" section.
	 */
	private static final int SUPPLEMENTARY_LEAD_BYTE = 0xF0;
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
	 * {@link ThreadLocal} removes the per-call {@code byte[]} the search path ({@link #findKeyPosition}'s loop, which
	 * fetches this scratch once and calls {@link #decodeAtString} / {@link #decodeAtBytes} directly on every
	 * binary-search hop of every mutation) and the encode path would otherwise allocate — the dominant young-gen churn
	 * on the high-cardinality string warmup path.
	 *
	 * The reuse is safe because nothing thread-local ever escapes: {@link #decodeAtString} copies its result into a
	 * fresh {@link String}, {@link #decodeAtBytes}'s raw-byte fast path leaves its result in {@link DecodeScratch#cur}
	 * but only for the duration of the compare call that requested it, {@link #decodeAllBytes} copies each entry into
	 * a caller-owned {@code byte[]}, {@link #decodeAllToFlat}
	 * / {@link #decodeRangeToFlat} are only ever read back by the same call stack that produced them (never stored on the
	 * column), and {@link #encode} always trims {@link DecodeScratch#encodeBuf} into a freshly allocated {@link #data} blob — so no
	 * retained column state aliases the scratch, and a mutation on one column (or one MVCC transaction layer) cannot leak
	 * into another that later reuses the same thread's scratch. {@link #copyRangeTo} needs THREE independent flat/offset
	 * pairs live at once ({@code this}'s moved slice, the destination's own entries, and the spliced assembly output) —
	 * distinct fields rather than one shared pair, so decoding all three never clobbers one another even when the source
	 * and destination are the same column. All buffers grow on demand (doubling), are never shrunk, and there is exactly
	 * one holder per thread, so a 32-thread commit pool retains at most 32 holders (kilobytes each).
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
		/**
		 * {@link #copyRangeTo}'s moved-slice buffer, populated by {@link #decodeRangeToFlat}: distinct from {@link #flat}
		 * (which decodes the *destination* column's own entries) so both can be live at once even when the source and
		 * destination are the same column instance.
		 */
		byte[] flat2 = EMPTY_BYTES;
		/** Entry boundary table paired with {@link #flat2}, indexed relative to the {@code base} {@link #decodeRangeToFlat} returns. */
		int[] offsets2 = EMPTY_INT_ARRAY;
		/**
		 * {@link #copyRangeTo}'s splice-assembly output buffer: the prefix/gap/slice/suffix segments are written here
		 * before being handed to {@link #encode(DecodeScratch, byte[], int[], int)}, which only ever reads it
		 * (never retains it), so reusing this buffer across calls is safe.
		 */
		byte[] flat3 = EMPTY_BYTES;
		/** Entry boundary table paired with {@link #flat3}. */
		int[] offsets3 = EMPTY_INT_ARRAY;
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
	 * Whether every suffix byte in {@link #data} is {@code < 0xF0} (no supplementary-plane UTF-8 lead byte), i.e. every
	 * live key is BMP-only. Recomputed from scratch by every {@link #encode} call (self-heals across mutations — see
	 * the class javadoc "BMP-safe byte-compare fast path" section); {@code true} for an empty column.
	 */
	private boolean bmpSafe;
	/**
	 * Whether this column was constructed for a natural-order tree ({@link ValueColumnFactory#isNaturalOrder}
	 * evaluated once, at construction, against the same comparator every {@link #findKeyPosition} call receives).
	 * Immutable for the lifetime of the instance; carried forward by {@link #allocate} and {@link #duplicate}.
	 */
	private final boolean naturalOrderSafe;

	/**
	 * Creates an empty column for a leaf of the given block size.
	 *
	 * @param capacity         the leaf block size (== {@link #capacity()})
	 * @param naturalOrderSafe whether this column's tree orders keys naturally (see {@link #naturalOrderSafe})
	 */
	FrontCodedStringColumn(int capacity, boolean naturalOrderSafe) {
		this.capacity = capacity;
		this.naturalOrderSafe = naturalOrderSafe;
		this.size = 0;
		this.dataLength = 0;
		this.data = EMPTY_BYTES;
		this.restartOffsets = EMPTY_OFFSETS;
		this.bmpSafe = true;
	}

	/**
	 * Internal constructor adopting pre-built state (duplicate path).
	 *
	 * @param capacity         the leaf block size
	 * @param size             the live entry count
	 * @param dataLength       the live byte length of {@code data}
	 * @param data             the front-coded blob (adopted as-is, already trimmed)
	 * @param restartOffsets   the restart-offset index (adopted as-is)
	 * @param bmpSafe          whether every live key is BMP-only (adopted as-is)
	 * @param naturalOrderSafe whether this column's tree orders keys naturally (adopted as-is)
	 */
	private FrontCodedStringColumn(int capacity, int size, int dataLength, @Nonnull byte[] data,
	                               @Nonnull int[] restartOffsets, boolean bmpSafe, boolean naturalOrderSafe) {
		this.capacity = capacity;
		this.size = size;
		this.dataLength = dataLength;
		this.data = data;
		this.restartOffsets = restartOffsets;
		this.bmpSafe = bmpSafe;
		this.naturalOrderSafe = naturalOrderSafe;
	}

	@Override
	public int capacity() {
		return this.capacity;
	}

	@Nonnull
	@Override
	public ValueColumn<M> allocate(int capacity) {
		return new FrontCodedStringColumn<>(capacity, this.naturalOrderSafe);
	}

	@Nonnull
	@Override
	public ValueColumn<M> duplicate() {
		// structural share, not a copy: `data` / `restartOffsets` are safe to alias because every mutator
		// (insertKeyAt / removeKeyAt / copyRangeTo / clearAt / fillEmpty) replaces both by WHOLE REFERENCE via
		// encode(), never edits their bytes in place - so the new layer and this column can never observe each
		// other's writes even though they start out pointing at the same arrays. If a future in-place `data` edit
		// ever lands (reusing slack instead of trimming on every encode), this share must become copy-on-first-write.
		// bmpSafe / naturalOrderSafe are plain booleans, copied by value - nothing to alias.
		return new FrontCodedStringColumn<>(
			this.capacity, this.size, this.dataLength,
			this.data,
			this.restartOffsets,
			this.bmpSafe, this.naturalOrderSafe
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
		encode(scratch, flat, offsets, n + 1);
	}

	@Override
	public void bulkLoad(@Nonnull Object[] keys, int count) {
		// one UTF-8 encode per key (unavoidable — the blob has to hold the bytes anyway), then a single encode() pass
		// builds the whole front-coded blob at once, instead of the O(count) decode-splice-reencode-of-everything-
		// so-far that insertKeyAt would pay on each of `count` sequential calls (O(count²) total there vs O(count) here)
		final byte[][] rawKeys = new byte[count][];
		for (int i = 0; i < count; i++) {
			rawKeys[i] = ((String) keys[i]).getBytes(StandardCharsets.UTF_8);
		}
		encode(rawKeys, count);
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
		encode(scratch, flat, offsets, n - 1);
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
		// snapshot the moved slice into flat2/offsets2 and decode the destination's own live entries into
		// flat/offsets - two DISTINCT thread-local buffer pairs, so both can be live at once even when dst == this
		// (the overlapping right-shift in stealFromLeft); neither read touches this.data/target.data, which are only
		// replaced by the final encode() call below, once every read from them is already done
		final int sliceBase = decodeRangeToFlat(scratch, srcPos, srcPos + length);
		final byte[] sliceFlat = scratch.flat2;
		final int[] sliceOffsets = scratch.offsets2;
		final int sliceIndexBase = srcPos - sliceBase;

		final FrontCodedStringColumn<M> target = asSameKind(dst);
		target.decodeAllToFlat(scratch);
		final int oldSize = target.size;
		final int newSize = Math.max(oldSize, dstPos + length);
		final byte[] srcFlat = scratch.flat;
		final int[] srcOffsets = scratch.offsets;

		// splice three segments into the reused flat3/offsets3 assembly buffer: the unchanged prefix [0, dstPos)
		// (gap-filled with empty keys past oldSize — see the note below), the moved slice, and the unchanged suffix
		// [dstPos + length, oldSize). Sizes change (the slice's total byte length rarely matches what it overwrites),
		// so this has to be assembled fresh rather than mutated in place; encode() only ever reads flat3/offsets3
		// (never retains them), so reusing the buffer across calls instead of allocating fresh ones is safe.
		final int prefixCount = Math.min(dstPos, oldSize);
		int outLen = srcOffsets[prefixCount];
		for (int i = 0; i < length; i++) {
			final int idx = sliceIndexBase + i;
			outLen += sliceOffsets[idx + 1] - sliceOffsets[idx];
		}
		if (dstPos + length < oldSize) {
			outLen += srcOffsets[oldSize] - srcOffsets[dstPos + length];
		}
		final byte[] outFlat = ensureCapacity(scratch.flat3, outLen);
		final int[] outOffsets = ensureIntCapacity(scratch.offsets3, newSize + 1);
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
		for (int i = 0; i < length; i++) {
			final int sIdx = sliceIndexBase + i;
			final int sStart = sliceOffsets[sIdx];
			final int len = sliceOffsets[sIdx + 1] - sStart;
			System.arraycopy(sliceFlat, sStart, outFlat, pos, len);
			outOffsets[idx++] = pos;
			pos += len;
		}
		for (int i = dstPos + length; i < oldSize; i++) {
			final int len = srcOffsets[i + 1] - srcOffsets[i];
			System.arraycopy(srcFlat, srcOffsets[i], outFlat, pos, len);
			outOffsets[idx++] = pos;
			pos += len;
		}
		outOffsets[idx] = pos;
		scratch.flat3 = outFlat;
		scratch.offsets3 = outOffsets;
		target.encode(scratch, outFlat, outOffsets, newSize);
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
		final DecodeScratch scratch = SCRATCH.get();
		// BMP-safe byte-compare fast path: resolved ONCE, outside the loop (mirrors decodeRangeToFlatCore's
		// `secondary` boolean) so the per-hop cost of choosing a strategy is zero. Non-null iff every operand -
		// the corpus (bmpSafe), this column's tree order (naturalOrderSafe), this call's comparator, and the probe
		// itself - is provably BMP-only; see the class javadoc. Falls through to the always-correct String path
		// otherwise (a supplementary character anywhere, a localized comparator, or a non-natural-order tree).
		byte[] probeBytes = null;
		if (this.naturalOrderSafe && this.bmpSafe && ValueColumnFactory.isNaturalOrder(comparator)
			&& isBmpSafe(probe)) {
			probeBytes = probe.getBytes(StandardCharsets.UTF_8);
		}
		int lo = from;
		int hi = to - 1;
		while (lo <= hi) {
			final int mid = (lo + hi) >>> 1;
			final int cmp;
			if (probeBytes != null) {
				final int candidateLen = decodeAtBytes(scratch, mid);
				cmp = compareUnsignedBytes(scratch.cur, candidateLen, probeBytes, probeBytes.length);
			} else {
				final String candidate = decodeAtString(scratch, mid);
				cmp = comparator != null
					? comparator.compare((M) candidate, key)
					: candidate.compareTo(probe);
			}
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
		return decodeAtString(SCRATCH.get(), index);
	}

	/**
	 * Same as {@link #decodeAt(int)}, but takes an already-fetched {@link DecodeScratch} - used by
	 * {@link #findKeyPosition}'s per-hop loop to avoid a repeated {@link ThreadLocal#get()} per binary-search hop.
	 *
	 * @param scratch the calling thread's scratch (already fetched by the caller)
	 * @param index   the live slot to decode (must be {@code < size})
	 * @return the decoded value
	 */
	@Nonnull
	private String decodeAtString(@Nonnull DecodeScratch scratch, int index) {
		final int len = decodeAtBytes(scratch, index);
		// the explicit length is load-bearing: it stops a shorter entry from leaking stale tail bytes left in the
		// reused scratch by a longer predecessor
		return new String(scratch.cur, 0, len, StandardCharsets.UTF_8);
	}

	/**
	 * Decodes the key at the given live index by seeking the enclosing restart point and walking forward, leaving the
	 * result in {@link DecodeScratch#cur} instead of wrapping it in a {@link String}: {@link #decodeAtString} wraps
	 * the result for the general case, while {@link #findKeyPosition}'s BMP-safe fast path calls this directly to
	 * compare raw bytes without ever allocating a {@link String}.
	 *
	 * @param scratch the calling thread's scratch (already fetched by the caller)
	 * @param index   the live slot to decode (must be {@code < size})
	 * @return the decoded key's length within {@link DecodeScratch#cur}
	 */
	private int decodeAtBytes(@Nonnull DecodeScratch scratch, int index) {
		final int restart = index / RESTART_INTERVAL;
		final int base = restart * RESTART_INTERVAL;
		int pos = this.restartOffsets[restart];
		// one scratch buffer reused across every forward hop AND across calls (thread-local): front-coding shares each
		// entry's prefix with its physical predecessor (shared <= the length we just decoded), so the first `shared`
		// bytes are already present in `cur` from the previous hop - only the suffix from offset `shared` onward is
		// overwritten. No per-hop byte[] and no per-hop prefix copy; borrowing the thread-local scratch also removes the
		// per-call allocation that dominated the search-path churn. The varint-read loop stays inlined (a shared helper
		// would force a per-call holder).
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
		return curLen;
	}

	/**
	 * Decodes all live entries sequentially into a fresh {@code byte[][]} of length {@link #size}, each element
	 * holding one entry's raw UTF-8 bytes. Used by the cold {@link #clearAt} / {@link #fillEmpty} truncation paths
	 * (re-encoding via {@link #encode(byte[][], int)} never has to turn the unchanged keys back into bytes) and by
	 * {@link #decodeAll}; the hot slot mutators use {@link #decodeAllToFlat} / {@link #decodeRangeToFlat} instead.
	 *
	 * @return the decoded live entries' raw UTF-8 bytes, in physical order; every element is a freshly allocated,
	 *         caller-owned {@code byte[]} that aliases neither the column's internal {@link #data} blob nor the
	 *         decode scratch, so a caller may keep, mutate, or replace any slot
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
	 * entry {@code i}'s full decoded bytes occupy {@code flat[offsets[i] .. offsets[i + 1])}. Used by the hot slot
	 * mutators ({@link #insertKeyAt} / {@link #removeKeyAt} / {@link #copyRangeTo}'s destination decode); see
	 * {@link #decodeRangeToFlatCore} for the shared decode mechanism.
	 *
	 * @param scratch the calling thread's scratch (already fetched by the caller, which reuses it afterward)
	 */
	private void decodeAllToFlat(@Nonnull DecodeScratch scratch) {
		decodeRangeToFlatCore(scratch, false, 0, this.size);
	}

	/**
	 * Decodes entries {@code [base, toExclusive)} — where {@code base} is the restart point enclosing
	 * {@code fromInclusive} — into {@code scratch.flat2} / {@code scratch.offsets2}: the caller reads entry
	 * {@code i} (for {@code i} in {@code [fromInclusive, toExclusive)}) as {@code scratch.flat2[offsets2[i - base]
	 * .. offsets2[i - base + 1])}. Used by {@link #copyRangeTo} to snapshot the moved slice into a buffer distinct
	 * from {@link DecodeScratch#flat} (which decodes the *destination* column's own entries), so both can be live
	 * at once even when the source and destination are the same column instance; see
	 * {@link #decodeRangeToFlatCore} for the shared decode mechanism.
	 *
	 * @param scratch       the calling thread's scratch (already fetched by the caller)
	 * @param fromInclusive the first live index the caller needs (restart-seeks to its enclosing restart point)
	 * @param toExclusive   the exclusive end index the caller needs
	 * @return {@code base}, the restart point {@code scratch.offsets2} is indexed relative to
	 */
	private int decodeRangeToFlat(@Nonnull DecodeScratch scratch, int fromInclusive, int toExclusive) {
		return decodeRangeToFlatCore(scratch, true, fromInclusive, toExclusive);
	}

	/**
	 * Shared core of {@link #decodeAllToFlat} and {@link #decodeRangeToFlat}: decodes {@code [base, toExclusive)}
	 * into a flat {@code byte[]} + {@code int[]} offset-table pair using a self-referential copy — entries are
	 * appended left to right without gaps, so entry {@code i}'s shared prefix (if any) already sits at
	 * {@code flat[offsets[i - 1] .. offsets[i - 1] + shared)}, the immediately preceding entry, still resident
	 * earlier in the same buffer, and no separate rolling "current key" buffer is needed the way {@link #decodeAt}
	 * / {@link #decodeAllBytes} need one; the shared-prefix compression itself is re-derived once, on the way back
	 * out, by {@link #encode(DecodeScratch, byte[], int[], int)}. Entries in {@code [base, fromInclusive)} are
	 * decoded only to reconstruct that chain for a range decode ({@link #decodeAllToFlat} always starts at {@code fromInclusive ==
	 * 0}, so it never has any). The two callers differ only in which buffer pair
	 * ({@link DecodeScratch#flat}/{@link DecodeScratch#offsets} vs {@link DecodeScratch#flat2}/
	 * {@link DecodeScratch#offsets2}) they read from / write back to; selecting the pair via {@code secondary} only
	 * branches outside the per-entry loop, so this costs nothing extra on the hot {@link #decodeAllToFlat} path.
	 *
	 * @param scratch       the calling thread's scratch (already fetched by the caller)
	 * @param secondary     {@code true} to use {@link DecodeScratch#flat2}/{@link DecodeScratch#offsets2} (the
	 *                      {@link #copyRangeTo} slice snapshot), {@code false} for {@link DecodeScratch#flat}/
	 *                      {@link DecodeScratch#offsets} (the whole-column decode)
	 * @param fromInclusive the first live index the caller needs (restart-seeks to its enclosing restart point)
	 * @param toExclusive   the exclusive end index the caller needs
	 * @return {@code base}, the restart point the written offsets are indexed relative to
	 */
	private int decodeRangeToFlatCore(
		@Nonnull DecodeScratch scratch, boolean secondary, int fromInclusive, int toExclusive
	) {
		if (fromInclusive == toExclusive) {
			// degenerate empty range: fromInclusive may equal `size` here, which would be an out-of-bounds restart
			// index below, so bail out before touching restartOffsets - callers never read the offsets in this case
			final int[] offsets = ensureIntCapacity(secondary ? scratch.offsets2 : scratch.offsets, 1);
			offsets[0] = 0;
			if (secondary) {
				scratch.offsets2 = offsets;
			} else {
				scratch.offsets = offsets;
			}
			return fromInclusive;
		}
		final int restart = fromInclusive / RESTART_INTERVAL;
		final int base = restart * RESTART_INTERVAL;
		final int n = toExclusive - base;
		int[] offsets = ensureIntCapacity(secondary ? scratch.offsets2 : scratch.offsets, n + 1);
		byte[] flat = secondary ? scratch.flat2 : scratch.flat;
		int pos = this.restartOffsets[restart];
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
		if (secondary) {
			scratch.flat2 = flat;
			scratch.offsets2 = offsets;
		} else {
			scratch.flat = flat;
			scratch.offsets = offsets;
		}
		return base;
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
	 * Cold path only ({@link #clearAt} / {@link #fillEmpty} truncation — the hot slot mutators call
	 * {@link #encode(DecodeScratch, byte[], int[], int)} directly). Flattens {@code keys} (raw UTF-8 key bytes, one
	 * entry per element; {@code keys.length} may exceed {@code n}, only the live prefix is encoded) into a contiguous
	 * buffer once and delegates there, so the shared-prefix / restart-table / trim logic exists in exactly one place.
	 *
	 * @param keys the source key bytes (at least {@code n} non-null entries)
	 * @param n    the number of entries to encode
	 */
	private void encode(@Nonnull byte[][] keys, int n) {
		if (n == 0) {
			resetToEmpty();
			return;
		}
		int totalLen = 0;
		for (int i = 0; i < n; i++) {
			totalLen += keys[i].length;
		}
		final byte[] flat = new byte[totalLen];
		final int[] offsets = new int[n + 1];
		int pos = 0;
		for (int i = 0; i < n; i++) {
			offsets[i] = pos;
			System.arraycopy(keys[i], 0, flat, pos, keys[i].length);
			pos += keys[i].length;
		}
		offsets[n] = pos;
		// cold path: no caller-held scratch to thread in, so this adapter is the one place that still looks it up
		encode(SCRATCH.get(), flat, offsets, n);
	}

	/**
	 * Re-encodes the first {@code n} entries of the flat buffer (entry {@code i} is {@code flat[offsets[i] ..
	 * offsets[i + 1])}, raw UTF-8 key bytes) into a fresh trimmed blob + restart index, replacing this column's
	 * state. Called directly by every hot slot mutator ({@link #insertKeyAt} / {@link #removeKeyAt} /
	 * {@link #copyRangeTo}) with their own flat/offsets scratch pair, and by the cold
	 * {@link #encode(byte[][], int)} adapter after it flattens a {@code byte[][]}. The same pass also determines
	 * whether the re-encoded corpus is BMP-only, feeding {@link #bmpSafe} via {@link #finishEncode}.
	 *
	 * @param scratch the calling thread's scratch — the caller already holds it, so it is threaded in rather than
	 *                looked up a second time (the thread-local lookup here used to duplicate the mutator's own)
	 * @param flat    the source key bytes, concatenated (at least {@code offsets[n]} bytes)
	 * @param offsets the entry boundary table (at least {@code n + 1} entries)
	 * @param n       the number of entries to encode
	 */
	private void encode(@Nonnull DecodeScratch scratch, @Nonnull byte[] flat, @Nonnull int[] offsets, int n) {
		if (n == 0) {
			resetToEmpty();
			return;
		}
		final int[] restarts = newRestartTable(n);
		byte[] buf = acquireEncodeBuf(scratch, n);
		int len = 0;
		int prevStart = 0;
		int prevLen = 0;
		// every suffix byte is a suffix byte of exactly one entry (a shared prefix traces back to the restart entry
		// that first wrote it as its own suffix), so this single scan covers the whole corpus once - no separate pass
		boolean bmpSafe = true;
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
			if (bmpSafe) {
				bmpSafe = isBmpSafe(flat, start + shared, suffixLen);
			}
			len += suffixLen;
			prevStart = start;
			prevLen = keyLen;
		}
		finishEncode(scratch, buf, len, restarts, n, bmpSafe);
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
		this.bmpSafe = true;
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
	 * @param bmpSafe  whether every suffix byte scanned during this encode was BMP-safe (see {@link #bmpSafe})
	 */
	private void finishEncode(
		@Nonnull DecodeScratch scratch, @Nonnull byte[] buf, int len, @Nonnull int[] restarts, int n, boolean bmpSafe
	) {
		scratch.encodeBuf = buf;
		this.bmpSafe = bmpSafe;
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
	 * Returns the length of the common leading byte run of two ranges within the same array, used by
	 * {@link #encode(DecodeScratch, byte[], int[], int)}.
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
	 * Returns whether {@code arr[start, start + len)} contains no supplementary-plane UTF-8 lead byte — see the class
	 * javadoc "BMP-safe byte-compare fast path" section for why this single threshold is exact. Used for the
	 * CORPUS side of the fast-path gate ({@link #encode} scans the already-stored suffix bytes it is about to
	 * write): every corpus entry reaches {@code encode} only via bytes that were already produced by
	 * {@link String#getBytes(java.nio.charset.Charset)} at {@link #insertKeyAt} time (or decoded back from such
	 * bytes), so this byte-based scan and a char-based scan of the original {@link String} always agree here — there
	 * is no separate original {@link String} left to consult once a key is part of the corpus.
	 *
	 * @param arr   the backing array
	 * @param start the range's start offset
	 * @param len   the range's length
	 * @return {@code true} if every byte in the range is {@code < 0xF0}
	 */
	private static boolean isBmpSafe(@Nonnull byte[] arr, int start, int len) {
		for (int i = start; i < start + len; i++) {
			if ((arr[i] & 0xFF) >= SUPPLEMENTARY_LEAD_BYTE) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Returns whether {@code s} contains no UTF-16 surrogate code unit, paired or lone. Used for the PROBE side of
	 * the fast-path gate in {@link #findKeyPosition}, where the original {@link String} is still available and
	 * must be consulted directly rather than via {@link String#getBytes(java.nio.charset.Charset)}: a lone
	 * (unpaired) surrogate is malformed input for UTF-8 encoding, and {@link String#getBytes(java.nio.charset.Charset)}
	 * silently replaces it with the byte {@code 0x3F} (the ASCII {@code '?'}), which is {@code < 0xF0} and would
	 * therefore incorrectly pass the byte-based {@link #isBmpSafe(byte[], int, int)} check — even though
	 * {@link String#compareTo} (the always-correct fallback this fast path must agree with) still compares the
	 * surrogate at its true code-unit value ({@code 0xD800-0xDFFF}), not at the replacement byte's value. Scanning
	 * chars directly also means {@link String#getBytes(java.nio.charset.Charset)} is only called once the probe is
	 * already confirmed surrogate-free, instead of encoding first and rescanning the result.
	 *
	 * @param s the probe string
	 * @return {@code true} if every UTF-16 code unit in {@code s} is BMP (no surrogate, paired or lone)
	 */
	private static boolean isBmpSafe(@Nonnull String s) {
		final int len = s.length();
		for (int i = 0; i < len; i++) {
			if (Character.isSurrogate(s.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Unsigned byte-lexicographic comparison of {@code a[0, aLen)} vs {@code b[0, bLen)} — equals
	 * {@link String#compareTo} order for two BMP-only UTF-8 encoded operands (see the class javadoc). Used by
	 * {@link #findKeyPosition}'s fast path only.
	 *
	 * @param a    the first (candidate) range, from offset 0
	 * @param aLen the first range's length
	 * @param b    the second (probe) range, from offset 0
	 * @param bLen the second range's length
	 * @return negative / zero / positive matching {@link String#compareTo}'s contract
	 */
	private static int compareUnsignedBytes(@Nonnull byte[] a, int aLen, @Nonnull byte[] b, int bLen) {
		// the JDK intrinsic matches this method's contract exactly: on a mismatch it returns
		// Byte.compareUnsigned of the differing pair, and when one range is a prefix of the other it returns the
		// difference of the range lengths - only the sign is consumed by findKeyPosition either way
		return Arrays.compareUnsigned(a, 0, aLen, b, 0, bLen);
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
