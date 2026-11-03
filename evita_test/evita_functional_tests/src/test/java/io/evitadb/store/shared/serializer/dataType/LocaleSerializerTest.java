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

package io.evitadb.store.shared.serializer.dataType;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.DefaultSerializers;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Verifies that {@link LocaleSerializer} stores every part of a {@link Locale} - script, Unicode extensions and
 * private-use subtags included - and that the bytes Kryo's own `DefaultSerializers.LocaleSerializer` wrote, which
 * is what every catalog stored so far contains, still read back as the very same locales.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Locale serializer")
@Tag(STORAGE)
@Tag(SERIALIZATION)
@Tag(DATA_TYPE)
class LocaleSerializerTest {

	/**
	 * Locales with no script and no extensions - fully described by their language, country and variant.
	 */
	private static final List<Locale> PLAIN_LOCALES = List.of(
		Locale.ROOT,
		Locale.ENGLISH,
		Locale.forLanguageTag("cs-CZ"),
		Locale.US,
		Locale.of("en", "", "POSIX"),
		Locale.of("de", "CH", "1996"),
		Locale.forLanguageTag("en-US-x-lvariant-WIN"),
		// the `Locale` constructor does not validate the variant - which is why no marker may live inside it
		Locale.of("en", "US", "a#b")
	);

	/**
	 * Locales carrying a script, extensions or a private-use part - each of them loses something in Kryo's
	 * serializer.
	 */
	private static final List<Locale> EXTENDED_LOCALES = List.of(
		Locale.forLanguageTag("zh-Hans"),
		Locale.forLanguageTag("zh-Hant"),
		Locale.forLanguageTag("zh-Hant-TW"),
		Locale.forLanguageTag("de-DE-u-co-phonebk"),
		Locale.forLanguageTag("en-US-x-private"),
		Locale.forLanguageTag("sr-Latn-RS"),
		new Locale.Builder().setLanguage("en").setScript("Latn").setRegion("US").setVariant("POSIX").build(),
		Locale.forLanguageTag("de-CH-1996-u-nu-latn-x-foo"),
		Locale.forLanguageTag("und-Latn"),
		Locale.of("ja", "JP", "JP"),
		Locale.of("th", "TH", "TH")
	);

	/**
	 * Bytes Kryo's `LocaleSerializer` writes for `en_US`: the language and the country as ASCII strings (the last
	 * character carries the end-of-string bit) and the empty variant as a one-byte string length.
	 */
	private static final byte[] EN_US_GOLDEN = new byte[]{101, -18, 85, -45, -127};

	private final Kryo kryo = KryoFactory.createKryo();

	/**
	 * Writes the locale with the passed serializer and returns the bytes.
	 *
	 * @param serializer the serializer to write with
	 * @param locale     the locale to write
	 * @return the written bytes
	 */
	@Nonnull
	private byte[] write(@Nonnull Serializer<Locale> serializer, @Nonnull Locale locale) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(64);
		try (final Output output = new Output(os, 64)) {
			serializer.write(this.kryo, output, locale);
		}
		return os.toByteArray();
	}

	/**
	 * Reads a locale from the bytes with the passed serializer.
	 *
	 * @param serializer the serializer to read with
	 * @param bytes      the bytes to read
	 * @return the read locale
	 */
	@Nonnull
	private Locale read(@Nonnull Serializer<Locale> serializer, @Nonnull byte[] bytes) {
		try (final Input input = new Input(bytes)) {
			return serializer.read(this.kryo, input, Locale.class);
		}
	}

	/**
	 * Writes the locale through the Kryo storage instance - the way every storage part writes a nested locale - and
	 * reads it back.
	 *
	 * @param locale the locale to round-trip
	 * @return the deserialized copy
	 */
	@Nonnull
	private Locale roundTripThroughKryo(@Nonnull Locale locale) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(64);
		try (final Output output = new Output(os, 64)) {
			this.kryo.writeObject(output, locale);
		}
		try (final Input input = new Input(os.toByteArray())) {
			return this.kryo.readObject(input, Locale.class);
		}
	}

	@Nested
	@DisplayName("round-trip")
	class RoundTrip {

		@Test
		@DisplayName("a plain locale reads back equal")
		void shouldRoundTripPlainLocale() {
			for (final Locale locale : PLAIN_LOCALES) {
				assertEquals(locale, roundTripThroughKryo(locale), locale.toLanguageTag());
			}
		}

		@Test
		@DisplayName("a locale with a script, extensions or a private-use part reads back equal")
		void shouldRoundTripExtendedLocale() {
			for (final Locale locale : EXTENDED_LOCALES) {
				assertEquals(locale, roundTripThroughKryo(locale), locale.toLanguageTag());
			}
		}

		@Test
		@DisplayName("two locales differing only in their script stay distinct")
		void shouldKeepLocalesDifferingInScriptDistinct() {
			final Locale simplified = roundTripThroughKryo(Locale.forLanguageTag("zh-Hans"));
			final Locale traditional = roundTripThroughKryo(Locale.forLanguageTag("zh-Hant"));

			assertEquals("Hans", simplified.getScript());
			assertEquals("Hant", traditional.getScript());
			assertNotEquals(simplified, traditional);
		}

		@Test
		@DisplayName("an array of locales reads back equal")
		void shouldRoundTripLocaleArray() {
			final Locale[] locales = new Locale[]{
				Locale.forLanguageTag("zh-Hans"), Locale.ENGLISH, Locale.forLanguageTag("de-DE-u-co-phonebk")
			};
			final ByteArrayOutputStream os = new ByteArrayOutputStream(128);
			try (final Output output = new Output(os, 128)) {
				LocaleSerializerTest.this.kryo.writeObject(output, locales);
			}
			try (final Input input = new Input(os.toByteArray())) {
				assertArrayEquals(locales, LocaleSerializerTest.this.kryo.readObject(input, Locale[].class));
			}
		}
	}

	@Nested
	@DisplayName("compatibility with stored data")
	class StoredDataCompatibility {

		@Test
		@DisplayName("bytes written by Kryo's serializer read back as the locale Kryo's serializer read")
		void shouldReadBytesWrittenByKryoSerializer() {
			final DefaultSerializers.LocaleSerializer kryoSerializer = new DefaultSerializers.LocaleSerializer();
			final LocaleSerializer serializer = new LocaleSerializer();
			for (final Locale locale : concat(PLAIN_LOCALES, EXTENDED_LOCALES)) {
				final byte[] storedBytes = write(kryoSerializer, locale);
				assertEquals(
					read(kryoSerializer, storedBytes), read(serializer, storedBytes), locale.toLanguageTag()
				);
			}
		}

		@Test
		@DisplayName("the stored layout of a plain locale reads back")
		void shouldReadGoldenBytes() {
			assertArrayEquals(EN_US_GOLDEN, write(new DefaultSerializers.LocaleSerializer(), Locale.US));
			assertEquals(Locale.US, read(new LocaleSerializer(), EN_US_GOLDEN));
		}

		@Test
		@DisplayName("a plain locale is written in exactly the bytes Kryo's serializer wrote")
		void shouldWritePlainLocaleInStoredLayout() {
			final DefaultSerializers.LocaleSerializer kryoSerializer = new DefaultSerializers.LocaleSerializer();
			final LocaleSerializer serializer = new LocaleSerializer();
			for (final Locale locale : PLAIN_LOCALES) {
				assertArrayEquals(write(kryoSerializer, locale), write(serializer, locale), locale.toLanguageTag());
			}
		}

		@Test
		@DisplayName("an extended locale starts with the null string Kryo's serializer never wrote")
		void shouldMarkExtendedLocaleWithNullString() {
			final LocaleSerializer serializer = new LocaleSerializer();
			for (final Locale locale : EXTENDED_LOCALES) {
				// 0x80 is Kryo's encoding of a `null` string; a stored language is never `null`
				assertEquals((byte) 0x80, write(serializer, locale)[0], locale.toLanguageTag());
			}
		}

		/**
		 * Concatenates two lists.
		 *
		 * @param first  the first list
		 * @param second the second list
		 * @return a list with the elements of both
		 */
		@Nonnull
		private static List<Locale> concat(@Nonnull List<Locale> first, @Nonnull List<Locale> second) {
			return Stream.concat(first.stream(), second.stream()).toList();
		}
	}
}
