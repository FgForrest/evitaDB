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


package io.evitadb.externalApi.grpc.requestResponse.schema;

import com.google.protobuf.StringValue;
import io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract;
import io.evitadb.api.requestResponse.schema.FulltextLanguageSchemaContract;
import io.evitadb.api.requestResponse.schema.FulltextResourceSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.*;
import io.evitadb.api.requestResponse.schema.dto.FulltextAnalyzerSchema;
import io.evitadb.api.requestResponse.schema.dto.FulltextLanguageSchema;
import io.evitadb.api.requestResponse.schema.dto.FulltextResourceSchema;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.grpc.generated.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Map.Entry;
import java.util.stream.Collectors;

/** Converts fulltext declaration values shared by catalog mutations and schema reads. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class FulltextSchemaConverter {
	/**
	 * Reads undefined and explicitly empty analyzer phases distinctly.
	 *
	 * @param phases wire declaration whose missing wrappers denote inheritance
	 * @return phase lists preserving the difference between undefined and empty
	 */
	@Nonnull
	public static FulltextAnalysisPhases toPhases(@Nonnull GrpcFulltextAnalysisPhases phases) {
		return new FulltextAnalysisPhases(
			phases.hasText() ? AnalysisStepConverter.toAnalysisSteps(phases.getText().getStepsList()) : null,
			phases.hasWords() ? AnalysisStepConverter.toAnalysisSteps(phases.getWords().getStepsList()) : null
		);
	}

	/**
	 * Writes a wrapper for each defined phase, including phases with no steps.
	 *
	 * @param phases domain declaration before inheritance is resolved
	 * @return wire phases with absent wrappers only for undefined phases
	 */
	@Nonnull
	public static GrpcFulltextAnalysisPhases toGrpcPhases(@Nonnull FulltextAnalysisPhases phases) {
		final GrpcFulltextAnalysisPhases.Builder builder = GrpcFulltextAnalysisPhases.newBuilder();
		if (phases.text() != null) {
			builder.setText(GrpcAnalysisSteps.newBuilder()
				.addAllSteps(AnalysisStepConverter.toGrpcAnalysisSteps(phases.text())));
		}
		if (phases.words() != null) {
			builder.setWords(GrpcAnalysisSteps.newBuilder()
				.addAllSteps(AnalysisStepConverter.toGrpcAnalysisSteps(phases.words())));
		}
		return builder.build();
	}

	/**
	 * Reads the declared resource kind and preserves word and group order.
	 *
	 * @param content exactly one declared content alternative
	 * @return immutable word list or synonym list
	 */
	@Nonnull
	public static FulltextResourceContent toContent(@Nonnull GrpcFulltextResourceContent content) {
		return switch (content.getContentCase()) {
			case WORDLIST -> new WordList(content.getWordList().getWordsList());
			case SYNONYMLIST -> new SynonymList(content.getSynonymList().getGroupsList().stream()
				.map(group -> new SynonymGroup(group.getWordsList())).toList());
			case CONTENT_NOT_SET -> throw new EvitaInvalidUsageException("Fulltext resource content is required.");
		};
	}

	/**
	 * Writes exactly the resource content exposed by the Java schema contract.
	 *
	 * @param content catalog word list or synonym list, without resolved resources
	 * @return the corresponding content alternative with declaration order preserved
	 */
	@Nonnull
	public static GrpcFulltextResourceContent toGrpcContent(@Nonnull FulltextResourceContent content) {
		final GrpcFulltextResourceContent.Builder builder = GrpcFulltextResourceContent.newBuilder();
		if (content instanceof WordList words) {
			builder.setWordList(GrpcWordList.newBuilder().addAllWords(words.words()));
		} else if (content instanceof SynonymList synonyms) {
			builder.setSynonymList(GrpcSynonymList.newBuilder().addAllGroups(synonyms.groups().stream()
				.map(group -> GrpcSynonymGroup.newBuilder().addAllWords(group.words()).build()).toList()));
		} else {
			throw new EvitaInvalidUsageException("Unknown fulltext resource content: " + content.getClass());
		}
		return builder.build();
	}

	/**
	 * Writes a catalog language declaration with its word splitting in execution order.
	 *
	 * @param schema catalog language declaration
	 * @return the wire declaration of the language
	 */
	@Nonnull
	public static GrpcFulltextLanguageSchema toGrpcLanguage(@Nonnull FulltextLanguageSchemaContract schema) {
		return GrpcFulltextLanguageSchema.newBuilder().setLanguage(schema.getLanguage())
			.addAllWordSplitting(AnalysisStepConverter.toGrpcAnalysisSteps(schema.getWordSplitting())).build();
	}

	/**
	 * Reads a catalog language declaration, keeping the word splitting order.
	 *
	 * @param schema wire declaration of the language
	 * @return the catalog language declaration
	 */
	@Nonnull
	public static FulltextLanguageSchema toLanguage(@Nonnull GrpcFulltextLanguageSchema schema) {
		return FulltextLanguageSchema._internalBuild(schema.getLanguage(),
			AnalysisStepConverter.toAnalysisSteps(schema.getWordSplittingList()));
	}

	/**
	 * Writes a catalog resource declaration with its content.
	 *
	 * @param schema catalog word list or synonym list declaration
	 * @return the wire declaration of the resource
	 */
	@Nonnull
	public static GrpcFulltextResourceSchema toGrpcResource(@Nonnull FulltextResourceSchemaContract schema) {
		return GrpcFulltextResourceSchema.newBuilder().setName(schema.getName())
			.setContent(toGrpcContent(schema.getContent())).build();
	}

	/**
	 * Reads a catalog resource declaration with its content.
	 *
	 * @param schema wire declaration of the resource
	 * @return the catalog resource declaration
	 */
	@Nonnull
	public static FulltextResourceSchema toResource(@Nonnull GrpcFulltextResourceSchema schema) {
		return FulltextResourceSchema._internalBuild(schema.getName(), toContent(schema.getContent()));
	}

	/**
	 * Writes a catalog analyzer declaration as declared, before its phases are inherited from the base analyzer.
	 *
	 * @param schema catalog analyzer declaration
	 * @return the wire declaration, without `basedOn` for an analyzer based on none
	 */
	@Nonnull
	public static GrpcFulltextAnalyzerSchema toGrpcAnalyzer(@Nonnull FulltextAnalyzerSchemaContract schema) {
		final GrpcFulltextAnalyzerSchema.Builder analyzer = GrpcFulltextAnalyzerSchema.newBuilder()
			.setName(schema.getName())
			.addAllSamples(schema.getSamples())
			.setAllLanguages(toGrpcPhases(schema.getAllLanguages()));
		schema.getBasedOn().ifPresent(value -> analyzer.setBasedOn(StringValue.of(value)));
		schema.getLanguages().forEach((language, phases) -> analyzer.putLanguages(language, toGrpcPhases(phases)));
		return analyzer.build();
	}

	/**
	 * Reads a catalog analyzer declaration without resolving the phases it inherits.
	 *
	 * @param schema wire declaration of the analyzer
	 * @return the catalog analyzer declaration, based on none when `basedOn` is absent
	 */
	@Nonnull
	public static FulltextAnalyzerSchema toAnalyzer(@Nonnull GrpcFulltextAnalyzerSchema schema) {
		return FulltextAnalyzerSchema._internalBuild(schema.getName(),
			schema.hasBasedOn() ? schema.getBasedOn().getValue() : null,
			toPhases(schema.getAllLanguages()),
			schema.getLanguagesMap().entrySet().stream().collect(Collectors.toMap(
				Entry::getKey, language -> toPhases(language.getValue()))),
			schema.getSamplesList());
	}
}
