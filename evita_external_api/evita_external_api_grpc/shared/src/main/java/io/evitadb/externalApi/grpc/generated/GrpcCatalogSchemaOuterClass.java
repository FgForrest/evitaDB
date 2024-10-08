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
// source: GrpcCatalogSchema.proto

package io.evitadb.externalApi.grpc.generated;

public final class GrpcCatalogSchemaOuterClass {
  private GrpcCatalogSchemaOuterClass() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcCatalogSchema_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcCatalogSchema_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcCatalogSchema_AttributesEntry_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcCatalogSchema_AttributesEntry_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcGlobalAttributeSchema_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcGlobalAttributeSchema_fieldAccessorTable;

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n\027GrpcCatalogSchema.proto\022%io.evitadb.ex" +
      "ternalApi.grpc.generated\032\017GrpcEnums.prot" +
      "o\032\030GrpcEvitaDataTypes.proto\032\036google/prot" +
      "obuf/wrappers.proto\"\344\003\n\021GrpcCatalogSchem" +
      "a\022\014\n\004name\030\001 \001(\t\022\017\n\007version\030\002 \001(\005\0221\n\013desc" +
      "ription\030\003 \001(\0132\034.google.protobuf.StringVa" +
      "lue\022]\n\024catalogEvolutionMode\030\004 \003(\0162?.io.e" +
      "vitadb.externalApi.grpc.generated.GrpcCa" +
      "talogEvolutionMode\022\\\n\nattributes\030\005 \003(\0132H" +
      ".io.evitadb.externalApi.grpc.generated.G" +
      "rpcCatalogSchema.AttributesEntry\022K\n\013name" +
      "Variant\030\006 \003(\01326.io.evitadb.externalApi.g" +
      "rpc.generated.GrpcNameVariant\032s\n\017Attribu" +
      "tesEntry\022\013\n\003key\030\001 \001(\t\022O\n\005value\030\002 \001(\0132@.i" +
      "o.evitadb.externalApi.grpc.generated.Grp" +
      "cGlobalAttributeSchema:\0028\001\"\256\005\n\031GrpcGloba" +
      "lAttributeSchema\022\014\n\004name\030\001 \001(\t\0221\n\013descri" +
      "ption\030\002 \001(\0132\034.google.protobuf.StringValu" +
      "e\0227\n\021deprecationNotice\030\003 \001(\0132\034.google.pr" +
      "otobuf.StringValue\022R\n\006unique\030\004 \001(\0162B.io." +
      "evitadb.externalApi.grpc.generated.GrpcA" +
      "ttributeUniquenessType\022\022\n\nfilterable\030\005 \001" +
      "(\010\022\020\n\010sortable\030\006 \001(\010\022\021\n\tlocalized\030\007 \001(\010\022" +
      "\020\n\010nullable\030\010 \001(\010\022\026\n\016representative\030\t \001(" +
      "\010\022F\n\004type\030\n \001(\01628.io.evitadb.externalApi" +
      ".grpc.generated.GrpcEvitaDataType\022K\n\014def" +
      "aultValue\030\013 \001(\01325.io.evitadb.externalApi" +
      ".grpc.generated.GrpcEvitaValue\022\034\n\024indexe" +
      "dDecimalPlaces\030\014 \001(\005\022`\n\016uniqueGlobally\030\r" +
      " \001(\0162H.io.evitadb.externalApi.grpc.gener" +
      "ated.GrpcGlobalAttributeUniquenessType\022K" +
      "\n\013nameVariant\030\016 \003(\01326.io.evitadb.externa" +
      "lApi.grpc.generated.GrpcNameVariantB\014P\001\252" +
      "\002\007EvitaDBb\006proto3"
    };
    descriptor = com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
          io.evitadb.externalApi.grpc.generated.GrpcEnums.getDescriptor(),
          io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.getDescriptor(),
          com.google.protobuf.WrappersProto.getDescriptor(),
        });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcCatalogSchema_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcCatalogSchema_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcCatalogSchema_descriptor,
        new java.lang.String[] { "Name", "Version", "Description", "CatalogEvolutionMode", "Attributes", "NameVariant", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcCatalogSchema_AttributesEntry_descriptor =
      internal_static_io_evitadb_externalApi_grpc_generated_GrpcCatalogSchema_descriptor.getNestedTypes().get(0);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcCatalogSchema_AttributesEntry_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcCatalogSchema_AttributesEntry_descriptor,
        new java.lang.String[] { "Key", "Value", });
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcGlobalAttributeSchema_descriptor =
      getDescriptor().getMessageTypes().get(1);
    internal_static_io_evitadb_externalApi_grpc_generated_GrpcGlobalAttributeSchema_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_io_evitadb_externalApi_grpc_generated_GrpcGlobalAttributeSchema_descriptor,
        new java.lang.String[] { "Name", "Description", "DeprecationNotice", "Unique", "Filterable", "Sortable", "Localized", "Nullable", "Representative", "Type", "DefaultValue", "IndexedDecimalPlaces", "UniqueGlobally", "NameVariant", });
    io.evitadb.externalApi.grpc.generated.GrpcEnums.getDescriptor();
    io.evitadb.externalApi.grpc.generated.GrpcEvitaDataTypes.getDescriptor();
    com.google.protobuf.WrappersProto.getDescriptor();
  }

  // @@protoc_insertion_point(outer_class_scope)
}
