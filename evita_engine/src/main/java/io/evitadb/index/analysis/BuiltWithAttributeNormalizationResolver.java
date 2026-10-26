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

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.requestResponse.schema.OrderBehaviour.NULLS_LAST;

/**
 * Binds immutable functions from owner-held built-with descriptions. A missing row always means legacy NFD,
 * regardless of today's declaration. Owners publish a new resolver only at schema adoption; indexes borrow it.
 *
 * @param registry catalog-owned step registry and compiled-function cache
 * @param policy immutable collection rows and retained bindings
 * @param globalRows authoritative catalog rows borrowed by every adopter
 * @param catalogOwned whether this resolver binds catalog-owned structures directly to catalog rows
 */
public record BuiltWithAttributeNormalizationResolver(
	@Nonnull AnalysisStepRegistry registry,
	@Nonnull AttributeNormalizationPolicy policy,
	@Nonnull Map<String, List<AnalysisStep>> globalRows,
	boolean catalogOwned
) implements AttributeNormalizationResolver {
	/**
	 * Creates a collection resolver whose persisted adoption set determines global ownership.
	 *
	 * @param registry   catalog-owned step registry and compiled-function cache
	 * @param policy     immutable collection rows and retained bindings
	 * @param globalRows authoritative catalog rows borrowed by every adopter
	 */
	public BuiltWithAttributeNormalizationResolver(
		@Nonnull AnalysisStepRegistry registry, @Nonnull AttributeNormalizationPolicy policy,
		@Nonnull Map<String, List<AnalysisStep>> globalRows
	) {
		this(registry, policy, globalRows, false);
	}

	/**
	 * Resolves the schema's identity through its retained owner binding rather than its current schema type.
	 *
	 * @param referenceName   owning reference, null for entity or global attributes
	 * @param attributeSchema declaration supplying only the attribute name
	 * @param foldLocale      locale for localized Strings, null for root folding
	 * @return immutable raw String key function
	 */
	@Override
	@Nonnull
	public UnaryOperator<String> resolve(
		@Nullable String referenceName, @Nonnull AttributeSchemaContract attributeSchema, @Nullable Locale foldLocale
	) {
		return resolve(referenceName, attributeSchema.getName(), foldLocale);
	}

	/**
	 * Resolves an orphaned scalar String tree by its persisted identity.
	 *
	 * @param referenceName owning reference, null for entity attributes
	 * @param attributeName retained attribute identity
	 * @param foldLocale locale for localized Strings, null for root folding
	 * @return immutable raw String key function
	 */
	@Nonnull
	public UnaryOperator<String> resolve(
		@Nullable String referenceName, @Nonnull String attributeName, @Nullable Locale foldLocale
	) {
		return this.registry.compile(steps(referenceName, attributeName), foldLocale);
	}

	/**
	 * Binds a newly created compound from its supplied declaration. Resolver slots need only effective rows: new or
	 * changed empty compound bindings must not depend on the compound descriptions captured by an older slot.
	 * Non-String elements keep the existing stored-compound encoding, including its zero scale.
	 *
	 * @param referenceName owning reference, null for entity attributes
	 * @param compoundSchema compound being created
	 * @param attributeSchemaProvider resolves the compound's element declarations
	 * @param foldLocale compound locale, masked for each non-localized element
	 * @return immutable raw-tuple function
	 */
	@Override
	@Nonnull
	@SuppressWarnings({"unchecked", "rawtypes"})
	public UnaryOperator<Serializable> resolveCompoundNormalizer(
		@Nullable String referenceName,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchema,
		@Nonnull Function<String, AttributeSchemaContract> attributeSchemaProvider,
		@Nullable Locale foldLocale
	) {
		final UnaryOperator<Serializable>[] functions =
			new UnaryOperator[compoundSchema.getAttributeElements().size()];
		int position = 0;
		for (final SortableAttributeCompoundSchemaContract.AttributeElement element :
			compoundSchema.getAttributeElements()) {
			final AttributeSchemaContract attribute = attributeSchemaProvider.apply(element.attributeName());
			final Class<?> type = attribute.getPlainType();
			if (type == String.class) {
				functions[position++] = (UnaryOperator) resolve(
					referenceName, attribute, attribute.isLocalized() ? foldLocale : null);
			} else {
				functions[position++] = nonStringElementNormalizer(
					new SortIndex.ComparatorSource(type, ASC, NULLS_LAST), 0);
			}
		}
		return SortIndex.createCompoundNormalizer(functions);
	}

	/**
	 * Compares the effective rows used by this owner, ignoring compound metadata and unrelated catalog rows.
	 *
	 * @param previous previous owner resolver
	 * @return true when all adopted or retained rows still describe the same functions
	 */
	public boolean hasSameRows(@Nonnull BuiltWithAttributeNormalizationResolver previous) {
		for (final AttributeIndexKey key : this.policy.rows().keySet()) {
			if (hasChangedSteps(previous, key.referenceName(), key.attributeName())) {
				return false;
			}
		}
		for (final AttributeIndexKey key : previous.policy.rows().keySet()) {
			if (hasChangedSteps(previous, key.referenceName(), key.attributeName())) {
				return false;
			}
		}
		for (final String name : this.policy.globalAttributes()) {
			if (hasChangedSteps(previous, null, name)) {
				return false;
			}
		}
		for (final String name : previous.policy.globalAttributes()) {
			if (hasChangedSteps(previous, null, name)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Tests whether the effective row of one identity differs between this resolver and `previous`.
	 *
	 * @param previous      resolver to compare against
	 * @param referenceName owning reference, null for entity or global attributes
	 * @param attributeName attribute identity
	 * @return true when the two resolvers select different steps for the identity
	 */
	private boolean hasChangedSteps(
		@Nonnull BuiltWithAttributeNormalizationResolver previous,
		@Nullable String referenceName, @Nonnull String attributeName
	) {
		return !steps(referenceName, attributeName).equals(previous.steps(referenceName, attributeName));
	}

	/**
	 * Tests the effective String functions of a retained compound before replacing its wrapper.
	 *
	 * @param key physical compound identity
	 * @param previous resolver captured before adoption
	 * @return true when at least one String element's effective steps changed
	 */
	public boolean hasChangedCompoundSteps(
		@Nonnull AttributeIndexKey key, @Nonnull AttributeNormalizationResolver previous
	) {
		final List<AttributeNormalizationPolicy.ElementBinding> bindings = this.policy.compounds().get(
			new AttributeIndexKey(key.referenceName(), key.attributeName(), null));
		if (bindings != null) {
			for (final AttributeNormalizationPolicy.ElementBinding element : bindings) {
				if (element.type() == String.class) {
					final List<AnalysisStep> previousSteps =
						previous instanceof BuiltWithAttributeNormalizationResolver builtWith ?
							builtWith.steps(key.referenceName(), element.attributeName()) : List.of();
					if (!steps(key.referenceName(), element.attributeName()).equals(previousSteps)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Selects the retained owner row, regardless of current schema or compound element ownership hints.
	 *
	 * @param referenceName owning reference, null for entity or global attributes
	 * @param attributeName attribute identity
	 * @return the catalog row for a global attribute, otherwise the collection row; empty when none is recorded
	 */
	@Nonnull
	private List<AnalysisStep> steps(
		@Nullable String referenceName, @Nonnull String attributeName
	) {
		return referenceName == null && (this.catalogOwned || this.policy.globalAttributes().contains(attributeName)) ?
			this.globalRows.getOrDefault(attributeName, List.of()) :
			this.policy.rows().getOrDefault(new AttributeIndexKey(referenceName, attributeName, null), List.of());
	}

	/**
	 * Restores per-element functions using persisted identities and comparator types, without consulting a possibly
	 * different compound declaration. Legacy compounds without metadata retain their type-level NFD functions.
	 *
	 * @param key physical sort identity, including locale
	 * @param sources frozen comparator types of the stored tree
	 * @param scale frozen decimal scale
	 * @return immutable raw scalar or tuple key function
	 */
	@Nonnull
	@SuppressWarnings({"unchecked", "rawtypes"})
	public UnaryOperator<Serializable> resolveStoredSortNormalizer(
		@Nonnull AttributeIndexKey key, @Nonnull SortIndex.ComparatorSource[] sources, int scale
	) {
		final List<AttributeNormalizationPolicy.ElementBinding> bindings = this.policy.compounds().get(
			new AttributeIndexKey(key.referenceName(), key.attributeName(), null));
		if (bindings == null) {
			return sources.length == 1 && sources[0].type() == String.class ?
				(UnaryOperator) resolve(key.referenceName(), key.attributeName(), key.locale()) :
				SortIndex.createLegacyNormalizerFor(sources, scale);
		}
		if (bindings.size() != sources.length) {
			throw new GenericEvitaInternalError(
				"Persisted compound binding does not match comparator descriptors for `" + key + "`.");
		}
		final UnaryOperator<Serializable>[] functions = new UnaryOperator[sources.length];
		for (int i = 0; i < sources.length; i++) {
			final AttributeNormalizationPolicy.ElementBinding element = bindings.get(i);
			if (sources[i].type() == String.class) {
				functions[i] = (UnaryOperator) resolve(key.referenceName(), element.attributeName(),
					element.localized() ? key.locale() : null);
			} else {
				functions[i] = nonStringElementNormalizer(sources[i], scale);
			}
		}
		return SortIndex.createCompoundNormalizer(functions);
	}

	/**
	 * Keeps the stored-compound encoding of a non-String element: a `BigDecimal` passes through unchanged, any other
	 * type uses its sort normalizer when it has one.
	 *
	 * @param source comparator type of the element
	 * @param scale decimal scale of the encoding
	 * @return immutable element key function
	 */
	@Nonnull
	private static UnaryOperator<Serializable> nonStringElementNormalizer(
		@Nonnull SortIndex.ComparatorSource source, int scale
	) {
		return BigDecimal.class.isAssignableFrom(source.type()) ?
			UnaryOperator.identity() :
			SortIndex.createNormalizerFor(source, scale).orElseGet(UnaryOperator::identity);
	}
}
