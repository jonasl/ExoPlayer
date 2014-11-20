/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.google.android.exoplayer.upstream;

import android.util.Log;

import com.google.android.exoplayer.C;
import com.google.android.exoplayer.util.Assertions;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.spec.AlgorithmParameterSpec;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A {@link DataSource} that decrypts the data read from an upstream source, encrypted with AES-128
 * with a 128-bit key and PKCS7 padding.
 *
 */
public class Aes128DataSource implements DataSource {

  private static final String TAG = "Aes128DataSource";

  private final DataSource upstream;
  private final byte[] secretKey;
  private final byte[] iv;

  private Cipher cipher;
  private CipherInputStream cipherInputStream;

  public Aes128DataSource(byte[] secretKey, byte[] iv, DataSource upstream) {
    this.upstream = upstream;
    this.secretKey = secretKey;
    this.iv = iv;
  }

  @Override
  public long open(DataSpec dataSpec) throws IOException {
    try {
      cipher = Cipher.getInstance("AES/CBC/PKCS7Padding");
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    } catch (NoSuchPaddingException e) {
      throw new RuntimeException(e);
    }

    Key cipherKey = new SecretKeySpec(secretKey, "AES");
    AlgorithmParameterSpec cipherIV = new IvParameterSpec(iv);

    try {
      cipher.init(Cipher.DECRYPT_MODE, cipherKey, cipherIV);
    } catch (InvalidKeyException e) {
      throw new RuntimeException(e);
    } catch (InvalidAlgorithmParameterException e) {
      throw new RuntimeException(e);
    }

    // TODO: We can't start reding from a specific position with our cipher mode.
    // Must start from 0, read bytes and throw away. This must be tested!
    int throwAway = 0;
    if (dataSpec.position != 0) {
      long newPosition = 0;
      long newLength = dataSpec.length == C.LENGTH_UNBOUNDED ? C.LENGTH_UNBOUNDED :
          dataSpec.length + dataSpec.position;
      throwAway = (int)dataSpec.position;
      dataSpec = new DataSpec(dataSpec.uri, newPosition, newLength, dataSpec.key);
      Log.w(TAG, String.format("position=%d, trying to read from start and throw away", throwAway));
    }

    cipherInputStream = new CipherInputStream(
        new DataSourceInputStream(upstream, dataSpec), cipher);

    if (throwAway > 0) {
      byte[] buffer = new byte[50 * 1024];
      int bytesRead = 0;
      while (bytesRead != -1 && throwAway > 0) {
        bytesRead = cipherInputStream.read(buffer, 0, Math.min(throwAway, buffer.length));
        if (bytesRead > 0) {
          throwAway -= bytesRead;
        }
      }
    }
    if (throwAway > 0) {
      Log.e(TAG, String.format("Throw away start bytes failed, starting %d bytes off", throwAway));
    }

    return C.LENGTH_UNBOUNDED;
  }

  @Override
  public void close() throws IOException {
    upstream.close();
  }

  @Override
  public int read(byte[] buffer, int offset, int readLength) throws IOException {
    Assertions.checkState(cipherInputStream != null);
    int bytesRead = cipherInputStream.read(buffer, offset, readLength);
    if (bytesRead < 0) {
      return -1;
    }
    return bytesRead;
  }

}
