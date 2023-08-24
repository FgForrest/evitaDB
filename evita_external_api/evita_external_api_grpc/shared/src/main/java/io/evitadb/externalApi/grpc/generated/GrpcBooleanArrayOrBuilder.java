// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcEvitaDataTypes.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcBooleanArrayOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcBooleanArray)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * Value that supports storing a boolean array.
   * </pre>
   *
   * <code>repeated bool value = 1;</code>
   * @return A list containing the value.
   */
  java.util.List<java.lang.Boolean> getValueList();
  /**
   * <pre>
   * Value that supports storing a boolean array.
   * </pre>
   *
   * <code>repeated bool value = 1;</code>
   * @return The count of value.
   */
  int getValueCount();
  /**
   * <pre>
   * Value that supports storing a boolean array.
   * </pre>
   *
   * <code>repeated bool value = 1;</code>
   * @param index The index of the element to return.
   * @return The value at the given index.
   */
  boolean getValue(int index);
}
