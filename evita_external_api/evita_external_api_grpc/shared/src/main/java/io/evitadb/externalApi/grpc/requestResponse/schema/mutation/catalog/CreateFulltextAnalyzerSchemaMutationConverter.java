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


package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog;

import com.google.protobuf.StringValue;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateFulltextAnalyzerSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcCreateFulltextAnalyzerSchemaMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.FulltextSchemaConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts the mutation that declares a catalog fulltext analyzer between its Java and gRPC forms. The phases
 * travel as declared, before inheritance is resolved: an undefined phase is an absent wrapper, a phase with no
 * steps an empty one.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CreateFulltextAnalyzerSchemaMutationConverter
	implements SchemaMutationConverter<
		CreateFulltextAnalyzerSchemaMutation, GrpcCreateFulltextAnalyzerSchemaMutation> {
	/** Shared stateless converter. */
	@Nonnull
	public static final CreateFulltextAnalyzerSchemaMutationConverter INSTANCE =
		new CreateFulltextAnalyzerSchemaMutationConverter();

	/**
	 * Reads the complete wire declaration, retaining the author's sample order.
	 *
	 * @param mutation the wire mutation, with absent samples represented by an empty list
	 * @return the Java mutation with its own immutable author samples
	 */
	@Nonnull
	@Override
	public CreateFulltextAnalyzerSchemaMutation convert(@Nonnull GrpcCreateFulltextAnalyzerSchemaMutation mutation) {
		return new CreateFulltextAnalyzerSchemaMutation(mutation.getName(),
			mutation.hasBasedOn() ? mutation.getBasedOn().getValue() : null,
			FulltextSchemaConverter.toPhases(mutation.getAllLanguages()),
			mutation.getLanguagesMap().entrySet().stream().collect(Collectors.toMap(
				Map.Entry::getKey, entry -> FulltextSchemaConverter.toPhases(entry.getValue()))),
			mutation.getSamplesList());
	}

	/**
	 * Writes all declared analyzer members, including author samples, without resolving inheritance.
	 *
	 * @param mutation the Java mutation to send or capture
	 * @return the complete wire mutation
	 */
	@Nonnull
	@Override
	public GrpcCreateFulltextAnalyzerSchemaMutation convert(@Nonnull CreateFulltextAnalyzerSchemaMutation mutation) {
		final GrpcCreateFulltextAnalyzerSchemaMutation.Builder builder =
			GrpcCreateFulltextAnalyzerSchemaMutation.newBuilder()
			.setName(mutation.getName())
			.addAllSamples(mutation.getSamples())
			.setAllLanguages(FulltextSchemaConverter.toGrpcPhases(mutation.getAllLanguages()))
			.putAllLanguages(mutation.getLanguages().entrySet().stream().collect(Collectors.toMap(
				Map.Entry::getKey, entry -> FulltextSchemaConverter.toGrpcPhases(entry.getValue()))));
		if (mutation.getBasedOn() != null) {
			builder.setBasedOn(StringValue.of(mutation.getBasedOn()));
		}
		return builder.build();
	}
}
