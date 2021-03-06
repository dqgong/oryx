/*
 * Copyright (c) 2013, Cloudera, Inc. All Rights Reserved.
 *
 * Cloudera, Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the
 * License.
 */

package com.cloudera.oryx.als.computation.local;

import com.google.common.base.Preconditions;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.Callable;

import com.cloudera.oryx.als.common.StringLongMapping;
import com.cloudera.oryx.common.LangUtils;
import com.cloudera.oryx.common.collection.LongFloatMap;
import com.cloudera.oryx.common.collection.LongObjectMap;
import com.cloudera.oryx.common.collection.LongSet;
import com.cloudera.oryx.common.io.IOUtils;
import com.cloudera.oryx.common.iterator.FileLineIterable;
import com.cloudera.oryx.common.math.MatrixUtils;
import com.cloudera.oryx.common.io.DelimitedDataUtils;
import com.cloudera.oryx.common.settings.ConfigUtils;

final class ReadInputs implements Callable<Object> {

  private static final Logger log = LoggerFactory.getLogger(ReadInputs.class);

  private final File inputDir;
  private final boolean isInbound;
  private final LongObjectMap<LongSet> knownItemIDs;
  private final LongObjectMap<LongFloatMap> RbyRow;
  private final LongObjectMap<LongFloatMap> RbyColumn;
  private final StringLongMapping idMapping;
  private final float zeroThreshold;
  private final float decayFactor;

  ReadInputs(File inputDir,
             boolean isInbound,
             LongObjectMap<LongSet> knownItemIDs,
             LongObjectMap<LongFloatMap> rbyRow,
             LongObjectMap<LongFloatMap> rbyColumn,
             StringLongMapping idMapping) {
    this.inputDir = inputDir;
    this.isInbound = isInbound;
    this.knownItemIDs = knownItemIDs;
    RbyRow = rbyRow;
    RbyColumn = rbyColumn;
    this.idMapping = idMapping;
    Config config = ConfigUtils.getDefaultConfig();
    zeroThreshold = (float) config.getDouble("model.decay.zeroThreshold");
    decayFactor = (float) config.getDouble("model.decay.factor");
    Preconditions.checkArgument(zeroThreshold >= 0.0f,
                                "Zero threshold must be nonnegative: %s", zeroThreshold);
    Preconditions.checkArgument(decayFactor >= 0.0f && decayFactor <= 1.0f,
                                "Decay factor must be in [0,1]: %s", decayFactor);
  }

  @Override
  public Void call() throws IOException {
    // Always read inbound; only read past data if decay factor is positive.
    // If it's 0, all past data is ignored.
    if (isInbound || decayFactor > 0.0f) {

      readInput();

      if (zeroThreshold > 0.0f) {
        log.info("Pruning near-zero entries");
        removeSmall(RbyRow);
        removeSmall(RbyColumn);
      }

    }

    return null;
  }

  private void readInput() throws IOException {
    File[] inputFiles = inputDir.listFiles(IOUtils.NOT_HIDDEN);
    if (inputFiles == null || inputFiles.length == 0) {
      log.info("No input files in {}", inputDir);
      return;
    }
    Arrays.sort(inputFiles, ByLastModifiedComparator.INSTANCE);

    for (File inputFile : inputFiles) {
      log.info("Reading {}", inputFile);
      for (CharSequence line : new FileLineIterable(inputFile)) {

        String[] columns = DelimitedDataUtils.decode(line);

        String userIDString = columns[0];
        long userID = isInbound ? idMapping.add(userIDString) : Long.parseLong(userIDString);
        String itemIDString = columns[1];
        long itemID = isInbound ? idMapping.add(itemIDString) : Long.parseLong(itemIDString);
        float value;
        if (columns.length > 2) {
          String valueToken = columns[2];
          value = valueToken.isEmpty() ? Float.NaN : LangUtils.parseFloat(valueToken);
        } else {
          value = 1.0f;
        }

        if (!isInbound) {
          value *= decayFactor;
        }

        if (Float.isNaN(value)) {
          // Remove, not set
          MatrixUtils.remove(userID, itemID, RbyRow, RbyColumn);
        } else {
          MatrixUtils.addTo(userID, itemID, value, RbyRow, RbyColumn);
        }

        if (knownItemIDs != null) {
          LongSet itemIDs = knownItemIDs.get(userID);
          if (Float.isNaN(value)) {
            // Remove, not set
            if (itemIDs != null) {
              itemIDs.remove(itemID);
              if (itemIDs.isEmpty()) {
                knownItemIDs.remove(userID);
              }
            }
          } else {
            if (itemIDs == null) {
              itemIDs = new LongSet();
              knownItemIDs.put(userID, itemIDs);
            }
            itemIDs.add(itemID);
          }
        }

      }
    }

  }

  private void removeSmall(LongObjectMap<LongFloatMap> matrix) {
    for (LongObjectMap.MapEntry<LongFloatMap> entry : matrix.entrySet()) {
      for (Iterator<LongFloatMap.MapEntry> it = entry.getValue().entrySet().iterator(); it.hasNext();) {
        LongFloatMap.MapEntry entry2 = it.next();
        if (Math.abs(entry2.getValue()) < zeroThreshold) {
          it.remove();
        }
      }
    }
  }

}
