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
import io.evitadb.spi.analysis.FulltextAnalysisSide;
import io.evitadb.spi.analysis.FulltextAnalysisStepFactory;
import io.evitadb.spi.analysis.FulltextSplittingAnalysisStepFactory;
import io.evitadb.spi.analysis.FulltextTextAnalysisStepFactory;
import io.evitadb.spi.analysis.FulltextTokenizerAnalysisStepFactory;
import io.evitadb.spi.analysis.FulltextWordsAnalysisStepFactory;
import io.evitadb.spi.analysis.NormalizingAnalysisStepFactory;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Catalog-owned, immutable provider registry, shared by every collection and catalog snapshot.
 * Discovery happens once at catalog creation. Only immutable compiled functions are memoized; no active attribute
 * policy is stored here. Providers and steps own no catalog-storage resources and require no close operation.
 * It resolves the steps of attribute normalization and of fulltext analyzers alike; a fulltext provider whose type
 * does not name exactly one phase, or which acts on no side, is refused at discovery. So is one whose declarations
 * would let its index terms change without changing the fingerprint fields persist: an index-side parameter without
 * a descriptor or of a type without a lossless encoding, or a parameter left out of the index side of a step without
 * a query side of its own.
 */
public final class AnalysisStepRegistry {
	/**
	 * Exact scalar descriptor types with lossless fingerprint encodings; their object arrays are accepted too.
	 */
	@Nonnull private static final Set<Class<?>> INDEX_PARAMETER_TYPES = Set.of(
		String.class, Character.class, Boolean.class, Byte.class, Short.class, Integer.class, Long.class,
		BigDecimal.class, Locale.class, Currency.class, UUID.class, LocalDate.class, LocalTime.class,
		LocalDateTime.class, OffsetDateTime.class
	);
	/**
	 * Primitive arrays already accepted by analysis parameter descriptions, encoded element by element.
	 */
	@Nonnull private static final Set<Class<?>> INDEX_PRIMITIVE_ARRAY_TYPES = Set.of(
		char[].class, boolean[].class, byte[].class, short[].class, int[].class, long[].class
	);
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
	 * @throws EvitaInvalidUsageException when two providers claim the same name, or a fulltext provider declares its
	 *                                    phase, sides or index-side parameters inconsistently
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
			if (factory instanceof FulltextAnalysisStepFactory fulltextFactory) {
				validateFulltextFactory(fulltextFactory);
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
		final AnalysisStepFactory factory = lookup(step);
		if (!(factory instanceof NormalizingAnalysisStepFactory normalizingFactory)) {
			throw new EvitaInvalidUsageException(
				"Analysis step `" + step.name() + "` is not usable as a normalizer."
			);
		}
		validateParameters(step, normalizingFactory);
		return normalizingFactory;
	}

	/**
	 * Resolves a fulltext analysis step description and validates its declared parameters.
	 *
	 * @param step persisted description
	 * @return installed fulltext-capable factory
	 * @throws EvitaInvalidUsageException distinguishing unknown, unusable and invalid-parameter declarations
	 */
	@Nonnull
	public FulltextAnalysisStepFactory resolveFulltext(@Nonnull AnalysisStep step) {
		final AnalysisStepFactory factory = lookup(step);
		if (!(factory instanceof FulltextAnalysisStepFactory fulltextFactory)) {
			throw new EvitaInvalidUsageException(
				"Analysis step `" + step.name() + "` is not usable in a fulltext analyzer."
			);
		}
		validateParameters(step, fulltextFactory);
		return fulltextFactory;
	}

	/**
	 * Finds the installed provider of a step's name, whatever it is capable of.
	 *
	 * @param step persisted description
	 * @return the provider installed under the step's name
	 * @throws EvitaInvalidUsageException when no provider of the step's name is installed
	 */
	@Nonnull
	private AnalysisStepFactory lookup(@Nonnull AnalysisStep step) {
		final AnalysisStepFactory factory = this.factories.get(step.name());
		if (factory == null) {
			throw new EvitaInvalidUsageException("Unknown analysis step `" + step.name() + "`.");
		}
		return factory;
	}

	/**
	 * Refuses a fulltext provider whose type does not say one phase, or which acts on no side. The phase interfaces are
	 * sealed under {@link FulltextAnalysisStepFactory}, but a class can still implement two of them, and a side set is
	 * a value the type cannot check. It also refuses index-side declarations the fingerprint cannot trust - see
	 * {@link #validateIndexSideParameters(FulltextAnalysisStepFactory)} and
	 * {@link #requireEveryParameterIndexSide(FulltextAnalysisStepFactory)}.
	 *
	 * @param factory the discovered provider
	 * @throws EvitaInvalidUsageException naming the invalid provider or its index-side parameter and type
	 */
	private static void validateFulltextFactory(@Nonnull FulltextAnalysisStepFactory factory) {
		final int phases = (factory instanceof FulltextTextAnalysisStepFactory ? 1 : 0) +
			(factory instanceof FulltextTokenizerAnalysisStepFactory ? 1 : 0) +
			(factory instanceof FulltextSplittingAnalysisStepFactory ? 1 : 0) +
			(factory instanceof FulltextWordsAnalysisStepFactory ? 1 : 0);
		if (phases != 1) {
			throw new EvitaInvalidUsageException(
				"Analysis step `" + factory.getName() + "` (" + factory.getClass().getName() + ") implements " +
					phases + " fulltext phases - a step belongs to exactly one."
			);
		}
		validateIndexSideParameters(factory);
		final Set<FulltextAnalysisSide> sides;
		if (factory instanceof FulltextTextAnalysisStepFactory textFactory) {
			sides = textFactory.getSides();
		} else if (factory instanceof FulltextWordsAnalysisStepFactory wordsFactory) {
			sides = wordsFactory.getSides();
		} else {
			// the tokenizer and the further splitting steps act on both sides by their type, and alike
			requireEveryParameterIndexSide(factory);
			return;
		}
		if (sides.isEmpty()) {
			throw new EvitaInvalidUsageException(
				"Analysis step `" + factory.getName() + "` (" + factory.getClass().getName() + ") acts on no side."
			);
		}
		if (!sides.contains(FulltextAnalysisSide.QUERY)) {
			requireEveryParameterIndexSide(factory);
		}
	}

	/**
	 * Refuses an index-side parameter without a descriptor, or whose type is none of {@link #INDEX_PARAMETER_TYPES},
	 * their object arrays and {@link #INDEX_PRIMITIVE_ARRAY_TYPES}. The fingerprint a field persists encodes these
	 * values as text, and a type without a lossless text form could change the index terms without changing it.
	 *
	 * @param factory the discovered provider
	 * @throws EvitaInvalidUsageException naming the provider and the parameter without a descriptor or its type
	 */
	private static void validateIndexSideParameters(@Nonnull FulltextAnalysisStepFactory factory) {
		for (final String parameter : factory.getIndexSideParameters()) {
			AnalysisStepParameterDescriptor descriptor = null;
			for (final AnalysisStepParameterDescriptor candidate : factory.getParameters()) {
				if (candidate.name().equals(parameter)) {
					descriptor = candidate;
					break;
				}
			}
			if (descriptor == null) {
				throw new EvitaInvalidUsageException(
					"Index-side parameter `" + parameter + "` of analysis step `" + factory.getName() +
						"` has no descriptor."
				);
			}
			if (!isLosslessIndexType(descriptor.type())) {
				throw new EvitaInvalidUsageException(
					"Index-side parameter `" + parameter + "` of analysis step `" + factory.getName() +
						"` has unsupported fingerprint type " + descriptor.type().getTypeName() + "."
				);
			}
		}
	}

	/**
	 * Tells whether the fingerprint can encode the values of a descriptor type without loss.
	 *
	 * @param type the exact type an index-side parameter's descriptor declares
	 * @return `true` for an accepted scalar, an object array of one, or an accepted primitive array
	 */
	private static boolean isLosslessIndexType(@Nonnull Class<?> type) {
		return INDEX_PARAMETER_TYPES.contains(type) || INDEX_PRIMITIVE_ARRAY_TYPES.contains(type) ||
			(type.isArray() && INDEX_PARAMETER_TYPES.contains(type.getComponentType()));
	}

	/**
	 * Refuses a parameter left out of the index-side parameters of a step without a query side of its own: none of
	 * its parameters can act on the query side alone, so each one changes the index terms, and one left out would
	 * change them without changing the fingerprint a field persists.
	 *
	 * @param factory a discovered provider of a tokenizer, a further splitting step, or a step acting on the index side
	 *                only
	 * @throws EvitaInvalidUsageException naming the provider and the parameter missing from its index-side parameters
	 */
	private static void requireEveryParameterIndexSide(@Nonnull FulltextAnalysisStepFactory factory) {
		final Set<String> indexSide = factory.getIndexSideParameters();
		for (final AnalysisStepParameterDescriptor descriptor : factory.getParameters()) {
			if (!indexSide.contains(descriptor.name())) {
				throw new EvitaInvalidUsageException(
					"Parameter `" + descriptor.name() + "` of analysis step `" + factory.getName() + "` (" +
						factory.getClass().getName() + ") is missing from its index-side parameters: the step has " +
						"no query side of its own, so every parameter changes the index terms."
				);
			}
		}
	}

	/**
	 * Checks the declared parameters against the factory's descriptors, then lets the factory validate their values.
	 *
	 * @param step    persisted description
	 * @param factory the factory the description resolved to
	 * @throws EvitaInvalidUsageException naming the step when a parameter is missing, unknown, mistyped, holds a `null`
	 *                                    array element or is invalid
	 */
	private static void validateParameters(@Nonnull AnalysisStep step, @Nonnull AnalysisStepFactory factory) {
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
				refuseNullArrayElements(parameter.getKey(), parameter.getValue());
			}
			factory.validate(parameters);
		} catch (EvitaInvalidUsageException ex) {
			throw new EvitaInvalidUsageException(
				"Invalid parameters for analysis step `" + step.name() + "`: " + ex.getMessage(), ex
			);
		}
	}

	/**
	 * Refuses an object array holding a `null` element; a primitive array cannot hold one.
	 *
	 * @param name  the parameter's name, for the refusal
	 * @param value the parameter's value, already checked against the type of its descriptor
	 * @throws EvitaInvalidUsageException naming the parameter when an element of the array is `null`
	 */
	private static void refuseNullArrayElements(@Nonnull String name, @Nonnull Serializable value) {
		final Class<?> type = value.getClass();
		if (type.isArray() && !type.getComponentType().isPrimitive()) {
			for (final Object element : (Object[]) value) {
				if (element == null) {
					throw new EvitaInvalidUsageException(
						"Parameter `" + name + "` must not contain null array elements."
					);
				}
			}
		}
	}

	/**
	 * Returns the shared immutable key function for this exact step list and fold locale (null means root).
	 * Null values pass through. An empty list preserves the type-level NFD-only String key function.
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
