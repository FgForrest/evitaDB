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
// source: GrpcEvitaManagementAPI.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcEndpointOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcEndpoint)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * logical name of the endpoint
   * </pre>
   *
   * <code>string name = 1;</code>
   * @return The name.
   */
  java.lang.String getName();
  /**
   * <pre>
   * logical name of the endpoint
   * </pre>
   *
   * <code>string name = 1;</code>
   * @return The bytes for name.
   */
  com.google.protobuf.ByteString
      getNameBytes();

  /**
   * <pre>
   * absolute URL of the endpoint
   * </pre>
   *
   * <code>repeated string url = 2;</code>
   * @return A list containing the url.
   */
  java.util.List<java.lang.String>
      getUrlList();
  /**
   * <pre>
   * absolute URL of the endpoint
   * </pre>
   *
   * <code>repeated string url = 2;</code>
   * @return The count of url.
   */
  int getUrlCount();
  /**
   * <pre>
   * absolute URL of the endpoint
   * </pre>
   *
   * <code>repeated string url = 2;</code>
   * @param index The index of the element to return.
   * @return The url at the given index.
   */
  java.lang.String getUrl(int index);
  /**
   * <pre>
   * absolute URL of the endpoint
   * </pre>
   *
   * <code>repeated string url = 2;</code>
   * @param index The index of the value to return.
   * @return The bytes of the url at the given index.
   */
  com.google.protobuf.ByteString
      getUrlBytes(int index);
}
