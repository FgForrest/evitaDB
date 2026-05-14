/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.store.query.serializer.filter;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.filter.GroupHaving;
import io.evitadb.api.query.filter.HistogramHaving;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * This {@link Serializer} implementation reads/writes {@link HistogramHaving} from/to binary format.
 *
 * The wire format mirrors the constraint's logical shape:
 *
 * 1. reference name (non-null string)
 * 2. histogram name (nullable string — Kryo's `writeString` / `readString` encode null natively)
 * 3. `from` bound (nullable {@link BigDecimal} via `writeClassAndObject`)
 * 4. `to` bound (nullable {@link BigDecimal} via `writeClassAndObject`)
 * 5. group selector (nullable {@link GroupHaving} via `writeClassAndObject`)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class HistogramHavingSerializer extends Serializer<HistogramHaving> {

	/**
	 * Writes a {@link HistogramHaving} to the Kryo output stream. The reference name is written first as a plain
	 * non-null string, followed by the nullable histogram name, the two nullable `from` / `to` bounds, and finally the
	 * nullable group-selector child constraint.
	 *
	 * @param kryo   the Kryo instance coordinating class registration
	 * @param output the Kryo output stream to write to
	 * @param object the constraint to serialize
	 */
	@Override
	public void write(Kryo kryo, Output output, HistogramHaving object) {
		output.writeString(object.getReferenceName());
		output.writeString(object.getHistogramName());
		kryo.writeClassAndObject(output, object.getFrom());
		kryo.writeClassAndObject(output, object.getTo());
		kryo.writeClassAndObject(output, object.getGroupHaving());
	}

	/**
	 * Reads a {@link HistogramHaving} from the Kryo input stream, reconstructing the constraint via its public
	 * {@code (referenceName, histogramName, from, to, groupSelector)} constructor. The wire-format order must match
	 * {@link #write(Kryo, Output, HistogramHaving)} exactly.
	 *
	 * @param kryo  the Kryo instance coordinating class registration
	 * @param input the Kryo input stream to read from
	 * @param type  the concrete type being deserialized (unused — we always return {@link HistogramHaving})
	 * @return the deserialized constraint
	 */
	@Override
	public HistogramHaving read(Kryo kryo, Input input, Class<? extends HistogramHaving> type) {
		final String referenceName = input.readString();
		final String histogramName = input.readString();
		final BigDecimal from = (BigDecimal) kryo.readClassAndObject(input);
		final BigDecimal to = (BigDecimal) kryo.readClassAndObject(input);
		final GroupHaving groupSelector = (GroupHaving) kryo.readClassAndObject(input);
		return new HistogramHaving(referenceName, histogramName, from, to, groupSelector);
	}

}
