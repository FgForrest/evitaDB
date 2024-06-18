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
// source: GrpcEvitaAPI.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcEvitaSessionResponseOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * UUID of the created session.
   * </pre>
   *
   * <code>string sessionId = 1;</code>
   * @return The sessionId.
   */
  java.lang.String getSessionId();
  /**
   * <pre>
   * UUID of the created session.
   * </pre>
   *
   * <code>string sessionId = 1;</code>
   * @return The bytes for sessionId.
   */
  com.google.protobuf.ByteString
      getSessionIdBytes();

  /**
   * <pre>
   * Type of the created session.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSessionType sessionType = 2;</code>
   * @return The enum numeric value on the wire for sessionType.
   */
  int getSessionTypeValue();
  /**
   * <pre>
   * Type of the created session.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcSessionType sessionType = 2;</code>
   * @return The sessionType.
   */
  io.evitadb.externalApi.grpc.generated.GrpcSessionType getSessionType();

  /**
   * <pre>
   * Commit behaviour
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcCommitBehavior commitBehaviour = 3;</code>
   * @return The enum numeric value on the wire for commitBehaviour.
   */
  int getCommitBehaviourValue();
  /**
   * <pre>
   * Commit behaviour
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcCommitBehavior commitBehaviour = 3;</code>
   * @return The commitBehaviour.
   */
  io.evitadb.externalApi.grpc.generated.GrpcCommitBehavior getCommitBehaviour();

  /**
   * <pre>
   * State of the catalog after the session was created.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcCatalogState catalogState = 4;</code>
   * @return The enum numeric value on the wire for catalogState.
   */
  int getCatalogStateValue();
  /**
   * <pre>
   * State of the catalog after the session was created.
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcCatalogState catalogState = 4;</code>
   * @return The catalogState.
   */
  io.evitadb.externalApi.grpc.generated.GrpcCatalogState getCatalogState();

  /**
   * <pre>
   * UUID of the catalog the session is bound to.
   * </pre>
   *
   * <code>string catalogId = 5;</code>
   * @return The catalogId.
   */
  java.lang.String getCatalogId();
  /**
   * <pre>
   * UUID of the catalog the session is bound to.
   * </pre>
   *
   * <code>string catalogId = 5;</code>
   * @return The bytes for catalogId.
   */
  com.google.protobuf.ByteString
      getCatalogIdBytes();
}
