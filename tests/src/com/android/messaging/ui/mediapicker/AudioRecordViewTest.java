/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.messaging.ui.mediapicker;

import android.media.MediaPlayer;
import android.media.MediaRecorder.OnErrorListener;
import android.media.MediaRecorder.OnInfoListener;
import android.net.Uri;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;

import com.android.messaging.FakeFactory;
import com.android.messaging.R;
import com.android.messaging.datamodel.data.MessagePartData;
import com.android.messaging.ui.BugleActivityInstrumentationTestCase;
import com.android.messaging.ui.TestActivity;
import com.android.messaging.util.FakeMediaUtil;

import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.Mockito;

public class AudioRecordViewTest extends BugleActivityInstrumentationTestCase<TestActivity> {

    public AudioRecordViewTest() { super(TestActivity.class); }

    @Mock AudioRecordView.HostInterface mockHost;
    @Mock LevelTrackingMediaRecorder mockRecorder;
    @Mock MediaPlayer mockMediaPlayer;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        FakeFactory.register(getInstrumentation().getTargetContext())
                .withMediaUtil(new FakeMediaUtil(mockMediaPlayer));
    }

    private void verifyAudioSubmitted() {
        Mockito.verify(mockHost).onAudioRecorded(Matchers.any(MessagePartData.class));
    }

    private AudioRecordView initView() {
        getActivity();
        final AudioRecordView[] result = new AudioRecordView[1];
        getInstrumentation().runOnMainSync(() -> {
            AudioRecordView view = (AudioRecordView) getActivity().getLayoutInflater()
                    .inflate(R.layout.mediapicker_audio_chooser, null);
            view.testSetMediaRecorder(mockRecorder);
            view.setHostInterface(mockHost);
            getActivity().setContentView(view);
            result[0] = view;
        });
        getInstrumentation().waitForIdleSync();
        return result[0];
    }

    public void testRecording() {
        Mockito.when(mockRecorder.isRecording()).thenReturn(false);
        Mockito.when(mockRecorder.startRecording(Matchers.<OnErrorListener>any(),
                Matchers.<OnInfoListener>any(), Matchers.anyInt())).thenReturn(true);
        Mockito.when(mockRecorder.stopRecording()).thenReturn(Uri.parse("content://someaudio/2"));
        final AudioRecordView view = initView();
        getInstrumentation().runOnMainSync(() -> view.onRecordButtonTouchDown());
        Mockito.verify(mockRecorder).startRecording(Matchers.<OnErrorListener>any(),
                Matchers.<OnInfoListener>any(), Matchers.anyInt());
        Mockito.when(mockRecorder.isRecording()).thenReturn(true);
        // Record for 1 second to make it meaningful.
        sleepNoThrow(1000);
        getInstrumentation().runOnMainSync(() -> view.onRecordButtonTouchUp());
        // We added some buffer to the end of the audio recording, so sleep for sometime and
        // verify audio is recorded.
        sleepNoThrow(700);
        Mockito.verify(mockRecorder).stopRecording();
        verifyAudioSubmitted();
    }

    private void sleepNoThrow(final long duration) {
        try {
            Thread.sleep(duration);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void testCenterHoldRecordsOnceAndReleases() {
        Mockito.when(mockRecorder.isRecording()).thenReturn(false);
        Mockito.when(mockRecorder.startRecording(Matchers.<OnErrorListener>any(),
                Matchers.<OnInfoListener>any(), Matchers.anyInt())).thenReturn(true);
        Mockito.when(mockRecorder.stopRecording()).thenReturn(Uri.parse("content://someaudio/2"));
        final AudioRecordView view = initView();
        final View button = view.findViewById(R.id.record_button);
        assertFalse(button.isFocusableInTouchMode());
        getInstrumentation().setInTouchMode(false);
        getInstrumentation().runOnMainSync(() -> assertTrue(button.requestFocus()));
        final long down = SystemClock.uptimeMillis();
        getInstrumentation().runOnMainSync(() -> button.dispatchKeyEvent(new KeyEvent(down, down,
                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0)));
        Mockito.when(mockRecorder.isRecording()).thenReturn(true);
        getInstrumentation().runOnMainSync(() -> button.dispatchKeyEvent(new KeyEvent(
                down, down + 100,
                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1)));
        Mockito.verify(mockRecorder, Mockito.times(1)).startRecording(
                Matchers.<OnErrorListener>any(), Matchers.<OnInfoListener>any(), Matchers.anyInt());
        sleepNoThrow(1000);
        getInstrumentation().runOnMainSync(() -> button.dispatchKeyEvent(new KeyEvent(down,
                SystemClock.uptimeMillis(), KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_DPAD_CENTER, 0)));
        sleepNoThrow(700);
        Mockito.verify(mockRecorder).stopRecording();
        verifyAudioSubmitted();
    }

}
