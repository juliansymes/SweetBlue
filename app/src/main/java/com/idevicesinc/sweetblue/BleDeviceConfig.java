package com.idevicesinc.sweetblue;


public class BleDeviceConfig extends BleNodeConfig implements Cloneable
{

    public boolean cacheDeviceOnUndiscovery                     = true;

    /**
     * Default is <code>false</code> - whether to use <code>BluetoothGatt.refresh()</code> right before service discovery.
     * This method is not in the public Android API, so its use is disabled by default. You may find it useful to enable
     * if your remote device is routinely changing its gatt service profile. This method call supposedly clears a cache
     * that would otherwise prevent changes from being discovered.
     */
    public Boolean useGattRefresh								= false;


    @Override protected BleDeviceConfig clone()
    {
        return (BleDeviceConfig) super.clone();
    }
}