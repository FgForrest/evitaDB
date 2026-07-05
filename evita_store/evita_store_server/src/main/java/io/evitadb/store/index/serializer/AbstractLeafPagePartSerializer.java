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

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import javax.annotation.Nonnull;

/**
 * Shared base for every granular leaf-page Kryo {@link Serializer}. A leaf page — of any paged index family — is framed
 * identically: the `(streamId, pageSequence)` identifying pair leads the record, written as two positive var-ints. That
 * pair fully determines the storage-part primary key (`pack(streamId, pageSequence)`), so the key is recomputed on read
 * rather than stored. Only the trailing payload columns differ per family.
 *
 * This base owns that leading frame in one authoritative place; concrete serializers implement the two identity
 * accessors and the two payload hooks. Keeping the frame here means the "key derived from the identifying pair, never
 * stored" contract cannot silently drift between the ten leaf-page serializers that share it.
 *
 * @param <T> the concrete leaf-page storage-part type
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public abstract class AbstractLeafPagePartSerializer<T> extends Serializer<T> {

	@Override
	public final void write(Kryo kryo, Output output, T page) {
		output.writeVarInt(streamId(page), true);
		output.writeVarInt(pageSequence(page), true);
		writePayload(kryo, output, page);
	}

	@Override
	public final T read(Kryo kryo, Input input, Class<? extends T> type) {
		final int streamId = input.readVarInt(true);
		final int pageSequence = input.readVarInt(true);
		// the key is derived from the identifying pair, never stored
		return readPayload(kryo, input, streamId, pageSequence);
	}

	/**
	 * @param page the live page being written
	 * @return the page's stream id (the paged-stream discriminator half of its identity)
	 */
	protected abstract int streamId(@Nonnull T page);

	/**
	 * @param page the live page being written
	 * @return the page's sequence within its stream (the second half of its identity)
	 */
	protected abstract int pageSequence(@Nonnull T page);

	/**
	 * Writes the page's payload columns, positioned immediately after the `(streamId, pageSequence)` frame this base has
	 * already written.
	 *
	 * @param kryo   the Kryo instance
	 * @param output the target output
	 * @param page   the live page being written
	 */
	protected abstract void writePayload(@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull T page);

	/**
	 * Reads the page's payload columns (positioned immediately after the frame this base has already consumed) and
	 * rehydrates the concrete page. Implementations recompute the unique part id from `(streamId, pageSequence)` rather
	 * than expect it on the wire.
	 *
	 * @param kryo         the Kryo instance
	 * @param input        the source input
	 * @param streamId     the stream id read from the frame
	 * @param pageSequence the page sequence read from the frame
	 * @return the rehydrated leaf page
	 */
	@Nonnull
	protected abstract T readPayload(@Nonnull Kryo kryo, @Nonnull Input input, int streamId, int pageSequence);

}
