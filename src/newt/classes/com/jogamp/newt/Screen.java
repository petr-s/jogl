/**
 * Copyright 2010 JogAmp Community. All rights reserved.
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
package com.jogamp.newt;

import com.jogamp.newt.event.ScreenModeListener;
import com.jogamp.newt.impl.Debug;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.media.nativewindow.AbstractGraphicsScreen;
import javax.media.nativewindow.NativeWindowException;

public abstract class Screen {

    /**
     * A 10s timeout for screen mode change. It is observed, that some platforms
     * need a notable amount of time for this task, especially in case of rotation change.
     */
    public static final int SCREEN_MODE_CHANGE_TIMEOUT = 10000;

    public static final boolean DEBUG = Debug.debug("Screen");

    /** return precomputed hashCode from FQN {@link #getFQName()} */
    public abstract int hashCode();

    /** return true if obj is of type Display and both FQN {@link #getFQName()} equals */
    public boolean equals(Object obj) {
        if (this == obj) { return true; }
        if (obj instanceof Screen) {
            Screen s = (Screen)obj;
            return s.getFQName().equals(getFQName());
        }
        return false;
    }

    /**
     * Manual trigger the native creation.<br>
     * This is useful to be able to request the {@link javax.media.nativewindow.AbstractGraphicsScreen}, via
     * {@link #getGraphicsScreen()}. Otherwise the abstract device won't be available before the dependent component (Window)
     * is realized.
     * @throws NativeWindowException if the native creation failed.
     */
    public abstract void createNative() throws NativeWindowException;

    public abstract void destroy();

    public abstract boolean isNativeValid();

    /**
     * @return {@link Display#getDestroyWhenUnused()}
     *
     * @see #addReference()
     * @see #removeReference()
     * @see Display#setDestroyWhenUnused(boolean)
     */
    public abstract boolean getDestroyWhenUnused();

    /**
     * calls {@link Display#setDestroyWhenUnused(boolean)}.
     *
     * @see #addReference()
     * @see #removeReference()
     * @see Display#setDestroyWhenUnused(boolean)
     */
    public abstract void setDestroyWhenUnused(boolean v);

    /**
     *
     * @return number of references by Window
     */
    public abstract int getReferenceCount();

    /**
     * See {@link Display#addReference()}
     *
     * @throws NativeWindowException if the native creation failed.
     * @see #removeReference()
     * @see #setDestroyWhenUnused(boolean)
     * @see #getDestroyWhenUnused()
     */
    public abstract int addReference() throws NativeWindowException;

    /**
     * See {@link Display#removeReference()}
     *
     * @see #addReference()
     * @see #setDestroyWhenUnused(boolean)
     * @see #getDestroyWhenUnused()
     */
    public abstract int removeReference();

    public abstract AbstractGraphicsScreen getGraphicsScreen();

    /**
     * @return this Screen index of all Screens of {@link #getDisplay()}.
     */
    public abstract int getIndex();

    /**
     * @return the current screen width
     */
    public abstract int getWidth();

    /**
     * @return the current screen height
     */
    public abstract int getHeight();

    /**
     * @return the associated Display
     */
    public abstract Display getDisplay();

    /** 
     * @return the screen fully qualified Screen name,
     * which is a key of {@link com.jogamp.newt.Display#getFQName()} + {@link #getIndex()}.
     */
    public abstract String getFQName();

    /**
     * @param sml ScreenModeListener to be added for ScreenMode change events
     */
    public abstract void addScreenModeListener(ScreenModeListener sml);

    /**
     * @param sml ScreenModeListener to be removed from ScreenMode change events
     */
    public abstract void removeScreenModeListener(ScreenModeListener sml);

    /** 
     * Return a list of available {@link com.jogamp.newt.ScreenMode}s.
     * @return a shallow copy of the internal immutable {@link com.jogamp.newt.ScreenMode}s,
     * or null if not implemented for this native type {@link com.jogamp.newt.Display#getType()}.
     */
    public abstract List/*<ScreenMode>*/ getScreenModes();

    /**
     * Return the original {@link com.jogamp.newt.ScreenMode}, as used at NEWT initialization.
     * @return null if functionality not implemented,
     * otherwise the original ScreenMode which is element of the list {@link #getScreenModes()}.
     *
     */
    public abstract ScreenMode getOriginalScreenMode();

    /**
     * Return the current {@link com.jogamp.newt.ScreenMode}.
     * @return null if functionality not implemented,
     * otherwise the current ScreenMode which is element of the list {@link #getScreenModes()}.
     */
    public abstract ScreenMode getCurrentScreenMode();

    /**
     * Set the current {@link com.jogamp.newt.ScreenMode}.
     * @param screenMode to be made current, must be element of the list {@link #getScreenModes()}.
     * @return true if successful, otherwise false
     */
    public abstract boolean setCurrentScreenMode(ScreenMode screenMode);

    // Global Screens
    protected static ArrayList screenList = new ArrayList();
    protected static int screensActive = 0;

    /**
     *
     * @param type
     * @param name
     * @param fromIndex start index, then increasing until found or end of list     *
     * @return
     */
    public static Screen getFirstScreenOf(Display display, int idx, int fromIndex) {
        return getScreenOfImpl(display, idx, fromIndex, 1);
    }

    /**
     *
     * @param type
     * @param name
     * @param fromIndex start index, then decreasing until found or end of list. -1 is interpreted as size - 1.
     * @return
     */
    public static Screen getLastScreenOf(Display display, int idx, int fromIndex) {
        return getScreenOfImpl(display, idx, fromIndex, -1);
    }

    private static Screen getScreenOfImpl(Display display, int idx, int fromIndex, int incr) {
        synchronized(screenList) {
            int i = fromIndex >= 0 ? fromIndex : screenList.size() - 1 ;
            while( ( incr > 0 ) ? i < screenList.size() : i >= 0 ) {
                Screen screen = (Screen) screenList.get(i);
                if( screen.getDisplay().equals(display) &&
                    screen.getIndex() == idx ) {
                    return screen;
                }
                i+=incr;
            }
        }
        return null;
    }
    /** Returns the global display collection */
    public static Collection getAllScreens() {
        ArrayList list;
        synchronized(screenList) {
            list = (ArrayList) screenList.clone();
        }
        return list;
    }

    public static int getActiveScreenNumber() {
        synchronized(screenList) {
            return screensActive;
        }
    }
}
