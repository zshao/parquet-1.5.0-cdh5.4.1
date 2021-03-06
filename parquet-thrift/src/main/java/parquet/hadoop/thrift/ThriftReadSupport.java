/* 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package parquet.hadoop.thrift;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapred.JobConf;
import org.apache.thrift.TBase;
import org.apache.thrift.protocol.TProtocol;

import parquet.Log;
import parquet.Preconditions;
import parquet.hadoop.api.InitContext;
import parquet.hadoop.api.ReadSupport;
import parquet.io.ParquetDecodingException;
import parquet.io.api.RecordMaterializer;
import parquet.schema.MessageType;
import parquet.thrift.TBaseRecordConverter;
import parquet.thrift.ThriftMetaData;
import parquet.thrift.ThriftRecordConverter;
import parquet.thrift.ThriftSchemaConverter;
import parquet.thrift.projection.FieldProjectionFilter;
import parquet.thrift.projection.ThriftProjectionException;
import parquet.thrift.struct.ThriftType.StructType;

public class ThriftReadSupport<T> extends ReadSupport<T> {
  private static final Log LOG = Log.getLog(ThriftReadSupport.class);

  /**
   * configuration key for thrift read projection schema
   */
  public static final String THRIFT_COLUMN_FILTER_KEY = "parquet.thrift.column.filter";
  private static final String RECORD_CONVERTER_DEFAULT = TBaseRecordConverter.class.getName();
  public static final String THRIFT_READ_CLASS_KEY = "parquet.thrift.read.class";


  /**
   * A {@link ThriftRecordConverter} builds an object by working with {@link TProtocol}. The default
   * implementation creates standard Apache Thrift {@link TBase} objects; to support alternatives, such
   * as <a href="http://github.com/twitter/scrooge">Twiter's Scrooge</a>, a custom converter can be specified using this key
   * (for example, ScroogeRecordConverter from parquet-scrooge).
   */
  private static final String RECORD_CONVERTER_CLASS_KEY = "parquet.thrift.converter.class";

  protected Class<T> thriftClass;

  /**
   * A {@link ThriftRecordConverter} builds an object by working with {@link TProtocol}. The default
   * implementation creates standard Apache Thrift {@link TBase} objects; to support alternatives, such
   * as <a href="http://github.com/twitter/scrooge">Twiter's Scrooge</a>, a custom converter can be specified
   * (for example, ScroogeRecordConverter from parquet-scrooge).
   */
  public static void setRecordConverterClass(JobConf conf,
      Class<?> klass) {
    conf.set(RECORD_CONVERTER_CLASS_KEY, klass.getName());
  }

  /**
   * used from hadoop
   * the configuration must contain a "parquet.thrift.read.class" setting
   */
  public ThriftReadSupport() {
  }

  /**
   * @param thriftClass the thrift class used to deserialize the records
   */
  public ThriftReadSupport(Class<T> thriftClass) {
    this.thriftClass = thriftClass;
  }

  @Override
  public parquet.hadoop.api.ReadSupport.ReadContext init(InitContext context) {
    final Configuration configuration = context.getConfiguration();
    final MessageType fileMessageType = context.getFileSchema();
    MessageType requestedProjection = fileMessageType;
    String partialSchemaString = configuration.get(ReadSupport.PARQUET_READ_SCHEMA);
    String projectionFilterString = configuration.get(THRIFT_COLUMN_FILTER_KEY);

    if (partialSchemaString != null && projectionFilterString != null)
      throw new ThriftProjectionException("PARQUET_READ_SCHEMA and THRIFT_COLUMN_FILTER_KEY are both specified, should use only one.");

    //set requestedProjections only when it's specified
    if (partialSchemaString != null) {
      requestedProjection = getSchemaForRead(fileMessageType, partialSchemaString);
    } else if (projectionFilterString != null && !projectionFilterString.isEmpty()) {
      FieldProjectionFilter fieldProjectionFilter = new FieldProjectionFilter(projectionFilterString);
      try {
        initThriftClassFromMultipleFiles(context.getKeyValueMetadata(), configuration);
        requestedProjection =  getProjectedSchema(fieldProjectionFilter);
      } catch (ClassNotFoundException e) {
        throw new ThriftProjectionException("can not find thriftClass from configuration");
      }
    }

    MessageType schemaForRead = getSchemaForRead(fileMessageType, requestedProjection);
    return new ReadContext(schemaForRead);
  }

  protected MessageType getProjectedSchema(FieldProjectionFilter fieldProjectionFilter) {
    return new ThriftSchemaConverter(fieldProjectionFilter).convert((Class<TBase<?, ?>>)thriftClass);
  }

  @SuppressWarnings("unchecked")
  private void initThriftClassFromMultipleFiles(Map<String, Set<String>> fileMetadata, Configuration conf) throws ClassNotFoundException {
    if (thriftClass != null) {
      return;
    }
    String className = conf.get(THRIFT_READ_CLASS_KEY, null);
    if (className == null) {
      Set<String> names = ThriftMetaData.getThriftClassNames(fileMetadata);
      if (names == null || names.size() != 1) {
        throw new ParquetDecodingException("Could not read file as the Thrift class is not provided and could not be resolved from the file: " + names);
      }
      className = names.iterator().next();
    }
    thriftClass = (Class<T>)Class.forName(className);
  }

  @SuppressWarnings("unchecked")
  private void initThriftClass(ThriftMetaData metadata, Configuration conf) throws ClassNotFoundException {
    if (thriftClass != null) {
      return;
    }
    String className = conf.get(THRIFT_READ_CLASS_KEY, null);
    if (className == null) {
      if (metadata == null) {
        throw new ParquetDecodingException("Could not read file as the Thrift class is not provided and could not be resolved from the file");
      }
      thriftClass = (Class<T>)metadata.getThriftClass();
    } else {
      thriftClass = (Class<T>)Class.forName(className);
    }
  }

  @Override
  public RecordMaterializer<T> prepareForRead(Configuration configuration,
      Map<String, String> keyValueMetaData, MessageType fileSchema,
      parquet.hadoop.api.ReadSupport.ReadContext readContext) {
    ThriftMetaData thriftMetaData = ThriftMetaData.fromExtraMetaData(keyValueMetaData);
    try {
      initThriftClass(thriftMetaData, configuration);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException("Cannot find Thrift object class for metadata: " + thriftMetaData, e);
    }

    // if there was not metadata in the file, get it from requested class
    if (thriftMetaData == null) {
      thriftMetaData = ThriftMetaData.fromThriftClass(thriftClass);
    }

    String converterClassName = configuration.get(RECORD_CONVERTER_CLASS_KEY, RECORD_CONVERTER_DEFAULT);
    return getRecordConverterInstance(converterClassName, thriftClass,
        readContext.getRequestedSchema(), thriftMetaData.getDescriptor(),
        configuration);
  }

  @SuppressWarnings("unchecked")
  private static <T> ThriftRecordConverter<T> getRecordConverterInstance(
      String converterClassName, Class<T> thriftClass,
      MessageType requestedSchema, StructType descriptor, Configuration conf) {
    Class<ThriftRecordConverter<T>> converterClass;
    try {
      converterClass = (Class<ThriftRecordConverter<T>>) Class.forName(converterClassName);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException("Cannot find Thrift converter class: " + converterClassName, e);
    }

    try {
      // first try the new version that accepts a Configuration
      try {
        Constructor<ThriftRecordConverter<T>> constructor =
            converterClass.getConstructor(Class.class, MessageType.class, StructType.class, Configuration.class);
        return constructor.newInstance(thriftClass, requestedSchema, descriptor, conf);
      } catch (IllegalAccessException e) {
        // try the other constructor pattern
      } catch (NoSuchMethodException e) {
        // try to find the other constructor pattern
      }

      Constructor<ThriftRecordConverter<T>> constructor =
          converterClass.getConstructor(Class.class, MessageType.class, StructType.class);
      return constructor.newInstance(thriftClass, requestedSchema, descriptor);
    } catch (InstantiationException e) {
      throw new RuntimeException("Failed to construct Thrift converter class: " + converterClassName, e);
    } catch (InvocationTargetException e) {
      throw new RuntimeException("Failed to construct Thrift converter class: " + converterClassName, e);
    } catch (IllegalAccessException e) {
      throw new RuntimeException("Cannot access constructor for Thrift converter class: " + converterClassName, e);
    } catch (NoSuchMethodException e) {
      throw new RuntimeException("Cannot find constructor for Thrift converter class: " + converterClassName, e);
    }
  }

  public static void setProjectionPushdown(JobConf jobConf, String projectionString) {
    jobConf.set(THRIFT_COLUMN_FILTER_KEY, projectionString);
  }
}
