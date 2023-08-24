// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcAttributeMutations.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcRemoveAttributeMutationOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcRemoveAttributeMutation)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * Unique name of the attribute. Case-sensitive. Distinguishes one associated data item from another within
   * single entity instance.
   * </pre>
   *
   * <code>string attributeName = 1;</code>
   * @return The attributeName.
   */
  java.lang.String getAttributeName();
  /**
   * <pre>
   * Unique name of the attribute. Case-sensitive. Distinguishes one associated data item from another within
   * single entity instance.
   * </pre>
   *
   * <code>string attributeName = 1;</code>
   * @return The bytes for attributeName.
   */
  com.google.protobuf.ByteString
      getAttributeNameBytes();

  /**
   * <pre>
   * Contains locale in case the attribute is locale specific.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocale attributeLocale = 2;</code>
   * @return Whether the attributeLocale field is set.
   */
  boolean hasAttributeLocale();
  /**
   * <pre>
   * Contains locale in case the attribute is locale specific.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocale attributeLocale = 2;</code>
   * @return The attributeLocale.
   */
  io.evitadb.externalApi.grpc.generated.GrpcLocale getAttributeLocale();
  /**
   * <pre>
   * Contains locale in case the attribute is locale specific.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocale attributeLocale = 2;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcLocaleOrBuilder getAttributeLocaleOrBuilder();
}
