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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramLeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramLeafStreamKey.StreamKind;

import java.util.Locale;

/**
 * This {@link Serializer} implementation reads/writes {@link HistogramLeafStreamKey} — the per-histogram page-stream
 * identity of the granular storage layout — from/to binary format. The owning entity-index primary key, the histogram
 * name, the optional locale and the stream kind (bucket vs range) are all written; the stream kind is written by name
 * for stability against future enum reordering.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class HistogramLeafStreamKeySerializer extends Serializer<HistogramLeafStreamKey> {

	@Override
	public void write(Kryo kryo, Output output, HistogramLeafStreamKey streamKey) {
		output.writeVarInt(streamKey.getEntityIndexPrimaryKey(), false);
		output.writeString(streamKey.getHistogramName());
		kryo.writeObjectOrNull(output, streamKey.getLocale(), Locale.class);
		output.writeString(streamKey.getStreamKind().name());
	}

	@Override
	public HistogramLeafStreamKey read(Kryo kryo, Input input, Class<? extends HistogramLeafStreamKey> type) {
		final int entityIndexPrimaryKey = input.readVarInt(false);
		final String histogramName = input.readString();
		final Locale locale = kryo.readObjectOrNull(input, Locale.class);
		final StreamKind streamKind = StreamKind.valueOf(input.readString());
		return new HistogramLeafStreamKey(entityIndexPrimaryKey, histogramName, locale, streamKind);
	}

}
