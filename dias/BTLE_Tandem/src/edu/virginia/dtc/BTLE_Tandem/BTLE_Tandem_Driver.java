package edu.virginia.dtc.BTLE_Tandem;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import edu.virginia.dtc.BTLE_Tandem.TandemFormats.Ack;
import edu.virginia.dtc.BTLE_Tandem.TandemFormats.BolusEndTime;
import edu.virginia.dtc.BTLE_Tandem.TandemFormats.BolusStatus;
import edu.virginia.dtc.BTLE_Tandem.TandemFormats.ConfirmRequest;
import edu.virginia.dtc.BTLE_Tandem.TandemFormats.EndConnection;
import edu.virginia.dtc.BTLE_Tandem.TandemFormats.GetFluidName;
import edu.virginia.dtc.BTLE_Tandem.TandemFormats.GetFluidRemaining;
import edu.virginia.dtc.BTLE_Tandem.TandemFormats.GetInternalTime;
import edu.virginia.dtc.BTLE_Tandem.TandemFormats.GetPumpStatus;
import edu.virginia.dtc.BTLE_Tandem.TandemFormats.GetTimeDate;
import edu.virginia.dtc.BTLE_Tandem.TandemFormats.Header;
import edu.virginia.dtc.BTLE_Tandem.TandemFormats.Nack;
import edu.virginia.dtc.BTLE_Tandem.TandemFormats.Packet;
import edu.virginia.dtc.BTLE_Tandem.TandemFormats.RequestBolus;
import edu.virginia.dtc.BTLE_Tandem.TandemFormats.RequestConfirm;
import edu.virginia.dtc.BTLE_Tandem.TandemFormats.SetFluidName;
import edu.virginia.dtc.BTLE_Tandem.TandemFormats.SetTimeDate;
import edu.virginia.dtc.SysMan.Biometrics;
import edu.virginia.dtc.SysMan.Debug;
import edu.virginia.dtc.SysMan.Event;
import edu.virginia.dtc.SysMan.Params;
import edu.virginia.dtc.SysMan.Pump;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.widget.Toast;

public class BTLE_Tandem_Driver extends Service{

	//GLOBAL STATIC VARIABLES
	//*******************************************************
	private static final String TAG = "BTLE_Tandem_Driver";
	
	private static final String Driver_Name = "BTLE_Tandem";
	public static final String UI_Intent = "Driver.UI.BTLE_Tandem";
	private static final String Pump_Intent = "Driver.Pump.BTLE_Tandem";
	
	private static final UUID CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
	
	// Parse constants
 	private static final int INVALID = -1;
 	private static final int GET_TYPE = 1;
 	private static final int GET_LENGTH = 2;
 	private static final int GET_CARGO = 3;
 	private static final int GET_TIMESTAMP = 4;
 	private static final int GET_CHECKSUM = 5;
 	private static final int COMPLETE = 6;
 	
 	private static double INFUSION_RATE = 0.285714;					//Rate of 1U/35s 
 	
 	//GLOBAL VARIABLES
 	//*******************************************************
 	private static ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
	private static ScheduledFuture<?> bolusRetry, bolusExpire, warningTimer, disconnectTimer, reconTimer;
 	
    private final Messenger messengerFromUI = new Messenger(new incomingUIHandler());
    private final Messenger messengerFromPumpService = new Messenger(new incomingPumpHandler());
    private Messenger messengerToPumpService = null;
    
    //Bolus/Pump Variables
    private boolean bolusing = false;
    private boolean confirming = false;
    private boolean setTime = false;
    private int bolusId, bolusState = INVALID;
    private long bolusInfusionTime;
    private String bolusAmount, bolusStatus;
    private float fluidRem;
    private Bundle queryData;
    private double totalDelivered, bolusDelivered;
    
    //BLE variables
	private BluetoothManager btleManager;
	private BluetoothAdapter btleAdapter;  
	public static BluetoothDevice dev;
	private Handler handler;
	private static BluetoothGattCharacteristic writeChar = null;
	
	public static String devMac;
	public static List<listDevice> devices;
	private static BluetoothGatt btleGatt;
	public static String description, btleDescription, fluid;
	
	private TandemFormats tandem;
	private Context me;
	
	public static SharedPreferences settings;
	
	private static final int TX_THREAD_SLEEP = 2000;
	private static final int RW_LATCH_TIMEOUT = 10;
	private static Queue<OutPacket> txMessages;
	private static volatile Thread transmit;
	private static boolean txRunning, txStop;
	private CountDownLatch rwLatch = new CountDownLatch(0); 
	
	//Parsing variables
	private int parseStatus;
	private int type = 0, index = 0, queryId = 0;
	private Header hdr = null;
	private ByteBuffer packet = null;
	private long start;

	private List<String> prefixes;
	
	private static final long SCAN_PERIOD = 2000;
	private static boolean scan = false;
	
	private int state = Pump.NONE;
	
	//-----------------------------------------------------------------------------------------
	//-----------------------------------------------------------------------------------------
	//	RUNNABLES
	//-----------------------------------------------------------------------------------------
	//-----------------------------------------------------------------------------------------
	
	private Runnable recon = new Runnable()
	{
		final String FUNC_TAG = "recon";
		
		public void run()
		{
			Debug.i(TAG, FUNC_TAG, "Reconnecting...");
			
			reconnectDevice();
		}
	};
	
	private Runnable warning = new Runnable()
	{
		public void run()
		{
			//Fire warning event
			Bundle b = new Bundle();
    		b.putString("description", "The pump has been disconnected for 15 minutes!  Please reconnect or move closer to the pump.");
			Event.addEvent(me, Event.EVENT_PUMP_DISCONNECT_WARN, Event.makeJsonString(b), Event.SET_POPUP_AUDIBLE);
		}
	};
	
	private Runnable disconnect = new Runnable()
	{
		public void run()
		{
			//Fire disconnect event
			Bundle b = new Bundle();
    		b.putString("description", "The pump has been disconnected for 20 minutes!  Disconnecting from system.");
			Event.addEvent(me, Event.EVENT_PUMP_DISCONNECT_TIMEOUT, Event.makeJsonString(b), Event.SET_POPUP_AUDIBLE);
			
			//Disconnect device
			saveDevice("", false);
			
			ContentValues dv = new ContentValues();
			Debug.e(TAG, "disconnect", "Removing running pump DB value!");
			dv.put("running_pump", "");					
			getContentResolver().update(Biometrics.HARDWARE_CONFIGURATION_URI, dv, null, null);
			
			setState(Pump.DISCONNECTED);
			
			if(btleGatt != null)
			{
				btleGatt.disconnect();
				btleGatt.close();
			}
			
			devMac = "";
			description = "N/A";
		}
	};
	
	public Runnable status = new Runnable()
	{
		final String FUNC_TAG = "status";
		public void run() 
		{
			Debug.i(TAG, FUNC_TAG, "Checking on bolus ID: "+bolusId);
			byte[] buffer = tandem.new BolusStatus().Build(bolusId);
			sendTandemData(BolusStatus.TYPE, buffer, BolusStatus.RESP_CNT);
		}
	};
	
	public Runnable expire = new Runnable()
	{
		final String FUNC_TAG = "expire";
		public void run()
		{
			if(bolusRetry != null)			//Cancel the bolus update timer
				bolusRetry.cancel(true);
			
			bolusing = false;
			Debug.i(TAG, FUNC_TAG, "Bolus ID: "+bolusId+" timeout!");
		}
	};
	
	//-----------------------------------------------------------------------------------------
	//-----------------------------------------------------------------------------------------
	//	SYSTEM FUNCTIONS
	//-----------------------------------------------------------------------------------------
	//-----------------------------------------------------------------------------------------
	
	@Override
	public void onCreate() {
		super.onCreate();
		
		final String FUNC_TAG = "onCreate";
		
		Debug.i(TAG, FUNC_TAG, "");
		
		settings = getSharedPreferences("BTLE_Tandem", 0);

		me = this;
		tandem = new TandemFormats();
		
		getAddressPrefixes();
		
		description = "N/A";
		fluid = "N/A";
		
		setState(Pump.NONE);
		
		int icon = R.drawable.ic_launcher;
		CharSequence tickerText = "";
		long when = System.currentTimeMillis();
		Notification notification = new Notification(icon, tickerText, when);
		Context context = getApplicationContext();
		CharSequence contentTitle = "Tandem Driver";
		CharSequence contentText = "BTLE Pump";
		Intent notificationIntent = new Intent(this, BTLE_Tandem_Driver.class);
		PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
		notification.setLatestEventInfo(context, contentTitle, contentText, contentIntent);
		final int DRVR_ID = 94;
		startForeground(DRVR_ID, notification);
		
		Intent pumpIntent = new Intent();
		pumpIntent.setClassName("edu.virginia.dtc.PumpService", "edu.virginia.dtc.PumpService.PumpService");
		pumpIntent.putExtra("driver_intent", BTLE_Tandem_Driver.Pump_Intent);
		pumpIntent.putExtra("driver_name", BTLE_Tandem_Driver.Driver_Name);
		pumpIntent.putExtra("PumpCommand", 9);
		startService(pumpIntent);
		
		devices = new ArrayList<listDevice>();
		btleManager = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
		btleAdapter = btleManager.getAdapter();
		
		startTransmitThread();
		
		if(btleAdapter == null || !btleAdapter.isEnabled())
		{
			Debug.i(TAG, FUNC_TAG, "BTLE is not enabled!");
		}
		
		handler = new Handler();
		
		restoreDevice();
		
		if(devMac != null && !devMac.equalsIgnoreCase(""))
		{
			Debug.i(TAG, FUNC_TAG, "Recovering device: "+devMac);
			
			//Set it to null so it will rediscover the characteristic
			writeChar = null;
			connect(btleAdapter.getRemoteDevice(devMac));
		}
		else
			devMac = "";
	}
	
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		final String FUNC_TAG = "onStartCommand";
		
		Debug.i(TAG, FUNC_TAG, "");
		
		if(!intent.getBooleanExtra("auto", false))
		{
			Intent uiIntent = new Intent();
			uiIntent.setClassName("edu.virginia.dtc.BTLE_Tandem", "edu.virginia.dtc.BTLE_Tandem.BTLE_Tandem_UI");
			uiIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			startActivity(uiIntent);
		}
		
		return 0;
	}
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		
		final String FUNC_TAG = "onDestroy";
		
		Debug.i(TAG, FUNC_TAG, "");
		
		txStop = true;
		//Close transmit thread
		if(transmit != null)
		{
			if(transmit.isAlive())
			{
				try {
					transmit.join();
					txRunning = false;
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	@Override
	public IBinder onBind(Intent intent) {
		if(intent.getAction().equalsIgnoreCase(BTLE_Tandem_Driver.UI_Intent))
			return messengerFromUI.getBinder();
		else if(intent.getAction().equalsIgnoreCase(BTLE_Tandem_Driver.Pump_Intent))
			return messengerFromPumpService.getBinder();
		else
			return null;
	}
	
	//-----------------------------------------------------------------------------------------
	//-----------------------------------------------------------------------------------------
	//	BLE CALLBACKS/FUNCTIONS
	//-----------------------------------------------------------------------------------------
	//-----------------------------------------------------------------------------------------
	
	public BluetoothAdapter.LeScanCallback callBack = new BluetoothAdapter.LeScanCallback() {
		final String FUNC_TAG = "callBack";
		
		public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) 
		{	
			Debug.i(TAG, FUNC_TAG, "Device found: "+device.getName() + " " + device.getAddress());

			String sub = device.getAddress().substring(0, 8);
			
			if(!prefixes.contains(sub))
			{
				Debug.i(TAG, FUNC_TAG, "Device is not Tandem!  Ignoring...");
				return;
			}
			
			boolean exists = false;
			for(listDevice d:devices)
			{
				if(d.address.equalsIgnoreCase(device.getAddress()))
				{
					Debug.i(TAG, FUNC_TAG, "Device already found in the list...");
					exists = true;
				}
			}
			if(!exists)
			{
				Debug.i(TAG, FUNC_TAG, "Adding device...");
				devices.add(new listDevice(device.getAddress(), device));
			}
		}
	};
	
	private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
		final String FUNC_TAG = "gattCallback";
		
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) 
        {
        	Debug.i(TAG, FUNC_TAG, "Connection state change: "+newState+" Status: "+checkStatus(status));
        	Debug.i(TAG, FUNC_TAG, "Device is: "+gatt.getDevice().getAddress());
        	
            switch(newState)
            {
	            case BluetoothProfile.STATE_CONNECTED: 
	            	if(status != BluetoothGatt.GATT_SUCCESS) {
		            	reconnect();
	            		return;
	            	}
	            	
	            	setState(Pump.CONNECTED);
	            	
	            	Debug.i(TAG, FUNC_TAG, "Connected to GATT server!");
	                devMac = gatt.getDevice().getAddress();
	                
	                saveDevice(devMac, true);
	                
	                ContentValues dv = new ContentValues();
					Debug.i(TAG, FUNC_TAG, "Adding running pump DB value!");
					dv.put("running_pump", "edu.virginia.dtc.BTLE_Tandem.BTLE_Tandem_Driver");					
					getContentResolver().update(Biometrics.HARDWARE_CONFIGURATION_URI, dv, null, null);
	                
	                cancelWarnings();
	                cancelReconnect();
	                
	                btleGatt.discoverServices();
	            	break;
	            case BluetoothProfile.STATE_CONNECTING: 
	            	break;
	            case BluetoothProfile.STATE_DISCONNECTED: 
	            	setState(Pump.CONNECTING);
	                
	                reconnect();
	            	break;
	            case BluetoothProfile.STATE_DISCONNECTING: 
	            	break;
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) 
        {
        	Debug.i(TAG, FUNC_TAG, "onServicesDiscovered received: " + checkStatus(status));
        	
            if (status == BluetoothGatt.GATT_SUCCESS) {
            	List<BluetoothGattService> services = gatt.getServices();
            	for(BluetoothGattService s:services) {
            		Debug.i(TAG, FUNC_TAG, "Service >>> "+s.getUuid().toString());
            		for(BluetoothGattCharacteristic c:s.getCharacteristics()) {
            			Debug.i(TAG, FUNC_TAG, 	"	Char >>> "+c.getUuid().toString()+
            									"		Perm: "+BTLE_Info.charPerm(c.getPermissions())+":"+c.getPermissions()+
            									"		Prop: "+BTLE_Info.charProp(c.getProperties())+":"+c.getProperties());
            			
//            			for(BluetoothGattDescriptor d:c.getDescriptors())
//            				Debug.i(TAG, FUNC_TAG, "			Desc: "+d.toString());
            			
            			String sub = c.getUuid().toString().substring(0, 8);
            			if(sub.equalsIgnoreCase("0000fff3")) {
            				Debug.i(TAG, FUNC_TAG, "Write characteristic found!");
            				writeChar = c;
            			}
            			else if(sub.equalsIgnoreCase("0000fff4")) {
            				Debug.i(TAG, FUNC_TAG, "Found read characteristic, setting notifications!");
            				btleGatt.setCharacteristicNotification(c, true);
            	            
            	            BluetoothGattDescriptor descriptor = c.getDescriptor(CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID);
            	            boolean success1 = descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            	            boolean success = btleGatt.writeDescriptor(descriptor);
            	            Debug.i(TAG, FUNC_TAG, "Set Notifications: "+success1+" "+success);
            			}
            		}
            	}
            	
            	setTime = true;
				Calendar c = Calendar.getInstance();
				c.setTimeZone(TimeZone.getDefault());
				
				final byte[] buffer = tandem.new SetTimeDate().Build(c.get(Calendar.YEAR), c.get(Calendar.MONTH)+1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND));
				
				handler.postDelayed(new Runnable()
                {
                	public void run()
                	{
                		Debug.i(TAG, FUNC_TAG, "Setting time...");
                		sendTandemData(SetTimeDate.TYPE, buffer, SetTimeDate.RESP_CNT);
                	}
                }, 3000);
            } 
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic)
        {
        	rwLatch.countDown();
        	Debug.i(TAG, FUNC_TAG, "onCharacteristicChanged received: "+characteristic.getValue().length+" bytes!");
        	Debug.i(TAG, FUNC_TAG, "RW Latch counted down...");
        	parseTandemPacket(characteristic.getValue());
        }
    };
    
    //-----------------------------------------------------------------------------------------
    //-----------------------------------------------------------------------------------------
  	//	BLE HELPER FUNCTIONS
  	//-----------------------------------------------------------------------------------------
    //-----------------------------------------------------------------------------------------
    
    private void reconnect()
    {
    	final String FUNC_TAG = "reconnect";
    	
    	if(state == Pump.RECONNECTING) {
    		Debug.w(TAG, FUNC_TAG, "Already attempting reconnect, ignoring this call");
    		return;
    	}
    	else if(state == Pump.CONNECTED) {
    		Debug.w(TAG, FUNC_TAG, "Device is connected, cancelling any currently running reconnect timers...");
    		cancelReconnect();
    	}
    	else {
    		if(!settings.getBoolean("paired", false)) {
    			Debug.w(TAG, FUNC_TAG, "There is no saved device, not reconnecting!");
    			return;
    		}
    		
    		setState(Pump.RECONNECTING);

    		bolusing = false;
			setTime = false;
			
        	Debug.i(TAG, FUNC_TAG, "Device was previously paired!  Attempting reconnect!");
        	
        	cancelWarnings();
			
			txMessages.clear();
			rwLatch = new CountDownLatch(0);
			
			startWarnings();
			
			//reconTimer = scheduler.scheduleAtFixedRate(recon, 0, 30, TimeUnit.SECONDS);
			btleGatt.connect();
    	}
    }
    
    private void cancelReconnect()
    {
    	Debug.i(TAG, "cancelReconnect", "Cancelling reconnect timer...");
    	
    	if(reconTimer != null)
    		reconTimer.cancel(true);
    }
    
    private void cancelWarnings()
    {
    	Debug.w(TAG, "cancelWarnings", "Stopping warning and disconnect timers...");
        if(warningTimer != null)
			warningTimer.cancel(true);
		if(disconnectTimer != null)
			disconnectTimer.cancel(true);
    }
    
    private void startWarnings()
    {
    	Debug.i(TAG, "startWarnings", "Starting up 15 minute warning and 20 minute disconnect...");
    	
    	warningTimer = scheduler.schedule(warning, 15, TimeUnit.MINUTES);
    	disconnectTimer = scheduler.schedule(disconnect, 20, TimeUnit.MINUTES);
    }
    
    private void reconnectDevice()
    {
    	final String FUNC_TAG = "reconnectDevice";
		
		if(state != Pump.RECONNECTING)
		{
			cancelReconnect();
			Debug.w(TAG, FUNC_TAG, "The device is not in reconnect mode...cancelling this timer!");
			return;
		}
		
		Debug.i(TAG, FUNC_TAG, "Attempting reconnection to device...");
		
		if(devMac != null && !devMac.equals(""))
			connect(btleAdapter.getRemoteDevice(devMac));
		else
			Debug.e(TAG, FUNC_TAG, "Unable to reconnect, invalid MAC address!");
    }
    
    private void setState(int s)
    {
    	final String FUNC_TAG = "setState";
    	
    	if(state != s) {
    		//Only update changes on state transitions
			Debug.v(TAG, FUNC_TAG, "State Change: "+Pump.stateToString(s));
			ContentValues pv = new ContentValues();
			pv.put("state", s);
			getContentResolver().update(Biometrics.PUMP_DETAILS_URI, pv, null, null);
		}
    	
    	btleDescription = Pump.stateToString(s);
    	
    	state = s;
    }
	
	private void connect(BluetoothDevice d)
	{
		final String FUNC_TAG = "connect";
		
		if(btleGatt != null)
			btleGatt.close();
		
		if(d != null)
			btleGatt = d.connectGatt(this, false, gattCallback);
		else
			Debug.e(TAG, FUNC_TAG, "The connection or device is null!");
	}
	
	private void getAddressPrefixes()
	{
		final String FUNC_TAG = "getAddressPrefixes";
		
		prefixes = new ArrayList<String>();
		
		prefixes.clear();
		
		int num = Params.getInt(getContentResolver(), "tandem_addresses", 0);
		
		for(int i = 1; i <= num; i++)
		{
			prefixes.add(Params.getString(getContentResolver(), "mac"+i, ""));
		}
		
		for(String s:prefixes)
		{
			Debug.i(TAG, FUNC_TAG, "Accepted Prefix: "+s);
		}
	}
	
	public void discoverLeDevices()
	{
		final String FUNC_TAG = "discoverLeDevices";
		
		devices.clear();
		
		if(!scan)
		{
			handler.postDelayed(new Runnable()
			{
				public void run() 
				{
					btleAdapter.stopLeScan(callBack);
					scan = false;
				}
			}, SCAN_PERIOD);
			
			btleAdapter.startLeScan(callBack);
			scan = true;
		}
		else
		{
			Debug.w(TAG, FUNC_TAG, "BTLE is already scanning...stopping now!");
			btleAdapter.stopLeScan(callBack);
			scan = false;
		}
	}
	
	private boolean isPumpBonded()
	{
		final String FUNC_TAG = "isPumpBonded";
		Set<BluetoothDevice> bondedDevices = btleAdapter.getBondedDevices();
		
		if(bondedDevices.isEmpty())
		{
			Debug.i(TAG, FUNC_TAG, "There are no bonded devices...");
			return false;
		}
		
		for(BluetoothDevice d:bondedDevices)
		{
			String sub = d.getAddress().substring(0, 8);
			
			if(prefixes.contains(sub))
			{
				Debug.i(TAG, FUNC_TAG, "Found bonded pump! "+d.getAddress());
				return true;
			}
		}
		
		//If you're here then there are no bonded pumps
		Debug.i(TAG, FUNC_TAG, "There are no bonded pumps!");
		return false;
	}
	
	private void erasePairedPumps()
	{
		final String FUNC_TAG = "erasePairedPumps";
		Set<BluetoothDevice> bondedDevices = btleAdapter.getBondedDevices();
		int k = 0;
		boolean timeout = true;
		
		if(reconTimer != null)
			reconTimer.cancel(true);
        if(warningTimer != null)
			warningTimer.cancel(true);
		if(disconnectTimer != null)
			disconnectTimer.cancel(true);
		
		//Clear the write characteristic so it will  discover it again on reconnect
		writeChar = null;
		
		Debug.i(TAG, FUNC_TAG, "Erasing pumps...");
		
		if(isPumpBonded())
		{
		    try {
		    	
		    	while(isPumpBonded() && timeout)
		    	{
		    		Debug.i(TAG, FUNC_TAG, "Clearing pumps!");
		    		
			        Class<?> btDeviceInstance =  Class.forName(BluetoothDevice.class.getCanonicalName());
			        Method removeBondMethod = btDeviceInstance.getMethod("removeBond");
			        
			        if(bondedDevices.isEmpty())
			        	Debug.i(TAG, FUNC_TAG, "No bonded devices!");
			        
			        for (BluetoothDevice d : bondedDevices) 
			        {
			        	Debug.i(TAG, FUNC_TAG, "Device MAC: "+d.getAddress());
			        	String sub = d.getAddress().substring(0, 8);
						
						if(prefixes.contains(sub))
						{
			                removeBondMethod.invoke(d);
			                Debug.i(TAG,FUNC_TAG,"Cleared Pairing");
			            }
			        }
			        
			        k++;
			        if(k > 10)
			        	timeout = false;
			        Thread.sleep(2000);
		    	}
		    	
		    	if(timeout == false)
		    	{
		    		Debug.e(TAG, FUNC_TAG, "The device is unable to be removed, probably need to restart the phone!");
		    	}
		    } 
		    catch (Throwable th) 
		    {
		        Debug.e(TAG, FUNC_TAG, "Error removing pairing", th);
		    }
		}
	}
	
	//-----------------------------------------------------------------------------------------
	//-----------------------------------------------------------------------------------------
	//	TRANSMIT THREAD
	//-----------------------------------------------------------------------------------------
	//-----------------------------------------------------------------------------------------
	
	private void startTransmitThread()
	{
		final String FUNC_TAG = "startTransmitThread";

		txMessages = new ConcurrentLinkedQueue<OutPacket>();				//Initialize the TX queue
		
		if(!txRunning)
		{
			txRunning = true;
			
			transmit = new Thread ()
			{
				public void run ()
				{
					Debug.i("Thread", FUNC_TAG, "TX Thread starting!");
					txStop = false;
				
					while(!txStop)
					{
						OutPacket op = txMessages.poll();
						if(op != null)
						{
							start = System.currentTimeMillis();
							if(!op.frames.isEmpty())
							{
								Debug.i(TAG, FUNC_TAG, "Transmitting packet: "+op.name+" setting RW latch to "+op.responseFrames);
								rwLatch = new CountDownLatch(op.responseFrames);
								
								for(byte[] b:op.frames)		//So we have packets that are segmented into 19-byte frames
								{
									Debug.i(TAG, FUNC_TAG, "Transmitting buffer: "+b.length);
									writeChar.setValue(b);
									if(btleGatt != null)
										Debug.i(TAG, FUNC_TAG, "Write BLE Status: "+btleGatt.writeCharacteristic(writeChar));
									else
										Debug.i(TAG, FUNC_TAG, "BTLE Gatt Connection is null or closed!");
									
									try {
										Thread.sleep(TX_THREAD_SLEEP);
									} catch (InterruptedException e) {
										e.printStackTrace();
									}
								}
								
								try {
									Debug.i(TAG, FUNC_TAG, "RW Latch: "+rwLatch.await(RW_LATCH_TIMEOUT, TimeUnit.SECONDS));
								} catch (InterruptedException e) {
									e.printStackTrace();
								}
							}
						}
						
						try {
							Thread.sleep(100);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
				}
			};
			
			transmit.start();
		}
	}
	
	//-----------------------------------------------------------------------------------------
	//-----------------------------------------------------------------------------------------
	// Message Handlers
	//-----------------------------------------------------------------------------------------
	//-----------------------------------------------------------------------------------------
	
	class incomingPumpHandler extends Handler {
		final String FUNC_TAG = "incomingPumpHandler";
		@Override
		public void handleMessage(Message msg) {
			switch(msg.what){
				case Pump.PUMP_SERVICE2DRIVER_NULL:
					break;
				case Pump.PUMP_SERVICE2DRIVER_REGISTER:
					messengerToPumpService = msg.replyTo;
					Debug.i(TAG, FUNC_TAG,"Connection made to service, sending parameters");
					
					ContentValues pumpValues = new ContentValues();
					pumpValues.put("max_bolus_U", 12.0);
					pumpValues.put("min_bolus_U", 0.0083);							//The lowest value is 0.0083
					pumpValues.put("min_quanta_U", 0.0001);
					pumpValues.put("infusion_rate_U_sec", INFUSION_RATE);
					pumpValues.put("reservoir_size_U", 300.0);
					pumpValues.put("low_reservoir_threshold_U", 50.0);				//Low reservoir message will show at 50U remaining
					pumpValues.put("queryable", 1);
					
					pumpValues.put("temp_basal", 0);							//Indicates if Temp Basals are possible
					pumpValues.put("temp_basal_time", 15);						//Indicates the time frame of a Temp Basal
					
					pumpValues.put("retries", 0);								//Indicates if retries are used
					
					getContentResolver().update(Biometrics.PUMP_DETAILS_URI, pumpValues, null, null);
					
					sendDataMessage(messengerToPumpService, null, Pump.DRIVER2PUMP_SERVICE_PARAMETERS, msg.arg1, msg.arg2, null);
					break;
				case Pump.PUMP_SERVICE2DRIVER_DISCONNECT:
					Debug.i(TAG, FUNC_TAG,"Disconnecting pump...");
					break;	
				case Pump.PUMP_SERVICE2DRIVER_BOLUS:
					Debug.i(TAG, FUNC_TAG,"Receiving bolus command!");

					double bolus_req = msg.getData().getDouble("bolus");		//Convert the bolus from U to uL for tandem pump
					float bolus_req_ul = (float)(bolus_req*10);
					byte[] buffer;
					
					Debug.i(TAG, FUNC_TAG,"Bolus requested for "+bolus_req+"U ("+bolus_req_ul+"uL)");
					bolusing = true;
					confirming = false;
					
					//Gather ID from the Pump Service
					bolusId = msg.getData().getInt("bolusId");
					bolusState = INVALID;
					
					buffer = tandem.new RequestBolus().Build(bolusId, (float)bolus_req_ul);
					sendTandemData(RequestBolus.TYPE, buffer, RequestBolus.RESP_CNT);
					
					Debug.i(TAG, FUNC_TAG, "Bolus ID: "+bolusId+" of "+bolus_req+"U");
					
					bolusInfusionTime = (long)((bolus_req/INFUSION_RATE)*1000);		//Use the bolus in units
					bolusInfusionTime += 180000;										//Add 3 minutes to timeout (this seems excessive)
					
					Debug.i(TAG, FUNC_TAG,"Timer set to "+bolusInfusionTime+" ms");
					break;
				case Pump.PUMP_SERVICE2DRIVER_QUERY:
					Bundle data = msg.getData();
					queryId = data.getInt("bolusId");
					
					Debug.i(TAG, FUNC_TAG,"Querying bolus ID: "+queryId);
					
					buffer = tandem.new BolusStatus().Build(queryId);
					sendTandemData(BolusStatus.TYPE, buffer, BolusStatus.RESP_CNT);
					break;
			}
		}
	}
	
	class incomingUIHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			final String FUNC_TAG = "incomingUIHandler";
			Bundle b;
			byte[] buffer;
			
			switch(msg.what)
			{
				case BTLE_Tandem_UI.UI2DRIVER_NULL:
					break;
				case BTLE_Tandem_UI.UI2DRIVER_REGISTER:
				break;
				case BTLE_Tandem_UI.UI2DRIVER_CONNECT:
					b = msg.getData();
					int index = b.getInt("index");
					listDevice d = devices.get(index);
					Debug.i(TAG, FUNC_TAG, "Connecting to device: "+d.dev.getAddress());
					dev = d.dev;
					devMac = dev.getAddress();
					
					Debug.i(TAG, FUNC_TAG, "MAC: "+dev.getAddress());
					Debug.i(TAG, FUNC_TAG, "State: "+btleManager.getConnectionState(dev, BluetoothProfile.GATT));
					Debug.i(TAG, FUNC_TAG, "Bond State: "+dev.getBondState());
					
					connect(dev);
					break;
				case BTLE_Tandem_UI.UI2DRIVER_SCAN:
					discoverLeDevices();
					break;
				case BTLE_Tandem_UI.UI2DRIVER_STATUS:
					Debug.i(TAG, FUNC_TAG, "Status message sent!");
					
					buffer = tandem.new GetPumpStatus().Build();
					sendTandemData(GetPumpStatus.TYPE, buffer, GetPumpStatus.RESP_CNT);
					
					buffer = tandem.new GetFluidRemaining().Build();
					sendTandemData(GetFluidRemaining.TYPE, buffer, GetFluidRemaining.RESP_CNT);
					break;
				case BTLE_Tandem_UI.UI2DRIVER_ERASE:
					saveDevice("", false);
					
					ContentValues dv = new ContentValues();
					Debug.e(TAG, FUNC_TAG, "Removing running pump DB value!");
					dv.put("running_pump", "");					
					getContentResolver().update(Biometrics.HARDWARE_CONFIGURATION_URI, dv, null, null);
					
					setState(Pump.DISCONNECTED);
					
					if(btleGatt != null)
					{
						btleGatt.disconnect();
						btleGatt.close();
					}
					
					erasePairedPumps();
					
					devMac = "";
					description = "N/A";
					fluid = "N/A";
					
					break;
			}
		}
	}
	
	private void sendTandemData(String name, byte[] buffer, int responseFrames)
	{
		final int MAX = 19;
		final String FUNC_TAG = "sendTandemData";
		String s = "";
		for(byte b: buffer)
			s+=String.format("%X ", b);
		Debug.i(TAG, FUNC_TAG, "Original Buffer to Send: "+s);
		Debug.i(TAG, FUNC_TAG, "Length of Original Buffer: "+buffer.length);
		
		int count = buffer.length / MAX;
		
		Debug.i(TAG, FUNC_TAG, "Count: "+count+" Mod: "+buffer.length%MAX);
		
		OutPacket op = new OutPacket();
		op.name = name;
		op.responseFrames = responseFrames;
		
		if(buffer.length <= MAX)			//If it's less than or equal to MAX then we can just send it
		{
			s="";
			for(byte b: buffer)
				s+=String.format("%X ", b);
			Debug.i(TAG, FUNC_TAG, s);

			op.frames.add(buffer);
		}
		else
		{
			for(int i=0;i<=count;i++)
			{
				byte[] temp;
				int length = 0;
				
				if((i == count))			//This is the last buffer so it may not be a full MAX-bytes
				{
					if(buffer.length % MAX > 0)
						length = buffer.length - MAX;
					else
						break;
				}
				else
					length = MAX;
				
				Debug.i(TAG, FUNC_TAG, "Loop count: "+i+" Length: "+length);
				
				if(length > 0)
				{
					temp = new byte[length];
					
					System.arraycopy(buffer, i*MAX, temp, 0, temp.length);
					
					s="";
					for(byte b: temp)
						s+=String.format("%X ", b);
					Debug.i(TAG, FUNC_TAG, s);

					op.frames.add(temp);
				}
			}
		}
		
		if(writeChar != null && state >= Pump.CONNECTED)
			txMessages.offer(op);
		else
			Debug.e(TAG, FUNC_TAG, "Write Characteristic is null or is disconnected!");
	}
	
	private void sendDataMessage(Messenger messenger, Bundle bundle, int what, int arg1, int arg2, Messenger reply)
	{
		final String FUNC_TAG = "sendDataMessage";
		
		if(messenger != null)
		{
			Message msg = Message.obtain(null, what);
			msg.arg1 = arg1;
			msg.arg2 = arg2;
			msg.setData(bundle);
			
			if(reply!=null)
				msg.replyTo = reply;
			
			try{
				messenger.send(msg);
			}
			catch(RemoteException e) {
				e.printStackTrace();
			}
		}
		else
			Debug.i(TAG, FUNC_TAG, "Messenger is not connected or is null!");
	}

	private void parseTandemPacket(byte[] in)
	{
		final String FUNC_TAG = "parseTandemPacket";
		
		String s = "";
		for(byte b:in)
			s += String.format("%X ", b);
		
		Debug.i(TAG, FUNC_TAG, "Raw data: "+s);
		
		for(byte b:in)
		{
			switch(parseStatus)
			{
				case GET_TYPE:
					if(TandemFormats.isValidType((int)(b & 0xFF)))
					{
						type = (int)(b & 0xFF);
						Debug.i(TAG, FUNC_TAG, "Type: "+String.format("%X", type));
						parseStatus = GET_LENGTH;
					}
					else
					{
						parseStatus = INVALID;
						Debug.e(TAG, FUNC_TAG, "Invalid packet!");
					}
					break;
				case GET_LENGTH:
					hdr = tandem.new Header(type, (int)(b & 0xFF));
					packet = ByteBuffer.allocate(hdr.getLength() + TandemFormats.HDR_SIZE + TandemFormats.TAIL_SIZE);
					packet.put(hdr.getHeaderArray());
					
					if(b > 0)
					{
						parseStatus = GET_CARGO;
					}
					else
					{
						parseStatus = GET_TIMESTAMP;
					}
					break;
				case GET_CARGO:
					if((index < hdr.getLength()) && (packet != null))
					{
						packet.put(b);
						index++;
					}
					if(index == hdr.getLength())
					{
						parseStatus = GET_TIMESTAMP;
						index = 0;
					}
					break;
				case GET_TIMESTAMP:
					if(index < 4)
					{
						packet.put(b);
						index++;
					}
					if(index == 4)
					{
						parseStatus = GET_CHECKSUM;
						index = 0;
					}
					break;
				case GET_CHECKSUM:
					if(index < 2)
					{
						packet.put(b);
						index++;
					}
					if(index == 2)
						parseStatus = COMPLETE;
					break;
				case COMPLETE:
				case INVALID:
				default:
					if(b == TandemFormats.DELIMITER)
					{
						index = 0;
						type = 0;
						parseStatus = GET_TYPE;
					}
					break;
			}
		}
		
		if(parseStatus == COMPLETE)
		{
			Debug.i(TAG, FUNC_TAG, "Total time to receive response: "+(System.currentTimeMillis() - start));
			Debug.i(TAG, FUNC_TAG, "Complete packet found...");
			setState(Pump.CONNECTED);
			
			Packet pkt = tandem.new Packet(packet.array());
			
			String str = "";
			for(byte b:pkt.getPacket())
				str += String.format("%X ", b);
			
			Debug.i(TAG, FUNC_TAG, "Complete Packet: "+str);
			Debug.i(TAG, FUNC_TAG, "Completed packet length: "+packet.array().length);
			
			extractData(pkt);
		}
	}
	
	private void extractData(Packet pkt)
	{
		final String FUNC_TAG = "extractData";
		
		switch(pkt.getType())
		{
			case TandemFormats.BOLUS_END_TIME:
				Debug.i(TAG, FUNC_TAG, BolusEndTime.TYPE);
				
				BolusEndTime mTime = tandem.new BolusEndTime();
				if(mTime.Extract(pkt))
				{
					long bolusTime = 0;
					String time = mTime.month+"/"+mTime.day+"/"+mTime.year+" "+mTime.hour+":"+mTime.min+":"+mTime.sec;
					
					try {
						bolusTime = new java.text.SimpleDateFormat("MM/dd/yyyy HH:mm:ss").parse(time).getTime();
						bolusTime /= 1000;		//Convert to seconds
					} catch (ParseException e) {
						Debug.i(TAG, FUNC_TAG, "Problem parsing time string!");
					}

					Debug.i(TAG, FUNC_TAG, "Responding to query for ID: "+queryId+" with delivery time of "+time);
					
					//Use the Bundle from the original query status response and simply tack on the bolus end time
					queryData.putLong("time", bolusTime);
					sendDataMessage(messengerToPumpService, queryData, Pump.DRIVER2PUMP_SERVICE_QUERY_RESP, 0, 0, null);
				}
				break;
			case TandemFormats.GET_INTERNAL_TIME:
				Debug.i(TAG, FUNC_TAG, GetInternalTime.TYPE);
				break;
			case TandemFormats.GET_TIME_DATE:
				Debug.i(TAG, FUNC_TAG, GetTimeDate.TYPE);
				break;
			case TandemFormats.BOLUS_STATUS:
				Debug.i(TAG, FUNC_TAG, BolusStatus.TYPE);
				
				BolusStatus mBolus = tandem.new BolusStatus();
				if(mBolus.Extract(pkt))
				{
					if(bolusing && mBolus.id == bolusId)
					{
						bolusAmount = "ID: "+ mBolus.id + " - " + String.format("%f / %f", (mBolus.delivered/10), (mBolus.requested/10));
						bolusStatus = mBolus.statusString;
						
						Debug.i(TAG, FUNC_TAG, bolusAmount + " " + bolusStatus);
						
						bolusState = mBolus.status;			//Set bolus state to value from message
						
						if((bolusState == Pump.DELIVERED) || (bolusState == Pump.CANCELLED))
						{
							int state = Pump.DELIVERED;
							
							if(bolusState == Pump.CANCELLED)
							{
								Toast.makeText(getApplicationContext(), "Bolus "+mBolus.id+" Cancelled!", Toast.LENGTH_LONG).show();
								state = Pump.CANCELLED;
							}
							
							bolusing = false;		//Set bolusing flag to false if the bolus was delivered
							totalDelivered += mBolus.delivered/10;
							bolusDelivered = mBolus.delivered;
							
							if(bolusRetry != null)
								bolusRetry.cancel(true);
							
							if(bolusExpire != null)
								bolusExpire.cancel(true);
							
							//The current time is close enough to this that we can use the BolusEndTime exclusively for querying
							//byte[] buffer = tandem.new BolusEndTime().Build(Driver.bolusId);		//Get the infusion time, by sending this message
							//sendTandemData(buffer);
							
							Bundle data = new Bundle();
							data.putDouble("totalDelivered", totalDelivered);
							data.putLong("time", System.currentTimeMillis()/1000);
							data.putDouble("remainingInsulin", (double)(fluidRem/10));
							data.putDouble("batteryVoltage", 3.00);
							data.putDouble("deliveredInsulin", (double)(bolusDelivered/10));
							data.putInt("status", state);
							
							if(bolusState == Pump.DELIVERED)
								Debug.i(TAG, FUNC_TAG, "Bolus successfully delivered!");
							else
								Debug.i(TAG, FUNC_TAG, "Bolus cancelled!");
							
							Debug.i(TAG, FUNC_TAG, bolusAmount);
							Debug.i(TAG, FUNC_TAG, "Bolus ID: "+bolusId+" delivered!");
							
							sendDataMessage(messengerToPumpService, data, Pump.DRIVER2PUMP_SERVICE_BOLUS_DELIVERY_ACK, 0, 0, null);
						}
					}
					else
					{
						if(mBolus.id == queryId)
						{
							Debug.i(TAG, FUNC_TAG, "QUERY ID: "+ mBolus.id + " - " + String.format("%f / %f", (mBolus.delivered/10), (mBolus.requested/10)));
							
							String descrip = "";
							switch(mBolus.status)
							{
								case Pump.PENDING:
									descrip = "Pending";
									break;
								case Pump.CANCELLED:
									descrip = "Cancelled";
									break;
								case Pump.INTERRUPTED:
									descrip = "Interrupted";
									break;
								case Pump.INVALID_REQ:
									descrip = "Invalid Request";
									break;							
								case Pump.DELIVERED:
									descrip = "Delivered";
									break;
								case Pump.DELIVERING:
									descrip = "Delivering";
									break;
							}
							
							queryData = new Bundle();
							queryData.putInt("status", (int)(mBolus.status & 0xFF));
							queryData.putInt("queryId", queryId);
							queryData.putDouble("delivered_amount_U", mBolus.delivered/10);
							queryData.putString("description", descrip);
							
							if(mBolus.status == Pump.DELIVERED || mBolus.status == Pump.CANCELLED)
							{
								//Send back the delivered time
								byte[] buffer = tandem.new BolusEndTime().Build(queryId);		//Get the infusion time, by sending this message
								sendTandemData(BolusEndTime.TYPE, buffer, BolusEndTime.RESP_CNT);
							}
							else
							{
								//If the state suggests that there is no completed infusion time then disregard
								sendDataMessage(messengerToPumpService, queryData, Pump.DRIVER2PUMP_SERVICE_QUERY_RESP, 0, 0, null);
							}
						}
					}
				}
				break;
			case TandemFormats.SET_FLUID_NAME:
				Debug.i(TAG, FUNC_TAG, SetFluidName.TYPE);
				break;
			case TandemFormats.GET_FLUID_REMAINING:
				Debug.i(TAG, FUNC_TAG, GetFluidRemaining.TYPE);
				
				GetFluidRemaining mFluid = tandem.new GetFluidRemaining(); 
				if(mFluid.Extract(pkt))
				{
					fluidRem = mFluid.remaining;
					fluid = (mFluid.remaining/10) + " Units";
					Debug.i(TAG, FUNC_TAG, "Fluid Remaining: "+fluidRem);
				}
				break;
			case TandemFormats.GET_FLUID_NAME:
				Debug.i(TAG, FUNC_TAG, GetFluidName.TYPE);
				break;
			case TandemFormats.GET_PUMP_STATUS:
				Debug.i(TAG, FUNC_TAG, GetPumpStatus.TYPE);
				
				GetPumpStatus mStatus = tandem.new GetPumpStatus();
				if(mStatus.Extract(pkt))
				{
					description = mStatus.statusString;
					Debug.i(TAG, FUNC_TAG, "Status: "+mStatus.statusString);
				}
				break;
			case TandemFormats.REQUEST_CONFIRM:
				Debug.i(TAG, FUNC_TAG, RequestConfirm.TYPE);
				
				RequestConfirm mReq = tandem.new RequestConfirm();
				if(mReq.Extract(pkt))
				{
					if(bolusing)
					{
						Debug.i(TAG, FUNC_TAG, "Confirming request bolus command!");
						confirming = true;
						ConfirmRequest mConfirm = tandem.new ConfirmRequest();
						sendTandemData(ConfirmRequest.TYPE, mConfirm.Build(TandemFormats.REQUEST_BOLUS, mReq.token), ConfirmRequest.RESP_CNT);
					}
					else if(setTime)
					{
						Debug.i(TAG, FUNC_TAG, "Confirming set time command!");
						setTime = false;
						ConfirmRequest mConfirm = tandem.new ConfirmRequest();
						sendTandemData(ConfirmRequest.TYPE, mConfirm.Build(TandemFormats.SET_TIME_DATE, mReq.token), ConfirmRequest.RESP_CNT);
					}
				}
				break;
			case TandemFormats.NACK:
				Debug.i(TAG, FUNC_TAG, Nack.TYPE);
				break;
			case TandemFormats.ACK:
				Debug.i(TAG, FUNC_TAG, Ack.TYPE);
				
				if(confirming && bolusing)
				{
					Debug.i(TAG, FUNC_TAG, "Confirming bolus message delivery");
					confirming = false;
					
					Bundle data = new Bundle();
					data.putDouble("totalDelivered", totalDelivered);
					data.putLong("infusionTime", bolusInfusionTime);
					data.putLong("identifier", bolusId);
					sendDataMessage(messengerToPumpService, data, Pump.DRIVER2PUMP_SERVICE_BOLUS_COMMAND_ACK, 0, 0, null);
					
					bolusRetry = scheduler.scheduleAtFixedRate(status, 0, 10, TimeUnit.SECONDS);
					bolusExpire = scheduler.schedule(expire, bolusInfusionTime, TimeUnit.MILLISECONDS);
				}
				break;
			case TandemFormats.END_CONNECTION:
				Debug.i(TAG, FUNC_TAG, EndConnection.TYPE);
				break;
			default:
				Debug.i(TAG, FUNC_TAG, "Unknown Packet!");
				break;
		}
	}
	
	private String checkStatus(int status)
	{
		String s = "";
		
		switch(status)
		{
			case    0x0000:	s = "GATT_SUCCESS"; break;
			case	0x0001:	s = "GATT_INVALID_HANDLE"; break;
			case    0x0002: s = "GATT_READ_NOT_PERMIT"; break;
			case    0x0003: s = "GATT_WRITE_NOT_PERMIT"; break;
			case    0x0004: s = "GATT_INVALID_PDU"; break;
			case    0x0005: s = "GATT_INSUF_AUTHENTICATION"; break;
			case    0x0006: s = "GATT_REQ_NOT_SUPPORTED"; break;
			case    0x0007: s = "GATT_INVALID_OFFSET"; break;
			case    0x0008: s = "GATT_INSUF_AUTHORIZATION"; break;
			case    0x0009: s = "GATT_PREPARE_Q_FULL"; break;
			case    0x000a: s = "GATT_NOT_FOUND"; break;
			case    0x000b: s = "GATT_NOT_LONG"; break;
			case    0x000c: s = "GATT_INSUF_KEY_SIZE"; break;
			case    0x000d: s = "GATT_INVALID_ATTR_LEN"; break;
			case    0x000e: s = "GATT_ERR_UNLIKELY"; break;
			case    0x000f: s = "GATT_INSUF_ENCRYPTION"; break;
			case    0x0010: s = "GATT_UNSUPPORT_GRP_TYPE"; break;
			case    0x0011: s = "GATT_INSUF_RESOURCE"; break;
			case    0x0087: s = "GATT_ILLEGAL_PARAMETER"; break;
			case    0x0080: s = "GATT_NO_RESOURCES"; break;
			case    0x0081: s = "GATT_INTERNAL_ERROR"; break;
			case    0x0082: s = "GATT_WRONG_STATE"; break;
			case    0x0083: s = "GATT_DB_FULL"; break;
			case    0x0084: s = "GATT_BUSY"; break;
			case    0x0085: s = "GATT_ERROR"; break;
			case    0x0086: s = "GATT_CMD_STARTED"; break;
			case    0x0088: s = "GATT_PENDING"; break;
			case    0x0089: s = "GATT_AUTH_FAIL"; break;
			case    0x008a: s = "GATT_MORE"; break;
			case    0x008b: s = "GATT_INVALID_CFG"; break;
			case    0x008c: s = "GATT_SERVICE_STARTED"; break;
			case    0x008d: s = "GATT_ENCRYPED_NO_MITM"; break;
			case	0x008e: s = "GATT_NOT_ENCRYPTED"; break;
			default: s = "Unknown"; break;
		}
		
		return s;
	}
	
	private void restoreDevice()
	{
		final String FUNC_TAG = "restoreDevice";
		Debug.i(TAG, FUNC_TAG, "Attempting to restore device from memory...");
		
		settings = getSharedPreferences("BTLE_Tandem", 0);
		
		if(settings.getBoolean("paired", false))
		{
			Debug.i(TAG, FUNC_TAG, "Found device in memory!");
			BTLE_Tandem_Driver.devMac = settings.getString("mac", "");
		}
		else
			Debug.w(TAG, FUNC_TAG, "No device in memory!");
	}
	
	private void saveDevice(String mac, boolean paired)
	{
		final String FUNC_TAG = "saveDevice";
		Debug.i(TAG, FUNC_TAG, "Saving device to memory...");
		
		settings = getSharedPreferences("BTLE_Tandem", 0);
		SharedPreferences.Editor edit = settings.edit();
		
		edit.putBoolean("paired", paired);
		edit.putString("mac", mac);

		edit.commit();
	}
	
	public class listDevice
	{
		String address;
		BluetoothDevice dev;
		
		public listDevice(){};
		
		public listDevice(String address, BluetoothDevice dev)
		{
			this.address = address;
			this.dev = dev;
		}
	}
	
	public class OutPacket
	{
		public String name = "UNKNOWN";
		public int responseFrames = 0;
		public List<byte[]> frames = new ArrayList<byte[]>();
		
		public OutPacket(){};
	}
}
