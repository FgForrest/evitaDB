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
// source: GrpcAssociatedDataMutations.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcRemoveAssociatedDataMutationOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcRemoveAssociatedDataMutation)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * Unique name of the associatedData. Case-sensitive. Distinguishes one associated data item from another within
   * single entity instance.
   * </pre>
   *
   * <code>string associatedDataName = 1;</code>
   * @return The associatedDataName.
   */
  java.lang.String getAssociatedDataName();
  /**
   * <pre>
   * Unique name of the associatedData. Case-sensitive. Distinguishes one associated data item from another within
   * single entity instance.
   * </pre>
   *
   * <code>string associatedDataName = 1;</code>
   * @return The bytes for associatedDataName.
   */
  com.google.protobuf.ByteString
      getAssociatedDataNameBytes();

  /**
   * <pre>
   * Contains locale in case the associatedData is locale specific.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocale associatedDataLocale = 2;</code>
   * @return Whether the associatedDataLocale field is set.
   */
  boolean hasAssociatedDataLocale();
  /**
   * <pre>
   * Contains locale in case the associatedData is locale specific.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocale associatedDataLocale = 2;</code>
   * @return The associatedDataLocale.
   */
  io.evitadb.externalApi.grpc.generated.GrpcLocale getAssociatedDataLocale();
  /**
   * <pre>
   * Contains locale in case the associatedData is locale specific.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcLocale associatedDataLocale = 2;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcLocaleOrBuilder getAssociatedDataLocaleOrBuilder();
}
