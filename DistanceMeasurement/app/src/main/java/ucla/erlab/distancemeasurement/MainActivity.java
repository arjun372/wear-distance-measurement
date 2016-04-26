package ucla.erlab.distancemeasurement;

import android.content.Context;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorEventListener2;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.Vibrator;
import android.support.v4.content.LocalBroadcastManager;
import android.support.wearable.activity.WearableActivity;
import android.support.wearable.view.BoxInsetLayout;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends WearableActivity implements SensorEventListener {



    private static final long msTimeOfBoot = System.currentTimeMillis() - SystemClock.elapsedRealtime();

    private static SensorManager mSensorManager = null;

    private static final String READ_RESULT_URL = "http://131.179.80.155/read.php";
    private static final String POST_DATA_URL = "http://131.179.80.155/upload.php";

    private static final long SAMPLING_PERIOD = 1 * 60 * 1000; // Sample every 2 mins
    private static final long POLLING_PERIOD  = 1 * 10 * 1000; // Poll every 10 seconds

    private static final int ACCEL_SAMPLE_RATE = 1000000/10;   // 10 Hz
    private static final int GYRO_SAMPLE_RATE  = 1000000/10;   // 10 Hz

    private static float[] gyroD_buffer = new float[3];
    private static long gyroT_buffer    = 0;

    private static String velocity = "-1";
    private static String distance = "-1";

    private static boolean POLLING = false, SENDING = false;


    private static Handler mHandler = null;
    private static void startHandlerThread()
    {
        final HandlerThread mHandlerThread = new HandlerThread("PeriodicUpload");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
    }

    /* File writing code */
    private static final boolean APPEND_PREVIOUS_FILE = false;
    private static final String rootDir = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
    private static final File appDir = new File(rootDir+"/EbrahimApp");
    private static final File accel_file = new File(appDir, "test.accel.csv");
    private static final File gyro_file  = new File(appDir, "test.gyro.csv");
    private static BufferedWriter mAccelBuffer = null;
    private static BufferedWriter mGyroBuffer  = null;

    private BoxInsetLayout mContainerView;
    private TextView mVelocity;
    private TextView mDistance;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setAmbientEnabled();

        mContainerView = (BoxInsetLayout) findViewById(R.id.container);
        mDistance = (TextView) findViewById(R.id.distance);
        mVelocity = (TextView) findViewById(R.id.velocity);

        startHandlerThread();
    }

    @Override
    public void onEnterAmbient(Bundle ambientDetails) {
        super.onEnterAmbient(ambientDetails);
        updateDisplay();
    }

    @Override
    public void onUpdateAmbient() {
        super.onUpdateAmbient();
        updateDisplay();
    }

    @Override
    public void onExitAmbient() {
        updateDisplay();
        super.onExitAmbient();
    }

    private void updateDisplay() {
        mVelocity.setText(velocity);
        mDistance.setText(distance);
    }

    @Override
    public void onResume() {
        resumeScan();
        mHandler.post(getResult);
        mHandler.postDelayed(sendFiles, SAMPLING_PERIOD);

        updateDisplay();
        super.onResume();
    }

    @Override
    public void onPause() {

        mHandler.removeCallbacks(getResult);
        mHandler.removeCallbacks(sendFiles);
        pauseScan();

        super.onPause();
    }

    /* Stop scanning, flush all data to files, then close the file-writer */
    private boolean pauseScan(){
        Log.d("onPause", "..");

        if(mSensorManager == null)  mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        mSensorManager.unregisterListener(this);
        mSensorManager.flush(this);

        try {
            mAccelBuffer.flush();
            mGyroBuffer.flush();
            mAccelBuffer.close();
            mGyroBuffer.close();
            return true;
        } catch (Exception e) {return false;}

    }

    /* Write new files, and start writing data into them */
    private void resumeScan() {
        Log.d("" + "onResume", "..");

        try {
            appDir.mkdirs();
            if(accel_file.exists()) accel_file.delete();
            if(gyro_file.exists())  gyro_file.delete();
            mAccelBuffer = new BufferedWriter(new FileWriter(accel_file, APPEND_PREVIOUS_FILE));
            mGyroBuffer  = new BufferedWriter(new FileWriter(gyro_file, APPEND_PREVIOUS_FILE));
        } catch (Exception e) {e.printStackTrace();}

        if(mSensorManager == null)  mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        final Sensor mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        final Sensor mGyroscope     = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        mSensorManager.registerListener(this, mAccelerometer, ACCEL_SAMPLE_RATE, 0);
        mSensorManager.registerListener(this, mGyroscope, GYRO_SAMPLE_RATE, 0);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        final long absolute_timestamp = msTimeOfBoot + (event.timestamp/1000000);
        switch (event.sensor.getType()) {
            case (Sensor.TYPE_GYROSCOPE):
                gyroT_buffer = absolute_timestamp;
                gyroD_buffer[0] = event.values[0];
                gyroD_buffer[1] = event.values[1];
                gyroD_buffer[2] = event.values[2];
                break;

            case (Sensor.TYPE_ACCELEROMETER):

                try {
                    mAccelBuffer.write(absolute_timestamp + "," +
                                       event.values[0]    + "," +
                                       event.values[1]    + "," +
                                       event.values[2]    + "\n");

                    mGyroBuffer.write(gyroT_buffer    + "," +
                                      gyroD_buffer[0] + "," +
                                      gyroD_buffer[1] + "," +
                                      gyroD_buffer[2] + "\n");

                }catch (Exception e) {}
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    private Runnable getResult = new Runnable() {
        @Override
        public void run() {

            Log.d("getResult", "Begin getting");

            if(SENDING) {
                mHandler.postDelayed(getResult, POLLING_PERIOD);
                Log.d("getResult", "oops sending data");
                return;
            }

            POLLING = true;

            StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
            StrictMode.setThreadPolicy(policy);

            String dataString = "";
            HttpURLConnection urlConnection = null;
            try {
                final URL mURL = new URL (READ_RESULT_URL);
                urlConnection = (HttpURLConnection) mURL.openConnection();
                InputStream in = urlConnection.getInputStream();
                InputStreamReader isw = new InputStreamReader(in);

                int data = isw.read();
                while (data != -1) {
                    char current = (char) data;
                    data = isw.read();
                    dataString+=current;
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
            }

            mHandler.postDelayed(getResult, POLLING_PERIOD);


            Log.d("READ_",""+distance+","+velocity);

            Log.d("getResult", "Stop getting");

            POLLING = false;

            if(dataString.equals(distance) || dataString.equals(""))
                return;

            distance = dataString;
            velocity = String.format("%.2f", Double.valueOf(dataString)/(2*60))+" m/s";
            updateDisplay();

            Vibrator vibe = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            vibe.vibrate(new long[] {100, 200, 100, 200}, -1);


        }
    };

    private Runnable sendFiles = new Runnable() {
        @Override
        public void run() {

            if(POLLING) {
                mHandler.postDelayed(sendFiles, SAMPLING_PERIOD);
                return;
            }

            SENDING  = true;

            Log.d("sendFiles", "Begin Sending");

            pauseScan();

            StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
            StrictMode.setThreadPolicy(policy);

            String charset = "UTF-8";
            String requestURL = POST_DATA_URL;

            MultipartUtility multipart = null;
            try {
                multipart = new MultipartUtility(requestURL, charset);
                multipart.addFilePart("accel", accel_file);
                multipart.addFilePart("gyro", gyro_file);
                List<String> response = multipart.finish();
                for(String one : response)
                    Log.d("Reponses:",one);

            } catch (IOException e) { e.printStackTrace();}

            resumeScan();

            Log.d("sendFiles", "Stop Sending");

            mHandler.postDelayed(sendFiles, SAMPLING_PERIOD);

            SENDING = false;
        }
    };


}
