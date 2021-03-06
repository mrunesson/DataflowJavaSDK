/*
 * Copyright (C) 2015 The Google Cloud Dataflow Hadoop Library Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.contrib.hadoop;

import com.google.cloud.dataflow.sdk.testing.CoderProperties;

import org.apache.hadoop.io.IntWritable;
import org.junit.Test;

/**
 * Tests for WritableCoder.
 */
public class WritableCoderTest {

  @Test
  public void testIntWritableEncoding() throws Exception {
    IntWritable value = new IntWritable(42);
    WritableCoder<IntWritable> coder = WritableCoder.of(IntWritable.class);

    CoderProperties.coderDecodeEncodeEqual(coder, value);
  }
}
