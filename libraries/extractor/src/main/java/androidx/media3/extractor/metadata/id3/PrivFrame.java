/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.metadata.id3;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import java.util.Arrays;
import java.util.Objects;

/** PRIV (Private) ID3 frame. */
@UnstableApi
public final class PrivFrame extends Id3Frame {

  public static final String ID = "PRIV";

  public final String owner;
  public final byte[] privateData;

  public PrivFrame(String owner, byte[] privateData) {
    super(ID);
    this.owner = owner;
    this.privateData = privateData;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    PrivFrame other = (PrivFrame) obj;
    return Objects.equals(owner, other.owner) && Arrays.equals(privateData, other.privateData);
  }

  @Override
  public int hashCode() {
    int result = 17;
    result = 31 * result + (owner != null ? owner.hashCode() : 0);
    result = 31 * result + Arrays.hashCode(privateData);
    return result;
  }

  @Override
  public String toString() {
    return id + ": owner=" + owner;
  }
}
