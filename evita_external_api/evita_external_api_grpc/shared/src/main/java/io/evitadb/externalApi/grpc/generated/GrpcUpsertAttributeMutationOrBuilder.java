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
// source: GrpcAttributeMutations.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcUpsertAttributeMutationOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcUpsertAttributeMutation)
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

  /**
   * <pre>
   * New value of this attribute. Data type is expected to be the same as in schema or must be explicitly
   * set via `valueType`.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEvitaValue attributeValue = 3;</code>
   * @return Whether the attributeValue field is set.
   */
  boolean hasAttributeValue();
  /**
   * <pre>
   * New value of this attribute. Data type is expected to be the same as in schema or must be explicitly
   * set via `valueType`.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEvitaValue attributeValue = 3;</code>
   * @return The attributeValue.
   */
  io.evitadb.externalApi.grpc.generated.GrpcEvitaValue getAttributeValue();
  /**
   * <pre>
   * New value of this attribute. Data type is expected to be the same as in schema or must be explicitly
   * set via `valueType`.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcEvitaValue attributeValue = 3;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcEvitaValueOrBuilder getAttributeValueOrBuilder();
}
