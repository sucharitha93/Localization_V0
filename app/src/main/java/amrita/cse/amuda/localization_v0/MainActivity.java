package amrita.cse.amuda.localization_v0;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.QueueingConsumer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

public class MainActivity extends AppCompatActivity implements SensorEventListener{
    private SensorManager mSensorManager;
    private Sensor accSensor;
    private Sensor magSensor;
    private Sensor gyroSensor;

    private WifiManager wifiManager;
    WiFiScanReceiver wifiReceiver;
    private TextView textAcc,textGyr,textMag;
    private EditText xCoordinate, yCoordinate;
    private Button button;
    private int i;

    ConnectionFactory factory = new ConnectionFactory();
    private BlockingDeque queue = new LinkedBlockingDeque();
    Thread subscribeThread;
    Thread publishThread;

    private float mAccel=1;
    private float timeStamp;
    private static final float NS2S = 1.0f / 1000000000.0f;
    private float d;
    float[] distance = new float[3];
    float[] velocity = new float[3];

    int[] rssiList = new int[20];
    int[] cnt = new int[20];
    File fileWifi;
    String[] macList = {
            "44:31:92:AF:A4:B0","44:31:92:B0:16:D0",
            "44:31:92:B0:10:90","44:31:92:9A:44:D0",
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        button = (Button)findViewById(R.id.button);

        textAcc = (TextView)findViewById(R.id.textAcc);
        textGyr = (TextView)findViewById(R.id.textGyr);
        textMag = (TextView)findViewById(R.id.textMag);

        xCoordinate = (EditText)findViewById(R.id.xCoordinate);
        yCoordinate = (EditText) findViewById(R.id.yCoordinate);

        setupConnectionFactory();
        publishToAMQP();

        final Handler incomingMessageHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                String message = msg.getData().getString("msg");
                //TextView tv = (TextView) findViewById(R.id.textView);
                Date now = new Date();
                SimpleDateFormat ft = new SimpleDateFormat ("hh:mm:ss");
                //tv.append(ft.format(now) + ' ' + message + '\n');
            }
        };
        subscribe(incomingMessageHandler);


        //Async task to get the application permissions to location and storage write
        new RequestPermissions().execute("Manifest.permission.ACCESS_COARSE_LOCATION","Manifest.permission.WRITE_EXTERNAL_STORAGE");

        //Check if location is turned on and notify the user to turn on location
        LocationManager lm = (LocationManager)getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
        boolean gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        if(!gpsEnabled)
        {
            Toast.makeText(this,"Please TURN ON location service to get data!",Toast.LENGTH_SHORT).show();
        }

        fileWifi = new File(Environment.getExternalStorageDirectory()+"//WiFiLog.csv");
        try{
            if (!fileWifi.exists()) {
                fileWifi.createNewFile();
            }
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        i=0;

        //define the sensor manager and the corresponding sensor variables
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        magSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        gyroSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        //register the sensors
        mSensorManager.registerListener( this,accSensor,mSensorManager.SENSOR_DELAY_NORMAL);
        mSensorManager.registerListener( this,magSensor,mSensorManager.SENSOR_DELAY_NORMAL);
        mSensorManager.registerListener( this,gyroSensor,mSensorManager.SENSOR_DELAY_NORMAL);

        //wifi service enabling
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiReceiver = new WiFiScanReceiver();

    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        publishThread.interrupt();
        subscribeThread.interrupt();
    }

    public void scan(){

        //.setText("");
        IntentFilter filterScanResult = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        IntentFilter filterRSSIChange = new IntentFilter(WifiManager.RSSI_CHANGED_ACTION);
        IntentFilter filterChange = new IntentFilter(WifiManager.ACTION_REQUEST_SCAN_ALWAYS_AVAILABLE);
        if(!wifiManager.isWifiEnabled())
        {
            Toast.makeText(this,"Wifi Turned On",Toast.LENGTH_SHORT).show();
            wifiManager.setWifiEnabled(true);
        }
        Toast.makeText(this,"Wifi Scan started",Toast.LENGTH_SHORT).show();
        this.registerReceiver(wifiReceiver, filterScanResult);
        this.registerReceiver(wifiReceiver, filterRSSIChange);
        this.registerReceiver(wifiReceiver, filterChange);
        wifiManager.startScan();

        Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {

                try {
                    unregisterReceiver(wifiReceiver);
                    button.setBackgroundColor(Color.GREEN);
                    String msg="";
                    FileWriter fwWifi = new FileWriter(fileWifi,true);
                    BufferedWriter bwWifi = new BufferedWriter(fwWifi);

                    for(int i = 0;i<4;i++){
                        if(cnt[i] != 0)
                        {
                            bwWifi.append(String.valueOf(rssiList[i] / cnt[i])+",");
                            msg = msg + String.valueOf(rssiList[i] / cnt[i])+",";
                        }
                        else
                        {
                            bwWifi.append(String.valueOf(0)+",");
                            msg = msg + String.valueOf(0)+";";
                        }
                    }
                    try {
                        queue.putLast(msg);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    //

                    WifiInfo info = wifiManager.getConnectionInfo();
                    String macaddress = info.getMacAddress();
                    bwWifi.append("\n");
                    bwWifi.close();
                    Location l = Trilateration.trilaterate(Float.parseFloat(xCoordinate.getText().toString()),
                            Float.parseFloat(yCoordinate.getText().toString()),
                            (float)Math.pow(10,(rssiList[0]+38.667)/-16),
                            (float)Math.pow(10,(rssiList[2]+38.667)/-16),
                            (float)Math.pow(10,(rssiList[4]+38.667)/-16),
                            (float)Math.pow(10,(rssiList[6]+38.667)/-16));
//                            (float)Math.pow(10,(rssiList[8]+38.667)/-16),
//                            (float)Math.pow(10,(rssiList[10]+38.667)/-16),
//                            (float)Math.pow(10,(rssiList[12]+38.667)/-16),
//                            (float)Math.pow(10,(rssiList[14]+38.667)/-16));
//                    Location l = Trilateration.trilaterate((float)110.0,(float)0.0,(float)21.5443469,(float)2.782559402,(float)146.7799268,(float)113.6463666);

                    String body = macaddress+","+l.getActualX()+","+l.getActualY()+","+l.getExperimentalX()+","+l.getExperimentalY()+","+msg;
                    Toast.makeText(MainActivity.this,body,Toast.LENGTH_SHORT).show();
                    //String body =macaddress+","+xCoordinate.getText().toString()+","+yCoordinate.getText().toString()+","+6567+","+4567+","+msg;

                    try {
                        queue.putLast(body);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    Arrays.fill(rssiList,new Integer(0));
                    Arrays.fill(cnt,new Integer(0));
                    Toast.makeText(MainActivity.this,"20 seconds scan results done",Toast.LENGTH_SHORT).show();
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }

        }, 20000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        //mSensorManager.unregisterListener(this);
    }

    protected void onClickButton(View v){
        new WifiAsyncTask().doInBackground(1);

        plotOnMap(10,10);

        button.setBackgroundColor(Color.LTGRAY);
        String x,y;
        x= xCoordinate.getText().toString();
        y = yCoordinate.getText().toString();

        //check if the co-ordinate points are entered

    }

    private void plotOnMap(float x, float y){
        i = i + 1;
        BitmapFactory.Options myOptions = new BitmapFactory.Options();
        myOptions.inDither = true;
        myOptions.inScaled = false;
        myOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;// important
        myOptions.inPurgeable = true;

        Bitmap bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.testbed,myOptions);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setColor(Color.BLUE);

        Bitmap workingBitmap = Bitmap.createBitmap(bitmap);
        Bitmap mutableBitmap = workingBitmap.copy(Bitmap.Config.ARGB_8888, true);

        Canvas canvas = new Canvas(mutableBitmap);
        canvas.drawCircle(x+(i*10), y+(i*10), 15, paint);

        ImageView imageView = (ImageView)findViewById(R.id.imageView);
        imageView.setAdjustViewBounds(true);
        imageView.setImageBitmap(mutableBitmap);
    }

    @Override
    public void onSensorChanged(SensorEvent event){

        switch(event.sensor.getType()){

            case Sensor.TYPE_LINEAR_ACCELERATION:
                velocity[0] = 0;
                distance[0] = 0;
                velocity[1] = 0;
                distance[1] = 0;
                velocity[2] = 0;
                distance[2] = 0;
                float x,y,z;

                x = event.values[0];
                y = event.values[1];
                z = event.values[2];
                /*
                final float dT = (event.timestamp - timeStamp) * NS2S;
                float mAccelCurrent = (float)(Math.sqrt(x*x + y*y + z*z));
                mAccel = mAccel * 0.9f + mAccelCurrent * 0.1f;
                d = mAccel * dT*dT/2;
                */
                velocity[0] = velocity[0]+ x;
                distance[0] = distance[0] + velocity[0];
                velocity[1] = velocity[1]+ x;
                distance[1] = distance[1] + velocity[1];
                velocity[2] = velocity[2]+ x;
                distance[2] = distance[2] + velocity[2];


                textAcc.setText("Linear Acceleration: X = "+String.valueOf(x)+" Y = "+String.valueOf(y)+" Z = "+String.valueOf(z)
                        //+"\n"+String.valueOf(mAccelCurrent)+"\nAcceleration"+String.valueOf(mAccel)+"\nDistance"+String.valueOf(d));
                +"\nVelocity: "+velocity[0]+"\nDistance: "+distance[0]);
                break;

            case Sensor.TYPE_GYROSCOPE:
                textGyr.setText("GYROSCOPE: X = "+String.valueOf( event.values[0])+" Y = "+String.valueOf( event.values[1])+" Z = "+String.valueOf( event.values[2])+"\n");
                break;

            case Sensor.TYPE_MAGNETIC_FIELD:
                textMag.setText("MAGNETOMETER: X = "+String.valueOf( event.values[0])+" Y = "+String.valueOf( event.values[1])+" Z = "+String.valueOf( event.values[2])+"\n");
                break;

        }

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    //task to scan wifi data and run the localization
    private class WifiAsyncTask extends AsyncTask<Integer,Void,Integer>{

        @Override
        protected Integer doInBackground(Integer... params) {
            if (xCoordinate.equals(null) && yCoordinate.equals(null))
            {
                Toast.makeText(getApplication(),"Enter the co-ordinates to begin SCAN",Toast.LENGTH_SHORT).show();
            }
            else
            {
                scan();
            }
            plotOnMap(10,10);
            return null;
        }
    }
    //task to get the security permissions from the user
    private class RequestPermissions extends AsyncTask<String,Void,Void>{

        @Override
        protected Void doInBackground(String... permission) {
            //loop through the list of permission strings and request the permissions from the user
            for(int i=0;i< permission.length;i++)
            {
                //check if the permission is granted, if no ask for the corresponding permission
                if(ContextCompat.checkSelfPermission(MainActivity.this, permission[i]) != PackageManager.PERMISSION_GRANTED)
                {
                    //method to request permission during runtime
                    ActivityCompat.requestPermissions(MainActivity.this, new String[]{permission[i]}, 1);
                }
            }
            return null;
        }
    }

    class WiFiScanReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(action) || WifiManager.RSSI_CHANGED_ACTION.equals(action)|| WifiManager.ACTION_REQUEST_SCAN_ALWAYS_AVAILABLE.equals(action))
            {
                List<ScanResult> wifiScanResultList = wifiManager.getScanResults();
                System.out.print(wifiScanResultList.toString());

                for(int i = 0; i < wifiScanResultList.size(); i++){
                    ScanResult accessPoint = wifiScanResultList.get(i);
                    String listItem = "SSID: "+accessPoint.SSID + "\n" + "MAC Address: "+accessPoint.BSSID + "\n" + "RSSI Signal Level"+accessPoint.level;
                                        // textView.append(listItem + "\n\n");
                }
                //textView.append("***********************************\n");
            }
        }
    }


    private void setupConnectionFactory() {
        String uri = "amqp://amuda:amuda2017@172.17.9.61:5672/%2f";
        try {
            factory.setAutomaticRecoveryEnabled(false);
            factory.setUsername("amuda");
            factory.setPassword("amuda2017");
            factory.setHost("172.17.9.61");
            factory.setPort(5672);
            factory.setVirtualHost("amudavhost");
        } catch (Exception e1) {
            e1.printStackTrace();
        }
    }

    public void publishToAMQP(){
        Log.i("","Reached publish to AMQP");
        publishThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while(true) {
                    try {
                        Connection connection = factory.newConnection();
                        Channel ch = connection.createChannel();
                        //ch.queueDeclare("PQ", false, false, false, null);

                        //ch.queueDeclare("a",true,true,false,null);
                        ch.confirmSelect();
                        Log.i("","Reached publish to AMQP");

                        while(true) {

                            String message = queue.takeFirst().toString();
                            Log.i("",message+" is the message to be published");
                            try{
                                //ch.basicPublish("", "PQ", null, message.getBytes());
                                ch.basicPublish("amq.fanout","severity" , null, message.getBytes());
                                ch.waitForConfirmsOrDie();
                            } catch (Exception e){
                                queue.putFirst(message);
                                throw e;
                            }
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        Log.d("", "Connection broken: " + e.getClass().getName());
                        try {
                            Thread.sleep(1000); //sleep and then try again
                        } catch (InterruptedException e1) {
                            break;
                        }
                    }
                }
            }
        });
        publishThread.start();
    }

    void subscribe(final Handler handler){
        subscribeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while(true) {
                    try {
                        Connection connection = factory.newConnection();
                        Channel channel = connection.createChannel();
                        channel.basicQos(1);
                        AMQP.Queue.DeclareOk q = channel.queueDeclare();
                        //channel.queueDeclare("a",true,true,false,null);
                        channel.queueBind(q.getQueue(), "amq.fanout", "severity");
                        QueueingConsumer consumer = new QueueingConsumer(channel);
                        channel.basicConsume(q.getQueue(), true, consumer);

                        while (true) {
                            QueueingConsumer.Delivery delivery = consumer.nextDelivery();
                            String message = new String(delivery.getBody());
                            Message msg = handler.obtainMessage();
                            Bundle bundle = new Bundle();
                            bundle.putString("msg", message);
                            msg.setData(bundle);
                            handler.sendMessage(msg);
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e1) {
                        Log.d("", "Connection broken: " + e1.getClass().getName());
                        try {

                            Thread.sleep(1000); //sleep and then try again
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }
            }
        });
        subscribeThread.start();
    }

}
