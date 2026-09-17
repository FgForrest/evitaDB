/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.api.query.require.HierarchyContent;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.AssociatedDataContract;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.schema.AttributeSchemaProvider;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.PlainChunk;
import io.evitadb.dataType.StripList;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.attribute.AttributesProviderDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference.ReferenceDefinitionDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference.ReferenceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference.ReferenceWithReferencedEntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference.WithNamedReferenceDescriptor;
import io.evitadb.externalApi.api.catalog.model.VersionedDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.entity.RestEntityDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.entity.SectionedAssociatedDataDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.entity.SectionedAttributesDescriptor;
import io.evitadb.externalApi.rest.api.resolver.serializer.ObjectJsonSerializer;
import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.exception.RestQueryResolvingInternalError;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.Map.Entry;

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
	protected final DataChunkJsonSerializer dataChunkJsonSerializer;

	/**
	 * Creates new instance of {@link EntityJsonSerializer}
	 * @param localized whether the entity is serialized in localized form, and thus, the result object doesn't need to distinguish between global and localized attributes
	 * @param objectMapper object mapper used for serialization
	 */
	public EntityJsonSerializer(boolean localized, @Nonnull ObjectMapper objectMapper) {
		this.localized = localized;
		this.objectJsonSerializer = new ObjectJsonSerializer(objectMapper);
		this.dataChunkJsonSerializer = new DataChunkJsonSerializer(this.objectJsonSerializer);
	}

	/**
	 * Performs serialization and returns serialized entity in form of JsonNode
	 *
	 * @return serialized entity or list of entities
	 */
	public JsonNode serialize(@Nonnull EntitySerializationContext ctx, @Nonnull EntityClassifier entityClassifier) {
		return serializeSingleEntity(ctx, entityClassifier);
	}

	/**
	 * Performs serialization and returns serialized entity in form of JsonNode
	 *
	 * @return serialized entity or list of entities
	 */
	public JsonNode serialize(@Nonnull EntitySerializationContext ctx, @Nonnull List<EntityClassifier> entityClassifiers) {
		final ArrayNode arrayNode = this.objectJsonSerializer.arrayNode();
		for (EntityClassifier classifier : entityClassifiers) {
			arrayNode.add(serializeSingleEntity(ctx, classifier));
		}
		return arrayNode;
	}

	/**
	 * Performs serialization and returns serialized entity in form of JsonNode
	 *
	 * @return serialized entity or list of entities
	 */
	public JsonNode serialize(@Nonnull EntitySerializationContext ctx, @Nonnull EntityClassifier[] entityClassifiers) {
		return serialize(ctx, Arrays.asList(entityClassifiers));
	}

	@Nonnull
	private ObjectNode serializeSingleEntity(@Nonnull EntitySerializationContext ctx, @Nonnull EntityClassifier entityClassifier) {
		final ObjectNode rootNode = serializeEntityWithoutParentAxis(ctx, entityClassifier);
		if (entityClassifier instanceof EntityClassifierWithParent entity) {
			serializeParentAxis(ctx, rootNode, entity);
		}
		return rootNode;
	}

	/**
	 * Serializes everything an entity carries except the chain of its ancestors, which is written separately because
	 * the same ancestor may have to be reported through two different properties.
	 *
	 * @param ctx              context of the serialization
	 * @param entityClassifier the entity or the mere pointer at one
	 * @return the serialized entity, with no parent property of any kind
	 */
	@Nonnull
	private ObjectNode serializeEntityWithoutParentAxis(@Nonnull EntitySerializationContext ctx,
	                                                    @Nonnull EntityClassifier entityClassifier) {
		final ObjectNode rootNode = serializeEntityClassifier(entityClassifier);
		if (entityClassifier instanceof EntityDecorator entity) {
			final EntitySchemaContract entitySchema = ctx.getCatalogSchema().getEntitySchemaOrThrowException(entity.getType());
			serializeEntityBody(rootNode, entity);
			serializeAttributes(rootNode, entity.getLocales(), entity, entitySchema, entitySchema);
			serializeAssociatedData(rootNode, entity.getLocales(), entity, entitySchema);
			serializePrices(rootNode, entity);
			serializeReferences(ctx, rootNode, entity, entitySchema);
		}
		return rootNode;
	}

	/**
	 * Writes the chain of ancestors of `entity` into `rootNode`.
	 *
	 * {@link RestEntityDescriptor#PARENT_ENTITY} is documented to contain materialized ancestors only, so it reports
	 * the chain cut below the first ancestor whose requested body could not be materialized - which is exactly what
	 * {@link io.evitadb.api.query.require.HierarchyParentsBehaviour#MATCHING} returns.
	 * {@link RestEntityDescriptor#PARENT_ENTITY_COMPLETE} reports the whole chain including the bodyless pointers, and
	 * is written only when the chain actually holds one, since otherwise the two would be identical.
	 *
	 * Whether a chain element that is not an {@link EntityDecorator} is a bodyless pointer or a plain primary key of
	 * a `hierarchyContent()` that asked for no ancestor body at all is read off the requirement the entity was
	 * fetched with - see {@link #ancestorBodiesRequested(EntitySerializationContext, EntityClassifierWithParent)}.
	 * When bodies were asked for and the immediate parent is a pointer, the cut yields nothing and
	 * {@link RestEntityDescriptor#PARENT_ENTITY} is omitted altogether rather than being handed a chain it cannot
	 * type; nothing is lost by that, since the caller reads such a chain through
	 * {@link RestEntityDescriptor#PARENT_ENTITY_COMPLETE}.
	 *
	 * @param ctx      context of the serialization
	 * @param rootNode the node of the entity whose ancestors are written
	 * @param entity   the entity whose ancestors are written
	 */
	private void serializeParentAxis(@Nonnull EntitySerializationContext ctx,
	                                 @Nonnull ObjectNode rootNode,
	                                 @Nonnull EntityClassifierWithParent entity) {
		final EntityClassifierWithParent parent = resolveParent(entity);
		if (parent == null) {
			return;
		}
		if (!ancestorBodiesRequested(ctx, parent)) {
			rootNode.putIfAbsent(
				RestEntityDescriptor.PARENT_ENTITY.name(),
				serializeParentChain(ctx, parent, RestEntityDescriptor.PARENT_ENTITY.name(), false)
			);
			return;
		}
		final ObjectNode matchingChain = serializeParentChain(
			ctx, parent, RestEntityDescriptor.PARENT_ENTITY.name(), true
		);
		if (matchingChain != null) {
			rootNode.putIfAbsent(RestEntityDescriptor.PARENT_ENTITY.name(), matchingChain);
		}
		// the two chains coincide unless the walk actually passed a bodyless ancestor, and a property repeating its
		// sibling verbatim tells the caller nothing
		if (holdsBodylessAncestor(parent)) {
			rootNode.putIfAbsent(
				RestEntityDescriptor.PARENT_ENTITY_COMPLETE.name(),
				serializeParentChain(ctx, parent, RestEntityDescriptor.PARENT_ENTITY_COMPLETE.name(), false)
			);
		}
	}

	/**
	 * Serializes one ancestor together with everything above it, linking the elements through `parentPropertyName` so
	 * that the whole chain is walkable through a single property.
	 *
	 * @param ctx                context of the serialization
	 * @param ancestor           the ancestor to serialize
	 * @param parentPropertyName the property the next ancestor is written into
	 * @param cutBelowBodyless   whether to end the chain below an ancestor whose body was not materialized
	 * @return the serialized ancestor, or NULL when the chain is cut below it
	 */
	@Nullable
	private ObjectNode serializeParentChain(@Nonnull EntitySerializationContext ctx,
	                                        @Nonnull EntityClassifierWithParent ancestor,
	                                        @Nonnull String parentPropertyName,
	                                        boolean cutBelowBodyless) {
		// `EntityDecorator` rather than `SealedEntity`, because it is what `serializeEntityWithoutParentAxis` can
		// write a body from - a plain `Entity` would serialize as a bare classifier here. It must nevertheless keep
		// agreeing with `ParentsDataFetcher`'s `SealedEntity` predicate, which classifies the same ancestors for
		// GraphQL; widening one without widening the other makes the two channels disagree about the same data.
		if (cutBelowBodyless && !(ancestor instanceof EntityDecorator)) {
			return null;
		}
		final ObjectNode ancestorNode = serializeEntityWithoutParentAxis(ctx, ancestor);
		final EntityClassifierWithParent parent = resolveParent(ancestor);
		if (parent != null) {
			final ObjectNode parentNode = serializeParentChain(ctx, parent, parentPropertyName, cutBelowBodyless);
			if (parentNode != null) {
				ancestorNode.putIfAbsent(parentPropertyName, parentNode);
			}
		}
		return ancestorNode;
	}

	/**
	 * Returns TRUE when the `hierarchyContent` the chain was fetched with asked for ancestor bodies, and therefore
	 * when a chain element that is not an {@link EntityDecorator} is a bodyless pointer rather than a plain primary
	 * key of a requirement that never asked for a body at all.
	 *
	 * The requirement itself answers the question wherever it could be supplied, and every path that reports an
	 * ancestor axis supplies one: the entity-fetch endpoints read it off the query they executed, and an axis
	 * reported inside an extra result is served by the requirement of the constraint that produced it - the
	 * `entityFetch` of the hierarchy constraint, of the reference / facet summary, or of the histogram whose anchor
	 * entities are being written. Where none could be resolved after all, the answer is inferred from the chain:
	 * only a requirement asking for bodies can have materialized any of them, so a chain mixing materialized
	 * ancestors with bodyless ones proves the request. A chain mixing nothing proves nothing and is read as the far
	 * more common bodyless request, which reports it whole and hands back genuine primary keys rather than typing
	 * them as pointers on a guess.
	 *
	 * @param ctx       context of the serialization
	 * @param chainHead the immediate parent of the entity whose axis is being reported
	 * @return TRUE when a chain element that is not an {@link EntityDecorator} is to be read as a bodyless pointer
	 */
	private static boolean ancestorBodiesRequested(@Nonnull EntitySerializationContext ctx,
	                                               @Nonnull EntityClassifierWithParent chainHead) {
		final HierarchyContent hierarchyContent = ctx.resolveHierarchyContent();
		if (hierarchyContent != null) {
			return hierarchyContent.getEntityFetch().isPresent();
		}
		return holdsMaterializedAncestor(chainHead) && holdsBodylessAncestor(chainHead);
	}

	/**
	 * Returns TRUE when the chain starting at `chainHead` holds an ancestor that carries no body - either a pointer
	 * standing in for a body that could not be materialized, or a plain primary key of a requirement that asked for
	 * no body at all.
	 *
	 * @param chainHead the immediate parent of the entity whose axis is being reported
	 * @return TRUE when the chain holds a bodyless ancestor
	 */
	private static boolean holdsBodylessAncestor(@Nonnull EntityClassifierWithParent chainHead) {
		EntityClassifierWithParent ancestor = chainHead;
		while (ancestor != null) {
			// `EntityDecorator` for the same reason as in `serializeParentChain`: it is the type the body writer can
			// handle, and it must track `ParentsDataFetcher`'s predicate
			if (!(ancestor instanceof EntityDecorator)) {
				return true;
			}
			ancestor = resolveParent(ancestor);
		}
		return false;
	}

	/**
	 * Returns TRUE when the chain starting at `chainHead` holds an ancestor that carries the body that was asked for.
	 *
	 * @param chainHead the immediate parent of the entity whose axis is being reported
	 * @return TRUE when the chain holds a materialized ancestor
	 */
	private static boolean holdsMaterializedAncestor(@Nonnull EntityClassifierWithParent chainHead) {
		EntityClassifierWithParent ancestor = chainHead;
		while (ancestor != null) {
			if (ancestor instanceof EntityDecorator) {
				return true;
			}
			ancestor = resolveParent(ancestor);
		}
		return false;
	}

	/**
	 * Returns the next ancestor above `entity`, or NULL when there is none - including when the entity was fetched
	 * without its hierarchy placement and therefore cannot answer the question at all.
	 *
	 * @param entity the entity whose ancestor is looked up
	 * @return the next ancestor, or NULL
	 */
	@Nullable
	private static EntityClassifierWithParent resolveParent(@Nonnull EntityClassifierWithParent entity) {
		if (entity instanceof EntityDecorator entityDecorator && !entityDecorator.parentAvailable()) {
			return null;
		}
		return entity.getParentEntity().orElse(null);
	}

	@Nonnull
	private ObjectNode serializeEntityClassifier(@Nonnull EntityClassifier entity) {
		final ObjectNode rootNode = this.objectJsonSerializer.objectNode();
		rootNode.set(EntityDescriptor.PRIMARY_KEY.name(), this.objectJsonSerializer.serializeObject(Objects.requireNonNull(entity.getPrimaryKey())));
		rootNode.set(EntityDescriptor.TYPE.name(), this.objectJsonSerializer.serializeObject(entity.getType()));
		return rootNode;
	}

	/**
	 * Serialize body of entity. The chain of ancestors is not part of it - see
	 * {@link #serializeParentAxis(EntitySerializationContext, ObjectNode, EntityClassifierWithParent)}.
	 */
	private void serializeEntityBody(@Nonnull ObjectNode rootNode,
	                                 @Nonnull EntityDecorator entity) {
		rootNode.set(VersionedDescriptor.VERSION.name(), this.objectJsonSerializer.serializeObject(entity.version()));
		rootNode.set(EntityDescriptor.SCOPE.name(), this.objectJsonSerializer.serializeObject(entity.getScope()));

		if (!entity.getLocales().isEmpty()) {
			rootNode.putIfAbsent(EntityDescriptor.LOCALES.name(), this.objectJsonSerializer.serializeObject(entity.getLocales()));
		}
		if (!entity.getAllLocales().isEmpty()) {
			rootNode.putIfAbsent(EntityDescriptor.ALL_LOCALES.name(), this.objectJsonSerializer.serializeObject(entity.getAllLocales()));
		}

		if (entity.getPriceInnerRecordHandling() != PriceInnerRecordHandling.UNKNOWN) {
			rootNode.putIfAbsent(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.name(), this.objectJsonSerializer.serializeObject(entity.getPriceInnerRecordHandling()));
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
			final ObjectNode attributesNode = this.objectJsonSerializer.objectNode();
			rootNode.putIfAbsent(AttributesProviderDescriptor.ATTRIBUTES.name(), attributesNode);
			final Set<AttributeKey> attributeKeys = attributes.getAttributeKeys();
			if (this.localized) {
				writeAttributesIntoNode(attributesNode, attributeKeys, attributes, parentSchema, attributeSchemaProvider);
			} else {
				final Map<String, List<AttributeKey>> localeSeparatedKeys = separateAttributeKeysByLocale(locales, attributeKeys);

				final List<AttributeKey> globalAttributes = localeSeparatedKeys.remove(SectionedAttributesDescriptor.GLOBAL.name());
				if(!globalAttributes.isEmpty()) {
					final ObjectNode globalNode = this.objectJsonSerializer.objectNode();
					attributesNode.putIfAbsent(SectionedAttributesDescriptor.GLOBAL.name(), globalNode);
					writeAttributesIntoNode(globalNode, globalAttributes, attributes, parentSchema, attributeSchemaProvider);
				}

				final ObjectNode localizedNode = this.objectJsonSerializer.objectNode();
				for (Entry<String, List<AttributeKey>> entry : localeSeparatedKeys.entrySet()) {
					final ObjectNode langNode = this.objectJsonSerializer.objectNode();
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
			final ObjectNode associatedDataNode = this.objectJsonSerializer.objectNode();
			final Set<AssociatedDataKey> associatedDataKeys = associatedData.getAssociatedDataKeys();
			if (this.localized) {
				writeAssociatedDataIntoNode(associatedDataNode, entitySchema, associatedDataKeys, associatedData);
			} else {
				final Map<String, List<AssociatedDataKey>> localeSeparatedKeys = separateAssociatedDataKeysByLocale(locales, associatedDataKeys);

				final List<AssociatedDataKey> globalAssociatedData = localeSeparatedKeys.remove(SectionedAssociatedDataDescriptor.GLOBAL.name());
				if(!globalAssociatedData.isEmpty()) {
					final ObjectNode globalNode = this.objectJsonSerializer.objectNode();
					associatedDataNode.putIfAbsent(SectionedAssociatedDataDescriptor.GLOBAL.name(), globalNode);
					writeAssociatedDataIntoNode(globalNode, entitySchema, globalAssociatedData, associatedData);
				}

				final ObjectNode localizedNode = this.objectJsonSerializer.objectNode();
				for (Entry<String, List<AssociatedDataKey>> entry : localeSeparatedKeys.entrySet()) {
					final ObjectNode langNode = this.objectJsonSerializer.objectNode();
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
				rootNode.putIfAbsent(EntityDescriptor.ASSOCIATED_DATA.name(), associatedDataNode);
			}
		}
	}

	/**
	 * Serialize references
	 */
	protected void serializeReferences(@Nonnull EntitySerializationContext ctx,
	                                   @Nonnull ObjectNode rootNode,
	                                   @Nonnull EntityDecorator entity,
	                                   @Nonnull EntitySchemaContract entitySchema) {
		if (entity.referencesAvailable()) {
			entity.getReferenceNames().forEach(referenceName ->
				serializeReferencesWithSameName(ctx, rootNode, entity, referenceName, entitySchema));
		}
	}

	/**
	 * Serialize references of same name
	 */
	protected void serializeReferencesWithSameName(@Nonnull EntitySerializationContext ctx,
	                                               @Nonnull ObjectNode rootNode,
	                                               @Nonnull EntityDecorator entity,
	                                               @Nonnull String referenceName,
	                                               @Nonnull EntitySchemaContract entitySchema) {
		final ReferenceSchemaContract referenceSchema = entitySchema
			.getReference(referenceName)
			.orElseThrow(() -> new OpenApiBuildingError("Schema for reference `" + referenceName + "` not known."));
		final Cardinality referenceCardinality = referenceSchema.getCardinality();

		final DataChunk<ReferenceContract> groupedReferences = entity.getReferenceChunk(referenceName);

		if (referenceCardinality.getMax() == 1) {
			Assert.isPremiseValid(
				groupedReferences instanceof PlainChunk<ReferenceContract> && groupedReferences.getTotalRecordCount() <= 1,
				"Reference cardinality is: " + referenceCardinality + " but found " +
					groupedReferences.getTotalRecordCount() + " references with same name: " + referenceName
			);

			final String referencePropertyName = WithNamedReferenceDescriptor.REFERENCE.name(referenceSchema);
			if (groupedReferences.getData().isEmpty()) {
				rootNode.putIfAbsent(referencePropertyName, null);
			} else {
				rootNode.putIfAbsent(referencePropertyName, serializeSingleReference(ctx, entity.getLocales(), groupedReferences.getData().get(0), entitySchema));
			}
		} else {
			// the switch needs no `default` branch - `DataChunk` is sealed and all three permitted
			// implementations are covered, so javac proves the dispatch exhaustive
			final String referencePropertyName = switch (groupedReferences) {
				case PlainChunk<ReferenceContract> ignored ->
					WithNamedReferenceDescriptor.REFERENCE.name(referenceSchema);
				case PaginatedList<ReferenceContract> ignored ->
					WithNamedReferenceDescriptor.REFERENCE_PAGE.name(referenceSchema);
				case StripList<ReferenceContract> ignored ->
					WithNamedReferenceDescriptor.REFERENCE_STRIP.name(referenceSchema);
			};
			final JsonNode dataChunkNode = this.dataChunkJsonSerializer.serialize(
				groupedReferences,
				groupedReference -> serializeSingleReference(ctx, entity.getLocales(), groupedReference, entitySchema)
			);
			rootNode.putIfAbsent(referencePropertyName, dataChunkNode);
		}
	}

	/**
	 * Serializes single reference
	 */
	@Nonnull
	private ObjectNode serializeSingleReference(@Nonnull EntitySerializationContext ctx,
	                                            @Nonnull Set<Locale> locales,
	                                            @Nonnull ReferenceContract reference,
	                                            @Nonnull EntitySchemaContract entitySchema) {
		final ObjectNode referenceNode = this.objectJsonSerializer.objectNode();

		referenceNode.putIfAbsent(ReferenceDescriptor.REFERENCED_PRIMARY_KEY.name(), this.objectJsonSerializer.serializeObject(reference.getReferencedPrimaryKey()));

		// a referenced entity is fetched with the requirement written inside this `referenceContent`, not with the
		// one the referencing entity was fetched with - the two describe different collections
		reference.getReferencedEntity().ifPresent(sealedEntity ->
			referenceNode.putIfAbsent(
				ReferenceWithReferencedEntityDescriptor.REFERENCED_ENTITY.name(),
				serializeSingleEntity(ctx.forReferencedEntity(reference.getReferenceName()), sealedEntity)
			));

		reference.getGroupEntity()
			.map(EntityClassifier.class::cast)
			.or(reference::getGroup)
			.ifPresent(groupEntity -> referenceNode.putIfAbsent(
				ReferenceDefinitionDescriptor.GROUP_ENTITY.name(),
				serializeSingleEntity(ctx.forGroupEntity(reference.getReferenceName()), groupEntity)
			));

		final ReferenceSchemaContract referenceSchema = reference.getReferenceSchema()
			.orElseThrow(() -> new RestQueryResolvingInternalError("Cannot find reference schema for `" + reference.getReferenceName() + "` in entity schema `" + entitySchema.getName() + "`."));
		serializeAttributes(referenceNode, locales, reference, referenceSchema, referenceSchema);

		return referenceNode;
	}

	/**
	 * Serialize prices
	 */
	private void serializePrices(@Nonnull ObjectNode rootNode, @Nonnull EntityDecorator entity) {
		if (entity.pricesAvailable()) {
			final Collection<PriceContract> prices = entity.getPrices();
			final ArrayNode pricesNode = this.objectJsonSerializer.arrayNode();
			rootNode.putIfAbsent(EntityDescriptor.PRICES.name(), pricesNode);

			for (PriceContract price : prices) {
				pricesNode.add(this.objectJsonSerializer.serializeObject(price));
			}

			entity.getPriceForSaleWithAccompanyingPricesIfAvailable().ifPresent(it -> {
				rootNode.putIfAbsent(EntityDescriptor.PRICE_FOR_SALE.name(), this.objectJsonSerializer.serializeObject(it.priceForSale()));

				// Range fields are emitted unconditionally — even under NONE inner-record handling, where
				// `lowestPrice == highestPrice == priceForSale` and the three values are necessarily identical.
				// The redundancy is intentional: REST clients receive a stable schema that does not depend on
				// the entity's strategy, so generated client code can dereference the bounds without nullness or
				// strategy checks. (Contrast with GraphQL, where the bound fields are opt-in via the selection
				// set and clients explicitly request them.)
				entity.getPriceRangeForSaleIfAvailable().ifPresent(range -> {
					rootNode.putIfAbsent(
						EntityDescriptor.PRICE_FOR_SALE_MIN.name(),
						this.objectJsonSerializer.serializeObject(range.lowestPrice())
					);
					rootNode.putIfAbsent(
						EntityDescriptor.PRICE_FOR_SALE_MAX.name(),
						this.objectJsonSerializer.serializeObject(range.highestPrice())
					);
				});

				final Map<String, Optional<PriceContract>> accompanyingPrices = it.accompanyingPrices();
				if (!accompanyingPrices.isEmpty()) {
					final ObjectNode accompanyingPricesNode = this.objectJsonSerializer.objectNode();
					accompanyingPrices.forEach((accompanyingPriceName, accompanyingPrice) -> accompanyingPricesNode.putIfAbsent(
						accompanyingPriceName,
						accompanyingPrice.map(this.objectJsonSerializer::serializeObject).orElse(null)
					));
					rootNode.putIfAbsent(RestEntityDescriptor.ACCOMPANYING_PRICES.name(), accompanyingPricesNode);
				}

				if (!entity.getPriceInnerRecordHandling().equals(PriceInnerRecordHandling.NONE)) {
					final boolean multiplePricesForSale = hasMultiplePricesForSaleAvailable(entity);
					rootNode.putIfAbsent(EntityDescriptor.MULTIPLE_PRICES_FOR_SALE_AVAILABLE.name(), this.objectJsonSerializer.serializeObject(multiplePricesForSale));
				}
			});
		}
	}

	/**
	 * Resolves whether there are multiple unique prices which the entity could be sold for.
	 */
	private static boolean hasMultiplePricesForSaleAvailable(@Nonnull EntityDecorator entity) {
		final List<PriceContract> allPricesForSale = entity.getAllPricesForSale();
		if (allPricesForSale.size() <= 1) {
			return false;
		}

		final boolean hasMultiplePricesForSale;
		final PriceInnerRecordHandling priceInnerRecordHandling = entity.getPriceInnerRecordHandling();
		if (priceInnerRecordHandling.equals(PriceInnerRecordHandling.LOWEST_PRICE)) {
			if (allPricesForSale.size() <= 1) {
				return false;
			}

			final QueryPriceMode desiredPriceType = entity.getPricePredicate().getQueryPriceMode();
			final long uniquePriceValuesCount = allPricesForSale.stream()
				.map(price -> {
					if (desiredPriceType.equals(QueryPriceMode.WITH_TAX)) {
						return price.priceWithTax();
					} else if (desiredPriceType.equals(QueryPriceMode.WITHOUT_TAX)) {
						return price.priceWithoutTax();
					} else {
						throw new RestInternalError("Unsupported price type `" + desiredPriceType + "`");
					}
				})
				.distinct()
				.count();
			hasMultiplePricesForSale = uniquePriceValuesCount > 1;
		} else if (priceInnerRecordHandling.equals(PriceInnerRecordHandling.SUM)) {
			hasMultiplePricesForSale = allPricesForSale.size() > 1;
		} else {
			hasMultiplePricesForSale = false;
		}

		return hasMultiplePricesForSale;
	}

	private void writeAttributesIntoNode(@Nonnull ObjectNode attributesNode,
	                                     @Nonnull Collection<AttributeKey> attributeKeys,
	                                     @Nonnull AttributesContract<?> attributes,
										 @Nonnull NamedSchemaContract parentSchema,
	                                     @Nonnull AttributeSchemaProvider<?> attributeSchemaProvider) {
		for (AttributeKey attributeKey : attributeKeys) {
			final String attributeName = attributeKey.attributeName();
			final Locale locale = attributeKey.locale();
			final Optional<AttributeValue> attributeValue = locale != null ?
				attributes.getAttributeValue(attributeName, locale) :
				attributes.getAttributeValue(attributeName);

			final String serializableAttributeName = attributeSchemaProvider.getAttribute(attributeName)
				.map(it -> it.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
				.orElseThrow(() -> new RestQueryResolvingInternalError("Cannot find attribute schema for `" + attributeName + "` in entity schema `" + parentSchema.getName() + "`."));

			if(attributeValue.isPresent() && attributeValue.get().value() != null) {
				attributesNode.putIfAbsent(serializableAttributeName, this.objectJsonSerializer.serializeObject(attributeValue.get().value()));
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
			final Locale locale = associatedDataKey.locale();
			final Optional<AssociatedDataValue> associatedDataValue = locale != null ?
				associatedData.getAssociatedDataValue(associatedDataName, locale) :
				associatedData.getAssociatedDataValue(associatedDataName);

			final String serializableAssociatedDataName = entitySchema.getAssociatedData(associatedDataName)
				.map(it -> it.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
				.orElseThrow(() -> new RestQueryResolvingInternalError("Cannot find associated data schema for `" + associatedDataName + "` in entity schema `" + entitySchema.getName() + "`."));

			if(associatedDataValue.isPresent() && associatedDataValue.get().value() != null) {
				attributesNode.putIfAbsent(serializableAssociatedDataName, this.objectJsonSerializer.serializeObject(associatedDataValue.get().value()));
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
			final Locale locale = attributeKey.locale();
			if (locale != null) {
				final List<AttributeKey> localizedKeys = localeSeparatedKeys.get(locale.toLanguageTag());
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
			final Locale locale = associatedDataKey.locale();
			if (locale != null) {
				final List<AssociatedDataKey> localizedKeys = localeSeparatedKeys.get(locale.toLanguageTag());
				localizedKeys.add(associatedDataKey);
			} else {
				final List<AssociatedDataKey> globalKeys = localeSeparatedKeys.get(SectionedAssociatedDataDescriptor.GLOBAL.name());
				globalKeys.add(associatedDataKey);
			}
		}
		return localeSeparatedKeys;
	}
}
