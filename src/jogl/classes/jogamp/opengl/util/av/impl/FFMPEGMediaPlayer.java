/**
 * Copyright 2012 JogAmp Community. All rights reserved.
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

package jogamp.opengl.util.av.impl;

import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.security.AccessController;
import java.security.PrivilegedAction;

import javax.media.opengl.GL;
import javax.media.opengl.GL2ES2;
import javax.media.opengl.GLException;

import com.jogamp.common.util.VersionNumber;
import com.jogamp.gluegen.runtime.ProcAddressTable;
import com.jogamp.opengl.util.GLPixelStorageModes;
import com.jogamp.opengl.util.av.AudioSink;
import com.jogamp.opengl.util.av.AudioSink.AudioDataFormat;
import com.jogamp.opengl.util.av.AudioSink.AudioDataType;
import com.jogamp.opengl.util.av.AudioSinkFactory;
import com.jogamp.opengl.util.av.GLMediaPlayer;
import com.jogamp.opengl.util.texture.Texture;

import jogamp.opengl.GLContextImpl;
import jogamp.opengl.util.av.GLMediaPlayerImpl;

/***
 * Implementation utilizes <a href="http://libav.org/">Libav</a>
 * or  <a href="http://ffmpeg.org/">FFmpeg</a> which is ubiquitous
 * available and usually pre-installed on Unix platforms. Due to legal 
 * reasons we cannot deploy binaries of it, which contains patented codecs.
 * Besides the default BSD/Linux/.. repositories and installations,
 * precompiled binaries can be found at the listed location below. 
 * <p>
 * Implements YUV420P to RGB fragment shader conversion 
 * and the usual packed RGB formats.
 * The decoded video frame is written directly into an OpenGL texture 
 * on the GPU in it's native format. A custom fragment shader converts 
 * the native pixelformat to a usable RGB format if required. 
 * Hence only 1 copy is required before bloating the picture 
 * from YUV to RGB, for example.
 * </p> 
 * <p>
 * Utilizes a slim dynamic and native binding to the Lib_av 
 * libraries:
 * <ul>
 *   <li>libavutil</li>
 *   <li>libavformat</li>
 *   <li>libavcodec</li>
 * </ul> 
 * </p>
 * <p>
 * http://libav.org/
 * </p>
 * <p> 
 * Check tag 'FIXME: Add more planar formats !' 
 * here and in the corresponding native code
 * <code>jogl/src/jogl/native/ffmpeg/jogamp_opengl_util_av_impl_FFMPEGMediaPlayer.c</code>
 * </p>
 * <p>
 * TODO:
 * <ul>
 *   <li>Audio Output</li>
 *   <li>Off thread <i>next frame</i> processing using multiple target textures</li>
 *   <li>better pts sync handling</li>
 *   <li>fix seek</li>   
 * </ul> 
 * </p>
 * Pre-compiled Libav / FFmpeg packages:
 * <ul>
 *   <li>Windows: http://ffmpeg.zeranoe.com/builds/</li>
 *   <li>MacOSX: http://www.ffmpegx.com/</li>
 *   <li>OpenIndiana/Solaris:<pre>
 *       pkg set-publisher -p http://pkg.openindiana.org/sfe-encumbered.
 *       pkt install pkg:/video/ffmpeg
 *       </pre></li>
 * </ul> 
 */
public class FFMPEGMediaPlayer extends GLMediaPlayerImpl {

    // Count of zeroed buffers to return before switching to real sample provider
    private static final int TEMP_BUFFER_COUNT = 20;

    // Instance data
    public static final VersionNumber avUtilVersion;
    public static final VersionNumber avFormatVersion;
    public static final VersionNumber avCodecVersion;    
    static final boolean available;
    
    static {
        final boolean libAVGood = FFMPEGDynamicLibraryBundleInfo.initSingleton();
        if( FFMPEGDynamicLibraryBundleInfo.libsLoaded() ) {
            avUtilVersion = getAVVersion(getAvUtilVersion0());
            avFormatVersion = getAVVersion(getAvFormatVersion0());
            avCodecVersion = getAVVersion(getAvCodecVersion0());        
            System.err.println("LIB_AV Util  : "+avUtilVersion);
            System.err.println("LIB_AV Format: "+avFormatVersion);
            System.err.println("LIB_AV Codec : "+avCodecVersion);
        } else {
            avUtilVersion = null;
            avFormatVersion = null;
            avCodecVersion = null;
        }
        available = libAVGood ? initIDs0() : false;            
    }
    
    public static final boolean isAvailable() { return available; }

    private static VersionNumber getAVVersion(int vers) {
        return new VersionNumber( ( vers >> 16 ) & 0xFF,
                                  ( vers >>  8 ) & 0xFF,
                                  ( vers >>  0 ) & 0xFF );
    }

    //
    // General
    //
    
    protected long moviePtr = 0;    
    
    //
    // Video
    //
    
    protected PixelFormat vPixelFmt = null;
    protected int vPlanes = 0;
    protected int vBitsPerPixel = 0;
    protected int vBytesPerPixelPerPlane = 0;    
    protected int[] vLinesize = { 0, 0, 0 }; // per plane
    protected int[] vTexWidth = { 0, 0, 0 }; // per plane
    protected int texWidth, texHeight; // overall (stuffing planes in one texture)
    protected ByteBuffer texCopy;
    protected String singleTexComp = "r";
    protected GLPixelStorageModes psm;

    //
    // Audio
    //
    
    protected static final int AFRAMES_PER_VFRAME = 8;
    protected int aFrameCount = 0;
    protected SampleFormat aSampleFmt = null;
    protected AudioSink.AudioDataFormat avChosenAudioFormat;
    protected AudioSink.AudioDataFormat sinkChosenAudioFormat;
    
    public FFMPEGMediaPlayer() {
        if(!available) {
            throw new RuntimeException("FFMPEGMediaPlayer not available");
        }
        moviePtr = createInstance0(DEBUG);
        if(0==moviePtr) {
            throw new GLException("Couldn't create FFMPEGInstance");
        }
        psm = new GLPixelStorageModes();
        audioSink = null;
    }
    @Override
    protected final int validateTextureCount(int desiredTextureCount) {
        return desiredTextureCount>2 ? Math.max(4, desiredTextureCount) : 2;
    }
    @Override
    protected final boolean requiresOffthreadGLCtx() { return true; }

    @Override
    protected final void destroyImpl(GL gl) {
        if (moviePtr != 0) {
            destroyInstance0(moviePtr);
            moviePtr = 0;
        }
        destroyAudioSink();
    }
    private final void destroyAudioSink() {
        final AudioSink _audioSink = audioSink;
        if( null != _audioSink ) {            
            audioSink = null;
            _audioSink.destroy();
        }
    }
    
    @Override
    protected final void initGLStreamImpl(GL gl, int vid, int aid) throws IOException {
        if(0==moviePtr) {
            throw new GLException("FFMPEG native instance null");
        }
        {
            final GLContextImpl ctx = (GLContextImpl)gl.getContext();
            final ProcAddressTable pt = ctx.getGLProcAddressTable();
            final long procAddrGLTexSubImage2D = getAddressFor(pt, "glTexSubImage2D");
            if( 0 == procAddrGLTexSubImage2D ) {
                throw new InternalError("glTexSubImage2D n/a in ProcAddressTable: "+pt.getClass().getName()+" of "+ctx.getGLVersion());
            }
            final long procAddrGLGetError = getAddressFor(pt, "glGetError");
            if( 0 == procAddrGLGetError ) {
                throw new InternalError("glGetError n/a in ProcAddressTable: "+pt.getClass().getName()+" of "+ctx.getGLVersion());
            }
            setGLFuncs0(moviePtr, procAddrGLTexSubImage2D, procAddrGLGetError);
        }
        
        final String urlS=urlConn.getURL().toExternalForm();
        
        aFrameCount = AFRAMES_PER_VFRAME * textureCount + AFRAMES_PER_VFRAME/2;
    
        System.err.println("setURL: p1 "+this);
        destroyAudioSink();
        AudioSink _audioSink;
        if( GLMediaPlayer.STREAM_ID_NONE == aid ) {
            _audioSink = AudioSinkFactory.createNull();
        } else {
            _audioSink = AudioSinkFactory.createDefault();
        }
        final AudioDataFormat preferredAudioFormat = _audioSink.getPreferredFormat();
         // setStream(..) issues updateAttributes*(..), and defines avChosenAudioFormat, vid, aid, .. etc
        setStream0(moviePtr, urlS, vid, aid, aFrameCount, preferredAudioFormat.channelCount, preferredAudioFormat.sampleRate);
        // final int audioBytesPerFrame = bps_audio/8000 * frame_period * textureCount;
        
        System.err.println("setURL: p2 preferred "+preferredAudioFormat+", avChosen "+avChosenAudioFormat+", "+this);
        sinkChosenAudioFormat = _audioSink.initSink(avChosenAudioFormat, aFrameCount);
        System.err.println("setURL: p3 avChosen "+avChosenAudioFormat+", chosen "+sinkChosenAudioFormat);
        if( null == sinkChosenAudioFormat ) {
            System.err.println("AudioSink "+_audioSink.getClass().getName()+" does not support "+avChosenAudioFormat+", using Null");
            _audioSink.destroy();
            _audioSink = AudioSinkFactory.createNull();
            sinkChosenAudioFormat = _audioSink.initSink(avChosenAudioFormat, aFrameCount);
        }
        audioSink = _audioSink;
        
        int tf, tif=GL.GL_RGBA; // texture format and internal format
        switch(vBytesPerPixelPerPlane) {
            case 1:
                if( gl.isGL3ES3() ) {
                    // RED is supported on ES3 and >= GL3 [core]; ALPHA is deprecated on core
                    tf = GL2ES2.GL_RED;   tif=GL2ES2.GL_RED; singleTexComp = "r";
                } else {
                    // ALPHA is supported on ES2 and GL2, i.e. <= GL3 [core] or compatibility
                    tf = GL2ES2.GL_ALPHA; tif=GL2ES2.GL_ALPHA; singleTexComp = "a";
                }
                break;
            case 3: tf = GL2ES2.GL_RGB;   tif=GL.GL_RGB;     break;
            case 4: tf = GL2ES2.GL_RGBA;  tif=GL.GL_RGBA;    break;
            default: throw new RuntimeException("Unsupported bytes-per-pixel / plane "+vBytesPerPixelPerPlane);
        }        
        setTextureFormat(tif, tf);
        setTextureType(GL.GL_UNSIGNED_BYTE);
    }
    @Override
    protected final TextureFrame createTexImage(GL gl, int texName) {
        return new TextureFrame( createTexImageImpl(gl, texName, texWidth, texHeight, true) );
    }
    
    /**
     * Catches IllegalArgumentException and returns 0 if functionName is n/a,
     * otherwise the ProcAddressTable's field value. 
     */
    private final long getAddressFor(final ProcAddressTable table, final String functionName) {
        return AccessController.doPrivileged(new PrivilegedAction<Long>() {
            public Long run() {
                try {
                    return Long.valueOf( table.getAddressFor(functionName) );
                } catch (IllegalArgumentException iae) { 
                    return Long.valueOf(0);
                }
            }
        } ).longValue();
    }

    private void updateAttributes2(int pixFmt, int planes, int bitsPerPixel, int bytesPerPixelPerPlane,
                                   int lSz0, int lSz1, int lSz2,
                                   int tWd0, int tWd1, int tWd2,
                                   int sampleFmt, int sampleRate, int channels) {
        vPixelFmt = PixelFormat.valueOf(pixFmt);
        vPlanes = planes;
        vBitsPerPixel = bitsPerPixel;
        vBytesPerPixelPerPlane = bytesPerPixelPerPlane;
        vLinesize[0] = lSz0; vLinesize[1] = lSz1; vLinesize[2] = lSz2;
        vTexWidth[0] = tWd0; vTexWidth[1] = tWd1; vTexWidth[2] = tWd2;
        
        switch(vPixelFmt) {
            case YUV420P:
                // YUV420P: Adding U+V on right side of fixed height texture,
                //          since width is already aligned by decoder.
                // Y=w*h, Y=w/2*h/2, U=w/2*h/2
                // w*h + 2 ( w/2 * h/2 ) 
                // w*h + w*h/2
                // 2*w/2 * h 
                texWidth = vTexWidth[0] + vTexWidth[1]; texHeight = height; 
                break;
            // case PIX_FMT_YUYV422:
            case RGB24:
            case BGR24:
            case ARGB:
            case RGBA:
            case ABGR:
            case BGRA:
                texWidth = vTexWidth[0]; texHeight = height; 
                break;
            default: // FIXME: Add more planar formats !
                throw new RuntimeException("Unsupported pixelformat: "+vPixelFmt);
        }

        aSampleFmt = SampleFormat.valueOf(sampleFmt);
        final int sampleSize;
        final boolean signed, fixedP;
        switch( aSampleFmt ) {
            case S32:
            case S32P:
                sampleSize = 32;
                signed = true;
                fixedP = true;
                break;
            case S16:
            case S16P:
                sampleSize = 16;
                signed = true;
                fixedP = true;
                break;
            case U8:
            case U8P:
                sampleSize = 8;
                signed = false;
                fixedP = true;
                break;
            case DBL:
            case DBLP:
                sampleSize = 64;
                signed = true;
                fixedP = true;
                break;
            case FLT:
            case FLTP:
                sampleSize = 32;
                signed = true;
                fixedP = true;
                break;
            default: // FIXME: Add more planar formats !
                throw new RuntimeException("Unsupported sampleformat: "+aSampleFmt);
        }
        avChosenAudioFormat = new AudioDataFormat(AudioDataType.PCM, sampleRate, sampleSize, channels, signed, fixedP, true /* littleEndian */);  
        
        if(DEBUG) {
            System.err.println("audio: fmt "+aSampleFmt+", "+avChosenAudioFormat);
            System.err.println("video: fmt "+vPixelFmt+", planes "+vPlanes+", bpp "+vBitsPerPixel+"/"+vBytesPerPixelPerPlane);
            for(int i=0; i<3; i++) {
                System.err.println("video: "+i+": "+vTexWidth[i]+"/"+vLinesize[i]);
            }
            System.err.println("video: total tex "+texWidth+"x"+texHeight);
        }
    }
    
    /**
     * {@inheritDoc}
     * 
     * If this implementation generates a specialized shader,
     * it allows the user to override the default function name <code>ffmpegTexture2D</code>.
     * Otherwise the call is delegated to it's super class.
     */
    @Override
    public final String getTextureLookupFunctionName(String desiredFuncName) throws IllegalStateException {
        if(State.Uninitialized == state) {
            throw new IllegalStateException("Instance not initialized: "+this);
        }
        if(PixelFormat.YUV420P == vPixelFmt) {
            if(null != desiredFuncName && desiredFuncName.length()>0) {
                textureLookupFunctionName = desiredFuncName;
            }
            return textureLookupFunctionName;
        }
        return super.getTextureLookupFunctionName(desiredFuncName);        
    }
    private String textureLookupFunctionName = "ffmpegTexture2D";
    
    /**
     * {@inheritDoc}
     * 
     * Depending on the pixelformat, a specific conversion shader is being created,
     * e.g. YUV420P to RGB. Otherwise the call is delegated to it's super class.  
     */ 
    @Override
    public final String getTextureLookupFragmentShaderImpl() throws IllegalStateException {
      if(State.Uninitialized == state) {
          throw new IllegalStateException("Instance not initialized: "+this);
      }
      final float tc_w_1 = (float)getWidth() / (float)texWidth;
      switch(vPixelFmt) {
        case YUV420P:
          return
              "vec4 "+textureLookupFunctionName+"(in "+getTextureSampler2DType()+" image, in vec2 texCoord) {\n"+
              "  vec2 u_off = vec2("+tc_w_1+", 0.0);\n"+
              "  vec2 v_off = vec2("+tc_w_1+", 0.5);\n"+
              "  vec2 tc_half = texCoord*0.5;\n"+
              "  float y,u,v,r,g,b;\n"+
              "  y = texture2D(image, texCoord)."+singleTexComp+";\n"+
              "  u = texture2D(image, u_off+tc_half)."+singleTexComp+";\n"+
              "  v = texture2D(image, v_off+tc_half)."+singleTexComp+";\n"+
              "  y = 1.1643*(y-0.0625);\n"+
              "  u = u-0.5;\n"+
              "  v = v-0.5;\n"+
              "  r = y+1.5958*v;\n"+
              "  g = y-0.39173*u-0.81290*v;\n"+
              "  b = y+2.017*u;\n"+
              "  return vec4(r, g, b, 1);\n"+
              "}\n"
          ;
        default: // FIXME: Add more planar formats !
          return super.getTextureLookupFragmentShaderImpl();
      }        
    }
    
    @Override
    public final boolean playImpl() {
        if(0==moviePtr) {
            return false;
        }
        return true;
    }

    @Override
    public final boolean pauseImpl() {
        if(0==moviePtr) {
            return false;
        }
        return true;
    }

    @Override
    protected final synchronized int seekImpl(int msec) {
        if(0==moviePtr) {
            throw new GLException("FFMPEG native instance null");
        }
        return seek0(moviePtr, msec);
    }

    @Override
    protected final boolean getNextTextureImpl(GL gl, TextureFrame nextFrame, boolean blocking) {
        if(0==moviePtr) {
            throw new GLException("FFMPEG native instance null");
        }                
        psm.setUnpackAlignment(gl, 1); // RGBA ? 4 : 1
        int avPTS = 0;
        try {
            final Texture tex = nextFrame.getTexture();
            gl.glActiveTexture(GL.GL_TEXTURE0+getTextureUnit());
            tex.enable(gl);
            tex.bind(gl);

            /** Try decode up to 10 packets to find one containing video, i.e. vPTS > 0 */
            for(int retry=10; 0 >= avPTS && 0 < retry; retry--) {
               avPTS = readNextPacket0(moviePtr, textureTarget, textureFormat, textureType);
               retry--;
            }
        } finally {
            psm.restore(gl);
        }
        if( 0 < avPTS ) {
            nextFrame.setPTS(avPTS);
            return true;
        } else {
            return false;
        }
    }
    
    private final void pushSound(ByteBuffer sampleData, int data_size, int audio_pts) {
        setFirstAudioPTS2SCR( audio_pts );
        if( 1.0f == playSpeed || audioSinkPlaySpeedSet ) {
            audioSink.enqueueData( new AudioSink.AudioFrame(sampleData, data_size, audio_pts ) );
        }
    }

    @Override
    protected final boolean syncAVRequired() { return true; }
    
    private static native int getAvUtilVersion0();
    private static native int getAvFormatVersion0();
    private static native int getAvCodecVersion0();
    private static native boolean initIDs0();
    private native long createInstance0(boolean verbose);    
    private native void destroyInstance0(long moviePtr);
    
    /**
     * Issues {@link #updateAttributes(int, int, int, int, int, int, int, float, int, int, String, String)}
     * and {@link #updateAttributes2(int, int, int, int, int, int, int, int, int, int)}.
     * <p>
     * Always uses {@link AudioSink.AudioDataFormat}:
     * <pre>
     *   [type PCM, sampleRate [10000(?)..44100..48000], sampleSize 16, channelCount 1-2, signed, littleEndian]
     * </pre>
     * </p>
     */
    private native void setStream0(long moviePtr, String url, int vid, int aid, int audioFrameCount,
                                   int aChannelCount, int aSampleRate);
    private native void setGLFuncs0(long moviePtr, long procAddrGLTexSubImage2D, long procAddrGLGetError);

    private native int getVideoPTS0(long moviePtr);    
    
    private native int getAudioPTS0(long moviePtr);
    private native Buffer getAudioBuffer0(long moviePtr, int plane);
    
    /**
     * @return resulting current PTS: audio < 0, video > 0, invalid == 0
     */
    private native int readNextPacket0(long moviePtr, int texTarget, int texFmt, int texType);
    
    private native int seek0(long moviePtr, int position);
    
    public static enum SampleFormat {
        // NONE = -1,
        U8,          ///< unsigned 8 bits
        S16,         ///< signed 16 bits
        S32,         ///< signed 32 bits
        FLT,         ///< float
        DBL,         ///< double

        U8P,         ///< unsigned 8 bits, planar
        S16P,        ///< signed 16 bits, planar
        S32P,        ///< signed 32 bits, planar
        FLTP,        ///< float, planar
        DBLP,        ///< double, planar
        
        COUNT;       ///< Number of sample formats.
        
        public static SampleFormat valueOf(int i) {
            for (SampleFormat fmt : SampleFormat.values()) {
                if(fmt.ordinal() == i) {
                    return fmt;
                }
            }
            return null;            
        }
    };

    public static enum PixelFormat {
        // NONE= -1,
        YUV420P,   ///< planar YUV 4:2:0, 12bpp, (1 Cr & Cb sample per 2x2 Y samples)
        YUYV422,   ///< packed YUV 4:2:2, 16bpp, Y0 Cb Y1 Cr
        RGB24,     ///< packed RGB 8:8:8, 24bpp, RGBRGB...
        BGR24,     ///< packed RGB 8:8:8, 24bpp, BGRBGR...
        YUV422P,   ///< planar YUV 4:2:2, 16bpp, (1 Cr & Cb sample per 2x1 Y samples)
        YUV444P,   ///< planar YUV 4:4:4, 24bpp, (1 Cr & Cb sample per 1x1 Y samples)
        YUV410P,   ///< planar YUV 4:1:0,  9bpp, (1 Cr & Cb sample per 4x4 Y samples)
        YUV411P,   ///< planar YUV 4:1:1, 12bpp, (1 Cr & Cb sample per 4x1 Y samples)
        GRAY8,     ///<        Y        ,  8bpp
        MONOWHITE, ///<        Y        ,  1bpp, 0 is white, 1 is black, in each byte pixels are ordered from the msb to the lsb
        MONOBLACK, ///<        Y        ,  1bpp, 0 is black, 1 is white, in each byte pixels are ordered from the msb to the lsb
        PAL8,      ///< 8 bit with RGB32 palette
        YUVJ420P,  ///< planar YUV 4:2:0, 12bpp, full scale (JPEG), deprecated in favor of YUV420P and setting color_range
        YUVJ422P,  ///< planar YUV 4:2:2, 16bpp, full scale (JPEG), deprecated in favor of YUV422P and setting color_range
        YUVJ444P,  ///< planar YUV 4:4:4, 24bpp, full scale (JPEG), deprecated in favor of YUV444P and setting color_range
        XVMC_MPEG2_MC,///< XVideo Motion Acceleration via common packet passing
        XVMC_MPEG2_IDCT,
        UYVY422,   ///< packed YUV 4:2:2, 16bpp, Cb Y0 Cr Y1
        UYYVYY411, ///< packed YUV 4:1:1, 12bpp, Cb Y0 Y1 Cr Y2 Y3
        BGR8,      ///< packed RGB 3:3:2,  8bpp, (msb)2B 3G 3R(lsb)
        BGR4,      ///< packed RGB 1:2:1 bitstream,  4bpp, (msb)1B 2G 1R(lsb), a byte contains two pixels, the first pixel in the byte is the one composed by the 4 msb bits
        BGR4_BYTE, ///< packed RGB 1:2:1,  8bpp, (msb)1B 2G 1R(lsb)
        RGB8,      ///< packed RGB 3:3:2,  8bpp, (msb)2R 3G 3B(lsb)
        RGB4,      ///< packed RGB 1:2:1 bitstream,  4bpp, (msb)1R 2G 1B(lsb), a byte contains two pixels, the first pixel in the byte is the one composed by the 4 msb bits
        RGB4_BYTE, ///< packed RGB 1:2:1,  8bpp, (msb)1R 2G 1B(lsb)
        NV12,      ///< planar YUV 4:2:0, 12bpp, 1 plane for Y and 1 plane for the UV components, which are interleaved (first byte U and the following byte V)
        NV21,      ///< as above, but U and V bytes are swapped

        ARGB,      ///< packed ARGB 8:8:8:8, 32bpp, ARGBARGB...
        RGBA,      ///< packed RGBA 8:8:8:8, 32bpp, RGBARGBA...
        ABGR,      ///< packed ABGR 8:8:8:8, 32bpp, ABGRABGR...
        BGRA,      ///< packed BGRA 8:8:8:8, 32bpp, BGRABGRA...

        GRAY16BE,  ///<        Y        , 16bpp, big-endian
        GRAY16LE,  ///<        Y        , 16bpp, little-endian
        YUV440P,   ///< planar YUV 4:4:0 (1 Cr & Cb sample per 1x2 Y samples)
        YUVJ440P,  ///< planar YUV 4:4:0 full scale (JPEG), deprecated in favor of YUV440P and setting color_range
        YUVA420P,  ///< planar YUV 4:2:0, 20bpp, (1 Cr & Cb sample per 2x2 Y & A samples)
        VDPAU_H264,///< H.264 HW decoding with VDPAU, data[0] contains a vdpau_render_state struct which contains the bitstream of the slices as well as various fields extracted from headers
        VDPAU_MPEG1,///< MPEG-1 HW decoding with VDPAU, data[0] contains a vdpau_render_state struct which contains the bitstream of the slices as well as various fields extracted from headers
        VDPAU_MPEG2,///< MPEG-2 HW decoding with VDPAU, data[0] contains a vdpau_render_state struct which contains the bitstream of the slices as well as various fields extracted from headers
        VDPAU_WMV3,///< WMV3 HW decoding with VDPAU, data[0] contains a vdpau_render_state struct which contains the bitstream of the slices as well as various fields extracted from headers
        VDPAU_VC1, ///< VC-1 HW decoding with VDPAU, data[0] contains a vdpau_render_state struct which contains the bitstream of the slices as well as various fields extracted from headers
        RGB48BE,   ///< packed RGB 16:16:16, 48bpp, 16R, 16G, 16B, the 2-byte value for each R/G/B component is stored as big-endian
        RGB48LE,   ///< packed RGB 16:16:16, 48bpp, 16R, 16G, 16B, the 2-byte value for each R/G/B component is stored as little-endian

        RGB565BE,  ///< packed RGB 5:6:5, 16bpp, (msb)   5R 6G 5B(lsb), big-endian
        RGB565LE,  ///< packed RGB 5:6:5, 16bpp, (msb)   5R 6G 5B(lsb), little-endian
        RGB555BE,  ///< packed RGB 5:5:5, 16bpp, (msb)1A 5R 5G 5B(lsb), big-endian, most significant bit to 0
        RGB555LE,  ///< packed RGB 5:5:5, 16bpp, (msb)1A 5R 5G 5B(lsb), little-endian, most significant bit to 0

        BGR565BE,  ///< packed BGR 5:6:5, 16bpp, (msb)   5B 6G 5R(lsb), big-endian
        BGR565LE,  ///< packed BGR 5:6:5, 16bpp, (msb)   5B 6G 5R(lsb), little-endian
        BGR555BE,  ///< packed BGR 5:5:5, 16bpp, (msb)1A 5B 5G 5R(lsb), big-endian, most significant bit to 1
        BGR555LE,  ///< packed BGR 5:5:5, 16bpp, (msb)1A 5B 5G 5R(lsb), little-endian, most significant bit to 1

        VAAPI_MOCO, ///< HW acceleration through VA API at motion compensation entry-point, Picture.data[3] contains a vaapi_render_state struct which contains macroblocks as well as various fields extracted from headers
        VAAPI_IDCT, ///< HW acceleration through VA API at IDCT entry-point, Picture.data[3] contains a vaapi_render_state struct which contains fields extracted from headers
        VAAPI_VLD,  ///< HW decoding through VA API, Picture.data[3] contains a vaapi_render_state struct which contains the bitstream of the slices as well as various fields extracted from headers

        YUV420P16LE,  ///< planar YUV 4:2:0, 24bpp, (1 Cr & Cb sample per 2x2 Y samples), little-endian
        YUV420P16BE,  ///< planar YUV 4:2:0, 24bpp, (1 Cr & Cb sample per 2x2 Y samples), big-endian
        YUV422P16LE,  ///< planar YUV 4:2:2, 32bpp, (1 Cr & Cb sample per 2x1 Y samples), little-endian
        YUV422P16BE,  ///< planar YUV 4:2:2, 32bpp, (1 Cr & Cb sample per 2x1 Y samples), big-endian
        YUV444P16LE,  ///< planar YUV 4:4:4, 48bpp, (1 Cr & Cb sample per 1x1 Y samples), little-endian
        YUV444P16BE,  ///< planar YUV 4:4:4, 48bpp, (1 Cr & Cb sample per 1x1 Y samples), big-endian
        VDPAU_MPEG4,  ///< MPEG4 HW decoding with VDPAU, data[0] contains a vdpau_render_state struct which contains the bitstream of the slices as well as various fields extracted from headers
        DXVA2_VLD,    ///< HW decoding through DXVA2, Picture.data[3] contains a LPDIRECT3DSURFACE9 pointer

        RGB444LE,  ///< packed RGB 4:4:4, 16bpp, (msb)4A 4R 4G 4B(lsb), little-endian, most significant bits to 0
        RGB444BE,  ///< packed RGB 4:4:4, 16bpp, (msb)4A 4R 4G 4B(lsb), big-endian, most significant bits to 0
        BGR444LE,  ///< packed BGR 4:4:4, 16bpp, (msb)4A 4B 4G 4R(lsb), little-endian, most significant bits to 1
        BGR444BE,  ///< packed BGR 4:4:4, 16bpp, (msb)4A 4B 4G 4R(lsb), big-endian, most significant bits to 1
        Y400A,     ///< 8bit gray, 8bit alpha
        BGR48BE,   ///< packed RGB 16:16:16, 48bpp, 16B, 16G, 16R, the 2-byte value for each R/G/B component is stored as big-endian
        BGR48LE,   ///< packed RGB 16:16:16, 48bpp, 16B, 16G, 16R, the 2-byte value for each R/G/B component is stored as little-endian
        YUV420P9BE, ///< planar YUV 4:2:0, 13.5bpp, (1 Cr & Cb sample per 2x2 Y samples), big-endian
        YUV420P9LE, ///< planar YUV 4:2:0, 13.5bpp, (1 Cr & Cb sample per 2x2 Y samples), little-endian
        YUV420P10BE,///< planar YUV 4:2:0, 15bpp, (1 Cr & Cb sample per 2x2 Y samples), big-endian
        YUV420P10LE,///< planar YUV 4:2:0, 15bpp, (1 Cr & Cb sample per 2x2 Y samples), little-endian
        YUV422P10BE,///< planar YUV 4:2:2, 20bpp, (1 Cr & Cb sample per 2x1 Y samples), big-endian
        YUV422P10LE,///< planar YUV 4:2:2, 20bpp, (1 Cr & Cb sample per 2x1 Y samples), little-endian
        YUV444P9BE, ///< planar YUV 4:4:4, 27bpp, (1 Cr & Cb sample per 1x1 Y samples), big-endian
        YUV444P9LE, ///< planar YUV 4:4:4, 27bpp, (1 Cr & Cb sample per 1x1 Y samples), little-endian
        YUV444P10BE,///< planar YUV 4:4:4, 30bpp, (1 Cr & Cb sample per 1x1 Y samples), big-endian
        YUV444P10LE,///< planar YUV 4:4:4, 30bpp, (1 Cr & Cb sample per 1x1 Y samples), little-endian
        YUV422P9BE, ///< planar YUV 4:2:2, 18bpp, (1 Cr & Cb sample per 2x1 Y samples), big-endian
        YUV422P9LE, ///< planar YUV 4:2:2, 18bpp, (1 Cr & Cb sample per 2x1 Y samples), little-endian
        VDA_VLD,    ///< hardware decoding through VDA
        GBRP,      ///< planar GBR 4:4:4 24bpp
        GBRP9BE,   ///< planar GBR 4:4:4 27bpp, big endian
        GBRP9LE,   ///< planar GBR 4:4:4 27bpp, little endian
        GBRP10BE,  ///< planar GBR 4:4:4 30bpp, big endian
        GBRP10LE,  ///< planar GBR 4:4:4 30bpp, little endian
        GBRP16BE,  ///< planar GBR 4:4:4 48bpp, big endian
        GBRP16LE,  ///< planar GBR 4:4:4 48bpp, little endian
        COUNT      ///< number of pixel formats in this list
        ;
        public static PixelFormat valueOf(int i) {
            for (PixelFormat fmt : PixelFormat.values()) {
                if(fmt.ordinal() == i) {
                    return fmt;
                }
            }
            return null;            
        }
    }
}

