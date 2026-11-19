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


package io.evitadb.index.fulltext.analysis;

import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent;
import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent.FulltextResourceKind;
import io.evitadb.api.requestResponse.schema.analysis.SynonymGroup;
import io.evitadb.api.requestResponse.schema.analysis.SynonymList;
import io.evitadb.api.requestResponse.schema.analysis.WordList;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.analysis.AnalysisStepParameterDescriptor;
import io.evitadb.spi.analysis.FulltextAnalysisContext;
import io.evitadb.spi.analysis.FulltextAnalysisStepFactory;
import io.evitadb.utils.CollectionUtils;
import net.openhft.hashing.LongHashFunction;

import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Canonical versioned encoding for fulltext fingerprints. Each field has a length-prefixed tag and value, each
 * string is encoded as a count of UTF-16 code units followed by those units in big-endian order, and each sequence
 * starts with a tagged count. This preserves even unpaired surrogates and never depends on the JVM's byte order.
 *
 * Resource parameters encode content rather than resource names. Word lists retain their raw words in list order;
 * synonym lists retain group order and word order within each group. Both kinds carry a kind tag and counts.
 * Preparation is deliberately omitted: the preceding steps already contribute to the fingerprint. Order remains
 * significant because a provider may read a resource in order; an unnecessary retirement is safer than a missed one.
 *
 * The hash of this stream is persisted with every field it describes, so the stream itself is a stored format: a
 * changed tag, field order or text form of a value changes the fingerprint of every unchanged chain and retires every
 * stored field. Such a change is made only deliberately, together with the version in the format marker.
 */
@NotThreadSafe
final class FulltextFingerprintEncoder {
	/**
	 * The canonical bytes accumulated before hashing.
	 */
	@Nonnull private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);

	/**
	 * Starts an independently versioned fingerprint stream.
	 *
	 * @param marker the fixed marker identifying the encoding and the hash's purpose
	 */
	FulltextFingerprintEncoder(@Nonnull String marker) {
		text("format", marker);
	}

	/**
	 * Adds a tagged sequence count, also used for scalar integer fields.
	 *
	 * @param tag the field's stable meaning
	 * @param count the number of following items, or the scalar integer value
	 */
	void count(@Nonnull String tag, int count) {
		text(tag, Integer.toString(count));
	}

	/**
	 * Encodes one selected index-side step, including only present index-side parameters in sorted name order.
	 *
	 * @param step the declared step and its values
	 * @param factory the validated provider belonging to the step's phase
	 * @param context the locale selecting delegates and the resources named by parameters
	 * @throws EvitaInvalidUsageException when a resource a parameter names is missing or of another kind than its
	 *                                    descriptor declares
	 * @throws GenericEvitaInternalError when the delegates form a cycle or a parameter holds a value of a type the
	 *                                   registry should have refused
	 */
	void step(
		@Nonnull AnalysisStep step,
		@Nonnull FulltextAnalysisStepFactory factory,
		@Nonnull FulltextAnalysisContext context
	) {
		text("step", step.name());
		behaviour(factory, context, CollectionUtils.createHashSet(4));
		final Map<String, Serializable> parameters = step.parameters();
		final List<String> names = indexSideParameterNames(parameters, factory);
		count("parameters", names.size());
		for (final String name : names) {
			parameter(step.name(), name, parameters.get(name), factory, context);
		}
	}

	/**
	 * Hashes the canonical byte stream using XXH3.
	 *
	 * @return the stable 64-bit identity of the encoded definition
	 */
	long hash() {
		return LongHashFunction.xx3().hashBytes(this.bytes.toByteArray());
	}

	/**
	 * Selects the parameters a step holds that its provider declares on the index side, in the order they are encoded.
	 *
	 * @param parameters the values the step holds, keyed by parameter name
	 * @param factory the provider declaring the index-side parameters
	 * @return the names of the held index-side parameters, sorted
	 */
	@Nonnull
	private static List<String> indexSideParameterNames(
		@Nonnull Map<String, Serializable> parameters,
		@Nonnull FulltextAnalysisStepFactory factory
	) {
		final Set<String> indexSide = factory.getIndexSideParameters();
		final List<String> names = new ArrayList<>(parameters.size());
		for (final String name : parameters.keySet()) {
			if (indexSide.contains(name)) {
				names.add(name);
			}
		}
		Collections.sort(names);
		return names;
	}

	/**
	 * Encodes one index-side parameter: its value, or the content of every resource it names when its descriptor
	 * declares a resource kind.
	 *
	 * @param step the name of the step holding the parameter, for diagnostics
	 * @param name the name of a parameter the step holds
	 * @param value the value the step holds for the parameter
	 * @param factory the provider describing the parameter
	 * @param context the resources the parameter may name
	 */
	private void parameter(
		@Nonnull String step,
		@Nonnull String name,
		@Nullable Serializable value,
		@Nonnull FulltextAnalysisStepFactory factory,
		@Nonnull FulltextAnalysisContext context
	) {
		text("parameter", name);
		final FulltextResourceKind resourceKind = resourceKind(factory, name);
		if (resourceKind == null) {
			value(step, name, value);
		} else {
			final String[] resources = value instanceof String resource ? new String[]{resource} : (String[]) value;
			count("resources", resources.length);
			for (final String resource : resources) {
				resource(context.getResource(resource), resourceKind, resource, step, name);
			}
		}
	}

	/**
	 * Finds the resource kind the provider declares for a parameter.
	 *
	 * @param factory the provider describing its parameters
	 * @param name the parameter's name
	 * @return the declared kind, or `null` when the parameter holds a plain value or has no descriptor
	 */
	@Nullable
	private static FulltextResourceKind resourceKind(
		@Nonnull FulltextAnalysisStepFactory factory,
		@Nonnull String name
	) {
		for (final AnalysisStepParameterDescriptor descriptor : factory.getParameters()) {
			if (descriptor.name().equals(name)) {
				return descriptor.resourceKind();
			}
		}
		return null;
	}

	/**
	 * Encodes a provider's behaviour and recursively its delegates, refusing cycles by stable provider name.
	 *
	 * @param factory the provider whose behaviour is being encoded
	 * @param context the locale used to select delegates
	 * @param ancestors provider names on the current recursion path, never iterated into the encoding
	 */
	private void behaviour(
		@Nonnull FulltextAnalysisStepFactory factory,
		@Nonnull FulltextAnalysisContext context,
		@Nonnull Set<String> ancestors
	) {
		if (!ancestors.add(factory.getName())) {
			throw new GenericEvitaInternalError("Fulltext delegate cycle at step `" + factory.getName() + "`.");
		}
		text("factory", factory.getName());
		count("version", factory.getBehaviourVersion());
		final List<FulltextAnalysisStepFactory> delegates = factory.getDelegates(context.locale());
		count("delegates", delegates.size());
		for (final FulltextAnalysisStepFactory delegate : delegates) {
			behaviour(delegate, context, ancestors);
		}
		ancestors.remove(factory.getName());
	}

	/**
	 * Encodes raw resource content, including its kind and all sequence boundaries.
	 *
	 * @param content the content found through the context's resource lookup
	 * @param expected the kind promised by the parameter descriptor
	 * @param name the resource name for diagnostics, never encoded
	 * @param step the step naming the resource
	 * @param parameter the parameter naming the resource
	 */
	private void resource(
		@Nonnull FulltextResourceContent content, @Nonnull FulltextResourceKind expected,
		@Nonnull String name, @Nonnull String step, @Nonnull String parameter
	) {
		if (content.kind() != expected) {
			throw new EvitaInvalidUsageException(
				"Fulltext resource `" + name + "` named by parameter `" + parameter + "` of step `" + step +
					"` is a " + describe(content.kind()) + ", not a " + describe(expected) + "."
			);
		}
		text("resource-kind", content.kind().name());
		switch (content) {
			case WordList list -> words(list.words());
			case SynonymList list -> {
				count("groups", list.groups().size());
				for (final SynonymGroup group : list.groups()) {
					words(group.words());
				}
			}
		}
	}

	/**
	 * Names a resource kind the way the wrong-kind refusal reads.
	 *
	 * @param kind the kind to name
	 * @return the kind in words
	 */
	@Nonnull
	private static String describe(@Nonnull FulltextResourceKind kind) {
		return switch (kind) {
			case WORD_LIST -> "word list";
			case SYNONYM_LIST -> "synonym list";
		};
	}

	/**
	 * Encodes words as held in a resource, without preparation or reordering.
	 *
	 * @param words the words in their declared order
	 */
	private void words(@Nonnull List<String> words) {
		count("words", words.size());
		for (final String word : words) {
			text("word", word);
		}
	}

	/**
	 * Encodes a supported scalar or its one-dimensional array with explicit type and element boundaries. The `type`
	 * field holds the runtime class name, so `int[]` and `Integer[]` holding the same numbers encode differently. The
	 * discovery whitelist has explicit lossless forms, including full ISO temporal precision and offsets.
	 * `BigDecimal#toString` preserves scale: a needless retirement for `1.0` versus `1.00` is acceptable.
	 *
	 * @param step the step name used to report an unsupported value
	 * @param parameter the parameter name used to report an unsupported value
	 * @param value the scalar or array to encode; `null` is an internal error here, because shared validation refuses
	 *              a `null` value and a `null` array element before any encoding
	 */
	private void value(@Nonnull String step, @Nonnull String parameter, @Nullable Object value) {
		if (value == null) {
			throw new GenericEvitaInternalError(
				"Unsupported fingerprint value for step `" + step + "`, parameter `" + parameter + "`: " +
					"null."
			);
		}
		text("type", value.getClass().getName());
		if (value.getClass().isArray()) {
			final int length = Array.getLength(value);
			count("elements", length);
			for (int i = 0; i < length; i++) {
				value(step, parameter, Array.get(value, i));
			}
		} else if (value instanceof Locale locale) {
			locale(locale);
		} else {
			final String formatted = switch (value) {
				case String string -> string;
				case Character character -> character.toString();
				case Boolean bool -> bool.toString();
				case Byte number -> number.toString();
				case Short number -> number.toString();
				case Integer number -> number.toString();
				case Long number -> number.toString();
				case BigDecimal number -> number.toString();
				case Currency currency -> currency.getCurrencyCode();
				case UUID uuid -> uuid.toString();
				case LocalDate date -> date.toString();
				case LocalTime time -> time.toString();
				case LocalDateTime dateTime -> dateTime.toString();
				case OffsetDateTime dateTime -> dateTime.toString();
				default -> throw new GenericEvitaInternalError(
					"Unsupported fingerprint value for step `" + step + "`, parameter `" + parameter + "`: " +
						value.getClass().getName() + "."
				);
			};
			text("value", formatted);
		}
	}

	/**
	 * Encodes a locale by its language tag when the tag restores it exactly, and by its parts otherwise. A tag drops
	 * what is not well-formed - `Locale.of("en", "USA")` has the tag `en` - while `Locale#equals` and the stored schema
	 * keep those parts, so two locales differing only there must not share an encoding. A field tagged `value`
	 * carries the tag, one tagged `language` starts the parts, so the two forms never meet.
	 *
	 * @param locale the parameter value or array element to encode
	 */
	private void locale(@Nonnull Locale locale) {
		final String tag = locale.toLanguageTag();
		if (Locale.forLanguageTag(tag).equals(locale)) {
			text("value", tag);
		} else {
			text("language", locale.getLanguage());
			text("script", locale.getScript());
			text("country", locale.getCountry());
			text("variant", locale.getVariant());
			final List<Character> keys = new ArrayList<>(locale.getExtensionKeys());
			Collections.sort(keys);
			count("extensions", keys.size());
			for (final Character key : keys) {
				text("extension", key.toString());
				text("extension-value", locale.getExtension(key));
			}
		}
	}

	/**
	 * Adds one tagged, length-prefixed text field.
	 *
	 * @param tag the stable field identifier
	 * @param value the field's deterministic text
	 */
	private void text(@Nonnull String tag, @Nonnull String value) {
		string(tag);
		string(value);
	}

	/**
	 * Writes a string with a four-byte big-endian code-unit count and two bytes per UTF-16 code unit.
	 *
	 * @param value the exact Java string, including any unpaired surrogate code units
	 */
	private void string(@Nonnull String value) {
		final int length = value.length();
		this.bytes.write(length >>> 24);
		this.bytes.write(length >>> 16);
		this.bytes.write(length >>> 8);
		this.bytes.write(length);
		for (int i = 0; i < length; i++) {
			final char unit = value.charAt(i);
			this.bytes.write(unit >>> 8);
			this.bytes.write(unit);
		}
	}
}
