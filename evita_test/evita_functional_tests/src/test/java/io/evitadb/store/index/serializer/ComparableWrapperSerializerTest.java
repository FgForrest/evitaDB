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
import io.evitadb.dataType.ComparableCurrency;
import io.evitadb.dataType.ComparableLocale;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.Currency;
import java.util.Locale;

import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins that the comparable wrappers an index keys a `Currency` / `Locale` value by survive the class-tagged write
 * a leaf page uses for its values - the round trip a paged index of such an attribute makes on every reload.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Comparable wrapper serializers")
@Tag(STORAGE)
@Tag(SERIALIZATION)
class ComparableWrapperSerializerTest {

	@Test
	@DisplayName("Should read back a ComparableCurrency and a ComparableLocale written with their class")
	void shouldRoundTripComparableWrappers() {
		final Kryo kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(new ReadWriteKeyCompressor(Collections.emptyMap())));
		final ComparableCurrency currency = new ComparableCurrency(Currency.getInstance("CZK"));
		final ComparableLocale locale = new ComparableLocale(Locale.forLanguageTag("cs-CZ"));

		assertEquals(currency, roundTrip(kryo, currency));
		assertEquals(locale, roundTrip(kryo, locale));
	}

	/**
	 * Writes `value` with its class, as a leaf page does, and reads it back.
	 */
	@Nonnull
	private static Object roundTrip(@Nonnull Kryo kryo, @Nonnull Object value) {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (Output output = new Output(bytes)) {
			kryo.writeClassAndObject(output, value);
		}
		try (Input input = new Input(bytes.toByteArray())) {
			return kryo.readClassAndObject(input);
		}
	}

}
