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
// source: GrpcEntityMutation.proto

package io.evitadb.externalApi.grpc.generated;

public final class GrpcEntityMutationOuterClass {
  private GrpcEntityMutationOuterClass() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcEntityUpsertMutation_descriptor;
  static final
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcEntityUpsertMutation_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcEntityRemoveMutation_descriptor;
  static final
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcEntityRemoveMutation_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcEntityMutation_descriptor;
  static final
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcEntityMutation_fieldAccessorTable;

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n\030GrpcEntityMutation.proto\022%io.evitadb.e" +
      "xternalApi.grpc.generated\032\017GrpcEnums.pro" +
      "to\032\027GrpcLocalMutation.proto\032\036google/prot" +
      "obuf/wrappers.proto\"\207\002\n\030GrpcEntityUpsert" +
      "Mutation\022\022\n\nentityType\030\001 \001(\t\0225\n\020entityPr" +
      "imaryKey\030\002 \001(\0132\033.google.protobuf.Int32Va" +
      "lue\022S\n\017entityExistence\030\003 \001(\0162:.io.evitad" +
      "b.externalApi.grpc.generated.GrpcEntityE" +
      "xistence\022K\n\tmutations\030\004 \003(\01328.io.evitadb" +
      ".externalApi.grpc.generated.GrpcLocalMut" +
      "ation\"H\n\030GrpcEntityRemoveMutation\022\022\n\nent" +
      "ityType\030\001 \001(\t\022\030\n\020entityPrimaryKey\030\002 \001(\005\"" +
      "\342\001\n\022GrpcEntityMutation\022_\n\024entityUpsertMu" +
      "tation\030\001 \001(\0132?.io.evitadb.externalApi.gr" +
      "pc.generated.GrpcEntityUpsertMutationH\000\022" +
      "_\n\024entityRemoveMutation\030\002 \001(\0132?.io.evita" +
      "db.externalApi.grpc.generated.GrpcEntity" +
      "RemoveMutationH\000B\n\n\010mutationB\014P\001\252\002\007Evita" +
      "DBb\006proto3"
    };
    descriptor = com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
          io.evitadb.externalApi.grpc.generated.GrpcEnums.getDescriptor(),
          io.evitadb.externalApi.grpc.generated.GrpcLocalMutationOuterClass.getDescriptor(),
          com.google.protobuf.WrappersProto.getDescriptor(),
        });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcEntityUpsertMutation_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcEntityUpsertMutation_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcEntityUpsertMutation_descriptor,
        new java.lang.String[] { "EntityType", "EntityPrimaryKey", "EntityExistence", "Mutations", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcEntityRemoveMutation_descriptor =
      getDescriptor().getMessageTypes().get(1);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcEntityRemoveMutation_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcEntityRemoveMutation_descriptor,
        new java.lang.String[] { "EntityType", "EntityPrimaryKey", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcEntityMutation_descriptor =
      getDescriptor().getMessageTypes().get(2);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcEntityMutation_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcEntityMutation_descriptor,
        new java.lang.String[] { "EntityUpsertMutation", "EntityRemoveMutation", "Mutation", });
    io.evitadb.externalApi.grpc.generated.GrpcEnums.getDescriptor();
    io.evitadb.externalApi.grpc.generated.GrpcLocalMutationOuterClass.getDescriptor();
    com.google.protobuf.WrappersProto.getDescriptor();
  }

  // @@protoc_insertion_point(outer_class_scope)
}
