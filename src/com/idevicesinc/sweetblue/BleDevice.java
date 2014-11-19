package com.idevicesinc.sweetblue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import com.idevicesinc.sweetblue.BleDevice.ConnectionFailListener.Reason;
import com.idevicesinc.sweetblue.BleDevice.ConnectionFailListener.Please;
import com.idevicesinc.sweetblue.BleDevice.ReadWriteListener.Status;
import com.idevicesinc.sweetblue.BleDevice.ReadWriteListener.Type;
import com.idevicesinc.sweetblue.BleDevice.ReadWriteListener.Result;
import com.idevicesinc.sweetblue.utils.*;
import com.idevicesinc.sweetblue.utils.TimeEstimator;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.*;
import android.os.Build;
import android.util.Log;
import static com.idevicesinc.sweetblue.BleDeviceState.*;

/**
 * This is the one other class you will use the most besides {@link BleManager}. It acts as a
 * BLE-specific abstraction for the rather useless {@link BluetoothDevice} class and the rather hard-to-use
 * {@link BluetoothGatt} class. It does everything you would expect, like providing methods for connecting,
 * reading/writing characteristics, enabling notifications, etc.
 * <br><br>
 * Instances of this class are generally not created by the calling library or application, but rather are
 * creating implicitly by {@link BleManager} as a result of a scanning operation (e.g. {@link BleManager#startScan()}
 * and sent to you through {@link BleManager.DiscoveryListener#onDeviceDiscovered(BleDevice)}.
 * 
 * @author dougkoellmer
 */
public class BleDevice
{
	/**
	 * Provide an implementation of this callback to various methods like {@link BleDevice#read(UUID, ReadWriteListener)},
	 * {@link BleDevice#write(UUID, byte[], ReadWriteListener)}, {@link BleDevice#startPoll(UUID, Interval, ReadWriteListener)},
	 * {@link BleDevice#enableNotify(UUID, ReadWriteListener)}, etc.
	 * 
	 * @author dougkoellmer
	 */
	public static interface ReadWriteListener
	{
		/**
		 * A value returned to {@link ReadWriteListener#onReadOrWriteComplete(Result)} by way of
		 * {@link Result#status} that indicates success of the operation or the reason for its failure.
		 * This enum is <i>not</i> meant to match up with BluetoothGatt.GATT_* values in any way.
		 * 
		 * @see Result#status
		 * 
		 * @author dougkoellmer
		 */
		public static enum Status
		{
			/**
			 * If {@link Result#type} {@link Type#isRead()} then {@link Result#data} should contain
			 * some data returned from the device. If type is {@link Type#WRITE} then {@link Result#data}
			 * was sent to the device.
			 */
			SUCCESS,
			
			/**
			 * Device is not {@link BleDeviceState#CONNECTED}.
			 */
			NOT_CONNECTED,
			
			/**
			 * Couldn't find a matching {@link Result#target} for {@link Result#uuid} which was given to {@link BleDevice#read(UUID, ReadWriteListener)},
			 * {@link BleDevice#write(UUID, byte[])}, etc. This most likely means that the internal call to {@link BluetoothGatt#discoverServices()}
			 * didn't find any {@link BluetoothGattService} that contains the given {@link Result#uuid}.
			 */
			NO_MATCHING_TARGET,
			
			/**
			 * You tried to do a read on a characteristic that is write-only, or vice-versa, or tried to read a notify-only characteristic, etc., etc. 
			 */
			OPERATION_NOT_SUPPORTED,
			
			/**
			 * {@link BluetoothGatt#setCharacteristicNotification(BluetoothGattCharacteristic, boolean)} returned false for an unknown reason.
			 */
			FAILED_TO_REGISTER_FOR_NOTIFICATIONS,
			
			/**
			 * {@link BluetoothGattCharacteristic#setValue(byte[])} (or one of its overloads) or
			 * {@link BluetoothGattDescriptor#setValue(byte[])} (or one of its overloads) returned false.
			 */
			FAILED_TO_WRITE_VALUE_TO_TARGET,
			
			/**
			 * The call to {@link BluetoothGatt#readCharacteristic(BluetoothGattCharacteristic)} or {@link BluetoothGatt#writeCharacteristic(BluetoothGattCharacteristic)}
			 * or etc. returned {@link Boolean#false} and thus failed immediately for unknown reasons. No good remedy for this...perhaps try {@link BleManager#dropTacticalNuke()}.
			 */
			FAILED_TO_SEND_OUT,
			
			/**
			 * The operation was cancelled either by the device becoming {@link BleDeviceState#DISCONNECTED} or {@link BleManager} turning {@link BleState#OFF}.
			 */
			CANCELLED,
			
			/**
			 * Used if {@link Result#type} {@link Type#isRead()} and the stack returned a null value for {@link BluetoothGattCharacteristic#getValue()} despite
			 * the operation being otherwise "successful".
			 * Will throw an {@link UhOh#READ_RETURNED_NULL} but hopefully it was just a glitch. If problem persists try {@link BleManager#dropTacticalNuke()}.
			 */
			NULL_VALUE_RETURNED,
			
			/**
			 * Used when {@link Result#type} {@link Type#isRead()} and the operation was "successful" but returned a zero-length array for {@link Result#data}. 
			 */
			EMPTY_VALUE_RETURNED,
			
			/**
			 * The operation failed in a "normal" fashion, at least relative to all the other strange ways an operation can fail. This means
			 * for example that {@link BluetoothGattCallback#onCharacteristicRead(BluetoothGatt, BluetoothGattCharacteristic, int)} returned
			 * a status code that was not zero. This could mean the device went out of range, was turned off, signal was disrupted, whatever.
			 */
			REMOTE_GATT_FAILURE,
			
			/**
			 * Operation took longer than {@link BleManagerConfig#DEFAULT_TASK_TIMEOUT} seconds so we cut it loose.
			 */
			TIMED_OUT;
		}
		
		/**
		 * The type of operation for a {@link Result} - read, write, poll, etc.
		 * 
		 * @author dougkoellmer
		 */
		public static enum Type
		{
			/**
			 * Associated with {@link BleDevice#read(UUID, ReadWriteListener)}.
			 */
			READ,
			
			/**
			 * Associated with {@link BleDevice#write(UUID, byte[])} or {@link BleDevice#write(UUID, byte[], ReadWriteListener)}.
			 */
			WRITE,
			
			/**
			 * Associated with {@link BleDevice#startPoll(UUID, Interval, ReadWriteListener)}.
			 */
			POLL,
			
			/**
			 * Associated with {@link BleDevice#enableNotify(UUID, ReadWriteListener)} when we actually get a notification.
			 */
			NOTIFICATION,
			
			/**
			 * Similar to {@link #NOTIFICATION}, kicked off from {@link BleDevice#enableNotify(UUID, ReadWriteListener)},
			 * but under the hood this is treated differently.
			 */
			INDICATION,
			
			/**
			 * Associated with {@link BleDevice#startChangeTrackingPoll(UUID, Interval, ReadWriteListener)} or
			 * {@link BleDevice#enableNotify(UUID, Interval, ReadWriteListener)} where a force-read timeout is invoked.
			 */
			PSUEDO_NOTIFICATION,
			
			/**
			 * Associated with {@link BleDevice#enableNotify(UUID, ReadWriteListener)} and called when enabling the notification
			 * completes by writing to the Descriptor of the given {@link UUID}. {@link Status#SUCCESS} doesn't <i>necessarily</i> mean
			 * that notifications will definitely work (there may be other issues in the underlying stack), but it's a reasonable guarantee.
			 */
			ENABLING_NOTIFICATION,
			
			/**
			 * Opposite of {@link #ENABLING_NOTIFICATION}.
			 */
			DISABLING_NOTIFICATION;
			
			/**
			 * Returns {@link Boolean#TRUE} if <code>this</code> does not equal {@link #WRITE}, otherwise {@link Boolean#FALSE}.
			 */
			public boolean isRead()
			{
				return this != WRITE && this != ENABLING_NOTIFICATION && this != DISABLING_NOTIFICATION;
			}
		}
		
		/**
		 * The type of GATT object that {@link Result#charUuid} represents.
		 *  
		 * @author dougkoellmer
		 */
		public static enum Target
		{
			/**
			 * The {@link Result} returned has to do with a {@link BluetoothGattCharacteristic} under the hood.
			 */
			CHARACTERISTIC,
			
			/**
			 * The {@link Result} returned has to do with a {@link BluetoothGattDescriptor} under the hood.
			 */
			DESCRIPTOR
		}
		
		/**
		 * Provides a bunch of information about a completed read, write, or notification.
		 * 
		 * @author dougkoellmer
		 */
		public static class Result
		{
			/**
			 * Value used in place of <code>null</code>, for now only indicating that {@link #descUuid}
			 * isn't used for the {@link Result} because {@link #target} is {@link Target#CHARACTERISTIC}.
			 */
			public static final UUID NON_APPLICABLE_UUID = Uuids.INVALID;
			
			/**
			 * The {@link BleDevice} this {@link Result} is for.
			 */
			public final BleDevice device;
			
			/**
			 * The type of operation, read, write, etc.
			 */
			public final Type type;
			
			/**
			 * The type of GATT object this {@link Result} is for, characteristic or descriptor.
			 */
			public final Target target;
			
			/**
			 * The {@link UUID} of the characteristic associated with this {@link Result}. This will always be
			 * a valid {@link UUID}, even if {@link #target} is {@link Target#DESCRIPTOR}.
			 */
			public final UUID charUuid;
			
			/**
			 * The {@link UUID} of the descriptor associated with this {@link Result}. If {@link #target} is
			 * {@link Target#CHARACTERISTIC} then this will be referentially equal (i.e. you can use == to compare)
			 * to {@link #NON_APPLICABLE_UUID}.
			 */
			public final UUID descUuid;
			
			/**
			 * The data sent to the peripheral if {@link Result#type} is {@link Type#WRITE},
			 * otherwise the data received from the peripheral if {@link Result#type} {@link Type#isRead()}.
			 */
			public final byte[] data;
			
			/**
			 * Indicates either success or the type of failure. Some values of {@link Status} are not
			 * used for certain values of {@link Type}. For example a {@link Type#NOTIFICATION}
			 * cannot fail with {@link Status#TIMED_OUT}.
			 */
			public final Status status;
			
			/**
			 * Time spent "over the air" - so in the native stack, processing in the peripheral's embedded software, what have you.
			 */
			public final Interval transitTime;
			
			/**
			 * Total time it took for the operation to complete, whether success or failure.
			 * This mainly includes time spent in the internal job queue plus {@link Result#transitTime}.
			 */
			public final Interval totalTime;
			
			Result(BleDevice device, UUID charUuid_in, UUID descUuid_in, Type type_in, Target target_in, byte[] data_in, Status status_in, double totalTime, double transitTime)
			{
				if( data_in != null && data_in.length == 0 && type_in.isRead() )
				{
					status_in = Status.EMPTY_VALUE_RETURNED;
				}
				
				this.device = device;
				this.charUuid = charUuid_in;
				this.descUuid = descUuid_in != null ? descUuid_in : NON_APPLICABLE_UUID;
				this.type = type_in;
				this.target = target_in;
				this.data = data_in != null ? data_in : EMPTY_BYTE_ARRAY;
				this.status = status_in;
				this.totalTime = Interval.seconds(totalTime);
				this.transitTime = Interval.seconds(transitTime);
			}
			
			/**
			 * Convenience method for checking if {@link Result#status} equals {@link Status#SUCCESS}.
			 */
			public boolean wasSuccess()
			{
				return status == Status.SUCCESS;
			}
			
			@Override public String toString()
			{
				return "status="+status+" type="+type+" target="+target+" charUuid="+charUuid;
			}
		}
		
		/**
		 * Called when a read or write is complete or when a notification comes in.
		 */
		void onReadOrWriteComplete(Result result);
	}
	
	/**
	 * Provide an implementation to {@link BleDevice#setListener_State(StateListener)} and/or
	 * {@link BleManager#setListener_DeviceState(BleDevice.StateListener)} to receive state change events.
	 * 
	 * @see BleDeviceState
	 * @see BleDevice#setListener_State(StateListener)
	 * 
	 * @author dougkoellmer
	 */
	public static interface StateListener
	{
		/**
		 * Called when a device's bitwise {@link BleDeviceState} changes. As many bits as possible are flipped at the same time.
		 *  
		 * @param oldStateBits The previous bitwise representation of {@link BleDeviceState}.
		 * @param newStateBits The new and now current bitwise representation of {@link BleDeviceState}. Will be the same as {@link BleDevice#getStateMask()}.
		 */
		void onStateChange(BleDevice device, int oldStateBits, int newStateBits);
	}
	
	/**
	 * Provide an implementation of this callback to {@link BleDevice#setListener_ConnectionFail(ConnectionFailListener)}.
	 * 
	 * @see DefaultConnectionFailListener
	 * @see BleDevice#setListener_ConnectionFail(ConnectionFailListener)
	 * 
	 * @author dougkoellmer
	 */
	public static interface ConnectionFailListener
	{
		/**
		 * The reason for the connection failure.
		 * 
		 * @author dougkoellmer
		 */
		public static enum Reason
		{
			/**
			 * Couldn't actually connect through {@link BluetoothDevice#connectGatt(android.content.Context, boolean, BluetoothGattCallback)}.
			 */
			NATIVE_CONNECTION_FAILED,
			
			/**
			 * {@link BluetoothDevice#connectGatt(android.content.Context, boolean, BluetoothGattCallback)} took longer than
			 * {@link BleManagerConfig#DEFAULT_TASK_TIMEOUT} seconds.
			 */
			NATIVE_CONNECTION_TIMED_OUT,
			
			/**
			 * {@link BluetoothGatt#discoverServices()} did not complete successfully.
			 */
			GETTING_SERVICES_FAILED,
			
			/**
			 * The {@link BleTransaction} instance passed to {@link BleDevice#connectAndAuthenticate(BleTransaction)} or
			 * {@link BleDevice#connect(BleTransaction, BleTransaction)} failed through {@link BleTransaction#fail()}.
			 */
			AUTHENTICATION_FAILED,
			
			/**
			 * {@link BleTransaction} instance passed to {@link BleDevice#connectAndInitialize(BleTransaction)} or
			 * {@link BleDevice#connect(BleTransaction, BleTransaction)} failed through {@link BleTransaction#fail()}.
			 */
			INITIALIZATION_FAILED,
			
			/**
			 * Remote peripheral randomly disconnected sometime during the connection process. Similar to {@link #NATIVE_CONNECTION_FAILED}
			 * but only occurs after the device is {@link BleDeviceState#CONNECTED} and we're going through {@link BleDeviceState#GETTING_SERVICES},
			 * or {@link BleDeviceState#AUTHENTICATING}, or what have you.
			 */
			ROGUE_DISCONNECT,
			
			/**
			 * {@link BleDevice#disconnect()} was called sometime during the connection process.
			 */
			EXPLICITLY_CANCELLED;
		}
		
		/**
		 * Simply a more explicit return value than {@link Boolean}.
		 */
		public static enum Please
		{
			RETRY, DO_NOT_RETRY;
		}
		
		/**
		 * Return value is ignored if device is either {@link BleDeviceState#ATTEMPTING_RECONNECT} or reason is {@link Reason#EXPLICITLY_CANCELLED}.
		 * If the device is {@link BleDeviceState#ATTEMPTING_RECONNECT} then authority is deferred to {@link BleManagerConfig.ReconnectRateLimiter}.
		 * Otherwise, this method offers a more convenient way of retrying a connection, as opposed to manually doing it yourself. It also
		 * lets the library handle things in a slightly more optimized fashion and so is recommended for that reason also.
		 * <br><br>
		 * NOTE that this callback gets fired *after* {@link StateListener} lets you know that the device is {@link BleDeviceState#DISCONNECTED}.
		 */
		Please onConnectionFail(BleDevice device, ConnectionFailListener.Reason reason, int failureCountSoFar);
	}
	
	/**
	 * The default retry count provided to {@link DefaultConnectionFailListener}. So if you were to call
	 * {@link BleDevice#connect()} and all connections failed, in total the library would try to connect
	 * {@link #DEFAULT_CONNECTION_FAIL_RETRY_COUNT}+1 times.
	 * 
	 * @see DefaultConnectionFailListener
	 */
	public static final int DEFAULT_CONNECTION_FAIL_RETRY_COUNT = 2;
	
	/**
	 * Default implementation of {@link ConnectionFailListener} that attempts a certain number of retries.
	 * An instance of this class is set by default for all new {@link BleDevice} instances using {@link BleDevice#DEFAULT_CONNECTION_FAIL_RETRY_COUNT}.
	 * Use {@link BleDevice#setListener_ConnectionFail(ConnectionFailListener)} to override the default behavior.
	 * 
	 * @see ConnectionFailListener
	 * @see BleDevice#setListener_ConnectionFail(ConnectionFailListener)
	 * 
	 * @author dougkoellmer
	 */
	public static class DefaultConnectionFailListener implements ConnectionFailListener
	{
		private final int m_retryCount;
		
		public DefaultConnectionFailListener(int retryCount)
		{
			m_retryCount = retryCount;
		}
		
		public int getRetryCount()
		{
			return m_retryCount;
		}
		
		@Override public Please onConnectionFail(BleDevice device, Reason reason, int failureCountSoFar)
		{
			return failureCountSoFar <= m_retryCount ? Please.RETRY : Please.DO_NOT_RETRY; 
		}
	}
	
	static ConnectionFailListener DEFAULT_CONNECTION_FAIL_LISTENER = new DefaultConnectionFailListener(DEFAULT_CONNECTION_FAIL_RETRY_COUNT);

	final Object m_threadLock = new Object();
	
	final P_NativeDeviceWrapper m_nativeWrapper;
	
	private double m_timeSinceLastDiscovery;
	
			final P_BleDevice_Listeners m_listeners;
	private final P_ServiceManager m_serviceMngr;
	private final P_DeviceStateTracker m_stateTracker;
	private final P_PollManager m_pollMngr;
	
	private final BleManager m_mngr;
	private final P_Logger m_logger;
	private final P_TaskQueue m_queue;
			final P_TransactionManager m_txnMngr;
	private final P_ReconnectManager m_reconnectMngr;
	private final P_ConnectionFailManager m_connectionFailMngr;
	
	private final TimeEstimator m_writeTimeEstimator;
	private final TimeEstimator m_readTimeEstimator;
	
	private final PA_Task.I_StateListener m_taskStateListener;
	
	private static final UUID[]				EMPTY_UUID_ARRAY	= new UUID[0];
	private static final ArrayList<UUID>	EMPTY_LIST			= new ArrayList<UUID>();
			static final byte[]				EMPTY_BYTE_ARRAY	= new byte[0];
	
	private int m_rssi = 0;
	private List<UUID> m_advertisedServices = EMPTY_LIST;
	private byte[] m_scanRecord = EMPTY_BYTE_ARRAY;
	
	private boolean m_useAutoConnect = false;
	private boolean m_alwaysUseAutoConnect = false;
	
	/**
	 * Field for app to associate any data it wants with instances of this class
	 * instead of having to subclass or manage associative hash maps or something.
	 */
	public Object appData;
	
	
	BleDevice(BleManager mngr, BluetoothDevice device_native, String normalizedName)
	{
		m_mngr = mngr;
		m_nativeWrapper = new P_NativeDeviceWrapper(this, device_native, normalizedName);
		m_queue = m_mngr.getTaskQueue();
		m_listeners = new P_BleDevice_Listeners(this);
		m_logger = m_mngr.getLogger();
		m_serviceMngr = new P_ServiceManager(this);
		m_stateTracker = new P_DeviceStateTracker(this);
		m_pollMngr = new P_PollManager(this);
		m_txnMngr = new P_TransactionManager(this);
		m_taskStateListener = m_listeners.m_taskStateListener;
		m_reconnectMngr = new P_ReconnectManager(this);
		m_connectionFailMngr = new P_ConnectionFailManager(this, m_reconnectMngr);
		m_writeTimeEstimator = new TimeEstimator(m_mngr.m_config.nForAverageRunningWriteTime);
		m_readTimeEstimator = new TimeEstimator(m_mngr.m_config.nForAverageRunningReadTime);
		
		m_alwaysUseAutoConnect = m_mngr.m_config.alwaysUseAutoConnect;
		m_useAutoConnect = m_alwaysUseAutoConnect;
	}
	
	/**
	 * Set a listener here to be notified whenever this device's state changes.
	 */
	public void setListener_State(StateListener listener)
	{
		m_stateTracker.setListener(listener);
	}
	
	/**
	 * Set a listener here to be notified whenever a connection fails and to have control over retry behavior.
	 */
	public void setListener_ConnectionFail(ConnectionFailListener listener)
	{
		m_connectionFailMngr.setListener(listener);
	}
	
	/**
	 * Returns the connection failure retry count during a retry loop. Basic example use case is to provide a
	 * callback to {@link #setListener_ConnectionFail(ConnectionFailListener)} and update your application's UI with this method's
	 * return value downstream of your {@link ConnectionFailListener#onConnectionFail(BleDevice, Reason, int)} override.
	 */
	public int getConnectionRetryCount()
	{
		return m_connectionFailMngr.getRetryCount();
	}
	
	/**
	 * Returns the bitwise state mask representation of {@link BleDeviceState} for this device.
	 * 
	 * @see BleDeviceState
	 */
	public int getStateMask()
	{
		return m_stateTracker.getState();
	}
	
	/**
	 * See similar explanation for {@link #getAverageWriteTime()}.
	 *  
	 * @see #getAverageWriteTime()
	 * @see BleManagerConfig#nForAverageRunningReadTime
	 */
	public double getAverageReadTime()
	{
		return m_readTimeEstimator.getRunningAverage();
	}
	
	/**
	 * Returns the average round trip time in seconds for all write operations started with
	 * {@link #write(UUID, byte[])} or {@link #write(UUID, byte[], ReadWriteListener)}.
	 * This is a running average with N being defined by {@link BleManagerConfig#nForAverageRunningWriteTime}.
	 * This may be useful for estimating how long a series of reads and/or writes will take. For example
	 * for displaying the estimated time remaining for a firmware update.
	 */
	public double getAverageWriteTime()
	{
		return m_writeTimeEstimator.getRunningAverage();
	}
	
	/**
	 * Returns the raw RSSI retrieved from when the device was discovered or rediscovered.
	 * 
	 */
	public int getRssi()
	{
		return m_rssi;
	}
	
	/**
	 * Returns the scan record from when we discovered the device.
	 * May be empty but never null.
	 */
	public byte[] getScanRecord()
	{
		return m_scanRecord;
	}
	
	/**
	 * Returns the advertised services, if any, parsed from {@link #getScanRecord()}.
	 * May be empty but never null.
	 */
	public UUID[] getAdvertisedServices()
	{
		UUID[] toReturn = m_advertisedServices.size() > 0 ? new UUID[m_advertisedServices.size()] : EMPTY_UUID_ARRAY;
		return m_advertisedServices.toArray(toReturn);
	}
	
	/**
	 * Returns whether the device is in any of the provided states.
	 * 
	 * @see #is(BleDeviceState)
	 */
	public boolean isAny(BleDeviceState ... states)
	{
		for( int i = 0; i < states.length; i++ )
		{
			if( is(states[i]) )  return true;
		}
		
		return false;
	}
	
	/**
	 * Returns whether the device is in the provided state.
	 * 
	 * @see #isAny(BleDeviceState...)
	 */
	public boolean is(BleDeviceState state)
	{
		return state.overlaps(getStateMask());
	}
	
	/**
	 * Similar to {@link #is(BleDeviceState)} and {@link #isAny(BleDeviceState...)} but allows you
	 * to give a simple query made up of {@link BleDeviceState} and {@link Boolean} pairs. So an example
	 * would be <code>myDevice.is({@link BleDeviceState#CONNECTING}, true, {@link BleDeviceState#ATTEMPTING_RECONNECT}, false)</code>.
	 */
	public boolean is(Object ... query)
	{
		if( query == null || query.length == 0 )	return false;
		
		for( int i = 0; i < query.length; i+=2 )
		{
			Object first = query[i];
			Object second = i+1 < query.length ? query[i+1] : null;
			
			if( first == null || second == null )	return false;
			
			if( !(first instanceof BleDeviceState) || !(second instanceof Boolean) )
			{
				return false;
			}
			
			BleDeviceState state = (BleDeviceState) first;
			Boolean value  = (Boolean) second;
			
			if( value && !this.is(state) )			return false;
			else if( !value && this.is(state) )		return false;
		}
		
		return true;
	}
	
	/**
	 * Returns the raw, unmodified device name retrieved from the stack.
	 * Same as {@link BluetoothDevice#getName()}.
	 */
	public String getNativeName()
	{
		return m_nativeWrapper.getNativeName();
	}
	
	/**
	 * The name retrieved from {@link #getNativeName()} can change arbitrarily, like the last 4 of the MAC
	 * address can get appended sometimes, and spaces might get changed to underscores or vice-versa,
	 * caps to lowercase, etc. This may somehow be standard, to-the-spec behavior but to the newcomer it's
	 * confusing and potentially time-bomb-bug-inducing, like if you're using device name as a filter for something and 
	 * everything's working until one day your app is suddenly broken and you don't know why. This method is an
	 * attempt to normalize name behavior and always return the same name regardless of the underlying stack's
	 * whimsy. The target format is all lowercase and underscore-delimited with no trailing MAC address.
	 */
	public String getNormalizedName()
	{
		return m_nativeWrapper.getNormalizedName();
	}
	
	/**
	 * Returns a name useful for logging and debugging. As of this writing it is {@link #getNormalizedName()}
	 * plus the last four digits of the device's MAC address from {@link #getMacAddress()}.
	 * {@link BleDevice#toString()} uses this.
	 */
	public String getDebugName()
	{
		return m_nativeWrapper.getDebugName();
	}
	
	/**
	 * Provides just-in-case lower-level access to the native device instance. Be careful with this.
	 * It generally should not be needed. Only invoke "mutators" of this object in times of extreme need.
	 * If you are forced to use this please contact library developers to discuss possible feature addition
	 * or report bugs.
	 */
	public BluetoothDevice getNative()
	{
		return m_nativeWrapper.getDevice();
	}
	
	/**
	 * Returns the native characteristic for the given UUID in case you need lower-level access.
	 * You should only call this after {@link BleDeviceState#GETTING_SERVICES} has completed.
	 * Please see the warning for {@link #getNative()}.
	 */
	public BluetoothGattCharacteristic getNativeCharacteristic(UUID uuid)
	{
		P_Characteristic characteristic = m_serviceMngr.getCharacteristic(uuid);
		
		if (characteristic == null )  return null;
		
		return characteristic.getGuaranteedNative();
	}
	
	/**
	 * Returns the native service for the given UUID in case you need lower-level access.
	 * You should only call this after {@link BleDeviceState#GETTING_SERVICES} has completed.
	 * Please see the warning for {@link #getNative()}.
	 */
	public BluetoothGattService getNativeService(UUID uuid)
	{
		P_Service service = m_serviceMngr.get(uuid);
		
		if( service == null )  return null;
		
		return service.getNative();
	}
	
	/**
	 * See pertinent warning for {@link #getNative()}.
	 */
	public BluetoothGatt getGatt()
	{
		return m_nativeWrapper.getGatt();
	}
	
	/**
	 * Returns this devices's manager.
	 */
	public BleManager getManager()
	{
		return m_mngr;
	}
	
	/**
	 * Returns the MAC address of this device, as retrieved from the native stack.
	 */
	public String getMacAddress()
	{
		return m_nativeWrapper.getAddress();
	}
	
	/**
	 * Attempts to create a bond. Analogous to {@link BluetoothDevice#createBond()}
	 * This is also sometimes called pairing, but while pairing and bonding are closely
	 * related, they are technically different from each other.
	 * 
	 * Bonding is required for reading/writing encrypted characteristics and, anecdotally,
	 * may improve connection stability. This is mentioned here and there on Internet
	 * threads complaining about Android BLE so take it with a grain of salt.
	 * 
	 * @see #unbond()
	 */
	public void bond()
	{
		if( isAny(BONDING, BONDED) )  return;
	
		m_queue.add(new P_Task_Bond(this, /*explicit=*/true, /*partOfConnection=*/false, m_taskStateListener));
		
		m_stateTracker.append(BONDING);
	}
	
	/**
	 * Opposite of {@link #bond()}.
	 * 
	 * @see #bond()
	 */
	public void unbond()
	{
		removeBond(null);
	}
	
	/**
	 * Starts a connection process, or does nothing if already {@link BleDeviceState#CONNECTED} or {@link BleDeviceState#CONNECTING}.
	 * Use {@link #setListener_ConnectionFail(ConnectionFailListener)} and {@link #setListener_State(StateListener)} to receive callbacks for progress and errors.
	 */
	public void connect()
	{
		connect((StateListener)null);
	}
	
	/**
	 * Same as {@link #connect()} but calls {@link #setListener_State(StateListener)} for you.
	 */
	public void connect(StateListener stateListener)
	{
		connect(stateListener, null);
	}
	
	/**
	 * Same as {@link #connect()} but calls {@link #setListener_State(StateListener)} and
	 * {@link #setListener_ConnectionFail(ConnectionFailListener)} for you.
	 */
	public void connect(StateListener stateListener, ConnectionFailListener failListener)
	{
		connect(null, null, stateListener, failListener);
	}
	
	/**
	 * Same as {@link #connect()} but provides a hook for the app to do some kind of authentication
	 * handshake if it wishes. This is popular with commercial BLE devices where you don't want 
	 * hobbyists or competitors using your devices for nefarious purposes - like releasing a better
	 * application for your device than you ;-)
	 * 
	 * @see #connect()
	 * @see BleDeviceState#AUTHENTICATING
	 * @see BleDeviceState#AUTHENTICATED
	 */
	public void connectAndAuthenticate(BleTransaction authenticationTxn)
	{
		connectAndAuthenticate(authenticationTxn, null);
	}
	
	/**
	 * Same as {@link #connectAndAuthenticate(BleTransaction)} but calls {@link #setListener_State(StateListener)} for you.
	 */
	public void connectAndAuthenticate(BleTransaction authenticationTxn, StateListener stateListener)
	{
		connectAndAuthenticate(authenticationTxn, stateListener, (ConnectionFailListener) null);
	}
	
	/**
	 * Same as {@link #connectAndAuthenticate(BleTransaction)} but calls {@link #setListener_State(StateListener)}
	 * and {@link #setListener_ConnectionFail(ConnectionFailListener)} for you.
	 */
	public void connectAndAuthenticate(BleTransaction authenticationTxn, StateListener stateListener, ConnectionFailListener failListener)
	{
		connect(authenticationTxn, null, stateListener, failListener);
	}
	
	/**
	 * Same as {@link #connect()} but provides a hook for the app to do some kind of initialization
	 * before it's considered fully {@link BleDeviceState#INITIALIZED}. For example if you had a BLE-enabled thermometer
	 * you could use this transaction to attempt an initial temperature read before updating your UI
	 * to indicate "full" connection success, even though BLE connection itself already succeeded.
	 * 
	 * @see #connect()
	 * @see BleDeviceState#INITIALIZING
	 * @see BleDeviceState#INITIALIZED
	 */
	public void connectAndInitialize(BleTransaction initTxn)
	{
		connectAndInitialize(initTxn, null);
	}
	
	/**
	 * Same as {@link #connectAndInitialize(BleTransaction)} but calls {@link #setListener_State(StateListener)} for you.
	 */
	public void connectAndInitialize(BleTransaction initTxn, StateListener stateListener)
	{
		connectAndInitialize(initTxn, stateListener, (ConnectionFailListener)null);
	}
	
	
	/**
	 * Same as {@link #connectAndInitialize(BleTransaction)} but calls 
	 * {@link #setListener_State(StateListener)} and {@link #setListener_ConnectionFail(ConnectionFailListener)} for you.
	 */
	public void connectAndInitialize(BleTransaction initTxn, StateListener stateListener, ConnectionFailListener failListener)
	{
		connect(null, initTxn, stateListener, failListener);
	}
	
	/**
	 * Combination of {@link #connectAndAuthenticate(BleTransaction)} and {@link #connectAndInitialize(BleTransaction)}.
	 * See those two methods for explanation.
	 * 
	 * @see #connect()
	 * @see #connectAndAuthenticate(BleTransaction)
	 * @see #connectAndInitialize(BleTransaction)
	 */
	public void connect(BleTransaction authenticationTxn, BleTransaction initTxn)
	{
		connect(authenticationTxn, initTxn, (StateListener)null, (ConnectionFailListener)null);
	}
	
	/**
	 * Same as {@link #connect(BleTransaction, BleTransaction)} but calls {@link #setListener_State(StateListener)} for you.
	 */
	public void connect(BleTransaction authenticationTxn, BleTransaction initTxn, StateListener stateListener)
	{
		connect(authenticationTxn, initTxn, stateListener, (ConnectionFailListener)null);
	}
	
	/**
	 * Same as {@link #connect(BleTransaction, BleTransaction)} but calls
	 * {@link #setListener_State(StateListener)} and {@link #setListener_ConnectionFail(ConnectionFailListener)} for you.
	 */
	public void connect(BleTransaction authenticationTxn, BleTransaction initTxn, StateListener stateListener, ConnectionFailListener failListener)
	{
		if( stateListener != null )
		{
			setListener_State(stateListener);
		}
		
		if( failListener != null )
		{
			setListener_ConnectionFail(failListener);
		}
		
		m_connectionFailMngr.onExplicitConnectionStarted();
		
		connect_private(authenticationTxn, initTxn, /*isReconnect=*/false);
	}
	
	/**
	 * Disconnects from a connected device or does nothing if already {@link BleDeviceState#DISCONNECTED}.
	 * You can call this at any point during the connection process as a whole, during
	 * reads and writes, during transactions, whenever, and the device will cleanly
	 * cancel all ongoing operations. This method will also bring the device out of the
	 * {@link BleDeviceState#ATTEMPTING_RECONNECT} state.
	 * 
	 * @see ConnectionFailListener.Reason#EXPLICITLY_CANCELLED
	 */
	public void disconnect()
	{
		disconnectExplicitly(null);
	}
	
	/**
	 * First checks referential equality and if {@link Boolean#FALSE} checks equality of {@link #getMacAddress()}.
	 * Note that ideally this method isn't useful to you and never returns true (besides the identity
	 * case, which isn't useful to you). Otherwise it probably means your app is holding on to old references
	 * that have been undiscovered, and this may be a bug or bad design decision in your code. This library
	 * will (well, should) never hold references to two devices such that this method returns true for them.
	 * 
	 * @see BleManager.DiscoveryListener#onDeviceUndiscovered(BleDevice)
	 */
	public boolean equals(BleDevice device)
	{
		if( device == null )  return false;
	
		if( device == this )  return true;
		
		if( device.getNative() == null || this.getNative() == null )  return false;
		
		return device.getNative().equals(this.getNative());
	}

	/**
	 * Returns {@link #equals(BleDevice)} if object is an instance of {@link BleDevice}.
	 * Otherwise calls super.
	 * 
	 * @see BleDevice#equals(BleDevice)
	 */
	@Override public boolean equals(Object object)
	{
		if( object instanceof BleDevice )
		{
			BleDevice object_cast = (BleDevice) object;
			
			return this.equals(object_cast);
		}
		
		return false;
	}
	
	/**
	 * Starts a periodic read of a particular characteristic. Use this wherever you can in place of {@link #enableNotify(UUID, ReadWriteListener)}.
	 * One use case would be to periodically read wind speed from a weather device. You *could* develop your device firmware to send
	 * notifications to the app only when the wind speed changes, but Android has observed stability issues with notifications,
	 * so use them only when needed.
	 * 
	 * @see #startChangeTrackingPoll(UUID, Interval, ReadWriteListener)
	 * @see #enableNotify(UUID, ReadWriteListener)
	 * @see #stopPoll(UUID, ReadWriteListener)
	 */
	public void startPoll(UUID uuid, Interval interval, ReadWriteListener listener)
	{
		m_pollMngr.startPoll(uuid, interval.seconds, listener, /*trackChanges=*/false, /*usingNotify=*/false);
	}
	
	/**
	 * Similar to {@link #startPoll(UUID, Interval, ReadWriteListener)} but only invokes a
	 * callback when a change in the characteristic value is detected.
	 * Use this in preference to {@link #enableNotify(UUID, ReadWriteListener)()} if possible.
	 */
	public void startChangeTrackingPoll(UUID uuid, Interval interval, ReadWriteListener listener)
	{
		m_pollMngr.startPoll(uuid, interval.seconds, listener, /*trackChanges=*/true, /*usingNotify=*/false);
	}
	
	/**
	 * Stops a poll(s) started by either {@link #startPoll(UUID, Interval, ReadWriteListener)} or
	 * {@link #startChangeTrackingPoll(UUID, Interval, ReadWriteListener)}.
	 * This will stop all polls matching the provided parameters.
	 * 
	 * @see #startPoll(UUID, Interval, ReadWriteListener)
	 * @see #startChangeTrackingPoll(UUID, Interval, ReadWriteListener)
	 */
	public void stopPoll(UUID uuid, ReadWriteListener listener)
	{
		stopPoll_private(uuid, null, listener);
	}
	
	/**
	 * Same as {@link #stopPoll(UUID, ReadWriteListener)} but with added filtering for the poll {@link Interval}.
	 */
	public void stopPoll(UUID uuid, Interval interval, ReadWriteListener listener)
	{
		stopPoll_private(uuid, interval.seconds, listener);
	}
	
	/**
	 * Writes to the device without a callback.
	 * 
	 * @see #write(UUID, byte[], ReadWriteListener)
	 */
	public void write(UUID uuid, byte[] data)
	{
		this.write(uuid, data, (ReadWriteListener)null);
	}
	
	/**
	 * Writes to the device with a callback.
	 * 
	 * @see #write(UUID, byte[])
	 */
	public void write(UUID uuid, byte[] data, ReadWriteListener listener)
	{
		write_internal(uuid, data, new P_WrappingReadWriteListener(listener, m_mngr.m_mainThreadHandler, m_mngr.m_config.postCallbacksToMainThread));
	}
	
	/**
	 * Reads a characteristic from the device.
	 */
	public void read(UUID uuid, ReadWriteListener listener)
	{
		read_internal(uuid, Type.READ, new P_WrappingReadWriteListener(listener, m_mngr.m_mainThreadHandler, m_mngr.m_config.postCallbacksToMainThread));
	}
	
	/**
	 * Enables notification on the given characteristic.
	 */
	public void enableNotify(UUID uuid, ReadWriteListener listener)
	{
		this.enableNotify(uuid, Interval.INFINITE, listener);
	}
	
	/**
	 * Same as {@link #enableNotify(UUID, ReadWriteListener)} but forces a read after a given amount of time.
	 * If we haven't received a notification in some time it may be an indication that notifications have broken,
	 * in the underlying stack.
	 */
	public void enableNotify(UUID uuid, Interval forceReadTimeout, ReadWriteListener listener)
	{
		Result earlyOutResult = m_serviceMngr.getEarlyOutResult(uuid, EMPTY_BYTE_ARRAY, Type.ENABLING_NOTIFICATION);
		
		if( earlyOutResult != null )
		{
			if( listener != null )
			{
				listener.onReadOrWriteComplete(earlyOutResult);
			}
			
			return;
		}
		
		P_Characteristic characteristic = m_serviceMngr.getCharacteristic(uuid);
		
		if( characteristic != null && is(CONNECTED) )
		{
			P_WrappingReadWriteListener wrappingListener = new P_WrappingReadWriteListener(listener, m_mngr.m_mainThreadHandler, m_mngr.m_config.postCallbacksToMainThread);
			m_queue.add(new P_Task_ToggleNotify(characteristic, /*enable=*/true, wrappingListener));
		}
		
		m_pollMngr.startPoll(uuid, forceReadTimeout.seconds, listener, /*trackChanges=*/true, /*usingNotify=*/true);
	}	
	
	/**
	 * Disables all notifications enabled by {@link #enableNotify(UUID, ReadWriteListener)} or 
	 * {@link #enableNotify(UUID, Interval, ReadWriteListener)}.
	 */
	public void disableNotify(UUID uuid, ReadWriteListener listener)
	{
		this.disableNotify_private(uuid, null, listener);
	}
	
	/**
	 * Same as {@link #disableNotify(UUID, ReadWriteListener)} but filters on the given {@link Interval}.
	 */
	public void disableNotify(UUID uuid, Interval forceReadTimeout, ReadWriteListener listener)
	{
		this.disableNotify_private(uuid, forceReadTimeout.seconds, listener);
	}
	
	/**
	 * Kicks off a firmware update transaction if it's not already taking place and the device is {@link BleDeviceState#INITIALIZED}.
	 * This will put the device into the {@link BleDeviceState#UPDATING_FIRMWARE} state.
	 * 
	 * @return	{@link Boolean#TRUE} if firmware update has started, otherwise {@link Boolean#FALSE} if device is either already
	 * 			{@link BleDeviceState#UPDATING_FIRMWARE} or is not {@link BleDeviceState#INITIALIZED}.
	 * 	
	 * @see BleManagerConfig#includeFirmwareUpdateReadWriteTimesInAverage
	 * @see BleManagerConfig#autoScanDuringFirmwareUpdates
	 */
	public boolean updateFirmware(BleTransaction txn)
	{
		if( is(UPDATING_FIRMWARE) )  return false;
		if( !is(INITIALIZED) )  return false;
		
		m_txnMngr.onFirmwareUpdate(txn);
		
		return true;
	}
	
	/**
	 * Returns the device's name and current state for logging and debugging purposes.
	 */
	@Override public String toString()
	{
		return getDebugName() + " " + m_stateTracker.toString();
	}
	
	private boolean addOperationTime()
	{
		return m_mngr.m_config.includeFirmwareUpdateReadWriteTimesInAverage || !is(UPDATING_FIRMWARE);
	}
	
	void addReadTime(double timeStep)
	{
		if( !addOperationTime() )  return;
		
		m_readTimeEstimator.addTime(timeStep);
	}
	
	void addWriteTime(double timeStep)
	{
		if( !addOperationTime() )  return;
		
		m_writeTimeEstimator.addTime(timeStep);
	}
	
	void setToAlwaysUseAutoConnectIfItWorked()
	{
		m_alwaysUseAutoConnect = m_useAutoConnect;
	}
	
	boolean shouldUseAutoConnect()
	{
		return m_useAutoConnect;
	}
	
	P_BleDevice_Listeners getListeners()
	{
		return m_listeners;
	}
	
	P_TaskQueue getTaskQueue(){						return m_queue;								}
	PA_StateTracker getStateTracker(){				return m_stateTracker;						}
	BleTransaction getFirmwareUpdateTxn(){			return m_txnMngr.m_firmwareUpdateTxn;		}
	P_PollManager getPollManager(){					return m_pollMngr;							}
	P_ServiceManager getServiceManager(){			return m_serviceMngr;						}
	
	void onNewlyDiscovered(List<UUID> advertisedServices_nullable, int rssi, byte[] scanRecord_nullable)
	{
		onDiscovered_private(advertisedServices_nullable, rssi, scanRecord_nullable);
		
		if( m_mngr.m_config.removeBondOnDiscovery )
		{
			m_stateTracker.update
			(
				BONDING,		false,
				BONDED,			false,
				UNBONDED,		true,
				UNDISCOVERED,	false,
				DISCOVERED,		true,
				ADVERTISING,	true,
				DISCONNECTED,	true
			);
			
			m_queue.add(new P_Task_Unbond(this, m_taskStateListener));
		}
		else
		{
			m_stateTracker.update
			(
				BONDING,		m_nativeWrapper.isNativelyBonding(),
				BONDED,			m_nativeWrapper.isNativelyBonded(),
				UNBONDED,		m_nativeWrapper.isNativelyUnbonded(),
				UNDISCOVERED,	false,
				DISCOVERED,		true,
				ADVERTISING,	true,
				DISCONNECTED,	true
			);
		}
	}
	
	void onRediscovered(List<UUID> advertisedServices_nullable, int rssi, byte[] scanRecord_nullable)
	{
		onDiscovered_private(advertisedServices_nullable, rssi, scanRecord_nullable);
		
		m_stateTracker.update
		(
			BONDING,		m_nativeWrapper.isNativelyBonding(),
			BONDED,			m_nativeWrapper.isNativelyBonded()
		);
	}
	
	void onUndiscovered()
	{
		m_reconnectMngr.stop();
		
		m_stateTracker.set
		(
			UNDISCOVERED,	true,
			DISCOVERED,		false,
			ADVERTISING,	false,
			BONDING,		m_nativeWrapper.isNativelyBonding(),
			BONDED,			m_nativeWrapper.isNativelyBonded(),
			UNBONDED,		m_nativeWrapper.isNativelyUnbonded()
		);
	}
	
	double getTimeSinceLastDiscovery()
	{
		return m_timeSinceLastDiscovery;
	}
	
	private void onDiscovered_private(List<UUID> advertisedServices_nullable, int rssi, byte[] scanRecord_nullable)
	{
		m_timeSinceLastDiscovery = 0.0;
		m_rssi = rssi;
		m_advertisedServices = advertisedServices_nullable == null || advertisedServices_nullable.size() == 0 ? m_advertisedServices : advertisedServices_nullable;
		m_scanRecord = scanRecord_nullable != null ? scanRecord_nullable : m_scanRecord;
	}
	
	void update(double timeStep)
	{
		m_timeSinceLastDiscovery += timeStep;
		
		m_pollMngr.update(timeStep);
		m_txnMngr.update(timeStep);
		m_reconnectMngr.update(timeStep);
	}
	
	void removeBond(PE_TaskPriority priority)
	{
		m_queue.add(new P_Task_Unbond(this, m_taskStateListener, priority));
		
		m_stateTracker.update(BONDED, false, BONDING, false, UNBONDED, true);
	}
	
	void onBondTaskStateChange(PA_Task task, PE_TaskState state)
	{
		if( task.getClass() == P_Task_Bond.class )
		{
			if( state.isEndingState() )
			{
				if( state == PE_TaskState.SUCCEEDED )
				{
					this.onNativeBond();
				}
				else
				{
					this.onNativeBondFailed();
				}
			}
		}
		else if( task.getClass() == P_Task_Unbond.class )
		{
			if( state == PE_TaskState.SUCCEEDED )
			{
				this.onNativeUnbond();
			}
			else
			{
				// not sure what to do here, if anything
			}
		}
	}
	
	void onNativeUnbond()
	{
		m_stateTracker.update(BONDED, false, BONDING, false, UNBONDED, true);
	}
	
	void onNativeBonding()
	{
		m_stateTracker.update(BONDED, false, BONDING, true, UNBONDED, false);
	}
	
	void onNativeBond()
	{
		m_stateTracker.update(BONDED, true, BONDING, false, UNBONDED, false);
	}
	
	void onNativeBondFailed()
	{
		m_stateTracker.update(BONDED, false, BONDING, false, UNBONDED, true);
	}
	
	void attemptReconnect()
	{
		connect_private(m_txnMngr.m_authTxn, m_txnMngr.m_initTxn, /*isReconnect=*/true);
	}
	
	private void connect_private(BleTransaction authenticationTxn, BleTransaction initTxn, boolean isReconnect)
	{
		if( isAny(CONNECTED, CONNECTING, CONNECTING_OVERALL))  return;
		
		if( is(INITIALIZED) )
		{
			m_mngr.ASSERT(false, "Device is initialized but not connected!");
			
			return;
		}
	
		m_txnMngr.onConnect(authenticationTxn, initTxn);
		
		m_queue.add(new P_Task_Connect(this, m_taskStateListener));
		
		onConnecting(/*definitelyExplicit=*/true, isReconnect);
	}
	
	void onConnecting(boolean definitelyExplicit, boolean isReconnect)
	{
		if( is(/*already*/CONNECTING) )
		{
			P_Task_Connect task = getTaskQueue().getCurrent(P_Task_Connect.class, this);
			boolean mostDefinitelyExplicit = task != null && task.isExplicit();
			
			//--- DRK > Not positive about this assert...we'll see if it trips.
			m_mngr.ASSERT(definitelyExplicit || mostDefinitelyExplicit);
		}
		else
		{
			if( definitelyExplicit && !isReconnect )
			{
				//--- DRK > We're stopping the reconnect process (if it's running) because the user has decided to explicitly connect
				//---		for whatever reason. Making a judgement call that the user would then expect reconnect to stop.
				//---		In other words it's not stopped for any hard technical reasons...it could go on.
				m_reconnectMngr.stop();
				m_stateTracker.update(ATTEMPTING_RECONNECT, false, CONNECTING, true, CONNECTING_OVERALL, true, DISCONNECTED, false, ADVERTISING, false);
			}
			else
			{
				m_stateTracker.update(CONNECTING, true, CONNECTING_OVERALL, true, DISCONNECTED, false, ADVERTISING, false);
			}
		}
	}
	
	private boolean isBondingOrBonded()
	{
		//--- DRK > These asserts are here because, as far as I could discern from logs, the abstracted
		//---		state for bonding/bonded was true, but when we did an encrypted write, it kicked
		//---		off a bonding operation, implying that natively the bonding state silently changed
		//---		since we discovered the device. I really don't know.
		//---		UPDATE: Nevermind, the reason bonding wasn't happening after connection was because
		//---				it was using the default config option of false. Leaving asserts here anywway
		//---				cause they can't hurt.
		//---		UPDATE AGAIN: Actually, these asserts can hit if you're connected to a device, you go
		//---		into OS settings, unbond, which kicks off an implicit disconnect which then kicks off
		//---		an implicit reconnect...race condition makes it so that you can query the bond state
		//---		and get its updated value before the bond state callback gets sent
		//---		UPDATE AGAIN AGAIN: Nevermind, it seems getBondState *can* actually lie, so original comment sorta stands...wow.
//		m_mngr.ASSERT(m_stateTracker.checkBitMatch(BONDED, isNativelyBonded()));
//		m_mngr.ASSERT(m_stateTracker.checkBitMatch(BONDING, isNativelyBonding()));
		
		return m_nativeWrapper.isNativelyBonded() || m_nativeWrapper.isNativelyBonding();
	}
	
	void onNativeConnect()
	{
		if( is(/*already*/CONNECTED) )
		{
			//--- DRK > Possible to get here when implicit tasks are involved I think. Not sure if assertion should be here,
			//---		and if it should it perhaps should be keyed off whether the task is implicit or something.
			//---		Also possible to get here for example on connection fail retries, where we queue a disconnect
			//---		but that gets immediately soft-cancelled by what will be a redundant connect task.
			//---		OVERALL, This assert is here because I'm just curious how it hits (it does).
			String message = "nativelyConnected=" + m_logger.gattConn(m_nativeWrapper.getConnectionState()) + " gatt==" + m_nativeWrapper.getGatt();
//			m_mngr.ASSERT(false, message);
			m_mngr.ASSERT(m_nativeWrapper.isNativelyConnected(), message);
			
			return;
		}
		
		m_mngr.ASSERT(m_nativeWrapper.getGatt() != null);
		
		//--- DRK > There exists a fringe case like this: You try to connect with autoConnect==true in the gatt object.
		//---		The connection fails, so you stop trying. Then you turn off the remote device. Device gets "undiscovered".
		//---		You turn the device back on, and apparently underneath the hood, this whole time, the stack has been trying
		//---		to reconnect, and now it does, *without* (re)discovering the device first, or even discovering it at all.
		//---		So as usual, here's another gnarly workaround to ensure a consistent API experience through SweetBlue.
		//---
		//---		NOTE: We do explicitly disconnect after a connection failure if we're using autoConnect, so this
		//---				case shouldn't really come up much or at all with that in place.
		if( !m_mngr.hasDevice(getMacAddress()) )
		{
			m_mngr.onDiscovered_wrapItUp(this, /*newlyDiscovered=*/true, m_advertisedServices, getScanRecord(), getRssi());
		}
		
		//--- DRK > Some trapdoor logic for bad android ble bug.
		int nativeBondState = m_nativeWrapper.getNativeBondState();
		if( nativeBondState == BluetoothDevice.BOND_BONDED )
		{
			//--- DRK > Trying to catch fringe condition here of stack lying to us about bonded state.
			//---		This is not about finding a logic error in my code.
			m_mngr.ASSERT(m_mngr.getNative().getAdapter().getBondedDevices().contains(m_nativeWrapper.getDevice()));
		}
		m_logger.d(m_logger.gattBondState(m_nativeWrapper.getNativeBondState()));
		
		
		boolean bond = m_mngr.m_config.autoBondAfterConnect && !isBondingOrBonded();
		
		if( bond )
		{
			m_queue.add(new P_Task_Bond(this, /*explicit=*/true, /*partOfConnection=*/true, new PA_Task.I_StateListener()
			{
				@Override public void onStateChange(PA_Task task, PE_TaskState state)
				{
					if( state.isEndingState() )
					{
						if(state == PE_TaskState.SUCCEEDED )
						{
							if( m_mngr.m_config.autoGetServices )
							{
								getServices(BONDING, false, BONDED, true);
							}
							else
							{
								m_txnMngr.runAuthOrInitTxnIfNeeded(BONDING, false, BONDED, true);
							}
						}
						else if( state == PE_TaskState.SOFTLY_CANCELLED )
						{
							//--- DRK > This actually means native bonding succeeded but since it was "cancelled"
							//---		by a disconnect we don't update the state tracker.
//								onNativeBond();
						}
						else
						{
							if( m_mngr.m_config.autoGetServices )
							{
								getServices(BONDING, false, BONDED, false);
							}
							else
							{
								m_txnMngr.runAuthOrInitTxnIfNeeded(BONDING, false, BONDED, false);
							}
						}
					}
				}
			}));

			m_stateTracker.update
			(
				DISCONNECTED,false, CONNECTING_OVERALL,true, CONNECTING,false, CONNECTED,true, BONDING,true, ADVERTISING,false
			);
		}
		else
		{
			if( m_mngr.m_config.autoGetServices )
			{
				getServices(DISCONNECTED,false, CONNECTING_OVERALL,true, CONNECTING,false, CONNECTED,true, ADVERTISING,false);
			}
			else
			{
				m_txnMngr.runAuthOrInitTxnIfNeeded(DISCONNECTED,false, CONNECTING_OVERALL,true, CONNECTING,false, CONNECTED,true, ADVERTISING,false);
			}
		}
	}
	
	private void getServices(Object ... extraFlags)
	{
		if( !m_nativeWrapper.isNativelyConnected() )
		{
			//--- DRK > This accounts for certain fringe cases, for example the Nexus 5 log when you unbond a device from the OS settings while it's connected:
//			07-03 12:53:49.489: D/BluetoothGatt(11442): onClientConnectionState() - status=0 clientIf=5 device=D4:81:CA:00:1D:61
//			07-03 12:53:49.499: I/BleDevice_Listeners(11442): FAY(11538) onConnectionStateChange() - GATT_SUCCESS(0) STATE_DISCONNECTED(0)
//			07-03 12:53:49.759: I/BleManager_Listeners(11442): AMY(11442) onNativeBondStateChanged() - previous=BOND_BONDED(12) new=BOND_NONE(10)
//			07-03 12:53:54.299: D/BluetoothGatt(11442): onClientConnectionState() - status=0 clientIf=5 device=D4:81:CA:00:1D:61
//			07-03 12:53:54.299: I/BleDevice_Listeners(11442): CAM(11453) onConnectionStateChange() - GATT_SUCCESS(0) STATE_CONNECTED(2)
//			07-03 12:53:54.299: D/BleDevice(11442): CAM(11453) getNativeBondState() - BOND_NONE(10)
//			07-03 12:53:54.309: D/BleDevice(11442): CAM(11453) getNativeBondState() - BOND_NONE(10)
//			07-03 12:53:54.309: I/A_BtTask(11442): CAM(11453) setState() - BtTask_Bond(CREATED)
//			07-03 12:53:54.309: I/A_BtTask(11442): CAM(11453) setState() - BtTask_Bond(QUEUED) - 4032
//			07-03 12:53:54.309: I/BtTaskQueue(11442): CAM(11453) printQueue() - null [BtTask_Bond(QUEUED)]
//			07-03 12:53:54.309: D/BluetoothManager(11442): getConnectionState()
//			07-03 12:53:54.319: D/BluetoothManager(11442): getConnectedDevices
//			07-03 12:53:54.329: I/A_BtTask(11442): BEN(11488) setState() - BtTask_Bond(ARMED) - 4032
//			07-03 12:53:54.339: I/BtTaskQueue(11442): BEN(11488) printQueue() - BtTask_Bond(ARMED) [ empty ]
//			07-03 12:53:54.379: I/A_BtTask(11442): BEN(11488) setState() - BtTask_Bond(EXECUTING) - 4033
//			07-03 12:53:54.379: D/BleDevice(11442): GUS(11487) getNativeBondState() - BOND_NONE(10)
//			07-03 12:53:54.379: D/BleDevice(11442): GUS(11487) getNativeBondState() - BOND_NONE(10)
//			07-03 12:53:54.419: I/BleManager_Listeners(11442): AMY(11442) onNativeBondStateChanged() - previous=BOND_NONE(10) new=BOND_BONDING(11)
//			07-03 12:53:54.599: D/BluetoothGatt(11442): onClientConnectionState() - status=133 clientIf=5 device=D4:81:CA:00:1D:61
//			07-03 12:53:54.599: W/BleDevice_Listeners(11442): FAY(11538) onConnectionStateChange() - UNKNOWN_STATUS(133) STATE_DISCONNECTED(0)
//			07-03 12:53:54.599: I/BleManager_Listeners(11442): AMY(11442) onNativeBondStateChanged() - previous=BOND_BONDING(11) new=BOND_NONE(10)
//			07-03 12:53:54.599: I/A_BtTask(11442): AMY(11442) setState() - BtTask_Bond(FAILED) - 4042
//			07-03 12:53:54.609: I/A_BtTask(11442): AMY(11442) setState() - BtTask_DiscoverServices(CREATED)
//			07-03 12:53:54.609: I/A_BtTask(11442): AMY(11442) setState() - BtTask_DiscoverServices(QUEUED) - 4042
//			07-03 12:53:54.609: I/BtTaskQueue(11442): AMY(11442) printQueue() - null [BtTask_DiscoverServices(QUEUED)]

			return;
		}
		
		m_serviceMngr.clear();
		m_queue.add(new P_Task_DiscoverServices(this, m_taskStateListener));
		
		//--- DRK > We check up top, but check again here cause we might have been disconnected on another thread in the mean time.
		//---		Even without this check the library should still be in a goodish state. Might send some weird state
		//---		callbacks to the app but eventually things settle down and we're good again.
		if( m_nativeWrapper.isNativelyConnected() )
		{
			m_stateTracker.update(extraFlags, GETTING_SERVICES, true);
		}
	}
	
	void onConnectFail(PE_TaskState state)
	{
		if( state == PE_TaskState.SOFTLY_CANCELLED )  return;
	
		boolean attemptingReconnect = is(ATTEMPTING_RECONNECT);
		
		if( !m_nativeWrapper.isNativelyConnected() )
		{
//			if( !attemptingReconnect )
			{
				m_nativeWrapper.closeGattIfNeeded(/*disconnectAlso=*/true);
			}
		}
		
		boolean wasConnecting = is(CONNECTING_OVERALL);
		
		if( isAny(CONNECTED, CONNECTING, CONNECTING_OVERALL) )
		{
			setStateToDisconnected(attemptingReconnect, /*fromBleCallback=*/false);
		}
		
		if( wasConnecting )
		{
			ConnectionFailListener.Reason reason = ConnectionFailListener.Reason.NATIVE_CONNECTION_FAILED;
			
			if( state == PE_TaskState.TIMED_OUT )
			{
				reason = Reason.NATIVE_CONNECTION_TIMED_OUT;
			}

			Please retry = m_connectionFailMngr.onConnectionFailed(reason, attemptingReconnect);
			
			if( !attemptingReconnect && retry == Please.RETRY )
			{
				m_useAutoConnect = true;
			}
			else
			{				
				m_useAutoConnect = m_alwaysUseAutoConnect;
			}
		}
	}
	
	void onServicesDiscovered()
	{
		m_serviceMngr.clear();
		m_serviceMngr.loadDiscoveredServices();
		
		m_pollMngr.enableNotifications();
		
		m_txnMngr.runAuthOrInitTxnIfNeeded(GETTING_SERVICES, false);
	}
	
	void onFullyInitialized(Object ... extraFlags)
	{
		m_mngr.onConnectionSucceeded();
		m_reconnectMngr.stop();
		m_connectionFailMngr.onFullyInitialized();
		
		m_stateTracker.update
		(
			extraFlags, ATTEMPTING_RECONNECT, false, CONNECTING_OVERALL, false,
			AUTHENTICATING, false, AUTHENTICATED, true, INITIALIZING, false, INITIALIZED, true
		);
	}
	
	private void setStateToDisconnected(boolean attemptingReconnect, boolean fromBleCallback)
	{
		//--- DRK > Device probably wasn't advertising while connected so here we reset the timer to keep
		//---		it from being immediately undiscovered after disconnection.
		m_timeSinceLastDiscovery = 0.0;
		
		m_serviceMngr.clear();
		m_txnMngr.clearQueueLock();
		
		if( fromBleCallback && m_mngr.m_config.removeBondOnDisconnect )
		{
			m_stateTracker.set
			(
				DISCOVERED, true,
				DISCONNECTED, true,
				BONDING, false,
				BONDED, false,
				UNBONDED, true,
				ATTEMPTING_RECONNECT, attemptingReconnect,
				ADVERTISING, !attemptingReconnect
			);
			
			m_queue.add(new P_Task_Unbond(this, m_taskStateListener));
		}
		else
		{
			m_stateTracker.set
			(
				DISCOVERED, true,
				DISCONNECTED, true,
				BONDING, m_nativeWrapper.isNativelyBonding(),
				BONDED, m_nativeWrapper.isNativelyBonded(),
				UNBONDED, m_nativeWrapper.isNativelyUnbonded(),
				ATTEMPTING_RECONNECT, attemptingReconnect,
				ADVERTISING, !attemptingReconnect
			);
		}
	}
	
	void disconnectExplicitly(PE_TaskPriority priority)
	{
		m_useAutoConnect = m_alwaysUseAutoConnect;
		
		m_connectionFailMngr.onExplicitDisconnect();
		
		disconnectWithReason(priority, ConnectionFailListener.Reason.EXPLICITLY_CANCELLED);
	}
	
	void disconnectWithReason(ConnectionFailListener.Reason connectionFailReasonIfConnecting)
	{
		disconnectWithReason(null, connectionFailReasonIfConnecting);
	}
	
	private void disconnectWithReason(PE_TaskPriority disconnectPriority_nullable, ConnectionFailListener.Reason connectionFailReasonIfConnecting)
	{
		boolean wasConnecting = is(CONNECTING_OVERALL);
		boolean attemptingReconnect = is(ATTEMPTING_RECONNECT);
		
		if( connectionFailReasonIfConnecting == Reason.EXPLICITLY_CANCELLED )
		{
			attemptingReconnect = false;
		}
		
		if( isAny(CONNECTED, CONNECTING_OVERALL, INITIALIZED) )
		{
			setStateToDisconnected(attemptingReconnect, /*fromBleCallback=*/false);
			
			m_txnMngr.cancelAllTransactions();
//				m_txnMngr.clearAllTxns();
			
			if( !attemptingReconnect )
			{
				m_reconnectMngr.stop();
			}
		}
		else
		{
			if( !attemptingReconnect )
			{
				m_stateTracker.update(ATTEMPTING_RECONNECT, false);
				m_reconnectMngr.stop();
			}
		}
		
		m_queue.add(new P_Task_Disconnect(this, m_taskStateListener, /*explicit=*/true, disconnectPriority_nullable));
		
		if( wasConnecting )
		{
			if( m_mngr.ASSERT(connectionFailReasonIfConnecting != null ) )
			{
				m_connectionFailMngr.onConnectionFailed(connectionFailReasonIfConnecting, attemptingReconnect);
			}
		}
	}
	
	void onDisconnecting()
	{
	}
	
	void onNativeDisconnect(boolean wasExplicit)
	{
//		if( m_state.ordinal() < E_State.CONNECTING.ordinal() )
//		{
//			m_logger.w("Already disconnected!");
//			
//			m_mngr.ASSERT(m_gatt == null);
//			
//			return;
//		}
		
		boolean attemptingReconnect = false;
		
		m_nativeWrapper.closeGattIfNeeded(/*disconnectAlso=*/false);
		
		if( !wasExplicit )
		{
			if( is(INITIALIZED) )
			{
				m_reconnectMngr.start();
				attemptingReconnect = m_reconnectMngr.isRunning();
			}
		}
		else
		{
			m_connectionFailMngr.onExplicitDisconnect();
		}
		
		ConnectionFailListener.Reason connectionFailReason_nullable = null;
		
		if( is(CONNECTING_OVERALL) )
		{
			if( m_mngr.ASSERT(!wasExplicit) )
			{
				connectionFailReason_nullable = ConnectionFailListener.Reason.ROGUE_DISCONNECT;
			}
		}
		
		attemptingReconnect = attemptingReconnect || is(ATTEMPTING_RECONNECT);
		
		setStateToDisconnected(attemptingReconnect, /*fromBleCallback=*/true);
		
		if( !attemptingReconnect )
		{
			//--- DRK > Should be overkill cause disconnect call itself should have cleared these.
			m_txnMngr.cancelAllTransactions();
//			m_txnMngr.clearAllTxns();
		}
		else
		{
			m_txnMngr.cancelAllTransactions();
//			m_txnMngr.clearFirmwareUpdateTxn();
		}
		
		Please retrying = m_connectionFailMngr.onConnectionFailed(connectionFailReason_nullable, attemptingReconnect);
		
		//--- DRK > Not actually entirely sure how, it may be legitimate, but a connect task can still be
		//---		hanging out in the queue at this point, so we just make sure to clear the queue as a failsafe.
		//---		TODO: Understand the conditions under which a connect task can still be queued...might be a bug upstream.
		if( retrying == Please.DO_NOT_RETRY )
		{
			m_queue.clearQueueOf(P_Task_Connect.class, this);
		}
	}
	
	private void stopPoll_private(UUID uuid, Double interval, ReadWriteListener listener)
	{
		m_pollMngr.stopPoll(uuid, interval, listener, /*usingNotify=*/false);
	}
	
	void read_internal(UUID uuid, Type type, P_WrappingReadWriteListener listener)
	{
		Result earlyOut = m_serviceMngr.getEarlyOutResult(uuid, EMPTY_BYTE_ARRAY, type);
		
		if( earlyOut != null )
		{
			if( listener != null )
			{
				listener.onReadOrWriteComplete(earlyOut);
			}
			
			return;
		}
		
		P_Characteristic characteristic = m_serviceMngr.getCharacteristic(uuid);
		
		read_internal(characteristic, type, listener);
	}
	
	void read_internal(P_Characteristic characteristic, Type type, P_WrappingReadWriteListener listener)
	{
		boolean requiresBonding = requiresBonding(characteristic);
		
		m_queue.add(new P_Task_Read(characteristic, type, requiresBonding, listener, m_txnMngr.getCurrent(), getOverrideReadWritePriority()));
	}
	
	void write_internal(UUID uuid, byte[] data, P_WrappingReadWriteListener listener)
	{
		Result earlyOutResult = m_serviceMngr.getEarlyOutResult(uuid, data, Type.WRITE);
		
		if( earlyOutResult != null )
		{
			if( listener != null )
			{
				listener.onReadOrWriteComplete(earlyOutResult);
			}
			
			return;
		}
		
		P_Characteristic characteristic = m_serviceMngr.getCharacteristic(uuid);
		
		boolean requiresBonding = requiresBonding(characteristic);
		
		m_queue.add(new P_Task_Write(characteristic, data, requiresBonding, listener, m_txnMngr.getCurrent(), getOverrideReadWritePriority()));
	}
	
	private void disableNotify_private(UUID uuid, Double forceReadTimeout, ReadWriteListener listener)
	{
		Result earlyOutResult = m_serviceMngr.getEarlyOutResult(uuid, EMPTY_BYTE_ARRAY, Type.DISABLING_NOTIFICATION);
		
		if( earlyOutResult != null )
		{
			if( listener != null )
			{
				listener.onReadOrWriteComplete(earlyOutResult);
			}
			
			return;
		}
		
		P_Characteristic characteristic = m_serviceMngr.getCharacteristic(uuid);
		
		if( characteristic != null && is(CONNECTED) )
		{
			P_WrappingReadWriteListener wrappingListener = new P_WrappingReadWriteListener(listener, m_mngr.m_mainThreadHandler, m_mngr.m_config.postCallbacksToMainThread);
			m_queue.add(new P_Task_ToggleNotify(characteristic, /*enable=*/false, wrappingListener));
		}
		
		m_pollMngr.stopPoll(uuid, forceReadTimeout, listener, /*usingNotify=*/true);
	}
	
	private boolean requiresBonding(P_Characteristic characteristic)
	{
		if( m_mngr.m_config.bondingFilter == null )  return false;
		
		return m_mngr.m_config.bondingFilter.requiresBonding(characteristic.getUuid());
	}
	
	private PE_TaskPriority getOverrideReadWritePriority()
	{
		if( isAny(AUTHENTICATING, INITIALIZING) )
		{
			m_mngr.ASSERT(m_txnMngr.getCurrent() != null);
			
			return PE_TaskPriority.FOR_PRIORITY_READS_WRITES; 
		}
		else
		{
			return PE_TaskPriority.FOR_NORMAL_READS_WRITES;
		}
	}
}