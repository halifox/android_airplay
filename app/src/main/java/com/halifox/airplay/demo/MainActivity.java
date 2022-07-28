package com.halifox.airplay.demo;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public final class MainActivity extends AppCompatActivity {
    private TextView statusText;
    private ImageView coverImage;
    private TextView titleText;
    private TextView artistText;
    private TextView albumText;
    private TextView positionText;
    private TextView durationText;
    private TextView formatText;
    private SeekBar progressBar;
    private Button startButton;
    private Button stopButton;

    private AirplayReceiverController receiverController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        coverImage = findViewById(R.id.cover_image);
        titleText = findViewById(R.id.title_text);
        artistText = findViewById(R.id.artist_text);
        albumText = findViewById(R.id.album_text);
        positionText = findViewById(R.id.position_text);
        durationText = findViewById(R.id.duration_text);
        formatText = findViewById(R.id.format_text);
        progressBar = findViewById(R.id.progress_bar);
        startButton = findViewById(R.id.start_button);
        stopButton = findViewById(R.id.stop_button);

        coverImage.setImageResource(R.drawable.ic_album_placeholder);
        startButton.setOnClickListener(view -> receiverController.start());
        stopButton.setOnClickListener(view -> receiverController.stop());

        receiverController = new AirplayReceiverController(
                this,
                new AirplayReceiverController.Listener() {
                    @Override
                    public void onStatusChanged(String message, boolean active, boolean error) {
                        statusText.setText(message);
                        startButton.setEnabled(!active || error);
                        stopButton.setEnabled(active);
                    }

                    @Override
                    public void onAudioFormatChanged(int bits, int channels, int sampleRate) {
                        formatText.setText(getString(
                                R.string.airplay_format,
                                bits,
                                channels,
                                sampleRate
                        ));
                    }

                    @Override
                    public void onMetadataChanged(
                            String title,
                            String artist,
                            String album
                    ) {
                        titleText.setText(title);
                        artistText.setText(artist);
                        albumText.setText(album);
                    }

                    @Override
                    public void onCoverArtChanged(Bitmap coverArt) {
                        if (coverArt == null) {
                            coverImage.setImageResource(R.drawable.ic_album_placeholder);
                        } else {
                            coverImage.setImageBitmap(coverArt);
                        }
                    }

                    @Override
                    public void onProgressChanged(long positionMs, long durationMs) {
                        positionText.setText(formatTime(positionMs));
                        if (durationMs > 0L) {
                            durationText.setText(formatTime(durationMs));
                            progressBar.setProgress(progressFor(positionMs, durationMs));
                        } else {
                            durationText.setText(R.string.airplay_no_duration);
                            progressBar.setProgress(0);
                        }
                    }
                }
        );
    }

    @Override
    protected void onDestroy() {
        if (receiverController != null) {
            receiverController.destroy();
        }
        super.onDestroy();
    }

    private static int progressFor(long positionMs, long durationMs) {
        if (durationMs <= 0L) {
            return 0;
        }
        long boundedPosition = Math.max(0L, Math.min(positionMs, durationMs));
        return (int) ((boundedPosition * 1000L) / durationMs);
    }

    private static String formatTime(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }
}
