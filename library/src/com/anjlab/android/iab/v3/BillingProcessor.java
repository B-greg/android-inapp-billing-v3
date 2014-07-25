/**
 * Copyright 2014 AnjLab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.anjlab.android.iab.v3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import com.android.vending.billing.IInAppBillingService;

public class BillingProcessor extends BillingBase {

    /**
     * Callback methods where billing events are reported.
     * Apps must implement one of these to construct a BillingProcessor.
     */
    public static interface IBillingHandler {
        void onProductPurchased(String productId, boolean isOwn);
        void onPurchaseHistoryRestored();
        void onBillingError(int errorCode, Throwable error);
        void onBillingInitialized();
        void onReturnData(PurchaseData data);
        void onReturnProductList(List<SkuDetails> skuList);
    }
    
    public static interface IBillingHandlerProduct{
        void onReturnProductList(SkuDetails skuDetails);
    }

    private static final int PURCHASE_FLOW_REQUEST_CODE = 2061984;
    private static final String LOG_TAG = "viable";
    private static final String SETTINGS_VERSION = ".v2_4";
    private static final String RESTORE_KEY = ".products.restored" + SETTINGS_VERSION;
    private static final String MANAGED_PRODUCTS_CACHE_KEY = ".products.cache" + SETTINGS_VERSION;
    private static final String SUBSCRIPTIONS_CACHE_KEY = ".subscriptions.cache" + SETTINGS_VERSION;


    private IInAppBillingService billingService;
    private String contextPackageName;
    private String purchasePayload;
    private String signatureBase64;
    private BillingCache cachedProducts;
    private BillingCache cachedSubscriptions;
    private IBillingHandler eventHandler;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceDisconnected(ComponentName name) {
            billingService = null;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            billingService = IInAppBillingService.Stub.asInterface(service);
            if (!isPurchaseHistoryRestored() && loadOwnedPurchasesFromGoogle()) {
                setPurchaseHistoryRestored();
                if(eventHandler != null)
                    eventHandler.onPurchaseHistoryRestored();
            }
            if(eventHandler != null)
                eventHandler.onBillingInitialized();
        }
    };

    public BillingProcessor(Activity context, String licenseKey, IBillingHandler handler) {
        super(context);
        signatureBase64 = licenseKey;
        eventHandler = handler;
        contextPackageName = context.getApplicationContext().getPackageName();
        cachedProducts = new BillingCache(context, MANAGED_PRODUCTS_CACHE_KEY);
        cachedSubscriptions = new BillingCache(context, SUBSCRIPTIONS_CACHE_KEY);
        bindPlayServices();
    }

    private void bindPlayServices() {
        try {
            getContext().bindService(new Intent("com.android.vending.billing.InAppBillingService.BIND"),  serviceConnection, Context.BIND_AUTO_CREATE);
        }
        catch (Exception e) {
            Log.e(LOG_TAG, e.toString());
        }
    }

    @Override
    public void release() {
        if (serviceConnection != null && getContext() != null) {
            try {
                getContext().unbindService(serviceConnection);
            }
            catch (Exception e) {
                Log.e(LOG_TAG, e.toString());
            }
            billingService = null;
        }
        cachedProducts.release();
        super.release();
    }

    public boolean isInitialized() {
        return billingService != null;
    }

    public boolean isPurchased(String productId) {
        return cachedProducts.includesProduct(productId);
    }

    public boolean isSubscribed(String productId) {
        return cachedSubscriptions.includesProduct(productId);
    }

    public List<String> listOwnedProducts() {
        return cachedProducts.getContents();
    }

    public List<String> listOwnedSubscriptions() {
        return cachedSubscriptions.getContents();
    }

    private boolean loadPurchasesByType(String type, BillingCache cacheStorage) {
        if (!isInitialized())
            return false;
        try {
            Bundle bundle = billingService.getPurchases(Constants.GOOGLE_API_VERSION, contextPackageName, type, null);
            if (bundle.getInt(Constants.RESPONSE_CODE) == Constants.BILLING_RESPONSE_RESULT_OK) {
                cacheStorage.clear();
                for (String purchaseData : bundle.getStringArrayList(Constants.INAPP_PURCHASE_DATA_LIST)) {
                    JSONObject purchase = new JSONObject(purchaseData);
                    cacheStorage.put(purchase.getString("productId"), purchase.getString("purchaseToken"));
                }
            }
            return true;
        }
        catch (Exception e) {
            if(eventHandler != null)
                eventHandler.onBillingError(Constants.BILLING_ERROR_FAILED_LOAD_PURCHASES, e);
            Log.e(LOG_TAG, e.toString());
        }
        return false;
    }

    public boolean loadOwnedPurchasesFromGoogle() {
        return isInitialized() &&
                loadPurchasesByType(Constants.PRODUCT_TYPE_MANAGED, cachedProducts) &&
                loadPurchasesByType(Constants.PRODUCT_TYPE_SUBSCRIPTION, cachedSubscriptions);
    }

    public boolean purchase(String productId) {
        return purchase(productId, Constants.PRODUCT_TYPE_MANAGED, cachedProducts);
    }

    public List<PurchaseData> getPurchasesNotConsumed(){
        List<PurchaseData> list = new ArrayList<PurchaseData>();
        //		if (!isInitialized())
        //			return null;
        eventHandler.onPurchaseHistoryRestored();
        try {
            Bundle bundle = billingService.getPurchases(Constants.GOOGLE_API_VERSION, contextPackageName, Constants.PRODUCT_TYPE_MANAGED, null);
            Log.d(LOG_TAG, billingService.getPurchases(Constants.GOOGLE_API_VERSION, contextPackageName, Constants.PRODUCT_TYPE_MANAGED, null).toString());
            if (bundle.getInt(Constants.RESPONSE_CODE) == Constants.BILLING_RESPONSE_RESULT_OK) {
                Log.d(LOG_TAG, bundle.getStringArrayList(Constants.INAPP_PURCHASE_DATA_LIST).toString());
                for (String purchaseData : bundle.getStringArrayList(Constants.INAPP_PURCHASE_DATA_LIST)) {
                    JSONObject purchase = new JSONObject(purchaseData);
                    PurchaseData purchasedData = new PurchaseData(purchase.getString("packageName"), purchase.getString("orderId"), 
                            purchase.getString("productId"), purchase.getString("developerPayload"), 
                            Integer.toString(purchase.getInt("purchaseTime")), Integer.toString(purchase.getInt("purchaseState")),
                            purchase.getString("purchaseToken"));
                    list.add(purchasedData);
                }
            }
            return list;
        }catch (Exception e) {
            //if(eventHandler != null)
            //eventHandler.onBillingError(Constants.BILLING_ERROR_FAILED_LOAD_PURCHASES, e);
            Log.e(LOG_TAG, e.toString());
        }
        return null;
    }

    public boolean subscribe(String productId) {
        return purchase(productId, Constants.PRODUCT_TYPE_SUBSCRIPTION, cachedSubscriptions);
    }

    private boolean purchase(String productId, String purchaseType, BillingCache cacheStorage) {
        if (!isInitialized())
            return false;
        try {
            purchasePayload = UUID.randomUUID().toString();
            Bundle bundle = billingService.getBuyIntent(Constants.GOOGLE_API_VERSION, contextPackageName, productId, purchaseType, purchasePayload);
            if (bundle != null) {
                int response = bundle.getInt(Constants.RESPONSE_CODE);
                if (response == Constants.BILLING_RESPONSE_RESULT_OK) {
                    PendingIntent pendingIntent = bundle.getParcelable(Constants.BUY_INTENT);
                    if (getContext() != null){   
                    	getContext().startIntentSenderForResult(pendingIntent.getIntentSender(), PURCHASE_FLOW_REQUEST_CODE, new Intent(), Integer.valueOf(0), Integer.valueOf(0), Integer.valueOf(0));
                        
                    }
                     else{
                        if(eventHandler != null)
                            eventHandler.onBillingError(Constants.BILLING_ERROR_LOST_CONTEXT, null);
                     }
                }
                else if (response == Constants.BILLING_RESPONSE_RESULT_ITEM_ALREADY_OWNED) {
                    if (!isPurchased(productId) && !isSubscribed(productId)){
                        loadOwnedPurchasesFromGoogle();
                    }
                    if(eventHandler != null){
                        eventHandler.onProductPurchased(productId, true);
                    }
                }
                else{
                    if(eventHandler != null)
                        eventHandler.onBillingError(Constants.BILLING_ERROR_FAILED_TO_INITIALIZE_PURCHASE, null);
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(LOG_TAG, e.toString());
        }
        return false;
    }

    public boolean consumePurchase(String productId) {
        if (!isInitialized())
            return false;
        try {
            String purchaseToken = cachedProducts.getProductPurchaseToken(productId);
            if (!TextUtils.isEmpty(purchaseToken)) {

                int response =  billingService.consumePurchase(Constants.GOOGLE_API_VERSION, contextPackageName, purchaseToken);
                if (response == Constants.BILLING_RESPONSE_RESULT_OK) {
                    cachedProducts.remove(productId);
                    Log.d(LOG_TAG, "Successfully consumed " + productId + " purchase.");
                    return  true;
                }
                else {
                    if(eventHandler != null)
                        eventHandler.onBillingError(response, null);
                    Log.e(LOG_TAG, String.format("Failed to consume %s: error %d", productId, response));
                }
            }
        }
        catch (Exception e) {
            Log.e(LOG_TAG, e.toString());
        }
        return false;
    }

    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != PURCHASE_FLOW_REQUEST_CODE)
            return false;
        int responseCode = data.getIntExtra(Constants.RESPONSE_CODE, Constants.BILLING_RESPONSE_RESULT_OK);
        if (resultCode == Activity.RESULT_OK && responseCode == Constants.BILLING_RESPONSE_RESULT_OK) {
            String purchaseData = data.getStringExtra(Constants.INAPP_PURCHASE_DATA);
            String dataSignature = data.getStringExtra(Constants.RESPONSE_INAPP_SIGNATURE);
            try {
                JSONObject purchase = new JSONObject(purchaseData);
                Log.d("DATA", purchaseData.toString());
                String productId = purchase.getString("productId");
                String purchaseToken = purchase.getString("purchaseToken");
                String developerPayload = purchase.getString("developerPayload");
                PurchaseData purchaseDate = new PurchaseData(purchase.getString("packageName"), purchase.getString("orderId"), 
                        purchase.getString("productId"), purchase.getString("developerPayload"), 
                        Integer.toString(purchase.getInt("purchaseTime")), Integer.toString(purchase.getInt("purchaseState")),
                        purchase.getString("purchaseToken"));
                if (purchasePayload.equals(developerPayload)) {
                    if (verifyPurchaseSignature(purchaseData, dataSignature)) {
                        cachedProducts.put(productId, purchaseToken);
                        if(eventHandler != null)
                            eventHandler.onProductPurchased(productId, false);
                    }
                    else {
                        Log.e(LOG_TAG, "Public key signature doesn't match!");
                        if(eventHandler != null)
                            eventHandler.onBillingError(Constants.BILLING_ERROR_INVALID_SIGNATURE, null);
                    }
                }
                else {
                    Log.e(LOG_TAG, String.format("Payload mismatch: %s != %s", purchasePayload, developerPayload));
                    if(eventHandler != null)
                        eventHandler.onBillingError(Constants.BILLING_ERROR_INVALID_SIGNATURE, null);
                }

                if(purchaseDate != null) if(eventHandler != null)eventHandler.onReturnData(purchaseDate);
            }
            catch (Exception e) {
                Log.e(LOG_TAG, e.toString());
                if(eventHandler != null)
                    eventHandler.onBillingError(Constants.BILLING_ERROR_OTHER_ERROR, null);
            }
        }
        else {
            if(eventHandler != null)
                eventHandler.onBillingError(Constants.BILLING_ERROR_OTHER_ERROR, null);
        }
        return true;
    }

    public int querySkuDetails(String itemType, ArrayList<String> skuList)
            throws RemoteException, JSONException {
        Log.d(LOG_TAG ,"Querying SKU details.");

        

        if (skuList.size() == 0) {
            Log.d(LOG_TAG ,"queryPrices: nothing to do because there are no SKUs.");
            return Constants.BILLING_RESPONSE_RESULT_OK;
        }

        Bundle querySkus = new Bundle();
        querySkus.putStringArrayList(Constants.GET_SKU_DETAILS_ITEM_LIST, skuList);
        Bundle skuDetails = billingService.getSkuDetails(3, contextPackageName,
                itemType, querySkus);

        if (!skuDetails.containsKey(Constants.RESPONSE_GET_SKU_DETAILS_LIST)) {
            int response = skuDetails.getInt("RESPONSE_CODE");
            if (response != Constants.BILLING_RESPONSE_RESULT_OK) {
                Log.d(LOG_TAG ,"getSkuDetails() failed: " + String.valueOf(response));
                return response;
            }
            else {
                Log.d(LOG_TAG ,"getSkuDetails() returned a bundle with neither an error nor a detail list.");
                return Constants.IABHELPER_BAD_RESPONSE;
            }
        }

        ArrayList<String> responseList = skuDetails.getStringArrayList(
                Constants.RESPONSE_GET_SKU_DETAILS_LIST);

        List<SkuDetails> skuResultList = new ArrayList<SkuDetails>();
        for (String thisResponse : responseList) {
            SkuDetails d = new SkuDetails(itemType, thisResponse);
            Log.d(LOG_TAG , "Got sku details: " + d);
            //inv.addSkuDetails(d);
            skuResultList.add(d);
        }
        eventHandler.onReturnProductList(skuResultList);
        return Constants.BILLING_RESPONSE_RESULT_OK;
    }
    
    public int querySkuDetail(String itemType, String skuName, IBillingHandlerProduct listner)
            throws RemoteException, JSONException {
        Log.d(LOG_TAG ,"Querying SKU details.");

        

        if (skuName == null || skuName.equals("")) {
            Log.d(LOG_TAG ,"queryPrices: nothing to do because there are no SKUs.");
            return Constants.BILLING_RESPONSE_RESULT_OK;
        }
        ArrayList<String> skuList = new ArrayList<String>();
        skuList.add(skuName);

        Bundle querySkus = new Bundle();
        querySkus.putStringArrayList(Constants.GET_SKU_DETAILS_ITEM_LIST, skuList);
        Bundle skuDetails = billingService.getSkuDetails(3, contextPackageName,
                itemType, querySkus);

        if (!skuDetails.containsKey(Constants.RESPONSE_GET_SKU_DETAILS_LIST)) {
            int response = skuDetails.getInt("RESPONSE_CODE");
            if (response != Constants.BILLING_RESPONSE_RESULT_OK) {
                Log.d(LOG_TAG ,"getSkuDetails() failed: " + String.valueOf(response));
                return response;
            }
            else {
                Log.d(LOG_TAG ,"getSkuDetails() returned a bundle with neither an error nor a detail list.");
                return Constants.IABHELPER_BAD_RESPONSE;
            }
        }

        ArrayList<String> responseList = skuDetails.getStringArrayList(
                Constants.RESPONSE_GET_SKU_DETAILS_LIST);

        List<SkuDetails> skuResultList = new ArrayList<SkuDetails>();
        for (String thisResponse : responseList) {
            SkuDetails d = new SkuDetails(itemType, thisResponse);
            Log.d(LOG_TAG , "Got sku details: " + d);
            //inv.addSkuDetails(d);
            skuResultList.add(d);
        }
        if(listner != null)
            listner.onReturnProductList(skuResultList.get(0));
        return Constants.BILLING_RESPONSE_RESULT_OK;
    }



    private boolean verifyPurchaseSignature(String purchaseData, String dataSignature) {
        if (!TextUtils.isEmpty(signatureBase64)) {
            try {
                return Security.verifyPurchase(signatureBase64, purchaseData, dataSignature);
            }
            catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    private boolean isPurchaseHistoryRestored() {
        return loadBoolean(getPreferencesBaseKey() + RESTORE_KEY, false);
    }

    public void setPurchaseHistoryRestored() {
        saveBoolean(getPreferencesBaseKey() + RESTORE_KEY, true);
    }

}
