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
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.util.Collections;
import java.util.Currency;
import java.util.Locale;

import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Round-trips the comparable wrappers a sort or filter index keeps for `Locale` and `Currency` values through the
 * Kryo the index storage parts are written with, the way a storage part writes a distinct value - with its class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Comparable Locale and Currency serializers round-trip")
@Tag(STORAGE)
@Tag(SERIALIZATION)
class ComparableLocaleSerializerTest {
	private final Kryo kryo = KryoFactory.createKryo(
		SharedClassesConfigurer.INSTANCE.andThen(
			new IndexStoragePartConfigurer(new ReadWriteKeyCompressor(Collections.emptyMap()))
		)
	);

	/**
	 * Writes the value with its class and reads it straight back.
	 *
	 * @param value the value to round-trip
	 * @return the deserialized copy
	 */
	@Nonnull
	private Serializable roundTrip(@Nonnull Serializable value) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(256);
		try (final Output output = new Output(os, 256)) {
			this.kryo.writeClassAndObject(output, value);
		}
		try (final Input input = new Input(os.toByteArray())) {
			return (Serializable) this.kryo.readClassAndObject(input);
		}
	}

	@Test
	@DisplayName("a comparable locale reads back as the same locale")
	void shouldRoundTripComparableLocale() {
		for (final Locale locale : new Locale[]{Locale.ENGLISH, Locale.forLanguageTag("cs-CZ"), Locale.ROOT}) {
			assertEquals(new ComparableLocale(locale), roundTrip(new ComparableLocale(locale)));
		}
	}

	@Test
	@DisplayName("a comparable currency reads back as the same currency")
	void shouldRoundTripComparableCurrency() {
		final ComparableCurrency currency = new ComparableCurrency(Currency.getInstance("CZK"));
		assertEquals(currency, roundTrip(currency));
	}
}
