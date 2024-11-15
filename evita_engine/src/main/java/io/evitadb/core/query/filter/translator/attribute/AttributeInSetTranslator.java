/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.core.query.filter.translator.attribute;

import io.evitadb.api.query.filter.AttributeInSet;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.core.query.AttributeSchemaAccessor.AttributeTrait;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.prefetch.EntityFilteringFormula;
import io.evitadb.core.query.algebra.prefetch.MultipleEntityFormula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Scope;
import io.evitadb.index.attribute.EntityReferenceWithLocale;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static io.evitadb.core.query.filter.translator.attribute.AbstractAttributeComparisonTranslator.createAlternativeBitmapFilter;
import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link FilteringConstraintTranslator} converts {@link AttributeInSet} to {@link AbstractFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeInSetTranslator extends AbstractAttributeTranslator
	implements FilteringConstraintTranslator<AttributeInSet> {

	/**
	 * Creates an AttributeFormula that targets globally unique attributes.
	 * When the entity type is not known and the attribute is unique globally,
	 * it accesses the catalog index instead.
	 *
	 * @param filterByVisitor       The visitor used to apply filters to global unique indices.
	 * @param globalAttributeSchema The schema of the global attribute.
	 * @param attributeKey          The key of the attribute.
	 * @param theComparedValues     A list of values to be compared against.
	 * @return The created AttributeFormula for the globally unique attributes.
	 */
	@Nonnull
	private static AttributeFormula createGloballyUniqueAttributeFormula(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull GlobalAttributeSchema globalAttributeSchema,
		@Nonnull AttributeKey attributeKey,
		@Nonnull List<? extends Serializable> theComparedValues
	) {
		// when entity type is not known and attribute is unique globally - access catalog index instead
		return new AttributeFormula(
			true,
			attributeKey,
			filterByVisitor.applyOnFirstGlobalUniqueIndex(
				globalAttributeSchema,
				index -> {
					final EntityReferenceWithLocale[] filteredEntityMaskedIds = theComparedValues.stream()
						.map(it -> index.getEntityReferenceByUniqueValue(it, attributeKey.locale()))
						.filter(Objects::nonNull)
						.toArray(EntityReferenceWithLocale[]::new);

					return ArrayUtils.isEmpty(filteredEntityMaskedIds) ?
						EmptyFormula.INSTANCE :
						new MultipleEntityFormula(
							new long[]{index.getId()},
							new BaseBitmap(
								filterByVisitor.translateEntityReference(filteredEntityMaskedIds)
							)
						);
				}
			)
		);
	}

	/**
	 * Creates an AttributeFormula for a unique attribute by utilizing hash map lookups for efficient access.
	 *
	 * @param filterByVisitor     The visitor used to apply filters to unique indexes.
	 * @param attributeDefinition The schema of the attribute.
	 * @param attributeKey        The key of the attribute.
	 * @param theComparedValues   A list of values to be compared against.
	 * @return The created AttributeFormula for the given attribute parameters.
	 */
	@Nonnull
	private static AttributeFormula createUniqueAttributeFormula(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull AttributeSchemaContract attributeDefinition,
		@Nonnull AttributeKey attributeKey,
		@Nonnull List<? extends Serializable> theComparedValues
	) {
		// if attribute is unique prefer O(1) hash map lookup over histogram
		return new AttributeFormula(
			attributeDefinition instanceof GlobalAttributeSchemaContract,
			attributeKey,
			filterByVisitor.applyStreamOnUniqueIndexes(
				attributeDefinition,
				index -> theComparedValues
					.stream()
					.map(
						it -> ofNullable(index.getRecordIdByUniqueValue(it))
							.map(x -> (Formula) new ConstantFormula(new ArrayBitmap(x)))
							.orElse(EmptyFormula.INSTANCE)
					)
			)
		);
	}

	/**
	 * Creates an AttributeFormula that uses histogram lookup for filterable attributes.
	 * This method constructs an AttributeFormula by leveraging the FilterByVisitor
	 * for applying specific filters to attribute indices.
	 *
	 * @param filterByVisitor     The visitor used to apply filters to filterable indexes.
	 * @param attributeDefinition The schema of the attribute.
	 * @param attributeKey        The key of the attribute.
	 * @param theComparedValues   A list of values to be compared against.
	 * @return The created AttributeFormula for the given filterable attribute parameters.
	 */
	@Nonnull
	private static AttributeFormula createFilterableAttributeFormula(
		@Nonnull FilterByVisitor filterByVisitor,
		@Nonnull AttributeSchemaContract attributeDefinition,
		@Nonnull AttributeKey attributeKey,
		@Nonnull List<? extends Serializable> theComparedValues
	) {
		// use histogram lookup
		return new AttributeFormula(
			attributeDefinition instanceof GlobalAttributeSchemaContract,
			attributeKey,
			filterByVisitor.applyStreamOnFilterIndexes(
				attributeDefinition,
				index -> theComparedValues.stream().map(index::getRecordsEqualToFormula)
			)
		);
	}

	@Nonnull
	@Override
	public Formula translate(@Nonnull AttributeInSet attributeInSet, @Nonnull FilterByVisitor filterByVisitor) {
		final Serializable[] comparedValues = attributeInSet.getAttributeValues();
		if (ArrayUtils.isEmpty(comparedValues)) {
			return EmptyFormula.INSTANCE;
		}

		final String attributeName = attributeInSet.getAttributeName();
		final Optional<GlobalAttributeSchemaContract> optionalGlobalAttributeSchema = getOptionalGlobalAttributeSchema(filterByVisitor, attributeName);

		if (filterByVisitor.isEntityTypeKnown() || optionalGlobalAttributeSchema.isPresent()) {
			final Set<Scope> scopes = filterByVisitor.getScopes();
			final AttributeSchemaContract attributeDefinition = optionalGlobalAttributeSchema
				.map(AttributeSchemaContract.class::cast)
				.orElseGet(() -> filterByVisitor.getAttributeSchema(attributeName, AttributeTrait.FILTERABLE));
			final AttributeKey attributeKey = createAttributeKey(filterByVisitor, attributeDefinition);

			final Class<? extends Serializable> plainType = attributeDefinition.getPlainType();
			final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(plainType);

			final List<? extends Serializable> theComparedValues = Arrays.stream(comparedValues)
				.map(it -> EvitaDataTypes.toTargetType(it, plainType))
				.map(normalizer)
				.toList();

			if (attributeDefinition instanceof GlobalAttributeSchema globalAttributeSchema &&
				scopes.stream().anyMatch(globalAttributeSchema::isUniqueGloballyInScope)) {
				return createGloballyUniqueAttributeFormula(
					filterByVisitor, globalAttributeSchema, attributeKey, theComparedValues
				);
			} else if (scopes.stream().anyMatch(attributeDefinition::isUnique)) {
				return createUniqueAttributeFormula(
					filterByVisitor, attributeDefinition, attributeKey, theComparedValues
				);
			} else {
				return createFilterableAttributeFormula(
					filterByVisitor, attributeDefinition, attributeKey, theComparedValues
				);
			}
		} else {
			return new EntityFilteringFormula(
				"attribute in set filter",
				createAlternativeBitmapFilter(filterByVisitor, attributeName, comparedValues, result -> result == 0)
			);
		}
	}

}
