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

package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;

/**
 * Shared read/write of the PAGED page-stream metadata a granular index root carries in place of its inline column(s): the
 * high-water page sequence followed by the ordered, length-prefixed list of live leaf-page sequences. The page-stream id
 * itself is NOT part of this metadata — it is recomputed at load from the sub-index identity. The list is written as one
 * `varint` length followed by one `varint` per sequence.
 *
 * Two levels of granularity are offered:
 *
 * - {@link #writeOptional}/{@link #readOptional} own the WHOLE optional block — the leading `paged` (SINGLE/PAGED)
 *   discriminator, the body when PAGED, and the not-paged sentinel ({@link #NOT_PAGED}: `paged = false`, high-water `-1`,
 *   empty live-page list) when SINGLE. This is what a root should use when its `paged` boolean is a standalone,
 *   independent trailing flag (the {@code FilterIndex} and {@code HistogramIndex} bucket + range axes and the price-super
 *   axis).
 * - {@link #writeBody}/{@link #readBody} cover only the BODY, for the roots whose discriminator is entangled with other
 *   framing and cannot be delegated: some nest it under another marker ({@code UniqueIndex} under `dataPresent`), some
 *   pair it with an inline `else` branch ({@code GlobalUniqueIndex}, {@code ReferenceTypeCardinalityIndex}). Those keep
 *   their own discriminator and not-paged mapping and delegate only the identical body here.
 *
 * Note this uses the compact per-element `varint` encoding shared by the {@code FilterIndex}, {@code HistogramIndex},
 * {@code UniqueIndex}, {@code GlobalUniqueIndex}, {@code ReferenceTypeCardinalityIndex} and price-super roots. The
 * {@code SortIndex} and {@code ChainIndex} roots write the same logical metadata with fixed-width {@code writeInts}
 * instead, so they intentionally do NOT use this helper — routing them through it would change their bytes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class PagedStreamMetadataSerializer {

	/**
	 * The not-paged sentinel returned by {@link #readOptional} for a SINGLE stream: `paged = false`, no high-water
	 * (`-1`) and an empty live leaf-page list.
	 */
	public static final PagedStreamMetadata NOT_PAGED =
		new PagedStreamMetadata(false, -1, ArrayUtils.EMPTY_INT_ARRAY);

	private PagedStreamMetadataSerializer() {
		throw new UnsupportedOperationException("This class is not intended to be instantiated!");
	}

	/**
	 * Writes the whole optional block: the standalone `paged` (SINGLE/PAGED) discriminator, followed by the metadata body
	 * ({@link #writeBody}) only when `paged`. Byte-identical to a hand-written `writeBoolean(paged); if (paged) body`.
	 *
	 * @param output                the target output
	 * @param paged                 whether the stream is PAGED (true) or SINGLE (false)
	 * @param highWaterPageSequence the highest page sequence ever allocated to the stream (ignored when not paged)
	 * @param leafPageSequences     the ordered live leaf-page sequences (ignored when not paged)
	 */
	public static void writeOptional(
		@Nonnull Output output, boolean paged, int highWaterPageSequence, @Nonnull int[] leafPageSequences
	) {
		output.writeBoolean(paged);
		if (paged) {
			writeBody(output, highWaterPageSequence, leafPageSequences);
		}
	}

	/**
	 * Reads the whole optional block written by {@link #writeOptional}: the `paged` discriminator plus the body when
	 * PAGED, returning {@link #NOT_PAGED} when the discriminator is SINGLE.
	 *
	 * @param input the source input
	 * @return the decoded metadata, or {@link #NOT_PAGED} when the stream is SINGLE
	 */
	@Nonnull
	public static PagedStreamMetadata readOptional(@Nonnull Input input) {
		if (input.readBoolean()) {
			return readBody(input);
		}
		return NOT_PAGED;
	}

	/**
	 * Writes the PAGED metadata body — the high-water page sequence plus the length-prefixed live leaf-page list —
	 * immediately after the caller has written its own `paged = true` discriminator.
	 *
	 * @param output              the target output
	 * @param highWaterPageSequence the highest page sequence ever allocated to the stream
	 * @param leafPageSequences   the ordered live leaf-page sequences
	 */
	public static void writeBody(
		@Nonnull Output output, int highWaterPageSequence, @Nonnull int[] leafPageSequences
	) {
		output.writeVarInt(highWaterPageSequence, true);
		output.writeVarInt(leafPageSequences.length, true);
		for (final int pageSequence : leafPageSequences) {
			output.writeVarInt(pageSequence, true);
		}
	}

	/**
	 * Reads the PAGED metadata body written by {@link #writeBody}, positioned immediately after the caller has consumed
	 * its own `paged = true` discriminator. The returned record's {@link PagedStreamMetadata#paged()} is always `true` —
	 * a body is only ever present for a PAGED stream.
	 *
	 * @param input the source input
	 * @return the high-water page sequence and the ordered live leaf-page list (with `paged = true`)
	 */
	@Nonnull
	public static PagedStreamMetadata readBody(@Nonnull Input input) {
		final int highWaterPageSequence = input.readVarInt(true);
		final int leafPageCount = input.readVarInt(true);
		final int[] leafPageSequences = new int[leafPageCount];
		for (int i = 0; i < leafPageCount; i++) {
			leafPageSequences[i] = input.readVarInt(true);
		}
		return new PagedStreamMetadata(true, highWaterPageSequence, leafPageSequences);
	}

	/**
	 * The decoded PAGED metadata: whether the stream is paged plus, when it is, the high-water page sequence and the
	 * ordered live leaf-page list. For a SINGLE stream this is {@link #NOT_PAGED} (high-water `-1`, empty list).
	 *
	 * @param paged                 whether the stream is PAGED (true) or SINGLE (false)
	 * @param highWaterPageSequence the highest page sequence ever allocated to the stream
	 * @param leafPageSequences     the ordered live leaf-page sequences
	 */
	public record PagedStreamMetadata(boolean paged, int highWaterPageSequence, @Nonnull int[] leafPageSequences) {
	}

}
