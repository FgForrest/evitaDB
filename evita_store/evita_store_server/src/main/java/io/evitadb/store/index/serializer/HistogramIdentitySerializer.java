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
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractHistogramStoragePart;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Locale;

/**
 * Shared read/write of the identity header both histogram serializers open with — the owning entity index primary key,
 * the storage part primary key and the (histogramName, locale) pair. This is exactly the identity carried by
 * {@link AbstractHistogramStoragePart}, from which both the {@code HistogramIndexStoragePart} root and its
 * {@code HistogramCardinalityStoragePart} sibling descend; the serializer-side header mirrors that part-side base so the
 * two siblings stay byte-identical over this region by construction.
 *
 * The value type that each serializer writes right after this header is intentionally NOT part of it: the root writes the
 * histogram's own value type while the sibling writes its cardinality index's value type — the same runtime encoding, but
 * distinct fields sourced from different accessors — so each serializer reads/writes it itself.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class HistogramIdentitySerializer {

	private HistogramIdentitySerializer() {
		throw new UnsupportedOperationException("This class is not intended to be instantiated!");
	}

	/**
	 * Writes the identity header: the entity index primary key, the storage part primary key (computed and cached on the
	 * part via {@link AbstractHistogramStoragePart#computeUniquePartIdAndSet(KeyCompressor)}), the histogram name and the
	 * optional locale.
	 *
	 * @param kryo          the kryo instance
	 * @param output        the target output
	 * @param part          the histogram part whose identity is written
	 * @param keyCompressor the key compressor used to resolve/verify the storage part primary key
	 */
	public static void write(
		@Nonnull Kryo kryo,
		@Nonnull Output output,
		@Nonnull AbstractHistogramStoragePart part,
		@Nonnull KeyCompressor keyCompressor
	) {
		output.writeInt(part.getEntityIndexPrimaryKey());
		output.writeVarLong(part.computeUniquePartIdAndSet(keyCompressor), true);
		output.writeString(part.getHistogramName());
		kryo.writeObjectOrNull(output, part.getLocale(), Locale.class);
	}

	/**
	 * Reads the identity header written by {@link #write} into a {@link HistogramIdentity}.
	 *
	 * @param kryo  the kryo instance
	 * @param input the source input
	 * @return the decoded entity index primary key, storage part primary key, histogram name and optional locale
	 */
	@Nonnull
	public static HistogramIdentity read(@Nonnull Kryo kryo, @Nonnull Input input) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final String histogramName = input.readString();
		final Locale locale = kryo.readObjectOrNull(input, Locale.class);
		return new HistogramIdentity(entityIndexPrimaryKey, uniquePartId, histogramName, locale);
	}

	/**
	 * The decoded identity header shared by both histogram parts.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param uniquePartId          the storage part primary key
	 * @param histogramName         the histogram definition name
	 * @param locale                the locale for localized histograms, or `null`
	 */
	public record HistogramIdentity(
		int entityIndexPrimaryKey,
		long uniquePartId,
		@Nonnull String histogramName,
		@Nullable Locale locale
	) {
	}

}
