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
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.SortIndex;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.requestResponse.schema.OrderBehaviour.NULLS_LAST;

/**
 * Resolves the String key function for a collection attribute `(reference name or null, attribute name)`,
 * or a catalog global attribute (null reference name), at the supplied fold locale.
 *
 * The declaration supplies the attribute identity; built-with descriptions belong to the collection or catalog
 * owner and can differ from the declaration. Call only when binding a structure; the returned function
 * is immutable and must remain attached to that structure through copies and removals.
 */
public interface AttributeNormalizationResolver {
	/** Explicit NFD-only binding for release migrations and standalone synthetic index owners. */
	AttributeNormalizationResolver LEGACY_NFD = (referenceName, attributeSchema, foldLocale) ->
		text -> (String) FilterIndex.getLegacyStringNormalizer().apply(text);

	/**
	 * Resolves an immutable String key function. Null fold locale means root, including cross-locale uniqueness.
	 */
	@Nonnull
	UnaryOperator<String> resolve(
		@Nullable String referenceName,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nullable Locale foldLocale
	);

	/**
	 * Binds the String policy or retains the existing type/scale encoding for a non-String attribute.
	 */
	@Nonnull
	@SuppressWarnings({"unchecked", "rawtypes"})
	default Function<Object, Serializable> resolveKeyNormalizer(
		@Nullable String referenceName,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nullable Locale foldLocale
	) {
		if (attributeSchema.getPlainType() == String.class) {
			return (Function) resolve(referenceName, attributeSchema, foldLocale);
		}
		return FilterIndex.getNormalizer(attributeSchema.getPlainType(), attributeSchema.getIndexedDecimalPlaces());
	}

	/**
	 * Binds a scalar sort key, retaining the existing non-String sort encoding.
	 */
	@Nonnull
	@SuppressWarnings({"unchecked", "rawtypes"})
	default UnaryOperator<Serializable> resolveSortNormalizer(
		@Nullable String referenceName,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nullable Locale foldLocale
	) {
		if (attributeSchema.getPlainType() == String.class) {
			return (UnaryOperator) resolve(referenceName, attributeSchema, foldLocale);
		}
		return SortIndex.createNormalizerFor(
			new SortIndex.ComparatorSource(attributeSchema.getPlainType(),
				ASC,
				NULLS_LAST),
			attributeSchema.getIndexedDecimalPlaces()
		).orElseGet(UnaryOperator::identity);
	}

	/**
	 * Binds every compound element separately. Non-localized elements fold by root even in a localized compound;
	 * BigDecimal elements retain the compound index's exact representation.
	 */
	@Nonnull
	default UnaryOperator<Serializable> resolveCompoundNormalizer(
		@Nullable String referenceName,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchema,
		@Nonnull Function<String, AttributeSchemaContract> attributeSchemaProvider,
		@Nullable Locale foldLocale
	) {
		return resolveCompoundNormalizer(referenceName, compoundSchema, attributeSchemaProvider, foldLocale, false);
	}

	/**
	 * Binds a prefetched compound comparator. String elements share the effective index function; non-String
	 * elements retain their comparator encoding, including rescaling raw decimal ranges before comparison.
	 * A scalar BigDecimal element stays exact, as it does in the compound index.
	 *
	 * @param referenceName the owning reference, null for entity attributes
	 * @param compoundSchema the compared compound
	 * @param attributeSchemaProvider resolves each element's schema
	 * @param foldLocale the compound locale, masked separately for each String element
	 * @return the bound raw-tuple comparator normalizer
	 */
	@Nonnull
	default UnaryOperator<Serializable> resolveCompoundComparatorNormalizer(
		@Nullable String referenceName,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchema,
		@Nonnull Function<String, AttributeSchemaContract> attributeSchemaProvider,
		@Nullable Locale foldLocale
	) {
		return resolveCompoundNormalizer(referenceName, compoundSchema, attributeSchemaProvider, foldLocale, true);
	}

	/** Binds per-element functions, preserving the raw-value encoding of the index or comparator role. */
	@Nonnull
	private UnaryOperator<Serializable> resolveCompoundNormalizer(
		@Nullable String referenceName,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchema,
		@Nonnull Function<String, AttributeSchemaContract> attributeSchemaProvider,
		@Nullable Locale foldLocale,
		boolean forComparison
	) {
		@SuppressWarnings("unchecked")
		final UnaryOperator<Serializable>[] normalizers =
			new UnaryOperator[compoundSchema.getAttributeElements().size()];
		int position = 0;
		boolean normalizationRequired = false;
		for (final AttributeElement element : compoundSchema.getAttributeElements()) {
			final AttributeSchemaContract schema = attributeSchemaProvider.apply(element.attributeName());
			final Class<?> type = schema.getPlainType();
			final Optional<UnaryOperator<Serializable>> normalizer;
			if (type == String.class) {
				normalizer = Optional.of(resolveSortNormalizer(
					referenceName, schema, schema.isLocalized() ? foldLocale : null));
			} else if (BigDecimal.class.isAssignableFrom(type)) {
				normalizer = Optional.empty();
			} else {
				final SortIndex.ComparatorSource source = new SortIndex.ComparatorSource(type, ASC, NULLS_LAST);
				normalizer = forComparison ?
					SortIndex.createComparatorNormalizerFor(source, schema.getIndexedDecimalPlaces()) :
					SortIndex.createNormalizerFor(source, schema.getIndexedDecimalPlaces());
			}
			normalizers[position++] = normalizer.orElseGet(UnaryOperator::identity);
			normalizationRequired |= normalizer.isPresent();
		}
		return normalizationRequired ? SortIndex.createCompoundNormalizer(normalizers) : UnaryOperator.identity();
	}
}
