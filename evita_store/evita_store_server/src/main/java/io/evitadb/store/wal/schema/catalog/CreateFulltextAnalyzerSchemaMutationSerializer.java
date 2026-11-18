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


package io.evitadb.store.wal.schema.catalog;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.schema.analysis.FulltextAnalysisPhases;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateFulltextAnalyzerSchemaMutation;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

import static io.evitadb.store.wal.schema.MutationSerializationFunctions.readFulltextAnalysisPhases;
import static io.evitadb.store.wal.schema.MutationSerializationFunctions.readFulltextAnalyzerBasedOn;
import static io.evitadb.store.wal.schema.MutationSerializationFunctions.readFulltextAnalyzerLanguages;
import static io.evitadb.store.wal.schema.MutationSerializationFunctions.readFulltextAnalyzerSamples;
import static io.evitadb.store.wal.schema.MutationSerializationFunctions.writeFulltextAnalyzerDefinition;

/**
 * Serializer for {@link CreateFulltextAnalyzerSchemaMutation}.
 */
public class CreateFulltextAnalyzerSchemaMutationSerializer extends Serializer<CreateFulltextAnalyzerSchemaMutation> {

	/**
	 * Writes the complete declaration, ending with its ordered author samples.
	 *
	 * @param kryo the WAL serialization context
	 * @param output the mutation record destination
	 * @param mutation the declaration to persist
	 */
	@Override
	public void write(
		@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull CreateFulltextAnalyzerSchemaMutation mutation
	) {
		output.writeString(mutation.getName());
		writeFulltextAnalyzerDefinition(
			kryo, output, mutation.getBasedOn(), mutation.getAllLanguages(), mutation.getLanguages(),
			mutation.getSamples()
		);
	}

	/**
	 * Reads the declaration and its author samples from the current WAL format.
	 *
	 * @param kryo the WAL serialization context
	 * @param input the mutation record source
	 * @param type the registered mutation type
	 * @return the reconstructed mutation with immutable author samples
	 */
	@Nonnull
	@Override
	public CreateFulltextAnalyzerSchemaMutation read(
		@Nonnull Kryo kryo, @Nonnull Input input, @Nonnull Class<? extends CreateFulltextAnalyzerSchemaMutation> type
	) {
		final String name = input.readString();
		final String basedOn = readFulltextAnalyzerBasedOn(input);
		final FulltextAnalysisPhases allLanguages = readFulltextAnalysisPhases(kryo, input);
		final Map<String, FulltextAnalysisPhases> languages = readFulltextAnalyzerLanguages(kryo, input);
		final List<String> samples = readFulltextAnalyzerSamples(input);
		return new CreateFulltextAnalyzerSchemaMutation(name, basedOn, allLanguages, languages, samples);
	}

}
