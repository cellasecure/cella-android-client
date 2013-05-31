/*
 * Copyright 2013 CellaSecure
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package edu.washington.cs.cellasecure;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import edu.washington.cs.cellasecure.bluetooth.Connection;
import edu.washington.cs.cellasecure.bluetooth.Connection.OnResponseListener;
import edu.washington.cs.cellasecure.bluetooth.DeviceConfiguration;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

public class Drive implements Parcelable {

    private static final String TAG = "Drive";

    // BEGIN PARCELABLE ///////////////////////////////////////////////////////

    public static final String KEY_BUNDLE_DRIVE = "drive";

    @SuppressWarnings("UnusedDeclaration")
    public static final Parcelable.Creator<Drive> CREATOR = new Parcelable.Creator<Drive>() {
        public Drive createFromParcel(Parcel in) {
            return new Drive(in);
        }

        public Drive[] newArray(int size) {
            return new Drive[size];
        }
    };

    private Drive(Parcel in) {
        mName = in.readString();
        mDevice = in.readParcelable(null);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mName);
        dest.writeParcelable(mDevice, flags);
    }

    // END PARCELABLE /////////////////////////////////////////////////////////

    private static final String BT_DEV_DEFAULT_NAME = "Unnamed";

    private String mName;
    private BluetoothDevice mDevice;
    private Connection mConnection;

    private OnConnectListener mOnConnectListener;
    private OnLockQueryResultListener mOnLockQueryResultListener;
    private OnLockStateChangeListener mOnLockStateChangeListener;

    public Drive(BluetoothDevice bt) {
        this(bt.getName(), bt);
    }


    public Drive(String name, String address) {
        this(name, BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address));
    }

    public Drive(String name, BluetoothDevice bt) {
        mName = (name == null || name.isEmpty()) ? BT_DEV_DEFAULT_NAME : name;
        mDevice = bt;
    }

    public String getAddress() {
        return mDevice.getAddress();
    }

    public String getName() {
        return mName;
    }

    public BluetoothDevice getDevice() {
        return mDevice;
    }

    // BEGIN BLUETOOTH ////////////////////////////////////////////////////////

    public static final boolean STATUS_LOCKED = true;
    public static final boolean STATUS_UNLOCKED = false;

    private static final byte RESPONSE_OKAY_BYTE = 'K';
    private static final byte RESPONSE_BAD_BYTE = '~';

    private static final byte[] LOCK_STATE_QUERY_BYTES = {'?'};
    private static final byte[] CONFIG_REQUEST_BYTES = {'g'};
    private static final byte[] CONFIG_SEND_BYTES = {'c'};

    private static final int LOCK_STATE_QUERY_RESPONSE_SIZE = 2;
    private static final int YES_NO_QUERY_RESPONSE_SIZE = 1;

    private static final byte PASSWD_SEND_BYTE = 'p';
    private static final int PASSWD_MAX_LENGTH = 32;

    public void setOnConnectListener(OnConnectListener listener) {
        mOnConnectListener = listener;
    }

    public void setOnLockQueryResultListener(OnLockQueryResultListener listener) {
        mOnLockQueryResultListener = listener;
    }

    public void setOnLockStateChangeListener(OnLockStateChangeListener listener) {
        mOnLockStateChangeListener = listener;
    }

    public void connect() {
        new DriveConnectTask(this, mOnConnectListener).run();
    }

    public boolean isConnected() {
        return mConnection != null && mConnection.isConnected();
    }

    public void unlock(String password) {
        mConnection.setOnResponseListener(new OnResponseListener() {
            private static final String TAG = "UnlockListener";

            @Override
            public void onResponse(byte[] message, IOException e) {
                if (e != null) {
                    Log.e(TAG, "Unlock request failure", e);
                    if (mOnLockStateChangeListener != null) {
                        mOnLockStateChangeListener.onLockStateChanged(STATUS_LOCKED, e);
                    }

                    boolean success = message[0] == RESPONSE_OKAY_BYTE;
                    if (success && mOnLockStateChangeListener != null) {
                        mOnLockStateChangeListener.onLockStateChanged(STATUS_UNLOCKED, null);
                    } else if (mOnLockStateChangeListener != null) {
                        mOnLockStateChangeListener.onLockStateChanged(STATUS_LOCKED,
                                new IOException("Bad password during request"));
                    }
                }
            }
        });
        byte[] message = new byte[PASSWD_MAX_LENGTH + 1];
        message[0] = PASSWD_SEND_BYTE;
        System.arraycopy(password.getBytes(), 0, message, 1, PASSWD_MAX_LENGTH);
        mConnection.send(message, YES_NO_QUERY_RESPONSE_SIZE);
    }

    public void queryLockStatus() {
        mConnection.setOnResponseListener(new OnResponseListener() {
            private static final String TAG = "QueryLockStatus";

            @Override
            public void onResponse(byte[] message, IOException e) {
                if (e != null) {
                    Log.e(TAG, "Send failure", e);
                    if (mOnLockQueryResultListener != null) {
                        mOnLockQueryResultListener.onLockQueryResult(STATUS_LOCKED, e);
                    }
                }

                if (message[0] != LOCK_STATE_QUERY_BYTES[0]) {
                    Log.e(TAG, "Invalid response");
                    if (mOnLockQueryResultListener != null) {
                        mOnLockQueryResultListener.onLockQueryResult(STATUS_LOCKED,
                                new IOException("Invalid response: " + Arrays.toString(message)));
                    }
                }

                if (mOnLockQueryResultListener != null) {
                    mOnLockQueryResultListener.onLockQueryResult(message[1] == 'L', null);
                }
            }
        });
        mConnection.send(LOCK_STATE_QUERY_BYTES, LOCK_STATE_QUERY_RESPONSE_SIZE);
    }

    public void disconnect() {
        try {
            mConnection.close();
        } catch (IOException e) {
            Log.e(TAG, "Error disconnecting", e);
        }
    }

    private static class DriveConnectTask implements Runnable {
        private static final String TAG = "DriveConnectTask";

        private static final UUID BT_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

        private final Drive mDrive;
        private final BluetoothSocket mSocket;
        private OnConnectListener mListener;

        public DriveConnectTask(Drive drive, OnConnectListener cl) {
            BluetoothSocket tmp = null;
            try {
                tmp = drive.mDevice.createRfcommSocketToServiceRecord(BT_UUID);
            } catch (IOException socketException) {
                Log.e(TAG, "Failed to create Rfcomm socket", socketException);
            }
            mDrive = drive;
            mSocket = tmp;
            mListener = cl;
        }

        public void run() {
            Connection c;
            try {
                mSocket.connect();
                c = new Connection(mSocket);
            } catch (IOException connectException) {
                Log.e("Foo", "Failed to connect", connectException);
                try {
                    mSocket.close();
                } catch (IOException ignored) {
                }
                if (mListener != null) {
                    mListener.onConnectFailure(connectException);
                }
                return;
            }
            mDrive.mConnection = c;
            if (mListener != null) {
                mListener.onConnect();
            }
        }
    }

    public interface OnConnectListener {
        /**
         * Callback to notify a client when the drive is connected
         */
        public void onConnect();

        /**
         * Callback to notify a client when the drive fails to connect
         */
        public void onConnectFailure(IOException connectException);
    }

    public interface OnConfigurationListener {
        /**
         * Callback to notify a client when configuration is received
         *
         * @param config the configuration received from Bluetooth device, null if failure
         */
        public void onConfigurationRead(DeviceConfiguration config);
    }

    public interface OnLockQueryResultListener {
        /**
         * Callback to notify a client whether a device is unlocked or not
         *
         * @param status         true is device is locked, false otherwise
         * @param queryException if there was an error, this was the exception raised
         */
        public void onLockQueryResult(boolean status, IOException queryException);
    }

    public interface OnLockStateChangeListener {

        /**
         * Callback to notify a client whether an attempt to unlock/lock was successful
         *
         * @param status             true if device is locked, false otherwise
         * @param lockStateException if there was an error, this was the exception raised
         */
        public void onLockStateChanged(boolean status, IOException lockStateException);
    }

    // END BLUETOOTH //////////////////////////////////////////////////////////

    @Override
    public String toString() {
        return mName + " " + mDevice.getAddress();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((mDevice == null) ? 0 : mDevice.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        Drive other = (Drive) obj;
        if (mDevice == null) {
            if (other.mDevice != null) {
                return false;
            }
        } else if (!mDevice.equals(other.mDevice)) {
            return false;
        }
        return true;
    }
}
