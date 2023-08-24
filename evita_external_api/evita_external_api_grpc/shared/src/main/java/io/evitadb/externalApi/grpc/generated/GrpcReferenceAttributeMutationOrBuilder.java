// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcReferenceMutations.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcReferenceAttributeMutationOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcReferenceAttributeMutation)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * Unique identifier of the reference.
   * </pre>
   *
   * <code>string referenceName = 1;</code>
   * @return The referenceName.
   */
  java.lang.String getReferenceName();
  /**
   * <pre>
   * Unique identifier of the reference.
   * </pre>
   *
   * <code>string referenceName = 1;</code>
   * @return The bytes for referenceName.
   */
  com.google.protobuf.ByteString
      getReferenceNameBytes();

  /**
   * <pre>
   * Primary key of the referenced entity. Might be also any integer that uniquely identifies some external
   * resource not maintained by Evita.
   * </pre>
   *
   * <code>int32 referencePrimaryKey = 2;</code>
   * @return The referencePrimaryKey.
   */
  int getReferencePrimaryKey();

  /**
   * <pre>
   * One attribute mutation to update / insert / delete single attribute of the reference.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcAttributeMutation attributeMutation = 3;</code>
   * @return Whether the attributeMutation field is set.
   */
  boolean hasAttributeMutation();
  /**
   * <pre>
   * One attribute mutation to update / insert / delete single attribute of the reference.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcAttributeMutation attributeMutation = 3;</code>
   * @return The attributeMutation.
   */
  io.evitadb.externalApi.grpc.generated.GrpcAttributeMutation getAttributeMutation();
  /**
   * <pre>
   * One attribute mutation to update / insert / delete single attribute of the reference.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcAttributeMutation attributeMutation = 3;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcAttributeMutationOrBuilder getAttributeMutationOrBuilder();
}
