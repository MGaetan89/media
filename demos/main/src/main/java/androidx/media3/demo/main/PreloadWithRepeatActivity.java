package androidx.media3.demo.main;

import android.os.Build;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.ui.PlayerView;

public class PreloadWithRepeatActivity extends AppCompatActivity {

  private Player player;
  private PlayerView playerView;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(R.layout.preload_repeat_activity);

    playerView = findViewById(R.id.player);
  }

  @Override
  protected void onStart() {
    super.onStart();

    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
      setupPlayer();
    }
  }

  @Override
  protected void onResume() {
    super.onResume();

    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
      setupPlayer();
    }
  }

  @Override
  protected void onPause() {
    super.onPause();

    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
      releasePlayer();
    }
  }

  @Override
  protected void onStop() {
    super.onStop();

    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
      releasePlayer();
    }
  }

  private void setupPlayer() {
    // media.exolist.json > Clear DASH > HD (MP4, H264)
    player = createPlayer("https://storage.googleapis.com/wvmedia/clear/h264/tears/tears.mpd");
    playerView.setPlayer(player);
    playerView.onResume();
  }

  private Player createPlayer(String mediaUrl) {
    MediaItem mediaItem = new MediaItem.Builder().setUri(mediaUrl).build();

    ExoPlayer player = new ExoPlayer.Builder(this).build();
    player.setPreloadConfiguration(new ExoPlayer.PreloadConfiguration(5_000_000L));
    player.addAnalyticsListener(new EventLogger());
    player.setRepeatMode(Player.REPEAT_MODE_ONE); // Or Player.REPEAT_MODE_ALL
    player.setMediaItem(mediaItem);
    player.prepare();
    player.play();

    return player;
  }

  private void releasePlayer() {
    playerView.onPause();
    player.release();
  }
}
