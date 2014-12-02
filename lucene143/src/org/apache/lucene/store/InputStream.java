package org.apache.lucene.store;

/**
 * Copyright 2004 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 基本理清，待对应实现类
 */

import java.io.IOException;

/** Abstract base class for input from a file in a {@link Directory}.  
 * 在Directory类中使用，读取文件流的基类
 * A random-access input stream.  Used for all Lucene index input operations.
 * 随机访问流，用于所有lucene索引的输入操作
 * @see Directory
 * @see OutputStream
 */
public abstract class InputStream implements Cloneable {
  static final int BUFFER_SIZE = OutputStream.BUFFER_SIZE;

  private byte[] buffer;
  private char[] chars;

  //文件缓冲位置开始
  private long bufferStart = 0;			  // position in file of buffer
  //文件缓冲长度
  private int bufferLength = 0;			  // end of valid bytes
  //下一次读取的位置
  //缓冲的当前读取位置
  //循环读取时设置递增
  private int bufferPosition = 0;		  // next byte to read
  //???实现类设置
  protected long length;			  // set by subclasses

  /** Reads and returns a single byte.
   * 读取并返回一位
   * 每次读取移动bufferPosition，达到bufferLength时，执行refill
   * @see OutputStream#writeByte(byte)
   */
  public final byte readByte() throws IOException {
    if (bufferPosition >= bufferLength)
      refill();
    return buffer[bufferPosition++];
  }

  /** Reads a specified number of bytes into an array at the specified offset.
   * 读一个指定位数、偏移，写入一个数组
   * @param b the array to read bytes into
   * @param offset the offset in the array to start storing bytes
   * @param len the number of bytes to read
   * @see OutputStream#writeBytes(byte[],int)
   */
  public final void readBytes(byte[] b, int offset, int len)
       throws IOException {
    if (len < BUFFER_SIZE) {
      for (int i = 0; i < len; i++)		  // read byte-by-byte
	b[i + offset] = (byte)readByte();
    } else {					  // read all-at-once
    	//当缓冲长度小于目标数组长度，重置开始
    	//一次读入并重置位置、长度
      long start = getFilePointer();
      seekInternal(start);
      
      //自文件读取内容直接填充目标数组-绕过缓冲
      readInternal(b, offset, len);

      bufferStart = start + len;		  // adjust stream variables
      bufferPosition = 0;
      bufferLength = 0;				  // trigger refill() on read
    }
  }

  /** Reads four bytes and returns an int.
   * 读取4位，返回int
   * @see OutputStream#writeInt(int)
   */
  public final int readInt() throws IOException {
    return ((readByte() & 0xFF) << 24) | ((readByte() & 0xFF) << 16)
         | ((readByte() & 0xFF) <<  8) |  (readByte() & 0xFF);
  }

  /** Reads an int stored in variable-length format.  
   * 读取被存储在可变长度格式中的int
   * Reads between one and five bytes.  Smaller values take fewer bytes.  Negative numbers are not
   * supported.
   * 读取1-5位，？？？更小的数取得数位，不支持负数
   * @see OutputStream#writeVInt(int)
   */
  public final int readVInt() throws IOException {
    byte b = readByte();
    int i = b & 0x7F;
    for (int shift = 7; (b & 0x80) != 0; shift += 7) {
      b = readByte();
      i |= (b & 0x7F) << shift;
    }
    return i;
  }

  /** Reads eight bytes and returns a long.
   * 读取8位并返回long
   * @see OutputStream#writeLong(long)
   */
  public final long readLong() throws IOException {
    return (((long)readInt()) << 32) | (readInt() & 0xFFFFFFFFL);
  }

  /** Reads a long stored in variable-length format.  Reads between one and
   * nine bytes.  Smaller values take fewer bytes.  Negative numbers are not
   * supported. */
  public final long readVLong() throws IOException {
    byte b = readByte();
    long i = b & 0x7F;
    for (int shift = 7; (b & 0x80) != 0; shift += 7) {
      b = readByte();
      i |= (b & 0x7FL) << shift;
    }
    return i;
  }

  /** Reads a string.
   * @see OutputStream#writeString(String)
   */
  public final String readString() throws IOException {
    int length = readVInt();
    if (chars == null || length > chars.length)
      chars = new char[length];
    readChars(chars, 0, length);
    return new String(chars, 0, length);
  }

  /** Reads UTF-8 encoded characters into an array.
   * @param buffer the array to read characters into
   * @param start the offset in the array to start storing characters
   * @param length the number of characters to read
   * @see OutputStream#writeChars(String,int,int)
   */
  public final void readChars(char[] buffer, int start, int length)
       throws IOException {
    final int end = start + length;
    for (int i = start; i < end; i++) {
      byte b = readByte();
      if ((b & 0x80) == 0)
	buffer[i] = (char)(b & 0x7F);
      else if ((b & 0xE0) != 0xE0) {
	buffer[i] = (char)(((b & 0x1F) << 6)
		 | (readByte() & 0x3F));
      } else
	buffer[i] = (char)(((b & 0x0F) << 12)
		| ((readByte() & 0x3F) << 6)
	        |  (readByte() & 0x3F));
    }
  }

  //重新计算流读取位置并填充缓冲数组
  private void refill() throws IOException {
	//重新计算读取位置
    long start = bufferStart + bufferPosition;
    long end = start + BUFFER_SIZE;
    if (end > length)				  // don't read past EOF
      end = length;
    bufferLength = (int)(end - start);
    if (bufferLength == 0)
      throw new IOException("read past EOF");
    //初始化buffer
    if (buffer == null)
      buffer = new byte[BUFFER_SIZE];		  // allocate buffer lazily
    readInternal(buffer, 0, bufferLength);

    bufferStart = start;
    bufferPosition = 0;
  }

  /** Expert: implements buffer refill.  Reads bytes from the current position
   * in the input.
   * 
   * 自文件填充缓冲
   * 
   * 实现buffer的再填充
   * 将文件从当前位置读入位数组
   * @param b the array to read bytes into
   * @param offset the offset in the array to start storing bytes
   * @param length the number of bytes to read
   */
  protected abstract void readInternal(byte[] b, int offset, int length)
       throws IOException;

  /** Closes the stream to futher operations. 
   * 关闭流以便进一步操作
   * */
  public abstract void close() throws IOException;

  /** Returns the current position in this file, where the next read will
   * occur.
   * 计算当前已经读入缓冲的文件位置
   * 以便下次读文件时候返回座位起点
   * @see #seek(long)
   */
  public final long getFilePointer() {
    return bufferStart + bufferPosition;
  }

  /** Sets current position in this file, where the next read will occur.
   * 续读时，设置文件当前位置？
   * 计算参数相对开始的偏移量
   * 自位置pos重新读取文件内容
   * @see #getFilePointer()
   */
  public final void seek(long pos) throws IOException {
    if (pos >= bufferStart && pos < (bufferStart + bufferLength))
      bufferPosition = (int)(pos - bufferStart);  // seek within buffer
    else {
      bufferStart = pos;
      bufferPosition = 0;
      bufferLength = 0;				  // trigger refill() on read()
      seekInternal(pos);
    }
  }

  /** Expert: implements seek.  Sets current position in this file, where the
   * next {@link #readInternal(byte[],int,int)} will occur.
   * 实现seek，下次readInternal时，设置文件位置
   * @see #readInternal(byte[],int,int)
   */
  protected abstract void seekInternal(long pos) throws IOException;

  /** The number of bytes in the file.
   * 文件的位长度
   *  */
  public final long length() {
    return length;
  }

  /** Returns a clone of this stream.
   * 克隆IS
   * <p>Clones of a stream access the same data, and are positioned at the same
   * point as the stream they were cloned from.
   *
   * <p>Expert: Subclasses must ensure that clones may be positioned at
   * different points in the input from each other and from the stream they
   * were cloned from.
   */
  public Object clone() {
    InputStream clone = null;
    try {
      clone = (InputStream)super.clone();
    } catch (CloneNotSupportedException e) {}

    if (buffer != null) {
      clone.buffer = new byte[BUFFER_SIZE];
      System.arraycopy(buffer, 0, clone.buffer, 0, bufferLength);
    }

    clone.chars = null;

    return clone;
  }

}
