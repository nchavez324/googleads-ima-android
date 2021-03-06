package com.google.ads.interactivemedia.v3.samples.audiodemo;

import static com.google.ads.interactivemedia.v3.samples.audiodemo.Constants.MEDIA_SESSION_TAG;
import static com.google.ads.interactivemedia.v3.samples.audiodemo.Constants.PLAYBACK_CHANNEL_ID;
import static com.google.ads.interactivemedia.v3.samples.audiodemo.Constants.PLAYBACK_NOTIFICATION_ID;
import static com.google.ads.interactivemedia.v3.samples.audiodemo.Samples.SAMPLES;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.IBinder;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.session.MediaSessionCompat;
import androidx.annotation.Nullable;
import com.google.ads.interactivemedia.v3.api.AdDisplayContainer;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.analytics.AnalyticsListener;
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector;
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator;
import com.google.android.exoplayer2.source.ConcatenatingMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.ShuffleOrder;
import com.google.android.exoplayer2.ui.PlayerNotificationManager;
import com.google.android.exoplayer2.ui.PlayerNotificationManager.BitmapCallback;
import com.google.android.exoplayer2.ui.PlayerNotificationManager.MediaDescriptionAdapter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;

/**
 * Allows audio playback with hooks for advertisements. This is meant to run as a Foreground Service
 * to enable playback to continue even if the app is minimized or cleaned up.
 */
public class AudioPlayerService extends Service {

  private boolean isAdPlaying;
  private SimpleExoPlayer player;
  private MediaDescriptionAdapter descriptionAdapter;
  private PlayerNotificationManager playerNotificationManager;
  private MediaSessionCompat mediaSession;
  private MediaSessionConnector mediaSessionConnector;
  private ImaService imaService;
  private DefaultDataSourceFactory dataSourceFactory;
  private ConcatenatingMediaSource contentMediaSource;

  @Override
  public void onCreate() {
    super.onCreate();
    final Context context = this;
    isAdPlaying = false;

    // new method in next version of exoplayer
    // player = new SimpleExoPlayer.Builder(context).build();

    player = ExoPlayerFactory.newSimpleInstance(context);

    dataSourceFactory =
        new DefaultDataSourceFactory(
            context, Util.getUserAgent(context, getString(R.string.application_name)));
    contentMediaSource =
        new ConcatenatingMediaSource(
            /* isAtomic= */ false,
            /* useLazyPreparation= */ true,
            new ShuffleOrder.DefaultShuffleOrder(/* length= */ 0));
    for (Samples.Sample sample : SAMPLES) {
      MediaSource mediaSource =
          new ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(sample.uri);
      contentMediaSource.addMediaSource(mediaSource);
    }
    player.prepare(contentMediaSource);
    player.setPlayWhenReady(true);

    descriptionAdapter =
        new MediaDescriptionAdapter() {
          @Override
          public String getCurrentContentTitle(Player player) {
            if (isAdPlaying) {
              return getString(R.string.ad_content_title);
            }
            return SAMPLES[player.getCurrentWindowIndex()].title;
          }

          @Nullable
          @Override
          public PendingIntent createCurrentContentIntent(Player player) {
            return null;
          }

          @Nullable
          @Override
          public String getCurrentContentText(Player player) {
            if (isAdPlaying) {
              // Null will remove the extra line for description.
              return null;
            }
            return SAMPLES[player.getCurrentWindowIndex()].description;
          }

          @Nullable
          @Override
          public Bitmap getCurrentLargeIcon(Player player, BitmapCallback callback) {
            // Use null for ad playback unless your ad has an icon to show in the notification
            // menu.
            if (isAdPlaying) {
              return null;
            }
            return Samples.getBitmap(
                context, SAMPLES[player.getCurrentWindowIndex()].bitmapResource);
          }
        };

    playerNotificationManager =
        PlayerNotificationManager.createWithNotificationChannel(
            context,
            PLAYBACK_CHANNEL_ID,
            R.string.playback_channel_name,
            PLAYBACK_NOTIFICATION_ID,
            descriptionAdapter);

    playerNotificationManager.setPlayer(player);

    mediaSession = new MediaSessionCompat(context, MEDIA_SESSION_TAG);
    mediaSession.setActive(true);
    playerNotificationManager.setMediaSessionToken(mediaSession.getSessionToken());

    mediaSessionConnector = new MediaSessionConnector(mediaSession);
    mediaSessionConnector.setQueueNavigator(
        new TimelineQueueNavigator(mediaSession) {
          @Override
          public MediaDescriptionCompat getMediaDescription(Player player, int windowIndex) {
            if (isAdPlaying) {
              return new MediaDescriptionCompat.Builder()
                  .setDescription(getString(R.string.ad_content_title))
                  .build();
            }
            return Samples.getMediaDescription(context, SAMPLES[windowIndex]);
          }
        });
    mediaSessionConnector.setPlayer(player);

    imaService = new ImaService(context, dataSourceFactory, new SharedAudioPlayer());
  }

  @Override
  public void onDestroy() {
    mediaSession.release();
    mediaSessionConnector.setPlayer(null);
    playerNotificationManager.setPlayer(null);
    player.release();
    player = null;

    super.onDestroy();
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return new AudioPlayerServiceBinder();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    return START_STICKY;
  }

  /**
   * A limited API for the ImaService which provides a minimal surface of control over playback on
   * the shared SimpleExoPlayer instance.
   */
  class SharedAudioPlayer {
    public void claim() {
      isAdPlaying = true;
      player.setPlayWhenReady(false);
    }

    public void release() {
      if (isAdPlaying) {
        isAdPlaying = false;
        player.prepare(contentMediaSource);
        player.setPlayWhenReady(true);
      }
    }

    public void prepare(MediaSource mediaSource) {
      player.prepare(mediaSource);
    }

    public void addAnalyticsListener(AnalyticsListener listener) {
      player.addAnalyticsListener(listener);
    }

    public Player getPlayer() {
      return player;
    }
  }

  /** Provide a Binder to the Application allowing control of the Audio Service */
  public class AudioPlayerServiceBinder extends Binder {
    public void updateSong(int index) {
      if (isAdPlaying) {
        // Return here to prevent changing the song while an ad is playing. A publisher could
        // instead choose queue up the change for after the ad is completed, or cancel the ad.
        return;
      }
      if (player.getCurrentTimeline().getWindowCount() < index) {
        player.seekTo(index, C.TIME_UNSET);
      }
    }

    public void initializeAds(AdDisplayContainer adc) {
      imaService.init(adc);
    }

    public void requestAd(String adTagUrl) {
      imaService.requestAds(adTagUrl);
    }
  }
}
