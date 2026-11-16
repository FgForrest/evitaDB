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

import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateFulltextLanguageSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcCreateFulltextLanguageSchemaMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.AnalysisStepConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/** Converts the mutation that declares a catalog fulltext language between its Java and gRPC forms. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CreateFulltextLanguageSchemaMutationConverter
	implements SchemaMutationConverter<
		CreateFulltextLanguageSchemaMutation, GrpcCreateFulltextLanguageSchemaMutation> {
	/** Shared stateless converter. */
	@Nonnull
	public static final CreateFulltextLanguageSchemaMutationConverter INSTANCE =
		new CreateFulltextLanguageSchemaMutationConverter();

	@Nonnull
	@Override
	public CreateFulltextLanguageSchemaMutation convert(@Nonnull GrpcCreateFulltextLanguageSchemaMutation mutation) {
		return new CreateFulltextLanguageSchemaMutation(mutation.getLanguage(),
			AnalysisStepConverter.toAnalysisSteps(mutation.getWordSplittingList()).toArray(AnalysisStep[]::new));
	}

	@Nonnull
	@Override
	public GrpcCreateFulltextLanguageSchemaMutation convert(@Nonnull CreateFulltextLanguageSchemaMutation mutation) {
		final GrpcCreateFulltextLanguageSchemaMutation.Builder builder =
			GrpcCreateFulltextLanguageSchemaMutation.newBuilder()
			.setLanguage(mutation.getLanguage())
			.addAllWordSplitting(AnalysisStepConverter.toGrpcAnalysisSteps(mutation.getWordSplitting()));
		return builder.build();
	}
}
