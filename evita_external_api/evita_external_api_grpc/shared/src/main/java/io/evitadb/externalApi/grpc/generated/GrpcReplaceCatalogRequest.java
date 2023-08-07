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
 * <pre>
 * Request to replace a catalog.
 * </pre>
 *
 * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest}
 */
public final class GrpcReplaceCatalogRequest extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest)
    GrpcReplaceCatalogRequestOrBuilder {
private static final long serialVersionUID = 0L;
  // Use GrpcReplaceCatalogRequest.newBuilder() to construct.
  private GrpcReplaceCatalogRequest(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private GrpcReplaceCatalogRequest() {
    catalogNameToBeReplacedWith_ = "";
    catalogNameToBeReplaced_ = "";
  }

  @java.lang.Override
  @SuppressWarnings({"unused"})
  protected java.lang.Object newInstance(
      UnusedPrivateParameter unused) {
    return new GrpcReplaceCatalogRequest();
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private GrpcReplaceCatalogRequest(
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

            catalogNameToBeReplacedWith_ = s;
            break;
          }
          case 18: {
            java.lang.String s = input.readStringRequireUtf8();

            catalogNameToBeReplaced_ = s;
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
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcReplaceCatalogRequest_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.evitadb.externalApi.grpc.generated.GrpcEvitaAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcReplaceCatalogRequest_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest.class, io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest.Builder.class);
  }

  public static final int CATALOGNAMETOBEREPLACEDWITH_FIELD_NUMBER = 1;
  private volatile java.lang.Object catalogNameToBeReplacedWith_;
  /**
   * <pre>
   * Name of the catalog that will become the successor of the original catalog (old name)
   * </pre>
   *
   * <code>string catalogNameToBeReplacedWith = 1;</code>
   * @return The catalogNameToBeReplacedWith.
   */
  @java.lang.Override
  public java.lang.String getCatalogNameToBeReplacedWith() {
    java.lang.Object ref = catalogNameToBeReplacedWith_;
    if (ref instanceof java.lang.String) {
      return (java.lang.String) ref;
    } else {
      com.google.protobuf.ByteString bs = 
          (com.google.protobuf.ByteString) ref;
      java.lang.String s = bs.toStringUtf8();
      catalogNameToBeReplacedWith_ = s;
      return s;
    }
  }
  /**
   * <pre>
   * Name of the catalog that will become the successor of the original catalog (old name)
   * </pre>
   *
   * <code>string catalogNameToBeReplacedWith = 1;</code>
   * @return The bytes for catalogNameToBeReplacedWith.
   */
  @java.lang.Override
  public com.google.protobuf.ByteString
      getCatalogNameToBeReplacedWithBytes() {
    java.lang.Object ref = catalogNameToBeReplacedWith_;
    if (ref instanceof java.lang.String) {
      com.google.protobuf.ByteString b = 
          com.google.protobuf.ByteString.copyFromUtf8(
              (java.lang.String) ref);
      catalogNameToBeReplacedWith_ = b;
      return b;
    } else {
      return (com.google.protobuf.ByteString) ref;
    }
  }

  public static final int CATALOGNAMETOBEREPLACED_FIELD_NUMBER = 2;
  private volatile java.lang.Object catalogNameToBeReplaced_;
  /**
   * <pre>
   * Name of the catalog that will be replaced and dropped (new name)
   * </pre>
   *
   * <code>string catalogNameToBeReplaced = 2;</code>
   * @return The catalogNameToBeReplaced.
   */
  @java.lang.Override
  public java.lang.String getCatalogNameToBeReplaced() {
    java.lang.Object ref = catalogNameToBeReplaced_;
    if (ref instanceof java.lang.String) {
      return (java.lang.String) ref;
    } else {
      com.google.protobuf.ByteString bs = 
          (com.google.protobuf.ByteString) ref;
      java.lang.String s = bs.toStringUtf8();
      catalogNameToBeReplaced_ = s;
      return s;
    }
  }
  /**
   * <pre>
   * Name of the catalog that will be replaced and dropped (new name)
   * </pre>
   *
   * <code>string catalogNameToBeReplaced = 2;</code>
   * @return The bytes for catalogNameToBeReplaced.
   */
  @java.lang.Override
  public com.google.protobuf.ByteString
      getCatalogNameToBeReplacedBytes() {
    java.lang.Object ref = catalogNameToBeReplaced_;
    if (ref instanceof java.lang.String) {
      com.google.protobuf.ByteString b = 
          com.google.protobuf.ByteString.copyFromUtf8(
              (java.lang.String) ref);
      catalogNameToBeReplaced_ = b;
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
    if (!com.google.protobuf.GeneratedMessageV3.isStringEmpty(catalogNameToBeReplacedWith_)) {
      com.google.protobuf.GeneratedMessageV3.writeString(output, 1, catalogNameToBeReplacedWith_);
    }
    if (!com.google.protobuf.GeneratedMessageV3.isStringEmpty(catalogNameToBeReplaced_)) {
      com.google.protobuf.GeneratedMessageV3.writeString(output, 2, catalogNameToBeReplaced_);
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (!com.google.protobuf.GeneratedMessageV3.isStringEmpty(catalogNameToBeReplacedWith_)) {
      size += com.google.protobuf.GeneratedMessageV3.computeStringSize(1, catalogNameToBeReplacedWith_);
    }
    if (!com.google.protobuf.GeneratedMessageV3.isStringEmpty(catalogNameToBeReplaced_)) {
      size += com.google.protobuf.GeneratedMessageV3.computeStringSize(2, catalogNameToBeReplaced_);
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
    if (!(obj instanceof io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest)) {
      return super.equals(obj);
    }
    io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest other = (io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest) obj;

    if (!getCatalogNameToBeReplacedWith()
        .equals(other.getCatalogNameToBeReplacedWith())) return false;
    if (!getCatalogNameToBeReplaced()
        .equals(other.getCatalogNameToBeReplaced())) return false;
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
    hash = (37 * hash) + CATALOGNAMETOBEREPLACEDWITH_FIELD_NUMBER;
    hash = (53 * hash) + getCatalogNameToBeReplacedWith().hashCode();
    hash = (37 * hash) + CATALOGNAMETOBEREPLACED_FIELD_NUMBER;
    hash = (53 * hash) + getCatalogNameToBeReplaced().hashCode();
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest parseFrom(
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
  public static Builder newBuilder(io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest prototype) {
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
   * <pre>
   * Request to replace a catalog.
   * </pre>
   *
   * Protobuf type {@code io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest)
      io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequestOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcReplaceCatalogRequest_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcReplaceCatalogRequest_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest.class, io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest.Builder.class);
    }

    // Construct using io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest.newBuilder()
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
      catalogNameToBeReplacedWith_ = "";

      catalogNameToBeReplaced_ = "";

      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcEvitaAPI.internal_static_io_evitadb_externalApi_grpc_generated_GrpcReplaceCatalogRequest_descriptor;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest getDefaultInstanceForType() {
      return io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest.getDefaultInstance();
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest build() {
      io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest buildPartial() {
      io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest result = new io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest(this);
      result.catalogNameToBeReplacedWith_ = catalogNameToBeReplacedWith_;
      result.catalogNameToBeReplaced_ = catalogNameToBeReplaced_;
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
      if (other instanceof io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest) {
        return mergeFrom((io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest other) {
      if (other == io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest.getDefaultInstance()) return this;
      if (!other.getCatalogNameToBeReplacedWith().isEmpty()) {
        catalogNameToBeReplacedWith_ = other.catalogNameToBeReplacedWith_;
        onChanged();
      }
      if (!other.getCatalogNameToBeReplaced().isEmpty()) {
        catalogNameToBeReplaced_ = other.catalogNameToBeReplaced_;
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
      io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private java.lang.Object catalogNameToBeReplacedWith_ = "";
    /**
     * <pre>
     * Name of the catalog that will become the successor of the original catalog (old name)
     * </pre>
     *
     * <code>string catalogNameToBeReplacedWith = 1;</code>
     * @return The catalogNameToBeReplacedWith.
     */
    public java.lang.String getCatalogNameToBeReplacedWith() {
      java.lang.Object ref = catalogNameToBeReplacedWith_;
      if (!(ref instanceof java.lang.String)) {
        com.google.protobuf.ByteString bs =
            (com.google.protobuf.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        catalogNameToBeReplacedWith_ = s;
        return s;
      } else {
        return (java.lang.String) ref;
      }
    }
    /**
     * <pre>
     * Name of the catalog that will become the successor of the original catalog (old name)
     * </pre>
     *
     * <code>string catalogNameToBeReplacedWith = 1;</code>
     * @return The bytes for catalogNameToBeReplacedWith.
     */
    public com.google.protobuf.ByteString
        getCatalogNameToBeReplacedWithBytes() {
      java.lang.Object ref = catalogNameToBeReplacedWith_;
      if (ref instanceof String) {
        com.google.protobuf.ByteString b = 
            com.google.protobuf.ByteString.copyFromUtf8(
                (java.lang.String) ref);
        catalogNameToBeReplacedWith_ = b;
        return b;
      } else {
        return (com.google.protobuf.ByteString) ref;
      }
    }
    /**
     * <pre>
     * Name of the catalog that will become the successor of the original catalog (old name)
     * </pre>
     *
     * <code>string catalogNameToBeReplacedWith = 1;</code>
     * @param value The catalogNameToBeReplacedWith to set.
     * @return This builder for chaining.
     */
    public Builder setCatalogNameToBeReplacedWith(
        java.lang.String value) {
      if (value == null) {
    throw new NullPointerException();
  }
  
      catalogNameToBeReplacedWith_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * Name of the catalog that will become the successor of the original catalog (old name)
     * </pre>
     *
     * <code>string catalogNameToBeReplacedWith = 1;</code>
     * @return This builder for chaining.
     */
    public Builder clearCatalogNameToBeReplacedWith() {
      
      catalogNameToBeReplacedWith_ = getDefaultInstance().getCatalogNameToBeReplacedWith();
      onChanged();
      return this;
    }
    /**
     * <pre>
     * Name of the catalog that will become the successor of the original catalog (old name)
     * </pre>
     *
     * <code>string catalogNameToBeReplacedWith = 1;</code>
     * @param value The bytes for catalogNameToBeReplacedWith to set.
     * @return This builder for chaining.
     */
    public Builder setCatalogNameToBeReplacedWithBytes(
        com.google.protobuf.ByteString value) {
      if (value == null) {
    throw new NullPointerException();
  }
  checkByteStringIsUtf8(value);
      
      catalogNameToBeReplacedWith_ = value;
      onChanged();
      return this;
    }

    private java.lang.Object catalogNameToBeReplaced_ = "";
    /**
     * <pre>
     * Name of the catalog that will be replaced and dropped (new name)
     * </pre>
     *
     * <code>string catalogNameToBeReplaced = 2;</code>
     * @return The catalogNameToBeReplaced.
     */
    public java.lang.String getCatalogNameToBeReplaced() {
      java.lang.Object ref = catalogNameToBeReplaced_;
      if (!(ref instanceof java.lang.String)) {
        com.google.protobuf.ByteString bs =
            (com.google.protobuf.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        catalogNameToBeReplaced_ = s;
        return s;
      } else {
        return (java.lang.String) ref;
      }
    }
    /**
     * <pre>
     * Name of the catalog that will be replaced and dropped (new name)
     * </pre>
     *
     * <code>string catalogNameToBeReplaced = 2;</code>
     * @return The bytes for catalogNameToBeReplaced.
     */
    public com.google.protobuf.ByteString
        getCatalogNameToBeReplacedBytes() {
      java.lang.Object ref = catalogNameToBeReplaced_;
      if (ref instanceof String) {
        com.google.protobuf.ByteString b = 
            com.google.protobuf.ByteString.copyFromUtf8(
                (java.lang.String) ref);
        catalogNameToBeReplaced_ = b;
        return b;
      } else {
        return (com.google.protobuf.ByteString) ref;
      }
    }
    /**
     * <pre>
     * Name of the catalog that will be replaced and dropped (new name)
     * </pre>
     *
     * <code>string catalogNameToBeReplaced = 2;</code>
     * @param value The catalogNameToBeReplaced to set.
     * @return This builder for chaining.
     */
    public Builder setCatalogNameToBeReplaced(
        java.lang.String value) {
      if (value == null) {
    throw new NullPointerException();
  }
  
      catalogNameToBeReplaced_ = value;
      onChanged();
      return this;
    }
    /**
     * <pre>
     * Name of the catalog that will be replaced and dropped (new name)
     * </pre>
     *
     * <code>string catalogNameToBeReplaced = 2;</code>
     * @return This builder for chaining.
     */
    public Builder clearCatalogNameToBeReplaced() {
      
      catalogNameToBeReplaced_ = getDefaultInstance().getCatalogNameToBeReplaced();
      onChanged();
      return this;
    }
    /**
     * <pre>
     * Name of the catalog that will be replaced and dropped (new name)
     * </pre>
     *
     * <code>string catalogNameToBeReplaced = 2;</code>
     * @param value The bytes for catalogNameToBeReplaced to set.
     * @return This builder for chaining.
     */
    public Builder setCatalogNameToBeReplacedBytes(
        com.google.protobuf.ByteString value) {
      if (value == null) {
    throw new NullPointerException();
  }
  checkByteStringIsUtf8(value);
      
      catalogNameToBeReplaced_ = value;
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


    // @@protoc_insertion_point(builder_scope:io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest)
  }

  // @@protoc_insertion_point(class_scope:io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest)
  private static final io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest();
  }

  public static io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<GrpcReplaceCatalogRequest>
      PARSER = new com.google.protobuf.AbstractParser<GrpcReplaceCatalogRequest>() {
    @java.lang.Override
    public GrpcReplaceCatalogRequest parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new GrpcReplaceCatalogRequest(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<GrpcReplaceCatalogRequest> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<GrpcReplaceCatalogRequest> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public io.evitadb.externalApi.grpc.generated.GrpcReplaceCatalogRequest getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

