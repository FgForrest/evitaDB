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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.api.requestResponse.data.AssociatedDataContract;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaProvider;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.entity.RestEntityDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.entity.SectionedAssociatedDataDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.entity.SectionedAttributesDescriptor;
import io.evitadb.externalApi.rest.api.resolver.serializer.ObjectJsonSerializer;
import io.evitadb.externalApi.rest.exception.RestQueryResolvingInternalError;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;

/**
 * Handles serializing of Evita entity into JSON structure
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class EntityJsonSerializer {

	protected final boolean localized;
	protected final ObjectJsonSerializer objectJsonSerializer;

	/**
	 * Creates new instance of {@link EntityJsonSerializer}
	 * @param localized whether the entity is serialized in localized form, and thus, the result object doesn't need to distinguish between global and localized attributes
	 * @param objectMapper object mapper used for serialization
	 */
	public EntityJsonSerializer(boolean localized, @Nonnull ObjectMapper objectMapper) {
		this.localized = localized;
		this.objectJsonSerializer = new ObjectJsonSerializer(objectMapper);
	}

	/**
	 * Performs serialization and returns serialized entity in form of JsonNode
	 *
	 * @return serialized entity or list of entities
	 */
	public JsonNode serialize(@Nonnull EntityClassifier entityClassifier, @Nonnull CatalogSchemaContract catalogSchema) {
		return serializeSingleEntity(entityClassifier, catalogSchema);
	}

	/**
	 * Performs serialization and returns serialized entity in form of JsonNode
	 *
	 * @return serialized entity or list of entities
	 */
	public JsonNode serialize(@Nonnull List<EntityClassifier> entityClassifiers, @Nonnull CatalogSchemaContract catalogSchema) {
		final ArrayNode arrayNode = objectJsonSerializer.arrayNode();
		for (EntityClassifier classifier : entityClassifiers) {
			arrayNode.add(serializeSingleEntity(classifier, catalogSchema));
		}
		return arrayNode;
	}

	/**
	 * Performs serialization and returns serialized entity in form of JsonNode
	 *
	 * @return serialized entity or list of entities
	 */
	public JsonNode serialize(@Nonnull EntityClassifier[] entityClassifiers, @Nonnull CatalogSchemaContract catalogSchema) {
		return serialize(Arrays.asList(entityClassifiers), catalogSchema);
	}

	@Nonnull
	private ObjectNode serializeSingleEntity(@Nonnull EntityClassifier entityClassifier, @Nonnull CatalogSchemaContract catalogSchema) {
		final ObjectNode rootNode = serializeEntityClassifier(entityClassifier);
		if (entityClassifier instanceof EntityContract entity) {
			final EntitySchemaContract entitySchema = catalogSchema.getEntitySchemaOrThrowException(entity.getType());
			serializeEntityBody(rootNode, entity, catalogSchema);
			serializeAttributes(rootNode, entity.getLocales(), entity, entitySchema, entitySchema);
			serializeAssociatedData(rootNode, entity.getLocales(), entity, entitySchema);
			serializePrices(rootNode, entity);
			serializeReferences(rootNode, entity, catalogSchema, entitySchema);
		} else if (entityClassifier instanceof EntityClassifierWithParent entity) {
			entity.getParentEntity().ifPresent(parent ->
				rootNode.putIfAbsent(RestEntityDescriptor.PARENT_ENTITY.name(), serializeSingleEntity(parent, catalogSchema)));
		}
		return rootNode;
	}

	@Nonnull
	private ObjectNode serializeEntityClassifier(@Nonnull EntityClassifier entity) {
		final ObjectNode rootNode = objectJsonSerializer.objectNode();
		rootNode.put(RestEntityDescriptor.PRIMARY_KEY.name(), entity.getPrimaryKey());
		rootNode.put(RestEntityDescriptor.TYPE.name(), entity.getType());
		return rootNode;
	}

	/**
	 * Serialize body of entity
	 */
	private void serializeEntityBody(@Nonnull ObjectNode rootNode, @Nonnull EntityContract entity, @Nonnull CatalogSchemaContract catalogSchema) {
		rootNode.put(RestEntityDescriptor.VERSION.name(), entity.version());

		if (entity.parentAvailable()) {
			entity.getParentEntity().ifPresent(parent -> rootNode.putIfAbsent(RestEntityDescriptor.PARENT_ENTITY.name(), serializeSingleEntity(parent, catalogSchema)));
		}

		if (!entity.getLocales().isEmpty()) {
			rootNode.putIfAbsent(RestEntityDescriptor.LOCALES.name(), objectJsonSerializer.serializeObject(entity.getLocales()));
		}
		if (!entity.getAllLocales().isEmpty()) {
			rootNode.putIfAbsent(RestEntityDescriptor.ALL_LOCALES.name(), objectJsonSerializer.serializeObject(entity.getAllLocales()));
		}

		if (entity.getPriceInnerRecordHandling() != PriceInnerRecordHandling.UNKNOWN) {
			rootNode.putIfAbsent(RestEntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), objectJsonSerializer.serializeObject(entity.getPriceInnerRecordHandling()));
		}
	}

	/**
	 * Serialize attributes
	 */
	private void serializeAttributes(@Nonnull ObjectNode rootNode,
	                                 @Nonnull Set<Locale> locales,
	                                 @Nonnull AttributesContract<?> attributes,
									 @Nonnull NamedSchemaContract parentSchema,
	                                 @Nonnull AttributeSchemaProvider<?> attributeSchemaProvider) {
		if (attributes.attributesAvailable() && !attributes.getAttributeKeys().isEmpty()) {
			final ObjectNode attributesNode = objectJsonSerializer.objectNode();
			rootNode.putIfAbsent(RestEntityDescriptor.ATTRIBUTES.name(), attributesNode);
			final Set<AttributeKey> attributeKeys = attributes.getAttributeKeys();
			if (localized) {
				writeAttributesIntoNode(attributesNode, attributeKeys, attributes, parentSchema, attributeSchemaProvider);
			} else {
				final Map<String, List<AttributeKey>> localeSeparatedKeys = separateAttributeKeysByLocale(locales, attributeKeys);

				final List<AttributeKey> globalAttributes = localeSeparatedKeys.remove(SectionedAttributesDescriptor.GLOBAL.name());
				if(!globalAttributes.isEmpty()) {
					final ObjectNode globalNode = objectJsonSerializer.objectNode();
					attributesNode.putIfAbsent(SectionedAttributesDescriptor.GLOBAL.name(), globalNode);
					writeAttributesIntoNode(globalNode, globalAttributes, attributes, parentSchema, attributeSchemaProvider);
				}

				final ObjectNode localizedNode = objectJsonSerializer.objectNode();
				for (Entry<String, List<AttributeKey>> entry : localeSeparatedKeys.entrySet()) {
					final ObjectNode langNode = objectJsonSerializer.objectNode();
					writeAttributesIntoNode(langNode, entry.getValue(), attributes, parentSchema, attributeSchemaProvider);
					if(!langNode.isEmpty()) {
						localizedNode.putIfAbsent(entry.getKey(), langNode);
					}
				}
				if(!localizedNode.isEmpty()) {
					attributesNode.putIfAbsent(SectionedAttributesDescriptor.LOCALIZED.name(), localizedNode);
				}
			}
		}
	}

	/**
	 * Serialize associated data
	 */
	private void serializeAssociatedData(@Nonnull ObjectNode rootNode,
	                                     @Nonnull Set<Locale> locales,
	                                     @Nonnull AssociatedDataContract associatedData,
	                                     @Nonnull EntitySchemaContract entitySchema) {
		if (associatedData.associatedDataAvailable() && !associatedData.getAssociatedDataKeys().isEmpty()) {
			final ObjectNode associatedDataNode = objectJsonSerializer.objectNode();
			final Set<AssociatedDataKey> associatedDataKeys = associatedData.getAssociatedDataKeys();
			if (localized) {
				writeAssociatedDataIntoNode(associatedDataNode, entitySchema, associatedDataKeys, associatedData);
			} else {
				final Map<String, List<AssociatedDataKey>> localeSeparatedKeys = separateAssociatedDataKeysByLocale(locales, associatedDataKeys);

				final List<AssociatedDataKey> globalAssociatedData = localeSeparatedKeys.remove(SectionedAssociatedDataDescriptor.GLOBAL.name());
				if(!globalAssociatedData.isEmpty()) {
					final ObjectNode globalNode = objectJsonSerializer.objectNode();
					associatedDataNode.putIfAbsent(SectionedAssociatedDataDescriptor.GLOBAL.name(), globalNode);
					writeAssociatedDataIntoNode(globalNode, entitySchema, globalAssociatedData, associatedData);
				}

				final ObjectNode localizedNode = objectJsonSerializer.objectNode();
				for (Entry<String, List<AssociatedDataKey>> entry : localeSeparatedKeys.entrySet()) {
					final ObjectNode langNode = objectJsonSerializer.objectNode();
					writeAssociatedDataIntoNode(langNode, entitySchema, entry.getValue(), associatedData);
					if(!langNode.isEmpty()) {
						localizedNode.putIfAbsent(entry.getKey(), langNode);
					}
				}
				if(!localizedNode.isEmpty()) {
					associatedDataNode.putIfAbsent(SectionedAssociatedDataDescriptor.LOCALIZED.name(), localizedNode);
				}
			}
			if(!associatedDataNode.isEmpty()) {
				rootNode.putIfAbsent(RestEntityDescriptor.ASSOCIATED_DATA.name(), associatedDataNode);
			}
		}
	}

	/**
	 * Serialize references
	 */
	protected void serializeReferences(@Nonnull ObjectNode rootNode,
	                                   @Nonnull EntityContract entity,
	                                   @Nonnull CatalogSchemaContract catalogSchema,
	                                   @Nonnull EntitySchemaContract entitySchema) {
		if (entity.referencesAvailable() && !entity.getReferences().isEmpty()) {
			entity.getReferences()
				.stream()
				.map(ReferenceContract::getReferenceName)
				.collect(Collectors.toCollection(TreeSet::new))
				.forEach(it -> serializeReferencesWithSameName(rootNode, entity, it, catalogSchema, entitySchema));
		}
	}

	/**
	 * Serialize references of same name
	 */
	protected void serializeReferencesWithSameName(@Nonnull ObjectNode rootNode,
	                                               @Nonnull EntityContract entity,
	                                               @Nonnull String referenceName,
	                                               @Nonnull CatalogSchemaContract catalogSchema,
	                                               @Nonnull EntitySchemaContract entitySchema) {
		final Collection<ReferenceContract> groupedReferences = entity.getReferences(referenceName);
		final Optional<ReferenceContract> anyReferenceFound = groupedReferences.stream().findFirst();
		if (anyReferenceFound.isPresent()) {
			final ReferenceContract firstReference = anyReferenceFound.get();
			final String nodeReferenceName = firstReference.getReferenceSchema()
				.map(it -> it.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
				.orElse(referenceName);

			if (firstReference.getReferenceCardinality() == Cardinality.EXACTLY_ONE ||
				firstReference.getReferenceCardinality() == Cardinality.ZERO_OR_ONE) {
				Assert.isPremiseValid(groupedReferences.size() == 1, "Reference cardinality is: " +
					firstReference.getReferenceCardinality() + " but found " + groupedReferences.size() +
					" references with same name: " + referenceName);

				rootNode.putIfAbsent(nodeReferenceName, serializeSingleReference(entity.getLocales(), firstReference, catalogSchema, entitySchema));
			} else {
				final ArrayNode referencesNode = objectJsonSerializer.arrayNode();
				rootNode.putIfAbsent(nodeReferenceName, referencesNode);

				for (ReferenceContract groupedReference : groupedReferences) {
					referencesNode.add(serializeSingleReference(entity.getLocales(), groupedReference, catalogSchema, entitySchema));
				}
			}
		}
	}

	/**
	 * Serializes single reference
	 */
	@Nonnull
	private ObjectNode serializeSingleReference(@Nonnull Set<Locale> locales,
	                                            @Nonnull ReferenceContract reference,
	                                            @Nonnull CatalogSchemaContract catalogSchema,
	                                            @Nonnull EntitySchemaContract entitySchema) {
		final ObjectNode referenceNode = objectJsonSerializer.objectNode();

		referenceNode.putIfAbsent(ReferenceDescriptor.REFERENCED_PRIMARY_KEY.name(), objectJsonSerializer.serializeObject(reference.getReferencedPrimaryKey()));

		reference.getReferencedEntity().ifPresent(sealedEntity ->
			referenceNode.putIfAbsent(ReferenceDescriptor.REFERENCED_ENTITY.name(), serializeSingleEntity(sealedEntity, catalogSchema)));

		reference.getGroupEntity()
			.map(it -> (EntityClassifier) it)
			.or(reference::getGroup)
			.ifPresent(groupEntity -> {
				referenceNode.putIfAbsent(ReferenceDescriptor.GROUP_ENTITY.name(), serializeSingleEntity(groupEntity, catalogSchema));
			});

		final ReferenceSchemaContract referenceSchema = reference.getReferenceSchema()
			.orElseThrow(() -> new RestQueryResolvingInternalError("Cannot find reference schema for `" + reference.getReferenceName() + "` in entity schema `" + entitySchema.getName() + "`."));
		serializeAttributes(referenceNode, locales, reference, referenceSchema, referenceSchema);

		return referenceNode;
	}

	/**
	 * Serialize prices
	 */
	private void serializePrices(@Nonnull ObjectNode rootNode, @Nonnull EntityContract entity) {
		if (entity.pricesAvailable()) {
			final Collection<PriceContract> prices = entity.getPrices();
			final ArrayNode pricesNode = objectJsonSerializer.arrayNode();
			rootNode.putIfAbsent(RestEntityDescriptor.PRICES.name(), pricesNode);

			for (PriceContract price : prices) {
				pricesNode.add(objectJsonSerializer.serializeObject(price));
			}

			entity.getPriceForSaleIfAvailable()
				.ifPresent(it -> rootNode.putIfAbsent(RestEntityDescriptor.PRICE_FOR_SALE.name(), objectJsonSerializer.serializeObject(it)));
		}
	}

	private void writeAttributesIntoNode(@Nonnull ObjectNode attributesNode,
	                                     @Nonnull Collection<AttributeKey> attributeKeys,
	                                     @Nonnull AttributesContract<?> attributes,
										 @Nonnull NamedSchemaContract parentSchema,
	                                     @Nonnull AttributeSchemaProvider<?> attributeSchemaProvider) {
		for (AttributeKey attributeKey : attributeKeys) {
			final String attributeName = attributeKey.attributeName();
			final Optional<AttributeValue> attributeValue = attributeKey.localized() ?
				attributes.getAttributeValue(attributeName, attributeKey.locale()) :
				attributes.getAttributeValue(attributeName);

			final String serializableAttributeName = attributeSchemaProvider.getAttribute(attributeName)
				.map(it -> it.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
				.orElseThrow(() -> new RestQueryResolvingInternalError("Cannot find attribute schema for `" + attributeName + "` in entity schema `" + parentSchema.getName() + "`."));

			if(attributeValue.isPresent() && attributeValue.get().value() != null) {
				attributesNode.putIfAbsent(serializableAttributeName, objectJsonSerializer.serializeObject(attributeValue.get().value()));
			} else {
				attributesNode.putIfAbsent(serializableAttributeName, null);
			}
		}
	}

	private void writeAssociatedDataIntoNode(@Nonnull ObjectNode attributesNode,
	                                         @Nonnull EntitySchemaContract entitySchema,
	                                         @Nonnull Collection<AssociatedDataKey> associatedDataKeys,
	                                         @Nonnull AssociatedDataContract associatedData) {
		for (AssociatedDataKey associatedDataKey : associatedDataKeys) {
			final String associatedDataName = associatedDataKey.associatedDataName();
			final Optional<AssociatedDataValue> associatedDataValue = associatedDataKey.localized() ?
				associatedData.getAssociatedDataValue(associatedDataName, associatedDataKey.locale()) :
				associatedData.getAssociatedDataValue(associatedDataName);

			final String serializableAssociatedDataName = entitySchema.getAssociatedData(associatedDataName)
				.map(it -> it.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
				.orElseThrow(() -> new RestQueryResolvingInternalError("Cannot find associated data schema for `" + associatedDataName + "` in entity schema `" + entitySchema.getName() + "`."));

			if(associatedDataValue.isPresent() && associatedDataValue.get().value() != null) {
				attributesNode.putIfAbsent(serializableAssociatedDataName, objectJsonSerializer.serializeObject(associatedDataValue.get().value()));
			} else {
				attributesNode.putIfAbsent(serializableAssociatedDataName, null);
			}
		}
	}

	@Nonnull
	public static Map<String, List<AttributeKey>> separateAttributeKeysByLocale(@Nonnull Set<Locale> locales,
	                                                                            @Nonnull Set<AttributeKey> attributeKeys) {
		final Map<String, List<AttributeKey>> localeSeparatedKeys = new HashMap<>(locales.size() + 1);
		localeSeparatedKeys.put(SectionedAttributesDescriptor.GLOBAL.name(), new LinkedList<>());
		for (Locale locale : locales) {
			localeSeparatedKeys.put(locale.toLanguageTag(), new LinkedList<>());
		}
		for (AttributeKey attributeKey : attributeKeys) {
			if (attributeKey.localized()) {
				final List<AttributeKey> localizedKeys = localeSeparatedKeys.get(attributeKey.locale().toLanguageTag());
				localizedKeys.add(attributeKey);
			} else {
				final List<AttributeKey> globalKeys = localeSeparatedKeys.get(SectionedAttributesDescriptor.GLOBAL.name());
				globalKeys.add(attributeKey);
			}
		}
		return localeSeparatedKeys;
	}

	@Nonnull
	public static Map<String, List<AssociatedDataKey>> separateAssociatedDataKeysByLocale(@Nonnull Set<Locale> locales,
	                                                                                      @Nonnull Set<AssociatedDataKey> associatedDataKeys) {
		final Map<String, List<AssociatedDataKey>> localeSeparatedKeys = new HashMap<>(locales.size() + 1);
		localeSeparatedKeys.put(SectionedAssociatedDataDescriptor.GLOBAL.name(), new LinkedList<>());
		for (Locale locale : locales) {
			localeSeparatedKeys.put(locale.toLanguageTag(), new LinkedList<>());
		}
		for (AssociatedDataKey associatedDataKey : associatedDataKeys) {
			if (associatedDataKey.localized()) {
				final List<AssociatedDataKey> localizedKeys = localeSeparatedKeys.get(associatedDataKey.locale().toLanguageTag());
				localizedKeys.add(associatedDataKey);
			} else {
				final List<AssociatedDataKey> globalKeys = localeSeparatedKeys.get(SectionedAssociatedDataDescriptor.GLOBAL.name());
				globalKeys.add(associatedDataKey);
			}
		}
		return localeSeparatedKeys;
	}
}
