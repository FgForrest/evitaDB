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

package io.evitadb.api.query.expression.object.accessor.entity;

import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.query.expression.object.accessor.ObjectElementAccessor;
import io.evitadb.api.query.expression.object.accessor.entity.EntityContractAccessor.EntityAttributesEvaluationDto;
import io.evitadb.api.query.expression.object.accessor.entity.ReferenceContractAccessor.ReferenceAttributesEvaluationDto;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.exception.ExpressionEvaluationException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Implementation of {@link ObjectElementAccessor} for {@link AttributesContract} objects.
 * Enables bracket-access expressions in EvitaEL to retrieve attribute values by name,
 * e.g. `entity.attributes['code']` or `entity.localizedAttributes['name']`.
 *
 * The accessor supports both entity-level attributes (via
 * {@link EntityContractAccessor.EntityAttributesEvaluationDto}) and reference-level attributes
 * (via {@link ReferenceContractAccessor.ReferenceAttributesEvaluationDto}). Each DTO carries
 * a flag indicating whether localized or non-localized attributes were requested. For localized
 * attributes, the accessor returns an unmodifiable {@link Map} keyed by {@link Locale} with
 * the corresponding values. For non-localized attributes, the raw value is returned directly.
 *
 * @see ObjectElementAccessor
 * @see EntityContractAccessor
 * @see ReferenceContractAccessor
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class AttributesContractAccessor implements ObjectElementAccessor {

	@Nonnull
	@Override
	public Class<? extends Serializable>[] getSupportedTypes() {
		//noinspection unchecked
		return new Class[] { AttributesContract.class };
	}

	@Nullable
	@Override
	public Serializable get(
		@Nonnull Serializable object,
		@Nonnull String elementName
	) throws ExpressionEvaluationException {
		if (!(object instanceof AttributesContract<?> attributesContract)) {
			throw new ExpressionEvaluationException(
				"Cannot access element by name on object of type `" + object.getClass().getName() + "`. Expected AttributesContract.",
				"Cannot access element."
			);
		}

		try {
			final AttributeSchemaContract attributeSchema = attributesContract.getAttributeSchema(elementName)
				.orElseThrow(() -> new ExpressionEvaluationException(
					"Attribute schema for element `" + elementName + "` not found.",
					"Attribute `" + elementName + "` not found."
				));

			if (attributesContract instanceof EntityAttributesEvaluationDto entityAttributes) {
				return getAttribute(
					elementName,
					attributeSchema,
					entityAttributes,
					entityAttributes.requestedLocalizedAttributes(),
					() -> entityAttributes.delegate().getAttributeLocales()
				);
			} else if (attributesContract instanceof ReferenceAttributesEvaluationDto referenceAttributes) {
				return getAttribute(
					elementName,
					attributeSchema,
					referenceAttributes,
					referenceAttributes.requestedLocalizedAttributes(),
					() -> referenceAttributes.delegate().getAttributeLocales()
				);
			} else {
				throw new ExpressionEvaluationException(
					"Cannot access attribute `" + elementName + "` on AttributesContract implementation `" + attributesContract.getClass().getSimpleName() + "`.",
					"Cannot access attribute `" + elementName + "`."
				);
			}
		} catch (ContextMissingException | AttributeNotFoundException e) {
			throw new ExpressionEvaluationException(
				"Cannot access attribute `" + elementName + "` on AttributesContract: " + e.getMessage(),
				"Cannot found non-localized attribute `" + elementName + "`.",
				e
			);
		}
	}

	/**
	 * Retrieves the attribute value for the given name. When a localized attribute is
	 * requested, returns an unmodifiable {@link Map} of {@link Locale} to value entries
	 * across all available locales. When a non-localized attribute is requested, returns
	 * the raw value directly. Throws if the localization expectation does not match the schema.
	 */
	@Nullable
	private static Serializable getAttribute(
		@Nonnull String attributeName,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull AttributesContract<?> attributesProvider,
		boolean requestedLocalizedAttribute,
		@Nonnull Supplier<Set<Locale>> availableLocalesProvider
	) {
		if (requestedLocalizedAttribute) {
			Assert.isTrue(
				attributeSchema.isLocalized(),
				() -> new ExpressionEvaluationException(
					"Attribute `" + attributeName + "` is not localized. Use `.attributes['" + attributeName + "']` instead to access non-localized attribute."
				)
			);

			final Set<Locale> availableLocales = availableLocalesProvider.get();
			final Map<Object, Object> attributesMap = createHashMap(availableLocales.size());
			for (final Locale locale : availableLocales) {
				attributesMap.put(locale, attributesProvider.getAttribute(attributeName, locale));
			}
			return (Serializable) Collections.unmodifiableMap(attributesMap);
		} else {
			Assert.isTrue(
				!attributeSchema.isLocalized(),
				() -> new ExpressionEvaluationException(
					"Attribute `" + attributeName + "` is localized. Use `.localizedAttributes['" + attributeName + "']` instead to access localized attribute."
				)
			);

			return attributesProvider.getAttribute(attributeName);
		}
	}
}
