/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: GrpcCatalogSchema.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcCatalogSchemaOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcCatalogSchema)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>string name = 1;</code>
   * @return The name.
   */
  java.lang.String getName();
  /**
   * <code>string name = 1;</code>
   * @return The bytes for name.
   */
  com.google.protobuf.ByteString
      getNameBytes();

  /**
   * <code>int32 version = 2;</code>
   * @return The version.
   */
  int getVersion();

  /**
   * <code>.google.protobuf.StringValue description = 3;</code>
   * @return Whether the description field is set.
   */
  boolean hasDescription();
  /**
   * <code>.google.protobuf.StringValue description = 3;</code>
   * @return The description.
   */
  com.google.protobuf.StringValue getDescription();
  /**
   * <code>.google.protobuf.StringValue description = 3;</code>
   */
  com.google.protobuf.StringValueOrBuilder getDescriptionOrBuilder();

  /**
   * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcGlobalAttributeSchema&gt; attributes = 4;</code>
   */
  int getAttributesCount();
  /**
   * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcGlobalAttributeSchema&gt; attributes = 4;</code>
   */
  boolean containsAttributes(
      java.lang.String key);
  /**
   * Use {@link #getAttributesMap()} instead.
   */
  @java.lang.Deprecated
  java.util.Map<java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcGlobalAttributeSchema>
  getAttributes();
  /**
   * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcGlobalAttributeSchema&gt; attributes = 4;</code>
   */
  java.util.Map<java.lang.String, io.evitadb.externalApi.grpc.generated.GrpcGlobalAttributeSchema>
  getAttributesMap();
  /**
   * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcGlobalAttributeSchema&gt; attributes = 4;</code>
   */

  io.evitadb.externalApi.grpc.generated.GrpcGlobalAttributeSchema getAttributesOrDefault(
      java.lang.String key,
      io.evitadb.externalApi.grpc.generated.GrpcGlobalAttributeSchema defaultValue);
  /**
   * <code>map&lt;string, .io.evitadb.externalApi.grpc.generated.GrpcGlobalAttributeSchema&gt; attributes = 4;</code>
   */

  io.evitadb.externalApi.grpc.generated.GrpcGlobalAttributeSchema getAttributesOrThrow(
      java.lang.String key);
}
