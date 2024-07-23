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

// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcEntitySchema.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcReferenceSchemaOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcReferenceSchema)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * Contains unique name of the model. Case-sensitive. Distinguishes one model item from another
   * within single entity instance.
   * </pre>
   *
   * <code>string name = 1;</code>
   * @return The name.
   */
  java.lang.String getName();
  /**
   * <pre>
   * Contains unique name of the model. Case-sensitive. Distinguishes one model item from another
   * within single entity instance.
   * </pre>
   *
   * <code>string name = 1;</code>
   * @return The bytes for name.
   */
  com.google.protobuf.ByteString
      getNameBytes();

  /**
   * <pre>
   * Contains description of the model is optional but helps authors of the schema / client API to better
   * explain the original purpose of the model to the consumers.
   * </pre>
   *
   * <code>.google.protobuf.StringValue description = 2;</code>
   * @return Whether the description field is set.
   */
  boolean hasDescription();
  /**
   * <pre>
   * Contains description of the model is optional but helps authors of the schema / client API to better
   * explain the original purpose of the model to the consumers.
   * </pre>
   *
   * <code>.google.protobuf.StringValue description = 2;</code>
   * @return The description.
   */
  com.google.protobuf.StringValue getDescription();
  /**
   * <pre>
   * Contains description of the model is optional but helps authors of the schema / client API to better
   * explain the original purpose of the model to the consumers.
   * </pre>
   *
   * <code>.google.protobuf.StringValue description = 2;</code>
   */
  com.google.protobuf.StringValueOrBuilder getDescriptionOrBuilder();

  /**
   * <pre>
   * Deprecation notice contains information about planned removal of this entity from the model / client API.
   * This allows to plan and evolve the schema allowing clients to adapt early to planned breaking changes.
   * If notice is `null`, this schema is considered not deprecated.
   * </pre>
   *
   * <code>.google.protobuf.StringValue deprecationNotice = 3;</code>
   * @return Whether the deprecationNotice field is set.
   */
  boolean hasDeprecationNotice();
  /**
   * <pre>
   * Deprecation notice contains information about planned removal of this entity from the model / client API.
   * This allows to plan and evolve the schema allowing clients to adapt early to planned breaking changes.
   * If notice is `null`, this schema is considered not deprecated.
   * </pre>
   *
   * <code>.google.protobuf.StringValue deprecationNotice = 3;</code>
   * @return The deprecationNotice.
   */
  com.google.protobuf.StringValue getDeprecationNotice();
  /**
   * <pre>
   * Deprecation notice contains information about planned removal of this entity from the model / client API.
   * This allows to plan and evolve the schema allowing clients to adapt early to planned breaking changes.
   * If notice is `null`, this schema is considered not deprecated.
   * </pre>
   *
   * <code>.google.protobuf.StringValue deprecationNotice = 3;</code>
   */
  com.google.protobuf.StringValueOrBuilder getDeprecationNoticeOrBuilder();

  /**
   * <pre>
   * Cardinality describes the expected count of relations of this type. In evitaDB we define only one-way
   * relationship from the perspective of the entity. We stick to the ERD modelling
   * [standards](https://www.gleek.io/blog/crows-foot-notation.html) here. Cardinality affect the design
   * of the client API (returning only single reference or collections) and also help us to protect the consistency
   * of the data so that conforms to the creator mental model.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcCardinality cardinality = 4;</code>
   * @return The enum numeric value on the wire for cardinality.
   */
  int getCardinalityValue();
  /**
   * <pre>
   * Cardinality describes the expected count of relations of this type. In evitaDB we define only one-way
   * relationship from the perspective of the entity. We stick to the ERD modelling
   * [standards](https://www.gleek.io/blog/crows-foot-notation.html) here. Cardinality affect the design
   * of the client API (returning only single reference or collections) and also help us to protect the consistency
   * of the data so that conforms to the creator mental model.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcCardinality cardinality = 4;</code>
   * @return The cardinality.
   */
  io.evitadb.externalApi.grpc.generated.GrpcCardinality getCardinality();

  /**
   * <pre>
   * Reference to `Entity.type` of the referenced entity. Might be also any `String`
   * that identifies type some external resource not maintained by Evita.
   * </pre>
   *
   * <code>string entityType = 5;</code>
   * @return The entityType.
   */
  java.lang.String getEntityType();
  /**
   * <pre>
   * Reference to `Entity.type` of the referenced entity. Might be also any `String`
   * that identifies type some external resource not maintained by Evita.
   * </pre>
   *
   * <code>string entityType = 5;</code>
   * @return The bytes for entityType.
   */
  com.google.protobuf.ByteString
      getEntityTypeBytes();

  /**
   * <pre>
   * Contains `true` if `entityType` refers to any existing entity that is maintained by Evita.
   * </pre>
   *
   * <code>bool entityTypeRelatesToEntity = 6;</code>
   * @return The entityTypeRelatesToEntity.
   */
  boolean getEntityTypeRelatesToEntity();

  /**
   * <pre>
   * Reference to `Entity.type` of the referenced entity. Might be also `String` that identifies type some external
   * resource not maintained by Evita.
   * </pre>
   *
   * <code>.google.protobuf.StringValue groupType = 7;</code>
   * @return Whether the groupType field is set.
   */
  boolean hasGroupType();
  /**
   * <pre>
   * Reference to `Entity.type` of the referenced entity. Might be also `String` that identifies type some external
   * resource not maintained by Evita.
   * </pre>
   *
   * <code>.google.protobuf.StringValue groupType = 7;</code>
   * @return The groupType.
   */
  com.google.protobuf.StringValue getGroupType();
  /**
   * <pre>
   * Reference to `Entity.type` of the referenced entity. Might be also `String` that identifies type some external
   * resource not maintained by Evita.
   * </pre>
   *
   * <code>.google.protobuf.StringValue groupType = 7;</code>
   */
  com.google.protobuf.StringValueOrBuilder getGroupTypeOrBuilder();

  /**
   * <pre>
   * Contains `true` if `groupType` refers to any existing entity that is maintained by Evita.
   * </pre>
   *
   * <code>bool groupTypeRelatesToEntity = 8;</code>
   * @return The groupTypeRelatesToEntity.
   */
  boolean getGroupTypeRelatesToEntity();

  /**
   * <pre>
   * Contains `true` if the index for this reference should be created and maintained allowing to filter by
   * `reference_{reference name}_having` filtering constraints. Index is also required when reference is
   * `faceted`.
   * Do not mark reference as faceted unless you know that you'll need to filter/sort entities by this reference.
   * Each indexed reference occupies (memory/disk) space in the form of index. When reference is not indexed,
   * the entity cannot be looked up by reference attributes or relation existence itself, but the data can be
   * fetched.
   * </pre>
   *
   * <code>bool indexed = 9;</code>
   * @return The indexed.
   */
  boolean getIndexed();

  /**
   * <pre>
   * Contains `true` if the statistics data for this reference should be maintained and this allowing to get
   * `facetStatistics` for this reference or use `facet_{reference name}_inSet`
   * filtering constraint.
   * Do not mark reference as faceted unless you want it among `facetStatistics`. Each faceted reference
   * occupies (memory/disk) space in the form of index.
   * Reference that was marked as faceted is called Facet.
   * </pre>
   *
   * <code>bool faceted = 10;</code>
   * @return The faceted.
   */
  boolean getFaceted();

  /**
   * <pre>
   * Attributes related to reference allows defining set of data that are fetched in bulk along with the entity body.
   * Attributes may be indexed for fast filtering (`AttributeSchema.filterable`) or can be used to sort along
   * (`AttributeSchema.filterable`). Attributes are not automatically indexed in order not to waste precious
   * memory space for data that will never be used in search queries.
   * Filtering in attributes is executed by using constraints like `and`,
   * `not`, `attributeEquals`, `attributeContains`
   * and many others. Sorting can be achieved with `attributeNatural` or others.
   * Attributes are not recommended for bigger data as they are all loaded at once.
   * </pre>
   *
   * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcAttributeSchema&gt; attributes = 11;</code>
   */
  int getAttributesCount();
  /**
   * <pre>
   * Attributes related to reference allows defining set of data that are fetched in bulk along with the entity body.
   * Attributes may be indexed for fast filtering (`AttributeSchema.filterable`) or can be used to sort along
   * (`AttributeSchema.filterable`). Attributes are not automatically indexed in order not to waste precious
   * memory space for data that will never be used in search queries.
   * Filtering in attributes is executed by using constraints like `and`,
   * `not`, `attributeEquals`, `attributeContains`
   * and many others. Sorting can be achieved with `attributeNatural` or others.
   * Attributes are not recommended for bigger data as they are all loaded at once.
   * </pre>
   *
   * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcAttributeSchema&gt; attributes = 11;</code>
   */
  boolean containsAttributes(
      java.lang.String key);
  /**
   * Use {@link #getAttributesMap()} instead.
   */
  @java.lang.Deprecated
  java.util.Map<java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcAttributeSchema>
  getAttributes();
  /**
   * <pre>
   * Attributes related to reference allows defining set of data that are fetched in bulk along with the entity body.
   * Attributes may be indexed for fast filtering (`AttributeSchema.filterable`) or can be used to sort along
   * (`AttributeSchema.filterable`). Attributes are not automatically indexed in order not to waste precious
   * memory space for data that will never be used in search queries.
   * Filtering in attributes is executed by using constraints like `and`,
   * `not`, `attributeEquals`, `attributeContains`
   * and many others. Sorting can be achieved with `attributeNatural` or others.
   * Attributes are not recommended for bigger data as they are all loaded at once.
   * </pre>
   *
   * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcAttributeSchema&gt; attributes = 11;</code>
   */
  java.util.Map<java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcAttributeSchema>
  getAttributesMap();
  /**
   * <pre>
   * Attributes related to reference allows defining set of data that are fetched in bulk along with the entity body.
   * Attributes may be indexed for fast filtering (`AttributeSchema.filterable`) or can be used to sort along
   * (`AttributeSchema.filterable`). Attributes are not automatically indexed in order not to waste precious
   * memory space for data that will never be used in search queries.
   * Filtering in attributes is executed by using constraints like `and`,
   * `not`, `attributeEquals`, `attributeContains`
   * and many others. Sorting can be achieved with `attributeNatural` or others.
   * Attributes are not recommended for bigger data as they are all loaded at once.
   * </pre>
   *
   * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcAttributeSchema&gt; attributes = 11;</code>
   */

  io.evitadb.externalApi.grpc.generated.GrpcAttributeSchema getAttributesOrDefault(
      java.lang.String key,
      io.evitadb.externalApi.grpc.generated.GrpcAttributeSchema defaultValue);
  /**
   * <pre>
   * Attributes related to reference allows defining set of data that are fetched in bulk along with the entity body.
   * Attributes may be indexed for fast filtering (`AttributeSchema.filterable`) or can be used to sort along
   * (`AttributeSchema.filterable`). Attributes are not automatically indexed in order not to waste precious
   * memory space for data that will never be used in search queries.
   * Filtering in attributes is executed by using constraints like `and`,
   * `not`, `attributeEquals`, `attributeContains`
   * and many others. Sorting can be achieved with `attributeNatural` or others.
   * Attributes are not recommended for bigger data as they are all loaded at once.
   * </pre>
   *
   * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcAttributeSchema&gt; attributes = 11;</code>
   */

  io.evitadb.externalApi.grpc.generated.GrpcAttributeSchema getAttributesOrThrow(
      java.lang.String key);

  /**
   * <pre>
   * Contains index of definitions of all sortable attribute compounds defined in this schema.
   * </pre>
   *
   * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcSortableAttributeCompoundSchema&gt; sortableAttributeCompounds = 12;</code>
   */
  int getSortableAttributeCompoundsCount();
  /**
   * <pre>
   * Contains index of definitions of all sortable attribute compounds defined in this schema.
   * </pre>
   *
   * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcSortableAttributeCompoundSchema&gt; sortableAttributeCompounds = 12;</code>
   */
  boolean containsSortableAttributeCompounds(
      java.lang.String key);
  /**
   * Use {@link #getSortableAttributeCompoundsMap()} instead.
   */
  @java.lang.Deprecated
  java.util.Map<java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcSortableAttributeCompoundSchema>
  getSortableAttributeCompounds();
  /**
   * <pre>
   * Contains index of definitions of all sortable attribute compounds defined in this schema.
   * </pre>
   *
   * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcSortableAttributeCompoundSchema&gt; sortableAttributeCompounds = 12;</code>
   */
  java.util.Map<java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcSortableAttributeCompoundSchema>
  getSortableAttributeCompoundsMap();
  /**
   * <pre>
   * Contains index of definitions of all sortable attribute compounds defined in this schema.
   * </pre>
   *
   * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcSortableAttributeCompoundSchema&gt; sortableAttributeCompounds = 12;</code>
   */

  io.evitadb.externalApi.grpc.generated.GrpcSortableAttributeCompoundSchema getSortableAttributeCompoundsOrDefault(
      java.lang.String key,
      io.evitadb.externalApi.grpc.generated.GrpcSortableAttributeCompoundSchema defaultValue);
  /**
   * <pre>
   * Contains index of definitions of all sortable attribute compounds defined in this schema.
   * </pre>
   *
   * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcSortableAttributeCompoundSchema&gt; sortableAttributeCompounds = 12;</code>
   */

  io.evitadb.externalApi.grpc.generated.GrpcSortableAttributeCompoundSchema getSortableAttributeCompoundsOrThrow(
      java.lang.String key);

  /**
   * <pre>
   * Contains reference name converted to different naming conventions.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcNameVariant nameVariant = 13;</code>
   */
  java.util.List<io.evitadb.externalApi.grpc.generated.GrpcNameVariant>
      getNameVariantList();
  /**
   * <pre>
   * Contains reference name converted to different naming conventions.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcNameVariant nameVariant = 13;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcNameVariant getNameVariant(int index);
  /**
   * <pre>
   * Contains reference name converted to different naming conventions.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcNameVariant nameVariant = 13;</code>
   */
  int getNameVariantCount();
  /**
   * <pre>
   * Contains reference name converted to different naming conventions.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcNameVariant nameVariant = 13;</code>
   */
  java.util.List<? extends io.evitadb.externalApi.grpc.generated.GrpcNameVariantOrBuilder>
      getNameVariantOrBuilderList();
  /**
   * <pre>
   * Contains reference name converted to different naming conventions.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcNameVariant nameVariant = 13;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcNameVariantOrBuilder getNameVariantOrBuilder(
      int index);
}
