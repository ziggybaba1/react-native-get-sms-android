
package com.react;

import android.Manifest;
import android.content.pm.PackageManager;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;

import android.content.ContentValues;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;
import android.app.LoaderManager;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.ContentResolver;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;

import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import androidx.core.content.ContextCompat;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class SmsModule extends ReactContextBaseJavaModule /*implements LoaderManager.LoaderCallbacks<Cursor>*/ {
    //    private LoaderManager mManager;
    private Cursor smsCursor;
    private Map<Long, String> smsList;
    private Map<Long, Object> smsListBody;
    Activity mActivity = null;
    private static Context context;
    private ReactContext mReactContext;
    private Callback cb_autoSend_succ = null;
    private Callback cb_autoSend_err = null;

    public SmsModule(ReactApplicationContext reactContext) {
        super(reactContext);
        mReactContext = reactContext;
        smsList = new HashMap<Long, String>();
        context = reactContext.getApplicationContext();
    }

    @Override
    public String getName() {
        return "Sms";
    }

    @ReactMethod
    public void list(String filter, final Callback errorCallback, final Callback successCallback) {
        try {
            JSONObject filterJ = new JSONObject(filter);
            String uri_filter = filterJ.has("box") ? filterJ.optString("box") : "inbox";
            int fread = filterJ.has("read") ? filterJ.optInt("read") : -1;
            int fid = filterJ.has("_id") ? filterJ.optInt("_id") : -1;
            int ftid = filterJ.has("thread_id") ? filterJ.optInt("thread_id") : -1;
            String faddress = filterJ.optString("address");
            String fcontent = filterJ.optString("body");
            String fContentRegex = filterJ.optString("bodyRegex");
            int indexFrom = filterJ.has("indexFrom") ? filterJ.optInt("indexFrom") : 0;
            int maxCount = filterJ.has("maxCount") ? filterJ.optInt("maxCount") : -1;
            String selection = filterJ.has("selection") ? filterJ.optString("selection") : "";
            String sortOrder = filterJ.has("sortOrder") ? filterJ.optString("sortOrder") : null;
            long maxDate = filterJ.has("maxDate") ? filterJ.optLong("maxDate") : -1;
            long minDate = filterJ.has("minDate") ? filterJ.optLong("minDate") : -1;
            Cursor cursor = context.getContentResolver().query(Uri.parse("content://sms/" + uri_filter), null, selection, null,
                    sortOrder);
            int c = 0;
            JSONArray jsons = new JSONArray();

            while (cursor != null && cursor.moveToNext()) {
                boolean matchFilter = true;
                if (fid > -1)
                    matchFilter = fid == cursor.getInt(cursor.getColumnIndex("_id"));
                else if (ftid > -1)
                    matchFilter = ftid == cursor.getInt(cursor.getColumnIndex("thread_id"));
                else if (fread > -1)
                    matchFilter = fread == cursor.getInt(cursor.getColumnIndex("read"));
                else if (faddress != null && !faddress.isEmpty())
                    matchFilter = faddress.equals(cursor.getString(cursor.getColumnIndex("address")).trim());
                else if (fcontent != null && !fcontent.isEmpty())
                    matchFilter = fcontent.equals(cursor.getString(cursor.getColumnIndex("body")).trim());

                if (fContentRegex != null && !fContentRegex.isEmpty())
                    matchFilter = matchFilter && cursor.getString(cursor.getColumnIndex("body")).matches(fContentRegex);
                if (maxDate > -1)
                    matchFilter = matchFilter && maxDate >= cursor.getLong(cursor.getColumnIndex("date"));
                if (minDate > -1)
                    matchFilter = matchFilter && minDate <= cursor.getLong(cursor.getColumnIndex("date"));
                if (matchFilter) {
                    if (c >= indexFrom) {
                        if (maxCount > 0 && c >= indexFrom + maxCount)
                            break;
                        // Long dateTime = Long.parseLong(cursor.getString(cursor.getColumnIndex("date")));
                        // String message = cursor.getString(cursor.getColumnIndex("body"));
                        JSONObject json;
                        json = getJsonFromCursor(cursor);
                        jsons.put(json);

                    }
                    c++;
                }
            }
            cursor.close();
            try {
                successCallback.invoke(c, jsons.toString());
            } catch (Exception e) {
                errorCallback.invoke(e.getMessage());
            }
        } catch (JSONException e) {
            errorCallback.invoke(e.getMessage());
            return;
        }
    }

    private JSONObject getJsonFromCursor(Cursor cur) {
        JSONObject json = new JSONObject();

        int nCol = cur.getColumnCount();
        String[] keys = cur.getColumnNames();
        try {
            for (int j = 0; j < nCol; j++)
                switch (cur.getType(j)) {
                    case Cursor.FIELD_TYPE_NULL:
                        json.put(keys[j], null);
                        break;
                    case Cursor.FIELD_TYPE_INTEGER:
                        json.put(keys[j], cur.getLong(j));
                        break;
                    case Cursor.FIELD_TYPE_FLOAT:
                        json.put(keys[j], cur.getFloat(j));
                        break;
                    case Cursor.FIELD_TYPE_STRING:
                        json.put(keys[j], cur.getString(j));
                        break;
                    case Cursor.FIELD_TYPE_BLOB:
                        json.put(keys[j], cur.getBlob(j));
                }
        } catch (Exception e) {
            return null;
        }

        return json;
    }

    @ReactMethod
    public void send(String addresses, String text, final Callback errorCallback, final Callback successCallback) {
        mActivity = getCurrentActivity();
        try {
            JSONObject jsonObject = new JSONObject(addresses);
            JSONArray addressList = jsonObject.getJSONArray("addressList");
            int n;
            if ((n = addressList.length()) > 0) {
                PendingIntent sentIntent = PendingIntent.getBroadcast(mActivity, 0, new Intent("SENDING_SMS"), 0);
                SmsManager sms = SmsManager.getDefault();
                for (int i = 0; i < n; i++) {
                    String address;
                    if ((address = addressList.optString(i)).length() > 0)
                        sms.sendTextMessage(address, null, text, sentIntent, null);
                }
            } else {
                PendingIntent sentIntent = PendingIntent.getActivity(mActivity, 0, new Intent("android.intent.action.VIEW"), 0);
                Intent intent = new Intent("android.intent.action.VIEW");
                intent.putExtra("sms_body", text);
                intent.setData(Uri.parse("sms:"));
                try {
                    sentIntent.send(mActivity.getApplicationContext(), 0, intent);
                    successCallback.invoke("OK");
                } catch (PendingIntent.CanceledException e) {
                    errorCallback.invoke(e.getMessage());
                    return;
                }
            }
            return;
        } catch (JSONException e) {
            errorCallback.invoke(e.getMessage());
            return;
        }

    }


    @ReactMethod
    public void delete(Integer id, final Callback errorCallback, final Callback successCallback) {
        try {
            int res = context.getContentResolver().delete(Uri.parse("content://sms/" + id), null, null);
            if (res > 0) {
                successCallback.invoke("OK");
            } else {
                errorCallback.invoke("SMS not found");
            }
            return;
        } catch (Exception e) {
            errorCallback.invoke(e.getMessage());
            return;
        }
    }

    private void sendEvent(ReactContext reactContext, String eventName, String params) {
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(eventName, params);
    }

    private void sendCallback(String message, boolean success) {
        if (success && cb_autoSend_succ != null) {
            cb_autoSend_succ.invoke(message);
            cb_autoSend_succ = null;
        } else if (!success && cb_autoSend_err != null) {
            cb_autoSend_err.invoke(message);
            cb_autoSend_err = null;
        }

    }

    @ReactMethod
public void autoSendMultiSim(Integer simSlot, String phoneNumber, String message,
                            final Callback errorCallback, final Callback successCallback) {
    cb_autoSend_succ = successCallback;
    cb_autoSend_err = errorCallback;
    try {
        // First check for SMS and phone state permissions
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) 
            != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) 
            != PackageManager.PERMISSION_GRANTED) {
            sendCallback("Missing required permissions: SEND_SMS or READ_PHONE_STATE", false);
            return;
        }

        String SENT = "com.smsapp.SMS_SENT";
        String DELIVERED = "com.smsapp.SMS_DELIVERED";
        ArrayList<PendingIntent> sentPendingIntents = new ArrayList<PendingIntent>();
        ArrayList<PendingIntent> deliveredPendingIntents = new ArrayList<PendingIntent>();

        Intent sentIntent = new Intent(SENT);
        Intent deliveredIntent = new Intent(DELIVERED);
        
        String packageName = context.getPackageName();
        sentIntent.setPackage(packageName);
        deliveredIntent.setPackage(packageName);

        int flags;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;
        } else {
            flags = PendingIntent.FLAG_UPDATE_CURRENT;
        }

        PendingIntent sentPI = PendingIntent.getBroadcast(context, 0, sentIntent, flags);
        PendingIntent deliveredPI = PendingIntent.getBroadcast(context, 0, deliveredIntent, flags);

        BroadcastReceiver sentReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context arg0, Intent arg1) {
                switch (getResultCode()) {
                    case Activity.RESULT_OK:
                        sendCallback("SMS sent", true);
                        break;
                    case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                        sendCallback("Generic failure", false);
                        break;
                    case SmsManager.RESULT_ERROR_NO_SERVICE:
                        sendCallback("No service", false);
                        break;
                    case SmsManager.RESULT_ERROR_NULL_PDU:
                        sendCallback("Null PDU", false);
                        break;
                    case SmsManager.RESULT_ERROR_RADIO_OFF:
                        sendCallback("Radio off", false);
                        break;
                }
                try {
                    context.unregisterReceiver(this);
                } catch (IllegalArgumentException e) {
                }
            }
        };

        BroadcastReceiver deliveredReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context arg0, Intent arg1) {
                switch (getResultCode()) {
                    case Activity.RESULT_OK:
                        sendEvent(mReactContext, "sms_onDelivery", "SMS delivered");
                        break;
                    case Activity.RESULT_CANCELED:
                        sendEvent(mReactContext, "sms_onDelivery", "SMS not delivered");
                        break;
                }
                try {
                    context.unregisterReceiver(this);
                } catch (IllegalArgumentException e) {
                }
            }
        };

        IntentFilter sentFilter = new IntentFilter(SENT);
        IntentFilter deliveredFilter = new IntentFilter(DELIVERED);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(sentReceiver, sentFilter, Context.RECEIVER_NOT_EXPORTED);
            context.registerReceiver(deliveredReceiver, deliveredFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(sentReceiver, sentFilter);
            context.registerReceiver(deliveredReceiver, deliveredFilter);
        }

        // Get default SmsManager if we can't access subscription info
        SmsManager sms = SmsManager.getDefault();
        
        // Only try to get specific SIM if we have permission
        if (simSlot != null && simSlot >= 0 && simSlot < 2) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SubscriptionManager subscriptionManager = context.getSystemService(SubscriptionManager.class);
                    if (subscriptionManager != null && subscriptionManager.getActiveSubscriptionInfoCount() > 1) {
                        List<SubscriptionInfo> subscriptions = subscriptionManager.getActiveSubscriptionInfoList();
                        if (subscriptions != null && subscriptions.size() > simSlot) {
                            SubscriptionInfo simInfo = subscriptions.get(simSlot);
                            sms = context.getSystemService(SmsManager.class)
                                .createForSubscriptionId(simInfo.getSubscriptionId());
                        }
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    SubscriptionManager localSubscriptionManager = SubscriptionManager.from(context);
                    if (localSubscriptionManager.getActiveSubscriptionInfoCount() > 1) {
                        List<SubscriptionInfo> localList = localSubscriptionManager.getActiveSubscriptionInfoList();
                        SubscriptionInfo simInfo = localList.get(simSlot);
                        sms = SmsManager.getSmsManagerForSubscriptionId(simInfo.getSubscriptionId());
                    }
                }
            } catch (SecurityException e) {
                // If we can't access subscription info, fall back to default SmsManager
                sms = SmsManager.getDefault();
            }
        }

        ArrayList<String> parts = sms.divideMessage(message);
        for (int i = 0; i < parts.size(); i++) {
            sentPendingIntents.add(i, sentPI);
            deliveredPendingIntents.add(i, deliveredPI);
        }
        sms.sendMultipartTextMessage(phoneNumber, null, parts, sentPendingIntents, deliveredPendingIntents);

        if (ContextCompat.checkSelfPermission(context, "android.permission.WRITE_SMS")  
            == PackageManager.PERMISSION_GRANTED) {
            ContentValues values = new ContentValues();
            values.put("address", phoneNumber);
            values.put("body", message);
            context.getContentResolver().insert(Uri.parse("content://sms/sent"), values);
        }
    } catch (Exception e) {
        sendCallback(e.getMessage(), false);
    }
}


    @ReactMethod
    public void autoSend(String phoneNumber, String message, final Callback errorCallback,
                         final Callback successCallback) {

        cb_autoSend_succ = successCallback;
        cb_autoSend_err = errorCallback;

        try {
            String SENT = "SMS_SENT";
            String DELIVERED = "SMS_DELIVERED";
            ArrayList<PendingIntent> sentPendingIntents = new ArrayList<PendingIntent>();
            ArrayList<PendingIntent> deliveredPendingIntents = new ArrayList<PendingIntent>();

            PendingIntent sentPI = PendingIntent.getBroadcast(context, 0, new Intent(SENT), 0);
            PendingIntent deliveredPI = PendingIntent.getBroadcast(context, 0, new Intent(DELIVERED), 0);

            //---when the SMS has been sent---
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context arg0, Intent arg1) {
                    switch (getResultCode()) {
                        case Activity.RESULT_OK:
                            sendCallback("SMS sent", true);
                            break;
                        case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                            sendCallback("Generic failure", false);
                            break;
                        case SmsManager.RESULT_ERROR_NO_SERVICE:
                            sendCallback("No service", false);
                            break;
                        case SmsManager.RESULT_ERROR_NULL_PDU:
                            sendCallback("Null PDU", false);
                            break;
                        case SmsManager.RESULT_ERROR_RADIO_OFF:
                            sendCallback("Radio off", false);
                            break;
                    }
                }
            }, new IntentFilter(SENT));

            //---when the SMS has been delivered---
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context arg0, Intent arg1) {
                    switch (getResultCode()) {
                        case Activity.RESULT_OK:
                            sendEvent(mReactContext, "sms_onDelivery", "SMS delivered");
                            break;
                        case Activity.RESULT_CANCELED:
                            sendEvent(mReactContext, "sms_onDelivery", "SMS not delivered");
                            break;
                    }
                }
            }, new IntentFilter(DELIVERED));

            SmsManager sms = SmsManager.getDefault();
            ArrayList<String> parts = sms.divideMessage(message);

            for (int i = 0; i < parts.size(); i++) {
                sentPendingIntents.add(i, sentPI);
                deliveredPendingIntents.add(i, deliveredPI);
            }
            sms.sendMultipartTextMessage(phoneNumber, null, parts, sentPendingIntents, deliveredPendingIntents);

            ContentValues values = new ContentValues();
            values.put("address", phoneNumber);
            values.put("body", message);
            context.getContentResolver().insert(Uri.parse("content://sms/sent"), values);

        } catch (Exception e) {
            sendCallback(e.getMessage(), false);
        }
    }
}
