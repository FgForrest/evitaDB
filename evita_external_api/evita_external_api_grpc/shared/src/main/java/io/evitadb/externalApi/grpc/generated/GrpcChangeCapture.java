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
// source: GrpcChangeCapture.proto

package io.evitadb.externalApi.grpc.generated;

public final class GrpcChangeCapture {
  private GrpcChangeCapture() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcChangeCaptureCriteria_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcChangeCaptureCriteria_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcChangeCaptureSchemaSite_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcChangeCaptureSchemaSite_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcChangeCaptureDataSite_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcChangeCaptureDataSite_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcChangeCatalogCapture_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcChangeCatalogCapture_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcChangeSystemCapture_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcChangeSystemCapture_fieldAccessorTable;

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n\027GrpcChangeCapture.proto\022%io.evitadb.ex" +
      "ternalApi.grpc.generated\032\036google/protobu" +
      "f/wrappers.proto\032\030GrpcEntityMutation.pro" +
      "to\032\027GrpcLocalMutation.proto\032\036GrpcEntityS" +
      "chemaMutation.proto\032\037GrpcCatalogSchemaMu" +
      "tation.proto\"\237\002\n\031GrpcChangeCaptureCriter" +
      "ia\022J\n\004area\030\001 \001(\0162<.io.evitadb.externalAp" +
      "i.grpc.generated.GrpcChangeCaptureArea\022X" +
      "\n\nschemaSite\030\002 \001(\0132B.io.evitadb.external" +
      "Api.grpc.generated.GrpcChangeCaptureSche" +
      "maSiteH\000\022T\n\010dataSite\030\003 \001(\0132@.io.evitadb." +
      "externalApi.grpc.generated.GrpcChangeCap" +
      "tureDataSiteH\000B\006\n\004site\"\203\002\n\033GrpcChangeCap" +
      "tureSchemaSite\0220\n\nentityType\030\001 \001(\0132\034.goo" +
      "gle.protobuf.StringValue\022T\n\toperation\030\002 " +
      "\003(\0162A.io.evitadb.externalApi.grpc.genera" +
      "ted.GrpcChangeCaptureOperation\022\\\n\rcontai" +
      "nerType\030\003 \003(\0162E.io.evitadb.externalApi.g" +
      "rpc.generated.GrpcChangeCaptureContainer" +
      "Type\"\317\002\n\031GrpcChangeCaptureDataSite\0220\n\nen" +
      "tityType\030\001 \001(\0132\034.google.protobuf.StringV" +
      "alue\0225\n\020entityPrimaryKey\030\002 \001(\0132\033.google." +
      "protobuf.Int32Value\022T\n\toperation\030\003 \003(\0162A" +
      ".io.evitadb.externalApi.grpc.generated.G" +
      "rpcChangeCaptureOperation\022\\\n\rcontainerTy" +
      "pe\030\004 \003(\0162E.io.evitadb.externalApi.grpc.g" +
      "enerated.GrpcChangeCaptureContainerType\022" +
      "\025\n\rcontainerName\030\005 \003(\t\"\231\004\n\030GrpcChangeCat" +
      "alogCapture\022\017\n\007version\030\001 \001(\003\022\r\n\005index\030\002 " +
      "\001(\005\022J\n\004area\030\003 \001(\0162<.io.evitadb.externalA" +
      "pi.grpc.generated.GrpcChangeCaptureArea\022" +
      "0\n\nentityType\030\004 \001(\0132\034.google.protobuf.St" +
      "ringValue\022T\n\toperation\030\005 \001(\0162A.io.evitad" +
      "b.externalApi.grpc.generated.GrpcChangeC" +
      "aptureOperation\022Y\n\016schemaMutation\030\006 \001(\0132" +
      "?.io.evitadb.externalApi.grpc.generated." +
      "GrpcEntitySchemaMutationH\000\022S\n\016entityMuta" +
      "tion\030\007 \001(\01329.io.evitadb.externalApi.grpc" +
      ".generated.GrpcEntityMutationH\000\022Q\n\rlocal" +
      "Mutation\030\010 \001(\01328.io.evitadb.externalApi." +
      "grpc.generated.GrpcLocalMutationH\000B\006\n\004bo" +
      "dy\"\240\002\n\027GrpcChangeSystemCapture\022\017\n\007versio" +
      "n\030\001 \001(\003\022\r\n\005index\030\002 \001(\005\022-\n\007catalog\030\003 \001(\0132" +
      "\034.google.protobuf.StringValue\022T\n\toperati" +
      "on\030\005 \001(\0162A.io.evitadb.externalApi.grpc.g" +
      "enerated.GrpcChangeCaptureOperation\022`\n\016s" +
      "ystemMutation\030\006 \001(\0132H.io.evitadb.externa" +
      "lApi.grpc.generated.GrpcTopLevelCatalogS" +
      "chemaMutation*A\n\025GrpcChangeCaptureArea\022\n" +
      "\n\006SCHEMA\020\000\022\010\n\004DATA\020\001\022\022\n\016INFRASTRUCTURE\020\002" +
      "*E\n\032GrpcChangeCaptureOperation\022\n\n\006UPSERT" +
      "\020\000\022\n\n\006REMOVE\020\001\022\017\n\013TRANSACTION\020\002*\263\001\n\036Grpc" +
      "ChangeCaptureContainerType\022\025\n\021CONTAINER_" +
      "CATALOG\020\000\022\024\n\020CONTAINER_ENTITY\020\001\022\027\n\023CONTA" +
      "INER_ATTRIBUTE\020\002\022\035\n\031CONTAINER_ASSOCIATED" +
      "_DATA\020\003\022\023\n\017CONTAINER_PRICE\020\004\022\027\n\023CONTAINE" +
      "R_REFERENCE\020\005*>\n\030GrpcChangeCaptureConten" +
      "t\022\021\n\rCHANGE_HEADER\020\000\022\017\n\013CHANGE_BODY\020\001*:\n" +
      "\027GrpcCaptureResponseType\022\023\n\017ACKNOWLEDGEM" +
      "ENT\020\000\022\n\n\006CHANGE\020\001B\014P\001\252\002\007EvitaDBb\006proto3"
    };
    descriptor = com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
          com.google.protobuf.WrappersProto.getDescriptor(),
          io.evitadb.externalApi.grpc.generated.GrpcEntityMutationOuterClass.getDescriptor(),
          io.evitadb.externalApi.grpc.generated.GrpcLocalMutationOuterClass.getDescriptor(),
          io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutationOuterClass.getDescriptor(),
          io.evitadb.externalApi.grpc.generated.GrpcCatalogSchemaMutation.getDescriptor(),
        });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcChangeCaptureCriteria_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcChangeCaptureCriteria_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcChangeCaptureCriteria_descriptor,
        new java.lang.String[] { "Area", "SchemaSite", "DataSite", "Site", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcChangeCaptureSchemaSite_descriptor =
      getDescriptor().getMessageTypes().get(1);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcChangeCaptureSchemaSite_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcChangeCaptureSchemaSite_descriptor,
        new java.lang.String[] { "EntityType", "Operation", "ContainerType", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcChangeCaptureDataSite_descriptor =
      getDescriptor().getMessageTypes().get(2);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcChangeCaptureDataSite_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcChangeCaptureDataSite_descriptor,
        new java.lang.String[] { "EntityType", "EntityPrimaryKey", "Operation", "ContainerType", "ContainerName", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcChangeCatalogCapture_descriptor =
      getDescriptor().getMessageTypes().get(3);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcChangeCatalogCapture_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcChangeCatalogCapture_descriptor,
        new java.lang.String[] { "Version", "Index", "Area", "EntityType", "Operation", "SchemaMutation", "EntityMutation", "LocalMutation", "Body", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcChangeSystemCapture_descriptor =
      getDescriptor().getMessageTypes().get(4);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcChangeSystemCapture_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcChangeSystemCapture_descriptor,
        new java.lang.String[] { "Version", "Index", "Catalog", "Operation", "SystemMutation", });
    com.google.protobuf.WrappersProto.getDescriptor();
    io.evitadb.externalApi.grpc.generated.GrpcEntityMutationOuterClass.getDescriptor();
    io.evitadb.externalApi.grpc.generated.GrpcLocalMutationOuterClass.getDescriptor();
    io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutationOuterClass.getDescriptor();
    io.evitadb.externalApi.grpc.generated.GrpcCatalogSchemaMutation.getDescriptor();
  }

  // @@protoc_insertion_point(outer_class_scope)
}
