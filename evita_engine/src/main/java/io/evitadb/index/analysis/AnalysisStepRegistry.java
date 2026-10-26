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


package io.evitadb.index.analysis;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.spi.analysis.AnalysisStepFactory;
import io.evitadb.spi.analysis.AnalysisStepParameterDescriptor;
import io.evitadb.spi.analysis.NormalizingAnalysisStepFactory;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.UnaryOperator;

/**
 * Catalog-owned, immutable provider registry, shared by every collection and catalog snapshot.
 * Discovery happens once at catalog creation. Only immutable compiled functions are memoized; no active attribute
 * policy is stored here. Providers and steps own no catalog-storage resources and require no close operation.
 */
public final class AnalysisStepRegistry {
	/** Installed providers, never mutated after construction. */
	@Nonnull private final Map<String, AnalysisStepFactory> factories;
	/** Thread-safe memoization of immutable functions, independent of schema snapshots. */
	@Nonnull private final StringNormalizationCompiler compiler;

	/** Discovers server providers through JPMS or the classpath service descriptor. */
	public AnalysisStepRegistry() {
		this(ServiceLoader.load(AnalysisStepFactory.class).stream().map(ServiceLoader.Provider::get).toList());
	}

	/**
	 * Builds an immutable registry from explicitly supplied providers.
	 * Duplicate diagnostics are sorted by name and provider class, independent of discovery order.
	 *
	 * @param factories complete provider set
	 * @throws EvitaInvalidUsageException when two providers claim the same name
	 */
	public AnalysisStepRegistry(@Nonnull Collection<? extends AnalysisStepFactory> factories) {
		final List<AnalysisStepFactory> sorted = new ArrayList<>(factories);
		sorted.sort(Comparator.comparing(AnalysisStepFactory::getName).thenComparing(it -> it.getClass().getName()));
		final Map<String, AnalysisStepFactory> installed = CollectionUtils.createLinkedHashMap(sorted.size());
		for (final AnalysisStepFactory factory : sorted) {
			final AnalysisStepFactory previous = installed.putIfAbsent(factory.getName(), factory);
			if (previous != null) {
				throw new EvitaInvalidUsageException(
					"Duplicate analysis step `" + factory.getName() + "`: " + previous.getClass().getName() +
						" and " + factory.getClass().getName() + "."
				);
			}
		}
		this.factories = Map.copyOf(installed);
		this.compiler = new StringNormalizationCompiler(this);
	}

	/**
	 * Returns immutable metadata for installed normalizing providers, ordered by their stable names.
	 * External APIs use these descriptors to generate typed inputs without exposing compiled index policy.
	 *
	 * @return normalizer-capable providers sorted by step name
	 */
	@Nonnull
	public List<NormalizingAnalysisStepFactory> getNormalizingFactories() {
		return this.factories.values().stream()
			.filter(NormalizingAnalysisStepFactory.class::isInstance)
			.map(NormalizingAnalysisStepFactory.class::cast)
			.sorted(Comparator.comparing(AnalysisStepFactory::getName))
			.toList();
	}

	/**
	 * Resolves a normalizer description and validates its declared parameters.
	 *
	 * @param step persisted description
	 * @return installed normalizer-capable factory
	 * @throws EvitaInvalidUsageException distinguishing unknown, unsafe and invalid-parameter declarations
	 */
	@Nonnull
	public NormalizingAnalysisStepFactory resolve(@Nonnull AnalysisStep step) {
		final AnalysisStepFactory factory = this.factories.get(step.name());
		if (factory == null) {
			throw new EvitaInvalidUsageException("Unknown analysis step `" + step.name() + "`.");
		}
		if (!(factory instanceof NormalizingAnalysisStepFactory normalizingFactory)) {
			throw new EvitaInvalidUsageException(
				"Analysis step `" + step.name() + "` is not usable as a normalizer."
			);
		}
		try {
			final Map<String, Serializable> parameters = step.parameters();
			final Map<String, AnalysisStepParameterDescriptor> descriptors =
				CollectionUtils.createHashMap(factory.getParameters().size());
			for (final AnalysisStepParameterDescriptor descriptor : factory.getParameters()) {
				descriptors.put(descriptor.name(), descriptor);
				if (descriptor.required() && !parameters.containsKey(descriptor.name())) {
					throw new EvitaInvalidUsageException("Missing required parameter `" + descriptor.name() + "`.");
				}
			}
			for (final Map.Entry<String, Serializable> parameter : parameters.entrySet()) {
				final AnalysisStepParameterDescriptor descriptor = descriptors.get(parameter.getKey());
				if (descriptor == null) {
					throw new EvitaInvalidUsageException("Unknown parameter `" + parameter.getKey() + "`.");
				}
				if (!descriptor.type().isInstance(parameter.getValue())) {
					throw new EvitaInvalidUsageException(
						"Parameter `" + parameter.getKey() + "` must be " + descriptor.type().getSimpleName() + "."
					);
				}
			}
			normalizingFactory.validate(parameters);
		} catch (EvitaInvalidUsageException ex) {
			throw new EvitaInvalidUsageException(
				"Invalid parameters for analysis step `" + step.name() + "`: " + ex.getMessage(), ex
			);
		}
		return normalizingFactory;
	}

	/**
	 * Returns the shared immutable key function for this exact step list and fold locale (null means root).
	 * Null values pass through. An empty list preserves the legacy NFD-only String key function.
	 *
	 * @param steps      ordered normalization steps to chain
	 * @param foldLocale locale for locale-sensitive steps such as lowercasing, `null` for the root locale
	 * @return shared immutable key function applying the steps in order
	 */
	@Nonnull
	public UnaryOperator<String> compile(@Nonnull List<AnalysisStep> steps, @Nullable Locale foldLocale) {
		return this.compiler.compile(steps, foldLocale);
	}

	/**
	 * Validates installed providers for all entity and reference attributes before a collection exchanges schemas.
	 *
	 * @param schema entity schema whose attribute and reference attribute normalization steps are checked
	 * @throws InvalidSchemaMutationException when a declared step has no usable provider or invalid parameters
	 */
	public void validateNormalizationDescriptions(@Nonnull EntitySchemaContract schema) {
		for (final AttributeSchemaContract attribute : schema.getAttributes().values()) {
			validateAttribute(attribute);
		}
		for (final ReferenceSchemaContract reference : schema.getReferences().values()) {
			// An unresolved reflection can already declare attributes, but cannot enumerate inherited ones yet.
			for (final AttributeSchemaContract attribute :
				AttributeNormalizationReconciler.attributesOf(reference).values()) {
				validateAttribute(attribute);
			}
		}
	}

	/**
	 * Validates global attributes before catalog schema exchange and propagation into collections.
	 *
	 * @param schema catalog schema whose global attribute normalization steps are checked
	 * @throws InvalidSchemaMutationException when a declared step has no usable provider or invalid parameters
	 */
	public void validateNormalizationDescriptions(@Nonnull CatalogSchemaContract schema) {
		for (final AttributeSchemaContract attribute : schema.getAttributes().values()) {
			validateAttribute(attribute);
		}
	}

	/**
	 * Checks provider availability only; assembled-schema structural validation retains its existing boundaries.
	 *
	 * @param attribute attribute whose declared normalization steps are resolved against the installed providers
	 */
	private void validateAttribute(@Nonnull AttributeSchemaContract attribute) {
		for (final AnalysisStep step : attribute.getNormalization()) {
			try {
				resolve(step);
			} catch (EvitaInvalidUsageException ex) {
				throw new InvalidSchemaMutationException(
					"Attribute `" + attribute.getName() + "` has invalid normalization step `" + step.name() +
						"`: " + ex.getMessage()
				);
			}
		}
	}
}
