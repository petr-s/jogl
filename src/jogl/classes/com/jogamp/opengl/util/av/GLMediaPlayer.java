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
package com.jogamp.opengl.util.av;

import java.io.IOException;
import java.net.URLConnection;

import javax.media.opengl.GL;
import javax.media.opengl.GLException;

import jogamp.opengl.Debug;
import jogamp.opengl.util.av.GLMediaPlayerImpl;

import com.jogamp.opengl.util.texture.TextureSequence;

/**
 * GLMediaPlayer interface specifies a {@link TextureSequence}
 * with a video stream as it's source.
 * <p>
 * Audio maybe supported and played back internally or via an {@link AudioSink} implementation,
 * if an audio stream is selected in {@link #initGLStream(GL, int, URLConnection, int, int)}.
 * </p>  
 * <a name="lifecycle"><h5>GLMediaPlayer Lifecycle</h5></a>
 * <p>
 * <table border="1">
 *   <tr><th>action</th>                                                  <th>state before</th>        <th>state after</th></tr>
 *   <tr><td>{@link #initGLStream(GL, int, URLConnection, int, int)}</td> <td>Uninitialized</td>       <td>Paused</td></tr>
 *   <tr><td>{@link #play()}</td>                                         <td>Paused</td>              <td>Playing</td></tr>
 *   <tr><td>{@link #pause()}</td>                                        <td>Playing</td>             <td>Paused</td></tr>
 *   <tr><td>{@link #seek(int)}</td>                                      <td>Playing, Paused</td>     <td>Unchanged</td></tr>
 *   <tr><td>{@link #destroy(GL)}</td>                                    <td>ANY</td>                 <td>Uninitialized</td></tr>
 * </table>
 * </p>
 * <a name="streamIDs"><h5>Audio and video Stream IDs</h5></a>
 * <p>
 * <table border="1">
 *   <tr><th>value</th>                    <th>request</th>             <th>get</th></tr>
 *   <tr><td>{@link #STREAM_ID_NONE}</td>  <td>mute</td>                <td>not available</td></tr>
 *   <tr><td>{@link #STREAM_ID_AUTO}</td>  <td>auto</td>                <td>unspecified</td></tr>
 *   <tr><td>&ge;0</td>                    <td>specific stream</td>     <td>specific stream</td></tr>
 * </table>
 * </p>
 * <p>
 * Current implementations (check each API doc link for details):
 * <ul>
 *   <li>{@link jogamp.opengl.util.av.NullGLMediaPlayer}</li>
 *   <li>{@link jogamp.opengl.util.av.impl.OMXGLMediaPlayer}</li>
 *   <li>{@link jogamp.opengl.util.av.impl.FFMPEGMediaPlayer}</li>
 *   <li>{@link jogamp.opengl.android.av.AndroidGLMediaPlayerAPI14}</li> 
 * </ul>
 * </p>
 * <p>
 * Implementations of this interface must implement:
 * <pre>
 *    public static final boolean isAvailable();
 * </pre>
 * to be properly considered by {@link GLMediaPlayerFactory#create(ClassLoader, String)}
 * and {@link GLMediaPlayerFactory#createDefault()}.
 * </p>
 * <p>
 * Variable type, value range and dimension has been chosen to suit embedded CPUs
 * and characteristics of audio and video streaming.
 * Milliseconds of type integer with a maximum value of {@link Integer#MAX_VALUE} 
 * will allow tracking time up 2,147,483.647 seconds or
 * 24 days 20 hours 31 minutes and 23 seconds.
 * Milliseconds granularity is also more than enough to deal with A-V synchronization,
 * where the threshold usually lies within 100ms. 
 * </p>
 */
public interface GLMediaPlayer extends TextureSequence {
    public static final boolean DEBUG = Debug.debug("GLMediaPlayer");
    
    /** Constant {@value} for <i>mute</i> or <i>not available</i>. See <a href="#streamIDs">Audio and video Stream IDs</a>. */
    public static final int STREAM_ID_NONE = -2;
    /** Constant {@value} for <i>auto</i> or <i>unspecified</i>. See <a href="#streamIDs">Audio and video Stream IDs</a>. */
    public static final int STREAM_ID_AUTO = -1;
        
    public interface GLMediaEventListener extends TexSeqEventListener<GLMediaPlayer> {
    
        static final int EVENT_CHANGE_VID    = 1<<0;
        static final int EVENT_CHANGE_AID    = 1<<1;
        static final int EVENT_CHANGE_SIZE   = 1<<2;
        static final int EVENT_CHANGE_FPS    = 1<<3;
        static final int EVENT_CHANGE_BPS    = 1<<4;
        static final int EVENT_CHANGE_LENGTH = 1<<5;
        static final int EVENT_CHANGE_CODEC  = 1<<6;
    
        /**
         * @param mp the event source 
         * @param event_mask the changes attributes
         * @param when system time in msec. 
         */
        public void attributesChanges(GLMediaPlayer mp, int event_mask, long when);    
    }
    
    /**
     * See <a href="#lifecycle">GLMediaPlayer Lifecycle</a>.
     */
    public enum State {
        Uninitialized(0), Playing(1), Paused(2);
        
        public final int id;

        State(int id){
            this.id = id;
        }
    }
    
    public int getTextureCount();
    
    /** Returns the texture target used by implementation. */
    public int getTextureTarget();

    /** Sets the texture unit. Defaults to 0. */
    public void setTextureUnit(int u);
    
    /** Sets the texture min-mag filter, defaults to {@link GL#GL_NEAREST}. */
    public void setTextureMinMagFilter(int[] minMagFilter);
    /** Sets the texture min-mag filter, defaults to {@link GL#GL_CLAMP_TO_EDGE}. */
    public void setTextureWrapST(int[] wrapST);
    
    /** 
     * Sets the stream to be used. Initializes all stream related states inclusive OpenGL ones,
     * if <code>gl</code> is not null.
     * <p>
     * <a href="#lifecycle">GLMediaPlayer Lifecycle</a>: Uninitialized -> Paused
     * </p>
     * @param gl current GL object. If null, no video output and textures will be available.
     * @param textureCount desired number of buffered textures to be decoded off-thread, use <code>1</code> for on-thread decoding.  
     * @param urlConn the stream connection
     * @param vid video stream id, see <a href="#streamIDs">audio and video Stream IDs</a>
     * @param aid video stream id, see <a href="#streamIDs">audio and video Stream IDs</a>
     * @return the new state
     * 
     * @throws IllegalStateException if not invoked in state Uninitialized 
     * @throws IOException in case of difficulties to open or process the stream
     * @throws GLException in case of difficulties to initialize the GL resources
     */
    public State initGLStream(GL gl, int textureCount, URLConnection urlConn, int vid, int aid) throws IllegalStateException, GLException, IOException;
    
    /** 
     * If implementation uses a {@link AudioSink}, it's instance will be returned.
     * <p> 
     * The {@link AudioSink} instance is available after {@link #initGLStream(GL, int, URLConnection, int, int)}, 
     * if used by implementation.
     * </p> 
     */
    public AudioSink getAudioSink();
    
    /**
     * Releases the GL and stream resources.
     * <p>
     * <a href="#lifecycle">GLMediaPlayer Lifecycle</a>: <code>ANY</code> -> Uninitialized
     * </p>
     */
    public State destroy(GL gl);

    /**
     * Sets the playback speed.
     * <p>
     * Play speed is set to <i>normal</i>, i.e. <code>1.0f</code>
     * if <code> abs(1.0f - rate) < 0.01f</code> to simplify test.
     * </p>
     * @return true if successful, otherwise false, i.e. due to unsupported value range of implementation. 
     */
    public boolean setPlaySpeed(float rate);

    public float getPlaySpeed();

    /**
     * <a href="#lifecycle">GLMediaPlayer Lifecycle</a>: Paused -> Playing
     */
    public State play();

    /**
     * <a href="#lifecycle">GLMediaPlayer Lifecycle</a>: Playing -> Paused
     */
    public State pause();

    /**
     * Allowed in state Playing and Paused, otherwise ignored,
     * see <a href="#lifecycle">GLMediaPlayer Lifecycle</a>. 
     * 
     * @param msec absolute desired time position in milliseconds 
     * @return time current position in milliseconds, after seeking to the desired position  
     **/
    public int seek(int msec);

    /**
     * See <a href="#lifecycle">GLMediaPlayer Lifecycle</a>.
     * @return the current state, either Uninitialized, Playing, Paused
     */
    public State getState();
    
    /**
     * Return the video stream id, see <a href="#streamIDs">audio and video Stream IDs</a>.
     */
    public int getVID();
    
    /**
     * Return the audio stream id, see <a href="#streamIDs">audio and video Stream IDs</a>.
     */
    public int getAID();
    
    /**
     * @return the current decoded frame count since {@link #initGLStream(GL, int, URLConnection, int, int)}.
     */
    public int getDecodedFrameCount();
    
    /**
     * @return the current presented frame count since {@link #initGLStream(GL, int, URLConnection, int, int)}, 
     *         increased by {@link #getNextTexture(GL, boolean)}.
     */
    public int getPresentedFrameCount();
    
    /**
     * @return current video presentation timestamp (PTS) in milliseconds of {@link #getLastTexture()} 
     **/
    public int getVideoPTS();
    
    /**
     * @return current audio presentation timestamp (PTS) in milliseconds. 
     **/
    public int getAudioPTS();
    
    /**
     * {@inheritDoc}
     */
    @Override
    public TextureSequence.TextureFrame getLastTexture() throws IllegalStateException;

    /**
     * {@inheritDoc}
     * 
     * <p>
     * In case the current state is not {@link State#Playing}, {@link #getLastTexture()} is returned.
     * </p>
     * 
     * @see #addEventListener(GLMediaEventListener)
     * @see GLMediaEventListener#newFrameAvailable(GLMediaPlayer, TextureFrame, long)
     */
    @Override
    public TextureSequence.TextureFrame getNextTexture(GL gl, boolean blocking) throws IllegalStateException;
    
    public URLConnection getURLConnection();

    /**
     * <i>Warning:</i> Optional information, may not be supported by implementation.
     * @return the code of the video stream, if available 
     */
    public String getVideoCodec();

    /**
     * <i>Warning:</i> Optional information, may not be supported by implementation.
     * @return the code of the audio stream, if available 
     */
    public String getAudioCodec();

    /**
     * <i>Warning:</i> Optional information, may not be supported by implementation.
     * @return the total number of video frames
     */
    public int getVideoFrames();

    /**
     * <i>Warning:</i> Optional information, may not be supported by implementation.
     * @return the total number of audio frames
     */
    public int getAudioFrames();

    /**
     * @return total duration of stream in msec.
     */
    public int getDuration();
    
    /**
     * <i>Warning:</i> Optional information, may not be supported by implementation.
     * @return the overall bitrate of the stream.  
     */
    public long getStreamBitrate();

    /**
     * <i>Warning:</i> Optional information, may not be supported by implementation.
     * @return video bitrate  
     */
    public int getVideoBitrate();
    
    /**
     * <i>Warning:</i> Optional information, may not be supported by implementation.
     * @return the audio bitrate  
     */
    public int getAudioBitrate();
    
    /**
     * <i>Warning:</i> Optional information, may not be supported by implementation.
     * @return the framerate of the video
     */
    public float getFramerate();

    public int getWidth();

    public int getHeight();

    public String toString();

    public String getPerfString();
    
    public void addEventListener(GLMediaEventListener l);

    public void removeEventListener(GLMediaEventListener l);

    public GLMediaEventListener[] getEventListeners();    

}
