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
import com.esotericsoftware.kryo.serializers.ImmutableSerializer;

import javax.annotation.Nonnull;
import java.util.Locale;

/**
 * This {@link Serializer} implementation reads/writes {@link Locale} values without losing any part of them.
 *
 * Kryo's own `DefaultSerializers.LocaleSerializer` stores only the language, the country and the variant, so a
 * script (`zh-Hans` and `zh-Hant` both come back as `zh`), Unicode extensions (`-u-...`) and private-use subtags are
 * dropped on the way to disk. This serializer keeps two forms:
 *
 * - **plain** - a locale with no script and no extensions is written exactly as Kryo's serializer wrote it: the
 *   language and the country as ASCII strings, the variant as a string. Such a locale is fully described by those
 *   three parts, and the bytes are identical to the ones already stored.
 * - **extended** - any other locale is written as a `null` string followed by its BCP 47 language tag, which carries
 *   the script, the extensions and the private-use part.
 *
 * The `null` marker is what keeps the bytes stored before this serializer existed unambiguous: Kryo's serializer
 * wrote `Locale#getLanguage()` first, which is never `null`, so a stored plain locale can never start with the `null`
 * string. A marker inside the variant would not be safe in the same way - the `Locale` constructor accepts any
 * variant text, `#` included.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class LocaleSerializer extends ImmutableSerializer<Locale> {

	@Override
	public void write(@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull Locale locale) {
		if (locale.getScript().isEmpty() && !locale.hasExtensions()) {
			// the three parts describe the locale completely - keep the byte layout of the data already stored
			output.writeAscii(locale.getLanguage());
			output.writeAscii(locale.getCountry());
			output.writeString(locale.getVariant());
		} else {
			output.writeString(null);
			output.writeString(locale.toLanguageTag());
		}
	}

	@Nonnull
	@Override
	public Locale read(@Nonnull Kryo kryo, @Nonnull Input input, @Nonnull Class<? extends Locale> type) {
		final String language = input.readString();
		if (language == null) {
			return Locale.forLanguageTag(input.readString());
		} else {
			final String country = input.readString();
			final String variant = input.readString();
			return Locale.of(language, country, variant);
		}
	}

}
