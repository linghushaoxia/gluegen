/**
 * Copyright 2014 JogAmp Community. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of
 *       conditions and the following disclaimer.
 *
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY JogAmp Community ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL JogAmp Community OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation are those of the
 * authors and should not be interpreted as representing official policies, either expressed
 * or implied, of JogAmp Community.
 */
package com.jogamp.common.nio;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.security.AccessController;
import java.security.PrivilegedAction;

import jogamp.common.Debug;

import com.jogamp.common.os.Platform;

/**
 * An {@link InputStream} implementation based on an underlying {@link MappedByteBuffer}
 * supporting {@link #markSupported() mark}.
 * <p>
 * Intended to be utilized with a {@link MappedByteBuffer memory-mapped} {@link FileChannel#map(MapMode, long, long) FileChannel}
 * beyond its size limitation of {@link Integer#MAX_VALUE}.<br>
 * </p>
 * @since 2.3.0
 */
public class MappedByteBufferInputStream extends InputStream {
    public static enum CacheMode {
        /**
         * Keep all previous lazily cached buffer slices alive, useful for hopping readers,
         * i.e. random access via {@link MappedByteBufferInputStream#position(long) position(p)}
         * or {@link MappedByteBufferInputStream#reset() reset()}.
         * <p>
         * Note that without flushing, the platform may fail memory mapping
         * due to virtual address space exhaustion.<br>
         * In such case an {@link OutOfMemoryError} may be thrown directly,
         * or encapsulated as the {@link IOException#getCause() the cause}
         * of a thrown {@link IOException}.
         * </p>
         */
        FLUSH_NONE,
        /**
         * Soft flush the previous lazily cached buffer slice when caching the next buffer slice,
         * useful for sequential forward readers, as well as for hopping readers like {@link #FLUSH_NONE}
         * in case of relatively short periods between hopping across slices.
         * <p>
         * Implementation clears the buffer slice reference
         * while preserving a {@link WeakReference} to allow its resurrection if not yet
         * {@link System#gc() garbage collected}.
         * </p>
         * <p>
         * This is the default.
         * </p>
         */
        FLUSH_PRE_SOFT,
        /**
         * Hard flush the previous lazily cached buffer slice when caching the next buffer slice,
         * useful for sequential forward readers.
         * <p>
         * Besides clearing the buffer slice reference,
         * implementation attempts to hard flush the mapped buffer
         * using a {@code sun.misc.Cleaner} by reflection.
         * In case such method does not exist nor works, implementation falls back to {@link #FLUSH_PRE_SOFT}.
         * </p>
         */
        FLUSH_PRE_HARD
    };

    /**
     * File resize interface allowing a file to change its size,
     * e.g. via {@link RandomAccessFile#setLength(long)}.
     */
    public static interface FileResizeOp {
        /**
         * @param newSize the new file size
         * @throws IOException if file size change is not supported or any other I/O error occurs
         */
        void setLength(final long newSize) throws IOException;
    }
    private static final FileResizeOp NoFileResize = new FileResizeOp() {
        @Override
        public void setLength(final long newSize) throws IOException {
            throw new IOException("file size change not supported");
        }
    };

    /**
     * Default slice shift, i.e. 1L << shift, denoting slice size in MiB:
     * <ul>
     *   <li>{@link Platform#is64Bit() 64bit machines} -> 30 = 1024 MiB</li>
     *   <li>{@link Platform#is32Bit() 32bit machines} -> 29 = 512 MiB</li>
     * </ul>
     * <p>
     * In case the default is too much of-used up address-space, one may choose other values:
     * <ul>
     *   <li>29 ->  512 MiB</li>
     *   <li>28 ->  256 MiB</li>
     *   <li>27 ->  128 MiB</li>
     *   <li>26 ->   64 MiB</li>
     * </ul>
     * </p>
     */
    public static final int DEFAULT_SLICE_SHIFT;

    static final boolean DEBUG;

    static {
        Platform.initSingleton();
        if( Platform.is32Bit() ) {
            DEFAULT_SLICE_SHIFT = 29;
        } else {
            DEFAULT_SLICE_SHIFT = 30;
        }

        DEBUG = Debug.debug("ByteBufferInputStream");
    }

    private final int sliceShift;
    private final FileChannel fc;
    private final FileChannel.MapMode mmode;
    private FileResizeOp fileResizeOp = NoFileResize;

    private int sliceCount;
    ByteBuffer[] slices;
    private WeakReference<ByteBuffer>[] slices2GC;
    private long totalSize;

    private int refCount;

    private Method mbbCleaner;
    private Method cClean;
    private boolean cleanerInit;
    private boolean hasCleaner;
    private CacheMode cmode;

    int currSlice;
    private long mark;

    public final void dbgDump(final String prefix, final PrintStream out) {
        long fcSz = 0, pos = 0, rem = 0;
        try {
            fcSz = fc.size();
        } catch (final IOException e) {
            e.printStackTrace();
        }
        if( 0 < refCount ) {
            try {
                pos = position();
                rem = totalSize - pos;
            } catch (final IOException e) {
                e.printStackTrace();
            }
        }
        final int sliceCount2 = null != slices ? slices.length : 0;
        out.println(prefix+" refCount "+refCount+", fcSize "+fcSz+", totalSize "+totalSize);
        out.println(prefix+" position "+pos+", remaining "+rem);
        out.println(prefix+" mmode "+mmode+", cmode "+cmode+", fileResizeOp "+fileResizeOp);
        out.println(prefix+" slice "+currSlice+" / "+sliceCount+" ("+sliceCount2+")");
        out.println(prefix+" sliceShift "+sliceShift+" -> "+(1L << sliceShift));
    }

    MappedByteBufferInputStream(final FileChannel fc, final FileChannel.MapMode mmode, final CacheMode cmode,
                                final int sliceShift, final long totalSize, final int currSlice) throws IOException {
        this.sliceShift = sliceShift;
        this.fc = fc;
        this.mmode = mmode;

        if( 0 > totalSize ) {
            throw new IllegalArgumentException("Negative size "+totalSize);
        }
        // trigger notifyLengthChange
        this.totalSize = -1;
        this.sliceCount = 0;
        notifyLengthChange(totalSize);

        this.refCount = 1;
        this.cleanerInit = false;
        this.hasCleaner = false;
        this.cmode = cmode;

        this.currSlice = currSlice;
        this.mark = -1;

        slice(currSlice).position(0);
    }

    /**
     * Creates a new instance using the given {@link FileChannel}.
     * <p>
     * The {@link MappedByteBuffer} slices will be mapped lazily at first usage.
     * </p>
     * @param fileChannel the file channel to be mapped lazily.
     * @param mmode the map mode, default is {@link FileChannel.MapMode#READ_ONLY}.
     * @param cmode the caching mode, default is {@link CacheMode#FLUSH_PRE_SOFT}.
     * @param sliceShift the pow2 slice size, default is {@link #DEFAULT_SLICE_SHIFT}.
     * @throws IOException
     */
    public MappedByteBufferInputStream(final FileChannel fileChannel,
                                       final FileChannel.MapMode mmode,
                                       final CacheMode cmode,
                                       final int sliceShift) throws IOException {
        this(fileChannel, mmode, cmode, sliceShift, fileChannel.size(), 0);
    }

    /**
     * Creates a new instance using the given {@link FileChannel},
     * given mapping-mode, given cache-mode and the {@link #DEFAULT_SLICE_SHIFT}.
     * <p>
     * The {@link MappedByteBuffer} slices will be mapped lazily at first usage.
     * </p>
     * @param fileChannel the file channel to be used.
     * @param mmode the map mode, default is {@link FileChannel.MapMode#READ_ONLY}.
     * @param cmode the caching mode, default is {@link CacheMode#FLUSH_PRE_SOFT}.
     * @throws IOException
     */
    public MappedByteBufferInputStream(final FileChannel fileChannel, final FileChannel.MapMode mmode, final CacheMode cmode) throws IOException {
        this(fileChannel, mmode, cmode, DEFAULT_SLICE_SHIFT);
    }

    /**
     * Creates a new instance using the given {@link FileChannel},
     * {@link FileChannel.MapMode#READ_ONLY read-only} mapping mode, {@link CacheMode#FLUSH_PRE_SOFT}
     * and the {@link #DEFAULT_SLICE_SHIFT}.
     * <p>
     * The {@link MappedByteBuffer} slices will be mapped {@link FileChannel.MapMode#READ_ONLY} lazily at first usage.
     * </p>
     * @param fileChannel the file channel to be used.
     * @throws IOException
     */
    public MappedByteBufferInputStream(final FileChannel fileChannel) throws IOException {
        this(fileChannel, FileChannel.MapMode.READ_ONLY, CacheMode.FLUSH_PRE_SOFT, DEFAULT_SLICE_SHIFT);
    }

    final synchronized void checkOpen() throws IOException {
        if( 0 == refCount ) {
            throw new IOException("stream closed");
        }
    }

    @Override
    public final synchronized void close() throws IOException {
        if( 0 < refCount ) {
            refCount--;
            if( 0 == refCount ) {
                for(int i=0; i<sliceCount; i++) {
                    cleanSlice(i);
                }
                if( mmode != FileChannel.MapMode.READ_ONLY ) {
                    fc.force(true);
                }
                fc.close();
                mark = -1;
                currSlice = -1;
                super.close();
            }
        }
    }

    /**
     * @param fileResizeOp the new {@link FileResizeOp}.
     * @throws IllegalStateException if attempting to set the {@link FileResizeOp} to a different value than before
     */
    public final synchronized void setFileResizeOp(final FileResizeOp fileResizeOp) throws IllegalStateException {
        if( NoFileResize != this.fileResizeOp && this.fileResizeOp != fileResizeOp ) {
            throw new IllegalStateException("FileResizeOp already set, this value differs");
        }
        this.fileResizeOp = null != fileResizeOp ? fileResizeOp : NoFileResize;
    }

    /**
     * Resize the underlying {@link FileChannel}'s size and adjusting this instance
     * via {@link #notifyLengthChange(long) accordingly}.
     * <p>
     * User must have a {@link FileResizeOp} {@link #setFileResizeOp(FileResizeOp) registered} before.
     * </p>
     * @param newTotalSize the new total size
     * @throws IOException if no {@link FileResizeOp} has been {@link #setFileResizeOp(FileResizeOp) registered}
     *                     or if a buffer slice operation failed
     */
    public final synchronized void setLength(final long newTotalSize) throws IOException {
        if( fc.size() != newTotalSize ) {
            fileResizeOp.setLength(newTotalSize);
        }
        notifyLengthChange(newTotalSize);
    }

    /**
     * Notify this instance that the underlying {@link FileChannel}'s size has been changed
     * and adjusting this instances buffer slices and states accordingly.
     * <p>
     * Should be called by user API when aware of such event.
     * </p>
     * @param newTotalSize the new total size
     * @throws IOException if a buffer slice operation failed
     */
    public final synchronized void notifyLengthChange(final long newTotalSize) throws IOException {
        /* if( DEBUG ) {
            System.err.println("notifyLengthChange.0: "+totalSize+" -> "+newTotalSize);
            dbgDump("notifyLengthChange.0:", System.err);
        } */
        if( totalSize == newTotalSize ) {
            // NOP
            return;
        } else if( 0 == newTotalSize ) {
            // ZERO - ensure one entry avoiding NULL checks
            if( null != slices ) {
                for(int i=0; i<sliceCount; i++) {
                    cleanSlice(i);
                }
            }
            @SuppressWarnings("unchecked")
            final WeakReference<ByteBuffer>[] newSlices2GC = new WeakReference[ 1 ];
            slices2GC = newSlices2GC;
            slices = new ByteBuffer[1];
            slices[0] = ByteBuffer.allocate(0);
            sliceCount = 0;
            totalSize = 0;
            mark = -1;
            currSlice = 0;
        } else {
            final long prePosition = position();

            final long sliceSize = 1L << sliceShift;
            final int newSliceCount = (int)( ( newTotalSize + ( sliceSize - 1 ) ) / sliceSize );
            @SuppressWarnings("unchecked")
            final WeakReference<ByteBuffer>[] newSlices2GC = new WeakReference[ newSliceCount ];
            final MappedByteBuffer[] newSlices = new MappedByteBuffer[ newSliceCount ];
            final int copySliceCount = Math.min(newSliceCount, sliceCount-1); // drop last (resize)
            if( 0 < copySliceCount ) {
                System.arraycopy(slices2GC, 0, newSlices2GC, 0, copySliceCount);
                System.arraycopy(slices,    0, newSlices,    0, copySliceCount);
                for(int i=copySliceCount; i<sliceCount; i++) { // clip shrunken slices + 1 (last), incl. slices2GC!
                    cleanSlice(i);
                }
            }
            slices2GC = newSlices2GC;
            slices = newSlices;
            sliceCount = newSliceCount;
            totalSize = newTotalSize;
            if( newTotalSize < mark ) {
                mark = -1;
            }
            positionImpl( Math.min(prePosition, newTotalSize) ); // -> clipped position (set currSlice and re-map/-pos buffer)
        }
        /* if( DEBUG ) {
            System.err.println("notifyLengthChange.X: "+slices[currSlice]);
            dbgDump("notifyLengthChange.X:", System.err);
        } */
    }

    /**
     *
     * @throws IOException if this stream has been {@link #close() closed}.
     */
    public final synchronized void flush() throws IOException {
        checkOpen();
        if( mmode != FileChannel.MapMode.READ_ONLY ) {
            fc.force(true);
        }
    }

    /**
     * Returns a new MappedByteBufferOutputStream instance sharing
     * all resources of this input stream, including all buffer slices.
     *
     * @throws IllegalStateException if attempting to set the {@link FileResizeOp} to a different value than before
     * @throws IOException if this instance was opened w/ {@link FileChannel.MapMode#READ_ONLY}
     *                     or if this stream has been {@link #close() closed}.
     */
    public final synchronized MappedByteBufferOutputStream getOutputStream(final FileResizeOp fileResizeOp)
            throws IllegalStateException, IOException
    {
        if( FileChannel.MapMode.READ_ONLY == mmode ) {
            throw new IOException("FileChannel map-mode is read-only");
        }
        checkOpen();
        setFileResizeOp(fileResizeOp);
        refCount++;
        this.fileResizeOp = null != fileResizeOp ? fileResizeOp : NoFileResize;
        return new MappedByteBufferOutputStream(this);
    }

    final synchronized ByteBuffer slice(final int i) throws IOException {
        if ( null != slices[i] ) {
            return slices[i];
        } else {
            if( CacheMode.FLUSH_PRE_SOFT == cmode ) {
                final WeakReference<ByteBuffer> ref = slices2GC[i];
                if( null != ref ) {
                    final ByteBuffer mbb = ref.get();
                    slices2GC[i] = null;
                    if( null != mbb ) {
                        slices[i] = mbb;
                        return mbb;
                    }
                }
            }
            final long pos = (long)i << sliceShift;
            slices[i] = fc.map(mmode, pos, Math.min(1L << sliceShift, totalSize - pos));
            return slices[i];
        }
    }
    final synchronized boolean nextSlice() throws IOException {
        if ( currSlice < sliceCount - 1 ) {
            if( CacheMode.FLUSH_NONE != cmode ) {
                flushSlice(currSlice);
            }
            currSlice++;
            slice( currSlice ).position( 0 );
            return true;
        } else {
            return false;
        }
    }

    private synchronized void flushSlice(final int i) throws IOException {
        final ByteBuffer s = slices[i];
        if ( null != s ) {
            slices[i] = null; // GC a slice is enough
            if( CacheMode.FLUSH_PRE_HARD == cmode ) {
                if( !cleanBuffer(s) ) {
                    cmode = CacheMode.FLUSH_PRE_SOFT;
                    slices2GC[i] = new WeakReference<ByteBuffer>(s);
                }
            } else {
                slices2GC[i] = new WeakReference<ByteBuffer>(s);
            }
        }
    }
    private synchronized void cleanSlice(final int i) {
        final ByteBuffer s = slices[i];
        if( null != s ) {
            slices[i] = null;
            cleanBuffer(s);
        }
        slices2GC[i] = null;
    }
    private synchronized boolean cleanBuffer(final ByteBuffer mbb) {
        if( !cleanerInit ) {
            initCleaner(mbb);
        }
        if ( !hasCleaner || !mbb.isDirect() ) {
            return false;
        }
        try {
            cClean.invoke(mbbCleaner.invoke(mbb));
            return true;
        } catch(final Throwable t) {
            hasCleaner = false;
            if( DEBUG ) {
                System.err.println("Caught "+t.getMessage());
                t.printStackTrace();
            }
            return false;
        }
    }
    private synchronized void initCleaner(final ByteBuffer bb) {
        final Method[] _mbbCleaner = { null };
        final Method[] _cClean = { null };
        AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    _mbbCleaner[0] = bb.getClass().getMethod("cleaner");
                    _mbbCleaner[0].setAccessible(true);
                    _cClean[0] = Class.forName("sun.misc.Cleaner").getMethod("clean");
                    _cClean[0].setAccessible(true);
                } catch(final Throwable t) {
                    if( DEBUG ) {
                        System.err.println("Caught "+t.getMessage());
                        t.printStackTrace();
                    }
                }
                return null;
            } } );
        mbbCleaner = _mbbCleaner[0];
        cClean = _cClean[0];
        final boolean res = null != mbbCleaner && null != cClean;
        if( DEBUG ) {
            System.err.println("initCleaner: Has cleaner: "+res+", mbbCleaner "+mbbCleaner+", cClean "+cClean);
        }
        hasCleaner = res;
        cleanerInit = true;
    }

    /**
     * Return the used {@link CacheMode}.
     * <p>
     * If a desired {@link CacheMode} is not available, it may fall back to an available one at runtime,
     * see {@link CacheMode#FLUSH_PRE_HARD}.<br>
     * This evaluation only happens if the {@link CacheMode} != {@link CacheMode#FLUSH_NONE}
     * and while attempting to flush an unused buffer slice.
     * </p>
     */
    public final synchronized CacheMode getCacheMode() { return cmode; }

    /**
     * Returns the total size in bytes of the {@link InputStream}
     * <pre>
     *   <code>0 <= {@link #position()} <= {@link #length()}</code>
     * </pre>
     */
    // @Override
    public final synchronized long length() {
        return totalSize;
    }

    /**
     * Returns the number of remaining available bytes of the {@link InputStream},
     * i.e. <code>{@link #length()} - {@link #position()}</code>.
     * <pre>
     *   <code>0 <= {@link #position()} <= {@link #length()}</code>
     * </pre>
     * <p>
     * In contrast to {@link InputStream}'s {@link #available()} method,
     * this method returns the proper return type {@code long}.
     * </p>
     * @throws IOException if a buffer slice operation failed.
     */
    public final synchronized long remaining() throws IOException {
        return 0 < refCount ? totalSize - position() : 0;
    }

    /**
     * <i>See {@link #remaining()} for an accurate variant.</i>
     * <p>
     * {@inheritDoc}
     * </p>
     * @throws IOException if a buffer slice operation failed.
     */
    @Override
    public final synchronized int available() throws IOException {
        final long available = remaining();
        return available <= Integer.MAX_VALUE ? (int)available : Integer.MAX_VALUE;
    }

    /**
     * Returns the absolute position of the {@link InputStream}.
     * <pre>
     *   <code>0 <= {@link #position()} <= {@link #length()}</code>
     * </pre>
     * @throws IOException if a buffer slice operation failed.
     */
    // @Override
    public final synchronized long position() throws IOException {
        if( 0 < refCount ) {
            return ( (long)currSlice << sliceShift ) + slice( currSlice ).position();
        } else {
            return 0;
        }
    }

    /**
     * Sets the absolute position of the {@link InputStream} to {@code newPosition}.
     * <pre>
     *   <code>0 <= {@link #position()} <= {@link #length()}</code>
     * </pre>
     * @param newPosition The new position, which must be non-negative and &le; {@link #length()}.
     * @return this instance
     * @throws IOException if a buffer slice operation failed or stream is {@link #close() closed}.
     */
    // @Override
    public final synchronized MappedByteBufferInputStream position( final long newPosition ) throws IOException {
        checkOpen();
        if ( totalSize < newPosition || 0 > newPosition ) {
            throw new IllegalArgumentException("new position "+newPosition+" not within [0.."+totalSize+"]");
        }
        final int preSlice = currSlice;
        positionImpl( newPosition );
        if( CacheMode.FLUSH_NONE != cmode && preSlice != currSlice) {
            flushSlice(preSlice);
        }
        return this;
    }
    private final synchronized void positionImpl( final long newPosition ) throws IOException {
        if ( totalSize == newPosition ) {
            // EOF, pos == maxPos + 1
            currSlice = Math.max(0, sliceCount - 1); // handle zero size
            final ByteBuffer s = slice( currSlice );
            s.position( s.capacity() );
        } else {
            currSlice = (int)( newPosition >>> sliceShift );
            slice( currSlice ).position( (int)( newPosition - ( (long)currSlice << sliceShift ) ) );
        }
    }

    @Override
    public final boolean markSupported() {
        return true;
    }

    /**
     * {@inheritDoc}
     * <p>
     * <i>Parameter {@code readLimit} is not used in this implementation,
     * since the whole file is memory mapped and no read limitation occurs.</i>
     * </p>
     */
    @Override
    public final synchronized void mark( final int readlimit ) {
        if( 0 < refCount ) {
            try {
                mark = position();
            } catch (final IOException e) {
                throw new RuntimeException(e); // FIXME: oops
            }
        }
    }

    /**
     * {@inheritDoc}
     * @throws IOException if this stream has not been marked,
     *                     a buffer slice operation failed or stream has been {@link #close() closed}.
     */
    @Override
    public final synchronized void reset() throws IOException {
        checkOpen();
        if ( mark == -1 ) {
            throw new IOException("mark not set");
        }
        position( mark );
    }

    /**
     * {@inheritDoc}
     * @throws IOException if a buffer slice operation failed or stream is {@link #close() closed}.
     */
    @Override
    public final synchronized long skip( final long n ) throws IOException {
        checkOpen();
        if( 0 > n ) {
            return 0;
        }
        final long pos = position();
        final long rem = totalSize - pos; // remaining
        final long s = Math.min( rem, n );
        position( pos + s );
        return s;
    }

    @Override
    public final synchronized int read() throws IOException {
        checkOpen();
        if ( ! slice( currSlice ).hasRemaining() ) {
            if ( !nextSlice() ) {
                return -1;
            }
        }
        return slices[ currSlice ].get() & 0xFF;
    }

    @Override
    public final synchronized int read( final byte[] b, final int off, final int len ) throws IOException {
        checkOpen();
        if (b == null) {
            throw new NullPointerException();
        } else if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException("offset "+off+", length "+len+", b.length "+b.length);
        }
        if ( 0 == len ) {
            return 0;
        }
        final long totalRem = remaining();
        if ( 0 == totalRem ) {
            return -1;
        }
        final int maxLen = (int)Math.min( totalRem, len );
        int read = 0;
        while( read < maxLen ) {
            int currRem = slice( currSlice ).remaining();
            if ( 0 == currRem ) {
                if ( !nextSlice() ) {
                    throw new InternalError("XX");
                }
                currRem = slice( currSlice ).remaining();
            }
            slices[ currSlice ].get( b, off + read, Math.min( maxLen - read, currRem ) );
            read += Math.min( maxLen - read, currRem );
        }
        return maxLen;
    }
}
