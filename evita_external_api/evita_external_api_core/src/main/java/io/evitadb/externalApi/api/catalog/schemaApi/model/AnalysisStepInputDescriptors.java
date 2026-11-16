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

package io.evitadb.externalApi.api.catalog.schemaApi.model;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.spi.analysis.*;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;
import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nullableRef;

/**
 * Shared typed analysis input metadata generated from installed providers for each declaration phase.
 * GraphQL applies `@oneOf` to the aggregate; REST represents the same exclusivity with OpenAPI `oneOf`.
 * Parameterless providers use `true`, because GraphQL does not permit input objects without fields.
 */
public final class AnalysisStepInputDescriptors {
	@Nonnull private static final AnalysisStepRegistry REGISTRY = new AnalysisStepRegistry();
	@Nonnull private static final List<StepInput> STEPS = getSteps(REGISTRY);

	/** Phase-specific selectors keep fulltext providers out of the attribute normalization input. */
	public enum Context {
		/** Attribute normalization. */
		NORMALIZATION,
		/** Whole-text preprocessing. */
		TEXT,
		/** Token transformations. */
		WORDS,
		/** Language tokenizer and subsequent splitting. */
		WORD_SPLITTING
	}

	/** Prevents instantiation of this static holder. */
	private AnalysisStepInputDescriptors() {
	}

	/**
	 * Returns the validated installed-provider registry shared by metadata and input conversion.
	 *
	 * @return installed providers used to generate and parse the external API's selectors
	 */
	@Nonnull
	public static AnalysisStepRegistry getRegistry() {
		return REGISTRY;
	}

	/**
	 * Returns installed normalizing steps sorted by their stable names.
	 *
	 * @return immutable metadata of the steps installed on this server
	 */
	@Nonnull
	public static List<StepInput> getSteps() {
		return STEPS;
	}

	/**
	 * Builds the attribute normalization selector; each API adds its exclusivity constraint.
	 *
	 * @return descriptor with one optional property per installed normalizing step
	 */
	@Nonnull
	public static ObjectDescriptor getInputDescriptor() {
		return getInputDescriptor(Context.NORMALIZATION);
	}

	/**
	 * Builds the selector for one declaration phase.
	 *
	 * @param context phase whose providers are accepted
	 * @return optional-field selector; APIs apply their own exclusivity constraint
	 */
	@Nonnull
	public static ObjectDescriptor getInputDescriptor(@Nonnull Context context) {
		return getInputDescriptor(context, getSteps(context));
	}

	/**
	 * Builds an exclusive selector from the provider metadata used by the API registry.
	 *
	 * @param context declaration phase whose selector is being built
	 * @param steps ordered provider metadata accepted by that phase
	 * @return aggregate whose optional fields select exactly one provider
	 */
	@Nonnull
	public static ObjectDescriptor getInputDescriptor(@Nonnull Context context, @Nonnull List<StepInput> steps) {
		final ObjectDescriptor.ObjectDescriptorBuilder builder = ObjectDescriptor.builder()
			.name(context == Context.NORMALIZATION ? "InputAnalysisStep" :
				"InputFulltext" + StringUtils.toPascalCase(context.name().toLowerCase(Locale.ROOT)) + "Step")
			.description("Exactly one installed " + (context == Context.NORMALIZATION ? "normalization" : "fulltext") +
				" step must be selected with a non-null value.");
		for (final StepInput step : steps) {
			builder.staticProperty(PropertyDescriptor.builder()
				.name(step.fieldName())
				.description("Selects `" + step.stepName() + "`; parameterless steps accept only `true`.")
				.type(step.parameters() == null ? nullable(Boolean.class) : nullableRef(step.parameters()))
				.build());
		}
		return builder.build();
	}

	/**
	 * Builds deterministic normalization metadata for a server registry and refuses step names that collide after
	 * camel-case conversion.
	 *
	 * @param registry installed server analysis providers
	 * @return immutable metadata of the normalizing steps sorted by step name
	 */
	@Nonnull
	public static List<StepInput> getSteps(@Nonnull AnalysisStepRegistry registry) {
		return describe(registry.getNormalizingFactories(), Context.NORMALIZATION);
	}

	/**
	 * Discovers providers usable in one fulltext phase without exposing compiled functions.
	 *
	 * @param context declaration phase
	 * @return stable metadata ordered by provider name
	 */
	@Nonnull
	public static List<StepInput> getSteps(@Nonnull Context context) {
		return context == Context.NORMALIZATION ? getSteps() : getSteps(REGISTRY, context);
	}

	/**
	 * Describes the validated providers of a server registry for one declaration phase.
	 *
	 * @param registry validated installed providers of the server
	 * @param context declaration phase restricting the providers
	 * @return immutable input metadata in provider name order
	 */
	@Nonnull
	public static List<StepInput> getSteps(@Nonnull AnalysisStepRegistry registry, @Nonnull Context context) {
		if (context == Context.NORMALIZATION) {
			return getSteps(registry);
		}
		return describe(registry.getFulltextFactories().stream()
			.filter(factory -> switch (context) {
				case TEXT -> factory instanceof FulltextTextAnalysisStepFactory;
				case WORDS -> factory instanceof FulltextWordsAnalysisStepFactory;
				case WORD_SPLITTING -> factory instanceof FulltextTokenizerAnalysisStepFactory ||
					factory instanceof FulltextSplittingAnalysisStepFactory;
				case NORMALIZATION -> throw new ExternalApiInternalError(
					"Normalization providers must use the normalization registry view.");
			}).toList(), context);
	}

	/**
	 * Describes installed provider parameters with API-safe field names, refusing two steps that would share a field
	 * name, and two parameters of one fulltext step whose camel-case spellings collide.
	 *
	 * @param factories providers admitted by the declaration phase
	 * @param context declaration phase; normalization retains its existing parameter spellings
	 * @return immutable selector metadata
	 */
	@Nonnull
	private static List<StepInput> describe(
		@Nonnull Collection<? extends AnalysisStepFactory> factories, @Nonnull Context context
	) {
		final List<StepInput> result = new ArrayList<>();
		final Set<String> names = CollectionUtils.createHashSet(factories.size());
		for (final AnalysisStepFactory factory : factories) {
			final String fieldName = StringUtils.toCamelCase(factory.getName());
			if (!names.add(fieldName)) {
				throw new ExternalApiInternalError((context == Context.NORMALIZATION ? "Normalizing" : "Fulltext") +
					" step input name collision: `" + fieldName + "`.");
			}
			ObjectDescriptor parameters = null;
			if (!factory.getParameters().isEmpty()) {
				final ObjectDescriptor.ObjectDescriptorBuilder builder = ObjectDescriptor.builder()
					.name("Input" + (context == Context.NORMALIZATION ? "" : "Fulltext") +
						StringUtils.toPascalCase(factory.getName()) + "AnalysisStepParameters")
					.description("Parameters accepted by installed " +
						(context == Context.NORMALIZATION ? "normalizing" : "fulltext") + " step `" +
						factory.getName() + "`.");
				final List<AnalysisStepParameterDescriptor> sorted = new ArrayList<>(factory.getParameters());
				sorted.sort(Comparator.comparing(AnalysisStepParameterDescriptor::name));
				final Set<String> parameterNames = CollectionUtils.createHashSet(sorted.size());
				for (final AnalysisStepParameterDescriptor parameter : sorted) {
					final String parameterName = context == Context.NORMALIZATION ?
						parameter.name() : StringUtils.toCamelCase(parameter.name());
					// two spellings camel-casing to one field would merge silently, and the input would reach only
					// one of them
					if (context != Context.NORMALIZATION && !parameterNames.add(parameterName)) {
						throw new ExternalApiInternalError("Fulltext step `" + factory.getName() +
							"` parameter name collision: `" + parameterName + "`.");
					}
					builder.staticProperty(PropertyDescriptor.builder()
						.name(parameterName)
						.description(parameter.description())
						.type(new PrimitivePropertyDataTypeDescriptor(parameter.type(), parameter.required()))
						.build());
				}
				parameters = builder.build();
			}
			result.add(new StepInput(factory.getName(), fieldName, parameters));
		}
		return List.copyOf(result);
	}

	/**
	 * Restores a provider parameter name using the same validated registry as the input metadata.
	 *
	 * @param registry validated providers used to describe this input
	 * @param stepName persisted provider name selected by the input
	 * @param inputName API-safe parameter spelling accepted by the selector
	 * @return original provider spelling, including its case and hyphens
	 */
	@Nonnull
	public static String getFulltextParameterName(
		@Nonnull AnalysisStepRegistry registry, @Nonnull String stepName, @Nonnull String inputName
	) {
		return registry.getFulltextFactories().stream().filter(factory -> factory.getName().equals(stepName))
			.flatMap(factory -> factory.getParameters().stream())
			.filter(parameter -> StringUtils.toCamelCase(parameter.name()).equals(inputName))
			.map(AnalysisStepParameterDescriptor::name).findFirst()
			.orElseThrow(() -> new ExternalApiInternalError(
				"Unknown fulltext parameter `" + inputName + "` of `" + stepName + "`."));
	}

	/**
	 * Input field and its optional parameter object.
	 *
	 * @param stepName persisted provider name
	 * @param fieldName camel-case API field name
	 * @param parameters parameter object descriptor, or null for a Boolean `true` selector
	 */
	public record StepInput(
		@Nonnull String stepName,
		@Nonnull String fieldName,
		@Nullable ObjectDescriptor parameters
	) {
	}
}
