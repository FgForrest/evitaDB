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

public final class GrpcSortableAttributeCompoundSchemaMutations {
  private GrpcSortableAttributeCompoundSchemaMutations() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcCreateSortableAttributeCompoundSchemaMutation_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcCreateSortableAttributeCompoundSchemaMutation_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaDeprecationNoticeMutation_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaDeprecationNoticeMutation_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaDescriptionMutation_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaDescriptionMutation_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaNameMutation_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaNameMutation_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcSetSortableAttributeCompoundIndexedMutation_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcSetSortableAttributeCompoundIndexedMutation_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcRemoveSortableAttributeCompoundSchemaMutation_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcRemoveSortableAttributeCompoundSchemaMutation_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcSortableAttributeCompoundSchemaMutation_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcSortableAttributeCompoundSchemaMutation_fieldAccessorTable;

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n2GrpcSortableAttributeCompoundSchemaMut" +
      "ations.proto\022%io.evitadb.externalApi.grp" +
      "c.generated\032\026GrpcEntitySchema.proto\032\017Grp" +
      "cEnums.proto\032\036google/protobuf/wrappers.p" +
      "roto\"\326\002\n1GrpcCreateSortableAttributeComp" +
      "oundSchemaMutation\022\014\n\004name\030\001 \001(\t\0221\n\013desc" +
      "ription\030\002 \001(\0132\034.google.protobuf.StringVa" +
      "lue\0227\n\021deprecationNotice\030\003 \001(\0132\034.google." +
      "protobuf.StringValue\022V\n\021attributeElement" +
      "s\030\004 \003(\0132;.io.evitadb.externalApi.grpc.ge" +
      "nerated.GrpcAttributeElement\022O\n\017indexedI" +
      "nScopes\030\005 \003(\01626.io.evitadb.externalApi.g" +
      "rpc.generated.GrpcEntityScope\"\213\001\nBGrpcMo" +
      "difySortableAttributeCompoundSchemaDepre" +
      "cationNoticeMutation\022\014\n\004name\030\001 \001(\t\0227\n\021de" +
      "precationNotice\030\002 \001(\0132\034.google.protobuf." +
      "StringValue\"\177\n<GrpcModifySortableAttribu" +
      "teCompoundSchemaDescriptionMutation\022\014\n\004n" +
      "ame\030\001 \001(\t\0221\n\013description\030\002 \001(\0132\034.google." +
      "protobuf.StringValue\"V\n5GrpcModifySortab" +
      "leAttributeCompoundSchemaNameMutation\022\014\n" +
      "\004name\030\001 \001(\t\022\017\n\007newName\030\002 \001(\t\"\220\001\n/GrpcSet" +
      "SortableAttributeCompoundIndexedMutation" +
      "\022\014\n\004name\030\001 \001(\t\022O\n\017indexedInScopes\030\002 \003(\0162" +
      "6.io.evitadb.externalApi.grpc.generated." +
      "GrpcEntityScope\"A\n1GrpcRemoveSortableAtt" +
      "ributeCompoundSchemaMutation\022\014\n\004name\030\001 \001" +
      "(\t\"\355\007\n+GrpcSortableAttributeCompoundSche" +
      "maMutation\022\221\001\n-createSortableAttributeCo" +
      "mpoundSchemaMutation\030\001 \001(\0132X.io.evitadb." +
      "externalApi.grpc.generated.GrpcCreateSor" +
      "tableAttributeCompoundSchemaMutationH\000\022\263" +
      "\001\n>modifySortableAttributeCompoundSchema" +
      "DeprecationNoticeMutation\030\002 \001(\0132i.io.evi" +
      "tadb.externalApi.grpc.generated.GrpcModi" +
      "fySortableAttributeCompoundSchemaDepreca" +
      "tionNoticeMutationH\000\022\247\001\n8modifySortableA" +
      "ttributeCompoundSchemaDescriptionMutatio" +
      "n\030\003 \001(\0132c.io.evitadb.externalApi.grpc.ge" +
      "nerated.GrpcModifySortableAttributeCompo" +
      "undSchemaDescriptionMutationH\000\022\231\001\n1modif" +
      "ySortableAttributeCompoundSchemaNameMuta" +
      "tion\030\004 \001(\0132\\.io.evitadb.externalApi.grpc" +
      ".generated.GrpcModifySortableAttributeCo" +
      "mpoundSchemaNameMutationH\000\022\221\001\n-removeSor" +
      "tableAttributeCompoundSchemaMutation\030\005 \001" +
      "(\0132X.io.evitadb.externalApi.grpc.generat" +
      "ed.GrpcRemoveSortableAttributeCompoundSc" +
      "hemaMutationH\000\022\215\001\n+setSortableAttributeC" +
      "ompoundIndexedMutation\030\006 \001(\0132V.io.evitad" +
      "b.externalApi.grpc.generated.GrpcSetSort" +
      "ableAttributeCompoundIndexedMutationH\000B\n" +
      "\n\010mutationB\014P\001\252\002\007EvitaDBb\006proto3"
    };
    descriptor = com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
          io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaOuterClass.getDescriptor(),
          io.evitadb.externalApi.grpc.generated.GrpcEnums.getDescriptor(),
          com.google.protobuf.WrappersProto.getDescriptor(),
        });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcCreateSortableAttributeCompoundSchemaMutation_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcCreateSortableAttributeCompoundSchemaMutation_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcCreateSortableAttributeCompoundSchemaMutation_descriptor,
        new java.lang.String[] { "Name", "Description", "DeprecationNotice", "AttributeElements", "IndexedInScopes", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaDeprecationNoticeMutation_descriptor =
      getDescriptor().getMessageTypes().get(1);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaDeprecationNoticeMutation_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaDeprecationNoticeMutation_descriptor,
        new java.lang.String[] { "Name", "DeprecationNotice", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaDescriptionMutation_descriptor =
      getDescriptor().getMessageTypes().get(2);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaDescriptionMutation_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaDescriptionMutation_descriptor,
        new java.lang.String[] { "Name", "Description", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaNameMutation_descriptor =
      getDescriptor().getMessageTypes().get(3);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaNameMutation_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcModifySortableAttributeCompoundSchemaNameMutation_descriptor,
        new java.lang.String[] { "Name", "NewName", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcSetSortableAttributeCompoundIndexedMutation_descriptor =
      getDescriptor().getMessageTypes().get(4);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcSetSortableAttributeCompoundIndexedMutation_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcSetSortableAttributeCompoundIndexedMutation_descriptor,
        new java.lang.String[] { "Name", "IndexedInScopes", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcRemoveSortableAttributeCompoundSchemaMutation_descriptor =
      getDescriptor().getMessageTypes().get(5);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcRemoveSortableAttributeCompoundSchemaMutation_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcRemoveSortableAttributeCompoundSchemaMutation_descriptor,
        new java.lang.String[] { "Name", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcSortableAttributeCompoundSchemaMutation_descriptor =
      getDescriptor().getMessageTypes().get(6);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcSortableAttributeCompoundSchemaMutation_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcSortableAttributeCompoundSchemaMutation_descriptor,
        new java.lang.String[] { "CreateSortableAttributeCompoundSchemaMutation", "ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation", "ModifySortableAttributeCompoundSchemaDescriptionMutation", "ModifySortableAttributeCompoundSchemaNameMutation", "RemoveSortableAttributeCompoundSchemaMutation", "SetSortableAttributeCompoundIndexedMutation", "Mutation", });
    io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaOuterClass.getDescriptor();
    io.evitadb.externalApi.grpc.generated.GrpcEnums.getDescriptor();
    com.google.protobuf.WrappersProto.getDescriptor();
  }

  // @@protoc_insertion_point(outer_class_scope)
}
