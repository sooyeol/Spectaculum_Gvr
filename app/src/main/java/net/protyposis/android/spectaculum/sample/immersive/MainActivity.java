package net.protyposis.android.spectaculum.sample.immersive;

import android.net.Uri;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.audio.*;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.ext.gvr.*;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.ui.PlaybackControlView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;

import net.protyposis.android.spectaculum.InputSurfaceHolder;
import net.protyposis.android.spectaculum.SpectaculumView;
import net.protyposis.android.spectaculum.effects.Effect;
import net.protyposis.android.spectaculum.effects.ImmersiveEffect;
import net.protyposis.android.spectaculum.effects.ImmersiveSensorNavigation;
import net.protyposis.android.spectaculum.effects.ImmersiveTouchNavigation;
import net.protyposis.android.spectaculum.effects.Parameter;


public class MainActivity extends AppCompatActivity implements InputSurfaceHolder.Callback {

    private SpectaculumView mSpectaculumView;
    private PlaybackControlView mPlaybackControlView;
    private GvrAudioProcessor gvrAudioProcessor;

    ImmersiveEffect immersiveEffect;

    private Handler handler;

    private SimpleExoPlayer mExoPlayer;

    TextView textView;
    int counter;

    private final VideoSource[] mVideoSources = {
            // Orion360 Test Video: http://www.finwe.mobi/main/360-degree/orion360-test-images-videos/
            // Url extracted from https://littlstar.com/videos/c9c27ffc
            // Video format not supported on Nexus 9
            new VideoSource("https://360.littlstar.com/production/79f8bd2f-d137-46ac-a60a-f9b22f77b57d/download.mp4", ImmersiveEffect.Mode.MONO),
            // National Geographic Virtual Yellowstone: https://littlstar.com/videos/b541e0f4
            new VideoSource("https://360.littlstar.com/production/83ef40fe-6e8e-45ed-86f8-871c89c3a60f/download.mp4", ImmersiveEffect.Mode.MONO),
            // House Stereo Side-By-Side Demo
            // This video has a really low resolution but should play back on all devices
            new VideoSource("http://hosting.360heros.com/3D360Video/3D360/Demo3-House/3DH-Take1-Side-By-Side-1920x960.mp4", ImmersiveEffect.Mode.STEREO_SBS)
    };

    // SELECT THE VIDEO SOURCE HERE! (an index of the VideoSource array above)
    private int mSelectedVideoSource = 2;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Get view references from layout
        mSpectaculumView = (SpectaculumView) findViewById(R.id.spectaculumview);
        mPlaybackControlView = (PlaybackControlView) findViewById(R.id.playbackcontrolview);

        // Register callbacks to initialize and release player
        mSpectaculumView.getInputHolder().addCallback(this);


        // Setup Spectaculum view for immersive content
        immersiveEffect = new ImmersiveEffect(); // create effect instance
        immersiveEffect.setMode(mVideoSources[mSelectedVideoSource].immersiveMode); // Set VR the mode for selected video source
        mSpectaculumView.addEffect(immersiveEffect); // add effect to view

        // Setup Spectaculum immersive viewport touch navigation
        ImmersiveTouchNavigation immersiveTouchNavigation = new ImmersiveTouchNavigation(mSpectaculumView);
        immersiveTouchNavigation.attachTo(immersiveEffect);
        immersiveTouchNavigation.activate(); // enable touch navigation

        // Setup Spectaculum immersive viewport sensor navigation (highly experimental! does not work well together with touch navigation!)
        //ImmersiveSensorNavigation immersiveSensorNavigation = new ImmersiveSensorNavigation(this);
        //immersiveSensorNavigation.attachTo(immersiveEffect);
        //immersiveSensorNavigation.activate();


        // Listen to changes of the rotation matrix, e.g. to implement immersive audio (e.g. Ambisonics)
        final float[] rotationMatrix = new float[16];
        immersiveEffect.addListener(new Effect.Listener() {
            @Override
            public void onEffectChanged(Effect effect) {
                immersiveEffect.getRotationMatrix(rotationMatrix);
                // Now you can update whichever dependencies need the rotation data
                // Attention: This listener is not called on the main thread, you need a handler
                // to update components (e.g. UI elements) on the main thread! Alternatively, you
                // can poll getRotationMatrix from the main thread without this listener.
            }

            @Override
            public void onParameterAdded(Effect effect, Parameter parameter) {
            }

            @Override
            public void onParameterRemoved(Effect effect, Parameter parameter) {
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Release player to avoid writing frames to an invalid Spectaculum surface
        releasePlayer();

        // Pause Spectaculum rendering while it's inactive
        mSpectaculumView.onPause();
    }
    @Override
    protected void onResume() {
        super.onResume();

        // Resume Spectaculum rendering
        mSpectaculumView.onResume();
    }

    @Override
    public void surfaceCreated(InputSurfaceHolder holder) {
        // When the input surface (i.e. the Spectaculum surface that the player needs to write video frames)
        // has been successfully created, we can initialize the player and activate the shader effect
        initializePlayer();
        mSpectaculumView.selectEffect(0); // activate effect
    }

    @Override
    public void surfaceDestroyed(InputSurfaceHolder holder) {
        // When the input surface is gone, we cannot display video frames any more and release the player
        releasePlayer();
    }

    /**
     * Create an initialize an ExoPlayer instance that is ready for playback.
     */
    private void initializePlayer() {
        /*
         * Creating the player
         * Code taken from docs and simplified
         * https://google.github.io/ExoPlayer/guide.html#creating-the-player
         */
        // 1. Create a default TrackSelector
        TrackSelector trackSelector = new DefaultTrackSelector();

        // 2. Create a default LoadControl
        LoadControl loadControl = new DefaultLoadControl();

        // 3. Create the player
        RenderersFactory renderersFactory = new DefaultRenderersFactory(this) {
            @Override
            protected AudioProcessor[] buildAudioProcessors() {
                gvrAudioProcessor = new GvrAudioProcessor();
                return new AudioProcessor[] {gvrAudioProcessor};
            }
        };
        SimpleExoPlayer player = ExoPlayerFactory.newSimpleInstance(renderersFactory, trackSelector);

        /*
         * Attaching the player to a view
         * We do not use the SimpleExoPlayerView, because we want to use SpectaculumView. Instead,
         * we directly use a PlaybackControlView and attach the player to it.
         * https://google.github.io/ExoPlayer/guide.html#attaching-the-player-to-a-view
         */
        mPlaybackControlView.setPlayer(player);
        mPlaybackControlView.show();

        /*
         * Configure player for SpectaculumView
         */
        // Set Spectaculum view as playback surface
        player.setVideoSurface(mSpectaculumView.getInputHolder().getSurface());
        // Attach listener to listen to video size changed events
        player.setVideoListener(new SimpleExoPlayer.VideoListener() {
            @Override
            public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                // When the video size changes, we update the Spectaculum view
                mSpectaculumView.updateResolution(width, height);
            }

            @Override
            public void onRenderedFirstFrame() {
                // Hide loading indicator when video is ready for playback
                findViewById(R.id.loadingindicator).setVisibility(View.GONE);

                // Inform user that he can look around in the video
                Toast.makeText(MainActivity.this, R.string.drag, Toast.LENGTH_LONG).show();
            }
        });


        /*
         * Preparing the player
         * Code taken from docs and simplified
         * https://google.github.io/ExoPlayer/guide.html#preparing-the-player
         */
        // Produces DataSource instances through which media data is loaded.
        DataSource.Factory dataSourceFactory = new DefaultDataSourceFactory(this,
                Util.getUserAgent(this, "SpectaculumImmersiveSample"));
        // Produces Extractor instances for parsing the media data.
        ExtractorsFactory extractorsFactory = new DefaultExtractorsFactory();
        // This is the MediaSource representing the media to be played.

        MediaSource videoSource = new ExtractorMediaSource(Uri.parse("asset:///rain_or_shine.mp4"),
                dataSourceFactory, extractorsFactory, null, null);
        // Prepare the player with the source.
        player.prepare(videoSource);

        mExoPlayer = player;

        // Display a hint to check for errors in case the video doesn't render
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                ((TextView)findViewById(R.id.loadingindicator)).setText(R.string.loading_hint_error);
            }
        }, 10000);

        handler = new Handler();
        // Start the initial runnable task by posting through the handler
        handler.post(runnableCode);
    }

    private Runnable runnableCode = new Runnable() {
        @Override
        public void run() {
            // Do something here on the main thread
            float rotationMatrix[] = new float[16];
            immersiveEffect.getRotationMatrix(rotationMatrix);
            float quaternion[] = rotationMatrixToQuaternion(rotationMatrix);
            textView.setText(String.format("%.3f", quaternion[0]));
            gvrAudioProcessor.updateOrientation(quaternion[0], quaternion[1], quaternion[2], quaternion[3]);
            // Repeat this the same runnable code block again another 2 seconds
            // 'this' is referencing the Runnable object
            handler.postDelayed(this, 25);
        }
    };

        /**
         * Release the ExoPlayer instance.
         */
        private void releasePlayer() {
        if(mExoPlayer != null) {
            mExoPlayer.release();
            mExoPlayer = null;
        }
    }

    private static float sign(float x) {
        return (x >= 0.0f) ? +1.0f : -1.0f;
    }

    private static float norm(float a, float b, float c, float d) {
        return (float) Math.sqrt(a * a + b * b + c * c + d * d);
    }

    private static float[] rotationMatrixToQuaternion(float[] rotationMatrix) {
        float r11 = rotationMatrix[0];
        float r12 = rotationMatrix[1];
        float r13 = rotationMatrix[2];
        float r21 = rotationMatrix[4];
        float r22 = rotationMatrix[5];
        float r23 = rotationMatrix[6];
        float r31 = rotationMatrix[8];
        float r32 = rotationMatrix[9];
        float r33 = rotationMatrix[10];

        float q0 = ( r11 + r22 + r33 + 1.0f) / 4.0f;
        float q1 = ( r11 - r22 - r33 + 1.0f) / 4.0f;
        float q2 = (-r11 + r22 - r33 + 1.0f) / 4.0f;
        float q3 = (-r11 - r22 + r33 + 1.0f) / 4.0f;

        if(q0 < 0.0f) q0 = 0.0f;
        if(q1 < 0.0f) q1 = 0.0f;
        if(q2 < 0.0f) q2 = 0.0f;
        if(q3 < 0.0f) q3 = 0.0f;

        q0 = (float) Math.sqrt(q0);
        q1 = (float)Math.sqrt(q1);
        q2 = (float)Math.sqrt(q2);
        q3 = (float)Math.sqrt(q3);

        if(q0 >= q1 && q0 >= q2 && q0 >= q3) {
            q0 *= +1.0f;
            q1 *= sign(r32 - r23);
            q2 *= sign(r13 - r31);
            q3 *= sign(r21 - r12);
        } else if(q1 >= q0 && q1 >= q2 && q1 >= q3) {
            q0 *= sign(r32 - r23);
            q1 *= +1.0f;
            q2 *= sign(r21 + r12);
            q3 *= sign(r13 + r31);
        } else if(q2 >= q0 && q2 >= q1 && q2 >= q3) {
            q0 *= sign(r13 - r31);
            q1 *= sign(r21 + r12);
            q2 *= +1.0f;
            q3 *= sign(r32 + r23);
        } else if(q3 >= q0 && q3 >= q1 && q3 >= q2) {
            q0 *= sign(r21 - r12);
            q1 *= sign(r31 + r13);
            q2 *= sign(r32 + r23);
            q3 *= +1.0f;
        } else {
            System.out.println("coding error\n");
        }
        float r = norm(q0, q1, q2, q3);
        q0 /= r;
        q1 /= r;
        q2 /= r;
        q3 /= r;

        float q[] = {q0, q1, q2, q3};
        return q;
    }

    private class VideoSource {

        private String url;
        private ImmersiveEffect.Mode immersiveMode;

        public VideoSource(String url, ImmersiveEffect.Mode immersiveMode) {
            this.url = url;
            this.immersiveMode = immersiveMode;
        }
    }
}
