/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
// source: GrpcAttributeSchemaMutations.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcCreateGlobalAttributeSchemaMutationOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcCreateGlobalAttributeSchemaMutation)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * Name of the attribute the mutation is targeting.
   * </pre>
   *
   * <code>string name = 1;</code>
   * @return The name.
   */
  java.lang.String getName();
  /**
   * <pre>
   * Name of the attribute the mutation is targeting.
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
   * Deprecation notice contains information about planned removal of this attribute from the model / client API.
   * This allows to plan and evolve the schema allowing clients to adapt early to planned breaking changes.
   * </pre>
   *
   * <code>.google.protobuf.StringValue deprecationNotice = 3;</code>
   * @return Whether the deprecationNotice field is set.
   */
  boolean hasDeprecationNotice();
  /**
   * <pre>
   * Deprecation notice contains information about planned removal of this attribute from the model / client API.
   * This allows to plan and evolve the schema allowing clients to adapt early to planned breaking changes.
   * </pre>
   *
   * <code>.google.protobuf.StringValue deprecationNotice = 3;</code>
   * @return The deprecationNotice.
   */
  com.google.protobuf.StringValue getDeprecationNotice();
  /**
   * <pre>
   * Deprecation notice contains information about planned removal of this attribute from the model / client API.
   * This allows to plan and evolve the schema allowing clients to adapt early to planned breaking changes.
   * </pre>
   *
   * <code>.google.protobuf.StringValue deprecationNotice = 3;</code>
   */
  com.google.protobuf.StringValueOrBuilder getDeprecationNoticeOrBuilder();

  /**
   * <pre>
   * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
   * having certain value of this attribute among other entities in the same collection.
   * deprecated in favor of `uniqueInScopes`
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcAttributeUniquenessType unique = 4 [deprecated = true];</code>
   * @deprecated
   * @return The enum numeric value on the wire for unique.
   */
  @java.lang.Deprecated int getUniqueValue();
  /**
   * <pre>
   * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
   * having certain value of this attribute among other entities in the same collection.
   * deprecated in favor of `uniqueInScopes`
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcAttributeUniquenessType unique = 4 [deprecated = true];</code>
   * @deprecated
   * @return The unique.
   */
  @java.lang.Deprecated io.evitadb.externalApi.grpc.generated.GrpcAttributeUniquenessType getUnique();

  /**
   * <pre>
   * When attribute is unique globally it is automatically filterable, and it is ensured there is exactly one single
   * entity having certain value of this attribute in entire catalog.
   * deprecated in favor of `uniqueGloballyInScopes`
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcGlobalAttributeUniquenessType uniqueGlobally = 5 [deprecated = true];</code>
   * @deprecated
   * @return The enum numeric value on the wire for uniqueGlobally.
   */
  @java.lang.Deprecated int getUniqueGloballyValue();
  /**
   * <pre>
   * When attribute is unique globally it is automatically filterable, and it is ensured there is exactly one single
   * entity having certain value of this attribute in entire catalog.
   * deprecated in favor of `uniqueGloballyInScopes`
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcGlobalAttributeUniquenessType uniqueGlobally = 5 [deprecated = true];</code>
   * @deprecated
   * @return The uniqueGlobally.
   */
  @java.lang.Deprecated io.evitadb.externalApi.grpc.generated.GrpcGlobalAttributeUniquenessType getUniqueGlobally();

  /**
   * <pre>
   * When attribute is filterable, it is possible to filter entities by this attribute. Do not mark attribute
   * as filterable unless you know that you'll search entities by this attribute. Each filterable attribute occupies
   * (memory/disk) space in the form of index.
   * deprecated in favor of `filterableInScopes`
   * </pre>
   *
   * <code>bool filterable = 6 [deprecated = true];</code>
   * @deprecated
   * @return The filterable.
   */
  @java.lang.Deprecated boolean getFilterable();

  /**
   * <pre>
   * When attribute is sortable, it is possible to sort entities by this attribute. Do not mark attribute
   * as sortable unless you know that you'll sort entities along this attribute. Each sortable attribute occupies
   * (memory/disk) space in the form of index.
   * deprecated in favor of `sortableInScopes`
   * </pre>
   *
   * <code>bool sortable = 7 [deprecated = true];</code>
   * @deprecated
   * @return The sortable.
   */
  @java.lang.Deprecated boolean getSortable();

  /**
   * <pre>
   * Localized attribute has to be ALWAYS used in connection with specific `locale`. In other
   * words - it cannot be stored unless associated locale is also provided.
   * </pre>
   *
   * <code>bool localized = 8;</code>
   * @return The localized.
   */
  boolean getLocalized();

  /**
   * <pre>
   * When attribute is nullable, its values may be missing in the entities. Otherwise, the system will enforce
   * non-null checks upon upserting of the entity.
   * </pre>
   *
   * <code>bool nullable = 9;</code>
   * @return The nullable.
   */
  boolean getNullable();

  /**
   * <pre>
   * If an attribute is flagged as representative, it should be used in developer tools along with the entity's
   * primary key to describe the entity or reference to that entity. The flag is completely optional and doesn't
   * affect the core functionality of the database in any way. However, if it's used correctly, it can be very
   * helpful to developers in quickly finding their way around the data. There should be very few representative
   * attributes in the entity type, and the unique ones are usually the best to choose.
   * </pre>
   *
   * <code>bool representative = 10;</code>
   * @return The representative.
   */
  boolean getRepresentative();

  /**
   * <pre>
   * Type of the attribute. Must be one of supported data types or its array.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEvitaDataType type = 11;</code>
   * @return The enum numeric value on the wire for type.
   */
  int getTypeValue();
  /**
   * <pre>
   * Type of the attribute. Must be one of supported data types or its array.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEvitaDataType type = 11;</code>
   * @return The type.
   */
  io.evitadb.externalApi.grpc.generated.GrpcEvitaDataType getType();

  /**
   * <pre>
   * Determines how many fractional places are important when entities are compared during filtering or sorting. It is
   * significant to know that all values of this attribute will be converted to `Integer`, so the attribute
   * number must not ever exceed maximum limits of `Integer` type when scaling the number by the power
   * of ten using `indexedDecimalPlaces` as exponent.
   * </pre>
   *
   * <code>int32 indexedDecimalPlaces = 12;</code>
   * @return The indexedDecimalPlaces.
   */
  int getIndexedDecimalPlaces();

  /**
   * <pre>
   * Default value is used when the entity is created without this attribute specified. Default values allow to pass
   * non-null checks even if no attributes of such name are specified.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEvitaValue defaultValue = 13;</code>
   * @return Whether the defaultValue field is set.
   */
  boolean hasDefaultValue();
  /**
   * <pre>
   * Default value is used when the entity is created without this attribute specified. Default values allow to pass
   * non-null checks even if no attributes of such name are specified.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEvitaValue defaultValue = 13;</code>
   * @return The defaultValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcEvitaValue getDefaultValue();
  /**
   * <pre>
   * Default value is used when the entity is created without this attribute specified. Default values allow to pass
   * non-null checks even if no attributes of such name are specified.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEvitaValue defaultValue = 13;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcEvitaValueOrBuilder getDefaultValueOrBuilder();

  /**
   * <pre>
   * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
   * having certain value of this attribute among other entities in the same collection.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcScopedAttributeUniquenessType uniqueInScopes = 14;</code>
   */
  java.util.List<io.evitadb.externalApi.grpc.generated.GrpcScopedAttributeUniquenessType>
      getUniqueInScopesList();
  /**
   * <pre>
   * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
   * having certain value of this attribute among other entities in the same collection.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcScopedAttributeUniquenessType uniqueInScopes = 14;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcScopedAttributeUniquenessType getUniqueInScopes(int index);
  /**
   * <pre>
   * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
   * having certain value of this attribute among other entities in the same collection.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcScopedAttributeUniquenessType uniqueInScopes = 14;</code>
   */
  int getUniqueInScopesCount();
  /**
   * <pre>
   * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
   * having certain value of this attribute among other entities in the same collection.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcScopedAttributeUniquenessType uniqueInScopes = 14;</code>
   */
  java.util.List<? extends io.evitadb.externalApi.grpc.generated.GrpcScopedAttributeUniquenessTypeOrBuilder>
      getUniqueInScopesOrBuilderList();
  /**
   * <pre>
   * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
   * having certain value of this attribute among other entities in the same collection.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcScopedAttributeUniquenessType uniqueInScopes = 14;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcScopedAttributeUniquenessTypeOrBuilder getUniqueInScopesOrBuilder(
      int index);

  /**
   * <pre>
   * When attribute is unique globally it is automatically filterable, and it is ensured there is exactly one single
   * entity having certain value of this attribute in entire catalog.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcScopedGlobalAttributeUniquenessType uniqueGloballyInScopes = 15;</code>
   */
  java.util.List<io.evitadb.externalApi.grpc.generated.GrpcScopedGlobalAttributeUniquenessType>
      getUniqueGloballyInScopesList();
  /**
   * <pre>
   * When attribute is unique globally it is automatically filterable, and it is ensured there is exactly one single
   * entity having certain value of this attribute in entire catalog.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcScopedGlobalAttributeUniquenessType uniqueGloballyInScopes = 15;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcScopedGlobalAttributeUniquenessType getUniqueGloballyInScopes(int index);
  /**
   * <pre>
   * When attribute is unique globally it is automatically filterable, and it is ensured there is exactly one single
   * entity having certain value of this attribute in entire catalog.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcScopedGlobalAttributeUniquenessType uniqueGloballyInScopes = 15;</code>
   */
  int getUniqueGloballyInScopesCount();
  /**
   * <pre>
   * When attribute is unique globally it is automatically filterable, and it is ensured there is exactly one single
   * entity having certain value of this attribute in entire catalog.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcScopedGlobalAttributeUniquenessType uniqueGloballyInScopes = 15;</code>
   */
  java.util.List<? extends io.evitadb.externalApi.grpc.generated.GrpcScopedGlobalAttributeUniquenessTypeOrBuilder>
      getUniqueGloballyInScopesOrBuilderList();
  /**
   * <pre>
   * When attribute is unique globally it is automatically filterable, and it is ensured there is exactly one single
   * entity having certain value of this attribute in entire catalog.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcScopedGlobalAttributeUniquenessType uniqueGloballyInScopes = 15;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcScopedGlobalAttributeUniquenessTypeOrBuilder getUniqueGloballyInScopesOrBuilder(
      int index);

  /**
   * <pre>
   * When attribute is filterable, it is possible to filter entities by this attribute. Do not mark attribute
   * as filterable unless you know that you'll search entities by this attribute. Each filterable attribute occupies
   * (memory/disk) space in the form of index.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope filterableInScopes = 16;</code>
   * @return A list containing the filterableInScopes.
   */
  java.util.List<io.evitadb.externalApi.grpc.generated.GrpcEntityScope> getFilterableInScopesList();
  /**
   * <pre>
   * When attribute is filterable, it is possible to filter entities by this attribute. Do not mark attribute
   * as filterable unless you know that you'll search entities by this attribute. Each filterable attribute occupies
   * (memory/disk) space in the form of index.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope filterableInScopes = 16;</code>
   * @return The count of filterableInScopes.
   */
  int getFilterableInScopesCount();
  /**
   * <pre>
   * When attribute is filterable, it is possible to filter entities by this attribute. Do not mark attribute
   * as filterable unless you know that you'll search entities by this attribute. Each filterable attribute occupies
   * (memory/disk) space in the form of index.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope filterableInScopes = 16;</code>
   * @param index The index of the element to return.
   * @return The filterableInScopes at the given index.
   */
  io.evitadb.externalApi.grpc.generated.GrpcEntityScope getFilterableInScopes(int index);
  /**
   * <pre>
   * When attribute is filterable, it is possible to filter entities by this attribute. Do not mark attribute
   * as filterable unless you know that you'll search entities by this attribute. Each filterable attribute occupies
   * (memory/disk) space in the form of index.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope filterableInScopes = 16;</code>
   * @return A list containing the enum numeric values on the wire for filterableInScopes.
   */
  java.util.List<java.lang.Integer>
  getFilterableInScopesValueList();
  /**
   * <pre>
   * When attribute is filterable, it is possible to filter entities by this attribute. Do not mark attribute
   * as filterable unless you know that you'll search entities by this attribute. Each filterable attribute occupies
   * (memory/disk) space in the form of index.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope filterableInScopes = 16;</code>
   * @param index The index of the value to return.
   * @return The enum numeric value on the wire of filterableInScopes at the given index.
   */
  int getFilterableInScopesValue(int index);

  /**
   * <pre>
   * When attribute is sortable, it is possible to sort entities by this attribute. Do not mark attribute
   * as sortable unless you know that you'll sort entities along this attribute. Each sortable attribute occupies
   * (memory/disk) space in the form of index.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope sortableInScopes = 17;</code>
   * @return A list containing the sortableInScopes.
   */
  java.util.List<io.evitadb.externalApi.grpc.generated.GrpcEntityScope> getSortableInScopesList();
  /**
   * <pre>
   * When attribute is sortable, it is possible to sort entities by this attribute. Do not mark attribute
   * as sortable unless you know that you'll sort entities along this attribute. Each sortable attribute occupies
   * (memory/disk) space in the form of index.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope sortableInScopes = 17;</code>
   * @return The count of sortableInScopes.
   */
  int getSortableInScopesCount();
  /**
   * <pre>
   * When attribute is sortable, it is possible to sort entities by this attribute. Do not mark attribute
   * as sortable unless you know that you'll sort entities along this attribute. Each sortable attribute occupies
   * (memory/disk) space in the form of index.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope sortableInScopes = 17;</code>
   * @param index The index of the element to return.
   * @return The sortableInScopes at the given index.
   */
  io.evitadb.externalApi.grpc.generated.GrpcEntityScope getSortableInScopes(int index);
  /**
   * <pre>
   * When attribute is sortable, it is possible to sort entities by this attribute. Do not mark attribute
   * as sortable unless you know that you'll sort entities along this attribute. Each sortable attribute occupies
   * (memory/disk) space in the form of index.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope sortableInScopes = 17;</code>
   * @return A list containing the enum numeric values on the wire for sortableInScopes.
   */
  java.util.List<java.lang.Integer>
  getSortableInScopesValueList();
  /**
   * <pre>
   * When attribute is sortable, it is possible to sort entities by this attribute. Do not mark attribute
   * as sortable unless you know that you'll sort entities along this attribute. Each sortable attribute occupies
   * (memory/disk) space in the form of index.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope sortableInScopes = 17;</code>
   * @param index The index of the value to return.
   * @return The enum numeric value on the wire of sortableInScopes at the given index.
   */
  int getSortableInScopesValue(int index);
}
