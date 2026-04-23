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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexKey;

import java.util.Locale;

/**
 * Kryo {@link Serializer} for {@link HistogramIndexKey}. Writes the histogram name and optional locale.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class HistogramIndexKeySerializer extends Serializer<HistogramIndexKey> {

	@Override
	public void write(Kryo kryo, Output output, HistogramIndexKey key) {
		output.writeString(key.histogramName());
		kryo.writeObjectOrNull(output, key.locale(), Locale.class);
	}

	@Override
	public HistogramIndexKey read(Kryo kryo, Input input, Class<? extends HistogramIndexKey> type) {
		final String histogramName = input.readString();
		final Locale locale = kryo.readObjectOrNull(input, Locale.class);
		return new HistogramIndexKey(histogramName, locale);
	}

}
