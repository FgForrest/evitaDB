// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcAttributeSchemaMutations.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcCreateAttributeSchemaMutationOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcCreateAttributeSchemaMutation)
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
   * </pre>
   *
   * <code>bool unique = 4;</code>
   * @return The unique.
   */
  boolean getUnique();

  /**
   * <pre>
   * When attribute is filterable, it is possible to filter entities by this attribute. Do not mark attribute
   * as filterable unless you know that you'll search entities by this attribute. Each filterable attribute occupies
   * (memory/disk) space in the form of index.
   * </pre>
   *
   * <code>bool filterable = 5;</code>
   * @return The filterable.
   */
  boolean getFilterable();

  /**
   * <pre>
   * When attribute is sortable, it is possible to sort entities by this attribute. Do not mark attribute
   * as sortable unless you know that you'll sort entities along this attribute. Each sortable attribute occupies
   * (memory/disk) space in the form of index.
   * </pre>
   *
   * <code>bool sortable = 6;</code>
   * @return The sortable.
   */
  boolean getSortable();

  /**
   * <pre>
   * Localized attribute has to be ALWAYS used in connection with specific `locale`. In other
   * words - it cannot be stored unless associated locale is also provided.
   * </pre>
   *
   * <code>bool localized = 7;</code>
   * @return The localized.
   */
  boolean getLocalized();

  /**
   * <pre>
   * 	When attribute is nullable, its values may be missing in the entities. Otherwise, the system will enforce
   *	non-null checks upon upserting of the entity.
   * </pre>
   *
   * <code>bool nullable = 8;</code>
   * @return The nullable.
   */
  boolean getNullable();

  /**
   * <pre>
   * Type of the attribute. Must be one of supported data types or its array.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEvitaDataType type = 9;</code>
   * @return The enum numeric value on the wire for type.
   */
  int getTypeValue();
  /**
   * <pre>
   * Type of the attribute. Must be one of supported data types or its array.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEvitaDataType type = 9;</code>
   * @return The type.
   */
  io.evitadb.externalApi.grpc.generated.GrpcEvitaDataType getType();

  /**
   * <pre>
   * 	Determines how many fractional places are important when entities are compared during filtering or sorting. It is
   *	significant to know that all values of this attribute will be converted to `Integer`, so the attribute
   *	number must not ever exceed maximum limits of `Integer` type when scaling the number by the power
   *	of ten using `indexedDecimalPlaces` as exponent.
   * </pre>
   *
   * <code>int32 indexedDecimalPlaces = 10;</code>
   * @return The indexedDecimalPlaces.
   */
  int getIndexedDecimalPlaces();

  /**
   * <pre>
   * Default value is used when the entity is created without this attribute specified. Default values allow to pass
   * non-null checks even if no attributes of such name are specified.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEvitaValue defaultValue = 11;</code>
   * @return Whether the defaultValue field is set.
   */
  boolean hasDefaultValue();
  /**
   * <pre>
   * Default value is used when the entity is created without this attribute specified. Default values allow to pass
   * non-null checks even if no attributes of such name are specified.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEvitaValue defaultValue = 11;</code>
   * @return The defaultValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcEvitaValue getDefaultValue();
  /**
   * <pre>
   * Default value is used when the entity is created without this attribute specified. Default values allow to pass
   * non-null checks even if no attributes of such name are specified.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEvitaValue defaultValue = 11;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcEvitaValueOrBuilder getDefaultValueOrBuilder();
}
