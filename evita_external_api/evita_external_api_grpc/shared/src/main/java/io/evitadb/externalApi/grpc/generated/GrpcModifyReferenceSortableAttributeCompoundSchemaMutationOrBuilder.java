// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcReferenceSchemaMutations.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcModifyReferenceSortableAttributeCompoundSchemaMutationOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcModifyReferenceSortableAttributeCompoundSchemaMutation)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * Name of the reference the mutation is targeting.
   * </pre>
   *
   * <code>string name = 1;</code>
   * @return The name.
   */
  java.lang.String getName();
  /**
   * <pre>
   * Name of the reference the mutation is targeting.
   * </pre>
   *
   * <code>string name = 1;</code>
   * @return The bytes for name.
   */
  com.google.protobuf.ByteString
      getNameBytes();

  /**
   * <pre>
   * Nested sortable attribute compound schema mutation that mutates reference sortable attribute compounds of targeted reference.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSortableAttributeCompoundSchemaMutation sortableAttributeCompoundSchemaMutation = 2;</code>
   * @return Whether the sortableAttributeCompoundSchemaMutation field is set.
   */
  boolean hasSortableAttributeCompoundSchemaMutation();
  /**
   * <pre>
   * Nested sortable attribute compound schema mutation that mutates reference sortable attribute compounds of targeted reference.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSortableAttributeCompoundSchemaMutation sortableAttributeCompoundSchemaMutation = 2;</code>
   * @return The sortableAttributeCompoundSchemaMutation.
   */
  io.evitadb.externalApi.grpc.generated.GrpcSortableAttributeCompoundSchemaMutation getSortableAttributeCompoundSchemaMutation();
  /**
   * <pre>
   * Nested sortable attribute compound schema mutation that mutates reference sortable attribute compounds of targeted reference.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSortableAttributeCompoundSchemaMutation sortableAttributeCompoundSchemaMutation = 2;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcSortableAttributeCompoundSchemaMutationOrBuilder getSortableAttributeCompoundSchemaMutationOrBuilder();
}
