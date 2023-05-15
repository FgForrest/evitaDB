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

/**
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest}
 */
public final class GrpcRenameCatalogRequest extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest)
    GrpcRenameCatalogRequestOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GrpcRenameCatalogRequest.newBuilder() to construct.
  private GrpcRenameCatalogRequest(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GrpcRenameCatalogRequest() {
    catalogName_ = "";
    newCatalogName_ = "";
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GrpcRenameCatalogRequest();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GrpcRenameCatalogRequest(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    this();
    if (extensionRegistry == null) {
      throw new java.lang.NullPointerException();
    }
    com.google.protobuf.UnknownFieldSet.Builder unknownFields =
        com.google.protobuf.UnknownFieldSet.newBuilder();
    try {
      boolean done = false;
      while (!done) {
        int tag = input.readTag();
        switch (tag) {
          case 0:
            done = true;
            break;
          case 10: {
            java.lang.String s = input.readStringRequireUtf8();

            catalogName_ = s;
            break;
          }
          case 18: {
            java.lang.String s = input.readStringRequireUtf8();

            newCatalogName_ = s;
            break;
          }
          default: {
            if (!parseUnknownField(
                input, unknownFields, extensionRegistry, tag)) {
              done = true;
            }
            break;
          }
        }
      }
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw e.setUnfinishedMessage(this);
    } catch (java.io.IOException e) {
      throw new com.google.protobuf.InvalidProtocolBufferException(
          e).setUnfinishedMessage(this);
    } finally {
      this.unknownFields = unknownFields.build();
      makeExtensionsImmutable();
    }
  }
  public static final com.google.protobuf.Descriptors.Descriptor
      getDescriptor() {
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcRenameCatalogRequest_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcRenameCatalogRequest_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest.class, io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest.Builder.class);
  }

  public static final int CATALOGNAME_FIELD_NUMBER = 1;
  private volatile java.lang.Object catalogName_;
  /**
   * <code>string catalogName = 1;</code>
   * @return The catalogName.
   */
  @java.lang.Override
  public java.lang.String getCatalogName() {
    java.lang.Object ref = catalogName_;
    if (ref instanceof java.lang.String) {
      return (java.lang.String) ref;
    } else {
      com.google.protobuf.ByteString bs =
          (com.google.protobuf.ByteString) ref;
      java.lang.String s = bs.toStringUtf8();
      catalogName_ = s;
      return s;
    }
  }
  /**
   * <code>string catalogName = 1;</code>
   * @return The bytes for catalogName.
   */
  @java.lang.Override
  public com.google.protobuf.ByteString
      getCatalogNameBytes() {
    java.lang.Object ref = catalogName_;
    if (ref instanceof java.lang.String) {
      com.google.protobuf.ByteString b =
          com.google.protobuf.ByteString.copyFromUtf8(
              (java.lang.String) ref);
      catalogName_ = b;
      return b;
    } else {
      return (com.google.protobuf.ByteString) ref;
    }
  }

  public static final int NEWCATALOGNAME_FIELD_NUMBER = 2;
  private volatile java.lang.Object newCatalogName_;
  /**
   * <code>string newCatalogName = 2;</code>
   * @return The newCatalogName.
   */
  @java.lang.Override
  public java.lang.String getNewCatalogName() {
    java.lang.Object ref = newCatalogName_;
    if (ref instanceof java.lang.String) {
      return (java.lang.String) ref;
    } else {
      com.google.protobuf.ByteString bs =
          (com.google.protobuf.ByteString) ref;
      java.lang.String s = bs.toStringUtf8();
      newCatalogName_ = s;
      return s;
    }
  }
  /**
   * <code>string newCatalogName = 2;</code>
   * @return The bytes for newCatalogName.
   */
  @java.lang.Override
  public com.google.protobuf.ByteString
      getNewCatalogNameBytes() {
    java.lang.Object ref = newCatalogName_;
    if (ref instanceof java.lang.String) {
      com.google.protobuf.ByteString b =
          com.google.protobuf.ByteString.copyFromUtf8(
              (java.lang.String) ref);
      newCatalogName_ = b;
      return b;
    } else {
      return (com.google.protobuf.ByteString) ref;
    }
  }

  private byte memoizedIsInitialized = -1;
  @java.lang.Override
  public final boolean isInitialized() {
    byte isInitialized = memoizedIsInitialized;
    if (isInitialized == 1) return true;
    if (isInitialized == 0) return false;

    memoizedIsInitialized = 1;
    return true;
  }

  @java.lang.Override
  public void writeTo(com.google.protobuf.CodedOutputStream output)
                      throws java.io.IOException {
    if (!com.google.protobuf.GeneratedMessageV3.isStringEmpty(catalogName_)) {
      com.google.protobuf.GeneratedMessageV3.writeString(output, 1, catalogName_);
    }
    if (!com.google.protobuf.GeneratedMessageV3.isStringEmpty(newCatalogName_)) {
      com.google.protobuf.GeneratedMessageV3.writeString(output, 2, newCatalogName_);
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (!com.google.protobuf.GeneratedMessageV3.isStringEmpty(catalogName_)) {
      size += com.google.protobuf.GeneratedMessageV3.computeStringSize(1, catalogName_);
    }
    if (!com.google.protobuf.GeneratedMessageV3.isStringEmpty(newCatalogName_)) {
      size += com.google.protobuf.GeneratedMessageV3.computeStringSize(2, newCatalogName_);
    }
    size += unknownFields.getSerializedSize();
    memoizedSize = size;
    return size;
  }

  @java.lang.Override
  public boolean equals(final java.lang.Object obj) {
    if (obj == this) {
     return true;
    }
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest other = (io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest) obj;

    if (!getCatalogName()
        .equals(other.getCatalogName())) return false;
    if (!getNewCatalogName()
        .equals(other.getNewCatalogName())) return false;
    if (!unknownFields.equals(other.unknownFields)) return false;
    return true;
  }

  @java.lang.Override
  public int hashCode() {
    if (memoizedHashCode != 0) {
      return memoizedHashCode;
    }
    int hash = 41;
    hash = (19 * hash) + getDescriptor().hashCode();
    hash = (37 * hash) + CATALOGNAME_FIELD_NUMBER;
    hash = (53 * hash) + getCatalogName().hashCode();
    hash = (37 * hash) + NEWCATALOGNAME_FIELD_NUMBER;
    hash = (53 * hash) + getNewCatalogName().hashCode();
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest parseFrom(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }

  @java.lang.Override
  public Builder newBuilderForType() { return newBuilder(); }
  public static Builder newBuilder() {
    return DEFAULT_INSTANCE.toBuilder();
  }
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest prototype) {
    return DEFAULT_INSTANCE.toBuilder().mergeFrom(prototype);
  }
  @java.lang.Override
  public Builder toBuilder() {
    return this == DEFAULT_INSTANCE
        ? new Builder() : new Builder().mergeFrom(this);
  }

  @java.lang.Override
  protected Builder newBuilderForType(
      com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
    Builder builder = new Builder(parent);
    return builder;
  }
  /**
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest)
      io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequestOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcRenameCatalogRequest_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcRenameCatalogRequest_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest.class, io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest.newBuilder()
    private Builder() {
      maybeForceBuilderInitialization();
    }

    private Builder(
        com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
      super(parent);
      maybeForceBuilderInitialization();
    }
    private void maybeForceBuilderInitialization() {
      if (com.google.protobuf.GeneratedMessageV3
              .alwaysUseFieldBuilders) {
      }
    }
    @java.lang.Override
    public Builder clear() {
      super.clear();
      catalogName_ = "";

      newCatalogName_ = "";

      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcRenameCatalogRequest_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest build() {
      io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest buildPartial() {
      io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest result = new io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest(this);
      result.catalogName_ = catalogName_;
      result.newCatalogName_ = newCatalogName_;
      onBuilt();
      return result;
    }

    @java.lang.Override
    public Builder clone() {
      return super.clone();
    }
    @java.lang.Override
    public Builder setField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return super.setField(field, value);
    }
    @java.lang.Override
    public Builder clearField(
        com.google.protobuf.Descriptors.FieldDescriptor field) {
      return super.clearField(field);
    }
    @java.lang.Override
    public Builder clearOneof(
        com.google.protobuf.Descriptors.OneofDescriptor oneof) {
      return super.clearOneof(oneof);
    }
    @java.lang.Override
    public Builder setRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        int index, java.lang.Object value) {
      return super.setRepeatedField(field, index, value);
    }
    @java.lang.Override
    public Builder addRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return super.addRepeatedField(field, value);
    }
    @java.lang.Override
    public Builder mergeFrom(com.google.protobuf.Message other) {
      if (other instanceof io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest other) {
      if (other == io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest.getDefaultInstance()) return this;
      if (!other.getCatalogName().isEmpty()) {
        catalogName_ = other.catalogName_;
        onChanged();
      }
      if (!other.getNewCatalogName().isEmpty()) {
        newCatalogName_ = other.newCatalogName_;
        onChanged();
      }
      this.mergeUnknownFields(other.unknownFields);
      onChanged();
      return this;
    }

    @java.lang.Override
    public final boolean isInitialized() {
      return true;
    }

    @java.lang.Override
    public Builder mergeFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private java.lang.Object catalogName_ = "";
    /**
     * <code>string catalogName = 1;</code>
     * @return The catalogName.
     */
    public java.lang.String getCatalogName() {
      java.lang.Object ref = catalogName_;
      if (!(ref instanceof java.lang.String)) {
        com.google.protobuf.ByteString bs =
            (com.google.protobuf.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        catalogName_ = s;
        return s;
      } else {
        return (java.lang.String) ref;
      }
    }
    /**
     * <code>string catalogName = 1;</code>
     * @return The bytes for catalogName.
     */
    public com.google.protobuf.ByteString
        getCatalogNameBytes() {
      java.lang.Object ref = catalogName_;
      if (ref instanceof String) {
        com.google.protobuf.ByteString b =
            com.google.protobuf.ByteString.copyFromUtf8(
                (java.lang.String) ref);
        catalogName_ = b;
        return b;
      } else {
        return (com.google.protobuf.ByteString) ref;
      }
    }
    /**
     * <code>string catalogName = 1;</code>
     * @param value The catalogName to set.
     * @return This builder for chaining.
     */
    public Builder setCatalogName(
        java.lang.String value) {
      if (value == null) {
    throw new NullPointerException();
  }

      catalogName_ = value;
      onChanged();
      return this;
    }
    /**
     * <code>string catalogName = 1;</code>
     * @return This builder for chaining.
     */
    public Builder clearCatalogName() {

      catalogName_ = getDefaultInstance().getCatalogName();
      onChanged();
      return this;
    }
    /**
     * <code>string catalogName = 1;</code>
     * @param value The bytes for catalogName to set.
     * @return This builder for chaining.
     */
    public Builder setCatalogNameBytes(
        com.google.protobuf.ByteString value) {
      if (value == null) {
    throw new NullPointerException();
  }
  checkByteStringIsUtf8(value);

      catalogName_ = value;
      onChanged();
      return this;
    }

    private java.lang.Object newCatalogName_ = "";
    /**
     * <code>string newCatalogName = 2;</code>
     * @return The newCatalogName.
     */
    public java.lang.String getNewCatalogName() {
      java.lang.Object ref = newCatalogName_;
      if (!(ref instanceof java.lang.String)) {
        com.google.protobuf.ByteString bs =
            (com.google.protobuf.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        newCatalogName_ = s;
        return s;
      } else {
        return (java.lang.String) ref;
      }
    }
    /**
     * <code>string newCatalogName = 2;</code>
     * @return The bytes for newCatalogName.
     */
    public com.google.protobuf.ByteString
        getNewCatalogNameBytes() {
      java.lang.Object ref = newCatalogName_;
      if (ref instanceof String) {
        com.google.protobuf.ByteString b =
            com.google.protobuf.ByteString.copyFromUtf8(
                (java.lang.String) ref);
        newCatalogName_ = b;
        return b;
      } else {
        return (com.google.protobuf.ByteString) ref;
      }
    }
    /**
     * <code>string newCatalogName = 2;</code>
     * @param value The newCatalogName to set.
     * @return This builder for chaining.
     */
    public Builder setNewCatalogName(
        java.lang.String value) {
      if (value == null) {
    throw new NullPointerException();
  }

      newCatalogName_ = value;
      onChanged();
      return this;
    }
    /**
     * <code>string newCatalogName = 2;</code>
     * @return This builder for chaining.
     */
    public Builder clearNewCatalogName() {

      newCatalogName_ = getDefaultInstance().getNewCatalogName();
      onChanged();
      return this;
    }
    /**
     * <code>string newCatalogName = 2;</code>
     * @param value The bytes for newCatalogName to set.
     * @return This builder for chaining.
     */
    public Builder setNewCatalogNameBytes(
        com.google.protobuf.ByteString value) {
      if (value == null) {
    throw new NullPointerException();
  }
  checkByteStringIsUtf8(value);

      newCatalogName_ = value;
      onChanged();
      return this;
    }
    @java.lang.Override
    public final Builder setUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.setUnknownFields(unknownFields);
    }

    @java.lang.Override
    public final Builder mergeUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.mergeUnknownFields(unknownFields);
    }


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest)
  private static final io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest();
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GrpcRenameCatalogRequest>
      PARSER = new com.google.protobuf.AbstractParser<GrpcRenameCatalogRequest>() {
    @java.lang.Override
    public GrpcRenameCatalogRequest parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GrpcRenameCatalogRequest(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GrpcRenameCatalogRequest> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GrpcRenameCatalogRequest> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcRenameCatalogRequest getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

