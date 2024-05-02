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
// source: GrpcEvitaAPI.proto

package io.evitadb.externalApi.grpc.generated;

public interface GrpcEvitaServerStatusResponseOrBuilder extends
    // @@protoc_insertion_point(interface_extends:io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * Version of evitaDB server taken from the MANIFEST.MF file
   * </pre>
   *
   * <code>string version = 1;</code>
   * @return The version.
   */
  java.lang.String getVersion();
  /**
   * <pre>
   * Version of evitaDB server taken from the MANIFEST.MF file
   * </pre>
   *
   * <code>string version = 1;</code>
   * @return The bytes for version.
   */
  com.google.protobuf.ByteString
      getVersionBytes();

  /**
   * <pre>
   * Date and time when the server was started
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime startedAt = 2;</code>
   * @return Whether the startedAt field is set.
   */
  boolean hasStartedAt();
  /**
   * <pre>
   * Date and time when the server was started
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime startedAt = 2;</code>
   * @return The startedAt.
   */
  io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime getStartedAt();
  /**
   * <pre>
   * Date and time when the server was started
   * </pre>
   *
   * <code>.io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTime startedAt = 2;</code>
   */
  io.evitadb.externalApi.grpc.generated.GrpcOffsetDateTimeOrBuilder getStartedAtOrBuilder();

  /**
   * <pre>
   * Duration of time since the server was started (seconds)
   * </pre>
   *
   * <code>int64 uptime = 3;</code>
   * @return The uptime.
   */
  long getUptime();

  /**
   * <pre>
   * Unique identifier of the server instance
   * </pre>
   *
   * <code>string instanceId = 4;</code>
   * @return The instanceId.
   */
  java.lang.String getInstanceId();
  /**
   * <pre>
   * Unique identifier of the server instance
   * </pre>
   *
   * <code>string instanceId = 4;</code>
   * @return The bytes for instanceId.
   */
  com.google.protobuf.ByteString
      getInstanceIdBytes();

  /**
   * <pre>
   * Number of corrupted catalogs
   * </pre>
   *
   * <code>int32 catalogsCorrupted = 5;</code>
   * @return The catalogsCorrupted.
   */
  int getCatalogsCorrupted();

  /**
   * <pre>
   * Number of catalogs that are ok
   * </pre>
   *
   * <code>int32 catalogsOk = 6;</code>
   * @return The catalogsOk.
   */
  int getCatalogsOk();

  /**
   * <pre>
   * Set of all observed health problems
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcHealthProblem healthProblems = 7;</code>
   * @return A list containing the healthProblems.
   */
  java.util.List<io.evitadb.externalApi.grpc.generated.GrpcHealthProblem> getHealthProblemsList();
  /**
   * <pre>
   * Set of all observed health problems
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcHealthProblem healthProblems = 7;</code>
   * @return The count of healthProblems.
   */
  int getHealthProblemsCount();
  /**
   * <pre>
   * Set of all observed health problems
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcHealthProblem healthProblems = 7;</code>
   * @param index The index of the element to return.
   * @return The healthProblems at the given index.
   */
  io.evitadb.externalApi.grpc.generated.GrpcHealthProblem getHealthProblems(int index);
  /**
   * <pre>
   * Set of all observed health problems
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcHealthProblem healthProblems = 7;</code>
   * @return A list containing the enum numeric values on the wire for healthProblems.
   */
  java.util.List<java.lang.Integer>
  getHealthProblemsValueList();
  /**
   * <pre>
   * Set of all observed health problems
   * </pre>
   *
   * <code>repeated .io.evitadb.externalApi.grpc.generated.GrpcHealthProblem healthProblems = 7;</code>
   * @param index The index of the value to return.
   * @return The enum numeric value on the wire of healthProblems at the given index.
   */
  int getHealthProblemsValue(int index);
}
