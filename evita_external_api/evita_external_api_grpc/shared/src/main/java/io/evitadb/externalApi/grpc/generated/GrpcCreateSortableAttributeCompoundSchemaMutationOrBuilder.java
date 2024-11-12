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
// source: GrpcSortableAttributeCompoundSchemaMutations.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcCreateSortableAttributeCompoundSchemaMutationOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcCreateSortableAttributeCompoundSchemaMutation)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * Name of the sortable attribute compound the mutation is targeting.
   * </pre>
   *
   * <code>string name = 1;</code>
   * @return The name.
   */
  java.lang.String getName();
  /**
   * <pre>
   * Name of the sortable attribute compound the mutation is targeting.
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
   * Deprecation notice contains information about planned removal of this sortable attribute compound from
   * the model / client API.
   * This allows to plan and evolve the schema allowing clients to adapt early to planned breaking changes.
   * </pre>
   *
   * <code>.google.protobuf.StringValue deprecationNotice = 3;</code>
   * @return Whether the deprecationNotice field is set.
   */
  boolean hasDeprecationNotice();
  /**
   * <pre>
   * Deprecation notice contains information about planned removal of this sortable attribute compound from
   * the model / client API.
   * This allows to plan and evolve the schema allowing clients to adapt early to planned breaking changes.
   * </pre>
   *
   * <code>.google.protobuf.StringValue deprecationNotice = 3;</code>
   * @return The deprecationNotice.
   */
  com.google.protobuf.StringValue getDeprecationNotice();
  /**
   * <pre>
   * Deprecation notice contains information about planned removal of this sortable attribute compound from
   * the model / client API.
   * This allows to plan and evolve the schema allowing clients to adapt early to planned breaking changes.
   * </pre>
   *
   * <code>.google.protobuf.StringValue deprecationNotice = 3;</code>
   */
  com.google.protobuf.StringValueOrBuilder getDeprecationNoticeOrBuilder();

  /**
   * <pre>
   * Defines list of individual elements forming this compound.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcAttributeElement attributeElements = 4;</code>
   */
  java.util.List<io.evitadb.externalApi.grpc.generated.GrpcAttributeElement>
      getAttributeElementsList();
  /**
   * <pre>
   * Defines list of individual elements forming this compound.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcAttributeElement attributeElements = 4;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcAttributeElement getAttributeElements(int index);
  /**
   * <pre>
   * Defines list of individual elements forming this compound.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcAttributeElement attributeElements = 4;</code>
   */
  int getAttributeElementsCount();
  /**
   * <pre>
   * Defines list of individual elements forming this compound.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcAttributeElement attributeElements = 4;</code>
   */
  java.util.List<? extends io.evitadb.externalApi.grpc.generated.GrpcAttributeElementOrBuilder>
      getAttributeElementsOrBuilderList();
  /**
   * <pre>
   * Defines list of individual elements forming this compound.
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcAttributeElement attributeElements = 4;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcAttributeElementOrBuilder getAttributeElementsOrBuilder(
      int index);

  /**
   * <pre>
   * TODO JNO - document me
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope indexedInScopes = 5;</code>
   * @return A list containing the indexedInScopes.
   */
  java.util.List<io.evitadb.externalApi.grpc.generated.GrpcEntityScope> getIndexedInScopesList();
  /**
   * <pre>
   * TODO JNO - document me
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope indexedInScopes = 5;</code>
   * @return The count of indexedInScopes.
   */
  int getIndexedInScopesCount();
  /**
   * <pre>
   * TODO JNO - document me
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope indexedInScopes = 5;</code>
   * @param index The index of the element to return.
   * @return The indexedInScopes at the given index.
   */
  io.evitadb.externalApi.grpc.generated.GrpcEntityScope getIndexedInScopes(int index);
  /**
   * <pre>
   * TODO JNO - document me
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope indexedInScopes = 5;</code>
   * @return A list containing the enum numeric values on the wire for indexedInScopes.
   */
  java.util.List<java.lang.Integer>
  getIndexedInScopesValueList();
  /**
   * <pre>
   * TODO JNO - document me
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcEntityScope indexedInScopes = 5;</code>
   * @param index The index of the value to return.
   * @return The enum numeric value on the wire of indexedInScopes at the given index.
   */
  int getIndexedInScopesValue(int index);
}
