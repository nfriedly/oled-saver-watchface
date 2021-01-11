/**
 * Copyright 2021 Incline Digital LLC
 */
package digital.incline.oledsaver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.view.SurfaceHolder;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face where the time moves to a new random location every minute.
 * The goal is to enabled for long periods of use without potential burn-in.
 */
public class OLEDSaver extends CanvasWatchFaceService {

    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<OLEDSaver.Engine> mWeakReference;

        public EngineHandler(OLEDSaver.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            OLEDSaver.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {

        private final Handler mUpdateTimeHandler = new EngineHandler(this);
        private Calendar mCalendar;
        private final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        private boolean mRegisteredTimeZoneReceiver = false;
        private Paint mTimePaint;
        private boolean mAmbient;
        private SimpleDateFormat timeFormat;
        private final Rect mTimeBounds = new Rect();
        private String mTime = "";
        private int x;
        private int y;
        private boolean isRound;
        // unicode circles: ∙ • ● ⚫ ⬤
        private final String mNotificationDot = "∙";
        private final Rect mNotificationBounds = new Rect();

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            Configuration config = new Configuration();
            config.setToDefaults();
            isRound = config.isScreenRound();

            setWatchFaceStyle(new WatchFaceStyle.Builder(OLEDSaver.this)
                    .setHideNotificationIndicator(true)
                    .build());

            mCalendar = Calendar.getInstance();
            timeFormat = new SimpleDateFormat("h:mm");

            mTimePaint = new Paint();
            mTimePaint.setColor(Color.WHITE);
            mTimePaint.setAntiAlias(true);
            mTimePaint.setStrokeCap(Paint.Cap.ROUND);
            mTimePaint.setTextSize(120f);

            mTimePaint.getTextBounds(mNotificationDot, 0, mNotificationDot.length(), mNotificationBounds);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            mAmbient = inAmbientMode;
            if (mAmbient) {
                mTimePaint.setShadowLayer(3f, 0f, 0f, Color.WHITE);
                mTimePaint.setColor(Color.BLACK);
                invalidate();
            } else {
                mTimePaint.setColor(Color.WHITE);
                mTimePaint.clearShadowLayer();
            }
            updateTimer();
        }

        public void calculateLocation(Rect bounds) {
            mTimePaint.getTextBounds(mTime, 0, mTime.length(), mTimeBounds);

            x = ThreadLocalRandom.current().nextInt(0, bounds.width() - mTimeBounds.width() + 1);
            y = ThreadLocalRandom.current().nextInt(0, bounds.height() - mTimeBounds.height() + 1) - mTimeBounds.top;

            if (isRound) {
                // round screens still use a square coordinate-system, but some of the pixels just aren't there.
                // a little bit of the number being off-screen is probably OK, but too much makes it hard to read.

                // determine the distance to the nearest two edges (top/bottom and left/right),
                // if it's less than some minimum value, calculate a new random position
                int xDistance = Math.min(x, bounds.width() - (x + mTimeBounds.width()));
                int yDistance = Math.min(y, bounds.height() - (y + mTimeBounds.height()));

                if (xDistance + yDistance < 20) { // todo: calculate the min value based on the screen's resolution
                    calculateLocation(bounds); // todo: set a max number of retries to avoid a potentially infinite loop here
                }
            }
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            // clear the screen
            canvas.drawColor(Color.BLACK);

            String time = timeFormat.format(mCalendar.getTime());

            // update the location only if the time has changed (so that it doesn't bounce around when going from ambient to awake)
            if(!time.equals(mTime)) {
                mTime = time;
                calculateLocation(bounds);
            }

            canvas.drawText(time, x, y, mTimePaint);

            // add a dot if there are unread notifications
            if (getUnreadCount() > 0) {
                int notificationX = x + (mTimeBounds.width() / 2) - (mNotificationBounds.width()/2);
                int notificationY = y + mTimeBounds.height() - mNotificationBounds.height();
                canvas.drawText(mNotificationDot, notificationX, notificationY, mTimePaint);
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                /* Update time zone in case it changed while we weren't visible. */
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
            }

            /* Check and trigger whether or not timer should be running (only in active mode). */
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            OLEDSaver.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            OLEDSaver.this.unregisterReceiver(mTimeZoneReceiver);
        }

        /**
         * Starts/stops the {@link #mUpdateTimeHandler} timer based on the state of the watch face.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer
         * should only run in active mode.
         */
        private boolean shouldTimerBeRunning() {
            return !mAmbient;
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}
