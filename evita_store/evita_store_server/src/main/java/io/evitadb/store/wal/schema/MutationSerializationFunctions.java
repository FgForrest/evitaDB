/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.store.wal.schema;


import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.analysis.FulltextAnalysisPhases;
import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent;
import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent.FulltextResourceKind;
import io.evitadb.api.requestResponse.schema.analysis.SynonymGroup;
import io.evitadb.api.requestResponse.schema.analysis.SynonymList;
import io.evitadb.api.requestResponse.schema.analysis.WordList;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeFilterAccelerators;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexedComponents;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Infrastructural interface allowing to share common method implementation among multiple serializers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface MutationSerializationFunctions {

	/**
	 * Writes ordered analysis steps inline, without registering {@link AnalysisStep} with Kryo. Each step contains
	 * its name and named parameters; parameter names are sorted so map iteration order cannot affect the bytes.
	 *
	 * @param kryo the Kryo instance used to serialize parameter values with their runtime types
	 * @param output the destination for the step count and step descriptions
	 * @param steps the ordered step descriptions, possibly empty
	 */
	static void writeAnalysisSteps(@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull List<AnalysisStep> steps) {
		output.writeVarInt(steps.size(), true);
		for (AnalysisStep step : steps) {
			output.writeString(step.name());
			final Map<String, Serializable> parameters = step.parameters();
			final String[] parameterNames = parameters.keySet().toArray(String[]::new);
			Arrays.sort(parameterNames);
			output.writeVarInt(parameterNames.length, true);
			for (String parameterName : parameterNames) {
				output.writeString(parameterName);
				kryo.writeClassAndObject(output, parameters.get(parameterName));
			}
		}
	}

	/**
	 * Reads ordered analysis steps written by {@link #writeAnalysisSteps(Kryo, Output, List)}.
	 *
	 * @param kryo the Kryo instance used to deserialize parameter values with their runtime types
	 * @param input the source of the step count and step descriptions
	 * @return the ordered step descriptions, empty when the stored count is zero
	 */
	@Nonnull
	static List<AnalysisStep> readAnalysisSteps(@Nonnull Kryo kryo, @Nonnull Input input) {
		final int count = input.readVarInt(true);
		final List<AnalysisStep> steps = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			final String name = input.readString();
			final int parameterCount = input.readVarInt(true);
			final Map<String, Serializable> parameters = CollectionUtils.createLinkedHashMap(parameterCount);
			for (int j = 0; j < parameterCount; j++) {
				final String parameterName = input.readString();
				parameters.put(parameterName, (Serializable) kryo.readClassAndObject(input));
			}
			steps.add(AnalysisStep.of(name, parameters));
		}
		return steps;
	}

	/**
	 * Writes ordered analysis steps that may be absent - an undefined analyzer phase - as a presence flag followed by
	 * {@link #writeAnalysisSteps(Kryo, Output, List)}.
	 *
	 * @param kryo   the Kryo instance used to serialize parameter values with their runtime types
	 * @param output the destination
	 * @param steps  the ordered step descriptions, or `null` when absent
	 */
	static void writeNullableAnalysisSteps(
		@Nonnull Kryo kryo,
		@Nonnull Output output,
		@Nullable List<AnalysisStep> steps
	) {
		output.writeBoolean(steps != null);
		if (steps != null) {
			writeAnalysisSteps(kryo, output, steps);
		}
	}

	/**
	 * Writes the fulltext field settings a searchable or create mutation carries: the analyzer name and the length
	 * pivot, each `null` when the mutation leaves it to the current or the default value.
	 *
	 * @param output      the destination
	 * @param analyzer    the name of the analyzer, or `null`
	 * @param lengthPivot the length pivot, or `null`
	 */
	static void writeFulltextFieldSettings(
		@Nonnull Output output,
		@Nullable String analyzer,
		@Nullable Integer lengthPivot
	) {
		output.writeString(analyzer);
		output.writeBoolean(lengthPivot != null);
		if (lengthPivot != null) {
			output.writeVarInt(lengthPivot, false);
		}
	}

	/**
	 * Reads the length pivot written by {@link #writeFulltextFieldSettings(Output, String, Integer)}, after the
	 * analyzer name, which {@link Input#readString()} reads.
	 *
	 * @param input the source
	 * @return the length pivot, or `null` when the mutation carries none
	 */
	@Nullable
	static Integer readNullableLengthPivot(@Nonnull Input input) {
		return input.readBoolean() ? input.readVarInt(false) : null;
	}

	/**
	 * Reads ordered analysis steps written by {@link #writeNullableAnalysisSteps(Kryo, Output, List)}.
	 *
	 * @param kryo  the Kryo instance used to deserialize parameter values with their runtime types
	 * @param input the source
	 * @return the ordered step descriptions, or `null` when absent
	 */
	@Nullable
	static List<AnalysisStep> readNullableAnalysisSteps(@Nonnull Kryo kryo, @Nonnull Input input) {
		return input.readBoolean() ? readAnalysisSteps(kryo, input) : null;
	}

	/**
	 * Writes the definition of a fulltext analyzer: the analyzer it is based on, its all-languages phases and its
	 * per-language overrides in the order of the map.
	 *
	 * @param kryo         the Kryo instance used to serialize step parameter values
	 * @param output       the destination
	 * @param basedOn      the name of the analyzer it is based on, or `null`
	 * @param allLanguages the phases defined for every language
	 * @param languages    the per-language overrides keyed by ISO 639 language code
	 */
	static void writeFulltextAnalyzerDefinition(
		@Nonnull Kryo kryo,
		@Nonnull Output output,
		@Nullable String basedOn,
		@Nonnull FulltextAnalysisPhases allLanguages,
		@Nonnull Map<String, FulltextAnalysisPhases> languages
	) {
		output.writeString(basedOn);
		writeFulltextAnalysisPhases(kryo, output, allLanguages);
		output.writeVarInt(languages.size(), true);
		for (Map.Entry<String, FulltextAnalysisPhases> entry : languages.entrySet()) {
			output.writeString(entry.getKey());
			writeFulltextAnalysisPhases(kryo, output, entry.getValue());
		}
	}

	/**
	 * Reads the name of the analyzer a fulltext analyzer is based on, the first part written by
	 * {@link #writeFulltextAnalyzerDefinition(Kryo, Output, String, FulltextAnalysisPhases, Map)}.
	 *
	 * @param input the source
	 * @return the name of the analyzer it is based on, or `null`
	 */
	@Nullable
	static String readFulltextAnalyzerBasedOn(@Nonnull Input input) {
		return input.readString();
	}

	/**
	 * Writes the two phases of a fulltext analyzer definition, each possibly undefined.
	 *
	 * @param kryo   the Kryo instance used to serialize step parameter values
	 * @param output the destination
	 * @param phases the phases
	 */
	static void writeFulltextAnalysisPhases(
		@Nonnull Kryo kryo,
		@Nonnull Output output,
		@Nonnull FulltextAnalysisPhases phases
	) {
		writeNullableAnalysisSteps(kryo, output, phases.text());
		writeNullableAnalysisSteps(kryo, output, phases.words());
	}

	/**
	 * Reads phases written by {@link #writeFulltextAnalysisPhases(Kryo, Output, FulltextAnalysisPhases)}.
	 *
	 * @param kryo  the Kryo instance used to deserialize step parameter values
	 * @param input the source
	 * @return the phases
	 */
	@Nonnull
	static FulltextAnalysisPhases readFulltextAnalysisPhases(@Nonnull Kryo kryo, @Nonnull Input input) {
		final List<AnalysisStep> text = readNullableAnalysisSteps(kryo, input);
		final List<AnalysisStep> words = readNullableAnalysisSteps(kryo, input);
		return new FulltextAnalysisPhases(text, words);
	}

	/**
	 * Reads the per-language overrides of a fulltext analyzer, the last part written by
	 * {@link #writeFulltextAnalyzerDefinition(Kryo, Output, String, FulltextAnalysisPhases, Map)}.
	 *
	 * @param kryo  the Kryo instance used to deserialize step parameter values
	 * @param input the source
	 * @return the overrides keyed by ISO 639 language code, in the stored order
	 */
	@Nonnull
	static Map<String, FulltextAnalysisPhases> readFulltextAnalyzerLanguages(@Nonnull Kryo kryo, @Nonnull Input input) {
		final int count = input.readVarInt(true);
		final Map<String, FulltextAnalysisPhases> languages = CollectionUtils.createLinkedHashMap(count);
		for (int i = 0; i < count; i++) {
			final String language = input.readString();
			languages.put(language, readFulltextAnalysisPhases(kryo, input));
		}
		return languages;
	}

	/**
	 * Writes the content of a fulltext resource: its kind by name, then its words.
	 *
	 * @param output  the destination
	 * @param content the content
	 */
	static void writeFulltextResourceContent(@Nonnull Output output, @Nonnull FulltextResourceContent content) {
		output.writeString(content.kind().name());
		if (content instanceof WordList wordList) {
			writeStrings(output, wordList.words());
		} else if (content instanceof SynonymList synonymList) {
			output.writeVarInt(synonymList.groups().size(), true);
			for (SynonymGroup group : synonymList.groups()) {
				writeStrings(output, group.words());
			}
		} else {
			throw new GenericEvitaInternalError("Unsupported fulltext resource content: " + content.getClass());
		}
	}

	/**
	 * Reads content written by {@link #writeFulltextResourceContent(Output, FulltextResourceContent)}.
	 *
	 * @param input the source
	 * @return the content
	 */
	@Nonnull
	static FulltextResourceContent readFulltextResourceContent(@Nonnull Input input) {
		final FulltextResourceKind kind = FulltextResourceKind.valueOf(input.readString());
		return switch (kind) {
			case WORD_LIST -> new WordList(readStrings(input));
			case SYNONYM_LIST -> {
				final int count = input.readVarInt(true);
				final List<SynonymGroup> groups = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					groups.add(new SynonymGroup(readStrings(input)));
				}
				yield new SynonymList(groups);
			}
		};
	}

	/**
	 * Writes a list of strings as a count followed by the strings.
	 *
	 * @param output  the destination
	 * @param strings the strings
	 */
	private static void writeStrings(@Nonnull Output output, @Nonnull List<String> strings) {
		output.writeVarInt(strings.size(), true);
		for (String string : strings) {
			output.writeString(string);
		}
	}

	/**
	 * Reads strings written by {@link #writeStrings(Output, List)}.
	 *
	 * @param input the source
	 * @return the strings in the stored order
	 */
	@Nonnull
	private static List<String> readStrings(@Nonnull Input input) {
		final int count = input.readVarInt(true);
		final List<String> strings = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			strings.add(input.readString());
		}
		return strings;
	}

	/**
	 * Serializes an array of Scope objects to the given Kryo output.
	 *
	 * @param kryo   the Kryo instance to use for serialization
	 * @param output the Output instance to write to
	 * @param scopes the array of Scope objects to serialize
	 */
	default void writeScopeArray(@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull Scope[] scopes) {
		output.writeVarInt(scopes.length, true);
		for (Scope scope : scopes) {
			kryo.writeObject(output, scope);
		}
	}

	/**
	 * Reads an array of Scope objects from the given Kryo input.
	 *
	 * @param kryo  the Kryo instance to use for deserialization
	 * @param input the Input instance to read from
	 * @return the array of Scope objects that were read from the input
	 */
	@Nonnull
	default Scope[] readScopeArray(@Nonnull Kryo kryo, @Nonnull Input input) {
		int size = input.readVarInt(true);
		Scope[] scopes = new Scope[size];
		for (int i = 0; i < size; i++) {
			scopes[i] = kryo.readObject(input, Scope.class);
		}
		return scopes;
	}

	/**
	 * Serializes an optional array of {@link ScopedAttributeFilterAccelerators} to the given Kryo output. A leading
	 * boolean marks presence, so a mutation that declares no accelerator at all costs a single byte and - more
	 * importantly - lets the matching reader know whether the section is there before it starts consuming it.
	 *
	 * The field is optional on the wire because it was added after release 2026.2; `null` and an empty array mean the
	 * same thing to every mutation that carries it (no acceleration anywhere), so both are written as *absent*.
	 *
	 * @param kryo         the Kryo instance to use for serialization
	 * @param output       the Output instance to write to
	 * @param accelerators the carriers to serialize, may be null or empty
	 */
	default void writeScopedAcceleratorsArray(
		@Nonnull Kryo kryo,
		@Nonnull Output output,
		@Nullable ScopedAttributeFilterAccelerators[] accelerators
	) {
		if (accelerators == null || accelerators.length == 0) {
			output.writeBoolean(false);
			return;
		}
		output.writeBoolean(true);
		output.writeVarInt(accelerators.length, true);
		for (ScopedAttributeFilterAccelerators entry : accelerators) {
			kryo.writeObject(output, entry.scope());
			final AttributeFilterAccelerator[] scopeAccelerators = entry.accelerators();
			output.writeVarInt(scopeAccelerators.length, true);
			for (AttributeFilterAccelerator accelerator : scopeAccelerators) {
				kryo.writeObject(output, accelerator);
			}
		}
	}

	/**
	 * Reads an optional array of {@link ScopedAttributeFilterAccelerators} previously written by
	 * {@link #writeScopedAcceleratorsArray(Kryo, Output, ScopedAttributeFilterAccelerators[])}.
	 *
	 * @param kryo  the Kryo instance to use for deserialization
	 * @param input the Input instance to read from
	 * @return the carriers that were read, or null when the record declares none
	 */
	@Nullable
	default ScopedAttributeFilterAccelerators[] readScopedAcceleratorsArray(@Nonnull Kryo kryo, @Nonnull Input input) {
		if (!input.readBoolean()) {
			return null;
		}
		final int size = input.readVarInt(true);
		final ScopedAttributeFilterAccelerators[] accelerators = new ScopedAttributeFilterAccelerators[size];
		for (int i = 0; i < size; i++) {
			final Scope scope = kryo.readObject(input, Scope.class);
			final int acceleratorCount = input.readVarInt(true);
			final AttributeFilterAccelerator[] scopeAccelerators = new AttributeFilterAccelerator[acceleratorCount];
			for (int j = 0; j < acceleratorCount; j++) {
				scopeAccelerators[j] = kryo.readObject(input, AttributeFilterAccelerator.class);
			}
			accelerators[i] = new ScopedAttributeFilterAccelerators(scope, scopeAccelerators);
		}
		return accelerators;
	}

	/**
	 * Serializes an array of ScopedReferenceIndexType objects to the given Kryo output.
	 *
	 * @param kryo                    the Kryo instance to use for serialization
	 * @param output                  the Output instance to write to
	 * @param scopedReferenceIndexTypes the array of ScopedReferenceIndexType objects to serialize
	 */
	default void writeScopedReferenceIndexTypeArray(@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull ScopedReferenceIndexType[] scopedReferenceIndexTypes) {
		output.writeVarInt(scopedReferenceIndexTypes.length, true);
		for (ScopedReferenceIndexType scopedReferenceIndexType : scopedReferenceIndexTypes) {
			kryo.writeObject(output, scopedReferenceIndexType.scope());
			kryo.writeObject(output, scopedReferenceIndexType.indexType());
		}
	}

	/**
	 * Reads an array of ScopedReferenceIndexType objects from the given Kryo input.
	 *
	 * @param kryo  the Kryo instance to use for deserialization
	 * @param input the Input instance to read from
	 * @return the array of ScopedReferenceIndexType objects that were read from the input
	 */
	@Nonnull
	default ScopedReferenceIndexType[] readScopedReferenceIndexTypeArray(@Nonnull Kryo kryo, @Nonnull Input input) {
		int size = input.readVarInt(true);
		ScopedReferenceIndexType[] scopedReferenceIndexTypes = new ScopedReferenceIndexType[size];
		for (int i = 0; i < size; i++) {
			scopedReferenceIndexTypes[i] = new ScopedReferenceIndexType(
				kryo.readObject(input, Scope.class),
				kryo.readObject(input, ReferenceIndexType.class)
			);
		}
		return scopedReferenceIndexTypes;
	}

	/**
	 * Serializes an array of ScopedReferenceIndexedComponents objects to the given Kryo output.
	 *
	 * @param kryo                            the Kryo instance to use for serialization
	 * @param output                          the Output instance to write to
	 * @param scopedReferenceIndexedComponents the array of ScopedReferenceIndexedComponents objects to serialize
	 */
	default void writeScopedReferenceIndexedComponentsArray(
		@Nonnull Kryo kryo,
		@Nonnull Output output,
		@Nonnull ScopedReferenceIndexedComponents[] scopedReferenceIndexedComponents
	) {
		output.writeVarInt(scopedReferenceIndexedComponents.length, true);
		for (ScopedReferenceIndexedComponents entry : scopedReferenceIndexedComponents) {
			kryo.writeObject(output, entry.scope());
			output.writeVarInt(entry.indexedComponents().length, true);
			for (ReferenceIndexedComponents component : entry.indexedComponents()) {
				kryo.writeObject(output, component);
			}
		}
	}

	/**
	 * Reads an array of ScopedReferenceIndexedComponents objects from the given Kryo input.
	 *
	 * @param kryo  the Kryo instance to use for deserialization
	 * @param input the Input instance to read from
	 * @return the array of ScopedReferenceIndexedComponents objects that were read from the input
	 */
	@Nonnull
	default ScopedReferenceIndexedComponents[] readScopedReferenceIndexedComponentsArray(
		@Nonnull Kryo kryo,
		@Nonnull Input input
	) {
		final int size = input.readVarInt(true);
		final ScopedReferenceIndexedComponents[] result = new ScopedReferenceIndexedComponents[size];
		for (int i = 0; i < size; i++) {
			final Scope scope = kryo.readObject(input, Scope.class);
			final int componentCount = input.readVarInt(true);
			final ReferenceIndexedComponents[] components = new ReferenceIndexedComponents[componentCount];
			for (int j = 0; j < componentCount; j++) {
				components[j] = kryo.readObject(input, ReferenceIndexedComponents.class);
			}
			result[i] = new ScopedReferenceIndexedComponents(scope, components);
		}
		return result;
	}

}
